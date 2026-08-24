package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

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
@Table(name = "legal_documento_reemplazo_sucesoras", uniqueConstraints =
        @UniqueConstraint(name = "uk_legal_reemplazo_sucesora", columnNames = {"lote_id", "documento_version_id"}))
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocumentoReemplazoSucesora {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lote_id", nullable = false, updatable = false)
    private LegalDocumentoReemplazoLote reemplazoLote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_id", nullable = false, updatable = false)
    private LegalPublicacion publicacion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_version_id", nullable = false, updatable = false)
    private LegalDocumentoVersion documentoVersion;

    public LegalDocumentoReemplazoSucesora(LegalDocumentoReemplazoLote reemplazoLote,
                                           LegalPublicacion publicacion,
                                           LegalDocumentoVersion documentoVersion) {
        this.reemplazoLote = Objects.requireNonNull(reemplazoLote);
        this.publicacion = Objects.requireNonNull(publicacion);
        this.documentoVersion = Objects.requireNonNull(documentoVersion);
    }
}
