package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TallerRepository extends JpaRepository<Taller, Long> {

    /**
     * Toma el taller con lock de escritura: serializa la generación del número de
     * orden correlativo por taller (evita que dos ingresos simultáneos repitan el número).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Taller t WHERE t.id = :id")
    Optional<Taller> findByIdForUpdate(@Param("id") Long id);
}
