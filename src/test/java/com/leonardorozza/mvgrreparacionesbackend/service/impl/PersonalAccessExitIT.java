package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.auth0.jwt.JWT;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.AuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoAuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.AuthTokenRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.usuario.ActualizarUsuarioRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP security, JPA, PostgreSQL 16, BCrypt and JWT; no SMTP or subscription dependency. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PersonalAccessExitIT extends IntegrationTestBase {

    private static final String PATH = "/api/cuenta/baja-acceso";
    private static final String PASSWORD = "personal-access-fixture-123";
    private static final String NEW_PASSWORD = "personal-access-updated-456";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_personal_access_exit")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    @Autowired private AccountAccessExitService exit;
    @Autowired private CuentaService cuenta;
    @Autowired private UsuarioService empleados;
    @Autowired private UserRepository users;
    @Autowired private AuthTokenRepository tokens;
    @Autowired private PasswordEncoder encoder;
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

    @Test
    void ownExitRevokesEverySessionAndAdminReactivationDoesNotRestoreOldTokens() throws Exception {
        Identity admin = seed(UserRole.ADMIN);
        Identity employee = seedInWorkshop(UserRole.USER, admin.workshopId());
        Identity colleague = seedInWorkshop(UserRole.USER, admin.workshopId());
        Identity foreign = seed(UserRole.USER);
        String adminToken = login(admin.email(), PASSWORD);
        String firstToken = login(employee.email(), PASSWORD);
        String secondToken = login(employee.email(), PASSWORD);
        String colleagueToken = login(colleague.email(), PASSWORD);
        String foreignToken = login(foreign.email(), PASSWORD);
        var untouched = otherRows(employee.userId());
        assertThat(JWT.decode(firstToken).getId()).isNotEqualTo(JWT.decode(secondToken).getId());
        assertThat(owner().queryForObject("SELECT count(*) FROM suscripciones WHERE taller_id = ?",
                Long.class, employee.workshopId())).isZero();
        assertThat(user(employee).getEmailVerificado()).isFalse();
        authGet("/api/perfil", firstToken).andExpect(status().isOk());

        authPost(PATH, firstToken, confirmation())
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().string(""));

        assertThat(user(employee).getActive()).isFalse();
        assertThat(user(employee).getTokenVersion()).isEqualTo(1L);
        assertThat(user(employee).getEmailVerificado()).isFalse();
        assertThat(encoder.matches(PASSWORD, user(employee).getPassword())).isTrue();
        assertThat(otherRows(employee.userId())).isEqualTo(untouched);
        authGet("/api/perfil", firstToken).andExpect(status().isForbidden());
        authGet("/api/perfil", secondToken).andExpect(status().isForbidden());
        authPost(PATH, secondToken, confirmation()).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(json(Map.of("email", employee.email(), "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        authGet("/api/perfil", adminToken).andExpect(status().isOk());
        authGet("/api/perfil", colleagueToken).andExpect(status().isOk());
        authGet("/api/perfil", foreignToken).andExpect(status().isOk());

        authPatch("/api/usuarios/" + employee.userId(), adminToken, json(Map.of("active", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));

        assertThat(user(employee).getTokenVersion()).isEqualTo(1L);
        authGet("/api/perfil", firstToken).andExpect(status().isForbidden());
        authGet("/api/perfil", secondToken).andExpect(status().isForbidden());
        String renewed = login(employee.email(), PASSWORD);
        assertThat(JWT.decode(renewed).getClaim("tokenVersion").asLong()).isEqualTo(1L);
        authGet("/api/perfil", renewed).andExpect(status().isOk());
        assertThat(otherRows(employee.userId())).isEqualTo(untouched);
        assertThat(emails.ultimoCuerpo(employee.email())).isNull();
    }

    @Test
    void wrongCurrentPasswordRollsBackEveryAccountAndTokenRow() throws Exception {
        Identity employee = seed(UserRole.USER);
        seedToken(employee, TipoAuthToken.RESET_PASSWORD);
        String token = login(employee.email(), PASSWORD);
        var before = durableRows();

        authPost(PATH, token, json(Map.of("passwordActual", "wrong-password", "confirmado", true)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value("PASSWORD_ACTUAL_INVALIDA"));

        assertThat(durableRows()).isEqualTo(before);
        authGet("/api/perfil", token).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"passwordActual\":\"personal-access-fixture-123\",\"confirmado\":false}",
            "{\"passwordActual\":\"personal-access-fixture-123\"}",
            "{\"passwordActual\":\"personal-access-fixture-123\",\"confirmado\":\"true\"}",
            "{\"passwordActual\":123,\"confirmado\":true}",
            "{\"passwordActual\":\"personal-access-fixture-123\",\"confirmado\":true,\"userId\":1}",
            "{\"passwordActual\":\"personal-access-fixture-123\",\"confirmado\":true,\"tallerId\":1}",
            "{\"passwordActual\":\"personal-access-fixture-123\",\"confirmado\":true,\"role\":\"ADMIN\"}"
    })
    void invalidConfirmationOrCallerSelectedTargetNeverMutates(String body) throws Exception {
        Identity employee = seed(UserRole.USER);
        String token = login(employee.email(), PASSWORD);
        var before = durableRows();

        authPost(PATH, token, body).andExpect(status().isBadRequest());

        assertThat(durableRows()).isEqualTo(before);
        authGet("/api/perfil", token).andExpect(status().isOk());
    }

    @Test
    void ownerAndAnonymousCannotUseTheEmployeeExit() throws Exception {
        Identity admin = seed(UserRole.ADMIN);
        String token = login(admin.email(), PASSWORD);
        var before = durableRows();

        authPost(PATH, token, confirmation()).andExpect(status().isForbidden());
        mvc.perform(post(PATH).contentType(APPLICATION_JSON).content(confirmation()))
                .andExpect(status().isForbidden());

        assertThat(durableRows()).isEqualTo(before);
        authGet("/api/perfil", token).andExpect(status().isOk());
    }

    @Test
    void passwordResetCommittedAfterAuthenticationMakesTheExitSnapshotInvalid() {
        Identity employee = seed(UserRole.USER);
        AuthenticatedUserPrincipal stalePrincipal = principal(employee);
        String reset = seedToken(employee, TipoAuthToken.RESET_PASSWORD);
        cuenta.resetPassword(reset, NEW_PASSWORD);
        var before = durableRows();

        assertThatThrownBy(() -> exit.deactivate(stalePrincipal, NEW_PASSWORD))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(durableRows()).isEqualTo(before);
        assertThat(user(employee).getActive()).isTrue();
        assertThat(user(employee).getTokenVersion()).isEqualTo(1L);
    }

    @ParameterizedTest
    @EnumSource(LateMutation.class)
    void concurrentStaleMutationWaitsForExitAndCannotRestoreActiveStateOrRevokedTokens(LateMutation mutation)
            throws Exception {
        Identity admin = seed(UserRole.ADMIN);
        Identity employee = seedInWorkshop(UserRole.USER, admin.workshopId());
        AuthenticatedUserPrincipal employeePrincipal = principal(employee);
        AuthenticatedUserPrincipal adminPrincipal = principal(admin);
        String oldToken = login(employee.email(), PASSWORD);
        String link = mutation == LateMutation.ADMIN_UPDATE ? null : seedToken(employee, mutation.tokenType());
        var beforeTokens = tokenRows(employee);
        var untouched = otherRows(employee.userId());
        CountDownLatch snapshotReady = new CountDownLatch(1);
        CountDownLatch mutate = new CountDownLatch(1);
        AtomicInteger workerPid = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        Future<?> worker = executor.submit(() -> {
            authenticate(adminPrincipal);
            try {
                transaction().executeWithoutResult(status -> {
                    entityManager.createNativeQuery("SET LOCAL lock_timeout = '10s'").executeUpdate();
                    User stale = users.findSessionByIdAndTallerId(employee.userId(), employee.workshopId()).orElseThrow();
                    assertThat(stale.getActive()).isTrue();
                    assertThat(stale.getTokenVersion()).isZero();
                    if (link != null) {
                        AuthToken loaded = tokens.findByTokenHashAndTipo(sha(link), mutation.tokenType()).orElseThrow();
                        assertThat(loaded.getUser()).isSameAs(stale);
                    }
                    workerPid.set(((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()")
                            .getSingleResult()).intValue());
                    snapshotReady.countDown();
                    await(mutate);
                    switch (mutation) {
                        case RESET -> cuenta.resetPassword(link, NEW_PASSWORD);
                        case VERIFY -> cuenta.verificarEmail(link);
                        case ADMIN_UPDATE -> empleados.actualizar(employee.userId(), new ActualizarUsuarioRequestDTO());
                    }
                });
            } finally {
                SecurityContextHolder.clearContext();
                TenantContext.clear();
            }
        });
        try {
            assertThat(snapshotReady.await(10, TimeUnit.SECONDS)).isTrue();
            transaction().executeWithoutResult(status -> {
                exit.deactivate(employeePrincipal, PASSWORD);
                entityManager.flush();
                mutate.countDown();
                assertWaitingForLock(workerPid.get(), worker);
            });

            if (mutation == LateMutation.ADMIN_UPDATE) worker.get(15, TimeUnit.SECONDS);
            else assertThatThrownBy(() -> worker.get(15, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BadRequestException.class);
        } finally {
            mutate.countDown();
            worker.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        User current = user(employee);
        assertThat(current.getActive()).isFalse();
        assertThat(current.getTokenVersion()).isEqualTo(1L);
        assertThat(current.getEmailVerificado()).isFalse();
        assertThat(encoder.matches(PASSWORD, current.getPassword())).isTrue();
        assertThat(tokenRows(employee)).isEqualTo(beforeTokens);
        assertThat(otherRows(employee.userId())).isEqualTo(untouched);
        authGet("/api/perfil", oldToken).andExpect(status().isForbidden());
    }

    @Test
    void twoConcurrentExitsFromTheSameAuthenticatedSnapshotIncrementRevocationOnlyOnce() throws Exception {
        Identity employee = seed(UserRole.USER);
        AuthenticatedUserPrincipal snapshot = principal(employee);
        var untouched = otherRows(employee.userId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown(); await(start);
                try { exit.deactivate(snapshot, PASSWORD); return "EXITED"; }
                catch (UnauthorizedException rejected) { return "REJECTED"; }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(attempts.getFirst().get(15, TimeUnit.SECONDS), attempts.getLast().get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("EXITED", "REJECTED");
        } finally {
            start.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(user(employee).getActive()).isFalse();
        assertThat(user(employee).getTokenVersion()).isEqualTo(1L);
        assertThat(otherRows(employee.userId())).isEqualTo(untouched);
    }

    private String confirmation() throws Exception {
        return json(Map.of("passwordActual", PASSWORD, "confirmado", true));
    }

    private Identity seed(UserRole role) {
        long workshop = Objects.requireNonNull(owner().queryForObject(
                "INSERT INTO talleres(nombre,activo) VALUES (?,true) RETURNING id", Long.class,
                "Personal exit " + UUID.randomUUID()));
        return seedInWorkshop(role, workshop);
    }

    private Identity seedInWorkshop(UserRole role, long workshop) {
        String email = UUID.randomUUID() + "@personal-exit.synthetic.invalid";
        long user = Objects.requireNonNull(owner().queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES ('Personal exit actor',?,?,?,?,true,false,0) RETURNING id
                """, Long.class, email, encoder.encode(PASSWORD), role.name(), workshop));
        return new Identity(user, workshop, email);
    }

    private String seedToken(Identity identity, TipoAuthToken type) {
        String raw = UUID.randomUUID().toString();
        owner().update("""
                INSERT INTO auth_tokens(user_id,tipo,token_hash,expira_en,created_at)
                VALUES (?,?,?,CURRENT_TIMESTAMP + INTERVAL '1 hour',CURRENT_TIMESTAMP)
                """, identity.userId(), type.name(), sha(raw));
        return raw;
    }

    private User user(Identity identity) {
        return users.findSessionByIdAndTallerId(identity.userId(), identity.workshopId()).orElseThrow();
    }

    private AuthenticatedUserPrincipal principal(Identity identity) {
        return new AuthenticatedUserPrincipal(user(identity));
    }

    private static void authenticate(AuthenticatedUserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        TenantContext.setTallerId(principal.getTallerId());
    }

    private TransactionTemplate transaction() {
        TransactionTemplate result = new TransactionTemplate(transactionManager);
        result.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return result;
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static void assertWaitingForLock(int pid, Future<?> worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var waiting = owner().queryForList("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", String.class, pid);
            if (waiting.contains("Lock")) {
                assertThat(worker.isDone()).isFalse();
                return;
            }
            assertThat(worker.isDone()).as("stale mutation must wait for the exit row lock").isFalse();
            try { Thread.sleep(20); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
        throw new AssertionError("PostgreSQL did not observe the competing mutation waiting for the exit row lock");
    }

    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static List<String> durableRows() {
        return otherRows(-1L);
    }

    private static List<String> otherRows(long excludedUser) {
        return owner().queryForList("""
                SELECT 'user:' || id || ':' || row_to_json(u)::text || ':' || xmin::text AS snapshot FROM users u WHERE id <> ?
                UNION ALL SELECT 'workshop:' || id || ':' || row_to_json(t)::text || ':' || xmin::text FROM talleres t
                UNION ALL SELECT 'subscription:' || id || ':' || row_to_json(s)::text || ':' || xmin::text FROM suscripciones s
                UNION ALL SELECT 'auth-token:' || id || ':' || row_to_json(a)::text || ':' || xmin::text FROM auth_tokens a
                ORDER BY snapshot
                """, String.class, excludedUser);
    }

    private static List<String> tokenRows(Identity identity) {
        return owner().queryForList("SELECT row_to_json(a)::text || ':' || xmin::text FROM auth_tokens a WHERE user_id = ? ORDER BY id",
                String.class, identity.userId());
    }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    private record Identity(long userId, long workshopId, String email) {}

    private enum LateMutation {
        RESET, VERIFY, ADMIN_UPDATE;
        TipoAuthToken tokenType() { return this == RESET ? TipoAuthToken.RESET_PASSWORD : TipoAuthToken.VERIFICACION_EMAIL; }
    }
}
