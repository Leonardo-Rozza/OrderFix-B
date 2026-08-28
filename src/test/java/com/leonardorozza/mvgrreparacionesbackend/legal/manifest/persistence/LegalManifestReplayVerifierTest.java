package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.DocumentPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.ScopePlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalManifestReplayVerifierTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final OffsetDateTime IMPORTED_AT = OffsetDateTime.of(
            2026, 8, 25, 18, 30, 0, 123_456_000, ZoneOffset.UTC);
    private static final OffsetDateTime SEALED_AT = IMPORTED_AT.plusSeconds(1);

    private static ValidatedRelease release;

    private JdbcTemplate database;
    private SelectOnlyJdbcTemplate verifierJdbc;
    private LegalRequiredSetRevisionCalculator revisionCalculator;
    private LegalManifestOriginGraphVerifier originVerifier;
    private LegalManifestReplayVerifier verifier;

    @BeforeAll
    static void validateGoldenRelease() throws URISyntaxException {
        Path manifest = Path.of(Objects.requireNonNull(
                LegalManifestReplayVerifierTest.class.getResource(GOLDEN_MANIFEST)).toURI());
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifest);
        assertThat(validation.status())
                .as("issues=%s", validation.issues())
                .isEqualTo(LegalManifestStatus.PASS);
        release = validation.value().orElseThrow();
    }

    @BeforeEach
    void createDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:legal-replay-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        database = new JdbcTemplate(dataSource);
        createSchema(database);
        verifierJdbc = new SelectOnlyJdbcTemplate(dataSource);
        revisionCalculator = new LegalRequiredSetRevisionCalculator();
        originVerifier = new LegalManifestOriginGraphVerifier(verifierJdbc, revisionCalculator);
        verifier = new LegalManifestReplayVerifier(verifierJdbc, originVerifier);
    }

    @Test
    void exactReplayReconstructsTheOriginalReceiptWithoutWriting() {
        SeededGraph graph = seedExactGraph();

        LegalManifestGraphReceipt receipt = verifier.verify(release, graph.publicationId());

        assertThat(receipt.publicationUuid()).isEqualTo(graph.publicationId());
        assertThat(receipt.importedAt()).isEqualTo(IMPORTED_AT.toInstant());
        assertThat(receipt.sealedAt()).isEqualTo(SEALED_AT.toInstant());
        assertThat(receipt.documents()).isEqualTo(release.documentCount());
        assertThat(receipt.requirements()).isEqualTo(release.requirementCount());
        assertThat(receipt.scopes()).isEqualTo(release.scopeCount());
        assertThat(receipt.newDocumentLines()).isEqualTo(graph.newDocumentLines());
        assertThat(receipt.newDocumentVersions()).isEqualTo(graph.newDocumentVersions());
        assertThat(receipt.reusedDocumentVersions())
                .isEqualTo(release.documentCount() - graph.newDocumentVersions());
        assertThat(receipt.newRequirementLines()).isEqualTo(graph.newRequirementLines());
        assertThat(receipt.newRequirementVersions()).isEqualTo(graph.newRequirementVersions());
        assertThat(receipt.reusedRequirementVersions())
                .isEqualTo(release.requirementCount() - graph.newRequirementVersions());
        assertThat(verifierJdbc.validationCalls()).isOne();
        assertThat(verifierJdbc.statements())
                .isNotEmpty()
                .allMatch(statement -> statement.startsWith("select "));
        assertThat(verifierJdbc.statements().get(0))
                .contains("from legal_publicaciones", "for update");
        assertThat(verifierJdbc.statements().get(1))
                .contains("from legal_publicaciones")
                .doesNotContain("for update", "for share");
        assertThat(verifierJdbc.statements().get(2))
                .contains("legal_validar_publicacion_sellada");
        assertThat(verifierJdbc.statements().stream()
                .filter(LegalManifestReplayVerifierTest::isMultirowReplayRead)
                .toList())
                .hasSize(7)
                .allMatch(statement -> statement.endsWith("limit ?"));
    }

    @Test
    void headerMismatchBlocksBeforeCallingTheV27Function() {
        SeededGraph graph = seedExactGraph();
        database.update("""
                UPDATE legal_publicaciones
                   SET razon_social = 'Otra entidad'
                 WHERE id = ?
                """, graph.publicationId());

        assertThatThrownBy(() -> verifier.verify(release, graph.publicationId()))
                .isInstanceOf(LegalImportBlockedException.class);
        assertThat(verifierJdbc.validationCalls()).isZero();
    }

    @Test
    void mutableEditorialStateIsIgnoredButIntrinsicGraphCorruptionBlocks() {
        SeededGraph graph = seedExactGraph();
        database.update("""
                UPDATE legal_documento_versiones
                   SET titulo = 'Contenido persistido alterado'
                 WHERE id = ?
                """, graph.firstDocumentVersionId());

        assertThatThrownBy(() -> verifier.verify(release, graph.publicationId()))
                .isInstanceOf(LegalImportBlockedException.class);
        assertThat(verifierJdbc.validationCalls()).isOne();
    }

    @Test
    void aRevisionThatCannotBeReconstructedDoesNotPassAsReplay() {
        SeededGraph graph = seedExactGraph();
        database.update("""
                UPDATE legal_requisito_conjuntos
                   SET required_set_revision = ?
                 WHERE id = ?
                """, "sha256:" + "0".repeat(64), graph.firstScopeId());

        assertThatThrownBy(() -> verifier.verify(release, graph.publicationId()))
                .isInstanceOf(LegalImportBlockedException.class);
        assertThat(verifierJdbc.validationCalls()).isOne();
    }

    @Test
    void originIsStrictlySelectOnlyAndNeverCallsTheV27Validator() {
        SeededGraph graph = seedExactGraph();

        LegalManifestGraphReceipt receipt = originVerifier.verify(
                release,
                graph.publicationId());

        assertThat(receipt.publicationUuid()).isEqualTo(graph.publicationId());
        assertThat(verifierJdbc.validationCalls()).isZero();
        assertThat(verifierJdbc.statements())
                .isNotEmpty()
                .allMatch(statement -> statement.startsWith("select "))
                .noneMatch(statement -> statement.contains("for update")
                        || statement.contains("for share")
                        || statement.contains("legal_validar_publicacion_sellada"));
    }

    @Test
    void originClassifiesMissingAndOpenTargetsAsNotSealed() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> originVerifier.verify(release, missing))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.PUBLICATION_NOT_SEALED));

        SeededGraph open = seedExactGraph();
        database.update("""
                UPDATE legal_publicaciones
                   SET estado_construccion = 'ABIERTO', sellado_en = NULL
                 WHERE id = ?
                """, open.publicationId());

        assertThatThrownBy(() -> originVerifier.verify(release, open.publicationId()))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.PUBLICATION_NOT_SEALED));
        assertThat(verifierJdbc.validationCalls()).isZero();
    }

    @Test
    void originClassifiesHeaderGraphAndRevisionMismatchesPrecisely() {
        SeededGraph headerMismatch = seedExactGraph();
        database.update("""
                UPDATE legal_publicaciones
                   SET razon_social = 'Otra entidad'
                 WHERE id = ?
                """, headerMismatch.publicationId());

        assertThatThrownBy(() -> originVerifier.verify(release, headerMismatch.publicationId()))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH));

        SeededGraph graphMismatch = seedExactGraph();
        database.update("""
                UPDATE legal_documento_versiones
                   SET titulo = 'Contenido persistido alterado'
                 WHERE id = ?
                """, graphMismatch.firstDocumentVersionId());

        assertThatThrownBy(() -> originVerifier.verify(release, graphMismatch.publicationId()))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH));

        SeededGraph revisionMismatch = seedExactGraph();
        database.update("""
                UPDATE legal_requisito_conjuntos
                   SET required_set_revision = ?
                 WHERE id = ?
                """, "sha256:" + "0".repeat(64), revisionMismatch.firstScopeId());

        assertThatThrownBy(() -> originVerifier.verify(release, revisionMismatch.publicationId()))
                .isInstanceOfSatisfying(LegalEditorialBlockedException.class, failure ->
                        assertThat(failure.issue().code())
                                .isEqualTo(LegalManifestIssueCode.REVISION_MISMATCH));
        assertThat(verifierJdbc.validationCalls()).isZero();
    }

    @Test
    void aV27IntegrityFailureIsPreservedAsAReplayConflict() {
        SeededGraph graph = seedExactGraph();
        DataIntegrityViolationException databaseFailure =
                new DataIntegrityViolationException("sealed graph is inconsistent");
        verifierJdbc.failValidationWith(databaseFailure);

        assertThatThrownBy(() -> verifier.verify(release, graph.publicationId()))
                .isInstanceOf(LegalImportBlockedException.class)
                .hasCause(databaseFailure);
        assertThat(verifierJdbc.validationCalls()).isOne();
    }

    private SeededGraph seedExactGraph() {
        LegalPublicationPlan plan = release.plan();
        UUID publicationId = UUID.randomUUID();
        insertPublication(publicationId, plan);

        UUID historicalPublicationId = UUID.randomUUID();
        Map<String, PersistedDocument> documents = new LinkedHashMap<>();
        int newDocumentLines = 0;
        int newDocumentVersions = 0;
        for (DocumentPlan document : plan.documents()) {
            int ordinal = document.manifestOrdinal() + 1;
            boolean newLine = document.manifestOrdinal() % 3 == 0;
            boolean newVersion = document.manifestOrdinal() % 3 != 2;
            UUID lineId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            UUID lineIntro = newLine ? publicationId : historicalPublicationId;
            UUID versionIntro = newVersion ? publicationId : historicalPublicationId;
            if (newLine) {
                newDocumentLines++;
            }
            if (newVersion) {
                newDocumentVersions++;
            }
            DocumentEntry declaration = document.declaration();
            database.update("""
                    INSERT INTO legal_documento_lineas
                        (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, lineId, declaration.key(), declaration.type().name(),
                    declaration.locale().getCodigo(), lineIntro, IMPORTED_AT);
            database.update("""
                    INSERT INTO legal_documento_versiones
                        (id, documento_linea_id, publicacion_intro_id, version,
                         lineage_ordinal, titulo, contenido_markdown, sha256,
                         vigente_desde, requires_reacceptance, estado,
                         estado_cambiado_en, ultimo_motivo, reemplazo_lote_id)
                    VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, 'RETIRADA', ?, 'test', NULL)
                    """, versionId, lineId, versionIntro, declaration.version(),
                    document.title(), document.markdown(), declaration.sha256(),
                    declaration.effectiveAt(), declaration.requiresReacceptance(), SEALED_AT);
            for (var context : declaration.contexts()) {
                database.update("""
                        INSERT INTO legal_documento_contextos
                            (documento_version_id, contexto)
                        VALUES (?, ?)
                        """, versionId, context.name());
            }
            database.update("""
                    INSERT INTO legal_publicacion_documentos
                        (publicacion_id, documento_version_id, manifest_ordinal)
                    VALUES (?, ?, ?)
                    """, publicationId, versionId, ordinal);
            documents.put(declaration.key(), new PersistedDocument(
                    document, lineId, versionId));
        }

        Map<Integer, PersistedRequirement> requirements = new LinkedHashMap<>();
        int newRequirementLines = 0;
        int newRequirementVersions = 0;
        for (int index = 0; index < plan.manifest().requirements().size(); index++) {
            RequirementEntry declaration = plan.manifest().requirements().get(index);
            boolean newLine = index % 3 == 0;
            boolean newVersion = index % 3 != 2;
            UUID lineId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            UUID lineIntro = newLine ? publicationId : historicalPublicationId;
            UUID versionIntro = newVersion ? publicationId : historicalPublicationId;
            if (newLine) {
                newRequirementLines++;
            }
            if (newVersion) {
                newRequirementVersions++;
            }
            database.update("""
                    INSERT INTO legal_requisito_lineas
                        (id, clave, locale, contexto, tipo_acto, publicacion_intro_id, creado_en)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, lineId, declaration.key(), plan.manifest().locale().getCodigo(),
                    declaration.context().name(), declaration.actType().name(),
                    lineIntro, IMPORTED_AT);
            for (var audience : declaration.roles()) {
                database.update("""
                        INSERT INTO legal_requisito_audiencias
                            (requisito_linea_id, audiencia)
                        VALUES (?, ?)
                        """, lineId, audience.name());
            }
            database.update("""
                    INSERT INTO legal_requisito_versiones
                        (id, requisito_linea_id, publicacion_intro_id, version,
                         lineage_ordinal, afirmacion, afirmacion_sha256, requerido,
                         requires_reacceptance, estado, estado_cambiado_en, ultimo_motivo)
                    VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, 'REEMPLAZADA', ?, NULL)
                    """, versionId, lineId, versionIntro, declaration.version(),
                    declaration.statement(), declaration.statementSha256(),
                    declaration.required(), declaration.requiresReacceptance(), SEALED_AT);
            List<PersistedDocument> referencedDocuments = declaration.documents().stream()
                    .map(documents::get)
                    .map(document -> Objects.requireNonNull(document, "persisted document"))
                    .toList();
            for (int documentIndex = 0;
                    documentIndex < referencedDocuments.size();
                    documentIndex++) {
                database.update("""
                        INSERT INTO legal_requisito_documentos
                            (requisito_version_id, documento_version_id, documento_ordinal)
                        VALUES (?, ?, ?)
                        """, versionId,
                        referencedDocuments.get(documentIndex).versionId(),
                        documentIndex + 1);
            }
            database.update("""
                    INSERT INTO legal_publicacion_requisitos
                        (publicacion_id, requisito_version_id, manifest_ordinal)
                    VALUES (?, ?, ?)
                    """, publicationId, versionId, index + 1);
            requirements.put(index, new PersistedRequirement(
                    declaration, lineId, versionId, referencedDocuments));
        }

        UUID firstScopeId = null;
        for (ScopePlan scope : plan.scopes()) {
            UUID scopeId = UUID.randomUUID();
            if (firstScopeId == null) {
                firstScopeId = scopeId;
            }
            String revision = revisionCalculator.calculate(projectScope(scope, requirements));
            database.update("""
                    INSERT INTO legal_requisito_conjuntos
                        (id, publicacion_id, locale, contexto, audiencia,
                         required_set_revision, creado_en)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, scopeId, publicationId, scope.locale().getCodigo(),
                    scope.context().name(), scope.audience().name(), revision, IMPORTED_AT);
            for (var member : scope.requirements()) {
                PersistedRequirement requirement = Objects.requireNonNull(
                        requirements.get(member.manifestOrdinal()), "persisted requirement");
                database.update("""
                        INSERT INTO legal_requisito_conjunto_miembros
                            (conjunto_id, publicacion_id, requisito_version_id,
                             requisito_linea_id, manifest_ordinal)
                        VALUES (?, ?, ?, ?, ?)
                        """, scopeId, publicationId, requirement.versionId(),
                        requirement.lineId(), member.manifestOrdinal() + 1);
            }
        }

        return new SeededGraph(
                publicationId,
                documents.values().iterator().next().versionId(),
                Objects.requireNonNull(firstScopeId, "first scope"),
                newDocumentLines,
                newDocumentVersions,
                newRequirementLines,
                newRequirementVersions);
    }

    private void insertPublication(UUID publicationId, LegalPublicationPlan plan) {
        var manifest = plan.manifest();
        var publisher = manifest.publisherSnapshot();
        var contacts = publisher.contacts();
        var legalReview = manifest.review().legal();
        var accountingReview = manifest.review().accounting();
        database.update("""
                INSERT INTO legal_publicaciones
                    (id, publication_external_id, schema_version, locale,
                     manifest_sha256, manifest_canonico, razon_social, cuit,
                     domicilio_legal, jurisdiccion, horario_atencion,
                     email_legal, email_privacidad, email_soporte,
                     revision_legal_estado, revision_legal_referencia, revision_legal_en,
                     revision_contable_estado, revision_contable_referencia,
                     revision_contable_en, estado_construccion, importado_en, sellado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'APROBADA', ?, ?, 'APROBADA', ?, ?, 'SELLADO', ?, ?)
                """, publicationId, manifest.publicationId(), manifest.schemaVersion(),
                manifest.locale().getCodigo(), plan.manifestSha256(), plan.canonicalJson(),
                publisher.legalName(), publisher.taxId().replace("-", ""),
                publisher.legalAddress(), publisher.jurisdiction(), publisher.businessHours(),
                contacts.legalEmail(), contacts.privacyEmail(), contacts.supportEmail(),
                legalReview.reference(), legalReview.reviewedAt(),
                accountingReview.reference(), accountingReview.reviewedAt(),
                IMPORTED_AT, SEALED_AT);
    }

    private static LegalRequiredSetProjection projectScope(
            ScopePlan scope,
            Map<Integer, PersistedRequirement> requirements) {
        List<RequirementProjection> projections = scope.requirements().stream()
                .map(member -> Objects.requireNonNull(
                        requirements.get(member.manifestOrdinal()), "persisted requirement"))
                .map(LegalManifestReplayVerifierTest::projectRequirement)
                .toList();
        return new LegalRequiredSetProjection(scope.context(), scope.locale(), projections);
    }

    private static RequirementProjection projectRequirement(
            PersistedRequirement requirement) {
        return new RequirementProjection(
                requirement.versionId(),
                requirement.declaration().context(),
                requirement.declaration().actType(),
                requirement.declaration().statement(),
                requirement.declaration().statementSha256(),
                requirement.documents().stream()
                        .map(LegalManifestReplayVerifierTest::projectDocument)
                        .toList(),
                requirement.declaration().required());
    }

    private static DocumentProjection projectDocument(PersistedDocument document) {
        DocumentPlan plan = document.plan();
        DocumentEntry declaration = plan.declaration();
        return new DocumentProjection(
                document.versionId(),
                declaration.type(),
                declaration.version(),
                plan.title(),
                plan.markdown(),
                declaration.sha256(),
                declaration.effectiveAt(),
                declaration.locale());
    }

    private static boolean isMultirowReplayRead(String statement) {
        return statement.contains("from legal_publicacion_documentos")
                || statement.contains("from legal_publicacion_requisitos")
                || statement.contains("from legal_requisito_conjuntos");
    }

    private static void createSchema(JdbcTemplate jdbc) {
        for (String statement : List.of(
                """
                CREATE TABLE legal_publicaciones (
                    id UUID PRIMARY KEY, publication_external_id VARCHAR(120),
                    schema_version INTEGER, locale VARCHAR(5), manifest_sha256 VARCHAR(64),
                    manifest_canonico CLOB, razon_social VARCHAR(200), cuit VARCHAR(11),
                    domicilio_legal VARCHAR(500), jurisdiccion VARCHAR(200),
                    horario_atencion VARCHAR(300), email_legal VARCHAR(320),
                    email_privacidad VARCHAR(320), email_soporte VARCHAR(320),
                    revision_legal_estado VARCHAR(20), revision_legal_referencia VARCHAR(500),
                    revision_legal_en TIMESTAMP WITH TIME ZONE,
                    revision_contable_estado VARCHAR(20),
                    revision_contable_referencia VARCHAR(500),
                    revision_contable_en TIMESTAMP WITH TIME ZONE,
                    estado_construccion VARCHAR(10),
                    importado_en TIMESTAMP WITH TIME ZONE,
                    sellado_en TIMESTAMP WITH TIME ZONE)
                """,
                """
                CREATE TABLE legal_documento_lineas (
                    id UUID PRIMARY KEY, clave VARCHAR(120), tipo VARCHAR(50), locale VARCHAR(5),
                    publicacion_intro_id UUID, creado_en TIMESTAMP WITH TIME ZONE)
                """,
                """
                CREATE TABLE legal_documento_versiones (
                    id UUID PRIMARY KEY, documento_linea_id UUID, publicacion_intro_id UUID,
                    version VARCHAR(64), lineage_ordinal INTEGER, titulo VARCHAR(300),
                    contenido_markdown CLOB, sha256 VARCHAR(64),
                    vigente_desde TIMESTAMP WITH TIME ZONE, requires_reacceptance BOOLEAN,
                    estado VARCHAR(20), estado_cambiado_en TIMESTAMP WITH TIME ZONE,
                    ultimo_motivo VARCHAR(1000), reemplazo_lote_id UUID)
                """,
                """
                CREATE TABLE legal_documento_contextos (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    documento_version_id UUID, contexto VARCHAR(40))
                """,
                """
                CREATE TABLE legal_publicacion_documentos (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    publicacion_id UUID, documento_version_id UUID, manifest_ordinal INTEGER)
                """,
                """
                CREATE TABLE legal_requisito_lineas (
                    id UUID PRIMARY KEY, clave VARCHAR(120), locale VARCHAR(5),
                    contexto VARCHAR(40), tipo_acto VARCHAR(20), publicacion_intro_id UUID,
                    creado_en TIMESTAMP WITH TIME ZONE)
                """,
                """
                CREATE TABLE legal_requisito_audiencias (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    requisito_linea_id UUID, audiencia VARCHAR(20))
                """,
                """
                CREATE TABLE legal_requisito_versiones (
                    id UUID PRIMARY KEY, requisito_linea_id UUID, publicacion_intro_id UUID,
                    version VARCHAR(64), lineage_ordinal INTEGER, afirmacion CLOB,
                    afirmacion_sha256 VARCHAR(64), requerido BOOLEAN,
                    requires_reacceptance BOOLEAN, estado VARCHAR(20),
                    estado_cambiado_en TIMESTAMP WITH TIME ZONE,
                    ultimo_motivo VARCHAR(1000))
                """,
                """
                CREATE TABLE legal_requisito_documentos (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    requisito_version_id UUID, documento_version_id UUID,
                    documento_ordinal INTEGER)
                """,
                """
                CREATE TABLE legal_publicacion_requisitos (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    publicacion_id UUID, requisito_version_id UUID, manifest_ordinal INTEGER)
                """,
                """
                CREATE TABLE legal_requisito_conjuntos (
                    id UUID PRIMARY KEY, publicacion_id UUID, locale VARCHAR(5),
                    contexto VARCHAR(40), audiencia VARCHAR(20),
                    required_set_revision VARCHAR(71), creado_en TIMESTAMP WITH TIME ZONE)
                """,
                """
                CREATE TABLE legal_requisito_conjunto_miembros (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    conjunto_id UUID, publicacion_id UUID, requisito_version_id UUID,
                    requisito_linea_id UUID, manifest_ordinal INTEGER)
                """)) {
            jdbc.execute(statement);
        }
    }

    private static final class SelectOnlyJdbcTemplate extends JdbcTemplate {

        private final List<String> statements = new ArrayList<>();
        private int validationCalls;
        private RuntimeException validationFailure;

        SelectOnlyJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        List<String> statements() {
            return List.copyOf(statements);
        }

        int validationCalls() {
            return validationCalls;
        }

        void failValidationWith(RuntimeException failure) {
            validationFailure = Objects.requireNonNull(failure, "failure");
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
        public List<Map<String, Object>> queryForList(
                String statement,
                Object... arguments) {
            inspect(statement);
            if (normalized(statement).contains("legal_validar_publicacion_sellada")) {
                validationCalls++;
                if (validationFailure != null) {
                    throw validationFailure;
                }
                return List.of();
            }
            return super.queryForList(statement, arguments);
        }

        private void inspect(String statement) {
            String normalized = normalized(statement);
            if (!normalized.startsWith("select ")) {
                throw new AssertionError("El replay ejecutó SQL no-SELECT: " + normalized);
            }
            statements.add(normalized);
        }

        private static String normalized(String statement) {
            return statement.strip()
                    .replaceAll("\\s+", " ")
                    .toLowerCase(java.util.Locale.ROOT);
        }
    }

    private record PersistedDocument(
            DocumentPlan plan,
            UUID lineId,
            UUID versionId
    ) { }

    private record PersistedRequirement(
            RequirementEntry declaration,
            UUID lineId,
            UUID versionId,
            List<PersistedDocument> documents
    ) {
        PersistedRequirement {
            documents = List.copyOf(documents);
        }
    }

    private record SeededGraph(
            UUID publicationId,
            UUID firstDocumentVersionId,
            UUID firstScopeId,
            int newDocumentLines,
            int newDocumentVersions,
            int newRequirementLines,
            int newRequirementVersions
    ) { }
}
