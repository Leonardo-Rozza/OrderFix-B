package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedEditorialPlanReader;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.aggregateCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.clearCatalog;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.seedRegistration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared HTTP observations, real editorial writers and convergent V28 materialization in PostgreSQL 16. */
class LegalPublicRequirementsHttpConcurrencyIT {

    private static final String BASE = "/api/public/requisitos-legales";
    private static final String DOCUMENTS = "/api/public/documentos-legales";
    private static final String REVALIDATE = "public, max-age=0, must-revalidate";
    private static final String DATABASE = "ordenfix_legal_public_requirements_http_concurrency";
    private static final String ROLE = "ordenfix_requirements_http_concurrency_it";
    private static final String DOCUMENT_ROLE = "ordenfix_requirements_history_reader_it";
    private static final String PASSWORD = "requirements-http-concurrency-test-only";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName(DATABASE).withUsername("ordenfix").withPassword("ordenfix");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Runnable NO_CHECKPOINT = () -> { };
    private static JdbcTemplate owner;

    @TempDir
    Path directory;
    private Harness harness;
    private LegalPublicRequirementsHttpITSupport.HttpHarness http;
    private LegalManifestPersistenceITSupport.ApplyHarness apply;
    private LegalEditorialITFixture editorial;

    @BeforeAll
    static void provisionOnlyTheDedicatedContainerAndIndependentConsumerRoles() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        new LegalRestrictedPublicRequirementsRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
        provisionHistoricalReaderRole();
    }

    @BeforeEach
    void prepareTheRealEditorialAndRequirementsGraphs() {
        clearCatalog(owner);
        var source = Objects.requireNonNull(owner.getDataSource());
        apply = LegalManifestPersistenceITSupport.applyHarness(source, LegalDatabaseBudgets.production());
        editorial = new LegalEditorialITFixture(directory, getClass(), owner,
                LegalManifestPersistenceITSupport.harness(source, LegalDatabaseBudgets.production()), apply);
    }

    @AfterEach
    void releaseTheCallerOwnedGraphs() {
        try {
            if (http != null) http.close();
        } finally {
            if (harness != null) {
                try {
                    assertThat(harness.pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                    assertThat(editorialLocks("ShareLock", true)).isZero();
                } finally {
                    harness.close();
                }
            }
        }
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @ParameterizedTest
    @EnumSource(value = Writer.class, names = {"REPLACE", "RETIRE"})
    void twoCompleteSharedObserversPreventARealWriterFromPassingEitherOne(Writer operation) throws Exception {
        String label = operation.name().toLowerCase(java.util.Locale.ROOT);
        var source = seedRegistration(owner, directory, "http-concurrency-" + label + "-source");
        Supplier<LegalEditorialApplyResult> writer;
        UUID targetPublication;
        if (operation == Writer.REPLACE) {
            var target = editorial.importedDocumentRevision("http-concurrency-replace-target", "terminos", "2.0.0");
            var plan = editorial.replacementPlan(source.release(), target, "http-concurrency-replace-operation");
            targetPublication = target.publicationId();
            writer = () -> apply.service().applyReplace(target.release(), plan.plan());
        } else {
            var plan = retireRegistrationDocument(source);
            targetPublication = source.publicationId();
            writer = () -> apply.service().applyRetire(source.release().release(), plan);
        }
        open(UUID::randomUUID);
        MockHttpServletResponse baseline = read();
        assertSuccess(baseline, expectedWire(source.projection()));
        String oldTag = baseline.getHeader("ETag");
        harness.metrics.reset();
        harness.receipts.clear();
        TwoObservers checkpoint = new TwoObservers();
        harness.beforeRead.set(checkpoint::pause);

        runTwoObserversAndWriter(checkpoint, writer, response -> assertSuccess(response, body(baseline)));

        assertThat(harness.metrics.snapshot().commits()).isEqualTo(2);
        assertThat(harness.metrics.snapshot().rollbacks()).isZero();
        assertThat(harness.metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(harness.receipts).hasSize(2).allSatisfy(receipt ->
                assertThat(receipt.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.REUSED));
        harness.metrics.reset();
        if (operation == Writer.REPLACE) {
            MockHttpServletResponse after = read(oldTag);
            assertSuccess(after, expectedWire(observeProjection(targetPublication)));
            assertThat(after.getHeader("ETag")).isNotEqualTo(oldTag);
            assertThat(harness.metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
            assertThat(aggregateCounts(owner)).containsEntry("legal_requisito_agregados", 2L)
                    .containsEntry("legal_requisito_agregado_scopes", 2L);
            harness.metrics.reset();
            assertNotModified(read(after.getHeader("ETag")), after.getHeader("ETag"));
            assertReusedCommit();
        } else {
            assertUnavailable(read(oldTag));
            assertThat(harness.metrics.snapshot().commits()).isZero();
            assertThat(harness.metrics.snapshot().rollbacks()).isEqualTo(1);
            assertThat(harness.metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            assertThat(aggregateCounts(owner)).containsEntry("legal_requisito_agregados", 1L)
                    .containsEntry("legal_requisito_agregado_scopes", 1L);
            assertHistoricalDocumentStillPublic(source.projection().requirements().getFirst().documents().getFirst());
        }
        System.out.println("requirements-http writer=" + operation + " sharedObservers=2 waitsAfterFirst=1");
    }

    @Test
    void freshPromoteWaitsForBothSharedUnavailableObservationsBeforeMakingRegistrationAvailable() throws Exception {
        var target = editorial.importedDraft("http-concurrency-promote-target");
        open(UUID::randomUUID);
        assertUnavailable(read("*"));
        harness.metrics.reset();
        TwoObservers checkpoint = new TwoObservers();
        // No current pointer exists yet, so the real store fails before the content reader can run.
        harness.beforeStore.set(checkpoint::pause);

        runTwoObserversAndWriter(checkpoint, () -> apply.service().applyPromote(target.release()),
                LegalPublicRequirementsHttpConcurrencyIT::assertUnavailable);

        assertThat(harness.metrics.snapshot().commits()).isZero();
        assertThat(harness.metrics.snapshot().rollbacks()).isEqualTo(2);
        assertThat(harness.metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(aggregateCounts(owner)).containsEntry("legal_requisito_agregados", 0L);
        harness.metrics.reset();
        MockHttpServletResponse after = read();
        assertSuccess(after, expectedWire(observeProjection(target.publicationId())));
        assertThat(harness.metrics.snapshot().commits()).isEqualTo(1);
        assertThat(harness.metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        harness.metrics.reset();
        assertNotModified(read(after.getHeader("ETag")), after.getHeader("ETag"));
        assertReusedCommit();
        System.out.println("requirements-http writer=PROMOTE sharedObservers=2 before=503 after=200");
    }

    @Test
    void equivalentHttpCreatorsBothAttemptInsertionAndConvergeToOneDurableIdentity() throws Exception {
        var source = seedRegistration(owner, directory, "http-concurrency-creators");
        CountDownLatch bothLookupsMissed = new CountDownLatch(2);
        CountDownLatch releaseInserts = new CountDownLatch(1);
        AtomicInteger generated = new AtomicInteger();
        // Existing store seam: UUID allocation occurs only after its initial lookup missed.
        open(() -> {
            generated.incrementAndGet();
            bothLookupsMissed.countDown();
            await(releaseInserts);
            return UUID.randomUUID();
        });
        harness.metrics.reset();
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<MockHttpServletResponse> first = workers.submit(() -> read());
            Future<MockHttpServletResponse> second = workers.submit(() -> read());
            try {
                await(bothLookupsMissed);
                assertThat(editorialLocks("ShareLock", true)).isEqualTo(2);
                assertThat(aggregateCounts(owner)).containsEntry("legal_requisito_agregados", 0L);
                releaseInserts.countDown();
                assertSuccess(first.get(10, TimeUnit.SECONDS), expectedWire(source.projection()));
                assertSuccess(second.get(10, TimeUnit.SECONDS), expectedWire(source.projection()));
            } finally {
                releaseInserts.countDown();
                first.cancel(true);
                second.cancel(true);
            }
        }
        assertThat(generated.get()).isEqualTo(2);
        assertThat(harness.receipts).hasSize(2);
        assertThat(harness.receipts).extracting(LegalRequiredSetAggregateReceipt::outcome)
                .containsExactlyInAnyOrder(LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                        LegalRequiredSetAggregateReceipt.Outcome.REUSED);
        assertThat(harness.receipts).extracting(LegalRequiredSetAggregateReceipt::aggregateId)
                .containsOnly(harness.receipts.element().aggregateId());
        assertThat(harness.receipts).extracting(LegalRequiredSetAggregateReceipt::provenanceFingerprint)
                .containsOnly(harness.receipts.element().provenanceFingerprint());
        assertThat(aggregateCounts(owner)).containsEntry("legal_requisito_agregados", 1L)
                .containsEntry("legal_requisito_agregado_scopes", 1L);
        assertThat(harness.metrics.snapshot().commits()).isEqualTo(2);
        assertThat(harness.metrics.snapshot().rollbacks()).isZero();
        // Two header INSERT attempts and one scope batch; the racing REUSED is not DML-free.
        assertThat(harness.metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(3);
        harness.metrics.reset();
        String etag = "W/\"" + harness.receipts.element().requiredSetRevision() + "\"";
        assertNotModified(read(etag), etag);
        assertReusedCommit();
        assertThat(generated.get()).isEqualTo(2);
        System.out.println("requirements-http equivalentCreators=2 durableIdentities=1 raceDml=3 stableDml=0");
    }

    @Test
    void equalSemanticTagWithDifferentPhysicalProvenanceRejectsTheOldReceiptAndRevalidatesTheNewOne() throws Exception {
        var source = seedRegistration(owner, directory, "http-concurrency-provenance-source");
        open(UUID::randomUUID);
        MockHttpServletResponse before = read();
        assertSuccess(before, expectedWire(source.projection()));
        LegalRequiredSetAggregateReceipt oldReceipt = harness.receipts.element();
        var target = LegalPublicRequirementsReadITSupport.replaceDocument(owner, directory, source,
                "cierre-cuenta", "2.0.0", "http-concurrency-provenance-target");
        assertThat(target.publicationId()).isNotEqualTo(source.publicationId());
        assertThat(target.requiredSetId()).isNotEqualTo(source.requiredSetId());
        assertThat(target.projection()).isEqualTo(source.projection());
        assertThat(new LegalPublicRequirementsValidator().validate(target.projection()).requiredSetRevision())
                .isEqualTo(oldReceipt.requiredSetRevision());
        harness.metrics.reset();
        harness.receipts.clear();
        // Deliberate receipt substitution at the reader boundary; the store and reader still execute real SQL.
        harness.substitutedReceipt.set(oldReceipt);
        assertUnavailable(read(before.getHeader("ETag")));
        LegalRequiredSetAggregateReceipt aborted = harness.receipts.element();
        assertThat(aborted.aggregateId()).isNotEqualTo(oldReceipt.aggregateId());
        assertThat(aborted.provenanceFingerprint()).isNotEqualTo(oldReceipt.provenanceFingerprint());
        assertThat(aborted.provenance().scopes().getFirst().publicationId()).isEqualTo(target.publicationId());
        assertThat(harness.metrics.snapshot().commits()).isZero();
        assertThat(harness.metrics.snapshot().rollbacks()).isEqualTo(1);
        assertThat(harness.metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertThat(aggregateCounts(owner)).containsEntry("legal_requisito_agregados", 1L)
                .containsEntry("legal_requisito_agregado_scopes", 1L);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_requisito_agregados WHERE id = ?",
                Long.class, aborted.aggregateId())).isZero();

        harness.substitutedReceipt.set(null);
        harness.receipts.clear();
        harness.metrics.reset();
        assertNotModified(read(before.getHeader("ETag")), before.getHeader("ETag"));
        LegalRequiredSetAggregateReceipt recovered = harness.receipts.element();
        assertThat(recovered.outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
        assertThat(recovered.aggregateId()).isNotEqualTo(oldReceipt.aggregateId()).isNotEqualTo(aborted.aggregateId());
        assertThat(recovered.provenance().scopes().getFirst().requiredSetId()).isEqualTo(target.requiredSetId());
        assertThat(recovered.requiredSetRevision()).isEqualTo(oldReceipt.requiredSetRevision());
        assertThat(aggregateCounts(owner)).containsEntry("legal_requisito_agregados", 2L)
                .containsEntry("legal_requisito_agregado_scopes", 2L);
        assertThat(harness.metrics.snapshot().commits()).isEqualTo(1);
        assertThat(harness.metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        harness.metrics.reset();
        assertNotModified(read(before.getHeader("ETag")), before.getHeader("ETag"));
        assertReusedCommit();
    }

    private void runTwoObserversAndWriter(TwoObservers checkpoint, Supplier<LegalEditorialApplyResult> operation,
                                         ResponseAssertion expected) throws Exception {
        try (var workers = Executors.newFixedThreadPool(3)) {
            Future<MockHttpServletResponse> first = workers.submit(() -> read());
            Future<MockHttpServletResponse> second = null;
            Future<LegalEditorialApplyResult> writer = null;
            try {
                await(checkpoint.firstEntered);
                second = workers.submit(() -> read());
                await(checkpoint.bothEntered);
                assertThat(editorialLocks("ShareLock", true)).isEqualTo(2);
                writer = workers.submit(operation::get);
                awaitCondition(() -> editorialLocks("ExclusiveLock", false) == 1);
                assertThat(writer.isDone()).isFalse();
                checkpoint.releaseFirst.countDown();
                expected.accept(first.get(10, TimeUnit.SECONDS));
                assertThat(editorialLocks("ShareLock", true)).isEqualTo(1);
                assertThat(editorialLocks("ExclusiveLock", false)).isEqualTo(1);
                assertThat(writer.isDone()).isFalse();
                checkpoint.releaseSecond.countDown();
                expected.accept(second.get(10, TimeUnit.SECONDS));
                LegalEditorialApplyResult result = writer.get(15, TimeUnit.SECONDS);
                assertThat(result.status()).as("issues=%s", result.issues()).isEqualTo(LegalManifestStatus.PASS);
                assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
                assertThat(result.persisted()).isTrue();
                assertThat(editorialLocks("ShareLock", true)).isZero();
                assertThat(editorialLocks("ExclusiveLock", false)).isZero();
            } finally {
                checkpoint.releaseFirst.countDown();
                checkpoint.releaseSecond.countDown();
                first.cancel(true);
                if (second != null) second.cancel(true);
                if (writer != null) writer.cancel(true);
            }
        }
    }

    private void open(Supplier<UUID> ids) {
        harness = new Harness(ids);
        http = LegalPublicRequirementsHttpITSupport.openHttp(harness.service);
    }

    private MockHttpServletResponse read(String... etag) throws Exception {
        var request = get(BASE).param("locale", "es-AR").param("contexto", "REGISTRO");
        if (etag.length != 0) request.header("If-None-Match", etag[0]);
        return http.mvc().perform(request).andReturn().getResponse();
    }

    private void assertReusedCommit() {
        assertThat(harness.metrics.snapshot().commits()).isEqualTo(1);
        assertThat(harness.metrics.snapshot().rollbacks()).isZero();
        assertThat(harness.metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(harness.pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private static void assertSuccess(MockHttpServletResponse response, JsonNode expected) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(expected);
        assertThat(response.getHeader("Cache-Control")).isEqualTo(REVALIDATE);
        assertThat(response.getHeader("ETag"))
                .isEqualTo("W/\"" + expected.path("requiredSetRevision").textValue() + "\"");
    }

    private static void assertNotModified(MockHttpServletResponse response, String tag) {
        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("ETag")).isEqualTo(tag);
        assertThat(response.getHeader("Cache-Control")).isEqualTo(REVALIDATE);
    }

    private static void assertUnavailable(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("ETag")).isNull();
        assertThat(body(response).path("code").textValue()).isEqualTo("CONTRATO_LEGAL_NO_DISPONIBLE");
        assertThat(body(response).path("details")).isEqualTo(JSON.readTree("{\"contexto\":\"REGISTRO\",\"locale\":\"es-AR\"}"));
    }

    private ValidatedEditorialPlan retireRegistrationDocument(LegalPublicRequirementsReadITSupport.Seed source)
            throws Exception {
        DocumentProjection document = source.projection().requirements().getFirst().documents().getFirst();
        String external = source.release().release().plan().manifest().publicationId();
        String manifestSha = source.release().release().plan().manifestSha256();
        String fingerprint = apply.readinessCore().observeState(external, editorial.databaseNow()).editorialStateFingerprint();
        ObjectNode plan = JSON.createObjectNode();
        plan.put("schemaVersion", 1);
        plan.put("operationId", UUID.randomUUID().toString());
        plan.put("operationType", "RETIRE");
        plan.put("expectedCurrentPublicationId", external);
        plan.put("expectedCurrentManifestSha256", manifestSha);
        plan.put("expectedEditorialStateFingerprint", fingerprint);
        plan.put("targetPublicationId", external);
        plan.put("targetManifestSha256", manifestSha);
        for (String field : List.of("documentAdditions", "documentReuses", "documentReplacementBatches",
                "documentRetirements", "requirementAdditions", "requirementReuses", "requirementReplacements",
                "requirementRetirements")) plan.putArray(field);
        ObjectNode retirement = plan.withArray("documentRetirements").addObject();
        retirement.put("documentVersionId", document.versionId().toString());
        retirement.put("sha256", document.sha256());
        owner.queryForList("SELECT contexto FROM legal_documento_contextos WHERE documento_version_id = ? ORDER BY contexto",
                String.class, document.versionId()).forEach(retirement.putArray("contexts")::add);
        retirement.put("reason", "Retiro sintético para acreditar indisponibilidad de registro");
        plan.put("expectedReadinessAfter", "NOT_READY");
        plan.put("acknowledgeFailClosedGap", true);
        Path folder = Files.createDirectory(directory.toRealPath().resolve("retirement-plan"));
        Path path = folder.resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
        Files.write(path, JSON.writeValueAsBytes(plan));
        var validated = new LegalEditorialPlanValidator().validate(path);
        assertThat(validated.status()).as("issues=%s", validated.issues()).isEqualTo(LegalManifestStatus.PASS);
        return validated.value().orElseThrow();
    }

    private static LegalRequiredSetProjection observeProjection(UUID publication) {
        UUID set = owner.queryForObject("""
                SELECT id FROM legal_requisito_conjuntos WHERE publicacion_id = ?
                 AND contexto = 'REGISTRO' AND locale = 'es-AR' AND audiencia = 'ADMIN_TITULAR'
                """, UUID.class, publication);
        List<RequirementProjection> requirements = owner.query("""
                SELECT v.id, line.contexto, line.tipo_acto, v.afirmacion, v.afirmacion_sha256, v.requerido
                  FROM legal_requisito_conjunto_miembros member
                  JOIN legal_requisito_versiones v ON v.id = member.requisito_version_id
                  JOIN legal_requisito_lineas line ON line.id = v.requisito_linea_id
                 WHERE member.conjunto_id = ? ORDER BY member.manifest_ordinal
                """, (row, number) -> {
            UUID id = row.getObject("id", UUID.class);
            List<DocumentProjection> documents = owner.query("""
                    SELECT d.id, line.tipo, d.version, d.titulo, d.contenido_markdown, d.sha256, d.vigente_desde, line.locale
                      FROM legal_requisito_documentos reference
                      JOIN legal_documento_versiones d ON d.id = reference.documento_version_id
                      JOIN legal_documento_lineas line ON line.id = d.documento_linea_id
                     WHERE reference.requisito_version_id = ? ORDER BY reference.documento_ordinal
                    """, (document, ordinal) -> new DocumentProjection(document.getObject("id", UUID.class),
                    TipoDocumentoLegal.valueOf(document.getString("tipo")), document.getString("version"),
                    document.getString("titulo"), document.getString("contenido_markdown"), document.getString("sha256"),
                    document.getObject("vigente_desde", OffsetDateTime.class), LocaleLegal.fromCodigo(document.getString("locale"))), id);
            return new RequirementProjection(id, ContextoLegal.valueOf(row.getString("contexto")),
                    TipoActoLegal.valueOf(row.getString("tipo_acto")), row.getString("afirmacion"),
                    row.getString("afirmacion_sha256"), documents, row.getBoolean("requerido"));
        }, set);
        return new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR, requirements);
    }

    private static JsonNode expectedWire(LegalRequiredSetProjection projection) {
        ObjectNode result = JSON.createObjectNode();
        result.put("contexto", projection.context().name());
        result.put("locale", projection.locale().getCodigo());
        result.put("requiredSetRevision", new LegalPublicRequirementsValidator().validate(projection).requiredSetRevision());
        var requirements = result.putArray("requisitos");
        projection.requirements().forEach(requirement -> {
            ObjectNode output = requirements.addObject();
            output.put("id", requirement.versionId().toString());
            output.put("contexto", requirement.context().name());
            output.put("tipoActo", requirement.actType().name());
            output.put("afirmacion", requirement.statement());
            output.put("afirmacionSha256", requirement.statementSha256());
            output.put("requerido", requirement.required());
            var documents = output.putArray("documentos");
            requirement.documents().forEach(document -> {
                ObjectNode item = documents.addObject();
                item.put("id", document.versionId().toString());
                item.put("tipo", document.type().name());
                item.put("version", document.version());
                item.put("titulo", document.title());
                item.put("contenidoMarkdown", document.markdown());
                item.put("sha256", document.sha256());
                item.put("vigenteDesde", document.effectiveAt().toInstant().toString());
                item.put("estado", "VIGENTE");
                item.put("locale", document.locale().getCodigo());
            });
        });
        return result;
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsByteArray());
    }

    private static long editorialLocks(String mode, boolean granted) {
        return owner.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_locks locks
                  JOIN pg_catalog.pg_stat_activity activity ON activity.pid = locks.pid
                 WHERE locks.locktype = 'advisory' AND activity.datname = current_database()
                   AND locks.mode = ? AND locks.granted = ?
                """, Long.class, mode, granted);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(8, TimeUnit.SECONDS)).as("measurable concurrency checkpoint").isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(10); // Poll a real PostgreSQL lock condition, never a fixed scheduling delay.
        }
        assertThat(condition.getAsBoolean()).as("PostgreSQL lock condition before deadline").isTrue();
    }

    /** Local DCL only: exact test database and an additional independent document role, never the requirements role. */
    private static void provisionHistoricalReaderRole() {
        assertThat(owner.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE)
                .startsWith("ordenfix_legal_public_requirements_");
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = ?", Long.class, DOCUMENT_ROLE)).isZero();
        owner.execute("CREATE ROLE " + DOCUMENT_ROLE + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOREPLICATION NOBYPASSRLS PASSWORD '" + PASSWORD + "'");
        owner.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO " + DOCUMENT_ROLE);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + DOCUMENT_ROLE);
        String tables = LegalPublicDocumentPrivilegeVerifier.READ_TABLES.stream().sorted()
                .map(table -> "public." + table).collect(java.util.stream.Collectors.joining(", "));
        owner.execute("GRANT SELECT ON TABLE " + tables + " TO " + DOCUMENT_ROLE);
        owner.execute("ALTER ROLE " + DOCUMENT_ROLE + " IN DATABASE " + DATABASE + " SET search_path TO pg_catalog, public, pg_temp");
        owner.execute("ALTER ROLE " + DOCUMENT_ROLE + " IN DATABASE " + DATABASE + " SET session_replication_role TO origin");
        owner.execute("ALTER ROLE " + DOCUMENT_ROLE + " IN DATABASE " + DATABASE + " SET lo_compat_privileges TO off");
        JdbcTemplate restricted = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), DOCUMENT_ROLE, PASSWORD));
        new LegalV28AggregateSchemaVerifier(restricted, "public").verify();
        new LegalPublicDocumentPrivilegeVerifier(restricted, DOCUMENT_ROLE, "public").verify();
    }

    private static void assertHistoricalDocumentStillPublic(DocumentProjection retired) throws Exception {
        try (AnnotationConfigApplicationContext documents = new AnnotationConfigApplicationContext()) {
            String prefix = LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX;
            documents.getEnvironment().getPropertySources().addFirst(new MapPropertySource("document-history-it", Map.of(
                    LegalPublicDocumentReadDatabaseConfiguration.ENABLED_PROPERTY, "true",
                    prefix + "jdbc-url", POSTGRES.getJdbcUrl(), prefix + "username", DOCUMENT_ROLE,
                    prefix + "password", PASSWORD)));
            documents.register(LegalPublicDocumentReadDatabaseConfiguration.class);
            documents.refresh();
            try (var history = LegalPublicDocumentHttpITSupport.openHttp(documents.getBean(LegalPublicDocumentReadService.class))) {
                var result = history.mvc().perform(get(DOCUMENTS).param("locale", "es-AR").param("size", "100"))
                        .andExpect(status().isOk()).andExpect(header().exists("ETag")).andReturn().getResponse();
                List<JsonNode> found = new ArrayList<>();
                body(result).path("documentos").forEach(document -> {
                    if (document.path("id").textValue().equals(retired.versionId().toString())) found.add(document);
                });
                assertThat(found).singleElement().satisfies(document ->
                        assertThat(document.path("estado").textValue()).isEqualTo("RETIRADA"));
                var exact = history.mvc().perform(get(DOCUMENTS + "/" + retired.versionId()))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"))
                        .andReturn().getResponse();
                assertThat(body(exact).path("contenidoMarkdown").textValue()).isEqualTo(retired.markdown());
                assertThat(body(exact).path("sha256").textValue()).isEqualTo(retired.sha256());
                assertThat(body(exact).path("estado").textValue()).isEqualTo("RETIRADA");
            }
        }
    }

    private static final class TwoObservers {
        final AtomicInteger next = new AtomicInteger();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch bothEntered = new CountDownLatch(2);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch releaseSecond = new CountDownLatch(1);

        void pause() {
            int slot = next.getAndIncrement();
            if (slot >= 2) return;
            firstEntered.countDown();
            bothEntered.countDown();
            await(slot == 0 ? releaseFirst : releaseSecond);
        }
    }

    private static final class Harness implements AutoCloseable {
        final HikariDataSource pool;
        final LegalJdbcMetricsSupport metrics;
        final LegalPublicRequirementsDataSource dataSource;
        final LegalPublicRequirementsReadService service;
        final AtomicReference<Runnable> beforeStore = new AtomicReference<>(NO_CHECKPOINT);
        final AtomicReference<Runnable> beforeRead = new AtomicReference<>(NO_CHECKPOINT);
        final AtomicReference<LegalRequiredSetAggregateReceipt> substitutedReceipt = new AtomicReference<>();
        final ConcurrentLinkedQueue<LegalRequiredSetAggregateReceipt> receipts = new ConcurrentLinkedQueue<>();

        Harness(Supplier<UUID> ids) {
            var config = new LegalPublicRequirementsDatabaseConfiguration();
            String prefix = LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
            var environment = new MockEnvironment().withProperty(prefix + "jdbc-url", POSTGRES.getJdbcUrl())
                    .withProperty(prefix + "username", ROLE).withProperty(prefix + "password", PASSWORD);
            pool = config.legalPublicRequirementsPool(environment);
            metrics = LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO);
            dataSource = new LegalPublicRequirementsDataSource(metrics.dataSource(), Duration.ofSeconds(15));
            var jdbc = config.legalPublicRequirementsJdbc(dataSource);
            var budgets = config.legalPublicRequirementsBudgets();
            var manager = config.legalPublicRequirementsTransactionManager(dataSource);
            var transaction = config.legalPublicRequirementsTransactionTemplate(manager, budgets);
            var schema = config.legalPublicRequirementsSchemaVerifier(jdbc);
            var privileges = config.legalPublicRequirementsPrivilegeVerifier(jdbc, environment);
            var guard = config.legalPublicRequirementsBoundaryGuard(List.of(config.legalPublicRequirementsBoundaryMarker()));
            var gate = config.legalPublicRequirementsGate(transaction, jdbc, budgets, schema, privileges, guard);
            var revision = config.legalPublicRequirementsRevisionCalculator();
            var provenance = config.legalPublicRequirementsProvenanceCalculator();
            var replay = config.legalPublicRequirementsReplayVerifier(jdbc, revision, provenance);
            var store = spy(new LegalRequiredSetAggregateStore(jdbc, revision, provenance, replay, ids));
            doAnswer(invocation -> {
                beforeStore.get().run();
                var receipt = (LegalRequiredSetAggregateReceipt) invocation.callRealMethod();
                receipts.add(receipt);
                return receipt;
            }).when(store).materialize(any(), any());
            var actualReader = config.legalPublicRequirementsReader(jdbc);
            var reader = spy(actualReader);
            doAnswer(invocation -> {
                beforeRead.get().run();
                LegalRequiredSetAggregateReceipt substitute = substitutedReceipt.get();
                return substitute == null ? invocation.callRealMethod()
                        : actualReader.read(substitute, invocation.getArgument(1), invocation.getArgument(2));
            }).when(reader).read(any(), any(), any());
            service = config.legalPublicRequirementsReadService(jdbc, dataSource, gate,
                    config.legalPublicRequirementsScopeResolver(), store, reader, schema, privileges, guard);
        }

        @Override public void close() {
            try { dataSource.close(); } finally { pool.close(); }
        }
    }

    private enum Writer { REPLACE, RETIRE }

    @FunctionalInterface
    private interface ResponseAssertion {
        void accept(MockHttpServletResponse response) throws Exception;
    }
}
