package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.RegisterRequestDto;
import jakarta.validation.Validator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict transport parsing only: preserves semantic omissions, order and multiplicity. */
final class LegalRegistrationRequests {
    private static final int MAX_BYTES = 8 * 1_048_576;
    private static final Pattern REVISION = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final JsonFactory JSON = jsonFactory();

    private LegalRegistrationRequests() { }

    static Parsed read(List<String> idempotencyHeaderValues, InputStream body, Validator validator) {
        return read(idempotencyHeaderValues, body, validator, () -> { });
    }

    /** Checkpoints and validation infrastructure failures retain their operational identity. */
    static Parsed read(List<String> idempotencyHeaderValues, InputStream body,
                       Validator validator, Runnable checkpoint) {
        Objects.requireNonNull(validator, "validator");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Runnable guarded = () -> {
            try { checkpoint.run(); }
            catch (RuntimeException failure) { throw new CheckpointFailure(failure); }
        };
        try {
            return readChecked(idempotencyHeaderValues, body, validator, guarded);
        } catch (CheckpointFailure interrupted) {
            throw interrupted.original;
        }
    }

    private static Parsed readChecked(List<String> idempotencyHeaderValues, InputStream body,
                                      Validator validator, Runnable checkpoint) {
        checkpoint.run();
        String key = presentKey(idempotencyHeaderValues);
        checkpoint.run();
        final String text;
        final Draft first;
        try {
            if (body == null) throw LegalRegistrationHttpException.invalidBody();
            // The caller owns the stream. Count actual bytes, including trailing whitespace.
            byte[] bytes = checkedBody(body, checkpoint).readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES || hasBom(bytes)) {
                throw LegalRegistrationHttpException.invalidBody();
            }
            checkpoint.run();
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            checkpoint.run();
            first = document(text, false, checkpoint);
            checkpoint.run();
        } catch (CheckpointFailure | LegalRegistrationHttpException expected) {
            throw expected;
        } catch (IOException | RuntimeException malformed) {
            throw LegalRegistrationHttpException.invalidBody();
        }
        // Deliberately outside parser catches: a broken provider is not a client rejection.
        var violations = Objects.requireNonNull(validator.validate(first.registration()), "validation result");
        checkpoint.run();
        if (!violations.isEmpty()) throw LegalRegistrationHttpException.invalidRegistration();
        if (key == null && !first.hasRevision() && !first.hasAcceptances()) {
            return new Parsed(Kind.ABSENT, first.registration(), null, null, null);
        }
        if (key == null) throw LegalRegistrationHttpException.requiredKey();
        if (!first.hasRevision() || !first.hasAcceptances()) throw LegalRegistrationHttpException.invalidPayload();
        try {
            Draft complete = document(text, true, checkpoint);
            checkpoint.run();
            return new Parsed(Kind.COMPLETE, complete.registration(), key,
                    complete.revision(), complete.acceptances());
        } catch (CheckpointFailure | LegalRegistrationHttpException expected) {
            throw expected;
        } catch (IOException | RuntimeException malformed) {
            throw LegalRegistrationHttpException.invalidBody();
        }
    }

    private static InputStream checkedBody(InputStream body, Runnable checkpoint) {
        return new InputStream() {
            @Override public int read() throws IOException {
                checkpoint.run();
                int value = body.read();
                checkpoint.run();
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                checkpoint.run();
                int count = body.read(bytes, offset, Math.min(length, 8_192));
                checkpoint.run();
                return count;
            }
            // This wrapper and the parser never close the caller-owned servlet stream.
        };
    }

    private static final class CheckpointFailure extends RuntimeException {
        private final RuntimeException original;
        private CheckpointFailure(RuntimeException original) {
            super("La lectura legal fue interrumpida.", null, false, false);
            this.original = original;
        }
    }

    private static String presentKey(List<String> values) {
        if (values == null || values.size() > 1) throw LegalRegistrationHttpException.invalidKey();
        if (values.isEmpty()) return null;
        String value = values.getFirst();
        try { LegalAcceptanceCommandValidator.requireIdempotencyKey(value); }
        catch (RuntimeException failure) { throw LegalRegistrationHttpException.invalidKey(); }
        return value;
    }

    private static Draft document(String text, boolean materialize, Runnable checkpoint) throws IOException {
        try (JsonParser parser = new JsonParserDelegate(JSON.createParser(text)) {
            @Override public JsonToken nextToken() throws IOException {
                checkpoint.run();
                JsonToken token = super.nextToken();
                checkpoint.run();
                return token;
            }
        }) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw LegalRegistrationHttpException.invalidBody();
            String workshop = null, phone = null, admin = null, email = null, password = null, revision = null;
            List<Acceptance> acceptances = null;
            int seen = 0;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw LegalRegistrationHttpException.invalidBody();
                String name = field(parser);
                int bit = switch (name) {
                    case "nombreTaller" -> 1;
                    case "telefonoTaller" -> 2;
                    case "nombreAdmin" -> 4;
                    case "email" -> 8;
                    case "password" -> 16;
                    case "requiredSetRevision" -> 32;
                    case "aceptacionesLegales" -> 64;
                    default -> throw LegalRegistrationHttpException.invalidBody();
                };
                if ((seen & bit) != 0) throw LegalRegistrationHttpException.invalidBody();
                seen |= bit;
                parser.nextToken();
                switch (bit) {
                    case 1 -> workshop = businessString(parser);
                    case 2 -> phone = businessString(parser);
                    case 4 -> admin = businessString(parser);
                    case 8 -> email = businessString(parser);
                    case 16 -> password = businessString(parser);
                    case 32 -> revision = matching(string(parser), REVISION);
                    case 64 -> acceptances = acceptances(parser, materialize);
                    default -> throw LegalRegistrationHttpException.invalidBody();
                }
            }
            if (parser.nextToken() != null) throw LegalRegistrationHttpException.invalidBody();
            return new Draft(new RegisterRequestDto(workshop, phone, admin, email, password), revision,
                    acceptances, (seen & 32) != 0, (seen & 64) != 0);
        }
    }

    private static String businessString(JsonParser parser) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) return null;
        if (parser.currentToken() != JsonToken.VALUE_STRING) throw LegalRegistrationHttpException.invalidBody();
        String value = parser.getText();
        requireScalars(value);
        return value;
    }

    private static List<Acceptance> acceptances(JsonParser parser, boolean materialize) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY);
        List<Acceptance> values = materialize ? new ArrayList<>() : null;
        int count = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (++count > LegalAcceptanceCommandValidator.MAX_ACCEPTANCES) {
                throw LegalRegistrationHttpException.invalidPayload();
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
                default -> throw LegalRegistrationHttpException.invalidPayload();
            };
            seen = unique(seen, bit);
            parser.nextToken();
            switch (bit) {
                case 1 -> id = uuid(parser);
                case 2 -> {
                    String value = string(parser);
                    try { type = TipoActoLegal.valueOf(value); }
                    catch (IllegalArgumentException unsupported) { throw LegalRegistrationHttpException.invalidPayload(); }
                }
                case 4 -> digest = matching(string(parser), DIGEST);
                case 8 -> documents = documents(parser, materialize);
                case 16 -> {
                    if (parser.currentToken() != JsonToken.VALUE_TRUE && parser.currentToken() != JsonToken.VALUE_FALSE) {
                        throw LegalRegistrationHttpException.invalidPayload();
                    }
                    confirmed = parser.currentToken() == JsonToken.VALUE_TRUE;
                }
                default -> throw LegalRegistrationHttpException.invalidPayload();
            }
        }
        if (seen != 31) throw LegalRegistrationHttpException.invalidPayload();
        return materialize ? new Acceptance(id, type, digest, documents, confirmed) : null;
    }

    private static List<Document> documents(JsonParser parser, boolean materialize) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY);
        List<Document> values = materialize ? new ArrayList<>() : null;
        int count = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (++count > LegalAcceptanceCommandValidator.MAX_DOCUMENTS_PER_ACCEPTANCE) {
                throw LegalRegistrationHttpException.invalidPayload();
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
                default -> throw LegalRegistrationHttpException.invalidPayload();
            };
            seen = unique(seen, bit);
            parser.nextToken();
            if (bit == 1) id = uuid(parser);
            else digest = matching(string(parser), DIGEST);
        }
        if (seen != 3) throw LegalRegistrationHttpException.invalidPayload();
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
        if (!pattern.matcher(value).matches()) throw LegalRegistrationHttpException.invalidPayload();
        return value;
    }

    private static int unique(int seen, int bit) {
        if ((seen & bit) != 0) throw LegalRegistrationHttpException.invalidPayload();
        return seen | bit;
    }

    private static void expect(JsonToken actual, JsonToken expected) {
        if (actual != expected) throw LegalRegistrationHttpException.invalidPayload();
    }

    private static void requireScalars(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw LegalRegistrationHttpException.invalidBody();
                }
            } else if (Character.isLowSurrogate(current)) throw LegalRegistrationHttpException.invalidBody();
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

    enum Kind { ABSENT, COMPLETE }

    record Parsed(Kind kind, RegisterRequestDto registration, String idempotencyKey,
                  String requiredSetRevision, List<Acceptance> acceptances) {
        Parsed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(registration, "registration");
            if ((kind == Kind.ABSENT && (idempotencyKey != null || requiredSetRevision != null || acceptances != null))
                    || (kind == Kind.COMPLETE && (idempotencyKey == null || requiredSetRevision == null || acceptances == null))) {
                throw new IllegalArgumentException("La clasificación de registro no es coherente.");
            }
            if (acceptances != null) acceptances = List.copyOf(acceptances);
        }
        @Override public String toString() { return "Parsed[redacted]"; }
    }

    private record Draft(RegisterRequestDto registration, String revision, List<Acceptance> acceptances,
                         boolean hasRevision, boolean hasAcceptances) {
        @Override public String toString() { return "Draft[redacted]"; }
    }
}
