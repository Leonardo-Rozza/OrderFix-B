package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceSelection.ExistingAcceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.Harness;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceEvidenceITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinatorITSupport.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL historical evidence through the restricted authenticated V29 reservation. */
class LegalAcceptanceEvidenceReaderIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_v29_acceptance_evidence").withUsername("ordenfix").withPassword("ordenfix");
    private static LegalIdempotencyCoordinatorITSupport fixture;
    @TempDir Path directory;
    private Harness harness;

    @BeforeAll static void start() {
        POSTGRES.start(); fixture = new LegalIdempotencyCoordinatorITSupport(POSTGRES);
        grantHistoricalSourceReads(fixture);
    }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception { fixture.reset(directory, getClass()); harness = fixture.harness(false, KEY_ONE); }

    @Test void emptyAndMissingOwnEvidenceAreNormalWithoutDml() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        assertThat(read(harness, withAcceptances(command, List.of()))).isEmpty();
        assertThat(read(harness, command)).isEmpty();
        var unknown = new Acceptance(UUID.randomUUID(), TipoActoLegal.ACEPTACION, "a".repeat(64), List.of(), false);
        assertThat(read(harness, withAcceptances(command, List.of(unknown)))).isEmpty();
        noEvidenceDmlOrPrivateMetadataRead(harness);
    }

    @Test void returnsExactStoredEvidenceRatherThanEchoingTheRequestAndPreservesDuplicatePayloads() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        var written = commitEvidence(harness, command);
        var before = fixture.database.durableRows();
        var original = command.acceptances().getFirst();
        var changed = new Acceptance(original.requisitoVersionId(), TipoActoLegal.LECTURA, "0".repeat(64),
                List.of(new Document(UUID.randomUUID(), "1".repeat(64))), false);
        var requested = withAcceptances(command, List.of(changed, changed));
        var result = read(harness, requested);
        assertThat(result).hasSize(1);
        var actual = result.getFirst();
        assertThat(actual.acceptanceId()).isIn(written.acceptanceIds());
        assertThat(actual.userId()).isEqualTo(command.actor().userId());
        assertThat(actual.tallerId()).isEqualTo(command.actor().tallerId());
        assertThat(actual.requirementVersionId()).isEqualTo(original.requisitoVersionId());
        assertThat(actual.actType()).isEqualTo(original.tipoActo());
        assertThat(actual.statementSha256()).isEqualTo(original.afirmacionSha256());
        assertThat(actual.documents()).containsExactlyElementsOf(original.documentos());
        assertThat(requested.acceptances()).hasSize(2);
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(actual.documents()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(fixture.database.durableRows()).isEqualTo(before);
        noEvidenceDmlOrPrivateMetadataRead(harness);
    }

    @Test void partialEvidenceReturnsOnlyTheCommittedSubsetAcrossDifferentLots() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        var first = withAcceptances(command, List.of(command.acceptances().getFirst()));
        commitEvidence(harness, first);
        assertThat(read(harness, command)).extracting(ExistingAcceptance::requirementVersionId)
                .containsExactly(command.acceptances().getFirst().requisitoVersionId());
        commitEvidence(harness, withAcceptances(command, List.of(command.acceptances().get(1))));
        assertThat(read(harness, command)).extracting(ExistingAcceptance::requirementVersionId)
                .containsExactlyInAnyOrderElementsOf(command.acceptances().stream().map(Acceptance::requisitoVersionId).toList());
        noEvidenceDmlOrPrivateMetadataRead(harness);
    }

    @Test void otherActorsAndOtherWorkshopsAreNeverEvidenceForTheReservedActor() throws Exception {
        var ownerCommand = fixture.acceptanceCommand(fixture.actor());
        commitEvidence(harness, ownerCommand);
        var employee = fixture.actor();
        fixture.owner.update("UPDATE users SET taller_id=? WHERE id=?", ownerCommand.actor().tallerId(), employee.userId());
        var sameWorkshop = new com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot(
                employee.userId(), ownerCommand.actor().tallerId(), employee.role(), employee.tokenVersion(), true, true);
        assertThat(read(harness, withActor(ownerCommand, sameWorkshop))).isEmpty();
        assertThat(read(harness, withActor(ownerCommand, fixture.actor()))).isEmpty();
        noEvidenceDmlOrPrivateMetadataRead(harness);
    }

    @Test void historicalVersionsRemainReadableAfterRealReplacementAndRetirement() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        commitEvidence(harness, command);
        var expected = read(harness, command);
        fixture.replaceAndRetire(directory, getClass(),
                () -> assertThat(read(harness, command)).isEqualTo(expected));
        assertThat(read(harness, command)).isEqualTo(expected);
        noEvidenceDmlOrPrivateMetadataRead(harness);
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "matching-text-wrong-digest", "document", "markdown", "transition", "lot-tenant", "membership", "aggregate", "early-date"})
    void corruptHistoricalEvidenceFailsClosedAndMarksCallerRollbackOnly(String damage) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        var written = commitEvidence(harness, command);
        assertThat(read(harness, command)).hasSize(command.acceptances().size());
        UUID requirement = command.acceptances().getFirst().requisitoVersionId();
        UUID document = command.acceptances().getFirst().documentos().getFirst().documentoVersionId();
        switch (damage) {
            case "snapshot" -> corrupt(fixture, "UPDATE legal_aceptaciones SET afirmacion='Snapshot alterado.' WHERE requisito_version_id=?", requirement);
            case "matching-text-wrong-digest" -> {
                corrupt(fixture, "UPDATE legal_aceptaciones SET afirmacion='Texto concordante alterado.' WHERE requisito_version_id=?", requirement);
                corrupt(fixture, "UPDATE legal_requisito_versiones SET afirmacion='Texto concordante alterado.' WHERE id=?", requirement);
            }
            case "document" -> corrupt(fixture, "UPDATE legal_aceptacion_documentos SET sha256=? WHERE documento_version_id=?", "0".repeat(64), document);
            case "markdown" -> corrupt(fixture, "UPDATE legal_documento_versiones SET contenido_markdown='Texto histórico cambiado.' WHERE id=?", document);
            case "transition" -> corrupt(fixture, "DELETE FROM legal_requisito_transiciones WHERE requisito_version_id=? AND estado_nuevo='VIGENTE'", requirement);
            case "lot-tenant" -> corrupt(fixture, "UPDATE legal_aceptacion_lotes SET taller_id=? WHERE id=?", fixture.actor().tallerId(), written.lotId());
            case "membership" -> corrupt(fixture, "DELETE FROM legal_publicacion_requisitos WHERE requisito_version_id=?", requirement);
            case "aggregate" -> corrupt(fixture, "UPDATE legal_requisito_agregados SET provenance_fingerprint=? WHERE id=(SELECT agregado_id FROM legal_aceptacion_lotes WHERE id=?)",
                    "sha256:" + "0".repeat(64), written.lotId());
            case "early-date" -> corrupt(fixture, "UPDATE legal_aceptacion_lotes SET aceptado_en=TIMESTAMPTZ '1900-01-01 00:00:00+00' WHERE id=?", written.lotId());
            default -> throw new AssertionError("fixture");
        }
        var before = fixture.database.durableRows();
        Throwable failure = catchThrowable(() -> inTransaction(harness, (status, remaining) -> {
            var reservation = harness.coordinator().reserve(command, key(), remaining);
            writerBoundary(harness.jdbc(), command.actor()); harness.metrics().reset();
            Throwable rejected = catchThrowable(() -> new LegalAcceptanceEvidenceReader(harness.jdbc()).read(reservation, command.actor()));
            unavailable(rejected);
            assertThat(status.isRollbackOnly()).isTrue();
            throw (RuntimeException) rejected;
        }));
        unavailable(failure);
        assertThat(harness.metrics().snapshot().rollbacks()).isEqualTo(1);
        assertThat(fixture.database.durableRows()).isEqualTo(before);
        noEvidenceDmlOrPrivateMetadataRead(harness);
    }

    @ParameterizedTest @ValueSource(strings = {"statement", "markdown"})
    void oversizedSourcesAreRejectedBeforeAnyHistoricalTextFetch(String source) throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor()); commitEvidence(harness, command);
        assertThat(read(harness, command)).hasSize(command.acceptances().size());
        if (source.equals("statement")) {
            UUID id = command.acceptances().getFirst().requisitoVersionId();
            corrupt(fixture, "UPDATE legal_requisito_versiones SET afirmacion=repeat('a',1001) WHERE id=?", id);
            corrupt(fixture, "UPDATE legal_aceptaciones SET afirmacion=repeat('a',1001) WHERE requisito_version_id=?", id);
        } else {
            UUID id = command.acceptances().getFirst().documentos().getFirst().documentoVersionId();
            corrupt(fixture, "UPDATE legal_documento_versiones SET contenido_markdown=repeat('a',1048577) WHERE id=?", id);
        }
        unavailable(catchThrowable(() -> read(harness, command)));
        assertThat(harness.metrics().snapshot().executionsContaining("AS text_utf8")).isZero();
        assertThat(harness.metrics().snapshot().rollbacks()).isEqualTo(1);
        noEvidenceDmlOrPrivateMetadataRead(harness);
    }

    @Test void fortyActsUseTwoHeaderAndDocumentBatchesAndOneDistinctSourceFetch() throws Exception {
        seedMany(fixture, directory.resolve("many"), getClass(), 40);
        var command = fixture.acceptanceCommand(fixture.actor()); assertThat(command.acceptances()).hasSize(40);
        commitEvidence(harness, command);
        assertThat(read(harness, command)).hasSize(40);
        var metrics = harness.metrics().snapshot();
        assertThat(metrics.executionsContaining("needed.id AS requested_id, a.id")).isEqualTo(2);
        assertThat(metrics.maximumRowsReadContaining("needed.id AS requested_id, a.id")).isLessThanOrEqualTo(32);
        assertThat(metrics.executionsContaining("d.documento_ordinal, d.documento_version_id")).isEqualTo(2);
        assertThat(metrics.executionsContaining("AS text_utf8", "legal_documento_versiones")).isEqualTo(1);
        assertThat(metrics.executionsContaining("g.provenance_fingerprint,g.scope_count")).isEqualTo(1);
        noEvidenceDmlOrPrivateMetadataRead(harness);
    }

    @Test void uncommittedEvidenceCannotBecomeExact() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        var before = fixture.database.durableRows();
        unavailable(catchThrowable(() -> inTransaction(harness, (status, remaining) -> {
            var reservation = harness.coordinator().reserve(command, key(), remaining);
            newLot(harness.jdbc(), command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING, command.acceptances());
            return new LegalAcceptanceEvidenceReader(harness.jdbc()).read(reservation, command.actor());
        })));
        assertThat(fixture.database.durableRows()).isEqualTo(before);
    }

    @Test void missingExclusiveActorLockExpiredBudgetAndForeignJdbcFailClosed() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        unavailable(catchThrowable(() -> inTransaction(harness, (status, remaining) -> {
            var reservation = harness.coordinator().reserve(command, key(), remaining);
            return new LegalAcceptanceEvidenceReader(harness.jdbc()).read(reservation, command.actor());
        })));
        unavailable(catchThrowable(() -> inTransaction(harness, (status, remaining) -> {
            AtomicInteger budget = new AtomicInteger(20_000);
            var reservation = harness.coordinator().reserve(command, key(), budget::get);
            writerBoundary(harness.jdbc(), command.actor()); budget.set(0);
            return new LegalAcceptanceEvidenceReader(harness.jdbc()).read(reservation, command.actor());
        })));
        var other = fixture.harness(false, KEY_ONE);
        unavailable(catchThrowable(() -> inTransaction(harness, (status, remaining) -> {
            var reservation = harness.coordinator().reserve(command, key(), remaining);
            writerBoundary(harness.jdbc(), command.actor());
            return new LegalAcceptanceEvidenceReader(other.jdbc()).read(reservation, command.actor());
        })));
    }

    @Test void oldOrReplayedReservationCannotAuthorizeANewEvidenceObservation() throws Exception {
        var command = fixture.acceptanceCommand(fixture.actor());
        AtomicReference<LegalIdempotencyCoordinator.Reservation> expired = new AtomicReference<>();
        inTransaction(harness, (status, remaining) -> {
            expired.set(harness.coordinator().reserve(command, key(), remaining)); return null;
        });
        unavailable(catchThrowable(() -> new LegalAcceptanceEvidenceReader(harness.jdbc()).read(expired.get(), command.actor())));
        String resultKey = key();
        inTransaction(harness, (status, remaining) -> {
            var reservation = harness.coordinator().reserve(command, resultKey, remaining);
            var lot = newLot(harness.jdbc(), command.actor(), PerfilAgregadoLegal.AUTHENTICATED_PENDING, command.acceptances());
            harness.store().persistWithActs(reservation, command.actor(), lot.lotId()); return null;
        });
        unavailable(catchThrowable(() -> inTransaction(harness, (status, remaining) -> {
            var reservation = harness.coordinator().reserve(command, resultKey, remaining);
            return new LegalAcceptanceEvidenceReader(harness.jdbc()).read(reservation, command.actor());
        })));
    }

    @Test void genuineScopeV1EvidenceSurvivesUpgradeWithoutInventingPublicationTimeRules() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("ordenfix_legal_v29_evidence_scope").withUsername("ordenfix").withPassword("ordenfix")) {
            postgres.start();
            var legacy = seedLegacy(postgres, directory.resolve("legacy"), getClass());
            var migrated = new LegalIdempotencyCoordinatorITSupport(postgres);
            grantHistoricalSourceReads(migrated);
            assertThat(migrated.owner.queryForMap("SELECT revision_scheme,perfil,agregado_id FROM legal_aceptacion_lotes WHERE id=?", legacy.lotId()))
                    .containsEntry("revision_scheme", "SCOPE_V1").containsEntry("perfil", null).containsEntry("agregado_id", null);
            var restricted = migrated.harness(false, KEY_ONE);
            var command = migrated.acceptanceCommand(legacy.actor());
            assertThat(read(restricted, command)).extracting(ExistingAcceptance::acceptanceId)
                    .containsExactlyInAnyOrderElementsOf(legacy.acceptanceIds());
            noEvidenceDmlOrPrivateMetadataRead(restricted);
        }
    }

    @Test void genuineReplacementTimestampMayPrecedeTheAcceptanceThatCommittedFirst() throws Exception {
        Path temporal = directory.resolve("editorial-time");
        var source = seedMany(fixture, temporal, getClass(), 2);
        var command = fixture.acceptanceCommand(fixture.actor());
        var written = replaceInEarlierTransaction(fixture, temporal, getClass(), source, harness, command);
        assertThat(read(harness, command)).extracting(ExistingAcceptance::acceptanceId)
                .containsExactlyInAnyOrderElementsOf(written.acceptanceIds());
        noEvidenceDmlOrPrivateMetadataRead(harness);
    }

    private static void unavailable(Throwable failure) {
        assertThat(failure).isInstanceOf(LegalIdempotencyException.class);
        assertThat(((LegalIdempotencyException) failure).reason()).isEqualTo(LegalIdempotencyException.Reason.UNAVAILABLE);
    }
}
