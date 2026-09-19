package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalV37OperationalDeletionSchema;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopOperationalDeletionCandidates.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkshopOperationalDeletionCandidatesTest {
    @Test void boundedPageKeepsTheSentinelForTheNextPassAndUsesFreshReadOnlyTransaction() {
        var fixture = new Fixture(11, 12, 13);
        try (var schema = mockStatic(LegalV37OperationalDeletionSchema.class)) {
            Page page = fixture.service.next(10);
            assertThat(page.candidates()).extracting(Candidate::tallerId).containsExactly(11L, 12L);
            assertThat(page.hasMore()).isTrue();
            schema.verify(() -> LegalV37OperationalDeletionSchema.require(fixture.jdbc));
            var definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
            verify(fixture.manager).getTransaction(definition.capture());
            assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(definition.getValue().isReadOnly()).isTrue();
            assertThat(definition.getValue().getTimeout()).isEqualTo(5);
            verify(fixture.jdbc).execute("SET LOCAL statement_timeout='5s'");
            verify(fixture.manager).commit(fixture.transaction);
            assertThat(fixture.sql).contains("t.id>?", "t.activo", "t.cierre_estado='RESTRINGIDO'",
                    "t.cierre_referencia IS NOT NULL", "t.cierre_reversible_hasta<=clock_timestamp()",
                    "ORDER BY t.id LIMIT 3", WorkshopOperationalDeletionProgress.PHOTOS_PENDING_FOR_TALLER);
            assertThat(fixture.sql).doesNotContain("cuenta_borrado_lotes", "FOR UPDATE", "FOR SHARE");
            assertThat(fixture.cursor).isEqualTo(10L);
            assertThat(mockingDetails(fixture.jdbc).getInvocations()).noneMatch(call ->
                    List.of("update", "batchUpdate").contains(call.getMethod().getName()));
        }
    }

    @Test void fullPageWithoutSentinelAndEmptyPageBothSignalEndOfRotation() {
        try (var schema = mockStatic(LegalV37OperationalDeletionSchema.class)) {
            var full = new Fixture(1, 2);
            assertThat(full.service.next(0).hasMore()).isFalse();
            var empty = new Fixture();
            assertThat(empty.service.next(Long.MAX_VALUE)).isEqualTo(new Page(List.of(), false));
        }
    }

    @Test void invalidCursorDoesNotOpenATransactionOrReadTheDatabase() {
        var fixture = new Fixture();
        assertThatThrownBy(() -> fixture.service.next(-1)).isInstanceOf(Rejected.class)
                .satisfies(failure -> assertThat(((Rejected) failure).code()).isEqualTo(Rejected.Code.INVALID_CURSOR))
                .hasNoCause();
        verifyNoInteractions(fixture.jdbc, fixture.manager);
    }

    @Test void schemaFailurePreventsDiscoveryAndIsSanitized() {
        var fixture = new Fixture(1);
        try (var schema = mockStatic(LegalV37OperationalDeletionSchema.class)) {
            schema.when(() -> LegalV37OperationalDeletionSchema.require(fixture.jdbc))
                    .thenThrow(new IllegalStateException("private-schema-password"));
            assertThatThrownBy(() -> fixture.service.next(0)).isInstanceOf(Rejected.class)
                    .hasNoCause().hasMessageNotContaining("private-schema-password");
            assertThat(fixture.sql).isNull();
            verify(fixture.manager).rollback(fixture.transaction);
            verify(fixture.manager, never()).commit(any());
        }
    }

    @Test void failedCommitCannotReturnASuccessfulDiscovery() {
        var fixture = new Fixture(1);
        doThrow(new IllegalStateException("private-connection-data")).when(fixture.manager).commit(fixture.transaction);
        try (var schema = mockStatic(LegalV37OperationalDeletionSchema.class)) {
            assertThatThrownBy(() -> fixture.service.next(0)).isInstanceOf(Rejected.class)
                    .hasNoCause().hasMessageNotContaining("private-connection-data");
        }
    }

    @Test void invalidOrderingRepeatsAndRowsOutsideTheBoundFailClosed() {
        for (long[] rows : new long[][]{{1, 1}, {2, 1}, {1, 2, 3, 4}, {5}}) {
            var fixture = new Fixture(rows);
            long cursor = rows.length == 1 ? 5 : 0;
            try (var schema = mockStatic(LegalV37OperationalDeletionSchema.class)) {
                assertThatThrownBy(() -> fixture.service.next(cursor)).isInstanceOf(Rejected.class).hasNoCause();
                verify(fixture.manager).rollback(fixture.transaction);
            }
        }
    }

    @Test void pagesAreImmutableBoundedAndDoNotExposeIdentifiersInToString() {
        var candidate = new Candidate(51, UUID.randomUUID());
        var input = new ArrayList<>(List.of(candidate));
        var page = new Page(input, false);
        input.clear();
        assertThat(page.candidates()).containsExactly(candidate);
        assertThatThrownBy(() -> page.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(candidate.toString()).doesNotContain("51", candidate.closureReference().toString());
        assertThat(page.toString()).doesNotContain("51", candidate.closureReference().toString());
        assertThatThrownBy(() -> new Candidate(0, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Candidate(1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Page(List.of(candidate), true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Page(List.of(candidate, candidate), false)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
        final WorkshopOperationalDeletionCandidates service;
        String sql;
        long cursor;
        Fixture(long... ids) {
            when(manager.getTransaction(any())).thenReturn(transaction);
            List<Candidate> rows = java.util.Arrays.stream(ids).mapToObj(id -> new Candidate(id, UUID.randomUUID())).toList();
            doAnswer(call -> {
                sql = call.getArgument(0);
                cursor = call.getArgument(2);
                return rows;
            }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Candidate>>any(), any(Object[].class));
            service = new WorkshopOperationalDeletionCandidates(jdbc, manager);
        }
    }
}
