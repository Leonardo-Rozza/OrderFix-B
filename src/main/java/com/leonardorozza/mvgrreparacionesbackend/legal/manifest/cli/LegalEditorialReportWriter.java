package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialReport.Counts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialReport.DeltaCounts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialReport.Operation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialReport.Plan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialReport.Publication;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialReport.Readiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialReport.ReleaseCounts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalEditorialReport.StateCounts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Objects;

/** Writes exactly one compact UTF-8 v3 object and leaves the caller-owned stream open. */
public final class LegalEditorialReportWriter {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .build();

    /** Writes and flushes one report without appending a newline. */
    public void write(LegalEditorialReport report, OutputStream output) throws IOException {
        LegalEditorialReport requiredReport = Objects.requireNonNull(report, "report");
        OutputStream requiredOutput = Objects.requireNonNull(output, "output");
        try (JsonGenerator json = JSON_FACTORY.createGenerator(requiredOutput, JsonEncoding.UTF8)) {
            writeReport(json, requiredReport);
        }
    }

    private static void writeReport(JsonGenerator json, LegalEditorialReport report)
            throws IOException {
        json.writeStartObject();
        json.writeNumberField("reportVersion", report.reportVersion());
        json.writeStringField("command", report.command());
        json.writeStringField("status", report.status().name());
        writeNullableBoolean(json, "persisted", report.persisted());
        writePublication(json, report.publication());
        writeOperation(json, report.operation());
        writePlan(json, report.plan());
        writeReadiness(json, report.readiness());
        writeCounts(json, report.counts());
        writeIssues(json, report);
        json.writeNumberField("omittedIssueCount", report.omittedIssueCount());
        json.writeEndObject();
    }

    private static void writePublication(JsonGenerator json, Publication publication)
            throws IOException {
        if (publication == null) {
            json.writeNullField("publication");
            return;
        }
        json.writeObjectFieldStart("publication");
        json.writeStringField("publicationId", publication.publicationId());
        json.writeNumberField("schemaVersion", publication.schemaVersion());
        json.writeStringField("manifestSha256", publication.manifestSha256());
        writeNullableString(
                json,
                "publicationUuid",
                publication.publicationUuid() == null
                        ? null
                        : publication.publicationUuid().toString());
        json.writeEndObject();
    }

    private static void writeOperation(JsonGenerator json, Operation operation)
            throws IOException {
        if (operation == null) {
            json.writeNullField("operation");
            return;
        }
        json.writeObjectFieldStart("operation");
        json.writeStringField("operationType", operation.operationType().name());
        json.writeStringField("outcome", operation.outcome().name());
        writeNullableInstant(json, "appliedAt", operation.appliedAt());
        json.writeEndObject();
    }

    private static void writePlan(JsonGenerator json, Plan plan) throws IOException {
        if (plan == null) {
            json.writeNullField("plan");
            return;
        }
        json.writeObjectFieldStart("plan");
        writeNullableString(
                json,
                "operationId",
                plan.operationId() == null ? null : plan.operationId().toString());
        writeNullableString(json, "editorialPlanSha256", plan.editorialPlanSha256());
        writeNullableBoolean(json, "changeRequired", plan.changeRequired());
        writeNullableInstant(json, "observedAt", plan.observedAt());
        json.writeStringField(
                "expectedReadinessAfter",
                plan.expectedReadinessAfter().name());
        json.writeEndObject();
    }

    private static void writeReadiness(JsonGenerator json, Readiness readiness)
            throws IOException {
        if (readiness == null) {
            json.writeNullField("readiness");
            return;
        }
        json.writeObjectFieldStart("readiness");
        json.writeStringField("value", readiness.value().name());
        writeNullableInstant(json, "observedAt", readiness.observedAt());
        writeNullableString(
                json,
                "editorialStateFingerprint",
                readiness.editorialStateFingerprint());
        json.writeEndObject();
    }

    private static void writeCounts(JsonGenerator json, Counts counts) throws IOException {
        if (counts == null) {
            json.writeNullField("counts");
            return;
        }
        json.writeObjectFieldStart("counts");
        writeReleaseCounts(json, counts.release());
        writeStateCounts(json, counts.state());
        writeDeltaCounts(json, counts.delta());
        json.writeEndObject();
    }

    private static void writeReleaseCounts(JsonGenerator json, ReleaseCounts counts)
            throws IOException {
        json.writeObjectFieldStart("release");
        json.writeNumberField("documents", counts.documents());
        json.writeNumberField("requirements", counts.requirements());
        json.writeNumberField("scopes", counts.scopes());
        json.writeEndObject();
    }

    private static void writeStateCounts(JsonGenerator json, StateCounts counts)
            throws IOException {
        if (counts == null) {
            json.writeNullField("state");
            return;
        }
        json.writeObjectFieldStart("state");
        json.writeNumberField("documentVersions", counts.documentVersions());
        json.writeNumberField("requirementVersions", counts.requirementVersions());
        json.writeNumberField("documentTransitions", counts.documentTransitions());
        json.writeNumberField("requirementTransitions", counts.requirementTransitions());
        json.writeNumberField("documentSlots", counts.documentSlots());
        json.writeNumberField("requiredSetPointers", counts.requiredSetPointers());
        json.writeNumberField("replacementBatches", counts.replacementBatches());
        json.writeEndObject();
    }

    private static void writeDeltaCounts(JsonGenerator json, DeltaCounts counts)
            throws IOException {
        if (counts == null) {
            json.writeNullField("delta");
            return;
        }
        json.writeObjectFieldStart("delta");
        json.writeNumberField(
                "directDocumentTransitions",
                counts.directDocumentTransitions());
        json.writeNumberField(
                "triggerDerivedDocumentTransitions",
                counts.triggerDerivedDocumentTransitions());
        json.writeNumberField(
                "directRequirementTransitions",
                counts.directRequirementTransitions());
        json.writeNumberField("directDocumentSlotDeletes", counts.directDocumentSlotDeletes());
        json.writeNumberField("directDocumentSlotInserts", counts.directDocumentSlotInserts());
        json.writeNumberField(
                "triggerDerivedDocumentSlotDeletes",
                counts.triggerDerivedDocumentSlotDeletes());
        json.writeNumberField(
                "triggerDerivedDocumentSlotInserts",
                counts.triggerDerivedDocumentSlotInserts());
        json.writeNumberField(
                "requiredSetPointerDeletes",
                counts.requiredSetPointerDeletes());
        json.writeNumberField(
                "requiredSetPointerInserts",
                counts.requiredSetPointerInserts());
        json.writeNumberField("replacementBatches", counts.replacementBatches());
        json.writeEndObject();
    }

    private static void writeIssues(JsonGenerator json, LegalEditorialReport report)
            throws IOException {
        json.writeArrayFieldStart("issues");
        for (LegalManifestIssue issue : report.issues()) {
            json.writeStartObject();
            json.writeStringField("severity", issue.severity().name());
            json.writeStringField("code", issue.code().name());
            json.writeStringField("location", issue.location());
            json.writeStringField("message", issue.message());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private static void writeNullableInstant(
            JsonGenerator json,
            String field,
            Instant value) throws IOException {
        writeNullableString(json, field, value == null ? null : value.toString());
    }

    private static void writeNullableString(
            JsonGenerator json,
            String field,
            String value) throws IOException {
        if (value == null) {
            json.writeNullField(field);
        } else {
            json.writeStringField(field, value);
        }
    }

    private static void writeNullableBoolean(
            JsonGenerator json,
            String field,
            Boolean value) throws IOException {
        if (value == null) {
            json.writeNullField(field);
        } else {
            json.writeBooleanField(field, value);
        }
    }
}
