package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Repuesto;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepuestoRepository extends JpaRepository<Repuesto, Long> {

    List<Repuesto> findAllByTallerId(Long tallerId);

    Optional<Repuesto> findByIdAndTallerId(Long id, Long tallerId);

    /**
     * Snapshot escalar previo al lock. Evita cargar una entidad administrada con
     * relaciones que podrían quedar obsoletas mientras esperamos los locks.
     */
    @Query("""
            SELECT r.id AS id, reparacion.id AS reparacionId, articulo.id AS articuloId
            FROM Repuesto r
            LEFT JOIN r.reparacion reparacion
            LEFT JOIN r.articulo articulo
            WHERE r.id = :id AND r.taller.id = :tallerId
            """)
    Optional<RepuestoVinculo> findVinculoByIdAndTallerId(
            @Param("id") Long id, @Param("tallerId") Long tallerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Repuesto r WHERE r.id = :id AND r.taller.id = :tallerId")
    Optional<Repuesto> findByIdAndTallerIdForUpdate(
            @Param("id") Long id, @Param("tallerId") Long tallerId);

    List<Repuesto> findByReparacionIdAndTallerId(Long reparacionId, Long tallerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM Repuesto r
            WHERE r.reparacion.id = :reparacionId AND r.taller.id = :tallerId
            ORDER BY r.id ASC
            """)
    List<Repuesto> findByReparacionIdAndTallerIdForUpdateOrderByIdAsc(
            @Param("reparacionId") Long reparacionId, @Param("tallerId") Long tallerId);

    boolean existsByArticuloIdAndTallerId(Long articuloId, Long tallerId);

    List<Repuesto> findByReparacionId(Long reparacionId);

    @Query(value = """
            SELECT r FROM Repuesto r
            LEFT JOIN FETCH r.reparacion rep
            LEFT JOIN FETCH rep.equipo
            WHERE r.taller.id = :tallerId
              AND (:q IS NULL OR :q = ''
                   OR LOWER(r.nombre) LIKE LOWER(CONCAT('%', :q, '%')))
            """,
            countQuery = """
            SELECT COUNT(r) FROM Repuesto r
            WHERE r.taller.id = :tallerId
              AND (:q IS NULL OR :q = ''
                   OR LOWER(r.nombre) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Repuesto> search(@Param("tallerId") Long tallerId, @Param("q") String q, Pageable pageable);

    interface RepuestoVinculo {
        Long getId();

        Long getReparacionId();

        Long getArticuloId();
    }
}
