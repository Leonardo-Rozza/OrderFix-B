package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceInputException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Membership;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Scope;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Snapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentLine;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentReference;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.EvidenceDocument;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementLine;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementSatisfactionEvaluator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceFailure;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceReceipt;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Motivo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Transport classification without HTTP registration, persistence, or manufactured commit outcomes. */
class LegalAcceptanceHttpExceptionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUBMITTED = "sha256:" + "a".repeat(64);
    private static final String SECRET = "SQL_SECRET_password=synthetic IP=192.0.2.200 UA=private-agent HMAC=private-hmac";

    @Test
    void keyErrorsIdentifyOnlyTheRequiredHeader() throws Exception {
        var required = LegalAcceptanceHttpException.requiredKey();
        var invalid = LegalAcceptanceHttpException.invalidKey();
        fields(required, HttpStatus.BAD_REQUEST, "Solicitud inválida", "IDEMPOTENCY_KEY_REQUERIDA",
                Map.of("header", "Idempotency-Key"), null);
        fields(invalid, HttpStatus.BAD_REQUEST, "Solicitud inválida", "IDEMPOTENCY_KEY_INVALIDA",
                Map.of("header", "Idempotency-Key"), null);
        assertThatThrownBy(() -> required.details().put("header", "other")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> invalid.details().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void incompletePayloadUsesTheSingleStableMotivo() throws Exception {
        var rejected = LegalAcceptanceHttpException.invalidPayload();
        fields(rejected, HttpStatus.BAD_REQUEST, "Solicitud inválida", "ACEPTACION_LEGAL_INVALIDA",
                Map.of("motivos", List.of("PAYLOAD_LEGAL_INCOMPLETO")), null);
        assertThatThrownBy(() -> ((List<?>) rejected.details().get("motivos")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void authenticationAndAuthorizationKeepGenericEnvelopeFieldsAndInternalCauses() throws Exception {
        Throwable cause = new SQLException(SECRET, "42501");
        var unauthorized = LegalAcceptanceHttpException.unauthorized(cause);
        var forbidden = LegalAcceptanceHttpException.forbidden(cause);
        fields(unauthorized, HttpStatus.UNAUTHORIZED, "No autorizado", null, null, null);
        fields(forbidden, HttpStatus.FORBIDDEN, "Acceso denegado", null, null, null);
        assertThat(unauthorized.getCause()).isSameAs(cause);
        assertThat(forbidden.getCause()).isSameAs(cause);
    }

    @Test
    void unavailableRetainsAnExplicitNullContextAndExposesNoDiagnostics() throws Exception {
        Throwable cause = new SQLException(SECRET, "08006");
        var rejected = LegalAcceptanceHttpException.unavailable(cause);
        unavailable(rejected);
        assertThat(rejected.getCause()).isSameAs(cause);
        assertThat(JSON.valueToTree(rejected.details()).get("contexto").isNull()).isTrue();
        assertThatThrownBy(() -> rejected.details().put("contexto", "REGISTRO"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = LegalAcceptanceFailure.Reason.class,
            names = {"INVALID_ACTOR", "INVALID_PAYLOAD", "KEY_REUSED", "IN_PROGRESS", "UNAVAILABLE"})
    void conclusiveRollbackMapsTheNonSemanticServiceDecisions(LegalAcceptanceFailure.Reason reason) throws Exception {
        var rejected = LegalAcceptanceHttpException.from(failure(reason));
        switch (reason) {
            case INVALID_ACTOR -> fields(rejected, HttpStatus.UNAUTHORIZED, "No autorizado", null, null, null);
            case INVALID_PAYLOAD -> fields(rejected, HttpStatus.BAD_REQUEST, "Solicitud inválida", "ACEPTACION_LEGAL_INVALIDA",
                    Map.of("motivos", List.of("PAYLOAD_LEGAL_INCOMPLETO")), null);
            case KEY_REUSED -> fields(rejected, HttpStatus.CONFLICT, "Conflicto", "IDEMPOTENCY_KEY_REUTILIZADA",
                    Map.of("operacion", "ACEPTACION_LEGAL"), null);
            case IN_PROGRESS -> fields(rejected, HttpStatus.CONFLICT, "Conflicto", "IDEMPOTENCY_EN_PROGRESO",
                    Map.of("operacion", "ACEPTACION_LEGAL"), "1");
            case UNAVAILABLE -> unavailable(rejected);
            default -> throw new AssertionError(reason);
        }
    }

    @ParameterizedTest @EnumSource(LegalAcceptanceFailure.Reason.class)
    void onlyPreTransactionIdentityFailureCanBeClassifiedWithoutObservedRollback(LegalAcceptanceFailure.Reason reason)
            throws Exception {
        var failure = coherentFailure(reason);
        when(failure.completion()).thenReturn(LegalAcceptanceFailure.Completion.NONE);
        var rejected = LegalAcceptanceHttpException.from(failure);
        if (reason == LegalAcceptanceFailure.Reason.INVALID_ACTOR) {
            fields(rejected, HttpStatus.UNAUTHORIZED, "No autorizado", null, null, null);
        } else unavailable(rejected);
    }

    @ParameterizedTest @EnumSource(LegalAcceptanceFailure.Reason.class)
    void commitOrPersistenceUncertaintyAlwaysOverridesTheBusinessReason(LegalAcceptanceFailure.Reason reason)
            throws Exception {
        for (var completion : List.of(LegalAcceptanceFailure.Completion.COMMITTED, LegalAcceptanceFailure.Completion.UNKNOWN)) {
            var failure = coherentFailure(reason);
            when(failure.completion()).thenReturn(completion);
            unavailable(LegalAcceptanceHttpException.from(failure));
        }
        for (var persistence : List.of(LegalAcceptanceFailure.Persistence.PERSISTED, LegalAcceptanceFailure.Persistence.UNKNOWN)) {
            var failure = coherentFailure(reason);
            when(failure.persistence()).thenReturn(persistence);
            unavailable(LegalAcceptanceHttpException.from(failure));
        }
    }

    @Test
    void contradictoryConfirmedReceiptNeverEscapesAsAClientConflict() throws Exception {
        var failure = failure(LegalAcceptanceFailure.Reason.KEY_REUSED);
        UUID lot = UUID.randomUUID(), act = UUID.randomUUID();
        when(failure.confirmedReceipt()).thenReturn(Optional.of(
                new LegalAcceptanceReceipt(LegalAcceptanceReceipt.Kind.WITH_ACTS, false, lot, List.of(act))));
        var rejected = LegalAcceptanceHttpException.from(failure);
        unavailable(rejected);
        assertThat(JSON.writeValueAsString(rejected.details())).doesNotContain(lot.toString(), act.toString());
    }

    @Test
    void unknownFailureCannotInventASemanticDecision() throws Exception {
        unavailable(LegalAcceptanceHttpException.from(null));
        var absentReason = failure(LegalAcceptanceFailure.Reason.UNAVAILABLE);
        when(absentReason.reason()).thenReturn(null);
        unavailable(LegalAcceptanceHttpException.from(absentReason));
    }

    @Test
    void staleIncludesOnlyTheLiteralPrivatePendingProjectionWithItsCompleteRevision() throws Exception {
        var current = projection(Set.of(id(30)));
        var failure = failure(LegalAcceptanceFailure.Reason.STALE);
        doReturn(Optional.of(staleValidation(current))).when(failure).validation();
        var rejected = LegalAcceptanceHttpException.from(failure);
        assertThat(rejected.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.error()).isEqualTo("Conflicto");
        assertThat(rejected.code()).isEqualTo("DOCUMENTOS_LEGALES_DESACTUALIZADOS");
        assertThat(rejected.retryAfter()).isNull();
        assertThat(rejected.details()).containsOnlyKeys("submittedRevision", "requisitosActuales")
                .containsEntry("submittedRevision", SUBMITTED);
        assertThat(rejected.details().get("requisitosActuales")).isExactlyInstanceOf(LegalPrivateRequirementsResponses.Pending.class);
        var pending = (LegalPrivateRequirementsResponses.Pending) rejected.details().get("requisitosActuales");
        assertThat(pending.locale()).isEqualTo("es-AR");
        assertThat(pending.requiredSetRevision()).isEqualTo(current.requiredSetRevision())
                .isEqualTo(projection(Set.of()).requiredSetRevision());
        assertThat(pending.requisitos()).extracting(LegalPrivateRequirementsResponses.Requisito::id)
                .containsExactly(id(10).toString(), id(20).toString());
        assertThat(pending.requisitos().getFirst().requerido()).isFalse();
        assertThat(pending.requisitos().getLast().contexto()).isEqualTo("CIERRE_CUENTA");
        JsonNode wire = JSON.valueToTree(rejected.details());
        JsonNode body = wire.get("requisitosActuales");
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("locale", "requiredSetRevision", "requisitos");
        JsonNode requirement = body.get("requisitos").get(0);
        assertThat(fieldNames(requirement)).containsExactlyInAnyOrder("id", "contexto", "tipoActo", "afirmacion",
                "afirmacionSha256", "requerido", "documentos");
        JsonNode document = requirement.get("documentos").get(0);
        assertThat(fieldNames(document)).containsExactlyInAnyOrder("id", "tipo", "version", "titulo", "contenidoMarkdown",
                "sha256", "vigenteDesde", "estado", "locale");
        assertThat(document.get("vigenteDesde").asText()).isEqualTo("2026-09-06T03:00:00Z");
        assertThat(document.get("contenidoMarkdown").asText()).isEqualTo("# Documento 100\n\nTexto legal de prueba.\n");
        assertThat(document.get("sha256").asText()).isEqualTo(sha(document.get("contenidoMarkdown").asText()));
        assertThatThrownBy(() -> rejected.details().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> pending.requisitos().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> pending.requisitos().getFirst().documentos().clear()).isInstanceOf(UnsupportedOperationException.class);
        safe(rejected);
        assertThat(wire.toString()).doesNotContain(id(30).toString(), id(3030).toString());
    }

    @Test
    void validStaleObservationMayHaveNoPendingRequirements() throws Exception {
        var current = projection(Set.of(id(30), id(10), id(20)));
        var failure = failure(LegalAcceptanceFailure.Reason.STALE);
        doReturn(Optional.of(staleValidation(current))).when(failure).validation();
        var rejected = LegalAcceptanceHttpException.from(failure);
        assertThat(rejected.status()).isEqualTo(HttpStatus.CONFLICT);
        var pending = (LegalPrivateRequirementsResponses.Pending) rejected.details().get("requisitosActuales");
        assertThat(pending.requisitos()).isEmpty();
        assertThat(pending.requiredSetRevision()).isEqualTo(current.requiredSetRevision());
        safe(rejected);
    }

    @Test
    void semanticMotivosAreDetachedDeduplicatedAndReturnedInFrozenEnumOrder() throws Exception {
        List<Motivo> supplied = new ArrayList<>(List.of(Motivo.PAYLOAD_LEGAL_INCOMPLETO, Motivo.DOCUMENTO_DUPLICADO,
                Motivo.REQUISITO_FALTANTE, Motivo.DOCUMENTO_DUPLICADO, Motivo.CONFIRMACION_REQUERIDA));
        var validation = invalidValidation(supplied);
        var failure = failure(LegalAcceptanceFailure.Reason.INVALID);
        doReturn(Optional.of(validation)).when(failure).validation();
        var rejected = LegalAcceptanceHttpException.from(failure);
        supplied.clear();
        fields(rejected, HttpStatus.BAD_REQUEST, "Solicitud inválida", "ACEPTACION_LEGAL_INVALIDA",
                Map.of("motivos", List.of("REQUISITO_FALTANTE", "DOCUMENTO_DUPLICADO", "CONFIRMACION_REQUERIDA",
                        "PAYLOAD_LEGAL_INCOMPLETO")), null);
        assertThatThrownBy(() -> ((List<?>) rejected.details().get("motivos")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "reason", "submitted", "current", "null-list", "empty-list", "null-motivo", "oversized"})
    void invalidNeedsAConsistentBoundedValidationResult(String defect) throws Exception {
        var failure = failure(LegalAcceptanceFailure.Reason.INVALID);
        var validation = invalidValidation(List.of(Motivo.REQUISITO_FALTANTE));
        switch (defect) {
            case "missing" -> { unavailable(LegalAcceptanceHttpException.from(failure)); return; }
            case "reason" -> when(validation.reason()).thenReturn(LegalAcceptanceValidationException.Reason.STALE);
            case "submitted" -> when(validation.submittedRevision()).thenReturn(SUBMITTED);
            case "current" -> when(validation.currentRequirements()).thenReturn(projection(Set.of()));
            case "null-list" -> when(validation.motivos()).thenReturn(null);
            case "empty-list" -> when(validation.motivos()).thenReturn(List.of());
            case "null-motivo" -> when(validation.motivos()).thenReturn(Arrays.asList(Motivo.REQUISITO_FALTANTE, null));
            case "oversized" -> when(validation.motivos()).thenReturn(Collections.nCopies(11, Motivo.REQUISITO_FALTANTE));
            default -> throw new AssertionError(defect);
        }
        doReturn(Optional.of(validation)).when(failure).validation();
        unavailable(LegalAcceptanceHttpException.from(failure));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "reason", "null-submitted", "uppercase-submitted", "same-revision",
            "null-current", "broken-snapshot", "null-motivos", "nonempty-motivos"})
    void staleNeedsADifferentCanonicalRevisionAndAnAccreditedPrivateProjection(String defect) throws Exception {
        var current = projection(Set.of());
        var failure = failure(LegalAcceptanceFailure.Reason.STALE);
        var validation = staleValidation(current);
        switch (defect) {
            case "missing" -> { unavailable(LegalAcceptanceHttpException.from(failure)); return; }
            case "reason" -> when(validation.reason()).thenReturn(LegalAcceptanceValidationException.Reason.INVALID);
            case "null-submitted" -> when(validation.submittedRevision()).thenReturn(null);
            case "uppercase-submitted" -> when(validation.submittedRevision()).thenReturn("sha256:" + "A".repeat(64));
            case "same-revision" -> when(validation.submittedRevision()).thenReturn(current.requiredSetRevision());
            case "null-current" -> when(validation.currentRequirements()).thenReturn(null);
            case "broken-snapshot" -> when(validation.currentRequirements()).thenReturn(mock(LegalAuthenticatedRequirements.class));
            case "null-motivos" -> when(validation.motivos()).thenReturn(null);
            case "nonempty-motivos" -> when(validation.motivos()).thenReturn(List.of(Motivo.REQUISITO_FALTANTE));
            default -> throw new AssertionError(defect);
        }
        doReturn(Optional.of(validation)).when(failure).validation();
        unavailable(LegalAcceptanceHttpException.from(failure));
    }

    @ParameterizedTest
    @EnumSource(value = LegalAcceptanceFailure.Reason.class,
            names = {"INVALID_ACTOR", "INVALID_PAYLOAD", "KEY_REUSED", "IN_PROGRESS", "UNAVAILABLE"})
    void nonSemanticReasonCannotCarryAnUnrelatedValidation(LegalAcceptanceFailure.Reason reason) throws Exception {
        var failure = failure(reason);
        doReturn(Optional.of(invalidValidation(List.of(Motivo.DOCUMENTO_FALTANTE)))).when(failure).validation();
        unavailable(LegalAcceptanceHttpException.from(failure));
    }

    @ParameterizedTest @EnumSource(LegalAcceptanceInputException.Reason.class)
    void neutralInputCodesRequireConclusiveRollbackAndNoValidation(LegalAcceptanceInputException.Reason input)
            throws Exception {
        var failure = failure(LegalAcceptanceFailure.Reason.INVALID_PAYLOAD);
        when(failure.inputReason()).thenReturn(Optional.of(input));
        var rejected = LegalAcceptanceHttpException.from(failure);
        switch (input) {
            case REQUIRED_KEY -> fields(rejected, HttpStatus.BAD_REQUEST, "Solicitud inválida",
                    "IDEMPOTENCY_KEY_REQUERIDA", Map.of("header", "Idempotency-Key"), null);
            case INVALID_KEY -> fields(rejected, HttpStatus.BAD_REQUEST, "Solicitud inválida",
                    "IDEMPOTENCY_KEY_INVALIDA", Map.of("header", "Idempotency-Key"), null);
            case INVALID_PAYLOAD -> fields(rejected, HttpStatus.BAD_REQUEST, "Solicitud inválida",
                    "ACEPTACION_LEGAL_INVALIDA", Map.of("motivos", List.of("PAYLOAD_LEGAL_INCOMPLETO")), null);
        }
        for (var completion : List.of(LegalAcceptanceFailure.Completion.NONE,
                LegalAcceptanceFailure.Completion.COMMITTED, LegalAcceptanceFailure.Completion.UNKNOWN)) {
            when(failure.completion()).thenReturn(completion);
            unavailable(LegalAcceptanceHttpException.from(failure));
        }
        when(failure.completion()).thenReturn(LegalAcceptanceFailure.Completion.ROLLED_BACK);
        for (var persistence : List.of(LegalAcceptanceFailure.Persistence.PERSISTED,
                LegalAcceptanceFailure.Persistence.UNKNOWN)) {
            when(failure.persistence()).thenReturn(persistence);
            unavailable(LegalAcceptanceHttpException.from(failure));
        }
        when(failure.persistence()).thenReturn(LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
        var validation = invalidValidation(List.of(Motivo.CONFIRMACION_REQUERIDA));
        when(failure.validation()).thenReturn(Optional.of(validation));
        unavailable(LegalAcceptanceHttpException.from(failure));
    }

    @ParameterizedTest @EnumSource(value = LegalAcceptanceFailure.Reason.class,
            names = "INVALID_PAYLOAD", mode = EnumSource.Mode.EXCLUDE)
    void neutralInputCannotOverrideAnotherServiceDecision(LegalAcceptanceFailure.Reason reason) throws Exception {
        var failure = coherentFailure(reason);
        when(failure.inputReason()).thenReturn(Optional.of(LegalAcceptanceInputException.Reason.INVALID_KEY));
        unavailable(LegalAcceptanceHttpException.from(failure));
    }

    private static LegalAcceptanceFailure coherentFailure(LegalAcceptanceFailure.Reason reason) throws Exception {
        var failure = failure(reason);
        if (reason == LegalAcceptanceFailure.Reason.INVALID) {
            doReturn(Optional.of(invalidValidation(List.of(Motivo.REQUISITO_FALTANTE)))).when(failure).validation();
        } else if (reason == LegalAcceptanceFailure.Reason.STALE) {
            doReturn(Optional.of(staleValidation(projection(Set.of())))).when(failure).validation();
        }
        return failure;
    }

    private static LegalAcceptanceFailure failure(LegalAcceptanceFailure.Reason reason) {
        var failure = mock(LegalAcceptanceFailure.class);
        when(failure.reason()).thenReturn(reason);
        when(failure.completion()).thenReturn(LegalAcceptanceFailure.Completion.ROLLED_BACK);
        when(failure.persistence()).thenReturn(LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
        when(failure.confirmedReceipt()).thenReturn(Optional.empty());
        when(failure.validation()).thenReturn(Optional.empty());
        when(failure.getMessage()).thenReturn(SECRET);
        when(failure.getCause()).thenReturn(new SQLException(SECRET, "08006"));
        return failure;
    }

    private static LegalAcceptanceValidationException invalidValidation(List<Motivo> motivos) {
        var validation = mock(LegalAcceptanceValidationException.class);
        when(validation.reason()).thenReturn(LegalAcceptanceValidationException.Reason.INVALID);
        when(validation.motivos()).thenReturn(motivos);
        when(validation.getMessage()).thenReturn(SECRET);
        return validation;
    }

    private static LegalAcceptanceValidationException staleValidation(LegalAuthenticatedRequirements current) {
        var validation = mock(LegalAcceptanceValidationException.class);
        when(validation.reason()).thenReturn(LegalAcceptanceValidationException.Reason.STALE);
        when(validation.submittedRevision()).thenReturn(SUBMITTED);
        when(validation.currentRequirements()).thenReturn(current);
        when(validation.motivos()).thenReturn(List.of());
        when(validation.getMessage()).thenReturn(SECRET);
        return validation;
    }

    private static void fields(LegalAcceptanceHttpException actual, HttpStatus status, String error,
            String code, Map<String, Object> details, String retryAfter) throws Exception {
        assertThat(actual.status()).isEqualTo(status);
        assertThat(actual.error()).isEqualTo(error);
        assertThat(actual.code()).isEqualTo(code);
        assertThat(actual.details()).isEqualTo(details);
        assertThat(actual.retryAfter()).isEqualTo(retryAfter);
        safe(actual);
    }

    private static void unavailable(LegalAcceptanceHttpException actual) throws Exception {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("contexto", null); expected.put("locale", "es-AR");
        fields(actual, HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible", "CONTRATO_LEGAL_NO_DISPONIBLE", expected, null);
    }

    private static void safe(LegalAcceptanceHttpException actual) throws Exception {
        assertThat(actual.getMessage()).isNotBlank();
        String visible = actual.getMessage() + actual.toString() + JSON.writeValueAsString(actual.details());
        assertThat(visible).doesNotContain(SECRET, "SQL_SECRET", "private-agent", "private-hmac", "192.0.2.200",
                "08006", "42501", "confirmedReceipt", "acceptanceIds", "lotId", "userId", "tallerId", "tokenVersion",
                "scopeRevision", "completion", "persistence", "COMMITTED", "ROLLED_BACK", "UNKNOWN");
    }

    private static Set<String> fieldNames(JsonNode node) {
        var names = new java.util.HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names;
    }

    /** Real evaluator fixture: accepted evidence is filtered from the wire, not from its complete revision. */
    private static LegalAuthenticatedRequirements projection(Set<UUID> accepted) throws Exception {
        ContextoLegal use = ContextoLegal.USO_CONTINUADO, close = ContextoLegal.CIERRE_CUENTA;
        DocumentProjection terms = document(200, "2026-09-06T00:00:00.123456-03:00");
        DocumentProjection privacy = document(100, "2026-09-06T00:00:00-03:00");
        RequirementProjection required = requirement(30, use, true, List.of(terms, privacy));
        RequirementProjection optional = requirement(10, use, false, List.of(privacy));
        RequirementProjection closing = requirement(20, close, true, List.of(terms));
        List<Scope> scopes = List.of(
                new Scope(1, new LegalRequiredSetProjection(use, LocaleLegal.ES_AR, List.of(required, optional)),
                        List.of(new Membership(3, "required-use", required.versionId()), new Membership(17, "optional-use", optional.versionId()))),
                new Scope(2, new LegalRequiredSetProjection(close, LocaleLegal.ES_AR, List.of(closing)),
                        List.of(new Membership(2, "account-close", closing.versionId()))));
        var applicable = new LegalApplicableScopeResolver((profile, audience) -> List.of(use, close))
                .resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING, LocaleLegal.ES_AR, UserRole.USER.toAudienciaLegal());
        var snapshot = new Snapshot(new LegalActorSnapshot(51, 72, UserRole.USER, 7, true, true), applicable, scopes);
        Map<UUID, DocumentLine> documents = new LinkedHashMap<>();
        List<RequirementLine> requirements = new ArrayList<>();
        List<Acceptance> evidence = new ArrayList<>();
        for (Scope scope : scopes) {
            for (int index = 0; index < scope.projection().requirements().size(); index++) {
                RequirementProjection requirement = scope.projection().requirements().get(index);
                List<DocumentReference> references = new ArrayList<>();
                for (DocumentProjection document : requirement.documents()) {
                    String key = "document-" + document.versionId().getLeastSignificantBits();
                    documents.computeIfAbsent(document.versionId(), ignored -> new DocumentLine(
                            id(1000 + document.versionId().getLeastSignificantBits()), key, document.locale(), document.type(),
                            List.of(new DocumentVersion(document.versionId(), 1, EstadoVersionLegal.VIGENTE, false, document.sha256()))));
                    references.add(new DocumentReference(key, document.versionId(), document.sha256()));
                }
                requirements.add(new RequirementLine(id(2000 + requirement.versionId().getLeastSignificantBits()),
                        scope.memberships().get(index).requirementKey(), requirement.context(), LocaleLegal.ES_AR,
                        requirement.actType(), Set.of(UserRole.USER.toAudienciaLegal()),
                        List.of(new RequirementVersion(requirement.versionId(), 1, EstadoVersionLegal.VIGENTE,
                                false, requirement.statementSha256(), requirement.required(), references))));
                if (accepted.contains(requirement.versionId())) {
                    evidence.add(new Acceptance(id(3000 + requirement.versionId().getLeastSignificantBits()), 51, 72, UserRole.USER,
                            requirement.versionId(), requirement.statementSha256(), references.stream()
                            .map(reference -> new EvidenceDocument(reference.key(), reference.versionId(), reference.sha256())).toList(),
                            Instant.parse("2026-09-06T04:00:00Z")));
                }
            }
        }
        return new LegalRequirementSatisfactionEvaluator().evaluate(snapshot,
                new LegalRequirementLineage(requirements, List.copyOf(documents.values())), evidence);
    }

    private static RequirementProjection requirement(long version, ContextoLegal context, boolean required,
            List<DocumentProjection> documents) throws Exception {
        String statement = "Acepto la afirmación " + version + ".";
        return new RequirementProjection(id(version), context, TipoActoLegal.ACEPTACION, statement, sha(statement), documents, required);
    }

    private static DocumentProjection document(long version, String effectiveAt) throws Exception {
        String markdown = "# Documento " + version + "\n\nTexto legal de prueba.\n";
        return new DocumentProjection(id(version), version == 200 ? TipoDocumentoLegal.TERMINOS_SERVICIO : TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                "v1", "Documento " + version, markdown, sha(markdown), OffsetDateTime.parse(effectiveAt), LocaleLegal.ES_AR);
    }

    private static UUID id(long value) { return new UUID(0, value); }
    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
