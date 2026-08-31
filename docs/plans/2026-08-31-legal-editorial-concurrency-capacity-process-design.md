# Corte 10 — Diseño de concurrencia, fallos, capacidad y procesos editoriales reales

Fecha: 2026-08-31

Estado: aprobado por el usuario el 2026-08-31; pendiente de implementación

Rama backend: `codex/lanzamiento-publico-backend`

Plan maestro:

- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`.

Este documento refina la única fila y el único commit que el plan maestro reservaba para Corte 10.
La aprobación del usuario amplía exclusivamente la documentación para registrar los subcortes
10A–10F; no autoriza Java productivo, migraciones, `pom.xml`, API ni frontend. Corte 10 permanece
backend-only y la puerta frontend continúa diferida a Corte 11.

## Contexto

Los Cortes 4 a 9 acreditaron el flujo editorial interno de PROMOTE, REPLACE y RETIRE, incluidos
rol restringido, split/merge, retiro fail-closed y reconciliación conservadora de commits
ambiguos. La cobertura actual demuestra los contratos principales con pruebas unitarias e
integraciones PostgreSQL, pero todavía no cierra tres riesgos de operación real:

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

- Java productivo, salvo que una prueba de capacidad revele una brecha concreta y el usuario
  apruebe un diseño separado;
- nuevas migraciones, índices, grants, roles o cambios en V27/V28;
- cambios en `pom.xml`, Start-Class o contenido funcional de los JAR;
- endpoints, controllers, JPA, scheduler, API pública o frontend;
- runbooks, staging, deploy y coordinación cross-repo, reservados para Corte 11;
- aumentar timeouts para conseguir una puerta verde;
- pruebas dependientes de sleeps, azar de scheduling o truncaciones no reproducibles.

El launcher POSIX sólo puede modificarse si una prueba focal roja demuestra una brecha real. No se
agregan flags, hooks ni switches productivos destinados exclusivamente a tests.

Si 10C demuestra N+1, batching insuficiente o una lectura no acotada, el subcorte se detiene con
evidencia reproducible. La corrección productiva se diseña y aprueba como commit separado antes de
reanudar la acreditación de capacidad.

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
15. Las lecturas multirrow instrumentadas deben quedar limitadas a la cardinalidad esperada más
    una fila de corrupción.

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

Nueva integración de capacidad editorial sobre el fixture contractual máximo: 128 documentos,
256 requisitos y 16 scopes. El escenario combina reuse, adición, reemplazo `1→1`, split y merge.

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
- Las **filas observadas** se cuentan al avanzar exitosamente el `ResultSet`. El límite
  `expected + 1` restringe filas consumidas para una relación multirrow potencialmente corrupta;
  no es un límite de llamadas JDBC.
- Los límites `<70 s` se evalúan por separado para readiness, plan y apply; ninguno puede usar el
  margen restante de otro.
- La espera del advisory lock se mide por separado cuando el driver permita identificar esa
  sentencia sin alterar producción.
- La memoria máxima sólo se congela si el entorno ofrece una medición reproducible. Si no, 10F
  registra la razón y no publica una cifra engañosa.

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

Crear `LegalEditorialConcurrencyIT` y reutilizar sólo los fixtures necesarios. El commit esperado
es:

    test(legal): acredita concurrencia editorial

### 10B — Fallos y recuperación

Crear `LegalEditorialFailureIT` y consolidar la matriz dispersa sin eliminar las pruebas focales
existentes. El commit esperado es:

    test(legal): acredita fallos editoriales

### 10C — Capacidad

Crear `LegalEditorialCapacityIT`, instrumentar round trips desde tests y congelar métricas reales.
El commit esperado es:

    test(legal): congela capacidad editorial

### 10D — Infraestructura de procesos

Ampliar `LegalCliProcessSupport` y sus pruebas; acreditar aislamiento, rol, propiedades hostiles y
launcher sin completar todavía toda la matriz funcional del JAR. El commit esperado es:

    test(legal): endurece procesos editoriales

### 10E — Matriz del JAR editorial

Crear `LegalEditorialProcessIT` y ejecutar todas las completion states aprobadas, incluido el
fallo de stdout real. El commit esperado es:

    test(legal): acredita jar editorial

### 10F — Puerta y documentación

Ejecutar las puertas focales, el verify completo, registrar métricas y cerrar el Corte 10 en el
plan maestro, reemplazando su resumen de un único commit por la evidencia real de 10A–10F. El
commit esperado es:

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

Corte 10 queda cerrado cuando las carreras aprobadas se serializan sin doble confirmación, los
fallos reales no fabrican éxito, el fixture máximo cumple presupuestos y caps congelados, y el JAR
editorial completo opera con PostgreSQL 16 y rol restringido a través del launcher real. La salida
de proceso debe conservar JSON único y semántica fail-closed incluso ante pérdida de stdout.

El cierre de Corte 10 no habilita por sí solo producción pública. El runbook, la coordinación
cross-repo y el cierre de la Fase 2.3C permanecen en Corte 11; V28, API pública, aceptación,
seguridad, staging y deploy conservan sus planes separados.
