package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.AuthTokenRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Callback selection only. PostgreSQL tests accredit real commits, suspension and token visibility. */
class CuentaVerificationSchedulingTest {
    private final UserRepository users = mock(UserRepository.class);
    private final AuthTokenRepository tokens = mock(AuthTokenRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final AccountVerificationNotifier notifier = mock(AccountVerificationNotifier.class);
    private final CuentaService cuenta = new CuentaService(users, tokens, encoder, sender, notifier, mock(UserSecurityStateLock.class));

    @AfterEach void releaseSyntheticTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.clear();
    }

    @Test void withoutAnOuterTransactionDelegatesDirectlyByIdsAndDoesNoTokenWork() {
        cuenta.enviarVerificacion(user(41L, 72L));

        verify(notifier).notifyVerification(41L, 72L);
        verifyNoMoreInteractions(notifier);
        verifyNoInteractions(users, tokens, encoder, sender);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @Test void activeTransactionOnlyNotifiesAtAfterCommitWithTheOriginallyCapturedIds() {
        begin();
        User user = user(41L, 72L);
        cuenta.enviarVerificacion(user);
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertThat(callbacks).hasSize(1);
        verifyNoInteractions(notifier, tokens, sender);

        user.setId(90L);
        user.getTaller().setId(91L);
        user.setEmail("replacement@synthetic.invalid");
        callbacks.getFirst().beforeCommit(false);
        callbacks.getFirst().beforeCompletion();
        verifyNoInteractions(notifier, tokens, sender);
        callbacks.getFirst().afterCommit();
        callbacks.getFirst().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(notifier).notifyVerification(41L, 72L);
        verifyNoMoreInteractions(notifier);
        verifyNoInteractions(users, tokens, encoder, sender);
    }

    @ParameterizedTest @ValueSource(ints = {TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_UNKNOWN})
    void rollbackOrUnknownCompletionNeverDispatches(int completion) {
        begin();
        cuenta.enviarVerificacion(user(41L, 72L));
        var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        callback.beforeCompletion();
        callback.afterCompletion(completion);

        verifyNoInteractions(notifier, users, tokens, encoder, sender);
    }

    @Test void activeTransactionWithoutCommitCallbacksCannotDispatchAnUnconfirmedWelcome() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        cuenta.enviarVerificacion(user(41L, 72L));

        verifyNoInteractions(notifier, users, tokens, encoder, sender);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @Test void resendSelfInvocationSchedulesTheSamePostCommitPath() {
        begin();
        User user = user(41L, 72L);
        when(users.findByEmail("lookup@synthetic.invalid")).thenReturn(Optional.of(user));
        cuenta.reenviarVerificacion("lookup@synthetic.invalid");
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertThat(callbacks).hasSize(1);
        verifyNoInteractions(notifier, tokens, encoder, sender);

        callbacks.getFirst().afterCommit();
        verify(notifier).notifyVerification(41L, 72L);
        verifyNoMoreInteractions(notifier);
        verify(users).findByEmail("lookup@synthetic.invalid");
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "inactive", "verified"})
    void resendStillOmitsUnknownInactiveOrAlreadyVerifiedAccounts(String state) {
        begin();
        User user = user(41L, 72L);
        if (state.equals("inactive")) user.setActive(false);
        if (state.equals("verified")) user.setEmailVerificado(true);
        when(users.findByEmail("lookup@synthetic.invalid"))
                .thenReturn(state.equals("absent") ? Optional.empty() : Optional.of(user));
        cuenta.reenviarVerificacion("lookup@synthetic.invalid");

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verifyNoInteractions(notifier, tokens, encoder, sender);
    }

    @Test void incompleteCallerObjectsDoNotBecomeAnAlternateSourceOfIdentity() {
        cuenta.enviarVerificacion(null);
        cuenta.enviarVerificacion(User.builder().id(41L).build());

        verify(notifier).notifyVerification(null, null);
        verify(notifier).notifyVerification(41L, null);
        verifyNoMoreInteractions(notifier);
        verifyNoInteractions(users, tokens, encoder, sender);
    }

    private static void begin() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static User user(Long userId, Long tallerId) {
        return User.builder().id(userId).taller(Taller.builder().id(tallerId).build())
                .email("original@synthetic.invalid").active(true).emailVerificado(false).build();
    }
}
