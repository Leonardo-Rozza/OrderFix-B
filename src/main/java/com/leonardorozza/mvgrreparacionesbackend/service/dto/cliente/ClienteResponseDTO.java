package com.leonardorozza.mvgrreparacionesbackend.service.dto.cliente;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClienteResponseDTO {

    private Long id;
    private String nombre;
    private String apellido;
    private String telefono;
    private String email;
    private String direccion;
    // Denormalizado (agregados)
    private long equiposCount;
    private long reparacionesCount;
    private LocalDateTime ultimaVisita;
}