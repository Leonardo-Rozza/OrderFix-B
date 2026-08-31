# Corte 9 — Plan de implementación de reconciliación editorial

Fecha: 2026-08-30

Estado: ejecución en curso; Subcorte 9A completado el 2026-08-30, 9B completado el 2026-08-31 y
9C pendiente

Rama backend: `codex/lanzamiento-publico-backend`

Diseño aprobado:

- `docs/plans/2026-08-30-legal-editorial-commit-reconciliation-design.md`;
- commit local `878a0cf`.

Plan maestro:

- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`.

## Objetivo

Agregar una reconciliación automática, conservadora y SELECT-only después de una completion
editorial `UNKNOWN`. Una nueva transacción protegida por el mismo advisory lock puede acreditar el
postestado exacto, la fuente exacta o mantener la incertidumbre. Nunca reintenta DML ni amplía la
superficie pública.

## Reglas de ejecución

1. Usar TDD: cada comportamiento nace en una prueba fallida y recibe la implementación mínima.
2. Ejecutar Maven con JDK 21 activo mediante `JAVA_HOME`.
3. Cada subcorte termina en un commit local atómico; no hacer push ni deploy.
4. Antes de cada commit ejecutar su puerta focal, `git diff --check`, revisar el diff completo y
   realizar una auditoría adversarial independiente.
5. Usar `clean verify` cuando se ejecute Failsafe para no reutilizar reportes anteriores.
6. No tocar V27/V28, migrations, schemas, grants, inventarios, API, controllers, JPA, scheduler,
   frontend, runbooks ni configuración pública.
7. No cambiar JSON v3, receipt, reportes v1/v2 ni contrato del importador salvo que una prueba
   demuestre una incompatibilidad real; detener el corte antes de ampliar contrato.
8. El reconciliador no recibe writers, no llama `apply` y no ejecuta DML.
9. Sólo `APPLICABLE+changeRequired=true` acredita source exacto. BLOCKED, ERROR, excepción o replay
   no verificado conservan UNKNOWN.
10. Toda metadata confirmada se relee desde PostgreSQL; la tentativa permanece privada.

## Subcorte 9A — Estado transaccional editorial

Estado: completado el 2026-08-30.

### Objetivo

Crear el facade editorial sobre el completion core sin abrir JDBC ni cambiar import.

### Archivos

Crear:

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialTransactionState.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialTransactionStateTest.java`.

No modificar todavía `LegalEditorialApplyService`.

### Implementación

1. Delegar callback, `beforeCommit`, `afterCompletion` y retorno normal a
   `LegalTransactionCompletionState`.
2. Registrar el facade como `TransactionSynchronization`, igual que el wrapper de import.
3. Permitir registrar exactamente una vez un marcador monótono `planConstructed`, después de
   construir el execution plan y antes del receipt y del commit boundary.
4. Mantener enums y snapshot editoriales propios, package-private y monótonos.
5. Exponer el receipt sólo con persistencia confirmada.
6. No conservar `LegalEditorialExecutionPlan`, postestado, deltas ni `expectedAppliedAt`: el apply
   entrega por separado al reconciliador la operación validada necesaria para el replan.
7. Rechazar doble inicio, doble plan, receipt fuera de orden y contradicciones de completion.

### Pruebas y puerta

~~~bash
./mvnw -Dtest=LegalEditorialTransactionStateTest,LegalTransactionCompletionStateTest,LegalImportTransactionStateTest test
git diff --check
git status --short
~~~

Commit:

    feat(legal): modela estado transaccional editorial

### Evidencia del Subcorte 9A

- El ciclo TDD comenzó con una falla de compilación esperada porque
  `LegalEditorialTransactionState` todavía no existía; la implementación posterior fue la mínima
  necesaria para cerrar el contrato del facade.
- El facade package-private delega la frontera transaccional al completion core y sólo agrega el
  marcador booleano monótono `planConstructed`. No conserva execution plan, postestado, deltas,
  timestamp tentativo ni metadata JDBC.
- El receipt tentativo permanece oculto hasta una confirmación autoritativa. Rollback confirmado
  produce `NOT_PERSISTED`; completion ambigua conserva `UNKNOWN`; las transiciones inválidas
  fallan temprano sin avanzar evidencia válida.
- Puerta focal con Amazon Corretto 21.0.10: 32/32 pruebas verdes —12 del nuevo facade, 8 del core y
  12 del wrapper de import—. Suite unitaria completa fresca: 4.205/4.205, sin fallos, errores ni
  omitidos.
- Las revisiones adversariales de implementación, alcance, pruebas y documentación cerraron sin
  hallazgos P1, P2 o P3 pendientes. `git diff --check` quedó limpio.
- 9A no modifica apply, reconciliación, JDBC, import, V27/V28, schemas, grants, API, frontend ni
  contratos públicos. No hubo push ni deploy.

## Subcorte 9B — Reconciliador SELECT-only

Estado: completado el 2026-08-31.

### Objetivo

Clasificar evidencia PostgreSQL sin integrar todavía el reconciliador al apply.

### Archivos

Crear:

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialCommitReconciler.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialCommitReconcilerTest.java`.

Modificar:

- `LegalManifestDatabaseGate`;
- `LegalManifestDatabaseGateTest`;
- `LegalEditorialDatabaseConfiguration`;
- `LegalEditorialDatabaseIsolationIT`.

### Implementación

1. Agregar al gate una acreditación package-private específica para reconciliación editorial
   read-only.
2. Exigir manager exactamente `DataSourceTransactionManager`, mismo datasource,
   `rollbackOnCommitFailure=false`, `REQUIRES_NEW`, `READ_COMMITTED`, timeout exacto y read-only.
3. Exigir schema verifier y privilege verifier exactos, ordenados y sobre el mismo `JdbcTemplate`.
4. Hacer fallar el constructor ante gate mutable, participante JDBC ajeno o preflight distinto.
5. Modelar una clasificación interna cerrada: POST_EXACT con receipt, SOURCE_EXACT sin issue y
   UNKNOWN sin metadata DB.
6. Ejecutar planner y verifier dentro de una única transacción/sesión protegida por el advisory
   lock.
7. Leer un `transaction_timestamp()` nuevo dentro de la frontera read-only; no reutilizar
   `Instant.now()` ni el timestamp tentativo de la transacción anterior.
8. POST_EXACT requiere replay `changeRequired=false` y verifier completo.
9. SOURCE_EXACT requiere exclusivamente `APPLICABLE+changeRequired=true` del replan completo.
10. BLOCKED, ERROR, excepción, verifier fallido, lock no adquirido o DB inaccesible devuelven
   UNKNOWN.
11. No inyectar writers ni reutilizar receipt tentativo, `operationId` o SHA como evidencia.

### Pruebas y puerta

~~~bash
./mvnw -Dtest=LegalEditorialCommitReconcilerTest,LegalManifestDatabaseGateTest,LegalEditorialPlannerCoreTest,LegalEditorialPostStateVerifierTest test
./mvnw clean -Dit.test=LegalEditorialDatabaseIsolationIT verify
git diff --check
git status --short
~~~

Commit:

    feat(legal): clasifica evidencia de commits ambiguos

### Evidencia del Subcorte 9B

- El ciclo TDD comenzó con la falla de compilación esperada porque
  `LegalEditorialCommitReconciler` todavía no existía. La implementación posterior quedó interna,
  package-private, con un único constructor acreditado y una matriz cerrada
  `POST_EXACT`/`SOURCE_EXACT`/`UNKNOWN`.
- El gate de reconciliación exige exactamente `DataSourceTransactionManager`,
  `rollbackOnCommitFailure=false`, `REQUIRES_NEW`, `READ_COMMITTED`, timeout de producción y
  `readOnly=true`; también exige el mismo datasource/JdbcTemplate y los preflights exactos de
  schema y privilegios, en ese orden. La configuración Spring inyecta explícitamente el gate
  read-only y el IT de aislamiento congela ese grafo.
- La reconciliación sólo comienza con evidencia `planConstructed=true` y después de comprobar que
  no queda transacción, sincronización ni recurso del datasource anterior ligado al thread. En una
  nueva frontera protegida por el mismo advisory lock lee un `transaction_timestamp()` fresco y
  mantiene planner y verifier dentro de la misma transacción/sesión.
- Sólo un replay `APPLICABLE+changeRequired=false` confirmado por el verificador completo produce
  `POST_EXACT` con receipt releído de PostgreSQL. Sólo
  `APPLICABLE+changeRequired=true` produce `SOURCE_EXACT` sin receipt. Replan nulo, operación
  cruzada, BLOCKED, ERROR, receipt nulo, excepción, `LinkageError`, fallo de lock o DB y cualquier
  evidencia incompleta permanecen `UNKNOWN` sin metadata tentativa.
- La regresión arquitectónica inspecciona campos, constructores, parámetros de métodos y fuente:
  prohíbe writers, apply, failure mapper y DML en el reconciliador. No usa `Instant.now()`,
  `operationId`, SHA ni receipt tentativo como evidencia.
- Puerta focal con Amazon Corretto 21.0.10: 91/91 pruebas verdes —16 del reconciliador, 22 del gate,
  33 del planner y 20 del verificador—. Lifecycle limpio: 4.225/4.225 unitarias y 2/2 pruebas de
  aislamiento del contexto sobre H2 en modo PostgreSQL, sin fallos, errores ni omitidos; ambos JAR
  se empaquetaron. La acreditación sobre PostgreSQL real permanece reservada para 9D.
- Las revisiones adversariales de implementación, alcance y pruebas cerraron sus observaciones de
  evidencia transaccional y SELECT-only sin hallazgos P1, P2 o P3 pendientes. `git diff --check`
  quedó limpio.
- 9B no integra todavía el reconciliador al apply, no ejecuta writers o DML y no modifica import,
  V27/V28, migrations, resources, schemas, grants, API, controllers, JPA, frontend ni contratos
  públicos. No hubo push ni deploy.

## Subcorte 9C — Integración con apply

Estado: pendiente.

### Objetivo

Activar una única reconciliación automática para PROMOTE, REPLACE y RETIRE después de UNKNOWN.

### Archivos

Crear:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyServiceReconciliationTest.java`.

Modificar:

- `LegalEditorialApplyService`;
- `LegalEditorialApplyResult` y su test sólo si hace falta una factory package-private;
- `LegalEditorialDatabaseConfiguration`;
- `LegalEditorialApplyServiceTest`;
- `LegalEditorialApplyServiceReplaceTest`;
- `LegalEditorialRetireServiceTest`;
- `LegalEditorialTransactionBoundaryTest`;
- `LegalInitialPromotionFailureIT`;
- `LegalEditorialReplaceIT`;
- `LegalEditorialRetireIT`;
- `LegalEditorialPrivilegeVerifierIT`, sólo para adaptar el constructor en este subcorte;
- `LegalManifestPersistenceITSupport`.

Actualizar todos los `new LegalEditorialApplyService(...)` en este subcorte. Los tres IT existentes
de ACK perdido pasan a exigir ALREADY_APPLIED en la primera invocación y conservan un replay
posterior SELECT-only. No agregar un overload legacy o un reconciliador no-op que permita omitir el
constructor fail-closed.

### Implementación

1. Reemplazar en apply el uso directo del completion core por el facade editorial.
2. Registrar `planConstructed` inmediatamente después de construir el execution plan y antes de
   cualquier writer; no conservar el objeto tentativo.
3. Mantener sin cambios los caminos PERSISTED y NOT_PERSISTED.
4. Invocar al reconciliador una sola vez únicamente para persistence UNKNOWN y después de
   desenrollar la transacción original.
5. POST_EXACT devuelve ALREADY_APPLIED con receipt reconstruido.
6. SOURCE_EXACT normaliza exclusivamente el throwable original con
   `LegalEditorialFailureMapper`; conservar sólo códigos ERROR allowlisteados y usar
   `EDITORIAL_OBSERVATION_FAILED` como fallback.
7. UNKNOWN conserva `COMMIT_OUTCOME_UNKNOWN` y no expone metadata DB.
8. Un fallo del reconciliador nunca produce ERROR/false ni reemplaza el issue original.
9. No recursar a apply, no reintentar writer y no ejecutar healing.

### Pruebas y puerta

~~~bash
./mvnw -Dtest=LegalEditorialApplyServiceReconciliationTest,LegalEditorialApplyServiceTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialRetireServiceTest,LegalEditorialApplyResultTest,LegalEditorialTransactionBoundaryTest test
./mvnw clean -Dit.test=LegalInitialPromotionFailureIT,LegalEditorialReplaceIT,LegalEditorialRetireIT verify
git diff --check
git status --short
~~~

Commit:

    fix(legal): reconcilia apply editorial ambiguo

## Subcorte 9D1 — Evidencia PostgreSQL concluyente

Estado: pendiente.

### Objetivo

Acreditar commits confirmados sin ACK y no persistencia confirmada sobre PostgreSQL real.

### Archivos

Crear:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReconciliationIT.java`.

Reutilizar fixtures y proxies de fallo sólo desde tests; no agregar switches productivos.

### Escenarios

- ACK perdido después del commit para PROMOTE, REPLACE y RETIRE termina ALREADY_APPLIED en esa
  misma invocación;
- receipt, `appliedAt`, readiness y conteos se reconstruyen desde PostgreSQL;
- filas no afectadas permanecen exactas y no se duplican transiciones ni DML;
- las secuencias sólo reflejan el primer commit real;
- rollback sin ACK inyectado después de `planConstructed`, con source exacto, termina ERROR/false;
- session kill inyectado después de `planConstructed`, cuya nueva observación sí acredita source
  exacto, termina ERROR/false;
- el issue original allowlisteado se conserva y cualquier blocker/issue ajeno usa el fallback;
- retry manual posterior a no persistencia aplica una única vez;
- la sesión original terminó, el reconciliador fue invocado una vez y usó otra transacción con el
  mismo lock.

### Puerta

~~~bash
./mvnw clean -Dit.test=LegalEditorialReconciliationIT,LegalInitialPromotionFailureIT,LegalEditorialReplaceIT,LegalEditorialRetireIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): acredita commits editoriales reconciliados

## Subcorte 9D2 — Incertidumbre PostgreSQL restante

Estado: pendiente.

### Objetivo

Demostrar que evidencia incompleta nunca se transforma en éxito o rollback falso.

### Archivos

Crear:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyFailureIT.java`.

### Escenarios

- marcador `planConstructed=false` conserva UNKNOWN con cero invocaciones al reconciliador y cero
  segunda transacción;
- estado parcial o diferente conserva UNKNOWN;
- session kill inyectado después de `planConstructed` cuya nueva observación también falla conserva
  UNKNOWN;
- DB inaccesible o fallo de conexión armado después de la completion UNKNOWN original y antes de
  abrir el gate de reconciliación conserva UNKNOWN;
- lock holder determinista activado después de la completion UNKNOWN original y antes del gate
  read-only conserva UNKNOWN cuando impide adquirir el advisory lock;
- estado parcial, session kill, DB inaccesible y lock no adquirido acreditan una invocación del
  reconciliador y el intento de una segunda transacción; no pueden satisfacerse fallando el gate
  mutante anterior;
- replay cuyo verifier falla conserva UNKNOWN;
- planner BLOCKED/ERROR conserva UNKNOWN y nunca acredita no persistencia;
- retry posterior a parcial/diferente no ejecuta healing ni DML y se clasifica por su nueva
  observación;
- no se filtran canaries ni receipt tentativo.

No convertir este subcorte en la campaña multithread, deadlock/timeout exhaustiva o de capacidad
del Corte 10.

### Puerta

~~~bash
./mvnw clean -Dit.test=LegalEditorialApplyFailureIT,LegalEditorialReconciliationIT,LegalManifestImportFailureIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): preserva incertidumbre editorial

## Subcorte 9E — Contratos y regresiones

Estado: pendiente.

### Objetivo

Congelar la compatibilidad de CLI, reportes e import sin agregar superficie operativa.

### Archivos

Modificar sólo si las pruebas nuevas lo requieren:

- `LegalEditorialCliExecutionStateTest`;
- `LegalEditorialReportTest`;
- `LegalEditorialReportWriterTest`;
- `LegalEditorialCliTest`;
- `LegalManifestCliExecutionStateTest`;
- `LegalManifestReportTest`;
- `LegalManifestReportWriterTest`;
- `LegalManifestImportReportTest`;
- `LegalManifestImportReportWriterTest`;
- `LegalImportTransactionStateTest`.

No modificar el formato público si las expectativas actuales ya expresan el contrato aprobado.

### Escenarios

- UNKNOWN conserva identidad validada del input, `operationId`, SHA y readiness esperado;
- `publicationUuid`, `appliedAt`, readiness autoritativo, `counts.state`, `counts.delta` y receipt
  DB permanecen nulos; `counts.release` validado permanece;
- receipt tentativo envenenado nunca llega al JSON;
- otro `operationId` o SHA con el mismo postestado exacto sigue ALREADY_APPLIED sin acreditar
  autoría;
- stdout ausente/truncado no fabrica un envelope UNKNOWN y conserva exit 3 en la lógica unitaria;
- APPLIED/ALREADY_APPLIED conservan exit 0 y ERROR/UNKNOWN exit 3;
- import v2 y reportes v1/v2 permanecen byte-compatible.

La inyección de stdout truncado en un proceso JAR real permanece en Corte 10.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialCliExecutionStateTest,LegalEditorialReportTest,LegalEditorialReportWriterTest,LegalEditorialCliTest,LegalManifestCliExecutionStateTest,LegalManifestReportTest,LegalManifestReportWriterTest,LegalManifestImportReportTest,LegalManifestImportReportWriterTest,LegalImportTransactionStateTest test
./mvnw clean -Dit.test=LegalManifestImportFailureIT,LegalManifestImportProcessIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): conserva contratos al reconciliar commits

## Subcorte 9F — Puerta integral y cierre documental

Estado: pendiente.

### Objetivo

Cerrar Corte 9 con evidencia fresca, documentación alineada y ninguna expansión funcional.

### Documentos autorizados

Modificar:

- este plan;
- `docs/plans/2026-08-30-legal-editorial-commit-reconciliation-design.md`;
- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`.

No actualizar todavía runbooks, frontend ni documentos de integración pública; pertenecen al
Corte 11.

### Puerta final

~~~bash
./mvnw -Dtest=LegalEditorialTransactionStateTest,LegalEditorialCommitReconcilerTest,LegalEditorialApplyServiceReconciliationTest,LegalEditorialApplyServiceTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialRetireServiceTest,LegalEditorialApplyResultTest,LegalEditorialCliExecutionStateTest,LegalEditorialReportTest,LegalEditorialReportWriterTest,LegalEditorialCliTest,LegalManifestReportTest,LegalManifestReportWriterTest,LegalManifestImportReportTest,LegalManifestImportReportWriterTest,LegalTransactionCompletionStateTest,LegalImportTransactionStateTest,LegalManifestDatabaseGateTest test
./mvnw clean -Dit.test=LegalEditorialApplyFailureIT,LegalEditorialReconciliationIT,LegalEditorialRetireIT,LegalEditorialPlannerIT,LegalEditorialSplitMergeIT,LegalEditorialReplaceIT,LegalInitialPromotionIT,LegalInitialPromotionFailureIT,LegalEditorialReadinessIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialDatabaseIsolationIT,LegalManifestImportIT,LegalManifestImportFailureIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT,LegalManifestImportProcessIT verify
./mvnw test
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Registrar JDK, PostgreSQL, Flyway, conteos, duración, nueva transacción/mismo lock, cero DML
duplicado, regresiones, auditorías y ausencia de push/deploy. Los procesos reales, capacidad y
concurrencia exhaustiva permanecen en Corte 10.

Commit:

    docs(legal): cierra reconciliacion editorial

## Criterio de cierre

Corte 9 termina únicamente cuando 9A, 9B, 9C, 9D1, 9D2, 9E y 9F están acreditados en siete commits
locales separados; la pérdida de ACK converge dentro de la misma invocación cuando existe
postestado exacto; source exacto acredita no persistencia; toda evidencia parcial conserva
UNKNOWN; no se reintenta DML; import y reportes históricos continúan compatibles; y no hubo
cambios de V27, API, frontend, push o deploy.
