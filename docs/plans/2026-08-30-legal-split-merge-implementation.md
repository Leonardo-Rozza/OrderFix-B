# Corte 7 — Plan de implementación de split, merge y lotes disjuntos

Fecha: 2026-08-30

Estado: completado el 2026-08-30 — Subcortes 7A a 7E acreditados

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

Estado: completado el 2026-08-30.

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
  acredita en el writer instrumentado de 7A, mientras esta IT acredita el postestado conjunto;
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

### Evidencia de cierre 7C

- `LegalEditorialSplitMergeIT` es un fixture autónomo y test-only. Acredita en PostgreSQL fresco un
  split real `1→2`, un merge real `2→1` y una operación con ambos lotes disjuntos; congela las
  cardinalidades para que una futura edición no degrade silenciosamente los escenarios a `1→1`.
- El caso compuesto asigna IDs de lote en orden textual inverso al UUID mínimo de sus miembros y
  comprueba que el plan validado conserva el orden canónico por miembro. El orden efectivo de los
  sellos continúa cubierto por la instrumentación del writer de 7A.
- Los receipts exactos fueron `12/6/24/12/21/8/1` para split, `11/6/22/12/21/8/1` para merge y
  `12/6/24/12/21/8/2` para multibatch: versiones documentales, requisitos, transiciones target,
  transiciones de requisitos target, slots, punteros y lotes, respectivamente. Cada conteo se
  contrastó además con SQL independiente.
- Cabeceras, membresías, estados, historia completa, slots, punteros y timestamps coinciden con el
  postestado esperado. Los snapshots sellados permanecen inmutables y las seis tablas externas de
  aceptación/idempotencia conservan exactamente sus filas.
- Las tres operaciones se ejecutan con `current_user` igual al rol editorial restringido; el
  verificador de privilegios pasa antes y después sin agregar grants. Readiness converge de
  `NOT_READY` a `READY`.
- Java 21 (Corretto 21.0.10): puerta unitaria focal de 61 tests y suite completa de 2.629 tests,
  todas con 0 fallos, 0 errores y 0 omitidos. PostgreSQL 16.14/Flyway V27 ejecutó 23 integraciones
  focales —7 de privilegios, 13 de REPLACE y 3 de split/merge— sin fallos; el control de secretos
  del JAR también pasó.
- Tres revisiones adversariales finales cerraron sin hallazgos P0–P2. Los dos P2 de precisión
  detectados —nombre que sobreacreditaba atomicidad y cardinalidades no congeladas— se corrigieron
  antes del commit.
- No se modificaron producción, soporte compartido, V27/V28, schemas, grants, rol, endpoints, JPA
  ni frontend; tampoco hubo push o deploy. Replay, corrupción y rollback entre sellos permanecen
  deliberadamente para 7D.

## Subcorte 7D — Replay, corrupción y rollback multibatch

Estado: completado el 2026-08-30.

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

### Evidencia de cierre 7D

- El replay compuesto se ejecuta primero con el mismo plan y después con otro `operationId` y SHA
  de plan, pero con idénticos lotes y miembros. Ambos intentos devuelven `ALREADY_APPLIED`, el
  mismo receipt y `appliedAt`; las 19 tablas editoriales y las 10 secuencias permanecen idénticas
  después de cada llamada.
- Ocho fixtures PostgreSQL independientes corrompen lote, miembro, transición o slot, tanto por
  exceso como por ausencia. La siembra usa `session_replication_role=replica` sólo desde el owner
  test-only e IDs explícitos para no consumir secuencias. Todos los replays terminan
  `BLOCKED/SOURCE_FINGERPRINT_MISMATCH` en `database/source`, sin healing ni DML; batch, miembro e
  historia conservan readiness `READY`, mientras los slots corruptos quedan `NOT_READY`.
- La inyección JDBC test-only comparte el datasource y la transacción del rol editorial
  restringido con schema y privilege verifiers reales. Deja completar el primer sello y acredita
  dentro de esa transacción sus tres efectos V27; antes del segundo observa el lote aún `ABIERTO`,
  sus predecesores `VIGENTE`, sus sucesoras `PUBLICADA` y cero transiciones de reemplazo, y recién
  entonces falla.
- El fallo entre sellos devuelve `ERROR`, `persisted=false` y
  `EDITORIAL_OBSERVATION_FAILED` en `database/observation`. Tras el rollback, las 19 tablas vuelven
  fila por fila al baseline, no queda ningún lote y el target continúa `NOT_READY`. PostgreSQL
  conserva únicamente los huecos exactos esperados: 3 IDs de anteriores, 3 de sucesoras, 6 de
  transiciones documentales y 6 de requisitos; las otras seis secuencias no cambian. El cálculo
  cubre también un baseline con `is_called=false`.
- Java 21 (Corretto 21.0.10): puerta unitaria focal de 39 tests y suite completa de 2.629 tests,
  todas con 0 fallos, 0 errores y 0 omitidos. PostgreSQL 16.14/Flyway V27 ejecutó 33 integraciones
  focales —7 de concurrencia, 13 de REPLACE y 13 de split/merge— sin fallos; el control de secretos
  del JAR pasó y `git diff --check` quedó limpio.
- No se modificaron producción, soporte compartido, V27/V28, schemas, grants, rol, CLI, reportes,
  endpoints, JPA ni frontend; tampoco hubo push o deploy. La matriz amplia y el cierre documental
  definitivo permanecen deliberadamente para 7E.

## Subcorte 7E — Regresiones y cierre

Estado: completado el 2026-08-30.

### Objetivo

Cerrar Corte 7 con evidencia real, documentación alineada y ninguna expansión de superficie.

### Modificar

- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`;
- `docs/plans/2026-08-30-legal-split-merge-design.md`;
- este documento;
- `docs/plans/2026-08-29-legal-replace-cutover-implementation.md`, sólo con una nota de
  continuidad que preserve como histórico el cierre del Corte 6.

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

### Evidencia de cierre 7E

- La puerta unitaria focal ejecutó 134 tests de validator, scope guard, execution plan, planner,
  writer, post-verifier, apply, plan service, CLI y reportes: 0 fallos, 0 errores y 0 omitidos.
- La matriz final seleccionada volvió a ejecutar los 2.629 tests unitarios y 82 integraciones:
  13 split/merge, 13 REPLACE, 7 PROMOTE, 4 fallos PROMOTE, 9 readiness, 7 privilegios, 2 de
  aislamiento DB, 7 de import, 3 de aislamiento CLI, 10 de procesos CLI y 7 de concurrencia V27.
  Todo pasó con Java 21 (Corretto 21.0.10), PostgreSQL 16.14 y Flyway V27; el control de secretos
  del JAR también quedó verde.
- `./mvnw test` se ejecutó nuevamente de forma independiente y confirmó los 2.629 unitarios sin
  fallos, errores ni omitidos. `sh -n scripts/legal-manifest-editor.sh` y `git diff --check`
  quedaron limpios.
- La regresión conserva `1→N`, `N→1` y lotes disjuntos; `N→M` sigue fail-closed. Replay no mutante,
  corrupción sin healing, rollback multibatch, PROMOTE, REPLACE `1→1`, readiness, privilegios,
  import, aislamiento y procesos mantienen los contratos acreditados en 7A–7D y Corte 6.
- 7E modifica sólo documentación. El Corte 7 sí incluye los cambios productivos acotados de 7A y
  7B, pero nunca modificó V27/V28, schemas externos, grants, rol, endpoints, JPA, frontend ni
  contenido legal. No hubo deploy ni push.
- La rama backend es `codex/lanzamiento-publico-backend`; el frontend permanece en
  `codex/frontend-refactor-checkpoint` sin cambios versionados y conserva únicamente sus dos
  directorios no versionados preexistentes. Las revisiones adversariales de alcance, pruebas y
  documentación cerraron sin hallazgos P0–P2 pendientes.

Commits locales atómicos del Corte 7:

1. `cf0b281` — `feat(legal): prepara lotes split y merge` (7A);
2. `e177590` — `feat(legal): habilita reemplazos split y merge` (7B);
3. `a780482` — `test(legal): acredita split y merge en postgresql` (7C);
4. `8b2c96f` — `test(legal): acredita atomicidad multibatch` (7D);
5. `docs(legal): cierra reemplazos split y merge` (7E, este cierre).

## Criterio de cierre

Corte 7 termina únicamente cuando 7A–7E están en commits locales separados, `N→M` sigue bloqueado,
los tres escenarios PostgreSQL frescos y su replay/rollback son exactos, las regresiones quedan
verdes, frontend permanece intacto y no hubo push ni deploy.

Estado del criterio: satisfecho el 2026-08-30. Corte 7 queda completado; los Cortes 8 a 11 y la
Fase 2.3C permanecen abiertos.
