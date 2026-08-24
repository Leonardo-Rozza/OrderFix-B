package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "legal_publicacion_documentos", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_pub_doc_version", columnNames = {"publicacion_id", "documento_version_id"}),
        @UniqueConstraint(name = "uk_legal_pub_doc_ordinal", columnNames = {"publicacion_id", "manifest_ordinal"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalPublicacionDocumento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_id", nullable = false, updatable = false)
    private LegalPublicacion publicacion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_version_id", nullable = false, updatable = false)
    private LegalDocumentoVersion documentoVersion;

    @Column(name = "manifest_ordinal", nullable = false, updatable = false)
    private int manifestOrdinal;

    public LegalPublicacionDocumento(LegalPublicacion publicacion,
                                     LegalDocumentoVersion documentoVersion,
                                     int manifestOrdinal) {
        this.publicacion = Objects.requireNonNull(publicacion);
        this.documentoVersion = Objects.requireNonNull(documentoVersion);
        this.manifestOrdinal = manifestOrdinal;
    }
}
