-- V34: internal closure confirmation, immutable receipts and durable provider intentions.
-- No controller, scheduler or real provider adapter is enabled by this migration.
CREATE TABLE public.cuenta_cierre_confirmaciones (
    token_hash CHAR(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE RESTRICT,
    taller_id BIGINT NOT NULL REFERENCES public.talleres(id) ON DELETE RESTRICT,
    token_version BIGINT NOT NULL CHECK (token_version>=0),
    session_hash CHAR(64) NOT NULL CHECK (session_hash ~ '^[0-9a-f]{64}$'),
    proposito VARCHAR(16) NOT NULL CHECK (proposito IN ('CERRAR','RESTAURAR')),
    operacion_id UUID NOT NULL,
    cierre_referencia UUID NOT NULL,
    cierre_version BIGINT NOT NULL CHECK (cierre_version>=0),
    creada_en TIMESTAMPTZ NOT NULL,
    expira_en TIMESTAMPTZ NOT NULL,
    usada_en TIMESTAMPTZ,
    CONSTRAINT ck_cierre_confirmacion_plazo CHECK (
        isfinite(creada_en) AND isfinite(expira_en) AND expira_en>creada_en
        AND expira_en<=creada_en+INTERVAL '5 minutes'
        AND (usada_en IS NULL OR (isfinite(usada_en) AND usada_en>=creada_en AND usada_en<expira_en))),
    CONSTRAINT ck_cierre_confirmacion_referencia CHECK (proposito<>'CERRAR' OR operacion_id=cierre_referencia)
);
CREATE UNIQUE INDEX uq_cierre_confirmacion_vigente ON public.cuenta_cierre_confirmaciones(user_id,session_hash,proposito)
    WHERE usada_en IS NULL;
REVOKE ALL ON public.cuenta_cierre_confirmaciones FROM PUBLIC;

CREATE TABLE public.cuenta_cierre_operaciones (
    operacion_id UUID PRIMARY KEY,
    taller_id BIGINT NOT NULL REFERENCES public.talleres(id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL CHECK (user_id>0),
    proposito VARCHAR(16) NOT NULL CHECK (proposito IN ('CERRAR','RESTAURAR')),
    cierre_referencia UUID NOT NULL,
    cierre_version BIGINT NOT NULL CHECK (cierre_version>0),
    request_digest CHAR(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    proof_hash CHAR(64) NOT NULL UNIQUE REFERENCES public.cuenta_cierre_confirmaciones(token_hash) ON DELETE RESTRICT,
    estado_resultante VARCHAR(16) NOT NULL,
    politica VARCHAR(40) NOT NULL CHECK (politica='ordenfix-cierre/1'),
    confirmado_en TIMESTAMPTZ NOT NULL,
    reversible_hasta TIMESTAMPTZ NOT NULL,
    eliminacion_prevista_en TIMESTAMPTZ NOT NULL,
    registrada_en TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_cierre_operacion_generacion UNIQUE(taller_id,cierre_version),
    CONSTRAINT fk_cierre_operacion_referencia FOREIGN KEY(cierre_referencia,taller_id)
        REFERENCES public.cuenta_cierres(referencia,taller_id) ON DELETE RESTRICT,
    CONSTRAINT ck_cierre_operacion_estado CHECK (
        (proposito='CERRAR' AND estado_resultante='RESTRINGIDO' AND operacion_id=cierre_referencia)
        OR (proposito='RESTAURAR' AND estado_resultante='ABIERTO')),
    CONSTRAINT ck_cierre_operacion_fechas CHECK (
        isfinite(confirmado_en) AND isfinite(reversible_hasta) AND isfinite(eliminacion_prevista_en)
        AND isfinite(registrada_en) AND registrada_en>=confirmado_en AND registrada_en<reversible_hasta
        AND reversible_hasta=confirmado_en+INTERVAL '168 hours'
        AND eliminacion_prevista_en=reversible_hasta+INTERVAL '720 hours')
);
REVOKE ALL ON public.cuenta_cierre_operaciones FROM PUBLIC;

CREATE FUNCTION public.cuenta_cierre_confirmacion_guard_v34() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_taller BIGINT; v_state TEXT; v_owner public.users%ROWTYPE; v_anchor public.talleres%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN v_taller:=OLD.taller_id; ELSE v_taller:=NEW.taller_id; END IF;
    v_state:=public.cuenta_cierre_estado_v33(v_taller);
    IF TG_OP='DELETE' THEN
        IF OLD.usada_en IS NOT NULL THEN RAISE EXCEPTION 'confirmacion usada inmutable' USING ERRCODE='P0033'; END IF;
        RETURN OLD;
    END IF;
    IF TG_OP='UPDATE' AND ((to_jsonb(NEW)-'usada_en') IS DISTINCT FROM (to_jsonb(OLD)-'usada_en')
        OR OLD.usada_en IS NOT NULL OR NEW.usada_en IS NULL) THEN
        RAISE EXCEPTION 'confirmacion inmutable' USING ERRCODE='P0033';
    END IF;
    IF TG_OP='INSERT' AND NEW.usada_en IS NOT NULL THEN
        RAISE EXCEPTION 'confirmacion inicial invalida' USING ERRCODE='P0033';
    END IF;
    SELECT * INTO v_owner FROM public.users WHERE id=NEW.user_id;
    SELECT * INTO v_anchor FROM public.talleres WHERE id=NEW.taller_id;
    IF v_owner.id IS NULL OR v_owner.taller_id IS DISTINCT FROM NEW.taller_id
        OR v_owner.role<>'ADMIN' OR NOT v_owner.active OR NOT v_owner.email_verificado
        OR v_owner.token_version IS DISTINCT FROM NEW.token_version
        OR v_anchor.cierre_version IS DISTINCT FROM NEW.cierre_version
        OR NOT ((NEW.proposito='CERRAR' AND v_state='ABIERTO' AND v_anchor.cierre_referencia IS NULL)
          OR (NEW.proposito='RESTAURAR' AND v_state='RESTRINGIDO'
              AND v_anchor.cierre_referencia=NEW.cierre_referencia
              AND NEW.creada_en>=v_anchor.cierre_confirmado_en
              AND NEW.expira_en<=v_anchor.cierre_reversible_hasta)) THEN
        RAISE EXCEPTION 'confirmacion de cuenta invalida' USING ERRCODE='P0033';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER aa_cuenta_cierre_confirmacion_v34 BEFORE INSERT OR UPDATE OR DELETE
    ON public.cuenta_cierre_confirmaciones FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_confirmacion_guard_v34();
REVOKE ALL ON FUNCTION public.cuenta_cierre_confirmacion_guard_v34() FROM PUBLIC;

CREATE FUNCTION public.cuenta_cierre_operacion_guard_v34() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE v_proof public.cuenta_cierre_confirmaciones%ROWTYPE; v_history public.cuenta_cierres%ROWTYPE;
    v_anchor public.talleres%ROWTYPE; v_owner public.users%ROWTYPE;
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'constancia de cierre inmutable' USING ERRCODE='P0033'; END IF;
    IF NOT public.cuenta_cierre_exclusivo_v33(NEW.taller_id)
        OR current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'constancia fuera de transicion' USING ERRCODE='P0033';
    END IF;
    SELECT * INTO v_proof FROM public.cuenta_cierre_confirmaciones WHERE token_hash=NEW.proof_hash;
    SELECT * INTO v_history FROM public.cuenta_cierres WHERE referencia=NEW.cierre_referencia;
    SELECT * INTO v_anchor FROM public.talleres WHERE id=NEW.taller_id;
    SELECT * INTO v_owner FROM public.users WHERE id=NEW.user_id;
    IF v_proof.token_hash IS NULL OR v_history.referencia IS NULL OR v_anchor.id IS NULL OR v_owner.id IS NULL
        OR v_proof.usada_en IS NULL OR v_proof.operacion_id IS DISTINCT FROM NEW.operacion_id
        OR v_proof.cierre_referencia IS DISTINCT FROM NEW.cierre_referencia
        OR v_proof.user_id IS DISTINCT FROM NEW.user_id OR v_proof.taller_id IS DISTINCT FROM NEW.taller_id
        OR v_proof.proposito IS DISTINCT FROM NEW.proposito OR v_proof.cierre_version=9223372036854775807
        OR NEW.cierre_version<>v_proof.cierre_version+1 OR v_proof.token_version=9223372036854775807
        OR v_owner.token_version<>v_proof.token_version+1 OR v_owner.taller_id<>NEW.taller_id
        OR v_owner.role<>'ADMIN' OR NOT v_owner.active OR NOT v_owner.email_verificado
        OR v_history.taller_id<>NEW.taller_id OR v_history.titular_id<>NEW.user_id
        OR v_history.politica<>NEW.politica OR v_history.confirmado_en<>NEW.confirmado_en
        OR v_history.reversible_hasta<>NEW.reversible_hasta OR v_history.eliminacion_prevista_en<>NEW.eliminacion_prevista_en
        OR v_anchor.cierre_version<>NEW.cierre_version OR v_anchor.cierre_estado<>NEW.estado_resultante
        OR NEW.registrada_en<v_proof.usada_en OR NEW.registrada_en>=v_proof.expira_en
        OR NOT v_anchor.activo
        OR NOT ((NEW.proposito='CERRAR' AND v_history.estado='RESTRINGIDO'
                 AND v_history.generacion=NEW.cierre_version AND v_anchor.cierre_referencia=NEW.cierre_referencia)
             OR (NEW.proposito='RESTAURAR' AND v_history.estado='RESTAURADO'
                 AND v_history.generacion=NEW.cierre_version-1 AND v_anchor.cierre_referencia IS NULL)) THEN
        RAISE EXCEPTION 'constancia de cierre incompatible' USING ERRCODE='P0033';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER aa_cuenta_cierre_operacion_v34 BEFORE INSERT OR UPDATE OR DELETE
    ON public.cuenta_cierre_operaciones FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_operacion_guard_v34();
REVOKE ALL ON FUNCTION public.cuenta_cierre_operacion_guard_v34() FROM PUBLIC;

-- Durable effects; cancellation identity survives restoration and subsequent closures.
CREATE TABLE public.cuenta_cierre_efectos (
 efecto_id UUID PRIMARY KEY,
 operacion_id UUID NOT NULL REFERENCES public.cuenta_cierre_operaciones(operacion_id) ON DELETE RESTRICT,
 cierre_referencia UUID NOT NULL,
 taller_id BIGINT NOT NULL,
 usuario_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE RESTRICT,
 tipo VARCHAR(32) NOT NULL CHECK(tipo IN ('CANCELAR_RENOVACION','AVISO_CIERRE','AVISO_RESTAURACION','REVISAR_RENOVACION')),
 link_id BIGINT REFERENCES public.subscription_provider_links(id) ON DELETE RESTRICT,
 expected_external_reference VARCHAR(255),
 expected_external_id VARCHAR(255),
 estado VARCHAR(16) NOT NULL CHECK(estado IN ('PENDIENTE','EN_CURSO','CONFIRMADO','INCIERTO')),
 intentos INTEGER NOT NULL DEFAULT 0 CHECK(intentos BETWEEN 0 AND 10),
 available_at TIMESTAMPTZ NOT NULL,
 lease_token UUID,
 lease_until TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL,
 confirmed_at TIMESTAMPTZ,
 CONSTRAINT fk_cierre_efecto_pertenencia FOREIGN KEY(cierre_referencia,taller_id)
   REFERENCES public.cuenta_cierres(referencia,taller_id) ON DELETE RESTRICT,
 CONSTRAINT ck_cierre_efecto_target CHECK(
  (tipo='CANCELAR_RENOVACION' AND link_id IS NOT NULL AND expected_external_reference IS NOT NULL
    AND length(btrim(expected_external_reference))>0 AND (expected_external_id IS NULL OR length(btrim(expected_external_id))>0))
  OR (tipo<>'CANCELAR_RENOVACION' AND link_id IS NULL AND expected_external_reference IS NULL AND expected_external_id IS NULL)),
 CONSTRAINT ck_cierre_efecto_lease CHECK((estado='EN_CURSO' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (estado<>'EN_CURSO' AND lease_token IS NULL AND lease_until IS NULL)),
 CONSTRAINT ck_cierre_efecto_confirmacion CHECK((estado='CONFIRMADO')=(confirmed_at IS NOT NULL)),
 CONSTRAINT ck_cierre_efecto_fechas CHECK(isfinite(created_at) AND isfinite(available_at)
    AND (lease_until IS NULL OR isfinite(lease_until)) AND (confirmed_at IS NULL OR isfinite(confirmed_at)))
);
CREATE UNIQUE INDEX uq_cierre_efecto_link ON public.cuenta_cierre_efectos(link_id) WHERE tipo='CANCELAR_RENOVACION';
CREATE UNIQUE INDEX uq_cierre_efecto_aviso ON public.cuenta_cierre_efectos(operacion_id,tipo) WHERE tipo<>'CANCELAR_RENOVACION';
CREATE INDEX ix_cierre_efecto_pendiente ON public.cuenta_cierre_efectos(available_at,created_at,efecto_id) WHERE estado IN ('PENDIENTE','EN_CURSO');
CREATE INDEX ix_cierre_efecto_taller ON public.cuenta_cierre_efectos(taller_id,tipo);
REVOKE ALL ON public.cuenta_cierre_efectos FROM PUBLIC;

CREATE FUNCTION public.cuenta_cierre_efecto_guard_v34() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
 IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'cuenta_cierre_efectos' OR TG_OP='DELETE'
 THEN RAISE EXCEPTION 'closure effect is durable' USING ERRCODE='23514'; END IF;
 PERFORM public.cuenta_cierre_estado_v33(NEW.taller_id);
 IF TG_OP='INSERT' AND NOT public.cuenta_cierre_exclusivo_v33(NEW.taller_id)
 THEN RAISE EXCEPTION 'closure effect requires exclusive transition' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.cuenta_cierre_operaciones o WHERE o.operacion_id=NEW.operacion_id
   AND o.taller_id=NEW.taller_id AND o.user_id=NEW.usuario_id AND o.cierre_referencia=NEW.cierre_referencia
   AND ((NEW.tipo='AVISO_RESTAURACION' AND o.proposito='RESTAURAR')
     OR (NEW.tipo<>'AVISO_RESTAURACION' AND o.proposito='CERRAR')))
 THEN RAISE EXCEPTION 'closure effect operation mismatch' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND
   (NEW.efecto_id,NEW.operacion_id,NEW.cierre_referencia,NEW.taller_id,NEW.usuario_id,NEW.tipo,NEW.link_id,NEW.expected_external_reference,NEW.created_at)
    IS DISTINCT FROM
   (OLD.efecto_id,OLD.operacion_id,OLD.cierre_referencia,OLD.taller_id,OLD.usuario_id,OLD.tipo,OLD.link_id,OLD.expected_external_reference,OLD.created_at)
 THEN RAISE EXCEPTION 'closure effect identity is immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND OLD.expected_external_id IS NOT NULL AND NEW.expected_external_id IS DISTINCT FROM OLD.expected_external_id
 THEN RAISE EXCEPTION 'closure effect remote identity is immutable' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.users u JOIN public.cuenta_cierres h ON h.taller_id=u.taller_id
    WHERE u.id=NEW.usuario_id AND u.taller_id=NEW.taller_id AND h.referencia=NEW.cierre_referencia AND h.titular_id=u.id)
 THEN RAISE EXCEPTION 'closure effect actor mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.tipo='CANCELAR_RENOVACION' AND NOT EXISTS(SELECT 1 FROM public.subscription_provider_links l
    JOIN public.suscripciones s ON s.id=l.suscripcion_id WHERE l.id=NEW.link_id AND s.taller_id=NEW.taller_id
      AND l.external_reference=NEW.expected_external_reference
      AND (NEW.expected_external_id IS NULL OR l.external_subscription_id=NEW.expected_external_id))
 THEN RAISE EXCEPTION 'closure effect target mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION public.cuenta_cierre_efecto_guard_v34() FROM PUBLIC;
CREATE TRIGGER aa_cuenta_cierre_efecto_v34 BEFORE INSERT OR UPDATE OR DELETE ON public.cuenta_cierre_efectos
 FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_efecto_guard_v34();

-- Existing PRO observations may be retained; a canceled-by-closure link cannot grant or renew access.
CREATE FUNCTION public.cuenta_cierre_renewal_guard_v34() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
 IF NEW.plan='PRO' AND (TG_OP='INSERT' OR (NEW.plan,NEW.estado,NEW.fecha_inicio) IS DISTINCT FROM (OLD.plan,OLD.estado,OLD.fecha_inicio))
  AND (EXISTS(SELECT 1 FROM public.subscription_provider_links l JOIN public.cuenta_cierre_efectos e ON e.link_id=l.id
    WHERE l.suscripcion_id=NEW.id AND l.is_current AND e.tipo='CANCELAR_RENOVACION')
    OR EXISTS(SELECT 1 FROM public.cuenta_cierre_efectos e WHERE e.taller_id=NEW.taller_id AND e.tipo='REVISAR_RENOVACION' AND e.estado='INCIERTO'))
 THEN RAISE EXCEPTION 'closure renewal is fenced' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION public.cuenta_cierre_renewal_guard_v34() FROM PUBLIC;
CREATE TRIGGER ab_cuenta_cierre_renewal_v34 BEFORE INSERT OR UPDATE ON public.suscripciones
 FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_renewal_guard_v34();

-- Direct SQL observations obey the same durable identity and late-ACK fence as the Java services.
CREATE FUNCTION public.cuenta_cierre_link_observation_v34() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
 IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'subscription_provider_links' OR TG_OP<>'UPDATE'
 THEN RAISE EXCEPTION 'closure link context invalid' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM public.cuenta_cierre_efectos e WHERE e.link_id=OLD.id AND e.tipo='CANCELAR_RENOVACION') THEN
   IF (NEW.id,NEW.suscripcion_id,NEW.provider,NEW.external_reference,NEW.idempotency_key)
      IS DISTINCT FROM (OLD.id,OLD.suscripcion_id,OLD.provider,OLD.external_reference,OLD.idempotency_key)
      OR (OLD.external_subscription_id IS NOT NULL AND NEW.external_subscription_id IS DISTINCT FROM OLD.external_subscription_id)
   THEN RAISE EXCEPTION 'closure link identity is immutable' USING ERRCODE='23514'; END IF;
   UPDATE public.cuenta_cierre_efectos SET
     expected_external_id=coalesce(expected_external_id,nullif(btrim(NEW.external_subscription_id),'')),
     estado=CASE WHEN (expected_external_id IS NOT NULL AND expected_external_id IS DISTINCT FROM NEW.external_subscription_id)
        OR (estado='CONFIRMADO' AND lower(btrim(coalesce(NEW.status,''))) NOT IN ('canceled','cancelled')) THEN 'INCIERTO' ELSE estado END,
     confirmed_at=CASE WHEN (expected_external_id IS NOT NULL AND expected_external_id IS DISTINCT FROM NEW.external_subscription_id)
        OR (estado='CONFIRMADO' AND lower(btrim(coalesce(NEW.status,''))) NOT IN ('canceled','cancelled')) THEN NULL ELSE confirmed_at END,
     lease_token=CASE WHEN expected_external_id IS NOT NULL AND expected_external_id IS DISTINCT FROM NEW.external_subscription_id THEN NULL ELSE lease_token END,
     lease_until=CASE WHEN expected_external_id IS NOT NULL AND expected_external_id IS DISTINCT FROM NEW.external_subscription_id THEN NULL ELSE lease_until END
   WHERE link_id=NEW.id AND tipo='CANCELAR_RENOVACION'
     AND ((expected_external_id IS NULL AND nullif(btrim(NEW.external_subscription_id),'') IS NOT NULL)
       OR (expected_external_id IS NOT NULL AND expected_external_id IS DISTINCT FROM NEW.external_subscription_id)
       OR ((OLD.status IS DISTINCT FROM NEW.status OR (OLD.updated_at IS DISTINCT FROM NEW.updated_at AND OLD.is_current=NEW.is_current)) AND estado='CONFIRMADO'
            AND lower(btrim(coalesce(NEW.status,''))) NOT IN ('canceled','cancelled')));
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION public.cuenta_cierre_link_observation_v34() FROM PUBLIC;
CREATE TRIGGER ac_cuenta_cierre_link_v34 AFTER UPDATE ON public.subscription_provider_links
 FOR EACH ROW EXECUTE FUNCTION public.cuenta_cierre_link_observation_v34();
