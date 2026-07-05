package com.leonardorozza.mvgrreparacionesbackend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Base64;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base para tests de integración (MockMvc sobre el stack real + H2).
 * Provee helpers de registro/login, autorización por header y atajos de JSON
 * para que cada test se lea como el flujo de negocio que prueba.
 *
 * No es transaccional a propósito: cada request corre en su propia transacción
 * (como en producción), así un 402/400 esperado no invalida el resto del test.
 * Para evitar choques, cada test usa emails/talleres únicos.
 */
@SpringBootTest
public abstract class IntegrationTestBase {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    protected SuscripcionRepository suscripcionRepository;

    /** Captura los emails "enviados" (reemplaza al SMTP real vía @Primary). */
    @Autowired
    protected RecordingEmailSender emails;

    protected MockMvc mvc;

    protected final ObjectMapper om = new ObjectMapper();

    private static final String AUTH = "Authorization";

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    /** Registra un taller nuevo (con su admin) y devuelve el token JWT. */
    protected String registrar(String nombreTaller, String email) throws Exception {
        String body = json(Map.of(
                "nombreTaller", nombreTaller,
                "telefonoTaller", "1100000000",
                "nombreAdmin", "Admin",
                "email", email,
                "password", "secret123"));
        String resp = mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return token(resp);
    }

    protected String login(String email, String password) throws Exception {
        String body = json(Map.of("email", email, "password", password));
        String resp = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return token(resp);
    }

    /** Sube el taller (dueño del token) a plan PRO, para probar funciones PRO. */
    protected void activarPro(String token) throws Exception {
        long tallerId = tallerIdFromToken(token);
        Suscripcion s = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        s.setPlan(PlanType.PRO);
        suscripcionRepository.save(s);
    }

    // ---- HTTP helpers (autenticados) ----

    protected ResultActions authGet(String url, String token) throws Exception {
        return mvc.perform(get(url).header(AUTH, bearer(token)));
    }

    protected ResultActions authPost(String url, String token, String body) throws Exception {
        return mvc.perform(post(url).header(AUTH, bearer(token)).contentType(APPLICATION_JSON).content(body));
    }

    protected ResultActions authPost(String url, String token) throws Exception {
        return mvc.perform(post(url).header(AUTH, bearer(token)));
    }

    protected ResultActions authPut(String url, String token, String body) throws Exception {
        return mvc.perform(put(url).header(AUTH, bearer(token)).contentType(APPLICATION_JSON).content(body));
    }

    protected ResultActions authPatch(String url, String token, String body) throws Exception {
        return mvc.perform(patch(url).header(AUTH, bearer(token)).contentType(APPLICATION_JSON).content(body));
    }

    protected ResultActions authDelete(String url, String token) throws Exception {
        return mvc.perform(delete(url).header(AUTH, bearer(token)));
    }

    // ---- JSON helpers ----

    protected String json(Object value) throws Exception {
        return om.writeValueAsString(value);
    }

    protected JsonNode node(ResultActions actions) throws Exception {
        return om.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    protected long idOf(ResultActions actions) throws Exception {
        return node(actions).get("id").asLong();
    }

    private String token(String authResponse) throws Exception {
        return om.readTree(authResponse).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private long tallerIdFromToken(String jwt) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        return om.readTree(payload).get("tallerId").asLong();
    }
}
