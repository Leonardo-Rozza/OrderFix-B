package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadITSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;

/** Real import/editorial fixtures shared by capacity and deadline ITs, never a production provisioner. */
final class LegalPublicRequirementsCapacityITSupport {

    static final int MAX_REGISTRATION_IN_COMPLETE_FIXTURE = 251;
    static final long MAX_EXPANDED_BYTES = 16_777_216L;

    private LegalPublicRequirementsCapacityITSupport() { }

    static Seed seedRegistration(JdbcTemplate owner, Path directory, String externalId, int registrationCount)
            throws Exception {
        return seed(owner, directory, externalId, registrationCount, false);
    }

    /** 256 publication requirements, 251 REGISTRO, 253 references, three current document UUIDs, exactly 16 MiB. */
    static Seed seedMaximumRegistration(JdbcTemplate owner, Path directory, String externalId) throws Exception {
        return seed(owner, directory, externalId, MAX_REGISTRATION_IN_COMPLETE_FIXTURE, true);
    }

    private static Seed seed(JdbcTemplate owner, Path directory, String id, int count, boolean maximumBytes)
            throws Exception {
        requireCount(count);
        Editorial setup = editorial(owner, directory);
        var imported = setup.fixture().importedDraft(id,
                (path, manifest) -> capacityManifest(path, manifest, count, maximumBytes, false));
        requireApplied(setup.apply().service().applyPromote(imported.release()));
        return snapshot(owner, imported, maximumBytes);
    }

    /** Changes only the final registration requirement through real REPLACE; all documents and other members are reused. */
    static Seed replaceLastRequirement(JdbcTemplate owner, Path directory, String externalId, Seed current)
            throws Exception {
        Editorial setup = editorial(owner, directory);
        var imported = setup.fixture().importedDraft(externalId, (path, manifest) -> capacityManifest(path, manifest,
                current.projection().requirements().size(), current.maximumBytes(), true));
        var plan = setup.fixture().replacementPlan(current.release(), imported, externalId + "-replace");
        requireApplied(setup.apply().service().applyReplace(imported.release(), plan.plan()));
        return snapshot(owner, imported, current.maximumBytes());
    }

    /** Thirteen publications (one PROMOTE and twelve REPLACE operations) give 143 versions, eleven current. */
    static Seed seedHistory(JdbcTemplate owner, Path directory, String prefix, int publications) throws Exception {
        if (publications < 1) {
            throw new IllegalArgumentException("publications debe ser positivo");
        }
        Editorial setup = editorial(owner, directory);
        var current = setup.fixture().readyRelease(prefix + "-0");
        for (int index = 1; index < publications; index++) {
            String version = (index + 1) + ".0.0";
            var next = setup.fixture().importedDraft(prefix + "-" + index, (path, manifest) -> {
                manifest.withArray("documents").forEach(candidate -> ((ObjectNode) candidate).put("version", version));
                manifest.withArray("requirements").forEach(candidate -> ((ObjectNode) candidate).put("version", version));
            });
            var plan = setup.fixture().replacementPlan(current, next, prefix + "-replace-" + index);
            requireApplied(setup.apply().service().applyReplace(next.release(), plan.plan()));
            current = next;
        }
        return snapshot(owner, current, false);
    }

    private static void capacityManifest(Path manifestPath, ObjectNode manifest, int registrationCount,
                                         boolean maximumBytes, boolean changeLast) throws Exception {
        requireCount(registrationCount);
        if (maximumBytes) {
            if (registrationCount != MAX_REGISTRATION_IN_COMPLETE_FIXTURE) {
                throw new IllegalArgumentException("El límite exacto requiere la combinación editorial de 251 miembros");
            }
            for (JsonNode candidate : manifest.withArray("documents")) {
                ObjectNode document = (ObjectNode) candidate;
                int bytes = switch (document.path("key").asText()) {
                    case "terminos" -> 65_536;
                    case "privacidad", "tratamiento-datos" -> 163_840;
                    default -> 0;
                };
                if (bytes > 0) {
                    String text = markdown(bytes, document.path("key").asText());
                    Files.writeString(manifestPath.getParent().resolve(document.path("source").asText()),
                            text, StandardCharsets.UTF_8);
                    document.put("sha256", sha256(text));
                }
            }
        }
        var requirements = manifest.withArray("requirements");
        assertThat(requirements.size()).isEqualTo(6);
        for (int index = 1; index < registrationCount; index++) {
            ObjectNode requirement = requirements.addObject();
            String statement = "Leí el requisito de capacidad %03d.".formatted(index);
            requirement.put("key", "registration-capacity-%03d".formatted(index));
            requirement.put("version", "1.0.0");
            requirement.put("context", "REGISTRO");
            requirement.putArray("roles").add("ADMIN_TITULAR");
            requirement.put("actType", "LECTURA");
            requirement.put("statement", statement);
            requirement.put("statementSha256", sha256(statement));
            requirement.putArray("documents").add("terminos");
            requirement.put("required", false);
            requirement.put("requiresReacceptance", true);
        }
        if (changeLast) {
            ObjectNode last = (ObjectNode) requirements.get(registrationCount == 1 ? 0 : requirements.size() - 1);
            String changed = last.path("statement").asText() + " Actualización final.";
            last.put("version", "2.0.0");
            last.put("statement", changed);
            last.put("statementSha256", sha256(changed));
        }
    }

    private static String markdown(int byteCount, String key) {
        String prefix = "# Capacidad legal " + key + "\n\n";
        String unit = "Contenido \"visible\" \\ con tab\t y linea\n";
        int remaining = byteCount - prefix.length();
        String text = prefix + unit.repeat(remaining / unit.length()) + "a".repeat(remaining % unit.length());
        assertThat(text.getBytes(StandardCharsets.UTF_8)).hasSize(byteCount);
        return text;
    }

    private static Seed snapshot(JdbcTemplate owner, LegalEditorialITFixture.ImportedRelease release,
                                 boolean maximumBytes) {
        requireEphemeral(owner);
        UUID setId = owner.queryForObject("""
                SELECT id FROM public.legal_requisito_conjuntos WHERE publicacion_id = ?
                   AND contexto = 'REGISTRO' AND locale = 'es-AR' AND audiencia = 'ADMIN_TITULAR'
                """, UUID.class, release.publicationId());
        List<RequirementRow> requirements = owner.query("""
                SELECT version.id, line.tipo_acto, version.afirmacion, version.afirmacion_sha256, version.requerido
                  FROM public.legal_requisito_conjunto_miembros member
                  JOIN public.legal_requisito_versiones version ON version.id = member.requisito_version_id
                  JOIN public.legal_requisito_lineas line ON line.id = version.requisito_linea_id
                 WHERE member.conjunto_id = ? ORDER BY member.manifest_ordinal
                """, (row, ordinal) -> new RequirementRow(row.getObject("id", UUID.class),
                TipoActoLegal.valueOf(row.getString("tipo_acto")), row.getString("afirmacion"),
                row.getString("afirmacion_sha256"), row.getBoolean("requerido")), setId);
        Map<UUID, DocumentProjection> uniqueDocuments = new LinkedHashMap<>();
        owner.query("""
                SELECT document.id, line.tipo, document.version, document.titulo, document.contenido_markdown,
                       document.sha256, document.vigente_desde
                  FROM public.legal_documento_versiones document
                  JOIN public.legal_documento_lineas line ON line.id = document.documento_linea_id
                 WHERE EXISTS (SELECT 1 FROM public.legal_requisito_conjunto_miembros member
                                 JOIN public.legal_requisito_documentos reference
                                   ON reference.requisito_version_id = member.requisito_version_id
                                WHERE member.conjunto_id = ? AND reference.documento_version_id = document.id)
                """, row -> {
            UUID id = row.getObject("id", UUID.class);
            uniqueDocuments.put(id, new DocumentProjection(id, TipoDocumentoLegal.valueOf(row.getString("tipo")),
                    row.getString("version"), row.getString("titulo"), row.getString("contenido_markdown"),
                    row.getString("sha256"), row.getObject("vigente_desde", OffsetDateTime.class), LocaleLegal.ES_AR));
        }, setId);
        Map<UUID, List<DocumentProjection>> references = new LinkedHashMap<>();
        owner.query("""
                SELECT reference.requisito_version_id, reference.documento_version_id
                  FROM public.legal_requisito_conjunto_miembros member
                  JOIN public.legal_requisito_documentos reference ON reference.requisito_version_id = member.requisito_version_id
                 WHERE member.conjunto_id = ? ORDER BY member.manifest_ordinal, reference.documento_ordinal
                """, row -> {
            references.computeIfAbsent(row.getObject("requisito_version_id", UUID.class), ignored -> new ArrayList<>())
                    .add(Objects.requireNonNull(uniqueDocuments.get(row.getObject("documento_version_id", UUID.class))));
        }, setId);
        var projection = new LegalRequiredSetProjection(ContextoLegal.REGISTRO, LocaleLegal.ES_AR,
                requirements.stream().map(requirement -> new RequirementProjection(requirement.id(), ContextoLegal.REGISTRO,
                        requirement.type(), requirement.statement(), requirement.digest(),
                        references.get(requirement.id()), requirement.required())).toList());
        long expanded = projection.requirements().stream().flatMap(requirement -> requirement.documents().stream())
                .mapToLong(document -> document.markdown().getBytes(StandardCharsets.UTF_8).length).sum();
        long distinct = uniqueDocuments.values().stream()
                .mapToLong(document -> document.markdown().getBytes(StandardCharsets.UTF_8).length).sum();
        return new Seed(release, setId, projection, expanded, distinct, maximumBytes);
    }

    private static void requireCount(int count) {
        if (count < 1 || count > MAX_REGISTRATION_IN_COMPLETE_FIXTURE) {
            throw new IllegalArgumentException("El fixture completo reserva cinco requisitos para los demás contextos");
        }
    }

    private static Editorial editorial(JdbcTemplate owner, Path directory) {
        requireEphemeral(owner);
        var dataSource = Objects.requireNonNull(owner.getDataSource());
        var apply = LegalManifestPersistenceITSupport.applyHarness(dataSource, LegalDatabaseBudgets.production());
        return new Editorial(new LegalEditorialITFixture(directory, LegalPublicRequirementsCapacityITSupport.class,
                owner, LegalManifestPersistenceITSupport.harness(dataSource, LegalDatabaseBudgets.production()), apply), apply);
    }

    private static void requireEphemeral(JdbcTemplate owner) {
        String database = owner.queryForObject("SELECT current_database()", String.class);
        if (database == null || !database.startsWith("ordenfix_legal_public_requirements_")) {
            throw new IllegalStateException("El fixture requiere una base efímera dedicada de requisitos públicos");
        }
    }

    private static void requireApplied(LegalEditorialApplyResult result) {
        assertThat(result.status()).as("issues=%s", result.issues()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
    }

    record Seed(LegalEditorialITFixture.ImportedRelease release, UUID requiredSetId,
                LegalRequiredSetProjection projection, long expandedMarkdownBytes, long distinctMarkdownBytes,
                boolean maximumBytes) {
        UUID publicationId() { return release.publicationId(); }
    }

    private record RequirementRow(UUID id, TipoActoLegal type, String statement, String digest, boolean required) { }
    private record Editorial(LegalEditorialITFixture fixture, LegalManifestPersistenceITSupport.ApplyHarness apply) { }
}
