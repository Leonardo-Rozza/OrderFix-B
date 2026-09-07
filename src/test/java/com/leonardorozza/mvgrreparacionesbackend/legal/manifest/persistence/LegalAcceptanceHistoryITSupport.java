package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.*;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.requireSafeEphemeralDatabase;
import static org.assertj.core.api.Assertions.assertThat;

/** Owner-only fixtures; production reads always use the exact restricted 15C role. */
final class LegalAcceptanceHistoryITSupport {
    private LegalAcceptanceHistoryITSupport() { }

    static AuthenticatedUserPrincipal principal(LegalPrivateRequirementsITSupport.Actor actor) {
        var workshop = new Taller(); workshop.setId(actor.workshopId());
        var user = User.builder().password("history-principal-test-only").build();
        user.setId(actor.userId()); user.setTaller(workshop); user.setEmail("history@ordenfix.test");
        user.setActive(true); user.setRole(UserRole.valueOf(actor.role())); user.setTokenVersion(0L);
        return new AuthenticatedUserPrincipal(user);
    }

    static LegalEditorialITFixture.ImportedRelease seedCatalog(JdbcTemplate owner, Path directory,
                                                                Class<?> anchor, int requirements) throws Exception {
        requireSafeEphemeralDatabase(owner);
        var release = LegalManifestPersistenceITSupport.copyRelease(directory, anchor, "history-source",
                (path, manifest) -> {
                    manifest.withArray("documents").forEach(document -> ((ObjectNode) document)
                            .put("effectiveAt", "2020-01-01T00:00:00-03:00"));
                    for (int index = 1; index <= requirements; index++) {
                        var requirement = manifest.withArray("requirements").addObject();
                        requirement.put("key", "history-use-" + index);
                        requirement.put("version", "1.0.0"); requirement.put("context", "USO_CONTINUADO");
                        requirement.putArray("roles").add("ADMIN_TITULAR").add("USER");
                        requirement.put("actType", "ACEPTACION");
                        String statement = "Confirmo la afirmación histórica original número " + index + ".";
                        requirement.put("statement", statement); requirement.put("statementSha256", sha256(statement));
                        requirement.putArray("documents").add("terminos");
                        requirement.put("required", true); requirement.put("requiresReacceptance", true);
                    }
                });
        UUID publication = LegalV28AggregateITSupport.importRelease(owner.getDataSource(), release);
        LegalManifestPersistenceITSupport.promoteToReady(owner, publication);
        return new LegalEditorialITFixture.ImportedRelease(release, publication);
    }

    static void actorExclusive(JdbcTemplate jdbc, LegalPrivateRequirementsITSupport.Actor actor) {
        sharedBoundary(jdbc);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(jsonb_build_array('ordenfix:legal-actor:v1', ?::bigint, ?::bigint)::text,0))",
                actor.workshopId(), actor.userId());
        jdbc.queryForList("SELECT id FROM talleres WHERE id=? FOR SHARE", actor.workshopId());
        jdbc.queryForList("SELECT id FROM users WHERE id=? AND taller_id=? FOR SHARE", actor.userId(), actor.workshopId());
    }

    static List<Act> accept(DataSource dataSource, LegalPrivateRequirementsITSupport.Actor actor,
                            PerfilAgregadoLegal profile, Integer ordinal) throws Exception {
        try (Connection connection = transaction(dataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            actorExclusive(jdbc, actor);
            List<Act> result = acceptLocked(jdbc, actor, profile, ordinal);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            connection.commit();
            return result;
        }
    }

    /** Caller holds the editorial shared gate, actor exclusive advisory and actor row locks. */
    static List<Act> acceptLocked(JdbcTemplate jdbc, LegalPrivateRequirementsITSupport.Actor actor,
                                  PerfilAgregadoLegal profile, Integer ordinal) {
        var aggregate = materialize(jdbc, profile, AudienciaLegal.valueOf(actor.audience()));
        var lot = insertLot(jdbc, new Actor(actor.userId(), actor.workshopId(), actor.role(), actor.audience()), aggregate);
        List<UUID> requirements = jdbc.queryForList("""
                SELECT m.requisito_version_id FROM legal_requisito_agregado_scopes s
                  JOIN legal_requisito_conjunto_miembros m ON m.conjunto_id=s.conjunto_id
                 WHERE s.agregado_id=? ORDER BY s.scope_ordinal,m.manifest_ordinal
                """, UUID.class, aggregate.aggregateId());
        List<Act> acts = new ArrayList<>();
        for (int index = 0; index < requirements.size(); index++) {
            if (ordinal != null && ordinal != index) continue;
            // Exercise PostgreSQL UUID ordering across Java UUID's signed boundary without mutation.
            UUID id = index == 0 ? UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff")
                    : index == 1 ? UUID.fromString("00000000-0000-4000-8000-000000000001") : UUID.randomUUID();
            // Different actors/lots need distinct immutable evidence identifiers.
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM legal_aceptaciones WHERE id=?)", Boolean.class, id)))
                id = UUID.randomUUID();
            UUID requirement = requirements.get(index);
            assertThat(jdbc.update("""
                    INSERT INTO legal_aceptaciones
                       (id,lote_id,user_id,taller_id,requisito_version_id,requisito_clave,requisito_version,
                        contexto,tipo_acto,afirmacion,afirmacion_sha256,requerido)
                    SELECT ?,?,?,?,v.id,l.clave,v.version,l.contexto,l.tipo_acto,
                           v.afirmacion,v.afirmacion_sha256,v.requerido
                      FROM legal_requisito_versiones v JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id
                     WHERE v.id=?
                    """, id, lot.id(), actor.userId(), actor.workshopId(), requirement)).isOne();
            acts.add(new Act(id, requirement));
        }
        assertThat(acts).isNotEmpty();
        insertDocuments(jdbc, acts); insertMetadata(jdbc, lot);
        return List.copyOf(acts);
    }

    static List<UUID> orderedIds(JdbcTemplate owner, LegalPrivateRequirementsITSupport.Actor actor, ContextoLegal context) {
        return owner.queryForList("""
                SELECT a.id FROM legal_aceptaciones a JOIN legal_aceptacion_lotes l ON l.id=a.lote_id
                 WHERE a.user_id=? AND a.taller_id=? AND (?::text IS NULL OR a.contexto=?::text)
                 ORDER BY l.aceptado_en DESC,a.id DESC
                """, UUID.class, actor.userId(), actor.workshopId(), context == null ? null : context.name(),
                context == null ? null : context.name());
    }

    static Map<String, List<String>> evidenceRows(JdbcTemplate owner) {
        requireSafeEphemeralDatabase(owner);
        Map<String, List<String>> captured = new LinkedHashMap<>();
        for (String table : List.of("legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
                "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados")) {
            String columns = table.equals("legal_aceptacion_lotes")
                    ? "to_jsonb(t) - ARRAY['revision_scheme','perfil','agregado_id']" : "to_jsonb(t)";
            captured.put(table, owner.queryForList("SELECT (" + columns
                    + ")::text || '|xmin=' || xmin::text FROM public." + table + " t ORDER BY 1", String.class));
        }
        return Map.copyOf(captured);
    }

    static LegalEditorialITFixture.ImportedRelease replace(JdbcTemplate owner, DataSource dataSource,
            Path directory, Class<?> anchor, LegalEditorialITFixture.ImportedRelease source) throws Exception {
        var apply = LegalManifestPersistenceITSupport.applyHarness(dataSource, LegalDatabaseBudgets.production());
        var fixture = new LegalEditorialITFixture(directory, anchor, owner,
                LegalManifestPersistenceITSupport.harness(dataSource, LegalDatabaseBudgets.production()), apply);
        var sourceManifest = new ObjectMapper().readTree(directory.resolve("history-source/publication-manifest.json").toFile());
        var target = fixture.importedDraft("history-replacement", (path, manifest) -> {
            manifest.removeAll(); manifest.setAll((ObjectNode) sourceManifest.deepCopy());
            manifest.put("publicationId", "history-replacement");
            for (var candidate : manifest.withArray("requirements")) {
                if (!"history-use-1".equals(candidate.path("key").asText())) continue;
                var requirement = (ObjectNode) candidate;
                String statement = "La nueva afirmación reemplaza la versión original y exige otro acto.";
                requirement.put("version", "2.0.0"); requirement.put("statement", statement);
                requirement.put("statementSha256", sha256(statement)); requirement.put("requiresReacceptance", true);
            }
        });
        var result = apply.service().applyReplace(target.release(), fixture.replacementPlan(source, target, "history-replace").plan());
        assertThat(result.persisted()).as("issues=%s", result.issues()).isTrue();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
        return target;
    }

    static void retire(JdbcTemplate owner, DataSource dataSource, Path directory,
                       LegalEditorialITFixture.ImportedRelease current) throws Exception {
        var apply = LegalManifestPersistenceITSupport.applyHarness(dataSource, LegalDatabaseBudgets.production());
        String externalId = current.release().plan().manifest().publicationId();
        String fingerprint = apply.readinessCore().observeState(externalId,
                owner.queryForObject("SELECT statement_timestamp()", OffsetDateTime.class).toInstant()).editorialStateFingerprint();
        ObjectNode plan = new ObjectMapper().createObjectNode();
        plan.put("schemaVersion", 1); plan.put("operationId", UUID.randomUUID().toString()); plan.put("operationType", "RETIRE");
        plan.put("expectedCurrentPublicationId", externalId); plan.put("targetPublicationId", externalId);
        plan.put("expectedCurrentManifestSha256", current.release().plan().manifestSha256());
        plan.put("targetManifestSha256", current.release().plan().manifestSha256());
        plan.put("expectedEditorialStateFingerprint", fingerprint);
        for (String field : List.of("documentAdditions", "documentReuses", "documentReplacementBatches", "documentRetirements",
                "requirementAdditions", "requirementReuses", "requirementReplacements", "requirementRetirements")) plan.putArray(field);
        owner.query("""
                SELECT v.id,v.afirmacion_sha256,l.contexto FROM legal_requisito_versiones v
                  JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id
                 WHERE l.clave LIKE 'history-use-%' AND v.estado='VIGENTE'
                """, rs -> {
            var retired = plan.withArray("requirementRetirements").addObject();
            retired.put("requirementVersionId", rs.getObject("id", UUID.class).toString());
            retired.put("statementSha256", rs.getString("afirmacion_sha256"));
            retired.put("context", rs.getString("contexto"));
            retired.putArray("audiences").add("ADMIN_TITULAR").add("USER");
            retired.put("reason", "Retiro explícito del fixture de historia propia.");
        });
        plan.put("expectedReadinessAfter", "NOT_READY"); plan.put("acknowledgeFailClosedGap", true);
        Path path = directory.resolve("history-retire/editorial-plan.json"); Files.createDirectories(path.getParent());
        Files.write(path, new ObjectMapper().writeValueAsBytes(plan));
        var validated = new LegalEditorialPlanValidator().validate(path.toRealPath());
        assertThat(validated.passed()).as("issues=%s", validated.issues()).isTrue();
        var result = apply.service().applyRetire(current.release(), validated.value().orElseThrow());
        assertThat(result.persisted()).as("issues=%s", result.issues()).isTrue();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
    }

    /** Waits for a concrete PostgreSQL advisory lock dependency, rather than inferring order from time. */
    static void awaitBlockedPid(JdbcTemplate owner, long blockedPid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        boolean blocked;
        do {
            blocked = Boolean.TRUE.equals(owner.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks
                     WHERE pid=? AND locktype='advisory' AND NOT granted)
                    """, Boolean.class, blockedPid));
            if (blocked) return;
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("El escritor no quedó esperando el advisory compartido del lector");
    }

    static final class CountBarrier {
        final CountDownLatch countReturned = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean once = new AtomicBoolean();
        void intercept(String sql) {
            String normalized = sql.toLowerCase(Locale.ROOT);
            if (!normalized.contains("count(") || !normalized.contains("legal_aceptaciones") || !once.compareAndSet(false, true)) return;
            countReturned.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("No se liberó la barrera del conteo histórico");
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }

    static Connection observeCount(Connection delegate, java.util.function.Supplier<CountBarrier> barrier) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql && result instanceof PreparedStatement statement) {
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        try {
                                            Object statementResult = statementMethod.invoke(statement, statementArgs);
                                            CountBarrier active = barrier.get();
                                            if (active != null && statementMethod.getName().equals("executeQuery")) active.intercept(sql);
                                            return statementResult;
                                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                                    });
                        }
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }
}
