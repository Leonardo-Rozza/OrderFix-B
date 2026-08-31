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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Executes only the direct DML of one fresh, already-derived RETIRE plan. */
final class LegalEditorialRetirementWriter implements LegalEditorialMutationWriter {

    private static final String MAPPING_LOCATION = "retirements";
    private static final String STATE_LOCATION = "database/state";
    private static final String SOURCE_LOCATION = "database/source";
    private static final String BINDING_LOCATION = "editorialPlan";

    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);
    private static final Comparator<SlotProjection> SLOT_ORDER = Comparator
            .comparing((SlotProjection slot) -> slot.key().type().name())
            .thenComparing(slot -> slot.key().locale().getCodigo())
            .thenComparing(slot -> slot.key().context().name());
    private static final Comparator<PointerProjection> POINTER_ORDER = Comparator
            .comparing((PointerProjection pointer) -> pointer.key().locale().getCodigo())
            .thenComparing(pointer -> pointer.key().context().name())
            .thenComparing(pointer -> pointer.key().audience().name());

    private final JdbcTemplate jdbc;
    private final LegalEditorialReadinessCore readinessCore;

    LegalEditorialRetirementWriter(
            JdbcTemplate jdbc,
            LegalEditorialReadinessCore readinessCore) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.readinessCore = Objects.requireNonNull(readinessCore, "readinessCore");
        if (!this.readinessCore.usesJdbc(this.jdbc)) {
            throw new IllegalArgumentException(
                    "El writer RETIRE requiere una unica sesion JDBC compartida");
        }
    }

    @Override
    public void write(LegalEditorialExecutionPlan plan) {
        RetirementContext context = requireRetirement(plan);
        LockedGraph graph = lockAndRevalidate(context);
        executeDirectMutation(context, graph);
    }

    @Override
    public boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate && readinessCore.usesJdbc(candidate);
    }

    private static RetirementContext requireRetirement(
            LegalEditorialExecutionPlan plan) {
        LegalEditorialExecutionPlan required = Objects.requireNonNull(plan, "plan");
        if (required.operationType() != LegalEditorialExecutionPlan.OperationType.RETIRE) {
            throw mappingMismatch();
        }
        if (required.expectedReadinessAfter() != LegalEditorialReadiness.NOT_READY) {
            throw new LegalEditorialBlockedException(
                    LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH,
                    BINDING_LOCATION + "/expectedReadinessAfter");
        }
        if (!required.acknowledgeFailClosedGap()) {
            throw new LegalEditorialBlockedException(
                    LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED,
                    BINDING_LOCATION + "/acknowledgeFailClosedGap");
        }

        Optional<LegalEditorialExecutionPlan.SourceIdentity> source = required.source();
        LegalEditorialExecutionPlan.ExpectedPostState expected = required.expectedPostState();
        LegalEditorialExecutionPlan.MutationCommands commands = required.mutationCommands();
        if (!required.changeRequired()
                || source == null
                || source.isEmpty()
                || required.operationId().isEmpty()
                || required.planSha256().isEmpty()
                || expected == null
                || commands == null
                || !required.expectedAppliedAt().equals(required.transactionAt())
                || !source.orElseThrow().publication().equals(required.target())) {
            throw mappingMismatch();
        }
        requireCommandShape(required, expected, commands);
        return new RetirementContext(
                required,
                source.orElseThrow(),
                expected,
                commands,
                expectedDocumentPreStates(expected),
                expectedRequirementPreStates(expected));
    }

    private static void requireCommandShape(
            LegalEditorialExecutionPlan plan,
            LegalEditorialExecutionPlan.ExpectedPostState expected,
            LegalEditorialExecutionPlan.MutationCommands commands) {
        List<LegalEditorialExecutionPlan.DocumentTransition> documents =
                commands.documentTransitions();
        List<LegalEditorialExecutionPlan.RequirementTransition> requirements =
                commands.requirementTransitions();
        if (documents == null
                || requirements == null
                || commands.documentSlotDeletes() == null
                || commands.requiredSetPointerDeletes() == null
                || commands.documentSlotInserts() == null
                || commands.requiredSetPointerInserts() == null
                || commands.replacementBatchesToCreateAndSeal() == null
                || documents.size() + requirements.size() == 0
                || !commands.documentSlotInserts().isEmpty()
                || !commands.requiredSetPointerInserts().isEmpty()
                || !commands.replacementBatchesToCreateAndSeal().isEmpty()
                || !expected.replacementBatches().isEmpty()
                || !expected.v27TriggerEffects().isEmpty()
                || !documents.equals(expected.documentTransitions())
                || !requirements.equals(expected.requirementTransitions())
                || documents.stream().anyMatch(transition ->
                        !validRetirementTransition(transition, plan.expectedAppliedAt()))
                || requirements.stream().anyMatch(transition ->
                        !validRetirementTransition(transition, plan.expectedAppliedAt()))) {
            throw mappingMismatch();
        }

        Set<LegalEditorialExecutionPlan.DocumentSlotKey> finalSlotKeys = expected
                .documentSlots().stream()
                .map(LegalEditorialExecutionPlan.ExpectedDocumentSlot::key)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> retiredDocumentIds = documents.stream()
                .map(LegalEditorialExecutionPlan.DocumentTransition::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> retiredRequirementIds = requirements.stream()
                .map(LegalEditorialExecutionPlan.RequirementTransition::requirementVersionId)
                .collect(Collectors.toUnmodifiableSet());
        if (commands.documentSlotDeletes().stream()
                .map(LegalEditorialExecutionPlan.DocumentSlotDelete::key)
                .anyMatch(finalSlotKeys::contains)
                || commands.documentSlotDeletes().stream()
                        .map(delete -> delete.expectedDocumentVersionId())
                        .anyMatch(versionId -> !retiredDocumentIds.contains(versionId))) {
            throw mappingMismatch();
        }
        Set<LegalEditorialExecutionPlan.RequiredSetPointerKey> finalPointerKeys = expected
                .requiredSetPointers().stream()
                .map(LegalEditorialExecutionPlan.ExpectedRequiredSetPointer::key)
                .collect(Collectors.toUnmodifiableSet());
        if (commands.requiredSetPointerDeletes().stream()
                .map(LegalEditorialExecutionPlan.RequiredSetPointerDelete::key)
                .anyMatch(finalPointerKeys::contains)
                || expected.requiredSetPointers().stream().anyMatch(pointer ->
                        pointer.dependenciesEvidence().isEmpty()
                                || referencesAny(
                                        pointer.dependenciesEvidence().orElseThrow(),
                                        retiredDocumentIds,
                                        retiredRequirementIds))
                || commands.requiredSetPointerDeletes().stream().anyMatch(pointer ->
                        pointer.dependenciesEvidence().isEmpty()
                                || !referencesAny(
                                        pointer.dependenciesEvidence().orElseThrow(),
                                        retiredDocumentIds,
                                        retiredRequirementIds))) {
            throw mappingMismatch();
        }
    }

    private static boolean referencesAny(
            LegalEditorialExecutionPlan.RequiredSetDependencies dependencies,
            Set<UUID> retiredDocumentIds,
            Set<UUID> retiredRequirementIds) {
        return dependencies.memberRequirementVersionIds().stream()
                .anyMatch(retiredRequirementIds::contains)
                || dependencies.referencedDocumentVersionIds().stream()
                .anyMatch(retiredDocumentIds::contains);
    }

    private static boolean validRetirementTransition(
            LegalEditorialExecutionPlan.DocumentTransition transition,
            Instant expectedAppliedAt) {
        return transition != null
                && transition.previousState() == EstadoVersionLegal.VIGENTE
                && transition.newState() == EstadoVersionLegal.RETIRADA
                && transition.reason() != null
                && !transition.reason().isBlank()
                && transition.replacementBatchId() == null
                && transition.occurredAt().equals(expectedAppliedAt);
    }

    private static boolean validRetirementTransition(
            LegalEditorialExecutionPlan.RequirementTransition transition,
            Instant expectedAppliedAt) {
        return transition != null
                && transition.previousState() == EstadoVersionLegal.VIGENTE
                && transition.newState() == EstadoVersionLegal.RETIRADA
                && transition.reason() != null
                && !transition.reason().isBlank()
                && transition.occurredAt().equals(expectedAppliedAt);
    }

    private LockedGraph lockAndRevalidate(RetirementContext context) {
        Set<UUID> documentIds = context.documentPreStates().keySet();
        Set<UUID> requirementIds = context.requirementPreStates().keySet();
        List<VersionEvidence> documentsBefore = readDocumentVersions(documentIds, false);
        List<VersionEvidence> requirementsBefore = readRequirementVersions(
                requirementIds,
                false);
        requireIds(documentIds, documentsBefore, "versiones-documentales");
        requireIds(requirementIds, requirementsBefore, "versiones-requisito");

        Set<UUID> publicationIds = new LinkedHashSet<>();
        publicationIds.add(context.plan().target().publicationUuid());
        documentsBefore.forEach(version -> publicationIds.add(
                version.introductionPublicationId()));
        requirementsBefore.forEach(version -> publicationIds.add(
                version.introductionPublicationId()));
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
        requireExactMembership(context);
        lockCurrentProjectionTables();
        requireExactPointers(context);
        requireExactSlots(context, documentsById);
        revalidateSourceFingerprint(context);
        return new LockedGraph(documentsById, requirementsById);
    }

    private void lockCurrentProjectionTables() {
        // DELETE authorizes this table lock; row-level FOR UPDATE would broaden the role grant.
        jdbc.execute("""
                LOCK TABLE legal_requisito_conjuntos_actuales,
                           legal_documento_vigentes
                IN SHARE ROW EXCLUSIVE MODE
                """);
    }

    private void lockAndValidatePublications(
            RetirementContext context,
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
        Map<String, Object> target = rows.stream()
                .filter(row -> context.plan().target().publicationUuid().equals(row.get("id")))
                .findFirst()
                .orElseThrow(LegalEditorialRetirementWriter::stateMismatch);
        if (!context.plan().target().publicationExternalId()
                        .equals(target.get("publication_external_id"))
                || !context.plan().target().manifestSha256()
                        .equals(target.get("manifest_sha256"))) {
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

    private static Map<UUID, VersionEvidence> indexVersions(
            List<VersionEvidence> versions) {
        return versions.stream().collect(Collectors.toUnmodifiableMap(
                VersionEvidence::id,
                Function.identity()));
    }

    private static void requireExpectedPreStates(
            RetirementContext context,
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

    private void requireExactMembership(RetirementContext context) {
        UUID publicationId = context.plan().target().publicationUuid();
        Set<UUID> expectedDocuments = context.expected().documentStates().stream()
                .map(LegalEditorialExecutionPlan.ExpectedDocumentState::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> expectedRequirements = context.expected().requirementStates().stream()
                .map(LegalEditorialExecutionPlan.ExpectedRequirementState::requirementVersionId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> actualDocuments = jdbc.queryForList("""
                SELECT documento_version_id
                  FROM legal_publicacion_documentos
                 WHERE publicacion_id = ?
                 ORDER BY manifest_ordinal
                """, publicationId).stream()
                .map(row -> requiredUuid(row, "documento_version_id"))
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> actualRequirements = jdbc.queryForList("""
                SELECT requisito_version_id
                  FROM legal_publicacion_requisitos
                 WHERE publicacion_id = ?
                 ORDER BY manifest_ordinal
                """, publicationId).stream()
                .map(row -> requiredUuid(row, "requisito_version_id"))
                .collect(Collectors.toUnmodifiableSet());
        if (!expectedDocuments.equals(actualDocuments)
                || !expectedRequirements.equals(actualRequirements)) {
            throw stateMismatch();
        }
    }

    private void requireExactPointers(RetirementContext context) {
        List<Map<String, Object>> baseRows = jdbc.queryForList("""
                SELECT a.locale, a.contexto, a.audiencia, a.conjunto_id,
                       a.publicacion_id, a.actualizado_en, c.required_set_revision
                  FROM legal_requisito_conjuntos_actuales a
                  JOIN legal_requisito_conjuntos c ON c.id = a.conjunto_id
                 ORDER BY a.locale, a.contexto, a.audiencia
                """);
        List<Map<String, Object>> memberRows = jdbc.queryForList("""
                SELECT a.conjunto_id, m.requisito_version_id
                  FROM legal_requisito_conjuntos_actuales a
                  JOIN legal_requisito_conjunto_miembros m
                    ON m.conjunto_id = a.conjunto_id
                 ORDER BY a.locale, a.contexto, a.audiencia,
                          m.manifest_ordinal, m.id
                """);
        List<Map<String, Object>> documentRows = jdbc.queryForList("""
                SELECT a.conjunto_id, rd.documento_version_id
                  FROM legal_requisito_conjuntos_actuales a
                  JOIN legal_requisito_conjunto_miembros m
                    ON m.conjunto_id = a.conjunto_id
                  JOIN legal_requisito_documentos rd
                    ON rd.requisito_version_id = m.requisito_version_id
                 ORDER BY a.locale, a.contexto, a.audiencia,
                          m.manifest_ordinal, rd.documento_ordinal, rd.id
                """);

        Map<UUID, List<UUID>> membersBySet = groupUuidRows(
                memberRows,
                "conjunto_id",
                "requisito_version_id");
        Map<UUID, List<UUID>> documentsBySet = groupUuidRows(
                documentRows,
                "conjunto_id",
                "documento_version_id");
        List<PointerProjection> actual = baseRows.stream()
                .map(row -> {
                    UUID setId = requiredUuid(row, "conjunto_id");
                    return new PointerProjection(
                            pointerKey(row),
                            setId,
                            requiredUuid(row, "publicacion_id"),
                            requiredString(row, "required_set_revision"),
                            instantRequired(row.get("actualizado_en")),
                            new LegalEditorialExecutionPlan.RequiredSetDependencies(
                                    membersBySet.getOrDefault(setId, List.of()),
                                    documentsBySet.getOrDefault(setId, List.of())));
                })
                .sorted(POINTER_ORDER)
                .toList();

        Map<LegalEditorialExecutionPlan.RequiredSetPointerKey, PointerProjection> actualByKey =
                uniqueIndex(actual, PointerProjection::key);
        Set<LegalEditorialExecutionPlan.RequiredSetPointerKey> expectedKeys =
                new LinkedHashSet<>();
        for (LegalEditorialExecutionPlan.ExpectedRequiredSetPointer survivor :
                context.expected().requiredSetPointers()) {
            expectedKeys.add(survivor.key());
            PointerProjection observed = actualByKey.get(survivor.key());
            PointerProjection expected = new PointerProjection(
                    survivor.key(),
                    survivor.requiredSetId(),
                    survivor.publicationId(),
                    survivor.requiredSetRevision(),
                    survivor.updatedAt(),
                    survivor.dependenciesEvidence().orElseThrow(
                            LegalEditorialRetirementWriter::mappingMismatch));
            if (!expected.equals(observed)) {
                throw stateMismatch();
            }
        }
        for (LegalEditorialExecutionPlan.RequiredSetPointerDelete deleted :
                context.commands().requiredSetPointerDeletes()) {
            expectedKeys.add(deleted.key());
            PointerProjection observed = actualByKey.get(deleted.key());
            if (observed == null
                    || !observed.requiredSetId().equals(deleted.expectedRequiredSetId())
                    || !observed.publicationId().equals(
                            context.plan().target().publicationUuid())
                    || !observed.dependencies().equals(
                            deleted.dependenciesEvidence().orElseThrow(
                                    LegalEditorialRetirementWriter::mappingMismatch))) {
                throw stateMismatch();
            }
        }
        if (!actualByKey.keySet().equals(Set.copyOf(expectedKeys))) {
            throw stateMismatch();
        }
    }

    private void requireExactSlots(
            RetirementContext context,
            Map<UUID, VersionEvidence> documents) {
        List<SlotProjection> expected = new ArrayList<>();
        context.expected().documentSlots().forEach(slot -> expected.add(new SlotProjection(
                slot.key(),
                slot.documentVersionId(),
                slot.documentLineId(),
                slot.publicationId())));
        for (LegalEditorialExecutionPlan.DocumentSlotDelete deleted :
                context.commands().documentSlotDeletes()) {
            VersionEvidence version = documents.get(deleted.expectedDocumentVersionId());
            if (version == null) {
                throw stateMismatch();
            }
            expected.add(new SlotProjection(
                    deleted.key(),
                    deleted.expectedDocumentVersionId(),
                    version.lineId(),
                    context.plan().target().publicationUuid()));
        }
        expected.sort(SLOT_ORDER);
        if (uniqueIndex(expected, SlotProjection::key).size() != expected.size()) {
            throw mappingMismatch();
        }

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

    private void revalidateSourceFingerprint(RetirementContext context) {
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

    private void executeDirectMutation(
            RetirementContext context,
            LockedGraph graph) {
        deleteRequiredSetPointers(context);
        deleteDocumentSlots(context, graph.documents());
        insertRequirementTransitions(context.commands().requirementTransitions());
        insertDocumentTransitions(context.commands().documentTransitions());
    }

    private void deleteRequiredSetPointers(RetirementContext context) {
        UUID publicationId = context.plan().target().publicationUuid();
        executeDeleteBatch("""
                DELETE FROM legal_requisito_conjuntos_actuales
                 WHERE locale = ? AND contexto = ? AND audiencia = ?
                   AND conjunto_id = ? AND publicacion_id = ?
                """, context.commands().requiredSetPointerDeletes().stream()
                .map(pointer -> new Object[]{
                        pointer.key().locale().getCodigo(),
                        pointer.key().context().name(),
                        pointer.key().audience().name(),
                        pointer.expectedRequiredSetId(),
                        publicationId})
                .toList());
    }

    private void deleteDocumentSlots(
            RetirementContext context,
            Map<UUID, VersionEvidence> documents) {
        UUID publicationId = context.plan().target().publicationUuid();
        executeDeleteBatch("""
                DELETE FROM legal_documento_vigentes
                 WHERE tipo = ? AND locale = ? AND contexto = ?
                   AND documento_version_id = ? AND documento_linea_id = ?
                   AND publicacion_id = ? AND estado_documento = 'VIGENTE'
                """, context.commands().documentSlotDeletes().stream()
                .map(slot -> {
                    VersionEvidence version = documents.get(
                            slot.expectedDocumentVersionId());
                    if (version == null) {
                        throw stateMismatch();
                    }
                    return new Object[]{
                            slot.key().type().name(),
                            slot.key().locale().getCodigo(),
                            slot.key().context().name(),
                            slot.expectedDocumentVersionId(),
                            version.lineId(),
                            publicationId};
                })
                .toList());
    }

    private void insertRequirementTransitions(
            List<LegalEditorialExecutionPlan.RequirementTransition> transitions) {
        executeInsertBatch("""
                INSERT INTO legal_requisito_transiciones
                    (requisito_version_id, estado_anterior, estado_nuevo,
                     motivo, ocurrido_en)
                VALUES (?, 'VIGENTE', 'RETIRADA', ?, ?)
                """, transitions.stream()
                .sorted(Comparator.comparing(
                        LegalEditorialExecutionPlan.RequirementTransition::requirementVersionId,
                        UUID_ORDER))
                .map(transition -> new Object[]{
                        transition.requirementVersionId(),
                        transition.reason(),
                        Timestamp.from(transition.occurredAt())})
                .toList());
    }

    private void insertDocumentTransitions(
            List<LegalEditorialExecutionPlan.DocumentTransition> transitions) {
        executeInsertBatch("""
                INSERT INTO legal_documento_transiciones
                    (documento_version_id, estado_anterior, estado_nuevo,
                     motivo, reemplazo_lote_id, ocurrido_en)
                VALUES (?, 'VIGENTE', 'RETIRADA', ?, NULL, ?)
                """, transitions.stream()
                .sorted(Comparator.comparing(
                        LegalEditorialExecutionPlan.DocumentTransition::documentVersionId,
                        UUID_ORDER))
                .map(transition -> new Object[]{
                        transition.documentVersionId(),
                        transition.reason(),
                        Timestamp.from(transition.occurredAt())})
                .toList());
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
            List<LegalEditorialExecutionPlan.DocumentTransition> delta = expected
                    .documentTransitions().stream()
                    .filter(transition -> transition.documentVersionId()
                            .equals(state.documentVersionId()))
                    .toList();
            if (delta.isEmpty()) {
                states.put(state.documentVersionId(), new ExpectedPreState(
                        state.state(),
                        state.stateChangedAt(),
                        state.lastReason(),
                        state.replacementBatchId()));
                continue;
            }
            if (delta.size() != 1
                    || state.state() != EstadoVersionLegal.RETIRADA
                    || !Objects.equals(state.stateChangedAt(), delta.getFirst().occurredAt())
                    || !Objects.equals(state.lastReason(), delta.getFirst().reason())
                    || state.replacementBatchId() != null) {
                throw mappingMismatch();
            }
            LegalEditorialExecutionPlan.DocumentTransition last = expected
                    .preexistingDocumentTransitions().stream()
                    .filter(transition -> transition.documentVersionId()
                            .equals(state.documentVersionId()))
                    .reduce((left, right) -> right)
                    .orElseThrow(LegalEditorialRetirementWriter::mappingMismatch);
            if (last.newState() != EstadoVersionLegal.VIGENTE) {
                throw mappingMismatch();
            }
            states.put(state.documentVersionId(), new ExpectedPreState(
                    EstadoVersionLegal.VIGENTE,
                    last.occurredAt(),
                    last.reason(),
                    last.replacementBatchId()));
        }
        return Map.copyOf(states);
    }

    private static Map<UUID, ExpectedPreState> expectedRequirementPreStates(
            LegalEditorialExecutionPlan.ExpectedPostState expected) {
        Map<UUID, ExpectedPreState> states = new HashMap<>();
        for (LegalEditorialExecutionPlan.ExpectedRequirementState state :
                expected.requirementStates()) {
            List<LegalEditorialExecutionPlan.RequirementTransition> delta = expected
                    .requirementTransitions().stream()
                    .filter(transition -> transition.requirementVersionId()
                            .equals(state.requirementVersionId()))
                    .toList();
            if (delta.isEmpty()) {
                states.put(state.requirementVersionId(), new ExpectedPreState(
                        state.state(),
                        state.stateChangedAt(),
                        state.lastReason(),
                        null));
                continue;
            }
            if (delta.size() != 1
                    || state.state() != EstadoVersionLegal.RETIRADA
                    || !Objects.equals(state.stateChangedAt(), delta.getFirst().occurredAt())
                    || !Objects.equals(state.lastReason(), delta.getFirst().reason())) {
                throw mappingMismatch();
            }
            LegalEditorialExecutionPlan.RequirementTransition last = expected
                    .preexistingRequirementTransitions().stream()
                    .filter(transition -> transition.requirementVersionId()
                            .equals(state.requirementVersionId()))
                    .reduce((left, right) -> right)
                    .orElseThrow(LegalEditorialRetirementWriter::mappingMismatch);
            if (last.newState() != EstadoVersionLegal.VIGENTE) {
                throw mappingMismatch();
            }
            states.put(state.requirementVersionId(), new ExpectedPreState(
                    EstadoVersionLegal.VIGENTE,
                    last.occurredAt(),
                    last.reason(),
                    null));
        }
        return Map.copyOf(states);
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
                    STATE_LOCATION + "/" + description);
        }
    }

    private static Map<UUID, List<UUID>> groupUuidRows(
            List<Map<String, Object>> rows,
            String groupKey,
            String valueKey) {
        Map<UUID, List<UUID>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent(
                    requiredUuid(row, groupKey),
                    ignored -> new ArrayList<>())
                    .add(requiredUuid(row, valueKey));
        }
        return grouped;
    }

    private static LegalEditorialExecutionPlan.RequiredSetPointerKey pointerKey(
            Map<String, Object> row) {
        return new LegalEditorialExecutionPlan.RequiredSetPointerKey(
                LocaleLegal.fromCodigo(requiredString(row, "locale")),
                ContextoLegal.valueOf(requiredString(row, "contexto")),
                AudienciaLegal.valueOf(requiredString(row, "audiencia")));
    }

    private static <K, V> Map<K, V> uniqueIndex(
            List<V> values,
            Function<V, K> keyExtractor) {
        Map<K, V> indexed = new LinkedHashMap<>();
        for (V value : values) {
            if (indexed.put(keyExtractor.apply(value), value) != null) {
                throw stateMismatch();
            }
        }
        return Map.copyOf(indexed);
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
        throw new IllegalStateException("La observacion RETIRE carece de " + key);
    }

    private static UUID optionalUuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null || value instanceof UUID) {
            return (UUID) value;
        }
        throw new IllegalStateException(
                "La observacion RETIRE contiene un " + key + " invalido");
    }

    private static String requiredString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalStateException("La observacion RETIRE carece de " + key);
    }

    private static String optionalString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalStateException(
                "La observacion RETIRE contiene un " + key + " invalido");
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            default -> throw new IllegalStateException(
                    "La observacion RETIRE contiene un instante invalido");
        };
    }

    private static Instant instantRequired(Object value) {
        return Objects.requireNonNull(instant(value), "instante RETIRE");
    }

    private static String placeholders(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Se requiere al menos un UUID RETIRE");
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
                "El DML RETIRE no afecto exactamente una fila esperada");
    }

    private record RetirementContext(
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

        private boolean matches(VersionEvidence actual) {
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
            UUID publicationId,
            String revision,
            Instant updatedAt,
            LegalEditorialExecutionPlan.RequiredSetDependencies dependencies) {
    }
}
