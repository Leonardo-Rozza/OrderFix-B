package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalRequisitoVersion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection.LegalRequisitoLinajeProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalRequisitoVersionRepository extends JpaRepository<LegalRequisitoVersion, UUID> {

    Optional<LegalRequisitoVersion> findByLinea_ClaveAndLinea_LocaleAndVersion(
            String clave, LocaleLegal locale, String version);

    @Query("""
            select v.id as requisitoVersionId,
                   v.linea.id as requisitoLineaId,
                   v.linea.clave as clave,
                   v.version as version,
                   v.lineageOrdinal as lineageOrdinal,
                   v.requerido as requerido,
                   v.requiresReacceptance as requiresReacceptance,
                   v.estado as estado
              from LegalRequisitoVersion v
             where v.linea.clave = :clave
               and v.linea.locale = :locale
             order by v.lineageOrdinal asc
            """)
    List<LegalRequisitoLinajeProjection> findLinajeByClaveAndLocale(
            @Param("clave") String clave, @Param("locale") LocaleLegal locale);
}
