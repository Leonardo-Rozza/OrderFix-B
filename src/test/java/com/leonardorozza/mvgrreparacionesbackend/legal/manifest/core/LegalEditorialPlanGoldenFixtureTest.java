package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanParser.ParsedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LegalEditorialPlanGoldenFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of(
            "$schema",
            "planSha256",
            "content",
            "contenidoMarkdown",
            "markdown",
            "path",
            "source",
            "secret",
            "credential",
            "password",
            "operator",
            "documentSetRevision");

    @TempDir
    Path temporaryDirectory;

    @Test
    void freezesReplaceAndRetireRawRfc8785BytesAndSha256() throws IOException {
        for (String fixtureName : List.of("replace-valid-v1", "retire-valid-v1")) {
            byte[] raw = fixture(fixtureName, "editorial-plan.json");
            byte[] expectedCanonical = withoutSingleTerminalLf(
                    fixture(fixtureName, "canonical.json"));
            String expectedSha = new String(
                    fixture(fixtureName, "sha256.txt"),
                    StandardCharsets.US_ASCII).strip();

            ParsedEditorialPlan parsed = new LegalEditorialPlanParser().parse(raw)
                    .value().orElseThrow();

            assertThat(parsed.canonicalJson().getBytes(StandardCharsets.UTF_8))
                    .as(fixtureName)
                    .containsExactly(expectedCanonical);
            assertThat(parsed.editorialPlanSha256()).isEqualTo(expectedSha);
            assertThat(expectedSha).isEqualTo(sha256(expectedCanonical));

            Path path = temporaryDirectory.toRealPath().resolve(fixtureName)
                    .resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
            Files.createDirectories(path.getParent());
            Files.write(path, raw);
            ValidatedEditorialPlan validated = new LegalEditorialPlanValidator().validate(path)
                    .value().orElseThrow();
            assertThat(validated.canonicalJson()).isEqualTo(parsed.canonicalJson());
            assertThat(validated.editorialPlanSha256()).isEqualTo(expectedSha);
        }
    }

    @Test
    void arrayPermutationChangesArtifactShaButNotTheValidatedExecutionSemantics()
            throws IOException {
        ObjectNode original = (ObjectNode) JSON.readTree(
                fixture("replace-valid-v1", "editorial-plan.json"));
        ObjectNode permuted = original.deepCopy();
        reverse((ArrayNode) permuted.path("documentReuses").get(0).path("contexts"));
        reverse((ArrayNode) permuted.path("documentReplacementBatches").get(0)
                .path("predecessors"));
        reverse((ArrayNode) permuted.path("requirementReuses").get(0).path("audiences"));

        byte[] originalBytes = JSON.writeValueAsBytes(original);
        byte[] permutedBytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(permuted);
        ParsedEditorialPlan originalParsed = new LegalEditorialPlanParser().parse(originalBytes)
                .value().orElseThrow();
        ParsedEditorialPlan permutedParsed = new LegalEditorialPlanParser().parse(permutedBytes)
                .value().orElseThrow();
        assertThat(originalParsed.editorialPlanSha256())
                .isNotEqualTo(permutedParsed.editorialPlanSha256());

        ValidatedEditorialPlan originalValidated = validate("original", originalBytes);
        ValidatedEditorialPlan permutedValidated = validate("permuted", permutedBytes);
        assertThat(originalValidated.plan()).isEqualTo(permutedValidated.plan());
        assertThat(originalValidated.editorialPlanSha256())
                .isNotEqualTo(permutedValidated.editorialPlanSha256());
    }

    @Test
    void fixturesContainOnlyTheFrozenOperationalVocabulary() throws IOException {
        for (String fixtureName : List.of("replace-valid-v1", "retire-valid-v1")) {
            JsonNode root = JSON.readTree(fixture(fixtureName, "editorial-plan.json"));
            assertThat(root).hasSize(18);
            Deque<JsonNode> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                JsonNode node = pending.removeFirst();
                if (node.isObject()) {
                    node.fields().forEachRemaining(field -> {
                        assertThat(field.getKey()).isNotIn(FORBIDDEN_FIELD_NAMES);
                        pending.addLast(field.getValue());
                    });
                } else if (node.isArray()) {
                    node.forEach(pending::addLast);
                }
            }
        }
    }

    private ValidatedEditorialPlan validate(String directory, byte[] raw) throws IOException {
        Path path = temporaryDirectory.toRealPath().resolve(directory)
                .resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
        Files.createDirectories(path.getParent());
        Files.write(path, raw);
        return new LegalEditorialPlanValidator().validate(path).value().orElseThrow();
    }

    private static void reverse(ArrayNode array) {
        List<JsonNode> values = new java.util.ArrayList<>();
        array.forEach(values::add);
        array.removeAll();
        for (int index = values.size() - 1; index >= 0; index--) {
            array.add(values.get(index));
        }
    }

    private static byte[] fixture(String directory, String filename) throws IOException {
        try (InputStream input = LegalEditorialPlanGoldenFixtureTest.class.getResourceAsStream(
                "/legal/editorial/" + directory + "/" + filename)) {
            assertThat(input).isNotNull();
            return input.readAllBytes();
        }
    }

    private static byte[] withoutSingleTerminalLf(byte[] bytes) {
        if (bytes.length > 0 && bytes[bytes.length - 1] == '\n') {
            return java.util.Arrays.copyOf(bytes, bytes.length - 1);
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
