package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoOperacionIdempotenteLegal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Protected lookup values only; this value neither reserves a key nor proves a durable result. */
public record LegalIdempotencyFingerprint(
        int keyVersion,
        TipoOperacionIdempotenteLegal operation,
        String routeTemplate,
        String scopeHmac,
        String idempotencyKeyHmac,
        String fingerprintHmac
) {
    private static final String REGISTRATION_PATH = "/api/auth/register";
    private static final String ACCEPTANCE_PATH = "/api/aceptaciones-legales";
    private static final String DOMAIN = "ordenfix:legal-idempotency:";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public LegalIdempotencyFingerprint {
        if (keyVersion <= 0 || operation == null
                || !path(operation).equals(routeTemplate)
                || !isDigest(scopeHmac) || !isDigest(idempotencyKeyHmac)
                || !isDigest(fingerprintHmac)) {
            throw new IllegalArgumentException("La huella idempotente tiene una forma inválida");
        }
    }

    /** Derives all three HMAC domains without exporting a canonical body or an unkeyed digest. */
    public static LegalIdempotencyFingerprint derive(
            LegalAcceptanceCommand command, String idempotencyKey, int keyVersion, byte[] secret) {
        Objects.requireNonNull(command, "command");
        LegalAcceptanceCommandValidator.requireIdempotencyKey(idempotencyKey);
        if (keyVersion <= 0 || secret == null || secret.length != 32) {
            throw new IllegalArgumentException("La clave de huella idempotente es inválida");
        }
        TipoOperacionIdempotenteLegal operation = switch (command.operation()) {
            case REGISTRATION -> TipoOperacionIdempotenteLegal.REGISTRO;
            case AUTHENTICATED_ACCEPTANCE -> TipoOperacionIdempotenteLegal.ACEPTACION_LEGAL;
        };
        String route = path(operation);
        Mac mac;
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        } catch (GeneralSecurityException exception) {
            // Provider/configuration details never carry caller input into an HTTP diagnostic.
            throw new IllegalStateException("No se pudo calcular la huella idempotente");
        }
        String scope;
        try (CanonicalMac output = new CanonicalMac(mac)) {
            prefix(output, "scope", route, command);
            output.ascii("]");
            scope = output.finish();
        }
        String key;
        try (CanonicalMac output = new CanonicalMac(mac)) {
            output.ascii("[");
            output.string(DOMAIN + "key:v1");
            output.ascii(",");
            output.string(idempotencyKey);
            output.ascii("]");
            key = output.finish();
        }
        String fingerprint;
        try (CanonicalMac output = new CanonicalMac(mac)) {
            prefix(output, "fingerprint", route, command);
            output.ascii(",");
            business(output, command);
            output.ascii("]");
            fingerprint = output.finish();
        }
        return new LegalIdempotencyFingerprint(keyVersion, operation, route, scope, key, fingerprint);
    }

    /** Compare only complete, canonically encoded MACs without a prefix/early-byte comparison. */
    public boolean matchesFingerprint(String storedFingerprint) {
        return isDigest(storedFingerprint) && MessageDigest.isEqual(
                HexFormat.of().parseHex(fingerprintHmac), HexFormat.of().parseHex(storedFingerprint));
    }

    @Override
    public String toString() {
        return "LegalIdempotencyFingerprint[keyVersion=" + keyVersion + ", operation=" + operation + "]";
    }

    private static boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String path(TipoOperacionIdempotenteLegal operation) {
        return switch (operation) {
            case REGISTRO -> REGISTRATION_PATH;
            case ACEPTACION_LEGAL -> ACCEPTANCE_PATH;
        };
    }

    private static void prefix(CanonicalMac output, String domain, String route,
                               LegalAcceptanceCommand command) {
        output.ascii("[");
        output.string(DOMAIN + domain + ":v1");
        output.ascii(",\"POST\",");
        output.string(route);
        output.ascii(",");
        if (command.operation() == LegalAcceptanceCommand.Operation.REGISTRATION) {
            output.ascii("{\"kind\":\"REGISTRATION\"}");
        } else {
            output.ascii("{\"kind\":\"AUTHENTICATED\",\"userId\":");
            output.string(Long.toString(command.actor().userId()));
            output.ascii("}");
        }
    }

    // Fixed keys are emitted in RFC 8785 UTF-16 order. Arrays were copied and sorted by the validator.
    private static void business(CanonicalMac output, LegalAcceptanceCommand command) {
        output.ascii("{\"aceptacionesLegales\":[");
        boolean first = true;
        for (LegalAcceptanceCommand.Acceptance acceptance : command.acceptances()) {
            if (!first) output.ascii(",");
            first = false;
            output.ascii("{\"afirmacionSha256\":");
            output.string(acceptance.afirmacionSha256());
            output.ascii(",\"confirmado\":");
            output.ascii(acceptance.confirmado() ? "true" : "false");
            output.ascii(",\"documentos\":[");
            boolean firstDocument = true;
            for (LegalAcceptanceCommand.Document document : acceptance.documentos()) {
                if (!firstDocument) output.ascii(",");
                firstDocument = false;
                output.ascii("{\"documentoVersionId\":");
                output.string(document.documentoVersionId().toString());
                output.ascii(",\"sha256\":");
                output.string(document.sha256());
                output.ascii("}");
            }
            output.ascii("],\"requisitoVersionId\":");
            output.string(acceptance.requisitoVersionId().toString());
            output.ascii(",\"tipoActo\":");
            output.string(acceptance.tipoActo().name());
            output.ascii("}");
        }
        output.ascii("]");
        LegalAcceptanceCommand.Registration registration = command.registration();
        if (registration != null) {
            output.ascii(",\"email\":");
            output.string(registration.email());
            output.ascii(",\"nombreAdmin\":");
            output.string(registration.nombreAdmin());
            output.ascii(",\"nombreTaller\":");
            output.string(registration.nombreTaller());
            output.ascii(",\"password\":");
            output.string(registration.password());
        }
        output.ascii(",\"requiredSetRevision\":");
        output.string(command.requiredSetRevision());
        if (registration != null) {
            output.ascii(",\"telefonoTaller\":");
            if (registration.telefonoTaller() == null) output.ascii("null");
            else output.string(registration.telefonoTaller());
        }
        output.ascii("}");
    }

    /** Small scrubbed buffer, never a complete business JSON string/byte array. */
    private static final class CanonicalMac implements AutoCloseable {
        private final Mac mac;
        private final byte[] buffer = new byte[4096];
        private int position;

        private CanonicalMac(Mac mac) {
            this.mac = mac;
        }

        private void ascii(String value) {
            for (int i = 0; i < value.length(); i++) write(value.charAt(i));
        }

        private void string(String value) {
            write('"');
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                switch (current) {
                    case '"' -> ascii("\\\"");
                    case '\\' -> ascii("\\\\");
                    case '\b' -> ascii("\\b");
                    case '\t' -> ascii("\\t");
                    case '\n' -> ascii("\\n");
                    case '\f' -> ascii("\\f");
                    case '\r' -> ascii("\\r");
                    default -> {
                        if (current < 0x20) {
                            ascii("\\u00");
                            write(HEX[(current >>> 4) & 15]);
                            write(HEX[current & 15]);
                        } else if (Character.isHighSurrogate(current)) {
                            if (index + 1 >= value.length()
                                    || !Character.isLowSurrogate(value.charAt(index + 1))) invalidUnicode();
                            utf8(Character.toCodePoint(current, value.charAt(++index)));
                        } else if (Character.isLowSurrogate(current)) {
                            invalidUnicode();
                        } else {
                            utf8(current);
                        }
                    }
                }
            }
            write('"');
        }

        private void utf8(int cp) {
            if (cp <= 0x7f) {
                write(cp);
            } else if (cp <= 0x7ff) {
                write(0xc0 | (cp >>> 6)); write(0x80 | (cp & 0x3f));
            } else if (cp <= 0xffff) {
                write(0xe0 | (cp >>> 12)); write(0x80 | ((cp >>> 6) & 0x3f));
                write(0x80 | (cp & 0x3f));
            } else {
                write(0xf0 | (cp >>> 18)); write(0x80 | ((cp >>> 12) & 0x3f));
                write(0x80 | ((cp >>> 6) & 0x3f)); write(0x80 | (cp & 0x3f));
            }
        }

        private static void invalidUnicode() {
            throw new IllegalArgumentException("El comando idempotente contiene Unicode inválido");
        }

        private void write(int value) {
            buffer[position++] = (byte) value;
            if (position == buffer.length) flush();
        }

        private void flush() {
            mac.update(buffer, 0, position);
            Arrays.fill(buffer, (byte) 0);
            position = 0;
        }

        private String finish() {
            flush();
            return HexFormat.of().formatHex(mac.doFinal());
        }

        @Override
        public void close() {
            Arrays.fill(buffer, (byte) 0);
            position = 0;
            mac.reset();
        }
    }
}
