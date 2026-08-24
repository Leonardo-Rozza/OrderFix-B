package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalAceptacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection.LegalAceptacionHistorialProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalAceptacionRepository extends JpaRepository<LegalAceptacion, UUID> {

    Optional<LegalAceptacion> findByUserIdAndTallerIdAndRequisitoVersionId(
            Long userId, Long tallerId, UUID requisitoVersionId);

    @Query(value = """
            select a.id as aceptacionId,
                   a.loteId as loteId,
                   a.requisitoVersionId as requisitoVersionId,
                   a.requisitoClave as requisitoClave,
                   a.requisitoVersionSnapshot as requisitoVersion,
                   a.contexto as contexto,
                   a.tipoActo as tipoActo,
                   a.afirmacion as afirmacion,
                   a.afirmacionSha256 as afirmacionSha256,
                   a.requerido as requerido,
                   a.lote.aceptadoEn as aceptadoEn
              from LegalAceptacion a
             where a.userId = :userId
               and a.tallerId = :tallerId
               and (:contexto is null or a.contexto = :contexto)
             order by a.lote.aceptadoEn desc, a.id desc
            """,
            countQuery = """
            select count(a)
              from LegalAceptacion a
             where a.userId = :userId
               and a.tallerId = :tallerId
               and (:contexto is null or a.contexto = :contexto)
            """)
    Page<LegalAceptacionHistorialProjection> findHistorial(
            @Param("userId") Long userId,
            @Param("tallerId") Long tallerId,
            @Param("contexto") ContextoLegal contexto,
            Pageable pageable);
}
