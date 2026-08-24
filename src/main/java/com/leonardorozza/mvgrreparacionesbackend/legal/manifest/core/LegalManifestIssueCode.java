package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

/**
 * Códigos estables del núcleo de lectura y canonicalización del manifiesto.
 *
 * <p>Los mensajes son deliberadamente constantes: nunca incluyen bytes, valores del manifiesto,
 * rutas absolutas ni detalles de excepciones.</p>
 */
public enum LegalManifestIssueCode {
    MANIFEST_REQUIRED(
            LegalManifestStatus.BLOCKED,
            "Se requiere el contenido del manifiesto."),
    MANIFEST_SIZE_LIMIT_EXCEEDED(
            LegalManifestStatus.BLOCKED,
            "El manifiesto supera el límite permitido de 1 MiB."),
    MANIFEST_UTF8_INVALID(
            LegalManifestStatus.BLOCKED,
            "El manifiesto no contiene UTF-8 válido."),
    MANIFEST_BOM_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "El manifiesto no puede contener BOM."),
    MANIFEST_CR_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "El manifiesto debe usar saltos LF y no puede contener CR."),
    MANIFEST_NFC_REQUIRED(
            LegalManifestStatus.BLOCKED,
            "El manifiesto debe estar normalizado en Unicode NFC."),
    MANIFEST_SURROGATE_INVALID(
            LegalManifestStatus.BLOCKED,
            "El manifiesto contiene un surrogate Unicode inválido."),
    MANIFEST_UNICODE_NONCHARACTER_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "El manifiesto contiene un noncharacter Unicode no permitido."),
    MANIFEST_JSON_LIMIT_EXCEEDED(
            LegalManifestStatus.BLOCKED,
            "El JSON supera un límite operativo permitido."),
    MANIFEST_JSON_INVALID(
            LegalManifestStatus.BLOCKED,
            "El manifiesto no contiene un único documento JSON estricto válido."),
    MANIFEST_IJSON_NUMBER_INVALID(
            LegalManifestStatus.BLOCKED,
            "El manifiesto contiene un número no representable como IEEE-754 finito."),
    MANIFEST_SCHEMA_INVALID(
            LegalManifestStatus.BLOCKED,
            "El manifiesto no cumple el JSON Schema legal v1."),
    MANIFEST_LOCALE_UNSUPPORTED(
            LegalManifestStatus.BLOCKED,
            "El locale del manifiesto no está soportado por el modelo legal v1."),
    MANIFEST_RFC8785_INVALID(
            LegalManifestStatus.BLOCKED,
            "El manifiesto no puede representarse mediante RFC 8785."),
    MANIFEST_JSON_READER_ERROR(
            LegalManifestStatus.ERROR,
            "No se pudo completar la lectura segura del manifiesto."),
    MANIFEST_SCHEMA_UNAVAILABLE(
            LegalManifestStatus.ERROR,
            "El JSON Schema legal v1 no está disponible."),
    MANIFEST_MODEL_MAPPING_ERROR(
            LegalManifestStatus.ERROR,
            "El manifiesto validado no pudo mapearse al modelo legal v1."),
    MANIFEST_CANONICALIZATION_ERROR(
            LegalManifestStatus.ERROR,
            "No se pudo completar la canonicalización del manifiesto.");

    private final LegalManifestStatus severity;
    private final String safeMessage;

    LegalManifestIssueCode(LegalManifestStatus severity, String safeMessage) {
        this.severity = severity;
        this.safeMessage = safeMessage;
    }

    public LegalManifestStatus severity() {
        return severity;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
