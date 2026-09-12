package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** An immutable, generated-name payload; never serialize this class as an HTTP response. */
public final class ExportFile {
    private final String path;
    private final String mediaType;
    private final long records;
    private final byte[] content;
    private final String sha256;
    ExportFile(String path, String mediaType, long records, byte[] content) {
        if (path == null || !path.matches("(?:datos/[a-z_]+\\.json|documentos/[0-9a-f-]{36}\\.md|archivos/qr-cobro\\.png|LEEME\\.txt)") || records < -1) {
            throw new ExportPackageException(ExportPackageException.Code.INVALID_PACKAGE);
        }
        this.path = path;
        this.mediaType = Objects.requireNonNull(mediaType);
        this.records = records;
        this.content = Arrays.copyOf(content, content.length);
        this.sha256 = digest(this.content);
    }
    public String path() { return path; }
    public String mediaType() { return mediaType; }
    public long records() { return records; }
    public int size() { return content.length; }
    public String sha256() { return sha256; }
    public void writeTo(OutputStream output) throws IOException { output.write(content.clone()); }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new ExportPackageException(ExportPackageException.Code.UNAVAILABLE); }
    }
    @Override public String toString() { return "ExportFile[path=" + path + ", bytes=" + content.length + "]"; }
}
