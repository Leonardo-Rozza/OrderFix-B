package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

public enum EstadoPresupuesto {
    PENDIENTE,
    APROBADO,
    RECHAZADO,
    /** Derivado: PENDIENTE cuya validez ya pasó. No se almacena (se calcula en la respuesta). */
    VENCIDO
}
