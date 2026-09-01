package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.Artifacts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.StdoutMode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedEditorialPlanReader;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialPlanValidator.ValidatedEditorialPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRestrictedEditorialRoleFixture;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRestrictedImportRoleFixture;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Test-only files, process arguments and owner observations for the real editorial JAR. */
final class LegalEditorialProcessFixture {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String EFFECTIVE_AT = "2026-01-01T00:00:00-03:00";
    private static final String REPLACED_DOCUMENT_KEY = "terminos";
    private static final String SUCCESSOR_VERSION = "2.0.0";
    private static final String RETIRED_DOCUMENT_KEY = "aviso-clientes-taller";
    private static final String RETIRED_REQUIREMENT_KEY =
            "customer-photo-attestation";
    private static final String DOCUMENT_RETIREMENT_REASON =
            "Retiro mixto controlado del fixture de procesos editoriales";
    private static final String REQUIREMENT_RETIREMENT_REASON =
            "Retiro de requisito controlado del fixture de procesos editoriales";
    private static final String NORMAL_START_CLASS =
            "com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication";
    private static final String PROCESS_SECRET =
            "editorial-process-secret-must-never-leak";
    private static final String LEGACY_DATASOURCE_SECRET =
            "legacy-datasource-secret-must-never-leak";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DATABASE_IDENTIFIER =
            Pattern.compile("[a-z][a-z0-9_]*");

    private final Path temporaryDirectory;
    private final Class<?> resourceAnchor;
    private final Artifacts artifacts;
    private final JdbcTemplate owner;
    private final LegalRestrictedImportRoleFixture.Credentials importCredentials;
    private final LegalRestrictedEditorialRoleFixture.Credentials editorialCredentials;

    LegalEditorialProcessFixture(
            Path temporaryDirectory,
            Class<?> resourceAnchor,
            Artifacts artifacts,
            JdbcTemplate owner,
            LegalRestrictedImportRoleFixture.Credentials importCredentials,
            LegalRestrictedEditorialRoleFixture.Credentials editorialCredentials) {
        this.temporaryDirectory = Objects.requireNonNull(
                temporaryDirectory,
                "temporaryDirectory").toAbsolutePath().normalize();
        this.resourceAnchor = Objects.requireNonNull(resourceAnchor, "resourceAnchor");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.importCredentials = Objects.requireNonNull(
                importCredentials,
                "importCredentials");
        this.editorialCredentials = Objects.requireNonNull(
                editorialCredentials,
                "editorialCredentials");
        if (!Files.isDirectory(this.temporaryDirectory)) {
            throw new IllegalArgumentException("El directorio temporal de 10E no existe");
        }
    }

    ReleaseArtifact copyRelease(String publicationId) throws Exception {
        String requiredPublicationId = requireVisibleToken(
                publicationId,
                "publicationId");
        Path sourceManifest = Path.of(Objects.requireNonNull(
                resourceAnchor.getResource(GOLDEN_MANIFEST),
                GOLDEN_MANIFEST).toURI());
        Path sourceDirectory = sourceManifest.getParent();
        Path destinationDirectory = releaseDirectory(requiredPublicationId);
        copyDirectory(sourceDirectory, destinationDirectory);

        Path manifestPath = destinationDirectory.resolve("publication-manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(manifestPath.toFile());
        manifest.put("publicationId", requiredPublicationId);
        for (JsonNode document : manifest.withArray("documents")) {
            ((ObjectNode) document).put("effectiveAt", EFFECTIVE_AT);
        }
        Files.writeString(
                manifestPath,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + '\n',
                StandardCharsets.UTF_8);

        return validateRelease(destinationDirectory, manifestPath);
    }

    /** Creates the deterministic one-to-one successor used by the real REPLACE process. */
    ReleaseArtifact successor(ReleaseArtifact source, String publicationId) throws Exception {
        ReleaseArtifact requiredSource = Objects.requireNonNull(source, "source");
        if (!sha256Tree(requiredSource.directory())
                .equals(requiredSource.sha256ByRelativePath())) {
            throw new IllegalStateException(
                    "El release fuente cambió antes de construir su sucesor");
        }
        Path destinationDirectory = releaseDirectory(requireVisibleToken(
                publicationId,
                "publicationId"));
        copyDirectory(requiredSource.directory(), destinationDirectory);

        Path manifestPath = destinationDirectory.resolve("publication-manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(manifestPath.toFile());
        manifest.put("publicationId", publicationId);
        ObjectNode replacedDocument = document(manifest, REPLACED_DOCUMENT_KEY);
        if (SUCCESSOR_VERSION.equals(replacedDocument.path("version").textValue())) {
            throw new IllegalArgumentException(
                    "El release fuente ya usa la versión reservada para el sucesor");
        }
        replacedDocument.put("version", SUCCESSOR_VERSION);
        for (JsonNode candidate : manifest.withArray("requirements")) {
            ObjectNode requirement = (ObjectNode) candidate;
            if (containsText(
                    requirement.withArray("documents"),
                    REPLACED_DOCUMENT_KEY)) {
                requirement.put("version", SUCCESSOR_VERSION);
            }
        }
        Files.writeString(
                manifestPath,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + '\n',
                StandardCharsets.UTF_8);
        return validateRelease(destinationDirectory, manifestPath);
    }

    ProcessResult executeImport(ReleaseArtifact release) throws Exception {
        ReleaseArtifact requiredRelease = Objects.requireNonNull(release, "release");
        List<String> arguments = new ArrayList<>();
        arguments.add("import");
        arguments.addAll(releaseConfirmationArguments(requiredRelease));
        return LegalCliProcessSupport.executeJar(
                artifacts,
                temporaryDirectory,
                List.of(),
                arguments,
                importEnvironment(),
                StdoutMode.CAPTURE);
    }

    ProcessResult executeEditorial(
            String command,
            ReleaseArtifact release) throws Exception {
        String requiredCommand = requireVisibleToken(command, "command");
        ReleaseArtifact requiredRelease = Objects.requireNonNull(release, "release");
        requireCommandPlanContract(requiredCommand, false);
        List<String> arguments = new ArrayList<>();
        arguments.add(requiredCommand);
        arguments.addAll(releaseConfirmationArguments(requiredRelease));
        return LegalCliProcessSupport.executeJar(
                artifacts,
                temporaryDirectory,
                List.of(),
                arguments,
                editorialEnvironment(editorialCredentials),
                StdoutMode.CAPTURE);
    }

    ProcessResult executeEditorial(
            String command,
            ReleaseArtifact release,
            PlanArtifact plan) throws Exception {
        String requiredCommand = requireVisibleToken(command, "command");
        ReleaseArtifact requiredRelease = Objects.requireNonNull(release, "release");
        PlanArtifact requiredPlan = Objects.requireNonNull(plan, "plan");
        requireCommandPlanContract(requiredCommand, true);
        List<String> arguments = new ArrayList<>();
        arguments.add(requiredCommand);
        arguments.addAll(releaseConfirmationArguments(requiredRelease));
        arguments.add("--editorial-plan=" + requiredPlan.path());
        arguments.add("--confirm-operation-id=" + requiredPlan.plan().operationId());
        arguments.add("--confirm-editorial-plan-sha256="
                + requiredPlan.plan().editorialPlanSha256());
        return LegalCliProcessSupport.executeJar(
                artifacts,
                temporaryDirectory,
                List.of(),
                arguments,
                editorialEnvironment(editorialCredentials),
                StdoutMode.CAPTURE);
    }

    /** Builds the exact 1→1 REPLACE mapping from owner-observed database identities. */
    PlanArtifact replacementPlan(
            ReleaseArtifact source,
            ReleaseArtifact target,
            String freshFingerprint) throws Exception {
        ReleaseArtifact requiredSource = Objects.requireNonNull(source, "source");
        ReleaseArtifact requiredTarget = Objects.requireNonNull(target, "target");
        String fingerprint = requireFingerprint(freshFingerprint);
        UUID sourcePublicationId = publicationUuid(requiredSource);
        UUID targetPublicationId = publicationUuid(requiredTarget);
        if (sourcePublicationId.equals(targetPublicationId)) {
            throw new IllegalArgumentException("REPLACE requiere publicaciones distintas");
        }

        List<DocumentVersion> sourceDocuments = documents(sourcePublicationId);
        List<DocumentVersion> targetDocuments = documents(targetPublicationId);
        List<RequirementVersion> sourceRequirements = requirements(sourcePublicationId);
        List<RequirementVersion> targetRequirements = requirements(targetPublicationId);
        Map<UUID, DocumentVersion> sourceDocumentsById = sourceDocuments.stream()
                .collect(Collectors.toUnmodifiableMap(
                        DocumentVersion::id,
                        value -> value));
        Map<UUID, DocumentVersion> sourceDocumentsByLine = sourceDocuments.stream()
                .collect(Collectors.toUnmodifiableMap(
                        DocumentVersion::lineId,
                        value -> value));
        Map<UUID, RequirementVersion> sourceRequirementsById = sourceRequirements.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RequirementVersion::id,
                        value -> value));
        Map<UUID, RequirementVersion> sourceRequirementsByLine = sourceRequirements.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RequirementVersion::lineId,
                        value -> value));

        String sourceExternalId = externalId(requiredSource);
        String targetExternalId = externalId(requiredTarget);
        UUID operationId = stableUuid(
                "replace:terms:" + sourceExternalId + ":" + targetExternalId);
        ObjectNode plan = emptyReplacementPlan(
                operationId,
                requiredSource,
                fingerprint,
                requiredTarget);

        for (DocumentVersion document : targetDocuments) {
            if (sourceDocumentsById.containsKey(document.id())) {
                addDocumentScoped(plan.withArray("documentReuses"), document);
                continue;
            }
            DocumentVersion predecessor = Objects.requireNonNull(
                    sourceDocumentsByLine.get(document.lineId()),
                    "one-to-one document predecessor");
            ObjectNode batch = plan.withArray("documentReplacementBatches")
                    .addObject();
            batch.put("replacementBatchId", stableUuid(
                    "batch:" + predecessor.id() + ":" + document.id()).toString());
            document.contexts().forEach(batch.putArray("contexts")::add);
            addDocumentRef(batch.putArray("predecessors"), predecessor);
            addDocumentRef(batch.putArray("successors"), document);
        }

        for (RequirementVersion requirement : targetRequirements) {
            if (sourceRequirementsById.containsKey(requirement.id())) {
                addRequirementScoped(
                        plan.withArray("requirementReuses"),
                        requirement);
                continue;
            }
            RequirementVersion predecessor = Objects.requireNonNull(
                    sourceRequirementsByLine.get(requirement.lineId()),
                    "one-to-one requirement predecessor");
            ObjectNode replacement = plan.withArray("requirementReplacements")
                    .addObject();
            addRequirementRef(replacement.putObject("predecessor"), predecessor);
            addRequirementRef(replacement.putObject("successor"), requirement);
            replacement.put("context", requirement.context());
            requirement.audiences().forEach(
                    replacement.putArray("audiences")::add);
        }

        requireOneToOneShape(plan);
        return validatePlan(plan, operationId);
    }

    /** Builds the bounded mixed RETIRE mapping from the current owner-observed graph. */
    PlanArtifact retirementPlan(
            ReleaseArtifact current,
            String freshFingerprint) throws Exception {
        ReleaseArtifact requiredCurrent = Objects.requireNonNull(current, "current");
        String fingerprint = requireFingerprint(freshFingerprint);
        UUID publicationId = publicationUuid(requiredCurrent);
        DocumentVersion retiredDocument = requireDocument(
                documents(publicationId),
                RETIRED_DOCUMENT_KEY);
        RequirementVersion retiredRequirement = requireRequirement(
                requirements(publicationId),
                RETIRED_REQUIREMENT_KEY);
        String externalId = externalId(requiredCurrent);
        UUID operationId = stableUuid("retire:mixed:" + externalId);
        ObjectNode plan = emptyRetirementPlan(
                operationId,
                requiredCurrent,
                fingerprint);

        ObjectNode documentRetirement = plan.withArray("documentRetirements")
                .addObject();
        documentRetirement.put(
                "documentVersionId",
                retiredDocument.id().toString());
        documentRetirement.put("sha256", retiredDocument.sha256());
        retiredDocument.contexts().forEach(
                documentRetirement.putArray("contexts")::add);
        documentRetirement.put("reason", DOCUMENT_RETIREMENT_REASON);

        ObjectNode requirementRetirement = plan.withArray("requirementRetirements")
                .addObject();
        requirementRetirement.put(
                "requirementVersionId",
                retiredRequirement.id().toString());
        requirementRetirement.put(
                "statementSha256",
                retiredRequirement.sha256());
        requirementRetirement.put("context", retiredRequirement.context());
        retiredRequirement.audiences().forEach(
                requirementRetirement.putArray("audiences")::add);
        requirementRetirement.put("reason", REQUIREMENT_RETIREMENT_REASON);
        return validatePlan(plan, operationId);
    }

    JdbcTemplate owner() {
        return owner;
    }

    Map<String, String> digestTree(ReleaseArtifact release) throws Exception {
        return sha256Tree(Objects.requireNonNull(release, "release").directory());
    }

    String digestPlan(PlanArtifact plan) throws Exception {
        return sha256File(Objects.requireNonNull(plan, "plan").path());
    }

    OwnerSnapshot snapshotOwner() {
        List<String> tableNames = owner.queryForList("""
                SELECT tablename
                  FROM pg_catalog.pg_tables
                 WHERE schemaname = 'public'
                   AND tablename LIKE 'legal\\_%' ESCAPE '\\'
                 ORDER BY tablename
                """, String.class);
        Map<String, String> rowsByTable = new TreeMap<>();
        for (String tableName : tableNames) {
            String identifier = requireDatabaseIdentifier(tableName);
            rowsByTable.put(identifier, owner.queryForObject("""
                    SELECT COALESCE(
                        jsonb_agg(to_jsonb(snapshot)
                                  ORDER BY to_jsonb(snapshot)::text),
                        '[]'::jsonb
                    )::text
                      FROM (SELECT * FROM public.\"%s\") snapshot
                    """.formatted(identifier), String.class));
        }

        List<String> sequenceNames = owner.queryForList("""
                SELECT sequencename
                  FROM pg_catalog.pg_sequences
                 WHERE schemaname = 'public'
                   AND sequencename LIKE 'legal\\_%' ESCAPE '\\'
                 ORDER BY sequencename
                """, String.class);
        Map<String, SequenceState> sequences = new TreeMap<>();
        for (String sequenceName : sequenceNames) {
            String identifier = requireDatabaseIdentifier(sequenceName);
            sequences.put(identifier, owner.queryForObject(
                    "SELECT last_value, is_called FROM public.\"%s\""
                            .formatted(identifier),
                    (resultSet, rowNumber) -> new SequenceState(
                            resultSet.getLong("last_value"),
                            resultSet.getBoolean("is_called"))));
        }
        return new OwnerSnapshot(rowsByTable, sequences);
    }

    List<String> outputCanaries() {
        return List.of(
                PROCESS_SECRET,
                LEGACY_DATASOURCE_SECRET,
                artifacts.projectDirectory().toAbsolutePath().normalize().toString(),
                "legacy-user",
                "hostile.LegacyDriver",
                NORMAL_START_CLASS,
                "/private/ordenfix-hostile-import.properties",
                "/private/ordenfix-hostile-logback.xml",
                "application-secret.properties",
                "OrdenFix Argentina SAS",
                "30-00000000-0",
                "Calle Pública 100",
                "legal@ordenfix.com",
                "privacidad@ordenfix.com",
                "soporte@ordenfix.com",
                "# Términos");
    }

    private Path releaseDirectory(String publicationId) throws Exception {
        Path fixtureRoot = Files.createDirectories(
                temporaryDirectory.resolve("editorial fixtures with spaces"));
        Path destinationDirectory = fixtureRoot.resolve(publicationId).normalize();
        if (!Objects.equals(destinationDirectory.getParent(), fixtureRoot)) {
            throw new IllegalArgumentException(
                    "publicationId debe resolver a un directorio hijo directo");
        }
        return destinationDirectory;
    }

    private static ReleaseArtifact validateRelease(
            Path directory,
            Path manifestPath) throws Exception {
        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifestPath);
        if (validation.status() != LegalManifestStatus.PASS) {
            throw new AssertionError(
                    "El release temporal de 10E no es válido: " + validation.issues());
        }
        return new ReleaseArtifact(
                directory,
                manifestPath,
                validation.value().orElseThrow(),
                sha256Tree(directory));
    }

    private static void requireCommandPlanContract(
            String command,
            boolean planPresent) {
        LegalEditorialArguments.Command parsed =
                LegalEditorialArguments.Command.fromExternalValue(command);
        if (parsed == null || parsed.requiresEditorialPlan() != planPresent) {
            throw new IllegalArgumentException(
                    "El comando editorial y la presencia del plan no coinciden");
        }
    }

    private UUID publicationUuid(ReleaseArtifact release) {
        List<UUID> ids = owner.query("""
                SELECT id
                  FROM legal_publicaciones
                 WHERE publication_external_id = ?
                """, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                externalId(release));
        if (ids.size() != 1) {
            throw new IllegalStateException(
                    "La publicación del plan debe existir exactamente una vez");
        }
        return ids.getFirst();
    }

    private static String externalId(ReleaseArtifact release) {
        return release.release().plan().manifest().publicationId();
    }

    private List<DocumentVersion> documents(UUID publicationId) {
        List<DocumentVersionBase> rows = owner.query("""
                SELECT dv.id, dv.documento_linea_id, dv.sha256, dv.estado,
                       dl.clave, dl.tipo, dl.locale
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv
                    ON dv.id = pd.documento_version_id
                  JOIN legal_documento_lineas dl
                    ON dl.id = dv.documento_linea_id
                 WHERE pd.publicacion_id = ?
                 ORDER BY pd.manifest_ordinal
                """, (resultSet, rowNumber) -> new DocumentVersionBase(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("documento_linea_id", UUID.class),
                resultSet.getString("sha256"),
                resultSet.getString("estado"),
                resultSet.getString("clave"),
                resultSet.getString("tipo"),
                resultSet.getString("locale")), publicationId);
        return rows.stream().map(row -> new DocumentVersion(
                row.id(),
                row.lineId(),
                row.sha256(),
                row.state(),
                row.key(),
                row.type(),
                row.locale(),
                owner.queryForList("""
                        SELECT contexto
                          FROM legal_documento_contextos
                         WHERE documento_version_id = ?
                         ORDER BY contexto
                        """, String.class, row.id()))).toList();
    }

    private List<RequirementVersion> requirements(UUID publicationId) {
        List<RequirementVersionBase> rows = owner.query("""
                SELECT rv.id, rv.requisito_linea_id, rv.afirmacion_sha256,
                       rv.estado, rl.clave, rl.contexto
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv
                    ON rv.id = pr.requisito_version_id
                  JOIN legal_requisito_lineas rl
                    ON rl.id = rv.requisito_linea_id
                 WHERE pr.publicacion_id = ?
                 ORDER BY pr.manifest_ordinal
                """, (resultSet, rowNumber) -> new RequirementVersionBase(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("requisito_linea_id", UUID.class),
                resultSet.getString("afirmacion_sha256"),
                resultSet.getString("estado"),
                resultSet.getString("clave"),
                resultSet.getString("contexto")), publicationId);
        return rows.stream().map(row -> new RequirementVersion(
                row.id(),
                row.lineId(),
                row.sha256(),
                row.state(),
                row.key(),
                row.context(),
                owner.queryForList("""
                        SELECT audiencia
                          FROM legal_requisito_audiencias
                         WHERE requisito_linea_id = ?
                         ORDER BY audiencia
                        """, String.class, row.lineId()))).toList();
    }

    private PlanArtifact validatePlan(ObjectNode plan, UUID operationId)
            throws Exception {
        Path directory = Files.createDirectories(
                temporaryDirectory.toRealPath()
                        .resolve("editorial plans with spaces")
                        .resolve(operationId.toString()));
        Path path = directory.resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
        Files.write(
                path,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(plan));
        LegalManifestValidation<ValidatedEditorialPlan> validation =
                new LegalEditorialPlanValidator().validate(path);
        if (validation.status() != LegalManifestStatus.PASS) {
            throw new AssertionError(
                    "El plan editorial temporal de 10E no es válido: "
                            + validation.issues());
        }
        return new PlanArtifact(
                path,
                validation.value().orElseThrow(),
                sha256File(path));
    }

    private static ObjectNode emptyReplacementPlan(
            UUID operationId,
            ReleaseArtifact source,
            String fingerprint,
            ReleaseArtifact target) {
        ObjectNode plan = emptyPlan(
                operationId,
                "REPLACE",
                source,
                fingerprint,
                target);
        plan.put("expectedReadinessAfter", "READY");
        plan.put("acknowledgeFailClosedGap", false);
        return plan;
    }

    private static ObjectNode emptyRetirementPlan(
            UUID operationId,
            ReleaseArtifact current,
            String fingerprint) {
        ObjectNode plan = emptyPlan(
                operationId,
                "RETIRE",
                current,
                fingerprint,
                current);
        plan.put("expectedReadinessAfter", "NOT_READY");
        plan.put("acknowledgeFailClosedGap", true);
        return plan;
    }

    private static ObjectNode emptyPlan(
            UUID operationId,
            String operationType,
            ReleaseArtifact source,
            String fingerprint,
            ReleaseArtifact target) {
        ObjectNode plan = JSON.createObjectNode();
        plan.put("schemaVersion", 1);
        plan.put("operationId", operationId.toString());
        plan.put("operationType", operationType);
        plan.put("expectedCurrentPublicationId", externalId(source));
        plan.put(
                "expectedCurrentManifestSha256",
                source.release().plan().manifestSha256());
        plan.put("expectedEditorialStateFingerprint", fingerprint);
        plan.put("targetPublicationId", externalId(target));
        plan.put(
                "targetManifestSha256",
                target.release().plan().manifestSha256());
        plan.putArray("documentAdditions");
        plan.putArray("documentReuses");
        plan.putArray("documentReplacementBatches");
        plan.putArray("documentRetirements");
        plan.putArray("requirementAdditions");
        plan.putArray("requirementReuses");
        plan.putArray("requirementReplacements");
        plan.putArray("requirementRetirements");
        return plan;
    }

    private static void requireOneToOneShape(ObjectNode plan) {
        if (plan.withArray("documentReuses").size() != 10
                || plan.withArray("documentReplacementBatches").size() != 1
                || plan.withArray("requirementReuses").size() != 4
                || plan.withArray("requirementReplacements").size() != 2
                || !plan.withArray("documentAdditions").isEmpty()
                || !plan.withArray("documentRetirements").isEmpty()
                || !plan.withArray("requirementAdditions").isEmpty()
                || !plan.withArray("requirementRetirements").isEmpty()) {
            throw new IllegalStateException(
                    "El sucesor no produjo el grafo 1→1 congelado de 10E");
        }
    }

    private static void addDocumentScoped(
            ArrayNode target,
            DocumentVersion document) {
        ObjectNode item = target.addObject();
        item.put("documentVersionId", document.id().toString());
        item.put("sha256", document.sha256());
        document.contexts().forEach(item.putArray("contexts")::add);
    }

    private static void addDocumentRef(
            ArrayNode target,
            DocumentVersion document) {
        ObjectNode item = target.addObject();
        item.put("documentVersionId", document.id().toString());
        item.put("sha256", document.sha256());
    }

    private static void addRequirementScoped(
            ArrayNode target,
            RequirementVersion requirement) {
        ObjectNode item = target.addObject();
        addRequirementRef(item, requirement);
        item.put("context", requirement.context());
        requirement.audiences().forEach(item.putArray("audiences")::add);
    }

    private static void addRequirementRef(
            ObjectNode target,
            RequirementVersion requirement) {
        target.put("requirementVersionId", requirement.id().toString());
        target.put("statementSha256", requirement.sha256());
    }

    private static DocumentVersion requireDocument(
            List<DocumentVersion> documents,
            String key) {
        return documents.stream()
                .filter(document -> key.equals(document.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Documento de retiro inexistente: " + key));
    }

    private static RequirementVersion requireRequirement(
            List<RequirementVersion> requirements,
            String key) {
        return requirements.stream()
                .filter(requirement -> key.equals(requirement.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Requisito de retiro inexistente: " + key));
    }

    private static ObjectNode document(ObjectNode manifest, String key) {
        for (JsonNode candidate : manifest.withArray("documents")) {
            if (key.equals(candidate.path("key").textValue())) {
                return (ObjectNode) candidate;
            }
        }
        throw new IllegalArgumentException(
                "Documento de fixture inexistente: " + key);
    }

    private static boolean containsText(ArrayNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.textValue())) {
                return true;
            }
        }
        return false;
    }

    private static String requireFingerprint(String value) {
        String required = requireVisibleToken(value, "freshFingerprint");
        if (!required.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "freshFingerprint no tiene el formato editorial esperado");
        }
        return required;
    }

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> releaseConfirmationArguments(ReleaseArtifact release) {
        return List.of(
                "--manifest=" + release.manifestPath(),
                "--confirm-publication-id="
                        + release.release().plan().manifest().publicationId(),
                "--confirm-manifest-sha256="
                        + release.release().plan().manifestSha256());
    }

    private Map<String, String> importEnvironment() {
        Map<String, String> environment = hostileBaseEnvironment();
        environment.put(LegalImportEnvironment.ENABLED_VARIABLE, "true");
        environment.put(
                LegalImportEnvironment.URL_VARIABLE,
                importCredentials.jdbcUrl());
        environment.put(
                LegalImportEnvironment.USERNAME_VARIABLE,
                importCredentials.username());
        environment.put(
                LegalImportEnvironment.PASSWORD_VARIABLE,
                importCredentials.password());
        environment.put(
                LegalImportEnvironment.DRIVER_VARIABLE,
                importCredentials.driverClassName());
        return Map.copyOf(environment);
    }

    private Map<String, String> editorialEnvironment(
            LegalRestrictedEditorialRoleFixture.Credentials credentials) {
        Map<String, String> environment = hostileBaseEnvironment();
        environment.put(LegalEditorialEnvironment.ENABLED_VARIABLE, "true");
        environment.put(
                LegalEditorialEnvironment.URL_VARIABLE,
                credentials.jdbcUrl());
        environment.put(
                LegalEditorialEnvironment.USERNAME_VARIABLE,
                credentials.username());
        environment.put(
                LegalEditorialEnvironment.PASSWORD_VARIABLE,
                credentials.password());
        environment.put(
                LegalEditorialEnvironment.DRIVER_VARIABLE,
                credentials.driverClassName());
        return Map.copyOf(environment);
    }

    private static Map<String, String> hostileBaseEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("ORDENFIX_CLI_PROCESS_SECRET", PROCESS_SECRET);
        environment.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:1/legacy");
        environment.put("SPRING_DATASOURCE_USERNAME", "legacy-user");
        environment.put("SPRING_DATASOURCE_PASSWORD", LEGACY_DATASOURCE_SECRET);
        environment.put("SPRING_DATASOURCE_DRIVER_CLASS_NAME", "hostile.LegacyDriver");
        environment.put("SPRING_APPLICATION_JSON",
                "{\"spring\":{\"datasource\":{\"password\":\""
                        + LEGACY_DATASOURCE_SECRET + "\"}}}");
        environment.put("SPRING_CONFIG_ADDITIONAL_LOCATION", "classpath:/");
        environment.put("SPRING_CONFIG_IMPORT",
                "file:/private/ordenfix-hostile-import.properties");
        environment.put("SPRING_CONFIG_LOCATION", "classpath:/");
        environment.put("SPRING_FLYWAY_ENABLED", "true");
        environment.put("SPRING_MAIN_BANNER_MODE", "console");
        environment.put("SPRING_MAIN_KEEP_ALIVE", "true");
        environment.put("SPRING_MAIN_LOG_STARTUP_INFO", "true");
        environment.put("SPRING_MAIN_SOURCES", NORMAL_START_CLASS);
        environment.put("SPRING_MAIN_WEB_APPLICATION_TYPE", "servlet");
        environment.put("LOGGING_CONFIG", "file:/private/ordenfix-hostile-logback.xml");
        environment.put("LOGGING_LEVEL_ROOT", "TRACE");
        return environment;
    }

    private static void copyDirectory(Path sourceDirectory, Path destinationDirectory)
            throws Exception {
        try (Stream<Path> sources = Files.walk(sourceDirectory)) {
            for (Path source : sources.toList()) {
                Path destination = destinationDirectory.resolve(
                        sourceDirectory.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        }
    }

    private static Map<String, String> sha256Tree(Path directory) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                hashes.put(
                        directory.relativize(path).toString(),
                        sha256File(path));
            }
        }
        return Map.copyOf(hashes);
    }

    private static String sha256File(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String requireVisibleToken(String value, String name) {
        String required = Objects.requireNonNull(value, name);
        if (required.isBlank()
                || required.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " debe ser texto visible");
        }
        return required;
    }

    private static String requireDatabaseIdentifier(String value) {
        String required = Objects.requireNonNull(value, "databaseIdentifier");
        if (!DATABASE_IDENTIFIER.matcher(required).matches()) {
            throw new IllegalStateException(
                    "Identificador inesperado en el catálogo PostgreSQL");
        }
        return required;
    }

    record ReleaseArtifact(
            Path directory,
            Path manifestPath,
            ValidatedRelease release,
            Map<String, String> sha256ByRelativePath) {

        ReleaseArtifact {
            directory = Objects.requireNonNull(directory, "directory")
                    .toAbsolutePath().normalize();
            manifestPath = Objects.requireNonNull(manifestPath, "manifestPath")
                    .toAbsolutePath().normalize();
            Objects.requireNonNull(release, "release");
            sha256ByRelativePath = Map.copyOf(Objects.requireNonNull(
                    sha256ByRelativePath,
                    "sha256ByRelativePath"));
            if (!manifestPath.startsWith(directory)
                    || !Files.isRegularFile(manifestPath)
                    || sha256ByRelativePath.isEmpty()) {
                throw new IllegalArgumentException("El release temporal de 10E está incompleto");
            }
        }
    }

    record PlanArtifact(
            Path path,
            ValidatedEditorialPlan plan,
            String fileSha256) {

        PlanArtifact {
            path = Objects.requireNonNull(path, "path")
                    .toAbsolutePath().normalize();
            Objects.requireNonNull(plan, "plan");
            fileSha256 = Objects.requireNonNull(fileSha256, "fileSha256");
            if (!Files.isRegularFile(path)
                    || !ConfinedEditorialPlanReader.PLAN_FILENAME.equals(
                    path.getFileName().toString())
                    || !fileSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "El plan temporal de 10E está incompleto");
            }
        }
    }

    record OwnerSnapshot(
            Map<String, String> rowsByTable,
            Map<String, SequenceState> sequences) {

        OwnerSnapshot {
            rowsByTable = Map.copyOf(Objects.requireNonNull(
                    rowsByTable,
                    "rowsByTable"));
            sequences = Map.copyOf(Objects.requireNonNull(
                    sequences,
                    "sequences"));
            if (rowsByTable.isEmpty() || sequences.isEmpty()) {
                throw new IllegalArgumentException(
                        "El snapshot owner de 10E no puede estar vacío");
            }
        }
    }

    record SequenceState(long lastValue, boolean called) {
    }

    private record DocumentVersionBase(
            UUID id,
            UUID lineId,
            String sha256,
            String state,
            String key,
            String type,
            String locale) {
    }

    private record DocumentVersion(
            UUID id,
            UUID lineId,
            String sha256,
            String state,
            String key,
            String type,
            String locale,
            List<String> contexts) {
    }

    private record RequirementVersionBase(
            UUID id,
            UUID lineId,
            String sha256,
            String state,
            String key,
            String context) {
    }

    private record RequirementVersion(
            UUID id,
            UUID lineId,
            String sha256,
            String state,
            String key,
            String context,
            List<String> audiences) {
    }
}
