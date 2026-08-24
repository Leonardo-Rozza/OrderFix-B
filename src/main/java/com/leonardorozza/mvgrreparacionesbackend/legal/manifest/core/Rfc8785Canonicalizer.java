package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Adaptador acotado de JCS: sólo acepta texto previamente acreditado por {@link StrictJsonReader}.
 * Su visibilidad de paquete reserva la invocación productiva al parser, después del schema.
 */
final class Rfc8785Canonicalizer {

    public LegalManifestValidation<CanonicalJson> canonicalize(
            StrictJsonReader.StrictJsonDocument document) {
        Objects.requireNonNull(document, "document");

        final byte[] canonicalUtf8;
        try {
            canonicalUtf8 = new JsonCanonicalizer(document.text()).getEncodedUTF8();
        } catch (IOException exception) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.MANIFEST_RFC8785_INVALID,
                    StrictJsonReader.DEFAULT_LOCATION));
        } catch (RuntimeException exception) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.MANIFEST_CANONICALIZATION_ERROR,
                    StrictJsonReader.DEFAULT_LOCATION));
        }

        try {
            return LegalManifestValidation.pass(new CanonicalJson(
                    canonicalUtf8,
                    HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(canonicalUtf8))));
        } catch (NoSuchAlgorithmException exception) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.MANIFEST_CANONICALIZATION_ERROR,
                    StrictJsonReader.DEFAULT_LOCATION));
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
