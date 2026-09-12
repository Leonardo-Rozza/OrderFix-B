package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "security.rate-limit.enabled=false", "app.cors.allowed-origins=http://localhost:5173", "exports.jobs.enabled=true", "exports.jobs.active-key-version=1", "exports.jobs.key-versions=1",
        "exports.jobs.keys.v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "spring.config.import=", "spring.config.additional-location=",
        "spring.config.location=optional:classpath:/application.properties",
        "mail.enabled=false", "mercadopago.enabled=false", "mercadopago.checkout-enabled=false",
        "photos.private.enabled=false", "ordenfix.legal.registration-consent.enabled=false",
        "ordenfix.legal.registration-enforcement.enabled=false", "ordenfix.legal.account-read.enabled=false",
        "ordenfix.legal.account-acceptance.enabled=false", "ordenfix.legal.public-documents.enabled=false",
        "ordenfix.legal.public-requirements.enabled=false", "ordenfix.legal.aggregate-context.enabled=false",
        "ordenfix.legal.editorial-context.enabled=false", "ordenfix.legal.import-context.enabled=false",
        "ordenfix.legal.dry-run-context.enabled=false", "ordenfix.legal.public-document-read-context.enabled=false",
        "ordenfix.legal.public-requirements-context.enabled=false"
})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ExportHttpIT {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_export_http").withUsername("ordenfix").withPassword("ordenfix");
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy security;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExportJobService jobs;
    @Autowired ExportWorkPermit permit;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtUtils jwt;
    @MockitoBean(name="exportJobWorker") ExportJobConfiguration.Worker scheduler;
    private final ObjectMapper json=new ObjectMapper();
    private static final String PASSWORD="export-http-synthetic-password";
    private MockMvc mvc;
    private Actor own;
    private String access;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",PG::getJdbcUrl); registry.add("spring.datasource.username",PG::getUsername);
        registry.add("spring.datasource.password",PG::getPassword); registry.add("spring.datasource.driver-class-name",PG::getDriverClassName);
        registry.add("spring.flyway.enabled",()->"true"); registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        registry.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
    }
    @BeforeEach void prepare() {
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.update("DELETE FROM cuenta_exportaciones"); own=actor("ADMIN",true); access=token(own);
    }
    @Test void fullHttpFlowRecoversTheJobAndRequiresAnotherProofForEveryZip() throws Exception {
        auth(get("/api/exportaciones/actual"),access).andExpect(status().isOk())
                .andExpect(jsonPath("$.habilitada").value(true)).andExpect(jsonPath("$.exportacion").isEmpty());
        String proof=grant(access,"EXPORTAR"); UUID key=UUID.randomUUID();
        JsonNode created=node(request(access,proof,key).andExpect(status().isAccepted()));
        String id=created.path("id").asText(); assertThat(created.path("estado").asText()).isEqualTo("QUEUED"); used(proof,true);
        request(access,proof,key).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.reused").value(true));
        auth(get("/api/exportaciones/actual"),token(own)).andExpect(status().isOk()).andExpect(jsonPath("$.exportacion.id").value(id));
        assertThat(jobs.runNext()).isTrue();
        auth(get("/api/exportaciones/"+id),access).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("READY"));
        String download=grant(access,"DESCARGAR_EXPORTACION");
        byte[] bytes=archive(access,download,id).andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Cache-Control",containsString("no-store")))
                .andExpect(header().string("Content-Disposition",containsString(".zip")))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        used(download,true); var entries=unzip(bytes);
        assertThat(json.readTree(entries.get("manifest.json")).path("exportacion_integral_completa").asBoolean()).isTrue();
        assertThat(new String(entries.get("datos/clientes.json"),StandardCharsets.UTF_8)).contains("HTTP_OWN_CUSTOMER");
        archive(access,download,id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REAUTENTICACION_INVALIDA"));
        archive(access,grant(access,"DESCARGAR_EXPORTACION"),id).andExpect(status().isOk());
    }
    @Test void incorrectPasswordHasABusinessErrorWithoutInvalidatingTheSession() throws Exception {
        auth(post("/api/cuenta/reauthenticaciones").contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("passwordActual","wrong-password","proposito","EXPORTAR"))),access)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_ACTUAL_INVALIDA"))
                .andExpect(header().string("Cache-Control",containsString("no-store")));
        auth(get("/api/exportaciones/actual"),access).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"USER","UNVERIFIED"})
    void employeeAndUnverifiedOwnerCannotObtainAProof(String kind) throws Exception {
        Actor denied=actor(kind.equals("USER")?"USER":"ADMIN",!kind.equals("UNVERIFIED"));
        auth(post("/api/cuenta/reauthenticaciones").contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("passwordActual",PASSWORD,"proposito","EXPORTAR"))),token(denied))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_reautenticaciones WHERE user_id=?",Long.class,denied.user())).isZero();
    }
    @Test void anonymousAccessAndTheOldGetCannotReturnAnArchive() throws Exception {
        mvc.perform(get("/api/exportaciones/actual")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control",containsString("no-store")));
        auth(get("/api/exportaciones/actual"),"invalid-token").andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control",containsString("no-store")));
        auth(get("/api/export/excel"),access).andExpect(status().isGone());
        auth(head("/api/export/excel"),access).andExpect(status().is4xxClientError());
        auth(post("/api/export/excel"),access).andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings={
        "{\"passwordActual\":\"one\",\"passwordActual\":\"two\",\"proposito\":\"EXPORTAR\"}",
        "{\"passwordActual\":42,\"proposito\":\"EXPORTAR\"}",
        "{\"passwordActual\":\"abc\",\"proposito\":\"CERRAR\"}",
        "{\"passwordActual\":\"abc\",\"proposito\":\"EXPORTAR\",\"extra\":true}",
        "{\"passwordActual\":\"abc\",\"proposito\":\"EXPORTAR\"} {}"
    })
    void ambiguousOrUnsupportedConfirmationBodiesAreRejected(String body) throws Exception {
        auth(post("/api/cuenta/reauthenticaciones").contentType(APPLICATION_JSON).content(body),access)
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_reautenticaciones WHERE user_id=?",Long.class,own.user())).isZero();
    }
    @Test void oversizedBodyIsRejectedBeforePasswordOrDatabaseWork() throws Exception {
        auth(post("/api/cuenta/reauthenticaciones").contentType(APPLICATION_JSON).content(" ".repeat(4097)),access)
                .andExpect(status().isPayloadTooLarge());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_reautenticaciones WHERE user_id=?",Long.class,own.user())).isZero();
    }
    @Test void foreignWorkshopCannotReadOrDownloadAndKeepsItsProof() throws Exception {
        var created=node(request(access,grant(access,"EXPORTAR"),UUID.randomUUID()).andExpect(status().isAccepted()));
        String id=created.path("id").asText(); jobs.runNext();
        String foreign=token(actor("ADMIN",true)),proof=grant(foreign,"DESCARGAR_EXPORTACION");
        auth(get("/api/exportaciones/"+id),foreign).andExpect(status().isNotFound());
        archive(foreign,proof,id).andExpect(status().isNotFound()); used(proof,false);
        auth(get("/api/exportaciones/actual"),foreign).andExpect(status().isOk()).andExpect(jsonPath("$.exportacion").isEmpty());
    }
    @Test void expiryClearsTheArtifactAndDoesNotConsumeANewDownloadProof() throws Exception {
        String id=node(request(access,grant(access,"EXPORTAR"),UUID.randomUUID()).andExpect(status().isAccepted())).path("id").asText();
        jobs.runNext();
        jdbc.update("UPDATE cuenta_exportaciones SET creada_en=clock_timestamp()-INTERVAL '2 hours', expira_en=clock_timestamp()-INTERVAL '1 hour' WHERE id=?",UUID.fromString(id));
        auth(get("/api/exportaciones/"+id),access).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EXPIRED"));
        String proof=grant(access,"DESCARGAR_EXPORTACION");
        archive(access,proof,id).andExpect(status().isConflict()); used(proof,false);
        assertThat(jdbc.queryForObject("SELECT archive_cipher IS NULL FROM cuenta_exportaciones WHERE id=?",Boolean.class,UUID.fromString(id))).isTrue();
    }
    @Test void corruptedCiphertextRollsBackProofAndReturnsNoPlaintext() throws Exception {
        String id=node(request(access,grant(access,"EXPORTAR"),UUID.randomUUID()).andExpect(status().isAccepted())).path("id").asText(); jobs.runNext();
        jdbc.update("UPDATE cuenta_exportaciones SET archive_cipher=set_byte(archive_cipher,40,get_byte(archive_cipher,40)#1) WHERE id=?",UUID.fromString(id));
        String proof=grant(access,"DESCARGAR_EXPORTACION");
        var response=archive(access,proof,id).andExpect(status().is4xxClientError()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("HTTP_OWN_CUSTOMER",PASSWORD,proof,access); used(proof,false);
    }
    @Test void anOccupiedWorkPermitRejectsDownloadWithoutConsumingTheProof() throws Exception {
        String proof=grant(access,"DESCARGAR_EXPORTACION");
        try(var occupied=permit.tryAcquire()) {
            assertThat(occupied).isNotNull();
            auth(post("/api/export/excel").header("X-Reauth-Token",proof),access)
                    .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
            used(proof,false);
        }
        auth(post("/api/export/excel").header("X-Reauth-Token",proof),access).andExpect(status().isOk()); used(proof,true);
    }
    @Test void theOperationalWorkbookAlsoRequiresAFreshDownloadConfirmation() throws Exception {
        Actor foreign=actor("ADMIN",true);
        jdbc.update("UPDATE clientes SET nombre='HTTP_FOREIGN_CUSTOMER' WHERE taller_id=?",foreign.workshop());
        String proof=grant(access,"DESCARGAR_EXPORTACION");
        byte[] bytes=auth(post("/api/export/excel").header("X-Reauth-Token",proof),access)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control",containsString("no-store")))
                .andExpect(header().string("Content-Disposition",containsString(".xlsx"))).andReturn().getResponse().getContentAsByteArray();
        used(proof,true);
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var values=new StringBuilder(); workbook.getSheet("Clientes").forEach(row->row.forEach(cell->values.append(cell.toString())));
            assertThat(values.toString()).contains("HTTP_OWN_CUSTOMER").doesNotContain("HTTP_FOREIGN_CUSTOMER");
        }
        auth(post("/api/export/excel").header("X-Reauth-Token",proof),access).andExpect(status().isBadRequest());
    }
    @Test void theSixthConfirmationIsLimitedEvenIfPublicRateLimitingIsDisabled() throws Exception {
        for(int i=0;i<5;i++) grant(access,"DESCARGAR_EXPORTACION");
        auth(post("/api/cuenta/reauthenticaciones").contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("passwordActual",PASSWORD,"proposito","EXPORTAR"))),access)
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("EXPORTACION_LIMITE"))
                .andExpect(header().exists("Retry-After"));
    }
    @Test void theFrontendOriginCanPreflightTheProtectedHeadersWithoutASecret() throws Exception {
        mvc.perform(options("/api/cuenta/reauthenticaciones")
                .header("Origin","http://localhost:5173")
                .header("Access-Control-Request-Method","POST")
                .header("Access-Control-Request-Headers","authorization,content-type,x-reauth-token,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin","http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers",containsString("x-reauth-token")));
    }
    private ResultActions request(String token,String proof,UUID key)throws Exception {
        return auth(post("/api/exportaciones").header("X-Reauth-Token",proof).header("Idempotency-Key",key.toString()),token);
    }
    private ResultActions archive(String token,String proof,String id)throws Exception {
        return auth(post("/api/exportaciones/"+id+"/archivo").header("X-Reauth-Token",proof),token);
    }
    private ResultActions auth(MockHttpServletRequestBuilder builder,String token)throws Exception {
        return mvc.perform(builder.header("Authorization","Bearer "+token));
    }
    private JsonNode node(ResultActions response)throws Exception { return json.readTree(response.andReturn().getResponse().getContentAsString()); }
    private String grant(String token,String purpose)throws Exception {
        var response=auth(post("/api/cuenta/reauthenticaciones").contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("passwordActual",PASSWORD,"proposito",purpose))),token)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control",containsString("no-store")));
        return node(response).path("reauthToken").asText();
    }
    private Actor actor(String role,boolean verified) {
        long workshop=jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('Export HTTP') RETURNING id",Long.class);
        long user=jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES('HTTP actor',?,?,?, ?,true,?,0) RETURNING id",Long.class,
                UUID.randomUUID()+"@export.synthetic.invalid",encoder.encode(PASSWORD),role,workshop,verified);
        jdbc.update("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('HTTP_OWN_CUSTOMER','Fixture','0000000000',?)",workshop);
        return new Actor(user,workshop);
    }
    private String token(Actor actor) { return jwt.generateToken(new AuthenticatedUserPrincipal(users.findSessionByIdAndTallerId(actor.user(),actor.workshop()).orElseThrow()),actor.workshop()); }
    private void used(String proof,boolean expected) {
        assertThat(jdbc.queryForObject("SELECT usada_en IS NOT NULL FROM cuenta_reautenticaciones WHERE token_hash=?",Boolean.class,ExportFile.digest(proof.getBytes(StandardCharsets.UTF_8)))).isEqualTo(expected);
    }
    private static Map<String,byte[]> unzip(byte[] bytes)throws IOException {
        var entries=new LinkedHashMap<String,byte[]>(); try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) entries.put(entry.getName(),zip.readAllBytes());
        } return entries;
    }
    private record Actor(long user,long workshop) { }
}
