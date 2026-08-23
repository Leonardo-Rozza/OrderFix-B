package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cobro;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CobroRepository extends JpaRepository<Cobro, Long> {

    @EntityGraph(attributePaths = "anuladoPor")
    List<Cobro> findByReparacionIdAndTallerIdOrderByCreatedAtDesc(Long reparacionId, Long tallerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM Cobro c
            WHERE c.id = :id
              AND c.reparacion.id = :reparacionId
              AND c.taller.id = :tallerId
            """)
    Optional<Cobro> findByIdAndReparacionIdAndTallerIdForUpdate(
            @Param("id") Long id,
            @Param("reparacionId") Long reparacionId,
            @Param("tallerId") Long tallerId);

    /** Incluye activos y anulados: cualquier movimiento histórico impide la baja física. */
    boolean existsByReparacionId(Long reparacionId);

    @Query("""
            SELECT COALESCE(SUM(c.monto), 0)
            FROM Cobro c
            WHERE c.reparacion.id = :reparacionId
              AND c.anuladoAt IS NULL
            """)
    BigDecimal sumByReparacionId(@Param("reparacionId") Long reparacionId);

    /** Cobrado agrupado por reparación (para enriquecer listados sin N+1). */
    @Query("""
            SELECT c.reparacion.id, COALESCE(SUM(c.monto), 0)
            FROM Cobro c
            WHERE c.reparacion.id IN :ids
              AND c.anuladoAt IS NULL
            GROUP BY c.reparacion.id
            """)
    List<Object[]> sumByReparacionIds(@Param("ids") List<Long> ids);

    List<Cobro> findByTallerIdAndAnuladoAtIsNullAndCreatedAtBetween(
            Long tallerId, LocalDateTime desde, LocalDateTime hasta);

    @EntityGraph(attributePaths = {"reparacion", "anuladoPor"})
    List<Cobro> findAllByTallerIdOrderByIdAsc(Long tallerId);
}
