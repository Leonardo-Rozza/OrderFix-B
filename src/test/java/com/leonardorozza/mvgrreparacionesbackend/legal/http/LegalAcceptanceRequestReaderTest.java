package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceInputException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

class LegalAcceptanceRequestReaderTest {
    private static final String KEY = "64f89458-294c-4df5-a88d-833a1f1abcde";
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String BODY = "{\"requiredSetRevision\":\"" + REVISION + "\",\"aceptacionesLegales\":[]}";
    private static final LegalRequestMetadataResolver RESOLVER = new LegalRequestMetadataResolver(List.of());

    @Test void buildingTheReaderDoesNotObserveAnyRequestInput() {
        var request = mock(HttpServletRequest.class);
        var reader = LegalAcceptanceRequestReader.from(request, RESOLVER);
        assertThat(reader).isNotNull(); verifyNoInteractions(request);
    }

    @Test void callbackReadsOneBoundedHeaderSnapshotOneOwnedBodyAndThenServerMetadata() {
        var request = request(KEY, BODY);
        AtomicInteger checkpoints = new AtomicInteger();
        var input = LegalAcceptanceRequestReader.from(request, RESOLVER).read(checkpoints::incrementAndGet);
        assertThat(input.idempotencyKey()).isEqualTo(KEY);
        assertThat(input.requiredSetRevision()).isEqualTo(REVISION);
        assertThat(input.acceptances()).isEmpty();
        assertThat(input.metadata().ipAddress()).isEqualTo("192.0.2.8");
        assertThat(input.metadata().userAgent()).isEqualTo("synthetic-user-agent");
        assertThat(request.opens).isEqualTo(1); assertThat(request.body.closed).isFalse();
        assertThat(request.events.indexOf("peer")).isGreaterThan(request.events.indexOf("eof"));
        assertThat(checkpoints.get()).isGreaterThan(4);
        assertThat(input.toString()).isEqualTo("LegalAcceptanceInput[redacted]");
    }

    @ParameterizedTest @ValueSource(strings = {"", "invalid", "64F89458-294C-4DF5-A88D-833A1F1ABCDE"})
    void presentInvalidKeyPrecedesProtocolBodyOpeningAndMetadata(String key) {
        var request = request(key, "invalid JSON"); request.openFailure = true;
        request.wrapped().removeHeader("Content-Type"); request.wrapped().setQueryString("userId=other");
        rejected(request, LegalAcceptanceInputException.Reason.INVALID_KEY);
        assertThat(request.opens).isZero();
        assertThat(request.events).containsExactly("headers:Idempotency-Key");
    }

    @Test void repeatedAndNullHeadersAreRejectedWithoutConsumingAnUnboundedEnumeration() {
        for (List<String> values : List.of(Arrays.asList(KEY, KEY), Arrays.asList((String) null))) {
            var request = request(KEY, BODY); request.keyValues = values;
            rejected(request, LegalAcceptanceInputException.Reason.INVALID_KEY); assertThat(request.opens).isZero();
        }
        var request = request(KEY, BODY); AtomicInteger observed = new AtomicInteger();
        request.keys = new Enumeration<>() {
            @Override public boolean hasMoreElements() { return true; }
            @Override public String nextElement() {
                if (observed.incrementAndGet() > 2) throw new AssertionError("Unbounded header observation");
                return KEY;
            }
        };
        rejected(request, LegalAcceptanceInputException.Reason.INVALID_KEY); assertThat(observed).hasValue(2);
    }

    @Test void missingKeyFollowsJsonShapeAndProtocolButStillPrecedesMetadata() {
        var malformed = request(null, "{"); rejected(malformed, LegalAcceptanceInputException.Reason.INVALID_PAYLOAD);
        var valid = request(null, BODY); rejected(valid, LegalAcceptanceInputException.Reason.REQUIRED_KEY);
        assertThat(valid.events).doesNotContain("peer"); assertThat(valid.body.closed).isFalse();
        var wrongProtocol = request(null, BODY); wrongProtocol.wrapped().removeHeader("Content-Type");
        rejected(wrongProtocol, LegalAcceptanceInputException.Reason.INVALID_PAYLOAD);
        assertThat(wrongProtocol.opens).isZero();
    }

    @Test void bodyOpeningIoFailureIsSanitizedWithoutRetainingOrClosingServletInput() {
        var request = request(KEY, BODY); request.openFailure = true;
        rejected(request, LegalAcceptanceInputException.Reason.INVALID_PAYLOAD);
        assertThat(request.opens).isEqualTo(1); assertThat(request.body.closed).isFalse();
        assertThat(request.events).doesNotContain("peer");
    }

    @ParameterizedTest @ValueSource(strings = {"application/json", "APPLICATION/JSON", "application/json; charset=UTF-8",
            "application/json; CHARSET=\"utf-8\"", "application/json;charset=UTF8", " \tapplication/json\t "})
    void explicitJsonAndUtf8EquivalentMediaTypesAreAccepted(String contentType) {
        var request = request(KEY, BODY); request.wrapped().removeHeader("Content-Type");
        request.wrapped().addHeader("Content-Type", contentType);
        assertThat(LegalAcceptanceRequestReader.from(request, RESOLVER).read(() -> { }).acceptances()).isEmpty();
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"text/plain", "application/problem+json", "application/*",
            "application/json;charset=ISO-8859-1", "application/json;charset=UTF-16", "application/json;version=1",
            "application/json; charset=UTF-8; charset=UTF-8", "application/json; charset=UTF-8; CHARSET=UTF-8",
            "application/json,application/json", "application/json;", "application/json;charset", "application/json;charset=",
            "application/json;charset=\"UTF-8;UTF-16\"", "application/json\r\n", "application/json\u000b", "application/json\u007f"})
    void unsupportedMissingDuplicateOrMalformedMediaTypesArePayloadErrorsBeforeBody(String contentType) {
        var request = request(KEY, BODY); request.wrapped().removeHeader("Content-Type");
        if (contentType != null) request.wrapped().addHeader("Content-Type", contentType);
        rejected(request, LegalAcceptanceInputException.Reason.INVALID_PAYLOAD);
        assertThat(request.opens).isZero(); assertThat(request.events).doesNotContain("peer");
    }

    @Test void mediaTypeHasOneHeaderAndTheRaw256CharacterBound() {
        var request = request(KEY, BODY); request.contentTypeValues = List.of("application/json", "application/json");
        rejected(request, LegalAcceptanceInputException.Reason.INVALID_PAYLOAD);
        assertThat(request.opens).isZero();
        String raw = "application/json" + " ".repeat(256 - "application/json".length());
        var accepted = request(KEY, BODY); accepted.wrapped().removeHeader("Content-Type"); accepted.wrapped().addHeader("Content-Type", raw);
        assertThat(LegalAcceptanceRequestReader.from(accepted, RESOLVER).read(() -> { })).isNotNull();
        var excessive = request(KEY, BODY); excessive.wrapped().removeHeader("Content-Type"); excessive.wrapped().addHeader("Content-Type", raw + ' ');
        rejected(excessive, LegalAcceptanceInputException.Reason.INVALID_PAYLOAD); assertThat(excessive.opens).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"userId=other", "contexto=USO_CONTINUADO", "locale=es-AR", "&", "%", " "})
    void anyNonemptyRawQueryIsRejectedWithoutTriggeringServletParameterParsing(String query) {
        var request = request(KEY, BODY); request.wrapped().setQueryString(query);
        rejected(request, LegalAcceptanceInputException.Reason.INVALID_PAYLOAD); assertThat(request.opens).isZero();
    }

    @Test void anEmptyRawQueryIsAllowed() {
        var request = request(KEY, BODY); request.wrapped().setQueryString("");
        assertThat(LegalAcceptanceRequestReader.from(request, RESOLVER).read(() -> { })).isNotNull();
    }

    @ParameterizedTest @ValueSource(strings = {"peer", "agent", "agent-repeated", "trusted-chain"})
    void metadataFailuresRemainOperationalAfterValidProtocolAndPayload(String defect) {
        var request = request(KEY, BODY);
        LegalRequestMetadataResolver resolver = RESOLVER;
        switch (defect) {
            case "peer" -> request.wrapped().setRemoteAddr("not-an-address");
            case "agent" -> {
                request.wrapped().removeHeader("User-Agent"); request.wrapped().addHeader("User-Agent", "bad\nagent");
            }
            case "agent-repeated" -> request.wrapped().addHeader("User-Agent", "other-agent");
            case "trusted-chain" -> resolver = new LegalRequestMetadataResolver(List.of("192.0.2.0/24"));
            default -> throw new AssertionError(defect);
        }
        var reader = LegalAcceptanceRequestReader.from(request, resolver);
        Throwable failure = catchThrowable(() -> reader.read(() -> { }));
        assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(LegalAcceptanceInputException.class).isNotInstanceOf(LegalAcceptanceHttpException.class);
        assertThat(request.events).contains("eof", "peer"); assertThat(request.body.closed).isFalse();
    }

    @Test void trustedProxySelectionUsesTheResolverSnapshotAndIgnoresFalseLeftPrefixes() {
        var request = request(KEY, BODY); request.wrapped().setRemoteAddr("10.0.0.2");
        request.wrapped().addHeader("X-Forwarded-For", "203.0.113.99, 198.51.100.8, 10.0.0.3");
        var input = LegalAcceptanceRequestReader.from(request, new LegalRequestMetadataResolver(List.of("10.0.0.0/8")))
                .read(() -> { });
        assertThat(input.metadata().ipAddress()).isEqualTo("198.51.100.8");
    }

    @Test void checkpointFailuresAreNeverNeutralizedEvenIfTheyUseAnHttpExceptionType() {
        var request = request(KEY, BODY); AtomicInteger checks = new AtomicInteger();
        var operational = LegalAcceptanceHttpException.invalidKey();
        Throwable failure = catchThrowable(() -> LegalAcceptanceRequestReader.from(request, RESOLVER).read(() -> {
            if (checks.incrementAndGet() > 5) throw operational;
        }));
        assertThat(failure).isSameAs(operational).isNotInstanceOf(LegalAcceptanceInputException.class);
        assertThat(request.events).doesNotContain("peer"); assertThat(request.body.closed).isFalse();
    }

    private static void rejected(ObservedRequest request, LegalAcceptanceInputException.Reason reason) {
        Throwable failure = catchThrowable(() -> LegalAcceptanceRequestReader.from(request, RESOLVER).read(() -> { }));
        assertThat(failure).isInstanceOf(LegalAcceptanceInputException.class);
        assertThat(((LegalAcceptanceInputException) failure).reason()).isEqualTo(reason);
        assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.toString()).doesNotContain(KEY, REVISION, "synthetic", "other", "192.0.2.8");
    }

    private static ObservedRequest request(String key, String body) {
        var request = new MockHttpServletRequest("POST", "/api/aceptaciones-legales");
        request.setRemoteAddr("192.0.2.8"); request.addHeader("Content-Type", "application/json");
        request.addHeader("User-Agent", "synthetic-user-agent");
        if (key != null) request.addHeader("Idempotency-Key", key);
        return new ObservedRequest(request, body);
    }

    private static final class ObservedRequest extends HttpServletRequestWrapper {
        final List<String> events = new ArrayList<>();
        final OwnedBody body;
        int opens;
        boolean openFailure;
        List<String> keyValues;
        List<String> contentTypeValues;
        Enumeration<String> keys;
        ObservedRequest(MockHttpServletRequest request, String body) { super(request); this.body = new OwnedBody(body, events); }
        MockHttpServletRequest wrapped() { return (MockHttpServletRequest) getRequest(); }
        @Override public Enumeration<String> getHeaders(String name) {
            events.add("headers:" + name);
            if (name.equals("Idempotency-Key")) {
                if (keys != null) return keys;
                if (keyValues != null) return Collections.enumeration(keyValues);
            }
            if (name.equals("Content-Type") && contentTypeValues != null) return Collections.enumeration(contentTypeValues);
            return super.getHeaders(name);
        }
        @Override public ServletInputStream getInputStream() throws IOException {
            opens++; events.add("open");
            if (openFailure) throw new IOException("synthetic private input diagnostics");
            return body;
        }
        @Override public String getRemoteAddr() { events.add("peer"); return super.getRemoteAddr(); }
        @Override public Map<String, String[]> getParameterMap() { throw new AssertionError("Do not parse request parameters"); }
        @Override public String getContentType() { throw new AssertionError("Do not collapse repeated Content-Type values"); }
    }

    private static final class OwnedBody extends ServletInputStream {
        final ByteArrayInputStream source;
        final List<String> events;
        boolean closed;
        OwnedBody(String body, List<String> events) { source = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); this.events = events; }
        @Override public int read() { int value = source.read(); if (value < 0) events.add("eof"); return value; }
        @Override public int read(byte[] bytes, int offset, int length) {
            int count = source.read(bytes, offset, length); if (count < 0) events.add("eof"); return count;
        }
        @Override public void close() { closed = true; }
        @Override public boolean isFinished() { return source.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
    }
}
