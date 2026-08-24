package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalAceptacionDocumento;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection.LegalAceptacionDocumentoProjection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface LegalAceptacionDocumentoRepository
        extends Repository<LegalAceptacionDocumento, Long> {

    @Query("""
            select d.aceptacion.id as aceptacionId,
                   d.documentoOrdinal as documentoOrdinal,
                   d.documentoVersionId as documentoVersionId,
                   d.documentoClave as documentoClave,
                   d.tipo as tipo,
                   d.documentoVersionSnapshot as version,
                   d.titulo as titulo,
                   d.sha256 as sha256
              from LegalAceptacionDocumento d
             where d.aceptacion.id in :aceptacionIds
             order by d.aceptacion.id asc, d.documentoOrdinal asc, d.id asc
            """)
    List<LegalAceptacionDocumentoProjection> findDocumentosByAceptacionIds(
            @Param("aceptacionIds") Collection<UUID> aceptacionIds);
}
