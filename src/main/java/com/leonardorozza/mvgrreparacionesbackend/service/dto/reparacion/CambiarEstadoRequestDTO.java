package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Body para cambiar el estado de una reparación: { "estado": "EN_PROCESO" }.
 */
@Data
public class CambiarEstadoRequestDTO {

    @NotNull
    private EstadoReparacion estado;
}
