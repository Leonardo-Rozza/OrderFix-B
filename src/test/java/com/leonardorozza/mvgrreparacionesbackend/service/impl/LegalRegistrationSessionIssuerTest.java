package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationBudget;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationSessionResources;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationSessionUnavailableException;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** TransactionTemplate is real; manager, policy and resource scope are explicit unit doubles. */
class LegalRegistrationSessionIssuerTest {
    private static final Long USER_ID = 41L;
    private static final Long TALLER_ID = 7L;
    private static final String PASSWORD = "  synthetic original password \t";

    @Test
    void oneTransactionAndTheUnannotatedCoreStayInsideTheResourceScope() {
        var f = new Fixture();

        assertThat(f.issue()).isSameAs(f.response);

        assertThat(f.events).containsExactly("scope-enter", "begin", "core", "commit", "scope-exit");
        verify(f.resources).withinRegistrationBudget(eq(f.owner), any());
        verify(f.policy).issueInCurrentTransaction(eq(USER_ID), eq(TALLER_ID), eq(PASSWORD), any());
        verify(f.policy, never()).issueSession(any(), any(), any());
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(f.manager).getTransaction(definition.capture());
        assertTransaction(definition.getValue(), 30);
        verify(f.manager).commit(f.status);
        verify(f.manager, never()).rollback(any());
    }

    @Test
    void eachCallCreatesItsOwnDefinitionUsingTheSameOwnersCeilingRemainder() {
        var f = new Fixture();
        f.issue();
        f.clock.set(28_001_000_000L); // 1999 ms left: Spring's integer timeout must be 2 s.
        f.issue();

        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(f.manager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues().get(0)).isNotSameAs(definitions.getAllValues().get(1));
        assertTransaction(definitions.getAllValues().get(0), 30);
        assertTransaction(definitions.getAllValues().get(1), 2);
        verify(f.resources, times(2)).withinRegistrationBudget(eq(f.owner), any());
        verify(f.manager, never()).setDefaultTimeout(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void anAlreadyExpiredOwnerFailsBeforeRequestingATransaction() {
        var f = new Fixture();
        f.clock.set(30_000_000_000L);

        assertThatThrownBy(f::issue).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);

        verifyNoInteractions(f.manager, f.policy);
        assertThat(f.events).containsExactly("scope-enter", "scope-exit");
    }

    @Test
    void theSuppliedCheckpointObservesTheOwnersConsumptionInsideTheCore() {
        var f = new Fixture();
        f.core = checkpoint -> {
            f.clock.set(30_000_000_000L);
            checkpoint.run();
            throw new AssertionError("An expired checkpoint must not authorize a core result");
        };

        assertThatThrownBy(f::issue).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);

        assertThat(f.events).containsExactly("scope-enter", "begin", "core", "rollback", "scope-exit");
        verify(f.manager, never()).commit(any());
    }

    @Test
    void healthyBadCredentialsKeepTheirIdentityAfterRollbackAndScopeExit() {
        var f = new Fixture();
        var rejected = new BadCredentialsException("Usuario o contraseña incorrectos");
        f.core = checkpoint -> { checkpoint.run(); throw rejected; };

        assertThatThrownBy(f::issue).isSameAs(rejected);

        assertThat(f.events).containsExactly("scope-enter", "begin", "core", "rollback", "scope-exit");
        verify(f.manager, never()).commit(any());
    }

    @Test
    void anOperationalCoreFailureIsUnavailableAfterRollbackWithoutCallingTheAnnotatedPolicy() {
        var f = new Fixture();
        var primary = new IllegalStateException("synthetic operation failure");
        f.core = checkpoint -> { throw primary; };

        assertThatThrownBy(f::issue).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class)
                .hasMessage("La sesión de registro no está disponible").hasCause(primary);

        verify(f.manager).rollback(f.status);
        verify(f.manager, never()).commit(any());
        verify(f.policy, never()).issueSession(any(), any(), any());
    }

    @Test
    void aTypedCoreFailureKeepsItsIdentity() {
        var f = new Fixture();
        var primary = new LegalRegistrationSessionUnavailableException();
        f.core = checkpoint -> { throw primary; };

        assertThatThrownBy(f::issue).isSameAs(primary);

        verify(f.manager).rollback(f.status);
    }

    @Test
    void anErrorKeepsItsIdentityAndStillPassesThroughTransactionRollback() {
        var f = new Fixture();
        var primary = new AssertionError("synthetic fatal operation");
        f.core = checkpoint -> { throw primary; };

        assertThatThrownBy(f::issue).isSameAs(primary);

        assertThat(f.events).containsExactly("scope-enter", "begin", "core", "rollback", "scope-exit");
    }

    @Test
    void aResourceRejectionPreventsBothTheTransactionAndTheCore() {
        var f = new Fixture();
        var primary = new LegalRegistrationSessionUnavailableException();
        f.scope = (owner, work) -> { throw primary; };

        assertThatThrownBy(f::issue).isSameAs(primary);

        verifyNoInteractions(f.manager, f.policy);
    }

    @Test
    void aBeginFailureCannotRunTheCoreOrClaimCommit() {
        var f = new Fixture();
        var primary = new IllegalStateException("synthetic transaction begin failure");
        when(f.manager.getTransaction(any())).thenThrow(primary);

        assertThatThrownBy(f::issue).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(primary);

        verifyNoInteractions(f.policy);
        verify(f.manager, never()).commit(any());
        verify(f.manager, never()).rollback(any());
    }

    @Test
    void aCommitFailureDiscardsTheComputedSessionWithoutInventingRollbackOrRegistrationState() {
        var f = new Fixture();
        var primary = new IllegalStateException("synthetic commit acknowledgement failure");
        doThrow(primary).when(f.manager).commit(f.status);

        assertThatThrownBy(f::issue).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(primary);

        verify(f.policy).issueInCurrentTransaction(eq(USER_ID), eq(TALLER_ID), eq(PASSWORD), any());
        verify(f.manager).commit(f.status);
        verify(f.manager, never()).rollback(any());
        assertThat(f.events.getLast()).isEqualTo("scope-exit");
    }

    @Test
    void aFailureReportedByTheScopeAfterCommitPreventsDelivery() {
        var f = new Fixture();
        var exitFailure = new LegalRegistrationSessionUnavailableException(new IllegalStateException("synthetic cleanup"));
        f.scope = (owner, work) -> {
            assertThat(work.apply(owner)).isSameAs(f.response);
            verify(f.manager).commit(f.status);
            throw exitFailure;
        };

        assertThatThrownBy(f::issue).isSameAs(exitFailure);

        verify(f.manager, never()).rollback(any());
    }

    @Test
    void aScopeFailureAfterRollbackDominatesAnEarlierCredentialRejection() {
        var f = new Fixture();
        var rejected = new BadCredentialsException("Usuario o contraseña incorrectos");
        var exitFailure = new LegalRegistrationSessionUnavailableException();
        f.core = checkpoint -> { throw rejected; };
        f.scope = (owner, work) -> {
            assertThatThrownBy(() -> work.apply(owner)).isSameAs(rejected);
            verify(f.manager).rollback(f.status);
            exitFailure.addSuppressed(rejected);
            throw exitFailure;
        };

        assertThatThrownBy(f::issue).isSameAs(exitFailure);

        assertThat(exitFailure.getSuppressed()).containsExactly(rejected);
        verify(f.manager, never()).commit(any());
    }

    @Test
    void aNullCoreResultIsUnavailableEvenWhenTheReadTransactionHasAlreadyCompleted() {
        var f = new Fixture();
        f.core = checkpoint -> null;

        assertThatThrownBy(f::issue).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);

        verify(f.manager).commit(f.status);
        verify(f.manager, never()).rollback(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"policy", "resources", "manager", "dataSource"})
    void rejectsNullConstructionDependencies(String missing) {
        var f = new Fixture();
        assertThatThrownBy(() -> new LegalRegistrationSessionIssuer(
                missing.equals("policy") ? null : f.policy,
                missing.equals("resources") ? null : f.resources,
                missing.equals("manager") ? null : f.manager,
                missing.equals("dataSource") ? null : f.source))
                .isInstanceOf(missing.equals("dataSource") ? IllegalArgumentException.class : NullPointerException.class);
        verifyNoInteractions(f.policy, f.resources);
    }

    @ParameterizedTest
    @ValueSource(strings = {"manager-null-source", "manager-other-source", "no-factory", "uninspectable-factory", "factory-null-source", "factory-other-source"})
    void rejectsAnyMissingOrMismatchedJpaDataSourceIdentityBeforeComposition(String defect) {
        var f = new Fixture();
        DataSource other = mock(DataSource.class);
        EntityManagerFactory uninspectable = mock(EntityManagerFactory.class);
        switch (defect) {
            case "manager-null-source" -> when(f.manager.getDataSource()).thenReturn(null);
            case "manager-other-source" -> when(f.manager.getDataSource()).thenReturn(other);
            case "no-factory" -> when(f.manager.getEntityManagerFactory()).thenReturn(null);
            case "uninspectable-factory" -> when(f.manager.getEntityManagerFactory()).thenReturn(uninspectable);
            case "factory-null-source" -> when(((EntityManagerFactoryInfo) f.factory).getDataSource()).thenReturn(null);
            case "factory-other-source" -> when(((EntityManagerFactoryInfo) f.factory).getDataSource()).thenReturn(other);
            default -> throw new AssertionError(defect);
        }

        assertThatThrownBy(() -> new LegalRegistrationSessionIssuer(f.policy, f.resources, f.manager, f.source))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("La sesión de registro requiere una única frontera JPA");

        verifyNoInteractions(f.policy, f.resources, other);
    }

    private static void assertTransaction(TransactionDefinition definition, int seconds) {
        assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(definition.isReadOnly()).isTrue();
        assertThat(definition.getTimeout()).isEqualTo(seconds);
    }

    private static final class Fixture {
        final AccountSessionPolicy policy = mock(AccountSessionPolicy.class);
        final LegalRegistrationSessionResources resources = mock(LegalRegistrationSessionResources.class);
        final JpaTransactionManager manager = mock(JpaTransactionManager.class);
        final DataSource source = mock(DataSource.class);
        final EntityManagerFactory factory = mock(EntityManagerFactory.class, withSettings().extraInterfaces(EntityManagerFactoryInfo.class));
        final SimpleTransactionStatus status = new SimpleTransactionStatus();
        final AtomicLong clock = new AtomicLong();
        final LegalRegistrationBudget owner = ReflectionTestUtils.invokeMethod(LegalRegistrationBudget.class, "start", (LongSupplier) clock::get);
        final AuthResponseDto response = new AuthResponseDto("synthetic-jwt", "Bearer", "current@example.test", false);
        final List<String> events = new ArrayList<>();
        Function<Runnable, AuthResponseDto> core = checkpoint -> { checkpoint.run(); return response; };
        BiFunction<LegalRegistrationBudget, Function<LegalRegistrationBudget, AuthResponseDto>, AuthResponseDto> scope = (budget, work) -> work.apply(budget);
        final LegalRegistrationSessionIssuer issuer;

        Fixture() {
            when(manager.getDataSource()).thenReturn(source);
            when(manager.getEntityManagerFactory()).thenReturn(factory);
            when(((EntityManagerFactoryInfo) factory).getDataSource()).thenReturn(source);
            issuer = new LegalRegistrationSessionIssuer(policy, resources, manager, source);
            when(manager.getTransaction(any())).thenAnswer(call -> { events.add("begin"); return status; });
            doAnswer(call -> { events.add("commit"); return null; }).when(manager).commit(status);
            doAnswer(call -> { events.add("rollback"); return null; }).when(manager).rollback(status);
            when(policy.issueInCurrentTransaction(eq(USER_ID), eq(TALLER_ID), eq(PASSWORD), any())).thenAnswer(call -> {
                events.add("core");
                return core.apply(call.getArgument(3));
            });
            when(resources.withinRegistrationBudget(any(), any())).thenAnswer(call -> {
                LegalRegistrationBudget budget = call.getArgument(0);
                Function<LegalRegistrationBudget, AuthResponseDto> work = call.getArgument(1);
                events.add("scope-enter");
                try { return scope.apply(budget, work); }
                finally { events.add("scope-exit"); }
            });
            clearInvocations(manager, factory, policy, resources);
        }

        AuthResponseDto issue() { return issuer.issueSession(USER_ID, TALLER_ID, PASSWORD, owner); }
    }
}
