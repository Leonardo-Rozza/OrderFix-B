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
import java.util.UUID;

@Entity
@Table(name = "legal_requisito_conjunto_miembros", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_req_conjunto_linea", columnNames = {"conjunto_id", "requisito_linea_id"}),
        @UniqueConstraint(name = "uk_legal_req_conjunto_ordinal", columnNames = {"conjunto_id", "manifest_ordinal"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalRequisitoConjuntoMiembro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conjunto_id", nullable = false, updatable = false)
    private UUID conjuntoId;

    @Column(name = "publicacion_id", nullable = false, updatable = false)
    private UUID publicacionId;

    @Column(name = "requisito_version_id", nullable = false, updatable = false)
    private UUID requisitoVersionId;

    @Column(name = "requisito_linea_id", nullable = false, updatable = false)
    private UUID requisitoLineaId;

    @Column(name = "manifest_ordinal", nullable = false, updatable = false)
    private int manifestOrdinal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conjunto_id", nullable = false, insertable = false, updatable = false)
    private LegalRequisitoConjunto conjunto;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_id", nullable = false, insertable = false, updatable = false)
    private LegalPublicacion publicacion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requisito_version_id", nullable = false, insertable = false, updatable = false)
    private LegalRequisitoVersion requisitoVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requisito_linea_id", nullable = false, insertable = false, updatable = false)
    private LegalRequisitoLinea requisitoLinea;

    public LegalRequisitoConjuntoMiembro(UUID conjuntoId, UUID publicacionId,
                                         UUID requisitoVersionId, UUID requisitoLineaId,
                                         int manifestOrdinal) {
        this.conjuntoId = Objects.requireNonNull(conjuntoId);
        this.publicacionId = Objects.requireNonNull(publicacionId);
        this.requisitoVersionId = Objects.requireNonNull(requisitoVersionId);
        this.requisitoLineaId = Objects.requireNonNull(requisitoLineaId);
        this.manifestOrdinal = manifestOrdinal;
    }
}
