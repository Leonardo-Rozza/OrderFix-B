package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ReauthenticationGrant;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosurePurpose.*;
import static org.assertj.core.api.Assertions.*;

/** Disposable PostgreSQL 16, Flyway latest, real Spring transactions, JWT and BCrypt; no external providers. */
@SpringBootTest(properties={"spring.config.import=","spring.config.additional-location=",
        "spring.config.location=optional:classpath:/application.properties","exports.jobs.enabled=false",
        "mail.enabled=false","mercadopago.enabled=false","mercadopago.checkout-enabled=false","photos.private.enabled=false",
        "ordenfix.legal.registration-consent.enabled=false","ordenfix.legal.registration-enforcement.enabled=false",
        "ordenfix.legal.account-read.enabled=false","ordenfix.legal.account-acceptance.enabled=false",
        "ordenfix.legal.public-documents.enabled=false","ordenfix.legal.public-requirements.enabled=false",
        "ordenfix.legal.aggregate-context.enabled=false","ordenfix.legal.editorial-context.enabled=false",
        "ordenfix.legal.import-context.enabled=false","ordenfix.legal.dry-run-context.enabled=false",
        "ordenfix.legal.public-document-read-context.enabled=false","ordenfix.legal.public-requirements-context.enabled=false"})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WorkshopClosureReauthenticationIT {
    private static final String PASSWORD="closure-reauth-synthetic-123";
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("closure_reauth_synthetic").withUsername("closure").withPassword("closure");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl); r.add("spring.datasource.username",PG::getUsername);
        r.add("spring.datasource.password",PG::getPassword); r.add("spring.datasource.driver-class-name",PG::getDriverClassName);
        r.add("spring.flyway.enabled",()->"true"); r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
    }
    @TestConfiguration static class Configuration {
        @Bean @Primary AdjustableClock closureReauthenticationClock() { return new AdjustableClock(); }
    }
    static final class AdjustableClock extends Clock {
        volatile Instant now=Instant.now();
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @Autowired WorkshopClosureReauthenticationService service;
    @Autowired WorkshopClosureStore store;
    @Autowired WorkshopClosureGate gate;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired JwtUtils jwt;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired AdjustableClock clock;

    @BeforeEach void fixtureClock() {
        clock.now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS closure_auth_effects_fixture(id uuid PRIMARY KEY,actor bigint NOT NULL)");
    }

    @Test void persistsOnlyHashesAndTheExactOperationAndGenerationBinding() {
        var actor=actor(); String access=access(actor); UUID operation=UUID.randomUUID();
        var grant=issue(access,operation);
        assertThat(grant.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(grant.expiresAt()).isEqualTo(clock.now.plusSeconds(300));
        var row=jdbc.queryForMap("SELECT * FROM cuenta_cierre_confirmaciones WHERE token_hash=?",sha(grant.token()));
        assertThat(row.get("user_id")).isEqualTo(actor.user());
        assertThat(row.get("taller_id")).isEqualTo(actor.workshop());
        assertThat(row.get("token_version")).isEqualTo(0L);
        assertThat(row.get("session_hash")).isEqualTo(sha(access));
        assertThat(row.get("proposito")).isEqualTo("CERRAR");
        assertThat(row.get("operacion_id")).isEqualTo(operation);
        assertThat(row.get("cierre_referencia")).isEqualTo(operation);
        assertThat(row.get("cierre_version")).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT row_to_json(c)::text FROM cuenta_cierre_confirmaciones c WHERE token_hash=?",String.class,sha(grant.token())))
                .doesNotContain(access,grant.token(),PASSWORD);
        assertThat(grant.toString()).doesNotContain(grant.token());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='34' AND success",Long.class)).isEqualTo(1);
    }

    @Test void wrongPasswordPreservesThePreviousUnusedGrantWithoutDml() {
        var actor=actor(); String access=access(actor); UUID operation=UUID.randomUUID(); issue(access,operation);
        var before=rows(actor);
        assertThatThrownBy(()->service.issue(access,"incorrecta",CERRAR,operation,operation))
                .isInstanceOfSatisfying(BadRequestException.class,e->assertThat(e.getCode()).isEqualTo("PASSWORD_ACTUAL_INVALIDA"));
        assertThat(rows(actor)).isEqualTo(before);
    }

    @Test void replacingAnUnusedGrantRevokesItAndUsedGrantsDoNotBlockANewIssuance() {
        var actor=actor(); String access=access(actor); UUID first=UUID.randomUUID(), second=UUID.randomUUID();
        var old=issue(access,first); var fresh=issue(access,second);
        badProof(()->consume(access,old.token(),first));
        consume(access,fresh.token(),second);
        var used=rows(actor);
        var third=issue(access,UUID.randomUUID());
        assertThat(rows(actor)).hasSize(2).containsAll(used);
        assertThat(third.token()).isNotEqualTo(fresh.token());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_confirmaciones WHERE user_id=? AND usada_en IS NOT NULL",Long.class,actor.user())).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(Binding.class)
    void unrelatedSessionOperationReferencePurposeOrTenantCannotSpendAGrant(Binding binding) {
        var actor=actor(); String original=access(actor); UUID operation=UUID.randomUUID(); var grant=issue(original,operation);
        var before=rows(actor);
        String presented=switch(binding) { case SESSION->access(actor); case TENANT->access(actor()); default->original; };
        UUID request=binding==Binding.OPERATION?UUID.randomUUID():operation;
        UUID reference=binding==Binding.REFERENCE?UUID.randomUUID():request;
        var purpose=binding==Binding.PURPOSE?RESTAURAR:CERRAR;
        badProof(()->transaction().execute(s->service.consume(presented,grant.token(),purpose,request,reference)));
        assertThat(rows(actor)).isEqualTo(before);
    }

    @Test void revokingTheEpochInvalidatesAnUnspentProofAndOldJwtWithoutDeletingEvidence() {
        var actor=actor(); String access=access(actor); UUID operation=UUID.randomUUID(); var grant=issue(access,operation);
        jdbc.update("UPDATE users SET token_version=token_version+1 WHERE id=?",actor.user());
        var before=rows(actor);
        assertThatThrownBy(()->consume(access,grant.token(),operation)).isInstanceOf(UnauthorizedException.class);
        badProof(()->consume(access(actor),grant.token(),operation));
        assertThat(rows(actor)).isEqualTo(before);
    }

    @Test void failureOfTheCallerEffectRollsBackConsumptionAndAllowsAnHonestRetry() {
        var actor=actor(); String access=access(actor); UUID operation=UUID.randomUUID(); var grant=issue(access,operation);
        var before=rows(actor);
        assertThatThrownBy(()->transaction().executeWithoutResult(s->{
            service.consume(access,grant.token(),CERRAR,operation,operation); effect(actor); throw new EffectFailure();
        })).isExactlyInstanceOf(EffectFailure.class);
        assertThat(rows(actor)).isEqualTo(before); assertThat(effects(actor)).isZero();
        transaction().executeWithoutResult(s->{service.consume(access,grant.token(),CERRAR,operation,operation);effect(actor);});
        assertThat(effects(actor)).isEqualTo(1);
        badProof(()->consume(access,grant.token(),operation));
    }

    @Test void proofExpiryDuringTheCallerEffectRollsBackConsumptionAndTheEffect() {
        var actor=actor(); String access=access(actor); UUID operation=UUID.randomUUID(); var grant=issue(access,operation);
        var before=rows(actor); Instant previous=clock.now;
        try {
            assertThatThrownBy(()->transaction().executeWithoutResult(s->{
                var authority=service.consume(access,grant.token(),CERRAR,operation,operation);
                assertThat(authority.expiresAt()).isEqualTo(grant.expiresAt());
                effect(actor); clock.now=grant.expiresAt(); service.requireLive(authority);
            })).isInstanceOf(UnauthorizedException.class);
        } finally { clock.now=previous; }
        assertThat(rows(actor)).isEqualTo(before); assertThat(effects(actor)).isZero();
    }

    @Test void issuanceCommitsIndependentlyOfTheCallersUncommittedTransaction() {
        var actor=actor(); String access=access(actor); UUID operation=UUID.randomUUID();
        var grant=transaction().execute(s->{ effect(actor); var issued=issue(access,operation); s.setRollbackOnly(); return issued; });
        assertThat(grant).isNotNull(); assertThat(effects(actor)).isZero(); assertThat(rows(actor)).hasSize(1);
        consume(access,grant.token(),operation);
    }

    @Test void realClosureAndRestorationRequireFreshEpochsAndDistinctPurposeProofs() {
        var actor=actor(); String initial=access(actor); UUID reference=UUID.randomUUID(); var closing=issue(initial,reference);
        transaction().executeWithoutResult(s->{
            gate.lockExclusive(service.routeWorkshop(initial));
            var authority=service.consume(initial,closing.token(),CERRAR,reference,reference);
            store.restrict(authority.tallerId(),authority.userId(),reference); service.requireLive(authority);
        });
        assertThatThrownBy(()->transaction().execute(s->service.authorize(initial))).isInstanceOf(UnauthorizedException.class);
        String restricted=access(actor);
        var fresh=transaction().execute(s->service.authorize(restricted));
        assertThat(fresh.state()).isEqualTo("RESTRINGIDO"); assertThat(fresh.tokenVersion()).isEqualTo(1);
        assertThat(fresh.closureReference()).isEqualTo(reference);
        UUID restoration=UUID.randomUUID();
        var restoring=service.issue(restricted,PASSWORD,RESTAURAR,restoration,reference);
        transaction().executeWithoutResult(s->{
            gate.lockExclusive(service.routeWorkshop(restricted));
            var authority=service.consume(restricted,restoring.token(),RESTAURAR,restoration,reference);
            store.restore(authority.tallerId(),authority.userId(),reference); service.requireLive(authority);
        });
        assertThatThrownBy(()->transaction().execute(s->service.authorize(restricted))).isInstanceOf(UnauthorizedException.class);
        var restored=transaction().execute(s->service.authorize(access(actor)));
        assertThat(restored.state()).isEqualTo("ABIERTO"); assertThat(restored.tokenVersion()).isEqualTo(2);
        assertThat(restored.closureVersion()).isEqualTo(2); assertThat(restored.closureReference()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_confirmaciones WHERE user_id=? AND usada_en IS NOT NULL",Long.class,actor.user())).isEqualTo(2);
    }

    @Test void concurrentConsumersCommitExactlyOneEffect() throws Exception {
        var actor=actor(); String access=access(actor); UUID operation=UUID.randomUUID(); var grant=issue(access,operation);
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var outcomes=List.of(workers.submit(()->race(start,actor,access,grant.token(),operation)),
                    workers.submit(()->race(start,actor,access,grant.token(),operation)));
            start.countDown();
            assertThat(List.of(outcomes.get(0).get(15,TimeUnit.SECONDS),outcomes.get(1).get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThat(effects(actor)).isEqualTo(1);
    }

    @Test void mandatoryAndReadCommittedBoundariesAreEnforcedByTheRealProxy() {
        var actor=actor(); String access=access(actor);
        assertThatThrownBy(()->service.authorize(access)).isInstanceOf(IllegalTransactionStateException.class);
        var readOnly=transaction(); readOnly.setReadOnly(true);
        assertThatThrownBy(()->readOnly.execute(s->service.authorize(access))).isInstanceOf(IllegalStateException.class);
        var repeatable=transaction(); repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(()->repeatable.execute(s->service.authorize(access))).isInstanceOf(IllegalStateException.class);
        assertThat(rows(actor)).isEmpty();
    }

    private boolean race(CountDownLatch start,Actor actor,String access,String proof,UUID operation) throws Exception {
        assertThat(start.await(5,TimeUnit.SECONDS)).isTrue();
        try {
            transaction().executeWithoutResult(s->{service.consume(access,proof,CERRAR,operation,operation);effect(actor);});
            return true;
        } catch(BadRequestException expected) {
            assertThat(expected.getCode()).isEqualTo("REAUTENTICACION_INVALIDA"); return false;
        }
    }
    private ReauthenticationGrant issue(String access,UUID operation) { return service.issue(access,PASSWORD,CERRAR,operation,operation); }
    private WorkshopClosureReauthenticationService.Authority consume(String access,String proof,UUID operation) {
        return transaction().execute(s->service.consume(access,proof,CERRAR,operation,operation));
    }
    private Actor actor() {
        long workshop=Objects.requireNonNull(jdbc.queryForObject("INSERT INTO talleres(nombre,activo) VALUES ('Closure fixture',true) RETURNING id",Long.class));
        long user=Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES ('Closure fixture',?,?,'ADMIN',?,true,true,0) RETURNING id
                """,Long.class,UUID.randomUUID()+"@closure.synthetic.invalid",passwords.encode(PASSWORD),workshop));
        return new Actor(user,workshop);
    }
    private String access(Actor actor) {
        var user=users.findSessionByIdAndTallerId(actor.user(),actor.workshop()).orElseThrow();
        return jwt.generateToken(new AuthenticatedUserPrincipal(user,clock.instant()),actor.workshop());
    }
    private TransactionTemplate transaction() {
        var tx=new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15); return tx;
    }
    private void effect(Actor actor) { jdbc.update("INSERT INTO closure_auth_effects_fixture(id,actor) VALUES (?,?)",UUID.randomUUID(),actor.user()); }
    private long effects(Actor actor) { return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM closure_auth_effects_fixture WHERE actor=?",Long.class,actor.user())); }
    private List<String> rows(Actor actor) {
        return jdbc.queryForList("SELECT row_to_json(c)::text||':'||xmin::text FROM cuenta_cierre_confirmaciones c WHERE user_id=? ORDER BY token_hash",String.class,actor.user());
    }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static void badProof(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BadRequestException.class,e->assertThat(e.getCode()).isEqualTo("REAUTENTICACION_INVALIDA"));
    }
    private record Actor(long user,long workshop) { }
    private enum Binding { SESSION, OPERATION, REFERENCE, PURPOSE, TENANT }
    private static final class EffectFailure extends RuntimeException { }
}
