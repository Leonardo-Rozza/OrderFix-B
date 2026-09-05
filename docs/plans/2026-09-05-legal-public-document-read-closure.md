# Corte 13 — Cierre de lectura pública documental

Fecha: 2026-09-05

Estado: 13A–13D completados; gate integral aprobado. Flag apagado y handoff cerrado.

## Alcance e inventario

OrdenFix incorpora exclusivamente estos dos GET documentales:

```http
GET /api/public/documentos-legales?locale=es-AR&contexto=REGISTRO&page=0&size=20
GET /api/public/documentos-legales/{versionId}
```

El catálogo devuelve resúmenes de versiones VIGENTE, REEMPLAZADA y RETIRADA. La revisión
`documentSetRevision` identifica el filtro completo, independiente de page/size; contexto ausente
se representa como null. La versión exacta devuelve el Markdown original y su digest acreditado.
BORRADOR, PUBLICADA aún no vigente, UUID desconocido o malformado comparten el mismo 404.

El flag `ordenfix.legal.public-documents.enabled` permanece apagado por defecto. Apagado no agrega
mappings documentales ni excepciones de autenticación. Al encenderlo exige URL, usuario y contraseña
`ordenfix.legal.public-document-read.*` explícitos; no usa credenciales web como fallback ni provisiona
el rol. No se habilitó ningún entorno ni se importó contenido real.

El delta productivo 13A–13C respecto de `10bf5b5` contiene 26 archivos Java: 18 nuevos y ocho
modificados. Los nuevos son tres tipos/calculador core, nueve componentes de persistencia lectora,
cinco de transporte HTTP y un clasificador compartido. Los modificados son canonicalizer, gate,
marker y cinco componentes de configuración/seguridad/rate limit. No hay recursos ni migraciones
nuevos o modificados. 13D agrega pruebas y documentación; no amplía el código productivo.

La especificación, whitelist y evidencia focal se conservan en el
[diseño aprobado](2026-09-05-legal-public-document-read-design.md) y el
[plan de implementación](2026-09-05-legal-public-document-read-implementation.md).
El contrato wire actualizado está en [FRONTEND_INTEGRATION.md](../../FRONTEND_INTEGRATION.md).
El [cierre V28](2026-09-01-legal-required-set-aggregate-v28-closure.md) sigue siendo el baseline
histórico del núcleo y la persistencia agregada, con su evidencia y alcance de ese momento.

## Trazabilidad

Backend: `codex/lanzamiento-publico-backend`. Frontend conservado:
`codex/frontend-refactor-checkpoint`, HEAD `7545201`.

| Corte | Commit local | Resultado |
| --- | --- | --- |
| Diseño | `10bf5b5` | Contrato, frontera y secuencia aprobados |
| 13A | `67a9580` | Proyección y hash canónico documental |
| 13B | `a05c057` | Lector PostgreSQL restringido, preflight, cursor y deadline |
| 13C | `30526ef` | Dos GET, DTOs, flag, políticas HTTP, errores y caché |
| 13D | Commit de este documento | Capacidad/concurrencia HTTP y gate integral |

## Frontera de lectura y privilegios

La aplicación web recibe sólo `LegalPublicDocumentReadService`. Su contexto PostgreSQL independiente
no tiene parent y copia únicamente tres propiedades dedicadas y un flag interno. No comparte beans
JDBC, transaction manager, perfiles o fuentes de propiedades web. El bridge cierra el contexto y el
pool tanto en shutdown como ante errores de inicialización; no ejecuta Flyway ni abre conexiones al
construir el pool vacío.

Cada observación usa la credencial restringida real, una transacción
`REQUIRES_NEW/READ_COMMITTED/read-only`, preflight V27/V28 y de privilegios efectivos, y el advisory
lock editorial shared. Los escritores usan la misma key en exclusive. El lector consulta después
del lock y devuelve el resultado sólo después del commit y de comprobar su deadline final.

El rol lector tiene SELECT sobre `legal_documento_lineas`, `legal_documento_versiones`,
`legal_documento_contextos` y `flyway_schema_history`. No recibe DML, secuencias, ownership, DDL,
evidencia/usuarios, materialización de agregados ni EXECUTE de funciones legales. Se acreditan
capacidad efectiva, memberships y permisos de PUBLIC; la etiqueta read-only no sustituye esos checks.
Los fixtures restringen ACL sólo en sus bases efímeras. No se certificaron ni cambiaron grants de
una base compartida o productiva; cualquier provisioning futuro requiere inventariar consumidores.

El pool dedica dos conexiones, sin mínimo ocioso, adquisición de un segundo y límites de driver
explícitos. El presupuesto de operación es 15 s; statement 5 s y lock 1 s. Sentencias y socket se
acotan al tiempo restante, mientras lock_timeout conserva 1 s y el watchdog aplica el deadline global.
El deadline empieza antes del borrow y cubre lectura/commit/cleanup. No incluye
serialización del DTO ni transferencia HTTP posteriores. Un driver en teardown puede exceder el
instante nominal mientras completa cancelación; las mediciones de tests no son un SLA.

## Wire, caché y aislamiento de autenticación

La clasificación compartida permite únicamente GET sobre catálogo exacto o un segmento documental,
quitando una sola vez el context path comprobado. HEAD, otros métodos, requisitos y subrutas no
heredan permiso público. JWT y rate limit usan esa misma clasificación. El flag admite sólo el
literal true sin distinguir mayúsculas, igual que ConditionalOnProperty; aliases booleanos no abren
una excepción de autenticación. Los GET admitidos no consultan un Bearer inválido.

La policy inicial de 60 solicitudes/minuto por IP incluye condicionales y UUID inválidos. Conserva
el switch general y la política de forwarded headers. Los 429 documentales usan el envelope
existente, no-store y Retry-After; CORS conserva Authorization y expone los tres headers de cuota.
Las ventanas menores a 1 ms se rechazan porque el contador trabaja con resolución de milisegundos.

Los DTOs usan el mismo UUID/fecha UTC que los bytes canónicos. Los resúmenes no exponen Markdown,
claves internas, rutas o procedencia. Los conteos permanecen exactos hasta `2^53−1`; se rechaza un
conteo fuera de ese límite. Page/size no alteran el hash del catálogo completo.

La acreditación precede a cualquier 304. Catálogo y documento VIGENTE revalidan; documentos
REEMPLAZADA/RETIRADA son immutable. El ETag documental incorpora estado y digest; el del catálogo,
revisión y coordenadas de página. Se conservan tags débiles/fuertes y listas mediante Spring; para
el wildcard GET de Spring 7.0.7 se usa su parser después de evaluar el ETag real y sólo en una lectura
exitosa. Un ETag conocido no transforma pérdida de permisos, contenido corrupto o ausencia en 304.
Los 400/404/503 documentales tienen ApiError, no-store y carecen de ETag; no exponen causas JDBC.

## Capacidad, concurrencia y límites de la evidencia

El lector recorre un cursor forward-only con fetch 128 y hace una pasada por el filtro completo,
reteniendo como máximo una página de 100 resúmenes. EXISTS sobre contextos evita duplicar versiones;
la consulta de catálogo no selecciona Markdown ni aplica paginación SQL que trunque el hash.
Las pruebas miden ejecuciones JDBC y filas del cursor; no cuentan cada sentencia interna del servidor.
La memoria auxiliar acotada se acredita por inspección del algoritmo, no por un benchmark de heap.

Los límites numéricos ya están cubiertos por LegalPublicDocumentCatalogTest y el contrato MVC:
page máximo, offset largo, techo de páginas, `2^53−1`, exceso y `Long.MAX_VALUE`. No se presentan
esas pruebas aritméticas como una base con esa cantidad de filas físicas.

Las pruebas nuevas de 13D conectan el transporte productivo al lector restringido instrumentado
para observar JDBC y coordinar barreras. Sólo los colaboradores JWT se sustituyen en el helper HTTP;
el test declara cualquier spy de sincronización o inyección de fallo. La composición completa del
bridge productivo también se ejecuta mediante los siete LegalPublicDocumentHttpIT de 13C.

LegalPublicDocumentHttpCapacityIT usa 13 publicaciones reales: 143 versiones, 11 vigentes y 132
reemplazadas. Page0/size100 devuelve 100; page1 devuelve 43; page142/size1 devuelve la última fila;
page2147483647/size100 devuelve una página vacía. Las cuatro comparten revisión y cada llamada,
incluido el 304, recorre las 143 filas. Los filtros de fotos/credenciales leen 26 versiones históricas
sin duplicados; mismos IDs bajo filtros distintos producen revisiones distintas.

El probe de test, debajo de los wrappers del lector, observa el portal real y el buffer inicial de
128 filas mediante reflexión del driver PostgreSQL fijado. Confirma autocommit false, read-only,
READ_COMMITTED, avance exclusivamente forward, cruce de buffer, un solo EOF y cierre de statement/
resultset. No basta con observar setFetchSize. Es evidencia dependiente de esa versión del driver;
no una API productiva ni una medición del heap global.

Las métricas muestran una consulta documental por llamada, sin N+1, Markdown, COUNT separado o
LIMIT/OFFSET. Un cambio sintético de metadata sólo en la última fila modifica la revisión/ETag de
page0 aunque sus cien resúmenes permanezcan idénticos. Una fecha fuera del contrato en posición131,
después del primer fetch, produce 503 con/sin ETag previo: 131 filas observadas por intento, rollback,
cancelación y cierre. Se restaura la fila y se recupera la revisión anterior. Es deriva owner
deliberada de una base efímera, no una mutación editorial permitida después de publicar.

LegalPublicDocumentHttpConcurrencyIT retiene dos GET con shared y pone un REPLACE real en espera.
Libera los lectores por separado y observa dos, uno y cero locks compartidos. El writer sigue
bloqueado después del primer GET; ambos responses conservan el snapshot anterior. Después del
commit, revisión, páginas, conteos y estados corresponden a 22 versiones (11 VIGENTE/11 REEMPLAZADA).
Un documento anterior deja de revalidar con su ETag viejo y recibe caché terminal immutable.

Otros casos fuerzan un lock exclusivo ocupado y las dos conexiones del pool prestadas: HTTP503,
no-store, sin ETag ni snapshot parcial. Tras liberar el recurso, la revalidación vuelve a funcionar.
La prueba de deadline construye primero el catálogo real y luego añade un cursor JDBC controlado
dentro de la misma transacción: fetch32, 64 filas rápidas y un tercer FETCH lento. El deadline de
prueba de 2 s lo interrumpe y ese catálogo ya construido nunca se publica. Es una inyección de I/O posterior
al catálogo, no una medición de latencia del SELECT documental productivo.

Resultado focal: 13 pruebas numéricas existentes y ocho IT HTTP nuevos aprobados. Capacidad terminó
en 31.368 s; la repetición final de concurrencia, en 25.934 s, a las 13:27:11 -03.

| Inyección HTTP | Presupuesto configurado | Tiempo observado focal | Resultado |
| --- | --- | ---: | --- |
| Lock editorial ocupado | 1 s lock | 1123 ms | 503, SQLSTATE 55P03, sin lease/lock lector |
| Pool agotado | 1 s adquisición | 1018 ms | 503, sin adquirir una tercera conexión |
| Cursor lento tras catálogo | 2 s operación de prueba | 2046 ms | 503, 64 filas, deadline vencido, SQLSTATE 08006 |

Estos tiempos incluyen infraestructura y limpieza de la prueba. No se afirma un SLA, throughput,
latencia de cancelación aislada ni tiempo del SELECT documental productivo.

La primera ejecución de concurrencia falló únicamente en la inspección de causalidad del test:
buscaba SQLSTATE por getCause. El FETCH ya había fallado con SocketTimeoutException/08006 y el
rollback posterior sobre una conexión invalidada guardaba la excepción original en
TransactionSystemException.getOriginalException. Se corrigió el recorrido con protección de ciclos,
causas y suppressed; además se acredita directamente el deadline vencido con el hilo no interrumpido.
No se cambió producción ni se relajaron 503, las 64 filas, el presupuesto o la liberación de recursos.

## Migraciones y artefactos

V27/V28 permanecen congeladas. El cierre integral vuelve a ejecutar las rutas de instalación y
upgrade ya acreditadas, sin modificar las migraciones ni usar una base de aplicación.

| Migración | Checksum Flyway | SHA-256 del archivo |
| --- | ---: | --- |
| V27 | 1575269868 | `52fd5f3eda14fde228e218f127b5e9362c8542dc7e26df7b502ba65061332b9b` |
| V28 | 1900377028 | `1227c8261cfcca1263a0b2105bf0dc797c1f59f3b5bdc71225464fc4aa154a5e` |

Los dos JARs producidos por esta corrida contienen las 28 migraciones y no contienen
application-secret.properties ni el agente de fallos stdout de tests. Sus manifiestos no declaran
atributos de agente; Start-Class es MvgrReparacionesBackendApplication para web y LegalManifestCli
para el clasificador legal-cli.

| Artefacto en target | Bytes | SHA-256 |
| --- | ---: | --- |
| mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar | 90476672 | `7f7b848fb334a2fb4da2d3672ba5c826f5f153bc29b372c10887d0dfddf41486` |
| mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar | 90476676 | `869d0a1079f286f2c094108cea49215508191297740f2824759137c4c8eb15e1` |

Estos hashes identifican esta ejecución; Maven no fija outputTimestamp y no se afirma identidad
binaria entre builds. No se versionan los artefactos.

## Gate integral

Entorno de la corrida: Corretto 21.0.10, Maven Wrapper 3.9.11, Spring Boot 4.0.6,
Spring Web 7.0.7, Flyway 11.14.1, JDBC PostgreSQL 42.7.10, Testcontainers 2.0.5,
PostgreSQL 16.14 (`postgres:16-alpine`) y Surefire/Failsafe 3.5.5.

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw clean verify
git diff --check
```

Resultado: **BUILD SUCCESS**, **12:07 min**, finalizado el **2026-09-05 a las 13:39:36 -03**.
Se contrastaron los reportes XML creados desde cero por esta corrida:

| Suite | XML de suites | Pruebas | Fallos | Errores | Omitidas |
| --- | ---: | ---: | ---: | ---: | ---: |
| Surefire | 141 | 4649 | 0 | 0 | 0 |
| Failsafe | 49 | 370 | 0 | 0 | 0 |
| Total | 190 | 5019 | 0 | 0 | 0 |

Incluye los ocho IT nuevos, las lecturas públicas de 13B/13C, registro/JWT y políticas web,
los procesos CLI empaquetados, persistencia/roles editoriales, agregados, instalación y upgrade
con historia. El gate integral pasó en su primera ejecución; no exigió cambios productivos ni
repeticiones. El único ajuste de test previo fue la causalidad del deadline descrita arriba.

El build acredita la ausencia de secretos en los dos JARs; sus hashes permanecieron iguales al
final y los bytes de las 28 migraciones empaquetadas coinciden con el repositorio. `git diff --check`
no informó incidencias. Los mensajes esperados de corrupción, timeout o rollback de las pruebas
negativas no se contabilizan como fallos: el resultado proviene de los XML y del build completo.
Los logs/reportes/artefactos de target y temporales no se versionan.

## Fronteras pendientes

Este cierre no habilita BACKEND-HANDOFF 1 ni la Tarea 3 frontend. Quedan requisitos públicos y del
actor, aceptación/registro atómicos, historial propio, idempotencia HTTP, carry-forward y enforcement.
El token requiredSetRevision conserva su semántica V28 de conjuntos completos aplicables: filtrar
pendientes/evidencia no lo cambia. Un receipt materializador en otra transacción no acredita por sí
solo la coherencia de una futura aceptación; esa composición requiere un diseño separado.

Continúan pendientes contenido legal definitivo aprobado, provisioning de entornos compartidos,
staging y deploy; email y Mercado Pago conservan sus fases posteriores. El titular es ADMIN y puede
crear empleados; USER no obtiene funciones reservadas al titular. OrdenFix administra reparaciones
y evidencia, no procesa pagos taller-cliente ni emite comprobantes fiscales o participa de su
acuerdo económico. La lectura documental no introduce cambios de producto sobre esas decisiones.

El frontend y sus archivos no versionados se preservan. El corte termina en un commit atómico local
`test(legal): cierra lectura documental publica`, sin push ni despliegue.
El siguiente bloque debe diseñar requisitos HTTP y su composición coherente
con V28 antes de implementar aceptación o enforcement.
