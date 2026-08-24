package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
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

@Entity
@Table(name = "legal_documento_contextos", uniqueConstraints =
        @UniqueConstraint(name = "uk_legal_doc_contexto", columnNames = {"documento_version_id", "contexto"}))
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocumentoContexto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_version_id", nullable = false, updatable = false)
    private LegalDocumentoVersion documentoVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "contexto", nullable = false, updatable = false, length = 40)
    private ContextoLegal contexto;

    public LegalDocumentoContexto(LegalDocumentoVersion documentoVersion, ContextoLegal contexto) {
        this.documentoVersion = Objects.requireNonNull(documentoVersion);
        this.contexto = Objects.requireNonNull(contexto);
    }
}
