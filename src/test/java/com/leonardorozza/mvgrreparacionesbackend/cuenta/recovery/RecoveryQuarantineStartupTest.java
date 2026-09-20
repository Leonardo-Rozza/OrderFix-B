package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real Boot startup discovers the factory registration; no application database or server is used. */
class RecoveryQuarantineStartupTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest @ValueSource(strings = {"true", "TRUE", "false ", "private-marker-value"})
    void markerStopsBootBeforeContextCreationAndAnyBeanOrRunner(String value) {
        var counts = new Counts();
        var application = application(counts, WebApplicationType.SERVLET);
        Throwable failure = catchThrowable(() -> application.run(arguments(null, value)));
        assertBlocked(failure);
        assertNeverCreated(counts);
    }

    @Test void trueLoadedFromConfigDataAlsoStopsBeforeContextCreation() throws Exception {
        Path config = temporaryDirectory.resolve("quarantined.properties");
        Files.writeString(config, RecoveryQuarantineEnvironmentPostProcessor.PROPERTY + "=true\n");
        var counts = new Counts();
        Throwable failure = catchThrowable(() -> application(counts, WebApplicationType.NONE).run(arguments(config, null)));
        assertBlocked(failure);
        assertNeverCreated(counts);
    }

    @Test void unresolvedMarkerPlaceholderCannotLeakOrPermitStartup() throws Exception {
        Path config = temporaryDirectory.resolve("unresolved.properties");
        Files.writeString(config, RecoveryQuarantineEnvironmentPostProcessor.PROPERTY + "=${private_recovery_marker_missing}\n");
        var counts = new Counts();
        Throwable failure = catchThrowable(() -> application(counts, WebApplicationType.NONE).run(arguments(config, null)));
        assertBlocked(failure);
        var rendered = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(rendered));
        assertThat(rendered.toString()).doesNotContain("private_recovery_marker_missing");
        assertNeverCreated(counts);
    }

    @Test void absentMarkerKeepsNormalBeanAndRunnerStartup() {
        var counts = new Counts();
        try (var context = application(counts, WebApplicationType.NONE).run(arguments(null, null))) {
            assertThat(context.isActive()).isTrue();
            assertNormalStartup(counts);
        }
    }

    @Test void exactFalseInConfigDataKeepsNormalBeanAndRunnerStartup() throws Exception {
        Path config = temporaryDirectory.resolve("released.properties");
        Files.writeString(config, RecoveryQuarantineEnvironmentPostProcessor.PROPERTY + "=false\n");
        var counts = new Counts();
        try (var context = application(counts, WebApplicationType.NONE).run(arguments(config, null))) {
            assertThat(context.isActive()).isTrue();
            assertNormalStartup(counts);
        }
    }

    private static SpringApplication application(Counts counts, WebApplicationType webType) {
        var application = new SpringApplication(StartupProbes.class);
        application.setWebApplicationType(webType);
        // Do not inherit real process credentials or load the project's application.properties.
        application.setEnvironment(new MockEnvironment());
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.setApplicationContextFactory(type -> {
            counts.contexts++;
            var context = new AnnotationConfigApplicationContext();
            context.registerBean(Counts.class, () -> counts);
            return context;
        });
        return application;
    }

    private static String[] arguments(Path config, String value) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--spring.config.location=" + (config == null
                ? "optional:classpath:/ordenfix-quarantine-no-config.properties" : config.toUri()));
        arguments.add("--spring.config.import=");
        if (value != null) arguments.add("--" + RecoveryQuarantineEnvironmentPostProcessor.PROPERTY + "=" + value);
        return arguments.toArray(String[]::new);
    }

    private static void assertBlocked(Throwable failure) {
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertThat(root).isInstanceOf(RecoveryQuarantineEnvironmentPostProcessor.StartupBlocked.class)
                .hasNoCause().hasMessage("Inicio bloqueado por la barrera de cuarentena de recuperación.");
    }

    private static void assertNeverCreated(Counts counts) {
        assertThat(counts.contexts).as("no context means no refresh, HTTP startup or application bean creation").isZero();
        assertThat(counts.dataSources).isZero();
        assertThat(counts.flyways).isZero();
        assertThat(counts.runnerBeans).isZero();
        assertThat(counts.runnerCalls).isZero();
    }

    private static void assertNormalStartup(Counts counts) {
        assertThat(counts.contexts).isEqualTo(1);
        assertThat(counts.dataSources).isEqualTo(1);
        assertThat(counts.flyways).isEqualTo(1);
        assertThat(counts.runnerBeans).isEqualTo(1);
        assertThat(counts.runnerCalls).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StartupProbes {
        @Bean DataSource quarantineDataSourceProbe(Counts counts) {
            counts.dataSources++;
            return mock(DataSource.class);
        }
        @Bean Flyway quarantineFlywayProbe(Counts counts) {
            counts.flyways++;
            return mock(Flyway.class);
        }
        @Bean ApplicationRunner quarantineRunnerProbe(Counts counts) {
            counts.runnerBeans++;
            return args -> counts.runnerCalls++;
        }
    }

    static final class Counts {
        int contexts;
        int dataSources;
        int flyways;
        int runnerBeans;
        int runnerCalls;
    }
}
