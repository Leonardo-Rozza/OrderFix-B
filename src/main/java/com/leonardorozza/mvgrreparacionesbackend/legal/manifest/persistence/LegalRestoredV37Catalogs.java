package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;

import java.util.List;

/**
 * Exact public/V37 catalogs rebuilt by PostgreSQL 16 pg_dump/pg_restore.
 * Original migration fingerprints stay frozen. No SQL normalization, learned baseline,
 * privilege bypass or recovery/reopening authorization is provided by these alternatives.
 */
final class LegalRestoredV37Catalogs {
    // Independently captured by LegalV37RestoreSchemaSnapshot and exercised by real restore ITs.
    static final LegalV27ImportInventory.CatalogFingerprint IMPORT = new LegalV27ImportInventory.CatalogFingerprint(
            12, "d3b0a50cb6cbdf0a0a8eab97bd10ae0e1f8e605ce009c6083ae77e1827257ad9",
            93, "71ce2628bcac127f8798bbd5098563c8bd3a43c8fc505bd7794dfaa726ae96a7",
            97, "1f1be3cb84257e53627750ebe40699874aba19b9c3c5dfaed3d47d142145eaf0",
            29, "da03775969cf6c3695fa8ef10553f7e895a3019b2ed625efae672ebbb88ab20c",
            6, "308609421640e4120de7cf8621a605b541287c0808e9b44ca0f64f782df2874e");
    static final LegalV27EditorialInventory.CatalogFingerprint EDITORIAL = new LegalV27EditorialInventory.CatalogFingerprint(
            19, "75d5f6e901a16eee4c17b0f3cde6f1306870ff60d01f2d07960e9dbb74b53c91",
            130, "96f80a77a192335c8a3dc5b8c37ad493426502a98d401c83b13059de853ce705",
            130, "929b6329536c0f0ea22e00a458db5845be9722e0cedbdad319a66e2f4d395218",
            58, "d2233bdaacc69c01ed19ebcb7dfe46ca34249891fe8a0a18029806726c90a52d",
            10, "51080ba2c1cf9351dc94dc61ba6f303ae8e2234748b5d4738bc8d6c33f10b860");
    static final LegalV29AcceptanceInventory.CatalogFingerprint ACCEPTANCE = new LegalV29AcceptanceInventory.CatalogFingerprint(
            12, "28c4c52db8902f5097d957b7f7ad1263e7398eec719c482816e5ed41e194adb5",
            125, "1b2af7407adbff4f9236d83dc1f0a93627cfe295647a6dfe6e745da5614a4c1b",
            100, "cb327581c9af8910b3e94b98d2bb814973c4dc651a248ec9aa9a9e29cfbb2925",
            43, "f67d6a99f2c90d9b2e31b3b82f0b0a05201dbec2da1d089ccb08f4e5be347235",
            49, "76d982d5b3c6260d065d67c6ae52c4810fc69a017f9671b892b488699cef9e61",
            5, "7df57cfa66f5f92adc282732fc09a25a1a76407665b20793f7e729fd8712cdbf");

    // A whole tuple is accepted, never an independent migrated/restored choice for each delta.
    static final List<String> CLOSURE_DELTAS = List.of(
            "7c5d4173fbeb1a3b8cb32d1697431f02049c69161d1d7cf3ddc2d2e99b37fcb6",
            "651d041810178a4819d0b74911fff38eb525fc28f1edef774640f8b61ec02829",
            "1f87ce70ba546cdf7174c0d4363b57f09c5f7eee5ed2d4dcdc3c50dcfee3d4cd",
            "8286a4003e05b279f250fed4323e618ed9de3d6bf0b1d42c459095551fe8fff1");
    static final String PHOTO = "30ea186928151a86bc29f2fe9bfb541794a8a794d77d0070193d5cfdf355e578";

    private LegalRestoredV37Catalogs() { }

    /** History-only dispatch; deliberately does not call verify() or recurse into the V27 bases. */
    static boolean hasExactV37History(JdbcTemplate jdbc, String schema) {
        if (!"public".equals(schema)) return false;
        try {
            return new LegalV29AcceptanceSchemaVerifier(jdbc, schema).workshopClosureSchemaVersion() == 37;
        } catch (LegalEditorialOperationalException failure) {
            // Each historical caller retains its own schema-error contract (including import/dry-run).
            if (failure.issue().code() == LegalManifestIssueCode.SCHEMA_DRIFT) return false;
            throw failure;
        }
    }
}
