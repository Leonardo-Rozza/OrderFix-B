package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed argument contract for the editorial CLI commands exposed through Corte 6D. */
public record LegalEditorialArguments(
        Command command,
        Path manifestPath,
        Optional<Path> editorialPlanPath,
        LegalEditorialConfirmation confirmation,
        Optional<LegalEditorialPlanConfirmation> planConfirmation) {

    private static final String MANIFEST_PREFIX = "--manifest=";
    private static final String EDITORIAL_PLAN_PREFIX = "--editorial-plan=";
    private static final String CONFIRM_PUBLICATION_ID_PREFIX = "--confirm-publication-id=";
    private static final String CONFIRM_MANIFEST_SHA256_PREFIX =
            "--confirm-manifest-sha256=";
    private static final String CONFIRM_OPERATION_ID_PREFIX = "--confirm-operation-id=";
    private static final String CONFIRM_EDITORIAL_PLAN_SHA256_PREFIX =
            "--confirm-editorial-plan-sha256=";
    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String ISSUE_LOCATION = "cli/editorial/arguments";

    public LegalEditorialArguments {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(manifestPath, "manifestPath");
        editorialPlanPath = Objects.requireNonNull(editorialPlanPath, "editorialPlanPath");
        Objects.requireNonNull(confirmation, "confirmation");
        planConfirmation = Objects.requireNonNull(planConfirmation, "planConfirmation");
        if (editorialPlanPath.isPresent() != planConfirmation.isPresent()
                || command.requiresEditorialPlan() != editorialPlanPath.isPresent()) {
            throw new IllegalArgumentException(
                    "La ruta y confirmación del plan no coinciden con el comando editorial");
        }
    }

    public LegalEditorialArguments(
            Command command,
            Path manifestPath,
            LegalEditorialConfirmation confirmation) {
        this(command, manifestPath, Optional.empty(), confirmation, Optional.empty());
    }

    /** Parses the required artifact paths and literal confirmations without echoing input. */
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
        int expectedLength = command.requiresEditorialPlan() ? 7 : 4;
        if (arguments.length != expectedLength) {
            return invalidArguments();
        }

        String pathValue = null;
        String editorialPlanPathValue = null;
        String publicationId = null;
        String manifestSha256 = null;
        String operationId = null;
        String editorialPlanSha256 = null;
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
            if (argument.startsWith(EDITORIAL_PLAN_PREFIX)) {
                if (editorialPlanPathValue != null) {
                    return invalidArguments();
                }
                editorialPlanPathValue = argument.substring(EDITORIAL_PLAN_PREFIX.length());
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
            if (argument.startsWith(CONFIRM_OPERATION_ID_PREFIX)) {
                if (operationId != null) {
                    return invalidArguments();
                }
                operationId = argument.substring(CONFIRM_OPERATION_ID_PREFIX.length());
                continue;
            }
            if (argument.startsWith(CONFIRM_EDITORIAL_PLAN_SHA256_PREFIX)) {
                if (editorialPlanSha256 != null) {
                    return invalidArguments();
                }
                editorialPlanSha256 = argument.substring(
                        CONFIRM_EDITORIAL_PLAN_SHA256_PREFIX.length());
                continue;
            }
            return invalidArguments();
        }

        if (pathValue == null || pathValue.isBlank()) {
            return manifestPathRequired();
        }
        boolean requiresEditorialPlan = command.requiresEditorialPlan();
        if (requiresEditorialPlan
                && (editorialPlanPathValue == null || editorialPlanPathValue.isBlank())) {
            return editorialPlanPathRequired();
        }
        if (pathValue.startsWith("--")
                || publicationId == null
                || publicationId.isBlank()
                || manifestSha256 == null
                || !LOWERCASE_SHA256.matcher(manifestSha256).matches()
                || requiresEditorialPlan
                && (editorialPlanPathValue.startsWith("--")
                || operationId == null
                || editorialPlanSha256 == null
                || !LOWERCASE_SHA256.matcher(editorialPlanSha256).matches())
                || !requiresEditorialPlan
                && (editorialPlanPathValue != null
                || operationId != null
                || editorialPlanSha256 != null)) {
            return invalidArguments();
        }

        try {
            Optional<Path> editorialPlanPath = requiresEditorialPlan
                    ? Optional.of(Path.of(editorialPlanPathValue))
                    : Optional.empty();
            Optional<LegalEditorialPlanConfirmation> planConfirmation =
                    requiresEditorialPlan
                            ? Optional.of(new LegalEditorialPlanConfirmation(
                                    canonicalUuid(operationId),
                                    editorialPlanSha256))
                            : Optional.empty();
            return LegalManifestValidation.pass(new LegalEditorialArguments(
                    command,
                    Path.of(pathValue),
                    editorialPlanPath,
                    new LegalEditorialConfirmation(publicationId, manifestSha256),
                    planConfirmation));
        } catch (IllegalArgumentException invalidValue) {
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

    private static LegalManifestValidation<LegalEditorialArguments> editorialPlanPathRequired() {
        return failure(LegalManifestIssueCode.EDITORIAL_PLAN_PATH_REQUIRED);
    }

    private static UUID canonicalUuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("UUID no canónico");
        }
        return parsed;
    }

    private static LegalManifestValidation<LegalEditorialArguments> failure(
            LegalManifestIssueCode code) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(code, ISSUE_LOCATION));
    }

    /** Case-sensitive editorial commands exposed through Corte 6D. */
    public enum Command {
        READINESS("readiness", false, null),
        PLAN_PROMOTE("plan-promote", false, null),
        APPLY_PROMOTE("apply-promote", true, null),
        PLAN_REPLACE("plan-replace", false, OperationType.REPLACE),
        APPLY_REPLACE("apply-replace", true, OperationType.REPLACE);

        private final String externalValue;
        private final boolean mutating;
        private final OperationType editorialPlanOperationType;

        Command(
                String externalValue,
                boolean mutating,
                OperationType editorialPlanOperationType) {
            this.externalValue = externalValue;
            this.mutating = mutating;
            this.editorialPlanOperationType = editorialPlanOperationType;
        }

        public String externalValue() {
            return externalValue;
        }

        public boolean mutating() {
            return mutating;
        }

        public boolean requiresEditorialPlan() {
            return editorialPlanOperationType != null;
        }

        Optional<OperationType> editorialPlanOperationType() {
            return Optional.ofNullable(editorialPlanOperationType);
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
