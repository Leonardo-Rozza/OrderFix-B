package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "subscription_payments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_subscription_payment_provider_id",
                columnNames = {"provider", "external_authorized_payment_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "suscripcion_id", nullable = false)
    private Suscripcion suscripcion;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "external_authorized_payment_id", nullable = false, length = 255)
    private String externalAuthorizedPaymentId;

    @Column(name = "external_payment_id", length = 255)
    private String externalPaymentId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "invoice_status", length = 50)
    private String invoiceStatus;

    @Column(name = "payment_status", length = 50)
    private String paymentStatus;

    @Column(name = "status_detail", length = 100)
    private String statusDetail;

    @Column(length = 50)
    private String summarized;

    @Column(name = "retry_attempt")
    private Integer retryAttempt;

    @Column(name = "debit_at")
    private Instant debitAt;

    @Column(name = "provider_created_at")
    private Instant providerCreatedAt;

    @Column(name = "provider_modified_at")
    private Instant providerModifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
