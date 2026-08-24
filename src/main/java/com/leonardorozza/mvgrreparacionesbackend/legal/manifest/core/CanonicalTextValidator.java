package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Valida texto legal sin normalizarlo ni corregirlo antes de acreditar su SHA-256 exacto.
 */
public final class CanonicalTextValidator {

    private static final int SHA_256_HEX_LENGTH = 64;

    private final DigestFactory digestFactory;

    public CanonicalTextValidator() {
        this(() -> MessageDigest.getInstance("SHA-256"));
    }

    CanonicalTextValidator(DigestFactory digestFactory) {
        this.digestFactory = Objects.requireNonNull(digestFactory, "digestFactory");
    }

    /**
     * Valida los bytes originales de un documento Markdown.
     */
    public LegalManifestValidation<CanonicalText> validate(
            byte[] rawBytes,
            String expectedSha256,
            String safeLocation) {
        requireSafeLocation(safeLocation);
        if (rawBytes == null) {
            return failure(LegalManifestIssueCode.LEGAL_TEXT_UTF8_INVALID, safeLocation);
        }
        if (rawBytes.length > LegalManifestLimits.MAX_MARKDOWN_BYTES) {
            return failure(
                    LegalManifestIssueCode.DOCUMENT_SIZE_LIMIT_EXCEEDED,
                    safeLocation);
        }
        if (hasUtf8Bom(rawBytes)) {
            return failure(LegalManifestIssueCode.LEGAL_TEXT_BOM_FORBIDDEN, safeLocation);
        }

        final String text;
        try {
            text = decodeUtf8(rawBytes);
        } catch (CharacterCodingException exception) {
            return failure(LegalManifestIssueCode.LEGAL_TEXT_UTF8_INVALID, safeLocation);
        }

        List<LegalManifestIssue> textualIssues = inspectText(text, safeLocation);
        if (!textualIssues.isEmpty()) {
            return LegalManifestValidation.failure(textualIssues);
        }
        return validateDigest(rawBytes, text, expectedSha256, safeLocation);
    }

    /**
     * Valida una afirmación ya representada como {@link String} y acredita sus bytes UTF-8 exactos.
     */
    public LegalManifestValidation<CanonicalText> validate(
            String text,
            String expectedSha256,
            String safeLocation) {
        requireSafeLocation(safeLocation);
        if (text == null) {
            return failure(LegalManifestIssueCode.LEGAL_TEXT_UTF8_INVALID, safeLocation);
        }

        List<LegalManifestIssue> textualIssues = inspectText(text, safeLocation);
        if (!textualIssues.isEmpty()) {
            return LegalManifestValidation.failure(textualIssues);
        }

        byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);
        return validateDigest(utf8Bytes, text, expectedSha256, safeLocation);
    }

    private LegalManifestValidation<CanonicalText> validateDigest(
            byte[] exactBytes,
            String text,
            String expectedSha256,
            String safeLocation) {
        byte[] expectedDigest = decodeExpectedDigest(expectedSha256);
        if (expectedDigest == null) {
            return failure(LegalManifestIssueCode.LEGAL_TEXT_DIGEST_MISMATCH, safeLocation);
        }

        final byte[] actualDigest;
        try {
            actualDigest = digestFactory.create().digest(exactBytes);
        } catch (NoSuchAlgorithmException | RuntimeException exception) {
            return failure(LegalManifestIssueCode.LEGAL_TEXT_VALIDATION_ERROR, safeLocation);
        }

        if (!MessageDigest.isEqual(actualDigest, expectedDigest)) {
            return failure(LegalManifestIssueCode.LEGAL_TEXT_DIGEST_MISMATCH, safeLocation);
        }
        return LegalManifestValidation.pass(new CanonicalText(
                exactBytes,
                text,
                toLowerHex(actualDigest)));
    }

    private static List<LegalManifestIssue> inspectText(
            String text,
            String safeLocation) {
        EnumSet<LegalManifestIssueCode> issueCodes = EnumSet.noneOf(
                LegalManifestIssueCode.class);
        if (text.startsWith("\uFEFF")) {
            issueCodes.add(LegalManifestIssueCode.LEGAL_TEXT_BOM_FORBIDDEN);
        }
        if (text.indexOf('\r') >= 0) {
            issueCodes.add(LegalManifestIssueCode.LEGAL_TEXT_CR_FORBIDDEN);
        }
        if (hasForbiddenControl(text)) {
            issueCodes.add(LegalManifestIssueCode.LEGAL_TEXT_CONTROL_FORBIDDEN);
        }
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            issueCodes.add(LegalManifestIssueCode.LEGAL_TEXT_NFC_REQUIRED);
        }
        if (hasUnpairedSurrogate(text)) {
            issueCodes.add(LegalManifestIssueCode.LEGAL_TEXT_SURROGATE_INVALID);
        }
        if (hasUnicodeNoncharacter(text)) {
            issueCodes.add(
                    LegalManifestIssueCode.LEGAL_TEXT_UNICODE_NONCHARACTER_FORBIDDEN);
        }

        if (issueCodes.isEmpty()) {
            return List.of();
        }
        List<LegalManifestIssue> issues = new ArrayList<>(issueCodes.size());
        for (LegalManifestIssueCode issueCode : issueCodes) {
            issues.add(LegalManifestIssue.at(issueCode, safeLocation));
        }
        return List.copyOf(issues);
    }

    private static String decodeUtf8(byte[] rawBytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBytes))
                .toString();
    }

    private static boolean hasUtf8Bom(byte[] rawBytes) {
        return rawBytes.length >= 3
                && (rawBytes[0] & 0xFF) == 0xEF
                && (rawBytes[1] & 0xFF) == 0xBB
                && (rawBytes[2] & 0xFF) == 0xBF;
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasForbiddenControl(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            boolean forbiddenC0 = codePoint >= 0x0000
                    && codePoint <= 0x001F
                    && codePoint != '\t'
                    && codePoint != '\n'
                    && codePoint != '\r';
            if (forbiddenC0
                    || codePoint == 0x007F
                    || (codePoint >= 0x0080 && codePoint <= 0x009F)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean hasUnicodeNoncharacter(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if ((codePoint >= 0xFDD0 && codePoint <= 0xFDEF)
                    || (codePoint & 0xFFFE) == 0xFFFE) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static byte[] decodeExpectedDigest(String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.length() != SHA_256_HEX_LENGTH) {
            return null;
        }

        byte[] digest = new byte[SHA_256_HEX_LENGTH / 2];
        for (int index = 0; index < digest.length; index++) {
            int high = lowerHexValue(expectedSha256.charAt(index * 2));
            int low = lowerHexValue(expectedSha256.charAt(index * 2 + 1));
            if (high < 0 || low < 0) {
                return null;
            }
            digest[index] = (byte) ((high << 4) | low);
        }
        return digest;
    }

    private static int lowerHexValue(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        return -1;
    }

    private static String toLowerHex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int unsigned = bytes[index] & 0xFF;
            result[index * 2] = alphabet[unsigned >>> 4];
            result[index * 2 + 1] = alphabet[unsigned & 0x0F];
        }
        return new String(result);
    }

    private static <T> LegalManifestValidation<T> failure(
            LegalManifestIssueCode issueCode,
            String safeLocation) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(issueCode, safeLocation));
    }

    private static void requireSafeLocation(String safeLocation) {
        Objects.requireNonNull(safeLocation, "safeLocation");
        // Construir un issue acredita la location también en el camino PASS sin conservarla.
        LegalManifestIssue.at(LegalManifestIssueCode.LEGAL_TEXT_VALIDATION_ERROR, safeLocation);
    }

    @FunctionalInterface
    interface DigestFactory {
        MessageDigest create() throws NoSuchAlgorithmException;
    }

    /**
     * Texto acreditado junto con sus bytes exactos y su digest hexadecimal en minúsculas.
     */
    public static final class CanonicalText {

        private final byte[] bytes;
        private final String text;
        private final String sha256;

        private CanonicalText(byte[] bytes, String text, String sha256) {
            this.bytes = Arrays.copyOf(bytes, bytes.length);
            this.text = Objects.requireNonNull(text, "text");
            this.sha256 = Objects.requireNonNull(sha256, "sha256");
        }

        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        public String text() {
            return text;
        }

        public String sha256() {
            return sha256;
        }
    }
}
