# Corte 10 — Plan de implementación de concurrencia, fallos, capacidad y procesos reales

Fecha: 2026-08-31

Estado: 10A y 10B cerrados; 10C a 10F pendientes

Rama backend: `codex/lanzamiento-publico-backend`

Diseño aprobado:

- `docs/plans/2026-08-31-legal-editorial-concurrency-capacity-process-design.md`;
- commit local `2dcad53`.

Plan maestro:

- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`.

Este plan detalla y reemplaza para la ejecución el bosquejo de un único commit que el plan maestro
reservaba al Corte 10. La aprobación documental no amplía por sí sola Java productivo,
migraciones, `pom.xml`, API, frontend, runbooks ni deploy.

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
11. Toda lectura multirrow potencialmente corrupta consume como máximo `expected + 1` filas.
12. Los procesos reciben un entorno mínimo explícito más los overrides declarados; no heredan
    canales Spring, datasource, editoriales/importador ni opciones JVM ambient.
13. El agente de stdout vive sólo bajo `src/test`, se empaqueta en un temporal y debe estar ausente
    de ambos JAR de aplicación.
14. `scripts/legal-manifest-editor.sh` sólo cambia ante una prueba roja real y nunca recibe hooks o
    flags de test.
15. Si una prueba exige cambiar `src/main`, migraciones, V27/V28, grants, roles, `pom.xml`, schemas,
    API o timeouts, detener el subcorte, conservar la evidencia roja y solicitar diseño/aprobación
    separados.
16. Frontend, runbooks, `FRONTEND_INTEGRATION.md`, staging, deploy, cierre cross-repo y
    `BACKEND-HANDOFF 1` permanecen en Corte 11 o planes posteriores.

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

Estado: pendiente.

### Objetivo

Medir readiness, plan y apply sobre el fixture máximo, congelar caps JDBC deterministas y bloquear
lecturas no acotadas sin relajar presupuestos.

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
3. Construir fuente y target válidos de 128 documentos, 256 requisitos y los 16 scopes. Mantener el
   máximo combinando reuse mayoritario, una adición compensada, un lote `1→1`, un split `1→2` y un
   merge `2→1`, con requisitos reutilizados y reemplazados.
4. Preparar estado sin instrumentación, promover la fuente y acreditar el plan compuesto exacto
   antes de iniciar mediciones.
5. Sobre el datasource editorial restringido instrumentado con 5 ms, resetear y medir por
   separado readiness `NOT_READY`, plan `APPLICABLE` y apply `APPLIED`.
6. Para cada operación exigir duración `<70 s`, statement `<30 s`, transacción productiva de 75 s,
   rol/preflights exactos y resultado funcional correcto.
7. Inventariar cada lectura multirrow por SQL normalizado. Agregar corrupción sentinel por familia
   de relación y exigir consumo máximo `expected + 1`; observer y setup usan otro datasource.
8. Registrar primero valores observados. Tras revisar que no existe N+1, congelar caps exactos o
   márgenes enteros justificados por operación/categoría, nunca porcentajes arbitrarios.
9. Medir memoria sólo si el entorno permite repetir la cifra bajo condiciones controladas. Si no,
   dejar la razón explícita para 10F sin inventar un cap.
10. Si una lectura supera `expected + 1`, el tiempo excede el presupuesto o aparece batching
    deficiente, detener 10C con prueba roja; no modificar producción, migraciones ni timeouts.

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

Pendiente.

## Subcorte 10D — Infraestructura de procesos

Estado: pendiente.

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

Pendiente.

## Subcorte 10E — Matriz del JAR editorial

Estado: pendiente.

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
15. Comparar SHA-256 de manifest, Markdown y plan antes/después. Acreditar ausencia de web, Flyway,
    JPA, runners, schedulers y agente test-only en ambos JAR.
16. Registrar nombre, hash, Start-Class y ausencia de `application-secret.properties` para ambos
    artefactos.

### Pruebas y puerta

~~~bash
./mvnw clean -Dtest=LegalCliProcessSupportTest,LegalManifestEditorLauncherTest -Dit.test=LegalEditorialProcessIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT,LegalManifestImportProcessIT,LegalEditorialPrivilegeVerifierIT,LegalImportPrivilegeVerifierIT verify
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Commit:

    test(legal): acredita jar editorial

### Evidencia de cierre 10E

Pendiente.

## Subcorte 10F — Puerta final y documentación

Estado: pendiente.

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
5. Fixture `128/256/16`, delay 5 ms y, por separado para readiness/plan/apply: observado, cap,
   duración, llamadas totales/por categoría, mayor statement, advisory lock y `expected + 1`.
6. Memoria reproducible o razón explícita para no fijarla.
7. Matriz JAR con estado, outcome, readiness, exit, JSON, stderr, canaries, rol y postestado DB.
8. Nombre/hash/Start-Class/contenido de ambos JAR y ausencia de secretos/agente de test.
9. Launcher real, tres canales JVM limpiados, ausencia de web/Flyway/JPA/runners/schedulers y
   tablas de snapshots/aceptación/idempotencia HTTP intactas.
10. Confirmación de que no hubo push, deploy, cambio frontend, V28 ni promoción de contenido real.

Cada `clean` elimina reportes anteriores. Capturar inmediatamente después de cada lifecycle sus
resúmenes y métricas antes de ejecutar el siguiente comando.

### Actualización del plan maestro

1. Marcar Cortes 1–10 completados y Corte 11 pendiente.
2. Enlazar diseño, commit `2dcad53`, este plan y los hashes reales 10A–10F.
3. Reemplazar la fila única del Corte 10 por filas 10A–10F.
4. Sustituir la puerta antigua sin `clean` por la puerta aprobada y registrar evidencia real.
5. Mantener Fase 2.3C, integración frontend y `BACKEND-HANDOFF 1` abiertos.

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

Pendiente.

## Criterios de parada

Cualquiera de estos hallazgos bloquea el subcorte correspondiente:

- doble confirmación incompatible o falso `APPLIED`;
- discrepancia entre JSON y PostgreSQL;
- deadlock/session kill no reproducible sin sleeps;
- N+1, batching insuficiente o lectura por encima de `expected + 1`;
- necesidad de tocar producción, migraciones, grants, roles, `pom.xml` o timeouts;
- agente test-only presente en un artefacto;
- secreto, path, SQL, stack trace o canary filtrado;
- Start-Class o contenido de JAR incorrecto;
- importador capaz de ejecutar una mutación editorial;
- test flaky, skipped, retry automático o reporte Failsafe stale;
- cualquier puerta focal o `clean verify` rojo.

La memoria no reproducible no bloquea si 10F documenta la razón. Toda otra métrica requerida
ausente sí bloquea el cierre.

## Matriz de commits prevista

| Corte | Commit |
|---:|---|
| diseño | `2dcad53 docs(legal): diseña concurrencia y procesos editoriales` |
| plan | `docs(legal): planifica concurrencia y procesos editoriales` |
| 10A | `test(legal): acredita concurrencia editorial` |
| 10B | `test(legal): acredita fallos editoriales` |
| 10C | `test(legal): congela capacidad editorial` |
| 10D | `test(legal): endurece procesos editoriales` |
| 10E | `test(legal): acredita jar editorial` |
| 10F | `docs(legal): cierra corte de procesos reales` |

## Criterio de cierre

Corte 10 queda cerrado cuando las seis fronteras —import, dry-run, readiness, plan, apply y
reconciliación— comparten un advisory lock real, las carreras no confirman dos estados
incompatibles, los fallos no fabrican éxito, el fixture máximo cumple presupuestos/caps y los siete
comandos funcionan con JAR, rol y launcher reales. La pérdida total o parcial de stdout conserva
exit `3`, estado DB autoritativo y ausencia de un segundo envelope.

El cierre no habilita producción pública. Corte 11 conserva runbook, coordinación cross-repo y
cierre de Fase 2.3C; V28, API, aceptación, seguridad, staging, deploy y frontend siguen pendientes.
