-- ============================================================
-- V20 - Credenciales de acceso a dispositivos cifradas
--
-- Esta migración NO interpreta ni borra los valores existentes. Renombra las
-- columnas en el lugar y marca cada fila como legacy (versión 0). En el primer
-- arranque, DeviceCredentialLegacyMigration cifra los valores con AES-GCM y
-- recién entonces cambia la versión a 1 dentro de una única transacción.
-- ============================================================

ALTER TABLE reparaciones
    RENAME COLUMN patron_desbloqueo TO patron_desbloqueo_cifrado;

ALTER TABLE reparaciones
    RENAME COLUMN pin_desbloqueo TO pin_desbloqueo_cifrado;

ALTER TABLE reparaciones
    ALTER COLUMN patron_desbloqueo_cifrado TYPE TEXT;

ALTER TABLE reparaciones
    ALTER COLUMN pin_desbloqueo_cifrado TYPE TEXT;

ALTER TABLE reparaciones
    ADD COLUMN credenciales_cifrado_version SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE reparaciones
    ADD CONSTRAINT ck_reparaciones_credenciales_cifrado_version
        CHECK (credenciales_cifrado_version IN (0, 1));
