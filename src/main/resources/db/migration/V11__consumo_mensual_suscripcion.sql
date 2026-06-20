-- ============================================================
-- V11 - Contador de consumo mensual en la suscripción
-- (no baja al borrar reparaciones; se reinicia al cambiar de mes)
-- ============================================================
ALTER TABLE suscripciones ADD COLUMN consumo_mes      VARCHAR(7);
ALTER TABLE suscripciones ADD COLUMN reparaciones_mes INT NOT NULL DEFAULT 0;
