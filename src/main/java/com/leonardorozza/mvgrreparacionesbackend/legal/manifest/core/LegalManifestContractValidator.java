package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedReleaseReader.DocumentSource;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedReleaseReader.ReleaseDocuments;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalCoverageMatrix.ScopeCoverage;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.Contacts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.PublisherSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewRecord;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.DocumentPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.RequirementPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.ScopePlan;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies the cross-entry and content rules that JSON Schema cannot prove by itself.
 *
 * <p>This class is package-private on purpose. Only {@link LegalManifestValidator} turns its
 * normalized plan into the opaque value accepted by database operations.</p>
 */
final class LegalManifestContractValidator {

    private static final String MANIFEST_LOCATION = ConfinedReleaseReader.MANIFEST_FILENAME;
    private static final Pattern EMAIL_LOCAL_PART = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+");
    private static final Pattern DNS_LABEL = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?");
    private static final Set<String> NON_PUBLIC_DOMAIN_SUFFIXES = Set.of(
            "localhost",
            "local",
            "internal",
            "invalid",
            "test",
            "example",
            "home.arpa",
            "arpa",
            "onion",
            "alt",
            "lan",
            "home",
            "corp",
            "localdomain",
            "example.com",
            "example.org",
            "example.net");

    private final CanonicalTextValidator canonicalTextValidator;
    private final LegalMarkdownValidator markdownValidator;
    private final LegalEditorialMarkerValidator editorialMarkerValidator;

    LegalManifestContractValidator() {
        this(
                new CanonicalTextValidator(),
                new LegalMarkdownValidator(),
                new LegalEditorialMarkerValidator());
    }

    LegalManifestContractValidator(
            CanonicalTextValidator canonicalTextValidator,
            LegalMarkdownValidator markdownValidator,
            LegalEditorialMarkerValidator editorialMarkerValidator) {
        this.canonicalTextValidator = Objects.requireNonNull(
                canonicalTextValidator,
                "canonicalTextValidator");
        this.markdownValidator = Objects.requireNonNull(markdownValidator, "markdownValidator");
        this.editorialMarkerValidator = Objects.requireNonNull(
                editorialMarkerValidator,
                "editorialMarkerValidator");
    }

    LegalManifestValidation<LegalPublicationPlan> validate(
            LegalManifestParser.ParsedManifest parsedManifest,
            ReleaseDocuments releaseDocuments) {
        Objects.requireNonNull(parsedManifest, "parsedManifest");
        Objects.requireNonNull(releaseDocuments, "releaseDocuments");

        LegalManifestV1 manifest = parsedManifest.manifest();
        List<DocumentEntry> documents = manifest.documents();
        List<RequirementEntry> requirements = manifest.requirements();

        List<LegalManifestIssue> boundaryIssues = validateBoundaries(
                manifest,
                releaseDocuments);
        if (!boundaryIssues.isEmpty()) {
            return LegalManifestValidation.failure(boundaryIssues);
        }

        List<LegalManifestIssue> issues = new ArrayList<>();
        validateExpandedScopeMarkdownCapacity(
                requirements,
                releaseDocuments.documents(),
                issues);
        validatePublisher(manifest.publisherSnapshot(), issues);
        validateReview("review/legal", manifest.review().legal(), issues);
        validateReview("review/accounting", manifest.review().accounting(), issues);

        DocumentValidation documentValidation = validateDocuments(
                manifest,
                documents,
                releaseDocuments.documents(),
                issues);
        RequirementValidation requirementValidation = validateRequirements(
                manifest,
                requirements,
                documentValidation.documentsByKey(),
                issues);

        validateRequiredDocumentTypes(documentValidation.presentTypes(), issues);
        validateCoverage(
                requirements,
                documentValidation.uniqueDocumentsByKey(),
                issues);
        validateDocumentBindings(
                documents,
                requirementValidation.referencedDocumentKeys(),
                issues);

        if (!issues.isEmpty()) {
            return LegalManifestValidation.failure(issues);
        }

        return LegalManifestValidation.pass(new LegalPublicationPlan(
                manifest,
                parsedManifest.canonicalJson(),
                parsedManifest.manifestSha256(),
                documentValidation.plans(),
                buildScopes(manifest)));
    }

    private static List<LegalManifestIssue> validateBoundaries(
            LegalManifestV1 manifest,
            ReleaseDocuments releaseDocuments) {
        List<LegalManifestIssue> issues = new ArrayList<>();
        if (manifest.schemaVersion() != 1) {
            issues.add(issue(
                    LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID,
                    "schemaVersion"));
        }
        if (manifest.locale() != LocaleLegal.ES_AR) {
            issues.add(issue(
                    LegalManifestIssueCode.MANIFEST_LOCALE_UNSUPPORTED,
                    "locale"));
        }
        if (manifest.documents().size() > LegalManifestLimits.MAX_DOCUMENTS
                || manifest.requirements().size() > LegalManifestLimits.MAX_REQUIREMENTS) {
            issues.add(issue(
                    LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID,
                    MANIFEST_LOCATION));
            return issues;
        }
        for (int index = 0; index < manifest.requirements().size(); index++) {
            RequirementEntry requirement = manifest.requirements().get(index);
            if (requirement.documents().size()
                    > LegalManifestLimits.MAX_DOCUMENTS_PER_REQUIREMENT) {
                issues.add(issue(
                        LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID,
                        requirementLocation(index)));
            }
        }
        if (releaseDocuments.documents().size() != manifest.documents().size()
                || releaseDocuments.totalBytes()
                > LegalManifestLimits.MAX_TOTAL_MARKDOWN_BYTES) {
            issues.add(issue(
                    LegalManifestIssueCode.MANIFEST_VALIDATION_ERROR,
                    MANIFEST_LOCATION));
        }
        return issues;
    }

    private void validatePublisher(
            PublisherSnapshot publisher,
            List<LegalManifestIssue> issues) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("publisherSnapshot/legalName", publisher.legalName());
        values.put("publisherSnapshot/taxId", publisher.taxId());
        values.put("publisherSnapshot/legalAddress", publisher.legalAddress());
        values.put("publisherSnapshot/jurisdiction", publisher.jurisdiction());
        values.put("publisherSnapshot/businessHours", publisher.businessHours());

        Contacts contacts = publisher.contacts();
        values.put("publisherSnapshot/contacts/legalEmail", contacts.legalEmail());
        values.put("publisherSnapshot/contacts/privacyEmail", contacts.privacyEmail());
        values.put("publisherSnapshot/contacts/supportEmail", contacts.supportEmail());

        for (Map.Entry<String, String> value : values.entrySet()) {
            addIssues(editorialMarkerValidator.validate(value.getValue(), value.getKey()), issues);
        }

        requireVisible(publisher.legalName(), "publisherSnapshot/legalName", issues);
        requireVisible(publisher.legalAddress(), "publisherSnapshot/legalAddress", issues);
        requireVisible(publisher.jurisdiction(), "publisherSnapshot/jurisdiction", issues);
        requireVisible(publisher.businessHours(), "publisherSnapshot/businessHours", issues);

        validatePublicEmail(
                contacts.legalEmail(),
                "publisherSnapshot/contacts/legalEmail",
                issues);
        validatePublicEmail(
                contacts.privacyEmail(),
                "publisherSnapshot/contacts/privacyEmail",
                issues);
        validatePublicEmail(
                contacts.supportEmail(),
                "publisherSnapshot/contacts/supportEmail",
                issues);
    }

    private void validateReview(
            String location,
            ReviewRecord review,
            List<LegalManifestIssue> issues) {
        if (review.status() != ReviewStatus.APPROVED
                || review.reference() == null
                || review.reference().isBlank()
                || review.reviewedAt() == null) {
            issues.add(issue(
                    LegalManifestIssueCode.PROFESSIONAL_REVIEW_REQUIRED,
                    location));
        }
        if (review.reference() != null && !review.reference().isBlank()) {
            addIssues(
                    editorialMarkerValidator.validate(
                            review.reference(),
                            location + "/reference"),
                    issues);
        }
    }

    private DocumentValidation validateDocuments(
            LegalManifestV1 manifest,
            List<DocumentEntry> declarations,
            List<DocumentSource> sources,
            List<LegalManifestIssue> issues) {
        Map<String, List<DocumentEntry>> documentsByKey = new LinkedHashMap<>();
        Map<String, DocumentEntry> uniqueDocumentsByKey = new LinkedHashMap<>();
        Set<String> sourcesSeen = new HashSet<>();
        Set<DocumentIdentity> identitiesSeen = new HashSet<>();
        EnumSet<TipoDocumentoLegal> presentTypes = EnumSet.noneOf(TipoDocumentoLegal.class);
        List<DocumentPlan> plans = new ArrayList<>(declarations.size());

        for (int index = 0; index < declarations.size(); index++) {
            DocumentEntry declaration = declarations.get(index);
            DocumentSource source = sources.get(index);
            String location = documentLocation(index);
            documentsByKey.computeIfAbsent(declaration.key(), ignored -> new ArrayList<>())
                    .add(declaration);
            if (documentsByKey.get(declaration.key()).size() > 1) {
                issues.add(issue(
                        LegalManifestIssueCode.MANIFEST_DUPLICATE_DOCUMENT,
                        location));
            } else {
                uniqueDocumentsByKey.put(declaration.key(), declaration);
            }
            if (!identitiesSeen.add(new DocumentIdentity(
                    declaration.key(),
                    declaration.version()))) {
                issues.add(issue(
                        LegalManifestIssueCode.MANIFEST_DUPLICATE_DOCUMENT,
                        location));
            }
            if (!sourcesSeen.add(declaration.source())) {
                issues.add(issue(
                        LegalManifestIssueCode.DOCUMENT_SOURCE_DUPLICATE,
                        location + "/source"));
            }
            if (declaration.locale() != manifest.locale()) {
                issues.add(issue(
                        LegalManifestIssueCode.DOCUMENT_LOCALE_MISMATCH,
                        location + "/locale"));
            }
            if (declaration.contexts().isEmpty()) {
                issues.add(issue(
                        LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID,
                        location + "/contexts"));
            } else if (new HashSet<>(declaration.contexts()).size()
                    != declaration.contexts().size()) {
                issues.add(issue(
                        LegalManifestIssueCode.DOCUMENT_CONTEXT_DUPLICATE,
                        location + "/contexts"));
            }
            presentTypes.add(declaration.type());

            if (!declaration.key().equals(source.key())
                    || !declaration.source().equals(source.source())) {
                issues.add(issue(
                        LegalManifestIssueCode.MANIFEST_VALIDATION_ERROR,
                        location));
                continue;
            }

            LegalManifestValidation<CanonicalTextValidator.CanonicalText> canonical =
                    canonicalTextValidator.validate(
                            source.bytes(),
                            declaration.sha256(),
                            declaration.source());
            addIssues(canonical, issues);
            if (!canonical.passed()) {
                continue;
            }

            String markdown = canonical.value().orElseThrow().text();
            LegalManifestValidation<LegalMarkdownValidator.ValidatedMarkdown> markdownResult =
                    markdownValidator.validate(markdown, declaration.source());
            LegalManifestValidation<LegalEditorialMarkerValidator.CleanLegalText> editorialResult =
                    editorialMarkerValidator.validate(markdown, declaration.source());
            addIssues(markdownResult, issues);
            addIssues(editorialResult, issues);
            if (markdownResult.passed() && editorialResult.passed()) {
                plans.add(new DocumentPlan(
                        index,
                        declaration,
                        markdownResult.value().orElseThrow().title(),
                        markdown));
            }
        }

        Map<String, List<DocumentEntry>> immutableByKey = new LinkedHashMap<>();
        documentsByKey.forEach((key, values) -> immutableByKey.put(key, List.copyOf(values)));
        return new DocumentValidation(
                Map.copyOf(immutableByKey),
                Map.copyOf(uniqueDocumentsByKey),
                Set.copyOf(presentTypes),
                List.copyOf(plans));
    }

    private RequirementValidation validateRequirements(
            LegalManifestV1 manifest,
            List<RequirementEntry> requirements,
            Map<String, List<DocumentEntry>> documentsByKey,
            List<LegalManifestIssue> issues) {
        Set<String> keysSeen = new HashSet<>();
        Set<RequirementIdentity> identitiesSeen = new HashSet<>();
        Set<String> referencedDocumentKeys = new HashSet<>();

        for (int index = 0; index < requirements.size(); index++) {
            RequirementEntry requirement = requirements.get(index);
            String location = requirementLocation(index);
            if (!keysSeen.add(requirement.key())) {
                issues.add(issue(
                        LegalManifestIssueCode.MANIFEST_DUPLICATE_REQUIREMENT,
                        location));
            }
            if (!identitiesSeen.add(new RequirementIdentity(
                    requirement.key(),
                    requirement.version()))) {
                issues.add(issue(
                        LegalManifestIssueCode.MANIFEST_DUPLICATE_REQUIREMENT,
                        location));
            }
            if (requirement.roles().isEmpty()) {
                issues.add(issue(
                        LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID,
                        location + "/roles"));
            } else if (new HashSet<>(requirement.roles()).size()
                    != requirement.roles().size()) {
                issues.add(issue(
                        LegalManifestIssueCode.REQUIREMENT_ROLE_DUPLICATE,
                        location + "/roles"));
            }
            if (requirement.documents().isEmpty()) {
                issues.add(issue(
                        LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID,
                        location + "/documents"));
            } else if (new HashSet<>(requirement.documents()).size()
                    != requirement.documents().size()) {
                issues.add(issue(
                        LegalManifestIssueCode.REQUIREMENT_DOCUMENT_DUPLICATE,
                        location + "/documents"));
            }

            String statementLocation = location + "/statement";
            LegalManifestValidation<CanonicalTextValidator.CanonicalText> canonicalStatement =
                    canonicalTextValidator.validate(
                            requirement.statement(),
                            requirement.statementSha256(),
                            statementLocation);
            addIssues(canonicalStatement, issues);
            addIssues(
                    editorialMarkerValidator.validate(
                            requirement.statement(),
                            statementLocation),
                    issues);
            requireVisible(requirement.statement(), statementLocation, issues);

            for (int referenceIndex = 0;
                 referenceIndex < requirement.documents().size();
                 referenceIndex++) {
                String documentKey = requirement.documents().get(referenceIndex);
                String referenceLocation = location + "/documents/" + referenceIndex;
                List<DocumentEntry> resolved = documentsByKey.get(documentKey);
                if (resolved == null || resolved.isEmpty()) {
                    issues.add(issue(
                            LegalManifestIssueCode.REQUIREMENT_DOCUMENT_UNKNOWN,
                            referenceLocation));
                    continue;
                }
                referencedDocumentKeys.add(documentKey);
                if (resolved.size() != 1) {
                    issues.add(issue(
                            LegalManifestIssueCode.REQUIREMENT_DOCUMENT_AMBIGUOUS,
                            referenceLocation));
                    continue;
                }
                DocumentEntry document = resolved.getFirst();
                if (document.locale() != manifest.locale()) {
                    issues.add(issue(
                            LegalManifestIssueCode.DOCUMENT_LOCALE_MISMATCH,
                            referenceLocation));
                }
                if (!document.contexts().contains(requirement.context())) {
                    issues.add(issue(
                            LegalManifestIssueCode.REQUIREMENT_CONTEXT_MISMATCH,
                            referenceLocation));
                }
            }
        }
        return new RequirementValidation(Set.copyOf(referencedDocumentKeys));
    }

    private static void validateExpandedScopeMarkdownCapacity(
            List<RequirementEntry> requirements,
            List<DocumentSource> sources,
            List<LegalManifestIssue> issues) {
        Map<String, List<Long>> markdownBytesByDocumentKey = new LinkedHashMap<>();
        for (DocumentSource source : sources) {
            markdownBytesByDocumentKey
                    .computeIfAbsent(source.key(), ignored -> new ArrayList<>())
                    .add((long) source.bytes().length);
        }
        issues.addAll(validateExpandedScopeMarkdownCapacity(
                requirements,
                markdownBytesByDocumentKey));
    }

    static List<LegalManifestIssue> validateExpandedScopeMarkdownCapacity(
            List<RequirementEntry> requirements,
            Map<String, List<Long>> markdownBytesByDocumentKey) {
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(markdownBytesByDocumentKey, "markdownBytesByDocumentKey");

        Map<ScopeIdentity, Long> expandedBytesByScope = new LinkedHashMap<>();
        for (RequirementEntry requirement : requirements) {
            long requirementBytes = 0L;
            for (String documentKey : requirement.documents()) {
                List<Long> matchingDocuments = markdownBytesByDocumentKey.get(documentKey);
                if (matchingDocuments == null) {
                    continue;
                }
                for (Long documentBytes : matchingDocuments) {
                    requirementBytes = safeAddMarkdownBytes(
                            requirementBytes,
                            Objects.requireNonNull(documentBytes, "documentBytes"));
                }
            }

            for (AudienciaLegal audience : requirement.roles()) {
                ScopeIdentity scope = new ScopeIdentity(requirement.context(), audience);
                expandedBytesByScope.put(
                        scope,
                        safeAddMarkdownBytes(
                                expandedBytesByScope.getOrDefault(scope, 0L),
                                requirementBytes));
            }
        }

        List<LegalManifestIssue> issues = new ArrayList<>();
        for (Map.Entry<ScopeIdentity, Long> scope : expandedBytesByScope.entrySet()) {
            if (scope.getValue() > LegalManifestLimits.MAX_EXPANDED_SCOPE_MARKDOWN_BYTES) {
                issues.add(issue(
                        LegalManifestIssueCode.DOCUMENT_TOTAL_SIZE_LIMIT_EXCEEDED,
                        scopeLocation(scope.getKey())));
            }
        }
        return List.copyOf(issues);
    }

    private static long safeAddMarkdownBytes(long current, long increment) {
        if (current < 0L || increment < 0L) {
            throw new IllegalArgumentException("Los tamaños Markdown no pueden ser negativos");
        }
        if (current > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return current + increment;
    }

    private static void validateRequiredDocumentTypes(
            Set<TipoDocumentoLegal> presentTypes,
            List<LegalManifestIssue> issues) {
        for (TipoDocumentoLegal requiredType : LegalCoverageMatrix.documentTypes()) {
            if (!presentTypes.contains(requiredType)) {
                issues.add(issue(
                        LegalManifestIssueCode.REQUIRED_DOCUMENT_MISSING,
                        "coverage/types/" + requiredType.name()));
            }
        }
    }

    private static void validateCoverage(
            List<RequirementEntry> requirements,
            Map<String, DocumentEntry> documentsByKey,
            List<LegalManifestIssue> issues) {
        LegalCoverageMatrix.Coverage coverage = LegalCoverageMatrix.evaluate(
                requirements,
                documentsByKey);
        for (ScopeCoverage scope : coverage.scopes()) {
            String scopeLocation = "coverage/"
                    + scope.scope().context().name()
                    + "/"
                    + scope.scope().audience().name();
            if (!scope.hasRequiredRequirement()) {
                issues.add(issue(
                        LegalManifestIssueCode.REQUIRED_REQUIREMENT_MISSING,
                        scopeLocation));
            }
            for (TipoDocumentoLegal missingType : scope.missingDocumentTypes()) {
                issues.add(issue(
                        LegalManifestIssueCode.REQUIRED_REQUIREMENT_DOCUMENT_MISSING,
                        scopeLocation + "/" + missingType.name()));
            }
        }
    }

    private static void validateDocumentBindings(
            List<DocumentEntry> documents,
            Set<String> referencedDocumentKeys,
            List<LegalManifestIssue> issues) {
        for (int index = 0; index < documents.size(); index++) {
            if (!referencedDocumentKeys.contains(documents.get(index).key())) {
                issues.add(issue(
                        LegalManifestIssueCode.REQUIRED_DOCUMENT_UNBOUND,
                        documentLocation(index)));
            }
        }
    }

    private static List<ScopePlan> buildScopes(LegalManifestV1 manifest) {
        Map<ScopeIdentity, List<RequirementPlan>> scopes = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < manifest.requirements().size(); ordinal++) {
            RequirementEntry requirement = manifest.requirements().get(ordinal);
            RequirementPlan requirementPlan = new RequirementPlan(ordinal, requirement);
            for (AudienciaLegal audience : requirement.roles()) {
                ScopeIdentity identity = new ScopeIdentity(
                        requirement.context(),
                        audience);
                scopes.computeIfAbsent(identity, ignored -> new ArrayList<>())
                        .add(requirementPlan);
            }
        }

        List<ScopePlan> plans = new ArrayList<>(scopes.size());
        for (Map.Entry<ScopeIdentity, List<RequirementPlan>> scope : scopes.entrySet()) {
            plans.add(new ScopePlan(
                    manifest.locale(),
                    scope.getKey().context(),
                    scope.getKey().audience(),
                    scope.getValue()));
        }
        return List.copyOf(plans);
    }

    private static void validatePublicEmail(
            String value,
            String location,
            List<LegalManifestIssue> issues) {
        if (!isPublicEmail(value)) {
            issues.add(issue(
                    LegalManifestIssueCode.MANIFEST_CONTACTS_INVALID,
                    location));
        }
    }

    private static void requireVisible(
            String value,
            String location,
            List<LegalManifestIssue> issues) {
        if (!LegalVisibleText.isPublishable(value)) {
            issues.add(issue(LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID, location));
        }
    }

    static boolean isPublicEmail(String value) {
        int separator = value == null ? -1 : value.lastIndexOf('@');
        if (separator <= 0 || separator == value.length() - 1) {
            return false;
        }
        String localPart = value.substring(0, separator);
        String domain = value.substring(separator + 1)
                .toLowerCase(Locale.ROOT)
                .replaceFirst("\\.+$", "");
        if (localPart.length() > 64
                || localPart.startsWith(".")
                || localPart.endsWith(".")
                || localPart.contains("..")
                || !EMAIL_LOCAL_PART.matcher(localPart).matches()) {
            return false;
        }
        if (domain.isEmpty()
                || domain.length() > 253
                || domain.indexOf(':') >= 0
                || domain.matches("\\d+(?:\\.\\d+){3}")) {
            return false;
        }
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (label.isEmpty()
                    || label.length() > 63
                    || !DNS_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        if (labels[labels.length - 1].chars().allMatch(Character::isDigit)) {
            return false;
        }
        for (String suffix : NON_PUBLIC_DOMAIN_SUFFIXES) {
            if (domain.equals(suffix) || domain.endsWith("." + suffix)) {
                return false;
            }
        }
        return true;
    }

    private static void addIssues(
            LegalManifestValidation<?> validation,
            List<LegalManifestIssue> issues) {
        if (!validation.passed()) {
            issues.addAll(validation.issues());
        }
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }

    private static String documentLocation(int index) {
        return "documents/" + index;
    }

    private static String requirementLocation(int index) {
        return "requirements/" + index;
    }

    private static String scopeLocation(ScopeIdentity scope) {
        return "scopes/"
                + scope.context().name()
                + "/"
                + scope.audience().name();
    }

    private record DocumentIdentity(String key, String version) {
    }

    private record RequirementIdentity(String key, String version) {
    }

    private record ScopeIdentity(
            ContextoLegal context,
            AudienciaLegal audience) {
    }

    private record DocumentValidation(
            Map<String, List<DocumentEntry>> documentsByKey,
            Map<String, DocumentEntry> uniqueDocumentsByKey,
            Set<TipoDocumentoLegal> presentTypes,
            List<DocumentPlan> plans) {
    }

    private record RequirementValidation(Set<String> referencedDocumentKeys) {
    }
}
