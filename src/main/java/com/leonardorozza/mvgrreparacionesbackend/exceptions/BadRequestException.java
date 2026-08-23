package com.leonardorozza.mvgrreparacionesbackend.exceptions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class BadRequestException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public BadRequestException(String message) {
        this(null, message, Map.of());
    }

    public BadRequestException(String code, String message) {
        this(code, message, Map.of());
    }

    public BadRequestException(String code, String message, Map<String, ?> details) {
        super(message);
        this.code = normalizeCode(code);
        this.details = immutableCopy(details);
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    private static String normalizeCode(String code) {
        return code == null || code.isBlank() ? null : code.trim();
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach(copy::put);
        return Collections.unmodifiableMap(copy);
    }
}
