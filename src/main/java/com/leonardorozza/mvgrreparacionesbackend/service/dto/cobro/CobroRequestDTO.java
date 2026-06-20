package com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CobroRequestDTO {

    @NotNull
    @Positive
    private BigDecimal monto;

    @NotNull
    private MetodoPago metodo;

    @Size(max = 255)
    private String observaciones;
}
