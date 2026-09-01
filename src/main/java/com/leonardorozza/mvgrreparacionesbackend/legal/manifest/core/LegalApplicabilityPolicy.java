package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;

import java.util.List;
import java.util.Objects;

/** Server authority that selects the complete legal contexts for one aggregate profile. */
@FunctionalInterface
public interface LegalApplicabilityPolicy {

    List<ContextoLegal> applicableContexts(
            PerfilAgregadoLegal profile,
            AudienciaLegal audience);

    /** Minimum production policy before lifecycle-specific contexts are enabled. */
    static LegalApplicabilityPolicy minimum() {
        return (profile, audience) -> switch (Objects.requireNonNull(profile, "profile")) {
            case REGISTRATION -> {
                if (audience != AudienciaLegal.ADMIN_TITULAR) {
                    throw new IllegalArgumentException(
                            "REGISTRATION requiere audiencia ADMIN_TITULAR");
                }
                yield List.of(ContextoLegal.REGISTRO);
            }
            case AUTHENTICATED_PENDING -> {
                Objects.requireNonNull(audience, "audience");
                yield List.of(ContextoLegal.USO_CONTINUADO);
            }
        };
    }
}
