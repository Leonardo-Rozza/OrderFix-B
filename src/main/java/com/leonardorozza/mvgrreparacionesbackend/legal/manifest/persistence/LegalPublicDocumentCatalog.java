package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** One page and the revision/counts of the complete public document filter. */
public record LegalPublicDocumentCatalog(
        ContextoLegal context,
        LocaleLegal locale,
        String documentSetRevision,
        List<LegalDocumentSummary> documents,
        int page,
        int size,
        long totalElements,
        long totalPages
) {

    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Pattern REVISION = Pattern.compile("sha256:[0-9a-f]{64}");

    public LegalPublicDocumentCatalog {
        Objects.requireNonNull(locale, "locale");
        if (!REVISION.matcher(Objects.requireNonNull(documentSetRevision,
                "documentSetRevision")).matches()) {
            throw new IllegalArgumentException("Revisión documental inválida");
        }
        long offset = pageOffset(page, size);
        if (totalElements < 1 || totalElements > MAX_SAFE_INTEGER
                || totalPages != pagesFor(totalElements, size)) {
            throw new IllegalArgumentException("Conteos documentales inválidos");
        }
        long expectedSize = offset >= totalElements ? 0 : Math.min(size, totalElements - offset);
        if (Objects.requireNonNull(documents, "documents").size() != expectedSize) {
            throw new IllegalArgumentException("La página no coincide con sus conteos documentales");
        }
        documents = List.copyOf(documents);
        for (LegalDocumentSummary summary : documents) {
            if (summary.locale() != locale) {
                throw new IllegalArgumentException("La página mezcla locales documentales");
            }
        }
    }

    static long pageOffset(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Paginación documental inválida");
        }
        return Math.multiplyExact((long) page, size);
    }

    static long pagesFor(long totalElements, int size) {
        if (totalElements < 1 || totalElements > MAX_SAFE_INTEGER || size < 1 || size > 100) {
            throw new IllegalArgumentException("Conteos documentales inválidos");
        }
        // Avoid totalElements + size - 1, including at the JavaScript exact-integer boundary.
        return 1 + (totalElements - 1) / size;
    }
}
