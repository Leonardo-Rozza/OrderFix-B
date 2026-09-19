package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.AuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.AuthTokenRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Real Spring commit/rollback callbacks; the local resource manager and repositories are doubles. */
class CuentaResetSchedulingTest {
    private static final String RECIPIENT = "private-recipient@synthetic.invalid";
    private static final String URL = "https://app.synthetic.invalid";
    private static final String NAME = "Nombre original";
    private final UserRepository users = mock(UserRepository.class);
    private final AuthTokenRepository tokens = mock(AuthTokenRepository.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final AccountVerificationNotifier notifier = mock(AccountVerificationNotifier.class);
    private final CuentaService cuenta = new CuentaService(users, tokens, mock(PasswordEncoder.class), sender,
            notifier, mock(UserSecurityStateLock.class));
    private final ObservedTransactionManager manager = new ObservedTransactionManager();
    private final TransactionTemplate transaction = new TransactionTemplate(manager);
    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> events;

    @BeforeEach void configureServiceAndObserveOnlyItsLogger() {
        ReflectionTestUtils.setField(cuenta, "publicUrl", URL);
        ReflectionTestUtils.setField(cuenta, "resetHoras", 2);
        logger = (Logger) LoggerFactory.getLogger(CuentaService.class);
        previousLevel = logger.getLevel();
        events = new ListAppender<>();
        events.setContext(logger.getLoggerContext());
        events.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(events);
    }

    @AfterEach void releaseState() {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(previousLevel);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.clear();
    }

    @Test void sendsOnlyAfterCommitAndCapturesStringsInsteadOfTheManagedUser() {
        User user = account(NAME);
        when(users.findByEmail(RECIPIENT)).thenReturn(Optional.of(user));
        doAnswer(invocation -> {
            assertThat(manager.commits).isEqualTo(1);
            return null;
        }).when(sender).enviar(anyString(), anyString(), anyString(), anyString());

        transaction.executeWithoutResult(status -> {
            cuenta.olvidePassword(RECIPIENT);
            verifyNoInteractions(sender);
            assertThat(manager.commits).isZero();
            user.setUsername("Nombre reemplazado");
            user.setEmail("replacement@synthetic.invalid");
            ReflectionTestUtils.setField(cuenta, "publicUrl", "https://replacement.synthetic.invalid");
        });

        String html = capturedHtml();
        assertThat(html).contains("Hola " + NAME + ",</p>", "vence en 2 horas", URL + "/reset-password?token=")
                .doesNotContain("Nombre reemplazado", "replacement.synthetic.invalid");
        assertThat(manager.commits).isEqualTo(1);
        assertThat(manager.rollbacks).isZero();
        verifyNoMoreInteractions(sender);
        verifyNoInteractions(notifier);
    }

    @Test void escapesTheDisplayNameAndEntireResetLinkInBothHtmlPositions() {
        String name = "<img src=x> & \"Nombre\"";
        String base = "https://synthetic.invalid/\"quoted\"&value";
        when(users.findByEmail(RECIPIENT)).thenReturn(Optional.of(account(name)));
        ReflectionTestUtils.setField(cuenta, "publicUrl", base);

        transaction.executeWithoutResult(status -> cuenta.olvidePassword(RECIPIENT));

        String html = capturedHtml();
        var token = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(html);
        assertThat(token.find()).isTrue();
        String link = HtmlUtils.htmlEscape(base + "/reset-password?token=" + token.group(1));
        assertThat(html).contains("Hola " + HtmlUtils.htmlEscape(name) + ",</p>",
                "href=\"" + link + "\"", ">" + link + "</a>")
                .doesNotContain(name, base + "/reset-password?token=");
    }

    @Test void rollbackNeverSendsTheUncommittedResetLink() {
        when(users.findByEmail(RECIPIENT)).thenReturn(Optional.of(account(NAME)));

        transaction.executeWithoutResult(status -> {
            cuenta.olvidePassword(RECIPIENT);
            verifyNoInteractions(sender);
            status.setRollbackOnly();
        });

        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isZero();
        verifyNoInteractions(sender, notifier);
    }

    @Test void commitFailureNeverSendsTheUnconfirmedResetLink() {
        when(users.findByEmail(RECIPIENT)).thenReturn(Optional.of(account(NAME)));
        manager.failCommit = true;

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> cuenta.olvidePassword(RECIPIENT)))
                .isInstanceOf(TransactionSystemException.class);

        assertThat(manager.commits).isZero();
        verifyNoInteractions(sender, notifier);
    }

    @Test void tokenPersistenceFailureRollsBackWithoutSchedulingOrSending() {
        when(users.findByEmail(RECIPIENT)).thenReturn(Optional.of(account(NAME)));
        when(tokens.save(any(AuthToken.class))).thenThrow(new IllegalStateException("synthetic persistence failure"));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> cuenta.olvidePassword(RECIPIENT)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isZero();
        verifyNoInteractions(sender, notifier);
    }

    @Test void senderFailureAfterCommitDoesNotBreakTheGenericAccountResponseOrReissueTheToken() {
        when(users.findByEmail(RECIPIENT)).thenReturn(Optional.of(account(NAME)));
        doAnswer(invocation -> {
            assertThat(manager.commits).isEqualTo(1);
            throw new IllegalStateException(RECIPIENT + invocation.getArgument(3));
        }).when(sender).enviar(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> transaction.executeWithoutResult(status -> cuenta.olvidePassword(RECIPIENT)))
                .doesNotThrowAnyException();

        verify(tokens, times(1)).save(any(AuthToken.class));
        verify(sender, times(1)).enviar(eq(RECIPIENT), anyString(), anyString(), anyString());
        assertThat(manager.commits).isEqualTo(1);
        assertThat(manager.rollbacks).isZero();
        assertFixedWarning("DELIVERY_FAILED");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void missingTransactionOrCommitCallbacksCannotDeliverAnUnconfirmedToken(boolean activeTransaction) {
        when(users.findByEmail(RECIPIENT)).thenReturn(Optional.of(account(NAME)));
        TransactionSynchronizationManager.setActualTransactionActive(activeTransaction);

        assertThatCode(() -> cuenta.olvidePassword(RECIPIENT)).doesNotThrowAnyException();

        verifyNoInteractions(sender, notifier);
        assertFixedWarning("COMMIT_CALLBACK_UNAVAILABLE");
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "inactive"})
    void unknownOrInactiveAccountStillDoesNoTokenOrDeliveryWork(String state) {
        User user = account(NAME);
        user.setActive(false);
        when(users.findByEmail(RECIPIENT)).thenReturn(state.equals("absent") ? Optional.empty() : Optional.of(user));

        assertThatCode(() -> transaction.executeWithoutResult(status -> cuenta.olvidePassword(RECIPIENT)))
                .doesNotThrowAnyException();

        verifyNoInteractions(tokens, sender, notifier);
        assertThat(manager.commits).isEqualTo(1);
    }

    private String capturedHtml() {
        var html = ArgumentCaptor.forClass(String.class);
        var text = ArgumentCaptor.forClass(String.class);
        verify(sender).enviar(eq(RECIPIENT), eq("Restablecer tu contraseña de OrdenFix"), text.capture(), html.capture());
        assertThat(text.getValue()).contains("Crear nueva contraseña", "vence en 2 horas", "Tu contraseña sigue igual.");
        return html.getValue();
    }

    private void assertFixedWarning(String category) {
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("Recuperación de contraseña omitida: " + category + ".")
                    .doesNotContain(RECIPIENT, NAME, URL, "token=");
            assertThat(event.getArgumentArray()).isNullOrEmpty();
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs()).isNullOrEmpty();
        });
    }

    private static User account(String name) {
        return User.builder().id(41L).email(RECIPIENT).username(name).active(true).build();
    }

    private static final class ObservedTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;
        private boolean failCommit;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new TransactionSystemException("synthetic commit failure");
            commits++;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
