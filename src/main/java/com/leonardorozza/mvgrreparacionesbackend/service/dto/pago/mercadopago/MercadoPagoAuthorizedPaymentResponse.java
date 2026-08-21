package com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MercadoPagoAuthorizedPaymentResponse(
        String id,
        @JsonProperty("preapproval_id") String preapprovalId,
        @JsonProperty("external_reference") String externalReference,
        @JsonProperty("currency_id") String currencyId,
        @JsonProperty("transaction_amount") BigDecimal transactionAmount,
        String status,
        String summarized,
        @JsonProperty("retry_attempt") Integer retryAttempt,
        @JsonProperty("debit_date") OffsetDateTime debitDate,
        @JsonProperty("date_created") OffsetDateTime dateCreated,
        @JsonProperty("last_modified") OffsetDateTime lastModified,
        Payment payment
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(
            String id,
            String status,
            @JsonProperty("status_detail") String statusDetail
    ) {
    }
}
