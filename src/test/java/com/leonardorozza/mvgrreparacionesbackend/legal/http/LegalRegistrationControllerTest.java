package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.controller.AuthController;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationFailure;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.AuthService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.CuentaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual MVC mappings and error wire; persistence and the bridge are doubles in this suite. */
class LegalRegistrationControllerTest {
    final AuthService auth = mock(AuthService.class);
    final CuentaService account = mock(CuentaService.class);
    final LegalRegistrationHttpBridge registration = mock(LegalRegistrationHttpBridge.class);
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, registration, account))
                .setControllerAdvice(new LegalRegistrationExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @Test void oneRegistrationHandlerKeeps201AuthShapeAndNeverCachesItsToken() throws Exception {
        when(registration.register(any())).thenReturn(LegalRegistrationHttpBridgeTest.RESPONSE);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(LegalRegistrationHttpBridgeTest.LEGACY).header(HttpHeaders.ACCEPT, "text/plain"))
                .andExpect(status().isCreated()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.token").value("synthetic-token"))
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.email").value("current@example.com"))
                .andExpect(jsonPath("$.emailVerificado").value(true))
                .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.lotId").doesNotExist());
        verify(registration).register(any()); verifyNoInteractions(auth, account);
        var mappings = mvc.getDispatcherServlet().getWebApplicationContext()
                .getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
                .getHandlerMethods().keySet().stream()
                .filter(mapping -> mapping.getPatternValues().contains("/api/auth/register")).toList();
        org.assertj.core.api.Assertions.assertThat(mappings).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(mappings.getFirst().getMethodsCondition().getMethods())
                .containsExactly(org.springframework.web.bind.annotation.RequestMethod.POST);
    }

    @Test void legal400IsJsonEvenForIncompatibleAcceptAndDoesNotExposeInputOrCause() throws Exception {
        when(registration.register(any())).thenThrow(LegalRegistrationHttpException.invalidPayload());
        mvc.perform(post("/api/auth/register").accept(MediaType.TEXT_HTML).content("private-password"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("ACEPTACION_LEGAL_INVALIDA"))
                .andExpect(jsonPath("$.details.motivos[0]").value("PAYLOAD_LEGAL_INCOMPLETO"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-password"))));
    }

    @Test void inProgressPreservesRetryAfterAndOperational503NeverLeaksTheInternalCause() throws Exception {
        var failure = LegalRegistrationHttpBridgeTest.serviceFailure(LegalRegistrationFailure.Reason.IN_PROGRESS);
        // Translate before starting Mockito stubbing: its failure getters are themselves mocked.
        var conflict = LegalRegistrationHttpException.from(failure);
        org.assertj.core.api.Assertions.assertThat(conflict.status().value()).isEqualTo(409);
        doThrow(conflict).when(registration).register(any());
        mvc.perform(post("/api/auth/register")).andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_EN_PROGRESO"))
                .andExpect(jsonPath("$.details.operacion").value("REGISTRO"));
        var unavailable = LegalRegistrationHttpException.unavailable(new IllegalStateException("SQL private"));
        doThrow(unavailable).when(registration).register(any());
        mvc.perform(post("/api/auth/register").accept(MediaType.TEXT_PLAIN)).andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("CONTRATO_LEGAL_NO_DISPONIBLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL private"))));
    }

    @Test void requiredAcceptancePresentsThePublicSnapshotWithoutChangingRegistrationRoute() throws Exception {
        var current = LegalRegistrationHttpBridgeTest.current();
        when(registration.register(any())).thenThrow(LegalRegistrationHttpException.requiredAcceptance(current));
        mvc.perform(post("/api/auth/register")).andExpect(status().isPreconditionRequired())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("ACEPTACION_LEGAL_REQUERIDA"))
                .andExpect(jsonPath("$.details.requisitosActuales.requiredSetRevision").value(current.requiredSetRevision()))
                .andExpect(jsonPath("$.details.requisitosActuales.requisitos[0].documentos[0].contenidoMarkdown").value("Texto.\n"));
    }

    @Test void legacyAuthenticationFailureStillUsesGlobalAdviceAndKeepsNoStore() throws Exception {
        when(registration.register(any())).thenThrow(new BadCredentialsException("private"));
        mvc.perform(post("/api/auth/register")).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value("Usuario o contraseña incorrectos"));
    }

    @Test void loginAndAccountEndpointsRetainTheirOriginalServicesAndErrorHandling() throws Exception {
        when(auth.login(anyString(), anyString())).thenThrow(new BadCredentialsException("private"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Usuario o contraseña incorrectos"));
        mvc.perform(post("/api/auth/password/olvide").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isOk());
        verify(auth).login("user@example.com", "password"); verify(account).olvidePassword("user@example.com");
        verifyNoInteractions(registration);
    }
}
