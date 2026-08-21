package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.PaymentEvent;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PaymentEventStatus;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.PaymentEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentEventInboxService {

    private static final String PROVIDER = "MERCADO_PAGO";

    private final PaymentEventRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventClaim register(
            String eventType,
            String dataId,
            String providerEventId,
            String requestId,
            int maxAttempts) {
        String key = eventKey(eventType, dataId, providerEventId, requestId);
        PaymentEvent existing = repository.findByProviderAndProviderEventKey(PROVIDER, key).orElse(null);
        if (existing != null) {
            if (existing.getStatus() != PaymentEventStatus.FAILED
                    || existing.getAttempts() >= maxAttempts) {
                return EventClaim.skip(existing.getId());
            }
            existing.setStatus(PaymentEventStatus.PROCESSING);
            existing.setAttempts(existing.getAttempts() + 1);
            existing.setLastError(null);
            existing.setLastAttemptAt(clock.instant());
            repository.save(existing);
            return EventClaim.process(existing.getId(), existing.getEventType(), existing.getDataId());
        }

        PaymentEvent event = PaymentEvent.builder()
                .provider(PROVIDER)
                .providerEventKey(key)
                .requestId(trimToNull(requestId))
                .eventType(eventType)
                .dataId(dataId)
                .status(PaymentEventStatus.PROCESSING)
                .attempts(1)
                .receivedAt(clock.instant())
                .lastAttemptAt(clock.instant())
                .build();
        repository.saveAndFlush(event);
        return EventClaim.process(event.getId(), eventType, dataId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventClaim claimRetry(Long eventId, int maxAttempts) {
        PaymentEvent event = repository.findByIdForUpdate(eventId).orElse(null);
        if (event == null || event.getStatus() != PaymentEventStatus.FAILED
                || event.getAttempts() >= maxAttempts) {
            return EventClaim.skip(eventId);
        }
        event.setStatus(PaymentEventStatus.PROCESSING);
        event.setAttempts(event.getAttempts() + 1);
        event.setLastError(null);
        event.setLastAttemptAt(clock.instant());
        repository.save(event);
        return EventClaim.process(event.getId(), event.getEventType(), event.getDataId());
    }

    @Transactional(readOnly = true)
    public List<Long> failedEventIds(int maxAttempts) {
        return repository.findTop50ByStatusAndAttemptsLessThanOrderByReceivedAtAsc(
                        PaymentEventStatus.FAILED, maxAttempts)
                .stream()
                .map(PaymentEvent::getId)
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStaleProcessing(Duration processingTimeout) {
        Instant cutoff = clock.instant().minus(processingTimeout);
        List<PaymentEvent> stale = repository
                .findTop50ByStatusAndLastAttemptAtBeforeOrderByLastAttemptAtAsc(
                        PaymentEventStatus.PROCESSING, cutoff);
        stale.forEach(event -> {
            event.setStatus(PaymentEventStatus.FAILED);
            event.setLastError("Procesamiento interrumpido antes de completar");
        });
        repository.saveAll(stale);
        return stale.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(Long eventId) {
        updateFinalState(eventId, PaymentEventStatus.PROCESSED, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIgnored(Long eventId, String reason) {
        updateFinalState(eventId, PaymentEventStatus.IGNORED, sanitize(reason));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long eventId, Throwable failure) {
        PaymentEvent event = repository.findByIdForUpdate(eventId).orElse(null);
        if (event == null) {
            return;
        }
        event.setStatus(PaymentEventStatus.FAILED);
        event.setLastError(sanitize(failure == null ? null : failure.getMessage()));
        repository.save(event);
    }

    private void updateFinalState(Long eventId, PaymentEventStatus status, String detail) {
        PaymentEvent event = repository.findByIdForUpdate(eventId).orElse(null);
        if (event == null) {
            return;
        }
        event.setStatus(status);
        event.setProcessedAt(Instant.now(clock));
        event.setLastError(detail);
        repository.save(event);
    }

    private String eventKey(String eventType, String dataId, String providerEventId, String requestId) {
        if (providerEventId != null && !providerEventId.isBlank()) {
            return "event:" + providerEventId.trim() + ':' + eventType + ':' + dataId;
        }
        if (requestId != null && !requestId.isBlank()) {
            return "request:" + requestId.trim();
        }
        // Webhooks oficiales incluyen al menos uno de los identificadores anteriores.
        // El UUID evita deduplicar erróneamente dos actualizaciones distintas del mismo recurso.
        return "fallback:" + eventType + ':' + dataId + ':' + UUID.randomUUID();
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record EventClaim(boolean shouldProcess, Long eventId, String eventType, String dataId) {
        static EventClaim process(Long id, String type, String dataId) {
            return new EventClaim(true, id, type, dataId);
        }

        static EventClaim skip(Long id) {
            return new EventClaim(false, id, null, null);
        }
    }
}
