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
@Table(name = "legal_requisito_versiones", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_req_version", columnNames = {"requisito_linea_id", "version"}),
        @UniqueConstraint(name = "uk_legal_req_lineage", columnNames = {"requisito_linea_id", "lineage_ordinal"}),
        @UniqueConstraint(name = "uk_legal_req_identidad", columnNames = {"id", "requisito_linea_id"}),
        @UniqueConstraint(name = "uk_legal_req_intro", columnNames = {"id", "publicacion_intro_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalRequisitoVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requisito_linea_id", nullable = false, updatable = false)
    private LegalRequisitoLinea linea;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_intro_id", nullable = false, updatable = false)
    private LegalPublicacion publicacionIntro;

    @Column(name = "version", nullable = false, updatable = false, length = 64)
    private String version;

    @Column(name = "lineage_ordinal", nullable = false, updatable = false)
    private int lineageOrdinal;

    @Column(name = "afirmacion", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String afirmacion;

    @Column(name = "afirmacion_sha256", nullable = false, updatable = false, length = 64)
    private String afirmacionSha256;

    @Column(name = "requerido", nullable = false, updatable = false)
    private boolean requerido;

    @Column(name = "requires_reacceptance", nullable = false, updatable = false)
    private boolean requiresReacceptance;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private EstadoVersionLegal estado;

    @Column(name = "estado_cambiado_en")
    private Instant estadoCambiadoEn;

    @Column(name = "ultimo_motivo", length = 1000)
    private String ultimoMotivo;

    public LegalRequisitoVersion(LegalRequisitoLinea linea, LegalPublicacion publicacionIntro,
                                 String version, int lineageOrdinal, String afirmacion,
                                 String afirmacionSha256, boolean requerido,
                                 boolean requiresReacceptance) {
        this.linea = Objects.requireNonNull(linea);
        this.publicacionIntro = Objects.requireNonNull(publicacionIntro);
        this.version = Objects.requireNonNull(version);
        this.lineageOrdinal = lineageOrdinal;
        this.afirmacion = Objects.requireNonNull(afirmacion);
        this.afirmacionSha256 = Objects.requireNonNull(afirmacionSha256);
        this.requerido = requerido;
        this.requiresReacceptance = requiresReacceptance;
        this.estado = EstadoVersionLegal.BORRADOR;
    }
}
