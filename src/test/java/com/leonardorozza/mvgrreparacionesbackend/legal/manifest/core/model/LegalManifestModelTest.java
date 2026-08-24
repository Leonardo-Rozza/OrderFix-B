package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.Contacts;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.DocumentEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.PublisherSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.RequirementEntry;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.Review;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewRecord;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalManifestV1.ReviewStatus;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.DocumentPlan;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.model.LegalPublicationPlan.ScopePlan;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegalManifestModelTest {

    private static final OffsetDateTime EFFECTIVE_AT =
            OffsetDateTime.parse("2026-09-01T00:00:00-03:00");

    @Test
    void copiesEveryCollectionAndKeepsDeclarationOrder() {
        var sourceContexts = new ArrayList<>(List.of(ContextoLegal.REGISTRO));
        var firstDocument = document(
                "terminos",
                TipoDocumentoLegal.TERMINOS_SERVICIO,
                sourceContexts
        );
        sourceContexts.add(ContextoLegal.CONTRATACION_PRO);

        var secondDocument = document(
                "privacidad",
                TipoDocumentoLegal.POLITICA_PRIVACIDAD,
                List.of(ContextoLegal.REGISTRO)
        );

        var sourceRoles = new ArrayList<>(List.of(AudienciaLegal.ADMIN_TITULAR));
        var sourceDocumentKeys = new ArrayList<>(List.of("terminos", "privacidad"));
        var firstRequirement = requirement(
                "admin-registration",
                sourceRoles,
                sourceDocumentKeys
        );
        sourceRoles.add(AudienciaLegal.USER);
        sourceDocumentKeys.clear();

        var secondRequirement = requirement(
                "admin-confirmation",
                List.of(AudienciaLegal.ADMIN_TITULAR),
                List.of("terminos")
        );

        var sourceDocuments = new ArrayList<>(List.of(firstDocument, secondDocument));
        var sourceRequirements = new ArrayList<>(List.of(firstRequirement, secondRequirement));
        var manifest = manifest(sourceDocuments, sourceRequirements);
        sourceDocuments.clear();
        sourceRequirements.clear();

        var firstDocumentPlan = new DocumentPlan(firstDocument, "Términos", "# Términos\n");
        var secondDocumentPlan = new DocumentPlan(secondDocument, "Privacidad", "# Privacidad\n");
        var sourceDocumentPlans = new ArrayList<>(List.of(firstDocumentPlan, secondDocumentPlan));

        var sourceScopeRequirements = new ArrayList<>(List.of(firstRequirement, secondRequirement));
        var firstScope = new ScopePlan(
                LocaleLegal.ES_AR,
                ContextoLegal.REGISTRO,
                AudienciaLegal.ADMIN_TITULAR,
                sourceScopeRequirements
        );
        var secondScope = new ScopePlan(
                LocaleLegal.ES_AR,
                ContextoLegal.REGISTRO,
                AudienciaLegal.USER,
                List.of(secondRequirement)
        );
        sourceScopeRequirements.clear();

        var sourceScopes = new ArrayList<>(List.of(firstScope, secondScope));
        var plan = new LegalPublicationPlan(
                manifest,
                "{\"publicationId\":\"release-valid-v1\"}",
                "0".repeat(64),
                sourceDocumentPlans,
                sourceScopes
        );
        sourceDocumentPlans.clear();
        sourceScopes.clear();

        assertEquals(List.of(ContextoLegal.REGISTRO), firstDocument.contexts());
        assertEquals(List.of(AudienciaLegal.ADMIN_TITULAR), firstRequirement.roles());
        assertEquals(List.of("terminos", "privacidad"), firstRequirement.documents());
        assertEquals(List.of("terminos", "privacidad"),
                manifest.documents().stream().map(DocumentEntry::key).toList());
        assertEquals(List.of("admin-registration", "admin-confirmation"),
                manifest.requirements().stream().map(RequirementEntry::key).toList());
        assertEquals(List.of("terminos", "privacidad"),
                plan.documents().stream().map(entry -> entry.declaration().key()).toList());
        assertEquals(List.of(AudienciaLegal.ADMIN_TITULAR, AudienciaLegal.USER),
                plan.scopes().stream().map(ScopePlan::audience).toList());
        assertEquals(List.of("admin-registration", "admin-confirmation"),
                firstScope.requirements().stream().map(RequirementEntry::key).toList());

        assertThrows(UnsupportedOperationException.class,
                () -> firstDocument.contexts().add(ContextoLegal.CONTRATACION_PRO));
        assertThrows(UnsupportedOperationException.class,
                () -> firstRequirement.roles().add(AudienciaLegal.USER));
        assertThrows(UnsupportedOperationException.class,
                () -> firstRequirement.documents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.documents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.requirements().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.documents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.scopes().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> firstScope.requirements().clear());

        assertEquals(2, plan.documentCount());
        assertEquals(2, plan.requirementCount());
        assertEquals(2, plan.scopeCount());
        assertEquals(secondDocumentPlan, plan.documentByKey("privacidad").orElseThrow());
        assertTrue(plan.documentByKey("inexistente").isEmpty());
    }

    @Test
    void permitsOnlyTheExplicitlyOptionalManifestFieldsToBeAbsent() {
        var pending = new ReviewRecord(ReviewStatus.PENDING, null, null);
        var manifest = manifest(
                List.of(document(
                        "terminos",
                        TipoDocumentoLegal.TERMINOS_SERVICIO,
                        List.of(ContextoLegal.REGISTRO)
                )),
                List.of(requirement(
                        "admin-registration",
                        List.of(AudienciaLegal.ADMIN_TITULAR),
                        List.of("terminos")
                )),
                pending
        );

        assertNull(manifest.$schema());
        assertNull(manifest.review().legal().reference());
        assertNull(manifest.review().legal().reviewedAt());

        assertThrows(NullPointerException.class,
                () -> new Contacts(null, "privacidad@ordenfix.com", "soporte@ordenfix.com"));
        assertThrows(NullPointerException.class,
                () -> new ReviewRecord(null, null, null));
        assertThrows(NullPointerException.class,
                () -> new LegalPublicationPlan(manifest, null, "0".repeat(64), List.of(), List.of()));
        var requirementsWithNull = new ArrayList<RequirementEntry>();
        requirementsWithNull.add(null);
        assertThrows(NullPointerException.class,
                () -> new ScopePlan(
                        LocaleLegal.ES_AR,
                        ContextoLegal.REGISTRO,
                        AudienciaLegal.ADMIN_TITULAR,
                        requirementsWithNull
                ));
    }

    private static LegalManifestV1 manifest(
            List<DocumentEntry> documents,
            List<RequirementEntry> requirements
    ) {
        var approved = new ReviewRecord(
                ReviewStatus.APPROVED,
                "LEGAL-2026-001",
                OffsetDateTime.parse("2026-08-20T12:00:00-03:00")
        );
        return manifest(documents, requirements, approved);
    }

    private static LegalManifestV1 manifest(
            List<DocumentEntry> documents,
            List<RequirementEntry> requirements,
            ReviewRecord reviewRecord
    ) {
        return new LegalManifestV1(
                null,
                1,
                "release-valid-v1",
                LocaleLegal.ES_AR,
                new PublisherSnapshot(
                        "OrdenFix Argentina SAS",
                        "30-00000000-0",
                        "Calle Pública 100, CABA",
                        "Ciudad Autónoma de Buenos Aires",
                        "Lunes a viernes de 9 a 17 h",
                        new Contacts(
                                "legal@ordenfix.com",
                                "privacidad@ordenfix.com",
                                "soporte@ordenfix.com"
                        )
                ),
                new Review(reviewRecord, reviewRecord),
                documents,
                requirements
        );
    }

    private static DocumentEntry document(
            String key,
            TipoDocumentoLegal type,
            List<ContextoLegal> contexts
    ) {
        return new DocumentEntry(
                key,
                type,
                "1.0.0",
                LocaleLegal.ES_AR,
                key + ".md",
                "0".repeat(64),
                EFFECTIVE_AT,
                contexts,
                true
        );
    }

    private static RequirementEntry requirement(
            String key,
            List<AudienciaLegal> roles,
            List<String> documents
    ) {
        return new RequirementEntry(
                key,
                "1.0.0",
                ContextoLegal.REGISTRO,
                roles,
                TipoActoLegal.ACEPTACION,
                "Confirmo " + key + ".",
                "0".repeat(64),
                documents,
                true,
                true
        );
    }
}
