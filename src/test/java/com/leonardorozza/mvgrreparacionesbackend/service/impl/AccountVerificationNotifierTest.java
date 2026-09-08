package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.util.HtmlUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Observes the issuer's public return; PostgreSQL tests independently accredit its actual commit. */
@ExtendWith(OutputCaptureExtension.class)
class AccountVerificationNotifierTest {
    private static final String RECIPIENT = "private-recipient@synthetic.invalid";
    private static final String NAME = "Nombre actual";
    private static final String TOKEN = "private-token-for-delivery";
    private static final String URL = "https://synthetic.invalid";

    @Test void sendsTheOriginalVerificationSubjectRouteAndHtmlOnlyAfterTheIssuerReturns() {
        var issuer = mock(AccountVerificationTokenIssuer.class); var sender = mock(EmailSender.class);
        var returned = new AtomicBoolean();
        when(issuer.issue(7L, 19L)).thenAnswer(invocation -> { returned.set(true); return Optional.of(delivery()); });
        doAnswer(invocation -> { assertThat(returned).isTrue(); return null; }).when(sender).enviar(anyString(), anyString(), anyString());
        new AccountVerificationNotifier(issuer, sender, URL).notifyVerification(7L, 19L);
        String link = URL + "/verificar-email?token=" + TOKEN;
        var order = inOrder(issuer, sender);
        order.verify(issuer).issue(7L, 19L);
        order.verify(sender).enviar(RECIPIENT, "Confirmá tu email de OrdenFix", """
                <p>Hola Nombre actual, ¡bienvenido a OrdenFix!</p>
                <p>Confirmá tu email haciendo clic en el link (vence en 48 horas):</p>
                <p><a href="%s">%s</a></p>
                """.formatted(link, link));
        order.verifyNoMoreInteractions();
    }

    @Test void escapesTheCurrentNameAndCompleteLinkWithoutChangingTheRecipientOrConfiguredHours() {
        var issuer = mock(AccountVerificationTokenIssuer.class); var sender = mock(EmailSender.class);
        String name = "<img src=x> & \"Nombre\"";
        String base = "https://synthetic.invalid/\"quoted\"&value";
        var value = new AccountVerificationTokenIssuer.Delivery(RECIPIENT, name, TOKEN, 3);
        when(issuer.issue(7L, 19L)).thenReturn(Optional.of(value));
        new AccountVerificationNotifier(issuer, sender, base).notifyVerification(7L, 19L);
        var html = ArgumentCaptor.forClass(String.class);
        verify(sender).enviar(eq(RECIPIENT), eq("Confirmá tu email de OrdenFix"), html.capture());
        assertThat(html.getValue()).contains(HtmlUtils.htmlEscape(name),
                HtmlUtils.htmlEscape(base + "/verificar-email?token=" + TOKEN), "vence en 3 horas");
        assertThat(html.getValue()).doesNotContain(name, base + "/verificar-email?token=" + TOKEN);
    }

    @Test void skippedAccountDoesNotSendOrReissueAnything(CapturedOutput output) {
        var issuer = mock(AccountVerificationTokenIssuer.class); var sender = mock(EmailSender.class);
        when(issuer.issue(7L, 19L)).thenReturn(Optional.empty());
        new AccountVerificationNotifier(issuer, sender, URL).notifyVerification(7L, 19L);
        verify(issuer, times(1)).issue(7L, 19L); verifyNoInteractions(sender);
        assertThat(output).doesNotContain("TOKEN_ISSUANCE_FAILED", "DELIVERY_FAILED", TOKEN, RECIPIENT);
    }

    @ParameterizedTest @ValueSource(strings = {"persistence", "commit", "incoherent-result"})
    void issuerOrCommitFailureNeverSendsAndIsLoggedOnlyAsAFixedCategory(String phase, CapturedOutput output) {
        var issuer = mock(AccountVerificationTokenIssuer.class); var sender = mock(EmailSender.class);
        RuntimeException failure = phase.equals("commit")
                ? new TransactionSystemException(TOKEN + RECIPIENT) : new IllegalStateException(TOKEN + RECIPIENT);
        if (phase.equals("incoherent-result")) when(issuer.issue(7L, 19L)).thenReturn(null);
        else when(issuer.issue(7L, 19L)).thenThrow(failure);
        assertThatCode(() -> new AccountVerificationNotifier(issuer, sender, URL).notifyVerification(7L, 19L))
                .doesNotThrowAnyException();
        verify(issuer, times(1)).issue(7L, 19L); verifyNoInteractions(sender);
        assertThat(output).contains("TOKEN_ISSUANCE_FAILED").doesNotContain("DELIVERY_FAILED", TOKEN, RECIPIENT,
                URL, "TransactionSystemException", "IllegalStateException", "NullPointerException");
    }

    @Test void senderFailureIsAbsorbedWithoutReissuingTheAlreadyCommittedToken(CapturedOutput output) {
        var issuer = mock(AccountVerificationTokenIssuer.class); var sender = mock(EmailSender.class);
        when(issuer.issue(7L, 19L)).thenReturn(Optional.of(delivery()));
        doThrow(new IllegalStateException(TOKEN + RECIPIENT + URL)).when(sender).enviar(anyString(), anyString(), anyString());
        assertThatCode(() -> new AccountVerificationNotifier(issuer, sender, URL).notifyVerification(7L, 19L))
                .doesNotThrowAnyException();
        verify(issuer, times(1)).issue(7L, 19L); verify(sender, times(1)).enviar(anyString(), anyString(), anyString());
        verifyNoMoreInteractions(issuer, sender);
        assertThat(output).contains("DELIVERY_FAILED").doesNotContain("TOKEN_ISSUANCE_FAILED", TOKEN, RECIPIENT, URL, "IllegalStateException");
    }

    private static AccountVerificationTokenIssuer.Delivery delivery() {
        return new AccountVerificationTokenIssuer.Delivery(RECIPIENT, NAME, TOKEN, 48);
    }
}
