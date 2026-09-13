package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.*;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Private role and production service on PostgreSQL16; synthetic metadata remains owner-only fixture data. */
class LegalPrivateRequirementsReadServiceIT {
    private static final String ROLE = "ordenfix_legal_private_requirements_service";
    private static final String PASSWORD = "private-service-test-only";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_private_requirements_service")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static JdbcTemplate owner;
    private static DataSource ownerDataSource;
    @TempDir Path directory;
    private AnnotationConfigApplicationContext context;
    private HikariDataSource pool;
    private LegalJdbcMetricsSupport metrics;
    private LegalPrivateRequirementsReadService service;

    @BeforeAll static void start() {
        POSTGRES.start();
        LegalManifestPersistenceITSupport.migrateLatest(POSTGRES);
        ownerDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        provision(owner, ROLE, PASSWORD);
    }
    @BeforeEach void open() throws Exception {
        requireSafeEphemeralDatabase(owner);
        owner.execute("TRUNCATE legal_requisito_agregados, legal_publicaciones, legal_documento_reemplazo_lotes, talleres RESTART IDENTITY CASCADE");
        seedCatalog(owner, directory, getClass());
        context = new AnnotationConfigApplicationContext();
        String prefix = LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("private-it", Map.of(
                LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true",
                prefix + "jdbc-url", POSTGRES.getJdbcUrl(), prefix + "username", ROLE, prefix + "password", PASSWORD)));
        context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!name.equals("legalPrivateRequirementsPool")) return bean;
                pool = (HikariDataSource) bean;
                metrics = LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO);
                var monitored = spy(pool);
                try { doAnswer(call -> metrics.dataSource().getConnection()).when(monitored).getConnection(); }
                catch (SQLException failure) { throw new IllegalStateException(failure); }
                return monitored;
            }
        });
        context.register(LegalPrivateRequirementsDatabaseConfiguration.class);
        context.refresh();
        service = context.getBean(LegalPrivateRequirementsReadService.class);
        metrics.reset();
    }
    @AfterEach void close() { if (context != null) context.close(); if (pool != null) pool.close(); }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "USER"})
    void bothRolesGetTheirCompletePendingCompositionAndReuseDoesNoDml(String role) {
        var actor = seedActor(owner, role);
        var first = service.read(principal(actor));
        assertThat(first.requirements()).hasSize(2);
        assertThat(first.decisions()).allSatisfy(d -> assertThat(d.satisfaction())
                .isEqualTo(LegalAuthenticatedRequirements.Satisfaction.PENDING));
        assertThat(first.hasRequiredPending()).isTrue();
        assertThat(first.snapshot().actor().userId()).isEqualTo(actor.userId());
        assertThat(first.snapshot().applicableScopes().audience().name()).isEqualTo(actor.audience());
        assertThat(counts(owner)).containsEntry("legal_requisito_agregados", 1L)
                .containsEntry("legal_aceptaciones", 0L).containsEntry("legal_aceptacion_metadatos", 0L);
        assertReleased(true);
        var original = owner.queryForList("SELECT to_jsonb(t)::text || xmin::text FROM legal_requisito_agregados t");
        metrics.reset();
        var reused = service.read(principal(actor));
        assertThat(reused.requiredSetRevision()).isEqualTo(first.requiredSetRevision());
        assertThat(reused.requirements()).isEqualTo(first.requirements());
        assertThat(owner.queryForList("SELECT to_jsonb(t)::text || xmin::text FROM legal_requisito_agregados t")).isEqualTo(original);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertReleased(true);
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "USER"})
    void exactOwnEvidenceReturnsEmptyWithOriginalRevisionAndNoNewActs(String role) throws Exception {
        var actor = seedActor(owner, role);
        var pending = service.read(principal(actor));
        List<Act> acts = accept(actor, null);
        var before = counts(owner);
        metrics.reset();
        var accepted = service.read(principal(actor));
        assertThat(accepted.requirements()).isEmpty();
        assertThat(accepted.hasRequiredPending()).isFalse();
        assertThat(accepted.requiredSetRevision()).isEqualTo(pending.requiredSetRevision());
        assertThat(accepted.snapshot().requirements()).hasSize(2);
        assertThat(accepted.decisions()).allSatisfy(d -> assertThat(d.satisfaction())
                .isEqualTo(LegalAuthenticatedRequirements.Satisfaction.EXACT));
        assertThat(accepted.decisions()).extracting(LegalAuthenticatedRequirements.Decision::acceptanceId)
                .containsExactlyElementsOf(acts.stream().map(Act::id).toList());
        assertThat(counts(owner)).isEqualTo(before);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertReleased(true);
    }

    @Test void partialOwnEvidenceKeepsOnlyTheMissingRequirementAndPreservesTheCompleteSnapshot() throws Exception {
        var actor = seedActor(owner, "USER");
        var before = service.read(principal(actor));
        accept(actor, 0);
        var after = service.read(principal(actor));
        assertThat(after.requirements()).containsExactly(before.requirements().getLast());
        assertThat(after.requiredSetRevision()).isEqualTo(before.requiredSetRevision());
        assertThat(after.snapshot().requirements()).hasSize(2);
        assertThat(after.hasRequiredPending()).isTrue();
    }

    @Test void anotherUserInSameWorkshopAndAnotherTenantNeverSatisfyTheActor() throws Exception {
        var actor = seedActor(owner, "ADMIN");
        var otherTenant = seedActor(owner, "USER");
        var sameWorkshop = seedActor(owner, "USER", actor.workshopId());
        accept(otherTenant, null);
        accept(sameWorkshop, null);
        var result = service.read(principal(actor));
        assertThat(result.requirements()).hasSize(2);
        assertThat(result.decisions()).allSatisfy(d -> assertThat(d.acceptanceId()).isNull());
    }

    @Test void exactEvidenceSurvivesALegitimateRoleChangeWhenTheNewPrincipalMatches() throws Exception {
        var actor = seedActor(owner, "USER");
        accept(actor, null);
        owner.update("UPDATE users SET role = 'ADMIN', token_version = token_version + 1 WHERE id = ?", actor.userId());
        var changed = new LegalPrivateRequirementsITSupport.Actor(actor.userId(), actor.workshopId(), "ADMIN", "ADMIN_TITULAR");
        var result = service.read(principal(changed, 1));
        assertThat(result.requirements()).isEmpty();
        assertThat(result.snapshot().actor().role()).isEqualTo(UserRole.ADMIN);
    }

    @ParameterizedTest @ValueSource(strings = {"role", "token", "inactive", "workshop", "tenant", "missing"})
    void staleOrForeignPrincipalFailsBeforeAggregateDml(String mutation) {
        var actor = seedActor(owner, "USER");
        // Keep current database membership and present an inconsistent identity for the tenant case.
        var presentedActor = mutation.equals("tenant")
                ? new LegalPrivateRequirementsITSupport.Actor(actor.userId(), seedActor(owner, "ADMIN").workshopId(), actor.role(), actor.audience())
                : actor;
        var principal = principal(presentedActor);
        switch (mutation) {
            case "role" -> owner.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", actor.userId());
            case "token" -> owner.update("UPDATE users SET token_version = 1 WHERE id = ?", actor.userId());
            case "inactive" -> owner.update("UPDATE users SET active = false WHERE id = ?", actor.userId());
            case "workshop" -> owner.update("UPDATE talleres SET activo = false WHERE id = ?", actor.workshopId());
            case "tenant" -> { /* The mismatching principal above must fail before aggregate DML. */ }
            case "missing" -> owner.update("DELETE FROM users WHERE id = ?", actor.userId());
        }
        assertThatThrownBy(() -> service.read(principal)).isInstanceOf(LegalActorSnapshotException.class)
                .hasMessage("La identidad legal no está disponible");
        assertThat(counts(owner)).containsEntry("legal_requisito_agregados", 0L);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertReleased(false);
    }

    @ParameterizedTest @ValueSource(strings = {"current-digest", "evidence-digest", "evidence-document", "scope-missing"})
    void corruptionNeverReturnsPartialRequirementsAndRollsBack(String corruption) throws Exception {
        var actor = seedActor(owner, "USER");
        if (corruption.startsWith("evidence")) accept(actor, null);
        var before = counts(owner);
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> {
            switch (corruption) {
                case "current-digest" -> owner.update("UPDATE legal_requisito_versiones SET afirmacion_sha256 = ? WHERE requisito_linea_id IN (SELECT id FROM legal_requisito_lineas WHERE contexto = 'USO_CONTINUADO')", "0".repeat(64));
                case "evidence-digest" -> owner.update("UPDATE legal_aceptaciones SET afirmacion_sha256 = ? WHERE user_id = ?", "0".repeat(64), actor.userId());
                case "evidence-document" -> owner.update("DELETE FROM legal_aceptacion_documentos WHERE aceptacion_id IN (SELECT id FROM legal_aceptaciones WHERE user_id = ?)", actor.userId());
                case "scope-missing" -> owner.update("DELETE FROM legal_requisito_conjuntos_actuales WHERE contexto = 'USO_CONTINUADO' AND audiencia = 'USER'");
            }
        });
        assertThatThrownBy(() -> service.read(principal(actor))).isInstanceOf(LegalPrivateRequirementsReadException.class)
                .hasMessage("El contrato de requisitos no está disponible");
        assertThat(counts(owner).get("legal_requisito_agregados")).isEqualTo(before.get("legal_requisito_agregados"));
        assertReleased(false);
    }

    private static AuthenticatedUserPrincipal principal(LegalPrivateRequirementsITSupport.Actor actor) {
        return principal(actor, 0);
    }
    private static AuthenticatedUserPrincipal principal(LegalPrivateRequirementsITSupport.Actor actor, long token) {
        var workshop = new Taller(); workshop.setId(actor.workshopId());
        var user = User.builder().password("server-principal-test-only").build(); user.setId(actor.userId()); user.setTaller(workshop);
        user.setEmail("actor@ordenfix.test");
        user.setActive(true); user.setRole(UserRole.valueOf(actor.role())); user.setTokenVersion(token);
        return new AuthenticatedUserPrincipal(user);
    }

    /** Owner fixture writes canonical acts with the future writer's editorial/actor order. */
    private static List<Act> accept(LegalPrivateRequirementsITSupport.Actor source, Integer ordinal) throws Exception {
        var actor = new LegalAcceptanceProtocolFeasibilityITSupport.Actor(source.userId(), source.workshopId(), source.role(), source.audience());
        try (var connection = transaction(ownerDataSource)) {
            var jdbc = jdbc(connection);
            sharedBoundary(jdbc);
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(jsonb_build_array('ordenfix:legal-actor:v1', ?::bigint, ?::bigint)::text,0))", actor.workshopId(), actor.userId());
            jdbc.queryForList("SELECT id FROM talleres WHERE id = ? FOR SHARE", actor.workshopId());
            jdbc.queryForList("SELECT id FROM users WHERE id = ? AND taller_id = ? FOR SHARE", actor.userId(), actor.workshopId());
            var aggregate = materialize(jdbc, PerfilAgregadoLegal.AUTHENTICATED_PENDING, AudienciaLegal.valueOf(actor.audience()));
            var lot = insertLot(jdbc, actor, aggregate);
            List<Act> acts;
            if (ordinal == null) acts = insertActs(jdbc, lot);
            else {
                UUID requirement = jdbc.queryForList("SELECT m.requisito_version_id FROM legal_requisito_agregado_scopes s JOIN legal_requisito_conjunto_miembros m ON m.conjunto_id=s.conjunto_id WHERE s.agregado_id=? ORDER BY s.scope_ordinal,m.manifest_ordinal", UUID.class, aggregate.aggregateId()).get(ordinal);
                UUID id = UUID.randomUUID();
                jdbc.update("INSERT INTO legal_aceptaciones (id,lote_id,user_id,taller_id,requisito_version_id,requisito_clave,requisito_version,contexto,tipo_acto,afirmacion,afirmacion_sha256,requerido) SELECT ?,?,?,?,v.id,l.clave,v.version,l.contexto,l.tipo_acto,v.afirmacion,v.afirmacion_sha256,v.requerido FROM legal_requisito_versiones v JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id WHERE v.id=?", id,lot.id(),actor.userId(),actor.workshopId(),requirement);
                acts=List.of(new Act(id,requirement));
            }
            insertDocuments(jdbc, acts);
            insertMetadata(jdbc, lot);
            connection.commit();
            return acts;
        }
    }

    private void assertReleased(boolean committed) {
        assertThat(metrics.snapshot().commits()).isEqualTo(committed ? 1 : 0);
        assertThat(metrics.snapshot().rollbacks()).isEqualTo(committed ? 0 : 1);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
    @Test void realReplacementsInheritWithoutNewActsAndAnIntermediateTrueKeepsBlocking() throws Exception {
        var actor = seedActor(owner, "USER");
        List<Act> acts = accept(actor, null);
        UUID originalEvidence = acts.getFirst().id();
        Map<String, List<String>> originalRows = evidenceRowsFor(actor);
        var apply = LegalManifestPersistenceITSupport.applyHarness(ownerDataSource, LegalDatabaseBudgets.production());
        var editorial = new LegalEditorialITFixture(directory, getClass(), owner,
                LegalManifestPersistenceITSupport.harness(ownerDataSource, LegalDatabaseBudgets.production()), apply);
        var source = seededEditorialRelease();

        source = replaceContinuedRequirement(editorial, apply, source, 2, false);
        var inherited = service.read(principal(actor));
        assertThat(inherited.requirements()).isEmpty();
        assertThat(inherited.decisions()).filteredOn(d -> d.requirementVersionId().equals(currentContinuedRequirement()))
                .singleElement().satisfies(decision -> {
                    assertThat(decision.satisfaction()).isEqualTo(LegalAuthenticatedRequirements.Satisfaction.INHERITED);
                    assertThat(decision.acceptanceId()).isEqualTo(originalEvidence);
                });
        assertThat(evidenceRowsFor(actor)).isEqualTo(originalRows);

        source = replaceContinuedRequirement(editorial, apply, source, 3, true);
        source = replaceContinuedRequirement(editorial, apply, source, 4, false);
        metrics.reset();
        var blocked = service.read(principal(actor));
        assertThat(blocked.requirements()).extracting(r -> r.versionId()).containsExactly(currentContinuedRequirement());
        assertThat(blocked.hasRequiredPending()).isTrue();
        assertThat(blocked.decisions()).filteredOn(d -> d.requirementVersionId().equals(currentContinuedRequirement()))
                .singleElement().satisfies(decision -> {
                    assertThat(decision.satisfaction()).isEqualTo(LegalAuthenticatedRequirements.Satisfaction.PENDING);
                    assertThat(decision.acceptanceId()).isNull();
                });
        assertThat(evidenceRowsFor(actor)).isEqualTo(originalRows);
        assertReleased(true);

        // A new explicit, committed base can satisfy the latest version; reading never fabricates it.
        UUID explicitEvidence = accept(actor, 0).getFirst().id();
        metrics.reset();
        var exact = service.read(principal(actor));
        assertThat(exact.requirements()).isEmpty();
        assertThat(exact.decisions()).filteredOn(d -> d.requirementVersionId().equals(currentContinuedRequirement()))
                .singleElement().satisfies(decision -> {
                    assertThat(decision.satisfaction()).isEqualTo(LegalAuthenticatedRequirements.Satisfaction.EXACT);
                    assertThat(decision.acceptanceId()).isEqualTo(explicitEvidence);
                });
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertReleased(true);
    }

    @Test void aReal129ReplacementIntervalRetainsAnEarlyIntermediateTrue() throws Exception {
        var actor = seedActor(owner, "USER");
        accept(actor, null);
        Map<String, List<String>> originalRows = evidenceRowsFor(actor);
        var apply = LegalManifestPersistenceITSupport.applyHarness(ownerDataSource, LegalDatabaseBudgets.production());
        var editorial = new LegalEditorialITFixture(directory, getClass(), owner,
                LegalManifestPersistenceITSupport.harness(ownerDataSource, LegalDatabaseBudgets.production()), apply);
        var source = seededEditorialRelease();
        // 130 persisted versions including the accepted base: the contractual (base,target] has129.
        // Every import, publication, transition and set is produced by real editorial services.
        // Only version2 requests reacceptance: dropping the oldest part of the interval would hide it.
        for (int version = 2; version <= 130; version++) {
            source = replaceContinuedRequirement(editorial, apply, source, version, version == 2);
        }
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM legal_requisito_versiones version
                JOIN legal_requisito_lineas line ON line.id = version.requisito_linea_id
                WHERE line.clave = 'private-continued-use-1'
                """, Long.class)).isEqualTo(130);
        metrics.reset();
        var result = service.read(principal(actor));
        assertThat(metrics.snapshot().rowsReadContaining("needed(line_id, from_ordinal, to_ordinal)",
                "public.legal_requisito_versiones")).isEqualTo(131);
        assertThat(result.requirements()).extracting(r -> r.versionId()).containsExactly(currentContinuedRequirement());
        assertThat(result.decisions()).filteredOn(d -> d.requirementVersionId().equals(currentContinuedRequirement()))
                .singleElement().satisfies(decision -> {
                    assertThat(decision.satisfaction()).isEqualTo(LegalAuthenticatedRequirements.Satisfaction.PENDING);
                    assertThat(decision.acceptanceId()).isNull();
                });
        assertThat(evidenceRowsFor(actor)).isEqualTo(originalRows);
        assertReleased(true);
    }

    private LegalEditorialITFixture.ImportedRelease seededEditorialRelease() {
        var validated = new com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator()
                .validate(directory.resolve("private-requirements/publication-manifest.json"));
        assertThat(validated.passed()).as("issues=%s", validated.issues()).isTrue();
        UUID publication = owner.queryForObject("""
                SELECT id FROM legal_publicaciones WHERE publication_external_id = 'private-requirements'
                """, UUID.class);
        return new LegalEditorialITFixture.ImportedRelease(validated.value().orElseThrow(), publication);
    }

    private LegalEditorialITFixture.ImportedRelease replaceContinuedRequirement(
            LegalEditorialITFixture editorial, LegalManifestPersistenceITSupport.ApplyHarness apply,
            LegalEditorialITFixture.ImportedRelease source, int version, boolean reacceptance) throws Exception {
        String sourceId = source.release().plan().manifest().publicationId();
        String externalId = "private-lineage-" + version;
        var sourceManifest = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                directory.resolve(sourceId).resolve("publication-manifest.json").toFile());
        var target = editorial.importedDraft(externalId, (path, manifest) -> {
            manifest.removeAll();
            manifest.setAll((com.fasterxml.jackson.databind.node.ObjectNode) sourceManifest.deepCopy());
            manifest.put("publicationId", externalId);
            for (var candidate : manifest.withArray("requirements")) {
                if ("private-continued-use-1".equals(candidate.path("key").asText())) {
                    var requirement = (com.fasterxml.jackson.databind.node.ObjectNode) candidate;
                    requirement.put("version", version + ".0.0");
                    requirement.put("requiresReacceptance", reacceptance);
                }
            }
        });
        var plan = editorial.replacementPlan(source, target, "private-lineage-replace-" + version);
        var result = apply.service().applyReplace(target.release(), plan.plan());
        assertThat(result.status()).as("issues=%s", result.issues()).isEqualTo(
                com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus.PASS);
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
        assertThat(result.persisted()).isTrue();
        return target;
    }

    private static UUID currentContinuedRequirement() {
        return owner.queryForObject("""
                SELECT version.id FROM legal_requisito_versiones version
                JOIN legal_requisito_lineas line ON line.id = version.requisito_linea_id
                WHERE line.clave = 'private-continued-use-1' AND version.estado = 'VIGENTE'
                """, UUID.class);
    }

    private static Map<String, List<String>> evidenceRowsFor(LegalPrivateRequirementsITSupport.Actor actor) {
        return Map.of(
                "lots", owner.queryForList("SELECT to_jsonb(e)::text || '|xmin=' || e.xmin::text FROM legal_aceptacion_lotes e WHERE e.user_id = ? AND e.taller_id = ? ORDER BY e.id", String.class, actor.userId(), actor.workshopId()),
                "acts", owner.queryForList("SELECT to_jsonb(e)::text || '|xmin=' || e.xmin::text FROM legal_aceptaciones e WHERE e.user_id = ? AND e.taller_id = ? ORDER BY e.id", String.class, actor.userId(), actor.workshopId()),
                "documents", owner.queryForList("SELECT to_jsonb(e)::text || '|xmin=' || e.xmin::text FROM legal_aceptacion_documentos e JOIN legal_aceptaciones act ON act.id = e.aceptacion_id WHERE act.user_id = ? AND act.taller_id = ? ORDER BY e.id", String.class, actor.userId(), actor.workshopId()));
    }

    @Test void historicalLotMustContainTheActInItsOwnAggregateSnapshot() throws Exception {
        var actor = seedActor(owner, "ADMIN");
        accept(actor, null);
        LegalRequiredSetAggregateReceipt registration;
        try (var connection = transaction(ownerDataSource)) {
            registration = materialize(jdbc(connection), PerfilAgregadoLegal.REGISTRATION, AudienciaLegal.ADMIN_TITULAR);
            connection.commit();
        }
        // Fault injection keeps the FK tuple valid while attaching continued-use acts to registration.
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE legal_aceptacion_lotes SET agregado_id = ?, perfil = 'REGISTRATION', required_set_revision = ?
                 WHERE user_id = ? AND taller_id = ?
                """, registration.aggregateId(), registration.requiredSetRevision(), actor.userId(), actor.workshopId()));
        metrics.reset();
        assertThatThrownBy(() -> service.read(principal(actor))).isInstanceOf(LegalPrivateRequirementsReadException.class);
        assertReleased(false);
    }

    @Test void acceptanceCommittedWhileReaderWaitsForActorIsObservedCompletely() throws Exception {
        var source = seedActor(owner, "USER");
        var actor = new LegalAcceptanceProtocolFeasibilityITSupport.Actor(source.userId(), source.workshopId(), source.role(), source.audience());
        try (var connection = transaction(ownerDataSource);
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var writer = jdbc(connection);
            sharedBoundary(writer);
            long writerPid = writer.queryForObject("SELECT pg_backend_pid()", Long.class);
            writer.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(jsonb_build_array('ordenfix:legal-actor:v1', ?::bigint, ?::bigint)::text,0))", actor.workshopId(), actor.userId());
            var waiting = executor.submit(() -> service.read(principal(source)));
            long limit = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            boolean blocked = false;
            while (!blocked && System.nanoTime() < limit && !waiting.isDone()) {
                blocked = Boolean.TRUE.equals(owner.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks w JOIN pg_catalog.pg_locks held
                          ON held.locktype = w.locktype AND held.database = w.database
                         AND held.classid = w.classid AND held.objid = w.objid AND held.objsubid = w.objsubid
                         WHERE w.locktype = 'advisory' AND NOT w.granted AND held.granted
                           AND held.pid = ? AND w.pid <> held.pid)
                        """, Boolean.class, writerPid));
                if (!blocked) Thread.sleep(5);
            }
            assertThat(blocked).as("reader waits for this writer's actor advisory").isTrue();
            var aggregate = materialize(writer, PerfilAgregadoLegal.AUTHENTICATED_PENDING, AudienciaLegal.USER);
            var lot = insertLot(writer, actor, aggregate);
            var acts = insertActs(writer, lot);
            insertDocuments(writer, acts);
            insertMetadata(writer, lot);
            connection.commit();
            var observed = waiting.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(observed.requirements()).isEmpty();
            assertThat(observed.decisions()).extracting(LegalAuthenticatedRequirements.Decision::acceptanceId)
                    .containsExactlyElementsOf(acts.stream().map(Act::id).toList());
            assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
            assertReleased(true);
        }
    }

    @Test
    void genuineScopeV1HistorySurvivesUpgradeEvenWhenItsTransactionStartedBeforePublication() throws Exception {
        // A separate fresh database is required: V28 forbids creating new SCOPE_V1 lots.
        String historyDatabase = "ordenfix_legal_private_requirements_scope_history";
        String historyRole = "ordenfix_legal_private_requirements_scope_history_reader";
        String historyPassword = "scope-history-reader-test-only";
        requireSafeEphemeralDatabase(owner);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_database WHERE datname = ?",
                Integer.class, historyDatabase)).isZero();
        owner.execute("CREATE DATABASE " + historyDatabase);
        try {
            String historyUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                    + POSTGRES.getMappedPort(5432) + "/" + historyDatabase;
            DataSource historyDataSource = new DriverManagerDataSource(historyUrl,
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            JdbcTemplate historyOwner = new JdbcTemplate(historyDataSource);
            org.flywaydb.core.Flyway.configure().dataSource(historyDataSource)
                    .locations("classpath:db/migration").target("27").load().migrate();
            assertThat(historyOwner.queryForObject(
                    "SELECT max(version::integer) FROM flyway_schema_history WHERE success",
                    Integer.class)).isEqualTo(27);
            UUID legacyLot = UUID.randomUUID();
            List<UUID> acceptanceIds = new java.util.ArrayList<>();
            LegalPrivateRequirementsITSupport.Actor historicalActor;
            java.time.OffsetDateTime startedAt;
            try (var historicalConnection = historyDataSource.getConnection()) {
                historicalConnection.setAutoCommit(false);
                historicalConnection.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
                JdbcTemplate historicalTx = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        historicalConnection, true));
                startedAt = historicalTx.queryForObject("SELECT transaction_timestamp()", java.time.OffsetDateTime.class);
                // The old transaction has acquired no legal locks. Another connection publishes
                // the catalogue and actor before V27 observes them at READ_COMMITTED.
                seedCatalog(historyOwner, directory.resolve("scope-v27-history"), getClass());
                historicalActor = seedActor(historyOwner, "USER");
                String scopeRevision = historicalTx.queryForObject("""
                        SELECT c.required_set_revision FROM legal_requisito_conjuntos_actuales p
                          JOIN legal_requisito_conjuntos c ON c.id = p.conjunto_id
                         WHERE p.contexto = 'USO_CONTINUADO' AND p.locale = 'es-AR' AND p.audiencia = 'USER'
                        """, String.class);
                assertThat(historicalTx.update("""
                        INSERT INTO legal_aceptacion_lotes
                            (id, user_id, taller_id, rol_wire, audiencia, required_set_revision, aceptado_en)
                        VALUES (?, ?, ?, 'USER', 'USER', ?, statement_timestamp())
                        """, legacyLot, historicalActor.userId(), historicalActor.workshopId(), scopeRevision)).isEqualTo(1);
                List<UUID> requirements = historicalTx.queryForList("""
                        SELECT m.requisito_version_id FROM legal_requisito_conjuntos_actuales p
                          JOIN legal_requisito_conjunto_miembros m ON m.conjunto_id = p.conjunto_id
                         WHERE p.contexto = 'USO_CONTINUADO' AND p.locale = 'es-AR' AND p.audiencia = 'USER'
                         ORDER BY m.manifest_ordinal
                        """, UUID.class);
                assertThat(requirements).hasSize(2);
                for (UUID requirement : requirements) {
                    UUID acceptance = UUID.randomUUID();
                    acceptanceIds.add(acceptance);
                    assertThat(historicalTx.update("""
                            INSERT INTO legal_aceptaciones
                                (id, lote_id, user_id, taller_id, requisito_version_id, requisito_clave,
                                 requisito_version, contexto, tipo_acto, afirmacion, afirmacion_sha256, requerido)
                            SELECT ?, ?, ?, ?, v.id, l.clave, v.version, l.contexto, l.tipo_acto,
                                   v.afirmacion, v.afirmacion_sha256, v.requerido
                              FROM legal_requisito_versiones v JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id
                             WHERE v.id=?
                            """, acceptance, legacyLot, historicalActor.userId(), historicalActor.workshopId(), requirement))
                            .isEqualTo(1);
                    assertThat(historicalTx.update("""
                            INSERT INTO legal_aceptacion_documentos
                                (aceptacion_id, documento_ordinal, documento_version_id, documento_clave,
                                 tipo, version, titulo, sha256)
                            SELECT ?, r.documento_ordinal, v.id, l.clave, l.tipo, v.version, v.titulo, v.sha256
                              FROM legal_requisito_documentos r
                              JOIN legal_documento_versiones v ON v.id=r.documento_version_id
                              JOIN legal_documento_lineas l ON l.id=v.documento_linea_id
                             WHERE r.requisito_version_id=?
                            """, acceptance, requirement)).isEqualTo(1);
                }
                assertThat(historicalTx.update("""
                        INSERT INTO legal_aceptacion_metadatos (lote_id, capturado_en, retener_hasta)
                        VALUES (?, statement_timestamp(), statement_timestamp() + INTERVAL '30 days')
                        """, legacyLot)).isEqualTo(1);
                // Synthetic ciphertext is restricted to this owner-side historical fixture.
                assertThat(historicalTx.update("""
                        INSERT INTO legal_aceptacion_metadatos_cifrados
                            (lote_id, tipo, key_version, nonce, ciphertext, tag, longitud_original)
                        VALUES (?, 'IP', 1, ?, ?, ?, 9)
                        """, legacyLot, new byte[12], new byte[9], new byte[16])).isEqualTo(1);
                assertThat(historicalTx.queryForObject(
                        "SELECT aceptado_en FROM legal_aceptacion_lotes WHERE id=?",
                        java.time.OffsetDateTime.class, legacyLot)).isEqualTo(startedAt);
                assertThat(startedAt.toInstant()).isBefore(historicalTx.queryForObject("""
                        SELECT min(t.ocurrido_en) FROM legal_requisito_transiciones t
                          JOIN legal_requisito_versiones v ON v.id=t.requisito_version_id
                          JOIN legal_requisito_lineas l ON l.id=v.requisito_linea_id
                         WHERE l.contexto='USO_CONTINUADO' AND t.estado_nuevo='VIGENTE'
                        """, java.time.OffsetDateTime.class).toInstant());
                // Trigger guards and every deferred constraint remain enabled throughout.
                historicalTx.execute("SET CONSTRAINTS ALL IMMEDIATE");
                historicalConnection.commit();
            }
            Map<String, List<String>> historicalRows = captureLegacyPrivateEvidence(historyOwner);
            assertThat(historicalRows.get("legal_aceptaciones")).hasSize(2);
            assertThat(historicalRows.get("legal_aceptacion_documentos")).hasSize(2);
            org.flywaydb.core.Flyway.configure().dataSource(historyDataSource)
                    .locations("classpath:db/migration").load().migrate();
            assertThat(historyOwner.queryForObject(
                    "SELECT max(version::integer) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(34);
            assertThat(historyOwner.queryForMap("""
                    SELECT revision_scheme, perfil, agregado_id FROM legal_aceptacion_lotes WHERE id=?
                    """, legacyLot)).containsEntry("revision_scheme", "SCOPE_V1")
                    .containsEntry("perfil", null).containsEntry("agregado_id", null);
            assertThat(captureLegacyPrivateEvidence(historyOwner)).isEqualTo(historicalRows);
            provision(historyOwner, historyRole, historyPassword);
            try (var historicalContext = new AnnotationConfigApplicationContext()) {
                String prefix = LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
                historicalContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("scope-history", Map.of(
                        LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true",
                        prefix + "jdbc-url", historyUrl, prefix + "username", historyRole,
                        prefix + "password", historyPassword)));
                historicalContext.register(LegalPrivateRequirementsDatabaseConfiguration.class);
                historicalContext.refresh();
                var historicalService = historicalContext.getBean(LegalPrivateRequirementsReadService.class);
                var result = historicalService.read(principal(historicalActor));
                assertThat(result.requirements()).isEmpty();
                assertThat(result.snapshot().requirements()).hasSize(2);
                assertThat(result.decisions()).allSatisfy(decision -> assertThat(decision.satisfaction())
                        .isEqualTo(LegalAuthenticatedRequirements.Satisfaction.EXACT));
                assertThat(result.decisions()).extracting(LegalAuthenticatedRequirements.Decision::acceptanceId)
                        .containsExactlyElementsOf(acceptanceIds);
                assertThat(result.hasRequiredPending()).isFalse();
                assertThat(captureLegacyPrivateEvidence(historyOwner)).isEqualTo(historicalRows);
                assertThat(historicalContext.getBean(HikariDataSource.class).getHikariPoolMXBean()
                        .getActiveConnections()).isZero();
            }
        } finally {
            // Only the exact database created above inside this disposable container is removed.
            owner.execute("DROP DATABASE " + historyDatabase + " WITH (FORCE)");
        }
    }

    private static Map<String, List<String>> captureLegacyPrivateEvidence(JdbcTemplate source) {
        requireSafeEphemeralDatabase(source);
        Map<String, List<String>> captured = new java.util.LinkedHashMap<>();
        for (String table : List.of("legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
                "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados")) {
            String legacyColumns = table.equals("legal_aceptacion_lotes")
                    ? "to_jsonb(t) - ARRAY['revision_scheme','perfil','agregado_id']" : "to_jsonb(t)";
            captured.put(table, source.queryForList("SELECT (" + legacyColumns
                    + ")::text || '|' || xmin::text FROM public." + table + " t ORDER BY 1", String.class));
        }
        return Map.copyOf(captured);
    }

// Add to LegalPrivateRequirementsReadServiceIT; reuses its existing setup and lineage helpers.
    @Test void anActivatedHistoricalDocumentCannotHaveAnEffectiveDateAfterItsActivation() throws Exception {
        var actor = seedActor(owner, "USER");
        accept(actor, null);
        var originalEvidence = evidenceRowsFor(actor);
        UUID oldTerms = owner.queryForObject("""
                SELECT version.id FROM legal_documento_versiones version
                JOIN legal_documento_lineas line ON line.id = version.documento_linea_id
                WHERE line.clave = 'terminos' AND version.estado = 'VIGENTE'
                """, UUID.class);
        var apply = LegalManifestPersistenceITSupport.applyHarness(ownerDataSource, LegalDatabaseBudgets.production());
        var editorial = new LegalEditorialITFixture(directory, getClass(), owner,
                LegalManifestPersistenceITSupport.harness(ownerDataSource, LegalDatabaseBudgets.production()), apply);
        var source = seededEditorialRelease();
        var sourceManifest = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                directory.resolve("private-requirements/publication-manifest.json").toFile());
        String externalId = "private-document-lineage-2";
        var target = editorial.importedDraft(externalId, (path, manifest) -> {
            manifest.removeAll();
            manifest.setAll((com.fasterxml.jackson.databind.node.ObjectNode) sourceManifest.deepCopy());
            manifest.put("publicationId", externalId);
            for (var candidate : manifest.withArray("documents")) {
                if ("terminos".equals(candidate.path("key").asText())) {
                    var document = (com.fasterxml.jackson.databind.node.ObjectNode) candidate;
                    document.put("version", "2.0.0");
                    document.put("requiresReacceptance", false);
                }
            }
            for (var candidate : manifest.withArray("requirements")) {
                boolean referencesTerms = false;
                for (var document : candidate.withArray("documents")) {
                    referencesTerms |= "terminos".equals(document.asText());
                }
                if (referencesTerms) {
                    var requirement = (com.fasterxml.jackson.databind.node.ObjectNode) candidate;
                    requirement.put("version", "2.0.0");
                    requirement.put("requiresReacceptance", false);
                }
            }
        });
        var plan = editorial.replacementPlan(source, target, "private-document-lineage-replace-2");
        var replacement = apply.service().applyReplace(target.release(), plan.plan());
        assertThat(replacement.status()).as("issues=%s", replacement.issues()).isEqualTo(
                com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus.PASS);
        assertThat(replacement.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
        assertThat(replacement.persisted()).isTrue();
        assertThat(owner.queryForObject("SELECT estado FROM legal_documento_versiones WHERE id = ?",
                String.class, oldTerms)).isEqualTo("REEMPLAZADA");
        var inherited = service.read(principal(actor));
        assertThat(inherited.requirements()).isEmpty();
        assertThat(inherited.decisions()).allSatisfy(decision ->
                assertThat(decision.satisfaction()).isEqualTo(LegalAuthenticatedRequirements.Satisfaction.INHERITED));
        assertThat(evidenceRowsFor(actor)).isEqualTo(originalEvidence);
        var before = counts(owner);

        // Explicit fault injection: V27 normally rejects a VIGENTE transition before vigente_desde.
        // The old document is no longer current, so only historical accreditation can detect this.
        LegalManifestPersistenceITSupport.withReplicaRole(owner, () -> owner.update("""
                UPDATE legal_documento_versiones
                   SET vigente_desde = statement_timestamp() + INTERVAL '1 day' WHERE id = ?
                """, oldTerms));
        metrics.reset();
        assertThatThrownBy(() -> service.read(principal(actor)))
                .isInstanceOf(LegalPrivateRequirementsReadException.class)
                .hasMessage("El contrato de requisitos no está disponible");
        assertThat(counts(owner)).isEqualTo(before);
        assertThat(evidenceRowsFor(actor)).isEqualTo(originalEvidence);
        assertThat(metrics.snapshot().executions(LegalJdbcMetricsSupport.Category.DML)).isZero();
        assertReleased(false);
    }

}
