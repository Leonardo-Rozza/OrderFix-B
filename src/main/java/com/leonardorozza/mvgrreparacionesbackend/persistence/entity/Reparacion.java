package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.CuentaVinculada;
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

    /** Número de orden mostrable, correlativo por taller con reinicio anual (ej: ORD-2026-0042). */
    @Column(name = "numero_orden", length = 20)
    private String numeroOrden;

    // ----- Flags de riesgo del ingreso (§1/§2: disparan avisos y afectan garantía) -----
    /** Cayó al agua / humedad: fallas impredecibles, garantía limitada. */
    @Column(nullable = false)
    @Builder.Default
    private boolean mojado = false;

    /** Reparación a nivel placa: riesgo de que no vuelva a encender. */
    @Column(name = "trabajo_en_placa", nullable = false)
    @Builder.Default
    private boolean trabajoEnPlaca = false;

    /** No enciende / sin carga: el diagnóstico es provisorio y la condición no se pudo verificar. */
    @Column(name = "no_testeable_al_ingreso", nullable = false)
    @Builder.Default
    private boolean noTesteableAlIngreso = false;

    /** Tiene PIN/patrón de pantalla (se guarda en pinDesbloqueo/patronDesbloqueo). */
    @Column(name = "tiene_bloqueo_pantalla", nullable = false)
    @Builder.Default
    private boolean tieneBloqueoPantalla = false;

    /** Cuenta vinculada activa (Activation Lock / FRP). */
    @Enumerated(EnumType.STRING)
    @Column(name = "tiene_cuenta_vinculada", nullable = false, length = 10)
    @Builder.Default
    private CuentaVinculada tieneCuentaVinculada = CuentaVinculada.NINGUNA;

    /** El cliente conoce las credenciales para quitar la cuenta. */
    @Column(name = "cliente_conoce_credenciales", nullable = false)
    @Builder.Default
    private boolean clienteConoceCredenciales = false;

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
    @Builder.Default
    private List<FotoReparacion> fotos = new ArrayList<>();

    /** Fecha/hora en que el cliente retiró conforme (registro anti-disputa, §8). */
    @Column(name = "fecha_conformidad_entrega")
    private LocalDateTime fechaConformidadEntrega;

    // ----- Garantía del trabajo (§7) -----
    /** Días de garantía (se fija al entregar; configurable por taller). */
    @Column(name = "garantia_dias")
    private Integer garantiaDias;

    /** Inicio de la garantía = fecha de entrega. */
    @Column(name = "garantia_inicio")
    private LocalDate garantiaInicio;

    /** Fin de la garantía = inicio + garantiaDias. */
    @Column(name = "garantia_fin")
    private LocalDate garantiaFin;

    @Column(name = "garantia_condiciones", length = 1000)
    private String garantiaCondiciones;

    /** Esta reparación es un retrabajo en garantía de otra (no cobra ni consume cupo). */
    @Column(name = "es_garantia", nullable = false)
    @Builder.Default
    private boolean esGarantia = false;

    /** Si es un reclamo en garantía, la reparación original que lo originó. */
    @Column(name = "reparacion_origen_id")
    private Long reparacionOrigenId;

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

    // Los presupuestos son parte de la orden: se limpian al borrar la reparación.
    // (Los cobros NO: son historial/caja y se protegen en el service.)
    @OneToMany(mappedBy = "reparacion", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    @Builder.Default
    private List<Presupuesto> presupuestos = new ArrayList<>();

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

    /**
     * Bandera roja (§2): equipo con cuenta activa y el cliente no conoce las credenciales.
     * Puede no poder entregarse activado tras la reparación.
     */
    @Transient
    public boolean isRiesgoCuentaSinCredenciales() {
        return tieneCuentaVinculada != null
                && tieneCuentaVinculada != CuentaVinculada.NINGUNA
                && !clienteConoceCredenciales;
    }

    /** ¿La garantía sigue vigente hoy? (hay fin de garantía y aún no pasó). */
    @Transient
    public boolean isGarantiaVigente() {
        return garantiaFin != null && !LocalDate.now().isAfter(garantiaFin);
    }

    /** Total a cobrar: mano de obra (precioFinal ?? precioEstimado ?? 0) + repuestos. */
    @Transient
    public BigDecimal calcularTotal() {
        BigDecimal manoDeObra = precioFinal != null ? precioFinal
                : (precioEstimado != null ? precioEstimado : BigDecimal.ZERO);
        return manoDeObra.add(calcularTotalRepuestos());
    }
}

