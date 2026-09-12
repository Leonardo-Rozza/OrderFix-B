package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import java.util.List;

/**
 * Fixed business projections for the export format. Each projection reads only
 * the selected workshop's own values. Related rows contribute a boolean integrity
 * check, never values that could expose another workshop's data.
 */
public final class ExportBusinessCatalog {
    private static final List<ExportQuery> QUERIES = List.of(
            new ExportQuery("talleres", """
                    SELECT t.id, t.nombre, t.email_contacto, t.telefono, t.activo,
                           t.created_at, t.updated_at, t.secuencia_orden, t.anio_secuencia_orden,
                           t.alias_cobro, t.titular_cobro, t.entidad_cobro, t.mostrar_en_resumen,
                           TRUE AS _relaciones_validas
                      FROM public.talleres t
                     WHERE t.id = ?
                     ORDER BY t.id
                    """),
            new ExportQuery("users", """
                    SELECT u.id, u.taller_id, u.username, u.email, u.role, u.active,
                           u.email_verificado,
                           EXISTS (SELECT 1 FROM public.talleres t
                                    WHERE t.id = u.taller_id) AS _relaciones_validas
                      FROM public.users u
                     WHERE u.taller_id = ?
                     ORDER BY u.id
                    """),
            new ExportQuery("clientes", """
                    SELECT c.id, c.taller_id, c.nombre, c.apellido, c.telefono, c.email,
                           c.direccion,
                           EXISTS (SELECT 1 FROM public.talleres t
                                    WHERE t.id = c.taller_id) AS _relaciones_validas
                      FROM public.clientes c
                     WHERE c.taller_id = ?
                     ORDER BY c.id
                    """),
            new ExportQuery("equipos", """
                    SELECT e.id, e.taller_id, e.marca, e.modelo, e.imei, e.color,
                           e.descripcion, e.cliente_id,
                           EXISTS (SELECT 1 FROM public.clientes c
                                    WHERE c.id = e.cliente_id
                                      AND c.taller_id = e.taller_id) AS _relaciones_validas
                      FROM public.equipos e
                     WHERE e.taller_id = ?
                     ORDER BY e.id
                    """),
            new ExportQuery("reparaciones", """
                    SELECT r.id, r.taller_id, r.descripcion_problema, r.estado,
                           r.precio_estimado, r.precio_final, r.fecha_ingreso,
                           r.fecha_estimada_entrega, r.fecha_entrega, r.equipo_id,
                           r.created_at, r.updated_at, r.accesorios, r.condiciones_ingreso,
                           r.observaciones, r.tecnico_id, r.numero_orden, r.mojado,
                           r.trabajo_en_placa, r.no_testeable_al_ingreso,
                           r.tiene_bloqueo_pantalla, r.tiene_cuenta_vinculada,
                           r.cliente_conoce_credenciales, r.fecha_conformidad_entrega,
                           r.garantia_dias, r.garantia_inicio, r.garantia_fin,
                           r.garantia_condiciones, r.es_garantia, r.reparacion_origen_id,
                           (EXISTS (SELECT 1 FROM public.equipos e
                                      JOIN public.clientes c ON c.id = e.cliente_id
                                     WHERE e.id = r.equipo_id
                                       AND e.taller_id = r.taller_id
                                       AND c.taller_id = r.taller_id)
                            AND (r.tecnico_id IS NULL OR EXISTS (
                                SELECT 1 FROM public.users u
                                 WHERE u.id = r.tecnico_id AND u.taller_id = r.taller_id))
                            AND (r.reparacion_origen_id IS NULL OR EXISTS (
                                SELECT 1 FROM public.reparaciones origen
                                 WHERE origen.id = r.reparacion_origen_id
                                   AND origen.taller_id = r.taller_id))) AS _relaciones_validas
                      FROM public.reparaciones r
                     WHERE r.taller_id = ?
                     ORDER BY r.id
                    """),
            new ExportQuery("repuestos", """
                    SELECT r.id, r.taller_id, r.nombre, r.descripcion, r.precio,
                           r.reparacion_id, r.articulo_id, r.cantidad,
                           ((r.reparacion_id IS NULL OR EXISTS (
                                SELECT 1 FROM public.reparaciones reparacion
                                 WHERE reparacion.id = r.reparacion_id
                                   AND reparacion.taller_id = r.taller_id))
                            AND (r.articulo_id IS NULL OR EXISTS (
                                SELECT 1 FROM public.articulos a
                                 WHERE a.id = r.articulo_id
                                   AND a.taller_id = r.taller_id))) AS _relaciones_validas
                      FROM public.repuestos r
                     WHERE r.taller_id = ?
                     ORDER BY r.id
                    """),
            new ExportQuery("articulos", """
                    SELECT a.id, a.taller_id, a.nombre, a.descripcion, a.sku, a.precio,
                           a.costo, a.stock, a.stock_minimo, a.activo, a.created_at, a.updated_at,
                           EXISTS (SELECT 1 FROM public.talleres t
                                    WHERE t.id = a.taller_id) AS _relaciones_validas
                      FROM public.articulos a
                     WHERE a.taller_id = ?
                     ORDER BY a.id
                    """),
            new ExportQuery("presupuestos", """
                    SELECT p.id, p.taller_id, p.reparacion_id, p.estado, p.total,
                           p.observaciones, p.fecha_respuesta, p.created_at, p.updated_at,
                           p.tipo, p.validez_dias, p.valido_hasta,
                           EXISTS (SELECT 1 FROM public.reparaciones r
                                    WHERE r.id = p.reparacion_id
                                      AND r.taller_id = p.taller_id) AS _relaciones_validas
                      FROM public.presupuestos p
                     WHERE p.taller_id = ?
                     ORDER BY p.id
                    """),
            new ExportQuery("presupuesto_items", """
                    SELECT i.presupuesto_id, i.descripcion, i.cantidad, i.precio_unitario,
                           i.tipo_item, i.calidad,
                           EXISTS (SELECT 1 FROM public.reparaciones r
                                    WHERE r.id = p.reparacion_id
                                      AND r.taller_id = p.taller_id) AS _relaciones_validas
                      FROM public.presupuesto_items i
                      JOIN public.presupuestos p ON p.id = i.presupuesto_id
                     WHERE p.taller_id = ?
                     ORDER BY i.presupuesto_id, i.descripcion COLLATE "C", i.cantidad,
                              i.precio_unitario, i.tipo_item COLLATE "C",
                              i.calidad COLLATE "C" NULLS FIRST
                    """),
            new ExportQuery("cobros", """
                    SELECT c.id, c.taller_id, c.reparacion_id, c.monto, c.metodo,
                           c.observaciones, c.created_at, c.referencia, c.anulado_at,
                           c.anulado_por_id, c.motivo_anulacion,
                           (EXISTS (SELECT 1 FROM public.reparaciones r
                                     WHERE r.id = c.reparacion_id
                                       AND r.taller_id = c.taller_id)
                            AND (c.anulado_por_id IS NULL OR EXISTS (
                                SELECT 1 FROM public.users u
                                 WHERE u.id = c.anulado_por_id
                                   AND u.taller_id = c.taller_id))) AS _relaciones_validas
                      FROM public.cobros c
                     WHERE c.taller_id = ?
                     ORDER BY c.id
                    """),
            new ExportQuery("suscripciones", """
                    SELECT s.id, s.taller_id, s.plan, s.estado, s.fecha_inicio,
                           s.fecha_fin_trial, s.proximo_cobro, s.consumo_mes,
                           s.reparaciones_mes, s.mp_preapproval_id, s.mp_status,
                           s.mp_next_payment_at, s.created_at, s.updated_at,
                           EXISTS (SELECT 1 FROM public.talleres t
                                    WHERE t.id = s.taller_id) AS _relaciones_validas
                      FROM public.suscripciones s
                     WHERE s.taller_id = ?
                     ORDER BY s.id
                    """),
            new ExportQuery("subscription_payments", """
                    SELECT p.id, p.suscripcion_id, p.provider, p.external_authorized_payment_id,
                           p.external_payment_id, p.amount, p.currency, p.invoice_status,
                           p.payment_status, p.status_detail, p.debit_at, p.provider_created_at,
                           p.provider_modified_at, p.created_at, p.updated_at,
                           EXISTS (SELECT 1 FROM public.talleres t
                                    WHERE t.id = s.taller_id) AS _relaciones_validas
                      FROM public.subscription_payments p
                      JOIN public.suscripciones s ON s.id = p.suscripcion_id
                     WHERE s.taller_id = ?
                     ORDER BY p.id
                    """)
    );

    private ExportBusinessCatalog() {
    }

    public static List<ExportQuery> queries() {
        return QUERIES;
    }
}
