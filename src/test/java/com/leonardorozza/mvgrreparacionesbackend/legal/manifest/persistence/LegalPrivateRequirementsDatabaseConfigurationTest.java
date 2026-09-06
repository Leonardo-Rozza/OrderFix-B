package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class LegalPrivateRequirementsDatabaseConfigurationTest {
    private static final String FLAG = LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY;
    private static final String PREFIX = LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX;

    @ParameterizedTest @NullSource @ValueSource(strings = "false")
    void absentOrFalseCreatesNoPrivateResources(String value) {
        try (var context = context(value)) {
            context.refresh();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalPrivateRequirementsReadService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class)).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "TRUE", "False", " true", "true ", "1", "yes"})
    void flagRejectsEveryPresentNonLiteral(String value) {
        assertThatThrownBy(() -> { try (var context = context(value)) { context.refresh(); } })
                .hasStackTraceContaining("El flag de lectura legal requiere true o false exactos");
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc-url", "username", "password"})
    void dedicatedCredentialsAreRequiredAndNeverInherited(String missing) {
        try (var context = context("true")) {
            var values = properties();
            values.remove(PREFIX + missing);
            values.put("spring.datasource.url", "jdbc:postgresql://localhost/web");
            values.put("spring.datasource.username", "web-owner");
            values.put("spring.datasource.password", "web-test-only");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("credentials", values));
            assertThatThrownBy(context::refresh).hasStackTraceContaining(PREFIX + missing);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc-url", "username", "password"})
    void blankDedicatedCredentialsFailBeforeConnecting(String field) {
        try (var context = configured()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("blank", Map.of(PREFIX + field, " ")));
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"user=other", "password=other", "socketTimeout=0",
            "options=-c%20search_path%3Devil", "currentSchema=evil", "connectTimeout=0", "ApplicationName=web",
            "sslfactory=custom.Driver", "socket%54imeout=0"})
    void jdbcUrlCannotOverrideIdentityOrBounds(String query) {
        try (var context = configured()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("url",
                    Map.of(PREFIX + "jdbc-url", "jdbc:postgresql://localhost/private?" + query)));
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void isolatedContextRequiresExplicitRegistrationAndOwnV29Graph() {
        assertThat(AnnotatedElementUtils.hasAnnotation(LegalPrivateRequirementsDatabaseConfiguration.class,
                Component.class)).isFalse();
        assertThat(LegalPrivateRequirementsDatabaseConfiguration.class.isAnnotationPresent(Configuration.class))
                .isFalse();
        HikariDataSource pool;
        LegalPrivateRequirementsDataSource bounded;
        try (var context = configured()) {
            context.refresh();
            pool = context.getBean(HikariDataSource.class);
            bounded = context.getBean(LegalPrivateRequirementsDataSource.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var manager = context.getBean(DataSourceTransactionManager.class);
            var transaction = context.getBean(TransactionTemplate.class);
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(2);
            assertThat(jdbc.getDataSource()).isSameAs(bounded);
            assertThat(manager).isExactlyInstanceOf(DataSourceTransactionManager.class);
            assertThat(manager.getDataSource()).isSameAs(bounded);
            assertThat(manager.isRollbackOnCommitFailure()).isFalse();
            assertThat(transaction.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(transaction.isReadOnly()).isFalse();
            assertThat(transaction.getTimeout()).isEqualTo(15);
            context.getBean(LegalManifestDatabaseGate.class).requireExactPrivateRequirementsBoundary(jdbc,
                    context.getBean(LegalV29AcceptanceSchemaVerifier.class),
                    context.getBean(LegalPrivateRequirementsPrivilegeVerifier.class));
            assertThat(pool.getUsername()).isEqualTo("private-reader");
            assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
            assertThat(pool.getMinimumIdle()).isZero();
            assertThat(pool.getConnectionTimeout()).isEqualTo(1000);
            assertThat(pool.getValidationTimeout()).isEqualTo(1000);
            assertThat(pool.getDataSourceProperties()).containsEntry("socketTimeout", "5")
                    .containsEntry("connectTimeout", "1").containsEntry("cancelSignalTimeout", "1");
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(context.getBeanNamesForAnnotation(Controller.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalPublicRequirementsReadService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalRequiredSetAggregateService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class).values())
                    .extracting(LegalDatabaseBoundaryMarker::kind)
                    .containsExactly(LegalDatabaseBoundaryMarker.Kind.PRIVATE_REQUIREMENTS);
        }
        assertThat(pool.isClosed()).isTrue();
        assertThatThrownBy(() -> bounded.withinDeadline(deadline -> "closed"))
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
    }

    @ParameterizedTest @EnumSource(value = LegalDatabaseBoundaryMarker.Kind.class,
            mode = EnumSource.Mode.EXCLUDE, names = "PRIVATE_REQUIREMENTS")
    void refusesEveryMixedConsumerContext(LegalDatabaseBoundaryMarker.Kind other) {
        try (var context = configured()) {
            context.registerBean("foreign", LegalDatabaseBoundaryMarker.class,
                    () -> new LegalDatabaseBoundaryMarker(other));
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void publicServiceSurfaceOnlyAcceptsTheExistingServerPrincipal() {
        assertThat(LegalPrivateRequirementsReadService.class.getConstructors()).isEmpty();
        assertThat(LegalPrivateRequirementsReadService.class.getDeclaredMethods())
                .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                .singleElement().satisfies(method -> {
                    assertThat(method.getName()).isEqualTo("read");
                    assertThat(method.getParameterTypes()).containsExactly(AuthenticatedUserPrincipal.class);
                    assertThat(method.getReturnType()).isEqualTo(LegalAuthenticatedRequirements.class);
                });
    }

    private static AnnotationConfigApplicationContext context(String value) {
        var context = new AnnotationConfigApplicationContext();
        if (value != null) context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("flag", Map.of(FLAG, value)));
        context.register(LegalPrivateRequirementsDatabaseConfiguration.class);
        return context;
    }
    private static AnnotationConfigApplicationContext configured() {
        var context = context("true");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("properties", properties()));
        return context;
    }
    private static Map<String, Object> properties() {
        return new HashMap<>(Map.of(PREFIX + "jdbc-url", "jdbc:postgresql://localhost:1/private",
                PREFIX + "username", "private-reader", PREFIX + "password", "test-only-private-password"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"store", "reader", "actor", "gate-jdbc", "bounded-datasource"})
    void constructorRejectsCrossWiredComponentsBeforeBorrowing(String foreign) {
        try (PrivateServiceComposition composition = new PrivateServiceComposition();
             LegalPrivateRequirementsDataSource otherSource = new LegalPrivateRequirementsDataSource(
                     composition.pool, java.time.Duration.ofSeconds(15))) {
            JdbcTemplate otherJdbc = new JdbcTemplate(composition.dataSource);
            LegalManifestDatabaseGate gate = foreign.equals("gate-jdbc")
                    ? new LegalManifestDatabaseGate(composition.transaction, otherJdbc, composition.budgets,
                            java.util.List.of(composition.schema, composition.privileges)) : composition.gate();
            LegalRequiredSetAggregateStore store = foreign.equals("store")
                    ? composition.storeFor(otherJdbc) : composition.store;
            LegalPrivateRequirementsReader reader = foreign.equals("reader")
                    ? new LegalPrivateRequirementsReader(otherJdbc) : composition.reader;
            LegalActorSnapshotReader actor = foreign.equals("actor")
                    ? new LegalActorSnapshotReader(otherJdbc) : composition.actor;
            LegalPrivateRequirementsDataSource source = foreign.equals("bounded-datasource")
                    ? otherSource : composition.dataSource;

            assertThatIllegalArgumentException().isThrownBy(() -> new LegalPrivateRequirementsReadService(
                    composition.jdbc, source, gate, composition.resolver, store, reader, actor,
                    composition.schema, composition.privileges));
            org.mockito.Mockito.verifyNoInteractions(composition.pool);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"read-only", "timeout", "isolation", "propagation", "rollback-on-commit-failure",
            "jdbc-translating-manager", "foreign-manager-datasource"})
    void constructorReaccreditsTheTransactionIncludingUnknownCommitSemantics(String mutation) {
        try (PrivateServiceComposition composition = new PrivateServiceComposition()) {
            LegalManifestDatabaseGate gate = composition.gate();
            switch (mutation) {
                case "read-only" -> composition.transaction.setReadOnly(true);
                case "timeout" -> composition.transaction.setTimeout(14);
                case "isolation" -> composition.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                case "propagation" -> composition.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                case "rollback-on-commit-failure" -> ((DataSourceTransactionManager)
                        composition.transaction.getTransactionManager()).setRollbackOnCommitFailure(true);
                case "jdbc-translating-manager" -> composition.transaction.setTransactionManager(
                        new org.springframework.jdbc.support.JdbcTransactionManager(composition.dataSource));
                case "foreign-manager-datasource" -> composition.transaction.setTransactionManager(
                        new DataSourceTransactionManager(org.mockito.Mockito.mock(DataSource.class)));
                default -> throw new AssertionError(mutation);
            }
            assertThatIllegalArgumentException().isThrownBy(() -> composition.service(gate));
            org.mockito.Mockito.verifyNoInteractions(composition.pool);
        }
    }

    @Test
    void constructorRequiresItsExactOrderedV29PreflightsAndRejectsHistoricalSchemas() {
        try (PrivateServiceComposition composition = new PrivateServiceComposition()) {
            LegalV29AcceptanceSchemaVerifier foreignSchema = new LegalV29AcceptanceSchemaVerifier(
                    new JdbcTemplate(composition.dataSource), "public");
            LegalV28AggregateSchemaVerifier historicalSchema = new LegalV28AggregateSchemaVerifier(
                    composition.jdbc, "public");
            LegalPrivateRequirementsPrivilegeVerifier foreignPrivileges = new LegalPrivateRequirementsPrivilegeVerifier(
                    new JdbcTemplate(composition.dataSource), "private-reader", "public");
            for (java.util.List<LegalDatabasePreflight> preflights : java.util.List.<java.util.List<LegalDatabasePreflight>>of(
                    java.util.List.of(), java.util.List.of(composition.schema),
                    java.util.List.of(composition.privileges, composition.schema),
                    java.util.List.of(composition.schema, composition.privileges, composition.schema),
                    java.util.List.of(foreignSchema, composition.privileges),
                    java.util.List.of(historicalSchema, composition.privileges),
                    java.util.List.of(composition.schema, foreignPrivileges))) {
                LegalManifestDatabaseGate candidate = new LegalManifestDatabaseGate(
                        composition.transaction, composition.jdbc, composition.budgets, preflights);
                assertThatIllegalArgumentException().isThrownBy(() -> composition.service(candidate));
            }
            assertThatCode(() -> composition.service(composition.gate())).doesNotThrowAnyException();
            org.mockito.Mockito.verifyNoInteractions(composition.pool);
        }
    }

    private static final class PrivateServiceComposition implements AutoCloseable {
        private final LegalPrivateRequirementsDatabaseConfiguration configuration =
                new LegalPrivateRequirementsDatabaseConfiguration();
        private final DataSource pool = org.mockito.Mockito.mock(DataSource.class);
        private final LegalPrivateRequirementsDataSource dataSource = new LegalPrivateRequirementsDataSource(
                pool, java.time.Duration.ofSeconds(15));
        private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        private final LegalDatabaseBudgets budgets = configuration.legalPrivateRequirementsBudgets();
        private final TransactionTemplate transaction = configuration.legalPrivateRequirementsTransactionTemplate(
                configuration.legalPrivateRequirementsTransactionManager(dataSource), budgets);
        private final LegalV29AcceptanceSchemaVerifier schema = configuration.legalPrivateRequirementsSchemaVerifier(jdbc);
        private final LegalPrivateRequirementsPrivilegeVerifier privileges =
                new LegalPrivateRequirementsPrivilegeVerifier(jdbc, "private-reader", "public");
        private final com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver resolver =
                configuration.legalPrivateRequirementsScopeResolver();
        private final LegalRequiredSetAggregateStore store = storeFor(jdbc);
        private final LegalPrivateRequirementsReader reader = configuration.legalPrivateRequirementsReader(jdbc);
        private final LegalActorSnapshotReader actor = configuration.legalPrivateRequirementsActorReader(jdbc);

        private LegalManifestDatabaseGate gate() {
            return configuration.legalPrivateRequirementsGate(transaction, jdbc, budgets, schema, privileges,
                    configuration.legalPrivateRequirementsBoundaryGuard(java.util.List.of(
                            configuration.legalPrivateRequirementsBoundaryMarker())));
        }

        private LegalRequiredSetAggregateStore storeFor(JdbcTemplate candidate) {
            var revision = configuration.legalPrivateRequirementsRevisionCalculator();
            var provenance = configuration.legalPrivateRequirementsProvenanceCalculator();
            var replay = configuration.legalPrivateRequirementsReplayVerifier(candidate, revision, provenance);
            return configuration.legalPrivateRequirementsStore(candidate, revision, provenance, replay);
        }

        private LegalPrivateRequirementsReadService service(LegalManifestDatabaseGate candidate) {
            return new LegalPrivateRequirementsReadService(jdbc, dataSource, candidate, resolver,
                    store, reader, actor, schema, privileges);
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }

}
