package com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoPresupuesto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PresupuestoRequestDTO {

    @NotEmpty
    @Valid
    private List<ItemPresupuestoDTO> items;

    @Size(max = 1000)
    private String observaciones;

    /** ORIGINAL | ADICIONAL (null = ORIGINAL). */
    private TipoPresupuesto tipo;

    /** Días de validez (null = default del backend, 7). */
    @Min(1)
    private Integer validezDias;
}
