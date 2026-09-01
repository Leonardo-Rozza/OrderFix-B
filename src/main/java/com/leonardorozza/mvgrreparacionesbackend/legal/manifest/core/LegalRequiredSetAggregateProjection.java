package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable semantic projection for one complete multi-context legal required set. */
public record LegalRequiredSetAggregateProjection(
        EsquemaRevisionLegal revisionScheme,
        LocaleLegal locale,
        AudienciaLegal audience,
        List<ScopeRevision> scopes
) {

    private static final int MAX_SCOPES = 8;
    private static final int SHA256_HEX_LENGTH = 64;
    private static final String SHA256_PREFIX = "sha256:";
    private static final Comparator<ScopeRevision> SCOPE_ORDER =
            Comparator.comparingInt(scope -> scope.context().ordinal());

    public LegalRequiredSetAggregateProjection {
        revisionScheme = Objects.requireNonNull(revisionScheme, "revisionScheme");
        if (revisionScheme != EsquemaRevisionLegal.AGGREGATE_V1) {
            throw new IllegalArgumentException("revisionScheme debe ser AGGREGATE_V1");
        }
        locale = Objects.requireNonNull(locale, "locale");
        audience = Objects.requireNonNull(audience, "audience");

        List<ScopeRevision> requiredScopes = Objects.requireNonNull(scopes, "scopes");
        int scopeCount = requiredScopes.size();
        if (scopeCount < 1 || scopeCount > MAX_SCOPES) {
            throw new IllegalArgumentException("scopes debe contener entre uno y ocho contextos");
        }

        List<ScopeRevision> normalizedScopes = new ArrayList<>(scopeCount);
        Set<ContextoLegal> contexts = EnumSet.noneOf(ContextoLegal.class);
        for (ScopeRevision scope : requiredScopes) {
            ScopeRevision requiredScope = Objects.requireNonNull(scope, "scope");
            if (!contexts.add(requiredScope.context())) {
                throw new IllegalArgumentException("scopes contiene un contexto duplicado");
            }
            normalizedScopes.add(requiredScope);
        }
        normalizedScopes.sort(SCOPE_ORDER);
        scopes = List.copyOf(normalizedScopes);
    }

    /** One complete V27 scope revision, ordered by the frozen {@link ContextoLegal} order. */
    public record ScopeRevision(
            ContextoLegal context,
            String requiredSetRevision
    ) {

        public ScopeRevision {
            context = Objects.requireNonNull(context, "context");
            requiredSetRevision = requireRequiredSetRevision(requiredSetRevision);
        }
    }

    private static String requireRequiredSetRevision(String value) {
        String required = Objects.requireNonNull(value, "requiredSetRevision");
        if (required.length() != SHA256_PREFIX.length() + SHA256_HEX_LENGTH
                || !required.startsWith(SHA256_PREFIX)) {
            throw new IllegalArgumentException(
                    "requiredSetRevision no respeta sha256:<64-hex lowercase>");
        }
        for (int index = SHA256_PREFIX.length(); index < required.length(); index++) {
            char current = required.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f'))) {
                throw new IllegalArgumentException(
                        "requiredSetRevision no respeta sha256:<64-hex lowercase>");
            }
        }
        return required;
    }
}
