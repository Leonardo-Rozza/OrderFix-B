package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.PagoException;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.CheckoutResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoAuthorizedPaymentResponse;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoAuthorizedPaymentsSearchResponse;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoPreapprovalRequest;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoPreapprovalResponse;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoPaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * Adaptador de Mercado Pago para suscripciones recurrentes.
 *
 * El checkout se prepara y persiste antes de llamar al proveedor, por lo que un timeout
 * puede reintentarse con la misma clave de idempotencia. Los webhooks se registran en una
 * inbox durable y las mutaciones de estado se aplican en transacciones separadas.
 */
@Service
@Slf4j
public class MercadoPagoService {

    private static final String TOPIC_PREAPPROVAL = "subscription_preapproval";
    private static final String TOPIC_AUTHORIZED_PAYMENT = "subscription_authorized_payment";
    private static final String TOPIC_PAYMENT = "payment";

    private final MercadoPagoProperties props;
    private final RestClient mercadoPagoRestClient;
    private final TenantService tenantService;
    private final MercadoPagoCheckoutStateService checkoutStateService;
    private final MercadoPagoSubscriptionStateService subscriptionStateService;
    private final PaymentEventInboxService eventInboxService;
    private final MercadoPagoResponseValidator responseValidator;
    private final MercadoPagoWebhookSignatureValidator signatureValidator;
    private final TaskExecutor eventTaskExecutor;

    public MercadoPagoService(
            MercadoPagoProperties props,
            RestClient mercadoPagoRestClient,
            TenantService tenantService,
            MercadoPagoCheckoutStateService checkoutStateService,
            MercadoPagoSubscriptionStateService subscriptionStateService,
            PaymentEventInboxService eventInboxService,
            MercadoPagoResponseValidator responseValidator,
            MercadoPagoWebhookSignatureValidator signatureValidator,
            @Qualifier("applicationTaskExecutor") TaskExecutor eventTaskExecutor) {
        this.props = props;
        this.mercadoPagoRestClient = mercadoPagoRestClient;
        this.tenantService = tenantService;
        this.checkoutStateService = checkoutStateService;
        this.subscriptionStateService = subscriptionStateService;
        this.eventInboxService = eventInboxService;
        this.responseValidator = responseValidator;
        this.signatureValidator = signatureValidator;
        this.eventTaskExecutor = eventTaskExecutor;
    }

    public CheckoutResponseDto crearCheckoutSuscripcion() {
        requireCheckoutEnabled();

        Long tallerId = tenantService.currentTallerId();
        MercadoPagoCheckoutStateService.CheckoutPreparation preparation =
                checkoutStateService.prepare(tallerId);
        if (preparation.existing()) {
            checkoutStateService.requireCheckoutDelivery(tallerId);
            return preparation.existingResponse();
        }

        MercadoPagoPreapprovalRequest request = new MercadoPagoPreapprovalRequest(
                props.getReason(),
                new MercadoPagoPreapprovalRequest.AutoRecurring(
                        1, "months", props.getAmount(), props.getCurrency()),
                props.getBackUrl(),
                preparation.payerEmail(),
                "pending",
                preparation.externalReference());

        MercadoPagoPreapprovalResponse response;
        try {
            response = mercadoPagoRestClient.post()
                    .uri("/preapproval")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken())
                    .header("X-Idempotency-Key", preparation.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(MercadoPagoPreapprovalResponse.class);
        } catch (RestClientException ex) {
            checkoutStateService.markAttemptFailed(preparation.linkId());
            logProviderFailure("crear preapproval", null, ex);
            throw new PagoException(
                    "No se pudo iniciar el checkout de Mercado Pago. Podés reintentarlo sin generar un duplicado.", ex);
        }

        try {
            responseValidator.validateCreatedPreapproval(response, preparation.externalReference());
        } catch (PagoException ex) {
            checkoutStateService.markAttemptFailed(preparation.linkId());
            throw ex;
        }
        CheckoutResponseDto result = checkoutStateService.complete(preparation.linkId(), response);
        checkoutStateService.requireCheckoutDelivery(tallerId);
        log.info("Checkout de suscripción creado para taller {}.", tallerId);
        return result;
    }

    public void procesarNotificacion(String type, String dataId) {
        procesarNotificacion(type, dataId, null, null);
    }

    public void procesarNotificacion(
            String type,
            String dataId,
            String providerEventId,
            String requestId) {
        if (!props.isEnabled()) {
            log.debug("Webhook MP ignorado porque la integración está deshabilitada.");
            return;
        }
        String topic = normalizeTopic(type);
        if (dataId == null || dataId.isBlank()) {
            log.warn("Webhook MP firmado sin identificador de recurso; se ignora.");
            return;
        }

        PaymentEventInboxService.EventClaim claim = eventInboxService.register(
                topic, dataId.trim(), providerEventId, requestId, props.getWebhookMaxAttempts());
        if (!claim.shouldProcess()) {
            log.debug("Webhook MP duplicado o ya procesado (eventId={}).", claim.eventId());
            return;
        }
        dispatchClaim(claim);
    }

    public void retryPersistedEvent(Long eventId) {
        PaymentEventInboxService.EventClaim claim = eventInboxService.claimRetry(
                eventId, props.getWebhookMaxAttempts());
        if (claim.shouldProcess()) {
            processClaim(claim);
        }
    }

    /**
     * Corrige eventos perdidos consultando tanto el estado del preapproval como sus facturas.
     * Cada vínculo registra el último resultado para que el lote avance aun ante fallas parciales.
     */
    public void reconcileCurrentSubscriptions() {
        if (!props.isEnabled()) {
            return;
        }
        List<String> subscriptions = subscriptionStateService.reconciliationCandidates(
                props.getReconciliationBatchSize());
        if (!subscriptions.isEmpty()) {
            log.info("Conciliando {} suscripciones de Mercado Pago.", subscriptions.size());
        }
        for (String preapprovalId : subscriptions) {
            try {
                subscriptionStateService.markReconciliationStarted(preapprovalId);
                processPreapproval(preapprovalId);
                processAuthorizedPaymentsForSubscription(preapprovalId);
                subscriptionStateService.markReconciliationSucceeded(preapprovalId);
            } catch (RuntimeException ex) {
                try {
                    subscriptionStateService.markReconciliationFailed(preapprovalId, ex);
                } catch (RuntimeException stateFailure) {
                    log.warn("No se pudo registrar el fallo de conciliación de una suscripción MP.");
                }
                log.warn("Conciliación MP incompleta para una suscripción; se reintentará.");
            }
        }
    }

    public void cancelarSuscripcion() {
        Long tallerId = tenantService.currentTallerId();
        MercadoPagoSubscriptionStateService.CancellationState state =
                subscriptionStateService.cancellationState(tallerId);

        if (state.alreadyCanceled()) {
            return;
        }

        boolean hasRemotePreapproval = state.preapprovalId() != null
                && !state.preapprovalId().isBlank();
        if (hasRemotePreapproval && !props.isEnabled()) {
            throw new PagoException(
                    "No se puede cancelar la suscripción porque Mercado Pago está deshabilitado. "
                            + "El plan local no fue modificado.");
        }

        if (hasRemotePreapproval) {
            try {
                mercadoPagoRestClient.put()
                        .uri("/preapproval/{id}", state.preapprovalId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("status", "canceled"))
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException ex) {
                logProviderFailure("cancelar preapproval", state.preapprovalId(), ex);
                throw new PagoException("No se pudo cancelar la suscripción en Mercado Pago.", ex);
            }
        }

        subscriptionStateService.applyLocalCancellation(tallerId);
        log.info("Suscripción del taller {} cancelada y convertida a FREE.", tallerId);
    }

    public boolean firmaWebhookValida(String dataId, String xSignature, String xRequestId) {
        return signatureValidator.isValid(dataId, xSignature, xRequestId);
    }

    private void processClaim(PaymentEventInboxService.EventClaim claim) {
        try {
            switch (claim.eventType()) {
                case TOPIC_PREAPPROVAL -> processPreapproval(claim.dataId());
                case TOPIC_AUTHORIZED_PAYMENT -> processAuthorizedPayment(claim.dataId());
                case TOPIC_PAYMENT -> {
                    if (!processPayment(claim.dataId())) {
                        eventInboxService.markIgnored(
                                claim.eventId(), "Pago ajeno a la cuenta configurada");
                        return;
                    }
                }
                default -> {
                    eventInboxService.markIgnored(claim.eventId(), "Tópico no soportado");
                    return;
                }
            }
            eventInboxService.markProcessed(claim.eventId());
        } catch (RuntimeException ex) {
            eventInboxService.markFailed(claim.eventId(), ex);
            log.warn("Evento MP {} quedó pendiente de reintento (tipo={}).",
                    claim.eventId(), claim.eventType());
        }
    }

    private void dispatchClaim(PaymentEventInboxService.EventClaim claim) {
        try {
            eventTaskExecutor.execute(() -> processClaim(claim));
        } catch (RuntimeException ex) {
            eventInboxService.markFailed(claim.eventId(), ex);
            log.warn("No se pudo encolar el evento MP {}; quedó pendiente de reintento.",
                    claim.eventId());
        }
    }

    private void processPreapproval(String dataId) {
        MercadoPagoPreapprovalResponse response;
        try {
            response = mercadoPagoRestClient.get()
                    .uri("/preapproval/{id}", dataId)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken())
                    .retrieve()
                    .body(MercadoPagoPreapprovalResponse.class);
        } catch (RestClientException ex) {
            logProviderFailure("consultar preapproval", dataId, ex);
            throw new PagoException("No se pudo consultar la suscripción en Mercado Pago.", ex);
        }
        subscriptionStateService.applyPreapproval(dataId, response);
    }

    private void processAuthorizedPayment(String dataId) {
        MercadoPagoAuthorizedPaymentResponse response;
        try {
            response = mercadoPagoRestClient.get()
                    .uri("/authorized_payments/{id}", dataId)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken())
                    .retrieve()
                    .body(MercadoPagoAuthorizedPaymentResponse.class);
        } catch (RestClientException ex) {
            logProviderFailure("consultar factura recurrente", dataId, ex);
            throw new PagoException("No se pudo consultar el cobro recurrente en Mercado Pago.", ex);
        }
        subscriptionStateService.applyAuthorizedPayment(dataId, response);
    }

    private boolean processPayment(String dataId) {
        MercadoPagoPaymentResponse payment;
        try {
            payment = mercadoPagoRestClient.get()
                    .uri("/v1/payments/{id}", dataId)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken())
                    .retrieve()
                    .body(MercadoPagoPaymentResponse.class);
        } catch (RestClientException ex) {
            logProviderFailure("consultar payment", dataId, ex);
            throw new PagoException("No se pudo consultar el pago en Mercado Pago.", ex);
        }
        if (!responseValidator.validatePayment(payment, dataId)) {
            return false;
        }

        MercadoPagoAuthorizedPaymentsSearchResponse search;
        try {
            search = mercadoPagoRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/authorized_payments/search")
                            .queryParam("payment_id", dataId)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, bearerToken())
                    .retrieve()
                    .body(MercadoPagoAuthorizedPaymentsSearchResponse.class);
        } catch (RestClientException ex) {
            logProviderFailure("buscar factura por payment", dataId, ex);
            throw new PagoException(
                    "No se pudo vincular el pago con su factura recurrente en Mercado Pago.", ex);
        }
        if (search == null || search.results() == null || search.results().size() != 1) {
            throw new PagoException(
                    "Mercado Pago todavía no informó una factura recurrente única para el pago.");
        }

        MercadoPagoAuthorizedPaymentResponse invoice = search.results().getFirst();
        responseValidator.validatePaymentInvoice(payment, invoice);
        MercadoPagoAuthorizedPaymentResponse currentInvoice =
                mergeCurrentPaymentState(invoice, payment);
        subscriptionStateService.applyAuthorizedPayment(currentInvoice.id(), currentInvoice);
        return true;
    }

    private MercadoPagoAuthorizedPaymentResponse mergeCurrentPaymentState(
            MercadoPagoAuthorizedPaymentResponse invoice,
            MercadoPagoPaymentResponse payment) {
        String effectiveStatus = payment.status();
        BigDecimal refunded = payment.transactionAmountRefunded();
        if ("approved".equalsIgnoreCase(effectiveStatus)
                && refunded != null && refunded.signum() > 0) {
            effectiveStatus = refunded.compareTo(payment.transactionAmount()) >= 0
                    ? "refunded"
                    : "partially_refunded";
        }
        OffsetDateTime lastModified = latest(invoice.lastModified(), payment.dateLastUpdated());
        return new MercadoPagoAuthorizedPaymentResponse(
                invoice.id(),
                invoice.preapprovalId(),
                invoice.externalReference(),
                invoice.currencyId(),
                invoice.transactionAmount(),
                invoice.status(),
                invoice.summarized(),
                invoice.retryAttempt(),
                invoice.debitDate(),
                invoice.dateCreated(),
                lastModified,
                new MercadoPagoAuthorizedPaymentResponse.Payment(
                        payment.id(), effectiveStatus, payment.statusDetail()));
    }

    private OffsetDateTime latest(OffsetDateTime first, OffsetDateTime second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private void processAuthorizedPaymentsForSubscription(String preapprovalId) {
        final int pageSize = 50;
        int offset = 0;
        for (int page = 0; page < props.getReconciliationMaxPaymentPages(); page++) {
            MercadoPagoAuthorizedPaymentsSearchResponse response;
            try {
                int pageOffset = offset;
                response = mercadoPagoRestClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/authorized_payments/search")
                                .queryParam("preapproval_id", preapprovalId)
                                .queryParam("sort", "date_created")
                                .queryParam("criteria", "desc")
                                .queryParam("offset", pageOffset)
                                .queryParam("limit", pageSize)
                                .build())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .retrieve()
                        .body(MercadoPagoAuthorizedPaymentsSearchResponse.class);
            } catch (RestClientException ex) {
                logProviderFailure("buscar facturas autorizadas", preapprovalId, ex);
                throw new PagoException("No se pudieron conciliar las facturas de Mercado Pago.", ex);
            }
            if (response == null || response.results() == null) {
                throw new PagoException("Respuesta inválida de Mercado Pago al conciliar facturas.");
            }
            response.results().forEach(payment ->
                    subscriptionStateService.applyAuthorizedPayment(payment.id(), payment));

            offset += response.results().size();
            Integer total = response.paging() == null ? null : response.paging().total();
            if (response.results().isEmpty() || total == null || offset >= total) {
                return;
            }
        }
    }

    private String normalizeTopic(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(TOPIC_AUTHORIZED_PAYMENT)) {
            return TOPIC_AUTHORIZED_PAYMENT;
        }
        if (normalized.contains(TOPIC_PREAPPROVAL)
                || (normalized.contains("preapproval") && !normalized.contains("plan"))) {
            return TOPIC_PREAPPROVAL;
        }
        if (TOPIC_PAYMENT.equals(normalized) || "payments".equals(normalized)
                || normalized.startsWith("payment.")) {
            return TOPIC_PAYMENT;
        }
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private void requireCheckoutEnabled() {
        if (!props.isEnabled()) {
            throw new PagoException("La integración con Mercado Pago no está habilitada.");
        }
        if (!props.isCheckoutEnabled()) {
            throw new PagoException("Los nuevos checkouts están temporalmente deshabilitados.");
        }
    }

    private String bearerToken() {
        return "Bearer " + props.getAccessToken();
    }

    private void logProviderFailure(String operation, String resourceId, RestClientException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            log.error("Fallo MP al {} (resourceId={}, status={}).",
                    operation, resourceId, responseException.getStatusCode().value());
        } else {
            log.error("Fallo de red MP al {} (resourceId={}, tipo={}).",
                    operation, resourceId, ex.getClass().getSimpleName());
        }
    }
}
