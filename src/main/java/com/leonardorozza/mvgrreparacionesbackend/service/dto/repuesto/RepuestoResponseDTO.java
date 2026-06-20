package com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RepuestoResponseDTO {

    private Long id;
    private String nombre;
    private String descripcion;
    private BigDecimal precio;
    private Long reparacionId;
    private String reparacionEquipo; // label "Marca Modelo" de la reparación asociada (o null)
    private Long articuloId;
    private int cantidad;

}
