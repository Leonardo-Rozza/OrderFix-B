package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

/**
 * Códigos estables del núcleo de validación legal del manifiesto.
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
            "No se pudo completar la canonicalización del manifiesto."),
    MANIFEST_VALIDATION_ERROR(
            LegalManifestStatus.ERROR,
            "No se pudo completar la validación integral del manifiesto."),
    CLI_ARGUMENTS_INVALID(
            LegalManifestStatus.BLOCKED,
            "Los argumentos del comando legal no son válidos."),
    CLI_OPERATION_FAILED(
            LegalManifestStatus.ERROR,
            "No se pudo completar el comando legal."),
    IMPORT_CLI_OPERATION_FAILED(
            LegalManifestStatus.ERROR,
            "No se pudo completar el comando de importación legal."),
    MANIFEST_PATH_REQUIRED(
            LegalManifestStatus.BLOCKED,
            "Se requiere la ruta local del manifiesto."),
    IMPORT_CONFIRMATION_MISMATCH(
            LegalManifestStatus.BLOCKED,
            "Las confirmaciones no coinciden con el release legal validado."),
    IMPORT_DISABLED(
            LegalManifestStatus.ERROR,
            "La importación legal no está habilitada en el entorno operativo."),
    IMPORT_DB_CONFIGURATION_INVALID(
            LegalManifestStatus.ERROR,
            "La configuración de base para la importación legal no es válida."),
    IMPORT_DATASOURCE_SYSTEM_PROPERTY_FORBIDDEN(
            LegalManifestStatus.ERROR,
            "La importación legal no admite propiedades JVM de datasource."),
    MANIFEST_FILENAME_INVALID(
            LegalManifestStatus.BLOCKED,
            "El manifiesto debe llamarse publication-manifest.json."),
    RELEASE_ROOT_INVALID(
            LegalManifestStatus.BLOCKED,
            "La raíz local de la publicación no es válida."),
    RELEASE_SYMLINK_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "La raíz de la publicación no puede ser un enlace simbólico."),
    MANIFEST_NOT_REGULAR(
            LegalManifestStatus.BLOCKED,
            "La ruta del manifiesto no identifica un archivo regular."),
    MANIFEST_FILE_CHANGED(
            LegalManifestStatus.BLOCKED,
            "El archivo del manifiesto cambió durante su lectura."),
    MANIFEST_READ_ERROR(
            LegalManifestStatus.ERROR,
            "No se pudo completar la lectura segura del archivo del manifiesto."),
    PUBLICATION_ID_DIRECTORY_MISMATCH(
            LegalManifestStatus.BLOCKED,
            "El identificador de publicación no coincide con su directorio."),
    DOCUMENT_SOURCE_INVALID(
            LegalManifestStatus.BLOCKED,
            "La ruta declarada para el documento legal no es válida."),
    DOCUMENT_SOURCE_DUPLICATE(
            LegalManifestStatus.BLOCKED,
            "Una fuente Markdown está declarada más de una vez."),
    DOCUMENT_NOT_FOUND(
            LegalManifestStatus.BLOCKED,
            "No se encontró el documento legal declarado."),
    DOCUMENT_NOT_REGULAR(
            LegalManifestStatus.BLOCKED,
            "La fuente declarada no identifica un archivo regular."),
    DOCUMENT_SYMLINK_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "La fuente legal no puede atravesar enlaces simbólicos."),
    DOCUMENT_PATH_ESCAPE(
            LegalManifestStatus.BLOCKED,
            "La fuente legal intenta salir de la raíz de publicación."),
    DOCUMENT_FILE_CHANGED(
            LegalManifestStatus.BLOCKED,
            "El documento legal cambió durante su lectura."),
    DOCUMENT_SIZE_LIMIT_EXCEEDED(
            LegalManifestStatus.BLOCKED,
            "Un documento legal supera el límite permitido de 1 MiB."),
    DOCUMENT_TOTAL_SIZE_LIMIT_EXCEEDED(
            LegalManifestStatus.BLOCKED,
            "Los documentos legales superan el límite total permitido de 16 MiB."),
    DOCUMENT_READ_ERROR(
            LegalManifestStatus.ERROR,
            "No se pudo completar la lectura segura de un documento legal."),
    LEGAL_TEXT_UTF8_INVALID(
            LegalManifestStatus.BLOCKED,
            "El texto legal no contiene UTF-8 válido."),
    LEGAL_TEXT_BOM_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "El texto legal no puede contener BOM."),
    LEGAL_TEXT_CR_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "El texto legal debe usar saltos LF y no puede contener CR."),
    LEGAL_TEXT_CONTROL_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "El texto legal contiene un carácter de control no permitido."),
    LEGAL_TEXT_NFC_REQUIRED(
            LegalManifestStatus.BLOCKED,
            "El texto legal debe estar normalizado en Unicode NFC."),
    LEGAL_TEXT_SURROGATE_INVALID(
            LegalManifestStatus.BLOCKED,
            "El texto legal contiene un surrogate Unicode inválido."),
    LEGAL_TEXT_UNICODE_NONCHARACTER_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "El texto legal contiene un noncharacter Unicode no permitido."),
    LEGAL_TEXT_DIGEST_MISMATCH(
            LegalManifestStatus.BLOCKED,
            "El SHA-256 del texto legal no coincide con el manifiesto."),
    LEGAL_TEXT_VALIDATION_ERROR(
            LegalManifestStatus.ERROR,
            "No se pudo completar la validación del texto legal."),
    DOCUMENT_H1_REQUIRED(
            LegalManifestStatus.BLOCKED,
            "El documento legal debe contener un encabezado H1 ATX."),
    DOCUMENT_H1_PLAIN_TEXT_REQUIRED(
            LegalManifestStatus.BLOCKED,
            "El encabezado H1 debe contener sólo texto plano."),
    DOCUMENT_H1_TOO_LONG(
            LegalManifestStatus.BLOCKED,
            "El encabezado H1 supera el límite de 300 caracteres."),
    DOCUMENT_HTML_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "El documento legal no puede contener HTML crudo."),
    DOCUMENT_ACTIVE_LINK_FORBIDDEN(
            LegalManifestStatus.BLOCKED,
            "El documento legal contiene un esquema de enlace activo no permitido."),
    LEGAL_PLACEHOLDER_FOUND(
            LegalManifestStatus.BLOCKED,
            "El contenido legal conserva un placeholder editorial."),
    LEGAL_EDITORIAL_MARKER_FOUND(
            LegalManifestStatus.BLOCKED,
            "El contenido legal conserva un marcador editorial no publicable."),
    PROFESSIONAL_REVIEW_REQUIRED(
            LegalManifestStatus.BLOCKED,
            "La publicación requiere revisión jurídica y contable aprobada."),
    MANIFEST_CONTACTS_INVALID(
            LegalManifestStatus.BLOCKED,
            "Los contactos de la publicación deben usar direcciones de correo públicas válidas."),
    MANIFEST_DUPLICATE_DOCUMENT(
            LegalManifestStatus.BLOCKED,
            "Una identidad documental está declarada más de una vez."),
    MANIFEST_DUPLICATE_REQUIREMENT(
            LegalManifestStatus.BLOCKED,
            "Una identidad de requisito está declarada más de una vez."),
    DOCUMENT_LOCALE_MISMATCH(
            LegalManifestStatus.BLOCKED,
            "El locale del documento no coincide con el de la publicación."),
    DOCUMENT_CONTEXT_DUPLICATE(
            LegalManifestStatus.BLOCKED,
            "Un documento declara el mismo contexto más de una vez."),
    REQUIREMENT_ROLE_DUPLICATE(
            LegalManifestStatus.BLOCKED,
            "Un requisito declara la misma audiencia más de una vez."),
    REQUIREMENT_DOCUMENT_DUPLICATE(
            LegalManifestStatus.BLOCKED,
            "Un requisito referencia el mismo documento más de una vez."),
    REQUIREMENT_DOCUMENT_UNKNOWN(
            LegalManifestStatus.BLOCKED,
            "Un requisito referencia un documento inexistente."),
    REQUIREMENT_DOCUMENT_AMBIGUOUS(
            LegalManifestStatus.BLOCKED,
            "Un requisito referencia una identidad documental ambigua."),
    REQUIREMENT_CONTEXT_MISMATCH(
            LegalManifestStatus.BLOCKED,
            "Un documento no cubre el contexto de su requisito."),
    REQUIRED_DOCUMENT_MISSING(
            LegalManifestStatus.BLOCKED,
            "Falta un tipo documental obligatorio para la publicación."),
    REQUIRED_DOCUMENT_UNBOUND(
            LegalManifestStatus.BLOCKED,
            "Un documento no está vinculado a ningún requisito."),
    REQUIRED_REQUIREMENT_MISSING(
            LegalManifestStatus.BLOCKED,
            "Falta un requisito obligatorio para un scope legal."),
    REQUIRED_REQUIREMENT_DOCUMENT_MISSING(
            LegalManifestStatus.BLOCKED,
            "Un scope obligatorio no cubre todos sus tipos documentales."),
    DB_PERSISTED_CONFLICT(
            LegalManifestStatus.BLOCKED,
            "El release entra en conflicto con una identidad legal ya persistida."),
    DB_CONSTRAINT(
            LegalManifestStatus.BLOCKED,
            "El grafo legal provisional no cumple una restricción de persistencia."),
    DB_ISOLATION(
            LegalManifestStatus.ERROR,
            "La base no permitió ejecutar el protocolo legal con aislamiento READ COMMITTED."),
    DB_LOCK_TIMEOUT(
            LegalManifestStatus.ERROR,
            "La simulación legal agotó el tiempo de espera de un lock."),
    DB_STATEMENT_TIMEOUT(
            LegalManifestStatus.ERROR,
            "La simulación legal agotó el tiempo máximo de una operación SQL."),
    DB_CONNECTION(
            LegalManifestStatus.ERROR,
            "No se pudo mantener una conexión válida con la base para la simulación legal."),
    DB_CONCURRENCY(
            LegalManifestStatus.ERROR,
            "La simulación legal no pudo completarse por un conflicto transaccional concurrente."),
    DB_SCHEMA_INCOMPATIBLE(
            LegalManifestStatus.ERROR,
            "La base no posee el schema legal V27 compatible requerido por el dry-run."),
    DB_OPERATION_FAILED(
            LegalManifestStatus.ERROR,
            "No se pudo completar la operación de base del dry-run legal."),
    IMPORT_DB_SCHEMA_INCOMPATIBLE(
            LegalManifestStatus.ERROR,
            "La base no posee el schema legal V27 exacto requerido por la importación."),
    IMPORT_DB_PRIVILEGES_INCOMPATIBLE(
            LegalManifestStatus.ERROR,
            "La credencial de importación no posee el perfil PostgreSQL mínimo requerido."),
    IMPORT_DB_PERSISTED_CONFLICT(
            LegalManifestStatus.BLOCKED,
            "El release entra en conflicto con una identidad legal ya persistida."),
    IMPORT_DB_CONSTRAINT(
            LegalManifestStatus.BLOCKED,
            "El grafo legal no cumple una restricción de persistencia."),
    IMPORT_DB_PUBLICATION_OPEN(
            LegalManifestStatus.ERROR,
            "La publicación persistida permanece abierta y requiere intervención manual."),
    IMPORT_DB_ISOLATION(
            LegalManifestStatus.ERROR,
            "La base no permitió importar con aislamiento READ COMMITTED."),
    IMPORT_DB_LOCK_TIMEOUT(
            LegalManifestStatus.ERROR,
            "La importación legal agotó el tiempo de espera de un lock."),
    IMPORT_DB_STATEMENT_TIMEOUT(
            LegalManifestStatus.ERROR,
            "La importación legal agotó el tiempo máximo de una operación SQL."),
    IMPORT_DB_CONNECTION(
            LegalManifestStatus.ERROR,
            "No se pudo mantener una conexión válida durante la importación legal."),
    IMPORT_DB_CONCURRENCY(
            LegalManifestStatus.ERROR,
            "La importación legal no pudo completarse por un conflicto transaccional concurrente."),
    IMPORT_DB_OPERATION_FAILED(
            LegalManifestStatus.ERROR,
            "No se pudo completar la operación de base de la importación legal."),
    IMPORT_DB_COMMIT_UNKNOWN(
            LegalManifestStatus.ERROR,
            "No se pudo determinar si la importación legal fue confirmada."),
    PUBLICATION_NOT_SEALED(
            LegalManifestStatus.BLOCKED,
            "La publicación legal objetivo no está sellada."),
    PUBLICATION_CONTENT_MISMATCH(
            LegalManifestStatus.BLOCKED,
            "La publicación legal persistida no coincide con el release confirmado."),
    EFFECTIVE_DATE_NOT_REACHED(
            LegalManifestStatus.BLOCKED,
            "Una versión legal todavía no alcanzó su fecha de vigencia."),
    CURRENT_STATE_MISMATCH(
            LegalManifestStatus.BLOCKED,
            "El estado editorial actual no coincide con el estado esperado."),
    SOURCE_FINGERPRINT_MISMATCH(
            LegalManifestStatus.BLOCKED,
            "El fingerprint editorial de origen no coincide con el confirmado."),
    INITIAL_PROJECTION_ALREADY_EXISTS(
            LegalManifestStatus.BLOCKED,
            "Ya existe una proyección o historia editorial que impide la primera promoción."),
    SCOPE_COVERAGE_INCOMPLETE(
            LegalManifestStatus.BLOCKED,
            "La cobertura editorial de scopes no está completa."),
    REPLACEMENT_MAPPING_INVALID(
            LegalManifestStatus.BLOCKED,
            "El mapeo editorial de reemplazo no es válido."),
    RETIREMENT_REASON_REQUIRED(
            LegalManifestStatus.BLOCKED,
            "El retiro editorial requiere un motivo no vacío."),
    EXPECTED_READINESS_MISMATCH(
            LegalManifestStatus.BLOCKED,
            "El readiness editorial esperado no coincide con la operación solicitada."),
    FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED(
            LegalManifestStatus.BLOCKED,
            "El hueco legal fail-closed no fue reconocido explícitamente."),
    REVISION_MISMATCH(
            LegalManifestStatus.BLOCKED,
            "La revisión legal persistida no coincide con la revisión esperada."),
    CONCURRENT_OPERATION(
            LegalManifestStatus.ERROR,
            "La operación editorial no pudo completarse por un conflicto concurrente."),
    ROLE_PRIVILEGE_DRIFT(
            LegalManifestStatus.ERROR,
            "La credencial editorial no posee el perfil PostgreSQL mínimo exacto requerido."),
    SCHEMA_DRIFT(
            LegalManifestStatus.ERROR,
            "La base no posee el schema legal V27 editorial exacto requerido."),
    POSTCONDITION_NOT_READY(
            LegalManifestStatus.ERROR,
            "La operación editorial no alcanzó el readiness requerido."),
    COMMIT_OUTCOME_UNKNOWN(
            LegalManifestStatus.ERROR,
            "No se pudo determinar si la operación editorial fue confirmada.");

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
