package com.leonardorozza.mvgrreparacionesbackend.service.dto.cobro;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Datos completos de una reparación para imprimir/compartir un recibo.
 */
public record ReciboDTO(
        Long reparacionId,
        String codigoSeguimiento,
        EstadoReparacion estado,
        // Taller
        String tallerNombre,
        String tallerTelefono,
        // Cliente
        String clienteNombre,
        String clienteApellido,
        String clienteTelefono,
        // Equipo
        String equipoMarca,
        String equipoModelo,
        String descripcionProblema,
        // Montos
        List<ItemReciboDTO> repuestos,
        BigDecimal manoDeObra,
        BigDecimal totalRepuestos,
        BigDecimal total,
        BigDecimal cobrado,
        BigDecimal saldo,
        boolean pagado,
        LocalDateTime fecha
) {
    public record ItemReciboDTO(String nombre, int cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {
    }
}
