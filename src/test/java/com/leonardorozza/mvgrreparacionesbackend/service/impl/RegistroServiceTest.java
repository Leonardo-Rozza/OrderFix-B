package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.RegisterRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Sequence only: the PostgreSQL IT proves the writer returned through a completed transaction. */
@ExtendWith(MockitoExtension.class)
class RegistroServiceTest {
    private static final String PASSWORD = "  exact-password-ñ-123  ";
    private static final RegisterRequestDto REQUEST = new RegisterRequestDto("Taller", null, "Admin", "old@example.invalid", PASSWORD);
    private static final LegacyRegistrationAccountWriter.Identity IDENTITY = new LegacyRegistrationAccountWriter.Identity(91L, 73L);
    @Mock private LegacyRegistrationAccountWriter writer;
    @Mock private AccountVerificationNotifier notifier;
    @Mock private AccountSessionPolicy policy;
    private RegistroService service;

    @BeforeEach void configureService() { service = new RegistroService(writer, notifier, policy); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void notifiesOnceThenReturnsTheCurrentPolicyResponseUsingDurableIdsAndTheExactPassword(boolean verified) {
        var current = new AuthResponseDto("fresh-session-token", "Bearer", "current@example.invalid", verified);
        when(writer.create(REQUEST)).thenReturn(IDENTITY);
        when(policy.issueSession(91L, 73L, PASSWORD)).thenReturn(current);

        var response = service.registrar(REQUEST);

        var ordered = inOrder(writer, notifier, policy);
        ordered.verify(writer).create(REQUEST); ordered.verify(notifier).notifyVerification(91L, 73L);
        ordered.verify(policy).issueSession(91L, 73L, PASSWORD);
        assertThat(response).isSameAs(current); assertThat(response.email()).isNotEqualTo(REQUEST.email());
        verifyNoMoreInteractions(writer, notifier, policy);
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "write", "commit", "unknown-commit"})
    void aWriterThatDoesNotReturnNeverTriggersNotificationOrSession(String phase) {
        RuntimeException failure = switch (phase) {
            case "duplicate" -> new BadRequestException("Ya existe una cuenta con ese email.");
            case "write" -> new DataAccessResourceFailureException("Synthetic SQL failure");
            case "commit" -> new TransactionSystemException("Synthetic failed commit");
            case "unknown-commit" -> new TransactionSystemException("Synthetic lost acknowledgement");
            default -> throw new AssertionError(phase);
        };
        when(writer.create(REQUEST)).thenThrow(failure);

        assertThatThrownBy(() -> service.registrar(REQUEST)).isSameAs(failure);

        verify(writer).create(REQUEST); verifyNoMoreInteractions(writer); verifyNoInteractions(notifier, policy);
    }

    @ParameterizedTest @ValueSource(strings = {"credentials", "database", "jwt"})
    void sessionFailureAfterCreationPropagatesWithoutRecreatingOrNotifyingAgain(String phase) {
        RuntimeException failure = switch (phase) {
            case "credentials" -> new BadCredentialsException("Usuario o contraseña incorrectos");
            case "database" -> new DataAccessResourceFailureException("Synthetic session read failure");
            case "jwt" -> new IllegalStateException("Synthetic JWT failure");
            default -> throw new AssertionError(phase);
        };
        when(writer.create(REQUEST)).thenReturn(IDENTITY);
        when(policy.issueSession(91L, 73L, PASSWORD)).thenThrow(failure);

        assertThatThrownBy(() -> service.registrar(REQUEST)).isSameAs(failure);

        var ordered = inOrder(writer, notifier, policy);
        ordered.verify(writer).create(REQUEST); ordered.verify(notifier).notifyVerification(91L, 73L);
        ordered.verify(policy).issueSession(91L, 73L, PASSWORD);
        verifyNoMoreInteractions(writer, notifier, policy);
    }

    @Test void theFacadeDoesNotDeclareAnEnclosingAccountTransaction() throws Exception {
        assertThat(RegistroService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(RegistroService.class.getMethod("registrar", RegisterRequestDto.class).getAnnotation(Transactional.class)).isNull();
    }
}
