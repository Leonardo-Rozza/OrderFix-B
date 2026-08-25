package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalManifestDryRunIT {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String V26_SCHEMA = "legal_dry_run_v26";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_manifest_dry_run")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static ValidatedRelease goldenRelease;
    private static AnnotationConfigApplicationContext v27Context;
    private static LegalManifestDryRunService dryRunService;
    private static JdbcTemplate jdbc;

    @TempDir
    private Path temporaryDirectory;

    @BeforeAll
    static void prepareSchemasAndGoldenRelease() throws URISyntaxException {
        migrate("public", null);
        migrate(V26_SCHEMA, "26");
        v27Context = contextForSchema("public");
        dryRunService = v27Context.getBean(LegalManifestDryRunService.class);
        jdbc = v27Context.getBean(JdbcTemplate.class);

        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(goldenManifest());
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        goldenRelease = validation.value().orElseThrow();
    }

    @AfterAll
    static void closeV27Context() {
        if (v27Context != null) {
            v27Context.close();
        }
    }

    @BeforeEach
    void cleanV27LegalState() {
        jdbc.execute("""
                TRUNCATE TABLE legal_publicaciones, legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void goldenReleasePassesThroughV27AndLeavesNoProvisionalRows() {
        assertThat(v27Context.getBeansOfType(Flyway.class)).isEmpty();
        Map<String, Long> baseline = requiredTableCounts(jdbc);
        Map<String, Long> allLegalBaseline = tableCounts(jdbc, true);
        Map<String, Long> nonLegalBaseline = tableCounts(jdbc, false);
        assertThat(allLegalBaseline.values()).containsOnly(0L);

        LegalManifestValidation<DryRunResult> validation =
                dryRunService.dryRun(goldenRelease);

        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        assertThat(validation.issues()).isEmpty();
        assertThat(validation.omittedIssueCount()).isZero();
        DryRunResult result = validation.value().orElseThrow();
        assertThat(result.documents()).isEqualTo(11);
        assertThat(result.requirements()).isEqualTo(6);
        assertThat(result.scopes()).isEqualTo(8);
        assertThat(result.newDocumentLines()).isEqualTo(11);
        assertThat(result.newDocumentVersions()).isEqualTo(11);
        assertThat(result.reusedDocumentVersions()).isZero();
        assertThat(result.newRequirementLines()).isEqualTo(6);
        assertThat(result.newRequirementVersions()).isEqualTo(6);
        assertThat(result.reusedRequirementVersions()).isZero();
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
        assertThat(tableCounts(jdbc, true)).isEqualTo(allLegalBaseline);
        assertThat(tableCounts(jdbc, false)).isEqualTo(nonLegalBaseline);
    }

    @Test
    void identitySequencesMayAdvanceAlthoughEveryDomainRowRollsBack() {
        SequenceState before = documentContextSequenceState();
        Map<String, Long> baseline = requiredTableCounts(jdbc);

        LegalManifestValidation<DryRunResult> validation =
                dryRunService.dryRun(goldenRelease);

        assertThat(validation.status()).isEqualTo(LegalManifestStatus.PASS);
        SequenceState after = documentContextSequenceState();
        assertThat(before.called()).isFalse();
        assertThat(after.called()).isTrue();
        assertThat(after.lastValue()).isGreaterThanOrEqualTo(before.lastValue());
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
    }

    @Test
    void existingPublicationExternalIdBlocksWithoutChangingTheBaseline() {
        UUID existingPublicationId = insertOpenPublication(
                goldenRelease.plan().manifest().publicationId());
        Map<String, Long> baseline = requiredTableCounts(jdbc);

        LegalManifestValidation<DryRunResult> validation =
                dryRunService.dryRun(goldenRelease);

        assertThat(validation.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(validation.value()).isEmpty();
        assertThat(validation.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DB_PERSISTED_CONFLICT);
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
        assertThat(jdbc.queryForObject("""
                SELECT estado_construccion
                  FROM legal_publicaciones
                 WHERE id = ?
                """, String.class, existingPublicationId)).isEqualTo("ABIERTO");
    }

    @Test
    void openHistoricalIntroPublicationWithDifferentExternalIdBlocksAndPreservesBaseline() {
        UUID historicalPublicationId = insertOpenPublication(
                "historical-open-" + UUID.randomUUID());
        UUID documentLineId = insertDocumentLine(
                historicalPublicationId,
                "terminos",
                "TERMINOS_SERVICIO");
        Map<String, Long> baseline = requiredTableCounts(jdbc);

        LegalManifestValidation<DryRunResult> validation =
                dryRunService.dryRun(goldenRelease);

        assertThat(validation.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(validation.value()).isEmpty();
        assertThat(validation.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DB_PERSISTED_CONFLICT);
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
        assertThat(jdbc.queryForMap("""
                SELECT p.estado_construccion, l.clave, l.tipo
                  FROM legal_publicaciones p
                  JOIN legal_documento_lineas l ON l.publicacion_intro_id = p.id
                 WHERE p.id = ? AND l.id = ?
                """, historicalPublicationId, documentLineId))
                .containsEntry("estado_construccion", "ABIERTO")
                .containsEntry("clave", "terminos")
                .containsEntry("tipo", "TERMINOS_SERVICIO");
    }

    @Test
    void incompatibleExistingLineIdentityBlocksAndPreservesBaseline() {
        UUID historicalPublicationId = insertOpenPublication(
                "historical-incompatible-" + UUID.randomUUID());
        UUID documentLineId = insertDocumentLine(
                historicalPublicationId,
                "terminos",
                "POLITICA_PRIVACIDAD");
        Map<String, Long> baseline = requiredTableCounts(jdbc);

        LegalManifestValidation<DryRunResult> validation =
                dryRunService.dryRun(goldenRelease);

        assertThat(validation.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(validation.value()).isEmpty();
        assertThat(validation.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DB_PERSISTED_CONFLICT);
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
        assertThat(jdbc.queryForObject("""
                SELECT tipo
                  FROM legal_documento_lineas
                 WHERE id = ?
                """, String.class, documentLineId)).isEqualTo("POLITICA_PRIVACIDAD");
    }

    @Test
    void subMicrosecondTimestampIsRejectedByDatabaseGateAndPreservesBaseline()
            throws Exception {
        ValidatedRelease incoming = copyAndValidateGolden(
                "release-sub-microsecond-timestamp-v1",
                (manifestPath, manifest) -> document(manifest, "terminos").put(
                        "effectiveAt",
                        "2026-09-01T00:00:00.123456789-03:00"));
        Map<String, Long> baseline = requiredTableCounts(jdbc);

        LegalManifestValidation<DryRunResult> validation = dryRunService.dryRun(incoming);

        assertThat(validation.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(validation.value()).isEmpty();
        assertThat(validation.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DB_CONSTRAINT);
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
    }

    @Test
    void exactPersistedVersionsAreReusedAndTheIncomingDryRunStillRollsBack()
            throws Exception {
        DryRunResult seeded = commitValidatedRelease(goldenRelease);
        assertThat(seeded.newDocumentVersions()).isEqualTo(11);
        assertThat(seeded.newRequirementVersions()).isEqualTo(6);
        ValidatedRelease incoming = copyAndValidateGolden(
                "release-reuse-exact-v1",
                ReleaseMutation.NONE);
        Map<String, Long> baseline = requiredTableCounts(jdbc);

        LegalManifestValidation<DryRunResult> validation = dryRunService.dryRun(incoming);

        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        DryRunResult result = validation.value().orElseThrow();
        assertThat(result.documents()).isEqualTo(11);
        assertThat(result.requirements()).isEqualTo(6);
        assertThat(result.scopes()).isEqualTo(8);
        assertThat(result.newDocumentLines()).isZero();
        assertThat(result.newDocumentVersions()).isZero();
        assertThat(result.reusedDocumentVersions()).isEqualTo(11);
        assertThat(result.newRequirementLines()).isZero();
        assertThat(result.newRequirementVersions()).isZero();
        assertThat(result.reusedRequirementVersions()).isEqualTo(6);
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("persistedConflictCases")
    void validIncomingMetadataConflictsAreBlockedWithoutChangingTheBaseline(
            PersistedConflictCase conflictCase) throws Exception {
        commitValidatedRelease(goldenRelease);
        ValidatedRelease incoming = copyAndValidateGolden(
                "release-conflict-" + conflictCase.slug(),
                conflictCase.mutation());
        Map<String, Long> baseline = requiredTableCounts(jdbc);

        LegalManifestValidation<DryRunResult> validation = dryRunService.dryRun(incoming);

        assertThat(validation.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(validation.value()).isEmpty();
        assertThat(validation.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DB_PERSISTED_CONFLICT);
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
    }

    @Test
    void v26IsRejectedWithoutRunningFlywayOrCreatingLegalTables() {
        JdbcTemplate v26Jdbc = new JdbcTemplate(schemaDataSource(V26_SCHEMA));
        String versionBefore = currentFlywayVersion(v26Jdbc);
        assertThat(versionBefore).isEqualTo("26");
        assertThat(legalTableCount(v26Jdbc, V26_SCHEMA)).isZero();

        LegalManifestValidation<DryRunResult> validation;
        try (AnnotationConfigApplicationContext context = contextForSchema(V26_SCHEMA)) {
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            validation = context.getBean(LegalManifestDryRunService.class)
                    .dryRun(goldenRelease);
        }

        assertThat(validation.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(validation.value()).isEmpty();
        assertThat(validation.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE);
        assertThat(currentFlywayVersion(v26Jdbc)).isEqualTo(versionBefore);
        assertThat(legalTableCount(v26Jdbc, V26_SCHEMA)).isZero();
    }

    private DryRunResult commitValidatedRelease(ValidatedRelease release) {
        LegalDryRunPersistence persistence = v27Context.getBean(LegalDryRunPersistence.class);
        TransactionTemplate transaction = v27Context.getBean(TransactionTemplate.class);
        DryRunResult result = transaction.execute(status -> {
            persistence.configureTransaction();
            return persistence.stageAndValidate(release);
        });
        return Objects.requireNonNull(result, "committed dry-run seed result");
    }

    private ValidatedRelease copyAndValidateGolden(
            String publicationId,
            ReleaseMutation mutation) throws Exception {
        Path sourceRoot = goldenManifest().getParent();
        Path releaseRoot = temporaryDirectory.resolve(publicationId);
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
        manifest.put("publicationId", publicationId);
        mutation.apply(manifestPath, manifest);
        Files.write(
                manifestPath,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));

        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifestPath);
        assertThat(validation.status())
                .as("incoming=%s issues=%s", publicationId, validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        return validation.value().orElseThrow();
    }

    private static Stream<PersistedConflictCase> persistedConflictCases() {
        return Stream.of(
                new PersistedConflictCase("document-bytes-title-digest", (manifestPath, manifest) -> {
                    String markdown = """
                            # Términos alternativos de OrdenFix

                            Contenido jurídico alternativo para acreditar un conflicto persistido.
                            """;
                    Files.writeString(
                            manifestPath.getParent().resolve("terminos.md"),
                            markdown,
                            StandardCharsets.UTF_8);
                    document(manifest, "terminos").put("sha256", sha256(markdown));
                }),
                new PersistedConflictCase("document-effective-at", (manifestPath, manifest) ->
                        document(manifest, "terminos").put(
                                "effectiveAt",
                                "2026-10-01T00:00:00-03:00")),
                new PersistedConflictCase("document-reacceptance", (manifestPath, manifest) ->
                        document(manifest, "terminos").put("requiresReacceptance", false)),
                new PersistedConflictCase("document-contexts", (manifestPath, manifest) ->
                        document(manifest, "terminos").set(
                                "contexts",
                                textArray("REGISTRO", "CONTRATACION_PRO"))),
                new PersistedConflictCase("requirement-audiences", (manifestPath, manifest) ->
                        requirement(manifest, "account-closure").set(
                                "roles",
                                textArray("ADMIN_TITULAR", "USER"))),
                new PersistedConflictCase("requirement-document-order", (manifestPath, manifest) ->
                        requirement(manifest, "admin-registration").set(
                                "documents",
                                textArray("tratamiento-datos", "privacidad", "terminos"))));
    }

    private static ObjectNode document(ObjectNode manifest, String key) {
        for (var candidate : manifest.path("documents")) {
            if (key.equals(candidate.path("key").textValue())) {
                return (ObjectNode) candidate;
            }
        }
        throw new IllegalArgumentException("Documento de test inexistente");
    }

    private static ObjectNode requirement(ObjectNode manifest, String key) {
        for (var candidate : manifest.path("requirements")) {
            if (key.equals(candidate.path("key").textValue())) {
                return (ObjectNode) candidate;
            }
        }
        throw new IllegalArgumentException("Requisito de test inexistente");
    }

    private static ArrayNode textArray(String... values) {
        ArrayNode array = MAPPER.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible en el test", exception);
        }
    }

    private UUID insertOpenPublication(String externalId) {
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

    private UUID insertDocumentLine(
            UUID introductoryPublicationId,
            String key,
            String type) {
        UUID lineId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO legal_documento_lineas
                    (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                VALUES (?, ?, ?, 'es-AR', ?, CURRENT_TIMESTAMP)
                """, lineId, key, type, introductoryPublicationId);
        return lineId;
    }

    private static Map<String, Long> requiredTableCounts(JdbcTemplate database) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new TreeSet<>(LegalV27SchemaVerifier.requiredTables())) {
            Long count = database.queryForObject(
                    "SELECT count(*) FROM " + quoteIdentifier(table),
                    Long.class);
            counts.put(table, Objects.requireNonNull(count, "row count"));
        }
        return Map.copyOf(counts);
    }

    private static Map<String, Long> tableCounts(JdbcTemplate database, boolean legal) {
        String operator = legal ? "LIKE" : "NOT LIKE";
        var tables = database.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = current_schema()
                   AND table_type = 'BASE TABLE'
                   AND table_name %s 'legal\\_%%' ESCAPE '\\'
                 ORDER BY table_name
                """.formatted(operator), String.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tables) {
            Long count = database.queryForObject(
                    "SELECT count(*) FROM " + quoteIdentifier(table),
                    Long.class);
            counts.put(table, Objects.requireNonNull(count, "row count"));
        }
        return Map.copyOf(counts);
    }

    private static SequenceState documentContextSequenceState() {
        return jdbc.queryForObject("""
                SELECT last_value, is_called
                  FROM legal_documento_contextos_id_seq
                """, (resultSet, rowNumber) -> new SequenceState(
                resultSet.getLong("last_value"),
                resultSet.getBoolean("is_called")));
    }

    private static AnnotationConfigApplicationContext contextForSchema(String schema) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        Map<String, Object> properties = Map.of(
                LegalDryRunDatabaseConfiguration.ENABLED_PROPERTY, "true",
                "spring.datasource.url", jdbcUrlForSchema(schema),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword(),
                "spring.datasource.driver-class-name", POSTGRES.getDriverClassName());
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("legal-dry-run-it-" + schema, properties));
        context.register(LegalDryRunDatabaseConfiguration.class);
        context.refresh();
        return context;
    }

    private static javax.sql.DataSource schemaDataSource(String schema) {
        org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource();
        dataSource.setUrl(jdbcUrlForSchema(schema));
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        return dataSource;
    }

    private static void migrate(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static String currentFlywayVersion(JdbcTemplate database) {
        return database.queryForObject("""
                SELECT version
                  FROM flyway_schema_history
                 WHERE success IS TRUE AND version IS NOT NULL
                 ORDER BY installed_rank DESC
                 LIMIT 1
                """, String.class);
    }

    private static int legalTableCount(JdbcTemplate database, String schema) {
        Integer count = database.queryForObject("""
                SELECT count(*)
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_type = 'BASE TABLE'
                   AND table_name LIKE 'legal\\_%' ESCAPE '\\'
                """, Integer.class, schema);
        return count == null ? 0 : count;
    }

    private static Path goldenManifest() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                LegalManifestDryRunIT.class.getResource(GOLDEN_MANIFEST)).toURI());
    }

    private static String jdbcUrlForSchema(String schema) {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    @FunctionalInterface
    private interface ReleaseMutation {

        ReleaseMutation NONE = (manifestPath, manifest) -> { };

        void apply(Path manifestPath, ObjectNode manifest) throws IOException;
    }

    private record PersistedConflictCase(String slug, ReleaseMutation mutation) {

        private PersistedConflictCase {
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(mutation, "mutation");
        }

        @Override
        public String toString() {
            return slug;
        }
    }

    private record SequenceState(long lastValue, boolean called) { }
}
