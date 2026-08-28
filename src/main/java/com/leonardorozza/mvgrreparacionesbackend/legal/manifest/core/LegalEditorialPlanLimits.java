package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

/**
 * Frozen syntactic and collection limits for the version 1 editorial-plan contract.
 *
 * <p>Combined source/target accounting remains a validator responsibility. These constants bound
 * each independently supplied collection and every nested collection before semantic planning.</p>
 */
public final class LegalEditorialPlanLimits {

    public static final int MAX_PLAN_BYTES = 1_048_576;
    public static final int MAX_JSON_DEPTH = 32;
    public static final long MAX_JSON_TOKENS = 100_000L;
    public static final int MAX_JSON_STRING_LENGTH = 1_048_576;
    public static final int MAX_JSON_NAME_LENGTH = 256;
    public static final int MAX_JSON_NUMBER_LENGTH = 128;

    public static final int MAX_DOCUMENTS = 128;
    public static final int MAX_REQUIREMENTS = 256;
    public static final int MAX_REPLACEMENT_BATCHES = 128;
    public static final int MAX_CONTEXTS = 8;
    public static final int MAX_AUDIENCES = 2;
    public static final int MAX_REASON_CODE_POINTS = 1_000;

    private LegalEditorialPlanLimits() {
    }
}
