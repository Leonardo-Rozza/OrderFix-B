package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.converter.LocaleLegalConverter;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
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
@Table(name = "legal_requisito_conjuntos", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_req_conjunto_scope",
                columnNames = {"publicacion_id", "locale", "contexto", "audiencia"}),
        @UniqueConstraint(name = "uk_legal_req_conjunto_identidad",
                columnNames = {"id", "publicacion_id", "locale", "contexto", "audiencia"}),
        @UniqueConstraint(name = "uk_legal_req_conjunto_publicacion", columnNames = {"id", "publicacion_id"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalRequisitoConjunto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_id", nullable = false, updatable = false)
    private LegalPublicacion publicacion;

    @Convert(converter = LocaleLegalConverter.class)
    @Column(name = "locale", nullable = false, updatable = false, length = 5)
    private LocaleLegal locale;

    @Enumerated(EnumType.STRING)
    @Column(name = "contexto", nullable = false, updatable = false, length = 40)
    private ContextoLegal contexto;

    @Enumerated(EnumType.STRING)
    @Column(name = "audiencia", nullable = false, updatable = false, length = 20)
    private AudienciaLegal audiencia;

    @Column(name = "required_set_revision", nullable = false, updatable = false, length = 71)
    private String requiredSetRevision;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    public LegalRequisitoConjunto(LegalPublicacion publicacion, LocaleLegal locale,
                                  ContextoLegal contexto, AudienciaLegal audiencia,
                                  String requiredSetRevision, Instant creadoEn) {
        this.publicacion = Objects.requireNonNull(publicacion);
        this.locale = Objects.requireNonNull(locale);
        this.contexto = Objects.requireNonNull(contexto);
        this.audiencia = Objects.requireNonNull(audiencia);
        this.requiredSetRevision = Objects.requireNonNull(requiredSetRevision);
        this.creadoEn = Objects.requireNonNull(creadoEn);
    }
}
