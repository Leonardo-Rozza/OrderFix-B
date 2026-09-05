# Fase 2.1 — Contrato API legal versionado v1

Fecha: 2026-08-23

Estado: contrato HTTP congelado; implementación HTTP pendiente. Núcleo/persistencia V28 cerrados.

Actualización de continuidad (2026-09-05): el
[cierre V28](2026-09-01-legal-required-set-aggregate-v28-closure.md) acredita núcleo y persistencia
internos; el [diseño de lectura documental](2026-09-05-legal-public-document-read-design.md) propone
el primer bloque HTTP. `BACKEND-HANDOFF 1` continúa cerrado. La semántica agregada de
`requiredSetRevision` indicada abajo sustituye la formulación inicial basada en una respuesta filtrada.

Alcance: diseño y contrato HTTP del backend; sin tablas, migraciones, controllers, seeds ni UI

## Objetivo

Congelar una única forma implementable para publicar documentos legales, obtener requisitos vigentes,
registrar aceptaciones y consultar la evidencia propia. Esta fase elimina las ambigüedades entre el
plan de lanzamiento del frontend y el backend antes de persistir información contractual.

La fuente normativa del wire queda en `FRONTEND_INTEGRATION.md`, sección **4.1.a**. Este documento
conserva las decisiones, invariantes, compatibilidad y secuencia de despliegue; no duplica todos los
ejemplos del contrato.

## Contexto original al 2026-08-23

- El backend todavía no posee entidades, endpoints ni migraciones legales.
- `POST /api/auth/register` aún acepta el request histórico sin evidencia legal.
- `/api/public/**` todavía no está autorizado por `SecurityConfig` ni exceptuado del filtro JWT.
- `ApiError` ya permite agregar `code` y `details` sin romper respuestas existentes.
- La Fase 2.0 garantiza un único `ADMIN` por taller. En el wire y el JWT se mantienen `ADMIN` y
  `USER`; `ADMIN_TITULAR` es sólo la audiencia legal del `ADMIN` único, no un rol nuevo.
- Los borradores y el manifiesto de publicación viven en el frontend. No son contenido vigente ni un
  fallback aceptable para el backend.

## Decisiones del contrato

### Recursos

El contrato v1 agrega, de forma aditiva:

```http
GET  /api/public/documentos-legales?locale=es-AR&contexto=REGISTRO&page=0&size=20
GET  /api/public/documentos-legales/{versionId}
GET  /api/public/requisitos-legales?contexto=REGISTRO&locale=es-AR
GET  /api/requisitos-legales
GET  /api/aceptaciones-legales?page=0&size=20
POST /api/aceptaciones-legales
```

También se extiende `POST /api/auth/register` con `requiredSetRevision`,
`aceptacionesLegales` e `Idempotency-Key`.

El GET de aceptaciones se incorpora porque el diseño ya promete que cada usuario puede consultar su
propia evidencia. Un `ADMIN` no obtiene la evidencia técnica de empleados y un `USER` no obtiene la
del titular.

### Identidad, tenant y roles

1. Los endpoints autenticados derivan usuario, taller y rol de la sesión contrastada contra base.
2. Ningún DTO legal acepta `userId`, `tallerId`, `role` ni audiencia enviados por el navegador.
3. El único `ADMIN` persistido por taller corresponde a la audiencia de manifiesto
   `ADMIN_TITULAR`; el JWT continúa exponiendo `ROLE_ADMIN`.
4. `USER` sólo acepta sus requisitos personales. No representa al taller ni acepta acuerdos PRO,
   cierre o tratamiento de datos por cuenta del titular.
5. La aceptación siempre pertenece al actor autenticado. No existe aceptación “en nombre de” otro
   usuario.
6. Un recurso privado de otro tenant conserva la política general de responder `404`, no `403`.

La futura allowlist no usa `permitAll("/api/public/**")`: autoriza sólo `HttpMethod.GET` sobre las
rutas legales públicas exactas. `JwtFilter` y rate limit deben compartir esa clasificación explícita,
para que agregar otro recurso bajo `/api/public/` no lo vuelva público por accidente.

### Estados documentales e inmutabilidad

El ciclo editorial backend será:

```text
BORRADOR -> PUBLICADA -> VIGENTE -> REEMPLAZADA | RETIRADA
```

- `BORRADOR` y `PUBLICADA` nunca se exponen a APIs consumidoras, públicas o autenticadas, incluso si
  se conoce el UUID.
- `VIGENTE`, `REEMPLAZADA` y `RETIRADA` sí son estados públicos.
- `REEMPLAZADA` y `RETIRADA` son terminales.
- El contenido, título, tipo, locale, versión, fecha de vigencia y digest de una versión publicada
  son inmutables. Cambiar texto crea otra versión.
- El catálogo revalidable expone versiones vigentes e históricas para que el Centro de Confianza
  pueda mostrar el archivo. Devuelve resúmenes paginados sin Markdown; el contenido completo vive en
  el recurso exacto por UUID.
- La consulta exacta de una versión `VIGENTE` debe revalidarse porque su campo `estado` todavía puede
  cambiar. Una versión terminal sí puede usar caché `immutable`.

Esta distinción evita cachear para siempre una representación que todavía diga `VIGENTE` después de
haber sido reemplazada.

Como `estado` pertenece a la versión y no a cada contexto, una transición es global. Para un mismo
tipo+locale, las versiones `VIGENTE` deben tener conjuntos de contextos disjuntos. Reemplazar una
versión multicontexto exige cubrir transaccionalmente todos sus contextos con una o más versiones
nuevas vigentes, también disjuntas; no puede reemplazarse sólo en un contexto y dejar la misma versión
vigente en otro. Una transición a `REEMPLAZADA` exige cobertura total y el dry-run rechaza huecos o
solapamientos. `RETIRADA` es la operación explícita de emergencia que puede dejar un hueco deliberado
en todos sus contextos; requiere motivo auditable y el dry-run informa los conjuntos afectados. Un
requisito que quede sin todos sus documentos vigentes falla cerrado con `503` hasta publicar una
sucesora; nunca continúa usando la versión retirada.

El catálogo usa `page=0`, `size=20` por defecto y máximo 100. Su filtro `contexto` consulta el
snapshot de contextos de cada versión publicada, no los requisitos que hoy la referencian. Un filtro
soportado sin ninguna publicación válida falla con `503`; sólo una página posterior a la última de
un catálogo existente devuelve `200` vacío.

### Título y contenido canónico

El manifiesto de publicación no posee un campo `titulo`. El importador backend debe obtenerlo del
primer encabezado Markdown de nivel uno (`# Título`) y congelarlo con la versión. Una publicación sin
un H1 no vacío se rechaza en dry-run.

`sha256` y `afirmacionSha256` son exactamente 64 caracteres hexadecimales minúsculos. El importador
exige primero bytes UTF-8 válidos, Unicode NFC, saltos LF y ausencia de BOM; luego hashea esos bytes
originales sin normalizar ni reescribir. Para una afirmación, hashea los bytes UTF-8 de su string
exacto sujeto a las mismas invariantes. El JSON puede escapar caracteres, pero eso no altera el
valor decodificado.

### Revisiones de conjuntos

`documentSetRevision` y `requiredSetRevision` usan el formato servidor `sha256:<64-hex>`, pero son
valores opacos para el frontend: el cliente sólo los conserva, compara y reenvía.

`requiredSetRevision` usa `AGGREGATE_V1`: se calcula sobre las revisiones de los conjuntos completos
de scopes aplicables resueltos por el servidor, antes de filtrar por actor/evidencia. Su proyección,
orden y bytes están congelados en el
[diseño V28](2026-09-01-legal-required-set-aggregate-v28-design.md). No se calcula sobre el response
de pendientes ni incluye la procedencia física interna.

`documentSetRevision` conserva la proyección `{contexto, locale, documentos}` con el catálogo
filtrado completo de resúmenes, antes de paginar y sin campos de revisión/página. Para ambas
proyecciones:

1. se conserva el orden contractual de los arrays;
2. se canonicaliza como JSON mediante RFC 8785;
3. se calcula SHA-256 sobre los bytes UTF-8 canónicos;
4. se antepone `sha256:`.

La revisión documental cambia ante cualquier cambio representable del catálogo, no sólo ante un
nuevo ID. La revisión requerida cambia al cambiar las revisiones completas o la composición de los
scopes incluidos, no al satisfacer pendientes. Los requisitos mantienen el orden del manifiesto
importado. El catálogo ordena por el orden exacto del
enum `TipoDocumentoLegal` publicado en `FRONTEND_INTEGRATION.md`, luego `vigenteDesde` descendente y
finalmente UUID ascendente.

Un conjunto público de registro vacío o incompleto responde `503`; nunca habilita un alta sin
contrato. Un usuario autenticado que ya satisfizo todos sus requisitos recibe legítimamente
`requisitos: []` y conserva la revisión de sus conjuntos completos aplicables. No representa un
agregado sin scopes ni genera un hash del conjunto vacío.

### Reaceptación y herencia editorial

El manifiesto posee `requiresReacceptance` tanto en cada versión documental como en cada versión de
requisito. Esos flags son metadata interna de publicación y no se exponen en el DTO consumidor: el
backend ya devuelve sólo los requisitos que ese actor debe evidenciar.

`key` identifica una línea estable y no una versión. El importador nunca permite reutilizar un key de
documento con otro tipo/locale ni un key de requisito con otro contexto, audiencia/roles o tipo de
acto. Cada versión conserva key, UUID, ordinal y flags para recorrer toda la línea, incluso si una
versión intermedia dejó de estar vigente.

Para un requisito vigente aplicable al actor:

1. una evidencia de su versión exacta y documentos exactos siempre lo satisface;
2. sin ninguna evidencia previa del mismo `requirement.key`, queda pendiente aunque sus flags sean
   `false`;
3. con evidencia de una versión anterior del mismo key, se hereda sólo si **todas** las versiones de
   requisito posteriores a la evidenciada y hasta la vigente tienen
   `requiresReacceptance=false`;
4. cada documento vigente referenciado debe tener el mismo `document.key` que un documento de la
   evidencia; un key agregado nunca se hereda sin presentarlo;
5. para cada key documental conservado, todas las versiones posteriores a la evidenciada y hasta la
   vigente deben tener `requiresReacceptance=false`;
6. por lo tanto, los flags de requisito y documentos se combinan con OR a través de toda la cadena:
   un solo `true` impide la herencia, aunque una versión posterior vuelva a `false`.

Una herencia válida sólo marca el requisito como satisfecho al calcular pendientes. No crea una
aceptación nueva, no cambia `aceptadoEn` y no fabrica evidencia de que el actor vio el texto nuevo;
el historial propio continúa mostrando la evidencia realmente emitida. Cambiar de key, agregar un
documento o marcar cualquier tramo material con `true` obliga a una aceptación explícita. Para
registro no existe evidencia anterior, por lo que todos los requisitos obligatorios vigentes deben
enviarse siempre.

### Escritura y fuente de verdad

El navegador envía IDs, tipo de acto y digests para demostrar qué snapshot presentó. Esos valores no
se persisten como verdad por confiar en el cliente: el backend compara todo contra sus versiones
vigentes y guarda sus propias copias canónicas.

La aceptación autenticada devuelve `204` sin body. El frontend invalida requisitos y aceptaciones y
vuelve a consultar. En el registro, taller, titular, suscripción inicial, evidencia e idempotencia se
confirman en una sola transacción; cualquier error revierte todo.

Cada evidencia guarda internamente el snapshot canónico de userId, tallerId, rol wire, audiencia
legal, requisito/key/acto/afirmación, documentos/keys/digests, revisión/lote y hora del servidor como
`Instant` UTC. También captura IP resuelta únicamente desde la cadena de proxies confiables
configurada y un User-Agent acotado a 512 caracteres. No se confía ciegamente en
`X-Forwarded-For`; IP/User-Agent se protegen y retienen según la política legal aprobada. El GET
propio omite esos metadatos técnicos.

La lista enviada:

- incluye todos los requisitos con `requerido=true`;
- puede incluir cualquier subconjunto de requisitos opcionales conocidos y confirmados;
- `REQUISITO_FALTANTE` sólo aplica a obligatorios;
- para cada requisito enviado, incluye exactamente sus documentos, sin extras, omisiones ni
  duplicados;
- no contiene requisitos desconocidos ni duplicados;
- usa `confirmado=true` literalmente;
- se compara como conjunto, aunque el orden del request no concede significado adicional.

### Idempotencia

Los POST legales usan `Idempotency-Key`, sin prefijo `X-`. La clave es un UUID v4 canónico generado
una sola vez por intento lógico. El scope autenticado es método + plantilla de ruta + usuario; el
registro público usa método + plantilla de ruta y una clave globalmente aleatoria.

El fingerprint es HMAC-SHA-256 con secreto servidor sobre método, plantilla, scope y DTO de negocio
normalizado/canonicalizado. El orden y whitespace de propiedades no importan; las aceptaciones se
ordenan por requisito y sus documentos por versión. La contraseña de registro participa dentro del
HMAC para detectar un payload distinto, pero no se persiste ni se deriva por separado en la tabla
idempotente.

- si ya existe una operación exitosa con el mismo scope, clave y fingerprint: reutiliza la operación
  sin duplicar evidencia;
- si esa operación existente tiene otro fingerprint: `409 IDEMPOTENCY_KEY_REUTILIZADA`;
- si la misma clave/fingerprint está en curso, el segundo request espera hasta cinco segundos; si no
  finaliza devuelve `409 IDEMPOTENCY_EN_PROGRESO` con `Retry-After: 1`;
- una reserva en curso es transitoria; sólo un commit exitoso guarda el resultado/fingerprint y
  consume la clave;
- una validación, un `409` por revisión vieja o un fallo no confirmado no se registran como éxito;
  si el commit ocurrió antes de un corte o `5xx` observado por el cliente, el éxito idempotente quedó
  confirmado en la misma transacción;
- después de modificar el formulario o aceptar un conjunto actualizado, el frontend crea otra clave;
- el backend guarda un HMAC de la clave y un fingerprint protegido, nunca la clave, contraseña, body
  completo ni JWT en claro;
- la garantía de replay por clave dura al menos 24 horas; la unicidad actor + versión de requisito es
  permanente y evita evidencia duplicada entre pestañas.

En un replay exitoso de registro se reutilizan los IDs creados, se vuelve a verificar la contraseña
recibida contra el hash del usuario y, si las precondiciones actuales de sesión lo permiten, se emite
un JWT nuevo con el mismo schema de respuesta `201`; `emailVerificado` refleja el estado actual. Si
cambió la contraseña o el usuario/taller no puede iniciar sesión, la creación no se repite ni se
revierte y se devuelve el error actual de autenticación/estado de cuenta. No se persiste ni reproduce
un token anterior. La operación se resuelve antes de comparar la revisión legal actual, para que una
respuesta perdida no se convierta en un falso conflicto después de una publicación nueva.

La unicidad actor + requisitoVersionId resuelve también dos claves distintas concurrentes. Si la
evidencia canónica ya fue confirmada, el segundo request termina en `204`, no en un conflicto genérico
de integridad. Esa deduplicación exacta ocurre antes del chequeo de revisión; el frontend refetchea
después del `204`, por lo que cualquier requisito nuevo continúa pendiente y mantiene el gate.

### Caché HTTP

| Recurso | Política |
|---|---|
| Catálogo público | `public, max-age=0, must-revalidate` + ETag débil de set/página |
| Requisitos públicos | `public, max-age=0, must-revalidate` + ETag débil de `requiredSetRevision` |
| Documento exacto `VIGENTE` | `public, max-age=0, must-revalidate` + ETag débil lógico |
| Documento exacto terminal | `public, max-age=31536000, immutable` + ETag débil lógico |
| Requisitos autenticados | `private, no-store` |
| Aceptaciones propias | `private, no-store` |
| POST y errores legales | `no-store` |

Los ETag usan `W/` porque validan el snapshot lógico canónico, no identidad byte a byte entre
Jackson, compresión y proxies. El del catálogo agrega `page` y `size`; el de un documento incluye
UUID, estado y digest. Un `If-None-Match` válido sobre un GET público revalidable devuelve `304` sin
body. El frontend delega la revalidación al caché HTTP del navegador y no construye manualmente un
`304` con Axios.

`no-store` HTTP no elimina por sí solo la memoria de React Query. Al integrar los endpoints privados,
el frontend debe usar `staleTime: 0`, recolección inmediata o limpieza explícita, claves ligadas a la
sesión y purga antes de cambiar de identidad o tenant.

### Errores y recuperación

Se conserva el envelope existente de `ApiError`; `code` y `details` se agregan sólo donde el cliente
debe decidir comportamiento. El wire exacto está en `FRONTEND_INTEGRATION.md`.

Códigos mínimos de esta fase:

- `400 ACEPTACION_LEGAL_INVALIDA`;
- `400 CONTEXTO_LEGAL_NO_SOPORTADO`;
- `400 LOCALE_LEGAL_NO_SOPORTADO`;
- `400 IDEMPOTENCY_KEY_REQUERIDA`;
- `400 IDEMPOTENCY_KEY_INVALIDA`;
- `404 DOCUMENTO_LEGAL_NO_ENCONTRADO`;
- `409 DOCUMENTOS_LEGALES_DESACTUALIZADOS`;
- `409 IDEMPOTENCY_KEY_REUTILIZADA`;
- `409 IDEMPOTENCY_EN_PROGRESO`;
- `428 ACEPTACION_LEGAL_REQUERIDA`;
- `503 CONTRATO_LEGAL_NO_DISPONIBLE`.

Si la revisión no coincide, el backend responde primero el `409` con el conjunto actual. Si la
revisión coincide pero IDs, actos, confirmaciones o digests no coinciden, responde `400
ACEPTACION_LEGAL_INVALIDA`. Nunca devuelve ni registra contraseña, token o el body original dentro
de un error.

`428 ACEPTACION_LEGAL_REQUERIDA` se usa para una sesión válida o un alta sin evidencia requerida; no
significa logout. Sus `details` contienen el conjunto necesario para resolver el gate. Los endpoints
que resuelven ese estado nunca pueden responder el mismo `428`:

- documentos públicos;
- requisitos públicos y autenticados;
- lectura y creación de aceptaciones propias;
- verificación/reenvío de email;
- estado de cuenta y logout cuando esos recursos existan.

El gate tampoco puede impedir que una persona rechace el nuevo contrato y ejerza una vía de salida
o un derecho sobre sus datos. Aunque esas operaciones no resuelvan la aceptación, quedan fuera del
interceptor de `428`: cancelar la renovación o suscripción, pedir la baja del propio acceso o del
servicio, consultar/iniciar/descargar una exportación, cerrar la cuenta y solicitar
eliminación/supresión. La excepción al gate no concede permisos: cada operación conserva
autenticación, rol, tenant, reautenticación y controles de ciclo de vida propios. La implementación
allowlistea cada combinación exacta de método y ruta —incluidas temporalmente `POST
/api/pagos/suscripcion/cancelar` y `GET /api/export/excel` mientras existan— y agrega sus reemplazos
explícitamente; nunca usa un wildcard de cuenta, pagos o exportación.

Si falta una publicación válida o se viola una invariante del conjunto, la respuesta es `503
CONTRATO_LEGAL_NO_DISPONIBLE`; no se devuelve un array parcial o vacío como si fuera aceptable.

Los GET públicos quedan bajo rate limit por IP configurable y conservan el contrato general de
headers `X-RateLimit-*`, `429` y `Retry-After`; el umbral numérico no forma parte de v1.

El `CorsConfig` actual sólo expone `Authorization`, por lo que un navegador cross-origin no puede
leer esos headers de respuesta. La implementación del contrato debe conservar `Authorization` y
agregar exactamente `Retry-After`, `X-RateLimit-Limit` y `X-RateLimit-Remaining` a
`Access-Control-Expose-Headers`. Esto aplica tanto al `409 IDEMPOTENCY_EN_PROGRESO` como a respuestas
limitadas/`429`. No hace falta exponer `ETag`: la revalidación queda a cargo del caché HTTP del
navegador y el JavaScript usa las revisiones incluidas en el body.

## Compatibilidad y despliegue

No se agrega `/v1` a la URL porque el backend actual es no versionado. El contrato se evoluciona de
forma aditiva: los clientes ignoran campos nuevos; remover, renombrar o cambiar semántica requiere
una versión nueva del recurso. Un enum nuevo no puede habilitar una aceptación si el cliente no logra
renderizar el requisito completo.

Orden obligatorio de rollout:

1. agregar persistencia, lectura, escritura, caché, errores y allowlists con enforcement apagado; un
   request legacy sin los tres elementos legales continúa, pero cualquier bloque legal presente se
   exige completo, se valida y se persiste incluso con enforcement apagado;
2. importar una publicación revisada en staging y ejecutar el dry-run/readiness;
3. integrar frontend, incluido `409`, `428`, historial y limpieza de caché privada;
4. comenzar a enviar evidencia e idempotencia desde el registro nuevo;
5. confirmar mediante telemetría que el cliente compatible está desplegado;
6. activar enforcement de registro mediante configuración;
7. programar la reaceptación de cuentas existentes sólo cuando el gate sea resoluble.

Nunca se autoacepta a usuarios históricos ni se activa un `428` que bloquee los mismos endpoints
necesarios para resolverlo.

## Fuera de alcance

Esta fase no:

- crea esquema ni datos;
- modifica Spring Security, filtros, CORS o rate limits;
- implementa `428` en Java;
- publica los borradores actuales;
- habilita enforcement de registro o reaceptación;
- implementa atestaciones de fotos/credenciales, suscripción PRO, cierre o exportación;
- cambia frontend runtime.

## Verificación de la fase

- El contrato define request, response, errores, cache y replay sin depender de decisiones futuras.
- Los nombres wire coinciden con el manifiesto y el plan frontend existentes.
- `ADMIN` continúa siendo el único rol titular real del backend.
- El historial prometido tiene endpoint y aislamiento explícitos.
- La política de caché distingue representaciones mutables y terminales.
- La siguiente fase puede modelar tablas y migración sin inventar comportamiento HTTP.

## Siguiente fase

Diseñar e implementar el modelo append-only de documentos, requisitos, publicaciones, evidencias e
idempotencia, con migración fail-closed y tests PostgreSQL. Todavía sin activar el enforcement sobre
clientes existentes.
