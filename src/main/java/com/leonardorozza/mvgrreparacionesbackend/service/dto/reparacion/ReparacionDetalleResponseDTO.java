package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

import lombok.Getter;
import lombok.Setter;

/**
 * Vista autenticada de detalle. Es el único DTO de salida que contiene las
 * credenciales de acceso descifradas; no redefine toString para evitar logs.
 */
@Getter
@Setter
public class ReparacionDetalleResponseDTO extends ReparacionResponseDTO {

    private String patronDesbloqueo;
    private String pinDesbloqueo;
}
