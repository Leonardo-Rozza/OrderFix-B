package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class ReparacionRequestDTO {

    @NotNull
    private Long equipoId;

    @NotBlank
    private String descripcionProblema;

    private EstadoReparacion estado;

    private BigDecimal precioEstimado;

    private BigDecimal precioFinal;

    private LocalDate fechaIngreso;

    private LocalDate fechaEstimadaEntrega;

    private LocalDate fechaEntrega;

    // ----- Orden de trabajo ampliada (todo opcional) -----
    @Size(max = 60)
    private String patronDesbloqueo;

    @Size(max = 20)
    private String pinDesbloqueo;

    @Size(max = 255)
    private String accesorios;

    @Size(max = 500)
    private String condicionesIngreso;

    @Size(max = 1000)
    private String observaciones;

    /** ID del usuario del taller asignado como técnico. */
    private Long tecnicoId;

    /** URLs de fotos del equipo (la subida la hace el front a su storage). */
    private List<String> fotos;
}
