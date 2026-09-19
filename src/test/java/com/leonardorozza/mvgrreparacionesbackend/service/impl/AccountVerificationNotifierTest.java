package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.util.HtmlUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Observes the issuer's public return; PostgreSQL tests independently accredit its actual commit. */
class AccountVerificationNotifierTest {
    private static final String RECIPIENT = "private-recipient@synthetic.invalid";
    private static final String NAME = "Nombre actual";
    private static final String TOKEN = "private-token-for-delivery";
    private static final String URL = "https://synthetic.invalid";

    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> events;

    @BeforeEach void observeOnlyTheNotifierLogger() {
        logger = (Logger) LoggerFactory.getLogger(AccountVerificationNotifier.class);
        previousLevel = logger.getLevel();
        events = new ListAppender<>();
        events.setContext(logger.getLoggerContext());
        events.start();
        // CLI contexts may disable the shared console/root logger. Observe this
        // class directly, then restore its prior level without resetting logging.
        logger.setLevel(Level.WARN);
        logger.addAppender(events);
    }

    @AfterEach void releaseTheObserverAndRestoreTheLogger() {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(previousLevel);
    }

    @Test void sendsTheOriginalVerificationSubjectRouteAndHtmlOnlyAfterTheIssuerReturns() {
        var issuer = mock(AccountVerificationTokenIssuer.class); var sender = mock(EmailSender.class);
        var returned = new AtomicBoolean();
        when(issuer.issue(7L, 19L)).thenAnswer(invocation -> { returned.set(true); return Optional.of(delivery()); });
        doAnswer(invocation -> { assertThat(returned).isTrue(); return null; }).when(sender).enviar(anyString(), anyString(), anyString(), anyString());
        new AccountVerificationNotifier(issuer, sender, URL).notifyVerification(7L, 19L);
        String link = URL + "/verificar-email?token=" + TOKEN;
        var order = inOrder(issuer, sender);
        order.verify(issuer).issue(7L, 19L);
        var text = ArgumentCaptor.forClass(String.class);
        var html = ArgumentCaptor.forClass(String.class);
        order.verify(sender).enviar(eq(RECIPIENT), eq("Confirmá tu email de OrdenFix"), text.capture(), html.capture());
        assertThat(text.getValue()).contains(NAME, link, "48 horas", "Confirmar mi email");
        assertThat(html.getValue()).contains(NAME, "href=\"" + link + "\"", "48 horas", "Confirmar mi email");
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
        verify(sender).enviar(eq(RECIPIENT), eq("Confirmá tu email de OrdenFix"), anyString(), html.capture());
        assertThat(html.getValue()).contains(HtmlUtils.htmlEscape(name),
                HtmlUtils.htmlEscape(base + "/verificar-email?token=" + TOKEN), "vence en 3 horas");
        assertThat(html.getValue()).doesNotContain(name, base + "/verificar-email?token=" + TOKEN);
    }

    @Test void skippedAccountDoesNotSendOrReissueAnything() {
        var issuer = mock(AccountVerificationTokenIssuer.class); var sender = mock(EmailSender.class);
        when(issuer.issue(7L, 19L)).thenReturn(Optional.empty());
        new AccountVerificationNotifier(issuer, sender, URL).notifyVerification(7L, 19L);
        verify(issuer, times(1)).issue(7L, 19L); verifyNoInteractions(sender);
        assertThat(events.list).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"persistence", "commit", "incoherent-result"})
    void issuerOrCommitFailureNeverSendsAndIsLoggedOnlyAsAFixedCategory(String phase) {
        var issuer = mock(AccountVerificationTokenIssuer.class); var sender = mock(EmailSender.class);
        RuntimeException failure = phase.equals("commit")
                ? new TransactionSystemException(TOKEN + RECIPIENT) : new IllegalStateException(TOKEN + RECIPIENT);
        if (phase.equals("incoherent-result")) when(issuer.issue(7L, 19L)).thenReturn(null);
        else when(issuer.issue(7L, 19L)).thenThrow(failure);
        assertThatCode(() -> new AccountVerificationNotifier(issuer, sender, URL).notifyVerification(7L, 19L))
                .doesNotThrowAnyException();
        verify(issuer, times(1)).issue(7L, 19L); verifyNoInteractions(sender);
        assertFixedWarning("TOKEN_ISSUANCE_FAILED");
    }

    @Test void senderFailureIsAbsorbedWithoutReissuingTheAlreadyCommittedToken() {
        var issuer = mock(AccountVerificationTokenIssuer.class); var sender = mock(EmailSender.class);
        when(issuer.issue(7L, 19L)).thenReturn(Optional.of(delivery()));
        doThrow(new IllegalStateException(TOKEN + RECIPIENT + URL)).when(sender).enviar(anyString(), anyString(), anyString(), anyString());
        assertThatCode(() -> new AccountVerificationNotifier(issuer, sender, URL).notifyVerification(7L, 19L))
                .doesNotThrowAnyException();
        verify(issuer, times(1)).issue(7L, 19L); verify(sender, times(1)).enviar(anyString(), anyString(), anyString(), anyString());
        verifyNoMoreInteractions(issuer, sender);
        assertFixedWarning("DELIVERY_FAILED");
    }

    private void assertFixedWarning(String category) {
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLoggerName()).isEqualTo(AccountVerificationNotifier.class.getName());
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMessage()).isEqualTo("Verificación de email omitida: " + category + ".");
            assertThat(event.getFormattedMessage()).isEqualTo(event.getMessage()).doesNotContain(TOKEN, RECIPIENT, URL);
            assertThat(event.getArgumentArray()).isNullOrEmpty();
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs()).isNullOrEmpty();
        });
    }

    private static AccountVerificationTokenIssuer.Delivery delivery() {
        return new AccountVerificationTokenIssuer.Delivery(RECIPIENT, NAME, TOKEN, 48);
    }
}
