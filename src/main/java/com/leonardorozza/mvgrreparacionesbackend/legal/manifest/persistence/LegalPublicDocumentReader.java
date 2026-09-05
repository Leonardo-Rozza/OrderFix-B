package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.CanonicalTextValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentCatalogProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSetRevisionCalculator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalDocumentSummary;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestLimits;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** JDBC-only reader inside the caller-owned, preflighted shared read-only transaction. */
final class LegalPublicDocumentReader {

    private static final int FETCH_SIZE = 128;
    private static final String SUMMARY_COLUMNS = """
            versions.id, lines.tipo, versions.version, versions.titulo, versions.sha256,
            versions.vigente_desde, versions.estado, lines.locale
            """;
    private static final String DOCUMENT_TABLES = """
              FROM public.legal_documento_versiones versions
              JOIN public.legal_documento_lineas lines
                ON lines.id = versions.documento_linea_id
            """;
    private static final String PUBLIC_STATES =
            "versions.estado IN ('VIGENTE', 'REEMPLAZADA', 'RETIRADA')";
    private static final String CONTEXT_PREDICATE = """
               AND EXISTS (
                   SELECT 1 FROM public.legal_documento_contextos contexts
                    WHERE contexts.documento_version_id = versions.id
                      AND contexts.contexto = ?)
            """;
    private static final String CATALOG_ORDER = documentTypeOrderSql()
            + ", versions.vigente_desde DESC, versions.id ASC";
    private static final String EXACT_VERSION_SQL = "SELECT " + SUMMARY_COLUMNS + """
                 , pg_catalog.octet_length(pg_catalog.convert_to(
                       versions.contenido_markdown, 'UTF8')) AS markdown_octets
                 , CASE WHEN pg_catalog.octet_length(pg_catalog.convert_to(
                       versions.contenido_markdown, 'UTF8')) BETWEEN 1 AND ?
                        THEN pg_catalog.convert_to(versions.contenido_markdown, 'UTF8')
                        ELSE NULL END AS markdown_utf8
            """ + DOCUMENT_TABLES + " WHERE versions.id = ? AND " + PUBLIC_STATES;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final LegalDocumentSetRevisionCalculator revisionCalculator;
    private final CanonicalTextValidator textValidator = new CanonicalTextValidator();

    LegalPublicDocumentReader(JdbcTemplate jdbc,
                              LegalDocumentSetRevisionCalculator revisionCalculator) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
        this.revisionCalculator = Objects.requireNonNull(revisionCalculator, "revisionCalculator");
    }

    LegalPublicDocumentCatalog readCatalog(ContextoLegal context, LocaleLegal locale,
                                          int page, int size,
                                          LegalPublicDocumentDeadline deadline) {
        long offset = LegalPublicDocumentCatalog.pageOffset(page, size);
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(deadline, "deadline").check();
        requireReadOnlyTransaction();
        try {
            LegalPublicDocumentCatalog result = jdbc.execute(
                    (ConnectionCallback<LegalPublicDocumentCatalog>) connection -> {
                        requireBoundConnection(connection);
                        deadline.check();
                        String sql = "SELECT " + SUMMARY_COLUMNS + DOCUMENT_TABLES
                                + " WHERE lines.locale = ? AND " + PUBLIC_STATES
                                + (context == null ? "\n" : "\n" + CONTEXT_PREDICATE)
                                + " ORDER BY " + CATALOG_ORDER;
                        try (PreparedStatement statement = forwardCursor(connection, sql, deadline)) {
                            try {
                                statement.setString(1, locale.getCodigo());
                                if (context != null) {
                                    statement.setString(2, context.name());
                                }
                                deadline.check();
                                try (ResultSet rows = statement.executeQuery()) {
                                    deadline.check();
                                    CatalogCursor cursor = new CatalogCursor(rows, offset, size, deadline);
                                    if (!cursor.hasNext()) {
                                        throw new LegalPublicDocumentReadException();
                                    }
                                    String revision = revisionCalculator.calculate(
                                            new LegalDocumentCatalogProjection(context, locale, cursor));
                                    deadline.check();
                                    if (!cursor.exhausted()) {
                                        throw new LegalPublicDocumentReadException();
                                    }
                                    return new LegalPublicDocumentCatalog(context, locale, revision,
                                            cursor.page(), page, size, cursor.count(),
                                            LegalPublicDocumentCatalog.pagesFor(cursor.count(), size));
                                }
                            } catch (SQLException | RuntimeException failure) {
                                deadline.cancel(statement);
                                throw failure;
                            }
                        }
                    });
            deadline.check();
            return Objects.requireNonNull(result);
        } catch (LegalPublicDocumentReadException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LegalPublicDocumentReadException(failure);
        }
    }

    Optional<LegalPublicDocumentVersion> findVersion(UUID versionId,
                                                    LegalPublicDocumentDeadline deadline) {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(deadline, "deadline").check();
        requireReadOnlyTransaction();
        try {
            Optional<LegalPublicDocumentVersion> result = jdbc.execute(
                    (ConnectionCallback<Optional<LegalPublicDocumentVersion>>) connection -> {
                        requireBoundConnection(connection);
                        deadline.check();
                        try (PreparedStatement statement = forwardCursor(
                                connection, EXACT_VERSION_SQL, deadline)) {
                            try {
                                statement.setInt(1, LegalManifestLimits.MAX_MARKDOWN_BYTES);
                                statement.setObject(2, versionId);
                                deadline.check();
                                try (ResultSet rows = statement.executeQuery()) {
                                    deadline.check();
                                    boolean found = rows.next();
                                    deadline.check();
                                    if (!found) {
                                        return Optional.empty();
                                    }
                                    LegalDocumentSummary summary = mapSummary(rows);
                                    if (!versionId.equals(summary.versionId())) {
                                        throw new LegalPublicDocumentReadException();
                                    }
                                    long octets = rows.getLong("markdown_octets");
                                    if (rows.wasNull() || octets < 1
                                            || octets > LegalManifestLimits.MAX_MARKDOWN_BYTES) {
                                        throw new LegalPublicDocumentReadException();
                                    }
                                    byte[] markdown = rows.getBytes("markdown_utf8");
                                    deadline.check();
                                    if (markdown == null || markdown.length != octets) {
                                        throw new LegalPublicDocumentReadException();
                                    }
                                    var validated = textValidator.validate(
                                            markdown, summary.sha256(), "public-document");
                                    deadline.check();
                                    if (!validated.passed()) {
                                        throw new LegalPublicDocumentReadException();
                                    }
                                    LegalPublicDocumentVersion version = new LegalPublicDocumentVersion(
                                            summary, validated.value().orElseThrow().text());
                                    boolean duplicate = rows.next();
                                    deadline.check();
                                    if (duplicate) {
                                        throw new LegalPublicDocumentReadException();
                                    }
                                    return Optional.of(version);
                                }
                            } catch (SQLException | RuntimeException failure) {
                                deadline.cancel(statement);
                                throw failure;
                            }
                        }
                    });
            deadline.check();
            return Objects.requireNonNull(result);
        } catch (LegalPublicDocumentReadException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LegalPublicDocumentReadException(failure);
        }
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    private static PreparedStatement forwardCursor(Connection connection, String sql,
                                                    LegalPublicDocumentDeadline deadline)
            throws SQLException {
        deadline.check();
        PreparedStatement statement = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        try {
            statement.setFetchSize(FETCH_SIZE);
            deadline.check();
            return statement;
        } catch (SQLException | RuntimeException failure) {
            deadline.cancel(statement);
            try {
                statement.close();
            } catch (SQLException | RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void requireReadOnlyTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        Connection.TRANSACTION_READ_COMMITTED)
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new LegalPublicDocumentReadException();
        }
    }

    private void requireBoundConnection(Connection connection) throws SQLException {
        if (connection.getAutoCommit() || !connection.isReadOnly()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED
                || !DataSourceUtils.isConnectionTransactional(
                        DataSourceUtils.getTargetConnection(connection), dataSource)) {
            throw new LegalPublicDocumentReadException();
        }
    }

    private static LegalDocumentSummary mapSummary(ResultSet row) throws SQLException {
        String persistedLocale = row.getString("locale");
        if (!LocaleLegal.ES_AR.getCodigo().equals(persistedLocale)) {
            throw new LegalPublicDocumentReadException();
        }
        OffsetDateTime effectiveAt = row.getObject("vigente_desde", OffsetDateTime.class);
        if (effectiveAt == null) {
            throw new LegalPublicDocumentReadException();
        }
        return new LegalDocumentSummary(row.getObject("id", UUID.class),
                TipoDocumentoLegal.valueOf(row.getString("tipo")),
                row.getString("version"), row.getString("titulo"), row.getString("sha256"),
                effectiveAt.toInstant(), EstadoVersionLegal.valueOf(row.getString("estado")),
                LocaleLegal.ES_AR);
    }

    private static String documentTypeOrderSql() {
        StringBuilder sql = new StringBuilder("CASE lines.tipo");
        for (TipoDocumentoLegal type : TipoDocumentoLegal.values()) {
            // Only compile-time enum values become SQL literals; request input is always bound.
            sql.append(" WHEN '").append(type.name()).append("' THEN ").append(type.ordinal());
        }
        return sql.append(" ELSE ").append(TipoDocumentoLegal.values().length)
                .append(" END").toString();
    }

    private static final class CatalogCursor implements Iterator<LegalDocumentSummary> {

        private final ResultSet rows;
        private final long offset;
        private final long endExclusive;
        private final List<LegalDocumentSummary> page;
        private final LegalPublicDocumentDeadline deadline;
        private boolean available;
        private boolean exhausted;
        private long count;

        private CatalogCursor(ResultSet rows, long offset, int size,
                              LegalPublicDocumentDeadline deadline) {
            this.rows = rows;
            this.offset = offset;
            this.endExclusive = Math.addExact(offset, size);
            this.page = new ArrayList<>(size);
            this.deadline = deadline;
        }

        @Override
        public boolean hasNext() {
            deadline.check();
            if (available || exhausted) {
                return available;
            }
            try {
                available = rows.next();
                deadline.check();
                exhausted = !available;
                return available;
            } catch (SQLException failure) {
                throw new LegalPublicDocumentReadException(failure);
            }
        }

        @Override
        public LegalDocumentSummary next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (count == LegalPublicDocumentCatalog.MAX_SAFE_INTEGER) {
                throw new LegalPublicDocumentReadException();
            }
            try {
                deadline.check();
                LegalDocumentSummary summary = mapSummary(rows);
                deadline.check();
                if (count >= offset && count < endExclusive) {
                    page.add(summary);
                }
                count = Math.addExact(count, 1);
                available = false;
                return summary;
            } catch (SQLException failure) {
                throw new LegalPublicDocumentReadException(failure);
            }
        }

        private boolean exhausted() {
            return exhausted && !available;
        }

        private List<LegalDocumentSummary> page() {
            return page;
        }

        private long count() {
            return count;
        }
    }
}
