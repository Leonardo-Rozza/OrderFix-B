package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.IOError;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reads one bounded editorial plan through a secure directory handle without following symlinks.
 *
 * <p>Portable Java does not expose the file key attached to an already-open byte channel. This
 * reader therefore opens the leaf once, reads that same handle twice, and brackets the operation
 * with file-key/timestamp snapshots of every path component. Providers exposing
 * {@link SecureDirectoryStream} are traversed relative to open directory handles; other providers
 * use the same fail-closed snapshots around a path-based open. Observable ABA replacement fails
 * closed; an actor able to restore bytes and every filesystem timestamp between all observations
 * remains outside portable NIO guarantees.</p>
 */
public final class ConfinedEditorialPlanReader {

    public static final String PLAN_FILENAME = "editorial-plan.json";
    private static final int READ_BUFFER_BYTES = 8_192;

    private final ReadHook readHook;

    public ConfinedEditorialPlanReader() {
        this(ReadHook.NOOP);
    }

    ConfinedEditorialPlanReader(ReadHook readHook) {
        this.readHook = Objects.requireNonNull(readHook, "readHook");
    }

    /** Reads the exact file bytes after filename, type, size and stable-identity checks. */
    public LegalManifestValidation<EditorialPlanSource> read(Path planPath) {
        if (planPath == null) {
            return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_PATH_REQUIRED);
        }

        final Path absolutePlan;
        try {
            Path fileName = planPath.getFileName();
            if (fileName == null || !PLAN_FILENAME.equals(fileName.toString())) {
                return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_FILENAME_INVALID);
            }
            absolutePlan = planPath.toAbsolutePath().normalize();
        } catch (SecurityException
                 | ClosedFileSystemException
                 | FileSystemNotFoundException
                 | ProviderMismatchException
                 | UnsupportedOperationException
                 | IOError exception) {
            return error(LegalManifestIssueCode.EDITORIAL_PLAN_READ_ERROR);
        }

        try {
            Path root = absolutePlan.getRoot();
            if (root == null || absolutePlan.getNameCount() == 0) {
                return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_NOT_REGULAR);
            }
            try (var rootStream = Files.newDirectoryStream(root)) {
                if (rootStream instanceof SecureDirectoryStream<?>) {
                    @SuppressWarnings("unchecked")
                    SecureDirectoryStream<Path> secureRoot =
                            (SecureDirectoryStream<Path>) rootStream;
                    return readSecurely(secureRoot, absolutePlan, 0);
                }
                return readWithPortableSnapshots(absolutePlan);
            }
        } catch (UnsafePathException exception) {
            return blocked(exception.code);
        } catch (NoSuchFileException | NotDirectoryException exception) {
            return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_NOT_REGULAR);
        } catch (AccessDeniedException exception) {
            return error(LegalManifestIssueCode.EDITORIAL_PLAN_READ_ERROR);
        } catch (SecurityException
                 | ClosedFileSystemException
                 | FileSystemNotFoundException
                 | ProviderMismatchException
                 | UnsupportedOperationException
                 | IOError exception) {
            return error(LegalManifestIssueCode.EDITORIAL_PLAN_READ_ERROR);
        } catch (IOException exception) {
            return error(LegalManifestIssueCode.EDITORIAL_PLAN_READ_ERROR);
        }
    }

    private LegalManifestValidation<EditorialPlanSource> readSecurely(
            SecureDirectoryStream<Path> parent,
            Path absolutePlan,
            int componentIndex) throws IOException, UnsafePathException {
        Path component = absolutePlan.getName(componentIndex);
        BasicFileAttributes parentBefore = readAttributes(parent);
        BasicFileAttributes entryBefore = readAttributes(parent, component);
        requireStableFileKey(parentBefore);
        requireStableFileKey(entryBefore);
        if (entryBefore.isSymbolicLink()) {
            throw new UnsafePathException(
                    LegalManifestIssueCode.EDITORIAL_PLAN_SYMLINK_FORBIDDEN);
        }

        boolean leaf = componentIndex == absolutePlan.getNameCount() - 1;
        if (leaf) {
            if (!entryBefore.isRegularFile()) {
                throw new UnsafePathException(
                        LegalManifestIssueCode.EDITORIAL_PLAN_NOT_REGULAR);
            }
            return readLeaf(parent, component, absolutePlan, parentBefore, entryBefore);
        }
        if (!entryBefore.isDirectory()) {
            throw new UnsafePathException(LegalManifestIssueCode.EDITORIAL_PLAN_NOT_REGULAR);
        }

        final LegalManifestValidation<EditorialPlanSource> result;
        try (SecureDirectoryStream<Path> child = parent.newDirectoryStream(
                component,
                LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes childBefore = readAttributes(child);
            requireStableFileKey(childBefore);
            if (!sameIdentity(entryBefore, childBefore)) {
                return changed();
            }

            result = readSecurely(child, absolutePlan, componentIndex + 1);
            if (!result.passed()) {
                return result;
            }

            BasicFileAttributes entryAfter = readAttributes(parent, component);
            BasicFileAttributes parentAfter = readAttributes(parent);
            BasicFileAttributes childAfter = readAttributes(child);
            if (!sameIdentity(entryBefore, entryAfter)
                    || !sameIdentity(parentBefore, parentAfter)
                    || !sameIdentity(childBefore, childAfter)) {
                return changed();
            }
        }
        return result;
    }

    private LegalManifestValidation<EditorialPlanSource> readWithPortableSnapshots(
            Path absolutePlan) throws IOException, UnsafePathException {
        PathSnapshot before = snapshotPath(absolutePlan);
        BasicFileAttributes leafBefore = before.leaf();
        if (leafBefore.size() > LegalEditorialPlanLimits.MAX_PLAN_BYTES) {
            return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_SIZE_LIMIT_EXCEEDED);
        }

        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(absolutePlan, options)) {
            long channelSizeBefore = channel.size();
            PathSnapshot afterOpen = snapshotPath(absolutePlan);
            if (channelSizeBefore != leafBefore.size() || !before.sameAs(afterOpen)) {
                return changed();
            }

            readHook.beforeRead(absolutePlan);
            byte[] bytes = boundedRead(channel, LegalEditorialPlanLimits.MAX_PLAN_BYTES);
            if (bytes == null) {
                return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_SIZE_LIMIT_EXCEEDED);
            }
            readHook.afterRead(absolutePlan);

            channel.position(0L);
            byte[] verification = boundedRead(channel, LegalEditorialPlanLimits.MAX_PLAN_BYTES);
            long channelSizeAfter = channel.size();
            PathSnapshot afterRead;
            try {
                afterRead = snapshotPath(absolutePlan);
            } catch (NoSuchFileException
                     | NotDirectoryException
                     | UnsafePathException exception) {
                return changed();
            }
            if (verification == null
                    || channelSizeBefore != channelSizeAfter
                    || channelSizeAfter != bytes.length
                    || !Arrays.equals(bytes, verification)
                    || !before.sameAs(afterRead)) {
                return changed();
            }
            return LegalManifestValidation.pass(new EditorialPlanSource(bytes));
        }
    }

    private LegalManifestValidation<EditorialPlanSource> readLeaf(
            SecureDirectoryStream<Path> parent,
            Path leaf,
            Path absolutePlan,
            BasicFileAttributes parentBefore,
            BasicFileAttributes entryBefore) throws IOException {
        if (entryBefore.size() > LegalEditorialPlanLimits.MAX_PLAN_BYTES) {
            return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_SIZE_LIMIT_EXCEEDED);
        }

        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = parent.newByteChannel(leaf, options)) {
            long channelSizeBefore = channel.size();
            BasicFileAttributes entryAfterOpen = readAttributes(parent, leaf);
            if (channelSizeBefore != entryBefore.size()
                    || !sameIdentity(entryBefore, entryAfterOpen)) {
                return changed();
            }

            readHook.beforeRead(absolutePlan);
            byte[] bytes = boundedRead(channel, LegalEditorialPlanLimits.MAX_PLAN_BYTES);
            if (bytes == null) {
                return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_SIZE_LIMIT_EXCEEDED);
            }
            readHook.afterRead(absolutePlan);

            channel.position(0L);
            byte[] verification = boundedRead(channel, LegalEditorialPlanLimits.MAX_PLAN_BYTES);
            long channelSizeAfter = channel.size();
            BasicFileAttributes entryAfter = readAttributes(parent, leaf);
            BasicFileAttributes parentAfter = readAttributes(parent);
            if (verification == null
                    || channelSizeBefore != channelSizeAfter
                    || channelSizeAfter != bytes.length
                    || !Arrays.equals(bytes, verification)
                    || !sameIdentity(entryBefore, entryAfter)
                    || !sameIdentity(parentBefore, parentAfter)) {
                return changed();
            }
            return LegalManifestValidation.pass(new EditorialPlanSource(bytes));
        }
    }

    private static byte[] boundedRead(SeekableByteChannel channel, long maxBytes)
            throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(READ_BUFFER_BYTES)) {
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

    private static BasicFileAttributes readAttributes(
            SecureDirectoryStream<Path> directory) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(BasicFileAttributeView.class);
        if (view == null) {
            throw new IOException("El proveedor no expone atributos del directorio abierto");
        }
        return view.readAttributes();
    }

    private static PathSnapshot snapshotPath(Path absolutePlan)
            throws IOException, UnsafePathException {
        Path root = absolutePlan.getRoot();
        if (root == null) {
            throw new UnsafePathException(LegalManifestIssueCode.EDITORIAL_PLAN_NOT_REGULAR);
        }

        List<BasicFileAttributes> attributes = new ArrayList<>(absolutePlan.getNameCount() + 1);
        Path current = root;
        BasicFileAttributes rootAttributes = Files.readAttributes(
                current,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        requireStableFileKey(rootAttributes);
        attributes.add(rootAttributes);

        int index = 0;
        for (Path component : absolutePlan) {
            current = current.resolve(component);
            BasicFileAttributes entry = Files.readAttributes(
                    current,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            requireStableFileKey(entry);
            if (entry.isSymbolicLink()) {
                throw new UnsafePathException(
                        LegalManifestIssueCode.EDITORIAL_PLAN_SYMLINK_FORBIDDEN);
            }
            boolean leaf = index == absolutePlan.getNameCount() - 1;
            if ((!leaf && !entry.isDirectory()) || (leaf && !entry.isRegularFile())) {
                throw new UnsafePathException(
                        LegalManifestIssueCode.EDITORIAL_PLAN_NOT_REGULAR);
            }
            attributes.add(entry);
            index++;
        }
        return new PathSnapshot(attributes);
    }

    private static BasicFileAttributes readAttributes(
            SecureDirectoryStream<Path> directory,
            Path entry) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(
                entry,
                BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("El proveedor no expone atributos seguros de la entrada");
        }
        return view.readAttributes();
    }

    private static void requireStableFileKey(BasicFileAttributes attributes) throws IOException {
        if (attributes.fileKey() == null) {
            throw new IOException("El proveedor no expone una identidad estable de archivo");
        }
    }

    private static boolean sameIdentity(
            BasicFileAttributes before,
            BasicFileAttributes after) {
        return before.fileKey() != null
                && before.fileKey().equals(after.fileKey())
                && before.isDirectory() == after.isDirectory()
                && before.isRegularFile() == after.isRegularFile()
                && before.isSymbolicLink() == after.isSymbolicLink()
                && before.size() == after.size()
                && before.creationTime().equals(after.creationTime())
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static <T> LegalManifestValidation<T> changed() {
        return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_FILE_CHANGED);
    }

    private static <T> LegalManifestValidation<T> blocked(LegalManifestIssueCode code) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(code, PLAN_FILENAME));
    }

    private static <T> LegalManifestValidation<T> error(LegalManifestIssueCode code) {
        return LegalManifestValidation.failure(LegalManifestIssue.at(code, PLAN_FILENAME));
    }

    @FunctionalInterface
    interface ReadHook {
        ReadHook NOOP = path -> { };

        default void beforeRead(Path path) throws IOException {
        }

        void afterRead(Path path) throws IOException;
    }

    private static final class UnsafePathException extends Exception {

        private final LegalManifestIssueCode code;

        private UnsafePathException(LegalManifestIssueCode code) {
            this.code = Objects.requireNonNull(code, "code");
        }
    }

    private record PathSnapshot(List<BasicFileAttributes> entries) {

        private PathSnapshot {
            entries = List.copyOf(entries);
        }

        private BasicFileAttributes leaf() {
            return entries.getLast();
        }

        private boolean sameAs(PathSnapshot other) {
            if (entries.size() != other.entries.size()) {
                return false;
            }
            for (int index = 0; index < entries.size(); index++) {
                if (!sameIdentity(entries.get(index), other.entries.get(index))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Defensive copy of the exact bounded bytes read from the explicit plan file. */
    public static final class EditorialPlanSource {

        private final byte[] bytes;

        private EditorialPlanSource(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
