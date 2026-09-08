package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.*;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.*;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.*;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/** Test-only caller of the writer. This is not the registration/replay service planned for L3. */
final class LegalRegistrationWriterITSupport {
    static final String ROLE = "ordenfix_registration_writer";
    static final String HMAC = Base64.getEncoder().encodeToString("11111111111111111111111111111111".getBytes(StandardCharsets.US_ASCII));
    static final String AES = LegalAcceptanceMetadataITSupport.AES_SECRET;
    static final Duration RETENTION = Duration.ofDays(30);
    static final Clock APPLICATION_CLOCK = Clock.fixed(Instant.parse("2026-09-09T01:30:00Z"), ZoneOffset.UTC);
    static final LocalDateTime AUDIT_AT = LocalDateTime.of(2026, 9, 8, 22, 30, 0, 123_456_000);
    static final List<String> TABLES = List.of("talleres", "suscripciones", "users", "legal_requisito_agregados",
            "legal_requisito_agregado_scopes", "legal_aceptacion_lotes", "legal_aceptaciones",
            "legal_aceptacion_documentos", "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados",
            "legal_idempotencia_resultados", "legal_idempotencia_sin_actos", "legal_idempotencia_sin_actos_referencias", "auth_tokens");
    final DataSource source;
    final JdbcTemplate owner;
    final LegalRestrictedRegistrationRoleFixture.Credentials credentials;

    LegalRegistrationWriterITSupport(PostgreSQLContainer postgres) {
        source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        owner = new JdbcTemplate(source);
        LegalRestrictedRegistrationRoleFixture.requireSafeEphemeralDatabase(owner);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("29").load().migrate();
        credentials = LegalRestrictedRegistrationRoleFixture.provision(owner, ROLE, "registration-writer-fixture");
    }

    void reset(Path directory, Class<?> anchor, int count) throws Exception {
        LegalRestrictedRegistrationRoleFixture.requireSafeEphemeralDatabase(owner);
        if (count < 1 || count > 251) throw new IllegalArgumentException("Fixture capacity");
        owner.execute("TRUNCATE legal_requisito_agregados,legal_publicaciones,legal_documento_reemplazo_lotes,talleres RESTART IDENTITY CASCADE");
        var release = LegalManifestPersistenceITSupport.copyRelease(directory, anchor, "registration-writer-source", (path, manifest) -> {
            manifest.withArray("documents").forEach(document -> ((ObjectNode) document).put("effectiveAt", "2020-01-01T00:00:00-03:00"));
            for (int index = 2; index <= count; index++) {
                var requirement = manifest.withArray("requirements").addObject();
                requirement.put("key", "registration-writer-optional-" + index);
                requirement.put("version", "1.0.0"); requirement.put("context", "REGISTRO");
                requirement.putArray("roles").add("ADMIN_TITULAR"); requirement.put("actType", "LECTURA");
                String statement = "Leí el aviso opcional de registro " + index + ".";
                requirement.put("statement", statement); requirement.put("statementSha256", sha(statement));
                requirement.putArray("documents").add("terminos");
                requirement.put("required", false); requirement.put("requiresReacceptance", true);
            }
        });
        UUID publication = LegalV28AggregateITSupport.importRelease(source, release);
        LegalManifestPersistenceITSupport.promoteToReady(owner, publication);
    }

    /** Models the separate public GET before a registration request; rolls back its aggregate. */
    Request request(Registration registration, boolean optional) throws Exception {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
        var deadline = new LegalPublicRequirementsDeadline(Duration.ofSeconds(15));
        return Objects.requireNonNull(transaction.execute(status -> {
            var time = LegalAcceptanceProtocolFeasibilityITSupport.sharedBoundary(owner);
            var aggregate = store(owner).materialize(scopes(), time);
            var current = new LegalPublicRequirementsReader(owner).read(aggregate, time, deadline);
            var acceptances = current.projection().requirements().stream().filter(r -> optional || r.required())
                    .map(r -> new Acceptance(r.versionId(), r.actType(), r.statementSha256(), r.documents().stream()
                            .map(d -> new Document(d.versionId(), d.sha256())).toList(), true)).toList();
            status.setRollbackOnly();
            return new Request(LegalAcceptanceCommandValidator.registration(registration,
                    current.requiredSetRevision(), acceptances), current);
        }));
    }

    Request request() throws Exception { return request(registration(UUID.randomUUID() + "@test.invalid"), true); }
    static Registration registration(String email) { return new Registration("Taller de prueba", "1100000000", "Admin de prueba", email, "contraseña-ñ-123"); }
    static LegalRequestMetadata metadata() { return LegalAcceptanceMetadataITSupport.capture("OrdenFix registration fixture"); }

    Harness harness() { return harness(new Probe(), null); }
    Harness harness(Probe probe, LegalAcceptanceMetadataCodec codec) {
        var environment = new MockEnvironment()
                .withProperty(LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", credentials.jdbcUrl())
                .withProperty(LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX + "username", credentials.username())
                .withProperty(LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX + "password", credentials.password())
                .withProperty(LegalAcceptanceKeyConfiguration.IDEMPOTENCY_PREFIX + "keyring.1", HMAC)
                .withProperty(LegalAcceptanceKeyConfiguration.IDEMPOTENCY_PREFIX + "active-write-version", "1")
                .withProperty(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "keyring.7", AES)
                .withProperty(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "active-write-version", "7")
                .withProperty(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "retention", RETENTION.toString());
        var keys = LegalAcceptanceKeyConfiguration.from(environment);
        var configuration = new LegalRegistrationDatabaseConfiguration();
        HikariDataSource pool = configuration.legalRegistrationPool(environment, keys);
        var bounded = LegalPrivateRequirementsDataSource.registration(instrument(pool, probe));
        try {
            var jdbc = new JdbcTemplate(bounded);
            var budgets = configuration.legalRegistrationBudgets();
            var transaction = configuration.legalRegistrationTransactionTemplate(configuration.legalRegistrationTransactionManager(bounded), budgets);
            var boundary = new LegalRegistrationTransactionBoundary(jdbc, bounded, transaction, budgets,
                    new LegalRegistrationSchemaVerifier(jdbc, "public"), new LegalRegistrationPrivilegeVerifier(jdbc, ROLE, "public"));
            var results = new LegalIdempotencyResultStore(jdbc);
            var coordinator = new LegalIdempotencyCoordinator(jdbc, new LegalV29AcceptanceSchemaVerifier(jdbc, "public"), keys.keyring(), results);
            var writer = new LegalRegistrationWriter(jdbc, codec == null ? keys.codec() : codec, keys.retentionPolicy(), results);
            return new Harness(pool, bounded, jdbc, boundary, coordinator, writer, probe);
        } catch (RuntimeException | Error failure) {
            bounded.close(); pool.close(); throw failure;
        }
    }

    Map<String, List<String>> rows() {
        LegalRestrictedRegistrationRoleFixture.requireSafeEphemeralDatabase(owner);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : TABLES) result.put(table, owner.queryForList(
                "SELECT to_jsonb(t)::text || '|xmin=' || xmin::text FROM public." + table + " t ORDER BY 1", String.class));
        return Map.copyOf(result);
    }

    static LegalRequiredSetAggregateStore store(JdbcTemplate jdbc) {
        return new LegalRequiredSetAggregateStore(jdbc, new LegalRequiredSetAggregateRevisionCalculator(),
                new LegalRequiredSetAggregateProvenanceCalculator());
    }
    static LegalApplicableScopeSet scopes() {
        return new LegalApplicableScopeResolver().resolve(PerfilAgregadoLegal.REGISTRATION, LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR);
    }
    static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    record Request(LegalAcceptanceCommand command, LegalPublicRegistrationRequirements current) { }

    record Harness(HikariDataSource pool, LegalPrivateRequirementsDataSource bounded, JdbcTemplate jdbc,
                   LegalRegistrationTransactionBoundary boundary, LegalIdempotencyCoordinator coordinator,
                   LegalRegistrationWriter writer, Probe probe) implements AutoCloseable {
        LegalRegistrationReceipt write(Request request, String key, LegalTransactionCompletionState<LegalRegistrationReceipt> completion) {
            return write(request, key, completion, UnaryOperator.identity());
        }
        LegalRegistrationReceipt write(Request request, String key, LegalTransactionCompletionState<LegalRegistrationReceipt> completion,
                                       UnaryOperator<LegalRequiredSetAggregateReceipt> mutate) {
            return bounded.withinDeadline(deadline -> {
                var prepared = new LegalRegistrationPreparation(new BCryptPasswordEncoder(), APPLICATION_CLOCK, () -> AUDIT_AT, 14)
                        .prepare(request.command(), deadline);
                return boundary.execute(completion, (status, inner) -> {
                    assertThat(inner).isSameAs(deadline);
                    assertThat(jdbc.queryForObject("SELECT current_user || ':' || session_user", String.class)).isEqualTo(ROLE + ":" + ROLE);
                    var reservation = coordinator.reserve(request.command(), key, deadline::remainingMillis);
                    reservation.requireNew();
                    var time = boundary.enterEditorialShared(reservation, deadline);
                    var aggregate = store(jdbc).materialize(scopes(), time);
                    var selected = new LegalRegistrationSelection().select(request.command(), request.current());
                    var receipt = writer.write(reservation, prepared, mutate.apply(aggregate), selected, metadata());
                    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
                    deadline.check();
                    return receipt;
                });
            });
        }
        @Override public void close() {
            bounded.close(); pool.close();
            assertThat(pool.isClosed()).isTrue();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        }
    }

    static final class Probe {
        int borrows, commits, rollbacks;
        String afterInsert;
        boolean omitEncrypted;
        final AtomicBoolean injected = new AtomicBoolean();
        final List<String> executed = new ArrayList<>();
        long businessInserts() { return executed.stream().filter(sql -> List.of("talleres", "suscripciones", "users").stream()
                .anyMatch(table -> isInsert(sql, table))).count(); }
    }
    private static DataSource instrument(DataSource source, Probe probe) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                probe.borrows++;
                Connection connection = source.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit")) probe.commits++;
                    if (method.getName().equals("rollback")) probe.rollbacks++;
                    Object result = invoke(connection, method, args);
                    if (!(result instanceof Statement statement)) return result;
                    String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                    Class<?> contract = result instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{contract}, (wrapped, operation, values) -> {
                        if (!operation.getName().startsWith("execute")) return invoke(statement, operation, values);
                        String executed = sql != null ? sql : values != null && values.length > 0 && values[0] instanceof String text ? text : "";
                        String normalized = LegalJdbcMetricsSupport.normalizeSql(executed).toLowerCase(Locale.ROOT);
                        probe.executed.add(normalized);
                        if (probe.omitEncrypted && isInsert(normalized, "legal_aceptacion_metadatos_cifrados")) {
                            assertThat(operation.getName()).isEqualTo("executeUpdate");
                            probe.injected.set(true); return 1;
                        }
                        Object answer = invoke(statement, operation, values);
                        if (probe.afterInsert != null && isInsert(normalized, probe.afterInsert) && probe.injected.compareAndSet(false, true)) {
                            try (Statement failure = connection.createStatement()) { failure.execute("SELECT 1/0"); }
                            throw new AssertionError("Expected real PostgreSQL division failure");
                        }
                        return answer;
                    });
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException("Fixed registration credential");
            }
        };
    }
    static boolean isInsert(String sql, String table) {
        return List.of("insert into public." + table, "insert into " + table).stream()
                .anyMatch(prefix -> sql.startsWith(prefix + " ") || sql.startsWith(prefix + "("));
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
