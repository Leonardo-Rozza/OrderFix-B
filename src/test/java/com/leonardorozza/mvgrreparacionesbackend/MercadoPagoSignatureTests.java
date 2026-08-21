package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.MercadoPagoWebhookSignatureValidator;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitarios (sin Spring) de la validación de firma del webhook de MercadoPago.
 */
class MercadoPagoSignatureTests {

    private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");

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

    private MercadoPagoWebhookSignatureValidator validator(String secret) {
        return validator(secret, false);
    }

    private MercadoPagoWebhookSignatureValidator validator(String secret, boolean enabled) {
        MercadoPagoProperties props = new MercadoPagoProperties();
        props.setWebhookSecret(secret);
        props.setEnabled(enabled);
        props.setWebhookTolerance(Duration.ofMinutes(5));
        return new MercadoPagoWebhookSignatureValidator(
                props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firmaCorrectaEsValida() throws Exception {
        String secret = "s3cret-key";
        String dataId = "AbC123", reqId = "req-1", ts = String.valueOf(NOW.getEpochSecond());
        String manifest = "id:" + dataId.toLowerCase() + ";request-id:" + reqId + ";ts:" + ts + ";";
        String v1 = hmac(secret, manifest);

        var signatureValidator = validator(secret);
        assertThat(signatureValidator.isValid(dataId, "ts=" + ts + ",v1=" + v1, reqId)).isTrue();
    }

    @Test
    void firmaIncorrectaEsRechazada() {
        var signatureValidator = validator("s3cret-key");
        String ts = String.valueOf(NOW.getEpochSecond());
        assertThat(signatureValidator.isValid("abc123", "ts=" + ts + ",v1=deadbeef", "req-1")).isFalse();
    }

    @Test
    void firmaAusenteEsRechazadaCuandoHaySecreto() {
        assertThat(validator("s3cret-key").isValid("abc123", null, "req-1")).isFalse();
    }

    @Test
    void sinSecretoConMpDeshabilitadoSeTolera() {
        // MP apagado: el webhook es un no-op, no hay nada que proteger
        assertThat(validator(null, false).isValid("abc123", "cualquier-cosa", "req-1")).isTrue();
    }

    @Test
    void sinSecretoConMpHabilitadoSeRechaza() {
        // Fail-closed: integración activa sin secreto no acepta webhooks
        assertThat(validator(null, true).isValid("abc123", "cualquier-cosa", "req-1")).isFalse();
    }

    @Test
    void firmaCorrectaPeroViejaEsRechazadaComoReplay() throws Exception {
        String secret = "s3cret-key";
        String ts = String.valueOf(NOW.minus(Duration.ofMinutes(6)).getEpochSecond());
        String manifest = "id:abc123;request-id:req-1;ts:" + ts + ";";
        String signature = hmac(secret, manifest);

        assertThat(validator(secret).isValid(
                "abc123", "ts=" + ts + ",v1=" + signature, "req-1")).isFalse();
    }

    @Test
    void aceptaTimestampEnMilisegundosDentroDeLaVentana() throws Exception {
        String secret = "s3cret-key";
        String ts = String.valueOf(NOW.toEpochMilli());
        String manifest = "id:abc123;request-id:req-1;ts:" + ts + ";";
        String signature = hmac(secret, manifest);

        assertThat(validator(secret).isValid(
                "abc123", "ts=" + ts + ",v1=" + signature, "req-1")).isTrue();
    }
}
