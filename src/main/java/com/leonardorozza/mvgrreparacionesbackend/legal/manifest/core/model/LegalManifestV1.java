package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Immutable representation of the version 1 publication manifest contract.
 * Contract validation belongs to the validator, not to these value objects.
 */
public record LegalManifestV1(
        String $schema,
        int schemaVersion,
        String publicationId,
        LocaleLegal locale,
        PublisherSnapshot publisherSnapshot,
        Review review,
        List<DocumentEntry> documents,
        List<RequirementEntry> requirements
) {

    public LegalManifestV1 {
        publicationId = Objects.requireNonNull(publicationId, "publicationId");
        locale = Objects.requireNonNull(locale, "locale");
        publisherSnapshot = Objects.requireNonNull(publisherSnapshot, "publisherSnapshot");
        review = Objects.requireNonNull(review, "review");
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
    }

    public record PublisherSnapshot(
            String legalName,
            String taxId,
            String legalAddress,
            String jurisdiction,
            String businessHours,
            Contacts contacts
    ) {
        public PublisherSnapshot {
            legalName = Objects.requireNonNull(legalName, "legalName");
            taxId = Objects.requireNonNull(taxId, "taxId");
            legalAddress = Objects.requireNonNull(legalAddress, "legalAddress");
            jurisdiction = Objects.requireNonNull(jurisdiction, "jurisdiction");
            businessHours = Objects.requireNonNull(businessHours, "businessHours");
            contacts = Objects.requireNonNull(contacts, "contacts");
        }
    }

    public record Contacts(
            String legalEmail,
            String privacyEmail,
            String supportEmail
    ) {
        public Contacts {
            legalEmail = Objects.requireNonNull(legalEmail, "legalEmail");
            privacyEmail = Objects.requireNonNull(privacyEmail, "privacyEmail");
            supportEmail = Objects.requireNonNull(supportEmail, "supportEmail");
        }
    }

    public record Review(
            ReviewRecord legal,
            ReviewRecord accounting
    ) {
        public Review {
            legal = Objects.requireNonNull(legal, "legal");
            accounting = Objects.requireNonNull(accounting, "accounting");
        }
    }

    public record ReviewRecord(
            ReviewStatus status,
            String reference,
            OffsetDateTime reviewedAt
    ) {
        public ReviewRecord {
            status = Objects.requireNonNull(status, "status");
        }
    }

    public enum ReviewStatus {
        PENDING,
        APPROVED
    }

    public record DocumentEntry(
            String key,
            TipoDocumentoLegal type,
            String version,
            LocaleLegal locale,
            String source,
            String sha256,
            OffsetDateTime effectiveAt,
            List<ContextoLegal> contexts,
            boolean requiresReacceptance
    ) {
        public DocumentEntry {
            key = Objects.requireNonNull(key, "key");
            type = Objects.requireNonNull(type, "type");
            version = Objects.requireNonNull(version, "version");
            locale = Objects.requireNonNull(locale, "locale");
            source = Objects.requireNonNull(source, "source");
            sha256 = Objects.requireNonNull(sha256, "sha256");
            effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
            contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
        }
    }

    public record RequirementEntry(
            String key,
            String version,
            ContextoLegal context,
            List<AudienciaLegal> roles,
            TipoActoLegal actType,
            String statement,
            String statementSha256,
            List<String> documents,
            boolean required,
            boolean requiresReacceptance
    ) {
        public RequirementEntry {
            key = Objects.requireNonNull(key, "key");
            version = Objects.requireNonNull(version, "version");
            context = Objects.requireNonNull(context, "context");
            roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
            actType = Objects.requireNonNull(actType, "actType");
            statement = Objects.requireNonNull(statement, "statement");
            statementSha256 = Objects.requireNonNull(statementSha256, "statementSha256");
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        }
    }
}
