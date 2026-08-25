package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Argumentos acreditados para el CLI interno del manifiesto legal.
 *
 * <p>El parser es deliberadamente cerrado: admite un comando estable y una única asignación
 * {@code --manifest=<path>}. Los rechazos producen issues constantes y nunca incorporan el valor
 * recibido.</p>
 */
public record LegalManifestArguments(Command command, Path manifestPath) {

    private static final String MANIFEST_PREFIX = "--manifest=";
    private static final String ISSUE_LOCATION = "cli/arguments";

    public LegalManifestArguments {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(manifestPath, "manifestPath");
    }

    /**
     * Valida la forma completa de los argumentos sin lanzar excepciones por input inválido.
     */
    public static LegalManifestValidation<LegalManifestArguments> parse(String[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return invalidArguments();
        }

        Command command = Command.fromExternalValue(arguments[0]);
        if (command == null) {
            return invalidArguments();
        }
        if (arguments.length == 1) {
            return manifestPathRequired();
        }
        if (arguments.length != 2) {
            return invalidArguments();
        }

        String manifestArgument = arguments[1];
        if (manifestArgument == null || !manifestArgument.startsWith(MANIFEST_PREFIX)) {
            return invalidArguments();
        }

        String pathValue = manifestArgument.substring(MANIFEST_PREFIX.length());
        if (pathValue.isBlank()) {
            return manifestPathRequired();
        }
        if (pathValue.startsWith("--")) {
            return invalidArguments();
        }

        try {
            return LegalManifestValidation.pass(
                    new LegalManifestArguments(command, Path.of(pathValue)));
        } catch (InvalidPathException invalidPath) {
            return invalidArguments();
        }
    }

    private static LegalManifestValidation<LegalManifestArguments> manifestPathRequired() {
        return failure(LegalManifestIssueCode.MANIFEST_PATH_REQUIRED);
    }

    private static LegalManifestValidation<LegalManifestArguments> invalidArguments() {
        return failure(LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
    }

    private static LegalManifestValidation<LegalManifestArguments> failure(
            LegalManifestIssueCode code) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(code, ISSUE_LOCATION));
    }

    /** Comandos externos estables aceptados por el CLI legal. */
    public enum Command {
        VALIDATE("validate"),
        DRY_RUN("dry-run");

        private final String externalValue;

        Command(String externalValue) {
            this.externalValue = externalValue;
        }

        public String externalValue() {
            return externalValue;
        }

        private static Command fromExternalValue(String candidate) {
            if (candidate == null) {
                return null;
            }
            for (Command command : values()) {
                if (command.externalValue.equals(candidate)) {
                    return command;
                }
            }
            return null;
        }
    }
}
