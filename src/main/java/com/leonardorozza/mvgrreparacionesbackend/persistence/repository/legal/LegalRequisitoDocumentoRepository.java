package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalRequisitoDocumento;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection.LegalRequisitoDocumentoActualProjection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface LegalRequisitoDocumentoRepository
        extends Repository<LegalRequisitoDocumento, Long> {

    @Query("""
            select d.requisitoVersion.id as requisitoVersionId,
                   d.documentoOrdinal as documentoOrdinal,
                   d.documentoVersion.id as documentoVersionId,
                   d.documentoVersion.linea.id as documentoLineaId,
                   d.documentoVersion.linea.clave as documentoClave,
                   d.documentoVersion.linea.tipo as tipo,
                   d.documentoVersion.linea.locale as locale,
                   d.documentoVersion.version as version,
                   d.documentoVersion.lineageOrdinal as lineageOrdinal,
                   d.documentoVersion.titulo as titulo,
                   d.documentoVersion.contenidoMarkdown as contenidoMarkdown,
                   d.documentoVersion.sha256 as sha256,
                   d.documentoVersion.vigenteDesde as vigenteDesde,
                   d.documentoVersion.requiresReacceptance as requiresReacceptance,
                   d.documentoVersion.estado as estado
              from LegalRequisitoDocumento d
             where d.requisitoVersion.id in :requisitoVersionIds
             order by d.requisitoVersion.id asc, d.documentoOrdinal asc, d.id asc
            """)
    List<LegalRequisitoDocumentoActualProjection> findDocumentosByRequisitoVersionIds(
            @Param("requisitoVersionIds") Collection<UUID> requisitoVersionIds);
}
