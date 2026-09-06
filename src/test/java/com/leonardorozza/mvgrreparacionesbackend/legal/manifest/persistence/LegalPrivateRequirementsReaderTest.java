package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegalPrivateRequirementsReaderTest {
    private static final Instant DATE = Instant.parse("2026-09-06T03:00:00Z");
    private static final UUID AGGREGATE = id(1);
    private static final UUID SET = id(2);
    private static final UUID PUBLICATION = id(3);
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private DataSource bound;

    @AfterEach
    void clearTransaction() {
        if (bound != null && TransactionSynchronizationManager.hasResource(bound)) {
            TransactionSynchronizationManager.unbindResource(bound);
        }
        TransactionSynchronizationManager.clear();
    }

    @Test
    void constructionRequiresItsOwnJdbcDataSource() {
        assertThrows(NullPointerException.class, () -> new LegalPrivateRequirementsReader(null));
        assertThrows(NullPointerException.class, () -> new LegalPrivateRequirementsReader(new JdbcTemplate()));
        JdbcTemplate jdbc = new JdbcTemplate(mock(DataSource.class));
        LegalPrivateRequirementsReader reader = new LegalPrivateRequirementsReader(jdbc);
        assertTrue(reader.usesJdbc(jdbc));
        assertFalse(reader.usesJdbc(new JdbcTemplate(jdbc.getDataSource())));
    }

    @Test
    void cannotAccreditOutsideATransactionEvenWithAConstructibleActorAndReceipt() {
        DataSource source = mock(DataSource.class);
        LegalPrivateRequirementsReader reader = new LegalPrivateRequirementsReader(new JdbcTemplate(source));
        assertThrows(LegalPrivateRequirementsReadException.class,
                () -> reader.read(actor(), scopes(), receipt(), boundary(), deadline()));
        verifyNoInteractions(source);
    }

    @ParameterizedTest
    @MethodSource("invalidTransactions")
    void requiresTheMutableReadCommittedTransaction(boolean actual, boolean readOnly, Integer isolation) throws Exception {
        Fixture fixture = boundFixture();
        TransactionSynchronizationManager.setActualTransactionActive(actual);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
        assertThrows(LegalPrivateRequirementsReadException.class, fixture::read);
        verify(fixture.connection, never()).prepareStatement(anyString(), anyInt(), anyInt());
    }

    static Stream<Arguments> invalidTransactions() {
        return Stream.of(Arguments.of(false, false, Connection.TRANSACTION_READ_COMMITTED),
                Arguments.of(true, true, Connection.TRANSACTION_READ_COMMITTED),
                Arguments.of(true, false, Connection.TRANSACTION_REPEATABLE_READ),
                Arguments.of(true, false, null));
    }

    @Test
    void thePhysicalConnectionMustMatchTheDeclaredTransaction() throws Exception {
        Fixture fixture = boundFixture();
        when(fixture.connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
        assertThrows(LegalPrivateRequirementsReadException.class, fixture::read);
        verify(fixture.connection, never()).prepareStatement(anyString(), anyInt(), anyInt());
    }

    @Test
    void registrationOrAnotherAudienceCannotEnterThePrivateReader() throws Exception {
        Fixture fixture = boundFixture();
        var foreign = new LegalRequiredSetAggregateProvenance(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR, receipt().provenance().scopes());
        var incompatible = new LegalRequiredSetAggregateReceipt(LegalRequiredSetAggregateReceipt.Outcome.REUSED,
                AGGREGATE, DIGEST, DIGEST, foreign, DATE);
        assertThrows(LegalPrivateRequirementsReadException.class, () -> fixture.reader.read(
                actor(), scopes(), incompatible, boundary(), deadline()));
        verify(fixture.connection, never()).prepareStatement(anyString(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @MethodSource("corruptHeaders")
    void aCorruptCompositionFailsBeforeMembersOrTextCanBeRead(String column, Object value) throws Exception {
        Fixture fixture = boundFixture();
        Map<String, Object> header = validHeader();
        header.put(column, value);
        ResultSet rows = rows(List.of(header));
        when(fixture.statement.executeQuery()).thenReturn(rows);
        assertThrows(LegalPrivateRequirementsReadException.class, fixture::read);
        verify(fixture.connection, times(1)).prepareStatement(anyString(), anyInt(), anyInt());
        verify(fixture.statement).cancel();
        verify(rows).close();
        verify(fixture.statement).close();
    }

    static Stream<Arguments> corruptHeaders() {
        return Stream.of(Arguments.of("scope_count", 2L), Arguments.of("scope_ordinal", 2L),
                Arguments.of("scope_context", "REGISTRO"), Arguments.of("scope_audience", "ADMIN_TITULAR"),
                Arguments.of("scope_set", id(99)), Arguments.of("current_set", id(99)),
                Arguments.of("current_publication", id(99)), Arguments.of("publication_id", null),
                Arguments.of("estado_construccion", "CONSTRUYENDO"),
                Arguments.of("scope_revision", "sha256:" + "b".repeat(64)),
                Arguments.of("aggregate_revision", "sha256:" + "b".repeat(64)),
                Arguments.of("publication_document_count", 129L), Arguments.of("publication_requirement_count", 257L),
                Arguments.of("sellado_en", DATE.plusSeconds(100).atOffset(ZoneOffset.UTC)));
    }

    @Test
    void anExcessRowIsNeverMappedAndAllResourcesClose() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, true, false);
        AtomicInteger mapped = new AtomicInteger();
        assertThrows(LegalPrivateRequirementsReadException.class, () -> LegalPrivateRequirementsReader.query(
                connection, "SELECT bounded", List.of(10L), 2, ignored -> mapped.incrementAndGet(), deadline()));
        assertEquals(2, mapped.get());
        verify(connection).prepareStatement("SELECT bounded LIMIT 3", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        verify(statement).setFetchSize(32);
        verify(statement).setObject(1, 10L);
        verify(statement).cancel();
        verify(rows).close();
        verify(statement).close();
    }

    @Test
    void zeroRemainingRowsStillExecutesTheSingleSentinelWithoutMapping() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        AtomicInteger mapped = new AtomicInteger();
        assertThrows(LegalPrivateRequirementsReadException.class, () -> LegalPrivateRequirementsReader.query(
                connection, "SELECT evidence", List.of(), 0, ignored -> mapped.incrementAndGet(), deadline()));
        assertEquals(0, mapped.get());
        verify(connection).prepareStatement("SELECT evidence LIMIT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    @Test
    void aFetchSQLExceptionRetainsItsIdentityAndClosesTheCursor() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        SQLException failure = new SQLException("fixture", "57014");
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenThrow(failure);
        SQLException actual = assertThrows(SQLException.class, () -> LegalPrivateRequirementsReader.query(
                connection, "SELECT evidence", List.of(), 3, ignored -> 1, deadline()));
        assertSame(failure, actual);
        assertEquals("57014", actual.getSQLState());
        verify(rows).close();
        verify(statement).cancel();
        verify(statement).close();
    }

    @Test
    void theDeadlineIsNotRestartedBetweenFetches() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true);
        AtomicLong clock = new AtomicLong();
        LegalPrivateRequirementsDeadline deadline = new LegalPrivateRequirementsDeadline(Duration.ofMillis(1), clock::get);
        AtomicInteger mapped = new AtomicInteger();
        assertThrows(LegalPrivateRequirementsReadException.class, () -> LegalPrivateRequirementsReader.query(
                connection, "SELECT evidence", List.of(), 2, ignored -> {
                    clock.set(1_000_000L);
                    return mapped.incrementAndGet();
                }, deadline));
        assertEquals(1, mapped.get());
        verify(rows).close();
        verify(statement).cancel();
        verify(statement).close();
    }

    @Test
    void budgetAdmitsExactRowsAndBytesWithoutAllocatingLargeContent() {
        var budget = new LegalPrivateRequirementsReader.ObservationBudget();
        budget.addRows(65_535);
        assertEquals(1, budget.remainingRows());
        budget.addRows(1);
        assertEquals(0, budget.remainingRows());
        assertThrows(LegalPrivateRequirementsReadException.class, () -> budget.addRows(1));
        budget.addSourceBytes(128L * 1024 * 1024 - 1);
        budget.addSourceBytes(1);
        assertThrows(LegalPrivateRequirementsReadException.class, () -> budget.addSourceBytes(1));
    }

    @Test
    void budgetRejectsNegativeAndOverflowingContributions() {
        var budget = new LegalPrivateRequirementsReader.ObservationBudget();
        assertThrows(LegalPrivateRequirementsReadException.class, () -> budget.addRows(-1));
        assertThrows(LegalPrivateRequirementsReadException.class, () -> budget.addRows(Integer.MAX_VALUE));
        assertThrows(LegalPrivateRequirementsReadException.class, () -> budget.addSourceBytes(-1));
        assertThrows(LegalPrivateRequirementsReadException.class, () -> budget.addSourceBytes(Long.MAX_VALUE));
        assertEquals(65_536, budget.remainingRows());
    }

    @ParameterizedTest
    @EnumSource(EstadoVersionLegal.class)
    void everyLegalHistoryStateHasItsExactTransitionChain(EstadoVersionLegal state) {
        List<LegalPrivateRequirementsReader.Transition> transitions = transitions(state);
        Instant changed = transitions.isEmpty() ? null : transitions.getLast().at();
        var version = version(state, changed);
        Instant activated = LegalPrivateRequirementsReader.accreditTransitions(version, transitions);
        assertEquals(state == EstadoVersionLegal.BORRADOR || state == EstadoVersionLegal.PUBLICADA
                ? null : DATE.plusSeconds(2), activated);
    }

    @Test
    void missingPublishedHistoryCannotBeInterpretedAsAFalseReacceptanceFlag() {
        var version = version(EstadoVersionLegal.VIGENTE, DATE.plusSeconds(2));
        assertThrows(LegalPrivateRequirementsReadException.class, () -> LegalPrivateRequirementsReader.accreditTransitions(
                version, List.of(new LegalPrivateRequirementsReader.Transition(
                        EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE, DATE.plusSeconds(2)))));
        assertThrows(LegalPrivateRequirementsReadException.class,
                () -> LegalPrivateRequirementsReader.accreditTransitions(version, List.of()));
    }

    @Test
    void transitionTimesAndTheStoredFinalStateMustAgree() {
        var valid = transitions(EstadoVersionLegal.VIGENTE);
        assertThrows(LegalPrivateRequirementsReadException.class, () -> LegalPrivateRequirementsReader.accreditTransitions(
                version(EstadoVersionLegal.VIGENTE, DATE.plusSeconds(3)), valid));
        assertThrows(LegalPrivateRequirementsReadException.class, () -> LegalPrivateRequirementsReader.accreditTransitions(
                version(EstadoVersionLegal.PUBLICADA, DATE.plusSeconds(2)), valid));
        assertThrows(LegalPrivateRequirementsReadException.class, () -> LegalPrivateRequirementsReader.accreditTransitions(
                version(EstadoVersionLegal.VIGENTE, DATE), List.of(valid.getFirst(),
                        new LegalPrivateRequirementsReader.Transition(EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE, DATE))));
    }

    @Test
    void repeatedOrReorderedTransitionEdgesFailClosed() {
        var chain = transitions(EstadoVersionLegal.REEMPLAZADA);
        var version = version(EstadoVersionLegal.REEMPLAZADA, DATE.plusSeconds(3));
        assertThrows(LegalPrivateRequirementsReadException.class, () -> LegalPrivateRequirementsReader.accreditTransitions(
                version, List.of(chain.getFirst(), chain.getFirst(), chain.get(1), chain.get(2))));
        assertThrows(LegalPrivateRequirementsReadException.class, () -> LegalPrivateRequirementsReader.accreditTransitions(
                version, List.of(chain.get(1), chain.getFirst(), chain.get(2))));
    }

    private Fixture boundFixture() throws SQLException {
        bound = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        TransactionSynchronizationManager.bindResource(bound, new ConnectionHolder(connection));
        return new Fixture(new LegalPrivateRequirementsReader(new JdbcTemplate(bound)), connection, statement);
    }

    private static ResultSet rows(List<Map<String, Object>> values) throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        AtomicInteger nullState = new AtomicInteger();
        when(rows.next()).thenAnswer(ignored -> index.incrementAndGet() < values.size());
        when(rows.getObject(anyString())).thenAnswer(call -> values.get(index.get()).get(call.getArgument(0)));
        when(rows.getObject(anyString(), any(Class.class))).thenAnswer(call -> values.get(index.get()).get(call.getArgument(0)));
        when(rows.getString(anyString())).thenAnswer(call -> {
            Object value = values.get(index.get()).get(call.getArgument(0));
            return value == null ? null : value.toString();
        });
        when(rows.getLong(anyString())).thenAnswer(call -> {
            Object value = values.get(index.get()).get(call.getArgument(0));
            nullState.set(value == null ? 1 : 0);
            return value == null ? 0 : ((Number) value).longValue();
        });
        when(rows.wasNull()).thenAnswer(ignored -> nullState.get() == 1);
        return rows;
    }

    private static Map<String, Object> validHeader() {
        Map<String, Object> row = new HashMap<>();
        row.put("aggregate_id", AGGREGATE);
        row.put("perfil", "AUTHENTICATED_PENDING");
        row.put("aggregate_locale", "es-AR");
        row.put("aggregate_audience", "USER");
        row.put("revision_scheme", "AGGREGATE_V1");
        row.put("aggregate_revision", DIGEST);
        row.put("provenance_fingerprint", DIGEST);
        row.put("aggregate_created", DATE.atOffset(ZoneOffset.UTC));
        row.put("scope_count", 1L);
        row.put("scope_ordinal", 1L);
        row.put("scope_context", "USO_CONTINUADO");
        row.put("scope_locale", "es-AR");
        row.put("scope_audience", "USER");
        row.put("scope_set", SET);
        row.put("scope_publication", PUBLICATION);
        row.put("set_id", SET);
        row.put("publicacion_id", PUBLICATION);
        row.put("locale", "es-AR");
        row.put("contexto", "USO_CONTINUADO");
        row.put("audiencia", "USER");
        row.put("current_set", SET);
        row.put("current_publication", PUBLICATION);
        row.put("publication_id", PUBLICATION);
        row.put("publication_locale", "es-AR");
        row.put("estado_construccion", "SELLADO");
        for (String column : List.of("creado_en", "actualizado_en", "importado_en", "sellado_en")) {
            row.put(column, DATE.atOffset(ZoneOffset.UTC));
        }
        row.put("publication_document_count", 1L);
        row.put("publication_requirement_count", 1L);
        row.put("required_set_revision", DIGEST);
        row.put("scope_revision", DIGEST);
        return row;
    }

    private static List<LegalPrivateRequirementsReader.Transition> transitions(EstadoVersionLegal state) {
        List<LegalPrivateRequirementsReader.Transition> chain = new ArrayList<>();
        if (state == EstadoVersionLegal.BORRADOR) return chain;
        chain.add(new LegalPrivateRequirementsReader.Transition(EstadoVersionLegal.BORRADOR,
                EstadoVersionLegal.PUBLICADA, DATE.plusSeconds(1)));
        if (state == EstadoVersionLegal.PUBLICADA) return chain;
        chain.add(new LegalPrivateRequirementsReader.Transition(EstadoVersionLegal.PUBLICADA,
                EstadoVersionLegal.VIGENTE, DATE.plusSeconds(2)));
        if (state != EstadoVersionLegal.VIGENTE) chain.add(new LegalPrivateRequirementsReader.Transition(
                EstadoVersionLegal.VIGENTE, state, DATE.plusSeconds(3)));
        return chain;
    }

    private static LegalPrivateRequirementsReader.VersionHeader version(EstadoVersionLegal state, Instant changed) {
        return new LegalPrivateRequirementsReader.VersionHeader(id(4), id(5), "document", 1, state, false,
                "a".repeat(64), 1L, DATE, changed);
    }

    private static LegalActorSnapshot actor() { return new LegalActorSnapshot(1, 2, UserRole.USER, 0, true, true); }
    private static LegalApplicableScopeSet scopes() {
        return new LegalApplicableScopeResolver().resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, AudienciaLegal.USER);
    }
    private static LegalRequiredSetAggregateReceipt receipt() {
        return new LegalRequiredSetAggregateReceipt(LegalRequiredSetAggregateReceipt.Outcome.REUSED, AGGREGATE,
                DIGEST, DIGEST, new LegalRequiredSetAggregateProvenance(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, AudienciaLegal.USER, List.of(new LegalRequiredSetAggregateProvenance.ScopeOrigin(
                        ContextoLegal.USO_CONTINUADO, SET, PUBLICATION))), DATE);
    }
    private static LegalEditorialTimeBoundary boundary() { return new LegalEditorialTimeBoundary(DATE, DATE.plusSeconds(10)); }
    private static LegalPrivateRequirementsDeadline deadline() { return new LegalPrivateRequirementsDeadline(Duration.ofSeconds(15)); }
    private static UUID id(int value) { return new UUID(0, value); }
    private record Fixture(LegalPrivateRequirementsReader reader, Connection connection, PreparedStatement statement) {
        void read() { reader.read(actor(), scopes(), receipt(), boundary(), deadline()); }
    }
}
