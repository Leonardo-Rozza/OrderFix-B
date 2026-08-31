package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Test-only Java agent used to inject deterministic stdout failures. */
public final class LegalCliStdoutFailureAgent extends OutputStream {

    private static final String FAILURE_MESSAGE =
            "stdout failure injected by test agent";

    private final OutputStream descriptor;
    private int remaining;
    private boolean failed;

    private LegalCliStdoutFailureAgent(OutputStream descriptor, int remaining) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.remaining = remaining;
    }

    public static void premain(String arguments) {
        int prefixBytes = parsePrefixBytes(arguments);
        OutputStream realStdout = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(
                new LegalCliStdoutFailureAgent(realStdout, prefixBytes),
                true,
                StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void write(int value) throws IOException {
        requireWritable();
        if (remaining == 0) {
            throw transitionToFailure();
        }
        descriptor.write(value);
        remaining--;
        if (remaining == 0) {
            throw transitionToFailure();
        }
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length)
            throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        requireWritable();
        if (length == 0) {
            return;
        }
        if (remaining == 0) {
            throw transitionToFailure();
        }

        int forwarded = Math.min(remaining, length);
        descriptor.write(bytes, offset, forwarded);
        remaining -= forwarded;
        if (remaining == 0) {
            throw transitionToFailure();
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        requireWritable();
        descriptor.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (failed) {
            return;
        }
        failed = true;
        remaining = 0;
        descriptor.close();
    }

    private void requireWritable() throws IOException {
        if (failed) {
            throw new IOException(FAILURE_MESSAGE);
        }
    }

    private IOException transitionToFailure() {
        failed = true;
        remaining = 0;
        IOException injected = new IOException(FAILURE_MESSAGE);
        try {
            descriptor.close();
        } catch (IOException closeFailure) {
            injected.addSuppressed(closeFailure);
        }
        return injected;
    }

    private static int parsePrefixBytes(String arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("prefixBytes es obligatorio");
        }
        try {
            int parsed = Integer.parseInt(arguments);
            if (parsed < 0) {
                throw new IllegalArgumentException(
                        "prefixBytes no puede ser negativo");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "prefixBytes debe ser un entero decimal",
                    failure);
        }
    }
}
