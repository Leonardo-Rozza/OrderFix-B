package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestParser.ParsedManifest;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewStatus;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class LegalManifestParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LegalManifestParser parser = new LegalManifestParser();

    @Test
    void returnsTheTypedManifestCanonicalJsonAndItsSha256() {
        LegalManifestValidation<ParsedManifest> result = parse(validManifest());

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.issues()).isEmpty();
        ParsedManifest parsed = result.value().orElseThrow();
        var manifest = parsed.manifest();

        assertThat(manifest.$schema())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(manifest.schemaVersion()).isEqualTo(1);
        assertThat(manifest.publicationId()).isEqualTo("release-parser-v1");
        assertThat(manifest.locale()).isEqualTo(LocaleLegal.ES_AR);
        assertThat(manifest.publisherSnapshot().contacts().privacyEmail())
                .isEqualTo("privacidad@ordenfix.com");
        assertThat(manifest.review().legal().status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(manifest.review().legal().reviewedAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-20T12:00:00-03:00"));
        assertThat(manifest.review().accounting().reference()).isNull();
        assertThat(manifest.documents().getFirst().type())
                .isEqualTo(TipoDocumentoLegal.POLITICA_PRIVACIDAD);
        assertThat(manifest.requirements().getFirst().actType())
                .isEqualTo(TipoActoLegal.ACEPTACION);

        assertThat(parsed.canonicalJson()).doesNotContain("\n", "  ");
        assertThat(parsed.manifestSha256())
                .isEqualTo(sha256(parsed.canonicalJson().getBytes(StandardCharsets.UTF_8)))
                .matches("[a-f0-9]{64}");
    }

    @Test
    void rejectsFutureUnknownAndInvalidFormatInputsAtTheSchemaBoundary() {
        String valid = validManifest();
        List<String> invalidManifests = List.of(
                valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                valid.replace("{\n", "{\n  \"unknown\": true,\n"),
                valid.replace("legal@ordenfix.com", "not-an-email"));

        for (String invalid : invalidManifests) {
            LegalManifestValidation<ParsedManifest> result = parse(invalid);

            assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
            assertThat(result.value()).isEmpty();
            assertThat(result.issues())
                    .extracting(LegalManifestIssue::code)
                    .containsExactly(LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID);
            assertThat(result.issues())
                    .extracting(LegalManifestIssue::location)
                    .containsOnly(StrictJsonReader.DEFAULT_LOCATION);
        }
    }

    @Test
    void rejectsHostileOneOfAndIfThenElseShapesAtTheSchemaBoundary() {
        String valid = validManifest();

        assertSchemaBlocked(
                "oneOf rejects an unknown placeholder",
                valid.replace(
                        "\"legalName\": \"OrdenFix Argentina SAS\"",
                        "\"legalName\": \"[OTRO PLACEHOLDER]\""));
        assertSchemaBlocked(
                "then requires reference for APPROVED",
                valid.replace(
                        "\"reference\": \"LEGAL-2026-001\",\n",
                        ""));
        assertSchemaBlocked(
                "else forbids reference for PENDING",
                valid.replace(
                        "\"accounting\": {\"status\": \"PENDING\"}",
                        "\"accounting\": {\"status\": \"PENDING\", "
                                + "\"reference\": \"ACC-2026-001\"}"));
    }

    @Test
    void rejectsDuplicateArrayItemsAndInvalidDateTimesAtTheSchemaBoundary() {
        String valid = validManifest();

        assertSchemaBlocked(
                "uniqueItems rejects duplicate contexts",
                valid.replace(
                        "[\"REGISTRO\", \"USO_CONTINUADO\"]",
                        "[\"REGISTRO\", \"REGISTRO\"]"));
        assertSchemaBlocked(
                "uniqueItems rejects duplicate roles",
                valid.replace(
                        "[\"USER\", \"ADMIN_TITULAR\"]",
                        "[\"USER\", \"USER\"]"));
        assertSchemaBlocked(
                "uniqueItems rejects duplicate document references",
                valid.replace(
                        "[\"terms\", \"privacy\"]",
                        "[\"terms\", \"terms\"]"));
        assertSchemaBlocked(
                "date-time rejects an impossible calendar date",
                valid.replace(
                        "2026-09-01T00:00:00-03:00",
                        "2026-02-30T00:00:00-03:00"));
    }

    @Test
    void rejectsDangerousUnicodeWhitespaceAndControlsInEmailsAtTheSchemaBoundary() {
        String valid = validManifest();

        assertAll(
                () -> assertSchemaBlocked(
                        "email rejects U+00A0",
                        valid.replace("legal@ordenfix.com", "legal\u00A0@ordenfix.com")),
                () -> assertSchemaBlocked(
                        "email rejects U+2007",
                        valid.replace("legal@ordenfix.com", "legal\u2007@ordenfix.com")),
                () -> assertSchemaBlocked(
                        "email rejects U+202F",
                        valid.replace("legal@ordenfix.com", "legal\u202F@ordenfix.com")),
                () -> assertSchemaBlocked(
                        "email rejects escaped U+0001",
                        valid.replace(
                                "legal@ordenfix.com",
                                "legal" + unicodeEscape("0001") + "@ordenfix.com")));
    }

    @Test
    void parserBlocksCollectionsAboveTheFrozenSchemaMaxima()
            throws JsonProcessingException {
        assertSchemaBlocked(
                "129 documents",
                manifestWithDocumentCount(129));
        assertSchemaBlocked(
                "257 requirements",
                manifestWithRequirementCount(257));
        assertSchemaBlocked(
                "17 document references per requirement",
                manifestWithRequirementReferenceCount(17));
    }

    @Test
    void rejectsDuplicateFieldsBeforeSchemaAndCanonicalization() {
        String duplicate = validManifest().replace(
                "\"schemaVersion\": 1,",
                "\"schemaVersion\": 1,\n  \"schemaVersion\": 1,");

        LegalManifestValidation<ParsedManifest> result = parse(duplicate);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.MANIFEST_JSON_INVALID);
    }

    @Test
    void blocksUnsupportedManifestAndDocumentLocalesBeforeModelMapping() {
        String valid = validManifest();
        String unsupportedManifestLocale = valid.replace(
                "\"locale\": \"es-AR\",\n  \"publisherSnapshot\"",
                "\"locale\": \"en-US\",\n  \"publisherSnapshot\"");
        String unsupportedDocumentLocale = valid.replace(
                "\"locale\": \"es-AR\",\n      \"source\": \"legal/privacy.md\"",
                "\"locale\": \"en-US\",\n      \"source\": \"legal/privacy.md\"");

        for (String unsupported : List.of(
                unsupportedManifestLocale,
                unsupportedDocumentLocale)) {
            LegalManifestValidation<ParsedManifest> result = parse(unsupported);

            assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
            assertThat(result.value()).isEmpty();
            assertThat(result.issues())
                    .extracting(LegalManifestIssue::code)
                    .containsExactly(LegalManifestIssueCode.MANIFEST_LOCALE_UNSUPPORTED)
                    .doesNotContain(LegalManifestIssueCode.MANIFEST_MODEL_MAPPING_ERROR);
        }
    }

    @Test
    void preservesEveryDeclaredArrayOrderWhileMapping() {
        ParsedManifest parsed = parse(validManifest()).value().orElseThrow();
        var manifest = parsed.manifest();

        assertThat(manifest.documents())
                .extracting(entry -> entry.key())
                .containsExactly("privacy", "terms");
        assertThat(manifest.documents().getFirst().contexts())
                .containsExactly(ContextoLegal.REGISTRO, ContextoLegal.USO_CONTINUADO);
        assertThat(manifest.requirements())
                .extracting(entry -> entry.key())
                .containsExactly("pro-terms", "registration");
        assertThat(manifest.requirements().getFirst().roles())
                .containsExactly(AudienciaLegal.USER, AudienciaLegal.ADMIN_TITULAR);
        assertThat(manifest.requirements().getFirst().documents())
                .containsExactly("terms", "privacy");
    }

    @Test
    void acceptsTheOptionalSchemaPropertyWhenAbsent() {
        String withoutSchema = validManifest().replace(
                "  \"$schema\": \"https://json-schema.org/draft/2020-12/schema\",\n",
                "");

        LegalManifestValidation<ParsedManifest> result = parse(withoutSchema);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        ParsedManifest parsed = result.value().orElseThrow();
        assertThat(parsed.manifest().$schema()).isNull();
        assertThat(parsed.canonicalJson()).doesNotContain("\"$schema\"");
    }

    private LegalManifestValidation<ParsedManifest> parse(String manifest) {
        return parser.parse(manifest.getBytes(StandardCharsets.UTF_8));
    }

    private void assertSchemaBlocked(String caseName, String manifest) {
        LegalManifestValidation<ParsedManifest> result = parse(manifest);

        assertThat(result.status()).as(caseName).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).as(caseName).isEmpty();
        assertThat(result.issues())
                .as(caseName)
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID);
        assertThat(result.issues())
                .as(caseName)
                .extracting(LegalManifestIssue::location)
                .containsExactly(StrictJsonReader.DEFAULT_LOCATION);
    }

    private static String manifestWithDocumentCount(int count) throws JsonProcessingException {
        ObjectNode manifest = mutableValidManifest();
        ObjectNode template = (ObjectNode) manifest.path("documents").get(0);
        ArrayNode documents = manifest.putArray("documents");
        for (int index = 0; index < count; index++) {
            ObjectNode document = template.deepCopy();
            document.put("key", "document-" + index);
            document.put("source", "legal/document-" + index + ".md");
            document.put("sha256", "%064x".formatted(index));
            documents.add(document);
        }
        return JSON.writeValueAsString(manifest);
    }

    private static String manifestWithRequirementCount(int count)
            throws JsonProcessingException {
        ObjectNode manifest = mutableValidManifest();
        ObjectNode template = (ObjectNode) manifest.path("requirements").get(0);
        ArrayNode requirements = manifest.putArray("requirements");
        for (int index = 0; index < count; index++) {
            ObjectNode requirement = template.deepCopy();
            requirement.put("key", "requirement-" + index);
            requirement.put("statement", "Acepto el requisito " + index + '.');
            requirement.put("statementSha256", "%064x".formatted(index));
            requirements.add(requirement);
        }
        return JSON.writeValueAsString(manifest);
    }

    private static String manifestWithRequirementReferenceCount(int count)
            throws JsonProcessingException {
        ObjectNode manifest = mutableValidManifest();
        ArrayNode references = (ArrayNode) manifest.path("requirements")
                .get(0)
                .path("documents");
        references.removeAll();
        for (int index = 0; index < count; index++) {
            references.add("document-" + index);
        }
        return JSON.writeValueAsString(manifest);
    }

    private static ObjectNode mutableValidManifest() throws JsonProcessingException {
        return (ObjectNode) JSON.readTree(validManifest());
    }

    private static String unicodeEscape(String hexadecimalCodeUnit) {
        return "\\" + "u" + hexadecimalCodeUnit;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String validManifest() {
        return """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "schemaVersion": 1,
                  "publicationId": "release-parser-v1",
                  "locale": "es-AR",
                  "publisherSnapshot": {
                    "legalName": "OrdenFix Argentina SAS",
                    "taxId": "30-12345678-9",
                    "legalAddress": "Calle Pública 100, CABA",
                    "jurisdiction": "Ciudad Autónoma de Buenos Aires",
                    "businessHours": "Lunes a viernes de 9 a 17 h",
                    "contacts": {
                      "legalEmail": "legal@ordenfix.com",
                      "privacyEmail": "privacidad@ordenfix.com",
                      "supportEmail": "soporte@ordenfix.com"
                    }
                  },
                  "review": {
                    "legal": {
                      "status": "APPROVED",
                      "reference": "LEGAL-2026-001",
                      "reviewedAt": "2026-08-20T12:00:00-03:00"
                    },
                    "accounting": {"status": "PENDING"}
                  },
                  "documents": [
                    {
                      "key": "privacy",
                      "type": "POLITICA_PRIVACIDAD",
                      "version": "1.0.0",
                      "locale": "es-AR",
                      "source": "legal/privacy.md",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "effectiveAt": "2026-09-01T00:00:00-03:00",
                      "contexts": ["REGISTRO", "USO_CONTINUADO"],
                      "requiresReacceptance": true
                    },
                    {
                      "key": "terms",
                      "type": "TERMINOS_SERVICIO",
                      "version": "1.0.0",
                      "locale": "es-AR",
                      "source": "legal/terms.md",
                      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "effectiveAt": "2026-09-01T00:00:00-03:00",
                      "contexts": ["CONTRATACION_PRO", "REGISTRO"],
                      "requiresReacceptance": false
                    }
                  ],
                  "requirements": [
                    {
                      "key": "pro-terms",
                      "version": "1.0.0",
                      "context": "CONTRATACION_PRO",
                      "roles": ["USER", "ADMIN_TITULAR"],
                      "actType": "ACEPTACION",
                      "statement": "Acepto las condiciones Pro.",
                      "statementSha256": "1111111111111111111111111111111111111111111111111111111111111111",
                      "documents": ["terms", "privacy"],
                      "required": true,
                      "requiresReacceptance": true
                    },
                    {
                      "key": "registration",
                      "version": "1.0.0",
                      "context": "REGISTRO",
                      "roles": ["ADMIN_TITULAR"],
                      "actType": "LECTURA",
                      "statement": "Leí la política de privacidad.",
                      "statementSha256": "2222222222222222222222222222222222222222222222222222222222222222",
                      "documents": ["privacy"],
                      "required": true,
                      "requiresReacceptance": false
                    }
                  ]
                }
                """;
    }
}
