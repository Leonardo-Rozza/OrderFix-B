package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.CuentaVinculada;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPago;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReparacionResponseDTO {

    private Long id;
    private Long equipoId;
    // Denormalizado (para listados autocontenidos)
    private String equipoMarca;
    private String equipoModelo;
    private Long clienteId;
    private String clienteNombre;
    private String clienteApellido;
    private String clienteTelefono;
    private String descripcionProblema;
    private EstadoReparacion estado;
    private BigDecimal precioEstimado;
    private BigDecimal precioFinal;
    private LocalDate fechaIngreso;
    private LocalDate fechaEstimadaEntrega;
    private LocalDate fechaEntrega;

    /** Código público de seguimiento (para compartir con el cliente). */
    private String codigoSeguimiento;

    /** Número de orden mostrable (correlativo por taller, ej: ORD-2026-0042). */
    private String numeroOrden;

    // ----- Flags de riesgo del ingreso -----
    private boolean mojado;
    private boolean trabajoEnPlaca;
    private boolean noTesteableAlIngreso;
    private boolean tieneBloqueoPantalla;
    private CuentaVinculada tieneCuentaVinculada;
    private boolean clienteConoceCredenciales;
    /** Derivada: cuenta activa + cliente no conoce credenciales → puede no entregarse activado. */
    private boolean riesgoCuentaSinCredenciales;

    // ----- Orden de trabajo ampliada -----
    private String patronDesbloqueo;
    private String pinDesbloqueo;
    private String accesorios;
    private String condicionesIngreso;
    private String observaciones;
    private Long tecnicoId;
    private String tecnicoNombre;
    private List<FotoDTO> fotos;
    /** Fecha/hora en que el cliente retiró conforme (null si todavía no se entregó). */
    private LocalDateTime fechaConformidadEntrega;

    // ----- Garantía -----
    private Integer garantiaDias;
    private LocalDate garantiaInicio;
    private LocalDate garantiaFin;
    private String garantiaCondiciones;
    /** Derivado: hay garantiaFin y todavía no pasó. */
    private boolean garantiaVigente;
    /** Esta reparación es un retrabajo en garantía (no cobra ni consume cupo). */
    private boolean esGarantia;
    /** Si es reclamo en garantía, ID de la reparación original. */
    private Long reparacionOrigenId;

    /** Suma de los precios de los repuestos asociados. */
    private BigDecimal totalRepuestos;

    /** Total a cobrar: mano de obra (precioFinal ?? precioEstimado ?? 0) + repuestos. */
    private BigDecimal total;

    // ----- Estado de pago (dimensión independiente del estado de reparación) -----
    /** Suma de los cobros registrados (0 en planes FREE: cobros es PRO). */
    private BigDecimal cobrado;
    /** Saldo pendiente = max(0, total - cobrado). */
    private BigDecimal saldo;
    /** Derivado de total vs cobrado: SIN_COBRAR | PARCIAL | PAGADO. */
    private EstadoPago estadoPago;
}
