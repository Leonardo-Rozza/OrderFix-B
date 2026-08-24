package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoOperacionIdempotenteLegal;
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
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_idempotencia_resultados", uniqueConstraints =
        @UniqueConstraint(name = "uk_legal_idempotencia",
                columnNames = {"operacion", "route_template", "scope_hmac", "idempotency_key_hmac"}))
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalIdempotenciaResultado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "operacion", nullable = false, updatable = false, length = 30)
    private TipoOperacionIdempotenteLegal operacion;

    @Column(name = "route_template", nullable = false, updatable = false, length = 200)
    private String routeTemplate;

    @Column(name = "scope_hmac", nullable = false, updatable = false, length = 64)
    private String scopeHmac;

    @Column(name = "idempotency_key_hmac", nullable = false, updatable = false, length = 64)
    private String idempotencyKeyHmac;

    @Column(name = "fingerprint_hmac", nullable = false, updatable = false, length = 64)
    private String fingerprintHmac;

    @Column(name = "hmac_key_version", nullable = false, updatable = false)
    private int hmacKeyVersion;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "taller_id", nullable = false, updatable = false)
    private Long tallerId;

    @Column(name = "lote_id", nullable = false, updatable = false)
    private UUID loteId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, insertable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "taller_id", nullable = false, insertable = false, updatable = false)
    private Taller taller;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lote_id", nullable = false, insertable = false, updatable = false)
    private LegalAceptacionLote lote;

    @Column(name = "completed_at", nullable = false, updatable = false)
    @Generated(event = EventType.INSERT, writable = true)
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    public LegalIdempotenciaResultado(TipoOperacionIdempotenteLegal operacion,
                                      String routeTemplate, String scopeHmac,
                                      String idempotencyKeyHmac, String fingerprintHmac,
                                      int hmacKeyVersion, Long userId, Long tallerId,
                                      UUID loteId, Instant completedAt, Instant expiresAt) {
        this.operacion = Objects.requireNonNull(operacion);
        this.routeTemplate = Objects.requireNonNull(routeTemplate);
        this.scopeHmac = Objects.requireNonNull(scopeHmac);
        this.idempotencyKeyHmac = Objects.requireNonNull(idempotencyKeyHmac);
        this.fingerprintHmac = Objects.requireNonNull(fingerprintHmac);
        this.hmacKeyVersion = hmacKeyVersion;
        this.userId = Objects.requireNonNull(userId);
        this.tallerId = Objects.requireNonNull(tallerId);
        this.loteId = Objects.requireNonNull(loteId);
        this.completedAt = Objects.requireNonNull(completedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }
}
