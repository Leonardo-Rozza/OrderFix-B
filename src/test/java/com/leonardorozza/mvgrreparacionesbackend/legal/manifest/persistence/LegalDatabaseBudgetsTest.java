package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalDatabaseBudgetsTest {

    @Test
    void freezesTheProductionBudgets() {
        LegalDatabaseBudgets budgets = LegalDatabaseBudgets.production();

        assertThat(budgets.transactionTimeoutSeconds()).isEqualTo(75);
        assertThat(budgets.statementTimeoutSeconds()).isEqualTo(30);
        assertThat(budgets.editorialLockTimeoutSeconds()).isEqualTo(30);
        assertThat(budgets.graphLockTimeoutSeconds()).isEqualTo(5);
    }

    @Test
    void acceptsOnlyPositiveTestBudgetsNotGreaterThanProduction() {
        assertThat(new LegalDatabaseBudgets(1, 1, 1, 1))
                .isEqualTo(new LegalDatabaseBudgets(1, 1, 1, 1));

        assertThatThrownBy(() -> new LegalDatabaseBudgets(0, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalDatabaseBudgets(76, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalDatabaseBudgets(1, 31, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalDatabaseBudgets(1, 1, 31, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalDatabaseBudgets(1, 1, 1, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
