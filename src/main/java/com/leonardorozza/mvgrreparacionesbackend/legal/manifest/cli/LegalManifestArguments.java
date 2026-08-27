package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Argumentos acreditados para el CLI interno del manifiesto legal.
 *
 * <p>El parser es deliberadamente cerrado. {@code validate} y {@code dry-run} conservan una
 * única asignación {@code --manifest=<path>}; {@code import} exige además las dos confirmaciones
 * exactas del release. Los rechazos producen issues constantes y nunca incorporan valores
 * recibidos.</p>
 */
public record LegalManifestArguments(
        Command command,
        Path manifestPath,
        Optional<LegalManifestImportConfirmation> importConfirmation) {

    private static final String MANIFEST_PREFIX = "--manifest=";
    private static final String CONFIRM_PUBLICATION_ID_PREFIX = "--confirm-publication-id=";
    private static final String CONFIRM_MANIFEST_SHA256_PREFIX =
            "--confirm-manifest-sha256=";
    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String ISSUE_LOCATION = "cli/arguments";

    public LegalManifestArguments {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(manifestPath, "manifestPath");
        importConfirmation = Objects.requireNonNull(importConfirmation, "importConfirmation");
        if ((command == Command.IMPORT) != importConfirmation.isPresent()) {
            throw new IllegalArgumentException(
                    "El comando import exige confirmaciones y los comandos v1 no las admiten");
        }
    }

    /** Constructor compatible con los comandos v1 que no aceptan confirmaciones. */
    public LegalManifestArguments(Command command, Path manifestPath) {
        this(command, manifestPath, Optional.empty());
    }

    /** Valida la forma completa sin lanzar excepciones por input inválido. */
    public static LegalManifestValidation<LegalManifestArguments> parse(String[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return invalidArguments();
        }

        Command command = Command.fromExternalValue(arguments[0]);
        if (command == null) {
            return invalidArguments();
        }
        if (command == Command.IMPORT) {
            return parseImport(arguments);
        }
        return parseV1(command, arguments);
    }

    private static LegalManifestValidation<LegalManifestArguments> parseV1(
            Command command,
            String[] arguments) {
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
        return buildV1(command, pathValue);
    }

    private static LegalManifestValidation<LegalManifestArguments> parseImport(
            String[] arguments) {
        if (arguments.length == 1) {
            return manifestPathRequired();
        }
        if (arguments.length != 4) {
            return invalidArguments();
        }

        String pathValue = null;
        String publicationId = null;
        String manifestSha256 = null;
        for (int index = 1; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument == null) {
                return invalidArguments();
            }
            if (argument.startsWith(MANIFEST_PREFIX)) {
                if (pathValue != null) {
                    return invalidArguments();
                }
                pathValue = argument.substring(MANIFEST_PREFIX.length());
                continue;
            }
            if (argument.startsWith(CONFIRM_PUBLICATION_ID_PREFIX)) {
                if (publicationId != null) {
                    return invalidArguments();
                }
                publicationId = argument.substring(CONFIRM_PUBLICATION_ID_PREFIX.length());
                continue;
            }
            if (argument.startsWith(CONFIRM_MANIFEST_SHA256_PREFIX)) {
                if (manifestSha256 != null) {
                    return invalidArguments();
                }
                manifestSha256 = argument.substring(CONFIRM_MANIFEST_SHA256_PREFIX.length());
                continue;
            }
            return invalidArguments();
        }

        if (pathValue == null || pathValue.isBlank()) {
            return manifestPathRequired();
        }
        if (publicationId == null
                || publicationId.isBlank()
                || manifestSha256 == null
                || !LOWERCASE_SHA256.matcher(manifestSha256).matches()) {
            return invalidArguments();
        }

        try {
            if (pathValue.startsWith("--")) {
                return invalidArguments();
            }
            return LegalManifestValidation.pass(new LegalManifestArguments(
                    Command.IMPORT,
                    Path.of(pathValue),
                    Optional.of(new LegalManifestImportConfirmation(
                            publicationId,
                            manifestSha256))));
        } catch (InvalidPathException invalidPath) {
            return invalidArguments();
        }
    }

    private static LegalManifestValidation<LegalManifestArguments> buildV1(
            Command command,
            String pathValue) {
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
        DRY_RUN("dry-run"),
        IMPORT("import");

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
