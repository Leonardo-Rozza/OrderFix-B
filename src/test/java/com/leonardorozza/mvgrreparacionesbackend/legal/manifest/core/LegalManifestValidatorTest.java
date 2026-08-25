package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestValidator.ValidatedRelease;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.DocumentPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.RequirementPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.ScopePlan;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class LegalManifestValidatorTest {

    private static final String GOLDEN_MANIFEST =
            "/legal/manifest/release-valid-v1/publication-manifest.json";
    private static final String GOLDEN_JCS_SHA256 =
            "b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1";

    private final LegalManifestValidator validator = new LegalManifestValidator();

    @Test
    void accreditsTheGoldenReleaseAndPreservesGlobalManifestOrdinals()
            throws URISyntaxException {
        LegalManifestValidation<ValidatedRelease> result = validator.validate(goldenManifest());

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        assertThat(result.issues()).isEmpty();
        ValidatedRelease accredited = result.value().orElseThrow();
        LegalPublicationPlan plan = accredited.plan();

        assertThat(accredited.documentCount()).isEqualTo(11);
        assertThat(accredited.requirementCount()).isEqualTo(6);
        assertThat(accredited.scopeCount()).isEqualTo(8);
        assertThat(plan.manifestSha256()).isEqualTo(GOLDEN_JCS_SHA256);
        assertThat(plan.documents())
                .extracting(DocumentPlan::manifestOrdinal)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(plan.documents())
                .extracting(document -> document.declaration().key())
                .containsExactly(
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

        assertThat(plan.scopes())
                .extracting(LegalManifestValidatorTest::scopeName)
                .containsExactly(
                        "REGISTRO:ADMIN_TITULAR",
                        "PRIMER_INGRESO_EMPLEADO:USER",
                        "CONTRATACION_PRO:ADMIN_TITULAR",
                        "ATESTACION_FOTOS:ADMIN_TITULAR",
                        "ATESTACION_FOTOS:USER",
                        "ATESTACION_CREDENCIALES:ADMIN_TITULAR",
                        "ATESTACION_CREDENCIALES:USER",
                        "CIERRE_CUENTA:ADMIN_TITULAR");
        assertThat(plan.scopes())
                .extracting(scope -> scope.requirements().stream()
                        .map(RequirementPlan::manifestOrdinal)
                        .toList())
                .containsExactly(
                        List.of(0),
                        List.of(1),
                        List.of(2),
                        List.of(3),
                        List.of(3),
                        List.of(4),
                        List.of(4),
                        List.of(5));
    }

    @Test
    void exposesAnOpaqueFinalAccreditationWithNoPublicConstructor() {
        assertThat(Modifier.isFinal(ValidatedRelease.class.getModifiers())).isTrue();
        assertThat(ValidatedRelease.class.getConstructors()).isEmpty();
        assertThat(ValidatedRelease.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(
                        Modifier.isPrivate(constructor.getModifiers())).isTrue());
    }

    @Test
    void propagatesExpectedInputFailuresWithoutMintingAnAccreditation() {
        LegalManifestValidation<ValidatedRelease> result = validator.validate(null);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(LegalManifestIssueCode.MANIFEST_PATH_REQUIRED);
    }

    private Path goldenManifest() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                getClass().getResource(GOLDEN_MANIFEST)).toURI());
    }

    private static String scopeName(ScopePlan scope) {
        return scope.context().name() + ':' + scope.audience().name();
    }
}
