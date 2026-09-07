package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceReceipt.Kind;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceServiceITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Motivo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.jdbc;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.transaction;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceServiceITSupport.key;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceServiceITSupport.metadata;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceServiceITSupport.principal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Full internal writes using production primitives and the exact restricted PostgreSQL role. */
class LegalAcceptanceServiceIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_service").withUsername("ordenfix").withPassword("ordenfix");
    private static final String STALE_REVISION = "sha256:" + "a".repeat(64);
    private static final String WRONG_DIGEST = "f".repeat(64);
    private static LegalAcceptanceServiceITSupport fixture;
    @TempDir Path directory;

    @BeforeAll static void start() { POSTGRES.start(); fixture = new LegalAcceptanceServiceITSupport(POSTGRES); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass()); }
    @AfterEach void noLeakedTransaction() { assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty(); }

    @ParameterizedTest @EnumSource(UserRole.class)
    void commitsTheCanonicalAggregateEvidenceMetadataAndResultOnceForEitherAllowedRole(UserRole role) throws Exception {
        var actor = fixture.actor(role); var command = fixture.command(actor); var before = fixture.counts();
        assertThat(before.get("legal_requisito_agregados")).isZero();
        Probe probe = new Probe();
        String userAgent = "\ud83d\udd27".repeat(512);
        try (var harness = fixture.harness(source -> instrument(source, probe), System::nanoTime)) {
            var result = harness.service().accept(principal(actor), key(), command.requiredSetRevision(),
                    reversed(command.acceptances()), metadata(userAgent));

            assertThat(result.kind()).isEqualTo(Kind.WITH_ACTS); assertThat(result.replay()).isFalse();
            assertThat(result.lotId()).isNotNull(); assertThat(result.acceptanceIds()).hasSize(2).doesNotHaveDuplicates();
            assertThat(probe.borrows).hasValue(1);
            assertThat(harness.metrics().snapshot().commits()).isOne();
            assertThat(harness.metrics().snapshot().rollbacks()).isZero();
            assertThat(fixture.owner.queryForList("SELECT id FROM legal_aceptaciones WHERE user_id=? AND taller_id=? ORDER BY id",
                    UUID.class, actor.userId(), actor.tallerId())).containsExactlyElementsOf(result.acceptanceIds());
            assertCanonicalSnapshots(result.lotId(), actor);
            Set<String> xids = new HashSet<>();
            for (String table : LegalAcceptanceServiceITSupport.LEGAL_TABLES) {
                xids.addAll(fixture.owner.queryForList("SELECT DISTINCT xmin::text FROM public." + table, String.class));
            }
            assertThat(xids).hasSize(1);
            var header = LegalAcceptanceMetadataITSupport.header(fixture.owner, result.lotId());
            OffsetDateTime acceptedAt = fixture.owner.queryForObject("SELECT aceptado_en FROM legal_aceptacion_lotes WHERE id=?",
                    OffsetDateTime.class, result.lotId());
            assertThat(header.capturedAt()).isEqualTo(acceptedAt.toInstant());
            assertThat(header.retainUntil()).isEqualTo(header.capturedAt().plus(LegalAcceptanceServiceITSupport.RETENTION));
            var fields = LegalAcceptanceMetadataITSupport.fields(fixture.owner, result.lotId());
            assertThat(fields).extracting(LegalAcceptanceMetadataITSupport.CipherRow::type).containsExactly("IP", "USER_AGENT");
            assertThat(LegalAcceptanceMetadataITSupport.decrypt(result.lotId(), fields.getFirst())).isEqualTo(metadata().ipAddress());
            assertThat(LegalAcceptanceMetadataITSupport.decrypt(result.lotId(), fields.getLast())).isEqualTo(userAgent);
            assertThat(fields.getLast().originalLength()).isEqualTo(512);
        }
        var after = fixture.counts();
        assertThat(after.get("legal_aceptacion_lotes")).isEqualTo(before.get("legal_aceptacion_lotes") + 1);
        assertThat(after.get("legal_idempotencia_resultados")).isEqualTo(before.get("legal_idempotencia_resultados") + 1);
        for (String account : List.of("users", "talleres", "suscripciones")) assertThat(after.get(account)).isEqualTo(before.get(account));
    }

    @Test void thirtyFourActsCrossTwoRealWriterBatchesInsideOneCommit() throws Exception {
        fixture.reset(directory.resolve("batch"), getClass(), 34);
        var actor = fixture.actor(); var command = fixture.command(actor); Probe probe = new Probe();
        assertThat(command.acceptances()).hasSize(34);
        try (var harness = fixture.harness(source -> instrument(source, probe), System::nanoTime)) {
            var result = accept(harness, command, key());
            assertThat(result.acceptanceIds()).hasSize(34).doesNotHaveDuplicates();
            assertThat(insertExecutions(harness, "legal_aceptaciones")).isEqualTo(2);
            assertThat(insertExecutions(harness, "legal_aceptacion_documentos")).isEqualTo(2);
            assertThat(harness.metrics().snapshot().commits()).isOne(); assertThat(probe.borrows).hasValue(1);
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_aceptaciones WHERE lote_id=?", Long.class, result.lotId())).isEqualTo(34);
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_aceptacion_documentos", Long.class)).isEqualTo(34);
            assertCanonicalSnapshots(result.lotId(), actor);
        }
    }

    @Test void optionalOmissionThenMixedAcceptanceCreatesOnlyThePreviouslyMissingAct() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor);
        var required = fixture.requirement(command, 1);
        try (var harness = fixture.harness()) {
            var first = harness.service().accept(principal(actor), key(), command.requiredSetRevision(), List.of(required), metadata());
            var original = evidenceRows();
            var mixed = accept(harness, command, key());
            assertThat(first.acceptanceIds()).hasSize(1);
            assertThat(mixed.kind()).isEqualTo(Kind.WITH_ACTS); assertThat(mixed.lotId()).isNotEqualTo(first.lotId());
            assertThat(mixed.acceptanceIds()).hasSize(2).containsAll(first.acceptanceIds());
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_aceptaciones WHERE lote_id=?", Long.class, mixed.lotId())).isOne();
            assertThat(evidenceRows().get("legal_aceptaciones")).containsAll(original.get("legal_aceptaciones"));
            assertThat(evidenceRows().get("legal_aceptacion_lotes")).containsAll(original.get("legal_aceptacion_lotes"));
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_aceptacion_metadatos", Long.class)).isEqualTo(2);
            assertThat(harness.metrics().snapshot().commits()).isEqualTo(2);
        }
    }

    @Test void emptySuccessWithOptionalPendingStoresOnlyTheTechnicalResultAndCanReplay() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); String emptyKey = key();
        try (var harness = fixture.harness()) {
            harness.service().accept(principal(actor), key(), command.requiredSetRevision(), List.of(fixture.requirement(command, 1)), metadata());
            var evidence = evidenceRows(); harness.metrics().reset();
            var result = harness.service().accept(principal(actor), emptyKey, command.requiredSetRevision(), List.of(), metadata());
            assertThat(result.kind()).isEqualTo(Kind.EMPTY); assertThat(result.lotId()).isNull(); assertThat(result.acceptanceIds()).isEmpty();
            assertThat(evidenceRows()).isEqualTo(evidence);
            assertThat(fixture.owner.queryForObject("SELECT resultado FROM legal_idempotencia_sin_actos", String.class)).isEqualTo("EMPTY");
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_idempotencia_sin_actos_referencias", Long.class)).isZero();
            assertThat(insertExecutions(harness, "legal_aceptacion_lotes")).isZero();
            var durable = fixture.durableRows(); harness.metrics().reset();
            var replay = harness.service().accept(principal(actor), emptyKey, command.requiredSetRevision(), List.of(), metadata("different UA"));
            assertThat(replay.kind()).isEqualTo(Kind.EMPTY); assertThat(replay.replay()).isTrue();
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(durable);
        }
    }

    @Test void aNewKeyForFullyConfirmedHistoryDeduplicatesBeforeFreshnessAfterReplacement() throws Exception {
        var actor = fixture.actor(); var originalCommand = fixture.command(actor);
        try (var harness = fixture.harness()) {
            var original = accept(harness, originalCommand, key()); var evidence = evidenceRows();
            fixture.replaceRequirement(directory, getClass(), 1, true, true);
            var current = fixture.command(actor);
            assertThat(current.requiredSetRevision()).isNotEqualTo(originalCommand.requiredSetRevision());
            var dedup = accept(harness, originalCommand, key());
            assertThat(dedup.kind()).isEqualTo(Kind.DEDUP); assertThat(dedup.replay()).isFalse(); assertThat(dedup.lotId()).isNull();
            assertThat(dedup.acceptanceIds()).isEqualTo(original.acceptanceIds()); assertThat(evidenceRows()).isEqualTo(evidence);
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_idempotencia_sin_actos_referencias", Long.class)).isEqualTo(2);
            var mixed = List.of(originalCommand.acceptances().stream()
                    .filter(value -> current.acceptances().stream().noneMatch(now -> now.requisitoVersionId().equals(value.requisitoVersionId())))
                    .findFirst().orElseThrow(), fixture.requirement(current, 1));
            var before = fixture.durableRows();
            var failure = failure(catchThrowable(() -> harness.service().accept(principal(actor), key(), current.requiredSetRevision(), mixed, metadata())),
                    LegalAcceptanceFailure.Reason.INVALID);
            assertThat(failure.validation().orElseThrow().motivos()).contains(Motivo.REQUISITO_NO_PERTENECE_AL_CONJUNTO);
            assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"requirements", "documents"})
    void existingEvidenceNeverMakesDuplicatesAnEarlyDedup(String duplicate) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor);
        try (var harness = fixture.harness()) {
            accept(harness, command, key());
            Acceptance first = command.acceptances().getFirst();
            List<Acceptance> input = duplicate.equals("requirements") ? List.of(first, first)
                    : List.of(new Acceptance(first.requisitoVersionId(), first.tipoActo(), first.afirmacionSha256(),
                    List.of(first.documentos().getFirst(), first.documentos().getFirst()), true));
            var before = fixture.durableRows();
            var stale = failure(catchThrowable(() -> harness.service().accept(principal(actor), key(), STALE_REVISION, input, metadata())),
                    LegalAcceptanceFailure.Reason.STALE);
            assertThat(stale.validation().orElseThrow().submittedRevision()).isEqualTo(STALE_REVISION);
            var invalid = failure(catchThrowable(() -> harness.service().accept(principal(actor), key(), command.requiredSetRevision(), input, metadata())),
                    LegalAcceptanceFailure.Reason.INVALID);
            assertThat(invalid.validation().orElseThrow().motivos()).containsExactly(duplicate.equals("requirements")
                    ? Motivo.REQUISITO_DUPLICADO : Motivo.DOCUMENTO_DUPLICADO);
            assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "digest", "act", "confirmation", "document", "unknown"})
    void clientSemanticFailuresRollBackEvenTheNewAggregate(String defect) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); var required = fixture.requirement(command, 1);
        List<Acceptance> input = switch (defect) {
            case "missing" -> List.of();
            case "digest" -> List.of(new Acceptance(required.requisitoVersionId(), required.tipoActo(), WRONG_DIGEST, required.documentos(), true));
            case "act" -> List.of(new Acceptance(required.requisitoVersionId(), TipoActoLegal.LECTURA, required.afirmacionSha256(), required.documentos(), true));
            case "confirmation" -> List.of(new Acceptance(required.requisitoVersionId(), required.tipoActo(), required.afirmacionSha256(), required.documentos(), false));
            case "document" -> List.of(new Acceptance(required.requisitoVersionId(), required.tipoActo(), required.afirmacionSha256(), List.of(), true));
            case "unknown" -> List.of(new Acceptance(UUID.randomUUID(), required.tipoActo(), required.afirmacionSha256(), required.documentos(), true));
            default -> throw new AssertionError(defect);
        };
        var before = fixture.durableRows();
        try (var harness = fixture.harness()) {
            failure(catchThrowable(() -> harness.service().accept(principal(actor), key(), STALE_REVISION, input, metadata())), LegalAcceptanceFailure.Reason.STALE);
            var invalid = failure(catchThrowable(() -> harness.service().accept(principal(actor), key(), command.requiredSetRevision(), input, metadata())),
                    LegalAcceptanceFailure.Reason.INVALID);
            assertThat(invalid.validation().orElseThrow().motivos()).contains(switch (defect) {
                case "missing" -> Motivo.REQUISITO_FALTANTE;
                case "digest" -> Motivo.DIGEST_NO_COINCIDE;
                case "act" -> Motivo.ACTO_NO_COINCIDE;
                case "confirmation" -> Motivo.CONFIRMACION_REQUERIDA;
                case "document" -> Motivo.DOCUMENTO_FALTANTE;
                case "unknown" -> Motivo.REQUISITO_NO_PERTENECE_AL_CONJUNTO;
                default -> throw new AssertionError(defect);
            });
            assertThat(harness.metrics().snapshot().commits()).isZero(); assertThat(harness.metrics().snapshot().rollbacks()).isEqualTo(2);
            assertThat(fixture.durableRows()).isEqualTo(before);
            assertThat(fixture.counts().get("legal_requisito_agregados")).isZero();
        }
    }

    @Test void anOptionalRequirementThatBecomesMandatoryWithoutPriorEvidenceBlocksEmptyAcceptance() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor);
        try (var harness = fixture.harness()) {
            harness.service().accept(principal(actor), key(), command.requiredSetRevision(), List.of(fixture.requirement(command, 1)), metadata());
            fixture.replaceRequirement(directory, getClass(), 2, true, false);
            var current = fixture.command(actor); var before = fixture.durableRows();
            var rejected = failure(catchThrowable(() -> harness.service().accept(principal(actor), key(), current.requiredSetRevision(), List.of(), metadata())),
                    LegalAcceptanceFailure.Reason.INVALID);
            assertThat(rejected.validation().orElseThrow().motivos()).containsExactly(Motivo.REQUISITO_FALTANTE);
            assertThat(fixture.durableRows()).isEqualTo(before);
            var accepted = harness.service().accept(principal(actor), key(), current.requiredSetRevision(), List.of(fixture.requirement(current, 2)), metadata());
            assertThat(accepted.kind()).isEqualTo(Kind.WITH_ACTS); assertThat(accepted.acceptanceIds()).hasSize(1);
        }
    }

    @Test void inheritanceAllowsEmptySuccessButExplicitConfirmationCreatesTheNewVersionAct() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor);
        try (var harness = fixture.harness()) {
            accept(harness, command, key()); var evidence = evidenceRows();
            fixture.replaceRequirement(directory, getClass(), 1, true, false);
            var current = fixture.command(actor);
            var empty = harness.service().accept(principal(actor), key(), current.requiredSetRevision(), List.of(), metadata());
            assertThat(empty.kind()).isEqualTo(Kind.EMPTY); assertThat(evidenceRows()).isEqualTo(evidence);
            var explicit = harness.service().accept(principal(actor), key(), current.requiredSetRevision(), List.of(fixture.requirement(current, 1)), metadata());
            assertThat(explicit.kind()).isEqualTo(Kind.WITH_ACTS); assertThat(explicit.acceptanceIds()).hasSize(1);
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM legal_aceptaciones", Long.class)).isEqualTo(3);
        }
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void replayOfEveryDurableResultIgnoresARetiredCatalogAndAnExclusivelyHeldEditorialGate(Kind kind) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); String requestedKey = key();
        try (var harness = fixture.harness()) {
            LegalAcceptanceReceipt expected;
            List<Acceptance> input = kind == Kind.EMPTY ? List.of() : command.acceptances();
            if (kind == Kind.WITH_ACTS) expected = accept(harness, command, requestedKey);
            else {
                accept(harness, command, key());
                expected = harness.service().accept(principal(actor), requestedKey, command.requiredSetRevision(), input, metadata());
            }
            assertThat(expected.kind()).isEqualTo(kind);
            fixture.retireUsage(directory, getClass()); var before = fixture.durableRows();
            try (Connection exclusive = transaction(fixture.dataSource)) {
                editorialLock(jdbc(exclusive)); harness.metrics().reset();
                var replay = harness.service().accept(principal(actor), requestedKey, command.requiredSetRevision(), input, metadata("new metadata must be ignored"));
                assertThat(replay.kind()).isEqualTo(kind); assertThat(replay.replay()).isTrue();
                assertThat(replay.lotId()).isEqualTo(expected.lotId()); assertThat(replay.acceptanceIds()).isEqualTo(expected.acceptanceIds());
                assertNoDml(harness); assertThat(harness.metrics().snapshot().commits()).isOne();
                assertThat(harness.metrics().snapshot().bySql().keySet()).noneMatch(sql -> sql.contains(
                        "pg_advisory_xact_lock_shared(pg_catalog.hashtextextended(?, 0))"));
                assertThat(fixture.durableRows()).isEqualTo(before);
            }
            var unavailable = failure(catchThrowable(() -> harness.service().accept(principal(actor), key(), command.requiredSetRevision(), input, metadata())),
                    LegalAcceptanceFailure.Reason.UNAVAILABLE);
            assertThat(unavailable.validation()).isEmpty();
            assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @Test void aReusedKeyWithDifferentPayloadFailsBeforeAvailabilityAndNeverPerformsDml() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); String requestKey = key();
        try (var harness = fixture.harness()) {
            accept(harness, command, requestKey); fixture.retireUsage(directory, getClass());
            var before = fixture.durableRows(); harness.metrics().reset();
            failure(catchThrowable(() -> harness.service().accept(principal(actor), requestKey, command.requiredSetRevision(), List.of(), metadata())),
                    LegalAcceptanceFailure.Reason.KEY_REUSED);
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @Test void actorsAndTenantsNeverReuseEachOthersEvidenceOrIdempotencyScope() throws Exception {
        var first = fixture.actor(); var second = fixture.actor(); var command = fixture.command(first); String sameKey = key();
        try (var harness = fixture.harness()) {
            var one = accept(harness, command, sameKey);
            var two = harness.service().accept(principal(second), sameKey, command.requiredSetRevision(), command.acceptances(), metadata());
            assertThat(two.kind()).isEqualTo(Kind.WITH_ACTS); assertThat(two.acceptanceIds()).doesNotContainAnyElementsOf(one.acceptanceIds());
            assertCanonicalSnapshots(one.lotId(), first); assertCanonicalSnapshots(two.lotId(), second);
            var forged = new LegalActorSnapshot(first.userId(), second.tallerId(), first.role(), first.tokenVersion(), true, true);
            var before = fixture.durableRows(); harness.metrics().reset();
            failure(catchThrowable(() -> harness.service().accept(principal(forged), "invalid-key", STALE_REVISION, List.of(), metadata())),
                    LegalAcceptanceFailure.Reason.INVALID_ACTOR);
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"role", "token", "user-active", "workshop-active"})
    void actorChangesWhileWaitingForTheGateAreRecheckedBeforeAnyBusinessWrite(String changed) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor);
        try (var harness = fixture.harness(); var executor = Executors.newSingleThreadExecutor(); Connection exclusive = transaction(fixture.dataSource)) {
            editorialLock(jdbc(exclusive));
            var pending = executor.submit(() -> catchThrowable(() -> accept(harness, command, key())));
            awaitEditorialWait();
            switch (changed) {
                case "role" -> fixture.owner.update("UPDATE users SET role='ADMIN' WHERE id=?", actor.userId());
                case "token" -> fixture.owner.update("UPDATE users SET token_version=token_version+1 WHERE id=?", actor.userId());
                case "user-active" -> fixture.owner.update("UPDATE users SET active=false WHERE id=?", actor.userId());
                case "workshop-active" -> fixture.owner.update("UPDATE talleres SET activo=false WHERE id=?", actor.tallerId());
                default -> throw new AssertionError(changed);
            }
            var before = fixture.durableRows(); exclusive.commit();
            failure(pending.get(10, TimeUnit.SECONDS), LegalAcceptanceFailure.Reason.INVALID_ACTOR);
            assertNoDml(harness); assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"source-statement", "source-document", "evidence-statement", "evidence-document"})
    void corruptCanonicalSourcesOrOwnEvidenceFailClosedWithoutPartialChanges(String corrupt) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor);
        try (var harness = fixture.harness()) {
            var written = accept(harness, command, key());
            fixture.replica(() -> {
                if (corrupt.equals("source-statement")) fixture.owner.update("UPDATE legal_requisito_versiones SET afirmacion=? WHERE id=?",
                        "Afirmación modificada sin su digest.", command.acceptances().getFirst().requisitoVersionId());
                else if (corrupt.equals("source-document")) fixture.owner.update("UPDATE legal_documento_versiones SET contenido_markdown=? WHERE id=?",
                        "# Documento modificado\n\nSin actualizar su digest.\n", command.acceptances().getFirst().documentos().getFirst().documentoVersionId());
                else if (corrupt.equals("evidence-statement")) fixture.owner.update("UPDATE legal_aceptaciones SET afirmacion=? WHERE id=?",
                        "Evidencia alterada.", written.acceptanceIds().getFirst());
                else fixture.owner.update("UPDATE legal_aceptacion_documentos SET sha256=? WHERE aceptacion_id=?",
                        WRONG_DIGEST, written.acceptanceIds().getFirst());
            });
            var before = fixture.durableRows(); harness.metrics().reset();
            failure(catchThrowable(() -> accept(harness, command, key())), LegalAcceptanceFailure.Reason.UNAVAILABLE);
            assertThat(fixture.durableRows()).isEqualTo(before); assertThat(harness.metrics().snapshot().commits()).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"legal_aceptaciones", "legal_aceptacion_documentos", "legal_aceptacion_metadatos", "legal_idempotencia_resultados"})
    void aRealSqlFailureInEachWritePhaseRollsBackTheWholeNewGraphWithoutRetry(String table) throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); var before = fixture.durableRows();
        Probe probe = new Probe(); probe.failTable = table;
        try (var harness = fixture.harness(source -> instrument(source, probe), System::nanoTime)) {
            var failure = failure(catchThrowable(() -> accept(harness, command, key())), LegalAcceptanceFailure.Reason.UNAVAILABLE);
            assertThat(probe.injected).isTrue(); assertThat(probe.borrows).hasValue(1);
            assertThat(sqlStates(failure)).contains("22012");
            assertThat(harness.metrics().snapshot().commits()).isZero(); assertThat(harness.metrics().snapshot().rollbacks()).isOne();
            assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @Test void aRealDeferredMetadataConstraintIsForcedBeforeCommitAndRollsBackEverything() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); var before = fixture.durableRows();
        Probe probe = new Probe(); probe.omitIpInsert = true;
        try (var harness = fixture.harness(source -> instrument(source, probe), System::nanoTime)) {
            var failure = failure(catchThrowable(() -> harness.service().accept(principal(actor), key(), command.requiredSetRevision(),
                    command.acceptances(), metadata(null))), LegalAcceptanceFailure.Reason.UNAVAILABLE);
            assertThat(probe.omitted).hasValue(1);
            assertThat(probe.executed).contains("set constraints all immediate");
            assertThat(sqlStates(failure)).contains("23514");
            assertThat(insertExecutions(harness, "legal_idempotencia_resultados")).isOne();
            assertThat(harness.metrics().snapshot().commits()).isZero(); assertThat(harness.metrics().snapshot().rollbacks()).isOne();
            assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    @Test void metadataRetentionThatExpiresBeforePersistenceRollsBackActsAndTheAggregate() throws Exception {
        var actor = fixture.actor(); var command = fixture.command(actor); var before = fixture.durableRows();
        try (var harness = fixture.harness(UnaryOperator.identity(), System::nanoTime, Duration.ofNanos(1))) {
            failure(catchThrowable(() -> accept(harness, command, key())), LegalAcceptanceFailure.Reason.UNAVAILABLE);
            assertThat(insertExecutions(harness, "legal_aceptacion_lotes")).isOne();
            assertThat(harness.metrics().snapshot().commits()).isZero(); assertThat(fixture.durableRows()).isEqualTo(before);
        }
    }

    private static LegalAcceptanceReceipt accept(Harness harness, LegalAcceptanceCommand command, String key) {
        return harness.service().accept(principal(command.actor()), key, command.requiredSetRevision(), command.acceptances(), metadata());
    }

    private static LegalAcceptanceFailure failure(Throwable thrown, LegalAcceptanceFailure.Reason reason) {
        assertThat(thrown).isInstanceOf(LegalAcceptanceFailure.class);
        var failure = (LegalAcceptanceFailure) thrown;
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.completion()).isEqualTo(LegalAcceptanceFailure.Completion.ROLLED_BACK);
        assertThat(failure.persistence()).isEqualTo(LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
        assertThat(failure.confirmedReceipt()).isEmpty();
        return failure;
    }

    private static void assertNoDml(Harness harness) { assertThat(harness.metrics().snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero(); }
    private static long insertExecutions(Harness harness, String table) {
        return harness.metrics().snapshot().bySql().values().stream()
                .filter(sql -> isInsertInto(sql.sql().toLowerCase(Locale.ROOT), table))
                .mapToLong(LegalJdbcMetricsSupport.SqlSnapshot::executions).sum();
    }

    private static boolean isInsertInto(String sql, String table) {
        String prefix = "insert into public." + table;
        return sql.startsWith(prefix + " ") || sql.startsWith(prefix + "(");
    }

    private static List<Acceptance> reversed(List<Acceptance> values) {
        var result = new ArrayList<>(values); Collections.reverse(result); return result;
    }

    private static Map<String, List<String>> evidenceRows() {
        var result = new java.util.LinkedHashMap<String, List<String>>();
        var rows = fixture.durableRows();
        for (String table : List.of("legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
                "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados")) result.put(table, rows.get(table));
        return Map.copyOf(result);
    }

    private static void assertCanonicalSnapshots(UUID lotId, LegalActorSnapshot actor) {
        assertThat(fixture.owner.queryForObject("""
                SELECT bool_and(a.user_id=? AND a.taller_id=? AND b.user_id=a.user_id AND b.taller_id=a.taller_id
                  AND b.rol_wire=? AND b.audiencia=? AND a.requisito_clave=line.clave AND a.requisito_version=v.version
                  AND a.contexto=line.contexto AND a.tipo_acto=line.tipo_acto AND a.afirmacion=v.afirmacion
                  AND a.afirmacion_sha256=v.afirmacion_sha256 AND a.requerido=v.requerido)
                  FROM legal_aceptaciones a JOIN legal_aceptacion_lotes b ON b.id=a.lote_id
                  JOIN legal_requisito_versiones v ON v.id=a.requisito_version_id
                  JOIN legal_requisito_lineas line ON line.id=v.requisito_linea_id WHERE b.id=?
                """, Boolean.class, actor.userId(), actor.tallerId(), actor.role().name(), actor.audience().name(), lotId)).isTrue();
        assertThat(fixture.owner.queryForObject("""
                SELECT bool_and(d.documento_clave=line.clave AND d.tipo=line.tipo AND d.version=v.version
                  AND d.titulo=v.titulo AND d.sha256=v.sha256 AND d.documento_ordinal=ref.documento_ordinal)
                  FROM legal_aceptacion_documentos d JOIN legal_aceptaciones a ON a.id=d.aceptacion_id
                  JOIN legal_documento_versiones v ON v.id=d.documento_version_id
                  JOIN legal_documento_lineas line ON line.id=v.documento_linea_id
                  JOIN legal_requisito_documentos ref ON ref.requisito_version_id=a.requisito_version_id AND ref.documento_version_id=v.id
                 WHERE a.lote_id=?
                """, Boolean.class, lotId)).isTrue();
    }

    private static void editorialLock(JdbcTemplate jdbc) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
    }

    private static void awaitEditorialWait() throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        do {
            Boolean waiting = fixture.owner.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid
                     WHERE a.usename=? AND l.locktype='advisory' AND NOT l.granted AND l.mode='ShareLock'
                       AND l.classid::bigint=((hashtextextended(?,0)>>32)&4294967295)
                       AND l.objid::bigint=(hashtextextended(?,0)&4294967295) AND l.objsubid=1)
                    """, Boolean.class, LegalAcceptanceServiceITSupport.ROLE,
                    LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME, LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
            if (Boolean.TRUE.equals(waiting)) return;
            Thread.sleep(5);
        } while (System.nanoTime() < end);
        throw new AssertionError("La operación no llegó al gate editorial esperado");
    }

    private static Set<String> sqlStates(Throwable failure) {
        Set<String> states = new HashSet<>(); Set<Throwable> visited = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cursor = failure; cursor != null && visited.add(cursor); cursor = cursor.getCause()) {
            if (cursor instanceof SQLException sql && sql.getSQLState() != null) states.add(sql.getSQLState());
        }
        return states;
    }

    /** Faults affect physical statements only; no reader, verifier, selector or service is mocked. */
    private static DataSource instrument(DataSource source, Probe probe) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                probe.borrows.incrementAndGet();
                Connection connection = source.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, arguments) -> {
                    Object result = invoke(connection, method, arguments);
                    if (result instanceof Statement statement) {
                        String sql = arguments != null && arguments.length > 0 && arguments[0] instanceof String value ? value : null;
                        Class<?> contract = result instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{contract}, (wrapped, operation, values) -> {
                            if (operation.getName().startsWith("execute")) {
                                String executed = sql != null ? sql : values != null && values.length > 0 && values[0] instanceof String value ? value : "";
                                String normalized = LegalJdbcMetricsSupport.normalizeSql(executed).toLowerCase(Locale.ROOT);
                                probe.executed.add(normalized);
                                if (probe.failTable != null && isInsertInto(normalized, probe.failTable)
                                        && probe.injected.compareAndSet(false, true)) {
                                    try (Statement divideByZero = connection.createStatement()) { divideByZero.execute("SELECT 1/0"); }
                                    throw new AssertionError("La falla SQL real no ocurrió");
                                }
                                if (probe.omitIpInsert && isInsertInto(normalized, "legal_aceptacion_metadatos_cifrados")) {
                                    assertThat(operation.getName()).isEqualTo("executeUpdate");
                                    probe.omitted.incrementAndGet();
                                    return 1;
                                }
                            }
                            return invoke(statement, operation, values);
                        });
                    }
                    return result;
                });
            }
            @Override public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException("Credencial fija del fixture");
            }
        };
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static final class Probe {
        final AtomicInteger borrows = new AtomicInteger();
        final AtomicInteger omitted = new AtomicInteger();
        final AtomicBoolean injected = new AtomicBoolean();
        final List<String> executed = new CopyOnWriteArrayList<>();
        String failTable;
        boolean omitIpInsert;
    }
}
