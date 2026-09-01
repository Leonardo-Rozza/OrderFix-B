package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

class LegalV28AggregatePersistenceIT {

    private static final String ROLE = "ordenfix_legal_aggregate_persistence_it";
    private static final String PASSWORD = "legal-aggregate-persistence-test-only";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_aggregate_persistence")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    @TempDir
    private static Path temporaryDirectory;

    private static JdbcTemplate restricted;
    private static LegalV28AggregateITSupport.AggregateHarness aggregate;
    private static UUID currentPublicationId;
    private static UUID stalePublicationId;

    @BeforeAll
    static void migrateSeedAndProvisionRestrictedRuntime() throws Exception {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // The owner is confined to Flyway and deterministic V27 fixture provisioning.
        DataSource ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
        ValidatedRelease current = LegalV28AggregateITSupport.releaseWithContinuedUse(
                temporaryDirectory,
                LegalV28AggregatePersistenceIT.class,
                "aggregate-persistence-current");
        currentPublicationId = LegalV28AggregateITSupport.importRelease(
                ownerDataSource,
                current);
        LegalManifestPersistenceITSupport.promoteToReady(owner, currentPublicationId);

        ValidatedRelease stale = LegalV28AggregateITSupport.releaseWithContinuedUse(
                temporaryDirectory,
                LegalV28AggregatePersistenceIT.class,
                "aggregate-persistence-stale");
        stalePublicationId = LegalV28AggregateITSupport.importRelease(
                ownerDataSource,
                stale);

        LegalRestrictedAggregateRoleFixture.Credentials credentials =
                new LegalRestrictedAggregateRoleFixture(
                        owner,
                        POSTGRES.getJdbcUrl(),
                        ROLE,
                        PASSWORD,
                        POSTGRES.getDriverClassName())
                        .provisionAndVerify();
        DataSource restrictedDataSource = LegalV28AggregateITSupport.dataSource(credentials);
        aggregate = LegalV28AggregateITSupport.aggregateHarness(
                restrictedDataSource,
                ROLE);
        restricted = aggregate.jdbc();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void materializesAndReusesTheExactCanonicalVectorAfterTheSharedLock() {
        LegalApplicableScopeSet scopes = new LegalApplicableScopeResolver(
                (profile, audience) -> List.of(
                        ContextoLegal.REGISTRO,
                        ContextoLegal.USO_CONTINUADO))
                .resolve(
                        PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                        LocaleLegal.ES_AR,
                        AudienciaLegal.ADMIN_TITULAR);
        AtomicReference<LegalEditorialTimeBoundary> firstBoundary = new AtomicReference<>();

        LegalRequiredSetAggregateReceipt created = aggregate.gate().executeMutableShared(
                (status, boundary) -> {
                    firstBoundary.set(boundary);
                    return aggregate.store().materialize(scopes, boundary);
                });
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
        assertThat(created.createdAt())
                .isAfterOrEqualTo(firstBoundary.get().observedAt());
        assertThat(firstBoundary.get().transactionAt())
                .isBeforeOrEqualTo(firstBoundary.get().observedAt());

        LegalV28AggregateITSupport.HeaderRow header = restricted.queryForObject("""
                SELECT id, perfil, locale, audiencia, revision_scheme,
                       required_set_revision, provenance_fingerprint, scope_count, creado_en
                  FROM legal_requisito_agregados
                 WHERE id = ?
                """, (resultSet, rowNumber) -> new LegalV28AggregateITSupport.HeaderRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("perfil"),
                resultSet.getString("locale"),
                resultSet.getString("audiencia"),
                resultSet.getString("revision_scheme"),
                resultSet.getString("required_set_revision"),
                resultSet.getString("provenance_fingerprint"),
                resultSet.getInt("scope_count"),
                resultSet.getObject("creado_en", java.time.OffsetDateTime.class).toInstant()),
                created.aggregateId());
        assertThat(header).isEqualTo(new LegalV28AggregateITSupport.HeaderRow(
                created.aggregateId(),
                PerfilAgregadoLegal.AUTHENTICATED_PENDING.name(),
                LocaleLegal.ES_AR.getCodigo(),
                AudienciaLegal.ADMIN_TITULAR.name(),
                "AGGREGATE_V1",
                created.requiredSetRevision(),
                created.provenanceFingerprint(),
                2,
                created.createdAt()));

        List<LegalV28AggregateITSupport.ScopeRow> persisted = restricted.query("""
                SELECT scope_ordinal, contexto, conjunto_id, publicacion_id,
                       locale, audiencia, required_set_revision
                  FROM legal_requisito_agregado_scopes
                 WHERE agregado_id = ?
                 ORDER BY scope_ordinal
                """, (resultSet, rowNumber) -> new LegalV28AggregateITSupport.ScopeRow(
                resultSet.getInt("scope_ordinal"),
                ContextoLegal.valueOf(resultSet.getString("contexto")),
                resultSet.getObject("conjunto_id", UUID.class),
                resultSet.getObject("publicacion_id", UUID.class),
                resultSet.getString("locale"),
                resultSet.getString("audiencia"),
                resultSet.getString("required_set_revision")),
                created.aggregateId());
        assertThat(persisted)
                .extracting(
                        LegalV28AggregateITSupport.ScopeRow::ordinal,
                        LegalV28AggregateITSupport.ScopeRow::context)
                .containsExactly(
                        tuple(1, ContextoLegal.REGISTRO),
                        tuple(2, ContextoLegal.USO_CONTINUADO));
        assertThat(persisted).allSatisfy(scope -> {
            LegalV28AggregateITSupport.Pointer pointer =
                    LegalV28AggregateITSupport.currentPointer(
                            restricted,
                            scope.context(),
                            AudienciaLegal.ADMIN_TITULAR);
            assertThat(scope.requiredSetId()).isEqualTo(pointer.requiredSetId());
            assertThat(scope.publicationId()).isEqualTo(pointer.publicationId());
            assertThat(scope.locale()).isEqualTo(pointer.locale());
            assertThat(scope.audience()).isEqualTo(pointer.audience());
            assertThat(scope.requiredSetRevision()).isEqualTo(pointer.requiredSetRevision());
        });
        assertThat(created.provenance().scopes())
                .extracting(
                        origin -> origin.context(),
                        origin -> origin.requiredSetId(),
                        origin -> origin.publicationId())
                .containsExactly(
                        tuple(
                                ContextoLegal.REGISTRO,
                                persisted.get(0).requiredSetId(),
                                persisted.get(0).publicationId()),
                        tuple(
                                ContextoLegal.USO_CONTINUADO,
                                persisted.get(1).requiredSetId(),
                                persisted.get(1).publicationId()));

        assertUuidAggregateTablesOwnNoSequence();
    }

    @Test
    void deferredCardinalityAndCanonicalOrdinalFailuresRollbackTheWholeAggregate() {
        LegalV28AggregateITSupport.Pointer registration =
                LegalV28AggregateITSupport.currentPointer(
                        restricted,
                        ContextoLegal.REGISTRO,
                        AudienciaLegal.ADMIN_TITULAR);
        LegalV28AggregateITSupport.Pointer continuedUse =
                LegalV28AggregateITSupport.currentPointer(
                        restricted,
                        ContextoLegal.USO_CONTINUADO,
                        AudienciaLegal.ADMIN_TITULAR);

        UUID partialId = UUID.randomUUID();
        AtomicBoolean partialCallbackCompleted = new AtomicBoolean();
        Throwable partialFailure = catchThrowable(() ->
                aggregate.gate().executeMutableShared((status, boundary) -> {
                    LegalV28AggregateITSupport.insertHeader(
                            restricted,
                            partialId,
                            PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                            AudienciaLegal.ADMIN_TITULAR,
                            LegalV28AggregateITSupport.digest('1'),
                            LegalV28AggregateITSupport.digest('2'),
                            2);
                    LegalV28AggregateITSupport.insertScope(
                            restricted,
                            partialId,
                            1,
                            registration);
                    partialCallbackCompleted.set(true);
                    return null;
                }));
        assertThat(partialCallbackCompleted).isTrue();
        LegalV28AggregateITSupport.assertSqlState(partialFailure, "23514");
        assertRolledBack(partialId);

        UUID ordinalId = UUID.randomUUID();
        AtomicBoolean ordinalCallbackCompleted = new AtomicBoolean();
        Throwable ordinalFailure = catchThrowable(() ->
                aggregate.gate().executeMutableShared((status, boundary) -> {
                    LegalV28AggregateITSupport.insertHeader(
                            restricted,
                            ordinalId,
                            PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                            AudienciaLegal.ADMIN_TITULAR,
                            LegalV28AggregateITSupport.digest('3'),
                            LegalV28AggregateITSupport.digest('4'),
                            2);
                    LegalV28AggregateITSupport.insertScope(
                            restricted,
                            ordinalId,
                            1,
                            registration);
                    LegalV28AggregateITSupport.insertScope(
                            restricted,
                            ordinalId,
                            3,
                            continuedUse);
                    ordinalCallbackCompleted.set(true);
                    return null;
                }));
        assertThat(ordinalCallbackCompleted).isTrue();
        LegalV28AggregateITSupport.assertSqlState(ordinalFailure, "23514");
        assertRolledBack(ordinalId);
    }

    @Test
    void stalePointerAndMissingLockFailClosedWithoutLeavingPartialRows() {
        LegalV28AggregateITSupport.Pointer stale =
                LegalV28AggregateITSupport.publicationPointer(
                        restricted,
                        stalePublicationId,
                        ContextoLegal.REGISTRO,
                        AudienciaLegal.ADMIN_TITULAR);
        LegalV28AggregateITSupport.Pointer current =
                LegalV28AggregateITSupport.currentPointer(
                        restricted,
                        ContextoLegal.REGISTRO,
                        AudienciaLegal.ADMIN_TITULAR);
        assertThat(stale.publicationId()).isEqualTo(stalePublicationId);
        assertThat(current.publicationId()).isEqualTo(currentPublicationId);
        assertThat(stale.requiredSetId()).isNotEqualTo(current.requiredSetId());

        UUID staleAggregateId = UUID.randomUUID();
        Throwable staleFailure = catchThrowable(() ->
                aggregate.gate().executeMutableShared((status, boundary) -> {
                    LegalV28AggregateITSupport.insertHeader(
                            restricted,
                            staleAggregateId,
                            PerfilAgregadoLegal.REGISTRATION,
                            AudienciaLegal.ADMIN_TITULAR,
                            LegalV28AggregateITSupport.digest('5'),
                            LegalV28AggregateITSupport.digest('6'),
                            1);
                    LegalV28AggregateITSupport.insertScope(
                            restricted,
                            staleAggregateId,
                            1,
                            stale);
                    return null;
                }));
        LegalV28AggregateITSupport.assertSqlState(staleFailure, "23514");
        assertRolledBack(staleAggregateId);

        UUID unlockedId = UUID.randomUUID();
        Throwable unlockedFailure = catchThrowable(() ->
                LegalV28AggregateITSupport.insertHeader(
                        restricted,
                        unlockedId,
                        PerfilAgregadoLegal.REGISTRATION,
                        AudienciaLegal.ADMIN_TITULAR,
                        LegalV28AggregateITSupport.digest('7'),
                        LegalV28AggregateITSupport.digest('8'),
                        1));
        LegalV28AggregateITSupport.assertSqlState(unlockedFailure, "55000");
        assertRolledBack(unlockedId);
    }

    private static void assertRolledBack(UUID aggregateId) {
        assertThat(restricted.queryForObject("""
                SELECT count(*)
                  FROM legal_requisito_agregados
                 WHERE id = ?
                """, Long.class, aggregateId)).isZero();
        assertThat(restricted.queryForObject("""
                SELECT count(*)
                  FROM legal_requisito_agregado_scopes
                 WHERE agregado_id = ?
                """, Long.class, aggregateId)).isZero();
    }

    private static void assertUuidAggregateTablesOwnNoSequence() {
        assertThat(restricted.queryForObject("""
                SELECT count(*)
                  FROM pg_catalog.pg_class sequence
                  JOIN pg_catalog.pg_depend dependency
                    ON dependency.objid = sequence.oid
                   AND dependency.deptype IN ('a', 'i')
                  JOIN pg_catalog.pg_class relation
                    ON relation.oid = dependency.refobjid
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                 WHERE sequence.relkind = 'S'
                   AND namespace.nspname = 'public'
                   AND relation.relname IN (
                       'legal_requisito_agregados',
                       'legal_requisito_agregado_scopes'
                   )
                """, Long.class)).isZero();
        assertThat(restricted.queryForObject("""
                SELECT pg_catalog.pg_get_serial_sequence(
                    'public.legal_requisito_agregados', 'id')
                """, String.class)).isNull();
        assertThat(restricted.queryForObject("""
                SELECT pg_catalog.pg_get_serial_sequence(
                    'public.legal_requisito_agregado_scopes', 'agregado_id')
                """, String.class)).isNull();
    }
}

/** Shared real-PostgreSQL assembly for the V28 persistence and pre-freeze smoke ITs. */
final class LegalV28AggregateITSupport {

    private LegalV28AggregateITSupport() { }

    static ValidatedRelease releaseWithContinuedUse(
            Path temporaryDirectory,
            Class<?> resourceAnchor,
            String publicationExternalId) throws Exception {
        return LegalManifestPersistenceITSupport.copyRelease(
                temporaryDirectory,
                resourceAnchor,
                publicationExternalId,
                (manifestPath, manifest) -> {
                    manifest.withArray("documents").forEach(document ->
                            ((ObjectNode) document).put(
                                    "effectiveAt",
                                    "2020-01-01T00:00:00-03:00"));
                    ObjectNode continuedUse = manifest.withArray("requirements").addObject();
                    continuedUse.put("key", "continued-use-v28");
                    continuedUse.put("version", "1.0.0");
                    continuedUse.put("context", ContextoLegal.USO_CONTINUADO.name());
                    continuedUse.putArray("roles")
                            .add(AudienciaLegal.ADMIN_TITULAR.name())
                            .add(AudienciaLegal.USER.name());
                    continuedUse.put("actType", "ACEPTACION");
                    String statement =
                            "Confirmo el requisito de uso continuado de OrdenFix.";
                    continuedUse.put("statement", statement);
                    continuedUse.put("statementSha256", sha256(statement));
                    continuedUse.putArray("documents").add("terminos");
                    continuedUse.put("required", true);
                    continuedUse.put("requiresReacceptance", true);
                });
    }

    static UUID importRelease(DataSource ownerDataSource, ValidatedRelease release) {
        LegalManifestImportResult result = LegalManifestPersistenceITSupport.harness(
                        ownerDataSource,
                        LegalDatabaseBudgets.production())
                .importService()
                .importManifest(release);
        assertThat(result.status())
                .as("issues=%s", result.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        return result.receipt().orElseThrow().publicationUuid();
    }

    static DataSource dataSource(LegalRestrictedAggregateRoleFixture.Credentials credentials) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(credentials.driverClassName());
        dataSource.setUrl(credentials.jdbcUrl());
        dataSource.setUsername(credentials.username());
        dataSource.setPassword(credentials.password());
        return dataSource;
    }

    static AggregateHarness aggregateHarness(DataSource dataSource, String username) {
        JdbcTemplate jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("legal-required-set-aggregate-v28-it");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(
                LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        transaction.setReadOnly(false);

        LegalV28AggregateSchemaVerifier schema = new LegalV28AggregateSchemaVerifier(
                jdbc,
                LegalV28AggregateInventory.DEFAULT_SCHEMA);
        LegalV28AggregatePrivilegeVerifier privileges =
                new LegalV28AggregatePrivilegeVerifier(
                        jdbc,
                        Objects.requireNonNull(username, "username"),
                        LegalV28AggregateInventory.DEFAULT_SCHEMA);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
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
        return new AggregateHarness(jdbc, transaction, schema, privileges, gate, replay, store);
    }

    static Pointer currentPointer(
            JdbcTemplate jdbc,
            ContextoLegal context,
            AudienciaLegal audience) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT actual.conjunto_id, actual.publicacion_id,
                       actual.locale, actual.contexto, actual.audiencia,
                       conjunto.required_set_revision
                  FROM legal_requisito_conjuntos_actuales actual
                  JOIN legal_requisito_conjuntos conjunto
                    ON conjunto.id = actual.conjunto_id
                   AND conjunto.publicacion_id = actual.publicacion_id
                   AND conjunto.locale = actual.locale
                   AND conjunto.contexto = actual.contexto
                   AND conjunto.audiencia = actual.audiencia
                 WHERE actual.locale = ?
                   AND actual.contexto = ?
                   AND actual.audiencia = ?
                """, LegalV28AggregateITSupport::mapPointer,
                LocaleLegal.ES_AR.getCodigo(),
                context.name(),
                audience.name()));
    }

    static Pointer publicationPointer(
            JdbcTemplate jdbc,
            UUID publicationId,
            ContextoLegal context,
            AudienciaLegal audience) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT conjunto.id AS conjunto_id, conjunto.publicacion_id,
                       conjunto.locale, conjunto.contexto, conjunto.audiencia,
                       conjunto.required_set_revision
                  FROM legal_requisito_conjuntos conjunto
                 WHERE conjunto.publicacion_id = ?
                   AND conjunto.locale = ?
                   AND conjunto.contexto = ?
                   AND conjunto.audiencia = ?
                """, LegalV28AggregateITSupport::mapPointer,
                publicationId,
                LocaleLegal.ES_AR.getCodigo(),
                context.name(),
                audience.name()));
    }

    private static Pointer mapPointer(java.sql.ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new Pointer(
                resultSet.getObject("conjunto_id", UUID.class),
                resultSet.getObject("publicacion_id", UUID.class),
                resultSet.getString("locale"),
                ContextoLegal.valueOf(resultSet.getString("contexto")),
                resultSet.getString("audiencia"),
                resultSet.getString("required_set_revision"));
    }

    static void insertHeader(
            JdbcTemplate jdbc,
            UUID aggregateId,
            PerfilAgregadoLegal profile,
            AudienciaLegal audience,
            String requiredSetRevision,
            String provenanceFingerprint,
            int scopeCount) {
        assertThat(jdbc.update("""
                INSERT INTO legal_requisito_agregados
                    (id, perfil, locale, audiencia, revision_scheme,
                     required_set_revision, provenance_fingerprint,
                     scope_count, creado_en)
                VALUES (?, ?, 'es-AR', ?, 'AGGREGATE_V1', ?, ?, ?, statement_timestamp())
                """,
                aggregateId,
                profile.name(),
                audience.name(),
                requiredSetRevision,
                provenanceFingerprint,
                scopeCount)).isEqualTo(1);
    }

    static void insertScope(
            JdbcTemplate jdbc,
            UUID aggregateId,
            int ordinal,
            Pointer pointer) {
        assertThat(jdbc.update("""
                INSERT INTO legal_requisito_agregado_scopes
                    (agregado_id, scope_ordinal, contexto, conjunto_id,
                     publicacion_id, locale, audiencia, required_set_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                aggregateId,
                ordinal,
                pointer.context().name(),
                pointer.requiredSetId(),
                pointer.publicationId(),
                pointer.locale(),
                pointer.audience(),
                pointer.requiredSetRevision())).isEqualTo(1);
    }

    static String digest(char value) {
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
            throw new IllegalArgumentException("digest test char must be lowercase hex");
        }
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    static void assertSqlState(Throwable failure, String expected) {
        assertThat(failure).isNotNull();
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) {
            current = current.getCause();
        }
        assertThat(current).isInstanceOf(SQLException.class);
        assertThat(((SQLException) current).getSQLState()).isEqualTo(expected);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    record AggregateHarness(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            LegalV28AggregateSchemaVerifier schema,
            LegalV28AggregatePrivilegeVerifier privileges,
            LegalManifestDatabaseGate gate,
            LegalRequiredSetAggregateReplayVerifier replay,
            LegalRequiredSetAggregateStore store
    ) { }

    record Pointer(
            UUID requiredSetId,
            UUID publicationId,
            String locale,
            ContextoLegal context,
            String audience,
            String requiredSetRevision
    ) { }

    record HeaderRow(
            UUID id,
            String profile,
            String locale,
            String audience,
            String revisionScheme,
            String requiredSetRevision,
            String provenanceFingerprint,
            int scopeCount,
            Instant createdAt
    ) { }

    record ScopeRow(
            int ordinal,
            ContextoLegal context,
            UUID requiredSetId,
            UUID publicationId,
            String locale,
            String audience,
            String requiredSetRevision
    ) { }
}
