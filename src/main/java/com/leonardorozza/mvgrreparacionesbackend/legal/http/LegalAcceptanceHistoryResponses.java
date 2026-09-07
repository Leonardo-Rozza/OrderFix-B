package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryPage;

import java.time.format.DateTimeFormatter;
import java.util.List;

/** Historical snapshots from the evidence, excluding metadata and current replacement content. */
public final class LegalAcceptanceHistoryResponses {

    private LegalAcceptanceHistoryResponses() { }

    static History history(LegalAcceptanceHistoryPage source) {
        return new History(source.content().stream().map(LegalAcceptanceHistoryResponses::acceptance).toList(),
                new Page(source.size(), source.page(), source.totalElements(), source.totalPages()));
    }

    private static Acceptance acceptance(LegalAcceptanceHistoryPage.Acceptance source) {
        return new Acceptance(source.id().toString(), source.requirementVersionId().toString(),
                source.context().name(), source.actType().name(), source.statement(), source.statementSha256(),
                source.documents().stream().map(document -> new Document(document.documentVersionId().toString(),
                        document.type().name(), document.version(), document.title(), document.sha256())).toList(),
                DateTimeFormatter.ISO_INSTANT.format(source.acceptedAt()));
    }

    public record History(List<Acceptance> content, Page page) { }
    public record Page(int size, int number, long totalElements, long totalPages) { }
    public record Acceptance(String id, String requisitoVersionId, String contexto, String tipoActo,
                             String afirmacion, String afirmacionSha256, List<Document> documentos,
                             String aceptadoEn) { }
    public record Document(String documentoVersionId, String tipo, String version, String titulo, String sha256) { }
}
