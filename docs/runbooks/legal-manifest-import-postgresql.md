# Runbook PostgreSQL del importador legal V27

Estado: operativo para el contexto DB del Corte 4. El comando `import` se habilita recién en el
Corte 5.

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

Si cualquiera de los verifiers devuelve `IMPORT_DB_SCHEMA_INCOMPATIBLE` o
`IMPORT_DB_PRIVILEGES_INCOMPATIBLE`, detenga la importación. No repare grants, historial Flyway ni
objetos automáticamente. Compare este runbook con infraestructura como código, corrija mediante un
cambio revisado y vuelva a abrir una conexión nueva.

## Evidencia de cierre

Archive fuera de V27, con acceso restringido:

- entorno, base, schema y rol (sin secreto);
- versión PostgreSQL y checksum V27 `1575269868`;
- identificadores del job y operador;
- SHA-256 del manifiesto confirmado;
- outcome, UUID, tiempos y receipt del import/replay;
- salida sanitizada de ambos verifiers y logs PostgreSQL relevantes;
- referencia al cambio que aplicó revocaciones/grants y a la revisión profesional del release.

No trate el receipt como comprobante fiscal ni como aceptación del cliente. Es evidencia técnica de
que el grafo editorial exacto fue sellado o reconciliado.
