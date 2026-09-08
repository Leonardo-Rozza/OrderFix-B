package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LegalPublicRequirementsDatabaseConfigurationTest {

    private static final String ENABLED = LegalPublicRequirementsDatabaseConfiguration.ENABLED_PROPERTY;
    private static final String PREFIX = LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String UNCONNECTED_URL = "jdbc:postgresql://127.0.0.1:1/unused_public_requirements";
    private static final String REQUIREMENTS_USER = "legal_requirements_configuration_test";
    private static final String REQUIREMENTS_PASSWORD = "configuration-test-only";
    private final LegalPublicRequirementsDatabaseConfiguration configuration =
            new LegalPublicRequirementsDatabaseConfiguration();

    @Test
    void requiresExplicitRegistrationAndItsOwnTrueFlagWithoutAutoconfiguration() {
        Class<?> type = LegalPublicRequirementsDatabaseConfiguration.class;
        ConditionalOnProperty conditional = type.getAnnotation(ConditionalOnProperty.class);

        assertThat(conditional).isNotNull();
        assertThat(conditional.name()).containsExactly(ENABLED);
        assertThat(conditional.havingValue()).isEqualTo("true");
        assertThat(conditional.matchIfMissing()).isFalse();
        assertThat(type.isAnnotationPresent(Configuration.class)).isFalse();
        assertThat(type.isAnnotationPresent(ImportAutoConfiguration.class)).isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(type, Component.class)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "http-only", "on", "yes", "1", " true ", ""})
    void registersNoGraphWithoutItsExactContextFlag(String mode) {
        Map<String, Object> properties = new LinkedHashMap<>(webProperties());
        if (mode.equals("http-only")) {
            properties.put("ordenfix.legal.public-requirements.enabled", "true");
        } else if (!mode.equals("absent")) {
            properties.put(ENABLED, mode);
        }

        try (AnnotationConfigApplicationContext context = newContext(properties)) {
            context.refresh();
            assertThat(context.getBeansOfType(LegalPublicRequirementsDatabaseConfiguration.class)).isEmpty();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
            assertThat(context.getBeansOfType(DataSourceTransactionManager.class)).isEmpty();
            assertThat(context.getBeansOfType(TransactionTemplate.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalManifestDatabaseGate.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalRequiredSetAggregateStore.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalPublicRequirementsReader.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalPublicRequirementsReadService.class)).isEmpty();
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
                .isThrownBy(() -> configuration.legalPublicRequirementsPool(environment))
                .withMessageContaining(PREFIX + missing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc-url", "username", "password"})
    void blankDedicatedPropertiesAreRejected(String blank) {
        MockEnvironment environment = configuredEnvironment();
        environment.setProperty(PREFIX + blank, " \t ");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> configuration.legalPublicRequirementsPool(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "user=another_role", "username=another_role", "password=another_password",
            "%75ser=another_role", "u%73er=another_role", "connectTimeout=600", "loginTimeout=600",
            "socketTimeout=0", "cancelSignalTimeout=600", "socketFactory=example.UntrustedSocketFactory",
            "socketFactoryArg=untrusted", "sslfactory=example.UntrustedSslFactory",
            "sslhostnameverifier=example.UntrustedHostnameVerifier", "currentSchema=untrusted",
            "options=-c%20search_path%3Duntrusted", "%6fptions=-c%20search_path%3Duntrusted",
            "ApplicationName=other_context", "defaultRowFetchSize=0", "maxResultBuffer=1G",
            "readOnly=true", "readOnly=false", "readOnlyMode=ignore", "sslmode=disable&user=another_role"
    })
    void rejectsUrlOverridesOfIdentitySessionDriverOrBudgets(String query) {
        MockEnvironment environment = configuredEnvironment();
        environment.setProperty(PREFIX + "jdbc-url", UNCONNECTED_URL + "?" + query);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> configuration.legalPublicRequirementsPool(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:h2:mem:requirements", "jdbc:postgresql:implicit_database",
            "postgresql://127.0.0.1:1/requirements", "jdbc:postgresql://127.0.0.1:1/requirements#user=other"
    })
    void requiresAnExplicitPostgresUrlWithoutFragments(String url) {
        MockEnvironment environment = configuredEnvironment();
        environment.setProperty(PREFIX + "jdbc-url", url);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> configuration.legalPublicRequirementsPool(environment));
    }

    @Test
    void transportOptionsCannotReplaceDedicatedCredentialsOrFixedPoolPolicy() {
        String url = UNCONNECTED_URL + "?sslmode=verify-full&sslrootcert=/unused/root.crt"
                + "&sslcert=/unused/client.crt&sslkey=/unused/client.key"
                + "&sslpassword=transport-test-only&loggerLevel=OFF";
        MockEnvironment environment = configuredEnvironment();
        environment.setProperty(PREFIX + "jdbc-url", url);
        environment.setProperty("spring.datasource.hikari.maximum-pool-size", "80");
        environment.setProperty(PREFIX + "maximum-pool-size", "80");
        environment.setProperty(PREFIX + "connection-timeout", "60000");

        // Empty pool only: minimumIdle=0, initializationFailTimeout=-1 and no borrow.
        try (HikariDataSource pool = configuration.legalPublicRequirementsPool(environment)) {
            assertThat(pool.getJdbcUrl()).isEqualTo(url);
            assertThat(pool.getUsername()).isEqualTo(REQUIREMENTS_USER);
            assertThat(pool.getPassword()).isEqualTo(REQUIREMENTS_PASSWORD);
            assertPoolPolicy(pool);
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True"})
    void enabledContextAssemblesOneMutableGraphAndClosesItWithoutOpeningConnections(String flag) {
        Map<String, Object> properties = enabledProperties();
        properties.put(ENABLED, flag);
        HikariDataSource pool;
        LegalPublicRequirementsDataSource dataSource;
        LegalPublicRequirementsReadService service;

        try (AnnotationConfigApplicationContext context = newContext(properties)) {
            context.refresh();
            pool = context.getBean("legalPublicRequirementsPool", HikariDataSource.class);
            dataSource = context.getBean(LegalPublicRequirementsDataSource.class);
            service = context.getBean(LegalPublicRequirementsReadService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            DataSourceTransactionManager manager = context.getBean(DataSourceTransactionManager.class);
            TransactionTemplate transaction = context.getBean(TransactionTemplate.class);
            LegalDatabaseBudgets budgets = context.getBean(LegalDatabaseBudgets.class);
            LegalV28AggregateSchemaVerifier schema = context.getBean(LegalV28AggregateSchemaVerifier.class);
            LegalPublicRequirementsPrivilegeVerifier privileges =
                    context.getBean(LegalPublicRequirementsPrivilegeVerifier.class);
            LegalManifestDatabaseGate gate = context.getBean(LegalManifestDatabaseGate.class);
            LegalRequiredSetAggregateStore store = context.getBean(LegalRequiredSetAggregateStore.class);
            LegalRequiredSetAggregateReplayVerifier replay =
                    context.getBean(LegalRequiredSetAggregateReplayVerifier.class);
            LegalPublicRequirementsReader reader = context.getBean(LegalPublicRequirementsReader.class);

            assertPoolPolicy(pool);
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
            assertThat(context.getParent()).isNull();
            assertThat(context.getBeansOfType(DataSource.class)).containsOnlyKeys(
                    "legalPublicRequirementsPool", "legalPublicRequirementsDataSource");
            assertThat(context.getBeansOfType(JdbcTemplate.class)).hasSize(1);
            assertThat(context.getBeansOfType(DataSourceTransactionManager.class)).hasSize(1);
            assertThat(context.getBeansOfType(TransactionTemplate.class)).hasSize(1);
            assertThat(jdbc.getDataSource()).isSameAs(dataSource).isNotSameAs(pool);
            assertThat(manager).isExactlyInstanceOf(DataSourceTransactionManager.class);
            assertThat(manager.getDataSource()).isSameAs(dataSource);
            assertThat(manager.isEnforceReadOnly()).isFalse();
            assertThat(manager.isRollbackOnCommitFailure()).isFalse();
            assertThat(transaction.getTransactionManager()).isSameAs(manager);
            assertThat(transaction.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(transaction.isReadOnly()).isFalse();
            assertThat(transaction.getTimeout()).isEqualTo(15);
            assertThat(budgets).isEqualTo(new LegalDatabaseBudgets(15, 5, 1, 1));
            assertThatCode(() -> gate.requireExactPublicRequirementsBoundary(jdbc, schema, privileges))
                    .doesNotThrowAnyException();
            assertThat(gate.usesJdbc(jdbc)).isTrue();
            assertThat(store.usesJdbc(jdbc)).isTrue();
            assertThat(replay.usesJdbc(jdbc)).isTrue();
            assertThat(reader.usesJdbc(jdbc)).isTrue();
            assertThat(context.getBeansOfType(LegalPublicRequirementsReadService.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalPublicRequirementsReader.class)).hasSize(1);
            assertThat(schema.usesJdbc(jdbc)).isTrue();
            assertThat(schema.expectedSchema()).isEqualTo("public");
            assertThat(privileges.usesJdbc(jdbc)).isTrue();
            assertThat(privileges.expectedRole()).isEqualTo(REQUIREMENTS_USER);
            assertThat(context.getBeansOfType(LegalRequiredSetAggregateRevisionCalculator.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalRequiredSetAggregateProvenanceCalculator.class)).hasSize(1);
            assertThat(context.getBean(LegalApplicableScopeResolver.class).resolve(
                    PerfilAgregadoLegal.REGISTRATION, LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR).contexts())
                    .containsExactly(ContextoLegal.REGISTRO);
            assertThat(context.getBeansOfType(LegalRequiredSetAggregateService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalPublicDocumentReadService.class)).isEmpty();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(context.getBeanNamesForAnnotation(Controller.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.class).values())
                    .extracting(LegalDatabaseBoundaryMarker::kind)
                    .containsExactly(LegalDatabaseBoundaryMarker.Kind.PUBLIC_REQUIREMENTS);
            assertThat(context.getBeansOfType(LegalDatabaseBoundaryMarker.Guard.class)).hasSize(1);
        }
        assertThat(pool.isClosed()).isTrue();
        assertThatThrownBy(() -> dataSource.withinDeadline(deadline -> "closed"))
                .isInstanceOf(LegalPublicRequirementsReadException.class);
        assertThatThrownBy(service::readRegistration).isInstanceOf(LegalPublicRequirementsReadException.class);
    }

    @ParameterizedTest
    @EnumSource(value = LegalDatabaseBoundaryMarker.Kind.class,
            mode = EnumSource.Mode.EXCLUDE, names = "PUBLIC_REQUIREMENTS")
    void refreshRejectsEveryForeignBoundary(LegalDatabaseBoundaryMarker.Kind foreignKind) {
        try (AnnotationConfigApplicationContext context = newContext(enabledProperties())) {
            context.registerBean("foreignLegalBoundary", LegalDatabaseBoundaryMarker.class,
                    () -> new LegalDatabaseBoundaryMarker(foreignKind));
            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Los contextos DB legales no pueden combinarse");
        }
    }

    @Test
    void guardRejectsMissingDuplicateAndImpersonatedBoundaries() {
        LegalDatabaseBoundaryMarker marker = configuration.legalPublicRequirementsBoundaryMarker();
        LegalDatabaseBoundaryMarker aggregate = new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.AGGREGATE);
        assertThatCode(() -> configuration.legalPublicRequirementsBoundaryGuard(List.of(marker)))
                .doesNotThrowAnyException();
        assertThatIllegalStateException().isThrownBy(
                () -> configuration.legalPublicRequirementsBoundaryGuard(List.of()));
        assertThatIllegalStateException().isThrownBy(
                () -> configuration.legalPublicRequirementsBoundaryGuard(List.of(marker, marker)));
        assertThatIllegalStateException().isThrownBy(
                () -> configuration.legalPublicRequirementsBoundaryGuard(List.of(aggregate)));
    }

    @Test
    void failedRefreshClosesTheAlreadyConstructedPoolAndWrapper() {
        AtomicReference<HikariDataSource> observedPool = new AtomicReference<>();
        AtomicReference<LegalPublicRequirementsDataSource> observedDataSource = new AtomicReference<>();
        try (AnnotationConfigApplicationContext context = newContext(enabledProperties())) {
            context.registerBean("failAfterLegalGraph", Object.class, () -> {
                context.getBean(LegalPublicRequirementsReadService.class);
                observedPool.set(context.getBean(HikariDataSource.class));
                observedDataSource.set(context.getBean(LegalPublicRequirementsDataSource.class));
                throw new IllegalStateException("configuration-test-refresh-failure");
            });
            assertThatThrownBy(context::refresh).hasRootCauseMessage("configuration-test-refresh-failure");
            assertThat(observedPool.get()).isNotNull();
            assertThat(observedPool.get().getHikariPoolMXBean().getTotalConnections()).isZero();
            assertThat(observedPool.get().isClosed()).isTrue();
            assertThat(observedDataSource.get()).isNotNull();
            assertThatThrownBy(() -> observedDataSource.get().withinDeadline(deadline -> "closed"))
                    .isInstanceOf(LegalPublicRequirementsReadException.class);
        }
    }

    @Test
    void serviceConstructorRejectsForeignStoreAndReaderEvenWhenTheirDatasourceMatches() {
        try (ServiceComposition composition = new ServiceComposition()) {
            JdbcTemplate otherJdbc = new JdbcTemplate(composition.dataSource);
            LegalRequiredSetAggregateStore foreignStore = composition.storeFor(otherJdbc);
            LegalPublicRequirementsReader foreignReader = new LegalPublicRequirementsReader(otherJdbc);

            assertThatIllegalArgumentException().isThrownBy(() -> composition.service(
                    composition.gate(), foreignStore, composition.reader));
            assertThatIllegalArgumentException().isThrownBy(() -> composition.service(
                    composition.gate(), composition.store, foreignReader));
            verifyNoInteractions(composition.pool);
        }
    }

    @Test
    void serviceConstructorRejectsADifferentBoundedDatasourceOrGateJdbcBeforeBorrowing() {
        try (ServiceComposition composition = new ServiceComposition();
             LegalPublicRequirementsDataSource otherSource =
                     new LegalPublicRequirementsDataSource(composition.pool, Duration.ofSeconds(15))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new LegalPublicRequirementsReadService(
                    composition.jdbc, otherSource, composition.gate(), composition.resolver,
                    composition.store, composition.reader, composition.schema, composition.privileges));

            LegalManifestDatabaseGate foreignGate = new LegalManifestDatabaseGate(
                    composition.transaction, new JdbcTemplate(composition.dataSource), composition.budgets,
                    List.of(composition.schema, composition.privileges));
            assertThatIllegalArgumentException().isThrownBy(() -> composition.service(
                    foreignGate, composition.store, composition.reader));
            verifyNoInteractions(composition.pool);
        }
    }

    @Test
    void serviceConstructorReaccreditsTheMutableTransactionAndExactPreflightPair() {
        try (ServiceComposition composition = new ServiceComposition()) {
            LegalManifestDatabaseGate gate = composition.gate();
            composition.transaction.setReadOnly(true);
            assertThatIllegalArgumentException().isThrownBy(() -> composition.service(
                    gate, composition.store, composition.reader));
            composition.transaction.setReadOnly(false);
            composition.transaction.setTimeout(14);
            assertThatIllegalArgumentException().isThrownBy(() -> composition.service(
                    gate, composition.store, composition.reader));
            composition.transaction.setTimeout(15);

            LegalV28AggregateSchemaVerifier foreignSchema =
                    new LegalV28AggregateSchemaVerifier(new JdbcTemplate(composition.dataSource), "public");
            for (List<LegalDatabasePreflight> preflights : List.<List<LegalDatabasePreflight>>of(
                    List.<LegalDatabasePreflight>of(),
                    List.of(composition.schema),
                    List.of(composition.privileges, composition.schema),
                    List.of(composition.schema, composition.privileges, composition.schema),
                    List.of(foreignSchema, composition.privileges))) {
                LegalManifestDatabaseGate invalidGate = new LegalManifestDatabaseGate(
                        composition.transaction, composition.jdbc, composition.budgets, preflights);
                assertThatIllegalArgumentException().isThrownBy(() -> composition.service(
                        invalidGate, composition.store, composition.reader));
            }
            assertThatCode(() -> composition.service(composition.gate(), composition.store, composition.reader))
                    .doesNotThrowAnyException();
            verifyNoInteractions(composition.pool);
        }
    }

    @Test
    void serviceExposesOnlyHistoricalAndBudgetedRegistrationWithoutHttpOrPublicConstruction() throws NoSuchMethodException {
        Class<?> type = LegalPublicRequirementsReadService.class;
        assertThat(Modifier.isPublic(type.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
        assertThat(type.getConstructors()).isEmpty();
        assertThat(type.getDeclaredAnnotations()).isEmpty();
        var methods = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())).toList();
        assertThat(methods).containsExactlyInAnyOrder(
                type.getDeclaredMethod("readRegistration"),
                type.getDeclaredMethod("readRegistration", LegalRegistrationBudget.class));
        assertThat(methods).allSatisfy(method -> {
            assertThat(Modifier.isStatic(method.getModifiers())).isFalse();
            assertThat(method.getReturnType()).isEqualTo(LegalPublicRegistrationRequirements.class);
            assertThat(method.getDeclaredAnnotations()).isEmpty();
        });
    }

    private static final class ServiceComposition implements AutoCloseable {
        private final LegalPublicRequirementsDatabaseConfiguration configuration =
                new LegalPublicRequirementsDatabaseConfiguration();
        private final DataSource pool = mock(DataSource.class);
        private final LegalPublicRequirementsDataSource dataSource =
                new LegalPublicRequirementsDataSource(pool, Duration.ofSeconds(15));
        private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        private final LegalDatabaseBudgets budgets = configuration.legalPublicRequirementsBudgets();
        private final TransactionTemplate transaction = configuration.legalPublicRequirementsTransactionTemplate(
                configuration.legalPublicRequirementsTransactionManager(dataSource), budgets);
        private final LegalV28AggregateSchemaVerifier schema =
                configuration.legalPublicRequirementsSchemaVerifier(jdbc);
        private final LegalPublicRequirementsPrivilegeVerifier privileges =
                configuration.legalPublicRequirementsPrivilegeVerifier(jdbc, configuredEnvironment());
        private final LegalApplicableScopeResolver resolver = configuration.legalPublicRequirementsScopeResolver();
        private final LegalRequiredSetAggregateStore store = storeFor(jdbc);
        private final LegalPublicRequirementsReader reader = configuration.legalPublicRequirementsReader(jdbc);

        private LegalManifestDatabaseGate gate() {
            return configuration.legalPublicRequirementsGate(transaction, jdbc, budgets, schema, privileges,
                    configuration.legalPublicRequirementsBoundaryGuard(
                            List.of(configuration.legalPublicRequirementsBoundaryMarker())));
        }

        private LegalRequiredSetAggregateStore storeFor(JdbcTemplate candidate) {
            LegalRequiredSetAggregateRevisionCalculator revision =
                    configuration.legalPublicRequirementsRevisionCalculator();
            LegalRequiredSetAggregateProvenanceCalculator provenance =
                    configuration.legalPublicRequirementsProvenanceCalculator();
            LegalRequiredSetAggregateReplayVerifier replay =
                    configuration.legalPublicRequirementsReplayVerifier(candidate, revision, provenance);
            return configuration.legalPublicRequirementsStore(candidate, revision, provenance, replay);
        }

        private LegalPublicRequirementsReadService service(LegalManifestDatabaseGate candidateGate,
                LegalRequiredSetAggregateStore candidateStore, LegalPublicRequirementsReader candidateReader) {
            return new LegalPublicRequirementsReadService(jdbc, dataSource, candidateGate, resolver,
                    candidateStore, candidateReader, schema, privileges);
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }

    private static void assertPoolPolicy(HikariDataSource pool) {
        assertThat(pool.getPoolName()).isEqualTo("legal-public-requirements");
        assertThat(pool.getDriverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
        assertThat(pool.getMinimumIdle()).isZero();
        assertThat(pool.getConnectionTimeout()).isEqualTo(1_000);
        assertThat(pool.getValidationTimeout()).isEqualTo(1_000);
        assertThat(pool.getInitializationFailTimeout()).isEqualTo(-1);
        assertThat(pool.isReadOnly()).isFalse();
        assertThat(pool.getDataSourceProperties()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "connectTimeout", "1", "loginTimeout", "1", "socketTimeout", "5",
                "cancelSignalTimeout", "1", "ApplicationName", "ordenfix-legal-public-requirements"));
    }

    private static AnnotationConfigApplicationContext newContext(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("requirements-configuration-test", properties));
        context.register(LegalPublicRequirementsDatabaseConfiguration.class);
        return context;
    }

    private static MockEnvironment configuredEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        webProperties().forEach((key, value) -> environment.setProperty(key, value.toString()));
        dedicatedProperties().forEach((key, value) -> environment.setProperty(key, value.toString()));
        return environment;
    }

    private static Map<String, Object> enabledProperties() {
        Map<String, Object> properties = new LinkedHashMap<>(webProperties());
        properties.putAll(dedicatedProperties());
        properties.put(ENABLED, "true");
        return properties;
    }

    private static Map<String, Object> dedicatedProperties() {
        return Map.of(PREFIX + "jdbc-url", UNCONNECTED_URL,
                PREFIX + "username", REQUIREMENTS_USER,
                PREFIX + "password", REQUIREMENTS_PASSWORD);
    }

    private static Map<String, Object> webProperties() {
        return Map.of("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/unused_web",
                "spring.datasource.username", "unrelated_web_owner",
                "spring.datasource.password", "web-configuration-test-only");
    }
}
