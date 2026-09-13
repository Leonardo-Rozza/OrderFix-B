package com.leonardorozza.mvgrreparacionesbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ConflictException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PaymentEventStatus;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.PaymentEventRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SubscriptionPaymentRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SubscriptionProviderLinkRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoPreapprovalResponse;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoAuthorizedPaymentResponse;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoCheckoutStateService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoSubscriptionStateService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.PaymentEventInboxService;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MercadoPagoPersistenceTests extends IntegrationTestBase {

    // Legacy H2 JPA fixture does not install the PostgreSQL outbox; the real integration is covered in PG IT.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureEffects closureEffects;

    @Autowired
    private PaymentEventInboxService inboxService;

    @Autowired
    private PaymentEventRepository eventRepository;

    @Autowired
    private MercadoPagoCheckoutStateService checkoutStateService;

    @Autowired
    private MercadoPagoSubscriptionStateService subscriptionStateService;

    @Autowired
    private SubscriptionProviderLinkRepository linkRepository;

    @Autowired
    private SubscriptionPaymentRepository paymentRepository;

    @Autowired
    private MercadoPagoProperties properties;

    @Test
    void inboxDeduplicaEventoYPermiteReintentarUnFallo() {
        String providerEventId = "evt-" + UUID.randomUUID();
        var first = inboxService.register(
                "subscription_preapproval", "pre-1", providerEventId, "req-1", 3);
        assertThat(first.shouldProcess()).isTrue();

        inboxService.markFailed(first.eventId(), new IllegalStateException("fallo transitorio"));
        var retry = inboxService.register(
                "subscription_preapproval", "pre-1", providerEventId, "req-distinto", 3);
        assertThat(retry.shouldProcess()).isTrue();
        assertThat(retry.eventId()).isEqualTo(first.eventId());

        inboxService.markProcessed(retry.eventId());
        var duplicate = inboxService.register(
                "subscription_preapproval", "pre-1", providerEventId, "req-3", 3);

        assertThat(duplicate.shouldProcess()).isFalse();
        var persisted = eventRepository.findById(first.eventId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PaymentEventStatus.PROCESSED);
        assertThat(persisted.getAttempts()).isEqualTo(2);
        assertThat(persisted.getProcessedAt()).isNotNull();
    }

    @Test
    void inboxRecuperaProcesamientoInterrumpidoParaQueElSchedulerLoReintente() {
        var claim = inboxService.register(
                "subscription_preapproval", "pre-stale", "evt-" + UUID.randomUUID(), "req-stale", 3);
        var event = eventRepository.findById(claim.eventId()).orElseThrow();
        event.setLastAttemptAt(Instant.now().minus(Duration.ofMinutes(2)));
        eventRepository.save(event);

        assertThat(inboxService.recoverStaleProcessing(Duration.ofMinutes(1))).isEqualTo(1);
        assertThat(eventRepository.findById(claim.eventId()).orElseThrow().getStatus())
                .isEqualTo(PaymentEventStatus.FAILED);
    }

    @Test
    void checkoutPendienteSeReusaYUnaResuscripcionConservaElVinculoAnterior() throws Exception {
        configureExpectedAccount();
        String token = registrar("Taller MP Persistencia", "mp-persistencia-" + UUID.randomUUID() + "@test.com");
        long tallerId = tallerId(token);

        var first = checkoutStateService.prepare(tallerId);
        assertThatThrownBy(() -> checkoutStateService.prepare(tallerId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ya se está creando");

        checkoutStateService.markAttemptFailed(first.linkId());
        var retry = checkoutStateService.prepare(tallerId);
        assertThat(retry.linkId()).isEqualTo(first.linkId());
        assertThat(retry.idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(retry.externalReference()).isEqualTo(first.externalReference());

        String checkoutUrl = "https://www.mercadopago.com.ar/subscriptions/checkout?preapproval_id=pre-1";
        checkoutStateService.complete(first.linkId(), new MercadoPagoPreapprovalResponse(
                "pre-1", "pending", checkoutUrl, first.externalReference(), null,
                null, null, null, null));

        var existing = checkoutStateService.prepare(tallerId);
        assertThat(existing.existing()).isTrue();
        assertThat(existing.existingResponse().preapprovalId()).isEqualTo("pre-1");

        subscriptionStateService.applyLocalCancellation(tallerId);
        var second = checkoutStateService.prepare(tallerId);

        assertThat(second.linkId()).isNotEqualTo(first.linkId());
        assertThat(second.externalReference()).isNotEqualTo(first.externalReference());
        assertThat(linkRepository.count()).isGreaterThanOrEqualTo(2);
        assertThat(linkRepository.findById(first.linkId()).orElseThrow().isCurrent()).isFalse();
        assertThat(linkRepository.findById(second.linkId()).orElseThrow().isCurrent()).isTrue();

        subscriptionStateService.applyPreapproval("pre-1", new MercadoPagoPreapprovalResponse(
                "pre-1", "authorized", null, first.externalReference(), "payer-old",
                100200300L, 12345678L, null, recurring()));
        subscriptionStateService.applyAuthorizedPayment("invoice-old-cycle",
                authorizedPayment("invoice-old-cycle", "pre-1", first.externalReference(), "approved"));

        var currentSubscription = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        assertThat(currentSubscription.getMpExternalReference()).isEqualTo(second.externalReference());
        assertThat(currentSubscription.getMpStatus()).isEqualTo("creating");
        assertThat(currentSubscription.getPlan()).isEqualTo(PlanType.FREE);
        assertThat(paymentRepository.findByProviderAndExternalAuthorizedPaymentId(
                "MERCADO_PAGO", "invoice-old-cycle")).isPresent();
    }

    @Test
    void estadosDePreapprovalUsanFechaRemotaYCierranEntitlementAlPausar() throws Exception {
        configureExpectedAccount();
        String token = registrar("Taller MP Estados", "mp-estados-" + UUID.randomUUID() + "@test.com");
        long tallerId = tallerId(token);
        var preparation = checkoutStateService.prepare(tallerId);
        checkoutStateService.complete(preparation.linkId(), new MercadoPagoPreapprovalResponse(
                "pre-states", "pending", checkoutUrl("pre-states"), preparation.externalReference(), null,
                100200300L, 12345678L, null, recurring()));

        OffsetDateTime nextPayment = OffsetDateTime.parse("2026-09-30T23:00:00-03:00");
        subscriptionStateService.applyPreapproval("pre-states", new MercadoPagoPreapprovalResponse(
                "pre-states", "authorized", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, nextPayment, recurring()));

        var active = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        assertThat(active.getPlan()).isEqualTo(PlanType.PRO);
        assertThat(active.getEstado()).isEqualTo(EstadoSuscripcion.ACTIVA);
        assertThat(active.getMpNextPaymentAt()).isEqualTo(nextPayment.toInstant());
        assertThat(active.getProximoCobro()).isEqualTo(nextPayment.toInstant()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate());

        subscriptionStateService.applyPreapproval("pre-states", new MercadoPagoPreapprovalResponse(
                "pre-states", "provider_unknown", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, nextPayment, recurring()));
        assertThat(suscripcionRepository.findByTallerId(tallerId).orElseThrow().getEstado())
                .as("un estado remoto desconocido debe cerrar un PRO previamente concedido")
                .isEqualTo(EstadoSuscripcion.VENCIDA);

        subscriptionStateService.applyPreapproval("pre-states", new MercadoPagoPreapprovalResponse(
                "pre-states", "authorized", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, nextPayment, recurring()));

        subscriptionStateService.applyPreapproval("pre-states", new MercadoPagoPreapprovalResponse(
                "pre-states", "paused", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, nextPayment, recurring()));

        var paused = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        assertThat(paused.getPlan()).isEqualTo(PlanType.PRO);
        assertThat(paused.getEstado()).isEqualTo(EstadoSuscripcion.VENCIDA);

        subscriptionStateService.applyAuthorizedPayment("invoice-paused",
                authorizedPayment("invoice-paused", "pre-states", preparation.externalReference(),
                        "approved", OffsetDateTime.parse("2026-10-01T01:00:00Z")));
        assertThat(suscripcionRepository.findByTallerId(tallerId).orElseThrow().getEstado())
                .as("un cobro no reactiva por sí solo un preapproval pausado")
                .isEqualTo(EstadoSuscripcion.VENCIDA);

        subscriptionStateService.applyLocalCancellation(tallerId);
        subscriptionStateService.applyAuthorizedPayment("invoice-after-cancel",
                authorizedPayment("invoice-after-cancel", "pre-states", preparation.externalReference(),
                        "approved", OffsetDateTime.parse("2026-10-02T01:00:00Z")));
        var canceled = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        assertThat(canceled.getPlan()).isEqualTo(PlanType.FREE);
        assertThat(canceled.getEstado()).isEqualTo(EstadoSuscripcion.ACTIVA);
    }

    @Test
    void cobroRechazadoCierraAccesoYAprobadoReactivaSinDuplicarFactura() throws Exception {
        configureExpectedAccount();
        String token = registrar("Taller MP Cobros", "mp-cobros-" + UUID.randomUUID() + "@test.com");
        long tallerId = tallerId(token);
        var preparation = checkoutStateService.prepare(tallerId);
        checkoutStateService.complete(preparation.linkId(), new MercadoPagoPreapprovalResponse(
                "pre-payments", "pending", checkoutUrl("pre-payments"), preparation.externalReference(), null,
                100200300L, 12345678L, null, recurring()));
        subscriptionStateService.applyPreapproval("pre-payments", new MercadoPagoPreapprovalResponse(
                "pre-payments", "authorized", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, null, recurring()));

        subscriptionStateService.applyAuthorizedPayment("invoice-1",
                authorizedPayment("invoice-1", "pre-payments", preparation.externalReference(), "rejected"));
        var rejected = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        assertThat(rejected.getPlan()).isEqualTo(PlanType.PRO);
        assertThat(rejected.getEstado()).isEqualTo(EstadoSuscripcion.VENCIDA);
        long countAfterRejected = paymentRepository.count();

        subscriptionStateService.applyPreapproval("pre-payments", new MercadoPagoPreapprovalResponse(
                "pre-payments", "authorized", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, null, recurring()));
        assertThat(suscripcionRepository.findByTallerId(tallerId).orElseThrow().getEstado())
                .as("el preapproval autorizado no debe tapar la mora de la última factura")
                .isEqualTo(EstadoSuscripcion.VENCIDA);

        subscriptionStateService.applyAuthorizedPayment("invoice-1",
                authorizedPayment("invoice-1", "pre-payments", preparation.externalReference(),
                        "approved", OffsetDateTime.parse("2026-08-14T02:00:00Z")));
        var approved = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        assertThat(approved.getEstado()).isEqualTo(EstadoSuscripcion.ACTIVA);
        assertThat(paymentRepository.count()).isEqualTo(countAfterRejected);
        assertThat(paymentRepository
                .findByProviderAndExternalAuthorizedPaymentId("MERCADO_PAGO", "invoice-1")
                .orElseThrow().getPaymentStatus()).isEqualTo("approved");
    }

    @Test
    void cobroHistoricoFueraDeOrdenSeAuditaPeroNoRevierteElEntitlement() throws Exception {
        configureExpectedAccount();
        String token = registrar("Taller MP Orden", "mp-orden-" + UUID.randomUUID() + "@test.com");
        long tallerId = tallerId(token);
        var preparation = checkoutStateService.prepare(tallerId);
        checkoutStateService.complete(preparation.linkId(), new MercadoPagoPreapprovalResponse(
                "pre-order", "pending", checkoutUrl("pre-order"), preparation.externalReference(), null,
                100200300L, 12345678L, null, recurring()));
        subscriptionStateService.applyPreapproval("pre-order", new MercadoPagoPreapprovalResponse(
                "pre-order", "authorized", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, null, recurring()));

        subscriptionStateService.applyAuthorizedPayment("invoice-new",
                authorizedPayment("invoice-new", "pre-order", preparation.externalReference(),
                        "rejected", OffsetDateTime.parse("2026-08-15T01:00:00Z")));
        subscriptionStateService.applyAuthorizedPayment("invoice-old",
                authorizedPayment("invoice-old", "pre-order", preparation.externalReference(),
                        "approved", OffsetDateTime.parse("2026-08-14T01:00:00Z")));

        var subscription = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        assertThat(subscription.getEstado()).isEqualTo(EstadoSuscripcion.VENCIDA);
        assertThat(subscription.getMpLastAuthorizedPaymentId()).isEqualTo("invoice-new");
        assertThat(paymentRepository
                .findByProviderAndExternalAuthorizedPaymentId("MERCADO_PAGO", "invoice-old"))
                .isPresent();
    }

    @Test
    void chargebackReembolsadoAlVendedorRestauraElAccesoYReembolsoParcialLoBloquea()
            throws Exception {
        configureExpectedAccount();
        String token = registrar(
                "Taller MP Contracargo", "mp-chargeback-" + UUID.randomUUID() + "@test.com");
        long tallerId = tallerId(token);
        var preparation = checkoutStateService.prepare(tallerId);
        checkoutStateService.complete(preparation.linkId(), new MercadoPagoPreapprovalResponse(
                "pre-chargeback", "pending", checkoutUrl("pre-chargeback"),
                preparation.externalReference(), null,
                100200300L, 12345678L, null, recurring()));
        subscriptionStateService.applyPreapproval("pre-chargeback", new MercadoPagoPreapprovalResponse(
                "pre-chargeback", "authorized", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, null, recurring()));

        subscriptionStateService.applyAuthorizedPayment("invoice-chargeback",
                authorizedPayment("invoice-chargeback", "pre-chargeback",
                        preparation.externalReference(), "charged_back", "in_process",
                        OffsetDateTime.parse("2026-08-20T10:00:00Z")));
        assertThat(suscripcionRepository.findByTallerId(tallerId).orElseThrow().getEstado())
                .isEqualTo(EstadoSuscripcion.VENCIDA);

        subscriptionStateService.applyAuthorizedPayment("invoice-chargeback",
                authorizedPayment("invoice-chargeback", "pre-chargeback",
                        preparation.externalReference(), "charged_back", "reimbursed",
                        OffsetDateTime.parse("2026-08-20T11:00:00Z")));
        assertThat(suscripcionRepository.findByTallerId(tallerId).orElseThrow().getEstado())
                .isEqualTo(EstadoSuscripcion.ACTIVA);

        subscriptionStateService.applyAuthorizedPayment("invoice-partial-refund",
                authorizedPayment("invoice-partial-refund", "pre-chargeback",
                        preparation.externalReference(), "partially_refunded", "partial_refund",
                        OffsetDateTime.parse("2026-08-20T12:00:00Z")));
        assertThat(suscripcionRepository.findByTallerId(tallerId).orElseThrow().getEstado())
                .isEqualTo(EstadoSuscripcion.VENCIDA);
    }

    @Test
    void cancelacionLocalEsIdempotenteYConservaAuditoria() throws Exception {
        configureExpectedAccount();
        String token = registrar(
                "Taller MP Cancelación", "mp-cancel-" + UUID.randomUUID() + "@test.com");
        long tallerId = tallerId(token);
        var preparation = checkoutStateService.prepare(tallerId);
        checkoutStateService.complete(preparation.linkId(), new MercadoPagoPreapprovalResponse(
                "pre-cancel", "pending", checkoutUrl("pre-cancel"), preparation.externalReference(), null,
                100200300L, 12345678L, null, recurring()));
        long linksBefore = linkRepository.count();

        subscriptionStateService.applyLocalCancellation(tallerId);
        subscriptionStateService.applyLocalCancellation(tallerId);

        var canceled = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        var link = linkRepository.findById(preparation.linkId()).orElseThrow();
        assertThat(canceled.getPlan()).isEqualTo(PlanType.FREE);
        assertThat(canceled.getEstado()).isEqualTo(EstadoSuscripcion.ACTIVA);
        assertThat(canceled.getMpStatus()).isEqualTo("canceled");
        assertThat(canceled.getMpPreapprovalId()).isEqualTo("pre-cancel");
        assertThat(canceled.getMpExternalReference()).isEqualTo(preparation.externalReference());
        assertThat(link.getExternalSubscriptionId()).isEqualTo("pre-cancel");
        assertThat(link.getExternalReference()).isEqualTo(preparation.externalReference());
        assertThat(linkRepository.count()).isEqualTo(linksBefore);
    }

    @Test
    void actualizacionViejaDeLaMismaFacturaNoPisaUnReembolsoNuevo() throws Exception {
        configureExpectedAccount();
        String token = registrar(
                "Taller MP Snapshot", "mp-snapshot-" + UUID.randomUUID() + "@test.com");
        long tallerId = tallerId(token);
        var preparation = checkoutStateService.prepare(tallerId);
        checkoutStateService.complete(preparation.linkId(), new MercadoPagoPreapprovalResponse(
                "pre-snapshot", "pending", checkoutUrl("pre-snapshot"),
                preparation.externalReference(), null,
                100200300L, 12345678L, null, recurring()));
        subscriptionStateService.applyPreapproval("pre-snapshot", new MercadoPagoPreapprovalResponse(
                "pre-snapshot", "authorized", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, null, recurring()));

        OffsetDateTime billingAt = OffsetDateTime.parse("2026-08-14T01:00:00Z");
        subscriptionStateService.applyAuthorizedPayment("invoice-snapshot",
                authorizedPayment("invoice-snapshot", "pre-snapshot",
                        preparation.externalReference(), "refunded", "refunded",
                        billingAt, OffsetDateTime.parse("2026-08-20T12:00:00Z")));
        subscriptionStateService.applyAuthorizedPayment("invoice-snapshot",
                authorizedPayment("invoice-snapshot", "pre-snapshot",
                        preparation.externalReference(), "approved", "accredited",
                        billingAt, OffsetDateTime.parse("2026-08-20T11:00:00Z")));
        subscriptionStateService.applyPreapproval("pre-snapshot", new MercadoPagoPreapprovalResponse(
                "pre-snapshot", "authorized", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, null, recurring()));

        var subscription = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        var payment = paymentRepository.findByProviderAndExternalAuthorizedPaymentId(
                "MERCADO_PAGO", "invoice-snapshot").orElseThrow();
        assertThat(payment.getPaymentStatus()).isEqualTo("refunded");
        assertThat(payment.getProviderModifiedAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-20T12:00:00Z").toInstant());
        assertThat(subscription.getEstado()).isEqualTo(EstadoSuscripcion.VENCIDA);
    }

    @Test
    void facturaViejaActualizadaTardeNoSuperaUnCicloPosteriorRechazado() throws Exception {
        configureExpectedAccount();
        String token = registrar(
                "Taller MP Ciclos", "mp-cycles-" + UUID.randomUUID() + "@test.com");
        long tallerId = tallerId(token);
        var preparation = checkoutStateService.prepare(tallerId);
        checkoutStateService.complete(preparation.linkId(), new MercadoPagoPreapprovalResponse(
                "pre-cycles", "pending", checkoutUrl("pre-cycles"), preparation.externalReference(), null,
                100200300L, 12345678L, null, recurring()));
        subscriptionStateService.applyPreapproval("pre-cycles", new MercadoPagoPreapprovalResponse(
                "pre-cycles", "authorized", null, preparation.externalReference(), "payer-1",
                100200300L, 12345678L, null, recurring()));

        subscriptionStateService.applyAuthorizedPayment("invoice-september",
                authorizedPayment("invoice-september", "pre-cycles",
                        preparation.externalReference(), "rejected", "rejected",
                        OffsetDateTime.parse("2026-09-14T01:00:00Z"),
                        OffsetDateTime.parse("2026-09-14T02:00:00Z")));
        subscriptionStateService.applyAuthorizedPayment("invoice-august",
                authorizedPayment("invoice-august", "pre-cycles",
                        preparation.externalReference(), "charged_back", "reimbursed",
                        OffsetDateTime.parse("2026-08-14T01:00:00Z"),
                        OffsetDateTime.parse("2026-10-01T01:00:00Z")));

        var subscription = suscripcionRepository.findByTallerId(tallerId).orElseThrow();
        assertThat(subscription.getEstado()).isEqualTo(EstadoSuscripcion.VENCIDA);
        assertThat(subscription.getMpLastAuthorizedPaymentId()).isEqualTo("invoice-september");
        assertThat(paymentRepository.findByProviderAndExternalAuthorizedPaymentId(
                "MERCADO_PAGO", "invoice-august")).isPresent();
    }

    private long tallerId(String token) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return new ObjectMapper().readTree(payload).get("tallerId").asLong();
    }

    private void configureExpectedAccount() {
        properties.setCollectorId(100200300L);
        properties.setApplicationId(12345678L);
        properties.setAmount(new BigDecimal("24900.00"));
        properties.setCurrency("ARS");
    }

    private MercadoPagoPreapprovalResponse.AutoRecurring recurring() {
        return new MercadoPagoPreapprovalResponse.AutoRecurring(
                new BigDecimal("24900.00"), "ARS");
    }

    private String checkoutUrl(String id) {
        return "https://www.mercadopago.com.ar/subscriptions/checkout?preapproval_id=" + id;
    }

    private MercadoPagoAuthorizedPaymentResponse authorizedPayment(
            String id, String preapprovalId, String externalReference, String paymentStatus) {
        return authorizedPayment(id, preapprovalId, externalReference, paymentStatus,
                OffsetDateTime.parse("2026-08-14T01:00:00Z"));
    }

    private MercadoPagoAuthorizedPaymentResponse authorizedPayment(
            String id,
            String preapprovalId,
            String externalReference,
            String paymentStatus,
            OffsetDateTime now) {
        String statusDetail = "approved".equals(paymentStatus)
                ? "accredited"
                : "cc_rejected_other_reason";
        return authorizedPayment(
                id, preapprovalId, externalReference, paymentStatus, statusDetail, now, now);
    }

    private MercadoPagoAuthorizedPaymentResponse authorizedPayment(
            String id,
            String preapprovalId,
            String externalReference,
            String paymentStatus,
            String statusDetail,
            OffsetDateTime now) {
        return authorizedPayment(
                id, preapprovalId, externalReference, paymentStatus, statusDetail, now, now);
    }

    private MercadoPagoAuthorizedPaymentResponse authorizedPayment(
            String id,
            String preapprovalId,
            String externalReference,
            String paymentStatus,
            String statusDetail,
            OffsetDateTime billingAt,
            OffsetDateTime stateUpdatedAt) {
        return new MercadoPagoAuthorizedPaymentResponse(
                id,
                preapprovalId,
                externalReference,
                "ARS",
                new BigDecimal("24900.00"),
                "processed",
                paymentStatus,
                "rejected".equals(paymentStatus) ? 1 : 0,
                billingAt,
                billingAt,
                stateUpdatedAt,
                new MercadoPagoAuthorizedPaymentResponse.Payment(
                        "payment-1", paymentStatus, statusDetail));
    }
}
