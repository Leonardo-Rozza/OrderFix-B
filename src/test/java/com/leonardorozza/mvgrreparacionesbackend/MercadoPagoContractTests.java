package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.PagoException;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.CheckoutResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoAuthorizedPaymentResponse;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoPreapprovalResponse;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MercadoPagoContractTests {

    private static final String API_URL = "https://api.mercadopago.test";
    private static final String EXTERNAL_REFERENCE = "ofx_123";

    private MercadoPagoProperties properties;
    private TenantService tenantService;
    private MercadoPagoCheckoutStateService checkoutStateService;
    private MercadoPagoSubscriptionStateService subscriptionStateService;
    private PaymentEventInboxService inboxService;
    private MercadoPagoResponseValidator responseValidator;
    private MercadoPagoWebhookSignatureValidator signatureValidator;
    private MockRestServiceServer server;
    private RestClient restClient;
    private MercadoPagoService service;

    @BeforeEach
    void setUp() {
        properties = new MercadoPagoProperties();
        properties.setEnabled(true);
        properties.setCheckoutEnabled(true);
        properties.setAccessToken("TEST-token");
        properties.setWebhookSecret("webhook-secret");
        properties.setCollectorId(100200300L);
        properties.setApplicationId(12345678L);
        properties.setAmount(new BigDecimal("24900.00"));
        properties.setCurrency("ARS");
        properties.setBackUrl("https://app.ordenfix.test/suscripcion/resultado");
        properties.setApiUrl(API_URL);

        tenantService = mock(TenantService.class);
        checkoutStateService = mock(MercadoPagoCheckoutStateService.class);
        subscriptionStateService = mock(MercadoPagoSubscriptionStateService.class);
        inboxService = mock(PaymentEventInboxService.class);
        responseValidator = new MercadoPagoResponseValidator(properties);
        signatureValidator = mock(MercadoPagoWebhookSignatureValidator.class);

        RestClient.Builder builder = RestClient.builder().baseUrl(API_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        service = new MercadoPagoService(
                properties,
                restClient,
                tenantService,
                checkoutStateService,
                subscriptionStateService,
                inboxService,
                responseValidator,
                signatureValidator,
                Runnable::run);
    }

    @Test
    void checkoutEnviaContratoExactoEIdempotencyKey() {
        when(tenantService.currentTallerId()).thenReturn(7L);
        var preparation = preparation();
        when(checkoutStateService.prepare(7L)).thenReturn(preparation);
        when(checkoutStateService.complete(eq(11L), any()))
                .thenReturn(new CheckoutResponseDto("pre-1", checkoutUrl("pre-1")));

        server.expect(requestTo(API_URL + "/preapproval"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer TEST-token"))
                .andExpect(header("X-Idempotency-Key", "idem-1"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "reason":"OrdenFix PRO - Suscripción mensual",
                          "auto_recurring":{
                            "frequency":1,
                            "frequency_type":"months",
                            "transaction_amount":24900.00,
                            "currency_id":"ARS"
                          },
                          "back_url":"https://app.ordenfix.test/suscripcion/resultado",
                          "payer_email":"admin@taller.test",
                          "status":"pending",
                          "external_reference":"ofx_123"
                        }
                        """, true))
                .andRespond(withSuccess(preapprovalJson("pre-1", "pending", "24900.00"),
                        MediaType.APPLICATION_JSON));

        CheckoutResponseDto response = service.crearCheckoutSuscripcion();

        assertThat(response.preapprovalId()).isEqualTo("pre-1");
        verify(checkoutStateService).complete(eq(11L), any(MercadoPagoPreapprovalResponse.class));
        server.verify();
    }

    @Test
    void checkoutPendienteSeReutilizaSinCrearOtroPreapproval() {
        when(tenantService.currentTallerId()).thenReturn(7L);
        CheckoutResponseDto existing = new CheckoutResponseDto("pre-existing", checkoutUrl("pre-existing"));
        when(checkoutStateService.prepare(7L)).thenReturn(
                new MercadoPagoCheckoutStateService.CheckoutPreparation(
                        true, existing, null, null, null, null, null));

        assertThat(service.crearCheckoutSuscripcion()).isEqualTo(existing);
        verify(checkoutStateService, never()).complete(anyLong(), any());
        server.verify();
    }

    @Test
    void corteOperativoBloqueaAltasSinDeshabilitarLaIntegracion() {
        properties.setCheckoutEnabled(false);

        assertThatThrownBy(() -> service.crearCheckoutSuscripcion())
                .isInstanceOf(PagoException.class)
                .hasMessageContaining("temporalmente deshabilitados");

        verifyNoInteractions(checkoutStateService);
        server.verify();
    }

    @Test
    void integracionApagadaBloqueaAltasAunqueCheckoutEsteEncendido() {
        properties.setEnabled(false);
        properties.setCheckoutEnabled(true);

        assertThatThrownBy(() -> service.crearCheckoutSuscripcion())
                .isInstanceOf(PagoException.class)
                .hasMessageContaining("no está habilitada");

        verifyNoInteractions(tenantService, checkoutStateService);
        server.verify();
    }

    @Test
    void checkoutEstaApagadoPorDefecto() {
        assertThat(new MercadoPagoProperties().isCheckoutEnabled()).isFalse();
    }

    @Test
    void respuestaConImporteAlteradoNoSePersiste() {
        when(tenantService.currentTallerId()).thenReturn(7L);
        when(checkoutStateService.prepare(7L)).thenReturn(preparation());
        server.expect(requestTo(API_URL + "/preapproval"))
                .andRespond(withSuccess(preapprovalJson("pre-1", "pending", "1.00"),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.crearCheckoutSuscripcion())
                .isInstanceOf(PagoException.class)
                .hasMessageContaining("importe o moneda");
        verify(checkoutStateService, never()).complete(anyLong(), any());
    }

    @Test
    void timeoutOErrorRemotoPermiteReintentarSinDuplicarEstadoLocal() {
        when(tenantService.currentTallerId()).thenReturn(7L);
        when(checkoutStateService.prepare(7L)).thenReturn(preparation());
        server.expect(requestTo(API_URL + "/preapproval")).andRespond(withServerError());

        assertThatThrownBy(() -> service.crearCheckoutSuscripcion())
                .isInstanceOf(PagoException.class)
                .hasMessageContaining("reintentarlo sin generar un duplicado");
        verify(checkoutStateService, never()).complete(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancelarConPreapprovalHabilitadoCancelaRemotoAntesDeLaBajaLocal(boolean altasHabilitadas) {
        properties.setCheckoutEnabled(altasHabilitadas);
        when(tenantService.currentTallerId()).thenReturn(7L);
        when(subscriptionStateService.cancellationState(7L))
                .thenReturn(new MercadoPagoSubscriptionStateService.CancellationState("pre-1", false));
        server.expect(requestTo(API_URL + "/preapproval/pre-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"status\":\"canceled\"}"))
                .andRespond(withSuccess());

        service.cancelarSuscripcion();

        verify(subscriptionStateService).applyLocalCancellation(7L);
        server.verify();
    }

    @Test
    void cancelarConPreapprovalYMpDeshabilitadoFallaSinModificarEstadoLocal() {
        properties.setEnabled(false);
        when(tenantService.currentTallerId()).thenReturn(7L);
        when(subscriptionStateService.cancellationState(7L))
                .thenReturn(new MercadoPagoSubscriptionStateService.CancellationState("pre-1", false));

        assertThatThrownBy(() -> service.cancelarSuscripcion())
                .isInstanceOf(PagoException.class)
                .hasMessageContaining("Mercado Pago está deshabilitado")
                .hasMessageContaining("no fue modificado");

        verify(subscriptionStateService, never()).applyLocalCancellation(anyLong());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancelarSinPreapprovalPermiteBajaLocalAunqueMpEsteDeshabilitado(boolean altasHabilitadas) {
        properties.setCheckoutEnabled(altasHabilitadas);
        properties.setEnabled(false);
        when(tenantService.currentTallerId()).thenReturn(7L);
        when(subscriptionStateService.cancellationState(7L))
                .thenReturn(new MercadoPagoSubscriptionStateService.CancellationState(null, false));

        service.cancelarSuscripcion();

        verify(subscriptionStateService).applyLocalCancellation(7L);
        server.verify();
    }

    @Test
    void cancelarYaCanceladaEsIdempotenteAunqueMpEsteDeshabilitado() {
        properties.setEnabled(false);
        when(tenantService.currentTallerId()).thenReturn(7L);
        when(subscriptionStateService.cancellationState(7L))
                .thenReturn(new MercadoPagoSubscriptionStateService.CancellationState("pre-1", true));

        service.cancelarSuscripcion();
        service.cancelarSuscripcion();

        verify(subscriptionStateService, times(2)).cancellationState(7L);
        verify(subscriptionStateService, never()).applyLocalCancellation(anyLong());
        server.verify();
    }

    @Test
    void errorRemotoAlCancelarNoAplicaBajaLocal() {
        when(tenantService.currentTallerId()).thenReturn(7L);
        when(subscriptionStateService.cancellationState(7L))
                .thenReturn(new MercadoPagoSubscriptionStateService.CancellationState("pre-1", false));
        server.expect(requestTo(API_URL + "/preapproval/pre-1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withServerError());

        assertThatThrownBy(() -> service.cancelarSuscripcion())
                .isInstanceOf(PagoException.class)
                .hasMessageContaining("No se pudo cancelar");

        verify(subscriptionStateService, never()).applyLocalCancellation(anyLong());
        server.verify();
    }

    @Test
    void webhookPreapprovalSeConsultaYMarcaProcesado() {
        var claim = new PaymentEventInboxService.EventClaim(
                true, 31L, "subscription_preapproval", "pre-1");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);
        server.expect(requestTo(API_URL + "/preapproval/pre-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(preapprovalJson("pre-1", "authorized", "24900.00"),
                        MediaType.APPLICATION_JSON));

        service.procesarNotificacion(
                "subscription_preapproval.updated", "pre-1", "event-1", "request-1");

        verify(subscriptionStateService).applyPreapproval(eq("pre-1"), any());
        verify(inboxService).markProcessed(31L);
        server.verify();
    }

    @Test
    void webhookPersisteYDespachaSinProcesarEnElHiloQueRecibeLaNotificacion() {
        List<Runnable> queued = new ArrayList<>();
        MercadoPagoService asynchronousService = new MercadoPagoService(
                properties,
                restClient,
                tenantService,
                checkoutStateService,
                subscriptionStateService,
                inboxService,
                responseValidator,
                signatureValidator,
                queued::add);
        var claim = new PaymentEventInboxService.EventClaim(
                true, 41L, "unsupported", "resource-1");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);

        asynchronousService.procesarNotificacion(
                "unsupported", "resource-1", "event-10", "request-10");

        assertThat(queued).hasSize(1);
        verify(inboxService, never()).markIgnored(anyLong(), anyString());

        queued.getFirst().run();

        verify(inboxService).markIgnored(41L, "Tópico no soportado");
    }

    @Test
    void webhookConColaSaturadaQuedaFallidoParaRecuperacion() {
        MercadoPagoService rejectingService = new MercadoPagoService(
                properties,
                restClient,
                tenantService,
                checkoutStateService,
                subscriptionStateService,
                inboxService,
                responseValidator,
                signatureValidator,
                task -> {
                    throw new IllegalStateException("cola saturada");
                });
        var claim = new PaymentEventInboxService.EventClaim(
                true, 43L, "payment", "7008");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);

        rejectingService.procesarNotificacion(
                "payment", "7008", "event-12", "request-12");

        verify(inboxService).markFailed(eq(43L), any(IllegalStateException.class));
        verify(inboxService, never()).markProcessed(43L);
    }

    @Test
    void webhookDeCobroRecurrenteConsultaAuthorizedPayments() {
        var claim = new PaymentEventInboxService.EventClaim(
                true, 32L, "subscription_authorized_payment", "9001");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);
        server.expect(requestTo(API_URL + "/authorized_payments/9001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(authorizedPaymentJson(), MediaType.APPLICATION_JSON));

        service.procesarNotificacion(
                "subscription_authorized_payment", "9001", "event-2", "request-2");

        verify(subscriptionStateService).applyAuthorizedPayment(
                eq("9001"), any(MercadoPagoAuthorizedPaymentResponse.class));
        verify(inboxService).markProcessed(32L);
        server.verify();
    }

    @Test
    void webhookPaymentConsultaRecursoYFacturaAntesDeAplicarEntitlement() {
        var claim = new PaymentEventInboxService.EventClaim(true, 35L, "payment", "7001");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);
        server.expect(requestTo(API_URL + "/v1/payments/7001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        paymentJson("7001", 100200300L, "24900.00", "approved", "accredited", "0.00"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL + "/authorized_payments/search?payment_id=7001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"paging":{"total":1},"results":[%s]}
                        """.formatted(authorizedPaymentJson()), MediaType.APPLICATION_JSON));

        service.procesarNotificacion("payment.updated", "7001", "event-4", "request-4");

        verify(inboxService).register(eq("payment"), eq("7001"), eq("event-4"), eq("request-4"), anyInt());
        verify(subscriptionStateService).applyAuthorizedPayment(eq("9001"), argThat(invoice ->
                invoice.payment() != null
                        && "7001".equals(invoice.payment().id())
                        && "approved".equals(invoice.payment().status())
                        && "2026-08-20T12:00Z".equals(invoice.lastModified().toString())));
        verify(inboxService).markProcessed(35L);
        server.verify();
    }

    @Test
    void paymentDeCollectorAjenoQuedaIgnoradoSinBuscarFactura() {
        var claim = new PaymentEventInboxService.EventClaim(true, 36L, "payment", "7002");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);
        server.expect(requestTo(API_URL + "/v1/payments/7002"))
                .andRespond(withSuccess(
                        paymentJson("7002", 999L, "24900.00", "approved", "accredited", "0.00"),
                        MediaType.APPLICATION_JSON));

        service.procesarNotificacion("payments", "7002", "event-5", "request-5");

        verify(inboxService).markIgnored(eq(36L), contains("ajeno"));
        verify(inboxService, never()).markProcessed(36L);
        verifyNoInteractions(subscriptionStateService);
        server.verify();
    }

    @Test
    void paymentSinFacturaUnicaQuedaFallidoParaReintento() {
        var claim = new PaymentEventInboxService.EventClaim(true, 37L, "payment", "7003");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);
        server.expect(requestTo(API_URL + "/v1/payments/7003"))
                .andRespond(withSuccess(
                        paymentJson("7003", 100200300L, "24900.00", "approved", "accredited", "0.00"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL + "/authorized_payments/search?payment_id=7003"))
                .andRespond(withSuccess("{\"paging\":{\"total\":0},\"results\":[]}",
                        MediaType.APPLICATION_JSON));

        service.procesarNotificacion("payment", "7003", "event-6", "request-6");

        verify(inboxService).markFailed(eq(37L), any(PagoException.class));
        verify(inboxService, never()).markProcessed(37L);
        verifyNoInteractions(subscriptionStateService);
        server.verify();
    }

    @Test
    void paymentConFacturaIncoherenteNoModificaEntitlement() {
        var claim = new PaymentEventInboxService.EventClaim(true, 38L, "payment", "7004");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);
        server.expect(requestTo(API_URL + "/v1/payments/7004"))
                .andRespond(withSuccess(
                        paymentJson("7004", 100200300L, "24900.00", "approved", "accredited", "0.00"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL + "/authorized_payments/search?payment_id=7004"))
                .andRespond(withSuccess("""
                        {"paging":{"total":1},"results":[%s]}
                        """.formatted(authorizedPaymentJson("otro-payment")), MediaType.APPLICATION_JSON));

        service.procesarNotificacion("payment", "7004", "event-7", "request-7");

        verify(inboxService).markFailed(eq(38L), any(PagoException.class));
        verifyNoInteractions(subscriptionStateService);
        server.verify();
    }

    @Test
    void paymentConImporteAlteradoFallaAntesDeBuscarFactura() {
        var claim = new PaymentEventInboxService.EventClaim(true, 39L, "payment", "7005");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);
        server.expect(requestTo(API_URL + "/v1/payments/7005"))
                .andRespond(withSuccess(
                        paymentJson("7005", 100200300L, "1.00", "approved", "accredited", "0.00"),
                        MediaType.APPLICATION_JSON));

        service.procesarNotificacion("payment", "7005", "event-8", "request-8");

        verify(inboxService).markFailed(eq(39L), any(PagoException.class));
        verifyNoInteractions(subscriptionStateService);
        server.verify();
    }

    @Test
    void paymentReembolsadoPrevaleceSobreElEstadoAprobadoDeLaFactura() {
        var claim = new PaymentEventInboxService.EventClaim(true, 40L, "payment", "7006");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);
        server.expect(requestTo(API_URL + "/v1/payments/7006"))
                .andRespond(withSuccess(
                        paymentJson("7006", 100200300L, "24900.00", "approved", "accredited", "24900.00"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL + "/authorized_payments/search?payment_id=7006"))
                .andRespond(withSuccess("""
                        {"paging":{"total":1},"results":[%s]}
                        """.formatted(authorizedPaymentJson("7006")), MediaType.APPLICATION_JSON));

        service.procesarNotificacion("payment.updated", "7006", "event-9", "request-9");

        verify(subscriptionStateService).applyAuthorizedPayment(eq("9001"), argThat(invoice ->
                invoice.payment() != null && "refunded".equals(invoice.payment().status())));
        verify(inboxService).markProcessed(40L);
        server.verify();
    }

    @Test
    void chargebackConResolucionFavorableNoEsOcultadoPorElMontoReintegrado() {
        var claim = new PaymentEventInboxService.EventClaim(true, 42L, "payment", "7007");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);
        server.expect(requestTo(API_URL + "/v1/payments/7007"))
                .andRespond(withSuccess(
                        paymentJson("7007", 100200300L, "24900.00", "charged_back", "reimbursed", "24900.00"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL + "/authorized_payments/search?payment_id=7007"))
                .andRespond(withSuccess("""
                        {"paging":{"total":1},"results":[%s]}
                        """.formatted(authorizedPaymentJson("7007")), MediaType.APPLICATION_JSON));

        service.procesarNotificacion("payment.updated", "7007", "event-11", "request-11");

        verify(subscriptionStateService).applyAuthorizedPayment(eq("9001"), argThat(invoice ->
                invoice.payment() != null
                        && "charged_back".equals(invoice.payment().status())
                        && "reimbursed".equals(invoice.payment().statusDetail())));
        verify(inboxService).markProcessed(42L);
        server.verify();
    }

    @Test
    void webhookDuplicadoNoVuelveAConsultarNiAplicar() {
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new PaymentEventInboxService.EventClaim(
                        false, 33L, null, null));

        service.procesarNotificacion(
                "subscription_preapproval", "pre-1", "event-1", "request-1");

        verifyNoInteractions(subscriptionStateService);
        server.verify();
    }

    @Test
    void fallaRemotaDelWebhookQuedaDurableParaReintentoYSinPropagarse() {
        var claim = new PaymentEventInboxService.EventClaim(
                true, 34L, "subscription_preapproval", "pre-1");
        when(inboxService.register(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(claim);
        server.expect(requestTo(API_URL + "/preapproval/pre-1")).andRespond(withServerError());

        service.procesarNotificacion(
                "subscription_preapproval", "pre-1", "event-3", "request-3");

        verify(inboxService).markFailed(eq(34L), any(PagoException.class));
        verify(inboxService, never()).markProcessed(34L);
        server.verify();
    }

    @Test
    void conciliacionConsultaSuscripcionYFacturasSinDependerDelWebhook() {
        when(subscriptionStateService.reconciliationCandidates(100)).thenReturn(java.util.List.of("pre-1"));
        server.expect(requestTo(API_URL + "/preapproval/pre-1"))
                .andRespond(withSuccess(preapprovalJson("pre-1", "authorized", "24900.00"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL
                        + "/authorized_payments/search?preapproval_id=pre-1&sort=date_created"
                        + "&criteria=desc&offset=0&limit=50"))
                .andRespond(withSuccess("""
                        {
                          "paging":{"offset":0,"limit":50,"total":1},
                          "results":[%s]
                        }
                        """.formatted(authorizedPaymentJson()), MediaType.APPLICATION_JSON));

        service.reconcileCurrentSubscriptions();

        verify(subscriptionStateService).markReconciliationStarted("pre-1");
        verify(subscriptionStateService).applyPreapproval(eq("pre-1"), any());
        verify(subscriptionStateService).applyAuthorizedPayment(eq("9001"), any());
        verify(subscriptionStateService).markReconciliationSucceeded("pre-1");
        verify(subscriptionStateService, never()).markReconciliationFailed(anyString(), any());
        server.verify();
    }

    private MercadoPagoCheckoutStateService.CheckoutPreparation preparation() {
        return new MercadoPagoCheckoutStateService.CheckoutPreparation(
                false, null, 11L, 21L, "admin@taller.test", EXTERNAL_REFERENCE, "idem-1");
    }

    private String checkoutUrl(String id) {
        return "https://www.mercadopago.com.ar/subscriptions/checkout?preapproval_id=" + id;
    }

    private String preapprovalJson(String id, String status, String amount) {
        return """
                {
                  "id":"%s",
                  "status":"%s",
                  "init_point":"%s",
                  "external_reference":"%s",
                  "payer_id":555,
                  "collector_id":100200300,
                  "application_id":12345678,
                  "next_payment_date":"2026-09-14T01:00:00Z",
                  "auto_recurring":{
                    "transaction_amount":"%s",
                    "currency_id":"ARS"
                  }
                }
                """.formatted(id, status, checkoutUrl(id), EXTERNAL_REFERENCE, amount);
    }

    private String authorizedPaymentJson() {
        return authorizedPaymentJson("7001");
    }

    private String authorizedPaymentJson(String paymentId) {
        return """
                {
                  "id":9001,
                  "preapproval_id":"pre-1",
                  "external_reference":"ofx_123",
                  "currency_id":"ARS",
                  "transaction_amount":"24900.00",
                  "status":"processed",
                  "summarized":"approved",
                  "retry_attempt":0,
                  "debit_date":"2026-08-14T01:00:00Z",
                  "date_created":"2026-08-14T01:00:00Z",
                  "last_modified":"2026-08-14T01:01:00Z",
                  "payment":{
                    "id":"%s",
                    "status":"approved",
                    "status_detail":"accredited"
                  }
                }
                """.formatted(paymentId);
    }

    private String paymentJson(
            String id,
            Long collectorId,
            String amount,
            String status,
            String statusDetail,
            String refunded) {
        return """
                {
                  "id":"%s",
                  "status":"%s",
                  "status_detail":"%s",
                  "collector_id":%d,
                  "external_reference":"%s",
                  "currency_id":"ARS",
                  "transaction_amount":"%s",
                  "transaction_amount_refunded":"%s",
                  "date_last_updated":"2026-08-20T12:00:00Z"
                }
                """.formatted(id, status, statusDetail, collectorId, EXTERNAL_REFERENCE, amount, refunded);
    }
}
