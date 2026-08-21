package com.leonardorozza.mvgrreparacionesbackend.config.mercadopago;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * Configuración de la integración con MercadoPago (suscripción PRO vía preapproval).
 * Si {@code enabled=false} no se llama a la API (dev/test sin Access Token).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mercadopago")
public class MercadoPagoProperties {

    /** Si está en false, no se llama a la API de MercadoPago. */
    private boolean enabled = false;

    /** Corte operativo de nuevos checkouts sin detener webhooks, bajas ni conciliación. */
    private boolean checkoutEnabled = false;

    /** Access Token de MercadoPago (TEST-... en pruebas, APP_USR-... en producción). */
    private String accessToken;

    /**
     * Clave secreta del webhook (panel MP → tu app → Webhooks → "Clave secreta").
     * Con la integración habilitada es obligatoria y se valida fail-closed.
     */
    private String webhookSecret;

    /** URL base de la API de MercadoPago. */
    private String apiUrl = "https://api.mercadopago.com";

    /** Texto que ve el usuario en el checkout. */
    private String reason = "OrdenFix PRO - Suscripción mensual";

    /** Monto mensual de la suscripción. */
    private BigDecimal amount = new BigDecimal("24900.00");

    /** Moneda (ISO): ARS, etc. */
    private String currency = "ARS";

    /** Página del frontend a la que vuelve el usuario tras pagar. */
    private String backUrl = "http://localhost:5173/suscripcion/resultado";

    /** Identificadores esperados de la cuenta y aplicación vendedora. */
    private Long collectorId;
    private Long applicationId;

    /** Límites de red y tolerancia anti-replay del webhook. */
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(8);
    private Duration webhookTolerance = Duration.ofMinutes(5);
    private Duration webhookProcessingTimeout = Duration.ofMinutes(1);
    private int webhookMaxAttempts = 8;

    /** Límite por ciclo para no concentrar toda la conciliación en una sola ejecución. */
    private int reconciliationBatchSize = 100;

    /** Máximo de páginas de facturas consultadas por suscripción y ciclo. */
    private int reconciliationMaxPaymentPages = 5;

    @PostConstruct
    void validateProductionConfiguration() {
        if (!enabled) {
            return;
        }
        requireText(accessToken, "MP_ACCESS_TOKEN");
        requireText(webhookSecret, "MP_WEBHOOK_SECRET");
        requireText(currency, "MP_CURRENCY");
        requireText(backUrl, "MP_BACK_URL");
        requireText(reason, "MP_REASON");
        requireText(apiUrl, "MP_API_URL");
        currency = currency.trim().toUpperCase(Locale.ROOT);
        reason = reason.trim();
        apiUrl = validatedApiUrl(apiUrl);
        backUrl = validatedBackUrl(backUrl);
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalStateException("MP_CURRENCY debe ser un código ISO de tres letras.");
        }
        if (collectorId == null || collectorId <= 0 || applicationId == null || applicationId <= 0) {
            throw new IllegalStateException(
                    "Mercado Pago habilitado requiere MP_COLLECTOR_ID y MP_APPLICATION_ID positivos.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalStateException("MP_AMOUNT debe ser mayor que cero.");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalStateException("Los timeouts de Mercado Pago deben ser positivos.");
        }
        if (webhookTolerance == null || webhookTolerance.isNegative() || webhookTolerance.isZero()) {
            throw new IllegalStateException("La tolerancia temporal del webhook debe ser positiva.");
        }
        if (webhookProcessingTimeout == null || webhookProcessingTimeout.isNegative()
                || webhookProcessingTimeout.isZero()) {
            throw new IllegalStateException("El timeout de procesamiento del webhook debe ser positivo.");
        }
        if (webhookMaxAttempts < 1 || webhookMaxAttempts > 50) {
            throw new IllegalStateException(
                    "mercadopago.webhook-max-attempts debe estar entre 1 y 50.");
        }
        if (reconciliationBatchSize < 1 || reconciliationBatchSize > 1_000) {
            throw new IllegalStateException(
                    "mercadopago.reconciliation-batch-size debe estar entre 1 y 1000.");
        }
        if (reconciliationMaxPaymentPages < 1 || reconciliationMaxPaymentPages > 20) {
            throw new IllegalStateException(
                    "mercadopago.reconciliation-max-payment-pages debe estar entre 1 y 20.");
        }
    }

    private void requireText(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Mercado Pago habilitado requiere " + variable + ".");
        }
    }

    private String validatedApiUrl(String value) {
        URI uri = parseUri(value, "MP_API_URL");
        String host = uri.getHost();
        String path = uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !"api.mercadopago.com".equalsIgnoreCase(host)
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (path != null && !path.isBlank() && !"/".equals(path))) {
            throw new IllegalStateException(
                    "MP_API_URL debe ser el endpoint HTTPS oficial de Mercado Pago.");
        }
        return "https://api.mercadopago.com";
    }

    private String validatedBackUrl(String value) {
        URI uri = parseUri(value, "MP_BACK_URL");
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalStateException("MP_BACK_URL debe ser una URL HTTPS válida.");
        }
        return uri.toString();
    }

    private URI parseUri(String value, String variable) {
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(variable + " no es una URL válida.", ex);
        }
    }
}
