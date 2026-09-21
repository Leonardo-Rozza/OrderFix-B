package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** V38 exact migrated/restored profiles and narrow capability grants, without changing frozen schemas. */
@Testcontainers
@Timeout(120)
class LegalV38CompatibilityIT {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_v38_source").withUsername("owner").withPassword("synthetic-v38-owner")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(5432))));
    private static final String EXECUTOR_ROLE = "ordenfix_profile_v38";
    private static Database migrated, restored, historical;
    private static List<Database> current;

    @BeforeAll static void prepare() throws Exception {
        migrated = database("ordenfix_legal_acceptance_v38_source");
        migrate(migrated,"38");
        command("pg_dump","--host=/var/run/postgresql","--username=owner","--dbname="+migrated.name(),"--format=custom","--file=/tmp/v38-compat.dump");
        command("createdb","--host=/var/run/postgresql","--username=owner","--template=template0","ordenfix_legal_acceptance_v38_restored");
        restored = database("ordenfix_legal_acceptance_v38_restored");
        assertThat(restored.jdbc().queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'")).isEmpty();
        command("pg_restore","--host=/var/run/postgresql","--username=owner","--dbname="+restored.name(),"--exit-on-error","--single-transaction","--no-owner","/tmp/v38-compat.dump");
        current = List.of(migrated,restored);
        migrated.jdbc().execute("CREATE ROLE "+EXECUTOR_ROLE+" LOGIN PASSWORD 'synthetic-profile-executor' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS");
        for (Database database : current) {
            var owner = database.jdbc();
            owner.execute("GRANT CONNECT ON DATABASE "+database.name()+" TO "+EXECUTOR_ROLE);
            owner.execute("GRANT USAGE ON SCHEMA public TO "+EXECUTOR_ROLE);
            owner.execute("GRANT SELECT ON public.flyway_schema_history TO "+EXECUTOR_ROLE);
            owner.execute("GRANT EXECUTE ON FUNCTION "+LegalV38ProfileErasureSchema.ENTRY_SIGNATURE+" TO "+EXECUTOR_ROLE);
        }
        command("createdb","--host=/var/run/postgresql","--username=owner","--template=template0","ordenfix_legal_acceptance_v38_historical37");
        historical = database("ordenfix_legal_acceptance_v38_historical37");
        migrate(historical,"37");
    }

    @Test void exactProfilesAccreditAllConsumersWithoutDml() {
        assertThat(LegalV38ProfileErasureSchema.profileSnapshot(migrated.jdbc())).isEqualTo(LegalV38ProfileErasureSchema.MIGRATED);
        assertThat(LegalV38ProfileErasureSchema.profileSnapshot(restored.jdbc())).isEqualTo(LegalV38ProfileErasureSchema.RESTORED);
        for (Database database : current) {
            var metrics = LegalJdbcMetricsSupport.instrument(database.source(),Duration.ZERO);
            var jdbc = new JdbcTemplate(metrics.dataSource());
            verifyConsumers(jdbc);
            assertThat(new LegalV29AcceptanceSchemaVerifier(jdbc,"public").workshopClosureSchemaVersion()).isEqualTo(38);
            assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            // Only catalog/Flyway SELECT plus the granted entry are needed; preflight never reads the ledger.
            LegalV38ProfileErasureSchema.require(executor(database));
            LegalV37OperationalDeletionSchema.require(executor(database));
        }
    }

    @Test void historicalV37StillWorksButCannotAccreditSuppression() {
        new LegalV29AcceptanceSchemaVerifier(historical.jdbc(),"public").verify();
        new LegalRegistrationSchemaVerifier(historical.jdbc(),"public").verify();
        LegalV37OperationalDeletionSchema.require(historical.jdbc());
        LegalPrivatePhotoSchema.requireDeletionReceipts(historical.jdbc());
        assertSchemaDrift(() -> LegalV38ProfileErasureSchema.require(historical.jdbc()));
    }

    @Test void executorHasNoTableMutationOrPrivateContextAccess() {
        for (Database database : current) {
            var jdbc = executor(database);
            assertThat(jdbc.queryForObject("SELECT has_function_privilege(current_user,?,'EXECUTE')",Boolean.class,
                    LegalV38ProfileErasureSchema.ENTRY_SIGNATURE)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname ~ '_v38$' AND p.proname<>'cuenta_cierre_suprimir_perfil_v38' AND has_function_privilege(current_user,p.oid,'EXECUTE')",Integer.class)).isZero();
            for (String table : LegalV38ProfileErasureSchema.TABLES)
                for (String privilege : List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE"))
                    assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,"public."+table,privilege)).isFalse();
            for (String table : List.of("users","talleres","taller_qr_cobro"))
                for (String privilege : List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE"))
                    assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,"public."+table,privilege)).isFalse();
        }
    }

    @Test void existingPhotoBoundaryReceivesNoSuppressionCapabilities() {
        for (Database database : current) {
            String role = database == migrated ? "ordenfix_photo_v38_source" : "ordenfix_photo_v38_restored";
            var owner = database.jdbc();
            var credentials = LegalRestrictedAcceptanceRoleFixture.provision(owner,role,"synthetic-photo-v38");
            owner.execute("GRANT SELECT,INSERT ON public.reparacion_fotos_privadas,public.reparacion_foto_atestaciones TO "+role);
            owner.execute("GRANT UPDATE(estado,asset_id,asset_version,lease_id,lease_hasta,asociada_en) ON public.reparacion_fotos_privadas TO "+role);
            owner.execute("GRANT SELECT(id,taller_id),UPDATE(id) ON public.reparaciones TO "+role);
            owner.execute("GRANT SELECT(cierre_estado) ON public.talleres TO "+role);
            owner.execute("GRANT EXECUTE ON FUNCTION public.foto_privada_insert_guard_v30(),public.foto_atestacion_insert_guard_v30(),public.foto_privada_completa_v30(),public.foto_privada_update_guard_v30() TO "+role);
            LegalPrivatePhotoOperationsIT.grantDeletionEvidence(owner,role);
            var photo = new JdbcTemplate(new DriverManagerDataSource(credentials.jdbcUrl(),credentials.username(),credentials.password()));
            new LegalAcceptancePrivilegeVerifier(photo,role,"public",true).verify();
            LegalPrivatePhotoSchema.requireDeletionReceipts(photo);
            assertThat(photo.queryForObject("SELECT has_function_privilege(current_user,?,'EXECUTE')",Boolean.class,LegalV38ProfileErasureSchema.ENTRY_SIGNATURE)).isFalse();
            for (String table : LegalV38ProfileErasureSchema.TABLES)
                assertThat(photo.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE')",Boolean.class,"public."+table)).isFalse();
            try {
                owner.execute("GRANT EXECUTE ON FUNCTION "+LegalV38ProfileErasureSchema.ENTRY_SIGNATURE+" TO "+role);
                assertThatThrownBy(() -> new LegalAcceptancePrivilegeVerifier(photo,role,"public",true).verify())
                        .isInstanceOf(LegalEditorialOperationalException.class);
            } finally {
                owner.execute("REVOKE EXECUTE ON FUNCTION "+LegalV38ProfileErasureSchema.ENTRY_SIGNATURE+" FROM "+role);
            }
            new LegalAcceptancePrivilegeVerifier(photo,role,"public",true).verify();
        }
    }

    @ParameterizedTest(name="both exact profiles reject {0}")
    @ValueSource(strings={
            "ALTER TABLE cuenta_perfil_baja_contextos ADD COLUMN untrusted text",
            "ALTER TABLE cuenta_perfil_bajas ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE cuenta_perfil_baja_contextos DISABLE TRIGGER ALL",
            "ALTER TABLE users DISABLE TRIGGER aa_cuenta_perfil_usuario_v38",
            "ALTER TABLE talleres DISABLE TRIGGER ab_cuenta_perfil_taller_normal_v38",
            "ALTER TABLE taller_qr_cobro DISABLE TRIGGER aa_cuenta_perfil_qr_v38",
            "ALTER TABLE taller_qr_cobro ADD COLUMN untrusted text",
            "GRANT SELECT ON cuenta_perfil_baja_contextos TO ordenfix_profile_v38",
            "GRANT INSERT ON cuenta_perfil_bajas TO ordenfix_profile_v38",
            "GRANT SELECT ON cuenta_perfil_bajas TO PUBLIC",
            "GRANT EXECUTE ON FUNCTION cuenta_perfil_baja_guard_v38() TO ordenfix_profile_v38",
            "GRANT EXECUTE ON FUNCTION cuenta_cierre_suprimir_perfil_v38(uuid,bigint,uuid) TO PUBLIC",
            "GRANT EXECUTE ON FUNCTION cuenta_cierre_suprimir_perfil_v38(uuid,bigint,uuid) TO ordenfix_profile_v38 WITH GRANT OPTION",
            "GRANT EXECUTE ON FUNCTION cuenta_cierre_borrar_lote_v37(uuid,bigint,uuid,text) TO ordenfix_profile_v38 WITH GRANT OPTION",
            "ALTER FUNCTION cuenta_cierre_suprimir_perfil_v38(uuid,bigint,uuid) SECURITY INVOKER",
            "ALTER FUNCTION cuenta_cierre_suprimir_perfil_v38(uuid,bigint,uuid) RESET search_path",
            "CREATE FUNCTION cuenta_cierre_suprimir_perfil_v38(integer) RETURNS integer LANGUAGE sql AS 'SELECT $1'",
            "UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='38'",
            "DELETE FROM flyway_schema_history WHERE version='37'",
            "DELETE FROM flyway_schema_history WHERE version='38'",
            "INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) SELECT max(installed_rank)+1,'39','unknown','SQL','V39__unknown.sql',1,current_user,1,true FROM flyway_schema_history"
    })
    void anyDriftFailsClosedAndRollbackRestoresTheMeasuredProfile(String mutation) {
        for (Database database : current) {
            var tx = new TransactionTemplate(new DataSourceTransactionManager(database.source()));
            tx.executeWithoutResult(status -> {
                database.jdbc().execute(mutation);
                assertSchemaDrift(() -> LegalV38ProfileErasureSchema.require(database.jdbc()));
                assertSchemaDrift(() -> LegalV37OperationalDeletionSchema.require(database.jdbc()));
                status.setRollbackOnly();
            });
            LegalV38ProfileErasureSchema.require(database.jdbc());
        }
    }

    private static void verifyConsumers(JdbcTemplate jdbc) {
        new LegalV27SchemaVerifier(jdbc,"public").verify();
        new LegalV27ImportSchemaVerifier(jdbc,"public").verify();
        new LegalEditorialSchemaVerifier(jdbc,"public").verify();
        new LegalV28AggregateSchemaVerifier(jdbc,"public").verify();
        new LegalV29AcceptanceSchemaVerifier(jdbc,"public").verify();
        new LegalRegistrationSchemaVerifier(jdbc,"public").verify();
        LegalPrivatePhotoSchema.requireDeletionReceipts(jdbc);
        LegalV37OperationalDeletionSchema.require(jdbc);
        LegalV38ProfileErasureSchema.require(jdbc);
    }

    private static void assertSchemaDrift(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(LegalEditorialOperationalException.class)
                .satisfies(failure -> assertThat(((LegalEditorialOperationalException)failure).issue().code()).isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT));
    }
    private static JdbcTemplate executor(Database database) {
        return new JdbcTemplate(new DriverManagerDataSource(database.url(),EXECUTOR_ROLE,"synthetic-profile-executor"));
    }
    private static Database database(String name) {
        String url="jdbc:postgresql://"+PG.getHost()+":"+PG.getMappedPort(5432)+"/"+name;
        var source=new DriverManagerDataSource(url,PG.getUsername(),PG.getPassword());
        return new Database(name,url,source,new JdbcTemplate(source));
    }
    private static void migrate(Database database,String version) {
        Flyway.configure().dataSource(database.source()).locations("classpath:db/migration").target(version).load().migrate();
    }
    private static void command(String... arguments) throws Exception {
        var bounded=new String[arguments.length+4];
        System.arraycopy(new String[]{"timeout","-s","KILL","45"},0,bounded,0,4);
        System.arraycopy(arguments,0,bounded,4,arguments.length);
        var result=PG.execInContainer(bounded);
        assertThat(result.getExitCode()).as("%s: %s",arguments[0],result.getStderr()).isZero();
    }
    private record Database(String name,String url,DataSource source,JdbcTemplate jdbc) { }
}
