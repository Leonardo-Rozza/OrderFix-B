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

/** V34 extends exact consumer accreditation; every corruption rolls back in its own transaction. */
class LegalV34ClosureSchemaVerifierIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_confirmed_closure").withUsername("owner").withPassword("disposable-closure-schema");
    private static DataSource source;
    private static JdbcTemplate jdbc;
    private static JdbcTemplate photoJdbc;
    private static JdbcTemplate photoOwner;
    private static JdbcTemplate registrationJdbc;
    private static final String PHOTO_ROLE = "ordenfix_confirmed_closure_photos";
    private static final String REGISTRATION_ROLE = "ordenfix_confirmed_closure_registration";

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("34").load().migrate();
        jdbc = new JdbcTemplate(source);
        var registration = LegalRestrictedRegistrationRoleFixture.provision(jdbc, REGISTRATION_ROLE, "disposable-registration");
        registrationJdbc = new JdbcTemplate(new DriverManagerDataSource(registration.jdbcUrl(), registration.username(), registration.password()));
        jdbc.execute("CREATE DATABASE ordenfix_legal_acceptance_confirmed_closure");
        var photoSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/ordenfix_legal_acceptance_confirmed_closure"),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(photoSource).locations("classpath:db/migration").target("34").load().migrate();
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
    void allHistoricalConsumersAccreditTheCompleteRecordedV34Delta() {
        assertThat(new LegalV29AcceptanceSchemaVerifier(jdbc, "public").snapshot().catalog())
                .isEqualTo(LegalV34ClosureSchema.LEGAL_CATALOG);
        assertThat(LegalV33ClosureSchema.snapshot(jdbc)).isEqualTo(LegalV34ClosureSchema.V33_DELTA);
        assertThat(LegalV34ClosureSchema.snapshot(jdbc)).isEqualTo(LegalV34ClosureSchema.EXPECTED);
        new LegalV27SchemaVerifier(jdbc, "public").verify();
        new LegalV27ImportSchemaVerifier(jdbc, "public").verify();
        new LegalEditorialSchemaVerifier(jdbc, "public").verify();
        new LegalV28AggregateSchemaVerifier(jdbc, "public").verify();
        new LegalV29AcceptanceSchemaVerifier(jdbc, "public").verify();
        new LegalRegistrationSchemaVerifier(jdbc, "public").verify();
        LegalPrivatePhotoSchema.verify(jdbc);
    }

    @Test
    void registrationAndPhotoConsumersKeepTheirExistingRestrictedCapabilities() {
        new LegalRegistrationSchemaVerifier(registrationJdbc, "public").verify();
        new LegalRegistrationPrivilegeVerifier(registrationJdbc, REGISTRATION_ROLE, "public").verify();
        new LegalV29AcceptanceSchemaVerifier(photoJdbc, "public").verify();
        new LegalAcceptancePrivilegeVerifier(photoJdbc, PHOTO_ROLE, "public", true).verify();
    }

    static Stream<String> newTables() { return LegalV34ClosureSchema.TABLES.stream(); }
    static Stream<String> newFunctions() { return LegalV34ClosureSchema.FUNCTIONS.stream(); }
    static Stream<String> operationalTriggers() {
        return LegalV34ClosureSchema.TRIGGERS.entrySet().stream().map(entry ->
                "ALTER TABLE public." + entry.getKey() + " DISABLE TRIGGER " + entry.getValue());
    }

    @ParameterizedTest
    @MethodSource("operationalTriggers")
    void everyConfirmationCommandEffectAndRenewalTriggerRemainsRequired(String mutation) { assertDrift(mutation); }

    @ParameterizedTest
    @MethodSource("newTables")
    void newAccountTableSurfacesCannotBeRelaxed(String table) {
        assertDrift("ALTER TABLE public." + table + " ADD COLUMN unexpected text");
        assertDrift("ALTER TABLE public." + table + " ENABLE ROW LEVEL SECURITY");
        assertDrift("GRANT SELECT ON TABLE public." + table + " TO PUBLIC");
    }

    @ParameterizedTest
    @MethodSource("newFunctions")
    void privateFunctionDefinitionsSearchPathAndAclRemainExact(String function) {
        assertDrift("ALTER FUNCTION public." + function + "() SECURITY INVOKER");
        assertDrift("ALTER FUNCTION public." + function + "() RESET search_path");
        assertDrift("GRANT EXECUTE ON FUNCTION public." + function + "() TO PUBLIC");
        assertDrift("CREATE OR REPLACE FUNCTION public." + function + "() RETURNS trigger "
                + "LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp "
                + "AS $$ BEGIN RETURN NULL; END $$");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE public.cuenta_cierre_confirmaciones ALTER COLUMN token_version DROP NOT NULL",
            "ALTER TABLE public.cuenta_cierre_confirmaciones DROP CONSTRAINT ck_cierre_confirmacion_plazo",
            "ALTER TABLE public.cuenta_cierre_confirmaciones DROP CONSTRAINT ck_cierre_confirmacion_referencia",
            "DROP INDEX public.uq_cierre_confirmacion_vigente; CREATE UNIQUE INDEX uq_cierre_confirmacion_vigente ON public.cuenta_cierre_confirmaciones(user_id,session_hash,proposito) WHERE usada_en IS NULL AND proposito='CERRAR'",
            "ALTER TABLE public.cuenta_cierre_operaciones DROP CONSTRAINT uq_cierre_operacion_generacion",
            "ALTER TABLE public.cuenta_cierre_operaciones DROP CONSTRAINT fk_cierre_operacion_referencia",
            "ALTER TABLE public.cuenta_cierre_operaciones DROP CONSTRAINT ck_cierre_operacion_estado",
            "ALTER TABLE public.cuenta_cierre_efectos DROP CONSTRAINT fk_cierre_efecto_pertenencia",
            "ALTER TABLE public.cuenta_cierre_efectos DROP CONSTRAINT ck_cierre_efecto_lease",
            "DROP INDEX public.uq_cierre_efecto_link; CREATE INDEX uq_cierre_efecto_link ON public.cuenta_cierre_efectos(link_id)",
            "GRANT SELECT(token_hash) ON public.cuenta_cierre_confirmaciones TO PUBLIC",
            "CREATE ROLE confirmed_drift_owner NOLOGIN; ALTER TABLE public.cuenta_cierre_operaciones OWNER TO confirmed_drift_owner",
            "CREATE ROLE confirmed_drift_owner NOLOGIN; ALTER FUNCTION public.cuenta_cierre_confirmacion_guard_v34() OWNER TO confirmed_drift_owner"
    })
    void proofBindingIdempotencyLeaseOwnershipAndPartialUniquenessStayExact(String mutation) { assertDrift(mutation); }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE public.flyway_schema_history SET checksum=checksum+1 WHERE version='34'",
            "UPDATE public.flyway_schema_history SET script='V34__unexpected.sql' WHERE version='34'",
            "UPDATE public.flyway_schema_history SET type='BASELINE' WHERE version='34'",
            "UPDATE public.flyway_schema_history SET success=false WHERE version='34'",
            "DELETE FROM public.flyway_schema_history WHERE version='34'"
    })
    void exactV34CannotMasqueradeAsAnOlderRecordedSchema(String mutation) { assertDrift(mutation); }

    @Test
    void anAdditionalOverloadCannotHideUnderAnAllowedFunctionName() {
        assertDrift("CREATE FUNCTION public.cuenta_cierre_operacion_guard_v34(integer) RETURNS integer "
                + "LANGUAGE sql AS 'SELECT $1'");
    }

    @ParameterizedTest
    @MethodSource("newTables")
    void closureRowsAreNotAddedToThePhotoRole(String table) {
        try {
            photoOwner.execute("GRANT SELECT ON public." + table + " TO " + PHOTO_ROLE);
            assertThatThrownBy(() -> new LegalAcceptancePrivilegeVerifier(photoJdbc, PHOTO_ROLE, "public", true).verify())
                    .isInstanceOf(LegalEditorialOperationalException.class);
        } finally {
            photoOwner.execute("REVOKE SELECT ON public." + table + " FROM " + PHOTO_ROLE);
        }
        new LegalAcceptancePrivilegeVerifier(photoJdbc, PHOTO_ROLE, "public", true).verify();
        new LegalV29AcceptanceSchemaVerifier(photoJdbc, "public").verify();
    }

    private static void assertDrift(String mutation) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            jdbc.execute(mutation);
            assertThatThrownBy(() -> new LegalV29AcceptanceSchemaVerifier(jdbc, "public").verify())
                    .isInstanceOf(LegalEditorialOperationalException.class)
                    .satisfies(failure -> assertThat(((LegalEditorialOperationalException) failure).issue().code())
                            .isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT));
            status.setRollbackOnly();
        });
        new LegalV29AcceptanceSchemaVerifier(jdbc, "public").verify();
    }
}
