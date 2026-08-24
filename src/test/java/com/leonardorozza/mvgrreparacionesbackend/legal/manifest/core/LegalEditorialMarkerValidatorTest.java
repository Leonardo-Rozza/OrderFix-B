package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialMarkerValidatorTest {

    private static final String LOCATION = "documents/privacidad.md";

    private final LegalEditorialMarkerValidator validator =
            new LegalEditorialMarkerValidator();

    @ParameterizedTest
    @MethodSource("knownPlaceholders")
    void blocksTheSevenKnownFrontendPlaceholders(String placeholder) {
        assertBlocked(
                "Texto anterior " + placeholder + " texto posterior.",
                LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
    }

    @Test
    void findsKnownPlaceholdersAcrossTypographicUnicodeVariants() {
        String nfd = Normalizer.normalize("[RAZÓN SOCIAL]", Normalizer.Form.NFD);
        String narrowSpaces = "[HORARIO\u202FDE\u00A0ATENCIÓN]";
        String fullWidth = "［JURISDICCIÓN］";

        for (String placeholder : new String[]{nfd, narrowSpaces, fullWidth}) {
            assertBlocked(placeholder, LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[CU**IT**]",
            "&#91;CUIT&#93;"
    })
    void findsKnownPlaceholdersInTheVisibleCommonmarkText(String placeholder) {
        assertBlocked(placeholder, LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{{legalName}}",
            "{{ dato pendiente\nen otra línea }}",
            "<REPLACE_ME>",
            "<replace_me>",
            "TODO_LEGAL",
            "todo_legal"
    })
    void blocksTemplatePlaceholders(String placeholder) {
        assertBlocked(placeholder, LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[RAZON PENDIENTE]",
            "[CUIT DEL PRESTADOR]",
            "[DOMICILIO A DEFINIR]",
            "[EMAIL SOPORTE]",
            "[JURISDICCION APLICABLE]",
            "[HORARIO]",
            "[REEMPLAZAR ESTO]",
            "[COMPLETAR]",
            "[PENDIENTE DE LEGALES]",
            "[razón pendiente]"
    })
    void blocksEveryGenericPendingBracketFamily(String placeholder) {
        assertBlocked(placeholder, LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[EMAIL](https://ordenfix.com.ar/contacto)",
            "[RAZON][contacto]\n\n[contacto]: https://ordenfix.com.ar",
            "[HORARIO][]\n\n[horario]: /atencion",
            "[EMAIL]\n\n[email]: mailto:legal@ordenfix.com.ar",
            "[EMAIL]: mailto:legal@ordenfix.com.ar",
            "[EMAIL][con\\]tacto]\n\n[con\\]tacto]: /contacto",
            "[EMAIL][con\ntacto]\n\n[con\ntacto]: /contacto",
            "![EMAIL](contacto.png)",
            "![RAZON][imagen]\n\n[imagen]: logo.png",
            "![PENDIENTE]\n\n[pendiente]: logo.png",
            "- [ ] Pendiente real de una tarea",
            "- [x] Revisión legal aprobada",
            "- [X] Revisión contable aprobada",
            "[Ver [EMAIL]](https://ordenfix.com.ar/contacto)"
    })
    void ignoresGenericBracketsInsideMarkdownLinksReferencesImagesAndCheckboxes(String content) {
        assertThat(validator.validate(content, LOCATION).passed()).isTrue();
    }

    @Test
    void doesNotLetEscapedMarkdownSyntaxHideAGenericPlaceholder() {
        assertBlocked(
                "\\[EMAIL](https://ordenfix.com.ar/contacto)",
                LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked(
                "\\![EMAIL]",
                LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
    }

    @Test
    void preservesFrontendParityByNotHidingAKnownPlaceholderInALinkLabel() {
        assertBlocked(
                "[CUIT](https://ordenfix.com.ar/datos)",
                LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
    }

    @Test
    void leavesAStandaloneGenericBracketBlockedWithoutAMatchingReferenceDefinition() {
        assertBlocked("[EMAIL]", LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked("![PENDIENTE]", LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked("[EMAIL](enlace-sin-cierre", LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked("[EMAIL]\n: no-es-una-definición", LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked("[COMPLETAR][missing]", LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked("[COMPLETAR][]", LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked("![COMPLETAR][missing]", LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked(
                "[RAZON][razon]\n\n[razón]: /contacto",
                LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked(
                "[EMAIL] : no-es-una-definición",
                LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked(
                "[texto](/ruta/[EMAIL]/contacto)",
                LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
    }

    @Test
    void keepsReferenceDefinitionDestinationsVisibleToPlaceholderDetection() {
        assertBlocked(
                "[contacto]: /ruta/[COMPLETAR]",
                LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "BORRADOR",
            "Este texto sigue en borrador.",
            "NO PUBLICAR",
            "no\u00A0publicar",
            "NO COBRAR",
            "Versión contractual no asignada",
            "VIGENCIA aún NO DEFINIDA",
            "Responsable legal a completar",
            "Prestador definitivo: A COMPLETAR",
            "Proveedor de soporte a completar",
            "PENDIENTE DE REVISIÓN",
            "pendiente de revision"
    })
    void blocksEveryCoordinatedEditorialMarker(String marker) {
        assertBlocked(marker, LegalManifestIssueCode.LEGAL_EDITORIAL_MARKER_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PENDIENTE DE **REVISIÓN**",
            "PENDIENTE DE\nREVISIÓN",
            "Vigencia contractual **no definida**"
    })
    void findsEditorialMarkersInTheVisibleCommonmarkText(String marker) {
        assertBlocked(marker, LegalManifestIssueCode.LEGAL_EDITORIAL_MARKER_FOUND);
    }

    @Test
    void removesInvisibleFormatCharactersOnlyForMarkerDetection() {
        assertBlocked("[CU\u200BIT]", LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked("TO\u200BDO_LEGAL", LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked("<REPLACE\u2060_ME>", LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertBlocked("BOR\u200BRADOR", LegalManifestIssueCode.LEGAL_EDITORIAL_MARKER_FOUND);
        assertBlocked("NO\u2060 PUBLICAR", LegalManifestIssueCode.LEGAL_EDITORIAL_MARKER_FOUND);

        String cleanEmoji = "Versión final para el taller 👩‍🔧.";
        LegalManifestValidation<LegalEditorialMarkerValidator.CleanLegalText> result =
                validator.validate(cleanEmoji, LOCATION);
        assertThat(result.passed()).isTrue();
        assertThat(result.value().orElseThrow().content()).isSameAs(cleanEmoji);
    }

    @Test
    void keepsTheFortyCharacterEditorialWindowOnASingleLine() {
        String atBoundary = "VERSIÓN " + "a".repeat(38) + " NO ASIGNADA";
        String outsideBoundary = "VERSIÓN " + "a".repeat(39) + " NO ASIGNADA";

        assertBlocked(atBoundary, LegalManifestIssueCode.LEGAL_EDITORIAL_MARKER_FOUND);
        assertThat(validator.validate(outsideBoundary, LOCATION).passed()).isTrue();
        assertThat(validator.validate("VERSIÓN\nNO ASIGNADA", LOCATION).passed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Versión 1.0 asignada y vigente.",
            "Vigencia definida desde el 24 de agosto de 2026.",
            "Responsable legal: OrdenFix Argentina SAS.",
            "Documento pendiente de pago, no de revisión.",
            "El proveedor puede completar el servicio.",
            "El documento preliminar fue reemplazado y esta versión es final."
    })
    void acceptsNearbyLegalLanguageOutsideTheExplicitMarkerSet(String content) {
        assertThat(validator.validate(content, LOCATION).passed()).isTrue();
    }

    @Test
    void returnsTheOriginalCleanContentWithoutNormalization() {
        String content = "Texto final con acentos y\nespaciado conservado.";

        LegalManifestValidation<LegalEditorialMarkerValidator.CleanLegalText> result =
                validator.validate(content, LOCATION);

        assertThat(result.passed()).isTrue();
        assertThat(result.value().orElseThrow().content()).isSameAs(content);
    }

    @Test
    void accumulatesBothStableIssuesInDeterministicOrderWithoutLeakingContent() {
        String content = "BORRADOR {{token-super-secreto}}";

        LegalManifestValidation<?> first = validator.validate(content, LOCATION);
        LegalManifestValidation<?> second = validator.validate(content, LOCATION);

        assertThat(first.issues()).isEqualTo(second.issues());
        assertThat(first.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(
                        LegalManifestIssueCode.LEGAL_EDITORIAL_MARKER_FOUND,
                        LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND);
        assertThat(first.issues()).allSatisfy(issue -> {
            assertThat(issue.location()).isEqualTo(LOCATION);
            assertThat(issue.message()).doesNotContain("token", "secreto");
        });
    }

    @Test
    void validatesTheSafeRelativeLocationEvenOnPass() {
        assertThatThrownBy(() -> validator.validate("Texto final.", "../secreto.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("../secreto.md");
    }

    private void assertBlocked(String content, LegalManifestIssueCode expectedCode) {
        LegalManifestValidation<?> result = validator.validate(content, LOCATION);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .contains(expectedCode);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::location)
                .containsOnly(LOCATION);
    }

    private static Stream<String> knownPlaceholders() {
        return Stream.of(
                "[RAZÓN SOCIAL]",
                "[CUIT]",
                "[DOMICILIO]",
                "[EMAIL LEGAL]",
                "[EMAIL PRIVACIDAD]",
                "[JURISDICCIÓN]",
                "[HORARIO DE ATENCIÓN]");
    }
}
