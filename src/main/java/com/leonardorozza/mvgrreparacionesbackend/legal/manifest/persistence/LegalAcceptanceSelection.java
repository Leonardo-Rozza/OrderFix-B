package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Decision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Satisfaction;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Motivo;

/**
 * Pure selection after replay and availability. The caller must independently accredit the current
 * observation and canonical evidence under the same stable actor; these values are not credentials.
 */
final class LegalAcceptanceSelection {
    private static final int MAX_ACCEPTANCES = LegalAcceptanceCommandValidator.MAX_ACCEPTANCES;
    private static final int MAX_DOCUMENTS = LegalAcceptanceCommandValidator.MAX_DOCUMENTS_PER_ACCEPTANCE;
    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);

    enum Kind { DEDUP, EMPTY, WITH_ACTS }

    /** Canonical persisted fields, independent of the submitted type, digest, confirmation and order. */
    record ExistingAcceptance(UUID acceptanceId, long userId, long tallerId, UUID requirementVersionId,
            TipoActoLegal actType, String statementSha256, List<Document> documents) {
        ExistingAcceptance {
            require(acceptanceId != null && userId > 0 && tallerId > 0 && requirementVersionId != null
                    && actType != null && isDigest(statementSha256) && documents != null
                    && !documents.isEmpty() && documents.size() <= MAX_DOCUMENTS);
            Set<UUID> versions = new HashSet<>();
            for (Document document : documents) {
                require(document != null && document.documentoVersionId() != null && isDigest(document.sha256())
                        && versions.add(document.documentoVersionId()));
            }
            documents = List.copyOf(documents);
        }

        @Override public String toString() { return "ExistingAcceptance[redacted]"; }
    }

    /** Canonical new versions remain in snapshot order; existing IDs never name inherited witnesses. */
    record Selection(Kind kind, List<RequirementProjection> newRequirements, List<UUID> existingAcceptanceIds) {
        Selection {
            require(kind != null && newRequirements != null && existingAcceptanceIds != null
                    && newRequirements.size() <= MAX_ACCEPTANCES && existingAcceptanceIds.size() <= MAX_ACCEPTANCES
                    && newRequirements.size() + existingAcceptanceIds.size() <= MAX_ACCEPTANCES);
            Set<UUID> versions = new HashSet<>();
            for (RequirementProjection requirement : newRequirements) {
                require(requirement != null && versions.add(requirement.versionId()));
            }
            Set<UUID> ids = new HashSet<>();
            for (UUID id : existingAcceptanceIds) require(id != null && ids.add(id));
            require(switch (kind) {
                case DEDUP -> newRequirements.isEmpty() && !existingAcceptanceIds.isEmpty();
                case EMPTY -> newRequirements.isEmpty() && existingAcceptanceIds.isEmpty();
                case WITH_ACTS -> !newRequirements.isEmpty();
            });
            newRequirements = List.copyOf(newRequirements);
            existingAcceptanceIds = existingAcceptanceIds.stream().sorted(UUID_ORDER).toList();
        }

        @Override public String toString() { return "LegalAcceptanceSelection[redacted]"; }
    }

    Selection select(LegalAcceptanceCommand command, LegalAuthenticatedRequirements current,
            List<ExistingAcceptance> existing) {
        require(command != null && current != null && existing != null && existing.size() <= MAX_ACCEPTANCES
                && command.operation() == LegalAcceptanceCommand.Operation.AUTHENTICATED_ACCEPTANCE
                && command.actor().equals(current.snapshot().actor()));
        Map<UUID, RequirementProjection> currentById = new HashMap<>();
        current.snapshot().requirements().forEach(value -> currentById.put(value.versionId(), value));
        Map<UUID, Decision> decisions = new HashMap<>();
        current.decisions().forEach(value -> decisions.put(value.requirementVersionId(), value));

        Set<UUID> submittedIds = new HashSet<>();
        EnumSet<Motivo> motivos = EnumSet.noneOf(Motivo.class);
        for (Acceptance acceptance : command.acceptances()) {
            if (!submittedIds.add(acceptance.requisitoVersionId())) motivos.add(Motivo.REQUISITO_DUPLICADO);
            Set<UUID> documentIds = new HashSet<>();
            for (Document document : acceptance.documentos()) {
                if (!documentIds.add(document.documentoVersionId())) motivos.add(Motivo.DOCUMENTO_DUPLICADO);
            }
        }

        Map<UUID, ExistingAcceptance> existingById = new HashMap<>();
        Set<UUID> acceptanceIds = new HashSet<>();
        for (ExistingAcceptance acceptance : existing) {
            require(acceptance != null && acceptance.userId() == command.actor().userId()
                    && acceptance.tallerId() == command.actor().tallerId()
                    && submittedIds.contains(acceptance.requirementVersionId())
                    && acceptanceIds.add(acceptance.acceptanceId())
                    && existingById.putIfAbsent(acceptance.requirementVersionId(), acceptance) == null);
        }
        // Observation contradictions are infrastructure failures, never client semantic rejections.
        for (UUID submitted : submittedIds) {
            RequirementProjection requirement = currentById.get(submitted);
            if (requirement == null) continue;
            Decision decision = decisions.get(submitted);
            ExistingAcceptance acceptance = existingById.get(submitted);
            require(decision != null);
            if (decision.satisfaction() == Satisfaction.EXACT) {
                require(acceptance != null && decision.acceptanceId().equals(acceptance.acceptanceId())
                        && matchesCanonical(requirement, acceptance));
            } else {
                require(acceptance == null);
            }
        }

        if (!command.acceptances().isEmpty() && motivos.isEmpty()
                && command.acceptances().stream().allMatch(value -> matchesSubmitted(value,
                        existingById.get(value.requisitoVersionId())))) {
            return new Selection(Kind.DEDUP, List.of(), ids(existing));
        }
        if (!command.requiredSetRevision().equals(current.requiredSetRevision())) {
            throw LegalAcceptanceValidationException.stale(command.requiredSetRevision(), current);
        }

        for (Acceptance acceptance : command.acceptances()) {
            if (!acceptance.confirmado()) motivos.add(Motivo.CONFIRMACION_REQUERIDA);
            RequirementProjection requirement = currentById.get(acceptance.requisitoVersionId());
            if (requirement == null) {
                motivos.add(Motivo.REQUISITO_NO_PERTENECE_AL_CONJUNTO);
                continue;
            }
            if (acceptance.tipoActo() != requirement.actType()) motivos.add(Motivo.ACTO_NO_COINCIDE);
            if (!acceptance.afirmacionSha256().equals(requirement.statementSha256())) motivos.add(Motivo.DIGEST_NO_COINCIDE);
            Map<UUID, String> expected = new HashMap<>();
            requirement.documents().forEach(value -> expected.put(value.versionId(), value.sha256()));
            Set<UUID> seen = new HashSet<>();
            for (Document document : acceptance.documentos()) {
                seen.add(document.documentoVersionId());
                String digest = expected.get(document.documentoVersionId());
                if (digest == null) motivos.add(Motivo.DOCUMENTO_NO_PERTENECE_AL_REQUISITO);
                else if (!digest.equals(document.sha256())) motivos.add(Motivo.DIGEST_NO_COINCIDE);
            }
            if (!seen.containsAll(expected.keySet())) motivos.add(Motivo.DOCUMENTO_FALTANTE);
        }
        for (RequirementProjection pending : current.requirements()) {
            if (pending.required() && !submittedIds.contains(pending.versionId())) motivos.add(Motivo.REQUISITO_FALTANTE);
        }
        if (!motivos.isEmpty()) throw LegalAcceptanceValidationException.invalid(motivos);

        if (command.acceptances().isEmpty()) return new Selection(Kind.EMPTY, List.of(), List.of());
        List<RequirementProjection> newRequirements = new ArrayList<>();
        for (RequirementProjection requirement : current.snapshot().requirements()) {
            if (submittedIds.contains(requirement.versionId()) && !existingById.containsKey(requirement.versionId())) {
                newRequirements.add(requirement);
            }
        }
        return new Selection(Kind.WITH_ACTS, newRequirements, ids(existing));
    }

    private static boolean matchesSubmitted(Acceptance submitted, ExistingAcceptance existing) {
        return existing != null && submitted.confirmado() && submitted.tipoActo() == existing.actType()
                && submitted.afirmacionSha256().equals(existing.statementSha256())
                && sameDocuments(submitted.documentos(), existing.documents());
    }

    private static boolean matchesCanonical(RequirementProjection current, ExistingAcceptance existing) {
        return current.actType() == existing.actType() && current.statementSha256().equals(existing.statementSha256())
                && sameDocuments(current.documents().stream()
                        .map(value -> new Document(value.versionId(), value.sha256())).toList(), existing.documents());
    }

    private static boolean sameDocuments(List<Document> left, List<Document> right) {
        if (left.size() != right.size()) return false;
        Map<UUID, String> expected = new HashMap<>();
        right.forEach(value -> expected.put(value.documentoVersionId(), value.sha256()));
        return left.stream().allMatch(value -> value.sha256().equals(expected.get(value.documentoVersionId())));
    }

    private static List<UUID> ids(List<ExistingAcceptance> existing) {
        return existing.stream().map(ExistingAcceptance::acceptanceId).toList();
    }

    private static boolean isDigest(String value) {
        return value != null && value.length() == 64 && value.matches("[0-9a-f]{64}");
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("La observación legal no es válida.");
    }
}
