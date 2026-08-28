package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class LegalEditorialPrivilegeVerifierTest {

    @Test
    void frozenRoleInventoryIsExactAndIndependentFromTheImporter() {
        assertThat(LegalV27EditorialInventory.EDITORIAL_TABLES)
                .hasSize(19)
                .doesNotHaveDuplicates();
        assertThat(LegalV27EditorialInventory.INSERT_TABLES).containsExactlyInAnyOrder(
                "legal_documento_reemplazo_lotes",
                "legal_documento_reemplazo_anteriores",
                "legal_documento_reemplazo_sucesoras",
                "legal_documento_transiciones",
                "legal_documento_vigentes",
                "legal_requisito_transiciones",
                "legal_requisito_conjuntos_actuales");
        assertThat(LegalV27EditorialInventory.DELETE_TABLES).containsExactlyInAnyOrder(
                "legal_documento_vigentes",
                "legal_requisito_conjuntos_actuales");
        assertThat(LegalV27EditorialInventory.UPDATE_COLUMNS).containsExactlyInAnyOrderEntriesOf(
                Map.of(
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
                                "sellado_en")));
        assertThat(LegalV27EditorialInventory.UPDATE_COLUMNS.values().stream()
                .mapToInt(Set::size).sum()).isEqualTo(14);

        assertThat(LegalV27EditorialInventory.INSERT_TABLES)
                .isSubsetOf(LegalV27EditorialInventory.EDITORIAL_TABLES)
                .doesNotContainAnyElementsOf(LegalV27ImportInventory.IMPORT_TABLES);
        assertThat(LegalV27EditorialInventory.DELETE_TABLES)
                .isSubsetOf(LegalV27EditorialInventory.EDITORIAL_TABLES)
                .doesNotContainAnyElementsOf(LegalV27ImportInventory.IMPORT_TABLES);
        assertThat(LegalV27EditorialInventory.UPDATE_COLUMNS.keySet())
                .isSubsetOf(LegalV27EditorialInventory.EDITORIAL_TABLES);
    }

    @Test
    void onlyFourEditorialIdentitySequencesAreWritable() {
        assertThat(LegalV27EditorialInventory.IDENTITY_SEQUENCES).hasSize(10);
        assertThat(LegalV27EditorialInventory.WRITABLE_SEQUENCES).containsExactlyInAnyOrder(
                "legal_documento_reemplazo_anteriores_id_seq",
                "legal_documento_reemplazo_sucesoras_id_seq",
                "legal_documento_transiciones_id_seq",
                "legal_requisito_transiciones_id_seq");
        assertThat(LegalV27EditorialInventory.WRITABLE_SEQUENCES)
                .isSubsetOf(LegalV27EditorialInventory.IDENTITY_SEQUENCES.keySet())
                .doesNotContainAnyElementsOf(
                        LegalV27ImportInventory.IDENTITY_SEQUENCES.keySet());
    }

    @Test
    void executeAllowlistContainsOnlyTheTwentyThreeEditorialCallGraphFunctions() {
        assertThat(LegalV27EditorialInventory.EDITORIAL_FUNCTIONS).hasSize(34);
        assertThat(LegalV27EditorialInventory.PRIVILEGED_FUNCTIONS)
                .hasSize(23)
                .isSubsetOf(LegalV27EditorialInventory.EDITORIAL_FUNCTIONS.keySet());
        assertThat(LegalV27EditorialInventory.EDITORIAL_FUNCTIONS.keySet())
                .filteredOn(function -> !LegalV27EditorialInventory.PRIVILEGED_FUNCTIONS
                        .contains(function))
                .containsExactlyInAnyOrder(
                        "legal_bloquear_publicacion_abierta(uuid)",
                        "legal_exigir_publicacion_abierta_columna()",
                        "legal_exigir_publicacion_doc_contexto_abierta()",
                        "legal_exigir_publicacion_req_audiencia_abierta()",
                        "legal_exigir_publicacion_req_documento_abierta()",
                        "legal_publicacion_insert_guard()",
                        "legal_bloquear_dependencias_publicacion(uuid)",
                        "legal_validar_publicacion_sellada(uuid)",
                        "legal_publicacion_constraint_guard()",
                        "legal_documento_version_insert_guard()",
                        "legal_requisito_version_insert_guard()");
        assertThat(LegalEditorialPrivilegeVerifier.LARGE_OBJECT_CREATION_FUNCTIONS)
                .containsExactlyInAnyOrder(
                        "lo_creat(integer)",
                        "lo_create(oid)",
                        "lo_from_bytea(oid, bytea)",
                        "lo_import(text)",
                        "lo_import(text, oid)");
    }

    @Test
    void verifierBindsTheExpectedRoleAndJdbcByIdentity() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcTemplate foreign = mock(JdbcTemplate.class);

        LegalEditorialPrivilegeVerifier verifier = new LegalEditorialPrivilegeVerifier(
                jdbc,
                "ordenfix_legal_editor",
                "public");

        assertThat(verifier.expectedRole()).isEqualTo("ordenfix_legal_editor");
        assertThat(verifier.usesJdbc(jdbc)).isTrue();
        assertThat(verifier.usesJdbc(foreign)).isFalse();
    }

    @Test
    void missingOrSubstitutedRoleIdentityFailsClosedWithTheEditorialIssue() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(List.of()).when(jdbc).query(
                anyString(),
                any(RowMapper.class),
                any(),
                any());
        LegalEditorialPrivilegeVerifier verifier = new LegalEditorialPrivilegeVerifier(
                jdbc,
                "ordenfix_legal_editor",
                "public");

        assertThatThrownBy(verifier::verify)
                .isInstanceOfSatisfying(
                        LegalEditorialOperationalException.class,
                        failure -> {
                            assertThat(failure.issue().code())
                                    .isEqualTo(LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT);
                            assertThat(failure.issue().location())
                                    .isEqualTo(LegalEditorialPrivilegeVerifier.ISSUE_LOCATION);
                        });
    }

    @Test
    void blankRoleOrSchemaIsRejectedBeforeAnyDatabaseRead() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThatThrownBy(() -> new LegalEditorialPrivilegeVerifier(jdbc, " ", "public"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialPrivilegeVerifier(
                jdbc,
                "ordenfix_legal_editor",
                " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
