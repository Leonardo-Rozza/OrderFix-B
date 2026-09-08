package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Registration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Motivo;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationValidationException.Reason;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalRegistrationSelectionTest {
    private static final String OLD_REVISION = "sha256:" + "a".repeat(64);
    private static final String WRONG_DIGEST = "f".repeat(64);
    private static final Registration REGISTRATION = new Registration("Taller exacto", null, "Titular exacto",
            "private-registration@example.invalid", "private-registration-password");
    private static final DocumentProjection FIRST_DOCUMENT = document(302);
    private static final DocumentProjection SECOND_DOCUMENT = document(301);
    private static final RequirementProjection REQUIRED = requirement(20, true, TipoActoLegal.ACEPTACION,
            FIRST_DOCUMENT, SECOND_DOCUMENT);
    private static final RequirementProjection OPTIONAL = requirement(10, false, TipoActoLegal.LECTURA, FIRST_DOCUMENT);
    private final LegalRegistrationSelection selector = new LegalRegistrationSelection();

    @Test void preservesServerSnapshotAndDocumentOrderDespiteCommandCanonicalOrdering() {
        var current = current(REQUIRED, OPTIONAL);
        var command = command(current, List.of(submit(OPTIONAL), submit(REQUIRED)));
        assertThat(command.acceptances()).extracting(Acceptance::requisitoVersionId)
                .containsExactly(OPTIONAL.versionId(), REQUIRED.versionId());
        assertThat(command.acceptances().getLast().documentos()).extracting(Document::documentoVersionId)
                .containsExactly(SECOND_DOCUMENT.versionId(), FIRST_DOCUMENT.versionId());

        var selected = selector.select(command, current);

        assertThat(selected.current()).isSameAs(current);
        assertThat(selected.requirements()).containsExactly(REQUIRED, OPTIONAL);
        assertThat(selected.requirements().getFirst()).isSameAs(REQUIRED);
        assertThat(selected.requirements().getFirst().documents()).containsExactly(FIRST_DOCUMENT, SECOND_DOCUMENT);
        assertThatCode(() -> selected.requireCommand(command)).doesNotThrowAnyException();
    }

    @Test void optionalRequirementsCanBeOmittedAndSharedDocumentsAreNotDuplicatesAcrossActs() {
        var current = current(REQUIRED, OPTIONAL);
        assertThat(selector.select(command(current, List.of(submit(REQUIRED))), current).requirements())
                .containsExactly(REQUIRED);
        assertThat(selector.select(command(current, List.of(submit(REQUIRED), submit(OPTIONAL))), current).requirements())
                .containsExactly(REQUIRED, OPTIONAL);
    }

    @ParameterizedTest @EnumSource(TipoActoLegal.class)
    void acceptsEveryCanonicalActTypeOnlyWithExplicitConfirmation(TipoActoLegal actType) {
        var requirement = requirement(25, true, actType, FIRST_DOCUMENT);
        var current = current(requirement);
        assertThat(selector.select(command(current, List.of(submit(requirement))), current).requirements())
                .containsExactly(requirement);
        var unconfirmed = new Acceptance(requirement.versionId(), actType, requirement.statementSha256(),
                List.of(doc(FIRST_DOCUMENT)), false);
        assertInvalid(() -> selector.select(command(current, List.of(unconfirmed)), current), Motivo.CONFIRMACION_REQUERIDA);
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "missing-required", "requirement-duplicate", "document-duplicate",
            "unknown-requirement", "missing-document", "foreign-document", "replaced-document", "statement-digest",
            "document-digest", "act-type", "unconfirmed", "empty-documents"})
    void currentRevisionReportsExactSemanticReasonsAndOldRevisionAlwaysWins(String defect) {
        var current = current(REQUIRED, OPTIONAL);
        var input = defective(defect);
        assertStale(() -> selector.select(command(OLD_REVISION, input), current), current);
        assertInvalid(() -> selector.select(command(current, input), current), expected(defect));
    }

    @Test void anOtherwiseExactRegistrationNeverBypassesRevisionAsHistoricalDedup() {
        var current = current(REQUIRED);
        assertStale(() -> selector.select(command(OLD_REVISION, List.of(submit(REQUIRED))), current), current);
        var first = selector.select(command(current, List.of(submit(REQUIRED))), current);
        var second = selector.select(command(current, List.of(submit(REQUIRED))), current);
        assertThat(first.requirements()).containsExactly(REQUIRED);
        assertThat(second.requirements()).containsExactly(REQUIRED);
    }

    @Test void cannotOmitAnotherMandatoryRequirementEvenWhenItsDocumentsWereSubmittedElsewhere() {
        var another = requirement(30, true, TipoActoLegal.ACEPTACION, FIRST_DOCUMENT);
        var current = current(REQUIRED, another, OPTIONAL);
        assertInvalid(() -> selector.select(command(current, List.of(submit(REQUIRED), submit(OPTIONAL))), current),
                Motivo.REQUISITO_FALTANTE);
    }

    @Test void oldRequirementIdsWithIdenticalTextAndDocumentDigestsDoNotBelongToTheCurrentSet() {
        var current = current(REQUIRED);
        var old = new Acceptance(uuid(900), REQUIRED.actType(), REQUIRED.statementSha256(),
                REQUIRED.documents().stream().map(LegalRegistrationSelectionTest::doc).toList(), true);
        assertInvalid(() -> selector.select(command(current, List.of(old)), current),
                Motivo.REQUISITO_FALTANTE, Motivo.REQUISITO_NO_PERTENECE_AL_CONJUNTO);
    }

    @Test void duplicateRequirementsCannotUnionTheirPartialDocumentListsIntoAValidAcceptance() {
        var current = current(REQUIRED);
        var firstHalf = new Acceptance(REQUIRED.versionId(), REQUIRED.actType(), REQUIRED.statementSha256(),
                List.of(doc(FIRST_DOCUMENT)), true);
        var secondHalf = new Acceptance(REQUIRED.versionId(), REQUIRED.actType(), REQUIRED.statementSha256(),
                List.of(doc(SECOND_DOCUMENT)), true);
        assertInvalid(() -> selector.select(command(current, List.of(firstHalf, secondHalf)), current),
                Motivo.REQUISITO_DUPLICADO, Motivo.DOCUMENTO_FALTANTE);
    }

    @Test void allSemanticReasonsAreDistinctAndUseFrozenOrderRegardlessOfPayloadOrder() {
        var current = current(REQUIRED, OPTIONAL);
        var foreign = new Document(uuid(999), FIRST_DOCUMENT.sha256());
        var badOptional = new Acceptance(OPTIONAL.versionId(), TipoActoLegal.DECLARACION, WRONG_DIGEST,
                List.of(foreign, foreign), false);
        var unknown = new Acceptance(uuid(900), TipoActoLegal.ACEPTACION, WRONG_DIGEST, List.of(), false);
        List<Acceptance> values = new ArrayList<>(List.of(unknown, badOptional, badOptional));
        for (int attempt = 0; attempt < 2; attempt++) {
            var failure = validationFailure(() -> selector.select(command(current, values), current));
            assertThat(failure.motivos()).containsExactly(Motivo.REQUISITO_FALTANTE, Motivo.REQUISITO_DUPLICADO,
                    Motivo.REQUISITO_NO_PERTENECE_AL_CONJUNTO, Motivo.DOCUMENTO_FALTANTE, Motivo.DOCUMENTO_DUPLICADO,
                    Motivo.DOCUMENTO_NO_PERTENECE_AL_REQUISITO, Motivo.DIGEST_NO_COINCIDE, Motivo.ACTO_NO_COINCIDE,
                    Motivo.CONFIRMACION_REQUERIDA);
            Collections.reverse(values);
        }
    }

    @Test void optionalActsAreValidatedJustAsStrictlyAsMandatoryOnes() {
        var current = current(REQUIRED, OPTIONAL);
        var badOptional = new Acceptance(OPTIONAL.versionId(), OPTIONAL.actType(), WRONG_DIGEST,
                List.of(doc(FIRST_DOCUMENT)), false);
        assertInvalid(() -> selector.select(command(current, List.of(submit(REQUIRED), badOptional)), current),
                Motivo.DIGEST_NO_COINCIDE, Motivo.CONFIRMACION_REQUERIDA);
    }

    @Test void rejectsWrongOperationAndMissingTrustedInputsBeforeRevisionClassification() {
        var current = current(REQUIRED);
        var authenticated = LegalAcceptanceCommandValidator.authenticated(
                new LegalActorSnapshot(11, 22, UserRole.ADMIN, 0, true, true), OLD_REVISION, List.of());
        assertObservationFailure(() -> selector.select(authenticated, current));
        assertObservationFailure(() -> selector.select(null, current));
        assertObservationFailure(() -> selector.select(command(current, List.of()), null));
    }

    @Test void aValidatedPublicSnapshotAlwaysHasMandatoryActsAndCannotRepresentEmptyRegistrationSuccess() {
        assertThatThrownBy(() -> current()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> current(OPTIONAL)).isInstanceOf(IllegalArgumentException.class);
        var current = current(REQUIRED);
        assertInvalid(() -> selector.select(command(current, List.of()), current), Motivo.REQUISITO_FALTANTE);
    }

    @Test void selectionBindsTheExactCommandInstanceAndRemainsDeeplyImmutable() {
        var input = new ArrayList<>(List.of(submit(REQUIRED)));
        var current = current(REQUIRED);
        var command = command(current, input);
        var selection = selector.select(command, current);
        input.clear();
        assertThat(selection.requirements()).containsExactly(REQUIRED);
        assertThatCode(() -> selection.requireCommand(command)).doesNotThrowAnyException();
        assertObservationFailure(() -> selection.requireCommand(command(current, List.of(submit(REQUIRED)))));
        assertObservationFailure(() -> selection.requireCommand(null));
        assertThatThrownBy(() -> selection.requirements().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> selection.requirements().getFirst().documents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void supportsACompleteSingleScopeAtItsReal256RequirementCapacityWithoutSortingTheSnapshot() {
        List<RequirementProjection> requirements = new ArrayList<>();
        for (int i = 255; i >= 0; i--) requirements.add(requirement(1_000 + i, i % 5 == 0,
                TipoActoLegal.ACEPTACION, FIRST_DOCUMENT, SECOND_DOCUMENT));
        var current = current(requirements.toArray(RequirementProjection[]::new));
        var command = command(current, requirements.stream().map(LegalRegistrationSelectionTest::submit).toList());
        assertThat(command.acceptances().getFirst().requisitoVersionId()).isEqualTo(uuid(1_000));
        var selected = selector.select(command, current);
        assertThat(selected.requirements()).hasSize(256).containsExactlyElementsOf(requirements);
    }

    @Test void exceptionFactoriesKeepPublicStaleDataSeparateFromSemanticReasonsAndRedactDiagnostics() {
        var current = current(REQUIRED);
        var stale = validationFailure(() -> selector.select(command(OLD_REVISION, List.of()), current));
        assertThat(stale.reason()).isEqualTo(Reason.STALE);
        assertThat(stale.currentRequirements()).isSameAs(current);
        assertThat(stale.submittedRevision()).isEqualTo(OLD_REVISION);
        assertThat(stale.motivos()).isEmpty();
        var invalid = validationFailure(() -> selector.select(command(current, List.of()), current));
        assertThat(invalid.currentRequirements()).isNull();
        assertThat(invalid.submittedRevision()).isNull();
        assertThatThrownBy(() -> invalid.motivos().clear()).isInstanceOf(UnsupportedOperationException.class);
        var selected = selector.select(command(current, List.of(submit(REQUIRED))), current);
        var diagnostics = new StringWriter();
        stale.printStackTrace(new PrintWriter(diagnostics));
        invalid.printStackTrace(new PrintWriter(diagnostics));
        assertThat(diagnostics + selected.toString()).doesNotContain(REGISTRATION.email(), REGISTRATION.password(),
                REQUIRED.statement(), FIRST_DOCUMENT.markdown(), OLD_REVISION, current.requiredSetRevision());
        assertThat(stale.getCause()).isNull();
        assertThat(invalid.getCause()).isNull();
    }

    @Test void exceptionFactoriesRejectIncompleteInternalObservations() {
        var current = current(REQUIRED);
        assertObservationFailure(() -> LegalRegistrationValidationException.stale(null, current));
        assertObservationFailure(() -> LegalRegistrationValidationException.stale("private-input", current));
        assertObservationFailure(() -> LegalRegistrationValidationException.stale(OLD_REVISION, null));
        assertObservationFailure(() -> LegalRegistrationValidationException.invalid(null));
        assertObservationFailure(() -> LegalRegistrationValidationException.invalid(List.of()));
        assertObservationFailure(() -> LegalRegistrationValidationException.invalid(java.util.Arrays.asList(Motivo.REQUISITO_FALTANTE, null)));
        var supplied = new ArrayList<>(List.of(Motivo.CONFIRMACION_REQUERIDA, Motivo.REQUISITO_FALTANTE,
                Motivo.CONFIRMACION_REQUERIDA));
        var failure = LegalRegistrationValidationException.invalid(supplied);
        supplied.clear();
        assertThat(failure.motivos()).containsExactly(Motivo.REQUISITO_FALTANTE, Motivo.CONFIRMACION_REQUERIDA);
    }

    private static List<Acceptance> defective(String defect) {
        Acceptance valid = submit(REQUIRED);
        return switch (defect) {
            case "empty" -> List.of();
            case "missing-required" -> List.of(submit(OPTIONAL));
            case "requirement-duplicate" -> List.of(valid, valid);
            case "document-duplicate" -> List.of(new Acceptance(REQUIRED.versionId(), REQUIRED.actType(),
                    REQUIRED.statementSha256(), List.of(doc(FIRST_DOCUMENT), doc(SECOND_DOCUMENT), doc(SECOND_DOCUMENT)), true));
            case "unknown-requirement" -> List.of(valid, new Acceptance(uuid(900), TipoActoLegal.ACEPTACION,
                    REQUIRED.statementSha256(), List.of(doc(FIRST_DOCUMENT)), true));
            case "missing-document" -> List.of(new Acceptance(REQUIRED.versionId(), REQUIRED.actType(),
                    REQUIRED.statementSha256(), List.of(doc(FIRST_DOCUMENT)), true));
            case "foreign-document" -> List.of(new Acceptance(REQUIRED.versionId(), REQUIRED.actType(),
                    REQUIRED.statementSha256(), List.of(doc(FIRST_DOCUMENT), doc(SECOND_DOCUMENT),
                    new Document(uuid(999), FIRST_DOCUMENT.sha256())), true));
            case "replaced-document" -> List.of(new Acceptance(REQUIRED.versionId(), REQUIRED.actType(),
                    REQUIRED.statementSha256(), List.of(doc(FIRST_DOCUMENT), new Document(uuid(999), SECOND_DOCUMENT.sha256())), true));
            case "statement-digest" -> List.of(new Acceptance(REQUIRED.versionId(), REQUIRED.actType(), WRONG_DIGEST,
                    valid.documentos(), true));
            case "document-digest" -> List.of(new Acceptance(REQUIRED.versionId(), REQUIRED.actType(), REQUIRED.statementSha256(),
                    List.of(new Document(FIRST_DOCUMENT.versionId(), WRONG_DIGEST), doc(SECOND_DOCUMENT)), true));
            case "act-type" -> List.of(new Acceptance(REQUIRED.versionId(), TipoActoLegal.DECLARACION,
                    REQUIRED.statementSha256(), valid.documentos(), true));
            case "unconfirmed" -> List.of(new Acceptance(REQUIRED.versionId(), REQUIRED.actType(),
                    REQUIRED.statementSha256(), valid.documentos(), false));
            case "empty-documents" -> List.of(new Acceptance(REQUIRED.versionId(), REQUIRED.actType(),
                    REQUIRED.statementSha256(), List.of(), true));
            default -> throw new AssertionError(defect);
        };
    }

    private static Motivo[] expected(String defect) {
        return switch (defect) {
            case "empty", "missing-required" -> new Motivo[]{Motivo.REQUISITO_FALTANTE};
            case "requirement-duplicate" -> new Motivo[]{Motivo.REQUISITO_DUPLICADO};
            case "document-duplicate" -> new Motivo[]{Motivo.DOCUMENTO_DUPLICADO};
            case "unknown-requirement" -> new Motivo[]{Motivo.REQUISITO_NO_PERTENECE_AL_CONJUNTO};
            case "missing-document", "empty-documents" -> new Motivo[]{Motivo.DOCUMENTO_FALTANTE};
            case "foreign-document" -> new Motivo[]{Motivo.DOCUMENTO_NO_PERTENECE_AL_REQUISITO};
            case "replaced-document" -> new Motivo[]{Motivo.DOCUMENTO_FALTANTE, Motivo.DOCUMENTO_NO_PERTENECE_AL_REQUISITO};
            case "statement-digest", "document-digest" -> new Motivo[]{Motivo.DIGEST_NO_COINCIDE};
            case "act-type" -> new Motivo[]{Motivo.ACTO_NO_COINCIDE};
            case "unconfirmed" -> new Motivo[]{Motivo.CONFIRMACION_REQUERIDA};
            default -> throw new AssertionError(defect);
        };
    }

    private static LegalPublicRegistrationRequirements current(RequirementProjection... requirements) {
        return new LegalPublicRequirementsValidator().validate(new LegalRequiredSetProjection(
                ContextoLegal.REGISTRO, LocaleLegal.ES_AR, List.of(requirements)));
    }

    private static LegalAcceptanceCommand command(LegalPublicRegistrationRequirements current, List<Acceptance> acceptances) {
        return command(current.requiredSetRevision(), acceptances);
    }

    private static LegalAcceptanceCommand command(String revision, List<Acceptance> acceptances) {
        return LegalAcceptanceCommandValidator.registration(REGISTRATION, revision, acceptances);
    }

    private static Acceptance submit(RequirementProjection requirement) {
        return new Acceptance(requirement.versionId(), requirement.actType(), requirement.statementSha256(),
                requirement.documents().stream().map(LegalRegistrationSelectionTest::doc).toList(), true);
    }

    private static Document doc(DocumentProjection value) { return new Document(value.versionId(), value.sha256()); }

    private static RequirementProjection requirement(long id, boolean required, TipoActoLegal type,
                                                     DocumentProjection... documents) {
        String statement = "Confirmo el requisito canónico de registro " + id + ".";
        return new RequirementProjection(uuid(id), ContextoLegal.REGISTRO, type, statement, sha(statement), List.of(documents), required);
    }

    private static DocumentProjection document(long id) {
        String markdown = "# Documento " + id + "\n\nContenido legal canónico.\n";
        return new DocumentProjection(uuid(id), TipoDocumentoLegal.TERMINOS_SERVICIO, "2026.1", "Documento " + id,
                markdown, sha(markdown), OffsetDateTime.parse("2020-01-01T00:00:00Z"), LocaleLegal.ES_AR);
    }

    private static UUID uuid(long number) { return new UUID(0, number); }

    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static LegalRegistrationValidationException validationFailure(ThrowingCallable call) {
        Throwable failure = catchThrowable(call);
        assertThat(failure).isExactlyInstanceOf(LegalRegistrationValidationException.class);
        return (LegalRegistrationValidationException) failure;
    }

    private static void assertStale(ThrowingCallable call, LegalPublicRegistrationRequirements current) {
        var failure = validationFailure(call);
        assertThat(failure.reason()).isEqualTo(Reason.STALE);
        assertThat(failure.submittedRevision()).isEqualTo(OLD_REVISION);
        assertThat(failure.currentRequirements()).isSameAs(current);
        assertThat(failure.motivos()).isEmpty();
    }

    private static void assertInvalid(ThrowingCallable call, Motivo... expected) {
        var failure = validationFailure(call);
        assertThat(failure.reason()).isEqualTo(Reason.INVALID);
        assertThat(failure.motivos()).containsExactly(expected);
        assertThat(failure.submittedRevision()).isNull();
        assertThat(failure.currentRequirements()).isNull();
    }

    private static void assertObservationFailure(ThrowingCallable call) {
        assertThatThrownBy(call).isExactlyInstanceOf(IllegalStateException.class);
    }
}
