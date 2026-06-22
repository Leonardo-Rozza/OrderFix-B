package com.leonardorozza.mvgrreparacionesbackend.exceptions;

/**
 * Conflicto con el estado actual del recurso (HTTP 409): la operación es válida
 * en sí, pero no se puede aplicar dado cómo está el recurso ahora
 * (ej: una transición de estado no permitida).
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
