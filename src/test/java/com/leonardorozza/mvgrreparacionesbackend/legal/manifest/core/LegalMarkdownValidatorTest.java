package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalMarkdownValidatorTest {

    private static final String LOCATION = "documents/terminos.md";

    private final LegalMarkdownValidator validator = new LegalMarkdownValidator();

    @Test
    void derivesTheFirstAtxH1WithoutRewritingTheDocument() {
        String markdown = "Introducción editorial.\n\n"
                + "#   Términos y condiciones de OrdenFix   \n\n"
                + "Texto definitivo.\n";

        LegalManifestValidation<LegalMarkdownValidator.ValidatedMarkdown> result =
                validator.validate(markdown, LOCATION);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.issues()).isEmpty();
        assertThat(result.value().orElseThrow().title())
                .isEqualTo("Términos y condiciones de OrdenFix");
    }

    @Test
    void acceptsPlainPunctuationAmpersandsAndInteriorNumberSigns() {
        LegalManifestValidation<?> result = validator.validate(
                "# C# y OrdenFix: derechos & obligaciones (2026)\n",
                LOCATION);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void acceptsTheThreeSpacesAllowedBeforeAnAtxHeading() {
        LegalManifestValidation<LegalMarkdownValidator.ValidatedMarkdown> result =
                validator.validate("   # Título indentado", LOCATION);

        assertThat(result.passed()).isTrue();
        assertThat(result.value().orElseThrow().title()).isEqualTo("Título indentado");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Texto sin encabezado",
            "## Sólo H2",
            "#",
            "# ",
            "#\t\t"
    })
    void blocksMissingOrEmptyAtxH1(String markdown) {
        assertBlocked(markdown, LegalManifestIssueCode.DOCUMENT_H1_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Título *enfatizado*",
            "Título _enfatizado_",
            "Título `con código`",
            "Título ~~tachado~~",
            "[Título](https://ordenfix.com.ar)",
            "![Logo](logo.png)",
            "Título <strong>HTML</strong>",
            "Título &amp; condiciones",
            "Título \\*escapado\\*",
            "Título #"
    })
    void blocksEveryInlineMarkupFormInTheDerivedTitle(String title) {
        assertBlocked(
                "# " + title + "\n",
                LegalManifestIssueCode.DOCUMENT_H1_PLAIN_TEXT_REQUIRED);
    }

    @Test
    void validatesTheFirstH1InsteadOfSkippingItForALaterCleanH1() {
        LegalManifestValidation<?> result = validator.validate(
                "# **Título preliminar**\n\n# Título posterior\n",
                LOCATION);

        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(LegalManifestIssueCode.DOCUMENT_H1_PLAIN_TEXT_REQUIRED);
    }

    @Test
    void doesNotSkipABareEmptyFirstAtxH1ForALaterCleanH1() {
        LegalManifestValidation<?> result = validator.validate(
                "#\n\n# Título posterior\n",
                LOCATION);

        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DOCUMENT_H1_REQUIRED);
    }

    @Test
    void ignoresH1LookingTextInsideFencedCodeBlocks() {
        String markdown = """
                ```markdown
                # No es un encabezado
                ```

                # Título contractual real
                """;

        LegalManifestValidation<LegalMarkdownValidator.ValidatedMarkdown> result =
                validator.validate(markdown, LOCATION);

        assertThat(result.passed()).isTrue();
        assertThat(result.value().orElseThrow().title())
                .isEqualTo("Título contractual real");
    }

    @Test
    void countsUnicodeCodePointsAtThePersistentTitleBoundary() {
        String exact = "😀".repeat(LegalMarkdownValidator.MAX_TITLE_CODE_POINTS);
        String tooLong = exact + "😀";

        assertThat(validator.validate("# " + exact, LOCATION).passed()).isTrue();
        assertBlocked(
                "# " + tooLong,
                LegalManifestIssueCode.DOCUMENT_H1_TOO_LONG);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<div>Contenido</div>",
            "<!-- comentario -->",
            "<!DOCTYPE html>",
            "<?instrucción?>",
            "<http://ordenfix.com.ar>",
            "<ftp://archivos.ordenfix.com.ar/legal>"
    })
    void blocksRawHtmlAndEveryNonWhitelistedAngleConstruct(String body) {
        assertBlocked(
                "# Título válido\n\n" + body,
                LegalManifestIssueCode.DOCUMENT_HTML_FORBIDDEN);
    }

    @Test
    void allowsOnlySafeHttpsAndMailtoAutolinksAsAngleConstructs() {
        String markdown = """
                # Aviso de privacidad

                Sitio: <https://ordenfix.com.ar/legal?documento=privacidad>.
                Contacto: <mailto:privacidad@ordenfix.com.ar>.
                """;

        assertThat(validator.validate(markdown, LOCATION).passed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[abrir](javascript:alert(1))",
            "[abrir](JaVaScRiPt&colon;alert(1))",
            "[abrir](javascript\\:alert(1))",
            "[abrir](java&#x09;scr&#105;pt&#58;alert(1))",
            "[abrir](java&#127;script&colon;alert(1))",
            "[abrir](vb&#115;cript&colon;msgbox(1))",
            "[abrir](da&NewLine;ta&colon;text/html;base64,AA==)",
            "[abrir](<javascript:alert(1)>)",
            "![imagen](data:image/svg+xml;base64,AA==)"
    })
    void blocksActiveInlineDestinationsAfterEntityDecoding(String link) {
        assertBlocked(
                "# Título válido\n\n" + link,
                LegalManifestIssueCode.DOCUMENT_ACTIVE_LINK_FORBIDDEN);
    }

    @Test
    void findsAnActiveDestinationBehindANestedLinkLabel() {
        for (String link : List.of(
                "[Ver [detalle]](javascript&colon;alert(1))",
                "[texto\nen dos líneas](javascript:alert(1))",
                "[texto `]`](javascript:alert(1))")) {
            assertBlocked(
                    "# Título válido\n\n" + link,
                    LegalManifestIssueCode.DOCUMENT_ACTIVE_LINK_FORBIDDEN);
        }
    }

    @Test
    void leavesMalformedLinkLookingTextInertUnderTheCommonmarkAst() {
        String nul = Character.toString(0);
        List<String> links = List.of(
                "[abrir](java\nscript&colon;alert(1))",
                "[abrir](java\tscript:alert(1))",
                "[abrir](ja" + nul + "vascript:alert(1))");

        for (String link : links) {
            assertThat(validator.validate("# Título válido\n\n" + link, LOCATION).passed())
                    .isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[acción][peligro]\n\n[peligro]: javascript&colon;alert(1)",
            "[acción][peligro]\n\n[peligro]: vb&#115;cript&#58;msgbox(1)",
            "[acción][peligro]\n\n[peligro]: da&tab;ta&colon;text/plain,contenido",
            "[acción][peligro]\n\n[peligro]:\njavascript\\:alert(1)",
            "[acción][peligro]\n\n[foo\\]]: javascript:alert(1)",
            "[acción][peligro]\n\n[foo\nbar]: data:text/html,contenido",
            "[acción][peligro]\n\n> [foo]: vbscript:msgbox(1)"
    })
    void blocksObfuscatedAndMultilineReferenceDestinations(String reference) {
        assertBlocked(
                "# Título válido\n\n" + reference,
                LegalManifestIssueCode.DOCUMENT_ACTIVE_LINK_FORBIDDEN);
    }

    @Test
    void doesNotTreatSchemeWordsOrSafeLinkDestinationsAsActive() {
        String markdown = """
                # Seguridad de enlaces

                La palabra javascript: puede citarse como texto.
                [Sitio](https://ordenfix.com.ar).
                [Correo](mailto:legal@ordenfix.com.ar).
                [Referencia][legal].

                [legal]: https://ordenfix.com.ar/terminos
                """;

        assertThat(validator.validate(markdown, LOCATION).passed()).isTrue();
    }

    @Test
    void doesNotTreatWhitespaceSeparatedParenthesesAsAnInlineLink() {
        String markdown = """
                # Seguridad de enlaces

                La notación [texto] (javascript: citado como ejemplo) no es un enlace Markdown.
                """;

        assertThat(validator.validate(markdown, LOCATION).passed()).isTrue();
    }

    @Test
    void ignoresHtmlAndLinkLookingTextInsideCodeNodes() {
        String markdown = """
                # Ejemplos técnicos

                `<div>` y `[x](javascript:ejemplo)` son texto inline.

                ```markdown
                <script>alert(1)</script>
                [x](data:text/html,ejemplo)
                ```

                Un signo menor suelto también es texto: 1 < 2.
                """;

        assertThat(validator.validate(markdown, LOCATION).passed()).isTrue();
    }

    @Test
    void accumulatesSortedDeterministicIssuesWithoutLeakingTheSource() {
        String source = "# [secreto](<javascript&colon;token-super-secreto>)\n";

        LegalManifestValidation<?> first = validator.validate(source, LOCATION);
        LegalManifestValidation<?> second = validator.validate(source, LOCATION);

        assertThat(first.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(first.issues()).isEqualTo(second.issues());
        assertThat(first.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(
                        LegalManifestIssueCode.DOCUMENT_ACTIVE_LINK_FORBIDDEN,
                        LegalManifestIssueCode.DOCUMENT_H1_PLAIN_TEXT_REQUIRED);
        assertThat(first.issues()).allSatisfy(issue -> {
            assertThat(issue.location()).isEqualTo(LOCATION);
            assertThat(issue.message()).doesNotContain("secreto", "token");
        });
    }

    @Test
    void validatesTheSafeRelativeLocationEvenOnPass() {
        assertThatThrownBy(() -> validator.validate("# Título válido", "/tmp/secreto.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("/tmp/secreto.md");
    }

    private void assertBlocked(String markdown, LegalManifestIssueCode expectedCode) {
        LegalManifestValidation<?> result = validator.validate(markdown, LOCATION);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(expectedCode);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::location)
                .containsOnly(LOCATION);
    }
}
