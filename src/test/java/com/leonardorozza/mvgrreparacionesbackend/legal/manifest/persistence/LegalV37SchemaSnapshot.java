package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Explicit diagnostic only: clean disposable PostgreSQL 16, catalog hashes and migration metadata. */
class LegalV37SchemaSnapshot {
    @Test
    void captureOperationalDeletionCatalogs() {
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("ordenfix_v37_schema_snapshot")
                .withUsername("owner").withPassword("synthetic-v37-snapshot")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("37").load().migrate();
            var jdbc = new JdbcTemplate(source);
            var legal = new LegalV29AcceptanceSchemaVerifier(jdbc, "public").snapshot();
            System.out.println("V37_HISTORY=" + legal.flyway());
            System.out.println("V37_LEGAL_UNCHANGED=" + legal.catalog().equals(LegalV34ClosureSchema.LEGAL_CATALOG));
            System.out.println("V37_CLOSURE_BASE_DELTA=" + LegalV33ClosureSchema.snapshot(jdbc));
            System.out.println("V37_CLOSURE_CONFIRMED_UNCHANGED=" + LegalV34ClosureSchema.snapshot(jdbc).equals(LegalV34ClosureSchema.EXPECTED));
            System.out.println("V37_PHOTO_DELETION_DELTA=" + LegalV35PhotoDeletionSchema.snapshot(jdbc));
            System.out.println("V37_PHOTO_CATALOG=" + LegalPrivatePhotoSchema.snapshot(jdbc));
            System.out.println("V37_OPERATIONAL_DELETION_DELTA=" + LegalV37OperationalDeletionSchema.snapshot(jdbc));
        }
    }
}
