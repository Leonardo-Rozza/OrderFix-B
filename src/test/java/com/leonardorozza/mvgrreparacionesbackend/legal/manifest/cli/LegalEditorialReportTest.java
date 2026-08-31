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
    void planRetireKeepsNotReadyDistinctFromABlockedPlanningOutcome() {
        ValidatedEditorialPlan editorialPlan = validatedRetirePlan();
        LegalEditorialReport applicable = LegalEditorialReport.forPlanRetire(
                release,
                editorialPlan,
                planResult(
                        LegalManifestStatus.PASS,
                        LegalEditorialPlanResult.Outcome.APPLICABLE,
                        LegalEditorialReadiness.NOT_READY,
                        List.of()));
        LegalEditorialReport blocked = LegalEditorialReport.forPlanRetire(
                release,
                editorialPlan,
                planResult(
                        LegalManifestStatus.BLOCKED,
                        LegalEditorialPlanResult.Outcome.BLOCKED,
                        LegalEditorialReadiness.NOT_READY,
                        List.of(issue(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));

        assertThat(applicable.command()).isEqualTo("plan-retire");
        assertThat(applicable.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(applicable.persisted()).isFalse();
        assertThat(applicable.operation().operationType())
                .isEqualTo(LegalEditorialReport.OperationType.RETIRE);
        assertThat(applicable.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.APPLICABLE);
        assertThat(applicable.plan().operationId()).isEqualTo(OPERATION_ID);
        assertThat(applicable.plan().editorialPlanSha256()).isEqualTo(PLAN_SHA256);
        assertThat(applicable.plan().changeRequired()).isTrue();
        assertThat(applicable.plan().observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(applicable.plan().expectedReadinessAfter())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(applicable.publication().publicationUuid()).isEqualTo(PUBLICATION_UUID);
        assertThat(applicable.readiness().value())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(applicable.readiness().observedAt()).isNull();
        assertThat(applicable.counts().state()).isNull();
        assertThat(applicable.counts().delta()).isEqualTo(deltaCounts());

        assertThat(blocked.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(blocked.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.BLOCKED);
        assertThat(blocked.plan().operationId()).isEqualTo(OPERATION_ID);
        assertThat(blocked.plan().editorialPlanSha256()).isEqualTo(PLAN_SHA256);
        assertThat(blocked.plan().expectedReadinessAfter())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertNoDatabaseMetadata(blocked);
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
    void applyReplaceClosesAppliedReplayBlockedErrorAndUnknownWithPlanIdentity() {
        ValidatedEditorialPlan editorialPlan = validatedReplacePlan();
        LegalEditorialReport applied = LegalEditorialReport.forApplyReplace(
                release,
                editorialPlan,
                applyResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        LegalEditorialApplyResult.Outcome.APPLIED,
                        replaceReceipt(),
                        List.of()));
        LegalEditorialReport replay = LegalEditorialReport.forApplyReplace(
                release,
                editorialPlan,
                applyResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        LegalEditorialApplyResult.Outcome.ALREADY_APPLIED,
                        replaceReceipt(),
                        List.of()));
        LegalEditorialReport blocked = LegalEditorialReport.forApplyReplace(
                release,
                editorialPlan,
                applyResult(
                        LegalManifestStatus.BLOCKED,
                        Boolean.FALSE,
                        LegalEditorialApplyResult.Outcome.BLOCKED,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));
        LegalEditorialReport error = LegalEditorialReport.forApplyReplace(
                release,
                editorialPlan,
                applyResult(
                        LegalManifestStatus.ERROR,
                        Boolean.FALSE,
                        LegalEditorialApplyResult.Outcome.ERROR,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                                "database/postcondition"))));
        LegalEditorialReport unknown = LegalEditorialReport.forApplyReplace(
                release,
                editorialPlan,
                applyResult(
                        LegalManifestStatus.ERROR,
                        null,
                        LegalEditorialApplyResult.Outcome.UNKNOWN,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                                "database/commit"))));

        assertThat(applied.command()).isEqualTo("apply-replace");
        assertThat(applied.operation().operationType())
                .isEqualTo(LegalEditorialReport.OperationType.REPLACE);
        assertThat(applied.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.APPLIED);
        assertThat(applied.persisted()).isTrue();
        assertThat(applied.publication().publicationUuid()).isEqualTo(PUBLICATION_UUID);
        assertThat(applied.operation().appliedAt()).isEqualTo(OBSERVED_AT);
        assertThat(applied.readiness().value()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(applied.counts().state())
                .isEqualTo(new LegalEditorialReport.StateCounts(11, 6, 34, 12, 11, 8, 1));
        assertThat(replay.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.ALREADY_APPLIED);
        assertThat(replay.persisted()).isTrue();
        for (LegalEditorialReport report : List.of(
                applied,
                replay,
                blocked,
                error,
                unknown)) {
            assertThat(report.plan().operationId()).isEqualTo(OPERATION_ID);
            assertThat(report.plan().editorialPlanSha256()).isEqualTo(PLAN_SHA256);
            assertThat(report.plan().changeRequired()).isNull();
            assertThat(report.plan().observedAt()).isNull();
            assertThat(report.counts().delta()).isNull();
        }
        assertThat(blocked.persisted()).isFalse();
        assertThat(error.persisted()).isFalse();
        assertThat(unknown.persisted()).isNull();
        assertNoDatabaseMetadata(blocked);
        assertNoDatabaseMetadata(error);
        assertNoDatabaseMetadata(unknown);
    }

    @Test
    void alreadyAppliedKeepsDatabaseEvidenceIndependentFromCallerPlanIdentity() {
        UUID otherOperationId =
                UUID.fromString("00000000-0000-0000-0000-000000000011");
        String otherPlanSha256 =
                "875a60b975386133f8f06ecf4b48c27a12e831724c49cbc9861ce541562f0c0f";
        LegalEditorialApplyReceipt databaseReceipt = replaceReceipt();
        LegalEditorialApplyResult replay = applyResult(
                LegalManifestStatus.PASS,
                Boolean.TRUE,
                LegalEditorialApplyResult.Outcome.ALREADY_APPLIED,
                databaseReceipt,
                List.of());

        LegalEditorialReport first = LegalEditorialReport.forApplyReplace(
                release,
                validatedReplacePlan(OPERATION_ID, PLAN_SHA256),
                replay);
        LegalEditorialReport other = LegalEditorialReport.forApplyReplace(
                release,
                validatedReplacePlan(otherOperationId, otherPlanSha256),
                replay);

        for (LegalEditorialReport report : List.of(first, other)) {
            assertThat(report.status()).isEqualTo(LegalManifestStatus.PASS);
            assertThat(report.persisted()).isTrue();
            assertThat(report.operation().outcome())
                    .isEqualTo(LegalEditorialReport.Outcome.ALREADY_APPLIED);
            assertThat(report.publication().publicationUuid()).isEqualTo(PUBLICATION_UUID);
            assertThat(report.operation().appliedAt()).isEqualTo(OBSERVED_AT);
            assertThat(report.readiness().value()).isEqualTo(LegalEditorialReadiness.READY);
            assertThat(report.counts().state())
                    .isEqualTo(new LegalEditorialReport.StateCounts(
                            11, 6, 34, 12, 11, 8, 1));
            assertThat(report.counts().delta()).isNull();
        }
        assertThat(first.operation()).isEqualTo(other.operation());
        assertThat(first.publication()).isEqualTo(other.publication());
        assertThat(first.readiness()).isEqualTo(other.readiness());
        assertThat(first.counts()).isEqualTo(other.counts());
        assertThat(first.plan().operationId()).isEqualTo(OPERATION_ID);
        assertThat(first.plan().editorialPlanSha256()).isEqualTo(PLAN_SHA256);
        assertThat(other.plan().operationId()).isEqualTo(otherOperationId);
        assertThat(other.plan().editorialPlanSha256()).isEqualTo(otherPlanSha256);
    }

    @Test
    void applyRetireClosesSuccessReplayAndAllFailureRowsWithNotReadyIdentity() {
        ValidatedEditorialPlan editorialPlan = validatedRetirePlan();
        LegalEditorialReport applied = LegalEditorialReport.forApplyRetire(
                release,
                editorialPlan,
                applyResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        LegalEditorialApplyResult.Outcome.APPLIED,
                        retireReceipt(),
                        List.of()));
        LegalEditorialReport replay = LegalEditorialReport.forApplyRetire(
                release,
                editorialPlan,
                applyResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        LegalEditorialApplyResult.Outcome.ALREADY_APPLIED,
                        retireReceipt(),
                        List.of()));
        LegalEditorialReport blocked = LegalEditorialReport.forApplyRetire(
                release,
                editorialPlan,
                applyResult(
                        LegalManifestStatus.BLOCKED,
                        Boolean.FALSE,
                        LegalEditorialApplyResult.Outcome.BLOCKED,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                                "database/state"))));
        LegalEditorialReport error = LegalEditorialReport.forApplyRetire(
                release,
                editorialPlan,
                applyResult(
                        LegalManifestStatus.ERROR,
                        Boolean.FALSE,
                        LegalEditorialApplyResult.Outcome.ERROR,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                                "database/postcondition"))));
        LegalEditorialReport unknown = LegalEditorialReport.forApplyRetire(
                release,
                editorialPlan,
                applyResult(
                        LegalManifestStatus.ERROR,
                        null,
                        LegalEditorialApplyResult.Outcome.UNKNOWN,
                        null,
                        List.of(issue(
                                LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                                "database/commit"))));

        assertThat(applied.command()).isEqualTo("apply-retire");
        assertThat(applied.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(applied.persisted()).isTrue();
        assertThat(applied.operation().operationType())
                .isEqualTo(LegalEditorialReport.OperationType.RETIRE);
        assertThat(applied.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.APPLIED);
        assertThat(applied.operation().appliedAt()).isEqualTo(OBSERVED_AT);
        assertThat(applied.publication().publicationUuid()).isEqualTo(PUBLICATION_UUID);
        assertThat(applied.readiness().value())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(applied.counts().state())
                .isEqualTo(new LegalEditorialReport.StateCounts(11, 6, 35, 13, 10, 7, 1));
        assertThat(replay.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.ALREADY_APPLIED);
        assertThat(replay.persisted()).isTrue();

        for (LegalEditorialReport report : List.of(
                applied,
                replay,
                blocked,
                error,
                unknown)) {
            assertThat(report.plan().operationId()).isEqualTo(OPERATION_ID);
            assertThat(report.plan().editorialPlanSha256()).isEqualTo(PLAN_SHA256);
            assertThat(report.plan().expectedReadinessAfter())
                    .isEqualTo(LegalEditorialReadiness.NOT_READY);
            assertThat(report.plan().changeRequired()).isNull();
            assertThat(report.plan().observedAt()).isNull();
            assertThat(report.counts().delta()).isNull();
        }
        assertThat(blocked.persisted()).isFalse();
        assertThat(error.persisted()).isFalse();
        assertThat(unknown.persisted()).isNull();
        assertNoDatabaseMetadata(blocked);
        assertNoDatabaseMetadata(error);
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
    void conservativeUnknownReplaceBoundaryRetainsOnlyConfirmedInputIdentity() {
        LegalEditorialReport unknown = LegalEditorialReport.forUnknownApplyReplace(
                release,
                validatedReplacePlan());

        assertThat(unknown.command()).isEqualTo("apply-replace");
        assertThat(unknown.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(unknown.persisted()).isNull();
        assertThat(unknown.operation().operationType())
                .isEqualTo(LegalEditorialReport.OperationType.REPLACE);
        assertThat(unknown.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.UNKNOWN);
        assertThat(unknown.plan().operationId()).isEqualTo(OPERATION_ID);
        assertThat(unknown.plan().editorialPlanSha256()).isEqualTo(PLAN_SHA256);
        assertThat(unknown.plan().changeRequired()).isNull();
        assertThat(unknown.plan().observedAt()).isNull();
        assertThat(unknown.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN);
            assertThat(issue.location()).isEqualTo("database/commit");
        });
        assertNoDatabaseMetadata(unknown);
    }

    @Test
    void conservativeUnknownRetireBoundaryRetainsOnlyConfirmedInputIdentity() {
        LegalEditorialReport unknown = LegalEditorialReport.forUnknownApplyRetire(
                release,
                validatedRetirePlan());

        assertThat(unknown.command()).isEqualTo("apply-retire");
        assertThat(unknown.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(unknown.persisted()).isNull();
        assertThat(unknown.operation().operationType())
                .isEqualTo(LegalEditorialReport.OperationType.RETIRE);
        assertThat(unknown.operation().outcome())
                .isEqualTo(LegalEditorialReport.Outcome.UNKNOWN);
        assertThat(unknown.plan().operationId()).isEqualTo(OPERATION_ID);
        assertThat(unknown.plan().editorialPlanSha256()).isEqualTo(PLAN_SHA256);
        assertThat(unknown.plan().expectedReadinessAfter())
                .isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(unknown.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN);
            assertThat(issue.location()).isEqualTo("database/commit");
        });
        assertNoDatabaseMetadata(unknown);
    }

    @Test
    void knownPreflightFailuresRemainTypedForEveryEditorialCommand() {
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
        LegalEditorialReport applyReplaceBeforeConfirmation =
                LegalEditorialReport.forKnownFailure(
                        Command.APPLY_REPLACE,
                        null,
                        blockedIssue);
        LegalEditorialReport retireBeforeConfirmation =
                LegalEditorialReport.forKnownFailure(
                        Command.PLAN_RETIRE,
                        null,
                        blockedIssue);
        LegalEditorialReport applyRetireBeforeConfirmation =
                LegalEditorialReport.forKnownFailure(
                        Command.APPLY_RETIRE,
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
        assertThat(retireBeforeConfirmation.operation().operationType())
                .isEqualTo(LegalEditorialReport.OperationType.RETIRE);
        assertThat(retireBeforeConfirmation.plan()).isNull();
        for (LegalEditorialReport failure : List.of(
                readiness,
                plan,
                apply,
                replaceBeforeConfirmation,
                applyReplaceBeforeConfirmation,
                retireBeforeConfirmation,
                applyRetireBeforeConfirmation)) {
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

        LegalEditorialReport applyReplaceAfterConfirmation =
                LegalEditorialReport.forKnownFailure(
                        Command.APPLY_REPLACE,
                        release,
                        editorialPlan,
                        issue(
                                LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                                "documentReplacementBatches"));
        assertThat(applyReplaceAfterConfirmation.plan().operationId())
                .isEqualTo(OPERATION_ID);
        assertThat(applyReplaceAfterConfirmation.plan().editorialPlanSha256())
                .isEqualTo(PLAN_SHA256);
        assertNoDatabaseMetadata(applyReplaceAfterConfirmation);

        ValidatedEditorialPlan retirePlan = validatedRetirePlan();
        LegalEditorialReport retireAfterConfirmation =
                LegalEditorialReport.forKnownFailure(
                        Command.PLAN_RETIRE,
                        release,
                        retirePlan,
                        issue(
                                LegalManifestIssueCode.RETIREMENT_REASON_REQUIRED,
                                "documentRetirements"));
        LegalEditorialReport applyRetireAfterConfirmation =
                LegalEditorialReport.forKnownFailure(
                        Command.APPLY_RETIRE,
                        release,
                        retirePlan,
                        issue(
                                LegalManifestIssueCode.RETIREMENT_REASON_REQUIRED,
                                "documentRetirements"));
        for (LegalEditorialReport retireFailure : List.of(
                retireAfterConfirmation,
                applyRetireAfterConfirmation)) {
            assertThat(retireFailure.plan().operationId()).isEqualTo(OPERATION_ID);
            assertThat(retireFailure.plan().editorialPlanSha256()).isEqualTo(PLAN_SHA256);
            assertThat(retireFailure.plan().expectedReadinessAfter())
                    .isEqualTo(LegalEditorialReadiness.NOT_READY);
            assertNoDatabaseMetadata(retireFailure);
        }

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

        assertThatThrownBy(() -> LegalEditorialReport.forApplyReplace(
                release,
                validatedReplacePlan(),
                applyResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        LegalEditorialApplyResult.Outcome.APPLIED,
                        receipt(),
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LegalEditorialReport.forPlanRetire(
                release,
                validatedRetirePlan(),
                planResult(
                        LegalManifestStatus.PASS,
                        LegalEditorialPlanResult.Outcome.APPLICABLE,
                        LegalEditorialReadiness.READY,
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LegalEditorialReport.forApplyRetire(
                release,
                validatedRetirePlan(),
                applyResult(
                        LegalManifestStatus.PASS,
                        Boolean.TRUE,
                        LegalEditorialApplyResult.Outcome.APPLIED,
                        replaceReceipt,
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LegalEditorialReport.forPlanRetire(
                release,
                validatedReplacePlan(),
                planResult(
                        LegalManifestStatus.PASS,
                        LegalEditorialPlanResult.Outcome.APPLICABLE,
                        LegalEditorialReadiness.NOT_READY,
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LegalEditorialReport.forPlanReplace(
                release,
                validatedRetirePlan(),
                planResult(
                        LegalManifestStatus.PASS,
                        LegalEditorialPlanResult.Outcome.APPLICABLE,
                        LegalEditorialReadiness.READY,
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownRejectsEveryMatchingTentativeReceiptBeforeExposingDatabaseMetadata() {
        LegalEditorialApplyResult poisonedPromote = applyResult(
                LegalManifestStatus.ERROR,
                null,
                LegalEditorialApplyResult.Outcome.UNKNOWN,
                receipt(),
                List.of(issue(
                        LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                        "database/commit")));
        LegalEditorialApplyResult poisonedReplace = applyResult(
                LegalManifestStatus.ERROR,
                null,
                LegalEditorialApplyResult.Outcome.UNKNOWN,
                replaceReceipt(),
                List.of(issue(
                        LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                        "database/commit")));
        LegalEditorialApplyResult poisonedRetire = applyResult(
                LegalManifestStatus.ERROR,
                null,
                LegalEditorialApplyResult.Outcome.UNKNOWN,
                retireReceipt(),
                List.of(issue(
                        LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                        "database/commit")));

        assertThatThrownBy(() -> LegalEditorialReport.forApplyPromote(
                release,
                poisonedPromote))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialReport.forApplyReplace(
                release,
                validatedReplacePlan(),
                poisonedReplace))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialReport.forApplyRetire(
                release,
                validatedRetirePlan(),
                poisonedRetire))
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
        return planResult(status, outcome, LegalEditorialReadiness.READY, issues);
    }

    private static LegalEditorialPlanResult planResult(
            LegalManifestStatus status,
            LegalEditorialPlanResult.Outcome outcome,
            LegalEditorialReadiness expectedReadinessAfter,
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
                    .thenReturn(Optional.of(expectedReadinessAfter));
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
        return validatedReplacePlan(OPERATION_ID, PLAN_SHA256);
    }

    private static ValidatedEditorialPlan validatedReplacePlan(
            UUID operationId,
            String editorialPlanSha256) {
        LegalEditorialPlanV1 model = mock(LegalEditorialPlanV1.class);
        when(model.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        ValidatedEditorialPlan plan = mock(ValidatedEditorialPlan.class);
        when(plan.plan()).thenReturn(model);
        when(plan.operationType()).thenReturn(OperationType.REPLACE);
        when(plan.operationId()).thenReturn(operationId);
        when(plan.editorialPlanSha256()).thenReturn(editorialPlanSha256);
        return plan;
    }

    private static ValidatedEditorialPlan validatedRetirePlan() {
        LegalEditorialPlanV1 model = mock(LegalEditorialPlanV1.class);
        when(model.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.NOT_READY);
        ValidatedEditorialPlan plan = mock(ValidatedEditorialPlan.class);
        when(plan.plan()).thenReturn(model);
        when(plan.operationType()).thenReturn(OperationType.RETIRE);
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

    private static LegalEditorialApplyReceipt replaceReceipt() {
        return new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.REPLACE,
                PUBLICATION_UUID,
                OBSERVED_AT,
                LegalEditorialReadiness.READY,
                11, 6, 34, 12, 11, 8, 1);
    }

    private static LegalEditorialApplyReceipt retireReceipt() {
        return new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.RETIRE,
                PUBLICATION_UUID,
                OBSERVED_AT,
                LegalEditorialReadiness.NOT_READY,
                11, 6, 35, 13, 10, 7, 1);
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
