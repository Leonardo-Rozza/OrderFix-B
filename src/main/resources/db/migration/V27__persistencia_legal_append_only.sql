-- ============================================================
-- V27 - Persistencia legal append-only
-- ============================================================
--
-- La migracion crea exclusivamente la base relacional del contrato legal v1.
-- No importa manifiestos, no publica contenido y no activa enforcement HTTP.

ALTER TABLE users
    ADD CONSTRAINT uk_users_id_taller_id UNIQUE (id, taller_id);

-- ------------------------------------------------------------
-- Publicaciones editoriales
-- ------------------------------------------------------------

CREATE TABLE legal_publicaciones (
    id                           UUID                     NOT NULL,
    publication_external_id      VARCHAR(120)             NOT NULL,
    schema_version               INTEGER                  NOT NULL,
    locale                       VARCHAR(5)               NOT NULL,
    manifest_sha256              VARCHAR(64)              NOT NULL,
    manifest_canonico            TEXT                     NOT NULL,
    razon_social                 VARCHAR(200)             NOT NULL,
    cuit                         VARCHAR(11)              NOT NULL,
    domicilio_legal              VARCHAR(500)             NOT NULL,
    jurisdiccion                 VARCHAR(200)             NOT NULL,
    horario_atencion             VARCHAR(300)             NOT NULL,
    email_legal                  VARCHAR(320)             NOT NULL,
    email_privacidad             VARCHAR(320)             NOT NULL,
    email_soporte                VARCHAR(320)             NOT NULL,
    revision_legal_estado        VARCHAR(20)              NOT NULL,
    revision_legal_referencia    VARCHAR(500),
    revision_legal_en            TIMESTAMP WITH TIME ZONE,
    revision_contable_estado     VARCHAR(20)              NOT NULL,
    revision_contable_referencia VARCHAR(500),
    revision_contable_en         TIMESTAMP WITH TIME ZONE,
    estado_construccion          VARCHAR(10)              NOT NULL DEFAULT 'ABIERTO',
    importado_en                 TIMESTAMP WITH TIME ZONE NOT NULL,
    sellado_en                   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_legal_publicaciones PRIMARY KEY (id),
    CONSTRAINT uk_legal_publicaciones_external UNIQUE (publication_external_id),
    CONSTRAINT ck_legal_publicaciones_schema CHECK (schema_version = 1),
    CONSTRAINT ck_legal_publicaciones_locale CHECK (locale IN ('es-AR')),
    CONSTRAINT ck_legal_publicaciones_manifest_sha CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_legal_publicaciones_manifest CHECK (length(btrim(manifest_canonico)) > 0),
    CONSTRAINT ck_legal_publicaciones_cuit CHECK (cuit ~ '^[0-9]{11}$'),
    CONSTRAINT ck_legal_publicaciones_contactos CHECK (
        length(btrim(razon_social)) > 0
        AND length(btrim(domicilio_legal)) > 0
        AND length(btrim(jurisdiccion)) > 0
        AND length(btrim(horario_atencion)) > 0
        AND position('@' IN email_legal) > 1
        AND position('@' IN email_privacidad) > 1
        AND position('@' IN email_soporte) > 1
    ),
    CONSTRAINT ck_legal_publicaciones_revision_legal CHECK (
        (revision_legal_estado = 'PENDIENTE'
            AND revision_legal_referencia IS NULL AND revision_legal_en IS NULL)
        OR
        (revision_legal_estado IN ('APROBADA', 'RECHAZADA')
            AND revision_legal_referencia IS NOT NULL
            AND length(btrim(revision_legal_referencia)) > 0 AND revision_legal_en IS NOT NULL)
    ),
    CONSTRAINT ck_legal_publicaciones_revision_contable CHECK (
        (revision_contable_estado = 'PENDIENTE'
            AND revision_contable_referencia IS NULL AND revision_contable_en IS NULL)
        OR
        (revision_contable_estado IN ('APROBADA', 'RECHAZADA')
            AND revision_contable_referencia IS NOT NULL
            AND length(btrim(revision_contable_referencia)) > 0 AND revision_contable_en IS NOT NULL)
    ),
    CONSTRAINT ck_legal_publicaciones_construccion CHECK (
        (estado_construccion = 'ABIERTO' AND sellado_en IS NULL)
        OR (estado_construccion = 'SELLADO' AND sellado_en IS NOT NULL)
    )
);

CREATE INDEX idx_legal_publicaciones_locale_estado
    ON legal_publicaciones (locale, estado_construccion);
CREATE INDEX idx_legal_publicaciones_importado_en
    ON legal_publicaciones (importado_en DESC);

-- ------------------------------------------------------------
-- Documentos, versiones y membresias de publicacion
-- ------------------------------------------------------------

CREATE TABLE legal_documento_reemplazo_lotes (
    id                  UUID                     NOT NULL,
    estado_construccion VARCHAR(10)              NOT NULL DEFAULT 'ABIERTO',
    creado_en           TIMESTAMP WITH TIME ZONE NOT NULL,
    sellado_en          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_legal_documento_reemplazo_lotes PRIMARY KEY (id),
    CONSTRAINT ck_legal_doc_reemplazo_lote_estado CHECK (
        (estado_construccion = 'ABIERTO' AND sellado_en IS NULL)
        OR (estado_construccion = 'SELLADO' AND sellado_en IS NOT NULL)
    )
);

CREATE TABLE legal_documento_lineas (
    id                   UUID                     NOT NULL,
    clave                VARCHAR(120)             NOT NULL,
    tipo                 VARCHAR(50)              NOT NULL,
    locale               VARCHAR(5)               NOT NULL,
    publicacion_intro_id UUID                     NOT NULL,
    creado_en            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_documento_lineas PRIMARY KEY (id),
    CONSTRAINT uk_legal_documento_lineas_clave UNIQUE (clave),
    CONSTRAINT uk_legal_documento_lineas_identidad UNIQUE (id, tipo, locale),
    CONSTRAINT fk_legal_documento_lineas_publicacion FOREIGN KEY (publicacion_intro_id)
        REFERENCES legal_publicaciones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_documento_lineas_clave CHECK (length(btrim(clave)) > 0),
    CONSTRAINT ck_legal_documento_lineas_tipo CHECK (tipo IN (
        'TERMINOS_SERVICIO', 'POLITICA_PRIVACIDAD', 'ACUERDO_TRATAMIENTO_DATOS',
        'CONDICIONES_PRO', 'POLITICA_CANCELACIONES_REEMBOLSOS', 'POLITICA_CIERRE_CUENTA',
        'AVISO_CLIENTES_TALLER', 'TERMINOS_USUARIO', 'AVISO_PRIVACIDAD_USUARIO',
        'COMPROMISO_CONFIDENCIALIDAD', 'ATESTACION_DATOS_CLIENTE'
    )),
    CONSTRAINT ck_legal_documento_lineas_locale CHECK (locale IN ('es-AR'))
);

CREATE TABLE legal_documento_versiones (
    id                       UUID                     NOT NULL,
    documento_linea_id       UUID                     NOT NULL,
    publicacion_intro_id     UUID                     NOT NULL,
    version                  VARCHAR(64)              NOT NULL,
    lineage_ordinal          INTEGER                  NOT NULL,
    titulo                   VARCHAR(300)             NOT NULL,
    contenido_markdown       TEXT                     NOT NULL,
    sha256                   VARCHAR(64)              NOT NULL,
    vigente_desde            TIMESTAMP WITH TIME ZONE NOT NULL,
    requires_reacceptance    BOOLEAN                  NOT NULL,
    estado                   VARCHAR(20)              NOT NULL DEFAULT 'BORRADOR',
    estado_cambiado_en       TIMESTAMP WITH TIME ZONE,
    ultimo_motivo            VARCHAR(1000),
    reemplazo_lote_id        UUID,
    CONSTRAINT pk_legal_documento_versiones PRIMARY KEY (id),
    CONSTRAINT uk_legal_documento_version_linea_version UNIQUE (documento_linea_id, version),
    CONSTRAINT uk_legal_documento_version_linea_ordinal UNIQUE (documento_linea_id, lineage_ordinal),
    CONSTRAINT uk_legal_documento_version_identidad UNIQUE (id, documento_linea_id),
    CONSTRAINT uk_legal_documento_version_intro UNIQUE (id, publicacion_intro_id),
    CONSTRAINT uk_legal_documento_version_estado UNIQUE (id, estado),
    CONSTRAINT fk_legal_documento_version_linea FOREIGN KEY (documento_linea_id)
        REFERENCES legal_documento_lineas (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_documento_version_publicacion FOREIGN KEY (publicacion_intro_id)
        REFERENCES legal_publicaciones (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_documento_version_reemplazo FOREIGN KEY (reemplazo_lote_id)
        REFERENCES legal_documento_reemplazo_lotes (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_documento_version_version CHECK (length(btrim(version)) > 0),
    CONSTRAINT ck_legal_documento_version_ordinal CHECK (lineage_ordinal > 0),
    CONSTRAINT ck_legal_documento_version_titulo CHECK (length(btrim(titulo)) > 0),
    CONSTRAINT ck_legal_documento_version_contenido CHECK (length(contenido_markdown) > 0),
    CONSTRAINT ck_legal_documento_version_sha CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_legal_documento_version_estado CHECK (
        estado IN ('BORRADOR', 'PUBLICADA', 'VIGENTE', 'REEMPLAZADA', 'RETIRADA')
    ),
    CONSTRAINT ck_legal_documento_version_estado_meta CHECK (
        (estado = 'BORRADOR' AND estado_cambiado_en IS NULL
            AND ultimo_motivo IS NULL AND reemplazo_lote_id IS NULL)
        OR
        (estado = 'PUBLICADA' AND estado_cambiado_en IS NOT NULL
            AND ultimo_motivo IS NULL AND reemplazo_lote_id IS NULL)
        OR
        (estado = 'VIGENTE' AND estado_cambiado_en IS NOT NULL
            AND ultimo_motivo IS NULL)
        OR
        (estado = 'REEMPLAZADA' AND estado_cambiado_en IS NOT NULL
            AND ultimo_motivo IS NULL AND reemplazo_lote_id IS NOT NULL)
        OR
        (estado = 'RETIRADA' AND estado_cambiado_en IS NOT NULL
            AND ultimo_motivo IS NOT NULL AND length(btrim(ultimo_motivo)) > 0
            AND reemplazo_lote_id IS NULL)
    )
);

CREATE TABLE legal_documento_contextos (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    documento_version_id  UUID        NOT NULL,
    contexto              VARCHAR(40) NOT NULL,
    CONSTRAINT pk_legal_documento_contextos PRIMARY KEY (id),
    CONSTRAINT uk_legal_documento_contexto UNIQUE (documento_version_id, contexto),
    CONSTRAINT fk_legal_documento_contexto_version FOREIGN KEY (documento_version_id)
        REFERENCES legal_documento_versiones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_documento_contexto CHECK (contexto IN (
        'REGISTRO', 'PRIMER_INGRESO_EMPLEADO', 'USO_CONTINUADO', 'CONTRATACION_PRO',
        'ATESTACION_FOTOS', 'ATESTACION_CREDENCIALES', 'CIERRE_CUENTA', 'ARREPENTIMIENTO'
    ))
);

CREATE TABLE legal_publicacion_documentos (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    publicacion_id        UUID    NOT NULL,
    documento_version_id  UUID    NOT NULL,
    manifest_ordinal      INTEGER NOT NULL,
    CONSTRAINT pk_legal_publicacion_documentos PRIMARY KEY (id),
    CONSTRAINT uk_legal_pub_documento_version UNIQUE (publicacion_id, documento_version_id),
    CONSTRAINT uk_legal_pub_documento_ordinal UNIQUE (publicacion_id, manifest_ordinal),
    CONSTRAINT fk_legal_pub_documento_publicacion FOREIGN KEY (publicacion_id)
        REFERENCES legal_publicaciones (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_pub_documento_version FOREIGN KEY (documento_version_id)
        REFERENCES legal_documento_versiones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_pub_documento_ordinal CHECK (manifest_ordinal > 0)
);

CREATE TABLE legal_documento_reemplazo_anteriores (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    lote_id               UUID NOT NULL,
    documento_version_id  UUID NOT NULL,
    CONSTRAINT pk_legal_documento_reemplazo_anteriores PRIMARY KEY (id),
    CONSTRAINT uk_legal_doc_reemplazo_anterior UNIQUE (lote_id, documento_version_id),
    CONSTRAINT fk_legal_doc_reemplazo_anterior_lote FOREIGN KEY (lote_id)
        REFERENCES legal_documento_reemplazo_lotes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_doc_reemplazo_anterior_version FOREIGN KEY (documento_version_id)
        REFERENCES legal_documento_versiones (id) ON DELETE RESTRICT
);

CREATE TABLE legal_documento_reemplazo_sucesoras (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    lote_id               UUID NOT NULL,
    documento_version_id  UUID NOT NULL,
    publicacion_id        UUID NOT NULL,
    CONSTRAINT pk_legal_documento_reemplazo_sucesoras PRIMARY KEY (id),
    CONSTRAINT uk_legal_doc_reemplazo_sucesora UNIQUE (lote_id, documento_version_id),
    CONSTRAINT fk_legal_doc_reemplazo_sucesora_lote FOREIGN KEY (lote_id)
        REFERENCES legal_documento_reemplazo_lotes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_doc_reemplazo_sucesora_version FOREIGN KEY (documento_version_id)
        REFERENCES legal_documento_versiones (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_doc_reemplazo_sucesora_membresia
        FOREIGN KEY (publicacion_id, documento_version_id)
        REFERENCES legal_publicacion_documentos (publicacion_id, documento_version_id)
        ON DELETE RESTRICT
);

CREATE TABLE legal_documento_transiciones (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    documento_version_id  UUID                     NOT NULL,
    estado_anterior       VARCHAR(20)              NOT NULL,
    estado_nuevo          VARCHAR(20)              NOT NULL,
    motivo                VARCHAR(1000),
    reemplazo_lote_id     UUID,
    ocurrido_en           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_documento_transiciones PRIMARY KEY (id),
    CONSTRAINT fk_legal_documento_transicion_version FOREIGN KEY (documento_version_id)
        REFERENCES legal_documento_versiones (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_documento_transicion_reemplazo FOREIGN KEY (reemplazo_lote_id)
        REFERENCES legal_documento_reemplazo_lotes (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_documento_transicion_estados CHECK (
        estado_anterior IN ('BORRADOR', 'PUBLICADA', 'VIGENTE', 'REEMPLAZADA', 'RETIRADA')
        AND estado_nuevo IN ('BORRADOR', 'PUBLICADA', 'VIGENTE', 'REEMPLAZADA', 'RETIRADA')
    ),
    CONSTRAINT ck_legal_documento_transicion_arista CHECK (
        (estado_anterior = 'BORRADOR' AND estado_nuevo = 'PUBLICADA')
        OR (estado_anterior = 'PUBLICADA' AND estado_nuevo = 'VIGENTE')
        OR (estado_anterior = 'VIGENTE' AND estado_nuevo IN ('REEMPLAZADA', 'RETIRADA'))
    ),
    CONSTRAINT ck_legal_documento_transicion_motivo CHECK (
        (estado_nuevo = 'RETIRADA' AND motivo IS NOT NULL
            AND length(btrim(motivo)) > 0 AND reemplazo_lote_id IS NULL)
        OR (estado_nuevo = 'REEMPLAZADA' AND motivo IS NULL AND reemplazo_lote_id IS NOT NULL)
        OR (estado_nuevo IN ('PUBLICADA', 'VIGENTE') AND motivo IS NULL)
    ),
    CONSTRAINT ck_legal_documento_transicion_lote CHECK (
        reemplazo_lote_id IS NULL
        OR (estado_anterior = 'VIGENTE' AND estado_nuevo = 'REEMPLAZADA')
        OR (estado_anterior = 'PUBLICADA' AND estado_nuevo = 'VIGENTE')
    )
);

CREATE TABLE legal_documento_vigentes (
    tipo                   VARCHAR(50)              NOT NULL,
    locale                 VARCHAR(5)               NOT NULL,
    contexto               VARCHAR(40)              NOT NULL,
    documento_version_id   UUID                     NOT NULL,
    documento_linea_id     UUID                     NOT NULL,
    publicacion_id         UUID                     NOT NULL,
    estado_documento       VARCHAR(20)              NOT NULL DEFAULT 'VIGENTE',
    CONSTRAINT pk_legal_documento_vigentes PRIMARY KEY (tipo, locale, contexto),
    CONSTRAINT uk_legal_documento_vigente_version_contexto
        UNIQUE (documento_version_id, contexto),
    CONSTRAINT fk_legal_documento_vigente_version_linea
        FOREIGN KEY (documento_version_id, documento_linea_id)
        REFERENCES legal_documento_versiones (id, documento_linea_id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_documento_vigente_estado
        FOREIGN KEY (documento_version_id, estado_documento)
        REFERENCES legal_documento_versiones (id, estado)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_legal_documento_vigente_linea_identidad
        FOREIGN KEY (documento_linea_id, tipo, locale)
        REFERENCES legal_documento_lineas (id, tipo, locale) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_documento_vigente_contexto
        FOREIGN KEY (documento_version_id, contexto)
        REFERENCES legal_documento_contextos (documento_version_id, contexto) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_documento_vigente_membresia
        FOREIGN KEY (publicacion_id, documento_version_id)
        REFERENCES legal_publicacion_documentos (publicacion_id, documento_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_legal_documento_vigente_estado CHECK (estado_documento = 'VIGENTE')
);

CREATE INDEX idx_legal_documento_lineas_tipo_locale
    ON legal_documento_lineas (tipo, locale);
CREATE INDEX idx_legal_documento_lineas_publicacion
    ON legal_documento_lineas (publicacion_intro_id);
CREATE INDEX idx_legal_documento_versiones_lineage
    ON legal_documento_versiones (documento_linea_id, lineage_ordinal);
CREATE INDEX idx_legal_documento_versiones_estado
    ON legal_documento_versiones (estado, vigente_desde);
CREATE INDEX idx_legal_documento_versiones_publicacion
    ON legal_documento_versiones (publicacion_intro_id);
CREATE INDEX idx_legal_publicacion_documentos_version
    ON legal_publicacion_documentos (documento_version_id);
CREATE INDEX idx_legal_documento_transiciones_version
    ON legal_documento_transiciones (documento_version_id, ocurrido_en, id);
CREATE INDEX idx_legal_doc_reemplazo_anteriores_version
    ON legal_documento_reemplazo_anteriores (documento_version_id);
CREATE INDEX idx_legal_doc_reemplazo_sucesoras_version
    ON legal_documento_reemplazo_sucesoras (documento_version_id);

-- ------------------------------------------------------------
-- Requisitos, versiones y snapshots
-- ------------------------------------------------------------

CREATE TABLE legal_requisito_lineas (
    id                   UUID                     NOT NULL,
    clave                VARCHAR(120)             NOT NULL,
    locale               VARCHAR(5)               NOT NULL,
    contexto             VARCHAR(40)              NOT NULL,
    tipo_acto            VARCHAR(20)              NOT NULL,
    publicacion_intro_id UUID                     NOT NULL,
    creado_en            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_requisito_lineas PRIMARY KEY (id),
    CONSTRAINT uk_legal_requisito_lineas_clave UNIQUE (clave),
    CONSTRAINT uk_legal_requisito_lineas_identidad UNIQUE (id, locale, contexto, tipo_acto),
    CONSTRAINT fk_legal_requisito_lineas_publicacion FOREIGN KEY (publicacion_intro_id)
        REFERENCES legal_publicaciones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_requisito_lineas_clave CHECK (length(btrim(clave)) > 0),
    CONSTRAINT ck_legal_requisito_lineas_locale CHECK (locale IN ('es-AR')),
    CONSTRAINT ck_legal_requisito_lineas_contexto CHECK (contexto IN (
        'REGISTRO', 'PRIMER_INGRESO_EMPLEADO', 'USO_CONTINUADO', 'CONTRATACION_PRO',
        'ATESTACION_FOTOS', 'ATESTACION_CREDENCIALES', 'CIERRE_CUENTA', 'ARREPENTIMIENTO'
    )),
    CONSTRAINT ck_legal_requisito_lineas_tipo_acto CHECK (
        tipo_acto IN ('ACEPTACION', 'LECTURA', 'DECLARACION')
    )
);

CREATE TABLE legal_requisito_audiencias (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    requisito_linea_id    UUID        NOT NULL,
    audiencia             VARCHAR(20) NOT NULL,
    CONSTRAINT pk_legal_requisito_audiencias PRIMARY KEY (id),
    CONSTRAINT uk_legal_requisito_audiencia UNIQUE (requisito_linea_id, audiencia),
    CONSTRAINT fk_legal_requisito_audiencia_linea FOREIGN KEY (requisito_linea_id)
        REFERENCES legal_requisito_lineas (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_requisito_audiencia CHECK (audiencia IN ('ADMIN_TITULAR', 'USER'))
);

CREATE TABLE legal_requisito_versiones (
    id                       UUID                     NOT NULL,
    requisito_linea_id       UUID                     NOT NULL,
    publicacion_intro_id     UUID                     NOT NULL,
    version                  VARCHAR(64)              NOT NULL,
    lineage_ordinal          INTEGER                  NOT NULL,
    afirmacion               TEXT                     NOT NULL,
    afirmacion_sha256        VARCHAR(64)              NOT NULL,
    requerido                BOOLEAN                  NOT NULL,
    requires_reacceptance    BOOLEAN                  NOT NULL,
    estado                   VARCHAR(20)              NOT NULL DEFAULT 'BORRADOR',
    estado_cambiado_en       TIMESTAMP WITH TIME ZONE,
    ultimo_motivo            VARCHAR(1000),
    CONSTRAINT pk_legal_requisito_versiones PRIMARY KEY (id),
    CONSTRAINT uk_legal_requisito_version_linea_version UNIQUE (requisito_linea_id, version),
    CONSTRAINT uk_legal_requisito_version_linea_ordinal UNIQUE (requisito_linea_id, lineage_ordinal),
    CONSTRAINT uk_legal_requisito_version_identidad UNIQUE (id, requisito_linea_id),
    CONSTRAINT uk_legal_requisito_version_intro UNIQUE (id, publicacion_intro_id),
    CONSTRAINT fk_legal_requisito_version_linea FOREIGN KEY (requisito_linea_id)
        REFERENCES legal_requisito_lineas (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_requisito_version_publicacion FOREIGN KEY (publicacion_intro_id)
        REFERENCES legal_publicaciones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_requisito_version_version CHECK (length(btrim(version)) > 0),
    CONSTRAINT ck_legal_requisito_version_ordinal CHECK (lineage_ordinal > 0),
    CONSTRAINT ck_legal_requisito_version_afirmacion CHECK (length(afirmacion) > 0),
    CONSTRAINT ck_legal_requisito_version_sha CHECK (afirmacion_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_legal_requisito_version_estado CHECK (
        estado IN ('BORRADOR', 'PUBLICADA', 'VIGENTE', 'REEMPLAZADA', 'RETIRADA')
    ),
    CONSTRAINT ck_legal_requisito_version_estado_meta CHECK (
        (estado = 'BORRADOR' AND estado_cambiado_en IS NULL AND ultimo_motivo IS NULL)
        OR
        (estado IN ('PUBLICADA', 'VIGENTE', 'REEMPLAZADA')
            AND estado_cambiado_en IS NOT NULL AND ultimo_motivo IS NULL)
        OR
        (estado = 'RETIRADA' AND estado_cambiado_en IS NOT NULL
            AND ultimo_motivo IS NOT NULL AND length(btrim(ultimo_motivo)) > 0)
    )
);

CREATE TABLE legal_requisito_documentos (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    requisito_version_id  UUID    NOT NULL,
    documento_version_id  UUID    NOT NULL,
    documento_ordinal     INTEGER NOT NULL,
    CONSTRAINT pk_legal_requisito_documentos PRIMARY KEY (id),
    CONSTRAINT uk_legal_requisito_documento_version
        UNIQUE (requisito_version_id, documento_version_id),
    CONSTRAINT uk_legal_requisito_documento_ordinal
        UNIQUE (requisito_version_id, documento_ordinal),
    CONSTRAINT fk_legal_requisito_documento_requisito FOREIGN KEY (requisito_version_id)
        REFERENCES legal_requisito_versiones (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_requisito_documento_documento FOREIGN KEY (documento_version_id)
        REFERENCES legal_documento_versiones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_requisito_documento_ordinal CHECK (documento_ordinal > 0)
);

CREATE TABLE legal_publicacion_requisitos (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    publicacion_id        UUID    NOT NULL,
    requisito_version_id  UUID    NOT NULL,
    manifest_ordinal      INTEGER NOT NULL,
    CONSTRAINT pk_legal_publicacion_requisitos PRIMARY KEY (id),
    CONSTRAINT uk_legal_pub_requisito_version UNIQUE (publicacion_id, requisito_version_id),
    CONSTRAINT uk_legal_pub_requisito_ordinal UNIQUE (publicacion_id, manifest_ordinal),
    CONSTRAINT fk_legal_pub_requisito_publicacion FOREIGN KEY (publicacion_id)
        REFERENCES legal_publicaciones (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_pub_requisito_version FOREIGN KEY (requisito_version_id)
        REFERENCES legal_requisito_versiones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_pub_requisito_ordinal CHECK (manifest_ordinal > 0)
);

CREATE TABLE legal_requisito_transiciones (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    requisito_version_id  UUID                     NOT NULL,
    estado_anterior       VARCHAR(20)              NOT NULL,
    estado_nuevo          VARCHAR(20)              NOT NULL,
    motivo                VARCHAR(1000),
    ocurrido_en           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_requisito_transiciones PRIMARY KEY (id),
    CONSTRAINT fk_legal_requisito_transicion_version FOREIGN KEY (requisito_version_id)
        REFERENCES legal_requisito_versiones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_requisito_transicion_estados CHECK (
        estado_anterior IN ('BORRADOR', 'PUBLICADA', 'VIGENTE', 'REEMPLAZADA', 'RETIRADA')
        AND estado_nuevo IN ('BORRADOR', 'PUBLICADA', 'VIGENTE', 'REEMPLAZADA', 'RETIRADA')
    ),
    CONSTRAINT ck_legal_requisito_transicion_arista CHECK (
        (estado_anterior = 'BORRADOR' AND estado_nuevo = 'PUBLICADA')
        OR (estado_anterior = 'PUBLICADA' AND estado_nuevo = 'VIGENTE')
        OR (estado_anterior = 'VIGENTE' AND estado_nuevo IN ('REEMPLAZADA', 'RETIRADA'))
    ),
    CONSTRAINT ck_legal_requisito_transicion_motivo CHECK (
        (estado_nuevo = 'RETIRADA' AND motivo IS NOT NULL AND length(btrim(motivo)) > 0)
        OR (estado_nuevo IN ('PUBLICADA', 'VIGENTE', 'REEMPLAZADA') AND motivo IS NULL)
    )
);

CREATE TABLE legal_requisito_conjuntos (
    id             UUID                     NOT NULL,
    publicacion_id UUID                     NOT NULL,
    locale         VARCHAR(5)               NOT NULL,
    contexto       VARCHAR(40)              NOT NULL,
    audiencia      VARCHAR(20)              NOT NULL,
    required_set_revision VARCHAR(71)       NOT NULL,
    creado_en      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_requisito_conjuntos PRIMARY KEY (id),
    CONSTRAINT uk_legal_requisito_conjunto_scope
        UNIQUE (publicacion_id, locale, contexto, audiencia),
    CONSTRAINT uk_legal_requisito_conjunto_identidad
        UNIQUE (id, publicacion_id, locale, contexto, audiencia),
    CONSTRAINT uk_legal_requisito_conjunto_publicacion UNIQUE (id, publicacion_id),
    CONSTRAINT fk_legal_requisito_conjunto_publicacion FOREIGN KEY (publicacion_id)
        REFERENCES legal_publicaciones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_requisito_conjunto_locale CHECK (locale IN ('es-AR')),
    CONSTRAINT ck_legal_requisito_conjunto_contexto CHECK (contexto IN (
        'REGISTRO', 'PRIMER_INGRESO_EMPLEADO', 'USO_CONTINUADO', 'CONTRATACION_PRO',
        'ATESTACION_FOTOS', 'ATESTACION_CREDENCIALES', 'CIERRE_CUENTA', 'ARREPENTIMIENTO'
    )),
    CONSTRAINT ck_legal_requisito_conjunto_audiencia CHECK (
        audiencia IN ('ADMIN_TITULAR', 'USER')
    ),
    CONSTRAINT ck_legal_requisito_conjunto_revision CHECK (
        required_set_revision ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE legal_requisito_conjunto_miembros (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    conjunto_id           UUID    NOT NULL,
    publicacion_id        UUID    NOT NULL,
    requisito_version_id  UUID    NOT NULL,
    requisito_linea_id    UUID    NOT NULL,
    manifest_ordinal      INTEGER NOT NULL,
    CONSTRAINT pk_legal_requisito_conjunto_miembros PRIMARY KEY (id),
    CONSTRAINT uk_legal_requisito_conjunto_linea UNIQUE (conjunto_id, requisito_linea_id),
    CONSTRAINT uk_legal_requisito_conjunto_ordinal UNIQUE (conjunto_id, manifest_ordinal),
    CONSTRAINT fk_legal_requisito_conjunto_miembro_cabecera
        FOREIGN KEY (conjunto_id, publicacion_id)
        REFERENCES legal_requisito_conjuntos (id, publicacion_id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_requisito_conjunto_miembro_version
        FOREIGN KEY (requisito_version_id, requisito_linea_id)
        REFERENCES legal_requisito_versiones (id, requisito_linea_id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_requisito_conjunto_miembro_membresia
        FOREIGN KEY (publicacion_id, requisito_version_id)
        REFERENCES legal_publicacion_requisitos (publicacion_id, requisito_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_legal_requisito_conjunto_ordinal CHECK (manifest_ordinal > 0)
);

CREATE TABLE legal_requisito_conjuntos_actuales (
    locale         VARCHAR(5)               NOT NULL,
    contexto       VARCHAR(40)              NOT NULL,
    audiencia      VARCHAR(20)              NOT NULL,
    conjunto_id    UUID                     NOT NULL,
    publicacion_id UUID                     NOT NULL,
    actualizado_en TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_requisito_conjuntos_actuales PRIMARY KEY (locale, contexto, audiencia),
    CONSTRAINT fk_legal_requisito_conjunto_actual_scope
        FOREIGN KEY (conjunto_id, publicacion_id, locale, contexto, audiencia)
        REFERENCES legal_requisito_conjuntos (id, publicacion_id, locale, contexto, audiencia)
        ON DELETE RESTRICT
);

CREATE INDEX idx_legal_requisito_lineas_scope
    ON legal_requisito_lineas (locale, contexto, tipo_acto);
CREATE INDEX idx_legal_requisito_lineas_publicacion
    ON legal_requisito_lineas (publicacion_intro_id);
CREATE INDEX idx_legal_requisito_versiones_lineage
    ON legal_requisito_versiones (requisito_linea_id, lineage_ordinal);
CREATE INDEX idx_legal_requisito_versiones_estado
    ON legal_requisito_versiones (estado);
CREATE INDEX idx_legal_requisito_versiones_publicacion
    ON legal_requisito_versiones (publicacion_intro_id);
CREATE INDEX idx_legal_requisito_documentos_documento
    ON legal_requisito_documentos (documento_version_id);
CREATE INDEX idx_legal_publicacion_requisitos_version
    ON legal_publicacion_requisitos (requisito_version_id);
CREATE INDEX idx_legal_requisito_transiciones_version
    ON legal_requisito_transiciones (requisito_version_id, ocurrido_en, id);
CREATE INDEX idx_legal_requisito_conjunto_miembros_version
    ON legal_requisito_conjunto_miembros (requisito_version_id);
CREATE INDEX idx_legal_requisito_conjuntos_publicacion
    ON legal_requisito_conjuntos (publicacion_id, locale, contexto, audiencia);

-- ------------------------------------------------------------
-- Evidencia de aceptacion y metadata protegida
-- ------------------------------------------------------------

CREATE TABLE legal_aceptacion_lotes (
    id                    UUID                     NOT NULL,
    user_id               BIGINT                   NOT NULL,
    taller_id             BIGINT                   NOT NULL,
    rol_wire              VARCHAR(10)              NOT NULL,
    audiencia             VARCHAR(20)              NOT NULL,
    required_set_revision VARCHAR(71)              NOT NULL,
    aceptado_en           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_aceptacion_lotes PRIMARY KEY (id),
    CONSTRAINT uk_legal_aceptacion_lote_actor UNIQUE (id, user_id, taller_id),
    CONSTRAINT fk_legal_aceptacion_lote_actor FOREIGN KEY (user_id, taller_id)
        REFERENCES users (id, taller_id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_aceptacion_lote_rol CHECK (rol_wire IN ('ADMIN', 'USER')),
    CONSTRAINT ck_legal_aceptacion_lote_audiencia CHECK (
        (rol_wire = 'ADMIN' AND audiencia = 'ADMIN_TITULAR')
        OR (rol_wire = 'USER' AND audiencia = 'USER')
    ),
    CONSTRAINT ck_legal_aceptacion_lote_revision CHECK (
        required_set_revision ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE legal_aceptaciones (
    id                    UUID        NOT NULL,
    lote_id               UUID        NOT NULL,
    user_id               BIGINT      NOT NULL,
    taller_id             BIGINT      NOT NULL,
    requisito_version_id  UUID        NOT NULL,
    requisito_clave       VARCHAR(120) NOT NULL,
    requisito_version     VARCHAR(64) NOT NULL,
    contexto              VARCHAR(40) NOT NULL,
    tipo_acto             VARCHAR(20) NOT NULL,
    afirmacion            TEXT        NOT NULL,
    afirmacion_sha256     VARCHAR(64) NOT NULL,
    requerido             BOOLEAN     NOT NULL,
    CONSTRAINT pk_legal_aceptaciones PRIMARY KEY (id),
    CONSTRAINT uk_legal_aceptacion_usuario_requisito UNIQUE (user_id, requisito_version_id),
    CONSTRAINT uk_legal_aceptacion_actor UNIQUE (id, user_id, taller_id),
    CONSTRAINT fk_legal_aceptacion_actor_lote
        FOREIGN KEY (lote_id, user_id, taller_id)
        REFERENCES legal_aceptacion_lotes (id, user_id, taller_id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_aceptacion_requisito FOREIGN KEY (requisito_version_id)
        REFERENCES legal_requisito_versiones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_aceptacion_clave CHECK (length(btrim(requisito_clave)) > 0),
    CONSTRAINT ck_legal_aceptacion_version CHECK (length(btrim(requisito_version)) > 0),
    CONSTRAINT ck_legal_aceptacion_contexto CHECK (contexto IN (
        'REGISTRO', 'PRIMER_INGRESO_EMPLEADO', 'USO_CONTINUADO', 'CONTRATACION_PRO',
        'ATESTACION_FOTOS', 'ATESTACION_CREDENCIALES', 'CIERRE_CUENTA', 'ARREPENTIMIENTO'
    )),
    CONSTRAINT ck_legal_aceptacion_tipo_acto CHECK (
        tipo_acto IN ('ACEPTACION', 'LECTURA', 'DECLARACION')
    ),
    CONSTRAINT ck_legal_aceptacion_afirmacion CHECK (length(afirmacion) > 0),
    CONSTRAINT ck_legal_aceptacion_afirmacion_sha CHECK (afirmacion_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE legal_aceptacion_documentos (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    aceptacion_id         UUID         NOT NULL,
    documento_ordinal     INTEGER      NOT NULL,
    documento_version_id  UUID         NOT NULL,
    documento_clave       VARCHAR(120) NOT NULL,
    tipo                  VARCHAR(50)  NOT NULL,
    version               VARCHAR(64)  NOT NULL,
    titulo                VARCHAR(300) NOT NULL,
    sha256                VARCHAR(64)  NOT NULL,
    CONSTRAINT pk_legal_aceptacion_documentos PRIMARY KEY (id),
    CONSTRAINT uk_legal_aceptacion_documento_version
        UNIQUE (aceptacion_id, documento_version_id),
    CONSTRAINT uk_legal_aceptacion_documento_ordinal
        UNIQUE (aceptacion_id, documento_ordinal),
    CONSTRAINT fk_legal_aceptacion_documento_aceptacion FOREIGN KEY (aceptacion_id)
        REFERENCES legal_aceptaciones (id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_aceptacion_documento_version FOREIGN KEY (documento_version_id)
        REFERENCES legal_documento_versiones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_aceptacion_documento_ordinal CHECK (documento_ordinal > 0),
    CONSTRAINT ck_legal_aceptacion_documento_clave CHECK (length(btrim(documento_clave)) > 0),
    CONSTRAINT ck_legal_aceptacion_documento_tipo CHECK (tipo IN (
        'TERMINOS_SERVICIO', 'POLITICA_PRIVACIDAD', 'ACUERDO_TRATAMIENTO_DATOS',
        'CONDICIONES_PRO', 'POLITICA_CANCELACIONES_REEMBOLSOS', 'POLITICA_CIERRE_CUENTA',
        'AVISO_CLIENTES_TALLER', 'TERMINOS_USUARIO', 'AVISO_PRIVACIDAD_USUARIO',
        'COMPROMISO_CONFIDENCIALIDAD', 'ATESTACION_DATOS_CLIENTE'
    )),
    CONSTRAINT ck_legal_aceptacion_documento_version CHECK (length(btrim(version)) > 0),
    CONSTRAINT ck_legal_aceptacion_documento_titulo CHECK (length(btrim(titulo)) > 0),
    CONSTRAINT ck_legal_aceptacion_documento_sha CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE legal_aceptacion_metadatos (
    lote_id        UUID                     NOT NULL,
    capturado_en   TIMESTAMP WITH TIME ZONE NOT NULL,
    retener_hasta  TIMESTAMP WITH TIME ZONE NOT NULL,
    purgado_en     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_legal_aceptacion_metadatos PRIMARY KEY (lote_id),
    CONSTRAINT fk_legal_aceptacion_metadatos_lote FOREIGN KEY (lote_id)
        REFERENCES legal_aceptacion_lotes (id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_aceptacion_metadatos_retencion CHECK (retener_hasta > capturado_en),
    CONSTRAINT ck_legal_aceptacion_metadatos_purga CHECK (
        purgado_en IS NULL OR purgado_en >= retener_hasta
    )
);

CREATE TABLE legal_aceptacion_metadatos_cifrados (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY,
    lote_id            UUID        NOT NULL,
    tipo               VARCHAR(20) NOT NULL,
    key_version        INTEGER     NOT NULL,
    nonce              BYTEA       NOT NULL,
    ciphertext         BYTEA,
    tag                BYTEA,
    longitud_original  INTEGER,
    tombstone_en       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_legal_aceptacion_metadatos_cifrados PRIMARY KEY (id),
    CONSTRAINT uk_legal_aceptacion_metadata_tipo UNIQUE (lote_id, tipo),
    CONSTRAINT uk_legal_aceptacion_metadata_nonce UNIQUE (key_version, nonce),
    CONSTRAINT fk_legal_aceptacion_metadata_cifrada_lote FOREIGN KEY (lote_id)
        REFERENCES legal_aceptacion_metadatos (lote_id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_aceptacion_metadata_tipo CHECK (tipo IN ('IP', 'USER_AGENT')),
    CONSTRAINT ck_legal_aceptacion_metadata_key CHECK (key_version > 0),
    CONSTRAINT ck_legal_aceptacion_metadata_nonce CHECK (octet_length(nonce) = 12),
    CONSTRAINT ck_legal_aceptacion_metadata_estado CHECK (
        (tombstone_en IS NULL
            AND ciphertext IS NOT NULL AND octet_length(ciphertext) > 0
            AND tag IS NOT NULL AND octet_length(tag) = 16
            AND longitud_original IS NOT NULL AND longitud_original > 0
            AND (tipo <> 'USER_AGENT' OR longitud_original <= 512))
        OR
        (tombstone_en IS NOT NULL
            AND ciphertext IS NULL AND tag IS NULL AND longitud_original IS NULL)
    )
);

CREATE INDEX idx_legal_aceptacion_lotes_actor_fecha
    ON legal_aceptacion_lotes (user_id, taller_id, aceptado_en DESC, id DESC);
CREATE INDEX idx_legal_aceptaciones_taller_requisito
    ON legal_aceptaciones (taller_id, requisito_version_id);
CREATE INDEX idx_legal_aceptaciones_lote
    ON legal_aceptaciones (lote_id);
CREATE INDEX idx_legal_aceptacion_documentos_version
    ON legal_aceptacion_documentos (documento_version_id);
CREATE INDEX idx_legal_aceptacion_metadatos_retencion
    ON legal_aceptacion_metadatos (retener_hasta) WHERE purgado_en IS NULL;

-- ------------------------------------------------------------
-- Ledger de idempotencia exitosa
-- ------------------------------------------------------------

CREATE TABLE legal_idempotencia_resultados (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY,
    operacion             VARCHAR(30)              NOT NULL,
    route_template        VARCHAR(200)             NOT NULL,
    scope_hmac            VARCHAR(64)              NOT NULL,
    idempotency_key_hmac  VARCHAR(64)              NOT NULL,
    fingerprint_hmac      VARCHAR(64)              NOT NULL,
    hmac_key_version      INTEGER                  NOT NULL,
    user_id               BIGINT                   NOT NULL,
    taller_id             BIGINT                   NOT NULL,
    lote_id               UUID                     NOT NULL,
    completed_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_idempotencia_resultados PRIMARY KEY (id),
    CONSTRAINT uk_legal_idempotencia_resultado
        UNIQUE (operacion, route_template, scope_hmac, idempotency_key_hmac),
    CONSTRAINT fk_legal_idempotencia_lote_actor
        FOREIGN KEY (lote_id, user_id, taller_id)
        REFERENCES legal_aceptacion_lotes (id, user_id, taller_id) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_idempotencia_operacion CHECK (
        operacion IN ('REGISTRO', 'ACEPTACION_LEGAL')
    ),
    CONSTRAINT ck_legal_idempotencia_route CHECK (
        length(btrim(route_template)) > 0 AND left(route_template, 1) = '/'
    ),
    CONSTRAINT ck_legal_idempotencia_scope CHECK (scope_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_legal_idempotencia_key CHECK (idempotency_key_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_legal_idempotencia_fingerprint CHECK (fingerprint_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_legal_idempotencia_key_version CHECK (hmac_key_version > 0),
    CONSTRAINT ck_legal_idempotencia_expiracion CHECK (
        expires_at >= completed_at + INTERVAL '24 hours'
    )
);

CREATE INDEX idx_legal_idempotencia_lookup
    ON legal_idempotencia_resultados
        (operacion, route_template, scope_hmac, hmac_key_version, idempotency_key_hmac);
CREATE INDEX idx_legal_idempotencia_expires
    ON legal_idempotencia_resultados (expires_at);

-- ------------------------------------------------------------
-- Guardas comunes y protocolo de construccion de publicaciones
-- ------------------------------------------------------------

CREATE FUNCTION legal_rechazar_update_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% es append-only: % no permitido', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION legal_exigir_read_committed()
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'el protocolo de escritura legal exige aislamiento READ COMMITTED'
            USING ERRCODE = '25001';
    END IF;
END;
$$;

CREATE FUNCTION legal_read_committed_statement_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM legal_exigir_read_committed();
    RETURN NULL;
END;
$$;

CREATE FUNCTION legal_publicacion_update_statement_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM legal_exigir_read_committed();

    -- Se toma antes de que el UPDATE adquiera cualquier lock de fila. Los sellos son
    -- infrecuentes y esta barrera evita el ciclo A(fila A)->B / B(fila B)->A.
    PERFORM pg_advisory_xact_lock(
        pg_catalog.hashtextextended('ordenfix:legal-publicaciones:sello:v1', 0)
    );
    RETURN NULL;
END;
$$;

CREATE FUNCTION legal_bloquear_publicacion_abierta(p_publicacion_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_estado VARCHAR(10);
BEGIN
    PERFORM legal_exigir_read_committed();

    SELECT estado_construccion
      INTO v_estado
      FROM legal_publicaciones
     WHERE id = p_publicacion_id
     FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'publicacion legal inexistente: %', p_publicacion_id
            USING ERRCODE = '23503';
    END IF;
    IF v_estado <> 'ABIERTO' THEN
        RAISE EXCEPTION 'publicacion legal % ya esta sellada', p_publicacion_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION legal_bloquear_publicacion_sellada(p_publicacion_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_estado VARCHAR(10);
BEGIN
    PERFORM legal_exigir_read_committed();

    SELECT estado_construccion
      INTO v_estado
      FROM legal_publicaciones
     WHERE id = p_publicacion_id
     FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'publicacion legal inexistente: %', p_publicacion_id
            USING ERRCODE = '23503';
    END IF;
    IF v_estado <> 'SELLADO' THEN
        RAISE EXCEPTION 'publicacion legal % todavia no esta sellada', p_publicacion_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION legal_exigir_publicacion_abierta_columna()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_publicacion_id UUID;
BEGIN
    v_publicacion_id := (to_jsonb(NEW) ->> TG_ARGV[0])::UUID;
    PERFORM legal_bloquear_publicacion_abierta(v_publicacion_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_exigir_publicacion_doc_contexto_abierta()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_publicacion_id UUID;
BEGIN
    SELECT publicacion_intro_id
      INTO v_publicacion_id
      FROM legal_documento_versiones
     WHERE id = NEW.documento_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'version documental inexistente: %', NEW.documento_version_id
            USING ERRCODE = '23503';
    END IF;
    PERFORM legal_bloquear_publicacion_abierta(v_publicacion_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_exigir_publicacion_req_audiencia_abierta()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_publicacion_id UUID;
BEGIN
    SELECT publicacion_intro_id
      INTO v_publicacion_id
      FROM legal_requisito_lineas
     WHERE id = NEW.requisito_linea_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'linea de requisito inexistente: %', NEW.requisito_linea_id
            USING ERRCODE = '23503';
    END IF;
    PERFORM legal_bloquear_publicacion_abierta(v_publicacion_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_exigir_publicacion_req_documento_abierta()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_publicacion_id UUID;
BEGIN
    SELECT publicacion_intro_id
      INTO v_publicacion_id
      FROM legal_requisito_versiones
     WHERE id = NEW.requisito_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'version de requisito inexistente: %', NEW.requisito_version_id
            USING ERRCODE = '23503';
    END IF;
    PERFORM legal_bloquear_publicacion_abierta(v_publicacion_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_publicacion_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.estado_construccion <> 'ABIERTO' OR NEW.sellado_en IS NOT NULL THEN
        RAISE EXCEPTION 'una publicacion debe nacer ABIERTO y sin sello'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_bloquear_dependencias_publicacion(p_publicacion_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_dependencia UUID;
    v_estado      VARCHAR(10);
BEGIN
    FOR v_dependencia IN
        SELECT DISTINCT dependencias.id
          FROM (
                SELECT dl.publicacion_intro_id AS id
                  FROM legal_documento_versiones dv
                  JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
                 WHERE dv.publicacion_intro_id = p_publicacion_id
                UNION ALL
                SELECT dv.publicacion_intro_id
                  FROM legal_publicacion_documentos pd
                  JOIN legal_documento_versiones dv ON dv.id = pd.documento_version_id
                 WHERE pd.publicacion_id = p_publicacion_id
                UNION ALL
                SELECT rl.publicacion_intro_id
                  FROM legal_requisito_versiones rv
                  JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
                 WHERE rv.publicacion_intro_id = p_publicacion_id
                UNION ALL
                SELECT rv.publicacion_intro_id
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_versiones rv ON rv.id = pr.requisito_version_id
                 WHERE pr.publicacion_id = p_publicacion_id
                UNION ALL
                SELECT dv.publicacion_intro_id
                  FROM legal_publicacion_requisitos pr
                  JOIN legal_requisito_documentos rd
                    ON rd.requisito_version_id = pr.requisito_version_id
                  JOIN legal_documento_versiones dv ON dv.id = rd.documento_version_id
                 WHERE pr.publicacion_id = p_publicacion_id
          ) dependencias
         WHERE dependencias.id <> p_publicacion_id
         ORDER BY dependencias.id
    LOOP
        SELECT estado_construccion
          INTO v_estado
          FROM legal_publicaciones
         WHERE id = v_dependencia
         FOR SHARE;
        IF NOT FOUND OR v_estado <> 'SELLADO' THEN
            RAISE EXCEPTION 'publicacion % depende de publicacion externa no sellada %',
                p_publicacion_id, v_dependencia
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
END;
$$;

CREATE FUNCTION legal_publicacion_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (to_jsonb(NEW) - ARRAY['estado_construccion', 'sellado_en'])
        IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['estado_construccion', 'sellado_en']) THEN
        RAISE EXCEPTION 'los campos editoriales de una publicacion son inmutables'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.estado_construccion <> 'ABIERTO'
       OR NEW.estado_construccion <> 'SELLADO'
       OR OLD.sellado_en IS NOT NULL THEN
        RAISE EXCEPTION 'transicion de publicacion invalida: % -> %',
            OLD.estado_construccion, NEW.estado_construccion
            USING ERRCODE = '23514';
    END IF;

    NEW.sellado_en := COALESCE(NEW.sellado_en, transaction_timestamp());
    IF NEW.sellado_en < NEW.importado_en
       OR NEW.sellado_en > transaction_timestamp() THEN
        RAISE EXCEPTION 'instante de sello de publicacion invalido'
            USING ERRCODE = '23514';
    END IF;

    -- El UPDATE ya retiene un lock incompatible sobre la publicacion propia.
    -- Las dependencias externas se bloquean siempre en orden UUID.
    PERFORM legal_bloquear_dependencias_publicacion(NEW.id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_validar_publicacion_sellada(p_publicacion_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_estado VARCHAR(10);
BEGIN
    SELECT estado_construccion INTO v_estado
      FROM legal_publicaciones WHERE id = p_publicacion_id;
    IF v_estado <> 'SELLADO' THEN
        RAISE EXCEPTION 'la publicacion % no termino sellada', p_publicacion_id
            USING ERRCODE = '23514';
    END IF;

    PERFORM legal_bloquear_dependencias_publicacion(p_publicacion_id);

    IF EXISTS (
        SELECT 1
          FROM legal_documento_lineas dl
         WHERE dl.publicacion_intro_id = p_publicacion_id
           AND NOT EXISTS (
               SELECT 1 FROM legal_documento_versiones dv
                WHERE dv.documento_linea_id = dl.id
                  AND dv.publicacion_intro_id = p_publicacion_id
           )
    ) THEN
        RAISE EXCEPTION 'publicacion % contiene linea documental sin version propia', p_publicacion_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_documento_versiones dv
         WHERE dv.publicacion_intro_id = p_publicacion_id
           AND (
               NOT EXISTS (SELECT 1 FROM legal_documento_contextos dc
                            WHERE dc.documento_version_id = dv.id)
               OR NOT EXISTS (SELECT 1 FROM legal_publicacion_documentos pd
                              WHERE pd.publicacion_id = p_publicacion_id
                                AND pd.documento_version_id = dv.id)
           )
    ) THEN
        RAISE EXCEPTION 'publicacion % contiene version documental incompleta o no incluida',
            p_publicacion_id USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_requisito_lineas rl
         WHERE rl.publicacion_intro_id = p_publicacion_id
           AND (
               NOT EXISTS (SELECT 1 FROM legal_requisito_audiencias ra
                            WHERE ra.requisito_linea_id = rl.id)
               OR NOT EXISTS (
                   SELECT 1 FROM legal_requisito_versiones rv
                    WHERE rv.requisito_linea_id = rl.id
                      AND rv.publicacion_intro_id = p_publicacion_id
               )
           )
    ) THEN
        RAISE EXCEPTION 'publicacion % contiene linea de requisito incompleta', p_publicacion_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_requisito_versiones rv
         WHERE rv.publicacion_intro_id = p_publicacion_id
           AND (
               NOT EXISTS (SELECT 1 FROM legal_requisito_documentos rd
                            WHERE rd.requisito_version_id = rv.id)
               OR NOT EXISTS (SELECT 1 FROM legal_publicacion_requisitos pr
                              WHERE pr.publicacion_id = p_publicacion_id
                                AND pr.requisito_version_id = rv.id)
           )
    ) THEN
        RAISE EXCEPTION 'publicacion % contiene version de requisito incompleta o no incluida',
            p_publicacion_id USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_requisito_versiones rv
          JOIN LATERAL (
              SELECT count(*) AS cantidad, min(documento_ordinal) AS minimo,
                     max(documento_ordinal) AS maximo
                FROM legal_requisito_documentos rd
               WHERE rd.requisito_version_id = rv.id
          ) x ON TRUE
         WHERE rv.publicacion_intro_id = p_publicacion_id
           AND (x.cantidad = 0 OR x.minimo <> 1 OR x.maximo <> x.cantidad)
    ) THEN
        RAISE EXCEPTION 'publicacion % posee documentos de requisito no contiguos',
            p_publicacion_id USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_requisito_documentos rd
          JOIN legal_requisito_versiones rv ON rv.id = rd.requisito_version_id
          JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
          JOIN legal_documento_versiones dv ON dv.id = rd.documento_version_id
          JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
         WHERE rv.publicacion_intro_id = p_publicacion_id
           AND (dl.locale <> rl.locale
                OR NOT EXISTS (
                    SELECT 1 FROM legal_documento_contextos dc
                     WHERE dc.documento_version_id = dv.id
                       AND dc.contexto = rl.contexto
                ))
    ) THEN
        RAISE EXCEPTION 'publicacion % vincula requisito y documento incompatibles',
            p_publicacion_id USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM (
                SELECT count(*) AS cantidad, min(manifest_ordinal) AS minimo,
                       max(manifest_ordinal) AS maximo
                  FROM legal_publicacion_documentos
                 WHERE publicacion_id = p_publicacion_id
                UNION ALL
                SELECT count(*), min(manifest_ordinal), max(manifest_ordinal)
                  FROM legal_publicacion_requisitos
                 WHERE publicacion_id = p_publicacion_id
          ) ordinales
         WHERE cantidad > 0 AND (minimo <> 1 OR maximo <> cantidad)
    ) THEN
        RAISE EXCEPTION 'publicacion % posee ordinales de manifiesto no contiguos', p_publicacion_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_requisito_conjuntos c
         WHERE c.publicacion_id = p_publicacion_id
           AND EXISTS (
               SELECT 1
                 FROM legal_requisito_conjunto_miembros m
                 JOIN legal_requisito_versiones rv ON rv.id = m.requisito_version_id
                 JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
                WHERE m.conjunto_id = c.id
                  AND (m.publicacion_id <> c.publicacion_id
                       OR rl.locale <> c.locale
                       OR rl.contexto <> c.contexto
                       OR NOT EXISTS (
                           SELECT 1 FROM legal_requisito_audiencias ra
                            WHERE ra.requisito_linea_id = rl.id
                              AND ra.audiencia = c.audiencia
                       ))
           )
    ) THEN
        RAISE EXCEPTION 'publicacion % posee snapshot con scope o publicacion mezclados',
            p_publicacion_id USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_requisito_conjunto_miembros m
          JOIN legal_publicacion_requisitos pr
            ON pr.publicacion_id = m.publicacion_id
           AND pr.requisito_version_id = m.requisito_version_id
         WHERE m.publicacion_id = p_publicacion_id
           AND m.manifest_ordinal <> pr.manifest_ordinal
    ) THEN
        RAISE EXCEPTION 'publicacion % altera ordinales del manifiesto dentro de snapshots',
            p_publicacion_id USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_requisito_conjuntos c
         WHERE c.publicacion_id = p_publicacion_id
           AND EXISTS (
               SELECT 1
                 FROM legal_publicacion_requisitos pr
                 JOIN legal_requisito_versiones rv ON rv.id = pr.requisito_version_id
                 JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
                WHERE pr.publicacion_id = c.publicacion_id
                  AND rl.locale = c.locale
                  AND rl.contexto = c.contexto
                  AND EXISTS (
                      SELECT 1 FROM legal_requisito_audiencias ra
                       WHERE ra.requisito_linea_id = rl.id
                         AND ra.audiencia = c.audiencia
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM legal_requisito_conjunto_miembros m
                       WHERE m.conjunto_id = c.id
                         AND m.requisito_version_id = rv.id
                  )
           )
    ) THEN
        RAISE EXCEPTION 'publicacion % posee snapshot incompleto', p_publicacion_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_publicacion_requisitos pr
          JOIN legal_requisito_versiones rv ON rv.id = pr.requisito_version_id
          JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
          JOIN legal_requisito_audiencias ra ON ra.requisito_linea_id = rl.id
         WHERE pr.publicacion_id = p_publicacion_id
           AND NOT EXISTS (
               SELECT 1 FROM legal_requisito_conjuntos c
                WHERE c.publicacion_id = p_publicacion_id
                  AND c.locale = rl.locale
                  AND c.contexto = rl.contexto
                  AND c.audiencia = ra.audiencia
           )
    ) THEN
        RAISE EXCEPTION 'publicacion % no congela todos los snapshots aplicables', p_publicacion_id
            USING ERRCODE = '23514';
    END IF;

END;
$$;

CREATE FUNCTION legal_publicacion_constraint_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM legal_validar_publicacion_sellada(NEW.id);
    RETURN NULL;
END;
$$;

CREATE FUNCTION legal_documento_version_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_linea_publicacion UUID;
BEGIN
    IF NEW.estado <> 'BORRADOR' OR NEW.estado_cambiado_en IS NOT NULL
       OR NEW.ultimo_motivo IS NOT NULL OR NEW.reemplazo_lote_id IS NOT NULL THEN
        RAISE EXCEPTION 'una version documental debe nacer BORRADOR'
            USING ERRCODE = '23514';
    END IF;
    PERFORM legal_bloquear_publicacion_abierta(NEW.publicacion_intro_id);
    SELECT publicacion_intro_id INTO v_linea_publicacion
      FROM legal_documento_lineas WHERE id = NEW.documento_linea_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'linea documental inexistente: %', NEW.documento_linea_id
            USING ERRCODE = '23503';
    END IF;
    IF v_linea_publicacion <> NEW.publicacion_intro_id THEN
        PERFORM legal_bloquear_publicacion_sellada(v_linea_publicacion);
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_requisito_version_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_linea_publicacion UUID;
BEGIN
    IF NEW.estado <> 'BORRADOR' OR NEW.estado_cambiado_en IS NOT NULL
       OR NEW.ultimo_motivo IS NOT NULL THEN
        RAISE EXCEPTION 'una version de requisito debe nacer BORRADOR'
            USING ERRCODE = '23514';
    END IF;
    PERFORM legal_bloquear_publicacion_abierta(NEW.publicacion_intro_id);
    SELECT publicacion_intro_id INTO v_linea_publicacion
      FROM legal_requisito_lineas WHERE id = NEW.requisito_linea_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'linea de requisito inexistente: %', NEW.requisito_linea_id
            USING ERRCODE = '23503';
    END IF;
    IF v_linea_publicacion <> NEW.publicacion_intro_id THEN
        PERFORM legal_bloquear_publicacion_sellada(v_linea_publicacion);
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_version_update_interno_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'el estado legal solo puede ser materializado por el trigger de transicion'
            USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'legal_documento_versiones' THEN
        IF (to_jsonb(NEW) - ARRAY['estado', 'estado_cambiado_en', 'ultimo_motivo', 'reemplazo_lote_id'])
            IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['estado', 'estado_cambiado_en', 'ultimo_motivo', 'reemplazo_lote_id']) THEN
            RAISE EXCEPTION 'los campos intrinsecos de una version documental son inmutables'
                USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM legal_documento_transiciones t
             WHERE t.documento_version_id = NEW.id
               AND t.estado_anterior = OLD.estado
               AND t.estado_nuevo = NEW.estado
               AND t.ocurrido_en = NEW.estado_cambiado_en
               AND t.motivo IS NOT DISTINCT FROM NEW.ultimo_motivo
               AND t.reemplazo_lote_id IS NOT DISTINCT FROM NEW.reemplazo_lote_id
        ) THEN
            RAISE EXCEPTION 'el estado documental solo cambia mediante una transicion de esta transaccion'
                USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'legal_requisito_versiones' THEN
        IF (to_jsonb(NEW) - ARRAY['estado', 'estado_cambiado_en', 'ultimo_motivo'])
            IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['estado', 'estado_cambiado_en', 'ultimo_motivo']) THEN
            RAISE EXCEPTION 'los campos intrinsecos de una version de requisito son inmutables'
                USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM legal_requisito_transiciones t
             WHERE t.requisito_version_id = NEW.id
               AND t.estado_anterior = OLD.estado
               AND t.estado_nuevo = NEW.estado
               AND t.ocurrido_en = NEW.estado_cambiado_en
               AND t.motivo IS NOT DISTINCT FROM NEW.ultimo_motivo
        ) THEN
            RAISE EXCEPTION 'el estado de requisito solo cambia mediante una transicion de esta transaccion'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'guarda de version aplicada a tabla inesperada: %', TG_TABLE_NAME
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_legal_publicacion_insert_guard
    BEFORE INSERT ON legal_publicaciones
    FOR EACH ROW EXECUTE FUNCTION legal_publicacion_insert_guard();
CREATE TRIGGER trg_legal_publicacion_update_statement
    BEFORE UPDATE ON legal_publicaciones
    FOR EACH STATEMENT EXECUTE FUNCTION legal_publicacion_update_statement_guard();
CREATE TRIGGER trg_legal_publicacion_update_guard
    BEFORE UPDATE ON legal_publicaciones
    FOR EACH ROW EXECUTE FUNCTION legal_publicacion_update_guard();
CREATE TRIGGER trg_legal_publicacion_no_delete
    BEFORE DELETE ON legal_publicaciones
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE CONSTRAINT TRIGGER ct_legal_publicacion_sello
    AFTER UPDATE ON legal_publicaciones
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.estado_construccion = 'SELLADO')
    EXECUTE FUNCTION legal_publicacion_constraint_guard();

CREATE TRIGGER trg_legal_documento_linea_publicacion
    BEFORE INSERT ON legal_documento_lineas
    FOR EACH ROW EXECUTE FUNCTION legal_exigir_publicacion_abierta_columna('publicacion_intro_id');
CREATE TRIGGER trg_legal_documento_linea_inmutable
    BEFORE UPDATE OR DELETE ON legal_documento_lineas
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_documento_version_insert
    BEFORE INSERT ON legal_documento_versiones
    FOR EACH ROW EXECUTE FUNCTION legal_documento_version_insert_guard();
CREATE TRIGGER trg_legal_documento_version_update
    BEFORE UPDATE ON legal_documento_versiones
    FOR EACH ROW EXECUTE FUNCTION legal_version_update_interno_guard();
CREATE TRIGGER trg_legal_documento_version_no_delete
    BEFORE DELETE ON legal_documento_versiones
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_documento_contexto_publicacion
    BEFORE INSERT ON legal_documento_contextos
    FOR EACH ROW EXECUTE FUNCTION legal_exigir_publicacion_doc_contexto_abierta();
CREATE TRIGGER trg_legal_documento_contexto_inmutable
    BEFORE UPDATE OR DELETE ON legal_documento_contextos
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_pub_documento_publicacion
    BEFORE INSERT ON legal_publicacion_documentos
    FOR EACH ROW EXECUTE FUNCTION legal_exigir_publicacion_abierta_columna('publicacion_id');
CREATE TRIGGER trg_legal_pub_documento_inmutable
    BEFORE UPDATE OR DELETE ON legal_publicacion_documentos
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();

CREATE TRIGGER trg_legal_requisito_linea_publicacion
    BEFORE INSERT ON legal_requisito_lineas
    FOR EACH ROW EXECUTE FUNCTION legal_exigir_publicacion_abierta_columna('publicacion_intro_id');
CREATE TRIGGER trg_legal_requisito_linea_inmutable
    BEFORE UPDATE OR DELETE ON legal_requisito_lineas
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_requisito_audiencia_publicacion
    BEFORE INSERT ON legal_requisito_audiencias
    FOR EACH ROW EXECUTE FUNCTION legal_exigir_publicacion_req_audiencia_abierta();
CREATE TRIGGER trg_legal_requisito_audiencia_inmutable
    BEFORE UPDATE OR DELETE ON legal_requisito_audiencias
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_requisito_version_insert
    BEFORE INSERT ON legal_requisito_versiones
    FOR EACH ROW EXECUTE FUNCTION legal_requisito_version_insert_guard();
CREATE TRIGGER trg_legal_requisito_version_update
    BEFORE UPDATE ON legal_requisito_versiones
    FOR EACH ROW EXECUTE FUNCTION legal_version_update_interno_guard();
CREATE TRIGGER trg_legal_requisito_version_no_delete
    BEFORE DELETE ON legal_requisito_versiones
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_requisito_documento_publicacion
    BEFORE INSERT ON legal_requisito_documentos
    FOR EACH ROW EXECUTE FUNCTION legal_exigir_publicacion_req_documento_abierta();
CREATE TRIGGER trg_legal_requisito_documento_inmutable
    BEFORE UPDATE OR DELETE ON legal_requisito_documentos
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_pub_requisito_publicacion
    BEFORE INSERT ON legal_publicacion_requisitos
    FOR EACH ROW EXECUTE FUNCTION legal_exigir_publicacion_abierta_columna('publicacion_id');
CREATE TRIGGER trg_legal_pub_requisito_inmutable
    BEFORE UPDATE OR DELETE ON legal_publicacion_requisitos
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_requisito_conjunto_publicacion
    BEFORE INSERT ON legal_requisito_conjuntos
    FOR EACH ROW EXECUTE FUNCTION legal_exigir_publicacion_abierta_columna('publicacion_id');
CREATE TRIGGER trg_legal_requisito_conjunto_inmutable
    BEFORE UPDATE OR DELETE ON legal_requisito_conjuntos
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_requisito_miembro_publicacion
    BEFORE INSERT ON legal_requisito_conjunto_miembros
    FOR EACH ROW EXECUTE FUNCTION legal_exigir_publicacion_abierta_columna('publicacion_id');
CREATE TRIGGER trg_legal_requisito_miembro_inmutable
    BEFORE UPDATE OR DELETE ON legal_requisito_conjunto_miembros
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();

-- ------------------------------------------------------------
-- Transiciones auditadas, linaje y proyecciones vigentes
-- ------------------------------------------------------------

CREATE FUNCTION legal_documento_transicion_before_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_version       legal_documento_versiones%ROWTYPE;
    v_max_publicado INTEGER;
    v_lote_estado   VARCHAR(10);
BEGIN
    SELECT * INTO v_version
      FROM legal_documento_versiones
     WHERE id = NEW.documento_version_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'version documental inexistente: %', NEW.documento_version_id
            USING ERRCODE = '23503';
    END IF;

    IF NEW.estado_anterior <> v_version.estado THEN
        RAISE EXCEPTION 'estado documental desactualizado para %: esperado %, recibido %',
            NEW.documento_version_id, v_version.estado, NEW.estado_anterior
            USING ERRCODE = '40001';
    END IF;

    PERFORM legal_bloquear_publicacion_sellada(v_version.publicacion_intro_id);

    IF NEW.ocurrido_en > transaction_timestamp() THEN
        RAISE EXCEPTION 'una transicion documental no puede ocurrir en el futuro'
            USING ERRCODE = '23514';
    END IF;
    IF v_version.estado_cambiado_en IS NOT NULL
       AND NEW.ocurrido_en < v_version.estado_cambiado_en THEN
        RAISE EXCEPTION 'el instante de transicion documental no puede retroceder'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.estado_anterior = 'BORRADOR' AND NEW.estado_nuevo = 'PUBLICADA' THEN
        PERFORM 1 FROM legal_documento_lineas
         WHERE id = v_version.documento_linea_id FOR UPDATE;

        SELECT max(dv.lineage_ordinal)
          INTO v_max_publicado
          FROM legal_documento_transiciones dt
          JOIN legal_documento_versiones dv ON dv.id = dt.documento_version_id
         WHERE dv.documento_linea_id = v_version.documento_linea_id
           AND dt.estado_nuevo = 'PUBLICADA';

        IF v_max_publicado IS NOT NULL
           AND v_version.lineage_ordinal <= v_max_publicado THEN
            RAISE EXCEPTION 'ordinal documental % no supera el maximo publicado % para la linea %',
                v_version.lineage_ordinal, v_max_publicado, v_version.documento_linea_id
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.estado_nuevo = 'VIGENTE'
       AND (v_version.vigente_desde > transaction_timestamp()
            OR NEW.ocurrido_en < v_version.vigente_desde) THEN
        RAISE EXCEPTION 'la transicion de la version documental % es anterior a vigente_desde',
            NEW.documento_version_id USING ERRCODE = '23514';
    END IF;

    IF NEW.reemplazo_lote_id IS NOT NULL THEN
        SELECT estado_construccion
          INTO v_lote_estado
          FROM legal_documento_reemplazo_lotes lote
         WHERE lote.id = NEW.reemplazo_lote_id
           AND lote.estado_construccion = 'SELLADO';
        IF pg_trigger_depth() < 2 OR NOT FOUND OR v_lote_estado <> 'SELLADO' THEN
            RAISE EXCEPTION 'las transiciones de reemplazo solo nacen al sellar el lote en esta transaccion'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.estado_anterior = 'VIGENTE' AND NEW.estado_nuevo = 'REEMPLAZADA'
           AND NOT EXISTS (
               SELECT 1 FROM legal_documento_reemplazo_anteriores a
                WHERE a.lote_id = NEW.reemplazo_lote_id
                  AND a.documento_version_id = NEW.documento_version_id
           ) THEN
            RAISE EXCEPTION 'la version anterior no pertenece al lote de reemplazo'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.estado_anterior = 'PUBLICADA' AND NEW.estado_nuevo = 'VIGENTE'
           AND NOT EXISTS (
               SELECT 1 FROM legal_documento_reemplazo_sucesoras s
                WHERE s.lote_id = NEW.reemplazo_lote_id
                  AND s.documento_version_id = NEW.documento_version_id
           ) THEN
            RAISE EXCEPTION 'la version sucesora no pertenece al lote de reemplazo'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.estado_nuevo = 'REEMPLAZADA' THEN
        RAISE EXCEPTION 'REEMPLAZADA requiere un lote de reemplazo sellado atomicamente'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_documento_transicion_after_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE legal_documento_versiones
       SET estado = NEW.estado_nuevo,
           estado_cambiado_en = NEW.ocurrido_en,
           ultimo_motivo = NEW.motivo,
           reemplazo_lote_id = NEW.reemplazo_lote_id
     WHERE id = NEW.documento_version_id;

    IF NEW.estado_nuevo IN ('REEMPLAZADA', 'RETIRADA') THEN
        DELETE FROM legal_requisito_conjuntos_actuales actual
         WHERE EXISTS (
             SELECT 1
               FROM legal_requisito_conjunto_miembros miembro
               JOIN legal_requisito_documentos rd
                 ON rd.requisito_version_id = miembro.requisito_version_id
              WHERE miembro.conjunto_id = actual.conjunto_id
                AND rd.documento_version_id = NEW.documento_version_id
         );
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION legal_requisito_transicion_before_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_version       legal_requisito_versiones%ROWTYPE;
    v_max_publicado INTEGER;
BEGIN
    SELECT * INTO v_version
      FROM legal_requisito_versiones
     WHERE id = NEW.requisito_version_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'version de requisito inexistente: %', NEW.requisito_version_id
            USING ERRCODE = '23503';
    END IF;

    IF NEW.estado_anterior <> v_version.estado THEN
        RAISE EXCEPTION 'estado de requisito desactualizado para %: esperado %, recibido %',
            NEW.requisito_version_id, v_version.estado, NEW.estado_anterior
            USING ERRCODE = '40001';
    END IF;
    PERFORM legal_bloquear_publicacion_sellada(v_version.publicacion_intro_id);

    IF NEW.ocurrido_en > transaction_timestamp() THEN
        RAISE EXCEPTION 'una transicion de requisito no puede ocurrir en el futuro'
            USING ERRCODE = '23514';
    END IF;
    IF v_version.estado_cambiado_en IS NOT NULL
       AND NEW.ocurrido_en < v_version.estado_cambiado_en THEN
        RAISE EXCEPTION 'el instante de transicion de requisito no puede retroceder'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.estado_anterior = 'BORRADOR' AND NEW.estado_nuevo = 'PUBLICADA' THEN
        PERFORM 1 FROM legal_requisito_lineas
         WHERE id = v_version.requisito_linea_id FOR UPDATE;

        SELECT max(rv.lineage_ordinal)
          INTO v_max_publicado
          FROM legal_requisito_transiciones rt
          JOIN legal_requisito_versiones rv ON rv.id = rt.requisito_version_id
         WHERE rv.requisito_linea_id = v_version.requisito_linea_id
           AND rt.estado_nuevo = 'PUBLICADA';

        IF v_max_publicado IS NOT NULL
           AND v_version.lineage_ordinal <= v_max_publicado THEN
            RAISE EXCEPTION 'ordinal de requisito % no supera el maximo publicado % para la linea %',
                v_version.lineage_ordinal, v_max_publicado, v_version.requisito_linea_id
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_requisito_transicion_after_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE legal_requisito_versiones
       SET estado = NEW.estado_nuevo,
           estado_cambiado_en = NEW.ocurrido_en,
           ultimo_motivo = NEW.motivo
     WHERE id = NEW.requisito_version_id;

    IF NEW.estado_nuevo IN ('REEMPLAZADA', 'RETIRADA') THEN
        DELETE FROM legal_requisito_conjuntos_actuales actual
         WHERE EXISTS (
             SELECT 1 FROM legal_requisito_conjunto_miembros miembro
              WHERE miembro.conjunto_id = actual.conjunto_id
                AND miembro.requisito_version_id = NEW.requisito_version_id
         );
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION legal_documento_slot_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM legal_bloquear_publicacion_sellada(NEW.publicacion_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_validar_slots_documentales()
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM legal_documento_versiones dv
         WHERE dv.estado = 'VIGENTE'
           AND EXISTS (
               (SELECT dc.contexto
                  FROM legal_documento_contextos dc
                 WHERE dc.documento_version_id = dv.id)
               EXCEPT
               (SELECT vigente.contexto
                  FROM legal_documento_vigentes vigente
                 WHERE vigente.documento_version_id = dv.id)
           )
    ) OR EXISTS (
        SELECT 1
          FROM legal_documento_versiones dv
         WHERE dv.estado = 'VIGENTE'
           AND EXISTS (
               (SELECT vigente.contexto
                  FROM legal_documento_vigentes vigente
                 WHERE vigente.documento_version_id = dv.id)
               EXCEPT
               (SELECT dc.contexto
                  FROM legal_documento_contextos dc
                 WHERE dc.documento_version_id = dv.id)
           )
    ) THEN
        RAISE EXCEPTION 'cada documento VIGENTE debe ocupar exactamente todos sus contextos'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_documento_vigentes vigente
          JOIN legal_documento_versiones dv ON dv.id = vigente.documento_version_id
         WHERE dv.estado <> 'VIGENTE'
    ) THEN
        RAISE EXCEPTION 'un documento no vigente conserva slots activos'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION legal_slots_constraint_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM legal_validar_slots_documentales();
    RETURN NULL;
END;
$$;

CREATE FUNCTION legal_requisito_actual_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_revision VARCHAR(71);
BEGIN
    PERFORM legal_bloquear_publicacion_sellada(NEW.publicacion_id);
    SELECT required_set_revision INTO v_revision
      FROM legal_requisito_conjuntos
     WHERE id = NEW.conjunto_id
       AND publicacion_id = NEW.publicacion_id
       AND locale = NEW.locale
       AND contexto = NEW.contexto
       AND audiencia = NEW.audiencia;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'snapshot actual no coincide con su scope'
            USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_validar_conjuntos_actuales()
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM legal_requisito_conjuntos_actuales actual
          JOIN legal_requisito_conjunto_miembros miembro
            ON miembro.conjunto_id = actual.conjunto_id
          JOIN legal_requisito_versiones rv ON rv.id = miembro.requisito_version_id
         WHERE miembro.publicacion_id <> actual.publicacion_id
            OR rv.estado <> 'VIGENTE'
    ) THEN
        RAISE EXCEPTION 'un snapshot actual mezcla publicaciones o requisitos no vigentes'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_requisito_conjuntos_actuales actual
          JOIN legal_requisito_conjunto_miembros miembro
            ON miembro.conjunto_id = actual.conjunto_id
          JOIN legal_requisito_documentos rd
            ON rd.requisito_version_id = miembro.requisito_version_id
          JOIN legal_documento_versiones dv ON dv.id = rd.documento_version_id
          JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
         WHERE dv.estado <> 'VIGENTE'
            OR NOT EXISTS (
                SELECT 1 FROM legal_documento_vigentes vigente
                 WHERE vigente.documento_version_id = dv.id
                   AND vigente.contexto = actual.contexto
                   AND vigente.tipo = dl.tipo
                   AND vigente.locale = actual.locale
                   AND vigente.publicacion_id = actual.publicacion_id
            )
            OR NOT EXISTS (
                SELECT 1 FROM legal_publicacion_documentos pd
                 WHERE pd.publicacion_id = actual.publicacion_id
                   AND pd.documento_version_id = dv.id
            )
    ) THEN
        RAISE EXCEPTION 'un snapshot actual referencia documentos no vigentes o ajenos'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_requisito_conjuntos_actuales actual
         WHERE actual.contexto = 'REGISTRO'
           AND NOT EXISTS (
               SELECT 1
                 FROM legal_requisito_conjunto_miembros miembro
                 JOIN legal_requisito_versiones rv ON rv.id = miembro.requisito_version_id
                WHERE miembro.conjunto_id = actual.conjunto_id
                  AND rv.requerido
           )
    ) THEN
        RAISE EXCEPTION 'REGISTRO requiere al menos un requisito obligatorio vigente'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT rv.id
          FROM legal_requisito_versiones rv
         WHERE rv.estado = 'VIGENTE'
           AND EXISTS (
               SELECT 1 FROM legal_requisito_conjunto_miembros m
               JOIN legal_requisito_conjuntos_actuales a ON a.conjunto_id = m.conjunto_id
               WHERE m.requisito_version_id = rv.id
           )
           AND (
               SELECT count(*)
                 FROM legal_requisito_audiencias ra
                WHERE ra.requisito_linea_id = rv.requisito_linea_id
           ) <> (
               SELECT count(*)
                 FROM legal_requisito_conjunto_miembros m
                 JOIN legal_requisito_conjuntos_actuales a ON a.conjunto_id = m.conjunto_id
                WHERE m.requisito_version_id = rv.id
           )
    ) THEN
        RAISE EXCEPTION 'la promocion de requisitos debe cubrir todas sus audiencias atomicamente'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION legal_conjuntos_actuales_constraint_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM legal_validar_conjuntos_actuales();
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_legal_documento_transicion_isolation
    BEFORE INSERT ON legal_documento_transiciones
    FOR EACH STATEMENT EXECUTE FUNCTION legal_read_committed_statement_guard();
CREATE TRIGGER trg_legal_documento_transicion_before
    BEFORE INSERT ON legal_documento_transiciones
    FOR EACH ROW EXECUTE FUNCTION legal_documento_transicion_before_insert();
CREATE TRIGGER trg_legal_documento_transicion_after
    AFTER INSERT ON legal_documento_transiciones
    FOR EACH ROW EXECUTE FUNCTION legal_documento_transicion_after_insert();
CREATE TRIGGER trg_legal_documento_transicion_inmutable
    BEFORE UPDATE OR DELETE ON legal_documento_transiciones
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_requisito_transicion_isolation
    BEFORE INSERT ON legal_requisito_transiciones
    FOR EACH STATEMENT EXECUTE FUNCTION legal_read_committed_statement_guard();
CREATE TRIGGER trg_legal_requisito_transicion_before
    BEFORE INSERT ON legal_requisito_transiciones
    FOR EACH ROW EXECUTE FUNCTION legal_requisito_transicion_before_insert();
CREATE TRIGGER trg_legal_requisito_transicion_after
    AFTER INSERT ON legal_requisito_transiciones
    FOR EACH ROW EXECUTE FUNCTION legal_requisito_transicion_after_insert();
CREATE TRIGGER trg_legal_requisito_transicion_inmutable
    BEFORE UPDATE OR DELETE ON legal_requisito_transiciones
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();

CREATE TRIGGER trg_legal_documento_slot_insert
    BEFORE INSERT ON legal_documento_vigentes
    FOR EACH ROW EXECUTE FUNCTION legal_documento_slot_insert_guard();
CREATE TRIGGER trg_legal_documento_slot_no_update
    BEFORE UPDATE ON legal_documento_vigentes
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE CONSTRAINT TRIGGER ct_legal_documento_slot_insert
    AFTER INSERT ON legal_documento_vigentes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_slots_constraint_guard();
CREATE CONSTRAINT TRIGGER ct_legal_documento_slot_delete
    AFTER DELETE ON legal_documento_vigentes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_slots_constraint_guard();
CREATE CONSTRAINT TRIGGER ct_legal_documento_estado_slots
    AFTER INSERT ON legal_documento_transiciones
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_slots_constraint_guard();

CREATE TRIGGER trg_legal_requisito_actual_insert
    BEFORE INSERT ON legal_requisito_conjuntos_actuales
    FOR EACH ROW EXECUTE FUNCTION legal_requisito_actual_insert_guard();
CREATE TRIGGER trg_legal_requisito_actual_no_update
    BEFORE UPDATE ON legal_requisito_conjuntos_actuales
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE CONSTRAINT TRIGGER ct_legal_requisito_actual_insert
    AFTER INSERT ON legal_requisito_conjuntos_actuales
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_conjuntos_actuales_constraint_guard();
CREATE CONSTRAINT TRIGGER ct_legal_requisito_actual_delete
    AFTER DELETE ON legal_requisito_conjuntos_actuales
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_conjuntos_actuales_constraint_guard();
CREATE CONSTRAINT TRIGGER ct_legal_requisito_estado_actual
    AFTER INSERT ON legal_requisito_transiciones
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_conjuntos_actuales_constraint_guard();
CREATE CONSTRAINT TRIGGER ct_legal_documento_estado_requisitos
    AFTER INSERT ON legal_documento_transiciones
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_conjuntos_actuales_constraint_guard();

-- ------------------------------------------------------------
-- Reemplazos documentales split/merge
-- ------------------------------------------------------------

CREATE FUNCTION legal_reemplazo_lote_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.estado_construccion <> 'ABIERTO' OR NEW.sellado_en IS NOT NULL THEN
        RAISE EXCEPTION 'un lote de reemplazo debe nacer ABIERTO y sin sello'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_reemplazo_miembro_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_estado VARCHAR(10);
BEGIN
    PERFORM legal_exigir_read_committed();

    SELECT estado_construccion INTO v_estado
      FROM legal_documento_reemplazo_lotes
     WHERE id = NEW.lote_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'lote de reemplazo inexistente: %', NEW.lote_id
            USING ERRCODE = '23503';
    END IF;
    IF v_estado <> 'ABIERTO' THEN
        RAISE EXCEPTION 'el lote de reemplazo % ya esta sellado', NEW.lote_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_validar_reemplazo_estructura(p_lote_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM legal_documento_reemplazo_anteriores a WHERE a.lote_id = p_lote_id
    ) OR NOT EXISTS (
        SELECT 1 FROM legal_documento_reemplazo_sucesoras s WHERE s.lote_id = p_lote_id
    ) THEN
        RAISE EXCEPTION 'el lote de reemplazo % requiere anteriores y sucesoras', p_lote_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_documento_reemplazo_anteriores a
          JOIN legal_documento_reemplazo_sucesoras s
            ON s.lote_id = a.lote_id
           AND s.documento_version_id = a.documento_version_id
         WHERE a.lote_id = p_lote_id
    ) THEN
        RAISE EXCEPTION 'el lote de reemplazo % contiene un autociclo', p_lote_id
            USING ERRCODE = '23514';
    END IF;

    IF (
        SELECT count(DISTINCT dl.tipo || '|' || dl.locale)
          FROM (
                SELECT documento_version_id FROM legal_documento_reemplazo_anteriores
                 WHERE lote_id = p_lote_id
                UNION ALL
                SELECT documento_version_id FROM legal_documento_reemplazo_sucesoras
                 WHERE lote_id = p_lote_id
          ) miembros
          JOIN legal_documento_versiones dv ON dv.id = miembros.documento_version_id
          JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
    ) <> 1 THEN
        RAISE EXCEPTION 'el lote de reemplazo % mezcla tipo o locale', p_lote_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT dc.contexto
          FROM legal_documento_reemplazo_anteriores a
          JOIN legal_documento_contextos dc ON dc.documento_version_id = a.documento_version_id
         WHERE a.lote_id = p_lote_id
         GROUP BY dc.contexto HAVING count(*) > 1
    ) OR EXISTS (
        SELECT dc.contexto
          FROM legal_documento_reemplazo_sucesoras s
          JOIN legal_documento_contextos dc ON dc.documento_version_id = s.documento_version_id
         WHERE s.lote_id = p_lote_id
         GROUP BY dc.contexto HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'el lote de reemplazo % contiene contextos solapados', p_lote_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        (SELECT dc.contexto
           FROM legal_documento_reemplazo_anteriores a
           JOIN legal_documento_contextos dc ON dc.documento_version_id = a.documento_version_id
          WHERE a.lote_id = p_lote_id)
        EXCEPT
        (SELECT dc.contexto
           FROM legal_documento_reemplazo_sucesoras s
           JOIN legal_documento_contextos dc ON dc.documento_version_id = s.documento_version_id
          WHERE s.lote_id = p_lote_id)
    ) OR EXISTS (
        (SELECT dc.contexto
           FROM legal_documento_reemplazo_sucesoras s
           JOIN legal_documento_contextos dc ON dc.documento_version_id = s.documento_version_id
          WHERE s.lote_id = p_lote_id)
        EXCEPT
        (SELECT dc.contexto
           FROM legal_documento_reemplazo_anteriores a
           JOIN legal_documento_contextos dc ON dc.documento_version_id = a.documento_version_id
          WHERE a.lote_id = p_lote_id)
    ) THEN
        RAISE EXCEPTION 'el lote de reemplazo % no cubre exactamente los mismos contextos', p_lote_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION legal_reemplazo_lote_before_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_version_id UUID;
BEGIN
    IF (to_jsonb(NEW) - ARRAY['estado_construccion', 'sellado_en'])
        IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['estado_construccion', 'sellado_en']) THEN
        RAISE EXCEPTION 'la cabecera del lote de reemplazo es inmutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.estado_construccion <> 'ABIERTO'
       OR NEW.estado_construccion <> 'SELLADO'
       OR OLD.sellado_en IS NOT NULL THEN
        RAISE EXCEPTION 'transicion invalida del lote de reemplazo: % -> %',
            OLD.estado_construccion, NEW.estado_construccion
            USING ERRCODE = '23514';
    END IF;
    NEW.sellado_en := COALESCE(NEW.sellado_en, transaction_timestamp());
    IF NEW.sellado_en < NEW.creado_en OR NEW.sellado_en > transaction_timestamp() THEN
        RAISE EXCEPTION 'instante de sello de reemplazo invalido'
            USING ERRCODE = '23514';
    END IF;

    -- El UPDATE del lote es incompatible con el FOR SHARE de cada insert de miembro.
    -- Las versiones se bloquean en orden UUID para serializar lotes superpuestos.
    FOR v_version_id IN
        SELECT documento_version_id
          FROM (
                SELECT documento_version_id FROM legal_documento_reemplazo_anteriores
                 WHERE lote_id = NEW.id
                UNION
                SELECT documento_version_id FROM legal_documento_reemplazo_sucesoras
                 WHERE lote_id = NEW.id
          ) versiones
         ORDER BY documento_version_id
    LOOP
        PERFORM 1 FROM legal_documento_versiones
         WHERE id = v_version_id FOR UPDATE;
    END LOOP;

    PERFORM legal_validar_reemplazo_estructura(NEW.id);

    IF EXISTS (
        SELECT 1
          FROM legal_documento_reemplazo_anteriores a
          JOIN legal_documento_versiones dv ON dv.id = a.documento_version_id
         WHERE a.lote_id = NEW.id AND dv.estado <> 'VIGENTE'
    ) THEN
        RAISE EXCEPTION 'todas las versiones anteriores deben estar VIGENTE'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM legal_documento_reemplazo_sucesoras s
          JOIN legal_documento_versiones dv ON dv.id = s.documento_version_id
         WHERE s.lote_id = NEW.id AND dv.estado <> 'PUBLICADA'
    ) THEN
        RAISE EXCEPTION 'todas las versiones sucesoras deben estar PUBLICADA'
            USING ERRCODE = '23514';
    END IF;

    FOR v_version_id IN
        SELECT DISTINCT s.publicacion_id
          FROM legal_documento_reemplazo_sucesoras s
         WHERE s.lote_id = NEW.id
         ORDER BY s.publicacion_id
    LOOP
        PERFORM legal_bloquear_publicacion_sellada(v_version_id);
    END LOOP;

    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_reemplazo_lote_after_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM legal_documento_vigentes vigente
     WHERE EXISTS (
         SELECT 1 FROM legal_documento_reemplazo_anteriores a
          WHERE a.lote_id = NEW.id
            AND a.documento_version_id = vigente.documento_version_id
     );

    INSERT INTO legal_documento_transiciones
        (documento_version_id, estado_anterior, estado_nuevo, motivo,
         reemplazo_lote_id, ocurrido_en)
    SELECT s.documento_version_id, 'PUBLICADA', 'VIGENTE', NULL, NEW.id, NEW.sellado_en
      FROM legal_documento_reemplazo_sucesoras s
     WHERE s.lote_id = NEW.id
     ORDER BY s.documento_version_id;

    INSERT INTO legal_documento_transiciones
        (documento_version_id, estado_anterior, estado_nuevo, motivo,
         reemplazo_lote_id, ocurrido_en)
    SELECT a.documento_version_id, 'VIGENTE', 'REEMPLAZADA', NULL, NEW.id, NEW.sellado_en
      FROM legal_documento_reemplazo_anteriores a
     WHERE a.lote_id = NEW.id
     ORDER BY a.documento_version_id;

    INSERT INTO legal_documento_vigentes
        (tipo, locale, contexto, documento_version_id, documento_linea_id,
         publicacion_id, estado_documento)
    SELECT dl.tipo, dl.locale, dc.contexto, s.documento_version_id,
           dv.documento_linea_id, s.publicacion_id, 'VIGENTE'
      FROM legal_documento_reemplazo_sucesoras s
      JOIN legal_documento_versiones dv ON dv.id = s.documento_version_id
      JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
      JOIN legal_documento_contextos dc ON dc.documento_version_id = dv.id
     WHERE s.lote_id = NEW.id
     ORDER BY dl.tipo, dl.locale, dc.contexto;

    RETURN NULL;
END;
$$;

CREATE FUNCTION legal_reemplazo_constraint_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM legal_validar_reemplazo_estructura(NEW.id);

    IF EXISTS (
        SELECT 1 FROM legal_documento_reemplazo_anteriores a
        JOIN legal_documento_versiones dv ON dv.id = a.documento_version_id
        WHERE a.lote_id = NEW.id
          AND (dv.estado <> 'REEMPLAZADA' OR dv.reemplazo_lote_id <> NEW.id)
    ) OR EXISTS (
        SELECT 1 FROM legal_documento_reemplazo_sucesoras s
        JOIN legal_documento_versiones dv ON dv.id = s.documento_version_id
        WHERE s.lote_id = NEW.id
          AND (dv.estado <> 'VIGENTE' OR dv.reemplazo_lote_id <> NEW.id)
    ) THEN
        RAISE EXCEPTION 'el lote % no dejo estados finales coherentes', NEW.id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1 FROM legal_documento_reemplazo_anteriores a
         WHERE a.lote_id = NEW.id
           AND NOT EXISTS (
               SELECT 1 FROM legal_documento_transiciones t
                WHERE t.documento_version_id = a.documento_version_id
                  AND t.reemplazo_lote_id = NEW.id
                  AND t.estado_anterior = 'VIGENTE'
                  AND t.estado_nuevo = 'REEMPLAZADA'
           )
    ) OR EXISTS (
        SELECT 1 FROM legal_documento_reemplazo_sucesoras s
         WHERE s.lote_id = NEW.id
           AND NOT EXISTS (
               SELECT 1 FROM legal_documento_transiciones t
                WHERE t.documento_version_id = s.documento_version_id
                  AND t.reemplazo_lote_id = NEW.id
                  AND t.estado_anterior = 'PUBLICADA'
                  AND t.estado_nuevo = 'VIGENTE'
           )
    ) THEN
        RAISE EXCEPTION 'el lote % no posee todas sus transiciones enlazadas', NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_legal_reemplazo_lote_insert
    BEFORE INSERT ON legal_documento_reemplazo_lotes
    FOR EACH ROW EXECUTE FUNCTION legal_reemplazo_lote_insert_guard();
CREATE TRIGGER trg_legal_reemplazo_lote_update_isolation
    BEFORE UPDATE ON legal_documento_reemplazo_lotes
    FOR EACH STATEMENT EXECUTE FUNCTION legal_read_committed_statement_guard();
CREATE TRIGGER trg_legal_reemplazo_lote_before_update
    BEFORE UPDATE ON legal_documento_reemplazo_lotes
    FOR EACH ROW EXECUTE FUNCTION legal_reemplazo_lote_before_update();
CREATE TRIGGER trg_legal_reemplazo_lote_after_update
    AFTER UPDATE ON legal_documento_reemplazo_lotes
    FOR EACH ROW EXECUTE FUNCTION legal_reemplazo_lote_after_update();
CREATE TRIGGER trg_legal_reemplazo_lote_no_delete
    BEFORE DELETE ON legal_documento_reemplazo_lotes
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE CONSTRAINT TRIGGER ct_legal_reemplazo_lote_sello
    AFTER UPDATE ON legal_documento_reemplazo_lotes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.estado_construccion = 'SELLADO')
    EXECUTE FUNCTION legal_reemplazo_constraint_guard();
CREATE TRIGGER trg_legal_reemplazo_anterior_insert
    BEFORE INSERT ON legal_documento_reemplazo_anteriores
    FOR EACH ROW EXECUTE FUNCTION legal_reemplazo_miembro_insert_guard();
CREATE TRIGGER trg_legal_reemplazo_anterior_inmutable
    BEFORE UPDATE OR DELETE ON legal_documento_reemplazo_anteriores
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_reemplazo_sucesora_insert
    BEFORE INSERT ON legal_documento_reemplazo_sucesoras
    FOR EACH ROW EXECUTE FUNCTION legal_reemplazo_miembro_insert_guard();
CREATE TRIGGER trg_legal_reemplazo_sucesora_inmutable
    BEFORE UPDATE OR DELETE ON legal_documento_reemplazo_sucesoras
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();

-- ------------------------------------------------------------
-- Evidencia exacta, agregado atomico y metadata tombstone
-- ------------------------------------------------------------

CREATE FUNCTION legal_fila_es_transaccion_actual(p_xmin XID)
RETURNS BOOLEAN
LANGUAGE sql
VOLATILE
AS $$
    SELECT p_xmin::TEXT::NUMERIC =
           mod(pg_current_xact_id()::TEXT::NUMERIC, 4294967296::NUMERIC)
$$;

CREATE FUNCTION legal_aceptacion_lote_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_rol VARCHAR(255);
BEGIN
    -- La evidencia usa una unica hora autoritativa de PostgreSQL. No se acepta una
    -- fecha aportada por el cliente o por el reloj de una replica de aplicacion.
    NEW.aceptado_en := transaction_timestamp();

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
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_aceptacion_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_lote_audiencia  VARCHAR(20);
    v_lote_revision   VARCHAR(71);
    v_lote_xmin       XID;
    v_locale          VARCHAR(5);
    v_contexto        VARCHAR(40);
    v_conjunto_id     UUID;
    v_revision_actual VARCHAR(71);
BEGIN
    SELECT lote.audiencia, lote.required_set_revision, lote.xmin
      INTO v_lote_audiencia, v_lote_revision, v_lote_xmin
      FROM legal_aceptacion_lotes lote
     WHERE lote.id = NEW.lote_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'lote de aceptacion inexistente: %', NEW.lote_id
            USING ERRCODE = '23503';
    END IF;
    IF NOT legal_fila_es_transaccion_actual(v_lote_xmin) THEN
        RAISE EXCEPTION 'no se pueden agregar aceptaciones a un lote ya confirmado'
            USING ERRCODE = '23514';
    END IF;

    SELECT rl.locale, rl.contexto
      INTO v_locale, v_contexto
      FROM legal_requisito_versiones rv
      JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
     WHERE rv.id = NEW.requisito_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'version de requisito inexistente: %', NEW.requisito_version_id
            USING ERRCODE = '23503';
    END IF;

    SELECT actual.conjunto_id, conjunto.required_set_revision
      INTO v_conjunto_id, v_revision_actual
      FROM legal_requisito_conjuntos_actuales actual
      JOIN legal_requisito_conjuntos conjunto ON conjunto.id = actual.conjunto_id
     WHERE actual.locale = v_locale
       AND actual.contexto = v_contexto
       AND actual.audiencia = v_lote_audiencia
     FOR SHARE OF actual;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'no existe snapshot legal actual para la aceptacion'
            USING ERRCODE = '23514';
    END IF;
    IF v_revision_actual <> v_lote_revision THEN
        RAISE EXCEPTION 'required_set_revision ya no coincide con el snapshot actual'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM legal_requisito_conjunto_miembros miembro
         WHERE miembro.conjunto_id = v_conjunto_id
           AND miembro.requisito_version_id = NEW.requisito_version_id
    ) THEN
        RAISE EXCEPTION 'el requisito aceptado no pertenece al snapshot actual'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_aceptacion_documento_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_xmin XID;
BEGIN
    SELECT xmin INTO v_xmin
      FROM legal_aceptaciones
     WHERE id = NEW.aceptacion_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'aceptacion inexistente: %', NEW.aceptacion_id
            USING ERRCODE = '23503';
    END IF;
    IF NOT legal_fila_es_transaccion_actual(v_xmin) THEN
        RAISE EXCEPTION 'no se pueden agregar documentos a una aceptacion confirmada'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_metadata_header_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_xmin       XID;
    v_aceptado_en TIMESTAMP WITH TIME ZONE;
BEGIN
    SELECT xmin, aceptado_en INTO v_xmin, v_aceptado_en
      FROM legal_aceptacion_lotes
     WHERE id = NEW.lote_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'lote de aceptacion inexistente: %', NEW.lote_id
            USING ERRCODE = '23503';
    END IF;
    IF NOT legal_fila_es_transaccion_actual(v_xmin) THEN
        RAISE EXCEPTION 'la metadata debe crearse junto con el lote de aceptacion'
            USING ERRCODE = '23514';
    END IF;
    NEW.capturado_en := v_aceptado_en;
    IF NEW.retener_hasta <= transaction_timestamp() THEN
        RAISE EXCEPTION 'retener_hasta debe ser posterior a la confirmacion de la evidencia'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.purgado_en IS NOT NULL THEN
        RAISE EXCEPTION 'la metadata no puede nacer purgada'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_metadata_cifrada_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_xmin       XID;
    v_purgado_en TIMESTAMP WITH TIME ZONE;
BEGIN
    SELECT xmin, purgado_en INTO v_xmin, v_purgado_en
      FROM legal_aceptacion_metadatos
     WHERE lote_id = NEW.lote_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'cabecera de metadata inexistente: %', NEW.lote_id
            USING ERRCODE = '23503';
    END IF;
    IF v_purgado_en IS NOT NULL OR NOT legal_fila_es_transaccion_actual(v_xmin) THEN
        RAISE EXCEPTION 'los campos cifrados deben crearse junto con su cabecera'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.tombstone_en IS NOT NULL THEN
        RAISE EXCEPTION 'un campo cifrado no puede nacer como tombstone'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_validar_aceptacion(p_aceptacion_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_aceptacion legal_aceptaciones%ROWTYPE;
BEGIN
    SELECT * INTO v_aceptacion
      FROM legal_aceptaciones WHERE id = p_aceptacion_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM legal_requisito_versiones rv
          JOIN legal_requisito_lineas rl ON rl.id = rv.requisito_linea_id
          JOIN legal_aceptacion_lotes lote ON lote.id = v_aceptacion.lote_id
         WHERE rv.id = v_aceptacion.requisito_version_id
           AND rl.clave = v_aceptacion.requisito_clave
           AND rv.version = v_aceptacion.requisito_version
           AND rl.contexto = v_aceptacion.contexto
           AND rl.tipo_acto = v_aceptacion.tipo_acto
           AND rv.afirmacion = v_aceptacion.afirmacion
           AND rv.afirmacion_sha256 = v_aceptacion.afirmacion_sha256
           AND rv.requerido = v_aceptacion.requerido
           AND EXISTS (
               SELECT 1 FROM legal_requisito_audiencias ra
                WHERE ra.requisito_linea_id = rl.id
                  AND ra.audiencia = lote.audiencia
           )
    ) THEN
        RAISE EXCEPTION 'snapshot canonico invalido para aceptacion %', p_aceptacion_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        (SELECT rd.documento_version_id, rd.documento_ordinal
           FROM legal_requisito_documentos rd
          WHERE rd.requisito_version_id = v_aceptacion.requisito_version_id)
        EXCEPT
        (SELECT ad.documento_version_id, ad.documento_ordinal
           FROM legal_aceptacion_documentos ad
          WHERE ad.aceptacion_id = p_aceptacion_id)
    ) OR EXISTS (
        (SELECT ad.documento_version_id, ad.documento_ordinal
           FROM legal_aceptacion_documentos ad
          WHERE ad.aceptacion_id = p_aceptacion_id)
        EXCEPT
        (SELECT rd.documento_version_id, rd.documento_ordinal
           FROM legal_requisito_documentos rd
          WHERE rd.requisito_version_id = v_aceptacion.requisito_version_id)
    ) THEN
        RAISE EXCEPTION 'documentos incompletos o extra en aceptacion %', p_aceptacion_id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM legal_aceptacion_documentos ad
          JOIN legal_documento_versiones dv ON dv.id = ad.documento_version_id
          JOIN legal_documento_lineas dl ON dl.id = dv.documento_linea_id
         WHERE ad.aceptacion_id = p_aceptacion_id
           AND (ad.documento_clave <> dl.clave
                OR ad.tipo <> dl.tipo
                OR ad.version <> dv.version
                OR ad.titulo <> dv.titulo
                OR ad.sha256 <> dv.sha256)
    ) THEN
        RAISE EXCEPTION 'snapshot documental no canonico en aceptacion %', p_aceptacion_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION legal_validar_lote_aceptacion(p_lote_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_metadata legal_aceptacion_metadatos%ROWTYPE;
    v_id       UUID;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM legal_aceptacion_lotes WHERE id = p_lote_id) THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM legal_aceptaciones WHERE lote_id = p_lote_id) THEN
        RAISE EXCEPTION 'el lote de aceptacion % no contiene actos', p_lote_id
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO v_metadata
      FROM legal_aceptacion_metadatos WHERE lote_id = p_lote_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'el lote de aceptacion % no posee metadata tecnica', p_lote_id
            USING ERRCODE = '23514';
    END IF;

    IF v_metadata.purgado_en IS NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM legal_aceptacion_metadatos_cifrados campo
             WHERE campo.lote_id = p_lote_id
               AND campo.tipo = 'IP'
               AND campo.tombstone_en IS NULL
        ) OR EXISTS (
            SELECT 1 FROM legal_aceptacion_metadatos_cifrados campo
             WHERE campo.lote_id = p_lote_id
               AND campo.tombstone_en IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'metadata activa incompleta o parcialmente purgada para lote %', p_lote_id
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NOT EXISTS (
            SELECT 1 FROM legal_aceptacion_metadatos_cifrados campo
             WHERE campo.lote_id = p_lote_id AND campo.tipo = 'IP'
        ) OR EXISTS (
            SELECT 1 FROM legal_aceptacion_metadatos_cifrados campo
             WHERE campo.lote_id = p_lote_id AND campo.tombstone_en IS NULL
        ) THEN
            RAISE EXCEPTION 'purga incompleta para lote %', p_lote_id
                USING ERRCODE = '23514';
        END IF;
    END IF;

    FOR v_id IN SELECT id FROM legal_aceptaciones WHERE lote_id = p_lote_id LOOP
        PERFORM legal_validar_aceptacion(v_id);
    END LOOP;
END;
$$;

CREATE FUNCTION legal_aceptacion_constraint_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_aceptacion_id UUID;
    v_lote_id       UUID;
BEGIN
    IF TG_TABLE_NAME = 'legal_aceptacion_lotes' THEN
        v_lote_id := NEW.id;
    ELSIF TG_TABLE_NAME = 'legal_aceptaciones' THEN
        v_lote_id := NEW.lote_id;
        v_aceptacion_id := NEW.id;
    ELSIF TG_TABLE_NAME = 'legal_aceptacion_documentos' THEN
        SELECT a.id, a.lote_id INTO v_aceptacion_id, v_lote_id
          FROM legal_aceptaciones a WHERE a.id = NEW.aceptacion_id;
    ELSE
        v_lote_id := NEW.lote_id;
    END IF;

    IF v_aceptacion_id IS NOT NULL THEN
        PERFORM legal_validar_aceptacion(v_aceptacion_id);
    END IF;
    PERFORM legal_validar_lote_aceptacion(v_lote_id);
    RETURN NULL;
END;
$$;

CREATE FUNCTION legal_metadata_cifrada_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_retener_hasta TIMESTAMP WITH TIME ZONE;
BEGIN
    IF (to_jsonb(NEW) - ARRAY['ciphertext', 'tag', 'longitud_original', 'tombstone_en'])
        IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['ciphertext', 'tag', 'longitud_original', 'tombstone_en']) THEN
        RAISE EXCEPTION 'lote, tipo, key_version y nonce del cifrado son inmutables'
            USING ERRCODE = '23514';
    END IF;
    SELECT retener_hasta INTO v_retener_hasta
      FROM legal_aceptacion_metadatos
     WHERE lote_id = OLD.lote_id
     FOR UPDATE;
    IF OLD.tombstone_en IS NOT NULL
       OR NEW.tombstone_en IS NULL
       OR NEW.ciphertext IS NOT NULL
       OR NEW.tag IS NOT NULL
       OR NEW.longitud_original IS NOT NULL
       OR v_retener_hasta > transaction_timestamp()
       OR NEW.tombstone_en < v_retener_hasta
       OR NEW.tombstone_en > transaction_timestamp() THEN
        RAISE EXCEPTION 'transicion de tombstone cifrado invalida'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_metadata_header_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.lote_id <> OLD.lote_id
       OR NEW.capturado_en <> OLD.capturado_en
       OR NEW.retener_hasta <> OLD.retener_hasta
       OR OLD.purgado_en IS NOT NULL
       OR NEW.purgado_en IS NULL
       OR NEW.purgado_en < OLD.retener_hasta
       OR NEW.purgado_en > transaction_timestamp()
       OR OLD.retener_hasta > transaction_timestamp() THEN
        RAISE EXCEPTION 'transicion de purga de metadata invalida'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM legal_aceptacion_metadatos_cifrados campo
         WHERE campo.lote_id = OLD.lote_id AND campo.tipo = 'IP'
    ) OR EXISTS (
        SELECT 1 FROM legal_aceptacion_metadatos_cifrados campo
         WHERE campo.lote_id = OLD.lote_id AND campo.tombstone_en IS NULL
    ) THEN
        RAISE EXCEPTION 'todos los campos deben ser tombstones antes de cerrar la purga'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_legal_aceptacion_lote_insert
    BEFORE INSERT ON legal_aceptacion_lotes
    FOR EACH ROW EXECUTE FUNCTION legal_aceptacion_lote_insert_guard();
CREATE TRIGGER trg_legal_aceptacion_lote_inmutable
    BEFORE UPDATE OR DELETE ON legal_aceptacion_lotes
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_aceptacion_insert
    BEFORE INSERT ON legal_aceptaciones
    FOR EACH ROW EXECUTE FUNCTION legal_aceptacion_insert_guard();
CREATE TRIGGER trg_legal_aceptacion_inmutable
    BEFORE UPDATE OR DELETE ON legal_aceptaciones
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_aceptacion_documento_insert
    BEFORE INSERT ON legal_aceptacion_documentos
    FOR EACH ROW EXECUTE FUNCTION legal_aceptacion_documento_insert_guard();
CREATE TRIGGER trg_legal_aceptacion_documento_inmutable
    BEFORE UPDATE OR DELETE ON legal_aceptacion_documentos
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_metadata_header_insert
    BEFORE INSERT ON legal_aceptacion_metadatos
    FOR EACH ROW EXECUTE FUNCTION legal_metadata_header_insert_guard();
CREATE TRIGGER trg_legal_metadata_header_update
    BEFORE UPDATE ON legal_aceptacion_metadatos
    FOR EACH ROW EXECUTE FUNCTION legal_metadata_header_update_guard();
CREATE TRIGGER trg_legal_metadata_header_no_delete
    BEFORE DELETE ON legal_aceptacion_metadatos
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();
CREATE TRIGGER trg_legal_metadata_cifrada_insert
    BEFORE INSERT ON legal_aceptacion_metadatos_cifrados
    FOR EACH ROW EXECUTE FUNCTION legal_metadata_cifrada_insert_guard();
CREATE TRIGGER trg_legal_metadata_cifrada_update
    BEFORE UPDATE ON legal_aceptacion_metadatos_cifrados
    FOR EACH ROW EXECUTE FUNCTION legal_metadata_cifrada_update_guard();
CREATE TRIGGER trg_legal_metadata_cifrada_no_delete
    BEFORE DELETE ON legal_aceptacion_metadatos_cifrados
    FOR EACH ROW EXECUTE FUNCTION legal_rechazar_update_delete();

CREATE CONSTRAINT TRIGGER ct_legal_aceptacion_lote_completo
    AFTER INSERT ON legal_aceptacion_lotes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_aceptacion_constraint_guard();
CREATE CONSTRAINT TRIGGER ct_legal_aceptacion_exacta
    AFTER INSERT ON legal_aceptaciones
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_aceptacion_constraint_guard();
CREATE CONSTRAINT TRIGGER ct_legal_aceptacion_documentos_exactos
    AFTER INSERT ON legal_aceptacion_documentos
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_aceptacion_constraint_guard();
CREATE CONSTRAINT TRIGGER ct_legal_metadata_header_completa
    AFTER INSERT OR UPDATE ON legal_aceptacion_metadatos
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_aceptacion_constraint_guard();
CREATE CONSTRAINT TRIGGER ct_legal_metadata_cifrada_completa
    AFTER INSERT OR UPDATE ON legal_aceptacion_metadatos_cifrados
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION legal_aceptacion_constraint_guard();

-- ------------------------------------------------------------
-- Idempotencia: solo exitos, misma transaccion y purga vencida
-- ------------------------------------------------------------

CREATE FUNCTION legal_idempotencia_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_xmin        XID;
    v_aceptado_en TIMESTAMP WITH TIME ZONE;
BEGIN
    SELECT xmin, aceptado_en INTO v_xmin, v_aceptado_en
      FROM legal_aceptacion_lotes
     WHERE id = NEW.lote_id
       AND user_id = NEW.user_id
       AND taller_id = NEW.taller_id
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'resultado idempotente no coincide con su lote/actor'
            USING ERRCODE = '23503';
    END IF;
    IF NOT legal_fila_es_transaccion_actual(v_xmin) THEN
        RAISE EXCEPTION 'el resultado idempotente debe confirmarse con el resultado de negocio'
            USING ERRCODE = '23514';
    END IF;
    NEW.completed_at := v_aceptado_en;
    RETURN NEW;
END;
$$;

CREATE FUNCTION legal_idempotencia_update_delete_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'un resultado idempotente confirmado es inmutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.expires_at > transaction_timestamp() THEN
        RAISE EXCEPTION 'un resultado idempotente no puede purgarse antes de expires_at'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_legal_idempotencia_insert
    BEFORE INSERT ON legal_idempotencia_resultados
    FOR EACH ROW EXECUTE FUNCTION legal_idempotencia_insert_guard();
CREATE TRIGGER trg_legal_idempotencia_update_delete
    BEFORE UPDATE OR DELETE ON legal_idempotencia_resultados
    FOR EACH ROW EXECUTE FUNCTION legal_idempotencia_update_delete_guard();

-- ------------------------------------------------------------
-- Endurecimiento de resolucion de objetos
-- ------------------------------------------------------------
--
-- Las funciones de trigger son SECURITY INVOKER, pero nunca deben resolver tablas
-- legales desde el search_path aportado por la conexion. Capturamos el schema en
-- el que Flyway instalo V27 y dejamos pg_temp explicitamente al final para impedir
-- que una tabla temporal homonima sombree el agregado persistente.

DO $legal_harden_search_path$
DECLARE
    v_schema  TEXT := current_schema();
    v_funcion RECORD;
BEGIN
    FOR v_funcion IN
        SELECT procedimiento.proname,
               pg_catalog.pg_get_function_identity_arguments(procedimiento.oid) AS argumentos
          FROM pg_catalog.pg_proc procedimiento
          JOIN pg_catalog.pg_namespace esquema ON esquema.oid = procedimiento.pronamespace
         WHERE esquema.nspname = v_schema
           AND procedimiento.proname LIKE 'legal\_%' ESCAPE '\'
         ORDER BY procedimiento.proname, argumentos
    LOOP
        EXECUTE pg_catalog.format(
            'ALTER FUNCTION %I.%I(%s) SET search_path TO pg_catalog, %I, pg_temp',
            v_schema, v_funcion.proname, v_funcion.argumentos, v_schema
        );
    END LOOP;
END;
$legal_harden_search_path$;
