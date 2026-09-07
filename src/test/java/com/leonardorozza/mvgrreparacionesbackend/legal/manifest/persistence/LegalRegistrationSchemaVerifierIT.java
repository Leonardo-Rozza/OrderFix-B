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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each catalog mutation is genuine PostgreSQL DDL and is isolated by rollback. */
class LegalRegistrationSchemaVerifierIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_schema")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static final String ROLE = "ordenfix_registration_schema_consumer";
    private static DataSource ownerDataSource;
    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static LegalRegistrationSchemaVerifier verifier;

    @BeforeAll
    static void migrateAndProvision() {
        POSTGRES.start();
        ownerDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ownerDataSource).locations("classpath:db/migration").target("29").load().migrate();
        owner = new JdbcTemplate(ownerDataSource);
        var credentials = LegalRestrictedRegistrationRoleFixture.provision(owner, ROLE, "disposable-registration-schema");
        restricted = new JdbcTemplate(new DriverManagerDataSource(
                credentials.jdbcUrl(), credentials.username(), credentials.password()));
        verifier = new LegalRegistrationSchemaVerifier(owner, "public");
    }

    @AfterAll
    static void stop() { POSTGRES.stop(); }

    @Test
    void bothOwnerAndExactRestrictedConsumerAccreditTheCleanV29AndSubscriptionSurface() {
        verifier.verify();
        assertThat(restricted.queryForObject("SELECT current_user || ':' || session_user", String.class))
                .isEqualTo(ROLE + ":" + ROLE);
        new LegalRegistrationSchemaVerifier(restricted, "public").verify();
        assertThat(verifier.usesJdbc(owner)).isTrue();
        assertThat(verifier.usesJdbc(new JdbcTemplate(ownerDataSource))).isFalse();
        assertThat(verifier.expectedSchema()).isEqualTo("public");
        assertThat(owner.queryForObject("SELECT count(*) FROM suscripciones", Long.class)).isZero();
        assertThat(owner.queryForObject(
                "SELECT has_sequence_privilege(?, 'public.suscripciones_id_seq', 'USAGE')", Boolean.class, ROLE)).isFalse();
    }

    @Test
    void unsupportedSchemaIsRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> new LegalRegistrationSchemaVerifier(new JdbcTemplate(), "other_schema"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalRegistrationSchemaVerifier(new JdbcTemplate(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE suscripciones ALTER COLUMN plan DROP NOT NULL",
            "ALTER TABLE suscripciones ALTER COLUMN estado SET DEFAULT 'TRIAL'",
            "ALTER TABLE suscripciones ALTER COLUMN reparaciones_mes SET DEFAULT 1",
            "ALTER TABLE suscripciones ALTER COLUMN mp_status TYPE varchar(49)",
            "ALTER TABLE suscripciones ALTER COLUMN mp_next_payment_at TYPE timestamp without time zone",
            "ALTER TABLE suscripciones ALTER COLUMN id SET GENERATED ALWAYS",
            "ALTER TABLE suscripciones ALTER COLUMN id DROP IDENTITY",
            "ALTER TABLE suscripciones ADD COLUMN unapproved_extra integer",
            "ALTER TABLE suscripciones ADD COLUMN dropped_extra integer; ALTER TABLE suscripciones DROP COLUMN dropped_extra",
            "ALTER TABLE suscripciones DROP CONSTRAINT suscripciones_taller_id_key",
            "ALTER TABLE suscripciones DROP CONSTRAINT suscripciones_taller_id_fkey",
            "ALTER TABLE suscripciones ALTER CONSTRAINT suscripciones_taller_id_fkey DEFERRABLE INITIALLY DEFERRED",
            "ALTER TABLE suscripciones ADD CONSTRAINT unapproved_check CHECK (reparaciones_mes >= 0)",
            "ALTER INDEX suscripciones_pkey SET (fillfactor = 90)",
            "CREATE INDEX unapproved_registration_index ON suscripciones (estado)",
            "DROP INDEX uk_suscripciones_mp_external_reference",
            "ALTER TABLE suscripciones SET (fillfactor = 90)",
            "ALTER TABLE suscripciones ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE suscripciones FORCE ROW LEVEL SECURITY",
            "CREATE POLICY unapproved_registration_policy ON suscripciones USING (true)",
            "ALTER TABLE suscripciones DISABLE TRIGGER ALL",
            "CREATE TABLE registration_schema_child () INHERITS (suscripciones)",
            "CREATE TABLE registration_schema_parent (); ALTER TABLE suscripciones INHERIT registration_schema_parent",
            "CREATE RULE registration_schema_rule AS ON INSERT TO suscripciones DO INSTEAD NOTHING",
            "CREATE FUNCTION registration_schema_trigger() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$; "
                    + "CREATE TRIGGER unapproved_registration_trigger BEFORE INSERT ON suscripciones "
                    + "FOR EACH ROW EXECUTE FUNCTION registration_schema_trigger()",
            "ALTER TABLE subscription_provider_links ALTER CONSTRAINT fk_provider_link_suscripcion DEFERRABLE INITIALLY DEFERRED",
            "ALTER TABLE subscription_payments ALTER CONSTRAINT fk_subscription_payment_suscripcion DEFERRABLE INITIALLY DEFERRED",
            "ALTER SEQUENCE suscripciones_id_seq INCREMENT BY 2",
            "ALTER SEQUENCE suscripciones_id_seq CACHE 2",
            "ALTER SEQUENCE suscripciones_id_seq START WITH 3",
            "ALTER SEQUENCE suscripciones_id_seq CYCLE",
            "ALTER TABLE suscripciones RENAME TO registration_schema_renamed"
    })
    void subscriptionShapeAndTopologyDriftRejectBeforeTheCallersFirstWrite(String mutation) {
        assertDrift(mutation);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE users DISABLE TRIGGER USER",
            "ALTER TABLE talleres ALTER COLUMN activo DROP NOT NULL",
            "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '29'"
    })
    void registrationStillRequiresTheUnmodifiedCompleteV29Accreditation(String mutation) {
        assertDrift(mutation);
    }

    @Test
    void allocatingIdentityValuesDoesNotChangeTheAccreditedSchema() {
        owner.queryForObject("SELECT nextval('public.suscripciones_id_seq')", Long.class);
        owner.queryForObject("SELECT nextval('public.suscripciones_id_seq')", Long.class);
        verifier.verify();
        new LegalRegistrationSchemaVerifier(restricted, "public").verify();
        assertThat(owner.queryForObject("SELECT count(*) FROM suscripciones", Long.class)).isZero();
    }

    private static void assertDrift(String mutation) {
        Long workshops = owner.queryForObject("SELECT count(*) FROM talleres", Long.class);
        AtomicBoolean callerReachedWrite = new AtomicBoolean();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(ownerDataSource));
        transaction.executeWithoutResult(status -> {
            owner.execute(mutation);
            assertThatThrownBy(() -> {
                verifier.verify();
                callerReachedWrite.set(true);
                owner.update("INSERT INTO talleres(nombre) VALUES ('must not be reached after schema drift')");
            }).isInstanceOfSatisfying(LegalEditorialOperationalException.class,
                    failure -> assertThat(failure.issue().code()).isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT));
            assertThat(callerReachedWrite).isFalse();
            assertThat(owner.queryForObject("SELECT count(*) FROM talleres", Long.class)).isEqualTo(workshops);
            status.setRollbackOnly();
        });
        verifier.verify();
        assertThat(owner.queryForObject("SELECT count(*) FROM talleres", Long.class)).isEqualTo(workshops);
    }
}
