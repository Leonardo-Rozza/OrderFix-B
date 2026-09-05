# Corte 12 — Cierre de persistencia legal multicontexto V28

Fecha: 2026-09-05

Estado: núcleo y persistencia internos implementados; gate integral aprobado.

## Resultado y alcance

OrdenFix dispone de una revisión `AGGREGATE_V1` determinística para los conjuntos completos de uno
a ocho scopes aplicables, con procedencia física exacta, materialización inmutable, replay y guards
de pertenencia para nuevos lotes. La evidencia V27 conserva sus valores y relaciones como historia
`SCOPE_V1`. V27 y V28 están congeladas; cualquier corrección futura de esquema requiere V29.

La entrada implementada es `LegalRequiredSetAggregateService`, interna y sin controller, endpoint
HTTP o comando CLI nuevo. El contexto `AGGREGATE` es explícito y usa una credencial materializadora
restringida. No está habilitado en la aplicación web ni acepta del navegador un vector autoritativo.

El token representa los conjuntos completos aplicables. La lista de pendientes y la evidencia no
forman parte de sus bytes: filtrar todos los requisitos conserva el token, incluso con
`requisitos: []`. La procedencia física permite distinguir snapshots semánticamente equivalentes
sin exponer UUIDs internos, un mapa de revisiones por contexto ni el fingerprint en el wire futuro.

El resolver productivo mínimo cubre `REGISTRATION → REGISTRO/ADMIN_TITULAR` y
`AUTHENTICATED_PENDING → USO_CONTINUADO`. Los ocho contextos de los fixtures acreditan capacidad;
las reglas adicionales de ciclo de vida y la derivación del actor en HTTP siguen pendientes.

Continúan pendientes controllers/APIs legales, catálogo, `documentSetRevision`, ETag/readiness
pública, cálculo de pendientes y carry-forward, aceptación y registro de aplicación, idempotencia
HTTP, traducción legal a `409/428/503`, enforcement, contenido real, staging y deploy.
`BACKEND-HANDOFF 1` y la Tarea 3 frontend permanecen cerrados.

## Trazabilidad de los cortes

Rama backend: `codex/lanzamiento-publico-backend`. Baseline del gate: `6f2a1cc`.
Rama frontend conservada: `codex/frontend-refactor-checkpoint`.

| Corte | Commit local | Resultado |
| --- | --- | --- |
| Diseño | `5f70e28` | Decisiones de revisión agregada aprobadas |
| Plan | `7caf88a` | Secuencia de cortes y gates |
| 12A | `981b678` | Modelo canónico, perfiles, vectores golden |
| 12B | `b3d8fab` | Gate editorial shared/exclusive |
| 12C | `cd7f2bb` | Store JDBC y replay |
| 12D | `7b6afd6` | V28 definitiva, inventarios, historia y aislamiento |
| 12E | `a65614c` | Servicio interno e integración PostgreSQL |
| 12F | `6f2a1cc` | Concurrencia, causalidad y capacidad |
| 12G | Commit de este documento | Cierre documental y gate integral |

La especificación está en el [diseño](2026-09-01-legal-required-set-aggregate-v28-design.md) y la
evidencia focalizada de 12E/12F en el [plan](2026-09-01-legal-required-set-aggregate-v28-implementation.md).
El contrato futuro se coordina en [FRONTEND_INTEGRATION.md](../../FRONTEND_INTEGRATION.md).

## Frontera transaccional

El servicio obtiene el vector opaco del resolver servidor en memoria. Después, en una única sesión
JDBC y transacción `REQUIRES_NEW/READ_COMMITTED`, ejecuta los preflights V28 de esquema y privilegios,
adquiere el advisory lock compartido y captura la frontera temporal post-lock. Usa la misma key
editorial que importación y mutaciones exclusivas.

El store consulta los punteros mediante una lectura acotada `LIMIT 9 FOR SHARE`, calcula token y
procedencia y busca la identidad física completa. Si existe, replay reconstruye cabecera y miembros
y devuelve `REUSED` sin DML ni UUID nuevo. Si no existe, inserta la cabecera y los miembros en batch;
una carrera se resuelve con `ON CONFLICT DO NOTHING` y relectura en otra sentencia. Una colisión
ajena, digest distinto o pertenencia inválida no se convierte en éxito. Sólo se entrega el receipt
después del commit exitoso. No hay retry automático.

`LegalV28AggregateSchemaVerifier` acredita esquema, funciones, topología y Flyway; no hace replay de
todas las filas. `LegalRequiredSetAggregateReplayVerifier` reconstruye y verifica cada agregado
materializado. Las constraints diferidas impiden confirmar una cabecera parcial.

Las pruebas de aceptación usan un consumidor SQL owner de la base efímera y requisitos reales del
snapshot. Acreditan guards, timestamps y rollback de lote/actos/documentos/metadata; no implementan
un servicio de aceptación. Su futura composición transaccional requiere diseño propio: el servicio
actual `REQUIRES_NEW` no se suma automáticamente a una transacción exterior de aceptación.

## Migraciones e historia

Se acreditaron tres rutas de migración, no tres upgrades adicionales a la instalación limpia:

| Ruta | Evidencia |
| --- | --- |
| Instalación limpia V1→V28 | `PostgresMigrationIT`: valida JPA, cero migraciones pendientes, current exactamente 28 y una fila V28 exitosa |
| V26→V27 vacío→V28 | `PostgresMigrationIT`: conserva usuarios/talleres y ausencia de semillas legales |
| V27 con historia→V28 | `LegalV28UpgradeIT`: conserva bytes canónicos, `xmin`, cardinalidades y relaciones de nueve tablas históricas |

La historia incluye lote multicontexto V27 con revisiones coincidentes, actos, documentos, metadata,
idempotencia y un snapshot completo vacío. Tras el upgrade, el lote sigue como `SCOPE_V1`, con perfil
y agregado nulos; se puede crear un lote `AGGREGATE_V1` y todo INSERT nuevo `SCOPE_V1` falla con
`23514`. No se fabrica historia después de migrar ni se aplica backfill de procedencia.

Las suites editoriales que necesitan el contrato histórico conservan su target V27. Las suites
latest y de agregados llegan a V28; `LegalEditorialProcessIT` ejecuta ambos roles restringidos y los
JARs sobre latest. Ninguna base de aplicación, staging o producción fue migrada durante el cierre.

| Migración | Checksum Flyway | SHA-256 del archivo |
| --- | ---: | --- |
| V27 | 1575269868 | `52fd5f3eda14fde228e218f127b5e9362c8542dc7e26df7b502ba65061332b9b` |
| V28 | 1900377028 | `1227c8261cfcca1263a0b2105bf0dc797c1f59f3b5bdc71225464fc4aa154a5e` |

V27 es idéntica al baseline del plan; V28 es idéntica al commit que la congeló en 12D.

## Inventario y privilegios

El delta V28 agrega dos tablas (`legal_requisito_agregados`, `legal_requisito_agregado_scopes`), tres
columnas en lotes (`revision_scheme`, `perfil`, `agregado_id`), 25 constraints, tres índices
explícitos y ocho triggers. Crea siete funciones y reemplaza dos guards de aceptación. Los agregados
usan UUID y no agregan secuencias. El default temporal de `revision_scheme` se elimina al terminar
la migración.

El inventario acreditado de V28, más amplio que el delta SQL, cubre ocho tablas, 73 columnas,
75 constraints, 30 índices, 27 triggers, tres secuencias históricas y 20 funciones. Se compone con
los preflights V27 e incluye las dependencias `users` y `flyway_schema_history`; el conjunto legal
latest contiene 27 tablas. `LegalV28AggregateSchemaVerifierIT` contrasta fingerprints, checksum y
topología, acredita schema alternativo y rechaza deriva de catálogo, funciones y dependencias.

El materializador tiene SELECT sobre cuatro tablas del grafo y `flyway_schema_history`, INSERT sólo
en las dos tablas agregadas y UPDATE exclusivamente sobre `conjuntos_actuales.conjunto_id` para
permitir `FOR SHARE`. El guard V27 sigue rechazando todo UPDATE real o no-op. No obtiene DELETE,
escritura de lotes/evidencia, secuencias, DDL, ownership, herencia de roles ni permisos de aplicación.
Su allowlist de EXECUTE contiene siete funciones SECURITY INVOKER.

`PostgresMigrationIT` exige cero funciones `legal_%` sin
`search_path=pg_catalog, <schema>, pg_temp`. V28 revoca PUBLIC sobre sus nueve funciones
creadas/reemplazadas. No se atribuye a la instalación limpia una revocación global de EXECUTE sobre
todas las funciones históricas de V27: los fixtures de roles restringidos revocan PUBLIC EXECUTE
en el esquema y los verificadores contrastan capacidades efectivas exactas.

`LegalEditorialProcessIT` migra latest, provisiona importador y editorial y verifica ambos antes
de cada caso; sus allowlists V27 no adquieren permisos sobre objetos V28. Las pruebas de privilegios
agregados rechazan owner, superusuario, INHERIT, privilegios extra y ejecución ampliada.
`LegalRequiredSetAggregateDatabaseIsolationIT` acredita datasource dedicado y cero DML V28 para el
rol web de prueba. Esto es evidencia sobre roles efímeros, no una certificación de grants de una
base productiva.

## Concurrencia, causalidad y capacidad

La corrida integral vuelve a ejecutar los escenarios de 12F: shared/shared simultáneos,
shared/exclusive en ambos sentidos, carrera idéntica con un CREATED y un REUSED, promoción de dos
scopes sin mezcla y procedencias físicas diferentes con token equivalente.

`creado_en` y `aceptado_en` quedan después de adquirir el lock y de la observación, aunque la
transacción haya empezado antes de esperar. Timeout `55P03`, deadlock `40P01` y terminación de sesión
después de DML eliminan el agregado abortado. Las constraints diferidas revierten cabeceras parciales
y evidencia incompleta. El retry explícito conserva token/procedencia y luego converge a REUSED;
no existe retry interno ni se fabrica un resultado exitoso tras perder la conexión.

La capacidad compara uno y ocho contextos desde la composición Spring productiva:

| Scopes | Ejecuciones CREATED | Ejecuciones REUSED | Filas leídas por llamada |
| --- | ---: | ---: | ---: |
| 1 | 55 | 53 | 1473 |
| 8 | 55 | 53 | 1487 |

El grafo usa seis ejecuciones al crear y cuatro al reutilizar, con `2N + 2` filas; preflight, lock y
control no crecen con N. El batch cuenta como una ejecución JDBC lógica; la medida no cuenta SQL
interno de triggers ni constituye un SLA o benchmark de throughput. Shared/exclusive se distinguen
sin cambiar las categorías históricas de métricas. El sentinel noveno es una inyección sobre el
resultado JDBC de ocho filas reales, porque el enum y la unicidad impiden nueve punteros válidos.

## Gate integral fresco

```text
Amazon Corretto: 21.0.10+7-LTS
Maven Wrapper:   3.9.11
Flyway:          11.14.1
Testcontainers:  2.0.5
PostgreSQL:      16.14 (postgres:16-alpine)
Surefire/Failsafe: 3.5.5
```

Comando ejecutado desde el backend, con Docker disponible y Java 21 explícito:

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw clean verify
git diff --check
```

Resultado: `BUILD SUCCESS`, **09:13 min**, finalizado a las **11:29:59 -03:00** del 2026-09-05.
Los conteos se contrastaron con todos los XML de `target/surefire-reports` y `target/failsafe-reports`
generados por esta corrida desde cero:

| Suite | Pruebas | Fallos | Errores | Omitidas |
| --- | ---: | ---: | ---: | ---: |
| Surefire | 4366 | 0 | 0 | 0 |
| Failsafe | 306 | 0 | 0 | 0 |

No hubo fallos que exigieran cambiar código o repetir/ampliar el gate. La auditoría documental
independiente contrastó el alcance de runtime, los inventarios y la evidencia de migración/roles.

El build verifica además ausencia de `application-secret.properties` en ambos JARs. Los mensajes
esperados de timeout, deadlock, sesión terminada y rollback JDBC corresponden a inyecciones de fallo;
el resultado se toma de Surefire/Failsafe, no de la mera presencia de mensajes ERROR en el log.

## Artefactos de esta ejecución

| Artefacto | Bytes | SHA-256 |
| --- | ---: | --- |
| `mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar` | 90382843 | `53cb1f7c01b7c2f521740f33b1455a2c0f7b367cbc07f592a213b1e141a17dda` |
| `mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar` | 90382847 | `1eb67149829249b9a6eb95e2fa8bb62403c8cfff3ae0df4c8bfe804777faee79` |

Ambos están en `target/`, contienen las 28 migraciones y no contienen `application-secret.properties`
ni el agente de fallos de stdout de pruebas. El Start-Class del primero es `MvgrReparacionesBackendApplication` y
el del segundo `LegalManifestCli`; sus manifiestos no declaran atributos de agente. Los hashes
identifican esta corrida: Maven no fija `outputTimestamp` y no se afirma build reproducible byte a byte.
Los artefactos, logs y reportes temporales no se versionan.

## Cierre y siguiente frontera

12G modifica únicamente este cierre, diseño, plan, README y FRONTEND_INTEGRATION. El diff productivo
12A–12F se limita a 21 archivos Java y la migración V28: no agrega controllers, DTO HTTP, endpoints ni
contenido legal real. El frontend no se modifica y conserva sus directorios no versionados
`.agents/` y `public/OrdenFix project naming/`.

El corte termina en un commit atómico local `docs(legal): cierra persistencia multicontexto v28`,
sin push ni deploy. El siguiente paso es diseñar la capa HTTP y su composición con aceptación,
catálogo y readiness; no se inicia automáticamente dentro de este corte.
