package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalAceptacionMetadataCifrada;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

@org.springframework.stereotype.Repository
public interface LegalAceptacionMetadataCifradaRepository
        extends Repository<LegalAceptacionMetadataCifrada, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update legal_aceptacion_metadatos_cifrados
               set ciphertext = null,
                   tag = null,
                   longitud_original = null,
                   tombstone_en = transaction_timestamp()
             where lote_id = :loteId
               and tombstone_en is null
            """, nativeQuery = true)
    int tombstonearTodosPorLote(@Param("loteId") UUID loteId);
}
