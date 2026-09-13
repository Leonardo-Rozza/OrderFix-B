package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.PagoException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.SubscriptionPayment;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.SubscriptionProviderLink;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SubscriptionPaymentRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SubscriptionProviderLinkRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoAuthorizedPaymentResponse;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoPreapprovalResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoSubscriptionStateService {

    private static final String PROVIDER = "MERCADO_PAGO";

    private final SubscriptionProviderLinkRepository linkRepository;
    private final SubscriptionPaymentRepository paymentRepository;
    private final SuscripcionRepository suscripcionRepository;
    private final MercadoPagoResponseValidator responseValidator;
    private final Clock clock;

    @Transactional
    public void applyPreapproval(String dataId, MercadoPagoPreapprovalResponse response) {
        SubscriptionProviderLink link = linkRepository.findMercadoPagoByExternalId(dataId)
                .or(() -> response == null || response.externalReference() == null
                        ? java.util.Optional.empty()
                        : linkRepository.findMercadoPagoByExternalReference(response.externalReference()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existe un vínculo local para la suscripción informada por Mercado Pago."));

        responseValidator.validatePreapproval(response, dataId, link.getExternalReference());

        if (link.getExternalSubscriptionId() != null
                && !link.getExternalSubscriptionId().equals(dataId)) {
            throw new PagoException("La referencia externa ya está asociada a otra suscripción.");
        }

        String status = normalize(response.status());
        Instant now = clock.instant();
        link.setExternalSubscriptionId(dataId);
        link.setStatus(status);
        link.setUpdatedAt(now);
        linkRepository.save(link);

        if (!link.isCurrent()) {
            log.info("Estado MP histórico persistido sin modificar la suscripción vigente.");
            return;
        }

        Suscripcion suscripcion = link.getSuscripcion();
        suscripcion.setMpPreapprovalId(dataId);
        suscripcion.setMpExternalReference(link.getExternalReference());
        suscripcion.setMpStatus(status);
        if (response.payerId() != null && !response.payerId().isBlank()) {
            suscripcion.setMpPayerId(response.payerId());
        }
        if (response.nextPaymentDate() != null) {
            Instant nextPayment = response.nextPaymentDate().toInstant();
            suscripcion.setMpNextPaymentAt(nextPayment);
            suscripcion.setProximoCobro(nextPayment.atZone(ZoneOffset.UTC).toLocalDate());
        }

        // Provider observations remain durable; closure is never an entitlement activation.
        if (!isOperational(suscripcion)) {
            suscripcionRepository.save(suscripcion);
            return;
        }

        switch (status) {
            case "authorized" -> applyAuthorizedPreapproval(suscripcion);
            case "paused" -> {
                suscripcion.setPlan(PlanType.PRO);
                suscripcion.setEstado(EstadoSuscripcion.VENCIDA);
            }
            case "canceled", "cancelled" -> downgradeToFree(suscripcion);
            case "pending" -> closePreviouslyGrantedPro(suscripcion);
            default -> {
                closePreviouslyGrantedPro(suscripcion);
                log.warn("Estado de preapproval MP no reconocido; no se concede acceso (status={}).", status);
            }
        }

        suscripcionRepository.save(suscripcion);
        log.info("Estado de suscripción MP aplicado al taller {}: {}.",
                suscripcion.getTaller().getId(), status);
    }

    @Transactional
    public void applyAuthorizedPayment(
            String dataId,
            MercadoPagoAuthorizedPaymentResponse response) {
        if (response == null || response.preapprovalId() == null) {
            throw new PagoException("Respuesta inválida de Mercado Pago para la factura recurrente.");
        }

        SubscriptionProviderLink link = linkRepository
                .findMercadoPagoByExternalId(response.preapprovalId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existe una suscripción local para la factura informada por Mercado Pago."));

        responseValidator.validateAuthorizedPayment(
                response, dataId, link.getExternalSubscriptionId(), link.getExternalReference());

        Instant now = clock.instant();
        Instant incomingStateUpdatedAt = paymentStateUpdatedAt(response, now);
        SubscriptionPayment existing = paymentRepository
                .findByProviderAndExternalAuthorizedPaymentIdForUpdate(PROVIDER, dataId)
                .orElse(null);
        MercadoPagoAuthorizedPaymentResponse.Payment remotePayment = response.payment();
        if (existing != null && existing.getExternalPaymentId() != null
                && !existing.getExternalPaymentId().equals(remotePayment.id())) {
            throw new PagoException("La factura recurrente cambió de payment asociado.");
        }
        if (existing != null && !shouldReplacePaymentSnapshot(
                existing, remotePayment.status(), remotePayment.statusDetail(), incomingStateUpdatedAt)) {
            log.info("Actualización MP antigua de la factura {} ignorada.", dataId);
            return;
        }

        SubscriptionPayment payment = existing == null
                ? SubscriptionPayment.builder()
                        .suscripcion(link.getSuscripcion())
                        .provider(PROVIDER)
                        .externalAuthorizedPaymentId(dataId)
                        .createdAt(now)
                        .build()
                : existing;

        payment.setExternalPaymentId(remotePayment.id());
        payment.setAmount(response.transactionAmount());
        payment.setCurrency(response.currencyId().toUpperCase(Locale.ROOT));
        payment.setInvoiceStatus(normalize(response.status()));
        payment.setPaymentStatus(normalize(remotePayment.status()));
        payment.setStatusDetail(normalize(remotePayment.statusDetail()));
        payment.setSummarized(normalize(response.summarized()));
        payment.setRetryAttempt(response.retryAttempt());
        payment.setDebitAt(toInstant(response.debitDate()));
        payment.setProviderCreatedAt(toInstant(response.dateCreated()));
        payment.setProviderModifiedAt(incomingStateUpdatedAt);
        payment.setUpdatedAt(now);
        paymentRepository.save(payment);

        if (!link.isCurrent()) {
            log.info("Factura MP de un vínculo histórico persistida sin modificar el entitlement.");
            return;
        }

        Suscripcion suscripcion = link.getSuscripcion();
        String paymentStatus = payment.getPaymentStatus();
        String statusDetail = payment.getStatusDetail();
        if (isEntitlementPaymentStatus(paymentStatus, statusDetail)) {
            Instant billingAt = paymentBillingAt(response, now);
            if (isCurrentOrNewerPayment(suscripcion, dataId, billingAt)) {
                suscripcion.setMpLastPaymentAt(billingAt);
                suscripcion.setMpLastAuthorizedPaymentId(dataId);
                if (isOperational(suscripcion)) {
                    applyPaymentEntitlement(
                            suscripcion, normalize(link.getStatus()), paymentStatus, statusDetail);
                }
            } else {
                log.info("Factura MP histórica {} persistida sin revertir el estado vigente.", dataId);
            }
        }
        suscripcionRepository.save(suscripcion);
    }

    @Transactional(readOnly = true)
    public List<String> reconciliationCandidates(int batchSize) {
        return linkRepository.findMercadoPagoReconciliationCandidates(
                PageRequest.of(0, Math.max(1, batchSize)));
    }

    @Transactional
    public void markReconciliationStarted(String externalSubscriptionId) {
        SubscriptionProviderLink link = requiredLink(externalSubscriptionId);
        link.setLastReconciledAt(clock.instant());
        link.setLastReconciliationStatus("PROCESSING");
        link.setLastReconciliationError(null);
        linkRepository.save(link);
    }

    @Transactional
    public void markReconciliationSucceeded(String externalSubscriptionId) {
        SubscriptionProviderLink link = requiredLink(externalSubscriptionId);
        link.setLastReconciledAt(clock.instant());
        link.setLastReconciliationStatus("SUCCEEDED");
        link.setLastReconciliationError(null);
        linkRepository.save(link);
    }

    @Transactional
    public void markReconciliationFailed(String externalSubscriptionId, Throwable failure) {
        SubscriptionProviderLink link = requiredLink(externalSubscriptionId);
        link.setLastReconciledAt(clock.instant());
        link.setLastReconciliationStatus("FAILED");
        link.setLastReconciliationError(sanitize(failure == null ? null : failure.getMessage()));
        linkRepository.save(link);
    }

    @Transactional(readOnly = true)
    public CancellationState cancellationState(Long tallerId) {
        Suscripcion suscripcion = suscripcionRepository.findByTallerId(tallerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El taller no tiene una suscripción asociada."));
        return new CancellationState(
                suscripcion.getMpPreapprovalId(),
                isCanceledStatus(normalize(suscripcion.getMpStatus())));
    }

    @Transactional
    public void applyLocalCancellation(Long tallerId) {
        Suscripcion suscripcion = suscripcionRepository.findByTallerIdForUpdate(tallerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El taller no tiene una suscripción asociada."));
        downgradeToFree(suscripcion);
        suscripcion.setMpStatus("canceled");
        suscripcionRepository.save(suscripcion);

        linkRepository.findFirstBySuscripcionIdAndCurrentTrueOrderByIdDesc(suscripcion.getId())
                .ifPresent(link -> {
                    link.setStatus("canceled");
                    link.setUpdatedAt(clock.instant());
                    linkRepository.save(link);
                });
    }

    private boolean isOperational(Suscripcion suscripcion) {
        var workshop = suscripcion.getTaller();
        return workshop != null && Boolean.TRUE.equals(workshop.getActivo())
                && "ABIERTO".equals(workshop.getCierreEstado());
    }

    private void activatePro(Suscripcion suscripcion) {
        suscripcion.setPlan(PlanType.PRO);
        suscripcion.setEstado(EstadoSuscripcion.ACTIVA);
        if (suscripcion.getFechaInicio() == null) {
            suscripcion.setFechaInicio(clock.instant().atZone(ZoneOffset.UTC).toLocalDate());
        }
    }

    /**
     * Un preapproval puede seguir autorizado mientras la última factura está en mora.
     * En ese caso el estado contractual se conserva, pero no se reabre el entitlement.
     */
    private void applyAuthorizedPreapproval(Suscripcion suscripcion) {
        String latestPaymentId = suscripcion.getMpLastAuthorizedPaymentId();
        SubscriptionPayment latestPayment = latestPaymentId == null
                ? null
                : paymentRepository
                    .findByProviderAndExternalAuthorizedPaymentId(PROVIDER, latestPaymentId)
                    .orElse(null);
        if (latestPayment != null && !isSuccessfulPaymentStatus(
                latestPayment.getPaymentStatus(), latestPayment.getStatusDetail())) {
            suscripcion.setPlan(PlanType.PRO);
            suscripcion.setEstado(EstadoSuscripcion.VENCIDA);
            return;
        }
        activatePro(suscripcion);
    }

    private void applyPaymentEntitlement(
            Suscripcion suscripcion,
            String preapprovalStatus,
            String paymentStatus,
            String statusDetail) {
        switch (preapprovalStatus == null ? "unknown" : preapprovalStatus) {
            case "authorized" -> {
                if (isSuccessfulPaymentStatus(paymentStatus, statusDetail)) {
                    activatePro(suscripcion);
                } else {
                    suscripcion.setPlan(PlanType.PRO);
                    suscripcion.setEstado(EstadoSuscripcion.VENCIDA);
                }
            }
            case "paused" -> {
                suscripcion.setPlan(PlanType.PRO);
                suscripcion.setEstado(EstadoSuscripcion.VENCIDA);
            }
            case "canceled", "cancelled" -> downgradeToFree(suscripcion);
            default -> {
                // Pending/creating/estado desconocido: se audita el pago, sin conceder acceso.
            }
        }
    }

    private void downgradeToFree(Suscripcion suscripcion) {
        suscripcion.setPlan(PlanType.FREE);
        suscripcion.setEstado(EstadoSuscripcion.ACTIVA);
        suscripcion.setProximoCobro(null);
        suscripcion.setMpNextPaymentAt(null);
    }

    private void closePreviouslyGrantedPro(Suscripcion suscripcion) {
        if (suscripcion.getPlan() == PlanType.PRO) {
            suscripcion.setEstado(EstadoSuscripcion.VENCIDA);
        }
    }

    private Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private boolean isEntitlementPaymentStatus(String status, String statusDetail) {
        return isSuccessfulPaymentStatus(status, statusDetail)
                || isBlockingPaymentStatus(status, statusDetail);
    }

    private boolean isSuccessfulPaymentStatus(String status, String statusDetail) {
        return "approved".equals(status)
                || ("charged_back".equals(status) && "reimbursed".equals(statusDetail));
    }

    private boolean isBlockingPaymentStatus(String status, String statusDetail) {
        return status != null && switch (status) {
            case "rejected", "cancelled", "canceled", "refunded", "partially_refunded" -> true;
            case "charged_back" -> !"reimbursed".equals(statusDetail);
            default -> false;
        };
    }

    private boolean isCanceledStatus(String status) {
        return "canceled".equals(status) || "cancelled".equals(status);
    }

    private Instant paymentBillingAt(
            MercadoPagoAuthorizedPaymentResponse response,
            Instant fallback) {
        if (response.debitDate() != null) {
            return response.debitDate().toInstant();
        }
        if (response.dateCreated() != null) {
            return response.dateCreated().toInstant();
        }
        return fallback;
    }

    private Instant paymentStateUpdatedAt(
            MercadoPagoAuthorizedPaymentResponse response,
            Instant fallback) {
        if (response.lastModified() != null) {
            return response.lastModified().toInstant();
        }
        if (response.dateCreated() != null) {
            return response.dateCreated().toInstant();
        }
        if (response.debitDate() != null) {
            return response.debitDate().toInstant();
        }
        return fallback;
    }

    private boolean shouldReplacePaymentSnapshot(
            SubscriptionPayment existing,
            String incomingStatus,
            String incomingStatusDetail,
            Instant incomingStateUpdatedAt) {
        Instant persistedStateUpdatedAt = existing.getProviderModifiedAt();
        if (persistedStateUpdatedAt == null) {
            return true;
        }
        int chronology = incomingStateUpdatedAt.compareTo(persistedStateUpdatedAt);
        if (chronology != 0) {
            return chronology > 0;
        }
        return paymentRiskRank(normalize(incomingStatus), normalize(incomingStatusDetail))
                >= paymentRiskRank(existing.getPaymentStatus(), existing.getStatusDetail());
    }

    private int paymentRiskRank(String status, String statusDetail) {
        if (isBlockingPaymentStatus(status, statusDetail)) {
            return 2;
        }
        return isSuccessfulPaymentStatus(status, statusDetail) ? 0 : 1;
    }

    private boolean isCurrentOrNewerPayment(
            Suscripcion suscripcion,
            String paymentId,
            Instant effectiveAt) {
        Instant last = suscripcion.getMpLastPaymentAt();
        return last == null
                || effectiveAt.isAfter(last)
                || (effectiveAt.equals(last)
                    && paymentId.equals(suscripcion.getMpLastAuthorizedPaymentId()));
    }

    private SubscriptionProviderLink requiredLink(String externalSubscriptionId) {
        return linkRepository.findMercadoPagoByExternalId(externalSubscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existe un vínculo local para reconciliar la suscripción."));
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public record CancellationState(String preapprovalId, boolean alreadyCanceled) {
    }
}
