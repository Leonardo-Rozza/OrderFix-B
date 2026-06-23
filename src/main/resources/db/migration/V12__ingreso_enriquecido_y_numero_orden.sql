-- ============================================================
-- V12 - Ingreso enriquecido + número de orden mostrable
-- Flags de riesgo del ingreso (§1/§2), bloqueo de cuenta (iCloud/FRP)
-- y número de orden correlativo por taller con reinicio anual.
-- ============================================================

-- Flags de riesgo y bloqueo de cuenta en la reparación
ALTER TABLE reparaciones ADD COLUMN numero_orden                VARCHAR(20);
ALTER TABLE reparaciones ADD COLUMN mojado                      BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE reparaciones ADD COLUMN trabajo_en_placa            BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE reparaciones ADD COLUMN no_testeable_al_ingreso     BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE reparaciones ADD COLUMN tiene_bloqueo_pantalla      BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE reparaciones ADD COLUMN tiene_cuenta_vinculada      VARCHAR(10) NOT NULL DEFAULT 'NINGUNA';
ALTER TABLE reparaciones ADD COLUMN cliente_conoce_credenciales BOOLEAN     NOT NULL DEFAULT FALSE;

CREATE INDEX idx_reparaciones_numero_orden ON reparaciones (numero_orden);

-- Contador de número de orden por taller (correlativo con reinicio anual)
ALTER TABLE talleres ADD COLUMN secuencia_orden      INTEGER NOT NULL DEFAULT 0;
ALTER TABLE talleres ADD COLUMN anio_secuencia_orden INTEGER;
