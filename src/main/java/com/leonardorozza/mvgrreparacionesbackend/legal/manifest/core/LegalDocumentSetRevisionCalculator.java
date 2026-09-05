package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import java.util.Objects;

/** Calculates the public catalog revision independently of required-set revisions and paging. */
public final class LegalDocumentSetRevisionCalculator {

    private final Rfc8785Canonicalizer canonicalizer = new Rfc8785Canonicalizer();

    /**
     * Consumes the complete ordered catalog once and returns {@code sha256:<64-hex>}.
     * Empty, unordered or invalid input and iterator failures produce no revision. Resources
     * remain owned by the caller, including on failure. The digest uses constant auxiliary memory.
     */
    public String calculate(LegalDocumentCatalogProjection projection) {
        return "sha256:" + canonicalizer.canonicalize(Objects.requireNonNull(projection, "projection"));
    }

    /** Materializes bytes for small golden/equivalence tests only; also consumes the projection. */
    byte[] canonicalUtf8(LegalDocumentCatalogProjection projection) {
        return canonicalizer.canonicalUtf8(Objects.requireNonNull(projection, "projection"));
    }
}
