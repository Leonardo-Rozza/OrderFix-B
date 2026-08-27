package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestImportReport.Counts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestImportReport.ImportDetails;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestImportReport.Publication;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Objects;

/** Writes one compact UTF-8 v2 import report and leaves the caller-owned stream open. */
public final class LegalManifestImportReportWriter {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .build();

    /** Writes exactly one JSON object, flushes it and never appends a newline. */
    public void write(LegalManifestImportReport report, OutputStream output) throws IOException {
        LegalManifestImportReport requiredReport = Objects.requireNonNull(report, "report");
        OutputStream requiredOutput = Objects.requireNonNull(output, "output");
        try (JsonGenerator json = JSON_FACTORY.createGenerator(requiredOutput, JsonEncoding.UTF8)) {
            writeReport(json, requiredReport);
        }
    }

    private static void writeReport(JsonGenerator json, LegalManifestImportReport report)
            throws IOException {
        json.writeStartObject();
        json.writeNumberField("reportVersion", report.reportVersion());
        json.writeStringField("command", report.command());
        json.writeStringField("status", report.status().name());
        writeNullableBoolean(json, "persisted", report.persisted());
        writePublication(json, report.publication());
        writeCounts(json, report.counts());
        json.writeNullField("dryRun");
        writeImport(json, report.importDetails());
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
        json.writeEndObject();
    }

    private static void writeCounts(JsonGenerator json, Counts counts) throws IOException {
        if (counts == null) {
            json.writeNullField("counts");
            return;
        }
        json.writeObjectFieldStart("counts");
        json.writeNumberField("documents", counts.documents());
        json.writeNumberField("requirements", counts.requirements());
        json.writeNumberField("scopes", counts.scopes());
        json.writeEndObject();
    }

    private static void writeImport(JsonGenerator json, ImportDetails importDetails)
            throws IOException {
        if (importDetails == null) {
            json.writeNullField("import");
            return;
        }
        json.writeObjectFieldStart("import");
        json.writeStringField("outcome", importDetails.outcome().name());
        writeNullableString(
                json,
                "publicationUuid",
                importDetails.publicationUuid() == null
                        ? null
                        : importDetails.publicationUuid().toString());
        writeNullableInstant(json, "importedAt", importDetails.importedAt());
        writeNullableInstant(json, "sealedAt", importDetails.sealedAt());
        writeNullableBoolean(json, "sealed", importDetails.sealed());
        json.writeBooleanField("promotionChanged", importDetails.promotionChanged());
        json.writeEndObject();
    }

    private static void writeIssues(JsonGenerator json, LegalManifestImportReport report)
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
