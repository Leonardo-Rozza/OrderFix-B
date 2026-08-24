package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import jakarta.persistence.Column;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "legal_documento_transiciones")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocumentoTransicion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_version_id", nullable = false, updatable = false)
    private LegalDocumentoVersion documentoVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_anterior", nullable = false, updatable = false, length = 20)
    private EstadoVersionLegal estadoAnterior;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_nuevo", nullable = false, updatable = false, length = 20)
    private EstadoVersionLegal estadoNuevo;

    @Column(name = "ocurrido_en", nullable = false, updatable = false)
    private Instant ocurridoEn;

    @Column(name = "motivo", updatable = false, length = 1000)
    private String motivo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reemplazo_lote_id", updatable = false)
    private LegalDocumentoReemplazoLote reemplazoLote;

    public LegalDocumentoTransicion(LegalDocumentoVersion documentoVersion,
                                    EstadoVersionLegal estadoAnterior,
                                    EstadoVersionLegal estadoNuevo,
                                    Instant ocurridoEn, String motivo,
                                    LegalDocumentoReemplazoLote reemplazoLote) {
        this.documentoVersion = Objects.requireNonNull(documentoVersion);
        this.estadoAnterior = Objects.requireNonNull(estadoAnterior);
        this.estadoNuevo = Objects.requireNonNull(estadoNuevo);
        this.ocurridoEn = Objects.requireNonNull(ocurridoEn);
        this.motivo = motivo;
        this.reemplazoLote = reemplazoLote;
    }
}
