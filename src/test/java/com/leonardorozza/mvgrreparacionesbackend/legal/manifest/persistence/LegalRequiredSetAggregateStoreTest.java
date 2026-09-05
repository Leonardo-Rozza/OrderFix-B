package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
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
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LegalRequiredSetAggregateStoreTest {

    private static final UUID CANDIDATE_ID = uuid(1);
    private static final UUID REUSED_ID = uuid(2);
    private static final UUID REQUIRED_SET_A = uuid(11);
    private static final UUID REQUIRED_SET_B = uuid(12);
    private static final UUID PUBLICATION_A = uuid(21);
    private static final UUID PUBLICATION_B = uuid(22);
    private static final Instant TRANSACTION_AT =
            Instant.parse("2026-09-01T12:00:00.123456Z");
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-09-01T12:00:01.123456Z");
    private static final Instant CREATED_AT =
            Instant.parse("2026-09-01T12:00:02.123456Z");
    private static final Instant HISTORICAL_CREATED_AT =
            Instant.parse("2026-08-31T10:00:00.123456Z");
    private static final String REVISION_A = digest('1');
    private static final String REVISION_B = digest('2');

    private static final String POINTER_SQL_ONE = """
            WITH requested(contexto, scope_ordinal) AS (
                VALUES (?, ?)
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
            """;
    private static final String POINTER_SQL_TWO = """
            WITH requested(contexto, scope_ordinal) AS (
                VALUES (?, ?), (?, ?)
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
            """;
    private static final String POINTER_SQL_EIGHT = """
            WITH requested(contexto, scope_ordinal) AS (
                VALUES (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)
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
            """;
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

    private static final Object[] TWO_SCOPE_POINTER_ARGUMENTS = {
            "USO_CONTINUADO", 1,
            "ATESTACION_FOTOS", 2,
            "es-AR", "USER"
    };

    @Test
    void createdUsesExactSqlAndParametersNormalizesRowsAndReplaysBeforeReturning() {
        Harness harness = harness(CANDIDATE_ID);
        LegalApplicableScopeSet scopes = authenticatedScopes(
                ContextoLegal.ATESTACION_FOTOS,
                ContextoLegal.USO_CONTINUADO);
        PointerData usage = pointer(
                1,
                ContextoLegal.USO_CONTINUADO,
                REQUIRED_SET_A,
                PUBLICATION_A,
                REVISION_A);
        PointerData photos = pointer(
                2,
                ContextoLegal.ATESTACION_FOTOS,
                REQUIRED_SET_B,
                PUBLICATION_B,
                REVISION_B);
        Identity expected = identity(scopes, List.of(usage, photos), harness);
        stubPointerRows(
                harness.jdbc(),
                POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS,
                List.of(photos, usage));
        stubHeaderRows(
                harness.jdbc(),
                INSERT_HEADER_SQL,
                insertArguments(CANDIDATE_ID, expected),
                List.of(header(CANDIDATE_ID, expected, CREATED_AT)));
        when(harness.jdbc().batchUpdate(eq(INSERT_SCOPES_SQL), anyList()))
                .thenReturn(new int[]{1, Statement.SUCCESS_NO_INFO});
        stubReplay(harness, CANDIDATE_ID, Outcome.CREATED, CREATED_AT);

        LegalRequiredSetAggregateReceipt receipt = harness.store().materialize(scopes, boundary());

        ArgumentCaptor<List<Object[]>> batch = batchCaptor();
        ArgumentCaptor<ExpectedAggregate> replayExpected =
                ArgumentCaptor.forClass(ExpectedAggregate.class);
        InOrder order = inOrder(harness.jdbc(), harness.replayVerifier());
        order.verify(harness.jdbc()).query(
                eq(POINTER_SQL_TWO),
                any(RowMapper.class),
                aryEq(TWO_SCOPE_POINTER_ARGUMENTS));
        order.verify(harness.jdbc()).query(
                eq(SELECT_HEADER_BY_IDENTITY_SQL),
                any(RowMapper.class),
                aryEq(identityArguments(expected)));
        order.verify(harness.jdbc()).query(
                eq(INSERT_HEADER_SQL),
                any(RowMapper.class),
                aryEq(insertArguments(CANDIDATE_ID, expected)));
        order.verify(harness.jdbc()).batchUpdate(eq(INSERT_SCOPES_SQL), batch.capture());
        order.verify(harness.replayVerifier()).verify(
                eq(CANDIDATE_ID),
                eq(Outcome.CREATED),
                replayExpected.capture(),
                eq(boundary()));

        assertThat(batch.getValue()).hasSize(2);
        assertThat(batch.getValue().get(0)).containsExactly(
                CANDIDATE_ID, 1, "USO_CONTINUADO", REQUIRED_SET_A, PUBLICATION_A,
                "es-AR", "USER", REVISION_A);
        assertThat(batch.getValue().get(1)).containsExactly(
                CANDIDATE_ID, 2, "ATESTACION_FOTOS", REQUIRED_SET_B, PUBLICATION_B,
                "es-AR", "USER", REVISION_B);
        assertThat(replayExpected.getValue().projection()).isEqualTo(expected.projection());
        assertThat(replayExpected.getValue().token()).isEqualTo(expected.token());
        assertThat(replayExpected.getValue().provenance()).isEqualTo(expected.provenance());
        assertThat(replayExpected.getValue().fingerprint()).isEqualTo(expected.fingerprint());
        assertThat(receipt.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(receipt.aggregateId()).isEqualTo(CANDIDATE_ID);
        assertThat(receipt.requiredSetRevision()).isEqualTo(expected.token());
        assertThat(receipt.provenanceFingerprint()).isEqualTo(expected.fingerprint());
        assertThat(receipt.provenance()).isEqualTo(expected.provenance());
        assertThat(receipt.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void malformedPointerSnapshotsFailBeforeAnyDmlOrReplay() {
        LegalApplicableScopeSet twoScopes = authenticatedScopes(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.ATESTACION_FOTOS);
        PointerData usage = pointer(
                1,
                ContextoLegal.USO_CONTINUADO,
                REQUIRED_SET_A,
                PUBLICATION_A,
                REVISION_A);
        PointerData photos = pointer(
                2,
                ContextoLegal.ATESTACION_FOTOS,
                REQUIRED_SET_B,
                PUBLICATION_B,
                REVISION_B);

        assertInvalidPointers("missing", twoScopes, POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS, List.of(usage));
        assertInvalidPointers("duplicate ordinal", twoScopes, POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS, List.of(usage, photos.withOrdinal(1)));
        assertInvalidPointers("duplicate context", twoScopes, POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS,
                List.of(usage, photos.withContext(ContextoLegal.USO_CONTINUADO.name())));
        assertInvalidPointers("mixed locale", twoScopes, POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS, List.of(usage, photos.withLocale("es-UY")));
        assertInvalidPointers("mixed audience", twoScopes, POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS,
                List.of(usage, photos.withAudience("ADMIN_TITULAR")));
        assertInvalidPointers("mixed context", twoScopes, POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS,
                List.of(usage, photos.withContext(ContextoLegal.CIERRE_CUENTA.name())));
        assertInvalidPointers("null required-set id", twoScopes, POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS, List.of(usage.withRequiredSetId(null), photos));
        assertInvalidPointers("null publication id", twoScopes, POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS, List.of(usage, photos.withPublicationId(null)));
        assertInvalidPointers("invalid digest", twoScopes, POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS,
                List.of(usage, photos.withRevision("sha256:" + "A".repeat(64))));

        LegalApplicableScopeSet allScopes = authenticatedScopes(ContextoLegal.values());
        List<PointerData> ninth = new ArrayList<>();
        for (int index = 0; index < ContextoLegal.values().length; index++) {
            ninth.add(pointer(
                    index + 1,
                    ContextoLegal.values()[index],
                    uuid(100 + index),
                    uuid(200 + index),
                    digest((char) ('1' + index))));
        }
        ninth.add(ninth.getFirst().withOrdinal(9));
        assertInvalidPointers(
                "ninth sentinel",
                allScopes,
                POINTER_SQL_EIGHT,
                pointerArguments(allScopes),
                ninth);
    }

    @Test
    void aPresentDeliberatelyEmptyV27SnapshotIsStillOneValidAggregateScope() {
        Harness harness = harness(CANDIDATE_ID);
        LegalApplicableScopeSet scopes = new LegalApplicableScopeResolver().resolve(
                PerfilAgregadoLegal.REGISTRATION,
                LocaleLegal.ES_AR,
                AudienciaLegal.ADMIN_TITULAR);
        PointerData emptyScope = new PointerData(
                1,
                "es-AR",
                ContextoLegal.REGISTRO.name(),
                AudienciaLegal.ADMIN_TITULAR.name(),
                REQUIRED_SET_A,
                PUBLICATION_A,
                REVISION_A);
        Identity expected = identity(scopes, List.of(emptyScope), harness);
        Object[] pointerArguments = {"REGISTRO", 1, "es-AR", "ADMIN_TITULAR"};
        stubPointerRows(
                harness.jdbc(),
                POINTER_SQL_ONE,
                pointerArguments,
                List.of(emptyScope));
        stubHeaderRows(
                harness.jdbc(),
                INSERT_HEADER_SQL,
                insertArguments(CANDIDATE_ID, expected),
                List.of(header(CANDIDATE_ID, expected, CREATED_AT)));
        when(harness.jdbc().batchUpdate(eq(INSERT_SCOPES_SQL), anyList()))
                .thenReturn(new int[]{1});
        stubReplay(harness, CANDIDATE_ID, Outcome.CREATED, CREATED_AT);

        LegalRequiredSetAggregateReceipt receipt = harness.store().materialize(scopes, boundary());

        ArgumentCaptor<List<Object[]>> batch = batchCaptor();
        verify(harness.jdbc()).query(
                eq(POINTER_SQL_ONE),
                any(RowMapper.class),
                aryEq(pointerArguments));
        verify(harness.jdbc()).query(
                eq(SELECT_HEADER_BY_IDENTITY_SQL),
                any(RowMapper.class),
                aryEq(identityArguments(expected)));
        verify(harness.jdbc()).query(
                eq(INSERT_HEADER_SQL),
                any(RowMapper.class),
                aryEq(insertArguments(CANDIDATE_ID, expected)));
        verify(harness.jdbc()).batchUpdate(eq(INSERT_SCOPES_SQL), batch.capture());
        verify(harness.replayVerifier()).verify(
                eq(CANDIDATE_ID),
                eq(Outcome.CREATED),
                any(ExpectedAggregate.class),
                eq(boundary()));
        verifyNoMoreInteractions(harness.jdbc(), harness.replayVerifier());
        assertThat(batch.getValue()).hasSize(1);
        assertThat(batch.getValue().getFirst()).containsExactly(
                CANDIDATE_ID, 1, "REGISTRO", REQUIRED_SET_A, PUBLICATION_A,
                "es-AR", "ADMIN_TITULAR", REVISION_A);
        assertThat(receipt.requiredSetRevision()).isEqualTo(expected.token());
    }

    @Test
    void exactExistingIdentityReplaysWithoutGeneratingUuidOrExecutingDml() {
        Harness harness = harness(CANDIDATE_ID);
        LegalApplicableScopeSet scopes = authenticatedScopes(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.ATESTACION_FOTOS);
        List<PointerData> pointers = exactPointers(REQUIRED_SET_A, PUBLICATION_A,
                REQUIRED_SET_B, PUBLICATION_B);
        Identity expected = identity(scopes, pointers, harness);
        stubPointerRows(harness.jdbc(), POINTER_SQL_TWO, TWO_SCOPE_POINTER_ARGUMENTS, pointers);
        stubHeaderRows(
                harness.jdbc(),
                SELECT_HEADER_BY_IDENTITY_SQL,
                identityArguments(expected),
                List.of(header(REUSED_ID, expected, HISTORICAL_CREATED_AT)));
        stubReplay(harness, REUSED_ID, Outcome.REUSED, HISTORICAL_CREATED_AT);

        LegalRequiredSetAggregateReceipt receipt = harness.store().materialize(scopes, boundary());

        ArgumentCaptor<ExpectedAggregate> replayExpected =
                ArgumentCaptor.forClass(ExpectedAggregate.class);
        InOrder order = inOrder(harness.jdbc(), harness.replayVerifier());
        order.verify(harness.jdbc()).query(
                eq(POINTER_SQL_TWO),
                any(RowMapper.class),
                aryEq(TWO_SCOPE_POINTER_ARGUMENTS));
        order.verify(harness.jdbc()).query(
                eq(SELECT_HEADER_BY_IDENTITY_SQL),
                any(RowMapper.class),
                aryEq(identityArguments(expected)));
        order.verify(harness.replayVerifier()).verify(
                eq(REUSED_ID),
                eq(Outcome.REUSED),
                replayExpected.capture(),
                eq(boundary()));
        verifyNoMoreInteractions(harness.jdbc(), harness.replayVerifier());
        verifyNoInteractions(harness.aggregateIdGenerator());
        assertThat(replayExpected.getValue().projection()).isEqualTo(expected.projection());
        assertThat(replayExpected.getValue().provenance()).isEqualTo(expected.provenance());
        assertThat(replayExpected.getValue().token()).isEqualTo(expected.token());
        assertThat(replayExpected.getValue().fingerprint()).isEqualTo(expected.fingerprint());
        assertThat(receipt.outcome()).isEqualTo(Outcome.REUSED);
        assertThat(receipt.aggregateId()).isEqualTo(REUSED_ID);
        assertThat(receipt.requiredSetRevision()).isEqualTo(expected.token());
        assertThat(receipt.provenanceFingerprint()).isEqualTo(expected.fingerprint());
        assertThat(receipt.provenance()).isEqualTo(expected.provenance());
        assertThat(receipt.createdAt()).isEqualTo(HISTORICAL_CREATED_AT);
    }

    @Test
    void nullDuplicateOrCorruptInitialLookupFailsBeforeUuidDmlAndReplay() {
        for (int scenario = 0; scenario < 4; scenario++) {
            Harness harness = harness(CANDIDATE_ID);
            LegalApplicableScopeSet scopes = authenticatedScopes(
                    ContextoLegal.USO_CONTINUADO,
                    ContextoLegal.ATESTACION_FOTOS);
            List<PointerData> pointers = exactPointers(REQUIRED_SET_A, PUBLICATION_A,
                    REQUIRED_SET_B, PUBLICATION_B);
            Identity expected = identity(scopes, pointers, harness);
            HeaderData exact = header(REUSED_ID, expected, HISTORICAL_CREATED_AT);
            stubPointerRows(
                    harness.jdbc(), POINTER_SQL_TWO, TWO_SCOPE_POINTER_ARGUMENTS, pointers);
            if (scenario == 0) {
                when(harness.jdbc().query(
                        eq(SELECT_HEADER_BY_IDENTITY_SQL),
                        any(RowMapper.class),
                        aryEq(identityArguments(expected)))).thenReturn(null);
            } else {
                List<HeaderData> rows = switch (scenario) {
                    case 1 -> List.of(exact, exact);
                    case 2 -> List.of(exact.withFingerprint(digest('9')));
                    default -> List.of(header(null, expected, HISTORICAL_CREATED_AT));
                };
                stubHeaderRows(
                        harness.jdbc(), SELECT_HEADER_BY_IDENTITY_SQL,
                        identityArguments(expected), rows);
            }

            assertThatThrownBy(() -> harness.store().materialize(scopes, boundary()))
                    .as("invalid initial lookup scenario %s", scenario)
                    .isInstanceOf(IllegalStateException.class);

            verify(harness.jdbc()).query(
                    eq(POINTER_SQL_TWO),
                    any(RowMapper.class),
                    aryEq(TWO_SCOPE_POINTER_ARGUMENTS));
            verify(harness.jdbc()).query(
                    eq(SELECT_HEADER_BY_IDENTITY_SQL),
                    any(RowMapper.class),
                    aryEq(identityArguments(expected)));
            verifyNoMoreInteractions(harness.jdbc());
            verifyNoInteractions(harness.replayVerifier(), harness.aggregateIdGenerator());
        }
    }

    @Test
    void reusedReplayFailurePropagatesWithoutUuidOrDml() {
        Harness harness = harness(CANDIDATE_ID);
        LegalApplicableScopeSet scopes = authenticatedScopes(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.ATESTACION_FOTOS);
        List<PointerData> pointers = exactPointers(REQUIRED_SET_A, PUBLICATION_A,
                REQUIRED_SET_B, PUBLICATION_B);
        Identity expected = identity(scopes, pointers, harness);
        stubPointerRows(harness.jdbc(), POINTER_SQL_TWO, TWO_SCOPE_POINTER_ARGUMENTS, pointers);
        stubHeaderRows(
                harness.jdbc(),
                SELECT_HEADER_BY_IDENTITY_SQL,
                identityArguments(expected),
                List.of(header(REUSED_ID, expected, HISTORICAL_CREATED_AT)));
        IllegalStateException failure = new IllegalStateException("persisted aggregate drift");
        when(harness.replayVerifier().verify(
                eq(REUSED_ID),
                eq(Outcome.REUSED),
                any(ExpectedAggregate.class),
                eq(boundary()))).thenThrow(failure);

        assertThatThrownBy(() -> harness.store().materialize(scopes, boundary()))
                .isSameAs(failure);

        verify(harness.jdbc()).query(
                eq(POINTER_SQL_TWO),
                any(RowMapper.class),
                aryEq(TWO_SCOPE_POINTER_ARGUMENTS));
        verify(harness.jdbc()).query(
                eq(SELECT_HEADER_BY_IDENTITY_SQL),
                any(RowMapper.class),
                aryEq(identityArguments(expected)));
        verifyNoMoreInteractions(harness.jdbc());
        verifyNoInteractions(harness.aggregateIdGenerator());
    }

    @Test
    void exactConflictIsReusedWithoutBatchAndMayKeepHistoricalCreatedAt() {
        Harness harness = harness(CANDIDATE_ID);
        LegalApplicableScopeSet scopes = authenticatedScopes(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.ATESTACION_FOTOS);
        List<PointerData> pointers = exactPointers(REQUIRED_SET_A, PUBLICATION_A,
                REQUIRED_SET_B, PUBLICATION_B);
        Identity expected = identity(scopes, pointers, harness);
        stubPointerRows(harness.jdbc(), POINTER_SQL_TWO, TWO_SCOPE_POINTER_ARGUMENTS, pointers);
        stubHeaderRows(
                harness.jdbc(),
                INSERT_HEADER_SQL,
                insertArguments(CANDIDATE_ID, expected),
                List.of());
        stubIdentityRowsAfterInitialMiss(
                harness.jdbc(),
                expected,
                List.of(header(REUSED_ID, expected, HISTORICAL_CREATED_AT)));
        stubReplay(harness, REUSED_ID, Outcome.REUSED, HISTORICAL_CREATED_AT);

        LegalRequiredSetAggregateReceipt receipt = harness.store().materialize(scopes, boundary());

        InOrder order = inOrder(harness.jdbc(), harness.replayVerifier());
        order.verify(harness.jdbc()).query(
                eq(POINTER_SQL_TWO),
                any(RowMapper.class),
                aryEq(TWO_SCOPE_POINTER_ARGUMENTS));
        order.verify(harness.jdbc()).query(
                eq(SELECT_HEADER_BY_IDENTITY_SQL),
                any(RowMapper.class),
                aryEq(identityArguments(expected)));
        order.verify(harness.jdbc()).query(
                eq(INSERT_HEADER_SQL),
                any(RowMapper.class),
                aryEq(insertArguments(CANDIDATE_ID, expected)));
        order.verify(harness.jdbc()).query(
                eq(SELECT_HEADER_BY_IDENTITY_SQL),
                any(RowMapper.class),
                aryEq(identityArguments(expected)));
        order.verify(harness.replayVerifier()).verify(
                eq(REUSED_ID),
                eq(Outcome.REUSED),
                any(ExpectedAggregate.class),
                eq(boundary()));
        verify(harness.jdbc(), never()).batchUpdate(eq(INSERT_SCOPES_SQL), anyList());
        assertThat(receipt.outcome()).isEqualTo(Outcome.REUSED);
        assertThat(receipt.aggregateId()).isEqualTo(REUSED_ID);
        assertThat(receipt.createdAt()).isEqualTo(HISTORICAL_CREATED_AT);
    }

    @Test
    void aDifferentIdentityAfterConflictFailsClosed() {
        Harness harness = harness(CANDIDATE_ID);
        LegalApplicableScopeSet scopes = authenticatedScopes(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.ATESTACION_FOTOS);
        List<PointerData> pointers = exactPointers(REQUIRED_SET_A, PUBLICATION_A,
                REQUIRED_SET_B, PUBLICATION_B);
        Identity expected = identity(scopes, pointers, harness);
        stubPointerRows(harness.jdbc(), POINTER_SQL_TWO, TWO_SCOPE_POINTER_ARGUMENTS, pointers);
        stubHeaderRows(
                harness.jdbc(),
                INSERT_HEADER_SQL,
                insertArguments(CANDIDATE_ID, expected),
                List.of());
        HeaderData different = header(REUSED_ID, expected, HISTORICAL_CREATED_AT)
                .withFingerprint(digest('9'));
        stubIdentityRowsAfterInitialMiss(harness.jdbc(), expected, List.of(different));

        assertThatThrownBy(() -> harness.store().materialize(scopes, boundary()))
                .isInstanceOf(IllegalStateException.class);

        verify(harness.jdbc(), never()).batchUpdate(eq(INSERT_SCOPES_SQL), anyList());
        verifyNoInteractions(harness.replayVerifier());
    }

    @Test
    void invalidBatchCountsAndReplayFailuresPropagateWithoutReceipt() {
        List<int[]> invalidCounts = Arrays.asList(
                null,
                new int[0],
                new int[]{1},
                new int[]{0, 1},
                new int[]{Statement.EXECUTE_FAILED, 1});
        for (int[] counts : invalidCounts) {
            Harness harness = harness(CANDIDATE_ID);
            CreatedFixture fixture = stubCreatedUntilBatch(harness);
            when(harness.jdbc().batchUpdate(eq(INSERT_SCOPES_SQL), anyList()))
                    .thenReturn(counts);

            assertThatThrownBy(() -> harness.store().materialize(
                    fixture.scopes(),
                    boundary()))
                    .as("invalid batch counts %s", Arrays.toString(counts))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(harness.replayVerifier());
        }

        Harness replayHarness = harness(CANDIDATE_ID);
        CreatedFixture fixture = stubCreatedUntilBatch(replayHarness);
        when(replayHarness.jdbc().batchUpdate(eq(INSERT_SCOPES_SQL), anyList()))
                .thenReturn(new int[]{1, 1});
        IllegalStateException replayFailure = new IllegalStateException("aggregate replay drift");
        when(replayHarness.replayVerifier().verify(
                eq(CANDIDATE_ID),
                eq(Outcome.CREATED),
                any(ExpectedAggregate.class),
                eq(boundary()))).thenThrow(replayFailure);

        assertThatThrownBy(() -> replayHarness.store().materialize(
                fixture.scopes(),
                boundary())).isSameAs(replayFailure);
    }

    @Test
    void duplicateKey23505IsNeverReinterpretedAsReplay() {
        Harness harness = harness(CANDIDATE_ID);
        LegalApplicableScopeSet scopes = authenticatedScopes(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.ATESTACION_FOTOS);
        List<PointerData> pointers = exactPointers(REQUIRED_SET_A, PUBLICATION_A,
                REQUIRED_SET_B, PUBLICATION_B);
        Identity expected = identity(scopes, pointers, harness);
        stubPointerRows(harness.jdbc(), POINTER_SQL_TWO, TWO_SCOPE_POINTER_ARGUMENTS, pointers);
        DuplicateKeyException failure = new DuplicateKeyException(
                "uuid collision",
                new SQLException("duplicate", "23505"));
        when(harness.jdbc().query(
                eq(INSERT_HEADER_SQL),
                any(RowMapper.class),
                aryEq(insertArguments(CANDIDATE_ID, expected)))).thenThrow(failure);

        assertThatThrownBy(() -> harness.store().materialize(scopes, boundary()))
                .isSameAs(failure);

        verify(harness.jdbc()).query(
                eq(SELECT_HEADER_BY_IDENTITY_SQL),
                any(RowMapper.class),
                aryEq(identityArguments(expected)));
        verify(harness.jdbc(), never()).batchUpdate(eq(INSERT_SCOPES_SQL), anyList());
        verifyNoInteractions(harness.replayVerifier());
    }

    @Test
    void equalComponentDigestsWithNewOriginsKeepTokenButCreateNewPhysicalIdentity() {
        Harness first = harness(CANDIDATE_ID);
        Harness second = harness(uuid(3));
        LegalApplicableScopeSet scopes = authenticatedScopes(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.ATESTACION_FOTOS);
        List<PointerData> firstPointers = exactPointers(
                REQUIRED_SET_A, PUBLICATION_A, REQUIRED_SET_B, PUBLICATION_B);
        List<PointerData> secondPointers = exactPointers(
                uuid(31), uuid(41), uuid(32), uuid(42));

        LegalRequiredSetAggregateReceipt firstReceipt = materializeCreated(
                first,
                scopes,
                firstPointers,
                CANDIDATE_ID);
        LegalRequiredSetAggregateReceipt secondReceipt = materializeCreated(
                second,
                scopes,
                secondPointers,
                uuid(3));

        assertThat(secondReceipt.requiredSetRevision())
                .isEqualTo(firstReceipt.requiredSetRevision());
        assertThat(secondReceipt.provenanceFingerprint())
                .isNotEqualTo(firstReceipt.provenanceFingerprint());
        assertThat(secondReceipt.provenance()).isNotEqualTo(firstReceipt.provenance());
        assertThat(secondReceipt.aggregateId()).isNotEqualTo(firstReceipt.aggregateId());
        assertThat(firstReceipt.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(secondReceipt.outcome()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void anExcludedScopeNeverAppearsInSqlOrParameters() {
        Harness harness = harness(CANDIDATE_ID);
        LegalApplicableScopeSet scopes = authenticatedScopes(ContextoLegal.USO_CONTINUADO);
        PointerData usage = pointer(
                1,
                ContextoLegal.USO_CONTINUADO,
                REQUIRED_SET_A,
                PUBLICATION_A,
                REVISION_A);
        Identity expected = identity(scopes, List.of(usage), harness);
        Object[] arguments = {"USO_CONTINUADO", 1, "es-AR", "USER"};
        stubPointerRows(harness.jdbc(), POINTER_SQL_ONE, arguments, List.of(usage));
        stubHeaderRows(
                harness.jdbc(),
                INSERT_HEADER_SQL,
                insertArguments(CANDIDATE_ID, expected),
                List.of(header(CANDIDATE_ID, expected, CREATED_AT)));
        when(harness.jdbc().batchUpdate(eq(INSERT_SCOPES_SQL), anyList()))
                .thenReturn(new int[]{1});
        stubReplay(harness, CANDIDATE_ID, Outcome.CREATED, CREATED_AT);

        LegalRequiredSetAggregateReceipt receipt = harness.store().materialize(scopes, boundary());

        verify(harness.jdbc()).query(
                eq(POINTER_SQL_ONE),
                any(RowMapper.class),
                aryEq(arguments));
        assertThat(POINTER_SQL_ONE).doesNotContain(ContextoLegal.ATESTACION_FOTOS.name());
        assertThat(Arrays.asList(arguments)).doesNotContain(ContextoLegal.ATESTACION_FOTOS.name());
        assertThat(receipt.provenance().scopes())
                .extracting(ScopeOrigin::context)
                .containsExactly(ContextoLegal.USO_CONTINUADO);
    }

    private static void assertInvalidPointers(
            String label,
            LegalApplicableScopeSet scopes,
            String sql,
            Object[] arguments,
            List<PointerData> rows) {
        Harness harness = harness(CANDIDATE_ID);
        stubPointerRows(harness.jdbc(), sql, arguments, rows);

        assertThatThrownBy(() -> harness.store().materialize(scopes, boundary()))
                .as(label)
                .isInstanceOf(IllegalStateException.class);

        verify(harness.jdbc()).query(
                eq(sql),
                any(RowMapper.class),
                aryEq(arguments));
        verifyNoMoreInteractions(harness.jdbc());
        verifyNoInteractions(harness.replayVerifier());
    }

    private static CreatedFixture stubCreatedUntilBatch(Harness harness) {
        LegalApplicableScopeSet scopes = authenticatedScopes(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.ATESTACION_FOTOS);
        List<PointerData> pointers = exactPointers(REQUIRED_SET_A, PUBLICATION_A,
                REQUIRED_SET_B, PUBLICATION_B);
        Identity expected = identity(scopes, pointers, harness);
        stubPointerRows(harness.jdbc(), POINTER_SQL_TWO, TWO_SCOPE_POINTER_ARGUMENTS, pointers);
        stubHeaderRows(
                harness.jdbc(),
                INSERT_HEADER_SQL,
                insertArguments(CANDIDATE_ID, expected),
                List.of(header(CANDIDATE_ID, expected, CREATED_AT)));
        return new CreatedFixture(scopes, expected);
    }

    private static LegalRequiredSetAggregateReceipt materializeCreated(
            Harness harness,
            LegalApplicableScopeSet scopes,
            List<PointerData> pointers,
            UUID aggregateId) {
        Identity expected = identity(scopes, pointers, harness);
        stubPointerRows(
                harness.jdbc(),
                POINTER_SQL_TWO,
                TWO_SCOPE_POINTER_ARGUMENTS,
                pointers);
        stubHeaderRows(
                harness.jdbc(),
                INSERT_HEADER_SQL,
                insertArguments(aggregateId, expected),
                List.of(header(aggregateId, expected, CREATED_AT)));
        when(harness.jdbc().batchUpdate(eq(INSERT_SCOPES_SQL), anyList()))
                .thenReturn(new int[]{1, 1});
        stubReplay(harness, aggregateId, Outcome.CREATED, CREATED_AT);
        return harness.store().materialize(scopes, boundary());
    }

    private static void stubReplay(
            Harness harness,
            UUID aggregateId,
            Outcome outcome,
            Instant createdAt) {
        when(harness.replayVerifier().verify(
                eq(aggregateId),
                eq(outcome),
                any(ExpectedAggregate.class),
                eq(boundary()))).thenReturn(new VerifiedAggregate(aggregateId, createdAt));
    }

    private static void stubPointerRows(
            JdbcTemplate jdbc,
            String sql,
            Object[] arguments,
            List<PointerData> rows) {
        stubRows(
                jdbc,
                sql,
                arguments,
                rows.stream().map(LegalRequiredSetAggregateStoreTest::pointerResultSet).toList());
    }

    private static void stubHeaderRows(
            JdbcTemplate jdbc,
            String sql,
            Object[] arguments,
            List<HeaderData> rows) {
        stubRows(
                jdbc,
                sql,
                arguments,
                rows.stream().map(LegalRequiredSetAggregateStoreTest::headerResultSet).toList());
    }

    @SuppressWarnings("unchecked")
    private static void stubIdentityRowsAfterInitialMiss(
            JdbcTemplate jdbc,
            Identity expected,
            List<HeaderData> rows) {
        List<ResultSet> persisted = rows.stream()
                .map(LegalRequiredSetAggregateStoreTest::headerResultSet)
                .toList();
        when(jdbc.query(
                eq(SELECT_HEADER_BY_IDENTITY_SQL),
                any(RowMapper.class),
                aryEq(identityArguments(expected))))
                .thenReturn(List.of())
                .thenAnswer(invocation -> mapRows(invocation.getArgument(1), persisted));
    }

    @SuppressWarnings("unchecked")
    private static void stubRows(
            JdbcTemplate jdbc,
            String sql,
            Object[] arguments,
            List<ResultSet> rows) {
        when(jdbc.query(
                eq(sql),
                any(RowMapper.class),
                aryEq(arguments))).thenAnswer(invocation -> mapRows(
                        invocation.getArgument(1),
                        rows));
    }

    private static <T> List<T> mapRows(RowMapper<T> mapper, List<ResultSet> rows)
            throws Exception {
        List<T> mapped = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            mapped.add(mapper.mapRow(rows.get(index), index));
        }
        return mapped;
    }

    private static ResultSet pointerResultSet(PointerData data) {
        ResultSet row = mock(ResultSet.class);
        try {
            when(row.getInt("scope_ordinal")).thenReturn(data.ordinal());
            when(row.getString("locale")).thenReturn(data.locale());
            when(row.getString("contexto")).thenReturn(data.context());
            when(row.getString("audiencia")).thenReturn(data.audience());
            when(row.getObject("conjunto_id", UUID.class)).thenReturn(data.requiredSetId());
            when(row.getObject("publicacion_id", UUID.class)).thenReturn(data.publicationId());
            when(row.getString("required_set_revision")).thenReturn(data.revision());
            return row;
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ResultSet headerResultSet(HeaderData data) {
        ResultSet row = mock(ResultSet.class);
        try {
            when(row.getObject("id", UUID.class)).thenReturn(data.id());
            when(row.getString("perfil")).thenReturn(data.profile());
            when(row.getString("locale")).thenReturn(data.locale());
            when(row.getString("audiencia")).thenReturn(data.audience());
            when(row.getString("revision_scheme")).thenReturn(data.revisionScheme());
            when(row.getString("required_set_revision")).thenReturn(data.token());
            when(row.getString("provenance_fingerprint")).thenReturn(data.fingerprint());
            when(row.getInt("scope_count")).thenReturn(data.scopeCount());
            when(row.getObject("creado_en", OffsetDateTime.class)).thenReturn(
                    data.createdAt() == null
                            ? null
                            : OffsetDateTime.ofInstant(data.createdAt(), ZoneOffset.UTC));
            return row;
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Identity identity(
            LegalApplicableScopeSet scopes,
            List<PointerData> pointers,
            Harness harness) {
        List<PointerData> ordered = pointers.stream()
                .sorted(Comparator.comparingInt(PointerData::ordinal))
                .toList();
        LegalRequiredSetAggregateProjection projection = new LegalRequiredSetAggregateProjection(
                EsquemaRevisionLegal.AGGREGATE_V1,
                scopes.locale(),
                scopes.audience(),
                ordered.stream()
                        .map(pointer -> new ScopeRevision(
                                ContextoLegal.valueOf(pointer.context()),
                                pointer.revision()))
                        .toList());
        LegalRequiredSetAggregateProvenance provenance =
                new LegalRequiredSetAggregateProvenance(
                        scopes.profile(),
                        scopes.locale(),
                        scopes.audience(),
                        ordered.stream()
                                .map(pointer -> new ScopeOrigin(
                                        ContextoLegal.valueOf(pointer.context()),
                                        pointer.requiredSetId(),
                                        pointer.publicationId()))
                                .toList());
        return new Identity(
                projection,
                provenance,
                harness.semanticCalculator().calculate(projection),
                harness.provenanceCalculator().calculate(provenance));
    }

    private static HeaderData header(UUID id, Identity identity, Instant createdAt) {
        return new HeaderData(
                id,
                identity.provenance().profile().name(),
                identity.projection().locale().getCodigo(),
                identity.projection().audience().name(),
                EsquemaRevisionLegal.AGGREGATE_V1.name(),
                identity.token(),
                identity.fingerprint(),
                identity.projection().scopes().size(),
                createdAt);
    }

    private static Object[] insertArguments(UUID aggregateId, Identity identity) {
        return new Object[]{
                aggregateId,
                identity.provenance().profile().name(),
                identity.projection().locale().getCodigo(),
                identity.projection().audience().name(),
                identity.token(),
                identity.fingerprint(),
                identity.projection().scopes().size()
        };
    }

    private static Object[] identityArguments(Identity identity) {
        return new Object[]{
                identity.provenance().profile().name(),
                identity.projection().locale().getCodigo(),
                identity.projection().audience().name(),
                identity.token(),
                identity.fingerprint()
        };
    }

    private static List<PointerData> exactPointers(
            UUID firstSet,
            UUID firstPublication,
            UUID secondSet,
            UUID secondPublication) {
        return List.of(
                pointer(1, ContextoLegal.USO_CONTINUADO,
                        firstSet, firstPublication, REVISION_A),
                pointer(2, ContextoLegal.ATESTACION_FOTOS,
                        secondSet, secondPublication, REVISION_B));
    }

    private static PointerData pointer(
            int ordinal,
            ContextoLegal context,
            UUID requiredSetId,
            UUID publicationId,
            String revision) {
        return new PointerData(
                ordinal,
                "es-AR",
                context.name(),
                "USER",
                requiredSetId,
                publicationId,
                revision);
    }

    private static LegalApplicableScopeSet authenticatedScopes(ContextoLegal... contexts) {
        List<ContextoLegal> policyContexts = List.of(contexts);
        return new LegalApplicableScopeResolver((profile, audience) -> policyContexts).resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER);
    }

    private static Object[] pointerArguments(LegalApplicableScopeSet scopes) {
        List<Object> arguments = new ArrayList<>();
        for (int index = 0; index < scopes.contexts().size(); index++) {
            arguments.add(scopes.contexts().get(index).name());
            arguments.add(index + 1);
        }
        arguments.add(scopes.locale().getCodigo());
        arguments.add(scopes.audience().name());
        return arguments.toArray();
    }

    @SuppressWarnings("unchecked")
    private static Harness harness(UUID candidateId) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegalRequiredSetAggregateRevisionCalculator semantic =
                new LegalRequiredSetAggregateRevisionCalculator();
        LegalRequiredSetAggregateProvenanceCalculator provenance =
                new LegalRequiredSetAggregateProvenanceCalculator();
        LegalRequiredSetAggregateReplayVerifier replay =
                mock(LegalRequiredSetAggregateReplayVerifier.class);
        Supplier<UUID> aggregateIdGenerator = mock(Supplier.class);
        when(aggregateIdGenerator.get()).thenReturn(candidateId);
        when(replay.usesJdbc(jdbc)).thenReturn(true);
        LegalRequiredSetAggregateStore store = new LegalRequiredSetAggregateStore(
                jdbc,
                semantic,
                provenance,
                replay,
                aggregateIdGenerator);
        clearInvocations(jdbc, replay, aggregateIdGenerator);
        return new Harness(jdbc, semantic, provenance, replay, store, aggregateIdGenerator);
    }

    private static LegalEditorialTimeBoundary boundary() {
        return new LegalEditorialTimeBoundary(TRANSACTION_AT, OBSERVED_AT);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<List<Object[]>> batchCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private record Harness(
            JdbcTemplate jdbc,
            LegalRequiredSetAggregateRevisionCalculator semanticCalculator,
            LegalRequiredSetAggregateProvenanceCalculator provenanceCalculator,
            LegalRequiredSetAggregateReplayVerifier replayVerifier,
            LegalRequiredSetAggregateStore store,
            Supplier<UUID> aggregateIdGenerator) {
    }

    private record Identity(
            LegalRequiredSetAggregateProjection projection,
            LegalRequiredSetAggregateProvenance provenance,
            String token,
            String fingerprint) {
    }

    private record CreatedFixture(LegalApplicableScopeSet scopes, Identity identity) {
    }

    private record PointerData(
            int ordinal,
            String locale,
            String context,
            String audience,
            UUID requiredSetId,
            UUID publicationId,
            String revision) {

        PointerData withOrdinal(int value) {
            return new PointerData(value, locale, context, audience, requiredSetId, publicationId,
                    revision);
        }

        PointerData withLocale(String value) {
            return new PointerData(ordinal, value, context, audience, requiredSetId, publicationId,
                    revision);
        }

        PointerData withContext(String value) {
            return new PointerData(ordinal, locale, value, audience, requiredSetId, publicationId,
                    revision);
        }

        PointerData withAudience(String value) {
            return new PointerData(ordinal, locale, context, value, requiredSetId, publicationId,
                    revision);
        }

        PointerData withRequiredSetId(UUID value) {
            return new PointerData(ordinal, locale, context, audience, value, publicationId,
                    revision);
        }

        PointerData withPublicationId(UUID value) {
            return new PointerData(ordinal, locale, context, audience, requiredSetId, value,
                    revision);
        }

        PointerData withRevision(String value) {
            return new PointerData(ordinal, locale, context, audience, requiredSetId, publicationId,
                    value);
        }
    }

    private record HeaderData(
            UUID id,
            String profile,
            String locale,
            String audience,
            String revisionScheme,
            String token,
            String fingerprint,
            int scopeCount,
            Instant createdAt) {

        HeaderData withFingerprint(String value) {
            return new HeaderData(id, profile, locale, audience, revisionScheme, token, value,
                    scopeCount, createdAt);
        }
    }
}
