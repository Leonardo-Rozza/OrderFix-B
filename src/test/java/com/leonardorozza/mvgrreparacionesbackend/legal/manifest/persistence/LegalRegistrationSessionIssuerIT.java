package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.AccountSessionPolicy;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.LegalRegistrationSessionIssuer;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.LegalRegistrationSessionIssuerConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real Boot/JPA/repository/BCrypt/JWT, with deterministic phase clocks and faults below the router. */
class LegalRegistrationSessionIssuerIT {
    private static final String ROLE = "ordenfix_registration_issuer_app_it";
    private static final String DB_PASSWORD = "registration-issuer-database-test-only";
    private static final String PASSWORD = "registration-session-password-123";
    private static final String NEW_PASSWORD = "registration-session-password-456";
    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder();
    private static final String PASSWORD_HASH = HASHER.encode(PASSWORD);
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_registration_session_issuer")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static JdbcTemplate owner;
    private Harness h;
    private Identity identity;

    @BeforeAll static void migrateAndProvisionTheIndependentApplicationRole() {
        POSTGRES.start(); LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + DB_PASSWORD + "'");
        owner.execute("GRANT CONNECT ON DATABASE ordenfix_registration_session_issuer TO " + ROLE);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        owner.execute("GRANT SELECT, UPDATE ON users, talleres, suscripciones TO " + ROLE);
    }
    @BeforeEach void openExplicitIssuerComposition() {
        identity = seed(UserRole.ADMIN);
        h = new Harness();
    }
    @AfterEach void releaseEveryContextAndThreadResource() {
        if (h != null) h.close();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    @AfterAll static void stopPostgres() { POSTGRES.stop(); }

    @ParameterizedTest @EnumSource(UserRole.class)
    void issuesTheRealJwtInExactlyOneNewReadOnlyTransactionWithCurrentRole(UserRole role) {
        owner.update("UPDATE users SET role=? WHERE id=?", role.name(), identity.userId());
        var before = durableRows();

        AuthResponseDto response = h.issue(identity, PASSWORD);

        var token = h.jwt().verifyToken(response.token());
        assertThat(response.type()).isEqualTo("Bearer");
        assertThat(response.email()).isEqualTo(identity.email());
        assertThat(response.emailVerificado()).isFalse();
        assertThat(token.getSubject()).isEqualTo(identity.email());
        assertThat(token.getClaim("role").asString()).isEqualTo("ROLE_" + role.name());
        assertThat(token.getClaim("tallerId").asLong()).isEqualTo(identity.workshopId());
        assertThat(token.getClaim("tokenVersion").asLong()).isZero();
        assertThat(token.getIssuer()).isEqualTo("ordenfix-registration-session-it");
        assertThat(token.getAudience()).containsExactly("ordenfix-registration-session-api-it");
        assertThat(token.getClaims().keySet()).containsExactlyInAnyOrder(
                "iss","aud","sub","role","tallerId","tokenVersion","iat","nbf","jti","exp");
        assertThat(h.probe.events).containsExactly("repository", "bcrypt", "jwt");
        assertOneTransaction(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertStageTransactions();
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void durableIdsUseTheCurrentPasswordEmailVerificationAndClaimsEvenWhenTheOldEmailHasANewOwner() {
        String changedEmail = UUID.randomUUID() + "@changed-session.test";
        owner.update("UPDATE users SET email=?, password=?, role='USER', email_verificado=true, token_version=9 WHERE id=?",
                changedEmail, HASHER.encode(NEW_PASSWORD), identity.userId());
        Identity other = seed(UserRole.ADMIN);
        owner.update("UPDATE users SET email=? WHERE id=?", identity.email(), other.userId());
        var before = durableRows();

        AuthResponseDto response = h.issue(identity, NEW_PASSWORD);

        var token = h.jwt().verifyToken(response.token());
        assertThat(response.email()).isEqualTo(changedEmail);
        assertThat(response.emailVerificado()).isTrue();
        assertThat(token.getSubject()).isEqualTo(changedEmail);
        assertThat(token.getClaim("tallerId").asLong()).isEqualTo(identity.workshopId());
        assertThat(token.getClaim("tokenVersion").asLong()).isEqualTo(9L);
        assertThat(token.getClaim("role").asString()).isEqualTo("ROLE_USER");
        assertOneTransaction(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertThat(durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"user", "workshop"})
    void anInactiveCurrentActorRetainsGenericCredentialRejectionWithoutHashOrSignature(String target) {
        if (target.equals("user")) owner.update("UPDATE users SET active=false WHERE id=?", identity.userId());
        else owner.update("UPDATE talleres SET activo=false WHERE id=?", identity.workshopId());
        var before = durableRows();

        Throwable failure = catchThrowable(() -> h.issue(identity, PASSWORD));

        assertThat(failure).isExactlyInstanceOf(BadCredentialsException.class).hasMessage("Usuario o contraseña incorrectos");
        assertThat(h.probe.repositoryCalls).isEqualTo(1);
        assertThat(h.probe.hashes).isZero(); assertThat(h.probe.signatures).isZero();
        assertThat(h.probe.budget.remainingMillis()).isEqualTo(30_000);
        assertOneTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void suspendsTheHistoricalJpaContextAndRestoresItsUncommittedStateAfterOneDedicatedSession() {
        var before = durableRows();
        TransactionTemplate outer = new TransactionTemplate(h.manager());
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            EntityManager previous = h.entityManager();
            Object previousResource = TransactionSynchronizationManager.getResource(h.factory());
            int previousPid = h.pid();
            User managed = previous.find(User.class, identity.userId());
            managed.setActive(false); managed.setUsername("Uncommitted issuer actor"); previous.flush();

            AuthResponseDto response = h.issue(identity, PASSWORD);

            assertThat(h.jwt().verifyToken(response.token()).getSubject()).isEqualTo(identity.email());
            assertThat(h.probe.stages.getFirst().pid()).isNotEqualTo(previousPid);
            assertThat(h.entityManager()).isSameAs(previous);
            assertThat(TransactionSynchronizationManager.getResource(h.factory())).isSameAs(previousResource);
            assertThat(h.pid()).isEqualTo(previousPid);
            assertThat(managed.getActive()).isFalse();
            assertThat(managed.getUsername()).isEqualTo("Uncommitted issuer actor");
            status.setRollbackOnly();
        });
        assertOneTransaction(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void theSameConsumedOwnerBoundsJpaSqlAndThePerCallTransactionTimeout() {
        h.begin(Fault.NONE, 28);
        var before = durableRows();

        AuthResponseDto response = h.issue(identity, PASSWORD);

        assertThat(h.jwt().verifyToken(response.token()).getSubject()).isEqualTo(identity.email());
        assertThat(h.probe.budget.remainingMillis()).isEqualTo(2_000);
        assertThat(h.probe.stages).hasSize(3).allSatisfy(stage -> {
            assertThat(stage.statementTimeout()).isEqualTo("2s");
            assertThat(stage.networkTimeout()).isEqualTo(2_000);
            assertThat(stage.transactionTtlSeconds()).isBetween(1, 2);
        });
        assertOneTransaction(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertThat(durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "expired"})
    void invalidOwnerNeverBorrowsOrInvokesThePolicy(String mode) {
        LegalRegistrationBudget budget = h.probe.budget;
        if (mode.equals("null")) budget = null;
        else h.probe.clock.set(Duration.ofSeconds(30).toNanos());
        LegalRegistrationBudget input = budget;
        var before = durableRows();

        Throwable failure = catchThrowable(() -> h.issuer().issueSession(identity.userId(), identity.workshopId(), PASSWORD, input));

        assertUnavailable(failure);
        assertThat(h.probe.borrows).isZero(); assertThat(h.probe.repositoryCalls).isZero();
        assertThat(h.probe.hashes).isZero(); assertThat(h.probe.signatures).isZero();
        assertThat(h.probe.completions).isEmpty();
        assertThat(durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"REPOSITORY_EXPIRY", "HASH_EXPIRY", "JWT_EXPIRY"})
    void expiryAfterRealPhaseWorkVetoesTheNextPhaseAndRollsBack(Fault phase) {
        h.begin(phase, 0);
        var before = durableRows();

        Throwable failure = catchThrowable(() -> h.issue(identity, PASSWORD));

        assertUnavailable(failure);
        assertThat(h.probe.injections).isEqualTo(1);
        assertThat(h.probe.repositoryCalls).isEqualTo(1);
        assertThat(h.probe.userQueries).isEqualTo(1); assertThat(h.probe.userRows).isEqualTo(1);
        assertThat(h.probe.hashes).isEqualTo(phase == Fault.REPOSITORY_EXPIRY ? 0 : 1);
        assertThat(h.probe.signatures).isEqualTo(phase == Fault.JWT_EXPIRY ? 1 : 0);
        if (phase == Fault.JWT_EXPIRY) assertThat(h.jwt().verifyToken(h.probe.computedJwt).getSubject()).isEqualTo(identity.email());
        assertOneTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void expiredEmptyRepositoryResultCannotBecomeBadCredentials() {
        h.begin(Fault.REPOSITORY_EXPIRY, 0);
        var before = durableRows();

        Throwable failure = catchThrowable(() -> h.issue(new Identity(Long.MAX_VALUE, identity.workshopId(), identity.email()), PASSWORD));

        assertUnavailable(failure);
        assertThat(h.probe.userQueries).isEqualTo(1); assertThat(h.probe.userRows).isZero();
        assertThat(h.probe.injections).isEqualTo(1); assertThat(h.probe.hashes).isZero(); assertThat(h.probe.signatures).isZero();
        assertOneTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void expiredFalseBcryptResultCannotBecomeBadCredentials() {
        h.begin(Fault.HASH_EXPIRY, 0);
        var before = durableRows();

        Throwable failure = catchThrowable(() -> h.issue(identity, "wrong-session-password"));

        assertUnavailable(failure);
        assertThat(h.probe.lastMatch).isFalse(); assertThat(h.probe.hashes).isEqualTo(1);
        assertThat(h.probe.injections).isEqualTo(1); assertThat(h.probe.signatures).isZero();
        assertOneTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void expiryAlsoDominatesAnExceptionThrownAfterTheRealPasswordWork() {
        h.begin(Fault.HASH_THROW_EXPIRY, 0);
        var before = durableRows();

        Throwable failure = catchThrowable(() -> h.issue(identity, PASSWORD));

        assertUnavailable(failure);
        assertThat(h.probe.lastMatch).isTrue(); assertThat(h.probe.hashes).isEqualTo(1); assertThat(h.probe.signatures).isZero();
        assertThat(containsThrowable(failure, h.probe.phaseFailure)).isTrue();
        assertOneTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertThat(durableRows()).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"RESULT_CLOSE", "GET_QUERY_TIMEOUT", "READ_ONLY_RESET", "CONNECTION_RETURN", "ROLLBACK"})
    void absorbedCleanupErrorsVetoDeliveryAndPreserveTheOriginalOwnerFailure(Fault phase) {
        h.begin(phase, 0);
        var before = durableRows();

        Throwable failure = catchThrowable(() -> h.issue(identity, phase == Fault.ROLLBACK ? "wrong-session-password" : PASSWORD));

        assertUnavailable(failure);
        assertThat(h.probe.injections).isEqualTo(1);
        assertThatThrownBy(h.probe.budget::check).isExactlyInstanceOf(LegalRegistrationBudget.UnavailableException.class)
                .hasCause(h.probe.cleanupFailure);
        boolean committed = phase == Fault.READ_ONLY_RESET || phase == Fault.CONNECTION_RETURN;
        assertThat(h.probe.signatures).isEqualTo(committed ? 1 : 0);
        if (phase == Fault.RESULT_CLOSE || phase == Fault.GET_QUERY_TIMEOUT) assertThat(h.probe.hashes).isZero();
        if (phase == Fault.ROLLBACK) assertThat(h.probe.lastMatch).isFalse();
        // A failed rollback notification is UNKNOWN even though the delegate rollback succeeded;
        // the other cleanup phases preserve the completion reported before release.
        int completion = committed ? TransactionSynchronization.STATUS_COMMITTED
                : phase == Fault.ROLLBACK ? TransactionSynchronization.STATUS_UNKNOWN : TransactionSynchronization.STATUS_ROLLED_BACK;
        assertOneTransaction(completion, committed ? 1 : 0, committed ? 0 : 1);
        int borrows = h.probe.borrows;
        assertUnavailable(catchThrowable(() -> h.issue(identity, PASSWORD)));
        assertThat(h.probe.borrows).isEqualTo(borrows);
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void expiryAfterTheRealCommitDiscardsTheAlreadyComputedJwtWithoutInventingRollback() {
        h.begin(Fault.COMMIT_EXPIRY, 0);
        var before = durableRows();

        Throwable failure = catchThrowable(() -> h.issue(identity, PASSWORD));

        assertUnavailable(failure);
        assertThat(h.probe.injections).isEqualTo(1); assertThat(h.probe.signatures).isEqualTo(1);
        assertThat(h.jwt().verifyToken(h.probe.computedJwt).getSubject()).isEqualTo(identity.email());
        assertOneTransaction(TransactionSynchronization.STATUS_COMMITTED, 1, 0);
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void lostCommitAcknowledgementSeparatesJpaNotificationFromTheSuccessfulJdbcCommit() {
        h.begin(Fault.COMMIT_ACK, 0);
        var before = durableRows();

        Throwable failure = catchThrowable(() -> h.issue(identity, PASSWORD));

        assertUnavailable(failure);
        assertThat(containsThrowable(failure, h.probe.ackFailure)).isTrue();
        assertThat(h.probe.injections).isEqualTo(1); assertThat(h.probe.signatures).isEqualTo(1);
        // Hibernate's FAILED_COMMIT is inactive; Spring's translated runtime failure reports
        // ROLLED_BACK while the physical JDBC commit already succeeded. It does not undo an account.
        assertOneTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, 1, 0);
        assertThat(h.probe.budget.remainingMillis()).isEqualTo(30_000);
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test void realSqlFailureRollsBackWithoutPoisoningTheOwnerWhenCleanupSucceeds() {
        h.begin(Fault.SQL_FAILURE, 0);
        var before = durableRows();

        Throwable failure = catchThrowable(() -> h.issue(identity, PASSWORD));

        assertUnavailable(failure);
        assertThat(h.probe.sqlState).isEqualTo("22012"); assertThat(h.probe.injections).isEqualTo(1);
        assertThat(h.probe.hashes).isZero(); assertThat(h.probe.signatures).isZero();
        assertOneTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, 0, 1);
        assertThat(h.probe.budget.remainingMillis()).isEqualTo(30_000);
        assertThat(durableRows()).isEqualTo(before);
    }

    private void assertUnavailable(Throwable failure) {
        assertThat(failure).isExactlyInstanceOf(LegalRegistrationSessionUnavailableException.class)
                .hasMessage("La sesión de registro no está disponible");
        assertThat(failure.toString()).doesNotContain(identity.email(), PASSWORD, DB_PASSWORD, "SELECT", "08006", "22012");
    }
    private void assertOneTransaction(int completion, int commits, int rollbacks) {
        assertThat(h.probe.borrows).isEqualTo(1);
        assertThat(h.probe.commits).isEqualTo(commits); assertThat(h.probe.rollbacks).isEqualTo(rollbacks);
        assertThat(h.probe.completions).containsExactly(completion);
        assertThat(h.probe.returns).isEqualTo(1);
        assertThat(h.pool().getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }
    private void assertStageTransactions() {
        assertThat(h.probe.stages).hasSize(3);
        Stage first = h.probe.stages.getFirst();
        assertThat(h.probe.stages).allSatisfy(stage -> {
            assertThat(stage.pid()).isEqualTo(first.pid());
            assertThat(stage.entityManagerResource()).isSameAs(first.entityManagerResource());
            assertThat(stage.entityManagerResource()).isNotNull();
            assertThat(stage.active()).isTrue(); assertThat(stage.springReadOnly()).isTrue();
            assertThat(stage.springIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(stage.isolation()).isEqualTo("read committed"); assertThat(stage.readOnly()).isEqualTo("on");
            assertThat(stage.autoCommit()).isFalse(); assertThat(stage.role()).isEqualTo(ROLE);
        });
    }
    private static Identity seed(UserRole role) {
        String marker = UUID.randomUUID().toString();
        long workshop = Objects.requireNonNull(owner.queryForObject("INSERT INTO talleres(nombre,activo) VALUES (?,true) RETURNING id",
                Long.class, "Issuer " + marker));
        String email = marker + "@issuer.test";
        long user = Objects.requireNonNull(owner.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES ('Issuer actor',?,?,?,?,true,false,0) RETURNING id
                """, Long.class, email, PASSWORD_HASH, role.name(), workshop));
        return new Identity(user, workshop, email);
    }
    private static List<String> durableRows() {
        return owner.queryForList("""
                SELECT 'users:' || id || ':' || row_to_json(u)::text || ':' || xmin::text AS snapshot FROM users u
                UNION ALL SELECT 'talleres:' || id || ':' || row_to_json(t)::text || ':' || xmin::text FROM talleres t
                UNION ALL SELECT 'suscripciones:' || id || ':' || row_to_json(s)::text || ':' || xmin::text FROM suscripciones s
                UNION ALL SELECT 'tokens:' || id || ':' || row_to_json(a)::text || ':' || xmin::text FROM auth_tokens a
                ORDER BY snapshot
                """, String.class);
    }

    private static final class Harness implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final Probe probe = new Probe();
        Harness() {
            try {
                TestPropertyValues.of(Map.of(
                        "spring.datasource.url", POSTGRES.getJdbcUrl(), "spring.datasource.username", ROLE,
                        "spring.datasource.password", DB_PASSWORD, "spring.datasource.driver-class-name", "org.postgresql.Driver",
                        "spring.datasource.hikari.maximum-pool-size", "2", "spring.datasource.hikari.minimum-idle", "0",
                        "spring.datasource.hikari.connection-timeout", "3000", "spring.datasource.hikari.initialization-fail-timeout", "-1",
                        "spring.jpa.hibernate.ddl-auto", "validate", "spring.jpa.open-in-view", "false")).applyTo(context);
                context.getBeanFactory().registerSingleton("sessionIssuerProbe", probe);
                context.register(BootSessionConfiguration.class, LegalRegistrationSessionIssuerConfiguration.class);
                context.refresh();
                probe.harness = this;
                assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
                assertThat(manager().getDataSource()).isSameAs(source());
                assertThat(((EntityManagerFactoryInfo) factory()).getDataSource()).isSameAs(source());
                assertThat(context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(source());
                // Test-only injection beneath the production router: holder, private pool, factory,
                // credentials and Boot composition remain real and unchanged. No production seam.
                ReflectionTestUtils.setField(source(), "dedicated", new FaultDataSource(pool(), probe));
                begin(Fault.NONE, 0);
            } catch (Throwable failure) {
                try { context.close(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void begin(Fault fault, long spentSeconds) {
            probe.fault = fault; probe.clock.set(0); probe.budget = LegalRegistrationBudget.start(probe.clock::get);
            probe.clock.set(Duration.ofSeconds(spentSeconds).toNanos());
        }
        AuthResponseDto issue(Identity identity, String password) {
            return issuer().issueSession(identity.userId(), identity.workshopId(), password, probe.budget);
        }
        LegalRegistrationSessionIssuer issuer() { return context.getBean(LegalRegistrationSessionIssuer.class); }
        ObservingJwt jwt() { return context.getBean(ObservingJwt.class); }
        DataSource source() { return context.getBean(DataSource.class); }
        EntityManagerFactory factory() { return context.getBean(EntityManagerFactory.class); }
        JpaTransactionManager manager() { return context.getBean(JpaTransactionManager.class); }
        HikariDataSource pool() {
            return (HikariDataSource) Objects.requireNonNull(ReflectionTestUtils.getField(
                    context.getBean(LegalRegistrationSessionResources.class), "pool"));
        }
        EntityManager entityManager() { return Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(factory())); }
        int pid() { return entityManager().unwrap(Session.class).doReturningWork(connection -> {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(rows.next()).isTrue(); return rows.getInt(1);
            }
        }); }
        Stage observe() { return entityManager().unwrap(Session.class).doReturningWork(connection -> {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    SELECT pg_backend_pid(),current_user,current_setting('transaction_isolation'),
                           current_setting('transaction_read_only'),current_setting('statement_timeout')
                    """)) {
                assertThat(rows.next()).isTrue();
                ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(source());
                assertThat(holder).isNotNull();
                return new Stage(rows.getInt(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
                        connection.getNetworkTimeout(), connection.getAutoCommit(), TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly(), TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        TransactionSynchronizationManager.getResource(factory()), holder.getTimeToLiveInSeconds());
            }
        }); }
        @Override public void close() { context.close(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    static class BootSessionConfiguration {
        @Bean static PersistenceManagedTypes sessionManagedTypes() {
            return PersistenceManagedTypes.of(User.class.getName(), Taller.class.getName(), Suscripcion.class.getName());
        }
        @Bean UserRepository issuerUserRepository(EntityManagerFactory factory, Probe probe) {
            UserRepository real = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory))
                    .getRepository(UserRepository.class);
            return (UserRepository) Proxy.newProxyInstance(UserRepository.class.getClassLoader(), new Class<?>[]{UserRepository.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                        boolean sessionQuery = method.getName().equals("findSessionByIdAndTallerId");
                        if (sessionQuery) { probe.repositoryCalls++; probe.stage("repository"); }
                        Object result = invoke(real, method, args);
                        if (sessionQuery && probe.fault == Fault.REPOSITORY_EXPIRY) probe.expire();
                        return result;
                    });
        }
        @Bean ObservingEncoder issuerEncoder(Probe probe) { return new ObservingEncoder(probe); }
        @Bean ObservingJwt issuerJwt(Probe probe) { return new ObservingJwt(probe); }
        @Bean AccountSessionPolicy issuerPolicy(UserRepository users, ObservingEncoder encoder, ObservingJwt jwt) {
            return new AccountSessionPolicy(users, encoder, jwt);
        }
    }
    static final class ObservingEncoder implements PasswordEncoder {
        private final Probe probe;
        private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();
        ObservingEncoder(Probe probe) { this.probe = probe; }
        @Override public String encode(CharSequence raw) { return delegate.encode(raw); }
        @Override public boolean matches(CharSequence raw, String encoded) {
            probe.hashes++; probe.stage("bcrypt");
            boolean result = delegate.matches(raw, encoded); probe.lastMatch = result;
            if (probe.fault == Fault.HASH_EXPIRY || probe.fault == Fault.HASH_THROW_EXPIRY) probe.expire();
            if (probe.fault == Fault.HASH_THROW_EXPIRY) throw probe.phaseFailure;
            return result;
        }
        @Override public boolean upgradeEncoding(String encoded) { return delegate.upgradeEncoding(encoded); }
    }
    static final class ObservingJwt extends JwtUtils {
        private final Probe probe;
        ObservingJwt(Probe probe) {
            super("registration-session-synthetic-jwt-key-at-least-32-bytes", 3_600_000,
                    "ordenfix-registration-session-it", "ordenfix-registration-session-api-it", 30);
            this.probe = probe;
        }
        @Override public String generateToken(UserDetails details, Long workshop) {
            probe.signatures++; probe.stage("jwt");
            String result = super.generateToken(details, workshop); probe.computedJwt = result;
            if (probe.fault == Fault.JWT_EXPIRY) probe.expire();
            return result;
        }
    }
    private static final class Probe {
        Harness harness;
        final AtomicLong clock = new AtomicLong();
        LegalRegistrationBudget budget;
        Fault fault;
        int repositoryCalls; int hashes; int signatures; int injections;
        int borrows; int commits; int rollbacks; int returns; int userQueries; int userRows;
        boolean lastMatch;
        String computedJwt; String sqlState;
        final List<String> events = new ArrayList<>();
        final List<Stage> stages = new ArrayList<>();
        final List<Integer> completions = new ArrayList<>();
        final SQLException cleanupFailure = new SQLException("Synthetic session cleanup failure", "08006");
        final SQLException ackFailure = new SQLException("Synthetic acknowledgement loss after JDBC commit", "08006");
        final IllegalStateException phaseFailure = new IllegalStateException("Synthetic failure after real password comparison");
        void stage(String stage) { events.add(stage); stages.add(harness.observe()); }
        void expire() { injections++; clock.set(Duration.ofSeconds(30).toNanos()); }
        void cleanup() throws SQLException { injections++; throw cleanupFailure; }
    }
    private static final class FaultDataSource extends AbstractDataSource {
        private final DataSource real;
        private final Probe probe;
        FaultDataSource(DataSource real, Probe probe) { this.real = real; this.probe = probe; }
        @Override public Connection getConnection() throws SQLException {
            Connection connection = real.getConnection(); probe.borrows++;
            AtomicBoolean registered = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (TransactionSynchronizationManager.isSynchronizationActive() && registered.compareAndSet(false, true)) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCompletion(int status) { probe.completions.add(status); }
                    });
                }
                String name = method.getName();
                Object result = invoke(connection, method, args);
                if (name.equals("commit")) {
                    probe.commits++;
                    if (probe.fault == Fault.COMMIT_EXPIRY) probe.expire();
                    if (probe.fault == Fault.COMMIT_ACK) { probe.injections++; throw probe.ackFailure; }
                }
                if (name.equals("rollback") && method.getParameterCount() == 0) {
                    probe.rollbacks++;
                    if (probe.fault == Fault.ROLLBACK && probe.injections == 0) probe.cleanup();
                }
                if (name.equals("setReadOnly") && Boolean.FALSE.equals(args[0]) && probe.commits == 1
                        && probe.fault == Fault.READ_ONLY_RESET && probe.injections == 0) probe.cleanup();
                if (name.equals("close")) {
                    probe.returns++;
                    if (probe.fault == Fault.CONNECTION_RETURN && probe.injections == 0) probe.cleanup();
                }
                if (name.equals("prepareStatement") && args[0] instanceof String sql && result instanceof PreparedStatement statement
                        && isUserQuery(sql)) return statement(statement, connection);
                return result;
            });
        }
        @Override public Connection getConnection(String username, String password) throws SQLException { return real.getConnection(username, password); }
        private PreparedStatement statement(PreparedStatement realStatement, Connection connection) {
            AtomicBoolean resultClosed = new AtomicBoolean();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                        String name = method.getName();
                        if (name.equals("executeQuery") && probe.fault == Fault.SQL_FAILURE && probe.injections == 0) {
                            probe.injections++;
                            try (var broken = connection.createStatement()) { broken.execute("SELECT 1/0"); }
                            catch (SQLException failure) { probe.sqlState = failure.getSQLState(); throw failure; }
                            throw new AssertionError("PostgreSQL did not reject division by zero");
                        }
                        Object result = invoke(realStatement, method, args);
                        if (name.equals("getQueryTimeout") && resultClosed.get() && probe.fault == Fault.GET_QUERY_TIMEOUT
                                && probe.injections == 0) probe.cleanup();
                        if (name.equals("executeQuery")) {
                            probe.userQueries++;
                            return rows((ResultSet) result, resultClosed);
                        }
                        return result;
                    });
        }
        private ResultSet rows(ResultSet realRows, AtomicBoolean closed) {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                Object result = invoke(realRows, method, args);
                if (method.getName().equals("next") && Boolean.TRUE.equals(result)) probe.userRows++;
                if (method.getName().equals("close")) {
                    closed.set(true);
                    if (probe.fault == Fault.RESULT_CLOSE && probe.injections == 0) probe.cleanup();
                }
                return result;
            });
        }
        private static boolean isUserQuery(String sql) {
            String normalized = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            return normalized.startsWith("select ") && (normalized.contains(" from users ") || normalized.contains(" from public.users "));
        }
    }
    private static Object invoke(Object real, Method method, Object[] args) throws Throwable {
        try { return method.invoke(real, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "RegistrationIssuerObservation[redacted]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError(method.getName());
        };
    }
    private static boolean containsThrowable(Throwable current, Throwable sought) {
        if (current == sought) return true;
        if (current == null) return false;
        if (containsThrowable(current.getCause(), sought)) return true;
        for (Throwable suppressed : current.getSuppressed()) if (containsThrowable(suppressed, sought)) return true;
        return false;
    }
    private enum Fault { NONE, REPOSITORY_EXPIRY, HASH_EXPIRY, JWT_EXPIRY, HASH_THROW_EXPIRY,
        RESULT_CLOSE, GET_QUERY_TIMEOUT, READ_ONLY_RESET, CONNECTION_RETURN, ROLLBACK, COMMIT_EXPIRY, COMMIT_ACK, SQL_FAILURE }
    private record Identity(long userId, long workshopId, String email) { }
    private record Stage(int pid, String role, String isolation, String readOnly, String statementTimeout, int networkTimeout,
            boolean autoCommit, boolean active, boolean springReadOnly, Integer springIsolation, Object entityManagerResource,
            int transactionTtlSeconds) { }
}
