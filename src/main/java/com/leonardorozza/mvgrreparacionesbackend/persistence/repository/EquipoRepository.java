package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Equipo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EquipoRepository extends JpaRepository<Equipo, Long> {

    List<Equipo> findAllByTallerId(Long tallerId);

    Optional<Equipo> findByIdAndTallerId(Long id, Long tallerId);

    boolean existsByIdAndTallerId(Long id, Long tallerId);

    List<Equipo> findByClienteIdAndTallerId(Long clienteId, Long tallerId);

    @Query(value = """
            SELECT e FROM Equipo e
            JOIN FETCH e.cliente c
            WHERE e.taller.id = :tallerId
              AND (:q IS NULL OR :q = ''
                   OR LOWER(e.marca) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.modelo) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.imei) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.nombre) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.apellido) LIKE LOWER(CONCAT('%', :q, '%')))
            """,
            countQuery = """
            SELECT COUNT(e) FROM Equipo e JOIN e.cliente c
            WHERE e.taller.id = :tallerId
              AND (:q IS NULL OR :q = ''
                   OR LOWER(e.marca) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.modelo) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.imei) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.nombre) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.apellido) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Equipo> search(@Param("tallerId") Long tallerId, @Param("q") String q, Pageable pageable);

    @Query("SELECT e.cliente.id, COUNT(e) FROM Equipo e WHERE e.cliente.id IN :clienteIds GROUP BY e.cliente.id")
    List<Object[]> countByClienteIds(@Param("clienteIds") List<Long> clienteIds);
}
