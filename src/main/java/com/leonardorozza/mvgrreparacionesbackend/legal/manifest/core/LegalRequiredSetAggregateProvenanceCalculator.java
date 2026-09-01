package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance.ScopeOrigin;

import java.util.Objects;

/** Calculates the internal RFC 8785 fingerprint of aggregate physical provenance. */
public final class LegalRequiredSetAggregateProvenanceCalculator {

    private static final String FINGERPRINT_PREFIX = "sha256:";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Rfc8785Canonicalizer canonicalizer;

    public LegalRequiredSetAggregateProvenanceCalculator() {
        this(new Rfc8785Canonicalizer());
    }

    LegalRequiredSetAggregateProvenanceCalculator(Rfc8785Canonicalizer canonicalizer) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    }

    /** Returns {@code sha256:<64-hex>} over the normalized internal provenance. */
    public String calculate(LegalRequiredSetAggregateProvenance provenance) {
        return FINGERPRINT_PREFIX + canonicalizer.canonicalize(
                Objects.requireNonNull(provenance, "provenance"));
    }

    /** Materializes canonical bytes only for golden/equivalence tests. */
    byte[] canonicalUtf8(LegalRequiredSetAggregateProvenance provenance) {
        return canonicalizer.canonicalUtf8(Objects.requireNonNull(provenance, "provenance"));
    }

    /** Readable pre-JCS projection used only by golden/equivalence tests. */
    String projectionJson(LegalRequiredSetAggregateProvenance provenance) {
        return project(Objects.requireNonNull(provenance, "provenance")).toString();
    }

    private static ObjectNode project(LegalRequiredSetAggregateProvenance provenance) {
        ObjectNode root = JSON.objectNode();
        root.put("provenanceScheme", LegalRequiredSetAggregateProvenance.PROVENANCE_SCHEME);
        root.put("perfil", provenance.profile().name());
        root.put("locale", provenance.locale().getCodigo());
        root.put("audiencia", provenance.audience().name());
        ArrayNode scopes = root.putArray("scopes");
        for (ScopeOrigin scope : provenance.scopes()) {
            ObjectNode item = scopes.addObject();
            item.put("contexto", scope.context().name());
            item.put("conjuntoId", scope.requiredSetId().toString());
            item.put("publicacionId", scope.publicationId().toString());
        }
        return root;
    }
}
