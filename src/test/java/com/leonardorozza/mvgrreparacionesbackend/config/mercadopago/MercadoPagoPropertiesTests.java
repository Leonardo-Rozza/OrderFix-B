package com.leonardorozza.mvgrreparacionesbackend.config.mercadopago;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MercadoPagoPropertiesTests {

    @Test
    void integracionActivaAceptaSoloEndpointsSegurosYNormalizaMoneda() {
        MercadoPagoProperties properties = validProperties();
        properties.setCurrency(" ars ");

        properties.validateProductionConfiguration();

        assertThat(properties.getCurrency()).isEqualTo("ARS");
        assertThat(properties.getApiUrl()).isEqualTo("https://api.mercadopago.com");
    }

    @Test
    void rechazaApiNoOficialParaNoFiltrarElAccessToken() {
        MercadoPagoProperties properties = validProperties();
        properties.setApiUrl("https://api.mercadopago.example");

        assertThatThrownBy(properties::validateProductionConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoint HTTPS oficial");
    }

    @Test
    void rechazaBackUrlSinHttps() {
        MercadoPagoProperties properties = validProperties();
        properties.setBackUrl("http://app.ordenfix.test/resultado");

        assertThatThrownBy(properties::validateProductionConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MP_BACK_URL");
    }

    private MercadoPagoProperties validProperties() {
        MercadoPagoProperties properties = new MercadoPagoProperties();
        properties.setEnabled(true);
        properties.setAccessToken("TEST-token");
        properties.setWebhookSecret("webhook-secret");
        properties.setCollectorId(100200300L);
        properties.setApplicationId(12345678L);
        properties.setBackUrl("https://app.ordenfix.test/suscripcion/resultado");
        return properties;
    }
}
