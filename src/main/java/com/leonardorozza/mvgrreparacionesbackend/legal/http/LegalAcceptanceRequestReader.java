package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceInput;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceInputException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/** Defers every request observation until the service has observed its persisted actor. */
final class LegalAcceptanceRequestReader {
    private LegalAcceptanceRequestReader() { }

    static LegalAcceptanceInput.Reader from(HttpServletRequest request, LegalRequestMetadataResolver metadataResolver) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(metadataResolver, "metadataResolver");
        return checkpoint -> {
            // Preserve even an unusual operational exception whose type resembles a parser rejection.
            Runnable operational = () -> {
                try { checkpoint.run(); }
                catch (RuntimeException failure) { throw new OperationalCheckpoint(failure); }
            };
            final LegalAcceptanceRequests.Parsed parsed;
            try {
                operational.run();
                List<String> headers = atMostTwo(request.getHeaders("Idempotency-Key"));
                try {
                    parsed = LegalAcceptanceRequests.read(headers, lazyBody(request), operational);
                } catch (LegalAcceptanceHttpException rejection) {
                    throw neutral(rejection);
                }
            } catch (OperationalCheckpoint interrupted) {
                throw interrupted.original;
            }
            // Capture errors are operational. They cannot be converted into a client payload error.
            checkpoint.run();
            var metadata = metadataResolver.resolve(request);
            checkpoint.run();
            return new LegalAcceptanceInput(parsed.idempotencyKey(), parsed.requiredSetRevision(),
                    parsed.acceptances(), metadata);
        };
    }

    private static InputStream lazyBody(HttpServletRequest request) {
        return new InputStream() {
            private InputStream delegate;
            private InputStream opened() throws IOException {
                if (delegate == null) {
                    requireProtocol(request);
                    delegate = Objects.requireNonNull(request.getInputStream(), "servlet input");
                }
                return delegate;
            }
            @Override public int read() throws IOException { return opened().read(); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                return opened().read(bytes, offset, length);
            }
            // The servlet owns the stream; no delegate is closed by this adapter.
        };
    }

    private static void requireProtocol(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) throw LegalAcceptanceHttpException.invalidPayload();
        List<String> values = atMostTwo(request.getHeaders(HttpHeaders.CONTENT_TYPE));
        if (values == null || values.size() != 1 || values.getFirst() == null) {
            throw LegalAcceptanceHttpException.invalidPayload();
        }
        String value = values.getFirst();
        if (value.length() > 256) throw LegalAcceptanceHttpException.invalidPayload();
        int separators = parameterSeparators(value);
        if (separators > 1) throw LegalAcceptanceHttpException.invalidPayload();
        MediaType contentType = MediaType.parseMediaType(value);
        if (!"application".equalsIgnoreCase(contentType.getType()) || !"json".equalsIgnoreCase(contentType.getSubtype())
                || contentType.getParameters().size() != separators
                || (!contentType.getParameters().isEmpty() && !contentType.getParameters().containsKey("charset"))
                || (contentType.getCharset() != null && !StandardCharsets.UTF_8.equals(contentType.getCharset()))) {
            throw LegalAcceptanceHttpException.invalidPayload();
        }
    }

    private static int parameterSeparators(String value) {
        int separators = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isISOControl(current) && current != '\t') throw LegalAcceptanceHttpException.invalidPayload();
            if (escaped) { escaped = false; continue; }
            if (quoted && current == '\\') { escaped = true; continue; }
            if (current == '"') quoted = !quoted;
            else if (current == ';' && !quoted) separators++;
        }
        return separators;
    }

    /** Repeated headers are rejected; their unbounded remainder need not be retained. */
    private static List<String> atMostTwo(Enumeration<String> source) {
        if (source == null) return null;
        List<String> values = new ArrayList<>(2);
        while (values.size() < 2 && source.hasMoreElements()) values.add(source.nextElement());
        return values;
    }

    private static LegalAcceptanceInputException neutral(LegalAcceptanceHttpException rejection) {
        LegalAcceptanceInputException.Reason reason = switch (rejection.code() == null ? "" : rejection.code()) {
            case "IDEMPOTENCY_KEY_REQUERIDA" -> LegalAcceptanceInputException.Reason.REQUIRED_KEY;
            case "IDEMPOTENCY_KEY_INVALIDA" -> LegalAcceptanceInputException.Reason.INVALID_KEY;
            case "ACEPTACION_LEGAL_INVALIDA" -> LegalAcceptanceInputException.Reason.INVALID_PAYLOAD;
            default -> throw rejection;
        };
        return new LegalAcceptanceInputException(reason);
    }

    private static final class OperationalCheckpoint extends RuntimeException {
        private final RuntimeException original;
        private OperationalCheckpoint(RuntimeException original) {
            super("La lectura legal fue interrumpida.", null, false, false);
            this.original = original;
        }
    }
}
