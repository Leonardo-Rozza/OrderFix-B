-- ============================================================
-- V14 - Fotos pre/post + conformidad de entrega (§8, §9)
-- Cada foto registra su momento (ingreso/post-reparación) y la reparación
-- guarda cuándo el cliente retiró conforme.
-- ============================================================

ALTER TABLE reparacion_fotos ADD COLUMN momento VARCHAR(20) NOT NULL DEFAULT 'INGRESO';

ALTER TABLE reparaciones ADD COLUMN fecha_conformidad_entrega TIMESTAMP;
