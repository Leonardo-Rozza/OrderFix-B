package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;

import java.util.UUID;

public interface LegalRequisitoActualProjection {
    UUID getConjuntoId();

    UUID getPublicacionId();

    String getRequiredSetRevision();

    UUID getRequisitoVersionId();

    UUID getRequisitoLineaId();

    String getClave();

    LocaleLegal getLocale();

    ContextoLegal getContexto();

    AudienciaLegal getAudiencia();

    TipoActoLegal getTipoActo();

    String getVersion();

    String getAfirmacion();

    String getAfirmacionSha256();

    boolean getRequerido();

    boolean getRequiresReacceptance();

    int getManifestOrdinal();
}
