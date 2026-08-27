# Runbook PostgreSQL del importador legal V27

Estado: contexto DB, comando CLI, launcher y protocolo real acreditados hasta el Corte 6. El cierre
cross-repo y la autorización operativa final corresponden al Corte 7.

## Propósito y límites

Este procedimiento configura una credencial PostgreSQL exclusiva para importar el grafo legal
sellado. La cuenta no es la de la aplicación, no es el owner de migraciones y no ejecuta Flyway.
Sólo recibe las capacidades que `LegalImportPrivilegeVerifier` acredita en cada intento.

El documento no contiene ni genera passwords. El rol debe existir y su secreto debe provisionarse
por el gestor de secretos del entorno antes de aplicar los grants. Use un rol nuevo, sin membresías,
creado como `LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`.

V27 no guarda el operador del job. Conserve externamente el identificador del job y del operador,
el SHA-256 confirmado, el receipt final y logs PostgreSQL sanitizados. No copie el manifiesto, el
password ni valores sensibles a los logs.

## Impactos que deben aprobarse en el entorno

- PostgreSQL concede `TEMPORARY` sobre una base y `EXECUTE` sobre funciones a `PUBLIC` por defecto.
  El perfil mínimo requiere revocarlos.
- `REVOKE TEMPORARY ... FROM PUBLIC` afecta a toda la base. Antes de aplicarlo, identifique los otros
  roles que realmente crean tablas temporales y reotórgueles ese permiso explícitamente.
- El verifier exige que la credencial no pueda conectarse ni crear temporales en ninguna otra base
  conectable del clúster. Revocar `PUBLIC CONNECT/TEMPORARY` allí también afecta a sus usuarios;
  inventaríelos y reotorgue primero los permisos nominales que deban conservar.
- `REVOKE CREATE ON SCHEMA ... FROM PUBLIC` afecta a todos los roles de la base. Identifique y
  reotorgue explícitamente esa capacidad sólo a los roles de migración que realmente la necesitan.
- La revocación de `PUBLIC EXECUTE` se limita a las 47 firmas legales de V27. Si otro rol operativo
  las necesita, concédaselas directamente en un cambio separado y documentado.
- Un grant de parámetros a `PUBLIC` es global al clúster. Antes de revocarlo, confirme qué roles lo
  usan; `session_replication_role` no puede quedar disponible para la credencial importadora.
- Ejecute el bloque con el owner de migraciones o un administrador autorizado, dentro de una
  ventana controlada. No lo ejecute con la credencial importadora.

## Placeholders editables

Revise estos tres valores antes de ejecutar el SQL:

```sql
\set ON_ERROR_STOP on
\set database_name 'EDITAR_BASE_ORDENFIX'
\set schema_name 'public'
\set import_role 'EDITAR_ROL_IMPORTADOR'
```

`schema_name` debe estar en minúsculas y cumplir `[a-z_][a-z0-9_]{0,62}`; el verifier rechaza
cualquier otro identificador antes de consultar el catálogo.

La configuración del contexto debe usar el mismo schema mediante
`ordenfix.legal.import-schema`; el default es `public`. El username resuelto de la conexión debe
coincidir exactamente con `import_role`.

## Precondiciones

Conectado como owner, confirme una única migración V27 exitosa con estos valores:

```sql
SELECT version, type, script, checksum, success
  FROM :"schema_name".flyway_schema_history
 WHERE version = '27';
```

Resultado esperado: `27 | SQL | V27__persistencia_legal_append_only.sql | 1575269868 | true`.
Deténgase si la fila falta, se repite o difiere. No corrija el historial a mano.

Confirme `SHOW server_version_num`; el contexto queda cerrado si la versión mayor no es PostgreSQL
16, porque las huellas congeladas de catálogo se acreditaron sobre esa versión.

Confirme además que el rol ya existe con el perfil nominal base y sin memberships:

```sql
SELECT rolname, rolcanlogin, rolinherit, rolsuper, rolcreatedb,
       rolcreaterole, rolreplication, rolbypassrls
  FROM pg_catalog.pg_roles
 WHERE rolname = :'import_role';

SELECT granted.rolname AS membership
  FROM pg_catalog.pg_roles importer
  JOIN pg_catalog.pg_auth_members membership
    ON membership.member = importer.oid
  JOIN pg_catalog.pg_roles granted
    ON granted.oid = membership.roleid
 WHERE importer.rolname = :'import_role';

SELECT grantee.rolname AS delegated_to
  FROM pg_catalog.pg_roles importer
  JOIN pg_catalog.pg_auth_members membership
    ON membership.roleid = importer.oid
  JOIN pg_catalog.pg_roles grantee
    ON grantee.oid = membership.member
 WHERE importer.rolname = :'import_role';
```

Debe existir una fila con `LOGIN=true`, `INHERIT=false` y todos los flags amplios en `false`; la
segunda y la tercera consulta deben devolver cero filas. Así ningún otro rol puede asumir la
credencial importadora mediante `SET ROLE`.

Inventaríe luego todas las bases conectables y sus permisos efectivos. Sólo `database_name` puede
devolver `CONNECT=true`; todas deben devolver `CREATE=false` y `TEMPORARY=false`:

```sql
SELECT datname,
       pg_catalog.has_database_privilege(:'import_role', oid, 'CONNECT') AS connect,
       pg_catalog.has_database_privilege(:'import_role', oid, 'CREATE') AS create_database,
       pg_catalog.has_database_privilege(:'import_role', oid, 'TEMPORARY') AS temporary
  FROM pg_catalog.pg_database
 WHERE datallowconn
 ORDER BY datname;
```

Antes del bloque principal, aplique en cada base conectable el cambio de infraestructura aprobado
equivalente a `REVOKE CONNECT, TEMPORARY ON DATABASE <base> FROM PUBLIC`, y reotorgue de forma
nominal los accesos legítimos de sus otros roles. El runbook no automatiza ese impacto global ni
acepta una excepción silenciosa: el importador permanecerá cerrado hasta que la matriz sea exacta.

Confirme también que la credencial no sea owner ni tenga `SELECT`/`UPDATE` sobre ningún large
object. Estos permisos viven fuera de las tablas de aplicación y también se rechazan:

```sql
SELECT DISTINCT lom.oid, pg_catalog.pg_get_userbyid(lom.lomowner) AS owner,
       CASE WHEN acl.grantee = 0 THEN 'PUBLIC'
            ELSE pg_catalog.pg_get_userbyid(acl.grantee) END AS grantee,
       acl.privilege_type, acl.is_grantable
  FROM pg_catalog.pg_largeobject_metadata lom
  CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
      lom.lomacl, pg_catalog.acldefault('L', lom.lomowner))) acl
 WHERE lom.lomowner = (
           SELECT oid FROM pg_catalog.pg_roles WHERE rolname = :'import_role')
    OR (acl.grantee IN (
           0::oid,
           (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = :'import_role'))
        AND acl.privilege_type IN ('SELECT', 'UPDATE'))
 ORDER BY lom.oid, grantee, acl.privilege_type;
```

La consulta debe devolver cero filas. Reasigne ownership y revoque grants de cualquier fila mediante
un cambio revisado; no elimine objetos grandes como parte de este runbook.

Finalmente, confirme que no exista ningún privilegio efectivo explícito sobre parámetros del
servidor. En particular, `SET ON PARAMETER session_replication_role` permitiría desactivar triggers
y constraints durante la sesión:

```sql
SELECT parname,
       pg_catalog.has_parameter_privilege(:'import_role', parname, 'SET') AS can_set,
       pg_catalog.has_parameter_privilege(
           :'import_role', parname, 'ALTER SYSTEM') AS can_alter_system
  FROM pg_catalog.pg_parameter_acl
 WHERE pg_catalog.has_parameter_privilege(:'import_role', parname, 'SET')
    OR pg_catalog.has_parameter_privilege(:'import_role', parname, 'ALTER SYSTEM')
 ORDER BY parname;
```

La consulta debe devolver cero filas. Revoque cualquier grant nominal o vía `PUBLIC` antes de
continuar; el verifier no acepta excepciones de parámetros.

## Aplicación idempotente del perfil

Este bloque sólo modifica autorización y search path. No elimina ni altera datos de negocio.

```sql
BEGIN;

REVOKE CONNECT, TEMPORARY ON DATABASE :"database_name" FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE :"database_name" FROM :"import_role";
GRANT CONNECT ON DATABASE :"database_name" TO :"import_role";

REVOKE CREATE ON SCHEMA :"schema_name" FROM PUBLIC;
REVOKE ALL PRIVILEGES ON SCHEMA :"schema_name" FROM :"import_role";
GRANT USAGE ON SCHEMA :"schema_name" TO :"import_role";

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA :"schema_name" FROM :"import_role";
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA :"schema_name" FROM :"import_role";
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA :"schema_name" FROM :"import_role";
REVOKE SET, ALTER SYSTEM ON PARAMETER session_replication_role FROM :"import_role";
REVOKE SET, ALTER SYSTEM ON PARAMETER session_replication_role FROM PUBLIC;

REVOKE EXECUTE ON FUNCTION :"schema_name".legal_rechazar_update_delete() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_exigir_read_committed() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_read_committed_statement_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_publicacion_update_statement_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_bloquear_publicacion_abierta(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_bloquear_publicacion_sellada(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_exigir_publicacion_abierta_columna() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_exigir_publicacion_doc_contexto_abierta() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_exigir_publicacion_req_audiencia_abierta() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_exigir_publicacion_req_documento_abierta() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_publicacion_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_bloquear_dependencias_publicacion(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_publicacion_update_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_validar_publicacion_sellada(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_publicacion_constraint_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_documento_version_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_requisito_version_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_version_update_interno_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_documento_transicion_before_insert() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_documento_transicion_after_insert() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_requisito_transicion_before_insert() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_requisito_transicion_after_insert() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_documento_slot_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_validar_slots_documentales() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_slots_constraint_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_requisito_actual_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_validar_conjuntos_actuales() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_conjuntos_actuales_constraint_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_reemplazo_lote_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_reemplazo_miembro_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_validar_reemplazo_estructura(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_reemplazo_lote_before_update() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_reemplazo_lote_after_update() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_reemplazo_constraint_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_fila_es_transaccion_actual(xid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_aceptacion_lote_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_aceptacion_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_aceptacion_documento_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_metadata_header_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_metadata_cifrada_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_validar_aceptacion(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_validar_lote_aceptacion(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_aceptacion_constraint_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_metadata_cifrada_update_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_metadata_header_update_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_idempotencia_insert_guard() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION :"schema_name".legal_idempotencia_update_delete_guard() FROM PUBLIC;

GRANT SELECT ON TABLE :"schema_name".flyway_schema_history TO :"import_role";

GRANT SELECT, INSERT ON TABLE
    :"schema_name".legal_publicaciones,
    :"schema_name".legal_documento_lineas,
    :"schema_name".legal_documento_versiones,
    :"schema_name".legal_documento_contextos,
    :"schema_name".legal_publicacion_documentos,
    :"schema_name".legal_requisito_lineas,
    :"schema_name".legal_requisito_audiencias,
    :"schema_name".legal_requisito_versiones,
    :"schema_name".legal_requisito_documentos,
    :"schema_name".legal_publicacion_requisitos,
    :"schema_name".legal_requisito_conjuntos,
    :"schema_name".legal_requisito_conjunto_miembros
TO :"import_role";

GRANT UPDATE (estado_construccion, sellado_en)
    ON TABLE :"schema_name".legal_publicaciones TO :"import_role";
GRANT UPDATE (id)
    ON TABLE :"schema_name".legal_documento_lineas TO :"import_role";
GRANT UPDATE (id)
    ON TABLE :"schema_name".legal_documento_versiones TO :"import_role";
GRANT UPDATE (id)
    ON TABLE :"schema_name".legal_requisito_lineas TO :"import_role";
GRANT UPDATE (id)
    ON TABLE :"schema_name".legal_requisito_versiones TO :"import_role";

GRANT USAGE ON SEQUENCE
    :"schema_name".legal_documento_contextos_id_seq,
    :"schema_name".legal_publicacion_documentos_id_seq,
    :"schema_name".legal_requisito_audiencias_id_seq,
    :"schema_name".legal_requisito_documentos_id_seq,
    :"schema_name".legal_publicacion_requisitos_id_seq,
    :"schema_name".legal_requisito_conjunto_miembros_id_seq
TO :"import_role";

GRANT EXECUTE ON FUNCTION
    :"schema_name".legal_rechazar_update_delete(),
    :"schema_name".legal_exigir_read_committed(),
    :"schema_name".legal_publicacion_update_statement_guard(),
    :"schema_name".legal_bloquear_publicacion_abierta(uuid),
    :"schema_name".legal_bloquear_publicacion_sellada(uuid),
    :"schema_name".legal_exigir_publicacion_abierta_columna(),
    :"schema_name".legal_exigir_publicacion_doc_contexto_abierta(),
    :"schema_name".legal_exigir_publicacion_req_audiencia_abierta(),
    :"schema_name".legal_exigir_publicacion_req_documento_abierta(),
    :"schema_name".legal_publicacion_insert_guard(),
    :"schema_name".legal_bloquear_dependencias_publicacion(uuid),
    :"schema_name".legal_publicacion_update_guard(),
    :"schema_name".legal_validar_publicacion_sellada(uuid),
    :"schema_name".legal_publicacion_constraint_guard(),
    :"schema_name".legal_documento_version_insert_guard(),
    :"schema_name".legal_requisito_version_insert_guard(),
    :"schema_name".legal_version_update_interno_guard()
TO :"import_role";

ALTER ROLE :"import_role" IN DATABASE :"database_name"
    SET search_path TO pg_catalog, :"schema_name", pg_temp;

COMMIT;
```

El `UPDATE(id)` de cuatro tablas existe sólo porque PostgreSQL exige privilegio de update para los
locks de fila usados por el protocolo. Los triggers V27 rechazan cualquier update directo, incluso
`SET id = id`; la prueba PostgreSQL del importador lo acredita. No agregue un grant de tabla
`UPDATE`.

## Contrato de ejecución del comando

El contexto importador acepta únicamente estas variables. El driver es opcional; las otras cuatro
deben estar presentes y la habilitación debe ser exactamente `true`:

```text
ORDENFIX_LEGAL_IMPORT_ENABLED=true
ORDENFIX_LEGAL_IMPORT_DB_URL=<jdbc-url>
ORDENFIX_LEGAL_IMPORT_DB_USERNAME=<rol-importador>
ORDENFIX_LEGAL_IMPORT_DB_PASSWORD=<secreto>
ORDENFIX_LEGAL_IMPORT_DB_DRIVER_CLASS_NAME=<driver-opcional>
```

El launcher agrega sólo dos variables de routing, que no ingresan al contexto Spring:
`ORDENFIX_LEGAL_CLI_JAR` es obligatoria y `ORDENFIX_JAVA_BIN` es opcional. No confundirlas con las
cinco variables del contrato importador.

Obtenga el password desde el gestor de secretos y entréguelo sólo como variable del proceso. No lo
pase por argumentos, archivos versionados, propiedades `-D` ni logs. Cualquier system property JVM
con prefijo `spring.datasource.*` bloquea el comando aunque también existan variables válidas.

La invocación operativa usa el launcher POSIX versionado. Debe distribuirse como archivo executable
junto al jar aprobado —Maven construye los jars, pero no empaqueta este script— y su checksum debe
formar parte del artefacto de release. `ORDENFIX_LEGAL_CLI_JAR` apunta al jar exacto; el launcher
agrega el comando `import` y acepta exactamente tres flags con forma `--nombre=valor`, en cualquier
orden y una sola vez cada uno:

```bash
ORDENFIX_LEGAL_CLI_JAR=/ruta/aprobada/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar \
./scripts/legal-manifest-import.sh \
  --manifest=/ruta/aprobada/publication-manifest.json \
  --confirm-publication-id=<publication-id-exacto> \
  --confirm-manifest-sha256=<64-hex-jcs-exacto>
```

El script requiere `/bin/sh` compatible con POSIX, permiso de ejecución y `java` en `PATH`; puede
recibir una ruta aprobada mediante `ORDENFIX_JAVA_BIN`. Linux y macOS son los sistemas cubiertos.
En Windows, detenga el procedimiento hasta contar con un launcher separado, probado y revisado; no
reemplace este control por una invocación manual del jar.

No continúe si el publication ID o el SHA-256 no coinciden exactamente con el release revisado. El
proceso devuelve `0` para PASS, `2` para BLOCKED y `3` para ERROR. Si el reporte trae
`persisted=null` y `outcome=UNKNOWN`, no confirma commit ni rollback y no expone receipt. No cambie
el release: resuelva la causa y reejecute exactamente el mismo bundle y las mismas confirmaciones.
El resultado puede ser `ALREADY_IMPORTED` si el intento previo confirmó, `IMPORTED` si revirtió y el
retry confirma, o un fallo conocido.

Un stdout ausente, truncado o inválido también deja el resultado operativo indeterminado, aunque el
proceso termine con exit `3`; no lo convierta automáticamente en `UNKNOWN` ni infiera rollback. El
caso acreditado de pipe cerrado ocurrió después del commit y el retry exacto devolvió
`ALREADY_IMPORTED`. Capture stdout y stderr por separado, sin pipelines que puedan cerrar stdout,
y aplique la misma reconciliación exacta.

`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` son interpretadas por la JVM antes de
`main`. El launcher las elimina antes de iniciar Java y su ejecución directa quedó acreditada en el
Corte 6. No lo omita: las defensas dentro de `main` no pueden neutralizar opciones que la JVM ya
procesó. La acreditación del corte se realizó sólo con fixtures sintéticas y no constituye por sí
sola autorización para importar documentos reales.

## Verificación posterior

Abra una conexión nueva usando la credencial importadora obtenida del gestor de secretos. No pase el
password por argumentos del proceso ni lo guarde en este archivo.

1. Confirme `SESSION_USER = CURRENT_USER = import_role`.
2. Confirme que `SHOW search_path` devuelve `pg_catalog, <schema>, pg_temp`.
3. Ejecute el contexto de importación para que `LegalV27ImportSchemaVerifier` y
   `LegalImportPrivilegeVerifier` acrediten el catálogo y los privilegios efectivos.
4. Ejecute primero el release aprobado y luego el replay idéntico. Espere `IMPORTED` y
   `ALREADY_IMPORTED` con el mismo receipt.
5. Con una conexión observer/owner, confirme que no quedaron publicaciones `ABIERTO` ni escrituras
   en transiciones, slots, reemplazos, aceptaciones, metadata o idempotencia.

El Corte 6 acreditó estos pasos con PostgreSQL 16 mediante `postgres:16-alpine` —la ejecución final
observó 16.14—, el jar empaquetado, un rol restringido y fixtures sintéticas: import fresco/replay,
conflicto, drift de privilegios, concurrencia, pérdida de stdout, canaries y recuperación de
resultados inciertos. No se usaron credenciales, documentos ni entornos operativos reales. Antes de
una importación real todavía deben cerrarse el Corte 7, la revisión profesional del release y la
autorización del entorno.

Si cualquiera de los verifiers devuelve `IMPORT_DB_SCHEMA_INCOMPATIBLE` o
`IMPORT_DB_PRIVILEGES_INCOMPATIBLE`, detenga la importación. No repare grants, historial Flyway ni
objetos automáticamente. Compare este runbook con infraestructura como código, corrija mediante un
cambio revisado y vuelva a abrir una conexión nueva.

## Evidencia de cierre

Archive fuera de V27, con acceso restringido:

- entorno, base, schema y rol (sin secreto);
- versión PostgreSQL y checksum V27 `1575269868`;
- revisión y SHA-256 del jar CLI y del launcher pareados, más el modo executable del script;
- identificadores del job y operador;
- SHA-256 del manifiesto confirmado;
- exit code y stdout JSON v2 crudo validado; conserve stderr por separado y sanitizado;
- outcome, UUID, tiempos y receipt del import/replay sólo cuando el reporte lo incluya;
- resultado del retry exacto cuando haya existido `UNKNOWN` o una salida ausente/truncada;
- salida sanitizada de ambos verifiers y logs PostgreSQL relevantes;
- referencia al cambio que aplicó revocaciones/grants y a la revisión profesional del release.

El receipt, los logs PostgreSQL y los resultados de una operación real se archivan sólo cuando esa
operación haya sido expresamente autorizada. Los receipts sintéticos del Corte 6 acreditan el
software, pero no deben reutilizarse ni presentarse como evidencia operativa del release real.

No trate el receipt como comprobante fiscal ni como aceptación del cliente. Es evidencia técnica de
que el grafo editorial exacto fue sellado o reconciliado.
