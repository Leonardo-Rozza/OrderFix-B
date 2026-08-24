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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_documento_versiones", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_doc_version", columnNames = {"documento_linea_id", "version"}),
        @UniqueConstraint(name = "uk_legal_doc_lineage", columnNames = {"documento_linea_id", "lineage_ordinal"}),
        @UniqueConstraint(name = "uk_legal_doc_identidad", columnNames = {"id", "documento_linea_id"}),
        @UniqueConstraint(name = "uk_legal_doc_intro", columnNames = {"id", "publicacion_intro_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocumentoVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_linea_id", nullable = false, updatable = false)
    private LegalDocumentoLinea linea;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_intro_id", nullable = false, updatable = false)
    private LegalPublicacion publicacionIntro;

    @Column(name = "version", nullable = false, updatable = false, length = 64)
    private String version;

    @Column(name = "lineage_ordinal", nullable = false, updatable = false)
    private int lineageOrdinal;

    @Column(name = "titulo", nullable = false, updatable = false, length = 300)
    private String titulo;

    @Column(name = "contenido_markdown", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String contenidoMarkdown;

    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "vigente_desde", nullable = false, updatable = false)
    private Instant vigenteDesde;

    @Column(name = "requires_reacceptance", nullable = false, updatable = false)
    private boolean requiresReacceptance;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private EstadoVersionLegal estado;

    @Column(name = "estado_cambiado_en")
    private Instant estadoCambiadoEn;

    @Column(name = "ultimo_motivo", length = 1000)
    private String ultimoMotivo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reemplazo_lote_id", insertable = false, updatable = false)
    private LegalDocumentoReemplazoLote reemplazoLote;

    public LegalDocumentoVersion(LegalDocumentoLinea linea, LegalPublicacion publicacionIntro,
                                 String version, int lineageOrdinal, String titulo,
                                 String contenidoMarkdown, String sha256, Instant vigenteDesde,
                                 boolean requiresReacceptance) {
        this.linea = Objects.requireNonNull(linea);
        this.publicacionIntro = Objects.requireNonNull(publicacionIntro);
        this.version = Objects.requireNonNull(version);
        this.lineageOrdinal = lineageOrdinal;
        this.titulo = Objects.requireNonNull(titulo);
        this.contenidoMarkdown = Objects.requireNonNull(contenidoMarkdown);
        this.sha256 = Objects.requireNonNull(sha256);
        this.vigenteDesde = Objects.requireNonNull(vigenteDesde);
        this.requiresReacceptance = requiresReacceptance;
        this.estado = EstadoVersionLegal.BORRADOR;
    }
}
