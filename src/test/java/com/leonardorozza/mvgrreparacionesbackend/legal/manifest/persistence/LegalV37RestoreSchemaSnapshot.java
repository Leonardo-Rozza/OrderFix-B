package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit synthetic diagnostic, excluded from normal test naming. No baseline learned at runtime. */
class LegalV37RestoreSchemaSnapshot {
    @Test void captureMigratedAndRestoredCatalogs() throws Exception {
        try (var pg = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("restore_source").withUsername("fixture").withPassword("synthetic-restore")) {
            pg.start();
            var source = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            Flyway.configure().dataSource(source.getDataSource()).locations("classpath:db/migration").target("37").load().migrate();
            capture("SOURCE", source);
            command(pg, "pg_dump", "-U", "fixture", "-d", "restore_source", "-Fc", "-f", "/tmp/schema.dump");
            source.execute("CREATE DATABASE restore_target");
            command(pg, "pg_restore", "-U", "fixture", "-d", "restore_target", "--no-owner", "--exit-on-error", "--single-transaction", "/tmp/schema.dump");
            var restored = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl().replace("/restore_source", "/restore_target"), pg.getUsername(), pg.getPassword()));
            capture("RESTORED", restored);
        }
    }
    private static void command(PostgreSQLContainer pg, String... arguments) throws Exception {
        var bounded = new String[arguments.length + 3];
        System.arraycopy(new String[]{"timeout", "45", "env"}, 0, bounded, 0, 3);
        System.arraycopy(arguments, 0, bounded, 3, arguments.length);
        var result = pg.execInContainer(bounded);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
    private static void capture(String name, JdbcTemplate jdbc) throws Exception {
        System.out.println(name + "_IMPORT=" + new LegalV27ImportSchemaVerifier(jdbc, "public").snapshot().catalog());
        System.out.println(name + "_EDITORIAL=" + new LegalEditorialSchemaVerifier(jdbc, "public").snapshot().catalog());
        System.out.println(name + "_LEGAL=" + new LegalV29AcceptanceSchemaVerifier(jdbc, "public").snapshot().catalog());
        var registration = LegalRegistrationSchemaVerifier.class.getDeclaredMethod("catalogFingerprint");
        registration.setAccessible(true);
        System.out.println(name + "_REGISTRATION=" + registration.invoke(new LegalRegistrationSchemaVerifier(jdbc, "public")));
        System.out.println(name + "_CLOSURE33=" + LegalV33ClosureSchema.snapshot(jdbc));
        System.out.println(name + "_CLOSURE34=" + LegalV34ClosureSchema.snapshot(jdbc));
        System.out.println(name + "_PHOTO35=" + LegalV35PhotoDeletionSchema.snapshot(jdbc));
        System.out.println(name + "_DELETION37=" + LegalV37OperationalDeletionSchema.snapshot(jdbc));
        System.out.println(name + "_PHOTO=" + LegalPrivatePhotoSchema.snapshot(jdbc));
        System.out.println(name + "_WHEN=" + jdbc.queryForList("SELECT tgname,tgqual::text,pg_get_triggerdef(oid) FROM pg_trigger WHERE tgqual IS NOT NULL ORDER BY tgname"));
    }
}
