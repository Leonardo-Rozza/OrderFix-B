package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoService;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitarios (sin Spring) de la validación de firma del webhook de MercadoPago.
 */
class MercadoPagoSignatureTests {

    private static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : h) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private MercadoPagoService service(String secret) {
        MercadoPagoProperties props = new MercadoPagoProperties();
        props.setWebhookSecret(secret);
        return new MercadoPagoService(props, null, null, null);
    }

    @Test
    void firmaCorrectaEsValida() throws Exception {
        String secret = "s3cret-key";
        String dataId = "abc123", reqId = "req-1", ts = "1700000000";
        String manifest = "id:" + dataId + ";request-id:" + reqId + ";ts:" + ts + ";";
        String v1 = hmac(secret, manifest);

        MercadoPagoService svc = service(secret);
        assertThat(svc.firmaWebhookValida(dataId, "ts=" + ts + ",v1=" + v1, reqId)).isTrue();
    }

    @Test
    void firmaIncorrectaEsRechazada() {
        MercadoPagoService svc = service("s3cret-key");
        assertThat(svc.firmaWebhookValida("abc123", "ts=1700000000,v1=deadbeef", "req-1")).isFalse();
    }

    @Test
    void firmaAusenteEsRechazadaCuandoHaySecreto() {
        MercadoPagoService svc = service("s3cret-key");
        assertThat(svc.firmaWebhookValida("abc123", null, "req-1")).isFalse();
    }

    @Test
    void sinSecretoConfiguradoNoSeValida() {
        MercadoPagoService svc = service(null); // webhookSecret no configurado
        assertThat(svc.firmaWebhookValida("abc123", "cualquier-cosa", "req-1")).isTrue();
    }
}
