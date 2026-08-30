# Corte 7 — Plan de implementación de split, merge y lotes disjuntos

Fecha: 2026-08-30

Estado: en ejecución — diseño aprobado; Subcortes 7A y 7B completados, 7C pendiente

Diseño aprobado:

- `docs/plans/2026-08-30-legal-split-merge-design.md`;
- commit local `ebeb487`;
- rama `codex/lanzamiento-publico-backend`.

Este plan divide el Corte 7 en subcortes pequeños. Cada uno empieza por pruebas, termina con diff
limpio y se registra en un commit local atómico. No se hace push ni deploy.

## Invariantes comunes

1. Admitir `1→1`, `1→N`, `N→1` y múltiples lotes actuales disjuntos.
2. Rechazar `N→M` cuando ambos lados superan uno.
3. Mantener predecesores y sucesoras no vacíos.
4. Preservar en el validator forma, límites, normalización, disjunción y autociclo; tipo, locale,
   contextos y pertenencia real continúan acreditándose en el planner contra el snapshot DB.
5. Ordenar el delta actual, comandos y sellos por UUID mínimo de miembro y luego por
   `replacementBatchId`; miembros por UUID. Preservar por `batchId` la historia y el fingerprint.
6. Insertar todas las cabeceras y membresías antes del primer sello.
7. Prebloquear globalmente publicaciones, líneas y versiones en orden UUID.
8. V27 continúa como único dueño de transiciones y slots derivados.
9. No existe commit parcial por lote; toda la operación usa un gate, transacción y timestamp.
10. Replay compara postestado exacto y no ejecuta DML ni avanza secuencias.
11. `N→M` y otra operación bloquean antes de credenciales/JDBC.
12. Incompatibilidades de tipo, locale, contextos o estado real bloquean antes de DML.
13. No modificar V27/V28, schemas JSON, grants, rol, CLI, reportes, endpoints, JPA ni frontend.
14. Conservar byte a byte v1/v2 y la semántica v3 ya acreditada.
15. `UNKNOWN` y completion-state mantienen el contrato del Corte 6.

## Subcorte 7A — Soporte interno y orden canónico

Estado: completado el 2026-08-30.

### Objetivo

Preparar el modelo y writer para lotes compuestos sin abrir todavía la capacidad en
`LegalEditorialReplaceScopeGuard`.

### Modificar

- `LegalEditorialExecutionPlan`;
- `LegalDocumentReplacementWriter`;
- `LegalEditorialExecutionPlanTest`;
- `LegalDocumentReplacementWriterTest`;
- este documento para registrar evidencia real.

### Pasos

1. Escribir una prueba roja que demuestre que el execution plan debe ordenar lotes por UUID mínimo
   de miembro, aunque el orden por `batchId` sea el inverso.
2. Congelar desempate por `batchId`, comparación UUID textual y orden UUID de
   predecesores/sucesoras, sin cambiar lotes históricos ni fingerprint.
3. Reemplazar la defensa writer 0..1/1→1 por una defensa interna que admita múltiples lotes y exija
   por lote lados no vacíos y `predecessors == 1 || successors == 1`.
4. Probar `0→1`, `1→0` y `N→M` contra un boundary mockeado y exigir cero interacciones JDBC;
   mantener además rechazo de overlap, autociclo y forma de comandos incompatible.
5. Probar `1→N`, `N→1` y dos lotes disjuntos directos contra el writer.
6. Acreditar que todas las cabeceras se insertan antes de miembros, todos los predecesores antes de
   sucesoras y todas las membresías antes del primer sello.
7. Acreditar sellos secuenciales en orden canónico y cardinalidad exacta.
8. Mantener el guard externo del Corte 6 cerrado: plan/apply/CLI aún bloquean split, merge y
   múltiples lotes hasta 7B.
9. Ejecutar una regresión focal de scope guard, planner, plan service y apply service que demuestre
   que split, merge y multibatch todavía bloquean antes de gate/JDBC durante 7A.
10. Ejecutar regresión writer `1→1`, requirements-only y validaciones de sesión compartida.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialExecutionPlanTest,LegalDocumentReplacementWriterTest,LegalEditorialReplaceScopeGuardTest,LegalEditorialPlannerCoreTest,LegalEditorialPlanServiceTest,LegalEditorialApplyServiceReplaceTest test
./mvnw test
git diff --check
git status --short
~~~

Commit:

    feat(legal): prepara lotes split y merge

### Evidencia de cierre 7A

- `LegalEditorialExecutionPlan` conserva los lotes históricos por `batchId` y ordena sólo el delta
  actual y sus comandos por el UUID textual mínimo de todos sus miembros, con `batchId` como
  desempate. La regresión adversarial distingue este contrato de `UUID.compareTo` y mantiene
  predecesores y sucesoras en orden canónico.
- `LegalDocumentReplacementWriter` admite múltiples lotes `1→1`, `1→N` y `N→1`. Lados vacíos y
  `N→M` bloquean con `REPLACEMENT_MAPPING_INVALID` antes de cualquier interacción JDBC.
- El recorder JDBC acredita una sola secuencia global: todas las cabeceras, todas las predecesoras,
  todas las sucesoras y recién entonces los sellos en orden canónico. Una cardinalidad inesperada
  en el segundo sello detiene el writer exactamente allí.
- El overlap entre lotes actuales sigue rechazado en los boundaries de postestado y comandos. El
  guard externo no se modificó: split, merge y multibatch todavía bloquean antes de gate/JDBC hasta
  el Subcorte 7B.
- Java 21 (Corretto 21.0.10): puerta focal de 76 tests y suite unitaria completa de 2.621 tests,
  todas con 0 fallos, 0 errores y 0 omitidos. `git diff --check` quedó limpio.
- Dos revisiones adversariales finales cerraron sin hallazgos P0–P2. No se modificaron V27/V28,
  schemas, grants, rol, CLI, reportes, endpoints, JPA ni frontend; tampoco hubo push o deploy.
  PostgreSQL fresco y la semántica integral de split/merge permanecen deliberadamente para 7C.

## Subcorte 7B — Apertura controlada y planner

Estado: completado el 2026-08-30.

### Objetivo

Abrir `plan-replace`/`apply-replace` para el alcance aprobado y mantener `N→M` fail-closed antes de
JDBC.

### Modificar

- `LegalEditorialReplaceScopeGuard` y su test;
- `LegalEditorialPlannerCoreTest`;
- tests de plan/apply/preflight que congelan la frontera antes de JDBC;
- `LegalEditorialReplaceIT` para retirar la expectativa obsoleta de rechazo general.

`LegalEditorialPlannerCore` sólo se modifica si una prueba positiva o negativa revela una brecha;
su loop, mapping y postestado ya son multi-lote/multi-miembro.

### Pasos

1. Guard rojo: aceptar cero, `1→1`, `1→N`, `N→1` y múltiples lotes disjuntos.
2. Guard rojo: rechazar otra operación y `N→M` con
   `BLOCKED/REPLACEMENT_MAPPING_INVALID` antes del gate/JDBC.
3. Probar planner source-state y post-state para split, merge y varios lotes.
4. Probar mapping inválido por tipo, locale, contextos solapados o cobertura desigual; estas
   verificaciones leen snapshot pero no ejecutan DML.
5. Conservar rechazo de overlap/autociclo/duplicados en el validator opaco.
6. Probar replay compuesto con todos los lotes y timestamp uniforme.
7. Ejecutar regresiones `1→1`, requirements-only, PROMOTE y CLI/report sin cambios de contrato.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialReplaceScopeGuardTest,LegalEditorialPlanValidatorTest,LegalEditorialPlannerCoreTest,LegalEditorialPlanServiceTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialPreflightTest,LegalEditorialCliTest,LegalEditorialReportTest test
./mvnw test
git diff --check
git status --short
~~~

Commit:

    feat(legal): habilita reemplazos split y merge

### Evidencia de cierre 7B

- `LegalEditorialReplaceScopeGuard` admite cero, `1→1`, `1→N`, `N→1` y múltiples lotes. Lados
  vacíos, `N→M` y otra operación conservan el rechazo tipado
  `BLOCKED/REPLACEMENT_MAPPING_INVALID` en `documentReplacementBatches`.
- Plan, apply y preflight usan el guard real y acreditan que `N→M` termina antes de resolver el
  entorno, adquirir el gate, leer `transaction_timestamp` o interactuar con JDBC. La integración
  PostgreSQL de REPLACE valida además que un `2→2` no modifica filas ni avanza secuencias.
- El planner acredita en una sola operación un split `1→2` y un merge `2→1`, con batch IDs
  adversariales: orden canónico, seis estados, nueve transiciones, tres comandos directos
  `BORRADOR→PUBLICADA`, efectos V27 completos y un único timestamp.
- El replay compuesto conserva lotes, historia, timestamp y efectos V27 exactos, devuelve
  `changeRequired=false` y emite cero comandos. La prueba roja reveló que la evidencia histórica
  de slots eliminados se perdía al releer el postestado; `LegalEditorialPlannerCore` ahora la
  reconstruye desde tipo, locale y contextos inmutables del predecesor. En source-state la
  proyección se valida contra los slots activos antes de DML; en replay se exigen lote sellado,
  membresías y transiciones exactas, además de la ausencia de slots de las predecesoras.
- Tipo o locale desigual en predecesoras o sucesoras, contextos solapados en cualquiera de ambos
  lados y cobertura desigual bloquean después de leer el snapshot y antes de readiness o DML. El
  validator opaco acredita además un multibatch JSON disjunto válido, su orden por miembro mínimo,
  el autociclo documental y la reutilización de miembros entre lotes; regresiones `1→1`,
  requirements-only, PROMOTE, CLI y reportes conservan sus contratos.
- Java 21 (Corretto 21.0.10): puerta focal ampliada de 130 tests y suite unitaria completa de 2.629
  tests, todas con 0 fallos, 0 errores y 0 omitidos. PostgreSQL 16.14/Flyway V27 ejecutó las 13
  integraciones `LegalEditorialReplaceIT` sin fallos; `git diff --check` quedó limpio.
- La primera ejecución completa encontró un bloqueo transitorio fail-closed
  `EDITORIAL_PLAN_FILE_CHANGED` sobre un directorio temporal en una prueba del validator. La misma
  puerta, sin cambio de código ni relajación de seguridad, pasó completa al repetirla y confirmó
  los conteos anteriores.
- Tres revisiones adversariales y sus reauditorías cerraron sin hallazgos P0–P2. Los desvíos P2
  de cobertura y documentación detectados durante la revisión se corrigieron antes del commit.
- No se modificaron V27/V28, schemas, grants, rol, endpoints, JPA ni frontend; tampoco hubo push o
  deploy. Los escenarios PostgreSQL positivos de split, merge y multibatch permanecen
  deliberadamente para 7C.

## Subcorte 7C — PostgreSQL fresco

Estado: pendiente.

### Objetivo

Acreditar ejecuciones frescas split, merge y multibatch sobre PostgreSQL 16/Flyway V27 con el rol
editorial restringido exacto.

### Crear

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialSplitMergeIT.java`.

### Modificar

- fixtures REPLACE test-only estrictamente necesarias;
- `LegalManifestPersistenceITSupport` sólo si hace falta compartir ensamblado sin relajar controles.

### Escenarios

- split `1→2` con contextos particionados y target `READY`;
- merge `2→1` con cobertura exacta y target `READY`;
- un split y un merge disjuntos en una sola operación;
- batch IDs invertidos respecto del mínimo miembro como fixture adversarial; el orden de sellado se
  acredita en el writer instrumentado de 7A, mientras esta IT acredita postestado y atomicidad;
- estados, historia, membresías, slots, punteros, receipt y timestamp exactos;
- snapshots sellados y tablas de aceptación/idempotencia sin cambios;
- rol restringido sin grant nuevo.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialPlannerCoreTest,LegalDocumentReplacementWriterTest,LegalEditorialPostStateVerifierTest,LegalEditorialApplyServiceReplaceTest test
./mvnw -Dit.test=LegalEditorialSplitMergeIT,LegalEditorialReplaceIT,LegalEditorialPrivilegeVerifierIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): acredita split y merge en postgresql

## Subcorte 7D — Replay, corrupción y rollback multibatch

Estado: pendiente.

### Objetivo

Demostrar que el postestado compuesto es idempotente, exacto y completamente atómico ante fallos
entre sellos.

### Modificar

- `LegalEditorialSplitMergeIT`;
- tests del verifier/writer únicamente si la integración revela una brecha;
- soporte test-only de inyección JDBC, sin hooks productivos.

### Escenarios

- replay exacto y replay con distinto `operationId`/SHA externo;
- cero cambios en filas y secuencias durante replay/BLOCKED;
- lote, miembro, transición o slot extra/faltante sin healing;
- fallo al sellar el lote `k` después de efectos V27 de `k-1` y antes de los restantes;
- rollback exacto de cabeceras, membresías, estados, historia, slots y punteros de toda la operación;
- post-verifier, constraints, readiness y commit incierto como regresiones del Corte 6.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialPostStateVerifierTest,LegalDocumentReplacementWriterTest,LegalEditorialApplyServiceReplaceTest test
./mvnw -Dit.test=LegalEditorialSplitMergeIT,LegalEditorialReplaceIT,LegalConcurrencyIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): acredita atomicidad multibatch

## Subcorte 7E — Regresiones y cierre

Estado: pendiente.

### Objetivo

Cerrar Corte 7 con evidencia real, documentación alineada y ninguna expansión de superficie.

### Modificar

- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`;
- este documento.

Los documentos del Corte 6 permanecen como registro histórico; sólo agregar una nota de continuidad
si evita una lectura engañosa.

### Puerta final

~~~bash
./mvnw -Dtest=LegalEditorialPlanValidatorTest,LegalEditorialReplaceScopeGuardTest,LegalEditorialExecutionPlanTest,LegalEditorialPlannerCoreTest,LegalDocumentReplacementWriterTest,LegalEditorialPostStateVerifierTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialPlanServiceTest,LegalEditorialCliTest,LegalEditorialReportTest test
./mvnw -Dit.test=LegalEditorialSplitMergeIT,LegalEditorialReplaceIT,LegalInitialPromotionIT,LegalInitialPromotionFailureIT,LegalEditorialReadinessIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialDatabaseIsolationIT,LegalManifestImportIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT,LegalConcurrencyIT verify
./mvnw test
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Registrar Java/PostgreSQL/Flyway, conteos reales, revisiones adversariales, ramas y ausencia de push.
Capacidad, concurrencia multithread del executor y matriz completa de procesos permanecen en Corte
10; Corte 7 no cierra 2.3C ni habilita producción pública.

Commit:

    docs(legal): cierra reemplazos split y merge

## Criterio de cierre

Corte 7 termina únicamente cuando 7A–7E están en commits locales separados, `N→M` sigue bloqueado,
los tres escenarios PostgreSQL frescos y su replay/rollback son exactos, las regresiones quedan
verdes, frontend permanece intacto y no hubo push ni deploy.
