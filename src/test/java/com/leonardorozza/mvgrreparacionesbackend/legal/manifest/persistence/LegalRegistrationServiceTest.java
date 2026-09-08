package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.*;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.*;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationFailure.Reason;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real operation deadline/Spring completion; SQL collaborators are controlled, not PostgreSQL evidence. */
class LegalRegistrationServiceTest {
    private static final Registration REGISTRATION = new Registration("Taller", null, "Titular",
            "private-registration@test.invalid", "private-password-123");
    private static final String KEY = "00000000-0000-4000-8000-000000000001";
    private static final String OLD_REVISION = "sha256:" + "a".repeat(64);
    private static final LegalRequestMetadata METADATA = LegalRequestMetadata.of(
            LegalRequestMetadata.parseIpLiteral("192.0.2.15"), "Private registration fixture");
    private static final LegalRegistrationReceipt FRESH = new LegalRegistrationReceipt(11, 22, false,
            new UUID(0, 30), List.of(new UUID(0, 40)));

    @ParameterizedTest @ValueSource(strings = {"dataSource", "historical", "boundary", "aggregate", "reader", "writer", "schema"})
    void rejectsForeignCollaboratorsBeforeBorrowingOrCreatingTheCoordinator(String foreign) throws Exception {
        try (var f = new Fixture(); var historical = new LegalPrivateRequirementsDataSource(f.pool, Duration.ofSeconds(15))) {
            var boundary = mock(LegalRegistrationTransactionBoundary.class);
            when(boundary.usesJdbc(f.jdbc)).thenReturn(true);
            var supplied = f.bounded;
            switch (foreign) {
                case "dataSource" -> { var different = mock(DataSource.class); when(f.jdbc.getDataSource()).thenReturn(different); }
                case "historical" -> { supplied = historical; when(f.jdbc.getDataSource()).thenReturn(historical); }
                case "boundary" -> when(boundary.usesJdbc(f.jdbc)).thenReturn(false);
                case "aggregate" -> when(f.aggregates.usesJdbc(f.jdbc)).thenReturn(false);
                case "reader" -> when(f.reader.usesJdbc(f.jdbc)).thenReturn(false);
                case "writer" -> when(f.writer.usesJdbc(f.jdbc)).thenReturn(false);
                case "schema" -> when(f.schema.usesJdbc(f.jdbc)).thenReturn(false);
                default -> throw new AssertionError(foreign);
            }
            var source = supplied;
            assertThatThrownBy(() -> new LegalRegistrationService(f.jdbc, source, boundary, f.resolver,
                    f.aggregates, f.reader, f.preparation, f.writer, f.schema, f.keys))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(f.pool, never()).getConnection();
            assertThat(f.coordinators.constructed()).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"key", "registration", "revision", "acceptances"})
    void malformedCompleteInputFailsBeforePoolAndMetadataValidation(String invalid) throws Exception {
        try (var f = new Fixture()) {
            var service = f.service();
            var failure = failure(catchThrowable(() -> service.register(invalid.equals("registration") ? null : REGISTRATION,
                    invalid.equals("key") ? "invalid-key" : KEY,
                    invalid.equals("revision") ? "invalid-revision" : f.current.requiredSetRevision(),
                    invalid.equals("acceptances") ? null : f.acceptances(), null)));
            assertThat(failure.reason()).isEqualTo(Reason.INVALID_PAYLOAD);
            assertNoAttempt(failure);
            verify(f.pool, never()).getConnection();
            assertThat(f.events).isEmpty();
        }
    }

    @Test void allMissingFieldsMatchTheConfigurationSmokeContractWithoutBorrowing() throws Exception {
        try (var f = new Fixture()) {
            var failure = failure(catchThrowable(() -> f.service().register(null, "invalid", null, null, null)));
            assertThat(failure.reason()).isEqualTo(Reason.INVALID_PAYLOAD);
            assertNoAttempt(failure);
            verify(f.pool, never()).getConnection();
        }
    }

    @Test void validShapeWithMissingServerCaptureFailsClosedBeforePool() throws Exception {
        try (var f = new Fixture()) {
            var failure = failure(catchThrowable(() -> f.service().register(REGISTRATION, KEY,
                    f.current.requiredSetRevision(), f.acceptances(), null)));
            assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE);
            assertNoAttempt(failure);
            verify(f.pool, never()).getConnection();
            verify(f.preparation, never()).prepare(any(), any());
        }
    }

    @Test void expiryDuringRejectedShapeValidationDominatesThePayloadErrorWithoutBorrowing() throws Exception {
        try (var f = new Fixture()) {
            List<Acceptance> input = new AbstractList<>() {
                @Override public Acceptance get(int index) { throw new AssertionError("oversized input must not be read"); }
                @Override public int size() { f.clock.set(30_000_000_000L); return 2_049; }
            };
            var failure = failure(catchThrowable(() -> f.service().register(REGISTRATION, KEY,
                    f.current.requiredSetRevision(), input, METADATA)));
            assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE);
            assertThat(failure.getCause()).isInstanceOf(LegalPrivateRequirementsReadException.class);
            assertNoAttempt(failure);
            verify(f.pool, never()).getConnection();
        }
    }

    @Test void unexpectedInputContainerFailureIsOperationalAndDoesNotExposeItsDiagnostics() throws Exception {
        try (var f = new Fixture()) {
            var input = new AbstractList<Acceptance>() {
                @Override public Acceptance get(int index) { throw new AssertionError(); }
                @Override public int size() { throw new IllegalStateException(REGISTRATION.password()); }
            };
            var failure = failure(catchThrowable(() -> f.service().register(REGISTRATION, KEY,
                    f.current.requiredSetRevision(), input, METADATA)));
            assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE);
            assertThat(failure.getMessage() + failure).doesNotContain(REGISTRATION.password());
            verify(f.pool, never()).getConnection();
        }
    }

    @Test void aFreshMissReadsSelectsPreparesAndWritesInOrderThenConfirmsConstraintsBeforeCommit() throws Exception {
        try (var f = new Fixture()) {
            assertThat(f.register()).isSameAs(FRESH);
            assertThat(f.events).containsSubsequence("schema", "privileges", "reserve", "replay", "resolve",
                    "aggregate", "read", "prepare", "write", "constraints", "commit", "close");
            verify(f.pool).getConnection();
            verify(f.connection).commit();
            verify(f.connection, never()).rollback();
            assertThat(f.command.get().operation()).isEqualTo(Operation.REGISTRATION);
            assertThat(f.command.get().registration()).isSameAs(REGISTRATION);
            verify(f.resolver).resolve(PerfilAgregadoLegal.REGISTRATION, LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR);
            assertThat(f.deadline.get()).isNotNull();
            assertClean();
        }
    }

    @Test void confirmedReplayWithOldRevisionSkipsAllEditorialAndNewAccountWork() throws Exception {
        try (var f = new Fixture()) {
            f.replay = Optional.of(stored());
            var result = f.service().register(REGISTRATION, KEY, OLD_REVISION, f.acceptances(), METADATA);
            assertThat(result).isEqualTo(replayed());
            assertThat(f.events).containsExactly("schema", "privileges", "reserve", "replay", "commit", "close");
            verifyNoInteractions(f.resolver);
            verify(f.aggregates, never()).materialize(any(), any());
            verify(f.reader, never()).readForRegistration(any(), any(), any());
            verify(f.preparation, never()).prepare(any(), any());
            verify(f.writer, never()).write(any(), any(), any(), any(), any());
            verify(f.jdbc, never()).execute("SET CONSTRAINTS ALL IMMEDIATE");
            verify(f.pool).getConnection();
            assertClean();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void revisionWinsOverMissingMandatoryAndFalseConfirmationBeforePreparation(boolean unconfirmed) throws Exception {
        try (var f = new Fixture()) {
            var valid = f.acceptances().getFirst();
            List<Acceptance> input = unconfirmed ? List.of(new Acceptance(valid.requisitoVersionId(), valid.tipoActo(),
                    valid.afirmacionSha256(), valid.documentos(), false)) : List.of();
            var service = f.service();
            var failure = failure(catchThrowable(() -> service.register(REGISTRATION, KEY, OLD_REVISION, input, METADATA)));
            assertThat(failure.reason()).isEqualTo(Reason.STALE);
            assertThat(failure.validation().orElseThrow().currentRequirements()).isSameAs(f.current);
            assertThat(failure.validation().orElseThrow().submittedRevision()).isEqualTo(OLD_REVISION);
            assertRolledBack(failure);
            verify(f.preparation, never()).prepare(any(), any());
            verify(f.writer, never()).write(any(), any(), any(), any(), any());
            verify(f.connection).rollback();
        }
    }

    @Test void currentEmptyInputIsInvalidAndAvailabilityFailurePrecedesThatSemanticDecision() throws Exception {
        try (var f = new Fixture()) {
            var service = f.service();
            var invalid = failure(catchThrowable(() -> service.register(REGISTRATION, KEY,
                    f.current.requiredSetRevision(), List.of(), METADATA)));
            assertThat(invalid.reason()).isEqualTo(Reason.INVALID);
            assertThat(invalid.validation().orElseThrow().motivos())
                    .containsExactly(LegalAcceptanceValidationException.Motivo.REQUISITO_FALTANTE);
            assertRolledBack(invalid);
            f.readerFailure = new IllegalStateException("unavailable fixture");
            var unavailable = failure(catchThrowable(() -> service.register(REGISTRATION, KEY, OLD_REVISION, List.of(), METADATA)));
            assertThat(unavailable.reason()).isEqualTo(Reason.UNAVAILABLE);
            assertThat(unavailable.validation()).isEmpty();
            assertRolledBack(unavailable);
            verify(f.preparation, never()).prepare(any(), any());
        }
    }

    @ParameterizedTest @EnumSource(LegalIdempotencyException.Reason.class)
    void idempotencyRejectionsRetainTheirTypedReasonBeforeEditorialWork(LegalIdempotencyException.Reason reason) throws Exception {
        try (var f = new Fixture()) {
            var service = f.service();
            var cause = new LegalIdempotencyException(reason);
            doThrow(cause).when(f.coordinator()).reserve(any(), anyString(), any());
            var rejected = failure(catchThrowable(() -> service.register(REGISTRATION, KEY,
                    f.current.requiredSetRevision(), f.acceptances(), METADATA)));
            assertThat(rejected.reason().name()).isEqualTo(reason.name());
            assertThat(rejected.getCause()).isSameAs(cause);
            assertRolledBack(rejected);
            verifyNoInteractions(f.resolver);
            verify(f.preparation, never()).prepare(any(), any());
        }
    }

    @Test void preparationAndDeferredConstraintFailuresAreOperationalAndRollbackTheAttempt() throws Exception {
        try (var f = new Fixture()) {
            var service = f.service();
            doThrow(new IllegalArgumentException("synthetic encoder failure")).when(f.preparation).prepare(any(), any());
            var rejected = failure(catchThrowable(() -> service.register(REGISTRATION, KEY,
                    f.current.requiredSetRevision(), f.acceptances(), METADATA)));
            assertThat(rejected.reason()).isEqualTo(Reason.UNAVAILABLE);
            assertRolledBack(rejected);
            verify(f.writer, never()).write(any(), any(), any(), any(), any());
        }
        try (var f = new Fixture()) {
            doThrow(new DataIntegrityViolationException("synthetic graph failure", new SQLException("fixture", "23514")))
                    .when(f.jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
            var rejected = failure(catchThrowable(f::register));
            assertThat(rejected.reason()).isEqualTo(Reason.UNAVAILABLE);
            assertRolledBack(rejected);
            verify(f.writer).write(any(), any(), any(), any(), any());
            verify(f.connection, never()).commit();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void commitFailurePreservesUnknownDeliveryAndOnlyPreviouslyVerifiedReplayHasDurableIdentity(boolean replay) throws Exception {
        try (var f = new Fixture()) {
            if (replay) f.replay = Optional.of(stored());
            doThrow(new SQLException("synthetic acknowledgement loss", "08006")).when(f.connection).commit();
            var failure = failure(catchThrowable(f::register));
            assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE);
            assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.UNKNOWN);
            assertThat(failure.persistence()).isEqualTo(replay ? LegalRegistrationFailure.Persistence.PERSISTED
                    : LegalRegistrationFailure.Persistence.UNKNOWN);
            assertThat(failure.confirmedReceipt()).isEqualTo(replay ? Optional.of(replayed()) : Optional.empty());
            verify(f.connection, never()).rollback();
            assertClean();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiryImmediatelyBeforeCommitRollsBackDeliveryButCannotEraseAnAccreditedReplay(boolean replay) throws Exception {
        try (var f = new Fixture()) {
            if (replay) f.replay = Optional.of(stored());
            f.expireBeforeCommit = true;
            var failure = failure(catchThrowable(f::register));
            assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE);
            assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.ROLLED_BACK);
            assertThat(failure.persistence()).isEqualTo(replay ? LegalRegistrationFailure.Persistence.PERSISTED
                    : LegalRegistrationFailure.Persistence.NOT_PERSISTED);
            assertThat(failure.confirmedReceipt()).isEqualTo(replay ? Optional.of(replayed()) : Optional.empty());
            verify(f.connection, never()).commit();
            verify(f.connection).rollback();
            assertClean();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"late-commit", "late-close", "failed-close"})
    void lateDeliveryFailureRetainsTheCommittedRegistrationAndItsReceipt(String defect) throws Exception {
        try (var f = new Fixture()) {
            if (defect.equals("late-commit")) doAnswer(call -> { f.clock.set(30_000_000_000L); return null; }).when(f.connection).commit();
            if (defect.equals("late-close")) doAnswer(call -> { f.clock.set(30_000_000_000L); return null; }).when(f.connection).close();
            if (defect.equals("failed-close")) doThrow(new SQLException("synthetic cleanup failure", "08006")).when(f.connection).close();
            var failure = failure(catchThrowable(f::register));
            assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE);
            assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.COMMITTED);
            assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.PERSISTED);
            assertThat(failure.confirmedReceipt()).containsSame(FRESH);
            verify(f.connection, never()).rollback();
            assertClean();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DataSource pool = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final AtomicLong clock = new AtomicLong();
        final LegalPrivateRequirementsDataSource bounded = LegalPrivateRequirementsDataSource.registration(pool, clock::get);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final LegalV29AcceptanceSchemaVerifier schema = mock(LegalV29AcceptanceSchemaVerifier.class);
        final LegalRegistrationSchemaVerifier registrationSchema = mock(LegalRegistrationSchemaVerifier.class);
        final LegalRegistrationPrivilegeVerifier privileges = mock(LegalRegistrationPrivilegeVerifier.class);
        final LegalApplicableScopeResolver resolver = spy(new LegalApplicableScopeResolver());
        final LegalRequiredSetAggregateStore aggregates = mock(LegalRequiredSetAggregateStore.class);
        final LegalPublicRequirementsReader reader = mock(LegalPublicRequirementsReader.class);
        final LegalRegistrationPreparation preparation = mock(LegalRegistrationPreparation.class);
        final LegalRegistrationPreparation.Prepared prepared = mock(LegalRegistrationPreparation.Prepared.class);
        final LegalRegistrationWriter writer = mock(LegalRegistrationWriter.class);
        final LegalAcceptanceKeyConfiguration keys = mock(LegalAcceptanceKeyConfiguration.class);
        final LegalIdempotencyCoordinator.Reservation reservation = mock(LegalIdempotencyCoordinator.Reservation.class);
        final LegalRequiredSetAggregateReceipt aggregate = mock(LegalRequiredSetAggregateReceipt.class);
        final AtomicReference<LegalAcceptanceCommand> command = new AtomicReference<>();
        final AtomicReference<LegalPrivateRequirementsDeadline> deadline = new AtomicReference<>();
        final List<String> events = new ArrayList<>();
        final LegalPublicRegistrationRequirements current = current();
        final LegalRegistrationTransactionBoundary boundary;
        final MockedConstruction<LegalIdempotencyCoordinator> coordinators;
        Optional<LegalIdempotencyResultStore.StoredResult> replay = Optional.empty();
        RuntimeException readerFailure;
        boolean expireBeforeCommit;

        Fixture() throws Exception {
            AtomicBoolean autoCommit = new AtomicBoolean(true);
            when(pool.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
            doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; }).when(connection).setAutoCommit(anyBoolean());
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            doAnswer(call -> { events.add("commit"); return null; }).when(connection).commit();
            doAnswer(call -> { events.add("close"); return null; }).when(connection).close();
            when(jdbc.getDataSource()).thenReturn(bounded);
            when(jdbc.queryForMap(anyString())).thenReturn(Map.of("isolation", "read committed", "read_only", "off"));
            when(jdbc.queryForObject(anyString(), eq(OffsetDateTime.class))).thenReturn(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
            when(schema.usesJdbc(jdbc)).thenReturn(true); when(schema.expectedSchema()).thenReturn("public");
            when(registrationSchema.usesJdbc(jdbc)).thenReturn(true); when(registrationSchema.expectedSchema()).thenReturn("public");
            when(privileges.usesJdbc(jdbc)).thenReturn(true); when(privileges.expectedSchema()).thenReturn("public");
            when(aggregates.usesJdbc(jdbc)).thenReturn(true); when(reader.usesJdbc(jdbc)).thenReturn(true); when(writer.usesJdbc(jdbc)).thenReturn(true);
            String secret = Base64.getEncoder().encodeToString("1".repeat(32).getBytes(StandardCharsets.US_ASCII));
            when(keys.keyring()).thenReturn(new LegalIdempotencyKeyring(Map.of(1, secret), 1, Duration.ofHours(25)));
            doAnswer(call -> { events.add("schema"); return null; }).when(registrationSchema).verify();
            doAnswer(call -> { events.add("privileges"); return null; }).when(privileges).verify();
            doAnswer(call -> { events.add("resolve"); return call.callRealMethod(); }).when(resolver).resolve(any(), any(), any());
            when(aggregates.materialize(any(), any())).thenAnswer(call -> { events.add("aggregate"); return aggregate; });
            when(reader.readForRegistration(any(), any(), any())).thenAnswer(call -> {
                events.add("read"); deadline.set(call.getArgument(2));
                if (readerFailure != null) throw readerFailure;
                return current;
            });
            when(preparation.prepare(any(), any())).thenAnswer(call -> {
                events.add("prepare"); assertThat(call.getArgument(0, LegalAcceptanceCommand.class)).isSameAs(command.get());
                assertThat(call.getArgument(1, LegalPrivateRequirementsDeadline.class)).isSameAs(deadline.get()); return prepared;
            });
            when(writer.write(any(), any(), any(), any(), any())).thenAnswer(call -> {
                events.add("write"); assertThat(call.getArgument(0, LegalIdempotencyCoordinator.Reservation.class)).isSameAs(reservation);
                assertThat(call.getArgument(1, LegalRegistrationPreparation.Prepared.class)).isSameAs(prepared);
                var selected = call.getArgument(3, LegalRegistrationSelection.Selection.class);
                selected.requireCommand(command.get()); assertThat(selected.current()).isSameAs(current);
                assertThat(selected.requirements()).containsExactlyElementsOf(current.projection().requirements()); return FRESH;
            });
            doAnswer(call -> { events.add("constraints"); return null; }).when(jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
            when(reservation.jdbc()).thenReturn(jdbc);
            when(reservation.command()).thenAnswer(call -> command.get());
            when(reservation.replay()).thenAnswer(call -> { events.add("replay"); return replay; });
            doAnswer(call -> {
                RuntimeException failure = call.getArgument(0);
                return failure instanceof LegalIdempotencyException typed ? typed
                        : new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE, failure);
            }).when(reservation).fail(any(RuntimeException.class));
            var manager = new DataSourceTransactionManager(bounded); manager.setRollbackOnCommitFailure(false);
            var transaction = new TransactionTemplate(manager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transaction.setTimeout(25);
            boundary = new LegalRegistrationTransactionBoundary(jdbc, bounded, transaction,
                    new LegalDatabaseBudgets(25, 5, 1, 1), registrationSchema, privileges);
            coordinators = mockConstruction(LegalIdempotencyCoordinator.class, (mock, context) -> {
                assertThat(context.arguments().getFirst()).isSameAs(jdbc);
                when(mock.reserve(any(), anyString(), any())).thenAnswer(call -> {
                    events.add("reserve"); command.set(call.getArgument(0));
                    if (expireBeforeCommit) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void beforeCompletion() { clock.set(30_000_000_000L); }
                    });
                    return reservation;
                });
            });
        }

        LegalRegistrationService service() {
            return new LegalRegistrationService(jdbc, bounded, boundary, resolver, aggregates, reader, preparation, writer, schema, keys);
        }
        LegalIdempotencyCoordinator coordinator() { return coordinators.constructed().getLast(); }
        LegalRegistrationReceipt register() { return service().register(REGISTRATION, KEY, current.requiredSetRevision(), acceptances(), METADATA); }
        List<Acceptance> acceptances() {
            return current.projection().requirements().stream().map(r -> new Acceptance(r.versionId(), r.actType(), r.statementSha256(),
                    r.documents().stream().map(d -> new Document(d.versionId(), d.sha256())).toList(), true)).toList();
        }
        @Override public void close() { try { bounded.close(); } finally { coordinators.close(); } }
    }

    private static LegalPublicRegistrationRequirements current() {
        var document = new DocumentProjection(new UUID(0, 1), TipoDocumentoLegal.TERMINOS_SERVICIO, "1.0.0", "Documento",
                "# Contenido\n", sha("# Contenido\n"), OffsetDateTime.parse("2020-01-01T00:00:00Z"), LocaleLegal.ES_AR);
        var requirement = new RequirementProjection(new UUID(0, 2), ContextoLegal.REGISTRO, TipoActoLegal.ACEPTACION,
                "Confirmo el registro.", sha("Confirmo el registro."), List.of(document), true);
        return new LegalPublicRequirementsValidator().validate(new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR, List.of(requirement)));
    }
    private static LegalIdempotencyResultStore.StoredResult stored() {
        return new LegalIdempotencyResultStore.StoredResult(LegalIdempotencyResultStore.Source.WITH_ACTS, 1L, null,
                FRESH.userId(), FRESH.tallerId(), FRESH.lotId(), "WITH_ACTS", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-03T00:00:00Z"), FRESH.acceptanceIds());
    }
    private static LegalRegistrationReceipt replayed() {
        return new LegalRegistrationReceipt(FRESH.userId(), FRESH.tallerId(), true, FRESH.lotId(), FRESH.acceptanceIds());
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static LegalRegistrationFailure failure(Throwable failure) {
        assertThat(failure).isExactlyInstanceOf(LegalRegistrationFailure.class); return (LegalRegistrationFailure) failure;
    }
    private static void assertNoAttempt(LegalRegistrationFailure failure) {
        assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.NONE);
        assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.NOT_PERSISTED);
        assertThat(failure.confirmedReceipt()).isEmpty();
    }
    private static void assertRolledBack(LegalRegistrationFailure failure) {
        assertThat(failure.completion()).isEqualTo(LegalRegistrationFailure.Completion.ROLLED_BACK);
        assertThat(failure.persistence()).isEqualTo(LegalRegistrationFailure.Persistence.NOT_PERSISTED);
        assertThat(failure.confirmedReceipt()).isEmpty();
        assertClean();
    }
    private static void assertClean() {
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }
}
