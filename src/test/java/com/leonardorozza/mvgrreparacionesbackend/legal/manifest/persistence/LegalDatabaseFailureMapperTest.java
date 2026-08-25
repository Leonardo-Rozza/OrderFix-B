package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LegalDatabaseFailureMapperTest {

    private final LegalDatabaseFailureMapper mapper = new LegalDatabaseFailureMapper();

    @ParameterizedTest(name = "SQLSTATE {0} -> {1}")
    @MethodSource("knownSqlStates")
    void traduceSqlStatesConCodigosEstables(
            String sqlState,
            LegalManifestIssueCode expectedCode,
            LegalManifestStatus expectedStatus) {
        LegalManifestIssue issue = mapper.map(sqlException(sqlState));

        assertThat(issue.code()).isEqualTo(expectedCode);
        assertThat(issue.severity()).isEqualTo(expectedStatus);
    }

    @Test
    void encuentraElSqlStateEnCausasAnidadas() {
        SQLException databaseFailure = sqlException("55P03");
        RuntimeException wrapped = new IllegalStateException(
                "Fallo operativo encapsulado",
                new RuntimeException("Frontera JDBC", databaseFailure));

        LegalManifestIssue issue = mapper.map(wrapped);

        assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.DB_LOCK_TIMEOUT);
        assertThat(issue.severity()).isEqualTo(LegalManifestStatus.ERROR);
    }

    @Test
    void recorreLaCadenaNextExceptionAunqueLaCabeceraNoTengaUnEstadoConocido() {
        SQLException head = sqlException("ZZZZZ");
        head.setNextException(sqlException("42703"));

        LegalManifestIssue issue = mapper.map(head);

        assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE);
        assertThat(issue.severity()).isEqualTo(LegalManifestStatus.ERROR);
    }

    @Test
    void unErrorOperativoDominaUnBlockerAunqueAparezcaDespuesEnLaCadena() {
        SQLException blocker = sqlException("23514");
        blocker.setNextException(sqlException("08006"));

        LegalManifestIssue issue = mapper.map(blocker);

        assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.DB_CONNECTION);
        assertThat(issue.severity()).isEqualTo(LegalManifestStatus.ERROR);
    }

    @Test
    void usaElFallbackSeguroCuandoNoHaySqlStateReconocible() {
        SQLException databaseFailure = new SQLException("Detalle interno", (String) null);
        RuntimeException wrapped = new RuntimeException("Fallo inesperado", databaseFailure);

        LegalManifestIssue issue = mapper.map(wrapped);

        assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.DB_OPERATION_FAILED);
        assertThat(issue.severity()).isEqualTo(LegalManifestStatus.ERROR);
    }

    @Test
    void traduceTimeoutsSpringAunqueNoConservenUnSqlState() {
        assertThat(mapper.map(new QueryTimeoutException("Detalle interno")).code())
                .isEqualTo(LegalManifestIssueCode.DB_STATEMENT_TIMEOUT);
        assertThat(mapper.map(new TransactionTimedOutException("Detalle interno")).code())
                .isEqualTo(LegalManifestIssueCode.DB_STATEMENT_TIMEOUT);
    }

    private static Stream<Arguments> knownSqlStates() {
        return Stream.of(
                blocked("23514", LegalManifestIssueCode.DB_CONSTRAINT),
                blocked("22001", LegalManifestIssueCode.DB_CONSTRAINT),
                blocked("P0001", LegalManifestIssueCode.DB_CONSTRAINT),
                error("25001", LegalManifestIssueCode.DB_ISOLATION),
                error("55P03", LegalManifestIssueCode.DB_LOCK_TIMEOUT),
                error("57014", LegalManifestIssueCode.DB_STATEMENT_TIMEOUT),
                error("08001", LegalManifestIssueCode.DB_CONNECTION),
                error("08006", LegalManifestIssueCode.DB_CONNECTION),
                error("40P01", LegalManifestIssueCode.DB_CONCURRENCY),
                error("40001", LegalManifestIssueCode.DB_CONCURRENCY),
                error("42P01", LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE),
                error("42703", LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE),
                error("42883", LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE),
                error("3F000", LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE),
                error("ZZZZZ", LegalManifestIssueCode.DB_OPERATION_FAILED));
    }

    private static Arguments blocked(String sqlState, LegalManifestIssueCode code) {
        return Arguments.of(sqlState, code, LegalManifestStatus.BLOCKED);
    }

    private static Arguments error(String sqlState, LegalManifestIssueCode code) {
        return Arguments.of(sqlState, code, LegalManifestStatus.ERROR);
    }

    private static SQLException sqlException(String sqlState) {
        return new SQLException("Detalle PostgreSQL no publicable", sqlState);
    }
}
