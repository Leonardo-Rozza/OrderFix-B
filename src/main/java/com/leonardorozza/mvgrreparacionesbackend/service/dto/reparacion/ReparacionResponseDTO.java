package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ReparacionResponseDTO {

    private Long id;
    private Long equipoId;
    private String descripcionProblema;
    private EstadoReparacion estado;
    private BigDecimal precioEstimado;
    private BigDecimal precioFinal;
    private LocalDate fechaIngreso;
    private LocalDate fechaEstimadaEntrega;
    private LocalDate fechaEntrega;

    /** Código público de seguimiento (para compartir con el cliente). */
    private String codigoSeguimiento;

    /** Suma de los precios de los repuestos asociados. */
    private BigDecimal totalRepuestos;

    /** Total a cobrar: mano de obra (precioFinal ?? precioEstimado ?? 0) + repuestos. */
    private BigDecimal total;
}
