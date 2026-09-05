package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalPublicRequirementsValidatorTest {

    private static final String FIXTURE = "/legal/manifest/public-registration-requirements-v1/";
    private static final ContextoLegal CONTEXT = ContextoLegal.REGISTRO;
    private static final LocaleLegal LOCALE = LocaleLegal.ES_AR;
    private static final OffsetDateTime DATE = OffsetDateTime.parse("2026-09-01T03:00:00.123456Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LegalPublicRequirementsValidator validator = new LegalPublicRequirementsValidator();

    @Test
    void matchesIndependentlyGeneratedWireCanonicalBytesAndBothRevisionGoldens() throws Exception {
        byte[] wire = fixture("projection.json");
        byte[] scopeCanonical = fixture("scope-canonical.json");
        byte[] aggregateCanonical = fixture("aggregate-canonical.json");
        String scopeRevision = fixtureText("scope-sha256.txt").strip();
        String aggregateRevision = fixtureText("aggregate-sha256.txt").strip();
        LegalRequiredSetProjection projection = projectionFromWire(JSON.readTree(wire));

        // This independent JCS implementation verifies the checked-in Python-produced artifacts.
        assertThat(new JsonCanonicalizer(new String(wire, StandardCharsets.UTF_8)).getEncodedUTF8())
                .containsExactly(scopeCanonical);
        assertThat(new JsonCanonicalizer(new String(aggregateCanonical, StandardCharsets.UTF_8))
                .getEncodedUTF8()).containsExactly(aggregateCanonical);
        assertThat("sha256:" + sha256(scopeCanonical)).isEqualTo(scopeRevision);
        assertThat("sha256:" + sha256(aggregateCanonical)).isEqualTo(aggregateRevision);

        LegalPublicRegistrationRequirements result = validator.validate(projection);

        assertThat(result.projection()).isSameAs(projection);
        assertThat(result.scopeRevision()).isEqualTo(scopeRevision);
        assertThat(result.requiredSetRevision()).isEqualTo(aggregateRevision).isNotEqualTo(scopeRevision);
        assertThat(new LegalRequiredSetRevisionCalculator().canonicalUtf8(result.projection()))
                .containsExactly(scopeCanonical);
        LegalRequiredSetAggregateProjection aggregate = new LegalRequiredSetAggregateProjection(
                EsquemaRevisionLegal.AGGREGATE_V1, LOCALE, AudienciaLegal.ADMIN_TITULAR,
                List.of(new LegalRequiredSetAggregateProjection.ScopeRevision(CONTEXT, result.scopeRevision())));
        assertThat(new LegalRequiredSetAggregateRevisionCalculator().canonicalUtf8(aggregate))
                .containsExactly(aggregateCanonical);
        assertThat(result.projection().requirements()).hasSize(2);
        assertThat(result.projection().requirements().getLast().required()).isFalse();
        assertThat(result.projection().requirements().stream().flatMap(item -> item.documents().stream())
                .map(DocumentProjection::versionId).distinct()).hasSize(3);
    }

    @Test
    void preservesContractualMemberAndDocumentOrderRatherThanSortingByUuidOrType() throws Exception {
        LegalRequiredSetProjection source = projectionFromWire(JSON.readTree(fixture("projection.json")));
        LegalPublicRegistrationRequirements baseline = validator.validate(source);
        List<RequirementProjection> reversedMembers = new ArrayList<>(source.requirements());
        Collections.reverse(reversedMembers);
        LegalPublicRegistrationRequirements members = validator.validate(projection(reversedMembers));
        assertThat(members.projection().requirements()).containsExactlyElementsOf(reversedMembers);
        assertDifferentRevisions(baseline, members);

        RequirementProjection first = source.requirements().getFirst();
        List<DocumentProjection> reversedDocuments = new ArrayList<>(first.documents());
        Collections.reverse(reversedDocuments);
        LegalRequiredSetProjection changed = projection(List.of(
                withDocuments(first, reversedDocuments), source.requirements().getLast()));
        LegalPublicRegistrationRequirements documents = validator.validate(changed);
        assertThat(documents.projection().requirements().getFirst().documents())
                .containsExactlyElementsOf(reversedDocuments);
        assertDifferentRevisions(baseline, documents);
    }

    @Test
    void theLastOptionalRequirementAndItsLastDocumentBothContributeToTheTokens() {
        RequirementProjection mandatory = requirement(1, true, List.of(document(1, "# Uno\n")));
        RequirementProjection optional = requirement(2, false,
                List.of(document(2, "# Dos\n"), document(3, "# Tres\n")));
        LegalPublicRegistrationRequirements baseline = validator.validate(projection(List.of(mandatory, optional)));
        RequirementProjection lastStatementChanged = withStatement(optional, "Leí también el último aviso.");
        assertDifferentRevisions(baseline, validator.validate(projection(List.of(mandatory, lastStatementChanged))));

        DocumentProjection last = optional.documents().getLast();
        DocumentProjection changed = withMarkdown(last, last.markdown() + "Última línea.\n");
        RequirementProjection lastDocumentChanged = withDocuments(optional,
                List.of(optional.documents().getFirst(), changed));
        assertDifferentRevisions(baseline, validator.validate(projection(List.of(mandatory, lastDocumentChanged))));
    }

    @Test
    void rejectsNullWithoutMintingAnAccreditedResult() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ContextoLegal.class, mode = EnumSource.Mode.EXCLUDE, names = "REGISTRO")
    void rejectsAnyRootScopeOtherThanRegistration(ContextoLegal context) {
        LegalRequiredSetProjection invalid = new LegalRequiredSetProjection(context, LOCALE,
                List.of(requirement(1, true, List.of(document(1, "# Documento\n")))));
        assertInvalid(invalid);
    }

    @Test
    void rejectsARequirementFromAnotherContextEvenWithValidContent() {
        RequirementProjection valid = requirement(1, true, List.of(document(1, "# Documento\n")));
        RequirementProjection foreign = new RequirementProjection(valid.versionId(), ContextoLegal.USO_CONTINUADO,
                valid.actType(), valid.statement(), valid.statementSha256(), valid.documents(), true);
        assertInvalid(projection(List.of(foreign)));
    }

    @Test
    void rejectsEmptyAndOptionalOnlySets() {
        assertInvalid(projection(List.of()));
        assertInvalid(projection(List.of(requirement(1, false, List.of(document(1, "# Documento\n"))))));
    }

    @Test
    void rejectsDuplicateRequirementIdsWithoutSilentlyDeduplicating() {
        RequirementProjection first = requirement(1, true, List.of(document(1, "# Documento\n")));
        assertInvalid(projection(List.of(first, first)));
        assertInvalid(projection(List.of(first, withStatement(first, "Otra afirmación con el mismo UUID."))));
    }

    @Test
    void rejectsZeroDocumentsAndRepeatedDocumentIdsWithinOneRequirement() {
        assertInvalid(projection(List.of(requirement(1, true, List.of()))));
        DocumentProjection repeated = document(1, "# Documento\n");
        assertInvalid(projection(List.of(requirement(1, true, List.of(repeated, repeated)))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"statement", "markdown", "title", "documents"})
    void validatesEveryOptionalRequirementInsteadOfFilteringAnInvalidMember(String defect) {
        RequirementProjection mandatory = requirement(1, true, List.of(document(1, "# Obligatorio\n")));
        DocumentProjection optionalDocument = document(2, "# Opcional\n");
        RequirementProjection optional = requirement(2, false, List.of(optionalDocument));
        RequirementProjection invalid = switch (defect) {
            case "statement" -> withStatement(optional, "\u200B\u200D");
            case "markdown" -> withDocuments(optional, List.of(withMarkdown(optionalDocument, "")));
            case "title" -> withDocuments(optional, List.of(withMetadata(optionalDocument,
                    optionalDocument.version(), "   ", optionalDocument.effectiveAt())));
            case "documents" -> withDocuments(optional, List.of());
            default -> throw new AssertionError(defect);
        };
        assertInvalid(projection(List.of(mandatory, invalid)));
    }

    @ParameterizedTest
    @MethodSource("unpublishableStatements")
    void rejectsEmptyInvisibleOrNonCanonicalStatementsEvenWithTheirExactDigest(String statement) {
        RequirementProjection member = requirement(1, true, List.of(document(1, "# Documento\n")));
        assertInvalid(projection(List.of(withStatement(member, statement))));
    }

    static Stream<String> unpublishableStatements() {
        return Stream.of("", "   ", "\u00A0\u2007\u202F", "\u200B\u200C\u200D", "\u0301",
                "Acepto\n", "Acepto\t", "Acepto\r\n", "\uFEFFAcepto", "Cafe\u0301",
                "Acepto\u0000", "Acepto\u0085", "Acepto\uFDD0", "Acepto\uD800", "Acepto\uDC00");
    }

    @ParameterizedTest
    @MethodSource("nonCanonicalMarkdown")
    void rejectsEmptyOrNonCanonicalMarkdownEvenWithItsExactDigest(String markdown) {
        assertInvalid(projection(List.of(requirement(1, true, List.of(document(1, markdown))))));
    }

    static Stream<String> nonCanonicalMarkdown() {
        return Stream.of("", "\uFEFF# Documento\n", "# Documento\r\n", "# Cafe\u0301\n",
                "# Documento\u0000", "# Documento\u007F", "# Documento\u0085", "# Documento\uFDD0",
                "# Documento\uD800", "# Documento\uDC00");
    }

    @Test
    void preservesOriginalWhitespaceEscapesAndCanonicalUnicodeWithoutRewritingContent() {
        String markdown = "# Café 🙂\n\n  Texto con \\\"comillas\\\" y ruta\\archivo.\n\tÚltima línea.\n";
        String statement = "  Acepto \"Café 🙂\" y ruta\\archivo.  ";
        RequirementProjection member = withStatement(requirement(1, true,
                List.of(document(1, markdown))), statement);
        LegalRequiredSetProjection original = projection(List.of(member));
        LegalPublicRegistrationRequirements result = validator.validate(original);
        assertThat(result.projection()).isSameAs(original);
        assertThat(result.projection().requirements().getFirst().statement()).isEqualTo(statement);
        assertThat(result.projection().requirements().getFirst().documents().getFirst().markdown())
                .isEqualTo(markdown);
        assertThat(result.scopeRevision()).isNotEqualTo(validator.validate(projection(List.of(
                withStatement(member, statement.strip())))).scopeRevision());
    }

    @Test
    void requiresNonemptyCanonicalMarkdownWithoutInventingAnH1OrVisibilityRule() {
        LegalPublicRegistrationRequirements result = validator.validate(projection(List.of(
                requirement(1, true, List.of(document(1, " \n\t"))))));
        assertThat(result.projection().requirements().getFirst().documents().getFirst().markdown())
                .isEqualTo(" \n\t");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mismatch", "uppercase", "nonhex"})
    void rejectsInvalidDigestsInBothStatementsAndDocuments(String defect) {
        DocumentProjection source = document(1, "# Documento\n");
        RequirementProjection member = requirement(1, true, List.of(source));
        String badStatementDigest = defectiveDigest(member.statementSha256(), defect);
        RequirementProjection invalidStatement = new RequirementProjection(member.versionId(), member.context(),
                member.actType(), member.statement(), badStatementDigest, member.documents(), true);
        assertInvalid(projection(List.of(invalidStatement)));
        DocumentProjection invalidDocument = new DocumentProjection(source.versionId(), source.type(),
                source.version(), source.title(), source.markdown(), defectiveDigest(source.sha256(), defect),
                source.effectiveAt(), source.locale());
        assertInvalid(projection(List.of(withDocuments(member, List.of(invalidDocument)))));
    }

    @ParameterizedTest
    @MethodSource("invalidPersistedMetadata")
    void rejectsInvalidVersionAndTitleMetadataAcceptedByTheStructuralProjection(String value) {
        DocumentProjection source = document(1, "# Documento\n");
        assertInvalid(projection(List.of(requirement(1, true,
                List.of(withMetadata(source, value, source.title(), source.effectiveAt()))))));
        assertInvalid(projection(List.of(requirement(1, true,
                List.of(withMetadata(source, source.version(), value, source.effectiveAt()))))));
    }

    static Stream<String> invalidPersistedMetadata() {
        return Stream.of("", "   ", "x\u0000", "x\uD800", "x\uDC00", "x\uD800y");
    }

    @Test
    void acceptsMetadataAtCodePointLimitsAndPreservesCombiningMarksWithoutNormalization() {
        DocumentProjection source = document(1, "# Documento\n");
        DocumentProjection maximum = withMetadata(source, "🙂".repeat(40), "🙂".repeat(300), DATE);
        RequirementProjection member = withStatement(requirement(1, true, List.of(maximum)), "🙂".repeat(1_000));
        LegalPublicRegistrationRequirements result = validator.validate(projection(List.of(member)));
        assertThat(result.projection().requirements().getFirst().statement().codePointCount(0, 2_000))
                .isEqualTo(1_000);
        assertThat(result.projection().requirements().getFirst().documents().getFirst()).isEqualTo(maximum);

        DocumentProjection decomposed = withMetadata(source, "v-e\u0301", "Cafe\u0301", DATE);
        DocumentProjection composed = withMetadata(source, "v-é", "Café", DATE);
        LegalPublicRegistrationRequirements preserved = validator.validate(projection(List.of(
                requirement(1, true, List.of(decomposed)))));
        assertThat(preserved.projection().requirements().getFirst().documents().getFirst()).isSameAs(decomposed);
        assertDifferentRevisions(preserved, validator.validate(projection(List.of(
                requirement(1, true, List.of(composed))))));
    }

    @Test
    void metadataUsesPostgresSpaceRulesRatherThanBlankOrCanonicalTextRules() {
        DocumentProjection source = document(1, "# Documento\n");
        DocumentProjection metadata = withMetadata(source, "\t", "\u00A0", DATE);
        assertThat(validator.validate(projection(List.of(requirement(1, true, List.of(metadata)))))
                .projection().requirements().getFirst().documents().getFirst()).isSameAs(metadata);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000-01-01T00:00:00Z", "9999-12-31T23:59:59.999999Z",
            "2999-09-01T00:00:00.123456Z", "0000-01-01T01:00:00+01:00"})
    void acceptsUtcRepresentableMicrosecondsIncludingFutureDatesWithoutConsultingAClock(String value) {
        DocumentProjection source = document(1, "# Documento\n");
        DocumentProjection dated = withMetadata(source, source.version(), source.title(), OffsetDateTime.parse(value));
        assertThat(validator.validate(projection(List.of(requirement(1, true, List.of(dated)))))
                .projection().requirements().getFirst().documents().getFirst()).isSameAs(dated);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0001-12-31T23:59:59.999999Z", "+10000-01-01T00:00:00Z",
            "2026-09-01T03:00:00.123456001Z", "0000-01-01T00:00:00+01:00",
            "9999-12-31T23:59:59.999999-01:00"})
    void rejectsUnrepresentableUtcDatesAndPrecisionWithoutRounding(String value) {
        DocumentProjection source = document(1, "# Documento\n");
        assertInvalid(projection(List.of(requirement(1, true, List.of(withMetadata(source,
                source.version(), source.title(), OffsetDateTime.parse(value)))))));
    }

    @Test
    void acceptsSharedDocumentIdsAcrossRequirementsWhenTheirCanonicalFieldsMatch() {
        DocumentProjection source = document(1, "# Compartido\n");
        DocumentProjection copy = withMetadata(source, source.version(), source.title(),
                source.effectiveAt().withOffsetSameInstant(ZoneOffset.ofHours(-3)));
        assertThat(copy).isNotEqualTo(source);
        LegalRequiredSetProjection equivalent = projection(List.of(
                requirement(1, true, List.of(source)), requirement(2, false, List.of(copy))));
        LegalPublicRegistrationRequirements result = validator.validate(equivalent);
        LegalPublicRegistrationRequirements baseline = validator.validate(projection(List.of(
                requirement(1, true, List.of(source)), requirement(2, false, List.of(source)))));
        assertThat(result.scopeRevision()).isEqualTo(baseline.scopeRevision());
        assertThat(result.requiredSetRevision()).isEqualTo(baseline.requiredSetRevision());
        assertThat(result.projection()).isSameAs(equivalent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000-0000-0000-0000-000000000000",
            "00000000-0000-0000-0000-000000000001", "ffffffff-ffff-ffff-ffff-ffffffffffff"})
    void preservesAnyUuidBitsAndKeepsRequirementAndDocumentIdentityNamespacesSeparate(String value) {
        UUID id = UUID.fromString(value);
        DocumentProjection document = new DocumentProjection(id, TipoDocumentoLegal.TERMINOS_SERVICIO,
                "1.0.0", "Documento", "# Documento\n", sha256("# Documento\n"), DATE, LOCALE);
        String statement = "Acepto el documento.";
        RequirementProjection requirement = new RequirementProjection(id, CONTEXT, TipoActoLegal.ACEPTACION,
                statement, sha256(statement), List.of(document), true);
        LegalPublicRegistrationRequirements result = validator.validate(projection(List.of(requirement)));
        assertThat(result.projection().requirements().getFirst().versionId()).isEqualTo(id);
        assertThat(result.projection().requirements().getFirst().documents().getFirst().versionId()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "version", "title", "markdown", "sha256", "effectiveAt"})
    void rejectsConflictingCanonicalFieldsForTheSameDocumentUuidAcrossRequirements(String field) {
        DocumentProjection source = document(1, "# Compartido\n");
        DocumentProjection conflicting = switch (field) {
            case "type" -> new DocumentProjection(source.versionId(), TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                    source.version(), source.title(), source.markdown(), source.sha256(), DATE, LOCALE);
            case "version" -> withMetadata(source, "2.0.0", source.title(), DATE);
            case "title" -> withMetadata(source, source.version(), "Otro título", DATE);
            case "markdown" -> withMarkdown(source, "# Compartido\nOtro contenido válido.\n");
            case "sha256" -> new DocumentProjection(source.versionId(), source.type(),
                    source.version(), source.title(), source.markdown(), "0".repeat(64), DATE, LOCALE);
            case "effectiveAt" -> withMetadata(source, source.version(), source.title(), DATE.plusNanos(1_000));
            default -> throw new AssertionError(field);
        };
        assertInvalid(projection(List.of(requirement(1, true, List.of(source)),
                requirement(2, false, List.of(conflicting)))));
    }

    @Test
    void accepts256RequirementsAndCountsSharedDocumentIdsOnlyOnceForTheDistinctLimit() {
        DocumentProjection shared = document(1, "# Compartido\n");
        LegalRequiredSetProjection maximum = projection(IntStream.rangeClosed(1, 256)
                .mapToObj(index -> requirement(index, index == 1, List.of(shared))).toList());
        LegalPublicRegistrationRequirements result = validator.validate(maximum);
        assertThat(result.projection()).isSameAs(maximum);
        assertThat(result.projection().requirements()).hasSize(256);
    }

    @Test
    void accepts128DistinctDocumentsInGroupsOf16AndRejectsThe129th() {
        List<DocumentProjection> documents = IntStream.rangeClosed(1, 129)
                .mapToObj(index -> document(index, "# Documento " + index + "\n")).toList();
        List<RequirementProjection> requirements = new ArrayList<>();
        for (int group = 0; group < 8; group++) {
            requirements.add(requirement(group + 1, group == 0, documents.subList(group * 16, (group + 1) * 16)));
        }
        LegalPublicRegistrationRequirements result = validator.validate(projection(requirements));
        assertThat(result.projection().requirements()).hasSize(8)
                .allSatisfy(requirement -> assertThat(requirement.documents()).hasSize(16));
        requirements.add(requirement(9, false, List.of(documents.getLast())));
        assertInvalid(projection(requirements));
    }

    @Test
    void measuresOneMiBMarkdownInUtf8BytesRatherThanUtf16Length() {
        String atLimit = "🙂".repeat(LegalManifestLimits.MAX_MARKDOWN_BYTES / 4);
        assertThat(atLimit.length()).isLessThan(LegalManifestLimits.MAX_MARKDOWN_BYTES);
        assertThat(atLimit.getBytes(StandardCharsets.UTF_8)).hasSize(LegalManifestLimits.MAX_MARKDOWN_BYTES);
        LegalPublicRegistrationRequirements result = validator.validate(projection(List.of(
                requirement(1, true, List.of(document(1, atLimit))))));
        assertThat(result.projection().requirements().getFirst().documents().getFirst().markdown())
                .isSameAs(atLimit);
        assertInvalid(projection(List.of(requirement(1, true, List.of(document(1, atLimit + "a"))))));
    }

    @Test
    void countsEachRepeatedMarkdownReferenceAtTheExpanded16MiBBoundary() {
        String oneMiB = "a".repeat(LegalManifestLimits.MAX_MARKDOWN_BYTES);
        DocumentProjection shared = document(1, oneMiB);
        List<RequirementProjection> references = new ArrayList<>(IntStream.rangeClosed(1, 16)
                .mapToObj(index -> requirement(index, index == 1, List.of(shared))).toList());
        LegalPublicRegistrationRequirements maximum = validator.validate(projection(references));
        assertThat(maximum.projection().requirements()).hasSize(16);
        assertThat(maximum.projection().requirements().stream().flatMap(item -> item.documents().stream())
                .map(DocumentProjection::versionId).distinct()).hasSize(1);
        references.add(requirement(17, false, List.of(document(2, "x"))));
        // Only two distinct documents, but sixteen shared 1 MiB references plus this byte exceed scope capacity.
        assertInvalid(projection(references));
    }

    @Test
    void accreditationKeepsImmutableInputAndCannotBeMintedThroughAPublicTokenConstructor() {
        List<DocumentProjection> documents = new ArrayList<>(List.of(document(1, "# Documento\n")));
        RequirementProjection member = requirement(1, true, documents);
        List<RequirementProjection> members = new ArrayList<>(List.of(member));
        LegalRequiredSetProjection source = projection(members);
        LegalPublicRegistrationRequirements result = validator.validate(source);
        String revision = result.requiredSetRevision();
        documents.clear();
        members.clear();
        assertThat(result.projection()).isSameAs(source);
        assertThat(result.projection().requirements()).containsExactly(member);
        assertThat(result.projection().requirements().getFirst().documents()).hasSize(1);
        assertThatThrownBy(() -> result.projection().requirements().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.projection().requirements().getFirst().documents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(validator.validate(source).requiredSetRevision()).isEqualTo(revision);
        assertThat(Modifier.isFinal(LegalPublicRegistrationRequirements.class.getModifiers())).isTrue();
        assertThat(LegalPublicRegistrationRequirements.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(LegalPublicRegistrationRequirements.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == LegalPublicRegistrationRequirements.class)).isEmpty();
        assertThat(Arrays.stream(LegalPublicRequirementsValidator.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && method.getReturnType() == LegalPublicRegistrationRequirements.class))
                .singleElement().satisfies(method ->
                        assertThat(method.getParameterTypes()).containsExactly(LegalRequiredSetProjection.class));
    }

    private void assertInvalid(LegalRequiredSetProjection projection) {
        assertThatThrownBy(() -> validator.validate(projection)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertDifferentRevisions(LegalPublicRegistrationRequirements first,
                                                 LegalPublicRegistrationRequirements second) {
        assertThat(second.scopeRevision()).isNotEqualTo(first.scopeRevision());
        assertThat(second.requiredSetRevision()).isNotEqualTo(first.requiredSetRevision());
    }

    private static LegalRequiredSetProjection projection(List<RequirementProjection> requirements) {
        return new LegalRequiredSetProjection(CONTEXT, LOCALE, requirements);
    }

    private static RequirementProjection requirement(int id, boolean required, List<DocumentProjection> documents) {
        String statement = "Acepto el requisito " + id + ".";
        return new RequirementProjection(new UUID(1, id), CONTEXT, TipoActoLegal.ACEPTACION,
                statement, sha256(statement), documents, required);
    }

    private static DocumentProjection document(int id, String markdown) {
        return new DocumentProjection(new UUID(2, id), TipoDocumentoLegal.TERMINOS_SERVICIO,
                "1.0.0", "Documento " + id, markdown, sha256(markdown), DATE, LOCALE);
    }

    private static RequirementProjection withDocuments(RequirementProjection source, List<DocumentProjection> docs) {
        return new RequirementProjection(source.versionId(), source.context(), source.actType(),
                source.statement(), source.statementSha256(), docs, source.required());
    }

    private static RequirementProjection withStatement(RequirementProjection source, String statement) {
        return new RequirementProjection(source.versionId(), source.context(), source.actType(),
                statement, sha256(statement), source.documents(), source.required());
    }

    private static DocumentProjection withMarkdown(DocumentProjection source, String markdown) {
        return new DocumentProjection(source.versionId(), source.type(), source.version(), source.title(),
                markdown, sha256(markdown), source.effectiveAt(), source.locale());
    }

    private static DocumentProjection withMetadata(DocumentProjection source, String version, String title,
                                                   OffsetDateTime effectiveAt) {
        return new DocumentProjection(source.versionId(), source.type(), version, title, source.markdown(),
                source.sha256(), effectiveAt, source.locale());
    }

    private static String defectiveDigest(String original, String defect) {
        return switch (defect) {
            case "mismatch" -> (original.charAt(0) == '0' ? "1" : "0") + original.substring(1);
            case "uppercase" -> original.toUpperCase(java.util.Locale.ROOT);
            case "nonhex" -> "g" + original.substring(1);
            default -> throw new AssertionError(defect);
        };
    }

    private static LegalRequiredSetProjection projectionFromWire(JsonNode root) {
        List<RequirementProjection> requirements = new ArrayList<>();
        for (JsonNode member : root.path("requisitos")) {
            List<DocumentProjection> documents = new ArrayList<>();
            for (JsonNode document : member.path("documentos")) {
                assertThat(document.path("estado").asText()).isEqualTo("VIGENTE");
                documents.add(new DocumentProjection(UUID.fromString(document.path("id").asText()),
                        TipoDocumentoLegal.valueOf(document.path("tipo").asText()),
                        document.path("version").asText(), document.path("titulo").asText(),
                        document.path("contenidoMarkdown").asText(), document.path("sha256").asText(),
                        OffsetDateTime.parse(document.path("vigenteDesde").asText()),
                        LocaleLegal.fromCodigo(document.path("locale").asText())));
            }
            requirements.add(new RequirementProjection(UUID.fromString(member.path("id").asText()),
                    ContextoLegal.valueOf(member.path("contexto").asText()),
                    TipoActoLegal.valueOf(member.path("tipoActo").asText()),
                    member.path("afirmacion").asText(), member.path("afirmacionSha256").asText(),
                    documents, member.path("requerido").asBoolean()));
        }
        return new LegalRequiredSetProjection(ContextoLegal.valueOf(root.path("contexto").asText()),
                LocaleLegal.fromCodigo(root.path("locale").asText()), requirements);
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream stream = LegalPublicRequirementsValidatorTest.class.getResourceAsStream(FIXTURE + name)) {
            assertThat(stream).as("fixture %s", name).isNotNull();
            return stream.readAllBytes();
        }
    }

    private static String fixtureText(String name) throws Exception {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
