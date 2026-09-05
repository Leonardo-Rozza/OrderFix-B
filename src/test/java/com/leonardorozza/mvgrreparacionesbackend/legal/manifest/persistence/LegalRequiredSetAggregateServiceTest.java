package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalRequiredSetAggregateServiceTest {

    private static final LegalEditorialTimeBoundary BOUNDARY =
            new LegalEditorialTimeBoundary(
                    Instant.parse("2026-09-05T12:00:00.123456Z"),
                    Instant.parse("2026-09-05T12:00:01.123456Z"));

    @ParameterizedTest
    @CsvSource({
            "REGISTRATION, ADMIN_TITULAR, REGISTRO",
            "AUTHENTICATED_PENDING, ADMIN_TITULAR, USO_CONTINUADO",
            "AUTHENTICATED_PENDING, USER, USO_CONTINUADO"
    })
    void resolvesTheAuthoritativeProfileAndUsesOnlyTheSharedMutableGate(
            PerfilAgregadoLegal profile,
            AudienciaLegal audience,
            ContextoLegal context) {
        Harness harness = new Harness();
        LegalRequiredSetAggregateReceipt receipt = mock(LegalRequiredSetAggregateReceipt.class);
        when(harness.store.materialize(any(), same(BOUNDARY))).thenReturn(receipt);

        assertThat(harness.service(new LegalApplicableScopeResolver())
                .materialize(profile, LocaleLegal.ES_AR, audience)).isSameAs(receipt);

        ArgumentCaptor<LegalApplicableScopeSet> scopes =
                ArgumentCaptor.forClass(LegalApplicableScopeSet.class);
        verify(harness.store).materialize(scopes.capture(), same(BOUNDARY));
        assertThat(scopes.getValue().profile()).isEqualTo(profile);
        assertThat(scopes.getValue().locale()).isEqualTo(LocaleLegal.ES_AR);
        assertThat(scopes.getValue().audience()).isEqualTo(audience);
        assertThat(scopes.getValue().contexts()).containsExactly(context);
        verify(harness.gate, times(1)).executeMutableShared(any());
        verify(harness.gate, never()).executeMutable(any());
        verify(harness.gate, never()).executeReadOnly(any());
        verify(harness.gate, never()).execute(any());
        verifyNoInteractions(harness.jdbc);
    }

    @Test
    void passesTheExactOpaqueResolverValueAndGateOwnedBoundaryToTheStore() {
        Harness harness = new Harness();
        LegalApplicableScopeResolver resolver = mock(LegalApplicableScopeResolver.class);
        LegalApplicableScopeSet scopes = new LegalApplicableScopeResolver(
                (profile, audience) -> List.of(
                        ContextoLegal.ATESTACION_FOTOS,
                        ContextoLegal.USO_CONTINUADO))
                .resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                        LocaleLegal.ES_AR, AudienciaLegal.USER);
        when(resolver.resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, AudienciaLegal.USER)).thenReturn(scopes);
        LegalRequiredSetAggregateReceipt receipt = mock(LegalRequiredSetAggregateReceipt.class);
        when(harness.store.materialize(same(scopes), same(BOUNDARY))).thenReturn(receipt);
        LegalRequiredSetAggregateService service = harness.service(resolver);
        clearInvocations(harness.gate, harness.store);

        assertThat(materialize(service)).isSameAs(receipt);

        InOrder order = inOrder(resolver, harness.gate, harness.store);
        order.verify(resolver).resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, AudienciaLegal.USER);
        order.verify(harness.gate).executeMutableShared(any());
        order.verify(harness.store).materialize(same(scopes), same(BOUNDARY));
        assertThat(scopes.contexts()).containsExactly(
                ContextoLegal.USO_CONTINUADO, ContextoLegal.ATESTACION_FOTOS);
    }

    @Test
    void constructorAccreditsTheCommitBoundaryAndExactV28Preflights() {
        Harness harness = new Harness();

        harness.service(new LegalApplicableScopeResolver());

        verify(harness.gate).requireCommitOutcomeSafe();
        verify(harness.gate).requireExactAggregatePreflights(
                harness.jdbc, harness.schema, harness.privileges);
        verify(harness.gate).usesJdbc(harness.jdbc);
        verify(harness.store).usesJdbc(harness.jdbc);
    }

    @Test
    void invalidRegistrationAudienceFailsBeforeOpeningTheGate() {
        Harness harness = new Harness();
        LegalRequiredSetAggregateService service =
                harness.service(new LegalApplicableScopeResolver());
        clearInvocations(harness.gate, harness.store);

        assertThatThrownBy(() -> service.materialize(
                PerfilAgregadoLegal.REGISTRATION, LocaleLegal.ES_AR, AudienciaLegal.USER))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(harness.gate, harness.store, harness.jdbc);
    }

    @Test
    void resolverFailureEscapesWithoutOpeningTheGateOrRetrying() {
        Harness harness = new Harness();
        LegalApplicableScopeResolver resolver = mock(LegalApplicableScopeResolver.class);
        IllegalStateException failure = new IllegalStateException("invalid applicability policy");
        when(resolver.resolve(any(), any(), any())).thenThrow(failure);
        LegalRequiredSetAggregateService service = harness.service(resolver);
        clearInvocations(harness.gate, harness.store);

        assertThatThrownBy(() -> materialize(service)).isSameAs(failure);

        verify(resolver, times(1)).resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, AudienciaLegal.USER);
        verifyNoInteractions(harness.gate, harness.store, harness.jdbc);
    }

    @Test
    void nullAuthorityInputsFailBeforeOpeningTheGate() {
        Harness harness = new Harness();
        LegalRequiredSetAggregateService service =
                harness.service(new LegalApplicableScopeResolver());
        clearInvocations(harness.gate, harness.store);

        assertThatThrownBy(() -> service.materialize(
                null, LocaleLegal.ES_AR, AudienciaLegal.USER))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.materialize(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING, null, AudienciaLegal.USER))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.materialize(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING, LocaleLegal.ES_AR, null))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(harness.gate, harness.store, harness.jdbc);
    }

    @Test
    void storeFailureEscapesWithoutProducingAReceiptOrRetrying() {
        Harness harness = new Harness();
        IllegalStateException failure = new IllegalStateException("aggregate replay mismatch");
        when(harness.store.materialize(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> materialize(
                harness.service(new LegalApplicableScopeResolver()))).isSameAs(failure);

        verify(harness.gate, times(1)).executeMutableShared(any());
        verify(harness.store, times(1)).materialize(any(), same(BOUNDARY));
    }

    @Test
    void commitFailureEscapesEvenAfterTheStoreProducedAReceipt() {
        Harness harness = new Harness();
        LegalRequiredSetAggregateReceipt receipt = mock(LegalRequiredSetAggregateReceipt.class);
        when(harness.store.materialize(any(), any())).thenReturn(receipt);
        TransactionSystemException failure = new TransactionSystemException("commit failed");
        doAnswer(invocation -> {
            LegalManifestDatabaseGate.EditorialTransactionCallback<?> callback =
                    invocation.getArgument(0);
            assertThat(callback.doInTransaction(new SimpleTransactionStatus(), BOUNDARY))
                    .isSameAs(receipt);
            throw failure;
        }).when(harness.gate).executeMutableShared(any());

        assertThatThrownBy(() -> materialize(
                harness.service(new LegalApplicableScopeResolver()))).isSameAs(failure);

        verify(harness.gate, times(1)).executeMutableShared(any());
        verify(harness.store, times(1)).materialize(any(), same(BOUNDARY));
    }

    @Test
    void returnsTheReceiptOnlyAfterTheGateCompletesSuccessfully() {
        Harness harness = new Harness();
        AtomicBoolean completed = new AtomicBoolean();
        LegalRequiredSetAggregateReceipt receipt = mock(LegalRequiredSetAggregateReceipt.class);
        when(harness.store.materialize(any(), any())).thenAnswer(invocation -> {
            assertThat(completed).isFalse();
            return receipt;
        });
        doAnswer(invocation -> {
            LegalManifestDatabaseGate.EditorialTransactionCallback<?> callback =
                    invocation.getArgument(0);
            Object result = callback.doInTransaction(new SimpleTransactionStatus(), BOUNDARY);
            completed.set(true);
            return result;
        }).when(harness.gate).executeMutableShared(any());

        assertThat(materialize(harness.service(new LegalApplicableScopeResolver())))
                .isSameAs(receipt);
        assertThat(completed).isTrue();
    }

    @Test
    void nullStoreReceiptFailsInsideTheTransactionBeforeCompletion() {
        Harness harness = new Harness();
        AtomicBoolean completed = new AtomicBoolean();
        doAnswer(invocation -> {
            LegalManifestDatabaseGate.EditorialTransactionCallback<?> callback =
                    invocation.getArgument(0);
            Object result = callback.doInTransaction(new SimpleTransactionStatus(), BOUNDARY);
            completed.set(true);
            return result;
        }).when(harness.gate).executeMutableShared(any());

        assertThatThrownBy(() -> materialize(
                harness.service(new LegalApplicableScopeResolver())))
                .isInstanceOf(NullPointerException.class);

        assertThat(completed).isFalse();
        verify(harness.store, times(1)).materialize(any(), same(BOUNDARY));
    }

    @Test
    void nullGateResultCannotBeReturnedAsAReceipt() {
        Harness harness = new Harness();
        doReturn(null).when(harness.gate).executeMutableShared(any());

        assertThatThrownBy(() -> materialize(
                harness.service(new LegalApplicableScopeResolver())))
                .isInstanceOf(NullPointerException.class);

        verify(harness.gate, times(1)).executeMutableShared(any());
        verify(harness.store, never()).materialize(any(), any());
    }

    @Test
    void preflightFailureInTheRealGatePreventsLockStoreAndReceipt() {
        Composition composition = new Composition();
        LegalV28AggregateSchemaVerifier schema = mock(LegalV28AggregateSchemaVerifier.class);
        when(schema.usesJdbc(composition.jdbc)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("V28 schema drift");
        doThrow(failure).when(schema).verify();
        when(composition.jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_isolation')", String.class))
                .thenReturn("read committed");
        when(composition.jdbc.queryForObject(
                "SELECT pg_catalog.current_setting('transaction_read_only')", String.class))
                .thenReturn("off");
        TransactionTemplate transaction = executingTransaction(composition);
        LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                transaction, composition.jdbc, LegalDatabaseBudgets.production(),
                List.of(schema, composition.privileges));
        LegalRequiredSetAggregateService service = new LegalRequiredSetAggregateService(
                new LegalApplicableScopeResolver(), composition.jdbc, gate,
                composition.store, schema, composition.privileges);

        assertThatThrownBy(() -> materialize(service)).isSameAs(failure);

        verify(transaction, times(1)).execute(any());
        verify(schema, times(1)).verify();
        verify(composition.store, never()).materialize(any(), any());
        verify(composition.jdbc, never()).queryForList(
                any(String.class), org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void constructorRejectsDifferentJdbcInstancesEvenWhenTheDatasourceMatches() {
        Composition composition = new Composition();
        JdbcTemplate otherJdbc = new JdbcTemplate(composition.dataSource);
        LegalManifestDatabaseGate foreignGate = new LegalManifestDatabaseGate(
                composition.transaction, otherJdbc, LegalDatabaseBudgets.production(),
                List.of(composition.schema, composition.privileges));

        assertThatThrownBy(() -> composition.service(foreignGate))
                .isInstanceOf(IllegalArgumentException.class);

        when(composition.store.usesJdbc(composition.jdbc)).thenReturn(false);
        assertThatThrownBy(() -> composition.service(composition.gate()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(composition.store, never()).materialize(any(), any());
    }

    @Test
    void constructorRejectsMissingReorderedExtraOrForeignPreflights() {
        Composition composition = new Composition();
        LegalV28AggregateSchemaVerifier foreignSchema = new LegalV28AggregateSchemaVerifier(
                new JdbcTemplate(composition.dataSource), "public");
        List<List<LegalDatabasePreflight>> invalid = List.of(
                List.of(),
                List.of(composition.schema),
                List.of(composition.privileges, composition.schema),
                List.of(composition.schema, composition.privileges, composition.schema),
                List.of(foreignSchema, composition.privileges));

        for (List<LegalDatabasePreflight> preflights : invalid) {
            LegalManifestDatabaseGate gate = new LegalManifestDatabaseGate(
                    composition.transaction, composition.jdbc,
                    LegalDatabaseBudgets.production(), preflights);
            assertThatThrownBy(() -> composition.service(gate))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        LegalManifestDatabaseGate foreignSchemaGate = new LegalManifestDatabaseGate(
                composition.transaction, composition.jdbc, LegalDatabaseBudgets.production(),
                List.of(foreignSchema, composition.privileges));
        assertThatThrownBy(() -> new LegalRequiredSetAggregateService(
                new LegalApplicableScopeResolver(), composition.jdbc, foreignSchemaGate,
                composition.store, foreignSchema, composition.privileges))
                .isInstanceOf(IllegalArgumentException.class);
        verify(composition.store, never()).materialize(any(), any());
    }

    @Test
    void constructorRejectsUnsafeTransactionsBeforeDatabaseAccess() {
        Composition composition = new Composition();
        composition.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThatThrownBy(() -> composition.service(composition.gate()))
                .isInstanceOf(IllegalArgumentException.class);
        composition.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        composition.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> composition.service(composition.gate()))
                .isInstanceOf(IllegalArgumentException.class);
        composition.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        composition.transaction.setReadOnly(true);
        assertThatThrownBy(() -> composition.service(composition.gate()))
                .isInstanceOf(IllegalArgumentException.class);
        composition.transaction.setReadOnly(false);
        composition.transaction.setTransactionManager(
                new DataSourceTransactionManager(mock(DataSource.class)));
        assertThatThrownBy(() -> composition.service(composition.gate()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(composition.dataSource);
        verify(composition.store, never()).materialize(any(), any());
    }

    @Test
    void constructorRejectsEveryMissingCollaborator() {
        Harness harness = new Harness();
        LegalApplicableScopeResolver resolver = new LegalApplicableScopeResolver();
        assertThatThrownBy(() -> new LegalRequiredSetAggregateService(
                null, harness.jdbc, harness.gate, harness.store, harness.schema, harness.privileges))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalRequiredSetAggregateService(
                resolver, null, harness.gate, harness.store, harness.schema, harness.privileges))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalRequiredSetAggregateService(
                resolver, harness.jdbc, null, harness.store, harness.schema, harness.privileges))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalRequiredSetAggregateService(
                resolver, harness.jdbc, harness.gate, null, harness.schema, harness.privileges))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalRequiredSetAggregateService(
                resolver, harness.jdbc, harness.gate, harness.store, null, harness.privileges))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalRequiredSetAggregateService(
                resolver, harness.jdbc, harness.gate, harness.store, harness.schema, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void serviceRemainsAnInternalTypedEntryPointWithoutHttpAnnotations() throws Exception {
        assertThat(Modifier.isPublic(LegalRequiredSetAggregateService.class.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(LegalRequiredSetAggregateService.class.getModifiers())).isTrue();
        assertThat(LegalRequiredSetAggregateService.class.getDeclaredAnnotations()).isEmpty();
        assertThat(LegalRequiredSetAggregateService.class.getDeclaredConstructors()).hasSize(1);
        var method = LegalRequiredSetAggregateService.class.getDeclaredMethod(
                "materialize", PerfilAgregadoLegal.class, LocaleLegal.class, AudienciaLegal.class);
        assertThat(method.getReturnType()).isEqualTo(LegalRequiredSetAggregateReceipt.class);
        assertThat(method.getDeclaredAnnotations()).isEmpty();
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
    }

    private static LegalRequiredSetAggregateReceipt materialize(
            LegalRequiredSetAggregateService service) {
        return service.materialize(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR, AudienciaLegal.USER);
    }

    private static TransactionTemplate executingTransaction(Composition composition) {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.getTransactionManager()).thenReturn(
                composition.transaction.getTransactionManager());
        when(transaction.getPropagationBehavior())
                .thenReturn(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        when(transaction.getIsolationLevel())
                .thenReturn(TransactionDefinition.ISOLATION_READ_COMMITTED);
        when(transaction.getTimeout())
                .thenReturn(LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        return transaction;
    }

    private static final class Harness {
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
        private final LegalRequiredSetAggregateStore store = mock(LegalRequiredSetAggregateStore.class);
        private final LegalV28AggregateSchemaVerifier schema = mock(LegalV28AggregateSchemaVerifier.class);
        private final LegalV28AggregatePrivilegeVerifier privileges =
                mock(LegalV28AggregatePrivilegeVerifier.class);

        private Harness() {
            when(gate.usesJdbc(jdbc)).thenReturn(true);
            when(store.usesJdbc(jdbc)).thenReturn(true);
            when(gate.executeMutableShared(any())).thenAnswer(invocation -> {
                LegalManifestDatabaseGate.EditorialTransactionCallback<?> callback =
                        invocation.getArgument(0);
                return callback.doInTransaction(new SimpleTransactionStatus(), BOUNDARY);
            });
        }

        private LegalRequiredSetAggregateService service(LegalApplicableScopeResolver resolver) {
            return new LegalRequiredSetAggregateService(
                    resolver, jdbc, gate, store, schema, privileges);
        }
    }

    private static final class Composition {
        private final DataSource dataSource = mock(DataSource.class);
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        private final LegalV28AggregateSchemaVerifier schema =
                new LegalV28AggregateSchemaVerifier(jdbc, "public");
        private final LegalV28AggregatePrivilegeVerifier privileges =
                new LegalV28AggregatePrivilegeVerifier(jdbc, "ordenfix_aggregate", "public");
        private final LegalRequiredSetAggregateStore store = mock(LegalRequiredSetAggregateStore.class);

        private Composition() {
            when(jdbc.getDataSource()).thenReturn(dataSource);
            when(store.usesJdbc(jdbc)).thenReturn(true);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.setTimeout(LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        }

        private LegalManifestDatabaseGate gate() {
            return new LegalManifestDatabaseGate(transaction, jdbc, LegalDatabaseBudgets.production(),
                    List.of(schema, privileges));
        }

        private LegalRequiredSetAggregateService service(LegalManifestDatabaseGate gate) {
            return new LegalRequiredSetAggregateService(
                    new LegalApplicableScopeResolver(), jdbc, gate, store, schema, privileges);
        }
    }
}
