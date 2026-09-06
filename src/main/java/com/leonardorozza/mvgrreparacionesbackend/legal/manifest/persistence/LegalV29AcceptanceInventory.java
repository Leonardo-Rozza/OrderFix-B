package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** PostgreSQL 16 inventory for V29; it never changes the frozen V27/V28 inventories. */
final class LegalV29AcceptanceInventory {

    static final String DEFAULT_SCHEMA = LegalV28AggregateInventory.DEFAULT_SCHEMA;
    static final String FLYWAY_HISTORY_TABLE = LegalV28AggregateInventory.FLYWAY_HISTORY_TABLE;
    static final String FLYWAY_TYPE = LegalV28AggregateInventory.FLYWAY_TYPE;
    static final String FLYWAY_VERSION_V27 = LegalV28AggregateInventory.FLYWAY_VERSION_V27;
    static final String FLYWAY_SCRIPT_V27 = LegalV28AggregateInventory.FLYWAY_SCRIPT_V27;
    static final int FLYWAY_CHECKSUM_V27 = LegalV28AggregateInventory.FLYWAY_CHECKSUM_V27;
    static final String FLYWAY_VERSION_V28 = LegalV28AggregateInventory.FLYWAY_VERSION_V28;
    static final String FLYWAY_SCRIPT_V28 = LegalV28AggregateInventory.FLYWAY_SCRIPT_V28;
    static final int FLYWAY_CHECKSUM_V28 = LegalV28AggregateInventory.FLYWAY_CHECKSUM_V28;
    static final String FLYWAY_VERSION_V29 = "29";
    static final String FLYWAY_SCRIPT_V29 = "V29__resultados_aceptacion_idempotente.sql";

    /** Frozen from the V29 resource migrated on clean PostgreSQL 16. */
    static final int FLYWAY_CHECKSUM_V29 = 2141641921;

    static final List<String> V29_TABLES = List.of(
            "legal_idempotencia_sin_actos",
            "legal_idempotencia_sin_actos_referencias");
    static final List<String> ACCOUNT_TABLES = List.of("users", "talleres");

    /** Eight aggregate/evidence tables, two new ledgers and the two actor dependencies. */
    static final List<String> CATALOG_TABLES = catalogTableInventory();

    /** Complete legal graph plus account dependencies and migration history; no duplicates. */
    static final List<String> LEGAL_GRAPH_TABLES = legalGraphTableInventory();

    /** V29 adds UUID keys; account identity sequences still belong to the accredited catalog. */
    static final Map<String, String> IDENTITY_SEQUENCES = identitySequenceInventory();

    /** Nominal V29 triggers, also checked for absence when a historical version is selected. */
    static final Set<String> V29_TRIGGER_NAMES = Set.of(
            "trg_legal_00_idempotencia_tupla_v29",
            "trg_legal_10_idem_sin_actos_insert_v29",
            "trg_legal_20_idem_sin_actos_mutation_v29",
            "ct_legal_idem_sin_actos_completo_v29",
            "trg_legal_idem_sin_actos_ref_insert_v29",
            "trg_legal_idem_sin_actos_ref_update_v29",
            "trg_legal_idem_sin_actos_ref_delete_v29",
            "ct_legal_idem_sin_actos_ref_completa_v29",
            "trg_legal_users_identidad_inmutable_v29",
            "trg_legal_talleres_identidad_inmutable_v29");

    /** Exact normalized source hashes captured by verifier.snapshot() on PostgreSQL 16. */
    static final Map<String, FunctionSpec> V29_FUNCTIONS = Map.ofEntries(
            function("legal_exigir_lock_idempotente_v29(character varying, character varying, character varying, character varying)", "fd017f41a57c7d0c5250a7f1288892caeb34b1f616142cc455f98c171a1157cd"),
            function("legal_idempotencia_tupla_guard_v29()", "07b08947c3c7e0c780a9fd503c83d4eb6abd8f07342f3b9fd67f4d4dde4ca10e"),
            function("legal_idempotencia_sin_actos_insert_guard_v29()", "734d7fe6e3e56cc0c3cd2950ba6adfce1eac9e53d0279f4c576dc73235d49274"),
            function("legal_idempotencia_sin_actos_ref_insert_guard_v29()", "5f0ac77623969c059162bc3e79aecd18e5aee936c5ba02e30d40119c7a73c952"),
            function("legal_validar_idempotencia_sin_actos_v29(uuid)", "0ec87a2eb34c5dd72aac06efc4d64e5b8d697cd43a1cc4041e671b6e15c43c1d"),
            function("legal_idempotencia_sin_actos_constraint_guard_v29()", "637780b8e2cc4f78e382e105ce2a0a63704d400aca3481c9ae95e8680d2c3f65"),
            function("legal_idempotencia_sin_actos_mutation_guard_v29()", "0789c8dcd5d5cf4cade74fdc0cbed9f98696d7228f8ba80bb7cb619e609e4ca8"),
            function("legal_idempotencia_sin_actos_ref_delete_guard_v29()", "5062f1c2804a9af6301abf8cbf0ed937b7ed9b2b69c49822a1909bb0e9cb9861"),
            function("legal_idempotencia_sin_actos_ref_update_guard_v29()", "972f7865b1637ebe8d01fd5d1cf019c9ba55e9d0129e9cc5423296a12eb41e50"),
            function("legal_rechazar_update_identidad_cuenta_v29()", "096aa85f0fee359338bdbc5f74c00de7e6448a072d16ee6a5cdeff5d9ac17106"));

    /** Complete V28/evidence chain, shared V27 helpers and the new V29 functions. */
    static final Map<String, FunctionSpec> SCHEMA_FUNCTIONS = schemaFunctionInventory();
    static final Set<String> VOID_FUNCTIONS = voidFunctionInventory();
    static final Set<String> SQL_FUNCTIONS = LegalV28AggregateInventory.SQL_FUNCTIONS;

    /**
     * Canonical PostgreSQL 16 catalog, including legacy evidence, account identity guards and
     * constraint-owned FK triggers. No OIDs or generated internal trigger names enter the hash.
     */
    static final CatalogFingerprint EXPECTED_CATALOG = new CatalogFingerprint(
            12, "28c4c52db8902f5097d957b7f7ad1263e7398eec719c482816e5ed41e194adb5",
            119, "8e24fd597e342462c0032a6b1752f5db2cc16efc3c1b106680bfa3801bf52330",
            96, "91dfe201f7813c3c82ca006802f7a9f85b7616f9ab834937bad3ab11c6ac3e74",
            43, "f67d6a99f2c90d9b2e31b3b82f0b0a05201dbec2da1d089ccb08f4e5be347235",
            38, "aba4594cb91187d860af3024b0ad5aa33cc65045a8e0430e825914dc1a31cd68",
            5, "7df57cfa66f5f92adc282732fc09a25a1a76407665b20793f7e729fd8712cdbf");

    private LegalV29AcceptanceInventory() { }

    private static List<String> catalogTableInventory() {
        ArrayList<String> tables = new ArrayList<>(LegalV28AggregateInventory.CATALOG_TABLES);
        tables.addAll(V29_TABLES);
        tables.addAll(ACCOUNT_TABLES);
        return uniqueTableInventory(tables);
    }

    private static List<String> legalGraphTableInventory() {
        ArrayList<String> tables = new ArrayList<>(LegalV27EditorialInventory.EDITORIAL_TABLES);
        tables.addAll(CATALOG_TABLES);
        tables.add(FLYWAY_HISTORY_TABLE);
        return uniqueTableInventory(tables);
    }

    private static List<String> uniqueTableInventory(List<String> tables) {
        if (Set.copyOf(tables).size() != tables.size()) {
            throw new IllegalStateException("El inventario legal V29 contiene relaciones duplicadas");
        }
        return List.copyOf(tables);
    }

    private static Map<String, String> identitySequenceInventory() {
        LinkedHashMap<String, String> sequences = new LinkedHashMap<>(
                LegalV28AggregateInventory.IDENTITY_SEQUENCES);
        sequences.put("users_id_seq", "users.id");
        sequences.put("talleres_id_seq", "talleres.id");
        return Map.copyOf(sequences);
    }

    private static Map<String, FunctionSpec> schemaFunctionInventory() {
        LinkedHashMap<String, FunctionSpec> functions = new LinkedHashMap<>();
        for (String signature : List.of(
                "legal_rechazar_update_delete()", "legal_exigir_read_committed()")) {
            LegalV27EditorialInventory.FunctionSpec v27 =
                    LegalV27EditorialInventory.EDITORIAL_FUNCTIONS.get(signature);
            if (v27 == null) {
                throw new IllegalStateException("El inventario V27 no contiene " + signature);
            }
            functions.put(signature, new FunctionSpec(signature, v27.sourceSha256()));
        }
        LegalV28AggregateInventory.SCHEMA_FUNCTIONS.forEach((signature, spec) ->
                functions.put(signature, new FunctionSpec(signature, spec.sourceSha256())));
        functions.putAll(V29_FUNCTIONS);
        return Map.copyOf(functions);
    }

    private static Set<String> voidFunctionInventory() {
        LinkedHashSet<String> signatures = new LinkedHashSet<>(
                LegalV28AggregateInventory.VOID_FUNCTIONS);
        signatures.add("legal_exigir_read_committed()");
        signatures.add("legal_exigir_lock_idempotente_v29(character varying, character varying, character varying, character varying)");
        signatures.add("legal_validar_idempotencia_sin_actos_v29(uuid)");
        return Set.copyOf(signatures);
    }

    private static Map.Entry<String, FunctionSpec> function(String signature, String sourceSha256) {
        return Map.entry(signature, new FunctionSpec(signature, sourceSha256));
    }

    record FunctionSpec(String signature, String sourceSha256) { }

    record CatalogFingerprint(
            int tableCount,
            String tablesSha256,
            int columnCount,
            String columnsSha256,
            int constraintCount,
            String constraintsSha256,
            int indexCount,
            String indexesSha256,
            int triggerCount,
            String triggersSha256,
            int sequenceCount,
            String sequencesSha256) { }
}
