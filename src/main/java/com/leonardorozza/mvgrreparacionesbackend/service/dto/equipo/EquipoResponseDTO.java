package com.leonardorozza.mvgrreparacionesbackend.service.dto.equipo;

import lombok.Data;

@Data
public class EquipoResponseDTO {
    private Long id;
    private String marca;
    private String modelo;
    private String imei;
    private String color;
    private String descripcion;
    private Long clienteId;
    // Denormalizado
    private String clienteNombre;
    private String clienteApellido;
    private String clienteTelefono;
    private long reparacionesCount;
}
