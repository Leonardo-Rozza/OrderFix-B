package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PaymentEventStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "payment_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_event_provider_key",
                columnNames = {"provider", "provider_event_key"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "provider_event_key", nullable = false, length = 255)
    private String providerEventKey;

    @Column(name = "request_id", length = 255)
    private String requestId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "data_id", nullable = false, length = 255)
    private String dataId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentEventStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "last_attempt_at", nullable = false)
    private Instant lastAttemptAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;
}
