package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalAceptacionMetadata;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface LegalAceptacionMetadataRepository extends JpaRepository<LegalAceptacionMetadata, UUID> {
    Page<LegalAceptacionMetadata> findAllByPurgadoEnIsNullAndRetenerHastaLessThanEqualOrderByRetenerHastaAsc(
            Instant instante, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update legal_aceptacion_metadatos
               set purgado_en = transaction_timestamp()
             where lote_id = :loteId
               and purgado_en is null
            """, nativeQuery = true)
    int marcarPurgaCompletada(@Param("loteId") UUID loteId);
}
