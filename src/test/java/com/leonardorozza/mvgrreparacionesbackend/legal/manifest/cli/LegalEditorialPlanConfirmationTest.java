package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalEditorialPlanV1.OperationType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalEditorialPlanConfirmationTest {

    private static final UUID OPERATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String SHA256 =
            "534ef5a63292484c4cde6fc2fa6735f7d42dbc40a3df671a6f9550626aebe49c";

    @Test
    void returnsTheSameOpaquePlanOnlyWhenTypeIdAndShaMatchExactly() {
        ValidatedEditorialPlan plan = plan(OperationType.REPLACE, OPERATION_ID, SHA256);
        LegalEditorialPlanConfirmation confirmation =
                new LegalEditorialPlanConfirmation(OPERATION_ID, SHA256);

        LegalManifestValidation<ValidatedEditorialPlan> result =
                confirmation.verify(OperationType.REPLACE, plan);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.value()).containsSame(plan);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void rejectsTypeOperationIdAndShaMismatchesWithOneSafeIssue() {
        LegalEditorialPlanConfirmation confirmation =
                new LegalEditorialPlanConfirmation(OPERATION_ID, SHA256);
        UUID anotherId = UUID.fromString("00000000-0000-0000-0000-000000000011");

        for (ValidatedEditorialPlan plan : java.util.List.of(
                plan(OperationType.RETIRE, OPERATION_ID, SHA256),
                plan(OperationType.REPLACE, anotherId, SHA256),
                plan(OperationType.REPLACE, OPERATION_ID, "a".repeat(64)))) {
            LegalManifestValidation<ValidatedEditorialPlan> result =
                    confirmation.verify(OperationType.REPLACE, plan);

            assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
            assertThat(result.value()).isEmpty();
            assertThat(result.issues()).singleElement().satisfies(issue -> {
                assertThat(issue.code())
                        .isEqualTo(LegalManifestIssueCode.EDITORIAL_CONFIRMATION_MISMATCH);
                assertThat(issue.location()).isEqualTo("cli/editorial/plan-confirmation");
            });
        }
    }

    @Test
    void constructorRejectsNullOrNonCanonicalHashAndDiagnosticIsRedacted() {
        assertThatThrownBy(() -> new LegalEditorialPlanConfirmation(null, SHA256))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalEditorialPlanConfirmation(OPERATION_ID, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalEditorialPlanConfirmation(
                OPERATION_ID,
                SHA256.toUpperCase()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new LegalEditorialPlanConfirmation(OPERATION_ID, SHA256).toString())
                .contains("redacted")
                .doesNotContain(OPERATION_ID.toString(), SHA256);
    }

    private static ValidatedEditorialPlan plan(
            OperationType type,
            UUID operationId,
            String sha256) {
        ValidatedEditorialPlan plan = mock(ValidatedEditorialPlan.class);
        when(plan.operationType()).thenReturn(type);
        when(plan.operationId()).thenReturn(operationId);
        when(plan.editorialPlanSha256()).thenReturn(sha256);
        return plan;
    }
}
