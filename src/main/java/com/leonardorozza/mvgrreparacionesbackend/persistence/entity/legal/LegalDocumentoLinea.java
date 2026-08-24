package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.converter.LocaleLegalConverter;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_documento_lineas", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_documento_lineas_clave", columnNames = "clave"),
        @UniqueConstraint(name = "uk_legal_documento_lineas_identidad", columnNames = {"id", "tipo", "locale"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocumentoLinea {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_intro_id", nullable = false, updatable = false)
    private LegalPublicacion publicacionIntro;

    @Column(name = "clave", nullable = false, updatable = false, length = 120)
    private String clave;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, updatable = false, length = 50)
    private TipoDocumentoLegal tipo;

    @Convert(converter = LocaleLegalConverter.class)
    @Column(name = "locale", nullable = false, updatable = false, length = 5)
    private LocaleLegal locale;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    public LegalDocumentoLinea(LegalPublicacion publicacionIntro, String clave,
                               TipoDocumentoLegal tipo, LocaleLegal locale, Instant creadoEn) {
        this.publicacionIntro = Objects.requireNonNull(publicacionIntro);
        this.clave = Objects.requireNonNull(clave);
        this.tipo = Objects.requireNonNull(tipo);
        this.locale = Objects.requireNonNull(locale);
        this.creadoEn = Objects.requireNonNull(creadoEn);
    }
}
