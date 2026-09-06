package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Decision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Membership;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Satisfaction;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Scope;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Snapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentLine;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentReference;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.EvidenceDocument;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementLine;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LegalRequirementSatisfactionEvaluatorTest {

    private static final String REQUIREMENT_KEY = "account-usage";
    private static final String DOCUMENT_KEY = "terms";
    private static final String STATEMENT = "Acepto las condiciones de uso de OrdenFix.";
    private static final String MARKDOWN = "# Condiciones de uso\n\nContenido legal del fixture.\n";
    private static final String STATEMENT_SHA = sha(STATEMENT);
    private static final String DOCUMENT_SHA = sha(MARKDOWN);
    private static final Instant ACCEPTED_AT = Instant.parse("2025-01-01T00:00:00Z");
    private static final OffsetDateTime EFFECTIVE_AT = OffsetDateTime.parse("2020-01-01T00:00:00Z");
    private static final LegalActorSnapshot ACTOR = new LegalActorSnapshot(11, 22, UserRole.USER, 3, true, true);
    private static final Set<AudienciaLegal> BOTH_AUDIENCES = Set.of(AudienciaLegal.ADMIN_TITULAR, AudienciaLegal.USER);
    private final LegalRequirementSatisfactionEvaluator evaluator = new LegalRequirementSatisfactionEvaluator();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noPriorEvidenceRemainsPendingRegardlessOfEditorialFlags(boolean requiresReacceptance) {
        DocumentVersion document = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, requiresReacceptance);
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, requiresReacceptance, ref(DOCUMENT_KEY, document));
        Fixture fixture = fixture(current, List.of(current), documentLine(DOCUMENT_KEY, document));

        assertDecision(evaluate(fixture, List.of()), current, Satisfaction.PENDING, null);
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void exactEvidenceSurvivesHistoricalRoleChangeAndItsOwnTrueFlags(UserRole currentRole) {
        DocumentVersion document = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, true);
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, true, ref(DOCUMENT_KEY, document));
        Fixture fixture = fixture(current, List.of(current), documentLine(DOCUMENT_KEY, document))
                .withActor(new LegalActorSnapshot(11, 22, currentRole, 8, true, true));
        UserRole historicalRole = currentRole == UserRole.ADMIN ? UserRole.USER : UserRole.ADMIN;
        Acceptance exact = evidence(current, uuid("exact-role"), historicalRole);

        assertDecision(evaluate(fixture, List.of(exact)), current, Satisfaction.EXACT, exact.acceptanceId());
        assertThat(exact.historicalRole()).isEqualTo(historicalRole);
        assertThat(exact.acceptedAt()).isEqualTo(ACCEPTED_AT);
    }

    @Test
    void exactEvidenceWinsOverLexicographicallyEarlierInheritedBase() {
        DocumentVersion document = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, true);
        RequirementVersion old = requirement(1, EstadoVersionLegal.REEMPLAZADA, true, ref(DOCUMENT_KEY, document));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, document));
        Fixture fixture = fixture(current, List.of(old, current), documentLine(DOCUMENT_KEY, document));
        Acceptance inherited = evidence(old, new UUID(0, 1), UserRole.USER);
        Acceptance exact = evidence(current, new UUID(0, 99), UserRole.USER);

        assertDecision(evaluate(fixture, List.of(inherited, exact)), current, Satisfaction.EXACT, exact.acceptanceId());
    }

    @Test
    void excludesBaseFlagsAndIncludesEveryFalseSuccessorWithoutRequiringConsecutiveOrdinals() {
        DocumentVersion first = document(DOCUMENT_KEY, 10, EstadoVersionLegal.REEMPLAZADA, true);
        DocumentVersion middle = document(DOCUMENT_KEY, 400, EstadoVersionLegal.PUBLICADA, false);
        DocumentVersion currentDocument = document(DOCUMENT_KEY, 1_000_000, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion base = requirement(10, EstadoVersionLegal.REEMPLAZADA, true, ref(DOCUMENT_KEY, first));
        RequirementVersion intermediate = requirement(400, EstadoVersionLegal.PUBLICADA, false, ref(DOCUMENT_KEY, middle));
        RequirementVersion current = requirement(1_000_000, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, currentDocument));
        Fixture fixture = fixture(current, List.of(base, intermediate, current), documentLine(DOCUMENT_KEY, first, middle, currentDocument));
        Acceptance evidence = evidence(base);

        assertDecision(evaluate(fixture, List.of(evidence)), current, Satisfaction.INHERITED, evidence.acceptanceId());
    }

    @ParameterizedTest
    @EnumSource(value = EstadoVersionLegal.class, names = {"BORRADOR", "PUBLICADA", "REEMPLAZADA", "RETIRADA"})
    void requirementFlagInAnyPublishedIntermediateVersionBlocksEvenAfterReturningToFalse(EstadoVersionLegal intermediateState) {
        DocumentVersion document = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, document));
        RequirementVersion middle = requirement(2, intermediateState, true, ref(DOCUMENT_KEY, document));
        RequirementVersion current = requirement(3, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, document));
        Fixture fixture = fixture(current, List.of(base, middle, current), documentLine(DOCUMENT_KEY, document));
        Acceptance evidence = evidence(base);
        Satisfaction expected = intermediateState == EstadoVersionLegal.BORRADOR ? Satisfaction.INHERITED : Satisfaction.PENDING;

        assertDecision(evaluate(fixture, List.of(evidence)), current, expected,
                expected == Satisfaction.INHERITED ? evidence.acceptanceId() : null);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoVersionLegal.class, names = {"BORRADOR", "PUBLICADA", "REEMPLAZADA", "RETIRADA"})
    void documentFlagUsesWholePublishedLineEvenWhenIntermediateIsNotReferencedByThisRequirement(EstadoVersionLegal intermediateState) {
        DocumentVersion first = document(DOCUMENT_KEY, 1, EstadoVersionLegal.REEMPLAZADA, false);
        DocumentVersion middle = document(DOCUMENT_KEY, 2, intermediateState, true);
        DocumentVersion last = document(DOCUMENT_KEY, 3, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, first));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, last));
        Fixture fixture = fixture(current, List.of(base, current), documentLine(DOCUMENT_KEY, first, middle, last));
        Acceptance evidence = evidence(base);
        Satisfaction expected = intermediateState == EstadoVersionLegal.BORRADOR ? Satisfaction.INHERITED : Satisfaction.PENDING;

        assertDecision(evaluate(fixture, List.of(evidence)), current, expected,
                expected == Satisfaction.INHERITED ? evidence.acceptanceId() : null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trueFlagAtCurrentRequirementOrDocumentIsIncluded(boolean atDocument) {
        DocumentVersion first = document(DOCUMENT_KEY, 1, EstadoVersionLegal.REEMPLAZADA, false);
        DocumentVersion last = document(DOCUMENT_KEY, 2, EstadoVersionLegal.VIGENTE, atDocument);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, first));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, !atDocument, ref(DOCUMENT_KEY, last));
        Fixture fixture = fixture(current, List.of(base, current), documentLine(DOCUMENT_KEY, first, last));

        assertDecision(evaluate(fixture, List.of(evidence(base))), current, Satisfaction.PENDING, null);
    }

    @Test
    void publishedTrueFlagsBeforeBaseAndAfterCurrentDoNotBlock() {
        DocumentVersion before = document(DOCUMENT_KEY, 1, EstadoVersionLegal.REEMPLAZADA, true);
        DocumentVersion baseDocument = document(DOCUMENT_KEY, 2, EstadoVersionLegal.REEMPLAZADA, true);
        DocumentVersion currentDocument = document(DOCUMENT_KEY, 3, EstadoVersionLegal.VIGENTE, false);
        DocumentVersion future = document(DOCUMENT_KEY, 4, EstadoVersionLegal.PUBLICADA, true);
        RequirementVersion beforeRequirement = requirement(1, EstadoVersionLegal.REEMPLAZADA, true, ref(DOCUMENT_KEY, before));
        RequirementVersion base = requirement(2, EstadoVersionLegal.REEMPLAZADA, true, ref(DOCUMENT_KEY, baseDocument));
        RequirementVersion current = requirement(3, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, currentDocument));
        RequirementVersion futureRequirement = requirement(4, EstadoVersionLegal.PUBLICADA, true, ref(DOCUMENT_KEY, future));
        Fixture fixture = fixture(current, List.of(beforeRequirement, base, current, futureRequirement),
                documentLine(DOCUMENT_KEY, before, baseDocument, currentDocument, future));
        Acceptance evidence = evidence(base);

        assertDecision(evaluate(fixture, List.of(evidence)), current, Satisfaction.INHERITED, evidence.acceptanceId());
    }

    @Test
    void addedDocumentKeyCannotInheritEvenWithSameDigestAndAllFalseFlags() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        DocumentVersion privacy = document("privacy", 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms), ref("privacy", privacy));
        Fixture fixture = fixture(current, List.of(base, current), documentLine(DOCUMENT_KEY, terms), documentLine("privacy", privacy));

        assertDecision(evaluate(fixture, List.of(evidence(base))), current, Satisfaction.PENDING, null);
    }

    @Test
    void removingADocumentDoesNotRequireTraversingItsLaterFlags() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        DocumentVersion privacy = document("privacy", 1, EstadoVersionLegal.REEMPLAZADA, false);
        DocumentVersion laterPrivacy = document("privacy", 2, EstadoVersionLegal.PUBLICADA, true);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms), ref("privacy", privacy));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(base, current), documentLine(DOCUMENT_KEY, terms), documentLine("privacy", privacy, laterPrivacy));
        Acceptance evidence = evidence(base);

        assertDecision(evaluate(fixture, List.of(evidence)), current, Satisfaction.INHERITED, evidence.acceptanceId());
    }

    @Test
    void neverUnionsDocumentsFromDifferentAcceptanceBases() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        DocumentVersion privacy = document("privacy", 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion first = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion second = requirement(2, EstadoVersionLegal.REEMPLAZADA, false, ref("privacy", privacy));
        RequirementVersion current = requirement(3, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms), ref("privacy", privacy));
        Fixture fixture = fixture(current, List.of(first, second, current), documentLine(DOCUMENT_KEY, terms), documentLine("privacy", privacy));

        assertDecision(evaluate(fixture, List.of(evidence(first), evidence(second))), current, Satisfaction.PENDING, null);
    }

    @Test
    void keepsAnOlderCompleteBaseWhenANewerBaseCannotCoverCurrentDocuments() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        DocumentVersion privacy = document("privacy", 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion first = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms), ref("privacy", privacy));
        RequirementVersion second = requirement(2, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(3, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms), ref("privacy", privacy));
        Fixture fixture = fixture(current, List.of(first, second, current), documentLine(DOCUMENT_KEY, terms), documentLine("privacy", privacy));
        Acceptance older = evidence(first);

        assertDecision(evaluate(fixture, List.of(evidence(second), older)), current, Satisfaction.INHERITED, older.acceptanceId());
    }

    @Test
    void aLaterExplicitAcceptanceBecomesABaseAfterAnEarlierReacceptanceBarrier() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion first = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion barrier = requirement(2, EstadoVersionLegal.REEMPLAZADA, true, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(3, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(first, barrier, current), documentLine(DOCUMENT_KEY, terms));
        Acceptance newer = evidence(barrier);

        assertDecision(evaluate(fixture, List.of(evidence(first), newer)), current, Satisfaction.INHERITED, newer.acceptanceId());
    }

    @Test
    void selectsLexicographicallySmallestValidAcceptanceIndependentlyOfInputOrderAndDate() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion first = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion second = requirement(2, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(3, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(first, second, current), documentLine(DOCUMENT_KEY, terms));
        Acceptance smaller = evidence(first, UUID.fromString("00000000-0000-0000-0000-000000000001"), UserRole.USER);
        Acceptance larger = new Acceptance(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                ACTOR.userId(), ACTOR.tallerId(), UserRole.USER, second.versionId(), second.statementSha256(),
                evidence(second).documents(), ACCEPTED_AT.plusSeconds(1));

        assertDecision(evaluate(fixture, List.of(larger, smaller)), current, Satisfaction.INHERITED, smaller.acceptanceId());
        assertDecision(evaluate(fixture, List.of(smaller, larger)), current, Satisfaction.INHERITED, smaller.acceptanceId());
    }

    @Test
    void currentDocumentOlderThanTheEvidenceCannotInheritBackwards() {
        DocumentVersion currentDocument = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        DocumentVersion evidencedDocument = document(DOCUMENT_KEY, 2, EstadoVersionLegal.REEMPLAZADA, false);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, evidencedDocument));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, currentDocument));
        Fixture fixture = fixture(current, List.of(base, current), documentLine(DOCUMENT_KEY, currentDocument, evidencedDocument));

        assertDecision(evaluate(fixture, List.of(evidence(base))), current, Satisfaction.PENDING, null);
    }

    @Test
    void evidenceFromAnotherRequirementKeyCannotSatisfyIdenticalContent() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion other = new RequirementVersion(uuid("other-requirement"), 1, EstadoVersionLegal.REEMPLAZADA,
                false, STATEMENT_SHA, true, List.of(ref(DOCUMENT_KEY, terms)));
        RequirementLine otherLine = new RequirementLine(uuid("other-line"), "different-key", ContextoLegal.USO_CONTINUADO,
                LocaleLegal.ES_AR, TipoActoLegal.ACEPTACION, BOTH_AUDIENCES, List.of(other));
        Fixture fixture = fixture(current, List.of(current), documentLine(DOCUMENT_KEY, terms)).withAdditionalLine(otherLine);

        assertDecision(evaluate(fixture, List.of(evidence(other))), current, Satisfaction.PENDING, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validatesForeignEvidenceEvenWhenAValidExactAcceptanceComesFirst(boolean foreignTenant) {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(base, current), documentLine(DOCUMENT_KEY, terms));
        Acceptance valid = evidence(base);
        Acceptance foreign = new Acceptance(valid.acceptanceId(), foreignTenant ? ACTOR.userId() : ACTOR.userId() + 1,
                foreignTenant ? ACTOR.tallerId() + 1 : ACTOR.tallerId(), valid.historicalRole(), valid.requirementVersionId(),
                valid.statementSha256(), valid.documents(), valid.acceptedAt());

        assertThatIllegalArgumentException().isThrownBy(() -> evaluate(fixture, List.of(evidence(current), foreign)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsCanonicalEvidenceDigestMismatchRegardlessOfExactEvidenceOrder(boolean exactFirst) {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(base, current), documentLine(DOCUMENT_KEY, terms));
        Acceptance valid = evidence(base);
        Acceptance bad = new Acceptance(valid.acceptanceId(), valid.userId(), valid.tallerId(), valid.historicalRole(),
                valid.requirementVersionId(), "0".repeat(64), valid.documents(), valid.acceptedAt());
        List<Acceptance> inputs = exactFirst ? List.of(evidence(current), bad) : List.of(bad, evidence(current));

        assertThatIllegalArgumentException().isThrownBy(() -> evaluate(fixture, inputs));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "extra", "duplicate", "wrong-key", "wrong-digest", "wrong-version", "reordered"})
    void exactUuidCannotHideInvalidEvidenceDocuments(String defect) {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, true);
        DocumentVersion privacy = document("privacy", 1, EstadoVersionLegal.VIGENTE, true);
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, true, ref(DOCUMENT_KEY, terms), ref("privacy", privacy));
        Fixture fixture = fixture(current, List.of(current), documentLine(DOCUMENT_KEY, terms), documentLine("privacy", privacy));
        Acceptance valid = evidence(current);
        List<EvidenceDocument> documents = new ArrayList<>(valid.documents());
        EvidenceDocument first = documents.getFirst();
        switch (defect) {
            case "missing" -> documents.removeLast();
            case "extra" -> documents.add(new EvidenceDocument("extra", uuid("extra-document"), DOCUMENT_SHA));
            case "duplicate" -> documents.add(first);
            case "wrong-key" -> documents.set(0, new EvidenceDocument("unseen-key", first.versionId(), first.sha256()));
            case "wrong-digest" -> documents.set(0, new EvidenceDocument(first.key(), first.versionId(), "0".repeat(64)));
            case "wrong-version" -> documents.set(0, new EvidenceDocument(first.key(), uuid("unknown-version"), first.sha256()));
            case "reordered" -> Collections.reverse(documents);
            default -> throw new AssertionError(defect);
        }

        assertThatIllegalArgumentException().isThrownBy(() -> {
            Acceptance invalid = new Acceptance(valid.acceptanceId(), valid.userId(), valid.tallerId(), valid.historicalRole(),
                    valid.requirementVersionId(), valid.statementSha256(), documents, valid.acceptedAt());
            evaluate(fixture, List.of(invalid));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDuplicateAcceptanceIdentityOrSameActorRequirementVersion(boolean sameVersion) {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(base, current), documentLine(DOCUMENT_KEY, terms));
        Acceptance first = evidence(current);
        Acceptance duplicate = evidence(sameVersion ? current : base,
                sameVersion ? uuid("another-acceptance") : first.acceptanceId(), UserRole.USER);

        assertThatIllegalArgumentException().isThrownBy(() -> evaluate(fixture, List.of(first, duplicate)));
    }

    @Test
    void historicalRoleMustHaveBelongedToTheRequirementAudienceEvenIfCurrentActorIsAllowed() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture original = fixture(current, List.of(current), documentLine(DOCUMENT_KEY, terms));
        RequirementLine originalLine = original.requirementLines().getFirst();
        RequirementLine userOnly = new RequirementLine(originalLine.lineId(), originalLine.key(), originalLine.context(),
                originalLine.locale(), originalLine.actType(), Set.of(AudienciaLegal.USER), originalLine.versions());
        Fixture fixture = new Fixture(ACTOR, List.of(userOnly), original.documentLines(), current);

        assertThatIllegalArgumentException().isThrownBy(() -> evaluate(fixture, List.of(evidence(current, uuid("wrong-role"), UserRole.ADMIN))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"required", "key", "act", "statement", "state"})
    void validatesCurrentCanonicalBindingBeforeGrantingExactSatisfaction(String defect) {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(current), documentLine(DOCUMENT_KEY, terms));
        RequirementLine original = fixture.requirementLines().getFirst();
        RequirementVersion mismatched = new RequirementVersion(current.versionId(), current.ordinal(),
                defect.equals("state") ? EstadoVersionLegal.PUBLICADA : current.state(), current.requiresReacceptance(),
                defect.equals("statement") ? "0".repeat(64) : current.statementSha256(),
                defect.equals("required") ? !current.required() : current.required(), current.documents());
        RequirementLine line = new RequirementLine(original.lineId(), defect.equals("key") ? "other-key" : original.key(),
                original.context(), original.locale(), defect.equals("act") ? TipoActoLegal.LECTURA : original.actType(),
                original.audiences(), List.of(mismatched));
        // The snapshot remains independently canonical; its binding to this supplied lineage is not.
        Snapshot snapshot = fixture.snapshot();

        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(snapshot,
                new LegalRequirementLineage(List.of(line), fixture.documentLines()), List.of(evidence(current))));
    }

    @Test
    void currentDocumentMustBeVigenteEvenWhenItsExactEvidenceIsOtherwiseCanonical() {
        DocumentVersion currentDocument = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, currentDocument));
        Fixture fixture = fixture(current, List.of(current), documentLine(DOCUMENT_KEY, currentDocument));
        DocumentVersion replaced = new DocumentVersion(currentDocument.versionId(), 1, EstadoVersionLegal.REEMPLAZADA, false, DOCUMENT_SHA);

        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(fixture.snapshot(),
                new LegalRequirementLineage(fixture.requirementLines(), List.of(documentLine(DOCUMENT_KEY, replaced))),
                List.of(evidence(current))));
    }

    @Test
    void validatesLaterCurrentMembersEvenWhenTheFirstAlreadyHasExactEvidence() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(current), documentLine(DOCUMENT_KEY, terms));
        Snapshot firstOnly = fixture.snapshot();
        RequirementProjection first = firstOnly.requirements().getFirst();
        UUID absentFromLineage = uuid("second-current-without-lineage");
        RequirementProjection second = new RequirementProjection(absentFromLineage, first.context(), first.actType(),
                first.statement(), first.statementSha256(), first.documents(), first.required());
        Snapshot complete = new Snapshot(ACTOR, firstOnly.applicableScopes(), List.of(new Scope(1,
                new LegalRequiredSetProjection(ContextoLegal.USO_CONTINUADO, LocaleLegal.ES_AR, List.of(first, second)),
                List.of(new Membership(7, REQUIREMENT_KEY, first.versionId()),
                        new Membership(11, "second-current", absentFromLineage)))));

        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(complete, fixture.lineage(),
                List.of(evidence(current))));
    }

    @Test
    void neverTreatsAnAcceptanceOfABorradorAsEvidence() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion draft = requirement(1, EstadoVersionLegal.BORRADOR, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(draft, current), documentLine(DOCUMENT_KEY, terms));

        assertThatIllegalArgumentException().isThrownBy(() -> evaluate(fixture, List.of(evidence(draft))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "negative", "unordered", "duplicate-ordinal", "duplicate-version"})
    void rejectsInvalidRequirementOrdinalOrVersionIdentityWithoutAssumingContiguousOrdinals(String defect) {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion first = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));

        assertThatIllegalArgumentException().isThrownBy(() -> {
            List<RequirementVersion> versions = switch (defect) {
                case "zero" -> List.of(requirement(0, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms)));
                case "negative" -> List.of(requirement(-1, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms)));
                case "unordered" -> List.of(current, first);
                case "duplicate-ordinal" -> List.of(first, new RequirementVersion(current.versionId(), 1,
                        current.state(), false, STATEMENT_SHA, true, current.documents()));
                case "duplicate-version" -> List.of(first, new RequirementVersion(first.versionId(), 2,
                        current.state(), false, STATEMENT_SHA, true, current.documents()));
                default -> throw new AssertionError(defect);
            };
            fixture(current, versions, documentLine(DOCUMENT_KEY, terms)).lineage();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"requirement-key", "requirement-line-id", "requirement-version-id",
            "document-key", "document-line-id", "document-version-id", "blank-requirement-key", "blank-document-key"})
    void rejectsDuplicateOrBlankCatalogIdentitiesBeforeSatisfaction(String defect) {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(current), documentLine(DOCUMENT_KEY, terms));
        RequirementLine requirementLine = fixture.requirementLines().getFirst();
        DocumentLine termsLine = fixture.documentLines().getFirst();

        assertThatIllegalArgumentException().isThrownBy(() -> {
            List<RequirementLine> requirements = new ArrayList<>(fixture.requirementLines());
            List<DocumentLine> documents = new ArrayList<>(fixture.documentLines());
            if (defect.contains("requirement")) {
                RequirementVersion nextVersion = new RequirementVersion(
                        defect.equals("requirement-version-id") ? current.versionId() : uuid("second-line-requirement"),
                        1, EstadoVersionLegal.PUBLICADA, false, STATEMENT_SHA, true, current.documents());
                requirements.add(new RequirementLine(
                        defect.equals("requirement-line-id") ? requirementLine.lineId() : uuid("second-line"),
                        defect.equals("requirement-key") ? requirementLine.key()
                                : defect.equals("blank-requirement-key") ? " " : "second-line",
                        requirementLine.context(), requirementLine.locale(), requirementLine.actType(),
                        requirementLine.audiences(), List.of(nextVersion)));
            } else {
                DocumentVersion nextVersion = new DocumentVersion(
                        defect.equals("document-version-id") ? terms.versionId() : uuid("second-document-version"),
                        1, EstadoVersionLegal.PUBLICADA, false, DOCUMENT_SHA);
                documents.add(new DocumentLine(
                        defect.equals("document-line-id") ? termsLine.lineId() : uuid("second-document-line"),
                        defect.equals("document-key") ? termsLine.key()
                                : defect.equals("blank-document-key") ? " " : "second-document",
                        termsLine.locale(), termsLine.type(), List.of(nextVersion)));
            }
            evaluator.evaluate(fixture.snapshot(), new LegalRequirementLineage(requirements, documents),
                    List.of(evidence(current)));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-key", "unknown-version", "wrong-digest"})
    void rejectsCatalogDocumentReferencesThatDoNotResolveCanonically(String defect) {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        DocumentReference bad = new DocumentReference(defect.equals("wrong-key") ? "foreign-key" : DOCUMENT_KEY,
                defect.equals("unknown-version") ? uuid("unknown-document") : terms.versionId(),
                defect.equals("wrong-digest") ? "0".repeat(64) : terms.sha256());
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, false, bad);

        assertThatIllegalArgumentException().isThrownBy(() -> fixture(current, List.of(current),
                documentLine(DOCUMENT_KEY, terms)).lineage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAnAbsentCurrentOrEvidenceEndpointWithoutClaimingToDetectOmittedIntermediateRows(boolean currentMissing) {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(base, current), documentLine(DOCUMENT_KEY, terms));
        Fixture incomplete = fixture(current, List.of(currentMissing ? base : current), documentLine(DOCUMENT_KEY, terms));

        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(fixture.snapshot(), incomplete.lineage(),
                List.of(evidence(base))));
    }

    @ParameterizedTest
    @ValueSource(ints = {4096, 4097})
    void boundsTheNumberOfRequirementIntervalRowsInsteadOfTheNumericOrdinalDistance(int intervalRows) {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        List<RequirementVersion> versions = new ArrayList<>(intervalRows + 1);
        for (int index = 0; index <= intervalRows; index++) {
            versions.add(requirement(index + 1, index == intervalRows ? EstadoVersionLegal.VIGENTE : EstadoVersionLegal.REEMPLAZADA,
                    false, ref(DOCUMENT_KEY, terms)));
        }
        RequirementVersion current = versions.getLast();
        Fixture fixture = fixture(current, versions, documentLine(DOCUMENT_KEY, terms));
        Acceptance evidence = evidence(versions.getFirst());

        if (intervalRows == 4096) {
            assertDecision(evaluate(fixture, List.of(evidence)), current, Satisfaction.INHERITED, evidence.acceptanceId());
        } else {
            assertThatIllegalArgumentException().isThrownBy(() -> evaluate(fixture, List.of(evidence)));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4096, 4097})
    void boundsDocumentIntervalRowsAsWellAsRequirementRows(int intervalRows) {
        List<DocumentVersion> documents = new ArrayList<>(intervalRows + 1);
        for (int index = 0; index <= intervalRows; index++) {
            documents.add(document(DOCUMENT_KEY, index + 1,
                    index == intervalRows ? EstadoVersionLegal.VIGENTE : EstadoVersionLegal.REEMPLAZADA, false));
        }
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, documents.getFirst()));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, documents.getLast()));
        Fixture fixture = fixture(current, List.of(base, current), new DocumentLine(uuid("document-line:" + DOCUMENT_KEY),
                DOCUMENT_KEY, LocaleLegal.ES_AR, TipoDocumentoLegal.TERMINOS_SERVICIO, documents));
        Acceptance evidence = evidence(base);

        if (intervalRows == 4096) {
            assertDecision(evaluate(fixture, List.of(evidence)), current, Satisfaction.INHERITED, evidence.acceptanceId());
        } else {
            assertThatIllegalArgumentException().isThrownBy(() -> evaluate(fixture, List.of(evidence)));
        }
    }

    @Test
    void observationBudgetIncludesAcceptanceAndEvidenceDocumentsBeforeExactShortcut() {
        List<DocumentVersion> documents = new ArrayList<>(65_533);
        documents.add(document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false));
        for (int ordinal = 2; ordinal <= 65_533; ordinal++) {
            documents.add(document(DOCUMENT_KEY, ordinal, EstadoVersionLegal.BORRADOR, false));
        }
        RequirementVersion current = requirement(1, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, documents.getFirst()));
        Fixture fixture = fixture(current, List.of(current), new DocumentLine(uuid("document-line:" + DOCUMENT_KEY),
                DOCUMENT_KEY, LocaleLegal.ES_AR, TipoDocumentoLegal.TERMINOS_SERVICIO, documents));
        LegalRequirementLineage lineage = fixture.lineage();
        // 65,533 document versions + one requirement + one document edge = 65,535.
        assertThat(lineage.rowCount()).isEqualTo(65_535);

        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(fixture.snapshot(), lineage,
                List.of(evidence(current)))); // One acceptance + its one document exceeds 65,536.
    }

    @Test
    void evaluationDoesNotMutateEvidenceOrRevisionWhenItInherits() {
        DocumentVersion terms = document(DOCUMENT_KEY, 1, EstadoVersionLegal.VIGENTE, false);
        RequirementVersion base = requirement(1, EstadoVersionLegal.REEMPLAZADA, false, ref(DOCUMENT_KEY, terms));
        RequirementVersion current = requirement(2, EstadoVersionLegal.VIGENTE, false, ref(DOCUMENT_KEY, terms));
        Fixture fixture = fixture(current, List.of(base, current), documentLine(DOCUMENT_KEY, terms));
        Acceptance acceptance = evidence(base);
        List<Acceptance> inputs = new ArrayList<>(List.of(acceptance));
        Snapshot snapshot = fixture.snapshot();
        String revision = snapshot.requiredSetRevision();

        LegalAuthenticatedRequirements result = evaluator.evaluate(snapshot, fixture.lineage(), inputs);
        inputs.clear();
        assertThat(result.requiredSetRevision()).isEqualTo(revision);
        assertThat(result.requirements()).isEmpty();
        assertThat(result.hasRequiredPending()).isFalse();
        assertThat(acceptance.acceptedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(acceptance.documents()).containsExactly(new EvidenceDocument(DOCUMENT_KEY, terms.versionId(), DOCUMENT_SHA));
        assertThat(result.decisions()).containsExactly(new Decision(current.versionId(), Satisfaction.INHERITED, acceptance.acceptanceId()));
    }

    private LegalAuthenticatedRequirements evaluate(Fixture fixture, List<Acceptance> evidence) {
        return evaluator.evaluate(fixture.snapshot(), fixture.lineage(), evidence);
    }

    private static void assertDecision(LegalAuthenticatedRequirements result, RequirementVersion requirement,
                                       Satisfaction satisfaction, UUID acceptanceId) {
        assertThat(result.decisions()).containsExactly(new Decision(requirement.versionId(), satisfaction, acceptanceId));
        assertThat(result.requirements()).hasSize(satisfaction == Satisfaction.PENDING ? 1 : 0);
        assertThat(result.hasRequiredPending()).isEqualTo(satisfaction == Satisfaction.PENDING && requirement.required());
    }

    private static Fixture fixture(RequirementVersion current, List<RequirementVersion> versions, DocumentLine... documents) {
        RequirementLine line = new RequirementLine(uuid("requirement-line"), REQUIREMENT_KEY, ContextoLegal.USO_CONTINUADO,
                LocaleLegal.ES_AR, TipoActoLegal.ACEPTACION, BOTH_AUDIENCES, versions);
        return new Fixture(ACTOR, List.of(line), List.of(documents), current);
    }

    private static RequirementVersion requirement(int ordinal, EstadoVersionLegal state, boolean reacceptance,
                                                   DocumentReference... documents) {
        return new RequirementVersion(uuid("requirement:" + ordinal), ordinal, state, reacceptance,
                STATEMENT_SHA, true, List.of(documents));
    }

    private static DocumentVersion document(String key, int ordinal, EstadoVersionLegal state, boolean reacceptance) {
        return new DocumentVersion(uuid("document:" + key + ":" + ordinal), ordinal, state, reacceptance, DOCUMENT_SHA);
    }

    private static DocumentLine documentLine(String key, DocumentVersion... versions) {
        return new DocumentLine(uuid("document-line:" + key), key, LocaleLegal.ES_AR,
                key.equals("privacy") ? TipoDocumentoLegal.POLITICA_PRIVACIDAD : TipoDocumentoLegal.TERMINOS_SERVICIO,
                List.of(versions));
    }

    private static DocumentReference ref(String key, DocumentVersion document) {
        return new DocumentReference(key, document.versionId(), document.sha256());
    }

    private static Acceptance evidence(RequirementVersion requirement) {
        return evidence(requirement, uuid("acceptance:" + requirement.versionId()), UserRole.USER);
    }

    private static Acceptance evidence(RequirementVersion requirement, UUID acceptanceId, UserRole historicalRole) {
        return new Acceptance(acceptanceId, ACTOR.userId(), ACTOR.tallerId(), historicalRole, requirement.versionId(),
                requirement.statementSha256(), requirement.documents().stream()
                .map(document -> new EvidenceDocument(document.key(), document.versionId(), document.sha256())).toList(), ACCEPTED_AT);
    }

    private record Fixture(LegalActorSnapshot actor, List<RequirementLine> requirementLines,
                           List<DocumentLine> documentLines, RequirementVersion current) {
        private Fixture withActor(LegalActorSnapshot nextActor) {
            return new Fixture(nextActor, requirementLines, documentLines, current);
        }

        private Fixture withAdditionalLine(RequirementLine line) {
            List<RequirementLine> lines = new ArrayList<>(requirementLines);
            lines.add(line);
            return new Fixture(actor, List.copyOf(lines), documentLines, current);
        }

        private LegalRequirementLineage lineage() {
            return new LegalRequirementLineage(requirementLines, documentLines);
        }

        private Snapshot snapshot() {
            RequirementLine line = requirementLines.getFirst();
            List<DocumentProjection> documents = current.documents().stream().map(reference -> {
                DocumentLine documentLine = documentLines.stream().filter(candidate -> candidate.key().equals(reference.key()))
                        .findFirst().orElseThrow();
                return new DocumentProjection(reference.versionId(), documentLine.type(), "1.0.0", "Condiciones de uso",
                        MARKDOWN, reference.sha256(), EFFECTIVE_AT, LocaleLegal.ES_AR);
            }).toList();
            RequirementProjection requirement = new RequirementProjection(current.versionId(), line.context(), line.actType(),
                    STATEMENT, current.statementSha256(), documents, current.required());
            LegalRequiredSetProjection projection = new LegalRequiredSetProjection(ContextoLegal.USO_CONTINUADO,
                    LocaleLegal.ES_AR, List.of(requirement));
            return new Snapshot(actor, new LegalApplicableScopeResolver().resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                    LocaleLegal.ES_AR, actor.audience()), List.of(new Scope(1, projection,
                    List.of(new Membership(7, line.key(), current.versionId())))));
        }
    }

    private static UUID uuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
