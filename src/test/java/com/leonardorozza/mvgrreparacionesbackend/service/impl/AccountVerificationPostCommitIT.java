package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real JPA and PostgreSQL; mail observes committed tokens and never contacts a transport. */
@SpringBootTest
@Testcontainers
@Import(AccountVerificationPostCommitIT.ObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountVerificationPostCommitIT {
    private static final Probe PROBE = new Probe();
    private static final String PASSWORD_HASH = "$2a$10$synthetic.verification.fixture.password.hash";
    private static final Pattern TOKEN_LINK = Pattern.compile("href=\"[^\"]*/verificar-email\\?token=([A-Za-z0-9_-]+)\"");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_account_verification_post_commit")
            .withUsername("ordenfix").withPassword("ordenfix");

    @Autowired private CuentaService cuenta;
    @Autowired private AccountVerificationTokenIssuer issuer;
    @Autowired private AccountVerificationNotifier notifier;
    @Autowired private UserRepository users;
    @Autowired private EmailObserver emails;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private ApplicationContext applicationContext;
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
        registry.add("app.public-url", () -> "https://verification.synthetic.invalid");
        registry.add("auth.token.verificacion-horas", () -> "48");
        registry.add("mail.enabled", () -> "false");
    }

    @BeforeEach void resetObservation() {
        PROBE.reset(); PROBE.entityManagerFactory = entityManagerFactory;
        emails.reset();
        assertThat(dataSource).isInstanceOf(ObservedDataSource.class);
        assertThat(applicationContext.getBean(EmailSender.class)).isSameAs(emails);
        assertNoAmbientTransaction();
    }

    @AfterEach void releaseObservation() {
        PROBE.reset(); emails.reset();
        assertNoAmbientTransaction();
    }

    @Test void aNewAccountCommitsBeforeItsVerificationTokenAndTheEmailObserver() {
        AtomicReference<Identity> created = new AtomicReference<>();
        AtomicReference<Object> outerResource = new AtomicReference<>();
        AtomicReference<String> outerXid = new AtomicReference<>();
        AtomicInteger outerPid = new AtomicInteger();
        transaction().executeWithoutResult(status -> {
            User user = persistAccount();
            Identity identity = identity(user); created.set(identity);
            outerResource.set(TransactionSynchronizationManager.getResource(entityManagerFactory));
            outerPid.set(((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue());
            outerXid.set(entityManager.createNativeQuery("SELECT pg_current_xact_id()::text").getSingleResult().toString());

            cuenta.enviarVerificacion(user);

            assertThat(emails.deliveries).isEmpty(); assertThat(PROBE.observations).isEmpty();
            assertThat(tokenRows(identity)).isEmpty();
            assertThat(owner().queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, identity.userId())).isZero();
        });

        Identity identity = created.get();
        Captured sent = onlyEmail(identity);
        assertThat(sent.subject()).isEqualTo("Confirmá tu email de OrdenFix");
        assertThat(sent.html()).contains("Tu cuenta de OrdenFix ya está creada.", "Confirmar mi email", "48 horas", "https://verification.synthetic.invalid/verificar-email?token=");
        assertThat(sent.observedRows()).hasSize(1).isEqualTo(tokenRows(identity));
        TokenRow token = sent.observedRows().getFirst();
        assertThat(token.hash()).isEqualTo(sha(sent.rawToken())).hasSize(64);
        assertThat(token.rawRow()).doesNotContain(sent.rawToken());
        assertThat(token.usedAt()).isNull(); assertThat(token.expiresAt()).isAfter(LocalDateTime.now().plusHours(47));
        TxObservation inner = onlyTokenTransaction();
        assertNewMutableTransaction(inner);
        assertThat(inner.pid()).isNotEqualTo(outerPid.get());
        assertThat(inner.entityManagerResource()).isNotSameAs(outerResource.get());
        assertThat(inner.xid()).isNotEqualTo(outerXid.get());
        assertThat(token.xmin()).isEqualTo(inner.xid());
        assertNoAmbientTransaction();
    }

    @Test void outerRollbackNeverCreatesATokenOrSendsAnEmail() {
        AtomicReference<Identity> created = new AtomicReference<>();
        transaction().executeWithoutResult(status -> {
            User user = persistAccount(); created.set(identity(user));
            cuenta.enviarVerificacion(user);
            assertThat(emails.deliveries).isEmpty(); assertThat(tokenRows(created.get())).isEmpty();
            status.setRollbackOnly();
        });
        Identity identity = created.get();
        assertThat(PROBE.observations).isEmpty(); assertThat(emails.deliveries).isEmpty();
        assertThat(tokenRows(identity)).isEmpty();
        assertThat(owner().queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, identity.userId())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM talleres WHERE id = ?", Long.class, identity.workshopId())).isZero();
    }

    @Test void invocationWithoutAnOuterTransactionPersistsThenSendsAndReleasesItsResources() {
        Identity identity = seed(); var accountBefore = accountRows(identity);

        cuenta.enviarVerificacion(detached(identity));

        onlyEmail(identity); assertNewMutableTransaction(onlyTokenTransaction());
        assertThat(accountRows(identity)).isEqualTo(accountBefore); assertNoAmbientTransaction();
    }

    @Test void callbackUsesTheDurableIdentityAndTheValuesCommittedAfterScheduling() {
        Identity identity = seed();
        String changedEmail = UUID.randomUUID() + "@verification.synthetic.invalid";
        transaction().executeWithoutResult(status -> {
            User managed = users.findSessionByIdAndTallerId(identity.userId(), identity.workshopId()).orElseThrow();
            cuenta.enviarVerificacion(managed);
            managed.setEmail(changedEmail); managed.setUsername("Current <admin> & name");
            entityManager.flush();
            assertThat(emails.deliveries).isEmpty(); assertThat(tokenRows(identity)).isEmpty();
        });

        Captured sent = onlyEmail(new Identity(identity.userId(), identity.workshopId(), changedEmail));
        assertThat(sent.html()).contains("Current &lt;admin&gt; &amp; name").doesNotContain("Current <admin>");
        assertThat(sent.observedRows()).singleElement().satisfies(row -> assertThat(row.userId()).isEqualTo(identity.userId()));
    }

    @Test void issuerSuspendsTheOuterJpaContextAndCannotObserveItsUncommittedAccountChanges() {
        Identity identity = seed(); var accountBefore = accountRows(identity);
        TransactionTemplate outer = transaction(); outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        AtomicReference<String> raw = new AtomicReference<>();
        outer.executeWithoutResult(status -> {
            User managed = users.findSessionByIdAndTallerId(identity.userId(), identity.workshopId()).orElseThrow();
            Object resource = TransactionSynchronizationManager.getResource(entityManagerFactory);
            int pid = ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
            // User has no DynamicUpdate: a normal Hibernate flush also names the unique email
            // column. Update only non-key columns on this same JPA connection, then reflect
            // those values in the managed instance without another flush before suspension.
            entityManager.createNativeQuery("UPDATE users SET active = false, username = :name WHERE id = :id")
                    .setParameter("name", "Uncommitted actor").setParameter("id", identity.userId()).executeUpdate();
            managed.setActive(false); managed.setUsername("Uncommitted actor");

            var issued = issuer.issue(identity.userId(), identity.workshopId()).orElseThrow();

            raw.set(issued.rawToken()); assertThat(issued.recipient()).isEqualTo(identity.email());
            assertThat(issued.displayName()).isEqualTo("Verification actor");
            TxObservation inner = onlyTokenTransaction(); assertNewMutableTransaction(inner);
            assertThat(inner.pid()).isNotEqualTo(pid); assertThat(inner.entityManagerResource()).isNotSameAs(resource);
            assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory)).isSameAs(resource);
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(entityManager.find(User.class, identity.userId())).isSameAs(managed);
            assertThat(managed.getActive()).isFalse(); assertThat(managed.getUsername()).isEqualTo("Uncommitted actor");
            assertThat(managed.getEmail()).isEqualTo(identity.email());
            assertThat(status.isRollbackOnly()).isFalse();
            assertThat(emails.deliveries).isEmpty();
            assertThat(tokenRows(identity)).singleElement().satisfies(row -> assertThat(row.hash()).isEqualTo(sha(raw.get())));
            status.setRollbackOnly();
        });
        assertThat(accountRows(identity)).isEqualTo(accountBefore);
        assertThat(tokenRows(identity)).singleElement().satisfies(row -> assertThat(row.hash()).isEqualTo(sha(raw.get())));
    }

    @Test void issuerReadsCommittedChangesByIdsEvenWhenTheOuterContextKeepsTheOldEmail() {
        Identity identity = seed(); Identity replacement = seed();
        String changed = UUID.randomUUID() + "@verification.synthetic.invalid";
        transaction().executeWithoutResult(status -> {
            User old = users.findSessionByIdAndTallerId(identity.userId(), identity.workshopId()).orElseThrow();
            Object resource = TransactionSynchronizationManager.getResource(entityManagerFactory);
            owner().update("UPDATE users SET email = ?, username = ? WHERE id = ?", changed, "Durable actor", identity.userId());
            owner().update("UPDATE users SET email = ? WHERE id = ?", identity.email(), replacement.userId());

            var issued = issuer.issue(identity.userId(), identity.workshopId()).orElseThrow();

            assertThat(issued.recipient()).isEqualTo(changed); assertThat(issued.displayName()).isEqualTo("Durable actor");
            assertThat(old.getEmail()).isEqualTo(identity.email());
            assertThat(entityManager.find(User.class, identity.userId())).isSameAs(old);
            assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory)).isSameAs(resource);
            assertThat(tokenRows(replacement)).isEmpty();
            assertThat(tokenRows(identity)).singleElement().satisfies(row -> assertThat(row.hash()).isEqualTo(sha(issued.rawToken())));
            status.setRollbackOnly();
        });
        assertThat(emails.deliveries).isEmpty(); assertNoAmbientTransaction();
    }

    @Test void theDeliveredTokenIsReallyConsumableOnceAndPersistsItsUsedTimestamp() {
        Identity identity = seed(); notifier.notifyVerification(identity.userId(), identity.workshopId());
        Captured sent = onlyEmail(identity); TokenRow before = tokenRows(identity).getFirst();

        cuenta.verificarEmail(sent.rawToken());

        assertThat(owner().queryForObject("SELECT email_verificado FROM users WHERE id = ?", Boolean.class, identity.userId())).isTrue();
        TokenRow consumed = tokenRows(identity).getFirst();
        assertThat(consumed.hash()).isEqualTo(before.hash()); assertThat(consumed.usedAt()).isNotNull();
        assertThat(consumed.usedAt()).isBefore(consumed.expiresAt());
        assertThatThrownBy(() -> cuenta.verificarEmail(sent.rawToken())).isInstanceOf(BadRequestException.class);
        assertThat(tokenRows(identity)).containsExactly(consumed); assertThat(emails.deliveries).hasSize(1);
    }

    @Test void sequentialResendReplacesOnlyVerificationTokensAndItsNewLinkRemainsUsable() {
        Identity identity = seed(); notifier.notifyVerification(identity.userId(), identity.workshopId());
        String original = onlyEmail(identity).rawToken();
        String resetHash = sha("reset-" + UUID.randomUUID());
        owner().update("INSERT INTO auth_tokens(user_id,tipo,token_hash,expira_en,created_at) "
                + "VALUES (?,'RESET_PASSWORD',?,localtimestamp + interval '1 hour',localtimestamp)", identity.userId(), resetHash);
        List<String> resetBefore = resetRows(identity);

        cuenta.reenviarVerificacion(identity.email());

        assertThat(emails.deliveries).hasSize(2);
        Captured second = emails.deliveries.getLast(); assertThat(second.rawToken()).isNotEqualTo(original);
        assertThat(second.observedRows()).singleElement().satisfies(row -> assertThat(row.hash()).isEqualTo(sha(second.rawToken())));
        assertThat(resetRows(identity)).isEqualTo(resetBefore);
        assertThatThrownBy(() -> cuenta.verificarEmail(original)).isInstanceOf(BadRequestException.class);
        cuenta.verificarEmail(second.rawToken());
        var afterConsumption = tokenRows(identity);

        cuenta.reenviarVerificacion(identity.email());

        assertThat(emails.deliveries).hasSize(2); assertThat(tokenRows(identity)).isEqualTo(afterConsumption);
        assertThat(resetRows(identity)).isEqualTo(resetBefore);
    }

    @ParameterizedTest @ValueSource(strings = {"user-inactive", "workshop-inactive", "verified", "foreign-workshop", "missing-user"})
    void currentEligibilityAndDurableIdsRejectWithoutReplacingExistingTokens(String defect) {
        Identity identity = seed(); notifier.notifyVerification(identity.userId(), identity.workshopId());
        emails.reset(); PROBE.observations.clear();
        long userId = identity.userId(), workshopId = identity.workshopId();
        switch (defect) {
            case "user-inactive" -> owner().update("UPDATE users SET active = false WHERE id = ?", userId);
            case "workshop-inactive" -> owner().update("UPDATE talleres SET activo = false WHERE id = ?", workshopId);
            case "verified" -> owner().update("UPDATE users SET email_verificado = true WHERE id = ?", userId);
            case "foreign-workshop" -> workshopId = seed().workshopId();
            case "missing-user" -> userId = Long.MAX_VALUE;
            default -> throw new AssertionError(defect);
        }
        var before = tokenRows(identity); var accountBefore = accountRows(identity);

        assertThat(issuer.issue(userId, workshopId)).isEmpty();
        notifier.notifyVerification(userId, workshopId);

        assertThat(emails.deliveries).isEmpty(); assertThat(PROBE.observations).isEmpty();
        assertThat(tokenRows(identity)).isEqualTo(before); assertThat(accountRows(identity)).isEqualTo(accountBefore);
    }

    @Test void aRealPostgresInsertFailureRollsBackThePreviousTokensDeletionWithoutAnEmail() {
        Identity identity = seed(); notifier.notifyVerification(identity.userId(), identity.workshopId());
        var tokensBefore = tokenRows(identity); var accountBefore = accountRows(identity); emails.reset();
        PROBE.arm(Fault.INSERT);

        assertThatCode(() -> notifier.notifyVerification(identity.userId(), identity.workshopId())).doesNotThrowAnyException();

        assertThat(PROBE.injected).hasValue(1); assertThat(PROBE.lastSqlState.get()).isEqualTo("22012");
        assertThat(emails.deliveries).isEmpty(); assertThat(tokenRows(identity)).isEqualTo(tokensBefore);
        assertThat(accountRows(identity)).isEqualTo(accountBefore); assertNoAmbientTransaction();
        notifier.notifyVerification(identity.userId(), identity.workshopId());
        Captured recovered = onlyEmail(identity);
        assertThat(recovered.observedRows()).singleElement().satisfies(row -> assertThat(row.hash()).isNotEqualTo(tokensBefore.getFirst().hash()));
        cuenta.verificarEmail(recovered.rawToken());
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"COMMIT_BEFORE_SERVER", "COMMIT_ACK_LOST"})
    void anUnacknowledgedIssuerCommitNeverDeliversAndExplicitResendRecovers(Fault fault) {
        Identity identity = seed(); notifier.notifyVerification(identity.userId(), identity.workshopId());
        var tokensBefore = tokenRows(identity); var accountBefore = accountRows(identity); emails.reset();
        PROBE.arm(fault);

        assertThatCode(() -> notifier.notifyVerification(identity.userId(), identity.workshopId())).doesNotThrowAnyException();

        assertThat(PROBE.injected).hasValue(1); assertThat(PROBE.lastSqlState.get()).isEqualTo("08006");
        assertThat(emails.deliveries).isEmpty(); assertThat(accountRows(identity)).isEqualTo(accountBefore);
        var afterFailure = tokenRows(identity);
        if (fault == Fault.COMMIT_BEFORE_SERVER) assertThat(afterFailure).isEqualTo(tokensBefore);
        else {
            assertThat(afterFailure).hasSize(1);
            assertThat(afterFailure.getFirst().hash()).isNotEqualTo(tokensBefore.getFirst().hash());
        }
        assertNoAmbientTransaction();

        cuenta.reenviarVerificacion(identity.email());

        Captured recovered = onlyEmail(identity);
        assertThat(recovered.observedRows()).singleElement().satisfies(row -> assertThat(row.hash()).isNotEqualTo(afterFailure.getFirst().hash()));
        cuenta.verificarEmail(recovered.rawToken());
    }

    @Test void transportFailureCannotUndoTheCommittedTokenOrAccountAndResendIsExplicit() {
        Identity identity = seed(); var accountBefore = accountRows(identity);
        emails.failAfterObservation = true;

        assertThatCode(() -> notifier.notifyVerification(identity.userId(), identity.workshopId())).doesNotThrowAnyException();

        Captured attempted = onlyEmail(identity); var durable = tokenRows(identity);
        assertThat(durable).isEqualTo(attempted.observedRows()); assertThat(accountRows(identity)).isEqualTo(accountBefore);
        assertNoAmbientTransaction(); emails.failAfterObservation = false;

        cuenta.reenviarVerificacion(identity.email());

        assertThat(emails.deliveries).hasSize(2);
        String replacement = emails.deliveries.getLast().rawToken(); assertThat(replacement).isNotEqualTo(attempted.rawToken());
        assertThatThrownBy(() -> cuenta.verificarEmail(attempted.rawToken())).isInstanceOf(BadRequestException.class);
        cuenta.verificarEmail(replacement);
    }

    private Captured onlyEmail(Identity identity) {
        assertThat(emails.deliveries).hasSize(1);
        Captured result = emails.deliveries.getFirst();
        assertThat(result.recipient()).isEqualTo(identity.email());
        assertThat(result.observedRows()).singleElement().satisfies(row -> assertThat(row.userId()).isEqualTo(identity.userId()));
        return result;
    }

    private TxObservation onlyTokenTransaction() {
        assertThat(PROBE.observations).hasSize(1); return PROBE.observations.getFirst();
    }

    private static void assertNewMutableTransaction(TxObservation observation) {
        assertThat(observation.springActive()).isTrue(); assertThat(observation.springReadOnly()).isFalse();
        assertThat(observation.springIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(observation.serverIsolation()).isEqualTo("read committed"); assertThat(observation.serverReadOnly()).isEqualTo("off");
        assertThat(observation.autoCommit()).isFalse(); assertThat(observation.entityManagerResource()).isNotNull();
    }

    private void assertNoAmbientTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory)).isNull();
        assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull();
    }

    private Identity seed() {
        String marker = UUID.randomUUID().toString(); JdbcTemplate owner = owner();
        long workshopId = Objects.requireNonNull(owner.queryForObject(
                "INSERT INTO talleres(nombre,activo) VALUES (?,true) RETURNING id", Long.class, "Verification " + marker));
        String email = marker + "@verification.synthetic.invalid";
        long userId = Objects.requireNonNull(owner.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES ('Verification actor',?,?,'ADMIN',?,true,false,0) RETURNING id
                """, Long.class, email, PASSWORD_HASH, workshopId));
        return new Identity(userId, workshopId, email);
    }

    private User persistAccount() {
        Taller workshop = Taller.builder().nombre("Verification workshop " + UUID.randomUUID()).activo(true).build();
        entityManager.persist(workshop);
        User user = User.builder().username("Verification actor").email(UUID.randomUUID() + "@verification.synthetic.invalid")
                .password(PASSWORD_HASH).role(UserRole.ADMIN).active(true).emailVerificado(false).taller(workshop).build();
        entityManager.persist(user); entityManager.flush(); return user;
    }

    private static Identity identity(User user) { return new Identity(user.getId(), user.getTaller().getId(), user.getEmail()); }
    private User detached(Identity identity) { return users.findSessionByIdAndTallerId(identity.userId(), identity.workshopId()).orElseThrow(); }
    private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static List<TokenRow> tokenRows(Identity identity) {
        return owner().query("SELECT user_id,token_hash,expira_en,usado_en,row_to_json(a)::text,xmin::text "
                        + "FROM auth_tokens a WHERE user_id = ? AND tipo = 'VERIFICACION_EMAIL' ORDER BY id",
                (rs, row) -> new TokenRow(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toLocalDateTime(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toLocalDateTime(), rs.getString(5), rs.getString(6)), identity.userId());
    }
    private static List<String> resetRows(Identity identity) {
        return owner().queryForList("SELECT row_to_json(a)::text || ':' || xmin::text FROM auth_tokens a "
                + "WHERE user_id = ? AND tipo = 'RESET_PASSWORD' ORDER BY id", String.class, identity.userId());
    }
    private static List<String> accountRows(Identity identity) {
        return owner().queryForList("SELECT 'user:' || row_to_json(u)::text || ':' || xmin::text AS snapshot FROM users u WHERE id = ? "
                + "UNION ALL SELECT 'workshop:' || row_to_json(t)::text || ':' || xmin::text FROM talleres t WHERE id = ? ORDER BY snapshot",
                String.class, identity.userId(), identity.workshopId());
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    private record Identity(long userId, long workshopId, String email) {}
    private record TokenRow(long userId, String hash, LocalDateTime expiresAt, LocalDateTime usedAt, String rawRow, String xmin) {}
    private record Captured(String recipient, String subject, String html, String rawToken, List<TokenRow> observedRows) {}
    private record TxObservation(boolean springActive, boolean springReadOnly, Integer springIsolation,
                                 String serverIsolation, String serverReadOnly, int pid, String xid, boolean autoCommit,
                                 Object entityManagerResource) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {
        @Bean @Primary EmailObserver verificationEmailObserver() { return new EmailObserver(); }
        @Bean static BeanFactoryPostProcessor verificationEmailIsolation() {
            return factory -> {
                // The historical test component remains available; only this isolated context
                // selects the observer as its single primary EmailSender.
                var definition = factory.getBeanDefinition("recordingEmailSender");
                if (!"com.leonardorozza.mvgrreparacionesbackend.support.RecordingEmailSender".equals(definition.getBeanClassName())
                        || !definition.isPrimary()) throw new IllegalStateException("Unexpected historical email fixture");
                definition.setPrimary(false);
            };
        }
        @Bean static BeanPostProcessor verificationDataSourceObservation() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return name.equals("dataSource") && bean instanceof DataSource source
                            ? new ObservedDataSource(source) : bean;
                }
            };
        }
    }

    static final class EmailObserver implements EmailSender {
        final List<Captured> deliveries = new ArrayList<>();
        boolean failAfterObservation;
        void reset() { deliveries.clear(); failAfterObservation = false; }
        @Override public void enviar(String recipient, String subject, String html) {
            var match = TOKEN_LINK.matcher(html);
            // AssertionError is deliberate: best-effort handling cannot hide a broken observation.
            assertThat(match.find()).isTrue(); String rawToken = match.group(1);
            assertThat(rawToken).hasSize(43);
            // The CTA and fallback repeat one committed token, not two independent credentials.
            assertThat(match.find()).isTrue();
            assertThat(match.group(1)).isEqualTo(rawToken);
            assertThat(match.find()).isFalse();
            var identities = owner().query("SELECT id,taller_id FROM users WHERE email = ?",
                    (rs, row) -> new Identity(rs.getLong(1), rs.getLong(2), recipient), recipient);
            assertThat(identities).hasSize(1);
            List<TokenRow> durable = tokenRows(identities.getFirst());
            assertThat(durable).singleElement().satisfies(row -> {
                assertThat(row.hash()).isEqualTo(sha(rawToken)); assertThat(row.usedAt()).isNull();
            });
            deliveries.add(new Captured(recipient, subject, html, rawToken, durable));
            if (failAfterObservation) throw new IllegalStateException("Synthetic mail transport failure");
        }
    }

    enum Fault { NONE, INSERT, COMMIT_BEFORE_SERVER, COMMIT_ACK_LOST }

    private static final class Probe {
        private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
        private final AtomicInteger injected = new AtomicInteger();
        private final AtomicReference<String> lastSqlState = new AtomicReference<>();
        private final List<TxObservation> observations = new ArrayList<>();
        private EntityManagerFactory entityManagerFactory;
        void arm(Fault selected) { fault.set(selected); injected.set(0); lastSqlState.set(null); }
        void reset() { arm(Fault.NONE); observations.clear(); entityManagerFactory = null; }
        void observe(Connection connection) throws SQLException {
            if (entityManagerFactory == null) return;
            // A fixture-only failure bound prevents an accidental lock cycle from hanging the
            // suite. It neither configures production nor participates in a timing assertion.
            try (var statement = connection.createStatement()) { statement.execute("SET LOCAL lock_timeout = '2s'"); }
            try (var statement = connection.createStatement(); var result = statement.executeQuery(
                    "SELECT current_setting('transaction_isolation'),current_setting('transaction_read_only'),pg_backend_pid(),pg_current_xact_id()::text")) {
                assertThat(result.next()).isTrue();
                observations.add(new TxObservation(TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), result.getString(1), result.getString(2),
                        result.getInt(3), result.getString(4), connection.getAutoCommit(),
                        TransactionSynchronizationManager.getResource(entityManagerFactory)));
            }
        }
        void insertFailure(Connection connection) throws SQLException {
            if (!fault.compareAndSet(Fault.INSERT, Fault.NONE)) return;
            injected.incrementAndGet();
            try (var statement = connection.createStatement()) { statement.execute("SELECT 1/0"); }
            catch (SQLException failure) { lastSqlState.set(failure.getSQLState()); throw failure; }
            throw new AssertionError("PostgreSQL did not reject division by zero");
        }
    }

    private static final class ObservedDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private ObservedDataSource(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException { return wrap(delegate.getConnection(username, password)); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type); }
        @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return type.isInstance(this) || delegate.isWrapperFor(type); }

        private static Connection wrap(Connection physical) {
            final boolean[] hasTokenInsert = {false};
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (method.getName().equals("prepareStatement") && args != null && args.length > 0 && args[0] instanceof String sql) {
                    PreparedStatement statement = (PreparedStatement) invoke(physical, method, args);
                    String normalized = sql.replace("\"", "").stripLeading().toLowerCase(Locale.ROOT);
                    if (!normalized.matches("(?s)insert\\s+into\\s+(?:public\\.)?auth_tokens(?=[\\s(]).*")) return statement;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                            (statementProxy, statementMethod, statementArgs) -> {
                                if (statementMethod.getDeclaringClass() == Object.class) return objectMethod(statementProxy, statementMethod, statementArgs);
                                if (statementMethod.getName().startsWith("execute")) {
                                    if (!hasTokenInsert[0]) { PROBE.observe(physical); hasTokenInsert[0] = true; }
                                    PROBE.insertFailure(physical);
                                }
                                return invoke(statement, statementMethod, statementArgs);
                            });
                }
                if (method.getName().equals("commit") && hasTokenInsert[0]) {
                    Fault fault = PROBE.fault.getAndSet(Fault.NONE);
                    if (fault == Fault.COMMIT_BEFORE_SERVER || fault == Fault.COMMIT_ACK_LOST) {
                        PROBE.injected.incrementAndGet();
                        if (fault == Fault.COMMIT_ACK_LOST) physical.commit();
                        else physical.close(); // Hikari rolls back dirty work when this connection is returned.
                        PROBE.lastSqlState.set("08006");
                        throw new SQLException("Synthetic verification commit acknowledgement failure", "08006");
                    }
                }
                return invoke(physical, method, args);
            });
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "VerificationJdbcObservation[redacted]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError(method.getName());
        };
    }
}
