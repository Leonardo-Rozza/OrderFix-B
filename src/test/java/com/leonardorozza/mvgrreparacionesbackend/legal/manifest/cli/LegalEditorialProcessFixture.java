package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.Artifacts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.ProcessResult;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalCliProcessSupport.StdoutMode;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Test-only files, process arguments and owner observations for the real editorial JAR. */
final class LegalEditorialProcessFixture {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String EFFECTIVE_AT = "2026-01-01T00:00:00-03:00";
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
        Path fixtureRoot = Files.createDirectories(
                temporaryDirectory.resolve("editorial fixtures with spaces"));
        Path destinationDirectory = fixtureRoot.resolve(requiredPublicationId).normalize();
        if (!Objects.equals(destinationDirectory.getParent(), fixtureRoot)) {
            throw new IllegalArgumentException(
                    "publicationId debe resolver a un directorio hijo directo");
        }
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

        LegalManifestValidation<ValidatedRelease> validation =
                new LegalManifestValidator().validate(manifestPath);
        if (validation.status() != LegalManifestStatus.PASS) {
            throw new AssertionError(
                    "El release temporal de 10E no es válido: " + validation.issues());
        }
        return new ReleaseArtifact(
                destinationDirectory,
                manifestPath,
                validation.value().orElseThrow(),
                sha256Tree(destinationDirectory));
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

    JdbcTemplate owner() {
        return owner;
    }

    Map<String, String> digestTree(ReleaseArtifact release) throws Exception {
        return sha256Tree(Objects.requireNonNull(release, "release").directory());
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
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
                hashes.put(
                        directory.relativize(path).toString(),
                        HexFormat.of().formatHex(digest.digest()));
            }
        }
        return Map.copyOf(hashes);
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
}
