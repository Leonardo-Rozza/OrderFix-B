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
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection.ScopeRevision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance.ScopeOrigin;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRestrictedEditorialRoleFixture;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRestrictedImportRoleFixture;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private static final String CONTINUED_USE_STATEMENT =
            "Confirmo el requisito continued-use de OrdenFix.";
    private static final String CONTINUED_USE_STATEMENT_SHA256 =
            "691f6e8465d629ca3e5f184539add016db43ee5be37011b4f5a1470b33ac780d";
    private static final String EDITORIAL_LOCK_NAME =
            "ordenfix:legal-publicaciones:sello:v1";
    private static final String NORMAL_START_CLASS =
            "com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication";
    private static final String PROCESS_SECRET =
            "editorial-process-secret-must-never-leak";
    private static final String LEGACY_DATASOURCE_SECRET =
            "legacy-datasource-secret-must-never-leak";
    private static final String LAUNCHER_URL_CANARY =
            "launcher-editorial-url-canary-must-never-leak";
    private static final String LAUNCHER_USERNAME_CANARY =
            "launcher-editorial-username-canary-must-never-leak";
    private static final String LAUNCHER_PASSWORD_CANARY =
            "launcher-editorial-password-canary-must-never-leak";
    private static final String LAUNCHER_DRIVER_CANARY =
            "launcher-editorial-driver-canary-must-never-leak";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DATABASE_IDENTIFIER =
            Pattern.compile("[a-z][a-z0-9_]*");

    private final Path temporaryDirectory;
    private final Class<?> resourceAnchor;
    private final Artifacts artifacts;
    private final JdbcTemplate owner;
    private final LegalRestrictedImportRoleFixture.Credentials importCredentials;
    private final EditorialConnection editorialConnection;

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
        LegalRestrictedEditorialRoleFixture.Credentials requiredEditorialCredentials =
                Objects.requireNonNull(editorialCredentials, "editorialCredentials");
        this.editorialConnection = new EditorialConnection(
                requiredEditorialCredentials.jdbcUrl(),
                requiredEditorialCredentials.username(),
                requiredEditorialCredentials.password(),
                requiredEditorialCredentials.driverClassName());
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
        // The protected acceptance is AUTHENTICATED_PENDING, so the release must
        // expose a real USO_CONTINUADO requirement before it is imported and sealed.
        appendContinuedUseRequirement(manifest);
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
        return executeEditorial(command, release, editorialConnection, List.of());
    }

    ProcessResult executeEditorial(
            String command,
            ReleaseArtifact release,
            List<String> jvmArguments) throws Exception {
        return executeEditorial(
                command,
                release,
                jvmArguments,
                StdoutMode.CAPTURE);
    }

    ProcessResult executeEditorial(
            String command,
            ReleaseArtifact release,
            List<String> jvmArguments,
            StdoutMode stdoutMode) throws Exception {
        return executeEditorial(
                command,
                release,
                editorialConnection,
                jvmArguments,
                stdoutMode);
    }

    ProcessResult executeEditorial(
            String command,
            ReleaseArtifact release,
            EditorialConnection connection,
            List<String> jvmArguments) throws Exception {
        return executeEditorial(
                command,
                release,
                connection,
                jvmArguments,
                StdoutMode.CAPTURE);
    }

    ProcessResult executeEditorial(
            String command,
            ReleaseArtifact release,
            EditorialConnection connection,
            List<String> jvmArguments,
            StdoutMode stdoutMode) throws Exception {
        return executeEditorialJar(
                command,
                release,
                null,
                connection,
                jvmArguments,
                stdoutMode);
    }

    ProcessResult executeEditorial(
            String command,
            ReleaseArtifact release,
            PlanArtifact plan) throws Exception {
        return executeEditorial(
                command,
                release,
                plan,
                editorialConnection,
                List.of());
    }

    ProcessResult executeEditorial(
            String command,
            ReleaseArtifact release,
            PlanArtifact plan,
            List<String> jvmArguments) throws Exception {
        return executeEditorial(
                command,
                release,
                plan,
                editorialConnection,
                jvmArguments);
    }

    ProcessResult executeEditorial(
            String command,
            ReleaseArtifact release,
            PlanArtifact plan,
            EditorialConnection connection,
            List<String> jvmArguments) throws Exception {
        return executeEditorialJar(
                command,
                release,
                Objects.requireNonNull(plan, "plan"),
                connection,
                jvmArguments,
                StdoutMode.CAPTURE);
    }

    ProcessResult executeEditorialLauncher(
            String command,
            ReleaseArtifact release) throws Exception {
        return executeEditorialLauncher(command, release, null);
    }

    ProcessResult executeEditorialLauncher(
            String command,
            ReleaseArtifact release,
            PlanArtifact plan) throws Exception {
        String requiredCommand = requireVisibleToken(command, "command");
        ReleaseArtifact requiredRelease = Objects.requireNonNull(release, "release");
        List<String> commandLine = new ArrayList<>();
        Path launcher = artifacts.projectDirectory()
                .resolve("scripts/legal-manifest-editor.sh")
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(launcher) || !Files.isExecutable(launcher)) {
            throw new AssertionError(
                    "El launcher editorial real no está disponible o no es ejecutable");
        }
        commandLine.add(launcher.toString());
        commandLine.addAll(editorialArguments(
                requiredCommand,
                requiredRelease,
                plan));

        Map<String, String> environment = new LinkedHashMap<>(
                editorialEnvironment(editorialConnection));
        environment.put("ORDENFIX_LEGAL_CLI_JAR", artifacts.legalCliJar().toString());
        environment.put("ORDENFIX_JAVA_BIN", artifacts.javaExecutable().toString());
        environment.put(
                "JAVA_TOOL_OPTIONS",
                "-Dspring.datasource.url=" + LAUNCHER_URL_CANARY);
        environment.put(
                "JDK_JAVA_OPTIONS",
                "-Dspring.datasource.username=" + LAUNCHER_USERNAME_CANARY
                        + " -Dspring.datasource.driver-class-name="
                        + LAUNCHER_DRIVER_CANARY);
        environment.put(
                "_JAVA_OPTIONS",
                "-Dspring.datasource.password=" + LAUNCHER_PASSWORD_CANARY);
        return LegalCliProcessSupport.execute(
                List.copyOf(commandLine),
                temporaryDirectory,
                Map.copyOf(environment),
                StdoutMode.CAPTURE);
    }

    private ProcessResult executeEditorialJar(
            String command,
            ReleaseArtifact release,
            PlanArtifact plan,
            EditorialConnection connection,
            List<String> jvmArguments,
            StdoutMode stdoutMode) throws Exception {
        String requiredCommand = requireVisibleToken(command, "command");
        ReleaseArtifact requiredRelease = Objects.requireNonNull(release, "release");
        EditorialConnection requiredConnection = Objects.requireNonNull(
                connection,
                "connection");
        List<String> requiredJvmArguments = List.copyOf(Objects.requireNonNull(
                jvmArguments,
                "jvmArguments"));
        StdoutMode requiredStdoutMode = Objects.requireNonNull(
                stdoutMode,
                "stdoutMode");
        return LegalCliProcessSupport.executeJar(
                artifacts,
                temporaryDirectory,
                requiredJvmArguments,
                editorialArguments(requiredCommand, requiredRelease, plan),
                editorialEnvironment(requiredConnection),
                requiredStdoutMode);
    }

    private List<String> editorialArguments(
            String command,
            ReleaseArtifact release,
            PlanArtifact plan) {
        requireCommandPlanContract(command, plan != null);
        List<String> arguments = new ArrayList<>();
        arguments.add(command);
        arguments.addAll(releaseConfirmationArguments(release));
        if (plan != null) {
            arguments.add("--editorial-plan=" + plan.path());
            arguments.add("--confirm-operation-id=" + plan.plan().operationId());
            arguments.add("--confirm-editorial-plan-sha256="
                    + plan.plan().editorialPlanSha256());
        }
        return List.copyOf(arguments);
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

    /** Seeds one complete HTTP-owned row per protected table in one owner transaction. */
    void seedProtectedHttpState(ReleaseArtifact current) {
        ReleaseArtifact requiredCurrent = Objects.requireNonNull(current, "current");
        String publicationExternalId = externalId(requiredCurrent);
        UUID seed = UUID.randomUUID();
        String seedHex = seed.toString().replace("-", "");
        UUID lotId = stableUuid("protected-http-lot:" + seed);
        UUID acceptanceId = stableUuid("protected-http-acceptance:" + seed);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(
                        owner.getDataSource(),
                        "owner.dataSource")));

        transaction.executeWithoutResult(status -> {
            owner.queryForList("""
                    SELECT pg_catalog.pg_advisory_xact_lock_shared(
                        pg_catalog.hashtextextended(?, 0)
                    )
                    """, EDITORIAL_LOCK_NAME);
            Long workshopId = Objects.requireNonNull(owner.queryForObject("""
                    INSERT INTO talleres (nombre)
                    VALUES (?)
                    RETURNING id
                    """, Long.class, "Taller HTTP protegido 10E " + seedHex));
            Long userId = Objects.requireNonNull(owner.queryForObject("""
                    INSERT INTO users (username, password, email, role, taller_id)
                    VALUES (?, 'hash-process-10e', ?, 'ADMIN', ?)
                    RETURNING id
                    """, Long.class,
                    "process10e-" + seedHex,
                    "process10e-" + seedHex + "@ordenfix.test",
                    workshopId));
            UUID publicationId = Objects.requireNonNull(owner.queryForObject("""
                    SELECT id
                      FROM legal_publicaciones
                     WHERE publication_external_id = ?
                    """, UUID.class, publicationExternalId));
            UUID requirementId = Objects.requireNonNull(owner.queryForObject("""
                    SELECT rv.id
                      FROM legal_publicacion_requisitos pr
                      JOIN legal_requisito_versiones rv
                        ON rv.id = pr.requisito_version_id
                      JOIN legal_requisito_lineas rl
                        ON rl.id = rv.requisito_linea_id
                     WHERE pr.publicacion_id = ?
                       AND rl.clave = 'account-closure'
                    """, UUID.class, publicationId));
            PersistedAcceptanceAggregate aggregate = materializeAcceptanceAggregate(
                    publicationId,
                    stableUuid("protected-http-aggregate:" + seed),
                    List.of(
                            ContextoLegal.USO_CONTINUADO,
                            ContextoLegal.CIERRE_CUENTA));

            requireSingleInsert(owner.update("""
                    INSERT INTO legal_aceptacion_lotes
                        (id, user_id, taller_id, rol_wire, audiencia,
                         required_set_revision, aceptado_en, revision_scheme,
                         perfil, agregado_id)
                    VALUES (?, ?, ?, 'ADMIN', 'ADMIN_TITULAR', ?,
                            transaction_timestamp(), 'AGGREGATE_V1',
                            'AUTHENTICATED_PENDING', ?)
                    """,
                    lotId,
                    userId,
                    workshopId,
                    aggregate.requiredSetRevision(),
                    aggregate.id()),
                    "legal_aceptacion_lotes");
            requireSingleInsert(owner.update("""
                    INSERT INTO legal_aceptaciones
                        (id, lote_id, user_id, taller_id, requisito_version_id,
                         requisito_clave, requisito_version, contexto, tipo_acto,
                         afirmacion, afirmacion_sha256, requerido)
                    SELECT ?, ?, ?, ?, rv.id, rl.clave, rv.version, rl.contexto,
                           rl.tipo_acto, rv.afirmacion, rv.afirmacion_sha256,
                           rv.requerido
                      FROM legal_requisito_versiones rv
                      JOIN legal_requisito_lineas rl
                        ON rl.id = rv.requisito_linea_id
                     WHERE rv.id = ?
                    """, acceptanceId, lotId, userId, workshopId, requirementId),
                    "legal_aceptaciones");
            requireSingleInsert(owner.update("""
                    INSERT INTO legal_aceptacion_documentos
                        (aceptacion_id, documento_ordinal, documento_version_id,
                         documento_clave, tipo, version, titulo, sha256)
                    SELECT ?, rd.documento_ordinal, dv.id, dl.clave, dl.tipo,
                           dv.version, dv.titulo, dv.sha256
                      FROM legal_requisito_documentos rd
                      JOIN legal_documento_versiones dv
                        ON dv.id = rd.documento_version_id
                      JOIN legal_documento_lineas dl
                        ON dl.id = dv.documento_linea_id
                     WHERE rd.requisito_version_id = ?
                     ORDER BY rd.documento_ordinal
                    """, acceptanceId, requirementId),
                    "legal_aceptacion_documentos");
            requireSingleInsert(owner.update("""
                    INSERT INTO legal_aceptacion_metadatos
                        (lote_id, capturado_en, retener_hasta)
                    VALUES (?, transaction_timestamp(),
                            transaction_timestamp() + INTERVAL '30 days')
                    """, lotId), "legal_aceptacion_metadatos");
            requireSingleInsert(owner.update("""
                    INSERT INTO legal_aceptacion_metadatos_cifrados
                        (lote_id, tipo, key_version, nonce, ciphertext, tag,
                         longitud_original)
                    VALUES (?, 'IP', 1, ?, ?, ?, 9)
                    """,
                    lotId,
                    HexFormat.of().parseHex(seedHex.substring(0, 24)),
                    bytes(9, 31),
                    bytes(16, 51)),
                    "legal_aceptacion_metadatos_cifrados");
            requireSingleInsert(owner.update("""
                    INSERT INTO legal_idempotencia_resultados
                        (operacion, route_template, scope_hmac,
                         idempotency_key_hmac, fingerprint_hmac,
                         hmac_key_version, user_id, taller_id, lote_id,
                         completed_at, expires_at)
                    VALUES ('ACEPTACION_LEGAL', '/api/legal/process-10e', ?, ?, ?,
                            1, ?, ?, ?, transaction_timestamp(),
                            transaction_timestamp() + INTERVAL '30 days')
                    """,
                    "a".repeat(64),
                    seedHex.repeat(2),
                    "c".repeat(64),
                    userId,
                    workshopId,
                    lotId),
                    "legal_idempotencia_resultados");
            owner.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
    }

    private PersistedAcceptanceAggregate materializeAcceptanceAggregate(
            UUID publicationId,
            UUID aggregateId,
            List<ContextoLegal> expectedContexts) {
        List<AggregateScope> scopes = owner.query("""
                SELECT current_set.conjunto_id, current_set.publicacion_id,
                       current_set.contexto, required_set.required_set_revision
                  FROM legal_requisito_conjuntos_actuales current_set
                  JOIN legal_requisito_conjuntos required_set
                    ON required_set.id = current_set.conjunto_id
                   AND required_set.publicacion_id = current_set.publicacion_id
                   AND required_set.locale = current_set.locale
                   AND required_set.contexto = current_set.contexto
                   AND required_set.audiencia = current_set.audiencia
                 WHERE current_set.publicacion_id = ?
                   AND current_set.locale = 'es-AR'
                   AND current_set.audiencia = 'ADMIN_TITULAR'
                   AND current_set.contexto IN ('USO_CONTINUADO', 'CIERRE_CUENTA')
                 ORDER BY CASE current_set.contexto
                     WHEN 'USO_CONTINUADO' THEN 1
                     WHEN 'CIERRE_CUENTA' THEN 2
                 END
                   FOR SHARE OF current_set
                """, (resultSet, rowNumber) -> new AggregateScope(
                ContextoLegal.valueOf(resultSet.getString("contexto")),
                resultSet.getObject("conjunto_id", UUID.class),
                resultSet.getObject("publicacion_id", UUID.class),
                resultSet.getString("required_set_revision")), publicationId);
        List<ContextoLegal> observedContexts = scopes.stream()
                .map(AggregateScope::context)
                .toList();
        if (!observedContexts.equals(expectedContexts)) {
            throw new AssertionError(
                    "El aggregate HTTP no resolvió los scopes esperados: "
                            + observedContexts);
        }

        LegalRequiredSetAggregateProjection projection =
                new LegalRequiredSetAggregateProjection(
                        EsquemaRevisionLegal.AGGREGATE_V1,
                        LocaleLegal.ES_AR,
                        AudienciaLegal.ADMIN_TITULAR,
                        scopes.stream()
                                .map(scope -> new ScopeRevision(
                                        scope.context(),
                                        scope.requiredSetRevision()))
                                .toList());
        LegalRequiredSetAggregateProvenance provenance =
                new LegalRequiredSetAggregateProvenance(
                        PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                        LocaleLegal.ES_AR,
                        AudienciaLegal.ADMIN_TITULAR,
                        scopes.stream()
                                .map(scope -> new ScopeOrigin(
                                        scope.context(),
                                        scope.requiredSetId(),
                                        scope.publicationId()))
                                .toList());
        String requiredSetRevision =
                new LegalRequiredSetAggregateRevisionCalculator().calculate(projection);
        String provenanceFingerprint =
                new LegalRequiredSetAggregateProvenanceCalculator().calculate(provenance);

        requireSingleInsert(owner.update("""
                INSERT INTO legal_requisito_agregados
                    (id, perfil, locale, audiencia, revision_scheme,
                     required_set_revision, provenance_fingerprint,
                     scope_count, creado_en)
                VALUES (?, 'AUTHENTICATED_PENDING', 'es-AR', 'ADMIN_TITULAR',
                        'AGGREGATE_V1', ?, ?, ?, transaction_timestamp())
                """,
                aggregateId,
                requiredSetRevision,
                provenanceFingerprint,
                scopes.size()),
                "legal_requisito_agregados");
        for (int index = 0; index < scopes.size(); index++) {
            AggregateScope scope = scopes.get(index);
            requireSingleInsert(owner.update("""
                    INSERT INTO legal_requisito_agregado_scopes
                        (agregado_id, scope_ordinal, contexto, conjunto_id,
                         publicacion_id, locale, audiencia, required_set_revision)
                    VALUES (?, ?, ?, ?, ?, 'es-AR', 'ADMIN_TITULAR', ?)
                    """,
                    aggregateId,
                    index + 1,
                    scope.context().name(),
                    scope.requiredSetId(),
                    scope.publicationId(),
                    scope.requiredSetRevision()),
                    "legal_requisito_agregado_scopes");
        }
        return new PersistedAcceptanceAggregate(aggregateId, requiredSetRevision);
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
                LAUNCHER_URL_CANARY,
                LAUNCHER_USERNAME_CANARY,
                LAUNCHER_PASSWORD_CANARY,
                LAUNCHER_DRIVER_CANARY,
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
                || plan.withArray("requirementReuses").size() != 5
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

    private static void appendContinuedUseRequirement(ObjectNode manifest) {
        for (JsonNode candidate : manifest.withArray("requirements")) {
            if ("continued-use".equals(candidate.path("key").textValue())) {
                throw new IllegalStateException(
                        "El fixture de procesos ya contiene USO_CONTINUADO");
            }
        }
        ObjectNode requirement = manifest.withArray("requirements").addObject();
        requirement.put("key", "continued-use");
        requirement.put("version", "1.0.0");
        requirement.put("context", "USO_CONTINUADO");
        requirement.putArray("roles").add("ADMIN_TITULAR");
        requirement.put("actType", "ACEPTACION");
        requirement.put("statement", CONTINUED_USE_STATEMENT);
        requirement.put("statementSha256", CONTINUED_USE_STATEMENT_SHA256);
        requirement.putArray("documents").add("privacidad");
        requirement.put("required", true);
        requirement.put("requiresReacceptance", true);
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
            EditorialConnection credentials) {
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

    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int index = 0; index < length; index++) {
            value[index] = (byte) (seed + index);
        }
        return value;
    }

    private static void requireSingleInsert(int affectedRows, String table) {
        if (affectedRows != 1) {
            throw new AssertionError(
                    "El seed HTTP de 10E no insertó una fila exacta en " + table);
        }
    }

    private static String requireDatabaseIdentifier(String value) {
        String required = Objects.requireNonNull(value, "databaseIdentifier");
        if (!DATABASE_IDENTIFIER.matcher(required).matches()) {
            throw new IllegalStateException(
                    "Identificador inesperado en el catálogo PostgreSQL");
        }
        return required;
    }

    /** Arbitrary process credentials with a deliberately redacted diagnostic form. */
    record EditorialConnection(
            String jdbcUrl,
            String username,
            String password,
            String driverClassName) {

        EditorialConnection {
            jdbcUrl = requireVisibleToken(jdbcUrl, "jdbcUrl");
            username = requireVisibleToken(username, "username");
            password = requireVisibleToken(password, "password");
            driverClassName = requireVisibleToken(
                    driverClassName,
                    "driverClassName");
        }

        @Override
        public String toString() {
            return "EditorialConnection[configured=true]";
        }
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

    private record AggregateScope(
            ContextoLegal context,
            UUID requiredSetId,
            UUID publicationId,
            String requiredSetRevision) {
    }

    private record PersistedAcceptanceAggregate(
            UUID id,
            String requiredSetRevision) {
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
