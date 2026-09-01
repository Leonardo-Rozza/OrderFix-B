package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Immutable server-accredited vector of legal scopes applicable to one operation. */
public final class LegalApplicableScopeSet {

    private static final int MAX_CONTEXTS = 8;

    private final PerfilAgregadoLegal profile;
    private final LocaleLegal locale;
    private final AudienciaLegal audience;
    private final List<ContextoLegal> contexts;

    LegalApplicableScopeSet(
            PerfilAgregadoLegal profile,
            LocaleLegal locale,
            AudienciaLegal audience,
            Collection<ContextoLegal> contexts) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.locale = Objects.requireNonNull(locale, "locale");
        this.audience = Objects.requireNonNull(audience, "audience");
        Collection<ContextoLegal> requiredContexts = Objects.requireNonNull(contexts, "contexts");
        int size = requiredContexts.size();
        if (size < 1 || size > MAX_CONTEXTS) {
            throw new IllegalArgumentException("contexts debe contener entre 1 y 8 valores");
        }

        EnumSet<ContextoLegal> unique = EnumSet.noneOf(ContextoLegal.class);
        for (ContextoLegal context : requiredContexts) {
            ContextoLegal requiredContext = Objects.requireNonNull(context, "context");
            if (!unique.add(requiredContext)) {
                throw new IllegalArgumentException("contexts no admite duplicados");
            }
        }

        ArrayList<ContextoLegal> ordered = new ArrayList<>(size);
        for (ContextoLegal context : ContextoLegal.values()) {
            if (unique.contains(context)) {
                ordered.add(context);
            }
        }
        this.contexts = List.copyOf(ordered);
        requireProfileInvariant();
    }

    private void requireProfileInvariant() {
        switch (profile) {
            case REGISTRATION -> {
                if (audience != AudienciaLegal.ADMIN_TITULAR
                        || !contexts.equals(List.of(ContextoLegal.REGISTRO))) {
                    throw new IllegalArgumentException(
                            "REGISTRATION requiere ADMIN_TITULAR y sólo REGISTRO");
                }
            }
            case AUTHENTICATED_PENDING -> {
                if (!contexts.contains(ContextoLegal.USO_CONTINUADO)) {
                    throw new IllegalArgumentException(
                            "AUTHENTICATED_PENDING requiere USO_CONTINUADO");
                }
            }
        }
    }

    public PerfilAgregadoLegal profile() {
        return profile;
    }

    public LocaleLegal locale() {
        return locale;
    }

    public AudienciaLegal audience() {
        return audience;
    }

    public List<ContextoLegal> contexts() {
        return contexts;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof LegalApplicableScopeSet that)) {
            return false;
        }
        return profile == that.profile
                && locale == that.locale
                && audience == that.audience
                && contexts.equals(that.contexts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, locale, audience, contexts);
    }

    @Override
    public String toString() {
        return "LegalApplicableScopeSet[profile=" + profile
                + ", locale=" + locale.getCodigo()
                + ", audience=" + audience
                + ", contexts=" + contexts + ']';
    }
}
