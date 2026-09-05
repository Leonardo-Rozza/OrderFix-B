package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;

import java.util.List;
import java.util.Objects;

/**
 * Immutable content validated for the public registration contract, with both canonical revisions.
 *
 * <p>Only the validator exposes construction to consumers. This value does not accredit database
 * membership, current publication state, provenance or a committed V28 aggregate. Persistence must
 * verify those separately before a transport can expose {@link #requiredSetRevision()}.</p>
 */
public final class LegalPublicRegistrationRequirements {

    private final LegalRequiredSetProjection projection;
    private final String scopeRevision;
    private final String requiredSetRevision;

    LegalPublicRegistrationRequirements(LegalRequiredSetProjection validatedProjection) {
        this.projection = Objects.requireNonNull(validatedProjection, "validatedProjection");
        this.scopeRevision = new LegalRequiredSetRevisionCalculator().calculate(projection);
        this.requiredSetRevision = new LegalRequiredSetAggregateRevisionCalculator().calculate(
                new LegalRequiredSetAggregateProjection(
                        EsquemaRevisionLegal.AGGREGATE_V1,
                        projection.locale(),
                        AudienciaLegal.ADMIN_TITULAR,
                        List.of(new LegalRequiredSetAggregateProjection.ScopeRevision(
                                projection.context(), scopeRevision))));
    }

    /** Complete immutable scope in its supplied manifest/reference order, including optional acts. */
    public LegalRequiredSetProjection projection() {
        return projection;
    }

    /** Internal SCOPE_V1 value to compare against the exact persisted V27 component. */
    public String scopeRevision() {
        return scopeRevision;
    }

    /** AGGREGATE_V1 value; never substitute the component revision in the public response. */
    public String requiredSetRevision() {
        return requiredSetRevision;
    }
}
