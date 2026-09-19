package com.leonardorozza.mvgrreparacionesbackend.service.email;

import org.springframework.web.util.HtmlUtils;

/** Pure presentation shared by account emails; no token issuance, network or external assets. */
public final class AccountEmailTemplate {
    private AccountEmailTemplate() { }

    public static Content verification(String displayName, String link, int validityHours) {
        return render("Confirmá tu email de OrdenFix", "Confirmá tu email para empezar a usar OrdenFix.",
                "Confirmá tu email", displayName,
                "Tu cuenta de OrdenFix ya está creada. Confirmá este email para empezar a trabajar en tu taller.",
                "Confirmar mi email", link, validityHours,
                "Si no creaste una cuenta ni te dieron acceso a un taller en OrdenFix, podés ignorar este correo.");
    }

    public static Content passwordReset(String displayName, String link, int validityHours) {
        return render("Restablecer tu contraseña de OrdenFix", "Creá una nueva contraseña para tu cuenta de OrdenFix.",
                "Creá una nueva contraseña", displayName,
                "Recibimos un pedido para restablecer la contraseña de tu cuenta. Usá el botón para elegir una nueva.",
                "Crear nueva contraseña", link, validityHours,
                "Si no pediste este cambio, ignorá este correo. Tu contraseña sigue igual.");
    }

    private static Content render(String subject, String preheader, String title, String displayName,
                                  String introduction, String action, String link, int validityHours, String safety) {
        String expiration = "Este enlace vence en " + validityHours + (validityHours == 1 ? " hora" : " horas")
                + " y se puede usar una sola vez.";
        String greeting = "Hola " + displayName + ",";
        String footer = "Correo automático de tu cuenta. No compartas este enlace.";
        String plainText = """
                OrdenFix · Gestión de reparaciones

                %s

                %s

                %s

                %s:
                %s

                %s

                %s

                %s
                """.formatted(title, greeting, introduction, action, link, expiration, safety, footer);
        String escapedLink = HtmlUtils.htmlEscape(link);
        String html = """
                <!doctype html>
                <html lang="es-AR">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                </head>
                <body style="margin:0;padding:0;background-color:#EEF2F6;color:#0E1726;font-family:Arial,Helvetica,sans-serif;">
                  <div style="display:none;font-size:1px;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;mso-hide:all;">%s</div>
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="width:100%%;background-color:#EEF2F6;">
                    <tr><td align="center" style="padding:32px 12px;">
                      <!--[if mso]><table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0"><tr><td><![endif]-->
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="width:100%%;max-width:600px;">
                        <tr><td style="padding:0 8px 24px;font-size:24px;line-height:30px;font-weight:bold;letter-spacing:-0.6px;">Orden<span style="color:#0B8076;">Fix</span></td></tr>
                        <tr><td style="padding:28px 24px;background-color:#FFFFFF;border:1px solid #E3E8EF;border-top:4px solid #0D9488;border-radius:16px;">
                          <p style="margin:0 0 16px;font-size:12px;line-height:18px;font-weight:bold;letter-spacing:1.5px;color:#0B6F66;">TU CUENTA</p>
                          <h1 style="margin:0 0 24px;font-size:28px;line-height:35px;letter-spacing:-0.5px;color:#0E1726;">%s</h1>
                          <p style="margin:0 0 12px;font-size:16px;line-height:26px;overflow-wrap:anywhere;word-break:break-word;">%s</p>
                          <p style="margin:0 0 24px;font-size:16px;line-height:26px;color:#536175;">%s</p>
                          <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 24px;">
                            <tr><td align="center" bgcolor="#0B8076" style="background-color:#0B8076;border-radius:10px;mso-padding-alt:15px 22px;">
                              <a href="%s" style="display:inline-block;padding:15px 22px;border:1px solid #0B8076;border-radius:10px;font-size:16px;line-height:22px;font-weight:bold;color:#FFFFFF;text-decoration:none;text-underline-color:#0B8076;">%s</a>
                            </td></tr>
                          </table>
                          <p style="margin:0 0 24px;padding:14px 16px;border:1px solid #E3E8EF;border-radius:10px;background-color:#F6F8FB;font-size:14px;line-height:22px;color:#536175;">%s</p>
                          <p style="margin:0 0 24px;font-size:14px;line-height:22px;color:#536175;">%s</p>
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
                """.formatted(subject, preheader, title, HtmlUtils.htmlEscape(greeting), introduction,
                escapedLink, action, expiration, safety, escapedLink, escapedLink, footer);
        return new Content(subject, plainText, html);
    }

    public record Content(String subject, String plainText, String html) {
        @Override public String toString() { return "AccountEmailContent[redacted]"; }
    }
}
