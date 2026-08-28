package com.leonardorozza.mvgrreparacionesbackend.legal.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Single classpath loader for the immutable version 1 legal editorial-plan schema.
 *
 * <p>The resource is pinned by byte length and SHA-256, then compiled with the same offline Draft
 * 2020-12 registry and format assertions as the publication-manifest contract.</p>
 */
public final class LegalEditorialPlanSchema {

    public static final String RESOURCE_PATH =
            "/legal/editorial/v1/editorial-plan.schema.json";
    public static final int EXPECTED_SIZE_BYTES = 8_871;
    public static final String EXPECTED_SHA256 =
            "4c8ba93eb8e9065854e714af7b4f3155320d52d25b613364bc060f0a4c0a1268";

    private static final Schema SCHEMA = loadVerifiedSchema();

    private LegalEditorialPlanSchema() {
    }

    public static Schema schema() {
        return SCHEMA;
    }

    public static List<Error> validate(JsonNode plan) {
        return SCHEMA.validate(plan);
    }

    private static Schema loadVerifiedSchema() {
        byte[] bytes = readResourceBytes();
        verifyIntegrity(bytes);
        return compileSchema(bytes);
    }

    static Schema compileSchema(byte[] schemaBytes) {
        return LegalManifestSchema.compileSchema(schemaBytes);
    }

    private static byte[] readResourceBytes() {
        try (InputStream input = LegalEditorialPlanSchema.class
                .getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException(
                        "No se encontró el JSON Schema del plan editorial v1 en classpath");
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo leer el JSON Schema del plan editorial v1",
                    exception);
        }
    }

    private static void verifyIntegrity(byte[] bytes) {
        if (bytes.length != EXPECTED_SIZE_BYTES) {
            throw new IllegalStateException(
                    "El tamaño del JSON Schema del plan editorial v1 no coincide");
        }

        String actualSha256 = HexFormat.of().formatHex(sha256(bytes));
        if (!EXPECTED_SHA256.equals(actualSha256)) {
            throw new IllegalStateException(
                    "El SHA-256 del JSON Schema del plan editorial v1 no coincide");
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible en el runtime", exception);
        }
    }
}
