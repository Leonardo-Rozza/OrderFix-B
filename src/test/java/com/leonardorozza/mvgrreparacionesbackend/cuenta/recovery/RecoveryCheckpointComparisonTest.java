package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpoint.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpointComparison.*;
import static org.assertj.core.api.Assertions.*;

class RecoveryCheckpointComparisonTest {
    final UUID environment = UUID.randomUUID();
    @Test void equalityIgnoresCaptureIdentityAndTimeButNeverAuthorizesReopening() {
        var first = snapshot(List.of(workshop(1)));
        var actual = new Snapshot(environment, UUID.randomUUID(), Instant.EPOCH, first.workshops());
        Report result = compare(first, actual);
        assertThat(result.status()).isEqualTo(Status.MATCH);
        assertThat(result.findings()).isEmpty();
        assertThat(result.notice()).isEqualTo("NO_AUTORIZA_REAPERTURA");
    }
    @Test void emptyDatabaseIsExplicitFullInventoryNotMissingEvidence() {
        assertThat(compare(snapshot(List.of()), snapshot(List.of())).status()).isEqualTo(Status.MATCH);
        assertThat(compare(snapshot(List.of()), snapshot(List.of(workshop(1)))).findings())
                .containsExactly(new Finding(1, null, Issue.UNEXPECTED_WORKSHOP));
    }
    @Test void inventoryAndLogicalChangesAreNeverCollapsedToCompatible() {
        var expected = snapshot(List.of(workshop(1), workshop(2)));
        var changed = new EnumMap<>(workshop(2).surfaces());
        changed.put(Surface.USERS, new Digest(0, "b".repeat(64)));
        var result = compare(expected, snapshot(List.of(new Workshop(2, changed), workshop(3))));
        assertThat(result.status()).isEqualTo(Status.DIFFERENCES);
        assertThat(result.findings()).containsExactly(new Finding(1, null, Issue.MISSING_WORKSHOP),
                new Finding(2, Surface.USERS, Issue.SURFACE_CHANGED), new Finding(3, null, Issue.UNEXPECTED_WORKSHOP));
    }
    @Test void environmentMismatchIsNotAComparisonResult() {
        var other = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH, List.of());
        assertThatThrownBy(() -> compare(snapshot(List.of()), other)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void incompleteDuplicateAndUnsortedInventoriesAreRejected() {
        assertThatThrownBy(() -> new Workshop(1, java.util.Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(List.of(workshop(1), workshop(1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(List.of(workshop(2), workshop(1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Digest(10001, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aggregatesCannotBypassGlobalSurfaceCapacity() {
        var high = new EnumMap<>(workshop(1).surfaces());
        high.put(Surface.USERS, new Digest(6000, "a".repeat(64)));
        assertThatThrownBy(() -> snapshot(List.of(new Workshop(1, high), new Workshop(2, high))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private Snapshot snapshot(List<Workshop> workshops) {
        return new Snapshot(environment, UUID.randomUUID(), Instant.now(), workshops);
    }
    private Workshop workshop(long id) {
        var surfaces = new EnumMap<Surface, Digest>(Surface.class);
        for (Surface surface : Surface.values()) surfaces.put(surface, new Digest(surface == Surface.WORKSHOP ? 1 : 0, "a".repeat(64)));
        return new Workshop(id, surfaces);
    }
}
