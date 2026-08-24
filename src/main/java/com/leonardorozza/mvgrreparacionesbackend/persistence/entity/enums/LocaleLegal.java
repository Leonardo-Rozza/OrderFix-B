package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

import java.util.Arrays;

public enum LocaleLegal {
    ES_AR("es-AR");

    private final String codigo;

    LocaleLegal(String codigo) {
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }

    public static LocaleLegal fromCodigo(String codigo) {
        return Arrays.stream(values())
                .filter(locale -> locale.codigo.equals(codigo))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Locale legal desconocido: " + codigo));
    }
}
