package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.jdbc;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.transaction;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** PostgreSQL16 history contract through the production isolated facade and exact restricted role. */
class LegalAcceptanceHistoryReaderIT {
    private static final String ROLE = "ordenfix_legal_private_requirements_history";
    private static final String PASSWORD = "history-read-test-only";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_private_requirements_history")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static JdbcTemplate owner;
    private static DataSource ownerDataSource;
    @TempDir Path directory;
    private AnnotationConfigApplicationContext context;
    private HikariDataSource pool;
    private LegalJdbcMetricsSupport metrics;
    private LegalAcceptanceHistoryService service;
    private LegalEditorialITFixture.ImportedRelease release;
    private volatile CountBarrier countBarrier;

    @BeforeAll static void start() {
        POSTGRES.start(); LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        ownerDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource); provision(owner, ROLE, PASSWORD);
    }
    @BeforeEach void open() throws Exception {
        requireSafeEphemeralDatabase(owner);
        owner.execute("TRUNCATE legal_requisito_agregados, legal_publicaciones, legal_documento_reemplazo_lotes, talleres RESTART IDENTITY CASCADE");
        release = LegalAcceptanceHistoryITSupport.seedCatalog(owner, directory, getClass(), 2);
        context = new AnnotationConfigApplicationContext();
        String prefix = LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("history-it", Map.of(
                LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true", prefix + "jdbc-url", POSTGRES.getJdbcUrl(),
                prefix + "username", ROLE, prefix + "password", PASSWORD)));
        context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!name.equals("legalPrivateRequirementsPool")) return bean;
                pool = (HikariDataSource) bean; metrics = LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO);
                var monitored = spy(pool);
                try { doAnswer(call -> observeCount(metrics.dataSource().getConnection(), () -> countBarrier)).when(monitored).getConnection(); }
                catch (SQLException failure) { throw new IllegalStateException(failure); }
                return monitored;
            }
        });
        context.register(LegalPrivateRequirementsDatabaseConfiguration.class); context.refresh();
        service = context.getBean(LegalAcceptanceHistoryService.class); metrics.reset();
    }
    @AfterEach void close() {
        if (countBarrier != null) countBarrier.release.countDown();
        if (context != null) context.close(); if (pool != null) pool.close();
    }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "USER"})
    void emptyHistoryDoesNotRequireMaterializationAndCommitsNoDml(String role) {
        var actor = seedActor(owner, role); var before = counts(owner);
        var page = service.read(principal(actor), null, 0, 20);
        assertThat(page.content()).isEmpty(); assertThat(page.page()).isZero(); assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isZero(); assertThat(page.totalPages()).isZero();
        assertThat(counts(owner)).isEqualTo(before); assertReleased(true);
    }

    @Test void emptyHistoryDoesNotDependOnAnyPublishedCatalog() {
        owner.execute("TRUNCATE legal_publicaciones CASCADE");
        var actor = seedActor(owner, "USER"); var before = counts(owner); metrics.reset();
        var page = service.read(principal(actor), null, 0, 20);
        assertThat(page.content()).isEmpty(); assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero(); assertThat(counts(owner)).isEqualTo(before); assertReleased(true);
    }

    @Test void onlyOwnActorEvidenceIsVisibleEvenForAdminInTheSameWorkshop() throws Exception {
        var administrator = seedActor(owner, "ADMIN");
        var employee = seedActor(owner, "USER", administrator.workshopId());
        var otherTenant = seedActor(owner, "USER");
        var adminActs = accept(ownerDataSource, administrator, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        var employeeActs = accept(ownerDataSource, employee, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        accept(ownerDataSource, otherTenant, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        var before = evidenceRows(owner);
        var adminPage = service.read(principal(administrator), null, 0, 20);
        assertThat(adminPage.content()).extracting(LegalAcceptanceHistoryPage.Acceptance::id)
                .containsExactlyInAnyOrderElementsOf(adminActs.stream().map(LegalAcceptanceProtocolFeasibilityITSupport.Act::id).toList());
        metrics.reset();
        var employeePage = service.read(principal(employee), null, 0, 20);
        assertThat(employeePage.content()).extracting(LegalAcceptanceHistoryPage.Acceptance::id)
                .containsExactlyInAnyOrderElementsOf(employeeActs.stream().map(LegalAcceptanceProtocolFeasibilityITSupport.Act::id).toList());
        assertThat(employeePage.totalElements()).isEqualTo(2); assertThat(adminPage.totalElements()).isEqualTo(2);
        assertThat(evidenceRows(owner)).isEqualTo(before); assertReleased(true);
    }

    @Test void stablePagesUseAcceptedDateDescendingThenPostgresUuidDescendingAndContextCounts() throws Exception {
        var actor = seedActor(owner, "ADMIN");
        var useActs = accept(ownerDataSource, actor, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        assertThat(useActs.getFirst().id().toString()).startsWith("ffffffff");
        assertThat(useActs.get(1).id().toString()).startsWith("00000000");
        var registrationActs = accept(ownerDataSource, actor, PerfilAgregadoLegal.REGISTRATION, null);
        var expected = orderedIds(owner, actor, null);
        List<UUID> observed = new ArrayList<>();
        for (int index = 0; index < expected.size(); index++) {
            metrics.reset(); var page = service.read(principal(actor), null, index, 1);
            assertThat(page.totalElements()).isEqualTo(expected.size()); assertThat(page.totalPages()).isEqualTo(expected.size());
            assertThat(page.content()).hasSize(1); observed.add(page.content().getFirst().id()); assertReleased(true);
        }
        assertThat(observed).containsExactlyElementsOf(expected);
        assertThat(observed.indexOf(useActs.getFirst().id())).isLessThan(observed.indexOf(useActs.get(1).id()));
        metrics.reset(); var filtered = service.read(principal(actor), ContextoLegal.REGISTRO, 0, 100);
        assertThat(filtered.totalElements()).isEqualTo(registrationActs.size());
        assertThat(filtered.content()).allSatisfy(act -> assertThat(act.context()).isEqualTo(ContextoLegal.REGISTRO));
        assertReleased(true);
        metrics.reset(); var beyond = service.read(principal(actor), null, 999, 20);
        assertThat(beyond.content()).isEmpty(); assertThat(beyond.totalElements()).isEqualTo(expected.size());
        assertThat(beyond.totalPages()).isEqualTo(1); assertReleased(true);
        metrics.reset(); var absentContext = service.read(principal(actor), ContextoLegal.CIERRE_CUENTA, 0, 20);
        assertThat(absentContext.content()).isEmpty(); assertThat(absentContext.totalElements()).isZero(); assertReleased(true);
    }

    @Test void oneHundredActsLoadOnlyPageDocumentsInFourBoundedBatchesWithoutMetadataOrNPlusOne() throws Exception {
        owner.execute("TRUNCATE legal_requisito_agregados, legal_publicaciones, legal_documento_reemplazo_lotes, talleres RESTART IDENTITY CASCADE");
        LegalAcceptanceHistoryITSupport.seedCatalog(owner, directory.resolve("capacity"), getClass(), 105);
        var actor = seedActor(owner, "USER"); accept(ownerDataSource, actor, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        var before = evidenceRows(owner); metrics.reset();
        var page = service.read(principal(actor), null, 0, 100);
        assertThat(page.content()).hasSize(100); assertThat(page.totalElements()).isEqualTo(105); assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).allSatisfy(act -> assertThat(act.documents()).hasSize(1));
        var documentsSql = metrics.snapshot().bySql().values().stream()
                .filter(sql -> sql.category() == LegalJdbcMetricsSupport.Category.SELECT)
                .filter(sql -> sql.sql().toLowerCase(Locale.ROOT).contains("join public.legal_aceptacion_documentos "))
                .toList();
        assertThat(documentsSql).isNotEmpty();
        assertThat(documentsSql.stream().mapToLong(LegalJdbcMetricsSupport.SqlSnapshot::executions).sum()).isEqualTo(4);
        assertThat(documentsSql.stream().mapToLong(LegalJdbcMetricsSupport.SqlSnapshot::rowsRead).sum()).isEqualTo(100);
        assertThat(documentsSql).allSatisfy(sql -> assertThat(sql.maximumRowsRead()).isLessThanOrEqualTo(32));
        assertThat(metrics.snapshot().bySql().values()).noneSatisfy(sql ->
                assertThat(sql.sql().toLowerCase(Locale.ROOT)).contains("from public.legal_aceptacion_metadatos"));
        assertThat(evidenceRows(owner)).isEqualTo(before); assertReleased(true);
        metrics.reset(); var tail = service.read(principal(actor), null, 1, 100);
        assertThat(tail.content()).hasSize(5); assertThat(tail.totalElements()).isEqualTo(105); assertReleased(true);
    }

    @ParameterizedTest @ValueSource(strings = {"statement", "digest", "document", "date", "membership"})
    void corruptedHistoricalEvidenceNeverReturnsAPartialPageAndRollsBack(String corruption) throws Exception {
        var actor = seedActor(owner, "USER"); var acts = accept(ownerDataSource, actor, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        LegalRequiredSetAggregateReceipt registration = null;
        if (corruption.equals("membership")) {
            try (var connection = transaction(ownerDataSource)) {
                registration = LegalAcceptanceProtocolFeasibilityITSupport.materialize(jdbc(connection), PerfilAgregadoLegal.REGISTRATION,
                        com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal.ADMIN_TITULAR);
                connection.commit();
            }
        }
        var foreignAggregate = registration;
        // Explicit fault injection bypasses immutable guards only in this disposable owner fixture.
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> {
            switch (corruption) {
                case "statement" -> owner.update("UPDATE legal_aceptaciones SET afirmacion='Afirmación alterada' WHERE id=?", acts.getFirst().id());
                case "digest" -> owner.update("UPDATE legal_aceptaciones SET afirmacion_sha256=? WHERE id=?", "0".repeat(64), acts.getFirst().id());
                case "document" -> owner.update("DELETE FROM legal_aceptacion_documentos WHERE aceptacion_id=?", acts.getFirst().id());
                case "date" -> owner.update("UPDATE legal_aceptacion_lotes SET aceptado_en='2999-01-01T00:00:00Z' WHERE user_id=?", actor.userId());
                case "membership" -> owner.update("UPDATE legal_aceptacion_lotes SET agregado_id=?,perfil='REGISTRATION',required_set_revision=?,audiencia='ADMIN_TITULAR',rol_wire='ADMIN' WHERE user_id=?",
                        foreignAggregate.aggregateId(), foreignAggregate.requiredSetRevision(), actor.userId());
                default -> throw new AssertionError(corruption);
            }
        });
        var before = counts(owner); metrics.reset();
        assertThatThrownBy(() -> service.read(principal(actor), null, 0, 20)).isInstanceOf(LegalAcceptanceHistoryReadException.class);
        assertThat(counts(owner)).isEqualTo(before); assertReleased(false);
    }

    @ParameterizedTest @ValueSource(strings = {"statement", "markdown"})
    void sourceLimitsRejectOversizedCanonicalSnapshotsBeforeFetchingAnyText(String field) throws Exception {
        var actor = seedActor(owner, "USER");
        var acts = accept(ownerDataSource, actor, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        var first = acts.getFirst();
        String content = "x".repeat(field.equals("statement") ? 1_001 : 1_048_577);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // Deliberately corrupt only this disposable fixture. Matching bytes/digests isolate the header size guard.
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> {
            if (field.equals("statement")) {
                owner.update("UPDATE legal_requisito_versiones SET afirmacion=?,afirmacion_sha256=? WHERE id=?",
                        content, digest, first.requirementId());
                owner.update("UPDATE legal_aceptaciones SET afirmacion=?,afirmacion_sha256=? WHERE id=?",
                        content, digest, first.id());
            } else {
                UUID document = owner.queryForObject("SELECT documento_version_id FROM legal_aceptacion_documentos "
                        + "WHERE aceptacion_id=? ORDER BY documento_ordinal LIMIT 1", UUID.class, first.id());
                owner.update("UPDATE legal_documento_versiones SET contenido_markdown=?,sha256=? WHERE id=?",
                        content, digest, document);
                owner.update("UPDATE legal_aceptacion_documentos SET sha256=? WHERE documento_version_id=?",
                        digest, document);
            }
        });
        var before = counts(owner); metrics.reset();
        assertThatThrownBy(() -> service.read(principal(actor), null, 0, 20))
                .isInstanceOf(LegalAcceptanceHistoryReadException.class);
        assertThat(metrics.snapshot().bySql().values()).noneSatisfy(sql ->
                assertThat(sql.sql().toLowerCase(Locale.ROOT)).contains(" as text_utf8"));
        assertThat(counts(owner)).isEqualTo(before); assertReleased(false);
    }

    @Test void staleActorRollsBackBeforeReadingHistory() {
        var actor = seedActor(owner, "USER"); var identity = principal(actor);
        owner.update("UPDATE users SET token_version=token_version+1 WHERE id=?", actor.userId()); metrics.reset();
        assertThatThrownBy(() -> service.read(identity, null, 0, 20)).isInstanceOf(LegalActorSnapshotException.class);
        assertThat(metrics.snapshot().bySql().values()).noneSatisfy(sql ->
                assertThat(sql.sql().toLowerCase(Locale.ROOT)).contains("select count(*)").contains("legal_aceptaciones")); assertReleased(false);
    }

    @Test void restrictedRolePrivilegeDriftFailsPreflightBeforeReadingHistory() {
        var actor = seedActor(owner, "USER");
        owner.execute("GRANT SELECT ON public.legal_aceptacion_metadatos TO " + ROLE);
        try {
            metrics.reset(); assertThatThrownBy(() -> service.read(principal(actor), null, 0, 20))
                    .isInstanceOf(LegalAcceptanceHistoryReadException.class);
            assertThat(metrics.snapshot().bySql().values()).noneSatisfy(sql ->
                assertThat(sql.sql().toLowerCase(Locale.ROOT)).contains("select count(*)").contains("legal_aceptaciones")); assertReleased(false);
        } finally { owner.execute("REVOKE SELECT ON public.legal_aceptacion_metadatos FROM " + ROLE); }
    }

    @Test void originalTextDocumentsAndDateSurviveRealReplaceAndExplicitRetirement() throws Exception {
        var actor = seedActor(owner, "USER"); accept(ownerDataSource, actor, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        var original = service.read(principal(actor), null, 0, 20); var originalRows = evidenceRows(owner);
        var replaced = replace(owner, ownerDataSource, directory, getClass(), release);
        metrics.reset(); assertThat(service.read(principal(actor), null, 0, 20)).isEqualTo(original); assertReleased(true);
        retire(owner, ownerDataSource, directory, replaced);
        metrics.reset(); assertThat(service.read(principal(actor), null, 0, 20)).isEqualTo(original); assertReleased(true);
        assertThat(evidenceRows(owner)).isEqualTo(originalRows);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_transiciones WHERE estado_nuevo='RETIRADA'", Long.class)).isPositive();
    }

    @Test void actorSharedLockKeepsCountAndPageCoherentUntilReaderCommits() throws Exception {
        var actor = seedActor(owner, "USER"); accept(ownerDataSource, actor, PerfilAgregadoLegal.AUTHENTICATED_PENDING, 0);
        countBarrier = new CountBarrier();
        try (var executor = Executors.newFixedThreadPool(2); var writerConnection = transaction(ownerDataSource)) {
            var writer = jdbc(writerConnection); long writerPid = writer.queryForObject("SELECT pg_backend_pid()", Long.class);
            var reading = executor.submit(() -> service.read(principal(actor), null, 0, 20));
            assertThat(countBarrier.countReturned.await(5, TimeUnit.SECONDS)).as("reader is paused after real SQL count").isTrue();
            var writing = executor.submit(() -> {
                actorExclusive(writer, actor);
                acceptLocked(writer, actor, PerfilAgregadoLegal.AUTHENTICATED_PENDING, 1);
                writerConnection.commit(); return true;
            });
            awaitBlockedPid(owner, writerPid);
            assertThat(writing.isDone()).isFalse(); countBarrier.release.countDown();
            var page = reading.get(10, TimeUnit.SECONDS);
            assertThat(page.totalElements()).isEqualTo(1); assertThat(page.content()).hasSize(1); assertReleased(true);
            assertThat(writing.get(10, TimeUnit.SECONDS)).isTrue();
            countBarrier = null; metrics.reset();
            var next = service.read(principal(actor), null, 0, 20);
            assertThat(next.totalElements()).isEqualTo(2); assertThat(next.content()).hasSize(2); assertReleased(true);
        } finally { if (countBarrier != null) countBarrier.release.countDown(); }
    }

    private void assertReleased(boolean committed) {
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.snapshot().commits()).isEqualTo(committed ? 1 : 0);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(committed ? 0 : 1);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
    @Test
    void genuineScopeV1HistorySurvivesUpgradeEvenWhenItsTransactionStartedBeforePublication() throws Exception {
        // A separate fresh database is required: V28 forbids creating new SCOPE_V1 lots.
        String historyDatabase = "ordenfix_legal_private_requirements_history_v27";
        String historyRole = "ordenfix_legal_private_requirements_history_v27_reader";
        String historyPassword = "scope-history-reader-test-only";
        requireSafeEphemeralDatabase(owner);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_database WHERE datname = ?",
                Integer.class, historyDatabase)).isZero();
        owner.execute("CREATE DATABASE " + historyDatabase);
        try {
            String historyUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                    + POSTGRES.getMappedPort(5432) + "/" + historyDatabase;
            DataSource historyDataSource = new DriverManagerDataSource(historyUrl,
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            JdbcTemplate historyOwner = new JdbcTemplate(historyDataSource);
            org.flywaydb.core.Flyway.configure().dataSource(historyDataSource)
                    .locations("classpath:db/migration").target("27").load().migrate();
            assertThat(historyOwner.queryForObject(
                    "SELECT max(version::integer) FROM flyway_schema_history WHERE success",
                    Integer.class)).isEqualTo(27);
            UUID legacyLot = UUID.randomUUID();
            List<UUID> acceptanceIds = new java.util.ArrayList<>();
            LegalPrivateRequirementsITSupport.Actor historicalActor;
            java.time.OffsetDateTime startedAt;
            try (var historicalConnection = historyDataSource.getConnection()) {
                historicalConnection.setAutoCommit(false);
                historicalConnection.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
                JdbcTemplate historicalTx = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        historicalConnection, true));
                startedAt = historicalTx.queryForObject("SELECT transaction_timestamp()", java.time.OffsetDateTime.class);
                // The old transaction has acquired no legal locks. Another connection publishes
                // the catalogue and actor before V27 observes them at READ_COMMITTED.
                seedCatalog(historyOwner, directory.resolve("scope-v27-history"), getClass());
                historicalActor = seedActor(historyOwner, "USER");
                String scopeRevision = historicalTx.queryForObject("""
                        SELECT c.required_set_revision FROM legal_requisito_conjuntos_actuales p
                          JOIN legal_requisito_conjuntos c ON c.id = p.conjunto_id
                         WHERE p.contexto = 'USO_CONTINUADO' AND p.locale = 'es-AR' AND p.audiencia = 'USER'
                        """, String.class);
                assertThat(historicalTx.update("""
                        INSERT INTO legal_aceptacion_lotes
                            (id, user_id, taller_id, rol_wire, audiencia, required_set_revision, aceptado_en)
                        VALUES (?, ?, ?, 'USER', 'USER', ?, statement_timestamp())
                        """, legacyLot, historicalActor.userId(), historicalActor.workshopId(), scopeRevision)).isEqualTo(1);
                List<UUID> requirements = historicalTx.queryForList("""
                        SELECT m.requisito_version_id FROM legal_requisito_conjuntos_actuales p
                          JOIN legal_requisito_conjunto_miembros m ON m.conjunto_id = p.conjunto_id
                         WHERE p.contexto = 'USO_CONTINUADO' AND p.locale = 'es-AR' AND p.audiencia = 'USER'
                         ORDER BY m.manifest_ordinal
                        """, UUID.class);
                assertThat(requirements).hasSize(2);
                for (UUID requirement : requirements) {
                    UUID acceptance = UUID.randomUUID();
                    acceptanceIds.add(acceptance);
                    assertThat(historicalTx.update("""
                            INSERT INTO legal_aceptaciones
                                (id, lote_id, user_id, taller_id, requisito_version_id, requisito_clave,
                                 requisito_version, contexto, tipo_acto, afirmacion, afirmacion_sha256, requerido)
                            SELECT ?, ?, ?, ?, v.id, l.clave, v.version, l.contexto, l.tipo_acto,
                                   v.afirmacion, v.afirmacion_sha256, v.requerido
                              FROM legal_requisito_versiones v JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id
                             WHERE v.id=?
                            """, acceptance, legacyLot, historicalActor.userId(), historicalActor.workshopId(), requirement))
                            .isEqualTo(1);
                    assertThat(historicalTx.update("""
                            INSERT INTO legal_aceptacion_documentos
                                (aceptacion_id, documento_ordinal, documento_version_id, documento_clave,
                                 tipo, version, titulo, sha256)
                            SELECT ?, r.documento_ordinal, v.id, l.clave, l.tipo, v.version, v.titulo, v.sha256
                              FROM legal_requisito_documentos r
                              JOIN legal_documento_versiones v ON v.id=r.documento_version_id
                              JOIN legal_documento_lineas l ON l.id=v.documento_linea_id
                             WHERE r.requisito_version_id=?
                            """, acceptance, requirement)).isEqualTo(1);
                }
                assertThat(historicalTx.update("""
                        INSERT INTO legal_aceptacion_metadatos (lote_id, capturado_en, retener_hasta)
                        VALUES (?, statement_timestamp(), statement_timestamp() + INTERVAL '30 days')
                        """, legacyLot)).isEqualTo(1);
                // Synthetic ciphertext is restricted to this owner-side historical fixture.
                assertThat(historicalTx.update("""
                        INSERT INTO legal_aceptacion_metadatos_cifrados
                            (lote_id, tipo, key_version, nonce, ciphertext, tag, longitud_original)
                        VALUES (?, 'IP', 1, ?, ?, ?, 9)
                        """, legacyLot, new byte[12], new byte[9], new byte[16])).isEqualTo(1);
                assertThat(historicalTx.queryForObject(
                        "SELECT aceptado_en FROM legal_aceptacion_lotes WHERE id=?",
                        java.time.OffsetDateTime.class, legacyLot)).isEqualTo(startedAt);
                assertThat(startedAt.toInstant()).isBefore(historicalTx.queryForObject("""
                        SELECT min(t.ocurrido_en) FROM legal_requisito_transiciones t
                          JOIN legal_requisito_versiones v ON v.id=t.requisito_version_id
                          JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id
                         WHERE l.contexto='USO_CONTINUADO' AND t.estado_nuevo='VIGENTE'
                        """, java.time.OffsetDateTime.class).toInstant());
                // Trigger guards and every deferred constraint remain enabled throughout.
                historicalTx.execute("SET CONSTRAINTS ALL IMMEDIATE");
                historicalConnection.commit();
            }
            Map<String, List<String>> historicalRows = evidenceRows(historyOwner);
            assertThat(historicalRows.get("legal_aceptaciones")).hasSize(2);
            assertThat(historicalRows.get("legal_aceptacion_documentos")).hasSize(2);
            org.flywaydb.core.Flyway.configure().dataSource(historyDataSource)
                    .locations("classpath:db/migration").load().migrate();
            assertThat(historyOwner.queryForObject(
                    "SELECT max(version::integer) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(33);
            assertThat(historyOwner.queryForMap("""
                    SELECT revision_scheme, perfil, agregado_id FROM legal_aceptacion_lotes WHERE id=?
                    """, legacyLot)).containsEntry("revision_scheme", "SCOPE_V1")
                    .containsEntry("perfil", null).containsEntry("agregado_id", null);
            assertThat(evidenceRows(historyOwner)).isEqualTo(historicalRows);
            provision(historyOwner, historyRole, historyPassword);
            try (var historicalContext = new AnnotationConfigApplicationContext()) {
                String prefix = LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
                historicalContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("scope-history", Map.of(
                        LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true",
                        prefix + "jdbc-url", historyUrl, prefix + "username", historyRole,
                        prefix + "password", historyPassword)));
                historicalContext.register(LegalPrivateRequirementsDatabaseConfiguration.class);
                historicalContext.refresh();
                var historicalService = historicalContext.getBean(LegalAcceptanceHistoryService.class);
                var result = historicalService.read(principal(historicalActor), null, 0, 20);
                assertThat(result.content()).hasSize(2);
                assertThat(result.totalElements()).isEqualTo(2);
                assertThat(result.content()).extracting(LegalAcceptanceHistoryPage.Acceptance::id)
                        .containsExactlyInAnyOrderElementsOf(acceptanceIds);
                var originalDate = startedAt.toInstant();
                assertThat(result.content()).allSatisfy(act -> {
                    assertThat(act.acceptedAt()).isEqualTo(originalDate);
                    assertThat(act.statement()).startsWith("Confirmo el requisito privado de uso continuado");
                    assertThat(act.documents()).hasSize(1);
                });
                assertThat(evidenceRows(historyOwner)).isEqualTo(historicalRows);
                assertThat(historicalContext.getBean(HikariDataSource.class).getHikariPoolMXBean()
                        .getActiveConnections()).isZero();
            }
        } finally {
            // Only the exact database created above inside this disposable container is removed.
            owner.execute("DROP DATABASE " + historyDatabase + " WITH (FORCE)");
        }
    }

}
