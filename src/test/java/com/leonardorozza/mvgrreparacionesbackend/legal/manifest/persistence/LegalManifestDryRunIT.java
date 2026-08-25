package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestReport;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestReportWriter;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService.DryRunResult;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalManifestDryRunIT {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String GOLDEN_JCS_SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";
    private static final String PASS_REPORT_V1 = """
            {"reportVersion":1,"command":"dry-run","status":"PASS","persisted":false,"publication":{"publicationId":"release-valid-v1","schemaVersion":1,"manifestSha256":"%s"},"counts":{"documents":11,"requirements":6,"scopes":8},"dryRun":{"newDocumentLines":11,"newDocumentVersions":11,"reusedDocumentVersions":0,"newRequirementLines":6,"newRequirementVersions":6,"reusedRequirementVersions":0},"issues":[],"omittedIssueCount":0}"""
            .formatted(GOLDEN_JCS_SHA256);
    private static final String BLOCKED_REPORT_V1 = """
            {"reportVersion":1,"command":"dry-run","status":"BLOCKED","persisted":false,"publication":{"publicationId":"release-valid-v1","schemaVersion":1,"manifestSha256":"%s"},"counts":{"documents":11,"requirements":6,"scopes":8},"dryRun":null,"issues":[{"severity":"BLOCKED","code":"DB_PERSISTED_CONFLICT","location":"database/publication","message":"El release entra en conflicto con una identidad legal ya persistida."}],"omittedIssueCount":0}"""
            .formatted(GOLDEN_JCS_SHA256);
    private static final String ERROR_REPORT_V1 = """
            {"reportVersion":1,"command":"dry-run","status":"ERROR","persisted":false,"publication":{"publicationId":"release-valid-v1","schemaVersion":1,"manifestSha256":"%s"},"counts":{"documents":11,"requirements":6,"scopes":8},"dryRun":null,"issues":[{"severity":"ERROR","code":"DB_SCHEMA_INCOMPATIBLE","location":"database/schema","message":"La base no posee el schema legal V27 compatible requerido por el dry-run."}],"omittedIssueCount":0}"""
            .formatted(GOLDEN_JCS_SHA256);
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
    void goldenReleasePassesThroughV27AndLeavesNoProvisionalRows() throws IOException {
        assertThat(v27Context.getBeansOfType(Flyway.class)).isEmpty();
        TransactionTemplate transaction = v27Context.getBean(TransactionTemplate.class);
        assertThat(transaction.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transaction.getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(transaction.getTimeout()).isEqualTo(75);
        assertThat(transaction.isReadOnly()).isFalse();
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
        assertDryRunReportBytes(validation, PASS_REPORT_V1);
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
        assertThat(tableCounts(jdbc, true)).isEqualTo(allLegalBaseline);
        assertThat(tableCounts(jdbc, false)).isEqualTo(nonLegalBaseline);
    }

    @Test
    void isolatedWriterDoesNotExecuteGateSchemaPrivilegeOrReplayQueries() {
        Map<String, Long> baseline = requiredTableCounts(jdbc);
        GuardedWriterJdbcTemplate guardedJdbc = new GuardedWriterJdbcTemplate(
                v27Context.getBean(DataSource.class));
        LegalManifestGraphWriter writer = new LegalManifestGraphWriter(
                guardedJdbc,
                v27Context.getBean(LegalRequiredSetRevisionCalculator.class));
        TransactionTemplate transaction = v27Context.getBean(TransactionTemplate.class);

        LegalManifestGraphReceipt receipt = transaction.execute(status -> {
            try {
                return writer.writeNew(goldenRelease);
            } finally {
                status.setRollbackOnly();
            }
        });

        assertThat(receipt).isNotNull();
        assertThat(receipt.publicationUuid()).isNotNull();
        assertThat(guardedJdbc.sql())
                .anyMatch(statement -> statement.startsWith("SET CONSTRAINTS ALL IMMEDIATE"))
                .noneMatch(statement -> statement.contains("publication_external_id = ?"));
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
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
    void requiredSetRevisionsAreReconstructedFromPostgresAndTheTransactionRollsBack() {
        Map<String, Long> baseline = requiredTableCounts(jdbc);
        Map<String, Long> allLegalBaseline = tableCounts(jdbc, true);
        LegalManifestDatabaseGate gate = v27Context.getBean(LegalManifestDatabaseGate.class);
        LegalManifestGraphWriter writer = v27Context.getBean(LegalManifestGraphWriter.class);
        LegalRequiredSetRevisionCalculator calculator =
                v27Context.getBean(LegalRequiredSetRevisionCalculator.class);

        List<ScopeRevisionEvidence> evidence = Objects.requireNonNull(
                gate.execute(status -> {
                    try {
                        LegalManifestGraphReceipt receipt = writer.writeNew(goldenRelease);
                        UUID publicationId = receipt.publicationUuid();
                        assertImportedVersionsRemainDraft(publicationId);
                        return reconstructScopeRevisions(publicationId, calculator);
                    } finally {
                        status.setRollbackOnly();
                    }
                }),
                "required-set revision evidence");

        assertThat(evidence).hasSize(8);
        assertThat(evidence)
                .extracting(ScopeRevisionEvidence::scopeIdentity)
                .doesNotHaveDuplicates();
        assertThat(evidence).allSatisfy(scope ->
                assertThat(scope.calculatedRevision())
                        .as(scope.scopeIdentity())
                        .isEqualTo(scope.storedRevision()));
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
        assertThat(tableCounts(jdbc, true)).isEqualTo(allLegalBaseline);
    }

    @Test
    void existingPublicationExternalIdBlocksWithoutChangingTheBaseline() throws IOException {
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
        assertDryRunReportBytes(validation, BLOCKED_REPORT_V1);
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

    @Test
    void reusedHistoricalVersionsKeepTheirIdsOrdinalsAndScopeRevisions()
            throws Exception {
        commitValidatedRelease(goldenRelease);
        UUID seededPublicationId = publicationId(
                goldenRelease.plan().manifest().publicationId());
        HistoricalVersionGraph seededGraph = captureVersionGraph(seededPublicationId);
        LegalRequiredSetRevisionCalculator calculator =
                v27Context.getBean(LegalRequiredSetRevisionCalculator.class);
        List<ScopeRevisionEvidence> seededRevisions = reconstructScopeRevisions(
                seededPublicationId,
                calculator);
        ValidatedRelease incoming = copyAndValidateGolden(
                "release-reuse-revisions-v1",
                ReleaseMutation.NONE);
        Map<String, Long> baseline = requiredTableCounts(jdbc);
        Map<String, Long> allLegalBaseline = tableCounts(jdbc, true);
        LegalManifestDatabaseGate gate = v27Context.getBean(LegalManifestDatabaseGate.class);
        LegalManifestGraphWriter writer = v27Context.getBean(LegalManifestGraphWriter.class);

        HistoricalReuseEvidence evidence = Objects.requireNonNull(
                gate.execute(status -> {
                    try {
                        LegalManifestGraphReceipt receipt = writer.writeNew(incoming);
                        DryRunResult result = toDryRunResult(receipt);
                        UUID incomingPublicationId = receipt.publicationUuid();
                        return new HistoricalReuseEvidence(
                                result,
                                captureVersionGraph(incomingPublicationId),
                                reconstructScopeRevisions(incomingPublicationId, calculator));
                    } finally {
                        status.setRollbackOnly();
                    }
                }),
                "historical reuse evidence");

        assertThat(evidence.result().newDocumentVersions()).isZero();
        assertThat(evidence.result().reusedDocumentVersions()).isEqualTo(11);
        assertThat(evidence.result().newRequirementVersions()).isZero();
        assertThat(evidence.result().reusedRequirementVersions()).isEqualTo(6);
        assertThat(seededGraph.publicationDocuments()).hasSize(11);
        assertThat(seededGraph.publicationRequirements()).hasSize(6);
        assertThat(seededGraph.scopeMembers()).isNotEmpty();
        assertThat(seededGraph.requirementDocuments()).isNotEmpty();
        assertThat(evidence.graph())
                .as("the second publication must reuse every historical UUID and ordinal")
                .isEqualTo(seededGraph);
        assertThat(seededRevisions).hasSize(8).allSatisfy(scope ->
                assertThat(scope.calculatedRevision())
                        .as(scope.scopeIdentity())
                        .isEqualTo(scope.storedRevision()));
        assertThat(evidence.scopeRevisions())
                .hasSize(8)
                .containsExactlyElementsOf(seededRevisions)
                .allSatisfy(scope -> assertThat(scope.calculatedRevision())
                        .as(scope.scopeIdentity())
                        .isEqualTo(scope.storedRevision()));
        assertThat(requiredTableCounts(jdbc)).isEqualTo(baseline);
        assertThat(tableCounts(jdbc, true)).isEqualTo(allLegalBaseline);
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
    void v26IsRejectedWithoutRunningFlywayOrCreatingLegalTables() throws IOException {
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
        assertDryRunReportBytes(validation, ERROR_REPORT_V1);
        assertThat(currentFlywayVersion(v26Jdbc)).isEqualTo(versionBefore);
        assertThat(legalTableCount(v26Jdbc, V26_SCHEMA)).isZero();
    }

    private DryRunResult commitValidatedRelease(ValidatedRelease release) {
        LegalManifestDatabaseGate gate = v27Context.getBean(LegalManifestDatabaseGate.class);
        LegalManifestGraphWriter writer = v27Context.getBean(LegalManifestGraphWriter.class);
        LegalManifestGraphReceipt receipt = gate.execute(status -> writer.writeNew(release));
        return toDryRunResult(Objects.requireNonNull(receipt, "committed graph receipt"));
    }

    private static void assertDryRunReportBytes(
            LegalManifestValidation<DryRunResult> validation,
            String expected) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new LegalManifestReportWriter().write(
                LegalManifestReport.forDryRun(goldenRelease, validation),
                output);
        assertThat(output.toByteArray())
                .containsExactly(expected.getBytes(StandardCharsets.UTF_8));
    }

    private static DryRunResult toDryRunResult(LegalManifestGraphReceipt receipt) {
        return new DryRunResult(
                receipt.documents(),
                receipt.requirements(),
                receipt.scopes(),
                receipt.newDocumentLines(),
                receipt.newDocumentVersions(),
                receipt.reusedDocumentVersions(),
                receipt.newRequirementLines(),
                receipt.newRequirementVersions(),
                receipt.reusedRequirementVersions());
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

    private static List<ScopeRevisionEvidence> reconstructScopeRevisions(
            UUID publicationId,
            LegalRequiredSetRevisionCalculator calculator) {
        List<PersistedScopeRow> scopes = jdbc.query("""
                SELECT id, locale, contexto, audiencia, required_set_revision
                  FROM legal_requisito_conjuntos
                 WHERE publicacion_id = ?
                 ORDER BY locale, contexto, audiencia
                """, (resultSet, rowNumber) -> new PersistedScopeRow(
                resultSet.getObject("id", UUID.class),
                LocaleLegal.fromCodigo(resultSet.getString("locale")),
                ContextoLegal.valueOf(resultSet.getString("contexto")),
                resultSet.getString("audiencia"),
                resultSet.getString("required_set_revision")),
                publicationId);

        List<ScopeRevisionEvidence> evidence = new ArrayList<>(scopes.size());
        for (PersistedScopeRow scope : scopes) {
            List<PersistedRequirementRow> persistedRequirements = jdbc.query("""
                    SELECT m.manifest_ordinal AS member_ordinal,
                           pr.manifest_ordinal AS publication_ordinal,
                           rv.id AS requisito_version_id,
                           rl.contexto,
                           rl.tipo_acto,
                           rv.afirmacion,
                           rv.afirmacion_sha256,
                           rv.requerido
                      FROM legal_requisito_conjunto_miembros m
                      JOIN legal_publicacion_requisitos pr
                        ON pr.publicacion_id = m.publicacion_id
                       AND pr.requisito_version_id = m.requisito_version_id
                      JOIN legal_requisito_versiones rv
                        ON rv.id = m.requisito_version_id
                      JOIN legal_requisito_lineas rl
                        ON rl.id = m.requisito_linea_id
                     WHERE m.conjunto_id = ?
                     ORDER BY m.manifest_ordinal
                    """, (resultSet, rowNumber) -> new PersistedRequirementRow(
                    resultSet.getInt("member_ordinal"),
                    resultSet.getInt("publication_ordinal"),
                    resultSet.getObject("requisito_version_id", UUID.class),
                    ContextoLegal.valueOf(resultSet.getString("contexto")),
                    TipoActoLegal.valueOf(resultSet.getString("tipo_acto")),
                    resultSet.getString("afirmacion"),
                    resultSet.getString("afirmacion_sha256"),
                    resultSet.getBoolean("requerido")),
                    scope.id());

            List<RequirementProjection> requirements = new ArrayList<>(
                    persistedRequirements.size());
            for (PersistedRequirementRow requirement : persistedRequirements) {
                assertThat(requirement.memberOrdinal())
                        .as("member/publication ordinal for %s", requirement.versionId())
                        .isEqualTo(requirement.publicationOrdinal());
                requirements.add(new RequirementProjection(
                        requirement.versionId(),
                        requirement.context(),
                        requirement.actType(),
                        requirement.statement(),
                        requirement.statementSha256(),
                        reconstructDocuments(requirement.versionId()),
                        requirement.required()));
            }

            LegalRequiredSetProjection projection = new LegalRequiredSetProjection(
                    scope.context(),
                    scope.locale(),
                    requirements);
            evidence.add(new ScopeRevisionEvidence(
                    scope.context().name() + "/" + scope.audience(),
                    scope.storedRevision(),
                    calculator.calculate(projection)));
        }
        return List.copyOf(evidence);
    }

    private static UUID publicationId(String externalId) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT id
                  FROM legal_publicaciones
                 WHERE publication_external_id = ?
                """, UUID.class, externalId));
    }

    private static HistoricalVersionGraph captureVersionGraph(UUID publicationId) {
        List<PublicationDocumentReference> publicationDocuments = jdbc.query("""
                SELECT dl.clave,
                       pd.documento_version_id,
                       pd.manifest_ordinal
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv
                    ON dv.id = pd.documento_version_id
                  JOIN legal_documento_lineas dl
                    ON dl.id = dv.documento_linea_id
                 WHERE pd.publicacion_id = ?
                 ORDER BY pd.manifest_ordinal
                """, (resultSet, rowNumber) -> new PublicationDocumentReference(
                resultSet.getString("clave"),
                resultSet.getObject("documento_version_id", UUID.class),
                resultSet.getInt("manifest_ordinal")),
                publicationId);
        List<PublicationRequirementReference> publicationRequirements = jdbc.query("""
                SELECT rl.clave,
                       pr.requisito_version_id,
                       pr.manifest_ordinal
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                  JOIN legal_requisito_lineas rl
                    ON rl.id = rv.requisito_linea_id
                 WHERE pr.publicacion_id = ?
                 ORDER BY pr.manifest_ordinal
                """, (resultSet, rowNumber) -> new PublicationRequirementReference(
                resultSet.getString("clave"),
                resultSet.getObject("requisito_version_id", UUID.class),
                resultSet.getInt("manifest_ordinal")),
                publicationId);
        List<ScopeMemberReference> scopeMembers = jdbc.query("""
                SELECT c.locale,
                       c.contexto,
                       c.audiencia,
                       rl.clave,
                       m.requisito_version_id,
                       m.manifest_ordinal
                  FROM legal_requisito_conjuntos c
                  JOIN legal_requisito_conjunto_miembros m
                    ON m.conjunto_id = c.id
                  JOIN legal_requisito_versiones rv
                    ON rv.id = m.requisito_version_id
                  JOIN legal_requisito_lineas rl
                    ON rl.id = rv.requisito_linea_id
                 WHERE c.publicacion_id = ?
                 ORDER BY c.locale, c.contexto, c.audiencia, m.manifest_ordinal
                """, (resultSet, rowNumber) -> new ScopeMemberReference(
                resultSet.getString("locale"),
                resultSet.getString("contexto"),
                resultSet.getString("audiencia"),
                resultSet.getString("clave"),
                resultSet.getObject("requisito_version_id", UUID.class),
                resultSet.getInt("manifest_ordinal")),
                publicationId);
        List<RequirementDocumentReference> requirementDocuments = jdbc.query("""
                SELECT rl.clave AS requisito_clave,
                       pr.requisito_version_id,
                       rd.documento_ordinal,
                       dl.clave AS documento_clave,
                       rd.documento_version_id
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                  JOIN legal_requisito_lineas rl
                    ON rl.id = rv.requisito_linea_id
                  JOIN legal_requisito_documentos rd
                    ON rd.requisito_version_id = rv.id
                  JOIN legal_documento_versiones dv
                    ON dv.id = rd.documento_version_id
                  JOIN legal_documento_lineas dl
                    ON dl.id = dv.documento_linea_id
                 WHERE pr.publicacion_id = ?
                 ORDER BY pr.manifest_ordinal, rd.documento_ordinal
                """, (resultSet, rowNumber) -> new RequirementDocumentReference(
                resultSet.getString("requisito_clave"),
                resultSet.getObject("requisito_version_id", UUID.class),
                resultSet.getInt("documento_ordinal"),
                resultSet.getString("documento_clave"),
                resultSet.getObject("documento_version_id", UUID.class)),
                publicationId);
        return new HistoricalVersionGraph(
                publicationDocuments,
                publicationRequirements,
                scopeMembers,
                requirementDocuments);
    }

    private static void assertImportedVersionsRemainDraft(UUID publicationId) {
        List<String> states = jdbc.queryForList("""
                SELECT estado
                  FROM legal_documento_versiones
                 WHERE publicacion_intro_id = ?
                UNION ALL
                SELECT estado
                  FROM legal_requisito_versiones
                 WHERE publicacion_intro_id = ?
                """, String.class, publicationId, publicationId);

        assertThat(states).hasSize(17).containsOnly("BORRADOR");
    }

    private static List<DocumentProjection> reconstructDocuments(UUID requirementVersionId) {
        List<PersistedDocumentRow> rows = jdbc.query("""
                SELECT rd.documento_ordinal,
                       dv.id AS documento_version_id,
                       dl.tipo,
                       dv.version,
                       dv.titulo,
                       dv.contenido_markdown,
                       dv.sha256,
                       dv.vigente_desde,
                       dl.locale
                  FROM legal_requisito_documentos rd
                  JOIN legal_documento_versiones dv
                    ON dv.id = rd.documento_version_id
                  JOIN legal_documento_lineas dl
                    ON dl.id = dv.documento_linea_id
                 WHERE rd.requisito_version_id = ?
                 ORDER BY rd.documento_ordinal
                """, (resultSet, rowNumber) -> new PersistedDocumentRow(
                resultSet.getInt("documento_ordinal"),
                resultSet.getObject("documento_version_id", UUID.class),
                TipoDocumentoLegal.valueOf(resultSet.getString("tipo")),
                resultSet.getString("version"),
                resultSet.getString("titulo"),
                resultSet.getString("contenido_markdown"),
                resultSet.getString("sha256"),
                resultSet.getObject("vigente_desde", OffsetDateTime.class),
                LocaleLegal.fromCodigo(resultSet.getString("locale"))),
                requirementVersionId);

        List<DocumentProjection> documents = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            PersistedDocumentRow row = rows.get(index);
            assertThat(row.ordinal())
                    .as("document ordinal for %s", requirementVersionId)
                    .isEqualTo(index + 1);
            documents.add(new DocumentProjection(
                    row.versionId(),
                    row.type(),
                    row.version(),
                    row.title(),
                    row.markdown(),
                    row.sha256(),
                    row.effectiveAt(),
                    row.locale()));
        }
        return List.copyOf(documents);
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

    private static final class GuardedWriterJdbcTemplate extends JdbcTemplate {

        private final List<String> sql = new ArrayList<>();

        GuardedWriterJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        List<String> sql() {
            return List.copyOf(sql);
        }

        @Override
        public void execute(String statement) {
            inspect(statement);
            super.execute(statement);
        }

        @Override
        public int update(String statement, Object... arguments) {
            inspect(statement);
            return super.update(statement, arguments);
        }

        @Override
        public <T> List<T> query(
                String statement,
                RowMapper<T> rowMapper,
                Object... arguments) {
            inspect(statement);
            return super.query(statement, rowMapper, arguments);
        }

        @Override
        public <T> T queryForObject(
                String statement,
                Class<T> requiredType,
                Object... arguments) {
            inspect(statement);
            return super.queryForObject(statement, requiredType, arguments);
        }

        @Override
        public <T> List<T> queryForList(
                String statement,
                Class<T> elementType,
                Object... arguments) {
            inspect(statement);
            return super.queryForList(statement, elementType, arguments);
        }

        @Override
        public List<Map<String, Object>> queryForList(
                String statement,
                Object... arguments) {
            inspect(statement);
            return super.queryForList(statement, arguments);
        }

        private void inspect(String statement) {
            String normalized = statement.strip().replaceAll("\\s+", " ");
            String lower = normalized.toLowerCase(java.util.Locale.ROOT);
            boolean requiredConstraintFlush =
                    lower.equals("set constraints all immediate");
            if ((lower.startsWith("set ") && !requiredConstraintFlush)
                    || lower.contains("set_config(")
                    || lower.contains("pg_advisory")
                    || lower.contains("pg_catalog.")
                    || lower.contains("information_schema.")
                    || lower.contains("current_schema")
                    || lower.contains("flyway_schema_history")
                    || lower.contains("_privilege(")) {
                throw new AssertionError("El writer ejecutó SQL reservado al gate/preflight");
            }
            sql.add(normalized);
        }
    }

    private record PersistedScopeRow(
            UUID id,
            LocaleLegal locale,
            ContextoLegal context,
            String audience,
            String storedRevision
    ) { }

    private record PersistedRequirementRow(
            int memberOrdinal,
            int publicationOrdinal,
            UUID versionId,
            ContextoLegal context,
            TipoActoLegal actType,
            String statement,
            String statementSha256,
            boolean required
    ) { }

    private record PersistedDocumentRow(
            int ordinal,
            UUID versionId,
            TipoDocumentoLegal type,
            String version,
            String title,
            String markdown,
            String sha256,
            OffsetDateTime effectiveAt,
            LocaleLegal locale
    ) { }

    private record ScopeRevisionEvidence(
            String scopeIdentity,
            String storedRevision,
            String calculatedRevision
    ) { }

    private record PublicationDocumentReference(
            String key,
            UUID versionId,
            int manifestOrdinal
    ) { }

    private record PublicationRequirementReference(
            String key,
            UUID versionId,
            int manifestOrdinal
    ) { }

    private record ScopeMemberReference(
            String locale,
            String context,
            String audience,
            String requirementKey,
            UUID requirementVersionId,
            int manifestOrdinal
    ) { }

    private record RequirementDocumentReference(
            String requirementKey,
            UUID requirementVersionId,
            int documentOrdinal,
            String documentKey,
            UUID documentVersionId
    ) { }

    private record HistoricalVersionGraph(
            List<PublicationDocumentReference> publicationDocuments,
            List<PublicationRequirementReference> publicationRequirements,
            List<ScopeMemberReference> scopeMembers,
            List<RequirementDocumentReference> requirementDocuments
    ) {
        private HistoricalVersionGraph {
            publicationDocuments = List.copyOf(publicationDocuments);
            publicationRequirements = List.copyOf(publicationRequirements);
            scopeMembers = List.copyOf(scopeMembers);
            requirementDocuments = List.copyOf(requirementDocuments);
        }
    }

    private record HistoricalReuseEvidence(
            DryRunResult result,
            HistoricalVersionGraph graph,
            List<ScopeRevisionEvidence> scopeRevisions
    ) {
        private HistoricalReuseEvidence {
            scopeRevisions = List.copyOf(scopeRevisions);
        }
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
