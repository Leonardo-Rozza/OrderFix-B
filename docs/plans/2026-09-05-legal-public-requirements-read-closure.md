# Corte 14 — Cierre de requisitos públicos de registro

Fecha: 2026-09-05

Estado: 14A–14E completados; gate integral de cierre aprobado. Flags apagados y handoff global cerrado.

## Alcance y trazabilidad

La superficie implementada es exclusivamente:

```http
GET /api/public/requisitos-legales?contexto=REGISTRO&locale=es-AR
```

Devuelve todos los requisitos vigentes de registro —obligatorios y opcionales— y sus documentos
completos, en orden, con un único `requiredSetRevision` agregado. No expone IDs de agregados,
publicaciones, procedencia, perfil, audiencia ni revisiones por scope. El token representa el
conjunto completo; aceptación y filtrado futuro de pendientes no cambian esta semántica V28.

El flag `ordenfix.legal.public-requirements.enabled` permanece apagado. Apagado no registra la
ruta, advice, fachada ni contexto nuevo. La activación exige las tres credenciales dedicadas
`ordenfix.legal.public-requirements.jdbc-url`, `.username` y `.password`; no hay fallback a la
credencial web ni documental. No se activaron flags, provisionaron roles compartidos, importaron
textos legales definitivos ni modificaron frontend o migraciones.

Backend: `codex/lanzamiento-publico-backend`; frontend conservado en
`codex/frontend-refactor-checkpoint`, HEAD `7545201`, incluidos sus archivos no versionados.

| Corte | Commit local | Resultado |
| --- | --- | --- |
| Diseño | `3351728` | Contrato, límites y secuencia aprobados |
| 14A | `efe8484` | Acreditación pura de la proyección completa |
| 14B | `208397d` | Contexto restringido, preflight, store y recursos acotados |
| 14C | `741a1b2` | Hidratación y servicio en la transacción del agregado |
| 14D | `82b6708` | GET exacto, DTO, caché, flags y políticas HTTP |
| 14E | Commit de este documento | Concurrencia, capacidad, tiempos límite y gate integral fresco |

El delta productivo de 14A–14D respecto de `40a31a2` comprende 21 archivos Java: 15 nuevos y seis
modificados. Los nuevos son dos tipos core, siete componentes de persistencia, cinco de HTTP y
un matcher. Los modificados son marker, gate y cuatro componentes de configuración/seguridad/rate
limit. No hay migraciones ni recursos productivos nuevos o modificados. El alcance de 14E se limita
a pruebas y documentación; cualquier defecto productivo exige un corte previo documentado.

El [diseño](2026-09-05-legal-public-requirements-read-design.md), el
[plan y evidencia focal](2026-09-05-legal-public-requirements-read-implementation.md) y el
[contrato de integración](../../FRONTEND_INTEGRATION.md) conservan las decisiones aprobadas.
El [cierre documental 13](2026-09-05-legal-public-document-read-closure.md) y el
[cierre V28](2026-09-01-legal-required-set-aggregate-v28-closure.md) son evidencia histórica de sus
propias superficies; no sustituyen las pruebas nuevas de requisitos HTTP.

## Frontera PostgreSQL y resultado transaccional

El contexto independiente no tiene parent, elimina fuentes y perfiles ambientales, copia sólo
las tres credenciales dedicadas y el flag interno, y expone a web únicamente la fachada.
No ejecuta JPA ni Flyway. Cierra sus recursos ante shutdown o fallo de inicialización.

Cada consulta resuelve `REGISTRATION/es-AR/ADMIN_TITULAR` del lado servidor. Usa preflight V28 y de
privilegios efectivos, gate editorial compartido y transacción propia
`REQUIRES_NEW/READ_COMMITTED`, mutable para persistir el agregado derivado. Dentro de ella materializa
o reutiliza, hidrata todos los miembros, valida texto/digests/estado/pertenencia y compara ambas
revisiones. Entrega el resultado sólo después de commit, cierre y control final del deadline.
La repetición estable devuelve el mismo contrato con `REUSED` y cero DML. Un error anterior al commit
revierte las inserciones nuevas; un commit confirmado seguido de un fallo de cierre no se presenta
como rollback. HTTP no entrega un éxito cuando la operación completa no puede acreditarse.

El rol propio tiene SELECT sobre las 17 tablas nominales de su verifier, INSERT en las dos tablas
agregadas, UPDATE de la columna necesaria para el `FOR SHARE` de V28 y EXECUTE en las siete funciones
nominales autorizadas. Los triggers congelados continúan rechazando modificaciones editoriales.
No recibe secuencias, ownership, DDL, evidencia de usuarios, aceptación ni poderes editoriales.
Se revisan capacidades efectivas, memberships, permisos de PUBLIC y extras, no sólo el nombre del rol.
Las revocaciones y grants de los tests afectan únicamente sus bases Testcontainers dedicadas.

Pool de dos conexiones, minIdle cero, adquisición de 1 s, connect/login de 1 s, socket de 5 s y
cancelSignal de 1 s. El presupuesto de operación es 15 s; transacción/sentencia/lock editorial/lock
de grafo son 15/5/1/1 s. El tiempo restante acota sentencias y socket sin reiniciar el presupuesto
entre fases. Los chequeos de CPU son cooperativos; serialización, red y teardown pueden excederlo.
Las pruebas no prometen un SLA extremo a extremo ni un límite de heap.

## Transporte y políticas

Se valida primero `locale`, luego `contexto`, con valores exactos, sensibles a mayúsculas y sin
trim/default. Ausencia o repetición, incluso idéntica, produce 400; el detalle conserva valores
originales unidos por coma en orden, o null cuando falta. El DTO preserva UUID, texto y orden; las
fechas usan UTC RFC3339 con la precisión aprobada, y el estado documental expuesto es VIGENTE.

El matcher compartido por SecurityConfig, JWT y rate limit admite sólo GET exacto con flag literal
true, ignorando mayúsculas pero sin trim. Comprueba y retira una vez el contextPath. HEAD, slash final,
vecinos y subrutas no heredan acceso público. Las cuatro combinaciones de flags de documentos y
requisitos mantienen sus permisos y cuotas independientes. Un Bearer inválido no carga al actor.

La política `public-legal-requirements`, configurable bajo `security.rate-limit`, empieza en 60/min
por IP. Cuenta 200/304/400/503; un 429 lleva Retry-After, limit/remaining, no-store y ningún ETag.
Conserva el switch global, confianza de proxies y CORS; no añade ETag a los headers expuestos.

El éxito usa `Cache-Control: public, max-age=0, must-revalidate` y
`ETag: W/"<requiredSetRevision>"`. Construye y acredita el contrato completo antes de comparar tags,
listas o wildcard. Un 304 no tiene cuerpo ni evita la lectura. Los errores propios usan ApiError,
no-store y ningún ETag; un tag antiguo válido no oculta corrupción, pérdida de permisos ni ausencia.
Las causas internas de SQL, credenciales o recursos no se incorporan al mensaje público.

## Evidencia de concurrencia, capacidad y tiempos límite

La composición HTTP de este corte usa la fachada real sobre su grafo restringido instrumentado;
el helper sólo administra MVC y políticas, y el test conserva el ownership del grafo PostgreSQL.
El puente de producción se vuelve a ejecutar en los 27 casos `LegalPublicRequirementsHttpIT` de 14D.
Los spies llaman al código real y añaden únicamente barreras o fallos explícitos. Los límites de
producción y las allowlists permanecen intactos.

### Concurrencia y procedencia

Cinco invocaciones acreditan REPLACE, RETIRE, PROMOTE, creación simultánea y procedencia física.
Para REPLACE/RETIRE se detienen dos lectores dentro del shared gate antes de hidratar. Se observa
el ExclusiveLock del writer real pendiente; al terminar la primera respuesta queda un ShareLock y
el writer sigue esperando al segundo lector. Ambas respuestas completas conservan el cuerpo y ETag
anterior. Tras REPLACE, la siguiente respuesta con tag viejo es 200 con cuerpo/revisión nuevos y una
revalidación estable posterior es 304 sin DML. RETIRE deja el registro incompleto: devuelve 503 incluso
con el tag anterior, mientras catálogo y documento exacto históricos siguen respondiendo 200.
La versión retirada conserva su Markdown/digest y caché immutable, usando el lector HTTP documental
real con una segunda identidad restringida de cuatro SELECT, verificada en la misma base efímera.

PROMOTE comienza sin puntero: ambas observaciones ocurren bajo shared y el writer publica sólo
tras terminar las dos transacciones. Las respuestas 503 representan ese estado anterior; no se
afirma que ambas lleguen al cliente antes del commit editorial. La barrera está antes del store
porque no existe aún un conjunto para hidratar. Tras la promoción real, lectura y revalidación
devuelven 200/304 coherentes.

La carrera fuerza dos lookups iniciales vacíos mediante el proveedor de UUID existente del store.
Ambos creadores mantienen shared y convergen a una sola identidad durable, con resultados
CREATED/REUSED. Hay tres ejecuciones DML en esa carrera —dos intentos de INSERT de cabecera y un
batch de scopes— y cero en la repetición estable. No se confunde un REUSED que perdió la carrera
con una materialización repetida que ya encontró la identidad existente.

Un REPLACE fuera de REGISTRO conserva su proyección y token, pero cambia publicación/conjunto y
huella de procedencia. Sustituir deliberadamente el receipt nuevo por el anterior en la frontera del
reader produce 503 y rollback del agregado nuevo. Retirada la inyección, el mismo ETag puede recibir
304 sólo después de crear y acreditar la procedencia actual; la repetición siguiente hace cero DML.

### Capacidad y límites observados

El extremo editorial válido contiene 256 requisitos en la publicación: 251 de REGISTRO y cinco de
los otros contextos obligatorios. Importación y PROMOTE reales acreditan la matriz congelada. No se
presenta como un scope editorial de 256: ese límite estructural se prueba por separado en el validador
puro. Sus 253 referencias apuntan a tres UUID vigentes; términos tiene 65.536 bytes y privacidad/DPA
163.840 cada uno. Suman 393.216 bytes distintos y 16.777.216 bytes de Markdown expandido exactos.

| Observación focal | Extremo editorial | Con 143 versiones históricas |
| --- | ---: | ---: |
| Requisitos REGISTRO entregados | 251 | 1 |
| Documentos distintos hidratados | 3 | 3 |
| SELECT del reader | 14 | 7 |
| Batches de afirmaciones | 8 | 1 |
| Batches de Markdown | 1 | 1 |
| Bytes de Markdown distintos | 393216 | 164 |
| Bytes del cuerpo HTTP | 19067968 | 1479 |

El gate integral reprodujo exactamente las cifras de ambas columnas.

Los SELECT contabilizados son consultas JDBC del reader; no incluyen sentencias de preflight, store,
triggers o trabajo interno del servidor. El probe suma payloads devueltos por `getBytes`, no tráfico
del protocolo PostgreSQL. Los bytes HTTP se miden sobre el cuerpo serializado. No se mide heap,
throughput ni memoria total del proceso y 16 MiB de Markdown no implica 16 MiB de JSON.

El cursor real de pgjdbc 42.7.10 acredita autocommit false, READ_COMMITTED, transacción mutable,
avance forward-only, fetch de 32, portal activo y recambio del buffer al cruzar 32 filas. Observa como
máximo 32 filas en ese buffer; no equivale a memoria auxiliar constante del lector completo. Cada
cursor/statement termina cerrado, el pool no mantiene leases y las sesiones no conservan transacción
ni advisory locks. La inspección de campos internos `rows/cursor` depende de esta versión del driver.

Un REPLACE editorial cambia sólo el último requisito, después de siete batches de afirmaciones:
los primeros 250 miembros mantienen el mismo wire y el token/ETag cambia. Trece publicaciones reales
—un PROMOTE y doce REPLACE— acumulan 143 versiones documentales (11 vigentes y 132 reemplazadas),
pero el conjunto actual sólo carga sus tres UUID mediante IDs exactos. No hay N+1 ni arrastre del
historial al payload de la consulta de registro.

Los casos negativos se rotulan como corrupción owner de bases efímeras o inyección JDBC:

- Un byte extra de privacidad supera el límite expandido y falla antes de transferir afirmaciones
  o Markdown; restaura el contenido y recupera 304 sin DML.
- Una afirmación de 1001 caracteres o Markdown de 1048577 bytes revierte la materialización nueva
  antes de transferir texto. Los límites/sentinelas se complementan con los casos 14A–14C del gate.
- Un miembro 257 en la publicación se detecta por el contador acotado antes de cargar miembros/textos.
- Un probe repite una fila física válida para presentar 257 avances: sólo 256 filas se mapean y la
  fila 257 detiene la lectura antes de entregar un prefijo. No es una publicación válida ni una
  prueba de 256 filas físicas; la inyección aísla el límite del cursor de las reglas editoriales.

### Tiempos límite y resultado transaccional

Nueve casos acreditan lock ocupado, pool agotado, FETCH lento y seis fallos por fase. Un cursor de
metadata real de 65 miembros conserva SQL/bindings y añade únicamente `pg_sleep` a la proyección:
los primeros dos FETCH de 32 filas terminan y la fila 65 provoca el vencimiento. Con presupuesto de
prueba de 3 s se observan 64 filas, cancelación, cierre de cursor/statement y remanente de red descendente.
La transacción no llama COMMIT, intenta rollback y un observador comprueba cero filas durables.
El focal reportó STATUS_UNKNOWN y ningún SQLSTATE extraído; no se lo presenta como rollback conocido.
La siguiente operación se recupera y materializa normalmente.

| Inyección HTTP | Presupuesto | Tiempo focal observado | Resultado |
| --- | --- | ---: | --- |
| Lock editorial ocupado | 1 s lock | 1176 ms | 503; luego 304 REUSED sin DML |
| Pool agotado | 1 s adquisición | 1015 ms | 503 sin transacción nueva; luego 304 |
| Tercer FETCH lento | 3 s de prueba | 3018 ms | 503, 64 filas y cero filas agregadas durables |

El gate integral registró 1182/1009/3014 ms para lock/pool/FETCH respectivamente, otra vez con
64 filas, STATUS_UNKNOWN y sin SQLSTATE extraído en FETCH.

Estos tiempos miden el GET hasta producir 503, incluida la limpieza del servicio. Excluyen el
poll posterior, acotado a 5 s, que comprueba por rol y base la liberación de transacciones y advisory
locks; permite conexiones idle y precede al conteo de filas durables tras FETCH y a la recuperación.
No acreditan liberación instantánea del servidor ni son un SLA o latencia aislada de cancelación.
El presupuesto productivo continúa en 15 s. Los casos poscálculo/precommit/postcommit
usan reloj controlado después de ejecutar el trabajo real; no simulan interrupción durante el hash.
Las inyecciones de cierre ocurren después de cerrar el delegado y no representan una fuga física
ni una conexión que se niega a cerrar.

Poscálculo/precommit vencidos terminan en rollback y recuperación CREATED. Commit confirmado seguido
de vencimiento o fallo de cierre conserva STATUS_COMMITTED y se recupera REUSED. Un COMMIT realmente
confirmado cuyo acuse se sustituye por una excepción mantiene STATUS_UNKNOWN, filas durables y
recuperación REUSED. Ningún caso publica 200/304, ETag o cuerpo parcial durante el fallo: responde 503,
no-store y mensaje público fijo. Se conservan causa interna, contadores físicos y resultado Spring.

### Focal y correcciones de preparación

El focal consolidado comprende 81 pruebas puras y 48 PostgreSQL: 7 de capacidad, 5 de concurrencia,
9 de deadline y 27 del puente HTTP existente. La compilación inicial detectó un import incorrecto
del tipo anidado `LegalEditorialPlanValidator.ValidatedEditorialPlan`; se corrigió sólo el import.
El primer PostgreSQL pasó 47/48: RETIRE fue rechazado antes de ejecutar por la ruta temporal de macOS
que atravesaba un symlink. Se corrigió el fixture con `directory.toRealPath()` y se repitió la clase
completa de concurrencia: 5/5 aprobados, sin relajar el lector confinado ni sus assertions.
La revisión reforzó la observación de recursos del servidor tras FETCH/UNKNOWN, con el poll
independiente anterior al conteo durable. Se repitió DeadlineIT: 9/9 aprobados, Maven 21.251 s,
final 2026-09-05 17:27:46 -03:00. La tabla de tiempos corresponde a esa repetición.
No fue necesario cambiar producción.

Maven focal puro/package: 15.144 s, final 2026-09-05 17:18:53 -03. La primera integración duró 1 min 29 s,
final 17:20:53 -03. La repetición de concurrencia pasó en 23.920 s, final 17:22:51 -03.
El gate integral fresco se registra a continuación y no reutiliza los reportes previos de 14D.

## Migraciones, artefactos y gate integral

`./mvnw clean verify` con Corretto 21.0.10 terminó en **BUILD SUCCESS** el
**2026-09-05T17:42:44-03:00**, Maven **14 min 44 s**, sobre PostgreSQL 16.14.
Ejecutó **5.492 pruebas**: **4.970 Surefire en 150 suites** y
**522 Failsafe en 57 suites**, sin fallos, errores ni omitidas.
Los 207 XML frescos y `failsafe-summary.xml` confirman los conteos; no hubo flakes, timeout
ni failureMessage. Esta corrida no requirió correcciones ni repeticiones.

El gate vuelve a ejecutar el conjunto completo: autenticación/tenant, CLI editorial, V27/V28,
fronteras restringidas, transporte documental y requisitos públicos. Los 21 casos nuevos de 14E
son cinco de concurrencia, siete de capacidad y nueve de tiempos límite. La evidencia de este corte
se distingue de las mediciones y corridas históricas de 14A–14D.

Los artefactos recién empaquetados se inspeccionaron como ZIP:

| Artefacto | Bytes | SHA-256 |
| --- | ---: | --- |
| `mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar` | 90571324 | `80316e8ef9d9c440d275ef140bee257de508a72f4e1c31cf719acf161725cf26` |
| `mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar` | 90571320 | `c3b848233cff736201fbdde9bab6b762df44e09ef4bb7904d94d9422790c5ed3` |

Ambos usan `org.springframework.boot.loader.launch.JarLauncher`, con entrypoints respectivos
`LegalManifestCli` y `MvgrReparacionesBackendApplication`. No contienen clases exclusivas de tests,
Testcontainers, Mockito, agente Byte Buddy ni configuración local adicional. Su único recurso de
configuración coincide byte a byte con la fuente versionada. Las 28 migraciones de ambos JAR
coinciden con sus fuentes. Estos hashes identifican esta ejecución; no prometen build reproducible.

Versiones de la ejecución: Corretto 21.0.10, Maven 3.9.11, Spring Boot 4.0.6, Spring Core 7.0.7,
PostgreSQL 16.14 (`postgres:16-alpine`), JDBC PostgreSQL 42.7.10, Flyway 11.14.1,
Testcontainers 2.0.5, JUnit 6.0.3 y Surefire/Failsafe 3.5.5. No se versionan JAR ni reportes.

V27/V28 conservan sus bytes congelados:

| Migración | Checksum Flyway | SHA-256 |
| --- | ---: | --- |
| V27 | 1575269868 | `52fd5f3eda14fde228e218f127b5e9362c8542dc7e26df7b502ba65061332b9b` |
| V28 | 1900377028 | `1227c8261cfcca1263a0b2105bf0dc797c1f59f3b5bdc71225464fc4aa154a5e` |

El diff nominal de cierre contiene diez archivos: cinco tests/helpers nuevos y cinco documentos.
No cambia producción, tests anteriores, dependencias ni recursos. Revisión independiente de
concurrencia/capacidad/deadlines, diff sin errores de whitespace y stage nominal revisados.
Commit atómico local: `test(legal): cierra requisitos publicos de registro`, sin push.

## Fronteras pendientes

BACKEND-HANDOFF 1 y la Tarea 3 frontend permanecen cerrados. Quedan requisitos del actor,
aceptación/registro atómicos, historial propio, idempotencia HTTP, carry-forward y enforcement;
contenido definitivo, aprobación profesional, configuración/grants de un entorno compartido,
staging y deploy siguen fuera de este cierre. Email y Mercado Pago continúan en fases posteriores.

El titular es ADMIN y puede crear empleados; éstos usan la aplicación sin modificar funciones
reservadas al titular. OrdenFix administra reparaciones y evidencia asociada, no procesa pagos del
cliente, no emite comprobantes fiscales y no participa del acuerdo económico taller-cliente.
La implementación técnica no reemplaza las decisiones de consentimiento, privacidad, uso de datos
y baja ni acredita por sí misma textos legales definitivos. No se hizo push.
