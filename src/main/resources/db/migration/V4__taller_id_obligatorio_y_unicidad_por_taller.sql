-- ============================================================
-- V4 - taller_id obligatorio en tablas de dominio +
--      unicidad de cliente por taller (no global)
-- ============================================================

-- ------------------------------------------------------------
-- Unicidad de cliente ahora es por taller (un mismo teléfono/email
-- puede existir en talleres distintos)
-- ------------------------------------------------------------
ALTER TABLE clientes DROP CONSTRAINT IF EXISTS clientes_telefono_key;
ALTER TABLE clientes DROP CONSTRAINT IF EXISTS clientes_email_key;

ALTER TABLE clientes ADD CONSTRAINT uk_clientes_taller_telefono UNIQUE (taller_id, telefono);
ALTER TABLE clientes ADD CONSTRAINT uk_clientes_taller_email    UNIQUE (taller_id, email);

-- ------------------------------------------------------------
-- taller_id pasa a ser obligatorio en las tablas de dominio.
-- (Las tablas están vacías en una instalación nueva.)
-- ------------------------------------------------------------
ALTER TABLE clientes     ALTER COLUMN taller_id SET NOT NULL;
ALTER TABLE equipos      ALTER COLUMN taller_id SET NOT NULL;
ALTER TABLE reparaciones ALTER COLUMN taller_id SET NOT NULL;
ALTER TABLE repuestos    ALTER COLUMN taller_id SET NOT NULL;
