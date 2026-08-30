package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/** Executes the direct DML of an already-derived editorial execution plan. */
interface LegalEditorialMutationWriter {

    void write(LegalEditorialExecutionPlan plan);

    boolean usesJdbc(JdbcTemplate candidate);
}
