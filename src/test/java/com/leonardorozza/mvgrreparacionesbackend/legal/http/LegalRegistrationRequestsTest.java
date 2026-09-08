package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Registration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Pure protocol and the real historical Bean Validation contract, with no servlet, flags or database. */
class LegalRegistrationRequestsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "64f89458-294c-4df5-a88d-833a1f1abcde";
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String DIGEST = "b".repeat(64);
    private static final String REQUIREMENT = "00000000-0000-1000-0000-000000000001";
    private static final String DOCUMENT = "00000000-0000-0000-0000-000000000000";
    private static final String PASSWORD = "private-password-ñ-123";
    private static final int MAX_BYTES = 8 * 1_048_576;
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void validationProvider() {
        factory = Validation.buildDefaultValidatorFactory(); validator = factory.getValidator();
    }
    @AfterAll static void closeValidationProvider() { factory.close(); }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void classifiesActualPresenceOnlyAfterTheBusinessDtoAndSuppliedLegalShapeAreValid(int presence) {
        boolean header = (presence & 1) != 0;
        ObjectNode body = business();
        if ((presence & 2) != 0) body.put("requiredSetRevision", REVISION);
        if ((presence & 4) != 0) body.putArray("aceptacionesLegales");
        List<String> headers = header ? List.of(KEY) : List.of();
        if (presence == 0 || presence == 7) {
            var parsed = parse(headers, body.toString());
            assertThat(parsed.kind()).isEqualTo(presence == 0
                    ? LegalRegistrationRequests.Kind.ABSENT : LegalRegistrationRequests.Kind.COMPLETE);
            assertThat(parsed.registration().password()).isEqualTo(PASSWORD);
            if (presence == 0) {
                assertThat(parsed.idempotencyKey()).isNull(); assertThat(parsed.requiredSetRevision()).isNull();
                assertThat(parsed.acceptances()).isNull();
            } else {
                assertThat(parsed.idempotencyKey()).isEqualTo(KEY); assertThat(parsed.requiredSetRevision()).isEqualTo(REVISION);
                assertThat(parsed.acceptances()).isEmpty();
            }
        } else if (!header) {
            error(catchThrowable(() -> parse(headers, body.toString())), "IDEMPOTENCY_KEY_REQUERIDA");
        } else {
            payloadError(catchThrowable(() -> parse(headers, body.toString())));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"revision-null", "revision-empty", "acceptances-null", "acceptances-empty-string"})
    void suppliedNullOrEmptyInvalidLegalValuesNeverBecomeLegacyOrMissingHeader(String defect) {
        var body = business();
        switch (defect) {
            case "revision-null" -> body.putNull("requiredSetRevision");
            case "revision-empty" -> body.put("requiredSetRevision", "");
            case "acceptances-null" -> body.putNull("aceptacionesLegales");
            case "acceptances-empty-string" -> body.put("aceptacionesLegales", "");
            default -> throw new AssertionError(defect);
        }
        payloadError(catchThrowable(() -> parse(List.of(), body.toString())));
    }

    @Test void optionalPhoneAndBusinessStringsKeepTheirExactValuesWithoutNormalization() {
        var missing = business(); missing.remove("telefonoTaller");
        var explicitNull = business(); explicitNull.putNull("telefonoTaller");
        assertThat(parse(List.of(), missing.toString())).isEqualTo(parse(List.of(), explicitNull.toString()));
        assertThat(parse(List.of(), missing.toString()).registration().telefonoTaller()).isNull();
        for (String phone : List.of("", "  ", " +54 11 ")) {
            var body = business(); body.put("telefonoTaller", phone);
            body.put("nombreTaller", " Taller e\u0301 "); body.put("nombreAdmin", " Admin 😀 ");
            body.put("email", "CaseSensitive@Example.COM"); body.put("password", "  Secret-é-123  ");
            var dto = parse(List.of(), body.toString()).registration();
            assertThat(dto.telefonoTaller()).isEqualTo(phone);
            assertThat(dto.nombreTaller()).isEqualTo(" Taller e\u0301 "); assertThat(dto.nombreAdmin()).isEqualTo(" Admin 😀 ");
            assertThat(dto.email()).isEqualTo("CaseSensitive@Example.COM"); assertThat(dto.password()).isEqualTo("  Secret-é-123  ");
        }
    }

    @Test void acceptsHistoricalLengthBoundariesAndAnEmailLongerThanTheWorkshopColumn() {
        var body = business();
        body.put("nombreTaller", "t".repeat(120)); body.put("nombreAdmin", "a".repeat(50));
        body.put("telefonoTaller", "1".repeat(20)); body.put("password", "p".repeat(100));
        String email = "e".repeat(60) + '@' + "d".repeat(60) + ".example";
        body.put("email", email);
        assertThat(email).hasSizeGreaterThan(120);
        assertThat(parse(List.of(), body.toString()).registration().email()).isEqualTo(email);
        body.put("password", "123456");
        assertThat(parse(List.of(), body.toString()).registration().password()).isEqualTo("123456");
    }

    @ParameterizedTest @MethodSource("invalidBusinesses")
    void realHistoricalValidationPrecedesMissingHeaderAndPartialClassification(String field, String value) {
        var body = business();
        if (value == null) body.remove(field); else body.put(field, value);
        body.put("requiredSetRevision", REVISION);
        var failure = genericError(catchThrowable(() -> parse(List.of(), body.toString())));
        assertThat(failure.error()).isEqualTo("Error de validación");
        assertThat(failure.getMessage()).isEqualTo("Los datos de registro no son válidos.");
    }
    static Stream<Arguments> invalidBusinesses() {
        return Stream.of(Arguments.of("nombreTaller", (String) null), Arguments.of("nombreAdmin", " "),
                Arguments.of("email", "not-an-email"), Arguments.of("password", "short"),
                Arguments.of("nombreTaller", "t".repeat(121)), Arguments.of("nombreAdmin", "a".repeat(51)),
                Arguments.of("telefonoTaller", "1".repeat(21)), Arguments.of("password", "p".repeat(101)));
    }

    @Test void nullRequiredBusinessFieldsAreDtoViolationsAndNotAnAbsentLegalBlock() {
        var body = business(); body.putNull("password");
        assertThat(genericError(catchThrowable(() -> parse(List.of(), body.toString()))).error())
                .isEqualTo("Error de validación");
    }

    @ParameterizedTest @MethodSource("invalidHeaders")
    void invalidPresentHeaderPrecedesAnyBodyReadOrValidation(List<String> headers) {
        var observedValidator = mock(Validator.class);
        var reads = new AtomicInteger();
        var body = new InputStream() {
            @Override public int read() { reads.incrementAndGet(); throw new AssertionError("Body observed too early"); }
            @Override public void close() { throw new AssertionError("Caller stream closed"); }
        };
        var failure = error(catchThrowable(() -> LegalRegistrationRequests.read(headers, body, observedValidator)),
                "IDEMPOTENCY_KEY_INVALIDA");
        assertThat(failure.details()).isEqualTo(Map.of("header", "Idempotency-Key"));
        assertThat(reads).hasValue(0); verifyNoInteractions(observedValidator);
    }
    static Stream<Arguments> invalidHeaders() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(Arrays.asList((String) null)),
                Arguments.of(List.of("")), Arguments.of(List.of(" ")), Arguments.of(List.of(KEY.toUpperCase())),
                Arguments.of(List.of(' ' + KEY)), Arguments.of(List.of(KEY + ' ')), Arguments.of(List.of(KEY + "\r\n")),
                Arguments.of(List.of(KEY.replace("-4df5-", "-1df5-"))), Arguments.of(List.of(KEY.replace("-a88d-", "-788d-"))),
                Arguments.of(List.of("urn:uuid:" + KEY)), Arguments.of(List.of(KEY.replace("-", ""))),
                Arguments.of(List.of(KEY + ',' + KEY)), Arguments.of(List.of(KEY, KEY)));
    }

    @ParameterizedTest @ValueSource(strings = {"", "{", "null", "[]", "{} {}", "{not-json}", "{\"email\":'single'}"})
    void malformedGlobalJsonPrecedesBusinessAndAbsentHeaderErrors(String body) {
        invalidBody(catchThrowable(() -> parse(List.of(), body)));
    }

    @ParameterizedTest @ValueSource(strings = {"nombreTaller", "telefonoTaller", "nombreAdmin", "email", "password"})
    void businessStringsNeverUseScalarCoercion(String field) {
        var body = business(); body.put(field, 123);
        invalidBody(catchThrowable(() -> parse(List.of(), body.toString())));
    }

    @ParameterizedTest @ValueSource(strings = {"role", "tallerId", "userId", "contexto", "unknown"})
    void rootOnlyAllowsTheFiveBusinessFieldsAndTwoLegalFields(String name) {
        var body = business(); body.put(name, "not-authority");
        invalidBody(catchThrowable(() -> parse(List.of(), body.toString())));
    }

    @ParameterizedTest @ValueSource(strings = {"root", "escaped-root", "acceptance", "document"})
    void duplicateJsonKeysAreRejectedEvenWhenValuesAreIdentical(String level) {
        String body = complete(act("ACEPTACION", documents(1), true));
        body = switch (level) {
            case "root" -> body.replace("\"password\":", "\"password\":\"same\",\"password\":");
            case "escaped-root" -> body.replace("\"password\":", "\"passw\\u006frd\":\"same\",\"password\":");
            case "acceptance" -> body.replace("\"confirmado\":true", "\"confirmado\":true,\"confirmado\":true");
            case "document" -> body.replace("\"documentoVersionId\":", "\"sha256\":\"" + DIGEST + "\",\"documentoVersionId\":");
            default -> throw new AssertionError(level);
        };
        String supplied = body;
        invalidBody(catchThrowable(() -> parse(List.of(KEY), supplied)));
    }

    @ParameterizedTest @EnumSource(TipoActoLegal.class)
    void exactLegalEnumsAndEditorialUuidsDoNotRequireUuidVersionFour(TipoActoLegal type) {
        var parsed = parse(List.of(KEY), complete(act(type.name(), documents(1), true)));
        assertThat(parsed.acceptances()).containsExactly(new Acceptance(UUID.fromString(REQUIREMENT), type, DIGEST,
                List.of(new Document(UUID.fromString(DOCUMENT), DIGEST)), true));
    }

    @ParameterizedTest @MethodSource("malformedLegalShape")
    void malformedLegalFieldsUseTheLegalPayloadErrorBeforeMissingHeader(String body) {
        payloadError(catchThrowable(() -> parse(List.of(), body)));
    }
    static Stream<String> malformedLegalShape() {
        String correct = complete(act("ACEPTACION", documents(1), true));
        return Stream.of(correct.replace(REVISION, REVISION.toUpperCase()), correct.replace(REVISION, "a".repeat(64)),
                correct.replace(REQUIREMENT, "1-1-1-1-1"), correct.replace(REQUIREMENT, "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"),
                correct.replace("ACEPTACION", "aceptacion"), correct.replace(DIGEST, "B".repeat(64)),
                correct.replace("\"confirmado\":true", "\"confirmado\":\"true\""),
                correct.replace("\"confirmado\":true", "\"extra\":true"),
                correct.replace("\"documentoVersionId\":", "\"extra\":null,\"documentoVersionId\":"),
                complete("null"), complete("{}"), complete(act("ACEPTACION", "[null]", false)));
    }

    @Test void orderEscapesWhitespaceAndArrayMultiplicityRemainAvailableToTheCanonicalValidator() {
        String first = act("ACEPTACION", documents(2), false);
        String second = act("LECTURA", "[]", true).replace(REQUIREMENT, "ffffffff-ffff-ffff-ffff-ffffffffffff");
        var received = parse(List.of(KEY), complete(second + ',' + first + ',' + first));
        var permuted = parse(List.of(KEY), complete(first + ',' + second + ',' + first));
        assertThat(received.acceptances().getFirst().requisitoVersionId()).isEqualTo(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        assertThat(received.acceptances()).hasSize(3);
        assertThat(received.acceptances().get(1).confirmado()).isFalse();
        assertThat(received.acceptances().get(1).documentos()).hasSize(2);
        var dto = received.registration();
        var registration = new Registration(dto.nombreTaller(), dto.telefonoTaller(), dto.nombreAdmin(), dto.email(), dto.password());
        assertThat(LegalAcceptanceCommandValidator.registration(registration, REVISION, received.acceptances()).acceptances())
                .isEqualTo(LegalAcceptanceCommandValidator.registration(registration, REVISION, permuted.acceptances()).acceptances());
        var tree = business(); tree.putArray("aceptacionesLegales"); tree.put("requiredSetRevision", REVISION);
        String wire = tree.toPrettyString().replace("\n", "\r\n").replace("requiredSetRevision", "requiredSet\\u0052evision");
        assertThat(parse(List.of(KEY), wire)).isEqualTo(parse(List.of(KEY), complete("")));
    }

    @Test void permitsTheFull2048By16ShapeWithoutDiscardingDuplicatesOrFalseConfirmations() {
        String one = act("ACEPTACION", documents(16), false);
        String body = complete(String.join(",", Collections.nCopies(2_048, one)));
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThan(MAX_BYTES);
        var parsed = parse(List.of(KEY), body);
        assertThat(parsed.acceptances()).hasSize(2_048).allSatisfy(value -> {
            assertThat(value.documentos()).hasSize(16); assertThat(value.confirmado()).isFalse();
        });
        payloadError(catchThrowable(() -> parse(List.of(KEY), complete(String.join(",", Collections.nCopies(2_049,
                act("ACEPTACION", "[]", true)))))));
        payloadError(catchThrowable(() -> parse(List.of(KEY), complete(act("ACEPTACION", documents(17), true)))));
    }

    @Test void boundsActualBytesIncludingWhitespaceAndReadsOnlyOneSentinelByte() {
        byte[] prefix = complete("").getBytes(StandardCharsets.UTF_8);
        byte[] allowed = new byte[MAX_BYTES]; Arrays.fill(allowed, (byte) ' '); System.arraycopy(prefix, 0, allowed, 0, prefix.length);
        var stream = new OwnedStream(allowed);
        assertThat(LegalRegistrationRequests.read(List.of(KEY), stream, validator).acceptances()).isEmpty();
        assertThat(stream.available()).isZero(); assertThat(stream.closed).isFalse();
        byte[] tooLong = Arrays.copyOf(allowed, MAX_BYTES + 101); Arrays.fill(tooLong, MAX_BYTES, tooLong.length, (byte) ' ');
        var rejected = new OwnedStream(tooLong);
        invalidBody(catchThrowable(() -> LegalRegistrationRequests.read(List.of(KEY), rejected, validator)));
        assertThat(rejected.available()).isEqualTo(100); assertThat(rejected.closed).isFalse();
    }

    @Test void defensiveStructureLimitsRejectHostileInputWithoutClaimingWhichTighterGrammarFailsFirst() {
        invalidBody(catchThrowable(() -> parse(List.of(), "{\"" + "n".repeat(257) + "\":0}")));
        invalidBody(catchThrowable(() -> parse(List.of(), business().put("nombreTaller", "t".repeat(1_048_577)).toString())));
        payloadError(catchThrowable(() -> parse(List.of(KEY), complete("[".repeat(33) + "0" + "]".repeat(33)))));
        payloadError(catchThrowable(() -> parse(List.of(KEY), complete("0,".repeat(300_000) + "0"))));
    }

    @ParameterizedTest @MethodSource("invalidUtf8")
    void rejectsInvalidUtf8AndBomWithoutTakingOwnership(byte[] bytes) {
        var body = new OwnedStream(bytes);
        invalidBody(catchThrowable(() -> LegalRegistrationRequests.read(List.of(), body, validator)));
        assertThat(body.closed).isFalse();
    }
    static Stream<byte[]> invalidUtf8() {
        return Stream.of(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'},
                new byte[]{'{', '"', (byte) 0xc3, '(', '"', ':', '0', '}'},
                new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80}, new byte[]{(byte) 0xff, (byte) 0xfe, '{', 0, '}', 0});
    }

    @ParameterizedTest @ValueSource(strings = {"\\uD800", "\\uDC00", "\\uD800x", "\\uD800\\uD800"})
    void rejectsUnpairedEscapedSurrogatesBeforeAnyLegacyClassification(String escaped) {
        invalidBody(catchThrowable(() -> parse(List.of(), business().toString().replace(PASSWORD, escaped))));
        invalidBody(catchThrowable(() -> parse(List.of(), business().toString().replace("password", "pass" + escaped + "word"))));
    }

    @Test void bodyFailuresAreSanitizedAndDoNotCloseTheOwnedStream() {
        var closes = new AtomicInteger();
        var body = new InputStream() {
            @Override public int read() throws IOException { throw new IOException(PASSWORD + " body-with-private-values"); }
            @Override public void close() { closes.incrementAndGet(); }
        };
        invalidBody(catchThrowable(() -> LegalRegistrationRequests.read(List.of(), body, validator)));
        assertThat(closes).hasValue(0);
        invalidBody(catchThrowable(() -> LegalRegistrationRequests.read(List.of(), null, validator)));
    }

    @Test void parsedAndLegalListsAreImmutableAndTheirOwnDiagnosticsAreRedacted() {
        var body = new OwnedStream(complete(act("ACEPTACION", documents(1), true)).getBytes(StandardCharsets.UTF_8));
        var parsed = LegalRegistrationRequests.read(List.of(KEY), body, validator);
        assertThat(body.closed).isFalse();
        assertThat(parsed.toString()).isEqualTo("Parsed[redacted]");
        assertThatThrownBy(() -> parsed.acceptances().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> parsed.acceptances().getFirst().documentos().clear()).isInstanceOf(UnsupportedOperationException.class);
        var mutable = new ArrayList<>(parsed.acceptances());
        var copied = new LegalRegistrationRequests.Parsed(parsed.kind(), parsed.registration(), KEY, REVISION, mutable);
        mutable.clear(); assertThat(copied.acceptances()).hasSize(1);
        assertThat(parsed.acceptances().getFirst().toString()).isEqualTo("Acceptance[redacted]");
    }

    @Test void validatorFailuresRemainOperationalEvenWhenTheyResembleProtocolRejections() {
        for (RuntimeException operational : List.of(new ValidationException(PASSWORD), LegalRegistrationHttpException.invalidKey())) {
            var broken = mock(Validator.class); when(broken.validate(any())).thenThrow(operational);
            assertThat(catchThrowable(() -> LegalRegistrationRequests.read(List.of(), stream(business().toString()), broken)))
                    .isSameAs(operational);
        }
    }

    @Test void validationRunsOnceAndItsElapsedBudgetIsObservedBeforeRejectingTheDto() {
        var controlled = mock(Validator.class);
        var calls = new AtomicInteger();
        when(controlled.validate(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return validator.validate(invocation.getArgument(0));
        });
        var operational = new IllegalStateException("Synthetic expired validation budget");
        String invalid = complete("").replace(PASSWORD, "short");
        assertThat(catchThrowable(() -> LegalRegistrationRequests.read(List.of(KEY), stream(invalid), controlled, () -> {
            if (calls.get() == 1) throw operational;
        }))).isSameAs(operational);
        assertThat(calls).hasValue(1);
        var failure = genericError(catchThrowable(() -> LegalRegistrationRequests.read(List.of(KEY), stream(invalid), controlled)));
        assertThat(failure.error()).isEqualTo("Error de validación");
        LegalRegistrationRequests.read(List.of(KEY), stream(complete("")), controlled);
        // One provider invocation per request, even when legal materialization uses a second pass.
        assertThat(calls).hasValue(3); verify(controlled, times(3)).validate(any());
    }

    @Test void checkpointsInterruptBoundedReadsAndRetainTheirOriginalException() {
        byte[] bytes = (complete("") + " ".repeat(20_000)).getBytes(StandardCharsets.UTF_8);
        var delegate = new ByteArrayInputStream(bytes); var reads = new AtomicInteger(); var closes = new AtomicInteger();
        var body = new InputStream() {
            @Override public int read() { throw new AssertionError("Expected bulk reads"); }
            @Override public int read(byte[] target, int offset, int length) {
                assertThat(length).isLessThanOrEqualTo(8_192); reads.incrementAndGet();
                return delegate.read(target, offset, Math.min(1_024, length));
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        var operational = LegalRegistrationHttpException.invalidKey();
        assertThat(catchThrowable(() -> LegalRegistrationRequests.read(List.of(KEY), body, validator, () -> {
            if (reads.get() == 2) throw operational;
        }))).isSameAs(operational);
        assertThat(reads).hasValue(2); assertThat(closes).hasValue(0); assertThat(delegate.available()).isEqualTo(bytes.length - 2_048);
    }

    @Test void cooperativeChecksAlsoCoverTheMaterializationPass() {
        String body = complete(act("ACEPTACION", documents(1), true));
        var firstChecks = new AtomicInteger();
        error(catchThrowable(() -> LegalRegistrationRequests.read(List.of(), stream(body), validator, firstChecks::incrementAndGet)),
                "IDEMPOTENCY_KEY_REQUERIDA");
        var checks = new AtomicInteger(); var operational = new IllegalStateException("Synthetic second-pass stop");
        assertThat(catchThrowable(() -> LegalRegistrationRequests.read(List.of(KEY), stream(body), validator, () -> {
            if (checks.incrementAndGet() > firstChecks.get()) throw operational;
        }))).isSameAs(operational);
    }

    private static ObjectNode business() {
        var value = JSON.createObjectNode(); value.put("nombreTaller", "Taller sintético"); value.put("telefonoTaller", "1100000000");
        value.put("nombreAdmin", "Admin"); value.put("email", "test@synthetic.invalid"); value.put("password", PASSWORD); return value;
    }
    private static String complete(String acts) {
        String business = business().toString();
        return business.substring(0, business.length() - 1) + ",\"requiredSetRevision\":\"" + REVISION
                + "\",\"aceptacionesLegales\":[" + acts + "]}";
    }
    private static String act(String type, String docs, boolean confirmed) {
        return "{\"requisitoVersionId\":\"" + REQUIREMENT + "\",\"tipoActo\":\"" + type
                + "\",\"afirmacionSha256\":\"" + DIGEST + "\",\"documentos\":" + docs + ",\"confirmado\":" + confirmed + '}';
    }
    private static String documents(int count) {
        String one = "{\"documentoVersionId\":\"" + DOCUMENT + "\",\"sha256\":\"" + DIGEST + "\"}";
        return '[' + String.join(",", Collections.nCopies(count, one)) + ']';
    }
    private static ByteArrayInputStream stream(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private static LegalRegistrationRequests.Parsed parse(List<String> headers, String body) {
        return LegalRegistrationRequests.read(headers, stream(body), validator);
    }
    private static LegalRegistrationHttpException error(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(LegalRegistrationHttpException.class);
        var failure = (LegalRegistrationHttpException) thrown;
        assertThat(failure.status()).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.toString()).doesNotContain(PASSWORD, KEY, REVISION, DIGEST);
        return failure;
    }
    private static LegalRegistrationHttpException genericError(Throwable thrown) {
        var failure = error(thrown, null); assertThat(failure.details()).isNull(); return failure;
    }
    private static void invalidBody(Throwable thrown) {
        assertThat(genericError(thrown).getMessage()).isEqualTo("El cuerpo de la solicitud es inválido o tiene un formato incorrecto.");
    }
    private static void payloadError(Throwable thrown) {
        assertThat(error(thrown, "ACEPTACION_LEGAL_INVALIDA").details())
                .isEqualTo(Map.of("motivos", List.of("PAYLOAD_LEGAL_INCOMPLETO")));
    }
    private static final class OwnedStream extends ByteArrayInputStream {
        boolean closed;
        OwnedStream(byte[] value) { super(value); }
        @Override public void close() { closed = true; }
    }
}
