package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentHttpITSupport.HttpHarness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.Seed;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.StoredDocument;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentHttpITSupport.openHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP over the real restricted reader and real editorial writers. Reader spies add only explicit
 * synchronization or a failing JDBC probe; they never replace a successful document observation.
 * Production budgets remain 15/5/1/1 s; only the last test reduces the operation deadline to 2 s.
 */
class LegalPublicDocumentHttpConcurrencyIT {

    private static final String ROOT = "/api/public/documentos-legales";
    private static final String ROLE = "ordenfix_public_document_http_concurrency_it";
    private static final String PASSWORD = "http-concurrency-test-only";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_document_http_concurrency")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static JdbcTemplate owner;

    @TempDir
    Path directory;
    private Seed seed;
    private ReaderHarness reader;
    private HttpHarness http;

    @BeforeAll
    static void provision() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        new LegalRestrictedPublicDocumentRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
    }

    @BeforeEach
    void seedAndOpenHttp() throws Exception {
        LegalPublicDocumentReadITSupport.clearCatalog(owner);
        seed = LegalPublicDocumentReadITSupport.seedCatalog(owner, directory, "http-concurrency-initial");
        reader = reader(Duration.ofSeconds(15));
        http = openHttp(reader.service());
    }

    @AfterEach
    void close() {
        if (http != null) {
            http.close();
        }
        if (reader != null) {
            reader.close();
        }
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void twoHttpReadersShareTheOldSnapshotAndARealReplaceWaitsForBoth() throws Exception {
        Supplier<Seed> replacement = LegalPublicDocumentReadITSupport.prepareReplacement(
                owner, directory, "http-concurrency-successor", seed);
        MockHttpServletResponse oldFirst = catalog(http.mvc(), 0, 3);
        MockHttpServletResponse oldSecond = catalog(http.mvc(), 1, 3);
        UUID oldId = seed.documents().getFirst().id();
        MockHttpServletResponse oldDocument = http.mvc().perform(get(ROOT + "/" + oldId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("VIGENTE"))
                .andReturn().getResponse();
        assertCatalog(oldFirst, seed.documents(), 0, 3);
        assertCatalog(oldSecond, seed.documents(), 1, 3);
        assertThat(body(oldSecond).get("documentSetRevision")).isEqualTo(body(oldFirst).get("documentSetRevision"));

        CountDownLatch bothReading = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        doAnswer(invocation -> {
            int page = invocation.getArgument(2);
            bothReading.countDown();
            await(page == 0 ? releaseFirst : releaseSecond);
            return invocation.callRealMethod();
        }).when(reader.reader()).readCatalog(any(), any(), anyInt(), anyInt(), any());

        try (var workers = Executors.newFixedThreadPool(3)) {
            Future<MockHttpServletResponse> first = workers.submit(() -> catalog(http.mvc(), 0, 3));
            Future<MockHttpServletResponse> second = workers.submit(() -> catalog(http.mvc(), 1, 3));
            Future<Seed> writer = null;
            try {
                await(bothReading);
                assertThat(lockCount("ShareLock", true)).isEqualTo(2);
                writer = workers.submit(replacement::get);
                awaitCondition(() -> lockCount("ExclusiveLock", false) == 1);
                assertThat(writer.isDone()).isFalse();

                releaseFirst.countDown();
                MockHttpServletResponse firstResult = first.get(10, TimeUnit.SECONDS);
                assertThat(body(firstResult)).isEqualTo(body(oldFirst));
                assertThat(firstResult.getHeader("ETag")).isEqualTo(oldFirst.getHeader("ETag"));
                assertThat(lockCount("ShareLock", true)).isEqualTo(1);
                assertThat(lockCount("ExclusiveLock", false)).isEqualTo(1);
                assertThat(writer.isDone()).isFalse();

                releaseSecond.countDown();
                MockHttpServletResponse secondResult = second.get(10, TimeUnit.SECONDS);
                assertThat(body(secondResult)).isEqualTo(body(oldSecond));
                assertThat(secondResult.getHeader("ETag")).isEqualTo(oldSecond.getHeader("ETag"));
                Seed replaced = writer.get(10, TimeUnit.SECONDS);

                MockHttpServletResponse allAfter = catalog(http.mvc(), 0, 100);
                assertCatalog(allAfter, replaced.documents(), 0, 100);
                JsonNode after = body(allAfter);
                assertThat(after.get("documentSetRevision")).isNotEqualTo(body(oldFirst).get("documentSetRevision"));
                assertThat(after.path("page").path("totalElements").asLong()).isEqualTo(22);
                assertThat(replaced.documents()).filteredOn(document -> document.state() == EstadoVersionLegal.REEMPLAZADA)
                        .hasSize(11);
                assertThat(replaced.documents()).filteredOn(document -> document.state() == EstadoVersionLegal.VIGENTE)
                        .hasSize(11);
                MockHttpServletResponse changedPage = http.mvc().perform(get(ROOT).param("locale", "es-AR")
                                .param("page", "0").param("size", "3")
                                .header("If-None-Match", oldFirst.getHeader("ETag")))
                        .andExpect(status().isOk()).andReturn().getResponse();
                assertCatalog(changedPage, replaced.documents(), 0, 3);
                assertThat(body(changedPage).get("documentSetRevision")).isEqualTo(after.get("documentSetRevision"));
                MockHttpServletResponse changedSecond = catalog(http.mvc(), 1, 3);
                assertCatalog(changedSecond, replaced.documents(), 1, 3);
                assertThat(body(changedSecond).get("documentSetRevision")).isEqualTo(after.get("documentSetRevision"));
                http.mvc().perform(get(ROOT + "/" + oldId).header("If-None-Match", oldDocument.getHeader("ETag")))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("REEMPLAZADA"))
                        .andExpect(jsonPath("$.sha256").value(seed.documents().getFirst().sha256()))
                        .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"));
                assertReaderReleased(reader);
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
                first.cancel(true);
                second.cancel(true);
                if (writer != null) {
                    writer.cancel(true);
                }
            }
        }
    }

    @Test
    void anExclusiveLockReturns503BeforeConditionalSuccessAndRecoversAfterRollback() throws Exception {
        String previousEtag = catalog(http.mvc(), 0, 20).getHeader("ETag");
        var counts = LegalManifestPersistenceITSupport.editorialTableCounts(owner);
        try (Connection writer = owner.getDataSource().getConnection()) {
            writer.setAutoCommit(false);
            try {
                try (PreparedStatement lock = writer.prepareStatement("""
                        SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 0))
                        """)) {
                    lock.setString(1, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
                    lock.execute();
                }
                long started = System.nanoTime();
                MvcResult failed = http.mvc().perform(get(ROOT).param("locale", "es-AR")
                                .header("If-None-Match", previousEtag))
                        .andExpect(status().isServiceUnavailable()).andReturn();
                long elapsed = elapsedMillis(started);
                assertUnavailable(failed);
                assertThat(sqlState(failed.getResolvedException())).isEqualTo("55P03");
                assertThat(elapsed).isBetween(900L, 4_000L);
                assertReaderReleased(reader);
                System.out.println("public-document HTTP lock-timeout elapsedMs=" + elapsed);
            } finally {
                writer.rollback();
            }
        }
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(owner)).isEqualTo(counts);
        http.mvc().perform(get(ROOT).param("locale", "es-AR").header("If-None-Match", previousEtag))
                .andExpect(status().isNotModified()).andExpect(header().string("ETag", previousEtag));
        assertReaderReleased(reader);
    }

    @Test
    void poolSaturationReturns503ForAKnownDocumentWithout304OrLeakedReaderLeases() throws Exception {
        UUID id = seed.documents().getFirst().id();
        String previousEtag = http.mvc().perform(get(ROOT + "/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        try (Connection first = reader.pool().getConnection(); Connection second = reader.pool().getConnection()) {
            assertThat(reader.pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
            long started = System.nanoTime();
            MvcResult failed = http.mvc().perform(get(ROOT + "/" + id).header("If-None-Match", previousEtag))
                    .andExpect(status().isServiceUnavailable()).andReturn();
            long elapsed = elapsedMillis(started);
            assertUnavailable(failed);
            assertThat(elapsed).isBetween(900L, 3_000L);
            assertThat(lockCount("ShareLock", true)).isZero();
            assertThat(reader.pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
            System.out.println("public-document HTTP pool-timeout elapsedMs=" + elapsed);
        }
        assertReaderReleased(reader);
        http.mvc().perform(get(ROOT + "/" + id).header("If-None-Match", previousEtag))
                .andExpect(status().isNotModified()).andExpect(header().string("ETag", previousEtag));
        assertReaderReleased(reader);
    }

    @Test
    void deadlineCancelsARealCursorAfterCatalogConstructionAndNeverPublishesItsRevision() throws Exception {
        var counts = LegalManifestPersistenceITSupport.editorialTableCounts(owner);
        var sequences = LegalManifestPersistenceITSupport.editorialSequenceStates(owner);
        try (ReaderHarness shortReader = reader(Duration.ofSeconds(2));
             HttpHarness shortHttp = openHttp(shortReader.service())) {
            String previousEtag = catalog(shortHttp.mvc(), 0, 20).getHeader("ETag");
            AtomicBoolean completeCatalogBuilt = new AtomicBoolean();
            AtomicInteger visited = new AtomicInteger();
            AtomicReference<LegalPublicDocumentDeadline> observationDeadline = new AtomicReference<>();
            doAnswer(invocation -> {
                Object completeCatalog = invocation.callRealMethod();
                completeCatalogBuilt.set(true);
                LegalPublicDocumentDeadline deadline = invocation.getArgument(4);
                observationDeadline.set(deadline);
                // Fault injection after real catalog construction, still inside its shared transaction.
                // The first two FETCH batches complete; the third must be interrupted by the budget.
                shortReader.jdbc().execute((ConnectionCallback<Integer>) connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            SELECT n, pg_catalog.pg_sleep(CASE WHEN n <= 64 THEN 0.005 ELSE 0.1 END)
                              FROM pg_catalog.generate_series(1, 1000) AS n
                            """, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                        statement.setFetchSize(32);
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                visited.incrementAndGet();
                            }
                            return visited.get();
                        } finally {
                            deadline.cancel(statement);
                        }
                    }
                });
                return completeCatalog;
            }).when(shortReader.reader()).readCatalog(any(), any(), anyInt(), anyInt(), any());
            long started = System.nanoTime();
            MvcResult failed = shortHttp.mvc().perform(get(ROOT).param("locale", "es-AR")
                            .header("If-None-Match", previousEtag))
                    .andExpect(status().isServiceUnavailable()).andReturn();
            long elapsed = elapsedMillis(started);
            assertUnavailable(failed);
            assertThat(completeCatalogBuilt.get()).isTrue();
            assertThat(visited.get()).isEqualTo(64);
            assertThat(observationDeadline.get()).isNotNull();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThatThrownBy(observationDeadline.get()::check)
                    .isInstanceOf(LegalPublicDocumentReadException.class);
            String failureSqlState = sqlState(failed.getResolvedException());
            if (failureSqlState != null) {
                assertThat(failureSqlState).isIn("57014", "08006", "08003");
            }
            assertThat(elapsed).isBetween(1_500L, 6_000L);
            assertReaderReleased(shortReader);
            System.out.println("public-document HTTP cursor-deadline elapsedMs=" + elapsed
                    + " visited=" + visited.get() + " deadlineExpired=true sqlState=" + failureSqlState);
        }
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(owner)).isEqualTo(counts);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(owner)).isEqualTo(sequences);
        assertCatalog(catalog(http.mvc(), 0, 20), seed.documents(), 0, 20);
        assertReaderReleased(reader);
    }

    private static ReaderHarness reader(Duration operationBudget) {
        LegalPublicDocumentReadDatabaseConfiguration config = new LegalPublicDocumentReadDatabaseConfiguration();
        String prefix = LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX;
        MockEnvironment environment = new MockEnvironment()
                .withProperty(prefix + "jdbc-url", POSTGRES.getJdbcUrl())
                .withProperty(prefix + "username", ROLE)
                .withProperty(prefix + "password", PASSWORD);
        HikariDataSource pool = config.legalPublicDocumentPool(environment);
        LegalPublicDocumentDataSource dataSource = new LegalPublicDocumentDataSource(pool, operationBudget);
        JdbcTemplate jdbc = config.legalPublicDocumentJdbc(dataSource);
        var budgets = config.legalPublicDocumentBudgets();
        var manager = config.legalPublicDocumentTransactionManager(dataSource);
        var transaction = config.legalPublicDocumentTransactionTemplate(manager, budgets);
        var schema = config.legalPublicDocumentSchemaVerifier(jdbc);
        var privileges = config.legalPublicDocumentPrivilegeVerifier(jdbc, environment);
        var gate = config.legalPublicDocumentGate(transaction, jdbc, budgets, schema, privileges);
        var reader = spy(config.legalPublicDocumentReader(jdbc));
        var guard = config.legalPublicDocumentBoundaryGuard(List.of(config.legalPublicDocumentBoundaryMarker()));
        var service = config.legalPublicDocumentReadService(jdbc, dataSource, gate, reader, schema, privileges, guard);
        return new ReaderHarness(pool, dataSource, jdbc, reader, service);
    }

    private static MockHttpServletResponse catalog(MockMvc mvc, int page, int size) throws Exception {
        return mvc.perform(get(ROOT).param("locale", "es-AR")
                        .param("page", String.valueOf(page)).param("size", String.valueOf(size)))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    private static void assertCatalog(MockHttpServletResponse response, List<StoredDocument> documents,
                                      int page, int size) throws Exception {
        JsonNode catalog = body(response);
        assertThat(catalog.path("page").path("number").asInt()).isEqualTo(page);
        assertThat(catalog.path("page").path("size").asInt()).isEqualTo(size);
        assertThat(catalog.path("page").path("totalElements").asLong()).isEqualTo(documents.size());
        assertThat(catalog.path("page").path("totalPages").asLong()).isEqualTo((documents.size() + size - 1L) / size);
        List<StoredDocument> expected = documents.stream().skip((long) page * size).limit(size).toList();
        assertThat(catalog.path("documentos").size()).isEqualTo(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            StoredDocument document = expected.get(index);
            JsonNode actual = catalog.path("documentos").get(index);
            assertThat(actual.path("id").asText()).isEqualTo(document.id().toString());
            assertThat(actual.path("tipo").asText()).isEqualTo(document.type().name());
            assertThat(actual.path("version").asText()).isEqualTo(document.version());
            assertThat(actual.path("titulo").asText()).isEqualTo(document.title());
            assertThat(actual.path("sha256").asText()).isEqualTo(document.sha256());
            assertThat(actual.path("vigenteDesde").asText()).isEqualTo(document.effectiveAt().toString());
            assertThat(actual.path("estado").asText()).isEqualTo(document.state().name());
            assertThat(actual.path("locale").asText()).isEqualTo("es-AR");
        }
        assertThat(response.getHeader("ETag")).isEqualTo("W/\""
                + catalog.path("documentSetRevision").asText() + ":p=" + page + ":s=" + size + "\"");
    }

    private static void assertUnavailable(MvcResult result) throws Exception {
        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("ETag")).isNull();
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isNotNull();
        JsonNode failure = body(response);
        assertThat(failure.path("code").asText()).isEqualTo("CONTRATO_LEGAL_NO_DISPONIBLE");
        assertThat(failure.path("status").asInt()).isEqualTo(503);
        assertThat(failure.has("documentSetRevision")).isFalse();
        assertThat(failure.has("documentos")).isFalse();
        assertThat(failure.has("contenidoMarkdown")).isFalse();
        assertThat(response.getContentAsString()).doesNotContain(ROLE, PASSWORD, "pg_sleep", "SELECT", "Hikari");
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsByteArray());
    }

    private static String sqlState(Throwable failure) {
        return sqlState(failure, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static String sqlState(Throwable failure, Set<Throwable> visited) {
        if (failure == null || !visited.add(failure)) {
            return null;
        }
        // Spring keeps the original read failure separately when rollback also fails.
        if (failure instanceof TransactionSystemException transaction) {
            String original = sqlState(transaction.getOriginalException(), visited);
            if (original != null) {
                return original;
            }
        }
        if (failure instanceof SQLException sql && sql.getSQLState() != null) {
            return sql.getSQLState();
        }
        String cause = sqlState(failure.getCause(), visited);
        if (cause != null) {
            return cause;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            String state = sqlState(suppressed, visited);
            if (state != null) {
                return state;
            }
        }
        return null;
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static long lockCount(String mode, boolean granted) {
        return owner.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_locks
                 WHERE locktype = 'advisory' AND mode = ? AND granted = ?
                """, Long.class, mode, granted);
    }

    private static void assertReaderReleased(ReaderHarness reader) {
        awaitCondition(() -> lockCount("ShareLock", true) == 0);
        assertThat(reader.pool().getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= until) {
                throw new AssertionError("PostgreSQL no confirmó la condición esperada");
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private record ReaderHarness(HikariDataSource pool, LegalPublicDocumentDataSource dataSource,
                                 JdbcTemplate jdbc, LegalPublicDocumentReader reader,
                                 LegalPublicDocumentReadService service) implements AutoCloseable {
        @Override public void close() {
            dataSource.close();
            pool.close();
        }
    }
}
