package com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion;

/**
 * Link de WhatsApp (wa.me) listo para que el taller avise al cliente.
 * El front puede abrir {@code url} en una pestaña nueva.
 */
public record WhatsappLinkDTO(
        String url,
        String telefono,
        String mensaje,
        String linkSeguimiento
) {
}
