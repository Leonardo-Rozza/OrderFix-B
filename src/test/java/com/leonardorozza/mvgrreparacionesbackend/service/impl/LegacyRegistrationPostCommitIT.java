package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.RegisterRequestDto;
import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Real account/token commits, independent PostgreSQL observations, BCrypt and JWT signing. */
@SpringBootTest
@Testcontainers
@Import(LegacyRegistrationPostCommitIT.ObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyRegistrationPostCommitIT {
    private static final Probe PROBE = new Probe();
    private static final String PASSWORD = "legacy-registration-fixture-password";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);
    private static final Pattern TOKEN_LINK = Pattern.compile("href=\"[^\"]*/verificar-email\\?token=([A-Za-z0-9_-]+)\"");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legacy_registration_post_commit")
            .withUsername("ordenfix").withPassword("ordenfix");

    @Autowired private RegistroService registration;
    @Autowired private LegacyRegistrationAccountWriter writer;
    @Autowired private ObservingJwt jwt;
    @Autowired private EmailObserver emails;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationContext applicationContext;
    @PersistenceContext private EntityManager entityManager;

    @DynamicPropertySource static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("plan.trial-dias", () -> "23");
        registry.add("mail.enabled", () -> "false");
    }

    @BeforeEach void beginObservation() {
        PROBE.reset(); PROBE.factory = entityManagerFactory; PROBE.active = true;
        assertThat(dataSource).isInstanceOf(ObservedDataSource.class);
        assertThat(applicationContext.getBean(EmailSender.class)).isSameAs(emails);
        assertThat(applicationContext.getBean(JwtUtils.class)).isSameAs(jwt);
        assertNoAmbientTransaction();
    }
    @AfterEach void endObservation() { PROBE.reset(); assertNoAmbientTransaction(); }

    @Test void writerReturnsOnlyAfterAllThreeRowsCommitWithHistoricalValuesAndTrialConfiguration() {
        var request = request();

        var identity = writer.create(request);

        assertThat(identity.userId()).isEqualTo(PROBE.created.userId());
        assertThat(identity.tallerId()).isEqualTo(PROBE.created.tallerId());
        assertAccount(request, PROBE.created);
        assertThat(PROBE.inserts).containsExactly("talleres", "suscripciones", "users");
        assertThat(PROBE.events).containsExactly("account-commit");
        assertThat(PROBE.signatures).isZero(); assertThat(PROBE.deliveries).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id = ?", Long.class, identity.userId())).isZero();
        assertMutableWriter(); assertNoAmbientTransaction();
    }

    @Test void registrationConfirmsAccountThenTokenBeforeSendingAndIssuingTheCurrentSession() {
        var request = request();

        AuthResponseDto session = registration.registrar(request);

        assertAccount(request, PROBE.created);
        assertThat(PROBE.events).containsExactly("account-commit", "email", "session");
        assertThat(PROBE.inserts).containsExactly("talleres", "suscripciones", "users", "auth_tokens");
        assertThat(PROBE.deliveries).isEqualTo(1); assertThat(PROBE.signatures).isEqualTo(1);
        assertThat(session.type()).isEqualTo("Bearer"); assertThat(session.email()).isEqualTo(request.email());
        assertThat(session.emailVerificado()).isFalse();
        var token = jwt.verifyToken(session.token());
        assertThat(token.getSubject()).isEqualTo(request.email());
        assertThat(token.getClaim("role").asString()).isEqualTo("ROLE_ADMIN");
        assertThat(token.getClaim("tallerId").asLong()).isEqualTo(PROBE.created.tallerId());
        assertThat(token.getClaim("tokenVersion").asLong()).isZero();
        assertMutableWriter(); assertReadOnlySession();
        assertThat(PROBE.session.resource()).isNotSameAs(PROBE.creation.resource());
        assertNoAmbientTransaction();
    }

    @Test void sessionFollowsDurableIdsWhenTheOriginalEmailIsReassignedAfterCommit() {
        var request = request(); String currentEmail = UUID.randomUUID() + "@current.synthetic.invalid";
        String replacementEmail = UUID.randomUUID() + "@replacement.synthetic.invalid";
        long replacementWorkshop = owner().queryForObject("INSERT INTO talleres(nombre,activo) VALUES ('Replacement',true) RETURNING id", Long.class);
        long replacementUser = owner().queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) "
                + "VALUES ('Replacement',?,?,'ADMIN',?,true,false,0) RETURNING id", Long.class,
                replacementEmail, new BCryptPasswordEncoder().encode(PASSWORD), replacementWorkshop);
        PROBE.afterCommit = identity -> {
            owner().update("UPDATE users SET email = ?, email_verificado = true, token_version = 7, role = 'USER' WHERE id = ?", currentEmail, identity.userId());
            owner().update("UPDATE users SET email = ? WHERE id = ?", request.email(), replacementUser);
        };

        AuthResponseDto session = registration.registrar(request);

        assertThat(session.email()).isEqualTo(currentEmail); assertThat(session.emailVerificado()).isTrue();
        var token = jwt.verifyToken(session.token());
        assertThat(token.getSubject()).isEqualTo(currentEmail);
        assertThat(token.getClaim("tallerId").asLong()).isEqualTo(PROBE.created.tallerId()).isNotEqualTo(replacementWorkshop);
        assertThat(token.getClaim("role").asString()).isEqualTo("ROLE_USER");
        assertThat(token.getClaim("tokenVersion").asLong()).isEqualTo(7);
        assertThat(PROBE.deliveries).isZero(); // Already verified: M2 must not send to the replacement.
        assertThat(PROBE.inserts).containsExactly("talleres", "suscripciones", "users");
        assertThat(rows()).isEqualTo(PROBE.rowsAfterCommit);
    }

    @ParameterizedTest @ValueSource(strings = {"user", "workshop", "password"})
    void postCommitIneligibilityCannotIssueASessionOrRecreateTheAccount(String change) {
        var request = request();
        PROBE.afterCommit = identity -> {
            switch (change) {
                case "user" -> owner().update("UPDATE users SET active = false WHERE id = ?", identity.userId());
                case "workshop" -> owner().update("UPDATE talleres SET activo = false WHERE id = ?", identity.tallerId());
                case "password" -> owner().update("UPDATE users SET password = ?, token_version = 1 WHERE id = ?",
                        new BCryptPasswordEncoder().encode("changed-registration-password"), identity.userId());
                default -> throw new AssertionError(change);
            }
        };

        assertThatThrownBy(() -> registration.registrar(request)).isInstanceOf(BadCredentialsException.class)
                .hasMessage("Usuario o contraseña incorrectos");

        assertThat(PROBE.signatures).isZero(); assertThat(PROBE.events).startsWith("account-commit");
        assertThat(PROBE.inserts.stream().filter("users"::equals)).hasSize(1);
        assertThat(PROBE.inserts.stream().filter("talleres"::equals)).hasSize(1);
        assertThat(PROBE.deliveries).isEqualTo(change.equals("password") ? 1 : 0);
        assertThat(owner().queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, PROBE.created.userId())).isEqualTo(1);
        assertThat(rows()).isEqualTo(change.equals("password") ? PROBE.rowsAtEmail : PROBE.rowsAfterCommit);
        assertNoAmbientTransaction();
    }

    @Test void mismatchedPostCommitIdentityCannotIssueASessionOrMoveTheCommittedAccount() {
        var request = request();
        long otherWorkshop = owner().queryForObject(
                "INSERT INTO talleres(nombre,activo) VALUES ('Other identity fixture',true) RETURNING id", Long.class);
        var identityBoundary = mock(LegacyRegistrationAccountWriter.class);
        when(identityBoundary.create(request)).thenAnswer(invocation -> {
            // Keep the real writer/proxy/commit. Corrupt only the returned identity checkpoint,
            // since V33 deliberately forbids moving an existing user to another workshop.
            var committed = writer.create(request);
            assertThat(PROBE.events).containsExactly("account-commit");
            assertThat(committed.userId()).isEqualTo(PROBE.created.userId());
            assertThat(committed.tallerId()).isEqualTo(PROBE.created.tallerId()).isNotEqualTo(otherWorkshop);
            return new LegacyRegistrationAccountWriter.Identity(committed.userId(), otherWorkshop);
        });
        var coordinator = new RegistroService(identityBoundary,
                applicationContext.getBean(AccountVerificationNotifier.class),
                applicationContext.getBean(AccountSessionPolicy.class));

        assertThatThrownBy(() -> coordinator.registrar(request)).isInstanceOf(BadCredentialsException.class)
                .hasMessage("Usuario o contraseña incorrectos");

        verify(identityBoundary).create(request); verifyNoMoreInteractions(identityBoundary);
        assertAccount(request, PROBE.created); assertMutableWriter();
        assertThat(PROBE.events).containsExactly("account-commit");
        assertThat(PROBE.inserts).containsExactly("talleres", "suscripciones", "users");
        assertThat(PROBE.signatures).isZero(); assertThat(PROBE.deliveries).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id = ?", Long.class,
                PROBE.created.userId())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM users WHERE id = ? AND taller_id = ?", Long.class,
                PROBE.created.userId(), otherWorkshop)).isZero();
        assertThat(rows()).isEqualTo(PROBE.rowsAfterCommit); assertNoAmbientTransaction();
    }

    @Test void aSigningFailurePreservesTheCommittedAccountAndTokenWithoutRetryOrAnotherWelcome() {
        var request = request(); PROBE.failSigning = true;

        assertThatThrownBy(() -> registration.registrar(request)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Synthetic signing failure");

        assertAccount(request, PROBE.created);
        assertThat(PROBE.events).containsExactly("account-commit", "email", "session");
        assertThat(PROBE.signatures).isEqualTo(1); assertThat(PROBE.deliveries).isEqualTo(1);
        assertThat(PROBE.inserts).containsExactly("talleres", "suscripciones", "users", "auth_tokens");
        assertThat(rows()).isEqualTo(PROBE.rowsAtEmail); assertNoAmbientTransaction();
    }

    @Test void mailTransportFailureStillAllowsSessionForTheCommittedAccount() {
        var request = request(); PROBE.failEmail = true;

        var session = registration.registrar(request);

        assertThat(jwt.verifyToken(session.token()).getClaim("tallerId").asLong()).isEqualTo(PROBE.created.tallerId());
        assertThat(PROBE.events).containsExactly("account-commit", "email", "session");
        assertThat(rows()).isEqualTo(PROBE.rowsAtEmail); assertNoAmbientTransaction();
    }

    @Test void anExistingEmailRejectsBeforeAnyWriteNotificationOrSession() {
        var request = request(); writer.create(request); var before = rows();
        PROBE.inserts.clear(); PROBE.events.clear();

        assertThatThrownBy(() -> registration.registrar(request)).isInstanceOf(BadRequestException.class)
                .hasMessage("Ya existe una cuenta con ese email.");

        assertThat(PROBE.inserts).isEmpty(); assertThat(PROBE.events).isEmpty();
        assertThat(PROBE.signatures).isZero(); assertThat(PROBE.deliveries).isZero(); assertThat(rows()).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"USER_INSERT", "COMMIT_BEFORE_SERVER", "COMMIT_ACK_LOST"})
    void failedOrUnacknowledgedAccountWriteNeverNotifiesOrIssuesSession(Fault fault) {
        var request = request(); var before = rows(); PROBE.fault = fault;

        assertThatThrownBy(() -> registration.registrar(request)).isInstanceOf(RuntimeException.class);

        assertThat(PROBE.injected).isEqualTo(1); assertThat(PROBE.signatures).isZero(); assertThat(PROBE.deliveries).isZero();
        assertThat(PROBE.events).isEmpty(); assertThat(PROBE.created).isNull();
        assertThat(PROBE.inserts).containsExactly("talleres", "suscripciones", "users");
        if (fault == Fault.COMMIT_ACK_LOST) {
            var identity = findIdentity(request.email()); assertAccount(request, identity);
            assertThat(rows()).hasSize(before.size() + 3);
            assertThat(owner().queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id = ?", Long.class, identity.userId())).isZero();
        } else assertThat(rows()).isEqualTo(before);
        assertThat(PROBE.sqlState).isEqualTo(fault == Fault.USER_INSERT ? "22012" : "08006");
        assertNoAmbientTransaction();
    }

    @Test void callingTransactionIsSuspendedAndRestoredAndItsRollbackDoesNotUndoTheConfirmedRegistration() {
        var request = request(); String outerName = "Uncommitted outer " + UUID.randomUUID();
        var outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            entityManager.createNativeQuery("INSERT INTO talleres(nombre,activo) VALUES (:name,true)").setParameter("name", outerName).executeUpdate();
            Object resource = TransactionSynchronizationManager.getResource(entityManagerFactory);
            int pid = ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
            // Do not attribute the caller's unrelated insert to the registration writer.
            PROBE.inserts.clear(); PROBE.creation = null;

            var session = registration.registrar(request);

            assertAccount(request, PROBE.created); assertThat(session.email()).isEqualTo(request.email());
            assertMutableWriter(); assertReadOnlySession();
            assertThat(PROBE.creation.pid()).isNotEqualTo(pid); assertThat(PROBE.session.pid()).isNotEqualTo(pid);
            assertThat(PROBE.creation.resource()).isNotSameAs(resource); assertThat(PROBE.session.resource()).isNotSameAs(resource);
            assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory)).isSameAs(resource);
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue()).isEqualTo(pid);
            assertThat(status.isRollbackOnly()).isFalse(); status.setRollbackOnly();
        });
        assertThat(owner().queryForObject("SELECT count(*) FROM talleres WHERE nombre = ?", Long.class, outerName)).isZero();
        assertAccount(request, PROBE.created); assertThat(PROBE.events).containsExactly("account-commit", "email", "session");
        assertThat(rows()).isEqualTo(PROBE.rowsAtEmail); assertNoAmbientTransaction();
    }

    private RegisterRequestDto request() {
        String email = UUID.randomUUID() + "@legacy.synthetic.invalid"; PROBE.requestEmail = email;
        return new RegisterRequestDto(" Taller & legacy ", " +54 11 5555 ", " Admin legacy ", email, PASSWORD);
    }
    private void assertNoAmbientTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory)).isNull();
        assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull();
    }
    private static void assertMutableWriter() {
        assertTransaction(PROBE.creation, false);
        var identity = PROBE.created;
        assertThat(owner().queryForList("SELECT xmin::text FROM users WHERE id = ? UNION ALL SELECT xmin::text FROM talleres WHERE id = ? "
                + "UNION ALL SELECT xmin::text FROM suscripciones WHERE taller_id = ?", String.class,
                identity.userId(), identity.tallerId(), identity.tallerId())).containsExactly(PROBE.creation.xid(), PROBE.creation.xid(), PROBE.creation.xid());
    }
    private static void assertReadOnlySession() { assertTransaction(PROBE.session, true); }
    private static void assertTransaction(Tx tx, boolean readOnly) {
        assertThat(tx).isNotNull(); assertThat(tx.active()).isTrue(); assertThat(tx.readOnly()).isEqualTo(readOnly);
        assertThat(tx.isolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(tx.serverIsolation()).isEqualTo("read committed"); assertThat(tx.serverReadOnly()).isEqualTo(readOnly ? "on" : "off");
        assertThat(tx.autoCommit()).isFalse(); assertThat(tx.resource()).isNotNull();
    }
    private static void assertAccount(RegisterRequestDto request, Identity identity) {
        var user = owner().queryForMap("SELECT * FROM users WHERE id = ?", identity.userId());
        assertThat(user.get("email")).isEqualTo(request.email()); assertThat(user.get("username")).isEqualTo(request.nombreAdmin());
        assertThat(user.get("role")).isEqualTo("ADMIN"); assertThat(user.get("active")).isEqualTo(true);
        assertThat(user.get("email_verificado")).isEqualTo(false); assertThat(((Number) user.get("token_version")).longValue()).isZero();
        assertThat(new BCryptPasswordEncoder().matches(request.password(), (String) user.get("password"))).isTrue();
        assertThat(user.get("taller_id")).isEqualTo(identity.tallerId());
        var taller = owner().queryForMap("SELECT * FROM talleres WHERE id = ?", identity.tallerId());
        assertThat(taller.get("nombre")).isEqualTo(request.nombreTaller()); assertThat(taller.get("email_contacto")).isEqualTo(request.email());
        assertThat(taller.get("telefono")).isEqualTo(request.telefonoTaller()); assertThat(taller.get("activo")).isEqualTo(true);
        var subscription = owner().queryForMap("SELECT * FROM suscripciones WHERE taller_id = ?", identity.tallerId());
        assertThat(subscription.get("plan")).isEqualTo("FREE"); assertThat(subscription.get("estado")).isEqualTo("TRIAL");
        assertThat(((java.sql.Date) subscription.get("fecha_inicio")).toLocalDate()).isEqualTo(LocalDate.now(CLOCK));
        assertThat(((java.sql.Date) subscription.get("fecha_fin_trial")).toLocalDate()).isEqualTo(LocalDate.now(CLOCK).plusDays(23));
        assertThat(subscription.get("created_at")).isNotNull(); assertThat(subscription.get("updated_at")).isNotNull();
        assertThat(subscription.get("reparaciones_mes")).isEqualTo(0); assertThat(subscription.get("proximo_cobro")).isNull();
    }
    private static JdbcTemplate owner() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())); }
    private static Identity findIdentity(String email) {
        return owner().queryForObject("SELECT id,taller_id FROM users WHERE email = ?", (rs, row) -> new Identity(rs.getLong(1), rs.getLong(2)), email);
    }
    private static List<String> rows() {
        return owner().queryForList("SELECT 'user:' || row_to_json(u)::text || ':' || xmin::text AS snapshot FROM users u "
                + "UNION ALL SELECT 'workshop:' || row_to_json(t)::text || ':' || xmin::text FROM talleres t "
                + "UNION ALL SELECT 'subscription:' || row_to_json(s)::text || ':' || xmin::text FROM suscripciones s "
                + "UNION ALL SELECT 'token:' || row_to_json(a)::text || ':' || xmin::text FROM auth_tokens a ORDER BY snapshot", String.class);
    }
    private static Tx observe(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT current_setting('transaction_isolation'), "
                + "current_setting('transaction_read_only'), pg_backend_pid(), pg_current_xact_id()::text")) {
            assertThat(rs.next()).isTrue();
            return new Tx(TransactionSynchronizationManager.isActualTransactionActive(), TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                    connection.getAutoCommit(), TransactionSynchronizationManager.getResource(PROBE.factory));
        }
    }
    private record Identity(long userId, long tallerId) {}
    private record Tx(boolean active, boolean readOnly, Integer isolation, String serverIsolation, String serverReadOnly,
                      int pid, String xid, boolean autoCommit, Object resource) {}
    enum Fault { NONE, USER_INSERT, COMMIT_BEFORE_SERVER, COMMIT_ACK_LOST }
    private static final class Probe {
        boolean active, failEmail, failSigning; int injected, signatures, deliveries; Fault fault;
        String requestEmail, sqlState; Identity created; Tx creation, session; EntityManagerFactory factory;
        Consumer<Identity> afterCommit; List<String> rowsAfterCommit, rowsAtEmail;
        final List<String> inserts = new ArrayList<>(), events = new ArrayList<>();
        void reset() {
            active = failEmail = failSigning = false; injected = signatures = deliveries = 0; fault = Fault.NONE;
            requestEmail = sqlState = null; created = null; creation = session = null; factory = null;
            afterCommit = ignored -> {}; rowsAfterCommit = rowsAtEmail = null; inserts.clear(); events.clear();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {
        @Bean @Primary Clock registrationClock() { return CLOCK; }
        @Bean @Primary EmailObserver registrationEmailObserver() { return new EmailObserver(); }
        @Bean @Primary ObservingJwt registrationJwt(Environment environment, DataSource source) { return new ObservingJwt(environment, source); }
        @Bean static BeanFactoryPostProcessor registrationEmailIsolation() {
            return factory -> {
                var definition = factory.getBeanDefinition("recordingEmailSender");
                if (!"com.leonardorozza.mvgrreparacionesbackend.support.RecordingEmailSender".equals(definition.getBeanClassName())
                        || !definition.isPrimary()) throw new IllegalStateException("Unexpected historical email fixture");
                definition.setPrimary(false);
            };
        }
        @Bean static BeanPostProcessor registrationDataSourceObservation() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return name.equals("dataSource") && bean instanceof DataSource source ? new ObservedDataSource(source) : bean;
                }
            };
        }
    }
    static final class EmailObserver implements EmailSender {
        @Override public void enviar(String recipient, String subject, String html) {
            assertThat(PROBE.created).isNotNull();
            var match = TOKEN_LINK.matcher(html); assertThat(match.find()).isTrue(); String raw = match.group(1);
            // The CTA and fallback repeat one committed token, not two independent credentials.
            assertThat(match.find()).isTrue();
            assertThat(match.group(1)).isEqualTo(raw);
            assertThat(match.find()).isFalse();
            String hash;
            try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
            // Distinct connection: account and token must both be committed before transport sees the link.
            assertThat(owner().queryForObject("SELECT count(*) FROM auth_tokens a JOIN users u ON u.id = a.user_id "
                    + "JOIN talleres t ON t.id = u.taller_id JOIN suscripciones s ON s.taller_id = t.id "
                    + "WHERE u.id = ? AND t.id = ? AND u.email = ? AND a.token_hash = ? AND a.tipo = 'VERIFICACION_EMAIL' "
                    + "AND a.usado_en IS NULL AND a.expira_en > localtimestamp", Long.class,
                    PROBE.created.userId(), PROBE.created.tallerId(), recipient, hash)).isEqualTo(1);
            PROBE.deliveries++; PROBE.events.add("email"); PROBE.rowsAtEmail = rows();
            if (PROBE.failEmail) throw new IllegalStateException("Synthetic email transport failure");
        }
    }
    static final class ObservingJwt extends JwtUtils {
        private final DataSource source;
        ObservingJwt(Environment environment, DataSource source) {
            super(environment.getRequiredProperty("security.jwt.secret"), environment.getRequiredProperty("security.jwt.expiration", Long.class),
                    environment.getRequiredProperty("security.jwt.issuer"), environment.getRequiredProperty("security.jwt.audience"),
                    environment.getRequiredProperty("security.jwt.clock-skew-seconds", Long.class));
            this.source = source;
        }
        @Override public String generateToken(UserDetails user, Long tallerId) {
            if (PROBE.active) {
                assertThat(PROBE.created).isNotNull(); assertThat(tallerId).isEqualTo(PROBE.created.tallerId());
                assertThat(owner().queryForObject("SELECT email FROM users WHERE id = ? AND taller_id = ?", String.class,
                        PROBE.created.userId(), tallerId)).isEqualTo(user.getUsername());
                var connection = DataSourceUtils.getConnection(source);
                try { PROBE.session = observe(connection); }
                catch (SQLException failure) { throw new AssertionError(failure); }
                finally { DataSourceUtils.releaseConnection(connection, source); }
                PROBE.signatures++; PROBE.events.add("session");
                if (PROBE.failSigning) throw new IllegalStateException("Synthetic signing failure");
            }
            return super.generateToken(user, tallerId);
        }
    }
    private static final class ObservedDataSource extends AbstractDataSource {
        private final DataSource delegate;
        ObservedDataSource(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException { return wrap(delegate.getConnection(username, password)); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type); }
        @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return type.isInstance(this) || delegate.isWrapperFor(type); }
        private static Connection wrap(Connection physical) {
            boolean[] account = {false};
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                    PreparedStatement statement = (PreparedStatement) invoke(physical, method, args);
                    var match = Pattern.compile("(?s)insert\\s+into\\s+(?:public\\.)?(talleres|suscripciones|users|auth_tokens)(?=[\\s(]).*")
                            .matcher(sql.replace("\"", "").stripLeading().toLowerCase(Locale.ROOT));
                    if (!match.matches()) return statement; String table = match.group(1);
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                        if (sm.getDeclaringClass() == Object.class) return objectMethod(sp, sm, sa);
                        if (PROBE.active && sm.getName().startsWith("execute")) {
                            PROBE.inserts.add(table);
                            if (!table.equals("auth_tokens")) { account[0] = true; if (PROBE.creation == null) PROBE.creation = observe(physical); }
                            if (table.equals("users") && PROBE.fault == Fault.USER_INSERT) {
                                PROBE.fault = Fault.NONE; PROBE.injected++;
                                try (var error = physical.createStatement()) { error.execute("SELECT 1/0"); }
                                catch (SQLException failure) { PROBE.sqlState = failure.getSQLState(); throw failure; }
                                throw new AssertionError("PostgreSQL did not reject division by zero");
                            }
                        }
                        return invoke(statement, sm, sa);
                    });
                }
                if (method.getName().equals("commit") && account[0] && PROBE.active) {
                    if (PROBE.fault == Fault.COMMIT_BEFORE_SERVER || PROBE.fault == Fault.COMMIT_ACK_LOST) {
                        Fault fault = PROBE.fault; PROBE.fault = Fault.NONE; PROBE.injected++;
                        if (fault == Fault.COMMIT_ACK_LOST) physical.commit(); else physical.close();
                        PROBE.sqlState = "08006"; throw new SQLException("Synthetic account commit acknowledgement failure", "08006");
                    }
                    Object result = invoke(physical, method, args);
                    PROBE.created = findIdentity(PROBE.requestEmail); PROBE.events.add("account-commit");
                    PROBE.afterCommit.accept(PROBE.created); PROBE.rowsAfterCommit = rows(); account[0] = false;
                    return result;
                }
                return invoke(physical, method, args);
            });
        }
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "LegacyRegistrationJdbcObservation[redacted]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError(method.getName());
        };
    }
}
