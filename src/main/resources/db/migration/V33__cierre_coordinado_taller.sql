-- V33: coordinated workshop restriction. No public close/restore command or erasure is enabled here.
-- Every writer shares the workshop admission until COMMIT. A transition owns the exclusive key first.
ALTER TABLE public.talleres
    ADD COLUMN cierre_estado VARCHAR(16) NOT NULL DEFAULT 'ABIERTO',
    ADD COLUMN cierre_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cierre_referencia UUID,
    ADD COLUMN cierre_confirmado_en TIMESTAMPTZ,
    ADD COLUMN cierre_reversible_hasta TIMESTAMPTZ,
    ADD COLUMN cierre_eliminacion_prevista_en TIMESTAMPTZ,
    ADD CONSTRAINT ck_taller_cierre_version CHECK (cierre_version >= 0),
    ADD CONSTRAINT ck_taller_cierre_estado CHECK (cierre_estado IN ('ABIERTO','RESTRINGIDO','ELIMINADO')),
    ADD CONSTRAINT ck_taller_cierre_fechas CHECK (
        (cierre_estado='ABIERTO' AND cierre_referencia IS NULL AND cierre_confirmado_en IS NULL
          AND cierre_reversible_hasta IS NULL AND cierre_eliminacion_prevista_en IS NULL)
        OR (cierre_estado IN ('RESTRINGIDO','ELIMINADO') AND cierre_version>0 AND cierre_referencia IS NOT NULL
          AND cierre_confirmado_en IS NOT NULL AND cierre_reversible_hasta IS NOT NULL
          AND cierre_eliminacion_prevista_en IS NOT NULL
          AND isfinite(cierre_confirmado_en) AND isfinite(cierre_reversible_hasta) AND isfinite(cierre_eliminacion_prevista_en)
          AND cierre_reversible_hasta=cierre_confirmado_en+INTERVAL '168 hours'
          AND cierre_eliminacion_prevista_en=cierre_reversible_hasta+INTERVAL '720 hours'));

CREATE TABLE public.cuenta_cierres (
    referencia UUID PRIMARY KEY,
    taller_id BIGINT NOT NULL REFERENCES public.talleres(id) ON DELETE RESTRICT,
    titular_id BIGINT NOT NULL CHECK (titular_id>0),
    generacion BIGINT NOT NULL CHECK (generacion>0),
    estado VARCHAR(16) NOT NULL CHECK (estado IN ('RESTRINGIDO','RESTAURADO','ELIMINADO')),
    politica VARCHAR(40) NOT NULL CHECK (politica='ordenfix-cierre/1'),
    confirmado_en TIMESTAMPTZ NOT NULL,
    reversible_hasta TIMESTAMPTZ NOT NULL,
    eliminacion_prevista_en TIMESTAMPTZ NOT NULL,
    restaurado_en TIMESTAMPTZ,
    CONSTRAINT uq_cuenta_cierre_generacion UNIQUE(taller_id,generacion),
    CONSTRAINT uq_cuenta_cierre_pertenencia UNIQUE(referencia,taller_id),
    CONSTRAINT ck_cuenta_cierre_fechas CHECK (
        isfinite(confirmado_en) AND isfinite(reversible_hasta) AND isfinite(eliminacion_prevista_en)
        AND reversible_hasta=confirmado_en+INTERVAL '168 hours'
        AND eliminacion_prevista_en=reversible_hasta+INTERVAL '720 hours'),
    CONSTRAINT ck_cuenta_cierre_restauracion CHECK (
        (estado='RESTAURADO' AND restaurado_en IS NOT NULL AND isfinite(restaurado_en)
          AND restaurado_en>=confirmado_en AND restaurado_en<reversible_hasta)
        OR (estado<>'RESTAURADO' AND restaurado_en IS NULL))
);
CREATE UNIQUE INDEX uq_cuenta_cierre_restringido ON public.cuenta_cierres(taller_id) WHERE estado='RESTRINGIDO';
ALTER TABLE public.talleres ADD CONSTRAINT fk_taller_cierre_referencia
    FOREIGN KEY(cierre_referencia,id) REFERENCES public.cuenta_cierres(referencia,taller_id)
    DEFERRABLE INITIALLY DEFERRED;
REVOKE ALL ON public.cuenta_cierres FROM PUBLIC;

-- Preserve ownership when the parent has already disappeared from a cascading DELETE's snapshot.
-- The before-row guard supplies this column to existing JPA writers, which do not map it.
ALTER TABLE public.reparacion_fotos ADD COLUMN taller_id BIGINT;
UPDATE public.reparacion_fotos f SET taller_id=r.taller_id FROM public.reparaciones r WHERE r.id=f.reparacion_id;
ALTER TABLE public.reparacion_fotos ALTER COLUMN taller_id SET NOT NULL;
ALTER TABLE public.reparacion_fotos ADD CONSTRAINT fk_reparacion_fotos_cierre_taller
    FOREIGN KEY(taller_id) REFERENCES public.talleres(id) ON DELETE RESTRICT;
ALTER TABLE public.presupuesto_items ADD COLUMN taller_id BIGINT;
UPDATE public.presupuesto_items i SET taller_id=p.taller_id FROM public.presupuestos p WHERE p.id=i.presupuesto_id;
ALTER TABLE public.presupuesto_items ALTER COLUMN taller_id SET NOT NULL;
ALTER TABLE public.presupuesto_items ADD CONSTRAINT fk_presupuesto_items_cierre_taller
    FOREIGN KEY(taller_id) REFERENCES public.talleres(id) ON DELETE RESTRICT;
ALTER TABLE public.auth_tokens ADD COLUMN taller_id BIGINT;
UPDATE public.auth_tokens a SET taller_id=u.taller_id FROM public.users u WHERE u.id=a.user_id;
ALTER TABLE public.auth_tokens ALTER COLUMN taller_id SET NOT NULL;
ALTER TABLE public.auth_tokens ADD CONSTRAINT fk_auth_tokens_cierre_taller
    FOREIGN KEY(taller_id) REFERENCES public.talleres(id) ON DELETE RESTRICT;

CREATE FUNCTION public.cuenta_cierre_exclusivo_v33(p_taller_id BIGINT) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_key BIGINT;
BEGIN
    IF p_taller_id IS NULL OR p_taller_id<=0 THEN RETURN FALSE; END IF;
    v_key:=pg_catalog.hashtextextended('ordenfix:closure:'||p_taller_id::TEXT,0);
    RETURN EXISTS (
        SELECT 1 FROM pg_catalog.pg_locks held
        WHERE held.locktype='advisory' AND held.pid=pg_catalog.pg_backend_pid()
          AND held.database=(SELECT d.oid FROM pg_catalog.pg_database d WHERE d.datname=pg_catalog.current_database())
          AND held.granted AND held.objsubid=1 AND held.mode='ExclusiveLock'
          AND held.classid::BIGINT=((v_key>>32)&4294967295::BIGINT)
          AND held.objid::BIGINT=(v_key&4294967295::BIGINT));
END;
$$;

CREATE FUNCTION public.cuenta_cierre_estado_v33(p_taller_id BIGINT) RETURNS TEXT
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_estado TEXT; v_activo BOOLEAN;
BEGIN
    IF p_taller_id IS NULL OR p_taller_id<=0 THEN
        RAISE EXCEPTION 'pertenencia de cuenta no disponible' USING ERRCODE='P0033';
    END IF;
    -- A writer may already own row locks. Never wait for an exclusive transition in that order.
    IF NOT pg_catalog.pg_try_advisory_xact_lock_shared(pg_catalog.hashtextextended('ordenfix:closure:'||p_taller_id::TEXT,0)) THEN
        RAISE EXCEPTION 'cuenta en transicion concurrente' USING ERRCODE='P0034';
    END IF;
    IF current_setting('transaction_isolation')='read committed' THEN
        -- The shared advisory gate excludes transitions. No row lock: normal numbering may later
        -- take a workshop write lock, so two admitted RC requests must not deadlock upgrading SHARE.
        SELECT t.cierre_estado,t.activo INTO v_estado,v_activo FROM public.talleres t WHERE t.id=p_taller_id;
    ELSE
        -- Snapshot/Excel/preparation readers use RR; this detects a snapshot older than a transition.
        SELECT t.cierre_estado,t.activo INTO v_estado,v_activo FROM public.talleres t WHERE t.id=p_taller_id FOR SHARE;
    END IF;
    IF NOT FOUND THEN RAISE EXCEPTION 'pertenencia de cuenta no disponible' USING ERRCODE='P0033'; END IF;
    RETURN CASE WHEN v_activo THEN v_estado ELSE 'INACTIVO' END;
END;
$$;

CREATE FUNCTION public.cuenta_cierre_guard_v33() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE
    v_taller BIGINT; v_parent BIGINT; v_estado TEXT;
    v_new JSONB; v_old JSONB; v_gracia BOOLEAN:=FALSE; v_owner BOOLEAN:=FALSE;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' THEN RAISE EXCEPTION 'tabla de cuenta invalida' USING ERRCODE='P0033'; END IF;

    IF TG_TABLE_NAME='talleres' THEN
        IF TG_OP='INSERT' THEN
            IF NEW.cierre_estado<>'ABIERTO' OR NEW.cierre_version<>0 OR NEW.cierre_referencia IS NOT NULL
              OR NEW.cierre_confirmado_en IS NOT NULL OR NEW.cierre_reversible_hasta IS NOT NULL
              OR NEW.cierre_eliminacion_prevista_en IS NOT NULL THEN
                RAISE EXCEPTION 'estado inicial de cuenta invalido' USING ERRCODE='P0033';
            END IF;
            RETURN NEW;
        END IF;
        v_taller:=OLD.id;
        IF TG_OP='UPDATE' THEN
            IF NEW.id IS DISTINCT FROM OLD.id THEN RAISE EXCEPTION 'pertenencia de cuenta inmutable' USING ERRCODE='P0033'; END IF;
            IF (NEW.cierre_estado,NEW.cierre_version,NEW.cierre_referencia,NEW.cierre_confirmado_en,
                NEW.cierre_reversible_hasta,NEW.cierre_eliminacion_prevista_en) IS DISTINCT FROM
               (OLD.cierre_estado,OLD.cierre_version,OLD.cierre_referencia,OLD.cierre_confirmado_en,
                OLD.cierre_reversible_hasta,OLD.cierre_eliminacion_prevista_en) THEN
                IF NOT public.cuenta_cierre_exclusivo_v33(v_taller) OR OLD.cierre_version=9223372036854775807
                  OR NEW.cierre_version<>OLD.cierre_version+1
                  OR (to_jsonb(NEW)-ARRAY['cierre_estado','cierre_version','cierre_referencia','cierre_confirmado_en',
                      'cierre_reversible_hasta','cierre_eliminacion_prevista_en','updated_at']) IS DISTINCT FROM
                     (to_jsonb(OLD)-ARRAY['cierre_estado','cierre_version','cierre_referencia','cierre_confirmado_en',
                      'cierre_reversible_hasta','cierre_eliminacion_prevista_en','updated_at'])
                  OR NOT ((OLD.cierre_estado='ABIERTO' AND NEW.cierre_estado='RESTRINGIDO' AND OLD.activo)
                       OR (OLD.cierre_estado='RESTRINGIDO' AND NEW.cierre_estado='ABIERTO' AND OLD.activo)) THEN
                    RAISE EXCEPTION 'transicion de cuenta no autorizada' USING ERRCODE='P0033';
                END IF;
                -- The deferred history/anchor pair validates reference, generation and exact dates.
                RETURN NEW;
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME IN ('reparacion_fotos','presupuesto_items','auth_tokens') THEN
        IF TG_OP='DELETE' THEN v_taller:=OLD.taller_id;
        ELSE
            IF TG_TABLE_NAME='reparacion_fotos' THEN
                SELECT r.taller_id INTO v_parent FROM public.reparaciones r WHERE r.id=NEW.reparacion_id;
            ELSIF TG_TABLE_NAME='presupuesto_items' THEN
                SELECT p.taller_id INTO v_parent FROM public.presupuestos p WHERE p.id=NEW.presupuesto_id;
            ELSE
                SELECT u.taller_id INTO v_parent FROM public.users u WHERE u.id=NEW.user_id;
            END IF;
            IF v_parent IS NULL OR (NEW.taller_id IS NOT NULL AND NEW.taller_id<>v_parent)
              OR (TG_OP='UPDATE' AND OLD.taller_id IS DISTINCT FROM v_parent) THEN
                RAISE EXCEPTION 'pertenencia de cuenta inmutable' USING ERRCODE='P0033';
            END IF;
            NEW.taller_id:=v_parent; v_taller:=v_parent;
        END IF;
    ELSIF TG_TABLE_NAME IN ('subscription_provider_links','subscription_payments') THEN
        IF TG_OP='DELETE' THEN v_parent:=OLD.suscripcion_id; ELSE v_parent:=NEW.suscripcion_id; END IF;
        IF TG_OP='UPDATE' AND NEW.suscripcion_id IS DISTINCT FROM OLD.suscripcion_id THEN
            RAISE EXCEPTION 'pertenencia de cuenta inmutable' USING ERRCODE='P0033';
        END IF;
        SELECT s.taller_id INTO v_taller FROM public.suscripciones s WHERE s.id=v_parent;
    ELSIF TG_TABLE_NAME='legal_aceptacion_documentos' THEN
        IF TG_OP='DELETE' THEN
            SELECT a.taller_id INTO v_taller FROM public.legal_aceptaciones a WHERE a.id=OLD.aceptacion_id;
        ELSE
            SELECT a.taller_id INTO v_taller FROM public.legal_aceptaciones a WHERE a.id=NEW.aceptacion_id;
            IF TG_OP='UPDATE' AND NEW.aceptacion_id IS DISTINCT FROM OLD.aceptacion_id THEN
                RAISE EXCEPTION 'pertenencia de cuenta inmutable' USING ERRCODE='P0033';
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME IN ('legal_aceptacion_metadatos','legal_aceptacion_metadatos_cifrados') THEN
        IF TG_OP='DELETE' THEN
            SELECT l.taller_id INTO v_taller FROM public.legal_aceptacion_lotes l WHERE l.id=OLD.lote_id;
        ELSE
            SELECT l.taller_id INTO v_taller FROM public.legal_aceptacion_lotes l WHERE l.id=NEW.lote_id;
            IF TG_OP='UPDATE' AND NEW.lote_id IS DISTINCT FROM OLD.lote_id THEN
                RAISE EXCEPTION 'pertenencia de cuenta inmutable' USING ERRCODE='P0033';
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME IN ('users','clientes','equipos','reparaciones','repuestos','articulos','presupuestos','cobros',
            'taller_qr_cobro','suscripciones','cuenta_reautenticaciones','cuenta_exportaciones',
            'reparacion_fotos_privadas','reparacion_foto_atestaciones','legal_aceptacion_lotes','legal_aceptaciones',
            'legal_idempotencia_resultados','legal_idempotencia_sin_actos','legal_idempotencia_sin_actos_referencias') THEN
        IF TG_OP='DELETE' THEN v_taller:=OLD.taller_id; ELSE v_taller:=NEW.taller_id; END IF;
        IF TG_OP='UPDATE' AND NEW.taller_id IS DISTINCT FROM OLD.taller_id THEN
            RAISE EXCEPTION 'pertenencia de cuenta inmutable' USING ERRCODE='P0033';
        END IF;
    ELSE RAISE EXCEPTION 'tabla de cuenta invalida' USING ERRCODE='P0033';
    END IF;

    v_estado:=public.cuenta_cierre_estado_v33(v_taller);
    IF v_estado='ABIERTO' THEN
        IF TG_TABLE_NAME='talleres' AND TG_OP='DELETE' THEN
            -- Preserve the existing QR CASCADE, while the anchor is still visible to its own guard.
            DELETE FROM public.taller_qr_cobro WHERE taller_id=v_taller;
        END IF;
        IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;

    SELECT t.activo AND t.cierre_estado='RESTRINGIDO' AND clock_timestamp()>=t.cierre_confirmado_en
           AND clock_timestamp()<t.cierre_reversible_hasta INTO v_gracia
      FROM public.talleres t WHERE t.id=v_taller;

    -- Avoid serializing potentially large encrypted exports/QR bytea as JSON in a trigger.
    IF TG_TABLE_NAME='cuenta_exportaciones' THEN
        IF TG_OP='DELETE' THEN
            IF OLD.estado IN ('FAILED','EXPIRED','REVOKED') AND OLD.snapshot_cipher IS NULL
              AND OLD.archive_cipher IS NULL AND cardinality(OLD.foto_ids)=0 AND OLD.lease_id IS NULL
              AND OLD.lease_hasta IS NULL AND OLD.actualizada_en<clock_timestamp()-INTERVAL '7 days' THEN RETURN OLD; END IF;
            RAISE EXCEPTION 'operacion no disponible durante el cierre' USING ERRCODE='P0033';
        END IF;
        IF TG_OP='UPDATE' AND NEW.estado IN ('FAILED','EXPIRED','REVOKED')
          AND NEW.snapshot_cipher IS NULL AND NEW.archive_cipher IS NULL AND cardinality(NEW.foto_ids)=0
          AND NEW.lease_id IS NULL AND NEW.lease_hasta IS NULL AND NEW.actualizada_en>=OLD.actualizada_en
          AND (NEW.id,NEW.user_id,NEW.taller_id,NEW.token_version,NEW.session_hash,NEW.request_hash,
               NEW.creada_en,NEW.expira_en,NEW.intentos,NEW.capturada_en) IS NOT DISTINCT FROM
              (OLD.id,OLD.user_id,OLD.taller_id,OLD.token_version,OLD.session_hash,OLD.request_hash,
               OLD.creada_en,OLD.expira_en,OLD.intentos,OLD.capturada_en) THEN RETURN NEW; END IF;
        RAISE EXCEPTION 'operacion no disponible durante el cierre' USING ERRCODE='P0033';
    ELSIF TG_TABLE_NAME='taller_qr_cobro' THEN
        RAISE EXCEPTION 'operacion no disponible durante el cierre' USING ERRCODE='P0033';
    END IF;
    IF TG_OP<>'DELETE' THEN v_new:=to_jsonb(NEW); END IF;
    IF TG_OP<>'INSERT' THEN v_old:=to_jsonb(OLD); END IF;

    IF TG_TABLE_NAME='users' AND TG_OP='UPDATE' THEN
        IF (v_new-ARRAY['token_version','active','password']) IS NOT DISTINCT FROM (v_old-ARRAY['token_version','active','password'])
          AND NEW.token_version>=OLD.token_version AND NOT (NEW.active AND NOT OLD.active)
          AND (NEW.password IS NOT DISTINCT FROM OLD.password OR
               (v_gracia AND OLD.role='ADMIN' AND OLD.active AND OLD.email_verificado AND NEW.token_version>OLD.token_version))
          THEN RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME='auth_tokens' THEN
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
        SELECT u.active AND u.email_verificado AND u.role='ADMIN' INTO v_owner
          FROM public.users u WHERE u.id=NEW.user_id AND u.taller_id=v_taller;
        IF v_gracia AND v_owner AND NEW.tipo='RESET_PASSWORD' AND
          (TG_OP='INSERT' OR (TG_OP='UPDATE' AND OLD.usado_en IS NULL AND NEW.usado_en IS NOT NULL
           AND (v_new-'usado_en') IS NOT DISTINCT FROM (v_old-'usado_en'))) THEN RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME='cuenta_reautenticaciones' THEN
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
        SELECT u.active AND u.email_verificado AND u.role='ADMIN' AND u.token_version=NEW.token_version INTO v_owner
          FROM public.users u WHERE u.id=NEW.user_id AND u.taller_id=v_taller;
        IF v_gracia AND v_owner AND NEW.proposito='DESCARGAR_EXPORTACION'
          AND NEW.expira_en<=(SELECT t.cierre_reversible_hasta FROM public.talleres t WHERE t.id=v_taller)
          AND (TG_OP='INSERT' OR (TG_OP='UPDATE' AND OLD.usada_en IS NULL AND NEW.usada_en IS NOT NULL
           AND (v_new-'usada_en') IS NOT DISTINCT FROM (v_old-'usada_en'))) THEN RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME='reparacion_fotos_privadas' AND TG_OP='UPDATE' THEN
        -- Existing V30 identity and transition guards still run after this admission.
        IF (v_new-ARRAY['estado','lease_id','lease_hasta','asset_id','asset_version']) IS NOT DISTINCT FROM
           (v_old-ARRAY['estado','lease_id','lease_hasta','asset_id','asset_version'])
          AND NEW.lease_id IS NULL AND NEW.lease_hasta IS NULL
          AND ((NEW.estado=OLD.estado AND NEW.asset_id IS NOT DISTINCT FROM OLD.asset_id AND NEW.asset_version IS NOT DISTINCT FROM OLD.asset_version)
            OR (NEW.estado IN ('EXPIRADA','FALLIDA','LIMPIEZA_PENDIENTE') AND NEW.asset_id IS NOT DISTINCT FROM OLD.asset_id AND NEW.asset_version IS NOT DISTINCT FROM OLD.asset_version)
            OR (NEW.estado='ELIMINADA' AND OLD.estado='LIMPIEZA_PENDIENTE' AND NEW.asset_id IS NULL AND NEW.asset_version IS NULL)) THEN RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME='suscripciones' AND TG_OP='UPDATE' THEN
        IF (v_new-ARRAY['mp_status','mp_preapproval_id','mp_payer_id','mp_external_reference','mp_checkout_init_point',
                'mp_next_payment_at','mp_last_payment_at','mp_last_authorized_payment_id','proximo_cobro','updated_at','plan','estado'])
            IS NOT DISTINCT FROM
           (v_old-ARRAY['mp_status','mp_preapproval_id','mp_payer_id','mp_external_reference','mp_checkout_init_point',
                'mp_next_payment_at','mp_last_payment_at','mp_last_authorized_payment_id','proximo_cobro','updated_at','plan','estado'])
          AND (OLD.mp_preapproval_id IS NULL OR NEW.mp_preapproval_id IS NOT DISTINCT FROM OLD.mp_preapproval_id)
          AND (OLD.mp_external_reference IS NULL OR NEW.mp_external_reference IS NOT DISTINCT FROM OLD.mp_external_reference)
          AND ((NEW.plan,NEW.estado) IS NOT DISTINCT FROM (OLD.plan,OLD.estado)
            OR (NEW.plan='FREE' AND NEW.estado='ACTIVA' AND lower(btrim(NEW.mp_status)) IN ('canceled','cancelled')
                AND NEW.proximo_cobro IS NULL AND NEW.mp_next_payment_at IS NULL)) THEN RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME='subscription_provider_links' AND TG_OP='UPDATE' THEN
        IF (v_new-ARRAY['external_subscription_id','checkout_url','status','is_current','updated_at',
                'last_reconciled_at','last_reconciliation_status','last_reconciliation_error']) IS NOT DISTINCT FROM
           (v_old-ARRAY['external_subscription_id','checkout_url','status','is_current','updated_at',
                'last_reconciled_at','last_reconciliation_status','last_reconciliation_error'])
          AND (OLD.external_subscription_id IS NULL OR NEW.external_subscription_id IS NOT DISTINCT FROM OLD.external_subscription_id)
          AND NOT (NEW.is_current AND NOT OLD.is_current) THEN RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME='subscription_payments' THEN
        -- This is an observation ledger, not an entitlement. Subscription promotion remains blocked above.
        IF TG_OP='INSERT' OR (TG_OP='UPDATE' AND
           (v_new-ARRAY['external_payment_id','amount','currency','invoice_status','payment_status','status_detail','summarized',
                'retry_attempt','debit_at','provider_created_at','provider_modified_at','updated_at']) IS NOT DISTINCT FROM
           (v_old-ARRAY['external_payment_id','amount','currency','invoice_status','payment_status','status_detail','summarized',
                'retry_attempt','debit_at','provider_created_at','provider_modified_at','updated_at'])
           AND (OLD.external_payment_id IS NULL OR NEW.external_payment_id IS NOT DISTINCT FROM OLD.external_payment_id)) THEN RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME='legal_aceptacion_metadatos' AND TG_OP='UPDATE' THEN
        IF (v_new-'purgado_en') IS NOT DISTINCT FROM (v_old-'purgado_en')
          AND OLD.purgado_en IS NULL AND NEW.purgado_en IS NOT NULL THEN RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME='legal_aceptacion_metadatos_cifrados' AND TG_OP='UPDATE' THEN
        IF (v_new-ARRAY['ciphertext','tag','longitud_original','tombstone_en']) IS NOT DISTINCT FROM
           (v_old-ARRAY['ciphertext','tag','longitud_original','tombstone_en'])
          AND OLD.tombstone_en IS NULL AND NEW.tombstone_en IS NOT NULL
          AND NEW.ciphertext IS NULL AND NEW.tag IS NULL AND NEW.longitud_original IS NULL THEN RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME IN ('legal_idempotencia_resultados','legal_idempotencia_sin_actos','legal_idempotencia_sin_actos_referencias')
      AND TG_OP='DELETE' THEN
        -- V27/V29 still require the original expiration, lock and reference consistency.
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'operacion no disponible durante el cierre' USING ERRCODE='P0033';
END;
$$;

CREATE FUNCTION public.cuenta_cierre_historial_guard_v33() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_taller BIGINT; v_anchor RECORD; v_history RECORD;
BEGIN
    IF TG_WHEN='BEFORE' THEN
        IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'cuenta_cierres' OR TG_OP='DELETE' THEN
            RAISE EXCEPTION 'historial de cierre inmutable' USING ERRCODE='P0033';
        END IF;
        v_taller:=NEW.taller_id;
        IF NOT public.cuenta_cierre_exclusivo_v33(v_taller) THEN
            RAISE EXCEPTION 'historial sin coordinacion exclusiva' USING ERRCODE='P0033';
        END IF;
        SELECT t.cierre_estado,t.cierre_version,t.cierre_referencia,t.activo INTO v_anchor
          FROM public.talleres t WHERE t.id=v_taller FOR SHARE;
        IF NOT FOUND OR NOT v_anchor.activo THEN RAISE EXCEPTION 'ancla de cierre no habilitada' USING ERRCODE='P0033'; END IF;
        IF TG_OP='INSERT' THEN
            IF v_anchor.cierre_estado<>'ABIERTO' OR v_anchor.cierre_version=9223372036854775807
              OR NEW.generacion<>v_anchor.cierre_version+1 OR NEW.estado<>'RESTRINGIDO' OR NEW.restaurado_en IS NOT NULL THEN
                RAISE EXCEPTION 'historial inicial de cierre invalido' USING ERRCODE='P0033';
            END IF;
            PERFORM 1 FROM public.users u WHERE u.id=NEW.titular_id AND u.taller_id=v_taller
              AND u.active AND u.role='ADMIN' AND u.email_verificado FOR SHARE;
            IF NOT FOUND THEN RAISE EXCEPTION 'titular de cierre no habilitado' USING ERRCODE='P0033'; END IF;
        ELSE
            IF (to_jsonb(NEW)-ARRAY['estado','restaurado_en']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['estado','restaurado_en'])
              OR OLD.estado<>'RESTRINGIDO' OR NEW.estado<>'RESTAURADO' OR NEW.restaurado_en IS NULL
              OR v_anchor.cierre_estado<>'RESTRINGIDO' OR v_anchor.cierre_referencia IS DISTINCT FROM OLD.referencia
              OR v_anchor.cierre_version<>OLD.generacion THEN
                RAISE EXCEPTION 'transicion de historial invalida' USING ERRCODE='P0033';
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    -- Deferred checks inspect final persisted rows, including after repeated transitions in one transaction.
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME NOT IN ('talleres','cuenta_cierres') THEN
        RAISE EXCEPTION 'tabla de consistencia invalida' USING ERRCODE='P0033';
    END IF;
    IF TG_TABLE_NAME='talleres' THEN v_taller:=NEW.id; ELSE v_taller:=NEW.taller_id; END IF;
    SELECT t.cierre_estado,t.cierre_version,t.cierre_referencia,t.cierre_confirmado_en,
           t.cierre_reversible_hasta,t.cierre_eliminacion_prevista_en INTO v_anchor
      FROM public.talleres t WHERE t.id=v_taller;
    IF NOT FOUND THEN
        IF TG_TABLE_NAME='talleres' THEN RETURN NULL; END IF;
        RAISE EXCEPTION 'historial sin ancla' USING ERRCODE='P0033';
    END IF;
    IF v_anchor.cierre_estado='ABIERTO' THEN
        IF EXISTS (SELECT 1 FROM public.cuenta_cierres h WHERE h.taller_id=v_taller AND h.estado='RESTRINGIDO')
          OR (v_anchor.cierre_version=0 AND EXISTS (SELECT 1 FROM public.cuenta_cierres h WHERE h.taller_id=v_taller))
          OR (v_anchor.cierre_version>0 AND NOT EXISTS (SELECT 1 FROM public.cuenta_cierres h
              WHERE h.taller_id=v_taller AND h.estado='RESTAURADO' AND h.generacion=v_anchor.cierre_version-1)) THEN
            RAISE EXCEPTION 'ancla e historial de cierre inconsistentes' USING ERRCODE='P0033';
        END IF;
    ELSIF v_anchor.cierre_estado='RESTRINGIDO' THEN
        IF NOT EXISTS (SELECT 1 FROM public.cuenta_cierres h WHERE h.referencia=v_anchor.cierre_referencia
            AND h.taller_id=v_taller AND h.generacion=v_anchor.cierre_version AND h.estado='RESTRINGIDO'
            AND h.confirmado_en=v_anchor.cierre_confirmado_en AND h.reversible_hasta=v_anchor.cierre_reversible_hasta
            AND h.eliminacion_prevista_en=v_anchor.cierre_eliminacion_prevista_en) THEN
            RAISE EXCEPTION 'ancla e historial de cierre inconsistentes' USING ERRCODE='P0033';
        END IF;
    ELSE RAISE EXCEPTION 'eliminacion no habilitada en este corte' USING ERRCODE='P0033';
    END IF;
    IF TG_TABLE_NAME='cuenta_cierres' THEN
        SELECT h.estado,h.generacion,h.referencia INTO v_history FROM public.cuenta_cierres h WHERE h.referencia=NEW.referencia;
        IF NOT FOUND OR (v_history.estado='RESTRINGIDO' AND v_history.referencia IS DISTINCT FROM v_anchor.cierre_referencia)
          OR (v_history.estado='RESTAURADO' AND v_history.generacion>=v_anchor.cierre_version)
          OR v_history.estado='ELIMINADO' THEN
            RAISE EXCEPTION 'ancla e historial de cierre inconsistentes' USING ERRCODE='P0033';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

DO $$
DECLARE v_table TEXT;
BEGIN
    FOREACH v_table IN ARRAY ARRAY[
        'talleres','users','clientes','equipos','reparaciones','repuestos','articulos','presupuestos','cobros','taller_qr_cobro',
        'reparacion_fotos','presupuesto_items','auth_tokens','suscripciones','subscription_provider_links','subscription_payments',
        'cuenta_reautenticaciones','cuenta_exportaciones','reparacion_fotos_privadas','reparacion_foto_atestaciones',
        'legal_aceptacion_lotes','legal_aceptaciones','legal_aceptacion_documentos','legal_aceptacion_metadatos',
        'legal_aceptacion_metadatos_cifrados','legal_idempotencia_resultados','legal_idempotencia_sin_actos','legal_idempotencia_sin_actos_referencias']
    LOOP
        EXECUTE format('CREATE TRIGGER aa_cuenta_guard_v33 BEFORE INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_guard_v33()',v_table);
    END LOOP;
END;
$$;
CREATE TRIGGER aa_cuenta_historial_v33 BEFORE INSERT OR UPDATE OR DELETE ON public.cuenta_cierres
    FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_historial_guard_v33();
CREATE CONSTRAINT TRIGGER ct_cuenta_cierre_taller_v33 AFTER INSERT OR UPDATE ON public.talleres
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_historial_guard_v33();
CREATE CONSTRAINT TRIGGER ct_cuenta_cierre_historial_v33 AFTER INSERT OR UPDATE ON public.cuenta_cierres
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_historial_guard_v33();
REVOKE ALL ON FUNCTION public.cuenta_cierre_exclusivo_v33(BIGINT),public.cuenta_cierre_estado_v33(BIGINT),
    public.cuenta_cierre_guard_v33(),public.cuenta_cierre_historial_guard_v33() FROM PUBLIC;
