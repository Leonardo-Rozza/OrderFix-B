-- ============================================================
-- V17 - Integridad de bajas con historial operativo/contable
-- Evita que borrar un padre elimine en cascada reparaciones o cobros.
-- ============================================================

-- Cliente con equipos: la baja debe ser rechazada por el dominio y por la DB.
ALTER TABLE equipos DROP CONSTRAINT IF EXISTS equipos_cliente_id_fkey;
ALTER TABLE equipos
    ADD CONSTRAINT fk_equipos_cliente_restrict
    FOREIGN KEY (cliente_id) REFERENCES clientes (id) ON DELETE RESTRICT;

-- Equipo con reparaciones: la baja debe ser rechazada por el dominio y por la DB.
ALTER TABLE reparaciones DROP CONSTRAINT IF EXISTS reparaciones_equipo_id_fkey;
ALTER TABLE reparaciones
    ADD CONSTRAINT fk_reparaciones_equipo_restrict
    FOREIGN KEY (equipo_id) REFERENCES equipos (id) ON DELETE RESTRICT;

-- Los cobros son historial contable: nunca se borran por cascade al borrar la reparación.
ALTER TABLE cobros DROP CONSTRAINT IF EXISTS cobros_reparacion_id_fkey;
ALTER TABLE cobros
    ADD CONSTRAINT fk_cobros_reparacion_restrict
    FOREIGN KEY (reparacion_id) REFERENCES reparaciones (id) ON DELETE RESTRICT;
