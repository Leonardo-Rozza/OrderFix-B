package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    List<Cliente> findAllByTallerId(Long tallerId);

    Optional<Cliente> findByIdAndTallerId(Long id, Long tallerId);

    boolean existsByIdAndTallerId(Long id, Long tallerId);

    boolean existsByTelefonoAndTallerId(String telefono, Long tallerId);

    Optional<Cliente> findByTelefonoAndTallerId(String telefono, Long tallerId);

    @Query("""
            SELECT c FROM Cliente c
            WHERE c.taller.id = :tallerId
              AND (:q IS NULL OR :q = ''
                   OR LOWER(c.nombre) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.apellido) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR c.telefono LIKE CONCAT('%', :q, '%'))
            """)
    Page<Cliente> search(@Param("tallerId") Long tallerId, @Param("q") String q, Pageable pageable);
}
