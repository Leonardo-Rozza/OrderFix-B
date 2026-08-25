package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.LegalManifestSchema;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalCoverageMatrix.ScopeKey;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_ACTIVE_LINK_FORBIDDEN;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_CONTEXT_DUPLICATE;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_H1_PLAIN_TEXT_REQUIRED;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_H1_REQUIRED;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_H1_TOO_LONG;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_HTML_FORBIDDEN;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_LOCALE_MISMATCH;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_NOT_FOUND;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_NOT_REGULAR;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_PATH_ESCAPE;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_SOURCE_DUPLICATE;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_SOURCE_INVALID;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.DOCUMENT_SYMLINK_FORBIDDEN;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.LEGAL_EDITORIAL_MARKER_FOUND;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.LEGAL_TEXT_CR_FORBIDDEN;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.LEGAL_TEXT_DIGEST_MISMATCH;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.LEGAL_TEXT_NFC_REQUIRED;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.LEGAL_TEXT_UTF8_INVALID;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.MANIFEST_CONTACTS_INVALID;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.MANIFEST_DUPLICATE_DOCUMENT;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.MANIFEST_DUPLICATE_REQUIREMENT;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.MANIFEST_FILENAME_INVALID;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.MANIFEST_JSON_INVALID;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.MANIFEST_LOCALE_UNSUPPORTED;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.MANIFEST_NOT_REGULAR;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.MANIFEST_PATH_REQUIRED;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.MANIFEST_SCHEMA_UNAVAILABLE;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.PROFESSIONAL_REVIEW_REQUIRED;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.PUBLICATION_ID_DIRECTORY_MISMATCH;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.RELEASE_ROOT_INVALID;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.RELEASE_SYMLINK_FORBIDDEN;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.REQUIRED_DOCUMENT_MISSING;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.REQUIRED_DOCUMENT_UNBOUND;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.REQUIRED_REQUIREMENT_DOCUMENT_MISSING;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.REQUIRED_REQUIREMENT_MISSING;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.REQUIREMENT_CONTEXT_MISMATCH;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.REQUIREMENT_DOCUMENT_AMBIGUOUS;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.REQUIREMENT_DOCUMENT_DUPLICATE;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.REQUIREMENT_DOCUMENT_UNKNOWN;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode.REQUIREMENT_ROLE_DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backend half of the cross-repository legal contract handshake.
 *
 * <p>This test deliberately never locates or parses a sibling frontend checkout. The frontend
 * mirror must freeze the same schema fingerprint, catalogs, coverage rows, placeholders and issue
 * inventory. A contract change therefore requires coordinated edits in both repositories while
 * each suite remains hermetic in CI.</p>
 */
class LegalManifestFrontendParityTest {

    private static final int SHARED_SCHEMA_SIZE_BYTES = 10_547;
    private static final String SHARED_SCHEMA_SHA256 =
            "f7a4ee17f53f5ed3f2613d894fa3a4f46896dfaaec0c80dab055e4320f036f8b";

    private static final List<String> SHARED_PLACEHOLDERS = List.of(
            "[RAZÓN SOCIAL]",
            "[CUIT]",
            "[DOMICILIO]",
            "[EMAIL LEGAL]",
            "[EMAIL PRIVACIDAD]",
            "[JURISDICCIÓN]",
            "[HORARIO DE ATENCIÓN]"
    );

    private static final List<String> FRONTEND_LEGAL_ISSUE_CODES = List.of(
            "PLACEHOLDER_FOUND",
            "EDITORIAL_MARKER_FOUND",
            "DOCUMENT_UTF8_REQUIRED",
            "DOCUMENT_CANONICAL_TEXT_REQUIRED",
            "MANIFEST_SCHEMA_UNAVAILABLE",
            "MANIFEST_SCHEMA_INVALID",
            "MANIFEST_PUBLISHER_INVALID",
            "MANIFEST_CONTACTS_INVALID",
            "PROFESSIONAL_REVIEW_REQUIRED",
            "MANIFEST_DOCUMENTS_INVALID",
            "MANIFEST_DOCUMENT_INVALID",
            "MANIFEST_DUPLICATE_DOCUMENT",
            "MANIFEST_DUPLICATE_DOCUMENT_TYPE",
            "MANIFEST_DUPLICATE_DOCUMENT_SOURCE",
            "DOCUMENT_PATH_INVALID",
            "DOCUMENT_NOT_FOUND",
            "UNSAFE_MARKDOWN_FOUND",
            "MANIFEST_DIGEST_MISMATCH",
            "REQUIRED_DOCUMENT_MISSING",
            "MANIFEST_REQUIREMENTS_INVALID",
            "MANIFEST_REQUIREMENT_INVALID",
            "MANIFEST_DUPLICATE_REQUIREMENT",
            "REQUIREMENT_CANONICAL_TEXT_REQUIRED",
            "REQUIREMENT_DIGEST_MISMATCH",
            "REQUIREMENT_DOCUMENT_UNKNOWN",
            "REQUIREMENT_CONTEXT_MISMATCH",
            "REQUIRED_REQUIREMENT_MISSING",
            "REQUIRED_REQUIREMENT_DOCUMENT_MISSING",
            "REQUIRED_DOCUMENT_UNBOUND",
            "MANIFEST_NOT_CONFIGURED",
            "MANIFEST_PATH_INVALID",
            "MANIFEST_NOT_FOUND",
            "MANIFEST_JSON_INVALID",
            "MANIFEST_HEADER_INVALID",
            "MANIFEST_PUBLICATION_ID_MISMATCH"
    );

    /**
     * Blockers intentionally stronger than the current frontend parser. The broad frontend codes
     * remain mapped above; these names freeze the extra byte, resource and race guarantees that
     * must not disappear while preserving user-visible issue parity.
     */
    private static final Set<LegalManifestIssueCode> BACKEND_ADDITIONAL_GUARANTEE_CODES = Set.of(
            LegalManifestIssueCode.MANIFEST_SIZE_LIMIT_EXCEEDED,
            LegalManifestIssueCode.MANIFEST_UTF8_INVALID,
            LegalManifestIssueCode.MANIFEST_BOM_FORBIDDEN,
            LegalManifestIssueCode.MANIFEST_CR_FORBIDDEN,
            LegalManifestIssueCode.MANIFEST_NFC_REQUIRED,
            LegalManifestIssueCode.MANIFEST_SURROGATE_INVALID,
            LegalManifestIssueCode.MANIFEST_UNICODE_NONCHARACTER_FORBIDDEN,
            LegalManifestIssueCode.MANIFEST_JSON_LIMIT_EXCEEDED,
            LegalManifestIssueCode.MANIFEST_IJSON_NUMBER_INVALID,
            LegalManifestIssueCode.MANIFEST_RFC8785_INVALID,
            LegalManifestIssueCode.MANIFEST_FILE_CHANGED,
            LegalManifestIssueCode.DOCUMENT_FILE_CHANGED,
            LegalManifestIssueCode.DOCUMENT_SIZE_LIMIT_EXCEEDED,
            LegalManifestIssueCode.DOCUMENT_TOTAL_SIZE_LIMIT_EXCEEDED,
            LegalManifestIssueCode.LEGAL_TEXT_BOM_FORBIDDEN,
            LegalManifestIssueCode.LEGAL_TEXT_CONTROL_FORBIDDEN,
            LegalManifestIssueCode.LEGAL_TEXT_SURROGATE_INVALID,
            LegalManifestIssueCode.LEGAL_TEXT_UNICODE_NONCHARACTER_FORBIDDEN
    );

    private static final Map<String, IssueParity> ISSUE_PARITY = Map.ofEntries(
            entry("PLACEHOLDER_FOUND", mapped(LEGAL_PLACEHOLDER_FOUND)),
            entry("EDITORIAL_MARKER_FOUND", mapped(LEGAL_EDITORIAL_MARKER_FOUND)),
            entry("DOCUMENT_UTF8_REQUIRED", mapped(LEGAL_TEXT_UTF8_INVALID)),
            entry("DOCUMENT_CANONICAL_TEXT_REQUIRED",
                    split(LEGAL_TEXT_CR_FORBIDDEN, LEGAL_TEXT_NFC_REQUIRED)),
            entry("MANIFEST_SCHEMA_UNAVAILABLE", mapped(MANIFEST_SCHEMA_UNAVAILABLE)),
            entry("MANIFEST_SCHEMA_INVALID", mapped(MANIFEST_SCHEMA_INVALID)),
            entry("MANIFEST_PUBLISHER_INVALID", mapped(MANIFEST_SCHEMA_INVALID)),
            entry("MANIFEST_CONTACTS_INVALID", mapped(MANIFEST_CONTACTS_INVALID)),
            entry("PROFESSIONAL_REVIEW_REQUIRED", mapped(PROFESSIONAL_REVIEW_REQUIRED)),
            entry("MANIFEST_DOCUMENTS_INVALID", mapped(MANIFEST_SCHEMA_INVALID)),
            entry("MANIFEST_DOCUMENT_INVALID",
                    split(MANIFEST_SCHEMA_INVALID, DOCUMENT_LOCALE_MISMATCH,
                            DOCUMENT_CONTEXT_DUPLICATE)),
            entry("MANIFEST_DUPLICATE_DOCUMENT",
                    split(MANIFEST_DUPLICATE_DOCUMENT, REQUIREMENT_DOCUMENT_AMBIGUOUS)),
            entry("MANIFEST_DUPLICATE_DOCUMENT_TYPE", deliberate()),
            entry("MANIFEST_DUPLICATE_DOCUMENT_SOURCE", mapped(DOCUMENT_SOURCE_DUPLICATE)),
            entry("DOCUMENT_PATH_INVALID",
                    split(DOCUMENT_SOURCE_INVALID, DOCUMENT_NOT_REGULAR,
                            DOCUMENT_SYMLINK_FORBIDDEN, DOCUMENT_PATH_ESCAPE)),
            entry("DOCUMENT_NOT_FOUND", mapped(DOCUMENT_NOT_FOUND)),
            entry("UNSAFE_MARKDOWN_FOUND",
                    split(DOCUMENT_HTML_FORBIDDEN, DOCUMENT_ACTIVE_LINK_FORBIDDEN,
                            DOCUMENT_H1_REQUIRED, DOCUMENT_H1_PLAIN_TEXT_REQUIRED,
                            DOCUMENT_H1_TOO_LONG)),
            entry("MANIFEST_DIGEST_MISMATCH", mapped(LEGAL_TEXT_DIGEST_MISMATCH)),
            entry("REQUIRED_DOCUMENT_MISSING", mapped(REQUIRED_DOCUMENT_MISSING)),
            entry("MANIFEST_REQUIREMENTS_INVALID", mapped(MANIFEST_SCHEMA_INVALID)),
            entry("MANIFEST_REQUIREMENT_INVALID",
                    split(MANIFEST_SCHEMA_INVALID, REQUIREMENT_ROLE_DUPLICATE,
                            REQUIREMENT_DOCUMENT_DUPLICATE)),
            entry("MANIFEST_DUPLICATE_REQUIREMENT", mapped(MANIFEST_DUPLICATE_REQUIREMENT)),
            entry("REQUIREMENT_CANONICAL_TEXT_REQUIRED",
                    split(LEGAL_TEXT_CR_FORBIDDEN, LEGAL_TEXT_NFC_REQUIRED)),
            entry("REQUIREMENT_DIGEST_MISMATCH", mapped(LEGAL_TEXT_DIGEST_MISMATCH)),
            entry("REQUIREMENT_DOCUMENT_UNKNOWN", mapped(REQUIREMENT_DOCUMENT_UNKNOWN)),
            entry("REQUIREMENT_CONTEXT_MISMATCH", mapped(REQUIREMENT_CONTEXT_MISMATCH)),
            entry("REQUIRED_REQUIREMENT_MISSING", mapped(REQUIRED_REQUIREMENT_MISSING)),
            entry("REQUIRED_REQUIREMENT_DOCUMENT_MISSING",
                    mapped(REQUIRED_REQUIREMENT_DOCUMENT_MISSING)),
            entry("REQUIRED_DOCUMENT_UNBOUND",
                    deliberate(REQUIRED_DOCUMENT_UNBOUND)),
            entry("MANIFEST_NOT_CONFIGURED", adapted(MANIFEST_PATH_REQUIRED)),
            entry("MANIFEST_PATH_INVALID",
                    adapted(MANIFEST_FILENAME_INVALID, RELEASE_ROOT_INVALID,
                            RELEASE_SYMLINK_FORBIDDEN, MANIFEST_NOT_REGULAR)),
            entry("MANIFEST_NOT_FOUND", adapted(MANIFEST_NOT_REGULAR)),
            entry("MANIFEST_JSON_INVALID", mapped(MANIFEST_JSON_INVALID)),
            entry("MANIFEST_HEADER_INVALID",
                    split(MANIFEST_SCHEMA_INVALID, MANIFEST_LOCALE_UNSUPPORTED)),
            entry("MANIFEST_PUBLICATION_ID_MISMATCH",
                    mapped(PUBLICATION_ID_DIRECTORY_MISMATCH))
    );

    @Test
    void freezesTheSharedSchemaAndEnumCatalogsWithoutReadingAFrontendCheckout() {
        assertThat(LegalManifestSchema.EXPECTED_SIZE_BYTES).isEqualTo(SHARED_SCHEMA_SIZE_BYTES);
        assertThat(LegalManifestSchema.EXPECTED_SHA256).isEqualTo(SHARED_SCHEMA_SHA256);

        assertThat(TipoDocumentoLegal.values())
                .extracting(TipoDocumentoLegal::name)
                .containsExactly(
                        "TERMINOS_SERVICIO",
                        "POLITICA_PRIVACIDAD",
                        "ACUERDO_TRATAMIENTO_DATOS",
                        "CONDICIONES_PRO",
                        "POLITICA_CANCELACIONES_REEMBOLSOS",
                        "POLITICA_CIERRE_CUENTA",
                        "AVISO_CLIENTES_TALLER",
                        "TERMINOS_USUARIO",
                        "AVISO_PRIVACIDAD_USUARIO",
                        "COMPROMISO_CONFIDENCIALIDAD",
                        "ATESTACION_DATOS_CLIENTE"
                );
        assertThat(ContextoLegal.values())
                .extracting(ContextoLegal::name)
                .containsExactly(
                        "REGISTRO",
                        "PRIMER_INGRESO_EMPLEADO",
                        "USO_CONTINUADO",
                        "CONTRATACION_PRO",
                        "ATESTACION_FOTOS",
                        "ATESTACION_CREDENCIALES",
                        "CIERRE_CUENTA",
                        "ARREPENTIMIENTO"
                );
        assertThat(AudienciaLegal.values())
                .extracting(AudienciaLegal::name)
                .containsExactly("ADMIN_TITULAR", "USER");
        assertThat(TipoActoLegal.values())
                .extracting(TipoActoLegal::name)
                .containsExactly("ACEPTACION", "LECTURA", "DECLARACION");
        assertThat(LocaleLegal.values())
                .extracting(LocaleLegal::getCodigo)
                .containsExactly("es-AR");
    }

    @Test
    void freezesTheSixFrontendCoverageRowsExpandedToEightBackendScopes() {
        assertThat(LegalCoverageMatrix.documentTypes())
                .containsExactly(TipoDocumentoLegal.values());
        assertThat(LegalCoverageMatrix.requiredScopes())
                .extracting(LegalManifestFrontendParityTest::scopeName)
                .containsExactly(
                        "REGISTRO:ADMIN_TITULAR",
                        "PRIMER_INGRESO_EMPLEADO:USER",
                        "CONTRATACION_PRO:ADMIN_TITULAR",
                        "ATESTACION_FOTOS:ADMIN_TITULAR",
                        "ATESTACION_FOTOS:USER",
                        "ATESTACION_CREDENCIALES:ADMIN_TITULAR",
                        "ATESTACION_CREDENCIALES:USER",
                        "CIERRE_CUENTA:ADMIN_TITULAR"
                );

        assertRequiredTypes("REGISTRO:ADMIN_TITULAR",
                "TERMINOS_SERVICIO", "POLITICA_PRIVACIDAD",
                "ACUERDO_TRATAMIENTO_DATOS");
        assertRequiredTypes("PRIMER_INGRESO_EMPLEADO:USER",
                "TERMINOS_USUARIO", "AVISO_PRIVACIDAD_USUARIO",
                "COMPROMISO_CONFIDENCIALIDAD");
        assertRequiredTypes("CONTRATACION_PRO:ADMIN_TITULAR",
                "TERMINOS_SERVICIO", "CONDICIONES_PRO",
                "POLITICA_CANCELACIONES_REEMBOLSOS");
        assertRequiredTypes("ATESTACION_FOTOS:ADMIN_TITULAR",
                "AVISO_CLIENTES_TALLER", "ATESTACION_DATOS_CLIENTE");
        assertRequiredTypes("ATESTACION_FOTOS:USER",
                "AVISO_CLIENTES_TALLER", "ATESTACION_DATOS_CLIENTE");
        assertRequiredTypes("ATESTACION_CREDENCIALES:ADMIN_TITULAR",
                "AVISO_CLIENTES_TALLER", "ATESTACION_DATOS_CLIENTE");
        assertRequiredTypes("ATESTACION_CREDENCIALES:USER",
                "AVISO_CLIENTES_TALLER", "ATESTACION_DATOS_CLIENTE");
        assertRequiredTypes("CIERRE_CUENTA:ADMIN_TITULAR",
                "POLITICA_CIERRE_CUENTA");
    }

    @Test
    void freezesTheSevenSharedEditorialPlaceholders() {
        LegalEditorialMarkerValidator validator = new LegalEditorialMarkerValidator();

        for (String placeholder : SHARED_PLACEHOLDERS) {
            assertEditorialBlocked(validator, placeholder, LEGAL_PLACEHOLDER_FOUND);
        }

        for (String genericPlaceholder : List.of(
                "{{privacy_email}}",
                "<REPLACE_ME>",
                "TODO_LEGAL",
                "[REEMPLAZAR]",
                "[COMPLETAR]",
                "[PENDIENTE]"
        )) {
            assertEditorialBlocked(
                    validator,
                    genericPlaceholder,
                    LEGAL_PLACEHOLDER_FOUND);
        }
    }

    @Test
    void freezesTheSharedEditorialAndMarkdownBehaviorVectors() {
        LegalEditorialMarkerValidator editorial = new LegalEditorialMarkerValidator();
        for (String marker : List.of(
                "BORRADOR",
                "NO PUBLICAR",
                "NO COBRAR",
                "Versión no asignada",
                "Vigencia no definida",
                "Responsable a completar",
                "Prestador a completar",
                "Proveedor a completar",
                "Pendiente de revisión"
        )) {
            assertEditorialBlocked(editorial, marker, LEGAL_EDITORIAL_MARKER_FOUND);
        }
        assertEditorialBlocked(editorial, "TO\u200BDO_LEGAL", LEGAL_PLACEHOLDER_FOUND);
        assertEditorialBlocked(editorial, "NO\u2060 PUBLICAR", LEGAL_EDITORIAL_MARKER_FOUND);
        assertThat(editorial.validate(
                "[AAIP](https://www.argentina.gob.ar)",
                "parity").passed()).isTrue();

        LegalMarkdownValidator markdown = new LegalMarkdownValidator();
        assertThat(markdown.validate(
                "# Título\n\n<https://ordenfix.com> y `&lt;script&gt;`.\n",
                "parity").passed()).isTrue();
        assertMarkdownBlocked(markdown, "Texto sin título\n", DOCUMENT_H1_REQUIRED);
        assertMarkdownBlocked(markdown, "# \u200B\u2060\uFEFF\n", DOCUMENT_H1_REQUIRED);
        assertMarkdownBlocked(markdown, "# \uFE0F\u034F\u0301\n", DOCUMENT_H1_REQUIRED);
        assertMarkdownBlocked(markdown, "# **Título**\n", DOCUMENT_H1_PLAIN_TEXT_REQUIRED);
        assertMarkdownBlocked(markdown, "# " + "a".repeat(301) + "\n", DOCUMENT_H1_TOO_LONG);
        assertMarkdownBlocked(markdown, "# Título\n\n<div>HTML</div>\n", DOCUMENT_HTML_FORBIDDEN);
        assertMarkdownBlocked(
                markdown,
                "# Título\n\n[acción](java&#x73;cript:alert(1))\n",
                DOCUMENT_ACTIVE_LINK_FORBIDDEN);
    }

    @Test
    void accountsForAllThirtyFiveFrontendLegalIssueCodesAndOnlyTwoDeliberateDifferences() {
        assertThat(FRONTEND_LEGAL_ISSUE_CODES).hasSize(35).doesNotHaveDuplicates();
        assertThat(FRONTEND_LEGAL_ISSUE_CODES).doesNotContain("CONTACT_MISMATCH");
        assertThat(ISSUE_PARITY.keySet())
                .containsExactlyInAnyOrderElementsOf(FRONTEND_LEGAL_ISSUE_CODES);
        assertThat(ISSUE_PARITY.values())
                .allSatisfy(parity -> {
                    if (parity.disposition() != Disposition.DELIBERATE_DIFFERENCE) {
                        assertThat(parity.backendCodes()).isNotEmpty();
                    }
                });
        assertThat(ISSUE_PARITY.entrySet().stream()
                .filter(entry -> entry.getValue().disposition()
                        == Disposition.DELIBERATE_DIFFERENCE)
                .map(Map.Entry::getKey))
                .containsExactlyInAnyOrder(
                        "MANIFEST_DUPLICATE_DOCUMENT_TYPE",
                        "REQUIRED_DOCUMENT_UNBOUND"
                );
    }

    @Test
    void freezesTheDocumentedBackendOnlyHardeningAsBlockingGuarantees() {
        assertThat(BACKEND_ADDITIONAL_GUARANTEE_CODES).hasSize(18);
        assertThat(BACKEND_ADDITIONAL_GUARANTEE_CODES)
                .allSatisfy(code -> assertThat(code.severity())
                        .isEqualTo(LegalManifestStatus.BLOCKED));
    }

    private static void assertRequiredTypes(String scopeName, String... expectedTypes) {
        ScopeKey scope = LegalCoverageMatrix.requiredScopes().stream()
                .filter(candidate -> scopeName(candidate).equals(scopeName))
                .findFirst()
                .orElseThrow();
        assertThat(LegalCoverageMatrix.requiredDocumentTypes(scope))
                .extracting(TipoDocumentoLegal::name)
                .containsExactly(expectedTypes);
    }

    private static void assertEditorialBlocked(
            LegalEditorialMarkerValidator validator,
            String content,
            LegalManifestIssueCode expectedCode
    ) {
        assertThat(validator.validate(content, "parity").issues())
                .extracting(LegalManifestIssue::code)
                .contains(expectedCode);
    }

    private static void assertMarkdownBlocked(
            LegalMarkdownValidator validator,
            String content,
            LegalManifestIssueCode expectedCode
    ) {
        assertThat(validator.validate(content, "parity").issues())
                .extracting(LegalManifestIssue::code)
                .contains(expectedCode);
    }

    private static String scopeName(ScopeKey scope) {
        return scope.context().name() + ':' + scope.audience().name();
    }

    private static Map.Entry<String, IssueParity> entry(String code, IssueParity parity) {
        return Map.entry(code, parity);
    }

    private static IssueParity mapped(LegalManifestIssueCode... backendCodes) {
        return parity(Disposition.SHARED_RULE, backendCodes);
    }

    private static IssueParity split(LegalManifestIssueCode... backendCodes) {
        return parity(Disposition.BACKEND_SPLITS_FRONTEND_CODE, backendCodes);
    }

    private static IssueParity adapted(LegalManifestIssueCode... backendCodes) {
        return parity(Disposition.ENTRYPOINT_ADAPTATION, backendCodes);
    }

    private static IssueParity deliberate(LegalManifestIssueCode... backendCodes) {
        return parity(Disposition.DELIBERATE_DIFFERENCE, backendCodes);
    }

    private static IssueParity parity(
            Disposition disposition,
            LegalManifestIssueCode... backendCodes
    ) {
        return new IssueParity(disposition, Set.of(backendCodes));
    }

    private enum Disposition {
        SHARED_RULE,
        BACKEND_SPLITS_FRONTEND_CODE,
        ENTRYPOINT_ADAPTATION,
        /**
         * Exactly two approved differences: repeated document types are valid, and a document
         * referenced only by optional requirements is bound rather than orphaned.
         */
        DELIBERATE_DIFFERENCE
    }

    private record IssueParity(
            Disposition disposition,
            Set<LegalManifestIssueCode> backendCodes
    ) {
    }
}
