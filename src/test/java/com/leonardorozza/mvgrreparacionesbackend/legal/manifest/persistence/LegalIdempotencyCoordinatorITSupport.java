package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Registration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalIdempotencyFingerprint;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertActor;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertDocuments;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertLot;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.insertMetadata;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.jdbc;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.materialize;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.sharedBoundary;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.transaction;
import static org.assertj.core.api.Assertions.assertThat;

/** Disposable owner fixtures and restricted Spring transactions; no production registration/evidence writer is claimed. */
final class LegalIdempotencyCoordinatorITSupport {
    static final String ACCEPTOR = "ordenfix_v29_idempotency_acceptor";
    static final String REGISTRAR = "ordenfix_v29_idempotency_registration";
    static final String SECRET_ONE = Base64.getEncoder().encodeToString("11111111111111111111111111111111".getBytes(StandardCharsets.US_ASCII));
    static final String SECRET_TWO = Base64.getEncoder().encodeToString("22222222222222222222222222222222".getBytes(StandardCharsets.US_ASCII));
    static final LegalIdempotencyKeyring KEY_ONE = new LegalIdempotencyKeyring(Map.of(1, SECRET_ONE), 1);
    static final LegalIdempotencyKeyring ROTATED = new LegalIdempotencyKeyring(Map.of(1, SECRET_ONE, 2, SECRET_TWO), 2);
    private static final long OPERATION_NANOS = Duration.ofSeconds(20).toNanos();
    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);

    final LegalV29AcceptanceITSupport database;
    final JdbcTemplate owner;
    final DataSource acceptorDataSource;
    final DataSource registrationDataSource;
    private LegalEditorialITFixture.ImportedRelease source;

    LegalIdempotencyCoordinatorITSupport(PostgreSQLContainer postgres) {
        database = new LegalV29AcceptanceITSupport(postgres); database.migrate("29"); owner = database.owner;
        acceptorDataSource = database.provision(ACCEPTOR, false, false);
        registrationDataSource = database.provision(REGISTRAR, true, false);
        // Nominal addition for the production V29 preflight; business/metadata privileges stay those of the V29 fixture.
        owner.execute("GRANT SELECT ON public.flyway_schema_history TO " + ACCEPTOR + ", " + REGISTRAR);
    }

    void reset(Path directory, Class<?> anchor) throws Exception {
        database.requireEphemeral();
        owner.execute("TRUNCATE legal_requisito_agregados, legal_publicaciones, legal_documento_reemplazo_lotes, talleres RESTART IDENTITY CASCADE");
        var release = LegalManifestPersistenceITSupport.copyRelease(directory, anchor, "idempotency-source", (path, manifest) -> {
            manifest.withArray("documents").forEach(document -> ((ObjectNode) document).put("effectiveAt", "2020-01-01T00:00:00-03:00"));
            for (int index = 1; index <= 2; index++) {
                var requirement = manifest.withArray("requirements").addObject();
                requirement.put("key", "idempotency-use-" + index); requirement.put("version", "1.0.0");
                requirement.put("context", "USO_CONTINUADO"); requirement.putArray("roles").add("ADMIN_TITULAR").add("USER");
                requirement.put("actType", "ACEPTACION");
                String statement = "Confirmo el requisito real de idempotencia " + index + ".";
                requirement.put("statement", statement); requirement.put("statementSha256", sha(statement));
                requirement.putArray("documents").add("terminos"); requirement.put("required", true); requirement.put("requiresReacceptance", true);
            }
        });
        UUID publication = LegalV28AggregateITSupport.importRelease(database.dataSource, release);
        LegalManifestPersistenceITSupport.promoteToReady(owner, publication);
        source = new LegalEditorialITFixture.ImportedRelease(release, publication);
    }

    Harness harness(boolean registration, LegalIdempotencyKeyring keyring) {
        DataSource restricted = registration ? registrationDataSource : acceptorDataSource;
        var metrics = LegalJdbcMetricsSupport.instrument(restricted, Duration.ZERO);
        var jdbc = new JdbcTemplate(metrics.dataSource());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(metrics.dataSource()));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setReadOnly(false); tx.setTimeout(20);
        var store = new LegalIdempotencyResultStore(jdbc);
        return new Harness(jdbc, tx, new LegalIdempotencyCoordinator(jdbc, new LegalV29AcceptanceSchemaVerifier(jdbc, "public"), keyring, store),
                store, keyring, metrics);
    }

    static <T> T inTransaction(Harness harness, Work<T> work) {
        long deadline = System.nanoTime() + OPERATION_NANOS;
        IntSupplier remaining = () -> {
            long nanos = deadline - System.nanoTime();
            return nanos <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (nanos + 999_999L) / 1_000_000L);
        };
        return harness.transaction().execute(status -> {
            harness.jdbc().execute("SET LOCAL statement_timeout = '20s'");
            harness.jdbc().execute("SET LOCAL lock_timeout = '20s'");
            try { return work.run(status, remaining); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Fallo en fixture transaccional", failure); }
        });
    }

    LegalActorSnapshot actor() {
        var actor = insertActor(owner);
        return new LegalActorSnapshot(actor.userId(), actor.workshopId(), UserRole.USER, 0, true, true);
    }

    LegalAcceptanceCommand acceptanceCommand(LegalActorSnapshot actor) throws Exception {
        var aggregate = aggregateFixture(PerfilAgregadoLegal.AUTHENTICATED_PENDING, actor.audience());
        return LegalAcceptanceCommandValidator.authenticated(actor, aggregate.requiredSetRevision(), acceptances(aggregate.aggregateId()));
    }

    LegalAcceptanceCommand registrationCommand(String password) throws Exception {
        var aggregate = aggregateFixture(PerfilAgregadoLegal.REGISTRATION, AudienciaLegal.ADMIN_TITULAR);
        Registration registration = new Registration("Taller idempotencia", "1100000000", "Titular", "registro@ordenfix.test", password);
        return LegalAcceptanceCommandValidator.registration(registration, aggregate.requiredSetRevision(), acceptances(aggregate.aggregateId()));
    }

    static LegalAcceptanceCommand withAcceptances(LegalAcceptanceCommand source, List<Acceptance> acceptances) {
        return source.registration() == null
                ? LegalAcceptanceCommandValidator.authenticated(source.actor(), source.requiredSetRevision(), acceptances)
                : LegalAcceptanceCommandValidator.registration(source.registration(), source.requiredSetRevision(), acceptances);
    }

    static LegalAcceptanceCommand withActor(LegalAcceptanceCommand source, LegalActorSnapshot actor) {
        return LegalAcceptanceCommandValidator.authenticated(actor, source.requiredSetRevision(), source.acceptances());
    }

    static String key() { return UUID.randomUUID().toString(); }

    private LegalRequiredSetAggregateReceipt aggregateFixture(PerfilAgregadoLegal profile, AudienciaLegal audience) throws Exception {
        try (var connection = transaction(database.dataSource)) {
            var aggregate = materialize(jdbc(connection), profile, audience); connection.commit(); return aggregate;
        }
    }

    private List<Acceptance> acceptances(UUID aggregate) {
        return owner.query("""
                SELECT version.id,line.tipo_acto,version.afirmacion_sha256
                  FROM legal_requisito_agregado_scopes scope
                  JOIN legal_requisito_conjunto_miembros member ON member.conjunto_id=scope.conjunto_id
                  JOIN legal_requisito_versiones version ON version.id=member.requisito_version_id
                  JOIN legal_requisito_lineas line ON line.id=version.requisito_linea_id
                 WHERE scope.agregado_id=? ORDER BY scope.scope_ordinal,member.manifest_ordinal
                """, (row, ignored) -> {
            UUID id = row.getObject("id", UUID.class);
            List<Document> documents = owner.query("""
                    SELECT document.documento_version_id,version.sha256
                      FROM legal_requisito_documentos document
                      JOIN legal_documento_versiones version ON version.id=document.documento_version_id
                     WHERE document.requisito_version_id=? ORDER BY document.documento_ordinal
                    """, (doc, index) -> new Document(doc.getObject("documento_version_id", UUID.class), doc.getString("sha256")), id);
            return new Acceptance(id, TipoActoLegal.valueOf(row.getString("tipo_acto")), row.getString("afirmacion_sha256"), documents, true);
        }, aggregate);
    }

    static void writerBoundary(JdbcTemplate jdbc, LegalActorSnapshot actor) {
        sharedBoundary(jdbc);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(jsonb_build_array('ordenfix:legal-actor:v1',?::bigint,?::bigint)::text,0))",
                actor.tallerId(), actor.userId());
        jdbc.queryForList("SELECT id FROM talleres WHERE id=? FOR SHARE", actor.tallerId());
        jdbc.queryForList("SELECT id FROM users WHERE id=? AND taller_id=? FOR SHARE", actor.userId(), actor.tallerId());
    }

    static LegalRequiredSetAggregateReceipt observed(JdbcTemplate jdbc, LegalActorSnapshot actor) {
        return materialize(jdbc, PerfilAgregadoLegal.AUTHENTICATED_PENDING, actor.audience());
    }

    static LotWritten newLot(JdbcTemplate jdbc, LegalActorSnapshot actor, PerfilAgregadoLegal profile, List<Acceptance> subset) {
        writerBoundary(jdbc, actor);
        var aggregate = materialize(jdbc, profile, actor.audience());
        var originalActor = new LegalAcceptanceProtocolFeasibilityITSupport.Actor(actor.userId(), actor.tallerId(), actor.role().name(), actor.audience().name());
        var lot = insertLot(jdbc, originalActor, aggregate);
        List<LegalAcceptanceProtocolFeasibilityITSupport.Act> acts = new ArrayList<>();
        for (Acceptance acceptance : subset) {
            UUID id = UUID.randomUUID();
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptaciones
                      (id,lote_id,user_id,taller_id,requisito_version_id,requisito_clave,requisito_version,contexto,tipo_acto,afirmacion,afirmacion_sha256,requerido)
                    SELECT ?,?,?,?,v.id,l.clave,v.version,l.contexto,l.tipo_acto,v.afirmacion,v.afirmacion_sha256,v.requerido
                      FROM legal_requisito_versiones v JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id WHERE v.id=?
                    """, id, lot.id(), actor.userId(), actor.tallerId(), acceptance.requisitoVersionId())).isOne();
            acts.add(new LegalAcceptanceProtocolFeasibilityITSupport.Act(id, acceptance.requisitoVersionId()));
        }
        insertDocuments(jdbc, acts); insertMetadata(jdbc, lot);
        return new LotWritten(actor, lot.id(), acts.stream().map(LegalAcceptanceProtocolFeasibilityITSupport.Act::id).sorted(UUID_ORDER).toList());
    }

    static LegalActorSnapshot registrationActor(JdbcTemplate jdbc, Registration registration) {
        sharedBoundary(jdbc);
        long workshop = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO talleres(nombre,email_contacto,telefono,activo,created_at,updated_at)
                VALUES(?,?,?,true,localtimestamp,localtimestamp) RETURNING id
                """, Long.class, registration.nombreTaller(), registration.email(), registration.telefonoTaller()));
        jdbc.update("""
                INSERT INTO suscripciones(taller_id,plan,estado,fecha_inicio,fecha_fin_trial,created_at,updated_at)
                VALUES(?,'FREE','TRIAL',?,?,localtimestamp,localtimestamp)
                """, workshop, LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 20));
        long user = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO users(username,password,email,role,taller_id,active,email_verificado,token_version)
                VALUES(?,'synthetic-registration-hash',?,'ADMIN',?,true,false,0) RETURNING id
                """, Long.class, registration.nombreAdmin(), registration.email(), workshop));
        return new LegalActorSnapshot(user, workshop, UserRole.ADMIN, 0, true, true);
    }

    static LotWritten write(Harness harness, LegalIdempotencyCoordinator.Reservation reservation,
                            LegalActorSnapshot actor, List<Acceptance> subset) {
        PerfilAgregadoLegal profile = reservation.command().registration() == null ? PerfilAgregadoLegal.AUTHENTICATED_PENDING : PerfilAgregadoLegal.REGISTRATION;
        LotWritten written = newLot(harness.jdbc(), actor, profile, subset);
        harness.store().persistWithActs(reservation, actor, written.lotId());
        harness.jdbc().execute("SET CONSTRAINTS ALL IMMEDIATE"); return written;
    }

    LotWritten committedEvidence(LegalActorSnapshot actor, List<Acceptance> subset) throws Exception {
        // Existing canonical acts, without synthesizing an idempotency success, are valid historical evidence.
        try (var connection = transaction(database.dataSource)) {
            LotWritten written = newLot(jdbc(connection), actor, PerfilAgregadoLegal.AUTHENTICATED_PENDING, subset);
            jdbc(connection).execute("SET CONSTRAINTS ALL IMMEDIATE"); connection.commit(); return written;
        }
    }

    static LegalIdempotencyResultStore.StoredResult replay(Harness harness, LegalAcceptanceCommand command, String key) {
        return inTransaction(harness, (status, remaining) -> harness.coordinator().reserve(command, key, remaining).replay().orElseThrow());
    }

    static void emptyOrDedup(Harness harness, LegalIdempotencyCoordinator.Reservation reservation, List<UUID> ids) {
        LegalActorSnapshot actor = reservation.command().actor(); writerBoundary(harness.jdbc(), actor);
        var aggregate = observed(harness.jdbc(), actor);
        harness.store().persistWithoutActs(reservation, actor, aggregate, ids);
        harness.jdbc().execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    List<Long> physicalKeys(LegalIdempotencyKeyring ring, LegalAcceptanceCommand command, String key) {
        return ring.candidates(command, key).stream().map(candidate -> physicalKey(owner, candidate)).distinct().sorted(Long::compareUnsigned).toList();
    }

    static long physicalKey(JdbcTemplate jdbc, LegalIdempotencyFingerprint candidate) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT pg_catalog.hashtextextended(pg_catalog.jsonb_build_array(
                  'ordenfix:legal-idempotencia:tupla:v29',?::text,?::text,?::text,?::text)::text,0)
                """, Long.class, candidate.operation().name(), candidate.routeTemplate(), candidate.scopeHmac(), candidate.idempotencyKeyHmac()));
    }

    static void hold(JdbcTemplate jdbc, long key) { jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", key); }

    void awaitWait(int pid, long key) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        do {
            Boolean waiting = owner.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_locks WHERE pid=? AND locktype='advisory' AND NOT granted
                      AND objsubid=1 AND classid::bigint=((?::bigint>>32)&4294967295::bigint)
                      AND objid::bigint=(?::bigint&4294967295::bigint))
                    """, Boolean.class, pid, key, key);
            if (Boolean.TRUE.equals(waiting)) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("La reserva no quedó esperando el lock físico esperado");
    }

    void noLocksFor(int pid) {
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_catalog.pg_locks WHERE pid=? AND locktype='advisory'", Long.class, pid)).isZero();
    }

    void expire(LegalIdempotencyResultStore.StoredResult result) {
        database.requireEphemeral();
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> {
            String table = result.ledgerId() != null ? "legal_idempotencia_resultados" : "legal_idempotencia_sin_actos";
            Object id = result.ledgerId() != null ? result.ledgerId() : result.supplementalId();
            OffsetDateTime completed = owner.queryForObject("SELECT statement_timestamp()-INTERVAL '50 hours'", OffsetDateTime.class);
            owner.update("UPDATE " + table + " SET completed_at=?, expires_at=? WHERE id=?", completed, completed.plusHours(25), id);
            if (result.lotId() != null) owner.update("UPDATE legal_aceptacion_lotes SET aceptado_en=? WHERE id=?", completed, result.lotId());
        });
    }

    void replaceAndRetire(Path directory, Class<?> anchor, Runnable afterReplace) throws Exception {
        database.requireEphemeral();
        var apply = LegalManifestPersistenceITSupport.applyHarness(database.dataSource, LegalDatabaseBudgets.production());
        var fixture = new LegalEditorialITFixture(directory, anchor, owner,
                LegalManifestPersistenceITSupport.harness(database.dataSource, LegalDatabaseBudgets.production()), apply);
        var sourceManifest = new ObjectMapper().readTree(directory.resolve("idempotency-source/publication-manifest.json").toFile());
        var target = fixture.importedDraft("idempotency-replacement", (path, manifest) -> {
            manifest.removeAll(); manifest.setAll((ObjectNode) sourceManifest.deepCopy()); manifest.put("publicationId", "idempotency-replacement");
            for (var candidate : manifest.withArray("requirements")) {
                if (!"idempotency-use-1".equals(candidate.path("key").asText())) continue;
                var requirement = (ObjectNode) candidate; String statement = "Esta versión editorial posterior exige un nuevo acto.";
                requirement.put("version", "2.0.0"); requirement.put("statement", statement); requirement.put("statementSha256", sha(statement));
                requirement.put("requiresReacceptance", true);
            }
        });
        var replaced = apply.service().applyReplace(target.release(), fixture.replacementPlan(source, target, "idempotency-replace").plan());
        assertThat(replaced.persisted()).as("issues=%s", replaced.issues()).isTrue(); afterReplace.run();
        String externalId = target.release().plan().manifest().publicationId();
        String fingerprint = apply.readinessCore().observeState(externalId,
                owner.queryForObject("SELECT statement_timestamp()", OffsetDateTime.class).toInstant()).editorialStateFingerprint();
        ObjectNode plan = new ObjectMapper().createObjectNode();
        plan.put("schemaVersion", 1); plan.put("operationId", UUID.randomUUID().toString()); plan.put("operationType", "RETIRE");
        plan.put("expectedCurrentPublicationId", externalId); plan.put("targetPublicationId", externalId);
        plan.put("expectedCurrentManifestSha256", target.release().plan().manifestSha256()); plan.put("targetManifestSha256", target.release().plan().manifestSha256());
        plan.put("expectedEditorialStateFingerprint", fingerprint);
        for (String field : List.of("documentAdditions", "documentReuses", "documentReplacementBatches", "documentRetirements", "requirementAdditions", "requirementReuses", "requirementReplacements", "requirementRetirements")) plan.putArray(field);
        owner.query("""
                SELECT v.id,v.afirmacion_sha256,l.contexto FROM legal_requisito_versiones v
                  JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id WHERE l.clave LIKE 'idempotency-use-%' AND v.estado='VIGENTE'
                """, row -> {
            var retirement = plan.withArray("requirementRetirements").addObject();
            retirement.put("requirementVersionId", row.getObject("id", UUID.class).toString()); retirement.put("statementSha256", row.getString("afirmacion_sha256"));
            retirement.put("context", row.getString("contexto")); retirement.putArray("audiences").add("ADMIN_TITULAR").add("USER");
            retirement.put("reason", "Retiro posterior al resultado idempotente confirmado.");
        });
        plan.put("expectedReadinessAfter", "NOT_READY"); plan.put("acknowledgeFailClosedGap", true);
        Path path = directory.resolve("idempotency-retire/editorial-plan.json"); Files.createDirectories(path.getParent());
        Files.write(path, new ObjectMapper().writeValueAsBytes(plan));
        var validated = new LegalEditorialPlanValidator().validate(path.toRealPath()); assertThat(validated.passed()).as("issues=%s", validated.issues()).isTrue();
        var retired = apply.service().applyRetire(target.release(), validated.value().orElseThrow());
        assertThat(retired.persisted()).as("issues=%s", retired.issues()).isTrue();
    }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    record Harness(JdbcTemplate jdbc, TransactionTemplate transaction, LegalIdempotencyCoordinator coordinator,
                   LegalIdempotencyResultStore store, LegalIdempotencyKeyring keyring, LegalJdbcMetricsSupport metrics) { }
    record LotWritten(LegalActorSnapshot actor, UUID lotId, List<UUID> acceptanceIds) { }
    @FunctionalInterface interface Work<T> { T run(TransactionStatus status, IntSupplier remaining) throws Exception; }
}
