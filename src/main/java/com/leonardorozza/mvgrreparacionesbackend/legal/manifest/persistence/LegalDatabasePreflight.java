package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/** One ordered, fail-closed database accreditation executed before the editorial lock. */
@FunctionalInterface
interface LegalDatabasePreflight {

    void verify();

    /** Proves that this preflight runs through the same JDBC session as the protected graph. */
    default boolean usesJdbc(JdbcTemplate candidate) {
        return false;
    }
}
