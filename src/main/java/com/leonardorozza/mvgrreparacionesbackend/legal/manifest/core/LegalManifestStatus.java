package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

/**
 * Estado estable de una validación legal y su exit code de CLI.
 */
public enum LegalManifestStatus {
    PASS(0, 0),
    BLOCKED(2, 1),
    ERROR(3, 2);

    private final int exitCode;
    private final int precedence;

    LegalManifestStatus(int exitCode, int precedence) {
        this.exitCode = exitCode;
        this.precedence = precedence;
    }

    public int exitCode() {
        return exitCode;
    }

    int precedence() {
        return precedence;
    }

    public boolean passed() {
        return this == PASS;
    }

    public static LegalManifestStatus mostSevere(
            LegalManifestStatus left,
            LegalManifestStatus right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("Los estados no pueden ser null");
        }
        return left.precedence >= right.precedence ? left : right;
    }
}
