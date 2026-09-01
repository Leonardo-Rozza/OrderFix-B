# Runbook PostgreSQL de edición legal V27

Estado: procedimiento técnico interno acreditado en la Fase 2.3C. No se ejecutó con contenido
legal real, no publica APIs y no reemplaza una autorización editorial ni del entorno.

## Propósito y frontera

Este runbook permite consultar readiness editorial, planificar y aplicar una primera promoción,
un reemplazo o un retiro sobre el grafo legal V27. La operación es offline, usa PostgreSQL 16 y el
schema fijo `public`, y se ejecuta con una credencial temporal exclusiva.

Antes de llegar aquí, el release debe haberse validado, simulado, importado y sellado según
[`legal-manifest-import-postgresql.md`](legal-manifest-import-postgresql.md). El rol importador y el
rol editorial son distintos: ninguno se reutiliza como owner de migraciones, cuenta de aplicación
o credencial del otro proceso. El importador no recibe permisos de promoción, reemplazo o retiro y
el editor no importa el grafo origen.

Este procedimiento no:

- aprueba el contenido ni autoriza una publicación real;
- crea o modifica V27, ejecuta Flyway o acepta un schema alternativo;
- habilita V28, catálogo, documentos o requisitos HTTP, ETag, aceptación, seguridad o enforcement;
- acredita readiness público, staging, deploy, seed o integración frontend;
- guarda secretos, identidad del operador, rutas sensibles o contenido legal en Git.

Una operación real sólo puede comenzar cuando exista contenido definitivo aprobado por los
responsables editoriales aplicables y una autorización expresa del entorno. Copie a un registro
externo restringido —no complete estos datos en el repositorio— el entorno, base, ventana,
responsable operativo, aprobadores, custodio del secreto, identificador del job, ticket, origen de
red y destinos de stdout/stderr.

## Contrato congelado

- PostgreSQL: major `16`.
- Migración: una única V27 exitosa, script
  `V27__persistencia_legal_append_only.sql`, checksum Flyway `1575269868`.
- Schema operativo: exactamente `public`; no existe flag, variable ni propiedad para cambiarlo.
- Rol: `LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`.
- Sesión: `SESSION_USER = CURRENT_USER = <rol-editorial>`, `search_path = pg_catalog, public,
  pg_temp`, `session_replication_role = origin` y `lo_compat_privileges = off`.
- Runtime: Java 21 aprobado.
- CLI: JAR legal aprobado y launcher `scripts/legal-manifest-editor.sh`, ambos fijados y verificados.
- Reporte: exactamente un objeto JSON UTF-8 compacto v3 por stdout; stderr se conserva separado.

El contexto aislado verifica schema, inventario y privilegios antes de la operación. Un drift
devuelve un fallo cerrado; no se repara automáticamente durante el job.

## Preflight de release y ventana

Ejecute desde el checkout o paquete aprobado que contiene el launcher. No use un `target/` local
por fallback en una operación real.

```bash
sh -n scripts/legal-manifest-editor.sh
test -x scripts/legal-manifest-editor.sh
shasum -a 256 scripts/legal-manifest-editor.sh
shasum -a 256 /ruta/aprobada/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar
/ruta/aprobada/java -version
```

Compare ambos SHA-256 y la versión de Java con el release autorizado. En cada invocación fije:

```text
ORDENFIX_LEGAL_CLI_JAR=/ruta/aprobada/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar
ORDENFIX_JAVA_BIN=/ruta/aprobada/java
```

No dependa del JAR predeterminado en `target/` ni de `java` encontrado accidentalmente en `PATH`.
El launcher elimina `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` antes de iniciar la
JVM. No invoque el JAR manualmente para evitar ese control.

Verifique además, fuera de Git:

- release inmutable, sin placeholders y con aprobación editorial trazable;
- importación sellada sin publicaciones `ABIERTO` y sin escritura fuera del grafo origen;
- `publicationId`, SHA-256 JCS del manifiesto y archivos exactos del release congelado;
- para reemplazo/retiro, plan canónico aprobado, `operationId` y SHA-256 RFC 8785 exactos;
- ruta de red limitada al job, base y ventana autorizados;
- secreto temporal disponible por el gestor de secretos, nunca por argumentos o logs;
- almacenamiento de evidencia con permisos restrictivos y stdout/stderr separados;
- observador autorizado distinto del rol editorial para reconciliar resultados inciertos;
- plan de cierre de red, sesiones y credencial practicable antes de habilitar el job.

## Preflight PostgreSQL

Conéctese como owner de migraciones o administrador autorizado. Los únicos valores editables son
la base y el rol; `schema_name` es un centinela fijo y no se cambia.

```sql
\set ON_ERROR_STOP on
\set database_name 'EDITAR_BASE_ORDENFIX'
\set editor_role 'EDITAR_ROL_EDITORIAL_TEMPORAL'
\set schema_name 'public'
```

Confirme la base, versión y V27:

```sql
SELECT pg_catalog.current_database(),
       pg_catalog.current_setting('server_version_num');

SELECT version, type, script, checksum, success
  FROM public.flyway_schema_history
 WHERE version = '27';
```

Debe existir una sola fila
`27 | SQL | V27__persistencia_legal_append_only.sql | 1575269868 | true`. Deténgase si PostgreSQL
no es major 16, la fila falta, se repite o difiere. No edite el historial Flyway.

El inventario congelado por `LegalEditorialSchemaVerifier` es:

- 19 tablas;
- 130 columnas;
- 130 constraints;
- 58 triggers;
- 10 secuencias identity;
- 34 funciones editoriales V27.

Las 19 tablas son:

```text
legal_publicaciones
legal_documento_reemplazo_lotes
legal_documento_lineas
legal_documento_versiones
legal_documento_contextos
legal_publicacion_documentos
legal_documento_reemplazo_anteriores
legal_documento_reemplazo_sucesoras
legal_documento_transiciones
legal_documento_vigentes
legal_requisito_lineas
legal_requisito_audiencias
legal_requisito_versiones
legal_requisito_documentos
legal_publicacion_requisitos
legal_requisito_transiciones
legal_requisito_conjuntos
legal_requisito_conjunto_miembros
legal_requisito_conjuntos_actuales
```

Las 10 secuencias son:

```text
legal_documento_contextos_id_seq
legal_publicacion_documentos_id_seq
legal_documento_reemplazo_anteriores_id_seq
legal_documento_reemplazo_sucesoras_id_seq
legal_documento_transiciones_id_seq
legal_requisito_audiencias_id_seq
legal_requisito_documentos_id_seq
legal_publicacion_requisitos_id_seq
legal_requisito_transiciones_id_seq
legal_requisito_conjunto_miembros_id_seq
```

Sólo cuatro reciben `USAGE` en el perfil editorial; se enumeran más abajo.

## Impacto global que debe evaluarse

PostgreSQL suele otorgar `CONNECT`, `TEMPORARY` y `EXECUTE` a `PUBLIC`. El verifier exige los
privilegios efectivos, por lo que una revocación a `PUBLIC` afecta a otros roles aunque este runbook
nombre una sola credencial.

Antes de cambiar grants:

1. inventaríe todas las bases conectables y los roles que necesitan `CONNECT` o `TEMPORARY`;
2. inventaríe todos los schemas no sistémicos y quienes necesitan `USAGE` o `CREATE`;
3. inventaríe todas las funciones no sistémicas y sus consumidores efectivos;
4. inventaríe los grants de parámetros y las cinco funciones `lo_*` enumeradas abajo;
5. prepare regrants nominales revisados para cada consumidor legítimo;
6. deténgase si el impacto o los regrants no pueden acreditarse antes de la ventana.

El fixture de pruebas usa `REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC` porque
opera sobre una base efímera dedicada. **No copie esa sentencia en un cluster compartido.** El
perfil siguiente enumera las 47 firmas legales V27. Si existen funciones adicionales en `public` u
otros schemas, evalúelas y revoque/reotorgue cada firma nominalmente mediante un cambio separado;
el verifier bloqueará cualquier `EXECUTE` efectivo inesperado.

La misma precaución aplica a `REVOKE CONNECT, TEMPORARY ... FROM PUBLIC`, `REVOKE CREATE ON SCHEMA
public FROM PUBLIC` y revocaciones de parámetros o large objects. Si no puede aplicar el perfil
exacto sin afectar consumidores legítimos, el resultado es **NO-GO**, no una excepción al verifier.

## Perfil SQL mínimo e idempotente

El rol debe crearse y recibir su secreto temporal mediante infraestructura autorizada. No escriba
el password en este archivo, en el historial de shell ni en un argumento de `psql`. El bloque
siguiente asume que el rol ya existe; puede repetirse y no modifica datos de negocio.

Antes de ejecutarlo, confirme que el rol no posee bases, schemas, relaciones, secuencias,
funciones o large objects; que no recibe ni delega memberships; y que ningún otro rol puede
asumirlo. Reasigne ownership y revoque memberships mediante un cambio revisado si aparece una fila.

```sql
SELECT r.rolname, r.rolcanlogin, r.rolinherit, r.rolsuper, r.rolcreatedb,
       r.rolcreaterole, r.rolreplication, r.rolbypassrls
  FROM pg_catalog.pg_roles r
 WHERE r.rolname = :'editor_role';

SELECT granted.rolname AS membership_recibida
  FROM pg_catalog.pg_roles editor
  JOIN pg_catalog.pg_auth_members membership ON membership.member = editor.oid
  JOIN pg_catalog.pg_roles granted ON granted.oid = membership.roleid
 WHERE editor.rolname = :'editor_role';

SELECT member.rolname AS membership_delegada
  FROM pg_catalog.pg_roles editor
  JOIN pg_catalog.pg_auth_members membership ON membership.roleid = editor.oid
  JOIN pg_catalog.pg_roles member ON member.oid = membership.member
 WHERE editor.rolname = :'editor_role';
```

La primera consulta debe devolver exactamente un rol nominal; las otras dos, cero filas.

Primero complete el cambio de infraestructura aprobado que quite el acceso efectivo del rol a toda
base conectable distinta de `database_name`, quite `TEMPORARY` y `CREATE` en todas, y deje `USAGE`
únicamente en `public`. Una revocación directa al rol no neutraliza un grant heredado de `PUBLIC`:
aplique las revocaciones y regrants nominales derivados del inventario anterior.

Luego aplique el perfil en la base actual:

```sql
BEGIN;

ALTER ROLE :"editor_role"
    LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS;
ALTER ROLE :"editor_role" RESET ALL;
ALTER ROLE :"editor_role" IN DATABASE :"database_name" RESET ALL;

REVOKE CONNECT, TEMPORARY ON DATABASE :"database_name" FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE :"database_name" FROM :"editor_role";
GRANT CONNECT ON DATABASE :"database_name" TO :"editor_role";

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON SCHEMA public FROM :"editor_role";
GRANT USAGE ON SCHEMA public TO :"editor_role";

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM :"editor_role";
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM :"editor_role";
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM :"editor_role";
REVOKE SET, ALTER SYSTEM ON PARAMETER session_replication_role FROM :"editor_role";
REVOKE SET, ALTER SYSTEM ON PARAMETER session_replication_role FROM PUBLIC;

REVOKE EXECUTE ON FUNCTION
    public.legal_rechazar_update_delete(),
    public.legal_exigir_read_committed(),
    public.legal_read_committed_statement_guard(),
    public.legal_publicacion_update_statement_guard(),
    public.legal_bloquear_publicacion_abierta(uuid),
    public.legal_bloquear_publicacion_sellada(uuid),
    public.legal_exigir_publicacion_abierta_columna(),
    public.legal_exigir_publicacion_doc_contexto_abierta(),
    public.legal_exigir_publicacion_req_audiencia_abierta(),
    public.legal_exigir_publicacion_req_documento_abierta(),
    public.legal_publicacion_insert_guard(),
    public.legal_bloquear_dependencias_publicacion(uuid),
    public.legal_publicacion_update_guard(),
    public.legal_validar_publicacion_sellada(uuid),
    public.legal_publicacion_constraint_guard(),
    public.legal_documento_version_insert_guard(),
    public.legal_requisito_version_insert_guard(),
    public.legal_version_update_interno_guard(),
    public.legal_documento_transicion_before_insert(),
    public.legal_documento_transicion_after_insert(),
    public.legal_requisito_transicion_before_insert(),
    public.legal_requisito_transicion_after_insert(),
    public.legal_documento_slot_insert_guard(),
    public.legal_validar_slots_documentales(),
    public.legal_slots_constraint_guard(),
    public.legal_requisito_actual_insert_guard(),
    public.legal_validar_conjuntos_actuales(),
    public.legal_conjuntos_actuales_constraint_guard(),
    public.legal_reemplazo_lote_insert_guard(),
    public.legal_reemplazo_miembro_insert_guard(),
    public.legal_validar_reemplazo_estructura(uuid),
    public.legal_reemplazo_lote_before_update(),
    public.legal_reemplazo_lote_after_update(),
    public.legal_reemplazo_constraint_guard(),
    public.legal_fila_es_transaccion_actual(xid),
    public.legal_aceptacion_lote_insert_guard(),
    public.legal_aceptacion_insert_guard(),
    public.legal_aceptacion_documento_insert_guard(),
    public.legal_metadata_header_insert_guard(),
    public.legal_metadata_cifrada_insert_guard(),
    public.legal_validar_aceptacion(uuid),
    public.legal_validar_lote_aceptacion(uuid),
    public.legal_aceptacion_constraint_guard(),
    public.legal_metadata_cifrada_update_guard(),
    public.legal_metadata_header_update_guard(),
    public.legal_idempotencia_insert_guard(),
    public.legal_idempotencia_update_delete_guard()
FROM PUBLIC;

REVOKE EXECUTE ON FUNCTION
    pg_catalog.lo_creat(integer),
    pg_catalog.lo_create(oid),
    pg_catalog.lo_from_bytea(oid, bytea),
    pg_catalog.lo_import(text),
    pg_catalog.lo_import(text, oid)
FROM PUBLIC;

REVOKE EXECUTE ON FUNCTION
    pg_catalog.lo_creat(integer),
    pg_catalog.lo_create(oid),
    pg_catalog.lo_from_bytea(oid, bytea),
    pg_catalog.lo_import(text),
    pg_catalog.lo_import(text, oid)
FROM :"editor_role";

GRANT SELECT ON TABLE public.flyway_schema_history TO :"editor_role";

GRANT SELECT ON TABLE
    public.legal_publicaciones,
    public.legal_documento_reemplazo_lotes,
    public.legal_documento_lineas,
    public.legal_documento_versiones,
    public.legal_documento_contextos,
    public.legal_publicacion_documentos,
    public.legal_documento_reemplazo_anteriores,
    public.legal_documento_reemplazo_sucesoras,
    public.legal_documento_transiciones,
    public.legal_documento_vigentes,
    public.legal_requisito_lineas,
    public.legal_requisito_audiencias,
    public.legal_requisito_versiones,
    public.legal_requisito_documentos,
    public.legal_publicacion_requisitos,
    public.legal_requisito_transiciones,
    public.legal_requisito_conjuntos,
    public.legal_requisito_conjunto_miembros,
    public.legal_requisito_conjuntos_actuales
TO :"editor_role";

GRANT INSERT ON TABLE
    public.legal_documento_reemplazo_lotes,
    public.legal_documento_reemplazo_anteriores,
    public.legal_documento_reemplazo_sucesoras,
    public.legal_documento_transiciones,
    public.legal_documento_vigentes,
    public.legal_requisito_transiciones,
    public.legal_requisito_conjuntos_actuales
TO :"editor_role";

GRANT DELETE ON TABLE
    public.legal_documento_vigentes,
    public.legal_requisito_conjuntos_actuales
TO :"editor_role";

GRANT UPDATE (id)
    ON TABLE public.legal_publicaciones TO :"editor_role";
GRANT UPDATE (id)
    ON TABLE public.legal_documento_lineas TO :"editor_role";
GRANT UPDATE (id, estado, estado_cambiado_en, ultimo_motivo, reemplazo_lote_id)
    ON TABLE public.legal_documento_versiones TO :"editor_role";
GRANT UPDATE (id)
    ON TABLE public.legal_requisito_lineas TO :"editor_role";
GRANT UPDATE (id, estado, estado_cambiado_en, ultimo_motivo)
    ON TABLE public.legal_requisito_versiones TO :"editor_role";
GRANT UPDATE (estado_construccion, sellado_en)
    ON TABLE public.legal_documento_reemplazo_lotes TO :"editor_role";

GRANT USAGE ON SEQUENCE
    public.legal_documento_reemplazo_anteriores_id_seq,
    public.legal_documento_reemplazo_sucesoras_id_seq,
    public.legal_documento_transiciones_id_seq,
    public.legal_requisito_transiciones_id_seq
TO :"editor_role";

GRANT EXECUTE ON FUNCTION
    public.legal_rechazar_update_delete(),
    public.legal_exigir_read_committed(),
    public.legal_read_committed_statement_guard(),
    public.legal_publicacion_update_statement_guard(),
    public.legal_bloquear_publicacion_sellada(uuid),
    public.legal_publicacion_update_guard(),
    public.legal_version_update_interno_guard(),
    public.legal_documento_transicion_before_insert(),
    public.legal_documento_transicion_after_insert(),
    public.legal_requisito_transicion_before_insert(),
    public.legal_requisito_transicion_after_insert(),
    public.legal_documento_slot_insert_guard(),
    public.legal_validar_slots_documentales(),
    public.legal_slots_constraint_guard(),
    public.legal_requisito_actual_insert_guard(),
    public.legal_validar_conjuntos_actuales(),
    public.legal_conjuntos_actuales_constraint_guard(),
    public.legal_reemplazo_lote_insert_guard(),
    public.legal_reemplazo_miembro_insert_guard(),
    public.legal_validar_reemplazo_estructura(uuid),
    public.legal_reemplazo_lote_before_update(),
    public.legal_reemplazo_lote_after_update(),
    public.legal_reemplazo_constraint_guard()
TO :"editor_role";

ALTER ROLE :"editor_role" IN DATABASE :"database_name"
    SET search_path TO pg_catalog, public, pg_temp;
ALTER ROLE :"editor_role" IN DATABASE :"database_name"
    SET session_replication_role TO origin;
ALTER ROLE :"editor_role" IN DATABASE :"database_name"
    SET lo_compat_privileges TO off;

COMMIT;
```

El perfil efectivo esperado queda resumido así:

- `SELECT`: las 19 tablas editoriales y `flyway_schema_history`;
- `INSERT`: 7 tablas;
- `DELETE`: 2 tablas;
- `UPDATE`: 14 columnas de 6 tablas, nunca `UPDATE` de tabla completa;
- `USAGE`: 4 de las 10 secuencias, sin `SELECT` ni `UPDATE` de secuencia;
- `EXECUTE`: 23 de las 34 funciones editoriales;
- sin ownership, memberships, grant options, `TEMPORARY`, DDL, `TRUNCATE`, `REFERENCES`, `TRIGGER`,
  permisos de aplicación, large objects ni ejecución de funciones `lo_*`;
- sin privilegios `SET`/`ALTER SYSTEM` sobre parámetros; los tres settings anteriores son defaults
  por base y no autorizan al proceso a cambiarlos;
- `CONNECT` sólo a la base actual y `USAGE` sólo en `public`, sin `CREATE` sobre base o schema.

Los grants `UPDATE(id)` existen para locks de fila. Los guards V27 rechazan updates directos y
no-op. No amplíe esos grants ni los convierta en `UPDATE` de tabla.

## Verificación del rol

Abra una conexión nueva con la credencial temporal, porque una sesión anterior puede conservar
settings o autorización obsoletos. El secreto sólo entra desde el gestor del job.

```sql
SELECT SESSION_USER, CURRENT_USER, pg_catalog.current_database(),
       pg_catalog.current_schemas(true);
SHOW search_path;
SHOW session_replication_role;
SHOW lo_compat_privileges;

SELECT datname,
       pg_catalog.has_database_privilege(CURRENT_USER, oid, 'CONNECT') AS connect,
       pg_catalog.has_database_privilege(CURRENT_USER, oid, 'CREATE') AS create_database,
       pg_catalog.has_database_privilege(CURRENT_USER, oid, 'TEMPORARY') AS temporary
  FROM pg_catalog.pg_database
 WHERE datallowconn
 ORDER BY datname;
```

Espere identidad nominal exacta, schemas efectivos `{pg_catalog,public}`, `search_path` configurado,
`origin`, `off`, `CONNECT=true` sólo para la base actual y `CREATE=false`/`TEMPORARY=false` para
todas. La primera ejecución de cualquiera de los siete comandos vuelve a verificar además
memberships, ownership, schemas, relaciones/columnas, secuencias, parámetros, large objects,
funciones y fingerprints V27. No use un comando mutante como prueba inicial: ejecute `readiness` o
un `plan-*`.

Un `SCHEMA_DRIFT` o `ROLE_PRIVILEGE_DRIFT` es **NO-GO**. No arregle grants, Flyway o catálogo
dentro del job; cierre la conexión, corrija infraestructura por un cambio revisado y repita con una
sesión nueva.

## Configuración cerrada del proceso

El contexto editorial admite exactamente estas variables de datasource:

```text
ORDENFIX_LEGAL_EDITOR_DB_URL=<jdbc-url>
ORDENFIX_LEGAL_EDITOR_DB_USERNAME=<rol-editorial>
ORDENFIX_LEGAL_EDITOR_DB_PASSWORD=<secreto-temporal>
ORDENFIX_LEGAL_EDITOR_DB_DRIVER_CLASS_NAME=<driver-opcional>
```

Las tres primeras son obligatorias. El driver es opcional; si está presente no puede estar vacío.
Una variable adicional con prefijo `ORDENFIX_LEGAL_EDITOR_DB_` bloquea la ejecución. El contrato
operativo no admite otros canales de datasource. El launcher usa como routing externo
`ORDENFIX_LEGAL_CLI_JAR` y `ORDENFIX_JAVA_BIN`.

Sólo el proceso individual `apply-promote`, `apply-replace` o `apply-retire` recibe:

```text
ORDENFIX_LEGAL_EDITOR_ENABLED=true
```

No exporte esa variable en la shell, contenedor o job compartido de `readiness` y `plan-*`; inyéctela
en la invocación mutante y elimine el proceso al terminar. Está prohibido pasar datasource por
`-Dspring.datasource.*`. Tampoco use argumentos, archivos versionados o logs para el password.

## Interfaz exacta de los siete comandos

Los nombres son case-sensitive y no existe `--help`. Un argumento ausente, adicional, repetido o
mal formado bloquea antes de resolver secretos o abrir JDBC. Los flags usan siempre la forma
`--nombre=valor`; no admiten aliases ni separación entre flag y valor.

`readiness`, `plan-promote` y `apply-promote` reciben exactamente:

```text
--manifest=<ruta>
--confirm-publication-id=<publicationId-exacto>
--confirm-manifest-sha256=<64-hex-jcs-exacto>
```

`plan-replace`, `apply-replace`, `plan-retire` y `apply-retire` agregan exactamente:

```text
--editorial-plan=<ruta>
--confirm-operation-id=<uuid-canónico-exacto>
--confirm-editorial-plan-sha256=<64-hex-rfc8785-exacto>
```

El digest del plan es el SHA-256 de su canonicalización RFC 8785. Un `shasum` del JSON crudo no es
un sustituto salvo que el pipeline aprobado haya demostrado que esos bytes son precisamente la
representación canónica.

Ejemplos estructurales —las variables de datasource ya deben estar inyectadas por el gestor del
job y cada comando debe agregar las redirecciones directas descritas en la sección siguiente—:

```bash
ORDENFIX_LEGAL_CLI_JAR=/ruta/aprobada/legal-cli.jar \
ORDENFIX_JAVA_BIN=/ruta/aprobada/java \
./scripts/legal-manifest-editor.sh readiness \
  --manifest=/ruta/aprobada/publication-manifest.json \
  --confirm-publication-id=<publication-id-exacto> \
  --confirm-manifest-sha256=<sha256-jcs-exacto>

ORDENFIX_LEGAL_CLI_JAR=/ruta/aprobada/legal-cli.jar \
ORDENFIX_JAVA_BIN=/ruta/aprobada/java \
./scripts/legal-manifest-editor.sh plan-promote \
  --manifest=/ruta/aprobada/publication-manifest.json \
  --confirm-publication-id=<publication-id-exacto> \
  --confirm-manifest-sha256=<sha256-jcs-exacto>

ORDENFIX_LEGAL_EDITOR_ENABLED=true \
ORDENFIX_LEGAL_CLI_JAR=/ruta/aprobada/legal-cli.jar \
ORDENFIX_JAVA_BIN=/ruta/aprobada/java \
./scripts/legal-manifest-editor.sh apply-promote \
  --manifest=/ruta/aprobada/publication-manifest.json \
  --confirm-publication-id=<publication-id-exacto> \
  --confirm-manifest-sha256=<sha256-jcs-exacto>

ORDENFIX_LEGAL_CLI_JAR=/ruta/aprobada/legal-cli.jar \
ORDENFIX_JAVA_BIN=/ruta/aprobada/java \
./scripts/legal-manifest-editor.sh plan-replace \
  --manifest=/ruta/aprobada/publication-manifest.json \
  --editorial-plan=/ruta/aprobada/editorial-plan.json \
  --confirm-publication-id=<publication-id-exacto> \
  --confirm-manifest-sha256=<sha256-jcs-exacto> \
  --confirm-operation-id=<operation-id-exacto> \
  --confirm-editorial-plan-sha256=<sha256-rfc8785-exacto>

ORDENFIX_LEGAL_EDITOR_ENABLED=true \
ORDENFIX_LEGAL_CLI_JAR=/ruta/aprobada/legal-cli.jar \
ORDENFIX_JAVA_BIN=/ruta/aprobada/java \
./scripts/legal-manifest-editor.sh apply-replace \
  --manifest=/ruta/aprobada/publication-manifest.json \
  --editorial-plan=/ruta/aprobada/editorial-plan.json \
  --confirm-publication-id=<publication-id-exacto> \
  --confirm-manifest-sha256=<sha256-jcs-exacto> \
  --confirm-operation-id=<operation-id-exacto> \
  --confirm-editorial-plan-sha256=<sha256-rfc8785-exacto>

ORDENFIX_LEGAL_CLI_JAR=/ruta/aprobada/legal-cli.jar \
ORDENFIX_JAVA_BIN=/ruta/aprobada/java \
./scripts/legal-manifest-editor.sh plan-retire \
  --manifest=/ruta/aprobada/publication-manifest.json \
  --editorial-plan=/ruta/aprobada/editorial-plan.json \
  --confirm-publication-id=<publication-id-exacto> \
  --confirm-manifest-sha256=<sha256-jcs-exacto> \
  --confirm-operation-id=<operation-id-exacto> \
  --confirm-editorial-plan-sha256=<sha256-rfc8785-exacto>

ORDENFIX_LEGAL_EDITOR_ENABLED=true \
ORDENFIX_LEGAL_CLI_JAR=/ruta/aprobada/legal-cli.jar \
ORDENFIX_JAVA_BIN=/ruta/aprobada/java \
./scripts/legal-manifest-editor.sh apply-retire \
  --manifest=/ruta/aprobada/publication-manifest.json \
  --editorial-plan=/ruta/aprobada/editorial-plan.json \
  --confirm-publication-id=<publication-id-exacto> \
  --confirm-manifest-sha256=<sha256-jcs-exacto> \
  --confirm-operation-id=<operation-id-exacto> \
  --confirm-editorial-plan-sha256=<sha256-rfc8785-exacto>
```

No transforme un replace en retire ni genere un nuevo `operationId` durante una recuperación. Un
artefacto distinto representa otra intención y otra cadena de evidencia.

## Captura y reporte v3

Prepare fuera del checkout un directorio por job con `umask 077`. Capture stdout y stderr mediante
redirecciones directas a archivos distintos y registre el exit code inmediatamente:

```bash
umask 077
ORDENFIX_LEGAL_CLI_JAR=/ruta/aprobada/legal-cli.jar \
ORDENFIX_JAVA_BIN=/ruta/aprobada/java \
./scripts/legal-manifest-editor.sh readiness \
  --manifest=/ruta/aprobada/publication-manifest.json \
  --confirm-publication-id=<publication-id-exacto> \
  --confirm-manifest-sha256=<sha256-jcs-exacto> \
  > /ruta/restringida/job/readiness.stdout.json \
  2> /ruta/restringida/job/readiness.stderr.log
legal_editor_exit=$?
```

No use `| tee`, `| jq` ni ningún pipeline: además de poder cerrar stdout, POSIX `sh` puede ocultar
el exit code real del CLI. Valide el JSON desde el archivo ya cerrado, sin volver a ejecutar ni
modificar el receipt. La CLI espera stderr vacío tanto en `PASS` como en `BLOCKED` o `ERROR`; los
envelopes de fallo también salen por stdout. Cualquier contenido de stderr se trata como diagnóstico
no autoritativo del launcher/JVM, se sanitiza y nunca reemplaza al reporte v3.

El objeto v3 ordena estas claves:

```text
reportVersion, command, status, persisted, publication, operation,
plan, readiness, counts, issues, omittedIssueCount
```

Valide primero `reportVersion=3`, `command`, `status` y el exit code; después interprete los campos
anidados. `status` y exit code son estables:

- `PASS` → `0`;
- `BLOCKED` → `2`;
- `ERROR` → `3`.

Matriz terminal:

| Comando | Resultado terminal | Status/exit | `persisted` | Readiness |
| --- | --- | --- | --- | --- |
| `readiness` | `READY` | `PASS/0` | `false` | `READY` observado |
| `readiness` | `NOT_READY` | `BLOCKED/2` | `false` | `NOT_READY` observado |
| `readiness` | error | `ERROR/3` | `false` | `ERROR` o ausente |
| `plan-promote` | `APPLICABLE` | `PASS/0` | `false` | esperado `READY` |
| `plan-replace` | `APPLICABLE` | `PASS/0` | `false` | esperado `READY` |
| `plan-retire` | `APPLICABLE` | `PASS/0` | `false` | esperado `NOT_READY` |
| cualquier `plan-*` | `BLOCKED` | `BLOCKED/2` | `false` | sin aplicación |
| cualquier `plan-*` | `ERROR` | `ERROR/3` | `false` | sin aplicación |
| `apply-promote` | `APPLIED` o `ALREADY_APPLIED` | `PASS/0` | `true` | `READY` confirmado |
| `apply-replace` | `APPLIED` o `ALREADY_APPLIED` | `PASS/0` | `true` | `READY` confirmado |
| `apply-retire` | `APPLIED` o `ALREADY_APPLIED` | `PASS/0` | `true` | `NOT_READY` confirmado |
| cualquier `apply-*` | `BLOCKED` | `BLOCKED/2` | `false` | ausente |
| cualquier `apply-*` | `ERROR` conocido | `ERROR/3` | `false` | ausente |
| cualquier `apply-*` | `UNKNOWN` | `ERROR/3` | `null` | ausente |

`APPLIED + NOT_READY` en `apply-retire` es un éxito técnico con exit `0`: el retiro dejó
deliberadamente sin vigencia a los sets afectados y por eso falla cerrado. No lo reetiquete como
error ni como readiness público.

En un plan aplicable, `plan.changeRequired` sólo indica si esa evaluación escribiría al aplicarse;
no es autorización, confirmación de persistencia ni readiness público.

Considere stdout como receipt sólo si es un JSON v3 completo y consistente con el exit. Una salida
inválida se conserva aparte como evidencia no autoritativa del incidente. El receipt técnico no es
aprobación, aceptación del cliente ni comprobante fiscal. Conserve por separado stderr sanitizado,
hashes, job, operador, aprobadores, entorno y ventana.

## Secuencia go/no-go

Para primera promoción:

1. complete revisión/aprobación, digest, `validate`, `dry-run`, `import` y sello;
2. ejecute `readiness` y conserve el resultado previo;
3. ejecute `plan-promote`; continúe sólo con `APPLICABLE`, `PASS/0` y expected `READY`;
4. autorice la mutación exacta fuera de Git;
5. ejecute `apply-promote` con `ORDENFIX_LEGAL_EDITOR_ENABLED=true` sólo en ese proceso;
6. exija `APPLIED` o `ALREADY_APPLIED`, `persisted=true` y readiness `READY`;
7. ejecute nuevamente `readiness` y exija `READY`, `PASS/0`.

Para reemplazo:

1. preserve el mismo bundle y use el plan canónico aprobado de tipo `REPLACE`;
2. ejecute `plan-replace` con los seis flags exactos y continúe sólo si es `APPLICABLE`;
3. ejecute `apply-replace` con exactamente los mismos archivos, IDs y hashes;
4. exija `APPLIED`/`ALREADY_APPLIED` y `READY`; confirme con `readiness`.

Para retiro:

1. preserve el mismo bundle y use el plan canónico aprobado de tipo `RETIRE`;
2. ejecute `plan-retire` y continúe sólo si es `APPLICABLE` con expected `NOT_READY`;
3. ejecute `apply-retire` con exactamente los mismos archivos, IDs y hashes;
4. exija `APPLIED`/`ALREADY_APPLIED`, `persisted=true`, `NOT_READY` y exit `0`;
5. confirme el hueco deliberado con `readiness`, que devolverá `NOT_READY`, `BLOCKED/2`.

No ejecute un apply si el plan es `BLOCKED` o `ERROR`, cambió el fingerprint observado, expiró la
ventana, se alteró un artefacto o ya no coinciden las aprobaciones.

## Resultado incierto o stdout inválido

Un timeout, conexión perdida, exit `3`, `UNKNOWN`, stdout vacío/truncado o JSON inválido no prueba
rollback. Una salida inválida no es un envelope `UNKNOWN`: sólo un JSON v3 válido puede declarar ese
outcome. El commit puede haber ocurrido antes de perder la salida.

1. detenga reintentos automáticos y preserve stdout/stderr, exit, tiempos y artefactos originales;
2. no edite el bundle o plan y no genere nuevos IDs o hashes;
3. con credenciales de observación, inspeccione postestado, transiciones y proyecciones por sus IDs
   persistidos; V27 no guarda `operationId`, SHA del plan ni un receipt consultable por esa identidad;
4. repita el mismo comando con el mismo bundle, manifiesto, plan, `publicationId`, `operationId` y
   hashes originales;
5. acepte `ALREADY_APPLIED` sólo si el receipt y postestado exactos coinciden; si el intento previo
   revirtió, el retry puede devolver `APPLIED`;
6. si persiste `UNKNOWN`, aparece un postestado parcial o no puede observarse con autoridad,
   mantenga **NO-GO** y escale sin ejecutar otra intención editorial.

Un `operationId` nuevo inicia otra cadena de evidencia y no es un mecanismo de recuperación, aunque
el motor pueda detectar ciertos postestados equivalentes. La regla operativa es siempre el retry
exacto.

## Cierre de ventana, sesiones y secreto

`NOLOGIN` impide conexiones nuevas, pero **no termina sesiones existentes**. Cierre en este orden:

1. detenga el job y cualquier reintento automático;
2. cierre o bloquee la ruta de red desde el runner;
3. como administrador autorizado, ejecute `ALTER ROLE <rol-editorial> NOLOGIN`;
4. inventaríe las sesiones exactas del rol y espere su drenaje o termínelas nominalmente según el
   procedimiento aprobado;
5. verifique cero sesiones activas para ese rol;
6. rote o invalide el secreto temporal en el gestor;
7. conserve evidencia sanitizada y cierre la ventana.

Consultas de control:

```sql
ALTER ROLE :"editor_role" NOLOGIN;

SELECT pid, datname, usename, application_name, client_addr, state,
       backend_start, xact_start
  FROM pg_catalog.pg_stat_activity
 WHERE usename = :'editor_role'
 ORDER BY pid;
```

Si la política autoriza terminación, congele y apruebe primero cada PID junto con base,
`application_name`, origen y `backend_start`; termínelo nominalmente mediante el procedimiento del
entorno. Este runbook no incluye una sentencia amplia por nombre de rol. Después repita la consulta
de inventario y exija cero filas. El conteo final debe ser `0` antes de rotar el secreto. No quite
`NOLOGIN` hasta una nueva ventana autorizada, con secreto nuevo y verificación completa en una
conexión nueva; mientras permanezca en `NOLOGIN`, el verifier bloqueará porque el contrato
operativo exige `LOGIN`.

## Evidencia mínima externa

Archive con acceso restringido:

- release, `publicationId`, SHA-256 JCS y referencia de aprobación;
- para reemplazo/retiro, plan, `operationId` y SHA-256 RFC 8785;
- entorno, base, rol sin secreto, PostgreSQL 16 y fila/checksum V27;
- hashes del JAR, launcher y Java 21 aprobado;
- referencia al cambio de grants y resultado del verifier en conexión nueva;
- stdout JSON v3 crudo, stderr sanitizado y exit code por comando;
- receipts y postestado observado para apply/retry;
- job, operador, aprobadores, ventana, red, ticket y custodio del secreto;
- cierre de red/job, `NOLOGIN`, cero sesiones y rotación del secreto.

Readiness editorial sólo prueba coherencia interna del grafo V27. La publicación pública sigue
requiriendo V28 multicontexto, APIs, ETag, aceptación y registro atómicos, seguridad, contenido
definitivo, staging, deploy y smokes remotos antes de abrir `BACKEND-HANDOFF 1`.
