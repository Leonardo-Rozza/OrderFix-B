package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalRequisitoTransicion;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface LegalRequisitoTransicionRepository
        extends Repository<LegalRequisitoTransicion, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into legal_requisito_transiciones (
                requisito_version_id,
                estado_anterior,
                estado_nuevo,
                motivo,
                ocurrido_en
            ) values (
                :requisitoVersionId,
                :estadoAnterior,
                :estadoNuevo,
                :motivo,
                :ocurridoEn
            )
            """, nativeQuery = true)
    int insertarTransicion(
            @Param("requisitoVersionId") UUID requisitoVersionId,
            @Param("estadoAnterior") String estadoAnterior,
            @Param("estadoNuevo") String estadoNuevo,
            @Param("motivo") String motivo,
            @Param("ocurridoEn") Instant ocurridoEn);

    List<LegalRequisitoTransicion> findAllByRequisitoVersion_IdOrderByOcurridoEnAscIdAsc(
            UUID requisitoVersionId);
}
