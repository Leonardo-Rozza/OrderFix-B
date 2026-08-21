-- Revocación global de access tokens después de cambiar credenciales.
-- Los tokens emitidos llevan esta versión y el filtro la contrasta contra la base.
ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
