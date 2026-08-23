package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

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

    @Column(length = 120)
    private String referencia;

    @Setter(AccessLevel.NONE)
    @Column(name = "anulado_at")
    private LocalDateTime anuladoAt;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "anulado_por_id")
    private User anuladoPor;

    @Setter(AccessLevel.NONE)
    @Column(name = "motivo_anulacion", length = 255)
    private String motivoAnulacion;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public boolean estaAnulado() {
        return anuladoAt != null;
    }

    public void anular(LocalDateTime fecha, User usuario, String motivo) {
        this.anuladoAt = Objects.requireNonNull(fecha, "La fecha de anulación es obligatoria");
        this.anuladoPor = Objects.requireNonNull(usuario, "El usuario que anula es obligatorio");
        this.motivoAnulacion = Objects.requireNonNull(motivo, "El motivo de anulación es obligatorio");
    }
}
