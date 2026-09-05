package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Owner-only fixture setup and expectations for dedicated ephemeral PUBLIC_REQUIREMENTS databases. */
final class LegalPublicRequirementsReadITSupport {

    private static final String SAFE_DATABASE_PREFIX = "ordenfix_legal_public_requirements_";

    private LegalPublicRequirementsReadITSupport() { }

    static void clearCatalog(JdbcTemplate owner) {
        requireEphemeral(owner);
        owner.execute("""
                TRUNCATE public.legal_requisito_agregados, public.legal_publicaciones,
                         public.legal_documento_reemplazo_lotes RESTART IDENTITY CASCADE
                """);
    }

    static Seed seedRegistration(JdbcTemplate owner, Path directory, String externalId) throws Exception {
        Editorial setup = editorial(owner, directory);
        return snapshot(owner, setup.fixture().readyRelease(externalId));
    }

    /** Adds an optional member at manifest ordinal 7; REGISTRO legitimately keeps ordinals 1 and 7. */
    static Seed seedRegistrationWithOptional(JdbcTemplate owner, Path directory, String externalId)
            throws Exception {
        Editorial setup = editorial(owner, directory);
        var imported = setup.fixture().importedDraft(externalId, (path, manifest) -> {
            ObjectNode optional = manifest.withArray("requirements").addObject();
            String statement = "Leí el aviso opcional de registro de OrdenFix.";
            optional.put("key", "optional-registration");
            optional.put("version", "1.0.0");
            optional.put("context", "REGISTRO");
            optional.putArray("roles").add("ADMIN_TITULAR");
            optional.put("actType", "LECTURA");
            optional.put("statement", statement);
            optional.put("statementSha256", sha256(statement));
            optional.putArray("documents").add("privacidad").add("terminos");
            optional.put("required", false);
            optional.put("requiresReacceptance", true);
        });
        requireApplied(setup.apply().service().applyPromote(imported.release()));
        return snapshot(owner, imported);
    }

    /** Changes one editorial line through a real REPLACE; other lines may retain older introduction IDs. */
    static Seed replaceDocument(JdbcTemplate owner, Path directory, Seed source, String key,
                                String version, String externalId) throws Exception {
        Editorial setup = editorial(owner, directory);
        var target = setup.fixture().importedDocumentRevision(externalId, key, version);
        var plan = setup.fixture().replacementPlan(source.release(), target, externalId + "-replace");
        requireApplied(setup.apply().service().applyReplace(target.release(), plan.plan()));
        return snapshot(owner, target);
    }

    static Map<String, Long> aggregateCounts(JdbcTemplate owner) {
        requireEphemeral(owner);
        return Map.of(
                "legal_requisito_agregados", owner.queryForObject(
                        "SELECT count(*) FROM public.legal_requisito_agregados", Long.class),
                "legal_requisito_agregado_scopes", owner.queryForObject(
                        "SELECT count(*) FROM public.legal_requisito_agregado_scopes", Long.class));
    }

    /** Independent owner observation for fixture expectations, outside the instrumented consumer. */
    private static Seed snapshot(JdbcTemplate owner, LegalEditorialITFixture.ImportedRelease release) {
        requireEphemeral(owner);
        UUID setId = owner.queryForObject("""
                SELECT id FROM public.legal_requisito_conjuntos
                 WHERE publicacion_id = ? AND locale = 'es-AR'
                   AND contexto = 'REGISTRO' AND audiencia = 'ADMIN_TITULAR'
                """, UUID.class, release.publicationId());
        List<RequirementRow> requirements = owner.query("""
                SELECT version.id, line.contexto, line.tipo_acto, version.afirmacion,
                       version.afirmacion_sha256, version.requerido
                  FROM public.legal_requisito_conjunto_miembros member
                  JOIN public.legal_requisito_versiones version ON version.id = member.requisito_version_id
                  JOIN public.legal_requisito_lineas line ON line.id = version.requisito_linea_id
                 WHERE member.conjunto_id = ? ORDER BY member.manifest_ordinal
                """, (row, number) -> new RequirementRow(row.getObject("id", UUID.class),
                ContextoLegal.valueOf(row.getString("contexto")), TipoActoLegal.valueOf(row.getString("tipo_acto")),
                row.getString("afirmacion"), row.getString("afirmacion_sha256"), row.getBoolean("requerido")), setId);
        List<DocumentRow> documents = owner.query("""
                SELECT reference.requisito_version_id, document.id, line.tipo, document.version,
                       document.titulo, document.contenido_markdown, document.sha256,
                       document.vigente_desde, line.locale
                  FROM public.legal_requisito_conjunto_miembros member
                  JOIN public.legal_requisito_documentos reference
                    ON reference.requisito_version_id = member.requisito_version_id
                  JOIN public.legal_documento_versiones document ON document.id = reference.documento_version_id
                  JOIN public.legal_documento_lineas line ON line.id = document.documento_linea_id
                 WHERE member.conjunto_id = ? ORDER BY member.manifest_ordinal, reference.documento_ordinal
                """, (row, number) -> new DocumentRow(row.getObject("requisito_version_id", UUID.class),
                new DocumentProjection(row.getObject("id", UUID.class),
                        TipoDocumentoLegal.valueOf(row.getString("tipo")), row.getString("version"),
                        row.getString("titulo"), row.getString("contenido_markdown"), row.getString("sha256"),
                        row.getObject("vigente_desde", OffsetDateTime.class),
                        LocaleLegal.fromCodigo(row.getString("locale")))), setId);
        Map<UUID, List<DocumentProjection>> byRequirement = new LinkedHashMap<>();
        documents.forEach(document -> byRequirement.computeIfAbsent(document.requirementId(),
                ignored -> new ArrayList<>()).add(document.document()));
        LegalRequiredSetProjection projection = new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR,
                requirements.stream().map(requirement -> new RequirementProjection(requirement.id(),
                        requirement.context(), requirement.act(), requirement.statement(), requirement.sha256(),
                        byRequirement.getOrDefault(requirement.id(), List.of()), requirement.required())).toList());
        return new Seed(release, setId, projection);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Editorial editorial(JdbcTemplate owner, Path directory) {
        requireEphemeral(owner);
        var dataSource = Objects.requireNonNull(owner.getDataSource());
        var apply = LegalManifestPersistenceITSupport.applyHarness(dataSource, LegalDatabaseBudgets.production());
        return new Editorial(new LegalEditorialITFixture(directory, LegalPublicRequirementsReadITSupport.class, owner,
                LegalManifestPersistenceITSupport.harness(dataSource, LegalDatabaseBudgets.production()), apply), apply);
    }

    private static void requireApplied(LegalEditorialApplyResult result) {
        assertThat(result.status()).as("issues=%s", result.issues()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
    }

    private static void requireEphemeral(JdbcTemplate owner) {
        String database = Objects.requireNonNull(owner).queryForObject("SELECT current_database()", String.class);
        if (database == null || !database.startsWith(SAFE_DATABASE_PREFIX)) {
            throw new IllegalStateException("El fixture requiere una base efímera dedicada de requisitos públicos");
        }
    }

    record Seed(LegalEditorialITFixture.ImportedRelease release, UUID requiredSetId,
                LegalRequiredSetProjection projection) {
        Seed {
            Objects.requireNonNull(release);
            Objects.requireNonNull(requiredSetId);
            Objects.requireNonNull(projection);
        }

        UUID publicationId() {
            return release.publicationId();
        }
    }

    private record RequirementRow(UUID id, ContextoLegal context, TipoActoLegal act,
                                  String statement, String sha256, boolean required) { }
    private record DocumentRow(UUID requirementId, DocumentProjection document) { }
    private record Editorial(LegalEditorialITFixture fixture,
                             LegalManifestPersistenceITSupport.ApplyHarness apply) { }
}
