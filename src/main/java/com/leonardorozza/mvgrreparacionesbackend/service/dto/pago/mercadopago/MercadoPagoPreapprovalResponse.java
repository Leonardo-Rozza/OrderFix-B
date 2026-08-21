package com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MercadoPagoPreapprovalResponse(
        String id,
        String status,
        @JsonProperty("init_point") String initPoint,
        @JsonProperty("external_reference") String externalReference,
        @JsonProperty("payer_id") String payerId,
        @JsonProperty("collector_id") Long collectorId,
        @JsonProperty("application_id") Long applicationId,
        @JsonProperty("next_payment_date") OffsetDateTime nextPaymentDate,
        @JsonProperty("auto_recurring") AutoRecurring autoRecurring
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AutoRecurring(
            @JsonProperty("transaction_amount") BigDecimal transactionAmount,
            @JsonProperty("currency_id") String currencyId
    ) {
    }
}
