package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Explicit diagnostic, excluded by normal test naming: only a fresh disposable PostgreSQL 16. */
class LegalV35SchemaSnapshot {
    @Test
    void captureCleanPhotoDeletionCatalogs() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("ordenfix_v35_schema_snapshot")
                .withUsername("ordenfix").withPassword("disposable-snapshot")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("35").load().migrate();
            var jdbc = new JdbcTemplate(source);
            var legal = new LegalV29AcceptanceSchemaVerifier(jdbc, "public").snapshot();
            System.out.println("V35_LEGAL_CATALOG=" + legal.catalog());
            System.out.println("V35_HISTORY=" + legal.flyway());
            System.out.println("V35_BASE_CLOSURE_DELTA=" + LegalV33ClosureSchema.snapshot(jdbc));
            System.out.println("V35_CONFIRMED_CLOSURE_DELTA=" + LegalV34ClosureSchema.snapshot(jdbc));
            System.out.println("V35_PHOTO_DELETION_DELTA=" + LegalV35PhotoDeletionSchema.snapshot(jdbc));
            var registration = LegalRegistrationSchemaVerifier.class.getDeclaredMethod("catalogFingerprint");
            registration.setAccessible(true);
            System.out.println("V35_REGISTRATION_CATALOG=" + registration.invoke(new LegalRegistrationSchemaVerifier(jdbc, "public")));
            var photoSql = LegalPrivatePhotoSchema.class.getDeclaredField("SQL");
            photoSql.setAccessible(true);
            String photo = jdbc.queryForObject((String) photoSql.get(null), String.class);
            System.out.println("V35_PHOTO_CATALOG=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(photo.getBytes(StandardCharsets.UTF_8))));
        }
    }
}
