-- ============================================================
-- V13 - Presupuesto pro (§5)
-- Tipo (original/adicional), validez/vencimiento, y discriminación
-- mano de obra vs repuesto + calidad del repuesto en cada ítem.
-- ============================================================

-- Cabecera del presupuesto
ALTER TABLE presupuestos ADD COLUMN tipo         VARCHAR(10) NOT NULL DEFAULT 'ORIGINAL';
ALTER TABLE presupuestos ADD COLUMN validez_dias INTEGER     NOT NULL DEFAULT 7;
ALTER TABLE presupuestos ADD COLUMN valido_hasta TIMESTAMP;

-- Ítems: naturaleza (mano de obra / repuesto) y calidad del repuesto
ALTER TABLE presupuesto_items ADD COLUMN tipo_item VARCHAR(15) NOT NULL DEFAULT 'MANO_DE_OBRA';
ALTER TABLE presupuesto_items ADD COLUMN calidad   VARCHAR(25);
