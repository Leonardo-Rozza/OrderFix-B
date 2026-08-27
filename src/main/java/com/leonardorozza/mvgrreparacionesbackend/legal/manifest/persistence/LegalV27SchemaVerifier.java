package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Set;

/** Dry-run v1 adapter over the strict V27 catalog verifier. */
final class LegalV27SchemaVerifier implements LegalDatabasePreflight {

    private static final String ISSUE_LOCATION = "database/schema";

    private final LegalV27ImportSchemaVerifier delegate;

    LegalV27SchemaVerifier(JdbcTemplate jdbc) {
        this(jdbc, currentSchema(jdbc));
    }

    LegalV27SchemaVerifier(JdbcTemplate jdbc, String expectedSchema) {
        this.delegate = new LegalV27ImportSchemaVerifier(jdbc, expectedSchema);
    }

    @Override
    public void verify() {
        try {
            delegate.verify();
        } catch (LegalImportOperationalException incompatible) {
            if (incompatible.issue().code()
                    != LegalManifestIssueCode.IMPORT_DB_SCHEMA_INCOMPATIBLE) {
                throw incompatible;
            }
            throw new LegalDryRunOperationalException(
                    LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE,
                    ISSUE_LOCATION,
                    incompatible);
        }
    }

    @Override
    public boolean usesJdbc(JdbcTemplate candidate) {
        return delegate.usesJdbc(candidate);
    }

    static Set<String> requiredTables() {
        return Set.copyOf(LegalV27ImportInventory.IMPORT_TABLES);
    }

    private static String currentSchema(JdbcTemplate jdbc) {
        String schema = Objects.requireNonNull(jdbc, "jdbc").queryForObject(
                "SELECT pg_catalog.current_schema()",
                String.class);
        return Objects.requireNonNull(schema, "current schema");
    }
}
