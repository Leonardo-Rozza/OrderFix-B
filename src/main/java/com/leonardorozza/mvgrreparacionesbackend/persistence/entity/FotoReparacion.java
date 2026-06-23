package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MomentoFoto;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;

/**
 * Foto del equipo (se guarda en la tabla reparacion_fotos). La URL la sube el
 * front a su storage; acá guardamos la referencia + el momento (ingreso/post).
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FotoReparacion {

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "momento", nullable = false, length = 20)
    @Builder.Default
    private MomentoFoto momento = MomentoFoto.INGRESO;
}
