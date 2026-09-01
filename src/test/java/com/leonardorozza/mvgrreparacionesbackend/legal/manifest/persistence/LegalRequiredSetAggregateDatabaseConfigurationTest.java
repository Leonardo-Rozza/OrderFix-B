package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalRequiredSetAggregateDatabaseConfigurationTest {

    private final LegalRequiredSetAggregateDatabaseConfiguration configuration =
            new LegalRequiredSetAggregateDatabaseConfiguration();

    @Test
    void remainsExplicitConditionalAndNonScannable() {
        ConditionalOnProperty conditional = LegalRequiredSetAggregateDatabaseConfiguration.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(conditional).isNotNull();
        assertThat(conditional.name()).containsExactly(
                LegalRequiredSetAggregateDatabaseConfiguration.ENABLED_PROPERTY);
        assertThat(conditional.havingValue()).isEqualTo("true");
        assertThat(LegalRequiredSetAggregateDatabaseConfiguration.class
                .isAnnotationPresent(Configuration.class)).isFalse();
    }

    @Test
    void assemblesOneWritableCommitSafeBoundary() {
        DataSource dataSource = mock(DataSource.class);
        LegalDatabaseBudgets budgets = configuration.legalDatabaseBudgets();
        DataSourceTransactionManager manager =
                configuration.legalAggregateTransactionManager(dataSource);
        TransactionTemplate transaction = configuration.legalAggregateTransactionTemplate(
                manager,
                budgets);

        assertThat(manager.getDataSource()).isSameAs(dataSource);
        assertThat(manager.isRollbackOnCommitFailure()).isFalse();
        assertThat(transaction.getTransactionManager()).isSameAs(manager);
        assertThat(transaction.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transaction.getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(transaction.getTimeout()).isEqualTo(budgets.transactionTimeoutSeconds());
        assertThat(transaction.isReadOnly()).isFalse();
    }

    @Test
    void assemblesOrderedPreflightsAndOneJdbcGraph() {
        DataSource dataSource = mock(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LegalDatabaseBudgets budgets = configuration.legalDatabaseBudgets();
        DataSourceTransactionManager manager =
                configuration.legalAggregateTransactionManager(dataSource);
        TransactionTemplate transaction = configuration.legalAggregateTransactionTemplate(
                manager,
                budgets);
        LegalV28AggregateSchemaVerifier schema = mock(
                LegalV28AggregateSchemaVerifier.class);
        LegalV28AggregatePrivilegeVerifier privileges = mock(
                LegalV28AggregatePrivilegeVerifier.class);
        when(schema.usesJdbc(jdbc)).thenReturn(true);
        when(privileges.usesJdbc(jdbc)).thenReturn(true);
        LegalManifestDatabaseGate gate = configuration.legalAggregateDatabaseGate(
                transaction,
                jdbc,
                budgets,
                schema,
                privileges);
        LegalRequiredSetAggregateRevisionCalculator revision =
                configuration.legalRequiredSetAggregateRevisionCalculator();
        LegalRequiredSetAggregateProvenanceCalculator provenance =
                configuration.legalRequiredSetAggregateProvenanceCalculator();
        LegalRequiredSetAggregateReplayVerifier replay =
                configuration.legalRequiredSetAggregateReplayVerifier(
                        jdbc,
                        revision,
                        provenance);
        LegalRequiredSetAggregateStore store = configuration.legalRequiredSetAggregateStore(
                jdbc,
                revision,
                provenance,
                replay);

        assertThatCode(() -> gate.requireExactAggregatePreflights(
                jdbc,
                schema,
                privileges)).doesNotThrowAnyException();
        assertThat(gate.usesJdbc(jdbc)).isTrue();
        assertThat(replay.usesJdbc(jdbc)).isTrue();
        assertThat(store.usesJdbc(jdbc)).isTrue();
    }

    @Test
    void marksAndGuardsTheAggregateBoundary() {
        LegalDatabaseBoundaryMarker marker =
                configuration.legalAggregateDatabaseBoundaryMarker();

        assertThat(marker.kind()).isEqualTo(LegalDatabaseBoundaryMarker.Kind.AGGREGATE);
        assertThatCode(() -> configuration.legalAggregateDatabaseBoundaryGuard(
                List.of(marker))).doesNotThrowAnyException();
    }
}
