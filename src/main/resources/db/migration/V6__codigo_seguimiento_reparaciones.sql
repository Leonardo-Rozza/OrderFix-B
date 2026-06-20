-- ============================================================
-- V6 - Código de seguimiento público para reparaciones
-- ============================================================
ALTER TABLE reparaciones ADD COLUMN codigo_seguimiento VARCHAR(20);

-- Único (permite múltiples NULL en Postgres para las filas existentes)
CREATE UNIQUE INDEX uk_reparaciones_codigo_seguimiento
    ON reparaciones (codigo_seguimiento);
