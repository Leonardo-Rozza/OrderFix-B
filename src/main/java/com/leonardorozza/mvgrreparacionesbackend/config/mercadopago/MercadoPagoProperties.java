package com.leonardorozza.mvgrreparacionesbackend.config.mercadopago;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** Access Token de MercadoPago (TEST-... en pruebas, APP_USR-... en producción). */
    private String accessToken;

    /**
     * Clave secreta del webhook (panel MP → tu app → Webhooks → "Clave secreta").
     * Si está vacía, no se valida la firma (cómodo en dev; en prod conviene setearla).
     */
    private String webhookSecret;

    /** URL base de la API de MercadoPago. */
    private String apiUrl = "https://api.mercadopago.com";

    /** Texto que ve el usuario en el checkout. */
    private String reason = "OrdenFix PRO - Suscripción mensual";

    /** Monto mensual de la suscripción. */
    private double amount = 4999.0;

    /** Moneda (ISO): ARS, etc. */
    private String currency = "ARS";

    /** Página del frontend a la que vuelve el usuario tras pagar. */
    private String backUrl = "http://localhost:5173/suscripcion/resultado";
}
