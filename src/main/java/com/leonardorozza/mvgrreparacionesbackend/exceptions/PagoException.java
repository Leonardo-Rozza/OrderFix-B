package com.leonardorozza.mvgrreparacionesbackend.exceptions;

/**
 * Error al interactuar con el proveedor de pagos (MercadoPago).
 * Se mapea a 502 Bad Gateway.
 */
public class PagoException extends RuntimeException {

    public PagoException(String message) {
        super(message);
    }

    public PagoException(String message, Throwable cause) {
        super(message, cause);
    }
}
