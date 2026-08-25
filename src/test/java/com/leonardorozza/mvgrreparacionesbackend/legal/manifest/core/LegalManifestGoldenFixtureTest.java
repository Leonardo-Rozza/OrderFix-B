package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedReleaseReader.DocumentSource;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.ConfinedReleaseReader.ReleaseDocuments;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestParser.ParsedManifest;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewRecord;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewStatus;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auditoria autocontenida de la fixture positiva usada por el validador legal completo.
 *
 * <p>No reemplaza las pruebas negativas del contrato. Congela que el golden realmente representa
 * los once tipos documentales, los seis requisitos y los ocho scopes de la matriz aprobada, y que
 * sus bytes atraviesan las fronteras estrictas anteriores al validador cruzado.</p>
 */
class LegalManifestGoldenFixtureTest {

    private static final String RESOURCE_ROOT = "/legal/manifest/release-valid-v1/";
    private static final String MANIFEST_SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";

    private static final List<String> DOCUMENT_KEYS = List.of(
            "terminos",
            "privacidad",
            "tratamiento-datos",
            "condiciones-pro",
            "cancelaciones-reembolsos",
            "cierre-cuenta",
            "aviso-clientes-taller",
            "terminos-usuario",
            "aviso-privacidad-usuario",
            "compromiso-confidencialidad",
            "atestacion-datos-cliente");

    private static final List<String> REQUIREMENT_KEYS = List.of(
            "admin-registration",
            "employee-first-login",
            "pro-checkout",
            "customer-photo-attestation",
            "customer-credential-attestation",
            "account-closure");

    private static final Map<Scope, Set<TipoDocumentoLegal>> EXPECTED_COVERAGE = Map.ofEntries(
            Map.entry(
                    new Scope(ContextoLegal.REGISTRO, AudienciaLegal.ADMIN_TITULAR),
                    Set.of(
                            TipoDocumentoLegal.TERMINOS_SERVICIO,
                            TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                            TipoDocumentoLegal.ACUERDO_TRATAMIENTO_DATOS)),
            Map.entry(
                    new Scope(ContextoLegal.PRIMER_INGRESO_EMPLEADO, AudienciaLegal.USER),
                    Set.of(
                            TipoDocumentoLegal.TERMINOS_USUARIO,
                            TipoDocumentoLegal.AVISO_PRIVACIDAD_USUARIO,
                            TipoDocumentoLegal.COMPROMISO_CONFIDENCIALIDAD)),
            Map.entry(
                    new Scope(ContextoLegal.CONTRATACION_PRO, AudienciaLegal.ADMIN_TITULAR),
                    Set.of(
                            TipoDocumentoLegal.TERMINOS_SERVICIO,
                            TipoDocumentoLegal.CONDICIONES_PRO,
                            TipoDocumentoLegal.POLITICA_CANCELACIONES_REEMBOLSOS)),
            Map.entry(
                    new Scope(ContextoLegal.ATESTACION_FOTOS, AudienciaLegal.ADMIN_TITULAR),
                    Set.of(
                            TipoDocumentoLegal.AVISO_CLIENTES_TALLER,
                            TipoDocumentoLegal.ATESTACION_DATOS_CLIENTE)),
            Map.entry(
                    new Scope(ContextoLegal.ATESTACION_FOTOS, AudienciaLegal.USER),
                    Set.of(
                            TipoDocumentoLegal.AVISO_CLIENTES_TALLER,
                            TipoDocumentoLegal.ATESTACION_DATOS_CLIENTE)),
            Map.entry(
                    new Scope(ContextoLegal.ATESTACION_CREDENCIALES, AudienciaLegal.ADMIN_TITULAR),
                    Set.of(
                            TipoDocumentoLegal.AVISO_CLIENTES_TALLER,
                            TipoDocumentoLegal.ATESTACION_DATOS_CLIENTE)),
            Map.entry(
                    new Scope(ContextoLegal.ATESTACION_CREDENCIALES, AudienciaLegal.USER),
                    Set.of(
                            TipoDocumentoLegal.AVISO_CLIENTES_TALLER,
                            TipoDocumentoLegal.ATESTACION_DATOS_CLIENTE)),
            Map.entry(
                    new Scope(ContextoLegal.CIERRE_CUENTA, AudienciaLegal.ADMIN_TITULAR),
                    Set.of(TipoDocumentoLegal.POLITICA_CIERRE_CUENTA)));

    @Test
    void goldenAtraviesaLecturaParseoBytesMarkdownYMarcadores() throws URISyntaxException {
        Path manifestPath = Path.of(Objects.requireNonNull(
                getClass().getResource(RESOURCE_ROOT + ConfinedReleaseReader.MANIFEST_FILENAME))
                .toURI());
        ConfinedReleaseReader reader = new ConfinedReleaseReader();
        var manifestSource = requirePass(
                reader.readManifest(manifestPath),
                "lectura confinada del manifiesto golden");
        ParsedManifest parsed = requirePass(
                new LegalManifestParser().parse(manifestSource.bytes()),
                "parseo estricto del manifiesto golden");
        ReleaseDocuments releaseDocuments = requirePass(
                reader.readDocuments(manifestSource, parsed.manifest()),
                "lectura confinada de los Markdown golden");

        assertThat(parsed.manifestSha256()).isEqualTo(MANIFEST_SHA256);
        assertThat(releaseDocuments.documents())
                .extracting(DocumentSource::key)
                .containsExactlyElementsOf(DOCUMENT_KEYS);

        Map<String, DocumentEntry> declarations = parsed.manifest().documents().stream()
                .collect(Collectors.toMap(DocumentEntry::key, Function.identity()));
        CanonicalTextValidator canonicalTextValidator = new CanonicalTextValidator();
        LegalMarkdownValidator markdownValidator = new LegalMarkdownValidator();
        LegalEditorialMarkerValidator markerValidator = new LegalEditorialMarkerValidator();

        for (DocumentSource source : releaseDocuments.documents()) {
            DocumentEntry declaration = declarations.get(source.key());
            assertThat(declaration).as("declaracion de %s", source.key()).isNotNull();
            assertThat(source.source()).isEqualTo(declaration.source());

            CanonicalTextValidator.CanonicalText text = requirePass(
                    canonicalTextValidator.validate(
                            source.bytes(), declaration.sha256(), source.source()),
                    "bytes canonicos de " + source.key());
            assertThat(requirePass(
                    markdownValidator.validate(text.text(), source.source()),
                    "Markdown publicable de " + source.key()).title())
                    .isEqualTo(source.key());
            requirePass(
                    markerValidator.validate(text.text(), source.source()),
                    "ausencia de marcadores en " + source.key());
        }

        for (RequirementEntry requirement : parsed.manifest().requirements()) {
            String location = "requirements/" + requirement.key() + "#statement";
            CanonicalTextValidator.CanonicalText statement = requirePass(
                    canonicalTextValidator.validate(
                            requirement.statement(), requirement.statementSha256(), location),
                    "digest de afirmacion " + requirement.key());
            requirePass(
                    markerValidator.validate(statement.text(), location),
                    "ausencia de marcadores en afirmacion " + requirement.key());
        }

        publisherFields(parsed.manifest()).forEach((location, content) -> requirePass(
                markerValidator.validate(content, location),
                "ausencia de marcadores en " + location));
    }

    @Test
    void goldenCongelaConteosReferenciasScopesYMatrizCompleta() throws URISyntaxException {
        LegalManifestV1 manifest = parseGolden();

        assertThat(manifest.schemaVersion()).isEqualTo(1);
        assertThat(manifest.publicationId()).isEqualTo("release-valid-v1");
        assertThat(manifest.locale()).isEqualTo(LocaleLegal.ES_AR);
        assertApproved(manifest.review().legal());
        assertApproved(manifest.review().accounting());

        assertThat(manifest.documents()).hasSize(11);
        assertThat(manifest.documents())
                .extracting(DocumentEntry::key)
                .containsExactlyElementsOf(DOCUMENT_KEYS);
        assertThat(manifest.documents())
                .extracting(DocumentEntry::type)
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(TipoDocumentoLegal.class));
        assertThat(manifest.documents())
                .extracting(DocumentEntry::locale)
                .containsOnly(LocaleLegal.ES_AR);
        assertThat(manifest.documents())
                .extracting(DocumentEntry::source)
                .doesNotHaveDuplicates();

        Set<ContextoLegal> declaredContexts = manifest.documents().stream()
                .flatMap(document -> document.contexts().stream())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ContextoLegal.class)));
        assertThat(declaredContexts).isEqualTo(EnumSet.allOf(ContextoLegal.class));

        assertThat(manifest.requirements()).hasSize(6);
        assertThat(manifest.requirements())
                .extracting(RequirementEntry::key)
                .containsExactlyElementsOf(REQUIREMENT_KEYS);
        assertThat(manifest.requirements())
                .extracting(RequirementEntry::key)
                .doesNotHaveDuplicates();
        assertThat(manifest.requirements()).allMatch(RequirementEntry::required);

        Map<String, DocumentEntry> documents = manifest.documents().stream()
                .collect(Collectors.toMap(DocumentEntry::key, Function.identity()));
        Set<String> referencedDocuments = new LinkedHashSet<>();
        Set<AudienciaLegal> declaredRoles = EnumSet.noneOf(AudienciaLegal.class);
        Set<Scope> scopes = new LinkedHashSet<>();
        for (RequirementEntry requirement : manifest.requirements()) {
            assertThat(requirement.documents()).isNotEmpty().doesNotHaveDuplicates();
            assertThat(requirement.roles()).isNotEmpty().doesNotHaveDuplicates();
            declaredRoles.addAll(requirement.roles());
            requirement.roles().forEach(role -> scopes.add(new Scope(requirement.context(), role)));

            for (String documentKey : requirement.documents()) {
                DocumentEntry document = documents.get(documentKey);
                assertThat(document)
                        .as("referencia %s de %s", documentKey, requirement.key())
                        .isNotNull();
                assertThat(document.contexts())
                        .as("contextos de %s para %s", documentKey, requirement.key())
                        .contains(requirement.context());
                assertThat(document.locale()).isEqualTo(manifest.locale());
                referencedDocuments.add(documentKey);
            }
        }

        assertThat(declaredRoles).isEqualTo(EnumSet.allOf(AudienciaLegal.class));
        assertThat(referencedDocuments).containsExactlyInAnyOrderElementsOf(documents.keySet());
        assertThat(scopes).hasSize(8).containsExactlyInAnyOrderElementsOf(EXPECTED_COVERAGE.keySet());
        assertThat(aggregateRequiredCoverage(manifest, documents)).isEqualTo(EXPECTED_COVERAGE);
    }

    private LegalManifestV1 parseGolden() throws URISyntaxException {
        Path manifestPath = Path.of(Objects.requireNonNull(
                getClass().getResource(RESOURCE_ROOT + ConfinedReleaseReader.MANIFEST_FILENAME))
                .toURI());
        ConfinedReleaseReader reader = new ConfinedReleaseReader();
        var source = requirePass(
                reader.readManifest(manifestPath),
                "lectura confinada del manifiesto golden");
        return requirePass(
                new LegalManifestParser().parse(source.bytes()),
                "parseo estricto del manifiesto golden").manifest();
    }

    private static Map<Scope, Set<TipoDocumentoLegal>> aggregateRequiredCoverage(
            LegalManifestV1 manifest,
            Map<String, DocumentEntry> documents) {
        Map<Scope, Set<TipoDocumentoLegal>> coverage = new LinkedHashMap<>();
        for (RequirementEntry requirement : manifest.requirements()) {
            if (!requirement.required()) {
                continue;
            }
            for (AudienciaLegal role : requirement.roles()) {
                Scope scope = new Scope(requirement.context(), role);
                Set<TipoDocumentoLegal> types = coverage.computeIfAbsent(
                        scope,
                        ignored -> EnumSet.noneOf(TipoDocumentoLegal.class));
                requirement.documents().stream()
                        .map(documents::get)
                        .filter(Objects::nonNull)
                        .map(DocumentEntry::type)
                        .forEach(types::add);
            }
        }
        return coverage;
    }

    private static Map<String, String> publisherFields(LegalManifestV1 manifest) {
        var publisher = manifest.publisherSnapshot();
        return Map.ofEntries(
                Map.entry("publisherSnapshot/legalName", publisher.legalName()),
                Map.entry("publisherSnapshot/taxId", publisher.taxId()),
                Map.entry("publisherSnapshot/legalAddress", publisher.legalAddress()),
                Map.entry("publisherSnapshot/jurisdiction", publisher.jurisdiction()),
                Map.entry("publisherSnapshot/businessHours", publisher.businessHours()),
                Map.entry("publisherSnapshot/contacts/legalEmail", publisher.contacts().legalEmail()),
                Map.entry(
                        "publisherSnapshot/contacts/privacyEmail",
                        publisher.contacts().privacyEmail()),
                Map.entry(
                        "publisherSnapshot/contacts/supportEmail",
                        publisher.contacts().supportEmail()));
    }

    private static void assertApproved(ReviewRecord review) {
        assertThat(review.status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(review.reference()).isNotBlank();
        assertThat(review.reviewedAt()).isNotNull();
    }

    private static <T> T requirePass(
            LegalManifestValidation<T> validation,
            String description) {
        assertThat(validation.passed())
                .as("%s; issues=%s", description, validation.issues())
                .isTrue();
        return validation.value().orElseThrow();
    }

    private record Scope(ContextoLegal context, AudienciaLegal role) {
    }
}
