-- Short-lived export grants. Raw passwords, access JWTs and opaque tokens are never stored.
-- This isolated table does not change the frozen legal/photo migrations or their catalogs.
CREATE TABLE public.cuenta_reautenticaciones (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    taller_id BIGINT NOT NULL,
    token_version BIGINT NOT NULL,
    session_hash VARCHAR(64) NOT NULL,
    proposito VARCHAR(30) NOT NULL,
    creada_en TIMESTAMP WITH TIME ZONE NOT NULL,
    expira_en TIMESTAMP WITH TIME ZONE NOT NULL,
    usada_en TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_cuenta_reauth_actor FOREIGN KEY (user_id, taller_id)
        REFERENCES public.users (id, taller_id) ON DELETE CASCADE,
    CONSTRAINT ck_cuenta_reauth_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cuenta_reauth_session_hash CHECK (session_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cuenta_reauth_token_version CHECK (token_version >= 0),
    CONSTRAINT ck_cuenta_reauth_proposito CHECK (proposito IN ('EXPORTAR', 'DESCARGAR_EXPORTACION')),
    CONSTRAINT ck_cuenta_reauth_expiracion CHECK (
        expira_en > creada_en AND expira_en <= creada_en + INTERVAL '5 minutes'),
    CONSTRAINT ck_cuenta_reauth_consumo CHECK (
        usada_en IS NULL OR (usada_en >= creada_en AND usada_en < expira_en)),
    CONSTRAINT uq_cuenta_reauth_sesion_proposito UNIQUE (user_id, session_hash, proposito)
);

CREATE INDEX idx_cuenta_reauth_expiracion ON public.cuenta_reautenticaciones (expira_en);
