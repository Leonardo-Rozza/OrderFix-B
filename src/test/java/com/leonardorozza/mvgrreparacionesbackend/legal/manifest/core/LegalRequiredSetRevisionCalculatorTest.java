package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalRequiredSetRevisionCalculatorTest {

    private static final String FIXTURE_ROOT =
            "/legal/manifest/required-set-revision-v1/";

    private final LegalRequiredSetRevisionCalculator calculator =
            new LegalRequiredSetRevisionCalculator();

    @Test
    void matchesTheReadableProjectionCanonicalBytesAndRevisionGolden() {
        LegalRequiredSetProjection projection = goldenProjection();

        byte[] projectionFixture = readFixture("projection.json");
        String readableProjection = new String(projectionFixture, StandardCharsets.UTF_8);
        assertThat(readableProjection)
                .startsWith("{\n")
                .contains("\n  \"contexto\": \"REGISTRO\"")
                .endsWith("\n");
        assertThat(projectionFixture).doesNotContain((byte) '\r');

        JsonNode expectedProjection = parseStrictJson(projectionFixture);
        JsonNode actualProjection = parseStrictJson(
                calculator.projectionJson(projection).getBytes(StandardCharsets.UTF_8));
        assertThat(actualProjection).isEqualTo(expectedProjection);
        assertProjectionContract(actualProjection);

        byte[] expectedCanonical = withoutSingleTerminalLf(readFixture("canonical.json"));
        assertThat(calculator.canonicalUtf8(projection)).containsExactly(expectedCanonical);

        String expectedRevision = new String(
                withoutSingleTerminalLf(readFixture("sha256.txt")),
                StandardCharsets.UTF_8);
        assertThat(expectedRevision).matches("sha256:[0-9a-f]{64}");
        assertThat(calculator.calculate(projection)).isEqualTo(expectedRevision);
    }

    @Test
    void equivalentOffsetProducesTheSameRevision() {
        LegalRequiredSetProjection baseline = goldenProjection();
        LegalRequiredSetProjection equivalentUtc = replaceFirstDocument(
                baseline,
                copyDocument(
                        firstDocument(baseline),
                        firstDocument(baseline).versionId(),
                        firstDocument(baseline).markdown(),
                        firstDocument(baseline).sha256(),
                        OffsetDateTime.parse("2026-09-01T03:00:00.123456Z")));

        assertThat(calculator.calculate(equivalentUtc))
                .isEqualTo(calculator.calculate(baseline));
    }

    @Test
    void rejectsPrecisionBeyondPostgresMicroseconds() {
        LegalRequiredSetProjection baseline = goldenProjection();
        DocumentProjection document = firstDocument(baseline);
        LegalRequiredSetProjection nanosecondPrecision = replaceFirstDocument(
                baseline,
                copyDocument(
                        document,
                        document.versionId(),
                        document.markdown(),
                        document.sha256(),
                        OffsetDateTime.parse("2026-09-01T03:00:00.123456001Z")));

        assertThatThrownBy(() -> calculator.calculate(nanosecondPrecision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("microsegundos");
    }

    @Test
    void rejectsExpandedMarkdownBeyondSixteenMiBUsingOneSharedLargeString() {
        String sharedMarkdown = "a".repeat(LegalManifestLimits.MAX_MARKDOWN_BYTES);
        DocumentProjection sharedDocument = copyDocument(
                firstDocument(goldenProjection()),
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                sharedMarkdown,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                OffsetDateTime.parse("2026-09-01T03:00:00Z"));
        RequirementProjection repeatedRequirement = new RequirementProjection(
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                ContextoLegal.REGISTRO,
                TipoActoLegal.LECTURA,
                "Declaro la lectura del documento de capacidad.",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                List.of(sharedDocument),
                true);
        LegalRequiredSetProjection oversized = new LegalRequiredSetProjection(
                ContextoLegal.REGISTRO,
                LocaleLegal.ES_AR,
                Collections.nCopies(17, repeatedRequirement));

        assertThat(oversized.requirements())
                .allMatch(requirement -> requirement == repeatedRequirement);
        assertThat(oversized.requirements())
                .allSatisfy(requirement -> assertThat(requirement.documents().getFirst().markdown())
                        .isSameAs(sharedMarkdown));
        assertThatThrownBy(() -> calculator.calculate(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("límite Markdown del scope");
    }

    @Test
    void hashesSixteenMiBOfEscapableMarkdownWithoutMaterializingTheCanonicalJson() {
        String sharedMarkdown = "\\".repeat(LegalManifestLimits.MAX_MARKDOWN_BYTES);
        DocumentProjection sharedDocument = copyDocument(
                firstDocument(goldenProjection()),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                sharedMarkdown,
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                OffsetDateTime.parse("2026-09-01T03:00:00Z"));
        List<RequirementProjection> requirements = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            requirements.add(new RequirementProjection(
                    new UUID(0L, index + 1L),
                    ContextoLegal.REGISTRO,
                    TipoActoLegal.LECTURA,
                    "Declaro la lectura del documento de capacidad " + index + ".",
                    "f".repeat(64),
                    List.of(sharedDocument),
                    true));
        }
        LegalRequiredSetProjection maximumEscaped = new LegalRequiredSetProjection(
                ContextoLegal.REGISTRO,
                LocaleLegal.ES_AR,
                requirements);

        assertThat(maximumEscaped.requirements())
                .allSatisfy(requirement -> assertThat(requirement.documents().getFirst().markdown())
                        .isSameAs(sharedMarkdown));
        assertThat(calculator.calculate(maximumEscaped)).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void streamingWriterMatchesJcsForEscapesControlsAndAstralUnicode() throws IOException {
        LegalRequiredSetProjection baseline = goldenProjection();
        RequirementProjection requirement = baseline.requirements().getFirst();
        DocumentProjection document = requirement.documents().getFirst();
        DocumentProjection escapedDocument = new DocumentProjection(
                document.versionId(),
                document.type(),
                document.version(),
                "Título \"citado\" \\ / 😀",
                "# Título\n\n\b\t\f\r\u000f \"cita\" \\ / 😀\n",
                document.sha256(),
                document.effectiveAt(),
                document.locale());
        LegalRequiredSetProjection escaped = replaceFirstRequirement(
                baseline,
                copyRequirement(
                        requirement,
                        requirement.versionId(),
                        "Afirmación \"cita\" \\ / 😀.",
                        requirement.statementSha256(),
                        List.of(escapedDocument, requirement.documents().get(1)),
                        requirement.required()));

        byte[] expected = new JsonCanonicalizer(
                calculator.projectionJson(escaped)).getEncodedUTF8();

        assertThat(calculator.canonicalUtf8(escaped)).containsExactly(expected);
    }

    @Test
    void rejectsUnboundedNonMarkdownFieldsAtTheTypedBoundary() {
        LegalRequiredSetProjection baseline = goldenProjection();
        RequirementProjection requirement = baseline.requirements().getFirst();
        DocumentProjection document = requirement.documents().getFirst();

        assertThatThrownBy(() -> copyRequirement(
                requirement,
                requirement.versionId(),
                "a".repeat(1_001),
                requirement.statementSha256(),
                requirement.documents(),
                requirement.required()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentProjection(
                document.versionId(),
                document.type(),
                "v".repeat(41),
                document.title(),
                document.markdown(),
                document.sha256(),
                document.effectiveAt(),
                document.locale()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentProjection(
                document.versionId(),
                document.type(),
                document.version(),
                "t".repeat(301),
                document.markdown(),
                document.sha256(),
                document.effectiveAt(),
                document.locale()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentProjection(
                document.versionId(),
                document.type(),
                document.version(),
                document.title(),
                document.markdown(),
                "f".repeat(63),
                document.effectiveAt(),
                document.locale()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalRequiredSetProjection(
                baseline.context(),
                baseline.locale(),
                Collections.nCopies(Integer.MAX_VALUE, requirement)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequirementProjection(
                requirement.versionId(),
                requirement.context(),
                requirement.actType(),
                requirement.statement(),
                requirement.statementSha256(),
                Collections.nCopies(Integer.MAX_VALUE, document),
                requirement.required()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyIndependentContractMutationChangesTheRevision() {
        LegalRequiredSetProjection baseline = goldenProjection();
        RequirementProjection requirement = baseline.requirements().getFirst();
        DocumentProjection document = requirement.documents().getFirst();

        Map<String, LegalRequiredSetProjection> mutations = new LinkedHashMap<>();
        mutations.put("UUID", replaceFirstRequirement(
                baseline,
                copyRequirement(
                        requirement,
                        UUID.fromString("00000000-0000-0000-0000-0000000000c3"),
                        requirement.statement(),
                        requirement.statementSha256(),
                        requirement.documents(),
                        requirement.required())));
        mutations.put("Markdown", replaceFirstDocument(
                baseline,
                copyDocument(
                        document,
                        document.versionId(),
                        document.markdown() + "Cambio independiente.\n",
                        document.sha256(),
                        document.effectiveAt())));
        mutations.put("digest", replaceFirstDocument(
                baseline,
                copyDocument(
                        document,
                        document.versionId(),
                        document.markdown(),
                        "3333333333333333333333333333333333333333333333333333333333333333",
                        document.effectiveAt())));
        mutations.put("afirmación", replaceFirstRequirement(
                baseline,
                copyRequirement(
                        requirement,
                        requirement.versionId(),
                        requirement.statement() + " Actualizada.",
                        requirement.statementSha256(),
                        requirement.documents(),
                        requirement.required())));
        mutations.put("requerido", replaceFirstRequirement(
                baseline,
                copyRequirement(
                        requirement,
                        requirement.versionId(),
                        requirement.statement(),
                        requirement.statementSha256(),
                        requirement.documents(),
                        !requirement.required())));
        mutations.put("timestamp", replaceFirstDocument(
                baseline,
                copyDocument(
                        document,
                        document.versionId(),
                        document.markdown(),
                        document.sha256(),
                        document.effectiveAt().plusNanos(1_000))));
        mutations.put("membresía documental", replaceFirstRequirement(
                baseline,
                copyRequirement(
                        requirement,
                        requirement.versionId(),
                        requirement.statement(),
                        requirement.statementSha256(),
                        List.of(requirement.documents().getFirst()),
                        requirement.required())));
        mutations.put("orden documental", replaceFirstRequirement(
                baseline,
                copyRequirement(
                        requirement,
                        requirement.versionId(),
                        requirement.statement(),
                        requirement.statementSha256(),
                        List.of(requirement.documents().get(1), requirement.documents().get(0)),
                        requirement.required())));

        String baselineRevision = calculator.calculate(baseline);
        mutations.forEach((name, mutation) -> assertThat(calculator.calculate(mutation))
                .as(name)
                .isNotEqualTo(baselineRevision));
    }

    private static LegalRequiredSetProjection goldenProjection() {
        DocumentProjection privacy = new DocumentProjection(
                UUID.fromString("10000000-0000-0000-0000-000000000002"),
                TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                "1.0.0",
                "Privacidad",
                "# Privacidad\n\nDatos y derechos.\n",
                "2222222222222222222222222222222222222222222222222222222222222222",
                OffsetDateTime.parse("2026-09-01T00:00:00.123456-03:00"),
                LocaleLegal.ES_AR);
        DocumentProjection terms = new DocumentProjection(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                "1.0.0",
                "Términos y condiciones",
                "# Términos\n\nContenido legal.\n",
                "1111111111111111111111111111111111111111111111111111111111111111",
                OffsetDateTime.parse("2026-09-01T03:00:00Z"),
                LocaleLegal.ES_AR);

        RequirementProjection acceptedTerms = new RequirementProjection(
                UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
                ContextoLegal.REGISTRO,
                TipoActoLegal.ACEPTACION,
                "Acepto los términos de OrdenFix.",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                List.of(privacy, terms),
                true);
        RequirementProjection privacyNotice = new RequirementProjection(
                UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                ContextoLegal.REGISTRO,
                TipoActoLegal.LECTURA,
                "Declaro haber leído el aviso.",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                List.of(privacy),
                false);

        return new LegalRequiredSetProjection(
                ContextoLegal.REGISTRO,
                LocaleLegal.ES_AR,
                List.of(acceptedTerms, privacyNotice));
    }

    private static LegalRequiredSetProjection replaceFirstRequirement(
            LegalRequiredSetProjection source,
            RequirementProjection replacement) {
        List<RequirementProjection> requirements = new ArrayList<>(source.requirements());
        requirements.set(0, replacement);
        return new LegalRequiredSetProjection(source.context(), source.locale(), requirements);
    }

    private static LegalRequiredSetProjection replaceFirstDocument(
            LegalRequiredSetProjection source,
            DocumentProjection replacement) {
        RequirementProjection requirement = source.requirements().getFirst();
        List<DocumentProjection> documents = new ArrayList<>(requirement.documents());
        documents.set(0, replacement);
        return replaceFirstRequirement(
                source,
                copyRequirement(
                        requirement,
                        requirement.versionId(),
                        requirement.statement(),
                        requirement.statementSha256(),
                        documents,
                        requirement.required()));
    }

    private static RequirementProjection copyRequirement(
            RequirementProjection source,
            UUID versionId,
            String statement,
            String statementSha256,
            List<DocumentProjection> documents,
            boolean required) {
        return new RequirementProjection(
                versionId,
                source.context(),
                source.actType(),
                statement,
                statementSha256,
                documents,
                required);
    }

    private static DocumentProjection copyDocument(
            DocumentProjection source,
            UUID versionId,
            String markdown,
            String sha256,
            OffsetDateTime effectiveAt) {
        return new DocumentProjection(
                versionId,
                source.type(),
                source.version(),
                source.title(),
                markdown,
                sha256,
                effectiveAt,
                source.locale());
    }

    private static DocumentProjection firstDocument(LegalRequiredSetProjection projection) {
        return projection.requirements().getFirst().documents().getFirst();
    }

    private static void assertProjectionContract(JsonNode root) {
        assertThat(fieldNames(root)).containsExactly("contexto", "locale", "requisitos");

        JsonNode requirements = root.path("requisitos");
        assertThat(requirements).hasSize(2);
        JsonNode firstRequirement = requirements.get(0);
        JsonNode secondRequirement = requirements.get(1);
        assertThat(fieldNames(firstRequirement)).containsExactly(
                "id",
                "contexto",
                "tipoActo",
                "afirmacion",
                "afirmacionSha256",
                "documentos",
                "requerido");
        assertThat(firstRequirement.path("id").textValue())
                .isEqualTo("00000000-0000-0000-0000-0000000000b2");
        assertThat(secondRequirement.path("id").textValue())
                .isEqualTo("00000000-0000-0000-0000-0000000000a1");
        assertThat(firstRequirement.path("requerido").booleanValue()).isTrue();
        assertThat(secondRequirement.path("requerido").booleanValue()).isFalse();

        JsonNode firstDocuments = firstRequirement.path("documentos");
        JsonNode secondDocuments = secondRequirement.path("documentos");
        assertThat(firstDocuments).hasSize(2);
        assertThat(secondDocuments).hasSize(1);
        assertThat(firstDocuments.get(0).path("id").textValue())
                .isEqualTo("10000000-0000-0000-0000-000000000002");
        assertThat(firstDocuments.get(1).path("id").textValue())
                .isEqualTo("10000000-0000-0000-0000-000000000001");
        assertThat(secondDocuments.get(0)).isEqualTo(firstDocuments.get(0));
        assertThat(fieldNames(firstDocuments.get(0))).containsExactly(
                "id",
                "tipo",
                "version",
                "titulo",
                "contenidoMarkdown",
                "sha256",
                "vigenteDesde",
                "estado",
                "locale");
        assertThat(firstDocuments.get(0).path("vigenteDesde").textValue())
                .isEqualTo("2026-09-01T03:00:00.123456Z");
        assertThat(firstDocuments.get(1).path("vigenteDesde").textValue())
                .isEqualTo("2026-09-01T03:00:00Z");
        assertThat(firstDocuments.get(0).path("estado").textValue()).isEqualTo("VIGENTE");
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode parseStrictJson(byte[] bytes) {
        LegalManifestValidation<StrictJsonReader.StrictJsonDocument> parsed =
                new StrictJsonReader().read(bytes);
        assertThat(parsed.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(parsed.issues()).isEmpty();
        return parsed.value().orElseThrow().root();
    }

    private static byte[] withoutSingleTerminalLf(byte[] fixture) {
        assertThat(fixture).isNotEmpty();
        assertThat(fixture[fixture.length - 1]).isEqualTo((byte) '\n');
        if (fixture.length > 1) {
            assertThat(fixture[fixture.length - 2]).isNotEqualTo((byte) '\n');
        }
        return Arrays.copyOf(fixture, fixture.length - 1);
    }

    private static byte[] readFixture(String fileName) {
        try (InputStream input = LegalRequiredSetRevisionCalculatorTest.class
                .getResourceAsStream(FIXTURE_ROOT + fileName)) {
            return Objects.requireNonNull(input, fileName).readAllBytes();
        } catch (IOException exception) {
            throw new AssertionError("No se pudo leer el fixture " + fileName, exception);
        }
    }
}
