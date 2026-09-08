package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.hibernate.resource.jdbc.internal.ResourceRegistryStandardImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Actual Hibernate resource registry over simulated JDBC; the separate JPA IT proves real SQL. */
class LegalRegistrationSessionCleanupTest {
    private static final String QUERY = "SELECT session_resource_fixture";

    @ParameterizedTest
    @MethodSource("absorbedFailures")
    void aFailureAbsorbedByHibernateStillPreventsDeliveryAndPoisonsTheOriginalBudget(
            FailurePoint point, boolean unchecked) throws Exception {
        try (var f = new Fixture()) {
            Throwable failure = unchecked ? new IllegalStateException("synthetic resource control failure")
                    : new SQLException("synthetic resource control failure", "55P03");
            var hit = new AtomicInteger();
            var frameworkCompleted = new AtomicInteger();

            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, budget -> {
                try (Connection connection = f.source.getConnection()) {
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(QUERY);
                    verify(f.query).executeQuery(QUERY);
                    var registry = new ResourceRegistryStandardImpl();
                    registry.register(statement, true);
                    registry.register(rows, statement);
                    installFailure(f, point, failure, hit);

                    // This is Hibernate's real swallowing/early-return behavior, not a mocked release.
                    assertThatCode(() -> {
                        registry.release(rows, statement);
                        registry.release(statement);
                    }).doesNotThrowAnyException();
                    frameworkCompleted.incrementAndGet();
                    assertThat(registry.hasRegisteredResources()).isFalse();
                    assertThat(hit.get()).isPositive();
                    assertThatThrownBy(budget::check)
                            .isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class).hasCause(failure);
                    return "Hibernate finished, but this observation cannot be delivered";
                } catch (SQLException unexpected) {
                    throw new AssertionError(unexpected);
                }
            })).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(failure);

            assertThat(frameworkCompleted).hasValue(1);
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, ignored -> "later phase"))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(failure);
            verify(f.pool).getConnection();
            verify(f.driver).close();
            verifyNoInteractions(f.historical);
        }
    }

    @Test
    void runtimeFailureFromIsClosedPropagatesInHibernateButIsStillRecordedAsAControlFailure() throws Exception {
        try (var f = new Fixture()) {
            var failure = new IllegalStateException("synthetic isClosed control failure");
            var hit = new AtomicInteger();
            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, budget -> {
                try (Connection connection = f.source.getConnection()) {
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(QUERY);
                    var registry = new ResourceRegistryStandardImpl();
                    registry.register(statement, true);
                    registry.register(rows, statement);
                    installFailure(f, FailurePoint.IS_CLOSED, failure, hit);

                    // Hibernate catches SQLException at this call site, but not arbitrary RuntimeException.
                    assertThatThrownBy(() -> registry.release(rows, statement)).isSameAs(failure);
                    rows.close();
                    registry.release(statement);
                    assertThat(hit).hasValue(1);
                    return "cannot continue after the control failure";
                } catch (SQLException unexpected) {
                    throw new AssertionError(unexpected);
                }
            })).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(failure);
            assertThatThrownBy(f.owner::check).hasCause(failure);
            verify(f.driver).close();
        }
    }

    @Test
    void theSameSqlStateFromExecuteRemainsAnOperationalFailureAfterSuccessfulCleanup() throws Exception {
        try (var f = new Fixture()) {
            var operational = new SQLException("synthetic lock wait failure", "55P03");
            when(f.query.executeQuery(QUERY)).thenThrow(operational);

            String outcome = f.source.withinRegistrationBudget(f.owner, budget -> {
                try (Connection connection = f.source.getConnection()) {
                    Statement statement = connection.createStatement();
                    var registry = new ResourceRegistryStandardImpl();
                    registry.register(statement, true);
                    assertThatThrownBy(() -> statement.executeQuery(QUERY)).isSameAs(operational);
                    registry.release(statement);
                    assertThat(registry.hasRegisteredResources()).isFalse();
                    assertThat(budget.remainingMillis()).isEqualTo(30_000);
                    return "operational failure observed and resources released";
                } catch (SQLException unexpected) {
                    throw new AssertionError(unexpected);
                }
            });

            assertThat(outcome).isEqualTo("operational failure observed and resources released");
            assertThat(f.owner.remainingMillis()).isEqualTo(30_000);
            verify(f.query).close();
            verify(f.driver).close();
            verify(f.rows, never()).close();
        }
    }

    @Test
    void aControlFailureBeforeExecuteIsTerminalWithoutUsingAnExecutionStateHeuristic() throws Exception {
        try (var f = new Fixture()) {
            var controlFailure = new SQLException("synthetic timeout inspection failure", "55P03");
            doThrow(controlFailure).when(f.query).getQueryTimeout();

            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, budget -> {
                try (Connection connection = f.source.getConnection(); Statement statement = connection.createStatement()) {
                    assertThatThrownBy(statement::getQueryTimeout).isSameAs(controlFailure);
                    return "cannot ignore the failure before execution";
                } catch (SQLException unexpected) {
                    throw new AssertionError(unexpected);
                }
            })).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(controlFailure);

            verify(f.query, never()).executeQuery(anyString());
            verify(f.query).close();
            verify(f.driver).close();
        }
    }

    @Test
    void aLaterConnectionReleaseFailureCannotReplaceTheFirstAbsorbedResourceFailure() throws Exception {
        try (var f = new Fixture()) {
            var first = new SQLException("synthetic result release failure", "08006");
            var later = new SQLException("synthetic connection return failure", "08006");
            doThrow(first).when(f.rows).close();
            doThrow(later).when(f.driver).close();

            assertThatThrownBy(() -> f.source.withinRegistrationBudget(f.owner, budget -> {
                try {
                    Connection connection = f.source.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(QUERY);
                    var registry = new ResourceRegistryStandardImpl();
                    registry.register(statement, true);
                    registry.register(rows, statement);
                    registry.release(rows, statement);
                    registry.release(statement);
                    assertThatThrownBy(connection::close).isSameAs(later);
                    return "neither failure can authorize delivery";
                } catch (SQLException unexpected) {
                    throw new AssertionError(unexpected);
                }
            })).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(first);

            assertThat(first.getSuppressed()).containsExactly(later);
            assertThatThrownBy(f.owner::check).hasCause(first);
            verify(f.driver).close();
        }
    }

    private static Stream<Arguments> absorbedFailures() {
        return Stream.of(FailurePoint.values()).flatMap(point -> point == FailurePoint.IS_CLOSED
                ? Stream.of(Arguments.of(point, false))
                : Stream.of(Arguments.of(point, false), Arguments.of(point, true)));
    }

    private static void installFailure(Fixture f, FailurePoint point, Throwable failure, AtomicInteger hit)
            throws SQLException {
        org.mockito.stubbing.Answer<Object> failed = invocation -> { hit.incrementAndGet(); throw failure; };
        switch (point) {
            case RESULT_CLOSE -> doAnswer(failed).when(f.rows).close();
            case STATEMENT_CLOSE -> doAnswer(failed).when(f.query).close();
            case GET_MAX_ROWS -> doAnswer(failed).when(f.query).getMaxRows();
            case GET_QUERY_TIMEOUT -> doAnswer(failed).when(f.query).getQueryTimeout();
            case RESET_MAX_ROWS -> doAnswer(failed).when(f.query).setMaxRows(0);
            case RESET_QUERY_TIMEOUT -> doAnswer(failed).when(f.query).setQueryTimeout(0);
            case IS_CLOSED -> doAnswer(failed).when(f.query).isClosed();
        }
    }

    private enum FailurePoint {
        RESULT_CLOSE, STATEMENT_CLOSE, GET_MAX_ROWS, GET_QUERY_TIMEOUT, RESET_MAX_ROWS, RESET_QUERY_TIMEOUT, IS_CLOSED
    }

    private static final class Fixture implements AutoCloseable {
        final DataSource historical = mock(DataSource.class);
        final DataSource pool = mock(DataSource.class);
        final Connection driver = mock(Connection.class);
        final Statement query = mock(Statement.class);
        final Statement settings = mock(Statement.class);
        final ResultSet rows = mock(ResultSet.class);
        final LegalRegistrationBudget owner = LegalRegistrationBudget.start(() -> 0L);
        final LegalRegistrationSessionDataSource source = new LegalRegistrationSessionDataSource(historical, pool);

        Fixture() throws SQLException {
            when(pool.getConnection()).thenReturn(driver);
            when(driver.getAutoCommit()).thenReturn(false);
            when(driver.createStatement()).thenReturn(query, settings);
            when(query.executeQuery(QUERY)).thenReturn(rows);
            when(query.getMaxRows()).thenReturn(1);
            when(query.getQueryTimeout()).thenReturn(5);
        }

        @Override public void close() { source.close(); }
    }
}
