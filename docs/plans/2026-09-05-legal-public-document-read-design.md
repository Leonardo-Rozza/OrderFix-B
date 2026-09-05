# Corte 13 — Diseño de lectura pública de documentos legales

Fecha: 2026-09-05

Estado: diseño aprobado por el titular el 2026-09-05; 13A completado y verificado, 13B–13D pendientes.
Ejecución y evidencia por corte: [plan de implementación](2026-09-05-legal-public-document-read-implementation.md).

Baseline backend: `92a1fd1`, rama `codex/lanzamiento-publico-backend`.
Frontend: `7545201`, rama `codex/frontend-refactor-checkpoint`, sin cambios.

Fuentes:

- [Contrato wire vigente, §4.1.a](../../FRONTEND_INTEGRATION.md).
- [Contrato original y su actualización V28](2026-08-23-legal-api-contract-v1-design.md).
- [Cierre de núcleo y persistencia V28](2026-09-01-legal-required-set-aggregate-v28-closure.md).

## Objetivo y orden recomendado

La primera capacidad HTTP legal será consultar el catálogo documental y una versión exacta por
UUID. El bloque se divide en núcleo canónico, lector PostgreSQL, transporte protegido y cierre.
El primer corte de implementación, 13A, sólo crea la proyección y el cálculo de
`documentSetRevision`; todavía no registra controllers ni modifica autenticación.

| Enfoque | Beneficio | Trabajo adicional |
| --- | --- | --- |
| Catálogo y documento exacto — recomendado | Cierra una superficie de lectura independiente y verifica revisión, estados, paginación y caché | Exige un lector restringido y recorrer el catálogo completo para calcular su revisión |
| Requisitos públicos de registro primero | Lleva el token agregado V28 hasta el wire | Exige componer materialización y lectura de contenido con una observación coherente y permisos distintos |
| Aceptación y registro primero | Produce evidencia de aplicación | Exige resolver una conexión transaccional común, privilegios de usuario, idempotencia, metadata y compatibilidad del alta |

Este orden desarrolla el rollout ya acordado: lecturas antes de escritura y enforcement. No cambia
el contrato HTTP ni las decisiones de producto. El titular sigue siendo ADMIN; USER no representa
al taller ni accede a evidencia ajena. OrdenFix administra reparaciones/evidencia; no procesa pagos
taller-cliente, no emite comprobantes fiscales ni forma parte de ese acuerdo económico.

## Alcance del bloque 13

Incluye estos dos recursos, cuando se complete el corte de transporte:

```http
GET /api/public/documentos-legales?locale=es-AR&contexto=REGISTRO&page=0&size=20
GET /api/public/documentos-legales/{versionId}
```

Quedan para bloques posteriores requisitos públicos/autenticados, historial propio, aceptación,
registro con evidencia, idempotencia HTTP, carry-forward, scopes adicionales del ciclo de vida y
enforcement `428`. No se introduce una ruta de readiness pública: el contrato no define una.

V27/V28 permanecen congeladas. El bloque no importa contenido real, no publica borradores, no
modifica el frontend, no habilita email/Mercado Pago ni hace push, staging o deploy.
`BACKEND-HANDOFF 1` y la Tarea 3 frontend siguen cerrados hasta completar su gate integral posterior.

## Wire y disponibilidad por recurso

### Catálogo

- `locale` es obligatorio; v1 admite únicamente `es-AR`.
- `contexto` es opcional y admite los ocho valores de `ContextoLegal`; ausente se representa como
  `contexto: null`. El filtro usa los contextos históricos de cada versión, no los requisitos actuales.
- `page` empieza en cero y vale cero por defecto; `size` vale 20 por defecto y admite 1–100.
  Valores negativos, no enteros o fuera de rango producen `400`, sin fallback silencioso.
- Se exponen únicamente `VIGENTE`, `REEMPLAZADA` y `RETIRADA`, en el orden del enum
  `TipoDocumentoLegal`, luego `vigenteDesde` descendente y UUID ascendente.
- La respuesta mantiene `{contexto, locale, documentSetRevision, documentos, page}` y
  `page={size, number, totalElements, totalPages}`. Los resúmenes no incluyen Markdown, `href`,
  claves de manifiesto ni datos internos de publicación.
- Un filtro soportado sin ninguna versión que haya sido pública devuelve
  `503 CONTRATO_LEGAL_NO_DISPONIBLE`. Un archivo histórico existente sigue consultable aunque sólo
  tenga versiones terminales. Una página posterior al final devuelve `200` y `documentos: []`,
  conservando revisión y metadata del catálogo completo.

El catálogo es un archivo documental, no una acreditación de que existan todos los requisitos de
registro vigentes. Un retiro puede volver indisponible un requisito sin ocultar el documento histórico.
La readiness editorial y la disponibilidad de requisitos no sustituyen esta regla por recurso.

### Documento exacto

La respuesta es el `DocumentoLegalVersion` ya acordado, con `contenidoMarkdown` original, digest
hexadecimal, locale y fecha RFC 3339 UTC. Se consulta una versión pública en una sola proyección;
no se devuelve una entidad JPA con relaciones lazy ni información editorial privada.

UUID malformado, desconocido, BORRADOR y PUBLICADA responden el mismo
`404 DOCUMENTO_LEGAL_NO_ENCONTRADO`. El controller recibe el segmento como string y realiza la
validación contractual; el binding automático a UUID no debe convertir el caso malformado en `400`.
Una indisponibilidad de PostgreSQL o fallo de acreditación es `503`, no una falsa ausencia `404`.

## Revisión documental canónica

`documentSetRevision = "sha256:" + SHA-256(RFC8785(proyección))`, donde la proyección lógica es:

```json
{
  "contexto": null,
  "locale": "es-AR",
  "documentos": [
    {
      "id": "...",
      "tipo": "...",
      "version": "...",
      "titulo": "...",
      "sha256": "...",
      "vigenteDesde": "...",
      "estado": "...",
      "locale": "es-AR"
    }
  ]
}
```

Los documentos son todos los resúmenes visibles del filtro, antes de paginar. No participan
`documentSetRevision`, `page`, Markdown, actor, evidencia ni requisitos. El orden de propiedades
lo define RFC 8785; el orden de documentos es el contractual y no puede depender de la colección
recibida, de un `Set` o del orden accidental de la base.

El lector produce ese orden y el cálculo de una sola pasada lo exige. Una entrada fuera de orden
se rechaza; el calculador no retiene todo el histórico para ordenarlo en memoria. La consulta evita
duplicados con una fila por versión y el recorrido valida que no repita una posición contractual.

Una modificación representable, incluido `estado`, cambia la revisión. Páginas distintas del mismo
filtro comparten revisión; cambiar page/size sólo cambia su ETag. Un contexto excluido no introduce
filas en esa proyección. El filtro forma parte del hash aun cuando dos filtros devuelvan iguales IDs.

Se congela un único render de UUID canónico y fecha `Instant` UTC mediante `ISO_INSTANT`, usado tanto
en los bytes canónicos como en el DTO. Los golden incluyen segundos enteros y fracciones de
microsegundo, Unicode y escapes. El orden de UUID debe coincidir con PostgreSQL; se prueba con IDs
a ambos lados del bit alto, sin asumir que cualquier comparador Java reproduce el orden SQL.

La implementación extiende únicamente el adaptador RFC 8785 con una entrada tipada documental.
No expone un hash de JSON arbitrario ni cambia los bytes ya congelados de SCOPE_V1, AGGREGATE_V1,
procedencia o fingerprints editoriales. Los ejemplos del wire siguen siendo ilustrativos.

`requiredSetRevision` conserva la semántica V28 de conjuntos completos aplicables, estable al
filtrar evidencia, incluso con `requisitos: []`. El texto original de agosto que prometía un hash
de la respuesta filtrada queda corregido documentalmente; no se implementará esa semántica antigua.

## Lector PostgreSQL y coherencia

Se propone una frontera `PUBLIC_DOCUMENT_READ` separada del contexto web/JPA y de los contextos
importador, editorial y materializador. Una fachada estrecha entrega valores documentales al HTTP;
el resto del contexto no se publica como beans de acceso general en la aplicación web.

El lector usa una credencial dedicada NOINHERIT, sin ownership, DDL, DML, secuencias ni escritura
de evidencia. Su allowlist de datos comprende `legal_documento_lineas`, `legal_documento_versiones`,
`legal_documento_contextos` y `flyway_schema_history`. No recibe usuarios, metadata ni capacidad
para materializar agregados. El rol materializador existente mantiene sus permisos exactos.

El preflight de esquema reutiliza la acreditación V27/V28 instalada y el de privilegios es propio
del lector. Debe comprobar permisos efectivos, tablas/dependencias, funciones, search_path y
credencial real; no asumir que una etiqueta read-only del framework restringe una credencial amplia.
El contexto no migra Flyway por su cuenta ni admite fallback a credenciales web/owner.

La ausencia de EXECUTE sobre funciones legales debe ser efectiva: V28 revoca PUBLIC en nueve
funciones, pero V27 conserva permisos públicos históricos hasta provisionar roles restringidos.
No basta con omitir grants directos al lector. El preflight debe rechazar permisos heredados de
PUBLIC/memberships y el provisioning debe contemplar la revocación y regrants nominales necesarios.
Es una modificación global de ACL que requiere inventariar consumidores antes de aplicarla a una
base compartida; en 13B sólo se acredita sobre bases efímeras. No se reescriben las migraciones.

Cada operación abre una sesión dedicada `REQUIRES_NEW/READ_COMMITTED`, con read-only efectivo
acreditado en PostgreSQL. Ejecuta preflights, adquiere la key editorial en modo shared y lee después
del lock. El gate read-only existente usa exclusive: se agregará una variante shared explícita,
conservando la semántica de sus callers históricos. No se requiere `FOR SHARE` de filas ni permisos
UPDATE para este lector; el advisory compartido impide las mutaciones editoriales cooperativas.

Catálogo, revisión, conteos y página se obtienen dentro de esa misma observación. No se mezclan un
hash leído antes de la promoción con una página leída después. El documento exacto también obtiene
contenido, estado y ETag desde una única observación protegida. Se devuelve éxito sólo al terminar
correctamente la transacción, sin retry oculto.

## Capacidad del archivo histórico

El límite de documentos por manifiesto no es un límite válido para el archivo acumulado. La revisión
debe incluir todo el filtro: no se agrega `LIMIT 128`, no se hashea sólo la página y no se devuelve
éxito parcial al alcanzar un tope técnico.

Se propone una consulta ordenada de resúmenes con cursor JDBC, fetch size acotado y autocommit
desactivado. Un recorrido calcula el digest y totalElements, conservando sólo los resúmenes de la
página solicitada, hasta 100. El SELECT no incluye Markdown y usa EXISTS para el filtro, de modo que
cada versión aparece una sola vez. No hace una consulta por versión ni un COUNT fuera del snapshot.

La memoria del recorrido queda acotada al fetch, la página y el estado del digest; el trabajo y los
viajes de cursor crecen con N. Los tamaños de strings se validan contra el contrato persistido. Se
usan contadores y cálculos de página comprobados, sin overflow ni pérdida silenciosa de precisión
en los números expuestos al frontend.

El lector tendrá presupuestos HTTP propios: inicialmente un deadline cooperativo de 15 s, 5 s por
sentencia y 1 s de espera editorial. No modifica los presupuestos editoriales vigentes. El reloj
monotónico comienza antes de pedir conexión y abarca adquisición, preflights, lock, ejecución/FETCH,
canonicalización y finalización. Se comprueba entre fases y durante el recorrido; una respuesta
que ya agotó el deadline no se entrega como éxito.

13B debe acotar además adquisición de conexión y llamadas bloqueantes del driver, ajustar los
timeouts al tiempo restante y acreditar cancelación/cierre del cursor. Un chequeo Java no interrumpe
por sí solo un JDBC bloqueado ni garantiza una duración máxima de pared: el corte registra por
separado esos límites y la latencia de cancelación. Agotamiento, cancelación o resultado incompleto
producen `503`, sin enviar headers/body parciales. Los valores iniciales no son un SLA ni campos
nuevos del contrato.

No se introduce un puntero de revisión documental ni caché persistida en esta etapa. Si las
mediciones posteriores exigen precomputarla, se diseña otro corte sin cambiar este wire.

## Transporte, seguridad y caché

La propiedad propuesta `ordenfix.legal.public-documents.enabled` nace apagada. Al habilitarla debe
existir la configuración completa del datasource restringido; no se reutiliza implícitamente
`spring.datasource.*` del contexto web. Deshabilitada, no hay nuevos mappings ni nuevas excepciones
de autenticación. No habilita requisitos, aceptación ni enforcement.

Una sola clasificación de método/path, normalizada respecto del context path, se comparte entre
SecurityConfig, bypass de JwtFilter y rate policy. El catálogo exacto y el documento con exactamente
un segmento son públicos sólo para GET. El segmento de documento se clasifica antes de validar UUID,
para que un UUID malformado conserve el `404` público contractual.

No se autoriza `/api/public/**`; rutas vecinas, subrutas adicionales y otros métodos no heredan
la excepción. HEAD no hereda autorización pública de GET; OPTIONS de preflight sigue a cargo de
CORS. `/api/public/requisitos-legales` sólo se incorporará cuando tenga implementación y su propio
gate aprobado. Un Authorization inválido no transforma estas lecturas públicas en datos privados.

El rate limit por IP es configurable y aplica también a requests condicionales y UUID inválidos.
Como punto inicial se propone 60 requests/minuto por IP para la familia documental, sin fijarlo
como contrato wire; los headers siguen siendo autoritativos. La IP remota es el default y no se
amplía la confianza en forwarded headers. La política incluye limpieza de ventanas como las actuales.

CORS conserva Authorization y expone Retry-After, X-RateLimit-Limit y X-RateLimit-Remaining. No se
requiere exponer ETag ni cambiar Axios para gestionar manualmente el caché del navegador.

| Recurso | Cache-Control | ETag |
| --- | --- | --- |
| Catálogo | `public, max-age=0, must-revalidate` | `W/"<documentSetRevision>:p=<page>:s=<size>"` |
| Documento VIGENTE | `public, max-age=0, must-revalidate` | `W/"doc:<id>:VIGENTE:<sha256>"` |
| Documento REEMPLAZADA/RETIRADA | `public, max-age=31536000, immutable` | `W/"doc:<id>:<estado>:<sha256>"` |
| Error legal | `no-store` | Sin revalidación exitosa |

Se resuelve y acredita el recurso antes de comparar If-None-Match. Se usa el procesamiento HTTP
condicional de Spring para comparación débil, listas y `*`, sin inventar un formato alternativo.
Una coincidencia válida produce `304` sin body y conserva headers de caché/rate limit aplicables.
No se usa un ETag conocido para esconder indisponibilidad o convertir un borrador en `304`.

Los errores conservan ApiError. Locale/contexto inválidos usan los códigos y details exactos del
wire; el UUID no disponible usa `404 DOCUMENTO_LEGAL_NO_ENCONTRADO`; un fallo de contrato,
acreditación o disponibilidad usa `503 CONTRATO_LEGAL_NO_DISPONIBLE`. Para documento exacto, el
contexto del error de indisponibilidad es null y la locale de v1 es es-AR. No se filtran SQL,
credenciales, paths internos ni estado editorial oculto. Los errores de paginación conservan el
`400` de validación sin inventar un código legal nuevo. El `429` mantiene el envelope ya existente.

## Secuencia de implementación propuesta

Cada subcorte debe tener su whitelist, pruebas focalizadas y un commit atómico local. El plan
detallado enlazado al inicio registra la ejecución; las siguientes etapas fijan sus dependencias.

| Corte | Resultado revisable | Gate |
| --- | --- | --- |
| 13A | Proyección tipada y `documentSetRevision`, formato de resúmenes, orden y streaming canónico; sin HTTP/JDBC nuevo | Golden RFC 8785 y regresión de los calculadores congelados |
| 13B | Contexto/rol lector, preflight, gate read-only shared, cursor y disponibilidad por recurso; sin controllers | PostgreSQL 16 restringido, catálogo/historia, concurrencia y aislamiento |
| 13C | Los dos GET, DTO wire, flag apagado, clasificación pública, rate limit, CORS, errores y caché conjuntamente | Contrato MockMvc/seguridad, flags y lectura real con fixtures |
| 13D | Capacidad, concurrencia HTTP, documentación y cierre | Gate integral `clean verify`, inventario y hashes |

13A se limita a tipos/calculador documentales y una extensión tipada de `Rfc8785Canonicalizer`.
Los nombres propuestos son `LegalDocumentCatalogProjection`, `LegalDocumentSummary` y
`LegalDocumentSetRevisionCalculator`, bajo `legal.manifest.core`. El API de cálculo debe permitir
un recorrido ordenado de una sola pasada, sin exigir una lista completa en producción. Los tipos
de transporte se incorporan en 13C; no se crean DTOs de registro ni se aceptan campos legales que
luego se descarten.

### Criterios de prueba

- Golden: bytes canónicos y digest conocido; coincidencia con RFC 8785; Unicode/escapes, timestamps
  y UUIDs límite. Orden contractual y locale/contexto explícitos. No usar como oráculo el propio
  calculador bajo prueba.
- Semántica: mismo filtro comparte revisión entre páginas; cambio de estado/título/fecha/digest
  cambia revisión; versiones ocultas y contextos excluidos no entran. No confundir revisión
  documental con requiredSetRevision ni alterar los vectores V27/V28.
- PostgreSQL: versión visible/histórica, filtro por contexto publicado, catálogo sin datos `503`,
  página vacía válida, consultas sin Markdown en listado, bytes/digest del documento exacto y
  ausencia de DML. El rol lector no puede cambiar una fila ni acceder a usuarios/evidencia.
- Concurrencia: dos lectores shared avanzan; writer exclusivo espera; lector tras una promoción
  obtiene snapshot completo. Hash, página, total y estado pertenecen a la misma observación.
- Capacidad: historia mayor al límite de un manifiesto, cursor real, una sola pasada, sin N+1,
  page/size extremos, cancelación y deadline. Exceder un presupuesto nunca confirma una revisión
  truncada ni un response parcial.
- HTTP: flag apagado sin nuevas excepciones/mappings; GET públicos exactos con y sin token;
  UUID malformado/oculto/desconocido `404`; métodos/path vecinos cerrados; query/context path;
  `400/404/503/429`, headers CORS, revalidación débil/listas/`*`, `304` sin body y fallos `no-store`.
- Regresión al tocar seguridad: AuthTests, JwtSecurityIntegrationTests,
  PublicEndpointRateLimitFilterTests y ApiErrorContractTests. Registro histórico, principal DB y
  tenant permanecen iguales durante todo el bloque.

Se ejecutan pruebas focalizadas por subcorte. El clean verify de 12G (4366 Surefire + 306 Failsafe)
es evidencia del baseline, no valida código HTTP futuro. Se reserva un gate integral nuevo para
13D o para un cambio transversal/fallo que requiera ampliarlo.

## Fronteras de los bloques posteriores

Requisitos públicos y pendientes deben calcular el agregado de conjuntos completos antes de
hidratar/filtrar la respuesta. El materializador actual no tiene permisos para contenido documental
ni evidencia. Un receipt confirmado en una transacción no acredita por sí mismo que siga actual
en otra: una composición separada tendrá que revalidar los punteros bajo shared.

Para aceptación/registro se recomienda estudiar un contexto consumidor con rol, preflight y
frontera propios, que reutilice el store en la transacción del caller. No se amplía la allowlist
del materializador ni se anida su servicio REQUIRES_NEW para afirmar atomicidad. El registro debe
confirmar taller, ADMIN, suscripción inicial, lote, actos, documentos, metadata e idempotencia en
una conexión/transacción común; otra conexión no ve al actor todavía no confirmado.

Los guards actuales son SECURITY INVOKER y usan FOR SHARE también sobre users. El consumidor
necesita capacidades UPDATE por columna además de SELECT/INSERT donde ese protocolo lo exige.
Los guards legales limitan updates sobre evidencia, pero no se identificó una protección equivalente
genérica para users: no se promete un consumidor sin modificación efectiva de usuarios hasta
cerrar ese diseño. Si se exige encapsular esa capacidad con nuevos guards/wrappers SQL, se diseña
V29. V29 no es necesaria sólo por agregar HTTP o un nuevo contexto Java; V28 nunca se edita.

Hasta entonces el alta conserva su contrato histórico. La futura escritura respetará el rollout
acordado: ausencia completa del bloque legal con enforcement apagado, rechazo de combinaciones
parciales y `428` al faltar evidencia cuando el enforcement se active. Los errores/replays legales
`409/428/503`, el nuevo JWT de un replay de alta y la propia evidencia requieren sus gates completos.

## Validación de este corte documental

Se contrastaron wire, repositorios, gate, permisos, registro y filtros contra el baseline. Tres
revisiones independientes cubrieron contrato de lectura, transacciones/privilegios y seguridad.
No se ejecutó Maven porque este corte no modifica código, tests, dependencias ni configuración.

Whitelist del commit: este diseño y la actualización puntual del contrato de agosto para dejar
explícita la semántica V28 ya aprobada. Se verifican diff, enlaces y preservación de V27/V28.
El commit original `10bf5b5` documentó la propuesta. El titular aprobó después comenzar 13A;
esa aprobación no activa HTTP ni abre BACKEND-HANDOFF 1. La evidencia de implementación se registra
por separado en el plan enlazado al inicio.
