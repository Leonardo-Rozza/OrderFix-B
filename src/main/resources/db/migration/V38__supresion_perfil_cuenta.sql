-- V38: explicit, bounded removal of local profile fields and credentials after closure grace.
-- This migration grants no runtime role and performs no erasure. It never changes closure state
-- to ELIMINADO, removes identity anchors, or defines retention for legal/provider/photo evidence.
CREATE TABLE public.cuenta_perfil_bajas (
    operacion_id UUID PRIMARY KEY,
    taller_id BIGINT NOT NULL UNIQUE CHECK (taller_id>0),
    cierre_referencia UUID NOT NULL,
    generacion BIGINT NOT NULL CHECK (generacion>0),
    usuarios INTEGER NOT NULL CHECK (usuarios BETWEEN 1 AND 1000),
    qr_eliminado BOOLEAN NOT NULL,
    suprimido_en TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(suprimido_en)),
    CONSTRAINT fk_perfil_baja_cierre FOREIGN KEY(cierre_referencia,taller_id)
        REFERENCES public.cuenta_cierres(referencia,taller_id) ON DELETE RESTRICT
);

-- Only SECURITY DEFINER entry code writes this short-lived capability. It contains no old profile
-- values. Epochs authorize exactly one update of each locked user within this transaction.
CREATE TABLE public.cuenta_perfil_baja_contextos (
    transaccion XID8 PRIMARY KEY,
    backend_pid INTEGER NOT NULL CHECK (backend_pid>0),
    operacion_id UUID NOT NULL UNIQUE,
    taller_id BIGINT NOT NULL CHECK (taller_id>0),
    cierre_referencia UUID NOT NULL,
    generacion BIGINT NOT NULL CHECK (generacion>0),
    user_ids BIGINT[] NOT NULL,
    token_versions BIGINT[] NOT NULL,
    qr_presente BOOLEAN NOT NULL,
    habilitado_en TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(habilitado_en)),
    CONSTRAINT ck_perfil_baja_contexto_usuarios CHECK (
        cardinality(user_ids) BETWEEN 1 AND 1000
        AND cardinality(user_ids)=cardinality(token_versions)
        AND array_position(user_ids,NULL) IS NULL AND array_position(token_versions,NULL) IS NULL
        AND 0<ALL(user_ids) AND 0<=ALL(token_versions) AND 9223372036854775807>ALL(token_versions)),
    CONSTRAINT fk_perfil_baja_contexto_cierre FOREIGN KEY(cierre_referencia,taller_id)
        REFERENCES public.cuenta_cierres(referencia,taller_id) ON DELETE RESTRICT
);
REVOKE ALL ON public.cuenta_perfil_bajas,public.cuenta_perfil_baja_contextos FROM PUBLIC;

CREATE FUNCTION public.cuenta_perfil_baja_guard_v38() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_ctx public.cuenta_perfil_baja_contextos%ROWTYPE; v_taller BIGINT; v_pos INTEGER;
BEGIN
    IF TG_TABLE_SCHEMA<>'public'
        OR NOT ((TG_TABLE_NAME IN ('users','talleres') AND TG_OP='UPDATE')
            OR (TG_TABLE_NAME='taller_qr_cobro' AND TG_OP='DELETE')) THEN
        RAISE EXCEPTION 'frontera de supresion de perfil invalida' USING ERRCODE='P0038';
    END IF;
    IF TG_TABLE_NAME='talleres' THEN v_taller:=OLD.id; ELSE v_taller:=OLD.taller_id; END IF;
    SELECT * INTO v_ctx FROM public.cuenta_perfil_baja_contextos WHERE transaccion=pg_current_xact_id();
    IF NOT FOUND THEN
        -- Preserve V33's ordinary QR deletion on an open workshop, including its existing CASCADE.
        -- Profile-shaped UPDATEs are reserved to the private capability even on an open workshop.
        IF TG_TABLE_NAME='taller_qr_cobro' AND public.cuenta_cierre_estado_v33(v_taller)='ABIERTO' THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'supresion de perfil sin contexto privado' USING ERRCODE='P0038';
    END IF;
    IF v_ctx.backend_pid<>pg_backend_pid() OR v_ctx.taller_id<>v_taller
        OR NOT public.cuenta_cierre_exclusivo_v33(v_taller)
        OR current_setting('transaction_isolation')<>'read committed'
        OR current_setting('transaction_read_only')<>'off' THEN
        RAISE EXCEPTION 'fila fuera de la supresion autorizada' USING ERRCODE='P0038';
    END IF;
    IF TG_TABLE_NAME='users' THEN
        v_pos:=array_position(v_ctx.user_ids,OLD.id);
        IF v_pos IS NULL OR OLD.token_version<>v_ctx.token_versions[v_pos]
            OR NEW.token_version::NUMERIC<>OLD.token_version::NUMERIC+1
            OR NEW.username<>'Usuario dado de baja' OR NEW.password<>'!'
            OR NEW.email !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}@cuenta-eliminada[.]invalid$'
            OR NEW.email IS NOT DISTINCT FROM OLD.email OR NEW.active OR NEW.email_verificado
            OR (to_jsonb(NEW)-ARRAY['username','email','password','active','email_verificado','token_version'])
                IS DISTINCT FROM
               (to_jsonb(OLD)-ARRAY['username','email','password','active','email_verificado','token_version']) THEN
            RAISE EXCEPTION 'usuario fuera de la supresion autorizada' USING ERRCODE='P0038';
        END IF;
        RETURN NEW;
    ELSIF TG_TABLE_NAME='talleres' THEN
        IF OLD.cierre_estado<>'RESTRINGIDO' OR NEW.cierre_estado<>'RESTRINGIDO'
            OR OLD.cierre_referencia IS DISTINCT FROM v_ctx.cierre_referencia
            OR OLD.cierre_version<>v_ctx.generacion
            OR NEW.nombre<>'Taller dado de baja' OR NEW.email_contacto IS NOT NULL OR NEW.telefono IS NOT NULL
            OR NEW.alias_cobro IS NOT NULL OR NEW.titular_cobro IS NOT NULL OR NEW.entidad_cobro IS NOT NULL
            OR NEW.mostrar_en_resumen
            OR (to_jsonb(NEW)-ARRAY['nombre','email_contacto','telefono','alias_cobro','titular_cobro','entidad_cobro','mostrar_en_resumen'])
                IS DISTINCT FROM
               (to_jsonb(OLD)-ARRAY['nombre','email_contacto','telefono','alias_cobro','titular_cobro','entidad_cobro','mostrar_en_resumen']) THEN
            RAISE EXCEPTION 'taller fuera de la supresion autorizada' USING ERRCODE='P0038';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT v_ctx.qr_presente THEN
        RAISE EXCEPTION 'QR fuera de la supresion autorizada' USING ERRCODE='P0038';
    END IF;
    RETURN OLD;
END;
$$;

CREATE FUNCTION public.cuenta_perfil_baja_recibo_guard_v38() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_ctx public.cuenta_perfil_baja_contextos%ROWTYPE;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'cuenta_perfil_bajas' OR TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'constancia de supresion de perfil inmutable' USING ERRCODE='P0038';
    END IF;
    SELECT * INTO v_ctx FROM public.cuenta_perfil_baja_contextos WHERE transaccion=pg_current_xact_id();
    IF NOT FOUND OR v_ctx.backend_pid<>pg_backend_pid()
        OR (NEW.operacion_id,NEW.taller_id,NEW.cierre_referencia,NEW.generacion)
            IS DISTINCT FROM (v_ctx.operacion_id,v_ctx.taller_id,v_ctx.cierre_referencia,v_ctx.generacion)
        OR NEW.usuarios<>cardinality(v_ctx.user_ids) OR NEW.qr_eliminado IS DISTINCT FROM v_ctx.qr_presente
        OR NEW.suprimido_en<v_ctx.habilitado_en OR NEW.suprimido_en>clock_timestamp() THEN
        RAISE EXCEPTION 'constancia fuera de la supresion autorizada' USING ERRCODE='P0038';
    END IF;
    IF public.cuenta_borrado_exigir_scope_v37(v_ctx.taller_id,v_ctx.cierre_referencia)<>v_ctx.generacion
        OR (SELECT count(*) FROM public.users WHERE taller_id=v_ctx.taller_id)<>NEW.usuarios
        OR EXISTS (
            SELECT 1 FROM unnest(v_ctx.user_ids,v_ctx.token_versions) AS e(id,token_version)
              LEFT JOIN public.users u ON u.id=e.id AND u.taller_id=v_ctx.taller_id
             WHERE u.id IS NULL OR u.username<>'Usuario dado de baja' OR u.password<>'!'
               OR u.email !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}@cuenta-eliminada[.]invalid$'
               OR u.active OR u.email_verificado OR u.token_version::NUMERIC<>e.token_version::NUMERIC+1)
        OR NOT EXISTS (
            SELECT 1 FROM public.talleres t WHERE t.id=v_ctx.taller_id AND t.nombre='Taller dado de baja'
              AND t.email_contacto IS NULL AND t.telefono IS NULL AND t.alias_cobro IS NULL
              AND t.titular_cobro IS NULL AND t.entidad_cobro IS NULL AND NOT t.mostrar_en_resumen)
        OR EXISTS (SELECT 1 FROM public.taller_qr_cobro WHERE taller_id=v_ctx.taller_id) THEN
        RAISE EXCEPTION 'supresion de perfil incompleta' USING ERRCODE='P0038';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.cuenta_perfil_baja_contexto_vacio_v38() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'cuenta_perfil_baja_contextos'
        OR EXISTS (SELECT 1 FROM public.cuenta_perfil_baja_contextos WHERE transaccion=pg_current_xact_id())
        OR NOT EXISTS (SELECT 1 FROM public.cuenta_perfil_bajas r
            WHERE (r.operacion_id,r.taller_id,r.cierre_referencia,r.generacion,r.usuarios,r.qr_eliminado)
                IS NOT DISTINCT FROM
                  (NEW.operacion_id,NEW.taller_id,NEW.cierre_referencia,NEW.generacion,cardinality(NEW.user_ids),NEW.qr_presente)) THEN
        RAISE EXCEPTION 'contexto de supresion de perfil no finalizado' USING ERRCODE='P0038';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.cuenta_cierre_suprimir_perfil_v38(p_operacion UUID,p_taller BIGINT,p_cierre UUID)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE
    v_generacion BIGINT; v_recibo public.cuenta_perfil_bajas%ROWTYPE;
    v_ids BIGINT[]; v_versions BIGINT[]; v_usuarios INTEGER; v_qr BOOLEAN; v_changed INTEGER;
BEGIN
    IF p_operacion IS NULL THEN
        RAISE EXCEPTION 'identidad de supresion de perfil invalida' USING ERRCODE='P0038';
    END IF;
    -- The caller takes the exclusive workshop gate before entering. This verifies RC and grace,
    -- then obtains the first row locks. Existing references/generation remain unchanged.
    v_generacion:=public.cuenta_borrado_exigir_scope_v37(p_taller,p_cierre);
    IF EXISTS (SELECT 1 FROM public.cuenta_perfil_baja_contextos WHERE transaccion=pg_current_xact_id())
        OR EXISTS (SELECT 1 FROM public.cuenta_borrado_contextos WHERE transaccion=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'supresion de perfil anidada' USING ERRCODE='P0038';
    END IF;
    SELECT * INTO v_recibo FROM public.cuenta_perfil_bajas WHERE operacion_id=p_operacion;
    IF FOUND THEN
        IF (v_recibo.taller_id,v_recibo.cierre_referencia,v_recibo.generacion)
            IS DISTINCT FROM (p_taller,p_cierre,v_generacion) THEN
            RAISE EXCEPTION 'colision de identidad de supresion' USING ERRCODE='P0038';
        END IF;
        RETURN jsonb_build_object('status','REUSED','receiptId',v_recibo.operacion_id,
            'users',v_recibo.usuarios,'qrRemoved',v_recibo.qr_eliminado,'suppressedAt',v_recibo.suprimido_en);
    END IF;
    IF EXISTS (SELECT 1 FROM public.cuenta_perfil_bajas WHERE taller_id=p_taller) THEN
        RAISE EXCEPTION 'taller ya tiene una constancia de supresion' USING ERRCODE='P0038';
    END IF;
    PERFORM public.cuenta_borrado_preflight_v37(p_taller);
    IF EXISTS (SELECT 1 FROM public.presupuesto_items WHERE taller_id=p_taller)
        OR EXISTS (SELECT 1 FROM public.presupuestos WHERE taller_id=p_taller)
        OR EXISTS (SELECT 1 FROM public.cobros WHERE taller_id=p_taller)
        OR EXISTS (SELECT 1 FROM public.repuestos WHERE taller_id=p_taller)
        OR EXISTS (SELECT 1 FROM public.reparaciones WHERE taller_id=p_taller)
        OR EXISTS (SELECT 1 FROM public.equipos WHERE taller_id=p_taller)
        OR EXISTS (SELECT 1 FROM public.clientes WHERE taller_id=p_taller)
        OR EXISTS (SELECT 1 FROM public.articulos WHERE taller_id=p_taller) THEN
        RAISE EXCEPTION 'supresion de perfil requiere borrado operativo completo' USING ERRCODE='P0038';
    END IF;
    IF EXISTS (SELECT 1 FROM public.auth_tokens WHERE taller_id=p_taller)
        OR EXISTS (SELECT 1 FROM public.cuenta_reautenticaciones WHERE taller_id=p_taller)
        OR EXISTS (SELECT 1 FROM public.cuenta_exportaciones WHERE taller_id=p_taller
            AND (estado IN ('QUEUED','RUNNING','READY') OR snapshot_cipher IS NOT NULL OR archive_cipher IS NOT NULL)) THEN
        RAISE EXCEPTION 'supresion de perfil requiere mantenimiento de accesos y exportaciones' USING ERRCODE='P0038';
    END IF;
    -- Do not lock or update effect rows: workers can already hold one before asking for their gate.
    -- Uncertain/unsent messages do not prove delivery and do not prevent local profile suppression.
    IF EXISTS (SELECT 1 FROM public.cuenta_cierre_efectos WHERE taller_id=p_taller
        AND tipo IN ('AVISO_CIERRE','AVISO_RESTAURACION') AND estado='EN_CURSO' AND lease_until>clock_timestamp()) THEN
        RAISE EXCEPTION 'supresion de perfil espera aviso en curso' USING ERRCODE='P0038';
    END IF;
    SELECT coalesce(array_agg(u.id ORDER BY u.id),ARRAY[]::BIGINT[]),
           coalesce(array_agg(u.token_version ORDER BY u.id),ARRAY[]::BIGINT[])
      INTO v_ids,v_versions
      FROM (SELECT id,token_version FROM public.users WHERE taller_id=p_taller ORDER BY id LIMIT 1001 FOR UPDATE) u;
    v_usuarios:=cardinality(v_ids);
    IF v_usuarios NOT BETWEEN 1 AND 1000 OR NOT (0<=ALL(v_versions))
        OR NOT (9223372036854775807>ALL(v_versions)) THEN
        RAISE EXCEPTION 'capacidad o version de usuarios fuera de rango' USING ERRCODE='P0038';
    END IF;
    -- A second snapshot catches a foreign-tenant reference committed while the user locks waited.
    PERFORM public.cuenta_borrado_preflight_v37(p_taller);
    SELECT EXISTS (SELECT 1 FROM public.taller_qr_cobro WHERE taller_id=p_taller) INTO v_qr;
    INSERT INTO public.cuenta_perfil_baja_contextos
        (transaccion,backend_pid,operacion_id,taller_id,cierre_referencia,generacion,user_ids,token_versions,qr_presente)
        VALUES (pg_current_xact_id(),pg_backend_pid(),p_operacion,p_taller,p_cierre,v_generacion,v_ids,v_versions,v_qr);
    UPDATE public.users SET username='Usuario dado de baja',email=gen_random_uuid()::TEXT||'@cuenta-eliminada.invalid',
        password='!',active=FALSE,email_verificado=FALSE,token_version=token_version+1
        WHERE taller_id=p_taller AND id=ANY(v_ids);
    GET DIAGNOSTICS v_changed=ROW_COUNT;
    IF v_changed<>v_usuarios THEN
        RAISE EXCEPTION 'conteo de usuarios distinto de la supresion autorizada' USING ERRCODE='P0038';
    END IF;
    UPDATE public.talleres SET nombre='Taller dado de baja',email_contacto=NULL,telefono=NULL,
        alias_cobro=NULL,titular_cobro=NULL,entidad_cobro=NULL,mostrar_en_resumen=FALSE WHERE id=p_taller;
    GET DIAGNOSTICS v_changed=ROW_COUNT;
    IF v_changed<>1 THEN
        RAISE EXCEPTION 'ancla de supresion de perfil ausente' USING ERRCODE='P0038';
    END IF;
    DELETE FROM public.taller_qr_cobro WHERE taller_id=p_taller;
    GET DIAGNOSTICS v_changed=ROW_COUNT;
    IF v_changed<>CASE WHEN v_qr THEN 1 ELSE 0 END THEN
        RAISE EXCEPTION 'conteo de QR distinto de la supresion autorizada' USING ERRCODE='P0038';
    END IF;
    INSERT INTO public.cuenta_perfil_bajas
        (operacion_id,taller_id,cierre_referencia,generacion,usuarios,qr_eliminado)
        VALUES (p_operacion,p_taller,p_cierre,v_generacion,v_usuarios,v_qr) RETURNING * INTO v_recibo;
    DELETE FROM public.cuenta_perfil_baja_contextos WHERE transaccion=pg_current_xact_id();
    RETURN jsonb_build_object('status','SUPPRESSED','receiptId',v_recibo.operacion_id,
        'users',v_recibo.usuarios,'qrRemoved',v_recibo.qr_eliminado,'suppressedAt',v_recibo.suprimido_en);
END;
$$;

-- Route only the exact reserved UPDATE shape to the private V38 guard. The mutually exclusive
-- WHEN clauses contain built-ins only, so restricted callers need no EXECUTE on a bypass helper.
-- All other UPDATEs and every INSERT retain V33's original function body and behavior.
DO $$
DECLARE
    v_users TEXT := $shape$
        NEW.username='Usuario dado de baja' AND NEW.password='!'
        AND NEW.email ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}@cuenta-eliminada[.]invalid$'
        AND NEW.email IS DISTINCT FROM OLD.email AND NOT NEW.active AND NOT NEW.email_verificado
        AND NEW.token_version::NUMERIC=OLD.token_version::NUMERIC+1
        AND (to_jsonb(NEW)-ARRAY['username','email','password','active','email_verificado','token_version'])
            IS NOT DISTINCT FROM
            (to_jsonb(OLD)-ARRAY['username','email','password','active','email_verificado','token_version'])
        $shape$;
    v_taller TEXT := $shape$
        OLD.cierre_estado='RESTRINGIDO' AND NEW.cierre_estado='RESTRINGIDO'
        AND NEW.nombre='Taller dado de baja' AND NEW.email_contacto IS NULL AND NEW.telefono IS NULL
        AND NEW.alias_cobro IS NULL AND NEW.titular_cobro IS NULL AND NEW.entidad_cobro IS NULL AND NOT NEW.mostrar_en_resumen
        AND (to_jsonb(NEW)-ARRAY['nombre','email_contacto','telefono','alias_cobro','titular_cobro','entidad_cobro','mostrar_en_resumen'])
            IS NOT DISTINCT FROM
            (to_jsonb(OLD)-ARRAY['nombre','email_contacto','telefono','alias_cobro','titular_cobro','entidad_cobro','mostrar_en_resumen'])
        $shape$;
BEGIN
    DROP TRIGGER aa_cuenta_guard_v33 ON public.users;
    CREATE TRIGGER aa_cuenta_guard_v33 BEFORE INSERT OR DELETE ON public.users
        FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_guard_v33();
    EXECUTE format('CREATE TRIGGER aa_cuenta_perfil_usuario_v38 BEFORE UPDATE ON public.users
        FOR EACH ROW WHEN (%s) EXECUTE FUNCTION public.cuenta_perfil_baja_guard_v38()',v_users);
    EXECUTE format('CREATE TRIGGER ab_cuenta_perfil_usuario_normal_v38 BEFORE UPDATE ON public.users
        FOR EACH ROW WHEN (NOT (%s)) EXECUTE FUNCTION public.cuenta_cierre_guard_v33()',v_users);
    DROP TRIGGER aa_cuenta_guard_v33 ON public.talleres;
    CREATE TRIGGER aa_cuenta_guard_v33 BEFORE INSERT OR DELETE ON public.talleres
        FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_guard_v33();
    EXECUTE format('CREATE TRIGGER aa_cuenta_perfil_taller_v38 BEFORE UPDATE ON public.talleres
        FOR EACH ROW WHEN (%s) EXECUTE FUNCTION public.cuenta_perfil_baja_guard_v38()',v_taller);
    EXECUTE format('CREATE TRIGGER ab_cuenta_perfil_taller_normal_v38 BEFORE UPDATE ON public.talleres
        FOR EACH ROW WHEN (NOT (%s)) EXECUTE FUNCTION public.cuenta_cierre_guard_v33()',v_taller);
END;
$$;
DROP TRIGGER aa_cuenta_guard_v33 ON public.taller_qr_cobro;
CREATE TRIGGER aa_cuenta_guard_v33 BEFORE INSERT OR UPDATE ON public.taller_qr_cobro
    FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_guard_v33();
CREATE TRIGGER aa_cuenta_perfil_qr_v38 BEFORE DELETE ON public.taller_qr_cobro
    FOR EACH ROW EXECUTE FUNCTION public.cuenta_perfil_baja_guard_v38();
CREATE TRIGGER aa_cuenta_perfil_recibo_v38 BEFORE INSERT OR UPDATE OR DELETE ON public.cuenta_perfil_bajas
    FOR EACH ROW EXECUTE FUNCTION public.cuenta_perfil_baja_recibo_guard_v38();
CREATE CONSTRAINT TRIGGER ct_cuenta_perfil_contexto_v38 AFTER INSERT OR UPDATE ON public.cuenta_perfil_baja_contextos
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.cuenta_perfil_baja_contexto_vacio_v38();
REVOKE ALL ON FUNCTION public.cuenta_perfil_baja_guard_v38(),public.cuenta_perfil_baja_recibo_guard_v38(),
    public.cuenta_perfil_baja_contexto_vacio_v38(),public.cuenta_cierre_suprimir_perfil_v38(UUID,BIGINT,UUID) FROM PUBLIC;
