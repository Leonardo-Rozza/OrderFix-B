package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialArguments.Command;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyReceipt;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessObservation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalEditorialReportTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final UUID PUBLICATION_UUID =
            UUID.fromString("60870649-efbb-4d2b-8407-4f1901b86a45");
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-28T18:00:00.123456Z");
    private static final String FINGERPRINT =
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final UUID OPERATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String PLAN_SHA256 =
            "534ef5a63292484c4cde6fc2fa6735f7d42dbc40a3df671a6f9550626aebe49c";

    private static ValidatedRelease release;

    @BeforeAll
    static void validateRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalEditorialReportTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        release = validation.value().orElseThrow();
    }

    @Test
    void readinessPreservesTheClosedReadyNotReadyAndErrorRows() {
        LegalEditorialReport ready = LegalEditorialReport.forReadiness(
                release,
                readinessResult(
                        LegalManifestStatus.PASS,
                        LegalEditorialReadiness.READY,
                        observation(),
                        List.of()));
        LegalEditorialReport notReady = LegalEditorialReport.forReadiness(
                release,
                readinessResult(
                        LegalManifestStatus.BLOCKED,
                        LegalEditorialReadiness.NOT_READY,
                        observation(),
                        List.of(issue(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));
        LegalEditorialReport error = LegalEditorialReport.forReadiness(
                release,
                readinessResult(
                        LegalManifestStatus.ERROR,
                        LegalEditorialReadiness.ERROR,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
                                "database/privileges"))));

        assertThat(ready.command()).isEqualTo("readiness");
        assertThat(ready.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(ready.persisted()).isFalse();
        assertThat(ready.readiness().value()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(ready.publication().publicationUuid()).isEqualTo(PUBLICATION_UUID);
        assertThat(ready.counts().state()).isEqualTo(stateCounts());
        assertThat(notReady.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(notReady.readiness().value())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(error.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(error.readiness().value()).isEqualTo(LegalEditorialReadiness.ERROR);
        assertThat(error.readiness().observedAt()).isNull();
        assertThat(error.counts().state()).isNull();
    }

    @Test
    void planPromoteExposesOnlyApplicablePlanMetadataAndDelta() {
        LegalEditorialReport applicable = LegalEditorialReport.forPlanPromote(
                release,
                planResult(
                        LegalManifestStatus.PASS,
                        LegalEditorialPlanResult.Outcome.APPLICABLE,
                        List.of()));
        LegalEditorialReport blocked = LegalEditorialReport.forPlanPromote(
                release,
                planResult(
                        LegalManifestStatus.BLOCKED,
                        LegalEditorialPlanResult.Outcome.BLOCKED,
                        List.of(issue(
                                LegalManifestIssueCode.EFFECTIVE_DATE_NOT_REACHED,
                                "database/effective-date"))));

        assertThat(applicable.command()).isEqualTo("plan-promote");
        assertThat(applicable.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(applicable.persisted()).isFalse();
        assertThat(applicable.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.APPLICABLE);
        assertThat(applicable.plan().operationId()).isNull();
        assertThat(applicable.plan().editorialPlanSha256()).isNull();
        assertThat(applicable.plan().changeRequired()).isTrue();
        assertThat(applicable.plan().observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(applicable.readiness().value())
                .isEqualTo(LegalEditorialReadiness.READY);
        assertThat(applicable.counts().state()).isNull();
        assertThat(applicable.counts().delta()).isEqualTo(deltaCounts());
        assertThat(blocked.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(blocked.plan()).isNull();
        assertThat(blocked.readiness()).isNull();
        assertThat(blocked.counts().delta()).isNull();
    }

    @Test
    void planReplaceRetainsConfirmedIdentityButOnlyApplicableHasObservationMetadata() {
        ValidatedEditorialPlan editorialPlan = validatedReplacePlan();
        LegalEditorialReport applicable = LegalEditorialReport.forPlanReplace(
                release,
                editorialPlan,
                planResult(
                        LegalManifestStatus.PASS,
                        LegalEditorialPlanResult.Outcome.APPLICABLE,
                        List.of()));
        LegalEditorialReport blocked = LegalEditorialReport.forPlanReplace(
                release,
                editorialPlan,
                planResult(
                        LegalManifestStatus.BLOCKED,
                        LegalEditorialPlanResult.Outcome.BLOCKED,
                        List.of(issue(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));

        assertThat(applicable.command()).isEqualTo("plan-replace");
        assertThat(applicable.persisted()).isFalse();
        assertThat(applicable.operation().operationType())
                .isEqualTo(LegalEditorialReport.OperationType.REPLACE);
        assertThat(applicable.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.APPLICABLE);
        assertThat(applicable.plan().operationId()).isEqualTo(OPERATION_ID);
        assertThat(applicable.plan().editorialPlanSha256()).isEqualTo(PLAN_SHA256);
        assertThat(applicable.plan().changeRequired()).isTrue();
        assertThat(applicable.plan().observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(applicable.publication().publicationUuid()).isEqualTo(PUBLICATION_UUID);
        assertThat(applicable.readiness().value()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(applicable.counts().delta()).isEqualTo(deltaCounts());

        assertThat(blocked.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(blocked.plan().operationId()).isEqualTo(OPERATION_ID);
        assertThat(blocked.plan().editorialPlanSha256()).isEqualTo(PLAN_SHA256);
        assertThat(blocked.plan().changeRequired()).isNull();
        assertThat(blocked.plan().observedAt()).isNull();
        assertThat(blocked.readiness()).isNull();
        assertThat(blocked.publication().publicationUuid()).isNull();
        assertThat(blocked.counts().state()).isNull();
        assertThat(blocked.counts().delta()).isNull();
    }

    @Test
    void applyPromoteKeepsSuccessRollbackAndUnknownPersistenceDistinct() {
        LegalEditorialReport applied = LegalEditorialReport.forApplyPromote(
                release,
                applyResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        LegalEditorialApplyResult.Outcome.APPLIED,
                        receipt(),
                        List.of()));
        LegalEditorialReport rolledBack = LegalEditorialReport.forApplyPromote(
                release,
                applyResult(
                        LegalManifestStatus.ERROR,
                        Boolean.FALSE,
                        LegalEditorialApplyResult.Outcome.ERROR,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                                "database/postcondition"))));
        LegalEditorialReport unknown = LegalEditorialReport.forApplyPromote(
                release,
                applyResult(
                        LegalManifestStatus.ERROR,
                        null,
                        LegalEditorialApplyResult.Outcome.UNKNOWN,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                                "database/commit"))));

        assertThat(applied.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(applied.persisted()).isTrue();
        assertThat(applied.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.APPLIED);
        assertThat(applied.operation().appliedAt()).isEqualTo(OBSERVED_AT);
        assertThat(applied.publication().publicationUuid()).isEqualTo(PUBLICATION_UUID);
        assertThat(applied.counts().state()).isEqualTo(stateCounts());
        assertThat(rolledBack.persisted()).isFalse();
        assertThat(rolledBack.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.ERROR);
        assertNoDatabaseMetadata(rolledBack);
        assertThat(unknown.persisted()).isNull();
        assertThat(unknown.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.UNKNOWN);
        assertNoDatabaseMetadata(unknown);
    }

    @Test
    void conservativeUnknownBoundaryNeverInventsDatabaseMetadata() {
        LegalEditorialReport withRelease = LegalEditorialReport.forUnknownApply(release);
        LegalEditorialReport beforeRelease = LegalEditorialReport.forUnknownApply(null);

        assertThat(withRelease.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(withRelease.persisted()).isNull();
        assertThat(withRelease.publication()).isNotNull();
        assertThat(withRelease.counts().release())
                .isEqualTo(new LegalEditorialReport.ReleaseCounts(11, 6, 8));
        assertNoDatabaseMetadata(withRelease);
        assertThat(beforeRelease.publication()).isNull();
        assertThat(beforeRelease.counts()).isNull();
        assertNoDatabaseMetadata(beforeRelease);
    }

    @Test
    void knownPreflightFailuresRemainTypedForAllFourCommands() {
        LegalManifestIssue blockedIssue = issue(
                LegalManifestIssueCode.CLI_ARGUMENTS_INVALID,
                "cli/editorial/arguments");
        LegalEditorialReport readiness = LegalEditorialReport.forKnownFailure(
                Command.READINESS, null, blockedIssue);
        LegalEditorialReport plan = LegalEditorialReport.forKnownFailure(
                Command.PLAN_PROMOTE, null, blockedIssue);
        LegalEditorialReport apply = LegalEditorialReport.forKnownFailure(
                Command.APPLY_PROMOTE, null, blockedIssue);
        LegalEditorialReport replaceBeforeConfirmation =
                LegalEditorialReport.forKnownFailure(
                        Command.PLAN_REPLACE,
                        null,
                        blockedIssue);

        assertThat(readiness.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(readiness.persisted()).isFalse();
        assertThat(readiness.operation()).isNull();
        assertThat(plan.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.BLOCKED);
        assertThat(apply.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.BLOCKED);
        assertThat(replaceBeforeConfirmation.operation().operationType())
                .isEqualTo(LegalEditorialReport.OperationType.REPLACE);
        assertThat(replaceBeforeConfirmation.plan()).isNull();
        for (LegalEditorialReport failure : List.of(
                readiness,
                plan,
                apply,
                replaceBeforeConfirmation)) {
            assertThat(failure.publication()).isNull();
            assertThat(failure.counts()).isNull();
        }

        ValidatedEditorialPlan editorialPlan = validatedReplacePlan();
        LegalEditorialReport replaceAfterConfirmation =
                LegalEditorialReport.forKnownFailure(
                        Command.PLAN_REPLACE,
                        release,
                        editorialPlan,
                        issue(
                                LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                                "documentReplacementBatches"));
        assertThat(replaceAfterConfirmation.plan().operationId()).isEqualTo(OPERATION_ID);
        assertThat(replaceAfterConfirmation.plan().editorialPlanSha256())
                .isEqualTo(PLAN_SHA256);
        assertThat(replaceAfterConfirmation.plan().changeRequired()).isNull();
        assertThat(replaceAfterConfirmation.plan().observedAt()).isNull();
        assertNoDatabaseMetadata(replaceAfterConfirmation);

        LegalEditorialReport operational = LegalEditorialReport.forKnownFailure(
                Command.APPLY_PROMOTE,
                release,
                issue(
                        LegalManifestIssueCode.EDITORIAL_DB_CONFIGURATION_INVALID,
                        "cli/editorial/environment"));
        assertThat(operational.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(operational.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.ERROR);
        assertThat(operational.publication()).isNotNull();
        assertThat(operational.publication().publicationUuid()).isNull();

        assertThatThrownBy(() -> LegalEditorialReport.forKnownFailure(
                Command.APPLY_PROMOTE,
                release,
                issue(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN, "database/commit")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedServiceRowsAreRejectedInsteadOfBeingReinterpreted() {
        assertThatThrownBy(() -> LegalEditorialReport.forApplyPromote(
                release,
                applyResult(
                        LegalManifestStatus.ERROR,
                        Boolean.FALSE,
                        LegalEditorialApplyResult.Outcome.UNKNOWN,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                                "database/commit")))))
                .isInstanceOf(IllegalArgumentException.class);

        LegalEditorialApplyReceipt replaceReceipt = new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.REPLACE,
                PUBLICATION_UUID,
                OBSERVED_AT,
                LegalEditorialReadiness.READY,
                11, 6, 34, 12, 11, 8, 0);
        assertThatThrownBy(() -> LegalEditorialReport.forApplyPromote(
                release,
                applyResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        LegalEditorialApplyResult.Outcome.APPLIED,
                        replaceReceipt,
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertNoDatabaseMetadata(LegalEditorialReport report) {
        assertThat(report.operation().appliedAt()).isNull();
        assertThat(report.readiness()).isNull();
        if (report.publication() != null) {
            assertThat(report.publication().publicationUuid()).isNull();
        }
        if (report.counts() != null) {
            assertThat(report.counts().state()).isNull();
            assertThat(report.counts().delta()).isNull();
        }
    }

    private static LegalEditorialReadinessResult readinessResult(
            LegalManifestStatus status,
            LegalEditorialReadiness readiness,
            LegalEditorialReadinessObservation observation,
            List<LegalManifestIssue> issues) {
        LegalEditorialReadinessResult result = mock(LegalEditorialReadinessResult.class);
        when(result.status()).thenReturn(status);
        when(result.readiness()).thenReturn(readiness);
        when(result.observation()).thenReturn(Optional.ofNullable(observation));
        when(result.issues()).thenReturn(issues);
        when(result.omittedIssueCount()).thenReturn(0);
        return result;
    }

    private static LegalEditorialPlanResult planResult(
            LegalManifestStatus status,
            LegalEditorialPlanResult.Outcome outcome,
            List<LegalManifestIssue> issues) {
        LegalEditorialPlanResult result = mock(LegalEditorialPlanResult.class);
        when(result.status()).thenReturn(status);
        when(result.outcome()).thenReturn(outcome);
        when(result.issues()).thenReturn(issues);
        when(result.omittedIssueCount()).thenReturn(0);
        if (outcome == LegalEditorialPlanResult.Outcome.APPLICABLE) {
            when(result.targetPublicationUuid()).thenReturn(Optional.of(PUBLICATION_UUID));
            when(result.changeRequired()).thenReturn(Optional.of(true));
            when(result.observedAt()).thenReturn(Optional.of(OBSERVED_AT));
            when(result.expectedReadinessAfter())
                    .thenReturn(Optional.of(LegalEditorialReadiness.READY));
            when(result.deltaCounts()).thenReturn(Optional.of(new LegalEditorialPlanResult.DeltaCounts(
                    22, 0, 12, 0, 11, 0, 0, 0, 8, 0)));
        } else {
            when(result.targetPublicationUuid()).thenReturn(Optional.empty());
            when(result.changeRequired()).thenReturn(Optional.empty());
            when(result.observedAt()).thenReturn(Optional.empty());
            when(result.expectedReadinessAfter()).thenReturn(Optional.empty());
            when(result.deltaCounts()).thenReturn(Optional.empty());
        }
        return result;
    }

    private static LegalEditorialApplyResult applyResult(
            LegalManifestStatus status,
            Boolean persisted,
            LegalEditorialApplyResult.Outcome outcome,
            LegalEditorialApplyReceipt receipt,
            List<LegalManifestIssue> issues) {
        LegalEditorialApplyResult result = mock(LegalEditorialApplyResult.class);
        when(result.status()).thenReturn(status);
        when(result.persisted()).thenReturn(persisted);
        when(result.outcome()).thenReturn(outcome);
        when(result.receipt()).thenReturn(Optional.ofNullable(receipt));
        when(result.issues()).thenReturn(issues);
        when(result.omittedIssueCount()).thenReturn(0);
        return result;
    }

    private static ValidatedEditorialPlan validatedReplacePlan() {
        LegalEditorialPlanV1 model = mock(LegalEditorialPlanV1.class);
        when(model.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        ValidatedEditorialPlan plan = mock(ValidatedEditorialPlan.class);
        when(plan.plan()).thenReturn(model);
        when(plan.operationType()).thenReturn(OperationType.REPLACE);
        when(plan.operationId()).thenReturn(OPERATION_ID);
        when(plan.editorialPlanSha256()).thenReturn(PLAN_SHA256);
        return plan;
    }

    private static LegalEditorialReadinessObservation observation() {
        return new LegalEditorialReadinessObservation(
                Optional.of(PUBLICATION_UUID),
                OBSERVED_AT,
                FINGERPRINT,
                11, 6, 34, 12, 11, 8, 0);
    }

    private static LegalEditorialApplyReceipt receipt() {
        return new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                PUBLICATION_UUID,
                OBSERVED_AT,
                LegalEditorialReadiness.READY,
                11, 6, 34, 12, 11, 8, 0);
    }

    private static LegalEditorialReport.StateCounts stateCounts() {
        return new LegalEditorialReport.StateCounts(11, 6, 34, 12, 11, 8, 0);
    }

    private static LegalEditorialReport.DeltaCounts deltaCounts() {
        return new LegalEditorialReport.DeltaCounts(22, 0, 12, 0, 11, 0, 0, 0, 8, 0);
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }
}
