package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalTextValidatorTest {

    private static final String LOCATION = "documents/terminos.md";
    private static final String ZERO_DIGEST = "0".repeat(64);

    private final CanonicalTextValidator validator = new CanonicalTextValidator();

    @Test
    void acceptsExactMarkdownBytesAndReturnsAnImmutableDefensiveValue() {
        byte[] source = "# Términos\nContenido ágil 😀\n".getBytes(StandardCharsets.UTF_8);
        byte[] original = Arrays.copyOf(source, source.length);
        String expectedSha256 = sha256(source);

        LegalManifestValidation<CanonicalTextValidator.CanonicalText> result =
                validator.validate(source, expectedSha256, LOCATION);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.issues()).isEmpty();
        CanonicalTextValidator.CanonicalText canonicalText = result.value().orElseThrow();
        assertThat(canonicalText.bytes()).isEqualTo(original);
        assertThat(canonicalText.text()).isEqualTo(new String(original, StandardCharsets.UTF_8));
        assertThat(canonicalText.sha256()).isEqualTo(expectedSha256);
        assertThat(Modifier.isFinal(canonicalText.getClass().getModifiers())).isTrue();

        source[0] = '!';
        byte[] exposedBytes = canonicalText.bytes();
        exposedBytes[0] = '?';

        assertThat(canonicalText.bytes()).isEqualTo(original);
        assertThat(canonicalText.sha256()).isEqualTo(expectedSha256);
    }

    @Test
    void acceptsAStatementUsingItsExactUtf8Representation() {
        String statement = "Declaro que leí y acepto la política 😀";
        byte[] exactUtf8 = statement.getBytes(StandardCharsets.UTF_8);

        LegalManifestValidation<CanonicalTextValidator.CanonicalText> result =
                validator.validate(
                        statement,
                        sha256(exactUtf8),
                        "requirements/registro#statement");

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        CanonicalTextValidator.CanonicalText canonicalText = result.value().orElseThrow();
        assertThat(canonicalText.text()).isEqualTo(statement);
        assertThat(canonicalText.bytes()).isEqualTo(exactUtf8);
        assertThat(canonicalText.sha256()).isEqualTo(sha256(exactUtf8));
    }

    @Test
    void rejectsMalformedUtf8WithoutReplacement() {
        byte[] malformedUtf8 = {(byte) 0xC3, 0x28};

        assertBlocked(
                validator.validate(malformedUtf8, ZERO_DIGEST, LOCATION),
                LegalManifestIssueCode.LEGAL_TEXT_UTF8_INVALID,
                LOCATION);
    }

    @Test
    void rejectsBomForByteAndStringInputs() {
        byte[] byteBom = {
                (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '#', ' ', 'T', 'i', 't', 'l', 'e'
        };

        assertBlocked(
                validator.validate(byteBom, ZERO_DIGEST, LOCATION),
                LegalManifestIssueCode.LEGAL_TEXT_BOM_FORBIDDEN,
                LOCATION);
        assertBlocked(
                validator.validate(
                        "\uFEFFAcepto",
                        ZERO_DIGEST,
                        "requirements/registro#statement"),
                LegalManifestIssueCode.LEGAL_TEXT_BOM_FORBIDDEN,
                "requirements/registro#statement");
    }

    @Test
    void rejectsCrAndNonNfcWithoutRewritingTheText() {
        byte[] crlf = "# Título\r\nTexto\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] nfd = "# Te\u0301rminos\n".getBytes(StandardCharsets.UTF_8);

        assertBlocked(
                validator.validate(crlf, ZERO_DIGEST, LOCATION),
                LegalManifestIssueCode.LEGAL_TEXT_CR_FORBIDDEN,
                LOCATION);
        assertBlocked(
                validator.validate(nfd, ZERO_DIGEST, LOCATION),
                LegalManifestIssueCode.LEGAL_TEXT_NFC_REQUIRED,
                LOCATION);
    }

    @Test
    void rejectsC0ExceptTabAndLfAndAlsoRejectsDelAndC1ForBytesAndStrings() {
        List<Integer> forbiddenControls = List.of(
                0x0000,
                0x0001,
                0x0008,
                0x000B,
                0x000C,
                0x001F,
                0x007F,
                0x0080,
                0x009F);

        for (int forbiddenControl : forbiddenControls) {
            String text = "Texto" + (char) forbiddenControl;
            assertBlocked(
                    validator.validate(
                            text.getBytes(StandardCharsets.UTF_8),
                            ZERO_DIGEST,
                            LOCATION),
                    LegalManifestIssueCode.LEGAL_TEXT_CONTROL_FORBIDDEN,
                    LOCATION);
            assertBlocked(
                    validator.validate(
                            text,
                            ZERO_DIGEST,
                            "requirements/registro#statement"),
                    LegalManifestIssueCode.LEGAL_TEXT_CONTROL_FORBIDDEN,
                    "requirements/registro#statement");
        }
    }

    @Test
    void acceptsTabAndLfForByteAndStringInputs() {
        String text = "Primera línea\n\tSegunda línea\n";
        byte[] exactUtf8 = text.getBytes(StandardCharsets.UTF_8);
        String exactSha256 = sha256(exactUtf8);

        assertThat(validator.validate(exactUtf8, exactSha256, LOCATION).passed()).isTrue();
        assertThat(validator.validate(
                        text,
                        exactSha256,
                        "requirements/registro#statement")
                .passed()).isTrue();
    }

    @Test
    void rejectsUnpairedSurrogatesInStatementsAndAcceptsAValidPair() {
        assertBlocked(
                validator.validate(
                        "Acepto \uD800",
                        ZERO_DIGEST,
                        "requirements/registro#statement"),
                LegalManifestIssueCode.LEGAL_TEXT_SURROGATE_INVALID,
                "requirements/registro#statement");
        assertBlocked(
                validator.validate(
                        "Acepto \uDC00",
                        ZERO_DIGEST,
                        "requirements/registro#statement"),
                LegalManifestIssueCode.LEGAL_TEXT_SURROGATE_INVALID,
                "requirements/registro#statement");

        String validPair = "Acepto \uD83D\uDE00";
        assertThat(validator.validate(
                        validPair,
                        sha256(validPair.getBytes(StandardCharsets.UTF_8)),
                        "requirements/registro#statement")
                .passed()).isTrue();
    }

    @Test
    void rejectsUnicodeNoncharactersInTheBmpAndAstralPlanes() {
        List<Integer> noncharacters = List.of(0xFDD0, 0xFFFF, 0x1FFFE, 0x10FFFF);

        for (int noncharacter : noncharacters) {
            String text = "# Título\n" + new String(Character.toChars(noncharacter));
            assertBlocked(
                    validator.validate(
                            text.getBytes(StandardCharsets.UTF_8),
                            ZERO_DIGEST,
                            LOCATION),
                    LegalManifestIssueCode.LEGAL_TEXT_UNICODE_NONCHARACTER_FORBIDDEN,
                    LOCATION);
        }
    }

    @Test
    void rejectsDigestMismatchAndEveryNonExactExpectedHexDigest() {
        byte[] content = "# Título\n".getBytes(StandardCharsets.UTF_8);
        String actualDigest = sha256(content);

        assertBlocked(
                validator.validate(content, ZERO_DIGEST, LOCATION),
                LegalManifestIssueCode.LEGAL_TEXT_DIGEST_MISMATCH,
                LOCATION);

        for (String malformed : Arrays.asList(
                null,
                "",
                actualDigest.substring(1),
                actualDigest + "0",
                actualDigest.toUpperCase(),
                "g".repeat(64),
                actualDigest + "\n")) {
            assertBlocked(
                    validator.validate(content, malformed, LOCATION),
                    LegalManifestIssueCode.LEGAL_TEXT_DIGEST_MISMATCH,
                    LOCATION);
        }
    }

    @Test
    void acceptsTheExactDocumentByteLimitAndBlocksTheNextByteBeforeDigesting() {
        byte[] exactLimit = new byte[LegalManifestLimits.MAX_MARKDOWN_BYTES];
        Arrays.fill(exactLimit, (byte) 'a');
        byte[] overLimit = Arrays.copyOf(exactLimit, exactLimit.length + 1);

        assertThat(validator.validate(exactLimit, sha256(exactLimit), LOCATION).passed()).isTrue();
        assertBlocked(
                validator.validate(overLimit, null, LOCATION),
                LegalManifestIssueCode.DOCUMENT_SIZE_LIMIT_EXCEEDED,
                LOCATION);
    }

    @Test
    void mapsUnexpectedCryptoFailuresToASafeError() {
        byte[] content = "# Título\n".getBytes(StandardCharsets.UTF_8);
        String expectedSha256 = sha256(content);
        CanonicalTextValidator runtimeFailure = new CanonicalTextValidator(() -> {
            throw new IllegalStateException("detalle sensible del proveedor");
        });
        CanonicalTextValidator unavailableAlgorithm = new CanonicalTextValidator(() -> {
            throw new NoSuchAlgorithmException("detalle sensible del proveedor");
        });

        for (CanonicalTextValidator failingValidator
                : List.of(runtimeFailure, unavailableAlgorithm)) {
            LegalManifestValidation<?> result = failingValidator.validate(
                    content,
                    expectedSha256,
                    LOCATION);

            assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
            assertThat(result.issues()).singleElement().satisfies(issue -> {
                assertThat(issue.code())
                        .isEqualTo(LegalManifestIssueCode.LEGAL_TEXT_VALIDATION_ERROR);
                assertThat(issue.location()).isEqualTo(LOCATION);
                assertThat(issue.message()).doesNotContain("detalle sensible");
            });
        }
    }

    @Test
    void treatsNullContentAsBlockedInputInsteadOfAnOperationalError() {
        assertBlocked(
                validator.validate((byte[]) null, ZERO_DIGEST, LOCATION),
                LegalManifestIssueCode.LEGAL_TEXT_UTF8_INVALID,
                LOCATION);
        assertBlocked(
                validator.validate((String) null, ZERO_DIGEST, LOCATION),
                LegalManifestIssueCode.LEGAL_TEXT_UTF8_INVALID,
                LOCATION);
    }

    @Test
    void rejectsUnsafeLocationsBeforePassingByteOrStringInputs() {
        String text = "# Título\n";
        byte[] exactUtf8 = text.getBytes(StandardCharsets.UTF_8);
        String exactSha256 = sha256(exactUtf8);

        for (String unsafeLocation : List.of(
                "/tmp/publication/terminos.md",
                "documents/../secreto.md")) {
            assertThatThrownBy(() -> validator.validate(
                            exactUtf8,
                            exactSha256,
                            unsafeLocation))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(unsafeLocation);
            assertThatThrownBy(() -> validator.validate(
                            text,
                            exactSha256,
                            unsafeLocation))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(unsafeLocation);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertBlocked(
            LegalManifestValidation<?> result,
            LegalManifestIssueCode expectedCode,
            String expectedLocation) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(expectedCode);
            assertThat(issue.location()).isEqualTo(expectedLocation);
        });
    }
}
