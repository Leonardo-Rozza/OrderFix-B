package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict transport parsing only: preserves semantic omissions, order and multiplicity. */
final class LegalAcceptanceRequests {
    private static final int MAX_BYTES = 8 * 1_048_576;
    private static final Pattern REVISION = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final JsonFactory JSON = jsonFactory();

    private LegalAcceptanceRequests() { }

    static Parsed read(List<String> idempotencyHeaderValues, InputStream body) {
        String key = presentKey(idempotencyHeaderValues);
        final String text;
        try {
            if (body == null) throw LegalAcceptanceHttpException.invalidPayload();
            // The servlet retains ownership. Bound actual bytes, including trailing whitespace.
            byte[] bytes = body.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES || hasBom(bytes)) {
                throw LegalAcceptanceHttpException.invalidPayload();
            }
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            // Validate every supplied field and cardinality before allocating DTO lists.
            document(text, key, false);
        } catch (IOException | RuntimeException failure) {
            throw LegalAcceptanceHttpException.invalidPayload();
        }
        if (key == null) throw LegalAcceptanceHttpException.requiredKey();
        try {
            return document(text, key, true);
        } catch (IOException | RuntimeException failure) {
            // Parser/input diagnostics must never become response text or exception causes.
            throw LegalAcceptanceHttpException.invalidPayload();
        }
    }

    private static String presentKey(List<String> values) {
        if (values == null || values.size() > 1) throw LegalAcceptanceHttpException.invalidKey();
        if (values.isEmpty()) return null;
        String value = values.getFirst();
        try { LegalAcceptanceCommandValidator.requireIdempotencyKey(value); }
        catch (RuntimeException failure) { throw LegalAcceptanceHttpException.invalidKey(); }
        return value;
    }

    private static Parsed document(String text, String key, boolean materialize) throws IOException {
        try (JsonParser parser = JSON.createParser(text)) {
            expect(parser.nextToken(), JsonToken.START_OBJECT);
            String revision = null;
            List<Acceptance> acceptances = null;
            int seen = 0;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = field(parser);
                int bit = switch (name) {
                    case "requiredSetRevision" -> 1;
                    case "aceptacionesLegales" -> 2;
                    default -> throw LegalAcceptanceHttpException.invalidPayload();
                };
                seen = unique(seen, bit);
                parser.nextToken();
                if (bit == 1) revision = matching(string(parser), REVISION);
                else acceptances = acceptances(parser, materialize);
            }
            if (seen != 3 || parser.nextToken() != null) throw LegalAcceptanceHttpException.invalidPayload();
            return materialize ? new Parsed(key, revision, acceptances) : null;
        }
    }

    private static List<Acceptance> acceptances(JsonParser parser, boolean materialize) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY);
        List<Acceptance> values = materialize ? new ArrayList<>() : null;
        int count = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (++count > LegalAcceptanceCommandValidator.MAX_ACCEPTANCES) {
                throw LegalAcceptanceHttpException.invalidPayload();
            }
            Acceptance value = acceptance(parser, materialize);
            if (materialize) values.add(value);
        }
        return values;
    }

    private static Acceptance acceptance(JsonParser parser, boolean materialize) throws IOException {
        expect(parser.currentToken(), JsonToken.START_OBJECT);
        UUID id = null;
        TipoActoLegal type = null;
        String digest = null;
        List<Document> documents = null;
        boolean confirmed = false;
        int seen = 0;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = field(parser);
            int bit = switch (name) {
                case "requisitoVersionId" -> 1;
                case "tipoActo" -> 2;
                case "afirmacionSha256" -> 4;
                case "documentos" -> 8;
                case "confirmado" -> 16;
                default -> throw LegalAcceptanceHttpException.invalidPayload();
            };
            seen = unique(seen, bit);
            parser.nextToken();
            switch (bit) {
                case 1 -> id = uuid(parser);
                case 2 -> type = TipoActoLegal.valueOf(string(parser));
                case 4 -> digest = matching(string(parser), DIGEST);
                case 8 -> documents = documents(parser, materialize);
                case 16 -> {
                    if (parser.currentToken() != JsonToken.VALUE_TRUE && parser.currentToken() != JsonToken.VALUE_FALSE) {
                        throw LegalAcceptanceHttpException.invalidPayload();
                    }
                    confirmed = parser.currentToken() == JsonToken.VALUE_TRUE;
                }
                default -> throw LegalAcceptanceHttpException.invalidPayload();
            }
        }
        if (seen != 31) throw LegalAcceptanceHttpException.invalidPayload();
        return materialize ? new Acceptance(id, type, digest, documents, confirmed) : null;
    }

    private static List<Document> documents(JsonParser parser, boolean materialize) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY);
        List<Document> values = materialize ? new ArrayList<>() : null;
        int count = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (++count > LegalAcceptanceCommandValidator.MAX_DOCUMENTS_PER_ACCEPTANCE) {
                throw LegalAcceptanceHttpException.invalidPayload();
            }
            Document value = documentReference(parser, materialize);
            if (materialize) values.add(value);
        }
        return values;
    }

    private static Document documentReference(JsonParser parser, boolean materialize) throws IOException {
        expect(parser.currentToken(), JsonToken.START_OBJECT);
        UUID id = null;
        String digest = null;
        int seen = 0;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = field(parser);
            int bit = switch (name) {
                case "documentoVersionId" -> 1;
                case "sha256" -> 2;
                default -> throw LegalAcceptanceHttpException.invalidPayload();
            };
            seen = unique(seen, bit);
            parser.nextToken();
            if (bit == 1) id = uuid(parser);
            else digest = matching(string(parser), DIGEST);
        }
        if (seen != 3) throw LegalAcceptanceHttpException.invalidPayload();
        return materialize ? new Document(id, digest) : null;
    }

    private static String field(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.FIELD_NAME);
        String value = parser.currentName();
        requireScalars(value);
        return value;
    }

    private static String string(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.VALUE_STRING);
        String value = parser.getText();
        requireScalars(value);
        return value;
    }

    private static UUID uuid(JsonParser parser) throws IOException {
        return UUID.fromString(matching(string(parser), UUID_TEXT));
    }

    private static String matching(String value, Pattern pattern) {
        if (!pattern.matcher(value).matches()) throw LegalAcceptanceHttpException.invalidPayload();
        return value;
    }

    private static int unique(int seen, int bit) {
        if ((seen & bit) != 0) throw LegalAcceptanceHttpException.invalidPayload();
        return seen | bit;
    }

    private static void expect(JsonToken actual, JsonToken expected) {
        if (actual != expected) throw LegalAcceptanceHttpException.invalidPayload();
    }

    private static void requireScalars(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw LegalAcceptanceHttpException.invalidPayload();
                }
            } else if (Character.isLowSurrogate(current)) throw LegalAcceptanceHttpException.invalidPayload();
        }
    }

    private static boolean hasBom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf;
    }

    private static JsonFactory jsonFactory() {
        var constraints = StreamReadConstraints.builder()
                .maxDocumentLength(MAX_BYTES).maxNestingDepth(32).maxTokenCount(300_000)
                .maxStringLength(1_048_576).maxNameLength(256).build();
        var builder = JsonFactory.builder().streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        for (JsonReadFeature feature : JsonReadFeature.values()) builder.disable(feature);
        return builder.build();
    }

    record Parsed(String idempotencyKey, String requiredSetRevision, List<Acceptance> acceptances) {
        Parsed { acceptances = List.copyOf(acceptances); }
        @Override public String toString() { return "Parsed[redacted]"; }
    }
}
