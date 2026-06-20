package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Repuesto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepuestoRepository extends JpaRepository<Repuesto, Long> {

    List<Repuesto> findAllByTallerId(Long tallerId);

    Optional<Repuesto> findByIdAndTallerId(Long id, Long tallerId);

    List<Repuesto> findByReparacionIdAndTallerId(Long reparacionId, Long tallerId);

    List<Repuesto> findByReparacionId(Long reparacionId);

    @Query("""
            SELECT r FROM Repuesto r
            WHERE r.taller.id = :tallerId
              AND (:q IS NULL OR :q = ''
                   OR LOWER(r.nombre) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Repuesto> search(@Param("tallerId") Long tallerId, @Param("q") String q, Pageable pageable);
}
