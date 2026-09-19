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

/** V36 admits only the exact new history. No equipment data capability is added to legal/photo roles. */
class LegalV36CompatibilityIT {
    private static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_v36").withUsername("owner").withPassword("v36-synthetic");
    private static final String ROLE="ordenfix_photo_v36";
    private static DataSource source;
    private static JdbcTemplate owner,photo;

    @BeforeAll static void migrate() {
        PG.start();source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("36").load().migrate();
        owner=new JdbcTemplate(source);
        var role=LegalRestrictedAcceptanceRoleFixture.provision(owner,ROLE,"v36-photo-role");
        owner.execute("GRANT SELECT,INSERT ON public.reparacion_fotos_privadas,public.reparacion_foto_atestaciones TO "+ROLE);
        owner.execute("GRANT UPDATE(estado,asset_id,asset_version,lease_id,lease_hasta,asociada_en) ON public.reparacion_fotos_privadas TO "+ROLE);
        owner.execute("GRANT SELECT(id,taller_id),UPDATE(id) ON public.reparaciones TO "+ROLE);
        owner.execute("GRANT SELECT(cierre_estado) ON public.talleres TO "+ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION public.foto_privada_insert_guard_v30(),public.foto_atestacion_insert_guard_v30(),public.foto_privada_completa_v30(),public.foto_privada_update_guard_v30() TO "+ROLE);
        LegalPrivatePhotoOperationsIT.grantDeletionEvidence(owner,ROLE);
        photo=new JdbcTemplate(new DriverManagerDataSource(role.jdbcUrl(),role.username(),role.password()));
    }
    @AfterAll static void stop(){PG.stop();}

    @Test void exactV36PreservesEveryFrozenLegalClosureAndPhotoCatalog() {
        var verifier=new LegalV29AcceptanceSchemaVerifier(owner,"public");
        assertThat(verifier.workshopClosureSchemaVersion()).isEqualTo(36);
        assertThat(verifier.usesPhotoDeletionSchema()).isTrue();
        assertThat(verifier.snapshot().catalog()).isEqualTo(LegalV34ClosureSchema.LEGAL_CATALOG);
        assertThat(LegalV33ClosureSchema.snapshot(owner)).isEqualTo(LegalV35PhotoDeletionSchema.V33_DELTA);
        assertThat(LegalV34ClosureSchema.snapshot(owner)).isEqualTo(LegalV34ClosureSchema.EXPECTED);
        assertThat(LegalV35PhotoDeletionSchema.snapshot(owner)).isEqualTo(LegalV35PhotoDeletionSchema.EXPECTED);
        assertThat(LegalPrivatePhotoSchema.snapshot(owner)).isEqualTo(LegalV35PhotoDeletionSchema.PHOTO_CATALOG);
        new LegalV27SchemaVerifier(owner,"public").verify();
        new LegalV27ImportSchemaVerifier(owner,"public").verify();
        new LegalEditorialSchemaVerifier(owner,"public").verify();
        new LegalV28AggregateSchemaVerifier(owner,"public").verify();
        verifier.verify();
        new LegalRegistrationSchemaVerifier(owner,"public").verify();
        LegalPrivatePhotoSchema.requireDeletionReceipts(photo);
    }
    @Test void thePhotoRoleKeepsV35CapabilitiesWithoutReadingOrWritingEquipmentType() {
        new LegalAcceptancePrivilegeVerifier(photo,ROLE,"public",true).verify();
        assertThat(photo.queryForObject("SELECT has_column_privilege(current_user,'equipos','tipo','SELECT')",Boolean.class)).isFalse();
        assertThat(photo.queryForObject("SELECT has_column_privilege(current_user,'equipos','tipo','UPDATE')",Boolean.class)).isFalse();
        assertThat(photo.queryForObject("SELECT has_table_privilege(current_user,'equipos','INSERT')",Boolean.class)).isFalse();
        assertThat(photo.queryForObject("SELECT has_table_privilege(current_user,'reparacion_foto_eliminaciones','INSERT')",Boolean.class)).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={
            "UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='36'",
            "UPDATE flyway_schema_history SET script='V36__unexpected.sql' WHERE version='36'",
            "UPDATE flyway_schema_history SET type='BASELINE' WHERE version='36'",
            "UPDATE flyway_schema_history SET success=false WHERE version='36'",
            "DELETE FROM flyway_schema_history WHERE version='35'",
            "INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) SELECT max(installed_rank)+1,'37','unknown','SQL','V37__unknown.sql',1,current_user,1,true FROM flyway_schema_history"
    })
    void alteredOrFutureMigrationHistoryFailsClosed(String mutation) {
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
    @Test void independentEquipmentColumnDoesNotReplaceTheHistoricalLegalBoundary() {
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status->{
            // Like the independent V31 account extension, this business column is owned by
            // application Flyway/JPA and EquipmentTypeMigrationIT, not by legal-role accreditation.
            owner.execute("DELETE FROM flyway_schema_history WHERE version='36'");
            var verifier=new LegalV29AcceptanceSchemaVerifier(owner,"public");
            assertThat(verifier.workshopClosureSchemaVersion()).isEqualTo(35);
            verifier.verify();LegalPrivatePhotoSchema.requireDeletionReceipts(owner);
            status.setRollbackOnly();
        });
        new LegalV29AcceptanceSchemaVerifier(owner,"public").verify();
    }
}
