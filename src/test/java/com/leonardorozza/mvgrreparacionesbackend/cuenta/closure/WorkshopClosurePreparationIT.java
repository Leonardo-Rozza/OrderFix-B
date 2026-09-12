package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.ClosurePreparationException.Code.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.ClosureRenewalAssessment.Result.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose.DESCARGAR_EXPORTACION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Real PostgreSQL/JPA/JWT preparation; a spy only observes authorization or injects an infrastructure failure. */
@SpringBootTest(properties = {
        "exports.jobs.enabled=false", "spring.config.import=", "spring.config.additional-location=",
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
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkshopClosurePreparationIT {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_closure_preparation").withUsername("ordenfix").withPassword("ordenfix");
    private static final String PASSWORD = "closure-preparation-synthetic-password";
    private static final String PRIVATE_DIAGNOSTIC = "SYNTHETIC_PRIVATE_SQL_PROVIDER_DIAGNOSTIC";
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired WorkshopClosurePreparationService service;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtUtils jwt;
    @MockitoSpyBean ExportReauthenticationService authorization;
    private ExportReauthenticationService authorizationTarget;
    private Actor own;
    private String access;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.datasource.driver-class-name", PG::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @BeforeEach void prepare() {
        // Configure the spy behind Spring AOP; production calls still enter the real MANDATORY proxy.
        authorizationTarget = AopTestUtils.getUltimateTargetObject(authorization);
        reset(authorizationTarget);
        assertThat(manager).isInstanceOf(JpaTransactionManager.class);
        own = actor();
        access = token(own);
    }

    @AfterEach void clearContext() { TenantContext.clear(); }

    @Test void ownCountsIgnoreAnotherTenantAndPreparationDoesNoDmlOrProofConsumption() throws Exception {
        Actor other = actor();
        user(own.workshop(), "USER", true, true);
        user(own.workshop(), "USER", true, true);
        user(own.workshop(), "USER", false, true);
        user(other.workshop(), "USER", true, true);
        photo(own, "AUTORIZADA", true);
        photo(own, "EXPIRADA", false);
        photo(own, "ELIMINADA", false);
        photo(other, "FALLIDA", false);
        export(own, "READY");
        export(other, "QUEUED");
        String proof = authorization.issue(access, PASSWORD, DESCARGAR_EXPORTACION).token();
        Map<String, String> before = databaseFingerprint();
        // Caller-controlled tenant context cannot redirect the verified JWT's preparation.
        TenantContext.setTallerId(other.workshop());
        Instant started = Instant.now();

        WorkshopClosurePreparation result = service.prepare(access);

        assertThat(result.userId()).isEqualTo(own.user());
        assertThat(result.tallerId()).isEqualTo(own.workshop());
        assertThat(result.tokenVersion()).isZero();
        assertThat(result.tallerName()).isEqualTo(own.marker());
        assertThat(result.workforce()).isEqualTo(new WorkshopClosurePreparation.Workforce(2, 1));
        assertThat(result.resources()).isEqualTo(new WorkshopClosurePreparation.Resources(3, 1, 1, 0, 1));
        assertThat(result.renewal()).isEqualTo(NO_LOCAL_EVIDENCE);
        assertThat(result.observedAt()).isBetween(started.minusNanos(999), Instant.now());
        assertThat(result.observedAt().getNano() % 1000).isZero();
        assertThat(result.policyVersion()).isEqualTo("ordenfix-cierre/1");
        assertThat(databaseFingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT usada_en IS NULL FROM cuenta_reautenticaciones WHERE user_id=?", Boolean.class, own.user())).isTrue();
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(serialized).doesNotContain(PASSWORD, access, proof, other.marker(), "sessionHash", "reauthToken", "reversibleUntil", "deletionExpectedBy");
        assertThat(result.toString()).isEqualTo("WorkshopClosurePreparation[redacted]");
    }

    @Test void queuedExportsCountAsPendingWhileTerminalRowsRemainUntouched() {
        export(own, "QUEUED");
        export(own, "EXPIRED");
        Map<String, String> before = databaseFingerprint();

        assertThat(service.prepare(access).resources()).isEqualTo(new WorkshopClosurePreparation.Resources(0, 0, 0, 1, 0));
        assertThat(databaseFingerprint()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "UNVERIFIED", "STALE", "INACTIVE_USER", "INACTIVE_WORKSHOP"})
    void realAuthorizationRejectsDisallowedOrRevokedAccounts(String reason) {
        switch (reason) {
            case "USER" -> {
                jdbc.update("UPDATE users SET role='USER' WHERE id=?", own.user());
                access = token(own);
            }
            case "UNVERIFIED" -> jdbc.update("UPDATE users SET email_verificado=false WHERE id=?", own.user());
            case "STALE" -> jdbc.update("UPDATE users SET token_version=token_version+1 WHERE id=?", own.user());
            case "INACTIVE_USER" -> jdbc.update("UPDATE users SET active=false WHERE id=?", own.user());
            case "INACTIVE_WORKSHOP" -> jdbc.update("UPDATE talleres SET activo=false WHERE id=?", own.workshop());
            default -> throw new AssertionError(reason);
        }
        Map<String, String> before = databaseFingerprint();

        rejected(() -> service.prepare(access), reason.equals("USER") || reason.equals("UNVERIFIED") ? FORBIDDEN : SESSION_INVALID);
        assertThat(databaseFingerprint()).isEqualTo(before);
    }

    @Test void malformedAccessTokenFailsClosedWithoutSourceChanges() {
        Map<String, String> before = databaseFingerprint();
        rejected(() -> service.prepare("synthetic.invalid.signature"), SESSION_INVALID);
        assertThat(databaseFingerprint()).isEqualTo(before);
    }

    @Test void repeatableReadExcludesAnEmployeeAndProviderStateCommittedAfterInitialAuthorization() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            var actor = invocation.callRealMethod();
            if (calls.incrementAndGet() == 1) {
                assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
                int currentPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                try (var connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
                    connection.setAutoCommit(false);
                    try (var query = connection.createStatement(); var row = query.executeQuery("SELECT pg_backend_pid()")) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getInt(1)).isNotEqualTo(currentPid);
                    }
                    String marker = "Late-" + UUID.randomUUID().toString().substring(0, 8);
                    try (var insert = connection.prepareStatement("""
                            INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                            VALUES(?,?,?,'USER',?,true,true,0)
                            """)) {
                        insert.setQueryTimeout(5);
                        insert.setString(1, marker); insert.setString(2, marker + "@synthetic.invalid");
                        insert.setString(3, encoder.encode(PASSWORD)); insert.setLong(4, own.workshop());
                        assertThat(insert.executeUpdate()).isEqualTo(1);
                    }
                    try (var update = connection.prepareStatement("UPDATE suscripciones SET mp_status='creating' WHERE id=?")) {
                        update.setQueryTimeout(5); update.setLong(1, own.subscription());
                        assertThat(update.executeUpdate()).isEqualTo(1);
                    }
                    connection.commit();
                } catch (SQLException failure) { throw new AssertionError(failure); }
            }
            return actor;
        }).when(authorizationTarget).authorize(access);

        WorkshopClosurePreparation result = service.prepare(access);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(result.workforce().activeEmployees()).isZero();
        assertThat(result.renewal()).isEqualTo(NO_LOCAL_EVIDENCE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE taller_id=? AND role='USER'", Long.class, own.workshop())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT mp_status FROM suscripciones WHERE id=?", String.class, own.subscription())).isEqualTo("creating");
    }

    @Test void requiresNewDoesNotObserveOrCommitTheCallersPendingEmployeeInsert() {
        Map<String, String> before = databaseFingerprint();
        new TransactionTemplate(manager).executeWithoutResult(outer -> {
            user(own.workshop(), "USER", true, true);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE taller_id=? AND role='USER'", Long.class, own.workshop())).isEqualTo(1);
            assertThat(service.prepare(access).workforce().activeEmployees()).isZero();
            outer.setRollbackOnly();
        });
        assertThat(databaseFingerprint()).isEqualTo(before);
    }

    @Test void moreThanOneThousandProviderLinksFailsInsteadOfReturningATruncatedAssessment() {
        jdbc.update("""
                INSERT INTO subscription_provider_links(suscripcion_id,provider,external_reference,idempotency_key,status,is_current,created_at,updated_at)
                SELECT ?, 'MERCADO_PAGO', 'cap-ref-' || ? || '-' || n, 'cap-key-' || ? || '-' || n,
                       'canceled',false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM generate_series(1,1001) n
                """, own.subscription(), own.subscription(), own.subscription());
        Map<String, String> before = databaseFingerprint();

        rejected(() -> service.prepare(access), CAPACITY_EXCEEDED);
        assertThat(databaseFingerprint()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({
            "FREE_CREATING,UNCERTAIN", "CANCELLED_ALIAS,PROVIDER_COORDINATION_REQUIRED",
            "HISTORIC_AUTHORIZED,UNCERTAIN", "HISTORIC_CANCELED,PROVIDER_COORDINATION_REQUIRED",
            "PRO_WITH_ONLY_CANCELED_HISTORY,UNCERTAIN",
            "CONTRADICTORY_IDS,UNCERTAIN", "CONTRADICTORY_STATES,UNCERTAIN", "MISSING_CURRENT_PROJECTION,UNCERTAIN"
    })
    void sqlMappingPreservesUnresolvedHistoricalAndContradictoryCommercialEvidence(String scenario, ClosureRenewalAssessment.Result expected) throws Exception {
        String external = "private-subscription-" + own.subscription();
        switch (scenario) {
            case "FREE_CREATING" -> jdbc.update("UPDATE suscripciones SET mp_status='  CrEaTiNg  ' WHERE id=?", own.subscription());
            case "CANCELLED_ALIAS" -> {
                jdbc.update("UPDATE suscripciones SET mp_status=' cancelled ',mp_preapproval_id=? WHERE id=?", external, own.subscription());
                link(own, true, "CANCELED", external);
            }
            case "HISTORIC_AUTHORIZED" -> link(own, false, "authorized", external);
            case "HISTORIC_CANCELED" -> link(own, false, "cancelled", external);
            case "PRO_WITH_ONLY_CANCELED_HISTORY" -> {
                jdbc.update("UPDATE suscripciones SET plan='PRO' WHERE id=?", own.subscription());
                link(own, false, "canceled", external);
            }
            case "MISSING_CURRENT_PROJECTION" -> link(own, true, "authorized", external);
            case "CONTRADICTORY_IDS" -> {
                jdbc.update("UPDATE suscripciones SET mp_status='authorized',mp_preapproval_id=? WHERE id=?", external, own.subscription());
                link(own, true, "authorized", external + "-other");
            }
            case "CONTRADICTORY_STATES" -> {
                jdbc.update("UPDATE suscripciones SET mp_status='authorized',mp_preapproval_id=? WHERE id=?", external, own.subscription());
                link(own, true, "canceled", external);
            }
            default -> throw new AssertionError(scenario);
        }
        Map<String, String> before = databaseFingerprint();

        WorkshopClosurePreparation result = service.prepare(access);

        assertThat(result.renewal()).isEqualTo(expected);
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(result))
                .doesNotContain(external, "private-provider-reference", "private-provider-key", "private-checkout");
        assertThat(databaseFingerprint()).isEqualTo(before);
    }

    @Test void failureAtFinalRealAuthorizationIsSanitizedAndLeavesEverySourceAndProofUntouched() {
        authorization.issue(access, PASSWORD, DESCARGAR_EXPORTACION);
        Map<String, String> before = databaseFingerprint();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            var actor = invocation.callRealMethod();
            if (calls.incrementAndGet() == 2) throw new IllegalStateException(PRIVATE_DIAGNOSTIC + access,
                    new SQLException(PRIVATE_DIAGNOSTIC + PASSWORD));
            return actor;
        }).when(authorizationTarget).authorize(access);

        rejected(() -> service.prepare(access), UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(databaseFingerprint()).isEqualTo(before);
    }

    private Actor actor() {
        String marker = "Closure-" + UUID.randomUUID().toString().substring(0, 8);
        long workshop = jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES(?) RETURNING id", Long.class, marker);
        long user = user(workshop, "ADMIN", true, true);
        long subscription = jdbc.queryForObject("INSERT INTO suscripciones(taller_id,plan,estado,fecha_inicio) VALUES(?,'FREE','TRIAL',CURRENT_DATE) RETURNING id", Long.class, workshop);
        long customer = jdbc.queryForObject("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES(?,'Fixture','fixture-phone',?) RETURNING id", Long.class, marker, workshop);
        long equipment = jdbc.queryForObject("INSERT INTO equipos(marca,modelo,cliente_id,taller_id) VALUES('Fixture','Phone',?,?) RETURNING id", Long.class, customer, workshop);
        long repair = jdbc.queryForObject("INSERT INTO reparaciones(descripcion_problema,estado,equipo_id,taller_id,credenciales_cifrado_version) VALUES('Fixture','INGRESADO',?,?,1) RETURNING id", Long.class, equipment, workshop);
        return new Actor(user, workshop, subscription, repair, marker);
    }

    private long user(long workshop, String role, boolean active, boolean verified) {
        String marker = "ClosureUser-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES(?,?,?,?,?,?,?,0) RETURNING id
                """, Long.class, marker, marker + "@synthetic.invalid", encoder.encode(PASSWORD), role, workshop, active, verified);
    }

    private String token(Actor actor) {
        return jwt.generateToken(new AuthenticatedUserPrincipal(users.findSessionByIdAndTallerId(actor.user(), actor.workshop()).orElseThrow()), actor.workshop());
    }

    private void photo(Actor actor, String state, boolean leased) {
        // This disposable PG16 fixture tests persisted counts, not the legal upload protocol.
        // Only the initial-intention/attestation triggers are bypassed; CHECKs and all FKs remain enabled.
        new TransactionTemplate(manager).executeWithoutResult(transaction -> {
            jdbc.execute("ALTER TABLE reparacion_fotos_privadas DISABLE TRIGGER trg_foto_privada_insert");
            jdbc.execute("ALTER TABLE reparacion_fotos_privadas DISABLE TRIGGER ct_foto_privada_completa");
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO reparacion_fotos_privadas(id,reparacion_id,reparacion_original_id,taller_id,user_id,rol_wire,nombre,
                      mime_type,bytes,sha256,momento,estado,confirmado_en,expira_en,retener_hasta,object_key,
                      scope_hmac,key_hmac,fingerprint_hmac,hmac_key_version,lease_id,lease_hasta)
                    VALUES(?,?,?,?,?,'ADMIN','Synthetic photo','image/png',12,repeat('a',64),'INGRESO',?,
                      CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '5 minutes',CURRENT_TIMESTAMP+INTERVAL '1 day',?,
                      repeat('b',64),?,repeat('c',64),1,?,CASE WHEN ? THEN CURRENT_TIMESTAMP+INTERVAL '1 minute' ELSE NULL END)
                    """, id, actor.repair(), actor.repair(), actor.workshop(), actor.user(), state, "ordenfix-private/" + id,
                    id.toString().replace("-", "") + id.toString().replace("-", ""), leased ? UUID.randomUUID() : null, leased);
            jdbc.execute("ALTER TABLE reparacion_fotos_privadas ENABLE TRIGGER trg_foto_privada_insert");
            jdbc.execute("ALTER TABLE reparacion_fotos_privadas ENABLE TRIGGER ct_foto_privada_completa");
        });
    }

    private void export(Actor actor, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cuenta_exportaciones(id,user_id,taller_id,token_version,session_hash,request_hash,estado,
                  creada_en,actualizada_en,expira_en,capturada_en,archive_cipher)
                VALUES(?,?,?,0,repeat('a',64),?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 hour',
                  CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,CASE WHEN ? THEN decode(repeat('01',32),'hex') ELSE NULL END)
                """, id, actor.user(), actor.workshop(), id.toString().replace("-", "") + id.toString().replace("-", ""), state,
                state.equals("READY"), state.equals("READY"));
    }

    private void link(Actor actor, boolean current, String status, String external) {
        String suffix = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO subscription_provider_links(suscripcion_id,provider,external_subscription_id,external_reference,
                  idempotency_key,checkout_url,status,is_current,created_at,updated_at)
                VALUES(?,'MERCADO_PAGO',?,?,?,'https://synthetic.invalid/private-checkout',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, actor.subscription(), external, "private-provider-reference-" + suffix, "private-provider-key-" + suffix, status, current);
    }

    /** Hash source values instead of exposing credentials/provider data in assertion output; xmin detects identical-value DML. */
    private Map<String, String> databaseFingerprint() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            if (!table.matches("[a-z0-9_]+")) throw new AssertionError("Unexpected fixture table name");
            snapshot.put(table, jdbc.queryForObject("SELECT count(*)::text || ':' || coalesce(md5(string_agg(md5(to_jsonb(r)::text) || ':' || xmin::text, ',' ORDER BY md5(to_jsonb(r)::text) || ':' || xmin::text)), '') FROM public." + table + " r", String.class));
        }
        return snapshot;
    }

    private void rejected(Runnable operation, ClosurePreparationException.Code code) {
        assertThatThrownBy(operation::run).isExactlyInstanceOf(ClosurePreparationException.class)
                .hasMessage("No se pudo preparar el cierre del taller.").hasNoCause()
                .satisfies(failure -> {
                    assertThat(((ClosurePreparationException) failure).code()).isEqualTo(code);
                    assertThat(failure.getSuppressed()).isEmpty();
                    assertThat(failure.toString()).doesNotContain(PASSWORD, access, PRIVATE_DIAGNOSTIC);
                });
    }

    private record Actor(long user, long workshop, long subscription, long repair, String marker) { }
}
