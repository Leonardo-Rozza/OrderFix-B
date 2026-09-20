package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
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

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real PG16 restore, historical write preflights and a restricted aggregate boundary; no provider or HTTP. */
@Testcontainers
@Timeout(120)
class LegalV37RestoreCompatibilityIT {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("restore_lab").withUsername("fixture").withPassword("synthetic-restore-owner")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(5432))));
    @TempDir static Path directory;
    static Database migrated;
    static Database restored;
    static Database twiceRestored;
    static Database migratedV36;
    static Database restoredV36;

    @BeforeAll static void prepareRealRestores() throws Exception {
        assertThat(PG.getContainerInfo().getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(5432)))
                .isNotEmpty().allSatisfy(binding -> assertThat(binding.getHostIp()).isEqualTo("127.0.0.1"));
        migrated = createDatabase("source37");
        migrate(migrated, "37");
        var release = LegalV28AggregateITSupport.releaseWithContinuedUse(directory,
                LegalV37RestoreCompatibilityIT.class, "restored-v37-synthetic");
        UUID publication = LegalV28AggregateITSupport.importRelease(migrated.source(), release);
        LegalManifestPersistenceITSupport.promoteToReady(migrated.jdbc(), publication);
        restored = restore(migrated, "restored37");
        twiceRestored = restore(restored, "twice37");
        migratedV36 = createDatabase("source36");
        migrate(migratedV36, "36");
        restoredV36 = restore(migratedV36, "restored36");
    }

    @Test void everyConsumerAccreditsMigratedRestoredAndTwiceRestoredV37WithoutDml() {
        for (Database database : List.of(migrated, restored, twiceRestored)) {
            var before = rows(database);
            var metrics = LegalJdbcMetricsSupport.instrument(database.source(), Duration.ZERO);
            var jdbc = new JdbcTemplate(metrics.dataSource());
            for (Consumer consumer : consumers(jdbc, true))
                assertThatCode(consumer.verify()::run).as("%s in %s", consumer.name(), database.name()).doesNotThrowAnyException();
            assertThat(new LegalV29AcceptanceSchemaVerifier(jdbc, "public").workshopClosureSchemaVersion()).isEqualTo(37);
            assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            assertThat(rows(database)).as("preflights cannot rewrite restored evidence").isEqualTo(before);
        }
    }

    @Test void restoredAlternativesCannotAccreditV36WhileMigratedHistoricalConsumersStillWork() {
        for (Consumer consumer : consumers(migratedV36.jdbc(), false))
            assertThatCode(consumer.verify()::run).as("migrated V36: %s", consumer.name()).doesNotThrowAnyException();
        assertRejectedWithoutDml(restoredV36, false);
    }

    @ParameterizedTest(name="restored V37 rejects {0}")
    @ValueSource(strings={"WHEN", "CHECK", "INDEX", "FUNCTION", "PUBLIC_GRANT", "HISTORY_CHECKSUM", "HISTORY_DOWNGRADE", "HISTORY_FUTURE"})
    void driftCannotUseTheRestoredCatalogAlternative(String change) {
        var before = rows(restored);
        var metrics = LegalJdbcMetricsSupport.instrument(restored.source(), Duration.ZERO);
        var jdbc = new JdbcTemplate(metrics.dataSource());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(metrics.dataSource()));
        tx.executeWithoutResult(status -> {
            mutation(change).forEach(jdbc::execute);
            metrics.reset();
            // The same transaction sees the deliberate DDL mutation, with all business guards intact.
            for (Consumer consumer : consumers(jdbc, true))
                assertThatThrownBy(consumer.verify()::run).as("%s after %s", consumer.name(), change)
                        .isInstanceOf(consumer.rejection());
            assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            status.setRollbackOnly();
        });
        assertThat(rows(restored)).isEqualTo(before);
        for (Consumer consumer : consumers(restored.jdbc(), true))
            assertThatCode(consumer.verify()::run).as("rollback restores %s", consumer.name()).doesNotThrowAnyException();
    }

    @Test void restoredRestrictedRoleAccreditsItsRealBoundaryWithoutAdditionalCapabilities() {
        String role = "ordenfix_restore_aggregate";
        var credentials = new LegalRestrictedAggregateRoleFixture(restored.jdbc(), restored.url(), role,
                "synthetic-restored-aggregate", PG.getDriverClassName()).provisionAndVerify();
        // Account/role setup is explicit deployment bootstrap, not a claim that pg_dump carries cluster settings.
        var restricted = LegalV28AggregateITSupport.dataSource(credentials);
        var metrics = LegalJdbcMetricsSupport.instrument(restricted, Duration.ZERO);
        var harness = LegalV28AggregateITSupport.aggregateHarness(metrics.dataSource(), role);
        assertThat(harness.transaction().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(harness.transaction().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var before = rows(restored);
        harness.gate().executeMutableShared((status, boundary) -> {
            assertThat(harness.jdbc().queryForObject("SELECT current_user||':'||session_user", String.class)).isEqualTo(role+":"+role);
            assertThat(harness.jdbc().queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
            assertThat(harness.jdbc().queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("off");
            return null;
        });
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertThat(rows(restored)).isEqualTo(before);
        for (String forbidden : List.of("DELETE FROM public.legal_requisito_agregados WHERE false",
                "CREATE TABLE public.restore_forbidden(id integer)", "SET session_replication_role=replica"))
            LegalV28AggregateITSupport.assertSqlState(catchThrowable(() -> harness.jdbc().execute(forbidden)), "42501");
        assertThat(rows(restored)).isEqualTo(before);
    }

    private static void assertRejectedWithoutDml(Database database, boolean includeV37) {
        var before = rows(database);
        var metrics = LegalJdbcMetricsSupport.instrument(database.source(), Duration.ZERO);
        var jdbc = new JdbcTemplate(metrics.dataSource());
        for (Consumer consumer : consumers(jdbc, includeV37))
            assertThatThrownBy(consumer.verify()::run).as("%s in %s", consumer.name(), database.name())
                    .isInstanceOf(consumer.rejection());
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(rows(database)).isEqualTo(before);
    }

    private static List<Consumer> consumers(JdbcTemplate jdbc, boolean includeV37) {
        var consumers = new java.util.ArrayList<Consumer>();
        consumers.add(new Consumer("V27 dry run", () -> new LegalV27SchemaVerifier(jdbc,"public").verify(), LegalDryRunOperationalException.class));
        consumers.add(new Consumer("V27 import", () -> new LegalV27ImportSchemaVerifier(jdbc,"public").verify(), LegalImportOperationalException.class));
        consumers.add(new Consumer("editorial", () -> new LegalEditorialSchemaVerifier(jdbc,"public").verify(), LegalEditorialOperationalException.class));
        consumers.add(new Consumer("V28 aggregate", () -> new LegalV28AggregateSchemaVerifier(jdbc,"public").verify(), LegalEditorialOperationalException.class));
        consumers.add(new Consumer("V29 acceptance", () -> new LegalV29AcceptanceSchemaVerifier(jdbc,"public").verify(), LegalEditorialOperationalException.class));
        consumers.add(new Consumer("registration", () -> new LegalRegistrationSchemaVerifier(jdbc,"public").verify(), LegalEditorialOperationalException.class));
        consumers.add(new Consumer("photos with complete legal preflight", () -> {
            new LegalV29AcceptanceSchemaVerifier(jdbc,"public").verify();
            LegalPrivatePhotoSchema.requireDeletionReceipts(jdbc);
        }, LegalEditorialOperationalException.class));
        if (includeV37) consumers.add(new Consumer("V37 operational deletion", () -> LegalV37OperationalDeletionSchema.require(jdbc), LegalEditorialOperationalException.class));
        return consumers;
    }

    private static List<String> mutation(String change) {
        return switch (change) {
            case "WHEN" -> List.of("DROP TRIGGER ct_legal_publicacion_sello ON public.legal_publicaciones",
                    "CREATE CONSTRAINT TRIGGER ct_legal_publicacion_sello AFTER UPDATE ON public.legal_publicaciones DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (false) EXECUTE FUNCTION public.legal_publicacion_constraint_guard()");
            case "CHECK" -> List.of("ALTER TABLE public.legal_requisito_agregados DROP CONSTRAINT ck_legal_requisito_agregado_audiencia",
                    "ALTER TABLE public.legal_requisito_agregados ADD CONSTRAINT ck_legal_requisito_agregado_audiencia CHECK (audiencia IN ('ADMIN_TITULAR','USER','EXTRA'))");
            case "INDEX" -> List.of("DROP INDEX public.ix_cierre_efecto_pendiente",
                    "CREATE INDEX ix_cierre_efecto_pendiente ON public.cuenta_cierre_efectos(available_at,created_at,efecto_id) WHERE estado='PENDIENTE'");
            case "FUNCTION" -> List.of("CREATE OR REPLACE FUNCTION public.cuenta_borrado_contexto_vacio_v37() RETURNS TRIGGER LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$ BEGIN RETURN NULL; END; $$");
            case "PUBLIC_GRANT" -> List.of("GRANT SELECT ON public.cuenta_borrado_lotes TO PUBLIC");
            case "HISTORY_CHECKSUM" -> List.of("UPDATE public.flyway_schema_history SET checksum=checksum+1 WHERE version='37'");
            case "HISTORY_DOWNGRADE" -> List.of("DELETE FROM public.flyway_schema_history WHERE version='37'");
            case "HISTORY_FUTURE" -> List.of("INSERT INTO public.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) SELECT max(installed_rank)+1,'38','unexpected','SQL','V38__unexpected.sql',1,current_user,1,true FROM public.flyway_schema_history");
            default -> throw new AssertionError(change);
        };
    }

    private static Database createDatabase(String suffix) throws Exception {
        String name = "ordenfix_legal_aggregate_" + suffix + "_" + UUID.randomUUID().toString().substring(0,8);
        command("createdb", "--host=/var/run/postgresql", "--username=fixture", "--template=template0", name);
        String url = "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + name;
        var source = new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword());
        return new Database(name, url, source, new JdbcTemplate(source), new DataSourceTransactionManager(source));
    }

    private static void migrate(Database database, String version) {
        Flyway.configure().dataSource(database.source()).locations("classpath:db/migration").target(version).load().migrate();
    }

    private static Database restore(Database from, String suffix) throws Exception {
        var before = rows(from);
        var logical = logicalRows(from);
        String remote = "/tmp/" + UUID.randomUUID() + ".dump";
        command("pg_dump", "--host=/var/run/postgresql", "--username=fixture", "--dbname="+from.name(), "--format=custom", "--file="+remote);
        Database to = createDatabase(suffix);
        assertThat(to.jdbc().queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'"))
                .as("restore starts without running Flyway on the target").isEmpty();
        command("pg_restore", "--host=/var/run/postgresql", "--username=fixture", "--dbname="+to.name(),
                "--exit-on-error", "--single-transaction", "--no-owner", remote);
        assertThat(rows(from)).isEqualTo(before);
        assertThat(logicalRows(to)).isEqualTo(logical);
        return to;
    }

    private static void command(String... arguments) throws Exception {
        String[] bounded = new String[arguments.length+4];
        System.arraycopy(new String[]{"timeout","-s","KILL","45"},0,bounded,0,4);
        System.arraycopy(arguments,0,bounded,4,arguments.length);
        var result = PG.execInContainer(bounded);
        assertThat(result.getExitCode()).as("%s: %s",arguments[0],result.getStderr()).isZero();
    }

    private static Map<String,List<String>> rows(Database database) { return rows(database,true); }
    private static Map<String,List<String>> logicalRows(Database database) { return rows(database,false); }
    private static Map<String,List<String>> rows(Database database,boolean versions) {
        Map<String,List<String>> values = new LinkedHashMap<>();
        for (String table : database.jdbc().queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class)) {
            assertThat(table).matches("[a-z][a-z0-9_]*");
            values.put(table,database.jdbc().queryForList("SELECT to_jsonb(r)::text"+(versions?"||'|'||xmin::text":"")+
                    " FROM public.\""+table+"\" r ORDER BY 1",String.class));
        }
        return Map.copyOf(values);
    }

    private record Database(String name,String url,DataSource source,JdbcTemplate jdbc,DataSourceTransactionManager manager) { }
    private record Consumer(String name,Runnable verify,Class<? extends RuntimeException> rejection) { }
}
