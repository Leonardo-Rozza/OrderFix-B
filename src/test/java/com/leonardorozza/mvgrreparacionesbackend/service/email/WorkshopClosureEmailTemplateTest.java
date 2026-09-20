package com.leonardorozza.mvgrreparacionesbackend.service.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class WorkshopClosureEmailTemplateTest {
    private static final String ACCOUNT_URL = "https://preview.invalid/cuenta";
    private static final Instant CONFIRMED = Instant.parse("2026-09-19T02:30:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-09-26T02:30:00Z");

    @Test void closedDescribesRecordedDatesWithoutPromisingCurrentGraceOrCompleteDeletion() {
        var content = WorkshopClosureEmailTemplate.closed(CONFIRMED, DEADLINE, ACCOUNT_URL);
        assertThat(content.subject()).isEqualTo("Cierre de taller registrado en OrdenFix");
        for (String alternative : List.of(content.plainText(), content.html())) {
            assertThat(alternative)
                    .contains("El cierre de tu taller se registró el 18 de septiembre de 2026, 23:30 h (hora de Argentina).",
                            "Fecha límite registrada para solicitar la restauración: 25 de septiembre de 2026, 23:30 h (hora de Argentina).",
                            "El cierre restringió el acceso al taller.", "No confirma el borrado total de sus datos.",
                            "Este correo no renueva ni extiende el plazo registrado.")
                    .doesNotContain("todavía podés", "tenés 7 días", "vence en", "se eliminarán", "borrado completo");
        }
    }

    @Test void restoredDescribesAHistoricalRestorationWithoutReactivatingSubscriptions() {
        var content = WorkshopClosureEmailTemplate.restored(Instant.parse("2026-03-01T02:45:00Z"), ACCOUNT_URL);
        assertThat(content.subject()).isEqualTo("Restauración de taller registrada en OrdenFix");
        for (String alternative : List.of(content.plainText(), content.html())) {
            assertThat(alternative)
                    .contains("La restauración del acceso a tu taller se registró el 28 de febrero de 2026, 23:45 h (hora de Argentina).",
                            "El estado del taller puede haber cambiado después de esta operación.",
                            "Restaurar el acceso no reactiva suscripciones.")
                    .doesNotContain("tu cuenta está activa", "suscripción reactivada", "Mercado Pago", "fecha límite", "vence en");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bothAlternativesPointToTheSameAuthenticatedAccountWithoutExternalAssetsOrPersonalData(boolean closed) {
        var content = content(closed, ACCOUNT_URL);
        for (String alternative : List.of(content.plainText(), content.html())) {
            assertThat(alternative).contains("Hola,", "Consultá Cuenta para conocer el estado actual del taller.",
                    "Para acceder, iniciá sesión con tu cuenta de titular.", "Consultar Cuenta", ACCOUNT_URL)
                    .doesNotContain("?token=", "mailto:", "cobro", "pago aprobado", "factura");
        }
        assertThat(content.plainText()).doesNotContain("<html", "<table", "<a ");
        assertThat(content.html()).contains("lang=\"es-AR\"", "charset=\"UTF-8\"", "max-width:600px", "width=\"600\"",
                        "role=\"presentation\"", "Orden<span style=\"color:#0B8076;\">Fix</span>")
                .doesNotContain("<img", "<script", "<iframe", "<form", "@import", "url(", "src=");
        var links = Pattern.compile("href=\"([^\"]+)\"").matcher(content.html()).results()
                .map(match -> match.group(1)).toList();
        assertThat(links).containsExactly(ACCOUNT_URL, ACCOUNT_URL);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void escapesTheUrlInAttributesAndVisibleHtmlWithoutChangingThePlainAlternative(boolean closed) {
        // The adapter rejects this noncanonical destination; presentation still escapes every insertion.
        String link = "https://preview.invalid/cuenta?x=\"<tag>\"&y='value'";
        var content = content(closed, link);
        String escaped = HtmlUtils.htmlEscape(link);
        assertThat(content.html()).contains("href=\"" + escaped + "\"", ">" + escaped + "</a>")
                .doesNotContain(link, "<tag>");
        assertThat(content.plainText()).contains(link).doesNotContain("&amp;y");
    }

    @ParameterizedTest
    @CsvSource({"2026-01-02T01:05:00Z,1 de enero de 2026,22:05",
            "2026-07-02T01:05:00Z,1 de julio de 2026,22:05"})
    void datesUseSpanishAndBuenosAiresAcrossCalendarBoundaries(String instant, String date, String time) {
        var content = WorkshopClosureEmailTemplate.restored(Instant.parse(instant), ACCOUNT_URL);
        String expected = date + ", " + time + " h (hora de Argentina)";
        assertThat(content.plainText()).contains(expected);
        assertThat(content.html()).contains(expected);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void contentDoesNotExposeDeliveryDetailsThroughToString(boolean closed) {
        var content = content(closed, ACCOUNT_URL);
        assertThat(content.toString()).isEqualTo("AccountEmailContent[redacted]")
                .doesNotContain(content.subject(), "preview.invalid", "septiembre", "Hola");
    }

    private static AccountEmailTemplate.Content content(boolean closed, String url) {
        return closed ? WorkshopClosureEmailTemplate.closed(CONFIRMED, DEADLINE, url)
                : WorkshopClosureEmailTemplate.restored(CONFIRMED, url);
    }
}
