package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;

import java.time.Instant;
import java.util.UUID;

public interface LegalAceptacionHistorialProjection {
    UUID getAceptacionId();

    UUID getLoteId();

    UUID getRequisitoVersionId();

    String getRequisitoClave();

    String getRequisitoVersion();

    ContextoLegal getContexto();

    TipoActoLegal getTipoActo();

    String getAfirmacion();

    String getAfirmacionSha256();

    boolean getRequerido();

    Instant getAceptadoEn();
}
