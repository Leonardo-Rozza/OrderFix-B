package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Registers only the two opt-in components. No application imports, databases or SMTP connection. */
class ClosureNotificationConfigurationTest {
    private static final String ENABLED = "ordenfix.cuenta.cierre.notifications-enabled";
    private static final String SCHEDULED = "ordenfix.cuenta.cierre.notifications-scheduled";
    private static final String ENABLED_ALIAS = "ORDENFIX_CLOSURE_NOTIFICATIONS_ENABLED";
    private static final String SCHEDULED_ALIAS = "ORDENFIX_CLOSURE_NOTIFICATIONS_SCHEDULED";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources().remove("systemEnvironment");
                context.getEnvironment().getPropertySources().remove("systemProperties");
            })
            .withUserConfiguration(ClosureSmtpNotificationAdapter.class, WorkshopClosureNotificationScheduler.class);

    @Test void missingFlagsRegisterNothingAndRequireNoDependencies() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(ClosureSmtpNotificationAdapter.class)
                .doesNotHaveBean(WorkshopClosureNotificationScheduler.class));
    }

    @Test void mailDisabledCannotBeBypassedByEitherClosureFlag() {
        runner.withPropertyValues("mail.enabled=false", ENABLED + "=true", SCHEDULED + "=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ClosureSmtpNotificationAdapter.class)
                        .doesNotHaveBean(WorkshopClosureNotificationScheduler.class));
    }

    @Test void schedulingCannotBypassTheNotificationOptIn() {
        runner.withPropertyValues("mail.enabled=true", ENABLED + "=false", SCHEDULED + "=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ClosureSmtpNotificationAdapter.class)
                        .doesNotHaveBean(WorkshopClosureNotificationScheduler.class));
    }

    @Test void explicitNotificationOptInWithoutSchedulingRegistersOnlyTheAdapter() {
        var dependencies = new Dependencies();
        withDependencies(runner, dependencies)
                .withPropertyValues("mail.enabled=true", ENABLED + "=true", SCHEDULED + "=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ClosureSmtpNotificationAdapter.class)
                            .hasSingleBean(ClosureNotificationPort.class)
                            .doesNotHaveBean(WorkshopClosureNotificationScheduler.class);
                    dependencies.assertNoWork();
                });
    }

    @Test void allThreeOptInsRegisterBothComponentsWithoutStartingWork() {
        var dependencies = new Dependencies();
        withDependencies(runner, dependencies)
                .withPropertyValues("mail.enabled=true", ENABLED + "=true", SCHEDULED + "=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ClosureSmtpNotificationAdapter.class)
                            .hasSingleBean(ClosureNotificationPort.class)
                            .hasSingleBean(WorkshopClosureNotificationScheduler.class);
                    dependencies.assertNoWork();
                });
    }

    @Test void nonTrueNotificationValuesDoNotActivateEitherComponent() {
        for (String value : new String[]{"", "false", "yes", "1"}) {
            runner.withPropertyValues("mail.enabled=true", ENABLED + "=" + value, SCHEDULED + "=true")
                    .run(context -> assertThat(context).hasNotFailed()
                            .doesNotHaveBean(ClosureSmtpNotificationAdapter.class)
                            .doesNotHaveBean(WorkshopClosureNotificationScheduler.class));
        }
    }

    @Test void publicPropertiesKeepBothFlagsOffWithoutLoadingLocalSecretsOrImports() {
        withPublicProperties(runner).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ClosureSmtpNotificationAdapter.class)
                    .doesNotHaveBean(WorkshopClosureNotificationScheduler.class);
            assertThat(context.getEnvironment().getProperty("mail.enabled", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty(ENABLED, Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty(SCHEDULED, Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty("spring.config.import")).isNull();
            assertThat(context.getEnvironment().getProperty("SPRING_CONFIG_IMPORT")).isNull();
            assertThat(context.getEnvironment().getProperty("spring.mail.password")).isEmpty();
        });
    }

    @Test void documentedEnvironmentAliasesEnableOnlyTheExplicitComponents() {
        var dependencies = new Dependencies();
        var configured = withDependencies(withPublicProperties(runner), dependencies)
                .withPropertyValues("MAIL_ENABLED=true", ENABLED_ALIAS + "=true");
        configured.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ClosureSmtpNotificationAdapter.class)
                    .doesNotHaveBean(WorkshopClosureNotificationScheduler.class);
            assertThat(context.getEnvironment().getProperty(ENABLED, Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getProperty(SCHEDULED, Boolean.class)).isFalse();
            dependencies.assertNoWork();
        });
        configured.withPropertyValues(SCHEDULED_ALIAS + "=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ClosureSmtpNotificationAdapter.class)
                    .hasSingleBean(WorkshopClosureNotificationScheduler.class);
            assertThat(context.getEnvironment().getProperty(SCHEDULED, Boolean.class)).isTrue();
            dependencies.assertNoWork();
        });
    }

    private static ApplicationContextRunner withDependencies(ApplicationContextRunner context, Dependencies dependencies) {
        return context.withBean(JdbcTemplate.class, () -> dependencies.jdbc)
                .withBean(PlatformTransactionManager.class, () -> dependencies.manager)
                .withBean(JavaMailSender.class, () -> dependencies.mail)
                .withBean(WorkshopClosureEffectWorker.class, () -> dependencies.worker)
                .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC))
                .withPropertyValues("mail.from=OrdenFix <notificaciones@synthetic.invalid>",
                        "app.public-url=https://ordenfix.synthetic.invalid");
    }

    private static ApplicationContextRunner withPublicProperties(ApplicationContextRunner context) {
        return context.withInitializer(application -> {
            Properties properties;
            try {
                // Loading this resource as plain properties never follows developer config imports.
                properties = PropertiesLoaderUtils.loadProperties(new FileSystemResource("src/main/resources/application.properties"));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            application.getEnvironment().getPropertySources()
                    .addLast(new PropertiesPropertySource("closure-notification-public-contract", properties));
        });
    }

    private static final class Dependencies {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final JavaMailSender mail = mock(JavaMailSender.class);
        final WorkshopClosureEffectWorker worker = mock(WorkshopClosureEffectWorker.class);

        void assertNoWork() {
            // Spring may invoke JdbcTemplate.afterPropertiesSet during mock registration.
            assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(invocation -> {
                String method = invocation.getMethod().getName();
                return method.startsWith("query") || method.startsWith("update") || method.startsWith("batchUpdate")
                        || method.equals("execute") || method.equals("call");
            });
            verifyNoInteractions(manager, mail, worker);
        }
    }
}
