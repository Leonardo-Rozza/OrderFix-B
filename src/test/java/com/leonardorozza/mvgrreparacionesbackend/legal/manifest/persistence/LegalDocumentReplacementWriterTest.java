package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalDocumentReplacementWriterTest {

    private static final UUID SOURCE_PUBLICATION_ID = id(1);
    private static final UUID TARGET_PUBLICATION_ID = id(2);

    private static final UUID ADDITION_DOCUMENT_ID = id(101);
    private static final UUID REUSED_DOCUMENT_ID = id(102);
    private static final UUID PREDECESSOR_DOCUMENT_ID = id(103);
    private static final UUID SUCCESSOR_DOCUMENT_ID = id(104);
    private static final UUID RETIRED_DOCUMENT_ID = id(105);

    private static final UUID ADDITION_DOCUMENT_LINE_ID = id(201);
    private static final UUID REUSED_DOCUMENT_LINE_ID = id(202);
    private static final UUID PREDECESSOR_DOCUMENT_LINE_ID = id(203);
    private static final UUID SUCCESSOR_DOCUMENT_LINE_ID = id(204);
    private static final UUID RETIRED_DOCUMENT_LINE_ID = id(205);

    private static final UUID ADDITION_REQUIREMENT_ID = id(301);
    private static final UUID REPLACED_REQUIREMENT_ID = id(302);
    private static final UUID RETIRED_REQUIREMENT_ID = id(303);
    private static final UUID ADDITION_REQUIREMENT_LINE_ID = id(401);
    private static final UUID REPLACED_REQUIREMENT_LINE_ID = id(402);
    private static final UUID RETIRED_REQUIREMENT_LINE_ID = id(403);

    private static final UUID SOURCE_REQUIRED_SET_ID = id(501);
    private static final UUID TARGET_REQUIRED_SET_ID = id(502);
    private static final UUID REPLACEMENT_BATCH_ID = id(601);
    private static final UUID HISTORICAL_BATCH_ID = id(602);

    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-28T10:00:00Z");
    private static final Instant LIVE_AT = Instant.parse("2026-08-29T10:00:00Z");
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-30T10:00:00Z");
    private static final String SOURCE_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String TARGET_REVISION = "sha256:" + "b".repeat(64);
    private static final String RETIREMENT_REASON = "Fuera del alcance vigente";

    private static final LegalEditorialExecutionPlan.DocumentSlotKey ADDITION_SLOT =
            new LegalEditorialExecutionPlan.DocumentSlotKey(
                    TipoDocumentoLegal.POLITICA_CIERRE_CUENTA,
                    LocaleLegal.ES_AR,
                    ContextoLegal.CIERRE_CUENTA);
    private static final LegalEditorialExecutionPlan.DocumentSlotKey REUSE_SLOT =
            new LegalEditorialExecutionPlan.DocumentSlotKey(
                    TipoDocumentoLegal.TERMINOS_SERVICIO,
                    LocaleLegal.ES_AR,
                    ContextoLegal.REGISTRO);
    private static final LegalEditorialExecutionPlan.DocumentSlotKey REPLACEMENT_SLOT =
            new LegalEditorialExecutionPlan.DocumentSlotKey(
                    TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                    LocaleLegal.ES_AR,
                    ContextoLegal.USO_CONTINUADO);
    private static final LegalEditorialExecutionPlan.RequiredSetPointerKey POINTER_KEY =
            new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                    LocaleLegal.ES_AR,
                    ContextoLegal.REGISTRO,
                    AudienciaLegal.ADMIN_TITULAR);

    @Test
    void implementsTheSharedMutationBoundaryAndKeepsTheExactJdbcSession() {
        JdbcTemplate jdbc = new JdbcTemplate();
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);

        LegalEditorialMutationWriter writer = new LegalDocumentReplacementWriter(
                jdbc,
                readiness);

        assertThat(writer).isInstanceOf(LegalDocumentReplacementWriter.class);
        assertThat(writer.usesJdbc(jdbc)).isTrue();
        assertThat(writer.usesJdbc(new JdbcTemplate())).isFalse();
    }

    @Test
    void rejectsReplacementBatchWithoutPredecessorsBeforeTouchingJdbc() {
        LegalEditorialExecutionPlan.ReplacementBatch batch = replacementBatchMock(
                List.of(),
                List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                        id(801),
                        TARGET_PUBLICATION_ID)));

        assertInvalidReplacementMappingBeforeJdbc(batch);
    }

    @Test
    void rejectsReplacementBatchWithoutSuccessorsBeforeTouchingJdbc() {
        LegalEditorialExecutionPlan.ReplacementBatch batch = replacementBatchMock(
                List.of(id(801)),
                List.of());

        assertInvalidReplacementMappingBeforeJdbc(batch);
    }

    @Test
    void rejectsManyToManyReplacementBatchBeforeTouchingJdbc() {
        LegalEditorialExecutionPlan.ReplacementBatch batch = replacementBatchMock(
                List.of(id(801), id(802)),
                List.of(
                        new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                id(803),
                                TARGET_PUBLICATION_ID),
                        new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                id(804),
                                TARGET_PUBLICATION_ID)));

        assertInvalidReplacementMappingBeforeJdbc(batch);
    }

    private static void assertInvalidReplacementMappingBeforeJdbc(
            LegalEditorialExecutionPlan.ReplacementBatch batch) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        LegalEditorialExecutionPlan.MutationCommands commands =
                mock(LegalEditorialExecutionPlan.MutationCommands.class);
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.REPLACE);
        when(plan.changeRequired()).thenReturn(true);
        when(plan.mutationCommands()).thenReturn(commands);
        when(commands.replacementBatchesToCreateAndSeal()).thenReturn(List.of(batch));

        LegalDocumentReplacementWriter writer = new LegalDocumentReplacementWriter(
                jdbc,
                readiness);

        assertThatThrownBy(() -> writer.write(plan))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure -> {
                    assertThat(failure.issue().code())
                            .isEqualTo(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID);
                    assertThat(failure.issue().location())
                            .isEqualTo("documentReplacementBatches");
                });
        verifyNoInteractions(jdbc);
    }

    private static LegalEditorialExecutionPlan.ReplacementBatch replacementBatchMock(
            List<UUID> predecessors,
            List<LegalEditorialExecutionPlan.ReplacementSuccessor> successors) {
        LegalEditorialExecutionPlan.ReplacementBatch batch =
                mock(LegalEditorialExecutionPlan.ReplacementBatch.class);
        when(batch.predecessorDocumentVersionIds()).thenReturn(predecessors);
        when(batch.successors()).thenReturn(successors);
        return batch;
    }

    @Test
    void locksAndRevalidatesTheWholeFreshGraphBeforeExecutingTheSafeMixedDmlOrder() {
        Fixture fixture = fixture(true, SOURCE_FINGERPRINT);

        fixture.writer().write(fixture.plan());

        assertThat(fixture.jdbc().querySignatures()).containsExactly(
                "document-versions:read",
                "requirement-versions:read",
                "publications:lock",
                "document-lines:lock",
                "requirement-lines:lock",
                "document-versions:lock",
                "requirement-versions:lock",
                "target-documents:read",
                "target-requirements:read",
                "current-document-slots:read",
                "current-required-sets:read",
                "target-required-set-scopes:read",
                "replacement-batches:absence-check");
        assertThat(fixture.jdbc().queryArguments().get(0))
                .containsExactlyElementsOf(fixture.jdbc().sortedDocumentIds());
        assertThat(fixture.jdbc().queryArguments().get(1))
                .containsExactlyElementsOf(fixture.jdbc().sortedRequirementIds());
        assertThat(fixture.jdbc().queryArguments().get(2))
                .containsExactly(SOURCE_PUBLICATION_ID, TARGET_PUBLICATION_ID);
        assertThat(fixture.jdbc().queryArguments().get(3)).containsExactly(
                ADDITION_DOCUMENT_LINE_ID,
                REUSED_DOCUMENT_LINE_ID,
                PREDECESSOR_DOCUMENT_LINE_ID,
                SUCCESSOR_DOCUMENT_LINE_ID,
                RETIRED_DOCUMENT_LINE_ID);
        assertThat(fixture.jdbc().queryArguments().get(4)).containsExactly(
                ADDITION_REQUIREMENT_LINE_ID,
                REPLACED_REQUIREMENT_LINE_ID,
                RETIRED_REQUIREMENT_LINE_ID);
        assertThat(fixture.jdbc().queryArguments().get(5))
                .containsExactlyElementsOf(fixture.jdbc().sortedDocumentIds());
        assertThat(fixture.jdbc().queryArguments().get(6))
                .containsExactlyElementsOf(fixture.jdbc().sortedRequirementIds());
        verify(fixture.readiness()).observeState("legal-source-v1", OBSERVED_AT);

        assertThat(fixture.jdbc().dmlSignatures()).containsExactly(
                "document-transition:BORRADOR->PUBLICADA",
                "requirement-transition:BORRADOR->PUBLICADA",
                "required-set-pointer:delete",
                "document-slot:delete",
                "document-transition:PUBLICADA->VIGENTE",
                "document-slot:insert",
                "replacement-batch:insert",
                "replacement-predecessor:insert",
                "replacement-successor:insert",
                "replacement-batch:seal",
                "document-transition:VIGENTE->RETIRADA",
                "requirement-transition:PUBLICADA->VIGENTE",
                "requirement-transition:VIGENTE->REEMPLAZADA",
                "requirement-transition:VIGENTE->RETIRADA",
                "document-slot:insert",
                "required-set-pointer:insert");

        List<DmlCall> calls = fixture.jdbc().dmlCalls();
        assertThat(rows(calls.get(0))).containsExactly(
                parameters(
                        ADDITION_DOCUMENT_ID,
                        "BORRADOR",
                        "PUBLICADA",
                        null,
                        null,
                        Timestamp.from(OBSERVED_AT)),
                parameters(
                        SUCCESSOR_DOCUMENT_ID,
                        "BORRADOR",
                        "PUBLICADA",
                        null,
                        null,
                        Timestamp.from(OBSERVED_AT)));
        assertThat(rows(calls.get(1))).containsExactly(parameters(
                ADDITION_REQUIREMENT_ID,
                "BORRADOR",
                "PUBLICADA",
                null,
                Timestamp.from(OBSERVED_AT)));
        assertThat(rows(calls.get(2))).containsExactly(parameters(
                LocaleLegal.ES_AR.getCodigo(),
                ContextoLegal.REGISTRO.name(),
                AudienciaLegal.ADMIN_TITULAR.name(),
                SOURCE_REQUIRED_SET_ID));
        assertThat(rows(calls.get(3))).containsExactly(
                parameters(
                        TipoDocumentoLegal.POLITICA_CIERRE_CUENTA.name(),
                        LocaleLegal.ES_AR.getCodigo(),
                        ContextoLegal.CIERRE_CUENTA.name(),
                        RETIRED_DOCUMENT_ID),
                parameters(
                        TipoDocumentoLegal.TERMINOS_SERVICIO.name(),
                        LocaleLegal.ES_AR.getCodigo(),
                        ContextoLegal.REGISTRO.name(),
                        REUSED_DOCUMENT_ID));
        assertThat(rows(calls.get(4))).containsExactly(parameters(
                ADDITION_DOCUMENT_ID,
                "PUBLICADA",
                "VIGENTE",
                null,
                null,
                Timestamp.from(OBSERVED_AT)));
        assertThat(rows(calls.get(5))).containsExactly(parameters(
                TipoDocumentoLegal.POLITICA_CIERRE_CUENTA.name(),
                LocaleLegal.ES_AR.getCodigo(),
                ContextoLegal.CIERRE_CUENTA.name(),
                ADDITION_DOCUMENT_ID,
                ADDITION_DOCUMENT_LINE_ID,
                TARGET_PUBLICATION_ID));
        assertThat(rows(calls.get(6))).containsExactly(parameters(
                REPLACEMENT_BATCH_ID,
                Timestamp.from(OBSERVED_AT)));
        assertThat(rows(calls.get(7))).containsExactly(parameters(
                REPLACEMENT_BATCH_ID,
                PREDECESSOR_DOCUMENT_ID));
        assertThat(rows(calls.get(8))).containsExactly(parameters(
                REPLACEMENT_BATCH_ID,
                SUCCESSOR_DOCUMENT_ID,
                TARGET_PUBLICATION_ID));
        assertThat(rows(calls.get(9))).containsExactly(parameters(
                Timestamp.from(OBSERVED_AT),
                REPLACEMENT_BATCH_ID));
        assertThat(rows(calls.get(10))).containsExactly(parameters(
                RETIRED_DOCUMENT_ID,
                "VIGENTE",
                "RETIRADA",
                RETIREMENT_REASON,
                null,
                Timestamp.from(OBSERVED_AT)));
        assertThat(rows(calls.get(11))).containsExactly(parameters(
                ADDITION_REQUIREMENT_ID,
                "PUBLICADA",
                "VIGENTE",
                null,
                Timestamp.from(OBSERVED_AT)));
        assertThat(rows(calls.get(12))).containsExactly(parameters(
                REPLACED_REQUIREMENT_ID,
                "VIGENTE",
                "REEMPLAZADA",
                null,
                Timestamp.from(OBSERVED_AT)));
        assertThat(rows(calls.get(13))).containsExactly(parameters(
                RETIRED_REQUIREMENT_ID,
                "VIGENTE",
                "RETIRADA",
                RETIREMENT_REASON,
                Timestamp.from(OBSERVED_AT)));
        assertThat(rows(calls.get(14))).containsExactly(parameters(
                TipoDocumentoLegal.TERMINOS_SERVICIO.name(),
                LocaleLegal.ES_AR.getCodigo(),
                ContextoLegal.REGISTRO.name(),
                REUSED_DOCUMENT_ID,
                REUSED_DOCUMENT_LINE_ID,
                TARGET_PUBLICATION_ID));
        assertThat(rows(calls.get(15))).containsExactly(parameters(
                LocaleLegal.ES_AR.getCodigo(),
                ContextoLegal.REGISTRO.name(),
                AudienciaLegal.ADMIN_TITULAR.name(),
                TARGET_REQUIRED_SET_ID,
                TARGET_PUBLICATION_ID,
                Timestamp.from(OBSERVED_AT)));

        List<DmlCall> slotInserts = fixture.jdbc().dmlCalls().stream()
                .filter(call -> call.signature().equals("document-slot:insert"))
                .toList();
        assertThat(slotInserts).hasSize(2);
        assertThat(slotInserts.get(0).rows()).singleElement()
                .satisfies(row -> assertThat(row[3]).isEqualTo(ADDITION_DOCUMENT_ID));
        assertThat(slotInserts.get(1).rows()).singleElement()
                .satisfies(row -> assertThat(row[3]).isEqualTo(REUSED_DOCUMENT_ID));

        List<List<Object>> directDocumentTransitions = fixture.jdbc().dmlCalls().stream()
                .filter(call -> call.signature().startsWith("document-transition:"))
                .flatMap(call -> call.rows().stream())
                .map(row -> List.of(row[0], row[1], row[2]))
                .toList();
        assertThat(directDocumentTransitions).containsExactly(
                List.of(ADDITION_DOCUMENT_ID, "BORRADOR", "PUBLICADA"),
                List.of(SUCCESSOR_DOCUMENT_ID, "BORRADOR", "PUBLICADA"),
                List.of(ADDITION_DOCUMENT_ID, "PUBLICADA", "VIGENTE"),
                List.of(RETIRED_DOCUMENT_ID, "VIGENTE", "RETIRADA"));
        assertThat(fixture.jdbc().allDmlArguments())
                .doesNotContain(HISTORICAL_BATCH_ID);
        assertThat(fixture.jdbc().normalizedDml())
                .noneMatch(sql -> sql.contains("update legal_documento_versiones")
                        || sql.contains("update legal_requisito_versiones"));
    }

    @Test
    void executesTheFreshReplacementWithoutCreatingAnyBatchWhenThePlanHasNoBatch() {
        Fixture fixture = fixture(false, SOURCE_FINGERPRINT);

        fixture.writer().write(fixture.plan());

        assertThat(fixture.jdbc().querySignatures())
                .doesNotContain("replacement-batches:absence-check");
        assertThat(fixture.jdbc().dmlSignatures())
                .noneMatch(signature -> signature.startsWith("replacement-"));
        assertThat(fixture.jdbc().dmlSignatures()).containsSubsequence(
                "document-slot:delete",
                "document-transition:PUBLICADA->VIGENTE",
                "document-slot:insert",
                "document-transition:VIGENTE->RETIRADA",
                "document-slot:insert",
                "required-set-pointer:insert");
        assertThat(fixture.jdbc().allDmlArguments())
                .doesNotContain(HISTORICAL_BATCH_ID);
    }

    @Test
    void writesSplitMergeAndMultipleBatchMembershipsInCanonicalCommandOrder() {
        Fixture fixture = fixture(true, SOURCE_FINGERPRINT);
        UUID splitBatchId = id(611);
        UUID mergeBatchId = id(610);
        LegalEditorialExecutionPlan.ReplacementBatch split = replacementBatch(
                splitBatchId,
                List.of(id(801)),
                List.of(id(802), id(803)));
        LegalEditorialExecutionPlan.ReplacementBatch merge = replacementBatch(
                mergeBatchId,
                List.of(id(901), id(902)),
                List.of(id(903)));
        List<LegalEditorialExecutionPlan.ReplacementBatch> canonicalBatches =
                replaceCurrentBatches(fixture, List.of(merge, split));

        fixture.writer().write(fixture.plan());

        assertThat(canonicalBatches)
                .extracting(LegalEditorialExecutionPlan.ReplacementBatch::batchId)
                .containsExactly(splitBatchId, mergeBatchId);
        List<DmlCall> replacementCalls = fixture.jdbc().dmlCalls().stream()
                .filter(call -> call.signature().startsWith("replacement-"))
                .toList();
        assertThat(replacementCalls)
                .extracting(DmlCall::signature)
                .containsExactly(
                        "replacement-batch:insert",
                        "replacement-predecessor:insert",
                        "replacement-successor:insert",
                        "replacement-batch:seal",
                        "replacement-batch:seal");
        assertThat(rows(replacementCalls.get(0))).containsExactly(
                parameters(splitBatchId, Timestamp.from(OBSERVED_AT)),
                parameters(mergeBatchId, Timestamp.from(OBSERVED_AT)));
        assertThat(rows(replacementCalls.get(1))).containsExactly(
                parameters(splitBatchId, id(801)),
                parameters(mergeBatchId, id(901)),
                parameters(mergeBatchId, id(902)));
        assertThat(rows(replacementCalls.get(2))).containsExactly(
                parameters(splitBatchId, id(802), TARGET_PUBLICATION_ID),
                parameters(splitBatchId, id(803), TARGET_PUBLICATION_ID),
                parameters(mergeBatchId, id(903), TARGET_PUBLICATION_ID));
        assertThat(rows(replacementCalls.get(3))).containsExactly(parameters(
                Timestamp.from(OBSERVED_AT),
                splitBatchId));
        assertThat(rows(replacementCalls.get(4))).containsExactly(parameters(
                Timestamp.from(OBSERVED_AT),
                mergeBatchId));
    }

    @Test
    void stopsAtTheSecondUnexpectedSealCardinality() {
        Fixture fixture = fixture(true, SOURCE_FINGERPRINT);
        UUID splitBatchId = id(611);
        UUID mergeBatchId = id(610);
        replaceCurrentBatches(fixture, List.of(
                replacementBatch(
                        mergeBatchId,
                        List.of(id(901), id(902)),
                        List.of(id(903))),
                replacementBatch(
                        splitBatchId,
                        List.of(id(801)),
                        List.of(id(802), id(803)))));
        fixture.jdbc().failUpdateNumber(2);

        assertThatThrownBy(() -> fixture.writer().write(fixture.plan()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El DML REPLACE no afectó exactamente una fila esperada");

        assertThat(fixture.jdbc().dmlSignatures()).endsWith(
                "replacement-batch:insert",
                "replacement-predecessor:insert",
                "replacement-successor:insert",
                "replacement-batch:seal",
                "replacement-batch:seal");
        assertThat(rows(fixture.jdbc().dmlCalls().getLast())).containsExactly(parameters(
                Timestamp.from(OBSERVED_AT),
                mergeBatchId));
    }

    @Test
    void blocksObservedGraphDriftBeforeFingerprintRevalidationOrAnyDml() {
        Fixture fixture = fixture(true, SOURCE_FINGERPRINT);
        fixture.jdbc().omitTargetDocument(REUSED_DOCUMENT_ID);

        assertThatThrownBy(() -> fixture.writer().write(fixture.plan()))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure -> {
                    assertThat(failure.issue().code())
                            .isEqualTo(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
                    assertThat(failure.issue().location()).isEqualTo("database/state");
                });

        assertThat(fixture.jdbc().dmlCalls()).isEmpty();
        verify(fixture.readiness(), never()).observeState(anyString(), any());
    }

    @Test
    void blocksFingerprintDriftAfterLocksAndBeforeAnyDml() {
        String driftedFingerprint = "sha256:" + "f".repeat(64);
        Fixture fixture = fixture(true, driftedFingerprint);

        assertThatThrownBy(() -> fixture.writer().write(fixture.plan()))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure -> {
                    assertThat(failure.issue().code())
                            .isEqualTo(LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH);
                    assertThat(failure.issue().location()).isEqualTo("database/source");
                });

        assertThat(fixture.jdbc().querySignatures())
                .endsWith("replacement-batches:absence-check");
        assertThat(fixture.jdbc().dmlCalls()).isEmpty();
        verify(fixture.readiness()).observeState("legal-source-v1", OBSERVED_AT);
    }

    @Test
    void stopsOnTheFirstUnexpectedDmlCardinality() {
        Fixture fixture = fixture(true, SOURCE_FINGERPRINT);
        fixture.jdbc().failNextBatchContaining("legal_documento_transiciones");

        assertThatThrownBy(() -> fixture.writer().write(fixture.plan()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El DML REPLACE no afectó exactamente una fila esperada");

        assertThat(fixture.jdbc().dmlSignatures())
                .containsExactly("document-transition:BORRADOR->PUBLICADA");
    }

    @Test
    void acceptsSuccessNoInfoForInsertBatchElements() {
        Fixture fixture = fixture(true, SOURCE_FINGERPRINT);
        fixture.jdbc().returnSuccessNoInfoForNextBatchContaining(
                "legal_documento_transiciones");

        fixture.writer().write(fixture.plan());

        assertThat(fixture.jdbc().dmlSignatures()).hasSize(16);
    }

    @Test
    void rejectsSuccessNoInfoForDeleteBatchElements() {
        Fixture fixture = fixture(true, SOURCE_FINGERPRINT);
        fixture.jdbc().returnSuccessNoInfoForNextBatchContaining(
                "delete from legal_requisito_conjuntos_actuales");

        assertThatThrownBy(() -> fixture.writer().write(fixture.plan()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El DML REPLACE no afectó exactamente una fila esperada");

        assertThat(fixture.jdbc().dmlSignatures()).containsExactly(
                "document-transition:BORRADOR->PUBLICADA",
                "requirement-transition:BORRADOR->PUBLICADA",
                "required-set-pointer:delete");
    }

    private static LegalEditorialExecutionPlan.ReplacementBatch replacementBatch(
            UUID batchId,
            List<UUID> predecessors,
            List<UUID> successorIds) {
        return new LegalEditorialExecutionPlan.ReplacementBatch(
                batchId,
                OBSERVED_AT,
                OBSERVED_AT,
                predecessors,
                successorIds.stream()
                        .map(successorId ->
                                new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                        successorId,
                                        TARGET_PUBLICATION_ID))
                        .toList());
    }

    private static List<LegalEditorialExecutionPlan.ReplacementBatch> replaceCurrentBatches(
            Fixture fixture,
            List<LegalEditorialExecutionPlan.ReplacementBatch> batches) {
        LegalEditorialExecutionPlan.MutationCommands original =
                fixture.plan().mutationCommands();
        LegalEditorialExecutionPlan.MutationCommands updated =
                new LegalEditorialExecutionPlan.MutationCommands(
                        original.documentTransitions(),
                        original.requirementTransitions(),
                        original.documentSlotDeletes(),
                        original.documentSlotInserts(),
                        original.requiredSetPointerDeletes(),
                        original.requiredSetPointerInserts(),
                        batches);
        when(fixture.plan().mutationCommands()).thenReturn(updated);
        LegalEditorialExecutionPlan.ExpectedPostState expected =
                fixture.plan().expectedPostState();
        when(expected.replacementBatches()).thenReturn(
                updated.replacementBatchesToCreateAndSeal());
        return updated.replacementBatchesToCreateAndSeal();
    }

    private static Fixture fixture(boolean withBatch, String observedFingerprint) {
        List<LegalEditorialExecutionPlan.DocumentTransition> preexistingDocuments =
                new ArrayList<>();
        addPublishedHistory(
                preexistingDocuments,
                REUSED_DOCUMENT_ID,
                HISTORICAL_BATCH_ID);
        addPublishedHistory(preexistingDocuments, RETIRED_DOCUMENT_ID, null);
        if (withBatch) {
            addPublishedHistory(preexistingDocuments, PREDECESSOR_DOCUMENT_ID, null);
        }

        List<LegalEditorialExecutionPlan.DocumentTransition> documentTransitions =
                new ArrayList<>(List.of(
                        documentTransition(
                                ADDITION_DOCUMENT_ID,
                                EstadoVersionLegal.BORRADOR,
                                EstadoVersionLegal.PUBLICADA,
                                null,
                                null),
                        documentTransition(
                                ADDITION_DOCUMENT_ID,
                                EstadoVersionLegal.PUBLICADA,
                                EstadoVersionLegal.VIGENTE,
                                null,
                                null),
                        documentTransition(
                                RETIRED_DOCUMENT_ID,
                                EstadoVersionLegal.VIGENTE,
                                EstadoVersionLegal.RETIRADA,
                                RETIREMENT_REASON,
                                null)));
        List<LegalEditorialExecutionPlan.DocumentTransition> directDocumentTransitions =
                new ArrayList<>(documentTransitions);

        List<LegalEditorialExecutionPlan.ExpectedDocumentState> documentStates =
                new ArrayList<>(List.of(
                        new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                ADDITION_DOCUMENT_ID,
                                EstadoVersionLegal.VIGENTE,
                                OBSERVED_AT,
                                null,
                                null),
                        new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                REUSED_DOCUMENT_ID,
                                EstadoVersionLegal.VIGENTE,
                                LIVE_AT,
                                null,
                                HISTORICAL_BATCH_ID),
                        new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                RETIRED_DOCUMENT_ID,
                                EstadoVersionLegal.RETIRADA,
                                OBSERVED_AT,
                                RETIREMENT_REASON,
                                null)));

        List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> finalDocumentSlots =
                new ArrayList<>(List.of(
                        expectedSlot(
                                ADDITION_SLOT,
                                ADDITION_DOCUMENT_ID,
                                ADDITION_DOCUMENT_LINE_ID,
                                TARGET_PUBLICATION_ID),
                        expectedSlot(
                                REUSE_SLOT,
                                REUSED_DOCUMENT_ID,
                                REUSED_DOCUMENT_LINE_ID,
                                TARGET_PUBLICATION_ID)));
        List<LegalEditorialExecutionPlan.ReplacementBatch> batches = new ArrayList<>();
        LegalEditorialExecutionPlan.V27TriggerEffects v27 =
                LegalEditorialExecutionPlan.V27TriggerEffects.empty();
        if (withBatch) {
            LegalEditorialExecutionPlan.DocumentTransition publishSuccessor =
                    documentTransition(
                            SUCCESSOR_DOCUMENT_ID,
                            EstadoVersionLegal.BORRADOR,
                            EstadoVersionLegal.PUBLICADA,
                            null,
                            null);
            LegalEditorialExecutionPlan.DocumentTransition activateSuccessor =
                    documentTransition(
                            SUCCESSOR_DOCUMENT_ID,
                            EstadoVersionLegal.PUBLICADA,
                            EstadoVersionLegal.VIGENTE,
                            null,
                            REPLACEMENT_BATCH_ID);
            LegalEditorialExecutionPlan.DocumentTransition replacePredecessor =
                    documentTransition(
                            PREDECESSOR_DOCUMENT_ID,
                            EstadoVersionLegal.VIGENTE,
                            EstadoVersionLegal.REEMPLAZADA,
                            null,
                            REPLACEMENT_BATCH_ID);
            documentTransitions.add(publishSuccessor);
            documentTransitions.add(activateSuccessor);
            documentTransitions.add(replacePredecessor);
            directDocumentTransitions.add(publishSuccessor);
            documentStates.add(new LegalEditorialExecutionPlan.ExpectedDocumentState(
                    PREDECESSOR_DOCUMENT_ID,
                    EstadoVersionLegal.REEMPLAZADA,
                    OBSERVED_AT,
                    null,
                    REPLACEMENT_BATCH_ID));
            documentStates.add(new LegalEditorialExecutionPlan.ExpectedDocumentState(
                    SUCCESSOR_DOCUMENT_ID,
                    EstadoVersionLegal.VIGENTE,
                    OBSERVED_AT,
                    null,
                    REPLACEMENT_BATCH_ID));
            LegalEditorialExecutionPlan.ExpectedDocumentSlot successorSlot = expectedSlot(
                    REPLACEMENT_SLOT,
                    SUCCESSOR_DOCUMENT_ID,
                    SUCCESSOR_DOCUMENT_LINE_ID,
                    TARGET_PUBLICATION_ID);
            finalDocumentSlots.add(successorSlot);
            batches.add(new LegalEditorialExecutionPlan.ReplacementBatch(
                    REPLACEMENT_BATCH_ID,
                    OBSERVED_AT,
                    OBSERVED_AT,
                    List.of(PREDECESSOR_DOCUMENT_ID),
                    List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                            SUCCESSOR_DOCUMENT_ID,
                            TARGET_PUBLICATION_ID))));
            v27 = new LegalEditorialExecutionPlan.V27TriggerEffects(
                    List.of(activateSuccessor, replacePredecessor),
                    List.of(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                            REPLACEMENT_SLOT,
                            PREDECESSOR_DOCUMENT_ID)),
                    List.of(successorSlot));
        }

        List<LegalEditorialExecutionPlan.RequirementTransition> preexistingRequirements =
                new ArrayList<>();
        addPublishedRequirementHistory(preexistingRequirements, REPLACED_REQUIREMENT_ID);
        addPublishedRequirementHistory(preexistingRequirements, RETIRED_REQUIREMENT_ID);
        List<LegalEditorialExecutionPlan.RequirementTransition> requirementTransitions = List.of(
                requirementTransition(
                        ADDITION_REQUIREMENT_ID,
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA,
                        null),
                requirementTransition(
                        ADDITION_REQUIREMENT_ID,
                        EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE,
                        null),
                requirementTransition(
                        REPLACED_REQUIREMENT_ID,
                        EstadoVersionLegal.VIGENTE,
                        EstadoVersionLegal.REEMPLAZADA,
                        null),
                requirementTransition(
                        RETIRED_REQUIREMENT_ID,
                        EstadoVersionLegal.VIGENTE,
                        EstadoVersionLegal.RETIRADA,
                        RETIREMENT_REASON));
        List<LegalEditorialExecutionPlan.ExpectedRequirementState> requirementStates = List.of(
                new LegalEditorialExecutionPlan.ExpectedRequirementState(
                        ADDITION_REQUIREMENT_ID,
                        EstadoVersionLegal.VIGENTE,
                        OBSERVED_AT,
                        null),
                new LegalEditorialExecutionPlan.ExpectedRequirementState(
                        REPLACED_REQUIREMENT_ID,
                        EstadoVersionLegal.REEMPLAZADA,
                        OBSERVED_AT,
                        null),
                new LegalEditorialExecutionPlan.ExpectedRequirementState(
                        RETIRED_REQUIREMENT_ID,
                        EstadoVersionLegal.RETIRADA,
                        OBSERVED_AT,
                        RETIREMENT_REASON));

        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer pointer =
                new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                        POINTER_KEY,
                        TARGET_REQUIRED_SET_ID,
                        TARGET_PUBLICATION_ID,
                        TARGET_REVISION,
                        OBSERVED_AT);
        LegalEditorialExecutionPlan.MutationCommands commands =
                new LegalEditorialExecutionPlan.MutationCommands(
                        directDocumentTransitions,
                        requirementTransitions,
                        List.of(
                                new LegalEditorialExecutionPlan.DocumentSlotDelete(
                                        ADDITION_SLOT,
                                        RETIRED_DOCUMENT_ID),
                                new LegalEditorialExecutionPlan.DocumentSlotDelete(
                                        REUSE_SLOT,
                                        REUSED_DOCUMENT_ID)),
                        List.of(
                                expectedSlot(
                                        ADDITION_SLOT,
                                        ADDITION_DOCUMENT_ID,
                                        ADDITION_DOCUMENT_LINE_ID,
                                        TARGET_PUBLICATION_ID),
                                expectedSlot(
                                        REUSE_SLOT,
                                        REUSED_DOCUMENT_ID,
                                        REUSED_DOCUMENT_LINE_ID,
                                        TARGET_PUBLICATION_ID)),
                        List.of(new LegalEditorialExecutionPlan.RequiredSetPointerDelete(
                                POINTER_KEY,
                                SOURCE_REQUIRED_SET_ID)),
                        List.of(pointer),
                        batches);

        LegalEditorialExecutionPlan.ExpectedPostState expected =
                mock(LegalEditorialExecutionPlan.ExpectedPostState.class);
        when(expected.documentStates()).thenReturn(List.copyOf(documentStates));
        when(expected.requirementStates()).thenReturn(requirementStates);
        when(expected.documentTransitions()).thenReturn(List.copyOf(documentTransitions));
        when(expected.requirementTransitions()).thenReturn(requirementTransitions);
        when(expected.preexistingDocumentTransitions())
                .thenReturn(List.copyOf(preexistingDocuments));
        when(expected.preexistingRequirementTransitions())
                .thenReturn(List.copyOf(preexistingRequirements));
        when(expected.documentSlots()).thenReturn(List.copyOf(finalDocumentSlots));
        when(expected.requiredSetPointers()).thenReturn(List.of(pointer));
        when(expected.preexistingReplacementBatches()).thenReturn(List.of(
                new LegalEditorialExecutionPlan.ReplacementBatch(
                        HISTORICAL_BATCH_ID,
                        PUBLISHED_AT,
                        LIVE_AT,
                        List.of(id(999)),
                        List.of(new LegalEditorialExecutionPlan.ReplacementSuccessor(
                                REUSED_DOCUMENT_ID,
                                SOURCE_PUBLICATION_ID)))));
        when(expected.replacementBatches()).thenReturn(List.copyOf(batches));
        when(expected.v27TriggerEffects()).thenReturn(v27);

        LegalEditorialExecutionPlan.PublicationIdentity sourcePublication =
                new LegalEditorialExecutionPlan.PublicationIdentity(
                        "legal-source-v1",
                        SOURCE_PUBLICATION_ID,
                        "c".repeat(64));
        LegalEditorialExecutionPlan.PublicationIdentity targetPublication =
                new LegalEditorialExecutionPlan.PublicationIdentity(
                        "legal-target-v2",
                        TARGET_PUBLICATION_ID,
                        "d".repeat(64));
        LegalEditorialExecutionPlan.SourceIdentity source =
                new LegalEditorialExecutionPlan.SourceIdentity(
                        sourcePublication,
                        SOURCE_FINGERPRINT);
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.REPLACE);
        when(plan.source()).thenReturn(Optional.of(source));
        when(plan.target()).thenReturn(targetPublication);
        when(plan.operationId()).thenReturn(Optional.of(id(701)));
        when(plan.planSha256()).thenReturn(Optional.of("e".repeat(64)));
        when(plan.transactionAt()).thenReturn(OBSERVED_AT);
        when(plan.observedAt()).thenReturn(OBSERVED_AT);
        when(plan.expectedAppliedAt()).thenReturn(OBSERVED_AT);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        when(plan.acknowledgeFailClosedGap()).thenReturn(false);
        when(plan.changeRequired()).thenReturn(true);
        when(plan.expectedPostState()).thenReturn(expected);
        when(plan.mutationCommands()).thenReturn(commands);

        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(
                sourcePublication,
                targetPublication,
                documentStates,
                requirementStates,
                preexistingDocuments,
                preexistingRequirements,
                withBatch);
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);
        when(readiness.observeState("legal-source-v1", OBSERVED_AT)).thenReturn(
                new LegalEditorialReadinessObservation(
                        Optional.of(SOURCE_PUBLICATION_ID),
                        OBSERVED_AT,
                        observedFingerprint,
                        documentStates.size(),
                        requirementStates.size(),
                        preexistingDocuments.size() + documentTransitions.size(),
                        preexistingRequirements.size() + requirementTransitions.size(),
                        finalDocumentSlots.size(),
                        1,
                        withBatch ? 2 : 1));
        return new Fixture(
                plan,
                jdbc,
                readiness,
                new LegalDocumentReplacementWriter(jdbc, readiness));
    }

    private static void addPublishedHistory(
            List<LegalEditorialExecutionPlan.DocumentTransition> transitions,
            UUID documentId,
            UUID liveBatchId) {
        transitions.add(new LegalEditorialExecutionPlan.DocumentTransition(
                documentId,
                EstadoVersionLegal.BORRADOR,
                EstadoVersionLegal.PUBLICADA,
                null,
                null,
                PUBLISHED_AT));
        transitions.add(new LegalEditorialExecutionPlan.DocumentTransition(
                documentId,
                EstadoVersionLegal.PUBLICADA,
                EstadoVersionLegal.VIGENTE,
                null,
                liveBatchId,
                LIVE_AT));
    }

    private static void addPublishedRequirementHistory(
            List<LegalEditorialExecutionPlan.RequirementTransition> transitions,
            UUID requirementId) {
        transitions.add(new LegalEditorialExecutionPlan.RequirementTransition(
                requirementId,
                EstadoVersionLegal.BORRADOR,
                EstadoVersionLegal.PUBLICADA,
                null,
                PUBLISHED_AT));
        transitions.add(new LegalEditorialExecutionPlan.RequirementTransition(
                requirementId,
                EstadoVersionLegal.PUBLICADA,
                EstadoVersionLegal.VIGENTE,
                null,
                LIVE_AT));
    }

    private static LegalEditorialExecutionPlan.DocumentTransition documentTransition(
            UUID id,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            String reason,
            UUID batchId) {
        return new LegalEditorialExecutionPlan.DocumentTransition(
                id,
                previous,
                next,
                reason,
                batchId,
                OBSERVED_AT);
    }

    private static LegalEditorialExecutionPlan.RequirementTransition requirementTransition(
            UUID id,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            String reason) {
        return new LegalEditorialExecutionPlan.RequirementTransition(
                id,
                previous,
                next,
                reason,
                OBSERVED_AT);
    }

    private static LegalEditorialExecutionPlan.ExpectedDocumentSlot expectedSlot(
            LegalEditorialExecutionPlan.DocumentSlotKey key,
            UUID documentId,
            UUID lineId,
            UUID publicationId) {
        return new LegalEditorialExecutionPlan.ExpectedDocumentSlot(
                key,
                documentId,
                lineId,
                publicationId);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    private static List<List<Object>> rows(DmlCall call) {
        return call.rows().stream().map(Arrays::asList).toList();
    }

    private static List<Object> parameters(Object... values) {
        return Arrays.asList(values);
    }

    private record Fixture(
            LegalEditorialExecutionPlan plan,
            RecordingJdbcTemplate jdbc,
            LegalEditorialReadinessCore readiness,
            LegalDocumentReplacementWriter writer) {
    }

    private record QueryCall(String signature, String sql, List<Object> arguments) {
    }

    private record DmlCall(String signature, String sql, List<Object[]> rows) {
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private final LegalEditorialExecutionPlan.PublicationIdentity sourcePublication;
        private final LegalEditorialExecutionPlan.PublicationIdentity targetPublication;
        private final List<Map<String, Object>> documentRows;
        private final List<Map<String, Object>> requirementRows;
        private final List<UUID> documentLineIds;
        private final List<UUID> requirementLineIds;
        private final List<UUID> targetDocumentIds;
        private final List<UUID> targetRequirementIds;
        private final List<Map<String, Object>> currentSlots;
        private final List<QueryCall> queries = new ArrayList<>();
        private final List<DmlCall> dml = new ArrayList<>();
        private String forcedBatchSqlToken;
        private int forcedBatchCount;
        private int updateCallCount;
        private int failedUpdateCallNumber = -1;

        RecordingJdbcTemplate(
                LegalEditorialExecutionPlan.PublicationIdentity sourcePublication,
                LegalEditorialExecutionPlan.PublicationIdentity targetPublication,
                List<LegalEditorialExecutionPlan.ExpectedDocumentState> documentStates,
                List<LegalEditorialExecutionPlan.ExpectedRequirementState> requirementStates,
                List<LegalEditorialExecutionPlan.DocumentTransition> preexistingDocuments,
                List<LegalEditorialExecutionPlan.RequirementTransition> preexistingRequirements,
                boolean withBatch) {
            this.sourcePublication = sourcePublication;
            this.targetPublication = targetPublication;
            this.documentRows = documentStates.stream()
                    .map(state -> documentRow(state, preexistingDocuments))
                    .sorted((left, right) -> ((UUID) left.get("id")).toString()
                            .compareTo(((UUID) right.get("id")).toString()))
                    .toList();
            this.requirementRows = requirementStates.stream()
                    .map(state -> requirementRow(state, preexistingRequirements))
                    .sorted((left, right) -> ((UUID) left.get("id")).toString()
                            .compareTo(((UUID) right.get("id")).toString()))
                    .toList();
            this.documentLineIds = documentRows.stream()
                    .map(row -> (UUID) row.get("line_id"))
                    .sorted((left, right) -> left.toString().compareTo(right.toString()))
                    .toList();
            this.requirementLineIds = requirementRows.stream()
                    .map(row -> (UUID) row.get("line_id"))
                    .sorted((left, right) -> left.toString().compareTo(right.toString()))
                    .toList();
            this.targetDocumentIds = new ArrayList<>(documentStates.stream()
                    .filter(state -> state.state() == EstadoVersionLegal.VIGENTE)
                    .map(LegalEditorialExecutionPlan.ExpectedDocumentState::documentVersionId)
                    .toList());
            this.targetRequirementIds = requirementStates.stream()
                    .filter(state -> state.state() == EstadoVersionLegal.VIGENTE)
                    .map(LegalEditorialExecutionPlan.ExpectedRequirementState::requirementVersionId)
                    .toList();
            this.currentSlots = new ArrayList<>(List.of(
                    slotRow(
                            REUSE_SLOT,
                            REUSED_DOCUMENT_ID,
                            REUSED_DOCUMENT_LINE_ID),
                    slotRow(
                            ADDITION_SLOT,
                            RETIRED_DOCUMENT_ID,
                            RETIRED_DOCUMENT_LINE_ID)));
            if (withBatch) {
                this.currentSlots.add(slotRow(
                        REPLACEMENT_SLOT,
                        PREDECESSOR_DOCUMENT_ID,
                        PREDECESSOR_DOCUMENT_LINE_ID));
            }
        }

        @Override
        public List<Map<String, Object>> queryForList(String statement) {
            return queryForList(statement, new Object[0]);
        }

        @Override
        public List<Map<String, Object>> queryForList(String statement, Object... arguments) {
            String sql = normalized(statement);
            String signature = querySignature(sql);
            queries.add(new QueryCall(signature, sql, List.of(arguments)));
            return switch (signature) {
                case "document-versions:read", "document-versions:lock" -> documentRows;
                case "requirement-versions:read", "requirement-versions:lock" ->
                        requirementRows;
                case "publications:lock" -> List.of(
                        publicationRow(sourcePublication),
                        publicationRow(targetPublication));
                case "document-lines:lock" -> documentLineIds.stream()
                        .map(lineId -> row("id", lineId))
                        .toList();
                case "requirement-lines:lock" -> requirementLineIds.stream()
                        .map(lineId -> row("id", lineId))
                        .toList();
                case "target-documents:read" -> targetDocumentIds.stream()
                        .map(id -> row("documento_version_id", id))
                        .toList();
                case "target-requirements:read" -> targetRequirementIds.stream()
                        .map(id -> row("requisito_version_id", id))
                        .toList();
                case "current-document-slots:read" -> currentSlots;
                case "current-required-sets:read" -> List.of(row(
                        "locale", LocaleLegal.ES_AR.getCodigo(),
                        "contexto", ContextoLegal.REGISTRO.name(),
                        "audiencia", AudienciaLegal.ADMIN_TITULAR.name(),
                        "conjunto_id", SOURCE_REQUIRED_SET_ID,
                        "publicacion_id", SOURCE_PUBLICATION_ID));
                case "target-required-set-scopes:read" -> List.of(row(
                        "id", TARGET_REQUIRED_SET_ID,
                        "publicacion_id", TARGET_PUBLICATION_ID,
                        "locale", LocaleLegal.ES_AR.getCodigo(),
                        "contexto", ContextoLegal.REGISTRO.name(),
                        "audiencia", AudienciaLegal.ADMIN_TITULAR.name(),
                        "required_set_revision", TARGET_REVISION));
                case "replacement-batches:absence-check" -> List.of();
                default -> throw new AssertionError("SELECT REPLACE inesperado: " + sql);
            };
        }

        @Override
        public int[] batchUpdate(String statement, List<Object[]> batchArguments) {
            String sql = normalized(statement);
            List<Object[]> copiedRows = batchArguments.stream()
                    .map(Object[]::clone)
                    .toList();
            dml.add(new DmlCall(dmlSignature(sql, copiedRows), sql, copiedRows));
            int[] counts = new int[copiedRows.size()];
            Arrays.fill(counts, 1);
            if (forcedBatchSqlToken != null && sql.contains(forcedBatchSqlToken)) {
                counts[0] = forcedBatchCount;
                forcedBatchSqlToken = null;
            }
            return counts;
        }

        @Override
        public int update(String statement, Object... arguments) {
            String sql = normalized(statement);
            List<Object[]> rows = List.<Object[]>of(arguments.clone());
            dml.add(new DmlCall(
                    dmlSignature(sql, rows),
                    sql,
                    rows));
            updateCallCount++;
            if (updateCallCount == failedUpdateCallNumber) {
                return 0;
            }
            return 1;
        }

        void omitTargetDocument(UUID documentId) {
            targetDocumentIds.remove(documentId);
        }

        void failNextBatchContaining(String token) {
            forceNextBatchCountContaining(token, 0);
        }

        void returnSuccessNoInfoForNextBatchContaining(String token) {
            forceNextBatchCountContaining(token, Statement.SUCCESS_NO_INFO);
        }

        void failUpdateNumber(int updateNumber) {
            failedUpdateCallNumber = updateNumber;
        }

        private void forceNextBatchCountContaining(String token, int count) {
            forcedBatchSqlToken = Objects.requireNonNull(token, "token");
            forcedBatchCount = count;
        }

        List<String> querySignatures() {
            return queries.stream().map(QueryCall::signature).toList();
        }

        List<List<Object>> queryArguments() {
            return queries.stream().map(QueryCall::arguments).toList();
        }

        List<UUID> sortedDocumentIds() {
            return documentRows.stream().map(row -> (UUID) row.get("id")).toList();
        }

        List<UUID> sortedRequirementIds() {
            return requirementRows.stream().map(row -> (UUID) row.get("id")).toList();
        }

        List<DmlCall> dmlCalls() {
            return List.copyOf(dml);
        }

        List<String> dmlSignatures() {
            return dml.stream().map(DmlCall::signature).toList();
        }

        List<String> normalizedDml() {
            return dml.stream().map(DmlCall::sql).toList();
        }

        List<Object> allDmlArguments() {
            return dml.stream()
                    .flatMap(call -> call.rows().stream())
                    .flatMap(Arrays::stream)
                    .toList();
        }

        private String querySignature(String sql) {
            if (sql.contains("from legal_documento_versiones")) {
                return sql.endsWith("for update")
                        ? "document-versions:lock"
                        : "document-versions:read";
            }
            if (sql.contains("from legal_requisito_versiones")) {
                return sql.endsWith("for update")
                        ? "requirement-versions:lock"
                        : "requirement-versions:read";
            }
            if (sql.contains("from legal_publicaciones")) {
                return "publications:lock";
            }
            if (sql.contains("from legal_documento_lineas")) {
                return "document-lines:lock";
            }
            if (sql.contains("from legal_requisito_lineas")) {
                return "requirement-lines:lock";
            }
            if (sql.contains("from legal_publicacion_documentos")) {
                return "target-documents:read";
            }
            if (sql.contains("from legal_publicacion_requisitos")) {
                return "target-requirements:read";
            }
            if (sql.contains("from legal_documento_vigentes")) {
                return "current-document-slots:read";
            }
            if (sql.contains("from legal_requisito_conjuntos_actuales")) {
                return "current-required-sets:read";
            }
            if (sql.contains("from legal_requisito_conjuntos")) {
                return "target-required-set-scopes:read";
            }
            if (sql.contains("from legal_documento_reemplazo_lotes")) {
                return "replacement-batches:absence-check";
            }
            return "unknown";
        }

        private static String dmlSignature(String sql, List<Object[]> rows) {
            if (sql.startsWith("insert into legal_documento_transiciones")) {
                return "document-transition:" + rows.getFirst()[1] + "->"
                        + rows.getFirst()[2];
            }
            if (sql.startsWith("insert into legal_requisito_transiciones")) {
                return "requirement-transition:" + rows.getFirst()[1] + "->"
                        + rows.getFirst()[2];
            }
            if (sql.startsWith("delete from legal_requisito_conjuntos_actuales")) {
                return "required-set-pointer:delete";
            }
            if (sql.startsWith("insert into legal_requisito_conjuntos_actuales")) {
                return "required-set-pointer:insert";
            }
            if (sql.startsWith("delete from legal_documento_vigentes")) {
                return "document-slot:delete";
            }
            if (sql.startsWith("insert into legal_documento_vigentes")) {
                return "document-slot:insert";
            }
            if (sql.startsWith("insert into legal_documento_reemplazo_lotes")) {
                return "replacement-batch:insert";
            }
            if (sql.startsWith("insert into legal_documento_reemplazo_anteriores")) {
                return "replacement-predecessor:insert";
            }
            if (sql.startsWith("insert into legal_documento_reemplazo_sucesoras")) {
                return "replacement-successor:insert";
            }
            if (sql.startsWith("update legal_documento_reemplazo_lotes")) {
                return "replacement-batch:seal";
            }
            throw new AssertionError("DML REPLACE inesperado: " + sql);
        }

        private Map<String, Object> documentRow(
                LegalEditorialExecutionPlan.ExpectedDocumentState state,
                List<LegalEditorialExecutionPlan.DocumentTransition> preexisting) {
            UUID documentId = state.documentVersionId();
            boolean draft = documentId.equals(ADDITION_DOCUMENT_ID)
                    || documentId.equals(SUCCESSOR_DOCUMENT_ID);
            LegalEditorialExecutionPlan.DocumentTransition last = lastDocumentTransition(
                    documentId,
                    preexisting);
            return row(
                    "id", documentId,
                    "line_id", documentLineId(documentId),
                    "publicacion_intro_id", draft
                            ? TARGET_PUBLICATION_ID
                            : SOURCE_PUBLICATION_ID,
                    "estado", draft
                            ? EstadoVersionLegal.BORRADOR.name()
                            : EstadoVersionLegal.VIGENTE.name(),
                    "estado_cambiado_en", draft ? null : Timestamp.from(last.occurredAt()),
                    "ultimo_motivo", draft ? null : last.reason(),
                    "reemplazo_lote_id", draft ? null : last.replacementBatchId());
        }

        private Map<String, Object> requirementRow(
                LegalEditorialExecutionPlan.ExpectedRequirementState state,
                List<LegalEditorialExecutionPlan.RequirementTransition> preexisting) {
            UUID requirementId = state.requirementVersionId();
            boolean draft = requirementId.equals(ADDITION_REQUIREMENT_ID);
            LegalEditorialExecutionPlan.RequirementTransition last =
                    lastRequirementTransition(requirementId, preexisting);
            return row(
                    "id", requirementId,
                    "line_id", requirementLineId(requirementId),
                    "publicacion_intro_id", draft
                            ? TARGET_PUBLICATION_ID
                            : SOURCE_PUBLICATION_ID,
                    "estado", draft
                            ? EstadoVersionLegal.BORRADOR.name()
                            : EstadoVersionLegal.VIGENTE.name(),
                    "estado_cambiado_en", draft ? null : Timestamp.from(last.occurredAt()),
                    "ultimo_motivo", draft ? null : last.reason());
        }

        private static LegalEditorialExecutionPlan.DocumentTransition lastDocumentTransition(
                UUID documentId,
                List<LegalEditorialExecutionPlan.DocumentTransition> transitions) {
            return transitions.stream()
                    .filter(transition -> transition.documentVersionId().equals(documentId))
                    .reduce((left, right) -> right)
                    .orElse(null);
        }

        private static LegalEditorialExecutionPlan.RequirementTransition
                lastRequirementTransition(
                        UUID requirementId,
                        List<LegalEditorialExecutionPlan.RequirementTransition> transitions) {
            return transitions.stream()
                    .filter(transition -> transition.requirementVersionId()
                            .equals(requirementId))
                    .reduce((left, right) -> right)
                    .orElse(null);
        }

        private static UUID documentLineId(UUID documentId) {
            if (documentId.equals(ADDITION_DOCUMENT_ID)) {
                return ADDITION_DOCUMENT_LINE_ID;
            }
            if (documentId.equals(REUSED_DOCUMENT_ID)) {
                return REUSED_DOCUMENT_LINE_ID;
            }
            if (documentId.equals(PREDECESSOR_DOCUMENT_ID)) {
                return PREDECESSOR_DOCUMENT_LINE_ID;
            }
            if (documentId.equals(SUCCESSOR_DOCUMENT_ID)) {
                return SUCCESSOR_DOCUMENT_LINE_ID;
            }
            if (documentId.equals(RETIRED_DOCUMENT_ID)) {
                return RETIRED_DOCUMENT_LINE_ID;
            }
            throw new AssertionError("Documento de fixture desconocido: " + documentId);
        }

        private static UUID requirementLineId(UUID requirementId) {
            if (requirementId.equals(ADDITION_REQUIREMENT_ID)) {
                return ADDITION_REQUIREMENT_LINE_ID;
            }
            if (requirementId.equals(REPLACED_REQUIREMENT_ID)) {
                return REPLACED_REQUIREMENT_LINE_ID;
            }
            if (requirementId.equals(RETIRED_REQUIREMENT_ID)) {
                return RETIRED_REQUIREMENT_LINE_ID;
            }
            throw new AssertionError("Requisito de fixture desconocido: " + requirementId);
        }

        private static Map<String, Object> publicationRow(
                LegalEditorialExecutionPlan.PublicationIdentity publication) {
            return row(
                    "id", publication.publicationUuid(),
                    "publication_external_id", publication.publicationExternalId(),
                    "manifest_sha256", publication.manifestSha256(),
                    "estado_construccion", "SELLADO");
        }

        private static Map<String, Object> slotRow(
                LegalEditorialExecutionPlan.DocumentSlotKey key,
                UUID documentId,
                UUID lineId) {
            return row(
                    "tipo", key.type().name(),
                    "locale", key.locale().getCodigo(),
                    "contexto", key.context().name(),
                    "documento_version_id", documentId,
                    "documento_linea_id", lineId,
                    "publicacion_id", SOURCE_PUBLICATION_ID);
        }

        private static Map<String, Object> row(Object... values) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 0; index < values.length; index += 2) {
                row.put((String) values[index], values[index + 1]);
            }
            return row;
        }

        private static String normalized(String statement) {
            return statement.strip()
                    .replaceAll("\\s+", " ")
                    .toLowerCase(Locale.ROOT);
        }
    }
}
