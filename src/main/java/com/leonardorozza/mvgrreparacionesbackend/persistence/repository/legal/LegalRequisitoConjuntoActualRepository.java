package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalRequisitoConjuntoActual;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.id.LegalRequisitoConjuntoActualId;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection.LegalRequisitoActualProjection;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LegalRequisitoConjuntoActualRepository
        extends JpaRepository<LegalRequisitoConjuntoActual, LegalRequisitoConjuntoActualId> {

    @EntityGraph(attributePaths = {"conjunto", "publicacion"})
    Optional<LegalRequisitoConjuntoActual> findByIdLocaleAndIdContextoAndIdAudiencia(
            LocaleLegal locale, ContextoLegal contexto, AudienciaLegal audiencia);

    @Query("""
            select a.conjuntoId as conjuntoId,
                   a.publicacionId as publicacionId,
                   a.conjunto.requiredSetRevision as requiredSetRevision,
                   m.requisitoVersionId as requisitoVersionId,
                   m.requisitoLineaId as requisitoLineaId,
                   m.requisitoLinea.clave as clave,
                   a.id.locale as locale,
                   a.id.contexto as contexto,
                   a.id.audiencia as audiencia,
                   m.requisitoLinea.tipoActo as tipoActo,
                   m.requisitoVersion.version as version,
                   m.requisitoVersion.afirmacion as afirmacion,
                   m.requisitoVersion.afirmacionSha256 as afirmacionSha256,
                   m.requisitoVersion.requerido as requerido,
                   m.requisitoVersion.requiresReacceptance as requiresReacceptance,
                   m.manifestOrdinal as manifestOrdinal
              from LegalRequisitoConjuntoActual a,
                   LegalRequisitoConjuntoMiembro m
             where m.conjuntoId = a.conjuntoId
               and a.id.locale = :locale
               and a.id.contexto = :contexto
               and a.id.audiencia = :audiencia
             order by m.manifestOrdinal asc
            """)
    List<LegalRequisitoActualProjection> findRequisitosActuales(
            @Param("locale") LocaleLegal locale,
            @Param("contexto") ContextoLegal contexto,
            @Param("audiencia") AudienciaLegal audiencia);
}
