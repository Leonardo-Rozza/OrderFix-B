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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalEditorialRetirementWriterTest {

    private static final UUID PUBLICATION_ID = id(1);
    private static final UUID RETIRED_DOCUMENT_ID = id(101);
    private static final UUID SURVIVING_DOCUMENT_ID = id(102);
    private static final UUID RETIRED_DOCUMENT_LINE_ID = id(201);
    private static final UUID SURVIVING_DOCUMENT_LINE_ID = id(202);
    private static final UUID RETIRED_REQUIREMENT_ID = id(301);
    private static final UUID SURVIVING_REQUIREMENT_ID = id(302);
    private static final UUID RETIRED_REQUIREMENT_LINE_ID = id(401);
    private static final UUID SURVIVING_REQUIREMENT_LINE_ID = id(402);
    private static final UUID DELETED_REQUIRED_SET_ID = id(501);
    private static final UUID SURVIVING_REQUIRED_SET_ID = id(502);

    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-28T10:00:00Z");
    private static final Instant LIVE_AT = Instant.parse("2026-08-29T10:00:00Z");
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-30T10:00:00Z");
    private static final String SOURCE_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String DELETED_REVISION = "sha256:" + "b".repeat(64);
    private static final String SURVIVING_REVISION = "sha256:" + "c".repeat(64);
    private static final String MANIFEST_SHA = "d".repeat(64);
    private static final String REASON = "Retiro preventivo documentado";

    private static final LegalEditorialExecutionPlan.DocumentSlotKey DELETED_SLOT =
            new LegalEditorialExecutionPlan.DocumentSlotKey(
                    TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                    LocaleLegal.ES_AR,
                    ContextoLegal.CIERRE_CUENTA);
    private static final LegalEditorialExecutionPlan.DocumentSlotKey SURVIVING_SLOT =
            new LegalEditorialExecutionPlan.DocumentSlotKey(
                    TipoDocumentoLegal.TERMINOS_SERVICIO,
                    LocaleLegal.ES_AR,
                    ContextoLegal.REGISTRO);
    private static final LegalEditorialExecutionPlan.RequiredSetPointerKey DELETED_POINTER =
            new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                    LocaleLegal.ES_AR,
                    ContextoLegal.REGISTRO,
                    AudienciaLegal.ADMIN_TITULAR);
    private static final LegalEditorialExecutionPlan.RequiredSetPointerKey SURVIVING_POINTER =
            new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                    LocaleLegal.ES_AR,
                    ContextoLegal.REGISTRO,
                    AudienciaLegal.USER);

    @Test
    void implementsTheSharedMutationBoundaryAndKeepsTheExactJdbcSession() {
        JdbcTemplate jdbc = new JdbcTemplate();
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);

        LegalEditorialMutationWriter writer = new LegalEditorialRetirementWriter(
                jdbc,
                readiness);

        assertThat(writer).isInstanceOf(LegalEditorialRetirementWriter.class);
        assertThat(writer.usesJdbc(jdbc)).isTrue();
        assertThat(writer.usesJdbc(new JdbcTemplate())).isFalse();
    }

    @Test
    void rejectsWrongExpectedReadinessBeforeTouchingJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.RETIRE);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.READY);

        LegalEditorialRetirementWriter writer = new LegalEditorialRetirementWriter(
                jdbc,
                readiness);

        assertThatThrownBy(() -> writer.write(plan))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure -> {
                    assertThat(failure.issue().code())
                            .isEqualTo(LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH);
                    assertThat(failure.issue().location())
                            .isEqualTo("editorialPlan/expectedReadinessAfter");
                });
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMissingFailClosedAcknowledgementBeforeTouchingJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.RETIRE);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.NOT_READY);
        when(plan.acknowledgeFailClosedGap()).thenReturn(false);

        LegalEditorialRetirementWriter writer = new LegalEditorialRetirementWriter(
                jdbc,
                readiness);

        assertThatThrownBy(() -> writer.write(plan))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure -> {
                    assertThat(failure.issue().code())
                            .isEqualTo(LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED);
                    assertThat(failure.issue().location())
                            .isEqualTo("editorialPlan/acknowledgeFailClosedGap");
                });
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsAProjectionInsertBeforeTouchingJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);
        LegalEditorialExecutionPlan valid = retirementPlan();
        LegalEditorialExecutionPlan.MutationCommands commands =
                mock(LegalEditorialExecutionPlan.MutationCommands.class);
        when(commands.documentTransitions()).thenReturn(
                valid.mutationCommands().documentTransitions());
        when(commands.requirementTransitions()).thenReturn(
                valid.mutationCommands().requirementTransitions());
        when(commands.documentSlotDeletes()).thenReturn(
                valid.mutationCommands().documentSlotDeletes());
        when(commands.requiredSetPointerDeletes()).thenReturn(
                valid.mutationCommands().requiredSetPointerDeletes());
        when(commands.documentSlotInserts()).thenReturn(
                valid.expectedPostState().documentSlots());
        when(commands.requiredSetPointerInserts()).thenReturn(List.of());
        when(commands.replacementBatchesToCreateAndSeal()).thenReturn(List.of());

        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.RETIRE);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.NOT_READY);
        when(plan.acknowledgeFailClosedGap()).thenReturn(true);
        when(plan.changeRequired()).thenReturn(true);
        when(plan.source()).thenReturn(valid.source());
        when(plan.target()).thenReturn(valid.target());
        when(plan.operationId()).thenReturn(valid.operationId());
        when(plan.planSha256()).thenReturn(valid.planSha256());
        when(plan.observedAt()).thenReturn(valid.observedAt());
        when(plan.expectedAppliedAt()).thenReturn(valid.expectedAppliedAt());
        when(plan.expectedPostState()).thenReturn(valid.expectedPostState());
        when(plan.mutationCommands()).thenReturn(commands);

        LegalEditorialRetirementWriter writer = new LegalEditorialRetirementWriter(
                jdbc,
                readiness);

        assertThatThrownBy(() -> writer.write(plan))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsASlotDeleteUnrelatedToTheExplicitRetirementBeforeTouchingJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);
        LegalEditorialExecutionPlan valid = retirementPlan();
        LegalEditorialExecutionPlan.MutationCommands commands = commandsLike(
                valid,
                List.of(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                        DELETED_SLOT,
                        SURVIVING_DOCUMENT_ID)),
                valid.mutationCommands().requiredSetPointerDeletes());

        LegalEditorialRetirementWriter writer = new LegalEditorialRetirementWriter(
                jdbc,
                readiness);

        assertThatThrownBy(() -> writer.write(planLike(valid, valid.expectedPostState(), commands)))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsASurvivingPointerThatDependsOnTheExplicitRetirementBeforeTouchingJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);
        LegalEditorialExecutionPlan valid = retirementPlan();
        LegalEditorialExecutionPlan.ExpectedRequiredSetPointer survivor =
                valid.expectedPostState().requiredSetPointers().getFirst();
        LegalEditorialExecutionPlan.ExpectedPostState expected = expectedLike(
                valid,
                List.of(new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                        survivor.key(),
                        survivor.requiredSetId(),
                        survivor.publicationId(),
                        survivor.requiredSetRevision(),
                        survivor.updatedAt(),
                        Optional.of(new LegalEditorialExecutionPlan.RequiredSetDependencies(
                                List.of(SURVIVING_REQUIREMENT_ID),
                                List.of(RETIRED_DOCUMENT_ID))))));

        LegalEditorialRetirementWriter writer = new LegalEditorialRetirementWriter(
                jdbc,
                readiness);

        assertThatThrownBy(() -> writer.write(
                planLike(valid, expected, valid.mutationCommands())))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsADeletedPointerUnrelatedToTheExplicitRetirementBeforeTouchingJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);
        LegalEditorialExecutionPlan valid = retirementPlan();
        LegalEditorialExecutionPlan.RequiredSetPointerDelete deleted =
                valid.mutationCommands().requiredSetPointerDeletes().getFirst();
        LegalEditorialExecutionPlan.MutationCommands commands = commandsLike(
                valid,
                valid.mutationCommands().documentSlotDeletes(),
                List.of(new LegalEditorialExecutionPlan.RequiredSetPointerDelete(
                        deleted.key(),
                        deleted.expectedRequiredSetId(),
                        Optional.of(new LegalEditorialExecutionPlan.RequiredSetDependencies(
                                List.of(SURVIVING_REQUIREMENT_ID),
                                List.of(SURVIVING_DOCUMENT_ID))))));

        LegalEditorialRetirementWriter writer = new LegalEditorialRetirementWriter(
                jdbc,
                readiness);

        assertThatThrownBy(() -> writer.write(planLike(valid, valid.expectedPostState(), commands)))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID));
        verifyNoInteractions(jdbc);
    }

    @Test
    void locksAndRevalidatesTheWholeGraphBeforeTheExactRetirementDmlOrder() {
        Fixture fixture = fixture(SOURCE_FINGERPRINT, false, 1);

        fixture.writer().write(fixture.plan());

        assertThat(fixture.jdbc().querySignatures()).containsExactlyElementsOf(
                lockedGraphQueries());
        assertThat(fixture.jdbc().queryCall(
                "from legal_documento_versiones", "for update").args())
                .containsExactly(RETIRED_DOCUMENT_ID, SURVIVING_DOCUMENT_ID);
        assertThat(fixture.jdbc().queryCall(
                "from legal_requisito_versiones", "for update").args())
                .containsExactly(RETIRED_REQUIREMENT_ID, SURVIVING_REQUIREMENT_ID);
        assertThat(fixture.jdbc().queryCall(
                "select id from legal_documento_lineas").args())
                .containsExactly(RETIRED_DOCUMENT_LINE_ID, SURVIVING_DOCUMENT_LINE_ID);
        assertThat(fixture.jdbc().queryCall(
                "select id from legal_requisito_lineas").args())
                .containsExactly(RETIRED_REQUIREMENT_LINE_ID, SURVIVING_REQUIREMENT_LINE_ID);
        assertThat(fixture.jdbc().tableLocks()).containsExactly(
                "lock table legal_requisito_conjuntos_actuales, "
                        + "legal_documento_vigentes in share row exclusive mode");
        assertThat(fixture.jdbc().queryCall(
                "from legal_requisito_conjuntos_actuales a").sql())
                .doesNotContain("for update");
        assertThat(fixture.jdbc().queryCall(
                "from legal_documento_vigentes").sql())
                .doesNotContain("for update");
        assertThat(fixture.jdbc().dmlSignatures()).containsExactly(
                "required-set-pointer:delete",
                "document-slot:delete",
                "requirement-transition:insert",
                "document-transition:insert");
        assertThat(fixture.jdbc().dmlCalls().get(0).rows()).singleElement()
                .satisfies(row -> assertThat(row).containsExactly(
                        LocaleLegal.ES_AR.getCodigo(),
                        ContextoLegal.REGISTRO.name(),
                        AudienciaLegal.ADMIN_TITULAR.name(),
                        DELETED_REQUIRED_SET_ID,
                        PUBLICATION_ID));
        assertThat(fixture.jdbc().dmlCalls().get(1).rows()).singleElement()
                .satisfies(row -> assertThat(row).containsExactly(
                        TipoDocumentoLegal.POLITICA_PRIVACIDAD.name(),
                        LocaleLegal.ES_AR.getCodigo(),
                        ContextoLegal.CIERRE_CUENTA.name(),
                        RETIRED_DOCUMENT_ID,
                        RETIRED_DOCUMENT_LINE_ID,
                        PUBLICATION_ID));
        assertThat(fixture.jdbc().dmlCalls().get(2).rows()).singleElement()
                .satisfies(row -> assertThat(row).containsExactly(
                        RETIRED_REQUIREMENT_ID,
                        REASON,
                        Timestamp.from(OBSERVED_AT)));
        assertThat(fixture.jdbc().dmlCalls().get(3).rows()).singleElement()
                .satisfies(row -> assertThat(row).containsExactly(
                        RETIRED_DOCUMENT_ID,
                        REASON,
                        Timestamp.from(OBSERVED_AT)));
        assertThat(fixture.jdbc().normalizedDml()).allSatisfy(sql -> {
            assertThat(sql).doesNotContain("insert into legal_documento_vigentes");
            assertThat(sql).doesNotContain("insert into legal_requisito_conjuntos_actuales");
            assertThat(sql).doesNotContain("legal_documento_reemplazo_lotes");
        });
        assertThat(fixture.jdbc().normalizedDml().get(0))
                .contains("conjunto_id = ? and publicacion_id = ?");
        assertThat(fixture.jdbc().normalizedDml().get(1))
                .contains("documento_linea_id = ?")
                .contains("publicacion_id = ?")
                .contains("estado_documento = 'vigente'");
    }

    @Test
    void blocksDependencyDriftBeforeTheFirstDml() {
        Fixture fixture = fixture(SOURCE_FINGERPRINT, true, 1);

        assertThatThrownBy(() -> fixture.writer().write(fixture.plan()))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.CURRENT_STATE_MISMATCH));
        assertThat(fixture.jdbc().dmlCalls()).isEmpty();
    }

    @Test
    void blocksSourceFingerprintDriftAfterAllLocksAndBeforeTheFirstDml() {
        Fixture fixture = fixture("sha256:" + "e".repeat(64), false, 1);

        assertThatThrownBy(() -> fixture.writer().write(fixture.plan()))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH));
        assertThat(fixture.jdbc().dmlCalls()).isEmpty();
    }

    @Test
    void rejectsADeleteWithoutAnExactCasCountAndDoesNotContinue() {
        Fixture fixture = fixture(SOURCE_FINGERPRINT, false, 0);

        assertThatThrownBy(() -> fixture.writer().write(fixture.plan()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El DML RETIRE no afecto exactamente una fila esperada");
        assertThat(fixture.jdbc().dmlSignatures())
                .containsExactly("required-set-pointer:delete");
    }

    @Test
    void rejectsSuccessNoInfoForADeleteAndDoesNotContinue() {
        Fixture fixture = fixture(SOURCE_FINGERPRINT, false, Statement.SUCCESS_NO_INFO);

        assertThatThrownBy(() -> fixture.writer().write(fixture.plan()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El DML RETIRE no afecto exactamente una fila esperada");
        assertThat(fixture.jdbc().dmlSignatures())
                .containsExactly("required-set-pointer:delete");
    }

    @Test
    void acceptsSuccessNoInfoOnlyForTransitionInserts() {
        Fixture fixture = fixture(
                SOURCE_FINGERPRINT,
                false,
                Map.of(
                        "requirement-transition:insert", Statement.SUCCESS_NO_INFO,
                        "document-transition:insert", Statement.SUCCESS_NO_INFO));

        fixture.writer().write(fixture.plan());

        assertThat(fixture.jdbc().dmlSignatures()).containsExactly(
                "required-set-pointer:delete",
                "document-slot:delete",
                "requirement-transition:insert",
                "document-transition:insert");
    }

    private static Fixture fixture(
            String observedFingerprint,
            boolean dependencyDrift,
            int firstBatchCount) {
        return fixture(
                observedFingerprint,
                dependencyDrift,
                Map.of("required-set-pointer:delete", firstBatchCount));
    }

    private static Fixture fixture(
            String observedFingerprint,
            boolean dependencyDrift,
            Map<String, Integer> batchCounts) {
        LegalEditorialExecutionPlan plan = retirementPlan();
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(
                dependencyDrift,
                batchCounts);
        LegalEditorialReadinessCore readiness = mock(LegalEditorialReadinessCore.class);
        when(readiness.usesJdbc(jdbc)).thenReturn(true);
        when(readiness.observeState("legal-current-v1", OBSERVED_AT)).thenAnswer(invocation -> {
            assertThat(jdbc.querySignatures()).containsExactlyElementsOf(lockedGraphQueries());
            assertThat(jdbc.dmlCalls()).isEmpty();
            return new LegalEditorialReadinessObservation(
                        Optional.of(PUBLICATION_ID),
                        OBSERVED_AT,
                        observedFingerprint,
                        2,
                        2,
                        5,
                        5,
                        2,
                        2,
                        0);
        });
        return new Fixture(
                plan,
                jdbc,
                readiness,
                new LegalEditorialRetirementWriter(jdbc, readiness));
    }

    private static LegalEditorialExecutionPlan planLike(
            LegalEditorialExecutionPlan valid,
            LegalEditorialExecutionPlan.ExpectedPostState expected,
            LegalEditorialExecutionPlan.MutationCommands commands) {
        LegalEditorialExecutionPlan plan = mock(LegalEditorialExecutionPlan.class);
        when(plan.operationType()).thenReturn(LegalEditorialExecutionPlan.OperationType.RETIRE);
        when(plan.expectedReadinessAfter()).thenReturn(LegalEditorialReadiness.NOT_READY);
        when(plan.acknowledgeFailClosedGap()).thenReturn(true);
        when(plan.changeRequired()).thenReturn(true);
        when(plan.source()).thenReturn(valid.source());
        when(plan.target()).thenReturn(valid.target());
        when(plan.operationId()).thenReturn(valid.operationId());
        when(plan.planSha256()).thenReturn(valid.planSha256());
        when(plan.observedAt()).thenReturn(valid.observedAt());
        when(plan.expectedAppliedAt()).thenReturn(valid.expectedAppliedAt());
        when(plan.expectedPostState()).thenReturn(expected);
        when(plan.mutationCommands()).thenReturn(commands);
        return plan;
    }

    private static LegalEditorialExecutionPlan.MutationCommands commandsLike(
            LegalEditorialExecutionPlan valid,
            List<LegalEditorialExecutionPlan.DocumentSlotDelete> slotDeletes,
            List<LegalEditorialExecutionPlan.RequiredSetPointerDelete> pointerDeletes) {
        LegalEditorialExecutionPlan.MutationCommands commands =
                mock(LegalEditorialExecutionPlan.MutationCommands.class);
        when(commands.documentTransitions()).thenReturn(
                valid.mutationCommands().documentTransitions());
        when(commands.requirementTransitions()).thenReturn(
                valid.mutationCommands().requirementTransitions());
        when(commands.documentSlotDeletes()).thenReturn(slotDeletes);
        when(commands.requiredSetPointerDeletes()).thenReturn(pointerDeletes);
        when(commands.documentSlotInserts()).thenReturn(List.of());
        when(commands.requiredSetPointerInserts()).thenReturn(List.of());
        when(commands.replacementBatchesToCreateAndSeal()).thenReturn(List.of());
        return commands;
    }

    private static LegalEditorialExecutionPlan.ExpectedPostState expectedLike(
            LegalEditorialExecutionPlan valid,
            List<LegalEditorialExecutionPlan.ExpectedRequiredSetPointer> pointers) {
        LegalEditorialExecutionPlan.ExpectedPostState expected =
                mock(LegalEditorialExecutionPlan.ExpectedPostState.class);
        when(expected.documentTransitions()).thenReturn(
                valid.expectedPostState().documentTransitions());
        when(expected.requirementTransitions()).thenReturn(
                valid.expectedPostState().requirementTransitions());
        when(expected.documentSlots()).thenReturn(valid.expectedPostState().documentSlots());
        when(expected.requiredSetPointers()).thenReturn(pointers);
        when(expected.replacementBatches()).thenReturn(List.of());
        when(expected.v27TriggerEffects()).thenReturn(
                LegalEditorialExecutionPlan.V27TriggerEffects.empty());
        return expected;
    }

    private static List<String> lockedGraphQueries() {
        return List.of(
                "document-versions:read",
                "requirement-versions:read",
                "publications:lock",
                "document-lines:lock",
                "requirement-lines:lock",
                "document-versions:lock",
                "requirement-versions:lock",
                "target-documents:read",
                "target-requirements:read",
                "current-projections:table-lock",
                "current-required-sets:read",
                "current-required-set-members:read",
                "current-required-set-documents:read",
                "current-document-slots:read");
    }

    private static LegalEditorialExecutionPlan retirementPlan() {
        List<LegalEditorialExecutionPlan.DocumentTransition> documentHistory = List.of(
                documentTransition(
                        RETIRED_DOCUMENT_ID,
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA,
                        null,
                        PUBLISHED_AT),
                documentTransition(
                        RETIRED_DOCUMENT_ID,
                        EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE,
                        null,
                        LIVE_AT),
                documentTransition(
                        SURVIVING_DOCUMENT_ID,
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA,
                        null,
                        PUBLISHED_AT),
                documentTransition(
                        SURVIVING_DOCUMENT_ID,
                        EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE,
                        null,
                        LIVE_AT));
        List<LegalEditorialExecutionPlan.RequirementTransition> requirementHistory = List.of(
                requirementTransition(
                        RETIRED_REQUIREMENT_ID,
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA,
                        null,
                        PUBLISHED_AT),
                requirementTransition(
                        RETIRED_REQUIREMENT_ID,
                        EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE,
                        null,
                        LIVE_AT),
                requirementTransition(
                        SURVIVING_REQUIREMENT_ID,
                        EstadoVersionLegal.BORRADOR,
                        EstadoVersionLegal.PUBLICADA,
                        null,
                        PUBLISHED_AT),
                requirementTransition(
                        SURVIVING_REQUIREMENT_ID,
                        EstadoVersionLegal.PUBLICADA,
                        EstadoVersionLegal.VIGENTE,
                        null,
                        LIVE_AT));
        LegalEditorialExecutionPlan.DocumentTransition retiredDocument = documentTransition(
                RETIRED_DOCUMENT_ID,
                EstadoVersionLegal.VIGENTE,
                EstadoVersionLegal.RETIRADA,
                REASON,
                OBSERVED_AT);
        LegalEditorialExecutionPlan.RequirementTransition retiredRequirement =
                requirementTransition(
                        RETIRED_REQUIREMENT_ID,
                        EstadoVersionLegal.VIGENTE,
                        EstadoVersionLegal.RETIRADA,
                        REASON,
                        OBSERVED_AT);
        LegalEditorialExecutionPlan.RequiredSetDependencies deletedDependencies =
                new LegalEditorialExecutionPlan.RequiredSetDependencies(
                        List.of(RETIRED_REQUIREMENT_ID),
                        List.of(RETIRED_DOCUMENT_ID));
        LegalEditorialExecutionPlan.RequiredSetDependencies survivingDependencies =
                new LegalEditorialExecutionPlan.RequiredSetDependencies(
                        List.of(SURVIVING_REQUIREMENT_ID),
                        List.of(SURVIVING_DOCUMENT_ID));
        LegalEditorialExecutionPlan.ExpectedPostState postState =
                new LegalEditorialExecutionPlan.ExpectedPostState(
                        List.of(
                                new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                        RETIRED_DOCUMENT_ID,
                                        EstadoVersionLegal.RETIRADA,
                                        OBSERVED_AT,
                                        REASON,
                                        null),
                                new LegalEditorialExecutionPlan.ExpectedDocumentState(
                                        SURVIVING_DOCUMENT_ID,
                                        EstadoVersionLegal.VIGENTE,
                                        LIVE_AT,
                                        null,
                                        null)),
                        List.of(
                                new LegalEditorialExecutionPlan.ExpectedRequirementState(
                                        RETIRED_REQUIREMENT_ID,
                                        EstadoVersionLegal.RETIRADA,
                                        OBSERVED_AT,
                                        REASON),
                                new LegalEditorialExecutionPlan.ExpectedRequirementState(
                                        SURVIVING_REQUIREMENT_ID,
                                        EstadoVersionLegal.VIGENTE,
                                        LIVE_AT,
                                        null)),
                        List.of(retiredDocument),
                        List.of(retiredRequirement),
                        documentHistory,
                        requirementHistory,
                        List.of(new LegalEditorialExecutionPlan.ExpectedDocumentSlot(
                                SURVIVING_SLOT,
                                SURVIVING_DOCUMENT_ID,
                                SURVIVING_DOCUMENT_LINE_ID,
                                PUBLICATION_ID)),
                        List.of(new LegalEditorialExecutionPlan.ExpectedRequiredSetPointer(
                                SURVIVING_POINTER,
                                SURVIVING_REQUIRED_SET_ID,
                                PUBLICATION_ID,
                                SURVIVING_REVISION,
                                LIVE_AT,
                                Optional.of(survivingDependencies))),
                        List.of(),
                        List.of(),
                        LegalEditorialExecutionPlan.V27TriggerEffects.empty());
        LegalEditorialExecutionPlan.MutationCommands commands =
                new LegalEditorialExecutionPlan.MutationCommands(
                        List.of(retiredDocument),
                        List.of(retiredRequirement),
                        List.of(new LegalEditorialExecutionPlan.DocumentSlotDelete(
                                DELETED_SLOT,
                                RETIRED_DOCUMENT_ID)),
                        List.of(),
                        List.of(new LegalEditorialExecutionPlan.RequiredSetPointerDelete(
                                DELETED_POINTER,
                                DELETED_REQUIRED_SET_ID,
                                Optional.of(deletedDependencies))),
                        List.of(),
                        List.of());
        LegalEditorialExecutionPlan.PublicationIdentity publication =
                new LegalEditorialExecutionPlan.PublicationIdentity(
                        "legal-current-v1",
                        PUBLICATION_ID,
                        MANIFEST_SHA);
        return new LegalEditorialExecutionPlan(
                LegalEditorialExecutionPlan.OperationType.RETIRE,
                Optional.of(new LegalEditorialExecutionPlan.SourceIdentity(
                        publication,
                        SOURCE_FINGERPRINT)),
                publication,
                Optional.of(id(900)),
                Optional.of("f".repeat(64)),
                OBSERVED_AT,
                OBSERVED_AT,
                LegalEditorialReadiness.NOT_READY,
                true,
                true,
                postState,
                commands);
    }

    private static LegalEditorialExecutionPlan.DocumentTransition documentTransition(
            UUID versionId,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            String reason,
            Instant occurredAt) {
        return new LegalEditorialExecutionPlan.DocumentTransition(
                versionId,
                previous,
                next,
                reason,
                null,
                occurredAt);
    }

    private static LegalEditorialExecutionPlan.RequirementTransition requirementTransition(
            UUID versionId,
            EstadoVersionLegal previous,
            EstadoVersionLegal next,
            String reason,
            Instant occurredAt) {
        return new LegalEditorialExecutionPlan.RequirementTransition(
                versionId,
                previous,
                next,
                reason,
                occurredAt);
    }

    private static UUID id(long value) {
        return UUID.fromString(String.format(
                Locale.ROOT,
                "00000000-0000-0000-0000-%012d",
                value));
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            row.put((String) pairs[index], pairs[index + 1]);
        }
        return row;
    }

    private record Fixture(
            LegalEditorialExecutionPlan plan,
            RecordingJdbcTemplate jdbc,
            LegalEditorialReadinessCore readiness,
            LegalEditorialRetirementWriter writer) {
    }

    private record DmlCall(String signature, String sql, List<Object[]> rows) {
    }

    private record QueryCall(String sql, List<Object> args) {
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private final boolean dependencyDrift;
        private final Map<String, Integer> batchCounts;
        private final List<String> querySignatures = new ArrayList<>();
        private final List<QueryCall> queryCalls = new ArrayList<>();
        private final List<String> tableLocks = new ArrayList<>();
        private final List<DmlCall> dmlCalls = new ArrayList<>();

        private RecordingJdbcTemplate(
                boolean dependencyDrift,
                Map<String, Integer> batchCounts) {
            this.dependencyDrift = dependencyDrift;
            this.batchCounts = Map.copyOf(batchCounts);
        }

        @Override
        public void execute(String sql) {
            String normalized = normalize(sql);
            if (!normalized.equals(
                    "lock table legal_requisito_conjuntos_actuales, "
                            + "legal_documento_vigentes in share row exclusive mode")) {
                throw new AssertionError("SQL ejecutado inesperado: " + normalized);
            }
            tableLocks.add(normalized);
            querySignatures.add("current-projections:table-lock");
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            return queryForList(sql, new Object[0]);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            String normalized = normalize(sql);
            queryCalls.add(new QueryCall(
                    normalized,
                    List.copyOf(Arrays.asList(args))));
            if (normalized.contains("from legal_documento_versiones")) {
                querySignatures.add(normalized.contains("for update")
                        ? "document-versions:lock"
                        : "document-versions:read");
                return List.of(
                        versionRow(
                                RETIRED_DOCUMENT_ID,
                                RETIRED_DOCUMENT_LINE_ID,
                                true),
                        versionRow(
                                SURVIVING_DOCUMENT_ID,
                                SURVIVING_DOCUMENT_LINE_ID,
                                true));
            }
            if (normalized.contains("from legal_requisito_versiones")) {
                querySignatures.add(normalized.contains("for update")
                        ? "requirement-versions:lock"
                        : "requirement-versions:read");
                return List.of(
                        versionRow(
                                RETIRED_REQUIREMENT_ID,
                                RETIRED_REQUIREMENT_LINE_ID,
                                false),
                        versionRow(
                                SURVIVING_REQUIREMENT_ID,
                                SURVIVING_REQUIREMENT_LINE_ID,
                                false));
            }
            if (normalized.contains("from legal_publicaciones")) {
                querySignatures.add("publications:lock");
                return List.of(row(
                        "id", PUBLICATION_ID,
                        "publication_external_id", "legal-current-v1",
                        "manifest_sha256", MANIFEST_SHA,
                        "estado_construccion", "SELLADO"));
            }
            if (normalized.startsWith("select id from legal_documento_lineas")) {
                querySignatures.add("document-lines:lock");
                return ids(args);
            }
            if (normalized.startsWith("select id from legal_requisito_lineas")) {
                querySignatures.add("requirement-lines:lock");
                return ids(args);
            }
            if (normalized.contains("from legal_publicacion_documentos")) {
                querySignatures.add("target-documents:read");
                return List.of(
                        row("documento_version_id", RETIRED_DOCUMENT_ID),
                        row("documento_version_id", SURVIVING_DOCUMENT_ID));
            }
            if (normalized.contains("from legal_publicacion_requisitos")) {
                querySignatures.add("target-requirements:read");
                return List.of(
                        row("requisito_version_id", RETIRED_REQUIREMENT_ID),
                        row("requisito_version_id", SURVIVING_REQUIREMENT_ID));
            }
            if (normalized.contains("join legal_requisito_documentos rd")) {
                querySignatures.add("current-required-set-documents:read");
                return List.of(
                        row(
                                "conjunto_id", DELETED_REQUIRED_SET_ID,
                                "documento_version_id", dependencyDrift
                                        ? SURVIVING_DOCUMENT_ID
                                        : RETIRED_DOCUMENT_ID),
                        row(
                                "conjunto_id", SURVIVING_REQUIRED_SET_ID,
                                "documento_version_id", SURVIVING_DOCUMENT_ID));
            }
            if (normalized.contains("join legal_requisito_conjunto_miembros m")) {
                querySignatures.add("current-required-set-members:read");
                return List.of(
                        row(
                                "conjunto_id", DELETED_REQUIRED_SET_ID,
                                "requisito_version_id", RETIRED_REQUIREMENT_ID),
                        row(
                                "conjunto_id", SURVIVING_REQUIRED_SET_ID,
                                "requisito_version_id", SURVIVING_REQUIREMENT_ID));
            }
            if (normalized.contains("from legal_requisito_conjuntos_actuales a")) {
                querySignatures.add("current-required-sets:read");
                return List.of(
                        pointerRow(
                                DELETED_POINTER,
                                DELETED_REQUIRED_SET_ID,
                                DELETED_REVISION),
                        pointerRow(
                                SURVIVING_POINTER,
                                SURVIVING_REQUIRED_SET_ID,
                                SURVIVING_REVISION));
            }
            if (normalized.contains("from legal_documento_vigentes")) {
                querySignatures.add("current-document-slots:read");
                return List.of(
                        slotRow(
                                DELETED_SLOT,
                                RETIRED_DOCUMENT_ID,
                                RETIRED_DOCUMENT_LINE_ID),
                        slotRow(
                                SURVIVING_SLOT,
                                SURVIVING_DOCUMENT_ID,
                                SURVIVING_DOCUMENT_LINE_ID));
            }
            throw new AssertionError("SQL SELECT inesperado: " + normalized);
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
            String normalized = normalize(sql);
            String signature;
            if (normalized.startsWith("delete from legal_requisito_conjuntos_actuales")) {
                signature = "required-set-pointer:delete";
            } else if (normalized.startsWith("delete from legal_documento_vigentes")) {
                signature = "document-slot:delete";
            } else if (normalized.startsWith("insert into legal_requisito_transiciones")) {
                signature = "requirement-transition:insert";
            } else if (normalized.startsWith("insert into legal_documento_transiciones")) {
                signature = "document-transition:insert";
            } else {
                throw new AssertionError("DML RETIRE inesperado: " + normalized);
            }
            dmlCalls.add(new DmlCall(signature, normalized, List.copyOf(batchArgs)));
            int count = batchCounts.getOrDefault(signature, 1);
            int[] counts = new int[batchArgs.size()];
            Arrays.fill(counts, count);
            return counts;
        }

        private static List<Map<String, Object>> ids(Object[] args) {
            return Arrays.stream(args).map(value -> row("id", value)).toList();
        }

        private static Map<String, Object> versionRow(
                UUID versionId,
                UUID lineId,
                boolean document) {
            Map<String, Object> row = row(
                    "id", versionId,
                    "line_id", lineId,
                    "publicacion_intro_id", PUBLICATION_ID,
                    "estado", EstadoVersionLegal.VIGENTE.name(),
                    "estado_cambiado_en", Timestamp.from(LIVE_AT),
                    "ultimo_motivo", null);
            if (document) {
                row.put("reemplazo_lote_id", null);
            }
            return row;
        }

        private static Map<String, Object> pointerRow(
                LegalEditorialExecutionPlan.RequiredSetPointerKey key,
                UUID setId,
                String revision) {
            return row(
                    "locale", key.locale().getCodigo(),
                    "contexto", key.context().name(),
                    "audiencia", key.audience().name(),
                    "conjunto_id", setId,
                    "publicacion_id", PUBLICATION_ID,
                    "actualizado_en", Timestamp.from(LIVE_AT),
                    "required_set_revision", revision);
        }

        private static Map<String, Object> slotRow(
                LegalEditorialExecutionPlan.DocumentSlotKey key,
                UUID versionId,
                UUID lineId) {
            return row(
                    "tipo", key.type().name(),
                    "locale", key.locale().getCodigo(),
                    "contexto", key.context().name(),
                    "documento_version_id", versionId,
                    "documento_linea_id", lineId,
                    "publicacion_id", PUBLICATION_ID);
        }

        private List<String> querySignatures() {
            return List.copyOf(querySignatures);
        }

        private List<DmlCall> dmlCalls() {
            return List.copyOf(dmlCalls);
        }

        private List<String> tableLocks() {
            return List.copyOf(tableLocks);
        }

        private QueryCall queryCall(String... fragments) {
            return queryCalls.stream()
                    .filter(call -> Arrays.stream(fragments)
                            .allMatch(call.sql()::contains))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "SELECT RETIRE no registrado: " + List.of(fragments)));
        }

        private List<String> dmlSignatures() {
            return dmlCalls.stream().map(DmlCall::signature).toList();
        }

        private List<String> normalizedDml() {
            return dmlCalls.stream().map(DmlCall::sql).toList();
        }

        private static String normalize(String sql) {
            return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        }
    }
}
