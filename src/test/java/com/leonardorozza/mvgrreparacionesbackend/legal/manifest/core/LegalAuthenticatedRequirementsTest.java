package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Decision;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Membership;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Satisfaction;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Scope;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Snapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LegalAuthenticatedRequirementsTest {
    private static final ContextoLegal USE = ContextoLegal.USO_CONTINUADO;
    private static final ContextoLegal CLOSE = ContextoLegal.CIERRE_CUENTA;
    private static final OffsetDateTime DATE = OffsetDateTime.parse("2026-09-06T03:00:00.123456Z");

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void derivesAudienceFromThePersistedRoleWithoutChangingWireRoles(UserRole role) {
        LegalActorSnapshot actor = actor(role);
        actor.requireEnabled();
        assertEquals(role.toAudienciaLegal(), actor.audience());
        assertEquals(role, actor.role());
        assertEquals(role == UserRole.ADMIN ? AudienciaLegal.ADMIN_TITULAR : AudienciaLegal.USER,
                snapshot(actor, scope(USE, 1, requirement(1, USE, true))).applicableScopes().audience());
    }

    @ParameterizedTest
    @MethodSource("invalidActorNumbers")
    void rejectsInvalidActorIdsAndTokenVersions(long user, long workshop, long tokenVersion) {
        assertThrows(IllegalArgumentException.class,
                () -> new LegalActorSnapshot(user, workshop, UserRole.USER, tokenVersion, true, true));
    }

    static Stream<Arguments> invalidActorNumbers() {
        return Stream.of(Arguments.of(0L, 1L, 0L), Arguments.of(-1L, 1L, 0L),
                Arguments.of(1L, 0L, 0L), Arguments.of(1L, -1L, 0L), Arguments.of(1L, 1L, -1L));
    }

    @Test
    void rejectsMissingRoleAndAcceptsTheZeroTokenVersion() {
        assertThrows(NullPointerException.class, () -> new LegalActorSnapshot(1, 2, null, 0, true, true));
        assertEquals(0, new LegalActorSnapshot(1, 2, UserRole.ADMIN, 0, true, true).tokenVersion());
    }

    @ParameterizedTest
    @MethodSource("disabledActors")
    void aDisabledActorMayBeRepresentedButCannotAccreditARequirementsSnapshot(boolean active, boolean workshop) {
        LegalActorSnapshot disabled = new LegalActorSnapshot(1, 2, UserRole.USER, 0, active, workshop);
        assertThrows(IllegalArgumentException.class, disabled::requireEnabled);
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(disabled, scope(USE, 1, requirement(1, USE, true))));
    }

    static Stream<Arguments> disabledActors() {
        return Stream.of(Arguments.of(false, true), Arguments.of(true, false), Arguments.of(false, false));
    }

    @Test
    void registrationProfileAndForeignAudienceCannotBecomeAnAuthenticatedSnapshot() {
        Scope registration = scope(ContextoLegal.REGISTRO, 1, requirement(1, ContextoLegal.REGISTRO, true));
        LegalApplicableScopeSet registrationScopes = new LegalApplicableScopeResolver().resolve(
                PerfilAgregadoLegal.REGISTRATION, LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR);
        assertThrows(IllegalArgumentException.class,
                () -> new Snapshot(actor(UserRole.ADMIN), registrationScopes, List.of(registration)));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(actor(UserRole.USER),
                applicability(UserRole.ADMIN, USE), List.of(scope(USE, 1, requirement(1, USE, true)))));
    }

    @Test
    void requiresTheCompleteScopeVectorInItsAccreditedOrderWithoutSorting() {
        Scope use = scope(USE, 1, requirement(1, USE, true));
        Scope close = scope(CLOSE, 2, requirement(2, CLOSE, false));
        LegalApplicableScopeSet vector = applicability(UserRole.ADMIN, USE, CLOSE);
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(actor(UserRole.ADMIN), vector, List.of(use)));
        assertThrows(IllegalArgumentException.class,
                () -> new Snapshot(actor(UserRole.ADMIN), vector, List.of(close, use)));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(actor(UserRole.ADMIN), vector,
                List.of(use, new Scope(1, close.projection(), close.memberships()))));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(actor(UserRole.ADMIN), vector,
                List.of(use, new Scope(2, use.projection(), use.memberships()))));
        Snapshot valid = new Snapshot(actor(UserRole.ADMIN), vector, List.of(use, close));
        assertEquals(List.of(use, close), valid.scopes());
        assertEquals(List.of(USE, CLOSE), valid.scopeRevisions().stream().map(it -> it.context()).toList());
    }

    @Test
    void membershipOrdinalsMayHaveGapsButMustMatchRequirementIdentityAndOrder() {
        RequirementProjection first = requirement(1, USE, true);
        RequirementProjection second = requirement(2, USE, false);
        LegalRequiredSetProjection projection = projection(USE, first, second);
        Snapshot valid = snapshot(actor(UserRole.USER), new Scope(1, projection,
                List.of(new Membership(3, "first", first.versionId()), new Membership(17, "second", second.versionId()))));
        assertEquals(List.of(3, 17), valid.scopes().getFirst().memberships().stream()
                .map(Membership::manifestOrdinal).toList());
        for (List<Membership> invalid : List.of(
                List.of(new Membership(3, "first", first.versionId()), new Membership(3, "second", second.versionId())),
                List.of(new Membership(17, "first", first.versionId()), new Membership(3, "second", second.versionId())),
                List.of(new Membership(3, "first", second.versionId()), new Membership(17, "second", first.versionId())),
                List.of(new Membership(3, "same", first.versionId()), new Membership(17, "same", second.versionId())))) {
            assertThrows(IllegalArgumentException.class, () -> snapshot(actor(UserRole.USER), new Scope(1, projection, invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> new Scope(1, projection, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Membership(0, "first", first.versionId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Uppercase", "space key", "/slash", ".starts-with-dot"})
    void membershipKeysFollowTheEditorialContract(String key) {
        assertThrows(IllegalArgumentException.class, () -> new Membership(1, key, id(1)));
    }

    @Test
    void membershipKeyLengthUsesTheManifestHundredCharacterLimit() {
        assertEquals("a".repeat(100), new Membership(1, "a".repeat(100), id(1)).requirementKey());
        assertThrows(IllegalArgumentException.class, () -> new Membership(1, "a".repeat(101), id(1)));
    }

    @Test
    void rejectsRequirementsWithForeignContextOrRepeatedVersionIdentity() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(actor(UserRole.USER),
                scope(USE, 1, requirement(1, CLOSE, true))));
        RequirementProjection first = requirement(1, USE, true);
        LegalRequiredSetProjection duplicate = projection(USE, first, first);
        assertThrows(IllegalArgumentException.class, () -> snapshot(actor(UserRole.USER), new Scope(1, duplicate,
                List.of(new Membership(1, "one", first.versionId()), new Membership(2, "two", first.versionId())))));
    }

    @Test
    void globalRequirementKeysCannotBeReusedAcrossScopes() {
        Scope use = scope(USE, 1, requirement(1, USE, true));
        RequirementProjection close = requirement(2, CLOSE, true);
        Scope repeated = new Scope(2, projection(CLOSE, close),
                List.of(new Membership(1, use.memberships().getFirst().requirementKey(), close.versionId())));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(actor(UserRole.ADMIN),
                applicability(UserRole.ADMIN, USE, CLOSE), List.of(use, repeated)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t", "\uFEFFAcepto", "Acepto\r\n", "Acepto\u0000", "Afirmacio\u0301n", "\uD800"})
    void rejectsNonCanonicalOrInvisibleStatementsEvenWhenTheirDigestIsProvided(String statement) {
        RequirementProjection bad = new RequirementProjection(id(1), USE, TipoActoLegal.ACEPTACION,
                statement, sha(statement), List.of(document(101)), true);
        assertThrows(IllegalArgumentException.class, () -> snapshot(actor(UserRole.USER), scope(USE, 1, bad)));
    }

    @Test
    void validatesStatementDigestsAndRequiredDocumentsBeforeAnyResultCanFilterThemAway() {
        RequirementProjection good = requirement(1, USE, true);
        RequirementProjection badDigest = new RequirementProjection(good.versionId(), USE, good.actType(),
                good.statement(), "0".repeat(64), good.documents(), false);
        assertThrows(IllegalArgumentException.class, () -> snapshot(actor(UserRole.USER), scope(USE, 1, badDigest)));
        RequirementProjection noDocuments = new RequirementProjection(good.versionId(), USE, good.actType(),
                good.statement(), good.statementSha256(), List.of(), false);
        assertThrows(IllegalArgumentException.class, () -> snapshot(actor(UserRole.USER), scope(USE, 1, noDocuments)));
        RequirementProjection repeatedDocument = withDocuments(good, good.documents().getFirst(), good.documents().getFirst());
        assertThrows(IllegalArgumentException.class, () -> snapshot(actor(UserRole.USER), scope(USE, 1, repeatedDocument)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "# Documento\r\n", "\uFEFF# Documento", "# Poli\u0301tica", "# Documento\u0000", "\uD800"})
    void rejectsInvalidMarkdownWithMatchingComputedDigest(String markdown) {
        DocumentProjection good = document(101);
        DocumentProjection bad = new DocumentProjection(good.versionId(), good.type(), good.version(), good.title(),
                markdown, sha(markdown), good.effectiveAt(), good.locale());
        assertThrows(IllegalArgumentException.class, () -> snapshot(actor(UserRole.USER),
                scope(USE, 1, withDocuments(requirement(1, USE, true), bad))));
    }

    @Test
    void checksDocumentMetadataAndDigestWithoutRepairingEither() {
        DocumentProjection good = document(101);
        List<DocumentProjection> invalid = List.of(
                new DocumentProjection(good.versionId(), good.type(), good.version(), good.title(), good.markdown(),
                        "0".repeat(64), DATE, good.locale()),
                new DocumentProjection(good.versionId(), good.type(), " ", good.title(), good.markdown(),
                        good.sha256(), DATE, good.locale()),
                new DocumentProjection(good.versionId(), good.type(), good.version(), " ", good.markdown(),
                        good.sha256(), DATE, good.locale()),
                new DocumentProjection(good.versionId(), good.type(), good.version(), good.title(), good.markdown(),
                        good.sha256(), DATE.plusNanos(1), good.locale()));
        for (DocumentProjection bad : invalid) {
            assertThrows(IllegalArgumentException.class, () -> snapshot(actor(UserRole.USER),
                    scope(USE, 1, withDocuments(requirement(1, USE, true), bad))));
        }
    }

    @Test
    void validatesSharedDocumentIdentityAcrossAllScopesAndAcceptsEquivalentOffsets() {
        DocumentProjection document = document(101);
        Scope use = scope(USE, 1, withDocuments(requirement(1, USE, true), document));
        DocumentProjection retitled = new DocumentProjection(document.versionId(), document.type(), document.version(),
                "Otro título", document.markdown(), document.sha256(), DATE, document.locale());
        Scope closeBad = scope(CLOSE, 2, withDocuments(requirement(2, CLOSE, false), retitled));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(actor(UserRole.ADMIN),
                applicability(UserRole.ADMIN, USE, CLOSE), List.of(use, closeBad)));
        DocumentProjection equivalent = new DocumentProjection(document.versionId(), document.type(), document.version(),
                document.title(), document.markdown(), document.sha256(), DATE.withOffsetSameInstant(ZoneOffset.ofHours(-3)), document.locale());
        Scope close = scope(CLOSE, 2, withDocuments(requirement(2, CLOSE, false), equivalent));
        Snapshot accepted = new Snapshot(actor(UserRole.ADMIN), applicability(UserRole.ADMIN, USE, CLOSE), List.of(use, close));
        assertSame(equivalent, accepted.requirements().getLast().documents().getFirst());
    }

    @Test
    void rejectsMarkdownAndDistinctDocumentOverflowInTheCompleteScope() {
        DocumentProjection tooLarge = new DocumentProjection(id(101), TipoDocumentoLegal.TERMINOS_SERVICIO,
                "v1", "Título", "a".repeat(LegalManifestLimits.MAX_MARKDOWN_BYTES + 1), "0".repeat(64), DATE, LocaleLegal.ES_AR);
        assertThrows(IllegalArgumentException.class, () -> snapshot(actor(UserRole.USER),
                scope(USE, 1, withDocuments(requirement(1, USE, true), tooLarge))));
        RequirementProjection[] manyDocuments = IntStream.range(0, 129)
                .mapToObj(index -> withDocuments(requirement(index + 1, USE, false), document(index + 1001)))
                .toArray(RequirementProjection[]::new);
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(actor(UserRole.USER), scope(USE, 1, manyDocuments)));
    }

    @Test
    void optionalOnlyAndEmptyScopesAreRepresentableWithoutInventingARequiredPresenceRule() {
        Snapshot optional = snapshot(actor(UserRole.USER), scope(USE, 1, requirement(1, USE, false)));
        LegalAuthenticatedRequirements result = new LegalAuthenticatedRequirements(optional,
                List.of(new Decision(id(1), Satisfaction.PENDING, null)));
        assertEquals(1, result.requirements().size());
        assertFalse(result.hasRequiredPending());
        Snapshot empty = snapshot(actor(UserRole.USER), scope(USE, 1));
        LegalAuthenticatedRequirements none = new LegalAuthenticatedRequirements(empty, List.of());
        assertTrue(none.requirements().isEmpty());
        assertFalse(none.hasRequiredPending());
        assertEquals(1, empty.scopeRevisions().size());
        assertTrue(empty.requiredSetRevision().startsWith("sha256:"));
    }

    @Test
    void acceptsEightCompleteScopesAndThePerScopeRequirementBoundary() {
        ContextoLegal[] contexts = ContextoLegal.values();
        List<Scope> scopes = IntStream.range(0, contexts.length)
                .mapToObj(index -> scope(contexts[index], index + 1,
                        requirement(index + 1, contexts[index], false))).toList();
        Snapshot allScopes = new Snapshot(actor(UserRole.ADMIN),
                applicability(UserRole.ADMIN, contexts), scopes);
        assertEquals(8, allScopes.scopeRevisions().size());
        assertEquals(8, allScopes.requirements().size());
        assertThrows(IllegalArgumentException.class, () -> new Scope(0, scopes.getFirst().projection(),
                scopes.getFirst().memberships()));
        assertThrows(IllegalArgumentException.class, () -> new Scope(9, scopes.getFirst().projection(),
                scopes.getFirst().memberships()));

        List<RequirementProjection> maximum = IntStream.rangeClosed(1, LegalManifestLimits.MAX_REQUIREMENTS)
                .mapToObj(index -> requirement(index, USE, false)).toList();
        List<Membership> members = IntStream.range(0, maximum.size())
                .mapToObj(index -> new Membership(index + 1, "boundary-" + index,
                        maximum.get(index).versionId())).toList();
        Snapshot maximumScope = snapshot(actor(UserRole.USER), new Scope(1,
                new LegalRequiredSetProjection(USE, LocaleLegal.ES_AR, maximum), members));
        assertEquals(256, maximumScope.requirements().size());
        List<RequirementProjection> excessive = new ArrayList<>(maximum);
        excessive.add(requirement(257, USE, false));
        assertThrows(IllegalArgumentException.class,
                () -> new LegalRequiredSetProjection(USE, LocaleLegal.ES_AR, excessive));
    }

    @Test
    void pendingFilteringPreservesFullRevisionAndOriginalOrderIncludingOptionalRequirements() {
        Snapshot source = snapshot(actor(UserRole.USER), scope(USE, 1,
                requirement(9, USE, true), requirement(2, USE, false), requirement(7, USE, true)));
        LegalAuthenticatedRequirements mixed = new LegalAuthenticatedRequirements(source, List.of(
                new Decision(id(9), Satisfaction.EXACT, id(901)),
                new Decision(id(2), Satisfaction.PENDING, null),
                new Decision(id(7), Satisfaction.PENDING, null)));
        assertEquals(List.of(id(2), id(7)), mixed.requirements().stream().map(RequirementProjection::versionId).toList());
        assertTrue(mixed.hasRequiredPending());
        assertEquals(source.requiredSetRevision(), mixed.requiredSetRevision());
        LegalAuthenticatedRequirements satisfied = new LegalAuthenticatedRequirements(source, List.of(
                new Decision(id(9), Satisfaction.EXACT, id(901)),
                new Decision(id(2), Satisfaction.INHERITED, id(902)),
                new Decision(id(7), Satisfaction.EXACT, id(903))));
        assertTrue(satisfied.requirements().isEmpty());
        assertFalse(satisfied.hasRequiredPending());
        assertEquals(mixed.requiredSetRevision(), satisfied.requiredSetRevision());
        assertSame(source, satisfied.snapshot());
    }

    @Test
    void aTokenDependsOnTheCompleteScopeAndAudienceButNotActorIdentityOrTokenVersion() {
        Scope scope = scope(USE, 1, requirement(1, USE, true));
        Snapshot first = snapshot(actor(UserRole.USER), scope);
        Snapshot anotherActor = snapshot(new LegalActorSnapshot(90, 91, UserRole.USER, 92, true, true), scope);
        assertEquals(first.requiredSetRevision(), anotherActor.requiredSetRevision());
        Snapshot admin = snapshot(actor(UserRole.ADMIN), scope);
        assertNotEquals(first.requiredSetRevision(), admin.requiredSetRevision());
        Snapshot changed = snapshot(actor(UserRole.USER), scope(USE, 1, requirement(2, USE, true)));
        assertNotEquals(first.requiredSetRevision(), changed.requiredSetRevision());
    }

    @Test
    void decisionsMustCoverExactlyTheFullProjectionAndReferenceEvidenceOnlyWhenSatisfied() {
        Snapshot source = snapshot(actor(UserRole.USER), scope(USE, 1, requirement(1, USE, true), requirement(2, USE, false)));
        assertThrows(IllegalArgumentException.class, () -> new Decision(id(1), Satisfaction.PENDING, id(90)));
        assertThrows(IllegalArgumentException.class, () -> new Decision(id(1), Satisfaction.EXACT, null));
        assertThrows(IllegalArgumentException.class, () -> new Decision(id(1), Satisfaction.INHERITED, null));
        assertThrows(IllegalArgumentException.class, () -> new LegalAuthenticatedRequirements(source, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LegalAuthenticatedRequirements(source, List.of(
                new Decision(id(2), Satisfaction.PENDING, null), new Decision(id(1), Satisfaction.PENDING, null))));
        assertThrows(IllegalArgumentException.class, () -> new LegalAuthenticatedRequirements(source, List.of(
                new Decision(id(1), Satisfaction.PENDING, null), new Decision(id(1), Satisfaction.PENDING, null))));
    }

    @Test
    void allInputCollectionsAndExposedViewsRemainImmutable() {
        List<DocumentProjection> documents = new ArrayList<>(List.of(document(101)));
        RequirementProjection requirement = new RequirementProjection(id(1), USE, TipoActoLegal.ACEPTACION,
                "Acepto las condiciones.", sha("Acepto las condiciones."), documents, true);
        List<RequirementProjection> requirements = new ArrayList<>(List.of(requirement));
        LegalRequiredSetProjection projection = new LegalRequiredSetProjection(USE, LocaleLegal.ES_AR, requirements);
        List<Membership> memberships = new ArrayList<>(List.of(new Membership(2, "conditions", id(1))));
        Scope scope = new Scope(1, projection, memberships);
        List<Scope> scopes = new ArrayList<>(List.of(scope));
        Snapshot source = new Snapshot(actor(UserRole.USER), applicability(UserRole.USER, USE), scopes);
        List<Decision> decisions = new ArrayList<>(List.of(new Decision(id(1), Satisfaction.PENDING, null)));
        LegalAuthenticatedRequirements result = new LegalAuthenticatedRequirements(source, decisions);
        documents.clear(); requirements.clear(); memberships.clear(); scopes.clear(); decisions.clear();
        assertEquals(1, result.requirements().size());
        assertEquals(1, source.requirements().getFirst().documents().size());
        assertThrows(UnsupportedOperationException.class, () -> source.scopes().clear());
        assertThrows(UnsupportedOperationException.class, () -> source.scopeRevisions().clear());
        assertThrows(UnsupportedOperationException.class, () -> source.requirements().clear());
        assertThrows(UnsupportedOperationException.class, () -> source.scopes().getFirst().memberships().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.decisions().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.requirements().clear());
    }

    private static LegalActorSnapshot actor(UserRole role) {
        return new LegalActorSnapshot(1, 2, role, 3, true, true);
    }

    private static Snapshot snapshot(LegalActorSnapshot actor, Scope scope) {
        return new Snapshot(actor, applicability(actor.role(), scope.projection().context()), List.of(scope));
    }

    private static LegalApplicableScopeSet applicability(UserRole role, ContextoLegal... contexts) {
        return new LegalApplicableScopeResolver((profile, audience) -> Arrays.asList(contexts)).resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING, LocaleLegal.ES_AR, role.toAudienciaLegal());
    }

    private static Scope scope(ContextoLegal context, int ordinal, RequirementProjection... requirements) {
        List<Membership> members = IntStream.range(0, requirements.length)
                .mapToObj(index -> new Membership(index * 2 + 1, "requirement-" + requirements[index].versionId(),
                        requirements[index].versionId())).toList();
        return new Scope(ordinal, projection(context, requirements), members);
    }

    private static LegalRequiredSetProjection projection(ContextoLegal context, RequirementProjection... requirements) {
        return new LegalRequiredSetProjection(context, LocaleLegal.ES_AR, Arrays.asList(requirements));
    }

    private static RequirementProjection requirement(int identifier, ContextoLegal context, boolean required) {
        String statement = "Acepto las condiciones " + identifier + ".";
        return new RequirementProjection(id(identifier), context, TipoActoLegal.ACEPTACION,
                statement, sha(statement), List.of(document(101)), required);
    }

    private static RequirementProjection withDocuments(RequirementProjection source, DocumentProjection... documents) {
        return new RequirementProjection(source.versionId(), source.context(), source.actType(), source.statement(),
                source.statementSha256(), Arrays.asList(documents), source.required());
    }

    private static DocumentProjection document(int identifier) {
        String markdown = "# Documento " + identifier + "\nCondiciones vigentes.\n";
        return new DocumentProjection(id(identifier), TipoDocumentoLegal.TERMINOS_SERVICIO,
                "v1", "Condiciones", markdown, sha(markdown), DATE, LocaleLegal.ES_AR);
    }

    private static UUID id(int value) {
        return new UUID(0L, value);
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
