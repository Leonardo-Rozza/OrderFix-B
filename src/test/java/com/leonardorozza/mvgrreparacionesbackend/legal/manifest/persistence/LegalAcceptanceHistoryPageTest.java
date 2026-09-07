package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryPage.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryPage.Document;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalAcceptanceHistoryPageTest {
    private static final String STATEMENT = "Acepto las condiciones históricas de OrdenFix.";
    private static final String DIGEST = digest(STATEMENT);
    private static final Instant AT = Instant.parse("2026-09-01T13:42:18.123456Z");

    @ParameterizedTest
    @CsvSource({"0,20,0", "1,20,20", "2147483647,100,214748364700"})
    void computesOffsetWithoutIntegerOverflow(int page, int size, long expected) {
        assertThat(LegalAcceptanceHistoryPage.pageOffset(page, size)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0,20,0", "1,20,1", "20,20,1", "21,20,2", "9007199254740991,100,90071992547410"})
    void computesCeilingWithoutUnsafeDoubleConversion(long total, int size, long expected) {
        assertThat(LegalAcceptanceHistoryPage.pagesFor(total, size)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1", "0,101", "0,2147483647"})
    void rejectsInvalidPagination(int page, int size) {
        assertThatIllegalArgumentException().isThrownBy(() -> LegalAcceptanceHistoryPage.pageOffset(page, size));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 9_007_199_254_740_992L, Long.MAX_VALUE})
    void rejectsUnsafeCounts(long total) {
        assertThatIllegalArgumentException().isThrownBy(() -> LegalAcceptanceHistoryPage.pagesFor(total, 20));
    }

    @Test
    void permitsRealEmptyAndOutOfRangePages() {
        assertThat(new LegalAcceptanceHistoryPage(List.of(), 0, 20, 0, 0).content()).isEmpty();
        assertThat(new LegalAcceptanceHistoryPage(List.of(), 2, 20, 1, 1).totalElements()).isEqualTo(1);
        assertThat(new LegalAcceptanceHistoryPage(List.of(), Integer.MAX_VALUE, 100, 105, 2).content()).isEmpty();
    }

    @Test
    void rejectsMissingRowsWithinTheSelectedPage() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalAcceptanceHistoryPage(List.of(), 0, 20, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalAcceptanceHistoryPage(List.of(acceptance(1)), 0, 20, 2, 1));
    }

    @Test
    void rejectsInventedRowsOutsideTheSelectedPageAndWrongTotalPages() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalAcceptanceHistoryPage(List.of(acceptance(1)), 1, 20, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalAcceptanceHistoryPage(List.of(acceptance(1)), 0, 20, 1, 2));
    }

    @Test
    void preservesHistoricalTextAndNestedSnapshotsWithDefensiveCopies() {
        var documents = new ArrayList<>(List.of(document(1)));
        var item = new Acceptance(uuid(1), uuid(101), ContextoLegal.REGISTRO, TipoActoLegal.ACEPTACION,
                STATEMENT, DIGEST, documents, AT);
        var content = new ArrayList<>(List.of(item));
        var page = new LegalAcceptanceHistoryPage(content, 0, 20, 1, 1);
        content.clear();
        documents.clear();
        assertThat(page.content()).containsExactly(item);
        assertThat(page.content().getFirst().statement()).isEqualTo(STATEMENT);
        assertThat(page.content().getFirst().acceptedAt()).isEqualTo(AT);
        assertThat(page.content().getFirst().documents()).containsExactly(document(1));
        assertThatThrownBy(() -> page.content().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> item.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ordersEqualInstantsByUnsignedPostgresUuidDescending() {
        var high = withId(acceptance(1), UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        var low = withId(acceptance(2), UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"));
        assertThat(new LegalAcceptanceHistoryPage(List.of(high, low), 0, 20, 2, 1).content()).containsExactly(high, low);
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalAcceptanceHistoryPage(List.of(low, high), 0, 20, 2, 1));
    }

    @Test
    void usesTimeBeforeUuidAndRejectsAscendingTime() {
        var older = withTime(acceptance(2), AT.minusSeconds(1));
        var newer = acceptance(1);
        assertThat(new LegalAcceptanceHistoryPage(List.of(newer, older), 0, 20, 2, 1).content()).containsExactly(newer, older);
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalAcceptanceHistoryPage(List.of(older, newer), 0, 20, 2, 1));
    }

    @Test
    void rejectsDuplicateAcceptanceAndRequirementIdentities() {
        var item = acceptance(1);
        var duplicateVersion = withId(item, uuid(2));
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalAcceptanceHistoryPage(List.of(item, item), 0, 20, 2, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalAcceptanceHistoryPage(List.of(duplicateVersion, item), 0, 20, 2, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "incorrecto", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "0000000000000000000000000000000000000000000000000000000000000000"})
    void rejectsStatementDigestDrift(String digest) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Acceptance(uuid(1), uuid(2), ContextoLegal.REGISTRO,
                TipoActoLegal.ACEPTACION, STATEMENT, digest, List.of(document(1)), AT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"texto\r\n", "Cafe\u0301", "\uFEFFtexto", "texto\u0000"})
    void rejectsNonCanonicalTextWithoutRepairingIt(String statement) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Acceptance(uuid(1), uuid(2), ContextoLegal.REGISTRO,
                TipoActoLegal.ACEPTACION, statement, digest(statement), List.of(document(1)), AT));
    }

    @Test
    void enforcesStatementCodePointsAtTheUtf8Boundary() {
        String maximum = "😀".repeat(1_000);
        assertThat(new Acceptance(uuid(1), uuid(2), ContextoLegal.REGISTRO, TipoActoLegal.ACEPTACION,
                maximum, digest(maximum), List.of(document(1)), AT).statement()).isEqualTo(maximum);
        String excessive = maximum + "a";
        assertThatIllegalArgumentException().isThrownBy(() -> new Acceptance(uuid(1), uuid(2), ContextoLegal.REGISTRO,
                TipoActoLegal.ACEPTACION, excessive, digest(excessive), List.of(document(1)), AT));
    }

    @Test
    void enforcesNonEmptyAtMostSixteenDistinctDocuments() {
        List<Document> maximum = java.util.stream.LongStream.rangeClosed(1, 16).mapToObj(LegalAcceptanceHistoryPageTest::document).toList();
        assertThat(withDocuments(maximum).documents()).hasSize(16);
        assertThatIllegalArgumentException().isThrownBy(() -> withDocuments(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> withDocuments(Collections.nCopies(17, document(1))));
        assertThatIllegalArgumentException().isThrownBy(() -> withDocuments(List.of(document(1), document(1))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-01T13:42:18.123456789Z", "-0001-01-01T00:00:00Z", "+10000-01-01T00:00:00Z"})
    void rejectsTimestampPrecisionOrRfc3339Overflow(String instant) {
        assertThatIllegalArgumentException().isThrownBy(() -> withTime(acceptance(1), Instant.parse(instant)));
    }

    @Test
    void validatesDocumentMetadataWithoutPresentStateOrEffectiveDate() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Document(uuid(1), TipoDocumentoLegal.TERMINOS_SERVICIO,
                "v".repeat(41), "Título", DIGEST));
        assertThatIllegalArgumentException().isThrownBy(() -> new Document(uuid(1), TipoDocumentoLegal.TERMINOS_SERVICIO,
                "1", " ", DIGEST));
        assertThatIllegalArgumentException().isThrownBy(() -> new Document(uuid(1), TipoDocumentoLegal.TERMINOS_SERVICIO,
                "1", "Título", "incorrecto"));
    }

    private static Acceptance acceptance(long id) {
        return new Acceptance(uuid(id), uuid(100 + id), ContextoLegal.REGISTRO, TipoActoLegal.ACEPTACION,
                STATEMENT, DIGEST, List.of(document(1)), AT);
    }

    private static Acceptance withId(Acceptance item, UUID id) {
        return new Acceptance(id, item.requirementVersionId(), item.context(), item.actType(), item.statement(),
                item.statementSha256(), item.documents(), item.acceptedAt());
    }

    private static Acceptance withTime(Acceptance item, Instant acceptedAt) {
        return new Acceptance(item.id(), item.requirementVersionId(), item.context(), item.actType(), item.statement(),
                item.statementSha256(), item.documents(), acceptedAt);
    }

    private static Acceptance withDocuments(List<Document> documents) {
        return new Acceptance(uuid(1), uuid(2), ContextoLegal.REGISTRO, TipoActoLegal.ACEPTACION,
                STATEMENT, DIGEST, documents, AT);
    }

    private static Document document(long id) {
        return new Document(uuid(id), TipoDocumentoLegal.TERMINOS_SERVICIO, "2026.08.1", "Condiciones originales", DIGEST);
    }

    private static UUID uuid(long id) { return new UUID(0, id); }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
