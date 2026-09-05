package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalDatabaseBoundaryMarkerTest {

    @Test
    void publicDocumentReadAcceptsExactlyItsOwnMarker() {
        LegalDatabaseBoundaryMarker reader = new LegalDatabaseBoundaryMarker(
                LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ);

        assertThatCode(() -> new LegalDatabaseBoundaryMarker.Guard(
                List.of(reader), LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new LegalDatabaseBoundaryMarker.Guard(
                List.of(), LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LegalDatabaseBoundaryMarker.Guard(
                List.of(reader, reader), LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publicDocumentReadCannotShareOrImpersonateAnyHistoricalBoundary() {
        LegalDatabaseBoundaryMarker reader = new LegalDatabaseBoundaryMarker(
                LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ);
        for (LegalDatabaseBoundaryMarker.Kind kind : LegalDatabaseBoundaryMarker.Kind.values()) {
            if (kind == LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ) {
                continue;
            }
            LegalDatabaseBoundaryMarker historical = new LegalDatabaseBoundaryMarker(kind);
            assertThatThrownBy(() -> new LegalDatabaseBoundaryMarker.Guard(
                    List.of(historical), LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new LegalDatabaseBoundaryMarker.Guard(List.of(reader), kind))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new LegalDatabaseBoundaryMarker.Guard(
                    List.of(reader, historical), LegalDatabaseBoundaryMarker.Kind.PUBLIC_DOCUMENT_READ))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new LegalDatabaseBoundaryMarker.Guard(List.of(historical, reader), kind))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
