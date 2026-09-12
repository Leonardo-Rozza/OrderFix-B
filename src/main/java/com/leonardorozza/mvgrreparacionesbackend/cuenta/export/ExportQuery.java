package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import java.util.Objects;

/** Internal, fixed projection. Business queries bind only the workshop; photo eligibility also binds the observation time. */
public record ExportQuery(String category, String sql) {
    public ExportQuery {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(sql, "sql");
    }
}
