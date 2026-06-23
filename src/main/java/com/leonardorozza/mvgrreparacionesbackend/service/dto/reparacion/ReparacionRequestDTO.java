package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.CuentaVinculada;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    // ----- Flags de riesgo del ingreso (todo opcional; default false / NINGUNA) -----
    private boolean mojado;
    private boolean trabajoEnPlaca;
    private boolean noTesteableAlIngreso;
    private boolean tieneBloqueoPantalla;
    /** NINGUNA | ICLOUD | GOOGLE | OTRA (null = NINGUNA). */
    private CuentaVinculada tieneCuentaVinculada;
    private boolean clienteConoceCredenciales;

    /** ID del usuario del taller asignado como técnico. */
    private Long tecnicoId;

    /** Fotos del equipo con su momento (la subida la hace el front a su storage). */
    @Valid
    private List<FotoDTO> fotos;

    /** Fecha/hora de conformidad de entrega (opcional; si no, se setea al pasar a ENTREGADO). */
    private LocalDateTime fechaConformidadEntrega;
}
