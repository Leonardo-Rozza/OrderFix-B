-- Account export jobs and encrypted temporary payloads. No plaintext, passwords or access JWTs.
CREATE TABLE public.cuenta_exportaciones (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    taller_id BIGINT NOT NULL,
    token_version BIGINT NOT NULL CHECK (token_version >= 0),
    session_hash VARCHAR(64) NOT NULL CHECK (session_hash ~ '^[0-9a-f]{64}$'),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    estado VARCHAR(16) NOT NULL CHECK (estado IN ('QUEUED','RUNNING','READY','FAILED','EXPIRED','REVOKED')),
    creada_en TIMESTAMPTZ NOT NULL,
    actualizada_en TIMESTAMPTZ NOT NULL,
    expira_en TIMESTAMPTZ NOT NULL,
    intentos INTEGER NOT NULL DEFAULT 0 CHECK (intentos BETWEEN 0 AND 3),
    lease_id UUID,
    lease_hasta TIMESTAMPTZ,
    capturada_en TIMESTAMPTZ,
    snapshot_cipher BYTEA,
    archive_cipher BYTEA,
    foto_ids UUID[] NOT NULL DEFAULT '{}',
    fallo VARCHAR(40),
    CONSTRAINT fk_cuenta_export_actor FOREIGN KEY (user_id,taller_id)
        REFERENCES public.users(id,taller_id) ON DELETE CASCADE,
    CONSTRAINT uq_cuenta_export_request UNIQUE(user_id,taller_id,request_hash),
    CONSTRAINT ck_cuenta_export_tiempo CHECK (expira_en > creada_en AND expira_en <= creada_en + INTERVAL '24 hours'),
    CONSTRAINT ck_cuenta_export_lease CHECK ((estado='RUNNING') = (lease_id IS NOT NULL AND lease_hasta IS NOT NULL)
        AND ((lease_id IS NULL) = (lease_hasta IS NULL))),
    CONSTRAINT ck_cuenta_export_snapshot CHECK (snapshot_cipher IS NULL OR
        (capturada_en IS NOT NULL AND octet_length(snapshot_cipher) BETWEEN 32 AND 83886080)),
    CONSTRAINT ck_cuenta_export_archive CHECK ((estado='READY') = (archive_cipher IS NOT NULL)
        AND (archive_cipher IS NULL OR octet_length(archive_cipher) BETWEEN 32 AND 146800640)),
    CONSTRAINT ck_cuenta_export_terminal CHECK (estado NOT IN ('FAILED','EXPIRED','REVOKED') OR
        (snapshot_cipher IS NULL AND archive_cipher IS NULL AND cardinality(foto_ids)=0)),
    CONSTRAINT ck_cuenta_export_ready CHECK (estado<>'READY' OR (capturada_en IS NOT NULL AND snapshot_cipher IS NULL)),
    CONSTRAINT ck_cuenta_export_fotos CHECK (cardinality(foto_ids)<=256 AND array_position(foto_ids,NULL) IS NULL)
);
CREATE UNIQUE INDEX uq_cuenta_export_taller_activo ON public.cuenta_exportaciones(taller_id)
    WHERE estado IN ('QUEUED','RUNNING','READY');
CREATE UNIQUE INDEX uq_cuenta_export_worker ON public.cuenta_exportaciones((TRUE)) WHERE estado='RUNNING';
CREATE INDEX idx_cuenta_export_pendientes ON public.cuenta_exportaciones(creada_en,id) WHERE estado='QUEUED';
CREATE INDEX idx_cuenta_export_expiracion ON public.cuenta_exportaciones(expira_en);
