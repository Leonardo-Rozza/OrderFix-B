package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalInitialPromotionCoreTest {

    private static final Instant APPLIED_AT = Instant.parse("2026-08-28T18:00:00.123456Z");
    private static final UUID PUBLICATION = uuid(1);
    private static final UUID FOREIGN_INTRODUCTION_PUBLICATION = uuid(2);
    private static final UUID DOCUMENT_A = uuid(10);
    private static final UUID DOCUMENT_B = uuid(11);
    private static final UUID DOCUMENT_LINE_A = uuid(20);
    private static final UUID DOCUMENT_LINE_B = uuid(21);
    private static final UUID REQUIREMENT = uuid(30);
    private static final UUID REQUIREMENT_LINE = uuid(31);
    private static final UUID REQUIRED_SET = uuid(40);

    @Test
    void locksInUuidOrderThenExecutesTheV27ProtocolAndRereadsTheReceipt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialExecutionPlan plan = promotePlan();
        stubLockedGraph(jdbc);
        List<String> batches = new ArrayList<>();
        List<List<Object[]>> rows = new ArrayList<>();
        doAnswer(invocation -> {
            batches.add(invocation.getArgument(0));
            List<Object[]> batchRows = invocation.getArgument(1);
            rows.add(batchRows);
            return java.util.stream.IntStream.range(0, batchRows.size()).map(ignored -> 1).toArray();
        }).when(jdbc).batchUpdate(anyString(), any(List.class));
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(receiptRow());

        LegalEditorialApplyReceipt receipt = new LegalInitialPromotionCore(jdbc).apply(plan);

        assertThat(receipt.operationType()).isEqualTo(LegalEditorialApplyReceipt.OperationType.PROMOTE);
        assertThat(receipt.targetPublicationUuid()).isEqualTo(PUBLICATION);
        assertThat(receipt.appliedAt()).isEqualTo(APPLIED_AT);
        assertThat(receipt.documentTransitions()).isEqualTo(4);
        assertThat(receipt.requirementTransitions()).isEqualTo(2);
        assertThat(batches).hasSize(6);
        assertThat(batches.get(0)).contains("legal_documento_transiciones");
        assertThat(batches.get(1)).contains("legal_requisito_transiciones");
        assertThat(batches.get(2)).contains("legal_documento_transiciones");
        assertThat(batches.get(3)).contains("legal_requisito_transiciones");
        assertThat(rows.get(0)).extracting(row -> row[0]).containsExactly(DOCUMENT_A, DOCUMENT_B);
        assertThat(rows.get(1)).extracting(row -> row[0]).containsExactly(REQUIREMENT);
        assertThat(rows.get(2)).extracting(row -> row[0]).containsExactly(DOCUMENT_A, DOCUMENT_B);
        assertThat(rows.get(3)).extracting(row -> row[0]).containsExactly(REQUIREMENT);

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_documento_versiones"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_requisito_versiones"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_publicaciones"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_documento_lineas"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_requisito_lineas"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_documento_versiones"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_requisito_versiones"),
                any(Object[].class));
        order.verify(jdbc, org.mockito.Mockito.times(6)).batchUpdate(anyString(), any(List.class));
        order.verify(jdbc).execute("SET CONSTRAINTS ALL IMMEDIATE");
        order.verify(jdbc).queryForMap(anyString(), any(Object[].class));
    }

    @Test
    void prelocksEveryIntroductionPublicationBeforeLinesAndVersions() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialExecutionPlan plan = promotePlan();
        stubLockedGraph(jdbc, FOREIGN_INTRODUCTION_PUBLICATION);
        when(jdbc.batchUpdate(anyString(), any(List.class))).thenAnswer(invocation -> {
            List<Object[]> rows = invocation.getArgument(1);
            return java.util.stream.IntStream.range(0, rows.size()).map(ignored -> 1).toArray();
        });
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(receiptRow());

        new LegalInitialPromotionCore(jdbc).apply(plan);

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_documento_versiones"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_requisito_versiones"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_publicaciones"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_documento_lineas"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_requisito_lineas"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_documento_versiones"),
                any(Object[].class));
        order.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_requisito_versiones"),
                any(Object[].class));
        verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FROM legal_publicaciones"),
                eq(PUBLICATION),
                eq(FOREIGN_INTRODUCTION_PUBLICATION));
    }

    @Test
    void rejectsReplayOrAnotherOperationBeforeTouchingJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.REPLACE);

        assertThatThrownBy(() -> new LegalInitialPromotionCore(jdbc).apply(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PROMOTE");
        verifyNoInteractions(jdbc);
    }

    @Test
    void failsClosedBeforeDmlWhenTheLockedPublicationIdentityDrifts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialExecutionPlan plan = promotePlan();
        stubLockedGraph(jdbc, PUBLICATION, "otra");

        assertThatThrownBy(() -> new LegalInitialPromotionCore(jdbc).apply(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no coincide");
        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void confirmsReplayUsingMembershipEvidenceWithoutLocksOrWrites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialExecutionPlan fresh = promotePlan();
        LegalEditorialExecutionPlan replay = mock(LegalEditorialExecutionPlan.class);
        LegalEditorialExecutionPlan.PublicationIdentity target = fresh.target();
        LegalEditorialExecutionPlan.ExpectedPostState expectedPostState = fresh.expectedPostState();
        when(replay.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.PROMOTE);
        when(replay.changeRequired()).thenReturn(false);
        when(replay.source()).thenReturn(Optional.empty());
        when(replay.operationId()).thenReturn(Optional.empty());
        when(replay.planSha256()).thenReturn(Optional.empty());
        when(replay.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        when(replay.acknowledgeFailClosedGap()).thenReturn(false);
        when(replay.mutationCommands()).thenReturn(LegalEditorialExecutionPlan.MutationCommands.empty());
        when(replay.target()).thenReturn(target);
        when(replay.expectedPostState()).thenReturn(expectedPostState);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(receiptRow());

        LegalEditorialApplyReceipt receipt =
                new LegalInitialPromotionCore(jdbc).confirmAlreadyApplied(replay);

        assertThat(receipt.targetPublicationUuid()).isEqualTo(PUBLICATION);
        verify(jdbc).queryForMap(
                org.mockito.ArgumentMatchers.contains("legal_publicacion_documentos"),
                any(Object[].class));
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void ownsNoTransactionGateClockOrDirectStateUpdate() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalInitialPromotionCore.java"));

        assertThat(source)
                .doesNotContain("TransactionTemplate", "LegalManifestDatabaseGate", "Clock.",
                        "transaction_timestamp()", "UPDATE legal_documento_versiones",
                        "UPDATE legal_requisito_versiones", "legal_validar_publicacion_sellada",
                        "publicacion_intro_id =")
                .contains("SET CONSTRAINTS ALL IMMEDIATE", "FOR UPDATE",
                        "v.publicacion_intro_id");
    }

    @SuppressWarnings("unchecked")
    private static LegalEditorialExecutionPlan promotePlan() {
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        LegalEditorialExecutionPlan.MutationCommands commands = mock(
                LegalEditorialExecutionPlan.MutationCommands.class);
        LegalEditorialExecutionPlan.ExpectedPostState expected = mock(
                LegalEditorialExecutionPlan.ExpectedPostState.class);
        LegalEditorialExecutionPlan.PublicationIdentity target =
                new LegalEditorialExecutionPlan.PublicationIdentity(
                        "publication-1", PUBLICATION, "a".repeat(64));
        List<LegalEditorialExecutionPlan.DocumentTransition> documents = List.of(
                doc(DOCUMENT_B, EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE),
                doc(DOCUMENT_A, EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA),
                doc(DOCUMENT_B, EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA),
                doc(DOCUMENT_A, EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE));
        List<LegalEditorialExecutionPlan.RequirementTransition> requirements = List.of(
                req(EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE),
                req(EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA));
        List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> slots = List.of(
                new LegalEditorialExecutionPlan.ExpectedDocumentSlot(
                        new LegalEditorialExecutionPlan.DocumentSlotKey(
                                TipoDocumentoLegal.TERMINOS_SERVICIO,
                                LocaleLegal.ES_AR,
                                ContextoLegal.REGISTRO),
                        DOCUMENT_A, DOCUMENT_LINE_A, PUBLICATION),
                new LegalEditorialExecutionPlan.ExpectedDocumentSlot(
                        new LegalEditorialExecutionPlan.DocumentSlotKey(
                                TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                                LocaleLegal.ES_AR,
                                ContextoLegal.USO_CONTINUADO),
                        DOCUMENT_B, DOCUMENT_LINE_B, PUBLICATION));
        List<LegalEditorialExecutionPlan.ExpectedRequiredSetPointer> pointers = List.of(
                new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                        new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                                LocaleLegal.ES_AR, ContextoLegal.REGISTRO,
                                AudienciaLegal.ADMIN_TITULAR),
                        REQUIRED_SET, PUBLICATION, "sha256:" + "b".repeat(64), APPLIED_AT));
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.PROMOTE);
        when(plan.changeRequired()).thenReturn(true);
        when(plan.source()).thenReturn(Optional.empty());
        when(plan.operationId()).thenReturn(Optional.empty());
        when(plan.planSha256()).thenReturn(Optional.empty());
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);
        when(plan.acknowledgeFailClosedGap()).thenReturn(false);
        when(plan.target()).thenReturn(target);
        when(plan.mutationCommands()).thenReturn(commands);
        when(plan.expectedPostState()).thenReturn(expected);
        when(commands.documentTransitions()).thenReturn(documents);
        when(commands.requirementTransitions()).thenReturn(requirements);
        when(commands.documentSlotDeletes()).thenReturn(List.of());
        when(commands.documentSlotInserts()).thenReturn(slots);
        when(commands.requiredSetPointerDeletes()).thenReturn(List.of());
        when(commands.requiredSetPointerInserts()).thenReturn(pointers);
        when(commands.replacementBatchesToCreateAndSeal()).thenReturn(List.of());
        when(expected.documentStates()).thenReturn(List.of(
                mock(LegalEditorialExecutionPlan.ExpectedDocumentState.class),
                mock(LegalEditorialExecutionPlan.ExpectedDocumentState.class)));
        when(expected.requirementStates()).thenReturn(List.of(
                mock(LegalEditorialExecutionPlan.ExpectedRequirementState.class)));
        when(expected.documentTransitions()).thenReturn(documents);
        when(expected.requirementTransitions()).thenReturn(requirements);
        when(expected.documentSlots()).thenReturn(slots);
        when(expected.requiredSetPointers()).thenReturn(pointers);
        when(expected.replacementBatches()).thenReturn(List.of());
        return plan;
    }

    private static void stubLockedGraph(JdbcTemplate jdbc) {
        stubLockedGraph(jdbc, PUBLICATION, "publication-1");
    }

    private static void stubLockedGraph(JdbcTemplate jdbc, UUID introductionPublicationId) {
        stubLockedGraph(jdbc, introductionPublicationId, "publication-1");
    }

    private static void stubLockedGraph(
            JdbcTemplate jdbc,
            UUID introductionPublicationId,
            String targetExternalId) {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM legal_publicaciones")) {
                List<Map<String, Object>> rows = new ArrayList<>();
                rows.add(Map.of(
                            "id", PUBLICATION,
                            "publication_external_id", targetExternalId,
                            "manifest_sha256", "a".repeat(64),
                            "estado_construccion", "SELLADO"));
                if (!introductionPublicationId.equals(PUBLICATION)) {
                    rows.add(Map.of(
                            "id", introductionPublicationId,
                            "publication_external_id", "publication-introduction",
                            "manifest_sha256", "b".repeat(64),
                            "estado_construccion", "SELLADO"));
                }
                return List.copyOf(rows);
            }
            if (sql.contains("FROM legal_documento_lineas")) {
                return List.of(Map.of("id", DOCUMENT_LINE_A), Map.of("id", DOCUMENT_LINE_B));
            }
            if (sql.contains("FROM legal_documento_versiones")) {
                return List.of(
                        Map.of("id", DOCUMENT_A, "line_id", DOCUMENT_LINE_A,
                                "publicacion_intro_id", introductionPublicationId,
                                "estado", "BORRADOR"),
                        Map.of("id", DOCUMENT_B, "line_id", DOCUMENT_LINE_B,
                                "publicacion_intro_id", introductionPublicationId,
                                "estado", "BORRADOR"));
            }
            if (sql.contains("FROM legal_requisito_lineas")) {
                return List.of(Map.of("id", REQUIREMENT_LINE));
            }
            if (sql.contains("FROM legal_requisito_versiones")) {
                return List.of(Map.of(
                        "id", REQUIREMENT,
                        "line_id", REQUIREMENT_LINE,
                        "publicacion_intro_id", introductionPublicationId,
                        "estado", "BORRADOR"));
            }
            throw new AssertionError("SQL inesperado: " + sql);
        }).when(jdbc).queryForList(anyString(), any(Object[].class));
    }

    private static Map<String, Object> receiptRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publication_id", PUBLICATION);
        row.put("applied_at", Timestamp.from(APPLIED_AT));
        row.put("document_versions", 2L);
        row.put("requirement_versions", 1L);
        row.put("document_transitions", 4L);
        row.put("requirement_transitions", 2L);
        row.put("document_slots", 2L);
        row.put("required_set_pointers", 1L);
        row.put("replacement_batches", 0L);
        return row;
    }

    private static LegalEditorialExecutionPlan.DocumentTransition doc(
            UUID id, EstadoVersionLegal previous, EstadoVersionLegal next) {
        return new LegalEditorialExecutionPlan.DocumentTransition(
                id, previous, next, null, null, APPLIED_AT);
    }

    private static LegalEditorialExecutionPlan.RequirementTransition req(
            EstadoVersionLegal previous, EstadoVersionLegal next) {
        return new LegalEditorialExecutionPlan.RequirementTransition(
                REQUIREMENT, previous, next, null, APPLIED_AT);
    }

    private static UUID uuid(long value) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
    }
}
