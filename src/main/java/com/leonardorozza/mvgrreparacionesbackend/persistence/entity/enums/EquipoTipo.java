package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/** Classification only: every category uses the same repair workflow. */
public enum EquipoTipo {
    CELULAR,
    NOTEBOOK,
    CONSOLA,
    PC_ESCRITORIO,
    MONITOR,
    TV,
    OTRO;

    /** Accept symbolic wire values, never enum ordinals. Null preserves legacy requests. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static EquipoTipo fromJson(String value) {
        return value == null ? null : valueOf(value);
    }
}
