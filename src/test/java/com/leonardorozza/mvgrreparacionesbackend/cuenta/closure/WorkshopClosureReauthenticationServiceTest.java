package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserSecurityStateLock;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosurePurpose.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JWTs are real. PostgreSQL IT verifies physical constraints, persistence and transaction rollback. */
class WorkshopClosureReauthenticationServiceTest {
    private static final String PROOF = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    @BeforeEach void transaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }

    @Test void cryptographicRoutingDoesNotTouchDatabaseOrLocks() {
        var f = new Fixture();
        TransactionSynchronizationManager.clear();
        assertThat(f.service.routeWorkshop(f.access)).isEqualTo(20);
        verifyNoInteractions(f.users, f.security, f.gate, f.jdbc, f.passwords);
        int start = f.access.lastIndexOf('.') + 1;
        String tampered = f.access.substring(0, start) + (f.access.charAt(start) == 'A' ? 'B' : 'A') + f.access.substring(start + 1);
        assertThatThrownBy(() -> f.service.routeWorkshop(tampered)).isExactlyInstanceOf(UnauthorizedException.class).hasNoCause();
    }

    @Test void gateAdmissionPrecedesTheFreshUserLock() {
        var f = new Fixture();
        var authority = f.service.authorize(f.access);
        var ordered = inOrder(f.gate, f.users, f.security);
        ordered.verify(f.gate).requireAccountAccess(20);
        ordered.verify(f.users).findByEmail(f.user.getEmail());
        ordered.verify(f.security).refreshAndLock(f.user);
        assertThat(authority.userId()).isEqualTo(10);
        assertThat(authority.tokenVersion()).isEqualTo(8);
        assertThat(authority.state()).isEqualTo("ABIERTO");
        assertThat(authority.sessionHash()).matches("[0-9a-f]{64}");
        assertThat(authority.toString()).isEqualTo("Authority[redacted]");
        verifyNoInteractions(f.jdbc, f.passwords);
    }

    @Test void unavailableGateDoesNotAcquireTheUserLockAndPreservesSafeException() {
        var f = new Fixture(); var busy = new WorkshopClosureBusyException();
        doThrow(busy).when(f.gate).requireAccountAccess(20);
        assertThatThrownBy(() -> f.service.authorize(f.access)).isSameAs(busy).hasNoCause();
        verifyNoInteractions(f.users, f.security, f.jdbc, f.passwords);
    }

    @Test void stateIsReadAfterRefreshAndOldEpochFailsClosed() {
        var f = new Fixture();
        doAnswer(invocation -> { f.user.setTokenVersion(9); return null; }).when(f.security).refreshAndLock(f.user);
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(f.jdbc, f.passwords);
    }

    @Test void roleAndVerificationAreIndependentRequirements() {
        var f = new Fixture(); f.user.setRole(UserRole.USER);
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(AccessDeniedException.class);
        f.user.setRole(UserRole.ADMIN); f.user.setEmailVerificado(false);
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(f.jdbc, f.passwords);
    }

    @Test void authorizationSnapshotCannotAuthorizeAnotherTenantOrInactiveActor() {
        var f = new Fixture(); f.user.getTaller().setId(21L);
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(UnauthorizedException.class);
        f.user.getTaller().setId(20L); f.user.setActive(false);
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(f.jdbc, f.passwords);
    }

    @Test void purposeAndReferenceAreCheckedBeforePasswordOrProofDml() {
        var f = new Fixture();
        badProof(() -> f.service.issue(f.access, "password", CERRAR, f.operation, UUID.randomUUID()));
        badProof(() -> f.service.issue(f.access, "password", RESTAURAR, f.operation, f.operation));
        f.restrict();
        assertThatThrownBy(() -> f.service.issue(f.access, "password", CERRAR, f.operation, f.operation))
                .isExactlyInstanceOf(WorkshopClosureBlockedException.class);
        badProof(() -> f.service.issue(f.access, "password", RESTAURAR, f.operation, UUID.randomUUID()));
        verifyNoInteractions(f.passwords, f.jdbc);
    }

    @Test void grantDeadlineIsCappedByRestorationGraceAndPasswordRemainsUntrimmed() {
        var f = new Fixture(); f.restrict();
        when(f.passwords.matches(" password ", "fixture-hash")).thenReturn(true);
        when(f.jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var grant = f.service.issue(f.access, " password ", RESTAURAR, f.operation, f.reference());
        assertThat(grant.expiresAt()).isEqualTo(f.end());
        assertThat(grant.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(grant.toString()).doesNotContain(grant.token());
        verify(f.passwords).matches(" password ", "fixture-hash");
    }

    @Test void passwordWorkCannotCrossTheGraceDeadline() {
        var f = new Fixture(); f.restrict();
        when(f.passwords.matches("password", "fixture-hash")).thenAnswer(invocation -> { f.clock.now = f.end(); return true; });
        assertThatThrownBy(() -> f.service.issue(f.access, "password", RESTAURAR, f.operation, f.reference()))
                .isExactlyInstanceOf(UnauthorizedException.class).hasNoCause();
        verifyNoInteractions(f.jdbc);
    }

    @Test void grantInsertReturningAfterFiveMinutesFailsClosed() {
        var f = new Fixture();
        when(f.passwords.matches("password", "fixture-hash")).thenReturn(true);
        when(f.jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            if (((String) invocation.getArgument(0)).contains("INSERT")) f.clock.now = f.clock.now.plusSeconds(301);
            return 1;
        });
        badProof(() -> f.service.issue(f.access, "password", CERRAR, f.operation, f.operation));
    }

    @Test void expiredSqlConsumptionFailsEvenWhenJwtRemainsLive() {
        var f = new Fixture(); Instant proofExpiry = f.clock.now.plusSeconds(100);
        when(f.jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Instant>>any(), any(Object[].class)))
                .thenAnswer(invocation -> { f.clock.now = proofExpiry; return List.of(proofExpiry); });
        badProof(() -> f.service.consume(f.access, PROOF, CERRAR, f.operation, f.operation));
    }

    @Test void consumedAuthorityCarriesTheProofDeadlineIntoTheCoordinatorPostcheck() {
        var f = new Fixture(); Instant proofExpiry = f.clock.now.plusSeconds(100);
        when(f.jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Instant>>any(), any(Object[].class)))
                .thenReturn(List.of(proofExpiry));
        var authority = f.service.consume(f.access, PROOF, CERRAR, f.operation, f.operation);
        assertThat(authority.expiresAt()).isEqualTo(proofExpiry);
        f.user.setTokenVersion(9);
        f.service.requireLive(authority);
        f.clock.now = proofExpiry;
        assertThatThrownBy(() -> f.service.requireLive(authority)).isExactlyInstanceOf(UnauthorizedException.class);
    }

    @Test void temporalPostcheckWorksAfterTheCoordinatorChangesItsOwnEpoch() {
        var f = new Fixture(); var authority = f.service.authorize(f.access);
        f.user.setTokenVersion(9);
        f.service.requireLive(authority);
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(UnauthorizedException.class);
        f.clock.now = authority.expiresAt();
        assertThatThrownBy(() -> f.service.requireLive(authority)).isExactlyInstanceOf(UnauthorizedException.class);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "short", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
    void malformedProofNeverReachesDatabaseOrAuthority(String malformed) {
        var f = new Fixture();
        badProof(() -> f.service.consume(f.access, malformed, CERRAR, f.operation, f.operation));
        verifyNoInteractions(f.users, f.security, f.gate, f.jdbc, f.passwords);
    }

    @Test void missingReadonlyOrRepeatableReadTransactionCannotConsume() {
        var f = new Fixture();
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> f.service.consume(f.access, PROOF, CERRAR, f.operation, f.operation)).isExactlyInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.clear();
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(IllegalStateException.class);
        verifyNoInteractions(f.users, f.security, f.gate, f.jdbc, f.passwords);
    }

    @Test void unexpectedPersistenceFailureNeverRetainsSensitiveDiagnostic() {
        var f = new Fixture();
        when(f.users.findByEmail(anyString())).thenThrow(new IllegalStateException("private driver payload"));
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("No se pudo completar la reautenticación.").hasNoCause();
    }

    private static void badProof(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BadRequestException.class,
                error -> assertThat(error.getCode()).isEqualTo("REAUTENTICACION_INVALIDA"));
    }
    private static final class Fixture {
        final MutableClock clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
        final User user = User.builder().id(10L).username("Fixture").email("closure@example.test").password("fixture-hash")
                .role(UserRole.ADMIN).active(true).emailVerificado(true).tokenVersion(8)
                .taller(Taller.builder().id(20L).nombre("Fixture").activo(true).build()).build();
        final UserRepository users = mock(UserRepository.class);
        final UserSecurityStateLock security = mock(UserSecurityStateLock.class);
        final PasswordEncoder passwords = mock(PasswordEncoder.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final WorkshopClosureGate gate = mock(WorkshopClosureGate.class);
        final JwtUtils jwt = new JwtUtils("closure-reauth-fixture-key-at-least-32-bytes",600_000,"closure-test","api",0);
        final String access = jwt.generateToken(new AuthenticatedUserPrincipal(user, clock.instant()),20L);
        final UUID operation = UUID.randomUUID();
        final WorkshopClosureReauthenticationService service = new WorkshopClosureReauthenticationService(users,security,passwords,jwt,jdbc,clock,gate);
        Fixture() { when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user)); }
        void restrict() {
            var schedule = WorkshopClosurePolicy.scheduleAt(clock.now.minus(WorkshopClosurePolicy.RESTORATION_WINDOW).plusSeconds(120));
            var t=user.getTaller(); t.setCierreEstado("RESTRINGIDO"); t.setCierreVersion(1); t.setCierreReferencia(UUID.randomUUID());
            t.setCierreConfirmadoEn(schedule.confirmedAt().atOffset(ZoneOffset.UTC));
            t.setCierreReversibleHasta(schedule.reversibleUntil().atOffset(ZoneOffset.UTC));
            t.setCierreEliminacionPrevistaEn(schedule.deletionExpectedBy().atOffset(ZoneOffset.UTC));
        }
        UUID reference() { return user.getTaller().getCierreReferencia(); }
        Instant end() { return user.getTaller().getCierreReversibleHasta().toInstant(); }
    }
    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now=now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
