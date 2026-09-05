package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class LegalPublicDocumentReadDatabaseConfigurationTest {

    private static final String ENABLED =
            LegalPublicDocumentReadDatabaseConfiguration.ENABLED_PROPERTY;
    private static final String PREFIX =
            LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String UNCONNECTED_URL = "jdbc:postgresql://127.0.0.1:1/unused_legal_reader";
    private static final String READER_USER = "legal_reader_configuration_test";
    private static final String READER_PASSWORD = "configuration-test-only";
    private final LegalPublicDocumentReadDatabaseConfiguration configuration =
            new LegalPublicDocumentReadDatabaseConfiguration();

    @Test
    void requiresExplicitRegistrationAndAnExplicitTrueContextFlag() {
        Class<?> type = LegalPublicDocumentReadDatabaseConfiguration.class;
        ConditionalOnProperty conditional = type.getAnnotation(ConditionalOnProperty.class);

        assertThat(conditional).isNotNull();
        assertThat(conditional.name()).containsExactly(ENABLED);
        assertThat(conditional.havingValue()).isEqualTo("true");
        assertThat(conditional.matchIfMissing()).isFalse();
        assertThat(type.isAnnotationPresent(Configuration.class)).isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(type, Component.class)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "http-only"})
    void registersNoReaderBeansWhenItsOwnContextIsNotEnabled(String mode) {
        Map<String, Object> properties = new LinkedHashMap<>(webProperties());
        if (mode.equals("false")) {
            properties.put(ENABLED, "false");
        } else if (mode.equals("http-only")) {
            properties.put("ordenfix.legal.public-documents.enabled", "true");
        }

        try (AnnotationConfigApplicationContext context = newContext(properties)) {
            context.refresh();

            assertThat(context.getBeansOfType(LegalPublicDocumentReadDatabaseConfiguration.class)).isEmpty();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
            assertThat(context.getBeansOfType(DataSourceTransactionManager.class)).isEmpty();
            assertThat(context.getBeansOfType(TransactionTemplate.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalManifestDatabaseGate.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalPublicDocumentReadService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.Guard.class)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc-url", "username", "password"})
    void missingDedicatedPropertiesNeverFallBackToWebCredentials(String missing) {
        MockEnvironment environment = new MockEnvironment();
        webProperties().forEach((key, value) -> environment.setProperty(key, value.toString()));
        dedicatedProperties().forEach((key, value) -> {
            if (!key.equals(PREFIX + missing)) {
                environment.setProperty(key, value.toString());
            }
        });

        assertThatIllegalStateException()
                .isThrownBy(() -> configuration.legalPublicDocumentPool(environment))
                .withMessageContaining(PREFIX + missing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc-url", "username", "password"})
    void blankDedicatedPropertiesAreRejected(String blank) {
        MockEnvironment environment = configuredEnvironment();
        environment.setProperty(PREFIX + blank, " \t ");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> configuration.legalPublicDocumentPool(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "user=another_role",
            "username=another_role",
            "password=another_password",
            "%75ser=another_role",
            "u%73er=another_role",
            "connectTimeout=600",
            "loginTimeout=600",
            "socketTimeout=0",
            "cancelSignalTimeout=600",
            "socketFactory=example.UntrustedSocketFactory",
            "socketFactoryArg=untrusted",
            "sslfactory=example.UntrustedSslFactory",
            "sslhostnameverifier=example.UntrustedHostnameVerifier",
            "currentSchema=untrusted",
            "options=-c%20search_path%3Duntrusted",
            "%6fptions=-c%20search_path%3Duntrusted",
            "ApplicationName=other_context",
            "defaultRowFetchSize=0",
            "maxResultBuffer=1G",
            "readOnly=false",
            "sslmode=disable&user=another_role"
    })
    void rejectsUrlOptionsThatCanReplaceIdentitySessionPolicyDriverOrBudgets(String query) {
        MockEnvironment environment = configuredEnvironment();
        environment.setProperty(PREFIX + "jdbc-url", UNCONNECTED_URL + "?" + query);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> configuration.legalPublicDocumentPool(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:h2:mem:reader",
            "jdbc:postgresql:implicit_database",
            "postgresql://127.0.0.1:1/reader",
            "jdbc:postgresql://127.0.0.1:1/reader#user=another_role"
    })
    void requiresAnExplicitPostgresUrlWithoutFragments(String url) {
        MockEnvironment environment = configuredEnvironment();
        environment.setProperty(PREFIX + "jdbc-url", url);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> configuration.legalPublicDocumentPool(environment));
    }

    @Test
    void acceptsTheTransportAllowlistWithoutChangingDedicatedCredentialsOrBudgets() {
        String url = UNCONNECTED_URL + "?sslmode=verify-full&sslrootcert=/unused/root.crt"
                + "&sslcert=/unused/client.crt&sslkey=/unused/client.key"
                + "&sslpassword=transport-test-only&loggerLevel=OFF";
        MockEnvironment environment = configuredEnvironment();
        environment.setProperty(PREFIX + "jdbc-url", url);
        environment.setProperty("spring.datasource.hikari.maximum-pool-size", "80");
        environment.setProperty(PREFIX + "maximum-pool-size", "80");
        environment.setProperty(PREFIX + "connection-timeout", "60000");

        // minimumIdle=0 and initializationFailTimeout=-1 start no connection attempt. Never borrow.
        try (HikariDataSource pool = configuration.legalPublicDocumentPool(environment)) {
            assertThat(pool.getJdbcUrl()).isEqualTo(url);
            assertThat(pool.getUsername()).isEqualTo(READER_USER);
            assertThat(pool.getPassword()).isEqualTo(READER_PASSWORD);
            assertPoolPolicy(pool);
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
        }
    }

    @Test
    void explicitlyEnabledContextAssemblesOneRestrictedReadOnlyGraphWithoutOpeningConnections() {
        Map<String, Object> properties = new LinkedHashMap<>(webProperties());
        properties.putAll(dedicatedProperties());
        properties.put(ENABLED, "true");

        // This creates and promptly closes an empty pool; no operation obtains a connection.
        try (AnnotationConfigApplicationContext context = newContext(properties)) {
            context.refresh();
            HikariDataSource pool = context.getBean("legalPublicDocumentPool", HikariDataSource.class);
            LegalPublicDocumentDataSource dataSource = context.getBean(LegalPublicDocumentDataSource.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            DataSourceTransactionManager manager = context.getBean(DataSourceTransactionManager.class);
            TransactionTemplate transaction = context.getBean(TransactionTemplate.class);
            LegalDatabaseBudgets budgets = context.getBean(LegalDatabaseBudgets.class);
            LegalV28AggregateSchemaVerifier schema = context.getBean(LegalV28AggregateSchemaVerifier.class);
            LegalPublicDocumentPrivilegeVerifier privileges =
                    context.getBean(LegalPublicDocumentPrivilegeVerifier.class);
            LegalManifestDatabaseGate gate = context.getBean(LegalManifestDatabaseGate.class);
            LegalPublicDocumentReader reader = context.getBean(LegalPublicDocumentReader.class);

            assertPoolPolicy(pool);
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
            assertThat(context.getBeansOfType(DataSource.class)).containsOnlyKeys(
                    "legalPublicDocumentPool", "legalPublicDocumentDataSource");
            assertThat(jdbc.getDataSource()).isSameAs(dataSource).isNotSameAs(pool);
            assertThat(manager.getDataSource()).isSameAs(dataSource);
            assertThat(manager.isEnforceReadOnly()).isTrue();
            assertThat(manager.isRollbackOnCommitFailure()).isFalse();
            assertThat(transaction.getTransactionManager()).isSameAs(manager);
            assertThat(transaction.getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(transaction.getIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(transaction.isReadOnly()).isTrue();
            assertThat(transaction.getTimeout()).isEqualTo(15);
            assertThat(budgets).isEqualTo(new LegalDatabaseBudgets(15, 5, 1, 1));
            assertThatCode(() -> gate.requireExactPublicDocumentReadBoundary(jdbc, schema, privileges))
                    .doesNotThrowAnyException();
            assertThat(gate.usesJdbc(jdbc)).isTrue();
            assertThat(reader.usesJdbc(jdbc)).isTrue();
            assertThat(schema.usesJdbc(jdbc)).isTrue();
            assertThat(privileges.usesJdbc(jdbc)).isTrue();
            assertThat(context.getBeansOfType(LegalPublicDocumentReadService.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class).values())
                    .extracting(LegalDatabaseBoundaryMarker::kind)
                    .containsExactly(LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ);
            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.Guard.class)).hasSize(1);
        }
    }

    @Test
    void configurationGuardRejectsMissingMixedOrImpersonatedBoundaries() {
        LegalDatabaseBoundaryMarker reader = configuration.legalPublicDocumentBoundaryMarker();
        LegalDatabaseBoundaryMarker aggregate =
                new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.AGGREGATE);

        assertThatCode(() -> configuration.legalPublicDocumentBoundaryGuard(List.of(reader)))
                .doesNotThrowAnyException();
        assertThatIllegalStateException().isThrownBy(
                () -> configuration.legalPublicDocumentBoundaryGuard(List.of()));
        assertThatIllegalStateException().isThrownBy(
                () -> configuration.legalPublicDocumentBoundaryGuard(List.of(reader, aggregate)));
        assertThatIllegalStateException().isThrownBy(
                () -> configuration.legalPublicDocumentBoundaryGuard(List.of(aggregate)));
    }

    private static void assertPoolPolicy(HikariDataSource pool) {
        assertThat(pool.getPoolName()).isEqualTo("legal-public-document-read");
        assertThat(pool.getDriverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
        assertThat(pool.getMinimumIdle()).isZero();
        assertThat(pool.getConnectionTimeout()).isEqualTo(1_000);
        assertThat(pool.getValidationTimeout()).isEqualTo(1_000);
        assertThat(pool.getInitializationFailTimeout()).isEqualTo(-1);
        assertThat(pool.isReadOnly()).isTrue();
        assertThat(pool.getDataSourceProperties()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "connectTimeout", "1",
                "loginTimeout", "1",
                "socketTimeout", "5",
                "cancelSignalTimeout", "1",
                "ApplicationName", "ordenfix-legal-public-document-read"));
    }

    private static AnnotationConfigApplicationContext newContext(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("reader-configuration-test", properties));
        context.register(LegalPublicDocumentReadDatabaseConfiguration.class);
        return context;
    }

    private static MockEnvironment configuredEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        webProperties().forEach((key, value) -> environment.setProperty(key, value.toString()));
        dedicatedProperties().forEach((key, value) -> environment.setProperty(key, value.toString()));
        return environment;
    }

    private static Map<String, Object> dedicatedProperties() {
        return Map.of(PREFIX + "jdbc-url", UNCONNECTED_URL,
                PREFIX + "username", READER_USER,
                PREFIX + "password", READER_PASSWORD);
    }

    private static Map<String, Object> webProperties() {
        return Map.of("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/unused_web",
                "spring.datasource.username", "unrelated_web_owner",
                "spring.datasource.password", "web-configuration-test-only");
    }
}
