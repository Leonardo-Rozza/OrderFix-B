package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;

import java.util.List;

/** Literal public contract without persistence identities, provenance or the component revision. */
public final class LegalPublicRequirementsResponses {

    private LegalPublicRequirementsResponses() { }

    static Registration registration(LegalPublicRegistrationRequirements source) {
        var projection = source.projection();
        return new Registration(projection.context().name(), projection.locale().getCodigo(),
                source.requiredSetRevision(), projection.requirements().stream()
                .map(LegalPublicRequirementsResponses::requirement).toList());
    }

    private static Requisito requirement(RequirementProjection source) {
        return new Requisito(source.versionId().toString(), source.context().name(), source.actType().name(),
                source.statement(), source.statementSha256(), source.required(),
                source.documents().stream().map(LegalPublicRequirementsResponses::document).toList());
    }

    private static Documento document(DocumentProjection source) {
        LegalDocumentSummary summary = new LegalDocumentSummary(source.versionId(), source.type(),
                source.version(), source.title(), source.sha256(), source.effectiveAt().toInstant(),
                EstadoVersionLegal.VIGENTE, source.locale());
        return new Documento(summary.versionId().toString(), summary.type().name(), summary.version(),
                summary.title(), source.markdown(), summary.sha256(), summary.effectiveAtUtc(),
                summary.state().name(), summary.locale().getCodigo());
    }

    public record Registration(String contexto, String locale, String requiredSetRevision,
                               List<Requisito> requisitos) { }

    public record Requisito(String id, String contexto, String tipoActo, String afirmacion,
                            String afirmacionSha256, boolean requerido, List<Documento> documentos) { }

    public record Documento(String id, String tipo, String version, String titulo,
                            String contenidoMarkdown, String sha256, String vigenteDesde,
                            String estado, String locale) { }
}
