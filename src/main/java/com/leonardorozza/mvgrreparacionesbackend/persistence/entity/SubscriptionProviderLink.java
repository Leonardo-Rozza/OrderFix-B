package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "subscription_provider_links",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_subscription_provider_external_id",
                        columnNames = {"provider", "external_subscription_id"}),
                @UniqueConstraint(name = "uk_subscription_provider_external_ref",
                        columnNames = {"provider", "external_reference"}),
                @UniqueConstraint(name = "uk_subscription_provider_idempotency",
                        columnNames = {"provider", "idempotency_key"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionProviderLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "suscripcion_id", nullable = false)
    private Suscripcion suscripcion;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "external_subscription_id", length = 255)
    private String externalSubscriptionId;

    @Column(name = "external_reference", nullable = false, length = 255)
    private String externalReference;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "checkout_url", length = 1000)
    private String checkoutUrl;

    @Column(length = 50)
    private String status;

    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private boolean current = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_reconciled_at")
    private Instant lastReconciledAt;

    @Column(name = "last_reconciliation_status", length = 30)
    private String lastReconciliationStatus;

    @Column(name = "last_reconciliation_error", length = 500)
    private String lastReconciliationError;
}
