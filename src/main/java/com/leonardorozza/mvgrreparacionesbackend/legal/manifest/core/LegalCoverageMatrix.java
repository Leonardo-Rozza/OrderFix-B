package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Frozen minimum coverage matrix for a complete legal publication release.
 *
 * <p>The matrix is independent from persistence and only aggregates documents referenced by
 * required requirements. Contract-level defects such as duplicate keys and unknown references
 * belong to {@code LegalManifestContractValidator}; unresolved references are deliberately ignored
 * here and therefore cannot satisfy coverage.</p>
 */
public final class LegalCoverageMatrix {

    private static final List<MatrixRow> ROWS = List.of(
            row(
                    ContextoLegal.REGISTRO,
                    List.of(AudienciaLegal.ADMIN_TITULAR),
                    TipoDocumentoLegal.TERMINOS_SERVICIO,
                    TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                    TipoDocumentoLegal.ACUERDO_TRATAMIENTO_DATOS
            ),
            row(
                    ContextoLegal.PRIMER_INGRESO_EMPLEADO,
                    List.of(AudienciaLegal.USER),
                    TipoDocumentoLegal.TERMINOS_USUARIO,
                    TipoDocumentoLegal.AVISO_PRIVACIDAD_USUARIO,
                    TipoDocumentoLegal.COMPROMISO_CONFIDENCIALIDAD
            ),
            row(
                    ContextoLegal.CONTRATACION_PRO,
                    List.of(AudienciaLegal.ADMIN_TITULAR),
                    TipoDocumentoLegal.TERMINOS_SERVICIO,
                    TipoDocumentoLegal.CONDICIONES_PRO,
                    TipoDocumentoLegal.POLITICA_CANCELACIONES_REEMBOLSOS
            ),
            row(
                    ContextoLegal.ATESTACION_FOTOS,
                    List.of(AudienciaLegal.ADMIN_TITULAR, AudienciaLegal.USER),
                    TipoDocumentoLegal.AVISO_CLIENTES_TALLER,
                    TipoDocumentoLegal.ATESTACION_DATOS_CLIENTE
            ),
            row(
                    ContextoLegal.ATESTACION_CREDENCIALES,
                    List.of(AudienciaLegal.ADMIN_TITULAR, AudienciaLegal.USER),
                    TipoDocumentoLegal.AVISO_CLIENTES_TALLER,
                    TipoDocumentoLegal.ATESTACION_DATOS_CLIENTE
            ),
            row(
                    ContextoLegal.CIERRE_CUENTA,
                    List.of(AudienciaLegal.ADMIN_TITULAR),
                    TipoDocumentoLegal.POLITICA_CIERRE_CUENTA
            )
    );

    private static final List<ScopeKey> REQUIRED_SCOPES = ROWS.stream()
            .flatMap(row -> row.audiences().stream()
                    .map(audience -> new ScopeKey(row.context(), audience)))
            .toList();

    private static final Map<ScopeKey, Set<TipoDocumentoLegal>> REQUIRED_TYPES_BY_SCOPE =
            requiredTypesByScope();

    private static final Set<TipoDocumentoLegal> DOCUMENT_TYPES = immutableEnumSet(
            ROWS.stream()
                    .flatMap(row -> row.documentTypes().stream())
                    .toList()
    );

    private LegalCoverageMatrix() {
    }

    /** Returns the complete frozen catalog of the eleven legal document types. */
    public static Set<TipoDocumentoLegal> documentTypes() {
        return DOCUMENT_TYPES;
    }

    /** Returns the eight mandatory scopes in deterministic matrix order. */
    public static List<ScopeKey> requiredScopes() {
        return REQUIRED_SCOPES;
    }

    /**
     * Returns the minimum document types for a mandatory scope, or an empty set when the scope is
     * outside the frozen release matrix.
     */
    public static Set<TipoDocumentoLegal> requiredDocumentTypes(ScopeKey scope) {
        Objects.requireNonNull(scope, "scope");
        return REQUIRED_TYPES_BY_SCOPE.getOrDefault(scope, Set.of());
    }

    /**
     * Aggregates document coverage for every mandatory scope.
     *
     * <p>Only requirements with {@link RequirementEntry#required()} equal to {@code true}
     * contribute. A document contributes only when its key resolves and its declared contexts
     * contain the requirement context.</p>
     */
    public static Coverage evaluate(
            Collection<RequirementEntry> requirements,
            Map<String, DocumentEntry> documentsByKey
    ) {
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(documentsByKey, "documentsByKey");

        Map<ScopeKey, MutableScopeCoverage> accumulated = new LinkedHashMap<>();
        for (ScopeKey scope : REQUIRED_SCOPES) {
            accumulated.put(scope, new MutableScopeCoverage());
        }

        for (RequirementEntry requirement : requirements) {
            Objects.requireNonNull(requirement, "requirements cannot contain null");
            if (!requirement.required()) {
                continue;
            }

            Set<TipoDocumentoLegal> compatibleDocumentTypes = compatibleDocumentTypes(
                    requirement,
                    documentsByKey
            );
            for (AudienciaLegal audience : requirement.roles()) {
                ScopeKey scope = new ScopeKey(requirement.context(), audience);
                MutableScopeCoverage scopeCoverage = accumulated.get(scope);
                if (scopeCoverage == null) {
                    continue;
                }
                scopeCoverage.hasRequiredRequirement = true;
                scopeCoverage.coveredDocumentTypes.addAll(compatibleDocumentTypes);
            }
        }

        List<ScopeCoverage> scopes = new ArrayList<>(REQUIRED_SCOPES.size());
        for (Map.Entry<ScopeKey, MutableScopeCoverage> entry : accumulated.entrySet()) {
            ScopeKey scope = entry.getKey();
            MutableScopeCoverage mutable = entry.getValue();
            Set<TipoDocumentoLegal> covered = immutableEnumSet(mutable.coveredDocumentTypes);
            EnumSet<TipoDocumentoLegal> missing = EnumSet.noneOf(TipoDocumentoLegal.class);
            missing.addAll(requiredDocumentTypes(scope));
            missing.removeAll(covered);
            scopes.add(new ScopeCoverage(
                    scope,
                    mutable.hasRequiredRequirement,
                    covered,
                    immutableEnumSet(missing)
            ));
        }
        return new Coverage(scopes);
    }

    private static Set<TipoDocumentoLegal> compatibleDocumentTypes(
            RequirementEntry requirement,
            Map<String, DocumentEntry> documentsByKey
    ) {
        EnumSet<TipoDocumentoLegal> compatible = EnumSet.noneOf(TipoDocumentoLegal.class);
        for (String documentKey : requirement.documents()) {
            DocumentEntry document = documentsByKey.get(documentKey);
            if (document != null && document.contexts().contains(requirement.context())) {
                compatible.add(document.type());
            }
        }
        return compatible;
    }

    private static MatrixRow row(
            ContextoLegal context,
            List<AudienciaLegal> audiences,
            TipoDocumentoLegal firstType,
            TipoDocumentoLegal... remainingTypes
    ) {
        EnumSet<TipoDocumentoLegal> types = EnumSet.of(firstType, remainingTypes);
        return new MatrixRow(context, audiences, types);
    }

    private static Map<ScopeKey, Set<TipoDocumentoLegal>> requiredTypesByScope() {
        Map<ScopeKey, Set<TipoDocumentoLegal>> matrix = new LinkedHashMap<>();
        for (MatrixRow row : ROWS) {
            for (AudienciaLegal audience : row.audiences()) {
                ScopeKey scope = new ScopeKey(row.context(), audience);
                Set<TipoDocumentoLegal> previous = matrix.put(scope, row.documentTypes());
                if (previous != null) {
                    throw new ExceptionInInitializerError("Duplicate legal coverage scope");
                }
            }
        }
        return Collections.unmodifiableMap(matrix);
    }

    private static Set<TipoDocumentoLegal> immutableEnumSet(
            Collection<TipoDocumentoLegal> values
    ) {
        if (values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    /** Identifies a release scope independently from locale and persistence state. */
    public record ScopeKey(ContextoLegal context, AudienciaLegal audience) {
        public ScopeKey {
            context = Objects.requireNonNull(context, "context");
            audience = Objects.requireNonNull(audience, "audience");
        }
    }

    /** Immutable aggregate for all mandatory release scopes. */
    public record Coverage(List<ScopeCoverage> scopes) {
        public Coverage {
            scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
        }

        public Optional<ScopeCoverage> scope(ScopeKey scope) {
            Objects.requireNonNull(scope, "scope");
            return scopes.stream()
                    .filter(candidate -> candidate.scope().equals(scope))
                    .findFirst();
        }

        public boolean complete() {
            return scopes.stream().allMatch(ScopeCoverage::complete);
        }
    }

    /** Coverage contributed by required requirements for one mandatory scope. */
    public record ScopeCoverage(
            ScopeKey scope,
            boolean hasRequiredRequirement,
            Set<TipoDocumentoLegal> coveredDocumentTypes,
            Set<TipoDocumentoLegal> missingDocumentTypes
    ) {
        public ScopeCoverage {
            scope = Objects.requireNonNull(scope, "scope");
            coveredDocumentTypes = immutableEnumSet(Objects.requireNonNull(
                    coveredDocumentTypes,
                    "coveredDocumentTypes"
            ));
            missingDocumentTypes = immutableEnumSet(Objects.requireNonNull(
                    missingDocumentTypes,
                    "missingDocumentTypes"
            ));
        }

        public boolean complete() {
            return hasRequiredRequirement && missingDocumentTypes.isEmpty();
        }
    }

    private record MatrixRow(
            ContextoLegal context,
            List<AudienciaLegal> audiences,
            Set<TipoDocumentoLegal> documentTypes
    ) {
        private MatrixRow {
            context = Objects.requireNonNull(context, "context");
            audiences = List.copyOf(Objects.requireNonNull(audiences, "audiences"));
            documentTypes = immutableEnumSet(Objects.requireNonNull(documentTypes, "documentTypes"));
        }
    }

    private static final class MutableScopeCoverage {
        private boolean hasRequiredRequirement;
        private final EnumSet<TipoDocumentoLegal> coveredDocumentTypes =
                EnumSet.noneOf(TipoDocumentoLegal.class);
    }
}
