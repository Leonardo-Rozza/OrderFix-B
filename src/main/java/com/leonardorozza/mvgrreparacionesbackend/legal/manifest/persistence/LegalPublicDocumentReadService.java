package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Narrow facade for complete, commit-confirmed observations from the isolated reader context. */
public final class LegalPublicDocumentReadService {

    private final LegalPublicDocumentDataSource dataSource;
    private final LegalManifestDatabaseGate gate;
    private final LegalPublicDocumentReader reader;

    LegalPublicDocumentReadService(
            JdbcTemplate jdbc,
            LegalPublicDocumentDataSource dataSource,
            LegalManifestDatabaseGate gate,
            LegalPublicDocumentReader reader,
            LegalV28AggregateSchemaVerifier schema,
            LegalPublicDocumentPrivilegeVerifier privileges) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.reader = Objects.requireNonNull(reader, "reader");
        gate.requireExactPublicDocumentReadBoundary(jdbc, schema, privileges);
        if (jdbc.getDataSource() != dataSource || !reader.usesJdbc(jdbc)) {
            throw new IllegalArgumentException("El lector requiere una única frontera JDBC acotada");
        }
    }

    public LegalPublicDocumentCatalog catalog(ContextoLegal context, LocaleLegal locale, int page, int size) {
        Objects.requireNonNull(locale, "locale");
        LegalPublicDocumentCatalog.pageOffset(page, size);
        return observe(deadline -> reader.readCatalog(context, locale, page, size, deadline));
    }

    /** Empty only for a successfully observed UUID with no public version. */
    public Optional<LegalPublicDocumentVersion> document(UUID versionId) {
        Objects.requireNonNull(versionId, "versionId");
        return observe(deadline -> reader.findVersion(versionId, deadline));
    }

    private <T> T observe(Function<LegalPublicDocumentDeadline, T> read) {
        try {
            return dataSource.withinDeadline(deadline -> Objects.requireNonNull(
                    gate.executeReadOnlyShared((status, boundary) -> {
                        deadline.check();
                        T result = Objects.requireNonNull(read.apply(deadline), "document observation");
                        deadline.check();
                        return result;
                    }), "committed document observation"));
        } catch (LegalPublicDocumentReadException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LegalPublicDocumentReadException(failure);
        }
    }
}
