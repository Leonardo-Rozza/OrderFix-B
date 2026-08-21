package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.PagoException;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoAuthorizedPaymentResponse;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoPaymentResponse;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.MercadoPagoPreapprovalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;

@Component
@RequiredArgsConstructor
public class MercadoPagoResponseValidator {

    private final MercadoPagoProperties props;

    public void validateCreatedPreapproval(
            MercadoPagoPreapprovalResponse response,
            String expectedExternalReference) {
        validatePreapproval(response, response == null ? null : response.id(), expectedExternalReference);
        if (response.initPoint() == null || response.initPoint().isBlank()) {
            throw invalidResponse();
        }
        URI initPoint;
        try {
            initPoint = URI.create(response.initPoint());
        } catch (IllegalArgumentException ex) {
            throw invalidResponse();
        }
        String host = initPoint.getHost();
        if (!"https".equalsIgnoreCase(initPoint.getScheme()) || host == null
                || !(host.equals("mercadopago.com.ar") || host.endsWith(".mercadopago.com.ar"))) {
            throw new PagoException("Mercado Pago devolvió una URL de checkout no confiable.");
        }
    }

    public void validatePreapproval(
            MercadoPagoPreapprovalResponse response,
            String expectedId,
            String expectedExternalReference) {
        if (response == null || blank(response.id()) || blank(response.status())
                || !response.id().equals(expectedId)
                || !expectedExternalReference.equals(response.externalReference())) {
            throw invalidResponse();
        }
        if (!props.getCollectorId().equals(response.collectorId())
                || !props.getApplicationId().equals(response.applicationId())) {
            throw new PagoException("La suscripción informada no pertenece a la cuenta configurada.");
        }
        MercadoPagoPreapprovalResponse.AutoRecurring recurring = response.autoRecurring();
        if (recurring == null) {
            throw invalidResponse();
        }
        validateMoney(recurring.transactionAmount(), recurring.currencyId());
    }

    public void validateAuthorizedPayment(
            MercadoPagoAuthorizedPaymentResponse response,
            String expectedId,
            String expectedPreapprovalId,
            String expectedExternalReference) {
        if (response == null || blank(response.id()) || !response.id().equals(expectedId)
                || !expectedPreapprovalId.equals(response.preapprovalId())
                || (response.debitDate() == null && response.dateCreated() == null)) {
            throw invalidResponse();
        }
        if (response.externalReference() != null
                && !expectedExternalReference.equals(response.externalReference())) {
            throw new PagoException("La factura informada no pertenece a la suscripción esperada.");
        }
        MercadoPagoAuthorizedPaymentResponse.Payment payment = response.payment();
        if (payment == null || blank(payment.id()) || blank(payment.status())) {
            throw invalidResponse();
        }
        validateMoney(response.transactionAmount(), response.currencyId());
    }

    /**
     * @return {@code false} solo cuando el recurso pertenece explícitamente a otro collector.
     * Las respuestas incompletas o inconsistentes fallan para que el inbox pueda reintentarlas.
     */
    public boolean validatePayment(
            MercadoPagoPaymentResponse response,
            String expectedId) {
        if (response == null || blank(response.id()) || blank(response.status())
                || !response.id().equals(expectedId) || response.collectorId() == null) {
            throw invalidResponse();
        }
        if (!props.getCollectorId().equals(response.collectorId())) {
            return false;
        }
        validateMoney(response.transactionAmount(), response.currencyId());
        BigDecimal refunded = response.transactionAmountRefunded();
        if (refunded != null && (refunded.signum() < 0
                || refunded.compareTo(response.transactionAmount()) > 0)) {
            throw invalidResponse();
        }
        return true;
    }

    public void validatePaymentInvoice(
            MercadoPagoPaymentResponse payment,
            MercadoPagoAuthorizedPaymentResponse invoice) {
        MercadoPagoAuthorizedPaymentResponse.Payment invoicePayment =
                invoice == null ? null : invoice.payment();
        if (invoice == null || blank(invoice.id()) || blank(invoice.preapprovalId())
                || invoicePayment == null || blank(invoicePayment.id())
                || !payment.id().equals(invoicePayment.id())
                || invoice.transactionAmount() == null
                || invoice.transactionAmount().compareTo(payment.transactionAmount()) != 0
                || blank(invoice.currencyId())
                || !invoice.currencyId().equalsIgnoreCase(payment.currencyId())) {
            throw new PagoException(
                    "La factura recurrente no coincide con el pago informado por Mercado Pago.");
        }
    }

    private void validateMoney(BigDecimal amount, String currency) {
        if (amount == null || amount.compareTo(props.getAmount()) != 0
                || currency == null || !props.getCurrency().equalsIgnoreCase(currency)) {
            throw new PagoException("Mercado Pago informó un importe o moneda inesperados.");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private PagoException invalidResponse() {
        return new PagoException("Respuesta inválida de Mercado Pago.");
    }
}
