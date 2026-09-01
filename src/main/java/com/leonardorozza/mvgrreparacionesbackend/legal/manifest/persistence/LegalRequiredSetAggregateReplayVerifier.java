package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProjection.ScopeRevision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenance.ScopeOrigin;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateProvenanceCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetAggregateRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** SELECT-only reconstruction and cryptographic verification of one persisted V28 aggregate. */
final class LegalRequiredSetAggregateReplayVerifier {

    static final String HEADER_SQL = """
            SELECT id, perfil, locale, audiencia, revision_scheme,
                   required_set_revision, provenance_fingerprint, scope_count, creado_en
              FROM legal_requisito_agregados
             WHERE id = ?
             LIMIT 2
            """;
    static final String MEMBERS_SQL = """
            SELECT agregado_id, scope_ordinal, contexto, conjunto_id, publicacion_id,
                   locale, audiencia, required_set_revision
              FROM legal_requisito_agregado_scopes
             WHERE agregado_id = ?
             ORDER BY scope_ordinal, contexto
             LIMIT 9
            """;

    private static final Comparator<MemberRow> MEMBER_ORDER = Comparator
            .comparingInt(MemberRow::ordinal)
            .thenComparing(row -> row.context().ordinal());

    private final JdbcTemplate jdbc;
    private final LegalRequiredSetAggregateRevisionCalculator semanticCalculator;
    private final LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator;

    LegalRequiredSetAggregateReplayVerifier(
            JdbcTemplate jdbc,
            LegalRequiredSetAggregateRevisionCalculator semanticCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.semanticCalculator = Objects.requireNonNull(semanticCalculator, "semanticCalculator");
        this.provenanceCalculator = Objects.requireNonNull(
                provenanceCalculator,
                "provenanceCalculator");
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    VerifiedAggregate verify(
            UUID aggregateId,
            LegalRequiredSetAggregateReceipt.Outcome outcome,
            ExpectedAggregate expected,
            LegalEditorialTimeBoundary boundary) {
        UUID requiredId = Objects.requireNonNull(aggregateId, "aggregateId");
        LegalRequiredSetAggregateReceipt.Outcome requiredOutcome = Objects.requireNonNull(
                outcome,
                "outcome");
        ExpectedAggregate requiredExpected = Objects.requireNonNull(expected, "expected");
        LegalEditorialTimeBoundary requiredBoundary = Objects.requireNonNull(boundary, "boundary");

        HeaderRow header = readExactHeader(requiredId);
        requireExactHeader(requiredId, requiredExpected, header);
        if (requiredOutcome == LegalRequiredSetAggregateReceipt.Outcome.CREATED
                && header.createdAt().toInstant().isBefore(requiredBoundary.observedAt())) {
            throw corrupt("created aggregate predates its post-lock boundary");
        }

        List<MemberRow> members = readMembers(requiredId);
        Reconstructed reconstructed = reconstruct(header, members);
        requireExactReconstruction(requiredExpected, reconstructed);
        return new VerifiedAggregate(requiredId, header.createdAt().toInstant());
    }

    private HeaderRow readExactHeader(UUID aggregateId) {
        try {
            List<HeaderRow> rows = jdbc.query(HEADER_SQL, (resultSet, rowNumber) -> new HeaderRow(
                    resultSet.getObject("id", UUID.class),
                    PerfilAgregadoLegal.valueOf(resultSet.getString("perfil")),
                    LocaleLegal.fromCodigo(resultSet.getString("locale")),
                    AudienciaLegal.valueOf(resultSet.getString("audiencia")),
                    EsquemaRevisionLegal.valueOf(resultSet.getString("revision_scheme")),
                    resultSet.getString("required_set_revision"),
                    resultSet.getString("provenance_fingerprint"),
                    resultSet.getInt("scope_count"),
                    resultSet.getObject("creado_en", OffsetDateTime.class)), aggregateId);
            if (rows == null || rows.size() != 1) {
                throw corrupt("aggregate header cardinality is not exactly one");
            }
            return rows.getFirst();
        } catch (DataAccessException failure) {
            throw failure;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw corrupt("aggregate header row is invalid", failure);
        }
    }

    private List<MemberRow> readMembers(UUID aggregateId) {
        try {
            List<MemberRow> rows = jdbc.query(MEMBERS_SQL, (resultSet, rowNumber) -> new MemberRow(
                    resultSet.getObject("agregado_id", UUID.class),
                    resultSet.getInt("scope_ordinal"),
                    ContextoLegal.valueOf(resultSet.getString("contexto")),
                    resultSet.getObject("conjunto_id", UUID.class),
                    resultSet.getObject("publicacion_id", UUID.class),
                    LocaleLegal.fromCodigo(resultSet.getString("locale")),
                    AudienciaLegal.valueOf(resultSet.getString("audiencia")),
                    resultSet.getString("required_set_revision")), aggregateId);
            if (rows == null || rows.size() > 8) {
                throw corrupt("aggregate member cardinality is outside the accredited limit");
            }
            ArrayList<MemberRow> normalized = new ArrayList<>(rows);
            normalized.sort(MEMBER_ORDER);
            return List.copyOf(normalized);
        } catch (DataAccessException failure) {
            throw failure;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw corrupt("aggregate member row is invalid", failure);
        }
    }

    private static void requireExactHeader(
            UUID aggregateId,
            ExpectedAggregate expected,
            HeaderRow header) {
        if (!aggregateId.equals(header.id())
                || header.profile() != expected.provenance().profile()
                || header.locale() != expected.projection().locale()
                || header.locale() != expected.provenance().locale()
                || header.audience() != expected.projection().audience()
                || header.audience() != expected.provenance().audience()
                || header.revisionScheme() != EsquemaRevisionLegal.AGGREGATE_V1
                || !expected.token().equals(header.requiredSetRevision())
                || !expected.fingerprint().equals(header.provenanceFingerprint())
                || header.scopeCount() != expected.projection().scopes().size()
                || header.scopeCount() != expected.provenance().scopes().size()
                || header.createdAt() == null
                || header.createdAt().toInstant().getNano() % 1_000 != 0) {
            throw corrupt("aggregate header differs from the expected identity");
        }
    }

    private static Reconstructed reconstruct(HeaderRow header, List<MemberRow> members) {
        if (members.size() != header.scopeCount()) {
            throw corrupt("aggregate member cardinality differs from its header");
        }
        List<ScopeRevision> revisions = new ArrayList<>(members.size());
        List<ScopeOrigin> origins = new ArrayList<>(members.size());
        for (int index = 0; index < members.size(); index++) {
            MemberRow member = members.get(index);
            if (!header.id().equals(member.aggregateId())
                    || member.ordinal() != index + 1
                    || (index > 0 && members.get(index - 1).context().ordinal()
                            >= member.context().ordinal())
                    || member.locale() != header.locale()
                    || member.audience() != header.audience()) {
                throw corrupt("aggregate member differs from its header");
            }
            try {
                revisions.add(new ScopeRevision(
                        member.context(),
                        member.requiredSetRevision()));
                origins.add(new ScopeOrigin(
                        member.context(),
                        member.requiredSetId(),
                        member.publicationId()));
            } catch (IllegalArgumentException | NullPointerException failure) {
                throw corrupt("aggregate member is invalid", failure);
            }
        }
        try {
            return new Reconstructed(
                    new LegalRequiredSetAggregateProjection(
                            header.revisionScheme(),
                            header.locale(),
                            header.audience(),
                            revisions),
                    new LegalRequiredSetAggregateProvenance(
                            header.profile(),
                            header.locale(),
                            header.audience(),
                            origins));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw corrupt("aggregate vector is invalid", failure);
        }
    }

    private void requireExactReconstruction(
            ExpectedAggregate expected,
            Reconstructed reconstructed) {
        String semantic = semanticCalculator.calculate(reconstructed.projection());
        String provenance = provenanceCalculator.calculate(reconstructed.provenance());
        if (!reconstructed.projection().equals(expected.projection())
                || !reconstructed.provenance().equals(expected.provenance())
                || !semantic.equals(expected.token())
                || !provenance.equals(expected.fingerprint())) {
            throw corrupt("aggregate replay reconstruction differs from the expected vector");
        }
    }

    private static IllegalStateException corrupt(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException corrupt(String message, RuntimeException cause) {
        return new IllegalStateException(message, cause);
    }

    record ExpectedAggregate(
            LegalRequiredSetAggregateProjection projection,
            String token,
            LegalRequiredSetAggregateProvenance provenance,
            String fingerprint
    ) {
        ExpectedAggregate {
            projection = Objects.requireNonNull(projection, "projection");
            provenance = Objects.requireNonNull(provenance, "provenance");
            token = requireDigest(token, "token");
            fingerprint = requireDigest(fingerprint, "fingerprint");
            List<ContextoLegal> semanticContexts = projection.scopes().stream()
                    .map(ScopeRevision::context)
                    .toList();
            List<ContextoLegal> physicalContexts = provenance.scopes().stream()
                    .map(ScopeOrigin::context)
                    .toList();
            if (projection.locale() != provenance.locale()
                    || projection.audience() != provenance.audience()
                    || !semanticContexts.equals(physicalContexts)) {
                throw new IllegalArgumentException(
                        "expected aggregate projection and provenance are inconsistent");
            }
        }

        String requiredSetRevision() {
            return token;
        }

        String provenanceFingerprint() {
            return fingerprint;
        }
    }

    private static String requireDigest(String value, String field) {
        String required = Objects.requireNonNull(value, field);
        if (required.length() != 71 || !required.startsWith("sha256:")) {
            throw new IllegalArgumentException(field + " no respeta sha256:<64-hex lowercase>");
        }
        for (int index = 7; index < required.length(); index++) {
            char current = required.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f'))) {
                throw new IllegalArgumentException(
                        field + " no respeta sha256:<64-hex lowercase>");
            }
        }
        return required;
    }

    record VerifiedAggregate(UUID id, java.time.Instant createdAt) {
        VerifiedAggregate {
            id = Objects.requireNonNull(id, "id");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }

        UUID aggregateId() {
            return id;
        }
    }

    private record HeaderRow(
            UUID id,
            PerfilAgregadoLegal profile,
            LocaleLegal locale,
            AudienciaLegal audience,
            EsquemaRevisionLegal revisionScheme,
            String requiredSetRevision,
            String provenanceFingerprint,
            int scopeCount,
            OffsetDateTime createdAt) {
    }

    private record MemberRow(
            UUID aggregateId,
            int ordinal,
            ContextoLegal context,
            UUID requiredSetId,
            UUID publicationId,
            LocaleLegal locale,
            AudienciaLegal audience,
            String requiredSetRevision) {
    }

    private record Reconstructed(
            LegalRequiredSetAggregateProjection projection,
            LegalRequiredSetAggregateProvenance provenance) {
    }
}
