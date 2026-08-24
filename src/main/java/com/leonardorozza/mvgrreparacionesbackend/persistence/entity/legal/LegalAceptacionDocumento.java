package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
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
import org.hibernate.annotations.Immutable;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_aceptacion_documentos", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_aceptacion_documento", columnNames = {"aceptacion_id", "documento_version_id"}),
        @UniqueConstraint(name = "uk_legal_aceptacion_doc_ordinal", columnNames = {"aceptacion_id", "documento_ordinal"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalAceptacionDocumento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aceptacion_id", nullable = false, updatable = false)
    private LegalAceptacion aceptacion;

    @Column(name = "documento_version_id", nullable = false, updatable = false)
    private UUID documentoVersionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_version_id", nullable = false, insertable = false, updatable = false)
    private LegalDocumentoVersion documentoVersion;

    @Column(name = "documento_clave", nullable = false, updatable = false, length = 120)
    private String documentoClave;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, updatable = false, length = 50)
    private TipoDocumentoLegal tipo;

    @Column(name = "version", nullable = false, updatable = false, length = 64)
    private String documentoVersionSnapshot;

    @Column(name = "titulo", nullable = false, updatable = false, length = 300)
    private String titulo;

    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "documento_ordinal", nullable = false, updatable = false)
    private int documentoOrdinal;

    public LegalAceptacionDocumento(LegalAceptacion aceptacion, UUID documentoVersionId,
                                    String documentoClave, TipoDocumentoLegal tipo,
                                    String documentoVersionSnapshot, String titulo,
                                    String sha256, int documentoOrdinal) {
        this.aceptacion = Objects.requireNonNull(aceptacion);
        this.documentoVersionId = Objects.requireNonNull(documentoVersionId);
        this.documentoClave = Objects.requireNonNull(documentoClave);
        this.tipo = Objects.requireNonNull(tipo);
        this.documentoVersionSnapshot = Objects.requireNonNull(documentoVersionSnapshot);
        this.titulo = Objects.requireNonNull(titulo);
        this.sha256 = Objects.requireNonNull(sha256);
        this.documentoOrdinal = documentoOrdinal;
    }
}
