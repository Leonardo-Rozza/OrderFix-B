package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosurePolicy;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountSessionClosureTest {
    private static final Instant NOW = Instant.parse("2026-09-12T14:00:00Z");

    @Test void titularCanLogInDuringGraceUsingTheCurrentEpochAndPassword() {
        var f = new Fixture();
        when(f.jwt.generateToken(any(AuthenticatedUserPrincipal.class), eq(20L))).thenAnswer(invocation -> {
            AuthenticatedUserPrincipal principal = invocation.getArgument(0);
            assertThat(principal.isWorkshopRestricted()).isTrue();
            assertThat(principal.getTokenVersion()).isEqualTo(8);
            return "synthetic-access-token";
        });
        assertThat(f.issue().token()).isEqualTo("synthetic-access-token");
        verify(f.passwords).matches("fixture-password", "fixture-hash");
    }

    @ParameterizedTest @EnumSource(Denied.class)
    void ineligibleClosureAccountFailsBeforeCredentialsOrTokenWork(Denied state) {
        var f = new Fixture(); state.change.accept(f.user);
        assertThatThrownBy(f::issue).isExactlyInstanceOf(BadCredentialsException.class)
                .hasMessage("Usuario o contraseña incorrectos").hasNoCause();
        verifyNoInteractions(f.passwords, f.jwt);
    }

    @Test void passwordWorkCannotCrossTheExactGraceDeadlineAndIssueAJwt() {
        var f = new Fixture();
        when(f.passwords.matches("fixture-password", "fixture-hash")).thenAnswer(invocation -> {
            f.clock.now = f.end(); return true;
        });
        assertThatThrownBy(f::issue).isExactlyInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(f.jwt);
    }

    @Test void tokenWorkThatCrossesTheGraceDeadlineCannotReturnASession() {
        var f = new Fixture();
        when(f.jwt.generateToken(any(), eq(20L))).thenAnswer(invocation -> {
            f.clock.now = f.end(); return "synthetic-access-token";
        });
        assertThatThrownBy(f::issue).isExactlyInstanceOf(BadCredentialsException.class);
    }

    @Test void userDetailsReadsCurrentStateAndClockOnEveryRequest() {
        var f = new Fixture();
        when(f.users.findByEmail(f.user.getEmail())).thenReturn(Optional.of(f.user));
        var details = new UserDetailsServiceImpl(f.users, f.clock);
        assertThat(details.loadUserByUsername(f.user.getEmail()).isWorkshopRestricted()).isTrue();
        f.clock.now = f.end();
        assertThat(details.loadUserByUsername(f.user.getEmail()).isEnabled()).isFalse();
        f.user.getTaller().setCierreEstado("ABIERTO"); f.user.setTokenVersion(9);
        var restored = details.loadUserByUsername(f.user.getEmail());
        assertThat(restored.isEnabled()).isTrue(); assertThat(restored.isWorkshopRestricted()).isFalse();
        assertThat(restored.getTokenVersion()).isEqualTo(9);
        verify(f.users, times(3)).findByEmail(f.user.getEmail());
    }

    private enum Denied {
        EMPLOYEE(u -> u.setRole(UserRole.USER)), UNVERIFIED(u -> u.setEmailVerificado(false)),
        INACTIVE(u -> u.setActive(false)), WORKSHOP_INACTIVE(u -> u.getTaller().setActivo(false)),
        ELIMINATED(u -> u.getTaller().setCierreEstado("ELIMINADO"));
        final Consumer<User> change;
        Denied(Consumer<User> change) { this.change = change; }
    }

    private static final class Fixture {
        final UserRepository users = mock(UserRepository.class);
        final PasswordEncoder passwords = mock(PasswordEncoder.class);
        final JwtUtils jwt = mock(JwtUtils.class);
        final MutableClock clock = new MutableClock(NOW);
        final User user = actor();
        final AccountSessionPolicy policy = new AccountSessionPolicy(users, passwords, jwt, clock);
        Fixture() {
            when(users.findSessionByIdAndTallerId(10L, 20L)).thenReturn(Optional.of(user));
            when(passwords.matches("fixture-password", "fixture-hash")).thenReturn(true);
        }
        com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto issue() {
            return policy.issueSession(10L, 20L, "fixture-password");
        }
        Instant end() { return user.getTaller().getCierreReversibleHasta().toInstant(); }
    }

    private static User actor() {
        var workshop = Taller.builder().id(20L).nombre("Fixture").activo(true).build();
        var schedule = WorkshopClosurePolicy.scheduleAt(NOW.minus(WorkshopClosurePolicy.RESTORATION_WINDOW).plusSeconds(120));
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
