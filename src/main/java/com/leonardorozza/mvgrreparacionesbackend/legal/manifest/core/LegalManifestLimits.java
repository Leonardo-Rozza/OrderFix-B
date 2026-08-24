package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

/**
 * Límites operativos congelados para el contrato legal v1.
 */
public final class LegalManifestLimits {

    public static final int MAX_MANIFEST_BYTES = 1_048_576;
    public static final int MAX_JSON_DEPTH = 32;
    public static final long MAX_JSON_TOKENS = 100_000L;
    // Defense in depth: the byte gate is tighter for a quoted UTF-8 JSON string, but Jackson
    // independently freezes this limit in case the reader boundary changes in a future version.
    public static final int MAX_JSON_STRING_LENGTH = 1_048_576;
    public static final int MAX_JSON_NAME_LENGTH = 256;
    public static final int MAX_JSON_NUMBER_LENGTH = 128;

    public static final int MAX_DOCUMENTS = 128;
    public static final int MAX_REQUIREMENTS = 256;
    public static final int MAX_DOCUMENTS_PER_REQUIREMENT = 16;
    public static final int MAX_MARKDOWN_BYTES = 1_048_576;
    public static final int MAX_TOTAL_MARKDOWN_BYTES = 16_777_216;
    public static final int MAX_EXPOSED_ISSUES = 200;

    private LegalManifestLimits() {
    }
}
