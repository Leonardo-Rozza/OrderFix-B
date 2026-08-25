package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestReport.Counts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestReport.DryRunCounts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestReport.Publication;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Writes one compact UTF-8 report object in a stable field order and without a trailing newline. */
public final class LegalManifestReportWriter {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .build();

    /** Writes exactly one JSON object, flushes it and leaves the caller-owned stream open. */
    public void write(LegalManifestReport report, OutputStream output) throws IOException {
        LegalManifestReport requiredReport = Objects.requireNonNull(report, "report");
        OutputStream requiredOutput = Objects.requireNonNull(output, "output");
        try (JsonGenerator json = JSON_FACTORY.createGenerator(requiredOutput, JsonEncoding.UTF8)) {
            writeReport(json, requiredReport);
        }
    }

    private static void writeReport(JsonGenerator json, LegalManifestReport report)
            throws IOException {
        json.writeStartObject();
        json.writeNumberField("reportVersion", report.reportVersion());
        writeNullableString(json, "command", report.command());
        json.writeStringField("status", report.status().name());
        json.writeBooleanField("persisted", report.persisted());
        writePublication(json, report.publication());
        writeCounts(json, report.counts());
        writeDryRun(json, report.dryRun());
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

    private static void writeDryRun(JsonGenerator json, DryRunCounts dryRun)
            throws IOException {
        if (dryRun == null) {
            json.writeNullField("dryRun");
            return;
        }
        json.writeObjectFieldStart("dryRun");
        json.writeNumberField("newDocumentLines", dryRun.newDocumentLines());
        json.writeNumberField("newDocumentVersions", dryRun.newDocumentVersions());
        json.writeNumberField("reusedDocumentVersions", dryRun.reusedDocumentVersions());
        json.writeNumberField("newRequirementLines", dryRun.newRequirementLines());
        json.writeNumberField("newRequirementVersions", dryRun.newRequirementVersions());
        json.writeNumberField("reusedRequirementVersions", dryRun.reusedRequirementVersions());
        json.writeEndObject();
    }

    private static void writeIssues(JsonGenerator json, LegalManifestReport report)
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
}
