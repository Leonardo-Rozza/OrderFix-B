package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.*;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.*;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.AccountVerificationNotifier;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.LegalRegistrationSessionIssuer;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.RegistroService;
import jakarta.servlet.ServletInputStream;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real M1/Bean Validation and owner clock; services are doubles, not evidence of database commits. */
class LegalRegistrationHttpBridgeTest {
    static final String KEY = "0a7c6d9b-bd14-4fc5-b828-0ea889dc3bb8";
    static final String REVISION = "sha256:" + "a".repeat(64);
    static final String LEGACY = "{\"nombreTaller\":\"Taller\",\"nombreAdmin\":\"Titular\",\"email\":\"test@example.com\",\"password\":\" exact password \"}";
    static final String COMPLETE = LEGACY.substring(0, LEGACY.length() - 1)
            + ",\"requiredSetRevision\":\"" + REVISION + "\",\"aceptacionesLegales\":[]}";
    static final AuthResponseDto RESPONSE = new AuthResponseDto("synthetic-token", "Bearer", "current@example.com", true);
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    final RegistroService legacy = mock(RegistroService.class);
    final LegalRegistrationService registration = mock(LegalRegistrationService.class);
    final LegalPublicRequirementsReadService requirements = mock(LegalPublicRequirementsReadService.class);
    final LegalRegistrationSessionIssuer sessions = mock(LegalRegistrationSessionIssuer.class);
    final AccountVerificationNotifier notifier = mock(AccountVerificationNotifier.class);
    final AtomicLong clock = new AtomicLong();
    final LegalRegistrationBudget owner = ReflectionTestUtils.invokeMethod(LegalRegistrationBudget.class, "start", (java.util.function.LongSupplier) clock::get);

    @AfterAll static void closeValidators() { VALIDATORS.close(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void genuineAbsencePreservesLegacyAndDoesNotResolveOptionalServices(boolean consent) {
        when(legacy.registrar(any())).thenReturn(RESPONSE);
        var bridge = new LegalRegistrationHttpBridge(legacy, VALIDATORS.getValidator(), consent, false,
                () -> { throw new AssertionError("registration loaded"); },
                () -> { throw new AssertionError("reader loaded"); },
                () -> { throw new AssertionError("metadata loaded"); },
                () -> { throw new AssertionError("session loaded"); }, notifier, () -> owner);
        clock.set(40_000_000_000L);
        assertThat(bridge.register(request(LEGACY, null))).isSameAs(RESPONSE);
        verify(legacy).registrar(argThat(dto -> dto.password().equals(" exact password ") && dto.telefonoTaller() == null));
        verifyNoInteractions(registration, sessions, requirements, notifier);
    }

    @Test void completeDisabledNeverDiscardsEvidenceOrFallsBackToLegacy() {
        rejected(bridge(false, false), request(COMPLETE, KEY), 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        verifyNoInteractions(legacy, registration, sessions, requirements, notifier);
    }

    @Test void partialWithAndWithoutKeyNeverReachesEitherWriter() {
        rejected(bridge(true, false), request(LEGACY, KEY), 400, "ACEPTACION_LEGAL_INVALIDA");
        rejected(bridge(false, false), request(COMPLETE, null), 400, "IDEMPOTENCY_KEY_REQUERIDA");
        verifyNoInteractions(legacy, registration, sessions, requirements, notifier);
    }

    @Test void enforcementUsesTheExistingPublicReaderWithTheSameOwner() throws Exception {
        var current = current(); when(requirements.readRegistration(owner)).thenReturn(current);
        var failure = rejected(bridge(true, true), request(LEGACY, null), 428, "ACEPTACION_LEGAL_REQUERIDA");
        var wire = (LegalPublicRequirementsResponses.Registration) failure.details().get("requisitosActuales");
        assertThat(wire.requiredSetRevision()).isEqualTo(current.requiredSetRevision());
        verify(requirements).readRegistration(owner); verifyNoInteractions(legacy, registration, sessions, notifier);
    }

    @Test void unavailablePublicReaderCannotCreateALegacyAccount() {
        when(requirements.readRegistration(owner)).thenThrow(new IllegalStateException("private SQL"));
        var failure = rejected(bridge(true, true), request(LEGACY, null), 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        assertThat(failure.getMessage()).doesNotContain("private SQL");
        verifyNoInteractions(legacy, registration, sessions, notifier);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void committedIdentityGetsCurrentSessionAndOnlyNewRegistrationGetsWelcome(boolean replay) {
        ready(replay);
        assertThat(bridge(true, false).register(request(COMPLETE, KEY))).isSameAs(RESPONSE);
        var order = inOrder(registration, sessions, notifier);
        order.verify(registration).register(argThat(value -> value.password().equals(" exact password ")),
                eq(KEY), eq(REVISION), eq(List.of()), argThat(value -> value.ipAddress().equals("192.0.2.10")), same(owner));
        order.verify(sessions).issueSession(41L, 72L, " exact password ", owner);
        if (replay) verifyNoInteractions(notifier);
        else order.verify(notifier).notifyVerification(41L, 72L);
        verifyNoInteractions(legacy, requirements);
    }

    @Test void emailRunsAfterTheFinalLegalCheckAndDoesNotConsumeItsBudget() {
        ready(false);
        doAnswer(invocation -> { clock.set(50_000_000_000L); return null; }).when(notifier).notifyVerification(41L, 72L);
        assertThat(bridge(true, false).register(request(COMPLETE, KEY))).isSameAs(RESPONSE);
        assertThat(clock.get()).isEqualTo(50_000_000_000L);
    }

    @Test void sessionRejectionPreservesGeneric401AndDoesNotNotifyOrRetryWrites() {
        ready(true);
        when(sessions.issueSession(anyLong(), anyLong(), anyString(), any())).thenThrow(new BadCredentialsException("private account state"));
        var failure = rejected(bridge(true, false), request(COMPLETE, KEY), 401, null);
        assertThat(failure.getMessage()).isEqualTo("Usuario o contraseña incorrectos");
        verify(registration).register(any(), anyString(), anyString(), anyList(), any(), same(owner));
        verifyNoInteractions(notifier, legacy, requirements);
    }

    @Test void finalDeadlineVetoesEvenAComputedSessionAndSuppressesWelcome() {
        ready(false);
        when(sessions.issueSession(anyLong(), anyLong(), anyString(), any())).thenAnswer(call -> {
            clock.set(30_000_000_000L); return RESPONSE;
        });
        rejected(bridge(true, false), request(COMPLETE, KEY), 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        verifyNoInteractions(notifier);
    }

    @Test void cleanupOrDeadlineDominatesALateAuthenticationRejection() {
        ready(true);
        when(sessions.issueSession(anyLong(), anyLong(), anyString(), any())).thenAnswer(call -> {
            owner.recordCleanupFailure(new IllegalStateException("cleanup private"));
            throw new BadCredentialsException("private");
        });
        rejected(bridge(true, false), request(COMPLETE, KEY), 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        verifyNoInteractions(notifier);
    }

    @Test void timeSpentReadingLegalBodyIsNotResetBeforeTheWriter() {
        var request = new MockHttpServletRequest() {
            @Override public ServletInputStream getInputStream() {
                clock.set(30_000_000_000L); return super.getInputStream();
            }
        };
        populate(request, COMPLETE, KEY);
        rejected(bridge(true, false), request, 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        verifyNoInteractions(registration, legacy, sessions, notifier);
    }

    @Test void expiredLegalAttemptFailsBeforeOpeningItsBody() {
        clock.set(30_000_000_000L);
        var request = noBodyRead(KEY, "application/json");
        rejected(bridge(true, false), request, 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        verifyNoInteractions(legacy, registration, sessions, notifier);
    }

    @Test void aPresentInvalidKeyPrecedesMimeAndBodyOpening() {
        rejected(bridge(false, false), noBodyRead("invalid", "text/plain"), 400, "IDEMPOTENCY_KEY_INVALIDA");
        verifyNoInteractions(legacy, registration);
    }

    @ParameterizedTest @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data", "application/json;charset=UTF-16"})
    void unsupportedTransportIs415WithoutReadingOrWriting(String contentType) {
        rejected(bridge(false, false), noBodyRead(null, contentType), 415, null);
        verifyNoInteractions(legacy, registration, notifier);
    }

    @ParameterizedTest @ValueSource(strings = {"application/json", "Application/Json;charset=UTF-8", "application/vnd.ordenfix+json", "application/problem+json; charset=\"utf8\""})
    void compatibleJsonFamilyAndIgnoredQueryStillPermitLegacy(String contentType) {
        when(legacy.registrar(any())).thenReturn(RESPONSE);
        var request = request(LEGACY, null); request.setContentType(contentType); request.setQueryString("source=landing");
        assertThat(bridge(false, false).register(request)).isSameAs(RESPONSE);
    }

    @Test void serviceConflictKeepsItsContractButUnknownCommitCannotBecomeClientError() {
        var failure = serviceFailure(LegalRegistrationFailure.Reason.IN_PROGRESS);
        when(registration.register(any(), anyString(), anyString(), anyList(), any(), any())).thenThrow(failure);
        var rejected = rejected(bridge(true, false), request(COMPLETE, KEY), 409, "IDEMPOTENCY_EN_PROGRESO");
        assertThat(rejected.retryAfter()).isEqualTo("1");
        when(failure.completion()).thenReturn(LegalRegistrationFailure.Completion.UNKNOWN);
        rejected(bridge(true, false), request(COMPLETE, KEY), 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        verifyNoInteractions(sessions, notifier, legacy);
    }

    @Test void ownerConstructionFailureIsOperationalAndDoesNotReadTheBody() {
        var bridge = new LegalRegistrationHttpBridge(legacy, VALIDATORS.getValidator(), false, false,
                () -> registration, () -> requirements, () -> new LegalRequestMetadataResolver(List.of()),
                () -> sessions, notifier, () -> { throw new IllegalStateException("private clock state"); });
        var failure = rejected(bridge, noBodyRead(null, "application/json"), 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        assertThat(failure.getMessage()).doesNotContain("private clock state");
        verifyNoInteractions(legacy, registration, sessions, notifier);
    }

    @Test void servletHeaderObservationFailureIsSanitizedWithoutOpeningAnyService() {
        var request = new MockHttpServletRequest() {
            @Override public java.util.Enumeration<String> getHeaders(String name) {
                throw new IllegalStateException("private request header state");
            }
        };
        var failure = rejected(bridge(false, false), request, 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        assertThat(failure.getMessage()).doesNotContain("private request header state");
        verifyNoInteractions(legacy, registration, sessions, notifier);
    }

    @Test void invalidMetadataIsOperationalAndNoWriterRuns() {
        var request = request(COMPLETE, KEY); request.setRemoteAddr("hostname.invalid");
        rejected(bridge(true, false), request, 503, "CONTRATO_LEGAL_NO_DISPONIBLE");
        verifyNoInteractions(registration, sessions, notifier);
    }

    @Test void legacyRuntimeRetainsTheExistingErrorHandlerDecision() {
        var original = new BadCredentialsException("legacy"); when(legacy.registrar(any())).thenThrow(original);
        assertThatThrownBy(() -> bridge(false, false).register(request(LEGACY, null))).isSameAs(original);
    }

    LegalRegistrationHttpBridge bridge(boolean consent, boolean enforcement) {
        return new LegalRegistrationHttpBridge(legacy, VALIDATORS.getValidator(), consent, enforcement,
                () -> registration, () -> requirements, () -> new LegalRequestMetadataResolver(List.of()),
                () -> sessions, notifier, () -> owner);
    }
    void ready(boolean replay) {
        var receipt = new LegalRegistrationReceipt(41, 72, replay, UUID.randomUUID(), List.of(UUID.randomUUID()));
        when(registration.register(any(), anyString(), anyString(), anyList(), any(), any())).thenReturn(receipt);
        when(sessions.issueSession(anyLong(), anyLong(), anyString(), any())).thenReturn(RESPONSE);
    }
    static LegalRegistrationFailure serviceFailure(LegalRegistrationFailure.Reason reason) {
        var result = mock(LegalRegistrationFailure.class);
        when(result.reason()).thenReturn(reason); when(result.completion()).thenReturn(LegalRegistrationFailure.Completion.ROLLED_BACK);
        when(result.persistence()).thenReturn(LegalRegistrationFailure.Persistence.NOT_PERSISTED);
        when(result.confirmedReceipt()).thenReturn(Optional.empty()); when(result.validation()).thenReturn(Optional.empty());
        return result;
    }
    static LegalRegistrationHttpException rejected(LegalRegistrationHttpBridge bridge, MockHttpServletRequest request, int status, String code) {
        var failure = catchThrowable(() -> bridge.register(request));
        assertThat(failure).isInstanceOf(LegalRegistrationHttpException.class);
        var rejected = (LegalRegistrationHttpException) failure;
        assertThat(rejected.status().value()).isEqualTo(status); assertThat(rejected.code()).isEqualTo(code); return rejected;
    }
    static MockHttpServletRequest request(String json, String key) {
        var result = new MockHttpServletRequest(); populate(result, json, key); return result;
    }
    private static void populate(MockHttpServletRequest result, String json, String key) {
        result.setMethod("POST"); result.setRequestURI("/api/auth/register"); result.setContentType("application/json");
        result.setRemoteAddr("192.0.2.10"); result.setContent(json.getBytes(StandardCharsets.UTF_8));
        if (key != null) result.addHeader("Idempotency-Key", key);
    }
    private static MockHttpServletRequest noBodyRead(String key, String type) {
        var request = new MockHttpServletRequest() {
            @Override public ServletInputStream getInputStream() { throw new AssertionError("body opened"); }
        };
        request.setContentType(type); if (key != null) request.addHeader("Idempotency-Key", key); return request;
    }
    static LegalPublicRegistrationRequirements current() throws Exception {
        var doc = new DocumentProjection(UUID.fromString("00000000-0000-4000-8000-000000000002"),
                TipoDocumentoLegal.TERMINOS_SERVICIO, "v1", "Términos", "Texto.\n", digest("Texto.\n"),
                OffsetDateTime.parse("2026-09-06T00:00:00Z"), LocaleLegal.ES_AR);
        var requirement = new RequirementProjection(UUID.fromString("00000000-0000-4000-8000-000000000001"),
                ContextoLegal.REGISTRO, TipoActoLegal.ACEPTACION, "Acepto.", digest("Acepto."), List.of(doc), true);
        return new LegalPublicRequirementsValidator().validate(new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR, List.of(requirement)));
    }
    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
