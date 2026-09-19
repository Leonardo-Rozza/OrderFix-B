package com.leonardorozza.mvgrreparacionesbackend.service.email;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.HtmlUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AccountEmailTemplateTest {
    @ParameterizedTest
    @CsvSource({"verification,48,48 horas", "verification,1,1 hora", "reset,1,1 hora", "reset,2,2 horas"})
    void bothAlternativesDescribeTheSameActionDeadlineAndSingleUseLink(String kind, int hours, String deadline) {
        String route = kind.equals("verification") ? "/verificar-email" : "/reset-password";
        String link = "https://preview.invalid" + route + "?token=synthetic-token";
        var content = kind.equals("verification")
                ? AccountEmailTemplate.verification("María Pérez", link, hours)
                : AccountEmailTemplate.passwordReset("María Pérez", link, hours);
        String action = kind.equals("verification") ? "Confirmar mi email" : "Crear nueva contraseña";
        assertThat(content.plainText()).contains("María Pérez", action, link, "vence en " + deadline,
                "una sola vez", "No compartas este enlace.").doesNotContain("<a", "<html", "1 horas");
        assertThat(content.html()).contains("lang=\"es-AR\"", HtmlUtils.htmlEscape("María Pérez"), action,
                "href=\"" + link + "\"", "vence en " + deadline, "una sola vez")
                .doesNotContain("<script", "<img", "<form", "<iframe", "@import", "1 horas");
        assertThat(content.subject()).isEqualTo(kind.equals("verification")
                ? "Confirmá tu email de OrdenFix" : "Restablecer tu contraseña de OrdenFix");
        assertThat(content.toString()).doesNotContain("María", "synthetic-token", "preview.invalid");
        if (kind.equals("reset")) {
            assertThat(content.plainText()).contains("Si no pediste este cambio", "Tu contraseña sigue igual.");
            assertThat(content.html()).contains("Si no pediste este cambio", "Tu contraseña sigue igual.");
        } else {
            assertThat(content.plainText()).contains("ni te dieron acceso a un taller");
            assertThat(content.html()).doesNotContain("Creá tu taller", "Crear nueva contraseña");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void escapesDynamicHtmlWhileKeepingThePlainAlternativeLiteral(boolean verification) {
        String name = "<img src=x onerror=alert(1)> & \"María\"";
        String link = "https://preview.invalid/\"quoted\"?token=synthetic&other=1";
        var content = verification ? AccountEmailTemplate.verification(name, link, 48)
                : AccountEmailTemplate.passwordReset(name, link, 1);
        String escaped = HtmlUtils.htmlEscape(link);
        assertThat(content.html()).contains(HtmlUtils.htmlEscape(name),
                "href=\"" + escaped + "\"", ">" + escaped + "</a>").doesNotContain(name, link, "<img");
        assertThat(content.plainText()).contains(name, link).doesNotContain("&lt;img", "&amp;other");
    }
}
