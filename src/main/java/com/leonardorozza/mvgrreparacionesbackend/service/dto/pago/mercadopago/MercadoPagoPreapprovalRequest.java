package com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record MercadoPagoPreapprovalRequest(
        String reason,
        @JsonProperty("auto_recurring") AutoRecurring autoRecurring,
        @JsonProperty("back_url") String backUrl,
        @JsonProperty("payer_email") String payerEmail,
        String status,
        @JsonProperty("external_reference") String externalReference
) {
    public record AutoRecurring(
            int frequency,
            @JsonProperty("frequency_type") String frequencyType,
            @JsonProperty("transaction_amount") BigDecimal transactionAmount,
            @JsonProperty("currency_id") String currencyId
    ) {
    }
}
