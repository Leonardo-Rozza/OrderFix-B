package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalCoverageMatrix.Coverage;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalCoverageMatrix.ScopeCoverage;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalCoverageMatrix.ScopeKey;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal.ADMIN_TITULAR;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal.USER;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal.ATESTACION_CREDENCIALES;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal.ATESTACION_FOTOS;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal.CIERRE_CUENTA;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal.CONTRATACION_PRO;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal.PRIMER_INGRESO_EMPLEADO;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal.REGISTRO;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.ACUERDO_TRATAMIENTO_DATOS;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.ATESTACION_DATOS_CLIENTE;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.AVISO_CLIENTES_TALLER;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.AVISO_PRIVACIDAD_USUARIO;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.COMPROMISO_CONFIDENCIALIDAD;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.CONDICIONES_PRO;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.POLITICA_CANCELACIONES_REEMBOLSOS;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.POLITICA_CIERRE_CUENTA;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.POLITICA_PRIVACIDAD;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.TERMINOS_SERVICIO;
import static com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal.TERMINOS_USUARIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalCoverageMatrixTest {

    private static final OffsetDateTime EFFECTIVE_AT =
            OffsetDateTime.parse("2026-09-01T00:00:00-03:00");

    @Test
    void freezesTheElevenDocumentTypesAndEightOrderedScopes() {
        assertThat(LegalCoverageMatrix.documentTypes())
                .containsExactlyElementsOf(EnumSet.allOf(TipoDocumentoLegal.class));
        assertThat(LegalCoverageMatrix.documentTypes()).hasSize(11);
        assertThat(LegalCoverageMatrix.requiredScopes()).containsExactly(
                scope(REGISTRO, ADMIN_TITULAR),
                scope(PRIMER_INGRESO_EMPLEADO, USER),
                scope(CONTRATACION_PRO, ADMIN_TITULAR),
                scope(ATESTACION_FOTOS, ADMIN_TITULAR),
                scope(ATESTACION_FOTOS, USER),
                scope(ATESTACION_CREDENCIALES, ADMIN_TITULAR),
                scope(ATESTACION_CREDENCIALES, USER),
                scope(CIERRE_CUENTA, ADMIN_TITULAR)
        );

        assertRequiredTypes(REGISTRO, ADMIN_TITULAR,
                TERMINOS_SERVICIO, POLITICA_PRIVACIDAD, ACUERDO_TRATAMIENTO_DATOS);
        assertRequiredTypes(PRIMER_INGRESO_EMPLEADO, USER,
                TERMINOS_USUARIO, AVISO_PRIVACIDAD_USUARIO, COMPROMISO_CONFIDENCIALIDAD);
        assertRequiredTypes(CONTRATACION_PRO, ADMIN_TITULAR,
                TERMINOS_SERVICIO, CONDICIONES_PRO, POLITICA_CANCELACIONES_REEMBOLSOS);
        assertRequiredTypes(ATESTACION_FOTOS, ADMIN_TITULAR,
                AVISO_CLIENTES_TALLER, ATESTACION_DATOS_CLIENTE);
        assertRequiredTypes(ATESTACION_FOTOS, USER,
                AVISO_CLIENTES_TALLER, ATESTACION_DATOS_CLIENTE);
        assertRequiredTypes(ATESTACION_CREDENCIALES, ADMIN_TITULAR,
                AVISO_CLIENTES_TALLER, ATESTACION_DATOS_CLIENTE);
        assertRequiredTypes(ATESTACION_CREDENCIALES, USER,
                AVISO_CLIENTES_TALLER, ATESTACION_DATOS_CLIENTE);
        assertRequiredTypes(CIERRE_CUENTA, ADMIN_TITULAR, POLITICA_CIERRE_CUENTA);
        assertThat(LegalCoverageMatrix.requiredDocumentTypes(
                scope(ContextoLegal.USO_CONTINUADO, USER)
        )).isEmpty();
    }

    @Test
    void aggregatesCoverageAcrossRequiredRequirementsInTheSameScope() {
        Map<String, DocumentEntry> documents = documents(
                document("terminos", TERMINOS_SERVICIO, REGISTRO),
                document("privacidad", POLITICA_PRIVACIDAD, REGISTRO),
                document("dpa", ACUERDO_TRATAMIENTO_DATOS, REGISTRO)
        );
        List<RequirementEntry> requirements = List.of(
                requirement(
                        "registro-base",
                        REGISTRO,
                        List.of(ADMIN_TITULAR),
                        List.of("terminos", "privacidad"),
                        true
                ),
                requirement(
                        "registro-dpa",
                        REGISTRO,
                        List.of(ADMIN_TITULAR),
                        List.of("dpa"),
                        true
                )
        );

        ScopeCoverage registration = LegalCoverageMatrix.evaluate(requirements, documents)
                .scope(scope(REGISTRO, ADMIN_TITULAR))
                .orElseThrow();

        assertThat(registration.hasRequiredRequirement()).isTrue();
        assertThat(registration.coveredDocumentTypes()).containsExactly(
                TERMINOS_SERVICIO,
                POLITICA_PRIVACIDAD,
                ACUERDO_TRATAMIENTO_DATOS
        );
        assertThat(registration.missingDocumentTypes()).isEmpty();
        assertThat(registration.complete()).isTrue();
    }

    @Test
    void ignoresOptionalRequirementsForScopeAndDocumentCoverage() {
        Map<String, DocumentEntry> documents = documents(
                document("terminos", TERMINOS_SERVICIO, REGISTRO),
                document("privacidad", POLITICA_PRIVACIDAD, REGISTRO),
                document("dpa", ACUERDO_TRATAMIENTO_DATOS, REGISTRO)
        );
        List<RequirementEntry> requirements = List.of(
                requirement(
                        "registro-base",
                        REGISTRO,
                        List.of(ADMIN_TITULAR),
                        List.of("terminos", "privacidad"),
                        true
                ),
                requirement(
                        "registro-dpa-opcional",
                        REGISTRO,
                        List.of(ADMIN_TITULAR),
                        List.of("dpa"),
                        false
                ),
                requirement(
                        "primer-ingreso-opcional",
                        PRIMER_INGRESO_EMPLEADO,
                        List.of(USER),
                        List.of("terminos"),
                        false
                )
        );

        Coverage coverage = LegalCoverageMatrix.evaluate(requirements, documents);
        ScopeCoverage registration = coverage.scope(scope(REGISTRO, ADMIN_TITULAR)).orElseThrow();
        ScopeCoverage firstLogin = coverage.scope(scope(PRIMER_INGRESO_EMPLEADO, USER)).orElseThrow();

        assertThat(registration.hasRequiredRequirement()).isTrue();
        assertThat(registration.coveredDocumentTypes())
                .containsExactly(TERMINOS_SERVICIO, POLITICA_PRIVACIDAD);
        assertThat(registration.missingDocumentTypes())
                .containsExactly(ACUERDO_TRATAMIENTO_DATOS);
        assertThat(firstLogin.hasRequiredRequirement()).isFalse();
        assertThat(firstLogin.coveredDocumentTypes()).isEmpty();
        assertThat(firstLogin.missingDocumentTypes()).containsExactly(
                TERMINOS_USUARIO,
                AVISO_PRIVACIDAD_USUARIO,
                COMPROMISO_CONFIDENCIALIDAD
        );
        assertThat(coverage.complete()).isFalse();
    }

    @Test
    void expandsMultiRoleRequirementsAndDoesNotCountUnknownOrIncompatibleDocuments() {
        Map<String, DocumentEntry> documents = documents(
                document("aviso", AVISO_CLIENTES_TALLER, ATESTACION_FOTOS),
                document("atestacion-contexto-incorrecto", ATESTACION_DATOS_CLIENTE, REGISTRO)
        );
        RequirementEntry requirement = requirement(
                "atestacion-fotos",
                ATESTACION_FOTOS,
                List.of(ADMIN_TITULAR, USER),
                List.of("aviso", "atestacion-contexto-incorrecto", "inexistente"),
                true
        );

        Coverage coverage = LegalCoverageMatrix.evaluate(List.of(requirement), documents);

        for (AudienciaLegal audience : List.of(ADMIN_TITULAR, USER)) {
            ScopeCoverage scopeCoverage = coverage
                    .scope(scope(ATESTACION_FOTOS, audience))
                    .orElseThrow();
            assertThat(scopeCoverage.hasRequiredRequirement()).isTrue();
            assertThat(scopeCoverage.coveredDocumentTypes()).containsExactly(AVISO_CLIENTES_TALLER);
            assertThat(scopeCoverage.missingDocumentTypes()).containsExactly(ATESTACION_DATOS_CLIENTE);
        }
    }

    @Test
    void marksTheReleaseCompleteWhenSixRequiredRequirementsCoverAllEightScopes() {
        Map<String, DocumentEntry> documents = documents(
                document("terminos", TERMINOS_SERVICIO, REGISTRO, CONTRATACION_PRO),
                document("privacidad", POLITICA_PRIVACIDAD, REGISTRO),
                document("dpa", ACUERDO_TRATAMIENTO_DATOS, REGISTRO),
                document("terminos-usuario", TERMINOS_USUARIO, PRIMER_INGRESO_EMPLEADO),
                document("privacidad-usuario", AVISO_PRIVACIDAD_USUARIO,
                        PRIMER_INGRESO_EMPLEADO),
                document("confidencialidad", COMPROMISO_CONFIDENCIALIDAD,
                        PRIMER_INGRESO_EMPLEADO),
                document("condiciones-pro", CONDICIONES_PRO, CONTRATACION_PRO),
                document("cancelaciones", POLITICA_CANCELACIONES_REEMBOLSOS,
                        CONTRATACION_PRO),
                document("aviso-clientes", AVISO_CLIENTES_TALLER,
                        ATESTACION_FOTOS, ATESTACION_CREDENCIALES),
                document("atestacion", ATESTACION_DATOS_CLIENTE,
                        ATESTACION_FOTOS, ATESTACION_CREDENCIALES),
                document("cierre", POLITICA_CIERRE_CUENTA, CIERRE_CUENTA)
        );
        List<RequirementEntry> requirements = List.of(
                requirement("registro", REGISTRO, List.of(ADMIN_TITULAR),
                        List.of("terminos", "privacidad", "dpa"), true),
                requirement("primer-ingreso", PRIMER_INGRESO_EMPLEADO, List.of(USER),
                        List.of("terminos-usuario", "privacidad-usuario", "confidencialidad"),
                        true),
                requirement("pro", CONTRATACION_PRO, List.of(ADMIN_TITULAR),
                        List.of("terminos", "condiciones-pro", "cancelaciones"), true),
                requirement("fotos", ATESTACION_FOTOS, List.of(ADMIN_TITULAR, USER),
                        List.of("aviso-clientes", "atestacion"), true),
                requirement("credenciales", ATESTACION_CREDENCIALES,
                        List.of(ADMIN_TITULAR, USER),
                        List.of("aviso-clientes", "atestacion"), true),
                requirement("cierre", CIERRE_CUENTA, List.of(ADMIN_TITULAR),
                        List.of("cierre"), true)
        );

        Coverage coverage = LegalCoverageMatrix.evaluate(requirements, documents);

        assertThat(coverage.complete()).isTrue();
        assertThat(coverage.scopes()).hasSize(8).allMatch(ScopeCoverage::complete);
    }

    @Test
    void exposesOnlyDefensiveImmutableResults() {
        ArrayList<RequirementEntry> requirements = new ArrayList<>(List.of(requirement(
                "cierre",
                CIERRE_CUENTA,
                List.of(ADMIN_TITULAR),
                List.of("cierre"),
                true
        )));
        LinkedHashMap<String, DocumentEntry> documents = new LinkedHashMap<>(documents(
                document("cierre", POLITICA_CIERRE_CUENTA, CIERRE_CUENTA)
        ));

        Coverage coverage = LegalCoverageMatrix.evaluate(requirements, documents);
        requirements.clear();
        documents.clear();
        ScopeCoverage closure = coverage.scope(scope(CIERRE_CUENTA, ADMIN_TITULAR)).orElseThrow();

        assertThat(closure.complete()).isTrue();
        assertThatThrownBy(() -> LegalCoverageMatrix.documentTypes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> LegalCoverageMatrix.requiredScopes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> closure.coveredDocumentTypes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> closure.missingDocumentTypes().add(TERMINOS_SERVICIO))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> coverage.scopes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertRequiredTypes(
            ContextoLegal context,
            AudienciaLegal audience,
            TipoDocumentoLegal... expected
    ) {
        assertThat(LegalCoverageMatrix.requiredDocumentTypes(scope(context, audience)))
                .containsExactly(expected);
    }

    private static ScopeKey scope(ContextoLegal context, AudienciaLegal audience) {
        return new ScopeKey(context, audience);
    }

    private static Map<String, DocumentEntry> documents(DocumentEntry... documents) {
        LinkedHashMap<String, DocumentEntry> byKey = new LinkedHashMap<>();
        for (DocumentEntry document : documents) {
            byKey.put(document.key(), document);
        }
        return byKey;
    }

    private static DocumentEntry document(
            String key,
            TipoDocumentoLegal type,
            ContextoLegal... contexts
    ) {
        return new DocumentEntry(
                key,
                type,
                "1.0.0",
                LocaleLegal.ES_AR,
                key + ".md",
                "0".repeat(64),
                EFFECTIVE_AT,
                List.of(contexts),
                true
        );
    }

    private static RequirementEntry requirement(
            String key,
            ContextoLegal context,
            List<AudienciaLegal> roles,
            List<String> documents,
            boolean required
    ) {
        return new RequirementEntry(
                key,
                "1.0.0",
                context,
                roles,
                TipoActoLegal.ACEPTACION,
                "Confirmo " + key + '.',
                "0".repeat(64),
                documents,
                required,
                true
        );
    }
}
