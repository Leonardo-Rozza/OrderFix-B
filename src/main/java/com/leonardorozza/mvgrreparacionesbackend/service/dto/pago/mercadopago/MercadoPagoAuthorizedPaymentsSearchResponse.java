package com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MercadoPagoAuthorizedPaymentsSearchResponse(
        Paging paging,
        List<MercadoPagoAuthorizedPaymentResponse> results
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Paging(Integer offset, Integer limit, Integer total) {
    }
}
