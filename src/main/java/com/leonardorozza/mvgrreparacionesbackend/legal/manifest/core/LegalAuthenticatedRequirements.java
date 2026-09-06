package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection.ScopeRevision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Complete canonical snapshot plus pure satisfaction decisions; never an authentication token. */
public final class LegalAuthenticatedRequirements {
    private static final int MAX_SCOPES = 8;

    public record Membership(int manifestOrdinal, String requirementKey, UUID requirementVersionId) {
        public Membership {
            if (manifestOrdinal < 1) {
                throw new IllegalArgumentException("El ordinal del manifiesto debe ser positivo");
            }
            LegalRequirementLineage.requireKey(requirementKey);
            Objects.requireNonNull(requirementVersionId, "requirementVersionId");
        }
    }

    public record Scope(int scopeOrdinal, LegalRequiredSetProjection projection, List<Membership> memberships) {
        public Scope {
            if (scopeOrdinal < 1 || scopeOrdinal > MAX_SCOPES) {
                throw new IllegalArgumentException("El ordinal de scope debe estar entre uno y ocho");
            }
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(memberships, "memberships");
            if (memberships.size() != projection.requirements().size()) {
                throw new IllegalArgumentException("La pertenencia debe describir todos los requisitos");
            }
            memberships = List.copyOf(memberships);
        }
    }

    /**
     * Validates every full projection before calculating revisions, without sorting or repairing input.
     * Persistence remains responsible for publication, membership, temporal and actor observation proofs.
     */
    public static final class Snapshot {
        private final LegalActorSnapshot actor;
        private final LegalApplicableScopeSet applicableScopes;
        private final List<Scope> scopes;
        private final List<ScopeRevision> scopeRevisions;
        private final String requiredSetRevision;
        private final List<RequirementProjection> requirements;

        public Snapshot(LegalActorSnapshot actor, LegalApplicableScopeSet applicableScopes, List<Scope> scopes) {
            this.actor = Objects.requireNonNull(actor, "actor");
            actor.requireEnabled();
            this.applicableScopes = Objects.requireNonNull(applicableScopes, "applicableScopes");
            if (applicableScopes.profile() != PerfilAgregadoLegal.AUTHENTICATED_PENDING
                    || applicableScopes.audience() != actor.audience()) {
                throw new IllegalArgumentException("El perfil y audiencia no corresponden al actor autenticado");
            }
            Objects.requireNonNull(scopes, "scopes");
            if (scopes.size() != applicableScopes.contexts().size()) {
                throw new IllegalArgumentException("Los scopes deben coincidir con la composición completa");
            }
            this.scopes = List.copyOf(scopes);
            // Bound every scope before allocating UTF-8 canonical content or calculating any revision.
            for (int index = 0; index < this.scopes.size(); index++) {
                Scope scope = this.scopes.get(index);
                if (scope.scopeOrdinal() != index + 1
                        || scope.projection().context() != applicableScopes.contexts().get(index)
                        || scope.projection().locale() != applicableScopes.locale()) {
                    throw new IllegalArgumentException("El orden, contexto o locale del scope no corresponde");
                }
                LegalRequiredSetRevisionCalculator.requireCanonicalizationCapacity(scope.projection());
            }
            List<RequirementProjection> complete = new ArrayList<>();
            Set<UUID> requirementIds = new HashSet<>();
            Set<String> requirementKeys = new HashSet<>();
            Map<UUID, DocumentProjection> documentsById = new HashMap<>();
            CanonicalTextValidator validator = new CanonicalTextValidator();
            for (Scope scope : this.scopes) {
                validateScope(scope, requirementIds, requirementKeys, documentsById, validator, complete);
            }
            requirements = List.copyOf(complete);
            LegalRequiredSetRevisionCalculator scopeCalculator = new LegalRequiredSetRevisionCalculator();
            List<ScopeRevision> revisions = new ArrayList<>(this.scopes.size());
            for (Scope scope : this.scopes) {
                revisions.add(new ScopeRevision(scope.projection().context(),
                        scopeCalculator.calculate(scope.projection())));
            }
            scopeRevisions = List.copyOf(revisions);
            requiredSetRevision = new LegalRequiredSetAggregateRevisionCalculator().calculate(
                    new LegalRequiredSetAggregateProjection(EsquemaRevisionLegal.AGGREGATE_V1,
                            applicableScopes.locale(), applicableScopes.audience(), scopeRevisions));
        }

        private static void validateScope(Scope scope, Set<UUID> requirementIds, Set<String> requirementKeys,
                                          Map<UUID, DocumentProjection> documentsById,
                                          CanonicalTextValidator validator, List<RequirementProjection> complete) {
            Set<UUID> scopeDocumentIds = new HashSet<>();
            int previousOrdinal = 0;
            for (int index = 0; index < scope.projection().requirements().size(); index++) {
                RequirementProjection requirement = scope.projection().requirements().get(index);
                Membership membership = scope.memberships().get(index);
                if (membership.manifestOrdinal() <= previousOrdinal
                        || !membership.requirementVersionId().equals(requirement.versionId())
                        || !requirementIds.add(requirement.versionId())
                        || !requirementKeys.add(membership.requirementKey())
                        || requirement.context() != scope.projection().context()) {
                    throw new IllegalArgumentException("La identidad, orden o pertenencia del requisito es inválida");
                }
                previousOrdinal = membership.manifestOrdinal();
                if (!LegalVisibleText.isPublishable(requirement.statement())
                        || !validator.validate(requirement.statement(), requirement.statementSha256(),
                                "private-requirements/statement").passed()) {
                    throw new IllegalArgumentException("La afirmación no es canónica o su digest no coincide");
                }
                if (requirement.documents().isEmpty()) {
                    throw new IllegalArgumentException("Cada requisito debe contener documentos");
                }
                Set<UUID> referenceIds = new HashSet<>();
                for (DocumentProjection document : requirement.documents()) {
                    if (!referenceIds.add(document.versionId())) {
                        throw new IllegalArgumentException("El requisito repite un documento");
                    }
                    scopeDocumentIds.add(document.versionId());
                    if (scopeDocumentIds.size() > LegalManifestLimits.MAX_DOCUMENTS) {
                        throw new IllegalArgumentException("El scope supera el límite de documentos distintos");
                    }
                    DocumentProjection previous = documentsById.get(document.versionId());
                    if (previous == null) {
                        validateDocument(document, scope.projection().locale(), validator);
                        documentsById.put(document.versionId(), document);
                    } else if (document.locale() != scope.projection().locale()
                            || !sameCanonicalDocument(previous, document)) {
                        throw new IllegalArgumentException("Las referencias documentales no describen la misma versión");
                    }
                }
                complete.add(requirement);
            }
        }

        private static void validateDocument(DocumentProjection document, LocaleLegal locale,
                                             CanonicalTextValidator validator) {
            if (document.locale() != locale) {
                throw new IllegalArgumentException("El documento no pertenece al locale del scope");
            }
            new LegalDocumentSummary(document.versionId(), document.type(), document.version(), document.title(),
                    document.sha256(), document.effectiveAt().toInstant(), EstadoVersionLegal.VIGENTE, document.locale());
            if (document.markdown().isEmpty()
                    || !validator.validate(document.markdown(), document.sha256(),
                            "private-requirements/document").passed()) {
                throw new IllegalArgumentException("El documento no es canónico o su digest no coincide");
            }
        }

        private static boolean sameCanonicalDocument(DocumentProjection first, DocumentProjection second) {
            return first == second || (first.type() == second.type()
                    && first.version().equals(second.version()) && first.title().equals(second.title())
                    && first.markdown().equals(second.markdown()) && first.sha256().equals(second.sha256())
                    && first.effectiveAt().toInstant().equals(second.effectiveAt().toInstant())
                    && first.locale() == second.locale());
        }

        public LegalActorSnapshot actor() { return actor; }
        public LegalApplicableScopeSet applicableScopes() { return applicableScopes; }
        public List<Scope> scopes() { return scopes; }
        public List<ScopeRevision> scopeRevisions() { return scopeRevisions; }
        public String requiredSetRevision() { return requiredSetRevision; }
        public List<RequirementProjection> requirements() { return requirements; }
    }

    public enum Satisfaction { EXACT, INHERITED, PENDING }

    public record Decision(UUID requirementVersionId, Satisfaction satisfaction, UUID acceptanceId) {
        public Decision {
            Objects.requireNonNull(requirementVersionId, "requirementVersionId");
            Objects.requireNonNull(satisfaction, "satisfaction");
            if ((satisfaction == Satisfaction.PENDING) != (acceptanceId == null)) {
                throw new IllegalArgumentException("Sólo una decisión satisfecha debe referenciar evidencia");
            }
        }
    }

    private final Snapshot snapshot;
    private final List<Decision> decisions;
    private final List<RequirementProjection> requirements;
    private final boolean hasRequiredPending;

    LegalAuthenticatedRequirements(Snapshot snapshot, List<Decision> decisions) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(decisions, "decisions");
        if (decisions.size() != snapshot.requirements().size()) {
            throw new IllegalArgumentException("Cada requisito completo necesita exactamente una decisión");
        }
        this.decisions = List.copyOf(decisions);
        List<RequirementProjection> pending = new ArrayList<>();
        boolean mandatory = false;
        for (int index = 0; index < this.decisions.size(); index++) {
            Decision decision = this.decisions.get(index);
            RequirementProjection requirement = snapshot.requirements().get(index);
            if (!decision.requirementVersionId().equals(requirement.versionId())) {
                throw new IllegalArgumentException("Las decisiones deben conservar el orden completo de requisitos");
            }
            if (decision.satisfaction() == Satisfaction.PENDING) {
                pending.add(requirement);
                mandatory |= requirement.required();
            }
        }
        requirements = List.copyOf(pending);
        hasRequiredPending = mandatory;
    }

    public Snapshot snapshot() { return snapshot; }
    public String requiredSetRevision() { return snapshot.requiredSetRevision(); }
    public List<Decision> decisions() { return decisions; }
    public List<RequirementProjection> requirements() { return requirements; }
    public boolean hasRequiredPending() { return hasRequiredPending; }
}
