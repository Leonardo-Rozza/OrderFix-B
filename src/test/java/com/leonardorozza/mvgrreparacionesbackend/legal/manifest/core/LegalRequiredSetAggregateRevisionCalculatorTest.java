package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection.ScopeRevision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalRequiredSetAggregateRevisionCalculatorTest {

    private static final String FIXTURE_ROOT =
            "/legal/manifest/required-set-aggregate-v1/";
    private static final String REVISION_A = revision('1');
    private static final String REVISION_B = revision('2');
    private static final String REVISION_C = revision('3');

    private final LegalRequiredSetAggregateRevisionCalculator calculator =
            new LegalRequiredSetAggregateRevisionCalculator();

    @Test
    void matchesReadableCanonicalAndRevisionGoldensUsingAnIndependentJcsOracle()
            throws IOException {
        LegalRequiredSetAggregateProjection projection = goldenProjection();

        byte[] projectionFixture = readFixture("projection.json");
        assertSingleLfFixture(projectionFixture);
        JsonNode expectedProjection = parseStrictJson(projectionFixture);
        JsonNode actualProjection = parseStrictJson(
                calculator.projectionJson(projection).getBytes(StandardCharsets.UTF_8));
        assertThat(actualProjection).isEqualTo(expectedProjection);
        assertSemanticJsonContract(actualProjection);

        byte[] expectedCanonical = withoutSingleTerminalLf(readFixture("canonical.json"));
        byte[] actualCanonical = calculator.canonicalUtf8(projection);
        assertThat(actualCanonical).containsExactly(expectedCanonical);
        assertThat(actualCanonical).containsExactly(
                new JsonCanonicalizer(calculator.projectionJson(projection)).getEncodedUTF8());

        String expectedRevision = new String(
                withoutSingleTerminalLf(readFixture("sha256.txt")),
                StandardCharsets.US_ASCII);
        assertThat(expectedRevision).matches("sha256:[0-9a-f]{64}");
        assertThat(calculator.calculate(projection)).isEqualTo(expectedRevision);
        assertThat(expectedRevision).isEqualTo("sha256:" + sha256(expectedCanonical));
    }

    @Test
    void permutedInputProducesTheSameFrozenScopeOrderCanonicalBytesAndToken() {
        LegalRequiredSetAggregateProjection baseline = goldenProjection();
        List<ScopeRevision> reversed = new ArrayList<>(baseline.scopes());
        Collections.reverse(reversed);

        LegalRequiredSetAggregateProjection permuted = projection(
                baseline.audience(),
                reversed);

        assertThat(permuted.scopes())
                .extracting(ScopeRevision::context)
                .containsExactly(ContextoLegal.USO_CONTINUADO, ContextoLegal.ATESTACION_FOTOS);
        assertThat(calculator.canonicalUtf8(permuted))
                .containsExactly(calculator.canonicalUtf8(baseline));
        assertThat(calculator.calculate(permuted)).isEqualTo(calculator.calculate(baseline));
    }

    @Test
    void everyIncludedSemanticMutationChangesTheToken() {
        LegalRequiredSetAggregateProjection baseline = goldenProjection();
        String expected = calculator.calculate(baseline);
        Map<String, LegalRequiredSetAggregateProjection> mutations = new java.util.LinkedHashMap<>();
        mutations.put("component revision", projection(
                baseline.audience(),
                List.of(
                        new ScopeRevision(ContextoLegal.USO_CONTINUADO, REVISION_C),
                        baseline.scopes().get(1))));
        mutations.put("included scope removed", projection(
                baseline.audience(),
                List.of(baseline.scopes().getFirst())));
        mutations.put("included scope added", projection(
                baseline.audience(),
                List.of(
                        baseline.scopes().get(0),
                        new ScopeRevision(ContextoLegal.CONTRATACION_PRO, REVISION_C),
                        baseline.scopes().get(1))));
        mutations.put("included context", projection(
                baseline.audience(),
                List.of(
                        baseline.scopes().get(0),
                        new ScopeRevision(ContextoLegal.ATESTACION_CREDENCIALES, REVISION_B))));
        mutations.put("audience", projection(AudienciaLegal.USER, baseline.scopes()));

        mutations.forEach((name, mutation) -> assertThat(calculator.calculate(mutation))
                .as(name)
                .isNotEqualTo(expected));
    }

    @Test
    void legitimatelyExcludedScopeMayBeAbsentOrChangeWithoutEnteringTheHash() {
        LegalApplicableScopeSet applicable = new LegalApplicableScopeResolver(
                (profile, audience) -> List.of(
                        ContextoLegal.USO_CONTINUADO,
                        ContextoLegal.ATESTACION_FOTOS))
                .resolve(
                        PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                        LocaleLegal.ES_AR,
                        AudienciaLegal.ADMIN_TITULAR);
        Map<ContextoLegal, String> externalCatalog = new EnumMap<>(ContextoLegal.class);
        externalCatalog.put(ContextoLegal.USO_CONTINUADO, REVISION_A);
        externalCatalog.put(ContextoLegal.ATESTACION_FOTOS, REVISION_B);
        String baseline = calculator.calculate(projectApplicable(applicable, externalCatalog));

        externalCatalog.remove(ContextoLegal.ARREPENTIMIENTO);
        assertThat(calculator.calculate(projectApplicable(applicable, externalCatalog)))
                .isEqualTo(baseline);
        externalCatalog.put(ContextoLegal.ARREPENTIMIENTO, revision('4'));
        assertThat(calculator.calculate(projectApplicable(applicable, externalCatalog)))
                .isEqualTo(baseline);
        externalCatalog.put(ContextoLegal.ARREPENTIMIENTO, revision('5'));
        LegalRequiredSetAggregateProjection afterExcludedPromotion =
                projectApplicable(applicable, externalCatalog);
        assertThat(calculator.calculate(afterExcludedPromotion)).isEqualTo(baseline);

        String json = calculator.projectionJson(afterExcludedPromotion);
        assertThat(json)
                .doesNotContain(ContextoLegal.ARREPENTIMIENTO.name())
                .doesNotContain(revision('4'), revision('5'));
        assertThat(afterExcludedPromotion.scopes())
                .extracting(ScopeRevision::context)
                .doesNotContain(ContextoLegal.ARREPENTIMIENTO);
    }

    @Test
    void aggregateProjectionCarriesTheCompleteScopeTokenAndNoPendingView() {
        RequirementProjection requirement = new RequirementProjection(
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                ContextoLegal.USO_CONTINUADO,
                TipoActoLegal.LECTURA,
                "Declaro haber leído el requisito completo.",
                "a".repeat(64),
                List.of(),
                true);
        LegalRequiredSetProjection completeScope = new LegalRequiredSetProjection(
                ContextoLegal.USO_CONTINUADO,
                LocaleLegal.ES_AR,
                List.of(requirement));
        String componentRevision = new LegalRequiredSetRevisionCalculator().calculate(completeScope);
        LegalRequiredSetAggregateProjection aggregate = projection(
                AudienciaLegal.USER,
                List.of(new ScopeRevision(ContextoLegal.USO_CONTINUADO, componentRevision)));

        assertThat(calculator.calculate(aggregate)).matches("sha256:[0-9a-f]{64}");
        assertThat(aggregate.scopes())
                .singleElement()
                .extracting(ScopeRevision::requiredSetRevision)
                .isEqualTo(componentRevision);
        assertThat(calculator.projectionJson(aggregate))
                .doesNotContain("requisitos", "requirements", requirement.versionId().toString());
    }

    @Test
    void completeEmptyV27SetStillContributesOneAggregateComponent() {
        LegalRequiredSetProjection completeEmptyScope = new LegalRequiredSetProjection(
                ContextoLegal.USO_CONTINUADO,
                LocaleLegal.ES_AR,
                List.of());
        String componentRevision =
                new LegalRequiredSetRevisionCalculator().calculate(completeEmptyScope);
        LegalRequiredSetAggregateProjection aggregate = projection(
                AudienciaLegal.USER,
                List.of(new ScopeRevision(ContextoLegal.USO_CONTINUADO, componentRevision)));

        assertThat(componentRevision).matches("sha256:[0-9a-f]{64}");
        assertThat(aggregate.scopes())
                .singleElement()
                .satisfies(scope -> {
                    assertThat(scope.context()).isEqualTo(ContextoLegal.USO_CONTINUADO);
                    assertThat(scope.requiredSetRevision()).isEqualTo(componentRevision);
                });
        assertThat(calculator.calculate(aggregate)).matches("sha256:[0-9a-f]{64}");
        assertThat(calculator.projectionJson(aggregate)).doesNotContain("requisitos");
    }

    @Test
    void acceptsExactlyEightScopesAndRejectsTheNinthBeforeDuplicateScanning() {
        ContextoLegal[] contexts = ContextoLegal.values();
        assertThat(contexts).hasSize(8);
        List<ScopeRevision> maximum = new ArrayList<>();
        for (int index = contexts.length - 1; index >= 0; index--) {
            maximum.add(new ScopeRevision(contexts[index], indexedRevision(index)));
        }

        LegalRequiredSetAggregateProjection accepted = projection(
                AudienciaLegal.ADMIN_TITULAR,
                maximum);
        assertThat(accepted.scopes())
                .extracting(ScopeRevision::context)
                .containsExactly(contexts);
        assertThat(calculator.calculate(accepted)).matches("sha256:[0-9a-f]{64}");

        ScopeRevision repeated = new ScopeRevision(ContextoLegal.USO_CONTINUADO, REVISION_A);
        assertThatThrownBy(() -> projection(
                AudienciaLegal.USER,
                Collections.nCopies(Integer.MAX_VALUE, repeated)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ocho");
    }

    @Test
    void rejectsEmptyDuplicateAndMalformedComponentRevisions() {
        assertThat(EsquemaRevisionLegal.values()).containsExactly(
                EsquemaRevisionLegal.SCOPE_V1,
                EsquemaRevisionLegal.AGGREGATE_V1);
        assertThatThrownBy(() -> new LegalRequiredSetAggregateProjection(
                EsquemaRevisionLegal.SCOPE_V1,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER,
                List.of(new ScopeRevision(ContextoLegal.USO_CONTINUADO, REVISION_A))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AGGREGATE_V1");
        assertThatThrownBy(() -> projection(AudienciaLegal.USER, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projection(
                AudienciaLegal.USER,
                List.of(
                        new ScopeRevision(ContextoLegal.USO_CONTINUADO, REVISION_A),
                        new ScopeRevision(ContextoLegal.USO_CONTINUADO, REVISION_B))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicado");

        List<String> invalidRevisions = Arrays.asList(
                "a".repeat(64),
                "sha256:" + "A".repeat(64),
                "sha256:" + "a".repeat(63),
                "sha256:" + "a".repeat(65),
                "sha256:" + "g".repeat(64));
        invalidRevisions.forEach(value -> assertThatThrownBy(
                () -> new ScopeRevision(ContextoLegal.USO_CONTINUADO, value))
                .as(value)
                .isInstanceOf(IllegalArgumentException.class));
        assertThatThrownBy(() -> new ScopeRevision(ContextoLegal.USO_CONTINUADO, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void semanticProjectionSurfaceContainsOnlyTheFrozenFields() {
        assertThat(recordComponentNames(LegalRequiredSetAggregateProjection.class))
                .containsExactly("revisionScheme", "locale", "audience", "scopes");
        assertThat(recordComponentNames(ScopeRevision.class))
                .containsExactly("context", "requiredSetRevision");

        JsonNode json = parseStrictJson(
                calculator.projectionJson(goldenProjection()).getBytes(StandardCharsets.UTF_8));
        assertSemanticJsonContract(json);
        Set<String> forbidden = Set.of(
                "perfil", "profile", "actor", "user", "usuario", "userId", "taller",
                "tallerId", "tenant", "rolWire", "evidencia", "evidence", "pendientes",
                "pending", "requisitos", "requirements", "conjuntoId", "requiredSetId",
                "publicacionId", "publicationId", "snapshotId", "documentSetRevision");
        assertThat(allFieldNames(json)).doesNotContainAnyElementsOf(forbidden);
    }

    private static LegalRequiredSetAggregateProjection goldenProjection() {
        return projection(
                AudienciaLegal.ADMIN_TITULAR,
                List.of(
                        new ScopeRevision(ContextoLegal.USO_CONTINUADO, REVISION_A),
                        new ScopeRevision(ContextoLegal.ATESTACION_FOTOS, REVISION_B)));
    }

    private static LegalRequiredSetAggregateProjection projection(
            AudienciaLegal audience,
            List<ScopeRevision> scopes) {
        return new LegalRequiredSetAggregateProjection(
                EsquemaRevisionLegal.AGGREGATE_V1,
                LocaleLegal.ES_AR,
                audience,
                scopes);
    }

    private static LegalRequiredSetAggregateProjection projectApplicable(
            LegalApplicableScopeSet applicable,
            Map<ContextoLegal, String> currentRevisions) {
        List<ScopeRevision> scopes = applicable.contexts().stream()
                .map(context -> new ScopeRevision(
                        context,
                        Objects.requireNonNull(currentRevisions.get(context), context.name())))
                .toList();
        return new LegalRequiredSetAggregateProjection(
                EsquemaRevisionLegal.AGGREGATE_V1,
                applicable.locale(),
                applicable.audience(),
                scopes);
    }

    private static void assertSemanticJsonContract(JsonNode root) {
        assertThat(fieldNames(root))
                .containsExactly("revisionScheme", "locale", "audiencia", "scopes");
        assertThat(root.path("revisionScheme").textValue()).isEqualTo("AGGREGATE_V1");
        assertThat(root.path("locale").textValue()).isEqualTo("es-AR");
        assertThat(root.path("audiencia").textValue()).isEqualTo("ADMIN_TITULAR");
        assertThat(root.path("scopes")).hasSize(2);
        for (JsonNode scope : root.path("scopes")) {
            assertThat(fieldNames(scope)).containsExactly("contexto", "requiredSetRevision");
            assertThat(scope.path("requiredSetRevision").textValue())
                    .matches("sha256:[0-9a-f]{64}");
        }
    }

    private static List<String> recordComponentNames(Class<?> type) {
        assertThat(type.isRecord()).isTrue();
        return Arrays.stream(Objects.requireNonNull(type.getRecordComponents()))
                .map(RecordComponent::getName)
                .toList();
    }

    private static List<String> allFieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        collectFieldNames(node, names);
        return names;
    }

    private static void collectFieldNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                names.add(name);
                collectFieldNames(node.get(name), names);
            });
        } else if (node.isArray()) {
            node.forEach(item -> collectFieldNames(item, names));
        }
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

    private static void assertSingleLfFixture(byte[] fixture) {
        assertThat(fixture).isNotEmpty().doesNotContain((byte) '\r');
        assertThat(fixture[fixture.length - 1]).isEqualTo((byte) '\n');
        if (fixture.length > 1) {
            assertThat(fixture[fixture.length - 2]).isNotEqualTo((byte) '\n');
        }
    }

    private static byte[] withoutSingleTerminalLf(byte[] fixture) {
        assertSingleLfFixture(fixture);
        return Arrays.copyOf(fixture, fixture.length - 1);
    }

    private static byte[] readFixture(String fileName) {
        try (InputStream input = LegalRequiredSetAggregateRevisionCalculatorTest.class
                .getResourceAsStream(FIXTURE_ROOT + fileName)) {
            return Objects.requireNonNull(input, fileName).readAllBytes();
        } catch (IOException exception) {
            throw new AssertionError("No se pudo leer el fixture " + fileName, exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String indexedRevision(int index) {
        return revision("01234567".charAt(index));
    }

    private static String revision(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }
}
