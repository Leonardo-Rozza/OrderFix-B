package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Neutral checkpoint simulation; transaction completion and physical JDBC are tested by the issuer IT. */
class AccountSessionPolicyCheckpointTest {
    private static final long USER_ID = 51;
    private static final long WORKSHOP_ID = 17;
    private static final String PASSWORD = "  contraseña actual \t";
    private static final String HASH = "synthetic-current-hash";

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void checkpointsSurroundEachDependencyWhileCurrentIdentityAndWireValuesStayUnchanged(boolean verified) {
        var f = new Fixture();
        f.user.setEmailVerificado(verified);
        AuthResponseDto result = f.issue();
        assertThat(result).isEqualTo(new AuthResponseDto("synthetic-jwt", "Bearer", "current@example.test", verified));
        assertThat(f.calls()).containsExactly(Phase.REPOSITORY, Phase.PASSWORD, Phase.JWT);
        for (Phase phase : Phase.values()) {
            int position = f.events.indexOf(phase.name());
            assertThat(f.events.get(position - 1)).as("checkpoint before %s", phase).isEqualTo("CHECK");
            assertThat(f.events.get(position + 1)).as("checkpoint after %s", phase).isEqualTo("CHECK");
        }
        ArgumentCaptor<AuthenticatedUserPrincipal> principal = ArgumentCaptor.forClass(AuthenticatedUserPrincipal.class);
        verify(f.users).findSessionByIdAndTallerId(USER_ID, WORKSHOP_ID);
        verify(f.passwords).matches(PASSWORD, HASH);
        verify(f.tokens).generateToken(principal.capture(), eq(WORKSHOP_ID));
        assertThat(principal.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(principal.getValue().getTallerId()).isEqualTo(WORKSHOP_ID);
        assertThat(principal.getValue().getUsername()).isEqualTo("current@example.test");
        assertThat(principal.getValue().getTokenVersion()).isEqualTo(9);
        assertThat(principal.getValue().isEmailVerificado()).isEqualTo(verified);
        assertThat(principal.getValue().getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        verifyNoMoreInteractions(f.users, f.passwords, f.tokens);
    }

    @Test
    void anUnavailableEntryDoesNotReadValidateCredentialsOrFallBackToTheHistoricalApi() {
        var f = new Fixture();
        f.unavailable = true;
        assertThatThrownBy(() -> f.policy.issueInCurrentTransaction(null, null, null, f.checkpoint))
                .isSameAs(f.checkpointFailure);
        assertThat(f.calls()).isEmpty();
        verifyNoInteractions(f.users, f.passwords, f.tokens);
    }

    @Test
    void nullCheckpointIsRejectedWithoutTouchingDependencies() {
        var f = new Fixture();
        assertThatThrownBy(() -> f.policy.issueInCurrentTransaction(USER_ID, WORKSHOP_ID, PASSWORD, null))
                .isExactlyInstanceOf(NullPointerException.class).hasMessage("checkpoint");
        verifyNoInteractions(f.users, f.passwords, f.tokens);
    }

    @ParameterizedTest @EnumSource(Phase.class)
    void aDependencyThatReturnsAfterExpiryCannotDeliverOrAdvanceToTheNextPhase(Phase phase) {
        var f = new Fixture();
        f.operationHook = current -> { if (current == phase) f.unavailable = true; };
        assertThatThrownBy(f::issue).isSameAs(f.checkpointFailure);
        assertPrefix(f, phase);
        assertThat(f.failedChecks).hasValue(1); // A failed postcheck is not retried as an operation failure.
        assertThat(f.checkpointFailure.getSuppressed()).isEmpty();
    }

    @ParameterizedTest @EnumSource(Rejection.class)
    void postcheckRunsBeforeEmptyFalseOrInvalidSnapshotBecomesBadCredentials(Rejection rejection) {
        var f = new Fixture();
        rejection.apply(f);
        f.operationHook = phase -> { if (phase == rejection.lastOperation) f.unavailable = true; };
        assertThatThrownBy(f::issue).isSameAs(f.checkpointFailure);
        assertPrefix(f, rejection.lastOperation);
        assertThat(f.checkpointFailure.getSuppressed()).isEmpty();
    }

    @ParameterizedTest @EnumSource(Rejection.class)
    void healthyCheckpointsPreserveTheExactExistingCredentialRejection(Rejection rejection) {
        var f = new Fixture();
        rejection.apply(f);
        assertThatThrownBy(f::issue).isExactlyInstanceOf(BadCredentialsException.class)
                .hasMessage("Usuario o contraseña incorrectos").hasNoCause();
        assertPrefix(f, rejection.lastOperation);
    }

    @ParameterizedTest @EnumSource(Phase.class)
    void healthyPostcheckPreservesTheOriginalRuntimeFailureAndStopsFurtherWork(Phase phase) {
        var f = new Fixture();
        f.failAt = phase;
        assertThatThrownBy(f::issue).isSameAs(f.operationFailure);
        assertPrefix(f, phase);
        assertThat(f.events.getLast()).isEqualTo("CHECK");
        assertThat(f.operationFailure.getSuppressed()).isEmpty();
    }

    @ParameterizedTest @EnumSource(Phase.class)
    void expiryDuringAThrowingDependencyDominatesAndRetainsThePrimaryExactlyOnce(Phase phase) {
        var f = new Fixture();
        var originalCleanup = new IllegalStateException("synthetic cleanup cause");
        f.checkpointFailure = new IllegalStateException("synthetic unavailable", originalCleanup);
        f.failAt = phase;
        f.operationHook = current -> { if (current == phase) f.unavailable = true; };
        assertThatThrownBy(f::issue).isSameAs(f.checkpointFailure).hasCause(originalCleanup);
        assertPrefix(f, phase);
        assertThat(f.checkpointFailure.getSuppressed()).containsExactly(f.operationFailure);
        assertThat(f.failedChecks).hasValue(1);
    }

    @Test
    void anIdenticalOperationAndCheckpointFailureDoesNotAttemptSelfSuppression() {
        var f = new Fixture();
        f.failAt = Phase.REPOSITORY;
        f.checkpointFailure = f.operationFailure;
        f.operationHook = phase -> f.unavailable = true;
        assertThatThrownBy(f::issue).isSameAs(f.operationFailure);
        assertThat(f.operationFailure.getSuppressed()).isEmpty();
        assertPrefix(f, Phase.REPOSITORY);
    }

    @ParameterizedTest @EnumSource(Phase.class)
    void errorFromADependencyKeepsIdentityAndIsNotReplacedByALaterCheckpoint(Phase phase) {
        var f = new Fixture();
        var fatal = new AssertionError("synthetic fatal dependency failure");
        f.operationHook = current -> {
            if (current == phase) {
                f.unavailable = true;
                throw fatal;
            }
        };
        assertThatThrownBy(f::issue).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).isEmpty();
        assertPrefix(f, phase);
        assertThat(f.events.getLast()).isEqualTo(phase.name());
        assertThat(f.failedChecks).hasValue(0);
    }

    @Test
    void historicalEntryUsesNoopWithoutChangingItsConstructorQueriesPasswordOrResult() {
        var f = new Fixture();
        f.unavailable = true;
        AuthResponseDto result = f.policy.issueSession(USER_ID, WORKSHOP_ID, PASSWORD);
        assertThat(result).isEqualTo(new AuthResponseDto("synthetic-jwt", "Bearer", "current@example.test", false));
        assertThat(f.events).containsExactly("REPOSITORY", "PASSWORD", "JWT");
        verify(f.users).findSessionByIdAndTallerId(USER_ID, WORKSHOP_ID);
        verify(f.passwords).matches(PASSWORD, HASH);
        verify(f.tokens).generateToken(any(AuthenticatedUserPrincipal.class), eq(WORKSHOP_ID));
        verifyNoMoreInteractions(f.users, f.passwords, f.tokens);
    }

    @ParameterizedTest @EnumSource(Phase.class)
    void historicalNoopAlsoPreservesRuntimeFailureIdentityWithoutSuppression(Phase phase) {
        var f = new Fixture();
        f.failAt = phase;
        assertThatThrownBy(() -> f.policy.issueSession(USER_ID, WORKSHOP_ID, PASSWORD)).isSameAs(f.operationFailure);
        assertThat(f.operationFailure.getSuppressed()).isEmpty();
        assertThat(f.events).doesNotContain("CHECK");
        assertPrefix(f, phase);
    }

    @Test
    void onlyTheUnchangedPublicEntryDeclaresATransactionAndTheCoreStaysPackagePrivate() throws Exception {
        var publicEntry = AccountSessionPolicy.class.getDeclaredMethod("issueSession", Long.class, Long.class, String.class);
        var core = AccountSessionPolicy.class.getDeclaredMethod("issueInCurrentTransaction", Long.class, Long.class, String.class, Runnable.class);
        Transactional transaction = publicEntry.getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transaction.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.timeout()).isEqualTo(-1);
        assertThat(Modifier.isPublic(publicEntry.getModifiers())).isTrue();
        assertThat(core.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)).isZero();
        assertThat(core.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(AccountSessionPolicy.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(Arrays.stream(AccountSessionPolicy.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Transactional.class)).map(java.lang.reflect.Method::getName).toList())
                .containsExactly("issueSession");
        assertThat(AccountSessionPolicy.class.getConstructor(UserRepository.class, PasswordEncoder.class, JwtUtils.class)).isNotNull();
    }

    private static void assertPrefix(Fixture fixture, Phase last) {
        assertThat(fixture.calls()).containsExactly(Arrays.copyOf(Phase.values(), last.ordinal() + 1));
    }

    private enum Phase { REPOSITORY, PASSWORD, JWT }

    private enum Rejection {
        MISSING_ACCOUNT(Phase.REPOSITORY), INVALID_ACCOUNT(Phase.REPOSITORY), WRONG_PASSWORD(Phase.PASSWORD);
        final Phase lastOperation;
        Rejection(Phase lastOperation) { this.lastOperation = lastOperation; }
        void apply(Fixture fixture) {
            switch (this) {
                case MISSING_ACCOUNT -> fixture.missingAccount = true;
                case INVALID_ACCOUNT -> fixture.user.setActive(false);
                case WRONG_PASSWORD -> fixture.passwordMatches = false;
            }
        }
    }

    private static final class Fixture {
        final UserRepository users = mock(UserRepository.class);
        final PasswordEncoder passwords = mock(PasswordEncoder.class);
        final JwtUtils tokens = mock(JwtUtils.class);
        final AccountSessionPolicy policy = new AccountSessionPolicy(users, passwords, tokens);
        final List<String> events = new ArrayList<>();
        final AtomicInteger failedChecks = new AtomicInteger();
        final User user = User.builder().id(USER_ID).username("Current visible name").email("current@example.test")
                .password(HASH).role(UserRole.ADMIN).active(true).emailVerificado(false).tokenVersion(9L)
                .taller(Taller.builder().id(WORKSHOP_ID).nombre("Current workshop").activo(true).build()).build();
        boolean missingAccount;
        boolean passwordMatches = true;
        boolean unavailable;
        Phase failAt;
        RuntimeException operationFailure = new IllegalArgumentException("synthetic dependency failure");
        RuntimeException checkpointFailure = new IllegalStateException("synthetic unavailable");
        Consumer<Phase> operationHook = phase -> { };
        final Runnable checkpoint = () -> {
            events.add("CHECK");
            if (unavailable) {
                failedChecks.incrementAndGet();
                throw checkpointFailure;
            }
        };

        Fixture() {
            when(users.findSessionByIdAndTallerId(USER_ID, WORKSHOP_ID)).thenAnswer(call -> {
                operation(Phase.REPOSITORY);
                return missingAccount ? Optional.empty() : Optional.of(user);
            });
            when(passwords.matches(PASSWORD, HASH)).thenAnswer(call -> {
                operation(Phase.PASSWORD);
                return passwordMatches;
            });
            when(tokens.generateToken(any(AuthenticatedUserPrincipal.class), eq(WORKSHOP_ID))).thenAnswer(call -> {
                operation(Phase.JWT);
                return "synthetic-jwt";
            });
        }

        void operation(Phase phase) {
            events.add(phase.name());
            operationHook.accept(phase);
            if (failAt == phase) throw operationFailure;
        }

        AuthResponseDto issue() { return policy.issueInCurrentTransaction(USER_ID, WORKSHOP_ID, PASSWORD, checkpoint); }
        List<Phase> calls() { return events.stream().filter(event -> !event.equals("CHECK")).map(Phase::valueOf).toList(); }
    }
}
