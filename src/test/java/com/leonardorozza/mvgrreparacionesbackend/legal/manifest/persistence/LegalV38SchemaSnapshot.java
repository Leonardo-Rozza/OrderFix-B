package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit diagnostic: only synthetic PG16 and metadata digests; no runtime baseline learning. */
@Timeout(180)
class LegalV38SchemaSnapshot {
    @Test void captureMigratedAndRestoredProfiles() throws Exception {
        try (var pg = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("ordenfix_v38_schema_source").withUsername("fixture").withPassword("synthetic-v38-owner")
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(5432))))) {
            pg.start();
            var source = jdbc(pg, "ordenfix_v38_schema_source");
            Flyway.configure().dataSource(source.getDataSource()).locations("classpath:db/migration").target("38").load().migrate();
            capture("SOURCE", source);
            command(pg, "pg_dump", "--host=/var/run/postgresql", "--username=fixture", "--dbname=ordenfix_v38_schema_source", "--format=custom", "--file=/tmp/schema38.dump");
            command(pg, "createdb", "--host=/var/run/postgresql", "--username=fixture", "--template=template0", "ordenfix_v38_schema_restored");
            var restored = jdbc(pg, "ordenfix_v38_schema_restored");
            assertThat(restored.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'")).isEmpty();
            command(pg, "pg_restore", "--host=/var/run/postgresql", "--username=fixture", "--dbname=ordenfix_v38_schema_restored", "--no-owner", "--exit-on-error", "--single-transaction", "/tmp/schema38.dump");
            capture("RESTORED", restored);
        }
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer pg, String database) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://"+pg.getHost()+":"+pg.getMappedPort(5432)+"/"+database, pg.getUsername(), pg.getPassword()));
    }

    private static void command(PostgreSQLContainer pg, String... arguments) throws Exception {
        var bounded = new String[arguments.length+4];
        System.arraycopy(new String[]{"timeout","-s","KILL","45"},0,bounded,0,4);
        System.arraycopy(arguments,0,bounded,4,arguments.length);
        var result = pg.execInContainer(bounded);
        assertThat(result.getExitCode()).as("%s: %s",arguments[0],result.getStderr()).isZero();
    }

    private static void capture(String name, JdbcTemplate jdbc) throws Exception {
        System.out.println(name+"_V38_HISTORY="+jdbc.queryForList("SELECT version,script,checksum,success FROM public.flyway_schema_history WHERE version='38'"));
        var profile = LegalV38ProfileErasureSchema.profileSnapshot(jdbc);
        System.out.println(name+"_IMPORT="+profile.importCatalog());
        System.out.println(name+"_EDITORIAL="+profile.editorialCatalog());
        System.out.println(name+"_LEGAL="+profile.legalCatalog());
        var registration = LegalRegistrationSchemaVerifier.class.getDeclaredMethod("catalogFingerprint");
        registration.setAccessible(true);
        System.out.println(name+"_REGISTRATION="+registration.invoke(new LegalRegistrationSchemaVerifier(jdbc,"public")));
        var names = new String[]{"CLOSURE33","CLOSURE34","PHOTO35","DELETION37","PHOTO","ERASURE38"};
        for (int i=0;i<names.length;i++) System.out.println(name+"_"+names[i]+"="+profile.deltas().get(i));
    }
}
