package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Executes the direct DML of a fresh REPLACE execution plan. */
final class LegalDocumentReplacementWriter implements LegalEditorialMutationWriter {

    private static final String MAPPING_LOCATION = "documentReplacementBatches";
    private static final String STATE_LOCATION = "database/state";
    private static final String SOURCE_LOCATION = "database/source";

    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);
    private static final Comparator<SlotProjection> SLOT_ORDER = Comparator
            .comparing((SlotProjection slot) -> slot.key().type().name())
            .thenComparing(slot -> slot.key().locale().getCodigo())
            .thenComparing(slot -> slot.key().context().name());
    private static final Comparator<PointerProjection> POINTER_ORDER = Comparator
            .comparing((PointerProjection pointer) -> pointer.key().locale().getCodigo())
            .thenComparing(pointer -> pointer.key().context().name())
            .thenComparing(pointer -> pointer.key().audience().name());
    private static final Comparator<TargetScopeProjection> TARGET_SCOPE_ORDER = Comparator
            .comparing((TargetScopeProjection scope) -> scope.key().locale().getCodigo())
            .thenComparing(scope -> scope.key().context().name())
            .thenComparing(scope -> scope.key().audience().name());

    private final JdbcTemplate jdbc;
    private final LegalEditorialReadinessCore readinessCore;

    LegalDocumentReplacementWriter(
            JdbcTemplate jdbc,
            LegalEditorialReadinessCore readinessCore) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.readinessCore = Objects.requireNonNull(readinessCore, "readinessCore");
        if (!this.readinessCore.usesJdbc(this.jdbc)) {
            throw new IllegalArgumentException(
                    "El writer REPLACE requiere una única sesión JDBC compartida");
        }
    }

    @Override
    public void write(LegalEditorialExecutionPlan plan) {
        ReplacementContext context = requireReplacement(plan);
        LockedGraph graph = lockGraph(context);
        revalidateSourceState(context, graph);
        executeDirectMutation(context);
    }

    @Override
    public boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate && readinessCore.usesJdbc(candidate);
    }

    private static ReplacementContext requireReplacement(LegalEditorialExecutionPlan plan) {
        LegalEditorialExecutionPlan required = Objects.requireNonNull(plan, "plan");
        LegalEditorialExecutionPlan.MutationCommands commands = Objects.requireNonNull(
                required.mutationCommands(),
                "mutationCommands");
        List<LegalEditorialExecutionPlan.ReplacementBatch> batches = Objects.requireNonNull(
                commands.replacementBatchesToCreateAndSeal(),
                "replacementBatchesToCreateAndSeal");
        if (batches.stream().anyMatch(
                LegalDocumentReplacementWriter::hasInvalidReplacementCardinality)) {
            throw mappingMismatch();
        }

        Optional<LegalEditorialExecutionPlan.SourceIdentity> source = required.source();
        LegalEditorialExecutionPlan.ExpectedPostState expected = required.expectedPostState();
        if (required.operationType() != LegalEditorialExecutionPlan.OperationType.REPLACE
                || !required.changeRequired()
                || source == null
                || source.isEmpty()
                || required.operationId().isEmpty()
                || required.planSha256().isEmpty()
                || required.expectedReadinessAfter() != LegalEditorialReadiness.READY
                || required.acknowledgeFailClosedGap()
                || !required.expectedAppliedAt().equals(required.transactionAt())
                || source.orElseThrow().publication().publicationUuid()
                        .equals(required.target().publicationUuid())
                || expected == null) {
            throw mappingMismatch();
        }
        requireCommandShape(required, expected, commands, batches);
        return new ReplacementContext(
                required,
                source.orElseThrow(),
                expected,
                commands,
                expectedDocumentPreStates(expected),
                expectedRequirementPreStates(expected));
    }

    private static boolean hasInvalidReplacementCardinality(
            LegalEditorialExecutionPlan.ReplacementBatch batch) {
        if (batch == null
                || batch.predecessorDocumentVersionIds() == null
                || batch.successors() == null) {
            return true;
        }
        int predecessorCount = batch.predecessorDocumentVersionIds().size();
        int successorCount = batch.successors().size();
        return predecessorCount == 0
                || successorCount == 0
                || (predecessorCount > 1 && successorCount > 1);
    }

    private static void requireCommandShape(
            LegalEditorialExecutionPlan plan,
            LegalEditorialExecutionPlan.ExpectedPostState expected,
            LegalEditorialExecutionPlan.MutationCommands commands,
            List<LegalEditorialExecutionPlan.ReplacementBatch> batches) {
        UUID targetPublicationId = plan.target().publicationUuid();
        if (!batches.equals(expected.replacementBatches())
                || commands.documentTransitions().stream().anyMatch(transition ->
                        transition.replacementBatchId() != null
                                || transition.newState() == EstadoVersionLegal.REEMPLAZADA)
                || commands.documentSlotInserts().stream().anyMatch(slot ->
                        !slot.publicationId().equals(targetPublicationId))
                || commands.requiredSetPointerInserts().stream().anyMatch(pointer ->
                        !pointer.publicationId().equals(targetPublicationId))
                || batches.stream().flatMap(batch -> batch.successors().stream())
                        .anyMatch(successor ->
                                !successor.publicationId().equals(targetPublicationId))) {
            throw mappingMismatch();
        }

        Set<UUID> activatedDocumentIds = commands.documentTransitions().stream()
                .filter(transition ->
                        transition.previousState() == EstadoVersionLegal.PUBLICADA
                                && transition.newState() == EstadoVersionLegal.VIGENTE)
                .map(LegalEditorialExecutionPlan.DocumentTransition::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> retiredDocumentIds = commands.documentTransitions().stream()
                .filter(transition ->
                        transition.previousState() == EstadoVersionLegal.VIGENTE
                                && transition.newState() == EstadoVersionLegal.RETIRADA)
                .map(LegalEditorialExecutionPlan.DocumentTransition::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> insertedDocumentIds = commands.documentSlotInserts().stream()
                .map(LegalEditorialExecutionPlan.ExpectedDocumentSlot::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        boolean invalidDelete = commands.documentSlotDeletes().stream().anyMatch(deleted ->
                !insertedDocumentIds.contains(deleted.expectedDocumentVersionId())
                        && !retiredDocumentIds.contains(deleted.expectedDocumentVersionId()));
        boolean invalidInsert = commands.documentSlotInserts().stream().anyMatch(inserted -> {
            UUID id = inserted.documentVersionId();
            boolean addition = activatedDocumentIds.contains(id);
            boolean reuse = commands.documentTransitions().stream().noneMatch(transition ->
                    transition.documentVersionId().equals(id));
            return !addition && !reuse;
        });
        if (invalidDelete || invalidInsert) {
            throw mappingMismatch();
        }
    }

    private LockedGraph lockGraph(ReplacementContext context) {
        Set<UUID> documentIds = context.documentPreStates().keySet();
        Set<UUID> requirementIds = context.requirementPreStates().keySet();
        List<VersionEvidence> documentsBefore = readDocumentVersions(documentIds, false);
        List<VersionEvidence> requirementsBefore = readRequirementVersions(
                requirementIds,
                false);
        requireIds(documentIds, documentsBefore, "versiones documentales");
        requireIds(requirementIds, requirementsBefore, "versiones de requisito");

        Set<UUID> publicationIds = new LinkedHashSet<>();
        publicationIds.add(context.source().publication().publicationUuid());
        publicationIds.add(context.plan().target().publicationUuid());
        documentsBefore.forEach(version -> publicationIds.add(version.introductionPublicationId()));
        requirementsBefore.forEach(version ->
                publicationIds.add(version.introductionPublicationId()));
        lockAndValidatePublications(context, publicationIds);

        lockIds(
                "legal_documento_lineas",
                documentsBefore.stream().map(VersionEvidence::lineId)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
        lockIds(
                "legal_requisito_lineas",
                requirementsBefore.stream().map(VersionEvidence::lineId)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));

        List<VersionEvidence> lockedDocuments = readDocumentVersions(documentIds, true);
        List<VersionEvidence> lockedRequirements = readRequirementVersions(requirementIds, true);
        if (!documentsBefore.equals(lockedDocuments)
                || !requirementsBefore.equals(lockedRequirements)) {
            throw stateMismatch();
        }
        Map<UUID, VersionEvidence> documentsById = indexVersions(lockedDocuments);
        Map<UUID, VersionEvidence> requirementsById = indexVersions(lockedRequirements);
        requireExpectedPreStates(context, documentsById, requirementsById);
        requireTargetMembership(context);
        requireExactSlots(context, documentsById);
        requireExactPointers(context);
        requireExactTargetScopes(context);
        requireNewBatchesAbsent(context.commands().replacementBatchesToCreateAndSeal());
        return new LockedGraph(documentsById, requirementsById);
    }

    private void lockAndValidatePublications(
            ReplacementContext context,
            Set<UUID> publicationIds) {
        List<UUID> orderedIds = sortedUuids(publicationIds);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, publication_external_id, manifest_sha256, estado_construccion
                  FROM legal_publicaciones
                 WHERE id IN (%s)
                 ORDER BY id::text
                 FOR UPDATE
                """.formatted(placeholders(orderedIds.size())), orderedIds.toArray());
        if (!rowIds(rows, "id").equals(orderedIds)
                || rows.stream().anyMatch(row ->
                        !"SELLADO".equals(row.get("estado_construccion")))) {
            throw stateMismatch();
        }
        requirePublicationIdentity(rows, context.source().publication());
        requirePublicationIdentity(rows, context.plan().target());
    }

    private static void requirePublicationIdentity(
            List<Map<String, Object>> rows,
            LegalEditorialExecutionPlan.PublicationIdentity identity) {
        Map<String, Object> row = rows.stream()
                .filter(candidate -> identity.publicationUuid().equals(candidate.get("id")))
                .findFirst()
                .orElseThrow(LegalDocumentReplacementWriter::stateMismatch);
        if (!identity.publicationExternalId().equals(row.get("publication_external_id"))
                || !identity.manifestSha256().equals(row.get("manifest_sha256"))) {
            throw stateMismatch();
        }
    }

    private void lockIds(String table, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<UUID> orderedIds = sortedUuids(ids);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM " + table + " WHERE id IN ("
                        + placeholders(orderedIds.size())
                        + ") ORDER BY id::text FOR UPDATE",
                orderedIds.toArray());
        if (!rowIds(rows, "id").equals(orderedIds)) {
            throw stateMismatch();
        }
    }

    private List<VersionEvidence> readDocumentVersions(Set<UUID> ids, boolean lock) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> orderedIds = sortedUuids(ids);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, documento_linea_id AS line_id, publicacion_intro_id,
                       estado, estado_cambiado_en, ultimo_motivo, reemplazo_lote_id
                  FROM legal_documento_versiones
                 WHERE id IN (%s)
                 ORDER BY id::text%s
                """.formatted(
                placeholders(orderedIds.size()),
                lock ? " FOR UPDATE" : ""), orderedIds.toArray());
        return rows.stream().map(row -> versionEvidence(row, true)).toList();
    }

    private List<VersionEvidence> readRequirementVersions(Set<UUID> ids, boolean lock) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> orderedIds = sortedUuids(ids);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, requisito_linea_id AS line_id, publicacion_intro_id,
                       estado, estado_cambiado_en, ultimo_motivo
                  FROM legal_requisito_versiones
                 WHERE id IN (%s)
                 ORDER BY id::text%s
                """.formatted(
                placeholders(orderedIds.size()),
                lock ? " FOR UPDATE" : ""), orderedIds.toArray());
        return rows.stream().map(row -> versionEvidence(row, false)).toList();
    }

    private static VersionEvidence versionEvidence(
            Map<String, Object> row,
            boolean document) {
        return new VersionEvidence(
                requiredUuid(row, "id"),
                requiredUuid(row, "line_id"),
                requiredUuid(row, "publicacion_intro_id"),
                EstadoVersionLegal.valueOf(requiredString(row, "estado")),
                instant(row.get("estado_cambiado_en")),
                optionalString(row, "ultimo_motivo"),
                document ? optionalUuid(row, "reemplazo_lote_id") : null);
    }

    private static Map<UUID, VersionEvidence> indexVersions(List<VersionEvidence> versions) {
        return versions.stream().collect(Collectors.toUnmodifiableMap(
                VersionEvidence::id,
                Function.identity()));
    }

    private static void requireExpectedPreStates(
            ReplacementContext context,
            Map<UUID, VersionEvidence> documents,
            Map<UUID, VersionEvidence> requirements) {
        context.documentPreStates().forEach((id, expected) -> {
            VersionEvidence actual = documents.get(id);
            if (actual == null || !expected.matches(actual)) {
                throw stateMismatch();
            }
        });
        context.requirementPreStates().forEach((id, expected) -> {
            VersionEvidence actual = requirements.get(id);
            if (actual == null || !expected.matches(actual)) {
                throw stateMismatch();
            }
        });
    }

    private void requireTargetMembership(ReplacementContext context) {
        UUID targetId = context.plan().target().publicationUuid();
        Set<UUID> expectedDocuments = context.expected().documentStates().stream()
                .filter(state -> state.state() == EstadoVersionLegal.VIGENTE)
                .map(LegalEditorialExecutionPlan.ExpectedDocumentState::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> expectedRequirements = context.expected().requirementStates().stream()
                .filter(state -> state.state() == EstadoVersionLegal.VIGENTE)
                .map(LegalEditorialExecutionPlan.ExpectedRequirementState::requirementVersionId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> actualDocuments = jdbc.queryForList("""
                SELECT documento_version_id
                  FROM legal_publicacion_documentos
                 WHERE publicacion_id = ?
                 ORDER BY manifest_ordinal
                """, targetId).stream()
                .map(row -> requiredUuid(row, "documento_version_id"))
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> actualRequirements = jdbc.queryForList("""
                SELECT requisito_version_id
                  FROM legal_publicacion_requisitos
                 WHERE publicacion_id = ?
                 ORDER BY manifest_ordinal
                """, targetId).stream()
                .map(row -> requiredUuid(row, "requisito_version_id"))
                .collect(Collectors.toUnmodifiableSet());
        if (!expectedDocuments.equals(actualDocuments)
                || !expectedRequirements.equals(actualRequirements)) {
            throw stateMismatch();
        }
    }

    private void requireExactSlots(
            ReplacementContext context,
            Map<UUID, VersionEvidence> documents) {
        UUID sourceId = context.source().publication().publicationUuid();
        List<LegalEditorialExecutionPlan.DocumentSlotDelete> expectedDeletes = new ArrayList<>(
                context.commands().documentSlotDeletes());
        expectedDeletes.addAll(context.expected().v27TriggerEffects().documentSlotDeletes());
        List<SlotProjection> expected = expectedDeletes.stream()
                .map(deleted -> {
                    VersionEvidence version = documents.get(deleted.expectedDocumentVersionId());
                    if (version == null) {
                        throw stateMismatch();
                    }
                    return new SlotProjection(
                            deleted.key(),
                            deleted.expectedDocumentVersionId(),
                            version.lineId(),
                            sourceId);
                })
                .sorted(SLOT_ORDER)
                .toList();
        List<SlotProjection> actual = jdbc.queryForList("""
                SELECT tipo, locale, contexto, documento_version_id,
                       documento_linea_id, publicacion_id
                  FROM legal_documento_vigentes
                 ORDER BY tipo, locale, contexto
                """).stream().map(row -> new SlotProjection(
                new LegalEditorialExecutionPlan.DocumentSlotKey(
                        TipoDocumentoLegal.valueOf(requiredString(row, "tipo")),
                        LocaleLegal.fromCodigo(requiredString(row, "locale")),
                        ContextoLegal.valueOf(requiredString(row, "contexto"))),
                requiredUuid(row, "documento_version_id"),
                requiredUuid(row, "documento_linea_id"),
                requiredUuid(row, "publicacion_id")))
                .sorted(SLOT_ORDER)
                .toList();
        if (!expected.equals(actual)) {
            throw stateMismatch();
        }
    }

    private void requireExactPointers(ReplacementContext context) {
        UUID sourceId = context.source().publication().publicationUuid();
        List<PointerProjection> expected = context.commands().requiredSetPointerDeletes().stream()
                .map(deleted -> new PointerProjection(
                        deleted.key(),
                        deleted.expectedRequiredSetId(),
                        sourceId))
                .sorted(POINTER_ORDER)
                .toList();
        List<PointerProjection> actual = jdbc.queryForList("""
                SELECT locale, contexto, audiencia, conjunto_id, publicacion_id
                  FROM legal_requisito_conjuntos_actuales
                 ORDER BY locale, contexto, audiencia
                """).stream().map(row -> new PointerProjection(
                new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                        LocaleLegal.fromCodigo(requiredString(row, "locale")),
                        ContextoLegal.valueOf(requiredString(row, "contexto")),
                        AudienciaLegal.valueOf(requiredString(row, "audiencia"))),
                requiredUuid(row, "conjunto_id"),
                requiredUuid(row, "publicacion_id")))
                .sorted(POINTER_ORDER)
                .toList();
        if (!expected.equals(actual)) {
            throw stateMismatch();
        }
    }

    private void requireExactTargetScopes(ReplacementContext context) {
        UUID targetId = context.plan().target().publicationUuid();
        List<TargetScopeProjection> expected = context.commands()
                .requiredSetPointerInserts().stream()
                .map(pointer -> new TargetScopeProjection(
                        pointer.key(),
                        pointer.requiredSetId(),
                        pointer.publicationId(),
                        pointer.requiredSetRevision()))
                .sorted(TARGET_SCOPE_ORDER)
                .toList();
        List<TargetScopeProjection> actual = jdbc.queryForList("""
                SELECT id, publicacion_id, locale, contexto, audiencia,
                       required_set_revision
                  FROM legal_requisito_conjuntos
                 WHERE publicacion_id = ?
                 ORDER BY locale, contexto, audiencia
                """, targetId).stream().map(row -> new TargetScopeProjection(
                new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                        LocaleLegal.fromCodigo(requiredString(row, "locale")),
                        ContextoLegal.valueOf(requiredString(row, "contexto")),
                        AudienciaLegal.valueOf(requiredString(row, "audiencia"))),
                requiredUuid(row, "id"),
                requiredUuid(row, "publicacion_id"),
                requiredString(row, "required_set_revision")))
                .sorted(TARGET_SCOPE_ORDER)
                .toList();
        if (!expected.equals(actual)) {
            throw stateMismatch();
        }
    }

    private void requireNewBatchesAbsent(
            List<LegalEditorialExecutionPlan.ReplacementBatch> batches) {
        if (batches.isEmpty()) {
            return;
        }
        List<UUID> ids = batches.stream()
                .map(LegalEditorialExecutionPlan.ReplacementBatch::batchId)
                .sorted(UUID_ORDER)
                .toList();
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM legal_documento_reemplazo_lotes WHERE id IN ("
                        + placeholders(ids.size()) + ") ORDER BY id::text",
                ids.toArray());
        if (!existing.isEmpty()) {
            throw stateMismatch();
        }
    }

    private void revalidateSourceState(
            ReplacementContext context,
            LockedGraph ignored) {
        LegalEditorialReadinessObservation observation = readinessCore.observeState(
                context.source().publication().publicationExternalId(),
                context.plan().observedAt());
        if (!observation.publicationUuid().equals(Optional.of(
                context.source().publication().publicationUuid()))
                || !observation.observedAt().equals(context.plan().observedAt())) {
            throw stateMismatch();
        }
        if (!observation.editorialStateFingerprint().equals(
                context.source().expectedEditorialStateFingerprint())) {
            throw new LegalEditorialBlockedException(
                    LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH,
                    SOURCE_LOCATION);
        }
    }

    private void executeDirectMutation(ReplacementContext context) {
        LegalEditorialExecutionPlan.MutationCommands commands = context.commands();
        executeDocumentTransitions(commands.documentTransitions(),
                EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA);
        executeRequirementTransitions(commands.requirementTransitions(),
                EstadoVersionLegal.BORRADOR, EstadoVersionLegal.PUBLICADA);
        deleteRequiredSetPointers(commands.requiredSetPointerDeletes());

        // All direct slots are freed before any direct insert. This is required when one
        // cutover retires a line and adds another line for the same slot PK.
        deleteDocumentSlots(commands.documentSlotDeletes());

        executeDocumentTransitions(commands.documentTransitions(),
                EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE);
        Set<UUID> additions = commands.documentTransitions().stream()
                .filter(transition ->
                        transition.previousState() == EstadoVersionLegal.PUBLICADA
                                && transition.newState() == EstadoVersionLegal.VIGENTE)
                .map(LegalEditorialExecutionPlan.DocumentTransition::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        insertDocumentSlots(commands.documentSlotInserts().stream()
                .filter(slot -> additions.contains(slot.documentVersionId()))
                .toList());

        createAndSealReplacementBatches(commands.replacementBatchesToCreateAndSeal());
        executeDocumentTransitions(commands.documentTransitions(),
                EstadoVersionLegal.VIGENTE, EstadoVersionLegal.RETIRADA);
        executeRequirementTransitions(commands.requirementTransitions(),
                EstadoVersionLegal.PUBLICADA, EstadoVersionLegal.VIGENTE);
        executeRequirementTransitions(commands.requirementTransitions(),
                EstadoVersionLegal.VIGENTE, EstadoVersionLegal.REEMPLAZADA);
        executeRequirementTransitions(commands.requirementTransitions(),
                EstadoVersionLegal.VIGENTE, EstadoVersionLegal.RETIRADA);

        insertDocumentSlots(commands.documentSlotInserts().stream()
                .filter(slot -> !additions.contains(slot.documentVersionId()))
                .toList());
        insertRequiredSetPointers(commands.requiredSetPointerInserts());
    }

    private void executeDocumentTransitions(
            List<LegalEditorialExecutionPlan.DocumentTransition> transitions,
            EstadoVersionLegal previous,
            EstadoVersionLegal next) {
        executeInsertBatch("""
                INSERT INTO legal_documento_transiciones
                    (documento_version_id, estado_anterior, estado_nuevo,
                     motivo, reemplazo_lote_id, ocurrido_en)
                VALUES (?, ?, ?, ?, ?, ?)
                """, transitions.stream()
                .filter(transition -> transition.previousState() == previous
                        && transition.newState() == next)
                .sorted(Comparator.comparing(
                        LegalEditorialExecutionPlan.DocumentTransition::documentVersionId,
                        UUID_ORDER))
                .map(transition -> new Object[]{
                        transition.documentVersionId(), transition.previousState().name(),
                        transition.newState().name(), transition.reason(), null,
                        Timestamp.from(transition.occurredAt())})
                .toList());
    }

    private void executeRequirementTransitions(
            List<LegalEditorialExecutionPlan.RequirementTransition> transitions,
            EstadoVersionLegal previous,
            EstadoVersionLegal next) {
        executeInsertBatch("""
                INSERT INTO legal_requisito_transiciones
                    (requisito_version_id, estado_anterior, estado_nuevo,
                     motivo, ocurrido_en)
                VALUES (?, ?, ?, ?, ?)
                """, transitions.stream()
                .filter(transition -> transition.previousState() == previous
                        && transition.newState() == next)
                .sorted(Comparator.comparing(
                        LegalEditorialExecutionPlan.RequirementTransition::requirementVersionId,
                        UUID_ORDER))
                .map(transition -> new Object[]{
                        transition.requirementVersionId(), transition.previousState().name(),
                        transition.newState().name(), transition.reason(),
                        Timestamp.from(transition.occurredAt())})
                .toList());
    }

    private void deleteRequiredSetPointers(
            List<LegalEditorialExecutionPlan.RequiredSetPointerDelete> pointers) {
        executeDeleteBatch("""
                DELETE FROM legal_requisito_conjuntos_actuales
                 WHERE locale = ? AND contexto = ? AND audiencia = ?
                   AND conjunto_id = ?
                """, pointers.stream().map(pointer -> new Object[]{
                pointer.key().locale().getCodigo(), pointer.key().context().name(),
                pointer.key().audience().name(), pointer.expectedRequiredSetId()}).toList());
    }

    private void deleteDocumentSlots(
            List<LegalEditorialExecutionPlan.DocumentSlotDelete> slots) {
        executeDeleteBatch("""
                DELETE FROM legal_documento_vigentes
                 WHERE tipo = ? AND locale = ? AND contexto = ?
                   AND documento_version_id = ?
                """, slots.stream().map(slot -> new Object[]{
                slot.key().type().name(), slot.key().locale().getCodigo(),
                slot.key().context().name(), slot.expectedDocumentVersionId()}).toList());
    }

    private void insertDocumentSlots(
            List<LegalEditorialExecutionPlan.ExpectedDocumentSlot> slots) {
        executeInsertBatch("""
                INSERT INTO legal_documento_vigentes
                    (tipo, locale, contexto, documento_version_id,
                     documento_linea_id, publicacion_id, estado_documento)
                VALUES (?, ?, ?, ?, ?, ?, 'VIGENTE')
                """, slots.stream().map(slot -> new Object[]{
                slot.key().type().name(), slot.key().locale().getCodigo(),
                slot.key().context().name(), slot.documentVersionId(),
                slot.documentLineId(), slot.publicationId()}).toList());
    }

    private void createAndSealReplacementBatches(
            List<LegalEditorialExecutionPlan.ReplacementBatch> batches) {
        executeInsertBatch("""
                INSERT INTO legal_documento_reemplazo_lotes
                    (id, estado_construccion, creado_en, sellado_en)
                VALUES (?, 'ABIERTO', ?, NULL)
                """, batches.stream().map(batch -> new Object[]{
                batch.batchId(), Timestamp.from(batch.createdAt())}).toList());
        executeInsertBatch("""
                INSERT INTO legal_documento_reemplazo_anteriores
                    (lote_id, documento_version_id)
                VALUES (?, ?)
                """, batches.stream().flatMap(batch ->
                batch.predecessorDocumentVersionIds().stream()
                        .map(predecessor -> new Object[]{batch.batchId(), predecessor}))
                .toList());
        executeInsertBatch("""
                INSERT INTO legal_documento_reemplazo_sucesoras
                    (lote_id, documento_version_id, publicacion_id)
                VALUES (?, ?, ?)
                """, batches.stream().flatMap(batch -> batch.successors().stream()
                        .map(successor -> new Object[]{
                                batch.batchId(), successor.documentVersionId(),
                                successor.publicationId()}))
                .toList());
        for (LegalEditorialExecutionPlan.ReplacementBatch batch : batches) {
            int count = jdbc.update("""
                    UPDATE legal_documento_reemplazo_lotes
                       SET estado_construccion = 'SELLADO', sellado_en = ?
                     WHERE id = ?
                       AND estado_construccion = 'ABIERTO'
                       AND sellado_en IS NULL
                    """, Timestamp.from(batch.sealedAt()), batch.batchId());
            if (count != 1) {
                throw unexpectedCardinality();
            }
        }
    }

    private void insertRequiredSetPointers(
            List<LegalEditorialExecutionPlan.ExpectedRequiredSetPointer> pointers) {
        executeInsertBatch("""
                INSERT INTO legal_requisito_conjuntos_actuales
                    (locale, contexto, audiencia, conjunto_id,
                     publicacion_id, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?)
                """, pointers.stream().map(pointer -> new Object[]{
                pointer.key().locale().getCodigo(), pointer.key().context().name(),
                pointer.key().audience().name(), pointer.requiredSetId(),
                pointer.publicationId(), Timestamp.from(pointer.updatedAt())}).toList());
    }

    private void executeInsertBatch(String sql, List<Object[]> rows) {
        executeBatch(sql, rows, true);
    }

    private void executeDeleteBatch(String sql, List<Object[]> rows) {
        executeBatch(sql, rows, false);
    }

    private void executeBatch(
            String sql,
            List<Object[]> rows,
            boolean acceptsSuccessNoInfo) {
        if (rows.isEmpty()) {
            return;
        }
        int[] counts = jdbc.batchUpdate(sql, rows);
        if (counts.length != rows.size()) {
            throw unexpectedCardinality();
        }
        for (int count : counts) {
            if (count != 1
                    && !(acceptsSuccessNoInfo && count == Statement.SUCCESS_NO_INFO)) {
                throw unexpectedCardinality();
            }
        }
    }

    private static Map<UUID, ExpectedPreState> expectedDocumentPreStates(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        Map<UUID, ExpectedPreState> states = new HashMap<>();
        for (LegalEditorialExecutionPlan.ExpectedDocumentState state :
                expected.documentStates()) {
            List<LegalEditorialExecutionPlan.DocumentTransition> delta =
                    expected.documentTransitions().stream()
                            .filter(transition -> transition.documentVersionId()
                                    .equals(state.documentVersionId()))
                            .toList();
            if (delta.isEmpty()) {
                states.put(state.documentVersionId(), new ExpectedPreState(
                        state.state(), state.stateChangedAt(), state.lastReason(),
                        state.replacementBatchId()));
                continue;
            }
            EstadoVersionLegal currentState = delta.getFirst().previousState();
            states.put(state.documentVersionId(), documentPreState(
                    expected,
                    state.documentVersionId(),
                    currentState));
        }
        return Map.copyOf(states);
    }

    private static ExpectedPreState documentPreState(
            LegalEditorialExecutionPlan.ExpectedPostState expected,
            UUID versionId,
            EstadoVersionLegal currentState) {
        if (currentState == EstadoVersionLegal.BORRADOR) {
            return new ExpectedPreState(currentState, null, null, null);
        }
        LegalEditorialExecutionPlan.DocumentTransition last = expected
                .preexistingDocumentTransitions().stream()
                .filter(transition -> transition.documentVersionId().equals(versionId))
                .reduce((left, right) -> right)
                .orElseThrow(LegalDocumentReplacementWriter::mappingMismatch);
        if (last.newState() != currentState) {
            throw mappingMismatch();
        }
        return new ExpectedPreState(
                currentState,
                last.occurredAt(),
                last.reason(),
                last.replacementBatchId());
    }

    private static Map<UUID, ExpectedPreState> expectedRequirementPreStates(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        Map<UUID, ExpectedPreState> states = new HashMap<>();
        for (LegalEditorialExecutionPlan.ExpectedRequirementState state :
                expected.requirementStates()) {
            List<LegalEditorialExecutionPlan.RequirementTransition> delta =
                    expected.requirementTransitions().stream()
                            .filter(transition -> transition.requirementVersionId()
                                    .equals(state.requirementVersionId()))
                            .toList();
            if (delta.isEmpty()) {
                states.put(state.requirementVersionId(), new ExpectedPreState(
                        state.state(), state.stateChangedAt(), state.lastReason(), null));
                continue;
            }
            EstadoVersionLegal currentState = delta.getFirst().previousState();
            states.put(state.requirementVersionId(), requirementPreState(
                    expected,
                    state.requirementVersionId(),
                    currentState));
        }
        return Map.copyOf(states);
    }

    private static ExpectedPreState requirementPreState(
            LegalEditorialExecutionPlan.ExpectedPostState expected,
            UUID versionId,
            EstadoVersionLegal currentState) {
        if (currentState == EstadoVersionLegal.BORRADOR) {
            return new ExpectedPreState(currentState, null, null, null);
        }
        LegalEditorialExecutionPlan.RequirementTransition last = expected
                .preexistingRequirementTransitions().stream()
                .filter(transition -> transition.requirementVersionId().equals(versionId))
                .reduce((left, right) -> right)
                .orElseThrow(LegalDocumentReplacementWriter::mappingMismatch);
        if (last.newState() != currentState) {
            throw mappingMismatch();
        }
        return new ExpectedPreState(currentState, last.occurredAt(), last.reason(), null);
    }

    private static void requireIds(
            Set<UUID> expected,
            List<VersionEvidence> actual,
            String description) {
        Set<UUID> actualIds = actual.stream()
                .map(VersionEvidence::id)
                .collect(Collectors.toUnmodifiableSet());
        if (!actualIds.equals(expected)) {
            throw new LegalEditorialBlockedException(
                    LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                    STATE_LOCATION + "/" + description.replace(' ', '-'));
        }
    }

    private static List<UUID> sortedUuids(Set<UUID> values) {
        return values.stream().sorted(UUID_ORDER).toList();
    }

    private static List<UUID> rowIds(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(row -> requiredUuid(row, key)).toList();
    }

    private static UUID requiredUuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        throw new IllegalStateException("La observación REPLACE carece de " + key);
    }

    private static UUID optionalUuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null || value instanceof UUID) {
            return (UUID) value;
        }
        throw new IllegalStateException("La observación REPLACE contiene un " + key + " inválido");
    }

    private static String requiredString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalStateException("La observación REPLACE carece de " + key);
    }

    private static String optionalString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalStateException("La observación REPLACE contiene un " + key + " inválido");
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            default -> throw new IllegalStateException(
                    "La observación REPLACE contiene un instante inválido");
        };
    }

    private static String placeholders(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Se requiere al menos un UUID REPLACE");
        }
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static LegalEditorialBlockedException mappingMismatch() {
        return new LegalEditorialBlockedException(
                LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                MAPPING_LOCATION);
    }

    private static LegalEditorialBlockedException stateMismatch() {
        return new LegalEditorialBlockedException(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                STATE_LOCATION);
    }

    private static IllegalStateException unexpectedCardinality() {
        return new IllegalStateException(
                "El DML REPLACE no afectó exactamente una fila esperada");
    }

    private record ReplacementContext(
            LegalEditorialExecutionPlan plan,
            LegalEditorialExecutionPlan.SourceIdentity source,
            LegalEditorialExecutionPlan.ExpectedPostState expected,
            LegalEditorialExecutionPlan.MutationCommands commands,
            Map<UUID, ExpectedPreState> documentPreStates,
            Map<UUID, ExpectedPreState> requirementPreStates) {
    }

    private record LockedGraph(
            Map<UUID, VersionEvidence> documents,
            Map<UUID, VersionEvidence> requirements) {
    }

    private record VersionEvidence(
            UUID id,
            UUID lineId,
            UUID introductionPublicationId,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason,
            UUID replacementBatchId) {
    }

    private record ExpectedPreState(
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String lastReason,
            UUID replacementBatchId) {

        boolean matches(VersionEvidence actual) {
            return state == actual.state()
                    && Objects.equals(stateChangedAt, actual.stateChangedAt())
                    && Objects.equals(lastReason, actual.lastReason())
                    && Objects.equals(replacementBatchId, actual.replacementBatchId());
        }
    }

    private record SlotProjection(
            LegalEditorialExecutionPlan.DocumentSlotKey key,
            UUID documentVersionId,
            UUID documentLineId,
            UUID publicationId) {
    }

    private record PointerProjection(
            LegalEditorialExecutionPlan.RequiredSetPointerKey key,
            UUID requiredSetId,
            UUID publicationId) {
    }

    private record TargetScopeProjection(
            LegalEditorialExecutionPlan.RequiredSetPointerKey key,
            UUID requiredSetId,
            UUID publicationId,
            String revision) {
    }
}
