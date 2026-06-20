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

    // ----- Orden de trabajo ampliada (checklist de ingreso) -----
    @Column(name = "patron_desbloqueo", length = 60)
    private String patronDesbloqueo;

    @Column(name = "pin_desbloqueo", length = 20)
    private String pinDesbloqueo;

    @Column(length = 255)
    private String accesorios;

    @Column(name = "condiciones_ingreso", length = 500)
    private String condicionesIngreso;

    @Column(length = 1000)
    private String observaciones;

    /** Técnico (usuario del taller) asignado a la reparación. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tecnico_id")
    private User tecnico;

    @ElementCollection
    @CollectionTable(name = "reparacion_fotos", joinColumns = @JoinColumn(name = "reparacion_id"))
    @Column(name = "url")
    @Builder.Default
    private List<String> fotos = new ArrayList<>();

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

    /** Suma de los repuestos (precio × cantidad). Fuente única de verdad del total. */
    @Transient
    public BigDecimal calcularTotalRepuestos() {
        if (repuestos == null) {
            return BigDecimal.ZERO;
        }
        return repuestos.stream()
                .map(r -> (r.getPrecio() == null ? BigDecimal.ZERO : r.getPrecio())
                        .multiply(BigDecimal.valueOf(Math.max(1, r.getCantidad()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Total a cobrar: mano de obra (precioFinal ?? precioEstimado ?? 0) + repuestos. */
    @Transient
    public BigDecimal calcularTotal() {
        BigDecimal manoDeObra = precioFinal != null ? precioFinal
                : (precioEstimado != null ? precioEstimado : BigDecimal.ZERO);
        return manoDeObra.add(calcularTotalRepuestos());
    }
}

