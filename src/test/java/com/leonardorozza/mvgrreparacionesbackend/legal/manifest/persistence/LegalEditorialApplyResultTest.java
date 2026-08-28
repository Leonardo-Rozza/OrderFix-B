package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialApplyResultTest {

    private static final Set<LegalManifestIssueCode> BLOCKED_CODES = EnumSet.of(
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
    private static final Set<LegalManifestIssueCode> ERROR_CODES = EnumSet.of(
            LegalManifestIssueCode.CONCURRENT_OPERATION,
            LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
            LegalManifestIssueCode.SCHEMA_DRIFT,
            LegalManifestIssueCode.POSTCONDITION_NOT_READY,
            LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);

    @Test
    void freezesTheClosedFactoriesAndOutcomeVocabulary() throws Exception {
        assertThat(Modifier.isFinal(LegalEditorialApplyResult.class.getModifiers())).isTrue();
        assertThat(LegalEditorialApplyResult.class.getConstructors()).isEmpty();
        assertThat(LegalEditorialApplyResult.Outcome.values()).containsExactly(
                LegalEditorialApplyResult.Outcome.APPLIED,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED,
                LegalEditorialApplyResult.Outcome.BLOCKED,
                LegalEditorialApplyResult.Outcome.ERROR,
                LegalEditorialApplyResult.Outcome.UNKNOWN);

        assertPackagePrivateStaticFactory("applied", LegalEditorialApplyReceipt.class);
        assertPackagePrivateStaticFactory("alreadyApplied", LegalEditorialApplyReceipt.class);
        assertPackagePrivateStaticFactory("blocked", Collection.class);
        assertPackagePrivateStaticFactory("error", Collection.class);
        assertPackagePrivateStaticFactory("unknown");
    }

    @Test
    void appliedAndAlreadyAppliedAreTheOnlyPersistedSuccesses() {
        LegalEditorialApplyReceipt receipt = receipt();

        for (LegalEditorialApplyResult result : List.of(
                LegalEditorialApplyResult.applied(receipt),
                LegalEditorialApplyResult.alreadyApplied(receipt))) {
            assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
            assertThat(result.persisted()).isTrue();
            assertThat(result.receipt()).contains(receipt);
            assertThat(result.operationType())
                    .contains(LegalEditorialApplyReceipt.OperationType.PROMOTE);
            assertThat(result.targetPublicationUuid()).contains(receipt.targetPublicationUuid());
            assertThat(result.appliedAt()).contains(receipt.appliedAt());
            assertThat(result.readinessAfter()).contains(LegalEditorialReadiness.READY);
            assertThat(result.issues()).isEmpty();
            assertThat(result.omittedIssueCount()).isZero();
        }
        assertThat(LegalEditorialApplyResult.applied(receipt).outcome())
                .isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
        assertThat(LegalEditorialApplyResult.alreadyApplied(receipt).outcome())
                .isEqualTo(LegalEditorialApplyResult.Outcome.ALREADY_APPLIED);
    }

    @Test
    void blockedAcceptsOnlyTheClosedDomainAllowlistAndNoDatabaseEvidence() {
        List<LegalManifestIssue> issues = BLOCKED_CODES.stream()
                .map(code -> issue(code, "database/blocked/" + code.ordinal()))
                .toList();

        LegalEditorialApplyResult result = LegalEditorialApplyResult.blocked(issues);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.BLOCKED);
        assertNoDatabaseEvidence(result);
        assertThat(result.issues()).extracting(LegalManifestIssue::code)
                .containsExactlyInAnyOrderElementsOf(BLOCKED_CODES);
    }

    @Test
    void knownRollbackAcceptsOnlyOperationalApplyErrorsAndNoDatabaseEvidence() {
        List<LegalManifestIssue> issues = ERROR_CODES.stream()
                .map(code -> issue(code, "database/error/" + code.ordinal()))
                .toList();

        LegalEditorialApplyResult result = LegalEditorialApplyResult.error(issues);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.ERROR);
        assertNoDatabaseEvidence(result);
        assertThat(result.issues()).extracting(LegalManifestIssue::code)
                .containsExactlyInAnyOrderElementsOf(ERROR_CODES);
        assertThatThrownBy(() -> LegalEditorialApplyResult.error(List.of(issue(
                LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                "database/commit"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownIsTheOnlyIndeterminateResultAndNeverLeaksTentativeMetadata() {
        LegalEditorialApplyResult result = LegalEditorialApplyResult.unknown();

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isNull();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.UNKNOWN);
        assertNoDatabaseEvidence(result);
        assertThat(result.issues()).extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN);
        assertThat(result.omittedIssueCount()).isZero();
    }

    @Test
    void failureFactoriesRejectNullEmptyForeignAndWrongSeverityIssues() {
        List<LegalManifestIssue> containingNull = new ArrayList<>();
        containingNull.add(null);

        assertThatThrownBy(() -> LegalEditorialApplyResult.applied(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialApplyResult.blocked(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialApplyResult.blocked(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialApplyResult.blocked(containingNull))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialApplyResult.blocked(List.of(issue(
                LegalManifestIssueCode.CONCURRENT_OPERATION, "database"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialApplyResult.error(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialApplyResult.error(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialApplyResult.error(containingNull))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialApplyResult.error(List.of(issue(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH, "database"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialApplyResult.error(List.of(issue(
                LegalManifestIssueCode.IMPORT_DB_CONNECTION, "database"))))
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

        LegalEditorialApplyResult result = LegalEditorialApplyResult.blocked(candidates);
        candidates.clear();

        assertThat(result.issues()).hasSize(200).isSortedAccordingTo(LegalManifestIssue.ORDERING);
        assertThat(result.omittedIssueCount()).isEqualTo(50);
        assertThatThrownBy(() -> result.issues().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void privateConstructorRejectsImpossibleMatrixCombinations() throws Exception {
        Constructor<LegalEditorialApplyResult> constructor =
                LegalEditorialApplyResult.class.getDeclaredConstructor(
                        LegalManifestStatus.class,
                        Boolean.class,
                        LegalEditorialApplyResult.Outcome.class,
                        LegalEditorialApplyReceipt.class,
                        List.class,
                        int.class);
        constructor.setAccessible(true);
        LegalEditorialApplyReceipt receipt = receipt();
        LegalManifestIssue blocker = issue(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH, "database/state");
        LegalManifestIssue error = issue(
                LegalManifestIssueCode.SCHEMA_DRIFT, "database/schema");

        assertInvalid(constructor, LegalManifestStatus.PASS, true,
                LegalEditorialApplyResult.Outcome.APPLIED, null, List.of(), 0);
        assertInvalid(constructor, LegalManifestStatus.PASS, false,
                LegalEditorialApplyResult.Outcome.APPLIED, receipt, List.of(), 0);
        assertInvalid(constructor, LegalManifestStatus.BLOCKED, false,
                LegalEditorialApplyResult.Outcome.BLOCKED, receipt, List.of(blocker), 0);
        assertInvalid(constructor, LegalManifestStatus.ERROR, false,
                LegalEditorialApplyResult.Outcome.ERROR, null, List.of(blocker), 0);
        assertInvalid(constructor, LegalManifestStatus.ERROR, null,
                LegalEditorialApplyResult.Outcome.UNKNOWN, null, List.of(error), 0);
        assertInvalid(constructor, LegalManifestStatus.ERROR, null,
                LegalEditorialApplyResult.Outcome.UNKNOWN, null,
                List.of(issue(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN, "database/commit")), 1);
    }

    private static void assertNoDatabaseEvidence(LegalEditorialApplyResult result) {
        assertThat(result.receipt()).isEmpty();
        assertThat(result.operationType()).isEmpty();
        assertThat(result.targetPublicationUuid()).isEmpty();
        assertThat(result.appliedAt()).isEmpty();
        assertThat(result.readinessAfter()).isEmpty();
    }

    private static void assertPackagePrivateStaticFactory(
            String name,
            Class<?>... parameterTypes) throws Exception {
        var method = LegalEditorialApplyResult.class.getDeclaredMethod(name, parameterTypes);
        assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
        assertThat(Modifier.isPrivate(method.getModifiers())).isFalse();
        assertThat(method.getReturnType()).isEqualTo(LegalEditorialApplyResult.class);
    }

    private static void assertInvalid(
            Constructor<LegalEditorialApplyResult> constructor,
            LegalManifestStatus status,
            Boolean persisted,
            LegalEditorialApplyResult.Outcome outcome,
            LegalEditorialApplyReceipt receipt,
            List<LegalManifestIssue> issues,
            int omitted) {
        assertThatThrownBy(() -> constructor.newInstance(
                status, persisted, outcome, receipt, issues, omitted))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private static LegalEditorialApplyReceipt receipt() {
        return new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                new UUID(0, 1),
                Instant.parse("2026-08-28T20:15:30.123456Z"),
                LegalEditorialReadiness.READY,
                2, 3, 4, 6, 2, 1, 0);
    }

    private static LegalManifestIssue issue(LegalManifestIssueCode code, String location) {
        return LegalManifestIssue.at(code, location);
    }
}
