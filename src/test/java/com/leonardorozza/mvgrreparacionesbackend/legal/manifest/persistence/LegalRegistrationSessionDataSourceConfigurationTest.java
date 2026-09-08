package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Real Spring bean ordering/destruction; no database connection or JPA/Boot server is started. */
class LegalRegistrationSessionDataSourceConfigurationTest {
    private static final String RESOURCES = "legalRegistrationSessionResources";
    private static final String POST_PROCESSOR = "legalRegistrationSessionRoutingPostProcessor";
    private static final String READY = "legalRegistrationSessionWiringReady";

    @Test
    void moduleIsExplicitAndAddsOnlyResourcesAnOrderedStaticPostProcessorAndAnEagerGate() throws Exception {
        Class<?> type = LegalRegistrationSessionDataSourceConfiguration.class;
        assertThat(AnnotatedElementUtils.hasAnnotation(type, Component.class)).isFalse();
        var beans = Arrays.stream(type.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(Bean.class)).toList();
        assertThat(beans).extracting(java.lang.reflect.Method::getName).containsExactlyInAnyOrder(RESOURCES, POST_PROCESSOR, READY);
        assertThat(beans).allSatisfy(method -> assertThat(DataSource.class.isAssignableFrom(method.getReturnType())).isFalse());
        var resources = type.getDeclaredMethod(RESOURCES);
        assertThat(Modifier.isStatic(resources.getModifiers())).isTrue();
        assertThat(resources.getAnnotation(Bean.class).destroyMethod()).isEqualTo("close");
        var processor = beans.stream().filter(method -> method.getName().equals(POST_PROCESSOR)).findFirst().orElseThrow();
        assertThat(Modifier.isStatic(processor.getModifiers())).isTrue();
        assertThat(BeanPostProcessor.class.isAssignableFrom(processor.getReturnType())).isTrue();
        var gate = type.getDeclaredMethod(READY, LegalRegistrationSessionResources.class, DataSource.class);
        assertThat(gate.getAnnotation(Lazy.class).value()).isFalse();
        assertThat(gate.getAnnotation(DependsOn.class).value()).containsExactly("dataSource");
    }

    @Test
    void importingTheModulePublishesOneDataSourceAfterInitializationAndOnlyThenAuthorizesTheHolder() throws Exception {
        try (var f = new Fixture(false)) {
            f.context.refresh();

            DataSource exposed = f.context.getBean("dataSource", DataSource.class);
            assertThat(exposed).isInstanceOf(LegalRegistrationSessionDataSource.class);
            assertThat(f.context.getBeansOfType(DataSource.class)).containsOnlyKeys("dataSource");
            assertThat(f.context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(exposed);
            assertThat(f.context.getBean(RESOURCES)).isSameAs(f.resources);
            assertThat(f.context.getBean(POST_PROCESSOR, Ordered.class).getOrder()).isZero();
            assertThat(f.context.getBeanFactory().containsSingleton(READY)).isTrue();
            assertThat(f.factoryCalls).hasValue(1);
            assertThat(f.original.initializations).hasValue(1);
            assertThat(f.initializedAtCopy).isTrue();
            assertThat(f.copiedUsername).isEqualTo("initialized-session-user");
            assertThat(f.original.borrows).hasValue(0);
            assertThat(f.dedicated.borrows).hasValue(0);

            try (Connection historical = exposed.getConnection()) {
                assertThat(historical).isSameAs(f.original.connection);
            }
            f.resources.withinRegistrationBudget(LegalRegistrationBudget.start(() -> 0L), budget -> {
                try (Connection connection = exposed.getConnection()) {
                    connection.commit();
                    return null;
                } catch (SQLException failure) { throw new AssertionError(failure); }
            });
            assertThat(f.original.borrows).hasValue(1);
            assertThat(f.dedicated.borrows).hasValue(1);
            verify(f.dedicated.connection).commit();
            verify(f.original.connection, never()).commit();

            f.context.close();
            assertThat(f.original.closes).hasValue(1);
            assertThat(f.dedicated.closes).hasValue(1);
            assertThatThrownBy(() -> f.resources.withinRegistrationBudget(LegalRegistrationBudget.start(), owner -> "closed"))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
        }
    }

    @Test
    void anUnorderedRegisteredObserverSeesTheRouterKeepsCommitObservationAndCannotUseAPendingHolder() throws Exception {
        try (var f = new Fixture(true)) {
            f.context.refresh();

            assertThat(f.observerVisits).hasValue(1);
            assertThat(f.observedBeforeGate).isInstanceOf(LegalRegistrationSessionDataSource.class);
            assertThat(f.pendingFailure).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
            assertThat(f.pendingCallbacks).hasValue(0);
            DataSource finalSource = f.context.getBean("dataSource", DataSource.class);
            assertThat(finalSource).isSameAs(f.observerWrapper);
            assertThat(f.context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(finalSource);
            f.resources.withinRegistrationBudget(LegalRegistrationBudget.start(() -> 0L), budget -> {
                try (Connection connection = finalSource.getConnection()) {
                    connection.commit();
                    return "completed";
                } catch (SQLException failure) { throw new AssertionError(failure); }
            });
            assertThat(f.observerCommits).hasValue(1);
            assertThat(f.dedicated.borrows).hasValue(1);
            assertThat(f.original.borrows).hasValue(0);
            verify(f.dedicated.connection).commit();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aFactoryFailureReturnsThroughAllPostProcessorsThenTheMandatoryGateRejectsAndSpringClosesTheOriginal(boolean lazy) {
        try (var f = new Fixture(true)) {
            var primary = new IllegalArgumentException("synthetic composition rejection");
            f.factory = original -> { throw primary; };
            var lazyControlCreations = new AtomicInteger();
            f.context.registerBean("lazyControl", Object.class, () -> {
                lazyControlCreations.incrementAndGet();
                return new Object();
            });
            if (lazy) f.enableBootLazyInitialization();

            Throwable startup = catchThrowable(f.context::refresh);

            assertThat(startup).isNotNull();
            assertThat(hasCause(startup, primary)).isTrue();
            assertThat(f.observerVisits).hasValue(1); // The router BPP returned instead of throwing during bean creation.
            assertThat(f.observedBeforeGate).isSameAs(f.original);
            assertThat(f.pendingFailure).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(primary);
            assertThat(f.pendingCallbacks).hasValue(0);
            assertThat(f.original.initializations).hasValue(1);
            assertThat(f.original.closes).hasValue(1); // Registered disposal ran during failed refresh, before explicit close.
            assertThat(f.original.borrows).hasValue(0);
            assertThat(f.dedicated.closes).hasValue(0); // The factory never transferred this fixture-owned object.
            assertThat(f.factoryCalls).hasValue(1);
            assertThat(f.context.isActive()).isFalse();
            assertThat(f.context.getBeanFactory().getBeanDefinition(READY).isLazyInit()).isFalse();
            if (lazy) {
                assertThat(f.context.getBeanFactory().getBeanDefinition("dataSource").isLazyInit()).isTrue();
                assertThat(f.context.getBeanFactory().getBeanDefinition("lazyControl").isLazyInit()).isTrue();
                assertThat(lazyControlCreations).hasValue(0);
            }
            assertThatThrownBy(() -> f.resources.withinRegistrationBudget(LegalRegistrationBudget.start(), owner -> "not live"))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class).hasCause(primary);
            f.context.close();
            assertThat(f.original.closes).hasValue(1);
        }
    }

    @Test
    void anUnrelatedFailureAfterTheGateClosesBothOwnersThroughTheirRegisteredLifecycles() {
        try (var f = new Fixture(false)) {
            var primary = new IllegalStateException("synthetic later singleton failure");
            f.context.registerBean("laterFailure", Object.class, () -> { throw primary; },
                    definition -> definition.setDependsOn(READY));

            Throwable startup = catchThrowable(f.context::refresh);

            assertThat(hasCause(startup, primary)).isTrue();
            assertThat(f.factoryCalls).hasValue(1);
            assertThat(f.original.closes).hasValue(1);
            assertThat(f.dedicated.closes).hasValue(1);
            assertThat(f.original.borrows).hasValue(0);
            assertThat(f.dedicated.borrows).hasValue(0);
            assertThatThrownBy(() -> f.resources.withinRegistrationBudget(LegalRegistrationBudget.start(), owner -> "closed"))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
        }
    }

    @Test
    void aNonHikariOriginalIsRejectedAtTheGateAndItsDeclaredDestroyMethodStillRuns() {
        try (var f = new Fixture(true)) {
            var unsupported = new ClosingDataSource();
            f.context.removeBeanDefinition("dataSource");
            f.context.registerBean("dataSource", ClosingDataSource.class, () -> unsupported,
                    definition -> definition.setDestroyMethodName("close"));

            Throwable startup = catchThrowable(f.context::refresh);

            assertThat(startup).isNotNull();
            assertThat(f.observedBeforeGate).isSameAs(unsupported);
            assertThat(f.factoryCalls).hasValue(0);
            assertThat(f.pendingCallbacks).hasValue(0);
            assertThat(unsupported.closes).hasValue(1);
            assertThat(unsupported.borrows).hasValue(0);
            assertThat(f.context.isActive()).isFalse();
        }
    }

    @Test
    void aMissingExactDataSourceNameRejectsTheContextWithoutInstallingAnUnrelatedPool() {
        try (var f = new Fixture(false)) {
            f.context.removeBeanDefinition("dataSource");
            f.context.removeBeanDefinition("jdbcTemplate");
            f.context.registerBean("unrelatedPool", TrackingHikari.class, () -> f.original,
                    definition -> definition.setDestroyMethodName("close"));

            Throwable startup = catchThrowable(f.context::refresh);

            assertThat(startup).isNotNull();
            assertThat(f.factoryCalls).hasValue(0);
            assertThat(f.original.borrows).hasValue(0);
            assertThat(f.context.isActive()).isFalse();
            assertThatThrownBy(() -> f.resources.withinRegistrationBudget(LegalRegistrationBudget.start(), owner -> "not installed"))
                    .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
        }
    }

    @Test
    void onlyTheExactApplicationBeanIsDecoratedAndOtherPoolDefinitionsRemainLiteral() {
        var other = new TrackingHikari();
        try (var f = new Fixture(false)) {
            f.context.registerBean("dataSourceReplica", TrackingHikari.class, () -> other,
                    definition -> definition.setDestroyMethodName("close"));
            f.context.refresh();

            assertThat(f.context.getBean("dataSource")).isInstanceOf(LegalRegistrationSessionDataSource.class);
            assertThat(f.context.getBean("dataSourceReplica")).isSameAs(other);
            assertThat(f.factoryCalls).hasValue(1);
            assertThat(other.borrows).hasValue(0);
            assertThat(other.closes).hasValue(0);
            f.context.close();
            assertThat(other.closes).hasValue(1);
            assertThat(f.original.closes).hasValue(1);
            assertThat(f.dedicated.closes).hasValue(1);
        } finally {
            if (!other.isClosed()) other.close();
        }
    }

    @Test
    void independentlyComposedContextsCannotShareTheirRouterPoolOrLifecycle() {
        try (var first = new Fixture(false); var second = new Fixture(false)) {
            first.context.refresh(); second.context.refresh();
            DataSource firstSource = first.context.getBean(DataSource.class);
            DataSource secondSource = second.context.getBean(DataSource.class);
            assertThat(firstSource).isNotSameAs(secondSource);
            assertThat(first.resources).isNotSameAs(second.resources);

            first.context.close();

            assertThat(first.dedicated.closes).hasValue(1);
            assertThat(first.original.closes).hasValue(1);
            assertThat(second.dedicated.closes).hasValue(0);
            assertThat(second.original.closes).hasValue(0);
            assertThat(second.resources.<Integer>withinRegistrationBudget(LegalRegistrationBudget.start(() -> 0L), owner -> owner.remainingMillis()))
                    .isEqualTo(30_000);
        }
    }

    @Test
    void withoutAnExplicitModuleImportSpringLeavesTheApplicationDataSourceUntouched() {
        var original = new TrackingHikari();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", TrackingHikari.class, () -> original,
                    definition -> definition.setDestroyMethodName("close"));
            context.refresh();
            assertThat(context.getBean(DataSource.class)).isSameAs(original);
            assertThat(context.getBeansOfType(LegalRegistrationSessionResources.class)).isEmpty();
            assertThat(context.containsBean(READY)).isFalse();
        }
        assertThat(original.closes).hasValue(1);
    }

    @Test
    void nominalCompositionKeepsHistoricalSettingsAndTheGlobalDriverTimeoutWithoutOpeningConnections() {
        var original = new TrackingHikari();
        int globalLoginTimeout = DriverManager.getLoginTimeout();
        LegalRegistrationSessionResources resources;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", TrackingHikari.class, () -> original,
                    definition -> definition.setDestroyMethodName("close"));
            context.register(LegalRegistrationSessionDataSourceConfiguration.class);
            context.refresh();
            resources = context.getBean(LegalRegistrationSessionResources.class);
            assertThat(context.getBeansOfType(DataSource.class)).containsOnlyKeys("dataSource");
            assertThat(original.getJdbcUrl()).isEqualTo(TrackingHikari.URL);
            assertThat(original.getUsername()).isEqualTo("initialized-session-user");
            assertThat(original.getPassword()).isEqualTo("synthetic-session-password");
            assertThat(original.getConnectionTimeout()).isEqualTo(4_321);
            assertThat(original.isAutoCommit()).isFalse();
            assertThat(original.isReadOnly()).isTrue();
            assertThat(original.getDataSourceProperties()).containsOnlyKeys("ApplicationName")
                    .containsEntry("ApplicationName", "session-configuration-test");
            assertThat(original.borrows).hasValue(0);
            assertThat(DriverManager.getLoginTimeout()).isEqualTo(globalLoginTimeout);
        }
        assertThat(original.closes).hasValue(1);
        assertThat(DriverManager.getLoginTimeout()).isEqualTo(globalLoginTimeout);
        assertThatThrownBy(() -> resources.withinRegistrationBudget(LegalRegistrationBudget.start(), owner -> "closed"))
                .isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class);
    }

    private static boolean hasCause(Throwable failure, Throwable expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (current == expected) return true;
        return false;
    }

    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final TrackingHikari original = new TrackingHikari();
        final TrackingHikari dedicated = new TrackingHikari();
        final AtomicInteger factoryCalls = new AtomicInteger(), observerVisits = new AtomicInteger();
        final AtomicInteger pendingCallbacks = new AtomicInteger(), observerCommits = new AtomicInteger();
        boolean initializedAtCopy;
        String copiedUsername;
        Function<HikariDataSource, HikariDataSource> factory = ignored -> dedicated;
        final LegalRegistrationSessionResources resources = new LegalRegistrationSessionResources(raw -> {
            factoryCalls.incrementAndGet();
            initializedAtCopy = original.initializations.get() == 1;
            copiedUsername = raw.getUsername();
            return factory.apply(raw);
        });
        Object observedBeforeGate;
        Throwable pendingFailure;
        ObservedDataSource observerWrapper;

        Fixture(boolean observe) {
            context.registerBean("dataSource", TrackingHikari.class, () -> original,
                    definition -> definition.setDestroyMethodName("close"));
            context.register(LegalRegistrationSessionDataSourceConfiguration.class);
            // Replace only the factory-method supplier. Spring still registers and destroys the holder bean.
            context.addBeanFactoryPostProcessor(factory -> ((AbstractBeanDefinition) factory.getBeanDefinition(RESOURCES))
                    .setInstanceSupplier(() -> resources));
            if (observe) context.registerBean("unorderedObserver", UnorderedObserver.class, () -> new UnorderedObserver(this));
            context.registerBean("jdbcTemplate", JdbcTemplate.class,
                    () -> new JdbcTemplate(context.getBean("dataSource", DataSource.class)));
        }

        void enableBootLazyInitialization() {
            context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
        }

        @Override public void close() {
            try { context.close(); }
            finally {
                // Untransferred fixture objects are not Spring's resources and never opened a pool.
                if (!original.isClosed()) original.close();
                if (!dedicated.isClosed()) dedicated.close();
                resources.close();
            }
        }
    }

    /** Registered as an ordinary BPP bean, so Spring's Ordered sorting is exercised. */
    private static final class UnorderedObserver implements BeanPostProcessor {
        private final Fixture fixture;
        UnorderedObserver(Fixture fixture) { this.fixture = fixture; }
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!name.equals("dataSource")) return bean;
            fixture.observerVisits.incrementAndGet();
            fixture.observedBeforeGate = bean;
            fixture.pendingFailure = catchThrowable(() -> fixture.resources.withinRegistrationBudget(
                    LegalRegistrationBudget.start(() -> 0L), owner -> fixture.pendingCallbacks.incrementAndGet()));
            fixture.observerWrapper = new ObservedDataSource((DataSource) bean, fixture.observerCommits);
            return fixture.observerWrapper;
        }
    }

    private static final class ObservedDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final AtomicInteger commits;
        ObservedDataSource(DataSource delegate, AtomicInteger commits) { this.delegate = delegate; this.commits = commits; }
        @Override public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(connection, arguments);
                            if (method.getName().equals("commit")) commits.incrementAndGet();
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }
    }

    private static final class TrackingHikari extends HikariDataSource implements InitializingBean {
        static final String URL = "jdbc:postgresql://127.0.0.1:1/session_configuration_fixture";
        final AtomicInteger initializations = new AtomicInteger(), borrows = new AtomicInteger(), closes = new AtomicInteger();
        final Connection connection = mock(Connection.class);
        TrackingHikari() {
            setJdbcUrl(URL);
            setUsername("before-initialization");
            setPassword("synthetic-session-password");
            setConnectionTimeout(4_321);
            setAutoCommit(false);
            setReadOnly(true);
            addDataSourceProperty("ApplicationName", "session-configuration-test");
        }
        @Override public void afterPropertiesSet() {
            initializations.incrementAndGet();
            setUsername("initialized-session-user");
        }
        @Override public Connection getConnection() { borrows.incrementAndGet(); return connection; }
        @Override public void close() { closes.incrementAndGet(); super.close(); }
    }

    private static final class ClosingDataSource extends AbstractDataSource implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger(), borrows = new AtomicInteger();
        @Override public Connection getConnection() throws SQLException {
            borrows.incrementAndGet(); throw new SQLException("No connection belongs to this configuration test");
        }
        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        @Override public void close() { closes.incrementAndGet(); }
    }
}
