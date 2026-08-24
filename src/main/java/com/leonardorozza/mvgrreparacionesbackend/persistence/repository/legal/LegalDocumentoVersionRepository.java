package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalDocumentoVersion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection.LegalDocumentoCatalogoProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.projection.LegalDocumentoLinajeProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalDocumentoVersionRepository extends JpaRepository<LegalDocumentoVersion, UUID> {

    Optional<LegalDocumentoVersion> findByLinea_ClaveAndLinea_LocaleAndVersion(
            String clave, LocaleLegal locale, String version);

    @Query("""
            select v.id as documentoVersionId,
                   v.linea.id as documentoLineaId,
                   v.linea.clave as clave,
                   v.version as version,
                   v.lineageOrdinal as lineageOrdinal,
                   v.requiresReacceptance as requiresReacceptance,
                   v.estado as estado
              from LegalDocumentoVersion v
             where v.linea.clave = :clave
               and v.linea.locale = :locale
             order by v.lineageOrdinal asc
            """)
    List<LegalDocumentoLinajeProjection> findLinajeByClaveAndLocale(
            @Param("clave") String clave, @Param("locale") LocaleLegal locale);

    @Query(value = """
            select v.id as id,
                   v.linea.tipo as tipo,
                   v.version as version,
                   v.titulo as titulo,
                   v.sha256 as sha256,
                   v.vigenteDesde as vigenteDesde,
                   v.estado as estado,
                   v.linea.locale as locale
              from LegalDocumentoVersion v
             where v.linea.locale = :locale
               and v.estado in (
                   com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal.VIGENTE,
                   com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal.REEMPLAZADA,
                   com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal.RETIRADA
               )
               and (
                   :contexto is null
                   or exists (
                       select 1
                         from LegalDocumentoContexto c
                        where c.documentoVersion = v
                          and c.contexto = :contexto
                   )
               )
             order by case v.linea.tipo
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.TERMINOS_SERVICIO then 0
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.POLITICA_PRIVACIDAD then 1
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.ACUERDO_TRATAMIENTO_DATOS then 2
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.CONDICIONES_PRO then 3
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.POLITICA_CANCELACIONES_REEMBOLSOS then 4
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.POLITICA_CIERRE_CUENTA then 5
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.AVISO_CLIENTES_TALLER then 6
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.TERMINOS_USUARIO then 7
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.AVISO_PRIVACIDAD_USUARIO then 8
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.COMPROMISO_CONFIDENCIALIDAD then 9
                   when com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.ATESTACION_DATOS_CLIENTE then 10
                   else 2147483647
               end asc,
               v.vigenteDesde desc,
               v.id asc
            """,
            countQuery = """
            select count(v)
              from LegalDocumentoVersion v
             where v.linea.locale = :locale
               and v.estado in (
                   com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal.VIGENTE,
                   com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal.REEMPLAZADA,
                   com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal.RETIRADA
               )
               and (
                   :contexto is null
                   or exists (
                       select 1
                         from LegalDocumentoContexto c
                        where c.documentoVersion = v
                          and c.contexto = :contexto
                   )
               )
            """)
    Page<LegalDocumentoCatalogoProjection> findCatalogoPublico(
            @Param("locale") LocaleLegal locale,
            @Param("contexto") ContextoLegal contexto,
            Pageable pageable);
}
