package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.jdbc;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.materialize;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.transaction;
import static org.assertj.core.api.Assertions.assertThat;

/** Dedicated PostgreSQL fixture with exact request-role privileges and real publication/acceptance graphs. */
final class LegalAcceptanceServiceITSupport {
    static final String ROLE = "ordenfix_acceptance_service";
    static final String HMAC_SECRET = Base64.getEncoder().encodeToString("11111111111111111111111111111111".getBytes(StandardCharsets.US_ASCII));
    static final String AES_SECRET = Base64.getEncoder().encodeToString("33333333333333333333333333333333".getBytes(StandardCharsets.US_ASCII));
    static final Duration RETENTION = Duration.ofDays(30);
    static final String FIRST_REQUIREMENT_KEY = "acceptance-service-use-1";
    static final String SECOND_REQUIREMENT_KEY = "acceptance-service-use-2";
    static final List<String> LEGAL_TABLES = List.of("legal_requisito_agregados", "legal_requisito_agregado_scopes",
            "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados",
            "legal_idempotencia_sin_actos", "legal_idempotencia_sin_actos_referencias");
    static final List<String> DURABLE_TABLES = java.util.stream.Stream.concat(LEGAL_TABLES.stream(),
            java.util.stream.Stream.of("users", "talleres", "suscripciones")).toList();
    final DataSource dataSource;
    final JdbcTemplate owner;
    final LegalRestrictedAcceptanceRoleFixture.Credentials credentials;
    private LegalEditorialITFixture.ImportedRelease source;
    private Path sourceManifest;

    LegalAcceptanceServiceITSupport(PostgreSQLContainer postgres) {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        owner = new JdbcTemplate(dataSource);
        requireEphemeral();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("29").load().migrate();
        credentials = LegalRestrictedAcceptanceRoleFixture.provision(owner, ROLE, "acceptance-service-fixture-password");
    }

    void reset(Path directory, Class<?> anchor) throws Exception {
        reset(directory, anchor, 2);
    }

    void reset(Path directory, Class<?> anchor, int requirementCount) throws Exception {
        requireEphemeral();
        if (requirementCount < 1 || requirementCount > 128) throw new IllegalArgumentException("Capacidad del fixture");
        owner.execute("TRUNCATE legal_requisito_agregados,legal_publicaciones,legal_documento_reemplazo_lotes,talleres RESTART IDENTITY CASCADE");
        var release = LegalManifestPersistenceITSupport.copyRelease(directory, anchor, "acceptance-service-source", (path, manifest) -> {
            manifest.withArray("documents").forEach(document -> ((ObjectNode) document).put("effectiveAt", "2020-01-01T00:00:00-03:00"));
            for (int index = 1; index <= requirementCount; index++) {
                var requirement = manifest.withArray("requirements").addObject();
                requirement.put("key", "acceptance-service-use-" + index);
                requirement.put("version", "1.0.0"); requirement.put("context", "USO_CONTINUADO");
                requirement.putArray("roles").add("ADMIN_TITULAR").add("USER");
                requirement.put("actType", "ACEPTACION");
                String statement = "Confirmo el requisito de aceptación atómica " + index + ".";
                requirement.put("statement", statement); requirement.put("statementSha256", sha(statement));
                requirement.putArray("documents").add("terminos");
                requirement.put("required", index == 1); requirement.put("requiresReacceptance", true);
            }
        });
        UUID publication = LegalV28AggregateITSupport.importRelease(dataSource, release);
        LegalManifestPersistenceITSupport.promoteToReady(owner, publication);
        source = new LegalEditorialITFixture.ImportedRelease(release, publication);
        sourceManifest = directory.resolve("acceptance-service-source/publication-manifest.json");
    }

    Harness harness() { return harness(UnaryOperator.identity(), System::nanoTime); }

    Harness harness(UnaryOperator<DataSource> poolDecorator, LongSupplier clock) {
        return harness(poolDecorator, clock, RETENTION);
    }

    Harness harness(UnaryOperator<DataSource> poolDecorator, LongSupplier clock, Duration retention) {
        var environment = new MockEnvironment()
                .withProperty(LegalAcceptanceDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", credentials.jdbcUrl())
                .withProperty(LegalAcceptanceDatabaseConfiguration.PROPERTY_PREFIX + "username", credentials.username())
                .withProperty(LegalAcceptanceDatabaseConfiguration.PROPERTY_PREFIX + "password", credentials.password())
                .withProperty(LegalAcceptanceKeyConfiguration.IDEMPOTENCY_PREFIX + "keyring.1", HMAC_SECRET)
                .withProperty(LegalAcceptanceKeyConfiguration.IDEMPOTENCY_PREFIX + "active-write-version", "1")
                .withProperty(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "keyring.7", AES_SECRET)
                .withProperty(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "active-write-version", "7")
                .withProperty(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "retention", retention.toString());
        var keys = LegalAcceptanceKeyConfiguration.from(environment);
        var configuration = new LegalAcceptanceDatabaseConfiguration();
        HikariDataSource pool = configuration.legalAcceptancePool(environment, keys);
        var metrics = LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO);
        LegalPrivateRequirementsDataSource bounded = null;
        try {
            bounded = new LegalPrivateRequirementsDataSource(poolDecorator.apply(metrics.dataSource()), Duration.ofSeconds(15), clock);
            JdbcTemplate jdbc = configuration.legalAcceptanceJdbc(bounded);
            var budgets = configuration.legalAcceptanceBudgets();
            var transaction = configuration.legalAcceptanceTransactionTemplate(
                    configuration.legalAcceptanceTransactionManager(bounded), budgets);
            var schema = configuration.legalAcceptanceSchemaVerifier(jdbc);
            var privileges = configuration.legalAcceptancePrivilegeVerifier(jdbc, environment);
            var boundary = new LegalAcceptanceTransactionBoundary(jdbc, bounded, transaction, budgets, schema, privileges);
            var revision = new LegalRequiredSetAggregateRevisionCalculator();
            var provenance = new LegalRequiredSetAggregateProvenanceCalculator();
            var aggregateReplay = new LegalRequiredSetAggregateReplayVerifier(jdbc, revision, provenance);
            var aggregateStore = new LegalRequiredSetAggregateStore(jdbc, revision, provenance, aggregateReplay);
            var service = new LegalAcceptanceService(jdbc, boundary, new LegalApplicableScopeResolver(), aggregateStore,
                    new LegalPrivateRequirementsReader(jdbc), new LegalAcceptanceEvidenceReader(jdbc), schema, keys);
            return new Harness(service, jdbc, bounded, metrics, pool, transaction, keys);
        } catch (RuntimeException failure) {
            if (bounded != null) bounded.close();
            pool.close();
            throw failure;
        }
    }

    LegalActorSnapshot actor() { return actor(UserRole.USER); }

    LegalActorSnapshot actor(UserRole role) {
        requireEphemeral();
        String identity = UUID.randomUUID().toString();
        long workshop = Objects.requireNonNull(owner.queryForObject("INSERT INTO talleres(nombre) VALUES(?) RETURNING id",
                Long.class, "Acceptance service fixture " + identity));
        long user = Objects.requireNonNull(owner.queryForObject("""
                INSERT INTO users(username,password,email,role,taller_id,active,token_version)
                VALUES(?,'fixture-password-hash',?,?,?,true,0) RETURNING id
                """, Long.class, "accept-it-" + identity, identity + "@ordenfix.test", role.name(), workshop));
        return new LegalActorSnapshot(user, workshop, role, 0, true, true);
    }

    static AuthenticatedUserPrincipal principal(LegalActorSnapshot actor) {
        var user = User.builder().id(actor.userId()).email("fixture@ordenfix.test").password("fixture-password-hash")
                .active(actor.active()).role(actor.role()).tokenVersion(actor.tokenVersion())
                .taller(Taller.builder().id(actor.tallerId()).activo(actor.workshopActive()).build()).build();
        return new AuthenticatedUserPrincipal(user);
    }

    /** Calculates the real request in an owner transaction which never leaves a new aggregate behind. */
    LegalAcceptanceCommand command(LegalActorSnapshot actor) throws Exception {
        requireEphemeral();
        try (Connection connection = transaction(dataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            var aggregate = materialize(jdbc, PerfilAgregadoLegal.AUTHENTICATED_PENDING, actor.audience());
            List<Acceptance> acceptances = jdbc.query("""
                    SELECT v.id,line.tipo_acto,v.afirmacion_sha256
                      FROM legal_requisito_agregado_scopes scope
                      JOIN legal_requisito_conjunto_miembros member ON member.conjunto_id=scope.conjunto_id
                      JOIN legal_requisito_versiones v ON v.id=member.requisito_version_id
                      JOIN legal_requisito_lineas line ON line.id=v.requisito_linea_id
                     WHERE scope.agregado_id=? ORDER BY scope.scope_ordinal,member.manifest_ordinal
                    """, (row, ignored) -> {
                UUID id = row.getObject("id", UUID.class);
                List<Document> documents = jdbc.query("""
                        SELECT ref.documento_version_id,v.sha256 FROM legal_requisito_documentos ref
                          JOIN legal_documento_versiones v ON v.id=ref.documento_version_id
                         WHERE ref.requisito_version_id=? ORDER BY ref.documento_ordinal
                        """, (document, index) -> new Document(document.getObject("documento_version_id", UUID.class), document.getString("sha256")), id);
                return new Acceptance(id, TipoActoLegal.valueOf(row.getString("tipo_acto")), row.getString("afirmacion_sha256"), documents, true);
            }, aggregate.aggregateId());
            var command = LegalAcceptanceCommandValidator.authenticated(actor, aggregate.requiredSetRevision(), acceptances);
            connection.rollback();
            return command;
        }
    }

    Acceptance requirement(LegalAcceptanceCommand command, int number) {
        UUID id = owner.queryForObject("""
                SELECT v.id FROM legal_requisito_versiones v JOIN legal_requisito_lineas line ON line.id=v.requisito_linea_id
                 WHERE line.clave=? AND v.estado='VIGENTE'
                """, UUID.class, number == 1 ? FIRST_REQUIREMENT_KEY : SECOND_REQUIREMENT_KEY);
        return command.acceptances().stream().filter(value -> value.requisitoVersionId().equals(id)).findFirst().orElseThrow();
    }

    void replaceRequirement(Path directory, Class<?> anchor, int number, boolean required, boolean reacceptance) throws Exception {
        requireEphemeral();
        var apply = LegalManifestPersistenceITSupport.applyHarness(dataSource, LegalDatabaseBudgets.production());
        var editorial = new LegalEditorialITFixture(directory, anchor, owner,
                LegalManifestPersistenceITSupport.harness(dataSource, LegalDatabaseBudgets.production()), apply);
        ObjectNode original = (ObjectNode) new ObjectMapper().readTree(Files.readAllBytes(sourceManifest));
        var target = editorial.importedDraft("acceptance-service-replacement", (path, manifest) -> {
            manifest.removeAll(); manifest.setAll(original.deepCopy()); manifest.put("publicationId", "acceptance-service-replacement");
            for (var entry : manifest.withArray("requirements")) {
                if (!(number == 1 ? FIRST_REQUIREMENT_KEY : SECOND_REQUIREMENT_KEY).equals(entry.path("key").asText())) continue;
                ObjectNode requirement = (ObjectNode) entry;
                String statement = "Confirmo la nueva versión de aceptación atómica " + number + ".";
                requirement.put("version", "2.0.0"); requirement.put("statement", statement);
                requirement.put("statementSha256", sha(statement)); requirement.put("required", required);
                requirement.put("requiresReacceptance", reacceptance);
            }
        });
        var result = apply.service().applyReplace(target.release(), editorial.replacementPlan(source, target, "acceptance-service-replace").plan());
        assertThat(result.persisted()).as("issues=%s", result.issues()).isTrue();
        source = target;
        sourceManifest = directory.resolve("acceptance-service-replacement/publication-manifest.json");
    }

    void retireUsage(Path directory, Class<?> anchor) throws Exception {
        requireEphemeral();
        var apply = LegalManifestPersistenceITSupport.applyHarness(dataSource, LegalDatabaseBudgets.production());
        String publication = source.release().plan().manifest().publicationId();
        String fingerprint = apply.readinessCore().observeState(publication,
                owner.queryForObject("SELECT statement_timestamp()", OffsetDateTime.class).toInstant()).editorialStateFingerprint();
        ObjectNode plan = new ObjectMapper().createObjectNode();
        plan.put("schemaVersion", 1); plan.put("operationId", UUID.randomUUID().toString()); plan.put("operationType", "RETIRE");
        plan.put("expectedCurrentPublicationId", publication); plan.put("targetPublicationId", publication);
        plan.put("expectedCurrentManifestSha256", source.release().plan().manifestSha256());
        plan.put("targetManifestSha256", source.release().plan().manifestSha256());
        plan.put("expectedEditorialStateFingerprint", fingerprint);
        for (String field : List.of("documentAdditions", "documentReuses", "documentReplacementBatches", "documentRetirements",
                "requirementAdditions", "requirementReuses", "requirementReplacements", "requirementRetirements")) plan.putArray(field);
        owner.query("""
                SELECT v.id,v.afirmacion_sha256,line.contexto FROM legal_requisito_versiones v
                  JOIN legal_requisito_lineas line ON line.id=v.requisito_linea_id
                 WHERE line.clave IN (?,?) AND v.estado='VIGENTE'
                """, row -> {
            var retired = plan.withArray("requirementRetirements").addObject();
            retired.put("requirementVersionId", row.getObject("id", UUID.class).toString());
            retired.put("statementSha256", row.getString("afirmacion_sha256")); retired.put("context", row.getString("contexto"));
            retired.putArray("audiences").add("ADMIN_TITULAR").add("USER"); retired.put("reason", "Retiro del catálogo del fixture posterior al commit.");
        }, FIRST_REQUIREMENT_KEY, SECOND_REQUIREMENT_KEY);
        plan.put("expectedReadinessAfter", "NOT_READY"); plan.put("acknowledgeFailClosedGap", true);
        Path path = directory.resolve("acceptance-service-retire/editorial-plan.json");
        Files.createDirectories(path.getParent()); Files.write(path, new ObjectMapper().writeValueAsBytes(plan));
        var validated = new LegalEditorialPlanValidator().validate(path.toRealPath());
        assertThat(validated.passed()).as("issues=%s", validated.issues()).isTrue();
        var result = apply.service().applyRetire(source.release(), validated.value().orElseThrow());
        assertThat(result.persisted()).as("issues=%s", result.issues()).isTrue();
    }

    Map<String, Long> counts() {
        requireEphemeral();
        Map<String, Long> result = new LinkedHashMap<>();
        for (String table : DURABLE_TABLES) result.put(table, owner.queryForObject("SELECT count(*) FROM public." + table, Long.class));
        return Map.copyOf(result);
    }

    Map<String, List<String>> durableRows() {
        requireEphemeral();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : DURABLE_TABLES) result.put(table, owner.queryForList(
                "SELECT to_jsonb(t)::text || '|xmin=' || xmin::text FROM public." + table + " t ORDER BY 1", String.class));
        return Map.copyOf(result);
    }

    void replica(Runnable mutation) { requireEphemeral(); LegalManifestPersistenceITSupport.withReplicaRole(owner, mutation); }

    void requireEphemeral() {
        assertThat(owner.queryForObject("SELECT current_database()", String.class)).startsWith("ordenfix_legal_acceptance_");
    }

    static LegalRequestMetadata metadata() { return metadata("OrdenFix integration fixture"); }
    static LegalRequestMetadata metadata(String userAgent) {
        return LegalRequestMetadata.of(LegalRequestMetadata.parseIpLiteral("198.51.100.17"), userAgent);
    }
    static String key() { return UUID.randomUUID().toString(); }
    static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    record Harness(LegalAcceptanceService service, JdbcTemplate jdbc, LegalPrivateRequirementsDataSource boundedDataSource,
                   LegalJdbcMetricsSupport metrics, HikariDataSource pool, TransactionTemplate transaction,
                   LegalAcceptanceKeyConfiguration keys) implements AutoCloseable {
        @Override public void close() { try { boundedDataSource.close(); } finally { pool.close(); } }
    }
}
