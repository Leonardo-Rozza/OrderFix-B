package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AccountAccessExitServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final UserSecurityStateLock state = mock(UserSecurityStateLock.class);
    private final AccountAccessExitService service = new AccountAccessExitService(users, passwords, state);

    @Test void deactivatesOnlyOwnUserAfterCurrentPasswordVerificationAndDoesNotRequireVerifiedEmail() {
        User user = user();
        var principal = prepare(user);
        when(passwords.matches(" secret123 ", "hash")).thenReturn(true);

        service.deactivate(principal, " secret123 ");

        var ordered = inOrder(users, state, passwords);
        ordered.verify(users).findByIdAndTallerId(1L, 2L);
        ordered.verify(state).refreshAndLock(user);
        ordered.verify(passwords).matches(" secret123 ", "hash");
        ordered.verify(users).save(user);
        assertThat(user.getActive()).isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(5L);
        assertThat(user.getEmailVerificado()).isFalse();
        assertThat(user.getTaller().getActivo()).isTrue();
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
    }

    @Test void wrongPasswordDoesNotWriteOrRevokeTheValidSession() {
        User user = user();
        var principal = prepare(user);
        assertThatThrownBy(() -> service.deactivate(principal, "wrong"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("PASSWORD_ACTUAL_INVALIDA"));
        assertThat(user.getActive()).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(4L);
        verify(users, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "inactive", "workshop", "tenant", "email", "id"})
    void stateChangedAfterAuthenticationIsRejectedAfterRefresh(String change) {
        User user = user();
        var principal = prepare(user);
        doAnswer(call -> {
            switch (change) {
                case "version" -> user.setTokenVersion(5L);
                case "inactive" -> user.setActive(false);
                case "workshop" -> user.getTaller().setActivo(false);
                case "tenant" -> user.getTaller().setId(3L);
                case "email" -> user.setEmail("other@test.com");
                case "id" -> user.setId(3L);
                default -> throw new AssertionError(change);
            }
            return null;
        }).when(state).refreshAndLock(user);
        assertThatThrownBy(() -> service.deactivate(principal, "secret123")).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(passwords);
        verify(users, never()).save(any());
    }

    @Test void serviceAlsoRejectsTheOwnerRole() {
        User user = user();
        user.setRole(UserRole.ADMIN);
        var principal = prepare(user);
        assertThatThrownBy(() -> service.deactivate(principal, "secret123")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(passwords);
        verify(users, never()).save(any());
    }

    @Test void missingOrUnknownPrincipalCannotSelectAnotherUser() {
        assertThatThrownBy(() -> service.deactivate(null, "secret123")).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(users, state, passwords);
        var principal = new AuthenticatedUserPrincipal(user());
        assertThatThrownBy(() -> service.deactivate(principal, "secret123")).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(state, passwords);
    }

    @Test void overflowFailsBeforeAnyMutation() {
        User user = user();
        user.setTokenVersion(Long.MAX_VALUE);
        var principal = prepare(user);
        when(passwords.matches("secret123", "hash")).thenReturn(true);
        assertThatThrownBy(() -> service.deactivate(principal, "secret123")).isInstanceOf(ArithmeticException.class);
        assertThat(user.getActive()).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(Long.MAX_VALUE);
        verify(users, never()).save(any());
    }

    private AuthenticatedUserPrincipal prepare(User user) {
        when(users.findByIdAndTallerId(user.getId(), user.getTaller().getId())).thenReturn(Optional.of(user));
        return new AuthenticatedUserPrincipal(user);
    }

    private User user() {
        return User.builder().id(1L).username("Empleado").email("employee@test.com").password("hash")
                .role(UserRole.USER).active(true).emailVerificado(false).tokenVersion(4L)
                .taller(Taller.builder().id(2L).activo(true).build()).build();
    }
}
