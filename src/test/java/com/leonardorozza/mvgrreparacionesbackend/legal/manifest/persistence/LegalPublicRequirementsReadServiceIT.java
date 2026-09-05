package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.Seed;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.aggregateCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.clearCatalog;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.seedRegistration;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.seedRegistrationWithOptional;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Full content accreditation with production composition, PostgreSQL 16 and the dedicated role. */
class LegalPublicRequirementsReadServiceIT {

    private static final String ROLE = "ordenfix_legal_public_requirements_service_it";
    private static final String PASSWORD = "legal-public-requirements-service-test-only";
    private static final String TEXT_GATE = "ELSE NULL END AS text_utf8";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_requirements_service")
            .withUsername("ordenfix").withPassword("ordenfix");

    @TempDir
    private Path temporaryDirectory;
    private static DataSource ownerDataSource;
    private static JdbcTemplate owner;
    private AnnotationConfigApplicationContext context;
    private HikariDataSource originalPool;
    private LegalJdbcMetricsSupport metrics;
    private LegalPublicRequirementsReadService service;
    private final AtomicReference<LegalRequiredSetAggregateReceipt> receipt = new AtomicReference<>();
    private final AtomicReference<TransactionObservation> observed = new AtomicReference<>();

    @BeforeAll
    static void migrateAndProvisionTheRestrictedConsumer() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        new LegalRestrictedPublicRequirementsRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
    }

    @BeforeEach
    void openTheRealConsumerGraphWithJdbcObservationOnly() {
        clearCatalog(owner);
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("requirements-reader-it", Map.of(
                LegalPublicRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true",
                LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", POSTGRES.getJdbcUrl(),
                LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "username", ROLE,
                LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "password", PASSWORD)));
        context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (name.equals("legalPublicRequirementsPool")) {
                    return instrumentPool((HikariDataSource) bean);
                }
                if (bean instanceof LegalRequiredSetAggregateStore actualStore) {
                    LegalRequiredSetAggregateStore monitored = spy(actualStore);
                    doAnswer(invocation -> {
                        observeTransaction();
                        Object result = invocation.callRealMethod();
                        receipt.set((LegalRequiredSetAggregateReceipt) result);
                        return result;
                    }).when(monitored).materialize(any(), any());
                    return monitored;
                }
                return bean;
            }
        });
        context.register(LegalPublicRequirementsDatabaseConfiguration.class);
        context.refresh();
        service = context.getBean(LegalPublicRequirementsReadService.class);
        metrics.reset();
    }

    @AfterEach
    void closeTheWholeConsumer() {
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            if (originalPool != null) {
                originalPool.close();
            }
        }
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void registrationWithoutACurrentSetIsUnavailableAndNeverMaterializesAnEmptySuccess() {
        assertUnavailable();
        assertAggregateCounts(0);
        assertRolledBack();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(receipt.get()).isNull();
    }

    @Test
    void optionalMembersKeepManifestOrderAndSharedDocumentsAreLoadedOnceBeforeSequentialReuse() throws Exception {
        Seed seed = seedRegistrationWithOptional(owner, temporaryDirectory, "requirements-complete");
        var editorialCounts = LegalManifestPersistenceITSupport.editorialTableCounts(owner);
        var sequences = LegalManifestPersistenceITSupport.editorialSequenceStates(owner);
        assertThat(owner.queryForList("""
                SELECT manifest_ordinal FROM public.legal_requisito_conjunto_miembros
                 WHERE conjunto_id = ? ORDER BY manifest_ordinal
                """, Integer.class, seed.requiredSetId())).containsExactly(1, 7);

        LegalPublicRegistrationRequirements first = service.readRegistration();

        assertResult(first, seed);
        assertThat(first.projection().requirements()).extracting(RequirementProjection::required)
                .containsExactly(true, false);
        assertThat(first.projection().requirements().stream().flatMap(requirement -> requirement.documents().stream()))
                .hasSize(5);
        assertThat(receipt.get().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        UUID firstId = receipt.get().aggregateId();
        assertAggregateCounts(1);
        assertCommitted();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertTextBatches(2, 3);
        assertThat(observed.get().identity()).isEqualTo(ROLE + ":" + ROLE);
        assertThat(observed.get().isolation()).isEqualTo("read committed");
        assertThat(observed.get().readOnly()).isEqualTo("off");
        assertThat(observed.get().active()).isTrue();
        assertThat(observed.get().springReadOnly()).isFalse();

        metrics.reset();
        LegalPublicRegistrationRequirements reused = service.readRegistration();

        assertResult(reused, seed);
        assertThat(reused.requiredSetRevision()).isEqualTo(first.requiredSetRevision());
        assertThat(receipt.get().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.REUSED);
        assertThat(receipt.get().aggregateId()).isEqualTo(firstId);
        assertAggregateCounts(1);
        assertCommitted();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertTextBatches(2, 3);
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(owner)).isEqualTo(editorialCounts);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(owner)).isEqualTo(sequences);
    }

    @ParameterizedTest
    @EnumSource(Corruption.class)
    void corruptContentOrGraphRollsBackANewMaterializationInsteadOfDroppingInvalidRows(Corruption corruption)
            throws Exception {
        Seed seed = seedRegistrationWithOptional(owner, temporaryDirectory,
                "requirements-corrupt-" + corruption.name().toLowerCase(Locale.ROOT));
        // This owner-only injection deliberately bypasses append-only triggers/FKs in this disposable DB.
        // Production import/promotion above keeps all guards; no migration or live policy is weakened.
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> corrupt(corruption, seed));
        if (corruption == Corruption.PUBLICATION_DOCUMENTS_OVER_LIMIT) {
            assertThat(owner.queryForObject("SELECT count(*) FROM public.legal_publicacion_documentos WHERE publicacion_id = ?",
                    Long.class, seed.publicationId())).isEqualTo(129);
        }
        if (corruption == Corruption.PUBLICATION_REQUIREMENTS_OVER_LIMIT) {
            assertThat(owner.queryForObject("SELECT count(*) FROM public.legal_publicacion_requisitos WHERE publicacion_id = ?",
                    Long.class, seed.publicationId())).isEqualTo(257);
        }
        metrics.reset();

        assertUnavailable();

        assertAggregateCounts(0); // Independent owner connection observes no partial header or scope.
        assertRolledBack();
        assertThat(receipt.get()).as("the real store completed before content accreditation failed").isNotNull();
        assertThat(receipt.get().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        if (corruption == Corruption.STATEMENT_TOO_LARGE || corruption == Corruption.MARKDOWN_TOO_LARGE
                || corruption == Corruption.FUTURE_EFFECTIVE_AT
                || corruption == Corruption.PUBLICATION_DOCUMENTS_OVER_LIMIT
                || corruption == Corruption.PUBLICATION_REQUIREMENTS_OVER_LIMIT) {
            assertThat(metrics.snapshot().executionsContaining(TEXT_GATE)).isZero();
        }
    }

    @Test
    void corruptionAfterReusePreservesTheConfirmedAggregateAndRecoversWithoutDml() throws Exception {
        Seed seed = seedRegistrationWithOptional(owner, temporaryDirectory, "requirements-reuse-corrupt");
        LegalPublicRegistrationRequirements original = service.readRegistration();
        UUID aggregateId = receipt.get().aggregateId();
        var headers = owner.queryForList("SELECT * FROM public.legal_requisito_agregados");
        var scopes = owner.queryForList("SELECT * FROM public.legal_requisito_agregado_scopes");
        RequirementProjection optional = seed.projection().requirements().getLast();
        String altered = optional.statement() + " Alterada.";
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE public.legal_requisito_versiones SET afirmacion = ?, afirmacion_sha256 = ? WHERE id = ?
                """, altered, sha256(altered), optional.versionId()));
        metrics.reset();

        assertUnavailable();

        assertThat(receipt.get().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.REUSED);
        assertThat(receipt.get().aggregateId()).isEqualTo(aggregateId);
        assertAggregateCounts(1);
        assertRolledBack();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(owner.queryForList("SELECT * FROM public.legal_requisito_agregados")).isEqualTo(headers);
        assertThat(owner.queryForList("SELECT * FROM public.legal_requisito_agregado_scopes")).isEqualTo(scopes);
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE public.legal_requisito_versiones SET afirmacion = ?, afirmacion_sha256 = ? WHERE id = ?
                """, optional.statement(), optional.statementSha256(), optional.versionId()));
        metrics.reset();
        LegalPublicRegistrationRequirements recovered = service.readRegistration();
        assertResult(recovered, seed);
        assertThat(recovered.requiredSetRevision()).isEqualTo(original.requiredSetRevision());
        assertThat(receipt.get().aggregateId()).isEqualTo(aggregateId);
        assertCommitted();
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
    }

    @Test
    void realReplacementCanReuseVersionsIntroducedEarlierWhileMovingExactPublicationProvenance() throws Exception {
        Seed initial = seedRegistration(owner, temporaryDirectory, "requirements-old-introduction");
        LegalPublicRegistrationRequirements first = service.readRegistration();
        UUID initialAggregate = receipt.get().aggregateId();
        Seed replaced = LegalPublicRequirementsReadITSupport.replaceDocument(owner, temporaryDirectory, initial,
                "cierre-cuenta", "2.0.0", "requirements-new-publication");
        assertThat(replaced.publicationId()).isNotEqualTo(initial.publicationId());
        assertThat(replaced.requiredSetId()).isNotEqualTo(initial.requiredSetId());
        assertThat(replaced.projection()).isEqualTo(initial.projection());
        assertThat(owner.queryForObject("""
                SELECT publicacion_intro_id FROM public.legal_requisito_versiones WHERE id = ?
                """, UUID.class, replaced.projection().requirements().getFirst().versionId()))
                .isEqualTo(initial.publicationId());
        for (DocumentProjection document : replaced.projection().requirements().getFirst().documents()) {
            assertThat(owner.queryForObject("""
                    SELECT publicacion_intro_id FROM public.legal_documento_versiones WHERE id = ?
                    """, UUID.class, document.versionId())).isEqualTo(initial.publicationId());
            assertThat(owner.queryForObject("""
                    SELECT publicacion_id FROM public.legal_documento_vigentes
                     WHERE documento_version_id = ? AND contexto = 'REGISTRO'
                    """, UUID.class, document.versionId())).isEqualTo(replaced.publicationId());
        }
        metrics.reset();

        LegalPublicRegistrationRequirements after = service.readRegistration();

        assertResult(after, replaced);
        assertThat(after.requiredSetRevision()).isEqualTo(first.requiredSetRevision());
        assertThat(receipt.get().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertThat(receipt.get().aggregateId()).isNotEqualTo(initialAggregate);
        assertThat(receipt.get().provenance().scopes().getFirst().publicationId()).isEqualTo(replaced.publicationId());
        assertAggregateCounts(2);
        assertCommitted();
    }

    @Test
    void serviceRequiresNewCommitsItsCompleteResultWhileTheOuterTransactionRollsBack() throws Exception {
        Seed seed = seedRegistration(owner, temporaryDirectory, "requirements-requires-new");
        var outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        var jdbc = context.getBean(JdbcTemplate.class);
        var bounded = context.getBean(LegalPublicRequirementsDataSource.class);
        AtomicReference<LegalPublicRegistrationRequirements> returned = new AtomicReference<>();

        bounded.withinDeadline(deadline -> {
            outer.executeWithoutResult(status -> {
                int outerPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                returned.set(service.readRegistration());
                assertThat(observed.get().pid()).isNotEqualTo(outerPid);
                assertThat(observed.get().isolation()).isEqualTo("read committed");
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(outerPid);
                assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("serializable");
                status.setRollbackOnly();
            });
            return true;
        });

        assertResult(returned.get(), seed);
        assertAggregateCounts(1);
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(originalPool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema", "privileges"})
    void preflightDriftBecomesSafeUnavailabilityBeforeStoreAndRecoversAfterRestoration(String drift) throws Exception {
        Seed seed = seedRegistration(owner, temporaryDirectory, "requirements-preflight-" + drift);
        String mutation = drift.equals("schema")
                ? "UPDATE public.flyway_schema_history SET checksum = checksum + 1 WHERE version = '28'"
                : "GRANT SELECT ON public.users TO " + ROLE;
        String restoration = drift.equals("schema")
                ? "UPDATE public.flyway_schema_history SET checksum = checksum - 1 WHERE version = '28'"
                : "REVOKE SELECT ON public.users FROM " + ROLE;
        owner.execute(mutation);
        try {
            metrics.reset();
            assertUnavailable();
            assertAggregateCounts(0);
            assertRolledBack();
            assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.ADVISORY_LOCK)).isZero();
            assertThat(receipt.get()).isNull();
        } finally {
            owner.execute(restoration);
        }
        metrics.reset();
        assertResult(service.readRegistration(), seed);
        assertCommitted();
    }

    private void corrupt(Corruption corruption, Seed seed) {
        RequirementProjection mandatory = seed.projection().requirements().getFirst();
        RequirementProjection optional = seed.projection().requirements().getLast();
        UUID requirement = optional.versionId();
        DocumentProjection document = mandatory.documents().getLast();
        UUID documentId = document.versionId();
        switch (corruption) {
            case STATEMENT_DIGEST -> owner.update("UPDATE public.legal_requisito_versiones SET afirmacion_sha256 = ? WHERE id = ?",
                    "0".repeat(64), requirement);
            case STATEMENT_NONCANONICAL -> setStatement(requirement, "Afirmación con\r\nsalto no canónico.");
            case STATEMENT_INVISIBLE -> setStatement(requirement, "   ");
            case STATEMENT_TOO_LARGE -> setStatement(requirement, "a".repeat(1_001));
            case MARKDOWN_DIGEST -> owner.update("UPDATE public.legal_documento_versiones SET sha256 = ? WHERE id = ?",
                    "0".repeat(64), documentId);
            case MARKDOWN_NONCANONICAL -> setMarkdown(documentId, document.markdown() + "\r\n");
            case MARKDOWN_TOO_LARGE -> setMarkdown(documentId, "a".repeat(1_048_577));
            case SCOPE_REVISION -> owner.update("UPDATE public.legal_requisito_conjuntos SET required_set_revision = ? WHERE id = ?",
                    "sha256:" + "0".repeat(64), seed.requiredSetId());
            case DOCUMENT_VERSION_TOO_LONG -> owner.update("UPDATE public.legal_documento_versiones SET version = ? WHERE id = ?",
                    "v".repeat(41), documentId);
            case OPTIONAL_WITHOUT_DOCUMENTS -> owner.update("DELETE FROM public.legal_requisito_documentos WHERE requisito_version_id = ?",
                    requirement);
            case NO_MANDATORY -> owner.update("""
                    UPDATE public.legal_requisito_versiones SET requerido = false
                     WHERE id IN (SELECT requisito_version_id FROM public.legal_requisito_conjunto_miembros
                                   WHERE conjunto_id = ?)
                    """, seed.requiredSetId());
            case REQUIREMENT_VERSION_ORPHAN -> owner.update("DELETE FROM public.legal_requisito_versiones WHERE id = ?", requirement);
            case SET_MEMBER_MISSING -> owner.update("DELETE FROM public.legal_requisito_conjunto_miembros WHERE conjunto_id = ? AND requisito_version_id = ?",
                    seed.requiredSetId(), requirement);
            case PUBLICATION_MEMBER_MISSING -> owner.update("DELETE FROM public.legal_publicacion_requisitos WHERE publicacion_id = ? AND requisito_version_id = ?",
                    seed.publicationId(), requirement);
            case DOCUMENT_VERSION_ORPHAN -> owner.update("DELETE FROM public.legal_documento_versiones WHERE id = ?", documentId);
            case DOCUMENT_PUBLICATION_MEMBER_MISSING -> owner.update("DELETE FROM public.legal_publicacion_documentos WHERE publicacion_id = ? AND documento_version_id = ?",
                    seed.publicationId(), documentId);
            case DOCUMENT_SLOT_MISSING -> owner.update("DELETE FROM public.legal_documento_vigentes WHERE documento_version_id = ? AND contexto = 'REGISTRO'",
                    documentId);
            case DOCUMENT_CONTEXT_MISSING -> owner.update("DELETE FROM public.legal_documento_contextos WHERE documento_version_id = ? AND contexto = 'REGISTRO'",
                    documentId);
            case DOCUMENT_ORDINAL_GAP -> owner.update("UPDATE public.legal_requisito_documentos SET documento_ordinal = 9 WHERE requisito_version_id = ? AND documento_ordinal = 2",
                    requirement);
            case MANIFEST_ORDINAL_MISMATCH -> owner.update("UPDATE public.legal_requisito_conjunto_miembros SET manifest_ordinal = 8 WHERE conjunto_id = ? AND requisito_version_id = ?",
                    seed.requiredSetId(), requirement);
            case AUDIENCE_MISSING -> owner.update("DELETE FROM public.legal_requisito_audiencias WHERE requisito_linea_id = (SELECT requisito_linea_id FROM public.legal_requisito_versiones WHERE id = ?)",
                    requirement);
            case REQUIREMENT_NOT_CURRENT -> owner.update("UPDATE public.legal_requisito_versiones SET estado = 'PUBLICADA' WHERE id = ?",
                    requirement);
            case DOCUMENT_NOT_CURRENT -> owner.update("UPDATE public.legal_documento_versiones SET estado = 'PUBLICADA' WHERE id = ?",
                    documentId);
            case FUTURE_EFFECTIVE_AT -> owner.update("UPDATE public.legal_documento_versiones SET vigente_desde = TIMESTAMPTZ '9999-01-01 00:00:00+00' WHERE id = ?",
                    documentId);
            case PUBLICATION_UNSEALED -> owner.update("UPDATE public.legal_publicaciones SET estado_construccion = 'ABIERTO', sellado_en = NULL WHERE id = ?",
                    seed.publicationId());
            case PUBLICATION_DOCUMENTS_OVER_LIMIT -> owner.update("""
                    INSERT INTO public.legal_publicacion_documentos
                        (publicacion_id, documento_version_id, manifest_ordinal)
                    SELECT ?, pg_catalog.gen_random_uuid(), 1000 + extra.ordinal
                      FROM pg_catalog.generate_series(1, 129 - (
                          SELECT count(*)::integer FROM public.legal_publicacion_documentos
                           WHERE publicacion_id = ?)) AS extra(ordinal)
                    """, seed.publicationId(), seed.publicationId());
            case PUBLICATION_REQUIREMENTS_OVER_LIMIT -> owner.update("""
                    INSERT INTO public.legal_publicacion_requisitos
                        (publicacion_id, requisito_version_id, manifest_ordinal)
                    SELECT ?, pg_catalog.gen_random_uuid(), 1000 + extra.ordinal
                      FROM pg_catalog.generate_series(1, 257 - (
                          SELECT count(*)::integer FROM public.legal_publicacion_requisitos
                           WHERE publicacion_id = ?)) AS extra(ordinal)
                    """, seed.publicationId(), seed.publicationId());
        }
    }

    private static void setStatement(UUID id, String text) {
        owner.update("UPDATE public.legal_requisito_versiones SET afirmacion = ?, afirmacion_sha256 = ? WHERE id = ?",
                text, sha256(text), id);
    }

    private static void setMarkdown(UUID id, String text) {
        owner.update("UPDATE public.legal_documento_versiones SET contenido_markdown = ?, sha256 = ? WHERE id = ?",
                text, sha256(text), id);
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
                SELECT current_user || ':' || session_user AS identity,
                       pg_catalog.current_setting('transaction_isolation') AS isolation,
                       pg_catalog.current_setting('transaction_read_only') AS read_only,
                       pg_catalog.pg_backend_pid() AS pid
                """, (row, number) -> new TransactionObservation(row.getString("identity"),
                row.getString("isolation"), row.getString("read_only"), row.getInt("pid"),
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly())));
    }

    private static void assertResult(LegalPublicRegistrationRequirements actual, Seed seed) {
        LegalPublicRegistrationRequirements expected = new LegalPublicRequirementsValidator().validate(seed.projection());
        assertThat(actual.projection()).isEqualTo(expected.projection());
        assertThat(actual.scopeRevision()).isEqualTo(expected.scopeRevision());
        assertThat(actual.requiredSetRevision()).isEqualTo(expected.requiredSetRevision());
    }

    private void assertTextBatches(int requirementRows, int distinctDocumentRows) {
        // JDBC executions and rows are measured, not network round trips. Shared references do not fetch TEXT twice.
        var snapshot = metrics.snapshot();
        assertThat(snapshot.executionsContaining(TEXT_GATE, "v.afirmacion")).isEqualTo(1);
        assertThat(snapshot.rowsReadContaining(TEXT_GATE, "v.afirmacion")).isEqualTo(requirementRows);
        assertThat(snapshot.executionsContaining(TEXT_GATE, "v.contenido_markdown")).isEqualTo(1);
        assertThat(snapshot.rowsReadContaining(TEXT_GATE, "v.contenido_markdown")).isEqualTo(distinctDocumentRows);
        assertThat(snapshot.matching(TEXT_GATE).keySet()).allSatisfy(sql ->
                assertThat(sql).contains("LEFT JOIN", "FROM (VALUES", "CASE WHEN", " LIMIT "));
    }

    private void assertUnavailable() {
        assertThatThrownBy(service::readRegistration).isInstanceOf(LegalPublicRequirementsReadException.class)
                .hasMessage("El contrato de requisitos no está disponible");
    }

    private static void assertAggregateCounts(long expected) {
        assertThat(aggregateCounts(owner)).containsEntry("legal_requisito_agregados", expected)
                .containsEntry("legal_requisito_agregado_scopes", expected);
    }

    private void assertCommitted() {
        assertThat(metrics.snapshot().commits()).isEqualTo(1);
        assertThat(metrics.snapshot().rollbacks()).isZero();
        assertThat(metrics.snapshot().advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.SHARED)).isEqualTo(1);
        assertThat(metrics.snapshot().advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.EXCLUSIVE)).isZero();
        assertThat(originalPool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private void assertRolledBack() {
        assertThat(metrics.snapshot().commits()).isZero();
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(originalPool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private enum Corruption {
        STATEMENT_DIGEST, STATEMENT_NONCANONICAL, STATEMENT_INVISIBLE, STATEMENT_TOO_LARGE,
        MARKDOWN_DIGEST, MARKDOWN_NONCANONICAL, MARKDOWN_TOO_LARGE, SCOPE_REVISION,
        DOCUMENT_VERSION_TOO_LONG, OPTIONAL_WITHOUT_DOCUMENTS, NO_MANDATORY, REQUIREMENT_VERSION_ORPHAN,
        SET_MEMBER_MISSING, PUBLICATION_MEMBER_MISSING, DOCUMENT_VERSION_ORPHAN,
        DOCUMENT_PUBLICATION_MEMBER_MISSING, DOCUMENT_SLOT_MISSING, DOCUMENT_CONTEXT_MISSING,
        DOCUMENT_ORDINAL_GAP, MANIFEST_ORDINAL_MISMATCH, AUDIENCE_MISSING,
        REQUIREMENT_NOT_CURRENT, DOCUMENT_NOT_CURRENT, FUTURE_EFFECTIVE_AT, PUBLICATION_UNSEALED,
        PUBLICATION_DOCUMENTS_OVER_LIMIT, PUBLICATION_REQUIREMENTS_OVER_LIMIT
    }

    private record TransactionObservation(String identity, String isolation, String readOnly, int pid,
                                          boolean active, boolean springReadOnly) { }
}
