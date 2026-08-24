package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.time.Instant;
import java.util.UUID;

public interface LegalDocumentoCatalogoProjection {
    UUID getId();

    TipoDocumentoLegal getTipo();

    LocaleLegal getLocale();

    String getVersion();

    String getTitulo();

    String getSha256();

    Instant getVigenteDesde();

    EstadoVersionLegal getEstado();
}
