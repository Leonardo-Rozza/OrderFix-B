package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Executes only the mutating portion of a fresh, already-derived PROMOTE plan. */
final class LegalInitialPromotionCore implements LegalEditorialMutationWriter {

    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);

    private final JdbcTemplate jdbc;

    LegalInitialPromotionCore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void write(LegalEditorialExecutionPlan plan) {
        LegalEditorialExecutionPlan required = requireInitialPromotion(plan);
        LegalEditorialExecutionPlan.MutationCommands commands = required.mutationCommands();
        UUID publicationId = required.target().publicationUuid();

        TargetGraphEvidence evidence = readTargetGraphEvidence(publicationId, commands);
        lockPublications(required.target(), evidence.introductionPublicationIds());
        lockTargetGraph(publicationId, evidence);

        insertDocumentTransitionPhase(
                commands.documentTransitions(), EstadoVersionLegal.BORRADOR);
        insertRequirementTransitionPhase(
                commands.requirementTransitions(), EstadoVersionLegal.BORRADOR);
        insertDocumentTransitionPhase(
                commands.documentTransitions(), EstadoVersionLegal.PUBLICADA);
        insertRequirementTransitionPhase(
                commands.requirementTransitions(), EstadoVersionLegal.PUBLICADA);
        insertDocumentSlots(commands.documentSlotInserts());
        insertRequiredSetPointers(commands.requiredSetPointerInserts());
    }

    @Override
    public boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    private static LegalEditorialExecutionPlan requireInitialPromotion(
            LegalEditorialExecutionPlan plan) {
        LegalEditorialExecutionPlan required = Objects.requireNonNull(plan, "plan");
        LegalEditorialExecutionPlan.MutationCommands commands = required.mutationCommands();
        if (required.operationType() != LegalEditorialExecutionPlan.OperationType.PROMOTE
                || !required.changeRequired()
                || required.source().isPresent()
                || required.operationId().isPresent()
                || required.planSha256().isPresent()
                || required.expectedReadinessAfter() != LegalEditorialReadiness.READY
                || required.acknowledgeFailClosedGap()
                || !commands.documentSlotDeletes().isEmpty()
                || !commands.requiredSetPointerDeletes().isEmpty()
                || !commands.replacementBatchesToCreateAndSeal().isEmpty()) {
            throw new IllegalArgumentException("El core inicial requiere un plan PROMOTE mutante exacto");
        }
        requireTwoStepTransitions(commands.documentTransitions(), commands.requirementTransitions());
        UUID target = required.target().publicationUuid();
        if (commands.documentSlotInserts().stream().anyMatch(slot -> !slot.publicationId().equals(target))
                || commands.requiredSetPointerInserts().stream()
                .anyMatch(pointer -> !pointer.publicationId().equals(target))) {
            throw new IllegalArgumentException("El delta PROMOTE contiene una proyección ajena al target");
        }
        return required;
    }

    private static void requireTwoStepTransitions(
            List<LegalEditorialExecutionPlan.DocumentTransition> documents,
            List<LegalEditorialExecutionPlan.RequirementTransition> requirements) {
        requireTwoEdges(documents.stream().map(transition -> new Edge(
                transition.documentVersionId(), transition.previousState(), transition.newState()))
                .toList());
        requireTwoEdges(requirements.stream().map(transition -> new Edge(
                transition.requirementVersionId(), transition.previousState(), transition.newState()))
                .toList());
    }

    private static void requireTwoEdges(List<Edge> edges) {
        Map<UUID, Set<String>> byVersion = edges.stream().collect(java.util.stream.Collectors.groupingBy(
                Edge::versionId,
                java.util.stream.Collectors.mapping(
                        edge -> edge.previousState().name() + ">" + edge.newState().name(),
                        java.util.stream.Collectors.toSet())));
        Set<String> expected = Set.of("BORRADOR>PUBLICADA", "PUBLICADA>VIGENTE");
        if (byVersion.isEmpty() || byVersion.values().stream().anyMatch(actual -> !actual.equals(expected))) {
            throw new IllegalArgumentException("PROMOTE requiere exactamente dos transiciones por versión");
        }
    }

    private void lockPublications(
            LegalEditorialExecutionPlan.PublicationIdentity target,
            List<UUID> introductionPublicationIds) {
        List<UUID> publicationIds = distinctSorted(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(target.publicationUuid()),
                        introductionPublicationIds.stream())
                .toList());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, publication_external_id, manifest_sha256, estado_construccion
                  FROM legal_publicaciones
                 WHERE id IN (%s)
                 ORDER BY id::text
                 FOR UPDATE
                """.formatted(placeholders(publicationIds.size())),
                publicationIds.toArray());
        if (!rowIds(rows, "id").equals(publicationIds)
                || rows.stream().anyMatch(row ->
                        !"SELLADO".equals(row.get("estado_construccion")))) {
            throw new IllegalStateException(
                    "Las publicaciones PROMOTE no están íntegramente selladas bajo lock");
        }
        Map<String, Object> targetRow = rows.stream()
                .filter(row -> target.publicationUuid().equals(row.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "La publicación PROMOTE no existe bajo lock"));
        if (!target.publicationExternalId().equals(targetRow.get("publication_external_id"))
                || !target.manifestSha256().equals(targetRow.get("manifest_sha256"))) {
            throw new IllegalStateException("La publicación PROMOTE no coincide bajo lock");
        }
    }

    private TargetGraphEvidence readTargetGraphEvidence(
            UUID publicationId,
            LegalEditorialExecutionPlan.MutationCommands commands) {
        List<UUID> documentVersions = distinctSorted(commands.documentTransitions().stream()
                .map(LegalEditorialExecutionPlan.DocumentTransition::documentVersionId).toList());
        List<UUID> requirementVersions = distinctSorted(commands.requirementTransitions().stream()
                .map(LegalEditorialExecutionPlan.RequirementTransition::requirementVersionId).toList());
        List<VersionIdentity> documents = queryVersionIdentities(
                "legal_documento_versiones", "documento_linea_id",
                publicationId, documentVersions, false);
        List<VersionIdentity> requirements = queryVersionIdentities(
                "legal_requisito_versiones", "requisito_linea_id",
                publicationId, requirementVersions, false);

        Map<UUID, UUID> documentLinesByVersion = new HashMap<>();
        documents.forEach(identity -> documentLinesByVersion.put(
                identity.versionId(),
                identity.lineId()));
        Set<UUID> slottedVersions = new java.util.HashSet<>();
        for (LegalEditorialExecutionPlan.ExpectedDocumentSlot slot
                : commands.documentSlotInserts()) {
            if (!slot.documentLineId().equals(
                    documentLinesByVersion.get(slot.documentVersionId()))) {
                throw new IllegalStateException(
                        "Una versión documental no pertenece a las líneas del plan");
            }
            slottedVersions.add(slot.documentVersionId());
        }
        if (!slottedVersions.equals(Set.copyOf(documentVersions))) {
            throw new IllegalStateException(
                    "La proyección PROMOTE no cubre todas las versiones documentales");
        }
        return new TargetGraphEvidence(documents, requirements);
    }

    private void lockTargetGraph(UUID publicationId, TargetGraphEvidence evidence) {
        lockIds("legal_documento_lineas", evidence.documentLineIds());
        lockIds("legal_requisito_lineas", evidence.requirementLineIds());
        List<VersionIdentity> lockedDocuments = queryVersionIdentities(
                "legal_documento_versiones", "documento_linea_id",
                publicationId, evidence.documentVersionIds(), true);
        List<VersionIdentity> lockedRequirements = queryVersionIdentities(
                "legal_requisito_versiones", "requisito_linea_id",
                publicationId, evidence.requirementVersionIds(), true);
        if (!lockedDocuments.equals(evidence.documents())
                || !lockedRequirements.equals(evidence.requirements())) {
            throw new IllegalStateException(
                    "El grafo PROMOTE cambió antes de completar sus locks");
        }
    }

    private void lockIds(String table, List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM " + table + " WHERE id IN (" + placeholders(ids.size())
                        + ") ORDER BY id::text FOR UPDATE",
                ids.toArray());
        if (!rowIds(rows, "id").equals(ids)) {
            throw new IllegalStateException("Cardinalidad o identidad inesperada al bloquear " + table);
        }
    }

    private List<VersionIdentity> queryVersionIdentities(
            String table,
            String lineColumn,
            UUID publicationId,
            List<UUID> ids,
            boolean lock) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(ids);
        args.add(publicationId);
        String membership = table.equals("legal_documento_versiones")
                ? "legal_publicacion_documentos"
                : "legal_publicacion_requisitos";
        String membershipVersion = table.equals("legal_documento_versiones")
                ? "documento_version_id"
                : "requisito_version_id";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT v.id, v." + lineColumn
                        + " AS line_id, v.publicacion_intro_id, v.estado FROM " + table + " v"
                        + " JOIN " + membership + " m ON m." + membershipVersion + " = v.id"
                        + " WHERE v.id IN (" + placeholders(ids.size()) + ")"
                        + " AND m.publicacion_id = ? ORDER BY v.id::text"
                        + (lock ? " FOR UPDATE OF v" : ""),
                args.toArray());
        if (!rowIds(rows, "id").equals(ids)
                || rows.stream().anyMatch(row -> !"BORRADOR".equals(row.get("estado")))) {
            throw new IllegalStateException("Las versiones PROMOTE no están íntegramente BORRADOR");
        }
        return rows.stream().map(row -> new VersionIdentity(
                requiredUuid(row, "id"),
                requiredUuid(row, "line_id"),
                requiredUuid(row, "publicacion_intro_id")))
                .toList();
    }

    private void insertDocumentTransitionPhase(
            List<LegalEditorialExecutionPlan.DocumentTransition> transitions,
            EstadoVersionLegal previous) {
        List<Object[]> rows = transitions.stream()
                .filter(transition -> transition.previousState() == previous)
                .sorted(Comparator.comparing(
                        LegalEditorialExecutionPlan.DocumentTransition::documentVersionId,
                        UUID_ORDER))
                .map(transition -> new Object[]{
                        transition.documentVersionId(), transition.previousState().name(),
                        transition.newState().name(), transition.reason(),
                        transition.replacementBatchId(), Timestamp.from(transition.occurredAt())})
                .toList();
        executeExactBatch("""
                INSERT INTO legal_documento_transiciones
                    (documento_version_id, estado_anterior, estado_nuevo,
                     motivo, reemplazo_lote_id, ocurrido_en)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rows);
    }

    private void insertRequirementTransitionPhase(
            List<LegalEditorialExecutionPlan.RequirementTransition> transitions,
            EstadoVersionLegal previous) {
        List<Object[]> rows = transitions.stream()
                .filter(transition -> transition.previousState() == previous)
                .sorted(Comparator.comparing(
                        LegalEditorialExecutionPlan.RequirementTransition::requirementVersionId,
                        UUID_ORDER))
                .map(transition -> new Object[]{
                        transition.requirementVersionId(), transition.previousState().name(),
                        transition.newState().name(), transition.reason(),
                        Timestamp.from(transition.occurredAt())})
                .toList();
        executeExactBatch("""
                INSERT INTO legal_requisito_transiciones
                    (requisito_version_id, estado_anterior, estado_nuevo, motivo, ocurrido_en)
                VALUES (?, ?, ?, ?, ?)
                """, rows);
    }

    private void insertDocumentSlots(
            List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> slots) {
        executeExactBatch("""
                INSERT INTO legal_documento_vigentes
                    (tipo, locale, contexto, documento_version_id,
                     documento_linea_id, publicacion_id, estado_documento)
                VALUES (?, ?, ?, ?, ?, ?, 'VIGENTE')
                """, slots.stream().map(slot -> new Object[]{
                        slot.key().type().name(), slot.key().locale().getCodigo(),
                        slot.key().context().name(), slot.documentVersionId(),
                        slot.documentLineId(), slot.publicationId()}).toList());
    }

    private void insertRequiredSetPointers(
            List<LegalEditorialExecutionPlan.ExpectedRequiredSetPointer> pointers) {
        executeExactBatch("""
                INSERT INTO legal_requisito_conjuntos_actuales
                    (locale, contexto, audiencia, conjunto_id, publicacion_id, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?)
                """, pointers.stream().map(pointer -> new Object[]{
                        pointer.key().locale().getCodigo(), pointer.key().context().name(),
                        pointer.key().audience().name(), pointer.requiredSetId(),
                        pointer.publicationId(), Timestamp.from(pointer.updatedAt())}).toList());
    }

    private void executeExactBatch(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        int[] counts = jdbc.batchUpdate(sql, rows);
        if (counts.length != rows.size()) {
            throw new IllegalStateException("El batch editorial devolvió cardinalidad inesperada");
        }
        for (int count : counts) {
            if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("El batch editorial no insertó exactamente una fila");
            }
        }
    }

    private static List<UUID> distinctSorted(List<UUID> ids) {
        return ids.stream().distinct().sorted(UUID_ORDER).toList();
    }

    private static List<UUID> rowIds(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(row -> requiredUuid(row, key)).toList();
    }

    private static UUID requiredUuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        throw new IllegalStateException("El grafo PROMOTE carece de " + key);
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private record Edge(
            UUID versionId,
            EstadoVersionLegal previousState,
            EstadoVersionLegal newState) {
    }

    private record VersionIdentity(
            UUID versionId,
            UUID lineId,
            UUID introductionPublicationId
    ) {

        private VersionIdentity {
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(lineId, "lineId");
            Objects.requireNonNull(introductionPublicationId, "introductionPublicationId");
        }
    }

    private record TargetGraphEvidence(
            List<VersionIdentity> documents,
            List<VersionIdentity> requirements
    ) {

        private TargetGraphEvidence {
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
            requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        }

        private List<UUID> documentVersionIds() {
            return documents.stream().map(VersionIdentity::versionId).toList();
        }

        private List<UUID> requirementVersionIds() {
            return requirements.stream().map(VersionIdentity::versionId).toList();
        }

        private List<UUID> documentLineIds() {
            return distinctSorted(documents.stream().map(VersionIdentity::lineId).toList());
        }

        private List<UUID> requirementLineIds() {
            return distinctSorted(requirements.stream().map(VersionIdentity::lineId).toList());
        }

        private List<UUID> introductionPublicationIds() {
            return distinctSorted(java.util.stream.Stream.concat(
                            documents.stream(),
                            requirements.stream())
                    .map(VersionIdentity::introductionPublicationId)
                    .toList());
        }
    }
}
