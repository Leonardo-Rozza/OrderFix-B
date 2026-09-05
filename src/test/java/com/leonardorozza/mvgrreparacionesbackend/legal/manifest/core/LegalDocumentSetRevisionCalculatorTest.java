package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LegalDocumentSetRevisionCalculatorTest {

    private static final String FIXTURE_ROOT = "/legal/manifest/document-set-v1/";
    private static final String ALL_CONTEXTS_REVISION =
            "sha256:76420473f017cf5f0fad90635a166a0f3d449fe40c21367afa01302dc3137039";
    private static final String REGISTRATION_REVISION =
            "sha256:e9227403a61dce248feb37e7877177a953cc786c07dc692bdb86bbf88e92f214";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LegalDocumentSetRevisionCalculator calculator =
            new LegalDocumentSetRevisionCalculator();

    @ParameterizedTest(name = "document catalog golden: {0}")
    @ValueSource(strings = {"all-contexts", "registration"})
    void matchesFrozenCanonicalBytesAndRevisionUsingAnIndependentJcsOracle(String name)
            throws IOException {
        byte[] readableFixture = readFixture(name + "-projection.json");
        byte[] expectedCanonical = withoutSingleTerminalLf(
                readFixture(name + "-canonical.json"));
        String expectedRevision = new String(withoutSingleTerminalLf(
                readFixture(name + "-sha256.txt")), StandardCharsets.US_ASCII);
        String pinnedRevision = name.equals("all-contexts")
                ? ALL_CONTEXTS_REVISION : REGISTRATION_REVISION;

        assertThat(expectedRevision).isEqualTo(pinnedRevision);
        assertThat(expectedCanonical).containsExactly(new JsonCanonicalizer(
                new String(readableFixture, StandardCharsets.UTF_8)).getEncodedUTF8());
        assertThat(expectedRevision).isEqualTo("sha256:" + sha256(expectedCanonical));
        assertThat(calculator.canonicalUtf8(projectionFromFixture(name)))
                .containsExactly(expectedCanonical);
        assertThat(calculator.calculate(projectionFromFixture(name))).isEqualTo(pinnedRevision);
    }

    @Test
    void goldenRetainsPublicHistoryAndUnsignedUuidOrderAcrossBothHalves()
            throws IOException {
        List<LegalDocumentSummary> summaries = summariesFromFixture("all-contexts");
        JsonNode canonical = JSON.readTree(calculator.canonicalUtf8(
                projection(null, summaries)));

        assertThat(summaries).extracting(LegalDocumentSummary::state)
                .contains(EstadoVersionLegal.VIGENTE,
                        EstadoVersionLegal.REEMPLAZADA, EstadoVersionLegal.RETIRADA);
        assertThat(documentIds(canonical)).containsExactly(
                "00000000-0000-0000-7fff-ffffffffffff",
                "00000000-0000-0000-8000-000000000000",
                "7fffffff-ffff-ffff-ffff-ffffffffffff",
                "80000000-0000-0000-0000-000000000000",
                "ffffffff-ffff-ffff-ffff-ffffffffffff",
                "00000000-0000-0000-0000-000000000001");
        assertThat(canonical.path("documentos")).hasSize(6);
        assertThat(canonical.path("documentos").get(1).path("estado").textValue())
                .isEqualTo("REEMPLAZADA");
        assertThat(canonical.path("documentos").get(2).path("estado").textValue())
                .isEqualTo("RETIRADA");
        assertThat(canonical.path("documentos").get(4).path("vigenteDesde").textValue())
                .isEqualTo("2025-01-01T00:00:00Z");
        assertThat(canonical.path("documentos").get(5).path("tipo").textValue())
                .isEqualTo("POLITICA_PRIVACIDAD");
        assertThat(calculator.calculate(projection(null, summaries)))
                .isEqualTo(ALL_CONTEXTS_REVISION);
    }

    @Test
    void usesTheSameUtcInstantRenderingForWholeSecondsAndMicroseconds()
            throws IOException {
        JsonNode fixture = fixtureProjection("all-contexts");
        List<LegalDocumentSummary> summaries = summariesFromFixture("all-contexts");

        for (int index = 0; index < summaries.size(); index++) {
            assertThat(summaries.get(index).effectiveAtUtc())
                    .isEqualTo(fixture.path("documentos").get(index)
                            .path("vigenteDesde").textValue());
        }
        assertThat(summaries.getFirst().effectiveAtUtc())
                .isEqualTo("2026-09-05T12:34:56.123456Z");
        assertThat(summaries.get(4).effectiveAtUtc()).isEqualTo("2025-01-01T00:00:00Z");
    }

    @Test
    void everyChangeableSummaryFieldChangesTheTokenAndMatchesTheIndependentOracle()
            throws IOException {
        LegalDocumentSummary base = summariesFromFixture("all-contexts").getFirst();
        String baseline = calculator.calculate(projection(null, List.of(base)));
        Map<String, LegalDocumentSummary> mutations = new LinkedHashMap<>();
        mutations.put("id", new LegalDocumentSummary(
                UUID.fromString("80000000-0000-0000-0000-000000000042"),
                base.type(), base.version(), base.title(), base.sha256(),
                base.effectiveAt(), base.state(), base.locale()));
        mutations.put("tipo", new LegalDocumentSummary(
                base.versionId(), TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                base.version(), base.title(), base.sha256(),
                base.effectiveAt(), base.state(), base.locale()));
        mutations.put("version", new LegalDocumentSummary(
                base.versionId(), base.type(), "2026.2", base.title(), base.sha256(),
                base.effectiveAt(), base.state(), base.locale()));
        mutations.put("titulo", new LegalDocumentSummary(
                base.versionId(), base.type(), base.version(), "Otro título legal", base.sha256(),
                base.effectiveAt(), base.state(), base.locale()));
        mutations.put("sha256", new LegalDocumentSummary(
                base.versionId(), base.type(), base.version(), base.title(), "b".repeat(64),
                base.effectiveAt(), base.state(), base.locale()));
        mutations.put("vigenteDesde", new LegalDocumentSummary(
                base.versionId(), base.type(), base.version(), base.title(), base.sha256(),
                base.effectiveAt().plusNanos(1_000), base.state(), base.locale()));
        mutations.put("estado", new LegalDocumentSummary(
                base.versionId(), base.type(), base.version(), base.title(), base.sha256(),
                base.effectiveAt(), EstadoVersionLegal.REEMPLAZADA, base.locale()));

        for (Map.Entry<String, LegalDocumentSummary> mutation : mutations.entrySet()) {
            List<LegalDocumentSummary> documents = List.of(mutation.getValue());
            byte[] independentCanonical = independentCanonical(null, documents);
            assertThat(calculator.canonicalUtf8(projection(null, documents)))
                    .as("canonical field: %s", mutation.getKey())
                    .containsExactly(independentCanonical);
            assertThat(calculator.calculate(projection(null, documents)))
                    .as("revision field: %s", mutation.getKey())
                    .isEqualTo("sha256:" + sha256(independentCanonical))
                    .isNotEqualTo(baseline);
        }
    }

    @Test
    void contextIsHashInputAndNeverFiltersTheAlreadySelectedSummaries() throws IOException {
        List<LegalDocumentSummary> selected = summariesFromFixture("all-contexts");
        List<String> selectedIds = selected.stream()
                .map(summary -> summary.versionId().toString()).toList();
        Map<ContextoLegal, String> revisions = new java.util.EnumMap<>(ContextoLegal.class);

        for (ContextoLegal context : ContextoLegal.values()) {
            LegalDocumentCatalogProjection projection = projection(context, selected);
            assertThat(projection.context()).isEqualTo(context);
            assertThat(projection.locale()).isEqualTo(LocaleLegal.ES_AR);
            byte[] canonical = calculator.canonicalUtf8(projection);
            JsonNode parsed = JSON.readTree(canonical);
            assertThat(parsed.path("contexto").textValue()).isEqualTo(context.name());
            assertThat(documentIds(parsed)).containsExactlyElementsOf(selectedIds);
            assertThat(canonical).containsExactly(independentCanonical(context, selected));
            revisions.put(context, calculator.calculate(projection(context, selected)));
        }
        assertThat(revisions.values()).doesNotHaveDuplicates()
                .doesNotContain(ALL_CONTEXTS_REVISION);
        assertThat(revisions.get(ContextoLegal.REGISTRO)).isEqualTo(REGISTRATION_REVISION);
        LegalDocumentCatalogProjection unfiltered = projection(null, selected);
        assertThat(unfiltered.context()).isNull();
        assertThat(unfiltered.locale()).isEqualTo(LocaleLegal.ES_AR);
        assertThat(calculator.calculate(unfiltered)).isEqualTo(ALL_CONTEXTS_REVISION);
    }

    @Test
    void revisionHasExactlyTheFrozenDocumentFieldsAndNoPageMarkdownOrActorState()
            throws IOException {
        JsonNode root = JSON.readTree(calculator.canonicalUtf8(
                projectionFromFixture("all-contexts")));

        assertThat(fieldNames(root)).containsExactly("contexto", "documentos", "locale");
        assertThat(root.path("contexto").isNull()).isTrue();
        assertThat(root.path("locale").textValue()).isEqualTo("es-AR");
        for (JsonNode document : root.path("documentos")) {
            assertThat(fieldNames(document)).containsExactly(
                    "estado", "id", "locale", "sha256", "tipo", "titulo",
                    "version", "vigenteDesde");
            assertThat(document.path("locale").textValue()).isEqualTo("es-AR");
        }
        assertThat(Arrays.stream(LegalDocumentSummary.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("versionId", "type", "version", "title", "sha256",
                        "effectiveAt", "state", "locale");
        // ES_AR is the only representable locale in v1. Its presence at both levels is frozen;
        // testing an alternate locale token requires extending the supported product enum.
        assertThat(LocaleLegal.values()).containsExactly(LocaleLegal.ES_AR);
        assertThat(allFieldNames(root)).doesNotContain(
                "page", "size", "number", "totalElements", "totalPages",
                "documentSetRevision", "requiredSetRevision", "contenidoMarkdown", "href",
                "actor", "userId", "tallerId", "audiencia", "evidencia", "pendientes",
                "requisitos", "publicationId", "publicacionId", "contextos");
    }

    private static LegalDocumentCatalogProjection projection(
            ContextoLegal context, List<LegalDocumentSummary> summaries) {
        // Every calculation receives a fresh, single-use projection.
        return new LegalDocumentCatalogProjection(context, LocaleLegal.ES_AR, summaries.iterator());
    }

    private static LegalDocumentCatalogProjection projectionFromFixture(String name)
            throws IOException {
        JsonNode root = fixtureProjection(name);
        ContextoLegal context = root.path("contexto").isNull()
                ? null : ContextoLegal.valueOf(root.path("contexto").textValue());
        assertThat(root.path("locale").textValue()).isEqualTo("es-AR");
        return projection(context, summariesFromFixture(name));
    }

    private static List<LegalDocumentSummary> summariesFromFixture(String name)
            throws IOException {
        List<LegalDocumentSummary> summaries = new ArrayList<>();
        for (JsonNode document : fixtureProjection(name).path("documentos")) {
            assertThat(document.path("locale").textValue()).isEqualTo("es-AR");
            summaries.add(new LegalDocumentSummary(
                    UUID.fromString(document.path("id").textValue()),
                    TipoDocumentoLegal.valueOf(document.path("tipo").textValue()),
                    document.path("version").textValue(),
                    document.path("titulo").textValue(),
                    document.path("sha256").textValue(),
                    Instant.parse(document.path("vigenteDesde").textValue()),
                    EstadoVersionLegal.valueOf(document.path("estado").textValue()),
                    LocaleLegal.ES_AR));
        }
        return List.copyOf(summaries);
    }

    private static byte[] independentCanonical(
            ContextoLegal context, List<LegalDocumentSummary> summaries) throws IOException {
        // Build logical JSON in the test, deliberately outside canonical property order.
        // The oracle does not call the production canonicalizer or its date helper.
        ObjectNode root = JSON.createObjectNode();
        root.put("locale", "es-AR");
        ArrayNode documents = root.putArray("documentos");
        for (LegalDocumentSummary summary : summaries) {
            ObjectNode document = documents.addObject();
            document.put("version", summary.version());
            document.put("tipo", summary.type().name());
            document.put("titulo", summary.title());
            document.put("sha256", summary.sha256());
            document.put("locale", summary.locale().getCodigo());
            document.put("vigenteDesde", DateTimeFormatter.ISO_INSTANT.format(summary.effectiveAt()));
            document.put("id", summary.versionId().toString());
            document.put("estado", summary.state().name());
        }
        if (context == null) {
            root.putNull("contexto");
        } else {
            root.put("contexto", context.name());
        }
        return new JsonCanonicalizer(JSON.writeValueAsString(root)).getEncodedUTF8();
    }

    private static JsonNode fixtureProjection(String name) throws IOException {
        return JSON.readTree(readFixture(name + "-projection.json"));
    }

    private static List<String> documentIds(JsonNode root) {
        List<String> ids = new ArrayList<>();
        root.path("documentos").forEach(document -> ids.add(document.path("id").textValue()));
        return ids;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> allFieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                names.add(name);
                names.addAll(allFieldNames(node.get(name)));
            });
        } else if (node.isArray()) {
            node.forEach(child -> names.addAll(allFieldNames(child)));
        }
        return names;
    }

    private static byte[] readFixture(String name) throws IOException {
        try (InputStream resource = Objects.requireNonNull(
                LegalDocumentSetRevisionCalculatorTest.class.getResourceAsStream(FIXTURE_ROOT + name),
                name)) {
            return resource.readAllBytes();
        }
    }

    private static byte[] withoutSingleTerminalLf(byte[] bytes) {
        assertThat(bytes).isNotEmpty().doesNotContain((byte) '\r');
        assertThat(bytes[bytes.length - 1]).isEqualTo((byte) '\n');
        assertThat(bytes[bytes.length - 2]).isNotEqualTo((byte) '\n');
        return Arrays.copyOf(bytes, bytes.length - 1);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
