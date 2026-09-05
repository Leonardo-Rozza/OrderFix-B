package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;

import java.util.Objects;

/** Exact public version; the reader accredits the original Markdown before constructing it. */
public record LegalPublicDocumentVersion(LegalDocumentSummary summary, String markdown) {

    public LegalPublicDocumentVersion {
        Objects.requireNonNull(summary, "summary");
        if (Objects.requireNonNull(markdown, "markdown").isEmpty()) {
            throw new IllegalArgumentException("El documento público requiere contenido");
        }
    }
}
