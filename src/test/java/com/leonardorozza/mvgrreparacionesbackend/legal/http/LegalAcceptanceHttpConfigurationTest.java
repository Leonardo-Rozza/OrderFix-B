package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegalAcceptanceHttpConfigurationTest {
    private static final String FLAG = LegalAcceptanceHttpConfiguration.ENABLED_PROPERTY;
    private static final String PREFIX = LegalAcceptanceDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String READ = "ordenfix.legal.account-read.";
    private static final String HMAC = "ordenfix.legal.idempotency.";
    private static final String META = "ordenfix.legal.account-metadata.";

    @ParameterizedTest @ValueSource(strings = {"absent", "false"})
    void disabledBridgeNeedsNoSecretsAndAllocatesNoChildOrMetadataResolver(String mode) {
        var properties = new LinkedHashMap<String, Object>();
        if (!"absent".equals(mode)) properties.put(FLAG, mode);
        try (var web = web(properties); var children = mockConstruction(AnnotationConfigApplicationContext.class)) {
            web.refresh();
            assertThat(children.constructed()).isEmpty();
            assertThat(web.getBeansOfType(LegalAcceptanceHttpConfiguration.class)).isEmpty();
            assertThat(web.getBeansOfType(LegalAcceptanceService.class)).isEmpty();
            assertThat(web.getBeansOfType(LegalRequestMetadataResolver.class)).isEmpty();
            unchangedWeb(web);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"TRUE", "FALSE", " true", "true ", "1", "yes", ""})
    void invalidFlagFailsBeforeAnyResourcesIncludingDuringRegistration(String value) {
        var properties = properties(); properties.put(FLAG, value);
        try (var web = new AnnotationConfigApplicationContext();
             var children = mockConstruction(AnnotationConfigApplicationContext.class)) {
            web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            assertThatThrownBy(() -> { web.register(LegalAcceptanceHttpConfiguration.class); web.refresh(); })
                    .hasStackTraceContaining("true o false exactos");
            assertThat(children.constructed()).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "false", "TRUE", ""})
    void acceptanceRequiresAnExactEnabledReaderBeforeResources(String value) {
        var properties = properties();
        if ("absent".equals(value)) properties.remove(READ + "enabled"); else properties.put(READ + "enabled", value);
        try (var web = new AnnotationConfigApplicationContext();
             var children = mockConstruction(AnnotationConfigApplicationContext.class)) {
            web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            assertThatThrownBy(() -> { web.register(LegalAcceptanceHttpConfiguration.class); web.refresh(); })
                    .hasStackTraceContaining("account-read=true");
            assertThat(children.constructed()).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc-url", "username", "password"})
    void neverFallsBackToWebOrReaderCredentials(String missing) {
        var values = properties(); values.remove(PREFIX + missing);
        try (var web = web(values); var children = mockConstruction(AnnotationConfigApplicationContext.class)) {
            assertThatThrownBy(web::refresh).isInstanceOf(BeanCreationException.class);
            assertThat(children.constructed()).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"jwt", "device", "proxy", "strategy"})
    void invalidSecretOrCapturePolicyFailsBeforeContextAllocation(String defect) {
        var values = properties();
        switch (defect) {
            case "jwt" -> values.put("security.jwt.secret", "h".repeat(32));
            case "device" -> values.put("DEVICE_CREDENTIALS_ENCRYPTION_KEY", key('m'));
            case "proxy" -> values.put(META + "trusted-proxy-cidrs", "10.0.0.1/8");
            case "strategy" -> values.put("server.forward-headers-strategy", "framework");
        }
        try (var web = web(values); var children = mockConstruction(AnnotationConfigApplicationContext.class)) {
            assertThatThrownBy(web::refresh).isInstanceOf(BeanCreationException.class);
            assertThat(children.constructed()).isEmpty();
        }
    }

    @Test void serviceAndMetadataShareOneSettingsSnapshotWithNoJdbcGraphExported() {
        HikariDataSource pool;
        try (var web = web(properties())) {
            web.getEnvironment().setActiveProfiles("untrusted-parent-profile");
            web.getEnvironment().setDefaultProfiles("untrusted-default");
            web.refresh();
            var child = child(web);
            var facade = web.getBean(LegalAcceptanceService.class);
            pool = child.getBean(HikariDataSource.class);
            assertThat(facade).isSameAs(child.getBean(LegalAcceptanceService.class));
            assertThat(web.getBeansOfType(LegalAcceptanceService.class)).hasSize(1);
            assertThat(web.getBeansOfType(LegalRequestMetadataResolver.class)).hasSize(1);
            assertThat(child.getBeansOfType(LegalRequestMetadataResolver.class)).isEmpty();
            unchangedWeb(web);
            assertThat(child.getParent()).isNull();
            assertThat(child.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(child.getEnvironment().getDefaultProfiles()).isEmpty();
            assertThat(child.getEnvironment().getPropertySources()).hasSize(1);
            var source = (EnumerablePropertySource<?>) child.getEnvironment().getPropertySources().iterator().next();
            assertThat(source.getPropertyNames()).containsExactlyInAnyOrderElementsOf(selectedKeys());
            for (String forbidden : List.of("security.jwt.secret", "DEVICE_CREDENTIALS_ENCRYPTION_KEY",
                    "spring.datasource.url", READ + "password", META + "trusted-proxy-cidrs",
                    "server.forward-headers-strategy", "java.home")) {
                assertThat(child.getEnvironment().getProperty(forbidden)).as(forbidden).isNull();
            }
            assertThat(child.containsBean("webJdbc")).isFalse();
            assertThat(child.containsBean("webDataSource")).isFalse();
            assertThat(web.getBeansOfType(LegalAcceptanceDatabaseConfiguration.class)).isEmpty();
            assertThat(web.getBeansOfType(AnnotationConfigApplicationContext.class)).isEmpty();
            assertThat(pool.getJdbcUrl()).isEqualTo(properties().get(PREFIX + "jdbc-url"));
            assertThat(pool.getUsername()).isEqualTo("acceptance-http-test");
            assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
            assertThat(pool.getMinimumIdle()).isZero();
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
            assertThat(child.getBean(JdbcTemplate.class).getDataSource()).isNotSameAs(web.getBean(DataSource.class));
            var resolver = web.getBean(LegalRequestMetadataResolver.class);
            var request = new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.99");
            request.addHeader("X-Forwarded-For", "not an IP from an untrusted peer");
            assertThat(resolver.resolve(request)).isNotNull();
            // A later environment mutation does not rotate an existing context or metadata trust policy.
            web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("late", Map.of(
                    META + "trusted-proxy-cidrs", "192.0.2.0/24", PREFIX + "username", "different")));
            var configuration = web.getBean(LegalAcceptanceHttpConfiguration.class);
            assertThat(configuration.legalAcceptanceService(new MockEnvironment())).isSameAs(facade);
            assertThat(configuration.legalAcceptanceRequestMetadataResolver(new MockEnvironment())).isSameAs(resolver);
            assertThat(resolver.resolve(request)).isNotNull();
        }
        assertThat(pool.isClosed()).isTrue();
    }

    @Test void readerAndAcceptorUseDifferentOwnedPoolsWithoutChangingTheWebTransaction() {
        try (var web = web(properties())) {
            web.register(LegalPrivateRequirementsHttpConfiguration.class);
            web.refresh();
            var reader = (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(
                    web.getBean(LegalPrivateRequirementsHttpConfiguration.class), "requirementsContext");
            var acceptance = child(web);
            assertThat(reader).isNotSameAs(acceptance);
            assertThat(reader.getBean(HikariDataSource.class)).isNotSameAs(acceptance.getBean(HikariDataSource.class));
            assertThat(reader.getBean(HikariDataSource.class).getUsername()).isEqualTo("read-http-test");
            assertThat(acceptance.getBean(HikariDataSource.class).getUsername()).isEqualTo("acceptance-http-test");
            unchangedWeb(web);
        }
    }

    @Test void metadataCanBeRequestedFirstWithoutOpeningTheAcceptanceContext() {
        var configuration = new LegalAcceptanceHttpConfiguration();
        try (var children = mockConstruction(AnnotationConfigApplicationContext.class)) {
            var resolver = configuration.legalAcceptanceRequestMetadataResolver(environment());
            assertThat(resolver).isNotNull();
            assertThat(children.constructed()).isEmpty();
            configuration.destroy(); configuration.destroy();
            assertThatThrownBy(() -> configuration.legalAcceptanceRequestMetadataResolver(environment()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> configuration.legalAcceptanceService(environment()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(children.constructed()).isEmpty();
        }
    }

    @Test void invalidIsolatedCryptographicConfigCannotAllocateAPool() {
        var values = properties(); values.put(META + "retention", "PT0S");
        try (var web = web(values); var pools = mockConstruction(HikariDataSource.class)) {
            assertThatThrownBy(web::refresh).isInstanceOf(BeanCreationException.class);
            assertThat(pools.constructed()).isEmpty();
        }
    }

    @Test void laterWebFailureClosesTheAlreadyCreatedAcceptancePool() {
        var owned = new AtomicReference<AnnotationConfigApplicationContext>();
        var pool = new AtomicReference<HikariDataSource>();
        try (var web = web(properties())) {
            web.registerBean("laterFailure", Object.class, () -> {
                web.getBean(LegalAcceptanceService.class); owned.set(child(web));
                pool.set(owned.get().getBean(HikariDataSource.class));
                throw new IllegalStateException("deliberate web failure");
            });
            assertThatThrownBy(web::refresh).isInstanceOf(BeanCreationException.class);
            assertThat(owned.get()).isNotNull();
            assertThat(owned.get().isActive()).isFalse();
            assertThat(pool.get().isClosed()).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"refresh", "facade"})
    void failedChildIsClosedWithoutTakingOwnership(String stage) {
        var original = new IllegalStateException("deliberate initialization failure");
        var cleanup = new IllegalStateException("deliberate cleanup failure");
        var configuration = new LegalAcceptanceHttpConfiguration();
        try (var children = mockConstruction(AnnotationConfigApplicationContext.class, (candidate, ignored) -> {
            when(candidate.getEnvironment()).thenReturn(new StandardEnvironment());
            if ("refresh".equals(stage)) doThrow(original).when(candidate).refresh();
            else doThrow(original).when(candidate).getBean(LegalAcceptanceService.class);
            doThrow(cleanup).when(candidate).close();
        })) {
            assertThatThrownBy(() -> configuration.legalAcceptanceService(environment())).isSameAs(original);
            assertThat(original.getSuppressed()).containsExactly(cleanup);
            verify(children.constructed().getFirst()).close();
            assertThat(ReflectionTestUtils.getField(configuration, "acceptanceContext")).isNull();
            configuration.destroy();
            verify(children.constructed().getFirst(), times(1)).close();
        }
    }

    @Test void successfulChildIsReusedAndEvenAFailedDestroyCannotReopenIt() {
        var facade = mock(LegalAcceptanceService.class);
        var failure = new IllegalStateException("deliberate close failure");
        var configuration = new LegalAcceptanceHttpConfiguration();
        try (var children = mockConstruction(AnnotationConfigApplicationContext.class, (candidate, ignored) -> {
            when(candidate.getEnvironment()).thenReturn(new StandardEnvironment());
            when(candidate.getBean(LegalAcceptanceService.class)).thenReturn(facade);
            doThrow(failure).when(candidate).close();
        })) {
            assertThat(configuration.legalAcceptanceService(environment())).isSameAs(facade);
            assertThat(configuration.legalAcceptanceService(new MockEnvironment())).isSameAs(facade);
            assertThat(children.constructed()).hasSize(1);
            verify(children.constructed().getFirst(), times(1)).refresh();
            assertThatThrownBy(configuration::destroy).isSameAs(failure);
            assertThatCode(configuration::destroy).doesNotThrowAnyException();
            assertThatThrownBy(() -> configuration.legalAcceptanceService(environment())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> configuration.legalAcceptanceRequestMetadataResolver(environment())).isInstanceOf(IllegalStateException.class);
            verify(children.constructed().getFirst(), times(1)).close();
            assertThat(ReflectionTestUtils.getField(configuration, "acceptanceContext")).isNull();
        }
    }

    private static AnnotationConfigApplicationContext web(Map<String, Object> properties) {
        var web = new AnnotationConfigApplicationContext();
        web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("web", properties));
        var source = mock(DataSource.class);
        var jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        web.registerBean("webDataSource", DataSource.class, () -> source);
        web.registerBean("webJdbc", JdbcTemplate.class, () -> jdbc);
        web.registerBean("webTransactionManager", DataSourceTransactionManager.class, () -> manager);
        web.registerBean("webTransaction", TransactionTemplate.class, () -> new TransactionTemplate(manager));
        web.register(LegalAcceptanceHttpConfiguration.class);
        return web;
    }

    private static void unchangedWeb(AnnotationConfigApplicationContext web) {
        assertThat(web.getBeansOfType(DataSource.class)).containsOnlyKeys("webDataSource");
        assertThat(web.getBeansOfType(JdbcTemplate.class)).containsOnlyKeys("webJdbc");
        assertThat(web.getBeansOfType(DataSourceTransactionManager.class)).containsOnlyKeys("webTransactionManager");
        assertThat(web.getBeansOfType(TransactionTemplate.class)).containsOnlyKeys("webTransaction");
        var source = web.getBean(DataSource.class);
        assertThat(web.getBean(JdbcTemplate.class).getDataSource()).isSameAs(source);
        assertThat(web.getBean(DataSourceTransactionManager.class).getDataSource()).isSameAs(source);
        verifyNoInteractions(source);
    }

    private static AnnotationConfigApplicationContext child(AnnotationConfigApplicationContext web) {
        return (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(
                web.getBean(LegalAcceptanceHttpConfiguration.class), "acceptanceContext");
    }

    private static Set<String> selectedKeys() {
        return Set.of(FLAG, READ + "enabled", PREFIX + "jdbc-url", PREFIX + "username", PREFIX + "password",
                HMAC + "active-write-version", HMAC + "result-ttl", HMAC + "keyring.1", HMAC + "keyring.3",
                META + "active-write-version", META + "retention", META + "keyring.2", META + "keyring.4");
    }

    private static MockEnvironment environment() {
        var environment = new MockEnvironment();
        properties().forEach((key, value) -> environment.setProperty(key, value.toString()));
        return environment;
    }

    private static Map<String, Object> properties() {
        var values = new LinkedHashMap<String, Object>();
        values.put(FLAG, "true"); values.put(READ + "enabled", "true");
        values.put(PREFIX + "jdbc-url", "jdbc:postgresql://127.0.0.1:1/unused_acceptance_http");
        values.put(PREFIX + "username", "acceptance-http-test"); values.put(PREFIX + "password", "acceptance-synthetic");
        values.put(READ + "jdbc-url", "jdbc:postgresql://127.0.0.1:1/unused_reader_http");
        values.put(READ + "username", "read-http-test"); values.put(READ + "password", "read-synthetic");
        values.put(HMAC + "active-write-version", "3"); values.put(HMAC + "result-ttl", "PT25H");
        values.put(HMAC + "keyring.1", key('h')); values.put(HMAC + "keyring.3", key('j'));
        values.put(META + "active-write-version", "4"); values.put(META + "retention", "P30D");
        values.put(META + "keyring.2", key('m')); values.put(META + "keyring.4", key('n'));
        values.put(META + "trusted-proxy-cidrs", "10.0.0.0/8");
        values.put("security.jwt.secret", "w".repeat(32));
        values.put("DEVICE_CREDENTIALS_ENCRYPTION_KEY", key('d'));
        values.put("server.forward-headers-strategy", "none");
        values.put("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/unused_web");
        values.put("spring.datasource.username", "web-owner"); values.put("spring.datasource.password", "web-synthetic");
        return values;
    }

    private static String key(char character) {
        return Base64.getEncoder().encodeToString(String.valueOf(character).repeat(32).getBytes(StandardCharsets.US_ASCII));
    }
}
