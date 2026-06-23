package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

/**
 * Naturaleza de una línea de presupuesto: mano de obra o repuesto. Permite
 * discriminar el total (el cliente quiere ver el desglose y el taller, el margen).
 */
public enum TipoItemPresupuesto {
    MANO_DE_OBRA,
    REPUESTO
}
