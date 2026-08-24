package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.id.LegalDocumentoVigenteId;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_documento_vigentes", uniqueConstraints =
        @UniqueConstraint(name = "uk_legal_doc_vigente_version_contexto",
                columnNames = {"documento_version_id", "contexto"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocumentoVigente {

    @EmbeddedId
    private LegalDocumentoVigenteId id;

    @Column(name = "documento_linea_id", nullable = false, updatable = false)
    private UUID documentoLineaId;

    @Column(name = "publicacion_id", nullable = false, updatable = false)
    private UUID publicacionId;

    @Column(name = "documento_version_id", nullable = false, updatable = false)
    private UUID documentoVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_documento", nullable = false, updatable = false, length = 20)
    private EstadoVersionLegal estadoDocumento;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_linea_id", nullable = false, insertable = false, updatable = false)
    private LegalDocumentoLinea documentoLinea;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_id", nullable = false, insertable = false, updatable = false)
    private LegalPublicacion publicacion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_version_id", nullable = false, insertable = false, updatable = false)
    private LegalDocumentoVersion documentoVersion;

    public LegalDocumentoVigente(LegalDocumentoVigenteId id,
                                 UUID documentoLineaId, UUID publicacionId,
                                 UUID documentoVersionId, EstadoVersionLegal estadoDocumento) {
        this.id = Objects.requireNonNull(id);
        this.documentoLineaId = Objects.requireNonNull(documentoLineaId);
        this.publicacionId = Objects.requireNonNull(publicacionId);
        this.documentoVersionId = Objects.requireNonNull(documentoVersionId);
        this.estadoDocumento = Objects.requireNonNull(estadoDocumento);
    }
}
