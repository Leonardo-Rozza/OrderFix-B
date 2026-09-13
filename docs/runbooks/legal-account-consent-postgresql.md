# Mantenimiento PostgreSQL de metadatos de consentimiento — 15O

Este procedimiento corresponde al consumidor interno
`LegalAcceptanceRetentionService` y a su contexto aislado
`LegalAcceptanceMaintenanceConfiguration`. Aplica exclusivamente vencimientos ya
persistidos en V27/V29. V27–V34 permanecen congeladas: no añade migraciones,
plazos de retención ni una política de supresión de cuentas.

La implementación y sus pruebas se deben acreditar con el gate del corte antes
de habilitar un entorno compartido. Este runbook no certifica un despliegue, una
corrida de mantenimiento ni una eliminación de datos reales.

## Alcance de la operación

`runNext()` selecciona cabeceras con `purgado_en IS NULL` y
`retener_hasta <= transaction_timestamp()`. Para cada cabecera elegible convierte
todos sus campos técnicos en tombstones y luego fija `purgado_en`, en la misma
transacción. Sólo pone a `NULL` `ciphertext`, `tag` y `longitud_original`; conserva
las filas, `lote_id`, `tipo`, `key_version` y `nonce`. Los nonces conservados siguen
ocupando su unicidad y no se pueden reciclar.

También elimina resultados de `legal_idempotencia_resultados` y
`legal_idempotencia_sin_actos` cuyo `expires_at` ya venció; para el segundo elimina
primero sus referencias. Las guardas congeladas vuelven a exigir vencimiento,
reserva idempotente y completitud. Mientras una fila de resultado exista, el
consumidor de requests conserva su comportamiento de replay, incluso vencida.
La purga retira ese resultado técnico, no las aceptaciones que documenta.

Se conservan lotes, actos, snapshots documentales, catálogo y evidencia
contractual. No se eliminan talleres, usuarios, fotos, exportaciones ni efectos
externos de cierre. La operación admite mantenimiento de talleres inactivos o
restringidos; no cambia su estado ni restaura acceso. No llama a Cloudinary,
Mercado Pago, email ni otro proveedor.

Un tombstone en la base activa no acredita la supresión de backups, WAL,
réplicas, logs o copias externas. La gestión de esas copias y la supresión integral
de una cuenta requieren sus propios procedimientos y decisiones de retención.
Véanse [solicitudes de datos y cierre](solicitudes-datos-y-cierre.md) y
[recuperación de backup de cierre](cierre-recuperacion-backup.md).

## Contexto y configuración explícitos

Esta configuración no es un componente escaneado ni un import de la aplicación
HTTP. No contiene controller, endpoint, CLI de producción o adaptador web; cambiar
un flag en la aplicación habitual no abre por sí mismo este contexto. Tampoco se
mezcla con los contextos de importación editorial, lectura, aceptación o registro.
El guard de marcadores rechaza la combinación de consumidores DB legales.

Un operador debe componer explícitamente un contexto Spring independiente con
`LegalAcceptanceMaintenanceConfiguration`, aportar sus propiedades por el gestor
de configuración autorizado, obtener `LegalAcceptanceRetentionService` y llamar
`runNext()`, o habilitar el scheduler de ese mismo contexto. Cerrar ese contexto
cierra sus recursos. No se importa Flyway y no existe fallback al datasource de
la aplicación ni a sus credenciales.

| Propiedad | Contrato |
| --- | --- |
| `ordenfix.legal.maintenance.enabled` | Ausente o `false`: consumidor apagado. Sólo el literal `true` lo habilita en el contexto explícito. |
| `ordenfix.legal.maintenance.scheduled` | Ausente o `false`: no se crea scheduler. `true` exige además `enabled=true`. |
| `ordenfix.legal.maintenance.jdbc-url` | Obligatoria al habilitar; URL propia `jdbc:postgresql://…` hacia la base destinada al mantenimiento. |
| `ordenfix.legal.maintenance.username` | LOGIN nominal dedicado, distinto de los consumidores de requests y de los propietarios. |
| `ordenfix.legal.maintenance.password` | Credencial propia suministrada por el gestor de secretos, fuera de logs y repositorios. |

Los flags sólo aceptan `true` y `false` exactos: mayúsculas, espacios y otros
valores fallan al componer el contexto. No hay valores de retención o keyrings de
mantenimiento que completar. La URL no admite sustituir usuario, contraseña o
límites mediante opciones JDBC; las únicas opciones aceptadas son `sslmode`,
`sslrootcert`, `sslcert`, `sslkey`, `sslpassword` y `loggerLevel`. Resolver TLS y
credenciales mediante la configuración del entorno y mantener desactivado el
trazado que pudiera registrar SQL o parámetros sensibles.

El pool `legal-maintenance` tiene mínimo cero y máximo dos conexiones, espera de
adquisición y validación de 1 s, conexión/login de 1 s, socket de 6 s y cancelación
de 1 s. Identifica sus conexiones como `ordenfix-legal-maintenance`. Su creación
no acredita conectividad ni privilegios: cada operación pasa su preflight antes
del trabajo.

## Rol dedicado y acreditación

Usar un LOGIN propio `NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
NOBYPASSRLS`, sin membresías recibidas o delegadas, ownership de bases/esquemas/
relaciones/funciones, DDL ni privilegios con opción de conceder. La identidad debe
coincidir exactamente con `CURRENT_USER`, `SESSION_USER` y el username configurado;
`SET ROLE` no sustituye este contrato.

El rol sólo tiene `CONNECT` a la base objetivo y `USAGE` sobre `public`. No tiene
`CREATE` o `TEMPORARY` en bases ni `CREATE` en esquemas. La sesión debe conservar
`search_path = pg_catalog, public, pg_temp`, sin un esquema temporal abierto,
`session_replication_role = origin` y `lo_compat_privileges = off`.

Antes de seleccionar candidatos, `LegalAcceptanceMaintenanceBoundary` exige el
esquema exacto con `LegalV29AcceptanceSchemaVerifier`, incluida su compatibilidad
con V34, y los privilegios efectivos exactos de
`LegalAcceptanceMaintenancePrivilegeVerifier`. Una credencial insuficiente o más
potente que el inventario falla cerrado. No se amplían los roles de requests para
permitir este mantenimiento.

### Inventario nominal de relaciones

Todas las relaciones siguientes pertenecen a `public`. No hay `INSERT`,
`TRUNCATE`, `REFERENCES`, `TRIGGER`, permisos de secuencias ni `UPDATE` de tabla
completa. El resto de las tablas y columnas queda fuera de la allowlist.

`SELECT` de tabla completa:

- `flyway_schema_history`.
- `legal_requisito_lineas`, `legal_requisito_audiencias`,
  `legal_requisito_versiones`, `legal_requisito_documentos`.
- `legal_documento_lineas`, `legal_documento_versiones`.
- `legal_aceptaciones`, `legal_aceptacion_documentos`.
- `legal_aceptacion_metadatos`.
- `legal_idempotencia_sin_actos`.

Los validadores `SECURITY INVOKER` congelados leen la evidencia canónica y la
cabecera completa. El de resultados sin actos consulta además `xmin`, que exige
lectura de relación. Esa lectura incluye HMAC y versiones identificadoras ya
persistidos; no contiene material de claves AES o HMAC. El proceso no recibe
ningún keyring y no descifra metadatos.

| Tabla | Columnas adicionales con `SELECT` |
| --- | --- |
| `talleres` | `id` |
| `legal_aceptacion_lotes` | `id`, `taller_id`, `audiencia` |
| `legal_aceptacion_metadatos_cifrados` | `lote_id`, `tipo`, `tombstone_en` |
| `legal_idempotencia_resultados` | `id`, `taller_id`, `user_id`, `operacion`, `route_template`, `scope_hmac`, `idempotency_key_hmac`, `expires_at` |
| `legal_idempotencia_sin_actos_referencias` | `resultado_id`, `aceptacion_id`, `taller_id`, `user_id` |

| Tabla | Columnas con `UPDATE` |
| --- | --- |
| `legal_aceptacion_metadatos` | `purgado_en` |
| `legal_aceptacion_metadatos_cifrados` | `ciphertext`, `tag`, `longitud_original`, `tombstone_en` |
| `legal_idempotencia_resultados` | `id`, exclusivamente para habilitar el lock de fila; la guarda rechaza mutaciones del resultado |
| `legal_idempotencia_sin_actos` | `id`, con la misma restricción |

`DELETE` sólo sobre `legal_idempotencia_resultados`,
`legal_idempotencia_sin_actos` y `legal_idempotencia_sin_actos_referencias`.

Poder poner a `NULL` el contenido cifrado no concede leerlo. Se prohíbe `SELECT`
sobre `ciphertext`, `tag`, `nonce`, `key_version`, `longitud_original` e `id` de
`legal_aceptacion_metadatos_cifrados`, incluso como permiso de una sola columna.
No se leen contraseña, email, JWT o payload de exportación.

### Funciones y superficie PostgreSQL

El único `EXECUTE` directo en el esquema de aplicación corresponde a estas
firmas de `public`; las dependencias transitivas se incluyen explícitamente:

```text
legal_exigir_read_committed()
legal_fila_es_transaccion_actual(xid)
legal_metadata_cifrada_update_guard()
legal_metadata_header_update_guard()
legal_validar_aceptacion(uuid)
legal_validar_lote_aceptacion(uuid)
legal_aceptacion_constraint_guard()
legal_idempotencia_update_delete_guard()
legal_exigir_lock_idempotente_v29(character varying, character varying, character varying, character varying)
legal_idempotencia_tupla_guard_v29()
legal_validar_idempotencia_sin_actos_v29(uuid)
legal_idempotencia_sin_actos_constraint_guard_v29()
legal_idempotencia_sin_actos_mutation_guard_v29()
legal_idempotencia_sin_actos_ref_delete_guard_v29()
```

Los triggers de cierre existentes se ejecutan como tales; el rol no recibe
`EXECUTE` directo sobre sus helpers `SECURITY DEFINER`. Los builtins normales de
PostgreSQL necesarios para catálogos, tiempo y advisory locks transaccionales
conservan su superficie base acreditada.

Se prohíbe adquirir advisory locks de **sesión**: las firmas de uno y dos
argumentos de `pg_advisory_lock`, `pg_advisory_lock_shared`,
`pg_try_advisory_lock` y `pg_try_advisory_lock_shared`. Se prohíben también
`lo_creat(integer)`, `lo_create(oid)`, `lo_from_bytea(oid, bytea)`,
`lo_import(text)` y `lo_import(text, oid)`, además de ownership o ACL directas/
PUBLIC de lectura o escritura sobre large objects.

No deben quedar privilegios indirectos por `PUBLIC`: el verificador rechaza
CONNECT/CREATE/TEMP públicos en las bases inspeccionadas, ACL públicas de tablas
o columnas de aplicación y EXECUTE que permita salir del inventario. También
revisa ownership, grants y deriva respecto de los privilegios iniciales de los
esquemas de sistema, así como grants de parámetros que puedan alterar la
frontera. La comparación incluye permisos efectivos, no sólo grants nominales.

`LegalRestrictedMaintenanceRoleFixture` provisiona esta combinación únicamente
en PostgreSQL descartable con nombre `ordenfix_legal_maintenance_*`, rechaza
reutilizar un rol y sanea la representación de sus credenciales. Sus revocaciones
PUBLIC afectan el clúster efímero dedicado: **no ejecutar ni copiar ese fixture en
producción o una base compartida**. El aprovisionamiento real exige inventariar
los demás consumidores, coordinar sus grants explícitos y verificar el rol final
sin debilitar el preflight para hacer pasar un entorno incompatible.

## Transacción, límites y concurrencia

Cada invocación usa datasource, pool y `DataSourceTransactionManager` propios,
`REQUIRES_NEW`, `READ_COMMITTED` y una transacción de escritura con presupuesto
total de 15 s. No hereda la transacción web o la del caller. Los timeouts SQL se
limitan a 5 s y los de locks a 1 s, además del presupuesto restante; ejecución,
lectura, commit y liberación comparten el deadline. La cancelación y cierre del
driver pueden terminar después de ese plazo: no es una garantía de tiempo de
respuesta ni permite devolver éxito después de vencido.

El lote procesa como máximo diez cabeceras, diez resultados con actos y diez
resultados sin actos: hasta veinte campos técnicos y 20.480 referencias. Cada
categoría consulta un candidato adicional como sentinela; una cabecera sólo
admite IP y USER_AGENT opcional, y un resultado sin actos como máximo 2.048
referencias. Formas incompletas o pertenencias inconsistentes revierten el lote.

El orden de admisión es gate compartido por taller, reservas idempotentes con
orden estable de sus claves físicas, gate editorial compartido y locks de fila.
Los gates usan `try` transaccional y las filas `FOR UPDATE SKIP LOCKED`: trabajo
ocupado queda para otra invocación, sin forzar el cierre ni bloquear el replay
para conseguir una limpieza. Se revalidan vencimiento e identidad bajo lock y
se fuerzan las constraints diferidas antes de confirmar.

La respuesta `Batch` sólo contiene contadores de cabeceras, campos, resultados,
referencias y omitidos, más `pending`. Los contadores salen después del commit y
de liberar la conexión; no identifican personas o contenido. `pending=true`
indica un sentinela adicional o trabajo omitido en esta observación. `false` no
certifica una cola global vacía: nuevas filas pueden vencer o aparecer después.
La repetición selecciona lo que siga vencido; no vuelve a purgar tombstones ya
cerrados ni resultados eliminados.

## Scheduler, resultados y recuperación

El scheduler es opcional y está apagado por defecto. En el contexto explícito,
`enabled=true` y `scheduled=true` crean una invocación inicial tras 60 s y las
siguientes 60 s después de finalizar la anterior. No se instala cron, monitor o
servicio externo y no existe una elección de líder entre réplicas.

`lastOutcome` es estado en memoria interno del scheduler, reiniciado al recrear
el contexto. No es un endpoint, un ledger ni un recibo durable:

| Valor | Interpretación |
| --- | --- |
| `NOT_RUN` | Aún no hubo invocación en esta instancia. |
| `BATCH_COMPLETED` | Se entregó un resultado confirmado, sin pendiente observado en ese lote. No acredita una purga global. |
| `WORK_REMAINS` | Se entregó un resultado confirmado con `pending=true`. |
| `RETRY_REQUIRED` | La invocación no produjo un resultado acreditado; revisar disponibilidad, configuración o fallos antes de repetir. |
| `RECONCILIATION_REQUIRED` | Resultado `UNKNOWN`: no se conoce con certeza la persistencia o entrega del resultado; requiere revisión. |

Los eventos de éxito registran sólo el enum en INFO y los fallos sólo el enum en
WARN. No se registran SQL, causas, credenciales, IDs, IP, UA, HMAC, claves, payloads
o datos de clientes. El recolector, la retención de logs, los umbrales y la alerta
externa siguen siendo responsabilidad del despliegue; este corte no los conecta.

Ante un fallo:

1. No emitir un acuse de limpieza exitosa ni afirmar que no hubo cambios ante
   `UNKNOWN`. Commit, respuesta o cierre de conexión pueden haber resultado
   inciertos. El estado en memoria o un log no resuelven esa incertidumbre.
2. Ante `INVALID_BOUNDARY`, corregir la composición aislada; ante `UNAVAILABLE`,
   revisar identidad, ACL, esquema, conectividad y contención sin conceder
   permisos adicionales fuera del inventario. Conservar el contexto detenido si
   el entorno no acredita su configuración.
3. Para `UNKNOWN`, revisar el estado actual mediante la conexión de mantenimiento
   acreditada: cabeceras cerradas, tombstones, referencias y resultados que aún
   existen. No consultar contenido cifrado ni cambiar expiraciones para forzar
   una conclusión. Un conteo agregado no permite reconstruir por sí solo qué
   filas modificó la invocación incierta.
4. Es admisible repetir `runNext()` con el mismo preflight: las transiciones son
   idempotentes respecto de las filas que sigan presentes y vencidas. El scheduler
   también puede invocar otro lote en su siguiente ciclo; no hay pausa durable
   automática por `UNKNOWN`. Si la revisión requiere detenerlo, cerrar el contexto
   o recomponerlo con `scheduled=false`.
5. Un reintento confirmado permite informar sus propios contadores. No convierte
   retroactivamente el intento incierto en un recibo durable, ni acredita borrado
   total, supresión de cuenta o eliminación de backups. Si se necesita esa
   evidencia por operación, requiere otro mecanismo y alcance.

## Rotación y réplicas

El mantenimiento no necesita claves AES/HMAC y no es una herramienta para
rotarlas. Las escrituras y el replay sí dependen de sus keyrings: distribuir las
versiones requeridas a todas las réplicas antes de cambiar coordinadamente la
versión activa, y retirar del tráfico cualquier réplica que no acredite el
keyring previsto. No habilitar escritores con conjuntos incompatibles.

Conservar las versiones HMAC necesarias para lookup y locks de todos los
resultados retenidos en ambos ledgers, incluso vencidos que todavía no se hayan
purgado. El vencimiento por sí solo no autoriza abandonar una versión. Conservar
las claves AES necesarias para metadatos aún vivos y para los procedimientos de
recuperación aprobados. No retirar una clave basándose únicamente en el último
contador de mantenimiento, ni reutilizar un número de versión para otra clave.

Los tombstones conservan `key_version` y `nonce` aunque se retiren posteriormente
claves mediante un procedimiento autorizado. No borrar o reconstruir esa reserva
para simplificar una rotación. Coordinar la política de claves con réplicas,
backups y su recuperación: esta purga local no acredita destrucción criptográfica
de todas las copias. Una restauración de backup exige cuarentena y conciliación
según el runbook de cierre antes de volver a habilitar servicios.
