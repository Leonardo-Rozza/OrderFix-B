package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

/**
 * Calidad/origen de un repuesto. Cambia precio, garantía y funcionalidad
 * (ej. parts pairing en Apple). El cliente elige y queda registrado (§5).
 */
public enum CalidadRepuesto {
    ORIGINAL,
    ALTERNATIVO,
    USADO_REACONDICIONADO
}
