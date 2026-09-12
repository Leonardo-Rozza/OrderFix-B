package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;

class ExportArtifactCodecTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID JOB = UUID.fromString("6f7c3a59-357d-47ea-9bb8-606771cb23f0");
    private static final UUID PHOTO = UUID.fromString("94b802ed-6e74-4eac-ad14-e9d8cdd513df");
    private static final UUID DOCUMENT = UUID.fromString("f8444895-4556-4c55-86b3-01dc6f27cfc7");
    private static final UUID UNAVAILABLE_PHOTO = UUID.fromString("3bb3e6df-9946-4e15-a450-fb6d0311634e");
    private static final long TALLER = 9_007_199_254_740_993L;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0, 1, 2, 3, 4};
    private static final ExportArtifactCodec.Context CONTEXT = new ExportArtifactCodec.Context(JOB, TALLER, 14);
    private static final String KEY = key(27), OTHER_KEY = key(48);

    @Test void encryptedSnapshotRoundTripsExactPayloadsAndPendingMetadata() throws Exception {
        var codec = codec(); var snapshot = snapshot(true);
        byte[] encrypted = codec.encryptSnapshot(CONTEXT, snapshot);
        assertThat(new String(encrypted, StandardCharsets.ISO_8859_1)).doesNotContain("private customer canary").doesNotContain("documentos/");
        var recovered = codec.decryptSnapshot(CONTEXT, encrypted);
        assertThat(recovered.actorId()).isEqualTo(snapshot.actorId());
        assertThat(recovered.tallerId()).isEqualTo(snapshot.tallerId());
        assertThat(recovered.observedAt()).isEqualTo(snapshot.observedAt());
        assertThat(recovered.pendingPhotos()).containsExactlyElementsOf(snapshot.pendingPhotos());
        assertThat(recovered.files()).hasSameSizeAs(snapshot.files());
        for (int index = 0; index < snapshot.files().size(); index++) {
            var expected = snapshot.files().get(index); var actual = recovered.files().get(index);
            assertThat(actual.path()).isEqualTo(expected.path()); assertThat(actual.mediaType()).isEqualTo(expected.mediaType());
            assertThat(actual.records()).isEqualTo(expected.records()); assertThat(actual.sha256()).isEqualTo(expected.sha256());
            assertThat(bytes(actual)).containsExactly(bytes(expected));
        }
    }

    @Test void finalZipHasEveryVerifiedEntryAndExplicitExclusionsWithoutChangingTheDurableSnapshot() throws Exception {
        var codec = codec(); var snapshot = snapshot(true);
        byte[] original = codec.encryptSnapshot(CONTEXT, snapshot);
        byte[] archive = codec.archive(CONTEXT, snapshot, List.of(new ExportArtifactCodec.PhotoFile(PHOTO, PNG)));
        var entries = unzip(codec.decryptArchive(CONTEXT, archive));
        var manifest = JSON.readTree(entries.get("manifest.json"));
        assertThat(manifest.path("fase").asText()).isEqualTo("PAQUETE_COMPLETO");
        assertThat(manifest.path("exportacion_integral_completa").asBoolean()).isTrue();
        assertThat(manifest.path("archivos_pendientes").isEmpty()).isTrue();
        assertThat(manifest.path("taller_id").asText()).isEqualTo(Long.toString(TALLER));
        assertThat(manifest.path("archivos").size()).isEqualTo(snapshot.files().size() + 1);
        Set<String> paths = new HashSet<>();
        for (var file : manifest.path("archivos")) {
            String path = file.path("ruta").asText(); paths.add(path);
            assertThat(entries).containsKey(path);
            assertThat(entries.get(path).length).isEqualTo(file.path("bytes").asInt());
            assertThat(ExportFile.digest(entries.get(path))).isEqualTo(file.path("sha256").asText());
        }
        paths.add("manifest.json"); assertThat(entries.keySet()).isEqualTo(paths);
        assertThat(entries.get("archivos/fotos/" + PHOTO + ".png")).containsExactly(PNG);
        assertThat(entries.get("documentos/" + DOCUMENT + ".md")).containsExactly("# Privacidad\n\nTexto exacto: áé 🔧\n".getBytes(StandardCharsets.UTF_8));
        assertThat(entries.get("archivos/qr-cobro.png")).containsExactly(PNG);
        assertThat(new String(entries.get("LEEME.txt"), StandardCharsets.UTF_8)).contains("No hay archivos pendientes").doesNotContain("PENDIENTE_C");
        var photoRows = JSON.readTree(entries.get("datos/fotos_privadas.json"));
        assertThat(photoRows.get(0).path("archivo_estado").asText()).isEqualTo("INCLUIDA");
        assertThat(photoRows.get(1).path("archivo_estado").asText()).isEqualTo("NO_DISPONIBLE_EN_SNAPSHOT");
        assertThat(manifest.path("exclusiones").toString()).contains("legacy").contains("PIN").doesNotContain("pendientes del corte C");
        assertThat(JSON.readTree(bytes(file(snapshot, "datos/fotos_privadas.json"))).get(0).path("archivo_estado").asText()).isEqualTo("PENDIENTE_C");
        assertThat(bytes(file(codec.decryptSnapshot(CONTEXT, original), "LEEME.txt"))).containsExactly("Preparación B incompleta".getBytes(StandardCharsets.UTF_8));
    }

    @Test void emptyPhotoSetStillProducesACompleteScopedArchiveAndGeneratedStableZipBytes() throws Exception {
        var codec = codec(); var snapshot = snapshot(false);
        byte[] first = codec.archive(CONTEXT, snapshot, List.of()), second = codec.archive(CONTEXT, snapshot, List.of());
        assertThat(first).isNotEqualTo(second);
        byte[] clear = codec.decryptArchive(CONTEXT, first);
        assertThat(codec.decryptArchive(CONTEXT, second)).containsExactly(clear);
        assertThat(unzip(clear).keySet()).doesNotContain("archivos/fotos/" + PHOTO + ".png");
    }

    @Test void ciphertextUsesDifferentNoncesAndRotationPreservesOldArtifactsOnlyWhileTheirKeyRemains() {
        var old = codec(); var snapshot = snapshot(false);
        byte[] first = old.encryptSnapshot(CONTEXT, snapshot), second = old.encryptSnapshot(CONTEXT, snapshot);
        assertThat(Arrays.copyOfRange(first, 13, 25)).isNotEqualTo(Arrays.copyOfRange(second, 13, 25));
        var rotated = new ExportArtifactCodec(Map.of(1, KEY, 2, OTHER_KEY), 2);
        assertThat(rotated.decryptSnapshot(CONTEXT, first).actorId()).isEqualTo(14);
        byte[] current = rotated.encryptSnapshot(CONTEXT, snapshot);
        assertThatThrownBy(() -> old.decryptSnapshot(CONTEXT, current)).isInstanceOf(ExportPackageException.class).hasNoCause();
        assertThatThrownBy(() -> new ExportArtifactCodec(Map.of(2, OTHER_KEY), 2).decryptSnapshot(CONTEXT, first)).isInstanceOf(ExportPackageException.class).hasNoCause();
    }

    @Test void contextAndArtifactKindCannotBeSubstituted() {
        var codec = codec(); byte[] encrypted = codec.encryptSnapshot(CONTEXT, snapshot(false));
        for (var context : List.of(new ExportArtifactCodec.Context(UUID.randomUUID(), TALLER, 14),
                new ExportArtifactCodec.Context(JOB, TALLER + 1, 14), new ExportArtifactCodec.Context(JOB, TALLER, 15))) {
            assertThatThrownBy(() -> codec.decryptSnapshot(context, encrypted)).isInstanceOf(ExportPackageException.class).hasNoCause();
        }
        assertThatThrownBy(() -> codec.decryptArchive(CONTEXT, encrypted)).isInstanceOf(ExportPackageException.class).hasNoCause();
        assertThatThrownBy(() -> codec.decryptSnapshot(CONTEXT, codec.archive(CONTEXT, snapshot(false), List.of()))).isInstanceOf(ExportPackageException.class).hasNoCause();
        assertThatThrownBy(() -> codec.encryptSnapshot(new ExportArtifactCodec.Context(JOB, TALLER, 15), snapshot(false))).isInstanceOf(ExportPackageException.class);
    }

    @ParameterizedTest @ValueSource(ints = {0, 8, 12, 13, 25, 28, 29, -1})
    void changingAnyAuthenticatedHeaderPayloadOrTagFailsClosed(int position) {
        var codec = codec(); byte[] encrypted = codec.encryptSnapshot(CONTEXT, snapshot(false));
        encrypted[position == -1 ? encrypted.length - 1 : position] ^= 1;
        assertThatThrownBy(() -> codec.decryptSnapshot(CONTEXT, encrypted)).isInstanceOfSatisfying(ExportPackageException.class,
                failure -> assertThat(failure.code()).isEqualTo(ExportPackageException.Code.INVALID_PACKAGE)).hasNoCause();
    }

    @Test void truncationAppendedBytesAndWrongKeyCannotReturnAnyPlaintext() {
        var codec = codec(); byte[] encrypted = codec.archive(CONTEXT, snapshot(false), List.of());
        for (int length : List.of(0, 28, encrypted.length - 1, encrypted.length + 1)) {
            byte[] modified = Arrays.copyOf(encrypted, length);
            assertThatThrownBy(() -> codec.decryptArchive(CONTEXT, modified)).isInstanceOf(ExportPackageException.class).hasNoCause();
        }
        assertThatThrownBy(() -> new ExportArtifactCodec(Map.of(1, OTHER_KEY), 1).decryptArchive(CONTEXT, encrypted))
                .isInstanceOf(ExportPackageException.class).hasNoCause();
    }

    @Test void keyConfigurationRejectsMissingMalformedDuplicateAndInactiveKeysWithoutDisclosure() {
        List<Map<Integer, String>> invalid = List.of(Map.of(), Map.of(0, KEY), Map.of(2, KEY), Map.of(1, "private malformed key"),
                Map.of(1, KEY + "\n"), Map.of(1, KEY, 2, KEY), Map.of(1, Base64.getEncoder().encodeToString(new byte[16])));
        for (var keys : invalid) {
            assertThatThrownBy(() -> new ExportArtifactCodec(keys, 1)).isInstanceOf(IllegalArgumentException.class).hasNoCause()
                    .hasMessageNotContaining(KEY).hasMessageNotContaining("private malformed key");
        }
        assertThat(codec().toString()).doesNotContain(KEY).contains("REDACTED");
        assertThatThrownBy(() -> new ExportArtifactCodec(null, 1)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test void randomSourceFailureDoesNotExposeProviderDiagnostics() {
        var random = new java.security.SecureRandom() { @Override public void nextBytes(byte[] bytes) { throw new IllegalStateException("private entropy diagnostic"); } };
        var codec = new ExportArtifactCodec(Map.of(1, KEY), 1, random);
        assertThatThrownBy(() -> codec.encryptSnapshot(CONTEXT, snapshot(false))).isInstanceOf(ExportPackageException.class)
                .hasNoCause().hasMessageNotContaining("private entropy diagnostic");
    }

    @Test void generatedSnapshotPathsAndCompleteCategoriesAreRequired() {
        var original = snapshot(false);
        var missing = new ExportSnapshot(14, TALLER, original.observedAt(), original.files().stream().filter(f -> !f.path().equals("datos/clientes.json")).toList(), List.of());
        assertThatThrownBy(() -> codec().encryptSnapshot(CONTEXT, missing)).isInstanceOf(ExportPackageException.class);
        var extra = new ArrayList<>(original.files()); extra.add(new ExportFile("datos/secrets.json", "application/json", 0, "[]".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> codec().encryptSnapshot(CONTEXT, new ExportSnapshot(14, TALLER, original.observedAt(), extra, List.of()))).isInstanceOf(ExportPackageException.class);
        var invalidDocument = new ArrayList<>(original.files()); invalidDocument.add(new ExportFile("documentos/------------------------------------.md", "text/markdown", -1, new byte[0]));
        assertThatThrownBy(() -> codec().encryptSnapshot(CONTEXT, new ExportSnapshot(14, TALLER, original.observedAt(), invalidDocument, List.of()))).isInstanceOf(ExportPackageException.class);
    }

    @Test void binarySnapshotFramingRejectsTrailingAndTruncatedRecords() {
        byte[] encoded = ExportArtifactSnapshotCodec.encode(snapshot(true));
        assertThatThrownBy(() -> ExportArtifactSnapshotCodec.decode(Arrays.copyOf(encoded, encoded.length - 1))).isInstanceOf(ExportPackageException.class).hasNoCause();
        assertThatThrownBy(() -> ExportArtifactSnapshotCodec.decode(Arrays.copyOf(encoded, encoded.length + 1))).isInstanceOf(ExportPackageException.class).hasNoCause();
    }

    @Test void cumulativePhotoCapacityFailsBeforeAllocatingOrRequestingRemotePayloads() {
        var original = snapshot(false); var photos = new ArrayList<ExportSnapshot.PendingPhoto>();
        for (int index = 0; index < 9; index++) {
            UUID id = new UUID(1, index + 1); photos.add(new ExportSnapshot.PendingPhoto(id, "archivos/fotos/" + id + ".png", "a".repeat(64), 8_000_000));
        }
        var large = new ExportSnapshot(14, TALLER, original.observedAt(), original.files(), photos);
        assertThatThrownBy(() -> codec().encryptSnapshot(CONTEXT, large)).isInstanceOfSatisfying(ExportPackageException.class,
                failure -> assertThat(failure.code()).isEqualTo(ExportPackageException.Code.CAPACITY_EXCEEDED));
    }

    @Test void photoObjectsDefensivelyCopyCallerBytesAndRedactTheirContent() {
        byte[] caller = PNG.clone(); var photo = new ExportArtifactCodec.PhotoFile(PHOTO, caller);
        Arrays.fill(caller, (byte) 0); byte[] copy = photo.content(); Arrays.fill(copy, (byte) 0);
        assertThat(photo.content()).containsExactly(PNG);
        assertThat(photo.toString()).doesNotContain(PHOTO.toString()).doesNotContain(Arrays.toString(PNG));
    }

    @Test void missingExtraDuplicateWrongAndCorruptedPhotoPayloadsPreventACompleteArchive() {
        var codec = codec(); var snapshot = snapshot(true); var actual = new ExportArtifactCodec.PhotoFile(PHOTO, PNG);
        byte[] corrupted = PNG.clone(); corrupted[12] ^= 1;
        List<List<ExportArtifactCodec.PhotoFile>> invalid = List.of(List.of(), List.of(actual, actual),
                List.of(new ExportArtifactCodec.PhotoFile(UUID.randomUUID(), PNG)), List.of(new ExportArtifactCodec.PhotoFile(PHOTO, corrupted)),
                List.of(new ExportArtifactCodec.PhotoFile(PHOTO, Arrays.copyOf(PNG, PNG.length + 1))));
        for (var photos : invalid) assertThatThrownBy(() -> codec.archive(CONTEXT, snapshot, photos)).isInstanceOf(ExportPackageException.class).hasNoCause();
        assertThatThrownBy(() -> codec.archive(CONTEXT, snapshot(false), List.of(actual))).isInstanceOf(ExportPackageException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"taller_id", "sha256", "bytes", "mime_type", "archivo_estado", "estado", "reparacion_id", "id"})
    void pendingPhotoMetadataMustMatchTheExactAuthorizedBinaryAndTenant(String field) throws Exception {
        var original = snapshot(true);
        ArrayNode rows = (ArrayNode) JSON.readTree(bytes(file(original, "datos/fotos_privadas.json")));
        ((ObjectNode) rows.get(0)).put(field, field.equals("id") ? UUID.randomUUID().toString() : "incorrect");
        var modified = replace(original, new ExportFile("datos/fotos_privadas.json", "application/json", 2, JSON.writeValueAsBytes(rows)));
        // An arbitrary non-null repair string is not enough to disprove B's already-accredited parent identity;
        // missing/null is the invalid condition handled by the archive metadata check.
        if (field.equals("reparacion_id")) {
            ((ObjectNode) rows.get(0)).putNull(field);
            modified = replace(original, new ExportFile("datos/fotos_privadas.json", "application/json", 2, JSON.writeValueAsBytes(rows)));
        }
        var snapshot = modified;
        assertThatThrownBy(() -> codec().archive(CONTEXT, snapshot, List.of(new ExportArtifactCodec.PhotoFile(PHOTO, PNG))))
                .isInstanceOf(ExportPackageException.class).hasNoCause();
    }

    @Test void anExpectedDigestAloneCannotTurnAnArbitraryBinaryIntoAnImage() throws Exception {
        var original = snapshot(true); byte[] invalidImage = new byte[PNG.length]; String sha = ExportFile.digest(invalidImage);
        ArrayNode rows = (ArrayNode) JSON.readTree(bytes(file(original, "datos/fotos_privadas.json"))); ((ObjectNode) rows.get(0)).put("sha256", sha);
        var changed = replace(original, new ExportFile("datos/fotos_privadas.json", "application/json", 2, JSON.writeValueAsBytes(rows)));
        var snapshot = new ExportSnapshot(14, TALLER, original.observedAt(), changed.files(), List.of(new ExportSnapshot.PendingPhoto(PHOTO, "archivos/fotos/" + PHOTO + ".png", sha, invalidImage.length)));
        assertThatThrownBy(() -> codec().archive(CONTEXT, snapshot, List.of(new ExportArtifactCodec.PhotoFile(PHOTO, invalidImage)))).isInstanceOf(ExportPackageException.class);
    }

    private static ExportArtifactCodec codec() { return new ExportArtifactCodec(Map.of(1, KEY), 1); }
    private static String key(int value) { byte[] bytes = new byte[32]; Arrays.fill(bytes, (byte) value); return Base64.getEncoder().encodeToString(bytes); }
    private static ExportFile file(ExportSnapshot snapshot, String path) { return snapshot.files().stream().filter(value -> value.path().equals(path)).findFirst().orElseThrow(); }
    private static byte[] bytes(ExportFile file) throws Exception { var bytes = new ByteArrayOutputStream(); file.writeTo(bytes); return bytes.toByteArray(); }
    private static ExportSnapshot replace(ExportSnapshot snapshot, ExportFile replacement) {
        return new ExportSnapshot(snapshot.actorId(), snapshot.tallerId(), snapshot.observedAt(), snapshot.files().stream()
                .map(file -> file.path().equals(replacement.path()) ? replacement : file).toList(), snapshot.pendingPhotos());
    }
    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertThat(entry.getName()).doesNotContain("..").doesNotStartWith("/").doesNotContain("\\");
                assertThat(entries.put(entry.getName(), zip.readAllBytes())).isNull(); zip.closeEntry();
            }
        }
        return entries;
    }
    private static ExportSnapshot snapshot(boolean photos) {
        var files = new ArrayList<ExportFile>();
        for (var query : ExportBusinessCatalog.queries()) files.add(new ExportFile("datos/" + query.category() + ".json", "application/json", 0, "[]".getBytes(StandardCharsets.UTF_8)));
        files.replaceAll(file -> file.path().equals("datos/clientes.json") ? new ExportFile(file.path(), "application/json", 1,
                "[{\"id\":\"9007199254740993\",\"nombre\":\"private customer canary\",\"importe\":\"100000000000000.01\"}]".getBytes(StandardCharsets.UTF_8)) : file);
        for (String category : List.of("fotos_legacy", "qr_cobro", "aceptaciones_propias")) files.add(new ExportFile("datos/" + category + ".json", "application/json", 0, "[]".getBytes(StandardCharsets.UTF_8)));
        String photoRows = photos ? "[{\"id\":\"" + PHOTO + "\",\"taller_id\":\"" + TALLER + "\",\"reparacion_id\":\"13\",\"estado\":\"ASOCIADA\",\"archivo_estado\":\"PENDIENTE_C\",\"mime_type\":\"image/png\",\"bytes\":\"" + PNG.length + "\",\"sha256\":\"" + ExportFile.digest(PNG) + "\"},"
                + "{\"id\":\"" + UNAVAILABLE_PHOTO + "\",\"archivo_estado\":\"NO_DISPONIBLE_EN_SNAPSHOT\",\"estado\":\"ELIMINADA\"}]" : "[]";
        files.add(new ExportFile("datos/fotos_privadas.json", "application/json", photos ? 2 : 0, photoRows.getBytes(StandardCharsets.UTF_8)));
        files.add(new ExportFile("LEEME.txt", "text/plain; charset=utf-8", -1, "Preparación B incompleta".getBytes(StandardCharsets.UTF_8)));
        files.add(new ExportFile("documentos/" + DOCUMENT + ".md", "text/markdown; charset=utf-8", -1, "# Privacidad\n\nTexto exacto: áé 🔧\n".getBytes(StandardCharsets.UTF_8)));
        files.add(new ExportFile("archivos/qr-cobro.png", "image/png", -1, PNG));
        return new ExportSnapshot(14, TALLER, Instant.parse("2026-09-12T15:00:00.123456789Z"), files,
                photos ? List.of(new ExportSnapshot.PendingPhoto(PHOTO, "archivos/fotos/" + PHOTO + ".png", ExportFile.digest(PNG), PNG.length)) : List.of());
    }
}
