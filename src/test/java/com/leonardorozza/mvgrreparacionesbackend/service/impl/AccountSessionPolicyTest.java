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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountSessionPolicyTest {

    private static final Long USER_ID = 41L;
    private static final Long TALLER_ID = 7L;
    private static final String CURRENT_EMAIL = "email-actual@test.invalid";
    private static final String PASSWORD = "password-actual-privada";
    private static final String HASH = "hash-actual-privado";

    @Mock
    private UserRepository users;

    @Mock
    private PasswordEncoder passwords;

    @Mock
    private JwtUtils tokens;

    private AccountSessionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new AccountSessionPolicy(users, passwords, tokens);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void issuesFromCurrentDurableIdentityAndPreservesSoftEmailVerification(boolean verified) {
        User current = currentUser(HASH, verified);
        when(users.findSessionByIdAndTallerId(USER_ID, TALLER_ID)).thenReturn(Optional.of(current));
        when(passwords.matches(PASSWORD, HASH)).thenReturn(true);
        when(tokens.generateToken(any(AuthenticatedUserPrincipal.class), eq(TALLER_ID)))
                .thenReturn("fresh-jwt");

        AuthResponseDto response = policy.issueSession(USER_ID, TALLER_ID, PASSWORD);

        var principal = ArgumentCaptor.forClass(AuthenticatedUserPrincipal.class);
        var order = inOrder(users, passwords, tokens);
        order.verify(users).findSessionByIdAndTallerId(USER_ID, TALLER_ID);
        order.verify(passwords).matches(PASSWORD, HASH);
        order.verify(tokens).generateToken(principal.capture(), eq(TALLER_ID));
        assertThat(response).isEqualTo(new AuthResponseDto("fresh-jwt", "Bearer", CURRENT_EMAIL, verified));
        assertThat(principal.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(principal.getValue().getTallerId()).isEqualTo(TALLER_ID);
        assertThat(principal.getValue().getUsername()).isEqualTo(CURRENT_EMAIL);
        assertThat(principal.getValue().getTokenVersion()).isEqualTo(17L);
        assertThat(principal.getValue().isEmailVerificado()).isEqualTo(verified);
        assertThat(principal.getValue().isEnabled()).isTrue();
        assertThat(principal.getValue().getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        verifyNoMoreInteractions(users, passwords, tokens);
    }

    @Test
    void rebuildsPrincipalOnEveryCallUsingCurrentEmailRoleAndTokenVersion() {
        User previous = currentUser("hash-previo", false);
        previous.setEmail("email-previo@test.invalid");
        previous.setRole(UserRole.ADMIN);
        previous.setTokenVersion(16L);
        User current = currentUser(HASH, true);
        when(users.findSessionByIdAndTallerId(USER_ID, TALLER_ID))
                .thenReturn(Optional.of(previous), Optional.of(current));
        when(passwords.matches(PASSWORD, "hash-previo")).thenReturn(true);
        when(passwords.matches(PASSWORD, HASH)).thenReturn(true);
        when(tokens.generateToken(any(AuthenticatedUserPrincipal.class), eq(TALLER_ID)))
                .thenReturn("first-jwt", "second-jwt");

        AuthResponseDto first = policy.issueSession(USER_ID, TALLER_ID, PASSWORD);
        AuthResponseDto second = policy.issueSession(USER_ID, TALLER_ID, PASSWORD);

        var principal = ArgumentCaptor.forClass(AuthenticatedUserPrincipal.class);
        verify(users, times(2)).findSessionByIdAndTallerId(USER_ID, TALLER_ID);
        verify(passwords).matches(PASSWORD, "hash-previo");
        verify(passwords).matches(PASSWORD, HASH);
        verify(tokens, times(2)).generateToken(principal.capture(), eq(TALLER_ID));
        AuthenticatedUserPrincipal before = principal.getAllValues().getFirst();
        AuthenticatedUserPrincipal after = principal.getAllValues().getLast();
        assertThat(after).isNotSameAs(before);
        assertThat(before.getUsername()).isEqualTo("email-previo@test.invalid");
        assertThat(before.getTokenVersion()).isEqualTo(16L);
        assertThat(before.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(after.getUsername()).isEqualTo(CURRENT_EMAIL);
        assertThat(after.getTokenVersion()).isEqualTo(17L);
        assertThat(after.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertThat(first).isEqualTo(new AuthResponseDto("first-jwt", "Bearer", "email-previo@test.invalid", false));
        assertThat(second).isEqualTo(new AuthResponseDto("second-jwt", "Bearer", CURRENT_EMAIL, true));
        verifyNoMoreInteractions(users, passwords, tokens);
    }

    @Test
    void rejectsOldPasswordAgainstTheCurrentHashAndThenAcceptsCurrentPassword() {
        when(users.findSessionByIdAndTallerId(USER_ID, TALLER_ID))
                .thenReturn(Optional.of(currentUser(HASH, false)));
        when(passwords.matches("password-previa", HASH)).thenReturn(false);
        when(passwords.matches(PASSWORD, HASH)).thenReturn(true);
        when(tokens.generateToken(any(AuthenticatedUserPrincipal.class), eq(TALLER_ID)))
                .thenReturn("fresh-jwt");

        assertBadCredentials(() -> policy.issueSession(USER_ID, TALLER_ID, "password-previa"));
        verifyNoInteractions(tokens);
        assertThat(policy.issueSession(USER_ID, TALLER_ID, PASSWORD).token()).isEqualTo("fresh-jwt");

        verify(users, times(2)).findSessionByIdAndTallerId(USER_ID, TALLER_ID);
        verify(passwords).matches("password-previa", HASH);
        verify(passwords).matches(PASSWORD, HASH);
        verify(tokens).generateToken(any(AuthenticatedUserPrincipal.class), eq(TALLER_ID));
        verifyNoMoreInteractions(users, passwords, tokens);
    }

    @Test
    void keepsPasswordWhitespaceExactlyAsSubmitted() {
        String password = "  contraseña-actual \t";
        when(users.findSessionByIdAndTallerId(USER_ID, TALLER_ID))
                .thenReturn(Optional.of(currentUser(HASH, false)));
        when(passwords.matches(password, HASH)).thenReturn(true);
        when(tokens.generateToken(any(AuthenticatedUserPrincipal.class), eq(TALLER_ID)))
                .thenReturn("fresh-jwt");

        assertThat(policy.issueSession(USER_ID, TALLER_ID, password).token()).isEqualTo("fresh-jwt");

        verify(users).findSessionByIdAndTallerId(USER_ID, TALLER_ID);
        verify(passwords).matches(password, HASH);
        verify(tokens).generateToken(any(AuthenticatedUserPrincipal.class), eq(TALLER_ID));
        verifyNoMoreInteractions(users, passwords, tokens);
    }

    @ParameterizedTest(name = "invalid IDs: user={0}, taller={1}")
    @MethodSource("invalidIds")
    void rejectsInvalidIdsBeforeReadingAnything(Long userId, Long tallerId) {
        assertBadCredentials(() -> policy.issueSession(userId, tallerId, PASSWORD));
        verifyNoInteractions(users, passwords, tokens);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t"})
    void rejectsMissingOrBlankPasswordBeforeReadingAnything(String password) {
        assertBadCredentials(() -> policy.issueSession(USER_ID, TALLER_ID, password));
        verifyNoInteractions(users, passwords, tokens);
    }

    @Test
    void rejectsMissingDurableIdentityWithoutLookingUpAnEmail() {
        when(users.findSessionByIdAndTallerId(USER_ID, TALLER_ID)).thenReturn(Optional.empty());

        assertBadCredentials(() -> policy.issueSession(USER_ID, TALLER_ID, PASSWORD));

        verify(users).findSessionByIdAndTallerId(USER_ID, TALLER_ID);
        verifyNoInteractions(passwords, tokens);
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest(name = "invalid current account: {0}")
    @EnumSource(SnapshotFault.class)
    void rejectsInvalidCurrentAccountBeforeCheckingThePasswordOrIssuingJwt(SnapshotFault fault) {
        User current = faultedUser(fault);
        when(users.findSessionByIdAndTallerId(USER_ID, TALLER_ID)).thenReturn(Optional.of(current));

        assertBadCredentials(() -> policy.issueSession(USER_ID, TALLER_ID, PASSWORD));

        verify(users).findSessionByIdAndTallerId(USER_ID, TALLER_ID);
        verifyNoInteractions(passwords, tokens);
        verifyNoMoreInteractions(users);
    }

    @Test
    void propagatesRepositoryFailureWithoutClaimingInvalidCredentialsOrIssuingJwt() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(users.findSessionByIdAndTallerId(USER_ID, TALLER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> policy.issueSession(USER_ID, TALLER_ID, PASSWORD)).isSameAs(failure);

        verify(users).findSessionByIdAndTallerId(USER_ID, TALLER_ID);
        verifyNoInteractions(passwords, tokens);
        verifyNoMoreInteractions(users);
    }

    @Test
    void propagatesPasswordEncoderFailureWithoutIssuingJwt() {
        var failure = new IllegalStateException("password service unavailable");
        when(users.findSessionByIdAndTallerId(USER_ID, TALLER_ID))
                .thenReturn(Optional.of(currentUser(HASH, false)));
        when(passwords.matches(PASSWORD, HASH)).thenThrow(failure);

        assertThatThrownBy(() -> policy.issueSession(USER_ID, TALLER_ID, PASSWORD)).isSameAs(failure);

        verify(users).findSessionByIdAndTallerId(USER_ID, TALLER_ID);
        verify(passwords).matches(PASSWORD, HASH);
        verifyNoInteractions(tokens);
        verifyNoMoreInteractions(users, passwords);
    }

    @Test
    void propagatesJwtFailureWithoutWritingAccountState() {
        var failure = new IllegalStateException("token service unavailable");
        when(users.findSessionByIdAndTallerId(USER_ID, TALLER_ID))
                .thenReturn(Optional.of(currentUser(HASH, false)));
        when(passwords.matches(PASSWORD, HASH)).thenReturn(true);
        when(tokens.generateToken(any(AuthenticatedUserPrincipal.class), eq(TALLER_ID))).thenThrow(failure);

        assertThatThrownBy(() -> policy.issueSession(USER_ID, TALLER_ID, PASSWORD)).isSameAs(failure);

        verify(users).findSessionByIdAndTallerId(USER_ID, TALLER_ID);
        verify(passwords).matches(PASSWORD, HASH);
        verify(tokens).generateToken(any(AuthenticatedUserPrincipal.class), eq(TALLER_ID));
        verifyNoMoreInteractions(users, passwords, tokens);
    }

    private static void assertBadCredentials(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isExactlyInstanceOf(BadCredentialsException.class)
                .hasNoCause()
                .hasMessage("Usuario o contraseña incorrectos");
    }

    private static Stream<Arguments> invalidIds() {
        return Stream.of(
                Arguments.of(null, TALLER_ID),
                Arguments.of(0L, TALLER_ID),
                Arguments.of(-1L, TALLER_ID),
                Arguments.of(USER_ID, null),
                Arguments.of(USER_ID, 0L),
                Arguments.of(USER_ID, -1L));
    }

    private static User currentUser(String hash, boolean verified) {
        return User.builder()
                .id(USER_ID)
                .username("Nombre visible")
                .email(CURRENT_EMAIL)
                .password(hash)
                .role(UserRole.USER)
                .active(true)
                .emailVerificado(verified)
                .tokenVersion(17L)
                .taller(Taller.builder().id(TALLER_ID).nombre("Taller actual").activo(true).build())
                .build();
    }

    private static User faultedUser(SnapshotFault fault) {
        User user = currentUser(switch (fault) {
            case HASH_NULL -> null;
            case HASH_BLANK -> " \t";
            default -> HASH;
        }, false);
        switch (fault) {
            case USER_ID_NULL -> user.setId(null);
            case USER_ID_WRONG -> user.setId(42L);
            case USER_ID_ZERO -> user.setId(0L);
            case USER_INACTIVE -> user.setActive(false);
            case USER_ACTIVE_NULL -> user.setActive(null);
            case TALLER_MISSING -> user.setTaller(null);
            case TALLER_ID_NULL -> user.getTaller().setId(null);
            case TALLER_ID_WRONG -> user.getTaller().setId(8L);
            case TALLER_ID_ZERO -> user.getTaller().setId(0L);
            case TALLER_INACTIVE -> user.getTaller().setActivo(false);
            case TALLER_ACTIVE_NULL -> user.getTaller().setActivo(null);
            case EMAIL_NULL -> user.setEmail(null);
            case EMAIL_BLANK -> user.setEmail(" \t");
            case ROLE_NULL -> user.setRole(null);
            case TOKEN_VERSION_NEGATIVE -> user.setTokenVersion(-1L);
            case HASH_NULL, HASH_BLANK -> { }
        }
        return user;
    }

    private enum SnapshotFault {
        USER_ID_NULL, USER_ID_WRONG, USER_ID_ZERO, USER_INACTIVE, USER_ACTIVE_NULL,
        TALLER_MISSING, TALLER_ID_NULL, TALLER_ID_WRONG, TALLER_ID_ZERO, TALLER_INACTIVE,
        TALLER_ACTIVE_NULL, EMAIL_NULL, EMAIL_BLANK, HASH_NULL, HASH_BLANK, ROLE_NULL,
        TOKEN_VERSION_NEGATIVE
    }
}
