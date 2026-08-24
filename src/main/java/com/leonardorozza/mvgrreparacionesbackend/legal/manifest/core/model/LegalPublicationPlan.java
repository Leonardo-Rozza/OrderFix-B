package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Validated, immutable input consumed by the database dry-run boundary.
 */
public record LegalPublicationPlan(
        LegalManifestV1 manifest,
        String canonicalJson,
        String manifestSha256,
        List<DocumentPlan> documents,
        List<ScopePlan> scopes
) {

    public LegalPublicationPlan {
        manifest = Objects.requireNonNull(manifest, "manifest");
        canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
        manifestSha256 = Objects.requireNonNull(manifestSha256, "manifestSha256");
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
    }

    public int documentCount() {
        return documents.size();
    }

    public int requirementCount() {
        return manifest.requirements().size();
    }

    public int scopeCount() {
        return scopes.size();
    }

    public Optional<DocumentPlan> documentByKey(String key) {
        Objects.requireNonNull(key, "key");
        return documents.stream()
                .filter(document -> document.declaration().key().equals(key))
                .findFirst();
    }

    public record DocumentPlan(
            DocumentEntry declaration,
            String title,
            String markdown
    ) {
        public DocumentPlan {
            declaration = Objects.requireNonNull(declaration, "declaration");
            title = Objects.requireNonNull(title, "title");
            markdown = Objects.requireNonNull(markdown, "markdown");
        }
    }

    public record ScopePlan(
            LocaleLegal locale,
            ContextoLegal context,
            AudienciaLegal audience,
            List<RequirementEntry> requirements
    ) {
        public ScopePlan {
            locale = Objects.requireNonNull(locale, "locale");
            context = Objects.requireNonNull(context, "context");
            audience = Objects.requireNonNull(audience, "audience");
            requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        }
    }
}
