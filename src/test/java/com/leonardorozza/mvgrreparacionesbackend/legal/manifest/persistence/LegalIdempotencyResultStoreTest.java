package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyResultStore.Source;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalIdempotencyResultStore.StoredResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Pure receipt/precision contracts; SQL, rollback and durable replay are exercised in the PG16 IT. */
class LegalIdempotencyResultStoreTest {
    private static final UUID RESULT = new UUID(1, 2);
    private static final UUID LOT = new UUID(3, 4);
    private static final UUID ACT = new UUID(5, 6);
    private static final Instant COMPLETED = Instant.parse("2026-09-06T12:34:56.123456Z");
    private static final Instant EXPIRES = COMPLETED.plus(Duration.ofHours(24));

    @Test
    void constructionKeepsExactJdbcIdentityWithoutAcquiringAConnection() {
        DataSource source = mock(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        var store = new LegalIdempotencyResultStore(jdbc);
        assertThat(store.usesJdbc(jdbc)).isTrue();
        assertThat(store.usesJdbc(new JdbcTemplate(source))).isFalse();
        assertThat(store.usesJdbc(null)).isFalse();
        verifyNoInteractions(source);
    }

    @Test
    void receiptRetainsTheDifferentPhysicalIdentityTypesWithoutCoercion() {
        var withActs = new StoredResult(Source.WITH_ACTS, Long.MAX_VALUE, null, 11, 12, LOT,
                "WITH_ACTS", COMPLETED, EXPIRES, List.of(ACT));
        assertThat(withActs.ledgerId()).isEqualTo(Long.MAX_VALUE);
        assertThat(withActs.supplementalId()).isNull();
        assertThat(withActs.lotId()).isEqualTo(LOT);
        var empty = new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, null,
                "EMPTY", COMPLETED, EXPIRES, List.of());
        assertThat(empty.ledgerId()).isNull();
        assertThat(empty.supplementalId()).isEqualTo(RESULT);
        assertThat(empty.lotId()).isNull();
        assertThat(empty.acceptanceIds()).isEmpty();
    }

    @Test
    void dedupIsAnIdentityWithReferencesAndNeverInventsALot() {
        var result = dedup(List.of(ACT));
        assertThat(result.result()).isEqualTo("DEDUP");
        assertThat(result.lotId()).isNull();
        assertThat(result.acceptanceIds()).containsExactly(ACT);
    }

    @Test
    void defensiveCopyUsesPostgresUnsignedUuidOrderIncludingTheSignBoundary() {
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<UUID> supplied = new ArrayList<>(List.of(high, low));
        var result = dedup(supplied);
        supplied.clear();
        assertThat(result.acceptanceIds()).containsExactly(low, high);
        assertThatThrownBy(() -> result.acceptanceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allSubmittedIdentitiesCanBeRetainedAtThe2048Limit() {
        List<UUID> ids = LongStream.range(1, 2_049).mapToObj(value -> new UUID(0, value)).toList();
        assertThat(dedup(ids).acceptanceIds()).hasSize(2_048);
        List<UUID> excess = new ArrayList<>(ids); excess.add(new UUID(0, 2_049));
        rejects(() -> dedup(excess));
    }

    @Test
    void duplicatesOrMissingReferencesCannotBecomeASuccessfulReceipt() {
        rejects(() -> dedup(List.of(ACT, ACT)));
        rejects(() -> dedup(List.of()));
        rejects(() -> dedup(null));
        List<UUID> missing = new ArrayList<>(); missing.add(null);
        rejects(() -> dedup(missing));
        rejects(() -> new StoredResult(Source.WITH_ACTS, 1L, null, 11, 12, LOT,
                "WITH_ACTS", COMPLETED, EXPIRES, List.of()));
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, null,
                "EMPTY", COMPLETED, EXPIRES, List.of(ACT)));
    }

    @Test
    void originIdLotAndOutcomeMustDescribeOneConsistentStoredShape() {
        rejects(() -> new StoredResult(Source.WITH_ACTS, null, null, 11, 12, LOT, "WITH_ACTS", COMPLETED, EXPIRES, List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITH_ACTS, 0L, null, 11, 12, LOT, "WITH_ACTS", COMPLETED, EXPIRES, List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITH_ACTS, 1L, RESULT, 11, 12, LOT, "WITH_ACTS", COMPLETED, EXPIRES, List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITH_ACTS, 1L, null, 11, 12, null, "WITH_ACTS", COMPLETED, EXPIRES, List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITH_ACTS, 1L, null, 11, 12, LOT, "DEDUP", COMPLETED, EXPIRES, List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, 1L, RESULT, 11, 12, null, "DEDUP", COMPLETED, EXPIRES, List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, null, null, 11, 12, null, "DEDUP", COMPLETED, EXPIRES, List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, LOT, "DEDUP", COMPLETED, EXPIRES, List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, null, "WITH_ACTS", COMPLETED, EXPIRES, List.of(ACT)));
        rejects(() -> new StoredResult(null, null, RESULT, 11, 12, null, "DEDUP", COMPLETED, EXPIRES, List.of(ACT)));
    }

    @ParameterizedTest
    @CsvSource({"0,12", "-1,12", "11,0", "11,-1"})
    void durableActorAndWorkshopMustBothBePositive(long user, long taller) {
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, null, RESULT, user, taller, null,
                "DEDUP", COMPLETED, EXPIRES, List.of(ACT)));
    }

    @Test
    void completionAndExpirationAreOriginalMicrosecondInstantsWithAtLeast24Hours() {
        assertThat(dedup(List.of(ACT)).completedAt()).isEqualTo(COMPLETED);
        assertThat(dedup(List.of(ACT)).expiresAt()).isEqualTo(EXPIRES);
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, null,
                "DEDUP", COMPLETED, EXPIRES.minusNanos(1_000), List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, null,
                "DEDUP", COMPLETED.plusNanos(1), EXPIRES.plusNanos(1), List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, null,
                "DEDUP", COMPLETED, EXPIRES.plusNanos(1), List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, null,
                "DEDUP", null, EXPIRES, List.of(ACT)));
        rejects(() -> new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, null,
                "DEDUP", COMPLETED, null, List.of(ACT)));
    }

    @Test
    void expiredButStillStoredResultsKeepTheirIdentity() {
        Instant old = Instant.parse("2000-01-01T00:00:00Z");
        var result = new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, null,
                "DEDUP", old, old.plus(Duration.ofHours(25)), List.of(ACT));
        assertThat(result.acceptanceIds()).containsExactly(ACT);
        assertThat(result.completedAt()).isEqualTo(old);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,1000", "999,1000", "1000,1000", "1001,2000", "999999999,1000000000"})
    void postgresqlRoundingAlwaysCeilsAndNeverShortensConfiguredRetention(int nanos, long roundedNanos) {
        Duration supplied = Duration.ofHours(24).plusNanos(nanos);
        Duration rounded = LegalIdempotencyResultStore.roundedTtl(supplied);
        assertThat(rounded).isEqualTo(Duration.ofHours(24).plusNanos(roundedNanos));
        assertThat(rounded).isGreaterThanOrEqualTo(supplied);
        assertThat(rounded.minus(supplied)).isLessThan(Duration.ofNanos(1_000));
        assertThat(rounded.getNano() % 1_000).isZero();
    }

    @Test
    void ttlRoundingDoesNotOverflowAtTheLargestConfiguredNanosecondDuration() {
        Duration maximum = Duration.ofNanos(Long.MAX_VALUE);
        assertThat(LegalIdempotencyResultStore.roundedTtl(maximum))
                .isEqualTo(Duration.ofSeconds(9_223_372_036L, 854_776_000));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 86_399})
    void storeDoesNotAllowInvalidMinimumRetention(long seconds) {
        rejects(() -> LegalIdempotencyResultStore.roundedTtl(Duration.ofSeconds(seconds)));
    }

    @Test
    void storeRejectsUnrepresentableOrMissingConfiguredTtl() {
        rejects(() -> LegalIdempotencyResultStore.roundedTtl(null));
        assertThatThrownBy(() -> LegalIdempotencyResultStore.roundedTtl(Duration.ofNanos(Long.MAX_VALUE).plusNanos(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void receiptDiagnosticDoesNotExposeActorLotOrAcceptanceIdentities() {
        String diagnostic = dedup(List.of(ACT)).toString();
        assertThat(diagnostic).isEqualTo("StoredResult[source=WITHOUT_ACTS, result=DEDUP]")
                .doesNotContain(RESULT.toString(), LOT.toString(), ACT.toString());
    }

    private static StoredResult dedup(List<UUID> ids) {
        return new StoredResult(Source.WITHOUT_ACTS, null, RESULT, 11, 12, null, "DEDUP", COMPLETED, EXPIRES, ids);
    }

    private static void rejects(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(LegalIdempotencyException.class,
                failure -> assertThat(failure.reason()).isEqualTo(LegalIdempotencyException.Reason.UNAVAILABLE));
    }
}
