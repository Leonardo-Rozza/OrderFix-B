-- V29: resultados idempotentes sin actos nuevos y locks de identidad de cuenta.
-- Es aditiva: no cambia funciones ni filas historicas de V27/V28.
-- El advisory de actor es un protocolo de los consumidores, no una identidad HTTP de este rol.

CREATE TABLE legal_idempotencia_sin_actos (
    id UUID NOT NULL,
    operacion VARCHAR(30) NOT NULL,
    route_template VARCHAR(200) NOT NULL,
    scope_hmac VARCHAR(64) NOT NULL,
    idempotency_key_hmac VARCHAR(64) NOT NULL,
    fingerprint_hmac VARCHAR(64) NOT NULL,
    hmac_key_version INTEGER NOT NULL,
    user_id BIGINT NOT NULL,
    taller_id BIGINT NOT NULL,
    rol_wire VARCHAR(10) NOT NULL,
    audiencia VARCHAR(20) NOT NULL,
    resultado VARCHAR(10) NOT NULL,
    submitted_revision VARCHAR(71) NOT NULL,
    agregado_observado_id UUID NOT NULL,
    perfil VARCHAR(30) NOT NULL,
    revision_scheme VARCHAR(20) NOT NULL,
    observed_revision VARCHAR(71) NOT NULL,
    referencia_count INTEGER NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_idempotencia_sin_actos PRIMARY KEY (id),
    CONSTRAINT uk_legal_idem_sin_actos_actor UNIQUE (id, user_id, taller_id),
    CONSTRAINT uk_legal_idem_sin_actos_tupla
        UNIQUE (operacion, route_template, scope_hmac, idempotency_key_hmac),
    CONSTRAINT fk_legal_idem_sin_actos_actor FOREIGN KEY (user_id, taller_id)
        REFERENCES users (id, taller_id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_idem_sin_actos_agregado FOREIGN KEY
        (agregado_observado_id, perfil, audiencia, revision_scheme, observed_revision)
        REFERENCES legal_requisito_agregados
        (id, perfil, audiencia, revision_scheme, required_set_revision) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_idem_sin_actos_operacion CHECK
        (operacion = 'ACEPTACION_LEGAL'
         AND route_template = '/api/aceptaciones-legales'),
    CONSTRAINT ck_legal_idem_sin_actos_rol CHECK
        ((rol_wire = 'ADMIN' AND audiencia = 'ADMIN_TITULAR')
         OR (rol_wire = 'USER' AND audiencia = 'USER')),
    CONSTRAINT ck_legal_idem_sin_actos_perfil CHECK
        (perfil = 'AUTHENTICATED_PENDING' AND revision_scheme = 'AGGREGATE_V1'),
    CONSTRAINT ck_legal_idem_sin_actos_hmac CHECK
        (scope_hmac ~ '^[0-9a-f]{64}$'
         AND idempotency_key_hmac ~ '^[0-9a-f]{64}$'
         AND fingerprint_hmac ~ '^[0-9a-f]{64}$' AND hmac_key_version > 0),
    CONSTRAINT ck_legal_idem_sin_actos_revision CHECK
        (submitted_revision ~ '^sha256:[0-9a-f]{64}$'
         AND observed_revision ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_legal_idem_sin_actos_shape CHECK
        ((resultado = 'EMPTY' AND referencia_count = 0
          AND submitted_revision = observed_revision)
         OR (resultado = 'DEDUP' AND referencia_count BETWEEN 1 AND 2048)),
    CONSTRAINT ck_legal_idem_sin_actos_expiracion CHECK
        (expires_at >= completed_at + INTERVAL '24 hours')
);

CREATE TABLE legal_idempotencia_sin_actos_referencias (
    resultado_id UUID NOT NULL,
    aceptacion_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    taller_id BIGINT NOT NULL,
    CONSTRAINT pk_legal_idem_sin_actos_ref PRIMARY KEY (resultado_id, aceptacion_id),
    CONSTRAINT fk_legal_idem_sin_actos_ref_padre
        FOREIGN KEY (resultado_id, user_id, taller_id)
        REFERENCES legal_idempotencia_sin_actos (id, user_id, taller_id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_idem_sin_actos_ref_aceptacion
        FOREIGN KEY (aceptacion_id, user_id, taller_id)
        REFERENCES legal_aceptaciones (id, user_id, taller_id) ON DELETE RESTRICT
);

CREATE INDEX idx_legal_idem_sin_actos_expira
    ON legal_idempotencia_sin_actos (expires_at, id);
CREATE INDEX idx_legal_idem_sin_actos_version
    ON legal_idempotencia_sin_actos (hmac_key_version);
CREATE INDEX idx_legal_idem_sin_actos_ref_actor
    ON legal_idempotencia_sin_actos_referencias (aceptacion_id, user_id, taller_id);

-- ------------------------------------------------------------
-- Tupla global: la misma reserva protege ambos ledgers, incluso filas vencidas.
-- El consumidor adquiere el lock en una sentencia previa, antes del lookup y del DML.
-- El preflight de rol prohibe adquirir locks de sesion; pg_locks acredita PID/base/key/modo.
-- ------------------------------------------------------------

CREATE FUNCTION legal_exigir_lock_idempotente_v29(
    p_operacion VARCHAR,
    p_route_template VARCHAR,
    p_scope_hmac VARCHAR,
    p_idempotency_key_hmac VARCHAR)
RETURNS VOID
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
AS $$
DECLARE
    v_lock_key BIGINT;
BEGIN
    PERFORM legal_exigir_read_committed();
    IF p_operacion IS NULL OR p_route_template IS NULL
       OR p_scope_hmac IS NULL OR p_idempotency_key_hmac IS NULL THEN
        RAISE EXCEPTION 'la tupla idempotente debe estar completa'
            USING ERRCODE = '23514';
    END IF;

    v_lock_key := pg_catalog.hashtextextended(
        pg_catalog.jsonb_build_array(
            'ordenfix:legal-idempotencia:tupla:v29',
            p_operacion, p_route_template, p_scope_hmac, p_idempotency_key_hmac
        )::TEXT,
        0
    );
    IF NOT EXISTS (
        SELECT 1
          FROM pg_catalog.pg_locks held
         WHERE held.locktype = 'advisory'
           AND held.pid = pg_catalog.pg_backend_pid()
           AND held.database = (
               SELECT database.oid
                 FROM pg_catalog.pg_database database
                WHERE database.datname = pg_catalog.current_database()
           )
           AND held.granted
           AND held.objsubid = 1
           AND held.mode = 'ExclusiveLock'
           AND held.classid::BIGINT = ((v_lock_key >> 32) & 4294967295::BIGINT)
           AND held.objid::BIGINT = (v_lock_key & 4294967295::BIGINT)
    ) THEN
        RAISE EXCEPTION 'la operacion requiere el lock idempotente exclusivo acreditado'
            USING ERRCODE = '55000';
    END IF;
END;
$$;

CREATE FUNCTION legal_idempotencia_tupla_guard_v29()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
AS $$
BEGIN
    IF TG_TABLE_NAME NOT IN (
        'legal_idempotencia_resultados', 'legal_idempotencia_sin_actos'
    ) THEN
        RAISE EXCEPTION 'la guarda de tupla no corresponde a esta relacion'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'DELETE' THEN
        PERFORM legal_exigir_lock_idempotente_v29(
            OLD.operacion, OLD.route_template, OLD.scope_hmac, OLD.idempotency_key_hmac);
        IF OLD.expires_at > transaction_timestamp() THEN
            RAISE EXCEPTION 'un resultado idempotente no puede purgarse antes de expires_at'
                USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'operacion no admitida por la guarda de tupla'
            USING ERRCODE = '23514';
    END IF;
    PERFORM legal_exigir_lock_idempotente_v29(
        NEW.operacion, NEW.route_template, NEW.scope_hmac, NEW.idempotency_key_hmac);

    IF TG_TABLE_NAME = 'legal_idempotencia_resultados' THEN
        IF EXISTS (
            SELECT 1
              FROM legal_idempotencia_sin_actos result
             WHERE result.operacion = NEW.operacion
               AND result.route_template = NEW.route_template
               AND result.scope_hmac = NEW.scope_hmac
               AND result.idempotency_key_hmac = NEW.idempotency_key_hmac
        ) THEN
            RAISE EXCEPTION 'la tupla idempotente ya tiene un resultado confirmado'
                USING ERRCODE = '23505', CONSTRAINT = 'uk_legal_idempotencia_tupla_v29';
        END IF;
    ELSE
        IF EXISTS (
            SELECT 1
              FROM legal_idempotencia_resultados result
             WHERE result.operacion = NEW.operacion
               AND result.route_template = NEW.route_template
               AND result.scope_hmac = NEW.scope_hmac
               AND result.idempotency_key_hmac = NEW.idempotency_key_hmac
        ) THEN
            RAISE EXCEPTION 'la tupla idempotente ya tiene un resultado confirmado'
                USING ERRCODE = '23505', CONSTRAINT = 'uk_legal_idempotencia_tupla_v29';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- ------------------------------------------------------------
-- Construccion atomica del resultado sin nuevos actos.
-- ------------------------------------------------------------

CREATE FUNCTION legal_idempotencia_sin_actos_insert_guard_v29()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
AS $$
DECLARE
    v_taller_activo BOOLEAN;
    v_user_activo BOOLEAN;
    v_rol VARCHAR(255);
BEGIN
    PERFORM legal_exigir_lock_editorial_v28();

    -- El orden coincide con el consumidor: primero taller, despues usuario.
    SELECT taller.activo INTO v_taller_activo
      FROM talleres taller
     WHERE taller.id = NEW.taller_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'taller inexistente para resultado idempotente'
            USING ERRCODE = '23503';
    END IF;

    SELECT actor.role, actor.active INTO v_rol, v_user_activo
      FROM users actor
     WHERE actor.id = NEW.user_id AND actor.taller_id = NEW.taller_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'actor/taller inexistente para resultado idempotente'
            USING ERRCODE = '23503';
    END IF;
    IF v_rol IS DISTINCT FROM NEW.rol_wire
       OR v_user_activo IS DISTINCT FROM TRUE
       OR v_taller_activo IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'actor, rol o taller no habilitado para resultado idempotente'
            USING ERRCODE = '23514';
    END IF;

    PERFORM legal_validar_requisito_agregado_actual(NEW.agregado_observado_id);
    NEW.completed_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_idempotencia_sin_actos_ref_insert_guard_v29()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
AS $$
DECLARE
    v_padre RECORD;
    v_aceptacion_xmin XID;
    v_lote_xmin XID;
BEGIN
    SELECT parent.resultado, parent.user_id, parent.taller_id, parent.xmin AS row_xmin
      INTO v_padre
      FROM legal_idempotencia_sin_actos parent
     WHERE parent.id = NEW.resultado_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'resultado idempotente inexistente para referencia'
            USING ERRCODE = '23503';
    END IF;
    IF v_padre.resultado <> 'DEDUP'
       OR v_padre.user_id <> NEW.user_id
       OR v_padre.taller_id <> NEW.taller_id
       OR NOT legal_fila_es_transaccion_actual(v_padre.row_xmin) THEN
        RAISE EXCEPTION 'una referencia requiere resultado DEDUP nuevo del mismo actor'
            USING ERRCODE = '23514';
    END IF;

    SELECT acceptance.xmin, lote.xmin INTO v_aceptacion_xmin, v_lote_xmin
      FROM legal_aceptaciones acceptance
      JOIN legal_aceptacion_lotes lote
        ON lote.id = acceptance.lote_id
       AND lote.user_id = acceptance.user_id
       AND lote.taller_id = acceptance.taller_id
     WHERE acceptance.id = NEW.aceptacion_id
       AND acceptance.user_id = NEW.user_id
       AND acceptance.taller_id = NEW.taller_id
     FOR SHARE OF lote, acceptance;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'aceptacion inexistente o ajena para referencia idempotente'
            USING ERRCODE = '23503';
    END IF;
    -- V28 exige que el lote de cualquier acto nuevo tenga el xid superior actual.
    -- Comprobar tambien ese padre evita tratar un acto creado en SAVEPOINT como historico.
    IF legal_fila_es_transaccion_actual(v_aceptacion_xmin)
       OR legal_fila_es_transaccion_actual(v_lote_xmin) THEN
        RAISE EXCEPTION 'DEDUP requiere evidencia confirmada en una transaccion anterior'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_validar_idempotencia_sin_actos_v29(p_resultado_id UUID)
RETURNS VOID
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
AS $$
DECLARE
    v_padre RECORD;
    v_referencias BIGINT;
BEGIN
    SELECT parent.resultado, parent.referencia_count, parent.agregado_observado_id,
           parent.xmin AS row_xmin
      INTO v_padre
      FROM legal_idempotencia_sin_actos parent
     WHERE parent.id = p_resultado_id;
    IF NOT FOUND THEN
        -- Una purga confirmada elimina referencias y padre; nunca evidencia contractual.
        RETURN;
    END IF;

    SELECT count(*) INTO v_referencias
      FROM legal_idempotencia_sin_actos_referencias member
     WHERE member.resultado_id = p_resultado_id;
    IF v_referencias <> v_padre.referencia_count
       OR (v_padre.resultado = 'EMPTY' AND v_referencias <> 0)
       OR (v_padre.resultado = 'DEDUP' AND v_referencias NOT BETWEEN 1 AND 2048)
       OR v_padre.resultado NOT IN ('EMPTY', 'DEDUP') THEN
        RAISE EXCEPTION 'el resultado idempotente no conserva sus referencias completas'
            USING ERRCODE = '23514';
    END IF;

    IF legal_fila_es_transaccion_actual(v_padre.row_xmin) THEN
        -- Solo una observacion NUEVA debe seguir vigente al confirmar. Las referencias de un
        -- resultado historico no se reinterpretan ni bloquean su purga tras REPLACE/RETIRE.
        PERFORM legal_validar_requisito_agregado_actual(v_padre.agregado_observado_id);
    END IF;
END;
$$;

CREATE FUNCTION legal_idempotencia_sin_actos_constraint_guard_v29()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
AS $$
DECLARE
    v_resultado_id UUID;
    v_agregado_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'legal_idempotencia_sin_actos' AND TG_OP = 'INSERT' THEN
        v_resultado_id := NEW.id;
    ELSIF TG_TABLE_NAME = 'legal_idempotencia_sin_actos_referencias' THEN
        IF TG_OP = 'DELETE' THEN
            v_resultado_id := OLD.resultado_id;
        ELSIF TG_OP = 'INSERT' THEN
            v_resultado_id := NEW.resultado_id;
        ELSE
            RAISE EXCEPTION 'operacion no admitida por la guarda de completitud'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'relacion no admitida por la guarda de completitud'
            USING ERRCODE = '23514';
    END IF;
    PERFORM legal_validar_idempotencia_sin_actos_v29(v_resultado_id);
    IF TG_TABLE_NAME = 'legal_idempotencia_sin_actos' AND TG_OP = 'INSERT' THEN
        -- El evento INSERT prueba que la cabecera es nueva incluso cuando xmin es un subxid.
        -- DELETE historico no exige que el agregado observado siga siendo el actual.
        SELECT agregado_observado_id INTO v_agregado_id
          FROM legal_idempotencia_sin_actos
         WHERE id = v_resultado_id;
        IF FOUND THEN
            PERFORM legal_validar_requisito_agregado_actual(v_agregado_id);
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

-- ------------------------------------------------------------
-- Inmutabilidad y purga vencida, bajo la misma reserva que el replay.
-- ------------------------------------------------------------

CREATE FUNCTION legal_idempotencia_sin_actos_mutation_guard_v29()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'un resultado idempotente confirmado es inmutable'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        RAISE EXCEPTION 'operacion no admitida por la guarda de mutacion'
            USING ERRCODE = '23514';
    END IF;
    PERFORM legal_exigir_lock_idempotente_v29(
        OLD.operacion, OLD.route_template, OLD.scope_hmac, OLD.idempotency_key_hmac);
    IF OLD.expires_at > transaction_timestamp() THEN
        RAISE EXCEPTION 'un resultado idempotente no puede purgarse antes de expires_at'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;

CREATE FUNCTION legal_idempotencia_sin_actos_ref_delete_guard_v29()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
AS $$
DECLARE
    v_padre RECORD;
BEGIN
    SELECT parent.operacion, parent.route_template, parent.scope_hmac,
           parent.idempotency_key_hmac, parent.expires_at
      INTO v_padre
      FROM legal_idempotencia_sin_actos parent
     WHERE parent.id = OLD.resultado_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'la referencia requiere su padre durante la purga'
            USING ERRCODE = '23503';
    END IF;
    PERFORM legal_exigir_lock_idempotente_v29(
        v_padre.operacion, v_padre.route_template, v_padre.scope_hmac, v_padre.idempotency_key_hmac);
    IF v_padre.expires_at > transaction_timestamp() THEN
        RAISE EXCEPTION 'una referencia idempotente no puede purgarse antes de expires_at'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;

CREATE FUNCTION legal_idempotencia_sin_actos_ref_update_guard_v29()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
AS $$
BEGIN
    RAISE EXCEPTION 'las referencias idempotentes son inmutables'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION legal_rechazar_update_identidad_cuenta_v29()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
AS $$
BEGIN
    RAISE EXCEPTION 'la identidad de cuenta es inmutable'
        USING ERRCODE = '23514';
END;
$$;

-- Prefijo 00: la reserva global se acredita antes de las guardas historicas del ledger V27.
CREATE TRIGGER trg_legal_00_idempotencia_tupla_v29
    BEFORE INSERT OR DELETE ON legal_idempotencia_resultados
    FOR EACH ROW EXECUTE FUNCTION legal_idempotencia_tupla_guard_v29();
CREATE TRIGGER trg_legal_00_idempotencia_tupla_v29
    BEFORE INSERT OR DELETE ON legal_idempotencia_sin_actos
    FOR EACH ROW EXECUTE FUNCTION legal_idempotencia_tupla_guard_v29();
CREATE TRIGGER trg_legal_10_idem_sin_actos_insert_v29
    BEFORE INSERT ON legal_idempotencia_sin_actos
    FOR EACH ROW EXECUTE FUNCTION legal_idempotencia_sin_actos_insert_guard_v29();
CREATE TRIGGER trg_legal_20_idem_sin_actos_mutation_v29
    BEFORE UPDATE OR DELETE ON legal_idempotencia_sin_actos
    FOR EACH ROW EXECUTE FUNCTION legal_idempotencia_sin_actos_mutation_guard_v29();
CREATE CONSTRAINT TRIGGER ct_legal_idem_sin_actos_completo_v29
    AFTER INSERT ON legal_idempotencia_sin_actos
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_idempotencia_sin_actos_constraint_guard_v29();

CREATE TRIGGER trg_legal_idem_sin_actos_ref_insert_v29
    BEFORE INSERT ON legal_idempotencia_sin_actos_referencias
    FOR EACH ROW EXECUTE FUNCTION legal_idempotencia_sin_actos_ref_insert_guard_v29();
CREATE TRIGGER trg_legal_idem_sin_actos_ref_update_v29
    BEFORE UPDATE ON legal_idempotencia_sin_actos_referencias
    FOR EACH STATEMENT EXECUTE FUNCTION legal_idempotencia_sin_actos_ref_update_guard_v29();
CREATE TRIGGER trg_legal_idem_sin_actos_ref_delete_v29
    BEFORE DELETE ON legal_idempotencia_sin_actos_referencias
    FOR EACH ROW EXECUTE FUNCTION legal_idempotencia_sin_actos_ref_delete_guard_v29();
CREATE CONSTRAINT TRIGGER ct_legal_idem_sin_actos_ref_completa_v29
    AFTER INSERT OR DELETE ON legal_idempotencia_sin_actos_referencias
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_idempotencia_sin_actos_constraint_guard_v29();

CREATE TRIGGER trg_legal_users_identidad_inmutable_v29
    BEFORE UPDATE OF id ON users
    FOR EACH STATEMENT EXECUTE FUNCTION legal_rechazar_update_identidad_cuenta_v29();
CREATE TRIGGER trg_legal_talleres_identidad_inmutable_v29
    BEFORE UPDATE OF id ON talleres
    FOR EACH STATEMENT EXECUTE FUNCTION legal_rechazar_update_identidad_cuenta_v29();

-- Fijar la resolucion de objetos al schema efectivo de la migracion, no al search_path del caller.
-- No se provisionan roles/grants de entornos compartidos ni se concede ownership al consumidor.
DO $$
DECLARE
    v_schema TEXT := current_schema();
    v_function TEXT;
BEGIN
    FOREACH v_function IN ARRAY ARRAY[
        'legal_exigir_lock_idempotente_v29(character varying,character varying,character varying,character varying)',
        'legal_idempotencia_tupla_guard_v29()',
        'legal_idempotencia_sin_actos_insert_guard_v29()',
        'legal_idempotencia_sin_actos_ref_insert_guard_v29()',
        'legal_validar_idempotencia_sin_actos_v29(uuid)',
        'legal_idempotencia_sin_actos_constraint_guard_v29()',
        'legal_idempotencia_sin_actos_mutation_guard_v29()',
        'legal_idempotencia_sin_actos_ref_delete_guard_v29()',
        'legal_idempotencia_sin_actos_ref_update_guard_v29()',
        'legal_rechazar_update_identidad_cuenta_v29()'
    ] LOOP
        EXECUTE format(
            'ALTER FUNCTION %I.%s SET search_path TO pg_catalog, %I, pg_temp',
            v_schema, v_function, v_schema);
        EXECUTE format('REVOKE ALL ON FUNCTION %I.%s FROM PUBLIC', v_schema, v_function);
    END LOOP;
END;
$$;
