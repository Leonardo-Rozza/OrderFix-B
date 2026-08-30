# Corte 6 — Plan de implementación del cutover REPLACE uno a uno

Fecha: 2026-08-29

Estado: en ejecución — Subcortes 6A, 6B y 6C completados

Diseño aprobado:

- `docs/plans/2026-08-29-legal-replace-cutover-design.md`;
- commit local `a7f94df`;
- rama `codex/lanzamiento-publico-backend`.

Este documento descompone el Corte 6 de la Fase 2.3C en subcortes pequeños. Cada subcorte comienza
por sus pruebas, termina con diff limpio y se registra en un commit local atómico. No se hace push.

## Invariantes comunes

1. No modificar V27, schema del manifest, grants, endpoints, JPA, frontend ni contenido legal.
2. Preservar byte a byte reportes v1/v2 y la semántica `PROMOTE` del reporte v3.
3. Validar bundle, plan, alcance y confirmaciones antes de resolver credenciales o abrir JDBC.
4. El Corte 6 acepta cero o un lote; si existe, tiene exactamente una predecesora y una sucesora.
5. El advisory lock, datasource, transaction manager y sesión JDBC son los mismos ya acreditados.
6. `observedAt` es el timestamp de la observación actual; `expectedAppliedAt` es el instante
   histórico uniforme del cutover. Sólo coinciden obligatoriamente en una ejecución fresca.
7. Los writers ejecutan DML; el verificador común acredita postestado y construye receipts.
8. Después de `SET CONSTRAINTS ALL IMMEDIATE` sólo se ejecuta `ReadinessCore` y no hay más DML.
9. `ALREADY_APPLIED` y `BLOCKED` previos a DML no avanzan secuencias.
10. `UNKNOWN` conserva identidad input-safe, pero nunca metadata tentativa derivada de DB.

## Subcorte 6A — Capacidad, tiempo e historia esperada

Estado: completado el 2026-08-29.

### Objetivo

Impedir que plan, apply o replay adelanten split/merge, separar el instante actual del histórico y
transportar la historia completa que después acreditará replay.

### Crear

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReplaceScopeGuard.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReplaceScopeGuardTest.java`.

### Modificar

- `LegalEditorialExecutionPlan`;
- `LegalEditorialPlannerCore`;
- `LegalEditorialPlanService`;
- `LegalEditorialDatabaseConfiguration`;
- `LegalManifestPersistenceITSupport` y ensamblados directos del plan service;
- `LegalEditorialPrivilegeVerifierIT` cuando su ensamblado directo cambie;
- tests de execution plan, planner y plan service.

### Pasos

1. Escribir tests para cero lotes, un lote 1/1, más de un lote, 1/N y N/1.
2. Implementar un guard puro sobre `ValidatedEditorialPlan` que devuelva el issue estable
   `REPLACEMENT_MAPPING_INVALID`.
3. Inyectarlo en `LegalEditorialPlanService` antes de abrir su gate y conservar una defensa dentro
   de `PlannerCore`.
4. Agregar `expectedAppliedAt` a `LegalEditorialExecutionPlan` sin reutilizar `observedAt`.
5. En SOURCE_STATE usar el único transaction timestamp para ambos campos.
6. En POST_STATE conservar `observedAt` actual e inferir `expectedAppliedAt` de evidencia uniforme.
7. Ampliar `ExpectedPostState` para transportar la historia completa de transiciones de todas las
   versiones clasificadas, separada de `MutationCommands` y `V27TriggerEffects`.
8. Hacer que `PlannerCore` copie la prehistoria exacta del snapshot y agregue sólo el delta directo
   y derivado esperado; una transición extra vuelve inválido el postestado.
9. Probar replay con `observedAt != expectedAppliedAt`, incluido un plan sin lote y una versión
   reutilizada con historia previa.
10. Probar que historia extra, faltante o reordenada de forma no contractual no acredita replay.
11. Ejecutar regresión PROMOTE/RETIRE del planner.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialReplaceScopeGuardTest,LegalEditorialExecutionPlanTest,LegalEditorialPlannerCoreTest,LegalEditorialPlanServiceTest test
./mvnw -Dit.test=LegalEditorialPlannerIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialDatabaseIsolationIT verify
git diff --check
git status --short
~~~

Commit:

    fix(legal): delimita cutover editorial uno a uno

### Evidencia de cierre 6A

- `LegalEditorialReplaceScopeGuard` admite cero lotes o uno 1→1 y bloquea múltiples lotes,
  split y merge con `BLOCKED/REPLACEMENT_MAPPING_INVALID` antes del gate read-only, del timestamp
  y de cualquier lectura JDBC. `PlannerCore` conserva la misma defensa antes de acreditar estado.
- `LegalEditorialExecutionPlan` separa `observedAt` de `expectedAppliedAt`; una mutación fresca
  exige igualdad y un replay conserva el instante histórico, siempre anterior o igual al actual.
- El postestado transporta la prehistoria documental y de requisitos separada del delta. Cada
  cadena completa comienza en `BORRADOR`; comandos, efectos V27 y conteos continúan describiendo
  únicamente el cutover, nunca la historia previa.
- Java 21 (Corretto 21.0.10): puerta unitaria focal de 54 tests, 0 fallos, 0 errores y 0 omitidos.
- Puerta `verify`: suite unitaria backend completa de 1.049 tests y 28 tests de integración sobre
  PostgreSQL 16/Flyway V27, todos sin fallos, errores ni omitidos. Incluye planner, privilegios,
  aislamiento, promoción inicial y rollback tardío.
- El fixture del planner resuelve únicamente el padre real de `@TempDir` para que el test generado
  no herede el alias `/var` de macOS; el reader confinado productivo no fue relajado.
- Tres revisiones estáticas independientes cerraron sin hallazgos P0–P2. No se modificaron V27,
  grants, endpoints, JPA, frontend, contenido legal ni despliegue; no hubo push.

## Subcorte 6B — Plan REPLACE y contrato CLI

Estado: completado el 2026-08-29.

### Objetivo

Exponer `plan-replace` sin escritura, con segunda ruta, cuatro confirmaciones y reporte v3 exacto.

### Crear

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialPlanConfirmation.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialPlanConfirmationTest.java`.

### Modificar

- `LegalEditorialArguments`;
- `LegalEditorialCliExecutionState`;
- `LegalEditorialReport` y `LegalEditorialReportWriter`;
- `LegalManifestCli`;
- tests de argumentos, estado, preflight, CLI, report y procesos.

### Pasos

1. Congelar parser exacto de siete tokens: comando más manifest, plan y cuatro confirmaciones.
2. Rechazar duplicados, extras, aliases, argumentos separados y valores no canónicos.
3. Inyectar `LegalEditorialPlanValidator` en CLI y validar el plan después del bundle.
4. Comparar tipo `REPLACE`, operationId y SHA antes de resolver el entorno.
5. Ejecutar `LegalEditorialReplaceScopeGuard` antes de entorno/Spring/JDBC.
6. Invocar `LegalEditorialPlanService.planReplace` sólo después de todos los preflights.
7. Retener de forma monotónica `ValidatedEditorialPlan`, operationId y SHA.
8. Ampliar la matriz v3:
   - identidad del plan presente después de confirmación;
   - metadata de observación sólo en `APPLICABLE`;
   - fallos sin metadata DB;
   - `operationType=REPLACE`.
9. Mantener `plan-promote`, `apply-promote` y v1/v2 byte-compatible.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialArgumentsTest,LegalEditorialConfirmationTest,LegalEditorialPlanConfirmationTest,LegalEditorialCliExecutionStateTest,LegalEditorialPreflightTest,LegalEditorialCliTest,LegalEditorialReportTest,LegalEditorialReportWriterTest,LegalManifestCliTest test
./mvnw -Dit.test=LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
git diff --check
git status --short
~~~

Commit:

    feat(legal): expone plan de cutover editorial

### Evidencia de cierre 6B

- `plan-replace` es estrictamente read-only y exige exactamente siete tokens: comando, dos rutas
  y cuatro confirmaciones literales. El parser acepta cualquier orden de los seis argumentos
  nombrados y rechaza duplicados, extras, aliases, forma separada, UUID no canónico y SHA-256 que
  no sea lowercase de 64 caracteres.
- El preflight acredita bundle, plan, confirmaciones, binding target plan↔bundle y alcance 1→1
  antes de resolver entorno, abrir Spring o tocar JDBC. Una discrepancia de target se bloquea en
  `cli/editorial/plan-binding`; la defensa equivalente del planner permanece en profundidad.
- La CLI invoca únicamente `LegalEditorialPlanService.planReplace`. No se agregó `apply-replace`,
  DML ni otra ruta mutante. El estado conserva la identidad del plan sólo después de confirmar
  tipo, operationId y SHA, y `persisted` permanece siempre en `false` para este comando.
- El reporte v3 emite `operationType=REPLACE`. `APPLICABLE` contiene observación y delta; los
  fallos posteriores a la confirmación conservan sólo la identidad input-safe del plan y dejan
  nulos UUID, observación, readiness y conteos derivados de base. PROMOTE y los reportes v1/v2
  conservaron su contrato y sus bytes acreditados.
- TDD: el primer focal falló por los símbolos todavía ausentes; la prueba adicional de binding
  falló porque alcanzaba el entorno antes del fix. Puerta focal final en Java 21: 885 tests, 0
  fallos, 0 errores y 0 omitidos.
- JAR empaquetado y aislamiento: suite unitaria completa de 1.810 tests y 12 integraciones
  seleccionadas, todas verdes. `LegalManifestCliProcessIT` cubre tanto el rechazo plan↔bundle
  como un plan REPLACE 1→1 válido que supera el guard y se detiene de forma segura en el límite de
  entorno, sin filtrar rutas ni secretos.
- Regresión integral final: 1.810 tests unitarios y 169 tests de integración sobre H2,
  PostgreSQL 16/Flyway V27 y procesos empaquetados, sin fallos, errores ni omitidos. También pasó
  el control de ausencia de propiedades secretas en los JAR.
- La auditoría inicial detectó los huecos de binding temprano y proceso 1→1; ambos se corrigieron
  antes del commit. Tres reauditorías independientes cerraron sin hallazgos P0–P2. No se
  modificaron V27, grants, endpoints, JPA, frontend, contenido legal ni despliegue; no hubo push.

## Subcorte 6C — Writers y postestado común

Estado: completado el 2026-08-29.

### Objetivo

Separar coordinación, DML y verificación sin cambiar el comportamiento acreditado de PROMOTE.

### Crear

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialMutationWriter.java`;
- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPostStateVerifier.java`;
- tests unitarios de ambos componentes.

### Modificar

- `LegalInitialPromotionCore`;
- `LegalEditorialApplyService`;
- `LegalEditorialDatabaseConfiguration`;
- `LegalEditorialApplyReceipt` sólo si hace falta cerrar invariantes de construcción;
- `LegalManifestPersistenceITSupport`, `LegalInitialPromotionFailureIT` y cualquier ensamblado
  directo del apply service;
- tests de promoción, apply, transaction boundary y failure mapping.

### Pasos

1. Escribir caracterización exacta del orden SQL y receipt PROMOTE actual.
2. Hacer que el writer PROMOTE ejecute sólo DML directo y no sea autoridad de replay.
3. Mover la comparación exacta y construcción de receipt al verificador común.
4. Definir conteos del receipt como totales de la proyección target; mantener delta en plan.
5. Verificar historia completa, estados, slots, punteros y lotes según `ExpectedPostState`.
6. Usar `expectedAppliedAt` para receipt fresco y replay; prohibir `max(historial)`.
7. Reordenar el coordinador: writer → verificador → constraints → readiness.
8. Confirmar que después de constraints sólo corre readiness.
9. Conservar completion-state y `UNKNOWN` existentes sin reclasificar excepciones.
10. Ejecutar regresión integral de primera promoción y replay.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialPostStateVerifierTest,LegalEditorialMutationWriterTest,LegalInitialPromotionCoreTest,LegalEditorialApplyServiceTest,LegalEditorialTransactionBoundaryTest,LegalEditorialApplyReceiptTest test
./mvnw -Dit.test=LegalInitialPromotionIT,LegalInitialPromotionFailureIT,LegalEditorialReadinessIT,LegalEditorialDatabaseIsolationIT verify
git diff --check
git status --short
~~~

Commit:

    refactor(legal): separa mutacion y postestado editorial

### Evidencia de cierre 6C

- `LegalInitialPromotionCore` implementa la nueva frontera `LegalEditorialMutationWriter` y quedó
  limitado a releer/bloquear el grafo y ejecutar los seis batches PROMOTE exactos. Ya no acredita
  replay, construye receipts, fuerza constraints ni consulta readiness.
- `LegalEditorialPostStateVerifier` es SELECT-only y quedó como única autoridad del receipt fresco
  y de replay. Compara membresía target, estados, historia completa (prehistoria más delta), slots,
  punteros y lotes, y usa siempre `expectedAppliedAt`; no infiere tiempo mediante
  `max(historial)`.
- El coordinador ejecuta `writer → verifier → SET CONSTRAINTS ALL IMMEDIATE → readiness`. Replay
  ejecuta sólo el verificador, y `applyPromote` rechaza fail-closed un plan de otro tipo antes de
  writer o verifier. PROMOTE conserva además la igualdad de sus siete conteos receipt↔readiness.
- Completion-state conserva el contrato previo: un commit confirmado devuelve su receipt y
  `UNKNOWN` nunca filtra receipt ni metadata tentativa. Un mismatch real detectado por el
  verificador, una constraint diferida o readiness no exacto revierten todo el delta.
- TDD: el primer focal rojo falló por las dos fronteras todavía ausentes. El focal final en Java
  21 ejecutó 40 tests, sin fallos, errores ni omitidos; congela orden, SQL y parámetros de los seis
  batches, replay select-only, historia previa separada del delta y guard PROMOTE.
- Puerta final `verify`: suite unitaria backend completa de 1.829 tests y 22 tests de integración
  sobre PostgreSQL 16/Flyway V27, todos sin fallos, errores ni omitidos. Incluye promoción,
  replay, rollback por corrupción observada, constraints, readiness y sesión JDBC única.
- Tres revisiones independientes cerraron sin hallazgos P0–P2 ejecutables en 6C. Antes de exponer
  `apply-replace`, 6D debe representar y probar lotes históricos de reemplazos encadenados; la
  membresía parcial RETIRE y la extracción del reader anidado permanecen fuera de este subcorte.
- No se modificaron V27, grants, endpoints, JPA, frontend, contenido legal ni despliegue; no hubo
  push.

## Subcorte 6D — Apply REPLACE uno a uno

### Objetivo

Ejecutar el delta REPLACE permitido y exponer `apply-replace` con la frontera transaccional común.

### Crear

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalDocumentReplacementWriter.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyServiceReplaceTest.java`;
- tests unitarios del writer si se separan del service test.

### Modificar

- `LegalEditorialApplyService`;
- `LegalEditorialDatabaseConfiguration`;
- `LegalEditorialPostStateVerifier`;
- parser, execution state, report, writer y CLI v3;
- tests del servicio, CLI y reporte.

### Pasos

1. Extender el verificador para acreditar lotes históricos además del lote nuevo y cubrir un
   reemplazo encadenado X→B→C sin tratar el lote X como extra.
2. Ejecutar `LegalEditorialReplaceScopeGuard` sobre `ValidatedEditorialPlan` antes de
   `executeMutable`; una entrada programática fuera de alcance no abre el gate ni JDBC.
3. Repetir la defensa 0..1/1→1 sobre execution plan antes del primer DML.
4. Releer y bloquear publicaciones, líneas y versiones en orden UUID.
5. Releer slots/punteros en orden PK bajo advisory lock, sin ampliar row-lock grants.
6. Revalidar source fingerprint y grafo exacto bajo locks.
7. Ejecutar el orden DML congelado y cardinalidad exacta por batch.
8. Eliminar todos los punteros observados por PK más `conjunto_id` esperado.
9. Crear/sellar el lote opcional sin duplicar efectos de triggers V27.
10. Ejecutar retiros, requisitos, rebinds y punteros target.
11. Verificar el postestado antes de constraints y readiness después.
12. Implementar replay con verifier, sin writer ni avance de secuencias.
13. Exponer `apply-replace`, exigir flag e incluir identidad input-safe en fallos/UNKNOWN.
14. Probar rollback en cada frontera tardía y ausencia de DML fuera de superficie.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialApplyServiceReplaceTest,LegalEditorialPostStateVerifierTest,LegalEditorialApplyServiceTest,LegalEditorialReportTest,LegalEditorialCliTest test
./mvnw -Dit.test=LegalEditorialPrivilegeVerifierIT,LegalEditorialDatabaseIsolationIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
git diff --check
git status --short
~~~

Commit:

    feat(legal): aplica cutover editorial uno a uno

## Subcorte 6E — Acreditación PostgreSQL y cierre

### Objetivo

Acreditar el flujo combinado, replay, rollback y regresiones sobre PostgreSQL 16 con el rol exacto.

### Crear

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReplaceIT.java`.

### Modificar

- `LegalManifestPersistenceITSupport` y fixtures sintéticas estrictamente necesarios;
- tests de aislamiento/proceso sólo si la evidencia del jar lo requiere;
- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`;
- este documento para registrar evidencia real.

### Escenarios mínimos

- lote 1→1 combinado con adición, gap recuperado, reuse/rebind y retiro;
- requisitos nuevos, reusados, reemplazados y retirados con audiencias completas;
- cutover sin lote y reemplazo-only de requisitos;
- target inicialmente `NOT_READY` que converge a `READY`;
- replay exacto con `observedAt != appliedAt`, cero DML y cero secuencias;
- replay con distinto `operationId`/SHA externo pero el mismo postestado, acreditado únicamente como
  estado existente y nunca como autoría histórica del plan;
- source fingerprint distinto, lote ajeno y postestado parcial;
- rechazo previo a JDBC de múltiples lotes, split y merge;
- rollback tras sello, punteros, verifier, constraints y readiness;
- frontera de commit sin falso éxito;
- snapshots sellados y superficies ajenas sin cambios;
- regresión import, readiness, privilege verifier, PROMOTE y host isolation.

### Puerta final

~~~bash
./mvnw -Dtest=LegalEditorialReplaceScopeGuardTest,LegalEditorialPostStateVerifierTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialArgumentsTest,LegalEditorialPlanConfirmationTest,LegalEditorialCliExecutionStateTest,LegalEditorialCliTest,LegalEditorialReportTest,LegalEditorialApplyServiceTest,LegalInitialPromotionCoreTest test
./mvnw -Dit.test=LegalEditorialReplaceIT,LegalInitialPromotionIT,LegalInitialPromotionFailureIT,LegalEditorialReadinessIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialDatabaseIsolationIT,LegalManifestImportIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
./mvnw test
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Registrar conteos reales, estado del branch y ausencia de push. La concurrencia multithread,
capacidad y matriz completa de procesos permanecen en el Corte 10.

Commit:

    test(legal): acredita cutover editorial uno a uno

## Cierre del Corte 6

Al terminar 6E:

- cambiar Corte 6 a completado con fecha y evidencia;
- reemplazar en la matriz maestra el único commit previsto por los cinco commits reales y sus
  puertas/evidencia;
- mantener Corte 7 pendiente;
- comprobar que frontend no fue modificado;
- revisar los cinco commits locales en orden;
- no hacer push ni deploy.
