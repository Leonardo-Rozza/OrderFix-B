package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPresupuesto;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Presupuesto de una reparación. El taller lo crea; el cliente lo aprueba o rechaza
 * (incluso desde el link público de seguimiento).
 */
@Entity
@Table(name = "presupuestos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Presupuesto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reparacion_id", nullable = false)
    private Reparacion reparacion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "taller_id", nullable = false)
    private Taller taller;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EstadoPresupuesto estado = EstadoPresupuesto.PENDIENTE;

    @ElementCollection
    @CollectionTable(name = "presupuesto_items", joinColumns = @JoinColumn(name = "presupuesto_id"))
    @Builder.Default
    private List<ItemPresupuesto> items = new ArrayList<>();

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(length = 1000)
    private String observaciones;

    private LocalDateTime fechaRespuesta;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
