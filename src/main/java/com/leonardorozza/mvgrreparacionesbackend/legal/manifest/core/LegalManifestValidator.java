package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedReleaseReader.ManifestSource;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedReleaseReader.ReleaseDocuments;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan;

import java.nio.file.Path;
import java.util.Objects;

/** Single public entry point for complete static validation of a local legal release. */
public final class LegalManifestValidator {

    private static final String MANIFEST_LOCATION = ConfinedReleaseReader.MANIFEST_FILENAME;

    private final ConfinedReleaseReader releaseReader;
    private final LegalManifestParser manifestParser;
    private final LegalManifestContractValidator contractValidator;

    public LegalManifestValidator() {
        this(
                new ConfinedReleaseReader(),
                new LegalManifestParser(),
                new LegalManifestContractValidator());
    }

    LegalManifestValidator(
            ConfinedReleaseReader releaseReader,
            LegalManifestParser manifestParser,
            LegalManifestContractValidator contractValidator) {
        this.releaseReader = Objects.requireNonNull(releaseReader, "releaseReader");
        this.manifestParser = Objects.requireNonNull(manifestParser, "manifestParser");
        this.contractValidator = Objects.requireNonNull(contractValidator, "contractValidator");
    }

    /** Validates bytes, schema, paths, Markdown, references and the coverage matrix. */
    public LegalManifestValidation<ValidatedRelease> validate(Path manifestPath) {
        try {
            LegalManifestValidation<ManifestSource> sourceResult =
                    releaseReader.readManifest(manifestPath);
            if (!sourceResult.passed()) {
                return sourceResult.asFailure();
            }
            ManifestSource source = sourceResult.value().orElseThrow();

            LegalManifestValidation<LegalManifestParser.ParsedManifest> parsedResult =
                    manifestParser.parse(source.bytes());
            if (!parsedResult.passed()) {
                return parsedResult.asFailure();
            }
            LegalManifestParser.ParsedManifest parsed = parsedResult.value().orElseThrow();

            LegalManifestValidation<ReleaseDocuments> documentsResult =
                    releaseReader.readDocuments(source, parsed.manifest());
            if (!documentsResult.passed()) {
                return documentsResult.asFailure();
            }

            LegalManifestValidation<LegalPublicationPlan> contractResult =
                    contractValidator.validate(
                            parsed,
                            documentsResult.value().orElseThrow());
            if (!contractResult.passed()) {
                return contractResult.asFailure();
            }
            return LegalManifestValidation.pass(new ValidatedRelease(
                    contractResult.value().orElseThrow()));
        } catch (RuntimeException | LinkageError exception) {
            return LegalManifestValidation.failure(LegalManifestIssue.at(
                    LegalManifestIssueCode.MANIFEST_VALIDATION_ERROR,
                    MANIFEST_LOCATION));
        }
    }

    /**
     * Opaque accreditation token. Database operations must accept this type instead of a directly
     * constructible manifest or plan DTO.
     */
    public static final class ValidatedRelease {

        private final LegalPublicationPlan plan;

        private ValidatedRelease(LegalPublicationPlan plan) {
            this.plan = Objects.requireNonNull(plan, "plan");
        }

        public LegalPublicationPlan plan() {
            return plan;
        }

        public int documentCount() {
            return plan.documentCount();
        }

        public int requirementCount() {
            return plan.requirementCount();
        }

        public int scopeCount() {
            return plan.scopeCount();
        }
    }
}
