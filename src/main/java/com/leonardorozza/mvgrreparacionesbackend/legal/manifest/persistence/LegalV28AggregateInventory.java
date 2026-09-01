package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable PostgreSQL 16 inventory for the V28 aggregate persistence boundary. */
final class LegalV28AggregateInventory {

    static final String DEFAULT_SCHEMA = LegalV27EditorialInventory.DEFAULT_SCHEMA;
    static final String FLYWAY_HISTORY_TABLE =
            LegalV27EditorialInventory.FLYWAY_HISTORY_TABLE;
    static final String FLYWAY_VERSION_V27 = LegalV27EditorialInventory.FLYWAY_VERSION;
    static final String FLYWAY_TYPE = LegalV27EditorialInventory.FLYWAY_TYPE;
    static final String FLYWAY_SCRIPT_V27 = LegalV27EditorialInventory.FLYWAY_SCRIPT;
    static final int FLYWAY_CHECKSUM_V27 = LegalV27EditorialInventory.FLYWAY_CHECKSUM;
    static final String FLYWAY_VERSION_V28 = "28";
    static final String FLYWAY_SCRIPT_V28 =
            "V28__persistencia_revision_legal_agregada.sql";

    /** Frozen from the final V28 resource through a clean PostgreSQL 16 Flyway migration. */
    static final int FLYWAY_CHECKSUM_V28 = 1_900_377_028;

    /**
     * V28-owned and V28-affected relations not already accredited by the V27 import/editorial
     * verifiers. The six acceptance relations keep their historical rows but participate in the
     * aggregate acceptance graph.
     */
    static final List<String> CATALOG_TABLES = List.of(
            "legal_requisito_agregados",
            "legal_requisito_agregado_scopes",
            "legal_aceptacion_lotes",
            "legal_aceptaciones",
            "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos",
            "legal_aceptacion_metadatos_cifrados",
            "legal_idempotencia_resultados");

    /** Runtime and migration-evidence relations consumed outside the legal table namespace. */
    static final List<String> RELATION_BOUNDARY_DEPENDENCIES = List.of(
            "users",
            FLYWAY_HISTORY_TABLE);

    /** Complete V27 plus V28 graph whose rows must not be rewritten or inherited. */
    static final List<String> LEGAL_GRAPH_TABLES = legalGraphTableInventory();

    /** Exact read surface of the isolated aggregate materializer role. */
    static final Set<String> EDITORIAL_TABLES = Set.of(
            "legal_requisito_conjuntos_actuales",
            "legal_requisito_conjuntos",
            "legal_requisito_agregados",
            "legal_requisito_agregado_scopes");

    /** Exact DML surface of the isolated aggregate materializer role. */
    static final Set<String> INSERT_TABLES = Set.of(
            "legal_requisito_agregados",
            "legal_requisito_agregado_scopes");
    static final Set<String> DELETE_TABLES = Set.of();

    /**
     * PostgreSQL requires one UPDATE-capable column for {@code SELECT ... FOR SHARE OF actual}.
     * The V27 immutable guard rejects every real or no-op UPDATE on this projection.
     */
    static final Map<String, Set<String>> UPDATE_COLUMNS = Map.of(
            "legal_requisito_conjuntos_actuales", Set.of("conjunto_id"));

    static final Map<String, String> IDENTITY_SEQUENCES = Map.of(
            "legal_aceptacion_documentos_id_seq",
                    "legal_aceptacion_documentos.id",
            "legal_aceptacion_metadatos_cifrados_id_seq",
                    "legal_aceptacion_metadatos_cifrados.id",
            "legal_idempotencia_resultados_id_seq",
                    "legal_idempotencia_resultados.id");

    /** UUID-backed aggregate inserts never consume a PostgreSQL sequence. */
    static final Set<String> WRITABLE_SEQUENCES = Set.of();

    static final Map<String, FunctionSpec> V28_FUNCTIONS = Map.ofEntries(
            function("legal_exigir_lock_editorial_v28()",
                    "c3f7ce6f17bba57a2e8d4ff9354c4b5110cfc6c56dfc4fe67e30d8d047cecceb"),
            function("legal_requisito_agregado_insert_guard()",
                    "be067608b4d4192da8d758afd75d1f7bd8e75b49a952a2870fd6d470bfcf341b"),
            function("legal_requisito_agregado_scope_insert_guard()",
                    "1e685d406ea26bff8896a5d2944c2096586c8405d13bc979f9bef5ad11dc0074"),
            function("legal_validar_requisito_agregado(uuid)",
                    "484ae3c3e7e8d8e31ccf9c9684b58e3010222d3ca7f47b60c788986614b8c198"),
            function("legal_requisito_agregado_constraint_guard()",
                    "28bdcc5bd511878ad8fd08cca6217707c4362e5fbeda4f8c8a6243a52251d8d7"),
            function("legal_validar_requisito_agregado_actual(uuid)",
                    "de63c40dd3dbbb2533c617e559b20919b9dd79dd0a471974f9fe6629f616a109"),
            function("legal_aceptacion_lote_insert_guard()",
                    "9ddd2282b9cb7ec532a6c685e89b03f21db2a280bacd33f63a3ef59f08fe631e"),
            function("legal_aceptacion_insert_guard()",
                    "c23e76d804198b11c0e9b64767b9468dd5cbd970c1846a5ecc96ad4d7b519422"),
            function("legal_aceptacion_agregado_constraint_guard()",
                    "0650e8f5d1b1cc4560dd0a26bed98bfb4b8fd6368613fc519a6077a1ae75acf3"));

    /** Legacy acceptance/evidence functions still reachable from the V28 trigger graph. */
    static final Map<String, FunctionSpec> LEGACY_ACCEPTANCE_FUNCTIONS = Map.ofEntries(
            function("legal_fila_es_transaccion_actual(xid)",
                    "9b2d7e85206e1a2b8264f1f71c8714922c29ccabcabb68b3072a203f8ed72683"),
            function("legal_aceptacion_documento_insert_guard()",
                    "42bf500f81bf6d69acd027d420bd92fba705adb580efdafa8c52a58866194fac"),
            function("legal_metadata_header_insert_guard()",
                    "6ba67c948420ee4b7d406a092da7570737594c657f0a10de3366d2f8042f7921"),
            function("legal_metadata_cifrada_insert_guard()",
                    "65bb4b109e19c4848af97eff959f5f4208f31ce2b1803da43f1a1705a36e9ec5"),
            function("legal_validar_aceptacion(uuid)",
                    "13c1bae57452576d4745fa4d0f07b76c75f774cebf1dd73b1c0a1b151335ff45"),
            function("legal_validar_lote_aceptacion(uuid)",
                    "0b2723a7feb43c72113221fb46200b357640d8a3695db193ea52c3e82a17f247"),
            function("legal_aceptacion_constraint_guard()",
                    "3694470195fef6bdcfc931667124e49608222b406ce16da74441d0f629f3709e"),
            function("legal_metadata_cifrada_update_guard()",
                    "410b9389878361ab74f66c9428a05d9ba52757e093f6cf0198a0fc0f8143dbaa"),
            function("legal_metadata_header_update_guard()",
                    "e10ce66085b48ca017ef5f7642fe99dc8bbf6fb1fd77cf88fd2f9f25931c70ca"),
            function("legal_idempotencia_insert_guard()",
                    "7468cd844db357dddf893f564d29163ac1567b76e096b64c801491bddf86f1fb"),
            function("legal_idempotencia_update_delete_guard()",
                    "3813bef373b7b177050638d716a5ef82705c112347430117d563bc848375e04a"));

    /** Complete function surface that protects the eight V28-accredited relations. */
    static final Map<String, FunctionSpec> SCHEMA_FUNCTIONS = schemaFunctionInventory();

    static final Set<String> VOID_FUNCTIONS = Set.of(
            "legal_exigir_lock_editorial_v28()",
            "legal_validar_requisito_agregado(uuid)",
            "legal_validar_requisito_agregado_actual(uuid)",
            "legal_validar_aceptacion(uuid)",
            "legal_validar_lote_aceptacion(uuid)");

    static final Set<String> SQL_FUNCTIONS = Set.of(
            "legal_fila_es_transaccion_actual(xid)");

    /** V27 dependency plus the complete V28 function surface checked by the role verifier. */
    static final Map<String, FunctionSpec> EDITORIAL_FUNCTIONS =
            aggregateRoleFunctionInventory();

    /** Exact SECURITY INVOKER call graph reachable by aggregate materialization. */
    static final Set<String> PRIVILEGED_FUNCTIONS = Set.of(
            "legal_rechazar_update_delete()",
            "legal_exigir_read_committed()",
            "legal_exigir_lock_editorial_v28()",
            "legal_requisito_agregado_insert_guard()",
            "legal_requisito_agregado_scope_insert_guard()",
            "legal_validar_requisito_agregado(uuid)",
            "legal_requisito_agregado_constraint_guard()");

    /** Frozen from a clean PostgreSQL 16 catalog; values are schema-independent. */
    static final CatalogFingerprint EXPECTED_CATALOG = new CatalogFingerprint(
            8,
            "a22262b2fda50aa3ed2a8e8bba5886d60b73c6e7ca10d25c24d1607641a401d5",
            73,
            "bef14c0ff3fcda14c50e559ad446ac88b3ae94831e09d6188b471ed09d564f0b",
            75,
            "dc7eb059a84c601924380310166df488a5e5ed562f5ff21dcdfd3bc6a682f522",
            30,
            "0420bf78aebffe7f2777dbf433b5b717f921d045511e647f8eb0d37fe8fdfde4",
            27,
            "5af75cf387b32f69a8b43726a2f9adeea91f4034cf3604891f0ed298736b33b6",
            3,
            "419f8ef41627f77b98aa9849cce4bcd0775a831eb4cd8402df499fc73702c846");

    private LegalV28AggregateInventory() { }

    private static List<String> legalGraphTableInventory() {
        ArrayList<String> tables = new ArrayList<>(
                LegalV27EditorialInventory.EDITORIAL_TABLES);
        tables.addAll(CATALOG_TABLES);
        tables.addAll(RELATION_BOUNDARY_DEPENDENCIES);
        if (Set.copyOf(tables).size() != tables.size()) {
            throw new IllegalStateException(
                    "El inventario legal V27+V28 contiene relaciones duplicadas");
        }
        return List.copyOf(tables);
    }

    private static Map<String, FunctionSpec> schemaFunctionInventory() {
        LinkedHashMap<String, FunctionSpec> functions = new LinkedHashMap<>();
        functions.putAll(V28_FUNCTIONS);
        functions.putAll(LEGACY_ACCEPTANCE_FUNCTIONS);
        return Map.copyOf(functions);
    }

    private static Map<String, FunctionSpec> aggregateRoleFunctionInventory() {
        LinkedHashMap<String, FunctionSpec> functions = new LinkedHashMap<>();
        for (String signature : List.of(
                "legal_rechazar_update_delete()",
                "legal_exigir_read_committed()")) {
            LegalV27EditorialInventory.FunctionSpec v27 =
                    LegalV27EditorialInventory.EDITORIAL_FUNCTIONS.get(signature);
            if (v27 == null) {
                throw new IllegalStateException(
                        "El inventario V27 no contiene " + signature);
            }
            functions.put(
                    v27.signature(),
                    new FunctionSpec(v27.signature(), v27.sourceSha256()));
        }
        functions.putAll(V28_FUNCTIONS);
        return Map.copyOf(functions);
    }

    private static Map.Entry<String, FunctionSpec> function(
            String signature,
            String sourceSha256) {
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
