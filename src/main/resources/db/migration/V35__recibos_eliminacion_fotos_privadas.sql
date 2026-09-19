-- V35: durable objectives and observations for every private-photo deletion path.
-- Existing ELIMINADA rows remain historical: this migration does not invent remote evidence.
-- AUSENCIA_OBSERVADA_SIN_IDENTIDAD is only a point-in-time key observation, never a
-- confirmed asset deletion or proof against late uploads, CDN copies, or backups.
CREATE TABLE public.reparacion_foto_eliminaciones (
    foto_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    taller_id BIGINT NOT NULL,
    object_key VARCHAR(200) NOT NULL UNIQUE,
    asset_id VARCHAR(200),
    asset_version VARCHAR(100),
    creada_en TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    identificada_en TIMESTAMPTZ,
    resultado VARCHAR(40) NOT NULL DEFAULT 'PENDIENTE',
    observada_en TIMESTAMPTZ,
    confirmada_en TIMESTAMPTZ,
    CONSTRAINT fk_foto_eliminacion_actor FOREIGN KEY(foto_id,user_id,taller_id)
        REFERENCES public.reparacion_fotos_privadas(id,user_id,taller_id) ON DELETE RESTRICT,
    CONSTRAINT ck_foto_eliminacion_key CHECK (object_key='ordenfix-private/'||foto_id::TEXT),
    CONSTRAINT ck_foto_eliminacion_identidad CHECK (
        (asset_id IS NULL AND asset_version IS NULL AND identificada_en IS NULL)
        OR (asset_id IS NOT NULL AND asset_version IS NOT NULL AND identificada_en IS NOT NULL
            AND asset_id ~ '^[A-Za-z0-9_-]{1,200}$' AND asset_version ~ '^[0-9]{1,100}$')),
    CONSTRAINT ck_foto_eliminacion_resultado CHECK (
        (resultado='PENDIENTE' AND observada_en IS NULL AND confirmada_en IS NULL)
        OR (resultado='IDENTIDAD_ELIMINADA' AND asset_id IS NOT NULL
            AND observada_en IS NOT NULL AND confirmada_en IS NOT NULL)
        OR (resultado='AUSENCIA_OBSERVADA_SIN_IDENTIDAD' AND asset_id IS NULL
            AND observada_en IS NOT NULL AND confirmada_en IS NULL)),
    CONSTRAINT ck_foto_eliminacion_fechas CHECK (
        isfinite(creada_en)
        AND (identificada_en IS NULL OR (isfinite(identificada_en) AND identificada_en>=creada_en))
        AND (observada_en IS NULL OR (isfinite(observada_en) AND observada_en>=creada_en
            AND (identificada_en IS NULL OR observada_en>=identificada_en)))
        AND (confirmada_en IS NULL OR (isfinite(confirmada_en) AND confirmada_en>=observada_en)))
);
CREATE INDEX idx_foto_eliminacion_taller ON public.reparacion_foto_eliminaciones(taller_id,resultado,foto_id);
REVOKE ALL ON public.reparacion_foto_eliminaciones FROM PUBLIC;

CREATE FUNCTION public.foto_eliminacion_guard_v35() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_photo public.reparacion_fotos_privadas%ROWTYPE;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'reparacion_foto_eliminaciones' OR TG_OP='DELETE' THEN
        RAISE EXCEPTION 'objetivo de eliminacion privado inmutable' USING ERRCODE='23514';
    END IF;
    PERFORM public.legal_exigir_read_committed();
    -- Nonwaiting shared admission is acquired before the parent row lock.
    PERFORM public.cuenta_cierre_estado_v33(NEW.taller_id);
    SELECT * INTO v_photo FROM public.reparacion_fotos_privadas WHERE id=NEW.foto_id FOR UPDATE;
    IF NOT FOUND OR (v_photo.user_id,v_photo.taller_id,v_photo.object_key)
        IS DISTINCT FROM (NEW.user_id,NEW.taller_id,NEW.object_key)
        OR v_photo.estado<>'LIMPIEZA_PENDIENTE'
        OR (v_photo.lease_hasta IS NOT NULL AND v_photo.lease_hasta>clock_timestamp()) THEN
        RAISE EXCEPTION 'objetivo de eliminacion privado no disponible' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.resultado<>'PENDIENTE' OR NEW.observada_en IS NOT NULL OR NEW.confirmada_en IS NOT NULL
            OR NEW.creada_en IS DISTINCT FROM statement_timestamp()
            OR (NEW.asset_id,NEW.asset_version) IS DISTINCT FROM (v_photo.asset_id,v_photo.asset_version)
            OR NEW.identificada_en IS DISTINCT FROM
                (CASE WHEN v_photo.asset_id IS NULL THEN NULL::TIMESTAMPTZ ELSE NEW.creada_en END) THEN
            RAISE EXCEPTION 'objetivo de eliminacion inicial invalido' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP<>'UPDATE' OR OLD.resultado<>'PENDIENTE'
        OR (NEW.foto_id,NEW.user_id,NEW.taller_id,NEW.object_key,NEW.creada_en)
           IS DISTINCT FROM (OLD.foto_id,OLD.user_id,OLD.taller_id,OLD.object_key,OLD.creada_en) THEN
        RAISE EXCEPTION 'objetivo de eliminacion privado inmutable' USING ERRCODE='23514';
    END IF;
    IF OLD.asset_id IS NULL AND NEW.asset_id IS NOT NULL THEN
        -- One identity adoption, committed before the external DELETE by the application boundary.
        IF NEW.resultado<>'PENDIENTE' OR NEW.observada_en IS NOT NULL OR NEW.confirmada_en IS NOT NULL
            OR NEW.identificada_en IS DISTINCT FROM statement_timestamp()
            OR (v_photo.asset_id IS NOT NULL AND (NEW.asset_id,NEW.asset_version)
                IS DISTINCT FROM (v_photo.asset_id,v_photo.asset_version)) THEN
            RAISE EXCEPTION 'identidad remota de eliminacion invalida' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (NEW.asset_id,NEW.asset_version,NEW.identificada_en)
            IS DISTINCT FROM (OLD.asset_id,OLD.asset_version,OLD.identificada_en)
            OR NEW.observada_en IS DISTINCT FROM statement_timestamp()
            OR NOT ((OLD.asset_id IS NOT NULL AND NEW.resultado='IDENTIDAD_ELIMINADA'
                      AND NEW.confirmada_en IS NOT DISTINCT FROM NEW.observada_en)
                OR (OLD.asset_id IS NULL AND NEW.resultado='AUSENCIA_OBSERVADA_SIN_IDENTIDAD'
                      AND NEW.confirmada_en IS NULL)) THEN
            RAISE EXCEPTION 'observacion remota de eliminacion invalida' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.foto_eliminada_recibo_guard_v35() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_receipt public.reparacion_foto_eliminaciones%ROWTYPE;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'reparacion_fotos_privadas' OR TG_OP<>'UPDATE' THEN
        RAISE EXCEPTION 'frontera de recibo privado invalida' USING ERRCODE='23514';
    END IF;
    IF NEW.estado<>'ELIMINADA' OR OLD.estado='ELIMINADA' THEN RETURN NEW; END IF;
    PERFORM public.legal_exigir_read_committed();
    PERFORM public.cuenta_cierre_estado_v33(NEW.taller_id);
    SELECT * INTO v_receipt FROM public.reparacion_foto_eliminaciones WHERE foto_id=NEW.id;
    IF NOT FOUND OR (v_receipt.user_id,v_receipt.taller_id,v_receipt.object_key)
        IS DISTINCT FROM (OLD.user_id,OLD.taller_id,OLD.object_key)
        OR v_receipt.resultado='PENDIENTE' OR v_receipt.observada_en IS NULL
        OR (OLD.asset_id IS NOT NULL AND
            (v_receipt.resultado<>'IDENTIDAD_ELIMINADA' OR (v_receipt.asset_id,v_receipt.asset_version)
                IS DISTINCT FROM (OLD.asset_id,OLD.asset_version))) THEN
        RAISE EXCEPTION 'falta observacion durable de eliminacion privada' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.foto_eliminacion_completa_v35() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_receipt public.reparacion_foto_eliminaciones%ROWTYPE; v_photo public.reparacion_fotos_privadas%ROWTYPE;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'reparacion_foto_eliminaciones' OR TG_OP NOT IN ('INSERT','UPDATE') THEN
        RAISE EXCEPTION 'frontera de observacion privada invalida' USING ERRCODE='23514';
    END IF;
    SELECT * INTO v_receipt FROM public.reparacion_foto_eliminaciones WHERE foto_id=NEW.foto_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'objetivo privado ausente' USING ERRCODE='23514'; END IF;
    SELECT * INTO v_photo FROM public.reparacion_fotos_privadas WHERE id=NEW.foto_id;
    IF NOT FOUND OR (v_photo.user_id,v_photo.taller_id,v_photo.object_key)
        IS DISTINCT FROM (v_receipt.user_id,v_receipt.taller_id,v_receipt.object_key)
        OR (v_receipt.resultado='PENDIENTE' AND v_photo.estado<>'LIMPIEZA_PENDIENTE')
        OR (v_receipt.resultado<>'PENDIENTE' AND
            (v_photo.estado<>'ELIMINADA' OR v_photo.asset_id IS NOT NULL OR v_photo.asset_version IS NOT NULL)) THEN
        RAISE EXCEPTION 'observacion y foto privada inconsistentes' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER aa_foto_eliminacion_v35 BEFORE INSERT OR UPDATE OR DELETE ON public.reparacion_foto_eliminaciones
    FOR EACH ROW EXECUTE FUNCTION public.foto_eliminacion_guard_v35();
CREATE TRIGGER ab_foto_eliminada_recibo_v35 BEFORE UPDATE ON public.reparacion_fotos_privadas
    FOR EACH ROW EXECUTE FUNCTION public.foto_eliminada_recibo_guard_v35();
CREATE CONSTRAINT TRIGGER ct_foto_eliminacion_completa_v35 AFTER INSERT OR UPDATE ON public.reparacion_foto_eliminaciones
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.foto_eliminacion_completa_v35();
REVOKE ALL ON FUNCTION public.foto_eliminacion_guard_v35(),public.foto_eliminada_recibo_guard_v35(),
    public.foto_eliminacion_completa_v35() FROM PUBLIC;
