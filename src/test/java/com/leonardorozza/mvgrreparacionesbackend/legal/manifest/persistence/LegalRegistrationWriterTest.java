package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRequirementsValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinator.Reservation;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Boundary tests stop at the first business INSERT; PostgreSQL ITs own graph/privilege/rollback proofs. */
class LegalRegistrationWriterTest {
    private static final UUID REQUIREMENT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DOCUMENT = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID AGGREGATE = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID REQUIRED_SET = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID PUBLICATION = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime OBSERVED = OffsetDateTime.parse("2026-09-08T12:00:10Z");
    private static final OffsetDateTime CREATED = OBSERVED.minusSeconds(20);
    private static final LegalRequestMetadata METADATA = LegalRequestMetadata.of(
            LegalRequestMetadata.parseIpLiteral("192.0.2.31"), "registration writer fixture");
    private static final LegalAcceptanceMetadataPolicy POLICY = new LegalAcceptanceMetadataPolicy(Duration.ofDays(7));

    @Test void constructorRejectsResultsFromAnotherJdbcBeforeSql() {
        var source = mock(JdbcTemplate.class);
        var dataSource = mock(DataSource.class);
        when(source.getDataSource()).thenReturn(dataSource);
        var results = mock(LegalIdempotencyResultStore.class);
        assertThatThrownBy(() -> new LegalRegistrationWriter(source, codec(), POLICY, results))
                .isInstanceOf(IllegalArgumentException.class);
        verify(source).getDataSource();
        verify(results).usesJdbc(source);
        verifyNoMoreInteractions(source, results);
    }

    @Test void constructorRejectsMissingDataSourceWithoutBorrowing() {
        var source = mock(JdbcTemplate.class);
        var results = mock(LegalIdempotencyResultStore.class);
        assertThatThrownBy(() -> new LegalRegistrationWriter(source, codec(), POLICY, results))
                .isInstanceOf(IllegalArgumentException.class);
        verify(source).getDataSource();
        verifyNoMoreInteractions(source);
        verifyNoInteractions(results);
    }

    @Test void missingReservationCannotObserveDatabaseOrCreateAccount() {
        var f = new Fixture();
        assertUnavailable(() -> f.writer.write(null, f.prepared, f.aggregate, f.selection, METADATA));
        verifyNoInteractions(f.jdbc, f.codec, f.results);
    }

    @Test void anotherJdbcReservationFailsBeforePreflightOrCapture() {
        var f = new Fixture();
        var foreign = mock(JdbcTemplate.class);
        when(f.reservation.jdbc()).thenReturn(foreign);
        assertUnavailable(f::write);
        verify(f.reservation).fail(any(RuntimeException.class));
        verify(f.reservation, never()).requireNew();
        verifyNoInteractions(f.jdbc, f.codec, f.results);
    }

    @Test void replayOrConsumedReservationCannotReenterTheWriter() {
        var f = new Fixture();
        doThrow(unavailable()).when(f.reservation).requireNew();
        assertUnavailable(f::write);
        verify(f.reservation).fail(any(RuntimeException.class));
        verifyNoInteractions(f.jdbc, f.codec, f.results);
    }

    @Test void authenticatedOperationCannotBeUsedToCreateAnAccount() {
        var f = new Fixture();
        var command = LegalAcceptanceCommandValidator.authenticated(
                new LegalActorSnapshot(1, 2, UserRole.ADMIN, 0, true, true),
                f.current.requiredSetRevision(), f.command.acceptances());
        when(f.reservation.command()).thenReturn(command);
        assertUnavailable(f::write);
        verifyNoInteractions(f.jdbc, f.codec, f.results);
    }

    @Test void missingCaptureFailsBeforeAnySqlOrAccountCreation() {
        var f = new Fixture();
        assertUnavailable(() -> f.writer.write(f.reservation, f.prepared, f.aggregate, f.selection, null));
        verify(f.reservation).fail(any(RuntimeException.class));
        verifyNoInteractions(f.jdbc, f.codec, f.results);
    }

    @Test void preparationForAnEquivalentButDifferentCommandIsNotTransferable() {
        var f = new Fixture();
        var equivalent = command(f.current);
        var other = prepare(equivalent);
        assertUnavailable(() -> f.writer.write(f.reservation, other, f.aggregate, f.selection, METADATA));
        verifyNoInteractions(f.jdbc, f.codec, f.results);
    }

    @Test void selectionForAnEquivalentButDifferentCommandIsNotTransferable() {
        var f = new Fixture();
        var other = new LegalRegistrationSelection().select(command(f.current), f.current);
        assertUnavailable(() -> f.writer.write(f.reservation, f.prepared, f.aggregate, other, METADATA));
        verifyNoInteractions(f.jdbc, f.codec, f.results);
    }

    @Test void exhaustedPreparationBudgetFailsBeforeGateAndWithoutNewDeadline() {
        var f = new Fixture();
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var deadline = LegalPrivateRequirementsDeadline.registration(clock::get);
        var prepared = preparation().prepare(f.command, deadline);
        clock.set(Duration.ofSeconds(30).toNanos());
        assertUnavailable(() -> f.writer.write(f.reservation, prepared, f.aggregate, f.selection, METADATA));
        verifyNoInteractions(f.jdbc, f.codec, f.results);
    }

    @Test void missingEditorialLockRejectsBeforeAggregateReadEncryptionAndAccountInsert() {
        var f = new Fixture();
        f.gateFailure = new DataIntegrityViolationException("synthetic missing editorial lock");
        var failure = assertUnavailable(f::write);
        assertThat(failure.getCause()).isSameAs(f.gateFailure);
        assertThat(f.events).containsExactly("gate");
        verifyNoInteractions(f.codec, f.results);
        verify(f.reservation).fail(f.gateFailure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile", "revision", "timestamp", "future", "header", "provenance", "current"})
    void inconsistentAggregateReceiptCannotProduceBusinessRows(String drift) {
        var f = new Fixture();
        var aggregate = f.aggregate;
        if (drift.equals("profile")) {
            var provenance = new LegalRequiredSetAggregateProvenance(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                    LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR, aggregate.provenance().scopes());
            aggregate = new LegalRequiredSetAggregateReceipt(aggregate.outcome(), AGGREGATE,
                    aggregate.requiredSetRevision(), aggregate.provenanceFingerprint(), provenance, aggregate.createdAt());
        } else if (drift.equals("revision")) {
            aggregate = new LegalRequiredSetAggregateReceipt(aggregate.outcome(), AGGREGATE,
                    "sha256:" + "f".repeat(64), aggregate.provenanceFingerprint(), aggregate.provenance(), aggregate.createdAt());
        } else if (drift.equals("timestamp") || drift.equals("future")) {
            aggregate = new LegalRequiredSetAggregateReceipt(aggregate.outcome(), AGGREGATE,
                    aggregate.requiredSetRevision(), aggregate.provenanceFingerprint(), aggregate.provenance(),
                    drift.equals("future") ? OBSERVED.plusSeconds(1).toInstant() : CREATED.minusSeconds(1).toInstant());
        } else {
            f.drift = drift;
        }
        var candidate = aggregate;
        assertUnavailable(() -> f.writer.write(f.reservation, f.prepared, candidate, f.selection, METADATA));
        assertThat(f.businessInserts).isEmpty();
        verifyNoInteractions(f.codec, f.results);
        verify(f.reservation).fail(any(RuntimeException.class));
    }

    @Test void receiptLabelledCreatedCannotClaimAnAggregateFromBeforeItsTransaction() {
        var f = new Fixture();
        var receipt = new LegalRequiredSetAggregateReceipt(LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                AGGREGATE, f.aggregate.requiredSetRevision(), f.aggregate.provenanceFingerprint(),
                f.aggregate.provenance(), CREATED.toInstant());
        assertUnavailable(() -> f.writer.write(f.reservation, f.prepared, receipt, f.selection, METADATA));
        assertThat(f.businessInserts).isEmpty();
        verifyNoInteractions(f.codec, f.results);
    }

    @Test void encryptionFailureAfterSuccessfulAccreditationStillPrecedesEveryBusinessInsert() {
        var f = new Fixture();
        var encryptionFailure = new IllegalStateException("synthetic encryption failure");
        doThrow(encryptionFailure).when(f.codec).prepare(any(UUID.class), any(LegalRequestMetadata.class));
        var failure = assertUnavailable(f::write);
        assertThat(failure.getCause()).isSameAs(encryptionFailure);
        assertThat(f.events).contains("current");
        assertThat(f.businessInserts).isEmpty();
        verifyNoInteractions(f.results);
    }

    @Test void canonicalReceiptIsReaccreditedAndMetadataEncryptedBeforeFirstBusinessInsert() {
        var f = new Fixture();
        doAnswer(invocation -> {
            f.events.add("encrypt");
            return invocation.callRealMethod();
        }).when(f.codec).prepare(any(UUID.class), any(LegalRequestMetadata.class));
        var failure = assertUnavailable(f::write);
        assertThat(failure.getCause()).isSameAs(f.firstInsertFailure);
        assertThat(f.events).containsExactly("gate", "observation", "header", "members", "current", "encrypt", "business");
        assertThat(f.businessInserts).hasSize(1);
        verify(f.reservation).fail(f.firstInsertFailure);
        verify(f.reservation, never()).requireWriteActor(any());
        verifyNoInteractions(f.results);
    }

    private static LegalIdempotencyException assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        Throwable failure = catchThrowable(action);
        assertThat(failure).isInstanceOf(LegalIdempotencyException.class);
        assertThat(((LegalIdempotencyException) failure).reason()).isEqualTo(LegalIdempotencyException.Reason.UNAVAILABLE);
        return (LegalIdempotencyException) failure;
    }

    private static LegalIdempotencyException unavailable() {
        return new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE);
    }

    private static LegalAcceptanceMetadataCodec codec() {
        return new LegalAcceptanceMetadataCodec(Map.of(1,
                Base64.getEncoder().encodeToString(new byte[32])), 1);
    }

    private static LegalRegistrationPreparation preparation() {
        return new LegalRegistrationPreparation(new BCryptPasswordEncoder(4),
                Clock.fixed(OBSERVED.toInstant(), ZoneOffset.UTC),
                () -> LocalDateTime.of(2026, 9, 8, 9, 0), 14);
    }

    private static LegalRegistrationPreparation.Prepared prepare(LegalAcceptanceCommand command) {
        return preparation().prepare(command, LegalPrivateRequirementsDeadline.registration(System::nanoTime));
    }

    private static LegalPublicRegistrationRequirements current() {
        String statement = "Acepto los términos del taller.";
        String markdown = "# Términos\nTexto legal del fixture.\n";
        var document = new LegalRequiredSetProjection.DocumentProjection(DOCUMENT, TipoDocumentoLegal.TERMINOS_SERVICIO,
                "1.0.0", "Términos", markdown, sha(markdown), CREATED, LocaleLegal.ES_AR);
        var requirement = new LegalRequiredSetProjection.RequirementProjection(REQUIREMENT, ContextoLegal.REGISTRO,
                TipoActoLegal.ACEPTACION, statement, sha(statement), List.of(document), true);
        return new LegalPublicRequirementsValidator().validate(new LegalRequiredSetProjection(
                ContextoLegal.REGISTRO, LocaleLegal.ES_AR, List.of(requirement)));
    }

    private static LegalAcceptanceCommand command(LegalPublicRegistrationRequirements current) {
        var requirement = current.projection().requirements().getFirst();
        var document = requirement.documents().getFirst();
        return LegalAcceptanceCommandValidator.registration(new LegalAcceptanceCommand.Registration(
                "Taller fixture", null, "Titular fixture", "writer@ordenfix.test", "FixturePassword123"),
                current.requiredSetRevision(), List.of(new LegalAcceptanceCommand.Acceptance(REQUIREMENT,
                        requirement.actType(), requirement.statementSha256(), List.of(
                        new LegalAcceptanceCommand.Document(DOCUMENT, document.sha256())), true)));
    }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static final class Fixture {
        final LegalPublicRegistrationRequirements current = current();
        final LegalAcceptanceCommand command = command(current);
        final LegalRegistrationPreparation.Prepared prepared = prepare(command);
        final LegalRegistrationSelection.Selection selection = new LegalRegistrationSelection().select(command, current);
        final LegalRequiredSetAggregateProvenance provenance = new LegalRequiredSetAggregateProvenance(
                PerfilAgregadoLegal.REGISTRATION, LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR,
                List.of(new LegalRequiredSetAggregateProvenance.ScopeOrigin(ContextoLegal.REGISTRO, REQUIRED_SET, PUBLICATION)));
        final LegalRequiredSetAggregateReceipt aggregate = new LegalRequiredSetAggregateReceipt(
                LegalRequiredSetAggregateReceipt.Outcome.REUSED, AGGREGATE, current.requiredSetRevision(),
                new LegalRequiredSetAggregateProvenanceCalculator().calculate(provenance), provenance, CREATED.toInstant());
        final DataSource dataSource = mock(DataSource.class);
        final List<String> events = new ArrayList<>();
        final List<String> businessInserts = new ArrayList<>();
        final DataIntegrityViolationException firstInsertFailure = new DataIntegrityViolationException("synthetic first INSERT failure");
        RuntimeException gateFailure;
        String drift;
        final JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("getDataSource")) return dataSource;
            Object[] arguments = invocation.getArguments();
            if (arguments.length == 0 || !(arguments[0] instanceof String sql)) {
                return RETURNS_DEFAULTS.answer(invocation);
            }
            if (method.equals("queryForList") && sql.contains("legal_exigir_lock_editorial_v28")) {
                events.add("gate");
                if (gateFailure != null) throw gateFailure;
                return List.of();
            }
            if (method.equals("query") && arguments.length > 1 && arguments[1] instanceof RowMapper<?> mapper) {
                List<Map<String, Object>> rows = rows(sql);
                var result = new ArrayList<>();
                for (int index = 0; index < rows.size(); index++) result.add(mapper.mapRow(row(rows.get(index)), index));
                return result;
            }
            if (sql.stripLeading().startsWith("INSERT")) {
                events.add("business"); businessInserts.add(sql);
                throw firstInsertFailure;
            }
            throw new AssertionError("Unexpected JDBC invocation: " + method);
        });
        final LegalAcceptanceMetadataCodec codec = spy(codec());
        final LegalIdempotencyResultStore results = mock(LegalIdempotencyResultStore.class);
        final Reservation reservation = mock(Reservation.class);
        final LegalRegistrationWriter writer;

        Fixture() {
            when(results.usesJdbc(jdbc)).thenReturn(true);
            when(reservation.jdbc()).thenReturn(jdbc);
            when(reservation.command()).thenReturn(command);
            when(reservation.fail(any(RuntimeException.class))).thenAnswer(invocation -> {
                RuntimeException failure = invocation.getArgument(0);
                return failure instanceof LegalIdempotencyException typed ? typed
                        : new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE, failure);
            });
            writer = new LegalRegistrationWriter(jdbc, codec, POLICY, results);
            clearInvocations(jdbc, codec, results, reservation);
        }

        void write() { writer.write(reservation, prepared, aggregate, selection, METADATA); }

        private List<Map<String, Object>> rows(String sql) {
            if (sql.contains("AS transaction_at")) {
                events.add("observation");
                return List.of(Map.of("transaction_at", OBSERVED.minusSeconds(10), "observed_at", OBSERVED));
            }
            if (sql.equals(LegalRequiredSetAggregateReplayVerifier.HEADER_SQL)) {
                events.add("header");
                return List.of(Map.of("id", AGGREGATE, "perfil", "REGISTRATION", "locale", "es-AR",
                        "audiencia", "ADMIN_TITULAR", "revision_scheme", "AGGREGATE_V1",
                        "required_set_revision", current.requiredSetRevision(), "provenance_fingerprint",
                        "header".equals(drift) ? "sha256:" + "0".repeat(64) : aggregate.provenanceFingerprint(),
                        "scope_count", 1, "creado_en", CREATED));
            }
            if (sql.equals(LegalRequiredSetAggregateReplayVerifier.MEMBERS_SQL)) {
                events.add("members");
                return List.of(Map.of("agregado_id", AGGREGATE, "scope_ordinal", 1, "contexto", "REGISTRO",
                        "conjunto_id", REQUIRED_SET, "publicacion_id", "provenance".equals(drift) ? UUID.randomUUID() : PUBLICATION,
                        "locale", "es-AR", "audiencia", "ADMIN_TITULAR", "required_set_revision", current.scopeRevision()));
            }
            if (sql.contains("FROM public.legal_requisito_conjuntos c")) {
                events.add("current");
                return List.of(Map.ofEntries(Map.entry("id", REQUIRED_SET), Map.entry("publicacion_id", PUBLICATION),
                        Map.entry("locale", "es-AR"), Map.entry("contexto", "REGISTRO"), Map.entry("audiencia", "ADMIN_TITULAR"),
                        Map.entry("required_set_revision", current.scopeRevision()), Map.entry("creado_en", CREATED),
                        Map.entry("current_set", "current".equals(drift) ? UUID.randomUUID() : REQUIRED_SET),
                        Map.entry("current_publication", PUBLICATION), Map.entry("actualizado_en", CREATED),
                        Map.entry("publication_id", PUBLICATION), Map.entry("publication_locale", "es-AR"),
                        Map.entry("estado_construccion", "SELLADO"), Map.entry("importado_en", CREATED), Map.entry("sellado_en", CREATED)));
            }
            throw new AssertionError("Unexpected SELECT");
        }

        private static ResultSet row(Map<String, Object> fields) {
            return mock(ResultSet.class, invocation -> {
                if (List.of("getObject", "getString", "getInt").contains(invocation.getMethod().getName())) {
                    return fields.get((String) invocation.getArgument(0));
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
        }
    }
}
