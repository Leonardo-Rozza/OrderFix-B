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

    @Query(value = """
            SELECT r FROM Reparacion r
            JOIN FETCH r.equipo e
            JOIN FETCH e.cliente c
            WHERE r.taller.id = :tallerId
              AND (:estado IS NULL OR r.estado = :estado)
              AND (:q IS NULL OR :q = ''
                   OR LOWER(r.descripcionProblema) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.nombre) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.apellido) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR c.telefono LIKE CONCAT('%', :q, '%')
                   OR LOWER(e.marca) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.modelo) LIKE LOWER(CONCAT('%', :q, '%')))
            """,
            countQuery = """
            SELECT COUNT(r) FROM Reparacion r
            JOIN r.equipo e JOIN e.cliente c
            WHERE r.taller.id = :tallerId
              AND (:estado IS NULL OR r.estado = :estado)
              AND (:q IS NULL OR :q = ''
                   OR LOWER(r.descripcionProblema) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.nombre) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.apellido) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR c.telefono LIKE CONCAT('%', :q, '%')
                   OR LOWER(e.marca) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.modelo) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Reparacion> search(@Param("tallerId") Long tallerId,
                            @Param("q") String q,
                            @Param("estado") EstadoReparacion estado,
                            Pageable pageable);

    List<Reparacion> findTop5ByTallerIdOrderByIdDesc(Long tallerId);

    @Query("SELECT r.equipo.id, COUNT(r) FROM Reparacion r WHERE r.equipo.id IN :equipoIds GROUP BY r.equipo.id")
    List<Object[]> countByEquipoIds(@Param("equipoIds") List<Long> equipoIds);

    @Query("""
            SELECT r.equipo.cliente.id, COUNT(r), MAX(r.createdAt)
            FROM Reparacion r WHERE r.equipo.cliente.id IN :clienteIds
            GROUP BY r.equipo.cliente.id
            """)
    List<Object[]> agregadoPorCliente(@Param("clienteIds") List<Long> clienteIds);
}
