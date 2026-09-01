package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

class LegalRequiredSetAggregateIT {

    private static final String ROLE = "ordenfix_legal_aggregate_runtime_it";
    private static final String PASSWORD = "legal-aggregate-runtime-test-only";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_aggregate_runtime")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    @TempDir
    private static Path temporaryDirectory;

    private static LegalV28AggregateITSupport.AggregateHarness aggregate;

    @BeforeAll
    static void migrateSeedAndAssembleRestrictedRuntime() throws Exception {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DataSource ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
        ValidatedRelease release = LegalV28AggregateITSupport.releaseWithContinuedUse(
                temporaryDirectory,
                LegalRequiredSetAggregateIT.class,
                "aggregate-runtime-current");
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
        aggregate = tracedAggregateHarness(
                LegalV28AggregateITSupport.dataSource(credentials),
                ROLE);
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void sharedGateLocksInOneStatementBeforeStoreInsertAndRealReplayCreatesThenReuses() {
        LegalApplicableScopeSet scopes = new LegalApplicableScopeResolver().resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER);
        clearInvocations(aggregate.jdbc());

        LegalRequiredSetAggregateReceipt created = aggregate.gate().executeMutableShared(
                (status, boundary) -> aggregate.store().materialize(scopes, boundary));
        List<SqlInvocation> createdTrace = sqlTrace(aggregate.jdbc());
        clearInvocations(aggregate.jdbc());
        LegalRequiredSetAggregateReceipt reused = aggregate.gate().executeMutableShared(
                (status, boundary) -> aggregate.store().materialize(scopes, boundary));

        assertThat(created.outcome())
                .isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertThat(reused.outcome())
                .isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.REUSED);
        assertThat(reused.aggregateId()).isEqualTo(created.aggregateId());
        assertThat(reused.requiredSetRevision()).isEqualTo(created.requiredSetRevision());
        assertThat(reused.provenanceFingerprint()).isEqualTo(created.provenanceFingerprint());
        assertThat(reused.provenance()).isEqualTo(created.provenance());
        assertThat(reused.createdAt()).isEqualTo(created.createdAt());

        int lockIndex = firstSqlContaining(
                createdTrace,
                "pg_catalog.pg_advisory_xact_lock_shared(");
        int insertIndex = firstSqlStartingWith(
                createdTrace,
                "INSERT INTO legal_requisito_agregados");
        assertThat(lockIndex).isNotNegative().isLessThan(insertIndex);
        assertThat(createdTrace.get(lockIndex).method()).isEqualTo("queryForList");
        assertThat(createdTrace.get(insertIndex).method()).isEqualTo("query");
        assertThat(createdTrace.get(lockIndex).sql())
                .doesNotContain("INSERT INTO legal_requisito_agregados");
        assertThat(createdTrace.get(insertIndex).sql())
                .doesNotContain("pg_advisory_xact_lock_shared");
        assertThat(createdTrace).noneMatch(invocation ->
                invocation.sql().contains("pg_advisory_xact_lock_shared")
                        && invocation.sql().contains(
                                "INSERT INTO legal_requisito_agregados"));

        assertThat(createdTrace)
                .anySatisfy(invocation -> assertThat(invocation.sql())
                        .isEqualTo(LegalJdbcMetricsSupport.normalizeSql(
                                LegalRequiredSetAggregateReplayVerifier.HEADER_SQL)));
        assertThat(createdTrace)
                .anySatisfy(invocation -> assertThat(invocation.sql())
                        .isEqualTo(LegalJdbcMetricsSupport.normalizeSql(
                                LegalRequiredSetAggregateReplayVerifier.MEMBERS_SQL)));
        assertThat(aggregate.jdbc().queryForObject(
                "SELECT count(*) FROM legal_requisito_agregados WHERE id = ?",
                Long.class,
                created.aggregateId())).isEqualTo(1L);
        assertThat(aggregate.jdbc().queryForObject(
                "SELECT count(*) FROM legal_requisito_agregado_scopes WHERE agregado_id = ?",
                Long.class,
                created.aggregateId())).isEqualTo(1L);
    }

    private static LegalV28AggregateITSupport.AggregateHarness tracedAggregateHarness(
            DataSource dataSource,
            String expectedRole) {
        JdbcTemplate jdbc = spy(new JdbcTemplate(
                Objects.requireNonNull(dataSource, "dataSource")));
        LegalDatabaseBudgets budgets = LegalDatabaseBudgets.production();
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("legal-required-set-aggregate-order-it");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(budgets.transactionTimeoutSeconds());
        transaction.setReadOnly(false);

        LegalV28AggregateSchemaVerifier schema = new LegalV28AggregateSchemaVerifier(
                jdbc,
                LegalV28AggregateInventory.DEFAULT_SCHEMA);
        LegalV28AggregatePrivilegeVerifier privileges =
                new LegalV28AggregatePrivilegeVerifier(
                        jdbc,
                        Objects.requireNonNull(expectedRole, "expectedRole"),
                        LegalV28AggregateInventory.DEFAULT_SCHEMA);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                budgets,
                List.of(schema, privileges));
        gate.requireExactAggregatePreflights(jdbc, schema, privileges);

        LegalRequiredSetAggregateRevisionCalculator semantic =
                new LegalRequiredSetAggregateRevisionCalculator();
        LegalRequiredSetAggregateProvenanceCalculator provenance =
                new LegalRequiredSetAggregateProvenanceCalculator();
        LegalRequiredSetAggregateReplayVerifier replay =
                new LegalRequiredSetAggregateReplayVerifier(jdbc, semantic, provenance);
        LegalRequiredSetAggregateStore store = new LegalRequiredSetAggregateStore(
                jdbc,
                semantic,
                provenance,
                replay);
        assertThat(gate.usesJdbc(jdbc)).isTrue();
        assertThat(replay.usesJdbc(jdbc)).isTrue();
        assertThat(store.usesJdbc(jdbc)).isTrue();
        return new LegalV28AggregateITSupport.AggregateHarness(
                jdbc,
                transaction,
                schema,
                privileges,
                gate,
                replay,
                store);
    }

    private static List<SqlInvocation> sqlTrace(JdbcTemplate jdbc) {
        List<Invocation> invocations = new ArrayList<>(
                mockingDetails(jdbc).getInvocations());
        invocations.sort(Comparator.comparingInt(Invocation::getSequenceNumber));
        List<SqlInvocation> trace = new ArrayList<>();
        for (Invocation invocation : invocations) {
            Object[] arguments = invocation.getArguments();
            if (arguments.length > 0 && arguments[0] instanceof String sql) {
                trace.add(new SqlInvocation(
                        invocation.getMethod().getName(),
                        LegalJdbcMetricsSupport.normalizeSql(sql)));
            }
        }
        return List.copyOf(trace);
    }

    private static int firstSqlContaining(List<SqlInvocation> trace, String fragment) {
        for (int index = 0; index < trace.size(); index++) {
            if (trace.get(index).sql().contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    private static int firstSqlStartingWith(List<SqlInvocation> trace, String prefix) {
        for (int index = 0; index < trace.size(); index++) {
            if (trace.get(index).sql().startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    private record SqlInvocation(String method, String sql) { }
}
