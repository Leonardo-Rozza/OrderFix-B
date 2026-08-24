package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.util.UUID;

public interface LegalAceptacionDocumentoProjection {
    UUID getAceptacionId();

    int getDocumentoOrdinal();

    UUID getDocumentoVersionId();

    String getDocumentoClave();

    TipoDocumentoLegal getTipo();

    String getVersion();

    String getTitulo();

    String getSha256();
}
