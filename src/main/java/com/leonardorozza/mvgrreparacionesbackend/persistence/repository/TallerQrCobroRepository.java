package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.TallerQrCobro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TallerQrCobroRepository extends JpaRepository<TallerQrCobro, Long> {

    /** Consulta liviana para el DTO de metadatos: no selecciona el BYTEA. */
    @Query("SELECT q.sha256 FROM TallerQrCobro q WHERE q.tallerId = :tallerId")
    Optional<String> findSha256ByTallerId(@Param("tallerId") Long tallerId);

    /** Borrado directo para no cargar el BYTEA; es naturalmente idempotente. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TallerQrCobro q WHERE q.tallerId = :tallerId")
    int deleteByTallerId(@Param("tallerId") Long tallerId);
}
