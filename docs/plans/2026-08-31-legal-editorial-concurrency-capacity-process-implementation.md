# Corte 10 — Plan de implementación de concurrencia, fallos, capacidad y procesos reales

Fecha: 2026-08-31

Estado: completado el 2026-08-31; 10A–10F cerrados

Rama backend: `codex/lanzamiento-publico-backend`

Diseño aprobado:

- `docs/plans/2026-08-31-legal-editorial-concurrency-capacity-process-design.md`;
- commit local `2dcad53`.

Plan maestro:

- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`.

Este plan detalla y reemplaza para la ejecución el bosquejo de un único commit que el plan maestro
reservaba al Corte 10. La aprobación documental inicial no ampliaba por sí sola Java productivo,
migraciones, `pom.xml`, API, frontend, runbooks ni deploy. La carrera reproducible de 10A detuvo el
subcorte y recibió microdiseño y aprobación separados antes de la única corrección productiva de
Corte 10, `9f00cb6`.

## Objetivo

Acreditar sobre PostgreSQL 16, roles restringidos, JAR empaquetado y launcher POSIX que el ciclo
editorial completo se serializa, falla de forma conservadora, cumple sus presupuestos de capacidad
y preserva el contrato de proceso real. Cada riesgo se implementa y revisa en un commit local
atómico.

## Reglas de ejecución

1. Usar TDD: cada garantía nueva nace en una prueba roja y recibe sólo el soporte test-only mínimo.
2. Ejecutar Maven con JDK 21 activo y registrar vendor/versión en toda evidencia de cierre.
3. Cada subcorte termina con puerta focal, revisión del diff, `git diff --check`, auditoría
   adversarial y commit local. No hacer push ni deploy.
4. Usar `clean verify` al ejecutar Failsafe para no reutilizar reportes anteriores.
5. No usar sleeps abiertos, orden de threads, retries automáticos, tests skipped ni repeticiones
   como política anti-flake. Sincronizar con advisory locks, barreras, latches, PIDs y checkpoints
   PostgreSQL observables.
6. No afirmar qué actor gana una carrera; congelar el multiset de resultados y el postestado.
7. La verdad de persistencia siempre proviene de una conexión owner independiente, no del stdout,
   una excepción ni la posición aparente de una desconexión.
8. No modificar los presupuestos productivos de 75/30/30/5 s ni el límite operacional de 70 s.
9. Una ejecución JDBC lógica cuenta sólo invocaciones `execute*` que alcanzan el driver. Commit,
   rollback, apertura de conexión y consumo de filas se registran por separado.
10. Readiness, plan y apply se miden por separado; setup, observer y generación de fixtures usan
    datasources no instrumentados.
11. Toda lectura multirrow pertenece a un inventario cerrado. Las lecturas primarias de relaciones
    dinámicas consumen como máximo `expected + 1` bajo sentinel; sus proyecciones derivadas usan
    caps sentinel exactos. Los límites fijos se validan por sintaxis y bind exactos, y cada lectura
    sin `LIMIT` propio debe justificar una cota estructural previa.
12. Los procesos reciben un entorno mínimo explícito más los overrides declarados; no heredan
    canales Spring, datasource, editoriales/importador ni opciones JVM ambient.
13. El agente de stdout vive sólo bajo `src/test`, se empaqueta en un temporal y debe estar ausente
    de ambos JAR de aplicación.
14. `scripts/legal-manifest-editor.sh` sólo cambia ante una prueba roja real y nunca recibe hooks o
    flags de test.
15. Si una prueba exige cambiar `src/main`, migraciones, V27/V28, grants, roles, `pom.xml`, schemas,
    API o timeouts, detener el subcorte, conservar la evidencia roja y solicitar diseño/aprobación
    separados.
16. Frontend, runbooks, `FRONTEND_INTEGRATION.md`, staging, deploy y cierre cross-repo permanecen
    en Corte 11 o planes posteriores. `BACKEND-HANDOFF 1` continúa cerrado.

## Subcorte 10A — Concurrencia editorial

Estado: cerrado el 2026-08-31 mediante el microcorte 10A.1 de reloj post-lock.

### Objetivo

Demostrar que las operaciones cooperativas comparten un único advisory lock y que carreras
idénticas o incompatibles convergen sin doble mutación.

### Archivos

Crear:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialConcurrencyIT.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialITFixture.java`.

Modificar sólo si el fixture común lo exige:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestPersistenceITSupport.java`;
- este plan para registrar evidencia.

Reutilizar sin modificar inicialmente:

- `LegalRestrictedImportRoleFixture`;
- `LegalRestrictedEditorialRoleFixture`;
- `LegalManifestImportConcurrencyIT`;
- `LegalManifestDryRunConcurrencyIT`.

### Implementación TDD

1. Crear un container PostgreSQL 16 cuyo nombre permita ambos fixtures restringidos, migrar hasta
   V27 y abrir observer owner, pool importador y pool editorial con `application_name` distintos.
2. Extraer al fixture test-only únicamente preparación de releases, fuente READY, planes validados
   PROMOTE/REPLACE y snapshots SQL exactos. No mover assertions específicas de IT existentes.
3. Retener el advisory lock desde una conexión externa, lanzar dos PROMOTE idénticos y acreditar
   que ambos esperan el mismo SQL antes de liberar.
4. Exigir exactamente `APPLIED + ALREADY_APPLIED`, mismo receipt confirmado, un solo conjunto de
   transiciones/proyecciones y secuencias sin duplicación.
5. Repetir con dos targets inicialmente válidos pero incompatibles como estado vigente. Exigir un
   solo `APPLIED`, un resultado `BLOCKED` y postestado exacto del ganador.
6. Preparar una fuente READY y dos REPLACE cuyos lotes actuales comparten un predecesor. Exigir
   como máximo un confirmado, loser sin DML y postestado exacto del ganador.
7. Retener el lock y lanzar, con conexiones independientes, import replay, dry-run, readiness,
   plan y apply. Antes de liberar, observar dos waits importadores y tres editoriales sobre
   `pg_advisory_xact_lock(hashtextextended(EDITORIAL_LOCK_NAME, 0))`.
8. Después de liberar, exigir import `ALREADY_IMPORTED`, dry-run `PASS`, plan válido y apply
   confirmado. Readiness puede observar `NOT_READY` o `READY` según su turno, pero nunca ERROR ni
   estado parcial.
9. En cada carrera comparar filas, transiciones, secuencias, slots, punteros y postestado desde el
   observer independiente. Cerrar executors y conexiones en `finally` con timeout acotado.
10. Reacreditar que la reconciliación read-only toma ese mismo lock mediante
    `LegalManifestDatabaseGateTest` y el escenario PostgreSQL existente que retiene el lock sólo
    durante la observación posterior. No duplicar su matriz de Corte 9.

### Pruebas y puerta

~~~bash
./mvnw -Dtest=LegalManifestDatabaseGateTest test
./mvnw clean -Dit.test=LegalEditorialConcurrencyIT verify
./mvnw clean -Dit.test=LegalEditorialConcurrencyIT,LegalManifestImportConcurrencyIT,LegalManifestDryRunConcurrencyIT,LegalInitialPromotionIT,LegalEditorialReplaceIT,LegalEditorialSplitMergeIT,LegalEditorialApplyFailureIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): acredita concurrencia editorial

### Evidencia de cierre 10A

El `2026-08-31`, con Amazon Corretto `21.0.10`, la primera ejecución del gate focal
`./mvnw clean -Dit.test=LegalEditorialConcurrencyIT verify` llegó a PostgreSQL 16 y reveló una
carrera real en los dos `PROMOTE` idénticos:

- esperado: `APPLIED + ALREADY_APPLIED`;
- observado: `APPLIED + ERROR`, con issue público
  `EDITORIAL_OBSERVATION_FAILED/database/observation`;
- excepción interna capturada mediante instrumentación test-only temporal y luego retirada:
  `IllegalArgumentException: expectedAppliedAt no puede ser posterior a observedAt`, originada en
  `LegalEditorialExecutionPlan`;
- los escenarios de targets incompatibles, `REPLACE` con predecesor compartido y matriz del gate
  común alcanzaron sus assertions funcionales en esa misma ejecución;
- no se modificó `src/main`, migraciones, grants, roles, `pom.xml`, API ni timeouts; no se creó
  commit y no se hizo push.

Causa acreditada: ambas operaciones abren su transacción antes del advisory lock. Aunque el
servicio lee el tiempo después de obtenerlo, usa `transaction_timestamp()`, que conserva el inicio
de la transacción. Si la transacción más nueva aplica primero y la más antigua reanuda después, el
replay ve el `appliedAt` confirmado por la ganadora pero conserva un `observedAt` anterior y falla
en vez de converger a `ALREADY_APPLIED`.

La regla 15 obligó a detener 10A. Para reanudar se aprobó un microdiseño que:

1. fuerce de forma determinista la inversión `xact_start`/orden del advisory lock mediante
   latches test-only y evidencia de `pg_stat_activity`, sin sleeps ni retries;
2. defina un instante editorial congelado realmente posterior al lock para apply, plan, readiness
   y reconciliación, sin alterar los timestamps transaccionales del importador;
3. mantenga las validaciones temporales actuales y haga verde `APPLIED + ALREADY_APPLIED`;
4. vuelva a ejecutar toda la puerta 10A antes del commit previsto.

El microdiseño 10A.1 quedó aprobado y documentado en
`docs/plans/2026-08-31-legal-editorial-post-lock-dual-clock-design.md`. Antes de tocar producción,
la carrera fue convertida en una reproducción determinista con latches y `pg_stat_activity`. En
Amazon Corretto `21.0.10` y PostgreSQL 16, B inició después y confirmó primero (`APPLIED`,
`xact_start=2026-08-31T15:33:52.495618Z`); A había iniciado en
`2026-08-31T15:33:52.417101Z` y, al reanudar, devolvió
`ERROR / EDITORIAL_OBSERVATION_FAILED` en vez de `ALREADY_APPLIED`. La puerta focal terminó con
`1 test`, `1 failure`, `0 errors` y sin cambios productivos previos.

El microcorte quedó implementado y acreditado en dos commits locales atómicos:

- `9f00cb6 fix(legal): separa el reloj editorial post-lock`;
- `77c80c5 test(legal): acredita concurrencia editorial`.

La prueba determinista ahora fuerza que B, aun iniciando después, confirme primero. B devuelve
`APPLIED`; A reanuda con su transacción más antigua y converge a `ALREADY_APPLIED`. Ambos resultados
comparten el receipt confirmado por B, `receipt.appliedAt` conserva su `transactionAt`, existe una
sola mutación y un tercer replay no altera filas ni secuencias. La misma clase acredita además los
targets incompatibles, los `REPLACE` con predecesor compartido y la cooperación del gate entre
import, dry-run, readiness, plan y apply.

Puertas de cierre ejecutadas con Amazon Corretto `21.0.10`, PostgreSQL `16.14` y schema legal V27:

- clase de concurrencia completa: `4.263` unitarias + `4` integraciones, todas verdes;
- regresión de fallos y operaciones: `4.263` unitarias + `69` integraciones, todas verdes;
- puerta 10A ampliada: `4.263` unitarias + `126` integraciones, todas verdes en `4:42`;
- `./mvnw clean verify`: `4.263` unitarias + `237` integraciones, sin fallos, errores ni omitidas,
  en `6:18`.

La auditoría negativa confirmó que no hay reloj JVM ni fallback temporal, que
`statement_timestamp()` sólo se lee en el gate y que import/dry-run conservan su reloj
transaccional. No se modificaron migraciones, V27, grants, roles, API, CLI, frontend, `pom.xml`,
timeouts ni presupuestos. `git diff --check` quedó limpio. No hubo push ni deploy.

## Subcorte 10B — Fallos y recuperación

Estado: cerrado el 2026-08-31.

### Objetivo

Congelar una matriz integrada de timeout, deadlock, session kill y ACK perdido sin éxito falso y
con retry exacto convergente.

### Archivos

Crear:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialFailureIT.java`.

Modificar:

- `LegalEditorialITFixture` para holders, PIDs y snapshots de fallo;
- `LegalManifestPersistenceITSupport` sólo para probes SQL reutilizables que no relajen assertions;
- este plan para registrar evidencia.

Reutilizar sin eliminar cobertura:

- `LegalInitialPromotionFailureIT`;
- `LegalEditorialReconciliationIT`;
- `LegalEditorialApplyFailureIT`;
- `LegalManifestImportFailureIT`.

### Implementación TDD

1. Timeout: retener el advisory lock, ejecutar apply con `LegalDatabaseBudgets(5, 2, 1, 1)` y
   acreditar SQLState crudo `55P03`, mapeado por el contrato editorial público a
   `ERROR/persisted=false` con issue `CONCURRENT_OPERATION`, snapshot exacto y cero DML
   confirmado. Liberar; el retry aplica una vez y el siguiente replay devuelve
   `ALREADY_APPLIED`.
2. Deadlock real: una transacción externa retiene una identidad que REPLACE necesita; observar al
   apply bloqueado y recién entonces hacer que el holder solicite el advisory lock que el apply ya
   posee. Exigir SQLState crudo `40P01`, mapeado por el contrato editorial público al issue
   `CONCURRENT_OPERATION`, rollback completo y retry convergente.
3. Session kill antes de commit: detener apply en un checkpoint SQL observable, capturar el PID y
   ejecutar `pg_terminate_backend`. Una frontera owner nueva debe acreditar source exacto; el
   resultado no puede ser APPLIED y el retry debe aplicar exactamente una vez.
4. ACK perdido reconciliable: reutilizar el wrapper existente, exigir postestado exacto,
   `ALREADY_APPLIED/persisted=true`, receipt releído y replay posterior SELECT-only.
5. ACK perdido con reconciliación indisponible: confirmar el commit y hacer fallar de forma
   determinista la siguiente frontera read-only. Exigir `UNKNOWN/persisted=null`, redacción y cero
   metadata tentativa; el observer decide el estado real y el retry converge sin duplicar DML.
6. Correlacionar en todos los casos outcome, issue público, PID, filas, secuencias, source/poststate
   y cantidad de writers. No depender del texto completo de PostgreSQL.

### Pruebas y puerta

~~~bash
./mvnw clean -Dit.test=LegalEditorialFailureIT verify
./mvnw clean -Dit.test=LegalEditorialFailureIT,LegalInitialPromotionFailureIT,LegalEditorialReconciliationIT,LegalEditorialApplyFailureIT,LegalManifestImportFailureIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): acredita fallos editoriales

### Evidencia de cierre 10B

El subcorte quedó implementado en el commit local atómico:

- `794c9b2 test(legal): acredita fallos editoriales`.

La matriz nueva usa roles importador/editorial restringidos, observer owner independiente y
PostgreSQL real. Congela estos cinco resultados:

1. El timeout del advisory acredita SQLState crudo `55P03`, resultado público
   `ERROR/persisted=false`, issue `CONCURRENT_OPERATION` en `database/concurrency`, cero writer,
   cero DML y snapshot exacto. Tras liberar el lock, el retry devuelve `APPLIED` y el replay
   `ALREADY_APPLIED` sin writer ni DML adicional.
2. El deadlock de `REPLACE` pausa el apply justo antes de `FOR UPDATE`, confirma que ya posee el
   advisory y que el holder externo espera ese PID, y recién entonces habilita el row lock. El
   ciclo bidireccional produce `40P01`, mapeado a `CONCURRENT_OPERATION`, con rollback completo,
   cero DML y snapshot exacto. Retry y replay convergen sin duplicación.
3. El session kill observa al `PROMOTE` real bloqueado en la publicación antes de cualquier DML,
   termina ese PID y espera su ausencia de `pg_stat_activity`. Una frontera read-only nueva
   acredita `SOURCE_EXACT`; el resultado es `ERROR/persisted=false` con
   `EDITORIAL_OBSERVATION_FAILED`, sin metadata tentativa. El mismo servicio recupera el pool,
   aplica una vez y luego confirma replay sin writer ni DML adicional.
4. La pérdida de ACK con reconciliación disponible devuelve
   `ALREADY_APPLIED/persisted=true`, reconstruye el receipt exacto desde PostgreSQL y deja un solo
   writer persistido. El replay conserva receipt, filas, secuencias y contador DML.
5. La pérdida de ACK con la siguiente frontera read-only indisponible devuelve
   `UNKNOWN/persisted=null`, issue único `COMMIT_OUTCOME_UNKNOWN` en `database/commit` y cero
   metadata tentativa; el canary interno queda redactado. El owner prueba que el commit ocurrió y
   retry/replay convergen sin writer ni DML duplicado.

Todos los escenarios correlacionan PID/blocker, outcome, `persisted`, issue público, receipt,
writer, sentencias DML y snapshots exactos de filas, conteos y secuencias V27. El contador JDBC
test-only cubre las rutas productivas actuales `execute`, `update` y `batchUpdate`; el cleanup
termina y junta workers antes de liberar conexiones compartidas.

`LegalEditorialITFixture` se conservó sin cambios deliberadamente: los holders, conexiones,
executors y probes de PID pertenecen al soporte de integración que controla su ciclo de vida, no
al fixture declarativo compartido.

La primera ejecución focal directa llegó a `4/5` por una assertion de conteo de `REPLACE` más
estricta que el contrato. Se corrigió únicamente la prueba para comparar el receipt y el
postestado contractual; no se tocó producción. Después quedaron verdes, con Amazon Corretto
`21.0.10`, PostgreSQL `16.14` y schema legal V27:

- puerta focal limpia: `4.263` unitarias + `5` integraciones, sin fallos, errores ni omitidas, en
  `1:19`;
- matriz de regresión limpia: `4.263` unitarias + `25` integraciones, sin fallos, errores ni
  omitidas, en `1:46`.

Tres revisiones adversariales independientes encontraron inicialmente cobertura insuficiente del
writer de retry, ausencia de contador DML literal y una carrera/cleanup mejorable en el deadlock.
Tras agregar las assertions, el contador JDBC, el checkpoint previo al row lock y el cleanup por
PID/application name, las tres reauditorías cerraron sin hallazgos P0–P3. `git diff --check` quedó
limpio. No se modificaron `src/main`, migraciones, V27, grants, roles productivos, `pom.xml`, API,
frontend, presupuestos ni timeouts. No hubo push ni deploy.

## Subcorte 10C — Capacidad

Estado: cerrado el 2026-08-31.

### Objetivo

Conservar el techo importable `128/256/16` y medir readiness, plan y apply sobre el techo editorial
compuesto `87/256/16/88 slots`; congelar caps JDBC deterministas y bloquear lecturas no acotadas
sin relajar presupuestos.

### Archivos

Crear:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialCapacityIT.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalJdbcMetricsSupport.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalJdbcMetricsSupportTest.java`.

Modificar:

- `LegalEditorialITFixture` para generar fuente, target y plan máximos;
- `LegalManifestPersistenceITSupport` sólo si hace falta un ensamblado restringido común;
- este plan para congelar observados y caps antes del commit.

`LegalManifestImportCapacityIT` permanece inicialmente sin cambios y se ejecuta como regresión. No
se extrae su proxy privado durante este subcorte salvo que una prueba de caracterización separada
demuestre equivalencia exacta y el diff siga siendo acotado.

### Implementación TDD

1. Caracterizar el proxy test-only: cada `execute*` cuenta una vez; batch cuenta una vez; retry
   cuenta nuevamente; commit/rollback quedan fuera; `ResultSet.next()==true` cuenta una fila; una
   excepción conserva duración; el delay de 5 ms se aplica una vez por ejecución lógica.
2. Registrar por separado total, categoría SQL normalizada, mayor statement, sentencia del
   advisory lock, filas consumidas, commits/rollbacks y duración monotónica de operación.
3. Mantener `LegalManifestImportCapacityIT` como acreditación separada del máximo contractual
   importable de 128 documentos, 256 requisitos y 16 scopes; ese fixture no se promociona ni se
   presenta como READY.
4. Construir fuente y target editoriales de 87 documentos, 256 requisitos, 16 scopes y 88 slots
   únicos. Combinar exactamente 82 reuses, una adición compensada por un retiro, un lote `1→1`,
   un split `1→2` y un merge `2→1`; conservar 250 requisitos y reemplazar seis. Ningún requisito
   READY puede referenciar más de los 11 documentos disponibles en su contexto. Materializar
   2.642 referencias requisito-documento por publicación y 5.284 filas en la proyección activa.
5. Preparar estado sin instrumentación, promover la fuente y acreditar el plan compuesto exacto
   antes de iniciar mediciones.
6. Sobre el datasource editorial restringido instrumentado con 5 ms, resetear y medir por
   separado readiness `NOT_READY`, plan `APPLICABLE` y apply `APPLIED`.
7. Para cada operación exigir duración `<70 s`, statement `<30 s`, transacción productiva de 75 s,
   rol/preflights exactos y resultado funcional correcto.
8. Inventariar cada `SELECT` y `ROW_LOCK` por SQL normalizado, aunque devuelva cero o una fila.
   Agregar corrupción sentinel a las relaciones dinámicas principales: exigir `expected + 1` en
   sus lecturas primarias y caps sentinel exactos en sus proyecciones. Para techos fijos grandes,
   capturar por ejecución el bind entero exacto del `LIMIT`; toda lectura sin límite propio requiere
   una cota estructural allowlisteada. Observer y setup usan otro datasource.
9. Registrar primero valores observados. Tras revisar que no existe N+1, congelar caps exactos o
   márgenes enteros justificados por operación/categoría, nunca porcentajes arbitrarios.
10. Medir memoria sólo si el entorno permite repetir la cifra bajo condiciones controladas. Si no,
   dejar la razón explícita para 10F sin inventar un cap.
11. Si una lectura primaria sentinel supera `expected + 1`, una proyección supera su cap sentinel,
    un límite estructural pierde su bind exacto, el tiempo excede el presupuesto o aparece
    batching deficiente, detener 10C con prueba roja; no modificar producción, migraciones ni
    timeouts.

### Pruebas y puerta

~~~bash
./mvnw clean -Dtest=LegalJdbcMetricsSupportTest -Dit.test=LegalEditorialCapacityIT verify
./mvnw clean -Dtest=LegalJdbcMetricsSupportTest -Dit.test=LegalEditorialCapacityIT,LegalManifestImportCapacityIT,LegalEditorialReplaceIT,LegalEditorialSplitMergeIT verify
./mvnw clean -Dit.test=LegalEditorialConcurrencyIT,LegalEditorialFailureIT,LegalEditorialCapacityIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): congela capacidad editorial

### Evidencia de cierre 10C

El subcorte quedó implementado en cuatro commits locales atómicos:

- `c0f02cc docs(legal): separa techos de capacidad editorial`;
- `e227a2d test(legal): caracteriza metricas jdbc editoriales`;
- `dc9b619 test(legal): mide cardinalidad jdbc por resultado`;
- `148f989 test(legal): congela capacidad editorial`.

La separación documental fija primero el fixture dual; los dos microcommits siguientes
caracterizan la instrumentación antes de consumirla; el commit final agrega
`LegalEditorialCapacityIT` y completa `LegalEditorialITFixture`, `LegalJdbcMetricsSupport` y sus 11
caracterizaciones unitarias. Ninguno modifica `src/main`, migraciones, V27, roles, grants,
`pom.xml`, scripts, API, frontend, presupuestos ni timeouts.

#### Fixtures y resultado funcional

`LegalManifestImportCapacityIT` sigue siendo la acreditación independiente del máximo importable
`128/256/16`. El nuevo fixture editorial crea fuente y target por el flujo real import/apply, ambos
con locale único `es-AR`, `87` documentos, `256` requisitos, `16` scopes y `88` slots. Congela:

- `82` documentos reutilizados, una adición compensada por un retiro y lotes `1→1`, `1→2` y
  `2→1`;
- `250` requisitos reutilizados y seis reemplazados;
- `2.642` referencias requisito-documento por publicación y `5.284` identidades activas
  scope-miembro-documento;
- delta exacto, en el orden de `DeltaCounts`, `(7, 8, 18, 83, 83, 5, 5, 16, 16, 3)`;
- readiness inicial `NOT_READY`, plan `APPLICABLE`, apply `APPLIED`, `readinessAfter=READY`, batch
  identities exactas y postestado estructural observado desde una conexión owner independiente.

#### Métricas congeladas

Con un delay test-only de `5 ms` por ejecución JDBC lógica, datasource editorial restringido y
setup/observer no instrumentados, una ejecución limpia del gate final produjo:

| Operación | Duración | RT `A/D/R/T/S/O` | Filas | Máx. ejecuciones/SQL | Statement máx. | Advisory máx. |
|---|---:|---:|---:|---:|---:|---:|
| readiness | `0,935 s` | `52 (1/0/0/3/48/0)` | `11.636` | `2` | `69,298 ms` | `8,677 ms` |
| plan | `1,832 s` | `96 (1/0/0/3/92/0)` | `36.042` | `4` | `77,572 ms` | `8,781 ms` |
| apply | `4,450 s` | `184 (1/17/5/4/157/0)` | `68.648` | `6` | `906,670 ms` | `8,544 ms` |

`A/D/R/T/S/O` significa advisory lock, DML, row lock, control transaccional, SELECT y otras. Los
conteos, filas y repeticiones de la tabla son los caps congelados; cada categoría debe sumar el
total de round trips. Las tres operaciones confirman un commit, cero rollbacks y cero fallos JDBC.
Cada duración permanece bajo `70 s`; cada statement y espera advisory permanece bajo `30 s`.

Cada uno de los tres sentinels agrega tres filas inválidas en su relación —documentos, requisitos
o miembros— y exige `BLOCKED`, cero DML, un commit y cero rollback. Sólo se relajan las familias
nombradas por cada escenario y la prueba exige que cada una supere efectivamente su cap normal:

| Sentinel | Duración | RT | Filas | Máx. ejecuciones/SQL | Familias ejercitadas |
|---|---:|---:|---:|---:|---|
| documentos | `0,842 s` | `47` | `7.712` | `1` | documentos origen, contextos origen y documentos target |
| requisitos | `0,966 s` | `50` | `11.123` | `1` | requisitos, audiencias, referencias y requisitos target |
| miembros de scope | `0,933 s` | `52` | `11.684` | `2` | miembros origen y fingerprints activos de miembros/documentos |

#### Inventario SQL cerrado

Cada `SELECT` y `ROW_LOCK`, incluidos resultados de cero o una fila, debe coincidir con exactamente
una familia del inventario. Las 35 familias con límite parametrizado deben terminar en el sufijo
canónico `LIMIT ?`; todas sus ejecuciones deben entregar en el último placeholder un entero exacto
y sólo el valor configurado. Un bind ausente, `NULL`, fraccionario, fuera de rango o una expresión
posterior al placeholder falla. Las dos búsquedas de publicación deben terminar exactamente en
`LIMIT 2`.

Los binds entregados al driver quedaron congelados así:

- origen/target: documentos `88`, contextos `89`, requisitos `257`, audiencias `513`, referencias
  `2.643`, scopes `17`, miembros `513`, documentos target `88` y requisitos target `257`;
- fingerprints activos: slots `89`, punteros `17`, miembros `4.097` y referencias `65.537`;
- readiness: versiones documento `8.193`, versiones requisito `16.385`, lotes `8.193` y miembros
  predecesor/sucesor `16.385`;
- planner: membresías documento `1.025`, requisito `2.049`, slots `1.025`, punteros `65`, miembros
  `16.385` y documentos `32.769`;
- evidencia: documentos `1.025`, requisitos `2.049`, scopes `65`, miembros `16.385` y documentos
  `32.769`;
- historia/lotes: transiciones documento `8.193`, transiciones requisito `8.193`, identidades de
  lote `513`, predecesores `8.193`, sucesores `8.193` e historia de lote `8.193`.

Las únicas lecturas legales multirrow sin `LIMIT` propio son una allowlist cerrada: evidencias de
contexto y audiencia, membresías de publicación, slots y scopes activos, headers/locks de lotes,
publicaciones y líneas bloqueadas, versiones del writer e identidades de punteros. Sus IDs vienen
de una lectura anterior ya limitada o su espacio de claves queda cerrado por unicidad V27 y enums
legales. Cualquier nueva familia no inventariada falla; toda familia con bind configurado debe
observar su variante canónica limitada, y sólo las familias de esa allowlist pueden observar además
una variante sin límite propio.

Los sentinels demuestran `expected + 1` en sus lecturas primarias de documentos, requisitos y
miembros. Las proyecciones derivadas se validan con caps sentinel exactos —por ejemplo `515` para
miembros activos y `5.317` para sus referencias— y binds estructurales congelados. Para techos de
miles de filas no se construyó una base artificial de 8K–65K: se captura el valor exacto entregado
al driver en cada ejecución y la regresión `LegalEditorialSplitMergeIT` conserva la cobertura
semántica de corrupción y composición split/merge. Las lecturas de catálogo tienen caps del
entorno controlado PostgreSQL `16.14`/V27; no se presentan como cardinalidades del dominio.

#### Puertas, ambiente y auditoría

Se ejecutaron tres lifecycles frescos con Amazon Corretto `21.0.10`, Maven `3.9.11`, Flyway
`11.14.1`, PostgreSQL `16.14` y 27 migraciones hasta V27:

- focal: `11` unitarias + `1` integración, sin fallos, errores ni omitidas, en `43,278 s`;
- regresión capacity/import/replace/split-merge: `11` unitarias + `30` integraciones, todas verdes,
  en `2:04`;
- capacidad + concurrencia + fallos: `4.274` unitarias + `10` integraciones, todas verdes, en
  `1:43`.

Las rojas de TDD fueron sólo caracterizaciones esperadas del soporte: inventario de resultados
`0/1`, relajación localizada de sentinels y captura exacta de binds. No hubo incidente productivo,
flake, retry automático ni test omitido. Tres auditorías adversariales detectaron durante el
desarrollo máximos por `ResultSet`, cobertura `0/1`, relajación global, bypasses de binds
ausentes/`NULL`/fraccionarios y fronteras sintácticas de `LIMIT`; todos se corrigieron. Las
reauditorías finales cerraron sin P0–P3 y `git diff --check` quedó limpio.

No se congela memoria en 10C: Docker Desktop expuso `3.919 MB` compartidos, pero heap JVM, límite
del container, GC y carga del host no estaban fijados. Publicar ese pico sería engañoso; 10F debe
registrar la misma razón o repetir bajo un entorno controlado. No hubo push ni deploy.

## Subcorte 10D — Infraestructura de procesos

Estado: cerrado el 2026-08-31.

### Objetivo

Centralizar ejecución de subprocesses, medirlos y disponer de fallos de stdout deterministas antes
de agregar la matriz editorial del JAR.

### Archivos

Crear:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalCliStdoutFailureAgent.java`.

Modificar:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalCliProcessSupport.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalCliProcessSupportTest.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalManifestCliProcessIT.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalManifestImportProcessIT.java`, sólo por compatibilidad del soporte;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalManifestEditorLauncherTest.java`;
- este plan para registrar evidencia.

Modificar condicionalmente, sólo ante prueba roja:

- `scripts/legal-manifest-editor.sh`.

### Implementación TDD

1. Hacer que `ProcessResult` exponga bytes mediante copia defensiva, UTF-8 estricto, duración
   monotónica, timeout, exit final y límites de captura. El watchdog de 90 s no sustituye la
   medición `<70 s` de cada operación ni el presupuesto transaccional de 75 s; tampoco se los
   confunde con el tiempo de arranque de la JVM.
2. Agregar un timeout corto test-only y acreditarlo con un hijo coordinado por señal/pipe, sin
   `sleep`; destruir, esperar y cerrar lectores/executor siempre.
3. Construir un ambiente base mínimo y estable más overrides explícitos. Preservar sólo variables
   de runtime realmente necesarias; remover todos los canales import/editorial, Spring,
   datasource y opciones JVM antes de aplicar overrides allowlisteados.
4. Crear en `@TempDir` un Java agent que se ejecute antes de `main`, reemplace `System.out`, reenvíe
   un prefijo fijo al descriptor real, cierre ese descriptor y haga fallar toda escritura
   posterior. No modificar `LegalManifestCli` ni agregar properties productivas.
5. Caracterizar tres modos deterministas: captura completa, agent con `N=0` y agent con `N>0`.
   El último debe producir exactamente N bytes sin carrera; el primero conserva JSON completo.
   `CLOSE_IMMEDIATELY` permanece como regresión suplementaria del pipe real, pero no acredita por
   sí solo la ausencia de stdout porque el padre cierra después de arrancar al hijo.
6. Migrar el runner duplicado de `LegalManifestCliProcessIT` al soporte compartido manteniendo sus
   assertions actuales y el límite de captura.
7. Adaptar import process y launcher al nuevo resultado sin cambiar sus contratos.
8. Endurecer el launcher con argumentos que incluyan espacios, glob, `$`, comillas y
   `--flag=valor`; exigir preservación literal y eliminación de los tres canales JVM.
9. Acreditar que `LegalCliStdoutFailureAgent` no está dentro del JAR normal ni del clasificado.
10. Ejecutar aislamiento y privilegios como regresión. No tocar el launcher si ya pasa las pruebas.

### Pruebas y puerta

~~~bash
./mvnw -Dtest=LegalCliProcessSupportTest,LegalManifestEditorLauncherTest test
./mvnw clean -Dtest=LegalCliProcessSupportTest,LegalManifestEditorLauncherTest -Dit.test=LegalManifestCliIsolationIT,LegalManifestCliProcessIT,LegalManifestImportProcessIT,LegalEditorialPrivilegeVerifierIT verify
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Commit:

    test(legal): endurece procesos editoriales

### Evidencia de cierre 10D

El subcorte quedó implementado en ocho commits locales atómicos:

- `09c0e68 test(legal): conserva resultados binarios de procesos`;
- `9e8148b test(legal): cierra lifecycle de subprocesses`;
- `f528f46 test(legal): aisla entorno de procesos legales`;
- `23139f8 test(legal): congela limite de captura de procesos`;
- `e021d67 test(legal): centraliza runner cli legal`;
- `373bc47 test(legal): acredita fallo determinista de stdout`;
- `5923eec test(legal): endurece launcher editorial`;
- `d8f6a97 test(legal): conserva timeout del runner cli`.

El rango modifica cinco archivos y todos pertenecen a `src/test`: cuatro existentes y el agente
`LegalCliStdoutFailureAgent` nuevo. No modifica `src/main`, migraciones, properties, `pom.xml`,
scripts, API productiva ni frontend. `LegalManifestImportProcessIT` no necesitó adaptación porque el soporte
conservó compatibilidad; se recompiló y pasó sus nueve casos. El launcher productivo también quedó
sin cambios porque la nueva caracterización confirmó su comportamiento actual.

#### Contrato del soporte de procesos

`ProcessResult` conserva stdout y stderr como bytes con copias defensivas, decodifica UTF-8 de
forma estricta y expone duración monotónica, timeout, exit final y exceso independiente por cada
stream. El límite se congeló exactamente en `1.048.576` bytes por stream; los lectores continúan
drenando después del límite para no bloquear al hijo. El watchdog normal permanece en `90 s`, la
gracia de terminación en `2 s` y las ventanas de captura y apagado de lectores en `5 s` cada una.

El timeout corto se prueba con coordinación por archivo y `WatchService`, sin sleeps: el soporte
cierra stdin, termina el proceso, escala a terminación forzada si hace falta, espera el exit final,
cierra streams, cancela lectores y apaga el executor. Las rutas de excepción e interrupción
repropagan `InterruptedException` después del cleanup; si una interrupción ocurre durante las
propias esperas del cleanup, éste restaura el flag. La configuración nula, `stdoutMode` inválido y
los timeouts no positivos o fuera de rango se rechazan antes de iniciar el hijo.

El entorno hijo sustituye la herencia general del host por una base sintética. En POSIX parte de
`PATH=<java.home>/bin:/usr/bin:/bin`, `TMPDIR`/`TMP`/`TEMP` apuntando al directorio de trabajo y
`LANG`/`LC_ALL=C.UTF-8`. En Windows también sintetiza PATH y temporales, y sólo toma del host el
valor de `SystemRoot` mediante búsqueda case-insensitive. Los overrides explícitos se aplican al
final. Las pruebas niegan secretos y canales hostiles de JVM, Spring, OrdenFix, datasource,
preload, shell, proxy y PostgreSQL. La puerta ejecutada acredita POSIX en macOS; la compatibilidad
Linux queda diseñada y debe confirmarse en CI. Windows nativo no forma parte de 10D.

`LegalManifestCliProcessIT` eliminó su runner duplicado —incluidas `155` líneas de motor local— y
usa el soporte común con su timeout explícito de `75 s`. Sus doce casos de JAR, entorno,
redacción, límite y PostgreSQL permanecieron verdes.

#### Fallo de stdout y launcher

El agente se construye dentro de `@TempDir`, en una ruta con espacios, con manifest
`Premain-Class` y una única clase JDK-only. Se ubica primero en el classpath del hijo y reemplaza
`System.out` antes de `main`. El probe escribe el contrato ASCII `0123456789` en dos bloques y
congela tres resultados deterministas:

| Modo | Exit | Stdout exacto |
|---|---:|---|
| sin agente | `0` | `0123456789` |
| agente `N=0` | `3` | vacío |
| agente `N=7` | `3` | `0123456` |

Al alcanzar N, el agente cierra el descriptor real, marca el stream como fallido y mantiene
`PrintStream.checkError()`. Como evidencia interactiva de la sesión, no persistida en los reportes
finales, el ciclo TDD mostró dos rojos: primero no existían los bytes compilados del agente; con un
`premain` no-op, el hijo todavía salía `0` cuando se esperaba `3`. La implementación final cerró
ambos casos. Las reauditorías además endurecieron la semántica de escrituras vacías posteriores al
fallo y la creación del JAR temporal mediante `CREATE_NEW`.

Los dos JAR productivos conservan sus `Start-Class`, no declaran `Premain-Class`, `Agent-Class` ni
`Launcher-Agent-Class`, y no contienen la clase del agente ni clases anidadas asociadas. El agente
test-only todavía no se combina con apply y evidencia DB autoritativa: esa matriz pertenece a
10E.

El launcher real elimina `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS`. Un Java probe
en ruta con espacios recibió literalmente y en orden una ruta de JAR con espacios, espacios
internos, glob con coincidencia real, `$`, comillas simples/dobles, `--flag=value` y un argumento
vacío. La coincidencia `glob-match.json` hace que una regresión de `"$@"` a `$@` falle de manera
inequívoca. `scripts/legal-manifest-editor.sh` no necesitó cambios y pasó `sh -n`.

#### Puertas, ambiente y auditoría

Con Amazon Corretto `21.0.10+7-LTS` (`Corretto-21.0.10.7.1`), Maven `3.9.11`, Flyway `11.14.1`,
macOS `26.6.2` aarch64 y PostgreSQL `16.14` mediante la imagen `postgres:16-alpine`, se validaron
`27` migraciones hasta V27 y se ejecutó:

- puerta focal: `LegalCliProcessSupportTest` `9/9` y `LegalManifestEditorLauncherTest` `1/1`, sin
  fallos, errores ni omitidas, `BUILD SUCCESS`; tiempo de sesión observado `11,122 s`;
- lifecycle limpio: las mismas `10` unitarias más `LegalManifestCliIsolationIT` `3/3`,
  `LegalManifestCliProcessIT` `12/12`, `LegalManifestImportProcessIT` `9/9` y
  `LegalEditorialPrivilegeVerifierIT` `8/8`; total `32` integraciones, cero fallos, errores u
  omitidas, `BUILD SUCCESS`; tiempo de sesión observado `1:05`;
- `sh -n scripts/legal-manifest-editor.sh`, `git diff --check` y `git status --short`, todos
  limpios antes de abrir este commit documental.

Las auditorías adversariales encontraron durante el desarrollo dos P3 del agente y un P2 de
cobertura del glob; todos se corrigieron y las reauditorías cerraron sin P0–P3 técnicos. No hubo
test flaky, retry automático ni reporte Failsafe reutilizado. Una auditoría documental posterior
detectó que la migración del runner había sustituido sus `75 s` explícitos por el default de
`90 s`; `d8f6a97` restauró el contrato anterior y motivó la segunda puerta integral desde
`clean`. No hubo push ni deploy. La combinación del agente con el JAR editorial real, exit `3` y
postestado PostgreSQL independiente queda expresamente reservada al subcorte 10E.

## Subcorte 10E — Matriz del JAR editorial

Estado: cerrado el 2026-08-31.

### Objetivo

Ejecutar los siete comandos editoriales mediante el JAR real, PostgreSQL 16 y el rol restringido;
acreditar estados, seguridad, launcher y pérdida de stdout contra evidencia DB independiente.

### Archivos

Crear:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialProcessIT.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialProcessFixture.java`.

Modificar sólo si una prueba focal lo exige:

- `LegalCliProcessSupport`;
- `LegalRestrictedEditorialRoleFixture`;
- este plan para registrar evidencia.

Reutilizar sin modificar inicialmente:

- `src/test/resources/legal/manifest/release-valid-v1/`;
- `src/test/resources/legal/editorial/replace-one-to-one-release-valid-v1/editorial-plan.json`;
- `src/test/resources/legal/editorial/retire-valid-v1/editorial-plan.json`;
- `LegalRestrictedImportRoleFixture`;
- `LegalManifestCliIsolationIT`;
- `LegalManifestCliProcessIT`;
- `LegalManifestImportProcessIT`.

Los fixtures derivados se generan sólo en `@TempDir` y sus confirmations se calculan con los
validadores reales; no se duplican hashes hardcodeados.

`LegalEditorialProcessFixture` vive en el paquete `cli` y contiene exclusivamente copias/mutaciones
temporales, argumentos, environment maps y snapshots SQL owner necesarios por el proceso. No
intenta acceder a `LegalManifestPersistenceITSupport`, que es package-private en `persistence`, ni
abre una nueva API pública de tests.

### Implementación TDD

1. Migrar V27 en un container dedicado, provisionar owner, importador y editorial restringidos, y
   localizar ambos artefactos empaquetados.
2. Sembrar releases mediante el comando import del JAR y comprobar con readiness real el estado
   inicial `NOT_READY`.
3. Ejecutar `plan-promote` y exigir `APPLICABLE` sin delta DB. Ejecutar un target incompatible y
   exigir `BLOCKED` sin mutación.
4. Ejecutar `apply-promote`: `APPLIED + READY`. Repetir exactamente: `ALREADY_APPLIED`, mismo
   receipt, filas y secuencias.
5. Generar un sucesor compatible y plan `1→1`; ejecutar `plan-replace` `APPLICABLE`,
   `apply-replace` `APPLIED + READY` y replay `ALREADY_APPLIED` sin DML.
6. Ejecutar `plan-retire` y `apply-retire` sobre el vigente; exigir `APPLIED + NOT_READY`,
   postestado exacto y replay sin duplicación.
7. Usar las credenciales importadoras en el entorno editorial e intentar apply. Exigir error de
   privilegios antes de DML.
8. Ejecutar el JAR real con owner, un rol con privilegio extra y un rol con herencia indebida.
   Cada caso debe producir exit/JSON conocido y redacted, stderr allowlisteado y cero DML; restaurar
   todo drift en `finally` y volver a verificar el rol mínimo.
9. Mantener verdes las negativas de UPDATE directo/no-op desde sesión restringida y comparar antes
   y después las tablas de snapshots y aceptación/idempotencia HTTP. El aislamiento acredita que
   el proceso no levanta stack web ni cliente HTTP de aplicación; no se afirma observación global
   de sockets del sistema operativo.
10. Parametrizar las cuatro `-Dspring.datasource.*`; cada una falla antes de abrir una sesión nueva
   y nunca reemplaza las variables editoriales validadas.
11. Ejecutar readiness y al menos un apply mediante `scripts/legal-manifest-editor.sh` con
   `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` hostiles. Exigir launcher real, ningún
   mensaje pre-main, stderr limpio y argumentos intactos.
12. Aplicar sobre un estado fresco con el agent pre-main `N=0`: exit `3`, stdout vacío, commit SQL
    confirmado y retry capturado `ALREADY_APPLIED`. Repetir `CLOSE_IMMEDIATELY` sólo como evidencia
    suplementaria del pipe real, sin usarlo como prueba primaria de determinismo.
13. Aplicar otro estado fresco con el agent de N bytes: exit `3`, prefijo exacto no parseable, un
    solo `{`, ningún segundo envelope ni `UNKNOWN`; confirmar el commit por SQL y converger en el
    retry.
14. Para cada salida completa exigir bytes UTF-8, un objeto JSON seguido por un LF, schema v3,
    `status/outcome/persisted/readiness/exit` exactos, stderr allowlisteado y ausencia de paths,
    SQL, stack traces, passwords y canaries.
15. Comparar SHA-256 de manifest, Markdown y plan antes/después. Acreditar que el proceso legal no
    activa web, Flyway, JPA, runners ni schedulers, y que el agente test-only está ausente de ambos
    JAR.
16. Registrar nombre, hash, Start-Class y ausencia de `application-secret.properties` para ambos
    artefactos.

### Pruebas y puerta

~~~bash
./mvnw clean -Dtest=LegalCliProcessSupportTest,LegalManifestEditorLauncherTest -Dit.test=LegalEditorialProcessIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT,LegalManifestImportProcessIT,LegalEditorialPrivilegeVerifierIT,LegalImportPrivilegeVerifierIT verify
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Commits locales:

- `ab2a917 test(legal): prepara fixture de procesos editoriales`;
- `2f5985f test(legal): acredita ciclo promote empaquetado`;
- `bc83eaa test(legal): construye planes de procesos editoriales`;
- `0d40ae1 test(legal): acredita lifecycle editorial empaquetado`;
- `0b7f7f5 test(legal): prepara seguridad editorial empaquetada`;
- `9629a99 test(legal): acredita seguridad del jar editorial`;
- `8e6062b test(legal): comparte agente de falla stdout`;
- `2efc19f test(legal): acredita commit sin salida editorial`.

### Evidencia de cierre 10E

#### Lifecycle empaquetado y postestado

`LegalEditorialProcessIT` migra una base efímera dedicada PostgreSQL `16.14` hasta V27, crea los
roles importador y editorial exactos y localiza los dos JAR producidos por Maven. Los releases,
Markdown y planes viven bajo una ruta `@TempDir` con espacios; confirmations y SHA-256 se calculan
con los validadores reales. El snapshot owner descubre dinámicamente las `25` tablas y `13`
secuencias `legal_%` y conserva filas completas ordenadas, `last_value` e `is_called`.

La matriz funcional real quedó congelada así:

| Operación | Exit / status / outcome | Readiness | Delta o estado autoritativo |
|---|---|---|---|
| import source | `0 / PASS / IMPORTED` | n/a | publicación sellada, `11` documentos y `6` requisitos |
| readiness inicial | `2 / BLOCKED` | `NOT_READY` | sin mutación |
| plan-promote | `0 / PASS / APPLICABLE` | `READY` esperado | delta `22/0/12/0/21/0/0/0/8/0` |
| apply-promote | `0 / PASS / APPLIED` | `READY` | estado `11/6/22/12/21/8/0` |
| replay promote | `0 / PASS / ALREADY_APPLIED` | `READY` | mismo receipt y snapshot íntegro |
| plan-promote target incompatible | `2 / BLOCKED / BLOCKED` | n/a | `INITIAL_PROJECTION_ALREADY_EXISTS`, sin mutación |
| plan-replace `1→1` | `0 / PASS / APPLICABLE` | `READY` esperado | delta `1/2/6/18/18/3/3/8/8/1` |
| apply-replace | `0 / PASS / APPLIED` | `READY` | estado `11/6/22/12/21/8/1` |
| replay replace | `0 / PASS / ALREADY_APPLIED` | `READY` | mismo receipt y snapshot íntegro |
| plan-retire mixto | `0 / PASS / APPLICABLE` | `NOT_READY` esperado | delta `1/0/1/2/0/0/0/4/0/0` |
| apply-retire | `0 / PASS / APPLIED` | `NOT_READY` | estado `11/6/23/13/19/4/1` |
| replay retire | `0 / PASS / ALREADY_APPLIED` | `NOT_READY` | mismo receipt y snapshot íntegro |

El reemplazo reutiliza `10` documentos, reemplaza uno `1→1`, reutiliza `4` requisitos y reemplaza
`2`. El retiro elimina `aviso-clientes-taller` y `customer-photo-attestation`. Cada apply se
contrasta campo a campo con SQL owner independiente; cada plan exige el delta exacto y ausencia de
mutación; cada replay conserva filas y secuencias completas. Los SHA-256 de ambos releases, sus
Markdown y los dos planes permanecen iguales antes y después.

#### Roles, configuración hostil y datos HTTP

Sobre un REPLACE realmente aplicable se sembró, en una sola transacción owner, un agregado de
aceptación/idempotencia que deja filas no vacías en las seis tablas HTTP protegidas y avanza sus
tres identity sequences. Después se ejecutó el JAR con:

| Credencial o drift | Resultado | Postestado |
|---|---|---|
| rol importador | `3 / ERROR / ROLE_PRIVILEGE_DRIFT` | snapshot íntegro idéntico |
| owner de la base | `3 / ERROR / ROLE_PRIVILEGE_DRIFT` | snapshot íntegro idéntico |
| editorial con `SELECT` extra sobre una secuencia | `3 / ERROR / ROLE_PRIVILEGE_DRIFT` | snapshot íntegro idéntico |
| editorial cambiado temporalmente a `INHERIT` | `3 / ERROR / ROLE_PRIVILEGE_DRIFT` | snapshot íntegro idéntico |

Los dos drifts temporales se restauran en `finally` y el verificador del rol mínimo vuelve a pasar.
Las negativas directas/no-op y el aislamiento permanecen cubiertos por las regresiones incluidas
en la puerta final.

Cada una de `spring.datasource.url`, `username`, `password` y `driver-class-name` fue inyectada
como `-D` en una JVM nueva. Las cuatro terminaron `3 / ERROR`, `persisted=false` y
`EDITORIAL_DATASOURCE_SYSTEM_PROPERTY_FORBIDDEN`. Una conexión owner persistente leyó el contador
acumulativo `pg_stat_database.sessions` antes y después: no se abrió ninguna sesión DB nueva. El
snapshot owner y el árbol de release también quedaron idénticos.

El launcher real `scripts/legal-manifest-editor.sh` ejecutó readiness y apply-promote con
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` hostiles. Readiness produjo
`BLOCKED + NOT_READY`; apply produjo `PASS + APPLIED + READY`. No apareció ningún mensaje pre-main,
stderr quedó vacío, los argumentos con rutas espaciadas llegaron intactos y ninguno de los cuatro
canarios se filtró.

#### Pérdida total y parcial de stdout

El agente Java test-only se empaqueta bajo `@TempDir` con `CREATE_NEW`; el helper valida JAR
regular, ruta absoluta sin `=`, `prefixBytes >= 0`, `Premain-Class` y el inventario exacto de dos
entradas. Tres estados frescos demostraron que el commit ocurre antes de entregar el reporte:

| Frontera de salida | Exit / stdout / stderr | Evidencia DB previa al retry | Retry |
|---|---|---|---|
| agente `N=0` | `3 / 0 bytes / vacío` | `11/6/22/12/21/8/0` | `ALREADY_APPLIED`, snapshot idéntico |
| agente `N=78` | `3 / 78 bytes exactos / vacío` | `11/6/22/12/21/8/0` | `ALREADY_APPLIED`, snapshot idéntico |
| pipe `CLOSE_IMMEDIATELY` suplementario | `3 / no capturado / vacío` | `11/6/22/12/21/8/0` | `ALREADY_APPLIED`, snapshot idéntico |

El prefijo de `78` bytes es exactamente
`{"reportVersion":3,"command":"apply-promote","status":"PASS","persisted":true,`:
no es JSON parseable, contiene una sola `{`, no tiene LF, `UNKNOWN` ni segundo envelope. Exit `3`
no se interpretó como rollback; sólo SQL owner y el retry exacto acreditan el commit.

#### Artefactos, puerta y auditoría

La puerta limpia se ejecutó con Amazon Corretto `21.0.10+7-LTS`, Maven `3.9.11`, Flyway
`11.14.1`, Testcontainers `2.0.5` y PostgreSQL `16.14` (`postgres:16-alpine`). Flyway validó y
aplicó `27` migraciones hasta V27.

| Artefacto de la puerta limpia | SHA-256 | Start-Class |
|---|---|---|
| `mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar` | `ac0085fedc420c80f6db84b3880829b07c4d7b93d763f339a077143a96175519` | `com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication` |
| `mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar` | `23fa44f96c83a9e5c3d816a3a38842307513729f09dc78bbef89a7f1fe91cdca` | `com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestCli` |

Ningún manifest declara `Premain-Class`, `Agent-Class` o `Launcher-Agent-Class`; ninguno de los
JAR contiene `LegalCliStdoutFailureAgent` ni `application-secret.properties`. Ambos fat JAR
conservan dependencias web/Flyway/JPA por diseño del empaquetado compartido: la garantía correcta
es su **no activación en runtime**, acreditada por `LegalManifestCliIsolationIT` `3/3`, junto con
la ausencia de runners y schedulers activos.

Comando fresco:

~~~bash
./mvnw clean -Dtest=LegalCliProcessSupportTest,LegalManifestEditorLauncherTest -Dit.test=LegalEditorialProcessIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT,LegalManifestImportProcessIT,LegalEditorialPrivilegeVerifierIT,LegalImportPrivilegeVerifierIT verify
~~~

Resultado: Surefire `11/11`; Failsafe `48/48` —ProcessIT `7`, isolation `3`, CLI process `12`,
import process `9`, privilegios editoriales `8` y privilegios importador `9`—; cero fallos,
errores u omitidos; `BUILD SUCCESS`; tiempo Maven observado `2:30`. También pasaron
`sh -n scripts/legal-manifest-editor.sh` y `git diff --check`.

Hubo dos rojos de fixture durante el desarrollo: el plan confinado rechazó la representación
lógica `/var` del temporal macOS y se corrigió usando su `toRealPath()`; luego un helper AssertJ
rechazó un vararg vacío de canarios y se volvió condicional. Ninguno exigió tocar producción. Las
auditorías independientes de lifecycle, seguridad/launcher y stdout cerraron sin hallazgos
P0–P3. No hubo flaky, skipped, retry automático, push, deploy, cambio frontend, V28 ni promoción
de contenido legal real.

## Subcorte 10F — Puerta final y documentación

Estado: cerrado el 2026-08-31.

### Objetivo

Ejecutar una matriz fresca completa, registrar métricas reproducibles y cerrar Corte 10 sin cerrar
la Fase 2.3C ni habilitar integración pública.

### Archivos

Modificar exclusivamente:

- este plan;
- `docs/plans/2026-08-31-legal-editorial-concurrency-capacity-process-design.md`;
- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`.

No modificar frontend, runbooks, `FRONTEND_INTEGRATION.md`, `README.md`, código ni tests en 10F.

### Evidencia a registrar

1. Rama, hashes 10A–10E, auditorías e incidentes —o `ninguno`—.
2. JDK/vendor, Maven, PostgreSQL, Flyway y 27 migraciones hasta V27.
3. Comando exacto, duración de pared, Surefire/Failsafe, fallos, errores y omitidos de cada puerta.
4. Resultado de cada carrera y fallo contrastado con filas, transiciones, secuencias y SQL owner.
5. Fixture importable `128/256/16`; fixture editorial compuesto `87/256/16/88 slots`, con un
   máximo de 11 referencias por requisito READY, 2.642 referencias por publicación y 5.284
   proyecciones activas; delay 5 ms y, por separado para readiness/plan/apply: observado, cap,
   duración, llamadas totales/por categoría, mayor statement, advisory lock, sentinels primarios
   `expected + 1`, caps derivados y binds exactos de los límites estructurales.
6. Memoria reproducible o razón explícita para no fijarla.
7. Matriz JAR con estado, outcome, readiness, exit, JSON, stderr, canaries, rol y postestado DB.
8. Nombre/hash/Start-Class/contenido de ambos JAR y ausencia de secretos/agente de test.
9. Launcher real, tres canales JVM limpiados, no activación de web/Flyway/JPA/runners/schedulers y
   tablas de snapshots/aceptación/idempotencia HTTP intactas.
10. Confirmación de que no hubo push, deploy, cambio frontend, V28 ni promoción de contenido real.

Cada `clean` elimina reportes anteriores. Capturar inmediatamente después de cada lifecycle sus
resúmenes y métricas antes de ejecutar el siguiente comando.

### Actualización del plan maestro

1. Marcar Cortes 1–10 completados y Corte 11 pendiente.
2. Enlazar diseño, commit `2dcad53`, este plan, los hashes reales 10A–10E y el parent/asunto de
   10F.
3. Reemplazar la fila única del Corte 10 por filas 10A–10F.
4. Sustituir la puerta antigua sin `clean` por la puerta aprobada y registrar evidencia real.
5. Mantener Fase 2.3C e integración frontend pendientes; `BACKEND-HANDOFF 1` continúa cerrado.

### Puerta final

~~~bash
./mvnw clean -Dit.test=LegalEditorialConcurrencyIT,LegalEditorialFailureIT,LegalEditorialCapacityIT verify
./mvnw clean -Dit.test=LegalEditorialProcessIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
./mvnw clean verify
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Antes del commit, `git status --short` debe mostrar sólo los tres documentos autorizados. Después
del commit, el árbol debe quedar limpio.

Commit:

    docs(legal): cierra corte de procesos reales

### Evidencia de cierre 10F

#### Alcance, rama y trazabilidad

El cierre se ejecutó en `codex/lanzamiento-publico-backend`, con parent `d3a08a8`, y modificó
exclusivamente los tres documentos autorizados. Tres auditorías independientes revisaron la
historia/evidencia de 10A–10B, capacidad 10C y procesos 10D–10E; no quedaron hallazgos técnicos
P0–P2 ni contradicciones bloqueantes. Se corrigieron dos imprecisiones documentales: la frontera
dual aprobada en 10A.1 sustituye el viejo reloj editorial único, y `BACKEND-HANDOFF 1` continúa
cerrado.

Corte 10 empezó con alcance test/docs-only. La carrera determinista de 10A produjo
`APPLIED + ERROR` porque una transacción antigua conservaba un `transaction_timestamp()` anterior
al commit que observaba. La regla de parada se respetó: diseño `246ef54`, precisión causal
`69c3763`, plan `d882c63`, fix productivo único `9f00cb6`, acreditación `77c80c5` y cierre
`af5b847`. 10B–10E no requirieron cambios productivos. La matriz completa de hashes queda al final
de este plan.

Incidentes cerrados: 10B corrigió una assertion de conteo más estricta que el contrato y endureció
writer/contador/cleanup tras tres auditorías; 10C sólo tuvo rojos TDD esperados de inventario,
sentinels y binds, sin incidente productivo; 10D corrigió cobertura del glob, dos P3 del agente y la
preservación del timeout explícito de `75 s`; 10E corrigió la ruta lógica `/var` del temporal macOS
y un vararg AssertJ vacío. Todos quedaron acreditados por sus puertas y reauditorías; 10F no abrió
un incidente nuevo.

#### Ambiente y tres puertas limpias

- Amazon Corretto `21.0.10+7-LTS`, Maven `3.9.11`, Flyway `11.14.1`, Testcontainers `2.0.5` y
  PostgreSQL `16.14` mediante `postgres:16-alpine`.
- Flyway validó y aplicó `27` migraciones hasta V27; no se creó V28.
- Locale `es_AR`, UTF-8, macOS `26.6.2` ARM64 y Docker Desktop `28.3.3`.

| Puerta exacta | Surefire | Failsafe | Tiempo Maven | Resultado |
|---|---:|---:|---:|---|
| `./mvnw clean -Dit.test=LegalEditorialConcurrencyIT,LegalEditorialFailureIT,LegalEditorialCapacityIT verify` | `4.283` | `10` | `1:57` | verde |
| `./mvnw clean -Dit.test=LegalEditorialProcessIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify` | `4.283` | `22` | `2:24` | verde |
| `./mvnw clean verify` | `4.283` | `250` | `8:28` | verde |

Las tres puertas terminaron con cero fallos, errores u omitidos y sin retry automático. En la
primera, concurrencia fue `4/4` en `11,871 s`, fallos `5/5` en `14,926 s` y capacidad `1/1` en
`28,267 s`. En la segunda, lifecycle empaquetado fue `7/7` en `72,67 s`, aislamiento `3/3` en
`1,940 s` y procesos CLI `12/12` en `7,685 s`. Cada resumen se capturó antes del siguiente
`clean`; el último lifecycle global produjo la evidencia de artefactos que figura más abajo.

#### Concurrencia, fallos y verdad PostgreSQL

La puerta fresca volvió a demostrar:

- PROMOTE idénticos: exactamente `APPLIED + ALREADY_APPLIED`, un único writer, receipt compartido
  y replay posterior sin cambio de filas ni secuencias;
- targets incompatibles y REPLACE con predecesor compartido: como máximo una confirmación y
  postestado owner coherente;
- import, dry-run, readiness, plan y apply cooperan sobre el mismo advisory lock;
- timeout real `55P03`: `ERROR / CONCURRENT_OPERATION`, cero DML y retry convergente;
- deadlock real `40P01`: rollback exacto, `CONCURRENT_OPERATION` y retry convergente;
- sesión terminada antes del commit: `ERROR / EDITORIAL_OBSERVATION_FAILED`, fuente exacta y una
  única aplicación al reintentar;
- ACK perdido reconciliable: `ALREADY_APPLIED`, receipt exacto y un único writer;
- ACK perdido sin reconciliación disponible: `UNKNOWN / COMMIT_OUTCOME_UNKNOWN`, nunca falso
  `APPLIED`; SQL owner y replay exacto determinan luego el estado.

Las assertions contrastan resultados con publicaciones, transiciones, secuencias, DML observado y
consultas owner independientes; stdout, excepción o metadata tentativa nunca son fuente de verdad.

#### Capacidad y límites congelados

El fixture importable independiente conserva `128 documentos / 256 requisitos / 16 scopes`, con
`16` referencias por requisito y sin presentarlo como READY. El fixture editorial realizable usa
por publicación `87 documentos / 256 requisitos / 16 scopes / 88 slots`: once tipos, un locale y
ocho contextos; split/merge obligan un documento multicontexto. Tiene máximo `11` referencias por
requisito READY, `2.642` referencias requisito-documento por publicación y una cardinalidad
derivada/observada de `5.284` proyecciones activas. El delta exacto fue
`7/8/18/83/83/5/5/16/16/3`; plan `PASS/APPLICABLE`, apply `PASS/APPLIED` y readiness final `READY`.

La última puerta global, con delay test-only de `5 ms` por ejecución JDBC lógica, registró:

| Operación | Duración | RT y categorías A/D/R/T/S/O | Filas | Máx. por SQL | Mayor statement | Advisory |
|---|---:|---|---:|---:|---:|---:|
| readiness | `0,986486250 s` | `52 = 1/0/0/3/48/0` | `11.636` | `2` | `62,443875 ms` | `9,763292 ms` |
| plan | `1,929335625 s` | `96 = 1/0/0/3/92/0` | `36.042` | `4` | `56,667791 ms` | `8,444541 ms` |
| apply | `4,513654500 s` | `184 = 1/17/5/4/157/0` | `68.648` | `6` | `921,769458 ms` | `8,136833 ms` |

`A/D/R/T/S/O` significa advisory/DML/row-lock/control transaccional/SELECT/other. Cada operación
tuvo un commit, cero rollbacks y cero fallos JDBC. Los caps congelados son `52/96/184` RT,
`11.636/36.042/68.648` filas y `2/4/6` repeticiones máximas; las duraciones observadas permanecen
debajo de `70 s`, cada statement debajo de `30 s` y el presupuesto transaccional sigue en `75 s`.

Los sentinels primarios mantuvieron fail-closed y cero DML:

| Sentinel | Duración | RT | Filas | Máx. por SQL | Mayor statement | Advisory |
|---|---:|---:|---:|---:|---:|---:|
| documentos | `0,791931167 s` | `47` | `7.712` | `1` | `56,310875 ms` | `9,140541 ms` |
| requisitos | `0,916611916 s` | `50` | `11.123` | `1` | `69,645792 ms` | `8,344375 ms` |
| miembros de scope | `0,825072125 s` | `52` | `11.684` | `2` | `53,674042 ms` | `8,145125 ms` |

Cada sentinel inyectó tres filas inválidas, devolvió `NOT_READY/BLOCKED` con
`PUBLICATION_CONTENT_MISMATCH` y sólo relajó sus familias declaradas hasta `expected + 1` o el cap
derivado exacto. El inventario cerrado cubre `74` familias SELECT/row-lock; `35` límites
parametrizados deben terminar exactamente en `LIMIT ?` y presentar el último bind entero en todas
las ejecuciones. Los binds congelados son:

- origen/target: documentos `88`, contextos `89`, requisitos `257`, audiencias `513`, referencias
  `2.643`, scopes `17`, miembros `513`, target documentos `88` y target requisitos `257`;
- fingerprints activos: slots `89`, punteros `17`, miembros `4.097` y referencias `65.537`;
- readiness: versiones documento `8.193`, versiones requisito `16.385`, lotes `8.193` y miembros
  predecessor/successor `16.385`;
- planner/evidencia: membresía documento `1.025`, requisito `2.049`, slots `1.025`, punteros `65`,
  scopes/evidencia `65`, miembros `16.385` y documentos `32.769`;
- historia/lotes: transiciones documento/requisito `8.193`, identidades de lote `513`,
  predecesores/sucesores/historia `8.193`.

Dos lookups de publicación conservan `LIMIT 2`; catorce familias multirrow sin límite propio están
acotadas por IDs previos, unicidad V27 o enums. No se construyó artificialmente una base de
8K–65K: se acreditó el bind exacto entregado al driver y la regresión split/merge. Para import
máximo se congelaron caps, no valores observados publicables: fresh `<=1.000` RT, replay `<=500` y
menor que fresh; el sentinel consume exactamente `129` filas de la relación.

No se fija memoria: Docker informó `3.919 MB` compartidos, pero no estaban congelados heap JVM,
límite por contenedor, GC ni carga del host. Publicar ese valor como pico o cap no sería
reproducible; esta limitación explícita no bloquea el cierre aprobado.

#### Procesos reales, seguridad y postestado

La matriz completa se conserva en las tablas de “Lifecycle empaquetado y postestado”, “Roles,
configuración hostil y datos HTTP” y “Pérdida total y parcial de stdout” de 10E. La puerta 10F la
revalidó: import source `0 / PASS / IMPORTED`; readiness `BLOCKED/NOT_READY` exit `2`;
planes PROMOTE/REPLACE/RETIRE `PASS/APPLICABLE` exit `0`; applies `PASS/APPLIED` exit `0` con
readiness `READY`, `READY` y `NOT_READY`; replays `PASS/ALREADY_APPLIED`; y target incompatible
`BLOCKED` exit `2`. Los siete escenarios del JAR volvieron a pasar y cada plan/replay preservó
filas, secuencias, SHA-256, Markdown y planes según su contrato. Los reportes entregados conservaron
schema JSON v3, un único objeto UTF-8 y LF final.

La seguridad siguió dando exit `3 / ERROR / ROLE_PRIVILEGE_DRIFT` para importador, owner,
privilegio SELECT extra e `INHERIT`, sin mutación. Las cuatro
`-Dspring.datasource.{url,username,password,driver-class-name}` hostiles dieron exit `3`,
`persisted=false` y `EDITORIAL_DATASOURCE_SYSTEM_PROPERTY_FORBIDDEN`; la garantía observada es
ninguna sesión nueva contabilizada en la base objetivo, no una afirmación global sobre PostgreSQL.

El launcher ejecutable conservó `exec ... "$@"`, rutas con espacios y saneó
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS`. `sh -n` pasó. Los contextos CLI
acreditados no activaron web, Flyway, JPA, runners ni schedulers. Ambos fat JAR sí contienen esas
dependencias por su empaquetado compartido; no se afirma su ausencia física ni una observación
universal de sockets.

La pérdida total/parcial de stdout se revalidó con agente `N=0`, agente `N=78` y pipe cerrado:
exit `3`, stderr vacío, commit autoritativo previo al retry y retry `ALREADY_APPLIED` sin duplicar.
El prefijo de `78` bytes siguió no parseable, sin LF, `UNKNOWN` ni segundo envelope. En los casos
con reporte entregado hubo un único JSON UTF-8, stderr vacío o allowlisteado y ningún secreto,
path, SQL, stack trace o canary definido filtrado. El snapshot global cubrió `25` tablas y `13`
secuencias `legal_%`; el inventario editorial restringido sigue siendo `19` tablas y `10`
secuencias. Las seis tablas HTTP protegidas y sus tres identity sequences permanecieron intactas.

#### Artefactos finales 10F

| Artefacto del `clean verify` final | SHA-256 | Start-Class |
|---|---|---|
| `mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar` | `e5d3bafa48d80d346ce5afa05177f78eaff79780abb351c1d587699f21f68d5a` | `com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication` |
| `mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar` | `9b9eab3aec0a555c68e1081464b7fd20a3621ffc9a5672e8ca5d107fc23c2fa1` | `com.leonardorozza.mvgrreparacionesbackend.legal.manifest.cli.LegalManifestCli` |

Ningún manifest declara `Premain-Class`, `Agent-Class` o `Launcher-Agent-Class`; ninguno de los
artefactos contiene `LegalCliStdoutFailureAgent` ni `application-secret.properties`. Maven no fija
`outputTimestamp`, por lo que estos hashes identifican el build final 10F y no sustituyen los
hashes históricos de la puerta 10E.

#### Cierre de alcance

`git diff --check` y el inventario final se ejecutan inmediatamente antes del commit. No hubo push
ni deploy, ni cambios en frontend, runbooks, `FRONTEND_INTEGRATION.md`, API, V28, contenido legal
real, Java, tests, migraciones, grants, roles, `pom.xml` o launcher. Corte 10
queda cerrado; Fase 2.3C, integración frontend, staging y producción continúan pendientes de Corte
11 o de sus planes separados. El hash de 10F no puede autorreferenciarse dentro del mismo commit:
se identifica por parent `d3a08a8` y asunto `docs(legal): cierra corte de procesos reales`.

## Criterios de parada

Cualquiera de estos hallazgos bloquea el subcorte correspondiente:

- doble confirmación incompatible o falso `APPLIED`;
- discrepancia entre JSON y PostgreSQL;
- deadlock/session kill no reproducible sin sleeps;
- N+1, batching insuficiente, lectura primaria sentinel por encima de `expected + 1`, proyección
  por encima de su cap sentinel o límite estructural sin su bind exacto;
- necesidad de tocar producción, migraciones, grants, roles, `pom.xml` o timeouts;
- agente test-only presente en un artefacto;
- secreto, path, SQL, stack trace o canary filtrado;
- Start-Class o contenido de JAR incorrecto;
- importador capaz de ejecutar una mutación editorial;
- test flaky, skipped, retry automático o reporte Failsafe stale;
- cualquier puerta focal o `clean verify` rojo.

La memoria no reproducible no bloquea si 10F documenta la razón. Toda otra métrica requerida
ausente sí bloquea el cierre.

## Matriz de commits registrados

| Corte | Commit |
|---:|---|
| diseño | `2dcad53 docs(legal): diseña concurrencia y procesos editoriales` |
| plan | `73f5a83 docs(legal): planifica concurrencia y procesos editoriales` |
| 10A | `246ef54`, `69c3763`, `d882c63`, `9f00cb6`, `77c80c5`, `af5b847` |
| 10B | `794c9b2`, `b846965` |
| 10C | `c0f02cc`, `e227a2d`, `dc9b619`, `148f989`, `4e511e5` |
| 10D | `09c0e68`, `9e8148b`, `f528f46`, `23139f8`, `e021d67`, `373bc47`, `5923eec`, `d8f6a97`, `baa188f` |
| 10E | `ab2a917`, `2f5985f`, `bc83eaa`, `0d40ae1`, `0b7f7f5`, `9629a99`, `8e6062b`, `2efc19f`, `d3a08a8` |
| 10F | este commit, parent `d3a08a8`, asunto `docs(legal): cierra corte de procesos reales` |

## Criterio de cierre

Corte 10 quedó cerrado porque las seis fronteras —import, dry-run, readiness, plan, apply y
reconciliación— comparten un advisory lock real, las carreras no confirman dos estados
incompatibles, los fallos no fabrican éxito, el fixture máximo cumple presupuestos/caps y los siete
comandos funcionan con JAR, rol y launcher reales. La pérdida total o parcial de stdout conserva
exit `3`, estado DB autoritativo y ausencia de un segundo envelope.

El cierre no habilita producción pública. Corte 11 conserva runbook, coordinación cross-repo y
cierre de Fase 2.3C; V28, API, aceptación, seguridad, staging, deploy y frontend siguen pendientes.
