package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.IOError;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads one legal release without allowing its declared sources to escape or traverse symlinks.
 *
 * <p>The API is deliberately two-step: callers first obtain the bounded manifest bytes, validate
 * and map them, and only then ask this reader for the Markdown files declared by that validated
 * manifest. Expected release defects are returned as blockers; operational I/O failures never
 * expose host paths or exception details.</p>
 */
public final class ConfinedReleaseReader {

    public static final String MANIFEST_FILENAME = "publication-manifest.json";
    private static final String MANIFEST_LOCATION = MANIFEST_FILENAME;
    private static final int MAX_SOURCE_LENGTH = 240;
    private static final int READ_BUFFER_BYTES = 8_192;
    private static final Pattern SOURCE_PATTERN = Pattern.compile(
            "^(?:[a-z0-9][a-z0-9._-]*/)*[a-z0-9][a-z0-9._-]*\\.md$");

    private final ReadHook readHook;

    public ConfinedReleaseReader() {
        this(ReadHook.NOOP);
    }

    ConfinedReleaseReader(ReadHook readHook) {
        this.readHook = Objects.requireNonNull(readHook, "readHook");
    }

    /**
     * Locates the release root and reads the manifest with a hard one-MiB limit.
     */
    public LegalManifestValidation<ManifestSource> readManifest(Path manifestPath) {
        if (manifestPath == null) {
            return blocked(LegalManifestIssueCode.MANIFEST_PATH_REQUIRED, MANIFEST_LOCATION);
        }

        final Path absoluteManifest;
        final Path lexicalRoot;
        try {
            Path fileName = manifestPath.getFileName();
            if (fileName == null || !MANIFEST_FILENAME.equals(fileName.toString())) {
                return blocked(
                        LegalManifestIssueCode.MANIFEST_FILENAME_INVALID,
                        MANIFEST_LOCATION);
            }

            // Do not normalize before resolving the real parent: lexical ".." has different
            // semantics when an allowed external ancestor is itself a symlink.
            absoluteManifest = manifestPath.toAbsolutePath();
            LegalManifestValidation<Path> lexicalRootResult = lexicalReleaseRoot(
                    absoluteManifest.getParent());
            if (!lexicalRootResult.passed()) {
                return lexicalRootResult.asFailure();
            }
            lexicalRoot = lexicalRootResult.value().orElseThrow();
        } catch (SecurityException
                 | ClosedFileSystemException
                 | FileSystemNotFoundException
                 | ProviderMismatchException
                 | UnsupportedOperationException
                 | IOError exception) {
            return error(LegalManifestIssueCode.MANIFEST_READ_ERROR, MANIFEST_LOCATION);
        }

        LegalManifestValidation<ResolvedRoot> rootResult = resolveReleaseRoot(lexicalRoot);
        if (!rootResult.passed()) {
            return rootResult.asFailure();
        }
        ResolvedRoot resolvedRoot = rootResult.value().orElseThrow();
        Path realRoot = resolvedRoot.realRoot();

        LegalManifestValidation<Path> manifestResult = resolveManifest(
                absoluteManifest,
                realRoot);
        if (!manifestResult.passed()) {
            return manifestResult.asFailure();
        }
        Path realManifest = manifestResult.value().orElseThrow();

        LegalManifestValidation<byte[]> bytesResult = readStableFile(
                realManifest,
                FileKind.MANIFEST,
                MANIFEST_LOCATION,
                LegalManifestLimits.MAX_MANIFEST_BYTES,
                LegalManifestIssueCode.MANIFEST_SIZE_LIMIT_EXCEEDED,
                LegalManifestIssueCode.MANIFEST_NOT_REGULAR,
                LegalManifestIssueCode.MANIFEST_FILE_CHANGED,
                LegalManifestIssueCode.MANIFEST_READ_ERROR,
                () -> verifyRootIdentity(
                        resolvedRoot,
                        LegalManifestIssueCode.MANIFEST_READ_ERROR,
                        MANIFEST_LOCATION));
        if (!bytesResult.passed()) {
            return bytesResult.asFailure();
        }

        return LegalManifestValidation.pass(new ManifestSource(
                resolvedRoot,
                bytesResult.value().orElseThrow()));
    }

    /**
     * Reads each Markdown source from the already established release root, in manifest order.
     */
    public LegalManifestValidation<ReleaseDocuments> readDocuments(
            ManifestSource manifestSource,
            LegalManifestV1 manifest) {
        Objects.requireNonNull(manifestSource, "manifestSource");
        Objects.requireNonNull(manifest, "manifest");

        LegalManifestValidation<Boolean> initialRootResult = verifyRootIdentity(
                manifestSource.resolvedRoot,
                LegalManifestIssueCode.DOCUMENT_READ_ERROR,
                MANIFEST_LOCATION);
        if (!initialRootResult.passed()) {
            return initialRootResult.asFailure();
        }

        if (!manifest.publicationId().equals(
                manifestSource.resolvedRoot.directoryName())) {
            return blocked(
                    LegalManifestIssueCode.PUBLICATION_ID_DIRECTORY_MISMATCH,
                    "publicationId");
        }

        List<LegalManifestV1.DocumentEntry> declarations = manifest.documents();
        if (declarations.size() > LegalManifestLimits.MAX_DOCUMENTS) {
            return blocked(
                    LegalManifestIssueCode.MANIFEST_SCHEMA_INVALID,
                    MANIFEST_LOCATION);
        }
        List<Path> relativePaths = new ArrayList<>(declarations.size());
        Set<String> uniqueSources = new HashSet<>(declarations.size());
        for (int index = 0; index < declarations.size(); index++) {
            String source = declarations.get(index).source();
            String declarationLocation = declarationLocation(index);
            LegalManifestValidation<Path> sourceResult = validateSource(
                    manifestSource.resolvedRoot.realRoot(),
                    source,
                    declarationLocation);
            if (!sourceResult.passed()) {
                return sourceResult.asFailure();
            }
            if (!uniqueSources.add(source)) {
                return blocked(
                        LegalManifestIssueCode.DOCUMENT_SOURCE_DUPLICATE,
                        declarationLocation);
            }
            relativePaths.add(sourceResult.value().orElseThrow());
        }

        List<DocumentSource> documents = new ArrayList<>(relativePaths.size());
        long totalBytes = 0L;
        for (int index = 0; index < relativePaths.size(); index++) {
            Path relativePath = relativePaths.get(index);
            String safeLocation = declarations.get(index).source();

            LegalManifestValidation<Path> confinedResult = resolveDocument(
                    manifestSource.resolvedRoot.realRoot(),
                    relativePath,
                    safeLocation);
            if (!confinedResult.passed()) {
                return confinedResult.asFailure();
            }

            long remainingTotal = LegalManifestLimits.MAX_TOTAL_MARKDOWN_BYTES - totalBytes;
            if (remainingTotal < 0L) {
                return blocked(
                        LegalManifestIssueCode.DOCUMENT_TOTAL_SIZE_LIMIT_EXCEEDED,
                        safeLocation);
            }
            long readLimit = Math.min(LegalManifestLimits.MAX_MARKDOWN_BYTES, remainingTotal);
            LegalManifestIssueCode limitCode = remainingTotal < LegalManifestLimits.MAX_MARKDOWN_BYTES
                    ? LegalManifestIssueCode.DOCUMENT_TOTAL_SIZE_LIMIT_EXCEEDED
                    : LegalManifestIssueCode.DOCUMENT_SIZE_LIMIT_EXCEEDED;

            Path confinedPath = confinedResult.value().orElseThrow();
            LegalManifestValidation<byte[]> bytesResult = readStableFile(
                    confinedPath,
                    FileKind.DOCUMENT,
                    safeLocation,
                    readLimit,
                    limitCode,
                    LegalManifestIssueCode.DOCUMENT_NOT_REGULAR,
                    LegalManifestIssueCode.DOCUMENT_FILE_CHANGED,
                    LegalManifestIssueCode.DOCUMENT_READ_ERROR,
                    () -> verifyDocumentGuard(
                            manifestSource,
                            relativePath,
                            confinedPath,
                            safeLocation));
            if (!bytesResult.passed()) {
                return bytesResult.asFailure();
            }

            byte[] bytes = bytesResult.value().orElseThrow();
            totalBytes += bytes.length;
            if (totalBytes > LegalManifestLimits.MAX_TOTAL_MARKDOWN_BYTES) {
                return blocked(
                        LegalManifestIssueCode.DOCUMENT_TOTAL_SIZE_LIMIT_EXCEEDED,
                        safeLocation);
            }
            documents.add(new DocumentSource(
                    declarations.get(index).key(),
                    declarations.get(index).source(),
                    bytes));
        }

        LegalManifestValidation<Boolean> finalRootResult = verifyRootIdentity(
                manifestSource.resolvedRoot,
                LegalManifestIssueCode.DOCUMENT_READ_ERROR,
                MANIFEST_LOCATION);
        if (!finalRootResult.passed()) {
            return finalRootResult.asFailure();
        }

        return LegalManifestValidation.pass(new ReleaseDocuments(documents, totalBytes));
    }

    private LegalManifestValidation<Path> lexicalReleaseRoot(Path rawParent) {
        if (rawParent == null) {
            return blocked(LegalManifestIssueCode.RELEASE_ROOT_INVALID, MANIFEST_LOCATION);
        }

        Path lexicalRoot = rawParent.getRoot();
        boolean hasNamedComponent = false;
        for (Path component : rawParent) {
            String name = component.toString();
            if (".".equals(name)) {
                // Removing a current-directory component is semantics preserving even next to a
                // symlink, and exposes the actual final root entry to NOFOLLOW_LINKS.
                continue;
            }
            if ("..".equals(name)) {
                // Collapsing parent traversal across a possible symlink changes filesystem
                // semantics. Reject the ambiguous CLI spelling instead of normalizing it.
                return blocked(LegalManifestIssueCode.RELEASE_ROOT_INVALID, MANIFEST_LOCATION);
            }
            lexicalRoot = lexicalRoot == null ? component : lexicalRoot.resolve(component);
            hasNamedComponent = true;
        }

        if (!hasNamedComponent || lexicalRoot == null || lexicalRoot.getFileName() == null) {
            return blocked(LegalManifestIssueCode.RELEASE_ROOT_INVALID, MANIFEST_LOCATION);
        }
        return LegalManifestValidation.pass(lexicalRoot);
    }

    private LegalManifestValidation<ResolvedRoot> resolveReleaseRoot(Path lexicalRoot) {
        try {
            BasicFileAttributes lexicalBefore = Files.readAttributes(
                    lexicalRoot,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            LegalManifestValidation<Boolean> lexicalBeforeResult = validateRootAttributes(
                    lexicalBefore,
                    null);
            if (!lexicalBeforeResult.passed()) {
                return lexicalBeforeResult.asFailure();
            }
            RootIdentity identity = RootIdentity.from(lexicalBefore);

            // Following here is intentional: symlinks may exist only in ancestors outside the
            // exact lexical root entry inspected above.
            Path realRoot = lexicalRoot.toRealPath();
            BasicFileAttributes realAttributes = Files.readAttributes(
                    realRoot,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            LegalManifestValidation<Boolean> realResult = validateRootAttributes(
                    realAttributes,
                    identity);
            if (!realResult.passed()) {
                return realResult.asFailure();
            }

            BasicFileAttributes lexicalAfter = Files.readAttributes(
                    lexicalRoot,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            LegalManifestValidation<Boolean> lexicalAfterResult = validateRootAttributes(
                    lexicalAfter,
                    identity);
            if (!lexicalAfterResult.passed()) {
                return lexicalAfterResult.asFailure();
            }
            Path realRootName = realRoot.getFileName();
            if (realRootName == null) {
                return blocked(LegalManifestIssueCode.RELEASE_ROOT_INVALID, MANIFEST_LOCATION);
            }
            return LegalManifestValidation.pass(new ResolvedRoot(
                    lexicalRoot,
                    realRoot,
                    RootIdentity.from(lexicalAfter),
                    realRootName.toString()));
        } catch (NoSuchFileException | NotDirectoryException exception) {
            return blocked(LegalManifestIssueCode.RELEASE_ROOT_INVALID, MANIFEST_LOCATION);
        } catch (AccessDeniedException exception) {
            return error(LegalManifestIssueCode.MANIFEST_READ_ERROR, MANIFEST_LOCATION);
        } catch (SecurityException
                 | ClosedFileSystemException
                 | FileSystemNotFoundException
                 | ProviderMismatchException
                 | UnsupportedOperationException
                 | IOError exception) {
            return error(LegalManifestIssueCode.MANIFEST_READ_ERROR, MANIFEST_LOCATION);
        } catch (IOException exception) {
            return error(LegalManifestIssueCode.MANIFEST_READ_ERROR, MANIFEST_LOCATION);
        }
    }

    private LegalManifestValidation<Boolean> verifyRootIdentity(
            ResolvedRoot resolvedRoot,
            LegalManifestIssueCode errorCode,
            String errorLocation) {
        try {
            BasicFileAttributes lexicalBefore = Files.readAttributes(
                    resolvedRoot.lexicalRoot(),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            LegalManifestValidation<Boolean> lexicalBeforeResult = validateRootAttributes(
                    lexicalBefore,
                    resolvedRoot.identity());
            if (!lexicalBeforeResult.passed()) {
                return lexicalBeforeResult;
            }

            Path currentRealRoot = resolvedRoot.lexicalRoot().toRealPath();
            if (!currentRealRoot.equals(resolvedRoot.realRoot())) {
                return blocked(LegalManifestIssueCode.RELEASE_ROOT_INVALID, MANIFEST_LOCATION);
            }

            BasicFileAttributes realAttributes = Files.readAttributes(
                    resolvedRoot.realRoot(),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            LegalManifestValidation<Boolean> realResult = validateRootAttributes(
                    realAttributes,
                    resolvedRoot.identity());
            if (!realResult.passed()) {
                return realResult;
            }

            BasicFileAttributes lexicalAfter = Files.readAttributes(
                    resolvedRoot.lexicalRoot(),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return validateRootAttributes(lexicalAfter, resolvedRoot.identity());
        } catch (NoSuchFileException | NotDirectoryException exception) {
            return blocked(LegalManifestIssueCode.RELEASE_ROOT_INVALID, MANIFEST_LOCATION);
        } catch (AccessDeniedException exception) {
            return error(errorCode, errorLocation);
        } catch (SecurityException
                 | ClosedFileSystemException
                 | FileSystemNotFoundException
                 | ProviderMismatchException
                 | UnsupportedOperationException
                 | IOError exception) {
            return error(errorCode, errorLocation);
        } catch (IOException exception) {
            return error(errorCode, errorLocation);
        }
    }

    private LegalManifestValidation<Boolean> validateRootAttributes(
            BasicFileAttributes attributes,
            RootIdentity expectedIdentity) {
        if (attributes.isSymbolicLink()) {
            return blocked(LegalManifestIssueCode.RELEASE_SYMLINK_FORBIDDEN, MANIFEST_LOCATION);
        }
        if (!attributes.isDirectory()
                || (expectedIdentity != null && !expectedIdentity.matches(attributes))) {
            return blocked(LegalManifestIssueCode.RELEASE_ROOT_INVALID, MANIFEST_LOCATION);
        }
        return LegalManifestValidation.pass(Boolean.TRUE);
    }

    private LegalManifestValidation<Path> resolveManifest(Path manifest, Path realRoot) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    manifest,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                return blocked(LegalManifestIssueCode.MANIFEST_NOT_REGULAR, MANIFEST_LOCATION);
            }

            Path realManifest = manifest.toRealPath();
            Path expectedManifest = realRoot.resolve(MANIFEST_FILENAME).normalize();
            if (!realManifest.equals(expectedManifest) || !realManifest.startsWith(realRoot)) {
                return blocked(LegalManifestIssueCode.RELEASE_ROOT_INVALID, MANIFEST_LOCATION);
            }
            return LegalManifestValidation.pass(realManifest);
        } catch (NoSuchFileException | NotDirectoryException exception) {
            return blocked(LegalManifestIssueCode.MANIFEST_NOT_REGULAR, MANIFEST_LOCATION);
        } catch (AccessDeniedException exception) {
            return error(LegalManifestIssueCode.MANIFEST_READ_ERROR, MANIFEST_LOCATION);
        } catch (SecurityException
                 | ClosedFileSystemException
                 | FileSystemNotFoundException
                 | ProviderMismatchException
                 | UnsupportedOperationException
                 | IOError exception) {
            return error(LegalManifestIssueCode.MANIFEST_READ_ERROR, MANIFEST_LOCATION);
        } catch (IOException exception) {
            return error(LegalManifestIssueCode.MANIFEST_READ_ERROR, MANIFEST_LOCATION);
        }
    }

    private LegalManifestValidation<Path> validateSource(
            Path realRoot,
            String source,
            String location) {
        if (source == null
                || source.isBlank()
                || source.length() > MAX_SOURCE_LENGTH
                || source.indexOf('\\') >= 0
                || source.contains("://")
                || !source.endsWith(".md")
                || !SOURCE_PATTERN.matcher(source).matches()) {
            return blocked(LegalManifestIssueCode.DOCUMENT_SOURCE_INVALID, location);
        }

        try {
            Path relativePath = realRoot.getFileSystem().getPath(source);
            if (relativePath.isAbsolute() || relativePath.getNameCount() == 0) {
                return blocked(LegalManifestIssueCode.DOCUMENT_SOURCE_INVALID, location);
            }
            for (Path component : relativePath) {
                String segment = component.toString();
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    return blocked(LegalManifestIssueCode.DOCUMENT_SOURCE_INVALID, location);
                }
            }
            if (!relativePath.equals(relativePath.normalize())) {
                return blocked(LegalManifestIssueCode.DOCUMENT_SOURCE_INVALID, location);
            }
            return LegalManifestValidation.pass(relativePath);
        } catch (InvalidPathException exception) {
            return blocked(LegalManifestIssueCode.DOCUMENT_SOURCE_INVALID, location);
        } catch (SecurityException
                 | ClosedFileSystemException
                 | FileSystemNotFoundException
                 | ProviderMismatchException
                 | UnsupportedOperationException
                 | IOError exception) {
            return error(LegalManifestIssueCode.DOCUMENT_READ_ERROR, location);
        }
    }

    private LegalManifestValidation<Path> resolveDocument(
            Path realRoot,
            Path relativePath,
            String safeLocation) {
        try {
            Path candidate = realRoot.resolve(relativePath).normalize();
            if (!candidate.startsWith(realRoot)) {
                return blocked(LegalManifestIssueCode.DOCUMENT_PATH_ESCAPE, safeLocation);
            }

            Path component = realRoot;
            int componentIndex = 0;
            for (Path name : relativePath) {
                component = component.resolve(name);
                BasicFileAttributes attributes = Files.readAttributes(
                        component,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) {
                    return blocked(
                            LegalManifestIssueCode.DOCUMENT_SYMLINK_FORBIDDEN,
                            safeLocation);
                }
                componentIndex++;
                boolean finalComponent = componentIndex == relativePath.getNameCount();
                if (!finalComponent && !attributes.isDirectory()) {
                    return blocked(
                            LegalManifestIssueCode.DOCUMENT_NOT_REGULAR,
                            safeLocation);
                }
            }

            BasicFileAttributes fileAttributes = Files.readAttributes(
                    candidate,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!fileAttributes.isRegularFile()) {
                return blocked(LegalManifestIssueCode.DOCUMENT_NOT_REGULAR, safeLocation);
            }

            Path realDocument = candidate.toRealPath();
            if (!realDocument.startsWith(realRoot)) {
                return blocked(LegalManifestIssueCode.DOCUMENT_PATH_ESCAPE, safeLocation);
            }
            // Preserve the lexical path rooted at the trusted real release. Returning the
            // toRealPath value would hide a component later replaced with a symlink from the
            // pre/post-read NOFOLLOW checks.
            return LegalManifestValidation.pass(candidate);
        } catch (NoSuchFileException exception) {
            return blocked(LegalManifestIssueCode.DOCUMENT_NOT_FOUND, safeLocation);
        } catch (NotDirectoryException exception) {
            return blocked(LegalManifestIssueCode.DOCUMENT_NOT_REGULAR, safeLocation);
        } catch (InvalidPathException exception) {
            return blocked(LegalManifestIssueCode.DOCUMENT_SOURCE_INVALID, safeLocation);
        } catch (AccessDeniedException exception) {
            return error(LegalManifestIssueCode.DOCUMENT_READ_ERROR, safeLocation);
        } catch (SecurityException
                 | ClosedFileSystemException
                 | FileSystemNotFoundException
                 | ProviderMismatchException
                 | UnsupportedOperationException
                 | IOError exception) {
            return error(LegalManifestIssueCode.DOCUMENT_READ_ERROR, safeLocation);
        } catch (IOException exception) {
            return error(LegalManifestIssueCode.DOCUMENT_READ_ERROR, safeLocation);
        }
    }

    private LegalManifestValidation<byte[]> readStableFile(
            Path path,
            FileKind fileKind,
            String safeLocation,
            long maxBytes,
            LegalManifestIssueCode sizeCode,
            LegalManifestIssueCode nonRegularCode,
            LegalManifestIssueCode changedCode,
            LegalManifestIssueCode errorCode,
            StableReadGuard guard) {
        try {
            BasicFileAttributes before = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.isSymbolicLink()) {
                return blocked(nonRegularCode, safeLocation);
            }
            if (before.size() > maxBytes) {
                return blocked(sizeCode, safeLocation);
            }

            LegalManifestValidation<?> beforeGuardResult = guard.verify();
            if (!beforeGuardResult.passed()) {
                return beforeGuardResult.asFailure();
            }

            readHook.beforeRead(path, fileKind);

            LegalManifestValidation<?> preOpenGuardResult = guard.verify();
            if (!preOpenGuardResult.passed()) {
                return preOpenGuardResult.asFailure();
            }

            byte[] bytes = boundedRead(path, maxBytes);
            if (bytes == null) {
                return blocked(sizeCode, safeLocation);
            }

            readHook.afterRead(path, fileKind);

            LegalManifestValidation<?> afterGuardResult = guard.verify();
            if (!afterGuardResult.passed()) {
                return afterGuardResult.asFailure();
            }

            BasicFileAttributes after = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile()
                    || after.isSymbolicLink()
                    || !sameIdentity(before, after)) {
                return blocked(changedCode, safeLocation);
            }
            return LegalManifestValidation.pass(bytes);
        } catch (NoSuchFileException | NotDirectoryException exception) {
            return blocked(changedCode, safeLocation);
        } catch (AccessDeniedException exception) {
            return error(errorCode, safeLocation);
        } catch (SecurityException
                 | ClosedFileSystemException
                 | FileSystemNotFoundException
                 | ProviderMismatchException
                 | UnsupportedOperationException
                 | IOError exception) {
            return error(errorCode, safeLocation);
        } catch (IOException exception) {
            return error(errorCode, safeLocation);
        }
    }

    private static byte[] boundedRead(Path path, long maxBytes) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(path, options);
             ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity(maxBytes))) {
            ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
            long count = 0L;
            while (true) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    buffer.clear();
                    continue;
                }
                count += read;
                if (count > maxBytes) {
                    return null;
                }
                output.write(buffer.array(), 0, read);
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    private static int initialCapacity(long maxBytes) {
        return (int) Math.min(Math.max(0L, maxBytes), READ_BUFFER_BYTES);
    }

    static boolean sameIdentity(
            BasicFileAttributes before,
            BasicFileAttributes after) {
        return RootIdentity.from(before).matches(after)
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private LegalManifestValidation<Boolean> verifyDocumentGuard(
            ManifestSource manifestSource,
            Path relativePath,
            Path expectedPath,
            String safeLocation) {
        LegalManifestValidation<Boolean> rootResult = verifyRootIdentity(
                manifestSource.resolvedRoot,
                LegalManifestIssueCode.DOCUMENT_READ_ERROR,
                safeLocation);
        if (!rootResult.passed()) {
            return rootResult.asFailure();
        }

        LegalManifestValidation<Path> documentResult = resolveDocument(
                manifestSource.resolvedRoot.realRoot(),
                relativePath,
                safeLocation);
        if (!documentResult.passed()) {
            return documentResult.asFailure();
        }
        if (!expectedPath.equals(documentResult.value().orElseThrow())) {
            return blocked(LegalManifestIssueCode.DOCUMENT_FILE_CHANGED, safeLocation);
        }
        return LegalManifestValidation.pass(Boolean.TRUE);
    }

    private static String declarationLocation(int index) {
        return "documents/" + index + "/source";
    }

    private static <T> LegalManifestValidation<T> blocked(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(code, location));
    }

    private static <T> LegalManifestValidation<T> error(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(code, location));
    }

    enum FileKind {
        MANIFEST,
        DOCUMENT
    }

    @FunctionalInterface
    interface ReadHook {
        ReadHook NOOP = (path, fileKind) -> { };

        default void beforeRead(Path path, FileKind fileKind) throws IOException {
        }

        void afterRead(Path path, FileKind fileKind) throws IOException;
    }

    @FunctionalInterface
    private interface StableReadGuard {
        LegalManifestValidation<?> verify();
    }

    private record RootIdentity(
            Object fileKey,
            FileTime creationTime,
            FileTime lastModifiedTime,
            long size) {

        private RootIdentity {
            creationTime = Objects.requireNonNull(creationTime, "creationTime");
            lastModifiedTime = Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        }

        static RootIdentity from(BasicFileAttributes attributes) {
            return new RootIdentity(
                    attributes.fileKey(),
                    attributes.creationTime(),
                    attributes.lastModifiedTime(),
                    attributes.size());
        }

        boolean matches(BasicFileAttributes attributes) {
            if (fileKey != null || attributes.fileKey() != null) {
                return Objects.equals(fileKey, attributes.fileKey());
            }
            return creationTime.equals(attributes.creationTime())
                    && lastModifiedTime.equals(attributes.lastModifiedTime())
                    && size == attributes.size();
        }
    }

    private record ResolvedRoot(
            Path lexicalRoot,
            Path realRoot,
            RootIdentity identity,
            String directoryName) {

        private ResolvedRoot {
            lexicalRoot = Objects.requireNonNull(lexicalRoot, "lexicalRoot");
            realRoot = Objects.requireNonNull(realRoot, "realRoot");
            identity = Objects.requireNonNull(identity, "identity");
            directoryName = Objects.requireNonNull(directoryName, "directoryName");
        }
    }

    /** Bounded bytes and trusted real root established by {@link #readManifest(Path)}. */
    public static final class ManifestSource {

        private final ResolvedRoot resolvedRoot;
        private final byte[] bytes;

        private ManifestSource(
                ResolvedRoot resolvedRoot,
                byte[] bytes) {
            this.resolvedRoot = resolvedRoot;
            this.bytes = bytes.clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public String releaseDirectoryName() {
            return resolvedRoot.directoryName();
        }

        Path releaseRoot() {
            return resolvedRoot.realRoot();
        }

    }

    /** Immutable Markdown source returned in the same order as the manifest declaration. */
    public static final class DocumentSource {

        private final String key;
        private final String source;
        private final byte[] bytes;

        private DocumentSource(String key, String source, byte[] bytes) {
            this.key = Objects.requireNonNull(key, "key");
            this.source = Objects.requireNonNull(source, "source");
            this.bytes = bytes.clone();
        }

        public String key() {
            return key;
        }

        public String source() {
            return source;
        }

        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** Immutable ordered collection of all Markdown bytes in one release. */
    public static final class ReleaseDocuments {

        private final List<DocumentSource> documents;
        private final long totalBytes;

        private ReleaseDocuments(List<DocumentSource> documents, long totalBytes) {
            this.documents = List.copyOf(documents);
            this.totalBytes = totalBytes;
        }

        public List<DocumentSource> documents() {
            return documents;
        }

        public long totalBytes() {
            return totalBytes;
        }
    }
}
