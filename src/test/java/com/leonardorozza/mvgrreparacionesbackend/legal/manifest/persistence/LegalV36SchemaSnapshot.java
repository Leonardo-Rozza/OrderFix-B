package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Explicit opt-in clean-PG diagnostic. No new baseline replaces a frozen prior catalog. */
class LegalV36SchemaSnapshot {
    @Test void captureHistoryAndPreservedCatalogs() {
        try(var pg=new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("ordenfix_v36_snapshot")
                .withUsername("owner").withPassword("synthetic-v36-snapshot")) {
            pg.start();var source=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("36").load().migrate();
            var jdbc=new JdbcTemplate(source);
            System.out.println("V36_HISTORY="+new LegalV29AcceptanceSchemaVerifier(jdbc,"public").snapshot().flyway());
            System.out.println("V36_LEGAL_UNCHANGED="+new LegalV29AcceptanceSchemaVerifier(jdbc,"public").snapshot().catalog().equals(LegalV34ClosureSchema.LEGAL_CATALOG));
            System.out.println("V36_CLOSURE_BASE_UNCHANGED="+LegalV33ClosureSchema.snapshot(jdbc).equals(LegalV35PhotoDeletionSchema.V33_DELTA));
            System.out.println("V36_CLOSURE_CONFIRMED_UNCHANGED="+LegalV34ClosureSchema.snapshot(jdbc).equals(LegalV34ClosureSchema.EXPECTED));
            System.out.println("V36_PHOTO_UNCHANGED="+LegalPrivatePhotoSchema.snapshot(jdbc).equals(LegalV35PhotoDeletionSchema.PHOTO_CATALOG));
            System.out.println("V36_PHOTO_DELETION_UNCHANGED="+LegalV35PhotoDeletionSchema.snapshot(jdbc).equals(LegalV35PhotoDeletionSchema.EXPECTED));
        }
    }
}
