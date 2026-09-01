package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection.ScopeRevision;

import java.util.Objects;

/** Calculates the RFC 8785 semantic revision of a complete multi-context required set. */
public final class LegalRequiredSetAggregateRevisionCalculator {

    private static final String REVISION_PREFIX = "sha256:";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Rfc8785Canonicalizer canonicalizer;

    public LegalRequiredSetAggregateRevisionCalculator() {
        this(new Rfc8785Canonicalizer());
    }

    LegalRequiredSetAggregateRevisionCalculator(Rfc8785Canonicalizer canonicalizer) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    }

    /** Returns {@code sha256:<64-hex>} over the normalized semantic projection. */
    public String calculate(LegalRequiredSetAggregateProjection projection) {
        return REVISION_PREFIX + canonicalizer.canonicalize(
                Objects.requireNonNull(projection, "projection"));
    }

    /** Materializes canonical bytes only for golden/equivalence tests. */
    byte[] canonicalUtf8(LegalRequiredSetAggregateProjection projection) {
        return canonicalizer.canonicalUtf8(Objects.requireNonNull(projection, "projection"));
    }

    /** Readable pre-JCS projection used only by golden/equivalence tests. */
    String projectionJson(LegalRequiredSetAggregateProjection projection) {
        return project(Objects.requireNonNull(projection, "projection")).toString();
    }

    private static ObjectNode project(LegalRequiredSetAggregateProjection projection) {
        ObjectNode root = JSON.objectNode();
        root.put("revisionScheme", projection.revisionScheme().name());
        root.put("locale", projection.locale().getCodigo());
        root.put("audiencia", projection.audience().name());
        ArrayNode scopes = root.putArray("scopes");
        for (ScopeRevision scope : projection.scopes()) {
            ObjectNode item = scopes.addObject();
            item.put("contexto", scope.context().name());
            item.put("requiredSetRevision", scope.requiredSetRevision());
        }
        return root;
    }
}
