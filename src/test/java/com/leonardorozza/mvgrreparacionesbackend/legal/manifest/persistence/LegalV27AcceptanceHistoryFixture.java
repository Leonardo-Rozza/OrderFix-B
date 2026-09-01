package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Seeds genuine V27 acceptance history before V28 exists.
 *
 * <p>The fixture deliberately uses raw JDBC and schema-qualified object names. It refuses to
 * write unless the latest successful Flyway version in that schema is exactly V27, so a test
 * cannot manufacture {@code SCOPE_V1} history after the V28 guards are installed.</p>
 */
final class LegalV27AcceptanceHistoryFixture {

    private static final Pattern SAFE_SCHEMA = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final OffsetDateTime BASE_TIME =
            OffsetDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final String COMMON_REVISION = "sha256:" + "a".repeat(64);
    private static final String EMPTY_REVISION = "sha256:" + "0".repeat(64);

    private static final UUID PUBLICATION_ID = uuid("v27-history:publication");
    private static final UUID USAGE_DOCUMENT_LINE_ID = uuid("v27-history:document-line:usage");
    private static final UUID USAGE_DOCUMENT_ID = uuid("v27-history:document:usage");
    private static final UUID PHOTO_DOCUMENT_LINE_ID = uuid("v27-history:document-line:photo");
    private static final UUID PHOTO_DOCUMENT_ID = uuid("v27-history:document:photo");
    private static final UUID USAGE_REQUIREMENT_LINE_ID =
            uuid("v27-history:requirement-line:usage");
    private static final UUID USAGE_REQUIREMENT_ID = uuid("v27-history:requirement:usage");
    private static final UUID PHOTO_REQUIREMENT_LINE_ID =
            uuid("v27-history:requirement-line:photo");
    private static final UUID PHOTO_REQUIREMENT_ID = uuid("v27-history:requirement:photo");
    private static final UUID USAGE_SET_ID = uuid("v27-history:set:usage");
    private static final UUID PHOTO_SET_ID = uuid("v27-history:set:photo");
    private static final UUID EMPTY_SET_ID = uuid("v27-history:set:empty-closure");
    private static final UUID LEGACY_LOT_ID = uuid("v27-history:acceptance-lot");
    private static final UUID USAGE_ACCEPTANCE_ID = uuid("v27-history:acceptance:usage");
    private static final UUID PHOTO_ACCEPTANCE_ID = uuid("v27-history:acceptance:photo");

    private final DataSource dataSource;
    private final String schema;
    private final String quotedSchema;

    LegalV27AcceptanceHistoryFixture(DataSource dataSource, String schema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schema = requireSafeSchema(schema);
        this.quotedSchema = quoteIdentifier(this.schema);
    }

    SeededHistory seed() {
        assertCurrentFlywayVersionIsV27();

        inTransaction(this::insertOpenGraph);
        inTransaction(connection -> {
            update(connection, """
                    UPDATE %s
                       SET estado_construccion = 'SELLADO', sellado_en = ?
                     WHERE id = ?
                    """.formatted(table("legal_publicaciones")),
                    BASE_TIME.plusSeconds(1), PUBLICATION_ID);
            forceConstraints(connection);
        });
        transitionDocumentsToPublished();
        activateDocuments();
        transitionRequirementsToPublished();
        activateRequirementsAndPointers();

        Actor actor = insertActor();
        insertLegacyAcceptance(actor);
        return new SeededHistory(
                PUBLICATION_ID,
                actor.userId(),
                actor.workshopId(),
                LEGACY_LOT_ID,
                List.of(USAGE_ACCEPTANCE_ID, PHOTO_ACCEPTANCE_ID),
                Map.of(
                        "USO_CONTINUADO", USAGE_SET_ID,
                        "ATESTACION_FOTOS", PHOTO_SET_ID,
                        "CIERRE_CUENTA", EMPTY_SET_ID),
                Map.of(
                        "USO_CONTINUADO", USAGE_REQUIREMENT_ID,
                        "ATESTACION_FOTOS", PHOTO_REQUIREMENT_ID),
                Map.of(
                        "USO_CONTINUADO", USAGE_DOCUMENT_ID,
                        "ATESTACION_FOTOS", PHOTO_DOCUMENT_ID),
                COMMON_REVISION,
                EMPTY_REVISION);
    }

    HistorySnapshot capture(SeededHistory seeded) {
        Objects.requireNonNull(seeded, "seeded");
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            Map<String, TableState> tables = new LinkedHashMap<>();
            tables.put("legal_requisito_conjuntos", captureRows(connection, """
                    SELECT pg_catalog.encode(pg_catalog.convert_to(
                               pg_catalog.jsonb_build_array(
                                   c.id, c.publicacion_id, c.locale, c.contexto, c.audiencia,
                                   c.required_set_revision, c.creado_en)::text,
                               'UTF8'), 'hex'),
                           c.xmin::text
                      FROM %s c
                     WHERE c.publicacion_id = ?
                     ORDER BY c.contexto, c.id
                    """.formatted(table("legal_requisito_conjuntos")),
                    seeded.publicationId()));
            tables.put("legal_requisito_conjunto_miembros", captureRows(connection, """
                    SELECT pg_catalog.encode(pg_catalog.convert_to(
                               pg_catalog.jsonb_build_array(
                                   m.id, m.conjunto_id, m.publicacion_id,
                                   m.requisito_version_id, m.requisito_linea_id,
                                   m.manifest_ordinal)::text,
                               'UTF8'), 'hex'),
                           m.xmin::text
                      FROM %s m
                     WHERE m.publicacion_id = ?
                     ORDER BY m.id
                    """.formatted(table("legal_requisito_conjunto_miembros")),
                    seeded.publicationId()));
            tables.put("legal_requisito_conjuntos_actuales", captureRows(connection, """
                    SELECT pg_catalog.encode(pg_catalog.convert_to(
                               pg_catalog.jsonb_build_array(
                                   a.locale, a.contexto, a.audiencia, a.conjunto_id,
                                   a.publicacion_id, a.actualizado_en)::text,
                               'UTF8'), 'hex'),
                           a.xmin::text
                      FROM %s a
                     WHERE a.publicacion_id = ?
                     ORDER BY a.contexto
                    """.formatted(table("legal_requisito_conjuntos_actuales")),
                    seeded.publicationId()));
            tables.put("legal_aceptacion_lotes", captureRows(connection, """
                    SELECT pg_catalog.encode(pg_catalog.convert_to(
                               pg_catalog.jsonb_build_array(
                                   l.id, l.user_id, l.taller_id, l.rol_wire, l.audiencia,
                                   l.required_set_revision, l.aceptado_en)::text,
                               'UTF8'), 'hex'),
                           l.xmin::text
                      FROM %s l
                     WHERE l.id = ?
                    """.formatted(table("legal_aceptacion_lotes")), seeded.legacyLotId()));
            tables.put("legal_aceptaciones", captureRows(connection, """
                    SELECT pg_catalog.encode(pg_catalog.convert_to(
                               pg_catalog.jsonb_build_array(
                                   a.id, a.lote_id, a.user_id, a.taller_id,
                                   a.requisito_version_id, a.requisito_clave,
                                   a.requisito_version, a.contexto, a.tipo_acto,
                                   a.afirmacion, a.afirmacion_sha256, a.requerido)::text,
                               'UTF8'), 'hex'),
                           a.xmin::text
                      FROM %s a
                     WHERE a.lote_id = ?
                     ORDER BY a.id
                    """.formatted(table("legal_aceptaciones")), seeded.legacyLotId()));
            tables.put("legal_aceptacion_documentos", captureRows(connection, """
                    SELECT pg_catalog.encode(pg_catalog.convert_to(
                               pg_catalog.jsonb_build_array(
                                   d.id, d.aceptacion_id, d.documento_ordinal,
                                   d.documento_version_id, d.documento_clave, d.tipo,
                                   d.version, d.titulo, d.sha256)::text,
                               'UTF8'), 'hex'),
                           d.xmin::text
                      FROM %s d
                      JOIN %s a ON a.id = d.aceptacion_id
                     WHERE a.lote_id = ?
                     ORDER BY d.id
                    """.formatted(
                            table("legal_aceptacion_documentos"),
                            table("legal_aceptaciones")),
                    seeded.legacyLotId()));
            tables.put("legal_aceptacion_metadatos", captureRows(connection, """
                    SELECT pg_catalog.encode(pg_catalog.convert_to(
                               pg_catalog.jsonb_build_array(
                                   m.lote_id, m.capturado_en, m.retener_hasta,
                                   m.purgado_en)::text,
                               'UTF8'), 'hex'),
                           m.xmin::text
                      FROM %s m
                     WHERE m.lote_id = ?
                    """.formatted(table("legal_aceptacion_metadatos")),
                    seeded.legacyLotId()));
            tables.put("legal_aceptacion_metadatos_cifrados", captureRows(connection, """
                    SELECT pg_catalog.encode(pg_catalog.convert_to(
                               pg_catalog.jsonb_build_array(
                                   m.id, m.lote_id, m.tipo, m.key_version, m.nonce,
                                   m.ciphertext, m.tag, m.longitud_original,
                                   m.tombstone_en)::text,
                               'UTF8'), 'hex'),
                           m.xmin::text
                      FROM %s m
                     WHERE m.lote_id = ?
                     ORDER BY m.id
                    """.formatted(table("legal_aceptacion_metadatos_cifrados")),
                    seeded.legacyLotId()));
            tables.put("legal_idempotencia_resultados", captureRows(connection, """
                    SELECT pg_catalog.encode(pg_catalog.convert_to(
                               pg_catalog.jsonb_build_array(
                                   i.id, i.operacion, i.route_template, i.scope_hmac,
                                   i.idempotency_key_hmac, i.fingerprint_hmac,
                                   i.hmac_key_version, i.user_id, i.taller_id, i.lote_id,
                                   i.completed_at, i.expires_at)::text,
                               'UTF8'), 'hex'),
                           i.xmin::text
                      FROM %s i
                     WHERE i.lote_id = ?
                     ORDER BY i.id
                    """.formatted(table("legal_idempotencia_resultados")),
                    seeded.legacyLotId()));

            List<String> relationships = queryStrings(connection, """
                    SELECT relation
                      FROM (
                            SELECT 'ACT|' || a.id || '|' || a.lote_id || '|' || a.user_id
                                   || '|' || a.taller_id || '|' || a.requisito_version_id
                                   || '|' || a.contexto AS relation
                              FROM %s a
                             WHERE a.lote_id = ?
                            UNION ALL
                            SELECT 'DOC|' || d.aceptacion_id || '|' || d.documento_version_id
                                   || '|' || d.documento_ordinal
                              FROM %s d
                              JOIN %s a ON a.id = d.aceptacion_id
                             WHERE a.lote_id = ?
                            UNION ALL
                            SELECT 'CURRENT|' || p.contexto || '|' || p.conjunto_id
                                   || '|' || c.required_set_revision || '|members='
                                   || pg_catalog.count(m.id)
                              FROM %s p
                              JOIN %s c ON c.id = p.conjunto_id
                              LEFT JOIN %s m ON m.conjunto_id = p.conjunto_id
                             WHERE p.publicacion_id = ?
                             GROUP BY p.contexto, p.conjunto_id, c.required_set_revision
                      ) relations
                     ORDER BY relation
                    """.formatted(
                            table("legal_aceptaciones"),
                            table("legal_aceptacion_documentos"),
                            table("legal_aceptaciones"),
                            table("legal_requisito_conjuntos_actuales"),
                            table("legal_requisito_conjuntos"),
                            table("legal_requisito_conjunto_miembros")),
                    seeded.legacyLotId(), seeded.legacyLotId(), seeded.publicationId());
            return new HistorySnapshot(tables, relationships);
        } catch (SQLException error) {
            throw databaseFailure("capture V27 history", error);
        }
    }

    void assertCurrentFlywayVersionIsV27() {
        try (Connection connection = dataSource.getConnection()) {
            String current = queryString(connection, """
                    SELECT version
                      FROM %s
                     WHERE success AND version IS NOT NULL
                     ORDER BY installed_rank DESC
                     LIMIT 1
                    """.formatted(table("flyway_schema_history")));
            if (!"27".equals(current)) {
                throw new IllegalStateException(
                        "Legal V27 history fixture requires Flyway current exactly 27 in schema "
                                + schema + "; observed " + current);
            }
        } catch (SQLException error) {
            throw databaseFailure("verify Flyway V27 precondition", error);
        }
    }

    private void insertOpenGraph(Connection connection) throws SQLException {
        assertCurrentFlywayVersionIsV27(connection);
        update(connection, """
                INSERT INTO %s
                    (id, publication_external_id, schema_version, locale,
                     manifest_sha256, manifest_canonico, razon_social, cuit,
                     domicilio_legal, jurisdiccion, horario_atencion,
                     email_legal, email_privacidad, email_soporte,
                     revision_legal_estado, revision_contable_estado, importado_en)
                VALUES (?, 'v27-history-upgrade', 1, 'es-AR', ?, '{}',
                        'OrdenFix historia V27', '30712345678', 'Calle Historia 27',
                        'CABA', 'Lunes a viernes de 9 a 18',
                        'legal@ordenfix.test', 'privacidad@ordenfix.test',
                        'soporte@ordenfix.test', 'PENDIENTE', 'PENDIENTE', ?)
                """.formatted(table("legal_publicaciones")),
                PUBLICATION_ID, "1".repeat(64), BASE_TIME);

        insertDocument(
                connection,
                USAGE_DOCUMENT_LINE_ID,
                USAGE_DOCUMENT_ID,
                "v27-history-terms-usage",
                "TERMINOS_SERVICIO",
                "USO_CONTINUADO",
                1,
                "2".repeat(64));
        insertDocument(
                connection,
                PHOTO_DOCUMENT_LINE_ID,
                PHOTO_DOCUMENT_ID,
                "v27-history-privacy-photo",
                "POLITICA_PRIVACIDAD",
                "ATESTACION_FOTOS",
                2,
                "3".repeat(64));
        insertRequirement(
                connection,
                USAGE_REQUIREMENT_LINE_ID,
                USAGE_REQUIREMENT_ID,
                USAGE_DOCUMENT_ID,
                "v27-history-usage",
                "USO_CONTINUADO",
                1,
                "Acepto el uso continuado de OrdenFix");
        insertRequirement(
                connection,
                PHOTO_REQUIREMENT_LINE_ID,
                PHOTO_REQUIREMENT_ID,
                PHOTO_DOCUMENT_ID,
                "v27-history-photo",
                "ATESTACION_FOTOS",
                2,
                "Declaro el tratamiento correcto de fotos");
        insertSet(
                connection,
                USAGE_SET_ID,
                "USO_CONTINUADO",
                COMMON_REVISION,
                USAGE_REQUIREMENT_ID,
                USAGE_REQUIREMENT_LINE_ID,
                1);
        insertSet(
                connection,
                PHOTO_SET_ID,
                "ATESTACION_FOTOS",
                COMMON_REVISION,
                PHOTO_REQUIREMENT_ID,
                PHOTO_REQUIREMENT_LINE_ID,
                2);
        insertSet(connection, EMPTY_SET_ID, "CIERRE_CUENTA", EMPTY_REVISION, null, null, 0);
    }

    private void insertDocument(
            Connection connection,
            UUID lineId,
            UUID documentId,
            String key,
            String type,
            String context,
            int ordinal,
            String sha) throws SQLException {
        update(connection, """
                INSERT INTO %s
                    (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                VALUES (?, ?, ?, 'es-AR', ?, ?)
                """.formatted(table("legal_documento_lineas")),
                lineId, key, type, PUBLICATION_ID, BASE_TIME);
        update(connection, """
                INSERT INTO %s
                    (id, documento_linea_id, publicacion_intro_id, version,
                     lineage_ordinal, titulo, contenido_markdown, sha256,
                     vigente_desde, requires_reacceptance)
                VALUES (?, ?, ?, '1.0.0', 1, ?, ?, ?, ?, false)
                """.formatted(table("legal_documento_versiones")),
                documentId,
                lineId,
                PUBLICATION_ID,
                "Documento " + key,
                "# Documento " + key,
                sha,
                BASE_TIME);
        update(connection, """
                INSERT INTO %s (documento_version_id, contexto) VALUES (?, ?)
                """.formatted(table("legal_documento_contextos")), documentId, context);
        update(connection, """
                INSERT INTO %s (publicacion_id, documento_version_id, manifest_ordinal)
                VALUES (?, ?, ?)
                """.formatted(table("legal_publicacion_documentos")),
                PUBLICATION_ID, documentId, ordinal);
    }

    private void insertRequirement(
            Connection connection,
            UUID lineId,
            UUID requirementId,
            UUID documentId,
            String key,
            String context,
            int ordinal,
            String statement) throws SQLException {
        update(connection, """
                INSERT INTO %s
                    (id, clave, locale, contexto, tipo_acto,
                     publicacion_intro_id, creado_en)
                VALUES (?, ?, 'es-AR', ?, 'ACEPTACION', ?, ?)
                """.formatted(table("legal_requisito_lineas")),
                lineId, key, context, PUBLICATION_ID, BASE_TIME);
        update(connection, """
                INSERT INTO %s (requisito_linea_id, audiencia)
                VALUES (?, 'ADMIN_TITULAR')
                """.formatted(table("legal_requisito_audiencias")), lineId);
        update(connection, """
                INSERT INTO %s
                    (id, requisito_linea_id, publicacion_intro_id, version,
                     lineage_ordinal, afirmacion, afirmacion_sha256, requerido,
                     requires_reacceptance)
                VALUES (?, ?, ?, '1.0.0', 1, ?, ?, true, false)
                """.formatted(table("legal_requisito_versiones")),
                requirementId,
                lineId,
                PUBLICATION_ID,
                statement,
                "4".repeat(64));
        update(connection, """
                INSERT INTO %s
                    (requisito_version_id, documento_version_id, documento_ordinal)
                VALUES (?, ?, 1)
                """.formatted(table("legal_requisito_documentos")),
                requirementId, documentId);
        update(connection, """
                INSERT INTO %s
                    (publicacion_id, requisito_version_id, manifest_ordinal)
                VALUES (?, ?, ?)
                """.formatted(table("legal_publicacion_requisitos")),
                PUBLICATION_ID, requirementId, ordinal);
    }

    private void insertSet(
            Connection connection,
            UUID setId,
            String context,
            String revision,
            UUID requirementId,
            UUID requirementLineId,
            int ordinal) throws SQLException {
        update(connection, """
                INSERT INTO %s
                    (id, publicacion_id, locale, contexto, audiencia,
                     required_set_revision, creado_en)
                VALUES (?, ?, 'es-AR', ?, 'ADMIN_TITULAR', ?, ?)
                """.formatted(table("legal_requisito_conjuntos")),
                setId, PUBLICATION_ID, context, revision, BASE_TIME);
        if (requirementId != null) {
            update(connection, """
                    INSERT INTO %s
                        (conjunto_id, publicacion_id, requisito_version_id,
                         requisito_linea_id, manifest_ordinal)
                    VALUES (?, ?, ?, ?, ?)
                    """.formatted(table("legal_requisito_conjunto_miembros")),
                    setId, PUBLICATION_ID, requirementId, requirementLineId, ordinal);
        }
    }

    private void transitionDocumentsToPublished() {
        inTransaction(connection -> {
            insertDocumentTransition(connection, USAGE_DOCUMENT_ID, "BORRADOR", "PUBLICADA", 2);
            insertDocumentTransition(connection, PHOTO_DOCUMENT_ID, "BORRADOR", "PUBLICADA", 2);
            forceConstraints(connection);
        });
    }

    private void activateDocuments() {
        inTransaction(connection -> {
            insertDocumentTransition(connection, USAGE_DOCUMENT_ID, "PUBLICADA", "VIGENTE", 3);
            insertDocumentTransition(connection, PHOTO_DOCUMENT_ID, "PUBLICADA", "VIGENTE", 3);
            insertCurrentDocument(
                    connection,
                    "TERMINOS_SERVICIO",
                    "USO_CONTINUADO",
                    USAGE_DOCUMENT_ID,
                    USAGE_DOCUMENT_LINE_ID);
            insertCurrentDocument(
                    connection,
                    "POLITICA_PRIVACIDAD",
                    "ATESTACION_FOTOS",
                    PHOTO_DOCUMENT_ID,
                    PHOTO_DOCUMENT_LINE_ID);
            forceConstraints(connection);
        });
    }

    private void transitionRequirementsToPublished() {
        inTransaction(connection -> {
            insertRequirementTransition(
                    connection, USAGE_REQUIREMENT_ID, "BORRADOR", "PUBLICADA", 3);
            insertRequirementTransition(
                    connection, PHOTO_REQUIREMENT_ID, "BORRADOR", "PUBLICADA", 3);
            forceConstraints(connection);
        });
    }

    private void activateRequirementsAndPointers() {
        inTransaction(connection -> {
            insertRequirementTransition(
                    connection, USAGE_REQUIREMENT_ID, "PUBLICADA", "VIGENTE", 4);
            insertRequirementTransition(
                    connection, PHOTO_REQUIREMENT_ID, "PUBLICADA", "VIGENTE", 4);
            insertCurrentSet(connection, "USO_CONTINUADO", USAGE_SET_ID);
            insertCurrentSet(connection, "ATESTACION_FOTOS", PHOTO_SET_ID);
            insertCurrentSet(connection, "CIERRE_CUENTA", EMPTY_SET_ID);
            forceConstraints(connection);
        });
    }

    private void insertDocumentTransition(
            Connection connection,
            UUID documentId,
            String before,
            String after,
            int seconds) throws SQLException {
        update(connection, """
                INSERT INTO %s
                    (documento_version_id, estado_anterior, estado_nuevo,
                     motivo, ocurrido_en)
                VALUES (?, ?, ?, NULL, ?)
                """.formatted(table("legal_documento_transiciones")),
                documentId, before, after, BASE_TIME.plusSeconds(seconds));
    }

    private void insertRequirementTransition(
            Connection connection,
            UUID requirementId,
            String before,
            String after,
            int seconds) throws SQLException {
        update(connection, """
                INSERT INTO %s
                    (requisito_version_id, estado_anterior, estado_nuevo,
                     motivo, ocurrido_en)
                VALUES (?, ?, ?, NULL, ?)
                """.formatted(table("legal_requisito_transiciones")),
                requirementId, before, after, BASE_TIME.plusSeconds(seconds));
    }

    private void insertCurrentDocument(
            Connection connection,
            String type,
            String context,
            UUID documentId,
            UUID lineId) throws SQLException {
        update(connection, """
                INSERT INTO %s
                    (tipo, locale, contexto, documento_version_id,
                     documento_linea_id, publicacion_id, estado_documento)
                VALUES (?, 'es-AR', ?, ?, ?, ?, 'VIGENTE')
                """.formatted(table("legal_documento_vigentes")),
                type, context, documentId, lineId, PUBLICATION_ID);
    }

    private void insertCurrentSet(Connection connection, String context, UUID setId)
            throws SQLException {
        update(connection, """
                INSERT INTO %s
                    (locale, contexto, audiencia, conjunto_id,
                     publicacion_id, actualizado_en)
                VALUES ('es-AR', ?, 'ADMIN_TITULAR', ?, ?, ?)
                """.formatted(table("legal_requisito_conjuntos_actuales")),
                context, setId, PUBLICATION_ID, BASE_TIME.plusSeconds(4));
    }

    private Actor insertActor() {
        final Actor[] result = new Actor[1];
        inTransaction(connection -> {
            long workshopId = insertReturningLong(connection, """
                    INSERT INTO %s (nombre) VALUES ('Taller historia V27') RETURNING id
                    """.formatted(table("talleres")));
            long userId = insertReturningLong(connection, """
                    INSERT INTO %s (username, password, email, role, taller_id)
                    VALUES ('v27-history-admin', 'hash-v27-history',
                            'v27-history@ordenfix.test', 'ADMIN', ?)
                    RETURNING id
                    """.formatted(table("users")), workshopId);
            result[0] = new Actor(userId, workshopId);
        });
        return Objects.requireNonNull(result[0], "seeded actor");
    }

    private void insertLegacyAcceptance(Actor actor) {
        inTransaction(connection -> {
            update(connection, """
                    INSERT INTO %s
                        (id, user_id, taller_id, rol_wire, audiencia,
                         required_set_revision, aceptado_en)
                    VALUES (?, ?, ?, 'ADMIN', 'ADMIN_TITULAR', ?, ?)
                    """.formatted(table("legal_aceptacion_lotes")),
                    LEGACY_LOT_ID,
                    actor.userId(),
                    actor.workshopId(),
                    COMMON_REVISION,
                    BASE_TIME.plusSeconds(5));
            insertAcceptance(
                    connection,
                    actor,
                    USAGE_ACCEPTANCE_ID,
                    USAGE_REQUIREMENT_ID,
                    USAGE_DOCUMENT_ID);
            insertAcceptance(
                    connection,
                    actor,
                    PHOTO_ACCEPTANCE_ID,
                    PHOTO_REQUIREMENT_ID,
                    PHOTO_DOCUMENT_ID);
            update(connection, """
                    INSERT INTO %s (lote_id, capturado_en, retener_hasta)
                    VALUES (?, transaction_timestamp(),
                            transaction_timestamp() + INTERVAL '30 days')
                    """.formatted(table("legal_aceptacion_metadatos")), LEGACY_LOT_ID);
            insertEncryptedMetadata(connection, "IP", bytes(12, 11), bytes(9, 31), 9);
            insertEncryptedMetadata(
                    connection, "USER_AGENT", bytes(12, 51), bytes(24, 71), 80);
            update(connection, """
                    INSERT INTO %s
                        (operacion, route_template, scope_hmac,
                         idempotency_key_hmac, fingerprint_hmac,
                         hmac_key_version, user_id, taller_id, lote_id,
                         completed_at, expires_at)
                    VALUES ('ACEPTACION_LEGAL', '/api/requisitos-legales', ?, ?, ?,
                            1, ?, ?, ?, transaction_timestamp(),
                            transaction_timestamp() + INTERVAL '30 days')
                    """.formatted(table("legal_idempotencia_resultados")),
                    "5".repeat(64),
                    "6".repeat(64),
                    "7".repeat(64),
                    actor.userId(),
                    actor.workshopId(),
                    LEGACY_LOT_ID);
            forceConstraints(connection);
        });
    }

    private void insertAcceptance(
            Connection connection,
            Actor actor,
            UUID acceptanceId,
            UUID requirementId,
            UUID documentId) throws SQLException {
        update(connection, """
                INSERT INTO %s
                    (id, lote_id, user_id, taller_id, requisito_version_id,
                     requisito_clave, requisito_version, contexto, tipo_acto,
                     afirmacion, afirmacion_sha256, requerido)
                SELECT ?, ?, ?, ?, v.id, l.clave, v.version, l.contexto,
                       l.tipo_acto, v.afirmacion, v.afirmacion_sha256, v.requerido
                  FROM %s v
                  JOIN %s l ON l.id = v.requisito_linea_id
                 WHERE v.id = ?
                """.formatted(
                        table("legal_aceptaciones"),
                        table("legal_requisito_versiones"),
                        table("legal_requisito_lineas")),
                acceptanceId,
                LEGACY_LOT_ID,
                actor.userId(),
                actor.workshopId(),
                requirementId);
        update(connection, """
                INSERT INTO %s
                    (aceptacion_id, documento_ordinal, documento_version_id,
                     documento_clave, tipo, version, titulo, sha256)
                SELECT ?, d.documento_ordinal, v.id, l.clave, l.tipo,
                       v.version, v.titulo, v.sha256
                  FROM %s d
                  JOIN %s v ON v.id = d.documento_version_id
                  JOIN %s l ON l.id = v.documento_linea_id
                 WHERE d.requisito_version_id = ?
                   AND v.id = ?
                """.formatted(
                        table("legal_aceptacion_documentos"),
                        table("legal_requisito_documentos"),
                        table("legal_documento_versiones"),
                        table("legal_documento_lineas")),
                acceptanceId, requirementId, documentId);
    }

    private void insertEncryptedMetadata(
            Connection connection,
            String type,
            byte[] nonce,
            byte[] ciphertext,
            int originalLength) throws SQLException {
        update(connection, """
                INSERT INTO %s
                    (lote_id, tipo, key_version, nonce, ciphertext, tag,
                     longitud_original)
                VALUES (?, ?, 1, ?, ?, ?, ?)
                """.formatted(table("legal_aceptacion_metadatos_cifrados")),
                LEGACY_LOT_ID,
                type,
                nonce,
                ciphertext,
                bytes(16, 101),
                originalLength);
    }

    private void assertCurrentFlywayVersionIsV27(Connection connection) throws SQLException {
        String current = queryString(connection, """
                SELECT version
                  FROM %s
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank DESC
                 LIMIT 1
                """.formatted(table("flyway_schema_history")));
        if (!"27".equals(current)) {
            throw new IllegalStateException(
                    "Legal V27 history fixture requires Flyway current exactly 27 in schema "
                            + schema + "; observed " + current);
        }
    }

    private TableState captureRows(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet rows = statement.executeQuery()) {
            var payloads = new java.util.ArrayList<String>();
            var xmins = new java.util.ArrayList<String>();
            while (rows.next()) {
                payloads.add(rows.getString(1));
                xmins.add(rows.getString(2));
            }
            return new TableState(payloads, xmins);
        }
    }

    private List<String> queryStrings(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet rows = statement.executeQuery()) {
            var values = new java.util.ArrayList<String>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return List.copyOf(values);
        }
    }

    private String queryString(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return null;
            }
            String result = rows.getString(1);
            if (rows.next()) {
                throw new IllegalStateException("Expected exactly one row for scalar query");
            }
            return result;
        }
    }

    private long insertReturningLong(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("INSERT did not return an id");
            }
            long value = rows.getLong(1);
            if (rows.next()) {
                throw new IllegalStateException("INSERT returned more than one id");
            }
            return value;
        }
    }

    private int update(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            int changed = statement.executeUpdate();
            if (changed != 1) {
                throw new IllegalStateException(
                        "Expected exactly one affected row but observed " + changed);
            }
            return changed;
        }
    }

    private PreparedStatement prepare(Connection connection, String sql, Object... parameters)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int index = 0; index < parameters.length; index++) {
                Object parameter = parameters[index];
                if (parameter instanceof byte[] bytes) {
                    statement.setBytes(index + 1, bytes);
                } else {
                    statement.setObject(index + 1, parameter);
                }
            }
            return statement;
        } catch (RuntimeException | SQLException error) {
            statement.close();
            throw error;
        }
    }

    private void forceConstraints(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
    }

    private void inTransaction(SqlWork work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                work.execute(connection);
                connection.commit();
            } catch (RuntimeException | SQLException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                if (error instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw databaseFailure("seed V27 acceptance history", (SQLException) error);
            }
        } catch (SQLException error) {
            throw databaseFailure("open V27 history transaction", error);
        }
    }

    private String table(String table) {
        if (!SAFE_SCHEMA.matcher(table).matches()) {
            throw new IllegalArgumentException("Unsafe table identifier: " + table);
        }
        return quotedSchema + "." + quoteIdentifier(table);
    }

    private static String requireSafeSchema(String schema) {
        Objects.requireNonNull(schema, "schema");
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Unsafe PostgreSQL schema: " + schema);
        }
        return schema;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int index = 0; index < length; index++) {
            value[index] = (byte) (seed + index);
        }
        return value;
    }

    private static IllegalStateException databaseFailure(String operation, SQLException error) {
        return new IllegalStateException(
                "Could not " + operation + " [SQLSTATE " + error.getSQLState() + "]", error);
    }

    record SeededHistory(
            UUID publicationId,
            long userId,
            long workshopId,
            UUID legacyLotId,
            List<UUID> acceptanceIds,
            Map<String, UUID> setIds,
            Map<String, UUID> requirementIds,
            Map<String, UUID> documentIds,
            String commonRevision,
            String emptyRevision) {

        SeededHistory {
            acceptanceIds = List.copyOf(acceptanceIds);
            setIds = Map.copyOf(setIds);
            requirementIds = Map.copyOf(requirementIds);
            documentIds = Map.copyOf(documentIds);
        }
    }

    record HistorySnapshot(Map<String, TableState> tables, List<String> relationships) {
        HistorySnapshot {
            tables = Map.copyOf(tables);
            relationships = List.copyOf(relationships);
        }

        Map<String, Integer> counts() {
            Map<String, Integer> result = new LinkedHashMap<>();
            tables.forEach((table, state) -> result.put(table, state.rowBytesHex().size()));
            return Map.copyOf(result);
        }
    }

    record TableState(List<String> rowBytesHex, List<String> xmins) {
        TableState {
            rowBytesHex = List.copyOf(rowBytesHex);
            xmins = List.copyOf(xmins);
            if (rowBytesHex.size() != xmins.size()) {
                throw new IllegalArgumentException("Row bytes and xmin cardinality differ");
            }
        }
    }

    private record Actor(long userId, long workshopId) {
    }

    @FunctionalInterface
    private interface SqlWork {
        void execute(Connection connection) throws SQLException;
    }
}
