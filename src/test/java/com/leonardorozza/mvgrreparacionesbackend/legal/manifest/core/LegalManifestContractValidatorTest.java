package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.RequirementPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LegalManifestContractValidatorTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path temporaryDirectory;

    @Test
    void requiresBothProfessionalApprovalsAndPublicContactDomains() throws Exception {
        Path manifestPath = copyGoldenRelease();
        ObjectNode manifest = readManifest(manifestPath);
        ObjectNode legalReview = (ObjectNode) manifest.path("review").path("legal");
        legalReview.put("status", "PENDING");
        legalReview.remove("reference");
        legalReview.remove("reviewedAt");
        ((ObjectNode) manifest.path("publisherSnapshot").path("contacts"))
                .put("legalEmail", "legal@example.com");
        persist(manifestPath, manifest);

        LegalManifestValidation<LegalPublicationPlan> result = validateContract(manifestPath);

        assertBlockedWith(
                result,
                LegalManifestIssueCode.MANIFEST_CONTACTS_INVALID,
                LegalManifestIssueCode.PROFESSIONAL_REVIEW_REQUIRED);
    }

    @Test
    void rejectsEditorialMarkersInsideApprovedProfessionalReferences() throws Exception {
        Path manifestPath = copyGoldenRelease();
        ObjectNode manifest = readManifest(manifestPath);
        ((ObjectNode) manifest.path("review").path("accounting"))
                .put("reference", "BORRADOR");
        persist(manifestPath, manifest);

        LegalManifestValidation<LegalPublicationPlan> result = validateContract(manifestPath);

        assertBlockedWith(result, LegalManifestIssueCode.LEGAL_EDITORIAL_MARKER_FOUND);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::location)
                .contains("review/accounting/reference");
    }

    @Test
    void rejectsDuplicateDocumentAndRequirementIdentities() throws Exception {
        Path manifestPath = copyGoldenRelease();
        ObjectNode manifest = readManifest(manifestPath);
        ArrayNode documents = (ArrayNode) manifest.path("documents");
        ArrayNode requirements = (ArrayNode) manifest.path("requirements");
        ((ObjectNode) documents.get(1)).put("key", documents.get(0).path("key").textValue());
        ((ObjectNode) requirements.get(1)).put(
                "key",
                requirements.get(0).path("key").textValue());
        persist(manifestPath, manifest);

        LegalManifestValidation<LegalPublicationPlan> result = validateContract(manifestPath);

        assertBlockedWith(
                result,
                LegalManifestIssueCode.MANIFEST_DUPLICATE_DOCUMENT,
                LegalManifestIssueCode.MANIFEST_DUPLICATE_REQUIREMENT,
                LegalManifestIssueCode.REQUIREMENT_DOCUMENT_AMBIGUOUS,
                LegalManifestIssueCode.REQUIREMENT_DOCUMENT_UNKNOWN);
    }

    @Test
    void validatesStatementsReferencesContextsAndEditorialMarkersTogether() throws Exception {
        Path manifestPath = copyGoldenRelease();
        ObjectNode manifest = readManifest(manifestPath);
        ObjectNode registration = requirement(manifest, "admin-registration");
        String statement = "BORRADOR: confirmo el registro de OrdenFix.";
        registration.put("statement", statement);
        registration.put("statementSha256", sha256(statement));
        ((ArrayNode) registration.path("documents")).set(0, MAPPER.getNodeFactory()
                .textNode("documento-inexistente"));

        ObjectNode privacy = document(manifest, "privacidad");
        privacy.set("contexts", textArray("USO_CONTINUADO"));
        persist(manifestPath, manifest);

        LegalManifestValidation<LegalPublicationPlan> result = validateContract(manifestPath);

        assertBlockedWith(
                result,
                LegalManifestIssueCode.LEGAL_EDITORIAL_MARKER_FOUND,
                LegalManifestIssueCode.REQUIREMENT_DOCUMENT_UNKNOWN,
                LegalManifestIssueCode.REQUIREMENT_CONTEXT_MISMATCH,
                LegalManifestIssueCode.REQUIRED_REQUIREMENT_DOCUMENT_MISSING);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .doesNotContain(LegalManifestIssueCode.LEGAL_TEXT_DIGEST_MISMATCH);
    }

    @Test
    void rejectsOnlyCompletelyOrphanedDocuments() throws Exception {
        Path manifestPath = copyGoldenRelease();
        ObjectNode manifest = readManifest(manifestPath);
        ArrayNode registrationDocuments = (ArrayNode) requirement(
                manifest,
                "admin-registration").path("documents");
        registrationDocuments.remove(1);
        persist(manifestPath, manifest);

        LegalManifestValidation<LegalPublicationPlan> result = validateContract(manifestPath);

        assertBlockedWith(
                result,
                LegalManifestIssueCode.REQUIRED_DOCUMENT_UNBOUND,
                LegalManifestIssueCode.REQUIRED_REQUIREMENT_DOCUMENT_MISSING);
    }

    @Test
    void aggregatesARequiredScopeAcrossRequirementsAndKeepsGlobalOrdinals() throws Exception {
        Path manifestPath = copyGoldenRelease();
        ObjectNode manifest = readManifest(manifestPath);
        ArrayNode requirements = (ArrayNode) manifest.path("requirements");
        ObjectNode registration = requirement(manifest, "admin-registration");
        ((ArrayNode) registration.path("documents")).remove(2);

        ObjectNode dpaRequirement = registration.deepCopy();
        dpaRequirement.put("key", "admin-registration-dpa");
        String statement = "Confirmo el acuerdo de tratamiento de datos de OrdenFix.";
        dpaRequirement.put("statement", statement);
        dpaRequirement.put("statementSha256", sha256(statement));
        dpaRequirement.set("documents", textArray("tratamiento-datos"));
        requirements.add(dpaRequirement);
        persist(manifestPath, manifest);

        LegalManifestValidation<LegalPublicationPlan> result = validateContract(manifestPath);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        LegalPublicationPlan plan = result.value().orElseThrow();
        assertThat(plan.requirementCount()).isEqualTo(7);
        assertThat(plan.scopeCount()).isEqualTo(8);
        assertThat(plan.scopes().getFirst().requirements())
                .extracting(RequirementPlan::manifestOrdinal)
                .containsExactly(0, 6);
    }

    @Test
    void acceptsRepeatedDocumentTypesAndAnOptionalOnlyBinding() throws Exception {
        Path manifestPath = copyGoldenRelease();
        ObjectNode manifest = readManifest(manifestPath);
        String markdown = "# Términos opcionales\n\nTexto complementario final de OrdenFix.\n";
        Files.writeString(
                manifestPath.getParent().resolve("terminos-opcional.md"),
                markdown,
                StandardCharsets.UTF_8);

        ObjectNode optionalDocument = document(manifest, "terminos").deepCopy();
        optionalDocument.put("key", "terminos-opcional");
        optionalDocument.put("source", "terminos-opcional.md");
        optionalDocument.put("sha256", sha256(markdown));
        optionalDocument.set("contexts", textArray("USO_CONTINUADO"));
        ((ArrayNode) manifest.path("documents")).add(optionalDocument);

        ObjectNode optionalRequirement = requirement(
                manifest,
                "admin-registration").deepCopy();
        optionalRequirement.put("key", "optional-continuous-reading");
        optionalRequirement.put("context", "USO_CONTINUADO");
        optionalRequirement.set("roles", textArray("ADMIN_TITULAR"));
        optionalRequirement.put("actType", "LECTURA");
        String statement = "Declaro la lectura opcional de términos complementarios.";
        optionalRequirement.put("statement", statement);
        optionalRequirement.put("statementSha256", sha256(statement));
        optionalRequirement.set("documents", textArray("terminos-opcional"));
        optionalRequirement.put("required", false);
        ((ArrayNode) manifest.path("requirements")).add(optionalRequirement);
        persist(manifestPath, manifest);

        LegalManifestValidation<LegalPublicationPlan> result = validateContract(manifestPath);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        LegalPublicationPlan plan = result.value().orElseThrow();
        assertThat(plan.documentCount()).isEqualTo(12);
        assertThat(plan.requirementCount()).isEqualTo(7);
        assertThat(plan.scopeCount()).isEqualTo(9);
        assertThat(plan.documentByKey("terminos-opcional")).isPresent();
    }

    @Test
    void validatesPublisherPlaceholdersAndExactDocumentDigests() throws Exception {
        Path manifestPath = copyGoldenRelease();
        ObjectNode manifest = readManifest(manifestPath);
        ((ObjectNode) manifest.path("publisherSnapshot"))
                .put("legalName", "[RAZÓN SOCIAL]");
        persist(manifestPath, manifest);
        Files.writeString(
                manifestPath.getParent().resolve("terminos.md"),
                "# términos\n\nContenido alterado.\n",
                StandardCharsets.UTF_8);

        LegalManifestValidation<LegalPublicationPlan> result = validateContract(manifestPath);

        assertBlockedWith(
                result,
                LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND,
                LegalManifestIssueCode.LEGAL_TEXT_DIGEST_MISMATCH);
    }

    @Test
    void rejectsVisuallyEmptyPublisherFieldsAndRequirementStatements() throws Exception {
        Path manifestPath = copyGoldenRelease();
        ObjectNode manifest = readManifest(manifestPath);
        ObjectNode publisher = (ObjectNode) manifest.path("publisherSnapshot");
        publisher.put("legalName", "\u200B\u2060");
        publisher.put("legalAddress", "\u200B".repeat(5));
        publisher.put("jurisdiction", "\u200E\u200F");
        publisher.put("businessHours", "\u2060\uFEFF");

        ObjectNode registration = requirement(manifest, "admin-registration");
        String invisibleStatement = "\uFE0F\u034F";
        registration.put("statement", invisibleStatement);
        registration.put("statementSha256", sha256(invisibleStatement));
        persist(manifestPath, manifest);

        LegalManifestValidation<LegalPublicationPlan> result = validateContract(manifestPath);

        assertBlockedWith(result, LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID);
        assertThat(result.issues())
                .filteredOn(issue -> issue.code() == LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID)
                .extracting(LegalManifestIssue::location)
                .contains(
                        "publisherSnapshot/legalName",
                        "publisherSnapshot/legalAddress",
                        "publisherSnapshot/jurisdiction",
                        "publisherSnapshot/businessHours",
                        "requirements/0/statement");
    }

    @Test
    void rejectsControlsEmbeddedInOtherwiseVisiblePublisherFields() throws Exception {
        Path manifestPath = copyGoldenRelease();
        ObjectNode manifest = readManifest(manifestPath);
        String nul = Character.toString(0);
        ((ObjectNode) manifest.path("publisherSnapshot"))
                .put("legalName", "Orden" + nul + "Fix");
        persist(manifestPath, manifest);

        LegalManifestValidation<LegalPublicationPlan> result = validateContract(manifestPath);

        assertBlockedWith(result, LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID);
        assertThat(result.issues())
                .filteredOn(issue -> issue.code() == LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID)
                .extracting(LegalManifestIssue::location)
                .contains("publisherSnapshot/legalName");
    }

    @Test
    void mirrorsTheFrontendPublicEmailPolicyWithoutDependingOnEnvironmentVariables() {
        assertThat(LegalManifestContractValidator.isPublicEmail("legal@ordenfix.com")).isTrue();
        assertThat(LegalManifestContractValidator.isPublicEmail("legal@sub.ordenfix.com.ar"))
                .isTrue();
        assertThat(LegalManifestContractValidator.isPublicEmail("legal@example.com")).isFalse();
        assertThat(LegalManifestContractValidator.isPublicEmail("legal@localhost")).isFalse();
        assertThat(LegalManifestContractValidator.isPublicEmail("legal@ordenfix.local")).isFalse();
        assertThat(LegalManifestContractValidator.isPublicEmail("legal@127.0.0.1")).isFalse();
        assertThat(LegalManifestContractValidator.isPublicEmail(".legal@ordenfix.com")).isFalse();
        assertThat(LegalManifestContractValidator.isPublicEmail("le..gal@ordenfix.com")).isFalse();
    }

    private LegalManifestValidation<LegalPublicationPlan> validateContract(Path manifestPath) {
        ConfinedReleaseReader reader = new ConfinedReleaseReader();
        ConfinedReleaseReader.ManifestSource source = requirePass(
                reader.readManifest(manifestPath));
        LegalManifestParser.ParsedManifest parsed = requirePass(
                new LegalManifestParser().parse(source.bytes()));
        ConfinedReleaseReader.ReleaseDocuments documents = requirePass(
                reader.readDocuments(source, parsed.manifest()));
        return new LegalManifestContractValidator().validate(parsed, documents);
    }

    private Path copyGoldenRelease() throws IOException, URISyntaxException {
        Path sourceRoot = goldenManifest().getParent();
        Path releaseRoot = temporaryDirectory.resolve("release-valid-v1");
        Files.createDirectories(releaseRoot);
        try (Stream<Path> entries = Files.list(sourceRoot)) {
            for (Path source : entries.toList()) {
                Files.copy(
                        source,
                        releaseRoot.resolve(source.getFileName().toString()),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        return releaseRoot.resolve(ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    private Path goldenManifest() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                getClass().getResource(GOLDEN_MANIFEST)).toURI());
    }

    private static ObjectNode readManifest(Path manifestPath) throws IOException {
        return (ObjectNode) MAPPER.readTree(Files.readAllBytes(manifestPath));
    }

    private static void persist(Path manifestPath, ObjectNode manifest) throws IOException {
        Files.write(
                manifestPath,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
    }

    private static ObjectNode document(ObjectNode manifest, String key) {
        for (var candidate : manifest.path("documents")) {
            if (key.equals(candidate.path("key").textValue())) {
                return (ObjectNode) candidate;
            }
        }
        throw new IllegalArgumentException("Documento de test inexistente");
    }

    private static ObjectNode requirement(ObjectNode manifest, String key) {
        for (var candidate : manifest.path("requirements")) {
            if (key.equals(candidate.path("key").textValue())) {
                return (ObjectNode) candidate;
            }
        }
        throw new IllegalArgumentException("Requisito de test inexistente");
    }

    private static ArrayNode textArray(String... values) {
        ArrayNode array = MAPPER.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible en el test", exception);
        }
    }

    private static <T> T requirePass(LegalManifestValidation<T> validation) {
        assertThat(validation.passed())
                .as("issues=%s", validation.issues())
                .isTrue();
        return validation.value().orElseThrow();
    }

    private static void assertBlockedWith(
            LegalManifestValidation<?> result,
            LegalManifestIssueCode... expectedCodes) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(expectedCodes);
    }
}
