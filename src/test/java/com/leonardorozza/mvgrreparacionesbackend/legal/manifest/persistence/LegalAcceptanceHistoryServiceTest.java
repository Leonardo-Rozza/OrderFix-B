package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Focused boundary failures; PostgreSQL integration proves transactions, actor locks and historical observation. */
class LegalAcceptanceHistoryServiceTest {
    private final LegalPrivateRequirementsDataSource source = mock(LegalPrivateRequirementsDataSource.class);
    private final JdbcTemplate jdbc = new JdbcTemplate(source);
    private final LegalManifestDatabaseGate gate = mock(LegalManifestDatabaseGate.class);
    private final LegalActorSnapshotReader actor = mock(LegalActorSnapshotReader.class);
    private final LegalAcceptanceHistoryReader reader = mock(LegalAcceptanceHistoryReader.class);
    private final LegalV29AcceptanceSchemaVerifier schema = mock(LegalV29AcceptanceSchemaVerifier.class);
    private final LegalPrivateRequirementsPrivilegeVerifier privileges = mock(LegalPrivateRequirementsPrivilegeVerifier.class);

    @BeforeEach void accreditTheMatchingCollaborators() {
        when(actor.usesJdbc(jdbc)).thenReturn(true);
        when(reader.usesJdbc(jdbc)).thenReturn(true);
    }

    @ParameterizedTest @ValueSource(strings = {"source", "actor", "reader"})
    void refusesAReaderOrActorOutsideTheAccreditedJdbcGraph(String mismatch) {
        if (mismatch.equals("source")) jdbc.setDataSource(mock(DataSource.class));
        if (mismatch.equals("actor")) when(actor.usesJdbc(jdbc)).thenReturn(false);
        if (mismatch.equals("reader")) when(reader.usesJdbc(jdbc)).thenReturn(false);
        assertThatThrownBy(this::service).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(source);
    }

    @Test void requiresTheExactPrivateBoundaryAndDoesNotIgnoreItsRejection() {
        var failure = new IllegalArgumentException("boundary rejected");
        doThrow(failure).when(gate).requireExactPrivateRequirementsBoundary(jdbc, schema, privileges);
        assertThatThrownBy(this::service).isSameAs(failure);
        verifyNoInteractions(source);
    }

    @ParameterizedTest @CsvSource({"-1,20", "0,0", "0,101", "1,-1"})
    void invalidPaginationCannotStartAnObservation(int page, int size) {
        var service = service();
        clearInvocations(gate, actor, reader);
        assertThatThrownBy(() -> service.read(null, null, page, size)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(source, gate, actor, reader);
    }

    @Test void preservesActorInvalidationForTheUnauthorizedHttpDecision() {
        var service = service();
        var failure = new LegalActorSnapshotException();
        when(source.withinDeadline(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.read(null, null, 0, 20)).isSameAs(failure);
    }

    @Test void preservesAlreadyClassifiedHistoricalCorruption() {
        var service = service();
        var failure = new LegalAcceptanceHistoryReadException();
        when(source.withinDeadline(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.read(null, null, 0, 20)).isSameAs(failure);
    }

    @ParameterizedTest @ValueSource(strings = {"cleanup", "unexpected"})
    void convertsBoundaryCleanupOrOtherRuntimeFailuresToHistoricalUnavailability(String kind) {
        var service = service();
        RuntimeException failure = kind.equals("cleanup")
                ? new LegalPrivateRequirementsReadException(new IllegalStateException("cleanup detail"))
                : new IllegalStateException("internal driver detail");
        when(source.withinDeadline(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.read(null, null, 0, 20))
                .isInstanceOf(LegalAcceptanceHistoryReadException.class).hasCause(failure);
    }

    private LegalAcceptanceHistoryService service() {
        return new LegalAcceptanceHistoryService(jdbc, source, gate, actor, reader, schema, privileges);
    }
}
