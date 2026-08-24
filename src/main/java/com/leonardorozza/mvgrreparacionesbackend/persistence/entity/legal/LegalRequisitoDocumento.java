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
@Table(name = "legal_requisito_documentos", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_req_documento", columnNames = {"requisito_version_id", "documento_version_id"}),
        @UniqueConstraint(name = "uk_legal_req_doc_ordinal", columnNames = {"requisito_version_id", "documento_ordinal"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalRequisitoDocumento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requisito_version_id", nullable = false, updatable = false)
    private LegalRequisitoVersion requisitoVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_version_id", nullable = false, updatable = false)
    private LegalDocumentoVersion documentoVersion;

    @Column(name = "documento_ordinal", nullable = false, updatable = false)
    private int documentoOrdinal;

    public LegalRequisitoDocumento(LegalRequisitoVersion requisitoVersion,
                                   LegalDocumentoVersion documentoVersion,
                                   int documentoOrdinal) {
        this.requisitoVersion = Objects.requireNonNull(requisitoVersion);
        this.documentoVersion = Objects.requireNonNull(documentoVersion);
        this.documentoOrdinal = documentoOrdinal;
    }
}
