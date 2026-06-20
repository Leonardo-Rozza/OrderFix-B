package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Artículo del inventario del taller (catálogo con stock).
 */
@Entity
@Table(name = "articulos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Articulo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "taller_id", nullable = false)
    private Taller taller;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(length = 255)
    private String descripcion;

    @Column(length = 60)
    private String sku;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal precio = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2)
    private BigDecimal costo;

    @Column(nullable = false)
    @Builder.Default
    private int stock = 0;

    @Column(name = "stock_minimo", nullable = false)
    @Builder.Default
    private int stockMinimo = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean activo = true;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
