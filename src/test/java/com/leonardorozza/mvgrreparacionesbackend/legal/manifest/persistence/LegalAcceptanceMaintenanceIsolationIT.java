package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/** Real configuration, restricted LOGIN and PostgreSQL commit/rollback. No real provider or data. */
@Testcontainers
class LegalAcceptanceMaintenanceIsolationIT {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_maintenance_isolation").withUsername("fixture").withPassword("fixture-password");
    static final String ROLE="legal_maintenance_isolation", PASSWORD="synthetic-maintenance-password";
    static JdbcTemplate owner;
    static DriverManagerDataSource ownerSource;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;
    static LegalAcceptanceMaintenanceBoundary boundary;
    static LegalPrivateRequirementsDataSource source;

    @BeforeAll static void database() {
        ownerSource=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());owner=new JdbcTemplate(ownerSource);
        Flyway.configure().dataSource(ownerSource).locations("classpath:db/migration").load().migrate();
        LegalRestrictedMaintenanceRoleFixture.provision(owner,ROLE,PASSWORD);
        Map<String,Object> values=LegalAcceptanceMaintenanceConfigurationTest.properties();
        String prefix=LegalAcceptanceMaintenanceConfiguration.PROPERTY_PREFIX;
        values.put(prefix+"jdbc-url",PG.getJdbcUrl());values.put(prefix+"username",ROLE);values.put(prefix+"password",PASSWORD);
        context=LegalAcceptanceMaintenanceConfigurationTest.context(values);context.refresh();
        jdbc=context.getBean(JdbcTemplate.class);source=context.getBean(LegalPrivateRequirementsDataSource.class);
        boundary=context.getBean(LegalAcceptanceMaintenanceBoundary.class);
    }
    @AfterAll static void closeContext() { if(context!=null)context.close(); }

    @Test void callbackUsesItsRestrictedReadCommittedPhysicalTransactionAndCommits() {
        assertThat(boundary.<String>execute(deadline -> {
            assertThat(jdbc.queryForMap("SELECT current_user::text AS role,session_user::text AS session,current_setting('transaction_isolation') AS isolation,current_setting('transaction_read_only') AS readonly"))
                    .containsEntry("role",ROLE).containsEntry("session",ROLE).containsEntry("isolation","read committed").containsEntry("readonly","off");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return "confirmed";
        })).isEqualTo("confirmed");
        assertClean();
    }
    @Test void emptySweepReturnsCommittedCountsAndDoesNotRequireAesOrHmacKeys() {
        var result=context.getBean(LegalAcceptanceRetentionService.class).runNext();
        assertThat(result.metadataHeaders()+result.metadataFields()+result.resultsWithActs()+result.resultsWithoutActs()+result.references()).isZero();
        assertThat(result.pending()).isFalse();assertClean();
    }
    @Test void sameSourceOuterReadOnlyRepeatableReadIsSuspendedAndRestored() {
        var outer=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);outer.setReadOnly(true);outer.setTimeout(15);
        source.withinDeadline(deadline -> outer.execute(status -> {
            Object holder=TransactionSynchronizationManager.getResource(source);
            int pid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
            assertThat(boundary.<String>execute(inner -> {
                assertThat(TransactionSynchronizationManager.getResource(source)).isNotSameAs(holder);
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)).isNotEqualTo(pid);
                assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("read committed");return "inner";
            })).isEqualTo("inner");
            assertThat(TransactionSynchronizationManager.getResource(source)).isSameAs(holder);
            assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();status.setRollbackOnly();return true;
        }));assertClean();
    }
    @Test void ownerTransactionCannotSupplyItsRoleConnectionOrUncommittedState() {
        AtomicLong workshop=new AtomicLong();
        new TransactionTemplate(new DataSourceTransactionManager(ownerSource)).executeWithoutResult(status -> {
            workshop.set(owner.queryForObject("INSERT INTO talleres(nombre) VALUES('outer synthetic') RETURNING id",Long.class));
            Object holder=TransactionSynchronizationManager.getResource(ownerSource);
            int pid=owner.queryForObject("SELECT pg_backend_pid()",Integer.class);
            boundary.execute(deadline -> {
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)).isNotEqualTo(pid);
                assertThat(jdbc.queryForObject("SELECT current_user::text",String.class)).isEqualTo(ROLE);return true;
            });
            assertThat(TransactionSynchronizationManager.getResource(ownerSource)).isSameAs(holder);status.setRollbackOnly();
        });
        assertThat(owner.queryForObject("SELECT count(*) FROM talleres WHERE id=?",Long.class,workshop.get())).isZero();assertClean();
    }
    @Test void unsafePrivilegesFailBeforeTheCallbackAndDoNotLeakDiagnostics() {
        AtomicBoolean called=new AtomicBoolean();owner.execute("GRANT INSERT ON public.clientes TO "+ROLE);
        try {
            assertUnavailable(()->boundary.execute(deadline->{called.set(true);return true;}));
            assertThat(called).isFalse();
        } finally {owner.execute("REVOKE INSERT ON public.clientes FROM "+ROLE);}
        assertThat(boundary.<Boolean>execute(deadline->true)).isTrue();assertClean();
    }
    @Test void schemaDriftFailsBeforeTheCallback() {
        AtomicBoolean called=new AtomicBoolean();
        owner.execute("ALTER TABLE legal_aceptacion_metadatos DISABLE TRIGGER trg_legal_metadata_header_update");
        try {
            assertUnavailable(()->boundary.execute(deadline->{called.set(true);return true;}));assertThat(called).isFalse();
        } finally {owner.execute("ALTER TABLE legal_aceptacion_metadatos ENABLE TRIGGER trg_legal_metadata_header_update");}
        assertThat(boundary.<Boolean>execute(deadline->true)).isTrue();assertClean();
    }
    @Test void ciphertextIsNotReadableByTheMaintenanceRole() {
        assertUnavailable(()->boundary.execute(deadline->{jdbc.queryForList("SELECT ciphertext FROM legal_aceptacion_metadatos_cifrados");return true;}));
        assertClean();
    }
    @Test void callbackFailureReturnsNoReceiptAndReleasesTheTransaction() {
        assertUnavailable(()->boundary.execute(deadline->{throw new IllegalStateException("private SQL or HMAC diagnostic");}));
        assertThat(boundary.<Boolean>execute(deadline->true)).isTrue();assertClean();
    }
    @ParameterizedTest @ValueSource(strings={"commit-before","commit-after","close-after","deadline-after-commit"})
    void failedOrLateCommitAndReleaseNeverDeliverASuccessReceipt(String phase) {
        AtomicLong monotonic=new AtomicLong(System.nanoTime());AtomicBoolean intercepted=new AtomicBoolean();
        var raw=new DriverManagerDataSource(PG.getJdbcUrl(),ROLE,PASSWORD);
        var decorated=new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection=raw.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    boolean commit=method.getName().equals("commit"),close=method.getName().equals("close");
                    if(commit && phase.equals("commit-before")){intercepted.set(true);throw new SQLException("synthetic commit diagnostic","08006");}
                    Object result;
                    try {result=method.invoke(connection,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                    if(commit && phase.equals("deadline-after-commit")){intercepted.set(true);monotonic.addAndGet(Duration.ofSeconds(16).toNanos());}
                    if((commit && phase.equals("commit-after")) || (close && phase.equals("close-after"))) {
                        intercepted.set(true);throw new SQLException("synthetic close diagnostic","08006");
                    }
                    return result;
                });
            }
            @Override public Connection getConnection(String username,String password) throws SQLException {throw new SQLException("unexpected credentials");}
        };
        try(var bounded=new LegalPrivateRequirementsDataSource(decorated,Duration.ofSeconds(15),monotonic::get,1_000)) {
            var scopedJdbc=new JdbcTemplate(bounded);var config=new LegalAcceptanceMaintenanceConfiguration();
            var manager=config.legalMaintenanceManager(bounded);var transaction=config.legalMaintenanceTransaction(manager);
            var scoped=new LegalAcceptanceMaintenanceBoundary(scopedJdbc,bounded,transaction,
                    new LegalV29AcceptanceSchemaVerifier(scopedJdbc,"public"),new LegalAcceptanceMaintenancePrivilegeVerifier(scopedJdbc,ROLE,"public"));
            assertThatThrownBy(()->scoped.execute(deadline->"tentative"))
                    .isInstanceOfSatisfying(LegalAcceptanceMaintenanceException.class,
                        failure->assertThat(failure.code()).isEqualTo(LegalAcceptanceMaintenanceException.Code.UNKNOWN))
                    .hasMessage("No se pudo acreditar el mantenimiento legal.").hasNoCause();
            assertThat(intercepted).isTrue();
            assertThat(TransactionSynchronizationManager.hasResource(bounded)).isFalse();
        }
        assertClean();
    }
    private static void assertUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(LegalAcceptanceMaintenanceException.class,
                failure->assertThat(failure.code()).isEqualTo(LegalAcceptanceMaintenanceException.Code.UNAVAILABLE))
                .hasMessage("No se pudo acreditar el mantenimiento legal.").hasNoCause();
    }
    private static void assertClean() {
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
        assertThat(context.getBean(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
