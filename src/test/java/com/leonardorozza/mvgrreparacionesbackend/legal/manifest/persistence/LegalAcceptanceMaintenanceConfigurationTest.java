package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegalAcceptanceMaintenanceConfigurationTest {
    private static final String PREFIX=LegalAcceptanceMaintenanceConfiguration.PROPERTY_PREFIX;
    private static final String ENABLED=LegalAcceptanceMaintenanceConfiguration.ENABLED_PROPERTY;
    private static final String SCHEDULED=LegalAcceptanceMaintenanceConfiguration.SCHEDULED_PROPERTY;

    @ParameterizedTest @NullSource @ValueSource(strings="false")
    void disabledRequiresNoCredentialsAndCreatesNothing(String flag) {
        try (var context=context(flag==null?Map.of():Map.of(ENABLED,flag))) {
            context.refresh();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalAcceptanceRetentionService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalAcceptanceMaintenanceScheduler.class)).isEmpty();
        }
    }
    @ParameterizedTest @ValueSource(strings={"", "TRUE", " false", "1", "true "})
    void malformedFlagsFailBeforeOpeningResources(String flag) {
        for(String name:new String[]{ENABLED,SCHEDULED}) {
            Map<String,Object> values=properties();values.put(name,flag);
            try(var context=unregistered(values)) {
                assertThatThrownBy(()->{context.register(LegalAcceptanceMaintenanceConfiguration.class);context.refresh();})
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(context.getBeanFactory().containsSingleton("legalMaintenancePool")).isFalse();
            }
        }
    }
    @Test void schedulingCannotBypassTheDisabledMaintenanceFlag() {
        try(var context=unregistered(Map.of(SCHEDULED,"true"))) {
            assertThatThrownBy(()->context.register(LegalAcceptanceMaintenanceConfiguration.class))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @ParameterizedTest @ValueSource(strings={"jdbc-url","username","password"})
    void missingMaintenanceCredentialsNeverFallBackToWebOrAcceptance(String suffix) {
        Map<String,Object> values=properties();values.remove(PREFIX+suffix);
        values.put("spring.datasource."+suffix,"web-value");
        values.put(LegalAcceptanceDatabaseConfiguration.PROPERTY_PREFIX+suffix,"acceptance-value");
        try(var context=context(values)) {
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }
    @ParameterizedTest @ValueSource(strings={"socketTimeout=0","options=-c%20search_path%3Devil","currentSchema=other","user=other","password=other","socket%54imeout=0","sslfactory=other"})
    void urlCannotOverrideIdentityOrBudgets(String query) {
        Map<String,Object> values=properties();values.put(PREFIX+"jdbc-url","jdbc:postgresql://localhost/fixture?"+query);
        try(var context=context(values)) {
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void contextNeedsNoKeysAndUsesItsOwnBoundedPoolAndTransaction() {
        Map<String,Object> values=properties();
        values.put("ordenfix.legal.idempotency.keyring.1","invalid-unused-key");
        values.put("ordenfix.legal.metadata.keyring.1","invalid-unused-key");
        try(var context=context(values)) {
            context.refresh();
            var pool=context.getBean(HikariDataSource.class);
            assertThat(pool.getUsername()).isEqualTo("maintenance_fixture");
            assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
            assertThat(pool.getMinimumIdle()).isZero();
            assertThat(pool.getConnectionTimeout()).isEqualTo(1_000);
            var transaction=context.getBean(TransactionTemplate.class);
            assertThat(transaction.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(transaction.getTimeout()).isEqualTo(15);assertThat(transaction.isReadOnly()).isFalse();
            assertThat(context.getBeansOfType(LegalAcceptanceKeyConfiguration.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalAcceptanceMaintenanceScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalAcceptanceRetentionService.class)).hasSize(1);
        }
    }
    @Test void enabledSchedulerIsExplicitAndDoesNotRunOnContextRefresh() {
        Map<String,Object> values=properties();values.put(SCHEDULED,"true");
        try(var context=context(values)) {
            context.refresh();
            assertThat(context.getBean(LegalAcceptanceMaintenanceScheduler.class).lastOutcome())
                    .isEqualTo(LegalAcceptanceMaintenanceScheduler.Outcome.NOT_RUN);
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
        }
    }
    @ParameterizedTest @EnumSource(value=LegalDatabaseBoundaryMarker.Kind.class,names="MAINTENANCE",mode=EnumSource.Mode.EXCLUDE)
    void mixedContextsAreRejected(LegalDatabaseBoundaryMarker.Kind other) {
        try(var context=context(properties())) {
            context.registerBean("foreignBoundary",LegalDatabaseBoundaryMarker.class,()->new LegalDatabaseBoundaryMarker(other));
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }
    @Test void maintenanceIsNotAComponentOrHttpController() {
        for(Class<?> type:new Class<?>[]{LegalAcceptanceMaintenanceConfiguration.class,LegalAcceptanceRetentionService.class,
                LegalAcceptanceMaintenanceScheduler.class,LegalAcceptanceMaintenanceBoundary.class})
            assertThat(AnnotatedElementUtils.hasAnnotation(type,Component.class)).as(type.getSimpleName()).isFalse();
    }
    @Test void mutableTransactionTemplateCannotChangeTheBoundary() {
        try(var context=context(properties())) {
            context.refresh();context.getBean(TransactionTemplate.class).setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThatThrownBy(()->context.getBean(LegalAcceptanceMaintenanceBoundary.class).execute(deadline->Boolean.TRUE))
                    .isInstanceOfSatisfying(LegalAcceptanceMaintenanceException.class,
                        failure->assertThat(failure.code()).isEqualTo(LegalAcceptanceMaintenanceException.Code.INVALID_BOUNDARY));
        }
    }
    @Test void schedulerDistinguishesRetryFromUnknownCommit() {
        var service=mock(LegalAcceptanceRetentionService.class);
        var scheduler=new LegalAcceptanceMaintenanceScheduler(service);
        when(service.runNext()).thenThrow(new IllegalStateException("synthetic-private-diagnostic"));
        scheduler.run();assertThat(scheduler.lastOutcome()).isEqualTo(LegalAcceptanceMaintenanceScheduler.Outcome.RETRY_REQUIRED);
        doThrow(new LegalAcceptanceMaintenanceException(LegalAcceptanceMaintenanceException.Code.UNKNOWN)).when(service).runNext();
        scheduler.run();assertThat(scheduler.lastOutcome()).isEqualTo(LegalAcceptanceMaintenanceScheduler.Outcome.RECONCILIATION_REQUIRED);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void schedulerReportsObservedBacklogWithoutClaimingGlobalCompletion(boolean pending) {
        var service=mock(LegalAcceptanceRetentionService.class);
        when(service.runNext()).thenReturn(new LegalAcceptanceRetentionService.Batch(0,0,0,0,0,0,pending));
        var scheduler=new LegalAcceptanceMaintenanceScheduler(service);
        scheduler.run();
        assertThat(scheduler.lastOutcome()).isEqualTo(pending
                ? LegalAcceptanceMaintenanceScheduler.Outcome.WORK_REMAINS
                : LegalAcceptanceMaintenanceScheduler.Outcome.BATCH_COMPLETED);
    }
    static Map<String,Object> properties() {
        Map<String,Object> values=new HashMap<>();values.put(ENABLED,"true");
        values.put(PREFIX+"jdbc-url","jdbc:postgresql://127.0.0.1:1/fixture");
        values.put(PREFIX+"username","maintenance_fixture");values.put(PREFIX+"password","synthetic-maintenance-password");
        return values;
    }
    static AnnotationConfigApplicationContext context(Map<String,?> properties) {
        var context=unregistered(properties);context.register(LegalAcceptanceMaintenanceConfiguration.class);return context;
    }
    private static AnnotationConfigApplicationContext unregistered(Map<String,?> properties) {
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("maintenance-fixture",new HashMap<>(properties)));
        return context;
    }
}
