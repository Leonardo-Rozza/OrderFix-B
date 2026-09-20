package com.leonardorozza.mvgrreparacionesbackend.service.email;

import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Historical notices only; the adapter validates the static authenticated /cuenta destination. */
public final class WorkshopClosureEmailTemplate {
    private static final DateTimeFormatter EVENT_DATE = DateTimeFormatter
            .ofPattern("d 'de' MMMM 'de' uuuu, HH:mm 'h (hora de Argentina)'", Locale.forLanguageTag("es-AR"))
            .withZone(ZoneId.of("America/Argentina/Buenos_Aires"));

    private WorkshopClosureEmailTemplate() { }

    public static AccountEmailTemplate.Content closed(Instant confirmedAt, Instant reversibleUntil, String accountUrl) {
        return render("Cierre de taller registrado en OrdenFix", "Se registró el cierre del taller",
                "El cierre de tu taller se registró el " + EVENT_DATE.format(confirmedAt) + ".",
                "Fecha límite registrada para solicitar la restauración: " + EVENT_DATE.format(reversibleUntil) + ".",
                "El cierre restringió el acceso al taller. No confirma el borrado total de sus datos. "
                        + "Este correo no renueva ni extiende el plazo registrado.", accountUrl);
    }

    public static AccountEmailTemplate.Content restored(Instant occurredAt, String accountUrl) {
        return render("Restauración de taller registrada en OrdenFix", "Se registró la restauración del acceso",
                "La restauración del acceso a tu taller se registró el " + EVENT_DATE.format(occurredAt) + ".",
                "El estado del taller puede haber cambiado después de esta operación.",
                "Restaurar el acceso no reactiva suscripciones.", accountUrl);
    }

    private static AccountEmailTemplate.Content render(String subject, String title, String introduction,
                                                        String recordedDetail, String explanation, String accountUrl) {
        String currentState = "Consultá Cuenta para conocer el estado actual del taller. Para acceder, iniciá sesión con tu cuenta de titular.";
        String action = "Consultar Cuenta";
        String footer = "Correo automático de tu cuenta de OrdenFix.";
        String plainText = """
                OrdenFix · Gestión de reparaciones

                %s

                Hola,

                %s

                %s

                %s

                %s

                %s:
                %s

                %s
                """.formatted(title, introduction, recordedDetail, explanation, currentState, action, accountUrl, footer);
        String escapedLink = HtmlUtils.htmlEscape(accountUrl);
        String html = """
                <!doctype html>
                <html lang="es-AR">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                </head>
                <body style="margin:0;padding:0;background-color:#EEF2F6;color:#0E1726;font-family:Arial,Helvetica,sans-serif;">
                  <div style="display:none;font-size:1px;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;mso-hide:all;">%s. Consultá Cuenta para conocer el estado actual.</div>
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="width:100%%;background-color:#EEF2F6;">
                    <tr><td align="center" style="padding:32px 12px;">
                      <!--[if mso]><table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0"><tr><td><![endif]-->
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="width:100%%;max-width:600px;">
                        <tr><td style="padding:0 8px 24px;font-size:24px;line-height:30px;font-weight:bold;letter-spacing:-0.6px;">Orden<span style="color:#0B8076;">Fix</span></td></tr>
                        <tr><td style="padding:28px 24px;background-color:#FFFFFF;border:1px solid #E3E8EF;border-top:4px solid #0D9488;border-radius:16px;">
                          <p style="margin:0 0 16px;font-size:12px;line-height:18px;font-weight:bold;letter-spacing:1.5px;color:#0B6F66;">TU CUENTA</p>
                          <h1 style="margin:0 0 24px;font-size:28px;line-height:35px;letter-spacing:-0.5px;color:#0E1726;">%s</h1>
                          <p style="margin:0 0 12px;font-size:16px;line-height:26px;">Hola,</p>
                          <p style="margin:0 0 24px;font-size:16px;line-height:26px;color:#536175;">%s</p>
                          <p style="margin:0 0 24px;padding:14px 16px;border:1px solid #E3E8EF;border-radius:10px;background-color:#F6F8FB;font-size:14px;line-height:22px;color:#536175;">%s</p>
                          <p style="margin:0 0 24px;font-size:14px;line-height:22px;color:#536175;">%s</p>
                          <p style="margin:0 0 24px;font-size:14px;line-height:22px;color:#536175;">%s</p>
                          <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 24px;">
                            <tr><td align="center" bgcolor="#0B8076" style="background-color:#0B8076;border-radius:10px;mso-padding-alt:15px 22px;">
                              <a href="%s" style="display:inline-block;padding:15px 22px;border:1px solid #0B8076;border-radius:10px;font-size:16px;line-height:22px;font-weight:bold;color:#FFFFFF;text-decoration:none;text-underline-color:#0B8076;">%s</a>
                            </td></tr>
                          </table>
                          <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="width:100%%;table-layout:fixed;">
                            <tr><td style="padding-top:20px;border-top:1px solid #E3E8EF;">
                              <p style="margin:0 0 8px;font-size:13px;line-height:21px;color:#536175;">Si el botón no funciona, copiá y pegá este enlace en tu navegador:</p>
                              <a href="%s" style="font-size:12px;line-height:20px;color:#0B6F66;text-decoration:underline;overflow-wrap:anywhere;word-break:break-all;">%s</a>
                            </td></tr>
                          </table>
                        </td></tr>
                        <tr><td align="center" style="padding:24px 12px 0;">
                          <p style="margin:0 0 6px;font-size:13px;line-height:21px;font-weight:bold;color:#536175;">OrdenFix · Gestión de reparaciones</p>
                          <p style="margin:0;font-size:12px;line-height:20px;color:#536175;">%s</p>
                        </td></tr>
                      </table>
                      <!--[if mso]></td></tr></table><![endif]-->
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(subject, title, title, introduction, recordedDetail, explanation, currentState,
                escapedLink, action, escapedLink, escapedLink, footer);
        return new AccountEmailTemplate.Content(subject, plainText, html);
    }
}
