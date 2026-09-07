package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyCoordinator.Reservation;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LegalAcceptanceMetadataWriterTest {
    private static final LegalActorSnapshot ACTOR = new LegalActorSnapshot(1, 2, UserRole.ADMIN, 0, true, true);
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LegalAcceptanceMetadataCodec codec = new LegalAcceptanceMetadataCodec(Map.of(1, KEY), 1);
    private final LegalAcceptanceMetadataPolicy policy = new LegalAcceptanceMetadataPolicy(Duration.ofDays(7));

    @Test void rejectsMissingJdbcDataSourceAtConstruction() {
        assertThatThrownBy(() -> new LegalAcceptanceMetadataWriter(jdbc, codec, policy)).isInstanceOf(NullPointerException.class);
        verify(jdbc).getDataSource(); verifyNoMoreInteractions(jdbc);
    }

    @Test void missingReservationCannotBorrowAConnectionOrWriteMetadata() {
        var writer = writer();
        assertUnavailable(() -> writer.persist(null, ACTOR, prepared(codec)));
        verifyNoInteractions(jdbc);
    }

    @Test void missingPreparedMetadataPoisonsTheOwningReservationBeforeAnySql() {
        var writer = writer(); var reservation = reservation(jdbc);
        assertUnavailable(() -> writer.persist(reservation, ACTOR, null));
        verify(reservation).fail(any(RuntimeException.class));
        verify(reservation, never()).requireNew(); verifyNoInteractions(jdbc);
    }

    @Test void anotherCodecCannotSupplyPreparedBytesEvenWithIdenticalKeyConfiguration() {
        var writer = writer(); var reservation = reservation(jdbc);
        var other = new LegalAcceptanceMetadataCodec(Map.of(1, KEY), 1);
        assertUnavailable(() -> writer.persist(reservation, ACTOR, prepared(other)));
        verify(reservation).fail(any(RuntimeException.class));
        verify(reservation, never()).requireNew(); verifyNoInteractions(jdbc);
    }

    @Test void aDifferentJdbcBoundaryFailsBeforeAnyDatabaseOperation() {
        var writer = writer(); var reservation = reservation(mock(JdbcTemplate.class));
        assertUnavailable(() -> writer.persist(reservation, ACTOR, prepared(codec)));
        verify(reservation).fail(any(RuntimeException.class));
        verify(reservation, never()).requireNew(); verifyNoInteractions(jdbc);
    }

    @Test void replayOrConsumedReservationFailureCannotFallThroughToMetadataInsert() {
        var writer = writer(); var reservation = reservation(jdbc);
        doThrow(unavailable()).when(reservation).requireNew();
        assertUnavailable(() -> writer.persist(reservation, ACTOR, prepared(codec)));
        verify(reservation).fail(any(RuntimeException.class));
        verify(reservation, never()).requireWriteActor(any()); verifyNoInteractions(jdbc);
    }

    @Test void actorValidationFailureIsPropagatedThroughTheOwningRollbackBoundary() {
        var writer = writer(); var reservation = reservation(jdbc);
        doThrow(unavailable()).when(reservation).requireWriteActor(ACTOR);
        assertUnavailable(() -> writer.persist(reservation, ACTOR, prepared(codec)));
        verify(reservation).fail(any(RuntimeException.class));
        verify(reservation, never()).readBudget(); verifyNoInteractions(jdbc);
    }

    private LegalAcceptanceMetadataWriter writer() {
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        var result = new LegalAcceptanceMetadataWriter(jdbc, codec, policy);
        clearInvocations(jdbc); return result;
    }

    private static Reservation reservation(JdbcTemplate source) {
        Reservation reservation = mock(Reservation.class);
        when(reservation.jdbc()).thenReturn(source);
        when(reservation.fail(any(RuntimeException.class))).thenAnswer(invocation -> {
            RuntimeException failure = invocation.getArgument(0);
            return failure instanceof LegalIdempotencyException typed ? typed
                    : new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE, failure);
        });
        return reservation;
    }

    private static LegalAcceptanceMetadataCodec.PreparedMetadata prepared(LegalAcceptanceMetadataCodec source) {
        return source.prepare(UUID.randomUUID(), LegalRequestMetadata.of(LegalRequestMetadata.parseIpLiteral("192.0.2.15"), null));
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(LegalIdempotencyException.class)
                .extracting(failure -> ((LegalIdempotencyException) failure).reason())
                .isEqualTo(LegalIdempotencyException.Reason.UNAVAILABLE);
    }

    private static LegalIdempotencyException unavailable() {
        return new LegalIdempotencyException(LegalIdempotencyException.Reason.UNAVAILABLE);
    }
}
