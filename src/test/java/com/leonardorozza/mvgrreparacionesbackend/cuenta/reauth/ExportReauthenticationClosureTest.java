package com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBlockedException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBusyException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureGate;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosurePolicy;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Boundary checks around the real JWT binding; the existing PostgreSQL IT owns physical rollback. */
class ExportReauthenticationClosureTest {
    private static final String PROOF = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    @BeforeEach void writableTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }

    @Test void restrictedTitularCanAuthorizeAnExistingDownloadButCannotCreateANewExport() {
        var f = new Fixture();
        var session = f.service.authorizeDownload(f.access);
        assertThat(session.userId()).isEqualTo(10); assertThat(session.tallerId()).isEqualTo(20);
        assertThat(session.tokenVersion()).isEqualTo(8);
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(WorkshopClosureBlockedException.class);
        assertThatThrownBy(() -> f.service.issue(f.access, "fixture-password", EXPORTAR)).isExactlyInstanceOf(WorkshopClosureBlockedException.class);
        assertThatThrownBy(() -> f.service.consume(f.access, PROOF, EXPORTAR)).isExactlyInstanceOf(WorkshopClosureBlockedException.class);
        verifyNoInteractions(f.jdbc, f.passwords);
        verify(f.gate).requireAccountAccess(20);
        verifyNoMoreInteractions(f.gate);
    }

    @Test void downloadProofCannotOutliveTheOriginalGraceBoundary() {
        var f = new Fixture();
        when(f.jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(f.passwords.matches("fixture-password", "fixture-hash")).thenReturn(true);
        var grant = f.service.issue(f.access, "fixture-password", DESCARGAR_EXPORTACION);
        assertThat(grant.expiresAt()).isEqualTo(f.end());
        assertThat(grant.token()).hasSize(43);
        verify(f.passwords).matches("fixture-password", "fixture-hash");
        verify(f.gate).requireAccountAccess(20);
        verifyNoMoreInteractions(f.gate);
    }

    @Test void passwordWorkCrossingGraceFailsBeforeAnyProofDml() {
        var f = new Fixture();
        when(f.passwords.matches("fixture-password", "fixture-hash")).thenAnswer(invocation -> {
            f.clock.now = f.end(); return true;
        });
        assertThatThrownBy(() -> f.service.issue(f.access, "fixture-password", DESCARGAR_EXPORTACION))
                .isExactlyInstanceOf(UnauthorizedException.class).hasNoCause();
        verifyNoInteractions(f.jdbc);
    }

    @Test void refreshUnderTheUserLockDeterminesTheAllowedPurpose() {
        var f = new Fixture(); f.user.getTaller().setCierreEstado("ABIERTO");
        doAnswer(invocation -> { f.user.getTaller().setCierreEstado("RESTRINGIDO"); return null; })
                .when(f.security).refreshAndLock(f.user);
        assertThatThrownBy(() -> f.service.authorize(f.access)).isExactlyInstanceOf(WorkshopClosureBlockedException.class);
        verifyNoInteractions(f.gate, f.jdbc);
    }

    @Test void currentRevocationEpochRejectsAnOldSignedJwtIncludingDownload() {
        var f = new Fixture(); f.user.setTokenVersion(9);
        assertThatThrownBy(() -> f.service.authorizeDownload(f.access)).isExactlyInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> f.service.issue(f.access, "fixture-password", DESCARGAR_EXPORTACION)).isExactlyInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> f.service.consume(f.access, PROOF, DESCARGAR_EXPORTACION)).isExactlyInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(f.jdbc, f.passwords, f.gate);
    }

    @Test void consumeThatReturnsFromSqlAtTheDeadlineRejectsTheCallerTransaction() {
        var f = new Fixture();
        when(f.jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Instant>>any(), any(Object[].class)))
                .thenAnswer(invocation -> { f.clock.now = f.end(); return List.of(f.end().plusSeconds(300)); });
        assertThatThrownBy(() -> f.service.consume(f.access, PROOF, DESCARGAR_EXPORTACION))
                .isExactlyInstanceOf(UnauthorizedException.class).hasNoCause();
        verify(f.jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Instant>>any(), any(Object[].class));
    }

    @Test void operativeAuthorizationHoldsTheCommonGateAndPreservesItsSanitizedRejections() {
        var f = new Fixture(); f.user.getTaller().setCierreEstado("ABIERTO");
        f.service.authorize(f.access); verify(f.gate).requireOperational(20);
        var blocked = new WorkshopClosureBlockedException();
        doThrow(blocked).when(f.gate).requireOperational(20);
        assertThatThrownBy(() -> f.service.authorizeDownload(f.access)).isSameAs(blocked).hasNoCause();
        var busy = new WorkshopClosureBusyException();
        doThrow(busy).when(f.gate).requireOperational(20);
        assertThatThrownBy(() -> f.service.issue(f.access, "fixture-password", EXPORTAR)).isSameAs(busy).hasNoCause();
        verifyNoInteractions(f.jdbc, f.passwords);
    }

    @Test void restrictedDownloadFailsWithoutWaitingWhenRestorationOwnsAdmission() {
        var f = new Fixture();
        var busy = new WorkshopClosureBusyException();
        doThrow(busy).when(f.gate).requireAccountAccess(20);
        assertThatThrownBy(() -> f.service.authorizeDownload(f.access)).isSameAs(busy).hasNoCause();
        assertThatThrownBy(() -> f.service.issue(f.access, "fixture-password", DESCARGAR_EXPORTACION)).isSameAs(busy).hasNoCause();
        verifyNoInteractions(f.jdbc, f.passwords);
    }

    @Test void downloadAuthorizationStillRequiresTheCallersWritableTransaction() {
        var f = new Fixture(); TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> f.service.authorizeDownload(f.access)).isExactlyInstanceOf(IllegalStateException.class);
        verifyNoInteractions(f.users, f.security, f.jdbc, f.gate);
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
        final User user = actor(clock.instant());
        final UserRepository users = mock(UserRepository.class);
        final UserSecurityStateLock security = mock(UserSecurityStateLock.class);
        final PasswordEncoder passwords = mock(PasswordEncoder.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final WorkshopClosureGate gate = mock(WorkshopClosureGate.class);
        final JwtUtils jwt = new JwtUtils("closure-fixture-security-key-at-least-32-bytes", 600_000, "closure-test", "api", 0);
        final String access = jwt.generateToken(new AuthenticatedUserPrincipal(user, clock.instant()), 20L);
        final ExportReauthenticationService service = new ExportReauthenticationService(users, security, passwords, jwt, jdbc, clock, gate);
        Fixture() { when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user)); }
        Instant end() { return user.getTaller().getCierreReversibleHasta().toInstant(); }
    }

    private static User actor(Instant now) {
        var workshop = Taller.builder().id(20L).nombre("Fixture").activo(true).build();
        var schedule = WorkshopClosurePolicy.scheduleAt(now.minus(WorkshopClosurePolicy.RESTORATION_WINDOW).plusSeconds(120));
        workshop.setCierreEstado("RESTRINGIDO"); workshop.setCierreVersion(1); workshop.setCierreReferencia(UUID.randomUUID());
        workshop.setCierreConfirmadoEn(schedule.confirmedAt().atOffset(ZoneOffset.UTC));
        workshop.setCierreReversibleHasta(schedule.reversibleUntil().atOffset(ZoneOffset.UTC));
        workshop.setCierreEliminacionPrevistaEn(schedule.deletionExpectedBy().atOffset(ZoneOffset.UTC));
        return User.builder().id(10L).username("Fixture").email("closure@example.test").password("fixture-hash")
                .role(UserRole.ADMIN).active(true).emailVerificado(true).tokenVersion(8).taller(workshop).build();
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
