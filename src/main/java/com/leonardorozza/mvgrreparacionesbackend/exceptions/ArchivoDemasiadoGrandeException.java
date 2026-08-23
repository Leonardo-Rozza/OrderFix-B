package com.leonardorozza.mvgrreparacionesbackend.exceptions;

public class ArchivoDemasiadoGrandeException extends RuntimeException {

    public static final String CODE = "ARCHIVO_DEMASIADO_GRAN";

    public ArchivoDemasiadoGrandeException(String message) {
        super(message);
    }
}
