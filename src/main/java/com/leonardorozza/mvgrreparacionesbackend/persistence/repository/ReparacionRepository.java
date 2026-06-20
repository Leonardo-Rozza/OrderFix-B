package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReparacionRepository extends JpaRepository<Reparacion, Long> {

    List<Reparacion> findAllByTallerId(Long tallerId);

    Optional<Reparacion> findByIdAndTallerId(Long id, Long tallerId);

    Optional<Reparacion> findByCodigoSeguimiento(String codigoSeguimiento);

    boolean existsByCodigoSeguimiento(String codigoSeguimiento);

    boolean existsByIdAndTallerId(Long id, Long tallerId);

    List<Reparacion> findByEstadoAndTallerId(EstadoReparacion estado, Long tallerId);

    List<Reparacion> findByEquipoIdAndTallerId(Long equipoId, Long tallerId);

    long countByTallerIdAndCreatedAtAfter(Long tallerId, LocalDateTime desde);

    long countByTallerId(Long tallerId);

    long countByTallerIdAndEstado(Long tallerId, EstadoReparacion estado);

    @Query("""
            SELECT r FROM Reparacion r
            WHERE r.taller.id = :tallerId
              AND (:estado IS NULL OR r.estado = :estado)
              AND (:q IS NULL OR :q = ''
                   OR LOWER(r.descripcionProblema) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Reparacion> search(@Param("tallerId") Long tallerId,
                            @Param("q") String q,
                            @Param("estado") EstadoReparacion estado,
                            Pageable pageable);
}
