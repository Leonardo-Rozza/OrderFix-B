package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Registration;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalAcceptanceCommandValidatorTest {
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final UUID REQUIREMENT = new UUID(0, 101);
    private static final UUID DOCUMENT = new UUID(0, 201);

    @ParameterizedTest @EnumSource(UserRole.class)
    void enabledTypedActorsOfBothRolesAreAcceptedWithoutClaimingDatabaseAuthentication(UserRole role) {
        var actor = new LegalActorSnapshot(51, 72, role, 7, true, true);
        assertThat(LegalAcceptanceCommandValidator.authenticated(actor, REVISION, List.of()).actor()).isSameAs(actor);
    }

    @Test void inactiveActorInactiveWorkshopAndMissingActorFailClosed() {
        assertInvalid(() -> LegalAcceptanceCommandValidator.authenticated(null, REVISION, List.of()));
        assertInvalid(() -> LegalAcceptanceCommandValidator.authenticated(new LegalActorSnapshot(51, 72, UserRole.USER, 7, false, true), REVISION, List.of()));
        assertInvalid(() -> LegalAcceptanceCommandValidator.authenticated(new LegalActorSnapshot(51, 72, UserRole.USER, 7, true, false), REVISION, List.of()));
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "sha256:", "SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            " sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"})
    void revisionRequiresTheExactCanonicalPrefixAndLowercaseDigest(String revision) {
        assertInvalid(() -> LegalAcceptanceCommandValidator.authenticated(actor(), revision, List.of()));
        assertInvalid(() -> LegalAcceptanceCommandValidator.registration(registration(), revision, List.of()));
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "a", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    void everyStatementAndDocumentDigestHasStrictShape(String digest) {
        assertInvalid(() -> authenticated(List.of(new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, digest, List.of(), true))));
        assertInvalid(() -> authenticated(List.of(acceptance(List.of(new Document(DOCUMENT, digest))))));
    }

    @Test void nullListsElementsIdsAndActTypeAreAllRejectedByTheValidatorWithSafeErrors() {
        assertInvalid(() -> authenticated(null));
        assertInvalid(() -> authenticated(Arrays.asList((Acceptance) null)));
        assertInvalid(() -> authenticated(List.of(new Acceptance(null, TipoActoLegal.ACEPTACION, A, List.of(), true))));
        assertInvalid(() -> authenticated(List.of(new Acceptance(REQUIREMENT, null, A, List.of(), true))));
        assertInvalid(() -> authenticated(List.of(acceptance(null))));
        assertInvalid(() -> authenticated(List.of(acceptance(Arrays.asList((Document) null)))));
        assertInvalid(() -> authenticated(List.of(acceptance(List.of(new Document(null, A))))));
    }

    @Test void exactMaximumCardinalitiesAreAcceptedAndOneExtraIsRejected() {
        List<Document> documents = Collections.nCopies(16, new Document(DOCUMENT, A));
        Acceptance acceptance = acceptance(documents);
        var maximum = authenticated(Collections.nCopies(2_048, acceptance));
        assertThat(maximum.acceptances()).hasSize(2_048);
        assertThat(maximum.acceptances().getFirst().documentos()).hasSize(16);
        assertInvalid(() -> authenticated(Collections.nCopies(2_049, acceptance)));
        assertInvalid(() -> authenticated(List.of(acceptance(Collections.nCopies(17, new Document(DOCUMENT, A))))));
    }

    @Test void rawAcceptanceRejectsAnOversizedListBeforeTryingToCopyItsMembers() {
        List<Document> oversized = new AbstractList<>() {
            @Override public int size() { return 17; }
            @Override public Document get(int index) { throw new AssertionError("Oversized input must not be read or copied"); }
        };
        assertInvalid(() -> acceptance(oversized));
    }

    @Test void emptyActsEmptyDocumentsDuplicatesAndFalseConfirmationRemainForLaterSemanticPriority() {
        assertThat(authenticated(List.of()).acceptances()).isEmpty();
        Acceptance empty = new Acceptance(REQUIREMENT, TipoActoLegal.LECTURA, A, List.of(), false);
        Acceptance duplicateDocuments = new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, B,
                List.of(new Document(DOCUMENT, A), new Document(DOCUMENT, A)), false);
        var command = authenticated(List.of(empty, duplicateDocuments, empty));
        assertThat(command.acceptances()).hasSize(3);
        assertThat(command.acceptances().stream().filter(empty::equals)).hasSize(2);
        assertThat(command.acceptances().stream().filter(duplicateDocuments::equals)).hasSize(1);
        assertThat(command.acceptances().getFirst().documentos()).hasSize(2);
        assertThat(command.acceptances()).allMatch(acceptance -> !acceptance.confirmado());
    }

    @Test void typedEvidenceUuidsMayUseAnyVersionAndVariant() {
        List<UUID> ids = List.of(UUID.fromString("00000000-0000-0000-0000-000000000000"),
                UUID.fromString("77777777-7777-7777-7777-777777777777"), UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        for (UUID id : ids) {
            var acceptance = new Acceptance(id, TipoActoLegal.DECLARACION, A, List.of(new Document(id, B)), true);
            assertThat(authenticated(List.of(acceptance)).acceptances()).containsExactly(acceptance);
        }
    }

    @Test void uuidOrderIsUnsignedCanonicalTextOrderForBothHighAndLowWords() {
        List<UUID> expected = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-7fff-ffffffffffff"),
                UUID.fromString("00000000-0000-0000-8000-000000000000"),
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        List<Document> documents = expected.reversed().stream().map(id -> new Document(id, A)).toList();
        List<Acceptance> source = expected.reversed().stream().map(id -> new Acceptance(id, TipoActoLegal.ACEPTACION, A, documents, true)).toList();
        var command = authenticated(source);
        assertThat(command.acceptances().stream().map(Acceptance::requisitoVersionId).toList()).isEqualTo(expected);
        for (Acceptance acceptance : command.acceptances()) {
            assertThat(acceptance.documentos().stream().map(Document::documentoVersionId).toList()).isEqualTo(expected);
        }
    }

    @Test void duplicateIdTieBreakIncludesAllContentAndKeepsEveryOccurrence() {
        var docA = new Document(DOCUMENT, A); var docB = new Document(DOCUMENT, B);
        var laterDoc = new Document(new UUID(0, 202), A);
        List<Acceptance> expected = List.of(
                new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, A, List.of(), false),
                new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, A, List.of(docA), false),
                new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, A, List.of(docA), false),
                new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, A, List.of(docA), true),
                new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, A, List.of(docA, laterDoc), false),
                new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, A, List.of(docB), false),
                new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, B, List.of(), false),
                new Acceptance(REQUIREMENT, TipoActoLegal.DECLARACION, A, List.of(), false),
                new Acceptance(REQUIREMENT, TipoActoLegal.LECTURA, A, List.of(), false));
        for (int seed = 0; seed < 30; seed++) {
            List<Acceptance> input = new ArrayList<>();
            for (Acceptance acceptance : expected) {
                List<Document> documents = new ArrayList<>(acceptance.documentos());
                Collections.shuffle(documents, new Random(seed));
                input.add(new Acceptance(acceptance.requisitoVersionId(), acceptance.tipoActo(), acceptance.afirmacionSha256(), documents, acceptance.confirmado()));
            }
            Collections.shuffle(input, new Random(seed));
            assertThat(authenticated(input).acceptances()).as("permutation %s", seed).isEqualTo(expected);
        }
    }

    @Test void documentDuplicateIdTieBreakUsesDigestAndPreservesDuplicates() {
        var docA = new Document(DOCUMENT, A); var docB = new Document(DOCUMENT, B);
        var command = authenticated(List.of(acceptance(List.of(docB, docA, docB, docA))));
        assertThat(command.acceptances().getFirst().documentos()).containsExactly(docA, docA, docB, docB);
    }

    @Test void registrationKeepsEveryExactValueIncludingCaseWhitespaceNormalizationAndNullVersusEmptyPhone() {
        var decomposed = new Registration(" Taller e\u0301 ", null, " ADMIN ", "Case+Tag@Example.INVALID ", " secret-value ");
        var composed = new Registration(" Taller é ", "", " ADMIN ", "case+tag@example.invalid ", "secret-value");
        assertThat(LegalAcceptanceCommandValidator.registration(decomposed, REVISION, List.of()).registration()).isEqualTo(decomposed);
        assertThat(LegalAcceptanceCommandValidator.registration(composed, REVISION, List.of()).registration()).isEqualTo(composed);
        assertThat(decomposed).isNotEqualTo(composed);
        assertThatCode(() -> LegalAcceptanceCommandValidator.registration(new Registration("Taller", "   ", "Admin", "not-an-email", "secret"), REVISION, List.of()))
                .as("The future HTTP @Email check remains authoritative").doesNotThrowAnyException();
    }

    @Test void registrationUsesExistingDtoLengthLimitsIncludingUtf16Units() {
        Registration maximum = new Registration("a".repeat(120), "b".repeat(20), "c".repeat(50), "e".repeat(121), "d".repeat(100));
        assertThatCode(() -> LegalAcceptanceCommandValidator.registration(maximum, REVISION, List.of())).doesNotThrowAnyException();
        assertInvalid(() -> register(new Registration("a".repeat(121), null, "Admin", "email", "secret")));
        assertInvalid(() -> register(new Registration("Taller", "b".repeat(21), "Admin", "email", "secret")));
        assertInvalid(() -> register(new Registration("Taller", null, "c".repeat(51), "email", "secret")));
        assertInvalid(() -> register(new Registration("Taller", null, "Admin", "email", "d".repeat(101))));
        assertInvalid(() -> register(new Registration("Taller", null, "Admin", "email", "12345")));
        assertThatCode(() -> register(new Registration("😀".repeat(60), null, "😀".repeat(25), "email", "😀".repeat(3)))).doesNotThrowAnyException();
        assertInvalid(() -> register(new Registration("😀".repeat(61), null, "Admin", "email", "secret")));
        assertInvalid(() -> register(new Registration("Taller", null, "😀".repeat(26), "email", "secret")));
        assertInvalid(() -> register(new Registration("Taller", null, "Admin", "email", "😀".repeat(2))));
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "\t", "\n", "      "})
    void requiredRegistrationStringsMustNotBeMissingOrBlank(String value) {
        assertInvalid(() -> register(new Registration(value, null, "Admin", "email", "secret")));
        assertInvalid(() -> register(new Registration("Taller", null, value, "email", "secret")));
        assertInvalid(() -> register(new Registration("Taller", null, "Admin", value, "secret")));
        assertInvalid(() -> register(new Registration("Taller", null, "Admin", "email", value)));
    }

    @Test void missingRegistrationIsRejected() { assertInvalid(() -> register(null)); }

    @Test void emailHasADefensiveUtf8ByteLimitWithoutAddingTheDatabaseVarcharLimitToTheDto() {
        assertThatCode(() -> register(new Registration("Taller", null, "Admin", "a".repeat(1_048_576), "secret"))).doesNotThrowAnyException();
        assertInvalid(() -> register(new Registration("Taller", null, "Admin", "a".repeat(1_048_577), "secret")));
        assertThatCode(() -> register(new Registration("Taller", null, "Admin", "é".repeat(524_288), "secret"))).doesNotThrowAnyException();
        assertInvalid(() -> register(new Registration("Taller", null, "Admin", "é".repeat(524_289), "secret")));
    }

    @Test void malformedSurrogatesFailInEveryInputStringWithoutReplacement() {
        for (String malformed : List.of("\ud800", "\udc00", "\ud800x", "\udc00\ud800", "x\ud800x")) {
            assertInvalid(() -> register(new Registration("Taller" + malformed, null, "Admin", "email", "secret")));
            assertInvalid(() -> register(new Registration("Taller", malformed, "Admin", "email", "secret")));
            assertInvalid(() -> register(new Registration("Taller", null, "Admin" + malformed, "email", "secret")));
            assertInvalid(() -> register(new Registration("Taller", null, "Admin", "email" + malformed, "secret")));
            assertInvalid(() -> register(new Registration("Taller", null, "Admin", "email", "secret" + malformed)));
            assertInvalid(() -> LegalAcceptanceCommandValidator.authenticated(actor(), "sha256:" + "a".repeat(63) + malformed, List.of()));
            assertInvalid(() -> authenticated(List.of(new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, "a".repeat(63) + malformed, List.of(), true))));
            assertInvalid(() -> authenticated(List.of(acceptance(List.of(new Document(DOCUMENT, "a".repeat(63) + malformed))))));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"64f89458-294c-4df5-888d-833a1f1abcde", "64f89458-294c-4df5-988d-833a1f1abcde",
            "64f89458-294c-4df5-a88d-833a1f1abcde", "64f89458-294c-4df5-b88d-833a1f1abcde"})
    void canonicalV4KeysAcceptAllFourRfcVariantLeadingDigits(String source) {
        UUID key = LegalAcceptanceCommandValidator.requireIdempotencyKey(source);
        assertThat(key.toString()).isEqualTo(source); assertThat(key.version()).isEqualTo(4); assertThat(key.variant()).isEqualTo(2);
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "64F89458-294C-4DF5-A88D-833A1F1ABCDE", "64f89458-294c-1df5-a88d-833a1f1abcde",
            "64f89458-294c-7df5-a88d-833a1f1abcde", "64f89458-294c-4df5-788d-833a1f1abcde", "64f89458-294c-4df5-c88d-833a1f1abcde",
            "64f89458-294c-4df5-f88d-833a1f1abcde", "64f89458294c4df5a88d833a1f1abcde", " 64f89458-294c-4df5-a88d-833a1f1abcde",
            "64f89458-294c-4df5-a88d-833a1f1abcde\n", "{64f89458-294c-4df5-a88d-833a1f1abcde}", "urn:uuid:64f89458-294c-4df5-a88d-833a1f1abcde",
            "1-2-4000-8000-3", "00000000-0000-0000-0000-000000000000"})
    void keysRejectNoncanonicalCaseVersionVariantLengthAndAlternativeUuidForms(String source) {
        assertInvalid(() -> LegalAcceptanceCommandValidator.requireIdempotencyKey(source));
    }

    @Test void failureMessageCauseAndStackTraceNeverExposeTheRejectedInput() {
        String secret = "private-password-key-that-must-never-appear";
        Throwable failure = catchThrowable(() -> LegalAcceptanceCommandValidator.requireIdempotencyKey(secret));
        assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage("El comando legal no tiene un formato válido.").hasNoCause();
        var text = new StringWriter(); failure.printStackTrace(new PrintWriter(text));
        assertThat(text.toString()).doesNotContain(secret);
        Throwable registration = catchThrowable(() -> register(new Registration(secret, null, secret, secret, "bad")));
        assertThat(registration).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThat(registration.toString()).doesNotContain(secret, "bad");
    }

    private static LegalActorSnapshot actor() { return new LegalActorSnapshot(51, 72, UserRole.USER, 7, true, true); }
    private static Registration registration() { return new Registration("Taller", null, "Admin", "admin@example.invalid", "secret"); }
    private static Acceptance acceptance(List<Document> documents) { return new Acceptance(REQUIREMENT, TipoActoLegal.ACEPTACION, A, documents, false); }
    private static LegalAcceptanceCommand authenticated(List<Acceptance> acceptances) { return LegalAcceptanceCommandValidator.authenticated(actor(), REVISION, acceptances); }
    private static LegalAcceptanceCommand register(Registration registration) { return LegalAcceptanceCommandValidator.registration(registration, REVISION, List.of()); }
    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(IllegalArgumentException.class).hasMessage("El comando legal no tiene un formato válido.").hasNoCause();
    }
}
