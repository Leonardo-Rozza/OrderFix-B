package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class LocalExportPackageWriterTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void privatePackageHasVerifiedFilesAndAnExplicitIncompleteManifest() throws Exception {
        var snapshot = snapshot();
        Path sentinel = Files.writeString(directory.resolve("unrelated.txt"), "preserve");
        Path result = new LocalExportPackageWriter().write(snapshot, directory);
        assertThat(Files.getPosixFilePermissions(result)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        var manifest = JSON.readTree(Files.readAllBytes(result.resolve("manifest.json")));
        assertThat(manifest.path("formato").asText()).isEqualTo("ordenfix-export/1");
        assertThat(manifest.path("fase").asText()).isEqualTo("DATOS_LOCALES");
        assertThat(manifest.path("exportacion_integral_completa").asBoolean()).isFalse();
        assertThat(manifest.path("taller_id").asText()).isEqualTo("9007199254740993");
        assertThat(manifest.path("archivos").size()).isEqualTo(snapshot.files().size());
        assertThat(manifest.path("archivos_pendientes").size()).isEqualTo(1);
        assertThat(manifest.path("exclusiones").isEmpty()).isFalse();
        for (var entry : manifest.path("archivos")) {
            Path file = result.resolve(entry.path("ruta").asText());
            byte[] bytes = Files.readAllBytes(file);
            assertThat(bytes.length).isEqualTo(entry.path("bytes").asInt());
            assertThat(ExportFile.digest(bytes)).isEqualTo(entry.path("sha256").asText());
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
        assertThat(Files.getPosixFilePermissions(result.resolve("manifest.json"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.exists(result.resolve("manifest.json.part"))).isFalse();
        assertThat(Files.readString(sentinel)).isEqualTo("preserve");
        Path second = new LocalExportPackageWriter().write(snapshot, directory);
        assertThat(second).isNotEqualTo(result);
        assertThat(Files.readAllBytes(second.resolve("manifest.json"))).containsExactly(Files.readAllBytes(result.resolve("manifest.json")));
    }

    @Test void anIoFailureCannotLeaveACompletedManifestOrRemoveUnrelatedFiles() throws Exception {
        var sentinel = Files.writeString(directory.resolve("keep.txt"), "keep");
        AtomicInteger files = new AtomicInteger();
        var writer = new LocalExportPackageWriter((file, output) -> {
            file.writeTo(output);
            if (files.incrementAndGet() == 3) throw new IOException("synthetic private diagnostic");
        });
        assertThatThrownBy(() -> writer.write(snapshot(), directory)).isInstanceOf(ExportPackageException.class)
                .hasNoCause().hasMessageNotContaining("synthetic").hasMessageNotContaining(directory.toString());
        try (var children = Files.list(directory)) { assertThat(children.toList()).containsExactly(sentinel); }
    }

    @Test void silentPayloadCorruptionIsRejectedBeforeTheManifestAppears() throws Exception {
        var writer = new LocalExportPackageWriter((file, output) -> output.write(new byte[file.size()]));
        assertThatThrownBy(() -> writer.write(snapshot(), directory)).isInstanceOfSatisfying(ExportPackageException.class,
                failure -> assertThat(failure.code().name()).isEqualTo("INVALID_PACKAGE"));
        try (var children = Files.list(directory)) { assertThat(children.toList()).isEmpty(); }
    }

    @Test void anOmittedCategoryCannotBePresentedAsACompleteDatabaseCapture() throws Exception {
        var original = snapshot();
        var missing = new ExportSnapshot(original.actorId(), original.tallerId(), original.observedAt(),
                original.files().stream().filter(file -> !file.path().equals("datos/clientes.json")).toList(), original.pendingPhotos());
        assertThatThrownBy(() -> new LocalExportPackageWriter().write(missing, directory)).isInstanceOfSatisfying(ExportPackageException.class,
                failure -> assertThat(failure.code().name()).isEqualTo("INVALID_PACKAGE"));
        try (var children = Files.list(directory)) { assertThat(children.toList()).isEmpty(); }
    }

    @Test void sourceNamesCannotEscapeTheNewPrivateDirectory() {
        for (String path : List.of("../escape.json", "datos/../../escape.json", "/tmp/escape.json", "documentos/title.md", "datos/x.json/extra")) {
            assertThatThrownBy(() -> new ExportFile(path, "application/json", 0, new byte[0])).isInstanceOf(ExportPackageException.class);
        }
    }

    @Test void aSymlinkCannotRedirectTheConfiguredStagingRoot() throws Exception {
        Path target = Files.createDirectory(directory.resolve("actual"));
        Path link = Files.createSymbolicLink(directory.resolve("link"), target);
        assertThatThrownBy(() -> new LocalExportPackageWriter().write(snapshot(), link)).isInstanceOf(ExportPackageException.class);
        try (var children = Files.list(target)) { assertThat(children.toList()).isEmpty(); }
    }

    @Test void neitherInputNorOutputStreamsCanMutateTheStoredPayloadAndToStringOmitsData() throws Exception {
        byte[] content = "private customer value".getBytes(StandardCharsets.UTF_8);
        var file = new ExportFile("datos/clientes.json", "application/json", 1, content);
        String expected = file.sha256();
        Arrays.fill(content, (byte) 0);
        file.writeTo(new OutputStream() {
            @Override public void write(int value) { }
            @Override public void write(byte[] bytes) { Arrays.fill(bytes, (byte) 0); }
        });
        var output = new ByteArrayOutputStream(); file.writeTo(output);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("private customer value");
        assertThat(ExportFile.digest(output.toByteArray())).isEqualTo(expected);
        assertThat(file.toString()).doesNotContain("private customer value");
        assertThat(snapshot().toString()).doesNotContain("private customer value");
    }

    private static ExportSnapshot snapshot() {
        var files = new ArrayList<ExportFile>();
        for (var query : ExportBusinessCatalog.queries()) files.add(json("datos/" + query.category() + ".json"));
        for (String category : List.of("fotos_privadas", "fotos_legacy", "qr_cobro", "aceptaciones_propias")) files.add(json("datos/" + category + ".json"));
        files.add(new ExportFile("LEEME.txt", "text/plain", -1, "Preparación local; todavía incompleta".getBytes(StandardCharsets.UTF_8)));
        UUID photo = UUID.fromString("aa5c92c0-9465-4a2a-aabb-9669e2326957");
        return new ExportSnapshot(14, 9_007_199_254_740_993L, Instant.parse("2026-09-12T15:00:00Z"), files,
                List.of(new ExportSnapshot.PendingPhoto(photo, "archivos/fotos/" + photo + ".png", "a".repeat(64), 20)));
    }
    private static ExportFile json(String name) { return new ExportFile(name, "application/json", 0, "[]".getBytes(StandardCharsets.UTF_8)); }
}
