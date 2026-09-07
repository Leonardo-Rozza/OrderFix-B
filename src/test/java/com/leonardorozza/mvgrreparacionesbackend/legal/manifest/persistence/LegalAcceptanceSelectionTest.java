package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Membership;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Satisfaction;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Scope;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Snapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentLine;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentReference;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.EvidenceDocument;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementLine;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementSatisfactionEvaluator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceSelection.ExistingAcceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceSelection.Kind;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Motivo;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Reason;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalAcceptanceSelectionTest {
    private static final String OLD_REVISION = "sha256:" + "a".repeat(64);
    private static final String WRONG_DIGEST = "f".repeat(64);
    private static final LegalActorSnapshot ACTOR = new LegalActorSnapshot(11, 22, UserRole.ADMIN, 7, true, true);
    private static final DocumentProjection FIRST_DOCUMENT = document(302);
    private static final DocumentProjection SECOND_DOCUMENT = document(301);
    private static final RequirementProjection REQUIRED = requirement(20, true, FIRST_DOCUMENT, SECOND_DOCUMENT);
    private static final RequirementProjection OPTIONAL = requirement(10, false, FIRST_DOCUMENT);
    private final LegalAcceptanceSelection selector = new LegalAcceptanceSelection();

    @Test void selectsServerCanonicalNewRequirementsAndDocumentsInSnapshotOrder() {
        Fixture fixture = fixture(List.of(REQUIRED, OPTIONAL), Map.of());
        var command = command(fixture, List.of(submit(OPTIONAL), submit(REQUIRED)));
        assertThat(command.acceptances()).extracting(Acceptance::requisitoVersionId)
                .containsExactly(OPTIONAL.versionId(), REQUIRED.versionId());
        assertThat(command.acceptances().getLast().documentos()).extracting(Document::documentoVersionId)
                .containsExactly(SECOND_DOCUMENT.versionId(), FIRST_DOCUMENT.versionId());

        var result = selector.select(command, fixture.current(), List.of());

        assertThat(result.kind()).isEqualTo(Kind.WITH_ACTS);
        assertThat(result.newRequirements()).containsExactly(REQUIRED, OPTIONAL);
        assertThat(result.newRequirements().getFirst()).isSameAs(REQUIRED);
        assertThat(result.newRequirements().getFirst().documents()).containsExactly(FIRST_DOCUMENT, SECOND_DOCUMENT);
        assertThat(result.existingAcceptanceIds()).isEmpty();
    }

    @Test void optionalRequirementsCanBeOmittedAndSharedDocumentIdsAreNotGlobalDuplicates() {
        Fixture fixture = fixture(List.of(REQUIRED, OPTIONAL), Map.of());
        var requiredOnly = selector.select(command(fixture, List.of(submit(REQUIRED))), fixture.current(), List.of());
        assertThat(requiredOnly.newRequirements()).containsExactly(REQUIRED);
        assertThat(selector.select(command(fixture, List.of(submit(REQUIRED), submit(OPTIONAL))),
                fixture.current(), List.of()).newRequirements()).containsExactly(REQUIRED, OPTIONAL);
    }

    @ParameterizedTest
    @EnumSource(value = Satisfaction.class, names = {"EXACT", "INHERITED"})
    void aSatisfiedMandatoryRequirementCanBeOmittedWhenAnotherRequirementIsAccepted(Satisfaction satisfaction) {
        Fixture fixture = fixture(List.of(REQUIRED, OPTIONAL), Map.of(REQUIRED.versionId(), satisfaction));
        var result = selector.select(command(fixture, List.of(submit(OPTIONAL))), fixture.current(), List.of());

        assertThat(fixture.current().hasRequiredPending()).isFalse();
        assertThat(result.kind()).isEqualTo(Kind.WITH_ACTS);
        assertThat(result.newRequirements()).containsExactly(OPTIONAL);
        assertThat(result.existingAcceptanceIds()).isEmpty();
    }

    @Test void anExplicitCurrentAcceptanceOfAnInheritedVersionIsARealNewAct() {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of(REQUIRED.versionId(), Satisfaction.INHERITED));
        var result = selector.select(command(fixture, List.of(submit(REQUIRED))), fixture.current(), List.of());

        assertThat(fixture.current().requirements()).isEmpty();
        assertThat(fixture.current().decisions().getFirst().satisfaction()).isEqualTo(Satisfaction.INHERITED);
        assertThat(result.kind()).isEqualTo(Kind.WITH_ACTS);
        assertThat(result.newRequirements()).containsExactly(REQUIRED);
        assertThat(result.existingAcceptanceIds()).isEmpty();
    }

    @Test void mixedRequestsReuseOnlyExactSubmittedActsAndInsertOnlyTheNewCanonicalVersions() {
        Fixture fixture = fixture(List.of(REQUIRED, OPTIONAL), Map.of(REQUIRED.versionId(), Satisfaction.EXACT));
        ExistingAcceptance existing = fixture.exact().get(REQUIRED.versionId());
        var result = selector.select(command(fixture, List.of(submit(REQUIRED), submit(OPTIONAL))),
                fixture.current(), List.of(existing));

        assertThat(result.kind()).isEqualTo(Kind.WITH_ACTS);
        assertThat(result.newRequirements()).containsExactly(OPTIONAL);
        assertThat(result.existingAcceptanceIds()).containsExactly(existing.acceptanceId());
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void completeHistoricalDedupIgnoresFreshnessCurrentMembershipAndNewMandatoryPending(UserRole role) {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of(),
                new LegalActorSnapshot(ACTOR.userId(), ACTOR.tallerId(), role, 8, true, true));
        ExistingAcceptance earlier = historical(uuid(100), uuid(200), List.of(doc(FIRST_DOCUMENT)));
        var command = command(fixture, OLD_REVISION, List.of(submit(earlier)));

        var result = selector.select(command, fixture.current(), List.of(earlier));

        assertThat(fixture.current().hasRequiredPending()).isTrue();
        assertThat(result.kind()).isEqualTo(Kind.DEDUP);
        assertThat(result.newRequirements()).isEmpty();
        assertThat(result.existingAcceptanceIds()).containsExactly(earlier.acceptanceId());
    }

    @Test void exactDedupComparesDocumentSetsAndOrdersExistingIdsByCanonicalUnsignedUuid() {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of());
        ExistingAcceptance high = historical(new UUID(-1, -1), uuid(201),
                List.of(doc(FIRST_DOCUMENT), doc(SECOND_DOCUMENT)));
        ExistingAcceptance low = historical(new UUID(0, 1), uuid(202), List.of(doc(FIRST_DOCUMENT)));
        List<Acceptance> input = List.of(submit(low), submit(high));
        var result = selector.select(command(fixture, OLD_REVISION, input), fixture.current(), List.of(high, low));

        assertThat(result.kind()).isEqualTo(Kind.DEDUP);
        assertThat(result.existingAcceptanceIds()).containsExactly(low.acceptanceId(), high.acceptanceId());
        assertThat(high.documents()).containsExactly(doc(FIRST_DOCUMENT), doc(SECOND_DOCUMENT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"requirements", "documents"})
    void duplicatesWithAlreadyConfirmedEvidenceStillRequireFreshnessThenTheirSemanticReason(String duplicated) {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of(REQUIRED.versionId(), Satisfaction.EXACT));
        ExistingAcceptance exact = fixture.exact().get(REQUIRED.versionId());
        Acceptance normal = submit(REQUIRED);
        List<Acceptance> input = duplicated.equals("requirements") ? List.of(normal, normal)
                : List.of(new Acceptance(normal.requisitoVersionId(), normal.tipoActo(), normal.afirmacionSha256(),
                    List.of(doc(FIRST_DOCUMENT), doc(SECOND_DOCUMENT), doc(FIRST_DOCUMENT)), true));

        assertStale(() -> selector.select(command(fixture, OLD_REVISION, input), fixture.current(), List.of(exact)), fixture);
        assertInvalid(() -> selector.select(command(fixture, input), fixture.current(), List.of(exact)),
                duplicated.equals("requirements") ? Motivo.REQUISITO_DUPLICADO : Motivo.DOCUMENTO_DUPLICADO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-requirement", "unknown-requirement", "missing-document", "unknown-document",
            "statement-digest", "document-digest", "act", "confirmation", "requirement-duplicate", "document-duplicate"})
    void staleRevisionWinsOverEveryClientSemanticDefect(String defect) {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of());
        List<Acceptance> input = defective(defect);
        assertStale(() -> selector.select(command(fixture, OLD_REVISION, input), fixture.current(), List.of()), fixture);
        var failure = validationFailure(() -> selector.select(command(fixture, input), fixture.current(), List.of()));
        assertThat(failure.reason()).isEqualTo(Reason.INVALID);
        assertThat(failure.motivos()).contains(switch (defect) {
            case "missing-requirement" -> Motivo.REQUISITO_FALTANTE;
            case "unknown-requirement" -> Motivo.REQUISITO_NO_PERTENECE_AL_CONJUNTO;
            case "missing-document" -> Motivo.DOCUMENTO_FALTANTE;
            case "unknown-document" -> Motivo.DOCUMENTO_NO_PERTENECE_AL_REQUISITO;
            case "statement-digest", "document-digest" -> Motivo.DIGEST_NO_COINCIDE;
            case "act" -> Motivo.ACTO_NO_COINCIDE;
            case "confirmation" -> Motivo.CONFIRMACION_REQUERIDA;
            case "requirement-duplicate" -> Motivo.REQUISITO_DUPLICADO;
            case "document-duplicate" -> Motivo.DOCUMENTO_DUPLICADO;
            default -> throw new AssertionError(defect);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"statement-digest", "document-digest", "act", "confirmation", "missing-document", "unknown-document"})
    void anExistingUuidAloneCannotTurnAnIncorrectConfirmationIntoDedup(String defect) {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of(REQUIRED.versionId(), Satisfaction.EXACT));
        List<ExistingAcceptance> existing = List.of(fixture.exact().get(REQUIRED.versionId()));
        assertStale(() -> selector.select(command(fixture, OLD_REVISION, defective(defect)), fixture.current(), existing), fixture);
        var failure = validationFailure(() -> selector.select(command(fixture, defective(defect)), fixture.current(), existing));
        assertThat(failure.reason()).isEqualTo(Reason.INVALID);
    }

    @Test void aMixedRequestCannotCarryOldOrNonApplicableEvidenceAlongsideANewAct() {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of());
        ExistingAcceptance old = historical(uuid(101), uuid(201), List.of(doc(FIRST_DOCUMENT)));
        List<Acceptance> mixed = List.of(submit(old), submit(REQUIRED));

        assertStale(() -> selector.select(command(fixture, OLD_REVISION, mixed), fixture.current(), List.of(old)), fixture);
        assertInvalid(() -> selector.select(command(fixture, mixed), fixture.current(), List.of(old)),
                Motivo.REQUISITO_NO_PERTENECE_AL_CONJUNTO);
    }

    @Test void partialHistoricalDedupDoesNotHideAnUnconfirmedOrUnknownHistoricalItem() {
        Fixture fixture = fixture(List.of(), Map.of());
        ExistingAcceptance old = historical(uuid(101), uuid(201), List.of(doc(FIRST_DOCUMENT)));
        ExistingAcceptance other = historical(uuid(102), uuid(202), List.of(doc(FIRST_DOCUMENT)));
        List<Acceptance> input = List.of(submit(old), submit(other));

        assertStale(() -> selector.select(command(fixture, OLD_REVISION, input), fixture.current(), List.of(old)), fixture);
        assertInvalid(() -> selector.select(command(fixture, input), fixture.current(), List.of(old)),
                Motivo.REQUISITO_NO_PERTENECE_AL_CONJUNTO);
    }

    @Test void emptyRequestsAlwaysCheckFreshnessAndOnlyRequiredPendingCanBlockThem() {
        Fixture mandatory = fixture(List.of(REQUIRED, OPTIONAL), Map.of());
        assertStale(() -> selector.select(command(mandatory, OLD_REVISION, List.of()), mandatory.current(), List.of()), mandatory);
        assertInvalid(() -> selector.select(command(mandatory, List.of()), mandatory.current(), List.of()), Motivo.REQUISITO_FALTANTE);

        for (Fixture fixture : List.of(fixture(List.of(), Map.of()), fixture(List.of(OPTIONAL), Map.of()),
                fixture(List.of(REQUIRED, OPTIONAL), Map.of(REQUIRED.versionId(), Satisfaction.EXACT)),
                fixture(List.of(REQUIRED, OPTIONAL), Map.of(REQUIRED.versionId(), Satisfaction.INHERITED)))) {
            assertStale(() -> selector.select(command(fixture, OLD_REVISION, List.of()), fixture.current(), List.of()), fixture);
            var result = selector.select(command(fixture, List.of()), fixture.current(), List.of());
            assertThat(result.kind()).isEqualTo(Kind.EMPTY);
            assertThat(result.newRequirements()).isEmpty();
            assertThat(result.existingAcceptanceIds()).isEmpty();
        }
    }

    @Test void semanticReasonsAreUniqueOrderedAndDoNotMislabelInvalidFieldsAsAbsentRequirements() {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of());
        Acceptance wrong = new Acceptance(REQUIRED.versionId(), TipoActoLegal.LECTURA, WRONG_DIGEST,
                List.of(new Document(uuid(999), WRONG_DIGEST), new Document(uuid(999), WRONG_DIGEST)), false);
        assertInvalid(() -> selector.select(command(fixture, List.of(wrong, wrong)), fixture.current(), List.of()),
                Motivo.REQUISITO_DUPLICADO, Motivo.DOCUMENTO_FALTANTE, Motivo.DOCUMENTO_DUPLICADO,
                Motivo.DOCUMENTO_NO_PERTENECE_AL_REQUISITO, Motivo.DIGEST_NO_COINCIDE,
                Motivo.ACTO_NO_COINCIDE, Motivo.CONFIRMACION_REQUERIDA);
    }

    @Test void allPublishedMotivoNamesAndTheirOrderRemainFrozen() {
        assertThat(Motivo.values()).extracting(Enum::name).containsExactly(
                "REQUISITO_FALTANTE", "REQUISITO_DUPLICADO", "REQUISITO_NO_PERTENECE_AL_CONJUNTO",
                "DOCUMENTO_FALTANTE", "DOCUMENTO_DUPLICADO", "DOCUMENTO_NO_PERTENECE_AL_REQUISITO",
                "DIGEST_NO_COINCIDE", "ACTO_NO_COINCIDE", "CONFIRMACION_REQUERIDA", "PAYLOAD_LEGAL_INCOMPLETO");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-exact", "different-exact-id", "different-canonical-act", "different-canonical-digest",
            "different-canonical-documents", "foreign-user", "foreign-workshop", "duplicate-evidence", "unrequested-evidence"})
    void contradictoryEvidenceFailsAsAnObservationEvenBeforeFreshness(String defect) {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of(REQUIRED.versionId(), Satisfaction.EXACT));
        ExistingAcceptance exact = fixture.exact().get(REQUIRED.versionId());
        ExistingAcceptance changed = switch (defect) {
            case "different-exact-id" -> new ExistingAcceptance(uuid(999), ACTOR.userId(), ACTOR.tallerId(),
                    exact.requirementVersionId(), exact.actType(), exact.statementSha256(), exact.documents());
            case "different-canonical-act" -> new ExistingAcceptance(exact.acceptanceId(), ACTOR.userId(), ACTOR.tallerId(),
                    exact.requirementVersionId(), TipoActoLegal.LECTURA, exact.statementSha256(), exact.documents());
            case "different-canonical-digest" -> new ExistingAcceptance(exact.acceptanceId(), ACTOR.userId(), ACTOR.tallerId(),
                    exact.requirementVersionId(), exact.actType(), WRONG_DIGEST, exact.documents());
            case "different-canonical-documents" -> new ExistingAcceptance(exact.acceptanceId(), ACTOR.userId(), ACTOR.tallerId(),
                    exact.requirementVersionId(), exact.actType(), exact.statementSha256(), List.of(doc(FIRST_DOCUMENT)));
            case "foreign-user" -> new ExistingAcceptance(exact.acceptanceId(), 91, ACTOR.tallerId(),
                    exact.requirementVersionId(), exact.actType(), exact.statementSha256(), exact.documents());
            case "foreign-workshop" -> new ExistingAcceptance(exact.acceptanceId(), ACTOR.userId(), 92,
                    exact.requirementVersionId(), exact.actType(), exact.statementSha256(), exact.documents());
            case "unrequested-evidence" -> historical(uuid(999), uuid(998), List.of(doc(FIRST_DOCUMENT)));
            default -> exact;
        };
        List<ExistingAcceptance> existing = defect.equals("missing-exact") ? List.of()
                : defect.equals("duplicate-evidence") ? List.of(exact, exact) : List.of(changed);
        assertObservationFailure(() -> selector.select(command(fixture, OLD_REVISION, List.of(submit(REQUIRED))),
                fixture.current(), existing));
    }

    @ParameterizedTest
    @EnumSource(value = Satisfaction.class, names = {"PENDING", "INHERITED"})
    void currentPendingOrInheritedCannotContradictAnExactPersistedAct(Satisfaction satisfaction) {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of(REQUIRED.versionId(), satisfaction));
        ExistingAcceptance exact = canonical(REQUIRED, uuid(101));
        assertObservationFailure(() -> selector.select(command(fixture, OLD_REVISION, List.of(submit(REQUIRED))),
                fixture.current(), List.of(exact)));
    }

    @Test void duplicateEvidenceIdsAcrossDistinctRequirementsAreNotSilentlyCollapsed() {
        Fixture fixture = fixture(List.of(), Map.of());
        ExistingAcceptance first = historical(uuid(99), uuid(101), List.of(doc(FIRST_DOCUMENT)));
        ExistingAcceptance second = historical(uuid(99), uuid(102), List.of(doc(FIRST_DOCUMENT)));
        assertObservationFailure(() -> selector.select(command(fixture, OLD_REVISION, List.of(submit(first), submit(second))),
                fixture.current(), List.of(first, second)));
    }

    @Test void selectionCannotCrossActorRoleTokenOrOperationBoundaries() {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of());
        for (var actor : List.of(new LegalActorSnapshot(91, 22, UserRole.ADMIN, 7, true, true),
                new LegalActorSnapshot(11, 92, UserRole.ADMIN, 7, true, true),
                new LegalActorSnapshot(11, 22, UserRole.USER, 7, true, true),
                new LegalActorSnapshot(11, 22, UserRole.ADMIN, 8, true, true))) {
            var command = LegalAcceptanceCommandValidator.authenticated(actor, OLD_REVISION, List.of(submit(REQUIRED)));
            assertObservationFailure(() -> selector.select(command, fixture.current(), List.of()));
        }
        var registration = LegalAcceptanceCommandValidator.registration(new LegalAcceptanceCommand.Registration(
                "Taller", null, "Admin", "admin@example.invalid", "private-password"), OLD_REVISION, List.of());
        assertObservationFailure(() -> selector.select(registration, fixture.current(), List.of()));
        assertObservationFailure(() -> selector.select(null, fixture.current(), List.of()));
        assertObservationFailure(() -> selector.select(command(fixture, List.of()), null, List.of()));
        assertObservationFailure(() -> selector.select(command(fixture, List.of()), fixture.current(), null));
    }

    @Test void fullDedupSupports2048HistoricalActsWhileObservationInputIsBounded() {
        Fixture fixture = fixture(List.of(REQUIRED), Map.of());
        List<ExistingAcceptance> existing = new ArrayList<>();
        for (int i = 0; i < 2048; i++) existing.add(historical(uuid(10_000 + i), uuid(20_000 + i), List.of(doc(FIRST_DOCUMENT))));
        var command = command(fixture, OLD_REVISION, existing.stream().map(LegalAcceptanceSelectionTest::submit).toList());
        var result = selector.select(command, fixture.current(), existing);
        assertThat(result.kind()).isEqualTo(Kind.DEDUP);
        assertThat(result.existingAcceptanceIds()).hasSize(2048).doesNotHaveDuplicates();
        assertObservationFailure(() -> selector.select(command, fixture.current(), Collections.nCopies(2049, existing.getFirst())));
    }

    @Test void evidenceAndResultsKeepDefensiveListsAndRedactedDiagnostics() {
        List<Document> documents = new ArrayList<>(List.of(doc(FIRST_DOCUMENT)));
        var existing = historical(uuid(101), uuid(201), documents);
        documents.clear();
        assertThat(existing.documents()).containsExactly(doc(FIRST_DOCUMENT));
        assertThatThrownBy(() -> existing.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
        Fixture fixture = fixture(List.of(REQUIRED), Map.of());
        var dedup = selector.select(command(fixture, OLD_REVISION, List.of(submit(existing))), fixture.current(), List.of(existing));
        assertThatThrownBy(() -> dedup.existingAcceptanceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        var selected = selector.select(command(fixture, List.of(submit(REQUIRED))), fixture.current(), List.of());
        assertThatThrownBy(() -> selected.newRequirements().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(existing.toString()).isEqualTo("ExistingAcceptance[redacted]");
        assertThat(selected.toString()).isEqualTo("LegalAcceptanceSelection[redacted]");
        assertThat(existing.toString() + selected).doesNotContain(existing.acceptanceId().toString(),
                existing.statementSha256(), REQUIRED.statement(), OLD_REVISION);
    }

    @Test void malformedCanonicalEvidenceCannotEnterSelectionAsAClientValidationError() {
        for (List<Document> documents : List.of(List.<Document>of(),
                List.of(doc(FIRST_DOCUMENT), doc(FIRST_DOCUMENT)),
                List.of(new Document(FIRST_DOCUMENT.versionId(), "PRIVATE_DIGEST")),
                List.of(new Document(null, FIRST_DOCUMENT.sha256())),
                Collections.nCopies(17, doc(FIRST_DOCUMENT)))) {
            assertObservationFailure(() -> historical(uuid(101), uuid(201), documents));
        }
        assertObservationFailure(() -> historical(uuid(101), uuid(201), null));
        assertObservationFailure(() -> new ExistingAcceptance(null, 11, 22, uuid(201), TipoActoLegal.ACEPTACION,
                REQUIRED.statementSha256(), List.of(doc(FIRST_DOCUMENT))));
        assertObservationFailure(() -> new ExistingAcceptance(uuid(101), 0, 22, uuid(201), TipoActoLegal.ACEPTACION,
                REQUIRED.statementSha256(), List.of(doc(FIRST_DOCUMENT))));
    }

    private static List<Acceptance> defective(String defect) {
        Acceptance correct = submit(REQUIRED);
        return switch (defect) {
            case "missing-requirement" -> List.of();
            case "unknown-requirement" -> List.of(new Acceptance(uuid(999), correct.tipoActo(), correct.afirmacionSha256(), correct.documentos(), true));
            case "missing-document" -> List.of(new Acceptance(correct.requisitoVersionId(), correct.tipoActo(), correct.afirmacionSha256(), List.of(), true));
            case "unknown-document" -> List.of(new Acceptance(correct.requisitoVersionId(), correct.tipoActo(), correct.afirmacionSha256(),
                    List.of(doc(FIRST_DOCUMENT), doc(SECOND_DOCUMENT), new Document(uuid(999), WRONG_DIGEST)), true));
            case "statement-digest" -> List.of(new Acceptance(correct.requisitoVersionId(), correct.tipoActo(), WRONG_DIGEST, correct.documentos(), true));
            case "document-digest" -> List.of(new Acceptance(correct.requisitoVersionId(), correct.tipoActo(), correct.afirmacionSha256(),
                    List.of(new Document(FIRST_DOCUMENT.versionId(), WRONG_DIGEST), doc(SECOND_DOCUMENT)), true));
            case "act" -> List.of(new Acceptance(correct.requisitoVersionId(), TipoActoLegal.LECTURA, correct.afirmacionSha256(), correct.documentos(), true));
            case "confirmation" -> List.of(new Acceptance(correct.requisitoVersionId(), correct.tipoActo(), correct.afirmacionSha256(), correct.documentos(), false));
            case "requirement-duplicate" -> List.of(correct, correct);
            case "document-duplicate" -> List.of(new Acceptance(correct.requisitoVersionId(), correct.tipoActo(), correct.afirmacionSha256(),
                    List.of(doc(FIRST_DOCUMENT), doc(SECOND_DOCUMENT), doc(FIRST_DOCUMENT)), true));
            default -> throw new AssertionError(defect);
        };
    }

    private static Fixture fixture(List<RequirementProjection> current, Map<UUID, Satisfaction> statuses) {
        return fixture(current, statuses, ACTOR);
    }

    /** Uses the real evaluator so EXACT, INHERITED and PENDING cannot be invented by the test. */
    private static Fixture fixture(List<RequirementProjection> current, Map<UUID, Satisfaction> statuses, LegalActorSnapshot actor) {
        List<RequirementLine> lines = new ArrayList<>();
        Map<UUID, DocumentLine> documents = new LinkedHashMap<>();
        List<LegalRequirementLineage.Acceptance> evidence = new ArrayList<>();
        Map<UUID, ExistingAcceptance> exact = new HashMap<>();
        List<Membership> memberships = new ArrayList<>();
        for (RequirementProjection requirement : current) {
            String key = "requirement-" + requirement.versionId().getLeastSignificantBits();
            memberships.add(new Membership(memberships.size() + 1, key, requirement.versionId()));
            List<DocumentReference> references = requirement.documents().stream()
                    .map(value -> new DocumentReference(documentKey(value.versionId()), value.versionId(), value.sha256())).toList();
            requirement.documents().forEach(value -> documents.putIfAbsent(value.versionId(), new DocumentLine(
                    named("line-" + value.versionId()), documentKey(value.versionId()), LocaleLegal.ES_AR, value.type(),
                    List.of(new DocumentVersion(value.versionId(), 1, EstadoVersionLegal.VIGENTE, false, value.sha256())))));
            Satisfaction satisfaction = statuses.getOrDefault(requirement.versionId(), Satisfaction.PENDING);
            List<RequirementVersion> versions = new ArrayList<>();
            if (satisfaction == Satisfaction.INHERITED) {
                UUID oldId = named("old-" + requirement.versionId());
                String oldDigest = sha("Afirmación histórica " + requirement.versionId());
                versions.add(new RequirementVersion(oldId, 1, EstadoVersionLegal.REEMPLAZADA, false,
                        oldDigest, requirement.required(), references));
                evidence.add(evidence(actor, named("inherited-" + requirement.versionId()), oldId, oldDigest, references));
            }
            versions.add(new RequirementVersion(requirement.versionId(), 2, EstadoVersionLegal.VIGENTE, false,
                    requirement.statementSha256(), requirement.required(), references));
            lines.add(new RequirementLine(named("line-" + requirement.versionId()), key, requirement.context(), LocaleLegal.ES_AR,
                    requirement.actType(), Set.of(AudienciaLegal.ADMIN_TITULAR, AudienciaLegal.USER), versions));
            if (satisfaction == Satisfaction.EXACT) {
                UUID acceptanceId = named("exact-" + requirement.versionId());
                evidence.add(evidence(actor, acceptanceId, requirement.versionId(), requirement.statementSha256(), references));
                exact.put(requirement.versionId(), new ExistingAcceptance(acceptanceId, actor.userId(), actor.tallerId(),
                        requirement.versionId(), requirement.actType(), requirement.statementSha256(),
                        requirement.documents().stream().map(LegalAcceptanceSelectionTest::doc).toList()));
            }
        }
        var scopes = new LegalApplicableScopeResolver().resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, actor.audience());
        var snapshot = new Snapshot(actor, scopes, List.of(new Scope(1,
                new LegalRequiredSetProjection(ContextoLegal.USO_CONTINUADO, LocaleLegal.ES_AR, current), memberships)));
        var evaluated = new LegalRequirementSatisfactionEvaluator().evaluate(snapshot,
                new LegalRequirementLineage(lines, List.copyOf(documents.values())), evidence);
        return new Fixture(actor, evaluated, Map.copyOf(exact));
    }

    private static LegalRequirementLineage.Acceptance evidence(LegalActorSnapshot actor, UUID id, UUID requirementId,
            String digest, List<DocumentReference> documents) {
        return new LegalRequirementLineage.Acceptance(id, actor.userId(), actor.tallerId(),
                actor.role() == UserRole.ADMIN ? UserRole.USER : UserRole.ADMIN, requirementId, digest,
                documents.stream().map(value -> new EvidenceDocument(value.key(), value.versionId(), value.sha256())).toList(),
                Instant.parse("2025-01-01T00:00:00Z"));
    }

    private static ExistingAcceptance canonical(RequirementProjection requirement, UUID id) {
        return new ExistingAcceptance(id, ACTOR.userId(), ACTOR.tallerId(), requirement.versionId(), requirement.actType(),
                requirement.statementSha256(), requirement.documents().stream().map(LegalAcceptanceSelectionTest::doc).toList());
    }

    private static ExistingAcceptance historical(UUID id, UUID requirement, List<Document> documents) {
        return new ExistingAcceptance(id, ACTOR.userId(), ACTOR.tallerId(), requirement, TipoActoLegal.DECLARACION,
                sha("Afirmación anterior " + requirement), documents);
    }

    private static Acceptance submit(RequirementProjection requirement) {
        return new Acceptance(requirement.versionId(), requirement.actType(), requirement.statementSha256(),
                requirement.documents().stream().map(LegalAcceptanceSelectionTest::doc).toList(), true);
    }

    private static Acceptance submit(ExistingAcceptance evidence) {
        return new Acceptance(evidence.requirementVersionId(), evidence.actType(), evidence.statementSha256(), evidence.documents(), true);
    }

    private static Document doc(DocumentProjection document) { return new Document(document.versionId(), document.sha256()); }

    private static LegalAcceptanceCommand command(Fixture fixture, List<Acceptance> acceptances) {
        return command(fixture, fixture.current().requiredSetRevision(), acceptances);
    }

    private static LegalAcceptanceCommand command(Fixture fixture, String revision, List<Acceptance> acceptances) {
        return LegalAcceptanceCommandValidator.authenticated(fixture.actor(), revision, acceptances);
    }

    private static RequirementProjection requirement(long id, boolean required, DocumentProjection... documents) {
        String statement = "Confirmo el requisito canónico " + id + ".";
        return new RequirementProjection(uuid(id), ContextoLegal.USO_CONTINUADO,
                id == 10 ? TipoActoLegal.LECTURA : TipoActoLegal.ACEPTACION, statement, sha(statement), List.of(documents), required);
    }

    private static DocumentProjection document(long id) {
        String markdown = "# Documento " + id + "\n\nContenido legal canónico.\n";
        return new DocumentProjection(uuid(id), id == 301 ? TipoDocumentoLegal.POLITICA_PRIVACIDAD : TipoDocumentoLegal.TERMINOS_SERVICIO,
                "2026.1", "Documento " + id,
                markdown, sha(markdown), OffsetDateTime.parse("2020-01-01T00:00:00Z"), LocaleLegal.ES_AR);
    }

    private static String documentKey(UUID id) { return "document-" + id.getLeastSignificantBits(); }
    private static UUID uuid(long number) { return new UUID(0, number); }
    private static UUID named(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static LegalAcceptanceValidationException validationFailure(ThrowingCallable call) {
        Throwable failure = catchThrowable(call);
        assertThat(failure).isExactlyInstanceOf(LegalAcceptanceValidationException.class).hasNoCause();
        return (LegalAcceptanceValidationException) failure;
    }

    private static void assertStale(ThrowingCallable call, Fixture fixture) {
        var failure = validationFailure(call);
        assertThat(failure.reason()).isEqualTo(Reason.STALE);
        assertThat(failure.submittedRevision()).isEqualTo(OLD_REVISION);
        assertThat(failure.currentRequirements()).isSameAs(fixture.current());
        assertThat(failure.motivos()).isEmpty();
        assertThat(failure.getMessage() + failure).doesNotContain(OLD_REVISION, REQUIRED.statement(), ACTOR.toString());
    }

    private static void assertInvalid(ThrowingCallable call, Motivo... expected) {
        var failure = validationFailure(call);
        assertThat(failure.reason()).isEqualTo(Reason.INVALID);
        assertThat(failure.submittedRevision()).isNull();
        assertThat(failure.currentRequirements()).isNull();
        assertThat(failure.motivos()).containsExactly(expected);
        assertThatThrownBy(() -> failure.motivos().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(failure.getMessage() + failure).doesNotContain(OLD_REVISION, REQUIRED.statement(), ACTOR.toString());
    }

    private static void assertObservationFailure(ThrowingCallable call) {
        assertThatThrownBy(call).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("La observación legal no es válida.").hasNoCause();
    }

    private record Fixture(LegalActorSnapshot actor, LegalAuthenticatedRequirements current, Map<UUID, ExistingAcceptance> exact) { }
}
