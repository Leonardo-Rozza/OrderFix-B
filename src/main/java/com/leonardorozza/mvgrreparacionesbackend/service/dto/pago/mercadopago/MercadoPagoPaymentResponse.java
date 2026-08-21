package com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MercadoPagoPaymentResponse(
        String id,
        String status,
        @JsonProperty("status_detail") String statusDetail,
        @JsonProperty("collector_id") Long collectorId,
        @JsonProperty("external_reference") String externalReference,
        @JsonProperty("currency_id") String currencyId,
        @JsonProperty("transaction_amount") BigDecimal transactionAmount,
        @JsonProperty("transaction_amount_refunded") BigDecimal transactionAmountRefunded,
        @JsonProperty("date_last_updated") OffsetDateTime dateLastUpdated
) {
}
