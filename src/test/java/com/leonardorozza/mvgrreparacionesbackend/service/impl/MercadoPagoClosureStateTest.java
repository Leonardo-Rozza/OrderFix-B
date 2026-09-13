package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.*;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.*;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.*;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.*;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Provider doubles: verifies preserving acknowledgements without treating closure as renewed access. */
class MercadoPagoClosureStateTest {
    private static final Instant NOW=Instant.parse("2026-09-12T12:00:00Z");
    private final SuscripcionRepository subscriptions=mock(SuscripcionRepository.class);
    private final SubscriptionProviderLinkRepository links=mock(SubscriptionProviderLinkRepository.class);
    private final SubscriptionPaymentRepository payments=mock(SubscriptionPaymentRepository.class);
    private final MercadoPagoResponseValidator validator=mock(MercadoPagoResponseValidator.class);
    private final WorkshopClosureGate gate=mock(WorkshopClosureGate.class);
    private final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    private final MercadoPagoCheckoutStateService checkout=new MercadoPagoCheckoutStateService(
            subscriptions,links,new MercadoPagoProperties(),clock,gate);
    private final MercadoPagoSubscriptionStateService observations=new MercadoPagoSubscriptionStateService(
            links,payments,subscriptions,validator,clock);

    @Test void blockedCheckoutDoesNotReadOrMutateSubscriptionState() {
        var denied=new WorkshopClosureBlockedException();
        doThrow(denied).when(gate).requireOperational(7L);
        assertThatThrownBy(()->checkout.prepare(7L)).isSameAs(denied);
        verifyNoInteractions(subscriptions,links,payments);
    }

    @Test void lateCheckoutAcknowledgementRetainsItsIdentityEvenAfterRestriction() {
        var link=link("RESTRINGIDO");
        when(links.findByIdForUpdate(11L)).thenReturn(Optional.of(link));
        checkout.complete(11L,preapproval("pending"));
        assertThat(link.getExternalSubscriptionId()).isEqualTo("pre-1");
        assertThat(link.getCheckoutUrl()).isEqualTo("https://provider.synthetic.invalid/checkout");
        assertThat(link.getSuscripcion().getMpPreapprovalId()).isEqualTo("pre-1");
        assertUnchangedAccess(link.getSuscripcion());
        verify(links).save(link); verify(subscriptions).save(link.getSuscripcion());
        // A delivery check is separate from the completed persistence transaction.
        verifyNoInteractions(gate);
    }

    @Test void acknowledgementForReplacedLinkCannotOverwriteTheCurrentProjection() {
        var link=link("ABIERTO");link.setCurrent(false);
        link.getSuscripcion().setMpPreapprovalId("current-preapproval");
        when(links.findByIdForUpdate(11L)).thenReturn(Optional.of(link));
        checkout.complete(11L,preapproval("pending"));
        assertThat(link.getExternalSubscriptionId()).isEqualTo("pre-1");
        assertThat(link.getSuscripcion().getMpPreapprovalId()).isEqualTo("current-preapproval");
        verifyNoInteractions(subscriptions);
    }

    @ParameterizedTest @NullSource @ValueSource(strings={"RESTRINGIDO","ELIMINADO","FUTURE"})
    void authorizedPreapprovalOnUnavailableWorkshopRetainsEvidenceWithoutEntitlement(String state) {
        var link=link(state);
        when(links.findMercadoPagoByExternalId("pre-1")).thenReturn(Optional.of(link));
        observations.applyPreapproval("pre-1",preapproval("authorized"));
        assertThat(link.getStatus()).isEqualTo("authorized");
        assertThat(link.getSuscripcion().getMpStatus()).isEqualTo("authorized");
        assertThat(link.getSuscripcion().getMpPayerId()).isEqualTo("payer-1");
        assertThat(link.getSuscripcion().getMpNextPaymentAt()).isEqualTo(NOW.plusSeconds(86400));
        assertUnchangedAccess(link.getSuscripcion());
        verifyNoInteractions(payments);
        verify(validator).validatePreapproval(any(),eq("pre-1"),eq("ofx_synthetic"));
    }

    @ParameterizedTest @ValueSource(strings={"RESTRINGIDO","ELIMINADO"})
    void approvedInvoiceOnClosedWorkshopIsRetainedWithoutChangingAccess(String state) {
        var link=link(state);link.setStatus("authorized");link.setExternalSubscriptionId("pre-1");
        when(links.findMercadoPagoByExternalId("pre-1")).thenReturn(Optional.of(link));
        var response=new MercadoPagoAuthorizedPaymentResponse("invoice-1","pre-1","ofx_synthetic","ARS",
                new BigDecimal("24900.00"),"processed","",0,NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC),
                new MercadoPagoAuthorizedPaymentResponse.Payment("payment-1","approved","accredited"));
        observations.applyAuthorizedPayment("invoice-1",response);
        verify(payments).save(argThat(payment->"invoice-1".equals(payment.getExternalAuthorizedPaymentId())
                && "approved".equals(payment.getPaymentStatus()) && "payment-1".equals(payment.getExternalPaymentId())));
        assertThat(link.getSuscripcion().getMpLastAuthorizedPaymentId()).isEqualTo("invoice-1");
        assertThat(link.getSuscripcion().getMpLastPaymentAt()).isEqualTo(NOW);
        assertUnchangedAccess(link.getSuscripcion());
    }

    @Test void remoteCancellationStillRecordsTheTerminalObservationWhenClosed() {
        var link=link("RESTRINGIDO");
        when(links.findMercadoPagoByExternalId("pre-1")).thenReturn(Optional.of(link));
        observations.applyPreapproval("pre-1",preapproval("canceled"));
        assertThat(link.getStatus()).isEqualTo("canceled");
        assertThat(link.getSuscripcion().getMpStatus()).isEqualTo("canceled");
        assertUnchangedAccess(link.getSuscripcion());
    }

    private static SubscriptionProviderLink link(String state) {
        Taller workshop=Taller.builder().id(7L).cierreEstado(state).activo(true).build();
        Suscripcion subscription=Suscripcion.builder().id(5L).taller(workshop)
                .plan(PlanType.FREE).estado(EstadoSuscripcion.TRIAL).build();
        return SubscriptionProviderLink.builder().id(11L).suscripcion(subscription).provider("MERCADO_PAGO")
                .externalReference("ofx_synthetic").idempotencyKey("fixture-key").status("creating")
                .current(true).createdAt(NOW).updatedAt(NOW).build();
    }
    private static MercadoPagoPreapprovalResponse preapproval(String state) {
        return new MercadoPagoPreapprovalResponse("pre-1",state,"https://provider.synthetic.invalid/checkout",
                "ofx_synthetic","payer-1",1L,2L,NOW.plusSeconds(86400).atOffset(ZoneOffset.UTC),null);
    }
    private static void assertUnchangedAccess(Suscripcion subscription) {
        assertThat(subscription.getPlan()).isEqualTo(PlanType.FREE);
        assertThat(subscription.getEstado()).isEqualTo(EstadoSuscripcion.TRIAL);
        assertThat(subscription.getFechaInicio()).isNull();
    }
}
