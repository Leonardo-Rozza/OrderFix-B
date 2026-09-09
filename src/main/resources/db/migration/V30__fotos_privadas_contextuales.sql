-- V30: private photo lifecycle and contextual links to the canonical legal ledger.
-- No historical evidence or legacy remote asset is rewritten.
ALTER TABLE legal_idempotencia_resultados DROP CONSTRAINT ck_legal_idempotencia_operacion;
ALTER TABLE legal_idempotencia_resultados ADD CONSTRAINT ck_legal_idempotencia_operacion
 CHECK (operacion IN ('REGISTRO','ACEPTACION_LEGAL','ATESTACION_FOTOS'));
ALTER TABLE legal_idempotencia_sin_actos DROP CONSTRAINT ck_legal_idem_sin_actos_operacion;
ALTER TABLE legal_idempotencia_sin_actos ADD CONSTRAINT ck_legal_idem_sin_actos_operacion CHECK (
 (operacion='ACEPTACION_LEGAL' AND route_template='/api/aceptaciones-legales') OR
 (operacion='ATESTACION_FOTOS' AND route_template='/api/reparaciones/{reparacionId}/cargas-foto'));

CREATE TABLE reparacion_fotos_privadas (
 id UUID PRIMARY KEY,
 reparacion_id BIGINT REFERENCES reparaciones(id) ON DELETE SET NULL,
 reparacion_original_id BIGINT NOT NULL,
 taller_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 rol_wire VARCHAR(10) NOT NULL CHECK (rol_wire IN ('ADMIN','USER')),
 nombre VARCHAR(255) NOT NULL CHECK (length(btrim(nombre))>0),
 mime_type VARCHAR(20) NOT NULL CHECK (mime_type IN ('image/jpeg','image/png')),
 bytes BIGINT NOT NULL CHECK (bytes BETWEEN 12 AND 8000000),
 sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
 momento VARCHAR(20) NOT NULL CHECK (momento IN ('INGRESO','POST_REPARACION')),
 estado VARCHAR(30) NOT NULL CHECK (estado IN ('AUTORIZADA','SUBIDA','ASOCIADA','EXPIRADA','FALLIDA','LIMPIEZA_PENDIENTE','ELIMINADA')),
 confirmado_en TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
 expira_en TIMESTAMP WITH TIME ZONE NOT NULL,
 retener_hasta TIMESTAMP WITH TIME ZONE NOT NULL,
 asociada_en TIMESTAMP WITH TIME ZONE,
 object_key VARCHAR(200) NOT NULL UNIQUE CHECK (object_key ~ '^ordenfix-private/[0-9a-f-]{36}$'),
 asset_id VARCHAR(200),
 asset_version VARCHAR(100),
 lease_id UUID,
 lease_hasta TIMESTAMP WITH TIME ZONE,
 scope_hmac VARCHAR(64) NOT NULL CHECK (scope_hmac ~ '^[0-9a-f]{64}$'),
 key_hmac VARCHAR(64) NOT NULL CHECK (key_hmac ~ '^[0-9a-f]{64}$'),
 fingerprint_hmac VARCHAR(64) NOT NULL CHECK (fingerprint_hmac ~ '^[0-9a-f]{64}$'),
 hmac_key_version INTEGER NOT NULL CHECK (hmac_key_version>0),
 CONSTRAINT uk_foto_privada_actor UNIQUE (id,user_id,taller_id),
 CONSTRAINT uk_foto_privada_key UNIQUE (scope_hmac,key_hmac),
 CONSTRAINT fk_foto_privada_actor FOREIGN KEY (user_id,taller_id) REFERENCES users(id,taller_id) ON DELETE RESTRICT,
 CONSTRAINT ck_foto_privada_reparacion CHECK (reparacion_id IS NULL OR reparacion_id=reparacion_original_id),
 CONSTRAINT ck_foto_privada_fechas CHECK (expira_en>confirmado_en AND expira_en<=confirmado_en+INTERVAL '15 minutes' AND retener_hasta>expira_en),
 CONSTRAINT ck_foto_privada_lease CHECK ((lease_id IS NULL)=(lease_hasta IS NULL)),
 CONSTRAINT ck_foto_privada_asset CHECK ((asset_id IS NULL)=(asset_version IS NULL)),
 CONSTRAINT ck_foto_privada_asociada CHECK (estado<>'ASOCIADA' OR (asset_id IS NOT NULL AND asociada_en IS NOT NULL)),
 CONSTRAINT ck_foto_privada_eliminada CHECK (estado<>'ELIMINADA' OR (asset_id IS NULL AND lease_id IS NULL))
);
CREATE INDEX idx_foto_privada_reparacion ON reparacion_fotos_privadas(taller_id,reparacion_id,estado);
CREATE INDEX idx_foto_privada_cleanup ON reparacion_fotos_privadas(estado,expira_en,retener_hasta);

CREATE TABLE reparacion_foto_atestaciones (
 foto_id UUID NOT NULL,
 aceptacion_id UUID NOT NULL,
 user_id BIGINT NOT NULL,
 taller_id BIGINT NOT NULL,
 alcance VARCHAR(10) NOT NULL CHECK (alcance='FOTOS'),
 PRIMARY KEY (foto_id,aceptacion_id),
 FOREIGN KEY (foto_id,user_id,taller_id) REFERENCES reparacion_fotos_privadas(id,user_id,taller_id) ON DELETE RESTRICT,
 FOREIGN KEY (aceptacion_id,user_id,taller_id) REFERENCES legal_aceptaciones(id,user_id,taller_id) ON DELETE RESTRICT
);

CREATE FUNCTION foto_privada_insert_guard_v30() RETURNS TRIGGER LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
 PERFORM legal_exigir_read_committed();
 PERFORM legal_exigir_lock_editorial_v28();
 IF NEW.object_key <> 'ordenfix-private/' || NEW.id::text OR NEW.reparacion_id IS NULL OR NEW.estado<>'AUTORIZADA' OR NEW.asset_id IS NOT NULL OR NEW.lease_id IS NOT NULL OR NEW.asociada_en IS NOT NULL THEN
   RAISE EXCEPTION 'intencion privada inicial invalida' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM users u JOIN talleres t ON t.id=u.taller_id
 WHERE u.id=NEW.user_id AND u.taller_id=NEW.taller_id AND u.role=NEW.rol_wire AND u.active AND t.activo FOR SHARE OF u,t;
 IF NOT FOUND THEN RAISE EXCEPTION 'actor privado no habilitado' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM reparaciones r WHERE r.id=NEW.reparacion_id AND r.taller_id=NEW.taller_id FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'reparacion privada ajena' USING ERRCODE='23514'; END IF;
 NEW.confirmado_en:=statement_timestamp();
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_foto_privada_insert BEFORE INSERT ON reparacion_fotos_privadas FOR EACH ROW EXECUTE FUNCTION foto_privada_insert_guard_v30();

CREATE FUNCTION foto_atestacion_insert_guard_v30() RETURNS TRIGGER LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM reparacion_fotos_privadas f WHERE f.id=NEW.foto_id AND f.user_id=NEW.user_id AND f.taller_id=NEW.taller_id AND f.estado='AUTORIZADA' AND legal_fila_es_transaccion_actual(f.xmin)) THEN
   RAISE EXCEPTION 'confirmacion fuera de su transaccion' USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM legal_aceptaciones a WHERE a.id=NEW.aceptacion_id AND a.user_id=NEW.user_id AND a.taller_id=NEW.taller_id
                AND a.contexto='ATESTACION_FOTOS' AND a.tipo_acto='DECLARACION') THEN
   RAISE EXCEPTION 'la foto requiere declaracion canonica del actor' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_foto_atestacion_insert BEFORE INSERT ON reparacion_foto_atestaciones FOR EACH ROW EXECUTE FUNCTION foto_atestacion_insert_guard_v30();
CREATE TRIGGER trg_foto_atestacion_inmutable BEFORE UPDATE OR DELETE ON reparacion_foto_atestaciones FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();

CREATE FUNCTION foto_privada_completa_v30() RETURNS TRIGGER LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM reparacion_foto_atestaciones a WHERE a.foto_id=NEW.id) THEN
   RAISE EXCEPTION 'intencion sin confirmacion contextual' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ct_foto_privada_completa AFTER INSERT ON reparacion_fotos_privadas DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION foto_privada_completa_v30();

CREATE FUNCTION foto_privada_conservar_objetos_v30() RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
 IF EXISTS (SELECT 1 FROM public.reparacion_fotos_privadas f WHERE f.reparacion_id=OLD.id AND f.estado<>'ELIMINADA') THEN
   RAISE EXCEPTION 'eliminar fotos privadas antes de la reparacion' USING ERRCODE='23514', CONSTRAINT='foto_privada_borrado_pendiente';
 END IF;
 RETURN OLD;
END;
$$;
CREATE TRIGGER trg_reparacion_fotos_privadas_delete BEFORE DELETE ON reparaciones FOR EACH ROW EXECUTE FUNCTION foto_privada_conservar_objetos_v30();

REVOKE ALL ON reparacion_fotos_privadas,reparacion_foto_atestaciones FROM PUBLIC;
REVOKE ALL ON FUNCTION foto_privada_insert_guard_v30(),foto_atestacion_insert_guard_v30(),foto_privada_completa_v30(),foto_privada_conservar_objetos_v30() FROM PUBLIC;

CREATE FUNCTION foto_privada_update_guard_v30() RETURNS TRIGGER LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
 IF (NEW.id,NEW.reparacion_original_id,NEW.taller_id,NEW.user_id,NEW.rol_wire,NEW.nombre,NEW.mime_type,NEW.bytes,NEW.sha256,
     NEW.momento,NEW.confirmado_en,NEW.expira_en,NEW.retener_hasta,NEW.object_key,NEW.scope_hmac,NEW.key_hmac,NEW.fingerprint_hmac,NEW.hmac_key_version)
    IS DISTINCT FROM
    (OLD.id,OLD.reparacion_original_id,OLD.taller_id,OLD.user_id,OLD.rol_wire,OLD.nombre,OLD.mime_type,OLD.bytes,OLD.sha256,
     OLD.momento,OLD.confirmado_en,OLD.expira_en,OLD.retener_hasta,OLD.object_key,OLD.scope_hmac,OLD.key_hmac,OLD.fingerprint_hmac,OLD.hmac_key_version)
    OR (NEW.reparacion_id IS DISTINCT FROM OLD.reparacion_id AND NOT (NEW.reparacion_id IS NULL AND OLD.estado='ELIMINADA'))
    OR (OLD.asociada_en IS NOT NULL AND NEW.asociada_en IS DISTINCT FROM OLD.asociada_en)
    OR (OLD.asset_id IS NOT NULL AND NEW.estado<>'ELIMINADA' AND (NEW.asset_id,NEW.asset_version) IS DISTINCT FROM (OLD.asset_id,OLD.asset_version)) THEN
   RAISE EXCEPTION 'identidad privada inmutable' USING ERRCODE='23514';
 END IF;
 IF NEW.estado<>OLD.estado AND NOT (
    (OLD.estado='AUTORIZADA' AND NEW.estado IN ('SUBIDA','EXPIRADA','FALLIDA','LIMPIEZA_PENDIENTE')) OR
    (OLD.estado='SUBIDA' AND NEW.estado IN ('ASOCIADA','EXPIRADA','FALLIDA','LIMPIEZA_PENDIENTE')) OR
    (OLD.estado='ASOCIADA' AND NEW.estado='LIMPIEZA_PENDIENTE') OR
    (OLD.estado IN ('EXPIRADA','FALLIDA') AND NEW.estado='LIMPIEZA_PENDIENTE') OR
    (OLD.estado='LIMPIEZA_PENDIENTE' AND NEW.estado='ELIMINADA')) THEN
   RAISE EXCEPTION 'transicion privada invalida' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_foto_privada_update BEFORE UPDATE ON reparacion_fotos_privadas FOR EACH ROW EXECUTE FUNCTION foto_privada_update_guard_v30();
REVOKE ALL ON FUNCTION foto_privada_update_guard_v30() FROM PUBLIC;
