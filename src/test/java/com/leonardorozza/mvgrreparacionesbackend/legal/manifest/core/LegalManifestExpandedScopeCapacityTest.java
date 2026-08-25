package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegalManifestExpandedScopeCapacityTest {

    @Test
    void acceptsExactlySixteenMiBAndCountsARepeatedReferenceAcrossRequirements() {
        Map<String, List<Long>> documentBytes = oneMiBDocuments(16);
        RequirementEntry first = requirement(
                "first",
                List.of(AudienciaLegal.ADMIN_TITULAR),
                new ArrayList<>(documentBytes.keySet()));

        assertThat(LegalManifestContractValidator.validateExpandedScopeMarkdownCapacity(
                List.of(first),
                documentBytes)).isEmpty();

        RequirementEntry second = requirement(
                "second",
                List.of(AudienciaLegal.ADMIN_TITULAR),
                List.of("document-0"));

        assertThat(LegalManifestContractValidator.validateExpandedScopeMarkdownCapacity(
                List.of(first, second),
                documentBytes))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.code())
                            .isEqualTo(LegalManifestIssueCode.DOCUMENT_TOTAL_SIZE_LIMIT_EXCEEDED);
                    assertThat(issue.severity()).isEqualTo(LegalManifestStatus.BLOCKED);
                    assertThat(issue.location())
                            .isEqualTo("scopes/USO_CONTINUADO/ADMIN_TITULAR");
                });
    }

    @Test
    void countsEveryRepeatedReferenceAndAudienceOccurrence() {
        RequirementEntry repeated = requirement(
                "repeated",
                List.of(AudienciaLegal.USER, AudienciaLegal.USER),
                Collections.nCopies(16, "document"));

        assertThat(LegalManifestContractValidator.validateExpandedScopeMarkdownCapacity(
                List.of(repeated),
                Map.of("document", List.of((long) LegalManifestLimits.MAX_MARKDOWN_BYTES))))
                .singleElement()
                .extracting(LegalManifestIssue::location)
                .isEqualTo("scopes/USO_CONTINUADO/USER");
    }

    @Test
    void countsEveryDuplicateRequirementOccurrence() {
        RequirementEntry duplicate = requirement(
                "duplicate",
                List.of(AudienciaLegal.USER),
                List.of("document"));

        assertThat(LegalManifestContractValidator.validateExpandedScopeMarkdownCapacity(
                Collections.nCopies(17, duplicate),
                Map.of("document", List.of((long) LegalManifestLimits.MAX_MARKDOWN_BYTES))))
                .singleElement()
                .extracting(LegalManifestIssue::location)
                .isEqualTo("scopes/USO_CONTINUADO/USER");
    }

    @Test
    void countsEveryDuplicateDocumentDeclarationForEveryReference() {
        RequirementEntry repeatedReference = requirement(
                "duplicate-documents",
                List.of(AudienciaLegal.ADMIN_TITULAR),
                List.of("document", "document"));

        assertThat(LegalManifestContractValidator.validateExpandedScopeMarkdownCapacity(
                List.of(repeatedReference),
                Map.of(
                        "document",
                        Collections.nCopies(
                                9,
                                (long) LegalManifestLimits.MAX_MARKDOWN_BYTES))))
                .singleElement()
                .extracting(LegalManifestIssue::location)
                .isEqualTo("scopes/USO_CONTINUADO/ADMIN_TITULAR");
    }

    @Test
    void saturatesLongOverflowAndStillBlocksTheScope() {
        RequirementEntry requirement = requirement(
                "overflow",
                List.of(AudienciaLegal.ADMIN_TITULAR),
                List.of("document", "document"));

        assertThat(LegalManifestContractValidator.validateExpandedScopeMarkdownCapacity(
                List.of(requirement),
                Map.of("document", List.of(Long.MAX_VALUE))))
                .singleElement()
                .extracting(LegalManifestIssue::code)
                .isEqualTo(LegalManifestIssueCode.DOCUMENT_TOTAL_SIZE_LIMIT_EXCEEDED);
    }

    private static Map<String, List<Long>> oneMiBDocuments(int count) {
        Map<String, List<Long>> documents = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            documents.put(
                    "document-" + index,
                    List.of((long) LegalManifestLimits.MAX_MARKDOWN_BYTES));
        }
        return documents;
    }

    private static RequirementEntry requirement(
            String key,
            List<AudienciaLegal> roles,
            List<String> documents) {
        return new RequirementEntry(
                key,
                "2026-08-25",
                ContextoLegal.USO_CONTINUADO,
                roles,
                TipoActoLegal.LECTURA,
                "Declaro la lectura del documento legal.",
                "0".repeat(64),
                documents,
                false,
                false);
    }
}
