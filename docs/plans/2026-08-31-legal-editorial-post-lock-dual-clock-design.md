# Corte 10A.1 — Diseño de frontera temporal editorial post-lock

Fecha: 2026-08-31

Estado: aprobado

Rama backend: `codex/lanzamiento-publico-backend`

Documento padre:

- `docs/plans/2026-08-31-legal-editorial-concurrency-capacity-process-design.md`;
- `docs/plans/2026-08-31-legal-editorial-concurrency-capacity-process-implementation.md`.

## Contexto

La acreditación de concurrencia del Corte 10A encontró una carrera real entre dos `PROMOTE`
idénticos. Las dos transacciones comienzan antes del advisory lock. Si la transacción más antigua
queda detenida en preflight y una transacción posterior aplica primero, la antigua ve el commit
ganador bajo `READ COMMITTED`, pero conserva como `observedAt` su
`transaction_timestamp()` anterior. El replay infiere el `appliedAt` confirmado por la ganadora y
`LegalEditorialExecutionPlan` rechaza `expectedAppliedAt > observedAt`. El resultado público se
degrada a `EDITORIAL_OBSERVATION_FAILED` en lugar de converger a `ALREADY_APPLIED`.

No es válido reemplazar ciegamente `transaction_timestamp()` por `statement_timestamp()` o
`clock_timestamp()`. V27 prohíbe transiciones y sellos posteriores al inicio de la transacción.
Además, los writers frescos usan el instante del plan como tiempo auditable. Un reloj único
post-lock convertiría una operación válida en un `23514`.

PostgreSQL define `transaction_timestamp()` como el inicio de la transacción,
`statement_timestamp()` como el inicio de la sentencia actual y `clock_timestamp()` como un valor
que puede cambiar dentro de una misma sentencia:

- https://www.postgresql.org/docs/16/functions-datetime.html

## Objetivo

Separar el tiempo lógico exigido por V27 del tiempo real de observación posterior al advisory
lock, de modo que:

1. una mutación fresca conserve timestamps compatibles con V27;
2. readiness, plan, apply y reconciliación observen un instante posterior a la espera cooperativa;
3. un replay concurrente pueda acreditar causalmente el commit ganador;
4. import y dry-run conserven sin cambios su reloj transaccional.

## Alternativas evaluadas

### Reemplazo directo por `statement_timestamp()`

Captura correctamente un instante post-lock, pero el planner lo reutilizaría como tiempo de DML.
Los triggers V27 rechazarían `ocurrido_en` y `sellado_en` posteriores a
`transaction_timestamp()`. Requeriría migración, nuevos hashes de funciones y reacreditación de
schema y roles. Rechazado.

### Relajar sólo el replay

Permitir `expectedAppliedAt > observedAt` únicamente cuando no hay mutación arreglaría el síntoma
de PROMOTE con un diff pequeño. Sin embargo, readiness, plan y reconciliación continuarían
publicando una observación anterior al lock; también conservaría ambigüedades cerca de fechas
efectivas y cutovers. Rechazado.

### Frontera temporal dual post-lock

Conserva un instante transaccional para DML y agrega un instante de observación posterior al lock.
No modifica V27, grants, API, CLI, timeouts ni formatos persistidos. Es la alternativa aprobada.

## Arquitectura aprobada

### Frontera tipada

Agregar un tipo interno e inmutable:

~~~java
record LegalEditorialTimeBoundary(
        Instant transactionAt,
        Instant observedAt) {
}
~~~

Sus invariantes son:

- ambos valores existen y respetan la precisión de microsegundos de PostgreSQL;
- `transactionAt <= observedAt`;
- no se acepta tiempo de JVM ni un valor aportado por el caller.

### Construcción dentro del gate

`LegalManifestDatabaseGate` conserva `execute(...)` sin cambios para import y dry-run. Sus tres
fronteras editoriales —mutable, read-only y reconciliación— construirán la frontera después de:

1. acreditar modo efectivo;
2. ejecutar preflights;
3. adquirir el advisory lock;
4. restaurar el `graphLockTimeout`.

El gate leerá, en la misma sesión JDBC y dentro de la misma transacción:

~~~sql
SELECT transaction_timestamp();
SELECT statement_timestamp();
~~~

Se conservan dos sentencias explícitas para no romper la inyección acreditada de pérdida de sesión
que intercepta la lectura transaccional. La segunda sentencia necesariamente comienza después de
que retornó el advisory lock. El callback editorial recibe la frontera tipada; ningún servicio la
reconstruye.

### Distribución semántica

`transactionAt` se usa para:

- `expectedAppliedAt` de toda mutación fresca;
- timestamps de transiciones, lotes, slots y punteros materializados;
- elegibilidad de documentos futuros en una operación SOURCE;
- cutoffs SOURCE de RETIRE;
- toda regla cuya escritura V27 deba ser válida en la transacción actual.

`observedAt` se usa para:

- readiness y su evidencia pública;
- lectura y fingerprint del postestado;
- reconocimiento de replay;
- postcondiciones de apply;
- clasificación de reconciliación;
- evidencia temporal de planes SELECT-only.

Si una fecha efectiva cae entre ambos instantes, la operación fresca permanece bloqueada y exige
retry. No se persiste retrospectivamente una transición con `transactionAt` anterior a su fecha
efectiva.

La misma regla se aplica a la causalidad persistida que el planner puede descubrir después de la
espera. Antes de construir un delta SOURCE fresco, `transactionAt` debe ser igual o posterior al
piso causal autoritativo del estado que pretende continuar: sellos de publicaciones, últimas
transiciones/`stateChangedAt` relevantes, actualización de punteros activos y sellos de lotes
aplicables. POST/replay se intenta primero con `observedAt` y puede reconocer evidencia posterior
a `transactionAt`; SOURCE no puede retrofechar una mutación sobre esa evidencia y, si el piso la
supera, queda bloqueado para retry.

### Planner y execution plan

`LegalEditorialPlannerCore` recibirá la frontera completa en PROMOTE, REPLACE y RETIRE. Un plan
fresco llevará:

- `transactionAt` igual a la frontera;
- `observedAt` igual a la frontera;
- `expectedAppliedAt == transactionAt`.

Un replay conservará el `expectedAppliedAt` inferido del estado persistido y exigirá:

~~~text
transactionAt puede ser anterior a expectedAppliedAt
expectedAppliedAt <= observedAt
~~~

`LegalEditorialExecutionPlan` agregará `transactionAt` como dato interno. La igualdad fresca
`expectedAppliedAt == observedAt` será reemplazada por
`expectedAppliedAt == transactionAt`, conservando la prohibición
`expectedAppliedAt > observedAt`. Todos los comandos y el postestado seguirán usando exactamente
`expectedAppliedAt`.

Los guards redundantes de REPLACE y RETIRE compararán `expectedAppliedAt` con `transactionAt`; su
revalidación de postestado seguirá usando `observedAt`.

### Servicios

- readiness consume solamente `boundary.observedAt()`;
- plan pasa la frontera completa al planner;
- apply pasa la frontera y acredita que el plan devuelto pertenece exactamente a ella;
- reconciliación replantea con su propia frontera post-lock;
- `LegalManifestGraphWriter` mantiene `transaction_timestamp()` y queda fuera del microcorte.

## Fallos y seguridad

No se agregan códigos públicos. Un fallo al leer o validar la frontera atraviesa los mappers
existentes:

- readiness y plan devuelven error editorial conservador;
- apply no confirma persistencia falsa;
- reconciliación inconclusa conserva `UNKNOWN`;
- ninguna ruta usa `Instant.now()` como fallback.

La frontera continúa bajo los presupuestos productivos de 75/30/30/5 segundos. Se agrega un solo
round-trip editorial respecto del flujo actual; no se modifican los timeouts.

## Reproducción determinista

La regresión principal no dependerá de sleeps, retries ni orden del scheduler:

1. un `LegalEditorialSchemaVerifier` real, envuelto test-only, completa su verificación y detiene
   la primera transacción A mediante latch antes del advisory lock;
2. comienza B y queda detenida en el mismo checkpoint;
3. el observer owner consulta ambos PIDs en `pg_stat_activity` y acredita
   `xact_start(A) < xact_start(B)`;
4. se libera sólo B, que aplica mientras A sigue abierta;
5. se libera A, que debe reconocer el postestado de B;
6. se exige exactamente `APPLIED + ALREADY_APPLIED`, mismo receipt, una sola mutación, secuencias
   exactas y replay posterior sin cambios.

La documentación de `pg_stat_activity`, `xact_start` y `query_start` es:

- https://www.postgresql.org/docs/16/monitoring-stats.html.

Todos los latches se liberan y los futures se cancelan en `finally` con timeout acotado. El
preflight real siempre se ejecuta; el test no omite acreditación de schema o privilegios.

## Estrategia de pruebas

### Unitarias

- gate: orden `lock -> transactionAt -> observedAt -> callback`, misma sesión y una lectura de cada
  reloj;
- frontera: nulls, precisión, orden válido e inversión temporal;
- execution plan: fresh con `transactionAt < observedAt`, replay con
  `transactionAt < expectedAppliedAt <= observedAt` y rechazos inversos;
- planner PROMOTE/REPLACE/RETIRE: lecturas/postestado con `observedAt`, DML fresco con
  `transactionAt` y fecha efectiva cruzada bloqueada;
- services: propagación exacta de la frontera, sin relojes alternativos;
- writers: comandos y sellos uniformes en `expectedAppliedAt == transactionAt`;
- reconciliación: postestado exacto y fallos inconclusos conservadores.

### PostgreSQL 16

- carrera determinista de PROMOTE idéntico;
- targets incompatibles y REPLACE superpuesto ya incluidos en 10A;
- regresiones de PROMOTE, REPLACE, RETIRE y reconciliación;
- matriz existente de fallos y pérdida de ACK;
- gate compartido con import y dry-run.

## Compatibilidad y no objetivos

No cambia:

- migración V27 ni sus funciones acreditadas por hash;
- roles, grants, search path o privilegios;
- schemas JSON, CLI, API HTTP o frontend;
- receipt histórico ni `appliedAt` ya persistido;
- reloj del importador o dry-run;
- presupuestos productivos;
- proceso de deploy.

El cambio deliberado es que `observedAt` de readiness y planes editoriales representa realmente
una sentencia posterior al advisory lock. En una mutación fresca, `receipt.appliedAt` puede ser
estrictamente anterior a `observedAt`; ambos valores conservan significado explícito y distinto.

## Criterio de cierre

El microcorte 10A.1 queda cerrado cuando:

1. la reproducción determinista pasa sin retries;
2. todos los unitarios focales quedan verdes;
3. las puertas focal y ampliada de 10A quedan verdes con JDK 21 y PostgreSQL 16;
4. una auditoría adversarial no encuentra reloj de JVM, timestamp post-lock persistido ni bypass
   del advisory lock;
5. la evidencia queda registrada en el plan de implementación;
6. se crean commits locales atómicos y no se realiza push.
