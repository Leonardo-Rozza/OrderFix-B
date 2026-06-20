package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Cobro (pago) registrado sobre una reparación. Una reparación puede tener varios
 * (pagos parciales / seña + saldo).
 */
@Entity
@Table(name = "cobros")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Cobro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reparacion_id", nullable = false)
    private Reparacion reparacion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "taller_id", nullable = false)
    private Taller taller;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MetodoPago metodo;

    @Column(length = 255)
    private String observaciones;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
