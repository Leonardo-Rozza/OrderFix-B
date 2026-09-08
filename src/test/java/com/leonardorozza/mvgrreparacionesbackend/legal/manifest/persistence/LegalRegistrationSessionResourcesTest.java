package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalRegistrationSessionResourcesTest {
    @Test
    void aManagedOwnerCannotRunBeforeInstallationOrBeforeTheMandatoryGate() throws Exception {
        try (var f = new Fixture()) {
            assertUnavailable(f.resources, f.budget);
            var router = f.resources.install(f.historical);
            assertUnavailable(f.resources, f.budget);
            assertThat(f.creations).hasValue(1);
            verifyNoInteractions(f.historical, f.pool, f.connection);
            f.resources.markReady();
            f.resources.markReady();
            assertThat(f.resources.<LegalRegistrationBudget>withinRegistrationBudget(f.budget, owner -> owner)).isSameAs(f.budget);
            assertThat(f.resources.install(f.historical)).isSameAs(router);
            assertThat(f.creations).hasValue(1);
        }
    }

    @Test
    void readyOwnerRoutesThroughItsInstalledProtectionAndClosesOnlyItsPrivateResources() throws Exception {
        var f = new Fixture();
        var router = f.resources.install(f.historical);
        f.resources.markReady();
        f.resources.withinRegistrationBudget(f.budget, owner -> {
            try (Connection connection = router.getConnection()) {
                assertThat(connection).isNotSameAs(f.connection);
                assertThat(owner).isSameAs(f.budget);
            } catch (SQLException failure) { throw new AssertionError(failure); }
            return null;
        });
        verify(f.pool).getConnection();
        verify(f.connection).close();
        verifyNoInteractions(f.historical);
        f.resources.close();
        f.resources.close();
        assertUnavailable(f.resources, f.budget);
        verify(f.pool, times(1)).close();
        verify(f.historical, never()).close();
    }

    @Test
    void aForeignInstallationCannotReplaceTheOriginalIdentityOrCreateAnotherPool() {
        try (var f = new Fixture()) {
            var other = mock(HikariDataSource.class);
            var installed = f.resources.install(f.historical);
            assertThatThrownBy(() -> f.resources.install(other)).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            assertThat(f.resources.install(f.historical)).isSameAs(installed);
            assertThat(f.creations).hasValue(1);
            verifyNoInteractions(other, f.historical, f.pool);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anInvalidFactoryResultNeverTransfersOrClosesHistoricalOwnership(boolean returnsHistorical) {
        var historical = mock(HikariDataSource.class);
        try (var resources = new LegalRegistrationSessionResources(original -> returnsHistorical ? original : null)) {
            assertThatThrownBy(() -> resources.install(historical)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(resources::markReady).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            assertUnavailable(resources, LegalRegistrationBudget.start(() -> 0L));
        }
        verifyNoInteractions(historical);
    }

    @Test
    void aFactoryFailureSurvivesTheDeferredGateAndCannotAuthorizeWorkOrRetry() {
        var original = mock(HikariDataSource.class);
        var primary = new IllegalArgumentException("synthetic configuration failure");
        var calls = new AtomicInteger();
        try (var resources = new LegalRegistrationSessionResources(source -> {
            calls.incrementAndGet();
            throw primary;
        })) {
            assertThatThrownBy(() -> resources.install(original)).isSameAs(primary);
            resources.recordFailure(primary);
            RuntimeException repeated = (RuntimeException) catchThrowable(() -> resources.install(original));
            resources.recordFailure(repeated);
            assertThat(primary.getSuppressed()).isEmpty();
            assertThatThrownBy(resources::markReady).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(primary);
            assertThatThrownBy(() -> resources.install(original)).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(primary);
            assertThatThrownBy(() -> resources.withinRegistrationBudget(LegalRegistrationBudget.start(() -> 0L), owner -> "unreachable"))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(primary);
            assertThat(calls).hasValue(1);
            assertThat(resources.toString()).isEqualTo("LegalRegistrationSessionResources[redacted]");
        }
        verifyNoInteractions(original);
    }

    @Test
    void aFailureAfterPoolCreationClosesThePartialPrivateResourceBeforeRethrowing() {
        try (var f = new Fixture(); var constructor = mockConstruction(LegalRegistrationSessionDataSource.class,
                (proxy, context) -> { throw new IllegalStateException("synthetic protection construction failure"); })) {
            Throwable primary = catchThrowable(() -> f.resources.install(f.historical));
            assertThat(primary).isNotNull();
            verify(f.pool).close();
            assertThatThrownBy(f.resources::markReady).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(primary);
            verifyNoInteractions(f.historical);
        }
    }

    @Test
    void recordingALaterCompositionFailureRetainsTheFirstCauseAndBlocksAPreviouslyReadyOwner() {
        try (var f = new Fixture()) {
            f.resources.install(f.historical);
            f.resources.markReady();
            var first = new IllegalArgumentException("synthetic first failure");
            var later = new IllegalStateException("synthetic later failure");
            f.resources.recordFailure(first);
            f.resources.recordFailure(first);
            f.resources.recordFailure(later);
            assertThatThrownBy(() -> f.resources.withinRegistrationBudget(f.budget, owner -> "unreachable"))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(first);
            assertThat(first.getSuppressed()).containsExactly(later);
            assertThatThrownBy(f.resources::markReady).hasCause(first);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void shutdownAttemptsThePoolEvenIfProtectionCloseFailsAndPreservesThePrimary(boolean error) {
        var original = mock(HikariDataSource.class);
        var pool = mock(HikariDataSource.class);
        Throwable first = error ? new AssertionError("synthetic protection shutdown")
                : new IllegalStateException("synthetic protection shutdown");
        var later = new IllegalStateException("synthetic pool shutdown");
        doThrow(later).when(pool).close();
        try (var constructor = mockConstruction(LegalRegistrationSessionDataSource.class,
                (proxy, context) -> doThrow(first).when(proxy).close())) {
            var resources = new LegalRegistrationSessionResources(source -> pool);
            resources.install(original);
            assertThatThrownBy(resources::close).isSameAs(first);
            assertThat(first.getSuppressed()).containsExactly(later);
            resources.close();
            verify(constructor.constructed().getFirst()).close();
            verify(pool).close();
            verifyNoInteractions(original);
            assertUnavailable(resources, LegalRegistrationBudget.start(() -> 0L));
        }
    }

    @Test
    void closingAnActiveLeaseAbortsAndReturnsItBeforeThePoolAndDoesNotOwnHistorical() throws Exception {
        try (var f = new Fixture()) {
            var router = f.resources.install(f.historical);
            f.resources.markReady();
            assertThatThrownBy(() -> f.resources.withinRegistrationBudget(f.budget, owner -> {
                try (Connection connection = router.getConnection()) {
                    f.resources.close();
                    assertThat(connection.isClosed()).isTrue();
                } catch (SQLException failure) { throw new AssertionError(failure); }
                return "unreachable";
            })).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            var order = org.mockito.Mockito.inOrder(f.connection, f.pool);
            order.verify(f.connection).abort(any());
            order.verify(f.connection).close();
            order.verify(f.pool).close();
            verify(f.historical, never()).close();
        }
    }

    @Test
    void closingBeforeInstallationPreventsAnyLaterResourceCreation() {
        try (var f = new Fixture()) {
            f.resources.close();
            assertThatThrownBy(() -> f.resources.install(f.historical)).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            assertThatThrownBy(f.resources::markReady).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            assertThat(f.creations).hasValue(0);
            verifyNoInteractions(f.historical, f.pool, f.connection);
        }
    }

    private static void assertUnavailable(LegalRegistrationSessionResources resources, LegalRegistrationBudget budget) {
        assertThatThrownBy(() -> resources.withinRegistrationBudget(budget, owner -> "unreachable"))
                .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class)
                .hasMessage("La sesión de registro no está disponible");
    }

    private static final class Fixture implements AutoCloseable {
        final HikariDataSource historical = mock(HikariDataSource.class);
        final HikariDataSource pool = mock(HikariDataSource.class);
        final Connection connection = mock(Connection.class);
        final LegalRegistrationBudget budget = LegalRegistrationBudget.start(() -> 0L);
        final AtomicInteger creations = new AtomicInteger();
        final LegalRegistrationSessionResources resources = new LegalRegistrationSessionResources(original -> {
            assertThat(original).isSameAs(historical);
            creations.incrementAndGet();
            return pool;
        });

        Fixture() {
            try { when(pool.getConnection()).thenReturn(connection); }
            catch (SQLException failure) { throw new AssertionError(failure); }
        }

        @Override public void close() { resources.close(); }
    }
}
