package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalActorSnapshotReaderTest {

    private static final LegalEditorialTimeBoundary BOUNDARY = new LegalEditorialTimeBoundary(
            Instant.parse("2026-09-06T00:00:00Z"), Instant.parse("2026-09-06T00:00:01Z"));
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final LegalActorSnapshotReader reader = new LegalActorSnapshotReader(jdbc);
    private final PreparedStatement advisory = mock(PreparedStatement.class);
    private final PreparedStatement workshop = mock(PreparedStatement.class);
    private final PreparedStatement user = mock(PreparedStatement.class);
    private final ResultSet advisoryRows = mock(ResultSet.class);
    private final ResultSet workshopRows = mock(ResultSet.class);
    private final ResultSet userRows = mock(ResultSet.class);
    private final List<String> sql = new ArrayList<>();

    @BeforeEach
    void bindOneMutableReadCommittedConnection() throws SQLException {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection));
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
            String statement = invocation.getArgument(0);
            sql.add(statement);
            if (statement.contains("pg_advisory_xact_lock_shared")) { return advisory; }
            if (statement.contains("public.talleres")) { return workshop; }
            if (statement.contains("public.users")) { return user; }
            throw new AssertionError("Unexpected SQL: " + statement);
        });
        when(advisory.executeQuery()).thenReturn(advisoryRows);
        when(workshop.executeQuery()).thenReturn(workshopRows);
        when(user.executeQuery()).thenReturn(userRows);
        when(advisoryRows.next()).thenReturn(true, false);
        when(workshopRows.next()).thenReturn(true, false);
        when(userRows.next()).thenReturn(true, false);
        when(workshopRows.getObject("id", Long.class)).thenReturn(22L);
        when(workshopRows.getObject("activo", Boolean.class)).thenReturn(true);
        when(userRows.getObject("id", Long.class)).thenReturn(11L);
        when(userRows.getObject("taller_id", Long.class)).thenReturn(22L);
        when(userRows.getString("role")).thenReturn("USER");
        when(userRows.getObject("active", Boolean.class)).thenReturn(true);
        when(userRows.getObject("token_version", Long.class)).thenReturn(3L);
    }

    @AfterEach
    void removeTheSyntheticTransaction() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void acceptsOnlyTheServerPrincipalAndLocksActorWorkshopThenUser(UserRole role) throws SQLException {
        when(userRows.getString("role")).thenReturn(role.name());
        AuthenticatedUserPrincipal principal = spy(principal(role));

        assertThat(reader.read(principal, BOUNDARY, deadline()))
                .isEqualTo(new LegalActorSnapshot(11, 22, role, 3, true, true));

        assertThat(sql).hasSize(3);
        assertThat(sql.get(0)).contains("pg_advisory_xact_lock_shared", "jsonb_build_array",
                "'ordenfix:legal-actor:v1'", "?::bigint, ?::bigint", "::text, 0");
        assertThat(sql.get(1)).contains("public.talleres", "LIMIT 2 FOR SHARE");
        assertThat(sql.get(2)).contains("public.users", "id = ? AND taller_id = ?", "LIMIT 2 FOR SHARE");
        assertThat(String.join(" ", sql)).doesNotContain("password", "username", "email", "UPDATE", "FOR KEY SHARE");
        verify(advisory).setLong(1, 22L);
        verify(advisory).setLong(2, 11L);
        verify(workshop).setLong(1, 22L);
        verify(user).setLong(1, 11L);
        verify(user).setLong(2, 22L);
        for (PreparedStatement statement : List.of(advisory, workshop, user)) {
            verify(statement).setFetchSize(32);
            verify(statement).setMaxRows(2);
            verify(statement).close();
        }
        for (ResultSet rows : List.of(advisoryRows, workshopRows, userRows)) { verify(rows).close(); }
        verify(principal, never()).getPassword();
        verify(connection, never()).close();
        verifyNoInteractions(dataSource);
        assertThat(reader.usesJdbc(jdbc)).isTrue();
        assertThat(reader.usesJdbc(new JdbcTemplate(dataSource))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "user-null", "user-zero", "workshop-null", "workshop-negative",
            "token-negative", "disabled", "expired", "locked", "credentials-expired", "unknown-role", "multiple-roles"})
    void rejectsInvalidPrincipalsBeforeAnyDatabaseAccess(String invalid) {
        AuthenticatedUserPrincipal principal = spy(principal(UserRole.USER));
        switch (invalid) {
            case "null" -> principal = null;
            case "user-null" -> doReturn(null).when(principal).getUserId();
            case "user-zero" -> doReturn(0L).when(principal).getUserId();
            case "workshop-null" -> doReturn(null).when(principal).getTallerId();
            case "workshop-negative" -> doReturn(-1L).when(principal).getTallerId();
            case "token-negative" -> doReturn(-1L).when(principal).getTokenVersion();
            case "disabled" -> doReturn(false).when(principal).isEnabled();
            case "expired" -> doReturn(false).when(principal).isAccountNonExpired();
            case "locked" -> doReturn(false).when(principal).isAccountNonLocked();
            case "credentials-expired" -> doReturn(false).when(principal).isCredentialsNonExpired();
            case "unknown-role" -> doReturn(List.of(new SimpleGrantedAuthority("ROLE_OWNER")))
                    .when(principal).getAuthorities();
            case "multiple-roles" -> doReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_USER"))).when(principal).getAuthorities();
            default -> throw new AssertionError(invalid);
        }
        AuthenticatedUserPrincipal finalPrincipal = principal;
        assertActorRejected(() -> reader.read(finalPrincipal, BOUNDARY, deadline()));
        assertThat(sql).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-workshop", "workshop-id", "workshop-disabled", "workshop-null",
            "missing-user", "user-id", "user-tenant", "user-role", "user-active", "user-active-null",
            "user-token", "user-token-null"})
    void rejectsEveryAccountMismatchWithoutReturningAPartialSnapshot(String mismatch) throws SQLException {
        switch (mismatch) {
            case "missing-workshop" -> when(workshopRows.next()).thenReturn(false);
            case "workshop-id" -> when(workshopRows.getObject("id", Long.class)).thenReturn(23L);
            case "workshop-disabled" -> when(workshopRows.getObject("activo", Boolean.class)).thenReturn(false);
            case "workshop-null" -> when(workshopRows.getObject("activo", Boolean.class)).thenReturn(null);
            case "missing-user" -> when(userRows.next()).thenReturn(false);
            case "user-id" -> when(userRows.getObject("id", Long.class)).thenReturn(12L);
            case "user-tenant" -> when(userRows.getObject("taller_id", Long.class)).thenReturn(23L);
            case "user-role" -> when(userRows.getString("role")).thenReturn("ADMIN");
            case "user-active" -> when(userRows.getObject("active", Boolean.class)).thenReturn(false);
            case "user-active-null" -> when(userRows.getObject("active", Boolean.class)).thenReturn(null);
            case "user-token" -> when(userRows.getObject("token_version", Long.class)).thenReturn(4L);
            case "user-token-null" -> when(userRows.getObject("token_version", Long.class)).thenReturn(null);
            default -> throw new AssertionError(mismatch);
        }
        assertActorRejected(() -> reader.read(principal(UserRole.USER), BOUNDARY, deadline()));
        if (mismatch.startsWith("workshop") || mismatch.equals("missing-workshop")) {
            assertThat(sql).hasSize(2);
            verify(workshop).cancel();
            verify(workshopRows).close();
        } else {
            assertThat(sql).hasSize(3);
            verify(user).cancel();
            verify(userRows).close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-active", "read-only", "isolation", "unbound",
            "effective-auto-commit", "effective-read-only", "effective-isolation"})
    void rejectsUnaccreditedTransactionsBeforeAcquiringLocks(String mode) throws SQLException {
        switch (mode) {
            case "not-active" -> TransactionSynchronizationManager.setActualTransactionActive(false);
            case "read-only" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            case "isolation" -> TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);
            case "unbound" -> TransactionSynchronizationManager.unbindResource(dataSource);
            case "effective-auto-commit" -> when(connection.getAutoCommit()).thenReturn(true);
            case "effective-read-only" -> when(connection.isReadOnly()).thenReturn(true);
            case "effective-isolation" -> when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
            default -> throw new AssertionError(mode);
        }
        assertThatThrownBy(() -> reader.read(principal(UserRole.USER), BOUNDARY, deadline()))
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
        assertThat(sql).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"advisory-empty", "advisory-duplicate", "workshop-duplicate", "user-duplicate"})
    void rejectsMissingLockEvidenceAndUnexpectedRowSentinels(String drift) throws SQLException {
        switch (drift) {
            case "advisory-empty" -> when(advisoryRows.next()).thenReturn(false);
            case "advisory-duplicate" -> when(advisoryRows.next()).thenReturn(true, true);
            case "workshop-duplicate" -> when(workshopRows.next()).thenReturn(true, true);
            case "user-duplicate" -> when(userRows.next()).thenReturn(true, true);
            default -> throw new AssertionError(drift);
        }
        assertThatThrownBy(() -> reader.read(principal(UserRole.USER), BOUNDARY, deadline()))
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
    }

    @Test
    void expiredMonotonicBudgetStopsBeforeQueryAndNeverReadsPassword() {
        AtomicLong now = new AtomicLong();
        LegalPrivateRequirementsDeadline deadline = new LegalPrivateRequirementsDeadline(Duration.ofSeconds(15), now::get);
        now.set(Duration.ofSeconds(15).toNanos());
        assertThatThrownBy(() -> reader.read(principal(UserRole.USER), BOUNDARY, deadline))
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
        assertThat(sql).isEmpty();
    }

    @Test
    void preservesSqlStateInternallyWhileSanitizingTheOuterFailureAndClosingResources() throws SQLException {
        SQLException failure = new SQLException("sensitive-sql-user-11", "55P03");
        when(user.executeQuery()).thenThrow(failure);
        assertThatThrownBy(() -> reader.read(principal(UserRole.USER), BOUNDARY, deadline()))
                .isInstanceOf(LegalPrivateRequirementsReadException.class)
                .hasRootCause(failure).hasMessageNotContainingAny("sensitive", "11", "55P03");
        verify(user).cancel();
        verify(user).close();
        verify(workshopRows).close();
        verify(connection, never()).close();
    }

    private static void assertActorRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(LegalActorSnapshotException.class)
                .hasMessage("La identidad legal no está disponible").hasNoCause();
    }

    private static LegalPrivateRequirementsDeadline deadline() {
        return new LegalPrivateRequirementsDeadline(Duration.ofSeconds(15));
    }

    private static AuthenticatedUserPrincipal principal(UserRole role) {
        return new AuthenticatedUserPrincipal(User.builder().id(11L).taller(Taller.builder().id(22L).build())
                .email("unit@example.invalid").password("test-only-unused-hash").role(role)
                .active(true).tokenVersion(3).build());
    }
}
