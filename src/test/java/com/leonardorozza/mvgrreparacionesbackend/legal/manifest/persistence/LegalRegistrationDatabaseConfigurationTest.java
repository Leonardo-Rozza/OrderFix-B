package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class LegalRegistrationDatabaseConfigurationTest {
    private static final String PREFIX = LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String FLAG = LegalRegistrationDatabaseConfiguration.ENABLED_PROPERTY;
    private static final String HMAC = LegalAcceptanceKeyConfiguration.IDEMPOTENCY_PREFIX;
    private static final String AES = LegalAcceptanceKeyConfiguration.METADATA_PREFIX;
    static final String HMAC_SECRET = secret('1');
    static final String AES_SECRET = secret('2');

    @ParameterizedTest @NullSource @ValueSource(strings = "false")
    void disabledCreatesNoResourcesOrRequiresSecrets(String flag) {
        try (var context = context(flag == null ? Map.of() : Map.of(FLAG, flag))) {
            context.refresh();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalAcceptanceKeyConfiguration.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalRegistrationPreparation.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalRegistrationWriter.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class)).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "TRUE", "False", " true", "true ", "1"})
    void malformedRegistrationFlagFails(String flag) {
        try (var context = unregisteredContext(Map.of(FLAG, flag))) {
            assertThatThrownBy(() -> {
                context.register(LegalRegistrationDatabaseConfiguration.class);
                context.refresh();
            }).isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("El flag de registro legal requiere true o false exactos");
            assertThat(context.isActive()).isFalse();
            assertThat(context.getBeanFactory().containsSingleton("legalRegistrationPool")).isFalse();
        }
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"false", "true"})
    void registrationDoesNotDependOnAccountReadOrPublicHttpFlags(String read) {
        var values = properties();
        if (read != null) values.put(LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY, read);
        try (var context = context(values)) {
            context.refresh();
            assertThat(context.getBean(LegalRegistrationTransactionBoundary.class)).isNotNull();
            assertThat(context.getBeansOfType(LegalPrivateRequirementsReadService.class)).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc-url", "username", "password"})
    void noCredentialsAreInheritedFromWebOrReaders(String missing) {
        Map<String, Object> values = properties();
        values.remove(PREFIX + missing);
        values.put("spring.datasource." + missing, "web-fallback");
        values.put("ordenfix.legal.account-read." + missing, "private-fallback");
        try (var context = context(values)) {
            assertThatThrownBy(context::refresh).hasRootCauseExactlyInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Required key '" + PREFIX + missing + "' not found");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"user=other", "password=other", "socketTimeout=0",
            "options=-c%20search_path%3Devil", "currentSchema=evil", "socket%54imeout=0", "sslfactory=evil"})
    void urlCannotOverrideCredentialDriverOrBounds(String query) {
        Map<String, Object> values = properties();
        values.put(PREFIX + "jdbc-url", "jdbc:postgresql://localhost/test?" + query);
        try (var context = context(values)) {
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"hmac-version", "hmac-key", "aes-version", "aes-key",
            "retention", "ttl", "reuse", "alias", "unbounded", "duplicate", "retention-overflow"})
    void invalidCryptoConfigurationFailsBeforePoolConstructionWithoutSecrets(String variant) {
        Map<String, Object> values = properties();
        switch (variant) {
            case "hmac-version" -> values.put(HMAC + "active-write-version", "2");
            case "hmac-key" -> values.remove(HMAC + "keyring.1");
            case "aes-version" -> values.put(AES + "active-write-version", "01");
            case "aes-key" -> values.put(AES + "keyring.1", "sensitive-invalid-value");
            case "retention" -> values.remove(AES + "retention");
            case "ttl" -> values.put(HMAC + "result-ttl", "PT23H");
            case "reuse" -> values.put(AES + "keyring.1", HMAC_SECRET);
            case "alias" -> values.put(AES + "keyring.01", AES_SECRET);
            case "unbounded" -> { for (int i = 2; i <= 9; i++) values.put(HMAC + "keyring." + i, secret((char) ('1' + i))); }
            case "duplicate" -> values.put(AES + "keyring.2", AES_SECRET);
            case "retention-overflow" -> values.put(AES + "retention", "P999999999999D");
            default -> throw new AssertionError();
        }
        try (var context = context(values)) {
            Throwable failure = catchThrowable(context::refresh);
            assertThat(failure).isNotNull().hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(failure).hasStackTraceContaining("configuración criptográfica");
            var diagnostics = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(diagnostics));
            assertThat(diagnostics.toString()).doesNotContain(HMAC_SECRET, AES_SECRET, "sensitive-invalid-value");
            assertThat(context.getBeanFactory().containsSingleton("legalRegistrationPool")).isFalse();
        }
    }

    @Test
    void isolatedExactGraphIsLazyClosesAndContainsOnlyRegistrationInfrastructure() {
        assertThat(AnnotatedElementUtils.hasAnnotation(LegalRegistrationDatabaseConfiguration.class, Component.class)).isFalse();
        assertThat(LegalRegistrationDatabaseConfiguration.class.isAnnotationPresent(Configuration.class)).isFalse();
        HikariDataSource pool;
        LegalPrivateRequirementsDataSource bounded;
        try (var context = context(properties())) {
            context.refresh();
            pool = context.getBean(HikariDataSource.class);
            bounded = context.getBean(LegalPrivateRequirementsDataSource.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            var manager = context.getBean(DataSourceTransactionManager.class);
            var tx = context.getBean(TransactionTemplate.class);
            assertThat(jdbc.getDataSource()).isSameAs(bounded);
            assertThat(manager.getDataSource()).isSameAs(bounded);
            assertThat(manager).isExactlyInstanceOf(DataSourceTransactionManager.class);
            assertThat(manager.isRollbackOnCommitFailure()).isFalse();
            assertThat(tx.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(tx.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(tx.isReadOnly()).isFalse();
            assertThat(tx.getTimeout()).isEqualTo(25);
            assertThat(bounded.isRegistrationBoundary()).isTrue();
            int remaining = bounded.withinDeadline(d -> d.remainingMillis());
            assertThat(remaining).isBetween(29_000, 30_000);
            var boundary = context.getBean(LegalRegistrationTransactionBoundary.class);
            boundary.requireExactBoundary();
            assertThat(context.getBean(LegalRegistrationWriter.class).usesJdbc(jdbc)).isTrue();
            assertThat(context.getBean(LegalIdempotencyResultStore.class).usesJdbc(jdbc)).isTrue();
            assertThat(context.getBean(LegalRegistrationPreparation.class)).isNotNull();
            assertThat(boundary.usesJdbc(jdbc)).isTrue();
            assertThat(context.getBean(LegalAcceptanceKeyConfiguration.class).toString())
                    .doesNotContain(HMAC_SECRET, AES_SECRET);
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(2);
            assertThat(context.getBeansOfType(LegalManifestDatabaseGate.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalPrivateRequirementsReadService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalAcceptanceHistoryService.class)).isEmpty();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalAcceptanceService.class)).isEmpty();
            assertThat(context.getBeansOfType(com.leonardorozza.mvgrreparacionesbackend.service.impl.RegistroService.class)).isEmpty();
            assertThat(context.getBeansOfType(com.leonardorozza.mvgrreparacionesbackend.service.impl.AccountSessionPolicy.class)).isEmpty();
            assertThat(context.getBeanNamesForAnnotation(Controller.class)).isEmpty();
            assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
            assertThat(pool.getMinimumIdle()).isZero();
            assertThat(pool.getConnectionTimeout()).isEqualTo(1000);
            assertThat(pool.getDataSourceProperties()).containsEntry("socketTimeout", "6")
                    .containsEntry("connectTimeout", "1").containsEntry("cancelSignalTimeout", "1");
            assertThat(ReflectionTestUtils.getField(bounded, "networkTimeoutMillis")).isEqualTo(6_000);
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
        }
        assertThat(pool.isClosed()).isTrue();
        assertThatThrownBy(() -> bounded.withinDeadline(d -> "closed"))
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
    }

    @Test
    void cannotCombineRegistrationAndPrivateDatabaseContexts() {
        var values = properties();
        values.put(LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true");
        values.put(LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", "jdbc:postgresql://localhost/reader");
        values.put(LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "username", "reader");
        values.put(LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "password", "reader-fixture");
        try (var context = context(values)) {
            context.register(LegalPrivateRequirementsDatabaseConfiguration.class);
            assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, -2, 21})
    void preparationUsesTheConfiguredTrialWithoutOpeningThePool(int days) {
        var values = properties();
        values.put("plan.trial-dias", Integer.toString(days));
        try (var context = context(values)) {
            context.refresh();
            var command = com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator.registration(
                    new com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Registration(
                            "Trial fixture", null, "Admin fixture", "trial@test.invalid", "fixture-password"),
                    "sha256:" + "a".repeat(64), java.util.List.of());
            var bounded = context.getBean(LegalPrivateRequirementsDataSource.class);
            var prepared = bounded.withinDeadline(deadline -> context.getBean(LegalRegistrationPreparation.class)
                    .prepare(command, deadline));
            assertThat(java.time.temporal.ChronoUnit.DAYS.between(prepared.startDate(), prepared.trialEndDate())).isEqualTo(days);
            assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .matches(command.registration().password(), prepared.encodedPassword())).isTrue();
            assertThat(context.getBean(HikariDataSource.class).getHikariPoolMXBean().getTotalConnections()).isZero();
        }
    }

    static Map<String, Object> properties() {
        Map<String, Object> values = new HashMap<>();
        values.put(FLAG, "true");
        values.put(PREFIX + "jdbc-url", "jdbc:postgresql://127.0.0.1:1/registration");
        values.put(PREFIX + "username", "registration-fixture");
        values.put(PREFIX + "password", "registration-fixture-password");
        values.put(HMAC + "active-write-version", "1");
        values.put(HMAC + "keyring.1", HMAC_SECRET);
        values.put(AES + "active-write-version", "1");
        values.put(AES + "keyring.1", AES_SECRET);
        values.put(AES + "retention", "PT24H");
        return values;
    }

    static AnnotationConfigApplicationContext context(Map<String, Object> values) {
        var context = unregisteredContext(values);
        try {
            context.register(LegalRegistrationDatabaseConfiguration.class);
            return context;
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }

    private static AnnotationConfigApplicationContext unregisteredContext(Map<String, Object> values) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("registration-test", values));
        return context;
    }

    private static String secret(char value) {
        return Base64.getEncoder().encodeToString(String.valueOf(value).repeat(32).getBytes(StandardCharsets.US_ASCII));
    }
}
