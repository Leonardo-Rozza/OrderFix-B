package com.leonardorozza.mvgrreparacionesbackend.service.email;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/** Exercises MIME creation and adapter failure boundaries without an SMTP connection. */
class SmtpEmailSenderTest {
    private static final String RECIPIENT = "private-recipient@synthetic.invalid";
    private static final String FROM = "OrdenFix <no-reply@synthetic.invalid>";
    private static final String SUBJECT = "Restablecé tu contraseña";
    private static final String TOKEN = "private-reset-token";
    private static final String BODY = "<p>Hola, José &amp; Ana.</p><a href=\"https://synthetic.invalid/?token=" + TOKEN + "\">Continuar</a>";
    private static final String TEXT = "Hola, José & Ana.\r\nContinuar: https://synthetic.invalid/?token=" + TOKEN;
    private static final String PROVIDER_DETAIL = "private-provider-credential";

    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> events;

    @BeforeEach void observeOnlyTheAdapterLogger() {
        logger = (Logger) LoggerFactory.getLogger(SmtpEmailSender.class);
        previousLevel = logger.getLevel();
        events = new ListAppender<>();
        events.setContext(logger.getLoggerContext());
        events.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(events);
    }

    @AfterEach void releaseTheObserver() {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(previousLevel);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disabledSenderDoesNotResolveSmtpOrExposeDeliveryData(boolean alternatives) {
        send(new SmtpEmailSender(provider, false, FROM), alternatives);

        verifyNoInteractions(provider, mail);
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("Email transaccional omitido: mail.enabled=false.");
        });
        assertSanitizedLogs();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void missingSmtpKeepsTheCallerSuccessful(boolean alternatives) {
        assertThatCode(() -> send(sender(), alternatives)).doesNotThrowAnyException();

        verify(provider).getIfAvailable();
        verifyNoInteractions(mail);
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("Email transaccional omitido: SMTP no configurado.");
        });
        assertSanitizedLogs();
    }

    @Test void sendsOneUtf8HtmlMessageWithTheConfiguredFromAndOriginalContent() throws Exception {
        when(provider.getIfAvailable()).thenReturn(mail);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(invocation -> {
            ((MimeMessage) invocation.getArgument(0)).saveChanges();
            return null;
        }).when(mail).send(any(MimeMessage.class));

        sender().enviar(RECIPIENT, SUBJECT, BODY);

        var sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        MimeMessage message = sent.getValue();
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("no-reply@synthetic.invalid");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("OrdenFix");
        assertThat(message.getRecipients(Message.RecipientType.TO)).hasSize(1);
        assertThat(((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress()).isEqualTo(RECIPIENT);
        assertThat(message.getSubject()).isEqualTo(SUBJECT);
        var contentType = new ContentType(message.getContentType());
        assertThat(contentType.getBaseType()).isEqualTo("text/html");
        assertThat(contentType.getParameter("charset")).isEqualToIgnoringCase("UTF-8");
        assertThat(message.getContent()).isEqualTo(BODY);
        verify(provider).getIfAvailable();
        verify(mail).createMimeMessage();
        verifyNoMoreInteractions(provider, mail);
        assertThat(events.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).isEqualTo("Email transaccional enviado."));
        assertSanitizedLogs();
    }

    @ParameterizedTest @CsvSource({
            "provider,false", "creation,false", "send,false",
            "provider,true", "creation,true", "send,true"
    })
    void smtpFailuresNeverEscapeOrLogProviderMessages(String phase, boolean alternatives) {
        String sensitive = PROVIDER_DETAIL + RECIPIENT + SUBJECT + TEXT + BODY;
        if (phase.equals("provider")) {
            when(provider.getIfAvailable()).thenThrow(new BeanCreationException(sensitive));
        } else {
            when(provider.getIfAvailable()).thenReturn(mail);
            if (phase.equals("creation")) {
                when(mail.createMimeMessage()).thenThrow(new IllegalStateException(sensitive));
            } else {
                when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
                doThrow(new MailSendException(sensitive)).when(mail).send(any(MimeMessage.class));
            }
        }

        assertThatCode(() -> send(sender(), alternatives)).doesNotThrowAnyException();

        verify(provider).getIfAvailable();
        if (phase.equals("provider")) verifyNoInteractions(mail);
        else if (phase.equals("creation")) verify(mail, never()).send(any(MimeMessage.class));
        else verify(mail, times(1)).send(any(MimeMessage.class));
        String category = switch (phase) {
            case "provider" -> "BeanCreationException";
            case "creation" -> "IllegalStateException";
            default -> "MailSendException";
        };
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("No se pudo enviar el email transaccional (" + category + ").");
            assertThat(event.getArgumentArray()).containsExactly(category);
        });
        assertSanitizedLogs();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invalidFromDoesNotSendOrExposeTheAddressInLogs(boolean alternatives) {
        when(provider.getIfAvailable()).thenReturn(mail);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        assertThatCode(() -> send(new SmtpEmailSender(provider, true, "invalid@@synthetic.invalid"), alternatives))
                .doesNotThrowAnyException();

        verify(mail, never()).send(any(MimeMessage.class));
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).doesNotContain("invalid@@synthetic.invalid");
        });
        assertSanitizedLogs();
    }

    @Test void sendsExactlyTwoUtf8AlternativesThatSurviveMimeSerialization() throws Exception {
        when(provider.getIfAvailable()).thenReturn(mail);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        sender().enviar(RECIPIENT, SUBJECT, TEXT, BODY);

        var sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        // Re-read the actual MIME bytes, rather than trusting only the in-memory content objects.
        var wire = new ByteArrayOutputStream();
        sent.getValue().writeTo(wire);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(wire.toByteArray()));
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("no-reply@synthetic.invalid");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("OrdenFix");
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress()).isEqualTo(RECIPIENT);
        assertThat(message.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(message.getSubject()).isEqualTo(SUBJECT);
        assertThat(new ContentType(message.getContentType()).getBaseType()).isEqualTo("multipart/alternative");
        assertThat(message.getContent()).isInstanceOf(MimeMultipart.class);
        MimeMultipart content = (MimeMultipart) message.getContent();
        assertThat(content.getCount()).isEqualTo(2);
        for (int index = 0; index < content.getCount(); index++) {
            var part = content.getBodyPart(index);
            var type = new ContentType(part.getContentType());
            assertThat(type.getBaseType()).isEqualTo(index == 0 ? "text/plain" : "text/html");
            assertThat(type.getParameter("charset")).isEqualToIgnoringCase("UTF-8");
            assertThat(part.getContent()).isEqualTo(index == 0 ? TEXT : BODY);
            assertThat(part.getFileName()).isNull();
            assertThat(part.getDisposition()).isNull();
            assertThat(part.getHeader("Content-ID")).isNull();
        }
        verify(provider).getIfAvailable();
        verify(mail).createMimeMessage();
        verifyNoMoreInteractions(provider, mail);
        assertThat(events.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).isEqualTo("Email transaccional enviado."));
        assertSanitizedLogs();
    }

    @Test void defaultAlternativeOverloadPreservesTheExistingHtmlSenderContract() {
        List<List<String>> delivered = new ArrayList<>();
        EmailSender legacy = (para, asunto, cuerpoHtml) -> delivered.add(List.of(para, asunto, cuerpoHtml));

        legacy.enviar(RECIPIENT, SUBJECT, TEXT, BODY);

        assertThat(delivered).containsExactly(List.of(RECIPIENT, SUBJECT, BODY));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invalidRecipientDoesNotSendOrExposeTheAddressInLogs(boolean alternatives) {
        when(provider.getIfAvailable()).thenReturn(mail);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        assertThatCode(() -> {
            if (alternatives) sender().enviar("invalid@@synthetic.invalid", SUBJECT, TEXT, BODY);
            else sender().enviar("invalid@@synthetic.invalid", SUBJECT, BODY);
        }).doesNotThrowAnyException();

        verify(mail, never()).send(any(MimeMessage.class));
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).doesNotContain("invalid@@synthetic.invalid");
        });
        assertSanitizedLogs();
    }

    private void send(SmtpEmailSender sender, boolean alternatives) {
        if (alternatives) sender.enviar(RECIPIENT, SUBJECT, TEXT, BODY);
        else sender.enviar(RECIPIENT, SUBJECT, BODY);
    }

    private SmtpEmailSender sender() { return new SmtpEmailSender(provider, true, FROM); }

    private void assertSanitizedLogs() {
        assertThat(events.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(RECIPIENT, FROM, SUBJECT, TEXT, BODY, TOKEN, PROVIDER_DETAIL);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs()).isNullOrEmpty();
        });
    }
}
