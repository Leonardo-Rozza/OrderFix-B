package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cobro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CobroRepository extends JpaRepository<Cobro, Long> {

    List<Cobro> findByReparacionIdAndTallerIdOrderByCreatedAtDesc(Long reparacionId, Long tallerId);

    Optional<Cobro> findByIdAndTallerId(Long id, Long tallerId);

    @Query("SELECT COALESCE(SUM(c.monto), 0) FROM Cobro c WHERE c.reparacion.id = :reparacionId")
    BigDecimal sumByReparacionId(@Param("reparacionId") Long reparacionId);

    List<Cobro> findByTallerIdAndCreatedAtBetween(Long tallerId, LocalDateTime desde, LocalDateTime hasta);
}
