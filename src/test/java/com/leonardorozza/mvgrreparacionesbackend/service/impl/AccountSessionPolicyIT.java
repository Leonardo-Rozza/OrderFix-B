package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real JPA, PostgreSQL, BCrypt and JWT; the encoder only observes the active transaction. */
@SpringBootTest
@Testcontainers
@Import(AccountSessionPolicyIT.ObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountSessionPolicyIT {

    private static final String PASSWORD = "session-fixture-password-123";
    private static final String NEW_PASSWORD = "current-session-password-456";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_account_session_policy")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    @Autowired private AccountSessionPolicy policy;
    @Autowired private UserRepository users;
    @Autowired private JwtUtils jwt;
    @Autowired private ObservingEncoder encoder;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void activeAccountIssuesTheExistingJwtContractWithoutWriting(UserRole role) {
        Identity identity = seed(role);
        var before = durableRows();
        encoder.last.set(null);

        AuthResponseDto session = policy.issueSession(identity.userId(), identity.workshopId(), PASSWORD);

        DecodedJWT token = jwt.verifyToken(session.token());
        assertThat(session.type()).isEqualTo("Bearer");
        assertThat(session.email()).isEqualTo(identity.email());
        assertThat(session.emailVerificado()).isFalse();
        assertThat(token.getClaims().keySet()).containsExactlyInAnyOrder(
                "iss", "aud", "sub", "role", "tallerId", "tokenVersion", "iat", "nbf", "jti", "exp");
        assertThat(token.getIssuer()).isEqualTo("ordenfix-test");
        assertThat(token.getAudience()).containsExactly("ordenfix-api-test");
        assertThat(token.getSubject()).isEqualTo(identity.email());
        assertThat(token.getClaim("role").asString()).isEqualTo("ROLE_" + role.name());
        assertThat(token.getClaim("tallerId").asLong()).isEqualTo(identity.workshopId());
        assertThat(token.getClaim("tokenVersion").asLong()).isZero();
        assertThat(token.getExpiresAtAsInstant()).isAfter(token.getIssuedAtAsInstant());
        assertThat(jwt.validateToken(token, principal(identity))).isTrue();
        assertReadOnlyCommitted(encoder.last.get());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory)).isNull();
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test
    void durableIdsFindTheOriginalAccountAfterItsEmailIsReassigned() {
        Identity original = seed(UserRole.ADMIN);
        String oldToken = policy.issueSession(original.userId(), original.workshopId(), PASSWORD).token();
        String currentEmail = UUID.randomUUID() + "@session.test";
        owner().update("UPDATE users SET email = ?, role = 'USER', email_verificado = true, token_version = 9 WHERE id = ?",
                currentEmail, original.userId());
        Identity replacement = seed(UserRole.ADMIN);
        owner().update("UPDATE users SET email = ? WHERE id = ?", original.email(), replacement.userId());
        var before = durableRows();

        AuthResponseDto session = policy.issueSession(original.userId(), original.workshopId(), PASSWORD);
        AuthResponseDto repeated = policy.issueSession(original.userId(), original.workshopId(), PASSWORD);

        DecodedJWT token = jwt.verifyToken(session.token());
        assertThat(session.email()).isEqualTo(currentEmail);
        assertThat(session.emailVerificado()).isTrue();
        assertThat(token.getSubject()).isEqualTo(currentEmail);
        assertThat(token.getClaim("role").asString()).isEqualTo("ROLE_USER");
        assertThat(token.getClaim("tokenVersion").asLong()).isEqualTo(9L);
        assertThat(token.getClaim("tallerId").asLong()).isEqualTo(original.workshopId());
        assertThat(jwt.validateToken(token, principal(original))).isTrue();
        assertThat(jwt.validateToken(jwt.verifyToken(oldToken), principal(original))).isFalse();
        assertThat(jwt.verifyToken(repeated.token()).getId()).isNotEqualTo(token.getId());
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test
    void currentPasswordAndRevocationVersionAreRequiredWithoutRecreatingTheAccount() {
        Identity identity = seed(UserRole.ADMIN);
        String oldToken = policy.issueSession(identity.userId(), identity.workshopId(), PASSWORD).token();
        transaction().executeWithoutResult(status -> {
            User user = users.findSessionByIdAndTallerId(identity.userId(), identity.workshopId()).orElseThrow();
            user.cambiarPassword(encoder.encode(NEW_PASSWORD));
        });
        var before = durableRows();

        assertRejected(identity, PASSWORD);
        var current = jwt.verifyToken(policy.issueSession(identity.userId(), identity.workshopId(), NEW_PASSWORD).token());

        assertThat(current.getClaim("tokenVersion").asLong()).isEqualTo(1L);
        assertThat(jwt.validateToken(jwt.verifyToken(oldToken), principal(identity))).isFalse();
        assertThat(jwt.validateToken(current, principal(identity))).isTrue();
        assertThat(durableRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "workshop"})
    void currentInactiveStateRejectsSessionWithoutDml(String target) {
        Identity identity = seed(UserRole.ADMIN);
        if (target.equals("user")) {
            owner().update("UPDATE users SET active = false WHERE id = ?", identity.userId());
        } else {
            owner().update("UPDATE talleres SET activo = false WHERE id = ?", identity.workshopId());
        }
        var before = durableRows();

        assertRejected(identity, PASSWORD);

        assertThat(durableRows()).isEqualTo(before);
    }

    @Test
    void mismatchedAndDeletedDurableIdsCannotProduceAnotherAccountsSession() {
        Identity first = seed(UserRole.ADMIN);
        Identity second = seed(UserRole.ADMIN);
        var beforeMismatch = durableRows();
        assertRejected(new Identity(first.userId(), second.workshopId(), first.email()), PASSWORD);
        assertThat(durableRows()).isEqualTo(beforeMismatch);
        owner().update("DELETE FROM users WHERE id = ?", first.userId());
        var before = durableRows();

        assertRejected(first, PASSWORD);

        assertThat(durableRows()).isEqualTo(before);
    }

    @Test
    void anUncommittedRegistrationCannotIssueASession() {
        var before = durableRows();
        transaction().executeWithoutResult(status -> {
            Taller workshop = Taller.builder().nombre("Uncommitted session workshop").activo(true).build();
            entityManager.persist(workshop);
            String email = UUID.randomUUID() + "@session.test";
            User user = User.builder().username("Uncommitted session actor").email(email)
                    .password(encoder.encode(PASSWORD)).role(UserRole.ADMIN).active(true).taller(workshop).build();
            entityManager.persist(user);
            entityManager.flush();

            assertRejected(new Identity(user.getId(), workshop.getId(), email), PASSWORD);
            assertThat(status.isRollbackOnly()).isFalse();
            status.setRollbackOnly();
        });
        assertThat(durableRows()).isEqualTo(before);
    }

    @Test
    void suspendsUncommittedOuterChangesAndRestoresTheSameJpaTransaction() {
        Identity identity = seed(UserRole.ADMIN);
        var before = durableRows();
        TransactionTemplate outer = transaction();
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            User managed = users.findSessionByIdAndTallerId(identity.userId(), identity.workshopId()).orElseThrow();
            Object outerResource = TransactionSynchronizationManager.getResource(entityManagerFactory);
            int outerPid = ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
            managed.cambiarPassword(encoder.encode(NEW_PASSWORD));
            managed.setEmail(UUID.randomUUID() + "@session.test");
            // Both changes remain uncommitted. V33 admits user edits before workshop inactivation.
            entityManager.flush();
            managed.getTaller().setActivo(false);
            entityManager.flush();

            AuthResponseDto session = policy.issueSession(identity.userId(), identity.workshopId(), PASSWORD);
            Observation inner = encoder.last.get();
            assertReadOnlyCommitted(inner);
            assertThat(inner.pid()).isNotEqualTo(outerPid);
            assertThat(inner.entityManagerResource()).isNotSameAs(outerResource);
            assertThat(session.email()).isEqualTo(identity.email());
            assertThat(jwt.verifyToken(session.token()).getClaim("tokenVersion").asLong()).isZero();
            assertRejected(identity, NEW_PASSWORD);

            assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory)).isSameAs(outerResource);
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(entityManager.find(User.class, identity.userId())).isSameAs(managed);
            assertThat(managed.getTaller().getActivo()).isFalse();
            assertThat(status.isRollbackOnly()).isFalse();
            assertThat(((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue())
                    .isEqualTo(outerPid);
            status.setRollbackOnly();
        });
        assertThat(durableRows()).isEqualTo(before);
        assertThat(jwt.verifyToken(policy.issueSession(identity.userId(), identity.workshopId(), PASSWORD).token())
                .getClaim("tokenVersion").asLong()).isZero();
    }

    @Test
    void committedChangesAreFreshEvenWhenTheOuterJpaContextRetainsTheOldAccount() {
        Identity identity = seed(UserRole.ADMIN);
        String nextEmail = UUID.randomUUID() + "@session.test";
        AtomicReference<List<String>> committedRows = new AtomicReference<>();
        transaction().executeWithoutResult(status -> {
            User stale = users.findSessionByIdAndTallerId(identity.userId(), identity.workshopId()).orElseThrow();
            Object outerResource = TransactionSynchronizationManager.getResource(entityManagerFactory);
            // owner() has its own DataSource, so this change commits outside the ambient JPA transaction.
            owner().update("UPDATE users SET email = ?, password = ?, token_version = 5, role = 'USER', "
                            + "email_verificado = true WHERE id = ?", nextEmail, encoder.encode(NEW_PASSWORD), identity.userId());
            committedRows.set(durableRows());

            assertRejected(identity, PASSWORD);
            AuthResponseDto current = policy.issueSession(identity.userId(), identity.workshopId(), NEW_PASSWORD);
            DecodedJWT token = jwt.verifyToken(current.token());

            assertThat(current.email()).isEqualTo(nextEmail);
            assertThat(current.emailVerificado()).isTrue();
            assertThat(token.getClaim("tokenVersion").asLong()).isEqualTo(5L);
            assertThat(token.getClaim("role").asString()).isEqualTo("ROLE_USER");
            assertThat(stale.getEmail()).isEqualTo(identity.email());
            assertThat(stale.getTokenVersion()).isZero();
            assertThat(entityManager.find(User.class, identity.userId())).isSameAs(stale);
            assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory)).isSameAs(outerResource);
            assertThat(status.isRollbackOnly()).isFalse();
            status.setRollbackOnly();
        });
        assertThat(durableRows()).isEqualTo(committedRows.get());
    }

    private void assertRejected(Identity identity, String password) {
        assertThatThrownBy(() -> policy.issueSession(identity.userId(), identity.workshopId(), password))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Usuario o contraseña incorrectos");
    }

    private AuthenticatedUserPrincipal principal(Identity identity) {
        return new AuthenticatedUserPrincipal(users.findSessionByIdAndTallerId(identity.userId(), identity.workshopId()).orElseThrow());
    }

    private static void assertReadOnlyCommitted(Observation observation) {
        assertThat(observation).isNotNull();
        assertThat(observation.active()).isTrue();
        assertThat(observation.springReadOnly()).isTrue();
        assertThat(observation.springIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(observation.serverIsolation()).isEqualTo("read committed");
        assertThat(observation.serverReadOnly()).isEqualTo("on");
        assertThat(observation.autoCommit()).isFalse();
        assertThat(observation.entityManagerResource()).isNotNull();
    }

    private Identity seed(UserRole role) {
        JdbcTemplate jdbc = owner();
        String marker = UUID.randomUUID().toString();
        long workshop = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO talleres(nombre,activo) VALUES (?,true) RETURNING id", Long.class, "Session " + marker));
        String email = marker + "@session.test";
        long user = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES (?,?,?,?,?,true,false,0) RETURNING id
                """, Long.class, "Session actor", email, encoder.encode(PASSWORD), role.name(), workshop));
        return new Identity(user, workshop, email);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static JdbcTemplate owner() {
        // A distinct DataSource deliberately avoids enlistment in the application's thread-bound JPA connection.
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static List<String> durableRows() {
        return owner().queryForList("""
                SELECT 'user:' || id || ':' || row_to_json(u)::text || ':' || xmin::text AS snapshot FROM users u
                UNION ALL
                SELECT 'workshop:' || id || ':' || row_to_json(t)::text || ':' || xmin::text FROM talleres t
                UNION ALL
                SELECT 'subscription:' || id || ':' || row_to_json(s)::text || ':' || xmin::text FROM suscripciones s
                UNION ALL
                SELECT 'auth-token:' || id || ':' || row_to_json(a)::text || ':' || xmin::text FROM auth_tokens a
                ORDER BY snapshot
                """, String.class);
    }

    private record Identity(long userId, long workshopId, String email) {}

    private record Observation(boolean active, boolean springReadOnly, Integer springIsolation,
                               String serverIsolation, String serverReadOnly, int pid, boolean autoCommit,
                               Object entityManagerResource) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {
        @Bean
        @Primary
        ObservingEncoder observingSessionEncoder(DataSource dataSource, ObjectProvider<EntityManagerFactory> factory) {
            return new ObservingEncoder(dataSource, factory);
        }
    }

    static final class ObservingEncoder implements PasswordEncoder {
        private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();
        private final DataSource dataSource;
        private final ObjectProvider<EntityManagerFactory> factory;
        private final AtomicReference<Observation> last = new AtomicReference<>();

        private ObservingEncoder(DataSource dataSource, ObjectProvider<EntityManagerFactory> factory) {
            this.dataSource = dataSource;
            this.factory = factory;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            boolean result = delegate.matches(rawPassword, encodedPassword);
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT current_setting('transaction_isolation'), "
                         + "current_setting('transaction_read_only'), pg_backend_pid()")) {
                if (!rows.next()) throw new IllegalStateException("Missing transaction observation");
                last.set(new Observation(TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        rows.getString(1), rows.getString(2), rows.getInt(3), connection.getAutoCommit(),
                        TransactionSynchronizationManager.getResource(factory.getObject())));
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot observe the session transaction", ex);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
            return result;
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return delegate.upgradeEncoding(encodedPassword);
        }
    }
}
