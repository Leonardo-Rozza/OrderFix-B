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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;

/** Reads one bounded editorial plan without following a symlink at the explicit file entry. */
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
            absolutePlan = planPath.toAbsolutePath();
        } catch (SecurityException
                 | ClosedFileSystemException
                 | FileSystemNotFoundException
                 | ProviderMismatchException
                 | UnsupportedOperationException
                 | IOError exception) {
            return error(LegalManifestIssueCode.EDITORIAL_PLAN_READ_ERROR);
        }

        try {
            BasicFileAttributes before = Files.readAttributes(
                    absolutePlan,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (before.isSymbolicLink()) {
                return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_SYMLINK_FORBIDDEN);
            }
            if (!before.isRegularFile()) {
                return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_NOT_REGULAR);
            }
            if (before.size() > LegalEditorialPlanLimits.MAX_PLAN_BYTES) {
                return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_SIZE_LIMIT_EXCEEDED);
            }

            readHook.beforeRead(absolutePlan);
            byte[] bytes = boundedRead(absolutePlan, LegalEditorialPlanLimits.MAX_PLAN_BYTES);
            if (bytes == null) {
                return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_SIZE_LIMIT_EXCEEDED);
            }
            readHook.afterRead(absolutePlan);

            BasicFileAttributes after = Files.readAttributes(
                    absolutePlan,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile()
                    || after.isSymbolicLink()
                    || !ConfinedReleaseReader.sameIdentity(before, after)) {
                return blocked(LegalManifestIssueCode.EDITORIAL_PLAN_FILE_CHANGED);
            }
            return LegalManifestValidation.pass(new EditorialPlanSource(bytes));
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

    private static byte[] boundedRead(Path path, long maxBytes) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(path, options);
             ByteArrayOutputStream output = new ByteArrayOutputStream(READ_BUFFER_BYTES)) {
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
