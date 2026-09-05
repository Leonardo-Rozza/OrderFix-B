package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.Seed;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadITSupport.StoredDocument;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.postgresql.jdbc.PgResultSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP capacity over 13 real editorial publications, a restricted PostgreSQL reader and real MVC
 * policies. The driver probe only observes; it neither replaces SQL/results nor adds queries.
 * Driver buffer/portal inspection is intentionally tied to the PostgreSQL JDBC dependency.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LegalPublicDocumentHttpCapacityIT {

    private static final String ROOT = "/api/public/documentos-legales";
    private static final String CATALOG_SELECT = "FROM public.legal_documento_versiones versions";
    private static final String REVALIDATE = "public, max-age=0, must-revalidate";
    private static final String ROLE = "ordenfix_public_document_http_capacity_it";
    private static final String PASSWORD = "public-document-http-capacity-test-only";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_document_http_capacity")
            .withUsername("ordenfix").withPassword("ordenfix");
    @TempDir
    static Path directory;
    private static JdbcTemplate owner;
    private static Seed history;
    private Harness harness;
    private LegalPublicDocumentHttpITSupport.HttpHarness http;

    @BeforeAll
    static void provisionHistoryOnce() throws Exception {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        new LegalRestrictedPublicDocumentRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
        history = LegalPublicDocumentReadITSupport.seedHistory(owner, directory, "http-capacity", 13);
        assertThat(history.documents()).hasSize(143);
        assertThat(history.documents()).filteredOn(document ->
                document.state() == EstadoVersionLegal.VIGENTE).hasSize(11);
        assertThat(history.documents()).filteredOn(document ->
                document.state() == EstadoVersionLegal.REEMPLAZADA).hasSize(132);
    }

    @BeforeEach
    void compose() {
        harness = harness();
        http = LegalPublicDocumentHttpITSupport.openHttp(harness.service());
    }

    @AfterEach
    void close() {
        try {
            if (http != null) {
                http.close();
            }
        } finally {
            if (harness != null) {
                harness.close();
            }
        }
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void everyPageHashesAll143VersionsInOneRealCursorWithoutMarkdownOrNPlusOne() throws Exception {
        var counts = LegalManifestPersistenceITSupport.editorialTableCounts(owner);
        var sequences = LegalManifestPersistenceITSupport.editorialSequenceStates(owner);
        MockHttpServletResponse first = catalog(null, 0, 100);
        JsonNode firstBody = body(first);
        JsonNode second = body(catalog(null, 1, 100));
        JsonNode last = body(catalog(null, 142, 1));
        // The HTTP maximum forces a long offset (214748364700), without manufacturing huge data.
        JsonNode empty = body(catalog(null, Integer.MAX_VALUE, 100));

        assertPage(firstBody, 0, 100, 143, 2, 100);
        assertPage(second, 1, 100, 143, 2, 43);
        assertPage(last, 142, 1, 143, 143, 1);
        assertPage(empty, Integer.MAX_VALUE, 100, 143, 2, 0);
        String revision = firstBody.path("documentSetRevision").asText();
        assertThat(revision).matches("sha256:[0-9a-f]{64}");
        for (JsonNode page : List.of(second, last, empty)) {
            assertThat(page.path("documentSetRevision").asText()).isEqualTo(revision);
        }
        List<UUID> all = new ArrayList<>(ids(firstBody));
        all.addAll(ids(second));
        assertThat(all).containsExactlyElementsOf(history.documents().stream()
                .map(StoredDocument::id).toList()).doesNotHaveDuplicates();
        assertThat(ids(last)).containsExactly(history.documents().getLast().id());
        assertThat(firstBody.path("contexto").isNull()).isTrue();
        assertThat(first.getHeader("ETag")).isEqualTo("W/\"" + revision + ":p=0:s=100\"");
        assertThat(first.getContentAsString()).doesNotContain("contenidoMarkdown");

        http.mvc().perform(get(ROOT).param("locale", "es-AR").param("size", "100")
                        .header("If-None-Match", first.getHeader("ETag")))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andExpect(header().string("Cache-Control", REVALIDATE));
        assertSuccessfulReads(5, 143);
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(owner)).isEqualTo(counts);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(owner)).isEqualTo(sequences);
    }

    @Test
    void historicalContextFilteringUsesExistsWithoutDuplicatingVersionsOrSharingFilterRevisions()
            throws Exception {
        ContextoLegal photos = ContextoLegal.ATESTACION_FOTOS;
        ContextoLegal credentials = ContextoLegal.ATESTACION_CREDENCIALES;
        JsonNode photoBody = body(catalog(photos, 0, 100));
        JsonNode credentialBody = body(catalog(credentials, 0, 100));
        JsonNode empty = body(catalog(photos, Integer.MAX_VALUE, 100));
        List<StoredDocument> expected = history.documents().stream()
                .filter(document -> document.contexts().contains(photos)).toList();
        assertThat(expected).hasSize(26);
        assertThat(expected).filteredOn(document ->
                document.state() == EstadoVersionLegal.REEMPLAZADA).hasSize(24);
        assertPage(photoBody, 0, 100, 26, 1, 26);
        assertPage(credentialBody, 0, 100, 26, 1, 26);
        assertPage(empty, Integer.MAX_VALUE, 100, 26, 1, 0);
        assertThat(ids(photoBody)).containsExactlyElementsOf(expected.stream().map(StoredDocument::id)
                .toList()).doesNotHaveDuplicates();
        assertThat(ids(credentialBody)).containsExactlyElementsOf(ids(photoBody));
        assertThat(photoBody.path("contexto").asText()).isEqualTo(photos.name());
        assertThat(credentialBody.path("contexto").asText()).isEqualTo(credentials.name());
        assertThat(photoBody.path("documentSetRevision")).isNotEqualTo(
                credentialBody.path("documentSetRevision"));
        assertThat(empty.path("documentSetRevision")).isEqualTo(photoBody.path("documentSetRevision"));
        assertSuccessfulReads(3, 26);
        assertThat(harness.metrics().snapshot().matching(CATALOG_SELECT).keySet())
                .allSatisfy(sql -> assertThat(sql).contains("AND EXISTS (")
                        .doesNotContain("JOIN public.legal_documento_contextos"));
    }

    @Test
    void aChangeOnlyInTheLastRowInvalidatesTheFirstPageEtagAfterTheFirstDriverFetch() throws Exception {
        MockHttpServletResponse first = catalog(null, 0, 100);
        JsonNode firstBody = body(first);
        StoredDocument last = history.documents().getLast();
        resetObservations();
        try {
            // Owner-only synthetic drift in this ephemeral DB; this does not model an editorial write.
            LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                    UPDATE public.legal_documento_versiones SET titulo = ? WHERE id = ?
                    """, last.title() + " — cambio final HTTP", last.id()));
            MockHttpServletResponse changed = http.mvc().perform(get(ROOT)
                            .param("locale", "es-AR").param("size", "100")
                            .header("If-None-Match", first.getHeader("ETag")))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", REVALIDATE))
                    .andReturn().getResponse();
            JsonNode changedBody = body(changed);
            assertThat(changedBody.path("documentos")).isEqualTo(firstBody.path("documentos"));
            assertThat(changedBody.path("documentSetRevision"))
                    .isNotEqualTo(firstBody.path("documentSetRevision"));
            assertThat(changed.getHeader("ETag")).isNotEqualTo(first.getHeader("ETag"));
            assertPage(changedBody, 0, 100, 143, 2, 100);
            http.mvc().perform(get(ROOT).param("locale", "es-AR").param("size", "100")
                            .header("If-None-Match", changed.getHeader("ETag")))
                    .andExpect(status().isNotModified())
                    .andExpect(header().string("ETag", changed.getHeader("ETag")));
            assertSuccessfulReads(2, 143);
        } finally {
            LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                    UPDATE public.legal_documento_versiones SET titulo = ? WHERE id = ?
                    """, last.title(), last.id()));
        }
    }

    @Test
    void metadataFailureAfter128RowsReturns503EvenForAConditionalRequestAndReleasesResources()
            throws Exception {
        MockHttpServletResponse baseline = catalog(null, 0, 100);
        StoredDocument last = history.documents().getLast();
        resetObservations();
        try {
            // PostgreSQL accepts year 10000, while the public RFC3339 projection rejects it.
            // The last type has 13 versions: this change sorts into position 131, beyond fetch 128.
            LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                    UPDATE public.legal_documento_versiones
                       SET vigente_desde = TIMESTAMPTZ '10000-01-01 00:00:00+00' WHERE id = ?
                    """, last.id()));
            for (String conditional : List.of("", baseline.getHeader("ETag"))) {
                var request = get(ROOT).param("locale", "es-AR").param("size", "100");
                if (!conditional.isEmpty()) {
                    request.header("If-None-Match", conditional);
                }
                http.mvc().perform(request).andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().doesNotExist("ETag"))
                        .andExpect(jsonPath("$.code").value("CONTRATO_LEGAL_NO_DISPONIBLE"))
                        .andExpect(jsonPath("$.documentos").doesNotExist())
                        .andExpect(jsonPath("$.documentSetRevision").doesNotExist());
            }
            var metrics = harness.metrics().snapshot();
            assertThat(metrics.commits()).isZero();
            assertThat(metrics.rollbacks()).isEqualTo(2);
            assertCatalogOnly(metrics, 2, 262);
            assertThat(harness.probe().cursors).hasSize(2).allSatisfy(cursor -> {
                assertCursor(cursor, 131, false, true);
                assertThat(cursor.cancels).isEqualTo(1);
            });
            assertResourcesReleased();
        } finally {
            LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                    UPDATE public.legal_documento_versiones SET vigente_desde = ? WHERE id = ?
                    """, OffsetDateTime.ofInstant(last.effectiveAt(), ZoneOffset.UTC), last.id()));
        }
        resetObservations();
        assertThat(body(catalog(null, 0, 100)).path("documentSetRevision"))
                .isEqualTo(body(baseline).path("documentSetRevision"));
        assertSuccessfulReads(1, 143);
    }

    private MockHttpServletResponse catalog(ContextoLegal context, int page, int size) throws Exception {
        var request = get(ROOT).param("locale", "es-AR")
                .param("page", Integer.toString(page)).param("size", Integer.toString(size));
        if (context != null) {
            request.param("contexto", context.name());
        }
        return http.mvc().perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", REVALIDATE)).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsByteArray());
    }

    private static List<UUID> ids(JsonNode response) {
        List<UUID> ids = new ArrayList<>();
        response.path("documentos").forEach(document -> ids.add(UUID.fromString(document.path("id").asText())));
        return ids;
    }

    private static void assertPage(JsonNode response, int page, int size, long total, long pages,
                                   int returned) {
        JsonNode metadata = response.path("page");
        assertThat(metadata.path("number").asInt()).isEqualTo(page);
        assertThat(metadata.path("size").asInt()).isEqualTo(size);
        assertThat(metadata.path("totalElements").asLong()).isEqualTo(total);
        assertThat(metadata.path("totalPages").asLong()).isEqualTo(pages);
        assertThat(response.path("documentos").size()).isEqualTo(returned);
    }

    private void assertSuccessfulReads(int requests, int rowsPerRequest) {
        var metrics = harness.metrics().snapshot();
        assertThat(metrics.commits()).isEqualTo(requests);
        assertThat(metrics.rollbacks()).isZero();
        assertCatalogOnly(metrics, requests, (long) requests * rowsPerRequest);
        assertThat(harness.probe().cursors).hasSize(requests).allSatisfy(cursor -> {
            assertCursor(cursor, rowsPerRequest, true, rowsPerRequest > 128);
            assertThat(cursor.cancels).isZero();
        });
        assertResourcesReleased();
    }

    private static void assertCatalogOnly(LegalJdbcMetricsSupport.Snapshot metrics, int requests,
                                          long rows) {
        assertThat(metrics.executionsContaining(CATALOG_SELECT)).isEqualTo(requests);
        assertThat(metrics.rowsReadContaining(CATALOG_SELECT)).isEqualTo(rows);
        assertThat(metrics.executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertThat(metrics.executions(LegalJdbcMetricsSupport.Category.ROW_LOCK)).isZero();
        assertThat(metrics.advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.SHARED))
                .isEqualTo(requests);
        assertThat(metrics.advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.EXCLUSIVE))
                .isZero();
        var documentQueries = metrics.bySql().values().stream().filter(sql ->
                sql.sql().toLowerCase(Locale.ROOT).matches(
                        ".*\\b(from|join)\\s+public\\.legal_documento_(lineas|versiones|contextos)\\b.*"))
                .toList();
        assertThat(documentQueries).hasSize(1);
        assertThat(documentQueries.getFirst().executions()).isEqualTo(requests);
        assertThat(documentQueries.getFirst().sql().toLowerCase(Locale.ROOT))
                .doesNotContain("contenido_markdown", "markdown_utf8", "count(", " limit ", " offset ");
    }

    private static void assertCursor(CursorObservation cursor, int rows, boolean exhausted,
                                      boolean crossedFetch) {
        assertThat(cursor.fetchSize).isEqualTo(128);
        assertThat(cursor.type).isEqualTo(ResultSet.TYPE_FORWARD_ONLY);
        assertThat(cursor.concurrency).isEqualTo(ResultSet.CONCUR_READ_ONLY);
        assertThat(cursor.autoCommit).isFalse();
        assertThat(cursor.readOnly).isTrue();
        assertThat(cursor.isolation).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(cursor.initialBufferedRows).isEqualTo(Math.min(128, rows));
        assertThat(cursor.maximumBufferedRows).isLessThanOrEqualTo(128);
        assertThat(cursor.trueNext).isEqualTo(rows);
        assertThat(cursor.falseNext).isEqualTo(exhausted ? 1 : 0);
        assertThat(cursor.repositions).isZero();
        assertThat(cursor.resultClosed).isTrue();
        assertThat(cursor.statementClosed).isTrue();
        if (crossedFetch) {
            assertThat(cursor.initialServerCursor).isTrue();
            assertThat(cursor.bufferChanges).isPositive();
        }
    }

    private void assertResourcesReleased() {
        assertThat(harness.pool().getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(harness.pool().getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_stat_activity
                 WHERE usename = ? AND (state <> 'idle' OR xact_start IS NOT NULL)
                """, Long.class, ROLE)).isZero();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_locks locks
                JOIN pg_catalog.pg_stat_activity activity ON activity.pid = locks.pid
                 WHERE activity.usename = ? AND locks.locktype = 'advisory'
                """, Long.class, ROLE)).isZero();
    }

    private void resetObservations() {
        assertResourcesReleased();
        harness.metrics().reset();
        harness.probe().cursors.clear();
    }

    private static Harness harness() {
        LegalPublicDocumentReadDatabaseConfiguration config = new LegalPublicDocumentReadDatabaseConfiguration();
        String prefix = LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX;
        MockEnvironment environment = new MockEnvironment()
                .withProperty(prefix + "jdbc-url", POSTGRES.getJdbcUrl())
                .withProperty(prefix + "username", ROLE).withProperty(prefix + "password", PASSWORD);
        HikariDataSource pool = config.legalPublicDocumentPool(environment);
        CursorProbe probe = new CursorProbe(pool);
        LegalJdbcMetricsSupport metrics = LegalJdbcMetricsSupport.instrument(probe, Duration.ZERO);
        LegalPublicDocumentDataSource dataSource =
                new LegalPublicDocumentDataSource(metrics.dataSource(), Duration.ofSeconds(15));
        JdbcTemplate jdbc = config.legalPublicDocumentJdbc(dataSource);
        var budgets = config.legalPublicDocumentBudgets();
        var manager = config.legalPublicDocumentTransactionManager(dataSource);
        var transaction = config.legalPublicDocumentTransactionTemplate(manager, budgets);
        var schema = config.legalPublicDocumentSchemaVerifier(jdbc);
        var privileges = config.legalPublicDocumentPrivilegeVerifier(jdbc, environment);
        var gate = config.legalPublicDocumentGate(transaction, jdbc, budgets, schema, privileges);
        var reader = config.legalPublicDocumentReader(jdbc);
        var service = new LegalPublicDocumentReadService(jdbc, dataSource, gate, reader, schema, privileges);
        return new Harness(pool, dataSource, probe, metrics, service);
    }

    private record Harness(HikariDataSource pool, LegalPublicDocumentDataSource dataSource,
                           CursorProbe probe, LegalJdbcMetricsSupport metrics,
                           LegalPublicDocumentReadService service) implements AutoCloseable {
        @Override
        public void close() {
            try {
                dataSource.close();
            } finally {
                pool.close();
            }
        }
    }

    private static final class CursorObservation {
        int fetchSize;
        int type;
        int concurrency;
        boolean autoCommit;
        boolean readOnly;
        int isolation;
        boolean initialServerCursor;
        int initialBufferedRows;
        int maximumBufferedRows;
        int bufferChanges;
        Object buffer;
        int trueNext;
        int falseNext;
        int repositions;
        int cancels;
        boolean resultClosed;
        boolean statementClosed;
    }

    /** This innermost wrapper observes the genuine driver cursor before metrics wrap the result. */
    private static final class CursorProbe extends AbstractDataSource {
        private final DataSource delegate;
        private final List<CursorObservation> cursors = new CopyOnWriteArrayList<>();

        private CursorProbe(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return connection(delegate.getConnection(username, password));
        }

        private Connection connection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        Object result = invoke(connection, method, args);
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                && sql.contains(CATALOG_SELECT) && !sql.contains("contenido_markdown")) {
                            CursorObservation observation = new CursorObservation();
                            cursors.add(observation);
                            return statement((PreparedStatement) result, connection, observation);
                        }
                        return result;
                    });
        }

        private PreparedStatement statement(PreparedStatement statement, Connection connection,
                                             CursorObservation observation) {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        if (method.getName().equals("executeQuery")) {
                            observation.fetchSize = statement.getFetchSize();
                            observation.type = statement.getResultSetType();
                            observation.concurrency = statement.getResultSetConcurrency();
                            observation.autoCommit = connection.getAutoCommit();
                            observation.readOnly = connection.isReadOnly();
                            observation.isolation = connection.getTransactionIsolation();
                            ResultSet result = (ResultSet) invoke(statement, method, args);
                            PgResultSet driver = result.unwrap(PgResultSet.class);
                            observation.initialServerCursor = field(driver, "cursor") != null;
                            observation.buffer = field(driver, "rows");
                            observation.initialBufferedRows = ((List<?>) observation.buffer).size();
                            observation.maximumBufferedRows = observation.initialBufferedRows;
                            return result(result, driver, observation);
                        }
                        Object result = invoke(statement, method, args);
                        if (method.getName().equals("cancel")) {
                            observation.cancels++;
                        } else if (method.getName().equals("close")) {
                            observation.statementClosed = statement.isClosed();
                        }
                        return result;
                    });
        }

        private ResultSet result(ResultSet result, PgResultSet driver, CursorObservation observation) {
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        Object value = invoke(result, method, args);
                        if (method.getName().equals("next")) {
                            if (Boolean.TRUE.equals(value)) {
                                observation.trueNext++;
                            } else {
                                observation.falseNext++;
                            }
                            Object buffer = field(driver, "rows");
                            if (buffer != observation.buffer) {
                                observation.bufferChanges++;
                                observation.buffer = buffer;
                            }
                            observation.maximumBufferedRows = Math.max(observation.maximumBufferedRows,
                                    ((List<?>) buffer).size());
                        } else if (method.getName().equals("close")) {
                            observation.resultClosed = result.isClosed();
                        } else if (List.of("absolute", "relative", "previous", "first", "last",
                                "beforeFirst", "afterLast").contains(method.getName())) {
                            observation.repositions++;
                        }
                        return value;
                    });
        }

        private static Object field(PgResultSet result, String name) throws ReflectiveOperationException {
            Field field = PgResultSet.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(result);
        }

        private static Object objectMethod(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "PublicDocumentHttpCapacityCursorProbe";
                default -> throw new IllegalStateException(method.getName());
            };
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
