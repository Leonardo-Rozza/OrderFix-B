package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;

/** V37 admits only the bounded operational capability, without widening existing legal/photo roles. */
class LegalV37CompatibilityIT {
    private static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_v37").withUsername("owner").withPassword("v37-synthetic");
    private static final String PHOTO_ROLE = "ordenfix_photo_v37";
    private static final String EXECUTOR_ROLE = "ordenfix_deletion_v37";
    private static DataSource source;
    private static JdbcTemplate owner, photo, executor;

    @BeforeAll
    static void migrate() {
        PG.start();
        source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("37").load().migrate();
        owner = new JdbcTemplate(source);
        var role = LegalRestrictedAcceptanceRoleFixture.provision(owner, PHOTO_ROLE, "v37-photo-role");
        owner.execute("GRANT SELECT,INSERT ON public.reparacion_fotos_privadas,public.reparacion_foto_atestaciones TO " + PHOTO_ROLE);
        owner.execute("GRANT UPDATE(estado,asset_id,asset_version,lease_id,lease_hasta,asociada_en) ON public.reparacion_fotos_privadas TO " + PHOTO_ROLE);
        owner.execute("GRANT SELECT(id,taller_id),UPDATE(id) ON public.reparaciones TO " + PHOTO_ROLE);
        owner.execute("GRANT SELECT(cierre_estado) ON public.talleres TO " + PHOTO_ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION public.foto_privada_insert_guard_v30(),public.foto_atestacion_insert_guard_v30(),public.foto_privada_completa_v30(),public.foto_privada_update_guard_v30() TO " + PHOTO_ROLE);
        LegalPrivatePhotoOperationsIT.grantDeletionEvidence(owner, PHOTO_ROLE);
        photo = new JdbcTemplate(new DriverManagerDataSource(role.jdbcUrl(), role.username(), role.password()));
        owner.execute("CREATE ROLE " + EXECUTOR_ROLE + " LOGIN PASSWORD 'v37-executor-synthetic' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS");
        owner.execute("GRANT CONNECT ON DATABASE ordenfix_legal_acceptance_v37 TO " + EXECUTOR_ROLE);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + EXECUTOR_ROLE);
        owner.execute("GRANT SELECT ON public.flyway_schema_history TO " + EXECUTOR_ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION " + LegalV37OperationalDeletionSchema.ENTRY_SIGNATURE + " TO " + EXECUTOR_ROLE);
        executor = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), EXECUTOR_ROLE, "v37-executor-synthetic"));
    }

    @AfterAll
    static void stop() { PG.stop(); }

    @Test
    void exactV37AccreditsEveryConsumerWithoutReplacingFrozenEvidence() {
        var verifier = new LegalV29AcceptanceSchemaVerifier(owner, "public");
        assertThat(verifier.workshopClosureSchemaVersion()).isEqualTo(37);
        assertThat(verifier.snapshot().catalog()).isEqualTo(LegalV34ClosureSchema.LEGAL_CATALOG);
        assertThat(LegalV34ClosureSchema.snapshot(owner)).isEqualTo(LegalV34ClosureSchema.EXPECTED);
        assertThat(LegalV33ClosureSchema.snapshot(owner)).isEqualTo(LegalV37OperationalDeletionSchema.V33_DELTA);
        assertThat(LegalV35PhotoDeletionSchema.snapshot(owner)).isEqualTo(LegalV37OperationalDeletionSchema.V35_DELTA);
        assertThat(LegalPrivatePhotoSchema.snapshot(owner)).isEqualTo(LegalV37OperationalDeletionSchema.PHOTO_CATALOG);
        new LegalV27SchemaVerifier(owner, "public").verify();
        new LegalV27ImportSchemaVerifier(owner, "public").verify();
        new LegalEditorialSchemaVerifier(owner, "public").verify();
        new LegalV28AggregateSchemaVerifier(owner, "public").verify();
        new LegalRegistrationSchemaVerifier(owner, "public").verify();
        LegalV37OperationalDeletionSchema.require(executor);
        LegalPrivatePhotoSchema.requireDeletionReceipts(photo);
    }

    @Test
    void photoAndExecutorHaveSeparateCapabilitiesWithoutPrivateContextAccess() {
        new LegalAcceptancePrivilegeVerifier(photo, PHOTO_ROLE, "public", true).verify();
        assertThat(photo.queryForObject("SELECT has_function_privilege(current_user,?,'EXECUTE')", Boolean.class,
                LegalV37OperationalDeletionSchema.ENTRY_SIGNATURE)).isFalse();
        assertThat(executor.queryForObject("SELECT has_function_privilege(current_user,?,'EXECUTE')", Boolean.class,
                LegalV37OperationalDeletionSchema.ENTRY_SIGNATURE)).isTrue();
        for (String table : LegalV37OperationalDeletionSchema.TABLES) {
            for (String privilege : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
                assertThat(photo.queryForObject("SELECT has_table_privilege(current_user,?,?)", Boolean.class,
                        "public." + table, privilege)).isFalse();
                assertThat(executor.queryForObject("SELECT has_table_privilege(current_user,?,?)", Boolean.class,
                        "public." + table, privilege)).isFalse();
            }
        }
        assertThat(executor.queryForObject("SELECT has_table_privilege(current_user,'public.clientes','DELETE')", Boolean.class)).isFalse();
        assertThat(executor.queryForObject("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname ~ '_v37$' AND p.proname<>'cuenta_cierre_borrar_lote_v37' AND has_function_privilege(current_user,p.oid,'EXECUTE')", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE cuenta_borrado_contextos ADD COLUMN untrusted text",
            "ALTER TABLE cuenta_borrado_lotes ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE cuenta_borrado_contextos DISABLE TRIGGER ALL",
            "ALTER TABLE clientes DISABLE TRIGGER aa_cuenta_guard_v33",
            "ALTER TABLE reparacion_fotos_privadas DISABLE TRIGGER ALL",
            "GRANT SELECT ON cuenta_borrado_contextos TO ordenfix_deletion_v37",
            "GRANT INSERT ON cuenta_borrado_lotes TO ordenfix_deletion_v37",
            "GRANT SELECT ON cuenta_borrado_lotes TO PUBLIC",
            "GRANT EXECUTE ON FUNCTION cuenta_cierre_borrar_lote_v37(uuid,bigint,uuid,text) TO PUBLIC",
            "GRANT EXECUTE ON FUNCTION cuenta_cierre_borrar_lote_v37(uuid,bigint,uuid,text) TO ordenfix_deletion_v37 WITH GRANT OPTION",
            "ALTER FUNCTION cuenta_cierre_borrar_lote_v37(uuid,bigint,uuid,text) SECURITY INVOKER",
            "ALTER FUNCTION cuenta_cierre_borrar_lote_v37(uuid,bigint,uuid,text) RESET search_path",
            "CREATE FUNCTION cuenta_cierre_borrar_lote_v37(integer) RETURNS integer LANGUAGE sql AS 'SELECT $1'",
            "UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='37'",
            "DELETE FROM flyway_schema_history WHERE version='36'",
            "DELETE FROM flyway_schema_history WHERE version='37'",
            "INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) SELECT max(installed_rank)+1,'38','unknown','SQL','V38__unknown.sql',1,current_user,1,true FROM flyway_schema_history"
    })
    void alteredCapabilityOrHistoryFailsClosed(String mutation) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            owner.execute(mutation);
            assertThatThrownBy(() -> LegalV37OperationalDeletionSchema.require(owner))
                    .isInstanceOf(LegalEditorialOperationalException.class)
                    .satisfies(failure -> assertThat(((LegalEditorialOperationalException) failure).issue().code())
                            .isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT));
            status.setRollbackOnly();
        });
        LegalV37OperationalDeletionSchema.require(owner);
    }

    @Test
    void grantingTheEntryToPhotoRoleIsRejectedByItsExistingPrivilegeBoundary() {
        try {
            owner.execute("GRANT EXECUTE ON FUNCTION " + LegalV37OperationalDeletionSchema.ENTRY_SIGNATURE + " TO " + PHOTO_ROLE);
            assertThatThrownBy(() -> new LegalAcceptancePrivilegeVerifier(photo, PHOTO_ROLE, "public", true).verify())
                    .isInstanceOf(LegalEditorialOperationalException.class);
        } finally {
            owner.execute("REVOKE EXECUTE ON FUNCTION " + LegalV37OperationalDeletionSchema.ENTRY_SIGNATURE + " FROM " + PHOTO_ROLE);
        }
        new LegalAcceptancePrivilegeVerifier(photo, PHOTO_ROLE, "public", true).verify();
    }
}
