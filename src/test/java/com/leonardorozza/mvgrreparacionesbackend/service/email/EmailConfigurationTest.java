package com.leonardorozza.mvgrreparacionesbackend.service.email;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class EmailConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withInitializer(app -> {
                // Carga sólo el contrato público: no procesa imports ni secretos del desarrollador.
                Properties properties;
                try {
                    properties = PropertiesLoaderUtils.loadProperties(new FileSystemResource("src/main/resources/application.properties"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                app.getEnvironment().getPropertySources().remove("systemEnvironment");
                app.getEnvironment().getPropertySources().remove("systemProperties");
                app.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource("mail-contract", properties));
            });

    @Test
    void defaultsDoNotEnableDeliveryOrLoadLocalSecrets() {
        context.run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app.getEnvironment().getProperty("mail.enabled", Boolean.class)).isFalse();
            assertThat(app.getEnvironment().getProperty("spring.config.import")).isNull();
            assertThat(app.getEnvironment().getProperty("SPRING_CONFIG_IMPORT")).isNull();
            assertThat(app.getBean(JavaMailSenderImpl.class).getPassword()).isEmpty();
        });
    }

    @Test
    void resendKeyBindsToSmtpWithRequiredTlsAndFiniteTimeoutsWithoutConnecting() {
        context.withPropertyValues("API_KEY_RESEND=synthetic-resend-key", "MAIL_ENABLED=true").run(app -> {
            assertThat(app).hasNotFailed();
            JavaMailSenderImpl sender = app.getBean(JavaMailSenderImpl.class);
            assertThat(sender.getHost()).isEqualTo("smtp.resend.com");
            assertThat(sender.getPort()).isEqualTo(587);
            assertThat(sender.getUsername()).isEqualTo("resend");
            assertThat(sender.getPassword()).isEqualTo("synthetic-resend-key");
            assertThat(sender.getJavaMailProperties())
                    .containsEntry("mail.smtp.auth", "true")
                    .containsEntry("mail.smtp.starttls.enable", "true")
                    .containsEntry("mail.smtp.starttls.required", "true")
                    .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                    .containsEntry("mail.smtp.connectiontimeout", "5000")
                    .containsEntry("mail.smtp.timeout", "10000")
                    .containsEntry("mail.smtp.writetimeout", "10000");
        });
    }

    @Test
    void explicitPasswordWinsAndLocalAliasResolvesWithoutDuplicatingCredentials() {
        context.withPropertyValues("API_KEY_RESEND=synthetic-new-key", "MAIL_PASSWORD=synthetic-explicit-key").run(app ->
                assertThat(app.getBean(JavaMailSenderImpl.class).getPassword()).isEqualTo("synthetic-explicit-key"));
        context.withPropertyValues("API_KEY_RESEND=synthetic-new-key", "MAIL_PASSWORD=${API_KEY_RESEND}").run(app ->
                assertThat(app.getBean(JavaMailSenderImpl.class).getPassword()).isEqualTo("synthetic-new-key"));
    }
}
