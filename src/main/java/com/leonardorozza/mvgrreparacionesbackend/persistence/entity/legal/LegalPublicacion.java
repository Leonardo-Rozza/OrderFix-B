package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.converter.LocaleLegalConverter;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoConstruccionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_publicaciones")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalPublicacion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "publication_external_id", nullable = false, unique = true, updatable = false, length = 120)
    private String publicationExternalId;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Convert(converter = LocaleLegalConverter.class)
    @Column(name = "locale", nullable = false, updatable = false, length = 5)
    private LocaleLegal locale;

    @Column(name = "manifest_sha256", nullable = false, updatable = false, length = 64)
    private String manifestSha256;

    @Column(name = "manifest_canonico", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String manifestCanonico;

    @Column(name = "razon_social", nullable = false, updatable = false, length = 200)
    private String razonSocial;

    @Column(name = "cuit", nullable = false, updatable = false, length = 11)
    private String cuit;

    @Column(name = "domicilio_legal", nullable = false, updatable = false, length = 500)
    private String domicilioLegal;

    @Column(name = "jurisdiccion", nullable = false, updatable = false, length = 200)
    private String jurisdiccion;

    @Column(name = "horario_atencion", nullable = false, updatable = false, length = 300)
    private String horarioAtencion;

    @Column(name = "email_legal", nullable = false, updatable = false, length = 320)
    private String emailLegal;

    @Column(name = "email_privacidad", nullable = false, updatable = false, length = 320)
    private String emailPrivacidad;

    @Column(name = "email_soporte", nullable = false, updatable = false, length = 320)
    private String emailSoporte;

    @Enumerated(EnumType.STRING)
    @Column(name = "revision_legal_estado", nullable = false, updatable = false, length = 20)
    private EstadoRevisionLegal revisionLegalEstado;

    @Column(name = "revision_legal_referencia", updatable = false, length = 500)
    private String revisionLegalReferencia;

    @Column(name = "revision_legal_en", updatable = false)
    private Instant revisionLegalEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "revision_contable_estado", nullable = false, updatable = false, length = 20)
    private EstadoRevisionLegal revisionContableEstado;

    @Column(name = "revision_contable_referencia", updatable = false, length = 500)
    private String revisionContableReferencia;

    @Column(name = "revision_contable_en", updatable = false)
    private Instant revisionContableEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_construccion", nullable = false, length = 10)
    private EstadoConstruccionLegal estadoConstruccion;

    @Column(name = "importado_en", nullable = false, updatable = false)
    private Instant importadoEn;

    @Column(name = "sellado_en")
    private Instant selladoEn;

    public LegalPublicacion(String publicationExternalId, int schemaVersion, LocaleLegal locale,
                            String manifestSha256, String manifestCanonico, String razonSocial,
                            String cuit, String domicilioLegal, String jurisdiccion,
                            String horarioAtencion, String emailLegal, String emailPrivacidad,
                            String emailSoporte, EstadoRevisionLegal revisionLegalEstado,
                            String revisionLegalReferencia, Instant revisionLegalEn,
                            EstadoRevisionLegal revisionContableEstado,
                            String revisionContableReferencia, Instant revisionContableEn,
                            Instant importadoEn) {
        this.publicationExternalId = Objects.requireNonNull(publicationExternalId);
        this.schemaVersion = schemaVersion;
        this.locale = Objects.requireNonNull(locale);
        this.manifestSha256 = Objects.requireNonNull(manifestSha256);
        this.manifestCanonico = Objects.requireNonNull(manifestCanonico);
        this.razonSocial = Objects.requireNonNull(razonSocial);
        this.cuit = Objects.requireNonNull(cuit);
        this.domicilioLegal = Objects.requireNonNull(domicilioLegal);
        this.jurisdiccion = Objects.requireNonNull(jurisdiccion);
        this.horarioAtencion = Objects.requireNonNull(horarioAtencion);
        this.emailLegal = Objects.requireNonNull(emailLegal);
        this.emailPrivacidad = Objects.requireNonNull(emailPrivacidad);
        this.emailSoporte = Objects.requireNonNull(emailSoporte);
        this.revisionLegalEstado = Objects.requireNonNull(revisionLegalEstado);
        this.revisionLegalReferencia = revisionLegalReferencia;
        this.revisionLegalEn = revisionLegalEn;
        this.revisionContableEstado = Objects.requireNonNull(revisionContableEstado);
        this.revisionContableReferencia = revisionContableReferencia;
        this.revisionContableEn = revisionContableEn;
        this.estadoConstruccion = EstadoConstruccionLegal.ABIERTO;
        this.importadoEn = Objects.requireNonNull(importadoEn);
    }

    public void sellar(Instant instante) {
        if (estadoConstruccion != EstadoConstruccionLegal.ABIERTO) {
            throw new IllegalStateException("La publicación ya está sellada");
        }
        this.estadoConstruccion = EstadoConstruccionLegal.SELLADO;
        this.selladoEn = Objects.requireNonNull(instante);
    }
}
