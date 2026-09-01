package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection.ScopeRevision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance.ScopeOrigin;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalRequiredSetAggregateProvenanceCalculatorTest {

    private static final String FIXTURE_ROOT =
            "/legal/manifest/required-set-aggregate-provenance-v1/";
    private static final UUID SET_ONE =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SET_TWO =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID PUBLICATION_ONE =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID PUBLICATION_TWO =
            UUID.fromString("20000000-0000-0000-0000-000000000002");

    private final LegalRequiredSetAggregateProvenanceCalculator calculator =
            new LegalRequiredSetAggregateProvenanceCalculator();

    @Test
    void matchesReadableCanonicalAndFingerprintGoldensUsingAnIndependentJcsOracle()
            throws IOException {
        LegalRequiredSetAggregateProvenance provenance = goldenProvenance();

        byte[] projectionFixture = readFixture("projection.json");
        assertSingleLfFixture(projectionFixture);
        JsonNode expectedProjection = parseStrictJson(projectionFixture);
        JsonNode actualProjection = parseStrictJson(
                calculator.projectionJson(provenance).getBytes(StandardCharsets.UTF_8));
        assertThat(actualProjection).isEqualTo(expectedProjection);
        assertProvenanceJsonContract(actualProjection);

        byte[] expectedCanonical = withoutSingleTerminalLf(readFixture("canonical.json"));
        byte[] actualCanonical = calculator.canonicalUtf8(provenance);
        assertThat(actualCanonical).containsExactly(expectedCanonical);
        assertThat(actualCanonical).containsExactly(
                new JsonCanonicalizer(calculator.projectionJson(provenance)).getEncodedUTF8());

        String expectedFingerprint = new String(
                withoutSingleTerminalLf(readFixture("sha256.txt")),
                StandardCharsets.US_ASCII);
        assertThat(expectedFingerprint).matches("sha256:[0-9a-f]{64}");
        assertThat(calculator.calculate(provenance)).isEqualTo(expectedFingerprint);
        assertThat(expectedFingerprint).isEqualTo("sha256:" + sha256(expectedCanonical));
    }

    @Test
    void permutedInputProducesTheSameFrozenScopeOrderCanonicalBytesAndFingerprint() {
        LegalRequiredSetAggregateProvenance baseline = goldenProvenance();
        List<ScopeOrigin> reversed = new ArrayList<>(baseline.scopes());
        Collections.reverse(reversed);

        LegalRequiredSetAggregateProvenance permuted = provenance(
                baseline.profile(),
                baseline.audience(),
                reversed);

        assertThat(permuted.scopes())
                .extracting(ScopeOrigin::context)
                .containsExactly(ContextoLegal.USO_CONTINUADO, ContextoLegal.ATESTACION_FOTOS);
        assertThat(calculator.canonicalUtf8(permuted))
                .containsExactly(calculator.canonicalUtf8(baseline));
        assertThat(calculator.calculate(permuted)).isEqualTo(calculator.calculate(baseline));
    }

    @Test
    void physicalOriginMutationChangesFingerprintWithoutChangingSemanticToken() {
        LegalRequiredSetAggregateProvenance baseline = goldenProvenance();
        LegalRequiredSetAggregateProvenance moved = provenance(
                baseline.profile(),
                baseline.audience(),
                List.of(
                        new ScopeOrigin(
                                ContextoLegal.USO_CONTINUADO,
                                UUID.fromString("10000000-0000-0000-0000-000000000099"),
                                UUID.fromString("20000000-0000-0000-0000-000000000099")),
                        baseline.scopes().get(1)));
        LegalRequiredSetAggregateProjection baselineSemantic = semanticProjection('1', '2');
        LegalRequiredSetAggregateProjection movedSemantic = semanticProjection('1', '2');
        LegalRequiredSetAggregateRevisionCalculator semanticCalculator =
                new LegalRequiredSetAggregateRevisionCalculator();

        assertThat(calculator.calculate(moved)).isNotEqualTo(calculator.calculate(baseline));
        assertThat(movedSemantic).isNotSameAs(baselineSemantic);
        assertThat(semanticCalculator.calculate(movedSemantic))
                .isEqualTo(semanticCalculator.calculate(baselineSemantic));
    }

    @Test
    void componentRevisionMutationChangesSemanticTokenButNotPhysicalFingerprint() {
        LegalRequiredSetAggregateProvenance firstProvenance = goldenProvenance();
        LegalRequiredSetAggregateProvenance changedRevisionProvenance = provenance(
                firstProvenance.profile(),
                firstProvenance.audience(),
                new ArrayList<>(firstProvenance.scopes()));
        LegalRequiredSetAggregateProjection first = semanticProjection('1', '2');
        LegalRequiredSetAggregateProjection changed = semanticProjection('1', '3');

        LegalRequiredSetAggregateRevisionCalculator semanticCalculator =
                new LegalRequiredSetAggregateRevisionCalculator();
        assertThat(semanticCalculator.calculate(changed))
                .isNotEqualTo(semanticCalculator.calculate(first));
        assertThat(changedRevisionProvenance).isNotSameAs(firstProvenance);
        assertThat(calculator.calculate(changedRevisionProvenance))
                .isEqualTo(calculator.calculate(firstProvenance));
        assertThat(calculator.projectionJson(firstProvenance))
                .doesNotContain("requiredSetRevision", revision('1'), revision('2'), revision('3'));
    }

    @Test
    void profileAudienceContextAndPhysicalIdsAreIndependentFingerprintInputs() {
        LegalRequiredSetAggregateProvenance baseline = goldenProvenance();
        String expected = calculator.calculate(baseline);
        List<LegalRequiredSetAggregateProvenance> mutations = List.of(
                provenance(
                        PerfilAgregadoLegal.REGISTRATION,
                        baseline.audience(),
                        baseline.scopes()),
                provenance(
                        baseline.profile(),
                        AudienciaLegal.ADMIN_TITULAR,
                        baseline.scopes()),
                provenance(
                        baseline.profile(),
                        baseline.audience(),
                        List.of(
                                baseline.scopes().get(0),
                                new ScopeOrigin(
                                        ContextoLegal.ATESTACION_CREDENCIALES,
                                        SET_TWO,
                                        PUBLICATION_TWO))),
                provenance(
                        baseline.profile(),
                        baseline.audience(),
                        List.of(
                                new ScopeOrigin(
                                        ContextoLegal.USO_CONTINUADO,
                                        UUID.fromString("10000000-0000-0000-0000-000000000003"),
                                        PUBLICATION_ONE),
                                baseline.scopes().get(1))),
                provenance(
                        baseline.profile(),
                        baseline.audience(),
                        List.of(
                                new ScopeOrigin(
                                        ContextoLegal.USO_CONTINUADO,
                                        SET_ONE,
                                        UUID.fromString("20000000-0000-0000-0000-000000000003")),
                                baseline.scopes().get(1))));

        mutations.forEach(mutation -> assertThat(calculator.calculate(mutation))
                .isNotEqualTo(expected));
    }

    @Test
    void provenanceRejectsEmptyDuplicateAndNinthScopes() {
        assertThatThrownBy(() -> provenance(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                AudienciaLegal.USER,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        ScopeOrigin first = goldenProvenance().scopes().getFirst();
        assertThatThrownBy(() -> provenance(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                AudienciaLegal.USER,
                List.of(first, new ScopeOrigin(
                        first.context(),
                        UUID.fromString("10000000-0000-0000-0000-000000000003"),
                        UUID.fromString("20000000-0000-0000-0000-000000000003")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicado");
        assertThatThrownBy(() -> provenance(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                AudienciaLegal.USER,
                Collections.nCopies(Integer.MAX_VALUE, first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ocho");
    }

    @Test
    void provenanceOrdersAllEightContextsByTheFrozenSequence() {
        ContextoLegal[] contexts = ContextoLegal.values();
        List<ScopeOrigin> reversed = new ArrayList<>();
        for (int index = contexts.length - 1; index >= 0; index--) {
            reversed.add(new ScopeOrigin(
                    contexts[index],
                    new UUID(0L, index + 1L),
                    new UUID(1L, index + 1L)));
        }

        LegalRequiredSetAggregateProvenance provenance = provenance(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                AudienciaLegal.USER,
                reversed);

        assertThat(provenance.scopes())
                .extracting(ScopeOrigin::context)
                .containsExactly(
                        ContextoLegal.REGISTRO,
                        ContextoLegal.PRIMER_INGRESO_EMPLEADO,
                        ContextoLegal.USO_CONTINUADO,
                        ContextoLegal.CONTRATACION_PRO,
                        ContextoLegal.ATESTACION_FOTOS,
                        ContextoLegal.ATESTACION_CREDENCIALES,
                        ContextoLegal.CIERRE_CUENTA,
                        ContextoLegal.ARREPENTIMIENTO);
    }

    @Test
    void provenanceSurfaceContainsOnlyTheFrozenPhysicalFields() {
        assertThat(LegalRequiredSetAggregateProvenance.PROVENANCE_SCHEME)
                .isEqualTo("AGGREGATE_PROVENANCE_V1");
        assertThat(recordComponentNames(LegalRequiredSetAggregateProvenance.class))
                .containsExactly("profile", "locale", "audience", "scopes");
        assertThat(recordComponentNames(ScopeOrigin.class))
                .containsExactly("context", "requiredSetId", "publicationId");

        JsonNode json = parseStrictJson(
                calculator.projectionJson(goldenProvenance()).getBytes(StandardCharsets.UTF_8));
        assertProvenanceJsonContract(json);
        Set<String> forbidden = Set.of(
                "requiredSetRevision", "digest", "actor", "user", "usuario", "userId",
                "taller", "tallerId", "tenant", "rolWire", "evidencia", "evidence",
                "pendientes", "pending", "requisitos", "requirements", "snapshotId",
                "documentSetRevision", "password", "secret", "credential");
        assertThat(allFieldNames(json)).doesNotContainAnyElementsOf(forbidden);
    }

    private static LegalRequiredSetAggregateProvenance goldenProvenance() {
        return provenance(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                AudienciaLegal.USER,
                List.of(
                        new ScopeOrigin(
                                ContextoLegal.USO_CONTINUADO,
                                SET_ONE,
                                PUBLICATION_ONE),
                        new ScopeOrigin(
                                ContextoLegal.ATESTACION_FOTOS,
                                SET_TWO,
                                PUBLICATION_TWO)));
    }

    private static LegalRequiredSetAggregateProvenance provenance(
            PerfilAgregadoLegal profile,
            AudienciaLegal audience,
            List<ScopeOrigin> scopes) {
        return new LegalRequiredSetAggregateProvenance(
                profile,
                LocaleLegal.ES_AR,
                audience,
                scopes);
    }

    private static LegalRequiredSetAggregateProjection semanticProjection(
            char firstRevision,
            char secondRevision) {
        return new LegalRequiredSetAggregateProjection(
                EsquemaRevisionLegal.AGGREGATE_V1,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER,
                List.of(
                        new ScopeRevision(ContextoLegal.USO_CONTINUADO, revision(firstRevision)),
                        new ScopeRevision(ContextoLegal.ATESTACION_FOTOS, revision(secondRevision))));
    }

    private static void assertProvenanceJsonContract(JsonNode root) {
        assertThat(fieldNames(root)).containsExactly(
                "provenanceScheme", "perfil", "locale", "audiencia", "scopes");
        assertThat(root.path("provenanceScheme").textValue())
                .isEqualTo("AGGREGATE_PROVENANCE_V1");
        assertThat(root.path("perfil").textValue()).isEqualTo("AUTHENTICATED_PENDING");
        assertThat(root.path("locale").textValue()).isEqualTo("es-AR");
        assertThat(root.path("audiencia").textValue()).isEqualTo("USER");
        assertThat(root.path("scopes")).hasSize(2);
        for (JsonNode scope : root.path("scopes")) {
            assertThat(fieldNames(scope))
                    .containsExactly("contexto", "conjuntoId", "publicacionId");
            assertThat(scope.path("conjuntoId").textValue()).isNotBlank();
            assertThat(scope.path("publicacionId").textValue()).isNotBlank();
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
        try (InputStream input = LegalRequiredSetAggregateProvenanceCalculatorTest.class
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

    private static String revision(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }
}
