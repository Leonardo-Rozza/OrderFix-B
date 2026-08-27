package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable PostgreSQL 16 inventory required by the V27 manifest importer. */
final class LegalV27ImportInventory {

    static final String DEFAULT_SCHEMA = "public";
    static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";
    static final String FLYWAY_VERSION = "27";
    static final String FLYWAY_TYPE = "SQL";
    static final String FLYWAY_SCRIPT = "V27__persistencia_legal_append_only.sql";
    static final int FLYWAY_CHECKSUM = 1_575_269_868;

    static final List<String> IMPORT_TABLES = List.of(
            "legal_publicaciones",
            "legal_documento_lineas",
            "legal_documento_versiones",
            "legal_documento_contextos",
            "legal_publicacion_documentos",
            "legal_requisito_lineas",
            "legal_requisito_audiencias",
            "legal_requisito_versiones",
            "legal_requisito_documentos",
            "legal_publicacion_requisitos",
            "legal_requisito_conjuntos",
            "legal_requisito_conjunto_miembros");

    static final Map<String, String> IDENTITY_SEQUENCES = Map.of(
            "legal_documento_contextos_id_seq", "legal_documento_contextos.id",
            "legal_publicacion_documentos_id_seq", "legal_publicacion_documentos.id",
            "legal_requisito_audiencias_id_seq", "legal_requisito_audiencias.id",
            "legal_requisito_documentos_id_seq", "legal_requisito_documentos.id",
            "legal_publicacion_requisitos_id_seq", "legal_publicacion_requisitos.id",
            "legal_requisito_conjunto_miembros_id_seq",
                    "legal_requisito_conjunto_miembros.id");

    static final Map<String, Set<String>> UPDATE_COLUMNS = Map.of(
            "legal_publicaciones", Set.of("estado_construccion", "sellado_en"),
            "legal_documento_lineas", Set.of("id"),
            "legal_documento_versiones", Set.of("id"),
            "legal_requisito_lineas", Set.of("id"),
            "legal_requisito_versiones", Set.of("id"));

    static final Map<String, FunctionSpec> IMPORT_FUNCTIONS = Map.ofEntries(
            function("legal_rechazar_update_delete()",
                    "30a38b0d2b76c7f16bc5bcf02ae8c8b2f810c65ad5744553154763a90176c781"),
            function("legal_exigir_read_committed()",
                    "872689a843957830c3ef3474b8e20171a703c0761c3e080671a23332a5c907f6"),
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
                    "bc716a2bab6a2bc3043cfc22b236ab557fa4bf0f42bc221dfb39ffda720bb95e"));

    static final Set<String> ALL_V27_FUNCTIONS = Set.of(
            "legal_rechazar_update_delete()",
            "legal_exigir_read_committed()",
            "legal_read_committed_statement_guard()",
            "legal_publicacion_update_statement_guard()",
            "legal_bloquear_publicacion_abierta(uuid)",
            "legal_bloquear_publicacion_sellada(uuid)",
            "legal_exigir_publicacion_abierta_columna()",
            "legal_exigir_publicacion_doc_contexto_abierta()",
            "legal_exigir_publicacion_req_audiencia_abierta()",
            "legal_exigir_publicacion_req_documento_abierta()",
            "legal_publicacion_insert_guard()",
            "legal_bloquear_dependencias_publicacion(uuid)",
            "legal_publicacion_update_guard()",
            "legal_validar_publicacion_sellada(uuid)",
            "legal_publicacion_constraint_guard()",
            "legal_documento_version_insert_guard()",
            "legal_requisito_version_insert_guard()",
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
            "legal_reemplazo_constraint_guard()",
            "legal_fila_es_transaccion_actual(xid)",
            "legal_aceptacion_lote_insert_guard()",
            "legal_aceptacion_insert_guard()",
            "legal_aceptacion_documento_insert_guard()",
            "legal_metadata_header_insert_guard()",
            "legal_metadata_cifrada_insert_guard()",
            "legal_validar_aceptacion(uuid)",
            "legal_validar_lote_aceptacion(uuid)",
            "legal_aceptacion_constraint_guard()",
            "legal_metadata_cifrada_update_guard()",
            "legal_metadata_header_update_guard()",
            "legal_idempotencia_insert_guard()",
            "legal_idempotencia_update_delete_guard()");

    /* Filled from a clean PostgreSQL 16 catalog; values are intentionally schema-independent. */
    static final CatalogFingerprint EXPECTED_CATALOG = new CatalogFingerprint(
            12,
            "d3b0a50cb6cbdf0a0a8eab97bd10ae0e1f8e605ce009c6083ae77e1827257ad9",
            93,
            "71ce2628bcac127f8798bbd5098563c8bd3a43c8fc505bd7794dfaa726ae96a7",
            97,
            "359366a916255d91e4de547eb4476d236b307c984495490071f1b54920936475",
            29,
            "e85176c8a84aa7cbf52b5051b8a73981529e29955cf5a49127e20f7cbe3205e3",
            6,
            "308609421640e4120de7cf8621a605b541287c0808e9b44ca0f64f782df2874e");

    private LegalV27ImportInventory() { }

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
