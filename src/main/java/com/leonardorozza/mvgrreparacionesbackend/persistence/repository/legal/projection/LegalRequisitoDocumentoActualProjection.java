package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.time.Instant;
import java.util.UUID;

public interface LegalRequisitoDocumentoActualProjection {
    UUID getRequisitoVersionId();

    int getDocumentoOrdinal();

    UUID getDocumentoVersionId();

    UUID getDocumentoLineaId();

    String getDocumentoClave();

    TipoDocumentoLegal getTipo();

    LocaleLegal getLocale();

    String getVersion();

    int getLineageOrdinal();

    String getTitulo();

    String getContenidoMarkdown();

    String getSha256();

    Instant getVigenteDesde();

    boolean getRequiresReacceptance();

    EstadoVersionLegal getEstado();
}
