package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Decision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Satisfaction;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Snapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementVersion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Pure satisfaction of complete, independently accredited legal snapshots. No evidence is created.
 * The database reader must establish publication history and interval completeness before calling;
 * this evaluator cannot discover a version omitted from an otherwise consistent in-memory list.
 */
public final class LegalRequirementSatisfactionEvaluator {

    public LegalAuthenticatedRequirements evaluate(Snapshot snapshot, LegalRequirementLineage lineage,
                                                   List<Acceptance> evidence) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(lineage, "lineage");
        Objects.requireNonNull(evidence, "evidence");
        require(evidence.size() <= LegalRequirementLineage.MAX_OBSERVATION_ROWS,
                "La evidencia supera la capacidad de observación");
        List<Acceptance> accepted = List.copyOf(evidence);
        long rows = lineage.rowCount();
        for (Acceptance acceptance : accepted) {
            rows += 1L + acceptance.documents().size();
            require(rows <= LegalRequirementLineage.MAX_OBSERVATION_ROWS,
                    "El grafo de linajes y evidencia supera la capacidad de observación");
        }

        // Accredit every supplied member before an exact match can short-circuit satisfaction.
        validateCurrent(snapshot, lineage);
        Map<String, List<Acceptance>> basesByKey = validateEvidence(snapshot, lineage, accepted);
        Map<UUID, IntervalIndex> requirementIntervals = new HashMap<>();
        for (var line : lineage.requirementLines()) {
            requirementIntervals.put(line.lineId(), new IntervalIndex(line.versions(),
                    RequirementVersion::ordinal,
                    version -> published(version.state()) && version.requiresReacceptance()));
        }
        Map<UUID, IntervalIndex> documentIntervals = new HashMap<>();
        for (var line : lineage.documentLines()) {
            documentIntervals.put(line.lineId(), new IntervalIndex(line.versions(),
                    DocumentVersion::ordinal,
                    version -> published(version.state()) && version.requiresReacceptance()));
        }

        List<Decision> decisions = new ArrayList<>(snapshot.requirements().size());
        for (RequirementProjection current : snapshot.requirements()) {
            var line = lineage.requirementLine(current.versionId());
            List<Acceptance> bases = basesByKey.getOrDefault(line.key(), List.of());
            Acceptance exact = null;
            for (Acceptance base : bases) {
                if (base.requirementVersionId().equals(current.versionId())) {
                    exact = base;
                }
            }
            if (exact != null) {
                decisions.add(new Decision(current.versionId(), Satisfaction.EXACT, exact.acceptanceId()));
                continue;
            }
            Acceptance witness = null;
            for (Acceptance base : bases) {
                if (inherits(current.versionId(), base, lineage, requirementIntervals, documentIntervals)
                        && (witness == null || base.acceptanceId().toString()
                        .compareTo(witness.acceptanceId().toString()) < 0)) {
                    // Stable witness among independently valid bases, never a chronological filter.
                    witness = base;
                }
            }
            decisions.add(witness == null
                    ? new Decision(current.versionId(), Satisfaction.PENDING, null)
                    : new Decision(current.versionId(), Satisfaction.INHERITED, witness.acceptanceId()));
        }
        return new LegalAuthenticatedRequirements(snapshot, decisions);
    }

    private static void validateCurrent(Snapshot snapshot, LegalRequirementLineage lineage) {
        for (var scope : snapshot.scopes()) {
            for (int index = 0; index < scope.projection().requirements().size(); index++) {
                var current = scope.projection().requirements().get(index);
                var membership = scope.memberships().get(index);
                var line = lineage.requirementLine(current.versionId());
                var version = lineage.requirementVersion(current.versionId());
                require(line.key().equals(membership.requirementKey())
                                && line.context() == current.context()
                                && line.locale() == scope.projection().locale()
                                && line.actType() == current.actType()
                                && line.audiences().contains(snapshot.actor().audience())
                                && version.state() == EstadoVersionLegal.VIGENTE
                                && version.statementSha256().equals(current.statementSha256())
                                && version.required() == current.required()
                                && version.documents().size() == current.documents().size(),
                        "El requisito actual no coincide con su línea y versión canónicas");
                for (int documentIndex = 0; documentIndex < current.documents().size(); documentIndex++) {
                    var document = current.documents().get(documentIndex);
                    var reference = version.documents().get(documentIndex);
                    var documentLine = lineage.documentLine(document.versionId());
                    var documentVersion = lineage.documentVersion(document.versionId());
                    require(reference.versionId().equals(document.versionId())
                                    && reference.key().equals(documentLine.key())
                                    && reference.sha256().equals(document.sha256())
                                    && documentVersion.sha256().equals(document.sha256())
                                    && documentVersion.state() == EstadoVersionLegal.VIGENTE
                                    && documentLine.type() == document.type()
                                    && documentLine.locale() == document.locale(),
                            "El documento actual no coincide con la referencia canónica");
                }
            }
        }
    }

    private static Map<String, List<Acceptance>> validateEvidence(
            Snapshot snapshot, LegalRequirementLineage lineage, List<Acceptance> evidence) {
        Map<String, List<Acceptance>> byKey = new HashMap<>();
        Set<UUID> acceptanceIds = new HashSet<>();
        Set<UUID> acceptedVersions = new HashSet<>();
        for (Acceptance acceptance : evidence) {
            require(acceptance.userId() == snapshot.actor().userId()
                            && acceptance.tallerId() == snapshot.actor().tallerId()
                            && acceptanceIds.add(acceptance.acceptanceId())
                            && acceptedVersions.add(acceptance.requirementVersionId()),
                    "La evidencia tiene actor, pertenencia o unicidad incompatibles");
            var line = lineage.requirementLine(acceptance.requirementVersionId());
            var version = lineage.requirementVersion(acceptance.requirementVersionId());
            require(published(version.state())
                            && line.audiences().contains(acceptance.historicalRole().toAudienciaLegal())
                            && version.statementSha256().equals(acceptance.statementSha256())
                            && version.documents().size() == acceptance.documents().size(),
                    "El snapshot de evidencia no coincide con el requisito canónico");
            for (int index = 0; index < version.documents().size(); index++) {
                var reference = version.documents().get(index);
                var document = acceptance.documents().get(index);
                require(reference.key().equals(document.key())
                                && reference.versionId().equals(document.versionId())
                                && reference.sha256().equals(document.sha256())
                                && published(lineage.documentVersion(document.versionId()).state()),
                        "El snapshot documental de evidencia no coincide con su referencia canónica");
            }
            byKey.computeIfAbsent(line.key(), ignored -> new ArrayList<>()).add(acceptance);
        }
        return byKey;
    }

    private static boolean inherits(UUID currentId, Acceptance acceptance, LegalRequirementLineage lineage,
                                    Map<UUID, IntervalIndex> requirementIntervals,
                                    Map<UUID, IntervalIndex> documentIntervals) {
        var base = lineage.requirementVersion(acceptance.requirementVersionId());
        var target = lineage.requirementVersion(currentId);
        var line = lineage.requirementLine(currentId);
        if (base.ordinal() >= target.ordinal()) {
            return false;
        }
        boolean canInherit = requirementIntervals.get(line.lineId()).allows(base.ordinal(), target.ordinal());
        Map<String, LegalRequirementLineage.EvidenceDocument> baseDocuments = new HashMap<>();
        for (var document : acceptance.documents()) {
            baseDocuments.put(document.key(), document);
        }
        for (var reference : target.documents()) {
            var acceptedDocument = baseDocuments.get(reference.key());
            if (acceptedDocument == null) {
                canInherit = false;
                continue;
            }
            var baseDocument = lineage.documentVersion(acceptedDocument.versionId());
            var targetDocument = lineage.documentVersion(reference.versionId());
            if (baseDocument.ordinal() > targetDocument.ordinal()) {
                canInherit = false;
                continue;
            }
            var documentLine = lineage.documentLine(reference.versionId());
            // Each document interval is global to its immutable key, never filtered by context.
            canInherit &= documentIntervals.get(documentLine.lineId())
                    .allows(baseDocument.ordinal(), targetDocument.ordinal());
        }
        return canInherit;
    }

    private static boolean published(EstadoVersionLegal state) {
        return state != EstadoVersionLegal.BORRADOR;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Prefix flags and binary bounds avoid rescanning an entire lineage for every evidence base. */
    private static final class IntervalIndex {
        private final int[] ordinals;
        private final int[] blockingPrefix;

        private <T> IntervalIndex(List<T> versions, ToIntFunction<T> ordinal, Predicate<T> blocks) {
            ordinals = new int[versions.size()];
            blockingPrefix = new int[versions.size() + 1];
            for (int index = 0; index < versions.size(); index++) {
                T version = versions.get(index);
                ordinals[index] = ordinal.applyAsInt(version);
                blockingPrefix[index + 1] = blockingPrefix[index] + (blocks.test(version) ? 1 : 0);
            }
        }

        private boolean allows(int fromExclusive, int toInclusive) {
            int from = upperBound(fromExclusive);
            int to = upperBound(toInclusive);
            require(to - from <= LegalRequirementLineage.MAX_INTERVAL_VERSIONS,
                    "El intervalo de herencia supera la capacidad de versiones");
            return blockingPrefix[to] == blockingPrefix[from];
        }

        private int upperBound(int ordinal) {
            int low = 0;
            int high = ordinals.length;
            while (low < high) {
                int middle = low + (high - low) / 2;
                if (ordinals[middle] <= ordinal) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }
    }
}
