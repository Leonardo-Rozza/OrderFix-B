-- ============================================================
-- V3 - Login por email: email obligatorio, username deja de ser único global
-- ============================================================

-- El email pasa a ser el identificador de login (ya era UNIQUE en V1)
ALTER TABLE users ALTER COLUMN email SET NOT NULL;

-- username queda como nombre visible: ya no es único entre talleres
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_username_key;
