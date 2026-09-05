package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.util.UUID;
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

class LegalPublicDocumentHttpConfigurationTest {

    private static final String HTTP_FLAG = LegalPublicDocumentHttpConfiguration.ENABLED_PROPERTY;
    private static final String INTERNAL_FLAG = LegalPublicDocumentReadDatabaseConfiguration.ENABLED_PROPERTY;
    private static final String PREFIX = LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String READER_URL = "jdbc:postgresql://127.0.0.1:1/unused_http_reader";
    private static final String READER_USERNAME = "isolated_http_reader";
    private static final String READER_PASSWORD = "isolated-test-only";

    @Test
    void componentScannedBridgeHasItsOwnExplicitOptInAndDoesNotAnnotateTheDatabaseGraph() {
        Configuration configuration = LegalPublicDocumentHttpConfiguration.class
                .getAnnotation(Configuration.class);
        ConditionalOnProperty condition = LegalPublicDocumentHttpConfiguration.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(configuration).isNotNull();
        assertThat(configuration.proxyBeanMethods()).isFalse();
        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly(HTTP_FLAG);
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
        assertThat(LegalPublicDocumentReadDatabaseConfiguration.class
                .isAnnotationPresent(Configuration.class)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "internal-only"})
    void disabledHttpNeedsNoReaderCredentialsAndDoesNotCreateAnIsolatedContext(String mode) {
        Map<String, Object> properties = new LinkedHashMap<>(webProperties());
        if (mode.equals("false")) {
            properties.put(HTTP_FLAG, "false");
        } else if (mode.equals("internal-only")) {
            properties.put(INTERNAL_FLAG, "true");
        }
        try (AnnotationConfigApplicationContext web = webContext(properties);
             MockedConstruction<AnnotationConfigApplicationContext> children =
                     mockConstruction(AnnotationConfigApplicationContext.class)) {
            web.refresh();

            assertThat(children.constructed()).isEmpty();
            assertThat(web.getBeansOfType(LegalPublicDocumentReadService.class)).isEmpty();
            assertThat(web.getBeansOfType(LegalPublicDocumentHttpConfiguration.class)).isEmpty();
            assertWebGraphUnchanged(web);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc-url", "username", "password"})
    void enabledHttpRequiresEveryDedicatedPropertyAndNeverFallsBackToTheWebGraph(String missing) {
        Map<String, Object> properties = enabledProperties();
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
            LegalPublicDocumentReadService facade = web.getBean(LegalPublicDocumentReadService.class);
            HikariDataSource pool = isolated.getBean("legalPublicDocumentPool", HikariDataSource.class);

            assertWebGraphUnchanged(web);
            assertThat(facade).isSameAs(isolated.getBean(LegalPublicDocumentReadService.class));
            assertThat(isolated.getParent()).isNull();
            assertThat(isolated.getBeanFactory()).isNotSameAs(web.getBeanFactory());
            assertThat(isolated.containsBean("webDataSource")).isFalse();
            assertThat(isolated.containsBean("webJdbc")).isFalse();
            assertThat(isolated.containsBean("webTransactionManager")).isFalse();
            assertThat(isolated.containsBean("webPrivateObject")).isFalse();
            assertThat(web.getBeansOfType(LegalPublicDocumentReadDatabaseConfiguration.class)).isEmpty();
            assertThat(web.getBeansOfType(ConfigurableApplicationContext.class)).isEmpty();
            assertThat(web.containsBean("legalPublicDocumentPool")).isFalse();
            assertThat(web.containsBean("legalPublicDocumentJdbc")).isFalse();
            assertThat(web.containsBean("legalPublicDocumentTransactionManager")).isFalse();
            assertThat(web.containsBean("legalPublicDocumentGate")).isFalse();
            assertThat(isolated.getBeansOfType(DataSource.class)).containsOnlyKeys(
                    "legalPublicDocumentPool", "legalPublicDocumentDataSource");
            assertThat(pool.getJdbcUrl()).isEqualTo(READER_URL);
            assertThat(pool.getUsername()).isEqualTo(READER_USERNAME);
            assertThat(pool.getPassword()).isEqualTo(READER_PASSWORD);
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
            web.refresh();
            AnnotationConfigApplicationContext isolated = isolatedContext(web);

            assertThat(isolated.getEnvironment()).isNotSameAs(web.getEnvironment());
            assertThat(isolated.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(StreamSupport.stream(isolated.getEnvironment().getPropertySources().spliterator(), false)
                    .map(source -> source.getName()).toList())
                    .containsExactly("legal-public-document-read-isolated");
            MapPropertySource source = (MapPropertySource) isolated.getEnvironment().getPropertySources()
                    .iterator().next();
            assertThat(source.getSource()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    PREFIX + "jdbc-url", READER_URL,
                    PREFIX + "username", READER_USERNAME,
                    PREFIX + "password", READER_PASSWORD,
                    INTERNAL_FLAG, "true"));
            for (String key : List.of(HTTP_FLAG, PREFIX + "maximum-pool-size",
                    "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                    "private.web.setting", "java.version", "PATH",
                    "ordenfix.legal.aggregate-context.enabled")) {
                assertThat(isolated.getEnvironment().getProperty(key)).as(key).isNull();
            }
            assertThat(isolated.getBean(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(2);
            assertThat(web.getEnvironment().getProperty(INTERNAL_FLAG)).isEqualTo("false");
            assertWebGraphUnchanged(web);
        }
    }

    @Test
    void webShutdownClosesTheOwnedContextPoolAndFacadeWithoutBorrowingAConnection() {
        AnnotationConfigApplicationContext web = webContext(enabledProperties());
        web.refresh();
        AnnotationConfigApplicationContext isolated = isolatedContext(web);
        HikariDataSource pool = isolated.getBean("legalPublicDocumentPool", HikariDataSource.class);
        Object bounded = isolated.getBean("legalPublicDocumentDataSource");
        LegalPublicDocumentReadService facade = web.getBean(LegalPublicDocumentReadService.class);
        LegalPublicDocumentHttpConfiguration configuration =
                web.getBean(LegalPublicDocumentHttpConfiguration.class);
        assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();

        web.close();

        assertThat(isolated.isActive()).isFalse();
        assertThat(pool.isClosed()).isTrue();
        assertThat(ReflectionTestUtils.getField(bounded, "closed")).isEqualTo(true);
        assertThatCode(configuration::destroy).doesNotThrowAnyException();
        assertThatThrownBy(() -> facade.document(UUID.randomUUID()))
                .isInstanceOf(LegalPublicDocumentReadException.class);
        assertThatThrownBy(() -> configuration.legalPublicDocumentReadService(configuredEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLaterWebRefreshFailureStillDestroysTheAlreadyCreatedReaderGraph() {
        AtomicReference<AnnotationConfigApplicationContext> child = new AtomicReference<>();
        AtomicReference<HikariDataSource> pool = new AtomicReference<>();
        try (AnnotationConfigApplicationContext web = webContext(enabledProperties())) {
            web.registerBean("laterWebFailure", Object.class, () -> {
                web.getBean(LegalPublicDocumentReadService.class);
                child.set(isolatedContext(web));
                pool.set(child.get().getBean("legalPublicDocumentPool", HikariDataSource.class));
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
        LegalPublicDocumentHttpConfiguration configuration = new LegalPublicDocumentHttpConfiguration();
        try (MockedConstruction<AnnotationConfigApplicationContext> contexts = mockConstruction(
                AnnotationConfigApplicationContext.class, (candidate, ignored) -> {
                    when(candidate.getEnvironment()).thenReturn(new StandardEnvironment());
                    if (stage.equals("refresh")) {
                        doThrow(original).when(candidate).refresh();
                    } else {
                        when(candidate.getBean(LegalPublicDocumentReadService.class)).thenThrow(original);
                    }
                })) {
            assertThatThrownBy(() -> configuration.legalPublicDocumentReadService(configuredEnvironment()))
                    .isSameAs(original);

            assertThat(contexts.constructed()).hasSize(1);
            AnnotationConfigApplicationContext candidate = contexts.constructed().getFirst();
            verify(candidate).close();
            assertThat(ReflectionTestUtils.getField(configuration, "readerContext")).isNull();
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
            LegalPublicDocumentHttpConfiguration configuration = new LegalPublicDocumentHttpConfiguration();

            assertThatThrownBy(() -> configuration.legalPublicDocumentReadService(configuredEnvironment()))
                    .isSameAs(original);
            assertThat(original.getSuppressed()).containsExactly(cleanup);
            verify(contexts.constructed().getFirst()).close();
            assertThat(ReflectionTestUtils.getField(configuration, "readerContext")).isNull();
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
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(web, false);
        scanner.addIncludeFilter(new AssignableTypeFilter(LegalPublicDocumentHttpConfiguration.class));
        scanner.scan(LegalPublicDocumentHttpConfiguration.class.getPackageName());
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
                web.getBean(LegalPublicDocumentHttpConfiguration.class), "readerContext");
    }

    private static MockEnvironment configuredEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        enabledProperties().forEach((key, value) -> environment.setProperty(key, value.toString()));
        return environment;
    }

    private static Map<String, Object> enabledProperties() {
        Map<String, Object> values = new LinkedHashMap<>(webProperties());
        values.put(HTTP_FLAG, "true");
        values.put(PREFIX + "jdbc-url", READER_URL);
        values.put(PREFIX + "username", READER_USERNAME);
        values.put(PREFIX + "password", READER_PASSWORD);
        return values;
    }

    private static Map<String, Object> webProperties() {
        return Map.of("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/unused_web",
                "spring.datasource.username", "unrelated_web_owner",
                "spring.datasource.password", "unrelated-web-test-only");
    }
}
