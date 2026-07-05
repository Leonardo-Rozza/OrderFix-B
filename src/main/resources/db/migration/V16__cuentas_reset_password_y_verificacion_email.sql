-- Reset de contraseña y verificación de email.

-- Verificación de email: las cuentas existentes quedan como verificadas
-- (ya venían operando); las nuevas arrancan sin verificar.
ALTER TABLE users ADD COLUMN email_verificado BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE users SET email_verificado = TRUE;

-- Tokens de un solo uso enviados por email (se guarda solo el hash SHA-256).
CREATE TABLE auth_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tipo        VARCHAR(30) NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expira_en   TIMESTAMP NOT NULL,
    usado_en    TIMESTAMP,
    created_at  TIMESTAMP
);

CREATE INDEX idx_auth_tokens_user ON auth_tokens (user_id);
