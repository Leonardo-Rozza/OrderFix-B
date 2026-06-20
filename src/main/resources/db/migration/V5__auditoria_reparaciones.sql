-- ============================================================
-- V5 - Auditoría en reparaciones (para métricas y límites por plan)
-- ============================================================

ALTER TABLE reparaciones ADD COLUMN created_at TIMESTAMP;
ALTER TABLE reparaciones ADD COLUMN updated_at TIMESTAMP;

CREATE INDEX idx_reparaciones_taller_created ON reparaciones (taller_id, created_at);
