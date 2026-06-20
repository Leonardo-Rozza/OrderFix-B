package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "reparaciones")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Reparacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String descripcionProblema;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private EstadoReparacion estado = EstadoReparacion.INGRESADO;

    @Column(precision = 10, scale = 2)
    private BigDecimal precioEstimado;

    @Column(precision = 10, scale = 2)
    private BigDecimal precioFinal;

    private LocalDate fechaIngreso;

    private LocalDate fechaEstimadaEntrega;

    private LocalDate fechaEntrega;

    /** Código público para que el cliente consulte el estado sin login. */
    @Column(name = "codigo_seguimiento", unique = true, length = 20)
    private String codigoSeguimiento;

    @ManyToOne(optional = false)
    @JoinColumn(name = "equipo_id", nullable = false)
    private Equipo equipo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "taller_id", nullable = false)
    @JsonIgnore
    private Taller taller;

    @OneToMany(mappedBy = "reparacion", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    @Builder.Default
    private List<Repuesto> repuestos = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}

