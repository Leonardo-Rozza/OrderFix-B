-- ============================================================
-- V21 - Validaciones monetarias para escrituras nuevas
--
-- Se agregan NOT VALID para no bloquear registros históricos inconsistentes.
-- PostgreSQL sí aplica estos checks a INSERT/UPDATE nuevos. La validación total
-- queda para una migración posterior, después de auditar y corregir los legacy.
-- ============================================================

ALTER TABLE reparaciones
    ADD CONSTRAINT ck_reparaciones_precio_estimado_no_negativo
        CHECK (precio_estimado IS NULL OR precio_estimado >= 0) NOT VALID;
ALTER TABLE reparaciones
    ADD CONSTRAINT ck_reparaciones_precio_final_no_negativo
        CHECK (precio_final IS NULL OR precio_final >= 0) NOT VALID;

ALTER TABLE repuestos
    ADD CONSTRAINT ck_repuestos_precio_no_negativo
        CHECK (precio >= 0) NOT VALID;
ALTER TABLE repuestos
    ADD CONSTRAINT ck_repuestos_cantidad_positiva
        CHECK (cantidad > 0) NOT VALID;

ALTER TABLE cobros
    ADD CONSTRAINT ck_cobros_monto_positivo
        CHECK (monto > 0) NOT VALID;

ALTER TABLE presupuestos
    ADD CONSTRAINT ck_presupuestos_total_no_negativo
        CHECK (total >= 0) NOT VALID;

ALTER TABLE presupuesto_items
    ADD CONSTRAINT ck_presupuesto_items_precio_no_negativo
        CHECK (precio_unitario >= 0) NOT VALID;
ALTER TABLE presupuesto_items
    ADD CONSTRAINT ck_presupuesto_items_cantidad_positiva
        CHECK (cantidad > 0) NOT VALID;

ALTER TABLE articulos
    ADD CONSTRAINT ck_articulos_precio_no_negativo
        CHECK (precio >= 0) NOT VALID;
ALTER TABLE articulos
    ADD CONSTRAINT ck_articulos_costo_no_negativo
        CHECK (costo IS NULL OR costo >= 0) NOT VALID;
ALTER TABLE articulos
    ADD CONSTRAINT ck_articulos_stock_no_negativo
        CHECK (stock >= 0) NOT VALID;
ALTER TABLE articulos
    ADD CONSTRAINT ck_articulos_stock_minimo_no_negativo
        CHECK (stock_minimo >= 0) NOT VALID;
