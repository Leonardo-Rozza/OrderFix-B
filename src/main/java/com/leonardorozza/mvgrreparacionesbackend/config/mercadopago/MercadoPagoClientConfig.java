package com.leonardorozza.mvgrreparacionesbackend.config.mercadopago;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP para la API de MercadoPago. El header de Authorization se setea
 * por request en el service (el token puede estar vacío cuando la integración
 * está deshabilitada).
 */
@Configuration
public class MercadoPagoClientConfig {

    @Bean
    public RestClient mercadoPagoRestClient(MercadoPagoProperties props) {
        return RestClient.builder()
                .baseUrl(props.getApiUrl())
                .build();
    }
}
