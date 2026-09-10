package com.leonardorozza.mvgrreparacionesbackend.service.dto.cuenta;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Small, exact request shape, independent from the application's permissive DTO coercions. */
public record PersonalAccessExitRequest(String passwordActual, boolean confirmado) {
    private static final int MAX_BODY_BYTES = 4096;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(1000).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static PersonalAccessExitRequest read(InputStream body) {
        byte[] bytes = null;
        try {
            bytes = body.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw invalid();
            JsonNode value = JSON.readTree(bytes);
            if (value == null || !value.isObject() || value.size() != 2
                    || !value.has("passwordActual") || !value.has("confirmado")) throw invalid();
            JsonNode password = value.get("passwordActual");
            JsonNode confirmed = value.get("confirmado");
            if (!password.isTextual() || password.textValue().isBlank() || password.textValue().length() > 100
                    || !confirmed.isBoolean() || !confirmed.booleanValue()) throw invalid();
            return new PersonalAccessExitRequest(password.textValue(), true);
        } catch (IOException malformed) {
            // Parser diagnostics may contain body excerpts, so neither attach nor log the cause.
            throw invalid();
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static BadRequestException invalid() {
        return new BadRequestException("BAJA_ACCESO_INVALIDA", "Confirmá la baja e ingresá tu contraseña actual.");
    }

    @Override public String toString() { return "PersonalAccessExitRequest[redacted]"; }
}
