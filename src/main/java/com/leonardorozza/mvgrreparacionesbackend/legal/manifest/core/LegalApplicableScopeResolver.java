package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;

import java.util.Objects;

/** Sole production factory for accredited legal applicability vectors. */
public final class LegalApplicableScopeResolver {

    private final LegalApplicabilityPolicy policy;

    public LegalApplicableScopeResolver() {
        this(LegalApplicabilityPolicy.minimum());
    }

    public LegalApplicableScopeResolver(LegalApplicabilityPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public LegalApplicableScopeSet resolve(
            PerfilAgregadoLegal profile,
            LocaleLegal locale,
            AudienciaLegal audience) {
        PerfilAgregadoLegal requiredProfile = Objects.requireNonNull(profile, "profile");
        LocaleLegal requiredLocale = Objects.requireNonNull(locale, "locale");
        AudienciaLegal requiredAudience = Objects.requireNonNull(audience, "audience");
        return new LegalApplicableScopeSet(
                requiredProfile,
                requiredLocale,
                requiredAudience,
                policy.applicableContexts(requiredProfile, requiredAudience));
    }
}
