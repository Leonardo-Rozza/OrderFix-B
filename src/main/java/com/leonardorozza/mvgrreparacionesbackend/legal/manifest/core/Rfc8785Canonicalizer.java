package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.erdtman.jcs.JsonCanonicalizer;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Adaptador acotado de JCS: acepta JSON externo sólo después de {@link StrictJsonReader} y la
 * proyección interna tipada del conjunto requerido. No expone entradas de texto, bytes o árboles
 * JSON genéricos.
 */
final class Rfc8785Canonicalizer {

    private static final char[] LOWER_HEXADECIMAL = "0123456789abcdef".toCharArray();

    public LegalManifestValidation<CanonicalJson> canonicalize(
            StrictJsonReader.StrictJsonDocument document) {
        Objects.requireNonNull(document, "document");

        try {
            return LegalManifestValidation.pass(canonicalizeText(document.text()));
        } catch (IOException exception) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.MANIFEST_RFC8785_INVALID,
                    StrictJsonReader.DEFAULT_LOCATION));
        } catch (RuntimeException exception) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.MANIFEST_CANONICALIZATION_ERROR,
                    StrictJsonReader.DEFAULT_LOCATION));
        }
    }

    /**
     * Hashes the typed projection while emitting its RFC 8785 representation directly. The
     * projection contains only strings, booleans, arrays and fixed object keys, so no generic JSON
     * tree or full intermediate document is required.
     */
    String canonicalize(LegalRequiredSetProjection projection) {
        LegalRequiredSetProjection required = Objects.requireNonNull(projection, "projection");
        LegalRequiredSetRevisionCalculator.requireCanonicalizationCapacity(required);
        MessageDigest digest = sha256Digest();
        try (BufferedOutputStream output = new BufferedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            writeCanonicalProjection(required, output);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo canonicalizar la proyección legal interna", exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Materializes canonical bytes only for golden/equivalence tests, never for persistence. */
    byte[] canonicalUtf8(LegalRequiredSetProjection projection) {
        LegalRequiredSetProjection required = Objects.requireNonNull(projection, "projection");
        LegalRequiredSetRevisionCalculator.requireCanonicalizationCapacity(required);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BufferedOutputStream output = new BufferedOutputStream(bytes)) {
            writeCanonicalProjection(required, output);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo materializar la proyección legal canónica", exception);
        }
        return bytes.toByteArray();
    }

    private static CanonicalJson canonicalizeText(String json) throws IOException {
        byte[] canonicalUtf8 = new JsonCanonicalizer(json).getEncodedUTF8();
        return new CanonicalJson(
                canonicalUtf8,
                HexFormat.of().formatHex(sha256Digest().digest(canonicalUtf8)));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
    }

    private static void writeCanonicalProjection(
            LegalRequiredSetProjection projection,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"contexto\":");
        writeJsonString(output, projection.context().name());
        writeAscii(output, ",\"locale\":");
        writeJsonString(output, projection.locale().getCodigo());
        writeAscii(output, ",\"requisitos\":[");
        boolean firstRequirement = true;
        for (LegalRequiredSetProjection.RequirementProjection requirement
                : projection.requirements()) {
            if (!firstRequirement) {
                output.write(',');
            }
            firstRequirement = false;
            writeCanonicalRequirement(requirement, output);
        }
        writeAscii(output, "]}");
    }

    private static void writeCanonicalRequirement(
            LegalRequiredSetProjection.RequirementProjection requirement,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"afirmacion\":");
        writeJsonString(output, requirement.statement());
        writeAscii(output, ",\"afirmacionSha256\":");
        writeJsonString(output, requirement.statementSha256());
        writeAscii(output, ",\"contexto\":");
        writeJsonString(output, requirement.context().name());
        writeAscii(output, ",\"documentos\":[");
        boolean firstDocument = true;
        for (LegalRequiredSetProjection.DocumentProjection document
                : requirement.documents()) {
            if (!firstDocument) {
                output.write(',');
            }
            firstDocument = false;
            writeCanonicalDocument(document, output);
        }
        writeAscii(output, "],\"id\":");
        writeJsonString(output, requirement.versionId().toString());
        writeAscii(output, ",\"requerido\":");
        writeAscii(output, requirement.required() ? "true" : "false");
        writeAscii(output, ",\"tipoActo\":");
        writeJsonString(output, requirement.actType().name());
        output.write('}');
    }

    private static void writeCanonicalDocument(
            LegalRequiredSetProjection.DocumentProjection document,
            OutputStream output) throws IOException {
        writeAscii(output, "{\"contenidoMarkdown\":");
        writeJsonString(output, document.markdown());
        writeAscii(output, ",\"estado\":\"VIGENTE\",\"id\":");
        writeJsonString(output, document.versionId().toString());
        writeAscii(output, ",\"locale\":");
        writeJsonString(output, document.locale().getCodigo());
        writeAscii(output, ",\"sha256\":");
        writeJsonString(output, document.sha256());
        writeAscii(output, ",\"tipo\":");
        writeJsonString(output, document.type().name());
        writeAscii(output, ",\"titulo\":");
        writeJsonString(output, document.title());
        writeAscii(output, ",\"version\":");
        writeJsonString(output, document.version());
        writeAscii(output, ",\"vigenteDesde\":");
        writeJsonString(
                output,
                LegalRequiredSetRevisionCalculator.utcInstant(
                        document.effectiveAt().toInstant()));
        output.write('}');
    }

    private static void writeJsonString(OutputStream output, String value) throws IOException {
        output.write('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> writeAscii(output, "\\\"");
                case '\\' -> writeAscii(output, "\\\\");
                case '\b' -> writeAscii(output, "\\b");
                case '\t' -> writeAscii(output, "\\t");
                case '\n' -> writeAscii(output, "\\n");
                case '\f' -> writeAscii(output, "\\f");
                case '\r' -> writeAscii(output, "\\r");
                default -> {
                    if (current < 0x20) {
                        writeUnicodeControl(output, current);
                    } else if (Character.isHighSurrogate(current)) {
                        if (index + 1 >= value.length()
                                || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new IllegalArgumentException(
                                    "La proyección legal contiene Unicode inválido");
                        }
                        writeUtf8(output, Character.toCodePoint(
                                current,
                                value.charAt(++index)));
                    } else if (Character.isLowSurrogate(current)) {
                        throw new IllegalArgumentException(
                                "La proyección legal contiene Unicode inválido");
                    } else {
                        writeUtf8(output, current);
                    }
                }
            }
        }
        output.write('"');
    }

    private static void writeUnicodeControl(OutputStream output, char value) throws IOException {
        writeAscii(output, "\\u00");
        output.write(LOWER_HEXADECIMAL[(value >>> 4) & 0x0f]);
        output.write(LOWER_HEXADECIMAL[value & 0x0f]);
    }

    private static void writeUtf8(OutputStream output, int codePoint) throws IOException {
        if (codePoint <= 0x7f) {
            output.write(codePoint);
        } else if (codePoint <= 0x7ff) {
            output.write(0xc0 | (codePoint >>> 6));
            output.write(0x80 | (codePoint & 0x3f));
        } else if (codePoint <= 0xffff) {
            output.write(0xe0 | (codePoint >>> 12));
            output.write(0x80 | ((codePoint >>> 6) & 0x3f));
            output.write(0x80 | (codePoint & 0x3f));
        } else {
            output.write(0xf0 | (codePoint >>> 18));
            output.write(0x80 | ((codePoint >>> 12) & 0x3f));
            output.write(0x80 | ((codePoint >>> 6) & 0x3f));
            output.write(0x80 | (codePoint & 0x3f));
        }
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        for (int index = 0; index < value.length(); index++) {
            output.write(value.charAt(index));
        }
    }

    /**
     * Bytes canónicos y su identidad SHA-256. Los bytes se copian al entrar y al salir.
     */
    public static final class CanonicalJson {

        private final byte[] utf8;
        private final String sha256;

        private CanonicalJson(byte[] utf8, String sha256) {
            this.utf8 = Objects.requireNonNull(utf8, "utf8").clone();
            this.sha256 = Objects.requireNonNull(sha256, "sha256");
        }

        public byte[] utf8() {
            return utf8.clone();
        }

        public String sha256() {
            return sha256;
        }

        public int sizeBytes() {
            return utf8.length;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CanonicalJson canonicalJson)) {
                return false;
            }
            return sha256.equals(canonicalJson.sha256)
                    && Arrays.equals(utf8, canonicalJson.utf8);
        }

        @Override
        public int hashCode() {
            return 31 * sha256.hashCode() + Arrays.hashCode(utf8);
        }

        @Override
        public String toString() {
            return "CanonicalJson[sizeBytes=" + utf8.length + ", sha256=" + sha256 + ']';
        }
    }
}
