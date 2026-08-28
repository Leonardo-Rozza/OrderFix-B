package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import java.util.List;
import java.util.Objects;

/** Internal marker that makes mixed dry-run/import database contexts fail during refresh. */
record LegalDatabaseBoundaryMarker(Kind kind) {

    LegalDatabaseBoundaryMarker {
        Objects.requireNonNull(kind, "kind");
    }

    enum Kind {
        DRY_RUN,
        IMPORT,
        EDITORIAL
    }

    static final class Guard {

        Guard(List<LegalDatabaseBoundaryMarker> markers, Kind expected) {
            Objects.requireNonNull(markers, "markers");
            if (markers.size() != 1 || markers.getFirst().kind() != expected) {
                throw new IllegalStateException(
                        "Los contextos DB legales no pueden combinarse");
            }
        }
    }
}
