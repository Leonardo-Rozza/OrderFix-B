package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoCampoMetadataLegal;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "legal_aceptacion_metadatos_cifrados", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_metadata_tipo", columnNames = {"lote_id", "tipo"}),
        @UniqueConstraint(name = "uk_legal_metadata_nonce", columnNames = {"key_version", "nonce"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalAceptacionMetadataCifrada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lote_id", nullable = false, updatable = false)
    private LegalAceptacionMetadata metadata;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, updatable = false, length = 20)
    private TipoCampoMetadataLegal tipo;

    @Column(name = "key_version", nullable = false, updatable = false)
    private int keyVersion;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "nonce", nullable = false, updatable = false, length = 12)
    private byte[] nonce;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "ciphertext", length = 4096)
    private byte[] ciphertext;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "tag", length = 16)
    private byte[] tag;

    @Column(name = "longitud_original")
    private Integer longitudOriginal;

    @Column(name = "tombstone_en")
    private Instant tombstoneEn;

    public LegalAceptacionMetadataCifrada(LegalAceptacionMetadata metadata,
                                          TipoCampoMetadataLegal tipo, int keyVersion,
                                          byte[] nonce, byte[] ciphertext, byte[] tag,
                                          int longitudOriginal) {
        this.metadata = Objects.requireNonNull(metadata);
        this.tipo = Objects.requireNonNull(tipo);
        this.keyVersion = keyVersion;
        this.nonce = Objects.requireNonNull(nonce).clone();
        this.ciphertext = Objects.requireNonNull(ciphertext).clone();
        this.tag = Objects.requireNonNull(tag).clone();
        this.longitudOriginal = longitudOriginal;
    }

    public void purgar(Instant instante) {
        if (tombstoneEn != null) {
            throw new IllegalStateException("El campo cifrado ya es un tombstone");
        }
        this.ciphertext = null;
        this.tag = null;
        this.longitudOriginal = null;
        this.tombstoneEn = Objects.requireNonNull(instante);
    }
}
