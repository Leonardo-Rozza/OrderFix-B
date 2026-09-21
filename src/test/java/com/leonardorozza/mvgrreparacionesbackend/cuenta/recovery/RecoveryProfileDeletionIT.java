package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureGate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpoint.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpointComparison.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL logical restoration, synthetic profiles only; no application or provider starts. */
@Testcontainers
@Timeout(120)
class RecoveryProfileDeletionIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("profile_recovery").withUsername("fixture").withPassword("fixture-password")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(5432))));
    Database source;
    final UUID environment = UUID.randomUUID();

    @BeforeEach void database() throws Exception {
        assertThat(POSTGRES.getContainerInfo().getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(5432)))
                .isNotEmpty().allSatisfy(binding -> assertThat(binding.getHostIp()).isEqualTo("127.0.0.1"));
        source = freshDatabase();
        Flyway.configure().dataSource(source.jdbc().getDataSource()).locations("classpath:db/migration")
                .target("38").load().migrate();
    }

    @Test void portableV38ProfilesSurviveRepeatedLogicalRestore() throws Exception {
        Actor actor = actor();
        suppress(actor);
        Database restored = restore(source);
        Database twice = restore(restored);
        String migrated = fingerprint(source), rebuilt = fingerprint(restored);
        // Diagnostic metadata only; root freezes both exact profiles from this synthetic fixture.
        System.out.println("RECOVERY_V38_MIGRATED=" + migrated);
        System.out.println("RECOVERY_V38_RESTORED=" + rebuilt);
        assertThat(migrated).isEqualTo(RecoveryReadSchemaPreflight.MIGRATED_V38);
        assertThat(rebuilt).isEqualTo(RecoveryReadSchemaPreflight.RESTORED_V38);
        assertThat(fingerprint(twice)).isEqualTo(rebuilt);
        Snapshot expected = capture(source);
        assertThat(expected.formatVersion()).isEqualTo(2);
        assertThat(expected.workshops().getFirst().surfaces().get(Surface.PROFILE_DELETIONS).rows()).isEqualTo(1);
        assertThat(compare(expected, capture(restored)).status()).isEqualTo(Status.MATCH);
        assertThat(compare(expected, capture(twice)).status()).isEqualTo(Status.MATCH);
        assertThat(source.jdbc().queryForObject("SELECT count(*) FROM cuenta_perfil_baja_contextos", Long.class)).isZero();
    }

    @ParameterizedTest(name = "same-epoch recovery detects {0}")
    @ValueSource(strings = {"USERNAME", "EMAIL", "PASSWORD", "NAME", "CONTACT", "PAYMENT", "QR", "RECEIPT"})
    void restoredProfileDataCannotHideBehindUnchangedEpochsAndClosure(String field) throws Exception {
        Actor actor = actor();
        suppress(actor);
        Snapshot expected = capture(source);
        Database restored = restore(source);
        assertThat(compare(expected, capture(restored)).status()).isEqualTo(Status.MATCH);
        var epochs = restored.jdbc().queryForList("SELECT id,token_version FROM users ORDER BY id");
        String schema = fingerprint(restored);
        // Simulate a bad maintenance merge of old payloads after restore, as the database owner.
        // Trigger enforcement is restored before capture; production code never disables it.
        var mutation = new TransactionTemplate(restored.manager());
        mutation.executeWithoutResult(status -> {
            restored.jdbc().execute("SET LOCAL session_replication_role=replica");
            switch (field) {
                case "USERNAME" -> restored.jdbc().update("UPDATE users SET username='Synthetic original owner' WHERE id=?", actor.user());
                case "EMAIL" -> restored.jdbc().update("UPDATE users SET email='original-profile@synthetic.invalid' WHERE id=?", actor.user());
                case "PASSWORD" -> restored.jdbc().update("UPDATE users SET password='synthetic-old-credential' WHERE id=?", actor.user());
                case "NAME" -> restored.jdbc().update("UPDATE talleres SET nombre='Synthetic original workshop' WHERE id=?", actor.workshop());
                case "CONTACT" -> restored.jdbc().update("UPDATE talleres SET email_contacto='original-contact@synthetic.invalid',telefono='555-0100' WHERE id=?", actor.workshop());
                case "PAYMENT" -> restored.jdbc().update("UPDATE talleres SET alias_cobro='synthetic.alias',titular_cobro='Synthetic holder',entidad_cobro='Synthetic entity',mostrar_en_resumen=true WHERE id=?", actor.workshop());
                case "QR" -> restored.jdbc().update("INSERT INTO taller_qr_cobro(taller_id,png,sha256) VALUES(?,?,?)", actor.workshop(), new byte[]{1}, "a".repeat(64));
                case "RECEIPT" -> restored.jdbc().update("DELETE FROM cuenta_perfil_bajas WHERE taller_id=?", actor.workshop());
                default -> throw new AssertionError(field);
            }
        });
        assertThat(fingerprint(restored)).as("only rows changed, not the approved restored schema").isEqualTo(schema);
        assertThat(restored.jdbc().queryForList("SELECT id,token_version FROM users ORDER BY id")).isEqualTo(epochs);
        Snapshot actual = capture(restored);
        assertThat(actual.workshops().getFirst().surfaces().get(Surface.USERS))
                .isEqualTo(expected.workshops().getFirst().surfaces().get(Surface.USERS));
        assertThat(actual.workshops().getFirst().surfaces().get(Surface.WORKSHOP))
                .isEqualTo(expected.workshops().getFirst().surfaces().get(Surface.WORKSHOP));
        assertThat(compare(expected, actual).findings()).containsExactly(
                new Finding(actor.workshop(), Surface.PROFILE_DELETIONS, Issue.SURFACE_CHANGED));
    }

    @Test void legacySchemaProducesLegacyCoverageAndCannotMatchV38EvenWhenEmpty() throws Exception {
        Database legacy = freshDatabase();
        Flyway.configure().dataSource(legacy.jdbc().getDataSource()).locations("classpath:db/migration")
                .target("37").load().migrate();
        Snapshot previous = capture(legacy), current = capture(source);
        assertThat(previous.formatVersion()).isEqualTo(1);
        assertThat(current.formatVersion()).isEqualTo(2);
        assertThatThrownBy(() -> compare(previous, current)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test void profileReceiptTriggerDriftStillRejectsTheWholeCapture() {
        Actor actor = actor();
        suppress(actor);
        capture(source);
        source.jdbc().execute("ALTER TABLE cuenta_perfil_bajas DISABLE TRIGGER USER");
        String before = physical(source);
        assertThatThrownBy(() -> capture(source)).isInstanceOf(RecoverySnapshotReader.Rejected.class).hasNoCause();
        assertThat(physical(source)).isEqualTo(before);
    }

    private Snapshot capture(Database database) {
        String before = physical(database);
        var snapshot = new RecoverySnapshotReader(database.jdbc(), database.manager()).capture(environment, UUID.randomUUID());
        assertThat(physical(database)).as("capture is read-only").isEqualTo(before);
        return snapshot;
    }

    private static String physical(Database database) {
        var rows = new StringBuilder();
        for (String table : List.of("talleres", "users", "cuenta_cierres", "cuenta_perfil_bajas", "taller_qr_cobro")) {
            if (!Boolean.TRUE.equals(database.jdbc().queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table))) continue;
            rows.append(database.jdbc().queryForObject("SELECT coalesce(jsonb_agg(r ORDER BY r::text),'[]'::jsonb)::text "
                    + "FROM (SELECT to_jsonb(t)||jsonb_build_object('row_version',t.xmin::text) AS r FROM public." + table + " t) v", String.class));
        }
        return rows.toString();
    }

    private Actor actor() {
        long workshop = source.jdbc().queryForObject("""
                INSERT INTO talleres(nombre,email_contacto,telefono,alias_cobro,titular_cobro,entidad_cobro,mostrar_en_resumen)
                VALUES('Synthetic original workshop','original-contact@synthetic.invalid','555-0100',
                       'synthetic.alias','Synthetic holder','Synthetic entity',true) RETURNING id
                """, Long.class);
        long user = source.jdbc().queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES('Synthetic original owner','original-profile@synthetic.invalid','synthetic-old-credential','ADMIN',?,true,true,0) RETURNING id
                """, Long.class, workshop);
        source.jdbc().update("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES('Synthetic employee','employee-profile@synthetic.invalid','synthetic-employee-credential','USER',?,false,true,0)
                """, workshop);
        source.jdbc().update("INSERT INTO taller_qr_cobro(taller_id,png,sha256) VALUES(?,?,?)", workshop, new byte[]{1}, "a".repeat(64));
        UUID reference = UUID.randomUUID();
        var tx = new TransactionTemplate(source.manager());
        tx.executeWithoutResult(status -> {
            new WorkshopClosureGate(source.jdbc()).lockExclusive(workshop);
            OffsetDateTime confirmed = source.jdbc().queryForObject("SELECT clock_timestamp()-INTERVAL '8 days'", OffsetDateTime.class);
            source.jdbc().update("UPDATE users SET token_version=token_version+1 WHERE taller_id=?", workshop);
            source.jdbc().update("""
                    INSERT INTO cuenta_cierres(referencia,taller_id,titular_id,generacion,estado,politica,
                      confirmado_en,reversible_hasta,eliminacion_prevista_en)
                    VALUES(?,?,?,1,'RESTRINGIDO','ordenfix-cierre/1',?,?,?)
                    """, reference, workshop, user, confirmed, confirmed.plusDays(7), confirmed.plusDays(37));
            source.jdbc().update("""
                    UPDATE talleres SET cierre_estado='RESTRINGIDO',cierre_version=1,cierre_referencia=?,
                      cierre_confirmado_en=?,cierre_reversible_hasta=?,cierre_eliminacion_prevista_en=? WHERE id=?
                    """, reference, confirmed, confirmed.plusDays(7), confirmed.plusDays(37), workshop);
        });
        return new Actor(workshop, user, reference);
    }

    private void suppress(Actor actor) {
        var tx = new TransactionTemplate(source.manager());
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            new WorkshopClosureGate(source.jdbc()).lockExclusive(actor.workshop());
            String result = source.jdbc().queryForObject("SELECT public.cuenta_cierre_suprimir_perfil_v38(?,?,?)->>'status'",
                    String.class, UUID.randomUUID(), actor.workshop(), actor.reference());
            assertThat(result).isEqualTo("SUPPRESSED");
        });
    }

    private static String fingerprint(Database database) {
        var tx = new TransactionTemplate(database.manager());
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            var jdbc = database.jdbc();
            jdbc.execute("SET TRANSACTION READ ONLY");
            jdbc.execute("SET LOCAL row_security=off");
            jdbc.execute("SET LOCAL search_path=pg_catalog, public, pg_temp");
            jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
            jdbc.execute("SET LOCAL DateStyle='ISO, YMD'");
            jdbc.execute("SET LOCAL IntervalStyle='postgres'");
            jdbc.execute("SET LOCAL bytea_output='hex'");
            jdbc.execute("SET LOCAL extra_float_digits=3");
            return RecoveryReadSchemaPreflight.fingerprint(jdbc);
        });
    }

    private static Database freshDatabase() throws Exception {
        String name = "profile_" + UUID.randomUUID().toString().replace("-", "");
        command("createdb", "--host=/var/run/postgresql", "--username=fixture", "--template=template0", name);
        var dataSource = new DriverManagerDataSource("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/" + name, "fixture", "fixture-password");
        return new Database(name, new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
    }

    private static Database restore(Database original) throws Exception {
        String dump = "/tmp/" + UUID.randomUUID() + ".dump";
        command("pg_dump", "--host=/var/run/postgresql", "--username=fixture", "--dbname=" + original.name(),
                "--format=custom", "--file=" + dump);
        Database restored = freshDatabase();
        command("pg_restore", "--host=/var/run/postgresql", "--username=fixture", "--dbname=" + restored.name(),
                "--exit-on-error", "--single-transaction", "--no-owner", dump);
        return restored;
    }

    private static void command(String... args) throws Exception {
        var result = POSTGRES.execInContainer(args);
        assertThat(result.getExitCode()).as("synthetic PostgreSQL maintenance: %s", result.getStderr()).isZero();
    }

    private record Actor(long workshop, long user, UUID reference) { }
    private record Database(String name, JdbcTemplate jdbc, DataSourceTransactionManager manager) { }
}
