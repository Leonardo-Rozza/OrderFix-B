package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalDocumentoTransicion;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface LegalDocumentoTransicionRepository
        extends Repository<LegalDocumentoTransicion, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into legal_documento_transiciones (
                documento_version_id,
                estado_anterior,
                estado_nuevo,
                motivo,
                reemplazo_lote_id,
                ocurrido_en
            ) values (
                :documentoVersionId,
                :estadoAnterior,
                :estadoNuevo,
                :motivo,
                :reemplazoLoteId,
                :ocurridoEn
            )
            """, nativeQuery = true)
    int insertarTransicion(
            @Param("documentoVersionId") UUID documentoVersionId,
            @Param("estadoAnterior") String estadoAnterior,
            @Param("estadoNuevo") String estadoNuevo,
            @Param("motivo") String motivo,
            @Param("reemplazoLoteId") UUID reemplazoLoteId,
            @Param("ocurridoEn") Instant ocurridoEn);

    List<LegalDocumentoTransicion> findAllByDocumentoVersion_IdOrderByOcurridoEnAscIdAsc(
            UUID documentoVersionId);
}
