package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
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

class LegalPublicRequirementsHttpConfigurationTest {

    private static final String HTTP_FLAG = LegalPublicRequirementsHttpConfiguration.ENABLED_PROPERTY;
    private static final String INTERNAL_FLAG = LegalPublicRequirementsDatabaseConfiguration.ENABLED_PROPERTY;
    private static final String PREFIX = LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String CONSUMER_URL = "jdbc:postgresql://127.0.0.1:1/unused_http_requirements";
    private static final String CONSUMER_USERNAME = "isolated_http_requirements";
    private static final String CONSUMER_PASSWORD = "isolated-test-only";

    @Test
    void componentScannedBridgeHasItsOwnExplicitOptInAndDoesNotAnnotateTheDatabaseGraph() {
        Configuration configuration = LegalPublicRequirementsHttpConfiguration.class
                .getAnnotation(Configuration.class);
        ConditionalOnProperty condition = LegalPublicRequirementsHttpConfiguration.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(configuration).isNotNull();
        assertThat(configuration.proxyBeanMethods()).isFalse();
        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly(HTTP_FLAG);
        assertThat(HTTP_FLAG).isEqualTo(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY);
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
        assertThat(LegalPublicRequirementsDatabaseConfiguration.class
                .isAnnotationPresent(Configuration.class)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "internal-only", " true", "true ", " true ", "1", "yes", ""})
    void disabledHttpNeedsNoReaderCredentialsAndDoesNotCreateAnIsolatedContext(String mode) {
        Map<String, Object> properties = new LinkedHashMap<>(webProperties());
        if (mode.equals("internal-only")) {
            properties.put(INTERNAL_FLAG, "true");
        } else if (!mode.equals("absent")) {
            properties.put(HTTP_FLAG, mode);
        }
        try (AnnotationConfigApplicationContext web = webContext(properties);
             MockedConstruction<AnnotationConfigApplicationContext> children =
                     mockConstruction(AnnotationConfigApplicationContext.class)) {
            web.refresh();

            assertThat(children.constructed()).isEmpty();
            assertThat(web.getBeansOfType(LegalPublicRequirementsReadService.class)).isEmpty();
            assertThat(web.getBeansOfType(LegalPublicRequirementsHttpConfiguration.class)).isEmpty();
            assertWebGraphUnchanged(web);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "TrUe"})
    void onlyLiteralTrueIgnoringCaseEnablesTheBridge(String value) {
        Map<String, Object> properties = enabledProperties();
        properties.put(HTTP_FLAG, value);
        try (AnnotationConfigApplicationContext web = webContext(properties)) {
            web.refresh();
            assertThat(web.getBeansOfType(LegalPublicRequirementsReadService.class)).hasSize(1);
            assertThat(isolatedContext(web).isActive()).isTrue();
            assertWebGraphUnchanged(web);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void documentaryAndRequirementsFlagsCreateOnlyTheirIndependentGraphs(boolean documents, boolean requirements) {
        Map<String, Object> properties = enabledProperties();
        properties.put(HTTP_FLAG, Boolean.toString(requirements));
        properties.put(LegalPublicDocumentHttpConfiguration.ENABLED_PROPERTY, Boolean.toString(documents));
        String documentPrefix = LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX;
        properties.put(documentPrefix + "jdbc-url", "jdbc:postgresql://127.0.0.1:1/unused_other_reader");
        properties.put(documentPrefix + "username", "other_http_document_reader");
        properties.put(documentPrefix + "password", "other-document-test-only");
        try (AnnotationConfigApplicationContext web = webContext(properties, true)) {
            web.refresh();
            assertThat(web.getBeansOfType(LegalPublicRequirementsReadService.class))
                    .hasSize(requirements ? 1 : 0);
            assertThat(web.getBeansOfType(LegalPublicDocumentReadService.class))
                    .hasSize(documents ? 1 : 0);
            if (requirements) {
                AnnotationConfigApplicationContext child = isolatedContext(web);
                assertThat(child.getBeansOfType(LegalPublicDocumentReadService.class)).isEmpty();
                assertThat(child.getEnvironment().getProperty(documentPrefix + "username")).isNull();
                assertThat(child.getEnvironment().getProperty(PREFIX + "username")).isEqualTo(CONSUMER_USERNAME);
            }
            if (documents) {
                AnnotationConfigApplicationContext child = (AnnotationConfigApplicationContext)
                        ReflectionTestUtils.getField(web.getBean(LegalPublicDocumentHttpConfiguration.class),
                                "readerContext");
                assertThat(child).isNotNull();
                assertThat(child.getBeansOfType(LegalPublicRequirementsReadService.class)).isEmpty();
                assertThat(child.getEnvironment().getProperty(PREFIX + "username")).isNull();
                assertThat(child.getEnvironment().getProperty(documentPrefix + "username"))
                        .isEqualTo("other_http_document_reader");
                if (requirements) {
                    assertThat(child).isNotSameAs(isolatedContext(web));
                    assertThat(child.getBean(DataSourceTransactionManager.class))
                            .isNotSameAs(isolatedContext(web).getBean(DataSourceTransactionManager.class));
                }
            }
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
            LegalPublicRequirementsReadService facade = web.getBean(LegalPublicRequirementsReadService.class);
            HikariDataSource pool = isolated.getBean("legalPublicRequirementsPool", HikariDataSource.class);

            assertWebGraphUnchanged(web);
            assertThat(facade).isSameAs(isolated.getBean(LegalPublicRequirementsReadService.class));
            assertThat(isolated.getParent()).isNull();
            assertThat(isolated.getBeanFactory()).isNotSameAs(web.getBeanFactory());
            assertThat(isolated.containsBean("webDataSource")).isFalse();
            assertThat(isolated.containsBean("webJdbc")).isFalse();
            assertThat(isolated.containsBean("webTransactionManager")).isFalse();
            assertThat(isolated.containsBean("webPrivateObject")).isFalse();
            assertThat(web.getBeansOfType(LegalPublicRequirementsDatabaseConfiguration.class)).isEmpty();
            assertThat(web.getBeansOfType(ConfigurableApplicationContext.class)).isEmpty();
            assertThat(web.containsBean("legalPublicRequirementsPool")).isFalse();
            assertThat(web.containsBean("legalPublicRequirementsJdbc")).isFalse();
            assertThat(web.containsBean("legalPublicRequirementsTransactionManager")).isFalse();
            assertThat(web.containsBean("legalPublicRequirementsGate")).isFalse();
            for (String bean : List.of("legalPublicRequirementsDataSource", "legalPublicRequirementsStore",
                    "legalPublicRequirementsReader", "legalPublicRequirementsSchemaVerifier",
                    "legalPublicRequirementsPrivilegeVerifier", "legalPublicRequirementsScopeResolver",
                    "legalPublicRequirementsTransactionTemplate")) {
                assertThat(web.containsBean(bean)).as(bean).isFalse();
            }
            assertThat(isolated.getBeansOfType(DataSource.class)).containsOnlyKeys(
                    "legalPublicRequirementsPool", "legalPublicRequirementsDataSource");
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
        properties.put(INTERNAL_FLAG, "false");
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
                    .containsExactly("legal-public-requirements-isolated");
            MapPropertySource source = (MapPropertySource) isolated.getEnvironment().getPropertySources()
                    .iterator().next();
            assertThat(source.getSource()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    PREFIX + "jdbc-url", CONSUMER_URL,
                    PREFIX + "username", CONSUMER_USERNAME,
                    PREFIX + "password", CONSUMER_PASSWORD,
                    INTERNAL_FLAG, "true"));
            for (String key : List.of(HTTP_FLAG, PREFIX + "maximum-pool-size",
                    "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                    "private.web.setting", "java.version", "PATH",
                    "ordenfix.legal.aggregate-context.enabled")) {
                assertThat(isolated.getEnvironment().getProperty(key)).as(key).isNull();
            }
            assertThat(isolated.getBean(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(2);
            assertThat(web.getEnvironment().getProperty(INTERNAL_FLAG)).isEqualTo("false");
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
        HikariDataSource pool = isolated.getBean("legalPublicRequirementsPool", HikariDataSource.class);
        Object bounded = isolated.getBean("legalPublicRequirementsDataSource");
        LegalPublicRequirementsReadService facade = web.getBean(LegalPublicRequirementsReadService.class);
        LegalPublicRequirementsHttpConfiguration configuration =
                web.getBean(LegalPublicRequirementsHttpConfiguration.class);
        assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();

        web.close();

        assertThat(isolated.isActive()).isFalse();
        assertThat(pool.isClosed()).isTrue();
        assertThat(ReflectionTestUtils.getField(bounded, "closed")).isEqualTo(true);
        assertThatCode(configuration::destroy).doesNotThrowAnyException();
        assertThatThrownBy(() -> facade.readRegistration())
                .isInstanceOf(LegalPublicRequirementsReadException.class);
        assertThatThrownBy(() -> configuration.legalPublicRequirementsReadService(configuredEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLaterWebRefreshFailureStillDestroysTheAlreadyCreatedReaderGraph() {
        AtomicReference<AnnotationConfigApplicationContext> child = new AtomicReference<>();
        AtomicReference<HikariDataSource> pool = new AtomicReference<>();
        try (AnnotationConfigApplicationContext web = webContext(enabledProperties())) {
            web.registerBean("laterWebFailure", Object.class, () -> {
                web.getBean(LegalPublicRequirementsReadService.class);
                child.set(isolatedContext(web));
                pool.set(child.get().getBean("legalPublicRequirementsPool", HikariDataSource.class));
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
        LegalPublicRequirementsHttpConfiguration configuration = new LegalPublicRequirementsHttpConfiguration();
        try (MockedConstruction<AnnotationConfigApplicationContext> contexts = mockConstruction(
                AnnotationConfigApplicationContext.class, (candidate, ignored) -> {
                    when(candidate.getEnvironment()).thenReturn(new StandardEnvironment());
                    if (stage.equals("refresh")) {
                        doThrow(original).when(candidate).refresh();
                    } else {
                        when(candidate.getBean(LegalPublicRequirementsReadService.class)).thenThrow(original);
                    }
                })) {
            assertThatThrownBy(() -> configuration.legalPublicRequirementsReadService(configuredEnvironment()))
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
            LegalPublicRequirementsHttpConfiguration configuration = new LegalPublicRequirementsHttpConfiguration();

            assertThatThrownBy(() -> configuration.legalPublicRequirementsReadService(configuredEnvironment()))
                    .isSameAs(original);
            assertThat(original.getSuppressed()).containsExactly(cleanup);
            verify(contexts.constructed().getFirst()).close();
            assertThat(ReflectionTestUtils.getField(configuration, "requirementsContext")).isNull();
        }
    }

    @Test
    void repeatedFacadeRequestsKeepOneOwnedContextAndDestroyIsIdempotent() {
        LegalPublicRequirementsReadService facade = mock(LegalPublicRequirementsReadService.class);
        LegalPublicRequirementsHttpConfiguration configuration = new LegalPublicRequirementsHttpConfiguration();
        try (MockedConstruction<AnnotationConfigApplicationContext> contexts = mockConstruction(
                AnnotationConfigApplicationContext.class, (candidate, ignored) -> {
                    when(candidate.getEnvironment()).thenReturn(new StandardEnvironment());
                    when(candidate.getBean(LegalPublicRequirementsReadService.class)).thenReturn(facade);
                })) {
            assertThat(configuration.legalPublicRequirementsReadService(configuredEnvironment())).isSameAs(facade);
            // A repeated call cannot rotate credentials or create a second context behind the bean factory.
            assertThat(configuration.legalPublicRequirementsReadService(new MockEnvironment())).isSameAs(facade);
            assertThat(contexts.constructed()).hasSize(1);
            AnnotationConfigApplicationContext child = contexts.constructed().getFirst();
            verify(child, times(1)).register(LegalPublicRequirementsDatabaseConfiguration.class);
            verify(child, times(1)).refresh();

            configuration.destroy();
            configuration.destroy();

            verify(child, times(1)).close();
            assertThat(ReflectionTestUtils.getField(configuration, "requirementsContext")).isNull();
            assertThatThrownBy(() -> configuration.legalPublicRequirementsReadService(configuredEnvironment()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(contexts.constructed()).hasSize(1);
        }
    }

    @Test
    void aDestroyFailureStillClearsOwnershipAndForbidsReinitialization() {
        IllegalStateException failure = new IllegalStateException("deliberate child close failure");
        LegalPublicRequirementsHttpConfiguration configuration = new LegalPublicRequirementsHttpConfiguration();
        try (MockedConstruction<AnnotationConfigApplicationContext> contexts = mockConstruction(
                AnnotationConfigApplicationContext.class, (candidate, ignored) -> {
                    when(candidate.getEnvironment()).thenReturn(new StandardEnvironment());
                    when(candidate.getBean(LegalPublicRequirementsReadService.class))
                            .thenReturn(mock(LegalPublicRequirementsReadService.class));
                    doThrow(failure).when(candidate).close();
                })) {
            configuration.legalPublicRequirementsReadService(configuredEnvironment());

            assertThatThrownBy(configuration::destroy).isSameAs(failure);
            assertThatCode(configuration::destroy).doesNotThrowAnyException();
            assertThat(ReflectionTestUtils.getField(configuration, "requirementsContext")).isNull();
            assertThatThrownBy(() -> configuration.legalPublicRequirementsReadService(configuredEnvironment()))
                    .isInstanceOf(IllegalStateException.class);
            verify(contexts.constructed().getFirst(), times(1)).close();
        }
    }

    private static AnnotationConfigApplicationContext webContext(Map<String, Object> properties) {
        return webContext(properties, false);
    }

    private static AnnotationConfigApplicationContext webContext(Map<String, Object> properties,
                                                                 boolean includeDocumentBridge) {
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
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(web, false);
        scanner.addIncludeFilter(new AssignableTypeFilter(LegalPublicRequirementsHttpConfiguration.class));
        if (includeDocumentBridge) {
            scanner.addIncludeFilter(new AssignableTypeFilter(LegalPublicDocumentHttpConfiguration.class));
        }
        scanner.scan(LegalPublicRequirementsHttpConfiguration.class.getPackageName());
        return web;
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
                web.getBean(LegalPublicRequirementsHttpConfiguration.class), "requirementsContext");
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
