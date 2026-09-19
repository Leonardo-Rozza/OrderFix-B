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

/** Fresh PostgreSQL 16; exact delta, restricted capabilities and drift rejection without provider effects. */
class LegalV35PhotoDeletionSchemaVerifierIT {
    private static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_photo_receipts").withUsername("owner").withPassword("receipt-fixture");
    private static final String ROLE="ordenfix_photo_receipt_schema";
    private static DataSource source;
    private static JdbcTemplate owner,restricted;

    @BeforeAll static void migrate() {
        PG.start();source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("35").load().migrate();
        owner=new JdbcTemplate(source);
        var role=LegalRestrictedAcceptanceRoleFixture.provision(owner,ROLE,"receipt-fixture-role");
        owner.execute("GRANT SELECT,INSERT ON public.reparacion_fotos_privadas,public.reparacion_foto_atestaciones TO "+ROLE);
        owner.execute("GRANT UPDATE(estado,asset_id,asset_version,lease_id,lease_hasta,asociada_en) ON public.reparacion_fotos_privadas TO "+ROLE);
        owner.execute("GRANT SELECT(id,taller_id),UPDATE(id) ON public.reparaciones TO "+ROLE);
        owner.execute("GRANT SELECT(cierre_estado) ON public.talleres TO "+ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION public.foto_privada_insert_guard_v30(),public.foto_atestacion_insert_guard_v30(),public.foto_privada_completa_v30(),public.foto_privada_update_guard_v30() TO "+ROLE);
        LegalPrivatePhotoOperationsIT.grantDeletionEvidence(owner,ROLE);
        restricted=new JdbcTemplate(new DriverManagerDataSource(role.jdbcUrl(),role.username(),role.password()));
    }
    @AfterAll static void stop(){PG.stop();}

    @Test void everyHistoricalConsumerAccreditsTheV35DeltaAndRestrictedPhotoCapabilities() {
        assertThat(new LegalV29AcceptanceSchemaVerifier(owner,"public").workshopClosureSchemaVersion()).isEqualTo(35);
        assertThat(new LegalV29AcceptanceSchemaVerifier(owner,"public").snapshot().catalog()).isEqualTo(LegalV34ClosureSchema.LEGAL_CATALOG);
        assertThat(LegalV33ClosureSchema.snapshot(owner)).isEqualTo(LegalV35PhotoDeletionSchema.V33_DELTA);
        assertThat(LegalV34ClosureSchema.snapshot(owner)).isEqualTo(LegalV34ClosureSchema.EXPECTED);
        assertThat(LegalV35PhotoDeletionSchema.snapshot(owner)).isEqualTo(LegalV35PhotoDeletionSchema.EXPECTED);
        new LegalV27SchemaVerifier(owner,"public").verify();
        new LegalV27ImportSchemaVerifier(owner,"public").verify();
        new LegalEditorialSchemaVerifier(owner,"public").verify();
        new LegalV28AggregateSchemaVerifier(owner,"public").verify();
        new LegalV29AcceptanceSchemaVerifier(restricted,"public").verify();
        new LegalRegistrationSchemaVerifier(owner,"public").verify();
        LegalPrivatePhotoSchema.verify(restricted);
        privileges();
        assertThat(owner.queryForObject("SELECT count(*) FROM reparacion_foto_eliminaciones",Long.class)).isZero();
        assertThat(restricted.queryForObject("SELECT has_table_privilege(current_user,'reparacion_foto_eliminaciones','DELETE')",Boolean.class)).isFalse();
        for(String function:LegalV35PhotoDeletionSchema.FUNCTIONS)
            assertThat(restricted.queryForObject("SELECT has_function_privilege(current_user,?,'EXECUTE')",Boolean.class,"public."+function+"()")).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={
            "ALTER TABLE reparacion_foto_eliminaciones ADD COLUMN untrusted text",
            "ALTER TABLE reparacion_foto_eliminaciones ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE reparacion_foto_eliminaciones DISABLE TRIGGER aa_foto_eliminacion_v35",
            "ALTER TABLE reparacion_foto_eliminaciones DISABLE TRIGGER ct_foto_eliminacion_completa_v35",
            "ALTER TABLE reparacion_fotos_privadas DISABLE TRIGGER ab_foto_eliminada_recibo_v35",
            "ALTER TABLE reparacion_foto_eliminaciones DROP CONSTRAINT fk_foto_eliminacion_actor",
            "ALTER TABLE reparacion_foto_eliminaciones DROP CONSTRAINT ck_foto_eliminacion_identidad",
            "ALTER TABLE reparacion_foto_eliminaciones DROP CONSTRAINT ck_foto_eliminacion_resultado",
            "ALTER TABLE reparacion_foto_eliminaciones DROP CONSTRAINT ck_foto_eliminacion_fechas",
            "DROP INDEX idx_foto_eliminacion_taller",
            "GRANT SELECT ON reparacion_foto_eliminaciones TO PUBLIC",
            "GRANT UPDATE(asset_id) ON reparacion_foto_eliminaciones TO PUBLIC",
            "ALTER FUNCTION foto_eliminacion_guard_v35() SECURITY INVOKER",
            "ALTER FUNCTION foto_eliminacion_completa_v35() RESET search_path",
            "GRANT EXECUTE ON FUNCTION foto_eliminada_recibo_guard_v35() TO PUBLIC",
            "CREATE FUNCTION foto_eliminacion_guard_v35(integer) RETURNS integer LANGUAGE sql AS 'SELECT $1'",
            "UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='35'",
            "UPDATE flyway_schema_history SET script='V35__unexpected.sql' WHERE version='35'",
            "UPDATE flyway_schema_history SET type='BASELINE' WHERE version='35'",
            "UPDATE flyway_schema_history SET success=false WHERE version='35'"
    })
    void weakenedCatalogOrHistoryCannotAccreditReceipts(String mutation) {
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status->{
            owner.execute(mutation);
            assertThatThrownBy(()->new LegalV29AcceptanceSchemaVerifier(owner,"public").verify())
                    .isInstanceOf(LegalEditorialOperationalException.class)
                    .satisfies(failure->assertThat(((LegalEditorialOperationalException)failure).issue().code()).isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT));
            status.setRollbackOnly();
        });
        new LegalV29AcceptanceSchemaVerifier(owner,"public").verify();
    }

    @Test void deletingV35HistoryCannotMasqueradeAsAnExactV34PhotoSchema() {
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status->{
            owner.execute("DELETE FROM flyway_schema_history WHERE version='35'");
            assertThatThrownBy(()->new LegalV29AcceptanceSchemaVerifier(owner,"public").verify())
                    .isInstanceOf(IllegalStateException.class).hasMessage("Esquema de fotos privadas incompatible");
            status.setRollbackOnly();
        });
        new LegalV29AcceptanceSchemaVerifier(owner,"public").verify();
    }

    @ParameterizedTest @ValueSource(strings={"DELETE","TRUNCATE","UPDATE(user_id)","UPDATE(object_key)","UPDATE(creada_en)"})
    void extraReceiptCapabilitiesAreRejected(String capability) {
        try {
            owner.execute("GRANT "+capability+" ON reparacion_foto_eliminaciones TO "+ROLE);
            assertThatThrownBy(this::privileges).isInstanceOf(LegalEditorialOperationalException.class);
        } finally {owner.execute("REVOKE "+capability+" ON reparacion_foto_eliminaciones FROM "+ROLE);}
        privileges();
    }
    @Test void restrictedRoleCannotTruncateDurableDeletionEvidence() {
        assertThatThrownBy(()->restricted.execute("TRUNCATE reparacion_foto_eliminaciones"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(failure->{
                    var sql=(org.postgresql.util.PSQLException)((org.springframework.dao.DataAccessException)failure).getMostSpecificCause();
                    assertThat(sql.getSQLState()).isEqualTo("42501");
                });
    }
    @Test void triggerOnlyDefinerCannotBecomeCallableByPhotoRole() {
        try {
            owner.execute("GRANT EXECUTE ON FUNCTION foto_eliminada_recibo_guard_v35() TO "+ROLE);
            assertThatThrownBy(this::privileges).isInstanceOf(LegalEditorialOperationalException.class);
        } finally {owner.execute("REVOKE EXECUTE ON FUNCTION foto_eliminada_recibo_guard_v35() FROM "+ROLE);}
        privileges();
    }
    private void privileges(){new LegalAcceptancePrivilegeVerifier(restricted,ROLE,"public",true).verify();}
}
