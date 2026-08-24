package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedReleaseReader.DocumentSource;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedReleaseReader.ManifestSource;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedReleaseReader.ReleaseDocuments;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfinedReleaseReaderTest {

    private static final String RELEASE_ID = "release-valid-v1";
    private static final OffsetDateTime EFFECTIVE_AT =
            OffsetDateTime.parse("2026-08-24T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private final ConfinedReleaseReader reader = new ConfinedReleaseReader();

    @Test
    void readsExactManifestNameWithBoundedDefensiveBytes() throws IOException {
        Path release = createRelease(RELEASE_ID);
        byte[] expected = "{\"schemaVersion\":1}\n".getBytes(StandardCharsets.UTF_8);
        Path manifestPath = writeManifest(release, expected);

        LegalManifestValidation<ManifestSource> result = reader.readManifest(manifestPath);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        ManifestSource source = result.value().orElseThrow();
        assertThat(source.releaseDirectoryName()).isEqualTo(RELEASE_ID);
        assertThat(source.bytes()).containsExactly(expected);

        byte[] exposed = source.bytes();
        exposed[0] = 'X';
        assertThat(source.bytes()).containsExactly(expected);
    }

    @Test
    void rejectsMissingPathWrongFilenameAndInvalidRootDeterministically() throws IOException {
        assertBlocked(
                reader.readManifest(null),
                LegalManifestIssueCode.MANIFEST_PATH_REQUIRED,
                ConfinedReleaseReader.MANIFEST_FILENAME);
        assertBlocked(
                reader.readManifest(temporaryDirectory.resolve("manifest.json")),
                LegalManifestIssueCode.MANIFEST_FILENAME_INVALID,
                ConfinedReleaseReader.MANIFEST_FILENAME);
        assertBlocked(
                reader.readManifest(temporaryDirectory
                        .resolve("absent")
                        .resolve(ConfinedReleaseReader.MANIFEST_FILENAME)),
                LegalManifestIssueCode.RELEASE_ROOT_INVALID,
                ConfinedReleaseReader.MANIFEST_FILENAME);

        Path release = createRelease(RELEASE_ID);
        assertBlocked(
                reader.readManifest(release.resolve(ConfinedReleaseReader.MANIFEST_FILENAME)),
                LegalManifestIssueCode.MANIFEST_NOT_REGULAR,
                ConfinedReleaseReader.MANIFEST_FILENAME);

        Files.createDirectory(release.resolve(ConfinedReleaseReader.MANIFEST_FILENAME));
        assertBlocked(
                reader.readManifest(release.resolve(ConfinedReleaseReader.MANIFEST_FILENAME)),
                LegalManifestIssueCode.MANIFEST_NOT_REGULAR,
                ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    @Test
    void rejectsRootAndManifestSymlinksButAllowsAnExternalSymlinkAncestor() throws IOException {
        Path realParent = Files.createDirectory(temporaryDirectory.resolve("real-parent"));
        Path release = Files.createDirectory(realParent.resolve(RELEASE_ID));
        writeManifest(release, "{}".getBytes(StandardCharsets.UTF_8));

        Path externalAncestor = temporaryDirectory.resolve("external-ancestor");
        Files.createSymbolicLink(externalAncestor, realParent);
        assertThat(reader.readManifest(externalAncestor
                .resolve(RELEASE_ID)
                .resolve(ConfinedReleaseReader.MANIFEST_FILENAME)).passed()).isTrue();
        assertThat(reader.readManifest(externalAncestor
                .resolve(".")
                .resolve(RELEASE_ID)
                .resolve(".")
                .resolve(ConfinedReleaseReader.MANIFEST_FILENAME)).passed()).isTrue();

        Path rootLink = temporaryDirectory.resolve("linked-release");
        Files.createSymbolicLink(rootLink, release);
        assertBlocked(
                reader.readManifest(rootLink.resolve(ConfinedReleaseReader.MANIFEST_FILENAME)),
                LegalManifestIssueCode.RELEASE_SYMLINK_FORBIDDEN,
                ConfinedReleaseReader.MANIFEST_FILENAME);
        assertBlocked(
                reader.readManifest(rootLink
                        .resolve(".")
                        .resolve(ConfinedReleaseReader.MANIFEST_FILENAME)),
                LegalManifestIssueCode.RELEASE_SYMLINK_FORBIDDEN,
                ConfinedReleaseReader.MANIFEST_FILENAME);
        assertBlocked(
                reader.readManifest(temporaryDirectory
                        .resolve(".")
                        .resolve(rootLink.getFileName())
                        .resolve(".")
                        .resolve(".")
                        .resolve(ConfinedReleaseReader.MANIFEST_FILENAME)),
                LegalManifestIssueCode.RELEASE_SYMLINK_FORBIDDEN,
                ConfinedReleaseReader.MANIFEST_FILENAME);

        assertThat(reader.readManifest(release
                .resolve(".")
                .resolve(ConfinedReleaseReader.MANIFEST_FILENAME)).passed()).isTrue();
        Path redundantChild = Files.createDirectory(release.resolve("redundant-child"));
        assertBlocked(
                reader.readManifest(redundantChild
                        .resolve("..")
                        .resolve(ConfinedReleaseReader.MANIFEST_FILENAME)),
                LegalManifestIssueCode.RELEASE_ROOT_INVALID,
                ConfinedReleaseReader.MANIFEST_FILENAME);

        Path symlinkRelease = createRelease("manifest-link-release");
        Path realManifest = symlinkRelease.resolve("real-manifest.json");
        Files.writeString(realManifest, "{}", StandardCharsets.UTF_8);
        Files.createSymbolicLink(
                symlinkRelease.resolve(ConfinedReleaseReader.MANIFEST_FILENAME),
                realManifest.getFileName());
        assertBlocked(
                reader.readManifest(
                        symlinkRelease.resolve(ConfinedReleaseReader.MANIFEST_FILENAME)),
                LegalManifestIssueCode.MANIFEST_NOT_REGULAR,
                ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    @Test
    void blocksManifestOverTheOneMibLimitWithoutReadingIt() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Path manifest = release.resolve(ConfinedReleaseReader.MANIFEST_FILENAME);
        writeSparseFile(manifest, LegalManifestLimits.MAX_MANIFEST_BYTES + 1L);

        assertBlocked(
                reader.readManifest(manifest),
                LegalManifestIssueCode.MANIFEST_SIZE_LIMIT_EXCEEDED,
                ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    @Test
    void acceptsTheExactOneMibBoundaryForManifestAndDocument() throws IOException {
        Path release = createRelease(RELEASE_ID);
        byte[] exactDocument = new byte[LegalManifestLimits.MAX_MARKDOWN_BYTES];
        Files.write(release.resolve("exact.md"), exactDocument);
        byte[] exactManifest = new byte[LegalManifestLimits.MAX_MANIFEST_BYTES];
        Path manifest = writeManifest(release, exactManifest);

        LegalManifestValidation<ManifestSource> manifestResult = reader.readManifest(manifest);
        assertThat(manifestResult.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(manifestResult.value().orElseThrow().bytes())
                .hasSize(LegalManifestLimits.MAX_MANIFEST_BYTES);

        LegalManifestValidation<ReleaseDocuments> documentsResult = reader.readDocuments(
                manifestResult.value().orElseThrow(),
                manifest(RELEASE_ID, List.of(document("exact", "exact.md"))));
        assertThat(documentsResult.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(documentsResult.value().orElseThrow().documents().getFirst().bytes())
                .hasSize(LegalManifestLimits.MAX_MARKDOWN_BYTES);
    }

    @Test
    void detectsManifestReplacementAfterTheBoundedRead() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Path manifest = writeManifest(release, "old".getBytes(StandardCharsets.UTF_8));
        ConfinedReleaseReader swappingReader = new ConfinedReleaseReader((path, kind) -> {
            if (kind == ConfinedReleaseReader.FileKind.MANIFEST) {
                replaceAtomically(path, "new".getBytes(StandardCharsets.UTF_8));
            }
        });

        assertBlocked(
                swappingReader.readManifest(manifest),
                LegalManifestIssueCode.MANIFEST_FILE_CHANGED,
                ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    @Test
    void revalidatesReleaseRootImmediatelyAfterReadingTheManifest() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Path manifest = writeManifest(release, "{}".getBytes(StandardCharsets.UTF_8));
        ConfinedReleaseReader swappingReader = new ConfinedReleaseReader((path, kind) -> {
            if (kind == ConfinedReleaseReader.FileKind.MANIFEST) {
                Path displacedRelease = temporaryDirectory.resolve("displaced-release");
                Files.move(release, displacedRelease);
                Path replacementRelease = Files.createDirectory(release);
                Files.writeString(
                        replacementRelease.resolve(ConfinedReleaseReader.MANIFEST_FILENAME),
                        "{}");
            }
        });

        assertBlocked(
                swappingReader.readManifest(manifest),
                LegalManifestIssueCode.RELEASE_ROOT_INVALID,
                ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    @Test
    void mapsOperationalManifestIoFailureToErrorWithoutLeakingTheHostPath() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Path manifest = writeManifest(release, "{}".getBytes(StandardCharsets.UTF_8));
        ConfinedReleaseReader failingReader = new ConfinedReleaseReader((path, kind) -> {
            throw new IOException("sensitive host detail: " + path);
        });

        LegalManifestValidation<ManifestSource> result = failingReader.readManifest(manifest);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.MANIFEST_READ_ERROR);
            assertThat(issue.location()).isEqualTo(ConfinedReleaseReader.MANIFEST_FILENAME);
            assertThat(issue.message()).doesNotContain(temporaryDirectory.toString());
        });
    }

    @Test
    void mapsSecurityFailureWhileAbsolutizingToManifestReadError() {
        Path securityDeniedPath = (Path) Proxy.newProxyInstance(
                Path.class.getClassLoader(),
                new Class<?>[]{Path.class},
                (proxy, method, arguments) -> {
                    if ("getFileName".equals(method.getName())) {
                        return Path.of(ConfinedReleaseReader.MANIFEST_FILENAME);
                    }
                    if ("toAbsolutePath".equals(method.getName())) {
                        throw new SecurityException("host policy detail");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        LegalManifestValidation<ManifestSource> result =
                reader.readManifest(securityDeniedPath);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.MANIFEST_READ_ERROR);
            assertThat(issue.location()).isEqualTo(ConfinedReleaseReader.MANIFEST_FILENAME);
            assertThat(issue.message()).doesNotContain("host policy detail");
        });
    }

    @ParameterizedTest
    @EnumSource(OperationalFailure.class)
    void mapsUncheckedOperationalManifestFailuresToManifestReadError(
            OperationalFailure failure) throws IOException {
        Path release = createRelease(RELEASE_ID);
        Path manifest = writeManifest(release, "{}".getBytes(StandardCharsets.UTF_8));
        ConfinedReleaseReader failingReader = new ConfinedReleaseReader((path, kind) -> {
            if (kind == ConfinedReleaseReader.FileKind.MANIFEST) {
                failure.raise();
            }
        });

        LegalManifestValidation<ManifestSource> result = failingReader.readManifest(manifest);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.MANIFEST_READ_ERROR);
            assertThat(issue.location()).isEqualTo(ConfinedReleaseReader.MANIFEST_FILENAME);
        });
    }

    @Test
    void mapsAClosedAlternativeProviderToManifestReadError() throws IOException {
        Path archive = temporaryDirectory.resolve("closed-release.zip");
        URI archiveUri = URI.create("jar:" + archive.toUri());
        Path closedManifest;
        try (FileSystem zipFileSystem = FileSystems.newFileSystem(
                archiveUri,
                Map.of("create", "true"))) {
            Path release = Files.createDirectory(zipFileSystem.getPath("/", RELEASE_ID));
            closedManifest = Files.writeString(
                    release.resolve(ConfinedReleaseReader.MANIFEST_FILENAME),
                    "{}");
        }

        LegalManifestValidation<ManifestSource> result = reader.readManifest(closedManifest);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.MANIFEST_READ_ERROR);
    }

    @Test
    void requiresPublicationIdToEqualTheRealReleaseDirectoryBasename() throws IOException {
        Path release = createRelease(RELEASE_ID);
        ManifestSource source = readManifestSource(release);

        assertBlocked(
                reader.readDocuments(source, manifest("another-release", List.of())),
                LegalManifestIssueCode.PUBLICATION_ID_DIRECTORY_MISMATCH,
                "publicationId");
    }

    @Test
    void blocksMoreDocumentsThanTheFrozenSchemaLimitBeforePreallocation() throws IOException {
        Path release = createRelease(RELEASE_ID);
        ManifestSource manifestSource = readManifestSource(release);
        List<LegalManifestV1.DocumentEntry> declarations = new ArrayList<>();
        for (int index = 0; index <= LegalManifestLimits.MAX_DOCUMENTS; index++) {
            declarations.add(document("document-" + index, "document-" + index + ".md"));
        }

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, declarations)),
                LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID,
                ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    @Test
    void rejectsAReleaseRootReplacedBetweenManifestAndDocumentPhases() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Files.writeString(release.resolve("legal.md"), "# Original\n");
        ManifestSource manifestSource = readManifestSource(release);
        Path displacedRelease = temporaryDirectory.resolve("displaced-release");
        Files.move(release, displacedRelease);
        Path replacementRelease = Files.createDirectory(release);
        Files.writeString(replacementRelease.resolve("legal.md"), "# Replacement\n");

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("legal", "legal.md")))),
                LegalManifestIssueCode.RELEASE_ROOT_INVALID,
                ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    @Test
    void rejectsAReleaseRootChangedToSymlinkBetweenPhases() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Files.writeString(release.resolve("legal.md"), "# Original\n");
        ManifestSource manifestSource = readManifestSource(release);
        Path displacedRelease = temporaryDirectory.resolve("displaced-release");
        Files.move(release, displacedRelease);
        Files.createSymbolicLink(release, displacedRelease);

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("legal", "legal.md")))),
                LegalManifestIssueCode.RELEASE_SYMLINK_FORBIDDEN,
                ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    @Test
    void revalidatesTheExactLexicalRootWhenAnExternalAncestorChanges() throws IOException {
        Path originalParent = Files.createDirectory(temporaryDirectory.resolve("original-parent"));
        Path release = Files.createDirectory(originalParent.resolve(RELEASE_ID));
        Files.writeString(release.resolve("legal.md"), "# Original\n");
        writeManifest(release, "{}".getBytes(StandardCharsets.UTF_8));

        Path ancestorAlias = temporaryDirectory.resolve("ancestor-alias");
        Files.createSymbolicLink(ancestorAlias, originalParent);
        ManifestSource manifestSource = reader.readManifest(ancestorAlias
                .resolve(RELEASE_ID)
                .resolve(".")
                .resolve(ConfinedReleaseReader.MANIFEST_FILENAME)).value().orElseThrow();

        Path replacementParent = Files.createDirectory(
                temporaryDirectory.resolve("replacement-parent"));
        Files.createSymbolicLink(replacementParent.resolve(RELEASE_ID), release);
        Files.delete(ancestorAlias);
        Files.createSymbolicLink(ancestorAlias, replacementParent);

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("legal", "legal.md")))),
                LegalManifestIssueCode.RELEASE_SYMLINK_FORBIDDEN,
                ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/tmp/legal.md",
            "../legal.md",
            "nested/../legal.md",
            "nested\\legal.md",
            "https://example.test/legal.md",
            "legal.txt"
    })
    void rejectsUnsafeAbsoluteTraversalUrlBackslashAndWrongExtensionSources(String source)
            throws IOException {
        Path release = createRelease(RELEASE_ID);
        ManifestSource manifestSource = readManifestSource(release);

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("doc", source)))),
                LegalManifestIssueCode.DOCUMENT_SOURCE_INVALID,
                "documents/0/source");
    }

    @Test
    void rejectsDuplicateSourcesBeforeReadingDocuments() throws IOException {
        Path release = createRelease(RELEASE_ID);
        ManifestSource manifestSource = readManifestSource(release);
        List<LegalManifestV1.DocumentEntry> documents = List.of(
                document("first", "same.md"),
                document("second", "same.md"));

        assertBlocked(
                reader.readDocuments(manifestSource, manifest(RELEASE_ID, documents)),
                LegalManifestIssueCode.DOCUMENT_SOURCE_DUPLICATE,
                "documents/1/source");
    }

    @Test
    void distinguishesMissingAndNonRegularDocumentSources() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Files.createDirectory(release.resolve("directory.md"));
        Files.writeString(release.resolve("not-a-directory"), "regular file");
        ManifestSource manifestSource = readManifestSource(release);

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("missing", "missing.md")))),
                LegalManifestIssueCode.DOCUMENT_NOT_FOUND,
                "missing.md");

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("directory", "directory.md")))),
                LegalManifestIssueCode.DOCUMENT_NOT_REGULAR,
                "directory.md");

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(
                                document("nested", "not-a-directory/legal.md")))),
                LegalManifestIssueCode.DOCUMENT_NOT_REGULAR,
                "not-a-directory/legal.md");
    }

    @Test
    void rejectsSymlinkComponentsPointingInsideOrOutsideTheRelease() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Path internalDirectory = Files.createDirectory(release.resolve("internal"));
        Files.writeString(internalDirectory.resolve("legal.md"), "# Internal\n");
        Files.createSymbolicLink(release.resolve("inside-link"), internalDirectory.getFileName());

        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.writeString(outsideDirectory.resolve("legal.md"), "# Outside\n");
        Files.createSymbolicLink(release.resolve("outside-link"), outsideDirectory);
        ManifestSource manifestSource = readManifestSource(release);

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(
                                document("inside", "inside-link/legal.md")))),
                LegalManifestIssueCode.DOCUMENT_SYMLINK_FORBIDDEN,
                "inside-link/legal.md");
        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(
                                document("outside", "outside-link/legal.md")))),
                LegalManifestIssueCode.DOCUMENT_SYMLINK_FORBIDDEN,
                "outside-link/legal.md");
    }

    @Test
    void rejectsFinalDocumentSymlinkEvenWhenItPointsInsideTheRelease() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Files.writeString(release.resolve("real.md"), "# Real\n");
        Files.createSymbolicLink(release.resolve("linked.md"), Path.of("real.md"));
        ManifestSource manifestSource = readManifestSource(release);

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("linked", "linked.md")))),
                LegalManifestIssueCode.DOCUMENT_SYMLINK_FORBIDDEN,
                "linked.md");
    }

    @Test
    void detectsDocumentReplacementAfterReadUsingStableAttributes() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Path document = release.resolve("legal.md");
        Files.write(document, "old".getBytes(StandardCharsets.UTF_8));
        ManifestSource manifestSource = readManifestSource(release);
        ConfinedReleaseReader swappingReader = new ConfinedReleaseReader((path, kind) -> {
            if (kind == ConfinedReleaseReader.FileKind.DOCUMENT) {
                replaceAtomically(path, "new".getBytes(StandardCharsets.UTF_8));
            }
        });

        assertBlocked(
                swappingReader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("legal", "legal.md")))),
                LegalManifestIssueCode.DOCUMENT_FILE_CHANGED,
                "legal.md");
    }

    @Test
    void detectsDirectoryChangedToInternalSymlinkImmediatelyAfterRead() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Path section = Files.createDirectory(release.resolve("section"));
        Files.writeString(section.resolve("legal.md"), "# Legal\n");
        ManifestSource manifestSource = readManifestSource(release);
        ConfinedReleaseReader swappingReader = new ConfinedReleaseReader((path, kind) -> {
            if (kind == ConfinedReleaseReader.FileKind.DOCUMENT) {
                Path originalSection = release.resolve("section-original");
                Files.move(section, originalSection);
                Files.createSymbolicLink(section, originalSection.getFileName());
            }
        });

        assertBlocked(
                swappingReader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(
                                document("legal", "section/legal.md")))),
                LegalManifestIssueCode.DOCUMENT_SYMLINK_FORBIDDEN,
                "section/legal.md");
    }

    @Test
    void revalidatesReleaseRootImmediatelyAfterReadingADocument() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Files.writeString(release.resolve("legal.md"), "# Original\n");
        ManifestSource manifestSource = readManifestSource(release);
        ConfinedReleaseReader swappingReader = new ConfinedReleaseReader((path, kind) -> {
            if (kind == ConfinedReleaseReader.FileKind.DOCUMENT) {
                Path displacedRelease = temporaryDirectory.resolve("displaced-release");
                Files.move(release, displacedRelease);
                Path replacementRelease = Files.createDirectory(release);
                Files.writeString(replacementRelease.resolve("legal.md"), "# Replacement\n");
            }
        });

        assertBlocked(
                swappingReader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("legal", "legal.md")))),
                LegalManifestIssueCode.RELEASE_ROOT_INVALID,
                ConfinedReleaseReader.MANIFEST_FILENAME);
    }

    @Test
    void mapsOperationalDocumentIoFailureToError() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Files.writeString(release.resolve("legal.md"), "# Legal\n");
        ManifestSource manifestSource = readManifestSource(release);
        ConfinedReleaseReader failingReader = new ConfinedReleaseReader((path, kind) -> {
            if (kind == ConfinedReleaseReader.FileKind.DOCUMENT) {
                throw new IOException("host-only detail");
            }
        });

        LegalManifestValidation<ReleaseDocuments> result = failingReader.readDocuments(
                manifestSource,
                manifest(RELEASE_ID, List.of(document("legal", "legal.md"))));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.DOCUMENT_READ_ERROR);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::location)
                .containsExactly("legal.md");
    }

    @ParameterizedTest
    @EnumSource(OperationalFailure.class)
    void mapsUncheckedOperationalDocumentFailuresToDocumentReadError(
            OperationalFailure failure) throws IOException {
        Path release = createRelease(RELEASE_ID);
        Files.writeString(release.resolve("legal.md"), "# Legal\n");
        ManifestSource manifestSource = readManifestSource(release);
        ConfinedReleaseReader failingReader = new ConfinedReleaseReader((path, kind) -> {
            if (kind == ConfinedReleaseReader.FileKind.DOCUMENT) {
                failure.raise();
            }
        });

        LegalManifestValidation<ReleaseDocuments> result = failingReader.readDocuments(
                manifestSource,
                manifest(RELEASE_ID, List.of(document("legal", "legal.md"))));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.DOCUMENT_READ_ERROR);
            assertThat(issue.location()).isEqualTo("legal.md");
        });
    }

    @Test
    void enforcesOneMibPerDocument() throws IOException {
        Path release = createRelease(RELEASE_ID);
        writeSparseFile(
                release.resolve("oversized.md"),
                LegalManifestLimits.MAX_MARKDOWN_BYTES + 1L);
        ManifestSource manifestSource = readManifestSource(release);

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("oversized", "oversized.md")))),
                LegalManifestIssueCode.DOCUMENT_SIZE_LIMIT_EXCEEDED,
                "oversized.md");
    }

    @Test
    void boundedChannelStopsADocumentThatGrowsAfterTheInitialSizeCheck() throws IOException {
        Path release = createRelease(RELEASE_ID);
        Path growingDocument = release.resolve("growing.md");
        Files.write(growingDocument, new byte[LegalManifestLimits.MAX_MARKDOWN_BYTES]);
        ManifestSource manifestSource = readManifestSource(release);
        ConfinedReleaseReader growingReader = new ConfinedReleaseReader(
                new ConfinedReleaseReader.ReadHook() {
                    @Override
                    public void beforeRead(Path path, ConfinedReleaseReader.FileKind kind)
                            throws IOException {
                        if (kind == ConfinedReleaseReader.FileKind.DOCUMENT) {
                            Files.write(path, new byte[]{0}, StandardOpenOption.APPEND);
                        }
                    }

                    @Override
                    public void afterRead(Path path, ConfinedReleaseReader.FileKind kind) {
                    }
                });

        assertBlocked(
                growingReader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, List.of(document("growing", "growing.md")))),
                LegalManifestIssueCode.DOCUMENT_SIZE_LIMIT_EXCEEDED,
                "growing.md");
    }

    @Test
    void enforcesSixteenMibAcrossAllDocuments() throws IOException {
        Path release = createRelease(RELEASE_ID);
        byte[] oneMib = new byte[LegalManifestLimits.MAX_MARKDOWN_BYTES];
        List<LegalManifestV1.DocumentEntry> declarations = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            String source = "document-" + index + ".md";
            Files.write(release.resolve(source), oneMib);
            declarations.add(document("document-" + index, source));
        }
        Files.write(release.resolve("document-16.md"), new byte[]{0});
        ManifestSource manifestSource = readManifestSource(release);

        LegalManifestValidation<ReleaseDocuments> exactResult = reader.readDocuments(
                manifestSource,
                manifest(RELEASE_ID, declarations));
        assertThat(exactResult.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(exactResult.value().orElseThrow().totalBytes())
                .isEqualTo(LegalManifestLimits.MAX_TOTAL_MARKDOWN_BYTES);

        declarations.add(document("document-16", "document-16.md"));

        assertBlocked(
                reader.readDocuments(
                        manifestSource,
                        manifest(RELEASE_ID, declarations)),
                LegalManifestIssueCode.DOCUMENT_TOTAL_SIZE_LIMIT_EXCEEDED,
                "document-16.md");
    }

    @Test
    void preservesDocumentOrderAndReturnsImmutableDefensiveResults() throws IOException {
        Path release = createRelease(RELEASE_ID);
        byte[] secondBytes = "# Second\n".getBytes(StandardCharsets.UTF_8);
        byte[] firstBytes = "# First\n".getBytes(StandardCharsets.UTF_8);
        Files.write(release.resolve("second.md"), secondBytes);
        Files.write(release.resolve("first.md"), firstBytes);
        ManifestSource manifestSource = readManifestSource(release);
        LegalManifestV1 manifest = manifest(RELEASE_ID, List.of(
                document("second", "second.md"),
                document("first", "first.md")));

        LegalManifestValidation<ReleaseDocuments> result =
                reader.readDocuments(manifestSource, manifest);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        ReleaseDocuments releaseDocuments = result.value().orElseThrow();
        assertThat(releaseDocuments.documents())
                .extracting(DocumentSource::key)
                .containsExactly("second", "first");
        assertThat(releaseDocuments.documents())
                .extracting(DocumentSource::source)
                .containsExactly("second.md", "first.md");
        assertThat(releaseDocuments.totalBytes())
                .isEqualTo(secondBytes.length + firstBytes.length);

        byte[] exposed = releaseDocuments.documents().get(0).bytes();
        exposed[0] = 'X';
        assertThat(releaseDocuments.documents().get(0).bytes()).containsExactly(secondBytes);
        assertThatThrownBy(() -> releaseDocuments.documents().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void identityFallbackUsesCreationTimeWhenFileKeysAreUnavailable() {
        FileTime creation = FileTime.fromMillis(1_000L);
        FileTime differentCreation = FileTime.fromMillis(2_000L);
        FileTime modified = FileTime.fromMillis(3_000L);
        BasicFileAttributes before = attributesWithoutFileKey(10L, creation, modified);
        BasicFileAttributes same = attributesWithoutFileKey(10L, creation, modified);
        BasicFileAttributes recreated = attributesWithoutFileKey(
                10L,
                differentCreation,
                modified);

        assertThat(ConfinedReleaseReader.sameIdentity(before, same)).isTrue();
        assertThat(ConfinedReleaseReader.sameIdentity(before, recreated)).isFalse();
    }

    private Path createRelease(String releaseId) throws IOException {
        return Files.createDirectory(temporaryDirectory.resolve(releaseId));
    }

    private Path writeManifest(Path release, byte[] bytes) throws IOException {
        return Files.write(release.resolve(ConfinedReleaseReader.MANIFEST_FILENAME), bytes);
    }

    private ManifestSource readManifestSource(Path release) throws IOException {
        Path manifest = writeManifest(release, "{}".getBytes(StandardCharsets.UTF_8));
        return reader.readManifest(manifest).value().orElseThrow();
    }

    private static void writeSparseFile(Path path, long size) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.position(size - 1L);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }

    private static void replaceAtomically(Path path, byte[] replacement) throws IOException {
        Path sibling = path.resolveSibling(path.getFileName() + ".replacement");
        Files.write(sibling, replacement);
        try {
            Files.move(
                    sibling,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(sibling, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static BasicFileAttributes attributesWithoutFileKey(
            long size,
            FileTime creationTime,
            FileTime lastModifiedTime) {
        return new BasicFileAttributes() {
            @Override
            public FileTime lastModifiedTime() {
                return lastModifiedTime;
            }

            @Override
            public FileTime lastAccessTime() {
                return lastModifiedTime;
            }

            @Override
            public FileTime creationTime() {
                return creationTime;
            }

            @Override
            public boolean isRegularFile() {
                return true;
            }

            @Override
            public boolean isDirectory() {
                return false;
            }

            @Override
            public boolean isSymbolicLink() {
                return false;
            }

            @Override
            public boolean isOther() {
                return false;
            }

            @Override
            public long size() {
                return size;
            }

            @Override
            public Object fileKey() {
                return null;
            }
        };
    }

    private static LegalManifestV1 manifest(
            String publicationId,
            List<LegalManifestV1.DocumentEntry> documents) {
        LegalManifestV1.Contacts contacts = new LegalManifestV1.Contacts(
                "legal@example.test",
                "privacy@example.test",
                "support@example.test");
        LegalManifestV1.PublisherSnapshot publisher = new LegalManifestV1.PublisherSnapshot(
                "OrdenFix Argentina SAS",
                "30-00000000-0",
                "Calle 123",
                "Argentina",
                "Lunes a viernes",
                contacts);
        LegalManifestV1.ReviewRecord approved = new LegalManifestV1.ReviewRecord(
                LegalManifestV1.ReviewStatus.APPROVED,
                "review-1",
                EFFECTIVE_AT);
        return new LegalManifestV1(
                null,
                1,
                publicationId,
                LocaleLegal.ES_AR,
                publisher,
                new LegalManifestV1.Review(approved, approved),
                documents,
                List.of());
    }

    private static LegalManifestV1.DocumentEntry document(String key, String source) {
        return new LegalManifestV1.DocumentEntry(
                key,
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                "1.0.0",
                LocaleLegal.ES_AR,
                source,
                "0".repeat(64),
                EFFECTIVE_AT,
                List.of(ContextoLegal.REGISTRO),
                false);
    }

    private static void assertBlocked(
            LegalManifestValidation<?> result,
            LegalManifestIssueCode expectedCode,
            String expectedLocation) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(expectedCode);
            assertThat(issue.location()).isEqualTo(expectedLocation);
            assertThat(issue.location()).doesNotStartWith("/");
            assertThat(issue.location()).doesNotContain("\\", "://", "../");
        });
    }

    private enum OperationalFailure {
        CLOSED_FILE_SYSTEM,
        PROVIDER_MISMATCH,
        UNSUPPORTED_OPERATION,
        IO_ERROR;

        void raise() {
            switch (this) {
                case CLOSED_FILE_SYSTEM -> throw new ClosedFileSystemException();
                case PROVIDER_MISMATCH -> throw new ProviderMismatchException();
                case UNSUPPORTED_OPERATION -> throw new UnsupportedOperationException(
                        "provider operation");
                case IO_ERROR -> throw new java.io.IOError(
                        new IOException("provider I/O failure"));
            }
        }
    }
}
