package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

/** One ordered, fail-closed database accreditation executed before the editorial lock. */
@FunctionalInterface
interface LegalDatabasePreflight {

    void verify();
}
