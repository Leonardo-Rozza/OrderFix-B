package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalV28AggregatePreFreezeSmokeIT {

    private static final String ROLE = "ordenfix_legal_aggregate_prefreeze_it";
    private static final String PASSWORD = "legal-aggregate-prefreeze-test-only";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_aggregate_prefreeze")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    @TempDir
    private static Path temporaryDirectory;

    private static JdbcTemplate restricted;
    private static LegalV28AggregateITSupport.AggregateHarness aggregate;

    @BeforeAll
    static void migrateSeedAndProvisionRestrictedRuntime() throws Exception {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("28")
                .load()
                .migrate();

        // Migration and V27 fixture setup are the only owner operations before runtime.
        DataSource ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
        ValidatedRelease release = LegalV28AggregateITSupport.releaseWithContinuedUse(
                temporaryDirectory,
                LegalV28AggregatePreFreezeSmokeIT.class,
                "aggregate-prefreeze-current");
        UUID publicationId = LegalV28AggregateITSupport.importRelease(
                ownerDataSource,
                release);
        LegalManifestPersistenceITSupport.promoteToReady(owner, publicationId);

        LegalRestrictedAggregateRoleFixture.Credentials credentials =
                new LegalRestrictedAggregateRoleFixture(
                        owner,
                        POSTGRES.getJdbcUrl(),
                        ROLE,
                        PASSWORD,
                        POSTGRES.getDriverClassName())
                        .provisionAndVerify();
        aggregate = LegalV28AggregateITSupport.aggregateHarness(
                LegalV28AggregateITSupport.dataSource(credentials),
                ROLE);
        restricted = aggregate.jdbc();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void restrictedSharedGateResolvesStoresAndReplaysAgainstTheCandidateV28() {
        aggregate.schema().verify();
        aggregate.privileges().verify();
        aggregate.gate().requireExactAggregatePreflights(
                restricted,
                aggregate.schema(),
                aggregate.privileges());
        assertThat(restricted.queryForObject(
                "SELECT current_user = ? AND session_user = ?",
                Boolean.class,
                ROLE,
                ROLE)).isTrue();

        assertExactAggregateSqlNamesAndTypes();
        LegalApplicableScopeSet scopes = new LegalApplicableScopeResolver().resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER);
        AtomicReference<LegalEditorialTimeBoundary> observedBoundary =
                new AtomicReference<>();
        AtomicReference<AdvisoryLockRow> observedLock = new AtomicReference<>();
        AtomicInteger runtimeBackendPid = new AtomicInteger();

        LegalRequiredSetAggregateReceipt receipt = aggregate.gate().executeMutableShared(
                (status, boundary) -> {
                    observedBoundary.set(boundary);
                    runtimeBackendPid.set(restricted.queryForObject(
                            "SELECT pg_catalog.pg_backend_pid()",
                            Integer.class));
                    List<AdvisoryLockRow> locks = restricted.query("""
                            WITH expected AS (
                                SELECT pg_catalog.hashtextextended(?, 0) AS key
                            )
                            SELECT held.locktype, held.mode, held.granted,
                                   held.objsubid,
                                   held.classid::bigint AS classid,
                                   held.objid::bigint AS objid,
                                   ((expected.key >> 32)
                                       & 4294967295::bigint) AS expected_classid,
                                   (expected.key
                                       & 4294967295::bigint) AS expected_objid
                              FROM pg_catalog.pg_locks held
                              CROSS JOIN expected
                             WHERE held.pid = pg_catalog.pg_backend_pid()
                               AND held.database = (
                                   SELECT database.oid
                                     FROM pg_catalog.pg_database database
                                    WHERE database.datname = pg_catalog.current_database()
                               )
                               AND held.locktype = 'advisory'
                               AND held.granted
                               AND held.classid::bigint = (
                                   (expected.key >> 32) & 4294967295::bigint
                               )
                               AND held.objid::bigint = (
                                   expected.key & 4294967295::bigint
                               )
                            """, (resultSet, rowNumber) -> new AdvisoryLockRow(
                            resultSet.getString("locktype"),
                            resultSet.getString("mode"),
                            resultSet.getBoolean("granted"),
                            resultSet.getInt("objsubid"),
                            resultSet.getLong("classid"),
                            resultSet.getLong("objid"),
                            resultSet.getLong("expected_classid"),
                            resultSet.getLong("expected_objid")),
                            LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
                    assertThat(locks).hasSize(1);
                    observedLock.set(locks.getFirst());
                    return aggregate.store().materialize(scopes, boundary);
                });

        assertThat(scopes.profile()).isEqualTo(PerfilAgregadoLegal.AUTHENTICATED_PENDING);
        assertThat(scopes.locale()).isEqualTo(LocaleLegal.ES_AR);
        assertThat(scopes.audience()).isEqualTo(AudienciaLegal.USER);
        assertThat(scopes.contexts())
                .containsExactly(ContextoLegal.USO_CONTINUADO);
        assertThat(receipt.outcome())
                .isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertThat(receipt.createdAt())
                .isAfterOrEqualTo(observedBoundary.get().observedAt());
        assertThat(observedBoundary.get().transactionAt())
                .isBeforeOrEqualTo(observedBoundary.get().observedAt());
        assertThat(receipt.createdAt().getNano() % 1_000).isZero();
        assertThat(receipt.provenance().scopes()).hasSize(1);

        AdvisoryLockRow lock = observedLock.get();
        assertThat(lock.lockType()).isEqualTo("advisory");
        assertThat(lock.mode()).isEqualTo("ShareLock");
        assertThat(lock.granted()).isTrue();
        assertThat(lock.objectSubId()).isEqualTo(1);
        assertThat(lock.classId()).isEqualTo(lock.expectedClassId());
        assertThat(lock.objectId()).isEqualTo(lock.expectedObjectId());
        assertThat(restricted.queryForObject("""
                SELECT count(*)
                  FROM pg_catalog.pg_locks held
                 WHERE held.pid = ?
                   AND held.locktype = 'advisory'
                   AND held.classid::bigint = ?
                   AND held.objid::bigint = ?
                """, Long.class,
                runtimeBackendPid.get(),
                lock.expectedClassId(),
                lock.expectedObjectId())).isZero();

        Throwable sessionLockAttempt = catchThrowable(() -> restricted.queryForObject(
                "SELECT pg_catalog.pg_try_advisory_lock(1::bigint)",
                Boolean.class));
        LegalV28AggregateITSupport.assertSqlState(sessionLockAttempt, "42501");
        assertThat(restricted.queryForObject("""
                SELECT count(*) = 1
                  FROM legal_requisito_agregados header
                  JOIN legal_requisito_agregado_scopes scope
                    ON scope.agregado_id = header.id
                 WHERE header.id = ?
                   AND header.perfil = 'AUTHENTICATED_PENDING'
                   AND header.locale = 'es-AR'
                   AND header.audiencia = 'USER'
                   AND header.revision_scheme = 'AGGREGATE_V1'
                   AND header.required_set_revision = ?
                   AND header.provenance_fingerprint = ?
                   AND header.scope_count = 1
                   AND scope.scope_ordinal = 1
                   AND scope.contexto = 'USO_CONTINUADO'
                   AND scope.locale = header.locale
                   AND scope.audiencia = header.audiencia
                """, Boolean.class,
                receipt.aggregateId(),
                receipt.requiredSetRevision(),
                receipt.provenanceFingerprint())).isTrue();
    }

    private static void assertExactAggregateSqlNamesAndTypes() {
        List<ColumnDefinition> columns = restricted.query("""
                SELECT relation.relname,
                       attribute.attname,
                       pg_catalog.format_type(
                           attribute.atttypid,
                           attribute.atttypmod) AS sql_type
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_attribute attribute
                    ON attribute.attrelid = relation.oid
                   AND attribute.attnum > 0
                   AND NOT attribute.attisdropped
                 WHERE namespace.nspname = 'public'
                   AND relation.relkind = 'r'
                   AND relation.relname IN (
                       'legal_requisito_agregados',
                       'legal_requisito_agregado_scopes'
                   )
                 ORDER BY CASE relation.relname
                              WHEN 'legal_requisito_agregados' THEN 1
                              ELSE 2
                          END,
                          attribute.attnum
                """, (resultSet, rowNumber) -> new ColumnDefinition(
                resultSet.getString("relname"),
                resultSet.getString("attname"),
                resultSet.getString("sql_type")));
        assertThat(columns).containsExactly(
                column("legal_requisito_agregados", "id", "uuid"),
                column("legal_requisito_agregados", "perfil", "character varying(30)"),
                column("legal_requisito_agregados", "locale", "character varying(5)"),
                column("legal_requisito_agregados", "audiencia", "character varying(20)"),
                column("legal_requisito_agregados", "revision_scheme", "character varying(20)"),
                column("legal_requisito_agregados", "required_set_revision", "character varying(71)"),
                column("legal_requisito_agregados", "provenance_fingerprint", "character varying(71)"),
                column("legal_requisito_agregados", "scope_count", "integer"),
                column("legal_requisito_agregados", "creado_en", "timestamp with time zone"),
                column("legal_requisito_agregado_scopes", "agregado_id", "uuid"),
                column("legal_requisito_agregado_scopes", "scope_ordinal", "integer"),
                column("legal_requisito_agregado_scopes", "contexto", "character varying(40)"),
                column("legal_requisito_agregado_scopes", "conjunto_id", "uuid"),
                column("legal_requisito_agregado_scopes", "publicacion_id", "uuid"),
                column("legal_requisito_agregado_scopes", "locale", "character varying(5)"),
                column("legal_requisito_agregado_scopes", "audiencia", "character varying(20)"),
                column("legal_requisito_agregado_scopes", "required_set_revision", "character varying(71)"));
    }

    private static ColumnDefinition column(String table, String name, String type) {
        return new ColumnDefinition(table, name, type);
    }

    private record AdvisoryLockRow(
            String lockType,
            String mode,
            boolean granted,
            int objectSubId,
            long classId,
            long objectId,
            long expectedClassId,
            long expectedObjectId
    ) { }

    private record ColumnDefinition(String table, String name, String sqlType) { }
}
