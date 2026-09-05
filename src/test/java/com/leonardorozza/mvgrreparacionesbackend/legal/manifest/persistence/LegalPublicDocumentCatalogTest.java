package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LegalPublicDocumentCatalogTest {

    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    @Test
    void multipliesThePageOffsetAsLongBeforeTheOperation() {
        assertThat(LegalPublicDocumentCatalog.pageOffset(Integer.MAX_VALUE, 100))
                .isEqualTo(214_748_364_700L);
        assertThat(LegalPublicDocumentCatalog.pageOffset(0, 100)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1", "0,101"})
    void rejectsInvalidPageCoordinates(int page, int size) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> LegalPublicDocumentCatalog.pageOffset(page, size));
    }

    @ParameterizedTest
    @CsvSource({
            "1,100,1",
            "100,100,1",
            "101,100,2",
            "9007199254740991,1,9007199254740991",
            "9007199254740991,100,90071992547410"
    })
    void computesExactCeilDivisionThroughTheJavaScriptIntegerBoundary(
            long elements, int size, long expectedPages) {
        assertThat(LegalPublicDocumentCatalog.pagesFor(elements, size)).isEqualTo(expectedPages);
    }

    @Test
    void admitsTheLargestSafeCountAndRejectsLargerOrEmptyCounts() {
        LegalPublicDocumentCatalog accepted = new LegalPublicDocumentCatalog(
                null, LocaleLegal.ES_AR, REVISION, List.of(summary()),
                0, 1, MAX_SAFE_INTEGER, MAX_SAFE_INTEGER);
        assertThat(accepted.totalElements()).isEqualTo(MAX_SAFE_INTEGER);
        assertThat(accepted.totalPages()).isEqualTo(MAX_SAFE_INTEGER);

        for (long invalid : new long[]{0, -1, MAX_SAFE_INTEGER + 1, Long.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new LegalPublicDocumentCatalog(
                    null, LocaleLegal.ES_AR, REVISION, List.of(summary()),
                    0, 1, invalid, invalid));
        }
    }

    @Test
    void requiresTheExactPageCardinalityAndTotalPages() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalPublicDocumentCatalog(
                null, LocaleLegal.ES_AR, REVISION, List.of(), 0, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalPublicDocumentCatalog(
                null, LocaleLegal.ES_AR, REVISION, List.of(summary()), 1, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalPublicDocumentCatalog(
                null, LocaleLegal.ES_AR, REVISION, List.of(summary()), 0, 1, 1, 2));

        LegalPublicDocumentCatalog beyondEnd = new LegalPublicDocumentCatalog(
                null, LocaleLegal.ES_AR, REVISION, List.of(), Integer.MAX_VALUE, 100, 1, 1);
        assertThat(beyondEnd.documents()).isEmpty();
        assertThat(beyondEnd.documentSetRevision()).isEqualTo(REVISION);
        assertThat(beyondEnd.totalElements()).isOne();
    }

    @Test
    void keepsAnImmutableCopyOfOnlyTheRequestedPage() {
        List<LegalDocumentSummary> input = new ArrayList<>(List.of(summary()));
        LegalPublicDocumentCatalog catalog = new LegalPublicDocumentCatalog(
                null, LocaleLegal.ES_AR, REVISION, input, 0, 1, 1, 1);

        input.clear();

        assertThat(catalog.documents()).containsExactly(summary());
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> catalog.documents().clear());
    }

    private static LegalDocumentSummary summary() {
        return new LegalDocumentSummary(
                UUID.fromString("80000000-0000-0000-0000-000000000001"),
                TipoDocumentoLegal.TERMINOS_SERVICIO, "1.0", "Términos", "b".repeat(64),
                Instant.parse("2026-09-05T12:34:56.123456Z"),
                EstadoVersionLegal.VIGENTE, LocaleLegal.ES_AR);
    }
}
