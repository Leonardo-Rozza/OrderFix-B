package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalAcceptanceRequestsTest {
    private static final String KEY = "64f89458-294c-4df5-a88d-833a1f1abcde";
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String DIGEST = "b".repeat(64);
    // Editorial UUIDs are canonical strings but need neither v4 nor the idempotency-key variant.
    private static final String REQUIREMENT = "00000000-0000-1000-0000-000000000001";
    private static final String DOCUMENT = "00000000-0000-0000-0000-000000000000";
    private static final int MAX_BODY = 8 * 1_048_576;

    @ParameterizedTest @EnumSource(TipoActoLegal.class)
    void acceptsEveryExactActEnumAndCanonicalEditorialUuidWithoutImposingV4(TipoActoLegal type) {
        var parsed = read(request(act(REQUIREMENT, type.name(), documents(1), true)));
        assertThat(parsed.idempotencyKey()).isEqualTo(KEY);
        assertThat(parsed.requiredSetRevision()).isEqualTo(REVISION);
        assertThat(parsed.acceptances()).containsExactly(new Acceptance(UUID.fromString(REQUIREMENT), type, DIGEST,
                List.of(new Document(UUID.fromString(DOCUMENT), DIGEST)), true));
    }

    @Test void propertyOrderJsonEscapesAndCrLfWhitespaceDoNotChangeTheBusinessValue() {
        String reordered = """
                {
                  "aceptacionesLegales": [{
                    "confirmado": true,
                    "documentos": [{"sha256":"%s", "documentoVersionId":"%s"}],
                    "afirmacionSha256": "%s", "tipoActo":"ACEPTACION", "requisitoVersionId":"%s"
                  }],
                  "requiredSetRevision": "%s"
                }
                """.formatted(DIGEST, DOCUMENT, DIGEST, REQUIREMENT, REVISION).replace("\n", "\r\n");
        // A JSON escape changes wire spelling, not the decoded canonical lowercase UUID.
        reordered = reordered.replace(REQUIREMENT, "\\u0030" + REQUIREMENT.substring(1));
        assertThat(read(reordered)).isEqualTo(read(request(act(REQUIREMENT, "ACEPTACION", documents(1), true))));
    }

    @Test void receivedArrayOrderAndDuplicatesRemainAvailableToTheExistingCanonicalValidator() {
        String high = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        String lowAct = act(REQUIREMENT, "ACEPTACION", documents(2), false);
        String highAct = act(high, "LECTURA", "[]", true);
        var received = read(request(highAct + ',' + lowAct + ',' + lowAct));
        var permuted = read(request(lowAct + ',' + highAct + ',' + lowAct));
        assertThat(received.acceptances()).extracting(Acceptance::requisitoVersionId)
                .containsExactly(UUID.fromString(high), UUID.fromString(REQUIREMENT), UUID.fromString(REQUIREMENT));
        assertThat(received.acceptances().get(1).confirmado()).isFalse();
        assertThat(received.acceptances().get(1).documentos()).hasSize(2);
        assertThat(received.acceptances().get(1).documentos().getFirst())
                .isEqualTo(received.acceptances().get(1).documentos().getLast());
        var actor = new LegalActorSnapshot(1, 2, UserRole.USER, 0, true, true);
        assertThat(LegalAcceptanceCommandValidator.authenticated(actor, REVISION, received.acceptances()).acceptances())
                .isEqualTo(LegalAcceptanceCommandValidator.authenticated(actor, REVISION, permuted.acceptances()).acceptances());
    }

    @Test void emptyAcceptanceAndDocumentArraysAreValidShape() {
        assertThat(read(request("" )).acceptances()).isEmpty();
        var parsed = read(request(act(REQUIREMENT, "ACEPTACION", "[]", false)));
        assertThat(parsed.acceptances()).hasSize(1);
        assertThat(parsed.acceptances().getFirst().documentos()).isEmpty();
        assertThat(parsed.acceptances().getFirst().confirmado()).isFalse();
    }

    @ParameterizedTest @MethodSource("invalidHeaders")
    void anyInvalidPresentHeaderPrecedesBodyConsumption(List<String> values) {
        AtomicInteger reads = new AtomicInteger();
        var body = new InputStream() {
            @Override public int read() throws IOException {
                reads.incrementAndGet(); throw new IOException("untrusted body must not be read");
            }
            @Override public void close() { throw new AssertionError("Servlet stream closed"); }
        };
        var failure = error(catchThrowable(() -> LegalAcceptanceRequests.read(values, body)), "IDEMPOTENCY_KEY_INVALIDA");
        assertThat(failure.details()).isEqualTo(Map.of("header", "Idempotency-Key"));
        assertThat(reads).hasValue(0);
    }

    static Stream<Arguments> invalidHeaders() {
        return Stream.of(
                Arguments.of((Object) null), Arguments.of(Arrays.asList((String) null)),
                Arguments.of(List.of("")), Arguments.of(List.of(" ")), Arguments.of(List.of(KEY.toUpperCase())),
                Arguments.of(List.of(' ' + KEY)), Arguments.of(List.of(KEY + ' ')), Arguments.of(List.of(KEY + "\n")),
                Arguments.of(List.of(KEY.replace("-4df5-", "-1df5-"))),
                Arguments.of(List.of(KEY.replace("-a88d-", "-788d-"))),
                Arguments.of(List.of("urn:uuid:" + KEY)), Arguments.of(List.of(KEY.replace("-", ""))),
                Arguments.of(List.of(KEY + ',' + KEY)), Arguments.of(List.of(KEY, KEY)),
                Arguments.of(List.of(KEY, "00000000-0000-4000-8000-000000000000")));
    }

    @Test void absentHeaderIsReportedOnlyAfterACompleteValidDtoAndDoesNotCloseTheStream() {
        var body = new OwnedStream(request(""));
        var failure = error(catchThrowable(() -> LegalAcceptanceRequests.read(List.of(), body)), "IDEMPOTENCY_KEY_REQUERIDA");
        assertThat(failure.details()).isEqualTo(Map.of("header", "Idempotency-Key"));
        assertThat(body.available()).isZero(); assertThat(body.closed).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"", "{", "{}", "null", "[]",
            "{\"requiredSetRevision\":null,\"aceptacionesLegales\":[]}"})
    void malformedJsonOrDtoPrecedesAnAbsentHeader(String input) {
        payloadError(catchThrowable(() -> LegalAcceptanceRequests.read(List.of(), stream(input))));
    }

    @ParameterizedTest @MethodSource("invalidJson")
    void rejectsNonJsonOrMultipleRootsWithoutEchoingItsContents(String input) {
        payloadError(catchThrowable(() -> read(input)));
    }

    static Stream<String> invalidJson() {
        String valid = request("");
        return Stream.of(valid + valid, valid + " false", valid + " /* credential-secret */", "// comment\n" + valid,
                valid.replace("[]", "[,]"), valid.replace("[]", "[null,]"), valid.replace("}", ",}"),
                valid.replace("\"aceptacionesLegales\"", "aceptacionesLegales"), valid.replace('"', '\''),
                valid.replace("[]", "NaN"), valid.replace("[]", "Infinity"), valid.replace("[]", "+1"),
                valid.replace("[]", "01"), "\u00a0" + valid, valid.replace("sha256:", "sha256:\t"));
    }

    @ParameterizedTest @ValueSource(strings = {"root", "acceptance", "document", "escaped-root"})
    void duplicatedObjectKeysAreNeverCollapsedEvenWhenTheirValuesMatch(String level) {
        String input = switch (level) {
            case "root" -> "{\"requiredSetRevision\":\"" + REVISION + "\"," + request("").substring(1);
            case "acceptance" -> request(act(REQUIREMENT, "ACEPTACION", documents(1), true)
                    .replace("\"confirmado\":true", "\"confirmado\":true,\"confirmado\":true"));
            case "document" -> request(act(REQUIREMENT, "ACEPTACION", documents(1)
                    .replace("\"sha256\":", "\"sha256\":\"" + DIGEST + "\",\"sha256\":"), true));
            case "escaped-root" -> "{\"requiredSetRevi\\u0073ion\":\"" + REVISION + "\"," + request("").substring(1);
            default -> throw new AssertionError(level);
        };
        payloadError(catchThrowable(() -> read(input)));
    }

    @ParameterizedTest @MethodSource("unknownFields")
    void rejectsUnknownPropertiesIncludingBrowserAuthorityAtEveryObjectLevel(String input) {
        payloadError(catchThrowable(() -> read(input)));
    }

    static Stream<String> unknownFields() {
        return Stream.concat(Stream.of("userId", "tallerId", "rol", "audiencia", "contexto", "locale", "password")
                        .map(name -> request("").replace("{", "{\"" + name + "\":\"sensitive-browser-value\",")),
                Stream.of(request(act(REQUIREMENT, "ACEPTACION", documents(1), true)
                                .replace("{", "{\"actor\":1,")),
                        request(act(REQUIREMENT, "ACEPTACION", documents(1)
                                .replace("{", "{\"titulo\":\"untrusted\","), true))));
    }

    @ParameterizedTest @MethodSource("missingAndNullFields")
    void allRequiredPropertiesMustBePresentAndNonNull(String input) {
        payloadError(catchThrowable(() -> read(input)));
    }

    static Stream<String> missingAndNullFields() {
        String document = document();
        String acceptance = act(REQUIREMENT, "ACEPTACION", documents(1), true);
        List<String> result = new ArrayList<>();
        for (String field : List.of("requiredSetRevision", "aceptacionesLegales")) {
            String source = request("");
            result.add(changeField(source, field, null)); result.add(changeField(source, field, "null"));
        }
        for (String field : List.of("requisitoVersionId", "tipoActo", "afirmacionSha256", "documentos", "confirmado")) {
            result.add(request(changeField(acceptance, field, null)));
            result.add(request(changeField(acceptance, field, "null")));
        }
        for (String field : List.of("documentoVersionId", "sha256")) {
            result.add(request(act(REQUIREMENT, "ACEPTACION", '[' + changeField(document, field, null) + ']', true)));
            result.add(request(act(REQUIREMENT, "ACEPTACION", '[' + changeField(document, field, "null") + ']', true)));
        }
        return result.stream();
    }

    @ParameterizedTest @MethodSource("wrongTypesAndFormats")
    void neverCoercesTypesRepairsUuidOrNormalizesDigests(String input) {
        payloadError(catchThrowable(() -> read(input)));
    }

    static Stream<String> wrongTypesAndFormats() {
        String act = act(REQUIREMENT, "ACEPTACION", documents(1), true);
        List<String> result = new ArrayList<>();
        for (String value : List.of("1", "\"true\"", "{}", "[]")) result.add(request(changeField(act, "confirmado", value)));
        for (String value : List.of("\"aceptacion\"", "\"UNKNOWN\"", "1")) result.add(request(changeField(act, "tipoActo", value)));
        for (String value : List.of("{}", "\"[]\"", "[null]", "[1]")) {
            result.add(changeField(request(""), "aceptacionesLegales", value));
            result.add(request(changeField(act, "documentos", value)));
        }
        for (String value : List.of("1-1-1-1-1", REQUIREMENT + " ", "urn:uuid:" + REQUIREMENT,
                "00000000-0000-1000-0000-00000000000A", "00000000-0000-1000-0000-00000000000g")) {
            result.add(request(act(value, "ACEPTACION", documents(1), true)));
            result.add(request(act(REQUIREMENT, "ACEPTACION", '[' + document().replace(DOCUMENT, value) + ']', true)));
        }
        for (String value : List.of("B".repeat(64), "b".repeat(63), "sha256:" + DIGEST, DIGEST + " ")) {
            result.add(request(changeField(act, "afirmacionSha256", quote(value))));
            result.add(request(act(REQUIREMENT, "ACEPTACION", '[' + changeField(document(), "sha256", quote(value)) + ']', true)));
        }
        for (String value : List.of(REVISION.toUpperCase(), "a".repeat(64), REVISION + " ", "sha256:" + "a".repeat(63))) {
            result.add(changeField(request(""), "requiredSetRevision", quote(value)));
        }
        return result.stream();
    }

    @ParameterizedTest @MethodSource("invalidEncoding")
    void rejectsInvalidUtf8AndBomWithoutClosingTheServletStream(byte[] bytes) {
        var body = new OwnedStream(bytes);
        payloadError(catchThrowable(() -> LegalAcceptanceRequests.read(List.of(KEY), body)));
        assertThat(body.closed).isFalse();
    }

    static Stream<byte[]> invalidEncoding() {
        byte[] valid = request("").getBytes(StandardCharsets.UTF_8);
        return Stream.of(new byte[]{(byte) 0xc0, (byte) 0xaf}, new byte[]{(byte) 0xe2, (byte) 0x82},
                new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80}, new byte[]{(byte) 0xff},
                concat(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, valid),
                concat(new byte[]{(byte) 0xff, (byte) 0xfe}, valid),
                request("").getBytes(StandardCharsets.UTF_16LE));
    }

    @ParameterizedTest @ValueSource(strings = {"\\uD800", "\\uDC00", "\\uD800x", "\\uD800\\uD800"})
    void rejectsEscapedUnpairedSurrogatesInValuesAndPropertyNames(String escaped) {
        payloadError(catchThrowable(() -> read(request("").replace("sha256:", escaped))));
        payloadError(catchThrowable(() -> read(request("").replace("requiredSetRevision", "requiredSet" + escaped + "Revision"))));
    }

    @Test void acceptsTheComplete2048By16CardinalityWithoutDeduplicating() {
        String act = act(REQUIREMENT, "ACEPTACION", documents(16), false);
        String input = request(String.join(",", java.util.Collections.nCopies(2_048, act)));
        assertThat(input.getBytes(StandardCharsets.UTF_8).length).isLessThan(MAX_BODY);
        var parsed = read(input);
        assertThat(parsed.acceptances()).hasSize(2_048);
        assertThat(parsed.acceptances()).allSatisfy(value -> {
            assertThat(value.documentos()).hasSize(16); assertThat(value.confirmado()).isFalse();
        });
    }

    @Test void rejectsTheFirstAcceptanceOrDocumentBeyondItsLimit() {
        String act = act(REQUIREMENT, "ACEPTACION", "[]", true);
        payloadError(catchThrowable(() -> read(request(String.join(",", java.util.Collections.nCopies(2_049, act))))));
        payloadError(catchThrowable(() -> read(request(act(REQUIREMENT, "ACEPTACION", documents(17), true)))));
    }

    @Test void acceptsExactlyEightMiBIncludingWhitespaceAndReadsOnlyOneSentinelByteBeyondIt() {
        byte[] valid = request("").getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[MAX_BODY]; Arrays.fill(bytes, (byte) ' '); System.arraycopy(valid, 0, bytes, 0, valid.length);
        var accepted = new OwnedStream(bytes);
        assertThat(LegalAcceptanceRequests.read(List.of(KEY), accepted).acceptances()).isEmpty();
        assertThat(accepted.available()).isZero(); assertThat(accepted.closed).isFalse();
        byte[] excessive = Arrays.copyOf(bytes, MAX_BODY + 101); Arrays.fill(excessive, MAX_BODY, excessive.length, (byte) ' ');
        var rejected = new OwnedStream(excessive);
        payloadError(catchThrowable(() -> LegalAcceptanceRequests.read(List.of(KEY), rejected)));
        assertThat(rejected.available()).isEqualTo(100); assertThat(rejected.closed).isFalse();
    }

    @Test void adversarialStructuralInputsFailWithoutClaimingWhichStricterLimitRejectsThemFirst() {
        payloadError(catchThrowable(() -> read(request("").replace("[]", "[".repeat(33) + "0" + "]".repeat(33)))));
        payloadError(catchThrowable(() -> read(request("").replace("[]", '[' + "0,".repeat(300_000) + "0]"))));
        payloadError(catchThrowable(() -> read(request("").replace("requiredSetRevision", "n".repeat(257)))));
        payloadError(catchThrowable(() -> read(request("").replace(REVISION, "x".repeat(1_048_577)))));
    }

    @Test void ioFailureHasNoSensitiveCauseAndLeavesTheServletStreamOwnedByItsCaller() {
        AtomicInteger closed = new AtomicInteger();
        var body = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("password IP UA idempotency-private-value"); }
            @Override public void close() { closed.incrementAndGet(); }
        };
        payloadError(catchThrowable(() -> LegalAcceptanceRequests.read(List.of(KEY), body)));
        assertThat(closed).hasValue(0);
        payloadError(catchThrowable(() -> LegalAcceptanceRequests.read(List.of(KEY), null)));
    }

    @Test void parsedAndNestedValuesAreImmutableAndTheirDiagnosticsAreRedacted() {
        var body = new OwnedStream(request(act(REQUIREMENT, "ACEPTACION", documents(1), true)));
        var parsed = LegalAcceptanceRequests.read(List.of(KEY), body);
        assertThat(body.closed).isFalse();
        assertThatThrownBy(() -> parsed.acceptances().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> parsed.acceptances().getFirst().documentos().clear()).isInstanceOf(UnsupportedOperationException.class);
        var mutable = new ArrayList<>(parsed.acceptances());
        var copied = new LegalAcceptanceRequests.Parsed(KEY, REVISION, mutable); mutable.clear();
        assertThat(copied.acceptances()).hasSize(1);
        assertThat(parsed.toString()).isEqualTo("Parsed[redacted]");
        assertThat(parsed.acceptances().getFirst().toString()).isEqualTo("Acceptance[redacted]");
        assertThat(parsed.acceptances().getFirst().documentos().getFirst().toString()).isEqualTo("Document[redacted]");
    }

    @Test void checkpointsInterruptBetweenBoundedReadsWithoutClosingTheBodyOrBecomingPayloadErrors() {
        byte[] bytes = (request("") + " ".repeat(20_000)).getBytes(StandardCharsets.UTF_8);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        var delegate = new ByteArrayInputStream(bytes);
        var body = new InputStream() {
            @Override public int read() { throw new AssertionError("Expected bounded bulk input"); }
            @Override public int read(byte[] target, int offset, int length) {
                assertThat(length).isLessThanOrEqualTo(8_192);
                reads.incrementAndGet();
                return delegate.read(target, offset, Math.min(length, 1_024));
            }
            @Override public void close() { closed.incrementAndGet(); }
        };
        var operational = new IllegalStateException("synthetic deadline-private-value");
        Throwable failure = catchThrowable(() -> LegalAcceptanceRequests.read(List.of(KEY), body, () -> {
            if (reads.get() == 2) throw operational;
        }));
        assertThat(failure).isSameAs(operational);
        assertThat(reads).hasValue(2); assertThat(closed).hasValue(0);
        assertThat(delegate.available()).isEqualTo(bytes.length - 2_048);
    }

    @Test void checkpointsCoverBothValidationAndMaterializationWithoutChangingHeaderPrecedence() {
        String payload = request(act(REQUIREMENT, "ACEPTACION", documents(2), true));
        AtomicInteger validationChecks = new AtomicInteger();
        error(catchThrowable(() -> LegalAcceptanceRequests.read(List.of(), stream(payload),
                validationChecks::incrementAndGet)), "IDEMPOTENCY_KEY_REQUERIDA");
        AtomicInteger allChecks = new AtomicInteger();
        var parsed = LegalAcceptanceRequests.read(List.of(KEY), stream(payload), allChecks::incrementAndGet);
        assertThat(parsed.acceptances()).hasSize(1);
        assertThat(allChecks.get()).isGreaterThan(validationChecks.get());
        AtomicInteger interruptedChecks = new AtomicInteger();
        var operational = new IllegalStateException("synthetic second-pass interruption");
        Throwable failure = catchThrowable(() -> LegalAcceptanceRequests.read(List.of(KEY), stream(payload), () -> {
            if (interruptedChecks.incrementAndGet() > validationChecks.get()) throw operational;
        }));
        assertThat(failure).isSameAs(operational);
    }

    @Test void checkpointExceptionIsNotReclassifiedEvenWhenItsTypeResemblesAParserRejection() {
        var operational = LegalAcceptanceHttpException.invalidKey();
        AtomicInteger checks = new AtomicInteger();
        Throwable failure = catchThrowable(() -> LegalAcceptanceRequests.read(List.of(KEY), stream(request("")), () -> {
            if (checks.incrementAndGet() > 4) throw operational;
        }));
        assertThat(failure).isSameAs(operational);
    }

    private static LegalAcceptanceRequests.Parsed read(String input) {
        return LegalAcceptanceRequests.read(List.of(KEY), stream(input));
    }
    private static ByteArrayInputStream stream(String input) { return new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)); }
    private static String request(String acceptances) { return "{\"requiredSetRevision\":\"" + REVISION + "\",\"aceptacionesLegales\":[" + acceptances + "]}"; }
    private static String act(String id, String type, String docs, boolean confirmed) {
        return "{\"requisitoVersionId\":\"" + id + "\",\"tipoActo\":\"" + type + "\",\"afirmacionSha256\":\"" + DIGEST
                + "\",\"documentos\":" + docs + ",\"confirmado\":" + confirmed + '}';
    }
    private static String document() { return "{\"documentoVersionId\":\"" + DOCUMENT + "\",\"sha256\":\"" + DIGEST + "\"}"; }
    private static String documents(int count) { return '[' + String.join(",", java.util.Collections.nCopies(count, document())) + ']'; }
    private static String quote(String value) { return '"' + value + '"'; }

    /** Test fixture mutation only; production never delegates DTO validation to databind coercions. */
    private static String changeField(String json, String field, String rawJson) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
            if (rawJson == null) object.remove(field); else object.set(field, mapper.readTree(rawJson));
            return mapper.writeValueAsString(object);
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    private static LegalAcceptanceHttpException payloadError(Throwable thrown) {
        var failure = error(thrown, "ACEPTACION_LEGAL_INVALIDA");
        assertThat(failure.details()).isEqualTo(Map.of("motivos", List.of("PAYLOAD_LEGAL_INCOMPLETO")));
        return failure;
    }

    private static LegalAcceptanceHttpException error(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(LegalAcceptanceHttpException.class);
        var failure = (LegalAcceptanceHttpException) thrown;
        assertThat(failure.status()).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.toString()).doesNotContain(KEY, REVISION, REQUIREMENT, DOCUMENT, "sensitive-browser-value", "idempotency-private-value");
        return failure;
    }

    private static byte[] concat(byte[] prefix, byte[] suffix) {
        byte[] combined = Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, combined, prefix.length, suffix.length); return combined;
    }

    private static final class OwnedStream extends ByteArrayInputStream {
        boolean closed;
        OwnedStream(String text) { this(text.getBytes(StandardCharsets.UTF_8)); }
        OwnedStream(byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }
}
