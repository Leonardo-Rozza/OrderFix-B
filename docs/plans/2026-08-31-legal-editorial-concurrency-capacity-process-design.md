# Corte 10 — Diseño de concurrencia, fallos, capacidad y procesos editoriales reales

Fecha: 2026-08-31

Estado: completado el 2026-08-31; 10A–10F cerrados

Rama backend: `codex/lanzamiento-publico-backend`

Plan maestro:

- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`.

Este documento refina la única fila y el único commit que el plan maestro reservaba para Corte 10.
La autorización inicial limitaba el corte a pruebas y documentación. La carrera reproducible de
10A activó la regla de parada: el microdiseño post-lock se aprobó por separado y produjo la única
excepción productiva del corte, `9f00cb6 fix(legal): separa el reloj editorial post-lock`. 10D y
10E siguieron siendo test-only; no se modificaron migraciones, `pom.xml`, API ni frontend. Corte
10 permanece backend-only y la puerta frontend continúa diferida a Corte 11.

## Contexto

Los Cortes 4 a 9 acreditaron el flujo editorial interno de PROMOTE, REPLACE y RETIRE, incluidos
rol restringido, split/merge, retiro fail-closed y reconciliación conservadora de commits
ambiguos. La cobertura actual demuestra los contratos principales con pruebas unitarias e
integraciones PostgreSQL, pero al iniciar Corte 10 todavía no cerraba tres riesgos de operación
real:

1. carreras entre operadores que intentan mutar el mismo estado;
2. capacidad máxima y cantidad acotada de round trips JDBC;
3. ejecución completa mediante los JAR empaquetados y el launcher POSIX.

El plan maestro agrupaba toda esa evidencia en un único commit. El usuario aprobó dividirla en
subcortes pequeños y atómicos para que cada riesgo tenga una puerta focal, un diff revisable y un
rollback independiente.

## Decisión

Corte 10 se divide por riesgo técnico en seis subcortes:

1. **10A — Concurrencia editorial.**
2. **10B — Fallos y recuperación.**
3. **10C — Capacidad.**
4. **10D — Infraestructura de procesos.**
5. **10E — Matriz del JAR editorial.**
6. **10F — Puerta final y documentación.**

Se descartaron:

- un único commit, porque mezclaría cuatro IT nuevos, soporte de procesos, PostgreSQL, JAR y
  launcher en una unidad difícil de revisar;
- cortes verticales por PROMOTE, REPLACE y RETIRE, porque duplicarían fixtures y repartirían las
  garantías de concurrencia, fallos y capacidad entre varias fronteras;
- ampliar de antemano producción, schema o timeouts, porque Corte 10 primero debe medir y
  demostrar una brecha real.

Cada subcorte empieza por una prueba que demuestre la ausencia de su garantía, termina con una
puerta focal verde y se registra en un commit local atómico. No se hace push ni deploy.

## Objetivos

- Acreditar serialización real entre operaciones cooperativas mediante el mismo advisory lock.
- Demostrar que timeout, deadlock, session kill y pérdida del ACK no fabrican éxito.
- Congelar presupuesto temporal, cantidad de llamadas JDBC y lecturas multirrow acotadas.
- Ejecutar el ciclo editorial completo con PostgreSQL 16, rol restringido y JAR empaquetado.
- Acreditar el launcher POSIX real, aislamiento de Spring y saneamiento de configuración hostil.
- Conservar salida JSON única y semántica fail-closed cuando stdout no puede entregarse completo.
- Reutilizar las garantías existentes sin duplicar DML, relajar privilegios ni ampliar contratos.

## Fuera de alcance inicial

- Java productivo, salvo que una brecha reproducible de un subcorte active la regla de parada y el
  usuario apruebe un diseño separado; 10A.1 fue la única excepción aplicada;
- nuevas migraciones, índices, grants, roles o cambios en V27/V28;
- cambios en `pom.xml`, Start-Class o contenido funcional de los JAR;
- endpoints, controllers, JPA, scheduler, API pública o frontend;
- runbooks, staging, deploy y coordinación cross-repo, reservados para Corte 11;
- aumentar timeouts para conseguir una puerta verde;
- pruebas dependientes de sleeps, azar de scheduling o truncaciones no reproducibles.

El launcher POSIX sólo puede modificarse si una prueba focal roja demuestra una brecha real. No se
agregan flags, hooks ni switches productivos destinados exclusivamente a tests.

Si un subcorte demuestra una brecha productiva —N+1, batching insuficiente, lectura no acotada o
una carrera causal—, se detiene con evidencia reproducible. La corrección se diseña y aprueba como
microcorte separado antes de reanudar la acreditación. Esa regla se ejerció una sola vez en 10A.1.

## Invariantes comunes

1. Import, dry-run, readiness, plan, apply y reconciliación cooperan sobre el mismo advisory lock.
2. Dos operaciones incompatibles no pueden confirmar simultáneamente.
3. Un replay exacto converge sin DML ni avance de secuencias.
4. Timeout, deadlock o pérdida de sesión nunca se traducen en `APPLIED` sin evidencia autoritativa.
5. Un ACK perdido termina reconciliado o en `UNKNOWN`; nunca usa metadata tentativa como prueba.
6. Toda mutación real usa el rol editorial restringido y pasa los verificadores exactos.
7. El rol importador conserva su allowlist y no puede promover, reemplazar ni retirar.
8. Cada proceso editorial mantiene un único JSON en stdout y stderr vacío o allowlisteado.
9. Un fallo de entrega de stdout produce exit `3`, conserva el estado DB real y no fabrica un
   segundo envelope `UNKNOWN`.
10. Los JAR no contienen secretos y conservan sus Start-Class actuales.
11. El proceso editorial no levanta web, Flyway, JPA, runners ni schedulers.
12. Las system properties hostiles no pueden sustituir el entorno editorial validado.
13. El launcher conserva argumentos literalmente y elimina `JAVA_TOOL_OPTIONS`,
    `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` antes de iniciar la JVM.
14. Una operación medida debe terminar debajo de 70 s, cada statement debajo de 30 s y la
    frontera transaccional conserva un presupuesto máximo de 75 s.
15. Toda lectura multirrow instrumentada pertenece a un inventario cerrado. Las lecturas primarias
    de relaciones dinámicas consumen como máximo su cardinalidad válida más una fila bajo sentinel;
    los límites fijos del motor se acreditan por el valor exacto entregado al driver en cada
    ejecución, y las lecturas sin `LIMIT` propio sólo se admiten cuando sus IDs de entrada o su
    espacio de claves V27 ya están acotados de forma explícita.

## Componentes de prueba

### `LegalEditorialConcurrencyIT`

Nueva integración PostgreSQL dedicada a carreras editoriales. Ejecuta actores simultáneos con
conexiones independientes, barreras deterministas y el rol real correspondiente. Congela tanto el
resultado público como filas, transiciones, secuencias y postestado.

Escenarios mínimos:

- dos PROMOTE idénticos producen exactamente `APPLIED + ALREADY_APPLIED`;
- PROMOTE hacia targets incompatibles permite como máximo un resultado confirmado;
- dos REPLACE con predecesores superpuestos permiten como máximo un resultado confirmado;
- import, dry-run, readiness, plan y apply esperan el mismo advisory lock sin usar locks alternos.

### `LegalEditorialFailureIT`

Nueva matriz integrada de fallos reales. Reutiliza proxies, fixtures y reconciliación existentes;
no agrega switches productivos. Acredita timeout, deadlock PostgreSQL, session kill, pérdida del
ACK y retry exacto. Después de cada fallo compara el resultado con evidencia SQL independiente
para impedir falsos positivos.

La orquestación usa barreras o latches, checkpoints observables y PIDs de backend conocidos. No
usa sleeps abiertos para inferir que una transacción alcanzó un punto determinado. La posición
aparente de una desconexión tampoco acredita rollback: sólo el source exacto o el postestado
exacto observado bajo una frontera nueva permite concluir el resultado.

### `LegalEditorialCapacityIT`

La capacidad se acredita con dos techos distintos, porque importación y proyección editorial no
comparten la misma cardinalidad realizable:

- `LegalManifestImportCapacityIT` conserva el máximo contractual importable de 128 documentos,
  256 requisitos y 16 scopes;
- `LegalEditorialCapacityIT` usa el máximo editorial compuesto de 87 documentos, 256 requisitos,
  16 scopes y los 88 slots vigentes posibles.

La separación fue aprobada por el usuario después de caracterizar el modelo V27. Cada documento
requiere al menos un contexto y el slot vigente se identifica por `(tipo, locale, contexto)`.
Con 11 tipos, un locale y ocho contextos existen 88 claves únicas. Un split `1→2` necesita que su
predecesor ocupe al menos dos contextos y un merge `2→1` que su sucesor ocupe al menos dos; por
eso fuente y target pueden contener como máximo 87 documentos mientras ocupan exactamente los 88
slots. Los 128 documentos siguen siendo un límite válido de importación, pero no se presentan
como una publicación READY.

El escenario editorial combina 82 reuses, una adición compensada por un retiro, un lote `1→1`,
un split `1→2` y un merge `2→1`. Mantiene 250 requisitos reutilizados y seis reemplazados. Un
requisito READY puede referenciar como máximo 11 documentos dentro de su único contexto; el límite
contractual de 16 referencias continúa acreditado sólo en el fixture importable. Cada publicación
máxima materializa 2.642 referencias requisito-documento y la proyección activa
scope-miembro-documento llega a 5.284 filas.

La instrumentación agrega 5 ms por ejecución JDBC lógica, mide por separado readiness, plan y
apply, conserva el timeout real de statements y registra:

- duración de cada operación;
- cantidad total y por categoría de llamadas JDBC;
- mayor statement observado;
- espera del advisory lock;
- cardinalidad máxima de cada lectura multirrow;
- memoria reproducible e incidentes.

Los caps se fijan a partir de una ejecución observada y revisada antes del commit. No se aceptan
límites arbitrariamente holgados ni cambios de timeout para ocultar una regresión.

#### Contrato de métricas

- Una **ejecución JDBC lógica** es cada invocación instrumentada que alcanza
  `execute`, `executeQuery`, `executeUpdate`, `executeLargeUpdate`, `executeBatch` o
  `executeLargeBatch` en el driver. Cada retry o ejecución repetida cuenta nuevamente.
- La **mayor sentencia** es el máximo tiempo monotónico entre la entrada y la salida —normal o por
  excepción— de una de esas invocaciones; incluye los 5 ms artificiales y excluye consumo de filas
  posterior.
- Las **filas observadas** se cuentan sólo cuando `ResultSet.next()` devuelve `true`; una ejecución
  vacía aporta cero filas, pero igualmente permanece en el inventario SQL. En las lecturas
  primarias de relaciones dinámicas sentinela, `expected + 1` restringe filas consumidas; sus
  proyecciones derivadas usan caps sentinel propios. Los techos estructurales grandes se comprueban
  por el `LIMIT` canónico y su bind entero exacto. Ninguno de estos valores limita llamadas JDBC.
- Los límites `<70 s` se evalúan por separado para readiness, plan y apply; ninguno puede usar el
  margen restante de otro.
- La espera del advisory lock se mide por separado cuando el driver permita identificar esa
  sentencia sin alterar producción.
- La memoria máxima sólo se congela si el entorno ofrece una medición reproducible. Si no, 10F
  registra la razón y no publica una cifra engañosa.

#### Enmienda de cierre 10C

La implementación aprobada conserva un inventario cerrado de cada `SELECT` y `ROW_LOCK`, incluso
cuando devuelve cero o una fila. Toda familia parametrizada debe terminar exactamente en
`LIMIT ?`, observar en todas sus ejecuciones un último bind numérico entero y coincidir con el
techo configurado; `NULL`, bind ausente, decimal fraccionario, valor fuera de rango o una expresión
posterior al placeholder hacen fallar la puerta. Las dos búsquedas por publicación terminan
exactamente en `LIMIT 2`. Las consultas sin límite propio forman una allowlist cerrada y sólo se
aceptan porque reciben IDs de una lectura anterior ya limitada o recorren un espacio cerrado por
unicidad V27 y enums legales congelados.

Los sentinels físicos agregan corrupción a documentos, requisitos y miembros de scope, exigen
resultado fail-closed y demuestran que cada familia relajada realmente supera su cap normal sin
exceder su cap sentinel exacto.
Para límites fijos de 8.193, 16.385, 32.769 y 65.537 no se fabrica una base artificial de ese
tamaño: la prueba captura el bind exacto entregado al driver y la matriz `LegalEditorialSplitMergeIT`
mantiene la cobertura semántica de corrupción. Los caps de catálogos son, deliberadamente, un
snapshot controlado de PostgreSQL 16.14 con V27 y no se presentan como límites del dominio.

### `LegalCliProcessSupport`

El soporte compartido de procesos podrá ampliarse únicamente desde tests para:

- medir tiempo transcurrido con el mismo clock de la espera del proceso;
- distinguir captura completa, stdout cerrado y entrega parcial;
- conservar stdout y stderr como bytes antes de decodificar UTF-8;
- exponer exit, timeout y streams sin normalizar un fallo real;
- construir un entorno mínimo y reproducible para JAR y launcher.

La entrega parcial debe ser determinista y acreditar que el proceso hijo observó el fallo. Si el
JAR puede escribir el envelope completo en el pipe antes del cierre, no se usará un sleep para
forzar una carrera; 10D quedará bloqueado hasta contar con una estrategia test-only reproducible o
un nuevo diseño aprobado.

### `LegalEditorialProcessIT`

Nueva integración de procesos, separada de `LegalManifestCliProcessIT`, para no mezclar contratos
legacy v1/v3 con el setup pesado de PostgreSQL y roles restringidos. Reutiliza el patrón ya
acreditado por `LegalManifestImportProcessIT`.

La matriz mínima del JAR real incluye:

- readiness `NOT_READY` y luego `READY`;
- plan `APPLICABLE` y un caso `BLOCKED`;
- apply `APPLIED` y replay `ALREADY_APPLIED`;
- RETIRE confirmado con resultado `APPLIED + NOT_READY`;
- rechazo explícito al intentar aplicar con credenciales del importador;
- regresión del rechazo a rol owner, rol demasiado amplio o con herencia indebida;
- rechazo de UPDATE directo y de escrituras no-op fuera de las funciones allowlisteadas;
- imposibilidad del proceso de mutar snapshots o las tablas de aceptación/idempotencia HTTP;
  el aislamiento acredita además que no existe stack web ni cliente HTTP de aplicación, sin
  afirmar una observación universal del tráfico del sistema operativo;
- rechazo de las cuatro `-Dspring.datasource.*` hostiles;
- launcher editorial real limpiando los tres canales de opciones JVM;
- stdout cerrado y, cuando el harness determinista lo permita, truncado parcialmente;
- JSON único, UTF-8, canaries, redacción y stderr esperado en cada resultado confirmado.

## Partición de implementación

### 10A — Concurrencia editorial

Estado: cerrado el 2026-08-31, incluido el microcorte productivo 10A.1 aprobado por separado.

Crear `LegalEditorialConcurrencyIT` y reutilizar sólo los fixtures necesarios. El commit esperado
es:

    test(legal): acredita concurrencia editorial

### 10B — Fallos y recuperación

Estado: cerrado el 2026-08-31 sin cambios productivos.

Crear `LegalEditorialFailureIT` y consolidar la matriz dispersa sin eliminar las pruebas focales
existentes. El commit esperado es:

    test(legal): acredita fallos editoriales

### 10C — Capacidad

Estado: cerrado el 2026-08-31 sin brecha productiva.

Crear `LegalEditorialCapacityIT`, instrumentar round trips desde tests y congelar métricas reales.
El commit esperado es:

    test(legal): congela capacidad editorial

### 10D — Infraestructura de procesos

Estado: cerrado el 2026-08-31.

Ampliar `LegalCliProcessSupport` y sus pruebas; acreditar aislamiento, rol, propiedades hostiles y
launcher sin completar todavía toda la matriz funcional del JAR. El commit esperado es:

    test(legal): endurece procesos editoriales

Durante la implementación, ese commit previsto se descompuso en ocho microcommits test-only
entre `09c0e68` y `d8f6a97`. La descomposición separa resultado binario, lifecycle, entorno,
límite de captura, runner compartido, fallo determinista de stdout, launcher y preservación del
timeout explícito del runner, sin ampliar el alcance aprobado. La evidencia detallada queda
registrada en el plan de implementación.

### 10E — Matriz del JAR editorial

Estado: cerrado el 2026-08-31.

Crear `LegalEditorialProcessIT` y ejecutar todas las completion states aprobadas, incluido el
fallo de stdout real. El commit esperado es:

    test(legal): acredita jar editorial

La implementación se dividió en ocho commits test-only entre `ab2a917` y `2efc19f`: fixture de
procesos, PROMOTE, planes, lifecycle completo, fixture de seguridad, roles/launcher, agente stdout
compartido y commit con salida perdida. La puerta limpia acreditó `11` unitarias y `48`
integraciones, sin fallos, errores ni omitidos. No fue necesario modificar Java productivo,
migraciones, grants, `pom.xml`, launcher, frontend ni contratos públicos. La evidencia detallada,
incluidos estados PostgreSQL, artefactos y SHA-256, queda en el plan de implementación.

### 10F — Puerta y documentación

Estado: cerrado el 2026-08-31.

Se ejecutaron las puertas focales y el verify completo, se registraron las métricas y se cerró el
Corte 10 en el plan maestro, reemplazando su resumen de un único commit por la evidencia real de
10A–10F. El commit de cierre es:

    docs(legal): cierra corte de procesos reales

## Estrategia de validación

Cada subcorte ejecuta primero su clase nueva y las regresiones directamente relacionadas. Cuando
intervenga Failsafe se usa un lifecycle fresco para no reutilizar reportes anteriores.

La puerta acumulada mínima de Corte 10 es:

~~~bash
./mvnw clean -Dit.test=LegalEditorialConcurrencyIT,LegalEditorialFailureIT,LegalEditorialCapacityIT verify
./mvnw clean -Dit.test=LegalEditorialProcessIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
./mvnw clean verify
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Antes de cada commit se revisan el diff completo, los reportes Maven frescos y una auditoría
adversarial independiente. 10F registra versiones exactas de JDK, Maven, PostgreSQL y Flyway,
conteos de pruebas, duraciones, caps JDBC y cualquier incidente reproducible.

## Manejo de errores y bloqueo

- Un test inestable no se silencia, repite como política ni se marca skipped; se corrige su
  sincronización o se detiene el subcorte.
- Un presupuesto excedido no se resuelve aumentando 70/30/75 s; se identifica la llamada o lectura
  responsable.
- Un deadlock o session kill sin postestado concluyente conserva `UNKNOWN`.
- Una discrepancia entre salida del proceso y PostgreSQL se considera fallo P1 y bloquea el commit.
- Una modificación necesaria fuera del alcance inicial se documenta con prueba roja y se somete a
  aprobación antes de editar producción.

## Criterio de cierre

Corte 10 quedó cerrado porque las carreras aprobadas se serializan sin doble confirmación, los
fallos reales no fabrican éxito, el fixture máximo cumple presupuestos y caps congelados, y el JAR
editorial completo opera con PostgreSQL 16 y rol restringido a través del launcher real. La salida
de proceso conserva JSON único y semántica fail-closed incluso ante pérdida de stdout.

El cierre de Corte 10 no habilita por sí solo producción pública. El runbook, la coordinación
cross-repo y el cierre de la Fase 2.3C permanecen en Corte 11; V28, API pública, aceptación,
seguridad, staging y deploy conservan sus planes separados.
