package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsCapacityITSupport.Seed;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsCapacityITSupport.seedMaximumRegistration;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsCapacityITSupport.seedRegistration;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.aggregateCounts;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.clearCatalog;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real editorial capacities and bounded HTTP/JDBC observations; synthetic corruption is explicitly local. */
class LegalPublicRequirementsHttpCapacityIT {

    private static final String ROOT = "/api/public/requisitos-legales";
    private static final String TEXT_GATE = "ELSE NULL END AS text_utf8";
    private static final String ROLE = "ordenfix_public_requirements_http_capacity_it";
    private static final String PASSWORD = "public-requirements-http-capacity-test-only";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_public_requirements_http_capacity")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static JdbcTemplate owner;
    @TempDir private Path directory;
    private Harness harness;
    private LegalPublicRequirementsHttpITSupport.HttpHarness http;

    @BeforeAll
    static void provisionRestrictedConsumer() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        new LegalRestrictedPublicRequirementsRoleFixture(owner, POSTGRES.getJdbcUrl(), ROLE, PASSWORD,
                POSTGRES.getDriverClassName()).provisionAndVerify();
    }

    @BeforeEach
    void composeTheProductionDatabaseGraphAndHttpPolicies() {
        clearCatalog(owner);
        harness = harness();
        http = LegalPublicRequirementsHttpITSupport.openHttp(harness.service());
    }

    @AfterEach
    void closeEveryOwnedResource() {
        try {
            if (http != null) {
                http.close();
            }
        } finally {
            if (harness != null) {
                harness.close();
                assertThat(harness.pool().isClosed()).isTrue();
            }
        }
    }

    @AfterAll
    static void stop() { POSTGRES.stop(); }

    @Test
    void completePublicationOf256RequirementsServes251RegistrationMembersAtExactly16MiBAndRejectsOneMoreByte()
            throws Exception {
        Seed seed = seedMaximumRegistration(owner, directory, "requirements-http-capacity-max");
        assertThat(owner.queryForObject("SELECT count(*) FROM public.legal_publicacion_requisitos WHERE publicacion_id = ?",
                Long.class, seed.publicationId())).isEqualTo(256);
        assertThat(seed.projection().requirements()).hasSize(251);
        assertThat(seed.projection().requirements()).filteredOn(requirement -> requirement.required()).hasSize(1);
        assertThat(seed.expandedMarkdownBytes()).isEqualTo(16_777_216L);
        assertThat(seed.distinctMarkdownBytes()).isEqualTo(393_216L);
        var editorialCounts = LegalManifestPersistenceITSupport.editorialTableCounts(owner);
        var sequenceStates = LegalManifestPersistenceITSupport.editorialSequenceStates(owner);

        MockHttpServletResponse response = successfulGet();
        JsonNode wire = body(response);
        assertFullResponse(wire, seed);
        assertThat(wire.path("requisitos").get(250).path("afirmacion").asText())
                .isEqualTo(seed.projection().requirements().getLast().statement());
        assertThat(expandedWireMarkdown(wire)).isEqualTo(16_777_216L);
        int wireBytes = response.getContentAsByteArray().length;
        // Escapes and metadata are real transport overhead; no claim about a bound on JSON or heap.
        assertThat(wireBytes).isGreaterThan(16_777_216);
        assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertBatchedRead(seed, 14, 8);
        assertAggregateCounts(1);
        assertThat(LegalManifestPersistenceITSupport.editorialTableCounts(owner)).isEqualTo(editorialCounts);
        assertThat(LegalManifestPersistenceITSupport.editorialSequenceStates(owner)).isEqualTo(sequenceStates);
        System.out.printf(Locale.ROOT,
                "LEGAL_REQUIREMENTS_CAPACITY publication_requirements=256 registration_requirements=251 references=253 distinct_documents=3 expanded_markdown_bytes=%d distinct_markdown_bytes=%d wire_bytes=%d reader_selects=14 statement_batches=8 document_batches=1%n",
                seed.expandedMarkdownBytes(), seed.distinctMarkdownBytes(), wireBytes);

        String etag = response.getHeader("ETag");
        resetObservations();
        http.mvc().perform(request().header("If-None-Match", etag))
                .andExpect(status().isNotModified()).andExpect(content().string(""));
        assertBatchedRead(seed, 14, 8);
        assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();

        // Owner-only corruption: privacy is referenced once, so a single added byte exceeds the expanded limit by one.
        var privacy = seed.projection().requirements().getFirst().documents().stream()
                .filter(document -> document.type().name().equals("POLITICA_PRIVACIDAD")).findFirst().orElseThrow();
        String excess = privacy.markdown() + "x";
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE public.legal_documento_versiones SET contenido_markdown = ?, sha256 = ? WHERE id = ?
                """, excess, sha256(excess), privacy.versionId()));
        resetObservations();
        unavailable(etag);
        assertThat(harness.metrics().snapshot().executionsContaining(TEXT_GATE)).isZero();
        assertThat(harness.probe().transferredTextBytes()).isZero();
        assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertRollbackAndNoPartialRows(1);
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE public.legal_documento_versiones SET contenido_markdown = ?, sha256 = ? WHERE id = ?
                """, privacy.markdown(), privacy.sha256(), privacy.versionId()));
        resetObservations();
        http.mvc().perform(request().header("If-None-Match", etag)).andExpect(status().isNotModified());
        assertBatchedRead(seed, 14, 8);
        assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
    }

    @Test
    void theLastMemberBeyondSevenStatementBatchesChangesTheTokenThroughARealEditorialReplace() throws Exception {
        Seed initial = seedRegistration(owner, directory, "requirements-last-source", 251);
        MockHttpServletResponse first = successfulGet();
        JsonNode oldWire = body(first);
        Seed changed = LegalPublicRequirementsCapacityITSupport.replaceLastRequirement(owner, directory,
                "requirements-last-target", initial);
        resetObservations();

        MockHttpServletResponse response = http.mvc().perform(request().header("If-None-Match", first.getHeader("ETag")))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode newWire = body(response);

        assertFullResponse(newWire, changed);
        for (int index = 0; index < 250; index++) {
            assertThat(newWire.path("requisitos").get(index)).isEqualTo(oldWire.path("requisitos").get(index));
        }
        assertThat(newWire.path("requisitos").get(250)).isNotEqualTo(oldWire.path("requisitos").get(250));
        assertThat(newWire.path("requiredSetRevision")).isNotEqualTo(oldWire.path("requiredSetRevision"));
        assertThat(response.getHeader("ETag")).isNotEqualTo(first.getHeader("ETag"));
        assertBatchedRead(changed, 14, 8);
        assertAggregateCounts(2);
    }

    @Test
    void aHistoryOf143VersionsDoesNotEnterTheCurrentRegistrationTextOrMetadataBatches() throws Exception {
        Seed history = LegalPublicRequirementsCapacityITSupport.seedHistory(owner, directory, "requirements-history", 13);
        assertThat(owner.queryForObject("SELECT count(*) FROM public.legal_documento_versiones", Long.class)).isEqualTo(143);
        assertThat(owner.queryForObject("SELECT count(*) FROM public.legal_documento_versiones WHERE estado = 'VIGENTE'", Long.class))
                .isEqualTo(11);
        assertThat(owner.queryForObject("SELECT count(*) FROM public.legal_documento_versiones WHERE estado = 'REEMPLAZADA'", Long.class))
                .isEqualTo(132);

        MockHttpServletResponse response = successfulGet();

        assertFullResponse(body(response), history);
        assertBatchedRead(history, 7, 1);
        assertThat(harness.probe().of(Kind.DOCUMENT_METADATA)).singleElement().satisfies(cursor -> {
            assertThat(cursor.trueNext).isEqualTo(3);
            assertThat(cursor.sql).contains("FROM (VALUES", "needed.id", "membership.publicacion_id = ?");
        });
        assertThat(harness.probe().of(Kind.DOCUMENT_TEXT)).singleElement().satisfies(cursor -> {
            assertThat(cursor.trueNext).isEqualTo(3);
            assertThat(cursor.sql).contains("FROM (VALUES", "v.id = needed.id");
        });
        System.out.printf(Locale.ROOT,
                "LEGAL_REQUIREMENTS_HISTORY stored_document_versions=143 current_document_versions=11 response_document_versions=3 reader_selects=7 document_text_rows=3 document_text_bytes=%d wire_bytes=%d%n",
                history.distinctMarkdownBytes(), response.getContentAsByteArray().length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"statement", "markdown"})
    void oversizedStoredTextIsRejectedBeforeAnyTextTransferAndRollsBackTheNewAggregate(String field) throws Exception {
        Seed seed = seedRegistration(owner, directory, "requirements-size-" + field, 65);
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> {
            if (field.equals("statement")) {
                String excess = "a".repeat(1_001);
                owner.update("UPDATE public.legal_requisito_versiones SET afirmacion = ?, afirmacion_sha256 = ? WHERE id = ?",
                        excess, sha256(excess), seed.projection().requirements().getLast().versionId());
            } else {
                String excess = "a".repeat(1_048_577);
                owner.update("UPDATE public.legal_documento_versiones SET contenido_markdown = ?, sha256 = ? WHERE id = ?",
                        excess, sha256(excess), seed.projection().requirements().getFirst().documents().getFirst().versionId());
            }
        });
        unavailable("*");
        assertThat(harness.metrics().snapshot().executionsContaining(TEXT_GATE)).isZero();
        assertThat(harness.probe().transferredTextBytes()).isZero();
        assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertRollbackAndNoPartialRows(0);
    }

    @Test
    void thePublicationRequirementSentinelRejects257BeforeLoadingAnyMemberOrText() throws Exception {
        Seed seed = seedRegistration(owner, directory, "requirements-publication-overflow", 251);
        // Explicit owner corruption of the disposable publication; the normal importer forbids this 257th member.
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                INSERT INTO public.legal_publicacion_requisitos (publicacion_id, requisito_version_id, manifest_ordinal)
                VALUES (?, pg_catalog.gen_random_uuid(), 257)
                """, seed.publicationId()));
        assertThat(owner.queryForObject("SELECT count(*) FROM public.legal_publicacion_requisitos WHERE publicacion_id = ?",
                Long.class, seed.publicationId())).isEqualTo(257);
        unavailable("*");
        assertThat(harness.probe().of(Kind.HEADER)).singleElement().satisfies(cursor ->
                assertThat(cursor.sql).contains("LIMIT 257", "publication_requirement_count"));
        assertThat(harness.probe().of(Kind.MEMBERS)).isEmpty();
        assertThat(harness.probe().transferredTextBytes()).isZero();
        assertRollbackAndNoPartialRows(0);
    }

    @Test
    void injected257thMemberStopsAtTheReaderSentinelBeforeMappingOrDeliveringA256RowPrefix() throws Exception {
        seedRegistration(owner, directory, "requirements-member-sentinel", 65);
        // This probe repeats one valid driver row only for the member query, to reach the guard
        // despite the stricter complete-publication coverage/size rules. It is not a valid release.
        harness.probe().injectMemberOverflow = true;
        unavailable("*");
        assertThat(harness.probe().of(Kind.MEMBERS)).singleElement().satisfies(cursor -> {
            assertThat(cursor.sql).endsWith("LIMIT 257");
            assertThat(cursor.trueNext).isEqualTo(257);
            assertThat(cursor.mappedMemberIds).isEqualTo(256);
            assertThat(cursor.physicalNext).isEqualTo(1);
            assertThat(cursor.falseNext).isZero();
        });
        assertThat(harness.probe().of(Kind.PUBLICATION_MEMBERS)).isEmpty();
        assertThat(harness.probe().transferredTextBytes()).isZero();
        assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isEqualTo(2);
        assertRollbackAndNoPartialRows(0);
    }

    private MockHttpServletResponse successfulGet() throws Exception {
        return http.mvc().perform(request()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=0, must-revalidate"))
                .andReturn().getResponse();
    }

    private void unavailable(String etag) throws Exception {
        http.mvc().perform(request().header("If-None-Match", etag)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.code").value("CONTRATO_LEGAL_NO_DISPONIBLE"))
                .andExpect(jsonPath("$.requisitos").doesNotExist());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return get(ROOT).param("contexto", "REGISTRO").param("locale", "es-AR");
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsByteArray());
    }

    private static void assertFullResponse(JsonNode wire, Seed expected) {
        assertThat(wire.path("requiredSetRevision").asText())
                .isEqualTo(new LegalPublicRequirementsValidator().validate(expected.projection()).requiredSetRevision());
        assertThat(wire.path("requisitos").size()).isEqualTo(expected.projection().requirements().size());
        for (int index = 0; index < expected.projection().requirements().size(); index++) {
            var requirement = expected.projection().requirements().get(index);
            JsonNode received = wire.path("requisitos").get(index);
            assertThat(received.path("id").asText()).isEqualTo(requirement.versionId().toString());
            assertThat(received.path("afirmacion").asText()).isEqualTo(requirement.statement());
            assertThat(received.path("afirmacionSha256").asText()).isEqualTo(requirement.statementSha256());
            assertThat(received.path("requerido").asBoolean()).isEqualTo(requirement.required());
            assertThat(received.path("documentos").size()).isEqualTo(requirement.documents().size());
            for (int documentIndex = 0; documentIndex < requirement.documents().size(); documentIndex++) {
                var document = requirement.documents().get(documentIndex);
                JsonNode actual = received.path("documentos").get(documentIndex);
                assertThat(actual.path("id").asText()).isEqualTo(document.versionId().toString());
                assertThat(actual.path("contenidoMarkdown").asText()).isEqualTo(document.markdown());
                assertThat(actual.path("sha256").asText()).isEqualTo(document.sha256());
                assertThat(actual.path("estado").asText()).isEqualTo("VIGENTE");
            }
        }
    }

    private static long expandedWireMarkdown(JsonNode wire) {
        long bytes = 0;
        for (JsonNode requirement : wire.path("requisitos")) {
            for (JsonNode document : requirement.path("documentos")) {
                bytes = Math.addExact(bytes, document.path("contenidoMarkdown").asText().getBytes(StandardCharsets.UTF_8).length);
            }
        }
        return bytes;
    }

    private void assertBatchedRead(Seed expected, int readerQueries, int statementBatches) {
        var snapshot = harness.metrics().snapshot();
        assertThat(snapshot.commits()).isEqualTo(1);
        assertThat(snapshot.rollbacks()).isZero();
        assertThat(snapshot.advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.SHARED)).isEqualTo(1);
        assertThat(snapshot.advisoryLockExecutions(LegalJdbcMetricsSupport.AdvisoryLockMode.EXCLUSIVE)).isZero();
        assertThat(harness.probe().cursors).hasSize(readerQueries);
        assertThat(harness.probe().of(Kind.STATEMENT_TEXT)).hasSize(statementBatches);
        assertThat(harness.probe().of(Kind.DOCUMENT_TEXT)).hasSize(1);
        assertThat(harness.probe().of(Kind.DOCUMENT_METADATA)).hasSize(1);
        assertThat(snapshot.rowsReadContaining(TEXT_GATE, "v.afirmacion")).isEqualTo(expected.projection().requirements().size());
        assertThat(snapshot.rowsReadContaining(TEXT_GATE, "v.contenido_markdown")).isEqualTo(3);
        assertThat(harness.probe().of(Kind.DOCUMENT_TEXT).stream().mapToLong(cursor -> cursor.textBytes).sum())
                .isEqualTo(expected.distinctMarkdownBytes());
        long expectedStatementBytes = expected.projection().requirements().stream()
                .mapToLong(requirement -> requirement.statement().getBytes(StandardCharsets.UTF_8).length).sum();
        assertThat(harness.probe().of(Kind.STATEMENT_TEXT).stream().mapToLong(cursor -> cursor.textBytes).sum())
                .isEqualTo(expectedStatementBytes);
        for (Cursor cursor : harness.probe().cursors) {
            assertThat(cursor.fetchSize).isEqualTo(32);
            assertThat(cursor.type).isEqualTo(ResultSet.TYPE_FORWARD_ONLY);
            assertThat(cursor.concurrency).isEqualTo(ResultSet.CONCUR_READ_ONLY);
            assertThat(cursor.autoCommit).isFalse();
            assertThat(cursor.readOnly).isFalse();
            assertThat(cursor.isolation).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(cursor.falseNext).isEqualTo(1);
            assertThat(cursor.repositions).isZero();
            assertThat(cursor.maximumBufferedRows).isLessThanOrEqualTo(32);
            if (cursor.trueNext > 32) {
                assertThat(cursor.initialServerCursor).isTrue();
                assertThat(cursor.bufferChanges).isPositive();
            }
        }
        assertResourcesReleased();
    }

    private void assertRollbackAndNoPartialRows(long expected) {
        assertAggregateCounts(expected);
        assertThat(harness.metrics().snapshot().commits()).isZero();
        assertThat(harness.metrics().snapshot().rollbacks()).isEqualTo(1);
        assertResourcesReleased();
    }

    private static void assertAggregateCounts(long expected) {
        assertThat(aggregateCounts(owner)).containsEntry("legal_requisito_agregados", expected)
                .containsEntry("legal_requisito_agregado_scopes", expected);
    }

    private void assertResourcesReleased() {
        assertThat(harness.pool().getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(harness.probe().cursors).allSatisfy(cursor -> {
            assertThat(cursor.statementClosed).isTrue();
            assertThat(cursor.resultClosed).isTrue();
        });
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
        var config = new LegalPublicRequirementsDatabaseConfiguration();
        String prefix = LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
        var environment = new MockEnvironment().withProperty(prefix + "jdbc-url", POSTGRES.getJdbcUrl())
                .withProperty(prefix + "username", ROLE).withProperty(prefix + "password", PASSWORD);
        HikariDataSource pool = config.legalPublicRequirementsPool(environment);
        CursorProbe probe = new CursorProbe(pool);
        var metrics = LegalJdbcMetricsSupport.instrument(probe, Duration.ZERO);
        var bounded = new LegalPublicRequirementsDataSource(metrics.dataSource(), Duration.ofSeconds(15));
        try {
            var jdbc = config.legalPublicRequirementsJdbc(bounded);
            var budgets = config.legalPublicRequirementsBudgets();
            var manager = config.legalPublicRequirementsTransactionManager(bounded);
            var transaction = config.legalPublicRequirementsTransactionTemplate(manager, budgets);
            var schema = config.legalPublicRequirementsSchemaVerifier(jdbc);
            var privileges = config.legalPublicRequirementsPrivilegeVerifier(jdbc, environment);
            var guard = config.legalPublicRequirementsBoundaryGuard(List.of(config.legalPublicRequirementsBoundaryMarker()));
            var gate = config.legalPublicRequirementsGate(transaction, jdbc, budgets, schema, privileges, guard);
            var revision = config.legalPublicRequirementsRevisionCalculator();
            var provenance = config.legalPublicRequirementsProvenanceCalculator();
            var replay = config.legalPublicRequirementsReplayVerifier(jdbc, revision, provenance);
            var store = config.legalPublicRequirementsStore(jdbc, revision, provenance, replay);
            var reader = config.legalPublicRequirementsReader(jdbc);
            var service = config.legalPublicRequirementsReadService(jdbc, bounded, gate,
                    config.legalPublicRequirementsScopeResolver(), store, reader, schema, privileges, guard);
            return new Harness(pool, bounded, probe, metrics, service);
        } catch (RuntimeException | Error failure) {
            bounded.close();
            pool.close();
            throw failure;
        }
    }

    private record Harness(HikariDataSource pool, LegalPublicRequirementsDataSource bounded, CursorProbe probe,
                           LegalJdbcMetricsSupport metrics, LegalPublicRequirementsReadService service) implements AutoCloseable {
        @Override public void close() {
            try { bounded.close(); } finally { pool.close(); }
        }
    }

    private enum Kind { HEADER, MEMBERS, PUBLICATION_MEMBERS, REFERENCES, DOCUMENT_METADATA, STATEMENT_TEXT, DOCUMENT_TEXT }

    private static final class Cursor {
        final Kind kind;
        final String sql;
        int fetchSize, type, concurrency, isolation, trueNext, falseNext, physicalNext, mappedMemberIds;
        int repositions, maximumBufferedRows, bufferChanges;
        long textBytes;
        boolean autoCommit, readOnly, initialServerCursor, statementClosed, resultClosed;
        Object buffer;
        Cursor(Kind kind, String sql) { this.kind = kind; this.sql = sql; }
    }

    /** Driver observation is tied to pgjdbc's current rows/cursor fields. Only the sentinel test enables result injection. */
    private static final class CursorProbe extends AbstractDataSource {
        private final DataSource delegate;
        private final List<Cursor> cursors = new CopyOnWriteArrayList<>();
        private boolean injectMemberOverflow;
        CursorProbe(DataSource delegate) { this.delegate = delegate; }
        List<Cursor> of(Kind kind) { return cursors.stream().filter(cursor -> cursor.kind == kind).toList(); }
        long transferredTextBytes() { return cursors.stream().mapToLong(cursor -> cursor.textBytes).sum(); }
        @Override public Connection getConnection() throws SQLException { return connection(delegate.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return connection(delegate.getConnection(user, password));
        }

        private Connection connection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) { return objectMethod(proxy, method, args); }
                Object returned = invoke(connection, method, args);
                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                    Kind kind = classify(sql);
                    if (kind != null) {
                        Cursor cursor = new Cursor(kind, LegalJdbcMetricsSupport.normalizeSql(sql));
                        cursors.add(cursor);
                        return statement((PreparedStatement) returned, connection, cursor);
                    }
                }
                return returned;
            });
        }

        private PreparedStatement statement(PreparedStatement statement, Connection connection, Cursor cursor) {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) { return objectMethod(proxy, method, args); }
                Object returned = invoke(statement, method, args);
                if (method.getName().equals("executeQuery")) {
                    cursor.fetchSize = statement.getFetchSize();
                    cursor.type = statement.getResultSetType();
                    cursor.concurrency = statement.getResultSetConcurrency();
                    cursor.autoCommit = connection.getAutoCommit();
                    cursor.readOnly = connection.isReadOnly();
                    cursor.isolation = connection.getTransactionIsolation();
                    ResultSet rows = (ResultSet) returned;
                    PgResultSet driver = rows.unwrap(PgResultSet.class);
                    cursor.initialServerCursor = field(driver, "cursor") != null;
                    cursor.buffer = field(driver, "rows");
                    cursor.maximumBufferedRows = ((List<?>) cursor.buffer).size();
                    return result(rows, driver, cursor);
                }
                if (method.getName().equals("close")) { cursor.statementClosed = statement.isClosed(); }
                return returned;
            });
        }

        private ResultSet result(ResultSet rows, PgResultSet driver, Cursor cursor) {
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) { return objectMethod(proxy, method, args); }
                Object returned;
                if (method.getName().equals("next") && injectMemberOverflow && cursor.kind == Kind.MEMBERS) {
                    if (cursor.trueNext == 0) {
                        assertThat(rows.next()).isTrue();
                        cursor.physicalNext++;
                    }
                    returned = cursor.trueNext < 257;
                } else {
                    returned = invoke(rows, method, args);
                    if (method.getName().equals("next")) { cursor.physicalNext++; }
                }
                if (method.getName().equals("next")) {
                    if (Boolean.TRUE.equals(returned)) { cursor.trueNext++; } else { cursor.falseNext++; }
                    Object buffer = field(driver, "rows");
                    if (cursor.buffer != buffer) { cursor.bufferChanges++; cursor.buffer = buffer; }
                    cursor.maximumBufferedRows = Math.max(cursor.maximumBufferedRows, ((List<?>) buffer).size());
                } else if (method.getName().equals("getBytes") && returned instanceof byte[] bytes) {
                    cursor.textBytes += bytes.length;
                } else if (method.getName().equals("getObject") && cursor.kind == Kind.MEMBERS
                        && Objects.equals(args[0], "requisito_version_id")) {
                    cursor.mappedMemberIds++;
                } else if (method.getName().equals("close")) {
                    cursor.resultClosed = rows.isClosed();
                } else if (List.of("absolute", "relative", "previous", "first", "last", "beforeFirst", "afterLast")
                        .contains(method.getName())) { cursor.repositions++; }
                return returned;
            });
        }

        private static Kind classify(String sql) {
            if (sql.contains(TEXT_GATE)) { return sql.contains("v.afirmacion") ? Kind.STATEMENT_TEXT : Kind.DOCUMENT_TEXT; }
            if (sql.contains("FROM public.legal_requisito_agregados a")) { return Kind.HEADER; }
            if (sql.contains("FROM public.legal_requisito_conjunto_miembros m")) { return Kind.MEMBERS; }
            if (sql.contains("FROM public.legal_publicacion_requisitos membership")) { return Kind.PUBLICATION_MEMBERS; }
            if (sql.contains("AS requirement_id, reference.documento_version_id")) { return Kind.REFERENCES; }
            if (sql.contains("AS requested_id, v.id, v.documento_linea_id")) { return Kind.DOCUMENT_METADATA; }
            return null;
        }

        private static Object field(PgResultSet rows, String name) throws ReflectiveOperationException {
            Field field = PgResultSet.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(rows);
        }
        private static Object objectMethod(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "PublicRequirementsCapacityProbe";
                default -> throw new IllegalStateException(method.getName());
            };
        }
        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
