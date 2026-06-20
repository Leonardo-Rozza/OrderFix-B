-- ============================================================
-- V7 - Orden de trabajo ampliada
-- Checklist de ingreso, técnico asignado, observaciones y fotos.
-- ============================================================
ALTER TABLE reparaciones ADD COLUMN patron_desbloqueo   VARCHAR(60);
ALTER TABLE reparaciones ADD COLUMN pin_desbloqueo       VARCHAR(20);
ALTER TABLE reparaciones ADD COLUMN accesorios           VARCHAR(255);
ALTER TABLE reparaciones ADD COLUMN condiciones_ingreso  VARCHAR(500);
ALTER TABLE reparaciones ADD COLUMN observaciones        VARCHAR(1000);
ALTER TABLE reparaciones ADD COLUMN tecnico_id           BIGINT REFERENCES users (id);

CREATE INDEX idx_reparaciones_tecnico ON reparaciones (tecnico_id);

-- Fotos del equipo (URLs; la subida del archivo la hace el frontend a su storage)
CREATE TABLE reparacion_fotos (
    reparacion_id BIGINT       NOT NULL REFERENCES reparaciones (id) ON DELETE CASCADE,
    url           VARCHAR(500) NOT NULL
);
CREATE INDEX idx_reparacion_fotos_reparacion ON reparacion_fotos (reparacion_id);
