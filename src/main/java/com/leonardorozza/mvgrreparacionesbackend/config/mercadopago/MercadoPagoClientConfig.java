package com.leonardorozza.mvgrreparacionesbackend.config.mercadopago;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Cliente HTTP para la API de MercadoPago. El header de Authorization se setea
 * por request en el service (el token puede estar vacío cuando la integración
 * está deshabilitada).
 */
@Configuration
public class MercadoPagoClientConfig {

    @Bean
    public RestClient mercadoPagoRestClient(MercadoPagoProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.getReadTimeout());

        return RestClient.builder()
                .baseUrl(props.getApiUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
