package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.Seed;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.StoredDocument;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.clearCatalog;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.seedCatalog;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.seedDraft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Exercises complete reads through the real restricted PUBLIC_DOCUMENT_READ composition. */
class LegalPublicDocumentReadServiceIT {

    private static final String ROLE = "ordenfix_legal_public_document_service_it";
    private static final String PASSWORD = "legal-public-document-service-test-only";
    private static final LocaleLegal LOCALE = LocaleLegal.ES_AR;
    private static final String DOCUMENT_SELECT = "FROM public.legal_documento_versiones versions";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_public_document_service")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    @TempDir
    private Path temporaryDirectory;

    private static DataSource ownerDataSource;
    private static JdbcTemplate owner;
    private AnnotationConfigApplicationContext context;
    private HikariDataSource originalPool;
    private LegalJdbcMetricsSupport metrics;
    private LegalPublicDocumentReadService service;
    private final AtomicReference<TransactionObservation> observed = new AtomicReference<>();

    @BeforeAll
    static void migrateAndProvisionRestrictedReader() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        new LegalRestrictedPublicDocumentRoleFixture(
                owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD, POSTGRES.getDriverClassName())
                .provisionAndVerify();
    }

    @BeforeEach
    void clearAndLoadTheProductionContext() {
        clearCatalog(owner);
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("reader-it", Map.of(
                LegalPublicDocumentReadDatabaseConfiguration.ENABLED_PROPERTY, "true",
                LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url",
                POSTGRES.getJdbcUrl(),
                LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX + "username", ROLE,
                LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX + "password", PASSWORD)));
        context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (name.equals("legalPublicDocumentPool")) {
                    return instrumentPool((HikariDataSource) bean);
                }
                if (bean instanceof LegalPublicDocumentReader reader) {
                    LegalPublicDocumentReader monitored = spy(reader);
                    doAnswer(invocation -> {
                        observeTransaction();
                        return invocation.callRealMethod();
                    }).when(monitored).readCatalog(any(), any(), anyInt(), anyInt(), any());
                    doAnswer(invocation -> {
                        observeTransaction();
                        return invocation.callRealMethod();
                    }).when(monitored).findVersion(any(), any());
                    return monitored;
                }
                return bean;
            }
        });
        context.register(LegalPublicDocumentReadDatabaseConfiguration.class);
        context.refresh();
        service = context.getBean(LegalPublicDocumentReadService.class);
        metrics.reset();
    }

    @AfterEach
    void closeTheReaderContext() {
        if (context != null) {
            context.close();
        }
        if (originalPool != null) {
            originalPool.close();
        }
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void anEmptyCatalogIsUnavailableButAnUnknownUuidIsSuccessfullyAbsent() {
        assertUnavailable(() -> service.catalog(null, LOCALE, 0, 20));
        assertRollbackOnly(metrics.snapshot());

        metrics.reset();
        assertThat(service.document(UUID.randomUUID())).isEmpty();
        assertCommittedReads(metrics.snapshot(), 1);
    }

    @Test
    void draftsAndPublishedButNotCurrentVersionsStayHidden() throws Exception {
        Seed draft = seedDraft(owner, temporaryDirectory, "reader-hidden");
        UUID id = draft.documents().getFirst().id();
        assertThat(draft.documents()).allSatisfy(document ->
                assertThat(document.state()).isEqualTo(EstadoVersionLegal.BORRADOR));

        assertUnavailable(() -> service.catalog(null, LOCALE, 0, 20));
        assertThat(service.document(id)).isEmpty();
        assertThat(service.document(UUID.randomUUID())).isEmpty();
        assertThat(metrics.snapshot().commits()).isEqualTo(2);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertNoDml(metrics.snapshot());

        inOwnerEditorialTransaction(() -> owner.update("""
                INSERT INTO public.legal_documento_transiciones
                    (documento_version_id, estado_anterior, estado_nuevo, ocurrido_en)
                SELECT documento_version_id, 'BORRADOR', 'PUBLICADA', transaction_timestamp()
                  FROM public.legal_publicacion_documentos
                 WHERE publicacion_id = ?
                """, draft.publicationId()));
        assertThat(owner.queryForObject(
                "SELECT estado FROM public.legal_documento_versiones WHERE id = ?",
                String.class, id)).isEqualTo("PUBLICADA");

        metrics.reset();
        assertUnavailable(() -> service.catalog(null, LOCALE, 0, 20));
        assertThat(service.document(id)).isEmpty();
        assertThat(service.document(UUID.randomUUID())).isEmpty();
        assertThat(metrics.snapshot().commits()).isEqualTo(2);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertNoDml(metrics.snapshot());
    }

    @Test
    void historicalContextsUseExistsWithoutDuplicatingTheElevenVersions() throws Exception {
        Seed seed = seedCatalog(owner, temporaryDirectory, "reader-contexts");
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM public.legal_documento_contextos", Long.class)).isEqualTo(21);
        LegalPublicDocumentCatalog all = service.catalog(null, LOCALE, 0, 100);
        assertThat(all.context()).isNull();
        assertThat(all.locale()).isEqualTo(LOCALE);
        assertThat(all.totalElements()).isEqualTo(11);
        assertThat(ids(all)).containsExactlyElementsOf(ids(seed.documents()));
        assertThat(ids(all)).doesNotHaveDuplicates();

        LegalPublicDocumentCatalog photos = service.catalog(ContextoLegal.ATESTACION_FOTOS, LOCALE, 0, 100);
        LegalPublicDocumentCatalog credentials =
                service.catalog(ContextoLegal.ATESTACION_CREDENCIALES, LOCALE, 0, 100);
        assertThat(ids(photos)).hasSize(2).containsExactlyElementsOf(ids(seed.documents().stream()
                .filter(document -> document.contexts().contains(ContextoLegal.ATESTACION_FOTOS)).toList()));
        assertThat(ids(credentials)).containsExactlyElementsOf(ids(photos));
        assertThat(credentials.documentSetRevision()).isNotEqualTo(photos.documentSetRevision());
        assertThat(all.documentSetRevision()).isNotEqualTo(photos.documentSetRevision());
        assertThat(metrics.snapshot().matching(DOCUMENT_SELECT).keySet())
                .anySatisfy(sql -> assertThat(sql).contains("EXISTS")
                        .doesNotContain("contenido_markdown", " LIMIT ", " OFFSET "));
        assertCommittedReads(metrics.snapshot(), 3);
        assertThat(metrics.snapshot().executionsContaining(DOCUMENT_SELECT)).isEqualTo(3);
    }

    @Test
    void revisionAndCountsCoverTheWholeFilterRegardlessOfPageAndSize() throws Exception {
        Seed seed = seedCatalog(owner, temporaryDirectory, "reader-pages");
        LegalPublicDocumentCatalog first = service.catalog(null, LOCALE, 0, 3);
        LegalPublicDocumentCatalog second = service.catalog(null, LOCALE, 1, 3);
        LegalPublicDocumentCatalog larger = service.catalog(null, LOCALE, 0, 7);
        LegalPublicDocumentCatalog beyond = service.catalog(null, LOCALE, Integer.MAX_VALUE, 100);

        assertThat(ids(first)).containsExactlyElementsOf(ids(seed.documents().subList(0, 3)));
        assertThat(ids(second)).containsExactlyElementsOf(ids(seed.documents().subList(3, 6)));
        assertThat(ids(larger)).containsExactlyElementsOf(ids(seed.documents().subList(0, 7)));
        assertThat(first.totalPages()).isEqualTo(4);
        assertThat(second.page()).isEqualTo(1);
        assertThat(larger.totalPages()).isEqualTo(2);
        assertThat(beyond.documents()).isEmpty();
        assertThat(beyond.page()).isEqualTo(Integer.MAX_VALUE);
        for (LegalPublicDocumentCatalog page : List.of(first, second, larger, beyond)) {
            assertThat(page.totalElements()).isEqualTo(11);
            assertThat(page.documentSetRevision()).isEqualTo(first.documentSetRevision());
        }
        assertCommittedReads(metrics.snapshot(), 4);
        assertThat(metrics.snapshot().executionsContaining(DOCUMENT_SELECT)).isEqualTo(4);
        assertThat(metrics.snapshot().rowsReadContaining(DOCUMENT_SELECT)).isEqualTo(44);
    }

    @Test
    void aRealReplacementPreservesHistoricalContentAndChangesOnlyAffectedFilters() throws Exception {
        Seed initial = seedCatalog(owner, temporaryDirectory, "reader-replace-source");
        StoredDocument old = initial.documents().stream()
                .filter(document -> document.key().equals("cierre-cuenta")).findFirst().orElseThrow();
        LegalPublicDocumentCatalog before = service.catalog(ContextoLegal.CIERRE_CUENTA, LOCALE, 0, 100);
        String unaffected = service.catalog(ContextoLegal.REGISTRO, LOCALE, 0, 100).documentSetRevision();
        Seed replaced = LegalPublicDocumentReadITSupport.replaceDocument(
                owner, temporaryDirectory, initial, "cierre-cuenta", "2.0.0", "reader-replace-target");

        metrics.reset();
        LegalPublicDocumentCatalog after = service.catalog(ContextoLegal.CIERRE_CUENTA, LOCALE, 0, 100);
        assertThat(after.totalElements()).isEqualTo(before.totalElements() + 1);
        assertThat(after.documentSetRevision()).isNotEqualTo(before.documentSetRevision());
        assertThat(after.documents()).extracting(LegalDocumentSummary::state)
                .contains(EstadoVersionLegal.REEMPLAZADA, EstadoVersionLegal.VIGENTE);
        assertThat(ids(after)).contains(old.id());
        assertThat(replaced.documents()).hasSize(12);
        LegalPublicDocumentVersion historical = service.document(old.id()).orElseThrow();
        assertThat(historical.summary().state()).isEqualTo(EstadoVersionLegal.REEMPLAZADA);
        assertThat(historical.markdown()).isEqualTo(old.markdown());
        assertThat(historical.summary().sha256()).isEqualTo(old.sha256());
        assertThat(service.catalog(ContextoLegal.REGISTRO, LOCALE, 0, 100).documentSetRevision())
                .isEqualTo(unaffected);
        assertCommittedReads(metrics.snapshot(), 3);
    }

    @Test
    void aRetiredVersionRemainsAvailableWhenItsContextHasNoCurrentDocument() throws Exception {
        Seed initial = seedCatalog(owner, temporaryDirectory, "reader-retirement");
        StoredDocument old = initial.documents().stream()
                .filter(document -> document.key().equals("cierre-cuenta")).findFirst().orElseThrow();
        String before = service.catalog(ContextoLegal.CIERRE_CUENTA, LOCALE, 0, 20).documentSetRevision();
        // Real DELETE of the current slot and audited RETIRADA transition under the exclusive gate.
        inOwnerEditorialTransaction(() -> {
            owner.update("DELETE FROM public.legal_documento_vigentes WHERE documento_version_id = ?", old.id());
            owner.update("""
                    INSERT INTO public.legal_documento_transiciones
                        (documento_version_id, estado_anterior, estado_nuevo, ocurrido_en, motivo)
                    VALUES (?, 'VIGENTE', 'RETIRADA', transaction_timestamp(), 'Retiro del fixture lector')
                    """, old.id());
        });
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM public.legal_documento_vigentes WHERE contexto = 'CIERRE_CUENTA'
                """, Long.class)).isZero();

        metrics.reset();
        LegalPublicDocumentCatalog archived = service.catalog(ContextoLegal.CIERRE_CUENTA, LOCALE, 0, 20);
        assertThat(archived.totalElements()).isEqualTo(1);
        assertThat(archived.documents().getFirst().state()).isEqualTo(EstadoVersionLegal.RETIRADA);
        assertThat(archived.documentSetRevision()).isNotEqualTo(before);
        assertThat(service.document(old.id()).orElseThrow().markdown()).isEqualTo(old.markdown());
        assertCommittedReads(metrics.snapshot(), 2);
    }

    @Test
    void exactVersionReturnsTheOriginalMarkdownAndItsValidatedDigest() throws Exception {
        Seed seed = seedCatalog(owner, temporaryDirectory, "reader-exact");
        StoredDocument expected = seed.documents().getFirst();
        Map<String, Long> counts = LegalManifestPersistenceITSupport.editorialTableCounts(owner);
        Map<String, LegalManifestPersistenceITSupport.SequenceState> sequences =
                LegalManifestPersistenceITSupport.editorialSequenceStates(owner);

        LegalPublicDocumentVersion found = service.document(expected.id()).orElseThrow();

        assertThat(found.markdown().getBytes(StandardCharsets.UTF_8))
                .containsExactly(expected.markdown().getBytes(StandardCharsets.UTF_8));
        assertThat(found.summary()).isEqualTo(summary(expected));
        assertThat(found.summary().sha256()).isEqualTo(
                LegalPublicDocumentReadITSupport.sha256(found.markdown()));
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(owner)).isEqualTo(counts);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(owner)).isEqualTo(sequences);
        assertCommittedReads(metrics.snapshot(), 1);
        assertThat(metrics.snapshot().executionsContaining(DOCUMENT_SELECT)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"digest", "canonical-text", "oversized"})
    void corruptContentFailsClosedAndRollsBackWithoutBecomingAnAbsentUuid(String failure) throws Exception {
        StoredDocument target = seedCatalog(owner, temporaryDirectory, "reader-corrupt-" + failure)
                .documents().getFirst();
        String corrupt = switch (failure) {
            case "digest" -> "# Contenido distinto\n";
            case "canonical-text" -> "# Contenido con CRLF\r\n";
            case "oversized" -> "x".repeat(LegalManifestLimits.MAX_MARKDOWN_BYTES + 1);
            default -> throw new IllegalArgumentException(failure);
        };
        String digest = failure.equals("digest") ? target.sha256()
                : LegalPublicDocumentReadITSupport.sha256(corrupt);
        // Deliberate owner corruption in an ephemeral test, with guards restored at transaction end.
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE public.legal_documento_versiones SET contenido_markdown = ?, sha256 = ?
                 WHERE id = ?
                """, corrupt, digest, target.id()));

        metrics.reset();
        assertUnavailable(() -> service.document(target.id()));
        assertRollbackOnly(metrics.snapshot());
        assertThat(metrics.snapshot().executionsContaining(DOCUMENT_SELECT)).isEqualTo(1);
        assertThat(owner.queryForObject(
                "SELECT contenido_markdown FROM public.legal_documento_versiones WHERE id = ?",
                String.class, target.id())).isEqualTo(corrupt);

        metrics.reset();
        assertThat(service.catalog(null, LOCALE, 0, 20).totalElements()).isEqualTo(11);
        assertCommittedReads(metrics.snapshot(), 1);
    }

    @Test
    void outOfContractMetadataRejectsBothReadsEvenWhenPostgresCanRepresentIt() throws Exception {
        StoredDocument target = seedCatalog(owner, temporaryDirectory, "reader-invalid-metadata")
                .documents().getLast();
        // PostgreSQL permits year 10000; the approved RFC3339 projection is bounded to 0000–9999.
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE public.legal_documento_versiones
                   SET vigente_desde = TIMESTAMPTZ '10000-01-01 00:00:00+00'
                 WHERE id = ?
                """, target.id()));
        metrics.reset();
        assertUnavailable(() -> service.catalog(null, LOCALE, 0, 1));
        assertUnavailable(() -> service.document(target.id()));
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(2);
        assertNoDml(metrics.snapshot());
    }

    @Test
    void moreThan128HistoricalVersionsAreReadAndTheLastRowChangesTheRevision() throws Exception {
        Seed history = LegalPublicDocumentReadITSupport.seedHistory(
                owner, temporaryDirectory, "reader-history", 13);
        assertThat(history.documents()).hasSize(143);
        assertThat(history.documents()).filteredOn(document ->
                document.state() == EstadoVersionLegal.VIGENTE).hasSize(11);
        assertThat(history.documents()).filteredOn(document ->
                document.state() == EstadoVersionLegal.REEMPLAZADA).hasSize(132);

        metrics.reset();
        LegalPublicDocumentCatalog first = service.catalog(null, LOCALE, 0, 100);
        LegalPublicDocumentCatalog second = service.catalog(null, LOCALE, 1, 100);
        assertThat(first.totalElements()).isEqualTo(143);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.documents()).hasSize(100);
        assertThat(second.documents()).hasSize(43);
        assertThat(second.documentSetRevision()).isEqualTo(first.documentSetRevision());
        ArrayList<UUID> allIds = new ArrayList<>(ids(first));
        allIds.addAll(ids(second));
        assertThat(allIds).containsExactlyElementsOf(ids(history.documents())).doesNotHaveDuplicates();
        assertThat(metrics.snapshot().executionsContaining(DOCUMENT_SELECT)).isEqualTo(2);
        assertThat(metrics.snapshot().rowsReadContaining(DOCUMENT_SELECT)).isEqualTo(286);
        assertCommittedReads(metrics.snapshot(), 2);

        StoredDocument last = history.documents().getLast();
        // Deliberate metadata change beyond the first cursor fetch and beyond the first page.
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE public.legal_documento_versiones SET titulo = titulo || ' — cambio final'
                 WHERE id = ?
                """, last.id()));
        metrics.reset();
        LegalPublicDocumentCatalog changed = service.catalog(null, LOCALE, 0, 100);
        assertThat(changed.documents()).isEqualTo(first.documents());
        assertThat(changed.totalElements()).isEqualTo(143);
        assertThat(changed.documentSetRevision()).isNotEqualTo(first.documentSetRevision());
        assertThat(metrics.snapshot().rowsReadContaining(DOCUMENT_SELECT)).isEqualTo(143);
        assertCommittedReads(metrics.snapshot(), 1);
    }

    @Test
    void actualCredentialAndReadOnlySessionMatchTheIsolatedProductionBoundary() throws Exception {
        seedCatalog(owner, temporaryDirectory, "reader-boundary");
        assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class).values())
                .extracting(LegalDatabaseBoundaryMarker::kind)
                .containsExactly(LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ);
        assertThat(context.getBean(JdbcTemplate.class).getDataSource())
                .isSameAs(context.getBean(LegalPublicDocumentDataSource.class))
                .isNotSameAs(ownerDataSource);
        TransactionTemplate transaction = context.getBean(TransactionTemplate.class);
        assertThat(transaction.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(transaction.isReadOnly()).isTrue();

        service.catalog(null, LOCALE, 0, 20);

        assertThat(observed.get().identity()).isEqualTo(ROLE + ":" + ROLE);
        assertThat(observed.get().isolation()).isEqualTo("read committed");
        assertThat(observed.get().readOnly()).isEqualTo("on");
        assertThat(observed.get().active()).isTrue();
        assertThat(observed.get().springReadOnly()).isTrue();
        assertCommittedReads(metrics.snapshot(), 1);
    }

    @Test
    void requiresNewUsesAnotherConnectionAndRestoresTheOuterTransaction() throws Exception {
        seedCatalog(owner, temporaryDirectory, "reader-requires-new");
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        TransactionTemplate outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        outer.setReadOnly(true);
        AtomicReference<LegalPublicDocumentCatalog> returned = new AtomicReference<>();

        context.getBean(LegalPublicDocumentDataSource.class).withinDeadline(deadline ->
                outer.execute(status -> {
                    Integer outerPid = jdbc.queryForObject("SELECT pg_catalog.pg_backend_pid()", Integer.class);
                    returned.set(service.catalog(null, LOCALE, 0, 20));
                    assertThat(observed.get().pid()).isNotEqualTo(outerPid);
                    assertThat(observed.get().isolation()).isEqualTo("read committed");
                    assertThat(jdbc.queryForObject("SELECT pg_catalog.pg_backend_pid()", Integer.class))
                            .isEqualTo(outerPid);
                    assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
                            .isEqualTo("serializable");
                    status.setRollbackOnly();
                    return true;
                }));

        assertThat(returned.get().totalElements()).isEqualTo(11);
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertNoDml(metrics.snapshot());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema", "privileges"})
    void preflightDriftRollsBackBeforeAnyDocumentQuery(String drift) throws Exception {
        seedCatalog(owner, temporaryDirectory, "reader-preflight-" + drift);
        String mutation = drift.equals("schema")
                ? "UPDATE public.flyway_schema_history SET checksum = checksum + 1 WHERE version = '28'"
                : "GRANT SELECT ON TABLE public.users TO " + ROLE;
        String restore = drift.equals("schema")
                ? "UPDATE public.flyway_schema_history SET checksum = checksum - 1 WHERE version = '28'"
                : "REVOKE SELECT ON TABLE public.users FROM " + ROLE;
        owner.execute(mutation);
        try {
            metrics.reset();
            assertUnavailable(() -> service.catalog(null, LOCALE, 0, 20));
            assertRollbackOnly(metrics.snapshot());
            assertThat(metrics.snapshot().executionsContaining(DOCUMENT_SELECT)).isZero();
            assertThat(observed.get()).isNull();
        } finally {
            owner.execute(restore);
        }
        metrics.reset();
        assertThat(service.catalog(null, LOCALE, 0, 20).totalElements()).isEqualTo(11);
        assertCommittedReads(metrics.snapshot(), 1);
    }

    private HikariDataSource instrumentPool(HikariDataSource pool) {
        originalPool = pool;
        metrics = LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO);
        HikariDataSource monitored = spy(pool);
        try {
            doAnswer(invocation -> metrics.dataSource().getConnection()).when(monitored).getConnection();
        } catch (SQLException impossibleDuringStubbing) {
            throw new IllegalStateException(impossibleDuringStubbing);
        }
        return monitored;
    }

    private void observeTransaction() {
        observed.set(context.getBean(JdbcTemplate.class).queryForObject("""
                SELECT CURRENT_USER || ':' || SESSION_USER AS identity,
                       pg_catalog.current_setting('transaction_isolation') AS isolation,
                       pg_catalog.current_setting('transaction_read_only') AS read_only,
                       pg_catalog.pg_backend_pid() AS pid
                """, (row, number) -> new TransactionObservation(
                row.getString("identity"), row.getString("isolation"), row.getString("read_only"),
                row.getInt("pid"), TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly())));
    }

    private static void inOwnerEditorialTransaction(Runnable mutation) {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(ownerDataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> {
            owner.queryForList("""
                    SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 0))
                    """, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
            mutation.run();
            owner.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
    }

    private static LegalDocumentSummary summary(StoredDocument document) {
        return new LegalDocumentSummary(document.id(), document.type(), document.version(), document.title(),
                document.sha256(), document.effectiveAt(), document.state(), LOCALE);
    }

    private static List<UUID> ids(LegalPublicDocumentCatalog catalog) {
        return catalog.documents().stream().map(LegalDocumentSummary::versionId).toList();
    }

    private static List<UUID> ids(List<StoredDocument> documents) {
        return documents.stream().map(StoredDocument::id).toList();
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(LegalPublicDocumentReadException.class)
                .hasMessage("El contrato documental no está disponible");
    }

    private static void assertCommittedReads(LegalJdbcMetricsSupport.Snapshot snapshot, int count) {
        assertThat(snapshot.commits()).isEqualTo(count);
        assertThat(snapshot.rollbacks()).isZero();
        assertThat(snapshot.advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.SHARED))
                .isEqualTo(count);
        assertNoDml(snapshot);
    }

    private static void assertRollbackOnly(LegalJdbcMetricsSupport.Snapshot snapshot) {
        assertThat(snapshot.commits()).isZero();
        assertThat(snapshot.rollbacks()).isEqualTo(1);
        assertNoDml(snapshot);
    }

    private static void assertNoDml(LegalJdbcMetricsSupport.Snapshot snapshot) {
        assertThat(snapshot.executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(snapshot.executions(LegalJdbcMetricsSupport.Category.ROW_LOCK)).isZero();
        assertThat(snapshot.advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.EXCLUSIVE)).isZero();
    }

    private record TransactionObservation(String identity, String isolation, String readOnly,
                                          int pid, boolean active, boolean springReadOnly) { }
}
