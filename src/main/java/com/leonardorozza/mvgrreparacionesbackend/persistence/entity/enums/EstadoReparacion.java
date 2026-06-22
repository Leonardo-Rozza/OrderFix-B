package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

/**
 * Estados del ciclo de vida de una reparación.
 *
 * <p>Las transiciones permitidas entre estados las define {@code TransicionesEstado};
 * el enum solo enumera los valores posibles. Los 5 estados originales
 * (INGRESADO, EN_PROCESO, ESPERANDO_REPUESTO, COMPLETADO, ENTREGADO) se conservan
 * para no romper datos existentes; el resto modela los caminos reales del rubro
 * ("no salió", "el cliente no aceptó", "qué hago con el equipo").
 */
public enum EstadoReparacion {
    INGRESADO,             // entró, sin diagnóstico
    EN_DIAGNOSTICO,        // se está revisando para presupuestar
    PRESUPUESTADO,         // hay presupuesto, esperando que el cliente acepte/rechace
    EN_PROCESO,            // presupuesto aprobado (o arreglo simple), reparando
    ESPERANDO_REPUESTO,    // falta una parte
    ESPERANDO_ADICIONAL,   // apareció trabajo extra, esperando aprobación
    NO_REPARABLE,          // diagnosticado, no tiene arreglo / no conviene
    COMPLETADO,            // reparado, listo para retirar
    LISTO_SIN_REPARAR,     // listo para devolver sin arreglo (rechazó presup. o no reparable)
    ENTREGADO,             // el cliente lo retiró
    ABANDONADO,            // no lo retiró en el plazo definido
    CANCELADO              // anulado
}
