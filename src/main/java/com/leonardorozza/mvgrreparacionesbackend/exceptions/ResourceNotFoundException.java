package com.leonardorozza.mvgrreparacionesbackend.exceptions;

public class ResourceNotFoundException extends RuntimeException {

    private final String code;

    public ResourceNotFoundException(String message) {
        this(null, message);
    }

    public ResourceNotFoundException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? null : code.trim();
    }

    public String getCode() {
        return code;
    }
}
