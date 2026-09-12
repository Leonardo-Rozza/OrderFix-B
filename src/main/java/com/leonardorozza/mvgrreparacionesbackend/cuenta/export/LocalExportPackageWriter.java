package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.core.JsonFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPackageException.Code.*;

/** Private staging directory only. No URL, download authorization, encryption or durable job is created here. */
public final class LocalExportPackageWriter {
    private static final JsonFactory JSON = new JsonFactory();
    private static final String[] EXCLUSIONS = {
            "Contrasenas, PIN/patron y material de cifrado", "Tokens y codigo de seguimiento",
            "Aceptaciones personales de otros usuarios", "IP/UA y ledgers tecnicos",
            "URLs legacy de origen no acreditado", "Claves, assets, leases y diagnosticos de proveedores",
            "Binarios remotos: incorporacion y validacion pendientes del corte C"
    };
    private final PayloadWriter writer;
    public LocalExportPackageWriter() { this(ExportFile::writeTo); }
    LocalExportPackageWriter(PayloadWriter writer) { this.writer = Objects.requireNonNull(writer); }
    @FunctionalInterface interface PayloadWriter { void write(ExportFile file, OutputStream output) throws IOException; }

    public Path write(ExportSnapshot snapshot, Path root) {
        Objects.requireNonNull(snapshot);
        Path directory = null;
        try {
            validate(snapshot);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new ExportPackageException(INVALID_PACKAGE);
            directory = Files.createTempDirectory(root, "ordenfix-export-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            for (String name : List.of("datos", "documentos", "archivos")) {
                Files.createDirectory(directory.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
            for (ExportFile file : snapshot.files()) {
                Path target = directory.resolve(file.path());
                createPrivateFile(target);
                try (var output = Files.newOutputStream(target, StandardOpenOption.WRITE)) { writer.write(file, output); }
                if (Files.size(target) != file.size() || !digest(target).equals(file.sha256())) throw new ExportPackageException(INVALID_PACKAGE);
            }
            byte[] manifest = manifest(snapshot);
            Path temporary = directory.resolve("manifest.json.part");
            createPrivateFile(temporary);
            Files.write(temporary, manifest, StandardOpenOption.WRITE);
            Files.move(temporary, directory.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE);
            return directory;
        } catch (IOException | RuntimeException failure) {
            if (directory != null) cleanup(directory);
            if (failure instanceof ExportPackageException known) throw known;
            throw new ExportPackageException(UNAVAILABLE);
        }
    }

    private static void validate(ExportSnapshot snapshot) {
        if (snapshot.actorId() <= 0 || snapshot.tallerId() <= 0 || snapshot.observedAt() == null) throw new ExportPackageException(INVALID_PACKAGE);
        Set<String> paths = new HashSet<>(); long bytes = 0;
        for (ExportFile file : snapshot.files()) {
            if (!paths.add(file.path())) throw new ExportPackageException(INVALID_PACKAGE);
            bytes += file.size();
        }
        if (bytes > WorkshopExportSnapshotService.MAX_BYTES) throw new ExportPackageException(CAPACITY_EXCEEDED);
        Set<String> required = new HashSet<>(List.of("LEEME.txt", "datos/fotos_privadas.json", "datos/fotos_legacy.json", "datos/qr_cobro.json", "datos/aceptaciones_propias.json"));
        for (ExportQuery query : ExportBusinessCatalog.queries()) required.add("datos/" + query.category() + ".json");
        if (!paths.containsAll(required)) throw new ExportPackageException(INVALID_PACKAGE);
        Set<UUID> photos = new HashSet<>();
        for (var photo : snapshot.pendingPhotos()) {
            if (photo.id() == null || !photos.add(photo.id()) || photo.path() == null
                    || !(photo.path().equals("archivos/fotos/" + photo.id() + ".png") || photo.path().equals("archivos/fotos/" + photo.id() + ".jpg"))
                    || photo.sha256() == null || !photo.sha256().matches("[0-9a-f]{64}") || photo.bytes() < 12 || photo.bytes() > 8_000_000) {
                throw new ExportPackageException(INVALID_PACKAGE);
            }
        }
    }

    private static byte[] manifest(ExportSnapshot snapshot) throws IOException {
        var output = new ExportBuffer(8 * 1024 * 1024);
        try (var json = JSON.createGenerator(output)) {
            json.writeStartObject();
            json.writeStringField("formato", "ordenfix-export/1");
            json.writeStringField("fase", "DATOS_LOCALES");
            json.writeBooleanField("exportacion_integral_completa", false);
            json.writeStringField("observado_en", snapshot.observedAt().toString());
            json.writeStringField("taller_id", Long.toString(snapshot.tallerId()));
            json.writeStringField("solicitante_id", Long.toString(snapshot.actorId()));
            json.writeArrayFieldStart("archivos");
            for (var file : snapshot.files()) {
                json.writeStartObject(); json.writeStringField("ruta", file.path());
                json.writeStringField("tipo", file.mediaType()); json.writeNumberField("bytes", file.size());
                json.writeStringField("sha256", file.sha256());
                if (file.records() >= 0) json.writeNumberField("registros", file.records());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeArrayFieldStart("archivos_pendientes");
            for (var photo : snapshot.pendingPhotos()) {
                json.writeStartObject(); json.writeStringField("foto_id", photo.id().toString());
                json.writeStringField("ruta", photo.path()); json.writeNumberField("bytes", photo.bytes());
                json.writeStringField("sha256", photo.sha256()); json.writeStringField("estado", "PENDIENTE_C"); json.writeEndObject();
            }
            json.writeEndArray(); json.writeArrayFieldStart("exclusiones");
            for (var exclusion : EXCLUSIONS) json.writeString(exclusion);
            json.writeEndArray(); json.writeEndObject();
        }
        return output.toByteArray();
    }

    private static void createPrivateFile(Path file) throws IOException {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
    private static String digest(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException failure) { throw new ExportPackageException(UNAVAILABLE); }
    }
    private static void cleanup(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve("manifest.json"));
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            // C must reconcile abandoned staging directories. Never signal success on cleanup failure.
            throw new ExportPackageException(UNAVAILABLE);
        }
    }
}
