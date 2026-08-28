package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Rfc8785CanonicalizerTest {

    private static final String OFFICIAL_VECTOR_ROOT = "/legal/jcs/rfc8785/";
    private static final Map<String, String> OFFICIAL_SHA256 = Map.ofEntries(
            Map.entry("input/arrays.json",
                    "e503b6d71d1afa595b1c74b1016445c944cd89f90418066b23de1aeda7d17563"),
            Map.entry("input/french.json",
                    "03676a951cd8753ac62589f72eb2105cc782c33425418cfe1d517c111f6e5d5a"),
            Map.entry("input/structures.json",
                    "d66893805be1784116af50af3110d08766c70a6b4aad93374723f72346e7aaa6"),
            Map.entry("input/unicode.json",
                    "4621864e014d4a805a563f55b9ea20aba4a2d2dc09c7394f625496998c00702c"),
            Map.entry("input/values.json",
                    "c4a041b503d6bc236036ef44db4dac499272f60fc22c40dc3b7a54870ba6f1c3"),
            Map.entry("input/weird.json",
                    "a3a905266bd4a49a969274ea69baa14ee0c4af0ead926d6fa2b7612b4af75387"),
            Map.entry("output/arrays.json",
                    "099601b171cafed97c333f8878d68e7f8c8f795412adb34b2fdcf0e7c7beac42"),
            Map.entry("output/french.json",
                    "d99d0ebdcb0033cb858cfa830ae46bc0fb3309413b271f1da828c89901a27ed5"),
            Map.entry("output/structures.json",
                    "605f65004ec2db7692522a0852c22f1c989e036d547e88963d1a3143cf3195d5"),
            Map.entry("output/unicode.json",
                    "0d99aad92a125196ff887876643fd3206786a84ddce2cee52ba4ad256d2381d3"),
            Map.entry("output/values.json",
                    "2d5e01a318d0f0879ab568c4be289c8b1f64ef8921a53c6277d5e069978baacb"),
            Map.entry("output/weird.json",
                    "6af595a9aa80110b964b4de3f82a05fa6ae7423005019bacfa2620dddc4e94d1"));
    private static final Set<String> UPSTREAM_WITHOUT_TERMINAL_LF = Set.of(
            "input/structures.json",
            "input/values.json",
            "output/arrays.json",
            "output/french.json",
            "output/structures.json",
            "output/unicode.json",
            "output/values.json",
            "output/weird.json");

    private final StrictJsonReader reader = new StrictJsonReader();
    private final Rfc8785Canonicalizer canonicalizer = new Rfc8785Canonicalizer();

    @Test
    void canonicalizesTheRfc8785NumberAndLiteralExample() {
        String input = """
                {
                  "numbers": [333333333.33333329, 1E30, 4.50, 2e-3, 1e-27],
                  "literals": [null, true, false]
                }
                """;

        Rfc8785Canonicalizer.CanonicalJson canonical = canonicalize(input);

        assertThat(canonical.utf8()).asString(StandardCharsets.UTF_8).isEqualTo(
                "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27]}");
        assertThat(canonical.sha256()).isEqualTo(sha256(canonical.utf8()));
    }

    @Test
    void sortsPropertiesByUtf16CodeUnitsIncludingAnAstralCharacter() {
        String input = "{\"\":4,\"😀\":3,\"€\":2,\"ö\":1}";

        Rfc8785Canonicalizer.CanonicalJson canonical = canonicalize(input);

        assertThat(canonical.utf8()).asString(StandardCharsets.UTF_8)
                .isEqualTo("{\"ö\":1,\"€\":2,\"😀\":3,\"\":4}");
    }

    @Test
    void appliesTheRequiredControlQuoteAndSlashEscapes() {
        String input = "{\"slash\":\"\\/\",\"control\":\""
                + unicodeEscape("000F")
                + "\",\"quote\":\"\\\"\"}";

        Rfc8785Canonicalizer.CanonicalJson canonical = canonicalize(input);

        assertThat(canonical.utf8()).asString(StandardCharsets.UTF_8)
                .isEqualTo("{\"control\":\"\\u000f\",\"quote\":\"\\\"\",\"slash\":\"/\"}");
    }

    @Test
    void pinsEveryOfficialCorpusFixtureToItsUpstreamChecksum() {
        OFFICIAL_SHA256.forEach((relativePath, expectedSha256) -> assertThat(sha256(
                exactUpstreamBytes(relativePath, readOfficialResource(relativePath))))
                .as(relativePath)
                .isEqualTo(expectedSha256));
    }

    @ParameterizedTest(name = "official JCS vector {0}")
    @MethodSource("compatibleOfficialVectorNames")
    void matchesEveryOfficialCorpusVectorCompatibleWithTheStrictContract(String vectorName) {
        String inputPath = "input/" + vectorName + ".json";
        String outputPath = "output/" + vectorName + ".json";
        byte[] input = exactUpstreamBytes(inputPath, readOfficialResource(inputPath));
        byte[] expected = exactUpstreamBytes(outputPath, readOfficialResource(outputPath));

        LegalManifestValidation<StrictJsonReader.StrictJsonDocument> parsed = reader.read(input);
        assertThat(parsed.status()).isEqualTo(LegalManifestStatus.PASS);

        Rfc8785Canonicalizer.CanonicalJson canonical = canonicalizer
                .canonicalize(parsed.value().orElseThrow())
                .value()
                .orElseThrow();
        assertThat(canonical.utf8()).containsExactly(expected);
    }

    @ParameterizedTest(name = "official JCS vector {0} is intentionally stricter")
    @ValueSource(strings = {"unicode", "weird"})
    void blocksOfficialGenericVectorsThatViolateTheLegalManifestContract(String vectorName) {
        String inputPath = "input/" + vectorName + ".json";

        LegalManifestValidation<StrictJsonReader.StrictJsonDocument> result = reader.read(
                exactUpstreamBytes(inputPath, readOfficialResource(inputPath)));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(vectorName.equals("unicode")
                        ? LegalManifestIssueCode.MANIFEST_NFC_REQUIRED
                        : LegalManifestIssueCode.MANIFEST_CR_FORBIDDEN);
    }

    @ParameterizedTest(name = "direct JCS library vector {0}")
    @ValueSource(strings = {"unicode", "weird"})
    void directJcsLibraryMatchesTheOfficialVectorsBlockedByTheProductGate(String vectorName)
            throws IOException {
        String inputPath = "input/" + vectorName + ".json";
        String outputPath = "output/" + vectorName + ".json";
        byte[] input = exactUpstreamBytes(inputPath, readOfficialResource(inputPath));
        byte[] expected = exactUpstreamBytes(outputPath, readOfficialResource(outputPath));

        byte[] canonical = new JsonCanonicalizer(
                new String(input, StandardCharsets.UTF_8)).getEncodedUTF8();

        assertThat(canonical).containsExactly(expected);
    }

    @ParameterizedTest(name = "RFC 8785 Appendix B {0} -> {1}")
    @MethodSource("rfc8785AppendixBNumbers")
    void matchesEveryFiniteRfc8785AppendixBNumber(String ieee754Hex, String expected) {
        double value = Double.longBitsToDouble(Long.parseUnsignedLong(ieee754Hex, 16));

        Rfc8785Canonicalizer.CanonicalJson canonical = canonicalize(
                "[" + Double.toString(value) + "]");

        assertThat(canonical.utf8()).asString(StandardCharsets.UTF_8)
                .isEqualTo("[" + expected + "]");
    }

    @Test
    void orderAndInsignificantWhitespaceProduceTheSameBytesAndHash() {
        Rfc8785Canonicalizer.CanonicalJson first = canonicalize(
                "{\"b\":[3,2,1],\"a\":\"x\"}");
        Rfc8785Canonicalizer.CanonicalJson second = canonicalize("""
                {
                  "a": "x",
                  "b": [3, 2, 1]
                }
                """);

        assertThat(first).isEqualTo(second);
        assertThat(first.sha256()).isEqualTo(second.sha256());
        assertThat(first.utf8()).containsExactly(second.utf8());
    }

    @Test
    void canonicalBytesAreDefensivelyCopied() {
        Rfc8785Canonicalizer.CanonicalJson canonical = canonicalize("{\"a\":1}");
        byte[] exposed = canonical.utf8();
        exposed[0] = '!';

        assertThat(canonical.utf8()).asString(StandardCharsets.UTF_8)
                .isEqualTo("{\"a\":1}");
        assertThat(canonical.sizeBytes()).isEqualTo(7);
    }

    @Test
    void duplicateWithPreviousNullIsBlockedBeforeJcsCanSeeIt() {
        LegalManifestValidation<StrictJsonReader.StrictJsonDocument> parsed = reader.read(
                "{\"a\":null,\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(parsed.value()).isEmpty();
        assertThat(parsed.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.MANIFEST_JSON_INVALID);
    }

    @Test
    void jcsAcceptsOnlyTheStrictDocumentOrTypedProjectionThroughThisAdapter() {
        assertThatThrownBy(() -> canonicalizer.canonicalize(
                (StrictJsonReader.StrictJsonDocument) null))
                .isInstanceOf(NullPointerException.class);
        assertThat(Modifier.isPublic(Rfc8785Canonicalizer.class.getModifiers())).isFalse();
        assertThat(Rfc8785Canonicalizer.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("canonicalize"))
                .allSatisfy(method -> assertThat(method.getParameterCount()).isOne())
                .extracting(method -> method.getParameterTypes()[0])
                .containsExactlyInAnyOrder(
                        StrictJsonReader.StrictJsonDocument.class,
                        LegalRequiredSetProjection.class,
                        LegalEditorialStateProjection.class);
    }

    private static Stream<String> compatibleOfficialVectorNames() {
        return Stream.of("arrays", "french", "structures", "values");
    }

    private static Stream<Arguments> rfc8785AppendixBNumbers() {
        return Stream.of(
                Arguments.arguments("0000000000000000", "0"),
                Arguments.arguments("8000000000000000", "0"),
                Arguments.arguments("0000000000000001", "5e-324"),
                Arguments.arguments("8000000000000001", "-5e-324"),
                Arguments.arguments("7fefffffffffffff", "1.7976931348623157e+308"),
                Arguments.arguments("ffefffffffffffff", "-1.7976931348623157e+308"),
                Arguments.arguments("4340000000000000", "9007199254740992"),
                Arguments.arguments("c340000000000000", "-9007199254740992"),
                Arguments.arguments("4430000000000000", "295147905179352830000"),
                Arguments.arguments("44b52d02c7e14af5", "9.999999999999997e+22"),
                Arguments.arguments("44b52d02c7e14af6", "1e+23"),
                Arguments.arguments("44b52d02c7e14af7", "1.0000000000000001e+23"),
                Arguments.arguments("444b1ae4d6e2ef4e", "999999999999999700000"),
                Arguments.arguments("444b1ae4d6e2ef4f", "999999999999999900000"),
                Arguments.arguments("444b1ae4d6e2ef50", "1e+21"),
                Arguments.arguments("3eb0c6f7a0b5ed8c", "9.999999999999997e-7"),
                Arguments.arguments("3eb0c6f7a0b5ed8d", "0.000001"),
                Arguments.arguments("41b3de4355555553", "333333333.3333332"),
                Arguments.arguments("41b3de4355555554", "333333333.33333325"),
                Arguments.arguments("41b3de4355555555", "333333333.3333333"),
                Arguments.arguments("41b3de4355555556", "333333333.3333334"),
                Arguments.arguments("41b3de4355555557", "333333333.33333343"),
                Arguments.arguments("becbf647612f3696", "-0.0000033333333333333333"),
                Arguments.arguments("43143ff3c1cb0959", "1424953923781206.2"));
    }

    private static byte[] readOfficialResource(String relativePath) {
        try (InputStream input = Rfc8785CanonicalizerTest.class.getResourceAsStream(
                OFFICIAL_VECTOR_ROOT + relativePath)) {
            return Objects.requireNonNull(input, relativePath).readAllBytes();
        } catch (IOException exception) {
            throw new AssertionError("No se pudo leer el vector oficial " + relativePath, exception);
        }
    }

    private static byte[] exactUpstreamBytes(String relativePath, byte[] repositoryBytes) {
        if (!UPSTREAM_WITHOUT_TERMINAL_LF.contains(relativePath)) {
            return repositoryBytes;
        }
        if (repositoryBytes.length == 0 || repositoryBytes[repositoryBytes.length - 1] != '\n') {
            throw new AssertionError("El fixture no contiene el LF terminal documentado: "
                    + relativePath);
        }
        return Arrays.copyOf(repositoryBytes, repositoryBytes.length - 1);
    }

    private Rfc8785Canonicalizer.CanonicalJson canonicalize(String input) {
        LegalManifestValidation<StrictJsonReader.StrictJsonDocument> parsed = reader.read(
                input.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.status()).isEqualTo(LegalManifestStatus.PASS);

        LegalManifestValidation<Rfc8785Canonicalizer.CanonicalJson> canonical =
                canonicalizer.canonicalize(parsed.value().orElseThrow());
        assertThat(canonical.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(canonical.issues()).isEmpty();
        return canonical.value().orElseThrow();
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
}
