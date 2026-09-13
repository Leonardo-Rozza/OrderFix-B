package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Explicit isolated context only. Not scanned/imported by HTTP, no fallback datasource, keys or Flyway. */
@Conditional(LegalAcceptanceMaintenanceConfiguration.Enabled.class)
@Import(LegalAcceptanceMaintenanceConfiguration.Scheduling.class)
public class LegalAcceptanceMaintenanceConfiguration {
    public static final String PROPERTY_PREFIX="ordenfix.legal.maintenance.";
    public static final String ENABLED_PROPERTY=PROPERTY_PREFIX+"enabled";
    public static final String SCHEDULED_PROPERTY=PROPERTY_PREFIX+"scheduled";
    private static final Set<String> URL_OPTIONS=Set.of("sslmode","sslrootcert","sslcert","sslkey","sslpassword","loggerLevel");

    @Bean LegalDatabaseBoundaryMarker legalMaintenanceMarker() {
        return new LegalDatabaseBoundaryMarker(LegalDatabaseBoundaryMarker.Kind.MAINTENANCE);
    }
    @Bean LegalDatabaseBoundaryMarker.Guard legalMaintenanceGuard(List<LegalDatabaseBoundaryMarker> markers) {
        return new LegalDatabaseBoundaryMarker.Guard(markers,LegalDatabaseBoundaryMarker.Kind.MAINTENANCE);
    }
    @Bean(destroyMethod="close") HikariDataSource legalMaintenancePool(Environment environment) {
        String url=required(environment,"jdbc-url"); requireUrl(url);
        String username=required(environment,"username"),password=required(environment,"password");
        HikariConfig config=new HikariConfig();
        config.setPoolName("legal-maintenance");config.setJdbcUrl(url);
        config.setUsername(username);config.setPassword(password);config.setDriverClassName("org.postgresql.Driver");
        config.setMinimumIdle(0);config.setMaximumPoolSize(2);config.setConnectionTimeout(1_000);
        config.setValidationTimeout(1_000);config.setInitializationFailTimeout(-1);config.setReadOnly(false);
        config.addDataSourceProperty("connectTimeout","1");config.addDataSourceProperty("loginTimeout","1");
        config.addDataSourceProperty("socketTimeout","6");config.addDataSourceProperty("cancelSignalTimeout","1");
        config.addDataSourceProperty("ApplicationName","ordenfix-legal-maintenance");
        return new HikariDataSource(config);
    }
    @Bean(destroyMethod="close") LegalPrivateRequirementsDataSource legalMaintenanceSource(
            @Qualifier("legalMaintenancePool") HikariDataSource pool) {
        return new LegalPrivateRequirementsDataSource(pool,Duration.ofSeconds(15),System::nanoTime,1_000);
    }
    @Bean JdbcTemplate legalMaintenanceJdbc(LegalPrivateRequirementsDataSource source) { return new JdbcTemplate(source); }
    @Bean DataSourceTransactionManager legalMaintenanceManager(LegalPrivateRequirementsDataSource source) {
        var manager=new DataSourceTransactionManager(source);
        manager.setRollbackOnCommitFailure(false);manager.setEnforceReadOnly(false);return manager;
    }
    @Bean TransactionTemplate legalMaintenanceTransaction(DataSourceTransactionManager manager) {
        var transaction=new TransactionTemplate(manager);
        transaction.setName("legal-maintenance");transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setReadOnly(false);transaction.setTimeout(15);
        return transaction;
    }
    @Bean LegalV29AcceptanceSchemaVerifier legalMaintenanceSchema(JdbcTemplate jdbc) {
        return new LegalV29AcceptanceSchemaVerifier(jdbc,"public");
    }
    @Bean LegalAcceptanceMaintenancePrivilegeVerifier legalMaintenancePrivileges(JdbcTemplate jdbc,Environment environment) {
        return new LegalAcceptanceMaintenancePrivilegeVerifier(jdbc,required(environment,"username"),"public");
    }
    @Bean LegalAcceptanceMaintenanceBoundary legalMaintenanceBoundary(JdbcTemplate jdbc,LegalPrivateRequirementsDataSource source,
            TransactionTemplate transaction,LegalV29AcceptanceSchemaVerifier schema,LegalAcceptanceMaintenancePrivilegeVerifier privileges,
            LegalDatabaseBoundaryMarker.Guard guard) {
        return new LegalAcceptanceMaintenanceBoundary(jdbc,source,transaction,schema,privileges);
    }
    @Bean LegalAcceptanceRetentionService legalAcceptanceRetentionService(JdbcTemplate jdbc,LegalAcceptanceMaintenanceBoundary boundary) {
        return new LegalAcceptanceRetentionService(jdbc,boundary);
    }
    private static String required(Environment environment,String suffix) {
        String value=environment.getProperty(PROPERTY_PREFIX+suffix);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Falta configuración propia de mantenimiento legal");
        return value;
    }
    private static void requireUrl(String url) {
        if (!url.startsWith("jdbc:postgresql://") || url.indexOf('#')>=0)
            throw new IllegalArgumentException("El mantenimiento requiere URL PostgreSQL explícita");
        int query=url.indexOf('?');
        if (query>=0) for (String option:url.substring(query+1).split("&")) {
            String key=URLDecoder.decode(option.split("=",2)[0],StandardCharsets.UTF_8);
            if (!URL_OPTIONS.contains(key)) throw new IllegalArgumentException("La URL de mantenimiento no admite sustituir credenciales o límites");
        }
    }
    private static boolean flag(Environment environment,String property) {
        String value=environment.getProperty(property);
        if (value==null || "false".equals(value)) return false;
        if (!"true".equals(value)) throw new IllegalArgumentException("Los flags de mantenimiento requieren true o false exactos");
        return true;
    }
    static final class Enabled implements Condition {
        public boolean matches(ConditionContext context,AnnotatedTypeMetadata metadata) {
            boolean enabled=flag(context.getEnvironment(),ENABLED_PROPERTY);
            boolean scheduled=flag(context.getEnvironment(),SCHEDULED_PROPERTY);
            if (scheduled && !enabled) throw new IllegalArgumentException("El scheduler requiere mantenimiento habilitado");
            return enabled;
        }
    }
    static final class ScheduledOnly implements Condition {
        public boolean matches(ConditionContext context,AnnotatedTypeMetadata metadata) {
            return flag(context.getEnvironment(),SCHEDULED_PROPERTY);
        }
    }
    @Conditional(ScheduledOnly.class)
    @EnableScheduling
    static class Scheduling {
        @Bean LegalAcceptanceMaintenanceScheduler legalMaintenanceScheduler(LegalAcceptanceRetentionService service) {
            return new LegalAcceptanceMaintenanceScheduler(service);
        }
    }
}
