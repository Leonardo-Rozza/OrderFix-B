package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;

import java.util.UUID;

public interface LegalDocumentoLinajeProjection {
    UUID getDocumentoVersionId();

    UUID getDocumentoLineaId();

    String getClave();

    String getVersion();

    int getLineageOrdinal();

    boolean getRequiresReacceptance();

    EstadoVersionLegal getEstado();
}
