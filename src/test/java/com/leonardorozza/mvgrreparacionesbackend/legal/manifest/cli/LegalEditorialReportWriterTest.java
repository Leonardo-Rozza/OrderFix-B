package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalEditorialReportWriterTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String GOLDEN_SHA =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ValidatedRelease release;
    private final LegalEditorialReportWriter writer = new LegalEditorialReportWriter();

    @BeforeAll
    static void validateRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalEditorialReportWriterTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        release = validation.value().orElseThrow();
    }

    @Test
    void writesReadyInTheExactFrozenTopLevelAndNestedOrder() throws IOException {
        LegalEditorialReadinessResult result = mock(LegalEditorialReadinessResult.class);
        when(result.status()).thenReturn(LegalManifestStatus.PASS);
        when(result.readiness()).thenReturn(LegalEditorialReadiness.READY);
        when(result.observation()).thenReturn(Optional.of(observation()));
        when(result.issues()).thenReturn(List.of());
        when(result.omittedIssueCount()).thenReturn(0);
        CloseTrackingOutputStream output = new CloseTrackingOutputStream();

        writer.write(LegalEditorialReport.forReadiness(release, result), output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).isEqualTo("{\"reportVersion\":3,\"command\":\"readiness\","
                + "\"status\":\"PASS\",\"persisted\":false,\"publication\":{"
                + "\"publicationId\":\"release-valid-v1\",\"schemaVersion\":1,"
                + "\"manifestSha256\":\"" + GOLDEN_SHA + "\","
                + "\"publicationUuid\":\"60870649-efbb-4d2b-8407-4f1901b86a45\"},"
                + "\"operation\":null,\"plan\":null,\"readiness\":{"
                + "\"value\":\"READY\",\"observedAt\":\"2026-08-28T18:00:00.123456Z\","
                + "\"editorialStateFingerprint\":\"" + FINGERPRINT + "\"},"
                + "\"counts\":{\"release\":{\"documents\":11,\"requirements\":6,"
                + "\"scopes\":8},\"state\":{\"documentVersions\":11,"
                + "\"requirementVersions\":6,\"documentTransitions\":34,"
                + "\"requirementTransitions\":12,\"documentSlots\":11,"
                + "\"requiredSetPointers\":8,\"replacementBatches\":0},"
                + "\"delta\":null},\"issues\":[],\"omittedIssueCount\":0}");
        assertThat(json).doesNotEndWith("\n").doesNotEndWith("\r");
        assertThat(output.closed()).isFalse();
        assertExactlyOneJsonObject(output.toByteArray());
    }

    @Test
    void writesApplicablePlanWithFuturePlanIdentitySlotsAlreadyFrozen() throws IOException {
        LegalEditorialPlanResult result = mock(LegalEditorialPlanResult.class);
        when(result.status()).thenReturn(LegalManifestStatus.PASS);
        when(result.outcome()).thenReturn(LegalEditorialPlanResult.Outcome.APPLICABLE);
        when(result.targetPublicationUuid()).thenReturn(Optional.of(PUBLICATION_UUID));
        when(result.changeRequired()).thenReturn(Optional.of(true));
        when(result.observedAt()).thenReturn(Optional.of(OBSERVED_AT));
        when(result.expectedReadinessAfter())
                .thenReturn(Optional.of(LegalEditorialReadiness.READY));
        when(result.deltaCounts()).thenReturn(Optional.of(new LegalEditorialPlanResult.DeltaCounts(
                22, 0, 12, 0, 11, 0, 0, 0, 8, 0)));
        when(result.issues()).thenReturn(List.of());
        when(result.omittedIssueCount()).thenReturn(0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(LegalEditorialReport.forPlanPromote(release, result), output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).containsSubsequence(
                "\"operation\":{\"operationType\":\"PROMOTE\","
                        + "\"outcome\":\"APPLICABLE\",\"appliedAt\":null}",
                "\"plan\":{\"operationId\":null,\"editorialPlanSha256\":null,"
                        + "\"changeRequired\":true,"
                        + "\"observedAt\":\"2026-08-28T18:00:00.123456Z\","
                        + "\"expectedReadinessAfter\":\"READY\"}",
                "\"readiness\":{\"value\":\"READY\",\"observedAt\":null,"
                        + "\"editorialStateFingerprint\":null}",
                "\"counts\":{\"release\":{\"documents\":11,\"requirements\":6,"
                        + "\"scopes\":8},\"state\":null,\"delta\":{"
                        + "\"directDocumentTransitions\":22");
        assertExactlyOneJsonObject(output.toByteArray());
    }

    @Test
    void writesConfirmedReplaceIdentityWithoutInventingObservationMetadata() throws IOException {
        ValidatedEditorialPlan editorialPlan = validatedReplacePlan();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(
                LegalEditorialReport.forKnownFailure(
                        Command.PLAN_REPLACE,
                        release,
                        editorialPlan,
                        LegalManifestIssue.at(
                                LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                                "documentReplacementBatches")),
                output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).isEqualTo("{\"reportVersion\":3,\"command\":\"plan-replace\","
                + "\"status\":\"BLOCKED\",\"persisted\":false,\"publication\":{"
                + "\"publicationId\":\"release-valid-v1\",\"schemaVersion\":1,"
                + "\"manifestSha256\":\"" + GOLDEN_SHA + "\",\"publicationUuid\":null},"
                + "\"operation\":{\"operationType\":\"REPLACE\",\"outcome\":\"BLOCKED\","
                + "\"appliedAt\":null},\"plan\":{\"operationId\":\"" + OPERATION_ID + "\","
                + "\"editorialPlanSha256\":\"" + PLAN_SHA256 + "\","
                + "\"changeRequired\":null,\"observedAt\":null,"
                + "\"expectedReadinessAfter\":\"READY\"},\"readiness\":null,"
                + "\"counts\":{\"release\":{\"documents\":11,\"requirements\":6,"
                + "\"scopes\":8},\"state\":null,\"delta\":null},\"issues\":[{"
                + "\"severity\":\"BLOCKED\",\"code\":\"REPLACEMENT_MAPPING_INVALID\","
                + "\"location\":\"documentReplacementBatches\","
                + "\"message\":\"El mapeo editorial de reemplazo no es válido.\"}],"
                + "\"omittedIssueCount\":0}");
        assertExactlyOneJsonObject(output.toByteArray());
    }

    @Test
    void writesUnknownWithExplicitNullDatabaseMetadata() throws IOException {
        LegalEditorialApplyResult result = mock(LegalEditorialApplyResult.class);
        when(result.status()).thenReturn(LegalManifestStatus.ERROR);
        when(result.persisted()).thenReturn(null);
        when(result.outcome()).thenReturn(LegalEditorialApplyResult.Outcome.UNKNOWN);
        when(result.receipt()).thenReturn(Optional.empty());
        when(result.issues()).thenReturn(List.of(LegalManifestIssue.at(
                LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                "database/commit")));
        when(result.omittedIssueCount()).thenReturn(0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(LegalEditorialReport.forApplyPromote(release, result), output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).isEqualTo("{\"reportVersion\":3,\"command\":\"apply-promote\","
                + "\"status\":\"ERROR\",\"persisted\":null,\"publication\":{"
                + "\"publicationId\":\"release-valid-v1\",\"schemaVersion\":1,"
                + "\"manifestSha256\":\"" + GOLDEN_SHA + "\","
                + "\"publicationUuid\":null},\"operation\":{"
                + "\"operationType\":\"PROMOTE\",\"outcome\":\"UNKNOWN\","
                + "\"appliedAt\":null},\"plan\":null,\"readiness\":null,"
                + "\"counts\":{\"release\":{\"documents\":11,\"requirements\":6,"
                + "\"scopes\":8},\"state\":null,\"delta\":null},\"issues\":[{"
                + "\"severity\":\"ERROR\",\"code\":\"COMMIT_OUTCOME_UNKNOWN\","
                + "\"location\":\"database/commit\","
                + "\"message\":\"No se pudo determinar si la operación editorial fue "
                + "confirmada.\"}],\"omittedIssueCount\":0}");
        JsonNode parsed = JSON.readTree(json);
        assertThat(parsed.path("persisted").isNull()).isTrue();
        assertThat(parsed.path("publication").path("publicationUuid").isNull()).isTrue();
        assertThat(parsed.path("operation").path("appliedAt").isNull()).isTrue();
        assertThat(parsed.path("readiness").isNull()).isTrue();
        assertThat(parsed.path("counts").path("state").isNull()).isTrue();
        assertThat(parsed.path("counts").path("delta").isNull()).isTrue();
    }

    @Test
    void writesUnknownReplaceWithPlanIdentityAndExplicitNullDatabaseMetadata()
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(
                LegalEditorialReport.forUnknownApplyReplace(
                        release,
                        validatedReplacePlan()),
                output);

        String json = output.toString(StandardCharsets.UTF_8);
        assertThat(json).isEqualTo("{\"reportVersion\":3,\"command\":\"apply-replace\","
                + "\"status\":\"ERROR\",\"persisted\":null,\"publication\":{"
                + "\"publicationId\":\"release-valid-v1\",\"schemaVersion\":1,"
                + "\"manifestSha256\":\"" + GOLDEN_SHA + "\",\"publicationUuid\":null},"
                + "\"operation\":{\"operationType\":\"REPLACE\",\"outcome\":\"UNKNOWN\","
                + "\"appliedAt\":null},\"plan\":{\"operationId\":\"" + OPERATION_ID + "\","
                + "\"editorialPlanSha256\":\"" + PLAN_SHA256 + "\","
                + "\"changeRequired\":null,\"observedAt\":null,"
                + "\"expectedReadinessAfter\":\"READY\"},\"readiness\":null,"
                + "\"counts\":{\"release\":{\"documents\":11,\"requirements\":6,"
                + "\"scopes\":8},\"state\":null,\"delta\":null},\"issues\":[{"
                + "\"severity\":\"ERROR\",\"code\":\"COMMIT_OUTCOME_UNKNOWN\","
                + "\"location\":\"database/commit\","
                + "\"message\":\"No se pudo determinar si la operación editorial fue "
                + "confirmada.\"}],\"omittedIssueCount\":0}");
        assertExactlyOneJsonObject(output.toByteArray());
    }

    @Test
    void outputNeverLeaksContentPathsSqlOrRuntimeDetails() throws IOException {
        LegalEditorialApplyReceipt receipt = new LegalEditorialApplyReceipt(
                LegalEditorialApplyReceipt.OperationType.PROMOTE,
                PUBLICATION_UUID,
                OBSERVED_AT,
                LegalEditorialReadiness.READY,
                11, 6, 34, 12, 11, 8, 0);
        LegalEditorialApplyResult result = mock(LegalEditorialApplyResult.class);
        when(result.status()).thenReturn(LegalManifestStatus.PASS);
        when(result.persisted()).thenReturn(Boolean.TRUE);
        when(result.outcome()).thenReturn(LegalEditorialApplyResult.Outcome.APPLIED);
        when(result.receipt()).thenReturn(Optional.of(receipt));
        when(result.issues()).thenReturn(List.of());
        when(result.omittedIssueCount()).thenReturn(0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(LegalEditorialReport.forApplyPromote(release, result), output);

        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(
                "OrdenFix Argentina SAS",
                "30-00000000-0",
                "legal@ordenfix.com",
                "# Términos",
                "/Volumes/",
                "publication-manifest.json",
                "SELECT ",
                "Exception",
                "password");
    }

    private static LegalEditorialReadinessObservation observation() {
        return new LegalEditorialReadinessObservation(
                Optional.of(PUBLICATION_UUID),
                OBSERVED_AT,
                FINGERPRINT,
                11, 6, 34, 12, 11, 8, 0);
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

    private static void assertExactlyOneJsonObject(byte[] bytes) throws IOException {
        try (JsonParser parser = JSON.getFactory().createParser(bytes)) {
            assertThat(parser.nextToken()).isEqualTo(JsonToken.START_OBJECT);
            parser.skipChildren();
            assertThat(parser.nextToken()).isNull();
        }
    }

    private static final class CloseTrackingOutputStream extends ByteArrayOutputStream {

        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean closed() {
            return closed;
        }
    }
}
