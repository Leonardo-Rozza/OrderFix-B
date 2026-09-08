package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.*;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.*;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationWriterITSupport.Request;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationWriterITSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real service harness; the L2 fixture supplies publication/account observations, never orchestration. */
final class LegalRegistrationServiceITSupport {
    static final long EXPIRED_NANOS = Duration.ofSeconds(30).toNanos();
    final LegalRegistrationWriterITSupport writerFixture;
    final JdbcTemplate owner;
    final DataSource source;
    private Path directory;
    private Class<?> anchor;
    private Path sourceManifest;
    private LegalEditorialITFixture.ImportedRelease publication;

    LegalRegistrationServiceITSupport(PostgreSQLContainer postgres) {
        writerFixture = new LegalRegistrationWriterITSupport(postgres);
        owner = writerFixture.owner; source = writerFixture.source;
    }

    void reset(Path directory, Class<?> anchor) throws Exception { reset(directory, anchor, 1); }
    void reset(Path directory, Class<?> anchor, int count) throws Exception {
        this.directory = directory; this.anchor = anchor;
        writerFixture.reset(directory, anchor, count);
        sourceManifest = directory.resolve("registration-writer-source/publication-manifest.json");
        var validated = new LegalManifestValidator().validate(sourceManifest.toRealPath());
        assertThat(validated.passed()).as("issues=%s", validated.issues()).isTrue();
        publication = new LegalEditorialITFixture.ImportedRelease(validated.value().orElseThrow(),
                owner.queryForObject("SELECT id FROM public.legal_publicaciones", UUID.class));
    }

    Request request() throws Exception { return writerFixture.request(); }
    Request request(String email, boolean optional) throws Exception {
        return writerFixture.request(registration(email), optional);
    }
    Map<String, List<String>> rows() { return writerFixture.rows(); }

    Harness harness() { return harness(new Probe()); }
    Harness harness(Probe probe) {
        var credentials = writerFixture.credentials;
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
        var pool = configuration.legalRegistrationPool(environment, keys);
        var metrics = LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO);
        var bounded = LegalPrivateRequirementsDataSource.registration(instrument(metrics.dataSource(), probe), probe::nanoTime);
        try {
            var jdbc = new JdbcTemplate(bounded);
            var budgets = configuration.legalRegistrationBudgets();
            var transaction = configuration.legalRegistrationTransactionTemplate(
                    configuration.legalRegistrationTransactionManager(bounded), budgets);
            var boundary = new LegalRegistrationTransactionBoundary(jdbc, bounded, transaction, budgets,
                    new LegalRegistrationSchemaVerifier(jdbc, "public"), new LegalRegistrationPrivilegeVerifier(jdbc, ROLE, "public"));
            var results = new LegalIdempotencyResultStore(jdbc);
            var writer = new LegalRegistrationWriter(jdbc, keys.codec(), keys.retentionPolicy(), results);
            var preparation = new LegalRegistrationPreparation(new BCryptPasswordEncoder() {
                @Override protected String encodeNonNullPassword(String password) {
                    probe.hashes.incrementAndGet();
                    String encoded = super.encodeNonNullPassword(password);
                    probe.afterHash.run();
                    return encoded;
                }
            }, APPLICATION_CLOCK, () -> AUDIT_AT, 14);
            var service = new LegalRegistrationService(jdbc, bounded, boundary, new LegalApplicableScopeResolver(),
                    store(jdbc), new LegalPublicRequirementsReader(jdbc), preparation, writer,
                    new LegalV29AcceptanceSchemaVerifier(jdbc, "public"), keys);
            return new Harness(service, jdbc, bounded, pool, transaction, metrics, probe);
        } catch (RuntimeException | Error failure) {
            bounded.close(); pool.close(); throw failure;
        }
    }

    /** Makes the later unique-email race reach users, instead of the V28 aggregate INSERT conflict. */
    void materializeCommittedAggregate() {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> store(owner).materialize(scopes(),
                LegalAcceptanceProtocolFeasibilityITSupport.sharedBoundary(owner)));
    }

    void replaceRegistration() throws Exception {
        var apply = LegalManifestPersistenceITSupport.applyHarness(source, LegalDatabaseBudgets.production());
        var editorial = new LegalEditorialITFixture(directory, anchor, owner,
                LegalManifestPersistenceITSupport.harness(source, LegalDatabaseBudgets.production()), apply);
        ObjectNode original = (ObjectNode) new ObjectMapper().readTree(Files.readAllBytes(sourceManifest));
        var target = editorial.importedDraft("registration-service-replacement", (path, manifest) -> {
            manifest.removeAll(); manifest.setAll(original.deepCopy()); manifest.put("publicationId", "registration-service-replacement");
            for (var item : manifest.withArray("requirements")) {
                if (!"REGISTRO".equals(item.path("context").asText()) || !item.path("required").asBoolean()) continue;
                var requirement = (ObjectNode) item;
                String statement = "Acepto la nueva versión del contrato de registro.";
                requirement.put("version", "2.0.0"); requirement.put("statement", statement);
                requirement.put("statementSha256", sha(statement)); requirement.put("requiresReacceptance", true);
            }
        });
        var result = apply.service().applyReplace(target.release(),
                editorial.replacementPlan(publication, target, "registration-service-replace").plan());
        assertThat(result.persisted()).as("issues=%s", result.issues()).isTrue();
        publication = target;
        sourceManifest = directory.resolve("registration-service-replacement/publication-manifest.json");
    }

    void retireRegistration() throws Exception {
        var apply = LegalManifestPersistenceITSupport.applyHarness(source, LegalDatabaseBudgets.production());
        String external = publication.release().plan().manifest().publicationId();
        String fingerprint = apply.readinessCore().observeState(external,
                owner.queryForObject("SELECT statement_timestamp()", OffsetDateTime.class).toInstant()).editorialStateFingerprint();
        var plan = new ObjectMapper().createObjectNode();
        plan.put("schemaVersion", 1); plan.put("operationId", UUID.randomUUID().toString()); plan.put("operationType", "RETIRE");
        plan.put("expectedCurrentPublicationId", external); plan.put("targetPublicationId", external);
        plan.put("expectedCurrentManifestSha256", publication.release().plan().manifestSha256());
        plan.put("targetManifestSha256", publication.release().plan().manifestSha256());
        plan.put("expectedEditorialStateFingerprint", fingerprint);
        for (String field : List.of("documentAdditions", "documentReuses", "documentReplacementBatches", "documentRetirements",
                "requirementAdditions", "requirementReuses", "requirementReplacements", "requirementRetirements")) plan.putArray(field);
        owner.query("""
                SELECT v.id,v.afirmacion_sha256 FROM public.legal_requisito_versiones v
                  JOIN public.legal_requisito_lineas line ON line.id=v.requisito_linea_id
                 WHERE line.contexto='REGISTRO' AND v.estado='VIGENTE'
                """, row -> {
            var retired = plan.withArray("requirementRetirements").addObject();
            retired.put("requirementVersionId", row.getObject("id", UUID.class).toString());
            retired.put("statementSha256", row.getString("afirmacion_sha256")); retired.put("context", "REGISTRO");
            retired.putArray("audiences").add("ADMIN_TITULAR"); retired.put("reason", "Retiro posterior al registro del fixture.");
        });
        plan.put("expectedReadinessAfter", "NOT_READY"); plan.put("acknowledgeFailClosedGap", true);
        Path path = directory.resolve("registration-service-retire/editorial-plan.json");
        Files.createDirectories(path.getParent()); Files.write(path, new ObjectMapper().writeValueAsBytes(plan));
        var validated = new LegalEditorialPlanValidator().validate(path.toRealPath());
        assertThat(validated.passed()).as("issues=%s", validated.issues()).isTrue();
        var result = apply.service().applyRetire(publication.release(), validated.value().orElseThrow());
        assertThat(result.persisted()).as("issues=%s", result.issues()).isTrue();
    }

    void corrupt(Runnable mutation) { LegalManifestPersistenceITSupport.withReplicaRole(owner, mutation); }

    static String key() { return UUID.randomUUID().toString(); }
    static Request withAcceptances(Request original, String revision, List<Acceptance> values) {
        return new Request(LegalAcceptanceCommandValidator.registration(original.command().registration(), revision, values), original.current());
    }
    static Request withPassword(Request original, String password) {
        var r = original.command().registration();
        return new Request(LegalAcceptanceCommandValidator.registration(new Registration(r.nombreTaller(), r.telefonoTaller(),
                r.nombreAdmin(), r.email(), password), original.command().requiredSetRevision(), original.command().acceptances()), original.current());
    }

    record Harness(LegalRegistrationService service, JdbcTemplate jdbc, LegalPrivateRequirementsDataSource bounded,
                   HikariDataSource pool, TransactionTemplate transaction, LegalJdbcMetricsSupport metrics, Probe probe)
            implements AutoCloseable {
        LegalRegistrationReceipt register(Request request, String key) { return register(request, key, metadata()); }
        LegalRegistrationReceipt register(Request request, String key, LegalRequestMetadata metadata) {
            var command = request.command();
            return service.register(command.registration(), key, command.requiredSetRevision(), command.acceptances(), metadata);
        }
        @Override public void close() {
            bounded.close(); pool.close(); assertThat(pool.isClosed()).isTrue();
            assertThat(TransactionSynchronizationManager.getResource(bounded)).isNull();
        }
    }

    enum Fault { NONE, BEFORE_COMMIT, BEFORE_COMMIT_DEADLINE, COMMIT_BEFORE_SERVER, COMMIT_SQL_ACK,
        COMMIT_RUNTIME_ACK, AFTER_COMMIT, AFTER_COMMIT_DEADLINE, CLOSE_SQL, CLOSE_RUNTIME, CLOSE_DEADLINE, ROLLBACK_ACK }
    @FunctionalInterface interface SqlHook { void accept(String sql) throws SQLException; }
    static final class Probe {
        final AtomicLong offsetNanos = new AtomicLong();
        final AtomicInteger borrows = new AtomicInteger(), commits = new AtomicInteger(), rollbacks = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger(), hashes = new AtomicInteger(), pid = new AtomicInteger();
        final AtomicBoolean injected = new AtomicBoolean();
        final List<String> sql = new CopyOnWriteArrayList<>();
        volatile Fault fault = Fault.NONE;
        volatile String failAfterInsert;
        volatile boolean omitEncrypted;
        volatile Runnable afterHash = () -> { };
        volatile SqlHook beforeSql = value -> { }, afterSql = value -> { };
        long nanoTime() { return System.nanoTime() + offsetNanos.get(); }
        void expire() { offsetNanos.set(EXPIRED_NANOS); }
        long inserts(String table) { return sql.stream().filter(value -> isInsert(value, table)).count(); }
    }

    private static DataSource instrument(DataSource source, Probe probe) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = source.getConnection(); probe.borrows.incrementAndGet();
                try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
                    assertThat(row.next()).isTrue(); probe.pid.set(row.getInt(1));
                }
                var synchronizedOnce = new AtomicBoolean();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (TransactionSynchronizationManager.isSynchronizationActive() && synchronizedOnce.compareAndSet(false, true)) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) {
                                if (probe.fault == Fault.BEFORE_COMMIT) throw new IllegalStateException("Synthetic precommit failure");
                            }
                            @Override public void beforeCompletion() { if (probe.fault == Fault.BEFORE_COMMIT_DEADLINE) probe.expire(); }
                            @Override public void afterCommit() {
                                if (probe.fault == Fault.AFTER_COMMIT) throw new IllegalStateException("Synthetic delivery failure");
                            }
                        });
                    }
                    if (method.getName().equals("commit")) {
                        probe.commits.incrementAndGet();
                        if (probe.fault == Fault.COMMIT_BEFORE_SERVER) {
                            connection.close(); throw new SQLException("Synthetic connection loss before COMMIT", "08006");
                        }
                        Object result = invoke(connection, method, args);
                        if (probe.fault == Fault.COMMIT_SQL_ACK) throw new SQLException("Synthetic lost COMMIT acknowledgement", "08006");
                        if (probe.fault == Fault.COMMIT_RUNTIME_ACK) throw new IllegalStateException("Synthetic lost COMMIT acknowledgement");
                        if (probe.fault == Fault.AFTER_COMMIT_DEADLINE) probe.expire();
                        return result;
                    }
                    boolean rollback = method.getName().equals("rollback") && method.getParameterCount() == 0;
                    if (rollback) probe.rollbacks.incrementAndGet();
                    Object result = invoke(connection, method, args);
                    if (rollback && probe.fault == Fault.ROLLBACK_ACK) throw new SQLException("Synthetic lost ROLLBACK acknowledgement", "08006");
                    if (method.getName().equals("close")) {
                        probe.closes.incrementAndGet();
                        if (probe.fault == Fault.CLOSE_SQL) throw new SQLException("Synthetic close failure", "08006");
                        if (probe.fault == Fault.CLOSE_RUNTIME) throw new IllegalStateException("Synthetic close failure");
                        if (probe.fault == Fault.CLOSE_DEADLINE) probe.expire();
                    }
                    if (!(result instanceof Statement statement)) return result;
                    String sql = args != null && args.length > 0 && args[0] instanceof String value ? value : null;
                    Class<?> contract = result instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{contract}, (wrapped, operation, values) -> {
                        if (operation.getDeclaringClass() == Object.class) return objectMethod(wrapped, operation, values);
                        if (!operation.getName().startsWith("execute")) return invoke(statement, operation, values);
                        String executed = sql != null ? sql : values != null && values.length > 0 && values[0] instanceof String value ? value : "";
                        String normalized = LegalJdbcMetricsSupport.normalizeSql(executed).toLowerCase(Locale.ROOT);
                        probe.sql.add(normalized); probe.beforeSql.accept(normalized);
                        if (probe.omitEncrypted && isInsert(normalized, "legal_aceptacion_metadatos_cifrados")) {
                            assertThat(operation.getName()).isEqualTo("executeUpdate"); probe.injected.set(true); return 1;
                        }
                        Object answer = invoke(statement, operation, values);
                        if (probe.failAfterInsert != null && isInsert(normalized, probe.failAfterInsert) && probe.injected.compareAndSet(false, true)) {
                            try (Statement failure = connection.createStatement()) { failure.execute("SELECT 1/0"); }
                            throw new AssertionError("Expected PostgreSQL division failure");
                        }
                        probe.afterSql.accept(normalized);
                        return answer;
                    });
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException("Fixed registration credential");
            }
        };
    }

    static void await(CountDownLatch latch) throws SQLException {
        try { if (!latch.await(12, TimeUnit.SECONDS)) throw new AssertionError("Fixture barrier did not advance"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new SQLException("Fixture interrupted", interrupted); }
    }
    static void awaitObserved(BooleanSupplier observation) {
        long until = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (System.nanoTime() < until) {
            if (observation.getAsBoolean()) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("Expected PostgreSQL lock observation was not reached");
    }
    static void exclusiveEditorial(JdbcTemplate jdbc) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0]; case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "RegistrationServiceObservation"; default -> throw new IllegalStateException("Unexpected Object method");
        };
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
