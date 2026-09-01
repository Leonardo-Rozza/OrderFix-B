package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable physical provenance of one observed multi-context legal required set. */
public record LegalRequiredSetAggregateProvenance(
        PerfilAgregadoLegal profile,
        LocaleLegal locale,
        AudienciaLegal audience,
        List<ScopeOrigin> scopes
) {

    public static final String PROVENANCE_SCHEME = "AGGREGATE_PROVENANCE_V1";

    private static final int MAX_SCOPES = 8;
    private static final Comparator<ScopeOrigin> SCOPE_ORDER =
            Comparator.comparingInt(scope -> scope.context().ordinal());

    public LegalRequiredSetAggregateProvenance {
        profile = Objects.requireNonNull(profile, "profile");
        locale = Objects.requireNonNull(locale, "locale");
        audience = Objects.requireNonNull(audience, "audience");

        List<ScopeOrigin> requiredScopes = Objects.requireNonNull(scopes, "scopes");
        int scopeCount = requiredScopes.size();
        if (scopeCount < 1 || scopeCount > MAX_SCOPES) {
            throw new IllegalArgumentException("scopes debe contener entre uno y ocho contextos");
        }

        List<ScopeOrigin> normalizedScopes = new ArrayList<>(scopeCount);
        Set<ContextoLegal> contexts = EnumSet.noneOf(ContextoLegal.class);
        for (ScopeOrigin scope : requiredScopes) {
            ScopeOrigin requiredScope = Objects.requireNonNull(scope, "scope");
            if (!contexts.add(requiredScope.context())) {
                throw new IllegalArgumentException("scopes contiene un contexto duplicado");
            }
            normalizedScopes.add(requiredScope);
        }
        normalizedScopes.sort(SCOPE_ORDER);
        scopes = List.copyOf(normalizedScopes);
    }

    /** One exact V27 snapshot origin, ordered by the frozen {@link ContextoLegal} order. */
    public record ScopeOrigin(
            ContextoLegal context,
            UUID requiredSetId,
            UUID publicationId
    ) {

        public ScopeOrigin {
            context = Objects.requireNonNull(context, "context");
            requiredSetId = Objects.requireNonNull(requiredSetId, "requiredSetId");
            publicationId = Objects.requireNonNull(publicationId, "publicationId");
        }
    }
}
