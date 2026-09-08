package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Motivo;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationFailure;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationReceipt;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationValidationException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure defensive translation: mocked failure evidence exercises invariants, not PostgreSQL completion. */
class LegalRegistrationHttpExceptionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUBMITTED = "sha256:" + "a".repeat(64);
    private static final String SECRET = "SQL_SECRET_password=synthetic IP=192.0.2.200 UA=private-agent HMAC=private-hmac";

    @Test void keyFactoriesExposeOnlyTheFixedHeaderContract() throws Exception {
        var required = LegalRegistrationHttpException.requiredKey();
        fields(required, HttpStatus.BAD_REQUEST, "Solicitud inválida", "IDEMPOTENCY_KEY_REQUERIDA",
                Map.of("header", "Idempotency-Key"), null);
        assertThat(required.getMessage()).isEqualTo("La solicitud requiere una clave de idempotencia.");
        var invalid = LegalRegistrationHttpException.invalidKey();
        fields(invalid, HttpStatus.BAD_REQUEST, "Solicitud inválida", "IDEMPOTENCY_KEY_INVALIDA",
                Map.of("header", "Idempotency-Key"), null);
        assertThat(invalid.getMessage()).isEqualTo("La clave de idempotencia no tiene un formato válido.");
        assertThat(required.getCause()).isNull(); assertThat(invalid.getCause()).isNull();
        assertThatThrownBy(() -> required.details().put("header", "other")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> invalid.details().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void unreadableBodyUsesTheHistoricalGenericMessageWithoutParserDiagnostics() throws Exception {
        var rejected = LegalRegistrationHttpException.invalidBody();
        fields(rejected, HttpStatus.BAD_REQUEST, "Solicitud inválida", null, null, null);
        assertThat(rejected.getMessage()).isEqualTo("El cuerpo de la solicitud es inválido o tiene un formato incorrecto.");
        assertThat(rejected.getCause()).isNull(); assertThat(rejected.getSuppressed()).isEmpty();
    }

    @Test void invalidBusinessRegistrationHasNoFieldValuesViolationsOrLegalCode() throws Exception {
        var rejected = LegalRegistrationHttpException.invalidRegistration();
        fields(rejected, HttpStatus.BAD_REQUEST, "Error de validación", null, null, null);
        assertThat(rejected.getMessage()).isEqualTo("Los datos de registro no son válidos.");
        assertThat(rejected.getCause()).isNull(); assertThat(rejected.getSuppressed()).isEmpty();
    }

    @Test void incompleteLegalPayloadHasOneImmutableStableMotivo() throws Exception {
        var rejected = LegalRegistrationHttpException.invalidPayload();
        invalidPayload(rejected);
        assertThat(rejected.getMessage()).isEqualTo("La aceptación legal no es válida.");
        assertThat(rejected.getCause()).isNull();
        assertThatThrownBy(() -> ((List<?>) rejected.details().get("motivos")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void unavailableKeepsOnlyTheRegistrationScopeWhileRetainingItsInternalCause() throws Exception {
        Throwable cause = new SQLException(SECRET, "08006");
        var rejected = LegalRegistrationHttpException.unavailable(cause);
        unavailable(rejected);
        assertThat(rejected.getMessage()).isEqualTo("El contrato legal no está disponible.");
        assertThat(rejected.getCause()).isSameAs(cause);
        assertThatThrownBy(() -> rejected.details().put("contexto", null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest @EnumSource(LegalRegistrationFailure.Reason.class)
    void conclusiveRollbackMapsOnlyTheCoherentServiceDecision(LegalRegistrationFailure.Reason reason) throws Exception {
        var rejected = LegalRegistrationHttpException.from(coherentFailure(reason));
        switch (reason) {
            case INVALID_ACTOR -> {
                fields(rejected, HttpStatus.UNAUTHORIZED, "No autorizado", null, null, null);
                assertThat(rejected.getMessage()).isEqualTo("Usuario o contraseña incorrectos");
                assertThat(rejected.getCause()).isInstanceOf(LegalRegistrationFailure.class);
            }
            case INVALID_PAYLOAD -> invalidPayload(rejected);
            case INVALID -> fields(rejected, HttpStatus.BAD_REQUEST, "Solicitud inválida", "ACEPTACION_LEGAL_INVALIDA",
                    Map.of("motivos", List.of("REQUISITO_FALTANTE")), null);
            case KEY_REUSED -> fields(rejected, HttpStatus.CONFLICT, "Conflicto", "IDEMPOTENCY_KEY_REUTILIZADA",
                    Map.of("operacion", "REGISTRO"), null);
            case IN_PROGRESS -> fields(rejected, HttpStatus.CONFLICT, "Conflicto", "IDEMPOTENCY_EN_PROGRESO",
                    Map.of("operacion", "REGISTRO"), "1");
            case STALE -> {
                assertThat(rejected.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(rejected.code()).isEqualTo("DOCUMENTOS_LEGALES_DESACTUALIZADOS");
                assertThat(rejected.retryAfter()).isNull(); safe(rejected);
            }
            case UNAVAILABLE -> unavailable(rejected);
        }
    }

    @ParameterizedTest @EnumSource(LegalRegistrationFailure.Reason.class)
    void onlyShapeRejectionCanPrecedeTheTransaction(LegalRegistrationFailure.Reason reason) throws Exception {
        var failure = coherentFailure(reason);
        when(failure.completion()).thenReturn(LegalRegistrationFailure.Completion.NONE);
        var rejected = LegalRegistrationHttpException.from(failure);
        if (reason == LegalRegistrationFailure.Reason.INVALID_PAYLOAD) invalidPayload(rejected);
        else unavailable(rejected);
    }

    @ParameterizedTest @EnumSource(LegalRegistrationFailure.Reason.class)
    void committedUnknownOrPreviouslyPersistedEvidenceAlwaysDominatesClientReasons(LegalRegistrationFailure.Reason reason)
            throws Exception {
        for (var completion : List.of(LegalRegistrationFailure.Completion.COMMITTED, LegalRegistrationFailure.Completion.UNKNOWN)) {
            var failure = coherentFailure(reason);
            when(failure.completion()).thenReturn(completion);
            unavailable(LegalRegistrationHttpException.from(failure));
        }
        for (var persistence : List.of(LegalRegistrationFailure.Persistence.PERSISTED, LegalRegistrationFailure.Persistence.UNKNOWN)) {
            var failure = coherentFailure(reason);
            when(failure.persistence()).thenReturn(persistence);
            unavailable(LegalRegistrationHttpException.from(failure));
        }
        for (boolean replay : List.of(false, true)) {
            var failure = coherentFailure(reason);
            when(failure.confirmedReceipt()).thenReturn(Optional.of(new LegalRegistrationReceipt(51, 72, replay,
                    id(800), List.of(id(801)))));
            var rejected = LegalRegistrationHttpException.from(failure);
            unavailable(rejected);
            assertThat(JSON.writeValueAsString(rejected.details())).doesNotContain(id(800).toString(), id(801).toString());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"null-failure", "reason", "completion", "persistence", "receipt", "validation"})
    void missingClassificationEvidenceFailsClosed(String defect) throws Exception {
        var failure = failure(LegalRegistrationFailure.Reason.INVALID_PAYLOAD);
        switch (defect) {
            case "null-failure" -> { unavailable(LegalRegistrationHttpException.from(null)); return; }
            case "reason" -> when(failure.reason()).thenReturn(null);
            case "completion" -> when(failure.completion()).thenReturn(null);
            case "persistence" -> when(failure.persistence()).thenReturn(null);
            case "receipt" -> when(failure.confirmedReceipt()).thenReturn(null);
            case "validation" -> when(failure.validation()).thenReturn(null);
            default -> throw new AssertionError(defect);
        }
        unavailable(LegalRegistrationHttpException.from(failure));
    }

    @ParameterizedTest @EnumSource(value = LegalRegistrationFailure.Reason.class,
            names = {"INVALID_ACTOR", "INVALID_PAYLOAD", "KEY_REUSED", "IN_PROGRESS", "UNAVAILABLE"})
    void nonSemanticDecisionsCannotCarryAnUnrelatedValidation(LegalRegistrationFailure.Reason reason) throws Exception {
        var failure = failure(reason);
        doReturn(Optional.of(invalidValidation(List.of(Motivo.DOCUMENTO_FALTANTE)))).when(failure).validation();
        unavailable(LegalRegistrationHttpException.from(failure));
        when(failure.completion()).thenReturn(LegalRegistrationFailure.Completion.NONE);
        unavailable(LegalRegistrationHttpException.from(failure));
    }

    @ParameterizedTest @EnumSource(Motivo.class)
    void everyFrozenSemanticMotivoUsesTheLegalBadRequestEnvelope(Motivo motivo) throws Exception {
        var failure = failure(LegalRegistrationFailure.Reason.INVALID);
        doReturn(Optional.of(invalidValidation(List.of(motivo)))).when(failure).validation();
        fields(LegalRegistrationHttpException.from(failure), HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "ACEPTACION_LEGAL_INVALIDA", Map.of("motivos", List.of(motivo.name())), null);
    }

    @Test void semanticMotivosAreDetachedDeduplicatedAndOrderedByTheFrozenEnum() throws Exception {
        var supplied = new ArrayList<>(List.of(Motivo.PAYLOAD_LEGAL_INCOMPLETO, Motivo.DOCUMENTO_DUPLICADO,
                Motivo.REQUISITO_FALTANTE, Motivo.DOCUMENTO_DUPLICADO, Motivo.CONFIRMACION_REQUERIDA));
        var failure = failure(LegalRegistrationFailure.Reason.INVALID);
        doReturn(Optional.of(invalidValidation(supplied))).when(failure).validation();
        var rejected = LegalRegistrationHttpException.from(failure); supplied.clear();
        fields(rejected, HttpStatus.BAD_REQUEST, "Solicitud inválida", "ACEPTACION_LEGAL_INVALIDA",
                Map.of("motivos", List.of("REQUISITO_FALTANTE", "DOCUMENTO_DUPLICADO", "CONFIRMACION_REQUERIDA",
                        "PAYLOAD_LEGAL_INCOMPLETO")), null);
        assertThatThrownBy(() -> ((List<?>) rejected.details().get("motivos")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "reason", "submitted", "current", "null-list", "empty-list", "null-motivo", "oversized"})
    void invalidNeedsAConsistentBoundedValidation(String defect) throws Exception {
        var failure = failure(LegalRegistrationFailure.Reason.INVALID);
        var validation = invalidValidation(List.of(Motivo.REQUISITO_FALTANTE));
        switch (defect) {
            case "missing" -> { unavailable(LegalRegistrationHttpException.from(failure)); return; }
            case "reason" -> when(validation.reason()).thenReturn(LegalRegistrationValidationException.Reason.STALE);
            case "submitted" -> when(validation.submittedRevision()).thenReturn(SUBMITTED);
            case "current" -> when(validation.currentRequirements()).thenReturn(projection());
            case "null-list" -> when(validation.motivos()).thenReturn(null);
            case "empty-list" -> when(validation.motivos()).thenReturn(List.of());
            case "null-motivo" -> when(validation.motivos()).thenReturn(Arrays.asList(Motivo.REQUISITO_FALTANTE, null));
            case "oversized" -> when(validation.motivos()).thenReturn(Collections.nCopies(Motivo.values().length + 1, Motivo.REQUISITO_FALTANTE));
            default -> throw new AssertionError(defect);
        }
        when(failure.validation()).thenReturn(Optional.of(validation));
        unavailable(LegalRegistrationHttpException.from(failure));
    }

    @Test void staleCarriesTheCompletePublicProjectionWithOptionalActsAndCanonicalUtcDates() throws Exception {
        var current = projection(); var failure = failure(LegalRegistrationFailure.Reason.STALE);
        doReturn(Optional.of(staleValidation(current))).when(failure).validation();
        var rejected = LegalRegistrationHttpException.from(failure);
        assertThat(rejected.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.error()).isEqualTo("Conflicto");
        assertThat(rejected.getMessage()).isEqualTo("Las condiciones legales cambiaron.");
        assertThat(rejected.code()).isEqualTo("DOCUMENTOS_LEGALES_DESACTUALIZADOS");
        assertThat(rejected.retryAfter()).isNull(); assertThat(rejected.getCause()).isSameAs(failure);
        assertThat(rejected.details()).containsOnlyKeys("submittedRevision", "requisitosActuales");
        assertThat(rejected.details().get("submittedRevision")).isEqualTo(SUBMITTED);
        assertPublicSnapshot(rejected, current);
    }

    @Test void requiredAcceptanceUsesTheSamePublicProjectionAndNeverAnAuthenticationDecision() throws Exception {
        var current = projection(); var rejected = LegalRegistrationHttpException.requiredAcceptance(current);
        assertThat(rejected.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(rejected.error()).isEqualTo("Precondición requerida");
        assertThat(rejected.getMessage()).isEqualTo("Tenés condiciones pendientes de revisión y aceptación.");
        assertThat(rejected.code()).isEqualTo("ACEPTACION_LEGAL_REQUERIDA");
        assertThat(rejected.retryAfter()).isNull(); assertThat(rejected.getCause()).isNull();
        assertThat(rejected.details()).containsOnlyKeys("requisitosActuales");
        assertPublicSnapshot(rejected, current);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "reason", "null-submitted", "uppercase-submitted", "empty-submitted",
            "same-revision", "null-current", "null-motivos", "nonempty-motivos"})
    void staleNeedsADifferentCanonicalRevisionAndConsistentValidation(String defect) throws Exception {
        var current = projection(); var validation = staleValidation(current); var failure = failure(LegalRegistrationFailure.Reason.STALE);
        switch (defect) {
            case "missing" -> { unavailable(LegalRegistrationHttpException.from(failure)); return; }
            case "reason" -> when(validation.reason()).thenReturn(LegalRegistrationValidationException.Reason.INVALID);
            case "null-submitted" -> when(validation.submittedRevision()).thenReturn(null);
            case "uppercase-submitted" -> when(validation.submittedRevision()).thenReturn("sha256:" + "A".repeat(64));
            case "empty-submitted" -> when(validation.submittedRevision()).thenReturn("");
            case "same-revision" -> when(validation.submittedRevision()).thenReturn(current.requiredSetRevision());
            case "null-current" -> when(validation.currentRequirements()).thenReturn(null);
            case "null-motivos" -> when(validation.motivos()).thenReturn(null);
            case "nonempty-motivos" -> when(validation.motivos()).thenReturn(List.of(Motivo.REQUISITO_FALTANTE));
            default -> throw new AssertionError(defect);
        }
        when(failure.validation()).thenReturn(Optional.of(validation));
        unavailable(LegalRegistrationHttpException.from(failure));
    }

    @ParameterizedTest @ValueSource(strings = {"null", "projection", "context", "locale", "revision-null", "revision-uppercase",
            "revision-unprefixed", "empty", "only-optional", "requirement-context", "empty-documents", "document-locale"})
    void malformedPublicSnapshotsNeverEscapeThroughConflictOrPrecondition(String defect) throws Exception {
        var source = brokenProjection(defect);
        unavailable(LegalRegistrationHttpException.requiredAcceptance(source));
        var failure = failure(LegalRegistrationFailure.Reason.STALE);
        doReturn(Optional.of(staleValidation(source))).when(failure).validation();
        unavailable(LegalRegistrationHttpException.from(failure));
    }

    private static LegalPublicRegistrationRequirements brokenProjection(String defect) throws Exception {
        if (defect.equals("null")) return null;
        var real = projection(); var source = mock(LegalPublicRegistrationRequirements.class);
        when(source.projection()).thenReturn(real.projection()); when(source.requiredSetRevision()).thenReturn(real.requiredSetRevision());
        var original = real.projection();
        switch (defect) {
            case "projection" -> when(source.projection()).thenReturn(null);
            case "context", "locale" -> {
                var projected = mock(LegalRequiredSetProjection.class);
                when(projected.context()).thenReturn(defect.equals("context") ? ContextoLegal.USO_CONTINUADO : ContextoLegal.REGISTRO);
                when(projected.locale()).thenReturn(defect.equals("locale") ? null : LocaleLegal.ES_AR);
                when(projected.requirements()).thenReturn(original.requirements()); when(source.projection()).thenReturn(projected);
            }
            case "revision-null" -> when(source.requiredSetRevision()).thenReturn(null);
            case "revision-uppercase" -> when(source.requiredSetRevision()).thenReturn("sha256:" + "A".repeat(64));
            case "revision-unprefixed" -> when(source.requiredSetRevision()).thenReturn("f".repeat(64));
            case "empty" -> when(source.projection()).thenReturn(new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR, List.of()));
            case "only-optional" -> when(source.projection()).thenReturn(new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR,
                    List.of(original.requirements().getLast())));
            case "requirement-context", "empty-documents", "document-locale" -> {
                var first = original.requirements().getFirst(); var documents = first.documents();
                if (defect.equals("empty-documents")) documents = List.of();
                else if (defect.equals("document-locale")) {
                    var document = mock(DocumentProjection.class); when(document.locale()).thenReturn(null); documents = List.of(document);
                }
                var requirement = new RequirementProjection(first.versionId(), defect.equals("requirement-context")
                        ? ContextoLegal.USO_CONTINUADO : ContextoLegal.REGISTRO, first.actType(), first.statement(),
                        first.statementSha256(), documents, true);
                when(source.projection()).thenReturn(new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR, List.of(requirement)));
            }
            default -> throw new AssertionError(defect);
        }
        return source;
    }

    private static LegalRegistrationFailure coherentFailure(LegalRegistrationFailure.Reason reason) throws Exception {
        var failure = failure(reason);
        if (reason == LegalRegistrationFailure.Reason.INVALID)
            doReturn(Optional.of(invalidValidation(List.of(Motivo.REQUISITO_FALTANTE)))).when(failure).validation();
        else if (reason == LegalRegistrationFailure.Reason.STALE)
            doReturn(Optional.of(staleValidation(projection()))).when(failure).validation();
        return failure;
    }

    private static LegalRegistrationFailure failure(LegalRegistrationFailure.Reason reason) {
        var failure = mock(LegalRegistrationFailure.class);
        when(failure.reason()).thenReturn(reason); when(failure.completion()).thenReturn(LegalRegistrationFailure.Completion.ROLLED_BACK);
        when(failure.persistence()).thenReturn(LegalRegistrationFailure.Persistence.NOT_PERSISTED);
        when(failure.confirmedReceipt()).thenReturn(Optional.empty()); when(failure.validation()).thenReturn(Optional.empty());
        when(failure.getMessage()).thenReturn(SECRET); when(failure.getCause()).thenReturn(new SQLException(SECRET, "08006"));
        return failure;
    }

    private static LegalRegistrationValidationException invalidValidation(List<Motivo> motivos) {
        var validation = mock(LegalRegistrationValidationException.class);
        when(validation.reason()).thenReturn(LegalRegistrationValidationException.Reason.INVALID); when(validation.motivos()).thenReturn(motivos);
        when(validation.getMessage()).thenReturn(SECRET); return validation;
    }

    private static LegalRegistrationValidationException staleValidation(LegalPublicRegistrationRequirements current) {
        var validation = mock(LegalRegistrationValidationException.class);
        when(validation.reason()).thenReturn(LegalRegistrationValidationException.Reason.STALE);
        when(validation.submittedRevision()).thenReturn(SUBMITTED); when(validation.currentRequirements()).thenReturn(current);
        when(validation.motivos()).thenReturn(List.of()); when(validation.getMessage()).thenReturn(SECRET); return validation;
    }

    private static void assertPublicSnapshot(LegalRegistrationHttpException rejected, LegalPublicRegistrationRequirements source) throws Exception {
        var current = (LegalPublicRequirementsResponses.Registration) rejected.details().get("requisitosActuales");
        assertThat(current).isEqualTo(LegalPublicRequirementsResponses.registration(source));
        assertThat(current.contexto()).isEqualTo("REGISTRO"); assertThat(current.locale()).isEqualTo("es-AR");
        assertThat(current.requiredSetRevision()).isEqualTo(source.requiredSetRevision()).isNotEqualTo(source.scopeRevision());
        assertThat(current.requisitos()).extracting(LegalPublicRequirementsResponses.Requisito::id)
                .containsExactly(id(30).toString(), id(10).toString());
        assertThat(current.requisitos()).extracting(LegalPublicRequirementsResponses.Requisito::requerido).containsExactly(true, false);
        assertThat(current.requisitos().getFirst().documentos()).extracting(LegalPublicRequirementsResponses.Documento::id)
                .containsExactly(id(200).toString(), id(100).toString());
        assertThat(current.requisitos().getFirst().documentos().getFirst().vigenteDesde()).isEqualTo("2026-09-06T03:00:00.123456Z");
        JsonNode wire = JSON.valueToTree(current);
        assertThat(names(wire)).containsExactlyInAnyOrder("contexto", "locale", "requiredSetRevision", "requisitos");
        assertThat(names(wire.get("requisitos").get(0))).containsExactlyInAnyOrder("id", "contexto", "tipoActo", "afirmacion",
                "afirmacionSha256", "requerido", "documentos");
        assertThat(names(wire.get("requisitos").get(0).get("documentos").get(0))).containsExactlyInAnyOrder("id", "tipo", "version",
                "titulo", "contenidoMarkdown", "sha256", "vigenteDesde", "estado", "locale");
        assertThatThrownBy(() -> rejected.details().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> current.requisitos().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> current.requisitos().getFirst().documentos().clear()).isInstanceOf(UnsupportedOperationException.class);
        safe(rejected);
    }

    private static void fields(LegalRegistrationHttpException actual, HttpStatus status, String error,
            String code, Map<String, Object> details, String retryAfter) throws Exception {
        assertThat(actual.status()).isEqualTo(status); assertThat(actual.error()).isEqualTo(error);
        assertThat(actual.code()).isEqualTo(code); assertThat(actual.details()).isEqualTo(details);
        assertThat(actual.retryAfter()).isEqualTo(retryAfter); safe(actual);
    }

    private static void invalidPayload(LegalRegistrationHttpException actual) throws Exception {
        fields(actual, HttpStatus.BAD_REQUEST, "Solicitud inválida", "ACEPTACION_LEGAL_INVALIDA",
                Map.of("motivos", List.of("PAYLOAD_LEGAL_INCOMPLETO")), null);
    }

    private static void unavailable(LegalRegistrationHttpException actual) throws Exception {
        fields(actual, HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible", "CONTRATO_LEGAL_NO_DISPONIBLE",
                Map.of("contexto", "REGISTRO", "locale", "es-AR"), null);
    }

    private static void safe(LegalRegistrationHttpException actual) throws Exception {
        assertThat(actual.getMessage()).isNotBlank();
        String visible = actual.getMessage() + actual.toString() + JSON.writeValueAsString(actual.details());
        assertThat(visible).doesNotContain(SECRET, "SQL_SECRET", "private-agent", "private-hmac", "192.0.2.200", "08006",
                "confirmedReceipt", "acceptanceIds", "lotId", "userId", "tallerId", "tokenVersion", "scopeRevision",
                "completion", "persistence", "COMMITTED", "ROLLED_BACK", "UNKNOWN", "password", "ConstraintViolation");
    }

    private static Set<String> names(JsonNode node) {
        var result = new HashSet<String>(); node.fieldNames().forEachRemaining(result::add); return result;
    }

    private static LegalPublicRegistrationRequirements projection() throws Exception {
        var terms = document(200, "2026-09-06T00:00:00.123456-03:00");
        var privacy = document(100, "2026-09-06T00:00:00-03:00");
        var required = requirement(30, true, TipoActoLegal.ACEPTACION, List.of(terms, privacy));
        var optional = requirement(10, false, TipoActoLegal.LECTURA, List.of(privacy));
        return new LegalPublicRequirementsValidator().validate(new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR,
                List.of(required, optional)));
    }

    private static RequirementProjection requirement(long id, boolean required, TipoActoLegal type, List<DocumentProjection> documents)
            throws Exception {
        String statement = "Afirmación legal " + id + ".";
        return new RequirementProjection(id(id), ContextoLegal.REGISTRO, type, statement, sha(statement), documents, required);
    }

    private static DocumentProjection document(long id, String effectiveAt) throws Exception {
        String markdown = "# Documento " + id + "\n\nTexto legal de prueba.\n";
        return new DocumentProjection(id(id), id == 200 ? TipoDocumentoLegal.TERMINOS_SERVICIO : TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                "v1", "Documento " + id, markdown, sha(markdown), OffsetDateTime.parse(effectiveAt), LocaleLegal.ES_AR);
    }

    private static UUID id(long value) { return new UUID(0, value); }
    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
