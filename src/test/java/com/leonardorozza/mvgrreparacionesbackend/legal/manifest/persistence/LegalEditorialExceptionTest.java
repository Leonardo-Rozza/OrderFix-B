package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialExceptionTest {

    @Test
    void blockedExceptionAcceptsOnlyTheTwelveEditorialBlockers() {
        List<LegalManifestIssueCode> allowed = List.of(
                LegalManifestIssueCode.PUBLICATION_NOT_SEALED,
                LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH,
                LegalManifestIssueCode.EFFECTIVE_DATE_NOT_REACHED,
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH,
                LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS,
                LegalManifestIssueCode.SCOPE_COVERAGE_INCOMPLETE,
                LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                LegalManifestIssueCode.RETIREMENT_REASON_REQUIRED,
                LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH,
                LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED,
                LegalManifestIssueCode.REVISION_MISMATCH);

        allowed.forEach(code -> assertThatCode(() ->
                new LegalEditorialBlockedException(code, "database/state"))
                .doesNotThrowAnyException());
        assertThatThrownBy(() -> new LegalEditorialBlockedException(
                LegalManifestIssueCode.DB_PERSISTED_CONFLICT,
                "database/state"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialBlockedException(
                LegalManifestIssueCode.SCHEMA_DRIFT,
                "database/state"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void operationalExceptionAcceptsOnlyNonAmbiguousEditorialErrors() {
        List<LegalManifestIssueCode> allowed = List.of(
                LegalManifestIssueCode.CONCURRENT_OPERATION,
                LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
                LegalManifestIssueCode.SCHEMA_DRIFT,
                LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED);

        allowed.forEach(code -> assertThatCode(() ->
                new LegalEditorialOperationalException(code, "database/state"))
                .doesNotThrowAnyException());
        assertThatThrownBy(() -> new LegalEditorialOperationalException(
                LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                "database/commit"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialOperationalException(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalEditorialOperationalException(
                LegalManifestIssueCode.IMPORT_DB_OPERATION_FAILED,
                "database/state"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typedExceptionsExposeOnlySafeIssuesAndPreserveCausesInternally() {
        RuntimeException internal = new RuntimeException(
                "password=secret sql=SELECT contenido_markdown");
        LegalEditorialBlockedException blocked = new LegalEditorialBlockedException(
                LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH,
                "database/publication",
                internal);
        LegalEditorialOperationalException operational =
                new LegalEditorialOperationalException(
                        LegalManifestIssue.at(
                                LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED,
                                "database/observation"),
                        internal);

        assertThat(blocked.getCause()).isSameAs(internal);
        assertThat(blocked.issue().message())
                .isEqualTo(LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH.safeMessage());
        assertThat(blocked.getMessage()).doesNotContain("secret", "SELECT");
        assertThat(operational.getCause()).isSameAs(internal);
        assertThat(operational.issue().message())
                .isEqualTo(LegalManifestIssueCode.EDITORIAL_OBSERVATION_FAILED.safeMessage());
        assertThat(operational.getMessage()).doesNotContain("secret", "SELECT");
    }

    @Test
    void typedExceptionsRejectNullIssues() {
        assertThatThrownBy(() -> new LegalEditorialBlockedException((LegalManifestIssue) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalEditorialOperationalException(
                (LegalManifestIssue) null))
                .isInstanceOf(NullPointerException.class);
    }
}
