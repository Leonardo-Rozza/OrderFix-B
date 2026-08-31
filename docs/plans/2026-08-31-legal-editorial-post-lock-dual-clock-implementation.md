# Corte 10A.1 — Plan de implementación del reloj editorial post-lock

Fecha: 2026-08-31

Estado: listo para ejecutar

Rama backend: `codex/lanzamiento-publico-backend`

Diseño aprobado:

- `docs/plans/2026-08-31-legal-editorial-post-lock-dual-clock-design.md`;
- commit local `246ef54`.

Plan padre:

- `docs/plans/2026-08-31-legal-editorial-concurrency-capacity-process-implementation.md`.

## Objetivo

Corregir la inversión temporal acreditada en dos operaciones editoriales concurrentes sin cambiar
V27 ni persistir un timestamp posterior al inicio de la transacción. El microcorte introduce una
frontera temporal tipada, construida después del advisory lock, y separa:

- `transactionAt`, usado por toda mutación fresca y por reglas restringidas por V27;
- `observedAt`, usado para observar, reconocer replay y reconciliar el postestado.

La implementación se hace con TDD, en grupos coherentes y revisables. No incluye migraciones,
roles, API, CLI, frontend, `pom.xml`, presupuestos, deploy ni push.

## Reglas de ejecución

1. Mantener JDK 21 y PostgreSQL 16 en todas las puertas.
2. Antes de producción, convertir la carrera PROMOTE en una reproducción roja determinista.
3. No usar sleeps, retries, repeticiones ni orden del scheduler como sincronización.
4. Leer ambos relojes únicamente dentro del gate, en la misma sesión y después del lock.
5. Conservar dos sentencias SQL exactas: `SELECT transaction_timestamp()` y
   `SELECT statement_timestamp()`.
6. No usar `Instant.now()`, `Clock`, `current_timestamp`, `now()` ni `clock_timestamp()` en los
   servicios editoriales.
7. No reemplazar globalmente `transaction_timestamp()`: import y dry-run quedan intactos y los
   fixtures pueden necesitarlo como timestamp de DML o probe.
8. Cada grupo estable termina con tests focales, `git diff --check`, revisión adversarial y commit
   local atómico. No hacer push.
9. Ejecutar Failsafe con `clean verify` para no reutilizar clases ni reportes anteriores.
10. Si el fix exige cambiar V27, grants, roles, contratos públicos, timeouts o `pom.xml`, detener
    el corte y volver a diseño.

## Tarea 1 — Congelar la reproducción roja determinista

Modificar:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialConcurrencyIT.java`.

Reutilizar, sin agregar hooks productivos:

- `LegalEditorialITFixture`;
- `LegalManifestPersistenceITSupport`;
- el harness package-private de apply;
- `LegalEditorialSchemaVerifier` real envuelto por un spy test-only.

Pasos:

1. Crear un harness local con dos transacciones editoriales y latches separados para A y B.
2. El spy debe ejecutar primero la verificación real y detener sólo sus primeras dos invocaciones
   antes del advisory lock.
3. Capturar el PID de cada sesión desde la misma transacción vinculada.
4. Consultar `pg_stat_activity` como owner por esos PIDs y exigir:
   - PIDs distintos;
   - `application_name` editorial esperado;
   - ambas sesiones `idle in transaction`;
   - `xact_start(A) < xact_start(B)`.
5. Liberar sólo B y exigir `APPLIED`, conservando A abierta.
6. Liberar A y, sobre la implementación actual, acreditar el rojo determinista
   `APPLIED + ERROR` causado por `expectedAppliedAt > observedAt`.
7. Liberar latches, cancelar futures y cerrar executors en `finally` con timeout acotado.

Puerta roja esperada:

~~~bash
./mvnw clean "-Dit.test=LegalEditorialConcurrencyIT#identicalPromotionsSerializeToAppliedAndAlreadyApplied" verify
~~~

No crear commit rojo. Registrar comando, JDK, resultado y excepción en la evidencia del plan
padre antes de tocar producción.

## Tarea 2 — Introducir la frontera temporal

Crear:

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialTimeBoundary.java`.
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialTimeBoundaryTest.java`.

Pruebas primero:

1. Aceptar dos instantes PostgreSQL con `transactionAt <= observedAt`.
2. Rechazar nulls, nanosegundos fuera de precisión de microsegundos e inversión temporal.
3. Aceptar igualdad exacta de microsegundo.

Implementación mínima:

1. Crear el record package-private e inmutable `LegalEditorialTimeBoundary`.
2. Validar instantes ya convertidos; no truncar ni corregir silenciosamente.
3. No agregar aún un constructor público ni una factory accesible fuera del package.

Puerta focal:

~~~bash
./mvnw -Dtest=LegalEditorialTimeBoundaryTest test
~~~

## Tarea 3 — Extender las invariantes del execution plan

Modificar:

- `LegalEditorialExecutionPlan.java`;
- `LegalEditorialPlannerCore.java`, sólo para completar el nuevo constructor con el mismo instante
  en ambos campos hasta la Tarea 4;
- `LegalEditorialExecutionPlanTest.java`;
- `LegalEditorialPlanResultTest.java`;
- `LegalEditorialPostStateVerifierTest.java`;
- `LegalEditorialRetirementWriterTest.java`;
- `LegalEditorialReplaceIT.java`;
- `LegalInitialPromotionFailureIT.java`;
- todos los helpers test-only que construyan el record directamente.

Pruebas primero:

1. Fresh válido con `transactionAt < observedAt` y
   `expectedAppliedAt == transactionAt`.
2. Replay causal válido con
   `transactionAt < expectedAppliedAt <= observedAt`.
3. Replay secuencial válido con
   `expectedAppliedAt < transactionAt <= observedAt`.
4. Aceptar las igualdades límite de microsegundo.
5. Rechazar `transactionAt > observedAt`.
6. Rechazar fresh cuyo `expectedAppliedAt != transactionAt`.
7. Seguir rechazando `expectedAppliedAt > observedAt`.
8. Seguir exigiendo que comandos, transiciones y postestado usen exactamente
   `expectedAppliedAt`.

Pasos:

1. Agregar `transactionAt` antes de `observedAt` en el record.
2. Validar precisión PostgreSQL y `transactionAt <= observedAt`.
3. En fresh exigir `expectedAppliedAt == transactionAt`.
4. En replay no imponer relación entre `transactionAt` y `expectedAppliedAt`; la única cota
   común es `expectedAppliedAt <= observedAt`.
5. Actualizar todos los constructores directos antes de ejecutar Maven: `-Dtest` también compila
   el resto de los tests.
6. Mientras el planner aún recibe un único instante, pasar ese mismo valor como `transactionAt`
   y `observedAt`; no publicar ni commitear una semántica temporal parcial.

Pruebas focales:

~~~bash
./mvnw -Dtest=LegalEditorialTimeBoundaryTest,LegalEditorialExecutionPlanTest,LegalEditorialPlanResultTest,LegalEditorialPostStateVerifierTest test
~~~

## Tarea 4 — Migrar planner, gate y consumidores como un único grupo verde

Modificar:

- `LegalEditorialPlannerCore.java`;
- `LegalEditorialPlannerCoreTest.java`;
- `LegalManifestDatabaseGate.java`;
- `LegalManifestDatabaseGateTest.java`;
- `LegalEditorialReadinessService.java`;
- `LegalEditorialPlanService.java`;
- `LegalEditorialApplyService.java`;
- `LegalEditorialCommitReconciler.java`;
- sus tests unitarios directos.

Pruebas primero:

1. Gate mutable, read-only y reconciliación acreditan el orden exacto:
   `preflight -> advisory lock -> restaurar lock timeout -> transactionAt -> observedAt -> callback`.
2. El gate hace una sola lectura de cada reloj por la misma instancia `JdbcTemplate`.
3. Si la primera lectura falla o devuelve null, no ejecutar la segunda ni el callback.
4. Si la segunda lectura falla, devuelve null o invierte la frontera, no invocar el callback.
5. `execute(...)` genérico no lee ninguno de los dos relojes.
6. Readiness consume sólo `observedAt`.
7. Planner usa `observedAt` para readiness, fingerprints, replay y postestado.
8. Planner usa `transactionAt` para DML fresco, fechas efectivas SOURCE y cutoff RETIRE SOURCE.
9. SOURCE bloquea si la fecha efectiva queda entre ambos instantes; POST/replay puede reconocer
   el estado usando `observedAt`.
10. Reconciliación clasifica POST_EXACT cuando
    `transactionAt < persistedAppliedAt <= observedAt`.
11. Un PROMOTE fresco con sello causal posterior a `transactionAt` queda BLOCKED y no construye
    comandos.
12. Un REPLACE encadenado cuyo source se activó después de `transactionAt` queda BLOCKED, aunque
    el snapshot observado sea estructuralmente válido.
13. Los mismos estados exactos en fase POST siguen reconociéndose como replay mediante
    `observedAt`.

Implementación:

1. Cambiar PROMOTE, REPLACE y RETIRE para recibir `LegalEditorialTimeBoundary`.
2. En el gate, cambiar sólo las tres entradas editoriales a un callback tipado
   `(TransactionStatus, LegalEditorialTimeBoundary)`.
3. Construir la frontera exclusivamente dentro de esas entradas y después de restaurar
   `graphLockTimeout`; no agregarla al helper compartido por import/dry-run.
4. Leer por el mismo `jdbc`, en este orden y forma literal:
   - `queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class)`;
   - `queryForObject("SELECT statement_timestamp()", OffsetDateTime.class)`.
5. Convertir `OffsetDateTime -> Instant` y validar en el record.
6. Eliminar de readiness, plan, apply y reconciliación sus lecturas locales de reloj.
7. Usar `observedAt` para snapshots, fingerprints, replay y postestado.
8. Usar `transactionAt` como `expectedAppliedAt` cuando la operación es fresca.
9. En SOURCE, conservar con `transactionAt`:
   - elegibilidad frente a `vigente_desde`;
   - cutover de RETIRE;
   - cualquier regla cuya escritura deba pasar V27.
10. Evaluar POST/replay antes de SOURCE. Para toda mutación fresca, calcular el piso causal
    autoritativo del estado visible usando, según la operación:
    - `PublicationEvidence.sealedAt` de source/target;
    - últimas transiciones y `stateChangedAt` de miembros relevantes;
    - `updatedAt` de punteros activos afectados;
    - sellos de lotes de reemplazo que establezcan causalidad no cubierta por transiciones.
11. Si `transactionAt` es anterior a ese piso causal, devolver un BLOCKED conservador con un issue
    existente y exigir retry; no construir comandos ni alcanzar DML.
12. Si una fecha efectiva cruza durante la espera, devolver bloqueo/retry conservador; no
   retrofechar una transición.
13. No conservar overloads `Instant` transitorios ni callbacks legacy como bypass.

Puerta focal:

~~~bash
./mvnw -Dtest=LegalEditorialTimeBoundaryTest,LegalManifestDatabaseGateTest,LegalEditorialExecutionPlanTest,LegalEditorialPlannerCoreTest,LegalEditorialReadinessCoreTest,LegalEditorialPlanServiceTest,LegalEditorialApplyServiceTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialRetireServiceTest,LegalEditorialApplyServiceReconciliationTest,LegalEditorialCommitReconcilerTest test
~~~

Las firmas de planner y consumidores están acopladas. Esta tarea se aplica completa en el mismo
working tree antes del primer Maven verde y no se divide mediante overloads temporales.

## Tarea 5 — Acreditar ligadura, writers y postcondiciones

Modificar:

- `LegalDocumentReplacementWriter.java`;
- `LegalEditorialRetirementWriter.java`;
- `LegalEditorialApplyService.java`;
- `LegalEditorialCommitReconciler.java`;
- tests de ambos writers;
- tests de apply/postestado afectados por la nueva forma del plan.

Pasos:

1. Apply debe acreditar, antes de `transactionState.planConstructed()` y antes de cualquier DML,
   que `plan.transactionAt/observedAt` coinciden exactamente con la frontera.
2. Un mismatch se mapea conservadoramente a
   `EDITORIAL_OBSERVATION_FAILED/database/observation` y no alcanza writer ni verifier.
3. Reconciliación debe validar ambos campos contra su propia frontera antes de clasificar SOURCE
   o POST; mismatch conserva `UNKNOWN`.
4. Reemplazar el guard redundante fresh
   `expectedAppliedAt == observedAt` por
   `expectedAppliedAt == transactionAt`.
5. Mantener todos los timestamps DML en `expectedAppliedAt`.
6. Mantener la relectura del postestado y su `observedAt` en el instante público de la frontera.
7. No modificar SQL, triggers ni schema salvo la propagación estrictamente necesaria del valor.
8. Acreditar PROMOTE, REPLACE y RETIRE con `transactionAt < observedAt`.

Puerta focal:

~~~bash
./mvnw -Dtest=LegalEditorialApplyServiceTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialApplyServiceReconciliationTest,LegalEditorialCommitReconcilerTest,LegalDocumentReplacementWriterTest,LegalEditorialRetirementWriterTest,LegalEditorialMutationWriterTest,LegalEditorialPostStateVerifierTest test
~~~

## Tarea 6 — Hacer verde la carrera y congelar el receipt

Volver a ejecutar la reproducción de la Tarea 1 y exigir:

1. B devuelve `APPLIED`.
2. A devuelve `ALREADY_APPLIED`.
3. Ambos resultados contienen el mismo receipt.
4. `receipt.appliedAt` coincide con el `transactionAt` de B, no con el `observedAt` público.
5. Existe una sola mutación y las secuencias avanzan exactamente una vez.
6. El snapshot owner coincide con el postestado esperado.
7. Un tercer replay no altera filas, secuencias ni receipt.

Puerta focal:

~~~bash
./mvnw clean -Dit.test=LegalEditorialConcurrencyIT verify
~~~

## Tarea 7 — Regresiones de fallos y operaciones editoriales

Ejecutar, sin cambiar POM ni interceptores:

~~~bash
./mvnw clean -Dit.test=LegalEditorialApplyFailureIT,LegalEditorialReconciliationIT,LegalInitialPromotionFailureIT,LegalInitialPromotionIT,LegalEditorialReplaceIT,LegalEditorialRetireIT,LegalEditorialSplitMergeIT verify
~~~

Comprobar especialmente que `LegalEditorialApplyFailureIT` siga interceptando la sentencia exacta
`SELECT transaction_timestamp()` mediante
`queryForObject(String, OffsetDateTime.class)`. No agregar `pg_catalog.`, punto y coma, whitespace
ni combinar ambas columnas. Si el interceptor mata esa primera lectura, la segunda no se ejecuta.
La lectura separada de `statement_timestamp()` no debe impedir la inyección de pérdida de sesión
ni convertir UNKNOWN en éxito o rollback falso.

## Tarea 8 — Auditoría temporal negativa

Ejecutar búsquedas focales y revisar cada match, sin reemplazos mecánicos:

~~~bash
rg -n "Instant\.now|Clock\.|clock_timestamp|current_timestamp|SELECT now\(\)" src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence
rg -n "SELECT transaction_timestamp\(\)|SELECT statement_timestamp\(\)" src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence
rg -n "expectedAppliedAt\(\).*observedAt|expectedAppliedAt.*equals.*observedAt" src/main/java src/test/java
rg -n "new LegalEditorialTimeBoundary" src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence
~~~

Criterios:

- `statement_timestamp()` aparece sólo en el constructor central de la frontera;
- el único `new LegalEditorialTimeBoundary` productivo aparece en el gate;
- el gate contiene ambos SELECT y `LegalManifestGraphWriter` conserva sólo el transaccional;
- servicios, planner y writers no leen relojes por su cuenta;
- import/dry-run conservan su reloj transaccional y no construyen la frontera;
- ningún writer persiste `observedAt` como tiempo de mutación fresca;
- no existe bypass del advisory lock ni fallback de JVM.

## Tarea 9 — Puertas ampliadas y evidencia

Puerta 10A ampliada:

~~~bash
./mvnw clean -Dit.test=LegalEditorialConcurrencyIT,LegalManifestImportConcurrencyIT,LegalManifestDryRunConcurrencyIT,LegalManifestImportIT,LegalManifestImportFailureIT,LegalEditorialPlannerIT,LegalEditorialReadinessIT,LegalInitialPromotionIT,LegalInitialPromotionFailureIT,LegalEditorialReplaceIT,LegalEditorialRetireIT,LegalEditorialSplitMergeIT,LegalEditorialApplyFailureIT,LegalEditorialReconciliationIT,LegalEditorialDatabaseIsolationIT,LegalEditorialPrivilegeVerifierIT verify
~~~

Puerta Java focal completa:

~~~bash
./mvnw -Dtest=LegalEditorialTimeBoundaryTest,LegalManifestDatabaseGateTest,LegalEditorialExecutionPlanTest,LegalEditorialPlanResultTest,LegalEditorialPlannerCoreTest,LegalEditorialReadinessCoreTest,LegalEditorialPlanServiceTest,LegalEditorialApplyServiceTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialRetireServiceTest,LegalEditorialApplyServiceReconciliationTest,LegalEditorialCommitReconcilerTest,LegalDocumentReplacementWriterTest,LegalEditorialRetirementWriterTest,LegalEditorialMutationWriterTest,LegalEditorialPostStateVerifierTest,LegalEditorialCliTest,LegalEditorialCliExecutionStateTest,LegalEditorialReportTest,LegalEditorialReportWriterTest test
~~~

Puerta completa final:

~~~bash
./mvnw clean verify
~~~

Cierre:

1. Registrar JDK, comandos, duración, tests y resultado en este documento y en el plan padre.
2. Registrar que cada operación editorial agrega exactamente una sentencia respecto del flujo
   previo y revisar futuros presupuestos/conteos de capacidad sin cambiar sus límites.
3. Ejecutar `git diff --check` y revisar `git status --short`.
4. Separar el fix productivo/unitario de la acreditación IT 10A si el índice permite una división
   coherente y verde.
5. No mezclar documentación previa ni archivos ajenos al corte.
6. No hacer push.

## Estrategia de commits

Commits locales previstos:

1. `fix(legal): separa el reloj editorial post-lock`
   - frontera, gate, planner, plan, servicios, writers y unitarios;
   - sólo después de que unitarios y regresiones editoriales estén verdes.
2. `test(legal): acredita concurrencia editorial`
   - fixture/soporte/IT de 10A y evidencia final;
   - sólo después de la puerta focal y ampliada PostgreSQL 16.

Si la propagación productiva puede dividirse en commits verdes, usar como máximo dos commits
productivos (`refactor` de frontera y `fix` semántico). No conservar overloads temporales, APIs
internas duplicadas ni commits intermedios que compilen pero publiquen semántica incorrecta.

## Evidencia de ejecución

Pendiente. Completar durante el microcorte con:

- salida roja determinista previa;
- hash del commit productivo;
- gates unitarios;
- gates PostgreSQL 16;
- auditoría temporal negativa;
- hash del commit de acreditación 10A;
- confirmación explícita de que no hubo push ni deploy.
