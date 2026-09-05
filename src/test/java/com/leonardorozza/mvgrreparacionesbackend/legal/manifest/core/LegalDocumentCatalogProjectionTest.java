package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.UUID;
import java.util.stream.BaseStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalDocumentCatalogProjectionTest {

    private static final LocaleLegal LOCALE = LocaleLegal.ES_AR;
    private static final TipoDocumentoLegal TYPE = TipoDocumentoLegal.TERMINOS_SERVICIO;
    private static final EstadoVersionLegal STATE = EstadoVersionLegal.VIGENTE;
    private static final UUID ID = new UUID(0L, 1L);
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-09-05T12:30:00.123456Z");
    private static final String SHA256 = "a".repeat(64);
    private final LegalDocumentSetRevisionCalculator calculator = new LegalDocumentSetRevisionCalculator();

    @Test
    void constructionDoesNotTouchTheIteratorAndAllowsAnUnfilteredContext() {
        RuntimeException cursorFailure = new IllegalStateException("cursor was touched");
        Iterator<LegalDocumentSummary> cursor = new Iterator<>() {
            @Override
            public boolean hasNext() {
                throw cursorFailure;
            }

            @Override
            public LegalDocumentSummary next() {
                throw cursorFailure;
            }
        };

        LegalDocumentCatalogProjection projection =
                new LegalDocumentCatalogProjection(null, LOCALE, cursor);

        assertThat(projection.context()).isNull();
        assertThat(projection.locale()).isEqualTo(LOCALE);
        assertThatThrownBy(() -> calculator.calculate(projection)).isSameAs(cursorFailure);
        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(IllegalStateException.class)
                .isNotSameAs(cursorFailure);
    }

    @Test
    void preservesTheExplicitContextWithoutReadingTheIterator() {
        CountingIterator cursor = new CountingIterator(1, false);
        LegalDocumentCatalogProjection projection =
                new LegalDocumentCatalogProjection(ContextoLegal.REGISTRO, LOCALE, cursor);

        assertThat(projection.context()).isEqualTo(ContextoLegal.REGISTRO);
        assertThat(projection.locale()).isEqualTo(LOCALE);
        assertThat(cursor.hasNextCalls).isZero();
        assertThat(cursor.nextCalls).isZero();
    }

    @Test
    void consumesALazyCatalogLargerThan128ExactlyOnceAndRejectsReplay() {
        int documentCount = 4_097;
        CountingIterator cursor = new CountingIterator(documentCount, false);
        LegalDocumentCatalogProjection projection = projection(cursor);
        assertThat(cursor.hasNextCalls).isZero();
        assertThat(cursor.nextCalls).isZero();

        String revision = calculator.calculate(projection);

        assertThat(revision).matches("sha256:[0-9a-f]{64}");
        assertThat(cursor.nextCalls).isEqualTo(documentCount);
        assertThat(cursor.hasNextCalls).isGreaterThanOrEqualTo(documentCount + 1);
        int hasNextAfterSuccess = cursor.hasNextCalls;
        int nextAfterSuccess = cursor.nextCalls;
        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cursor.hasNextCalls).isEqualTo(hasNextAfterSuccess);
        assertThat(cursor.nextCalls).isEqualTo(nextAfterSuccess);
    }

    @Test
    void theLastDocumentBeyond128ContributesToTheRevision() {
        CountingIterator unchanged = new CountingIterator(4_097, false);
        CountingIterator changedLastTitle = new CountingIterator(4_097, true);

        String firstRevision = calculator.calculate(projection(unchanged));
        String changedRevision = calculator.calculate(projection(changedLastTitle));

        assertThat(firstRevision).isNotEqualTo(changedRevision);
        assertThat(unchanged.nextCalls).isEqualTo(4_097);
        assertThat(changedLastTitle.nextCalls).isEqualTo(4_097);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void propagatesCursorFailureAfterValidRowsAndConsumesTheProjection(boolean failInHasNext) {
        RuntimeException cursorFailure = new IllegalStateException("database cursor failed");
        FaultingIterator cursor = new FaultingIterator(failInHasNext, cursorFailure);
        LegalDocumentCatalogProjection projection = projection(cursor);

        assertThatThrownBy(() -> calculator.calculate(projection)).isSameAs(cursorFailure);
        assertThat(cursor.generated).isEqualTo(3);
        int callsAfterFailure = cursor.hasNextCalls + cursor.nextCalls;
        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(IllegalStateException.class)
                .isNotSameAs(cursorFailure);
        assertThat(cursor.hasNextCalls + cursor.nextCalls).isEqualTo(callsAfterFailure);
    }

    @Test
    void rejectsAnEmptyCatalogAndDoesNotAllowASecondCalculation() {
        LegalDocumentCatalogProjection projection = projection(Collections.emptyIterator());

        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsANullRowAndDoesNotAllowASecondCalculation() {
        LegalDocumentCatalogProjection projection = projection(
                Arrays.asList(summary(ID, TYPE, EFFECTIVE_AT), null).iterator());

        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsTheCompleteFrozenTypeOrderRegardlessOfSubordinateKeys() {
        Iterator<LegalDocumentSummary> cursor = Arrays.stream(TipoDocumentoLegal.values())
                .map(type -> summary(new UUID(-1L, -1L - type.ordinal()), type,
                        EFFECTIVE_AT.plusSeconds(type.ordinal())))
                .iterator();

        assertThat(calculator.calculate(projection(cursor))).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void rejectsOutOfOrderTypesInsteadOfSortingThem() {
        assertInvalidOrder(
                summary(ID, TipoDocumentoLegal.POLITICA_PRIVACIDAD, EFFECTIVE_AT),
                summary(new UUID(0L, 2L), TYPE, EFFECTIVE_AT));
    }

    @Test
    void acceptsDescendingEffectiveDatesRegardlessOfTheUuidOrder() {
        LegalDocumentSummary newer = summary(new UUID(-1L, -1L), TYPE, EFFECTIVE_AT);
        LegalDocumentSummary older = summary(new UUID(0L, 0L), TYPE, EFFECTIVE_AT.minusNanos(1_000));

        assertThat(calculator.calculate(projection(List.of(newer, older).iterator())))
                .matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void rejectsIncreasingEffectiveDatesInsteadOfSortingThem() {
        assertInvalidOrder(
                summary(ID, TYPE, EFFECTIVE_AT.minusNanos(1_000)),
                summary(new UUID(0L, 2L), TYPE, EFFECTIVE_AT));
    }

    @ParameterizedTest
    @MethodSource("unsignedUuidBoundaries")
    void ordersEqualTypeAndDateUsingBothUuidWordsAsUnsignedLongs(UUID lowerId, UUID higherId) {
        LegalDocumentSummary lower = summary(lowerId, TYPE, EFFECTIVE_AT);
        LegalDocumentSummary higher = summary(higherId, TYPE, EFFECTIVE_AT);

        assertThat(calculator.calculate(projection(List.of(lower, higher).iterator())))
                .matches("sha256:[0-9a-f]{64}");
        assertInvalidOrder(higher, lower);
    }

    @Test
    void rejectsDuplicateSortKeysRatherThanSilentlyDeduplicatingThem() {
        LegalDocumentSummary document = summary(ID, TYPE, EFFECTIVE_AT);

        assertInvalidOrder(document, document);
    }

    @Test
    void validatesOrderAtTheEndOfALargeCatalog() {
        CountingIterator orderedPrefix = new CountingIterator(1_024, false);
        Iterator<LegalDocumentSummary> cursor = new Iterator<>() {
            private boolean finalRowRead;

            @Override
            public boolean hasNext() {
                return orderedPrefix.hasNext() || !finalRowRead;
            }

            @Override
            public LegalDocumentSummary next() {
                if (orderedPrefix.hasNext()) {
                    return orderedPrefix.next();
                }
                if (finalRowRead) {
                    throw new NoSuchElementException();
                }
                finalRowRead = true;
                return summary(ID, TYPE, EFFECTIVE_AT);
            }
        };
        LegalDocumentCatalogProjection projection = projection(cursor);

        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(orderedPrefix.nextCalls).isEqualTo(1_024);
        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAMissingLocaleOrCursorAtConstruction() {
        assertThatThrownBy(() -> new LegalDocumentCatalogProjection(
                null, null, Collections.emptyIterator()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalDocumentCatalogProjection(null, LOCALE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void doesNotExposeTheConsumableCursorThroughItsPublicApi() {
        assertThat(Modifier.isFinal(LegalDocumentCatalogProjection.class.getModifiers())).isTrue();
        assertThat(Iterable.class.isAssignableFrom(LegalDocumentCatalogProjection.class)).isFalse();
        assertThat(Iterator.class.isAssignableFrom(LegalDocumentCatalogProjection.class)).isFalse();
        assertThat(Arrays.stream(LegalDocumentCatalogProjection.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getReturnType()))
                .noneMatch(type -> Iterator.class.isAssignableFrom(type)
                        || Iterable.class.isAssignableFrom(type)
                        || Spliterator.class.isAssignableFrom(type)
                        || BaseStream.class.isAssignableFrom(type));
    }

    @ParameterizedTest
    @ValueSource(strings = {"versionId", "type", "version", "title", "sha256",
            "effectiveAt", "state", "locale"})
    void rejectsEveryMissingSummaryField(String missing) {
        assertThatThrownBy(() -> new LegalDocumentSummary(
                missing.equals("versionId") ? null : ID,
                missing.equals("type") ? null : TYPE,
                missing.equals("version") ? null : "v1",
                missing.equals("title") ? null : "Título",
                missing.equals("sha256") ? null : SHA256,
                missing.equals("effectiveAt") ? null : EFFECTIVE_AT,
                missing.equals("state") ? null : STATE,
                missing.equals("locale") ? null : LOCALE))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoVersionLegal.class, names = {"VIGENTE", "REEMPLAZADA", "RETIRADA"})
    void acceptsEveryPublicHistoricalState(EstadoVersionLegal state) {
        LegalDocumentSummary document = new LegalDocumentSummary(
                ID, TYPE, "v1", "Título", SHA256, EFFECTIVE_AT, state, LOCALE);

        assertThat(document.state()).isEqualTo(state);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoVersionLegal.class, names = {"BORRADOR", "PUBLICADA"})
    void rejectsStatesOutsideThePublicCatalog(EstadoVersionLegal state) {
        assertThatThrownBy(() -> new LegalDocumentSummary(
                ID, TYPE, "v1", "Título", SHA256, EFFECTIVE_AT, state, LOCALE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void measuresVersionAndTitleLimitsInUnicodeCodePoints() {
        String supplementaryCharacter = "\uD83D\uDE00";
        String version = supplementaryCharacter.repeat(64);
        String title = supplementaryCharacter.repeat(300);

        LegalDocumentSummary boundary = withText(version, title);

        assertThat(boundary.version()).isEqualTo(version);
        assertThat(boundary.title()).isEqualTo(title);
        assertThatThrownBy(() -> withText(supplementaryCharacter.repeat(65), title))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withText(version, supplementaryCharacter.repeat(301)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "     "})
    void rejectsEmptyOrAsciiSpaceOnlyText(String invalid) {
        assertThatThrownBy(() -> withText(invalid, "Título"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withText("v1", invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesWhitespaceAllowedByPostgresBtrimAndDoesNotNormalizeUnicode() {
        LegalDocumentSummary whitespace = withText(" \t ", " \n ");
        String decomposed = "e\u0301";
        LegalDocumentSummary unicode = withText(decomposed, decomposed);
        LegalDocumentSummary composed = withText("\u00e9", "\u00e9");

        assertThat(whitespace.version()).isEqualTo(" \t ");
        assertThat(whitespace.title()).isEqualTo(" \n ");
        assertThat(unicode.version()).isEqualTo(decomposed).isNotEqualTo("\u00e9");
        assertThat(unicode.title()).isEqualTo(decomposed).isNotEqualTo("\u00e9");
        String decomposedRevision = calculator.calculate(projection(List.of(unicode).iterator()));
        String composedRevision = calculator.calculate(projection(List.of(composed).iterator()));
        assertThat(decomposedRevision).isNotEqualTo(composedRevision);
    }

    @ParameterizedTest
    @MethodSource("invalidUnicode")
    void rejectsNulAndUnpairedSurrogatesInBothTextFields(String invalid) {
        assertThatThrownBy(() -> withText(invalid, "Título"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withText("v1", invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidDigests")
    void rejectsEveryNonCanonicalDigest(String digest) {
        assertThatThrownBy(() -> new LegalDocumentSummary(
                ID, TYPE, "v1", "Título", digest, EFFECTIVE_AT, STATE, LOCALE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0000-01-01T00:00:00Z",
            "2026-09-05T12:30:00.123456Z",
            "9999-12-31T23:59:59.999999Z"
    })
    void acceptsTheRfc3339YearBoundsAndMicrosecondPrecision(String text) {
        LegalDocumentSummary document = summary(ID, TYPE, Instant.parse(text));

        assertThat(document.effectiveAtUtc()).isEqualTo(text);
        assertThat(document.effectiveAt()).isEqualTo(Instant.parse(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-0001-12-31T23:59:59.999999Z",
            "+10000-01-01T00:00:00Z",
            "2026-09-05T12:30:00.000000001Z",
            "2026-09-05T12:30:00.123456001Z",
            "2026-09-05T12:30:00.999999999Z"
    })
    void rejectsUnrepresentableYearsAndSubMicrosecondTimestamps(String text) {
        assertThatThrownBy(() -> summary(ID, TYPE, Instant.parse(text)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsEffectiveDatesUsingTheCanonicalIsoInstantRepresentation() {
        LegalDocumentSummary document = summary(
                ID, TYPE, Instant.parse("2026-09-05T12:30:00.123000000Z"));

        assertThat(document.effectiveAtUtc()).isEqualTo("2026-09-05T12:30:00.123Z");
    }

    private void assertInvalidOrder(LegalDocumentSummary first, LegalDocumentSummary second) {
        LegalDocumentCatalogProjection projection = projection(List.of(first, second).iterator());
        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(projection))
                .isInstanceOf(IllegalStateException.class);
    }

    private static LegalDocumentCatalogProjection projection(Iterator<LegalDocumentSummary> cursor) {
        return new LegalDocumentCatalogProjection(null, LOCALE, cursor);
    }

    private static LegalDocumentSummary summary(UUID id, TipoDocumentoLegal type, Instant effectiveAt) {
        return new LegalDocumentSummary(id, type, "v1", "Título", SHA256, effectiveAt, STATE, LOCALE);
    }

    private static LegalDocumentSummary withText(String version, String title) {
        return new LegalDocumentSummary(ID, TYPE, version, title, SHA256, EFFECTIVE_AT, STATE, LOCALE);
    }

    private static Stream<Arguments> unsignedUuidBoundaries() {
        return Stream.of(
                Arguments.of(new UUID(Long.MAX_VALUE, 0L), new UUID(Long.MIN_VALUE, 0L)),
                Arguments.of(new UUID(0L, Long.MAX_VALUE), new UUID(0L, Long.MIN_VALUE)),
                Arguments.of(new UUID(0L, -1L), new UUID(1L, 0L)));
    }

    private static Stream<String> invalidUnicode() {
        return Stream.of("\u0000", "a\u0000b", "\uD800", "\uDC00", "a\uD800b", "a\uDC00b");
    }

    private static Stream<String> invalidDigests() {
        return Stream.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64),
                "g".repeat(64), "sha256:" + SHA256, " " + "a".repeat(63));
    }

    /**
     * Generates each row only when requested. It has no backing collection and
     * counts cursor access independently of the number of generated rows.
     */
    private static final class CountingIterator implements Iterator<LegalDocumentSummary> {
        private final int count;
        private final boolean changeLastTitle;
        private int generated;
        private int hasNextCalls;
        private int nextCalls;

        private CountingIterator(int count, boolean changeLastTitle) {
            this.count = count;
            this.changeLastTitle = changeLastTitle;
        }

        @Override
        public boolean hasNext() {
            hasNextCalls++;
            return generated < count;
        }

        @Override
        public LegalDocumentSummary next() {
            nextCalls++;
            if (generated >= count) {
                throw new NoSuchElementException();
            }
            generated++;
            String title = changeLastTitle && generated == count ? "Título final distinto" : "Título";
            return new LegalDocumentSummary(new UUID(0L, generated), TYPE,
                    "v1", title, SHA256, EFFECTIVE_AT, STATE, LOCALE);
        }
    }

    private static final class FaultingIterator implements Iterator<LegalDocumentSummary> {
        private final boolean failInHasNext;
        private final RuntimeException failure;
        private int generated;
        private int hasNextCalls;
        private int nextCalls;

        private FaultingIterator(boolean failInHasNext, RuntimeException failure) {
            this.failInHasNext = failInHasNext;
            this.failure = failure;
        }

        @Override
        public boolean hasNext() {
            hasNextCalls++;
            if (failInHasNext && generated == 3) {
                throw failure;
            }
            return generated < 6;
        }

        @Override
        public LegalDocumentSummary next() {
            nextCalls++;
            if (!failInHasNext && generated == 3) {
                throw failure;
            }
            if (generated >= 6) {
                throw new NoSuchElementException();
            }
            generated++;
            return summary(new UUID(0L, generated), TYPE, EFFECTIVE_AT);
        }
    }
}
