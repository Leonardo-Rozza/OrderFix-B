-- V28: revision legal agregada multicontexto, append-only y compatible con historia V27.
--
-- Esta migracion es aditiva. No reinterpreta ni actualiza evidencia SCOPE_V1 existente.
-- Los agregados nuevos requieren que la sesion ya posea el advisory lock editorial V27.

-- ------------------------------------------------------------
-- Cabecera semantica y procedencia fisica exacta
-- ------------------------------------------------------------

CREATE TABLE legal_requisito_agregados (
    id                       UUID                     NOT NULL,
    perfil                   VARCHAR(30)              NOT NULL,
    locale                   VARCHAR(5)               NOT NULL,
    audiencia                VARCHAR(20)              NOT NULL,
    revision_scheme          VARCHAR(20)              NOT NULL,
    required_set_revision    VARCHAR(71)              NOT NULL,
    provenance_fingerprint   VARCHAR(71)              NOT NULL,
    scope_count              INTEGER                  NOT NULL,
    creado_en                TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_requisito_agregados PRIMARY KEY (id),
    CONSTRAINT uk_legal_requisito_agregado_identidad_fisica UNIQUE
        (perfil, locale, audiencia, revision_scheme,
         required_set_revision, provenance_fingerprint),
    CONSTRAINT uk_legal_requisito_agregado_scope_fk UNIQUE
        (id, locale, audiencia),
    CONSTRAINT uk_legal_requisito_agregado_lote_fk UNIQUE
        (id, perfil, audiencia, revision_scheme, required_set_revision),
    CONSTRAINT ck_legal_requisito_agregado_perfil CHECK (
        perfil IN ('REGISTRATION', 'AUTHENTICATED_PENDING')
    ),
    CONSTRAINT ck_legal_requisito_agregado_registro_audiencia CHECK (
        perfil <> 'REGISTRATION' OR audiencia = 'ADMIN_TITULAR'
    ),
    CONSTRAINT ck_legal_requisito_agregado_locale CHECK (locale IN ('es-AR')),
    CONSTRAINT ck_legal_requisito_agregado_audiencia CHECK (
        audiencia IN ('ADMIN_TITULAR', 'USER')
    ),
    CONSTRAINT ck_legal_requisito_agregado_scheme CHECK (
        revision_scheme = 'AGGREGATE_V1'
    ),
    CONSTRAINT ck_legal_requisito_agregado_revision CHECK (
        required_set_revision ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_legal_requisito_agregado_procedencia CHECK (
        provenance_fingerprint ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_legal_requisito_agregado_scope_count CHECK (
        scope_count BETWEEN 1 AND 8
    )
);

CREATE TABLE legal_requisito_agregado_scopes (
    agregado_id              UUID        NOT NULL,
    scope_ordinal            INTEGER     NOT NULL,
    contexto                 VARCHAR(40) NOT NULL,
    conjunto_id              UUID        NOT NULL,
    publicacion_id           UUID        NOT NULL,
    locale                   VARCHAR(5)  NOT NULL,
    audiencia                VARCHAR(20) NOT NULL,
    required_set_revision    VARCHAR(71) NOT NULL,
    CONSTRAINT pk_legal_requisito_agregado_scopes
        PRIMARY KEY (agregado_id, scope_ordinal),
    CONSTRAINT uk_legal_requisito_agregado_scope_contexto
        UNIQUE (agregado_id, contexto),
    CONSTRAINT fk_legal_requisito_agregado_scope_cabecera
        FOREIGN KEY (agregado_id, locale, audiencia)
        REFERENCES legal_requisito_agregados (id, locale, audiencia)
        ON DELETE RESTRICT,
    CONSTRAINT fk_legal_requisito_agregado_scope_snapshot
        FOREIGN KEY (conjunto_id, publicacion_id, locale, contexto, audiencia)
        REFERENCES legal_requisito_conjuntos
            (id, publicacion_id, locale, contexto, audiencia)
        ON DELETE RESTRICT,
    CONSTRAINT ck_legal_requisito_agregado_scope_ordinal CHECK (
        scope_ordinal BETWEEN 1 AND 8
    ),
    CONSTRAINT ck_legal_requisito_agregado_scope_contexto CHECK (contexto IN (
        'REGISTRO', 'PRIMER_INGRESO_EMPLEADO', 'USO_CONTINUADO', 'CONTRATACION_PRO',
        'ATESTACION_FOTOS', 'ATESTACION_CREDENCIALES', 'CIERRE_CUENTA', 'ARREPENTIMIENTO'
    )),
    CONSTRAINT ck_legal_requisito_agregado_scope_locale CHECK (locale IN ('es-AR')),
    CONSTRAINT ck_legal_requisito_agregado_scope_audiencia CHECK (
        audiencia IN ('ADMIN_TITULAR', 'USER')
    ),
    CONSTRAINT ck_legal_requisito_agregado_scope_revision CHECK (
        required_set_revision ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE INDEX idx_legal_requisito_agregados_revision
    ON legal_requisito_agregados
        (locale, audiencia, required_set_revision, perfil);
CREATE INDEX idx_legal_requisito_agregado_scopes_snapshot
    ON legal_requisito_agregado_scopes
        (conjunto_id, publicacion_id, locale, contexto, audiencia);

-- ------------------------------------------------------------
-- Lock, causalidad e integridad del vector materializado
-- ------------------------------------------------------------

CREATE FUNCTION legal_exigir_lock_editorial_v28()
RETURNS VOID
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_lock_key BIGINT := pg_catalog.hashtextextended(
        'ordenfix:legal-publicaciones:sello:v1', 0
    );
BEGIN
    PERFORM legal_exigir_read_committed();
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
           AND held.mode IN ('ShareLock', 'ExclusiveLock')
           AND held.classid::BIGINT = ((v_lock_key >> 32) & 4294967295::BIGINT)
           AND held.objid::BIGINT = (v_lock_key & 4294967295::BIGINT)
    ) THEN
        RAISE EXCEPTION 'la operacion agregada requiere el lock editorial acreditado'
            USING ERRCODE = '55000';
    END IF;
END;
$$;

CREATE FUNCTION legal_requisito_agregado_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    PERFORM legal_exigir_lock_editorial_v28();
    NEW.creado_en := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_requisito_agregado_scope_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_revision VARCHAR(71);
BEGIN
    PERFORM legal_exigir_lock_editorial_v28();

    SELECT conjunto.required_set_revision
      INTO v_revision
      FROM legal_requisito_conjuntos_actuales actual
      JOIN legal_requisito_conjuntos conjunto
        ON conjunto.id = actual.conjunto_id
       AND conjunto.publicacion_id = actual.publicacion_id
       AND conjunto.locale = actual.locale
       AND conjunto.contexto = actual.contexto
       AND conjunto.audiencia = actual.audiencia
     WHERE actual.locale = NEW.locale
       AND actual.contexto = NEW.contexto
       AND actual.audiencia = NEW.audiencia
       AND actual.conjunto_id = NEW.conjunto_id
       AND actual.publicacion_id = NEW.publicacion_id
     FOR SHARE OF actual;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'el scope agregado no coincide con el puntero V27 actual'
            USING ERRCODE = '23514';
    END IF;
    IF v_revision <> NEW.required_set_revision THEN
        RAISE EXCEPTION 'la revision componente no coincide con el snapshot V27'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_validar_requisito_agregado(p_agregado_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_header legal_requisito_agregados%ROWTYPE;
    v_count  INTEGER;
BEGIN
    -- Se vuelve a acreditar en el constraint diferido. Un lock de sesion liberado
    -- antes del COMMIT no puede convertir una escritura parcial/stale en exito.
    PERFORM legal_exigir_lock_editorial_v28();

    SELECT * INTO v_header
      FROM legal_requisito_agregados
     WHERE id = p_agregado_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT count(*)::INTEGER INTO v_count
      FROM legal_requisito_agregado_scopes
     WHERE agregado_id = p_agregado_id;
    IF v_count <> v_header.scope_count THEN
        RAISE EXCEPTION 'el agregado % posee una cardinalidad parcial', p_agregado_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM (
              SELECT scope.scope_ordinal,
                     row_number() OVER (
                         ORDER BY CASE scope.contexto
                             WHEN 'REGISTRO' THEN 1
                             WHEN 'PRIMER_INGRESO_EMPLEADO' THEN 2
                             WHEN 'USO_CONTINUADO' THEN 3
                             WHEN 'CONTRATACION_PRO' THEN 4
                             WHEN 'ATESTACION_FOTOS' THEN 5
                             WHEN 'ATESTACION_CREDENCIALES' THEN 6
                             WHEN 'CIERRE_CUENTA' THEN 7
                             WHEN 'ARREPENTIMIENTO' THEN 8
                         END
                     ) AS canonical_ordinal
                FROM legal_requisito_agregado_scopes scope
               WHERE scope.agregado_id = p_agregado_id
          ) ordered_scopes
         WHERE ordered_scopes.scope_ordinal <> ordered_scopes.canonical_ordinal
    ) THEN
        RAISE EXCEPTION 'el agregado % no respeta el orden canonico', p_agregado_id
            USING ERRCODE = '23514';
    END IF;

    IF v_header.perfil = 'REGISTRATION' AND (
        v_header.audiencia <> 'ADMIN_TITULAR'
        OR v_header.scope_count <> 1
        OR NOT EXISTS (
            SELECT 1 FROM legal_requisito_agregado_scopes scope
             WHERE scope.agregado_id = p_agregado_id
               AND scope.contexto = 'REGISTRO'
        )
    ) THEN
        RAISE EXCEPTION 'REGISTRATION requiere exclusivamente REGISTRO/ADMIN_TITULAR'
            USING ERRCODE = '23514';
    END IF;

    IF v_header.perfil = 'AUTHENTICATED_PENDING' AND NOT EXISTS (
        SELECT 1 FROM legal_requisito_agregado_scopes scope
         WHERE scope.agregado_id = p_agregado_id
           AND scope.contexto = 'USO_CONTINUADO'
    ) THEN
        RAISE EXCEPTION 'AUTHENTICATED_PENDING requiere USO_CONTINUADO'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION legal_requisito_agregado_constraint_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    IF TG_TABLE_NAME = 'legal_requisito_agregados' THEN
        PERFORM legal_validar_requisito_agregado(NEW.id);
    ELSE
        PERFORM legal_validar_requisito_agregado(NEW.agregado_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION legal_validar_requisito_agregado_actual(p_agregado_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_expected INTEGER;
    v_observed INTEGER;
BEGIN
    PERFORM legal_exigir_lock_editorial_v28();
    PERFORM legal_validar_requisito_agregado(p_agregado_id);

    SELECT scope_count INTO v_expected
      FROM legal_requisito_agregados
     WHERE id = p_agregado_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agregado legal inexistente: %', p_agregado_id
            USING ERRCODE = '23503';
    END IF;

    PERFORM 1
      FROM legal_requisito_agregado_scopes scope
      JOIN legal_requisito_conjuntos_actuales actual
        ON actual.locale = scope.locale
       AND actual.contexto = scope.contexto
       AND actual.audiencia = scope.audiencia
       AND actual.conjunto_id = scope.conjunto_id
       AND actual.publicacion_id = scope.publicacion_id
      JOIN legal_requisito_conjuntos conjunto
        ON conjunto.id = scope.conjunto_id
       AND conjunto.publicacion_id = scope.publicacion_id
       AND conjunto.locale = scope.locale
       AND conjunto.contexto = scope.contexto
       AND conjunto.audiencia = scope.audiencia
       AND conjunto.required_set_revision = scope.required_set_revision
     WHERE scope.agregado_id = p_agregado_id
     ORDER BY scope.scope_ordinal
     FOR SHARE OF actual;
    GET DIAGNOSTICS v_observed = ROW_COUNT;

    IF v_observed <> v_expected THEN
        RAISE EXCEPTION 'el agregado legal ya no coincide con los punteros actuales'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE TRIGGER trg_legal_requisito_agregado_insert
    BEFORE INSERT ON legal_requisito_agregados
    FOR EACH ROW EXECUTE FUNCTION legal_requisito_agregado_insert_guard();
CREATE TRIGGER trg_legal_requisito_agregado_inmutable
    BEFORE UPDATE OR DELETE ON legal_requisito_agregados
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE CONSTRAINT TRIGGER ct_legal_requisito_agregado_completo
    AFTER INSERT ON legal_requisito_agregados
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_requisito_agregado_constraint_guard();

CREATE TRIGGER trg_legal_requisito_agregado_scope_insert
    BEFORE INSERT ON legal_requisito_agregado_scopes
    FOR EACH ROW EXECUTE FUNCTION legal_requisito_agregado_scope_insert_guard();
CREATE TRIGGER trg_legal_requisito_agregado_scope_inmutable
    BEFORE UPDATE OR DELETE ON legal_requisito_agregado_scopes
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE CONSTRAINT TRIGGER ct_legal_requisito_agregado_scope_completo
    AFTER INSERT ON legal_requisito_agregado_scopes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_requisito_agregado_constraint_guard();

-- ------------------------------------------------------------
-- Compatibilidad historica SCOPE_V1 y lotes nuevos AGGREGATE_V1
-- ------------------------------------------------------------

ALTER TABLE legal_aceptacion_lotes
    ADD COLUMN revision_scheme VARCHAR(20) NOT NULL DEFAULT 'SCOPE_V1',
    ADD COLUMN perfil VARCHAR(30),
    ADD COLUMN agregado_id UUID;

ALTER TABLE legal_aceptacion_lotes
    ADD CONSTRAINT ck_legal_aceptacion_lote_scheme CHECK (
        revision_scheme IN ('SCOPE_V1', 'AGGREGATE_V1')
    ),
    ADD CONSTRAINT ck_legal_aceptacion_lote_perfil CHECK (
        perfil IS NULL OR perfil IN ('REGISTRATION', 'AUTHENTICATED_PENDING')
    ),
    ADD CONSTRAINT ck_legal_aceptacion_lote_revision_shape CHECK (
        (revision_scheme = 'SCOPE_V1' AND perfil IS NULL AND agregado_id IS NULL)
        OR
        (revision_scheme = 'AGGREGATE_V1' AND perfil IS NOT NULL AND agregado_id IS NOT NULL)
    ),
    ADD CONSTRAINT fk_legal_aceptacion_lote_agregado
        FOREIGN KEY (agregado_id, perfil, audiencia, revision_scheme, required_set_revision)
        REFERENCES legal_requisito_agregados
            (id, perfil, audiencia, revision_scheme, required_set_revision)
        ON DELETE RESTRICT;

CREATE INDEX idx_legal_aceptacion_lotes_agregado
    ON legal_aceptacion_lotes (agregado_id)
    WHERE agregado_id IS NOT NULL;

CREATE OR REPLACE FUNCTION legal_aceptacion_lote_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_rol      VARCHAR(255);
    v_perfil   VARCHAR(30);
    v_audiencia VARCHAR(20);
    v_scheme   VARCHAR(20);
    v_revision VARCHAR(71);
BEGIN
    IF NEW.revision_scheme <> 'AGGREGATE_V1'
       OR NEW.perfil IS NULL
       OR NEW.agregado_id IS NULL THEN
        RAISE EXCEPTION 'despues de V28 todo lote nuevo debe usar AGGREGATE_V1'
            USING ERRCODE = '23514';
    END IF;

    PERFORM legal_exigir_lock_editorial_v28();
    NEW.aceptado_en := statement_timestamp();

    SELECT role INTO v_rol FROM users
     WHERE id = NEW.user_id AND taller_id = NEW.taller_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'actor/taller inexistente para lote legal'
            USING ERRCODE = '23503';
    END IF;
    IF v_rol <> NEW.rol_wire THEN
        RAISE EXCEPTION 'rol wire no coincide con el actor al aceptar'
            USING ERRCODE = '23514';
    END IF;

    SELECT perfil, audiencia, revision_scheme, required_set_revision
      INTO v_perfil, v_audiencia, v_scheme, v_revision
      FROM legal_requisito_agregados
     WHERE id = NEW.agregado_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agregado legal inexistente para el lote'
            USING ERRCODE = '23503';
    END IF;
    IF v_perfil <> NEW.perfil
       OR v_audiencia <> NEW.audiencia
       OR v_scheme <> NEW.revision_scheme
       OR v_revision <> NEW.required_set_revision THEN
        RAISE EXCEPTION 'el lote no coincide con la identidad agregada'
            USING ERRCODE = '23514';
    END IF;

    PERFORM legal_validar_requisito_agregado_actual(NEW.agregado_id);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION legal_aceptacion_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_lote_agregado UUID;
    v_lote_scheme   VARCHAR(20);
    v_lote_xmin     XID;
    v_contexto      VARCHAR(40);
BEGIN
    PERFORM legal_exigir_lock_editorial_v28();

    SELECT lote.agregado_id, lote.revision_scheme, lote.xmin
      INTO v_lote_agregado, v_lote_scheme, v_lote_xmin
      FROM legal_aceptacion_lotes lote
     WHERE lote.id = NEW.lote_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'lote de aceptacion inexistente: %', NEW.lote_id
            USING ERRCODE = '23503';
    END IF;
    IF v_lote_scheme <> 'AGGREGATE_V1' OR v_lote_agregado IS NULL THEN
        RAISE EXCEPTION 'un acto nuevo requiere un lote AGGREGATE_V1'
            USING ERRCODE = '23514';
    END IF;
    IF NOT legal_fila_es_transaccion_actual(v_lote_xmin) THEN
        RAISE EXCEPTION 'no se pueden agregar aceptaciones a un lote ya confirmado'
            USING ERRCODE = '23514';
    END IF;

    SELECT linea.contexto INTO v_contexto
      FROM legal_requisito_versiones version
      JOIN legal_requisito_lineas linea ON linea.id = version.requisito_linea_id
     WHERE version.id = NEW.requisito_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'version de requisito inexistente: %', NEW.requisito_version_id
            USING ERRCODE = '23503';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM legal_requisito_agregado_scopes scope
          JOIN legal_requisito_conjunto_miembros miembro
            ON miembro.conjunto_id = scope.conjunto_id
           AND miembro.publicacion_id = scope.publicacion_id
           AND miembro.requisito_version_id = NEW.requisito_version_id
         WHERE scope.agregado_id = v_lote_agregado
           AND scope.contexto = v_contexto
    ) THEN
        RAISE EXCEPTION 'el requisito aceptado no pertenece al agregado del lote'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_aceptacion_agregado_constraint_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_lote_id UUID;
    v_scheme  VARCHAR(20);
    v_agregado_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'legal_aceptacion_lotes' THEN
        v_lote_id := NEW.id;
    ELSE
        v_lote_id := NEW.lote_id;
    END IF;

    SELECT revision_scheme, agregado_id
      INTO v_scheme, v_agregado_id
      FROM legal_aceptacion_lotes
     WHERE id = v_lote_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    IF v_scheme = 'AGGREGATE_V1' THEN
        PERFORM legal_validar_requisito_agregado_actual(v_agregado_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_legal_aceptacion_lote_agregado_actual
    AFTER INSERT ON legal_aceptacion_lotes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_aceptacion_agregado_constraint_guard();
CREATE CONSTRAINT TRIGGER ct_legal_aceptacion_agregado_actual
    AFTER INSERT ON legal_aceptaciones
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_aceptacion_agregado_constraint_guard();

-- ------------------------------------------------------------
-- Hardening de funciones V28 y cierre de la columna historica
-- ------------------------------------------------------------

DO $legal_v28_harden_functions$
DECLARE
    v_schema TEXT := current_schema();
    v_signature TEXT;
BEGIN
    FOREACH v_signature IN ARRAY ARRAY[
        'legal_exigir_lock_editorial_v28()',
        'legal_requisito_agregado_insert_guard()',
        'legal_requisito_agregado_scope_insert_guard()',
        'legal_validar_requisito_agregado(uuid)',
        'legal_requisito_agregado_constraint_guard()',
        'legal_validar_requisito_agregado_actual(uuid)',
        'legal_aceptacion_lote_insert_guard()',
        'legal_aceptacion_insert_guard()',
        'legal_aceptacion_agregado_constraint_guard()'
    ]
    LOOP
        EXECUTE pg_catalog.format(
            'ALTER FUNCTION %I.%s SET search_path TO pg_catalog, %I, pg_temp',
            v_schema, v_signature, v_schema
        );
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON FUNCTION %I.%s FROM PUBLIC',
            v_schema, v_signature
        );
    END LOOP;
END;
$legal_v28_harden_functions$;

ALTER TABLE legal_aceptacion_lotes
    ALTER COLUMN revision_scheme DROP DEFAULT;
