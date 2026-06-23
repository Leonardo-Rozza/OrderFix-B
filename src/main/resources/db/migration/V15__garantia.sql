-- ============================================================
-- V15 - Garantía del trabajo (§7)
-- Días/inicio/fin/condiciones de garantía (se fijan al entregar) y el
-- reclamo en garantía vinculado a la reparación original.
-- ============================================================

ALTER TABLE reparaciones ADD COLUMN garantia_dias        INTEGER;
ALTER TABLE reparaciones ADD COLUMN garantia_inicio      DATE;
ALTER TABLE reparaciones ADD COLUMN garantia_fin         DATE;
ALTER TABLE reparaciones ADD COLUMN garantia_condiciones VARCHAR(1000);
ALTER TABLE reparaciones ADD COLUMN es_garantia          BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reparaciones ADD COLUMN reparacion_origen_id BIGINT REFERENCES reparaciones (id);

CREATE INDEX idx_reparaciones_origen ON reparaciones (reparacion_origen_id);
