package com.leonardorozza.mvgrreparacionesbackend.service.dto.presupuesto;

import jakarta.validation.Valid;
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
}
