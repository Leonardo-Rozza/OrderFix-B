package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentCatalog;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentVersion;

import java.util.List;

/** The public wire values contain neither persistence entities nor editorial provenance. */
public final class LegalPublicDocumentResponses {

    private LegalPublicDocumentResponses() { }

    static Catalog catalog(LegalPublicDocumentCatalog source) {
        return new Catalog(source.context() == null ? null : source.context().name(),
                source.locale().getCodigo(), source.documentSetRevision(),
                source.documents().stream().map(LegalPublicDocumentResponses::summary).toList(),
                new Page(source.size(), source.page(), source.totalElements(), source.totalPages()));
    }

    static Document document(LegalPublicDocumentVersion source) {
        LegalDocumentSummary summary = source.summary();
        return new Document(summary.versionId().toString(), summary.type().name(), summary.version(),
                summary.title(), source.markdown(), summary.sha256(), summary.effectiveAtUtc(),
                summary.state().name(), summary.locale().getCodigo());
    }

    private static Summary summary(LegalDocumentSummary source) {
        return new Summary(source.versionId().toString(), source.type().name(), source.version(),
                source.title(), source.sha256(), source.effectiveAtUtc(), source.state().name(),
                source.locale().getCodigo());
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Catalog(String contexto, String locale, String documentSetRevision,
                          List<Summary> documentos, Page page) { }

    public record Summary(String id, String tipo, String version, String titulo, String sha256,
                          String vigenteDesde, String estado, String locale) { }

    public record Document(String id, String tipo, String version, String titulo,
                           String contenidoMarkdown, String sha256, String vigenteDesde,
                           String estado, String locale) { }

    public record Page(int size, int number, long totalElements, long totalPages) { }
}
