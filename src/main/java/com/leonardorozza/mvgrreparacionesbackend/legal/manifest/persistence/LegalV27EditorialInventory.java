package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable PostgreSQL 16 inventory for the complete V27 editorial surface. */
final class LegalV27EditorialInventory {

    static final String DEFAULT_SCHEMA = "public";
    static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";
    static final String FLYWAY_VERSION = "27";
    static final String FLYWAY_TYPE = "SQL";
    static final String FLYWAY_SCRIPT = "V27__persistencia_legal_append_only.sql";
    static final int FLYWAY_CHECKSUM = 1_575_269_868;

    /** The first nineteen V27 tables: immutable origin graph plus editorial state. */
    static final List<String> EDITORIAL_TABLES = List.of(
            "legal_publicaciones",
            "legal_documento_reemplazo_lotes",
            "legal_documento_lineas",
            "legal_documento_versiones",
            "legal_documento_contextos",
            "legal_publicacion_documentos",
            "legal_documento_reemplazo_anteriores",
            "legal_documento_reemplazo_sucesoras",
            "legal_documento_transiciones",
            "legal_documento_vigentes",
            "legal_requisito_lineas",
            "legal_requisito_audiencias",
            "legal_requisito_versiones",
            "legal_requisito_documentos",
            "legal_publicacion_requisitos",
            "legal_requisito_transiciones",
            "legal_requisito_conjuntos",
            "legal_requisito_conjunto_miembros",
            "legal_requisito_conjuntos_actuales");

    static final Map<String, String> IDENTITY_SEQUENCES = Map.ofEntries(
            Map.entry(
                    "legal_documento_contextos_id_seq",
                    "legal_documento_contextos.id"),
            Map.entry(
                    "legal_publicacion_documentos_id_seq",
                    "legal_publicacion_documentos.id"),
            Map.entry(
                    "legal_documento_reemplazo_anteriores_id_seq",
                    "legal_documento_reemplazo_anteriores.id"),
            Map.entry(
                    "legal_documento_reemplazo_sucesoras_id_seq",
                    "legal_documento_reemplazo_sucesoras.id"),
            Map.entry(
                    "legal_documento_transiciones_id_seq",
                    "legal_documento_transiciones.id"),
            Map.entry(
                    "legal_requisito_audiencias_id_seq",
                    "legal_requisito_audiencias.id"),
            Map.entry(
                    "legal_requisito_documentos_id_seq",
                    "legal_requisito_documentos.id"),
            Map.entry(
                    "legal_publicacion_requisitos_id_seq",
                    "legal_publicacion_requisitos.id"),
            Map.entry(
                    "legal_requisito_transiciones_id_seq",
                    "legal_requisito_transiciones.id"),
            Map.entry(
                    "legal_requisito_conjunto_miembros_id_seq",
                    "legal_requisito_conjunto_miembros.id"));

    /** Tables whose structural rows may be created by the offline editorial protocol. */
    static final Set<String> INSERT_TABLES = Set.of(
            "legal_documento_reemplazo_lotes",
            "legal_documento_reemplazo_anteriores",
            "legal_documento_reemplazo_sucesoras",
            "legal_documento_transiciones",
            "legal_documento_vigentes",
            "legal_requisito_transiciones",
            "legal_requisito_conjuntos_actuales");

    /** Current projections are rebuilt atomically; no historical row may be deleted. */
    static final Set<String> DELETE_TABLES = Set.of(
            "legal_documento_vigentes",
            "legal_requisito_conjuntos_actuales");

    /**
     * Exact column UPDATE surface.
     *
     * <p>The {@code id} grants are technical privileges required by PostgreSQL row locks. V27
     * guards reject direct and no-op updates. State columns are materialized only by the
     * SECURITY INVOKER transition call graph, except for sealing a replacement batch.</p>
     */
    static final Map<String, Set<String>> UPDATE_COLUMNS = Map.of(
            "legal_publicaciones", Set.of("id"),
            "legal_documento_lineas", Set.of("id"),
            "legal_documento_versiones", Set.of(
                    "id",
                    "estado",
                    "estado_cambiado_en",
                    "ultimo_motivo",
                    "reemplazo_lote_id"),
            "legal_requisito_lineas", Set.of("id"),
            "legal_requisito_versiones", Set.of(
                    "id",
                    "estado",
                    "estado_cambiado_en",
                    "ultimo_motivo"),
            "legal_documento_reemplazo_lotes", Set.of(
                    "estado_construccion",
                    "sellado_en"));

    /** Only identity sequences reached by editorial transitions and replacement membership. */
    static final Set<String> WRITABLE_SEQUENCES = Set.of(
            "legal_documento_reemplazo_anteriores_id_seq",
            "legal_documento_reemplazo_sucesoras_id_seq",
            "legal_documento_transiciones_id_seq",
            "legal_requisito_transiciones_id_seq");

    static final Map<String, FunctionSpec> EDITORIAL_FUNCTIONS = Map.ofEntries(
            function("legal_rechazar_update_delete()",
                    "30a38b0d2b76c7f16bc5bcf02ae8c8b2f810c65ad5744553154763a90176c781"),
            function("legal_exigir_read_committed()",
                    "872689a843957830c3ef3474b8e20171a703c0761c3e080671a23332a5c907f6"),
            function("legal_read_committed_statement_guard()",
                    "c29455423de3aa181e749545dbd301fe84f6f9add67bb5b60815a76934aa002d"),
            function("legal_publicacion_update_statement_guard()",
                    "aab8c8034aae016acffc34d52263fec1fac308ff69655611e59d2a032845756c"),
            function("legal_bloquear_publicacion_abierta(uuid)",
                    "7a91c1bfce486778bc2c482b772828787795497c4b46c6302b2a1ff496611138"),
            function("legal_bloquear_publicacion_sellada(uuid)",
                    "c23bb6662937bee3a385e5d8169f2b5296e25f483c1f231fcd1b08e54af7013b"),
            function("legal_exigir_publicacion_abierta_columna()",
                    "fd9fe0b7b9e449b748caa6ef02fcef849550b5031e34de915d96855b8ee9bf14"),
            function("legal_exigir_publicacion_doc_contexto_abierta()",
                    "71ae4b4f1f466f2772df95361fda6283f1846367cff5df618b0e40c44c01f1d9"),
            function("legal_exigir_publicacion_req_audiencia_abierta()",
                    "750871768163d7121f72f06bbf4902e8813914e6874b105ba624eb45faf347b8"),
            function("legal_exigir_publicacion_req_documento_abierta()",
                    "67cfeedea9719eac1b2e8a2392638634dd424e59e90c5fd49ad5e88ac2f8d00d"),
            function("legal_publicacion_insert_guard()",
                    "7b15b67e47b0c131c5bd719bcdc4107f92bd49c219b8af9c684da36daa57bce3"),
            function("legal_bloquear_dependencias_publicacion(uuid)",
                    "e2e4009e96a02958698256b9ea128dbd2f65fcfa670cb5e9584e3fbccdf7308d"),
            function("legal_publicacion_update_guard()",
                    "4fcf56cbd2e42555847f27795b5a604a75b839fee47eaeb04720a5f0c113eb24"),
            function("legal_validar_publicacion_sellada(uuid)",
                    "96f96fbaffae71125d20bb3196e1dd205b0d0c8dbb312e1fe706df17af870f11"),
            function("legal_publicacion_constraint_guard()",
                    "7d975f9c1489324140bd88e04b2323ea9e84e3555ae0c52e2829348ad13803eb"),
            function("legal_documento_version_insert_guard()",
                    "289438882daeb0d276c13033f263e3e8d4e6e8101c32ad81b59b31c4e3e70318"),
            function("legal_requisito_version_insert_guard()",
                    "6218b6c9288674229d53ee35c4c17f6eb57bd8c22b04c09aacb6250f7fdf67fe"),
            function("legal_version_update_interno_guard()",
                    "bc716a2bab6a2bc3043cfc22b236ab557fa4bf0f42bc221dfb39ffda720bb95e"),
            function("legal_documento_transicion_before_insert()",
                    "415152172de3d177cc4e608146ed12ba6c3819040945453d9ba97c37d4844965"),
            function("legal_documento_transicion_after_insert()",
                    "b7fe4679a62e7c573ae4aa47bf8207a89fa04df7bfdf331fcf921ca705e911f9"),
            function("legal_requisito_transicion_before_insert()",
                    "8bc9e5a702d4e885d994f8f78f58b287861395a29cf1e900c01b993639e7dc40"),
            function("legal_requisito_transicion_after_insert()",
                    "a95d26861c2cbdd4d6f04f38952afc95a32e843c515b3b04ef03f7055b7127e4"),
            function("legal_documento_slot_insert_guard()",
                    "ec1e2f7851ad6a99f91c5917a21967be230f128c5070ff1667a15c482bc79fce"),
            function("legal_validar_slots_documentales()",
                    "847e2df7bdc3f9bb823a7831d9b19a4e4447bcc188fffa4bd5b990f092fdbe40"),
            function("legal_slots_constraint_guard()",
                    "40f1423c84aeb510bcbbde5bbb9584d0e055935ab6d9170190f8c7a6b02afab7"),
            function("legal_requisito_actual_insert_guard()",
                    "894148e312db313f97c484da1c39694b7e5b52094d0797898341b15294b4f49a"),
            function("legal_validar_conjuntos_actuales()",
                    "9cd7035f2cd5e05b17e5d7d406fe59b6058d1ed738466494f32dad3850c93850"),
            function("legal_conjuntos_actuales_constraint_guard()",
                    "f0a0aed926463353d9edd5f7cc0598c9ff0f68de6692546d1ac06c5657b582b7"),
            function("legal_reemplazo_lote_insert_guard()",
                    "fcd6481f047363e18be24d9685fa5bdf499b74ff3ef5d03f2cb3343455ce2868"),
            function("legal_reemplazo_miembro_insert_guard()",
                    "5519e1b604d727de629ae261a81a4c4f2c5c0e50dd008cd8936aba113dc023fe"),
            function("legal_validar_reemplazo_estructura(uuid)",
                    "3abb5381547b7acf7bcab1402a6c057251dcf5187dffdb77367dbcf3a68273f5"),
            function("legal_reemplazo_lote_before_update()",
                    "bbfb708d15e95bb6aae0c823cf77af6066a5355bdaf60e04fb0fbcab26e87bbb"),
            function("legal_reemplazo_lote_after_update()",
                    "652fcb3db674a5aeb80972a0f4282d841ec8a32136be1068402addb898fd3d08"),
            function("legal_reemplazo_constraint_guard()",
                    "b865bc0bbcc03c52f00c6028694a2477e87458553901a4e5d768c1bb10e39ad8"));

    static final Set<String> VOID_FUNCTIONS = Set.of(
            "legal_exigir_read_committed()",
            "legal_bloquear_publicacion_abierta(uuid)",
            "legal_bloquear_publicacion_sellada(uuid)",
            "legal_bloquear_dependencias_publicacion(uuid)",
            "legal_validar_publicacion_sellada(uuid)",
            "legal_validar_slots_documentales()",
            "legal_validar_conjuntos_actuales()",
            "legal_validar_reemplazo_estructura(uuid)");

    /**
     * Exact SECURITY INVOKER call graph needed by transitions, projections and replacements.
     * Origin-import and publication-sealing functions deliberately remain excluded.
     */
    static final Set<String> PRIVILEGED_FUNCTIONS = Set.of(
            "legal_rechazar_update_delete()",
            "legal_exigir_read_committed()",
            "legal_read_committed_statement_guard()",
            "legal_publicacion_update_statement_guard()",
            "legal_bloquear_publicacion_sellada(uuid)",
            "legal_publicacion_update_guard()",
            "legal_version_update_interno_guard()",
            "legal_documento_transicion_before_insert()",
            "legal_documento_transicion_after_insert()",
            "legal_requisito_transicion_before_insert()",
            "legal_requisito_transicion_after_insert()",
            "legal_documento_slot_insert_guard()",
            "legal_validar_slots_documentales()",
            "legal_slots_constraint_guard()",
            "legal_requisito_actual_insert_guard()",
            "legal_validar_conjuntos_actuales()",
            "legal_conjuntos_actuales_constraint_guard()",
            "legal_reemplazo_lote_insert_guard()",
            "legal_reemplazo_miembro_insert_guard()",
            "legal_validar_reemplazo_estructura(uuid)",
            "legal_reemplazo_lote_before_update()",
            "legal_reemplazo_lote_after_update()",
            "legal_reemplazo_constraint_guard()");

    /* Filled from a clean PostgreSQL 16 catalog; values are schema-independent. */
    static final CatalogFingerprint EXPECTED_CATALOG = new CatalogFingerprint(
            19,
            "75d5f6e901a16eee4c17b0f3cde6f1306870ff60d01f2d07960e9dbb74b53c91",
            130,
            "96f80a77a192335c8a3dc5b8c37ad493426502a98d401c83b13059de853ce705",
            130,
            "6a8fc7a5420cff41c3e4dabb337f9e09fa5b56de0f734d04bd1947d474f0a135",
            58,
            "5024229e141e5286f873a1ebff14ded20c289e2d487863ed7bd6c8b0e906ed96",
            10,
            "51080ba2c1cf9351dc94dc61ba6f303ae8e2234748b5d4738bc8d6c33f10b860");

    private LegalV27EditorialInventory() { }

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
            int triggerCount,
            String triggersSha256,
            int sequenceCount,
            String sequencesSha256) { }
}
