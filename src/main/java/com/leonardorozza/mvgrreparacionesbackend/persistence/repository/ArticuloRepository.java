package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Articulo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ArticuloRepository extends JpaRepository<Articulo, Long> {

    Optional<Articulo> findByIdAndTallerId(Long id, Long tallerId);

    @Query("""
            SELECT a FROM Articulo a
            WHERE a.taller.id = :tallerId
              AND (:q IS NULL OR :q = ''
                   OR LOWER(a.nombre) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(a.sku) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Articulo> search(@Param("tallerId") Long tallerId, @Param("q") String q, Pageable pageable);

    @Query("""
            SELECT a FROM Articulo a
            WHERE a.taller.id = :tallerId AND a.activo = true AND a.stock <= a.stockMinimo
            ORDER BY a.stock ASC
            """)
    List<Articulo> findStockBajo(@Param("tallerId") Long tallerId);

    @Query("""
            SELECT COUNT(a) FROM Articulo a
            WHERE a.taller.id = :tallerId AND a.activo = true AND a.stock <= a.stockMinimo
            """)
    long countStockBajo(@Param("tallerId") Long tallerId);
}
