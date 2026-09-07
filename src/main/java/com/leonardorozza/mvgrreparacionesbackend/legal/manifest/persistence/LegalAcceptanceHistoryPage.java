package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.CanonicalTextValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable historical snapshots; no actor, provenance, metadata or present-day legal state. */
public record LegalAcceptanceHistoryPage(
        List<Acceptance> content, int page, int size, long totalElements, long totalPages
) {
    public static final int MAX_PAGE_SIZE = 100;
    public static final long MAX_SAFE_COUNT = 9_007_199_254_740_991L;
    private static final Instant FIRST_INSTANT = Instant.parse("0000-01-01T00:00:00Z");
    private static final Instant AFTER_LAST_INSTANT = Instant.parse("+10000-01-01T00:00:00Z");

    public LegalAcceptanceHistoryPage {
        long offset = pageOffset(page, size);
        require(totalPages == pagesFor(totalElements, size));
        Objects.requireNonNull(content, "content");
        long expected = offset >= totalElements ? 0 : Math.min(size, totalElements - offset);
        require(content.size() == expected);
        content = List.copyOf(content);
        var ids = new HashSet<UUID>();
        var versions = new HashSet<UUID>();
        Acceptance previous = null;
        for (Acceptance item : content) {
            require(ids.add(item.id()) && versions.add(item.requirementVersionId()));
            if (previous != null) {
                int time = previous.acceptedAt().compareTo(item.acceptedAt());
                // PostgreSQL UUID ordering is unsigned; canonical hexadecimal text has the same order.
                require(time > 0 || (time == 0 && previous.id().toString().compareTo(item.id().toString()) > 0));
            }
            previous = item;
        }
    }

    public static long pageOffset(int page, int size) {
        require(page >= 0 && size >= 1 && size <= MAX_PAGE_SIZE);
        return Math.multiplyExact((long) page, size);
    }

    public static long pagesFor(long totalElements, int size) {
        require(totalElements >= 0 && totalElements <= MAX_SAFE_COUNT && size >= 1 && size <= MAX_PAGE_SIZE);
        return totalElements / size + (totalElements % size == 0 ? 0 : 1);
    }

    public record Acceptance(
            UUID id, UUID requirementVersionId, ContextoLegal context, TipoActoLegal actType,
            String statement, String statementSha256, List<Document> documents, Instant acceptedAt
    ) {
        public Acceptance {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(requirementVersionId, "requirementVersionId");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(actType, "actType");
            Objects.requireNonNull(statement, "statement");
            require(!statement.isEmpty() && statement.codePointCount(0, statement.length()) <= 1_000
                    && statement.getBytes(StandardCharsets.UTF_8).length <= 4_000
                    && new CanonicalTextValidator().validate(statement, statementSha256, "acceptance-history").passed());
            Objects.requireNonNull(documents, "documents");
            require(!documents.isEmpty() && documents.size() <= 16);
            documents = List.copyOf(documents);
            var ids = new HashSet<UUID>();
            for (Document document : documents) require(ids.add(document.documentVersionId()));
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            require(acceptedAt.getNano() % 1_000 == 0 && !acceptedAt.isBefore(FIRST_INSTANT)
                    && acceptedAt.isBefore(AFTER_LAST_INSTANT));
        }
    }

    public record Document(UUID documentVersionId, TipoDocumentoLegal type, String version, String title, String sha256) {
        public Document {
            new LegalDocumentSummary(documentVersionId, type, version, title, sha256,
                    Instant.EPOCH, EstadoVersionLegal.VIGENTE, LocaleLegal.ES_AR);
            require(version.codePointCount(0, version.length()) <= 40);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("La página de historial legal no es válida");
    }
}
