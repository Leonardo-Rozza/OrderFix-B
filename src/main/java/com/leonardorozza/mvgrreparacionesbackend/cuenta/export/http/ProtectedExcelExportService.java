package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBlockedException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBusyException;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPackageException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService.ExportSession;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.ExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Protects the existing operational workbook without changing its format or financial calculations. */
@Service
public class ProtectedExcelExportService {
    static final long MAX_ROWS = 50_000;
    static final long MAX_SOURCE_BYTES = 16L * 1024 * 1024;
    static final int MAX_OUTPUT_BYTES = 32 * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(ProtectedExcelExportService.class);

    // Metadata only: no field values (including credential ciphertexts/hashes) leave PostgreSQL here.
    // Counting whole tenant categories is deliberately conservative. Reverse references cover the
    // unscoped repuestos collection and sumByReparacionIds used by the existing workbook mapper.
    private static final List<Estimate> ESTIMATES = List.of(
            estimate("talleres", "", "q.id = ?", "true", false,
                    "nombre", "email_contacto", "telefono", "alias_cobro", "titular_cobro", "entidad_cobro"),
            estimate("users", "", "q.taller_id = ?", "true", false,
                    "username", "password", "email", "role"),
            estimate("suscripciones", "", "q.taller_id = ?", "true", false,
                    "plan", "estado", "consumo_mes", "mp_preapproval_id", "mp_payer_id", "mp_status",
                    "mp_external_reference", "mp_checkout_init_point", "mp_last_authorized_payment_id"),
            estimate("clientes", "", "q.taller_id = ?", "true", false,
                    "nombre", "apellido", "telefono", "email", "direccion"),
            estimate("equipos", "LEFT JOIN public.clientes c ON c.id = q.cliente_id", "q.taller_id = ?",
                    "c.id IS NOT NULL AND c.taller_id = q.taller_id", false,
                    "marca", "modelo", "imei", "color", "descripcion"),
            estimate("reparaciones", """
                    LEFT JOIN public.equipos e ON e.id = q.equipo_id
                    LEFT JOIN public.clientes c ON c.id = e.cliente_id
                    LEFT JOIN public.users u ON u.id = q.tecnico_id
                    """, "q.taller_id = ?", """
                    e.id IS NOT NULL AND e.taller_id = q.taller_id AND c.id IS NOT NULL
                    AND c.taller_id = q.taller_id
                    AND (q.tecnico_id IS NULL OR (u.id IS NOT NULL AND u.taller_id = q.taller_id))
                    """, false, "descripcion_problema", "estado", "codigo_seguimiento", "numero_orden",
                    "tiene_cuenta_vinculada", "patron_desbloqueo_cifrado", "pin_desbloqueo_cifrado",
                    "accesorios", "condiciones_ingreso", "observaciones", "garantia_condiciones"),
            estimate("cobros", """
                    LEFT JOIN public.reparaciones r ON r.id = q.reparacion_id
                    LEFT JOIN public.users u ON u.id = q.anulado_por_id
                    """, "(q.taller_id = ? OR r.taller_id = ?)", """
                    r.id IS NOT NULL AND r.taller_id = q.taller_id
                    AND (q.anulado_por_id IS NULL OR (u.id IS NOT NULL AND u.taller_id = q.taller_id))
                    """, true, "metodo", "observaciones", "referencia", "motivo_anulacion"),
            estimate("presupuestos", "LEFT JOIN public.reparaciones r ON r.id = q.reparacion_id", "q.taller_id = ?",
                    "r.id IS NOT NULL AND r.taller_id = q.taller_id", false, "estado", "tipo", "observaciones"),
            estimate("repuestos", "LEFT JOIN public.reparaciones r ON r.id = q.reparacion_id",
                    "(q.taller_id = ? OR r.taller_id = ?)",
                    "q.reparacion_id IS NULL OR (r.id IS NOT NULL AND r.taller_id = q.taller_id)", true,
                    "nombre", "descripcion"),
            estimate("presupuesto_items", "JOIN public.presupuestos p ON p.id = q.presupuesto_id",
                    "p.taller_id = ?", "true", false, "descripcion", "tipo_item", "calidad")
    );

    private final ExportReauthenticationService reauth;
    private final ExportService excel;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int maximumOutputBytes;

    @Autowired
    public ProtectedExcelExportService(ExportReauthenticationService reauth, ExportService excel,
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(reauth, excel, jdbc, manager, MAX_OUTPUT_BYTES);
    }

    ProtectedExcelExportService(ExportReauthenticationService reauth, ExportService excel,
            JdbcTemplate jdbc, PlatformTransactionManager manager, int maximumOutputBytes) {
        this.reauth = Objects.requireNonNull(reauth);
        this.excel = Objects.requireNonNull(excel);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // The preflight and the unchanged JPA findAll calls must see the same database snapshot.
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(false);
        transaction.setTimeout(30);
        if (maximumOutputBytes <= 0) throw new IllegalArgumentException("Límite de Excel inválido.");
        this.maximumOutputBytes = maximumOutputBytes;
    }

    public byte[] download(String accessToken, String proof) {
        byte[][] generated = new byte[1][];
        ExportSession[] authorized = new ExportSession[1];
        boolean delivered = false;
        try {
            byte[] result = transaction.execute(status -> {
                ExportSession actor = reauth.authorize(accessToken);
                requireTenant(actor);
                // A concurrent workshop deactivation must wait or abort this RR snapshot.
                if (!Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT activo FROM public.talleres WHERE id = ? FOR SHARE", Boolean.class, actor.tallerId()))) {
                    throw denied();
                }
                reauth.consume(accessToken, proof, ExportReauthenticationPurpose.DESCARGAR_EXPORTACION);
                preflight(actor.tallerId());
                generated[0] = excel.exportarExcel();
                if (generated[0] == null || generated[0].length == 0) throw unavailable();
                if (generated[0].length > maximumOutputBytes) throw capacity();
                ExportSession current = reauth.authorize(accessToken);
                requireTenant(current);
                if (!actor.equals(current)) throw denied();
                authorized[0] = current;
                return generated[0];
            });
            if (result == null || authorized[0] == null) throw unavailable();
            // This is preparation after commit, not a claim that the client received the response body.
            log.info("Excel operativo preparado: userId={}, tallerId={}, formato=XLSX, bytes={}",
                    authorized[0].userId(), authorized[0].tallerId(), result.length);
            delivered = true;
            return result;
        } catch (BadRequestException | UnauthorizedException | AccessDeniedException | ExportPackageException
                | WorkshopClosureBlockedException | WorkshopClosureBusyException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw unavailable();
        } finally {
            if (!delivered && generated[0] != null) Arrays.fill(generated[0], (byte) 0);
        }
    }

    private void preflight(long tallerId) {
        long rows = 0;
        long bytes = 0;
        for (Estimate estimate : ESTIMATES) {
            long limit = MAX_ROWS - rows + 1;
            Object[] arguments = estimate.inbound() ? new Object[]{tallerId, tallerId, limit} : new Object[]{tallerId, limit};
            Footprint footprint = jdbc.queryForObject(estimate.sql(), (row, index) ->
                    new Footprint(row.getLong("rows"), row.getLong("bytes"), row.getBoolean("valid")), arguments);
            if (footprint == null || footprint.rows() < 0 || footprint.bytes() < 0) throw unavailable();
            if (!footprint.valid()) throw new ExportPackageException(ExportPackageException.Code.INCONSISTENT_RELATION);
            if (footprint.rows() > MAX_ROWS - rows || footprint.bytes() > MAX_SOURCE_BYTES - bytes) throw capacity();
            rows += footprint.rows();
            bytes += footprint.bytes();
        }
    }

    private static Estimate estimate(String table, String joins, String scope, String relation,
            boolean inbound, String... textColumns) {
        String textBytes = Arrays.stream(textColumns)
                .map(column -> " + COALESCE(octet_length(q." + column + "), 0)::bigint")
                .collect(Collectors.joining());
        return new Estimate("""
                SELECT count(*) AS rows, COALESCE(sum(bytes), 0) AS bytes,
                       COALESCE(bool_and(valid), true) AS valid
                  FROM (SELECT pg_column_size(q)::bigint %s AS bytes, (%s) AS valid
                          FROM public.%s q %s WHERE %s LIMIT ?) bounded
                """.formatted(textBytes, relation, table, joins, scope), inbound);
    }

    private static void requireTenant(ExportSession actor) {
        if (actor == null || actor.userId() <= 0 || actor.tallerId() <= 0
                || !Objects.equals(TenantContext.getTallerId(), actor.tallerId())) throw denied();
    }

    private static ExportPackageException denied() { return new ExportPackageException(ExportPackageException.Code.ACCESS_DENIED); }
    private static ExportPackageException capacity() { return new ExportPackageException(ExportPackageException.Code.CAPACITY_EXCEEDED); }
    private static ExportPackageException unavailable() { return new ExportPackageException(ExportPackageException.Code.UNAVAILABLE); }
    private record Estimate(String sql, boolean inbound) { }
    record Footprint(long rows, long bytes, boolean valid) { }
}
