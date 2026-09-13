package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportFile;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPackageException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.WorkshopExportSnapshotService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryITSupport.accept;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryITSupport.evidenceRows;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryITSupport.replace;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryITSupport.retire;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestPersistenceITSupport.withReplicaRole;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.requireSafeEphemeralDatabase;
import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsITSupport.seedActor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Export projections over real historical evidence; synthetic photo lifecycle rows use this disposable database only. */
@Testcontainers
class WorkshopExportLegalSnapshotIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_private_requirements_export")
            .withUsername("ordenfix").withPassword("ordenfix");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate owner;
    @TempDir Path directory;
    private LegalEditorialITFixture.ImportedRelease release;
    private LegalPrivateRequirementsITSupport.Actor administrator;
    private WorkshopExportSnapshotService service;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void seed() throws Exception {
        requireSafeEphemeralDatabase(owner);
        owner.execute("TRUNCATE legal_requisito_agregados, legal_publicaciones, legal_documento_reemplazo_lotes, talleres RESTART IDENTITY CASCADE");
        release = LegalAcceptanceHistoryITSupport.seedCatalog(owner, directory, getClass(), 2);
        administrator = seedActor(owner, "ADMIN");
        owner.update("UPDATE users SET email_verificado=TRUE WHERE id=?", administrator.userId());
        service = new WorkshopExportSnapshotService(owner, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void adminReceivesOnlyOwnActsAndExactCorrespondingMarkdownWithoutMetadataOrDml() throws Exception {
        var employee = seedActor(owner, "USER", administrator.workshopId());
        var otherTenant = seedActor(owner, "USER");
        var ownActs = accept(dataSource, administrator, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        var employeeActs = accept(dataSource, employee, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        var foreignActs = accept(dataSource, otherTenant, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        var before = evidenceRows(owner);
        var countsBefore = LegalPrivateRequirementsITSupport.counts(owner);

        ExportSnapshot snapshot = capture();
        JsonNode history = json(snapshot, "datos/aceptaciones_propias.json");
        assertThat(history.size()).isEqualTo(ownActs.size());
        assertThat(ids(history)).containsExactlyInAnyOrderElementsOf(
                ownActs.stream().map(act -> act.id().toString()).toList());
        assertThat(ids(history)).doesNotContainAnyElementsOf(
                employeeActs.stream().map(act -> act.id().toString()).toList());
        assertThat(ids(history)).doesNotContainAnyElementsOf(
                foreignActs.stream().map(act -> act.id().toString()).toList());
        Map<String, ExportFile> documents = documents(snapshot);
        assertThat(documents).isNotEmpty();
        Set<String> expectedDocumentPaths = new java.util.HashSet<>();
        for (JsonNode act : history) {
            String expectedStatement = owner.queryForObject("SELECT afirmacion FROM legal_aceptaciones WHERE id=?", String.class,
                    UUID.fromString(act.path("id").asText()));
            assertThat(act.path("afirmacion").asText()).isEqualTo(expectedStatement);
            assertThat(act.path("afirmacion_sha256").asText()).isEqualTo(sha(expectedStatement.getBytes(StandardCharsets.UTF_8)));
            for (JsonNode document : act.path("documentos")) {
                String path = document.path("archivo").asText();
                expectedDocumentPaths.add(path);
                ExportFile file = documents.get(path);
                assertThat(file).isNotNull();
                assertThat(file.mediaType()).startsWith("text/markdown");
                assertThat(file.records()).isEqualTo(-1);
                byte[] expected = owner.queryForObject("SELECT convert_to(contenido_markdown,'UTF8') FROM legal_documento_versiones WHERE id=?", byte[].class,
                        UUID.fromString(document.path("id").asText()));
                assertThat(bytes(file)).containsExactly(expected);
                assertThat(file.sha256()).isEqualTo(document.path("sha256").asText()).isEqualTo(sha(expected));
            }
            assertThat(act.has("lote_id")).isFalse();
            assertThat(act.has("metadata")).isFalse();
        }
        assertThat(documents.keySet()).containsExactlyInAnyOrderElementsOf(expectedDocumentPaths);
        String payload = new String(bytes(file(snapshot, "datos/aceptaciones_propias.json")), StandardCharsets.UTF_8);
        assertThat(payload).doesNotContain("ciphertext", "nonce", "key_version", "fingerprint_hmac", "scope_hmac", "user_agent", "ip_address");
        assertThat(evidenceRows(owner)).isEqualTo(before);
        assertThat(LegalPrivateRequirementsITSupport.counts(owner)).isEqualTo(countsBefore);
    }

    @Test
    void historicalTextsAndDocumentBytesSurviveRealEditorialReplacementAndRetirement() throws Exception {
        accept(dataSource, administrator, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        ExportSnapshot original = capture();
        var originalEvidence = evidenceRows(owner);
        var replaced = replace(owner, dataSource, directory, getClass(), release);
        assertSameHistory(original, capture());
        retire(owner, dataSource, directory, replaced);
        assertSameHistory(original, capture());
        assertThat(evidenceRows(owner)).isEqualTo(originalEvidence);
    }

    @ParameterizedTest
    @ValueSource(strings = {"statement", "statement_digest", "document_markdown", "document_digest", "missing_document", "future_date"})
    void corruptEvidenceAbortsTheWholeCapture(String corruption) throws Exception {
        var acts = accept(dataSource, administrator, PerfilAgregadoLegal.AUTHENTICATED_PENDING, null);
        UUID first = acts.getFirst().id();
        UUID document = owner.queryForObject("SELECT documento_version_id FROM legal_aceptacion_documentos WHERE aceptacion_id=? ORDER BY documento_ordinal LIMIT 1",
                UUID.class, first);
        requireSafeEphemeralDatabase(owner);
        withReplicaRole(owner, () -> {
            switch (corruption) {
                case "statement" -> owner.update("UPDATE legal_aceptaciones SET afirmacion='Afirmación alterada' WHERE id=?", first);
                case "statement_digest" -> owner.update("UPDATE legal_aceptaciones SET afirmacion_sha256=? WHERE id=?", "0".repeat(64), first);
                case "document_markdown" -> owner.update("UPDATE legal_documento_versiones SET contenido_markdown=? WHERE id=?", "# Documento alterado\n", document);
                case "document_digest" -> owner.update("UPDATE legal_documento_versiones SET sha256=? WHERE id=?", "0".repeat(64), document);
                case "missing_document" -> owner.update("DELETE FROM legal_aceptacion_documentos WHERE aceptacion_id=?", first);
                case "future_date" -> owner.update("UPDATE legal_aceptacion_lotes SET aceptado_en=TIMESTAMPTZ '2999-01-01 00:00:00Z' WHERE user_id=?", administrator.userId());
                default -> throw new AssertionError(corruption);
            }
        });
        var before = evidenceRows(owner);
        assertFailure("INVALID_EVIDENCE");
        assertThat(evidenceRows(owner)).isEqualTo(before);
    }

    @Test
    void privatePhotoMetadataPreservesTerminalOrphanRowsAndOnlyQueuesEligibleFiles() throws Exception {
        long repair = repair(administrator);
        long removedRepair = repair(administrator);
        owner.update("DELETE FROM reparaciones WHERE id=?", removedRepair);
        UUID eligible = photo(administrator, repair, repair, "ASOCIADA", false, "Eligible photo");
        UUID expiredRetention = photo(administrator, repair, repair, "ASOCIADA", true, "Retention expired");
        UUID removed = photo(administrator, repair, repair, "ELIMINADA", false, "Deleted photo");
        UUID expired = photo(administrator, repair, repair, "EXPIRADA", false, "Expired intention");
        UUID orphan = photo(administrator, null, removedRepair, "ELIMINADA", false, "Deleted repair photo");
        var other = seedActor(owner, "ADMIN");
        long foreignRepair = repair(other);
        UUID foreignPhoto = photo(other, foreignRepair, foreignRepair, "ASOCIADA", false, "FOREIGN_PHOTO_CANARY");
        String legacyUrl = "https://example.invalid/LEGACY_URL_CANARY?token=LEGACY_TOKEN_CANARY";
        owner.update("INSERT INTO reparacion_fotos(reparacion_id,url,momento) VALUES (?,?,'INGRESO'),(?,?,'INGRESO')", repair, legacyUrl, repair, legacyUrl);
        ExportSnapshot snapshot = capture();
        JsonNode photos = json(snapshot, "datos/fotos_privadas.json");
        assertThat(ids(photos)).containsExactlyInAnyOrder(eligible.toString(), expiredRetention.toString(), removed.toString(), expired.toString(), orphan.toString());
        assertThat(ids(photos)).doesNotContain(foreignPhoto.toString());
        assertThat(snapshot.pendingPhotos()).hasSize(1);
        var pending = snapshot.pendingPhotos().getFirst();
        assertThat(pending.id()).isEqualTo(eligible);
        assertThat(pending.path()).isEqualTo("archivos/fotos/" + eligible + ".png");
        assertThat(pending.sha256()).isEqualTo("a".repeat(64));
        assertThat(pending.bytes()).isEqualTo(123);
        assertThat(snapshot.files()).noneMatch(file -> file.path().startsWith("archivos/fotos/"));
        for (JsonNode photo : photos) {
            assertThat(photo.has("object_key")).isFalse();
            assertThat(photo.has("asset_id")).isFalse();
            assertThat(photo.has("asset_version")).isFalse();
            assertThat(photo.has("scope_hmac")).isFalse();
            assertThat(photo.has("key_hmac")).isFalse();
            assertThat(photo.has("fingerprint_hmac")).isFalse();
            assertThat(photo.has("hmac_key_version")).isFalse();
            assertThat(photo.has("lease_id")).isFalse();
            assertThat(photo.has("lease_hasta")).isFalse();
            assertThat(photo.path("archivo_estado").asText()).isEqualTo(photo.path("id").asText().equals(eligible.toString())
                    ? "PENDIENTE_C" : "NO_DISPONIBLE_EN_SNAPSHOT");
            if (photo.path("id").asText().equals(orphan.toString())) {
                assertThat(photo.get("reparacion_id").isNull()).isTrue();
                assertThat(photo.path("reparacion_original_id").asText()).isEqualTo(Long.toString(removedRepair));
                assertThat(photo.path("estado").asText()).isEqualTo("ELIMINADA");
            }
        }
        JsonNode legacy = json(snapshot, "datos/fotos_legacy.json");
        assertThat(legacy.size()).isEqualTo(2);
        assertThat(legacy.get(0)).isEqualTo(legacy.get(1));
        assertThat(legacy.get(0).path("url_excluida").asBoolean()).isTrue();
        assertThat(legacy.get(0).path("motivo_exclusion").asText()).isEqualTo("ORIGEN_NO_ACREDITADO");
        assertThat(allText(snapshot)).doesNotContain("FOREIGN_PHOTO_CANARY", "LEGACY_URL_CANARY", "LEGACY_TOKEN_CANARY", "ASSET_CANARY", "VERSION_CANARY", "ordenfix-private/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign_owner", "foreign_repair", "missing_owner", "orphan_associated"})
    void invalidPhotoOwnershipAbortsCapture(String corruption) {
        long repair = repair(administrator);
        UUID photo = photo(administrator, repair, repair, "ASOCIADA", false, "Photo");
        var other = seedActor(owner, "ADMIN");
        long foreignRepair = repair(other);
        requireSafeEphemeralDatabase(owner);
        withReplicaRole(owner, () -> {
            switch (corruption) {
                case "foreign_owner" -> owner.update("UPDATE reparacion_fotos_privadas SET user_id=? WHERE id=?", other.userId(), photo);
                case "foreign_repair" -> owner.update("UPDATE reparacion_fotos_privadas SET reparacion_id=?,reparacion_original_id=? WHERE id=?", foreignRepair, foreignRepair, photo);
                case "missing_owner" -> owner.update("UPDATE reparacion_fotos_privadas SET user_id=? WHERE id=?", Long.MAX_VALUE, photo);
                case "orphan_associated" -> owner.update("UPDATE reparacion_fotos_privadas SET reparacion_id=NULL WHERE id=?", photo);
                default -> throw new AssertionError(corruption);
            }
        });
        assertFailure("INCONSISTENT_RELATION");
    }

    private ExportSnapshot capture() {
        return service.capture(administrator.userId(), administrator.workshopId(), 0);
    }

    private void assertFailure(String code) {
        assertThatThrownBy(this::capture).isInstanceOfSatisfying(ExportPackageException.class,
                failure -> assertThat(failure.code().name()).isEqualTo(code));
    }

    private static void assertSameHistory(ExportSnapshot expected, ExportSnapshot actual) throws Exception {
        assertThat(bytes(file(actual, "datos/aceptaciones_propias.json")))
                .containsExactly(bytes(file(expected, "datos/aceptaciones_propias.json")));
        Map<String, ExportFile> originalDocuments = documents(expected);
        Map<String, ExportFile> actualDocuments = documents(actual);
        assertThat(actualDocuments.keySet()).isEqualTo(originalDocuments.keySet());
        for (String path : originalDocuments.keySet()) {
            assertThat(bytes(actualDocuments.get(path))).containsExactly(bytes(originalDocuments.get(path)));
        }
    }

    private static List<String> ids(JsonNode rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.path("id").asText()));
        return ids;
    }

    private static Map<String, ExportFile> documents(ExportSnapshot snapshot) {
        Map<String, ExportFile> documents = new LinkedHashMap<>();
        for (ExportFile file : snapshot.files()) {
            if (file.path().startsWith("documentos/")) assertThat(documents.put(file.path(), file)).isNull();
        }
        return documents;
    }

    private static JsonNode json(ExportSnapshot snapshot, String path) throws Exception {
        JsonNode rows = JSON.readTree(bytes(file(snapshot, path)));
        assertThat(rows.isArray()).isTrue();
        return rows;
    }

    private static ExportFile file(ExportSnapshot snapshot, String path) {
        return snapshot.files().stream().filter(file -> file.path().equals(path)).findFirst().orElseThrow();
    }

    private static byte[] bytes(ExportFile file) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        file.writeTo(bytes);
        return bytes.toByteArray();
    }

    private static String allText(ExportSnapshot snapshot) throws Exception {
        StringBuilder content = new StringBuilder();
        for (ExportFile file : snapshot.files()) {
            if (file.mediaType().startsWith("application/json") || file.mediaType().startsWith("text/")) {
                content.append(new String(bytes(file), StandardCharsets.UTF_8));
            }
        }
        return content.toString();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static long repair(LegalPrivateRequirementsITSupport.Actor actor) {
        long customer = owner.queryForObject("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('Export photo','Fixture',?,?) RETURNING id", Long.class,
                UUID.randomUUID().toString().substring(0,12), actor.workshopId());
        long equipment = owner.queryForObject("INSERT INTO equipos(marca,modelo,cliente_id,taller_id) VALUES('Marca','Modelo',?,?) RETURNING id", Long.class,
                customer, actor.workshopId());
        return owner.queryForObject("INSERT INTO reparaciones(equipo_id,taller_id,descripcion_problema,estado,fecha_ingreso,precio_estimado) VALUES(?,?,'Foto fixture','INGRESADO',current_date,50000) RETURNING id", Long.class,
                equipment, actor.workshopId());
    }

    /** B exercises persisted export shape, not the separately accredited photo/attestation protocol. */
    private static UUID photo(LegalPrivateRequirementsITSupport.Actor actor, Long repair, long original,
                              String state, boolean retentionExpired, String name) {
        requireSafeEphemeralDatabase(owner);
        UUID id = UUID.randomUUID();
        boolean associated = state.equals("ASOCIADA");
        String scope = id.toString().replace("-", "").repeat(2);
        withReplicaRole(owner, () -> owner.update("""
                INSERT INTO reparacion_fotos_privadas(id,reparacion_id,reparacion_original_id,taller_id,user_id,
                    rol_wire,nombre,mime_type,bytes,sha256,momento,estado,confirmado_en,expira_en,retener_hasta,
                    asociada_en,object_key,asset_id,asset_version,scope_hmac,key_hmac,fingerprint_hmac,hmac_key_version)
                VALUES (?, ?, ?, ?, ?, ?, ?,'image/png',123, ?,'INGRESO', ?,
                    statement_timestamp() - (? * INTERVAL '1 day'),
                    statement_timestamp() - (? * INTERVAL '1 day') + INTERVAL '15 minutes',
                    statement_timestamp() - (? * INTERVAL '1 day') + INTERVAL '30 days',
                    CASE WHEN ? THEN statement_timestamp() - (? * INTERVAL '1 day') + INTERVAL '1 minute' ELSE NULL END,
                    ?, ?, ?, ?, ?, ?,1)
                """, id, repair, original, actor.workshopId(), actor.userId(), actor.role(), name, "a".repeat(64), state,
                retentionExpired ? 60 : 2, retentionExpired ? 60 : 2, retentionExpired ? 60 : 2,
                associated, retentionExpired ? 60 : 2, "ordenfix-private/" + id,
                associated ? "ASSET_CANARY_" + id : null, associated ? "VERSION_CANARY" : null,
                scope, "b".repeat(64), "c".repeat(64)));
        return id;
    }
}
