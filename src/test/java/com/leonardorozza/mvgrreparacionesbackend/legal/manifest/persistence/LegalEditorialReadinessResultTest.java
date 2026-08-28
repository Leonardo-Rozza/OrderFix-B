package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialReadiness;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssue;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialReadinessResultTest {

    private static final UUID PUBLICATION_UUID = UUID.fromString(
            "60870649-efbb-4d2b-8407-4f1901b86a45");
    private static final Instant OBSERVED_AT = Instant.parse(
            "2026-08-28T12:30:00.123456Z");
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    private static final List<ExpectedReason> EXPECTED_EDITORIAL_REASONS = List.of(
            reason(
                    LegalManifestIssueCode.PUBLICATION_NOT_SEALED,
                    LegalManifestStatus.BLOCKED,
                    "La publicación legal objetivo no está sellada."),
            reason(
                    LegalManifestIssueCode.PUBLICATION_CONTENT_MISMATCH,
                    LegalManifestStatus.BLOCKED,
                    "La publicación legal persistida no coincide con el release confirmado."),
            reason(
                    LegalManifestIssueCode.EFFECTIVE_DATE_NOT_REACHED,
                    LegalManifestStatus.BLOCKED,
                    "Una versión legal todavía no alcanzó su fecha de vigencia."),
            reason(
                    LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                    LegalManifestStatus.BLOCKED,
                    "El estado editorial actual no coincide con el estado esperado."),
            reason(
                    LegalManifestIssueCode.SOURCE_FINGERPRINT_MISMATCH,
                    LegalManifestStatus.BLOCKED,
                    "El fingerprint editorial de origen no coincide con el confirmado."),
            reason(
                    LegalManifestIssueCode.INITIAL_PROJECTION_ALREADY_EXISTS,
                    LegalManifestStatus.BLOCKED,
                    "Ya existe una proyección o historia editorial que impide la primera promoción."),
            reason(
                    LegalManifestIssueCode.SCOPE_COVERAGE_INCOMPLETE,
                    LegalManifestStatus.BLOCKED,
                    "La cobertura editorial de scopes no está completa."),
            reason(
                    LegalManifestIssueCode.REPLACEMENT_MAPPING_INVALID,
                    LegalManifestStatus.BLOCKED,
                    "El mapeo editorial de reemplazo no es válido."),
            reason(
                    LegalManifestIssueCode.RETIREMENT_REASON_REQUIRED,
                    LegalManifestStatus.BLOCKED,
                    "El retiro editorial requiere un motivo no vacío."),
            reason(
                    LegalManifestIssueCode.EXPECTED_READINESS_MISMATCH,
                    LegalManifestStatus.BLOCKED,
                    "El readiness editorial esperado no coincide con la operación solicitada."),
            reason(
                    LegalManifestIssueCode.FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED,
                    LegalManifestStatus.BLOCKED,
                    "El hueco legal fail-closed no fue reconocido explícitamente."),
            reason(
                    LegalManifestIssueCode.REVISION_MISMATCH,
                    LegalManifestStatus.BLOCKED,
                    "La revisión legal persistida no coincide con la revisión esperada."),
            reason(
                    LegalManifestIssueCode.CONCURRENT_OPERATION,
                    LegalManifestStatus.ERROR,
                    "La operación editorial no pudo completarse por un conflicto concurrente."),
            reason(
                    LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
                    LegalManifestStatus.ERROR,
                    "La credencial editorial no posee el perfil PostgreSQL mínimo exacto requerido."),
            reason(
                    LegalManifestIssueCode.SCHEMA_DRIFT,
                    LegalManifestStatus.ERROR,
                    "La base no posee el schema legal V27 editorial exacto requerido."),
            reason(
                    LegalManifestIssueCode.POSTCONDITION_NOT_READY,
                    LegalManifestStatus.ERROR,
                    "La operación editorial no alcanzó el readiness requerido."),
            reason(
                    LegalManifestIssueCode.COMMIT_OUTCOME_UNKNOWN,
                    LegalManifestStatus.ERROR,
                    "No se pudo determinar si la operación editorial fue confirmada."));

    @Test
    void freezesTheReadinessObservationAndFactoryShapes() throws NoSuchMethodException {
        assertThat(LegalEditorialReadiness.values())
                .containsExactly(
                        LegalEditorialReadiness.READY,
                        LegalEditorialReadiness.NOT_READY,
                        LegalEditorialReadiness.ERROR);
        assertThat(LegalEditorialReadinessObservation.class.isRecord()).isTrue();
        RecordComponent[] observationComponents =
                LegalEditorialReadinessObservation.class.getRecordComponents();
        assertThat(Arrays.stream(observationComponents)
                .map(component -> component.getName()))
                .containsExactly(
                        "publicationUuid",
                        "observedAt",
                        "editorialStateFingerprint",
                        "documentVersions",
                        "requirementVersions",
                        "documentTransitions",
                        "requirementTransitions",
                        "documentSlots",
                        "currentRequirementSets",
                        "replacementLots")
                .doesNotContain("documentSetRevision");
        assertThat(Arrays.stream(observationComponents)
                .map(component -> component.getGenericType().getTypeName()))
                .containsExactly(
                        "java.util.Optional<java.util.UUID>",
                        "java.time.Instant",
                        "java.lang.String",
                        "int", "int", "int", "int", "int", "int", "int");

        assertThat(Modifier.isFinal(
                LegalEditorialReadinessResult.class.getModifiers())).isTrue();
        assertThat(LegalEditorialReadinessResult.class.getConstructors()).isEmpty();
        assertThat(LegalEditorialReadinessResult.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(
                        Modifier.isPrivate(constructor.getModifiers())).isTrue());

        assertPackagePrivateStaticFactory(
                "ready",
                LegalEditorialReadinessObservation.class);
        assertPackagePrivateStaticFactory(
                "notReady",
                LegalEditorialReadinessObservation.class,
                Collection.class);
        assertPackagePrivateStaticFactory("error", Collection.class);
    }

    @Test
    void privateConstructorRejectsEveryInvalidReadinessMatrix() throws Exception {
        Constructor<LegalEditorialReadinessResult> constructor =
                LegalEditorialReadinessResult.class.getDeclaredConstructor(
                        LegalEditorialReadiness.class,
                        LegalEditorialReadinessObservation.class,
                        List.class,
                        int.class);
        constructor.setAccessible(true);
        LegalEditorialReadinessObservation complete = observation(
                Optional.of(PUBLICATION_UUID));
        LegalEditorialReadinessObservation missing = observation(Optional.empty());
        LegalManifestIssue blocker = issue(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state");
        LegalManifestIssue failure = issue(
                LegalManifestIssueCode.SCHEMA_DRIFT,
                "database/schema");

        assertInvalidMatrix(constructor, LegalEditorialReadiness.READY, null, List.of(), 0);
        assertInvalidMatrix(constructor, LegalEditorialReadiness.READY, missing, List.of(), 0);
        assertInvalidMatrix(
                constructor, LegalEditorialReadiness.READY, complete, List.of(blocker), 0);
        assertInvalidMatrix(constructor, LegalEditorialReadiness.READY, complete, List.of(), 1);
        assertInvalidMatrix(
                constructor, LegalEditorialReadiness.NOT_READY, null, List.of(blocker), 0);
        assertInvalidMatrix(
                constructor, LegalEditorialReadiness.NOT_READY, complete, List.of(), 0);
        assertInvalidMatrix(
                constructor, LegalEditorialReadiness.ERROR, complete, List.of(failure), 0);
        assertInvalidMatrix(constructor, LegalEditorialReadiness.ERROR, null, List.of(), 0);
    }

    @Test
    void readyIsPassNonPersistedAndCarriesACompleteObservation() {
        LegalEditorialReadinessObservation observation = observation(
                Optional.of(PUBLICATION_UUID));

        LegalEditorialReadinessResult result =
                LegalEditorialReadinessResult.ready(observation);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.persisted()).isFalse();
        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.READY);
        assertThat(result.observation()).contains(observation);
        assertThat(result.issues()).isEmpty();
        assertThat(result.omittedIssueCount()).isZero();
    }

    @Test
    void notReadyIsBlockedNonPersistedAndKeepsAnObservationWithoutAPersistedTarget() {
        LegalEditorialReadinessObservation observation = observation(Optional.empty());
        LegalManifestIssue finding = issue(
                LegalManifestIssueCode.PUBLICATION_NOT_SEALED,
                "database/publication");

        LegalEditorialReadinessResult result =
                LegalEditorialReadinessResult.notReady(observation, List.of(finding));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.persisted()).isFalse();
        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.NOT_READY);
        assertThat(result.observation()).contains(observation);
        assertThat(result.observation().orElseThrow().publicationUuid()).isEmpty();
        assertThat(result.issues()).containsExactly(finding);
        assertThat(result.omittedIssueCount()).isZero();
    }

    @Test
    void errorIsNonPersistedAndNeverExposesAPartialObservation() {
        LegalManifestIssue failure = issue(
                LegalManifestIssueCode.SCHEMA_DRIFT,
                "database/schema");

        LegalEditorialReadinessResult result =
                LegalEditorialReadinessResult.error(List.of(failure));

        assertThat(result.status()).isEqualTo(LegalManifestStatus.ERROR);
        assertThat(result.persisted()).isFalse();
        assertThat(result.readiness()).isEqualTo(LegalEditorialReadiness.ERROR);
        assertThat(result.observation()).isEmpty();
        assertThat(result.issues()).containsExactly(failure);
        assertThat(result.omittedIssueCount()).isZero();
    }

    @Test
    void factoriesRejectNullEmptyForeignAndWrongSeverityInputs() {
        LegalEditorialReadinessObservation complete = observation(
                Optional.of(PUBLICATION_UUID));
        LegalEditorialReadinessObservation missing = observation(Optional.empty());
        LegalManifestIssue blocker = issue(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/state");
        LegalManifestIssue error = issue(
                LegalManifestIssueCode.CONCURRENT_OPERATION,
                "database");
        LegalManifestIssue foreignBlocker = issue(
                LegalManifestIssueCode.DB_PERSISTED_CONFLICT,
                "database/publication");
        LegalManifestIssue foreignError = issue(
                LegalManifestIssueCode.IMPORT_DB_CONNECTION,
                "database");
        List<LegalManifestIssue> containingNull = new ArrayList<>();
        containingNull.add(null);

        assertThatThrownBy(() -> LegalEditorialReadinessResult.ready(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.ready(missing))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.notReady(
                null,
                List.of(blocker)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.notReady(complete, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.notReady(
                complete,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.notReady(
                complete,
                containingNull))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.notReady(
                complete,
                List.of(error)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.notReady(
                complete,
                List.of(foreignBlocker)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.error(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.error(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.error(List.of(blocker)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.error(List.of(foreignError)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freezesTheExactSeventeenReasonCodesSeveritiesMessagesAndAllowlist() {
        assertThat(EXPECTED_EDITORIAL_REASONS).hasSize(17);
        assertThat(EXPECTED_EDITORIAL_REASONS)
                .extracting(expected -> expected.code().name())
                .containsExactly(
                        "PUBLICATION_NOT_SEALED",
                        "PUBLICATION_CONTENT_MISMATCH",
                        "EFFECTIVE_DATE_NOT_REACHED",
                        "CURRENT_STATE_MISMATCH",
                        "SOURCE_FINGERPRINT_MISMATCH",
                        "INITIAL_PROJECTION_ALREADY_EXISTS",
                        "SCOPE_COVERAGE_INCOMPLETE",
                        "REPLACEMENT_MAPPING_INVALID",
                        "RETIREMENT_REASON_REQUIRED",
                        "EXPECTED_READINESS_MISMATCH",
                        "FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED",
                        "REVISION_MISMATCH",
                        "CONCURRENT_OPERATION",
                        "ROLE_PRIVILEGE_DRIFT",
                        "SCHEMA_DRIFT",
                        "POSTCONDITION_NOT_READY",
                        "COMMIT_OUTCOME_UNKNOWN");

        Map<LegalManifestIssueCode, ExpectedReason> expectedByCode =
                EXPECTED_EDITORIAL_REASONS.stream().collect(Collectors.toUnmodifiableMap(
                        ExpectedReason::code,
                        Function.identity()));
        EXPECTED_EDITORIAL_REASONS.forEach(expected -> {
            assertThat(expected.code().severity()).isEqualTo(expected.severity());
            assertThat(expected.code().safeMessage()).isEqualTo(expected.message());
        });

        LegalEditorialReadinessObservation observation = observation(
                Optional.of(PUBLICATION_UUID));
        for (LegalManifestIssueCode code : LegalManifestIssueCode.values()) {
            LegalManifestIssue candidate = issue(code, "database/catalog");
            ExpectedReason expected = expectedByCode.get(code);
            if (expected == null) {
                if (code.severity() == LegalManifestStatus.BLOCKED) {
                    assertThatThrownBy(() -> LegalEditorialReadinessResult.notReady(
                            observation,
                            List.of(candidate)))
                            .isInstanceOf(IllegalArgumentException.class);
                } else {
                    assertThatThrownBy(() -> LegalEditorialReadinessResult.error(
                            List.of(candidate)))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            } else if (expected.severity() == LegalManifestStatus.BLOCKED) {
                assertThatCode(() -> LegalEditorialReadinessResult.notReady(
                        observation,
                        List.of(candidate))).doesNotThrowAnyException();
            } else {
                assertThatCode(() -> LegalEditorialReadinessResult.error(
                        List.of(candidate))).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void ordersDeduplicatesCapsAndDefensivelyCopiesAllFindings() {
        List<LegalManifestIssue> candidates = new ArrayList<>();
        for (int index = LegalManifestLimits.MAX_EXPOSED_ISSUES + 1; index >= 0; index--) {
            candidates.add(issue(
                    LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                    "database/state-" + "%03d".formatted(index)));
        }
        candidates.add(candidates.getFirst());

        LegalEditorialReadinessResult result = LegalEditorialReadinessResult.notReady(
                observation(Optional.of(PUBLICATION_UUID)),
                candidates);
        candidates.clear();

        assertThat(result.issues()).hasSize(LegalManifestLimits.MAX_EXPOSED_ISSUES);
        assertThat(result.omittedIssueCount()).isEqualTo(2);
        assertThat(result.issues().getFirst().location()).isEqualTo("database/state-000");
        assertThat(result.issues().getLast().location()).isEqualTo("database/state-199");
        assertThatThrownBy(() -> result.issues().add(issue(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/late")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesEveryCandidateBeforeCappingTheExposedIssues() {
        List<LegalManifestIssue> blocked = new ArrayList<>();
        List<LegalManifestIssue> errors = new ArrayList<>();
        for (int index = 0; index <= LegalManifestLimits.MAX_EXPOSED_ISSUES; index++) {
            blocked.add(issue(
                    LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                    "database/blocked-" + index));
            errors.add(issue(
                    LegalManifestIssueCode.SCHEMA_DRIFT,
                    "database/error-" + index));
        }
        blocked.add(issue(
                LegalManifestIssueCode.DB_PERSISTED_CONFLICT,
                "database/foreign-after-cap"));
        errors.add(issue(
                LegalManifestIssueCode.CURRENT_STATE_MISMATCH,
                "database/wrong-severity-after-cap"));

        assertThatThrownBy(() -> LegalEditorialReadinessResult.notReady(
                observation(Optional.of(PUBLICATION_UUID)),
                blocked))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegalEditorialReadinessResult.error(errors))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void observationRejectsNullsInvalidFingerprintsTimestampPrecisionAndNegativeCounts() {
        assertThatThrownBy(() -> observation(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalEditorialReadinessObservation(
                Optional.of(PUBLICATION_UUID),
                null,
                FINGERPRINT,
                11, 6, 22, 12, 11, 8, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalEditorialReadinessObservation(
                Optional.of(PUBLICATION_UUID),
                OBSERVED_AT,
                null,
                11, 6, 22, 12, 11, 8, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalEditorialReadinessObservation(
                Optional.of(PUBLICATION_UUID),
                Instant.parse("2026-08-28T12:30:00.123456789Z"),
                FINGERPRINT,
                11, 6, 22, 12, 11, 8, 0))
                .isInstanceOf(IllegalArgumentException.class);

        for (String invalid : List.of(
                "a".repeat(64),
                "sha256:" + "A".repeat(64),
                "sha256:" + "a".repeat(63),
                "sha256:" + "a".repeat(65),
                " " + FINGERPRINT,
                FINGERPRINT + " ")) {
            assertThatThrownBy(() -> new LegalEditorialReadinessObservation(
                    Optional.of(PUBLICATION_UUID),
                    OBSERVED_AT,
                    invalid,
                    11, 6, 22, 12, 11, 8, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        for (int index = 0; index < 7; index++) {
            int[] counts = {11, 6, 22, 12, 11, 8, 0};
            counts[index] = -1;
            assertThatThrownBy(() -> new LegalEditorialReadinessObservation(
                    Optional.of(PUBLICATION_UUID),
                    OBSERVED_AT,
                    FINGERPRINT,
                    counts[0], counts[1], counts[2], counts[3],
                    counts[4], counts[5], counts[6]))
                    .as("negative count at index %s", index)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThatCode(() -> new LegalEditorialReadinessObservation(
                Optional.empty(),
                OBSERVED_AT,
                "sha256:" + "0".repeat(64),
                0, 0, 0, 0, 0, 0, 0)).doesNotThrowAnyException();
    }

    private static LegalEditorialReadinessObservation observation(
            Optional<UUID> publicationUuid) {
        return new LegalEditorialReadinessObservation(
                publicationUuid,
                OBSERVED_AT,
                FINGERPRINT,
                11,
                6,
                22,
                12,
                11,
                8,
                0);
    }

    private static void assertPackagePrivateStaticFactory(
            String name,
            Class<?>... parameterTypes) throws NoSuchMethodException {
        var method = LegalEditorialReadinessResult.class.getDeclaredMethod(name, parameterTypes);
        assertThat(method.getReturnType()).isEqualTo(LegalEditorialReadinessResult.class);
        assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
        assertThat(Modifier.isProtected(method.getModifiers())).isFalse();
        assertThat(Modifier.isPrivate(method.getModifiers())).isFalse();
    }

    private static void assertInvalidMatrix(
            Constructor<LegalEditorialReadinessResult> constructor,
            LegalEditorialReadiness readiness,
            LegalEditorialReadinessObservation observation,
            List<LegalManifestIssue> issues,
            int omittedIssueCount) {
        assertThatThrownBy(() -> constructor.newInstance(
                readiness,
                observation,
                issues,
                omittedIssueCount))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }

    private static ExpectedReason reason(
            LegalManifestIssueCode code,
            LegalManifestStatus severity,
            String message) {
        return new ExpectedReason(code, severity, message);
    }

    private record ExpectedReason(
            LegalManifestIssueCode code,
            LegalManifestStatus severity,
            String message
    ) { }
}
