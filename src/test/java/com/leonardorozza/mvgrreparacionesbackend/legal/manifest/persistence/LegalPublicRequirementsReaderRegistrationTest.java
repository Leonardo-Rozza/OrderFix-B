package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Tests the real reader with bounded JDBC rows; PostgreSQL suites own schema, privilege and commit proofs. */
class LegalPublicRequirementsReaderRegistrationTest {
    private static final UUID AGGREGATE = id(1), SET = id(2), PUBLICATION = id(3);
    private static final UUID REQUIREMENT = id(4), REQUIREMENT_LINE = id(5), DOCUMENT = id(6), DOCUMENT_LINE = id(7);
    private static final OffsetDateTime OBSERVED = OffsetDateTime.parse("2026-09-08T12:00:00Z");
    private static final OffsetDateTime PAST = OBSERVED.minusDays(1);
    private static final LegalEditorialTimeBoundary BOUNDARY = new LegalEditorialTimeBoundary(
            OBSERVED.minusSeconds(1).toInstant(), OBSERVED.toInstant());
    private static final String STATEMENT = "Leí los términos del servicio.";
    private static final String MARKDOWN = "# Términos\n\nContenido de prueba.\n";
    private static final String STATEMENT_SHA = sha(STATEMENT), DOCUMENT_SHA = sha(MARKDOWN);

    @Test
    void bothEntryPointsReturnTheSameCanonicalContentThroughIdenticalReadOnlySql() throws Exception {
        List<String> publicSql;
        LegalPublicRegistrationRequirements publicResult;
        try (var fixture = new Fixture()) {
            publicResult = fixture.reader.read(fixture.receipt, BOUNDARY,
                    new LegalPublicRequirementsDeadline(Duration.ofSeconds(15), () -> 0L));
            assertContract(publicResult, fixture.expected);
            publicSql = List.copyOf(fixture.sql);
        }
        AtomicLong nanos = new AtomicLong();
        var deadline = LegalPrivateRequirementsDeadline.registration(nanos::get);
        nanos.set(20_000_000_000L);
        try (var fixture = new Fixture()) {
            assertContract(fixture.read(deadline), publicResult);
            assertThat(fixture.sql).containsExactlyElementsOf(publicSql).hasSize(7)
                    .allMatch(sql -> sql.stripLeading().startsWith("SELECT "));
            assertThat(deadline.remainingMillis()).isEqualTo(10_000);
            verify(fixture.connection, never()).commit();
            verify(fixture.connection, never()).rollback();
        }
    }

    @Test
    void successiveQueriesConsumeTheRemainderOfTheOriginalThirtySecondBudget() throws Exception {
        AtomicLong nanos = new AtomicLong();
        var deadline = LegalPrivateRequirementsDeadline.registration(nanos::get);
        nanos.set(20_000_000_000L);
        try (var fixture = new Fixture()) {
            fixture.checkpoint = (stage, point) -> { if (point == Point.EXECUTE) nanos.addAndGet(1_000_000_000L); };
            assertContract(fixture.read(deadline), fixture.expected);
            assertThat(fixture.sql).hasSize(7);
            assertThat(deadline.remainingMillis()).isEqualTo(3_000);
            nanos.addAndGet(3_000_000_000L);
            assertThatThrownBy(() -> fixture.read(deadline)).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class);
            assertThat(fixture.sql).hasSize(7);
        }
    }

    @Test
    void anAlreadyExpiredRegistrationBudgetCannotReachJdbcOrRenewItself() throws Exception {
        AtomicLong nanos = new AtomicLong();
        var deadline = LegalPrivateRequirementsDeadline.registration(nanos::get);
        nanos.set(30_000_000_000L);
        try (var fixture = new Fixture()) {
            assertThatThrownBy(() -> fixture.read(deadline)).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class);
            assertThat(fixture.sql).isEmpty();
            verify(fixture.source, never()).getConnection();
        }
    }

    @Test
    void thePublicEntryPointRetainsItsFifteenSecondCeilingAndPublicFailureType() throws Exception {
        AtomicLong nanos = new AtomicLong();
        var deadline = new LegalPublicRequirementsDeadline(Duration.ofSeconds(15), nanos::get);
        nanos.set(15_000_000_000L);
        try (var fixture = new Fixture()) {
            assertThatThrownBy(() -> fixture.reader.read(fixture.receipt, BOUNDARY, deadline))
                    .isExactlyInstanceOf(LegalPublicRequirementsReadException.class);
            assertThat(fixture.sql).isEmpty();
        }
        assertThatThrownBy(() -> new LegalPublicRequirementsDeadline(Duration.ofSeconds(30), nanos::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bothEntryPointsRejectMissingBudgetsBeforeJdbc() throws Exception {
        try (var fixture = new Fixture()) {
            assertThatThrownBy(() -> fixture.reader.readForRegistration(fixture.receipt, BOUNDARY, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> fixture.reader.read(fixture.receipt, BOUNDARY, null))
                    .isInstanceOf(NullPointerException.class);
            assertThat(fixture.sql).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("expirations")
    void expirationAtJdbcCheckpointsCancelsAndClosesWithoutReturningPartialContent(Stage stage, Point point) throws Exception {
        AtomicLong nanos = new AtomicLong();
        var deadline = LegalPrivateRequirementsDeadline.registration(nanos::get);
        nanos.set(20_000_000_000L);
        try (var fixture = new Fixture()) {
            fixture.checkpoint = (observedStage, observedPoint) -> {
                if (observedStage == stage && observedPoint == point) nanos.set(30_000_000_000L);
            };
            assertThatThrownBy(() -> fixture.read(deadline)).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class);
            assertThat(fixture.sql).hasSize(stage.ordinal() + 1);
            PreparedStatement active = fixture.statements.getLast();
            verify(active).cancel();
            verify(active).close();
            if (point == Point.PREPARE) verify(active, never()).executeQuery();
            else verify(fixture.results.getLast()).close();
            if (stage == Stage.HEADER) assertThat(fixture.mappedFields.get()).isZero();
        }
    }

    static Stream<Arguments> expirations() {
        return Stream.of(Arguments.of(Stage.HEADER, Point.PREPARE), Arguments.of(Stage.HEADER, Point.EXECUTE),
                Arguments.of(Stage.HEADER, Point.NEXT), Arguments.of(Stage.STATEMENT_TEXT, Point.BYTES),
                Arguments.of(Stage.DOCUMENT_TEXT, Point.BYTES));
    }

    @Test
    void cleanupRecordedDuringFetchRemainsAttachedToTheOriginalDeadline() throws Exception {
        var deadline = LegalPrivateRequirementsDeadline.registration(() -> 0L);
        var cleanup = new SQLException("synthetic reader cleanup failure");
        try (var fixture = new Fixture()) {
            fixture.checkpoint = (stage, point) -> {
                if (stage == Stage.DOCUMENT_TEXT && point == Point.BYTES) deadline.recordCleanupFailure(cleanup);
            };
            assertThatThrownBy(() -> fixture.read(deadline))
                    .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(cleanup);
            assertThatThrownBy(deadline::check).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(cleanup);
            verify(fixture.statements.getLast()).cancel();
            verify(fixture.statements.getLast()).close();
        }
    }

    @Test
    void cleanupAlreadyRecordedPreventsAnyRead() throws Exception {
        var deadline = LegalPrivateRequirementsDeadline.registration(() -> 0L);
        var cleanup = new SQLException("synthetic prior cleanup failure");
        deadline.recordCleanupFailure(cleanup);
        try (var fixture = new Fixture()) {
            assertThatThrownBy(() -> fixture.read(deadline))
                    .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(cleanup);
            assertThat(fixture.sql).isEmpty();
        }
    }

    @Test
    void cancellationFailureCannotReplaceTheExpiredBudget() throws Exception {
        AtomicLong nanos = new AtomicLong();
        var deadline = LegalPrivateRequirementsDeadline.registration(nanos::get);
        try (var fixture = new Fixture()) {
            fixture.cancelFailure = new SQLException("synthetic cancellation failure");
            fixture.checkpoint = (stage, point) -> { if (point == Point.NEXT) nanos.set(30_000_000_000L); };
            assertThatThrownBy(() -> fixture.read(deadline))
                    .isExactlyInstanceOf(LegalPrivateRequirementsReadException.class).hasNoCause();
            verify(fixture.statements.getFirst()).cancel();
            verify(fixture.statements.getFirst()).close();
        }
    }

    @Test
    void sqlFailuresAlsoCancelTheActiveStatementAndKeepTheirOriginalCause() throws Exception {
        var deadline = LegalPrivateRequirementsDeadline.registration(() -> 0L);
        var sqlFailure = new SQLException("synthetic SQL cancellation", "57014");
        try (var fixture = new Fixture()) {
            fixture.executeFailure = sqlFailure;
            assertThatThrownBy(() -> fixture.read(deadline)).isInstanceOf(DataAccessException.class).hasCause(sqlFailure);
            verify(fixture.statements.getFirst()).cancel();
            verify(fixture.statements.getFirst()).close();
        }
    }

    @Test
    void interruptionDuringFetchIsNotClearedOrTranslatedToAPublicDeadline() throws Exception {
        boolean originallyInterrupted = Thread.interrupted();
        try (var fixture = new Fixture()) {
            var deadline = LegalPrivateRequirementsDeadline.registration(() -> 0L);
            fixture.checkpoint = (stage, point) -> { if (point == Point.NEXT) Thread.currentThread().interrupt(); };
            assertThatThrownBy(() -> fixture.read(deadline)).isExactlyInstanceOf(LegalPrivateRequirementsReadException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(fixture.statements.getFirst()).cancel();
            verify(fixture.statements.getFirst()).close();
        } finally {
            Thread.interrupted();
            if (originallyInterrupted) Thread.currentThread().interrupt();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "read-only", "isolation", "unbound", "autocommit", "connection-read-only"})
    void registrationDoesNotRelaxTheExistingPhysicalTransactionRequirements(String fault) throws Exception {
        try (var fixture = new Fixture()) {
            switch (fault) {
                case "inactive" -> TransactionSynchronizationManager.setActualTransactionActive(false);
                case "read-only" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
                case "isolation" -> TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);
                case "unbound" -> TransactionSynchronizationManager.unbindResource(fixture.source);
                case "autocommit" -> when(fixture.connection.getAutoCommit()).thenReturn(true);
                case "connection-read-only" -> when(fixture.connection.isReadOnly()).thenReturn(true);
                default -> throw new AssertionError(fault);
            }
            assertThatThrownBy(() -> fixture.read(LegalPrivateRequirementsDeadline.registration(() -> 0L)))
                    .isExactlyInstanceOf(LegalPublicRequirementsReadException.class);
            assertThat(fixture.sql).isEmpty();
        }
    }

    private enum Stage { HEADER, MEMBERS, PUBLICATION_MEMBERS, REFERENCES, DOCUMENT_METADATA, STATEMENT_TEXT, DOCUMENT_TEXT }
    private enum Point { PREPARE, EXECUTE, NEXT, BYTES }

    private static final class Fixture implements AutoCloseable {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final LegalPublicRequirementsReader reader;
        final LegalPublicRegistrationRequirements expected;
        final LegalRequiredSetAggregateReceipt receipt;
        final List<String> sql = new ArrayList<>();
        final List<PreparedStatement> statements = new ArrayList<>();
        final List<ResultSet> results = new ArrayList<>();
        final AtomicInteger mappedFields = new AtomicInteger();
        BiConsumer<Stage, Point> checkpoint = (stage, point) -> { };
        SQLException cancelFailure;
        SQLException executeFailure;

        Fixture() throws Exception {
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            var document = new LegalRequiredSetProjection.DocumentProjection(DOCUMENT, TipoDocumentoLegal.TERMINOS_SERVICIO,
                    "1.0.0", "Términos", MARKDOWN, DOCUMENT_SHA, PAST, LocaleLegal.ES_AR);
            expected = new LegalPublicRequirementsValidator().validate(new LegalRequiredSetProjection(
                    ContextoLegal.REGISTRO, LocaleLegal.ES_AR, List.of(new LegalRequiredSetProjection.RequirementProjection(
                    REQUIREMENT, ContextoLegal.REGISTRO, TipoActoLegal.ACEPTACION, STATEMENT, STATEMENT_SHA, List.of(document), true))));
            var provenance = new LegalRequiredSetAggregateProvenance(PerfilAgregadoLegal.REGISTRATION, LocaleLegal.ES_AR,
                    AudienciaLegal.ADMIN_TITULAR, List.of(new LegalRequiredSetAggregateProvenance.ScopeOrigin(
                    ContextoLegal.REGISTRO, SET, PUBLICATION)));
            receipt = new LegalRequiredSetAggregateReceipt(LegalRequiredSetAggregateReceipt.Outcome.REUSED,
                    AGGREGATE, expected.requiredSetRevision(), new LegalRequiredSetAggregateProvenanceCalculator().calculate(provenance),
                    provenance, PAST.toInstant());
            when(connection.getAutoCommit()).thenReturn(false);
            when(connection.isReadOnly()).thenReturn(false);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(connection.prepareStatement(anyString(), eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY)))
                    .thenAnswer(invocation -> prepare(invocation.getArgument(0)));
            var jdbc = new JdbcTemplate(source);
            jdbc.setExceptionTranslator(new SQLStateSQLExceptionTranslator());
            reader = new LegalPublicRequirementsReader(jdbc);
            TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(connection));
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        }

        LegalPublicRegistrationRequirements read(LegalPrivateRequirementsDeadline deadline) {
            return reader.readForRegistration(receipt, BOUNDARY, deadline);
        }

        private PreparedStatement prepare(String query) throws Exception {
            Stage stage = Stage.values()[sql.size()];
            sql.add(query);
            Map<String, Object> values = values(stage);
            AtomicInteger row = new AtomicInteger();
            ResultSet result = mock(ResultSet.class, invocation -> {
                String method = invocation.getMethod().getName();
                if (method.equals("next")) {
                    boolean next = row.getAndIncrement() == 0;
                    checkpoint.accept(stage, Point.NEXT);
                    return next;
                }
                if (method.equals("wasNull")) return false;
                if (List.of("getObject", "getString", "getLong", "getBoolean", "getBytes").contains(method)) {
                    mappedFields.incrementAndGet();
                    String column = invocation.getArgument(0);
                    assertThat(values).as("fixture column for " + stage).containsKey(column);
                    Object value = values.get(column);
                    if (method.equals("getLong")) return ((Number) value).longValue();
                    if (method.equals("getBytes")) {
                        checkpoint.accept(stage, Point.BYTES);
                        return ((byte[]) value).clone();
                    }
                    return value;
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
            PreparedStatement statement = mock(PreparedStatement.class);
            when(statement.executeQuery()).thenAnswer(invocation -> {
                checkpoint.accept(stage, Point.EXECUTE);
                if (executeFailure != null) throw executeFailure;
                return result;
            });
            if (cancelFailure != null) doThrow(cancelFailure).when(statement).cancel();
            statements.add(statement);
            results.add(result);
            checkpoint.accept(stage, Point.PREPARE);
            return statement;
        }

        private Map<String, Object> values(Stage stage) {
            return switch (stage) {
                case HEADER -> row("aggregate_id", AGGREGATE, "perfil", "REGISTRATION", "aggregate_locale", "es-AR",
                        "aggregate_audience", "ADMIN_TITULAR", "revision_scheme", "AGGREGATE_V1",
                        "aggregate_revision", expected.requiredSetRevision(), "provenance_fingerprint", receipt.provenanceFingerprint(),
                        "aggregate_created", PAST, "scope_count", 1, "scope_ordinal", 1, "scope_context", "REGISTRO",
                        "scope_locale", "es-AR", "scope_audience", "ADMIN_TITULAR", "scope_set", SET, "scope_publication", PUBLICATION,
                        "set_id", SET, "publicacion_id", PUBLICATION, "locale", "es-AR", "contexto", "REGISTRO", "audiencia", "ADMIN_TITULAR",
                        "current_set", SET, "current_publication", PUBLICATION, "publication_id", PUBLICATION, "publication_locale", "es-AR",
                        "estado_construccion", "SELLADO", "creado_en", PAST, "actualizado_en", PAST, "importado_en", PAST, "sellado_en", PAST,
                        "publication_document_count", 1L, "publication_requirement_count", 1L,
                        "required_set_revision", expected.scopeRevision(), "scope_revision", expected.scopeRevision());
                case MEMBERS -> row("requisito_version_id", REQUIREMENT, "requisito_linea_id", REQUIREMENT_LINE, "manifest_ordinal", 1,
                        "publicacion_id", PUBLICATION, "version_id", REQUIREMENT, "version_line", REQUIREMENT_LINE, "line_id", REQUIREMENT_LINE,
                        "estado", "VIGENTE", "locale", "es-AR", "contexto", "REGISTRO", "applicable_audience", true,
                        "membership_id", id(8), "publication_ordinal", 1, "estado_cambiado_en", PAST,
                        "statement_characters", STATEMENT.codePointCount(0, STATEMENT.length()),
                        "statement_octets", STATEMENT.getBytes(StandardCharsets.UTF_8).length,
                        "tipo_acto", "ACEPTACION", "afirmacion_sha256", STATEMENT_SHA, "requerido", true);
                case PUBLICATION_MEMBERS -> row("requisito_version_id", REQUIREMENT, "version_id", REQUIREMENT,
                        "line_id", REQUIREMENT_LINE, "manifest_ordinal", 1);
                case REFERENCES -> row("requirement_id", REQUIREMENT, "documento_version_id", DOCUMENT, "documento_ordinal", 1);
                case DOCUMENT_METADATA -> row("id", DOCUMENT, "documento_linea_id", DOCUMENT_LINE, "requested_id", DOCUMENT,
                        "line_id", DOCUMENT_LINE, "estado", "VIGENTE", "locale", "es-AR", "context_id", id(9), "membership_id", id(10),
                        "manifest_ordinal", 1, "current_version", DOCUMENT, "current_line", DOCUMENT_LINE, "current_publication", PUBLICATION,
                        "current_state", "VIGENTE", "estado_cambiado_en", PAST, "vigente_desde", PAST,
                        "markdown_octets", MARKDOWN.getBytes(StandardCharsets.UTF_8).length, "tipo", "TERMINOS_SERVICIO",
                        "version", "1.0.0", "titulo", "Términos", "sha256", DOCUMENT_SHA);
                case STATEMENT_TEXT -> row("id", REQUIREMENT, "octets", STATEMENT.getBytes(StandardCharsets.UTF_8).length,
                        "text_utf8", STATEMENT.getBytes(StandardCharsets.UTF_8));
                case DOCUMENT_TEXT -> row("id", DOCUMENT, "octets", MARKDOWN.getBytes(StandardCharsets.UTF_8).length,
                        "text_utf8", MARKDOWN.getBytes(StandardCharsets.UTF_8));
            };
        }

        @Override public void close() {
            TransactionSynchronizationManager.unbindResourceIfPossible(source);
            TransactionSynchronizationManager.clear();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        }
    }

    private static Map<String, Object> row(Object... entries) {
        Map<String, Object> row = new HashMap<>();
        for (int index = 0; index < entries.length; index += 2) row.put((String) entries[index], entries[index + 1]);
        return row;
    }

    private static void assertContract(LegalPublicRegistrationRequirements actual, LegalPublicRegistrationRequirements expected) {
        assertThat(actual.projection()).isEqualTo(expected.projection());
        assertThat(actual.scopeRevision()).isEqualTo(expected.scopeRevision());
        assertThat(actual.requiredSetRevision()).isEqualTo(expected.requiredSetRevision());
    }

    private static UUID id(long low) { return new UUID(0x1000000000004000L, 0x8000000000000000L | low); }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}
