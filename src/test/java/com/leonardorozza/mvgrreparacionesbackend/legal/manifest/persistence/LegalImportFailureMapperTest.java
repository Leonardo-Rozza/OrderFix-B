package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalImportFailureMapperTest {

    private final LegalImportFailureMapper mapper = new LegalImportFailureMapper();

    @Test
    void unknownCommitStateOverridesEveryExceptionClassification() {
        LegalImportBlockedException conflict = new LegalImportBlockedException(
                LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                "database/publication");

        LegalManifestIssue issue = mapper.map(conflict, true);

        assertThat(issue.code()).isEqualTo(LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN);
        assertThat(issue.severity()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(issue.location()).isEqualTo("database/commit");
    }

    @Test
    void typedImportFailuresKeepTheirSafeIssueAndOperationalEvidenceWins() {
        LegalImportBlockedException blocked = new LegalImportBlockedException(
                LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                "database/publication");
        blocked.addSuppressed(new LegalImportOperationalException(
                LegalManifestIssueCode.IMPORT_DB_PUBLICATION_OPEN,
                "database/publication"));

        LegalManifestIssue issue = mapper.map(blocked, false);

        assertThat(issue.code())
                .isEqualTo(LegalManifestIssueCode.IMPORT_DB_PUBLICATION_OPEN);
        assertThat(issue.location()).isEqualTo("database/publication");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("legacyDatabaseMappings")
    void translatesEveryV1DatabaseIssueWithoutReusingDryRunMessages(
            LegalManifestIssueCode legacy,
            LegalManifestIssueCode expected) {
        RuntimeException failure = legacy.severity() == LegalManifestStatus.BLOCKED
                ? new LegalDryRunBlockedException(legacy, "database/evidence")
                : new LegalDryRunOperationalException(legacy, "database/evidence");

        LegalManifestIssue issue = mapper.map(failure, false);

        assertThat(issue.code()).isEqualTo(expected);
        assertThat(issue.location()).isEqualTo("database/evidence");
        assertThat(issue.message()).isEqualTo(expected.safeMessage());
        assertThat(issue.message()).doesNotContain("dry-run", "simulación");
    }

    @Test
    void unknownRuntimeFailureMapsToSafeImportDatabaseFailure() {
        IllegalStateException failure = new IllegalStateException(
                "password=secret sql=SELECT sensitive_content");

        LegalManifestIssue issue = mapper.map(failure, false);

        assertThat(issue.code())
                .isEqualTo(LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED);
        assertThat(issue.location()).isEqualTo("database");
        assertThat(issue.message()).doesNotContain("secret", "SELECT");
    }

    @Test
    void typedExceptionsRejectLegacySeverityAndAmbiguousCommitCodes() {
        assertThatThrownBy(() -> new LegalImportBlockedException(
                LegalManifestIssueCode.DB_PERSISTED_CONFLICT,
                "database"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalImportOperationalException(
                LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT,
                "database"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalImportOperationalException(
                LegalManifestIssueCode.IMPORT_DB_COMMIT_UNKNOWN,
                "database/commit"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullFailureIsRejectedEvenWhenCommitIsUnknown() {
        assertThatThrownBy(() -> mapper.map(null, true))
                .isInstanceOf(NullPointerException.class);
    }

    private static Stream<Arguments> legacyDatabaseMappings() {
        return Stream.of(
                Arguments.of(
                        LegalManifestIssueCode.DB_PERSISTED_CONFLICT,
                        LegalManifestIssueCode.IMPORT_DB_PERSISTED_CONFLICT),
                Arguments.of(
                        LegalManifestIssueCode.DB_CONSTRAINT,
                        LegalManifestIssueCode.IMPORT_DB_CONSTRAINT),
                Arguments.of(
                        LegalManifestIssueCode.DB_ISOLATION,
                        LegalManifestIssueCode.IMPORT_DB_ISOLATION),
                Arguments.of(
                        LegalManifestIssueCode.DB_LOCK_TIMEOUT,
                        LegalManifestIssueCode.IMPORT_DB_LOCK_TIMEOUT),
                Arguments.of(
                        LegalManifestIssueCode.DB_STATEMENT_TIMEOUT,
                        LegalManifestIssueCode.IMPORT_DB_STATEMENT_TIMEOUT),
                Arguments.of(
                        LegalManifestIssueCode.DB_CONNECTION,
                        LegalManifestIssueCode.IMPORT_DB_CONNECTION),
                Arguments.of(
                        LegalManifestIssueCode.DB_CONCURRENCY,
                        LegalManifestIssueCode.IMPORT_DB_CONCURRENCY),
                Arguments.of(
                        LegalManifestIssueCode.DB_SCHEMA_INCOMPATIBLE,
                        LegalManifestIssueCode.IMPORT_DB_SCHEMA_INCOMPATIBLE),
                Arguments.of(
                        LegalManifestIssueCode.DB_OPERATION_FAILED,
                        LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED));
    }
}
