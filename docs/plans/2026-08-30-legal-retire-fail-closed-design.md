# Corte 8 — Diseño de retiro explícito fail-closed

Fecha: 2026-08-30

Estado: aprobado por el usuario; implementación pendiente

Rama backend: `codex/lanzamiento-publico-backend`

Continuidad:

- Fase 2.3C: `docs/plans/2026-08-27-legal-manifest-promotion-design.md`;
- plan maestro: `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`;
- cierre Corte 7: `docs/plans/2026-08-30-legal-split-merge-implementation.md`;
- último commit previo al Corte 8: `1eb5445`.

## Decisión

Completar la operación `RETIRE` como herramienta editorial interna y de emergencia. El operador
enumera de forma explícita las versiones documentales y de requisitos que deben quedar
`RETIRADA`, aporta un motivo por elemento y reconoce deliberadamente que el resultado esperado es
`NOT_READY`.

La operación queda fuera del producto web. No se crea endpoint, controller, DTO HTTP, botón,
pantalla frontend ni permiso para `ADMIN` o `USER` de un taller. Tampoco se incorpora un scheduler,
un flag `force`, un prompt interactivo o inferencia de retiros por ausencia.

Un apply fresco exacto devuelve `APPLIED`, `persisted=true`, readiness `NOT_READY` y exit code 0.
Un postestado ya exacto devuelve `ALREADY_APPLIED` con la misma semántica de éxito y sin DML. Que
el resultado quede deliberadamente no listo es la razón por la que el plan exige
`acknowledgeFailClosedGap=true`.

V27 no contiene un ledger editorial de operaciones o receipts y el Corte 8 no agrega uno.
`persisted=true` acredita que el postestado editorial está confirmado en PostgreSQL; no significa
que se haya insertado una fila con `operationId`, SHA o el receipt. El verifier construye el
receipt determinista para la respuesta y el reporte.

## Estado de partida

Antes de implementar el Corte 8 ya existen:

- modelo, schema, parser y golden files del plan editorial con operación `RETIRE`;
- validación de `target=current`, motivos, acknowledgement y
  `expectedReadinessAfter=NOT_READY`;
- `LegalEditorialPlannerCore.planRetire` y `LegalEditorialPlanService.planRetire`;
- comandos de transición y eliminación derivados por el planner;
- representación `RETIRE` en execution plan, receipt y post-verifier;
- transiciones V27 `VIGENTE→RETIRADA`, triggers de invalidación y constraints terminales;
- permisos suficientes del rol editorial restringido para insertar transiciones y eliminar slots
  y punteros.

No obstante, el postestado actual de RETIRE contiene sólo los elementos retirados y modela slots,
punteros y lotes como vacíos. Esa forma sólo podría coincidir si se retirara el universo completo;
un retiro parcial omite membresía, historia y proyecciones que deben sobrevivir. Además, el apply
service no expone `applyRetire`, el writer REPLACE rechaza esta operación y la CLI/reportería v3 no
la despachan.

## Alternativas evaluadas

### A. Writer RETIRE dedicado y coordinador compartido — elegida

Crear `LegalEditorialRetirementWriter` para la revalidación bajo lock y el DML propio del retiro,
mientras `LegalEditorialApplyService` conserva la coordinación común de replay, writer,
post-verifier, constraints, readiness y completion state.

Ventajas:

- protege el writer REPLACE ya acreditado;
- mantiene explícita la diferencia `READY` frente a `NOT_READY`;
- limita la superficie DML a deletes y transiciones terminales;
- permite probar el retiro parcial sin agregar branches a un writer con otro contrato.

### B. Generalizar el writer REPLACE — descartada

Renombrar y convertir el writer actual en un executor común reduciría duplicación de locks y
lecturas. A cambio, ampliaría una pieza estable que hoy exige target distinto, postestado `READY`,
lotes y proyecciones de sucesoras. El riesgo de regresión sobre los Cortes 6 y 7 supera el ahorro.

### C. Agregar branches RETIRE al writer REPLACE — descartada

Es la opción con menos archivos nuevos, pero deja una clase cuyo nombre y precondiciones dejan de
describir su responsabilidad. Los branches afectarían orden DML, cardinalidades, target y
readiness, haciendo más difícil acreditar ambos caminos.

## Fronteras semánticas

1. `currentPublicationId` y `targetPublicationId` son el mismo UUID exacto.
2. Sólo se retiran versiones enumeradas en el plan validado.
3. Cada versión tiene contexto, audiencia cuando corresponde, digest y motivo exactos.
4. Toda versión enumerada pertenece a la publicación actual y está `VIGENTE` antes del delta.
5. Una versión terminal nunca se revive ni se vuelve a transicionar.
6. RETIRE no agrega, reemplaza, reutiliza ni rebinds versiones.
7. RETIRE no crea, abre ni sella lotes de reemplazo.
8. El postestado esperado es `NOT_READY`; cualquier otro readiness revierte la operación.
9. El acknowledgement es obligatorio y no existe una forma equivalente implícita.
10. Un plan parcial no se completa ni se expande a partir del estado observado.

La operación no acredita aprobación jurídica de los textos ni autoriza lanzamiento público. Es una
medida editorial de contención para retirar contenido vigente cuando continuar sirviéndolo sería
peor que dejar la proyección legal deliberadamente incompleta.

## Postestado exacto de un retiro parcial

`ExpectedPostState` continúa representando el estado final completo, no sólo el delta. No se
agregan colecciones paralelas de proyecciones preservadas; las validaciones se vuelven conscientes
del tipo de operación.

El planner debe producir:

- la membresía documental y de requisitos completa de la publicación actual;
- el estado final y la historia completa de todas las versiones clasificadas;
- las versiones enumeradas con una única transición nueva a `RETIRADA`;
- todas las demás versiones con estado e historia sin cambios;
- slots no afectados con clave, versión, línea y publicación exactas;
- punteros no afectados con scope, snapshot, revisión y `updatedAt` originales;
- ausencia de cada slot documental afectado;
- ausencia de la unión exacta de punteros afectados por documentos o requisitos retirados;
- todos los lotes históricos ya relacionados, sin cabecera o membresía nueva.

Las claves esperadas de slots y punteros sobrevivientes se derivan del grafo autoritativo e
inmutable de publicación, membresía, versiones y snapshots. Las proyecciones mutables actuales se
usan sólo después de comprobar que cada clave esperada existe una vez, no hay extras y sus valores
son coherentes. Recién entonces se transporta al postestado el `updatedAt` existente de cada
puntero sobreviviente.

Para los punteros, la evidencia autoritativa de cada scope incluye miembros del conjunto sellado y
versiones documentales referenciadas. Así la unión afectada se deriva igual antes y después del
retiro aunque los punteros eliminados ya no estén presentes.

No se construye el esperado copiando ciegamente `activeSlots` o `activePointers`: si una
proyección sobreviviente falta, la planificación se bloquea. De otro modo un replay podría omitir
del esperado la misma corrupción que debería detectar.

## Invariantes del execution plan

Para `RETIRE`, las colecciones finales de slots y punteros pueden ser no vacías porque describen
proyecciones preservadas. No implican inserts del delta ni deben tener
`updatedAt == expectedAppliedAt`.

La consistencia del plan exige:

- cada versión retirada tiene una transición directa y un motivo exacto;
- cada slot afectado tiene un delete y no aparece en el postestado;
- cada puntero afectado aparece una sola vez en la unión de deletes y no en el postestado;
- cada proyección preservada aparece en el postestado, pero no en comandos de insert/delete;
- no existen inserts de slots, punteros, versiones, snapshots o lotes;
- `MutationCommands` y `V27TriggerEffects` describen únicamente el delta actual;
- la historia y los lotes históricos permanecen separados del delta;
- `observedAt` describe la lectura actual y `expectedAppliedAt` el instante transaccional del
  retiro fresco.

Las transiciones nuevas usan `expectedAppliedAt`. Los timestamps de punteros preservados conservan
su valor histórico y no se normalizan al instante del retiro.

## Planificación y replay

`plan-retire` es estrictamente read-only. Valida bundle, plan, tipo, identidad y confirmaciones
antes de resolver credenciales o abrir JDBC. Dentro del gate read-only toma el advisory lock,
observa una vista coherente y:

1. intenta acreditar primero el postestado exacto;
2. si no existe, acredita publicación current/target, grafo y source fingerprint;
3. valida que cada miembro explícito sea vigente y esté dentro del scope declarado;
4. calcula deletes, transiciones y postestado completo;
5. emite un execution plan determinista sin escritura.

El replay de base de datos continúa acreditando estado, no autoría del `operationId`. Otra
identidad externa que describa exactamente el mismo postestado puede obtener
`ALREADY_APPLIED`, como en REPLACE, sin afirmar qué plan histórico causó el estado. En cambio, las
confirmaciones CLI deben coincidir exactamente con el archivo presentado en esa invocación; un
UUID o SHA confirmado que no coincida se bloquea antes de JDBC.

No se persisten el `operationId`, el SHA del plan ni el receipt. Su unicidad histórica pertenece a
la disciplina operativa externa hasta que una migración posterior diseñe y apruebe un ledger.

Esta frontera impone una limitación epistémica explícita: un apply fresco sí demuestra que los
timestamps de proyecciones sobrevivientes no cambiaron dentro de su transacción. Un replay
posterior detecta claves faltantes o extra y valores derivables incoherentes, pero no puede probar
por sí solo que alguien no alteró únicamente un timestamp mutable sobreviviente después del
commit. Acreditar esa historia exigiría persistir evidencia en una migración posterior; no se
sobrepromete en V27.

Un plan diferente que prediga otro postestado no se trata como replay: la terminalidad de las
versiones o la diferencia de fingerprint lo bloquea sin DML.

## Flujo transaccional de apply

1. Validar manifest, plan, operación, binding y confirmaciones antes del entorno/JDBC.
2. Abrir el único gate mutable `REQUIRES_NEW`, `READ_COMMITTED` y adquirir el advisory lock.
3. Leer una vez `transaction_timestamp()`.
4. Acreditar primero un postestado exacto para replay.
5. Si es fresco, recalcular el plan y source fingerprint dentro de la transacción.
6. Entregar el execution plan `RETIRE` al writer dedicado.
7. Bloquear publicación, líneas, versiones y proyecciones en orden determinista.
8. Revalidar membresía, estados, fingerprint, slots, punteros y ausencia de comandos ajenos.
9. Eliminar la unión exacta de punteros afectados.
10. Eliminar los slots documentales afectados.
11. Insertar transiciones de requisitos `VIGENTE→RETIRADA` en orden UUID.
12. Insertar transiciones documentales `VIGENTE→RETIRADA` en orden UUID.
13. Exigir cardinalidad exacta de cada batch JDBC.
14. Acreditar el postestado completo mediante el verifier común.
15. Ejecutar `SET CONSTRAINTS ALL IMMEDIATE`.
16. Calcular readiness dentro de la misma transacción y exigir exactamente `NOT_READY`.
17. No ejecutar más DML y permitir el commit.

Los deletes se anticipan a los triggers V27. Los triggers permanecen como defensa de integridad;
si invalidan una proyección adicional por una inconsistencia no prevista, el post-verifier o las
constraints detectan la diferencia y la transacción revierte.

## Writer dedicado

`LegalEditorialRetirementWriter` implementa la frontera común de mutation writer, pero acepta sólo
un execution plan `RETIRE`. Antes del primer DML comprueba:

- operación, current/target y readiness esperada;
- acknowledgement explícito;
- ausencia de lotes, sucesoras, inserts y comandos REPLACE/PROMOTE;
- correspondencia exacta entre miembros retirados, motivos, deletes y transiciones;
- fingerprint y grafo observados bajo los locks de la misma sesión JDBC.

El writer no decide replay, no construye receipts, no fuerza constraints y no consulta readiness.
Esas responsabilidades permanecen en el coordinador y el verifier compartidos.

## Resultados y manejo de errores

- éxito fresco: `APPLIED`, `persisted=true`, `NOT_READY`, exit code 0;
- replay acreditado: `ALREADY_APPLIED`, `persisted=true`, `NOT_READY`, exit code 0, sólo SELECT;
- plan o confirmación inválidos: `BLOCKED`, antes de JDBC cuando la evidencia es input-safe;
- fingerprint, scope o estado fuente incompatibles: `BLOCKED`, sin healing;
- acknowledgement ausente: issue estable `FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED`;
- readiness esperado en el input distinto de `NOT_READY`:
  `BLOCKED/EXPECTED_READINESS_MISMATCH` antes de DML;
- readiness real posterior distinto de `NOT_READY` o postcondición no exacta:
  `ERROR/POSTCONDITION_NOT_READY`, rollback y `persisted=false`;
- fallo SQL, verifier o constraints con rollback confirmado: `ERROR`, `persisted=false`;
- commit cuya finalización no puede acreditarse: `UNKNOWN`, `persisted=null`, sin receipt
  tentativo.

Se conserva la taxonomía tipada existente y se corrigen bindings demasiado genéricos sólo cuando
la prueba demuestre que ocultan una causa estable. No se agregan retries automáticos, fallback a
otra operación, reparación de proyecciones, excepción ignorada ni receipt parcial.

## CLI y reportes

La superficie interna incorpora únicamente:

- `plan-retire`;
- `apply-retire`.

Ambos reutilizan el parser estricto de manifest, plan y las mismas cuatro confirmaciones
editoriales. `apply-retire` sólo se habilita con el flag operativo existente
`ORDENFIX_LEGAL_EDITOR_ENABLED=true`; no agrega un quinto token. No hay aliases, argumentos
separados, defaults destructivos ni prompts.

El reporte v3 admite `operationType=RETIRE` y `expectedReadinessAfter=NOT_READY`. Para éxito fresco
o replay conserva identidad input-safe, receipt exacto y `persisted=true`. Los fallos no exponen
rutas, credenciales, variables de entorno, SQL ni metadata tentativa derivada de una transacción
no confirmada. Los reportes v1/v2 y las operaciones PROMOTE/REPLACE permanecen compatibles.

## Seguridad y límites de persistencia

No modificar:

- V27 ni crear V28;
- schemas JSON del manifest o del plan;
- grants, roles, inventarios de privilegios o funciones SQL;
- endpoints, JPA, frontend o permisos de usuarios de talleres;
- contenido legal real, deploy o configuración pública.

El rol editorial actual ya posee la superficie mínima: INSERT en transiciones, DELETE en slots y
punteros, SELECT de evidencia y uso de las secuencias involucradas. Las funciones V27 siguen como
`SECURITY INVOKER` y el advisory lock serializa el job offline con los writers cooperativos.

## Estrategia de pruebas

### Unitarias

- retiro sólo documental, sólo de requisitos y mixto;
- publicación con múltiples miembros, contextos y audiencias no afectados;
- membresía, historia, lotes, slots, punteros y `updatedAt` de punteros preservados;
- ausencia o exceso de una proyección sobreviviente bloquea y no se normaliza;
- invariantes operation-aware de execution plan;
- orden SQL y parámetros exactos del writer;
- target distinto, motivo vacío, acknowledgement falso y readiness distinto con cero DML;
- replay select-only y receipt exacto;
- regresión de PROMOTE y REPLACE.

### PostgreSQL 16 / Flyway V27

- apply fresco documental, de requisitos y mixto con rol restringido;
- efectos de triggers, motivos, historias y timestamps exactos;
- preservación completa de miembros y proyecciones no afectadas;
- replay exacto y con otra identidad externa, sin cambios de filas o secuencias;
- corrupción extra o faltante sin healing;
- fallo después de DML y antes de commit con rollback fila por fila de las 19 tablas editoriales;
- documentación explícita de los huecos esperados en secuencias PostgreSQL, que no son
  transaccionales;
- regresión de privilegios, aislamiento, readiness, PROMOTE, REPLACE e import.

### CLI y proceso

- argumentos estrictos de `plan-retire` y `apply-retire`;
- preflight completo antes de entorno/Spring/JDBC;
- el scope guard REPLACE no intercepta RETIRE;
- JSON v3 para `APPLIED`, `ALREADY_APPLIED`, `BLOCKED`, `ERROR` y `UNKNOWN`;
- exit 0 para los dos éxitos deliberadamente `NOT_READY` y no cero para fallos;
- JAR sin secretos y launcher con sintaxis válida.

## Subcortes aprobados

1. **8A — Invariantes del plan.** Admitir postestado preservado sin confundirlo con comandos del
   delta y mantener el verifier global exacto.
2. **8B — Postestado parcial exacto.** Derivación autoritativa y preservación de todo miembro,
   historia, lote y proyección no afectado.
3. **8C — Writer y aplicación.** Writer dedicado, `applyRetire` y postcondición `NOT_READY`.
4. **8D — PostgreSQL fresco.** Documentos, requisitos, mixto y rol restringido.
5. **8E — Replay, corrupción y rollback.** Cero DML en replay, no healing y atomicidad exacta.
6. **8F — CLI y reportes.** Comandos internos, reporte v3 y códigos de salida.
7. **8G — Regresiones y cierre.** Matriz amplia, documentación y evidencia final.

Cada subcorte comienza por pruebas, termina con `git diff --check` y se registra en un commit local
atómico. No se hace push ni deploy.

## Criterio de cierre

Corte 8 queda cerrado cuando un retiro documental, de requisitos o mixto puede aplicarse con el
rol editorial exacto y terminar deliberadamente `APPLIED+NOT_READY`; el replay exacto es
select-only; todo dato no afectado permanece byte por byte equivalente; la corrupción no se
repara; los fallos revierten toda la operación; PROMOTE y REPLACE permanecen verdes; y la única
superficie nueva es la CLI operativa interna.

Corte 8 no cierra por sí solo la Fase 2.3C ni habilita producción pública. Reconciliación ampliada
de `UNKNOWN`, capacidad/procesos exhaustivos y cierre cross-repo permanecen en los Cortes 9 a 11.
