package com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth;

import com.auth0.jwt.JWT;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserSecurityStateLock;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose.DESCARGAR_EXPORTACION;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose.EXPORTAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL 16 + Flyway V31 + real JWT/BCrypt. No HTTP or external provider. */
@SpringBootTest(properties = {
        "spring.config.import=", "spring.config.additional-location=",
        "spring.config.location=optional:classpath:/application.properties",
        "mail.enabled=false", "mercadopago.enabled=false", "mercadopago.checkout-enabled=false",
        "photos.private.enabled=false", "ordenfix.legal.registration-consent.enabled=false",
        "ordenfix.legal.registration-enforcement.enabled=false", "ordenfix.legal.account-read.enabled=false",
        "ordenfix.legal.account-acceptance.enabled=false", "ordenfix.legal.public-documents.enabled=false",
        "ordenfix.legal.public-requirements.enabled=false", "ordenfix.legal.aggregate-context.enabled=false",
        "ordenfix.legal.editorial-context.enabled=false", "ordenfix.legal.import-context.enabled=false",
        "ordenfix.legal.dry-run-context.enabled=false", "ordenfix.legal.public-document-read-context.enabled=false",
        "ordenfix.legal.public-requirements-context.enabled=false"
})
@Import(ExportReauthenticationServiceIT.TestClockConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExportReauthenticationServiceIT {
    private static final String PASSWORD = "reauth-synthetic-password-123";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_export_reauthentication").withUsername("ordenfix").withPassword("ordenfix");

    @Autowired private ExportReauthenticationService reauthentication;
    @Autowired private UserRepository users;
    @Autowired private JwtUtils jwt;
    @Autowired private UserSecurityStateLock securityState;
    @Autowired private PasswordEncoder encoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

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

    @BeforeEach
    void prepareIsolatedFixture() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        jdbc.execute("CREATE TABLE IF NOT EXISTS reauth_effects_fixture (id UUID PRIMARY KEY, user_id BIGINT NOT NULL)");
    }

    @ParameterizedTest
    @EnumSource(ExportReauthenticationPurpose.class)
    void issuesOnlyHashedGrantBoundToActorFullSessionPurposeAndFiveMinuteLifetime(ExportReauthenticationPurpose purpose) {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, purpose);
        StoredGrant persisted = grantFor(grant.token());

        assertThat(grant.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(grant.token())).hasSize(32);
        assertThat(grant.toString()).doesNotContain(grant.token());
        assertThat(persisted.tokenHash()).isEqualTo(sha(grant.token()));
        assertThat(persisted.sessionHash()).isEqualTo(sha(access));
        assertThat(persisted.userId()).isEqualTo(actor.userId());
        assertThat(persisted.workshopId()).isEqualTo(actor.workshopId());
        assertThat(persisted.tokenVersion()).isZero();
        assertThat(persisted.purpose()).isEqualTo(purpose.name());
        assertThat(persisted.createdAt()).isEqualTo(clock.instant());
        assertThat(persisted.expiresAt()).isEqualTo(clock.instant().plusSeconds(300)).isEqualTo(grant.expiresAt());
        assertThat(grant.expiresAt()).isBeforeOrEqualTo(JWT.decode(access).getExpiresAtAsInstant());
        assertThat(persisted.usedAt()).isNull();
        assertThat(owner().queryForObject("SELECT row_to_json(r)::text FROM cuenta_reautenticaciones r WHERE token_hash = ?",
                String.class, sha(grant.token()))).doesNotContain(grant.token(), access, PASSWORD);
        assertThat(owner().queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '31' AND success", Long.class))
                .isEqualTo(1L);
        consumeWithEffect(actor, access, grant.token(), purpose);
        assertThat(grantFor(grant.token()).usedAt()).isEqualTo(clock.instant());
        assertThat(effectCount(actor)).isEqualTo(1L);
    }

    @Test
    void wrongPasswordCreatesNothingAndDoesNotInvalidateAnExistingGrant() {
        Identity actor = seed();
        String access = accessToken(actor);
        badRequest(() -> reauthentication.issue(access, "wrong-password", EXPORTAR), "PASSWORD_ACTUAL_INVALIDA");
        assertThat(rows(actor)).isEmpty();
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        var before = rows(actor);
        badRequest(() -> reauthentication.issue(access, "wrong-password", EXPORTAR), "PASSWORD_ACTUAL_INVALIDA");
        assertThat(rows(actor)).isEqualTo(before);
        consumeWithEffect(actor, access, grant.token(), EXPORTAR);
    }

    @ParameterizedTest
    @EnumSource(ActorMutation.class)
    void rechecksDatabaseAuthorityForBothIssuanceAndConsumption(ActorMutation mutation) {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        mutation.apply(owner(), actor);
        var before = rows(actor);
        assertThatThrownBy(() -> reauthentication.issue(access, PASSWORD, EXPORTAR)).isInstanceOf(mutation.failureType);
        assertThatThrownBy(() -> consumeWithEffect(actor, access, grant.token(), EXPORTAR)).isInstanceOf(mutation.failureType);
        assertThat(rows(actor)).isEqualTo(before);
        assertThat(effectCount(actor)).isZero();
    }

    @Test
    void aDifferentJwtFromTheSameActorCannotConsumeTheGrant() {
        Identity actor = seed();
        String access = accessToken(actor);
        String otherSession = accessToken(actor);
        assertThat(JWT.decode(access).getId()).isNotEqualTo(JWT.decode(otherSession).getId());
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        badRequest(() -> consumeWithEffect(actor, otherSession, grant.token(), EXPORTAR), "REAUTENTICACION_INVALIDA");
        assertThat(grantFor(grant.token()).usedAt()).isNull();
        assertThat(effectCount(actor)).isZero();
        consumeWithEffect(actor, access, grant.token(), EXPORTAR);
    }

    @Test
    void anotherWorkshopCannotConsumeTheGrant() {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        Identity other = seed();
        badRequest(() -> consumeWithEffect(other, accessToken(other), grant.token(), EXPORTAR), "REAUTENTICACION_INVALIDA");
        assertThat(effectCount(other)).isZero();
        assertThat(grantFor(grant.token()).usedAt()).isNull();
        consumeWithEffect(actor, access, grant.token(), EXPORTAR);
    }

    @Test
    void purposeMismatchDoesNotConsumeOrAuthorizeAnEffect() {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        badRequest(() -> consumeWithEffect(actor, access, grant.token(), DESCARGAR_EXPORTACION), "REAUTENTICACION_INVALIDA");
        assertThat(grantFor(grant.token()).usedAt()).isNull();
        assertThat(effectCount(actor)).isZero();
        consumeWithEffect(actor, access, grant.token(), EXPORTAR);
    }

    @Test
    void grantExpiresAtTheBoundaryWithoutMutatingItsRow() {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        var before = rows(actor);
        clock.set(grant.expiresAt());
        badRequest(() -> consumeWithEffect(actor, access, grant.token(), EXPORTAR), "REAUTENTICACION_INVALIDA");
        assertThat(rows(actor)).isEqualTo(before);
        assertThat(effectCount(actor)).isZero();
    }

    @Test
    void grantLifetimeCannotOutliveItsAccessToken() {
        Identity actor = seed();
        String access = accessToken(actor);
        Instant accessExpires = JWT.decode(access).getExpiresAtAsInstant();
        clock.set(accessExpires.minusSeconds(17));
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        assertThat(grant.expiresAt()).isEqualTo(accessExpires);
        assertThat(grantFor(grant.token()).expiresAt()).isEqualTo(accessExpires);
    }

    @Test
    void tokenVersionChangeRevokesIssuanceAndConsumptionFromTheOldSession() {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        owner().update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", actor.userId());
        var before = rows(actor);
        assertThatThrownBy(() -> reauthentication.issue(access, PASSWORD, EXPORTAR)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> consumeWithEffect(actor, access, grant.token(), EXPORTAR)).isInstanceOf(UnauthorizedException.class);
        assertThat(rows(actor)).isEqualTo(before);
        assertThat(effectCount(actor)).isZero();
    }

    @Test
    void rejectsConsumptionWithoutAnEffectTransactionOrInsideReadOnlyTransaction() {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        var before = rows(actor);
        assertThatThrownBy(() -> reauthentication.consume(access, grant.token(), EXPORTAR))
                .isInstanceOf(IllegalTransactionStateException.class);
        TransactionTemplate readOnly = transaction();
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> reauthentication.consume(access, grant.token(), EXPORTAR)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rows(actor)).isEqualTo(before);
        consumeWithEffect(actor, access, grant.token(), EXPORTAR);
    }

    @Test
    void effectRollbackRestoresTheGrantAndAllowsOneLaterSuccessfulConsumption() {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        var before = rows(actor);
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            reauthentication.consume(access, grant.token(), EXPORTAR);
            insertEffect(actor);
            throw new SimulatedEffectFailure();
        })).isInstanceOf(SimulatedEffectFailure.class);
        assertThat(rows(actor)).isEqualTo(before);
        assertThat(effectCount(actor)).isZero();
        consumeWithEffect(actor, access, grant.token(), EXPORTAR);
        badRequest(() -> consumeWithEffect(actor, access, grant.token(), EXPORTAR), "REAUTENTICACION_INVALIDA");
        assertThat(effectCount(actor)).isEqualTo(1L);
    }

    @Test
    void replacementInvalidatesOnlyItsOwnSessionAndPurpose() {
        Identity actor = seed();
        String first = accessToken(actor);
        String second = accessToken(actor);
        ReauthenticationGrant replaced = reauthentication.issue(first, PASSWORD, EXPORTAR);
        ReauthenticationGrant otherSession = reauthentication.issue(second, PASSWORD, EXPORTAR);
        ReauthenticationGrant otherPurpose = reauthentication.issue(first, PASSWORD, DESCARGAR_EXPORTACION);
        ReauthenticationGrant replacement = reauthentication.issue(first, PASSWORD, EXPORTAR);
        assertThat(replacement.token()).isNotEqualTo(replaced.token());
        assertThat(rows(actor)).hasSize(3);
        badRequest(() -> consumeWithEffect(actor, first, replaced.token(), EXPORTAR), "REAUTENTICACION_INVALIDA");
        consumeWithEffect(actor, second, otherSession.token(), EXPORTAR);
        consumeWithEffect(actor, first, otherPurpose.token(), DESCARGAR_EXPORTACION);
        consumeWithEffect(actor, first, replacement.token(), EXPORTAR);
        assertThat(effectCount(actor)).isEqualTo(3L);
    }

    @Test
    void twoConcurrentTransactionsCanCommitOnlyOneConsumptionAndOneEffect() throws Exception {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        CountDownLatch firstEffect = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger secondPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        Future<?> first = executor.submit(() -> transaction().executeWithoutResult(status -> {
            reauthentication.consume(access, grant.token(), EXPORTAR);
            insertEffect(actor);
            firstEffect.countDown();
            await(allowCommit);
        }));
        Future<?> second = null;
        try {
            assertThat(firstEffect.await(10, TimeUnit.SECONDS)).isTrue();
            second = executor.submit(() -> badRequest(() -> transaction().executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '10s'");
                secondPid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                secondStarted.countDown();
                reauthentication.consume(access, grant.token(), EXPORTAR);
                insertEffect(actor);
            }), "REAUTENTICACION_INVALIDA"));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertWaitingForLock(secondPid.get(), second);
            allowCommit.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            allowCommit.countDown();
            first.cancel(true);
            if (second != null) second.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(effectCount(actor)).isEqualTo(1L);
        assertThat(grantFor(grant.token()).usedAt()).isEqualTo(clock.instant());
    }

    @Test
    void expirationWhileWaitingForTheGrantRowRollsBackTheConsumption() throws Exception {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        var before = rows(actor);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger pid = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        Future<?> worker = null;
        try (Connection blocker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT token_hash FROM cuenta_reautenticaciones WHERE token_hash = ? FOR UPDATE")) {
                lock.setString(1, sha(grant.token()));
                try (var row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            try {
                worker = executor.submit(() -> badRequest(() -> transaction().executeWithoutResult(status -> {
                    jdbc.execute("SET LOCAL lock_timeout = '10s'");
                    pid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                    started.countDown();
                    reauthentication.consume(access, grant.token(), EXPORTAR);
                    insertEffect(actor);
                }), "REAUTENTICACION_INVALIDA"));
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                assertWaitingForLock(pid.get(), worker);
                clock.set(grant.expiresAt().plusSeconds(1));
            } finally {
                blocker.rollback();
            }
            worker.get(15, TimeUnit.SECONDS);
        } finally {
            if (worker != null) worker.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(rows(actor)).isEqualTo(before);
        assertThat(grantFor(grant.token()).usedAt()).isNull();
        assertThat(effectCount(actor)).isZero();
    }

    @Test
    void aRepeatedRandomTokenFailsWithoutRenewingOrDeletingTheExistingGrant() {
        Identity actor = seed();
        String access = accessToken(actor);
        SecureRandom repeating = new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) 7); }
        };
        ExportReauthenticationService collision = new ExportReauthenticationService(users, securityState, encoder, jwt, jdbc, clock, repeating);
        ReauthenticationGrant first = transaction().execute(status -> collision.issue(access, PASSWORD, EXPORTAR));
        var before = rows(actor);
        clock.set(clock.instant().plusSeconds(1));
        assertThatThrownBy(() -> transaction().execute(status -> collision.issue(access, PASSWORD, EXPORTAR)))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(rows(actor)).isEqualTo(before);
        consumeWithEffect(actor, access, Objects.requireNonNull(first).token(), EXPORTAR);
        assertThat(effectCount(actor)).isEqualTo(1L);
    }

    @Test
    void malformedOrNoncanonicalOpaqueTokensAndMissingPurposeCannotMutateTheGrant() {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        String alias = grant.token().substring(0, 42) + alphabet.charAt(alphabet.indexOf(grant.token().charAt(42)) + 1);
        assertThat(Base64.getUrlDecoder().decode(alias)).containsExactly(Base64.getUrlDecoder().decode(grant.token()));
        var before = rows(actor);
        for (String malformed : Arrays.asList(null, "", grant.token() + "=", alias)) {
            badRequest(() -> consumeWithEffect(actor, access, malformed, EXPORTAR), "REAUTENTICACION_INVALIDA");
        }
        badRequest(() -> consumeWithEffect(actor, access, grant.token(), null), "REAUTENTICACION_INVALIDA");
        badRequest(() -> reauthentication.issue(access, PASSWORD, null), "REAUTENTICACION_INVALIDA");
        assertThat(rows(actor)).isEqualTo(before);
        assertThat(effectCount(actor)).isZero();
        consumeWithEffect(actor, access, grant.token(), EXPORTAR);
    }

    @Test
    void aTamperedJwtCannotIssueOrConsumeEvenWithTheCorrectPasswordAndOpaqueToken() {
        Identity actor = seed();
        String access = accessToken(actor);
        ReauthenticationGrant grant = reauthentication.issue(access, PASSWORD, EXPORTAR);
        int signatureStart = access.lastIndexOf('.') + 1;
        String tampered = access.substring(0, signatureStart) + (access.charAt(signatureStart) == 'A' ? 'B' : 'A')
                + access.substring(signatureStart + 1);
        var before = rows(actor);
        assertThatThrownBy(() -> reauthentication.issue(tampered, PASSWORD, EXPORTAR)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> consumeWithEffect(actor, tampered, grant.token(), EXPORTAR)).isInstanceOf(UnauthorizedException.class);
        assertThat(rows(actor)).isEqualTo(before);
        assertThat(effectCount(actor)).isZero();
    }

    private Identity seed() {
        long workshop = Objects.requireNonNull(owner().queryForObject(
                "INSERT INTO talleres(nombre,activo) VALUES (?,true) RETURNING id", Long.class, "Reauth " + UUID.randomUUID()));
        return seedInWorkshop(workshop);
    }

    private Identity seedInWorkshop(long workshop) {
        String email = UUID.randomUUID() + "@reauth.synthetic.invalid";
        long user = Objects.requireNonNull(owner().queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES ('Reauth actor',?,?,?, ?,true,true,0) RETURNING id
                """, Long.class, email, encoder.encode(PASSWORD), UserRole.ADMIN.name(), workshop));
        return new Identity(user, workshop);
    }

    private String accessToken(Identity actor) {
        var user = users.findSessionByIdAndTallerId(actor.userId(), actor.workshopId()).orElseThrow();
        return jwt.generateToken(new AuthenticatedUserPrincipal(user), actor.workshopId());
    }

    private void consumeWithEffect(Identity actor, String access, String token, ExportReauthenticationPurpose purpose) {
        transaction().executeWithoutResult(status -> {
            reauthentication.consume(access, token, purpose);
            insertEffect(actor);
        });
    }

    private void insertEffect(Identity actor) {
        jdbc.update("INSERT INTO reauth_effects_fixture(id,user_id) VALUES (?,?)", UUID.randomUUID(), actor.userId());
    }

    private long effectCount(Identity actor) {
        return Objects.requireNonNull(owner().queryForObject("SELECT count(*) FROM reauth_effects_fixture WHERE user_id = ?",
                Long.class, actor.userId()));
    }

    private TransactionTemplate transaction() {
        TransactionTemplate result = new TransactionTemplate(transactionManager);
        result.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        result.setTimeout(20);
        return result;
    }

    private StoredGrant grantFor(String token) {
        return owner().queryForObject("SELECT * FROM cuenta_reautenticaciones WHERE token_hash = ?", (row, index) ->
                new StoredGrant(row.getString("token_hash"), row.getLong("user_id"), row.getLong("taller_id"),
                        row.getLong("token_version"), row.getString("session_hash"), row.getString("proposito"),
                        row.getTimestamp("creada_en").toInstant(), row.getTimestamp("expira_en").toInstant(),
                        row.getTimestamp("usada_en") == null ? null : row.getTimestamp("usada_en").toInstant()), sha(token));
    }

    private static List<String> rows(Identity actor) {
        return owner().queryForList("SELECT row_to_json(r)::text || ':' || xmin::text FROM cuenta_reautenticaciones r WHERE user_id = ? ORDER BY token_hash",
                String.class, actor.userId());
    }

    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static void badRequest(ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BadRequestException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code));
    }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static void assertWaitingForLock(int pid, Future<?> worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (owner().queryForList("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", String.class, pid).contains("Lock")) {
                assertThat(worker.isDone()).isFalse();
                return;
            }
            assertThat(worker.isDone()).as("competing consumer must wait for the first transaction").isFalse();
            try { Thread.sleep(20); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
        throw new AssertionError("PostgreSQL did not observe the competing consumption waiting for a row lock");
    }

    private record Identity(long userId, long workshopId) {}
    private record StoredGrant(String tokenHash, long userId, long workshopId, long tokenVersion, String sessionHash,
                               String purpose, Instant createdAt, Instant expiresAt, Instant usedAt) {}
    private static final class SimulatedEffectFailure extends RuntimeException {}

    private enum ActorMutation {
        USER(AccessDeniedException.class), UNVERIFIED(AccessDeniedException.class),
        INACTIVE_USER(UnauthorizedException.class), INACTIVE_WORKSHOP(UnauthorizedException.class);
        final Class<? extends RuntimeException> failureType;
        ActorMutation(Class<? extends RuntimeException> failureType) { this.failureType = failureType; }
        void apply(JdbcTemplate fixture, Identity actor) {
            switch (this) {
                case USER -> fixture.update("UPDATE users SET role = 'USER' WHERE id = ?", actor.userId());
                case UNVERIFIED -> fixture.update("UPDATE users SET email_verificado = false WHERE id = ?", actor.userId());
                case INACTIVE_USER -> fixture.update("UPDATE users SET active = false WHERE id = ?", actor.userId());
                case INACTIVE_WORKSHOP -> fixture.update("UPDATE talleres SET activo = false WHERE id = ?", actor.workshopId());
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean @Primary MutableClock reauthenticationTestClock() { return new MutableClock(); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instant.get(); }
    }
}
