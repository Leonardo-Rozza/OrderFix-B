package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsReadException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsReadService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsDatabaseConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalPrivateRequirementsHttpConfigurationTest {

    private static final String HTTP_FLAG = LegalPrivateRequirementsHttpConfiguration.ENABLED_PROPERTY;
    private static final String PREFIX = LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String PUBLIC_DOCUMENT_FLAG = LegalPublicDocumentHttpConfiguration.ENABLED_PROPERTY;
    private static final String PUBLIC_REQUIREMENTS_FLAG = LegalPublicRequirementsHttpConfiguration.ENABLED_PROPERTY;
    private static final String CONSUMER_URL = "jdbc:postgresql://127.0.0.1:1/unused_http_private_requirements";
    private static final String CONSUMER_USERNAME = "isolated_http_private_requirements";
    private static final String CONSUMER_PASSWORD = "isolated-test-only";

    @Test
    void componentScannedBridgeUsesTheStrictAccountReadFlagAndDoesNotAnnotateTheDatabaseGraph() {
        Configuration configuration = LegalPrivateRequirementsHttpConfiguration.class
                .getAnnotation(Configuration.class);
        Conditional condition = LegalPrivateRequirementsHttpConfiguration.class.getAnnotation(Conditional.class);

        assertThat(configuration).isNotNull();
        assertThat(configuration.proxyBeanMethods()).isFalse();
        assertThat(condition).isNotNull();
        assertThat(condition.value()).containsExactly(LegalPrivateRequirementsHttpConfiguration.Enabled.class);
        assertThat(HTTP_FLAG).isEqualTo("ordenfix.legal.account-read.enabled");
        assertThat(HTTP_FLAG).isEqualTo(LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY);
        assertThat(LegalPrivateRequirementsDatabaseConfiguration.class
                .isAnnotationPresent(Configuration.class)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false"})
    void disabledHttpNeedsNoReaderCredentialsEvenWhenBothPublicFlagsAreEnabled(String mode) {
        Map<String, Object> properties = new LinkedHashMap<>(webProperties());
        properties.put(PUBLIC_DOCUMENT_FLAG, "true");
        properties.put(PUBLIC_REQUIREMENTS_FLAG, "true");
        if (!mode.equals("absent")) {
            properties.put(HTTP_FLAG, mode);
        }
        try (AnnotationConfigApplicationContext web = webContext(properties);
             MockedConstruction<AnnotationConfigApplicationContext> children =
                     mockConstruction(AnnotationConfigApplicationContext.class)) {
            web.refresh();

            assertThat(children.constructed()).isEmpty();
            assertThat(web.getBeansOfType(LegalPrivateRequirementsReadService.class)).isEmpty();
            assertThat(web.getBeansOfType(LegalPrivateRequirementsHttpConfiguration.class)).isEmpty();
            assertWebGraphUnchanged(web);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "TrUe", "FALSE", "False", " true", "true ", " true ", "1", "yes", ""})
    void invalidFlagFailsStartupWithoutCreatingAnIsolatedContext(String value) {
        Map<String, Object> properties = new LinkedHashMap<>(webProperties());
        properties.put(HTTP_FLAG, value);
        // Scanning itself evaluates the condition, before a web refresh can acquire any resources.
        AnnotationConfigApplicationContext web = new AnnotationConfigApplicationContext();
        web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("invalid-flag", properties));
        try (web;
             MockedConstruction<AnnotationConfigApplicationContext> children =
                     mockConstruction(AnnotationConfigApplicationContext.class)) {
            assertThatThrownBy(() -> {
                scanPrivateBridge(web);
                web.refresh();
            }).hasStackTraceContaining("El flag de lectura legal requiere true o false exactos")
                    .hasStackTraceContaining(IllegalArgumentException.class.getName());
            assertThat(children.constructed()).isEmpty();
            assertThat(web.getParent()).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void privateFacadeDoesNotDependOnEitherPublicFlag(boolean documents, boolean registration) {
        Map<String, Object> properties = enabledProperties();
        properties.put(PUBLIC_DOCUMENT_FLAG, Boolean.toString(documents));
        properties.put(PUBLIC_REQUIREMENTS_FLAG, Boolean.toString(registration));
        try (AnnotationConfigApplicationContext web = webContext(properties)) {
            web.refresh();
            assertThat(web.getBeansOfType(LegalPrivateRequirementsReadService.class)).hasSize(1);
            AnnotationConfigApplicationContext isolated = isolatedContext(web);
            assertThat(isolated.isActive()).isTrue();
            assertThat(isolated.getEnvironment().getProperty(PUBLIC_DOCUMENT_FLAG)).isNull();
            assertThat(isolated.getEnvironment().getProperty(PUBLIC_REQUIREMENTS_FLAG)).isNull();
            assertWebGraphUnchanged(web);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc-url", "username", "password"})
    void enabledHttpRequiresEveryDedicatedPropertyAndNeverFallsBackToTheWebGraph(String missing) {
        Map<String, Object> properties = enabledProperties();
        String otherPrefix = LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX;
        properties.put(otherPrefix + "jdbc-url", "jdbc:postgresql://127.0.0.1:1/unused_document_fallback");
        properties.put(otherPrefix + "username", "unrelated_document_reader");
        properties.put(otherPrefix + "password", "unrelated-document-test-only");
        String registrationPrefix = LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
        properties.put(registrationPrefix + "jdbc-url",
                "jdbc:postgresql://127.0.0.1:1/unused_registration_fallback");
        properties.put(registrationPrefix + "username", "unrelated_registration_reader");
        properties.put(registrationPrefix + "password", "unrelated-registration-test-only");
        properties.remove(PREFIX + missing);
        try (AnnotationConfigApplicationContext web = webContext(properties);
             MockedConstruction<AnnotationConfigApplicationContext> children =
                     mockConstruction(AnnotationConfigApplicationContext.class)) {
            assertThatThrownBy(web::refresh).isInstanceOf(BeanCreationException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(PREFIX + missing);
            assertThat(children.constructed()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc-url", "username", "password"})
    void blankDedicatedPropertiesFailBeforeCreatingResources(String blank) {
        Map<String, Object> properties = enabledProperties();
        properties.put(PREFIX + blank, " \t ");
        try (AnnotationConfigApplicationContext web = webContext(properties);
             MockedConstruction<AnnotationConfigApplicationContext> children =
                     mockConstruction(AnnotationConfigApplicationContext.class)) {
            assertThatThrownBy(web::refresh).isInstanceOf(BeanCreationException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(PREFIX + blank);
            assertThat(children.constructed()).isEmpty();
        }
    }

    @Test
    void enabledBridgeExposesOnlyTheFacadeAndLeavesExistingWebJdbcBeansUntouched() {
        try (AnnotationConfigApplicationContext web = webContext(enabledProperties())) {
            web.refresh();
            AnnotationConfigApplicationContext isolated = isolatedContext(web);
            LegalPrivateRequirementsReadService facade = web.getBean(LegalPrivateRequirementsReadService.class);
            HikariDataSource pool = isolated.getBean("legalPrivateRequirementsPool", HikariDataSource.class);

            assertWebGraphUnchanged(web);
            assertThat(facade).isSameAs(isolated.getBean(LegalPrivateRequirementsReadService.class));
            assertThat(isolated.getParent()).isNull();
            assertThat(isolated.getBeanFactory()).isNotSameAs(web.getBeanFactory());
            assertThat(isolated.containsBean("webDataSource")).isFalse();
            assertThat(isolated.containsBean("webJdbc")).isFalse();
            assertThat(isolated.containsBean("webTransactionManager")).isFalse();
            assertThat(isolated.containsBean("webPrivateObject")).isFalse();
            assertThat(web.getBeansOfType(LegalPrivateRequirementsDatabaseConfiguration.class)).isEmpty();
            assertThat(web.getBeansOfType(ConfigurableApplicationContext.class)).isEmpty();
            assertThat(web.containsBean("legalPrivateRequirementsPool")).isFalse();
            assertThat(web.containsBean("legalPrivateRequirementsJdbc")).isFalse();
            assertThat(web.containsBean("legalPrivateRequirementsTransactionManager")).isFalse();
            assertThat(web.containsBean("legalPrivateRequirementsGate")).isFalse();
            for (String bean : List.of("legalPrivateRequirementsDataSource", "legalPrivateRequirementsStore",
                    "legalPrivateRequirementsReader", "legalPrivateRequirementsSchemaVerifier",
                    "legalPrivateRequirementsPrivilegeVerifier", "legalPrivateRequirementsScopeResolver",
                    "legalPrivateRequirementsTransactionTemplate", "legalPrivateRequirementsActorReader",
                    "legalPrivateRequirementsRevisionCalculator", "legalPrivateRequirementsProvenanceCalculator",
                    "legalPrivateRequirementsReplayVerifier", "legalPrivateRequirementsBudgets",
                    "legalPrivateRequirementsBoundaryMarker", "legalPrivateRequirementsBoundaryGuard")) {
                assertThat(web.containsBean(bean)).as(bean).isFalse();
            }
            assertThat(isolated.getBeansOfType(DataSource.class)).containsOnlyKeys(
                    "legalPrivateRequirementsPool", "legalPrivateRequirementsDataSource");
            assertThat(pool.getJdbcUrl()).isEqualTo(CONSUMER_URL);
            assertThat(pool.getUsername()).isEqualTo(CONSUMER_USERNAME);
            assertThat(pool.getPassword()).isEqualTo(CONSUMER_PASSWORD);
            assertThat(pool.getMinimumIdle()).isZero();
            assertThat(pool.getInitializationFailTimeout()).isEqualTo(-1);
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
            assertThat(isolated.getBean(JdbcTemplate.class).getDataSource())
                    .isNotSameAs(web.getBean(DataSource.class));
        }
    }

    @Test
    void isolatedEnvironmentContainsExactlyFourSelectedValuesAndNoWebOrSystemSources() {
        Map<String, Object> properties = enabledProperties();
        properties.put(PREFIX + "maximum-pool-size", "80");
        properties.put("private.web.setting", "must-stay-in-web");
        properties.put("ordenfix.legal.aggregate-context.enabled", "true");
        try (AnnotationConfigApplicationContext web = webContext(properties)) {
            web.getEnvironment().setActiveProfiles("web-private");
            web.getEnvironment().setDefaultProfiles("web-default-private");
            web.refresh();
            AnnotationConfigApplicationContext isolated = isolatedContext(web);

            assertThat(isolated.getEnvironment()).isNotSameAs(web.getEnvironment());
            assertThat(isolated.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(isolated.getEnvironment().getDefaultProfiles()).isEmpty();
            assertThat(StreamSupport.stream(isolated.getEnvironment().getPropertySources().spliterator(), false)
                    .map(source -> source.getName()).toList())
                    .containsExactly("legal-private-requirements-isolated");
            MapPropertySource source = (MapPropertySource) isolated.getEnvironment().getPropertySources()
                    .iterator().next();
            assertThat(source.getSource()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    PREFIX + "jdbc-url", CONSUMER_URL,
                    PREFIX + "username", CONSUMER_USERNAME,
                    PREFIX + "password", CONSUMER_PASSWORD,
                    HTTP_FLAG, "true"));
            for (String key : List.of(PUBLIC_DOCUMENT_FLAG, PUBLIC_REQUIREMENTS_FLAG, PREFIX + "maximum-pool-size",
                    "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                    "private.web.setting", "java.version", "PATH",
                    "ordenfix.legal.aggregate-context.enabled")) {
                assertThat(isolated.getEnvironment().getProperty(key)).as(key).isNull();
            }
            assertThat(isolated.getBean(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(2);
            assertThat(web.getEnvironment().getProperty(HTTP_FLAG)).isEqualTo("true");
            assertThat(web.getEnvironment().getActiveProfiles()).containsExactly("web-private");
            assertThat(web.getEnvironment().getDefaultProfiles()).containsExactly("web-default-private");
            assertWebGraphUnchanged(web);
        }
    }

    @Test
    void webShutdownClosesTheOwnedContextPoolAndFacadeWithoutBorrowingAConnection() {
        AnnotationConfigApplicationContext web = webContext(enabledProperties());
        web.refresh();
        AnnotationConfigApplicationContext isolated = isolatedContext(web);
        HikariDataSource pool = isolated.getBean("legalPrivateRequirementsPool", HikariDataSource.class);
        Object bounded = isolated.getBean("legalPrivateRequirementsDataSource");
        LegalPrivateRequirementsReadService facade = web.getBean(LegalPrivateRequirementsReadService.class);
        LegalPrivateRequirementsHttpConfiguration configuration =
                web.getBean(LegalPrivateRequirementsHttpConfiguration.class);
        assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();

        web.close();

        assertThat(isolated.isActive()).isFalse();
        assertThat(pool.isClosed()).isTrue();
        assertThat(ReflectionTestUtils.getField(bounded, "closed")).isEqualTo(true);
        assertThatCode(configuration::destroy).doesNotThrowAnyException();
        assertThatThrownBy(() -> facade.read(null))
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
        assertThatThrownBy(() -> configuration.legalPrivateRequirementsReadService(configuredEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLaterWebRefreshFailureStillDestroysTheAlreadyCreatedReaderGraph() {
        AtomicReference<AnnotationConfigApplicationContext> child = new AtomicReference<>();
        AtomicReference<HikariDataSource> pool = new AtomicReference<>();
        try (AnnotationConfigApplicationContext web = webContext(enabledProperties())) {
            web.registerBean("laterWebFailure", Object.class, () -> {
                web.getBean(LegalPrivateRequirementsReadService.class);
                child.set(isolatedContext(web));
                pool.set(child.get().getBean("legalPrivateRequirementsPool", HikariDataSource.class));
                throw new IllegalStateException("deliberate later web refresh failure");
            });

            assertThatThrownBy(web::refresh).isInstanceOf(BeanCreationException.class)
                    .hasStackTraceContaining("deliberate later web refresh failure");

            assertThat(child.get()).isNotNull();
            assertThat(child.get().isActive()).isFalse();
            assertThat(pool.get().isClosed()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"refresh", "facade"})
    void isolatedInitializationFailureAlwaysClosesTheCandidateContext(String stage) {
        IllegalStateException original = new IllegalStateException("deliberate isolated " + stage + " failure");
        LegalPrivateRequirementsHttpConfiguration configuration = new LegalPrivateRequirementsHttpConfiguration();
        try (MockedConstruction<AnnotationConfigApplicationContext> contexts = mockConstruction(
                AnnotationConfigApplicationContext.class, (candidate, ignored) -> {
                    when(candidate.getEnvironment()).thenReturn(new StandardEnvironment());
                    if (stage.equals("refresh")) {
                        doThrow(original).when(candidate).refresh();
                    } else {
                        when(candidate.getBean(LegalPrivateRequirementsReadService.class)).thenThrow(original);
                    }
                })) {
            assertThatThrownBy(() -> configuration.legalPrivateRequirementsReadService(configuredEnvironment()))
                    .isSameAs(original);

            assertThat(contexts.constructed()).hasSize(1);
            AnnotationConfigApplicationContext candidate = contexts.constructed().getFirst();
            verify(candidate).close();
            assertThat(ReflectionTestUtils.getField(configuration, "requirementsContext")).isNull();
            configuration.destroy();
            verify(candidate, times(1)).close();
        }
    }

    @Test
    void cleanupFailureIsSuppressedWithoutReplacingTheOriginalRefreshError() {
        AssertionError original = new AssertionError("deliberate refresh error");
        IllegalStateException cleanup = new IllegalStateException("deliberate cleanup failure");
        try (MockedConstruction<AnnotationConfigApplicationContext> contexts = mockConstruction(
                AnnotationConfigApplicationContext.class, (candidate, ignored) -> {
                    when(candidate.getEnvironment()).thenReturn(new StandardEnvironment());
                    doThrow(original).when(candidate).refresh();
                    doThrow(cleanup).when(candidate).close();
                })) {
            LegalPrivateRequirementsHttpConfiguration configuration = new LegalPrivateRequirementsHttpConfiguration();

            assertThatThrownBy(() -> configuration.legalPrivateRequirementsReadService(configuredEnvironment()))
                    .isSameAs(original);
            assertThat(original.getSuppressed()).containsExactly(cleanup);
            verify(contexts.constructed().getFirst()).close();
            assertThat(ReflectionTestUtils.getField(configuration, "requirementsContext")).isNull();
        }
    }

    @Test
    void repeatedFacadeRequestsKeepOneOwnedContextAndDestroyIsIdempotent() {
        LegalPrivateRequirementsReadService facade = mock(LegalPrivateRequirementsReadService.class);
        LegalPrivateRequirementsHttpConfiguration configuration = new LegalPrivateRequirementsHttpConfiguration();
        try (MockedConstruction<AnnotationConfigApplicationContext> contexts = mockConstruction(
                AnnotationConfigApplicationContext.class, (candidate, ignored) -> {
                    when(candidate.getEnvironment()).thenReturn(new StandardEnvironment());
                    when(candidate.getBean(LegalPrivateRequirementsReadService.class)).thenReturn(facade);
                })) {
            assertThat(configuration.legalPrivateRequirementsReadService(configuredEnvironment())).isSameAs(facade);
            // A repeated call cannot rotate credentials or create a second context behind the bean factory.
            assertThat(configuration.legalPrivateRequirementsReadService(new MockEnvironment())).isSameAs(facade);
            assertThat(contexts.constructed()).hasSize(1);
            AnnotationConfigApplicationContext child = contexts.constructed().getFirst();
            verify(child, times(1)).register(LegalPrivateRequirementsDatabaseConfiguration.class);
            verify(child, times(1)).refresh();

            configuration.destroy();
            configuration.destroy();

            verify(child, times(1)).close();
            assertThat(ReflectionTestUtils.getField(configuration, "requirementsContext")).isNull();
            assertThatThrownBy(() -> configuration.legalPrivateRequirementsReadService(configuredEnvironment()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(contexts.constructed()).hasSize(1);
        }
    }

    @Test
    void aDestroyFailureStillClearsOwnershipAndForbidsReinitialization() {
        IllegalStateException failure = new IllegalStateException("deliberate child close failure");
        LegalPrivateRequirementsHttpConfiguration configuration = new LegalPrivateRequirementsHttpConfiguration();
        try (MockedConstruction<AnnotationConfigApplicationContext> contexts = mockConstruction(
                AnnotationConfigApplicationContext.class, (candidate, ignored) -> {
                    when(candidate.getEnvironment()).thenReturn(new StandardEnvironment());
                    when(candidate.getBean(LegalPrivateRequirementsReadService.class))
                            .thenReturn(mock(LegalPrivateRequirementsReadService.class));
                    doThrow(failure).when(candidate).close();
                })) {
            configuration.legalPrivateRequirementsReadService(configuredEnvironment());

            assertThatThrownBy(configuration::destroy).isSameAs(failure);
            assertThatCode(configuration::destroy).doesNotThrowAnyException();
            assertThat(ReflectionTestUtils.getField(configuration, "requirementsContext")).isNull();
            assertThatThrownBy(() -> configuration.legalPrivateRequirementsReadService(configuredEnvironment()))
                    .isInstanceOf(IllegalStateException.class);
            verify(contexts.constructed().getFirst(), times(1)).close();
        }
    }

    private static AnnotationConfigApplicationContext webContext(Map<String, Object> properties) {
        AnnotationConfigApplicationContext web = new AnnotationConfigApplicationContext();
        web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("web-test", properties));
        DataSource source = mock(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        web.registerBean("webDataSource", DataSource.class, () -> source);
        web.registerBean("webJdbc", JdbcTemplate.class, () -> jdbc);
        web.registerBean("webTransactionManager", DataSourceTransactionManager.class, () -> manager);
        web.registerBean("webTransactionTemplate", TransactionTemplate.class, () -> transaction);
        web.registerBean("webPrivateObject", Object.class, Object::new);
        scanPrivateBridge(web);
        return web;
    }

    private static void scanPrivateBridge(AnnotationConfigApplicationContext web) {
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(web, false);
        scanner.addIncludeFilter(new AssignableTypeFilter(LegalPrivateRequirementsHttpConfiguration.class));
        scanner.scan(LegalPrivateRequirementsHttpConfiguration.class.getPackageName());
    }

    private static void assertWebGraphUnchanged(AnnotationConfigApplicationContext web) {
        assertThat(web.getBeansOfType(DataSource.class)).containsOnlyKeys("webDataSource");
        assertThat(web.getBeansOfType(JdbcTemplate.class)).containsOnlyKeys("webJdbc");
        assertThat(web.getBeansOfType(DataSourceTransactionManager.class))
                .containsOnlyKeys("webTransactionManager");
        assertThat(web.getBeansOfType(TransactionTemplate.class)).containsOnlyKeys("webTransactionTemplate");
        DataSource dataSource = web.getBean("webDataSource", DataSource.class);
        assertThat(web.getBean(JdbcTemplate.class).getDataSource()).isSameAs(dataSource);
        assertThat(web.getBean(DataSourceTransactionManager.class).getDataSource()).isSameAs(dataSource);
        assertThat(web.getBean(TransactionTemplate.class).getTransactionManager())
                .isSameAs(web.getBean(DataSourceTransactionManager.class));
        verifyNoInteractions(dataSource);
    }

    private static AnnotationConfigApplicationContext isolatedContext(AnnotationConfigApplicationContext web) {
        return (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(
                web.getBean(LegalPrivateRequirementsHttpConfiguration.class), "requirementsContext");
    }

    private static MockEnvironment configuredEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        enabledProperties().forEach((key, value) -> environment.setProperty(key, value.toString()));
        return environment;
    }

    private static Map<String, Object> enabledProperties() {
        Map<String, Object> values = new LinkedHashMap<>(webProperties());
        values.put(HTTP_FLAG, "true");
        values.put(PREFIX + "jdbc-url", CONSUMER_URL);
        values.put(PREFIX + "username", CONSUMER_USERNAME);
        values.put(PREFIX + "password", CONSUMER_PASSWORD);
        return values;
    }

    private static Map<String, Object> webProperties() {
        return Map.of("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/unused_web",
                "spring.datasource.username", "unrelated_web_owner",
                "spring.datasource.password", "unrelated-web-test-only");
    }
}
