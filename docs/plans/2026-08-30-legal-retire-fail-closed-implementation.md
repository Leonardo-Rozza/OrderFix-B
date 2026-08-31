# Corte 8 — Plan de implementación del retiro editorial RETIRE fail-closed

Fecha: 2026-08-30

Estado: en ejecución — Subcortes 8A, 8B, 8C, 8D y 8E completados el 2026-08-30;
Subcorte 8F pendiente

Diseño aprobado:

- `docs/plans/2026-08-30-legal-retire-fail-closed-design.md`;
- commit local `5d2c358` y precisión de evidencia de replay `9991857`;
- rama `codex/lanzamiento-publico-backend`.

Este documento descompone el Corte 8 de la Fase 2.3C en subcortes pequeños. Cada subcorte empieza
por una prueba roja o de caracterización, termina con diff limpio y se registra en un commit local
atómico. No se hace push ni deploy.

## Invariantes comunes

1. `RETIRE` es una herramienta editorial interna. No agregar endpoint, controller, JPA, frontend,
   scheduler ni permisos para usuarios de talleres.
2. No modificar V27/V28, schemas JSON, grants, roles, inventarios ni funciones SQL.
3. Current y target son el mismo UUID; sólo se retiran miembros enumerados explícitamente.
4. Motivo, digest, contexto, audiencia, acknowledgement y readiness esperado se validan antes del
   primer DML.
5. El postestado representa el grafo completo. Nunca se deriva copiando una proyección mutable
   incompleta.
6. Todo miembro, historia, lote, slot y puntero no afectado se preserva exactamente, incluido el
   `updatedAt` de cada puntero sobreviviente.
7. El delta RETIRE contiene sólo deletes de punteros/slots y transiciones terminales; no contiene
   inserts de proyecciones, sucesoras, rebinds o lotes.
8. Apply usa una única sesión JDBC y transacción `REQUIRES_NEW/READ_COMMITTED` bajo el advisory
   lock editorial compartido.
9. El orden DML es punteros, slots, requisitos y documentos; cada batch exige cardinalidad exacta.
10. Writer, post-verifier, constraints y readiness mantienen responsabilidades separadas.
11. Después de `SET CONSTRAINTS ALL IMMEDIATE` sólo se ejecuta readiness; no hay más DML.
12. Un apply fresco sólo confirma si el readiness real es exactamente `NOT_READY`.
13. Replay se acredita por postestado, no por autoría de `operationId`; no se agrega ledger V27.
14. `persisted=true` acredita el estado editorial confirmado, no una fila de receipt persistida.
15. Replay o BLOCKED previo al DML no avanza secuencias. Rollback tardío puede dejar huecos en
    secuencias PostgreSQL sin representar persistencia parcial.
16. No agregar retries, force, healing, fallback a otra operación ni hooks productivos de fallo.
17. PROMOTE, REPLACE, reportes v1/v2 y demás comandos v3 permanecen compatibles.
18. Antes de cada commit: puerta focal, revisión adversarial, `git diff --check` y
    `git status --short`.

## Subcorte 8A — Invariantes del execution plan

Estado: completado el 2026-08-30.

### Objetivo

Preparar la forma operation-aware que permite a RETIRE transportar proyecciones preservadas sin
confundirlas con inserts del delta. Este subcorte debe quedar verde antes de que el planner empiece
a emitir el postestado parcial completo.

### Modificar

- `LegalEditorialExecutionPlan`;
- `LegalEditorialPlannerCore`, sólo para adaptar el delete RETIRE a la nueva forma de evidencia;
- `LegalEditorialPostStateVerifier`;
- `LegalEditorialExecutionPlanTest`;
- `LegalEditorialPlannerCoreTest`;
- `LegalEditorialPostStateVerifierTest`.

### Pasos

1. Caracterizar la matriz actual que exige slots/punteros/lotes finales vacíos para RETIRE.
2. Permitir colecciones finales no vacías cuando representen proyecciones históricas preservadas.
3. Exigir correspondencia causal entre cada transición terminal, delete de slot y delete de
   puntero declarado y al menos un miembro del retiro explícito.
4. Prohibir inserts de slots/punteros, altas, sucesoras, rebinds y lotes nuevos.
5. Prohibir que una clave eliminada aparezca también como sobreviviente.
6. No exigir `updatedAt == expectedAppliedAt` a punteros preservados.
7. Exigir `expectedAppliedAt` uniforme sólo para las transiciones nuevas del delta.
8. Separar lotes históricos esperados de comandos de lote actuales, que deben ser vacíos.
9. Mantener el verifier global: falta/exceso de membresía, historia, slot, puntero, conjunto de
   dependencias o lote bloquea el postestado fijo esperado.
10. Ejecutar regresión de execution plans PROMOTE y REPLACE.

### Frontera acreditada en 8A

`RequiredSetDependencies` transporta dos conjuntos canonicalizados y ordenados: versiones de
requisitos miembros y versiones documentales referenciadas. La canonicalización conserva una sola
aparición de un documento compartido por varios requisitos; el límite de lectura continúa
aplicándose sobre las filas originales antes de formar la unión.

En RETIRE, todo puntero sobreviviente y todo delete declarado debe aportar esa evidencia. Un
survivor no puede intersectar versiones retiradas y un delete debe intersectar al menos una. El
post-verifier compara ambos conjuntos contra la expectativa fija, además de key, set, publicación,
revisión y `updatedAt`. PROMOTE y REPLACE conservan su forma y comparación previas.

Esta capa no puede acreditar por sí sola que una key fue omitida de delete y postestado, ni que la
evidencia autocontenida de un delete coincide con PostgreSQL. La completitud del universo se
demuestra en 8B al derivar survivors y deletes desde todos los scopes autoritativos. La autenticidad
de key, set, dependencias y cardinalidad se revalida bajo lock en 8C antes del primer DML.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialExecutionPlanTest,LegalEditorialPlannerCoreTest,LegalEditorialPostStateVerifierTest test
git diff --check
git status --short
~~~

### Evidencia de cierre 8A

- Caracterización inicial: las pruebas nuevas reprodujeron las seis denegaciones de la matriz
  RETIRE previa cuando el postestado preservaba slots, punteros o lotes históricos.
- Prueba adversarial adicional: el contrato previo no podía representar dependencias de punteros y
  aceptaba un delete sin vínculo causal; la prueba quedó roja antes de agregar la nueva evidencia.
- Puerta focal final: 74 pruebas, 0 fallos, 0 errores y 0 omitidas.
- Suite completa: 2.646 pruebas, 0 fallos, 0 errores y 0 omitidas.
- La revisión adversarial detectó y corrigió dos riesgos antes del commit: over-delete declarado
  sin causalidad y referencias documentales repetidas legítimas entre varios requisitos.
- Se acreditaron retiros por documento y por requisito, evidencia obligatoria de deletes y
  survivors, drift de ambos conjuntos, timestamps históricos preservados y regresión
  PROMOTE/REPLACE.
- No se modificaron V27/V28, schemas, grants, API, frontend, writer, apply ni CLI; no hubo push ni
  deploy.

Commit:

    fix(legal): valida plan exacto de retiro

## Subcorte 8B — Postestado parcial exacto

Estado: completado el 2026-08-30.

### Objetivo

Corregir la planificación RETIRE para clasificar toda la publicación current y representar de
forma exacta los elementos retirados y los sobrevivientes, todavía sin habilitar apply o CLI.

### Modificar

- `LegalEditorialPlannerCore`;
- `LegalEditorialPlannerCoreTest`;
- `LegalEditorialPlanServiceTest` sólo si cambia la evidencia devuelta por el planner;
- `LegalEditorialPlannerIT` para acreditar la lectura real de proyecciones preservadas.

### Pasos

1. Escribir casos document-only, requirement-only y mixto con miembros no afectados.
2. Hacer que `retirementPlan` clasifique la membresía documental y de requisitos completa.
3. Preservar el estado y la prehistoria exacta de todas las versiones no retiradas.
4. Agregar una sola transición terminal por versión explícita, con motivo y timestamp esperados.
5. Enriquecer la evidencia autoritativa de cada scope con miembros del conjunto sellado y la unión
   canonicalizada de versiones documentales referenciadas; derivar desde allí las claves
   sobrevivientes y la unión afectada tanto en SOURCE como en POST.
6. Acreditar existencia y unicidad de cada slot/puntero esperado antes de copiar sus valores; sólo
   los punteros transportan `updatedAt`.
7. Construir la unión exacta de punteros afectados por documentos y requisitos retirados.
8. Preservar todos los lotes históricos y prohibir lotes actuales nuevos.
9. Bloquear una proyección sobreviviente extra, faltante o incoherente sin normalizarla.
10. Conservar replay-first: el postestado ya exacto se compara antes de exigir el estado fuente.

### Frontera acreditada en 8B

El planner clasifica ahora todos los documentos y requisitos de la membresía current. Sólo las
versiones enumeradas reciben una transición nueva a `RETIRADA`; los estados, historias y lotes
sellados de los demás miembros se preservan exactamente. Los lotes actuales y todas las formas de
insert de proyección permanecen prohibidos para RETIRE.

Los slots fuente se derivan de todos los documentos actualmente `VIGENTE` y los slots finales de
los documentos que seguirán `VIGENTE`. Los punteros se derivan de todos los scopes sellados de la
publicación: cada scope transporta sus miembros y la unión canonicalizada de referencias
documentales. La lectura aplica el presupuesto a las filas crudas antes de canonicalizar; un
overflow falla cerrado. Recién después de comparar key, set, publicación, revisión y dependencias
contra la proyección activa se copia el `updatedAt` de un survivor.

La partición admite una publicación ya `NOT_READY` por retiros anteriores. `source-active` contiene
los scopes cuyas dependencias observadas siguen `VIGENTE`; `post-active` aplica además el retiro
explícito; los deletes son la diferencia exacta. Un scope ya ausente por una dependencia terminal
histórica no se recrea ni se declara otra vez como delete.

Replay continúa antes del fingerprint fuente, pero sólo se acredita contra el corte append-only
exacto: una transición no declarada en el mismo timestamp o cualquier transición posterior sobre
la membresía bloquea. En SOURCE toda prehistoria debe ser estrictamente anterior a `observedAt`;
en POST el cutover inferido nunca puede ser posterior a la observación. Ambos desvíos fallan como
`CURRENT_STATE_MISMATCH` antes de construir el plan. Esta regla no se extiende al `updatedAt`
mutable de un puntero sobreviviente, cuya limitación forense permanece documentada. La
autenticidad y cardinalidad se volverán a acreditar bajo lock en 8C.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialExecutionPlanTest,LegalEditorialPlannerCoreTest,LegalEditorialPlanServiceTest,LegalEditorialPostStateVerifierTest test
./mvnw -Dit.test=LegalEditorialPlannerIT verify
git diff --check
git status --short
~~~

### Evidencia de cierre 8B

- Puerta focal final: 89 pruebas, 0 fallos, 0 errores y 0 omitidas.
- Suite unitaria ejecutada por el lifecycle de integración: 2.656 pruebas, 0 fallos, 0 errores y
  0 omitidas.
- `LegalEditorialPlannerIT` sobre PostgreSQL 16/Flyway V27: 10 pruebas, 0 fallos, 0 errores y
  0 omitidas.
- Se acreditaron retiros document-only, requirement-only y mixtos; unión deduplicada de punteros;
  postestado completo; historias, lotes, slots, dependencias y `updatedAt` sobrevivientes exactos;
  y replay-first con timestamp histórico.
- Una fixture PostgreSQL con dos requisitos que referencian los mismos documentos acreditó filas
  duplicadas reales y su canonicalización final. La revisión del reader confirmó que el
  `LIMIT max + 1` se aplica a las filas crudas y que `bounded()` falla antes de canonicalizar; una
  fixture que exceda materialmente ese presupuesto queda como refuerzo adversarial futuro.
- La revisión adversarial reprodujo y cerró un falso replay que absorbía un retiro posterior no
  declarado, además de prehistoria SOURCE igual/futura y cutover POST futuro.
- El planner permaneció select-only: el test de replay conservó sin cambios 19 tablas y 10
  secuencias.
- No se modificaron V27/V28, schemas, grants, API, frontend, writer, apply ni CLI; no hubo push ni
  deploy.

Commit:

    fix(legal): preserva postestado parcial de retiro

## Subcorte 8C — Writer y aplicación RETIRE

Estado: completado el 2026-08-30.

### Objetivo

Ejecutar el delta RETIRE mediante un writer dedicado y coordinar replay, verificación, constraints
y postcondición `NOT_READY` dentro de una sola transacción.

### Crear

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialRetirementWriter.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialRetirementWriterTest.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialRetireServiceTest.java`.

### Modificar

- `LegalEditorialApplyService`;
- `LegalEditorialDatabaseConfiguration`;
- `LegalEditorialApplyServiceTest` y `LegalEditorialApplyServiceReplaceTest` por ensamblado y
  regresión;
- `LegalEditorialTransactionBoundaryTest`, sólo como regresión reejecutada; no requirió cambios;
- `LegalManifestPersistenceITSupport` y ensamblados directos del apply service;
- failure mapper sólo si hace falta conservar el issue tipado exacto.

### Pasos

1. Escribir el contrato SQL exacto del writer antes de implementarlo.
2. Rechazar un plan que no sea RETIRE o contenga comandos PROMOTE/REPLACE antes del DML.
3. Bloquear publicación, líneas y versiones en orden determinista; después tomar un lock de tabla
   combinado y ordenado `SHARE ROW EXCLUSIVE` sobre punteros y slots actuales.
4. Revalidar current/target, estados, membresía, fingerprint y proyecciones bajo esos locks,
   incluida la igualdad de key, set, revisión y dependencias de cada puntero.
5. Eliminar la unión deduplicada de punteros afectados y exigir cardinalidad exacta.
6. Eliminar slots documentales afectados y exigir cardinalidad exacta.
7. Insertar transiciones de requisitos y luego documentos en orden UUID.
8. Mantener al writer fuera de replay, receipt, constraints y readiness.
9. Inyectar el writer dedicado y exponer `applyRetire` en el servicio.
10. Ejecutar `writer → verifier → constraints → readiness` en la misma sesión.
11. Rechazar un input cuyo readiness esperado no sea `NOT_READY` con
    `BLOCKED/EXPECTED_READINESS_MISMATCH` antes del DML.
12. Exigir readiness real `NOT_READY` después del DML; otra forma devuelve
    `ERROR/POSTCONDITION_NOT_READY`, `persisted=false` y rollback.
13. Congelar `APPLIED/ALREADY_APPLIED`, `persisted` y completion-state sin receipt tentativo.
14. Probar que PROMOTE/REPLACE continúan eligiendo sus writers exactos.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialRetirementWriterTest,LegalEditorialRetireServiceTest,LegalEditorialApplyServiceTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialTransactionBoundaryTest,LegalEditorialFailureMapperTest,LegalEditorialPostStateVerifierTest test
git diff --check
git status --short
~~~

### Frontera acreditada en 8C

`LegalEditorialRetirementWriter` acepta exclusivamente un apply fresco `RETIRE` con acknowledgement
y postcondición esperada `NOT_READY`. Antes del primer DML vuelve a acreditar identidad,
membresía completa, preestados, source fingerprint y la unión survivor+delete de slots y punteros.
Además exige causalidad local aunque reciba un plan construido fuera del planner: un slot eliminado
debe pertenecer a un documento retirado, un puntero sobreviviente no puede depender de una versión
retirada y cada puntero eliminado debe intersectar el retiro explícito.

El orden fresco quedó congelado como `pointer delete → slot delete → requirement transition →
document transition`, seguido en el coordinador por `verifier → SET CONSTRAINTS ALL IMMEDIATE →
readiness`. Los deletes conservan CAS estricto `count == 1`; sólo los inserts aceptan también
`Statement.SUCCESS_NO_INFO`, tal como permite el contrato JDBC ya acreditado para REPLACE. Replay
no invoca writer, constraints ni readiness, y conserva el `appliedAt` histórico construido por el
verifier. Una finalización `UNKNOWN` nunca expone receipt, target ni timestamp tentativos.

Las proyecciones actuales no usan `SELECT ... FOR UPDATE`, porque el rol editorial no posee ni
necesita `UPDATE` sobre ellas. El writer toma en cambio un lock combinado
`SHARE ROW EXCLUSIVE` sobre ambas tablas, en orden fijo y antes de leerlas. PostgreSQL admite ese
lock con el privilegio `DELETE` ya requerido por el delta y lo conserva hasta fin de transacción;
así se mantiene la revalidación estable sin V28, grants o inventario nuevo. La ejecución fresca
real con el rol restringido queda deliberadamente en 8D.

### Evidencia de cierre 8C

- Puerta focal limpia: 90 pruebas, 0 fallos, 0 errores y 0 omitidas.
- Suite unitaria completa: 2.676 pruebas, 0 fallos, 0 errores y 0 omitidas.
- Ensamblado y privilegios sobre PostgreSQL 16/Flyway V27:
  `LegalEditorialDatabaseIsolationIT` y `LegalEditorialPrivilegeVerifierIT`, 9 pruebas, 0 fallos,
  0 errores y 0 omitidas.
- La revisión adversarial cerró tres riesgos antes del commit: row locks incompatibles con mínimo
  privilegio, causalidad no repetida por el writer y tratamiento demasiado estricto de
  `SUCCESS_NO_INFO` en inserts.
- Se congelaron dispatch exacto por operación, replay select-only, rollback por readiness distinto
  de `NOT_READY`, error de readiness mapeado a la postcondición RETIRE y completion `UNKNOWN` sin
  receipt tentativo. PROMOTE y REPLACE permanecieron verdes.
- Residual explícito: las comparaciones exactas del writer recorren las proyecciones actuales sin
  un presupuesto SQL propio, igual que el writer REPLACE existente. El planner ya limita el input;
  capacidad y concurrencia exhaustivas permanecen en el Corte 10, fuera del alcance 8C.
- No se modificaron V27/V28, schemas, grants, roles, inventarios, API, frontend ni CLI; no hubo
  push ni deploy.

Commit:

    feat(legal): aplica retiro editorial fail closed

## Subcorte 8D — PostgreSQL fresco

Estado: completado el 2026-08-30.

### Objetivo

Acreditar ejecuciones frescas documentales, de requisitos y mixtas sobre PostgreSQL 16/Flyway V27
con el rol editorial restringido exacto.

### Archivos

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialRetireIT.java`.
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPrivilegeVerifierIT.java`.

### Alcance acreditado

`LegalEditorialRetireIT` ejecuta cuatro retiros frescos e independientes:

1. retiro exclusivamente documental;
2. retiro exclusivamente de requisito;
3. retiro mixto documental y de requisito;
4. `REPLACE` real seguido por el retiro de `account-closure`, con un lote histórico sellado.

Las cuatro operaciones se aplican mediante el datasource del rol editorial restringido, sin
suplantación de owner y sin agregar grants. La integración acredita así que el `LOCK TABLE` de
`legal_requisito_conjuntos_actuales` y `legal_documento_vigentes` en modo
`SHARE ROW EXCLUSIVE` puede adquirirse con la superficie V27 existente y mantenerse durante el
apply completo.

Las fixtures usan el único locale admitido por el contrato v1, `ES_AR` (`es-AR`), y ejercitan
múltiples contextos y audiencias. No se modelan múltiples locales: agregar otro locale implicaría
cambiar contrato, schema y alcance.

Cada escenario acredita:

- `PASS`, `APPLIED`, `persisted=true`, `operationType=RETIRE` y `readinessAfter=NOT_READY`;
- receipt exacto para documentos, requisitos, historias, slots, punteros y lotes;
- estados `RETIRADA`, motivos y `appliedAt` iguales a las transiciones persistidas;
- una única transición terminal por miembro retirado;
- `appliedAt` contenido en el bracket de timestamps obtenido directamente de PostgreSQL antes y
  después del apply;
- eliminación exacta de slots documentales declarados;
- eliminación deduplicada de la unión de punteros afectados;
- preservación exacta de estados, historias, slots, punteros, dependencias y `updatedAt` no
  afectados;
- sentinelas canónicos no vacíos de aceptación e idempotencia preservados en todos los casos;
- constraints y triggers V27 satisfechos;
- columnas de origen, aceptaciones, idempotencia y tablas ajenas sin cambios;
- avance exclusivo de las secuencias de transición realmente consumidas;
- ejecución completa con el rol editorial restringido y sin modificación de V27, inventario o
  privilegios.

Las tres primeras fixtures frescas parten sin lotes de reemplazo y confirman
`replacementBatches=0`. La cuarta construye un `REPLACE` real, confirma un lote histórico sellado
y luego aplica `RETIRE` sobre `account-closure`: las snapshots de
`legal_documento_reemplazo_lotes`, `legal_documento_reemplazo_anteriores` y
`legal_documento_reemplazo_sucesoras`, junto con las versiones y transiciones históricas, se
preservan exactamente. 8E acredita replay, corrupción extra o faltante, ausencia de healing,
fallo tardío, rollback fila por fila y completion `UNKNOWN`.

### Evidencia de cierre

- retiro documental: `documents=11`, `requirements=6`, `documentTransitions=23`,
  `requirementTransitions=12`, `documentSlots=19`, `requiredSetPointers=4` y
  `replacementBatches=0`;
- retiro de requisito: `documents=11`, `requirements=6`, `documentTransitions=22`,
  `requirementTransitions=13`, `documentSlots=21`, `requiredSetPointers=6` y
  `replacementBatches=0`;
- retiro mixto: `documents=11`, `requirements=6`, `documentTransitions=23`,
  `requirementTransitions=13`, `documentSlots=19`, `requiredSetPointers=4` y
  `replacementBatches=0`;
- retiro posterior al `REPLACE` real: `documents=11`, `requirements=6`,
  `documentTransitions=22`, `requirementTransitions=13`, `documentSlots=21`,
  `requiredSetPointers=7` y `replacementBatches=1`;
- la unión mixta elimina cuatro punteros únicos y no seis operaciones superpuestas;
- los deltas RETIRE de secuencias documental/requisito son `+1/+0`, `+0/+1`, `+1/+1` y
  `+0/+1`; las demás secuencias editoriales permanecen sin cambios respecto de la snapshot previa
  a cada retiro;
- la puerta focal ejecutó 42 pruebas unitarias, con cero fallos, errores u omitidas;
- la puerta `verify` ejecutó 2.676 pruebas unitarias y 21 integraciones seleccionadas: 8 de
  privilegios, 9 de readiness y 4 de RETIRE fresco, con cero fallos, errores u omitidas;
- PostgreSQL 16.14, Flyway 27 y la verificación del rol restringido quedaron verdes;
- `git diff --check` no reportó observaciones;
- no se modificaron migraciones, grants, inventario, API, CLI ni frontend.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialRetirementWriterTest,LegalEditorialRetireServiceTest,LegalEditorialPostStateVerifierTest test
./mvnw -Dit.test=LegalEditorialRetireIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialReadinessIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): acredita retiro editorial en postgresql

## Subcorte 8E — Replay, corrupción y rollback

Estado: completado el 2026-08-30.

### Objetivo

Demostrar que RETIRE es idempotente por postestado, no oculta corrupción y revierte exactamente un
fallo tardío.

### Modificar

- `LegalEditorialRetireIT`;
- `LegalEditorialPlannerCoreTest`;
- `LegalEditorialPlannerCore`, porque la integración reveló una brecha real de causalidad histórica;
- este documento.

### Escenarios

- replay exacto con la misma identidad externa;
- replay del mismo postestado con otro `operationId`/SHA, sin atribuir autoría histórica;
- cero cambios de filas y secuencias en replay o BLOCKED previo a DML;
- plan distinto que predice otro postestado bloqueado por terminalidad/fingerprint;
- slot, puntero, transición, membresía o lote sobreviviente extra/faltante sin healing;
- no atribuir al replay detección forense de una alteración aislada de `updatedAt` en un puntero
  sobreviviente, porque V27 no persiste la evidencia histórica necesaria;
- fallo después de deletes y transiciones, antes de permitir commit;
- rollback fila por fila de las 19 tablas editoriales;
- huecos de secuencia limitados a `nextval` realmente ejecutados y documentados como no
  transaccionales;
- regresión de post-verifier, constraints, readiness y completion-state.

### Alcance acreditado

- replay exacto y replay del mismo postestado con otro `operationId`/SHA terminan
  `ALREADY_APPLIED`, recuperan el mismo receipt/`appliedAt` histórico y cada intento por separado
  deja sin cambios las 19 tablas editoriales y las 10 secuencias;
- un plan alternativo y un fingerprint incorrecto terminan
  `BLOCKED/SOURCE_FINGERPRINT_MISMATCH` antes de DML;
- las 10 corrupciones extra/faltante de slot, puntero, transición, membresía y lote quedan
  bloqueadas sin healing ni avance de secuencias;
- la revisión adversarial reprodujo un falso replay: el planner sólo comprobaba
  transición-visible→membresía y podía absorber un predecessor histórico fuera del scope. El
  reader ahora siembra lotes desde las transiciones observadas y materializa, por `lote_id`, su
  causalidad separada del historial operativo;
- `BatchEvidence` exige una biyección exacta y acotada entre predecessors/successors y
  transiciones: cardinalidad y UUID únicos, roles no superpuestos, aristas
  `VIGENTE→REEMPLAZADA`/`PUBLICADA→VIGENTE`, motivo nulo e instante igual al sello. Los miembros
  combinados y las transiciones causales tienen presupuesto fail-closed de 8.192 filas;
- PostgreSQL acredita tanto el predecessor histórico faltante como un miembro extra real en una
  publicación draft fuera del scope actual;
- la alteración aislada de `updatedAt` en un puntero sobreviviente permanece como frontera
  epistémica explícita de V27: replay puede devolver `ALREADY_APPLIED` y no repara esa fila;
- el fallo después del último batch DML mixto devuelve
  `ERROR/EDITORIAL_OBSERVATION_FAILED`, revierte exactamente las 19 tablas partiendo de snapshots
  no vacías —incluidas las tres tablas de REPLACE— y sólo deja los huecos `+1/+1` de las
  secuencias de transición realmente ejecutadas;
- una pérdida de acuse de commit devuelve `UNKNOWN` sin evidencia tentativa. El retry converge a
  `ALREADY_APPLIED`, no ejecuta DML y recupera exactamente el `appliedAt` persistido;
- la consulta causal por `reemplazo_lote_id` no tiene índice dedicado en V27. Es un riesgo de
  capacidad no bloqueante reservado para Corte 10: el presupuesto/timeout falla cerrado y este
  subcorte no altera migraciones.

### Evidencia de cierre

- puerta focal: 75 tests verdes (`PlannerCore=33`, `PostStateVerifier=20`, `RetireService=9`,
  `RetirementWriter=13`);
- ciclo completo: 2.679 tests unitarios verdes;
- PostgreSQL 16.14/Flyway V27: `RetireIT=20`, `DatabaseIsolationIT=2` y `ReadinessIT=9`, total
  31/31;
- regresión compartida: `ReplaceIT=13` y `SplitMergeIT=13`, total 26/26;
- planner/promoción: `EditorialPlannerIT=10`, `InitialPromotionIT=7` y
  `InitialPromotionFailureIT=4`, total 21/21;
- dos revisiones independientes finales sin hallazgos P1/P2;
- `git diff --check` limpio;
- sin cambios en V27/V28, grants, roles, inventario, API, CLI o frontend; sin push ni deploy.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialPlannerCoreTest,LegalEditorialRetirementWriterTest,LegalEditorialRetireServiceTest,LegalEditorialPostStateVerifierTest test
./mvnw -Dit.test=LegalEditorialRetireIT,LegalEditorialDatabaseIsolationIT,LegalEditorialReadinessIT verify
./mvnw -Dit.test=LegalEditorialReplaceIT,LegalEditorialSplitMergeIT verify
./mvnw -Dit.test=LegalEditorialPlannerIT,LegalInitialPromotionIT,LegalInitialPromotionFailureIT verify
git diff --check
git status --short
~~~

Commit:

    fix(legal): acredita replay y rollback de retiro

## Subcorte 8F — CLI y reporte v3

Estado: pendiente.

### Objetivo

Exponer `plan-retire` y `apply-retire` sólo en la herramienta interna, con preflight estricto,
reporte sanitizado y exit codes coherentes con un éxito deliberadamente `NOT_READY`.

### Modificar

- `LegalEditorialArguments` y `LegalEditorialArgumentsTest`;
- `LegalEditorialCliExecutionState` y su test;
- `LegalEditorialReport`, `LegalEditorialReportWriter` y sus tests;
- `LegalManifestCli` y `LegalManifestCliTest`;
- `LegalEditorialCliTest`, `LegalEditorialPreflightTest`, `LegalEditorialPlanConfirmationTest` y
  `LegalEditorialEnvironmentTest`;
- `LegalManifestCliIsolationIT` y `LegalManifestCliProcessIT`;
- `scripts/legal-manifest-editor.sh` y su test sólo si el launcher enumera comandos.

### Pasos

1. Agregar comandos exactos `plan-retire` y `apply-retire`, sin aliases ni defaults.
2. Reutilizar ruta de manifest, ruta de plan y las cuatro confirmaciones literales estrictas.
3. Mantener siete tokens exactos en ambos comandos y habilitar apply sólo mediante el flag
   operativo existente `ORDENFIX_LEGAL_EDITOR_ENABLED=true`; no agregar otra confirmación.
4. Validar bundle, plan RETIRE, operationId, SHAs, current/target, acknowledgement y readiness antes
   de resolver entorno/Spring/JDBC.
5. Ejecutar `LegalEditorialReplaceScopeGuard` exclusivamente para comandos REPLACE.
6. Despachar al método plan/apply RETIRE exacto y retener identidad input-safe monotónica.
7. Admitir `operationType=RETIRE` y `expectedReadinessAfter=NOT_READY` en reporte v3.
8. Emitir exit 0 para `APPLIED` y `ALREADY_APPLIED` aunque readiness sea `NOT_READY`.
9. Mantener exit no cero y metadata sanitizada para `BLOCKED`, `ERROR` y `UNKNOWN`.
10. Probar proceso empaquetado y ausencia de secretos/rutas en stdout y JAR.
11. Confirmar que reportes v1/v2 y comandos PROMOTE/REPLACE no cambian.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialArgumentsTest,LegalEditorialCliExecutionStateTest,LegalEditorialPreflightTest,LegalEditorialCliTest,LegalEditorialReportTest,LegalEditorialReportWriterTest,LegalEditorialPlanConfirmationTest,LegalEditorialEnvironmentTest,LegalManifestCliTest,LegalManifestEditorLauncherTest test
./mvnw -Dit.test=LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Commit:

    feat(legal): expone retiro editorial interno

## Subcorte 8G — Regresiones y cierre

Estado: pendiente.

### Objetivo

Cerrar Corte 8 con evidencia ejecutable, documentación alineada y ninguna expansión de superficie.

### Modificar

- `docs/plans/2026-08-30-legal-retire-fail-closed-design.md`;
- este documento;
- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`;
- documentos históricos anteriores sólo con una nota de continuidad que evite una lectura
  engañosa.

No actualizar todavía runbooks de lanzamiento, `FRONTEND_INTEGRATION.md` o planes frontend: el
cierre cross-repo permanece reservado para el Corte 11.

### Puerta final

~~~bash
./mvnw -Dtest=LegalEditorialPlanValidatorTest,LegalEditorialExecutionPlanTest,LegalEditorialPlannerCoreTest,LegalEditorialPlanServiceTest,LegalEditorialRetirementWriterTest,LegalEditorialRetireServiceTest,LegalEditorialPostStateVerifierTest,LegalEditorialApplyServiceTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialArgumentsTest,LegalEditorialCliExecutionStateTest,LegalEditorialPreflightTest,LegalEditorialCliTest,LegalEditorialReportTest,LegalManifestCliTest test
./mvnw -Dit.test=LegalEditorialRetireIT,LegalEditorialSplitMergeIT,LegalEditorialReplaceIT,LegalInitialPromotionIT,LegalInitialPromotionFailureIT,LegalEditorialReadinessIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialDatabaseIsolationIT,LegalManifestImportIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
./mvnw test
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Registrar Java, PostgreSQL, Flyway, conteos reales, regresiones, revisión adversarial, ramas y
ausencia de push/deploy. Reconciliación ampliada de `UNKNOWN`, capacidad y procesos exhaustivos
permanecen en Cortes 9 y 10.

Commit:

    docs(legal): cierra retiro editorial fail closed

## Criterio de cierre

Corte 8 termina únicamente cuando 8A–8G están en commits locales separados; RETIRE preserva todo
el estado no afectado; documentos, requisitos y mixto terminan `APPLIED+NOT_READY`; replay es
select-only; corrupción no se repara; rollback es exacto; el rol no obtiene nuevos grants; CLI es
la única superficie incorporada; PROMOTE/REPLACE/import siguen verdes; y no hubo push ni deploy.
