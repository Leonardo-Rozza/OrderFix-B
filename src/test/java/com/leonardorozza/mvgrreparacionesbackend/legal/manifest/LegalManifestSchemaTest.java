package com.leonardorozza.mvgrreparacionesbackend.legal.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalManifestSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void freezesTheClasspathResourceBySizeAndSha256() throws IOException {
        byte[] bytes;
        try (InputStream input = LegalManifestSchema.class
                .getResourceAsStream(LegalManifestSchema.RESOURCE_PATH)) {
            assertThat(input).isNotNull();
            bytes = input.readAllBytes();
        }

        assertThat(bytes).hasSize(LegalManifestSchema.EXPECTED_SIZE_BYTES);
        assertThat(sha256(bytes)).isEqualTo(LegalManifestSchema.EXPECTED_SHA256);
    }

    @Test
    void initializesTheDraft202012SchemaWithFormatAssertions() throws JsonProcessingException {
        var schema = LegalManifestSchema.schema();

        assertThat(schema.getSchemaContext().getDialect().getSpecificationVersion())
                .isEqualTo(SpecificationVersion.DRAFT_2020_12);
        assertThat(schema.getSchemaContext().getSchemaRegistryConfig().getFormatAssertionsEnabled())
                .isTrue();
        assertThat(schema.getValidators()).isNotEmpty();
        assertThat(LegalManifestSchema.validate(JSON.readTree(validManifest()))).isEmpty();

        List<Error> invalidEmail = LegalManifestSchema.validate(JSON.readTree(
                validManifest().replace("[EMAIL LEGAL]", "not-an-email")));
        assertThat(invalidEmail)
                .extracting(Error::getKeyword)
                .contains("format");
    }

    @Test
    void rejectsAFutureManifestVersion() throws JsonProcessingException {
        List<Error> errors = LegalManifestSchema.validate(JSON.readTree(
                validManifest().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")));

        assertThat(errors).isNotEmpty();
        assertThat(errors)
                .extracting(Error::getKeyword)
                .contains("const");
    }

    @Test
    void enforcesDocumentAndRequirementCollectionLimitsAtTheirExactBoundaries()
            throws JsonProcessingException {
        ObjectNode manifest = (ObjectNode) JSON.readTree(validManifest());

        ArrayNode documents = manifest.putArray("documents");
        ObjectNode documentTemplate = (ObjectNode) JSON.readTree(validManifest())
                .path("documents")
                .get(0);
        for (int index = 0; index < 128; index++) {
            documents.add(uniqueDocument(documentTemplate, index));
        }

        ArrayNode requirements = manifest.putArray("requirements");
        ObjectNode requirementTemplate = (ObjectNode) JSON.readTree(validManifest())
                .path("requirements")
                .get(0);
        for (int index = 0; index < 256; index++) {
            requirements.add(uniqueRequirement(requirementTemplate, index));
        }

        assertThat(LegalManifestSchema.validate(manifest)).isEmpty();

        documents.add(uniqueDocument(documentTemplate, 128));
        assertThat(LegalManifestSchema.validate(manifest))
                .extracting(Error::getKeyword)
                .contains("maxItems");
        documents.remove(documents.size() - 1);

        requirements.add(uniqueRequirement(requirementTemplate, 256));
        assertThat(LegalManifestSchema.validate(manifest))
                .extracting(Error::getKeyword)
                .contains("maxItems");
    }

    @Test
    void enforcesDocumentsPerRequirementAtTheExactBoundary()
            throws JsonProcessingException {
        ObjectNode manifest = (ObjectNode) JSON.readTree(validManifest());
        ArrayNode references = (ArrayNode) manifest.path("requirements")
                .get(0)
                .path("documents");
        references.removeAll();
        for (int index = 0; index < 16; index++) {
            references.add("document-" + index);
        }

        assertThat(LegalManifestSchema.validate(manifest)).isEmpty();

        references.add("document-16");
        assertThat(LegalManifestSchema.validate(manifest))
                .extracting(Error::getKeyword)
                .contains("maxItems");
    }

    @Test
    void blocksExternalSchemaReferencesWithoutNetworkResolution() {
        byte[] externalReference = """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$ref": "https://example.invalid/never-fetch.json"
                }
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> LegalManifestSchema.compileSchema(externalReference))
                .isInstanceOf(RuntimeException.class);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static JsonNode uniqueDocument(ObjectNode template, int index) {
        ObjectNode document = template.deepCopy();
        document.put("key", "document-" + index);
        document.put("source", "document-" + index + ".md");
        document.put("sha256", "%064x".formatted(index));
        return document;
    }

    private static JsonNode uniqueRequirement(ObjectNode template, int index) {
        ObjectNode requirement = template.deepCopy();
        requirement.put("key", "requirement-" + index);
        requirement.put("statement", "Acepto el requisito " + index + '.');
        requirement.put("statementSha256", "%064x".formatted(index));
        return requirement;
    }

    private static String validManifest() {
        return """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "schemaVersion": 1,
                  "publicationId": "release-2026-08-24",
                  "locale": "es-AR",
                  "publisherSnapshot": {
                    "legalName": "[RAZÓN SOCIAL]",
                    "taxId": "[CUIT]",
                    "legalAddress": "[DOMICILIO]",
                    "jurisdiction": "[JURISDICCIÓN]",
                    "businessHours": "[HORARIO DE ATENCIÓN]",
                    "contacts": {
                      "legalEmail": "[EMAIL LEGAL]",
                      "privacyEmail": "[EMAIL PRIVACIDAD]",
                      "supportEmail": "[EMAIL LEGAL]"
                    }
                  },
                  "review": {
                    "legal": {"status": "PENDING"},
                    "accounting": {"status": "PENDING"}
                  },
                  "documents": [
                    {
                      "key": "terms",
                      "type": "TERMINOS_SERVICIO",
                      "version": "1",
                      "locale": "es-AR",
                      "source": "terms.md",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "effectiveAt": "2026-08-24T00:00:00Z",
                      "contexts": ["REGISTRO"],
                      "requiresReacceptance": false
                    }
                  ],
                  "requirements": [
                    {
                      "key": "registration",
                      "version": "1",
                      "context": "REGISTRO",
                      "roles": ["ADMIN_TITULAR"],
                      "actType": "ACEPTACION",
                      "statement": "Acepto los términos.",
                      "statementSha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "documents": ["terms"],
                      "required": true,
                      "requiresReacceptance": false
                    }
                  ]
                }
                """;
    }
}
