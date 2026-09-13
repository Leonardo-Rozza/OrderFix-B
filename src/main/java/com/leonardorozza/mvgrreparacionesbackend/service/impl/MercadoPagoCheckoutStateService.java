package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureGate;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ConflictException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.PagoException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.SubscriptionProviderLink;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SubscriptionProviderLinkRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.CheckoutResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoPreapprovalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MercadoPagoCheckoutStateService {

    private static final String PROVIDER = "MERCADO_PAGO";

    private final SuscripcionRepository suscripcionRepository;
    private final SubscriptionProviderLinkRepository linkRepository;
    private final MercadoPagoProperties properties;
    private final Clock clock;
    private final WorkshopClosureGate closureGate;

    @Transactional
    public CheckoutPreparation prepare(Long tallerId) {
        closureGate.requireOperational(tallerId);
        Suscripcion suscripcion = suscripcionRepository.findByTallerIdForUpdate(tallerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El taller no tiene una suscripción asociada."));

        SubscriptionProviderLink current = linkRepository
                .findFirstBySuscripcionIdAndCurrentTrueOrderByIdDesc(suscripcion.getId())
                .orElse(null);

        String currentStatus = current == null ? null : normalize(current.getStatus());
        if (current != null && "pending".equals(currentStatus)
                && hasText(current.getExternalSubscriptionId()) && hasText(current.getCheckoutUrl())) {
            return CheckoutPreparation.existing(new CheckoutResponseDto(
                    current.getExternalSubscriptionId(), current.getCheckoutUrl()));
        }
        if (current != null && isCheckoutInProgressStatus(currentStatus)) {
            if ("creating".equals(currentStatus) && leaseIsActive(current)) {
                throw new ConflictException(
                        "El checkout de Mercado Pago ya se está creando. Esperá unos segundos y reintentá.");
            }
            current.setStatus("creating");
            current.setUpdatedAt(clock.instant());
            linkRepository.save(current);
            suscripcion.setMpStatus("creating");
            suscripcionRepository.save(suscripcion);
            return CheckoutPreparation.pending(
                    current.getId(), suscripcion.getId(), payerEmail(suscripcion),
                    current.getExternalReference(), current.getIdempotencyKey());
        }

        if (current != null && !"canceled".equals(normalize(current.getStatus()))) {
            throw new ConflictException(
                    "Ya existe una suscripción de Mercado Pago vigente o pausada. Cancelala o reactivala antes de crear otra.");
        }
        if (suscripcion.getPlan() == PlanType.PRO
                && suscripcion.getEstado() == EstadoSuscripcion.ACTIVA) {
            throw new ConflictException("El taller ya tiene una suscripción PRO activa.");
        }
        if (current != null) {
            current.setCurrent(false);
            current.setUpdatedAt(clock.instant());
            linkRepository.save(current);
        }

        Instant now = clock.instant();
        SubscriptionProviderLink link = SubscriptionProviderLink.builder()
                .suscripcion(suscripcion)
                .provider(PROVIDER)
                .externalReference("ofx_" + UUID.randomUUID().toString().replace("-", ""))
                .idempotencyKey(UUID.randomUUID().toString())
                .status("creating")
                .current(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
        linkRepository.saveAndFlush(link);

        suscripcion.setMpExternalReference(link.getExternalReference());
        suscripcion.setMpStatus("creating");
        // El vínculo anterior queda en el historial; el estado actual empieza limpio.
        suscripcion.setMpPreapprovalId(null);
        suscripcion.setMpPayerId(null);
        suscripcion.setMpCheckoutInitPoint(null);
        suscripcion.setMpNextPaymentAt(null);
        suscripcion.setMpLastPaymentAt(null);
        suscripcion.setMpLastAuthorizedPaymentId(null);
        suscripcion.setProximoCobro(null);
        suscripcionRepository.save(suscripcion);

        return CheckoutPreparation.pending(
                link.getId(), suscripcion.getId(), payerEmail(suscripcion),
                link.getExternalReference(), link.getIdempotencyKey());
    }

    @Transactional
    public CheckoutResponseDto complete(Long linkId, MercadoPagoPreapprovalResponse response) {
        SubscriptionProviderLink link = linkRepository.findByIdForUpdate(linkId)
                .orElseThrow(() -> new PagoException("No se encontró el checkout local preparado."));

        if (hasText(link.getExternalSubscriptionId())
                && !link.getExternalSubscriptionId().equals(response.id())) {
            throw new ConflictException("El checkout ya fue asociado a otra suscripción externa.");
        }

        link.setExternalSubscriptionId(response.id());
        link.setCheckoutUrl(response.initPoint());
        link.setStatus(normalize(response.status()));
        link.setUpdatedAt(clock.instant());
        linkRepository.save(link);

        // A late acknowledgement remains durable even after restriction or replacement.
        if (!link.isCurrent()) return new CheckoutResponseDto(response.id(), response.initPoint());
        Suscripcion suscripcion = link.getSuscripcion();
        suscripcion.setMpPreapprovalId(response.id());
        suscripcion.setMpExternalReference(link.getExternalReference());
        suscripcion.setMpCheckoutInitPoint(response.initPoint());
        suscripcion.setMpStatus(normalize(response.status()));
        suscripcionRepository.save(suscripcion);

        return new CheckoutResponseDto(response.id(), response.initPoint());
    }

    @Transactional
    public void markAttemptFailed(Long linkId) {
        linkRepository.findByIdForUpdate(linkId).ifPresent(link -> {
            if ("creating".equals(normalize(link.getStatus()))) {
                link.setStatus("retryable");
                link.setUpdatedAt(clock.instant());
                linkRepository.save(link);
                if (!link.isCurrent()) return;
                Suscripcion suscripcion = link.getSuscripcion();
                suscripcion.setMpStatus("retryable");
                suscripcionRepository.save(suscripcion);
            }
        });
    }

    /** Called after complete commits: denying delivery must never roll back the provider identifier. */
    @Transactional(readOnly = true)
    public void requireCheckoutDelivery(Long tallerId) {
        closureGate.requireOperational(tallerId);
    }

    private String payerEmail(Suscripcion suscripcion) {
        String email = suscripcion.getTaller().getEmailContacto();
        if (!hasText(email)) {
            throw new BadRequestException(
                    "El taller necesita un email de contacto antes de iniciar la suscripción.");
        }
        return email.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean leaseIsActive(SubscriptionProviderLink link) {
        if (link.getUpdatedAt() == null) {
            return false;
        }
        Duration lease = properties.getConnectTimeout()
                .plus(properties.getReadTimeout())
                .plusSeconds(5);
        return Duration.between(link.getUpdatedAt(), clock.instant()).compareTo(lease) < 0;
    }

    private boolean isCheckoutInProgressStatus(String status) {
        return "creating".equals(status) || "retryable".equals(status) || "pending".equals(status);
    }

    public record CheckoutPreparation(
            boolean existing,
            CheckoutResponseDto existingResponse,
            Long linkId,
            Long suscripcionId,
            String payerEmail,
            String externalReference,
            String idempotencyKey
    ) {
        static CheckoutPreparation existing(CheckoutResponseDto response) {
            return new CheckoutPreparation(true, response, null, null, null, null, null);
        }

        static CheckoutPreparation pending(
                Long linkId,
                Long suscripcionId,
                String payerEmail,
                String externalReference,
                String idempotencyKey) {
            return new CheckoutPreparation(false, null, linkId, suscripcionId, payerEmail,
                    externalReference, idempotencyKey);
        }
    }
}
