package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;

import java.util.Iterator;
import java.util.Objects;

/**
 * One-shot traversal of every public summary in a filter, before pagination. The caller supplies
 * an exclusively owned, unconsumed iterator in contractual order and owns its resource lifecycle.
 * Construction neither reads nor buffers rows. Context membership and one row per version must
 * be established by the reader; a summary alone cannot prove either property globally.
 */
public final class LegalDocumentCatalogProjection {

    private final ContextoLegal context;
    private final LocaleLegal locale;
    private Iterator<LegalDocumentSummary> documents;

    public LegalDocumentCatalogProjection(
            ContextoLegal context,
            LocaleLegal locale,
            Iterator<LegalDocumentSummary> documents) {
        this.context = context;
        this.locale = Objects.requireNonNull(locale, "locale");
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    /** Null means the catalog has no context filter. */
    public ContextoLegal context() {
        return context;
    }

    public LocaleLegal locale() {
        return locale;
    }

    /** A failed or completed traversal cannot later be mistaken for a fresh catalog. */
    synchronized Iterator<LegalDocumentSummary> claimDocuments() {
        if (documents == null) {
            throw new IllegalStateException("La proyección documental ya fue consumida");
        }
        Iterator<LegalDocumentSummary> claimed = documents;
        documents = null;
        return claimed;
    }
}
