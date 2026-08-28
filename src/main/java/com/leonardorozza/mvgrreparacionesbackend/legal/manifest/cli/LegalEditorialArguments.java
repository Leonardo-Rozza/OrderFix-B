package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Closed argument contract for the initial editorial CLI commands. */
public record LegalEditorialArguments(
        Command command,
        Path manifestPath,
        LegalEditorialConfirmation confirmation) {

    private static final String MANIFEST_PREFIX = "--manifest=";
    private static final String CONFIRM_PUBLICATION_ID_PREFIX = "--confirm-publication-id=";
    private static final String CONFIRM_MANIFEST_SHA256_PREFIX =
            "--confirm-manifest-sha256=";
    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String ISSUE_LOCATION = "cli/editorial/arguments";

    public LegalEditorialArguments {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(manifestPath, "manifestPath");
        Objects.requireNonNull(confirmation, "confirmation");
    }

    /** Parses exactly one manifest and both release confirmations without echoing input. */
    public static LegalManifestValidation<LegalEditorialArguments> parse(String[] arguments) {
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
        if (pathValue.startsWith("--")
                || publicationId == null
                || publicationId.isBlank()
                || manifestSha256 == null
                || !LOWERCASE_SHA256.matcher(manifestSha256).matches()) {
            return invalidArguments();
        }

        try {
            return LegalManifestValidation.pass(new LegalEditorialArguments(
                    command,
                    Path.of(pathValue),
                    new LegalEditorialConfirmation(publicationId, manifestSha256)));
        } catch (InvalidPathException invalidPath) {
            return invalidArguments();
        }
    }

    @Override
    public String toString() {
        return "LegalEditorialArguments[command=" + command.externalValue + ", redacted]";
    }

    private static LegalManifestValidation<LegalEditorialArguments> manifestPathRequired() {
        return failure(LegalManifestIssueCode.MANIFEST_PATH_REQUIRED);
    }

    private static LegalManifestValidation<LegalEditorialArguments> invalidArguments() {
        return failure(LegalManifestIssueCode.CLI_ARGUMENTS_INVALID);
    }

    private static LegalManifestValidation<LegalEditorialArguments> failure(
            LegalManifestIssueCode code) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(code, ISSUE_LOCATION));
    }

    /** Initial case-sensitive editorial commands exposed by Corte 5. */
    public enum Command {
        READINESS("readiness", false),
        PLAN_PROMOTE("plan-promote", false),
        APPLY_PROMOTE("apply-promote", true);

        private final String externalValue;
        private final boolean mutating;

        Command(String externalValue, boolean mutating) {
            this.externalValue = externalValue;
            this.mutating = mutating;
        }

        public String externalValue() {
            return externalValue;
        }

        public boolean mutating() {
            return mutating;
        }

        static Command fromExternalValue(String candidate) {
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
