package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Public document metadata, without Markdown, requirements or editorial provenance. */
public record LegalDocumentSummary(
        UUID versionId,
        TipoDocumentoLegal type,
        String version,
        String title,
        String sha256,
        Instant effectiveAt,
        EstadoVersionLegal state,
        LocaleLegal locale
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Instant FIRST_RFC3339_INSTANT = Instant.parse("0000-01-01T00:00:00Z");
    private static final Instant AFTER_LAST_RFC3339_INSTANT = Instant.parse("+10000-01-01T00:00:00Z");

    public LegalDocumentSummary {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(type, "type");
        requirePersistedText(version, 64, "version");
        requirePersistedText(title, 300, "title");
        if (!SHA256.matcher(Objects.requireNonNull(sha256, "sha256")).matches()) {
            throw new IllegalArgumentException("sha256 debe contener 64 hexadecimales minúsculos");
        }
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        if (effectiveAt.getNano() % 1_000 != 0
                || effectiveAt.isBefore(FIRST_RFC3339_INSTANT)
                || !effectiveAt.isBefore(AFTER_LAST_RFC3339_INSTANT)) {
            throw new IllegalArgumentException(
                    "effectiveAt debe ser RFC 3339 UTC con precisión de microsegundos");
        }
        Objects.requireNonNull(state, "state");
        if (state != EstadoVersionLegal.VIGENTE
                && state != EstadoVersionLegal.REEMPLAZADA
                && state != EstadoVersionLegal.RETIRADA) {
            throw new IllegalArgumentException("El resumen documental requiere un estado público");
        }
        Objects.requireNonNull(locale, "locale");
    }

    /** Shared render for canonical bytes and the future public DTO; never rounds an instant. */
    public String effectiveAtUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(effectiveAt);
    }

    private static void requirePersistedText(String value, int maximumCodePoints, String field) {
        Objects.requireNonNull(value, field);
        // VARCHAR and btrim(text) in V27 count code points and trim only U+0020 by default.
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maximumCodePoints
                || value.chars().allMatch(character -> character == ' ')) {
            throw new IllegalArgumentException(field + " no respeta el contrato documental persistido");
        }
        // Preserve original text, including escapes and combining marks, without normalization.
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == 0 || Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " contiene texto PostgreSQL/Unicode inválido");
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " contiene Unicode inválido");
                }
                index++;
            }
        }
    }
}
