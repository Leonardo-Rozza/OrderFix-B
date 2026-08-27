package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class LegalManifestImportIT {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final List<String> IMPORT_SEQUENCES = List.of(
            "legal_documento_contextos_id_seq",
            "legal_publicacion_documentos_id_seq",
            "legal_requisito_audiencias_id_seq",
            "legal_requisito_documentos_id_seq",
            "legal_publicacion_requisitos_id_seq",
            "legal_requisito_conjunto_miembros_id_seq");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_manifest_import")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static ValidatedRelease goldenRelease;
    private static JdbcTemplate jdbc;
    private static LegalManifestDatabaseGate databaseGate;
    private static LegalRequiredSetRevisionCalculator revisionCalculator;
    private static LegalManifestReplayVerifier replayVerifier;
    private static LegalManifestImportService importService;
    private static TransactionTemplate editorialTransaction;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void migrateAndAssembleService() throws URISyntaxException {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DataSource dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setName("legal-manifest-import-it");
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(LegalDatabaseBudgets.production().transactionTimeoutSeconds());
        transaction.setReadOnly(false);
        editorialTransaction = transaction;
        revisionCalculator = new LegalRequiredSetRevisionCalculator();
        databaseGate = new LegalManifestDatabaseGate(
                transaction,
                jdbc,
                LegalDatabaseBudgets.production(),
                List.of(new LegalV27SchemaVerifier(jdbc)));
        replayVerifier = new LegalManifestReplayVerifier(jdbc, revisionCalculator);
        importService = new LegalManifestImportService(
                databaseGate,
                jdbc,
                new LegalManifestGraphWriter(jdbc, revisionCalculator),
                replayVerifier,
                new LegalImportFailureMapper());
        goldenRelease = validate(goldenManifest());
    }

    @BeforeEach
    void cleanLegalState() {
        jdbc.execute("""
                TRUNCATE TABLE legal_publicaciones, legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void freshImportCommitsACompleteSealedGraphWithoutOpenResidue() {
        LegalManifestImportResult result = importService.importManifest(goldenRelease);

        assertConfirmed(result, LegalManifestImportResult.Outcome.IMPORTED);
        UUID publicationId = result.receipt().orElseThrow().publicationUuid();
        assertThat(jdbc.queryForMap("""
                SELECT estado_construccion, importado_en IS NOT NULL AS imported,
                       sellado_en IS NOT NULL AS sealed
                  FROM legal_publicaciones
                 WHERE id = ?
                """, publicationId))
                .containsEntry("estado_construccion", "SELLADO")
                .containsEntry("imported", true)
                .containsEntry("sealed", true);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM legal_publicaciones
                 WHERE estado_construccion = 'ABIERTO'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM legal_documento_versiones
                 WHERE publicacion_intro_id = ? AND estado = 'BORRADOR'
                """, Integer.class, publicationId)).isEqualTo(11);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM legal_requisito_versiones
                 WHERE publicacion_intro_id = ? AND estado = 'BORRADOR'
                """, Integer.class, publicationId)).isEqualTo(6);
        assertThat(requiredTableCounts().values()).allMatch(count -> count > 0L);
    }

    @Test
    void exactReplayReturnsTheSameReceiptWithoutRowsOrSequenceAdvances() {
        LegalManifestImportResult imported = importService.importManifest(goldenRelease);
        Map<String, Long> rowsBefore = requiredTableCounts();
        Map<String, SequenceState> sequencesBefore = sequenceStates();

        LegalManifestImportResult replay = importService.importManifest(goldenRelease);

        assertConfirmed(replay, LegalManifestImportResult.Outcome.ALREADY_IMPORTED);
        assertThat(replay.receipt()).isEqualTo(imported.receipt());
        assertThat(requiredTableCounts()).isEqualTo(rowsBefore);
        assertThat(sequenceStates()).isEqualTo(sequencesBefore);
    }

    @Test
    void sameExternalIdWithDifferentCanonicalHeaderIsBlockedWithoutMutation()
            throws Exception {
        importService.importManifest(goldenRelease);
        ValidatedRelease changed = copyWithDifferentBusinessHours();
        Map<String, Long> rowsBefore = requiredTableCounts();
        Map<String, SequenceState> sequencesBefore = sequenceStates();

        LegalManifestImportResult result = importService.importManifest(changed);

        assertKnownFailure(
                result,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT);
        assertThat(requiredTableCounts()).isEqualTo(rowsBefore);
        assertThat(sequenceStates()).isEqualTo(sequencesBefore);
    }

    @Test
    void existingOpenPublicationRequiresManualInterventionAndRemainsUntouched() {
        UUID publicationId = insertOpenPublication(
                goldenRelease.plan().manifest().publicationId());
        Map<String, Long> rowsBefore = requiredTableCounts();

        LegalManifestImportResult result = importService.importManifest(goldenRelease);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.IMPORT_DB_PUBLICATION_OPEN);
        assertThat(requiredTableCounts()).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForObject("""
                SELECT estado_construccion
                  FROM legal_publicaciones
                 WHERE id = ?
                """, String.class, publicationId)).isEqualTo("ABIERTO");
    }

    @Test
    void immutableGraphCorruptionCannotPassAsReplay() {
        LegalManifestImportResult imported = importService.importManifest(goldenRelease);
        UUID publicationId = imported.receipt().orElseThrow().publicationUuid();
        UUID versionId = jdbc.queryForObject("""
                SELECT documento_version_id
                  FROM legal_publicacion_documentos
                 WHERE publicacion_id = ?
                 ORDER BY manifest_ordinal
                 LIMIT 1
                """, UUID.class, publicationId);
        jdbc.execute("""
                ALTER TABLE legal_documento_versiones
                DISABLE TRIGGER trg_legal_documento_version_update
                """);
        try {
            jdbc.update("""
                    UPDATE legal_documento_versiones
                       SET titulo = 'Título corrompido por el test'
                     WHERE id = ?
                    """, versionId);
        } finally {
            jdbc.execute("""
                    ALTER TABLE legal_documento_versiones
                    ENABLE TRIGGER trg_legal_documento_version_update
                    """);
        }
        Map<String, Long> rowsBefore = requiredTableCounts();
        Map<String, SequenceState> sequencesBefore = sequenceStates();

        LegalManifestImportResult result = importService.importManifest(goldenRelease);

        assertKnownFailure(
                result,
                LegalManifestStatus.BLOCKED,
                LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT);
        assertThat(requiredTableCounts()).isEqualTo(rowsBefore);
        assertThat(sequenceStates()).isEqualTo(sequencesBefore);
    }

    @Test
    void legitimateLaterEditorialStateDoesNotInvalidateImmutableReplay() throws Exception {
        ValidatedRelease effectiveRelease = copyWithPastEffectiveDates();
        LegalManifestImportResult imported = importService.importManifest(effectiveRelease);
        UUID publicationId = imported.receipt().orElseThrow().publicationUuid();
        UUID activeDocument = documentVersion(publicationId, "cierre-cuenta");
        UUID retiredDocument = documentVersion(publicationId, "aviso-clientes-taller");
        UUID activeRequirement = requirementVersion(publicationId, "account-closure");
        UUID replacedRequirement =
                requirementVersion(publicationId, "customer-photo-attestation");
        UUID retiredRequirement =
                requirementVersion(publicationId, "customer-credential-attestation");

        editorialTransaction.executeWithoutResult(ignored -> {
            publishAndActivateDocument(activeDocument, publicationId);
            publishAndActivateRequirement(activeRequirement);
            jdbc.update("""
                    INSERT INTO legal_requisito_conjuntos_actuales
                        (locale, contexto, audiencia, conjunto_id,
                         publicacion_id, actualizado_en)
                    SELECT locale, contexto, audiencia, id,
                           publicacion_id, CURRENT_TIMESTAMP
                      FROM legal_requisito_conjuntos
                     WHERE publicacion_id = ?
                       AND contexto = 'CIERRE_CUENTA'
                       AND audiencia = 'ADMIN_TITULAR'
                    """, publicationId);

            publishAndActivateDocument(retiredDocument, publicationId);
            jdbc.update("""
                    DELETE FROM legal_documento_vigentes
                     WHERE documento_version_id = ?
                    """, retiredDocument);
            transitionDocument(
                    retiredDocument, "VIGENTE", "RETIRADA", "Documento discontinuado");

            publishAndActivateRequirement(replacedRequirement);
            transitionRequirement(replacedRequirement, "VIGENTE", "REEMPLAZADA", null);
            publishAndActivateRequirement(retiredRequirement);
            transitionRequirement(
                    retiredRequirement, "VIGENTE", "RETIRADA", "Requisito discontinuado");
        });
        Map<String, Long> rowsBefore = requiredTableCounts();
        Map<String, SequenceState> sequencesBefore = sequenceStates();
        Map<String, Object> editorialStateBefore = editorialState(publicationId);

        LegalManifestImportResult replay = importService.importManifest(effectiveRelease);

        assertConfirmed(replay, LegalManifestImportResult.Outcome.ALREADY_IMPORTED);
        assertThat(replay.receipt()).isEqualTo(imported.receipt());
        assertThat(requiredTableCounts()).isEqualTo(rowsBefore);
        assertThat(sequenceStates()).isEqualTo(sequencesBefore);
        assertThat(editorialState(publicationId)).isEqualTo(editorialStateBefore);
        assertThat(editorialStateBefore)
                .containsEntry("active_document_state", "VIGENTE")
                .containsEntry("retired_document_state", "RETIRADA")
                .containsEntry("active_requirement_state", "VIGENTE")
                .containsEntry("replaced_requirement_state", "REEMPLAZADA")
                .containsEntry("retired_requirement_state", "RETIRADA")
                .containsEntry("active_document_slots", 1L)
                .containsEntry("current_requirement_sets", 1L);
    }

    @Test
    void failureAfterTheWriterFinishesRollsBackEveryRowAndLeavesNoOpenPublication() {
        LegalManifestGraphWriter realWriter =
                new LegalManifestGraphWriter(jdbc, revisionCalculator);
        LegalManifestGraphWriter failingWriter = mock(LegalManifestGraphWriter.class);
        when(failingWriter.usesJdbc(jdbc)).thenReturn(true);
        when(failingWriter.writeNew(any())).thenAnswer(invocation -> {
            realWriter.writeNew(invocation.getArgument(0));
            throw new LegalImportOperationalException(
                    LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED,
                    "database");
        });
        LegalManifestImportService failingService = new LegalManifestImportService(
                databaseGate,
                jdbc,
                failingWriter,
                replayVerifier,
                new LegalImportFailureMapper());

        LegalManifestImportResult result = failingService.importManifest(goldenRelease);

        assertKnownFailure(
                result,
                LegalManifestStatus.ERROR,
                LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED);
        assertThat(requiredTableCounts().values()).containsOnly(0L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM legal_publicaciones
                 WHERE estado_construccion = 'ABIERTO'
                """, Integer.class)).isZero();
    }

    private ValidatedRelease copyWithDifferentBusinessHours() throws Exception {
        return copyWithManifestChange(manifest -> ((ObjectNode) manifest.path("publisherSnapshot"))
                .put("businessHours", "Lunes a viernes de 10:00 a 17:00"));
    }

    private ValidatedRelease copyWithPastEffectiveDates() throws Exception {
        return copyWithManifestChange(manifest -> manifest.withArray("documents")
                .forEach(document -> ((ObjectNode) document)
                        .put("effectiveAt", "2020-01-01T00:00:00Z")));
    }

    private ValidatedRelease copyWithManifestChange(
            java.util.function.Consumer<ObjectNode> change) throws Exception {
        Path sourceRoot = goldenManifest().getParent();
        Path releaseRoot = temporaryDirectory.resolve(
                goldenRelease.plan().manifest().publicationId());
        Files.createDirectories(releaseRoot);
        try (Stream<Path> entries = Files.list(sourceRoot)) {
            for (Path source : entries.toList()) {
                Files.copy(
                        source,
                        releaseRoot.resolve(source.getFileName().toString()),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        Path manifestPath = releaseRoot.resolve("publication-manifest.json");
        ObjectNode manifest = (ObjectNode) MAPPER.readTree(Files.readAllBytes(manifestPath));
        change.accept(manifest);
        Files.write(
                manifestPath,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        return validate(manifestPath);
    }

    private static UUID documentVersion(UUID publicationId, String key) {
        return jdbc.queryForObject("""
                SELECT dv.id
                  FROM legal_documento_versiones dv
                  JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
                 WHERE dv.publicacion_intro_id = ?
                   AND dl.clave = ?
                """, UUID.class, publicationId, key);
    }

    private static UUID requirementVersion(UUID publicationId, String key) {
        return jdbc.queryForObject("""
                SELECT rv.id
                  FROM legal_requisito_versiones rv
                  JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
                 WHERE rv.publicacion_intro_id = ?
                   AND rl.clave = ?
                """, UUID.class, publicationId, key);
    }

    private static void publishAndActivateDocument(UUID versionId, UUID publicationId) {
        transitionDocument(versionId, "BORRADOR", "PUBLICADA", null);
        transitionDocument(versionId, "PUBLICADA", "VIGENTE", null);
        jdbc.update("""
                INSERT INTO legal_documento_vigentes
                    (tipo, locale, contexto, documento_version_id,
                     documento_linea_id, publicacion_id, estado_documento)
                SELECT dl.tipo, dl.locale, dc.contexto, dv.id,
                       dv.documento_linea_id, ?, 'VIGENTE'
                  FROM legal_documento_versiones dv
                  JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
                  JOIN legal_documento_contextos dc ON dc.documento_version_id = dv.id
                 WHERE dv.id = ?
                """, publicationId, versionId);
    }

    private static void publishAndActivateRequirement(UUID versionId) {
        transitionRequirement(versionId, "BORRADOR", "PUBLICADA", null);
        transitionRequirement(versionId, "PUBLICADA", "VIGENTE", null);
    }

    private static void transitionDocument(
            UUID versionId,
            String previousState,
            String newState,
            String reason) {
        jdbc.update("""
                INSERT INTO legal_documento_transiciones
                    (documento_version_id, estado_anterior, estado_nuevo,
                     motivo, reemplazo_lote_id, ocurrido_en)
                VALUES (?, ?, ?, ?, NULL, CURRENT_TIMESTAMP)
                """, versionId, previousState, newState, reason);
    }

    private static void transitionRequirement(
            UUID versionId,
            String previousState,
            String newState,
            String reason) {
        jdbc.update("""
                INSERT INTO legal_requisito_transiciones
                    (requisito_version_id, estado_anterior, estado_nuevo,
                     motivo, ocurrido_en)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, versionId, previousState, newState, reason);
    }

    private static Map<String, Object> editorialState(UUID publicationId) {
        return jdbc.queryForMap("""
                SELECT
                    (SELECT dv.estado
                       FROM legal_documento_versiones dv
                       JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
                      WHERE dv.publicacion_intro_id = ?
                        AND dl.clave = 'cierre-cuenta') AS active_document_state,
                    (SELECT dv.estado
                       FROM legal_documento_versiones dv
                       JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
                      WHERE dv.publicacion_intro_id = ?
                        AND dl.clave = 'aviso-clientes-taller') AS retired_document_state,
                    (SELECT rv.estado
                       FROM legal_requisito_versiones rv
                       JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
                      WHERE rv.publicacion_intro_id = ?
                        AND rl.clave = 'account-closure') AS active_requirement_state,
                    (SELECT rv.estado
                       FROM legal_requisito_versiones rv
                       JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
                      WHERE rv.publicacion_intro_id = ?
                        AND rl.clave = 'customer-photo-attestation')
                        AS replaced_requirement_state,
                    (SELECT rv.estado
                       FROM legal_requisito_versiones rv
                       JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
                      WHERE rv.publicacion_intro_id = ?
                        AND rl.clave = 'customer-credential-attestation')
                        AS retired_requirement_state,
                    (SELECT count(*)
                       FROM legal_documento_vigentes vigente
                      WHERE vigente.publicacion_id = ?
                        AND vigente.documento_version_id =
                            (SELECT dv.id
                               FROM legal_documento_versiones dv
                               JOIN legal_documento_lineas dl
                                 ON dl.id = dv.documento_linea_id
                              WHERE dv.publicacion_intro_id = ?
                                AND dl.clave = 'cierre-cuenta'))
                        AS active_document_slots,
                    (SELECT count(*)
                       FROM legal_requisito_conjuntos_actuales actual
                      WHERE actual.publicacion_id = ?)
                        AS current_requirement_sets
                """, publicationId, publicationId, publicationId, publicationId,
                publicationId, publicationId, publicationId, publicationId);
    }

    private static UUID insertOpenPublication(String externalId) {
        UUID publicationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO legal_publicaciones
                    (id, publication_external_id, schema_version, locale,
                     manifest_sha256, manifest_canonico, razon_social, cuit,
                     domicilio_legal, jurisdiccion, horario_atencion,
                     email_legal, email_privacidad, email_soporte,
                     revision_legal_estado, revision_contable_estado, importado_en)
                VALUES (?, ?, 1, 'es-AR', ?, '{}', 'OrdenFix Test', '30000000000',
                        'Calle de prueba 100', 'CABA', 'Lunes a viernes',
                        'legal@ordenfix.test', 'privacidad@ordenfix.test',
                        'soporte@ordenfix.test', 'PENDIENTE', 'PENDIENTE',
                        CURRENT_TIMESTAMP)
                """, publicationId, externalId, "a".repeat(64));
        return publicationId;
    }

    private static Map<String, Long> requiredTableCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new TreeSet<>(LegalV27SchemaVerifier.requiredTables())) {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM " + quoteIdentifier(table),
                    Long.class);
            counts.put(table, Objects.requireNonNull(count, "row count"));
        }
        return Map.copyOf(counts);
    }

    private static Map<String, SequenceState> sequenceStates() {
        Map<String, SequenceState> states = new LinkedHashMap<>();
        for (String sequence : IMPORT_SEQUENCES) {
            SequenceState state = jdbc.queryForObject(
                    "SELECT last_value, is_called FROM " + quoteIdentifier(sequence),
                    (resultSet, rowNumber) -> new SequenceState(
                            resultSet.getLong("last_value"),
                            resultSet.getBoolean("is_called")));
            states.put(sequence, Objects.requireNonNull(state, "sequence state"));
        }
        return Map.copyOf(states);
    }

    private static void assertConfirmed(
            LegalManifestImportResult result,
            LegalManifestImportResult.Outcome outcome) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isTrue();
        assertThat(result.outcome()).contains(outcome);
        assertThat(result.receipt()).isPresent();
        assertThat(result.issues()).isEmpty();
    }

    private static void assertKnownFailure(
            LegalManifestImportResult result,
            LegalManifestStatus status,
            LegalManifestIssueCode code) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.persisted()).isFalse();
        assertThat(result.outcome()).isEmpty();
        assertThat(result.receipt()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(code);
    }

    private static ValidatedRelease validate(Path manifest) {
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        return validation.value().orElseThrow();
    }

    private static Path goldenManifest() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                LegalManifestImportIT.class.getResource(GOLDEN_MANIFEST)).toURI());
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        return dataSource;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private record SequenceState(long lastValue, boolean called) { }
}
