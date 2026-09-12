package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.CanonicalTextValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryReadException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalExportSnapshotReader;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPackageException.Code.*;

/** Internal worker input only: C/D must authenticate and authorize the durable job before calling this reader. */
@Service
public class WorkshopExportSnapshotService {
    static final int MAX_BYTES = 64 * 1024 * 1024;
    static final int MAX_ROWS = 50_000;
    static final int MAX_TOTAL_ROWS = 200_000;
    static final int MAX_FIELD_CHARS = 262_144;
    private static final JsonFactory JSON = new JsonFactory();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final LegalExportSnapshotReader legal;

    public WorkshopExportSnapshotService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.legal = new LegalExportSnapshotReader(jdbc);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(60);
    }

    public ExportSnapshot capture(long actorId, long tallerId, long expectedTokenVersion) {
        if (actorId <= 0 || tallerId <= 0 || expectedTokenVersion < 0) throw failure(ACCESS_DENIED);
        long started = System.nanoTime();
        try {
            return Objects.requireNonNull(transaction.execute(status -> jdbc.execute((ConnectionCallback<ExportSnapshot>) connection -> {
                requireSnapshotConnection(connection);
                var capture = new Capture(connection, started);
                capture.statement("SET LOCAL statement_timeout = '15s'");
                capture.statement("SET LOCAL lock_timeout = '5s'");
                Instant observedAt;
                try (var statement = connection.prepareStatement("SELECT statement_timestamp()"); var row = statement.executeQuery()) {
                    if (!row.next()) throw failure(UNAVAILABLE);
                    observedAt = row.getObject(1, OffsetDateTime.class).toInstant();
                }
                var actor = capture.actor(actorId, tallerId, expectedTokenVersion);
                for (ExportQuery query : ExportBusinessCatalog.queries()) capture.rows(query, new Object[]{tallerId}, null);
                capture.photos(tallerId, observedAt);
                capture.qr(tallerId);
                capture.legal(actor, observedAt);
                capture.add(new ExportFile("LEEME.txt", "text/plain; charset=utf-8", -1, README.getBytes(StandardCharsets.UTF_8)));
                capture.check();
                return new ExportSnapshot(actorId, tallerId, observedAt, capture.files, capture.pending);
            })));
        } catch (ExportPackageException failure) { throw failure; }
        catch (LegalExportSnapshotReader.CapacityExceededException failure) { throw failure(CAPACITY_EXCEEDED); }
        catch (LegalAcceptanceHistoryReadException failure) { throw failure(INVALID_EVIDENCE); }
        catch (RuntimeException failure) { throw failure(UNAVAILABLE); }
    }

    private void requireSnapshotConnection(Connection connection) throws SQLException {
        if (connection.getAutoCommit() || !connection.isReadOnly()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !DataSourceUtils.isConnectionTransactional(DataSourceUtils.getTargetConnection(connection), jdbc.getDataSource())) {
            throw failure(UNAVAILABLE);
        }
    }

    private final class Capture {
        private final Connection connection;
        private final long started;
        private final List<ExportFile> files = new ArrayList<>();
        private final List<ExportSnapshot.PendingPhoto> pending = new ArrayList<>();
        private int bytes;
        private int totalRows;
        Capture(Connection connection, long started) { this.connection = connection; this.started = started; }
        void check() {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - started > Duration.ofSeconds(60).toNanos()) {
                throw failure(CAPACITY_EXCEEDED);
            }
        }
        void statement(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
        void add(ExportFile file) {
            check();
            if (file.size() > MAX_BYTES - bytes) throw failure(CAPACITY_EXCEEDED);
            bytes += file.size(); files.add(file);
        }
        ExportBuffer buffer() { check(); return new ExportBuffer(MAX_BYTES - bytes); }
        void record() { if (++totalRows > MAX_TOTAL_ROWS) throw failure(CAPACITY_EXCEEDED); check(); }
        LegalActorSnapshot actor(long id, long workshop, long version) throws SQLException {
            try (var statement = connection.prepareStatement("""
                    SELECT u.role, u.active, u.email_verificado, u.token_version, t.activo
                    FROM public.users u JOIN public.talleres t ON t.id=u.taller_id
                    WHERE u.id=? AND u.taller_id=?
                    """)) {
                statement.setLong(1,id); statement.setLong(2,workshop);
                try (var row=statement.executeQuery()) {
                    if (!row.next() || !"ADMIN".equals(row.getString("role")) || !row.getBoolean("active")
                            || !row.getBoolean("activo") || !row.getBoolean("email_verificado")
                            || row.getLong("token_version") != version || row.wasNull()) throw failure(ACCESS_DENIED);
                    var actor = new LegalActorSnapshot(id, workshop, UserRole.ADMIN, version, true, true);
                    if (row.next()) throw failure(ACCESS_DENIED);
                    return actor;
                }
            }
        }
        void rows(ExportQuery query, Object[] arguments, RowObserver observer) throws SQLException {
            var buffer = buffer(); int count=0;
            try (var json=JSON.createGenerator(buffer); var statement=connection.prepareStatement(query.sql()+" LIMIT "+(MAX_ROWS+1))) {
                statement.setFetchSize(256); statement.setQueryTimeout(15);
                for (int index=0; index<arguments.length; index++) statement.setObject(index+1,arguments[index]);
                json.writeStartArray();
                try (var rows=statement.executeQuery()) {
                    ResultSetMetaData columns=rows.getMetaData();
                    while (rows.next()) {
                        if (++count > MAX_ROWS) throw failure(CAPACITY_EXCEEDED);
                        record();
                        if (!rows.getBoolean("_relaciones_validas") || rows.wasNull()) throw failure(INCONSISTENT_RELATION);
                        if (observer != null) observer.read(rows);
                        json.writeStartObject();
                        for (int column=1; column<=columns.getColumnCount(); column++) {
                            String name=columns.getColumnLabel(column);
                            if (name.equals("_relaciones_validas")) continue;
                            json.writeFieldName(name); value(json,rows,column,columns.getColumnType(column));
                        }
                        json.writeEndObject();
                    }
                }
                json.writeEndArray();
            } catch (IOException failure) { throw failure(UNAVAILABLE); }
            add(new ExportFile("datos/"+query.category()+".json", "application/json", count, buffer.toByteArray()));
        }
        void photos(long workshop, Instant observed) throws SQLException {
            rows(new ExportQuery("fotos_privadas", """
                    SELECT p.id, p.reparacion_id, p.reparacion_original_id, p.taller_id, p.user_id,
                           p.nombre, p.mime_type, p.bytes, p.sha256, p.momento, p.estado,
                           p.confirmado_en, p.expira_en, p.retener_hasta, p.asociada_en,
                           CASE WHEN p.estado='ASOCIADA' AND p.reparacion_id IS NOT NULL AND p.retener_hasta>?
                                THEN 'PENDIENTE_C' ELSE 'NO_DISPONIBLE_EN_SNAPSHOT' END AS archivo_estado,
                           (EXISTS(SELECT 1 FROM public.users u WHERE u.id=p.user_id AND u.taller_id=p.taller_id)
                            AND ((p.reparacion_id IS NULL AND p.estado='ELIMINADA') OR EXISTS(SELECT 1 FROM public.reparaciones r
                                 WHERE r.id=p.reparacion_id AND r.taller_id=p.taller_id
                                   AND r.id=p.reparacion_original_id))) AS _relaciones_validas
                    FROM public.reparacion_fotos_privadas p WHERE p.taller_id=? ORDER BY p.id
                    """), new Object[]{observed.atOffset(ZoneOffset.UTC),workshop}, row -> {
                if ("PENDIENTE_C".equals(row.getString("archivo_estado"))) {
                    UUID id=row.getObject("id",UUID.class);
                    String mime=row.getString("mime_type"), sha=row.getString("sha256"); long length=row.getLong("bytes");
                    if (!("image/jpeg".equals(mime)||"image/png".equals(mime)) || sha==null || !sha.matches("[0-9a-f]{64}")
                            || length<12 || length>8_000_000) throw failure(INVALID_EVIDENCE);
                    pending.add(new ExportSnapshot.PendingPhoto(id,"archivos/fotos/"+id+("image/png".equals(mime)?".png":".jpg"),sha,length));
                }
            });
            rows(new ExportQuery("fotos_legacy", """
                    SELECT f.reparacion_id, f.momento, TRUE AS url_excluida,
                           'ORIGEN_NO_ACREDITADO'::text AS motivo_exclusion,
                           TRUE AS _relaciones_validas
                    FROM public.reparacion_fotos f JOIN public.reparaciones r ON r.id=f.reparacion_id
                    WHERE r.taller_id=? ORDER BY f.reparacion_id, f.momento COLLATE "C", f.url COLLATE "C"
                    """),new Object[]{workshop},null);
        }
        void qr(long workshop) throws SQLException {
            try (var statement=connection.prepareStatement("""
                    SELECT sha256, octet_length(png) AS size,
                           CASE WHEN octet_length(png) BETWEEN 1 AND 1048576 THEN png ELSE NULL END AS png
                    FROM public.taller_qr_cobro WHERE taller_id=?
                    """)) {
                statement.setLong(1,workshop);
                try (var row=statement.executeQuery()) {
                    if (row.next()) {
                        byte[] png=row.getBytes("png");
                        byte[] signature={(byte)137,80,78,71,13,10,26,10};
                        if (png==null || png.length<signature.length || png.length!=row.getInt("size")
                                || !Arrays.equals(Arrays.copyOf(png,8),signature) || !ExportFile.digest(png).equals(row.getString("sha256"))) {
                            throw failure(INVALID_EVIDENCE);
                        }
                        add(new ExportFile("archivos/qr-cobro.png","image/png",-1,png));
                        if (row.next()) throw failure(INVALID_EVIDENCE);
                    }
                }
            }
            rows(new ExportQuery("qr_cobro", """
                    SELECT taller_id, sha256, 'archivos/qr-cobro.png'::text AS archivo, TRUE AS _relaciones_validas
                    FROM public.taller_qr_cobro WHERE taller_id=? ORDER BY taller_id
                    """),new Object[]{workshop},null);
        }
        void legal(LegalActorSnapshot actor, Instant observed) throws SQLException {
            var history=legal.read(actor,observed);
            var documents=new LinkedHashMap<UUID,com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryPage.Document>();
            var buffer=buffer();
            try (var json=JSON.createGenerator(buffer)) {
                json.writeStartArray();
                for (var acceptance:history) {
                    record(); json.writeStartObject();
                    json.writeStringField("id",acceptance.id().toString());
                    json.writeStringField("requisito_version_id",acceptance.requirementVersionId().toString());
                    json.writeStringField("contexto",acceptance.context().name());
                    json.writeStringField("tipo_acto",acceptance.actType().name());
                    json.writeStringField("afirmacion",acceptance.statement());
                    json.writeStringField("afirmacion_sha256",acceptance.statementSha256());
                    json.writeStringField("aceptado_en",acceptance.acceptedAt().toString());
                    json.writeArrayFieldStart("documentos");
                    for (var document:acceptance.documents()) {
                        var previous=documents.putIfAbsent(document.documentVersionId(),document);
                        if (previous!=null && !previous.equals(document)) throw failure(INVALID_EVIDENCE);
                        json.writeStartObject(); json.writeStringField("id",document.documentVersionId().toString());
                        json.writeStringField("tipo",document.type().name()); json.writeStringField("version",document.version());
                        json.writeStringField("titulo",document.title()); json.writeStringField("sha256",document.sha256());
                        json.writeStringField("archivo","documentos/"+document.documentVersionId()+".md"); json.writeEndObject();
                    }
                    json.writeEndArray(); json.writeEndObject();
                }
                json.writeEndArray();
            } catch (IOException failure) { throw failure(UNAVAILABLE); }
            add(new ExportFile("datos/aceptaciones_propias.json","application/json",history.size(),buffer.toByteArray()));
            for (var document:documents.values()) {
                check();
                try (var statement=connection.prepareStatement("""
                        SELECT sha256, CASE WHEN octet_length(convert_to(contenido_markdown,'UTF8')) BETWEEN 1 AND 1048576
                          THEN convert_to(contenido_markdown,'UTF8') ELSE NULL END AS content
                        FROM public.legal_documento_versiones WHERE id=?
                        """)) {
                    statement.setObject(1,document.documentVersionId());
                    try (var row=statement.executeQuery()) {
                        if (!row.next() || !document.sha256().equals(row.getString("sha256"))) throw failure(INVALID_EVIDENCE);
                        byte[] content=row.getBytes("content");
                        if (!new CanonicalTextValidator().validate(content,document.sha256(),"export/document").passed() || row.next()) throw failure(INVALID_EVIDENCE);
                        add(new ExportFile("documentos/"+document.documentVersionId()+".md","text/markdown; charset=utf-8",-1,content));
                    }
                }
            }
        }
        void value(JsonGenerator json, ResultSet row, int column, int type) throws SQLException, IOException {
            switch(type) {
                case Types.VARCHAR, Types.LONGVARCHAR, Types.CHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> {
                    try (Reader reader=row.getCharacterStream(column)) {
                        if (reader==null) { json.writeNull(); break; }
                        var text=new StringBuilder(); char[] chunk=new char[4096]; int read;
                        while ((read=reader.read(chunk))!=-1) {
                            check(); if (read>MAX_FIELD_CHARS-text.length()) throw failure(CAPACITY_EXCEEDED);
                            text.append(chunk,0,read);
                        }
                        json.writeString(text.toString());
                    }
                }
                case Types.BIGINT -> { long value=row.getLong(column); if(row.wasNull()) json.writeNull(); else json.writeString(Long.toString(value)); }
                case Types.NUMERIC, Types.DECIMAL -> { var value=row.getBigDecimal(column); if(value==null) json.writeNull(); else json.writeString(value.toPlainString()); }
                case Types.INTEGER, Types.SMALLINT -> { int value=row.getInt(column); if(row.wasNull()) json.writeNull(); else json.writeNumber(value); }
                case Types.BOOLEAN, Types.BIT -> { boolean value=row.getBoolean(column); if(row.wasNull()) json.writeNull(); else json.writeBoolean(value); }
                case Types.DATE -> { var value=row.getObject(column,LocalDate.class); if(value==null) json.writeNull(); else json.writeString(value.toString()); }
                case Types.TIMESTAMP -> {
                    // pgjdbc reports timestamptz as TIMESTAMP too; the SQL type name distinguishes its semantics.
                    if ("timestamptz".equals(row.getMetaData().getColumnTypeName(column))) {
                        var value=row.getObject(column,OffsetDateTime.class); if(value==null) json.writeNull(); else json.writeString(value.toInstant().toString());
                    } else { var value=row.getObject(column,LocalDateTime.class); if(value==null) json.writeNull(); else json.writeString(value.toString()); }
                }
                case Types.TIMESTAMP_WITH_TIMEZONE -> { var value=row.getObject(column,OffsetDateTime.class); if(value==null) json.writeNull(); else json.writeString(value.toInstant().toString()); }
                case Types.OTHER -> { Object value=row.getObject(column); if(value==null) json.writeNull(); else if(value instanceof UUID) json.writeString(value.toString()); else throw failure(INVALID_EVIDENCE); }
                default -> throw failure(INVALID_EVIDENCE);
            }
        }
    }
    @FunctionalInterface private interface RowObserver { void read(ResultSet row) throws SQLException; }
    private static ExportPackageException failure(ExportPackageException.Code code) { return new ExportPackageException(code); }
    private static final String README = """
            OrdenFix — datos locales del taller, formato ordenfix-export/1
            Este paquete del corte B todavía no es una exportación integral lista para entrega.
            El manifiesto identifica archivos, registros, tamaños, SHA-256 y fotos pendientes.
            Los JSON contienen arreglos de registros. BIGINT y decimales son cadenas exactas.
            Las fechas sin zona conservan su calendario/hora; las fechas con zona se expresan en UTC.
            Se conservan nulls, duplicados, inactivos y anulaciones. El orden de ítems es técnico.
            Cobros: registros manuales del taller sobre pagos recibidos fuera de OrdenFix.
            Suscripción: relación comercial del taller con OrdenFix, separada de sus clientes.
            No se emiten comprobantes fiscales ni se acreditan pagos a clientes.
            El historial legal corresponde sólo al titular solicitante; los Markdown son textos exactos.
            Exclusiones: contraseñas, PIN/patrón, tokens, código de seguimiento, URLs legacy,
            datos técnicos de proveedores, IP/UA y aceptaciones personales de otros usuarios.
            Las fotos remotas requieren incorporación, validación y autorización en el corte C.
            Cifrado, expiración y entrega autorizada se completan en C/D. No publicar este directorio.
            """;
}
