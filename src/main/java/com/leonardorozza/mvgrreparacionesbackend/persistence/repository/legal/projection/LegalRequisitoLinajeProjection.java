package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;

import java.util.UUID;

public interface LegalRequisitoLinajeProjection {
    UUID getRequisitoVersionId();

    UUID getRequisitoLineaId();

    String getClave();

    String getVersion();

    int getLineageOrdinal();

    boolean getRequerido();

    boolean getRequiresReacceptance();

    EstadoVersionLegal getEstado();
}
