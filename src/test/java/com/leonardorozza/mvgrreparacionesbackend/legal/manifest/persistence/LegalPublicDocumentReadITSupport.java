package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner-only setup for ephemeral public-reader tests. Import, PROMOTE and REPLACE retain their
 * real PostgreSQL guards; the existing owner harness bypasses only the offline credential check.
 * No reader role, transaction, preflight or SELECT is replaced by this fixture.
 */
final class LegalPublicDocumentReadITSupport {

    private static final String SAFE_DATABASE_PREFIX = "ordenfix_legal_public_document_";
    static final Comparator<UUID> POSTGRES_UUID_ORDER = (left, right) -> {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };
    static final Comparator<StoredDocument> DOCUMENT_ORDER =
            Comparator.comparing(StoredDocument::type)
                    .thenComparing(StoredDocument::effectiveAt, Comparator.reverseOrder())
                    .thenComparing(StoredDocument::id, POSTGRES_UUID_ORDER);

    private LegalPublicDocumentReadITSupport() { }

    static void clearCatalog(JdbcTemplate owner) {
        requireEphemeral(owner);
        owner.execute("""
                TRUNCATE TABLE public.legal_requisito_agregados, public.legal_publicaciones,
                               public.legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
    }

    static Seed seedDraft(JdbcTemplate owner, Path directory, String externalId) throws Exception {
        Editorial setup = editorial(owner, directory);
        return seed(owner, setup.fixture().importedDraft(externalId));
    }

    /** The golden catalog has eleven document versions and twenty-one historical contexts. */
    static Seed seedCatalog(JdbcTemplate owner, Path directory, String externalId) throws Exception {
        Editorial setup = editorial(owner, directory);
        return seed(owner, setup.fixture().readyRelease(externalId));
    }

    /** Each additional publication replaces all eleven versions through the real editorial path. */
    static Seed seedHistory(JdbcTemplate owner, Path directory, String prefix, int publicationCount)
            throws Exception {
        if (publicationCount < 1) {
            throw new IllegalArgumentException("publicationCount debe ser positivo");
        }
        Editorial setup = editorial(owner, directory);
        LegalEditorialITFixture.ImportedRelease current = setup.fixture().readyRelease(prefix + "-0");
        for (int index = 1; index < publicationCount; index++) {
            String version = (index + 1) + ".0.0";
            LegalEditorialITFixture.ImportedRelease next = setup.fixture().importedDraft(
                    prefix + "-" + index, (path, manifest) -> {
                        manifest.withArray("documents").forEach(candidate ->
                                ((ObjectNode) candidate).put("version", version));
                        manifest.withArray("requirements").forEach(candidate ->
                                ((ObjectNode) candidate).put("version", version));
                    });
            LegalEditorialITFixture.ReplaceFixture plan = setup.fixture().replacementPlan(
                    current, next, prefix + "-replace-" + index);
            requireApplied(setup.apply().service().applyReplace(next.release(), plan.plan()));
            current = next;
        }
        return seed(owner, current);
    }

    static Seed replaceDocument(JdbcTemplate owner, Path directory, Seed source, String key,
                                String version, String externalId) throws Exception {
        Editorial setup = editorial(owner, directory);
        LegalEditorialITFixture.ImportedRelease target =
                setup.fixture().importedDocumentRevision(externalId, key, version);
        LegalEditorialITFixture.ReplaceFixture plan = setup.fixture().replacementPlan(
                source.release(), target, externalId + "-replace");
        requireApplied(setup.apply().service().applyReplace(target.release(), plan.plan()));
        return seed(owner, target);
    }

    /** Imports a complete successor, then confirms its REPLACE in the real writer transaction. */
    static Seed replaceCatalog(JdbcTemplate owner, Path directory, String externalId, Seed current)
            throws Exception {
        return prepareReplacement(owner, directory, externalId, current).get();
    }

    /** Prepares before readers enter; get() performs only the actual REPLACE and its observation. */
    static Supplier<Seed> prepareReplacement(JdbcTemplate owner, Path directory, String externalId,
                                            Seed current) throws Exception {
        Editorial setup = editorial(owner, directory);
        String version = UUID.randomUUID().toString();
        LegalEditorialITFixture.ImportedRelease target = setup.fixture().importedDraft(
                externalId, (path, manifest) -> {
                    manifest.withArray("documents").forEach(candidate ->
                            ((ObjectNode) candidate).put("version", version));
                    manifest.withArray("requirements").forEach(candidate ->
                            ((ObjectNode) candidate).put("version", version));
                });
        LegalEditorialITFixture.ReplaceFixture plan = setup.fixture().replacementPlan(
                current.release(), target, externalId + "-replace");
        return () -> {
            requireApplied(setup.apply().service().applyReplace(target.release(), plan.plan()));
            return seed(owner, target);
        };
    }

    static List<StoredDocument> documents(JdbcTemplate owner) {
        requireEphemeral(owner);
        List<StoredDocument> rows = owner.query("""
                SELECT versions.id, lines.clave, lines.tipo, versions.version, versions.titulo,
                       versions.sha256, versions.contenido_markdown,
                       versions.vigente_desde, versions.estado
                  FROM public.legal_documento_versiones versions
                  JOIN public.legal_documento_lineas lines
                    ON lines.id = versions.documento_linea_id
                """, (row, number) -> {
            UUID id = row.getObject("id", UUID.class);
            List<ContextoLegal> contexts = owner.queryForList("""
                    SELECT contexto FROM public.legal_documento_contextos
                     WHERE documento_version_id = ?
                    """, String.class, id).stream().map(ContextoLegal::valueOf).sorted().toList();
            return new StoredDocument(id, row.getString("clave"),
                    TipoDocumentoLegal.valueOf(row.getString("tipo")),
                    row.getString("version"), row.getString("titulo"), row.getString("sha256"),
                    row.getString("contenido_markdown"),
                    row.getObject("vigente_desde", OffsetDateTime.class).toInstant(),
                    EstadoVersionLegal.valueOf(row.getString("estado")), contexts);
        });
        return rows.stream().sorted(DOCUMENT_ORDER).toList();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Editorial editorial(JdbcTemplate owner, Path directory) {
        requireEphemeral(owner);
        DataSource source = Objects.requireNonNull(owner.getDataSource());
        LegalManifestPersistenceITSupport.ApplyHarness apply =
                LegalManifestPersistenceITSupport.applyHarness(source, LegalDatabaseBudgets.production());
        return new Editorial(new LegalEditorialITFixture(directory,
                LegalPublicDocumentReadITSupport.class, owner,
                LegalManifestPersistenceITSupport.harness(source, LegalDatabaseBudgets.production()),
                apply), apply);
    }

    private static Seed seed(JdbcTemplate owner, LegalEditorialITFixture.ImportedRelease release) {
        return new Seed(release, documents(owner));
    }

    private static void requireApplied(LegalEditorialApplyResult result) {
        assertThat(result.status()).as("issues=%s", result.issues()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).isEqualTo(LegalEditorialApplyResult.Outcome.APPLIED);
    }

    private static void requireEphemeral(JdbcTemplate owner) {
        String database = Objects.requireNonNull(owner).queryForObject(
                "SELECT pg_catalog.current_database()", String.class);
        if (database == null || !database.startsWith(SAFE_DATABASE_PREFIX)) {
            throw new IllegalStateException("El seed requiere una base documental efímera dedicada");
        }
    }

    record Seed(LegalEditorialITFixture.ImportedRelease release, List<StoredDocument> documents) {
        Seed {
            Objects.requireNonNull(release);
            documents = List.copyOf(documents);
        }

        UUID publicationId() {
            return release.publicationId();
        }
    }

    record StoredDocument(UUID id, String key, TipoDocumentoLegal type, String version,
                          String title, String sha256, String markdown, Instant effectiveAt,
                          EstadoVersionLegal state, List<ContextoLegal> contexts) {
        StoredDocument {
            contexts = List.copyOf(contexts);
        }
    }

    private record Editorial(LegalEditorialITFixture fixture,
                             LegalManifestPersistenceITSupport.ApplyHarness apply) { }
}
