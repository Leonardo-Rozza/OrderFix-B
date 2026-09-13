package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V33 extends exact consumer accreditation; every corruption rolls back in its own transaction. */
class LegalV33ClosureSchemaVerifierIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_closure").withUsername("owner").withPassword("disposable-closure-schema");
    private static DataSource source;
    private static JdbcTemplate jdbc;
    private static JdbcTemplate photoJdbc;
    private static JdbcTemplate photoOwner;
    private static JdbcTemplate registrationJdbc;
    private static final String PHOTO_ROLE = "ordenfix_closure_photos";
    private static final String REGISTRATION_ROLE = "ordenfix_closure_registration";

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("33").load().migrate();
        jdbc = new JdbcTemplate(source);
        var registration = LegalRestrictedRegistrationRoleFixture.provision(jdbc, REGISTRATION_ROLE, "disposable-registration");
        registrationJdbc = new JdbcTemplate(new DriverManagerDataSource(registration.jdbcUrl(), registration.username(), registration.password()));
        jdbc.execute("CREATE DATABASE ordenfix_legal_acceptance_closure");
        var photoSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/ordenfix_legal_acceptance_closure"),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(photoSource).locations("classpath:db/migration").target("33").load().migrate();
        photoOwner = new JdbcTemplate(photoSource);
        var photo = LegalRestrictedAcceptanceRoleFixture.provision(photoOwner, PHOTO_ROLE, "disposable-photos");
        photoOwner.execute("GRANT SELECT,INSERT ON public.reparacion_fotos_privadas,public.reparacion_foto_atestaciones TO " + PHOTO_ROLE);
        photoOwner.execute("GRANT UPDATE(estado,asset_id,asset_version,lease_id,lease_hasta,asociada_en) ON public.reparacion_fotos_privadas TO " + PHOTO_ROLE);
        photoOwner.execute("GRANT SELECT(id,taller_id),UPDATE(id) ON public.reparaciones TO " + PHOTO_ROLE);
        photoOwner.execute("GRANT SELECT(cierre_estado) ON public.talleres TO " + PHOTO_ROLE);
        photoOwner.execute("GRANT EXECUTE ON FUNCTION public.foto_privada_insert_guard_v30(),public.foto_atestacion_insert_guard_v30(),"
                + "public.foto_privada_completa_v30(),public.foto_privada_update_guard_v30() TO " + PHOTO_ROLE);
        photoJdbc = new JdbcTemplate(new DriverManagerDataSource(photo.jdbcUrl(), photo.username(), photo.password()));
    }

    @AfterAll
    static void stop() { POSTGRES.stop(); }

    @Test
    void allHistoricalConsumersAccreditOnlyTheRecordedV33Delta() {
        assertThat(new LegalV29AcceptanceSchemaVerifier(jdbc, "public").snapshot().catalog())
                .isEqualTo(LegalV33ClosureSchema.LEGAL_CATALOG);
        assertThat(LegalV33ClosureSchema.snapshot(jdbc)).isEqualTo(LegalV33ClosureSchema.EXPECTED);
        new LegalV27SchemaVerifier(jdbc, "public").verify();
        new LegalV27ImportSchemaVerifier(jdbc, "public").verify();
        new LegalEditorialSchemaVerifier(jdbc, "public").verify();
        new LegalV28AggregateSchemaVerifier(jdbc, "public").verify();
        new LegalV29AcceptanceSchemaVerifier(jdbc, "public").verify();
        new LegalRegistrationSchemaVerifier(jdbc, "public").verify();
        LegalPrivatePhotoSchema.verify(jdbc);
    }

    @Test
    void restrictedConsumersAccreditTheSameClosureDeltaWithOnlyTheirCapabilities() {
        new LegalRegistrationSchemaVerifier(registrationJdbc, "public").verify();
        new LegalRegistrationPrivilegeVerifier(registrationJdbc, REGISTRATION_ROLE, "public").verify();
        new LegalV29AcceptanceSchemaVerifier(photoJdbc, "public").verify();
        new LegalAcceptancePrivilegeVerifier(photoJdbc, PHOTO_ROLE, "public", true).verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "REVOKE SELECT(cierre_estado) ON public.talleres FROM ordenfix_closure_photos",
            "GRANT SELECT(cierre_referencia) ON public.talleres TO ordenfix_closure_photos",
            "GRANT EXECUTE ON FUNCTION public.cuenta_cierre_guard_v33() TO ordenfix_closure_photos"
    })
    void photoClosureCapabilityIsExactAndDoesNotExposePrivateFunctions(String mutation) {
        try {
            photoOwner.execute(mutation);
            assertThatThrownBy(() -> new LegalAcceptancePrivilegeVerifier(photoJdbc, PHOTO_ROLE, "public", true).verify())
                    .isInstanceOf(LegalEditorialOperationalException.class);
        } finally {
            photoOwner.execute("GRANT SELECT(cierre_estado) ON public.talleres TO " + PHOTO_ROLE);
            photoOwner.execute("REVOKE SELECT(cierre_referencia) ON public.talleres FROM " + PHOTO_ROLE);
            photoOwner.execute("REVOKE EXECUTE ON FUNCTION public.cuenta_cierre_guard_v33() FROM " + PHOTO_ROLE);
        }
        new LegalAcceptancePrivilegeVerifier(photoJdbc, PHOTO_ROLE, "public", true).verify();
    }

    static Stream<String> guardedTables() { return LegalV33ClosureSchema.GUARDED_TABLES.stream(); }

    @ParameterizedTest
    @MethodSource("guardedTables")
    void everyOperationalTriggerRemainsRequired(String table) {
        boolean photoCatalog = table.equals("reparacion_fotos_privadas") || table.equals("reparacion_foto_atestaciones");
        assertDrift("ALTER TABLE public." + table + " DISABLE TRIGGER aa_cuenta_guard_v33", photoCatalog);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE public.talleres ALTER COLUMN cierre_estado SET DEFAULT 'RESTRINGIDO'",
            "ALTER TABLE public.talleres ALTER COLUMN cierre_version DROP NOT NULL",
            "ALTER TABLE public.cuenta_cierres ADD COLUMN unexpected text",
            "ALTER TABLE public.reparacion_fotos ALTER COLUMN taller_id DROP NOT NULL",
            "ALTER TABLE public.presupuesto_items DROP CONSTRAINT fk_presupuesto_items_cierre_taller",
            "ALTER TABLE public.auth_tokens DROP CONSTRAINT fk_auth_tokens_cierre_taller",
            "ALTER TABLE public.cuenta_cierres ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE public.cuenta_cierres DISABLE TRIGGER aa_cuenta_historial_v33",
            "ALTER TABLE public.talleres DISABLE TRIGGER ct_cuenta_cierre_taller_v33",
            "ALTER TABLE public.cuenta_cierres DISABLE TRIGGER ct_cuenta_cierre_historial_v33",
            "ALTER FUNCTION public.cuenta_cierre_guard_v33() SECURITY INVOKER",
            "ALTER FUNCTION public.cuenta_cierre_guard_v33() SET search_path=public,pg_catalog,pg_temp",
            "ALTER FUNCTION public.cuenta_cierre_estado_v33(bigint) STABLE",
            "GRANT EXECUTE ON FUNCTION public.cuenta_cierre_exclusivo_v33(bigint) TO PUBLIC",
            "GRANT SELECT ON TABLE public.cuenta_cierres TO PUBLIC",
            "CREATE ROLE closure_drift_owner NOLOGIN; ALTER FUNCTION public.cuenta_cierre_guard_v33() OWNER TO closure_drift_owner",
            "CREATE ROLE closure_drift_owner NOLOGIN; ALTER TABLE public.cuenta_cierres OWNER TO closure_drift_owner",
            "UPDATE public.flyway_schema_history SET checksum=checksum+1 WHERE version='33'",
            "UPDATE public.flyway_schema_history SET script='V33__unexpected.sql' WHERE version='33'",
            "UPDATE public.flyway_schema_history SET success=false WHERE version='33'",
            "DELETE FROM public.flyway_schema_history WHERE version='33'"
    })
    void closureCatalogCannotBeLoosenedOrPresentedAsV32(String mutation) { assertDrift(mutation); }

    @Test
    void equivalentTriggerNamesCannotHideAnotherFunctionBody() {
        assertDrift("CREATE OR REPLACE FUNCTION public.cuenta_cierre_exclusivo_v33(p_taller_id bigint) RETURNS boolean "
                + "LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp "
                + "AS $$ BEGIN RETURN true; END $$");
    }

    private static void assertDrift(String mutation) { assertDrift(mutation, false); }

    private static void assertDrift(String mutation, boolean photoCatalog) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            jdbc.execute(mutation);
            var failure = assertThatThrownBy(() -> new LegalV29AcceptanceSchemaVerifier(jdbc, "public").verify());
            if (photoCatalog) {
                // The exact photo inventory runs before the complete closure delta.
                failure.isExactlyInstanceOf(IllegalStateException.class)
                        .hasMessage("Esquema de fotos privadas incompatible").hasNoCause();
            } else {
                failure.isInstanceOf(LegalEditorialOperationalException.class)
                        .satisfies(cause -> assertThat(((LegalEditorialOperationalException) cause).issue().code())
                                .isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT));
            }
            status.setRollbackOnly();
        });
        new LegalV29AcceptanceSchemaVerifier(jdbc, "public").verify();
    }
}
