package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the permitted archive in memory, with generated paths and a manifest of the final bytes. */
final class ExportArtifactArchiveWriter {
    private static final JsonFactory JSON = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON);
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    private static final String README = """
            Exportación de datos de OrdenFix

            Este paquete contiene los datos del taller observados en la fecha del manifiesto,
            las constancias legales propias del solicitante y las fotos privadas que pudieron
            autorizarse y verificarse al generar el archivo. El manifiesto enumera cada archivo,
            su tamaño, SHA-256 y las exclusiones del alcance. No hay archivos pendientes.

            Los datos están en JSON UTF-8. Los identificadores BIGINT y los importes decimales
            se representan como cadenas para conservar su precisión. Las fechas y horas sin
            zona mantienen el valor registrado, sin inventar una zona horaria. Se conservan
            nulls, duplicados y registros inactivos o anulados. Los documentos legales están
            en Markdown con sus bytes exactos; el QR está en PNG y las fotos en PNG o JPEG.

            No se incluyen contraseñas, PIN o patrón, tokens, códigos de seguimiento,
            identidades técnicas o secretos de proveedores, ni aceptaciones personales de
            otros usuarios. Las fotos vencidas, eliminadas o de origen legacy no acreditado
            conservan su metadata y motivo de exclusión; sus binarios no se recuperan.

            Los cobros son registros ingresados por el taller: OrdenFix no procesa pagos
            entre el taller y sus clientes ni emite comprobantes fiscales. La información
            comercial de la suscripción SaaS se encuentra en categorías separadas.
            """;
    private static final List<String> EXCLUSIONS = List.of(
            "Contraseñas, PIN/patrón y material de cifrado", "Tokens y código de seguimiento",
            "Aceptaciones personales de otros usuarios", "IP/UA y ledgers técnicos",
            "URLs y binarios legacy de origen no acreditado", "Claves, assets, leases y diagnósticos de proveedores",
            "Binarios de fotos no disponibles en el snapshot (vencidas, eliminadas o sin asociación vigente)"
    );

    private ExportArtifactArchiveWriter() { }

    static byte[] write(ExportSnapshot snapshot, List<ExportArtifactCodec.PhotoFile> providedPhotos) {
        ExportArtifactSnapshotCodec.validate(snapshot);
        try {
            if (providedPhotos == null || providedPhotos.size() != snapshot.pendingPhotos().size()) throw ExportArtifactCodec.invalid();
            Map<UUID, ExportArtifactCodec.PhotoFile> photos = new HashMap<>();
            for (var photo : List.copyOf(providedPhotos)) {
                if (photo == null || photos.putIfAbsent(photo.id(), photo) != null) throw ExportArtifactCodec.invalid();
            }
            long photoBytes = 0;
            for (var expected : snapshot.pendingPhotos()) {
                var actual = photos.get(expected.id());
                if (actual == null || actual.size() != expected.bytes()) throw ExportArtifactCodec.invalid();
                byte[] bytes = actual.content();
                try {
                    if (!ExportFile.digest(bytes).equals(expected.sha256()) || !imageSignature(expected.path(), bytes)) throw ExportArtifactCodec.invalid();
                } finally { Arrays.fill(bytes, (byte) 0); }
                photoBytes += actual.size();
                if (photoBytes > ExportArtifactCodec.MAX_PHOTO_BYTES) throw ExportArtifactCodec.capacity();
            }
            List<ExportFile> files = new ArrayList<>(); long contentBytes = photoBytes;
            for (var original : snapshot.files()) {
                ExportFile file = original;
                if (file.path().equals("LEEME.txt")) file = new ExportFile("LEEME.txt", "text/plain; charset=utf-8", -1, README.getBytes(StandardCharsets.UTF_8));
                if (file.path().equals("datos/fotos_privadas.json")) file = completedPhotoMetadata(snapshot, file);
                files.add(file); contentBytes += file.size();
                if (contentBytes > ExportArtifactCodec.MAX_ARCHIVE_CONTENT_BYTES) throw ExportArtifactCodec.capacity();
            }
            byte[] manifest = manifest(snapshot, files);
            if (manifest.length > ExportArtifactCodec.MAX_ARCHIVE_CONTENT_BYTES - contentBytes) throw ExportArtifactCodec.capacity();
            var output = new ExportBuffer(ExportArtifactCodec.MAX_CIPHERTEXT_BYTES - 128);
            try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (var file : files) {
                    entry(zip, file.path()); file.writeTo(zip); zip.closeEntry();
                }
                for (var expected : snapshot.pendingPhotos()) {
                    entry(zip, expected.path()); byte[] bytes = photos.get(expected.id()).content();
                    try { zip.write(bytes); } finally { Arrays.fill(bytes, (byte) 0); }
                    zip.closeEntry();
                }
                entry(zip, "manifest.json"); zip.write(manifest); zip.closeEntry();
            } finally { Arrays.fill(manifest, (byte) 0); }
            return output.toByteArray();
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ExportPackageException known) throw known;
            throw ExportArtifactCodec.invalid();
        }
    }

    private static ExportFile completedPhotoMetadata(ExportSnapshot snapshot, ExportFile original) throws IOException {
        var source = new ExportBuffer(WorkshopExportSnapshotService.MAX_BYTES); original.writeTo(source);
        var output = new ExportBuffer(WorkshopExportSnapshotService.MAX_BYTES);
        Map<UUID, ExportSnapshot.PendingPhoto> expected = new HashMap<>();
        for (var photo : snapshot.pendingPhotos()) expected.put(photo.id(), photo);
        Set<UUID> seen = new HashSet<>(), included = new HashSet<>(); long records = 0;
        byte[] bytes = source.toByteArray();
        try (var parser = JSON.createParser(bytes); var generator = JSON.createGenerator(output)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) throw ExportArtifactCodec.invalid();
            generator.writeStartArray();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() != JsonToken.START_OBJECT || ++records > 50_000) throw ExportArtifactCodec.invalid();
                var node = MAPPER.readTree(parser);
                if (!(node instanceof ObjectNode row)) throw ExportArtifactCodec.invalid();
                String textId = row.path("id").asText(); UUID id = UUID.fromString(textId);
                if (!id.toString().equals(textId) || !seen.add(id)) throw ExportArtifactCodec.invalid();
                String state = row.path("archivo_estado").asText();
                var photo = expected.get(id);
                if (photo != null) {
                    if (!state.equals("PENDIENTE_C") || !row.path("estado").asText().equals("ASOCIADA")
                            || row.path("reparacion_id").isMissingNode() || row.path("reparacion_id").isNull()
                            || !row.path("taller_id").asText().equals(Long.toString(snapshot.tallerId()))
                            || !row.path("sha256").asText().equals(photo.sha256()) || !row.path("bytes").asText().equals(Long.toString(photo.bytes()))
                            || !row.path("mime_type").asText().equals(photo.path().endsWith(".png") ? "image/png" : "image/jpeg")) throw ExportArtifactCodec.invalid();
                    included.add(id); row.put("archivo_estado", "INCLUIDA");
                } else if (!state.equals("NO_DISPONIBLE_EN_SNAPSHOT")) throw ExportArtifactCodec.invalid();
                MAPPER.writeTree(generator, row);
            }
            if (parser.nextToken() != null || records != original.records() || !included.equals(expected.keySet())) throw ExportArtifactCodec.invalid();
            generator.writeEndArray();
        } finally { Arrays.fill(bytes, (byte) 0); }
        return new ExportFile(original.path(), original.mediaType(), original.records(), output.toByteArray());
    }

    private static byte[] manifest(ExportSnapshot snapshot, List<ExportFile> files) throws IOException {
        var output = new ExportBuffer(MAX_MANIFEST_BYTES);
        try (var json = JSON.createGenerator(output)) {
            json.writeStartObject(); json.writeStringField("formato", "ordenfix-export/1");
            json.writeStringField("fase", "PAQUETE_COMPLETO"); json.writeBooleanField("exportacion_integral_completa", true);
            json.writeStringField("alcance", "Datos del taller, constancias legales propias y fotos privadas disponibles y autorizadas; las exclusiones se detallan abajo.");
            json.writeStringField("observado_en", snapshot.observedAt().toString());
            json.writeStringField("taller_id", Long.toString(snapshot.tallerId())); json.writeStringField("solicitante_id", Long.toString(snapshot.actorId()));
            json.writeArrayFieldStart("archivos");
            for (var file : files) {
                json.writeStartObject(); json.writeStringField("ruta", file.path()); json.writeStringField("tipo", file.mediaType());
                json.writeNumberField("bytes", file.size()); json.writeStringField("sha256", file.sha256());
                if (file.records() >= 0) json.writeNumberField("registros", file.records());
                json.writeEndObject();
            }
            for (var photo : snapshot.pendingPhotos()) {
                json.writeStartObject(); json.writeStringField("ruta", photo.path());
                json.writeStringField("foto_id", photo.id().toString());
                json.writeStringField("tipo", photo.path().endsWith(".png") ? "image/png" : "image/jpeg");
                json.writeNumberField("bytes", photo.bytes()); json.writeStringField("sha256", photo.sha256()); json.writeEndObject();
            }
            json.writeEndArray(); json.writeArrayFieldStart("archivos_pendientes"); json.writeEndArray();
            json.writeArrayFieldStart("exclusiones"); for (var exclusion : EXCLUSIONS) json.writeString(exclusion);
            json.writeEndArray(); json.writeEndObject();
        }
        return output.toByteArray();
    }
    private static boolean imageSignature(String path, byte[] bytes) {
        if (path.endsWith(".png")) return Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        return bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
    }
    private static void entry(ZipOutputStream zip, String path) throws IOException {
        var entry = new ZipEntry(path); entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0)); zip.putNextEntry(entry);
    }
}
