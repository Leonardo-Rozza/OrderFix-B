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
@Table(name = "legal_publicacion_requisitos", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_pub_req_version", columnNames = {"publicacion_id", "requisito_version_id"}),
        @UniqueConstraint(name = "uk_legal_pub_req_ordinal", columnNames = {"publicacion_id", "manifest_ordinal"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalPublicacionRequisito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_id", nullable = false, updatable = false)
    private LegalPublicacion publicacion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requisito_version_id", nullable = false, updatable = false)
    private LegalRequisitoVersion requisitoVersion;

    @Column(name = "manifest_ordinal", nullable = false, updatable = false)
    private int manifestOrdinal;

    public LegalPublicacionRequisito(LegalPublicacion publicacion,
                                     LegalRequisitoVersion requisitoVersion,
                                     int manifestOrdinal) {
        this.publicacion = Objects.requireNonNull(publicacion);
        this.requisitoVersion = Objects.requireNonNull(requisitoVersion);
        this.manifestOrdinal = manifestOrdinal;
    }
}
