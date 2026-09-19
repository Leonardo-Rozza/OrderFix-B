-- V37: bounded operational erasure after the owner's seven-day reversal window.
-- Legal evidence, users, workshop anchors, subscriptions and exports are retained.
-- A batch proves only its own category/count, never complete account erasure.
CREATE TABLE public.cuenta_borrado_lotes (
    lote_id UUID PRIMARY KEY,
    taller_id BIGINT NOT NULL CHECK (taller_id>0),
    cierre_referencia UUID NOT NULL,
    generacion BIGINT NOT NULL CHECK (generacion>0),
    categoria TEXT NOT NULL CHECK (categoria IN
        ('ITEMS','PRESUPUESTOS','COBROS','REPUESTOS','REPARACIONES','EQUIPOS','CLIENTES','ARTICULOS')),
    eliminados INTEGER NOT NULL CHECK (eliminados BETWEEN 1 AND 25),
    restantes BOOLEAN NOT NULL,
    borrado_en TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(borrado_en)),
    CONSTRAINT fk_borrado_lote_cierre FOREIGN KEY(cierre_referencia,taller_id)
        REFERENCES public.cuenta_cierres(referencia,taller_id) ON DELETE RESTRICT
);
CREATE INDEX idx_borrado_lote_cierre ON public.cuenta_borrado_lotes(taller_id,cierre_referencia,categoria);

-- This relation is private to SECURITY DEFINER code. No session setting authorizes erasure.
-- CTIDs authorize locked item rows only within this transaction; they are never durable identities.
CREATE TABLE public.cuenta_borrado_contextos (
    transaccion XID8 PRIMARY KEY,
    backend_pid INTEGER NOT NULL CHECK (backend_pid>0),
    lote_id UUID NOT NULL UNIQUE,
    taller_id BIGINT NOT NULL CHECK (taller_id>0),
    cierre_referencia UUID NOT NULL,
    generacion BIGINT NOT NULL CHECK (generacion>0),
    categoria TEXT NOT NULL CHECK (categoria IN
        ('ITEMS','PRESUPUESTOS','COBROS','REPUESTOS','REPARACIONES','EQUIPOS','CLIENTES','ARTICULOS')),
    ids BIGINT[] NOT NULL DEFAULT ARRAY[]::BIGINT[],
    item_tids TID[] NOT NULL DEFAULT ARRAY[]::TID[],
    habilitado_en TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(habilitado_en)),
    CONSTRAINT ck_borrado_contexto_objetivos CHECK (
        array_position(ids,NULL) IS NULL AND array_position(item_tids,NULL) IS NULL
        AND 0<ALL(ids)
        AND ((categoria='ITEMS' AND cardinality(ids)=0 AND cardinality(item_tids) BETWEEN 1 AND 25)
            OR (categoria<>'ITEMS' AND cardinality(item_tids)=0 AND cardinality(ids) BETWEEN 1 AND 25))),
    CONSTRAINT fk_borrado_contexto_cierre FOREIGN KEY(cierre_referencia,taller_id)
        REFERENCES public.cuenta_cierres(referencia,taller_id) ON DELETE RESTRICT
);
REVOKE ALL ON public.cuenta_borrado_lotes,public.cuenta_borrado_contextos FROM PUBLIC;

CREATE FUNCTION public.cuenta_borrado_exigir_scope_v37(p_taller BIGINT,p_cierre UUID) RETURNS BIGINT
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_generacion BIGINT;
BEGIN
    PERFORM public.legal_exigir_read_committed();
    IF current_setting('transaction_read_only')<>'off' OR p_taller IS NULL OR p_taller<=0 OR p_cierre IS NULL
        OR NOT public.cuenta_cierre_exclusivo_v33(p_taller) THEN
        RAISE EXCEPTION 'borrado requiere coordinacion exclusiva y transaccion de escritura' USING ERRCODE='P0037';
    END IF;
    -- The exclusive gate precedes every row lock, including on replay and empty batches.
    SELECT h.generacion INTO v_generacion
      FROM public.talleres t JOIN public.cuenta_cierres h
        ON h.referencia=t.cierre_referencia AND h.taller_id=t.id
      WHERE t.id=p_taller AND t.activo AND t.cierre_estado='RESTRINGIDO'
        AND t.cierre_referencia=p_cierre AND t.cierre_version=h.generacion
        AND h.estado='RESTRINGIDO' AND h.restaurado_en IS NULL AND h.politica='ordenfix-cierre/1'
        AND EXISTS (SELECT 1 FROM public.users u WHERE u.id=h.titular_id AND u.taller_id=t.id AND u.role='ADMIN')
        AND h.confirmado_en=t.cierre_confirmado_en AND h.reversible_hasta=t.cierre_reversible_hasta
        AND h.eliminacion_prevista_en=t.cierre_eliminacion_prevista_en
        AND isfinite(h.confirmado_en) AND isfinite(h.reversible_hasta) AND isfinite(h.eliminacion_prevista_en)
        AND h.reversible_hasta=h.confirmado_en+INTERVAL '168 hours'
        AND h.eliminacion_prevista_en=h.reversible_hasta+INTERVAL '720 hours'
        AND clock_timestamp()>=h.reversible_hasta
      FOR SHARE OF t,h;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'cierre vigente fuera de plazo o inconsistente' USING ERRCODE='P0037';
    END IF;
    RETURN v_generacion;
END;
$$;

CREATE FUNCTION public.cuenta_borrado_preflight_v37(p_taller BIGINT) RETURNS VOID
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    -- Legacy URLs have no durable remote identity. Absence-only receipts are not deletion proof.
    IF EXISTS (SELECT 1 FROM public.reparacion_fotos f WHERE f.taller_id=p_taller)
        OR EXISTS (SELECT 1 FROM public.reparacion_fotos_privadas f
            LEFT JOIN public.reparacion_foto_eliminaciones d ON d.foto_id=f.id
            WHERE f.taller_id=p_taller AND (f.estado<>'ELIMINADA' OR d.foto_id IS NULL
                OR d.resultado<>'IDENTIDAD_ELIMINADA' OR d.confirmada_en IS NULL
                OR d.asset_id IS NULL OR d.asset_version IS NULL
                OR (d.user_id,d.taller_id,d.object_key) IS DISTINCT FROM (f.user_id,f.taller_id,f.object_key))) THEN
        RAISE EXCEPTION 'borrado operativo bloqueado por evidencia remota pendiente' USING ERRCODE='P0037';
    END IF;
    -- Legacy foreign keys constrain IDs, not tenant pairs. Reject both inbound and outbound
    -- corruption before touching any category, including data unrelated to this batch's parents.
    IF EXISTS (SELECT 1 FROM public.equipos c JOIN public.clientes p ON p.id=c.cliente_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.reparaciones c JOIN public.equipos p ON p.id=c.equipo_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.reparaciones c JOIN public.reparaciones p ON p.id=c.reparacion_origen_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.reparaciones c JOIN public.users p ON p.id=c.tecnico_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.repuestos c JOIN public.reparaciones p ON p.id=c.reparacion_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.repuestos c JOIN public.articulos p ON p.id=c.articulo_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.presupuestos c JOIN public.reparaciones p ON p.id=c.reparacion_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.presupuesto_items c JOIN public.presupuestos p ON p.id=c.presupuesto_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.cobros c JOIN public.reparaciones p ON p.id=c.reparacion_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.cobros c JOIN public.users p ON p.id=c.anulado_por_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.reparacion_fotos c JOIN public.reparaciones p ON p.id=c.reparacion_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id))
        OR EXISTS (SELECT 1 FROM public.reparacion_fotos_privadas c JOIN public.reparaciones p
            ON p.id=c.reparacion_original_id
            WHERE c.taller_id<>p.taller_id AND p_taller IN (c.taller_id,p.taller_id)) THEN
        RAISE EXCEPTION 'borrado operativo bloqueado por referencias entre talleres' USING ERRCODE='P0037';
    END IF;
END;
$$;

CREATE FUNCTION public.cuenta_borrado_delete_guard_v37() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_ctx public.cuenta_borrado_contextos%ROWTYPE; v_categoria TEXT; v_generacion BIGINT;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_OP<>'DELETE' THEN
        RAISE EXCEPTION 'frontera de borrado operativo invalida' USING ERRCODE='P0037';
    END IF;
    v_categoria:=CASE TG_TABLE_NAME WHEN 'presupuesto_items' THEN 'ITEMS' WHEN 'presupuestos' THEN 'PRESUPUESTOS'
        WHEN 'cobros' THEN 'COBROS' WHEN 'repuestos' THEN 'REPUESTOS' WHEN 'reparaciones' THEN 'REPARACIONES'
        WHEN 'equipos' THEN 'EQUIPOS' WHEN 'clientes' THEN 'CLIENTES' WHEN 'articulos' THEN 'ARTICULOS' END;
    IF v_categoria IS NULL THEN
        RAISE EXCEPTION 'categoria de borrado operativo invalida' USING ERRCODE='P0037';
    END IF;
    SELECT * INTO v_ctx FROM public.cuenta_borrado_contextos WHERE transaccion=pg_current_xact_id();
    IF FOUND THEN
        -- Never fall back to ABIERTO while this transaction owns a private erase context:
        -- an unexpected FK cascade into a different tenant/category/row must roll back everything.
        IF v_ctx.backend_pid<>pg_backend_pid() OR v_ctx.taller_id<>OLD.taller_id OR v_ctx.categoria<>v_categoria THEN
            RAISE EXCEPTION 'fila fuera del lote de borrado' USING ERRCODE='P0037';
        END IF;
        IF v_categoria='ITEMS' THEN
            IF NOT (OLD.ctid=ANY(v_ctx.item_tids)) THEN
                RAISE EXCEPTION 'item fuera del lote de borrado' USING ERRCODE='P0037';
            END IF;
        ELSIF NOT (OLD.id=ANY(v_ctx.ids)) THEN
            RAISE EXCEPTION 'fila fuera del lote de borrado' USING ERRCODE='P0037';
        END IF;
        v_generacion:=public.cuenta_borrado_exigir_scope_v37(v_ctx.taller_id,v_ctx.cierre_referencia);
        IF v_generacion<>v_ctx.generacion THEN
            RAISE EXCEPTION 'generacion de borrado inconsistente' USING ERRCODE='P0037';
        END IF;
        RETURN OLD;
    END IF;
    -- Preserve V33's original normal DELETE admission exactly for these eight tables.
    IF public.cuenta_cierre_estado_v33(OLD.taller_id)='ABIERTO' THEN RETURN OLD; END IF;
    RAISE EXCEPTION 'operacion no disponible durante el cierre' USING ERRCODE='P0033';
END;
$$;

CREATE FUNCTION public.cuenta_borrado_foto_update_guard_v37() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_ctx public.cuenta_borrado_contextos%ROWTYPE; v_generacion BIGINT; v_new JSONB; v_old JSONB;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'reparacion_fotos_privadas' OR TG_OP<>'UPDATE'
        OR NEW.taller_id IS DISTINCT FROM OLD.taller_id THEN
        RAISE EXCEPTION 'pertenencia de cuenta inmutable' USING ERRCODE='P0033';
    END IF;
    SELECT * INTO v_ctx FROM public.cuenta_borrado_contextos WHERE transaccion=pg_current_xact_id();
    IF FOUND THEN
        IF v_ctx.backend_pid<>pg_backend_pid() OR v_ctx.taller_id<>OLD.taller_id OR v_ctx.categoria<>'REPARACIONES'
            OR OLD.reparacion_id IS NULL OR NOT (OLD.reparacion_id=ANY(v_ctx.ids))
            OR NEW.reparacion_id IS NOT NULL OR OLD.estado<>'ELIMINADA'
            OR (to_jsonb(NEW)-'reparacion_id') IS DISTINCT FROM (to_jsonb(OLD)-'reparacion_id')
            OR NOT EXISTS (SELECT 1 FROM public.reparacion_foto_eliminaciones d
                WHERE d.foto_id=OLD.id AND d.user_id=OLD.user_id AND d.taller_id=OLD.taller_id
                    AND d.object_key=OLD.object_key AND d.resultado='IDENTIDAD_ELIMINADA'
                    AND d.confirmada_en IS NOT NULL AND d.asset_id IS NOT NULL AND d.asset_version IS NOT NULL) THEN
            RAISE EXCEPTION 'desvinculacion privada fuera del lote de borrado' USING ERRCODE='P0037';
        END IF;
        v_generacion:=public.cuenta_borrado_exigir_scope_v37(v_ctx.taller_id,v_ctx.cierre_referencia);
        IF v_generacion<>v_ctx.generacion THEN
            RAISE EXCEPTION 'generacion de borrado inconsistente' USING ERRCODE='P0037';
        END IF;
        RETURN NEW;
    END IF;
    -- The normal V33 photo UPDATE branch is preserved; V30/V35 identity/lifecycle guards still run.
    IF public.cuenta_cierre_estado_v33(NEW.taller_id)='ABIERTO' THEN RETURN NEW; END IF;
    v_new:=to_jsonb(NEW); v_old:=to_jsonb(OLD);
    IF (v_new-ARRAY['estado','lease_id','lease_hasta','asset_id','asset_version']) IS NOT DISTINCT FROM
       (v_old-ARRAY['estado','lease_id','lease_hasta','asset_id','asset_version'])
        AND NEW.lease_id IS NULL AND NEW.lease_hasta IS NULL
        AND ((NEW.estado=OLD.estado AND NEW.asset_id IS NOT DISTINCT FROM OLD.asset_id AND NEW.asset_version IS NOT DISTINCT FROM OLD.asset_version)
            OR (NEW.estado IN ('EXPIRADA','FALLIDA','LIMPIEZA_PENDIENTE') AND NEW.asset_id IS NOT DISTINCT FROM OLD.asset_id AND NEW.asset_version IS NOT DISTINCT FROM OLD.asset_version)
            OR (NEW.estado='ELIMINADA' AND OLD.estado='LIMPIEZA_PENDIENTE' AND NEW.asset_id IS NULL AND NEW.asset_version IS NULL)) THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'operacion no disponible durante el cierre' USING ERRCODE='P0033';
END;
$$;

CREATE FUNCTION public.cuenta_borrado_lote_guard_v37() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_ctx public.cuenta_borrado_contextos%ROWTYPE;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'cuenta_borrado_lotes' OR TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'recibo de borrado inmutable' USING ERRCODE='P0037';
    END IF;
    SELECT * INTO v_ctx FROM public.cuenta_borrado_contextos WHERE transaccion=pg_current_xact_id();
    IF NOT FOUND OR v_ctx.backend_pid<>pg_backend_pid()
        OR (NEW.lote_id,NEW.taller_id,NEW.cierre_referencia,NEW.generacion,NEW.categoria)
            IS DISTINCT FROM (v_ctx.lote_id,v_ctx.taller_id,v_ctx.cierre_referencia,v_ctx.generacion,v_ctx.categoria)
        OR NEW.eliminados<>cardinality(v_ctx.ids)+cardinality(v_ctx.item_tids)
        OR NEW.borrado_en<v_ctx.habilitado_en OR NEW.borrado_en>clock_timestamp() THEN
        RAISE EXCEPTION 'recibo fuera del lote de borrado' USING ERRCODE='P0037';
    END IF;
    IF public.cuenta_borrado_exigir_scope_v37(v_ctx.taller_id,v_ctx.cierre_referencia)<>v_ctx.generacion THEN
        RAISE EXCEPTION 'generacion de borrado inconsistente' USING ERRCODE='P0037';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.cuenta_borrado_contexto_vacio_v37() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.cuenta_borrado_contextos WHERE transaccion=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'contexto de borrado no finalizado' USING ERRCODE='P0037';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.cuenta_cierre_borrar_lote_v37(p_lote UUID,p_taller BIGINT,p_cierre UUID,p_categoria TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE
    v_generacion BIGINT; v_recibo public.cuenta_borrado_lotes%ROWTYPE; v_table TEXT; v_predicate TEXT:='';
    v_ids BIGINT[]:=ARRAY[]::BIGINT[]; v_tids TID[]:=ARRAY[]::TID[]; v_deleted INTEGER; v_remaining BOOLEAN;
BEGIN
    IF p_lote IS NULL OR p_categoria IS NULL OR p_categoria NOT IN
        ('ITEMS','PRESUPUESTOS','COBROS','REPUESTOS','REPARACIONES','EQUIPOS','CLIENTES','ARTICULOS') THEN
        RAISE EXCEPTION 'identidad de lote de borrado invalida' USING ERRCODE='P0037';
    END IF;
    v_generacion:=public.cuenta_borrado_exigir_scope_v37(p_taller,p_cierre);
    IF EXISTS (SELECT 1 FROM public.cuenta_borrado_contextos WHERE transaccion=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'lote de borrado anidado' USING ERRCODE='P0037';
    END IF;
    SELECT * INTO v_recibo FROM public.cuenta_borrado_lotes WHERE lote_id=p_lote;
    IF FOUND THEN
        IF (v_recibo.taller_id,v_recibo.cierre_referencia,v_recibo.generacion,v_recibo.categoria)
            IS DISTINCT FROM (p_taller,p_cierre,v_generacion,p_categoria) THEN
            RAISE EXCEPTION 'colision de identidad del lote de borrado' USING ERRCODE='P0037';
        END IF;
        RETURN jsonb_build_object('status','REUSED','category',p_categoria,'deleted',v_recibo.eliminados,
            'remaining',v_recibo.restantes,'receiptId',v_recibo.lote_id);
    END IF;
    PERFORM public.cuenta_borrado_preflight_v37(p_taller);
    -- Only names/predicates from this fixed allowlist are interpolated. All input values are bound.
    v_table:=CASE p_categoria WHEN 'ITEMS' THEN 'presupuesto_items' WHEN 'PRESUPUESTOS' THEN 'presupuestos'
        WHEN 'COBROS' THEN 'cobros' WHEN 'REPUESTOS' THEN 'repuestos' WHEN 'REPARACIONES' THEN 'reparaciones'
        WHEN 'EQUIPOS' THEN 'equipos' WHEN 'CLIENTES' THEN 'clientes' WHEN 'ARTICULOS' THEN 'articulos' END;
    v_predicate:=CASE p_categoria
        WHEN 'PRESUPUESTOS' THEN ' AND NOT EXISTS (SELECT 1 FROM public.presupuesto_items c WHERE c.presupuesto_id=t.id)'
        WHEN 'REPARACIONES' THEN
            ' AND NOT EXISTS (SELECT 1 FROM public.cobros c WHERE c.reparacion_id=t.id)
              AND NOT EXISTS (SELECT 1 FROM public.repuestos c WHERE c.reparacion_id=t.id)
              AND NOT EXISTS (SELECT 1 FROM public.presupuestos c WHERE c.reparacion_id=t.id)
              AND NOT EXISTS (SELECT 1 FROM public.reparacion_fotos c WHERE c.reparacion_id=t.id)
              AND NOT EXISTS (SELECT 1 FROM public.reparaciones c WHERE c.reparacion_origen_id=t.id)'
        WHEN 'EQUIPOS' THEN ' AND NOT EXISTS (SELECT 1 FROM public.reparaciones c WHERE c.equipo_id=t.id)'
        WHEN 'CLIENTES' THEN ' AND NOT EXISTS (SELECT 1 FROM public.equipos c WHERE c.cliente_id=t.id)'
        WHEN 'ARTICULOS' THEN ' AND NOT EXISTS (SELECT 1 FROM public.repuestos c WHERE c.articulo_id=t.id)'
        ELSE '' END;
    IF p_categoria='ITEMS' THEN
        SELECT coalesce(array_agg(q.ctid ORDER BY q.ctid),ARRAY[]::TID[]) INTO v_tids
          FROM (SELECT i.ctid FROM public.presupuesto_items i WHERE i.taller_id=p_taller
              ORDER BY i.ctid LIMIT 25 FOR UPDATE OF i) q;
    ELSE
        EXECUTE format('SELECT coalesce(array_agg(q.id ORDER BY q.id),ARRAY[]::BIGINT[])
            FROM (SELECT t.id FROM public.%I t WHERE t.taller_id=$1 %s ORDER BY t.id LIMIT 25 FOR UPDATE OF t) q',
            v_table,v_predicate) INTO v_ids USING p_taller;
    END IF;
    -- FOR UPDATE prevents new foreign-key references to selected parents. A second RC snapshot
    -- catches a foreign tenant's writer that committed while those parent locks were being taken.
    PERFORM public.cuenta_borrado_preflight_v37(p_taller);
    IF cardinality(v_ids)+cardinality(v_tids)=0 THEN
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I WHERE taller_id=$1)',v_table)
            INTO v_remaining USING p_taller;
        RETURN jsonb_build_object('status','EMPTY','category',p_categoria,'deleted',0,
            'remaining',v_remaining,'receiptId',NULL);
    END IF;
    INSERT INTO public.cuenta_borrado_contextos
        (transaccion,backend_pid,lote_id,taller_id,cierre_referencia,generacion,categoria,ids,item_tids)
        VALUES (pg_current_xact_id(),pg_backend_pid(),p_lote,p_taller,p_cierre,v_generacion,p_categoria,v_ids,v_tids);
    IF p_categoria='REPARACIONES' THEN
        -- Preserve every evidence/receipt column and detach only confirmed-deleted photos of
        -- exactly the authorized repairs. V30 and V35 still enforce their independent invariants.
        UPDATE public.reparacion_fotos_privadas SET reparacion_id=NULL
            WHERE taller_id=p_taller AND reparacion_id=ANY(v_ids);
    END IF;
    IF p_categoria='ITEMS' THEN
        DELETE FROM public.presupuesto_items WHERE taller_id=p_taller AND ctid=ANY(v_tids);
    ELSE
        EXECUTE format('DELETE FROM public.%I WHERE taller_id=$1 AND id=ANY($2)',v_table) USING p_taller,v_ids;
    END IF;
    GET DIAGNOSTICS v_deleted=ROW_COUNT;
    IF v_deleted<>cardinality(v_ids)+cardinality(v_tids) THEN
        RAISE EXCEPTION 'conteo de borrado distinto del lote autorizado' USING ERRCODE='P0037';
    END IF;
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I WHERE taller_id=$1)',v_table)
        INTO v_remaining USING p_taller;
    INSERT INTO public.cuenta_borrado_lotes
        (lote_id,taller_id,cierre_referencia,generacion,categoria,eliminados,restantes)
        VALUES (p_lote,p_taller,p_cierre,v_generacion,p_categoria,v_deleted,v_remaining);
    DELETE FROM public.cuenta_borrado_contextos WHERE transaccion=pg_current_xact_id();
    RETURN jsonb_build_object('status','DELETED','category',p_categoria,'deleted',v_deleted,
        'remaining',v_remaining,'receiptId',p_lote);
END;
$$;

-- Split only the affected event: all V33 INSERT/UPDATE behavior stays on its original function.
DO $$
DECLARE v_table TEXT;
BEGIN
    FOREACH v_table IN ARRAY ARRAY['presupuesto_items','presupuestos','cobros','repuestos','reparaciones','equipos','clientes','articulos']
    LOOP
        EXECUTE format('DROP TRIGGER aa_cuenta_guard_v33 ON public.%I',v_table);
        EXECUTE format('CREATE TRIGGER aa_cuenta_guard_v33 BEFORE INSERT OR UPDATE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_guard_v33()',v_table);
        EXECUTE format('CREATE TRIGGER aa_cuenta_borrado_v37 BEFORE DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.cuenta_borrado_delete_guard_v37()',v_table);
    END LOOP;
END;
$$;
DROP TRIGGER aa_cuenta_guard_v33 ON public.reparacion_fotos_privadas;
CREATE TRIGGER aa_cuenta_guard_v33 BEFORE INSERT OR DELETE ON public.reparacion_fotos_privadas
    FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_guard_v33();
CREATE TRIGGER aa_cuenta_borrado_foto_v37 BEFORE UPDATE ON public.reparacion_fotos_privadas
    FOR EACH ROW EXECUTE FUNCTION public.cuenta_borrado_foto_update_guard_v37();
CREATE TRIGGER aa_cuenta_borrado_lote_v37 BEFORE INSERT OR UPDATE OR DELETE ON public.cuenta_borrado_lotes
    FOR EACH ROW EXECUTE FUNCTION public.cuenta_borrado_lote_guard_v37();
-- TRUNCATE remains owner-only via ACL, as with historical legal tables.
CREATE CONSTRAINT TRIGGER ct_cuenta_borrado_contexto_v37 AFTER INSERT OR UPDATE ON public.cuenta_borrado_contextos
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.cuenta_borrado_contexto_vacio_v37();
REVOKE ALL ON FUNCTION public.cuenta_borrado_exigir_scope_v37(BIGINT,UUID),public.cuenta_borrado_preflight_v37(BIGINT),
    public.cuenta_borrado_delete_guard_v37(),public.cuenta_borrado_foto_update_guard_v37(),
    public.cuenta_borrado_lote_guard_v37(),public.cuenta_borrado_contexto_vacio_v37(),
    public.cuenta_cierre_borrar_lote_v37(UUID,BIGINT,UUID,TEXT) FROM PUBLIC;
