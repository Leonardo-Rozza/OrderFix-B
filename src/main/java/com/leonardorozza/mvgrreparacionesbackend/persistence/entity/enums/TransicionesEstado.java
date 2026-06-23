package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.ConflictException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion.*;

/**
 * Máquina de transiciones de estado de una reparación.
 *
 * <p>Define qué saltos entre estados son legales (el grafo del flujo real del rubro).
 * Antes solo se validaba que el estado existiera en el enum; ahora también se valida
 * que el salto desde el estado actual sea uno permitido. Un salto ilegal es un
 * conflicto con el estado actual del recurso → {@link ConflictException} (HTTP 409).
 *
 * <p>Reglas:
 * <ul>
 *   <li>Quedarse en el mismo estado (X → X) es un no-op permitido (idempotente).</li>
 *   <li>ENTREGADO, CANCELADO y ABANDONADO son terminales: no se sale de ellos.</li>
 * </ul>
 */
public final class TransicionesEstado {

    private static final Map<EstadoReparacion, Set<EstadoReparacion>> PERMITIDAS =
            new EnumMap<>(EstadoReparacion.class);

    static {
        PERMITIDAS.put(INGRESADO, EnumSet.of(EN_DIAGNOSTICO, PRESUPUESTADO, EN_PROCESO, CANCELADO));
        PERMITIDAS.put(EN_DIAGNOSTICO, EnumSet.of(PRESUPUESTADO, NO_REPARABLE, CANCELADO));
        PERMITIDAS.put(PRESUPUESTADO, EnumSet.of(EN_PROCESO, LISTO_SIN_REPARAR, CANCELADO));
        PERMITIDAS.put(EN_PROCESO, EnumSet.of(ESPERANDO_REPUESTO, ESPERANDO_ADICIONAL, COMPLETADO, NO_REPARABLE));
        PERMITIDAS.put(ESPERANDO_REPUESTO, EnumSet.of(EN_PROCESO, NO_REPARABLE, CANCELADO));
        PERMITIDAS.put(ESPERANDO_ADICIONAL, EnumSet.of(EN_PROCESO, COMPLETADO, LISTO_SIN_REPARAR));
        PERMITIDAS.put(NO_REPARABLE, EnumSet.of(LISTO_SIN_REPARAR));
        PERMITIDAS.put(COMPLETADO, EnumSet.of(ENTREGADO, ABANDONADO));
        PERMITIDAS.put(LISTO_SIN_REPARAR, EnumSet.of(ENTREGADO, ABANDONADO));
        // Estados terminales: sin salida.
        PERMITIDAS.put(ENTREGADO, EnumSet.noneOf(EstadoReparacion.class));
        PERMITIDAS.put(ABANDONADO, EnumSet.noneOf(EstadoReparacion.class));
        PERMITIDAS.put(CANCELADO, EnumSet.noneOf(EstadoReparacion.class));
    }

    private TransicionesEstado() {
    }

    /** ¿Es legal pasar de {@code desde} a {@code hacia}? (X → X siempre lo es). */
    public static boolean permitida(EstadoReparacion desde, EstadoReparacion hacia) {
        if (desde == hacia) {
            return true;
        }
        return PERMITIDAS.getOrDefault(desde, EnumSet.noneOf(EstadoReparacion.class)).contains(hacia);
    }

    /**
     * Valida la transición; si no es legal lanza {@link ConflictException} (409).
     * No hace nada si el salto es válido o si es un no-op (mismo estado).
     */
    public static void validar(EstadoReparacion desde, EstadoReparacion hacia) {
        if (!permitida(desde, hacia)) {
            throw new ConflictException(
                    "No se puede pasar de " + desde + " a " + hacia + ".");
        }
    }
}
