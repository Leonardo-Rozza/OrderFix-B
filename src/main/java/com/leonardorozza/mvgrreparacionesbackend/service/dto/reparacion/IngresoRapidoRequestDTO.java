package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Carga rápida de una reparación en el mostrador: crea (o reutiliza) el cliente,
 * crea el equipo y la reparación de una sola vez con los datos imprescindibles.
 * El resto (apellido, email, IMEI, fechas, etc.) se completa después desde las
 * pantallas de Cliente / Equipo / Reparación.
 */
@Data
public class IngresoRapidoRequestDTO {

    // ----- Cliente (mínimo) -----
    @NotBlank
    @Size(max = 60)
    private String clienteNombre;

    /** Opcional en la carga rápida; se puede completar luego. */
    @Size(max = 60)
    private String clienteApellido;

    @NotBlank
    @Size(max = 20)
    private String clienteTelefono;

    // ----- Equipo (mínimo) -----
    @NotBlank
    @Size(max = 60)
    private String equipoMarca;

    @NotBlank
    @Size(max = 60)
    private String equipoModelo;

    // ----- Reparación (mínimo) -----
    @NotBlank
    @Size(max = 255)
    private String descripcionProblema;

    /** Opcional. */
    private BigDecimal precioEstimado;
}
