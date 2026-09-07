package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private static final String EMAIL = "original@example.invalid";
    private static final String PASSWORD = " original-password-ñ ";
    private AuthenticationManager manager;
    private AccountSessionPolicy policy;
    private AuthService service;

    @BeforeEach void prepare() {
        manager = mock(AuthenticationManager.class);
        policy = mock(AccountSessionPolicy.class);
        service = new AuthService(manager, policy);
    }

    @Test void theManagerReceivesTheExactCredentialsAndThePolicyReceivesOnlyDurableIdsAndOriginalPassword() {
        var principal = new AuthenticatedUserPrincipal(user());
        when(manager.authenticate(any())).thenReturn(authenticated(principal));
        var response = new AuthResponseDto("synthetic-current-token", "Bearer", "current@example.invalid", true);
        when(policy.issueSession(17L, 29L, PASSWORD)).thenReturn(response);

        assertThat(service.login(EMAIL, PASSWORD)).isSameAs(response);
        var attempt = ArgumentCaptor.forClass(Authentication.class);
        var order = inOrder(manager, policy);
        order.verify(manager).authenticate(attempt.capture());
        assertThat(attempt.getValue()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(attempt.getValue().isAuthenticated()).isFalse();
        assertThat(attempt.getValue().getPrincipal()).isEqualTo(EMAIL);
        assertThat(attempt.getValue().getCredentials()).isEqualTo(PASSWORD);
        order.verify(policy).issueSession(17L, 29L, PASSWORD);
        verifyNoMoreInteractions(manager, policy);
    }

    @Test void erasedProviderCredentialsCannotReplaceTheOriginalPasswordWithNull() {
        var principal = new AuthenticatedUserPrincipal(user());
        var authentication = authenticated(principal);
        authentication.eraseCredentials();
        assertThat(authentication.getCredentials()).isNull(); assertThat(principal.getPassword()).isNull();
        when(manager.authenticate(any())).thenReturn(authentication);
        var response = new AuthResponseDto("synthetic-token", "Bearer", EMAIL, false);
        when(policy.issueSession(17L, 29L, PASSWORD)).thenReturn(response);

        assertThat(service.login(EMAIL, PASSWORD)).isSameAs(response);
        verify(policy).issueSession(17L, 29L, PASSWORD);
    }

    @Test void managerRejectionPropagatesWithoutInvokingTheSessionPolicy() {
        var rejected = new BadCredentialsException("synthetic provider rejection");
        when(manager.authenticate(any())).thenThrow(rejected);
        assertThat(catchThrowable(() -> service.login(EMAIL, PASSWORD))).isSameAs(rejected);
        verifyNoInteractions(policy);
    }

    @ParameterizedTest @ValueSource(strings = {"null-result", "not-authenticated", "null-principal", "generic-principal", "string-principal"})
    void malformedOrUntypedAuthenticationIsRejectedBeforeThePolicy(String defect) {
        Authentication result = null;
        if (!defect.equals("null-result")) {
            result = mock(Authentication.class);
            when(result.isAuthenticated()).thenReturn(!defect.equals("not-authenticated"));
            Object principal = switch (defect) {
                case "not-authenticated" -> new AuthenticatedUserPrincipal(user());
                case "null-principal" -> null;
                case "generic-principal" -> org.springframework.security.core.userdetails.User.withUsername(EMAIL)
                        .password("synthetic-hash").roles("ADMIN").build();
                case "string-principal" -> EMAIL;
                default -> throw new AssertionError(defect);
            };
            when(result.getPrincipal()).thenReturn(principal);
        }
        when(manager.authenticate(any())).thenReturn(result);
        Throwable failure = catchThrowable(() -> service.login(EMAIL, PASSWORD));
        assertThat(failure).isInstanceOf(BadCredentialsException.class)
                .hasMessage("Usuario o contraseña incorrectos").hasNoCause();
        assertThat(failure.toString()).doesNotContain(EMAIL, PASSWORD);
        verifyNoInteractions(policy);
    }

    @ParameterizedTest @ValueSource(strings = {"authentication", "operational"})
    void policyRejectionOrOperationalFailureIsNotReplacedByAnOlderSuccessfulAuthentication(String kind) {
        var principal = new AuthenticatedUserPrincipal(user());
        when(manager.authenticate(any())).thenReturn(authenticated(principal));
        RuntimeException failure = kind.equals("authentication") ? new BadCredentialsException("changed credentials")
                : new IllegalStateException("synthetic unavailable store");
        when(policy.issueSession(17L, 29L, PASSWORD)).thenThrow(failure);
        assertThat(catchThrowable(() -> service.login(EMAIL, PASSWORD))).isSameAs(failure);
        verify(policy).issueSession(17L, 29L, PASSWORD);
    }

    @Test void aChangeAfterAuthenticationIsReadByIdsAndJwtAndResponseShareTheNewSnapshot() {
        User old = user();
        var authenticatedPrincipal = new AuthenticatedUserPrincipal(old);
        User current = user(); current.setEmail("updated@example.invalid"); current.setEmailVerificado(true);
        current.setRole(UserRole.USER); current.cambiarPassword("current-hash"); current.setTokenVersion(42L);
        var persisted = new AtomicReference<>(old);
        var repository = mock(UserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var jwt = mock(JwtUtils.class);
        var sharedPolicy = new AccountSessionPolicy(repository, encoder, jwt);
        service = new AuthService(manager, sharedPolicy);
        when(manager.authenticate(any())).thenAnswer(invocation -> {
            persisted.set(current);
            var authenticated = authenticated(authenticatedPrincipal); authenticated.eraseCredentials(); return authenticated;
        });
        when(repository.findSessionByIdAndTallerId(17L, 29L)).thenAnswer(invocation -> Optional.of(persisted.get()));
        when(encoder.matches(PASSWORD, "current-hash")).thenReturn(true);
        when(jwt.generateToken(any(AuthenticatedUserPrincipal.class), eq(29L))).thenReturn("synthetic-new-token");

        var response = service.login(EMAIL, PASSWORD);
        var issued = ArgumentCaptor.forClass(AuthenticatedUserPrincipal.class);
        var order = inOrder(manager, repository, encoder, jwt);
        order.verify(manager).authenticate(any()); order.verify(repository).findSessionByIdAndTallerId(17L, 29L);
        order.verify(encoder).matches(PASSWORD, "current-hash"); order.verify(jwt).generateToken(issued.capture(), eq(29L));
        assertThat(issued.getValue()).isNotSameAs(authenticatedPrincipal);
        assertThat(issued.getValue().getUsername()).isEqualTo("updated@example.invalid");
        assertThat(issued.getValue().getTokenVersion()).isEqualTo(42L);
        assertThat(issued.getValue().getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertThat(issued.getValue().isEmailVerificado()).isTrue();
        assertThat(response).isEqualTo(new AuthResponseDto("synthetic-new-token", "Bearer", "updated@example.invalid", true));
        verify(repository, never()).findByEmail(any()); verifyNoInteractions(policy);
        // This unit test proves delegation/observation order; the policy IT accredits its own transaction.
    }

    @Test void passwordChangedAfterProviderAuthenticationCannotProduceAJwtFromTheOldPrincipal() {
        User old = user(); var authenticatedPrincipal = new AuthenticatedUserPrincipal(old);
        User current = user(); current.cambiarPassword("changed-password-hash");
        var persisted = new AtomicReference<>(old);
        var repository = mock(UserRepository.class); var encoder = mock(PasswordEncoder.class); var jwt = mock(JwtUtils.class);
        service = new AuthService(manager, new AccountSessionPolicy(repository, encoder, jwt));
        when(manager.authenticate(any())).thenAnswer(invocation -> {
            persisted.set(current); return authenticated(authenticatedPrincipal);
        });
        when(repository.findSessionByIdAndTallerId(17L, 29L)).thenAnswer(invocation -> Optional.of(persisted.get()));
        when(encoder.matches(PASSWORD, "changed-password-hash")).thenReturn(false);

        assertThat(catchThrowable(() -> service.login(EMAIL, PASSWORD))).isInstanceOf(BadCredentialsException.class)
                .hasMessage("Usuario o contraseña incorrectos").hasNoCause();
        verify(encoder).matches(PASSWORD, "changed-password-hash");
        verifyNoInteractions(jwt); verify(repository, never()).findByEmail(any());
    }

    private static UsernamePasswordAuthenticationToken authenticated(AuthenticatedUserPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, PASSWORD, principal.getAuthorities());
    }
    private static User user() {
        return User.builder().id(17L).email(EMAIL).username("Actor").password("original-hash")
                .active(true).role(UserRole.ADMIN).tokenVersion(5L).emailVerificado(false)
                .taller(Taller.builder().id(29L).nombre("Taller").activo(true).build()).build();
    }
}
