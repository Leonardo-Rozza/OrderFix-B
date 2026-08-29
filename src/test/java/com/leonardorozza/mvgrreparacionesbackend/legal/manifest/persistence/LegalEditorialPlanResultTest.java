package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialPlanResultTest {

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-28T18:00:00.123456Z");
    private static final UUID TARGET_PUBLICATION = new UUID(0, 1);
    private static final UUID DOCUMENT_VERSION = new UUID(0, 2);
    private static final String SHA = "a".repeat(64);

    private static final List<LegalManifestIssueCode> BLOCKED_CODES = List.of(
            LegalManifestIssueCode.PUBLICATION_NOT_SEALED,
            LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH,
            LegalManifestIssueCode.EFFECTIVE_DATE_NOT_REACHED,
            LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
            LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH,
            LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS,
            LegalManifestIssueCode.SCOPE_COVERAGE_INCOMPLETE,
            LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
            LegalManifestIssueCode.RETIREMENT_REASON_REQUIRED,
            LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH,
            LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED,
            LegalManifestIssueCode.REVISION_MISMATCH);

    private static final List<LegalManifestIssueCode> ERROR_CODES = List.of(
            LegalManifestIssueCode.CONCURRENT_OPERATION,
            LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
            LegalManifestIssueCode.SCHEMA_DRIFT,
            LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);

    @Test
    void freezesTheClosedResultShapeAndFactories() throws NoSuchMethodException {
        assertThat(Modifier.isFinal(LegalEditorialPlanResult.class.getModifiers())).isTrue();
        assertThat(LegalEditorialPlanResult.class.getConstructors()).isEmpty();
        assertThat(LegalEditorialPlanResult.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(
                        Modifier.isPrivate(constructor.getModifiers())).isTrue());
        assertThat(LegalEditorialPlanResult.Outcome.values())
                .containsExactly(
                        LegalEditorialPlanResult.Outcome.APPLICABLE,
                        LegalEditorialPlanResult.Outcome.BLOCKED,
                        LegalEditorialPlanResult.Outcome.ERROR);

        assertPackagePrivateStaticFactory(
                "applicable",
                LegalEditorialExecutionPlan.class);
        assertPackagePrivateStaticFactory("blocked", Collection.class);
        assertPackagePrivateStaticFactory("error", Collection.class);
        assertThat(Arrays.stream(LegalEditorialPlanResult.DeltaCounts.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "directDocumentTransitions",
                        "triggerDerivedDocumentTransitions",
                        "directRequirementTransitions",
                        "directDocumentSlotDeletes",
                        "directDocumentSlotInserts",
                        "triggerDerivedDocumentSlotDeletes",
                        "triggerDerivedDocumentSlotInserts",
                        "requiredSetPointerDeletes",
                        "requiredSetPointerInserts",
                        "replacementBatches");
        assertThatThrownBy(() -> new LegalEditorialPlanResult.DeltaCounts(
                0, 0, 0, 0, 0, 0, 0, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applicableIsTheOnlyPassCombinationAndCarriesTheCompletePlanEvidence() {
        LegalEditorialExecutionPlan plan = plan(true);

        LegalEditorialPlanResult result = LegalEditorialPlanResult.applicable(plan);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.APPLICABLE);
        assertThat(result.executionPlan()).contains(plan);
        assertThat(result.expectedReadinessAfter()).contains(LegalEditorialReadiness.READY);
        assertThat(result.changeRequired()).contains(true);
        assertThat(result.observedAt()).contains(OBSERVED_AT);
        assertThat(result.targetPublicationUuid()).contains(TARGET_PUBLICATION);
        assertThat(result.deltaCounts()).contains(new LegalEditorialPlanResult.DeltaCounts(
                2, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(result.issues()).isEmpty();
        assertThat(result.omittedIssueCount()).isZero();
    }

    @Test
    void applicableReplayReportsNoChangeAndAnExactlyZeroDelta() {
        LegalEditorialPlanResult result = LegalEditorialPlanResult.applicable(plan(false));

        assertThat(result.changeRequired()).contains(false);
        assertThat(result.deltaCounts()).hasValueSatisfying(counts ->
                assertThat(counts.isZero()).isTrue());
        assertThat(result.executionPlan()).hasValueSatisfying(plan -> {
            assertThat(plan.expectedPostState().documentTransitions()).hasSize(2);
            assertThat(plan.mutationCommands().isEmpty()).isTrue();
        });
    }

    @Test
    void blockedAcceptsExactlyTheTwelvePlanningReasonsAndNeverLeaksPartialEvidence() {
        List<LegalManifestIssue> candidates = new ArrayList<>();
        for (int index = BLOCKED_CODES.size() - 1; index >= 0; index--) {
            candidates.add(issue(BLOCKED_CODES.get(index), "database/blocked/" + index));
        }

        LegalEditorialPlanResult result = LegalEditorialPlanResult.blocked(candidates);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.BLOCKED);
        assertNoEvidence(result);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactlyElementsOf(BLOCKED_CODES.stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .toList());
        assertThat(result.omittedIssueCount()).isZero();
    }

    @Test
    void errorAcceptsExactlyTheFourReadOnlyOperationalReasonsAndNoApplyFailures() {
        List<LegalManifestIssue> failures = ERROR_CODES.stream()
                .map(code -> issue(code, "database/error/" + code.ordinal()))
                .toList();

        LegalEditorialPlanResult result = LegalEditorialPlanResult.error(failures);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(LegalEditorialPlanResult.Outcome.ERROR);
        assertNoEvidence(result);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactlyElementsOf(ERROR_CODES.stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .toList());

        assertThatThrownBy(() -> LegalEditorialPlanResult.error(List.of(issue(
                LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                "database/postcondition"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.error(List.of(issue(
                LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                "database/commit"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonSuccessFactoriesRejectNullEmptyForeignAndWrongSeverityIssues() {
        List<LegalManifestIssue> containingNull = new ArrayList<>();
        containingNull.add(null);

        assertThatThrownBy(() -> LegalEditorialPlanResult.applicable(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.blocked(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.blocked(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.blocked(containingNull))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.blocked(List.of(issue(
                LegalManifestIssueCode.CONCURRENT_OPERATION,
                "database"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.blocked(List.of(issue(
                LegalManifestIssueCode.DB_PERSISTED_CONFLICT,
                "database"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.error(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.error(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.error(containingNull))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.error(List.of(issue(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialPlanResult.error(List.of(issue(
                LegalManifestIssueCode.IMPORT_DB_CONNECTION,
                "database"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuesAreDefensiveDeterministicDeduplicatedAndBounded() {
        List<LegalManifestIssue> candidates = new ArrayList<>();
        for (int index = 249; index >= 0; index--) {
            candidates.add(issue(
                    LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                    "database/state/" + index));
        }
        candidates.add(candidates.getFirst());

        LegalEditorialPlanResult result = LegalEditorialPlanResult.blocked(candidates);
        candidates.clear();

        assertThat(result.issues()).hasSize(200).isSortedAccordingTo(LegalManifestIssue.ORDERING);
        assertThat(result.omittedIssueCount()).isEqualTo(50);
        assertThatThrownBy(() -> result.issues().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void privateConstructorRejectsEveryImpossibleStatusOutcomeEvidenceCombination()
            throws Exception {
        Constructor<LegalEditorialPlanResult> constructor =
                LegalEditorialPlanResult.class.getDeclaredConstructor(
                        LegalManifestStatus.class,
                        LegalEditorialPlanResult.Outcome.class,
                        LegalEditorialExecutionPlan.class,
                        List.class,
                        int.class);
        constructor.setAccessible(true);
        LegalEditorialExecutionPlan plan = plan(true);
        LegalManifestIssue blocker = issue(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state");
        LegalManifestIssue error = issue(
                LegalManifestIssueCode.SCHEMA_DRIFT,
                "database/schema");

        assertInvalidMatrix(
                constructor,
                LegalManifestStatus.PASS,
                LegalEditorialPlanResult.Outcome.APPLICABLE,
                null,
                List.of(),
                0);
        assertInvalidMatrix(
                constructor,
                LegalManifestStatus.PASS,
                LegalEditorialPlanResult.Outcome.APPLICABLE,
                plan,
                List.of(blocker),
                0);
        assertInvalidMatrix(
                constructor,
                LegalManifestStatus.BLOCKED,
                LegalEditorialPlanResult.Outcome.BLOCKED,
                plan,
                List.of(blocker),
                0);
        assertInvalidMatrix(
                constructor,
                LegalManifestStatus.BLOCKED,
                LegalEditorialPlanResult.Outcome.BLOCKED,
                null,
                List.of(),
                0);
        assertInvalidMatrix(
                constructor,
                LegalManifestStatus.ERROR,
                LegalEditorialPlanResult.Outcome.ERROR,
                plan,
                List.of(error),
                0);
        assertInvalidMatrix(
                constructor,
                LegalManifestStatus.ERROR,
                LegalEditorialPlanResult.Outcome.ERROR,
                null,
                List.of(blocker),
                0);
        assertInvalidMatrix(
                constructor,
                LegalManifestStatus.ERROR,
                LegalEditorialPlanResult.Outcome.ERROR,
                null,
                List.of(error),
                -1);
    }

    private static LegalEditorialExecutionPlan plan(boolean changeRequired) {
        LegalEditorialExecutionPlan.DocumentTransition publish =
                new LegalEditorialExecutionPlan.DocumentTransition(
                        DOCUMENT_VERSION,
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA,
                        null,
                        null,
                        OBSERVED_AT);
        LegalEditorialExecutionPlan.DocumentTransition activate =
                new LegalEditorialExecutionPlan.DocumentTransition(
                        DOCUMENT_VERSION,
                        EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE,
                        null,
                        null,
                        OBSERVED_AT);
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        List.of(new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                DOCUMENT_VERSION,
                                EstadoVersionLegal.VIGENTE,
                                OBSERVED_AT,
                                null,
                                null)),
                        List.of(),
                        List.of(publish, activate),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        LegalEditorialExecutionPlan.V27TriggerEffects.empty());
        return new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.PROMOTE,
                Optional.empty(),
                new LegalEditorialExecutionPlan.PublicationIdentity(
                        "release-target",
                        TARGET_PUBLICATION,
                        SHA),
                Optional.empty(),
                Optional.empty(),
                OBSERVED_AT,
                OBSERVED_AT,
                LegalEditorialReadiness.READY,
                false,
                changeRequired,
                postState,
                changeRequired
                        ? new LegalEditorialExecutionPlan.MutationCommands(
                                List.of(publish, activate),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of())
                        : LegalEditorialExecutionPlan.MutationCommands.empty());
    }

    private static void assertNoEvidence(LegalEditorialPlanResult result) {
        assertThat(result.executionPlan()).isEmpty();
        assertThat(result.expectedReadinessAfter()).isEmpty();
        assertThat(result.changeRequired()).isEmpty();
        assertThat(result.observedAt()).isEmpty();
        assertThat(result.targetPublicationUuid()).isEmpty();
        assertThat(result.deltaCounts()).isEmpty();
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }

    private static void assertPackagePrivateStaticFactory(
            String name,
            Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = LegalEditorialPlanResult.class.getDeclaredMethod(name, parameterTypes);
        assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
        assertThat(Modifier.isProtected(method.getModifiers())).isFalse();
        assertThat(Modifier.isPrivate(method.getModifiers())).isFalse();
    }

    private static void assertInvalidMatrix(
            Constructor<LegalEditorialPlanResult> constructor,
            LegalManifestStatus status,
            LegalEditorialPlanResult.Outcome outcome,
            LegalEditorialExecutionPlan plan,
            List<LegalManifestIssue> issues,
            int omittedIssueCount) {
        assertThatThrownBy(() -> constructor.newInstance(
                status,
                outcome,
                plan,
                issues,
                omittedIssueCount))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
