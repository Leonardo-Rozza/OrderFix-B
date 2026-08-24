package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.id;

import com.leonardorozza.mvgrreparacionesbackend.persistence.converter.LocaleLegalConverter;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LegalRequisitoConjuntoActualId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Convert(converter = LocaleLegalConverter.class)
    @Column(name = "locale", nullable = false, length = 5)
    private LocaleLegal locale;

    @Enumerated(EnumType.STRING)
    @Column(name = "contexto", nullable = false, length = 40)
    private ContextoLegal contexto;

    @Enumerated(EnumType.STRING)
    @Column(name = "audiencia", nullable = false, length = 20)
    private AudienciaLegal audiencia;
}
