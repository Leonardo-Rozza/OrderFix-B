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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalRequiredSetAggregateReplayVerifierTest {

    private static final String EXPECTED_HEADER_SQL = """
            SELECT id, perfil, locale, audiencia, revision_scheme,
                   required_set_revision, provenance_fingerprint, scope_count, creado_en
              FROM legal_requisito_agregados
             WHERE id = ?
             LIMIT 2
            """;
    private static final String EXPECTED_MEMBERS_SQL = """
            SELECT agregado_id, scope_ordinal, contexto, conjunto_id, publicacion_id,
                   locale, audiencia, required_set_revision
              FROM legal_requisito_agregado_scopes
             WHERE agregado_id = ?
             ORDER BY scope_ordinal, contexto
             LIMIT 9
            """;

    private static final UUID AGGREGATE_ID = uuid(1);
    private static final UUID REQUIRED_SET_A = uuid(11);
    private static final UUID REQUIRED_SET_B = uuid(12);
    private static final UUID PUBLICATION_A = uuid(21);
    private static final UUID PUBLICATION_B = uuid(22);
    private static final Instant TRANSACTION_AT = Instant.parse("2026-09-01T12:00:00.123456Z");
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-01T12:00:01.123456Z");
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T12:00:02.123456Z");
    private static final String REVISION_A = digest('1');
    private static final String REVISION_B = digest('2');

    private JdbcTemplate jdbc;
    private LegalRequiredSetAggregateRevisionCalculator semanticCalculator;
    private LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator;
    private LegalRequiredSetAggregateReplayVerifier verifier;
    private LegalRequiredSetAggregateReplayVerifier.ExpectedAggregate expected;
    private HeaderData header;
    private List<MemberData> members;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        semanticCalculator = new LegalRequiredSetAggregateRevisionCalculator();
        provenanceCalculator = new LegalRequiredSetAggregateProvenanceCalculator();
        verifier = new LegalRequiredSetAggregateReplayVerifier(
                jdbc,
                semanticCalculator,
                provenanceCalculator);
        LegalRequiredSetAggregateProjection projection = new LegalRequiredSetAggregateProjection(
                EsquemaRevisionLegal.AGGREGATE_V1,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER,
                List.of(
                        new ScopeRevision(ContextoLegal.USO_CONTINUADO, REVISION_A),
                        new ScopeRevision(ContextoLegal.ATESTACION_FOTOS, REVISION_B)));
        LegalRequiredSetAggregateProvenance provenance = new LegalRequiredSetAggregateProvenance(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER,
                List.of(
                        new ScopeOrigin(ContextoLegal.USO_CONTINUADO, REQUIRED_SET_A, PUBLICATION_A),
                        new ScopeOrigin(ContextoLegal.ATESTACION_FOTOS, REQUIRED_SET_B, PUBLICATION_B)));
        expected = new LegalRequiredSetAggregateReplayVerifier.ExpectedAggregate(
                projection,
                semanticCalculator.calculate(projection),
                provenance,
                provenanceCalculator.calculate(provenance));
        header = exactHeader(CREATED_AT);
        members = exactMembers();
    }

    @Test
    void exactCreatedReplayReconstructsBothHashesAndReturnsAuthoritativeIdentity() throws Exception {
        assertThat(LegalRequiredSetAggregateReplayVerifier.HEADER_SQL)
                .isEqualTo(EXPECTED_HEADER_SQL);
        assertThat(LegalRequiredSetAggregateReplayVerifier.MEMBERS_SQL)
                .isEqualTo(EXPECTED_MEMBERS_SQL);
        stubRows(List.of(header), members);

        LegalRequiredSetAggregateReplayVerifier.VerifiedAggregate verified = verifier.verify(
                AGGREGATE_ID,
                LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                expected,
                boundary());

        assertThat(verified.id()).isEqualTo(AGGREGATE_ID);
        assertThat(verified.createdAt()).isEqualTo(CREATED_AT);
        assertThat(verifier.usesJdbc(jdbc)).isTrue();
        assertThat(verifier.usesJdbc(new JdbcTemplate())).isFalse();
    }

    @Test
    void permutedDriverRowsAreNormalizedByOrdinalBeforeReplay() throws Exception {
        ArrayList<MemberData> reversed = new ArrayList<>(members);
        Collections.reverse(reversed);
        stubRows(List.of(header), reversed);

        assertThat(verifier.verify(
                AGGREGATE_ID,
                LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                expected,
                boundary()).id()).isEqualTo(AGGREGATE_ID);
    }

    @Test
    void reusedAggregateMayPredateTheCurrentBoundaryButCreatedMayNot() throws Exception {
        HeaderData historical = exactHeader(TRANSACTION_AT.minusSeconds(30));
        stubRows(List.of(historical), members);

        assertThat(verifier.verify(
                AGGREGATE_ID,
                LegalRequiredSetAggregateReceipt.Outcome.REUSED,
                expected,
                boundary()).createdAt()).isEqualTo(TRANSACTION_AT.minusSeconds(30));
        assertThatThrownBy(() -> verifier.verify(
                AGGREGATE_ID,
                LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                expected,
                boundary())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingDuplicateOrDriftingHeaders() throws Exception {
        stubRows(List.of(), members);
        assertCorrupt();

        stubRows(List.of(header, header), members);
        assertCorrupt();

        stubRows(List.of(header.withToken(digest('9'))), members);
        assertCorrupt();

        stubRows(List.of(header.withProfile(PerfilAgregadoLegal.REGISTRATION)), members);
        assertCorrupt();
        stubRows(List.of(header.withAudience(AudienciaLegal.ADMIN_TITULAR)), members);
        assertCorrupt();
        stubRows(List.of(header.withScheme(EsquemaRevisionLegal.SCOPE_V1)), members);
        assertCorrupt();
        stubRows(List.of(header.withFingerprint(digest('8'))), members);
        assertCorrupt();

        stubRows(List.of(header.withCount(3)), members);
        assertCorrupt();

        stubRows(List.of(header.withCreatedAt(Instant.parse("2026-09-01T12:00:02.123456789Z"))), members);
        assertCorrupt();
    }

    @Test
    void rejectsMissingNinthDuplicateOrCrossIdentityMembersBeforeConfirmation() throws Exception {
        stubRows(List.of(header), List.of(members.getFirst()));
        assertCorrupt();

        stubRows(List.of(header), List.of(members.getFirst(), members.getFirst()));
        assertCorrupt();

        List<MemberData> nine = new ArrayList<>(Collections.nCopies(9, members.getFirst()));
        stubRows(List.of(header), nine);
        assertCorrupt();

        stubRows(List.of(header), List.of(
                members.getFirst().withAggregateId(uuid(99)),
                members.get(1)));
        assertCorrupt();

        stubRows(List.of(header), List.of(
                members.getFirst().withRevision(digest('8')),
                members.get(1)));
        assertCorrupt();
        stubRows(List.of(header), List.of(
                members.getFirst().withOrdinal(2),
                members.get(1).withOrdinal(1)));
        assertCorrupt();
        stubRows(List.of(header), List.of(
                members.getFirst().withContext(ContextoLegal.REGISTRO),
                members.get(1)));
        assertCorrupt();
        stubRows(List.of(header), List.of(
                members.getFirst().withRequiredSetId(null),
                members.get(1)));
        assertCorrupt();
        stubRows(List.of(header), List.of(
                members.getFirst().withPublicationId(null),
                members.get(1)));
        assertCorrupt();
        stubRows(List.of(header), List.of(
                members.getFirst().withAudience(AudienciaLegal.ADMIN_TITULAR),
                members.get(1)));
        assertCorrupt();
    }

    @Test
    void rejectsSemanticallyRecomputedOrPhysicallyRecomputedHashMismatch() throws Exception {
        LegalRequiredSetAggregateReplayVerifier.ExpectedAggregate wrongToken =
                new LegalRequiredSetAggregateReplayVerifier.ExpectedAggregate(
                        expected.projection(),
                        digest('a'),
                        expected.provenance(),
                        expected.fingerprint());
        stubRows(List.of(header.withToken(digest('a'))), members);
        assertThatThrownBy(() -> verifier.verify(
                AGGREGATE_ID,
                LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                wrongToken,
                boundary())).isInstanceOf(IllegalStateException.class);

        LegalRequiredSetAggregateReplayVerifier.ExpectedAggregate wrongFingerprint =
                new LegalRequiredSetAggregateReplayVerifier.ExpectedAggregate(
                        expected.projection(),
                        expected.token(),
                        expected.provenance(),
                        digest('b'));
        stubRows(List.of(header.withFingerprint(digest('b'))), members);
        assertThatThrownBy(() -> verifier.verify(
                AGGREGATE_ID,
                LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                wrongFingerprint,
                boundary())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propagatesJdbcFailureUnchanged() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("connection lost");
        when(jdbc.query(
                eq(EXPECTED_HEADER_SQL),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                eq(AGGREGATE_ID))).thenThrow(failure);

        assertThatThrownBy(() -> verifier.verify(
                AGGREGATE_ID,
                LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                expected,
                boundary())).isSameAs(failure);
    }

    @Test
    void mapsInvalidOrNullJdbcValuesToCorruption() throws Exception {
        ResultSet invalidHeader = headerResultSet(header);
        when(invalidHeader.getString("perfil")).thenReturn("UNKNOWN");
        stubRawHeader(invalidHeader);
        assertCorrupt();

        ResultSet nullLocaleHeader = headerResultSet(header);
        when(nullLocaleHeader.getString("locale")).thenReturn(null);
        stubRawHeader(nullLocaleHeader);
        assertCorrupt();

        ResultSet nullMember = memberResultSet(members.getFirst());
        when(nullMember.getString("contexto")).thenReturn(null);
        stubRows(List.of(header), members);
        stubRawMembers(List.of(nullMember, memberResultSet(members.get(1))));
        assertCorrupt();

        when(jdbc.query(eq(EXPECTED_HEADER_SQL), any(RowMapper.class), eq(AGGREGATE_ID)))
                .thenReturn(null);
        assertCorrupt();
    }

    @Test
    void expectedAggregateRejectsCrossedVectorsAndMalformedDigests() {
        LegalRequiredSetAggregateProvenance crossed = new LegalRequiredSetAggregateProvenance(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.ADMIN_TITULAR,
                expected.provenance().scopes());
        assertThatThrownBy(() -> new LegalRequiredSetAggregateReplayVerifier.ExpectedAggregate(
                expected.projection(), expected.token(), crossed, expected.fingerprint()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalRequiredSetAggregateReplayVerifier.ExpectedAggregate(
                expected.projection(), "invalid", expected.provenance(), expected.fingerprint()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receiptValidatesDigestsIdentityAndPostgresPrecision() {
        LegalRequiredSetAggregateReceipt receipt = new LegalRequiredSetAggregateReceipt(
                LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                AGGREGATE_ID,
                expected.token(),
                expected.fingerprint(),
                expected.provenance(),
                CREATED_AT);
        assertThat(receipt.aggregateId()).isEqualTo(AGGREGATE_ID);

        assertThatThrownBy(() -> new LegalRequiredSetAggregateReceipt(
                LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                AGGREGATE_ID,
                "sha256:" + "A".repeat(64),
                expected.fingerprint(),
                expected.provenance(),
                CREATED_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalRequiredSetAggregateReceipt(
                LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                AGGREGATE_ID,
                expected.token(),
                expected.fingerprint(),
                expected.provenance(),
                Instant.parse("2026-09-01T12:00:02.123456789Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private void stubRows(List<HeaderData> headerRows, List<MemberData> memberRows) throws Exception {
        List<ResultSet> mappedHeaders = headerRows.stream().map(this::headerResultSet).toList();
        List<ResultSet> mappedMembers = memberRows.stream().map(this::memberResultSet).toList();
        when(jdbc.query(
                eq(EXPECTED_HEADER_SQL),
                any(RowMapper.class),
                eq(AGGREGATE_ID))).thenAnswer(invocation -> mapRows(
                        invocation.getArgument(1),
                        mappedHeaders));
        when(jdbc.query(
                eq(EXPECTED_MEMBERS_SQL),
                any(RowMapper.class),
                eq(AGGREGATE_ID))).thenAnswer(invocation -> mapRows(
                        invocation.getArgument(1),
                        mappedMembers));
    }

    @SuppressWarnings("unchecked")
    private void stubRawHeader(ResultSet row) throws Exception {
        when(jdbc.query(eq(EXPECTED_HEADER_SQL), any(RowMapper.class), eq(AGGREGATE_ID)))
                .thenAnswer(invocation -> mapRows(invocation.getArgument(1), List.of(row)));
    }

    @SuppressWarnings("unchecked")
    private void stubRawMembers(List<ResultSet> rows) throws Exception {
        when(jdbc.query(eq(EXPECTED_MEMBERS_SQL), any(RowMapper.class), eq(AGGREGATE_ID)))
                .thenAnswer(invocation -> mapRows(invocation.getArgument(1), rows));
    }

    private static <T> List<T> mapRows(RowMapper<T> mapper, List<ResultSet> rows) throws Exception {
        List<T> mapped = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            mapped.add(mapper.mapRow(rows.get(index), index));
        }
        return mapped;
    }

    private ResultSet headerResultSet(HeaderData data) {
        ResultSet row = mock(ResultSet.class);
        try {
            when(row.getObject("id", UUID.class)).thenReturn(data.id());
            when(row.getString("perfil")).thenReturn(data.profile().name());
            when(row.getString("locale")).thenReturn(data.locale().getCodigo());
            when(row.getString("audiencia")).thenReturn(data.audience().name());
            when(row.getString("revision_scheme")).thenReturn(data.scheme().name());
            when(row.getString("required_set_revision")).thenReturn(data.token());
            when(row.getString("provenance_fingerprint")).thenReturn(data.fingerprint());
            when(row.getInt("scope_count")).thenReturn(data.count());
            when(row.getObject("creado_en", OffsetDateTime.class)).thenReturn(
                    OffsetDateTime.ofInstant(data.createdAt(), ZoneOffset.UTC));
            return row;
        } catch (java.sql.SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    private ResultSet memberResultSet(MemberData data) {
        ResultSet row = mock(ResultSet.class);
        try {
            when(row.getObject("agregado_id", UUID.class)).thenReturn(data.aggregateId());
            when(row.getInt("scope_ordinal")).thenReturn(data.ordinal());
            when(row.getString("contexto")).thenReturn(data.context().name());
            when(row.getObject("conjunto_id", UUID.class)).thenReturn(data.requiredSetId());
            when(row.getObject("publicacion_id", UUID.class)).thenReturn(data.publicationId());
            when(row.getString("locale")).thenReturn(data.locale().getCodigo());
            when(row.getString("audiencia")).thenReturn(data.audience().name());
            when(row.getString("required_set_revision")).thenReturn(data.revision());
            return row;
        } catch (java.sql.SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    private void assertCorrupt() {
        assertThatThrownBy(() -> verifier.verify(
                AGGREGATE_ID,
                LegalRequiredSetAggregateReceipt.Outcome.CREATED,
                expected,
                boundary())).isInstanceOf(IllegalStateException.class);
    }

    private HeaderData exactHeader(Instant createdAt) {
        return new HeaderData(
                AGGREGATE_ID,
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER,
                EsquemaRevisionLegal.AGGREGATE_V1,
                expected.token(),
                expected.fingerprint(),
                2,
                createdAt);
    }

    private static List<MemberData> exactMembers() {
        return List.of(
                new MemberData(
                        AGGREGATE_ID, 1, ContextoLegal.USO_CONTINUADO,
                        REQUIRED_SET_A, PUBLICATION_A, LocaleLegal.ES_AR,
                        AudienciaLegal.USER, REVISION_A),
                new MemberData(
                        AGGREGATE_ID, 2, ContextoLegal.ATESTACION_FOTOS,
                        REQUIRED_SET_B, PUBLICATION_B, LocaleLegal.ES_AR,
                        AudienciaLegal.USER, REVISION_B));
    }

    private static LegalEditorialTimeBoundary boundary() {
        return new LegalEditorialTimeBoundary(TRANSACTION_AT, OBSERVED_AT);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private record HeaderData(
            UUID id,
            PerfilAgregadoLegal profile,
            LocaleLegal locale,
            AudienciaLegal audience,
            EsquemaRevisionLegal scheme,
            String token,
            String fingerprint,
            int count,
            Instant createdAt) {
        HeaderData withToken(String value) {
            return new HeaderData(id, profile, locale, audience, scheme, value, fingerprint, count,
                    createdAt);
        }

        HeaderData withFingerprint(String value) {
            return new HeaderData(id, profile, locale, audience, scheme, token, value, count,
                    createdAt);
        }

        HeaderData withProfile(PerfilAgregadoLegal value) {
            return new HeaderData(id, value, locale, audience, scheme, token, fingerprint, count,
                    createdAt);
        }

        HeaderData withLocale(LocaleLegal value) {
            return new HeaderData(id, profile, value, audience, scheme, token, fingerprint, count,
                    createdAt);
        }

        HeaderData withAudience(AudienciaLegal value) {
            return new HeaderData(id, profile, locale, value, scheme, token, fingerprint, count,
                    createdAt);
        }

        HeaderData withScheme(EsquemaRevisionLegal value) {
            return new HeaderData(id, profile, locale, audience, value, token, fingerprint, count,
                    createdAt);
        }

        HeaderData withCount(int value) {
            return new HeaderData(id, profile, locale, audience, scheme, token, fingerprint, value,
                    createdAt);
        }

        HeaderData withCreatedAt(Instant value) {
            return new HeaderData(id, profile, locale, audience, scheme, token, fingerprint, count,
                    value);
        }
    }

    private record MemberData(
            UUID aggregateId,
            int ordinal,
            ContextoLegal context,
            UUID requiredSetId,
            UUID publicationId,
            LocaleLegal locale,
            AudienciaLegal audience,
            String revision) {
        MemberData withAggregateId(UUID value) {
            return new MemberData(value, ordinal, context, requiredSetId, publicationId, locale,
                    audience, revision);
        }

        MemberData withRevision(String value) {
            return new MemberData(aggregateId, ordinal, context, requiredSetId, publicationId,
                    locale, audience, value);
        }

        MemberData withOrdinal(int value) {
            return new MemberData(aggregateId, value, context, requiredSetId, publicationId, locale,
                    audience, revision);
        }

        MemberData withContext(ContextoLegal value) {
            return new MemberData(aggregateId, ordinal, value, requiredSetId, publicationId, locale,
                    audience, revision);
        }

        MemberData withRequiredSetId(UUID value) {
            return new MemberData(aggregateId, ordinal, context, value, publicationId, locale,
                    audience, revision);
        }

        MemberData withPublicationId(UUID value) {
            return new MemberData(aggregateId, ordinal, context, requiredSetId, value, locale,
                    audience, revision);
        }

        MemberData withLocale(LocaleLegal value) {
            return new MemberData(aggregateId, ordinal, context, requiredSetId, publicationId, value,
                    audience, revision);
        }

        MemberData withAudience(AudienciaLegal value) {
            return new MemberData(aggregateId, ordinal, context, requiredSetId, publicationId,
                    locale, value, revision);
        }
    }
}
