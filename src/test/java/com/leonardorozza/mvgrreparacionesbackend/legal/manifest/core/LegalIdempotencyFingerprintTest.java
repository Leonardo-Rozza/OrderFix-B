package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoOperacionIdempotenteLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalIdempotencyFingerprintTest {
    private static final String KEY = "64f89458-294c-4df5-a88d-833a1f1abcde";
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String STATEMENT = "b".repeat(64);
    private static final String DOCUMENT = "c".repeat(64);
    private static final UUID REQUIREMENT_ID = UUID.fromString("ffffffff-ffff-1fff-8fff-ffffffffffff");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-1000-8000-000000000001");
    private static final long USER_ID = 9_007_199_254_740_993L;

    @Test
    void matchesFixedIndependentPythonHmacVectorIncludingIdBeyondJavascriptIntegerRange() {
        var fingerprint = derive(auth(List.of(acceptance())));
        assertThat(fingerprint.operation()).isEqualTo(TipoOperacionIdempotenteLegal.ACEPTACION_LEGAL);
        assertThat(fingerprint.routeTemplate()).isEqualTo("/api/aceptaciones-legales");
        assertThat(fingerprint.scopeHmac())
                .isEqualTo("15bc563d93dac2e774e224d44ddd55e485200b4b20e4b381e3f2cf18dc1ca459");
        assertThat(fingerprint.idempotencyKeyHmac())
                .isEqualTo("359709afc79c67465b45f89d56025ddd8d6b13e26333ff03a9af4044fd7a4eaa");
        assertThat(fingerprint.fingerprintHmac())
                .isEqualTo("62f5a0cdee180056fdddedf6a14e7e4ea019c24fdaa4422cb77d94ce8129e82b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Normal", "  exacto  ", "é", "e\u0301", "中😀", "\u2028\u2029",
            "\"\\/\b\t\n\f\r\u0000\u000f\u001f"})
    void matchesIndependentJcsAndMacForRegistrationWithEveryUnicodeEscapeCategory(String name)
            throws Exception {
        String actualName = "T" + name;
        var registration = new LegalAcceptanceCommand.Registration(
                actualName, null, "A" + name, "Case@example.test", "pw" + name + "123456");
        var command = LegalAcceptanceCommandValidator.registration(registration, REVISION, List.of(acceptance()));
        var result = derive(command);
        Map<String, Object> business = new LinkedHashMap<>();
        // Intentionally non-canonical property order and whitespace; the independent JCS sorts them.
        business.put("telefonoTaller", null);
        business.put("password", registration.password());
        business.put("nombreTaller", registration.nombreTaller());
        business.put("requiredSetRevision", REVISION);
        business.put("email", registration.email());
        business.put("nombreAdmin", registration.nombreAdmin());
        business.put("aceptacionesLegales", List.of(Map.of(
                "tipoActo", "LECTURA", "requisitoVersionId", REQUIREMENT_ID.toString(),
                "documentos", List.of(Map.of("sha256", DOCUMENT,
                        "documentoVersionId", DOCUMENT_ID.toString())),
                "confirmado", false, "afirmacionSha256", STATEMENT)));
        assertThat(result.scopeHmac()).isEqualTo(independentMac(List.of(
                "ordenfix:legal-idempotency:scope:v1", "POST", "/api/auth/register",
                Map.of("kind", "REGISTRATION"))));
        assertThat(result.fingerprintHmac()).isEqualTo(independentMac(List.of(
                "ordenfix:legal-idempotency:fingerprint:v1", "POST", "/api/auth/register",
                Map.of("kind", "REGISTRATION"), business)));
        assertThat(result.idempotencyKeyHmac()).isEqualTo(independentMac(List.of(
                "ordenfix:legal-idempotency:key:v1", KEY)));
    }

    @Test
    void authenticatesEmptyListsAndFalseConfirmationWithoutInventingSemanticSuccess() {
        var empty = derive(auth(List.of()));
        var one = derive(auth(List.of(acceptance())));
        var noDocuments = derive(auth(List.of(new LegalAcceptanceCommand.Acceptance(
                REQUIREMENT_ID, TipoActoLegal.LECTURA, STATEMENT, List.of(), false))));
        assertThat(List.of(empty.fingerprintHmac(), one.fingerprintHmac(), noDocuments.fingerprintHmac()))
                .doesNotHaveDuplicates();
    }

    @Test
    void preservesDuplicateMultiplicityWhilePermutationsOfFullPayloadHaveTheSameFingerprint() {
        var document = new LegalAcceptanceCommand.Document(DOCUMENT_ID, DOCUMENT);
        var changedDocument = new LegalAcceptanceCommand.Document(DOCUMENT_ID, "d".repeat(64));
        var first = new LegalAcceptanceCommand.Acceptance(REQUIREMENT_ID, TipoActoLegal.LECTURA,
                STATEMENT, List.of(document, changedDocument, document), false);
        var second = new LegalAcceptanceCommand.Acceptance(REQUIREMENT_ID, TipoActoLegal.ACEPTACION,
                STATEMENT, List.of(changedDocument, document, document), true);
        var forward = auth(List.of(first, second, first));
        var permutedFirst = new LegalAcceptanceCommand.Acceptance(REQUIREMENT_ID, TipoActoLegal.LECTURA,
                STATEMENT, List.of(changedDocument, document, document), false);
        var backward = auth(List.of(permutedFirst, permutedFirst, second));
        assertThat(derive(forward)).isEqualTo(derive(backward));
        assertThat(forward.acceptances()).hasSize(3);
        assertThat(derive(auth(List.of(first, second))).fingerprintHmac())
                .isNotEqualTo(derive(forward).fingerprintHmac());
    }

    @ParameterizedTest
    @ValueSource(strings = {"revision", "requirement", "act", "statement", "confirmation", "document", "digest"})
    void everyAcceptanceBusinessFieldParticipatesInFingerprint(String field) {
        var original = derive(auth(List.of(acceptance())));
        var document = new LegalAcceptanceCommand.Document(
                field.equals("document") ? UUID.randomUUID() : DOCUMENT_ID,
                field.equals("digest") ? "d".repeat(64) : DOCUMENT);
        var changed = new LegalAcceptanceCommand.Acceptance(
                field.equals("requirement") ? UUID.randomUUID() : REQUIREMENT_ID,
                field.equals("act") ? TipoActoLegal.DECLARACION : TipoActoLegal.LECTURA,
                field.equals("statement") ? "e".repeat(64) : STATEMENT,
                List.of(document), field.equals("confirmation"));
        var command = LegalAcceptanceCommandValidator.authenticated(actor(),
                field.equals("revision") ? "sha256:" + "f".repeat(64) : REVISION, List.of(changed));
        var result = derive(command);
        assertThat(result.fingerprintHmac()).isNotEqualTo(original.fingerprintHmac());
        assertThat(result.scopeHmac()).isEqualTo(original.scopeHmac());
        assertThat(result.idempotencyKeyHmac()).isEqualTo(original.idempotencyKeyHmac());
    }

    @ParameterizedTest
    @ValueSource(strings = {"workshop", "phone", "admin", "email", "password"})
    void everyRegistrationFieldIncludingExactPasswordParticipates(String field) {
        var original = registration("Taller", null, "Admin", "Case@example.test", "secret123");
        var changed = registration(field.equals("workshop") ? "Taller " : "Taller",
                field.equals("phone") ? "" : null, field.equals("admin") ? "Admin " : "Admin",
                field.equals("email") ? "case@example.test" : "Case@example.test",
                field.equals("password") ? "Secret123" : "secret123");
        assertThat(derive(changed).fingerprintHmac()).isNotEqualTo(derive(original).fingerprintHmac());
        assertThat(derive(changed).scopeHmac()).isEqualTo(derive(original).scopeHmac());
    }

    @Test
    void doesNotNormalizeUnicodeOrCoerceNullEmptyAndBlankOptionalValues() {
        var nfc = registration("Café", null, "Admin", "a@b.test", "secret123");
        var nfd = registration("Cafe\u0301", null, "Admin", "a@b.test", "secret123");
        var empty = registration("Café", "", "Admin", "a@b.test", "secret123");
        var blank = registration("Café", " ", "Admin", "a@b.test", "secret123");
        assertThat(List.of(derive(nfc).fingerprintHmac(), derive(nfd).fingerprintHmac(),
                derive(empty).fingerprintHmac(), derive(blank).fingerprintHmac())).doesNotHaveDuplicates();
    }

    @Test
    void scopesByUserAndRouteWhileActorStateAndTenantRequireLaterDurableRevalidation() throws Exception {
        var original = derive(auth(List.of(acceptance())));
        var differentUser = derive(LegalAcceptanceCommandValidator.authenticated(
                new LegalActorSnapshot(Long.MAX_VALUE, 8, UserRole.ADMIN, 0, true, true),
                REVISION, List.of(acceptance())));
        assertThat(differentUser.scopeHmac()).isEqualTo(independentMac(List.of(
                "ordenfix:legal-idempotency:scope:v1", "POST", "/api/aceptaciones-legales",
                Map.of("userId", Long.toString(Long.MAX_VALUE), "kind", "AUTHENTICATED"))));
        assertThat(differentUser.scopeHmac()).isNotEqualTo(original.scopeHmac());
        assertThat(differentUser.fingerprintHmac()).isNotEqualTo(original.fingerprintHmac());
        var changedActorState = derive(LegalAcceptanceCommandValidator.authenticated(
                new LegalActorSnapshot(USER_ID, 77, UserRole.USER, 999, true, true),
                REVISION, List.of(acceptance())));
        assertThat(changedActorState).isEqualTo(original);
        var registration = derive(registration("Taller", null, "Admin", "a@b.test", "secret123"));
        assertThat(registration.scopeHmac()).isNotEqualTo(original.scopeHmac());
        assertThat(registration.operation()).isEqualTo(TipoOperacionIdempotenteLegal.REGISTRO);
        assertThat(registration.routeTemplate()).isEqualTo("/api/auth/register");
    }

    @Test
    void keySecretAndVersionHaveSeparateResponsibilities() {
        var command = auth(List.of(acceptance()));
        var original = derive(command);
        var anotherKey = LegalIdempotencyFingerprint.derive(command,
                "0a7c6d9b-bd14-4fc5-b828-0ea889dc3bb8", 7, secret());
        assertThat(anotherKey.idempotencyKeyHmac()).isNotEqualTo(original.idempotencyKeyHmac());
        assertThat(anotherKey.scopeHmac()).isEqualTo(original.scopeHmac());
        assertThat(anotherKey.fingerprintHmac()).isEqualTo(original.fingerprintHmac());
        byte[] otherSecret = secret();
        otherSecret[0] ^= 1;
        var anotherSecret = LegalIdempotencyFingerprint.derive(command, KEY, 8, otherSecret);
        assertThat(anotherSecret.scopeHmac()).isNotEqualTo(original.scopeHmac());
        assertThat(anotherSecret.idempotencyKeyHmac()).isNotEqualTo(original.idempotencyKeyHmac());
        assertThat(anotherSecret.fingerprintHmac()).isNotEqualTo(original.fingerprintHmac());
        var versionLabel = LegalIdempotencyFingerprint.derive(command, KEY, 8, secret());
        assertThat(versionLabel.fingerprintHmac()).isEqualTo(original.fingerprintHmac());
        assertThat(versionLabel.keyVersion()).isEqualTo(8);
        assertThat(List.of(original.scopeHmac(), original.idempotencyKeyHmac(), original.fingerprintHmac()))
                .doesNotHaveDuplicates();
    }

    @Test
    void streamsTheMaximumTypedAcceptanceGraphAcrossMultipleMacBufferFlushes() throws Exception {
        List<LegalAcceptanceCommand.Acceptance> input = new ArrayList<>();
        List<LegalAcceptanceCommand.Document> documents = new ArrayList<>();
        for (int i = 0; i < 16; i++) documents.add(new LegalAcceptanceCommand.Document(new UUID(0, i), DOCUMENT));
        for (int i = 0; i < 2048; i++) input.add(new LegalAcceptanceCommand.Acceptance(
                new UUID(0, i), TipoActoLegal.ACEPTACION, STATEMENT, documents, true));
        var forward = auth(input);
        Collections.reverse(input);
        Collections.reverse(documents);
        var reverse = auth(input);
        var result = derive(forward);
        assertThat(derive(reverse)).isEqualTo(result);
        List<Map<String, Object>> expected = new ArrayList<>();
        for (var acceptance : forward.acceptances()) expected.add(Map.of(
                "requisitoVersionId", acceptance.requisitoVersionId().toString(),
                "tipoActo", "ACEPTACION", "afirmacionSha256", STATEMENT, "confirmado", true,
                "documentos", acceptance.documentos().stream().map(document -> Map.of(
                        "documentoVersionId", document.documentoVersionId().toString(), "sha256", DOCUMENT)).toList()));
        assertThat(result.fingerprintHmac()).isEqualTo(independentMac(List.of(
                "ordenfix:legal-idempotency:fingerprint:v1", "POST", "/api/aceptaciones-legales",
                Map.of("kind", "AUTHENTICATED", "userId", Long.toString(USER_ID)),
                Map.of("aceptacionesLegales", expected, "requiredSetRevision", REVISION))));
    }

    @Test
    void ownsNoCallerSecretAndNeverIncludesMacsOrSecretsInDiagnostic() {
        byte[] secret = secret();
        byte[] copy = secret.clone();
        var value = LegalIdempotencyFingerprint.derive(auth(List.of()), KEY, 7, secret);
        assertThat(secret).containsExactly(copy);
        Arrays.fill(secret, (byte) 0);
        assertThat(value).isEqualTo(LegalIdempotencyFingerprint.derive(auth(List.of()), KEY, 7, copy));
        assertThat(value.toString()).doesNotContain(KEY, value.scopeHmac(), value.idempotencyKeyHmac(),
                value.fingerprintHmac(), HexFormat.of().formatHex(copy));
        assertThatThrownBy(() -> LegalIdempotencyFingerprint.derive(auth(List.of()),
                "sensitive-bad-key", 1, copy)).hasMessageNotContaining("sensitive-bad-key");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 16, 31, 33, 64})
    void rejectsWrongSecretLength(int size) {
        assertThatThrownBy(() -> LegalIdempotencyFingerprint.derive(auth(List.of()), KEY, 1, new byte[size]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCandidateShapeAndOnlyMatchesCompleteCanonicalFingerprints() {
        var result = derive(auth(List.of()));
        assertThat(result.matchesFingerprint(result.fingerprintHmac())).isTrue();
        assertThat(result.matchesFingerprint(null)).isFalse();
        assertThat(result.matchesFingerprint("a".repeat(63))).isFalse();
        assertThat(result.matchesFingerprint(result.fingerprintHmac().toUpperCase())).isFalse();
        assertThat(result.matchesFingerprint("0".repeat(64))).isFalse();
        assertThatThrownBy(() -> new LegalIdempotencyFingerprint(0, result.operation(), result.routeTemplate(),
                result.scopeHmac(), result.idempotencyKeyHmac(), result.fingerprintHmac()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalIdempotencyFingerprint(1, result.operation(), "/api/auth/register",
                result.scopeHmac(), result.idempotencyKeyHmac(), result.fingerprintHmac()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalIdempotencyFingerprint(1, result.operation(), result.routeTemplate(),
                "secret-value", result.idempotencyKeyHmac(), result.fingerprintHmac()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret-value");
    }

    private static LegalActorSnapshot actor() {
        return new LegalActorSnapshot(USER_ID, 8, UserRole.ADMIN, 0, true, true);
    }

    private static LegalAcceptanceCommand.Acceptance acceptance() {
        return new LegalAcceptanceCommand.Acceptance(REQUIREMENT_ID, TipoActoLegal.LECTURA, STATEMENT,
                List.of(new LegalAcceptanceCommand.Document(DOCUMENT_ID, DOCUMENT)), false);
    }

    private static LegalAcceptanceCommand auth(List<LegalAcceptanceCommand.Acceptance> acceptances) {
        return LegalAcceptanceCommandValidator.authenticated(actor(), REVISION, acceptances);
    }

    private static LegalAcceptanceCommand registration(String name, String phone, String admin,
                                                       String email, String password) {
        return LegalAcceptanceCommandValidator.registration(new LegalAcceptanceCommand.Registration(
                name, phone, admin, email, password), REVISION, List.of(acceptance()));
    }

    private static LegalIdempotencyFingerprint derive(LegalAcceptanceCommand command) {
        return LegalIdempotencyFingerprint.derive(command, KEY, 7, secret());
    }

    private static byte[] secret() {
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) result[i] = (byte) i;
        return result;
    }

    private static String independentMac(Object json) throws Exception {
        String serialized = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json);
        byte[] canonical = new JsonCanonicalizer(serialized).getEncodedUTF8();
        Mac reference = Mac.getInstance("HmacSHA256");
        reference.init(new SecretKeySpec(secret(), "HmacSHA256"));
        return HexFormat.of().formatHex(reference.doFinal(canonical));
    }
}
