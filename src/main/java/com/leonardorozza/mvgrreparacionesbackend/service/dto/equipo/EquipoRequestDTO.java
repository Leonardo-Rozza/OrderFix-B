package com.leonardorozza.mvgrreparacionesbackend.service.dto.equipo;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EquipoTipo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EquipoRequestDTO {

    @NotBlank
    @Size(max = 60)
    private String marca;

    @NotBlank
    @Size(max = 60)
    private String modelo;

    /** Optional for older clients: create defaults to OTRO; update preserves the current type. */
    private EquipoTipo tipo;

    @Size(max = 30)
    private String imei;

    @Size(max = 40)
    private String color;

    @Size(max = 255)
    private String descripcion;

    @NotNull
    private Long clienteId;
}
