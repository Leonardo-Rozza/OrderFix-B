package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialFailureMapperTest {

    private final LegalEditorialFailureMapper mapper = new LegalEditorialFailureMapper();

    @ParameterizedTest(name = "SQLSTATE {0} -> {1}")
    @MethodSource("knownSqlStates")
    void mapsSqlStatesToTheClosedEditorialErrorVocabulary(
            String sqlState,
            LegalManifestIssueCode expectedCode,
            String expectedLocation) {
        LegalManifestIssue issue = mapper.map(sqlException(sqlState));

        assertThat(issue.code()).isEqualTo(expectedCode);
        assertThat(issue.severity()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(issue.location()).isEqualTo(expectedLocation);
    }

    @Test
    void typedEditorialEvidenceWinsAndOperationalDominatesBlocked() {
        LegalEditorialBlockedException blocked = new LegalEditorialBlockedException(
                LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH,
                "database/publication");
        blocked.addSuppressed(new LegalEditorialOperationalException(
                LegalManifestIssueCode.SCHEMA_DRIFT,
                "database/schema"));
        blocked.addSuppressed(new SQLException("connection detail", "08006"));

        LegalManifestIssue issue = mapper.map(blocked);

        assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT);
        assertThat(issue.location()).isEqualTo("database/schema");
    }

    @Test
    void aTypedBlockedFindingKeepsItsSafeIssueWhenNoOperationalFailureExists() {
        LegalEditorialBlockedException blocked = new LegalEditorialBlockedException(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state");

        LegalManifestIssue issue = mapper.map(blocked);

        assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.CURRENT_STATE_MISMATCH);
        assertThat(issue.severity()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(issue.location()).isEqualTo("database/state");
    }

    @Test
    void sqlOperationalEvidenceDominatesAnEscapedTypedBlocker() {
        LegalEditorialBlockedException blocked = new LegalEditorialBlockedException(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state");
        blocked.addSuppressed(sqlException("42703"));

        LegalManifestIssue issue = mapper.map(blocked);

        assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT);
        assertThat(issue.location()).isEqualTo(LegalEditorialFailureMapper.SCHEMA_LOCATION);
    }

    @Test
    void traversesNestedNextAndSuppressedFailuresWithoutReadingTheirMessages() {
        SQLException head = new SQLException(
                "password=secret sql=SELECT contenido_markdown",
                "ZZZZZ");
        head.setNextException(new SQLException("undefined column", "42703"));
        RuntimeException root = new IllegalStateException(
                "ruta=/private/legal",
                new RuntimeException("jdbc", head));

        LegalManifestIssue issue = mapper.map(root);

        assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.SCHEMA_DRIFT);
        assertThat(issue.message())
                .doesNotContain("secret", "SELECT", "contenido_markdown", "/private");
    }

    @Test
    void connectionTimeoutIsolationAndUnknownRuntimeFailAsObservationErrors() {
        for (Throwable failure : new Throwable[]{
                new CannotCreateTransactionException("credentials=secret"),
                new QueryTimeoutException("sql=SELECT private"),
                new TransactionTimedOutException("path=/private/legal"),
                new IllegalStateException("password=secret")}) {
            LegalManifestIssue issue = mapper.map(failure);

            assertThat(issue.code())
                    .isEqualTo(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
            assertThat(issue.location())
                    .isEqualTo(LegalEditorialFailureMapper.OBSERVATION_LOCATION);
            assertThat(issue.message())
                    .doesNotContain("secret", "SELECT", "/private");
        }
    }

    @Test
    void anObservationFailureDominatesOtherUntypedSqlEvidence() {
        SQLException concurrency = sqlException("40P01");
        concurrency.setNextException(sqlException("08006"));

        LegalManifestIssue issue = mapper.map(concurrency);

        assertThat(issue.code())
                .isEqualTo(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);
    }

    @Test
    void rejectsANullFailure() {
        assertThatThrownBy(() -> mapper.map(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Stream<Arguments> knownSqlStates() {
        return Stream.of(
                Arguments.of(
                        "55P03",
                        LegalManifestIssueCode.CONCURRENT_OPERATION,
                        LegalEditorialFailureMapper.CONCURRENCY_LOCATION),
                Arguments.of(
                        "40P01",
                        LegalManifestIssueCode.CONCURRENT_OPERATION,
                        LegalEditorialFailureMapper.CONCURRENCY_LOCATION),
                Arguments.of(
                        "40001",
                        LegalManifestIssueCode.CONCURRENT_OPERATION,
                        LegalEditorialFailureMapper.CONCURRENCY_LOCATION),
                Arguments.of(
                        "42501",
                        LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
                        LegalEditorialFailureMapper.PRIVILEGES_LOCATION),
                Arguments.of(
                        "42P01",
                        LegalManifestIssueCode.SCHEMA_DRIFT,
                        LegalEditorialFailureMapper.SCHEMA_LOCATION),
                Arguments.of(
                        "42703",
                        LegalManifestIssueCode.SCHEMA_DRIFT,
                        LegalEditorialFailureMapper.SCHEMA_LOCATION),
                Arguments.of(
                        "42883",
                        LegalManifestIssueCode.SCHEMA_DRIFT,
                        LegalEditorialFailureMapper.SCHEMA_LOCATION),
                Arguments.of(
                        "3F000",
                        LegalManifestIssueCode.SCHEMA_DRIFT,
                        LegalEditorialFailureMapper.SCHEMA_LOCATION),
                Arguments.of(
                        "08001",
                        LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                        LegalEditorialFailureMapper.OBSERVATION_LOCATION),
                Arguments.of(
                        "08006",
                        LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                        LegalEditorialFailureMapper.OBSERVATION_LOCATION),
                Arguments.of(
                        "57014",
                        LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                        LegalEditorialFailureMapper.OBSERVATION_LOCATION),
                Arguments.of(
                        "25001",
                        LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                        LegalEditorialFailureMapper.OBSERVATION_LOCATION),
                Arguments.of(
                        "23514",
                        LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                        LegalEditorialFailureMapper.OBSERVATION_LOCATION),
                Arguments.of(
                        "ZZZZZ",
                        LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                        LegalEditorialFailureMapper.OBSERVATION_LOCATION));
    }

    private static SQLException sqlException(String sqlState) {
        return new SQLException("Detalle PostgreSQL no publicable", sqlState);
    }
}
