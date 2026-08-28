package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.CurrentPublication;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.DocumentReference;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.DocumentSlot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.DocumentVersionState;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.ReplacementBatch;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.ReplacementSuccessor;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.RequiredSetMember;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.RequiredSetPointer;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalEditorialStateProjection.RequirementVersionState;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoConstruccionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalEditorialStateFingerprintCalculatorTest {

    private static final String FIXTURE_ROOT =
            "/legal/manifest/editorial-state-fingerprint-v1/";

    private static final UUID PUBLICATION_ID = uuid("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER_PUBLICATION_ID =
            uuid("00000000-0000-0000-0000-0000000000c2");
    private static final UUID BATCH_ID = uuid("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_BATCH_ID =
            uuid("20000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_LOW =
            uuid("7fffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID DOCUMENT_HIGH =
            uuid("80000000-0000-0000-0000-000000000000");
    private static final UUID DOCUMENT_SECOND_PREDECESSOR =
            uuid("70000000-0000-0000-0000-000000000003");
    private static final UUID DOCUMENT_SECOND_SUCCESSOR =
            uuid("90000000-0000-0000-0000-000000000004");
    private static final UUID TERMS_PREDECESSOR =
            uuid("10000000-0000-0000-0000-000000000005");
    private static final UUID TERMS_SUCCESSOR =
            uuid("a0000000-0000-0000-0000-000000000006");
    private static final UUID RETIRED_DOCUMENT =
            uuid("b0000000-0000-0000-0000-000000000007");
    private static final UUID DOCUMENT_LINE_LOW =
            uuid("30000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_LINE_HIGH =
            uuid("30000000-0000-0000-0000-000000000002");
    private static final UUID TERMS_DOCUMENT_LINE =
            uuid("30000000-0000-0000-0000-000000000003");
    private static final UUID RETIRED_DOCUMENT_LINE =
            uuid("30000000-0000-0000-0000-000000000004");
    private static final UUID REQUIREMENT_A =
            uuid("40000000-0000-0000-0000-000000000001");
    private static final UUID REQUIREMENT_B =
            uuid("40000000-0000-0000-0000-000000000002");
    private static final UUID REQUIREMENT_C =
            uuid("40000000-0000-0000-0000-000000000003");
    private static final UUID REQUIREMENT_D =
            uuid("40000000-0000-0000-0000-000000000004");
    private static final UUID REQUIREMENT_LINE_A =
            uuid("50000000-0000-0000-0000-000000000001");
    private static final UUID REQUIREMENT_LINE_B =
            uuid("50000000-0000-0000-0000-000000000002");
    private static final UUID REQUIREMENT_LINE_C =
            uuid("50000000-0000-0000-0000-000000000003");
    private static final UUID REQUIREMENT_LINE_D =
            uuid("50000000-0000-0000-0000-000000000004");
    private static final UUID REQUIRED_SET_ADMIN =
            uuid("60000000-0000-0000-0000-000000000001");
    private static final UUID REQUIRED_SET_USER =
            uuid("60000000-0000-0000-0000-000000000002");
    private static final UUID REQUIRED_SET_CONTINUOUS =
            uuid("60000000-0000-0000-0000-000000000003");

    private static final Instant FIRST_STATE_TIME =
            Instant.parse("2026-09-01T03:00:00.123400Z");
    private static final Instant SECOND_STATE_TIME =
            Instant.parse("2026-09-01T03:00:00.123456Z");
    private static final Instant THIRD_STATE_TIME =
            Instant.parse("2026-09-01T03:00:01.123456Z");
    private static final Instant FOURTH_STATE_TIME =
            Instant.parse("2026-09-01T03:00:02.123456Z");

    private final LegalEditorialStateFingerprintCalculator calculator =
            new LegalEditorialStateFingerprintCalculator();

    @Test
    void matchesReadableCanonicalAndShaGoldenUsingAnIndependentJcsOracle()
            throws IOException {
        LegalEditorialStateProjection projection = goldenProjection();

        byte[] projectionFixture = readFixture("projection.json");
        assertThat(projectionFixture).doesNotContain((byte) '\r');
        assertThat(projectionFixture[projectionFixture.length - 1]).isEqualTo((byte) '\n');
        JsonNode expectedProjection = parseStrictJson(projectionFixture);
        JsonNode actualProjection = parseStrictJson(
                calculator.projectionJson(projection).getBytes(StandardCharsets.UTF_8));
        assertThat(actualProjection).isEqualTo(expectedProjection);

        byte[] expectedCanonical = withoutSingleTerminalLf(readFixture("canonical.json"));
        byte[] actualCanonical = calculator.canonicalUtf8(projection);
        assertThat(actualCanonical).containsExactly(expectedCanonical);
        assertThat(actualCanonical).containsExactly(
                new JsonCanonicalizer(calculator.projectionJson(projection)).getEncodedUTF8());

        String expectedFingerprint = new String(
                withoutSingleTerminalLf(readFixture("sha256.txt")),
                StandardCharsets.UTF_8);
        assertThat(expectedFingerprint).matches("sha256:[0-9a-f]{64}");
        assertThat(calculator.calculate(projection)).isEqualTo(expectedFingerprint);
        assertThat(expectedFingerprint).isEqualTo("sha256:" + sha256(expectedCanonical));
    }

    @Test
    void goldenReferencesOnlyV27CompatibleCurrentRowsAndReplacementMembers() {
        LegalEditorialStateProjection projection = goldenProjection();
        UUID currentPublicationId = projection.currentPublication().orElseThrow().id();

        for (DocumentSlot slot : projection.documentSlots()) {
            DocumentVersionState version = findDocumentVersion(
                    projection,
                    slot.documentVersionId());
            assertThat(slot.publicationId()).isEqualTo(currentPublicationId);
            assertThat(slot.documentState()).isEqualTo(EstadoVersionLegal.VIGENTE);
            assertThat(version.documentLineId()).isEqualTo(slot.documentLineId());
            assertThat(version.state()).isEqualTo(EstadoVersionLegal.VIGENTE);
        }

        for (RequiredSetPointer pointer : projection.requiredSetPointers()) {
            assertThat(pointer.publicationId()).isEqualTo(currentPublicationId);
            for (RequiredSetMember member : pointer.members()) {
                RequirementVersionState requirement = findRequirementVersion(
                        projection,
                        member.requirementVersionId());
                assertThat(requirement.requirementLineId())
                        .isEqualTo(member.requirementLineId());
                assertThat(requirement.state()).isEqualTo(EstadoVersionLegal.VIGENTE);
                for (DocumentReference reference : member.documentReferences()) {
                    DocumentVersionState document = findDocumentVersion(
                            projection,
                            reference.documentVersionId());
                    assertThat(document.state()).isEqualTo(EstadoVersionLegal.VIGENTE);
                    assertThat(projection.documentSlots())
                            .anySatisfy(slot -> {
                                assertThat(slot.documentVersionId())
                                        .isEqualTo(reference.documentVersionId());
                                assertThat(slot.context()).isEqualTo(pointer.context());
                                assertThat(slot.publicationId()).isEqualTo(pointer.publicationId());
                            });
                }
            }
        }

        for (ReplacementBatch batch : projection.replacementBatches()) {
            for (UUID predecessorId : batch.predecessorDocumentVersionIds()) {
                DocumentVersionState predecessor = findDocumentVersion(
                        projection,
                        predecessorId);
                assertThat(predecessor.state()).isEqualTo(EstadoVersionLegal.REEMPLAZADA);
                assertThat(predecessor.replacementBatchId()).isEqualTo(batch.id());
                assertThat(predecessor.stateChangedAt()).isEqualTo(batch.sealedAt());
            }
            for (ReplacementSuccessor successor : batch.successors()) {
                DocumentVersionState version = findDocumentVersion(
                        projection,
                        successor.documentVersionId());
                assertThat(version.state()).isEqualTo(EstadoVersionLegal.VIGENTE);
                assertThat(version.replacementBatchId()).isEqualTo(batch.id());
                assertThat(version.stateChangedAt()).isEqualTo(batch.sealedAt());
                assertThat(successor.publicationId()).isEqualTo(currentPublicationId);
            }
        }
    }

    @Test
    void normalizesEveryTopLevelAndNestedArrayWithoutChangingTheFingerprint() {
        LegalEditorialStateProjection baseline = goldenProjection();

        assertThat(baseline.documentSlots())
                .filteredOn(slot -> slot.type() == TipoDocumentoLegal.POLITICA_PRIVACIDAD)
                .extracting(DocumentSlot::context)
                .containsExactly(ContextoLegal.REGISTRO, ContextoLegal.USO_CONTINUADO);
        assertThat(baseline.requiredSetPointers())
                .extracting(RequiredSetPointer::context)
                .containsExactly(
                        ContextoLegal.REGISTRO,
                        ContextoLegal.REGISTRO,
                        ContextoLegal.USO_CONTINUADO);
        assertThat(baseline.replacementBatches()).hasSize(2);
        assertThat(baseline.replacementBatches().getFirst().predecessorDocumentVersionIds())
                .hasSize(2);
        assertThat(baseline.replacementBatches().getFirst().successors()).hasSize(2);

        List<DocumentSlot> slots = reversed(baseline.documentSlots());
        List<DocumentVersionState> documentVersions = reversed(baseline.documentVersions());
        List<RequirementVersionState> requirementVersions =
                reversed(baseline.requirementVersions());
        List<RequiredSetPointer> pointers = new ArrayList<>();
        for (RequiredSetPointer pointer : reversed(baseline.requiredSetPointers())) {
            List<RequiredSetMember> members = new ArrayList<>();
            for (RequiredSetMember member : reversed(pointer.members())) {
                members.add(new RequiredSetMember(
                        member.manifestOrdinal(),
                        member.requirementVersionId(),
                        member.requirementLineId(),
                        reversed(member.documentReferences())));
            }
            pointers.add(copyPointer(pointer, members));
        }
        List<ReplacementBatch> batches = new ArrayList<>();
        for (ReplacementBatch batch : reversed(baseline.replacementBatches())) {
            batches.add(new ReplacementBatch(
                    batch.id(),
                    batch.buildState(),
                    batch.createdAt(),
                    batch.sealedAt(),
                    reversed(batch.predecessorDocumentVersionIds()),
                    reversed(batch.successors())));
        }

        LegalEditorialStateProjection permuted = new LegalEditorialStateProjection(
                baseline.fingerprintVersion(),
                baseline.currentPublication(),
                slots,
                pointers,
                documentVersions,
                requirementVersions,
                batches);

        assertThat(permuted).isEqualTo(baseline);
        assertThat(calculator.calculate(permuted)).isEqualTo(calculator.calculate(baseline));
    }

    @Test
    void ordersUuidsByTheirLowercaseTextAcrossBothSignedLongBoundaries() {
        UUID mostSignificantPositive = uuid("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID mostSignificantNegative = uuid("80000000-0000-0000-0000-000000000000");
        UUID leastSignificantPositive = new UUID(0L, Long.MAX_VALUE);
        UUID leastSignificantNegative = new UUID(0L, Long.MIN_VALUE);

        LegalEditorialStateProjection projection = emptyProjection(
                List.of(
                        draftDocument(mostSignificantNegative, uuidWithSuffix(12)),
                        draftDocument(mostSignificantPositive, uuidWithSuffix(11))),
                List.of(new ReplacementBatch(
                        BATCH_ID,
                        EstadoConstruccionLegal.ABIERTO,
                        FIRST_STATE_TIME,
                        null,
                        List.of(leastSignificantNegative, leastSignificantPositive),
                        List.of())));

        assertThat(projection.documentVersions())
                .extracting(DocumentVersionState::documentVersionId)
                .containsExactly(mostSignificantPositive, mostSignificantNegative);
        assertThat(projection.replacementBatches().getFirst().predecessorDocumentVersionIds())
                .containsExactly(leastSignificantPositive, leastSignificantNegative);
    }

    @Test
    void emitsMissingCurrentPublicationAsExplicitNullAndEmptyCollectionsAsArrays()
            throws IOException {
        LegalEditorialStateProjection projection = emptyProjection(List.of(), List.of());

        String readable = calculator.projectionJson(projection);
        String canonical = new String(
                calculator.canonicalUtf8(projection),
                StandardCharsets.UTF_8);

        assertThat(readable).contains("\"currentPublication\":null");
        assertThat(canonical).isEqualTo(
                "{\"currentPublication\":null,\"documentSlots\":[],"
                        + "\"documentVersions\":[],\"fingerprintVersion\":1,"
                        + "\"replacementBatches\":[],\"requiredSetPointers\":[],"
                        + "\"requirementVersions\":[]}");
        assertThat(calculator.canonicalUtf8(projection)).containsExactly(
                new JsonCanonicalizer(readable).getEncodedUTF8());
    }

    @Test
    void matchesIndependentJcsForOpenDraftAndEveryNullableMetadataBranch()
            throws IOException {
        CurrentPublication openPublication = new CurrentPublication(
                PUBLICATION_ID,
                "ordenfix-legal-draft",
                hex('a'),
                EstadoConstruccionLegal.ABIERTO,
                null);
        DocumentVersionState draftDocument = new DocumentVersionState(
                DOCUMENT_LOW,
                DOCUMENT_LINE_LOW,
                PUBLICATION_ID,
                1,
                hex('1'),
                EstadoVersionLegal.BORRADOR,
                null,
                null,
                null);
        RequirementVersionState draftRequirement = new RequirementVersionState(
                REQUIREMENT_A,
                REQUIREMENT_LINE_A,
                PUBLICATION_ID,
                1,
                hex('e'),
                EstadoVersionLegal.BORRADOR,
                null,
                null);
        ReplacementBatch openBatch = new ReplacementBatch(
                BATCH_ID,
                EstadoConstruccionLegal.ABIERTO,
                FIRST_STATE_TIME,
                null,
                List.of(),
                List.of());
        LegalEditorialStateProjection projection = new LegalEditorialStateProjection(
                1,
                Optional.of(openPublication),
                List.of(),
                List.of(),
                List.of(draftDocument),
                List.of(draftRequirement),
                List.of(openBatch));

        String readable = calculator.projectionJson(projection);
        byte[] expected = new JsonCanonicalizer(readable).getEncodedUTF8();

        assertThat(readable)
                .contains("\"buildState\":\"ABIERTO\"")
                .contains("\"sealedAt\":null")
                .contains("\"state\":\"BORRADOR\"")
                .contains("\"stateChangedAt\":null")
                .contains("\"lastReason\":null")
                .contains("\"replacementBatchId\":null");
        assertThat(calculator.canonicalUtf8(projection)).containsExactly(expected);
        assertThat(calculator.calculate(projection)).isEqualTo("sha256:" + sha256(expected));
    }

    @Test
    void copiesEveryArrayDefensivelyAndExposesUnmodifiableLists() {
        List<DocumentReference> references = new ArrayList<>(List.of(
                new DocumentReference(1, DOCUMENT_LOW)));
        RequiredSetMember member = new RequiredSetMember(
                1,
                REQUIREMENT_A,
                REQUIREMENT_LINE_A,
                references);
        List<RequiredSetMember> members = new ArrayList<>(List.of(member));
        RequiredSetPointer pointer = new RequiredSetPointer(
                LocaleLegal.ES_AR,
                ContextoLegal.REGISTRO,
                AudienciaLegal.ADMIN_TITULAR,
                REQUIRED_SET_ADMIN,
                PUBLICATION_ID,
                revision('a'),
                FIRST_STATE_TIME,
                members);
        List<UUID> predecessors = new ArrayList<>(List.of(DOCUMENT_LOW));
        List<ReplacementSuccessor> successors = new ArrayList<>(List.of(
                new ReplacementSuccessor(DOCUMENT_HIGH, PUBLICATION_ID)));
        ReplacementBatch batch = new ReplacementBatch(
                BATCH_ID,
                EstadoConstruccionLegal.SELLADO,
                FIRST_STATE_TIME,
                SECOND_STATE_TIME,
                predecessors,
                successors);
        List<RequiredSetPointer> pointers = new ArrayList<>(List.of(pointer));
        List<ReplacementBatch> batches = new ArrayList<>(List.of(batch));
        LegalEditorialStateProjection projection = new LegalEditorialStateProjection(
                1,
                Optional.empty(),
                List.of(),
                pointers,
                List.of(),
                List.of(),
                batches);

        references.clear();
        members.clear();
        predecessors.clear();
        successors.clear();
        pointers.clear();
        batches.clear();

        assertThat(projection.requiredSetPointers().getFirst().members().getFirst()
                .documentReferences()).hasSize(1);
        assertThat(projection.replacementBatches().getFirst()
                .predecessorDocumentVersionIds()).hasSize(1);
        assertThatThrownBy(() -> projection.requiredSetPointers().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> projection.requiredSetPointers().getFirst().members().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> projection.replacementBatches().getFirst().successors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateKeysAtEveryV27UniquenessBoundary() {
        LegalEditorialStateProjection baseline = goldenProjection();
        DocumentSlot slot = baseline.documentSlots().getFirst();
        RequiredSetPointer pointer = baseline.requiredSetPointers().getFirst();
        DocumentVersionState document = baseline.documentVersions().getFirst();
        RequirementVersionState requirement = baseline.requirementVersions().getFirst();
        ReplacementBatch batch = baseline.replacementBatches().getFirst();

        assertThatThrownBy(() -> projectionWith(baseline, List.of(slot, slot),
                baseline.requiredSetPointers(), baseline.documentVersions(),
                baseline.requirementVersions(), baseline.replacementBatches()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope duplicado");
        DocumentSlot duplicateVersionContext = new DocumentSlot(
                TipoDocumentoLegal.ACUERDO_TRATAMIENTO_DATOS,
                slot.locale(),
                slot.context(),
                slot.documentVersionId(),
                slot.documentLineId(),
                slot.publicationId(),
                slot.documentState());
        assertThatThrownBy(() -> projectionWith(
                baseline,
                appended(baseline.documentSlots(), duplicateVersionContext),
                baseline.requiredSetPointers(),
                baseline.documentVersions(),
                baseline.requirementVersions(),
                baseline.replacementBatches()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versión/contexto duplicada");
        assertThatThrownBy(() -> projectionWith(baseline, baseline.documentSlots(),
                List.of(pointer, pointer), baseline.documentVersions(),
                baseline.requirementVersions(), baseline.replacementBatches()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope duplicado");
        assertThatThrownBy(() -> projectionWith(baseline, baseline.documentSlots(),
                baseline.requiredSetPointers(), List.of(document, document),
                baseline.requirementVersions(), baseline.replacementBatches()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentVersions");
        assertThatThrownBy(() -> projectionWith(baseline, baseline.documentSlots(),
                baseline.requiredSetPointers(), baseline.documentVersions(),
                List.of(requirement, requirement), baseline.replacementBatches()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirementVersions");
        assertThatThrownBy(() -> projectionWith(baseline, baseline.documentSlots(),
                baseline.requiredSetPointers(), baseline.documentVersions(),
                baseline.requirementVersions(), List.of(batch, batch)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replacementBatches");

        RequiredSetMember firstMember = pointer.members().getFirst();
        assertThatThrownBy(() -> copyPointer(pointer, List.of(firstMember, firstMember)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manifestOrdinal");
        RequiredSetMember duplicateRequirementLine = new RequiredSetMember(
                firstMember.manifestOrdinal() + 100,
                uuidWithSuffix(61),
                firstMember.requirementLineId(),
                firstMember.documentReferences());
        assertThatThrownBy(() -> copyPointer(
                pointer,
                appended(pointer.members(), duplicateRequirementLine)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirementLineId duplicado");
        RequiredSetMember duplicateRequirementVersion = new RequiredSetMember(
                firstMember.manifestOrdinal() + 101,
                firstMember.requirementVersionId(),
                uuidWithSuffix(62),
                firstMember.documentReferences());
        assertThatThrownBy(() -> copyPointer(
                pointer,
                appended(pointer.members(), duplicateRequirementVersion)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirementVersionId duplicado");
        DocumentReference firstReference = firstMember.documentReferences().getFirst();
        assertThatThrownBy(() -> new RequiredSetMember(
                firstMember.manifestOrdinal(),
                firstMember.requirementVersionId(),
                firstMember.requirementLineId(),
                List.of(firstReference, firstReference)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentOrdinal");
        DocumentReference duplicateDocumentVersion = new DocumentReference(
                firstReference.documentOrdinal() + 100,
                firstReference.documentVersionId());
        assertThatThrownBy(() -> new RequiredSetMember(
                firstMember.manifestOrdinal(),
                firstMember.requirementVersionId(),
                firstMember.requirementLineId(),
                appended(firstMember.documentReferences(), duplicateDocumentVersion)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentVersionId duplicado");
        assertThatThrownBy(() -> new ReplacementBatch(
                batch.id(), batch.buildState(), batch.createdAt(), batch.sealedAt(),
                List.of(DOCUMENT_LOW, DOCUMENT_LOW), batch.successors()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("predecessor");
        ReplacementSuccessor successor = batch.successors().getFirst();
        assertThatThrownBy(() -> new ReplacementBatch(
                batch.id(), batch.buildState(), batch.createdAt(), batch.sealedAt(),
                batch.predecessorDocumentVersionIds(), List.of(successor, successor)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successors");
    }

    @Test
    void rejectsInvalidV27MetadataMatricesAndNonLowercaseDigests() {
        assertThatCode(() -> new CurrentPublication(
                PUBLICATION_ID, " ", hex('a'), EstadoConstruccionLegal.SELLADO,
                FIRST_STATE_TIME)).doesNotThrowAnyException();
        assertThatCode(() -> new CurrentPublication(
                PUBLICATION_ID, "😀".repeat(120), hex('a'), EstadoConstruccionLegal.SELLADO,
                FIRST_STATE_TIME)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new CurrentPublication(
                PUBLICATION_ID, "x".repeat(121), hex('a'), EstadoConstruccionLegal.SELLADO,
                FIRST_STATE_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VARCHAR(120)");
        assertThatThrownBy(() -> new CurrentPublication(
                PUBLICATION_ID, "release", hex('a'), EstadoConstruccionLegal.ABIERTO,
                FIRST_STATE_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrentPublication(
                PUBLICATION_ID, "release", hex('a'), EstadoConstruccionLegal.SELLADO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentVersionState(
                DOCUMENT_LOW, DOCUMENT_LINE_LOW, PUBLICATION_ID, 1, hex('b'),
                EstadoVersionLegal.BORRADOR, FIRST_STATE_TIME, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentVersionState(
                DOCUMENT_LOW, DOCUMENT_LINE_LOW, PUBLICATION_ID, 1, hex('b'),
                EstadoVersionLegal.REEMPLAZADA, FIRST_STATE_TIME, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentVersionState(
                DOCUMENT_LOW, DOCUMENT_LINE_LOW, PUBLICATION_ID, 1, hex('b'),
                EstadoVersionLegal.RETIRADA, FIRST_STATE_TIME, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequirementVersionState(
                REQUIREMENT_A, REQUIREMENT_LINE_A, PUBLICATION_ID, 1, hex('c'),
                EstadoVersionLegal.RETIRADA, FIRST_STATE_TIME, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new RequirementVersionState(
                REQUIREMENT_A, REQUIREMENT_LINE_A, PUBLICATION_ID, 1, hex('c'),
                EstadoVersionLegal.RETIRADA, FIRST_STATE_TIME, "😀".repeat(1_000)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new RequirementVersionState(
                REQUIREMENT_A, REQUIREMENT_LINE_A, PUBLICATION_ID, 1, hex('c'),
                EstadoVersionLegal.RETIRADA, FIRST_STATE_TIME, "😀".repeat(1_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VARCHAR(1000)");
        assertThatThrownBy(() -> new CurrentPublication(
                PUBLICATION_ID, "release", "A".repeat(64),
                EstadoConstruccionLegal.SELLADO, FIRST_STATE_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase");
        assertThatThrownBy(() -> new RequiredSetPointer(
                LocaleLegal.ES_AR, ContextoLegal.REGISTRO, AudienciaLegal.USER,
                REQUIRED_SET_USER, PUBLICATION_ID, "sha256:" + "A".repeat(64),
                FIRST_STATE_TIME, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase");
    }

    @Test
    void acceptsOnlyFingerprintVersionOne() {
        assertThatThrownBy(() -> new LegalEditorialStateProjection(
                0,
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprintVersion");
        assertThatThrownBy(() -> new LegalEditorialStateProjection(
                2,
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprintVersion");
    }

    @Test
    void rejectsPrecisionBeyondPostgresMicrosecondsForEveryTimestampKind() {
        Instant invalid = Instant.parse("2026-09-01T03:00:00.123456001Z");

        assertThatThrownBy(() -> new CurrentPublication(
                PUBLICATION_ID, "release", hex('a'), EstadoConstruccionLegal.SELLADO, invalid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("microsegundos");
        assertThatThrownBy(() -> new DocumentVersionState(
                DOCUMENT_LOW, DOCUMENT_LINE_LOW, PUBLICATION_ID, 1, hex('b'),
                EstadoVersionLegal.PUBLICADA, invalid, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("microsegundos");
        assertThatThrownBy(() -> new RequirementVersionState(
                REQUIREMENT_A, REQUIREMENT_LINE_A, PUBLICATION_ID, 1, hex('c'),
                EstadoVersionLegal.PUBLICADA, invalid, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("microsegundos");
        assertThatThrownBy(() -> new RequiredSetPointer(
                LocaleLegal.ES_AR, ContextoLegal.REGISTRO, AudienciaLegal.USER,
                REQUIRED_SET_USER, PUBLICATION_ID, revision('d'), invalid, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("microsegundos");
        assertThatThrownBy(() -> new ReplacementBatch(
                BATCH_ID, EstadoConstruccionLegal.ABIERTO, invalid, null,
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("microsegundos");
        assertThatThrownBy(() -> new ReplacementBatch(
                BATCH_ID, EstadoConstruccionLegal.SELLADO, FIRST_STATE_TIME, invalid,
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("microsegundos");
    }

    @Test
    void everyProjectedFieldMutationChangesTheFingerprint() {
        LegalEditorialStateProjection baseline = goldenProjection();
        String expected = calculator.calculate(baseline);
        List<LegalEditorialStateProjection> mutations = contractMutations(baseline);

        assertThat(mutations).isNotEmpty();
        for (int index = 0; index < mutations.size(); index++) {
            assertThat(calculator.calculate(mutations.get(index)))
                    .as("contract mutation %s", index)
                    .isNotEqualTo(expected);
        }
    }

    @Test
    void projectionSurfaceAndJsonContainNoContentPathOperatorSecretOrDeferredRevision() {
        Set<String> forbiddenComponents = Set.of(
                "markdown", "content", "statement", "title", "version", "path",
                "operator", "password", "secret", "documentSetRevision", "manifestCanonical",
                "legalName", "cuit", "legalAddress", "jurisdiction", "supportEmail");
        List<String> components = projectionRecordTypes()
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getName)
                .toList();
        assertThat(components).doesNotContainAnyElementsOf(forbiddenComponents);

        String json = calculator.projectionJson(goldenProjection());
        assertThat(json).doesNotContain(
                "\"markdown\"", "\"content\"", "\"statement\"", "\"title\"",
                "\"path\"", "\"operator\"", "\"password\"", "\"secret\"",
                "\"documentSetRevision\"", "\"manifestCanonical\"", "\"cuit\"");
    }

    private static Stream<Class<?>> projectionRecordTypes() {
        return Stream.of(
                LegalEditorialStateProjection.class,
                CurrentPublication.class,
                DocumentSlot.class,
                RequiredSetPointer.class,
                RequiredSetMember.class,
                DocumentReference.class,
                DocumentVersionState.class,
                RequirementVersionState.class,
                ReplacementBatch.class,
                ReplacementSuccessor.class);
    }

    private static List<LegalEditorialStateProjection> contractMutations(
            LegalEditorialStateProjection baseline) {
        List<LegalEditorialStateProjection> mutations = new ArrayList<>();
        CurrentPublication current = baseline.currentPublication().orElseThrow();
        mutations.add(withCurrent(baseline, Optional.empty()));
        mutations.add(withCurrent(baseline, Optional.of(new CurrentPublication(
                OTHER_PUBLICATION_ID, current.publicationExternalId(), current.manifestSha256(),
                current.buildState(), current.sealedAt()))));
        mutations.add(withCurrent(baseline, Optional.of(new CurrentPublication(
                current.id(), current.publicationExternalId() + "-2", current.manifestSha256(),
                current.buildState(), current.sealedAt()))));
        mutations.add(withCurrent(baseline, Optional.of(new CurrentPublication(
                current.id(), current.publicationExternalId(), hex('9'), current.buildState(),
                current.sealedAt()))));
        mutations.add(withCurrent(baseline, Optional.of(new CurrentPublication(
                current.id(), current.publicationExternalId(), current.manifestSha256(),
                EstadoConstruccionLegal.ABIERTO, null))));
        mutations.add(withCurrent(baseline, Optional.of(new CurrentPublication(
                current.id(), current.publicationExternalId(), current.manifestSha256(),
                current.buildState(), current.sealedAt().plusSeconds(1)))));

        DocumentSlot slot = baseline.documentSlots().getFirst();
        mutations.add(withFirstSlot(baseline, new DocumentSlot(
                TipoDocumentoLegal.ACUERDO_TRATAMIENTO_DATOS, slot.locale(), slot.context(),
                slot.documentVersionId(), slot.documentLineId(), slot.publicationId(),
                slot.documentState())));
        mutations.add(withFirstSlot(baseline, new DocumentSlot(
                slot.type(), slot.locale(), ContextoLegal.ARREPENTIMIENTO,
                slot.documentVersionId(), slot.documentLineId(), slot.publicationId(),
                slot.documentState())));
        mutations.add(withFirstSlot(baseline, new DocumentSlot(
                slot.type(), slot.locale(), slot.context(), uuidWithSuffix(21),
                slot.documentLineId(), slot.publicationId(), slot.documentState())));
        mutations.add(withFirstSlot(baseline, new DocumentSlot(
                slot.type(), slot.locale(), slot.context(), slot.documentVersionId(),
                uuidWithSuffix(22), slot.publicationId(), slot.documentState())));
        mutations.add(withFirstSlot(baseline, new DocumentSlot(
                slot.type(), slot.locale(), slot.context(), slot.documentVersionId(),
                slot.documentLineId(), OTHER_PUBLICATION_ID, slot.documentState())));

        RequiredSetPointer pointer = baseline.requiredSetPointers().getFirst();
        mutations.add(withFirstPointer(baseline, new RequiredSetPointer(
                pointer.locale(), ContextoLegal.ARREPENTIMIENTO, pointer.audience(),
                pointer.requiredSetId(), pointer.publicationId(), pointer.requiredSetRevision(),
                pointer.updatedAt(), pointer.members())));
        mutations.add(withFirstPointer(baseline, new RequiredSetPointer(
                pointer.locale(), ContextoLegal.ARREPENTIMIENTO, AudienciaLegal.USER,
                uuidWithSuffix(23), pointer.publicationId(), pointer.requiredSetRevision(),
                pointer.updatedAt(), pointer.members())));
        mutations.add(withFirstPointer(baseline, new RequiredSetPointer(
                pointer.locale(), pointer.context(), pointer.audience(), uuidWithSuffix(24),
                pointer.publicationId(), pointer.requiredSetRevision(), pointer.updatedAt(),
                pointer.members())));
        mutations.add(withFirstPointer(baseline, new RequiredSetPointer(
                pointer.locale(), pointer.context(), pointer.audience(), pointer.requiredSetId(),
                OTHER_PUBLICATION_ID, pointer.requiredSetRevision(), pointer.updatedAt(),
                pointer.members())));
        mutations.add(withFirstPointer(baseline, new RequiredSetPointer(
                pointer.locale(), pointer.context(), pointer.audience(), pointer.requiredSetId(),
                pointer.publicationId(), revision('8'), pointer.updatedAt(), pointer.members())));
        mutations.add(withFirstPointer(baseline, new RequiredSetPointer(
                pointer.locale(), pointer.context(), pointer.audience(), pointer.requiredSetId(),
                pointer.publicationId(), pointer.requiredSetRevision(),
                pointer.updatedAt().plusSeconds(1), pointer.members())));

        RequiredSetMember member = pointer.members().getFirst();
        List<RequiredSetMember> changedMembers = replaceFirst(pointer.members(),
                new RequiredSetMember(member.manifestOrdinal() + 100,
                        member.requirementVersionId(), member.requirementLineId(),
                        member.documentReferences()));
        mutations.add(withFirstPointer(baseline, copyPointer(pointer, changedMembers)));
        changedMembers = replaceFirst(pointer.members(), new RequiredSetMember(
                member.manifestOrdinal(), uuidWithSuffix(25), member.requirementLineId(),
                member.documentReferences()));
        mutations.add(withFirstPointer(baseline, copyPointer(pointer, changedMembers)));
        changedMembers = replaceFirst(pointer.members(), new RequiredSetMember(
                member.manifestOrdinal(), member.requirementVersionId(), uuidWithSuffix(26),
                member.documentReferences()));
        mutations.add(withFirstPointer(baseline, copyPointer(pointer, changedMembers)));
        DocumentReference reference = member.documentReferences().getFirst();
        RequiredSetMember changedReferenceOrdinal = new RequiredSetMember(
                member.manifestOrdinal(), member.requirementVersionId(), member.requirementLineId(),
                replaceFirst(member.documentReferences(), new DocumentReference(
                        reference.documentOrdinal() + 100, reference.documentVersionId())));
        mutations.add(withFirstPointer(baseline, copyPointer(pointer,
                replaceFirst(pointer.members(), changedReferenceOrdinal))));
        RequiredSetMember changedReferenceVersion = new RequiredSetMember(
                member.manifestOrdinal(), member.requirementVersionId(), member.requirementLineId(),
                replaceFirst(member.documentReferences(), new DocumentReference(
                        reference.documentOrdinal(), uuidWithSuffix(27))));
        mutations.add(withFirstPointer(baseline, copyPointer(pointer,
                replaceFirst(pointer.members(), changedReferenceVersion))));

        DocumentVersionState document = baseline.documentVersions().getFirst();
        mutations.add(withFirstDocument(baseline, copyDocument(document,
                uuidWithSuffix(31), document.documentLineId(), document.introductionPublicationId(),
                document.lineageOrdinal(), document.sha256(), document.state(),
                document.stateChangedAt(), document.lastReason(), document.replacementBatchId())));
        mutations.add(withFirstDocument(baseline, copyDocument(document,
                document.documentVersionId(), uuidWithSuffix(32),
                document.introductionPublicationId(), document.lineageOrdinal(), document.sha256(),
                document.state(), document.stateChangedAt(), document.lastReason(),
                document.replacementBatchId())));
        mutations.add(withFirstDocument(baseline, copyDocument(document,
                document.documentVersionId(), document.documentLineId(),
                document.introductionPublicationId().equals(PUBLICATION_ID)
                        ? OTHER_PUBLICATION_ID
                        : PUBLICATION_ID,
                document.lineageOrdinal(), document.sha256(), document.state(),
                document.stateChangedAt(), document.lastReason(), document.replacementBatchId())));
        mutations.add(withFirstDocument(baseline, copyDocument(document,
                document.documentVersionId(), document.documentLineId(),
                document.introductionPublicationId(), document.lineageOrdinal() + 1,
                document.sha256(), document.state(), document.stateChangedAt(),
                document.lastReason(), document.replacementBatchId())));
        mutations.add(withFirstDocument(baseline, copyDocument(document,
                document.documentVersionId(), document.documentLineId(),
                document.introductionPublicationId(), document.lineageOrdinal(), hex('7'),
                document.state(), document.stateChangedAt(), document.lastReason(),
                document.replacementBatchId())));
        mutations.add(withFirstDocument(baseline, copyDocument(document,
                document.documentVersionId(), document.documentLineId(),
                document.introductionPublicationId(), document.lineageOrdinal(), document.sha256(),
                EstadoVersionLegal.RETIRADA, document.stateChangedAt(), "Motivo cambiado", null)));
        mutations.add(withFirstDocument(baseline, copyDocument(document,
                document.documentVersionId(), document.documentLineId(),
                document.introductionPublicationId(), document.lineageOrdinal(), document.sha256(),
                document.state(), document.stateChangedAt().plusSeconds(1), document.lastReason(),
                document.replacementBatchId())));
        mutations.add(withFirstDocument(baseline, copyDocument(document,
                document.documentVersionId(), document.documentLineId(),
                document.introductionPublicationId(), document.lineageOrdinal(), document.sha256(),
                document.state(), document.stateChangedAt(), document.lastReason(),
                uuidWithSuffix(33))));

        RequirementVersionState requirement = baseline.requirementVersions().getFirst();
        mutations.add(withFirstRequirement(baseline, copyRequirement(requirement,
                uuidWithSuffix(41), requirement.requirementLineId(),
                requirement.introductionPublicationId(), requirement.lineageOrdinal(),
                requirement.statementSha256(), requirement.state(), requirement.stateChangedAt(),
                requirement.lastReason())));
        mutations.add(withFirstRequirement(baseline, copyRequirement(requirement,
                requirement.requirementVersionId(), uuidWithSuffix(42),
                requirement.introductionPublicationId(), requirement.lineageOrdinal(),
                requirement.statementSha256(), requirement.state(), requirement.stateChangedAt(),
                requirement.lastReason())));
        mutations.add(withFirstRequirement(baseline, copyRequirement(requirement,
                requirement.requirementVersionId(), requirement.requirementLineId(),
                OTHER_PUBLICATION_ID, requirement.lineageOrdinal(), requirement.statementSha256(),
                requirement.state(), requirement.stateChangedAt(), requirement.lastReason())));
        mutations.add(withFirstRequirement(baseline, copyRequirement(requirement,
                requirement.requirementVersionId(), requirement.requirementLineId(),
                requirement.introductionPublicationId(), requirement.lineageOrdinal() + 1,
                requirement.statementSha256(), requirement.state(), requirement.stateChangedAt(),
                requirement.lastReason())));
        mutations.add(withFirstRequirement(baseline, copyRequirement(requirement,
                requirement.requirementVersionId(), requirement.requirementLineId(),
                requirement.introductionPublicationId(), requirement.lineageOrdinal(), hex('6'),
                requirement.state(), requirement.stateChangedAt(), requirement.lastReason())));
        mutations.add(withFirstRequirement(baseline, copyRequirement(requirement,
                requirement.requirementVersionId(), requirement.requirementLineId(),
                requirement.introductionPublicationId(), requirement.lineageOrdinal(),
                requirement.statementSha256(), EstadoVersionLegal.RETIRADA,
                requirement.stateChangedAt(), "Requisito retirado")));
        mutations.add(withFirstRequirement(baseline, copyRequirement(requirement,
                requirement.requirementVersionId(), requirement.requirementLineId(),
                requirement.introductionPublicationId(), requirement.lineageOrdinal(),
                requirement.statementSha256(), requirement.state(),
                requirement.stateChangedAt().plusSeconds(1), requirement.lastReason())));

        ReplacementBatch batch = baseline.replacementBatches().getFirst();
        mutations.add(withFirstBatch(baseline, new ReplacementBatch(
                uuidWithSuffix(51), batch.buildState(), batch.createdAt(), batch.sealedAt(),
                batch.predecessorDocumentVersionIds(), batch.successors())));
        mutations.add(withFirstBatch(baseline, new ReplacementBatch(
                batch.id(), EstadoConstruccionLegal.ABIERTO, batch.createdAt(), null,
                batch.predecessorDocumentVersionIds(), batch.successors())));
        mutations.add(withFirstBatch(baseline, new ReplacementBatch(
                batch.id(), batch.buildState(), batch.createdAt().plusSeconds(1), batch.sealedAt(),
                batch.predecessorDocumentVersionIds(), batch.successors())));
        mutations.add(withFirstBatch(baseline, new ReplacementBatch(
                batch.id(), batch.buildState(), batch.createdAt(), batch.sealedAt().plusSeconds(1),
                batch.predecessorDocumentVersionIds(), batch.successors())));
        mutations.add(withFirstBatch(baseline, new ReplacementBatch(
                batch.id(), batch.buildState(), batch.createdAt(), batch.sealedAt(),
                List.of(uuidWithSuffix(52)), batch.successors())));
        ReplacementSuccessor successor = batch.successors().getFirst();
        mutations.add(withFirstBatch(baseline, new ReplacementBatch(
                batch.id(), batch.buildState(), batch.createdAt(), batch.sealedAt(),
                batch.predecessorDocumentVersionIds(), List.of(new ReplacementSuccessor(
                        uuidWithSuffix(53), successor.publicationId())))));
        mutations.add(withFirstBatch(baseline, new ReplacementBatch(
                batch.id(), batch.buildState(), batch.createdAt(), batch.sealedAt(),
                batch.predecessorDocumentVersionIds(), List.of(new ReplacementSuccessor(
                        successor.documentVersionId(), OTHER_PUBLICATION_ID)))));
        return mutations;
    }

    private static LegalEditorialStateProjection goldenProjection() {
        CurrentPublication publication = new CurrentPublication(
                PUBLICATION_ID,
                "ordenfix-legal-v1 \"estable\" \\ 😀",
                hex('a'),
                EstadoConstruccionLegal.SELLADO,
                FIRST_STATE_TIME);
        DocumentSlot privacyRegistration = new DocumentSlot(
                TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                LocaleLegal.ES_AR,
                ContextoLegal.REGISTRO,
                DOCUMENT_HIGH,
                DOCUMENT_LINE_LOW,
                PUBLICATION_ID,
                EstadoVersionLegal.VIGENTE);
        DocumentSlot privacyContinuous = new DocumentSlot(
                TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                LocaleLegal.ES_AR,
                ContextoLegal.USO_CONTINUADO,
                DOCUMENT_SECOND_SUCCESSOR,
                DOCUMENT_LINE_HIGH,
                PUBLICATION_ID,
                EstadoVersionLegal.VIGENTE);
        DocumentSlot termsRegistration = new DocumentSlot(
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                LocaleLegal.ES_AR,
                ContextoLegal.REGISTRO,
                TERMS_SUCCESSOR,
                TERMS_DOCUMENT_LINE,
                PUBLICATION_ID,
                EstadoVersionLegal.VIGENTE);
        DocumentSlot termsContinuous = new DocumentSlot(
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                LocaleLegal.ES_AR,
                ContextoLegal.USO_CONTINUADO,
                TERMS_SUCCESSOR,
                TERMS_DOCUMENT_LINE,
                PUBLICATION_ID,
                EstadoVersionLegal.VIGENTE);
        RequiredSetMember registrationAdminMember = new RequiredSetMember(
                10,
                REQUIREMENT_B,
                REQUIREMENT_LINE_B,
                List.of(
                        new DocumentReference(10, TERMS_SUCCESSOR),
                        new DocumentReference(2, DOCUMENT_HIGH)));
        RequiredSetMember registrationSharedMember = new RequiredSetMember(
                2,
                REQUIREMENT_A,
                REQUIREMENT_LINE_A,
                List.of(new DocumentReference(1, DOCUMENT_HIGH)));
        RequiredSetMember continuousMember = new RequiredSetMember(
                20,
                REQUIREMENT_C,
                REQUIREMENT_LINE_C,
                List.of(
                        new DocumentReference(10, TERMS_SUCCESSOR),
                        new DocumentReference(2, DOCUMENT_SECOND_SUCCESSOR)));
        RequiredSetPointer admin = new RequiredSetPointer(
                LocaleLegal.ES_AR,
                ContextoLegal.REGISTRO,
                AudienciaLegal.ADMIN_TITULAR,
                REQUIRED_SET_ADMIN,
                PUBLICATION_ID,
                revision('c'),
                THIRD_STATE_TIME,
                List.of(registrationAdminMember, registrationSharedMember));
        RequiredSetPointer user = new RequiredSetPointer(
                LocaleLegal.ES_AR,
                ContextoLegal.REGISTRO,
                AudienciaLegal.USER,
                REQUIRED_SET_USER,
                PUBLICATION_ID,
                revision('d'),
                THIRD_STATE_TIME,
                List.of(registrationSharedMember));
        RequiredSetPointer continuous = new RequiredSetPointer(
                LocaleLegal.ES_AR,
                ContextoLegal.USO_CONTINUADO,
                AudienciaLegal.USER,
                REQUIRED_SET_CONTINUOUS,
                PUBLICATION_ID,
                revision('b'),
                FOURTH_STATE_TIME,
                List.of(continuousMember));
        DocumentVersionState replacedRegistration = new DocumentVersionState(
                DOCUMENT_LOW,
                DOCUMENT_LINE_LOW,
                OTHER_PUBLICATION_ID,
                1,
                hex('1'),
                EstadoVersionLegal.REEMPLAZADA,
                THIRD_STATE_TIME,
                null,
                BATCH_ID);
        DocumentVersionState replacedContinuous = new DocumentVersionState(
                DOCUMENT_SECOND_PREDECESSOR,
                DOCUMENT_LINE_HIGH,
                OTHER_PUBLICATION_ID,
                1,
                hex('2'),
                EstadoVersionLegal.REEMPLAZADA,
                THIRD_STATE_TIME,
                null,
                BATCH_ID);
        DocumentVersionState currentRegistration = new DocumentVersionState(
                DOCUMENT_HIGH,
                DOCUMENT_LINE_LOW,
                PUBLICATION_ID,
                2,
                hex('3'),
                EstadoVersionLegal.VIGENTE,
                THIRD_STATE_TIME,
                null,
                BATCH_ID);
        DocumentVersionState currentContinuous = new DocumentVersionState(
                DOCUMENT_SECOND_SUCCESSOR,
                DOCUMENT_LINE_HIGH,
                PUBLICATION_ID,
                2,
                hex('4'),
                EstadoVersionLegal.VIGENTE,
                THIRD_STATE_TIME,
                null,
                BATCH_ID);
        DocumentVersionState replacedTerms = new DocumentVersionState(
                TERMS_PREDECESSOR,
                TERMS_DOCUMENT_LINE,
                OTHER_PUBLICATION_ID,
                1,
                hex('5'),
                EstadoVersionLegal.REEMPLAZADA,
                FOURTH_STATE_TIME,
                null,
                SECOND_BATCH_ID);
        DocumentVersionState currentTerms = new DocumentVersionState(
                TERMS_SUCCESSOR,
                TERMS_DOCUMENT_LINE,
                PUBLICATION_ID,
                2,
                hex('6'),
                EstadoVersionLegal.VIGENTE,
                FOURTH_STATE_TIME,
                null,
                SECOND_BATCH_ID);
        DocumentVersionState retiredDocument = new DocumentVersionState(
                RETIRED_DOCUMENT,
                RETIRED_DOCUMENT_LINE,
                OTHER_PUBLICATION_ID,
                1,
                hex('7'),
                EstadoVersionLegal.RETIRADA,
                SECOND_STATE_TIME,
                "Retiro documental \"operativo\"\nCierre 😀",
                null);
        RequirementVersionState registrationShared = new RequirementVersionState(
                REQUIREMENT_A,
                REQUIREMENT_LINE_A,
                PUBLICATION_ID,
                1,
                hex('e'),
                EstadoVersionLegal.VIGENTE,
                THIRD_STATE_TIME,
                null);
        RequirementVersionState registrationAdmin = new RequirementVersionState(
                REQUIREMENT_B,
                REQUIREMENT_LINE_B,
                PUBLICATION_ID,
                1,
                hex('f'),
                EstadoVersionLegal.VIGENTE,
                THIRD_STATE_TIME,
                null);
        RequirementVersionState continuousRequirement = new RequirementVersionState(
                REQUIREMENT_C,
                REQUIREMENT_LINE_C,
                PUBLICATION_ID,
                1,
                hex('c'),
                EstadoVersionLegal.VIGENTE,
                FOURTH_STATE_TIME,
                null);
        RequirementVersionState retiredRequirement = new RequirementVersionState(
                REQUIREMENT_D,
                REQUIREMENT_LINE_D,
                OTHER_PUBLICATION_ID,
                1,
                hex('d'),
                EstadoVersionLegal.RETIRADA,
                SECOND_STATE_TIME,
                "Retiro de requisito \"operativo\"\nCierre 😀");
        ReplacementBatch firstBatch = new ReplacementBatch(
                BATCH_ID,
                EstadoConstruccionLegal.SELLADO,
                SECOND_STATE_TIME,
                THIRD_STATE_TIME,
                List.of(DOCUMENT_LOW, DOCUMENT_SECOND_PREDECESSOR),
                List.of(
                        new ReplacementSuccessor(DOCUMENT_SECOND_SUCCESSOR, PUBLICATION_ID),
                        new ReplacementSuccessor(DOCUMENT_HIGH, PUBLICATION_ID)));
        ReplacementBatch secondBatch = new ReplacementBatch(
                SECOND_BATCH_ID,
                EstadoConstruccionLegal.SELLADO,
                THIRD_STATE_TIME,
                FOURTH_STATE_TIME,
                List.of(TERMS_PREDECESSOR),
                List.of(new ReplacementSuccessor(TERMS_SUCCESSOR, PUBLICATION_ID)));
        return new LegalEditorialStateProjection(
                1,
                Optional.of(publication),
                List.of(
                        termsContinuous,
                        privacyContinuous,
                        termsRegistration,
                        privacyRegistration),
                List.of(continuous, user, admin),
                List.of(
                        retiredDocument,
                        currentTerms,
                        replacedTerms,
                        currentContinuous,
                        currentRegistration,
                        replacedContinuous,
                        replacedRegistration),
                List.of(
                        retiredRequirement,
                        continuousRequirement,
                        registrationAdmin,
                        registrationShared),
                List.of(secondBatch, firstBatch));
    }

    private static LegalEditorialStateProjection emptyProjection(
            List<DocumentVersionState> documents,
            List<ReplacementBatch> batches) {
        return new LegalEditorialStateProjection(
                1, Optional.empty(), List.of(), List.of(), documents, List.of(), batches);
    }

    private static DocumentVersionState draftDocument(UUID versionId, UUID lineId) {
        return new DocumentVersionState(
                versionId, lineId, PUBLICATION_ID, 1, hex('5'),
                EstadoVersionLegal.BORRADOR, null, null, null);
    }

    private static DocumentVersionState findDocumentVersion(
            LegalEditorialStateProjection projection,
            UUID versionId) {
        return projection.documentVersions().stream()
                .filter(version -> version.documentVersionId().equals(versionId))
                .findFirst()
                .orElseThrow();
    }

    private static RequirementVersionState findRequirementVersion(
            LegalEditorialStateProjection projection,
            UUID versionId) {
        return projection.requirementVersions().stream()
                .filter(version -> version.requirementVersionId().equals(versionId))
                .findFirst()
                .orElseThrow();
    }

    private static LegalEditorialStateProjection projectionWith(
            LegalEditorialStateProjection source,
            List<DocumentSlot> slots,
            List<RequiredSetPointer> pointers,
            List<DocumentVersionState> documents,
            List<RequirementVersionState> requirements,
            List<ReplacementBatch> batches) {
        return new LegalEditorialStateProjection(
                source.fingerprintVersion(), source.currentPublication(), slots, pointers,
                documents, requirements, batches);
    }

    private static LegalEditorialStateProjection withCurrent(
            LegalEditorialStateProjection source,
            Optional<CurrentPublication> current) {
        return new LegalEditorialStateProjection(
                source.fingerprintVersion(), current, source.documentSlots(),
                source.requiredSetPointers(), source.documentVersions(),
                source.requirementVersions(), source.replacementBatches());
    }

    private static LegalEditorialStateProjection withFirstSlot(
            LegalEditorialStateProjection source,
            DocumentSlot slot) {
        return projectionWith(source, replaceFirst(source.documentSlots(), slot),
                source.requiredSetPointers(), source.documentVersions(),
                source.requirementVersions(), source.replacementBatches());
    }

    private static LegalEditorialStateProjection withFirstPointer(
            LegalEditorialStateProjection source,
            RequiredSetPointer pointer) {
        return projectionWith(source, source.documentSlots(),
                replaceFirst(source.requiredSetPointers(), pointer), source.documentVersions(),
                source.requirementVersions(), source.replacementBatches());
    }

    private static LegalEditorialStateProjection withFirstDocument(
            LegalEditorialStateProjection source,
            DocumentVersionState document) {
        return projectionWith(source, source.documentSlots(), source.requiredSetPointers(),
                replaceFirst(source.documentVersions(), document), source.requirementVersions(),
                source.replacementBatches());
    }

    private static LegalEditorialStateProjection withFirstRequirement(
            LegalEditorialStateProjection source,
            RequirementVersionState requirement) {
        return projectionWith(source, source.documentSlots(), source.requiredSetPointers(),
                source.documentVersions(), replaceFirst(source.requirementVersions(), requirement),
                source.replacementBatches());
    }

    private static LegalEditorialStateProjection withFirstBatch(
            LegalEditorialStateProjection source,
            ReplacementBatch batch) {
        return projectionWith(source, source.documentSlots(), source.requiredSetPointers(),
                source.documentVersions(), source.requirementVersions(),
                replaceFirst(source.replacementBatches(), batch));
    }

    private static RequiredSetPointer copyPointer(
            RequiredSetPointer source,
            List<RequiredSetMember> members) {
        return new RequiredSetPointer(
                source.locale(), source.context(), source.audience(), source.requiredSetId(),
                source.publicationId(), source.requiredSetRevision(), source.updatedAt(), members);
    }

    private static DocumentVersionState copyDocument(
            DocumentVersionState source,
            UUID versionId,
            UUID lineId,
            UUID publicationId,
            int ordinal,
            String sha256,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String reason,
            UUID batchId) {
        return new DocumentVersionState(
                versionId, lineId, publicationId, ordinal, sha256, state,
                stateChangedAt, reason, batchId);
    }

    private static RequirementVersionState copyRequirement(
            RequirementVersionState source,
            UUID versionId,
            UUID lineId,
            UUID publicationId,
            int ordinal,
            String statementSha256,
            EstadoVersionLegal state,
            Instant stateChangedAt,
            String reason) {
        return new RequirementVersionState(
                versionId, lineId, publicationId, ordinal, statementSha256,
                state, stateChangedAt, reason);
    }

    private static <T> List<T> replaceFirst(List<T> source, T replacement) {
        List<T> copy = new ArrayList<>(source);
        copy.set(0, replacement);
        return copy;
    }

    private static <T> List<T> appended(List<T> source, T appended) {
        List<T> copy = new ArrayList<>(source);
        copy.add(appended);
        return copy;
    }

    private static <T> List<T> reversed(List<T> source) {
        List<T> copy = new ArrayList<>(source);
        Collections.reverse(copy);
        return copy;
    }

    private static String revision(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }

    private static String hex(char digit) {
        return String.valueOf(digit).repeat(64);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static UUID uuidWithSuffix(long suffix) {
        return new UUID(0L, suffix);
    }

    private static JsonNode parseStrictJson(byte[] bytes) {
        LegalManifestValidation<StrictJsonReader.StrictJsonDocument> parsed =
                new StrictJsonReader().read(bytes);
        assertThat(parsed.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(parsed.issues()).isEmpty();
        return parsed.value().orElseThrow().root();
    }

    private static byte[] withoutSingleTerminalLf(byte[] fixture) {
        assertThat(fixture).isNotEmpty();
        assertThat(fixture[fixture.length - 1]).isEqualTo((byte) '\n');
        if (fixture.length > 1) {
            assertThat(fixture[fixture.length - 2]).isNotEqualTo((byte) '\n');
        }
        return Arrays.copyOf(fixture, fixture.length - 1);
    }

    private static byte[] readFixture(String fileName) {
        try (InputStream input = LegalEditorialStateFingerprintCalculatorTest.class
                .getResourceAsStream(FIXTURE_ROOT + fileName)) {
            return Objects.requireNonNull(input, fileName).readAllBytes();
        } catch (IOException exception) {
            throw new AssertionError("No se pudo leer el fixture " + fileName, exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
