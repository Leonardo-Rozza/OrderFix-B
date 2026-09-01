package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeSet;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection.ScopeRevision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance.ScopeOrigin;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRequiredSetAggregateReceipt.Outcome;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRequiredSetAggregateReplayVerifier.ExpectedAggregate;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRequiredSetAggregateReplayVerifier.VerifiedAggregate;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Materializes one immutable V28 aggregate inside the caller-owned protected transaction. */
final class LegalRequiredSetAggregateStore {

    private static final int MAX_SCOPES = 8;
    private static final String INSERT_HEADER_SQL = """
            INSERT INTO legal_requisito_agregados
                (id, perfil, locale, audiencia, revision_scheme,
                 required_set_revision, provenance_fingerprint, scope_count, creado_en)
            VALUES (?, ?, ?, ?, 'AGGREGATE_V1', ?, ?, ?, statement_timestamp())
            ON CONFLICT ON CONSTRAINT uk_legal_requisito_agregado_identidad_fisica
            DO NOTHING
            RETURNING id, perfil, locale, audiencia, revision_scheme,
                      required_set_revision, provenance_fingerprint, scope_count, creado_en
            """;
    private static final String SELECT_HEADER_BY_IDENTITY_SQL = """
            SELECT id, perfil, locale, audiencia, revision_scheme,
                   required_set_revision, provenance_fingerprint, scope_count, creado_en
              FROM legal_requisito_agregados
             WHERE perfil = ?
               AND locale = ?
               AND audiencia = ?
               AND revision_scheme = 'AGGREGATE_V1'
               AND required_set_revision = ?
               AND provenance_fingerprint = ?
             LIMIT 2
            """;
    private static final String INSERT_SCOPES_SQL = """
            INSERT INTO legal_requisito_agregado_scopes
                (agregado_id, scope_ordinal, contexto, conjunto_id, publicacion_id,
                 locale, audiencia, required_set_revision)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final LegalRequiredSetAggregateRevisionCalculator revisionCalculator;
    private final LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator;
    private final LegalRequiredSetAggregateReplayVerifier replayVerifier;
    private final Supplier<UUID> aggregateIdGenerator;

    LegalRequiredSetAggregateStore(
            JdbcTemplate jdbc,
            LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator) {
        this(
                jdbc,
                revisionCalculator,
                provenanceCalculator,
                new LegalRequiredSetAggregateReplayVerifier(
                        jdbc,
                        revisionCalculator,
                        provenanceCalculator));
    }

    LegalRequiredSetAggregateStore(
            JdbcTemplate jdbc,
            LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator,
            LegalRequiredSetAggregateReplayVerifier replayVerifier) {
        this(
                jdbc,
                revisionCalculator,
                provenanceCalculator,
                replayVerifier,
                UUID::randomUUID);
    }

    LegalRequiredSetAggregateStore(
            JdbcTemplate jdbc,
            LegalRequiredSetAggregateRevisionCalculator revisionCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator,
            LegalRequiredSetAggregateReplayVerifier replayVerifier,
            Supplier<UUID> aggregateIdGenerator) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.revisionCalculator = Objects.requireNonNull(
                revisionCalculator,
                "revisionCalculator");
        this.provenanceCalculator = Objects.requireNonNull(
                provenanceCalculator,
                "provenanceCalculator");
        this.replayVerifier = Objects.requireNonNull(replayVerifier, "replayVerifier");
        this.aggregateIdGenerator = Objects.requireNonNull(
                aggregateIdGenerator,
                "aggregateIdGenerator");
        if (!replayVerifier.usesJdbc(jdbc)) {
            throw new IllegalArgumentException(
                    "El store agregado requiere una única sesión JDBC compartida");
        }
    }

    LegalRequiredSetAggregateReceipt materialize(
            LegalApplicableScopeSet applicableScopes,
            LegalEditorialTimeBoundary boundary) {
        LegalApplicableScopeSet accredited = Objects.requireNonNull(
                applicableScopes,
                "applicableScopes");
        LegalEditorialTimeBoundary timeBoundary = Objects.requireNonNull(boundary, "boundary");

        List<SnapshotScope> snapshot = readCurrentSnapshot(accredited);
        LegalRequiredSetAggregateProjection projection = new LegalRequiredSetAggregateProjection(
                EsquemaRevisionLegal.AGGREGATE_V1,
                accredited.locale(),
                accredited.audience(),
                snapshot.stream().map(SnapshotScope::revision).toList());
        LegalRequiredSetAggregateProvenance provenance =
                new LegalRequiredSetAggregateProvenance(
                        accredited.profile(),
                        accredited.locale(),
                        accredited.audience(),
                        snapshot.stream().map(SnapshotScope::origin).toList());
        String requiredSetRevision = revisionCalculator.calculate(projection);
        String provenanceFingerprint = provenanceCalculator.calculate(provenance);
        ExpectedAggregate expected = new ExpectedAggregate(
                projection,
                requiredSetRevision,
                provenance,
                provenanceFingerprint);

        UUID candidateId = Objects.requireNonNull(
                aggregateIdGenerator.get(),
                "aggregateIdGenerator result");
        ResolvedHeader resolved = insertOrResolveHeader(
                candidateId,
                expected,
                timeBoundary);
        if (resolved.outcome() == Outcome.CREATED) {
            insertScopes(resolved.header().id(), accredited, snapshot);
        }

        VerifiedAggregate verified = replayVerifier.verify(
                resolved.header().id(),
                resolved.outcome(),
                expected,
                timeBoundary);
        if (!verified.aggregateId().equals(resolved.header().id())
                || !verified.createdAt().equals(resolved.header().createdAt())) {
            throw new IllegalStateException(
                    "El replay agregado no coincide con la identidad materializada");
        }

        return new LegalRequiredSetAggregateReceipt(
                resolved.outcome(),
                verified.aggregateId(),
                requiredSetRevision,
                provenanceFingerprint,
                provenance,
                verified.createdAt());
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate && replayVerifier.usesJdbc(candidate);
    }

    private List<SnapshotScope> readCurrentSnapshot(LegalApplicableScopeSet applicableScopes) {
        List<ContextoLegal> contexts = applicableScopes.contexts();
        String sql = currentSnapshotSql(contexts.size());
        List<Object> arguments = new ArrayList<>(contexts.size() * 2 + 2);
        for (int index = 0; index < contexts.size(); index++) {
            arguments.add(contexts.get(index).name());
            arguments.add(index + 1);
        }
        arguments.add(applicableScopes.locale().getCodigo());
        arguments.add(applicableScopes.audience().name());

        List<SnapshotRow> rows = jdbc.query(
                sql,
                LegalRequiredSetAggregateStore::mapSnapshotRow,
                arguments.toArray());
        if (rows == null || rows.size() != contexts.size() || rows.size() > MAX_SCOPES) {
            throw new IllegalStateException(
                    "El snapshot agregado no contiene todos los scopes acreditados");
        }

        SnapshotScope[] byOrdinal = new SnapshotScope[contexts.size()];
        EnumSet<ContextoLegal> observedContexts = EnumSet.noneOf(ContextoLegal.class);
        for (SnapshotRow row : rows) {
            if (row == null || row.scopeOrdinal() < 1 || row.scopeOrdinal() > contexts.size()) {
                throw new IllegalStateException("El snapshot agregado posee un ordinal inválido");
            }
            int index = row.scopeOrdinal() - 1;
            if (byOrdinal[index] != null) {
                throw new IllegalStateException("El snapshot agregado repite un ordinal");
            }

            ContextoLegal context = persistedContext(row.context());
            if (context != contexts.get(index)
                    || !observedContexts.add(context)
                    || !applicableScopes.locale().getCodigo().equals(row.locale())
                    || !applicableScopes.audience().name().equals(row.audience())
                    || row.requiredSetId() == null
                    || row.publicationId() == null) {
                throw new IllegalStateException(
                        "El snapshot agregado no coincide con los scopes acreditados");
            }

            ScopeRevision revision = persistedRevision(context, row.requiredSetRevision());
            ScopeOrigin origin = new ScopeOrigin(
                    context,
                    row.requiredSetId(),
                    row.publicationId());
            byOrdinal[index] = new SnapshotScope(row.scopeOrdinal(), revision, origin);
        }

        List<SnapshotScope> normalized = new ArrayList<>(byOrdinal.length);
        for (SnapshotScope scope : byOrdinal) {
            if (scope == null) {
                throw new IllegalStateException(
                        "El snapshot agregado posee una cardinalidad incompleta");
            }
            normalized.add(scope);
        }
        return List.copyOf(normalized);
    }

    private ResolvedHeader insertOrResolveHeader(
            UUID candidateId,
            ExpectedAggregate expected,
            LegalEditorialTimeBoundary boundary) {
        List<HeaderRow> inserted = jdbc.query(
                INSERT_HEADER_SQL,
                LegalRequiredSetAggregateStore::mapHeaderRow,
                candidateId,
                expected.provenance().profile().name(),
                expected.projection().locale().getCodigo(),
                expected.projection().audience().name(),
                expected.requiredSetRevision(),
                expected.provenanceFingerprint(),
                expected.projection().scopes().size());
        if (inserted == null || inserted.size() > 1) {
            throw new IllegalStateException(
                    "El INSERT agregado devolvió una cardinalidad inesperada");
        }
        if (inserted.size() == 1) {
            HeaderRow created = requireExpectedHeader(inserted.getFirst(), expected);
            if (!candidateId.equals(created.id())) {
                throw new IllegalStateException(
                        "El INSERT agregado devolvió una identidad distinta");
            }
            if (created.createdAt().isBefore(boundary.observedAt())) {
                throw new IllegalStateException(
                        "El agregado fue fechado antes de adquirir el lock editorial");
            }
            return new ResolvedHeader(Outcome.CREATED, created);
        }

        List<HeaderRow> existing = jdbc.query(
                SELECT_HEADER_BY_IDENTITY_SQL,
                LegalRequiredSetAggregateStore::mapHeaderRow,
                expected.provenance().profile().name(),
                expected.projection().locale().getCodigo(),
                expected.projection().audience().name(),
                expected.requiredSetRevision(),
                expected.provenanceFingerprint());
        if (existing == null || existing.size() != 1) {
            throw new IllegalStateException(
                    "El conflicto agregado no resolvió una identidad física única");
        }
        return new ResolvedHeader(
                Outcome.REUSED,
                requireExpectedHeader(existing.getFirst(), expected));
    }

    private static HeaderRow requireExpectedHeader(
            HeaderRow header,
            ExpectedAggregate expected) {
        if (header == null
                || header.id() == null
                || header.createdAt() == null
                || !expected.provenance().profile().name().equals(header.profile())
                || !expected.projection().locale().getCodigo().equals(header.locale())
                || !expected.projection().audience().name().equals(header.audience())
                || !EsquemaRevisionLegal.AGGREGATE_V1.name().equals(header.revisionScheme())
                || !expected.requiredSetRevision().equals(header.requiredSetRevision())
                || !expected.provenanceFingerprint().equals(header.provenanceFingerprint())
                || expected.projection().scopes().size() != header.scopeCount()
                || header.createdAt().getNano() % 1_000 != 0) {
            throw new IllegalStateException(
                    "La cabecera agregada no coincide con la identidad esperada");
        }
        return header;
    }

    private void insertScopes(
            UUID aggregateId,
            LegalApplicableScopeSet applicableScopes,
            List<SnapshotScope> snapshot) {
        List<Object[]> rows = new ArrayList<>(snapshot.size());
        for (SnapshotScope scope : snapshot) {
            rows.add(new Object[]{
                    aggregateId,
                    scope.scopeOrdinal(),
                    scope.revision().context().name(),
                    scope.origin().requiredSetId(),
                    scope.origin().publicationId(),
                    applicableScopes.locale().getCodigo(),
                    applicableScopes.audience().name(),
                    scope.revision().requiredSetRevision()
            });
        }
        int[] updateCounts = jdbc.batchUpdate(INSERT_SCOPES_SQL, rows);
        if (updateCounts == null || updateCounts.length != rows.size()) {
            throw new IllegalStateException(
                    "El batch agregado devolvió una cardinalidad inesperada");
        }
        for (int updateCount : updateCounts) {
            if (updateCount != 1 && updateCount != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException(
                        "El batch agregado no insertó exactamente un scope");
            }
        }
    }

    private static String currentSnapshotSql(int scopeCount) {
        if (scopeCount < 1 || scopeCount > MAX_SCOPES) {
            throw new IllegalArgumentException("scopeCount debe estar entre uno y ocho");
        }
        String requestedRows = String.join(
                ", ",
                java.util.Collections.nCopies(scopeCount, "(?, ?)"));
        return """
                WITH requested(contexto, scope_ordinal) AS (
                    VALUES %s
                )
                SELECT requested.scope_ordinal,
                       actual.locale,
                       actual.contexto,
                       actual.audiencia,
                       actual.conjunto_id,
                       actual.publicacion_id,
                       conjunto.required_set_revision
                  FROM requested
                  JOIN legal_requisito_conjuntos_actuales actual
                    ON actual.contexto = requested.contexto
                   AND actual.locale = ?
                   AND actual.audiencia = ?
                  JOIN legal_requisito_conjuntos conjunto
                    ON conjunto.id = actual.conjunto_id
                   AND conjunto.publicacion_id = actual.publicacion_id
                   AND conjunto.locale = actual.locale
                   AND conjunto.contexto = actual.contexto
                   AND conjunto.audiencia = actual.audiencia
                 ORDER BY requested.scope_ordinal
                 LIMIT 9
                   FOR SHARE OF actual
                """.formatted(requestedRows);
    }

    private static SnapshotRow mapSnapshotRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new SnapshotRow(
                resultSet.getInt("scope_ordinal"),
                resultSet.getString("locale"),
                resultSet.getString("contexto"),
                resultSet.getString("audiencia"),
                resultSet.getObject("conjunto_id", UUID.class),
                resultSet.getObject("publicacion_id", UUID.class),
                resultSet.getString("required_set_revision"));
    }

    private static HeaderRow mapHeaderRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        OffsetDateTime createdAt = resultSet.getObject("creado_en", OffsetDateTime.class);
        return new HeaderRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("perfil"),
                resultSet.getString("locale"),
                resultSet.getString("audiencia"),
                resultSet.getString("revision_scheme"),
                resultSet.getString("required_set_revision"),
                resultSet.getString("provenance_fingerprint"),
                resultSet.getInt("scope_count"),
                createdAt == null ? null : createdAt.toInstant());
    }

    private static ContextoLegal persistedContext(String value) {
        try {
            return ContextoLegal.valueOf(Objects.requireNonNull(value, "contexto"));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("El snapshot agregado posee un contexto inválido", failure);
        }
    }

    private static ScopeRevision persistedRevision(ContextoLegal context, String revision) {
        try {
            return new ScopeRevision(context, revision);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("El snapshot agregado posee una revisión inválida", failure);
        }
    }

    private record SnapshotRow(
            int scopeOrdinal,
            String locale,
            String context,
            String audience,
            UUID requiredSetId,
            UUID publicationId,
            String requiredSetRevision) {
    }

    private record SnapshotScope(
            int scopeOrdinal,
            ScopeRevision revision,
            ScopeOrigin origin) {
    }

    private record HeaderRow(
            UUID id,
            String profile,
            String locale,
            String audience,
            String revisionScheme,
            String requiredSetRevision,
            String provenanceFingerprint,
            int scopeCount,
            Instant createdAt) {
    }

    private record ResolvedHeader(Outcome outcome, HeaderRow header) {
        private ResolvedHeader {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(header, "header");
        }
    }
}
