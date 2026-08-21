package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoWebhookSignatureValidator {

    private final MercadoPagoProperties props;
    private final Clock clock;

    public boolean isValid(String dataId, String xSignature, String xRequestId) {
        String secret = props.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            if (!props.isEnabled()) {
                return true;
            }
            log.warn("Webhook MP rechazado: integración habilitada sin clave de firma.");
            return false;
        }
        if (xSignature == null || xSignature.isBlank()) {
            log.warn("Webhook MP rechazado: falta x-signature.");
            return false;
        }

        SignatureParts parts = parse(xSignature);
        if (parts == null || !timestampIsFresh(parts.timestamp())) {
            log.warn("Webhook MP rechazado: firma mal formada o fuera de la ventana temporal.");
            return false;
        }

        String manifest = manifest(dataId, xRequestId, parts.timestamp());
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.error("No se pudo validar la firma del webhook MP.", ex);
            return false;
        }

        for (String candidate : parts.signatures()) {
            try {
                byte[] received = HexFormat.of().parseHex(candidate);
                if (MessageDigest.isEqual(expected, received)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Se rechaza como firma inválida sin reflejar el valor recibido en logs.
            }
        }
        log.warn("Webhook MP rechazado: firma inválida.");
        return false;
    }

    private SignatureParts parse(String header) {
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] keyValue = part.split("=", 2);
            if (keyValue.length != 2) {
                continue;
            }
            String key = keyValue[0].trim();
            String value = keyValue[1].trim();
            if ("ts".equals(key)) {
                timestamp = value;
            } else if ("v1".equals(key) && !value.isBlank()) {
                signatures.add(value);
            }
        }
        return timestamp == null || signatures.isEmpty()
                ? null
                : new SignatureParts(timestamp, signatures);
    }

    private boolean timestampIsFresh(String rawTimestamp) {
        try {
            long value = Long.parseLong(rawTimestamp);
            Instant receivedAt = value >= 100_000_000_000L
                    ? Instant.ofEpochMilli(value)
                    : Instant.ofEpochSecond(value);
            Duration age = Duration.between(receivedAt, clock.instant()).abs();
            return age.compareTo(props.getWebhookTolerance()) <= 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String manifest(String dataId, String requestId, String timestamp) {
        StringBuilder manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            manifest.append("id:").append(dataId.toLowerCase(Locale.ROOT)).append(';');
        }
        if (requestId != null && !requestId.isBlank()) {
            manifest.append("request-id:").append(requestId).append(';');
        }
        return manifest.append("ts:").append(timestamp).append(';').toString();
    }

    private record SignatureParts(String timestamp, List<String> signatures) {
    }
}
