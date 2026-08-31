# Corte 9 — Diseño de reconciliación editorial de commits ambiguos

Fecha: 2026-08-30

Estado: aprobado por el usuario; implementación en curso — 9A, 9B y 9C completados al 2026-08-31

Rama backend: `codex/lanzamiento-publico-backend`

Plan maestro:

- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`.

## Contexto

Los apply editoriales PROMOTE, REPLACE y RETIRE ya distinguen commit confirmado, rollback
confirmado y finalización indeterminada. Cuando el transaction manager no puede acreditar el
resultado después de entrar en la frontera de commit, el contrato actual devuelve
`UNKNOWN/persisted=null`, oculta el receipt tentativo y exige un retry manual exacto.

Corte 9 agrega una observación automática y conservadora dentro de esa misma invocación. No
reintenta la mutación: espera que la transacción original se desvincule, abre una frontera nueva de
solo lectura, toma el mismo advisory lock y clasifica el estado real de PostgreSQL. La evidencia
debe ser autoritativa; si no lo es, `UNKNOWN` se conserva.

## Decisión

Se implementará un reconciliador dedicado, package-private y `SELECT-only`, separado del writer y
activado únicamente después de una completion `UNKNOWN`.

Se descartaron:

1. reconciliar dentro del flujo mutante, porque mezclaría recuperación y escritura;
2. agregar comandos `reconcile-*`, porque ampliaría la CLI y la carga operativa sin aportar
   evidencia adicional;
3. reintentar DML automáticamente, porque podría duplicar una operación cuyo commit sí ocurrió.

La reconciliación no agrega ledger, tabla, migración, endpoint, scheduler, API, frontend ni deploy.
V27, JSON v3, reportes v1/v2 y el contrato del importador permanecen sin cambios.

## Objetivos

- Resolver automáticamente un commit ambiguo cuando PostgreSQL contiene evidencia completa.
- Devolver `ALREADY_APPLIED/persisted=true` sólo ante el postestado exacto.
- Devolver `ERROR/persisted=false` sólo cuando el source completo continúa exacto bajo una nueva
  transacción y el mismo lock.
- Mantener `UNKNOWN/persisted=null` ante estado parcial, conflicto o falta de evidencia.
- Reconstruir todo receipt confirmado desde PostgreSQL y no desde memoria tentativa.
- Preservar PROMOTE, REPLACE, RETIRE, import v2 y los reportes existentes.

## Fuera de alcance

- ledger editorial, V28 o cualquier cambio de schema;
- nuevo reason code público o cambio de forma del receipt;
- reintento automático de writers;
- comando, endpoint o pantalla de reconciliación;
- carreras multithread, capacidad, procesos JAR y launcher exhaustivos, reservados para Corte 10;
- runbooks, staging y coordinación cross-repo, reservados para Corte 11;
- push o deploy.

## Invariantes

1. `UNKNOWN` nunca se convierte en éxito por una excepción, SQLState, `operationId`, SHA externo o
   receipt tentativo.
2. El reconciliador no recibe writers y no puede ejecutar DML.
3. La transacción mutante anterior debe haber terminado y quedado desvinculada antes de observar.
4. La observación usa `REQUIRES_NEW`, `READ_COMMITTED`, modo read-only y el mismo advisory lock.
5. El transaction manager es exactamente `DataSourceTransactionManager`, usa el mismo datasource
   y conserva `rollbackOnCommitFailure=false`.
6. Schema verifier y privilege verifier exactos se ejecutan, en ese orden, antes del planner.
7. Planner y verifier comparten una única sesión JDBC dentro de esa frontera.
8. Sólo el postestado completo permite `ALREADY_APPLIED`.
9. Sólo el source completo y exacto permite `ERROR/persisted=false`.
10. Todo estado parcial, distinto o no observable conserva `UNKNOWN`.
11. Un resultado reconciliado nunca reutiliza metadata tentativa.
12. El importador conserva su wrapper y su semántica actuales.

## Componentes

### `LegalEditorialTransactionState`

Fachada editorial sobre `LegalTransactionCompletionState`. Mantiene su matriz monótona de
completion y registra, como evidencia efímera interna, si llegó a construirse un execution plan
exacto antes de entrar en la frontera de commit.

El facade puede entregar el receipt sólo cuando la completion está confirmada. Si la persistencia
es `UNKNOWN`, el receipt tentativo sigue oculto y la única información reutilizable es el contexto
validado necesario para una observación posterior. `LegalImportTransactionState` no cambia.

### `LegalEditorialCommitReconciler`

Servicio interno de solo lectura. Recibe la operación validada y la evidencia de que existió un
plan exacto; abre el gate read-only y realiza una observación protegida por el advisory lock dentro
de una única transacción y sesión JDBC. No recibe `LegalEditorialMutationWriter`, no llama `apply`
y no puede repetir comandos.

El planner existente vuelve a observar el mismo target y, para REPLACE/RETIRE, el mismo bundle,
plan, `operationId` y hashes validados. Ese replan es únicamente un clasificador SELECT-only: no
atribuye autoría al commit histórico ni sustituye la comprobación exacta del verifier.

- un plan replay `changeRequired=false`, confirmado por el post-state verifier, prueba el
  postestado exacto y reconstruye el receipt;
- un plan fresco `changeRequired=true` con el source completo todavía exacto prueba que la
  mutación no quedó aplicada;
- BLOCKED, ERROR, mismatch, observación incompleta o excepción no prueban ninguna conclusión.

V27 no conserva la autoría histórica de un comando. Por eso se acredita el postestado completo,
no que el `operationId` observado haya sido quien lo produjo.

### `LegalEditorialApplyService`

Conserva el flujo existente para success y rollback conocido. Sólo cuando el snapshot
transaccional sea `UNKNOWN`, y después de que `executeMutable` haya desenrollado, invoca una vez al
reconciliador. No hay recursión ni segundo writer.

### Configuración

`LegalEditorialDatabaseConfiguration` cablea el reconciliador con el gate read-only existente, el
planner, el post-state verifier, el schema verifier y el privilege verifier exactos que comparten
el mismo `JdbcTemplate`. El constructor falla cerrado ante participantes o preflights ajenos, gate
mutable, datasource distinto, transaction manager no exacto o configuración distinta de
`REQUIRES_NEW`, `READ_COMMITTED`, read-only y `rollbackOnCommitFailure=false`.

## Flujo

1. La CLI valida bundle, plan y confirmaciones como hoy.
2. `LegalEditorialApplyService` abre el gate mutable.
3. Se registra el completion state, se lee `transaction_timestamp()` y se construye el execution
   plan exacto.
4. Si corresponde, el writer aplica el delta; verifier, constraints y readiness producen un
   receipt tentativo.
5. Commit normal devuelve APPLIED o ALREADY_APPLIED sin reconciliación.
6. Rollback confirmado mapea el fallo actual con `persisted=false`, sin reconciliación.
7. Completion indeterminada termina y desvincula la transacción original.
8. Si nunca existió un execution plan exacto, se devuelve UNKNOWN.
9. En caso contrario se abre una nueva transacción read-only y se toma el mismo advisory lock.
10. Planner y verifier realizan y revalidan la observación bajo la misma transacción, sesión y
    advisory lock. `READ_COMMITTED` conserva snapshots por statement; el lock serializa a los
    actores cooperativos y la verificación final acredita la conclusión.
11. El resultado reconciliado se transforma a la matriz pública existente.

No se ejecuta un segundo intento mutante dentro de la misma invocación. Un retry posterior del
operador debe repetir bundle, plan, `operationId`, hashes y confirmaciones idénticos.

## Matriz de resultados

| Evidencia posterior | Resultado | `persisted` | Receipt |
|---|---|---:|---|
| Postestado completo exacto | `ALREADY_APPLIED` | `true` | Releído desde PostgreSQL |
| Source completo exacto | `ERROR` | `false` | No |
| Estado parcial o diferente | `UNKNOWN` | `null` | No |
| DB inaccesible o sesión fallida | `UNKNOWN` | `null` | No |
| Advisory lock no adquirido | `UNKNOWN` | `null` | No |
| Execution plan original ausente | `UNKNOWN` | `null` | No |

Un BLOCKED producido por un replan no demuestra rollback y siempre cae en UNKNOWN. La mera ausencia
de una parte del postestado tampoco demuestra que la operación no haya sido aplicada.

## Errores y redacción

- El reconciliador devuelve una clasificación interna `SOURCE_EXACT` sin fabricar un issue. Luego
  `LegalEditorialApplyService` normaliza exclusivamente el throwable original mediante
  `LegalEditorialFailureMapper`.
- El issue original sólo se conserva si su severidad y código pertenecen a la matriz ERROR de
  `LegalEditorialApplyResult`; cualquier otro caso usa `EDITORIAL_OBSERVATION_FAILED`.
- Un fallo ocurrido dentro del reconciliador nunca se usa como evidencia concluyente ni como issue
  de `ERROR/persisted=false`: conserva UNKNOWN.
- No se incorpora un reason code público nuevo.
- La reconciliación fallida no reemplaza `COMMIT_OUTCOME_UNKNOWN` por un error más concluyente.
- `UNKNOWN` conserva la identidad validada del input —incluidos `operationId`, SHA del plan y
  readiness esperado cuando corresponda—, pero mantiene nulos `publicationUuid`, `appliedAt`,
  readiness autoritativo, conteos de estado/delta y receipt derivados de DB.
- El JSON v3 mantiene forma, orden y nullability; stdout continúa siendo un único JSON y stderr
  sólo admite el resumen constante ya allowlisteado.
- APPLIED/ALREADY_APPLIED conservan exit 0; ERROR y UNKNOWN conservan exit 3.

## Estrategia de pruebas

### Unitarias

- todas las completion states y transiciones monótonas del facade editorial;
- plan ausente, receipt tentativo oculto y receipt confirmado visible;
- postestado exacto, source exacto, parcial y no observable;
- sólo `APPLICABLE+changeRequired=true` prueba source exacto; planner BLOCKED/ERROR, excepción JDBC
  y lock no adquirido conservan UNKNOWN;
- replay cuyo verifier no confirma el postestado completo conserva UNKNOWN;
- una sola apertura read-only y cero invocaciones a writers;
- reconciliación sólo para snapshot UNKNOWN;
- constructor fail-closed ante gate mutable, transaction manager/flag incorrecto, preflights o
  participantes JDBC ajenos;
- receipt tentativo envenenado nunca se filtra y el confirmado se reconstruye desde PostgreSQL;
- cambiar `operationId` o SHA conservando un postestado exacto mantiene `ALREADY_APPLIED`: esas
  identidades no acreditan autoría ni impiden acreditar el estado;
- SOURCE_EXACT conserva un error original allowlisteado y normaliza cualquier blocker, issue ajeno
  o severidad inválida a `EDITORIAL_OBSERVATION_FAILED`;
- PROMOTE, REPLACE y RETIRE recorren el mismo flujo;
- retry posterior a estado parcial/diferente no ejecuta healing ni DML y conserva el resultado
  derivado de la nueva observación;
- regresión de `LegalImportTransactionState`, reportes v1/v2/v3 y semántica unitaria de stdout
  ausente/truncado sin fabricar un envelope UNKNOWN.

### PostgreSQL

- pérdida del ACK después de commit para PROMOTE, REPLACE y RETIRE devuelve ALREADY_APPLIED en la
  misma invocación, con receipt real y sin duplicar filas ni secuencias;
- rollback sin ACK y session kill con source exacto devuelven ERROR/false;
- retry manual después de rollback aplica una única vez;
- estado parcial, DB inaccesible y lock no adquirido conservan UNKNOWN;
- la sesión anterior queda liberada y la observación ocurre en otra transacción con el mismo lock;
- import v2 permanece compatible.

La inyección de stdout truncado en un proceso real, los procesos JAR completos, carreras,
capacidad y métricas JDBC exhaustivas permanecen en Corte 10.

## Partición de implementación

1. **9A — Estado transaccional editorial.** Facade, invariantes y unitarias; cero JDBC nuevo.
2. **9B — Reconciliador SELECT-only.** Clasificador de evidencia y unitarias.
3. **9C — Integración con apply.** Activación exclusiva para UNKNOWN en las tres operaciones.
4. **9D1 — Evidencia PostgreSQL concluyente.** ACK perdido con commit y rollback sin ACK.
5. **9D2 — Incertidumbre PostgreSQL.** Session kill, estados parciales, DB y lock no disponibles.
6. **9E — Contratos y regresiones.** CLI/reportes/import sin expansión de contrato.
7. **9F — Puerta y cierre.** Suite amplia, auditoría de alcance y documentación.

Cada subcorte termina en un commit local atómico con prueba focal, revisión adversarial,
`git diff --check` y estado controlado. No se hace push ni deploy.

## Criterio de cierre

Corte 9 queda cerrado cuando PROMOTE, REPLACE y RETIRE pueden resolver un ACK perdido dentro de la
misma invocación sólo cuando existe evidencia PostgreSQL autoritativa; la no persistencia
acreditada mediante source exacto se distingue de un estado parcial; toda incertidumbre restante
conserva UNKNOWN; no se duplica DML; el importador y los reportes históricos permanecen
compatibles; y no se modifica V27, API, frontend, deploy ni la superficie pública.
