# Corte 7 — Diseño de reemplazos split, merge y lotes disjuntos

Fecha: 2026-08-30

Estado: aprobado por el usuario el 2026-08-30; pendiente de implementación

Rama backend: `codex/lanzamiento-publico-backend`

Continuidad:

- Fase 2.3C: `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`;
- diseño REPLACE base: `docs/plans/2026-08-29-legal-replace-cutover-design.md`;
- cierre Corte 6: commit local `b4ff4e8`.

## Decisión

Extender el pipeline REPLACE existente para admitir, dentro de una única operación:

- lotes `1→1` ya soportados;
- lotes `1→N`;
- lotes `N→1`;
- cero, uno o múltiples lotes actuales totalmente disjuntos.

Un lote `N→M`, con ambos lados mayores que uno, permanece fuera de alcance y se bloquea con
`REPLACEMENT_MAPPING_INVALID`. V27 lo podría ejecutar, pero habilitarlo sin una decisión explícita
ampliaría el contrato aprobado.

La implementación será evolutiva y dentro de los componentes actuales. No se crea
`LegalReplacementBatchExecutor` salvo que una prueba demuestre una frontera que el writer actual no
pueda expresar de forma segura.

## Estado actual acreditado

El schema v1, `LegalEditorialPlanValidator`, `LegalEditorialPlannerCore`,
`LegalEditorialExecutionPlan`, `LegalEditorialPostStateVerifier` y V27 ya representan múltiples
lotes y miembros. El writer ya inserta todas las cabeceras y membresías antes de sellar lote por
lote.

Las dos fronteras que todavía conservan el límite del Corte 6 son:

1. `LegalEditorialReplaceScopeGuard`, que sólo acepta cero lotes o uno `1→1`;
2. la defensa interna de `LegalDocumentReplacementWriter`, que repite ese límite.

Existe además un drift de orden: `LegalEditorialExecutionPlan` vuelve a ordenar lotes por `batchId`
aunque el validator los normaliza por el UUID mínimo de sus miembros.

No existe hoy una integración PostgreSQL positiva de `1→N`, `N→1` o múltiples lotes disjuntos.

## Alternativas evaluadas

### A. Evolución in-place — elegida

Ampliar el guard, la defensa del writer y el orden canónico. Mantener planner, apply, verifier,
transaction manager y V27 como autoridades actuales.

Ventajas:

- mínimo cambio productivo;
- conserva replay, completion-state y rollback ya acreditados;
- no agrega otra autoridad JDBC;
- permite abrir la capacidad en un subcorte posterior al soporte interno.

### B. Extraer un executor de lotes

Separar apertura, membresías y sellado en una clase nueva. Mejora el aislamiento nominal, pero el
método actual ya tiene esa responsabilidad acotada. Agregaría wiring, pruebas de sesión compartida
y riesgo de duplicar autoridad sin resolver un problema presente.

### C. Generalizar a un grafo `N→M`

Crear un compilador de topología que abarque toda cardinalidad admitida por V27. Facilitaría una
capacidad futura, pero amplía el producto y el espacio de replay/concurrencia fuera del Corte 7.

## Contrato de capacidad

Para cada lote actual:

1. predecesores y sucesoras son no vacíos;
2. `predecessors.size() == 1 || successors.size() == 1`;
3. todos los miembros comparten exactamente tipo y locale;
4. cada contexto aparece una sola vez por lado;
5. la unión de contextos predecesores y sucesores es idéntica a `batch.contexts`;
6. no hay autociclo;
7. una versión no participa en dos lotes actuales ni en dos roles operativos;
8. todas las sucesoras pertenecen a la publicación target.

Las formas estructurales inválidas se rechazan antes de resolver credenciales o abrir JDBC. Tipo,
locale, contextos y pertenencia real necesitan el snapshot acreditado: se bloquean después de la
lectura, pero antes de DML y sin consumo de secuencias.

El límite existente continúa en 128 lotes, 128 documentos por lado y ocho contextos. La semántica
de contextos de V27 limita en la práctica la cardinalidad útil por lado.

## Orden canónico

Todos los lugares que transportan lotes actuales e históricos usan el mismo orden:

1. UUID mínimo entre predecesores y sucesoras, comparado por representación textual;
2. `replacementBatchId` como desempate.

Dentro de cada lote, predecesores y sucesoras continúan ordenados por UUID. El ID de lote es
caller-generated y forma parte del postestado, pero no acredita autoría del `operationId`.

Este orden reemplaza el orden accidental por `batchId` de `LegalEditorialExecutionPlan` y gobierna
la secuencia de sellado del writer.

## Flujo transaccional

1. Validar plan y capacidad antes del entorno/JDBC.
2. Abrir el único gate mutable y adquirir el advisory lock editorial.
3. Leer una sola vez `transaction_timestamp()`.
4. Comparar primero el postestado exacto para replay.
5. Si falta, acreditar target, source fingerprint y mapping completo.
6. El writer prebloquea globalmente publicaciones, líneas y todas las versiones en orden UUID.
7. Relee membresía target, slots, punteros, headers/revisiones de scopes y ausencia de todos los
   lotes nuevos. La integridad completa de snapshots permanece a cargo del planner/origin verifier,
   la inmutabilidad del target sellado y el post-verifier.
8. Ejecuta el delta directo previo a lotes.
9. Inserta todas las cabeceras `ABIERTO`.
10. Inserta todos los predecesores y todas las sucesoras de todos los lotes.
11. Sella los lotes uno por uno en orden canónico.
12. V27 produce las transiciones y slots derivados; Java no los duplica.
13. Completa requisitos, rebinds y punteros.
14. El verificador acredita el postestado completo.
15. Fuerza constraints y ejecuta readiness sin DML posterior.
16. Sólo un postestado exactamente `READY` puede confirmarse.

No existe mini-commit por lote. Un fallo durante o después del último sello revierte cabeceras,
membresías, transiciones, slots, punteros y todos los efectos de lotes anteriores de esa operación.

## Historia y replay

Cada cabecera nace `ABIERTO` y sólo admite la transición `ABIERTO→SELLADO`; después del sello queda
inmutable. Membresías y transiciones son append-only. Una versión puede haber sido sucesora de un
lote histórico y predecesora de otro posterior, pero no puede ocupar dos roles en el delta actual.

El replay compara:

- todos los lotes actuales y los históricos relacionados con las versiones clasificadas;
- membresías exactas;
- estados e historia completa;
- timestamps uniformes del delta;
- slots y punteros target;
- readiness y conteos del receipt.

Un replay exacto devuelve `ALREADY_APPLIED`, ejecuta sólo SELECT y no avanza secuencias. Cambiar
`operationId` o el SHA externo no cambia autoría histórica: la base sólo acredita el postestado.

## Manejo de errores

- mapping o capacidad inválida: `BLOCKED/REPLACEMENT_MAPPING_INVALID`, antes de JDBC cuando la
  evidencia es puramente estructural;
- source, fingerprint o postestado incompatibles: `BLOCKED`, antes de DML;
- fallo SQL/verifier/constraints/readiness con rollback confirmado: `ERROR`, `persisted=false`;
- finalización de commit no acreditable: `UNKNOWN`, `persisted=null`;
- éxito fresco o replay acreditado: `APPLIED`/`ALREADY_APPLIED`, `persisted=true`.

No se agregan retries automáticos, fallbacks que oculten corrupción ni hooks productivos de fallo.

## Seguridad y límites de superficie

No modificar:

- V27 ni crear V28;
- schema JSON del plan o manifest;
- grants, roles o inventarios de privilegios;
- CLI, reporte v1/v2/v3 o exit codes;
- endpoints, JPA, frontend, aceptación, contenido legal o deploy.

El rol editorial restringido actual ya posee exactamente el DML y las secuencias requeridas. Las
funciones V27 siguen siendo `SECURITY INVOKER` y el advisory lock conserva la serialización global
del job editorial offline.

## Estrategia de pruebas

### Unitarias

- guard: acepta cero, `1→1`, `1→N`, `N→1` y múltiples lotes; bloquea `N→M` y otra operación;
- validator: vacíos, duplicados, autociclo, overlap cross-batch y normalización;
- execution plan: orden por miembro mínimo, miembros ordenados y disjunción;
- planner: source/poststate/replay de split, merge y multibatch; tipo/locale/contextos inválidos;
- writer: todas las cabeceras y membresías antes del primer sello, sellos en orden canónico y
  defensa contra `N→M`;
- apply/verifier: guard antes de JDBC, receipt completo y postestado extra/faltante.

### PostgreSQL 16

- split `1→N` fresco con rol restringido;
- merge `N→1` fresco con rol restringido;
- un split y un merge disjuntos en la misma operación;
- replay exacto y con otra identidad externa, sin DML ni secuencias;
- rollback por fallo al sellar el lote `k`, después de que `k-1` produjo efectos V27 y antes de
  completar los sellos restantes;
- lote, miembro, transición o slot extra/faltante sin healing;
- regresión Corte 6, PROMOTE, readiness, import, privilegios y concurrencia.

## Subcortes

1. **7A — soporte interno y orden canónico**, con el guard externo todavía cerrado;
2. **7B — apertura controlada del guard y planner**;
3. **7C — PostgreSQL fresco split, merge y multibatch**;
4. **7D — replay, corrupción y rollback integral**;
5. **7E — regresiones, evidencia y cierre documental**.

Cada subcorte termina en un commit local atómico. No se hace push ni deploy.

## Criterio de cierre

Corte 7 queda cerrado cuando `1→N`, `N→1` y múltiples lotes disjuntos convergen a `READY` con el
rol exacto, `N→M` permanece fail-closed, replay es no mutante, un fallo multibatch revierte toda la
operación y las regresiones de Corte 6/V27 permanecen verdes.
