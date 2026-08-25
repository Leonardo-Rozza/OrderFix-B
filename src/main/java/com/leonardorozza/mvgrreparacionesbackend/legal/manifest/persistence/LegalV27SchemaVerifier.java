package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Verifies the exact PostgreSQL objects required by the V27 provisional graph. */
final class LegalV27SchemaVerifier implements LegalDatabasePreflight {

    private static final String ISSUE_LOCATION = "database/schema";
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "legal_publicaciones",
            "legal_documento_lineas",
            "legal_documento_versiones",
            "legal_documento_contextos",
            "legal_publicacion_documentos",
            "legal_requisito_lineas",
            "legal_requisito_audiencias",
            "legal_requisito_versiones",
            "legal_requisito_documentos",
            "legal_publicacion_requisitos",
            "legal_requisito_conjuntos",
            "legal_requisito_conjunto_miembros");

    private final JdbcTemplate jdbc;

    LegalV27SchemaVerifier(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void verify() {
        String schema = jdbc.queryForObject(
                "SELECT pg_catalog.current_schema()",
                String.class);
        if (schema == null || schema.isBlank()) {
            incompatible();
        }

        Set<String> relations = new HashSet<>(jdbc.queryForList("""
                SELECT c.relname
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ?
                   AND c.relkind = 'r'
                   AND (c.relname = ? OR c.relname IN (
                       'legal_publicaciones',
                       'legal_documento_lineas',
                       'legal_documento_versiones',
                       'legal_documento_contextos',
                       'legal_publicacion_documentos',
                       'legal_requisito_lineas',
                       'legal_requisito_audiencias',
                       'legal_requisito_versiones',
                       'legal_requisito_documentos',
                       'legal_publicacion_requisitos',
                       'legal_requisito_conjuntos',
                       'legal_requisito_conjunto_miembros'
                   ))
                """, String.class, schema, FLYWAY_HISTORY_TABLE));

        if (!relations.contains(FLYWAY_HISTORY_TABLE)
                || !relations.containsAll(REQUIRED_TABLES)
                || relations.size() != REQUIRED_TABLES.size() + 1) {
            incompatible();
        }

        String historyTable = quoteIdentifier(schema)
                + "."
                + quoteIdentifier(FLYWAY_HISTORY_TABLE);
        Long successfulV27 = jdbc.queryForObject(
                "SELECT count(*) FROM " + historyTable
                        + " WHERE version = '27' AND success IS TRUE",
                Long.class);
        if (successfulV27 == null || successfulV27 != 1L) {
            incompatible();
        }

        Boolean functionAvailable = jdbc.queryForObject("""
                SELECT pg_catalog.count(*) = 1
                  FROM pg_catalog.pg_proc p
                  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = ?
                   AND p.proname = 'legal_validar_publicacion_sellada'
                   AND p.prokind = 'f'
                   AND p.pronargs = 1
                   AND p.proargtypes[0] = 'pg_catalog.uuid'::pg_catalog.regtype
                   AND p.prorettype = 'pg_catalog.void'::pg_catalog.regtype
                """, Boolean.class, schema);
        if (!Boolean.TRUE.equals(functionAvailable)) {
            incompatible();
        }
    }

    static Set<String> requiredTables() {
        return REQUIRED_TABLES;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static void incompatible() {
        throw new LegalDryRunOperationalException(
                LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE,
                ISSUE_LOCATION);
    }
}
