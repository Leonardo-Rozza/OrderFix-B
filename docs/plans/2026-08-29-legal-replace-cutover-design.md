# Corte 6 — Diseño del cutover editorial REPLACE uno a uno

Fecha: 2026-08-29

Estado: aprobado

Rama backend: `codex/lanzamiento-publico-backend`

## Contexto

Los Cortes 1 a 5 de la Fase 2.3C ya congelaron el contrato editorial, readiness, planificación,
primera promoción y CLI aislada. El planner y `LegalEditorialExecutionPlan` ya representan un
`REPLACE` completo, separando los comandos directos de los efectos que materializan los triggers
V27. Falta ejecutar ese delta de forma transaccional y exponer `plan-replace` y `apply-replace` sin
debilitar `PROMOTE` ni adelantar las capacidades de split/merge del Corte 7.

Este documento precisa el Corte 6 del plan aprobado en
`docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`.

## Alcance aprobado

Un `REPLACE` del Corte 6 puede combinar, en una sola transacción:

- como máximo un lote documental de reemplazo;
- exactamente una predecesora y una sucesora cuando el lote existe;
- adiciones documentales sin predecesor;
- reutilizaciones documentales con rebind de slots al target;
- retiros documentales explícitos sin sucesor y con motivo;
- requisitos nuevos, reutilizados, reemplazados y retirados;
- reemplazo completo de punteros actuales hacia snapshots sellados existentes;
- un cutover sin lote cuando el delta no necesita reemplazo documental —incluidos reemplazos de
  requisitos— y el target igualmente termina `READY`.

Quedan fuera de este corte:

- más de un lote por operación;
- lotes 1→N o N→1 y cualquier split/merge;
- V28 o cambios en V27;
- grants nuevos o ampliados;
- endpoints, JPA, repositorios de aplicación, scheduler, UI y frontend;
- cambios sobre snapshots sellados, aceptaciones o idempotencia HTTP;
- contenido legal real, deploy o push.

Todo plan fuera de esta frontera se bloquea con `REPLACEMENT_MAPPING_INVALID` antes de abrir la
base desde CLI y, como defensa programática, antes de comparar replay o ejecutar cualquier DML. El
Corte 7 elimina únicamente esa restricción estructural después de agregar sus pruebas específicas.

## Alternativas consideradas

### 1. Coordinador común y writers especializados — elegida

`LegalEditorialApplyService` conserva una sola frontera para gate, timestamp, planner, estado de
finalización, clasificación de errores y postcondición. La mutación se delega a un writer específico
por operación.

Ventajas:

- una sola semántica para rollback, commit confirmado y `UNKNOWN`;
- `PROMOTE` mantiene su writer acreditado;
- el SQL de `REPLACE` queda aislado y testeable;
- el Corte 7 puede extender el writer documental sin duplicar el coordinador;
- replay y verificación exacta se comparten.

### 2. Servicio REPLACE independiente — descartada

Aísla la nueva operación, pero duplicaría el gate, timestamp, completion state, mapeo de fallos,
sesión JDBC y postcondición. Dos fronteras de commit podrían divergir en su tratamiento de
`persisted`.

### 3. Servicio monolítico — descartada

Agregar todo el SQL a `LegalEditorialApplyService` requiere menos clases, pero mezcla coordinación,
locks, mutación, replay y verificación. Aumenta el riesgo de alterar `PROMOTE` y dificulta extender
split/merge sin una refactorización posterior.

## Componentes

### Coordinador transaccional

`LegalEditorialApplyService`:

- expone `applyPromote` y `applyReplace`;
- usa exactamente el gate mutable `REQUIRES_NEW/READ_COMMITTED` existente;
- lee una sola vez `transaction_timestamp()` dentro del callback;
- pide al planner un plan derivado desde la misma sesión JDBC;
- ejecuta el guard de capacidad antes de admitir `APPLICABLE` o `ALREADY_APPLIED`;
- no invoca un writer cuando el postestado ya es exacto y pide al verificador común el receipt de
  replay;
- conserva el receipt antes de salir del callback y clasifica la finalización con
  `LegalTransactionCompletionState`;
- no reintenta una nueva mutación dentro de la misma ejecución.

### Guard de capacidad del corte

`LegalEditorialReplaceScopeGuard` es puro y compartido por CLI/preflight, plan y apply. Recibe el
`ValidatedEditorialPlan` y exige entre cero y un lote y cardinalidad 1/1 cuando existe. Se ejecuta
antes de cualquier lectura de estado mutable y antes de la comparación de postestado. El writer
repite la defensa sobre `LegalEditorialExecutionPlan`, de modo que ninguna entrada programática
pueda saltarla.

`plan-replace` y `apply-replace` producen el mismo `BLOCKED/REPLACEMENT_MAPPING_INVALID` para una
capacidad reservada al Corte 7; ninguno puede presentar un split/merge como `APPLICABLE`,
`ALREADY_APPLIED` ni aplicado.

### Writers de mutación

`LegalEditorialMutationWriter` define la frontera interna mínima para aplicar un plan mutante. Los
writers no son autoridad de replay ni construyen receipts. `LegalInitialPromotionCore` conserva el
comportamiento de `PROMOTE` mediante esa frontera o un adaptador sin alterar el orden acreditado de
su SQL.

`LegalDocumentReplacementWriter`:

- acepta sólo planes `REPLACE` dentro del alcance del Corte 6;
- acredita y bloquea el grafo exacto en orden UUID determinista;
- ejecuta únicamente comandos directos;
- deja al trigger de sello V27 materializar reemplazos y slots derivados;
- nunca escribe snapshots sellados.

### Verificador de postestado

`LegalEditorialPostStateVerifier` compara el estado real con `ExpectedPostState`:

- estados y timestamps de todas las versiones clasificadas;
- historia completa preexistente, transiciones directas del cutover y efectos V27 derivados para
  esas versiones, sin mezclarlas ni contar sólo el delta;
- lote, miembros y sello;
- conjunto global de slots actuales exactamente igual a la proyección target y ligado al
  `publicationId` target;
- conjunto global de punteros actuales exactamente igual al target, con IDs de snapshot y
  revisiones esperadas;
- ausencia de lotes, miembros, transiciones, slots o punteros extra dentro de ese alcance exacto;
- contadores totales de la proyección target para el receipt.

`READY` es necesario, pero no suficiente. Sólo la igualdad del postestado y readiness final permite
confirmar el commit. Los conteos del receipt son totales del postestado target y no el delta del
cutover; el delta permanece separado en el resultado de plan. Las retiradas de versiones source se
verifican aunque no formen parte de esos conteos target.

El verificador es la única autoridad para construir receipts frescos y de replay.
`LegalEditorialExecutionPlan` separa `observedAt` de `expectedAppliedAt`: el primero siempre es el
`transaction_timestamp` de la observación actual; el segundo es el instante histórico acreditado
del cutover. En una ejecución fresca ambos coinciden. En replay, `expectedAppliedAt` se infiere de
evidencia uniforme del postestado —punteros target, transiciones nuevas y sello cuando existan— y
puede ser anterior a `observedAt`. El verificador exige esa uniformidad y usa exclusivamente
`expectedAppliedAt` como `receipt.appliedAt`; nunca calcula `max()` del historial ni usa un timestamp
nuevo. Los punteros target permiten acreditar también un cutover sin lote o compuesto sólo por
reutilizaciones.

### CLI v3

`LegalEditorialArguments` incorpora `plan-replace` y `apply-replace` con exactamente:

- `--manifest`;
- `--editorial-plan`;
- `--confirm-publication-id`;
- `--confirm-manifest-sha256`;
- `--confirm-operation-id`;
- `--confirm-editorial-plan-sha256`.

El preflight conserva el orden congelado: argumentos, bundle, plan, confirmaciones del bundle,
tipo de operación, `operationId` y SHA del plan. Sólo después se resuelven variables editoriales y
se abre Spring/JDBC. `apply-replace` exige el flag operativo; `plan-replace` no escribe.

`LegalEditorialCliExecutionState` retiene de manera monotónica la identidad validada del plan y si
la operación llegó a invocarse. El reporte v3 usa `operationType=REPLACE`; los reportes v1/v2 no
cambian.

La matriz de identidad v3 queda cerrada así:

- antes de validar y confirmar el plan, `plan=null`;
- después de validarlo y confirmarlo, `plan.operationId` y `plan.editorialPlanSha256` se conservan
  en `plan-replace`, `apply-replace`, `BLOCKED`, `ERROR` y `UNKNOWN`;
- `changeRequired` y `observedAt` sólo aparecen para un plan `APPLICABLE` acreditado;
- `expectedReadinessAfter=READY` puede conservarse porque pertenece al input validado;
- en apply fallido o `UNKNOWN`, `publicationUuid`, `appliedAt`, readiness confirmado, conteos de
  estado y cualquier receipt derivado de DB permanecen null.

V27 no persiste `operationId` ni SHA del plan. Esos dos valores son identidad y confirmación externa
del input, no prueba histórica de autoría. `ALREADY_APPLIED` se acredita exclusivamente por el
postestado exacto.

## Flujo transaccional

1. Validar bundle, plan y seis argumentos exactos sin resolver credenciales.
2. Ejecutar el guard puro de capacidad Corte 6; un rechazo termina sin entorno, Spring ni JDBC.
3. Abrir el contexto JDBC aislado y ejecutar schema/privilege preflights exactos.
4. Entrar al gate mutable, repetir defensivamente el guard y adquirir el advisory lock compartido.
5. Leer una sola vez `transaction_timestamp()`.
6. Derivar el plan y comparar primero el postestado completo.
7. Si el postestado ya existe, releer su receipt exacto y devolver `ALREADY_APPLIED` sin DML ni
   avance de secuencias.
8. Si no existe, acreditar target `SELLADO` y fingerprint fuente.
9. Prebloquear publicaciones, líneas y versiones en orden UUID determinista. Bajo el advisory lock,
   releer slots y punteros actuales en orden de PK, sin `FOR UPDATE/SHARE`, y revalidar el estado
   fuente completo antes del primer DML.
10. Publicar versiones documentales y de requisito nuevas.
11. Eliminar todos los punteros actuales observados, en orden de PK y mediante clave más
    `conjunto_id` esperado, exigiendo cardinalidad exacta.
12. Activar adiciones documentales e insertar sus slots.
13. Si existe lote, insertar `ABIERTO`, predecesora y sucesora y sellarlo.
14. No duplicar transiciones ni slots producidos por el trigger del sello.
15. Borrar slots de documentos salientes y llevarlos a `RETIRADA` con su motivo.
16. Aplicar transiciones de requisitos nuevos, reemplazados y retirados.
17. Rebind de cada documento reutilizado mediante `DELETE` más `INSERT` con el target.
18. Insertar únicamente los punteros target hacia snapshots sellados existentes.
19. Verificar el postestado exacto y construir el receipt todavía antes de forzar constraints.
20. Ejecutar `SET CONSTRAINTS ALL IMMEDIATE`.
21. Después de constraints, ejecutar únicamente `ReadinessCore` en la misma sesión y con el
    timestamp ya leído.
22. Confirmar sólo cuando el target queda exactamente `READY`.

Después de forzar constraints no se permite más DML.

## Manejo de errores

Se usan resultados tipados para condiciones esperables y excepciones sólo dentro de la frontera que
puede clasificarlas con autoridad:

- precondición o estado de dominio incompatible: `BLOCKED`, `persisted=false`, exit 2;
- fallo operativo con rollback confirmado: `ERROR`, `persisted=false`, exit 3;
- commit confirmado: `APPLIED` o `ALREADY_APPLIED`, `persisted=true`, exit 0;
- finalización no acreditable: `UNKNOWN`, `persisted=null`, exit 3.

`UNKNOWN` conserva el `operationId` y SHA validados del input, pero no expone UUID derivados de DB,
timestamps, deltas ni receipt tentativos. Un fallo de stdout no genera un segundo envelope ni
altera la realidad transaccional. El retry exacto vuelve a comparar el postestado; nunca usa un
nuevo `operationId` para eludir incertidumbre.

Un `ALREADY_APPLIED` o `BLOCKED` previo a DML no avanza las cuatro secuencias editoriales. Un
rollback tardío puede dejar huecos normales de PostgreSQL y no se interpreta como persistencia
parcial.

## Pruebas de cierre

### Unitarias

- parser estricto, segunda ruta y cuatro confirmaciones;
- preflight completo antes de entorno/JDBC;
- `plan-replace` nunca escribe y `apply-replace` exige flag;
- máximo un lote y cardinalidad 1→1;
- orden de comandos del writer;
- prohibición de duplicar efectos V27;
- comparación exacta del postestado;
- historia completa frente a delta del cutover y conteos target del receipt;
- `appliedAt` uniforme para ejecución fresca, replay mixto y replay sin lote, incluyendo
  `observedAt != appliedAt`;
- matrices de report, receipt, `persisted`, exit y redacción;
- regresión byte a byte de v1/v2 y `PROMOTE`.

### PostgreSQL 16

- cutover combinado con lote 1→1, adición, recuperación de gap, reuse con rebind y retiro;
- requisitos nuevos, reutilizados, reemplazados y retirados con todas sus audiencias;
- cutover válido sin lote, incluido uno compuesto sólo por reemplazos de requisitos;
- replay exacto con cero DML y cero avance de secuencias;
- fingerprint fuente distinto, lote ajeno y postestado parcial;
- target inicialmente `NOT_READY` que converge a `READY`; el readiness inicial no bloquea por sí
  solo;
- postcondición que permanece `NOT_READY`, inyectada después del delta, con rollback completo;
- rechazo de más de un lote, split y merge antes de DML;
- rollback por fallo después del sello, después de insertar punteros, en el verificador de
  postestado, en `SET CONSTRAINTS` y en readiness;
- fallo de frontera de commit sin falso `APPLIED` ni falso rollback conocido;
- ausencia de cambios en snapshots sellados y tablas fuera de la superficie editorial;
- regresión de importación, readiness y privilegios mínimos.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialReplaceScopeGuardTest,LegalEditorialMutationWriterTest,LegalEditorialPostStateVerifierTest,LegalEditorialReplaceServiceTest,LegalEditorialArgumentsTest,LegalEditorialCliExecutionStateTest,LegalEditorialCliTest,LegalEditorialReportTest,LegalEditorialApplyServiceTest,LegalInitialPromotionCoreTest test
./mvnw -Dit.test=LegalEditorialReplaceIT,LegalEditorialReadinessIT,LegalEditorialPrivilegeVerifierIT,LegalManifestImportIT verify
./mvnw test
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

La concurrencia multithread, capacidad y procesos reales se acreditan en el Corte 10. Este corte sí
conserva el advisory lock compartido y cubre replay, rollback y frontera de commit de forma
determinista. Antes del commit funcional se revisa además el diff completo. No se hace push.

## Criterio de cierre

El Corte 6 queda cerrado cuando `plan-replace` y `apply-replace` operan con el contrato v3, el
cutover permitido converge a `READY`, el replay es exacto y no mutante, todos los fallos conservan
una clasificación autoritativa y las regresiones de `PROMOTE`, import y host permanecen verdes.
