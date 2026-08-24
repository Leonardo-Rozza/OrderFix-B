package com.leonardorozza.mvgrreparacionesbackend.legal.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Punto único de carga del contrato JSON Schema v1 para publicaciones legales.
 *
 * <p>El recurso está congelado por tamaño y SHA-256. Se compila una sola vez desde classpath,
 * con Draft 2020-12 y assertions de formato activas. La resolución remota queda bloqueada para
 * que la validación sea determinista y funcione sin red.</p>
 */
public final class LegalManifestSchema {

    public static final String RESOURCE_PATH =
            "/legal/manifest/v1/publication-manifest.schema.json";
    public static final int EXPECTED_SIZE_BYTES = 10_547;
    public static final String EXPECTED_SHA256 =
            "f7a4ee17f53f5ed3f2613d894fa3a4f46896dfaaec0c80dab055e4320f036f8b";

    private static final Schema SCHEMA = loadVerifiedSchema();

    private LegalManifestSchema() {
    }

    public static Schema schema() {
        return SCHEMA;
    }

    public static List<Error> validate(JsonNode manifest) {
        return SCHEMA.validate(manifest);
    }

    private static Schema loadVerifiedSchema() {
        byte[] bytes = readResourceBytes();
        verifyIntegrity(bytes);
        return compileSchema(bytes);
    }

    static Schema compileSchema(byte[] schemaBytes) {
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                .preloadSchema(true)
                .build();

        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder
                        .schemaRegistryConfig(config)
                        .schemaLoader(loader -> loader
                                .fetchRemoteResources(false)
                                .block(location -> true)));

        Schema schema = registry.getSchema(
                new ByteArrayInputStream(schemaBytes),
                InputFormat.JSON);
        schema.initializeValidators();
        return schema;
    }

    private static byte[] readResourceBytes() {
        try (InputStream input = LegalManifestSchema.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("No se encontró el JSON Schema legal v1 en classpath");
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo leer el JSON Schema legal v1", exception);
        }
    }

    private static void verifyIntegrity(byte[] bytes) {
        if (bytes.length != EXPECTED_SIZE_BYTES) {
            throw new IllegalStateException("El tamaño del JSON Schema legal v1 no coincide");
        }

        String actualSha256 = HexFormat.of().formatHex(sha256(bytes));
        if (!EXPECTED_SHA256.equals(actualSha256)) {
            throw new IllegalStateException("El SHA-256 del JSON Schema legal v1 no coincide");
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
