# OrdenFix — Guía de integración Frontend

Fuente de verdad del contrato entre el frontend y el backend de **OrdenFix**.
Copiá este archivo al repo del frontend (o usalo como referencia para Claude Code).

---

## ⚡ Novedades de esta versión (para actualizar un front existente)

Si venís de una versión anterior del contrato, esto es lo que cambió / se agregó:

1. **⚠️ BREAKING — Fotos**: el campo `fotos` de reparación pasó de `string[]` a
   `{ url, momento }[]` con `momento: "INGRESO" | "POST_REPARACION"` (§4.5).
2. **Máquina de estados**: `EstadoReparacion` ahora tiene **12 estados** y el backend valida las
   transiciones → un salto ilegal en `PATCH /estado` devuelve **`409`**. El front debería ofrecer
   solo los estados legales según la tabla de transiciones (§4.5).
3. **Estado de pago** en cada reparación: `cobrado`, `saldo` y `estadoPago`
   (`SIN_COBRAR | PARCIAL | PAGADO`) — mostrar como chip aparte del estado (§4.5).
4. **Ingreso enriquecido**: flags de riesgo (`mojado`, `trabajoEnPlaca`, `noTesteableAlIngreso`),
   bloqueo de cuenta (`tieneCuentaVinculada`, `clienteConoceCredenciales`) y la bandera roja
   derivada **`riesgoCuentaSinCredenciales`** (§4.5). Nuevo **`numeroOrden`** (`ORD-2026-0042`,
   lo genera el backend, solo lectura).
5. **Presupuestos pro** (§4.11): ítems con `tipoItem` (`MANO_DE_OBRA|REPUESTO`) y `calidad`,
   `tipo` (`ORIGINAL|ADICIONAL`), validez con estado **`VENCIDO`** derivado, re-presupuestar,
   aprobar/rechazar desde el taller, y auto-estado de la reparación.
6. **Conformidad de entrega**: `fechaConformidadEntrega` se sella sola al pasar a `ENTREGADO` (§4.5).
7. **Garantía** (§4.5): `garantiaDias/Inicio/Fin/Condiciones`, `garantiaVigente` derivado, y
   **reclamo en garantía** `POST /api/reparaciones/{id}/garantia` (crea reparación vinculada,
   no consume cupo FREE). En la ficha de una reparación entregada, si `garantiaVigente`,
   ofrecé el botón "Reclamo en garantía".
8. **Seguridad**: un usuario desactivado pierde acceso **al instante** (su token viejo deja de
   servir → `403`). En una ruta base autenticada ese `403` invalida la sesión; en una acción
   solo-ADMIN puede significar únicamente falta de rol.
9. **Exportar datos**: `GET /api/export/excel` (solo ADMIN, todos los planes) descarga un `.xlsx`
   con clientes, órdenes, cobros y presupuestos del taller (§4.14).
10. **Cuentas por email** (§4.1): login/register ahora devuelven **`emailVerificado`** (mostrar
    banner "Confirmá tu email" si es `false` — puede operar igual). Nuevas pantallas:
    **olvidé mi contraseña** (`/password/olvide` + `/password/reset`) y **verificación de email**
    (`/verificar-email` + `/verificar-email/reenviar`). El front necesita las rutas
    `/reset-password?token=...` y `/verificar-email?token=...` (ahí llegan los links del email).
11. **Credenciales del dispositivo protegidas** (§4.5): PIN/patrón se aceptan en altas y ediciones,
    pero el backend solo los devuelve en el **detalle autenticado**. Nunca aparecen en altas, PUT,
    PATCH, listados, dashboard, ingreso rápido ni seguimiento público. Se borran al entregar.
12. **TRIAL con experiencia PRO** (§4.2 y §6): mientras `estado: "TRIAL"`, el taller tiene
    reparaciones ilimitadas y todas las capacidades habilitadas aunque `plan` todavía sea `FREE`.
13. **Checkout MP endurecido** (§4.7): abono mensual de **ARS 24.900**, respuesta validada contra
    `collector_id`/`application_id`, checkout apagado por defecto, kill switch y reintentos
    idempotentes.
14. **Perfil autenticado** (§4.2.a): `GET /api/perfil` devuelve por separado la identidad del usuario
    y la del taller; usá el nombre del taller como marca principal dentro de la aplicación.
15. **Cobros manuales consistentes** (§4.13): admiten `referencia`, nunca permiten superar el saldo
    pendiente y exponen `excedente/requiereRevision` para datos históricos inconsistentes.
16. **Anulación auditable** (§4.13): el endpoint canónico conserva el movimiento, requiere motivo y
    devuelve quién/cuándo lo anuló. El `DELETE` anterior queda como alias deprecado.
17. **Datos para recibir pagos** (§4.13): el ADMIN configura alias, titular, entidad y un QR raster
    opcional por taller; USER puede consultarlos, pero no modificarlos.
18. **Resumen digital no fiscal** (§4.13): `GET /resumen-digital` reemplaza al recibo imprimible. El
    alias `/recibo` sigue temporalmente disponible con el mismo JSON, pero está deprecado.
19. **Contrato legal v1 y operación interna segura** (§4.1.a): schema/persistencia V27 y las CLI
    internas de validación, simulación, importación, promoción, reemplazo, retiro y readiness
    editorial están implementadas, junto con el núcleo y servicio interno de agregados V28.
    `requiredSetRevision` representa los conjuntos completos aplicables y permanece estable al
    filtrar pendientes, incluso con `requisitos: []`. El bloque 13 implementa catálogo documental,
    `documentSetRevision` y documento exacto con rol restringido, ETag, errores, seguridad y rate limit,
    detrás de un flag apagado y con gate integral acreditado en 13D. El bloque 14 agrega requisitos
    públicos `REGISTRO/es-AR` con agregado V28, su propio flag apagado y gate de cierre en 14E.
    El corte 15D agrega el GET autenticado de pendientes con lector privado V29 y flag apagado;
    15E agrega historial propio paginado sobre la misma frontera. Quedan aceptación de aplicación,
    idempotencia HTTP, respuestas de escritura `409/428/503`, enforcement, contenido definitivo,
    staging y deploy.
    `BACKEND-HANDOFF 1` continúa cerrado.

Los tipos operativos de §7 y los tipos legales de §4.1.a reflejan estos contratos.

---

## 1. Qué es OrdenFix

SaaS para **talleres de reparación de tecnología** (celulares y dispositivos). Multi-empresa
(cada taller ve solo sus datos) y por **suscripción freemium** (plan FREE con límites + plan PRO).

Flujo del dominio:

```
Taller (cuenta/tenant)
  └── Usuarios (login por email)
  └── Clientes
        └── Equipos (celular/dispositivo del cliente)
              └── Reparaciones (orden de trabajo, con estado)
                    └── Repuestos (partes usadas)
```

El **multi-tenant es transparente para el frontend**: nunca se envía un `tallerId`.
El backend deduce el taller desde el token y filtra todo automáticamente.

---

## 2. Entorno / Base URL

- Local: `http://localhost:8080`
- Configurable por variable de entorno: `VITE_API_URL` (o equivalente).
- Todas las rutas cuelgan de `/api`.

`http://localhost` es correcto para desarrollar el API y el frontend. Si `MP_ENABLED=true`, el
`MP_BACK_URL` del frontend y el webhook del backend deben exponerse como URLs HTTPS públicas mediante
un túnel o staging; Mercado Pago no puede acceder a `localhost`.

CORS habilitado para:
- `http://localhost:5173` (Vite dev)
- `https://mvgr-reparaciones-frontend.vercel.app`

> Si el frontend se despliega en otro dominio, hay que agregarlo en el backend (`CorsConfig`).

---

## 3. Autenticación (JWT)

- Endpoints públicos: `/api/auth/**`, `/api/seguimiento/**`, `/api/pagos/webhook` y
  `/actuator/health`. Los GET legales de §4.1.a también son públicos cuando su flag correspondiente
  está activo; los métodos y rutas vecinas conservan autenticación. **El resto requiere token.**
  El webhook es exclusivo de Mercado Pago; el frontend nunca debe invocarlo.
- Header en cada request autenticada:
  `Authorization: Bearer <token>`
- El token es un JWT que contiene `iss`, `aud`, `sub` (email), `exp`, `iat`, `nbf`, `jti`, `role`,
  `tallerId` y `tokenVersion`. El claim `role` llega exactamente como `ROLE_ADMIN` o `ROLE_USER`.
  La respuesta de login no trae un campo `role` separado: si el front usa `ADMIN`/`USER` en sus
  guards, debe decodificar el claim y normalizar el prefijo de forma explícita.
  Se puede decodificar en el front para mostrar email/rol, **pero la seguridad la
  valida siempre el backend** (no confíes en el token para habilitar acciones críticas).
- Duración del token: la define cada entorno mediante `JWT_EXPIRATION`. El front debe guiarse por
  el claim `exp`, no asumir una duración fija. En la configuración actual, una ruta protegida con
  token ausente, inválido, revocado o de un usuario desactivado responde `403`; ver el manejo de
  sesión debajo.
- **Roles**: `ADMIN` (dueño) y `USER` (empleado). Hoy el registro crea siempre ADMIN.
  Operaciones **solo ADMIN** (un USER recibe `403`): iniciar/cancelar la suscripción PRO, gestionar
  empleados, anular cobros, modificar los datos de cobro/QR y los **borrados** de recursos operativos.
  USER conserva acceso a la operatoria cotidiana autenticada que no tenga un guard ADMIN explícito.

### Flujo
1. **Registro** (`POST /api/auth/register`) o **Login** (`POST /api/auth/login`) → devuelven
   `{ token, type, email, emailVerificado }`.
2. Guardar el token (localStorage) y mandarlo en el header en todas las llamadas.
3. Un **401** en login significa credenciales rechazadas; no hay sesión válida que conservar.
4. En rutas protegidas, un **403** puede significar tanto sesión ausente/inválida/revocada como falta
   de rol. No lo conviertas
   ciegamente en logout ni en "sin permisos": al iniciar la app validá la sesión con
   `GET /api/suscripcion`; un `403` en esa ruta base implica logout. En una acción conocida como
   solo-ADMIN, mostrale "sin permisos" al USER. Si aparece un `403` inesperado durante la sesión,
   repetí esa validación base antes de decidir.

---

## 4. Endpoints

### 4.1 Auth — PÚBLICO

#### `POST /api/auth/register` — alta de taller (onboarding)
Crea el taller + usuario admin + suscripción FREE en TRIAL (14 días) y deja al usuario logueado.

Request:
```json
{
  "nombreTaller": "CelExpress",        // obligatorio, máx 120
  "telefonoTaller": "1133334444",      // opcional, máx 20
  "nombreAdmin": "Juan",               // obligatorio, máx 50 (nombre visible)
  "email": "juan@celexpress.com",      // obligatorio, formato email, único global
  "password": "juan123"                // obligatorio, 6 a 100 chars
}
```
Respuesta `201`:
```json
{ "token": "eyJ...", "type": "Bearer", "email": "juan@celexpress.com", "emailVerificado": false }
```
Errores: `400` (email ya registrado o validación).
> Además manda el email de bienvenida con el link de verificación (ver más abajo).

#### `POST /api/auth/login`
Request:
```json
{ "email": "juan@celexpress.com", "password": "juan123" }
```
Respuesta `200`:
```json
{ "token": "eyJ...", "type": "Bearer", "email": "juan@celexpress.com", "emailVerificado": true }
```
Errores: `401` (email o contraseña incorrectos).

> **`emailVerificado`** también viene en el register (siempre `false` ahí). Si es `false`,
> mostrá un banner "Confirmá tu email (revisá tu casilla)" con botón de reenviar — el usuario
> **puede operar igual** (verificación suave).

#### Olvidé mi contraseña — `POST /api/auth/password/olvide` / `POST /api/auth/password/reset`

1. `POST /api/auth/password/olvide` con `{ "email": "..." }` → **siempre `200`** (no revela si
   el email existe). Si existe, le llega un email con un link a `{front}/reset-password?token=...`
   (el token vence en **1 hora**).
2. En esa pantalla del front, pedís la contraseña nueva y mandás
   `POST /api/auth/password/reset` con `{ "token": "...", "nuevaPassword": "..." }` → `200`.
   El token es de **un solo uso**; inválido/vencido/usado → `400` ("El link no es válido o ya venció").
3. Pedir un link nuevo invalida el anterior.

#### Verificación de email — `POST /api/auth/verificar-email` (+ `/reenviar`)

- Al registrarse, llega un email de bienvenida con link a `{front}/verificar-email?token=...` (vence en 48 h).
- En esa pantalla el front manda `POST /api/auth/verificar-email` con `{ "token": "..." }` → `200`
  (inválido/vencido → `400`).
- `POST /api/auth/verificar-email/reenviar` con `{ "email": "..." }` → siempre `200` (reenvía solo
  si la cuenta existe y no está verificada).

> **Rutas nuevas que el front debe tener**: `/reset-password` y `/verificar-email` (leen `?token=`
> de la URL). El link "¿Olvidaste tu contraseña?" va en la pantalla de login.

### 4.1.a Legal versionado — contrato v1 y persistencia interna V27/V28

> **Estado al 2026-09-05:** 2.3A–2.3C conservan la operación editorial interna V27. V28 implementa
> agregados multicontexto, preservación de historia y un servicio interno que materializa/reutiliza
> la composición bajo gate compartido y rol restringido. El contrato de un único token opaco se
> mantiene; representa conjuntos completos y no depende de la evidencia ni de la lista pendiente.
> El bloque 13 implementa los dos GET documentales, revisión, ETag, errores y políticas HTTP con lector
> restringido. Están apagados por defecto; encenderlos exige configuración documental explícita.
> El gate integral 13D está acreditado. El corte 14D implementa el GET de requisitos públicos
> `REGISTRO/es-AR`, detrás de su propio flag apagado. El corte 14E acredita
> concurrencia, capacidad, tiempos límite y un gate integral fresco sobre esa superficie.
> El corte 15D conecta el lector privado de 15C al GET autenticado de pendientes, bajo su propio
> flag apagado. 15E agrega el historial propio paginado, sin metadata técnica. Quedan aceptación
> de aplicación, idempotencia HTTP, respuestas
> de escritura `409/428/503`, enforcement, contenido real,
> staging y deploy. Las demás rutas legales de esta sección aún no existen en runtime.
> `BACKEND-HANDOFF 1` y la Tarea 3 permanecen cerrados, y el registro histórico de §4.1 continúa
> activo. No actives la UI basándote solamente en esta documentación.

#### Endpoints y autorización

| Método | Ruta | Sesión | Resultado |
|---|---|---|---|
| GET | `/api/public/documentos-legales?locale=es-AR&page=0&size=20` | Pública | Catálogo paginado; `contexto` es opcional |
| GET | `/api/public/documentos-legales/{versionId}` | Pública | Una versión publicada exacta |
| GET | `/api/public/requisitos-legales?contexto=REGISTRO&locale=es-AR` | Pública | Conjunto vigente para registro |
| GET | `/api/requisitos-legales` | ADMIN/USER | Requisitos pendientes del actor actual |
| GET | `/api/aceptaciones-legales?page=0&size=20` | ADMIN/USER | Evidencia propia; `contexto` es opcional |
| POST | `/api/aceptaciones-legales` | ADMIN/USER | Registra evidencia propia; `204` |

Las dos primeras filas están implementadas en 13C, la tercera en 14D, la cuarta en 15D y la quinta
en 15E. El POST de aceptaciones está implementado en 15J3 bajo su flag propio, apagado por defecto. El flag
`ordenfix.legal.public-documents.enabled` vale `false` por defecto: apagado no registra mappings
documentales ni excepciones de autenticación. Sólo `true` (sin distinguir mayúsculas) activa las
tres políticas y los mappings; aliases como `yes`, `on` o `1` no habilitan lectura pública.
Al encenderlo se requieren
`ordenfix.legal.public-document-read.jdbc-url`, `.username` y `.password` en configuración del backend;
no se heredan `spring.datasource.*`. Estas credenciales pertenecen a la frontera lectora acreditada
en 13B, con preflight V27/V28 y permisos efectivos restringidos en cada operación. El contexto
separado no ejecuta Flyway y se cierra con la aplicación. No se provisionaron roles en una base
compartida ni se habilitó el flag en este corte.

La policy `security.rate-limit.public-legal-documents` comparte una ventana por IP para ambos GET:
`requests=60`, `window=1m` inicialmente, bajo el switch general de rate limit existente. Incluye
UUID inválidos y peticiones condicionales. Se mantiene la política de confianza de forwarded headers
existente. HEAD, otros métodos, rutas vecinas y subrutas no heredan autorización pública documental;
OPTIONS preflight continúa bajo CORS. Un Authorization inválido no altera estos GET públicos.

El [cierre documental 13D](docs/plans/2026-09-05-legal-public-document-read-closure.md) registra
capacidad, concurrencia HTTP y el gate integral fresco. Esa evidencia no certifica los grants de
un entorno compartido ni acredita los requisitos públicos de 14D, las aceptaciones o el handoff.

El GET de requisitos públicos usa el flag independiente `ordenfix.legal.public-requirements.enabled`,
`false` por defecto. Sólo `true` sin distinguir mayúsculas y sin espacios registra ruta, advice,
puente y excepción pública. Requiere las tres credenciales dedicadas
`ordenfix.legal.public-requirements.jdbc-url`, `.username` y `.password`; no usa las credenciales
web ni documentales. El puente mantiene un contexto sin padre, con sólo estas tres propiedades y
su flag interno, sin JPA ni Flyway, y cierra su pool con la aplicación. El rol restringido de 14B
realiza preflight V28, gate compartido y una transacción propia `REQUIRES_NEW/READ_COMMITTED`.
La consulta materializa/reutiliza el agregado y valida textos, digests y pertenencia dentro de la misma
transacción, antes de confirmar y devolver el resultado. Un fallo anterior al commit revierte las
inserciones nuevas. Si el commit ya fue confirmado,
un fallo posterior no se presenta como rollback ni entrega un éxito HTTP; una repetición estable
es `REUSED` sin DML. El wire expone únicamente `requiredSetRevision`.

El [cierre de requisitos 14E](docs/plans/2026-09-05-legal-public-requirements-read-closure.md)
registra dos lectores compartidos frente a escritores editoriales, convergencia de creadores,
procedencia física aunque el token semántico no cambie, capacidad editorial válida, recursos y
fallos de deadline con recuperación. Un RETIRE que deja el registro incompleto produce 503,
aunque la versión documental histórica siga disponible. La evidencia incluye el gate integral
fresco; no habilita los flags, los grants compartidos, las aceptaciones ni el handoff global.

`locale=es-AR` y `contexto=REGISTRO` son obligatorios, sensibles a mayúsculas y sin trim/default.
Se valida primero `locale`. Un parámetro repetido, incluso con valores iguales, devuelve 400;
el detalle conserva los valores originales unidos por coma, en orden, o `null` si falta.
La respuesta incluye obligatorios y opcionales, en el orden original, con sus documentos completos.
El éxito devuelve `Cache-Control: public, max-age=0, must-revalidate` y
`ETag: W/"<requiredSetRevision>"`. Antes de evaluar cualquier `If-None-Match`, incluso `*`, se lee
y acredita el conjunto completo: 304 no omite la validación ni incluye cuerpo. Un fallo interno
con un ETag previo válido devuelve 503 con `CONTRATO_LEGAL_NO_DISPONIBLE`, `no-store` y sin ETag.

La cuota independiente `security.rate-limit.public-legal-requirements` empieza en `requests=60`,
`window=1m` por IP y cuenta 200, 304, 400 y 503. El 429 incluye `Retry-After`, `X-RateLimit-Limit`,
`X-RateLimit-Remaining`, `no-store` y ningún ETag. Comparte el switch global y la política de IP/proxy
existente, pero no el consumo de documentos ni autenticación. Sólo el GET exacto es público:
HEAD, métodos distintos, slash final y subrutas conservan autenticación. Los cuatro estados de ambos
flags mantienen sus permisos y cuotas independientes. CORS conserva orígenes y headers existentes;
ETag no se agrega a los headers expuestos. No se activaron flags ni se provisionaron roles compartidos.

`locale` es obligatorio en los dos GET públicos de colección y v1 sólo admite `es-AR`; el GET por
UUID no recibe locale. `contexto` es obligatorio en requisitos públicos; inicialmente sólo se
habilita `REGISTRO`. El catálogo admite omitir `contexto` para listar todas las versiones publicadas
del locale. No existe fallback silencioso por `Accept-Language`.

Los endpoints autenticados obtienen usuario, tenant y rol de la sesión validada. El navegador nunca
envía `userId`, `tallerId`, rol ni audiencia. El valor de dominio/JWT continúa siendo `ADMIN`; como
existe un único `ADMIN` por taller, el backend lo traduce a la audiencia legal `ADMIN_TITULAR` sin
agregar un claim ni un rol wire nuevo. `USER` no puede aceptar en representación del taller.

#### Enums y tipos TypeScript

```ts
export type LocaleLegal = 'es-AR';

export type TipoActoLegal =
  | 'ACEPTACION'
  | 'LECTURA'
  | 'DECLARACION';

export type EstadoDocumentoLegal =
  | 'VIGENTE'
  | 'REEMPLAZADA'
  | 'RETIRADA';

export type ContextoLegal =
  | 'REGISTRO'
  | 'PRIMER_INGRESO_EMPLEADO'
  | 'USO_CONTINUADO'
  | 'CONTRATACION_PRO'
  | 'ATESTACION_FOTOS'
  | 'ATESTACION_CREDENCIALES'
  | 'CIERRE_CUENTA'
  | 'ARREPENTIMIENTO';

export type TipoDocumentoLegal =
  | 'TERMINOS_SERVICIO'
  | 'POLITICA_PRIVACIDAD'
  | 'ACUERDO_TRATAMIENTO_DATOS'
  | 'CONDICIONES_PRO'
  | 'POLITICA_CANCELACIONES_REEMBOLSOS'
  | 'POLITICA_CIERRE_CUENTA'
  | 'AVISO_CLIENTES_TALLER'
  | 'TERMINOS_USUARIO'
  | 'AVISO_PRIVACIDAD_USUARIO'
  | 'COMPROMISO_CONFIDENCIALIDAD'
  | 'ATESTACION_DATOS_CLIENTE';

export interface DocumentoLegalVersion {
  id: string; // UUID del contenido/versionado inmutable; estado aún puede transicionar
  tipo: TipoDocumentoLegal;
  version: string;
  titulo: string;
  contenidoMarkdown: string;
  sha256: string; // 64 hex minúsculas, sin prefijo
  vigenteDesde: string; // RFC 3339 UTC
  estado: EstadoDocumentoLegal;
  locale: LocaleLegal;
}

export interface DocumentoLegalResumen {
  id: string;
  tipo: TipoDocumentoLegal;
  version: string;
  titulo: string;
  sha256: string;
  vigenteDesde: string;
  estado: EstadoDocumentoLegal;
  locale: LocaleLegal;
}

export interface PageMeta {
  size: number;
  number: number;
  totalElements: number;
  totalPages: number;
}

export interface DocumentosLegalesResponse {
  contexto: ContextoLegal | null;
  locale: LocaleLegal;
  documentSetRevision: string; // opaco; formato sha256:<64-hex>; catálogo completo del filtro
  documentos: DocumentoLegalResumen[];
  page: PageMeta;
}

export interface RequisitoLegalVersion {
  id: string; // UUID de la versión exacta del requisito
  contexto: ContextoLegal;
  tipoActo: TipoActoLegal;
  afirmacion: string;
  afirmacionSha256: string;
  documentos: DocumentoLegalVersion[];
  requerido: boolean;
}

export interface RequisitosLegalesPublicosResponse {
  contexto: ContextoLegal;
  locale: LocaleLegal;
  requiredSetRevision: string;
  requisitos: RequisitoLegalVersion[];
}

export interface RequisitosLegalesPendientesResponse {
  locale: LocaleLegal;
  requiredSetRevision: string;
  requisitos: RequisitoLegalVersion[];
}

export interface AceptacionLegalInput {
  requisitoVersionId: string;
  tipoActo: TipoActoLegal;
  afirmacionSha256: string;
  documentos: Array<{ documentoVersionId: string; sha256: string }>;
  confirmado: true;
}

export interface AceptacionesLegalesRequest {
  requiredSetRevision: string;
  aceptacionesLegales: AceptacionLegalInput[];
}

export interface DocumentoAceptacionLegal {
  documentoVersionId: string;
  tipo: TipoDocumentoLegal;
  version: string;
  titulo: string;
  sha256: string;
}

export interface AceptacionLegalResponse {
  id: string;
  requisitoVersionId: string;
  contexto: ContextoLegal;
  tipoActo: TipoActoLegal;
  afirmacion: string;
  afirmacionSha256: string;
  documentos: DocumentoAceptacionLegal[];
  aceptadoEn: string;
}

export interface AceptacionesLegalesPage {
  content: AceptacionLegalResponse[];
  page: PageMeta;
}
```

Los IDs son UUID. Los arrays tienen orden contractual: requisitos conservan el ordinal del
manifiesto; documentos del catálogo usan el mismo orden de `TipoDocumentoLegal` listado arriba y,
dentro de cada tipo, `vigenteDesde` descendente y UUID ascendente. El request de aceptación se
compara como conjunto, no por su orden accidental.

El importador primero exige bytes UTF-8 válidos, Unicode NFC, saltos LF y ausencia de BOM; luego
calcula el digest sobre esos bytes originales, sin normalizarlos ni reescribirlos. Para una
afirmación, hashea los bytes UTF-8 de su string exacto, sujeto a las mismas invariantes.
`documentSetRevision` y `requiredSetRevision` son opacos para el front: nunca los interpretes como
fecha, contador ni partes separables. El wire conserva un único `requiredSetRevision`; no incorpora
un mapa por contexto, revisiones componentes ni el fingerprint de procedencia interno.

`requiredSetRevision` usa la semántica agregada `AGGREGATE_V1` de V28: representa los conjuntos
completos de los contextos aplicables, resueltos por el servidor. Cambiar una revisión incluida o la
composición aplicable cambia el token; una edición en un contexto excluido no lo cambia. Registrar,
modificar o retirar evidencia y filtrar algunos o todos los pendientes tampoco lo cambia. Esta
semántica reemplaza la interpretación anterior de un hash de la respuesta filtrada, tanto para el
registro como para las respuestas autenticadas futuras. `documentSetRevision` está implementado
como hash canónico del catálogo completo del filtro, independiente de page/size, con el mismo
render UTC y UUID que los resúmenes enviados por HTTP.

#### Catálogo público

```http
GET /api/public/documentos-legales?locale=es-AR&contexto=REGISTRO&page=0&size=20
```

Respuesta `200`:

```json
{
  "contexto": "REGISTRO",
  "locale": "es-AR",
  "documentSetRevision": "sha256:4a8a8f09d37b73795649038408b5f33a7f8d5f0d5f6d39a83b2a8af2f104fc19",
  "documentos": [
    {
      "id": "77aa2a48-af19-4f80-92bb-d9f220c166d1",
      "tipo": "TERMINOS_SERVICIO",
      "version": "2026.08.1",
      "titulo": "Términos y Condiciones de OrdenFix",
      "sha256": "7a3fcccbd0e349cfb44129658cf7dd2d7c230eea560f8367cb6c25a7457b5298",
      "vigenteDesde": "2026-09-01T03:00:00Z",
      "estado": "VIGENTE",
      "locale": "es-AR"
    }
  ],
  "page": {
    "size": 20,
    "number": 0,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

El catálogo devuelve versiones que alguna vez fueron públicas: `VIGENTE`, `REEMPLAZADA` y
`RETIRADA`. Nunca expone `BORRADOR`, una `PUBLICADA` aún no vigente, referencias de revisión ni rutas
internas del manifiesto. Devuelve resúmenes sin Markdown; el contenido se obtiene construyendo el GET
exacto con `id` sobre el mismo cliente cuya base termina en `/api`. No devuelve `href`, para evitar
ambigüedad entre URLs relativas al origin y relativas a la API. `page` empieza en cero; `size` por
defecto es 20 y admite 1 a 100. Sin `contexto`, el response usa `"contexto": null`.

El filtro de contexto usa la lista de contextos congelada en cada versión documental publicada, no
los requisitos que hoy la referencian. Por eso una versión histórica continúa apareciendo bajo el
contexto que tuvo al publicarse. El `documentSetRevision` identifica el catálogo filtrado completo,
no sólo la página solicitada.

`BORRADOR -> PUBLICADA -> VIGENTE -> REEMPLAZADA | RETIRADA`; los dos estados finales son
terminales. Contenido, título, tipo, versión, locale, fecha y digest no cambian después de publicar.
El título se congela desde el primer H1 del Markdown; una publicación sin H1 válido se rechaza.

El estado es global a la versión, no por contexto. Para un tipo+locale, dos versiones `VIGENTE` no
pueden solapar contextos. Reemplazar una versión multicontexto debe cubrir todos sus contextos en la
misma promoción —con una o varias sucesoras disjuntas—; el dry-run de `REEMPLAZADA` rechaza huecos y
solapamientos. `RETIRADA` puede dejar un hueco deliberado en todos sus contextos mediante una
operación explícita y auditada. Todo requisito afectado falla cerrado con `503` hasta contar con una
sucesora; nunca sigue sirviendo el documento retirado como vigente.

Un contexto/locale fuera del contrato responde `400`. Si son soportados pero nunca existió una
publicación válida para ese filtro, se responde `503 CONTRATO_LEGAL_NO_DISPONIBLE`; no se devuelve un
catálogo parcial como contrato utilizable. Una página posterior a la última dentro de un catálogo
válido sí responde `200` con `documentos: []` y metadata de página.

Los UUID son ilustrativos. Los digests de documento y afirmación de los ejemplos corresponden a los
strings mostrados; las revisiones de conjunto sólo ilustran el formato opaco y no deben copiarse.

#### Documento exacto

```http
GET /api/public/documentos-legales/77aa2a48-af19-4f80-92bb-d9f220c166d1
```

Devuelve `200 DocumentoLegalVersion` para una versión `VIGENTE`, `REEMPLAZADA` o `RETIRADA`. Un UUID
malformado o desconocido, un borrador o una publicación futura responden el mismo `404
DOCUMENTO_LEGAL_NO_ENCONTRADO`, sin revelar su existencia editorial.

La URL es estable y recargable. Una aceptación histórica enlaza a este recurso por `id`.

#### Requisitos públicos de registro

```http
GET /api/public/requisitos-legales?contexto=REGISTRO&locale=es-AR
```

Respuesta `200`:

```json
{
  "contexto": "REGISTRO",
  "locale": "es-AR",
  "requiredSetRevision": "sha256:db8e1d073852d51b643bba92fd99590bb410578dd09ab7f0b1a1cf25bcf9e15d",
  "requisitos": [
    {
      "id": "557a8ce8-c72e-4088-a6b5-e2b0f775eb98",
      "contexto": "REGISTRO",
      "tipoActo": "ACEPTACION",
      "afirmacion": "Acepto los Términos y Condiciones de OrdenFix.",
      "afirmacionSha256": "68b041f6843211cb6e8d9e4799b1bb158af90cbf60a2d42fc9d70047a3b8bc1c",
      "documentos": [
        {
          "id": "77aa2a48-af19-4f80-92bb-d9f220c166d1",
          "tipo": "TERMINOS_SERVICIO",
          "version": "2026.08.1",
          "titulo": "Términos y Condiciones de OrdenFix",
          "contenidoMarkdown": "# Términos y Condiciones de OrdenFix\n\n...",
          "sha256": "7a3fcccbd0e349cfb44129658cf7dd2d7c230eea560f8367cb6c25a7457b5298",
          "vigenteDesde": "2026-09-01T03:00:00Z",
          "estado": "VIGENTE",
          "locale": "es-AR"
        }
      ],
      "requerido": true
    }
  ]
}
```

Los requisitos aceptables sólo enlazan documentos `VIGENTE`. Debe existir al menos un requisito
obligatorio completo para `REGISTRO`; de lo contrario el backend falla cerrado con `503`.
El token previsto para registro es el agregado de su único contexto `REGISTRO`; el frontend lo
conserva y reenvía sin calcular revisiones por contexto.

#### Requisitos pendientes autenticados

Implementado en 15D con `ordenfix.legal.account-read.enabled=false` por defecto. A diferencia de
los flags públicos, sólo admite los literales exactos `true` y `false`; un valor inválido impide el
arranque. Encenderlo exige `ordenfix.legal.account-read.jdbc-url`, `.username` y `.password` propias,
sin fallback a credenciales web/públicas. Su contexto aislado sin padre sólo recibe esas propiedades
y la bandera, no ejecuta Flyway y cierra su pool con la aplicación. La credencial restringida acredita
V29 y los privilegios privados de 15C en cada operación. No se provisionaron grants compartidos ni
se activó esta bandera.

```http
GET /api/requisitos-legales
Authorization: Bearer <token>
```

Devuelve `200 RequisitosLegalesPendientesResponse`. El backend resuelve primero la composición
aplicable y su revisión agregada sobre conjuntos completos; después filtra la lista por el actor,
su rol actual y las evidencias ya registradas. Puede incluir más de un contexto. No envíes perfil,
audiencia, rol, tenant ni un vector de contextos para elegir o reducir el resultado.

Esta ruta no admite parámetros de consulta: incluso `locale` devuelve 400 genérico si se envía.
El locale sale de la política del servidor (`es-AR`). Sólo GET consulta el lector; HEAD autenticado
responde 405 con `Allow: GET`, y métodos o rutas vecinas no amplían el acceso. Una URI codificada
que Spring asimile a la ruta se rechaza con 404 antes del lector si no coincide literalmente. El JWT sigue pasando
por la autenticación habitual; sin sesión válida, el GET exacto habilitado responde 401. Un rol sin
permiso recibe 403 antes de consultar legal. El principal se contrasta además con usuario, taller,
rol, estado y versión de token persistidos; una discordancia produce 401.

El éxito devuelve `Cache-Control: private, no-store`, sin ETag. `If-None-Match`, incluso `*`, no
produce 304 ni evita la observación completa. Los fallos legales propios devuelven `no-store`;
la indisponibilidad del contrato produce 503 `CONTRATO_LEGAL_NO_DISPONIBLE` con
`details: {"contexto": null, "locale": "es-AR"}`, sin causas SQL, datos del actor ni resultados
parciales. El lector utiliza el gate compartido, una transacción propia `REQUIRES_NEW/READ_COMMITTED`
y el rol restringido de 15C: materializa un agregado faltante y reutiliza uno existente sin DML del
agregado. Un fallo previo al commit revierte las escrituras nuevas; un fallo posterior al commit
no se describe como rollback. Esta consulta no registra aceptaciones ni aplica el bloqueo 428.

Incluye requisitos aplicables todavía no evidenciados, tanto obligatorios como opcionales. Sólo la
ausencia de un requisito `requerido=true` puede disparar `428`; omitir uno opcional nunca bloquea la
cuenta.

El backend puede considerar satisfecho un cambio puramente editorial sin fabricar otra evidencia.
Los `requiresReacceptance` del manifiesto no forman parte del wire: para heredar, debe existir una
aceptación previa del mismo `requirement.key`, cada documento vigente debe conservar un
`document.key` ya evidenciado y **todas** las versiones intermedias del requisito y de esos documentos
deben tener `requiresReacceptance=false`. Los flags se combinan con OR a lo largo de la cadena: un
solo `true`, un key nuevo o un documento agregado vuelve el requisito pendiente. Sin evidencia
previa siempre queda pendiente. La herencia no inserta una aceptación ni cambia su fecha; el historial
muestra exclusivamente el acto real. El importador impide reutilizar un key para otro tipo/locale,
contexto, audiencia o tipo de acto.

Si el actor satisfizo todo, la respuesta válida es:

```json
{
  "locale": "es-AR",
  "requiredSetRevision": "sha256:bbd8d51c7eb805a5a2b44bbe0a5877018ac0666986c3aa45256e2a387ad71bf0",
  "requisitos": []
}
```

La revisión es autoritativa y conserva el mismo valor que antes de satisfacer esos requisitos,
mientras no cambie la composición aplicable ni sus revisiones. `requisitos: []` no representa un
agregado sin scopes ni provoca un hash nuevo del conjunto vacío. El valor de ejemplo no debe
recalcularse ni asumirse en el cliente. El núcleo de 15B y el lector PostgreSQL de 15C acreditan
estas reglas; 15D expone el resultado completo del lector en esta ruta.

#### Registrar aceptación autenticada

Implementado en 15J3 bajo `ordenfix.legal.account-acceptance.enabled=true` exacto; requiere también
`ordenfix.legal.account-read.enabled=true`. Ausente/false no registra el POST ni su advice. La
configuración inválida impide iniciar. No se habilita ningún entorno mediante este cambio.
ADMIN y USER sólo registran sus propios actos; el actor se contrasta en PostgreSQL antes de leer
headers/cuerpo y nuevamente bajo locks antes de escribir. El POST no requiere estar al día con
los consentimientos que permite satisfacer.

La escritura usa `ordenfix.legal.account-acceptance.jdbc-url`, `.username` y `.password`, con contexto,
pool y rol restringidos propios, separados del lector privado y de `spring.datasource.*`. Requiere
keyrings completos y versiones activas de `ordenfix.legal.idempotency.*` y
`ordenfix.legal.account-metadata.*`, retención explícita de metadata y claves independientes de JWT y
del cifrado de equipos. No ejecuta Flyway ni provisiona roles durante el arranque web. SQL y espera idempotente tienen un
presupuesto de 5 s; el transporte de aceptación permite hasta 6 s para recibir la respuesta de
PostgreSQL, siempre acotado al remanente de la operación de 15 s. Los lectores conservan sus límites.

La captura exige `server.forward-headers-strategy=none` explícito; rechaza propiedades remoteip de
Tomcat que activen reescritura y registros conocidos de ForwardedHeaderFilter, RemoteIpFilter o
RemoteIpValve, incluidas subclases, antes de servir. Los proxies confiables se configuran sólo en
`ordenfix.legal.account-metadata.trusted-proxy-cidrs`, CSV de CIDR canónicos (máximo 64 y 4096
caracteres ASCII). Ausente/vacío no confía en headers de proxy. El flag de confianza del rate limit
no se reutiliza. Un fallo de captura responde 503 y revierte la operación; no se inventa metadata.

```http
POST /api/aceptaciones-legales
Authorization: Bearer <token>
Idempotency-Key: 64f89458-294c-4df5-a88d-833a1f1abcde
Content-Type: application/json
```

Request:

```json
{
  "requiredSetRevision": "sha256:db8e1d073852d51b643bba92fd99590bb410578dd09ab7f0b1a1cf25bcf9e15d",
  "aceptacionesLegales": [
    {
      "requisitoVersionId": "557a8ce8-c72e-4088-a6b5-e2b0f775eb98",
      "tipoActo": "ACEPTACION",
      "afirmacionSha256": "68b041f6843211cb6e8d9e4799b1bb158af90cbf60a2d42fc9d70047a3b8bc1c",
      "documentos": [
        {
          "documentoVersionId": "77aa2a48-af19-4f80-92bb-d9f220c166d1",
          "sha256": "7a3fcccbd0e349cfb44129658cf7dd2d7c230eea560f8367cb6c25a7457b5298"
        }
      ],
      "confirmado": true
    }
  ]
}
```

Éxito: `204 No Content`, sin body. Un replay con la misma clave y el mismo fingerprint también
devuelve `204` y no crea otra evidencia.

El POST admite una única cabecera Content-Type de hasta 256 caracteres, `application/json`, con
charset ausente o que represente `UTF-8` (incluido el alias `UTF8` y el valor entre comillas);
no admite otros parámetros, MIME ni charset.
Tipo/subtipo no distinguen mayúsculas. La query debe estar ausente o vacía. El cuerpo debe ser UTF-8
estricto y cumplir el DTO exacto, sin propiedades extra ni selectores de usuario, taller o rol.
Estos rechazos usan 400 `ACEPTACION_LEGAL_INVALIDA` / `PAYLOAD_LEGAL_INCOMPLETO`.
La prioridad es actor válido → header Idempotency-Key presente con formato válido → transporte y
JSON/DTO → header requerido. Por eso una clave presente inválida vence un cuerpo inválido, mientras
que la ausencia de clave se reclama sólo después de validar el cuerpo. El parseo preserva arrays
vacíos, duplicados y `confirmado:false` para las decisiones semánticas posteriores.

Toda respuesta del POST usa `Cache-Control: no-store`, sin ETag ni 304. `Retry-After: 1` aparece sólo
en 409 `IDEMPOTENCY_EN_PROGRESO`; no indica que un error 503 sea seguro para reintentar con otra clave.
Un resultado de commit incierto responde 503 sin afirmar rollback. El reintento explícito conserva
la misma clave y cuerpo para consultar el resultado durable. El endpoint no devuelve IDs internos,
metadata ni el receipt de persistencia.

La lista debe incluir todos los requisitos `requerido=true` todavía pendientes del actor, evaluados
en la misma transacción, y puede incluir cualquier subconjunto de opcionales conocidos y confirmados.
En registro, sin evidencia previa, se exigen todos los obligatorios vigentes de `REGISTRO`.
`REQUISITO_FALTANTE` sólo aplica a obligatorios. Filtrar evidencia no cambia la revisión del agregado.
Para cada requisito enviado, sus documentos deben coincidir exactamente: no admite documentos
omitidos, extra o duplicados. Tampoco admite requisitos desconocidos o duplicados. `tipoActo`,
digests y `confirmado` son pruebas de qué presentó el front, no una fuente confiable: el backend
persiste sus propios textos, tipos y digests canónicos con hora del servidor.

Precisiones ratificadas en 15A e implementadas en el POST de 15J3:

| Caso con clave nueva | Resultado después de acreditar disponibilidad |
| --- | --- |
| Lista no vacía, sin duplicados y totalmente confirmada con evidencia canónica exacta | `204` antes de comparar revisión; el refetch puede mostrar nuevos pendientes |
| Lista vacía | Comparar revisión: vieja → `409`; actual con obligatorios pendientes → `400` / `REQUISITO_FALTANTE`; actual sin obligatorios pendientes → `204` |
| Mezcla de actos confirmados y nuevos | Exigir revisión actual, pertenencia actual de todo lo enviado y cobertura de obligatorios pendientes; insertar sólo actos nuevos |
| Requisitos o documentos duplicados, aunque exista evidencia | No aplicar el atajo de deduplicación: revisión primero; si coincide, error de duplicado correspondiente |

Un éxito sin actos nuevos conserva resultado idempotente durable; no fabrica lotes, metadata ni
aceptaciones, ni modifica fechas históricas. La herencia sólo satisface lecturas y no crea evidencia.
El diseño SQL y las fronteras de implementación están en la
[decisión 15A](docs/plans/2026-09-06-legal-account-consent-v29-decision.md).

Después del `204`, invalidá requisitos y aceptaciones y volvé a consultar. No mantengas una bandera
local como autorización.

#### Consultar aceptaciones propias

Implementado en 15E bajo `ordenfix.legal.account-read.enabled`, apagado por defecto. Comparte
credencial restringida, preflight V29, contexto y pool con el GET privado de requisitos; no agrega
configuración ni activa el flag. ADMIN sólo consulta sus propios actos: no ve evidencia de sus
empleados. USER tampoco ve al titular, otros usuarios ni otros talleres. El actor se obtiene de la
sesión validada y se contrasta nuevamente con el estado persistido.

```http
GET /api/aceptaciones-legales?contexto=REGISTRO&page=0&size=20
Authorization: Bearer <token>
```

`contexto` es opcional. `page` empieza en cero; `size` por defecto es 20 y admite 1 a 100. El orden
es `aceptadoEn` descendente y luego UUID descendente.

`page` por defecto es 0. Sólo se admiten `contexto`, `page` y `size`, una vez cada uno, sin signos
ni espacios para los números. Parámetros desconocidos, repetidos o paginación inválida devuelven
400 genérico; un contexto no reconocido usa `CONTEXTO_LEGAL_NO_SOPORTADO`. No hay selectores de
usuario, taller, audiencia, locale ni orden. Una página posterior a la última devuelve content vacío
conservando los conteos; si el filtro propio no tiene actos, totalElements y totalPages son 0.

Cuenta actos y pagina dentro de la misma observación bajo el lock compartido del actor, de modo
que los escritores legales que respeten ese protocolo no pueden cambiar el total entre consultas.
La historia incluye únicamente evidencia real, tanto SCOPE_V1 anterior a V28 como AGGREGATE_V1,
y conserva afirmaciones, documentos y fechas originales después de reemplazos o retiros. Los
`documentos` conservan su ordinal histórico. La herencia no agrega entradas ni modifica fechas.
No depende de un conjunto vigente: esta consulta no materializa agregados ni ejecuta DML.

Éxito `private, no-store`, sin ETag ni respuesta 304: los condicionales no evitan consultar evidencia.
HEAD autenticado no lee y responde 405, y las URI codificadas equivalentes se rechazan antes del
lector. La sesión inválida o un actor que cambió produce 401, un rol no permitido, 403 y una historia
inconsistente o no disponible, 503 `CONTRATO_LEGAL_NO_DISPONIBLE`; sus details contienen únicamente
el contexto del filtro (o null) y locale es-AR. Los errores propios tienen no-store y nunca entregan
una página parcial. Métodos/rutas sin mapping conservan el manejador global existente.

Respuesta `200`:

```json
{
  "content": [
    {
      "id": "bd18f7c5-5fea-4470-bbec-bbcedf40f942",
      "requisitoVersionId": "557a8ce8-c72e-4088-a6b5-e2b0f775eb98",
      "contexto": "REGISTRO",
      "tipoActo": "ACEPTACION",
      "afirmacion": "Acepto los Términos y Condiciones de OrdenFix.",
      "afirmacionSha256": "68b041f6843211cb6e8d9e4799b1bb158af90cbf60a2d42fc9d70047a3b8bc1c",
      "documentos": [
        {
          "documentoVersionId": "77aa2a48-af19-4f80-92bb-d9f220c166d1",
          "tipo": "TERMINOS_SERVICIO",
          "version": "2026.08.1",
          "titulo": "Términos y Condiciones de OrdenFix",
          "sha256": "7a3fcccbd0e349cfb44129658cf7dd2d7c230eea560f8367cb6c25a7457b5298"
        }
      ],
      "aceptadoEn": "2026-09-01T13:42:18Z"
    }
  ],
  "page": {
    "size": 20,
    "number": 0,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

Sólo devuelve evidencia del actor. No expone IP, User-Agent, claves idempotentes, fingerprints ni
evidencia de empleados. Internamente, la evidencia sí conserva el snapshot canónico de userId,
tallerId, rol/audiencia, requisito, revisión, documentos/digests y hora `Instant` UTC; captura IP sólo
desde proxies confiables configurados y limita User-Agent a 512 caracteres. Esos metadatos se
protegen/retienen según la política aprobada y se omiten deliberadamente de este endpoint. El
documento completo se consulta por su URL pública exacta.

#### Registro objetivo con evidencia atómica

Cuando el rollout habilite enforcement, `POST /api/auth/register` requiere además:

```http
Idempotency-Key: 0a7c6d9b-bd14-4fc5-b828-0ea889dc3bb8
```

```json
{
  "nombreTaller": "CelExpress",
  "telefonoTaller": "1133334444",
  "nombreAdmin": "Juan",
  "email": "juan@celexpress.com",
  "password": "juan123",
  "requiredSetRevision": "sha256:db8e1d073852d51b643bba92fd99590bb410578dd09ab7f0b1a1cf25bcf9e15d",
  "aceptacionesLegales": [
    {
      "requisitoVersionId": "557a8ce8-c72e-4088-a6b5-e2b0f775eb98",
      "tipoActo": "ACEPTACION",
      "afirmacionSha256": "68b041f6843211cb6e8d9e4799b1bb158af90cbf60a2d42fc9d70047a3b8bc1c",
      "documentos": [
        {
          "documentoVersionId": "77aa2a48-af19-4f80-92bb-d9f220c166d1",
          "sha256": "7a3fcccbd0e349cfb44129658cf7dd2d7c230eea560f8367cb6c25a7457b5298"
        }
      ],
      "confirmado": true
    }
  ]
}
```

La respuesta exitosa continúa siendo `201` con el schema de §4.1. Taller, titular, suscripción,
evidencias e idempotencia se confirman en una transacción. Un fallo no deja una cuenta parcial.

Un replay exitoso no recrea el taller: vuelve a verificar la contraseña contra su hash y emite un
JWT nuevo con la misma forma de respuesta `201`. `emailVerificado` refleja su valor actual. También
aplica las precondiciones actuales de emisión de sesión: si cambió la contraseña o el usuario/taller
ya no puede iniciar sesión, devuelve el error genérico vigente de login/estado de cuenta y nunca
recrea ni revierte la operación original. El backend no guarda el body, contraseña o JWT en el
registro de idempotencia.

Durante el rollout aditivo, `enforcement=false` permite el request legacy únicamente cuando faltan
simultáneamente `Idempotency-Key`, `requiredSetRevision` y `aceptacionesLegales`. Si aparece cualquiera
de los tres, el bloque completo es obligatorio y el backend lo valida y persiste atómicamente aunque
el enforcement siga apagado; nunca descarta evidencia enviada por el cliente nuevo. Un bloque parcial
responde `400` y no crea la cuenta: si falta el header usa `IDEMPOTENCY_KEY_REQUERIDA`; si el header
existe pero falta un campo legal usa `ACEPTACION_LEGAL_INVALIDA` con
`PAYLOAD_LEGAL_INCOMPLETO`. Una propiedad presente con `null`, string vacío o lista vacía no cuenta
como ausente; legacy exige ausencia real de los tres elementos. Un bloque completo válido en forma con la capacidad
de consentimiento apagada falla cerrado con `503`, sin crear cuenta ni descartar el bloque enviado.

Con `enforcement=true`, omitir los tres elementos devuelve `428 ACEPTACION_LEGAL_REQUERIDA` con el
set público actual. Un bloque parcial continúa siendo `400`; uno completo siempre se valida. La
obligatoriedad sólo se activa después de desplegar el frontend compatible y confirmar la publicación
en staging.

#### Idempotencia

- Header exacto: `Idempotency-Key`; no uses `X-Idempotency-Key`.
- Valor: UUID v4 canónico de 36 caracteres.
- Scope autenticado: método + plantilla de ruta + usuario. En registro: método + plantilla de ruta y
  clave aleatoria global.
- El fingerprint es HMAC-SHA-256 con secreto servidor sobre método, plantilla, scope y DTO de negocio
  normalizado/canonicalizado. Orden/whitespace de propiedades JSON no cambia el fingerprint;
  `aceptacionesLegales` se ordena por `requisitoVersionId` y sus documentos por
  `documentoVersionId`. En registro, la contraseña participa dentro de ese HMAC para detectar un
  payload distinto, pero no se persiste ni hashea por separado en el registro idempotente.
- Se genera una vez por intento lógico y se conserva ante timeout/reintento de red.
- Modificar el formulario o aceptar un set nuevo crea otra clave.
- Si ya hay una operación exitosa, igual clave + igual fingerprint reproduce el resultado semántico.
- Si esa operación existente tiene otro payload, responde `409 IDEMPOTENCY_KEY_REUTILIZADA`.
- Mientras la misma clave/fingerprint está en curso, el segundo request espera como máximo cinco
  segundos. Si el primero termina, reproduce su resultado; si no, devuelve `409
  IDEMPOTENCY_EN_PROGRESO` con `Retry-After: 1`.
- La garantía por clave dura al menos 24 horas y se extiende mientras exista el resultado durable,
  aunque su vencimiento haya pasado; sólo la purga posterior termina esa garantía. También cubre
  éxitos sin nuevos actos. Actor + versión de requisito tiene unicidad permanente para evitar
  duplicados entre pestañas. La rotación conserva las claves necesarias hasta purgar sus resultados
  y drenar operaciones que aún pudieran confirmar con la versión anterior.
- La reserva transitoria de una operación en curso no consume definitivamente la clave. Sólo el
  commit exitoso guarda resultado/fingerprint y consume la clave.
- Errores de validación, revisión vieja y fallos no confirmados no se guardan como éxitos. Si la
  transacción sí hizo commit pero el cliente observó un corte o un `5xx` posterior, el resultado
  idempotente quedó confirmado en la misma transacción y el retry devuelve éxito.

La resolución de un replay exitoso ocurre antes de comparar la revisión legal actual. Así, una
respuesta perdida no falla porque se publicó una versión nueva después de la transacción original.
Dos requests con claves distintas que compiten por la misma evidencia tampoco crean duplicados: si
la evidencia canónica ya quedó confirmada, ambos terminan en `204`; nunca se filtra un `409` genérico
de integridad.

#### Caché y ETag

| Recurso | `Cache-Control` | `ETag` / condición |
|---|---|---|
| Catálogo público | `public, max-age=0, must-revalidate` | `W/"<documentSetRevision>:p=<page>:s=<size>"` |
| Requisitos públicos | `public, max-age=0, must-revalidate` | `W/"<requiredSetRevision>"` |
| Documento exacto `VIGENTE` | `public, max-age=0, must-revalidate` | `W/"doc:<id>:VIGENTE:<sha256>"` |
| Documento `REEMPLAZADA`/`RETIRADA` | `public, max-age=31536000, immutable` | `W/"doc:<id>:<estado>:<sha256>"` |
| Requisitos autenticados | `private, no-store` | sin depender de ETag |
| Aceptaciones propias | `private, no-store` | sin depender de ETag |
| POST y errores legales | `no-store` | no cachear |

Los ETag son débiles porque validan el snapshot lógico canónico y no prometen identidad byte a byte
entre Jackson, compresión y proxies. Un `If-None-Match` válido devuelve `304` sin body en los GET
públicos revalidables. No envíes ese header manualmente desde Axios: delegalo al caché HTTP del
navegador. La revisión también existe en el body donde el cliente necesita reenviarla, por lo que
JavaScript no necesita leer el header CORS.

`no-store` no borra la memoria de React Query: las queries autenticadas usan `staleTime: 0`,
recolección inmediata o limpieza explícita, una key ligada a la sesión y purga antes de cambiar de
usuario/tenant. No heredan un `staleTime` global de cinco minutos.

Los tres GET públicos legales quedan bajo rate limit por IP configurable. El número exacto no forma
parte del contrato v1; los headers `X-RateLimit-Limit` y `X-RateLimit-Remaining` son autoritativos y
un rechazo devuelve el `429` uniforme de §5 con `Retry-After`. Las respuestas cacheadas por el
navegador evitan requests innecesarios.

Para que Axios pueda leerlos desde el origin del frontend, la implementación debe ampliar el
`CorsConfig` actual: `Access-Control-Expose-Headers` conserva `Authorization` y agrega
`Retry-After`, `X-RateLimit-Limit` y `X-RateLimit-Remaining`. Esto también cubre el `Retry-After` del
`409 IDEMPOTENCY_EN_PROGRESO`. No se expone `ETag` para consumo manual; el navegador maneja la
revalidación y las revisiones necesarias ya viajan en el body.

La implementación de seguridad debe allowlistear por `HttpMethod.GET` las tres rutas legales exactas;
no se permite un `permitAll("/api/public/**")` amplio. El bypass JWT y la selección de rate policy
usan la misma clasificación cerrada.

#### Errores legales exactos

Se conserva el envelope de §5. `timestamp`, `status`, `error`, `message` y `path` siguen presentes;
`code` y `details` son contractuales para estas decisiones. Todos los errores legales envían
`Cache-Control: no-store`.

| HTTP / `code` | `details` | Acción del frontend |
|---|---|---|
| `400 ACEPTACION_LEGAL_INVALIDA` | `{ "motivos": ["..."] }` | Conservar form, bloquear envío y recargar set si corresponde |
| `400 CONTEXTO_LEGAL_NO_SOPORTADO` | `{ "contexto": string \| null, "contextosSoportados": ContextoLegal[] }` | No aplicar fallback silencioso |
| `400 LOCALE_LEGAL_NO_SOPORTADO` | `{ "locale": string \| null, "localesSoportados": LocaleLegal[] }` | Mostrar indisponibilidad del idioma |
| `400 IDEMPOTENCY_KEY_REQUERIDA` | `{ "header": "Idempotency-Key" }` | Corregir cliente; no reintentar sin clave |
| `400 IDEMPOTENCY_KEY_INVALIDA` | `{ "header": "Idempotency-Key" }` | Generar UUID v4 nuevo |
| `404 DOCUMENTO_LEGAL_NO_ENCONTRADO` | `{ "versionId": "uuid" }` | Mostrar versión no disponible |
| `409 DOCUMENTOS_LEGALES_DESACTUALIZADOS` | `{ "submittedRevision": "...", "requisitosActuales": RequisitosLegalesPublicosResponse \| RequisitosLegalesPendientesResponse }` | Conservar campos no legales y pedir nueva revisión |
| `409 IDEMPOTENCY_KEY_REUTILIZADA` | `{ "operacion": "REGISTRO" \| "ACEPTACION_LEGAL" }` | No reintentar; crear intento lógico nuevo |
| `409 IDEMPOTENCY_EN_PROGRESO` | igual `operacion`; header `Retry-After: 1` | Reintentar misma clave/payload después de la espera |
| `428 ACEPTACION_LEGAL_REQUERIDA` | `{ "requisitosActuales": RequisitosLegalesPublicosResponse \| RequisitosLegalesPendientesResponse }` | Montar gate; no hacer logout |
| `503 CONTRATO_LEGAL_NO_DISPONIBLE` | `{ "contexto": ContextoLegal \| null, "locale": "es-AR" }` | Bloquear aceptación/registro y ofrecer reintento |

Motivos estables de `ACEPTACION_LEGAL_INVALIDA`:

```text
REQUISITO_FALTANTE
REQUISITO_DUPLICADO
REQUISITO_NO_PERTENECE_AL_CONJUNTO
DOCUMENTO_FALTANTE
DOCUMENTO_DUPLICADO
DOCUMENTO_NO_PERTENECE_AL_REQUISITO
DIGEST_NO_COINCIDE
ACTO_NO_COINCIDE
CONFIRMACION_REQUERIDA
PAYLOAD_LEGAL_INCOMPLETO
```

Las listas de valores soportados dependen del endpoint y conservan el orden de los enums de esta
sección. En `/api/public/requisitos-legales`, `contextosSoportados` es exactamente `["REGISTRO"]`;
el catálogo público y el filtro opcional del historial propio admiten todos los valores de
`ContextoLegal`. Omitir `contexto` es válido sólo donde está documentado como opcional. Un query
obligatorio ausente se representa con `null`; un valor desconocido conserva su string original en
`details`. `localesSoportados` es `["es-AR"]` en v1 y un locale obligatorio ausente usa `null`.

Si `requiredSetRevision` ya no coincide, el backend devuelve primero `409`, sin intentar convertir
cada diferencia en un `400`.

Prioridad de evaluación: sesión/actor cuando corresponda; formato de un `Idempotency-Key` presente;
parseo JSON y DTO normalizado; clasificación legacy/parcial/completa del registro y header requerido;
fingerprint/replay; disponibilidad del contrato; deduplicación de una evidencia ya confirmada;
revisión del set; coincidencia semántica de requisitos/documentos. Sólo una lista no vacía, sin
duplicados y totalmente confirmada con la misma evidencia canónica devuelve `204` antes de evaluar
freshness; luego el frontend refetchea cualquier requisito nuevo. Una lista vacía siempre pasa por
revisión y cobertura de obligatorios pendientes. La excepción es registro con enforcement activo y los tres elementos legales
ausentes, que devuelve `428` en lugar de un error de header. En rollout, las combinaciones parciales
siguen las reglas de la sección de registro anterior.

En `/api/auth/register`, `requisitosActuales` usa siempre
`RequisitosLegalesPublicosResponse`. En endpoints autenticados usa siempre
`RequisitosLegalesPendientesResponse`; no se decide por inspeccionar campos opcionales.

Ejemplo de revisión desactualizada:

```json
{
  "timestamp": "2026-09-02T18:10:00",
  "status": 409,
  "error": "Conflicto de estado",
  "message": "Las condiciones cambiaron. Revisá la nueva versión.",
  "path": "/api/auth/register",
  "code": "DOCUMENTOS_LEGALES_DESACTUALIZADOS",
  "details": {
    "submittedRevision": "sha256:1111111111111111111111111111111111111111111111111111111111111111",
    "requisitosActuales": {
      "contexto": "REGISTRO",
      "locale": "es-AR",
      "requiredSetRevision": "sha256:2222222222222222222222222222222222222222222222222222222222222222",
      "requisitos": [
        {
          "id": "557a8ce8-c72e-4088-a6b5-e2b0f775eb98",
          "contexto": "REGISTRO",
          "tipoActo": "ACEPTACION",
          "afirmacion": "Acepto los Términos y Condiciones de OrdenFix.",
          "afirmacionSha256": "68b041f6843211cb6e8d9e4799b1bb158af90cbf60a2d42fc9d70047a3b8bc1c",
          "documentos": [
            {
              "id": "77aa2a48-af19-4f80-92bb-d9f220c166d1",
              "tipo": "TERMINOS_SERVICIO",
              "version": "2026.08.1",
              "titulo": "Términos y Condiciones de OrdenFix",
              "contenidoMarkdown": "# Términos y Condiciones de OrdenFix\n\n...",
              "sha256": "7a3fcccbd0e349cfb44129658cf7dd2d7c230eea560f8367cb6c25a7457b5298",
              "vigenteDesde": "2026-09-01T03:00:00Z",
              "estado": "VIGENTE",
              "locale": "es-AR"
            }
          ],
          "requerido": true
        }
      ]
    }
  }
}
```

Ejemplo de gate autenticado:

```json
{
  "timestamp": "2026-09-02T18:10:00",
  "status": 428,
  "error": "Precondición requerida",
  "message": "Tenés condiciones pendientes de revisión y aceptación.",
  "path": "/api/reparaciones",
  "code": "ACEPTACION_LEGAL_REQUERIDA",
  "details": {
    "requisitosActuales": {
      "locale": "es-AR",
      "requiredSetRevision": "sha256:2222222222222222222222222222222222222222222222222222222222222222",
      "requisitos": [
        {
          "id": "557a8ce8-c72e-4088-a6b5-e2b0f775eb98",
          "contexto": "USO_CONTINUADO",
          "tipoActo": "ACEPTACION",
          "afirmacion": "Acepto los Términos y Condiciones de OrdenFix.",
          "afirmacionSha256": "68b041f6843211cb6e8d9e4799b1bb158af90cbf60a2d42fc9d70047a3b8bc1c",
          "documentos": [
            {
              "id": "77aa2a48-af19-4f80-92bb-d9f220c166d1",
              "tipo": "TERMINOS_SERVICIO",
              "version": "2026.08.1",
              "titulo": "Términos y Condiciones de OrdenFix",
              "contenidoMarkdown": "# Términos y Condiciones de OrdenFix\n\n...",
              "sha256": "7a3fcccbd0e349cfb44129658cf7dd2d7c230eea560f8367cb6c25a7457b5298",
              "vigenteDesde": "2026-09-01T03:00:00Z",
              "estado": "VIGENTE",
              "locale": "es-AR"
            }
          ],
          "requerido": true
        }
      ]
    }
  }
}
```

El backend nunca incluye contraseña, JWT, clave idempotente ni el request original en `details` o
logs. Si falta o se corrompe el set vigente responde `503`, nunca éxito con contenido parcial.

`428` no puede bloquear las rutas necesarias para resolverlo: documentos, requisitos, lectura y
creación de aceptaciones propias, verificación/reenvío de email, estado de cuenta y logout cuando
existan. Esas rutas no responden recursivamente el mismo `428`.

Rechazar una nueva versión tampoco puede encerrar al usuario en la cuenta. El interceptor del gate
debe exceptuar las combinaciones exactas de método y ruta destinadas a cancelar renovación o
suscripción, pedir la baja personal o del servicio, consultar/iniciar/descargar una exportación,
cerrar la cuenta y solicitar eliminación/supresión. Esto incluye, mientras sigan vigentes, `POST
/api/pagos/suscripcion/cancelar` y `GET /api/export/excel`; cada reemplazo se incorpora de forma
explícita, sin wildcards. La excepción sólo evita el `428`: no omite autenticación, rol, aislamiento
por taller, reautenticación ni controles propios de cada operación.

#### CLI interna de validación e importación (Fases 2.3A y 2.3B)

Esta herramienta es operativa del backend; el frontend no la invoca ni consume su salida. El build
genera un ejecutable separado del API:

```text
target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar
```

Uso:

```bash
java -jar target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar \
  validate --manifest=/ruta/release/publication-manifest.json

SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/database \
SPRING_DATASOURCE_USERNAME=usuario \
SPRING_DATASOURCE_PASSWORD=secreto \
java -jar target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar \
  dry-run --manifest=/ruta/release/publication-manifest.json
```

Los comandos v1 aceptan exactamente `validate` o `dry-run` y una única asignación
`--manifest=<path>`. No admiten passwords ni propiedades Spring por argumento. `validate` es
completamente offline. `dry-run` exige `SPRING_DATASOURCE_URL`; por allowlist puede recibir también
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` y
`SPRING_DATASOURCE_DRIVER_CLASS_NAME`. La base debe estar migrada previamente y ser compatible con
V27: la CLI nunca ejecuta Flyway.

El launcher debe eliminar o controlar `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS`.
La JVM procesa esas variables antes de entrar a `main`, por lo que quedan fuera de la garantía de
redacción propia del comando.

Mientras stdout permanezca operativo, contiene un único JSON compacto UTF-8, seguido por un único
LF. El orden estable de primer nivel es:

```text
reportVersion, command, status, persisted, publication, counts, dryRun, issues,
omittedIssueCount
```

- `publication`: `publicationId`, `schemaVersion`, `manifestSha256`;
- `counts`: `documents`, `requirements`, `scopes`;
- `dryRun`: `newDocumentLines`, `newDocumentVersions`, `reusedDocumentVersions`,
  `newRequirementLines`, `newRequirementVersions`, `reusedRequirementVersions`; es `null` en
  cualquier reporte salvo un `dry-run` con estado `PASS`;
- cada issue: `severity`, `code`, `location`, `message`.

`persisted` es siempre `false`. Los estados y exit codes son `PASS`/`0`, `BLOCKED`/`2` y
`ERROR`/`3`. Se exponen como máximo 200 issues; el resto se cuenta en `omittedIssueCount`. Fuera de
la metadata segura enumerada (`publication`, `counts` y, cuando corresponde, `dryRun`), el reporte no
incluye argumentos, campos o contenido sensible, ruta absoluta, PII, SQL, stack traces, constraints
desconocidas ni credenciales.

Límites bloqueantes v1:

| Recurso | Máximo |
|---|---:|
| manifiesto raw | 1 MiB (`1_048_576`) |
| profundidad JSON | 32 |
| tokens JSON | 100.000 |
| string JSON | 1 MiB |
| nombre de campo JSON | 256 |
| número JSON | 128 caracteres |
| documentos | 128 |
| requisitos | 256 |
| documentos por requisito | 16 |
| Markdown individual | 1 MiB |
| Markdown total | 16 MiB |
| issues expuestos | 200 |

El schema v1 pesa `10547` bytes y su SHA-256 es
`f7a4ee17f53f5ed3f2613d894fa3a4f46896dfaaec0c80dab055e4320f036f8b`; la copia frontend y la
embebida en backend deben permanecer byte-identical. La fixture golden acredita 11 documentos, 6
requisitos y 8 scopes.

Matriz contractual mínima:

| Scope obligatorio | Tipos requeridos agregados |
|---|---|
| `REGISTRO / ADMIN_TITULAR` | términos, privacidad, DPA |
| `PRIMER_INGRESO_EMPLEADO / USER` | términos usuario, privacidad usuario, confidencialidad |
| `CONTRATACION_PRO / ADMIN_TITULAR` | términos, condiciones PRO, cancelaciones/reembolsos |
| `ATESTACION_FOTOS / ADMIN_TITULAR,USER` | aviso clientes, atestación |
| `ATESTACION_CREDENCIALES / ADMIN_TITULAR,USER` | aviso clientes, atestación |
| `CIERRE_CUENTA / ADMIN_TITULAR` | cierre de cuenta |

El dry-run construye el grafo provisional dentro de PostgreSQL y fuerza rollback. Después de
`PASS`, blockers y errores tardíos, el delta confirmado en las 12 tablas legales es cero. Esto no
promete ausencia de efectos físicos: puede generar WAL, tomar locks y avanzar secuencias no
transaccionales. Para exigir cero efectos físicos se usa una base descartable; para una validación
sin interacción con infraestructura se usa `validate`.

La Fase 2.3B agregó `import` como operación exclusiva de plataforma. El frontend no lo invoca. Se
ejecuta únicamente mediante `scripts/legal-manifest-import.sh`, con credenciales
`ORDENFIX_LEGAL_IMPORT_DB_*`, habilitación exacta y confirmaciones explícitas de `publicationId` y
SHA-256 JCS. El comando usa reporte v2: un fresco confirmado devuelve `IMPORTED`, un replay exacto
`ALREADY_IMPORTED`, ambos con `persisted=true` y el mismo receipt. Blockers y errores conocidos
devuelven `persisted=false`; sólo una finalización DB realmente indeterminada devuelve
`persisted=null` y `outcome=UNKNOWN`. El retry debe repetir exactamente el mismo bundle y
confirmaciones. El importador sella el grafo, deja versiones nuevas en `BORRADOR` y nunca promueve.

El cierre reproducible y el runbook operativo están en
`docs/plans/2026-08-25-legal-manifest-import-closure.md` y
`docs/runbooks/legal-manifest-import-postgresql.md`.

El cierre previo de 2.3A, su matriz de pruebas y sus alcances están en
`docs/plans/2026-08-25-legal-manifest-dry-run-closure.md`.

#### CLI editorial interna (Fase 2.3C)

Esta CLI es una operación de plataforma; el frontend no la invoca ni consume su salida. Se ejecuta
exclusivamente mediante `scripts/legal-manifest-editor.sh`. En una operación real se fijan y
verifican `ORDENFIX_LEGAL_CLI_JAR` y `ORDENFIX_JAVA_BIN`; no se invoca el JAR directamente ni se
depende del fallback local a `target/`. Opera sobre PostgreSQL 16, V27 y el schema fijo `public`,
con un rol editorial distinto del importador.

El datasource editorial se recibe sólo por `ORDENFIX_LEGAL_EDITOR_DB_URL`,
`ORDENFIX_LEGAL_EDITOR_DB_USERNAME`, `ORDENFIX_LEGAL_EDITOR_DB_PASSWORD` y el driver opcional
`ORDENFIX_LEGAL_EDITOR_DB_DRIVER_CLASS_NAME`. Las propiedades `-Dspring.datasource.*` están
prohibidas. `ORDENFIX_LEGAL_EDITOR_ENABLED=true` se inyecta únicamente en el proceso individual de
cada `apply-*`, nunca como habilitación global para readiness o planificación.

Los siete comandos exactos y case-sensitive son:

```text
readiness
plan-promote
apply-promote
plan-replace
apply-replace
plan-retire
apply-retire
```

`readiness`, `plan-promote` y `apply-promote` reciben exactamente `--manifest=<ruta>`,
`--confirm-publication-id=<id>` y `--confirm-manifest-sha256=<64-hex>`. Los cuatro comandos de
reemplazo/retiro agregan `--editorial-plan=<ruta>`, `--confirm-operation-id=<uuid>` y
`--confirm-editorial-plan-sha256=<64-hex>`. Faltantes, extras o duplicados bloquean antes de JDBC.

El reporte editorial v3 escribe un único JSON por stdout y mantiene stderr separado. El orden
superior es:

```text
reportVersion, command, status, persisted, publication, operation, plan,
readiness, counts, issues, omittedIssueCount
```

Los estados y exits son `PASS/0`, `BLOCKED/2` y `ERROR/3`. Un plan `APPLICABLE` no persiste; un
apply exitoso devuelve `APPLIED` o `ALREADY_APPLIED` con `persisted=true`. Un retiro aplicado
termina `NOT_READY` con exit `0` porque deja un hueco fail-closed deliberado; la consulta
`readiness` posterior devuelve `NOT_READY` y exit `2`. `UNKNOWN` usa `persisted=null` y `ERROR/3` y
exige observación más retry exacto, sin inferir rollback.

El perfil PostgreSQL, las confirmaciones, la captura sin pipelines, la reconciliación y el cierre de
credenciales están en `docs/runbooks/legal-manifest-editorial-postgresql.md`. Readiness editorial
`READY` no abre una ruta HTTP ni acredita contenido, seguridad, staging o disponibilidad pública.

#### Materialización agregada interna (V28)

V28 materializa y reconstruye agregados inmutables mediante un servicio interno, sin controller,
endpoint ni comando para el frontend. Usa un contexto aislado con credencial restringida, preflight
V28 y transacción `REQUIRES_NEW/READ_COMMITTED` bajo el gate editorial compartido. La misma identidad
física devuelve `REUSED` sin DML; un snapshot físico distinto puede conservar el token semántico y
registrar otra procedencia. V27 y V28 permanecen congeladas y la historia V27 conserva su identidad.

El resolver productivo mínimo fija `REGISTRATION` en `REGISTRO/ADMIN_TITULAR` y
`AUTHENTICATED_PENDING` en `USO_CONTINUADO`. Las reglas de ciclo de vida que amplíen este último
perfil siguen pendientes; los fixtures de ocho contextos prueban capacidad sin habilitar esos
flujos. La persistencia y los guards de lotes no implementan el servicio de aceptación ni el cálculo
de pendientes. El alcance y la evidencia están en
`docs/plans/2026-09-01-legal-required-set-aggregate-v28-closure.md`.

#### Orden de rollout

1. Mantener 2.3C como operación interna V27, sin exposición HTTP.
2. V28 multicontexto está implementada como núcleo y persistencia internos; conservar su frontera
   aislada y usar su cierre como base de los cortes HTTP pendientes.
3. El bloque 13 completa catálogo, `documentSetRevision`, documento exacto, ETag y sus políticas HTTP,
   con flag apagado y gate integral acreditado. Diseñar e implementar requisitos HTTP; enforcement sigue apagado.
4. Implementar aceptación y registro atómicos, idempotencia HTTP y respuestas legales `409/428/503`.
5. Extender la clasificación cerrada, seguridad, CORS y rate limits a los recursos pendientes;
   implementar enforcement compatible y mantenerlo desactivado hasta completar la validación.
6. Desplegar y migrar las capas backend compatibles en staging; luego importar/promover el release
   definitivo aprobado y validar readiness pública y smokes remotos.
7. Sólo entonces acreditar `BACKEND-HANDOFF 1`.
8. Conectar la Tarea 3, desplegar un frontend compatible en staging y ejecutar E2E.
9. Después decidir la activación productiva y la reaceptación, sin autoaceptar a cuentas legacy.

La readiness editorial `READY` sólo inspecciona el grafo V27; no sustituye el preflight V28 ni
acredita HTTP, aceptación de aplicación, seguridad, staging o disponibilidad pública.

Las decisiones y lo que queda fuera de esta fase están documentados en
`docs/plans/2026-08-23-legal-api-contract-v1-design.md`.

---

### 4.2 Suscripción / Plan — requiere token

#### `GET /api/suscripcion`
Plan y consumo del taller actual. Úsalo para la pantalla de Planes y el banner de "límite alcanzado".
```json
{
  "plan": "FREE",                  // FREE | PRO
  "estado": "TRIAL",               // TRIAL | ACTIVA | VENCIDA | CANCELADA
  "fechaInicio": "2026-06-16",
  "fechaFinTrial": "2026-06-30",   // null si no aplica
  "proximoCobro": null,            // fecha del próximo cobro PRO (la setea el webhook de MercadoPago)
  "reparacionesEsteMes": 12,
  "limiteReparacionesMes": null,   // null = ilimitado (TRIAL o PRO/ACTIVA)
  "funciones": {                   // capacidades del plan: el front habilita/oculta según esto
    "inventario": true,
    "cobros": true,                // cobros manuales + resumen por período + resumen digital
    "empleadosMultiples": true     // agregar más de 1 usuario
  }
}
```

**Entitlements:** durante `estado: "TRIAL"`, el taller recibe la experiencia PRO completa aunque el campo
`plan` sea `"FREE"`: funciones habilitadas y reparaciones ilimitadas. En `estado: "ACTIVA"`, las funciones
`inventario`, `cobros` (cobros manuales, datos de cobro y resumen digital) y `empleadosMultiples`
quedan habilitadas solo para `plan: "PRO"`.
El front debe usar siempre `funciones` y `limiteReparacionesMes` como fuente de verdad, **no inferir permisos
solo desde `plan`**. Debe deshabilitar/ocultar las secciones cuyo flag esté en `false`. Si igual se llama a un
endpoint sin entitlement, el backend responde **`402`** con un `message` accionable (modal "Pasá a PRO").
El plan `FREE/ACTIVA` mantiene clientes, equipos, reparaciones (con tope mensual), repuestos,
presupuestos, **dashboard** y seguimiento público.

### 4.2.a Perfil autenticado — requiere token (`/api/perfil`)

`GET /api/perfil` es la fuente canónica para mostrar quién inició sesión y a qué taller pertenece.
No requiere plan PRO. Tanto ADMIN como USER reciben únicamente su propio usuario y su propio tenant:

```json
{
  "usuario": {
    "id": 17,
    "username": "Juan",
    "email": "juan@celexpress.com",
    "role": "ADMIN"
  },
  "taller": {
    "id": 4,
    "nombre": "CelExpress",
    "telefono": "1133334444"
  }
}
```

El frontend no debe inferir el nombre del taller desde `usuario.username` ni enviar ninguno de esos
IDs en otras operaciones. Para la interfaz, `taller.nombre` identifica el negocio y
`usuario.username` identifica a la persona autenticada.

---

### 4.2.bis Paginación y búsqueda (todos los listados principales)

Los `GET` de listado (`/api/clientes`, `/api/equipos`, `/api/reparaciones`, `/api/repuestos`)
**devuelven una página**, no un array. Aceptan query params:

- `page` (0-based, default 0), `size` (default 20), `sort` (ej: `sort=nombre,asc`).
- `q` = búsqueda por texto (clientes: nombre/apellido/teléfono; equipos: marca/modelo/IMEI **+ cliente nombre/apellido**;
  reparaciones: descripción **+ cliente nombre/apellido/teléfono + equipo marca/modelo**; repuestos: nombre).
- Reparaciones además: `estado=EN_PROCESO` (filtro por estado). Se combinan `estado` + `q` + `page/size/sort`.
- `sort` ordena por **columnas de la entidad** (no por los campos denormalizados). Campos y defaults:
  - reparaciones → `id`,`estado`,`fechaIngreso`,`fechaEstimadaEntrega`,`fechaEntrega`,`precioFinal`,`createdAt` · default `id,desc`
  - clientes → `nombre`,`apellido`,`telefono`,`id` · default `nombre,asc`
  - equipos → `id`,`marca`,`modelo` · default `id,desc`

Forma de la respuesta (estable):
```json
{
  "content": [ /* ...los items... */ ],
  "page": { "size": 20, "number": 0, "totalElements": 42, "totalPages": 3 }
}
```
> En el front, leé `data.content` para los items y `data.page` para la paginación.
> Los endpoints `/cliente/{id}`, `/equipo/{id}`, `/reparacion/{id}` (sub-listados) siguen devolviendo array simple.

---

### 4.3 Clientes — requiere token  (`/api/clientes`)

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/api/clientes` | ClienteRequest | `200` ClienteResponse |
| PUT    | `/api/clientes/{id}` | ClienteRequest | `200` ClienteResponse |
| GET    | `/api/clientes/{id}` | — | `200` ClienteResponse |
| GET    | `/api/clientes?q=&page=&size=&sort=` | — | `200` Page de ClienteResponse |
| DELETE | `/api/clientes/{id}` | — | `204` |

ClienteRequest:
```json
{
  "nombre": "Pedro",       // obligatorio, máx 60
  "apellido": "Gomez",     // obligatorio, máx 60
  "telefono": "1155556666",// obligatorio, máx 20, único POR TALLER
  "email": "pedro@mail.com",// opcional, formato email, máx 120, único por taller
  "direccion": "Calle 123" // opcional, máx 255
}
```
ClienteResponse: `{ id, nombre, apellido, telefono, email, direccion, equiposCount, reparacionesCount, ultimaVisita }`
- `equiposCount` / `reparacionesCount`: agregados del cliente. `ultimaVisita`: fecha-hora de su última reparación (`null` si no tiene).

Errores: `400` si el teléfono ya existe en el taller (al crear); `409` si choca la unicidad al actualizar
o si se intenta borrar un cliente que todavía tiene equipos. Ante el `409`, conservá la fila y mostrale
al usuario el `message` del backend.

---

### 4.4 Equipos — requiere token  (`/api/equipos`)

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/api/equipos` | EquipoRequest | `201` EquipoResponse |
| PUT    | `/api/equipos/{id}` | EquipoRequest | `200` EquipoResponse |
| GET    | `/api/equipos/{id}` | — | `200` EquipoResponse |
| GET    | `/api/equipos?q=&page=&size=` | — | `200` Page de EquipoResponse |
| GET    | `/api/equipos/cliente/{clienteId}` | — | `200` EquipoResponse[] |
| DELETE | `/api/equipos/{id}` | — | `204` |

EquipoRequest:
```json
{
  "marca": "Samsung",      // obligatorio, máx 60
  "modelo": "A52",         // obligatorio, máx 60
  "imei": "35...",         // opcional, máx 30
  "color": "Negro",        // opcional, máx 40
  "descripcion": "...",    // opcional, máx 255
  "clienteId": 1           // obligatorio
}
```
EquipoResponse: `{ id, marca, modelo, imei, color, descripcion, clienteId, clienteNombre, clienteApellido, clienteTelefono, reparacionesCount }`

Errores: `404` si el `clienteId` no pertenece a tu taller; `409` si se intenta borrar un equipo que
todavía tiene reparaciones. No elimines optimistamente la fila antes de recibir el `204`.

---

### 4.5 Reparaciones — requiere token  (`/api/reparaciones`)

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/api/reparaciones` | ReparacionRequest | `201` ReparacionResponse |
| POST   | `/api/reparaciones/{id}/garantia` | `{ "descripcionProblema": "..." }` | `201` ReparacionResponse (reclamo en garantía) |
| PUT    | `/api/reparaciones/{id}` | ReparacionRequest | `200` ReparacionResponse |
| PATCH  | `/api/reparaciones/{id}/estado` | `{ "estado": "EN_PROCESO" }` | `200` ReparacionResponse · `409` si la transición no es legal |
| GET    | `/api/reparaciones/{id}` | — | `200` ReparacionDetalleResponse |
| GET    | `/api/reparaciones?q=&estado=&page=&size=` | — | `200` Page de ReparacionResponse |
| GET    | `/api/reparaciones/equipo/{equipoId}` | — | `200` ReparacionResponse[] |
| GET    | `/api/reparaciones/estado?estado=EN_PROCESO` | — | `200` ReparacionResponse[] |
| DELETE | `/api/reparaciones/{id}` | — | `204` |

ReparacionRequest:
```json
{
  "equipoId": 1,                       // obligatorio
  "descripcionProblema": "No carga",   // obligatorio
  "estado": "INGRESADO",               // opcional (default INGRESADO al crear)
  "precioEstimado": 15000.00,          // opcional, decimal
  "precioFinal": null,                 // opcional, decimal
  "fechaIngreso": "2026-06-16",        // opcional, YYYY-MM-DD
  "fechaEstimadaEntrega": "2026-06-20",// opcional
  "fechaEntrega": null,                // opcional
  // ----- Orden de trabajo ampliada (todo opcional) -----
  "patronDesbloqueo": "L invertida",   // máx 60
  "pinDesbloqueo": "1234",             // máx 20
  "accesorios": "cargador, funda, SIM",// máx 255
  "condicionesIngreso": "rayada",      // máx 500
  "observaciones": "interno, no público", // máx 1000
  // ----- Flags de riesgo del ingreso (todo opcional; default false / NINGUNA) -----
  "mojado": false,                     // cayó al agua: garantía limitada
  "trabajoEnPlaca": false,             // reparación a nivel placa
  "noTesteableAlIngreso": false,       // no enciende / sin carga → diagnóstico provisorio
  "tieneBloqueoPantalla": false,       // tiene PIN/patrón (se guarda en pin/patronDesbloqueo)
  "tieneCuentaVinculada": "NINGUNA",   // NINGUNA | ICLOUD | GOOGLE | OTRA
  "clienteConoceCredenciales": false,  // ¿puede quitar la cuenta?
  "tecnicoId": 1,                      // id de un usuario del taller (404 si no existe)
  "fotos": [                           // la subida del archivo la hace el front a su storage
    { "url": "https://cdn/ingreso1.jpg", "momento": "INGRESO" },
    { "url": "https://cdn/post1.jpg",    "momento": "POST_REPARACION" }
  ],
  "fechaConformidadEntrega": null,     // opcional; si no, se sella solo al pasar a ENTREGADO
  "garantiaDias": 90,                  // opcional; si no, el default del backend al entregar
  "garantiaCondiciones": "Cubre solo el repuesto cambiado" // opcional
}
```
> **Fotos**: cada una lleva `momento` (`INGRESO` | `POST_REPARACION`; si se omite, `INGRESO`).
> Las de ingreso prueban la condición de entrada; las post, el trabajo (QA). **Cambió el formato:**
> antes era `string[]`, ahora es `{ url, momento }[]`.
> El **`numeroOrden`** NO se envía: lo genera el backend al crear (correlativo por taller con
> reinicio anual, ej. `ORD-2026-0042`) y es inmutable. El ingreso rápido también lo asigna; los
> flags de riesgo se pueden completar después editando la reparación.

> **PIN/patrón al escribir:** el alta acepta ambos campos pero su `201` no los devuelve. En un `PUT`,
> omitir el campo conserva el valor ya guardado; enviar string vacío (`""`, también si contiene solo
> espacios) lo borra. Nunca uses la respuesta de un alta/PUT/PATCH para reemplazar en el formulario el
> secreto que el usuario acaba de escribir. Al pasar a `ENTREGADO`, el backend borra ambos valores.

ReparacionResponse: `{ ...campos no sensibles de ReparacionRequest, numeroOrden, equipoMarca, equipoModelo, clienteId, clienteNombre, clienteApellido, clienteTelefono, codigoSeguimiento, tecnicoId, tecnicoNombre, fotos (FotoDTO[]), fechaConformidadEntrega, totalRepuestos, total, cobrado, saldo, estadoPago, mojado, trabajoEnPlaca, noTesteableAlIngreso, tieneBloqueoPantalla, tieneCuentaVinculada, clienteConoceCredenciales, riesgoCuentaSinCredenciales, garantiaDias, garantiaInicio, garantiaFin, garantiaCondiciones, garantiaVigente, esGarantia, reparacionOrigenId }`. Los campos sensibles excluidos son `patronDesbloqueo` y `pinDesbloqueo`.
- **Denormalizado**: cada reparación trae el `equipoMarca/Modelo` y los datos del cliente, así el listado/tablero es autocontenido (no hace falta cruzar con otros endpoints).
> **Contrato de credenciales:** `ReparacionResponse` **no contiene** `patronDesbloqueo` ni
> `pinDesbloqueo`. Es la respuesta segura usada por POST, PUT, PATCH, reclamos en garantía, listados,
> dashboard e ingreso rápido. Solo `GET /api/reparaciones/{id}` autenticado devuelve
> `ReparacionDetalleResponse`, que agrega esos dos campos (pueden ser `null`) y respeta el tenant.
> El seguimiento público nunca los expone. Después de `ENTREGADO`, el detalle devuelve ambos en `null`.
- `codigoSeguimiento`: código público para compartir con el cliente (ver §4.9).
- `totalRepuestos`: suma de los repuestos. `total`: mano de obra (`precioFinal ?? precioEstimado ?? 0`) + repuestos.
- **Estado de pago** (dimensión independiente del estado de reparación): `cobrado` (suma de cobros; **0 en planes FREE**, que no usan cobros), `saldo` = `max(0, total - cobrado)`, y `estadoPago` derivado: `SIN_COBRAR | PARCIAL | PAGADO`.
  - En el tablero mostrá **las dos dimensiones**: ej. chip de estado ("Listo") + chip de pago ("Falta cobrar $X" si `saldo > 0`).
  - **No bloquea la entrega**: se puede pasar a `ENTREGADO` con saldo pendiente (pago al retirar). El front decide si avisa.
- **`numeroOrden`**: correlativo por taller con reinicio anual (`ORD-2026-0042`). Útil para etiquetar/buscar el equipo en el estante. Inmutable.
- **Flags de riesgo del ingreso**: `mojado`, `trabajoEnPlaca`, `noTesteableAlIngreso`, `tieneBloqueoPantalla`, `tieneCuentaVinculada` (`NINGUNA|ICLOUD|GOOGLE|OTRA`), `clienteConoceCredenciales`.
  - **`riesgoCuentaSinCredenciales`** (derivado, solo lectura): `true` cuando `tieneCuentaVinculada != NINGUNA` y `!clienteConoceCredenciales`. Mostrá una **bandera roja**: *"Riesgo: equipo con cuenta activa sin credenciales. Puede no poder entregarse activado."*
- **`fechaConformidadEntrega`**: cuándo el cliente retiró conforme (anti-disputa). Se **sella automáticamente** al pasar la reparación a `ENTREGADO` (si no se mandó antes); también se puede setear a mano en el PUT.
- **Garantía**: al pasar a `ENTREGADO`, el backend fija `garantiaInicio` (hoy), `garantiaFin` (= inicio + `garantiaDias`, default **90**) y deja `garantiaCondiciones`. `garantiaVigente` (derivado) indica si todavía no venció. `esGarantia`/`reparacionOrigenId` marcan los retrabajos en garantía.
  - **Reclamo en garantía**: `POST /api/reparaciones/{id}/garantia` con `{ descripcionProblema }` crea una **reparación nueva** vinculada al original (mismo equipo, `esGarantia: true`, `reparacionOrigenId`). **No consume cupo** del plan FREE y **arranca sin precio** (`total: 0`); el taller cobra solo si el diagnóstico revela falla ajena (golpe/mojado nuevo).

**Avisar al cliente por WhatsApp:** `GET /api/reparaciones/{id}/whatsapp` → `{ url, telefono, mensaje, linkSeguimiento }`.
El front abre `url` (wa.me con mensaje prearmado que incluye el link de seguimiento). Útil al pasar a COMPLETADO.

**Cambiar estado** (`PATCH .../estado`): body JSON con el campo `estado`:
```json
{ "estado": "EN_PROCESO" }
```
(Header `Content-Type: application/json`.)
- Estado inexistente (no está en el enum) → `400`.
- **Transición no permitida** (el salto desde el estado actual no es legal) → **`409`**. Repetir el estado actual (X → X) es un no-op válido (`200`).

Errores: `404` si el equipo/reparación no es de tu taller; **`402` si alcanzaste el límite del plan**
(ver §6); `400` si se intenta borrar una reparación con cobros registrados. En ese caso el historial
de caja se preserva y el front debe mostrar el `message` sin quitar la reparación de la vista.

Estados posibles (enum `EstadoReparacion`):
`INGRESADO`, `EN_DIAGNOSTICO`, `PRESUPUESTADO`, `EN_PROCESO`, `ESPERANDO_REPUESTO`,
`ESPERANDO_ADICIONAL`, `NO_REPARABLE`, `COMPLETADO`, `LISTO_SIN_REPARAR`, `ENTREGADO`,
`ABANDONADO`, `CANCELADO`

**Máquina de estados (transiciones permitidas).** El backend valida el salto; el front
debería ofrecer solo las opciones legales según el estado actual:

| Desde | Hacia (permitidos) |
|-------|--------------------|
| `INGRESADO` | `EN_DIAGNOSTICO`, `PRESUPUESTADO` (presupuesto directo), `EN_PROCESO` (arreglo simple), `CANCELADO` |
| `EN_DIAGNOSTICO` | `PRESUPUESTADO`, `NO_REPARABLE`, `CANCELADO` |
| `PRESUPUESTADO` | `EN_PROCESO` (cliente aprueba), `LISTO_SIN_REPARAR` (rechaza), `CANCELADO` |
| `EN_PROCESO` | `ESPERANDO_REPUESTO`, `ESPERANDO_ADICIONAL`, `COMPLETADO`, `NO_REPARABLE` |
| `ESPERANDO_REPUESTO` | `EN_PROCESO`, `NO_REPARABLE`, `CANCELADO` |
| `ESPERANDO_ADICIONAL` | `EN_PROCESO` (aprueba), `COMPLETADO` (solo lo aprobado), `LISTO_SIN_REPARAR` (rechaza) |
| `NO_REPARABLE` | `LISTO_SIN_REPARAR` |
| `COMPLETADO` | `ENTREGADO`, `ABANDONADO` |
| `LISTO_SIN_REPARAR` | `ENTREGADO`, `ABANDONADO` |
| `ENTREGADO` / `ABANDONADO` / `CANCELADO` | *(terminales: sin salida)* |

---

#### `POST /api/reparaciones/ingreso-rapido` — carga rápida (1 paso)
Crea **cliente + equipo + reparación de una sola vez** con lo mínimo. Ideal para el mostrador:
después se completan los datos faltantes desde las pantallas de Cliente/Equipo/Reparación.

Request (solo lo imprescindible; `clienteApellido` y `precioEstimado` son opcionales):
```json
{
  "clienteNombre": "Marcos",
  "clienteApellido": "Pérez",       // opcional
  "clienteTelefono": "1144556677",
  "equipoMarca": "Apple",
  "equipoModelo": "iPhone 12",
  "descripcionProblema": "Pantalla rota",
  "precioEstimado": 80000            // opcional
}
```
Respuesta `201`:
```json
{
  "clienteId": 1,
  "equipoId": 1,
  "clienteNuevo": true,             // false = se reutilizó un cliente existente
  "reparacion": { "id": 1, "equipoId": 1, "descripcionProblema": "Pantalla rota", "estado": "INGRESADO", "precioEstimado": 80000, "precioFinal": null, "fechaIngreso": null, "fechaEstimadaEntrega": null, "fechaEntrega": null }
}
```
Comportamiento:
- Si ya existe un cliente con ese **teléfono** en el taller, **lo reutiliza** (`clienteNuevo:false`) en vez de duplicarlo; crea siempre un equipo nuevo.
- Devuelve `clienteId` y `equipoId` para que el front pueda enlazar a "completar datos" del cliente/equipo.
- Aplica el límite del plan igual que el alta normal → puede dar **`402`**.

---

### 4.6 Repuestos — requiere token  (`/api/repuestos`)

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/api/repuestos` | RepuestoRequest | `201` RepuestoResponse |
| PUT    | `/api/repuestos/{id}` | RepuestoRequest | `200` RepuestoResponse |
| GET    | `/api/repuestos/{id}` | — | `200` RepuestoResponse |
| GET    | `/api/repuestos?q=&page=&size=` | — | `200` Page de RepuestoResponse |
| GET    | `/api/repuestos/reparacion/{reparacionId}` | — | `200` RepuestoResponse[] |
| DELETE | `/api/repuestos/{id}` | — | `204` |

RepuestoRequest:
```json
{
  "nombre": "Pantalla",    // obligatorio
  "descripcion": "OLED",   // opcional
  "precio": 8000.00,       // obligatorio, decimal
  "reparacionId": 1,       // opcional (puede cargarse sin asignar)
  "articuloId": 5,         // opcional: enlaza al inventario y DESCUENTA stock
  "cantidad": 2            // opcional, default 1 (cuánto descontar del stock)
}
```
RepuestoResponse: `{ id, nombre, descripcion, precio, reparacionId, reparacionEquipo, articuloId, cantidad }`
- `reparacionEquipo`: label "Marca Modelo" de la reparación asociada (`null` si no tiene reparación).
> Si mandás `articuloId`, el backend **descuenta `cantidad` del stock** (400 si no alcanza) y lo **repone** si borrás el repuesto. Ver inventario en §4.12.

---

### 4.7 Pagos / Upgrade a PRO — requiere token  (`/api/pagos`)

Suscripción PRO con **cobro mensual recurrente** vía MercadoPago (preapproval).

El precio contractual es **ARS 24.900 por mes** (`24900.00`). El monto y la moneda los fija el backend:
el frontend no los envía ni debe calcularlos.

#### `POST /api/pagos/suscripcion`
Inicia el checkout. No lleva body (el taller sale del token). Devuelve la URL de MercadoPago.
```json
{
  "preapprovalId": "2c938084abc...",
  "initPoint": "https://www.mercadopago.com.ar/subscriptions/checkout?preapproval_id=..."
}
```
El front **redirige** al usuario a `initPoint` (`window.location.href = data.initPoint`).
Al terminar, MercadoPago lo devuelve a `MP_BACK_URL` (por defecto `/suscripcion/resultado` del front).
Cuando MP está habilitado, esa URL debe ser **HTTPS**. Para probar el retorno desde un frontend local
se necesita una URL HTTPS pública/túnel; `http://localhost:5173/...` sirve únicamente con MP apagado.

- El backend valida la preferencia recibida contra su monto/moneda, `external_reference`,
  `collector_id: MP_COLLECTOR_ID` y `application_id: MP_APPLICATION_ID`, además de exigir un
  `initPoint` HTTPS de MercadoPago. Si algo no coincide, no entrega el checkout al front.
- `MP_CHECKOUT_ENABLED=false` es el **default seguro** y un kill switch solo para crear nuevos
  checkouts: el POST devuelve `502` ("Los nuevos checkouts están temporalmente deshabilitados"), pero
  siguen funcionando webhooks, cancelación y reconciliación. Se cambia a `true` únicamente después de
  aprobar sandbox. `MP_ENABLED=false` desactiva las llamadas de la integración completa.
- El POST es **idempotente del lado backend**: persiste una `external_reference` y una
  `X-Idempotency-Key` estables antes de llamar a MP. Ante timeout/`502`, el front puede reintentar el
  mismo POST; no debe generar ni mandar una clave propia. El backend reutiliza el intento recuperable
  y, si ya existe un checkout pendiente con URL, devuelve el mismo `preapprovalId`/`initPoint`.
- Si otro request ya lo está creando o ya existe una suscripción activa/pausada, devuelve `409`; mostrá
  el `message` y re-consultá la suscripción en vez de abrir dos checkouts.
- Tras el pago, el plan **no cambia al instante**: se confirma por webhook (server-to-server). El front debe **re-consultar `GET /api/suscripcion`** al volver (y/o reintentar unos segundos) para ver `plan: "PRO"` / `estado: "ACTIVA"`.

#### `POST /api/pagos/suscripcion/cancelar` — solo ADMIN
Cancela la suscripción PRO (cancela el preapproval en MercadoPago si existe) y **baja a plan FREE**
(el taller sigue operando con el tope gratuito). Devuelve la suscripción actualizada (`200`).
Si hay un preapproval remoto y MP está apagado o la cancelación remota falla, devuelve `502` y conserva
el plan/estado local para no dejar un cobro activo escondido detrás de una baja local.

#### `POST /api/pagos/webhook` — PÚBLICO (uso interno de MercadoPago)
Lo llama MercadoPago, **no el frontend**. Los eventos se guardan y procesan de forma durable e idempotente;
los reintentos y la reconciliación no duplican efectos, y un evento histórico fuera de orden no puede
revertir un estado de pago más nuevo. El frontend solo consulta `GET /api/suscripcion`.

El panel debe enviar `subscription_preapproval`, `subscription_authorized_payment` y `payment`. Para
`payment`, el backend consulta el recurso remoto validado y dispara conciliación; nunca toma como
verdad el payload recibido por webhook.

Estados relevantes del preapproval:

- `pending`: no activa PRO por sí solo. Si ese vínculo ya había concedido PRO, pasa a
  `PRO/VENCIDA` hasta recibir una confirmación autorizada; un TRIAL/FREE no se convierte en PRO.
- `authorized`: `PRO/ACTIVA`, salvo que el pago autorizado más reciente esté en un estado bloqueante.
- `paused`: conserva `plan: "PRO"`, pasa a `estado: "VENCIDA"` y pierde los entitlements.
- `canceled`/`cancelled`: vuelve a `FREE/ACTIVA`.
- Desconocido: nunca concede acceso por defecto y cierra un entitlement PRO previamente concedido
  dejándolo en `PRO/VENCIDA`.

Estados relevantes de cada cobro autorizado:

- `approved`: activa `PRO/ACTIVA` únicamente si el preapproval vigente está `authorized`; con
  `pending`, `paused`, cancelado o desconocido se audita sin abrir acceso.
- `rejected`, `cancelled`/`canceled`, `refunded` o `charged_back`: deja `PRO/VENCIDA` y cierra
  los entitlements; un `approved` posterior para la misma factura puede reactivar la suscripción.

---

### 4.8 Dashboard — requiere token  (`/api/dashboard`)

#### `GET /api/dashboard`
Métricas del taller para la pantalla de inicio.
```json
{
  // un contador por CADA estado del enum (12 claves: INGRESADO, EN_DIAGNOSTICO, PRESUPUESTADO,
  // EN_PROCESO, ESPERANDO_REPUESTO, ESPERANDO_ADICIONAL, NO_REPARABLE, COMPLETADO,
  // LISTO_SIN_REPARAR, ENTREGADO, ABANDONADO, CANCELADO)
  "reparacionesPorEstado": { "INGRESADO": 3, "EN_PROCESO": 1, "COMPLETADO": 0, "ENTREGADO": 0, "...": 0 },
  "totalReparaciones": 4,
  "reparacionesEsteMes": 4,
  "equiposListos": 0,
  "articulosStockBajo": 1,
  "plan": "PRO",
  "estadoSuscripcion": "ACTIVA",
  "limiteReparacionesMes": null,
  "ultimasReparaciones": [ /* últimas 5 ReparacionResponse (con equipo+cliente y estado de pago) */ ]
}
```

---

### 4.9 Seguimiento público — SIN token  (`/api/seguimiento`)

#### `GET /api/seguimiento/{codigo}`
Consulta pública (sin login) del estado de una reparación por su `codigoSeguimiento`.
Pensado para que el cliente del taller siga su equipo. Datos mínimos, sin info sensible.
```json
{
  "codigo": "W45TME2L",
  "estado": "INGRESADO",
  "marca": "Apple",
  "modelo": "iPhone 13",
  "taller": "MVGR Reparaciones",
  "fechaIngreso": null,
  "fechaEstimadaEntrega": null
}
```
`404` si el código no existe. La URL pública que se comparte es `{APP_PUBLIC_URL}/seguimiento/{codigo}`
(la arma el backend en el link de WhatsApp). La respuesta incluye `presupuesto` (el último, o `null`).

**Aprobación del presupuesto por el cliente (público, sin token):**
- `POST /api/seguimiento/{codigo}/presupuesto/aprobar` → marca APROBADO el presupuesto pendiente.
- `POST /api/seguimiento/{codigo}/presupuesto/rechazar` → marca RECHAZADO.
- `400` si no hay un presupuesto PENDIENTE para responder.

---

### 4.10 Usuarios / Empleados — solo ADMIN  (`/api/usuarios`)

Gestión de los empleados del taller. **Todo el grupo requiere rol ADMIN** (un USER recibe `403`).
`ADMIN` representa al **titular único** del taller; no es un nivel asignable desde esta API.

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/api/usuarios` | CrearUsuario | `201` UsuarioResponse |
| GET    | `/api/usuarios` | — | `200` UsuarioResponse[] |
| GET    | `/api/usuarios/{id}` | — | `200` UsuarioResponse |
| PATCH  | `/api/usuarios/{id}` | `{ "active"? }` | `200` UsuarioResponse |

CrearUsuario: `{ "username", "email", "password", "role"? }`. Todo empleado se persiste como
`USER`. El campo `role` es transitorio por compatibilidad: puede omitirse o ser `USER`; enviar
`ADMIN` devuelve `400 EMPLEADO_DEBE_SER_USER` y no crea el usuario.
> **PRO**: el plan FREE permite **1 usuario** (el dueño). Agregar empleados requiere PRO → si no, `402`. Ver `funciones.empleadosMultiples` en §4.2.
UsuarioResponse: `{ id, username, email, role, active }`.

- El rol no se edita. Un cliente legacy puede repetir el rol actual como no-op; intentar promover un
  empleado o degradar al titular devuelve `400 ROL_USUARIO_INMUTABLE`.
- La base impide más de un `ADMIN` por taller. Todavía no existe transferencia de titularidad.
- Un usuario **desactivado** (`active:false`) no puede loguear (`401`) y sus tokens ya emitidos
  **dejan de funcionar al instante** (las requests pasan a dar `403`).
- Guarda: un ADMIN **no puede desactivarse a sí mismo** (`400`).
- `400` si el email ya está en uso.

---

### 4.11 Presupuestos  (`/api/reparaciones/{reparacionId}/presupuestos`) — requiere token

Presupuesto de una reparación, con ítems discriminados, validez y aprobación del cliente.

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/api/reparaciones/{id}/presupuestos` | PresupuestoRequest | `201` PresupuestoResponse |
| GET    | `/api/reparaciones/{id}/presupuestos` | — | `200` PresupuestoResponse[] (más nuevo primero) |
| POST   | `/api/reparaciones/{id}/presupuestos/{presupuestoId}/aprobar` | — | `200` PresupuestoResponse |
| POST   | `/api/reparaciones/{id}/presupuestos/{presupuestoId}/rechazar` | — | `200` PresupuestoResponse |
| POST   | `/api/reparaciones/{id}/presupuestos/{presupuestoId}/represupuestar` | PresupuestoRequest (opcional) | `201` PresupuestoResponse |

PresupuestoRequest:
```json
{
  "items": [
    { "descripcion": "Mano de obra", "cantidad": 1, "precioUnitario": 12000, "tipoItem": "MANO_DE_OBRA" },
    { "descripcion": "Pantalla", "cantidad": 1, "precioUnitario": 30000, "tipoItem": "REPUESTO", "calidad": "ALTERNATIVO" }
  ],
  "observaciones": "Demora 48hs",  // opcional
  "tipo": "ORIGINAL",              // opcional: ORIGINAL (default) | ADICIONAL
  "validezDias": 7                 // opcional: default 7
}
```
- `tipoItem`: `MANO_DE_OBRA` (default si se omite) | `REPUESTO`. `calidad` (solo repuestos, opcional): `ORIGINAL | ALTERNATIVO | USADO_REACONDICIONADO`.

PresupuestoResponse: `{ id, reparacionId, estado, tipo, items[], total, manoDeObraTotal, repuestosTotal, validezDias, validoHasta, vencido, observaciones, fechaRespuesta, createdAt }`
- `estado`: `PENDIENTE | APROBADO | RECHAZADO | VENCIDO`. **VENCIDO es derivado** (un PENDIENTE cuyo `validoHasta` ya pasó); `vencido: true` lo marca también como bool. `total` = Σ cantidad×precioUnitario; `manoDeObraTotal`/`repuestosTotal` lo discriminan.
- **Aprobar/rechazar**: lo puede hacer el **taller** (endpoints de arriba) o el **cliente** desde el link público (§4.9). Un presupuesto **vencido o ya respondido** → `400`.
- **Re-presupuestar**: clona el presupuesto (mismos ítems) en uno nuevo PENDIENTE con validez fresca; si mandás `items` en el body, usa esos (precios nuevos). Útil cuando venció.
- **Auto-estado de la reparación** (best-effort, respeta la máquina de estados): crear ORIGINAL → `PRESUPUESTADO`; crear ADICIONAL → `ESPERANDO_ADICIONAL`; aprobar → `EN_PROCESO`; rechazar original → `LISTO_SIN_REPARAR`. Si el salto no es legal desde el estado actual, no se fuerza (no rompe).

---

### 4.12 Inventario — requiere token · **PRO**  (`/api/inventario`)
> Función PRO: con plan FREE todos estos endpoints devuelven `402`. Ver `funciones.inventario` en §4.2.

Catálogo de artículos con stock.

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/api/inventario` | ArticuloRequest | `201` ArticuloResponse |
| PUT    | `/api/inventario/{id}` | ArticuloRequest | `200` ArticuloResponse |
| GET    | `/api/inventario/{id}` | — | `200` ArticuloResponse |
| GET    | `/api/inventario?q=&page=&size=` | — | `200` Page de ArticuloResponse |
| GET    | `/api/inventario/stock-bajo` | — | `200` ArticuloResponse[] (stock ≤ mínimo) |
| POST   | `/api/inventario/{id}/ajuste` | `{ "delta": 10, "motivo": "compra" }` | `200` ArticuloResponse |
| DELETE | `/api/inventario/{id}` | — | `204` (solo ADMIN) |

ArticuloRequest: `{ nombre, descripcion?, sku?, precio, costo?, stock?, stockMinimo? }`
ArticuloResponse: `{ id, nombre, descripcion, sku, precio, costo, stock, stockMinimo, activo, stockBajo }`
- El **stock no se cambia con PUT**: se mueve con `/ajuste` (delta + entrada / − salida; 400 si queda negativo).
- El stock también **baja automáticamente** al usar el artículo como repuesto en una reparación (§4.6) y se **repone** al borrar ese repuesto.
- `stockBajo:true` cuando `stock ≤ stockMinimo`; el dashboard trae el contador `articulosStockBajo`.

---

### 4.13 Cobros manuales, datos de cobro y resumen digital — requiere token · **PRO**

Esta función registra manualmente pagos que el taller recibió por fuera de OrdenFix. No inicia una
transferencia, no consulta cuentas bancarias o billeteras, no concilia acreditaciones y no genera un
comprobante fiscal. `referencia` es una nota externa informada por el taller, no una confirmación de
pago de Mercado Pago ni de otra entidad.

> Función PRO: con el entitlement `funciones.cobros:false`, todos estos endpoints devuelven `402`.
> ADMIN y USER pueden leer/registrar la operatoria; anular movimientos y modificar los datos públicos
> de cobro son acciones exclusivas de ADMIN.

#### Cobros de una reparación (`/api/reparaciones/{reparacionId}`)

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST | `/cobros` | CobroRequest | `201` CobroResponse |
| GET | `/cobros` | — | `200` CobrosReparacion |
| POST | `/cobros/{cobroId}/anulacion` | `{ "motivo": "..." }` | `200` CobroResponse (solo ADMIN) |
| DELETE | `/cobros/{cobroId}` | — | `204` (solo ADMIN; alias legado deprecado) |
| GET | `/resumen-digital` | — | `200` ResumenDigitalOrden |
| GET | `/recibo` | — | `200` ResumenDigitalOrden (alias legado deprecado) |

CobroRequest:

```json
{
  "monto": 20000.00,
  "metodo": "TRANSFERENCIA",
  "referencia": "Operación 123456",
  "observaciones": "Seña"
}
```

- `monto`: obligatorio, mayor que cero, hasta 8 enteros y 2 decimales.
- `metodo`: `EFECTIVO | TRANSFERENCIA | TARJETA | MERCADOPAGO | OTRO`.
- `referencia`: opcional, máximo 120; se recorta y un blanco se guarda como `null`.
- `observaciones`: opcional, máximo 255; es información interna y no aparece en el resumen digital.
- El alta toma un lock sobre la reparación. Si el monto supera el pendiente, responde `409` con
  `code:"COBRO_SUPERA_SALDO"` y `details` (`reparacionId`, `total`, `cobrado`, `monto`, `pendiente`).
- Editar la mano de obra o los repuestos tampoco puede aumentar un excedente: responde `409` con
  `code:"TOTAL_MENOR_QUE_COBRADO"`. Un registro histórico ya inconsistente sí puede corregirse en
  pasos que reduzcan su excedente.

CobroResponse:

```json
{
  "id": 91,
  "reparacionId": 42,
  "monto": 20000.00,
  "metodo": "TRANSFERENCIA",
  "referencia": "Operación 123456",
  "observaciones": "Seña",
  "fecha": "2026-08-23T15:10:00",
  "estado": "ACTIVO",
  "anuladoAt": null,
  "anuladoPorNombre": null,
  "motivoAnulacion": null
}
```

`GET /cobros` devuelve los movimientos activos y anulados, más nuevos primero. Los importes derivados
consideran **sólo activos**:

```json
{
  "total": 80000.00,
  "cobrado": 20000.00,
  "saldo": 60000.00,
  "excedente": 0,
  "requiereRevision": false,
  "pagado": false,
  "cobros": [ /* CobroResponse[] */ ]
}
```

`saldo = max(total - cobrado, 0)` y `excedente = max(cobrado - total, 0)`: nunca se representa un
saldo negativo. `requiereRevision:true` identifica datos históricos con cobros activos por encima del
total actual.

La anulación canónica requiere `motivo` no vacío (máximo 255), conserva el cobro original y completa
`estado:"ANULADO"`, `anuladoAt`, `anuladoPorNombre` y `motivoAnulacion`. Repetirla responde `409` con
`code:"COBRO_YA_ANULADO"`. El `DELETE` legado es idempotente, registra el motivo técnico
`"Anulación mediante endpoint legado"` y no debe usarse en integraciones nuevas.

#### Datos opcionales para recibir pagos (`/api/taller/datos-cobro`)

| Método | Ruta | Body / respuesta | Rol |
|--------|------|------------------|-----|
| GET | `/api/taller/datos-cobro` | `200` DatosCobro | ADMIN / USER |
| PUT | `/api/taller/datos-cobro` | DatosCobroRequest → `200` DatosCobro | ADMIN |
| GET | `/api/taller/datos-cobro/qr` | `200 image/png` | ADMIN / USER |
| PUT | `/api/taller/datos-cobro/qr` | `multipart/form-data`, parte `file` → `200` DatosCobro | ADMIN |
| DELETE | `/api/taller/datos-cobro/qr` | `200` DatosCobro | ADMIN |

```json
{
  "alias": "celexpress.mp",
  "titular": "CelExpress SRL",
  "entidad": "Mercado Pago",
  "mostrarEnResumen": true,
  "qrDisponible": true,
  "qrVersion": "8de3...sha256-de-64-caracteres...1af0"
}
```

El `PUT` de metadatos **reemplaza el recurso completo**: `alias` (máx. 120), `titular` (máx. 160) y
`entidad` (máx. 120) aceptan `null`; blanco también los limpia. `mostrarEnResumen` es obligatorio. El
QR es un subrecurso separado, por lo que este `PUT` no lo modifica.

El upload acepta bytes PNG o JPEG reales de hasta **1 MiB**, máximo **1024×1024** y **1 megapíxel**;
el backend inspecciona la imagen, la vuelve a codificar como PNG y recién entonces reemplaza la vigente.
Un archivo inválido no pisa el QR anterior. Errores estables: `400 code:"QR_COBRO_INVALIDO"`,
`413 code:"ARCHIVO_DEMASIADO_GRAN"` y, al leer un QR inexistente,
`404 code:"QR_COBRO_NO_CONFIGURADO"`.

`GET /qr` devuelve `Content-Type:image/png`, `Cache-Control:private, no-store` y un `ETag` basado en el
SHA-256. `qrVersion` es ese SHA-256 y permite invalidar el blob en memoria cuando cambia; nunca es la
imagen ni una URL pública. El QR y sus metadatos siempre se resuelven desde el tenant del JWT.

#### Resumen digital de la orden

`GET /api/reparaciones/{id}/resumen-digital` es el contrato canónico. El endpoint deprecado
`GET /api/reparaciones/{id}/recibo` devuelve **exactamente el mismo JSON** durante la transición; el
frontend nuevo no debe presentar acciones de impresión ni describirlo como recibo fiscal.

```json
{
  "numeroOrden": "ORD-2026-0042",
  "codigoSeguimiento": "W45TME2L",
  "fecha": "2026-08-20T10:30:00",
  "estado": "EN_PROCESO",
  "taller": { "nombre": "CelExpress", "telefono": "1133334444" },
  "cliente": { "nombre": "Ana", "apellido": "Pérez", "telefono": "1144445555" },
  "equipo": {
    "marca": "Apple",
    "modelo": "iPhone 13",
    "descripcionProblema": "No enciende"
  },
  "detalle": {
    "repuestos": [
      { "nombre": "Pantalla", "cantidad": 1, "precioUnitario": 50000, "subtotal": 50000 }
    ],
    "manoDeObra": 30000,
    "totalRepuestos": 50000
  },
  "importes": {
    "total": 80000,
    "cobrado": 20000,
    "saldo": 60000,
    "excedente": 0,
    "requiereRevision": false,
    "pagado": false
  },
  "pagos": [
    {
      "fecha": "2026-08-23T15:10:00",
      "monto": 20000,
      "metodo": "TRANSFERENCIA",
      "referencia": "Operación 123456"
    }
  ],
  "datosCobro": {
    "alias": "celexpress.mp",
    "titular": "CelExpress SRL",
    "entidad": "Mercado Pago",
    "qrDisponible": true,
    "qrVersion": "8de3...sha256-de-64-caracteres...1af0"
  },
  "documentoFiscal": false,
  "leyenda": "Documento informativo. No es factura ni comprobante fiscal y no reemplaza los emitidos por ARCA.",
  "reparacionId": 42
}
```

- `pagos` incluye sólo cobros activos, en orden cronológico, y deliberadamente no expone IDs,
  observaciones internas ni datos de anulación.
- `datosCobro` es `null` si no hay saldo pendiente, si `mostrarEnResumen:false` o si el taller no
  configuró ningún texto ni QR. Puede contener sólo QR o sólo campos de texto.
- Para compatibilidad temporal, el JSON también incluye aliases planos del contrato anterior:
  `tallerNombre`, `tallerTelefono`, `clienteNombre`, `clienteApellido`, `clienteTelefono`,
  `equipoMarca`, `equipoModelo`, `descripcionProblema`, `repuestos`, `manoDeObra`,
  `totalRepuestos`, `total`, `cobrado`, `saldo`, `excedente`, `requiereRevision` y `pagado`.
  Integraciones nuevas deben consumir la estructura anidada.
- `documentoFiscal` siempre es `false` y la `leyenda` debe mostrarse sin reinterpretarla como factura,
  comprobante de pago o recibo legal.

#### Cobros registrados por período (ruta técnica `/api/caja`)

| Método | Ruta | Resp |
|--------|------|------|
| GET | `/api/caja?desde=YYYY-MM-DD&hasta=YYYY-MM-DD` | `200` CajaResumen |

Sin params consulta **hoy**. Devuelve `{ desde, hasta, totalCobrado, cantidad, porMetodo: {
EFECTIVO, TRANSFERENCIA, TARJETA, MERCADOPAGO, OTRO }, cobros: CobroResponse[] }` y excluye anulados.
Representa sólo lo cargado manualmente en OrdenFix dentro del período inclusivo; no contempla egresos,
gastos, comisiones ni acreditaciones reales, y no es el saldo de una cuenta o billetera.

---

### 4.14 Exportar datos — solo ADMIN  (`/api/export`)

El dueño del taller se descarga **todos sus datos** en un Excel (disponible en todos los planes).

| Método | Ruta | Resp |
|--------|------|------|
| GET | `/api/export/excel` | `200` archivo `.xlsx` (binario) |

- Devuelve un `.xlsx` con 4 hojas: **Clientes**, **Órdenes** (estado, técnico, total/cobrado/saldo, estado de pago, garantía), **Cobros** y **Presupuestos**.
- Responde con `Content-Disposition: attachment; filename="ordenfix-export-YYYY-MM-DD.xlsx"`.
- En el front: pedirlo con `responseType: 'blob'` (Axios) y disparar la descarga:

```ts
const { data } = await api.get('/api/export/excel', { responseType: 'blob' });
const url = URL.createObjectURL(data);
const a = document.createElement('a');
a.href = url;
a.download = `ordenfix-export-${new Date().toISOString().slice(0, 10)}.xlsx`;
a.click();
URL.revokeObjectURL(url);
```

- Empleado con rol USER → `403`. Botón sugerido: "Exportar mis datos" en Configuración.

---

## 5. Formato de error (todos los endpoints)

```json
{
  "timestamp": "2026-06-16T22:02:21.43",
  "status": 402,
  "error": "Límite del plan alcanzado",
  "message": "Alcanzaste el límite de 25 reparaciones por mes del plan FREE...",
  "path": "/api/reparaciones"
}
```

`code` y `details` se omiten cuando el error no define un contrato de negocio específico. Para
decisiones de UI usá `status` + `code`; `message` es texto mostrable, no un identificador estable.
Por ejemplo, un conflicto de cobro agrega:

```json
{
  "timestamp": "2026-08-23T15:10:00",
  "status": 409,
  "error": "Conflicto de estado",
  "message": "El monto supera el pendiente de cobro.",
  "path": "/api/reparaciones/42/cobros",
  "code": "COBRO_SUPERA_SALDO",
  "details": {
    "reparacionId": 42,
    "total": 80000,
    "cobrado": 70000,
    "monto": 20000,
    "pendiente": 10000
  }
}
```

| Código | Significado | Qué hace el front |
|--------|-------------|-------------------|
| 400 | Validación / dato inválido (incl. teléfono duplicado al crear cliente) | Mostrar `message` en el form/toast |
| 401 | Login rechazado; también firma inválida del webhook, que el frontend no invoca | No crear sesión / volver a `/login` |
| 402 | **Límite del plan / suscripción no vigente** | Modal "Pasá a PRO" con el `message` |
| 403 | Token ausente/inválido/revocado, usuario desactivado o rol insuficiente | Validar sesión base; logout si falla, o "sin permisos" si la sesión sigue válida |
| 404 | No encontrado (o recurso de otro taller) | "No existe" |
| 409 | Conflicto: unicidad, transición ilegal, checkout MP o invariantes de cobro (`COBRO_SUPERA_SALDO`, `TOTAL_MENOR_QUE_COBRADO`, `COBRO_YA_ANULADO`) | Resolver por `code`, conservar el formulario y mostrar `message` |
| 413 | QR por encima de 1 MiB (`ARCHIVO_DEMASIADO_GRAN`) | Conservar el QR vigente y pedir una imagen menor |
| 428 | Falta una precondición resoluble, como aceptación legal | Resolver el gate indicado por `code`; no hacer logout ni reintentar en loop |
| 429 | Demasiadas solicitudes en login, registro, recuperación, seguimiento público o webhook MP | Mostrar espera, respetar `Retry-After` y no reintentar en loop |
| 500 | Error interno (mensaje genérico) | Toast genérico "Intentá más tarde" |
| 502 | Falló MP o están pausados los nuevos checkouts | Toast con `message`; el POST de checkout se puede reintentar |
| 503 | Servicio o contrato requerido temporalmente indisponible | Bloquear la acción dependiente, respetar `Retry-After` si existe y ofrecer reintento |

Los endpoints limitados —login, registro, recuperación/verificación, seguimiento, webhook y los GET
legales públicos cuando se implementen— incluyen `X-RateLimit-Limit` y `X-RateLimit-Remaining`; al
rechazar agregan `Retry-After` en segundos. El frontend no llama al webhook, pero debe respetar estos
headers en los otros endpoints públicos.

---

## 6. Freemium / límites de plan

- `estado: "TRIAL"` (aunque `plan: "FREE"`): reparaciones **ilimitadas** + funciones PRO completas.
- `plan: "FREE"` + `estado: "ACTIVA"`: tope de **25 reparaciones por mes** (configurable por env
  `FREE_MAX_REPARACIONES`) y sin inventario, funciones de cobros ni multi-empleado.
- `plan: "PRO"` + `estado: "ACTIVA"`: reparaciones **ilimitadas** + funciones PRO.
- **Cómo cuenta:** suma 1 por cada reparación **creada** (`POST /api/reparaciones` o `/ingreso-rapido`).
  - **Borrar una reparación NO baja el contador** (no se puede esquivar el límite).
  - El contador **se reinicia el día 1 de cada mes** (mes calendario).
- Suscripción `VENCIDA`/`CANCELADA`: bloquea la creación de reparaciones y no concede funciones PRO.
- Al superar el tope, crear una reparación devuelve **`402`** con un `message` accionable.
- El front lee `GET /api/suscripcion` → `reparacionesEsteMes` / `limiteReparacionesMes` (null = ilimitado)
  y `funciones` como fuente de verdad para mostrar consumo/permisos y el modal de upgrade ante un 402.
  No deduzcas los entitlements solo desde `plan`, porque durante TRIAL el plan puede ser FREE.
- Al vencer el TRIAL, el backend pasa la suscripción a `FREE/ACTIVA`; recién entonces aplica el límite
  mensual y se cierran las funciones PRO.

**Flujo de upgrade a PRO:**
1. Ante el 402 (o desde la pantalla de Plan), el usuario toca "Pasar a PRO".
2. `POST /api/pagos/suscripcion` → tomás `initPoint` y hacés `window.location.href = initPoint`.
3. El usuario paga en MercadoPago y vuelve a `MP_BACK_URL` (`/suscripcion/resultado`).
4. En esa página, re-consultás `GET /api/suscripcion` con reintentos acotados hasta ver
   `plan: "PRO"` + `estado: "ACTIVA"`. Si todavía no se confirmó, mostrás "Pago pendiente" y
   permitís volver a consultar; `plan: "FREE"` durante TRIAL no significa que el pago haya fallado.

---

## 7. Setup recomendado (React + Vite + TS + Axios)

`src/api/client.ts`:
```ts
import axios from "axios";

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL ?? "http://localhost:8080",
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem("token");
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem("token");
      window.location.href = "/login";
    }
    // Un 403 requiere contexto: puede ser rol insuficiente o sesión revocada.
    // Validá la sesión con GET /api/suscripcion antes de desloguear globalmente.
    // Para 429, respetá Number(err.response.headers["retry-after"]).
    // err.response.data.message trae el mensaje del backend para toasts
    return Promise.reject(err);
  }
);
```

`src/api/types.ts`:
```ts
export type Plan = "FREE" | "PRO";
export type EstadoSuscripcion = "TRIAL" | "ACTIVA" | "VENCIDA" | "CANCELADA";
export type EstadoReparacion =
  | "INGRESADO" | "EN_DIAGNOSTICO" | "PRESUPUESTADO" | "EN_PROCESO"
  | "ESPERANDO_REPUESTO" | "ESPERANDO_ADICIONAL" | "NO_REPARABLE"
  | "COMPLETADO" | "LISTO_SIN_REPARAR" | "ENTREGADO" | "ABANDONADO" | "CANCELADO";
export type EstadoPago = "SIN_COBRAR" | "PARCIAL" | "PAGADO";
export type CuentaVinculada = "NINGUNA" | "ICLOUD" | "GOOGLE" | "OTRA";
export type MomentoFoto = "INGRESO" | "POST_REPARACION";
export interface Foto { url: string; momento: MomentoFoto; }
export type UserRole = "ADMIN" | "USER";

export interface AuthResponse { token: string; type: string; email: string; emailVerificado: boolean; }
export interface Perfil {
  usuario: { id: number; username: string; email: string; role: UserRole };
  taller: { id: number; nombre: string; telefono: string | null };
}
export interface Suscripcion {
  plan: Plan; estado: EstadoSuscripcion;
  fechaInicio: string | null; fechaFinTrial: string | null; proximoCobro: string | null;
  reparacionesEsteMes: number; limiteReparacionesMes: number | null;
  funciones: { inventario: boolean; cobros: boolean; empleadosMultiples: boolean };
}
export interface Cliente {
  id: number; nombre: string; apellido: string; telefono: string; email: string | null; direccion: string | null;
  equiposCount: number; reparacionesCount: number; ultimaVisita: string | null;
}
export interface Equipo {
  id: number; marca: string; modelo: string; imei: string | null; color: string | null; descripcion: string | null;
  clienteId: number; clienteNombre: string; clienteApellido: string; clienteTelefono: string; reparacionesCount: number;
}
export interface Reparacion {
  id: number; equipoId: number; equipoMarca: string; equipoModelo: string;
  clienteId: number; clienteNombre: string; clienteApellido: string; clienteTelefono: string;
  descripcionProblema: string; estado: EstadoReparacion;
  precioEstimado: number | null; precioFinal: number | null;
  fechaIngreso: string | null; fechaEstimadaEntrega: string | null; fechaEntrega: string | null;
  codigoSeguimiento: string | null; numeroOrden: string | null;
  totalRepuestos: number; total: number;
  cobrado: number; saldo: number; excedente: number; requiereRevision: boolean; estadoPago: EstadoPago;
  mojado: boolean; trabajoEnPlaca: boolean; noTesteableAlIngreso: boolean;
  tieneBloqueoPantalla: boolean; tieneCuentaVinculada: CuentaVinculada;
  clienteConoceCredenciales: boolean; riesgoCuentaSinCredenciales: boolean;
  accesorios: string | null; condicionesIngreso: string | null; observaciones: string | null;
  tecnicoId: number | null; tecnicoNombre: string | null;
  fotos: Foto[]; fechaConformidadEntrega: string | null;
  garantiaDias: number | null; garantiaInicio: string | null; garantiaFin: string | null;
  garantiaCondiciones: string | null; garantiaVigente: boolean;
  esGarantia: boolean; reparacionOrigenId: number | null;
}
// Solo GET /api/reparaciones/{id}; nunca usar este tipo para listados, altas, PUT o PATCH.
export interface ReparacionDetalle extends Reparacion {
  patronDesbloqueo: string | null;
  pinDesbloqueo: string | null;
}
export interface Repuesto { id: number; nombre: string; descripcion: string | null; precio: number; reparacionId: number | null; reparacionEquipo: string | null; articuloId: number | null; cantidad: number; }
export type EstadoPresupuesto = "PENDIENTE" | "APROBADO" | "RECHAZADO" | "VENCIDO";
export type TipoPresupuesto = "ORIGINAL" | "ADICIONAL";
export type TipoItemPresupuesto = "MANO_DE_OBRA" | "REPUESTO";
export type CalidadRepuesto = "ORIGINAL" | "ALTERNATIVO" | "USADO_REACONDICIONADO";
export interface ItemPresupuesto {
  descripcion: string; cantidad: number; precioUnitario: number;
  tipoItem: TipoItemPresupuesto; calidad: CalidadRepuesto | null;
}
export interface Presupuesto {
  id: number; reparacionId: number; estado: EstadoPresupuesto; tipo: TipoPresupuesto;
  items: ItemPresupuesto[]; total: number; manoDeObraTotal: number; repuestosTotal: number;
  validezDias: number; validoHasta: string | null; vencido: boolean;
  observaciones: string | null; fechaRespuesta: string | null; createdAt: string;
}
export interface CheckoutResponse { preapprovalId: string; initPoint: string; }
export type MetodoPago = "EFECTIVO" | "TRANSFERENCIA" | "TARJETA" | "MERCADOPAGO" | "OTRO";
export type EstadoCobro = "ACTIVO" | "ANULADO";
export interface CobroRequest {
  monto: number; metodo: MetodoPago; referencia?: string | null; observaciones?: string | null;
}
export interface Cobro {
  id: number; reparacionId: number; monto: number; metodo: MetodoPago;
  referencia: string | null; observaciones: string | null; fecha: string | null;
  estado: EstadoCobro; anuladoAt: string | null; anuladoPorNombre: string | null;
  motivoAnulacion: string | null;
}
export interface CobrosReparacion {
  total: number; cobrado: number; saldo: number; excedente: number;
  requiereRevision: boolean; pagado: boolean; cobros: Cobro[];
}
export interface DatosCobro {
  alias: string | null; titular: string | null; entidad: string | null;
  mostrarEnResumen: boolean; qrDisponible: boolean; qrVersion: string | null;
}
export interface DatosCobroRequest {
  alias?: string | null; titular?: string | null; entidad?: string | null;
  mostrarEnResumen: boolean;
}
export interface ResumenDigitalOrden {
  numeroOrden: string | null; codigoSeguimiento: string | null; fecha: string | null;
  estado: EstadoReparacion; reparacionId: number;
  taller: { nombre: string; telefono: string | null };
  cliente: { nombre: string; apellido: string; telefono: string };
  equipo: { marca: string; modelo: string; descripcionProblema: string };
  detalle: {
    repuestos: { nombre: string; cantidad: number; precioUnitario: number; subtotal: number }[];
    manoDeObra: number; totalRepuestos: number;
  };
  importes: {
    total: number; cobrado: number; saldo: number; excedente: number;
    requiereRevision: boolean; pagado: boolean;
  };
  pagos: { fecha: string | null; monto: number; metodo: MetodoPago; referencia: string | null }[];
  datosCobro: {
    alias: string | null; titular: string | null; entidad: string | null;
    qrDisponible: boolean; qrVersion: string | null;
  } | null;
  documentoFiscal: false;
  leyenda: string;
}
export interface CajaResumen {
  desde: string; hasta: string; totalCobrado: number; cantidad: number;
  porMetodo: Record<MetodoPago, number>; cobros: Cobro[];
}
// Respuesta paginada genérica
export interface Page<T> { content: T[]; page: { size: number; number: number; totalElements: number; totalPages: number }; }
```

Ejemplos:
```ts
// login
const { data } = await api.post<AuthResponse>("/api/auth/login", { email, password });
localStorage.setItem("token", data.token);

// cambiar estado de una reparación
await api.patch(`/api/reparaciones/${id}/estado`, { estado: "EN_PROCESO" });

// detalle autenticado: único response que puede incluir PIN/patrón
const { data: detalle } = await api.get<ReparacionDetalle>(`/api/reparaciones/${id}`);

// listar por estado
const { data } = await api.get<Reparacion[]>("/api/reparaciones/estado", { params: { estado: "EN_PROCESO" } });

// iniciar upgrade a PRO (redirige a MercadoPago)
const { data } = await api.post<CheckoutResponse>("/api/pagos/suscripcion");
window.location.href = data.initPoint;
```

---

## 8. Pantallas mínimas del frontend

1. **Login** y **Registro** (onboarding del taller).
2. **Layout protegido** (sidebar: Clientes, Equipos, Reparaciones, Repuestos, Plan) con logout. Carga
   `GET /api/perfil` y muestra el nombre del taller separado del nombre del usuario.
3. **Clientes**: tabla + búsqueda + ABM; ficha con sus equipos.
4. **Equipos**: tabla + ABM (selector de cliente); ver reparaciones del equipo.
5. **Reparaciones** (pantalla estrella): tablero/lista por estado, ABM, cambio rápido de estado,
   detalle con repuestos y total (suma de precios). PIN/patrón se muestran únicamente en esta ficha
   autenticada; no los copies al estado de tablas, tarjetas ni búsquedas.
   - **Carga rápida** (`POST /api/reparaciones/ingreso-rapido`): un formulario corto (nombre + teléfono
     del cliente, marca + modelo del equipo, problema) que crea todo de una. Con el `clienteId`/`equipoId`
     que devuelve, ofrecé "completar datos" para ir a las pantallas de Cliente/Equipo.
6. **Repuestos**: ABM, asociación a reparación.
7. **Plan**: muestra `GET /api/suscripcion` (consumo + estado), comparativa FREE/PRO, botón
   "Pasar a PRO" (`POST /api/pagos/suscripcion` → redirige a MercadoPago), y maneja el 402. Durante
   TRIAL presenta las capacidades PRO activas aunque el campo `plan` sea FREE.
8. **Resultado de pago** (`/suscripcion/resultado`): página de retorno de MercadoPago que
   re-consulta `GET /api/suscripcion` hasta confirmar el plan PRO; un checkout `pending` no equivale
   a pago aprobado.
9. **Cobros** (PRO): historial manual por orden y período, anulación con motivo para ADMIN,
   configuración opcional de alias/QR y resumen digital no fiscal. No mostrar saldo de cuenta ni
   ofrecer impresión del alias legado `/recibo`.

---

## 9. Estado actual del backend y roadmap

**Listo y funcionando:**
- Auth (registro + login por email), JWT con tenant.
- **Recuperación de contraseña** por email y **verificación de email** suave (§4.1) — emails vía Resend.
- Multi-tenancy con aislamiento total por taller.
- CRUD de Clientes, Equipos, Reparaciones, Repuestos.
- Suscripciones freemium (FREE/PRO) con límite mensual y gating 402.
- **MercadoPago**: checkout de suscripción PRO (`POST /api/pagos/suscripcion`) + **cancelación**
  (`/cancelar`) + webhook con **firma validada**, inbox idempotente, reconciliación y validación de
  collector/aplicación/monto. *(Requiere la configuración de §10.)*
- **Carga rápida** de reparación (§4.5), **total** de reparación (mano de obra + repuestos).
- **Máquina de estados** (12 estados, transición ilegal → 409) y **estado de pago** derivado (cobrado/saldo/estadoPago).
- **Orden de trabajo ampliada**: checklist de ingreso (patrón/PIN cifrados y visibles solo en detalle
  autenticado, accesorios, condiciones), técnico asignado, observaciones internas y **fotos con momento**
  (INGRESO/POST_REPARACION).
- **Ingreso enriquecido**: flags de riesgo, bloqueo de cuenta (iCloud/FRP) con bandera roja, y `numeroOrden` correlativo por taller.
- **Conformidad de entrega** (sellada al ENTREGADO) y **garantía** (default 90 días) con **reclamo en garantía** que no consume cupo.
- **Paginación + búsqueda** en todos los listados (§4.2.bis).
- **Roles ADMIN/USER** (borrados y suscripción solo ADMIN) y **gestión de empleados** (§4.10).
- **Dashboard** (§4.8), **seguimiento público** + **link de WhatsApp** (§4.9).
- **Presupuestos** con aprobación del cliente desde el link público (§4.11).
- **Inventario** con stock, ajustes, descuento automático y aviso de stock bajo (§4.12).
- **Cobros manuales y resumen digital** (§4.13): referencia, invariantes de saldo, excedentes legacy,
  anulación auditable, datos/QR del taller y documento informativo no fiscal.
- **Exportación a Excel** (§4.14): el ADMIN descarga todos los datos del taller en un `.xlsx`.
- **Gating por plan**: inventario, cobros manuales/datos de cobro y multi-empleado son PRO
  (402 + mapa `funciones` en §4.2). Perfil y dashboard son FREE.
- **Operación legal interna (V27/V28)**: schema v1, persistencia V27 y CLI aisladas para
  `validate`, `dry-run`, `import`, promoción, reemplazo, retiro y readiness editorial. El import
  sella versiones nuevas en `BORRADOR`; los siete comandos editoriales operan después sin exponer
  HTTP. V28 agrega materialización interna de una revisión de conjuntos completos, procedencia y
  replay. El bloque 13 agrega lectura HTTP de catálogo/documento exacto, revisión, ETag y políticas con
  rol restringido, flag apagado y gate integral acreditado. Quedan requisitos HTTP, aceptación de
  aplicación, idempotencia HTTP, respuestas de escritura `409/428/503`, enforcement, contenido
  definitivo, staging y deploy; `BACKEND-HANDOFF 1` y la Tarea 3 siguen cerrados.
- **Salud** (`/actuator/health`) y **tests** (aislamiento de tenant, 402, firma de webhook).
- Spring Boot 4 / Java 21, migraciones con Flyway.

**Próximo (ideas a futuro):**
- **WhatsApp Business API** (envío automático real; hoy es link wa.me manual).
- Cargo por diagnóstico y seña/anticipo; recordatorio de retiro + ABANDONADO automático.
- Inventario fino (catálogo por modelo/SKU, orden a proveedor con ETA) y **reportes** (tiempo de ciclo, margen).
- Login con Google; auditoría de eventos.

---

## 10. Configuración del backend para MercadoPago (no es del front)

La integración viene **desactivada por defecto** (`mercadopago.enabled=false`): el backend arranca igual
sin tocar nada. Para operar pagos en vivo, definí estas variables **solo en el entorno/secret manager**
(nunca en el frontend ni versionadas):

| Variable | Default | Descripción |
|----------|---------|-------------|
| `MP_ENABLED` | `false` | `true` para activar las llamadas a MercadoPago |
| `MP_CHECKOUT_ENABLED` | `false` | Habilitación deliberada: en `false` frena nuevos checkouts sin apagar webhooks, cancelación ni reconciliación |
| `MP_ACCESS_TOKEN` | *(vacío)* | Access Token de MercadoPago (las de prueba también empiezan con `APP_USR-`) |
| `MP_WEBHOOK_SECRET` | *(vacío)* | Clave secreta del webhook (panel MP → Webhooks). **Obligatoria si `MP_ENABLED=true`**: con MP activo y sin secreto, el webhook rechaza todo (fail-closed). |
| `MP_COLLECTOR_ID` | *(vacío)* | ID esperado de la cuenta cobradora; obligatorio con `MP_ENABLED=true` y validado en cada checkout |
| `MP_APPLICATION_ID` | *(vacío)* | ID esperado de la aplicación MP; obligatorio con `MP_ENABLED=true` y validado en cada checkout |
| `MP_AMOUNT` | `24900` | Monto mensual de la suscripción PRO (ARS 24.900) |
| `MP_CURRENCY` | `ARS` | Moneda |
| `MP_REASON` | `OrdenFix PRO - Suscripción mensual` | Texto que ve el usuario en el checkout |
| `MP_BACK_URL` | `http://localhost:5173/suscripcion/resultado` con MP apagado | URL del front a la que vuelve el usuario; debe ser HTTPS cuando `MP_ENABLED=true` |

Con `MP_ENABLED=true`, el backend valida al arrancar que Access Token, webhook secret,
`MP_COLLECTOR_ID` y `MP_APPLICATION_ID` estén presentes y que el monto sea positivo. Collector y
application ID son datos de confianza del backend: el frontend no debe recibirlos ni enviarlos.
También exige una `MP_BACK_URL` HTTPS y la API oficial `https://api.mercadopago.com`.

Además, en el panel de MercadoPago hay que configurar la **URL de notificaciones (webhook)** apuntando a
`https://<tu-backend>/api/pagos/webhook` (en local se usa un túnel tipo ngrok). El endpoint `/api/pagos/webhook`
es público pero **valida la firma `x-signature`** con `MP_WEBHOOK_SECRET`; el resto de `/api/pagos/**` exige token.
Suscribí `subscription_preapproval`, `subscription_authorized_payment` y `payment`.

**Health check (para el deploy):** `GET /actuator/health` es público y devuelve `{"status":"UP"}`. Útil para
configurar los probes de readiness/liveness en Render/Railway. No expone otros endpoints de Actuator.

> Diseñá la navegación contemplando estas secciones futuras (Dashboard, Cobros, Inventario, Reportes)
> para no rehacer el layout más adelante.

---

## 11. Checklist de integración y validación del frontend

Usá esta lista para marcar qué está integrado en el repo del frontend.

### Entorno y sesión

- [ ] `VITE_API_URL=http://localhost:8080` en desarrollo y URL HTTPS correcta en producción.
- [ ] Limpiar cualquier token de producción guardado antes de probar contra localhost.
- [ ] Login/registro guardan `token`, `email` y `emailVerificado`; todas las rutas privadas envían Bearer.
- [ ] Los guards entienden `ROLE_ADMIN` / `ROLE_USER` o normalizan el prefijo de manera consistente.
- [ ] La carga inicial valida sesión con `GET /api/suscripcion` y distingue sesión inválida de falta de rol.
- [ ] Login rechazado (`401`) y ruta protegida con sesión inválida/rol insuficiente (`403`) tienen
      tratamiento explícito; `402`, `409`, `429` y `502` también, y `429` respeta `Retry-After`.
- [ ] Existen `/reset-password?token=...` y `/verificar-email?token=...`.

### Datos y navegación

- [ ] Listados consumen `data.content` + `data.page`; no esperan un array directo.
- [ ] Búsqueda, filtros, orden y paginación conservan el estado en la URL o store de la pantalla.
- [ ] Las bajas esperan el `204`; ante `409`/`400` mantienen la fila y muestran `message`.
- [ ] El dashboard muestra por separado estado de reparación y estado/saldo de pago.
- [ ] Descarga Excel usa respuesta `blob` y respeta el nombre del archivo.

### Perfil y cobros manuales

- [ ] La identidad visual toma `taller.nombre` de `GET /api/perfil`; no lo reemplaza por
      `usuario.username` ni confía en IDs enviados por el navegador.
- [ ] USER puede consultar/registrar cobros y ver datos de pago; sólo ADMIN ve anulación y edición de
      alias/QR, y un `403` no se interpreta como pérdida automática de sesión.
- [ ] El alta maneja `COBRO_SUPERA_SALDO`; las ediciones de orden/repuestos manejan
      `TOTAL_MENOR_QUE_COBRADO`; la UI nunca representa `saldo` como negativo.
- [ ] Anular usa `POST /cobros/{id}/anulacion` con motivo; no borra la fila ni usa el alias DELETE.
- [ ] El resumen consume `/resumen-digital`, muestra la leyenda no fiscal, omite impresión y no expone
      observaciones internas. `/recibo` se prueba sólo como compatibilidad temporal.
- [ ] Alias/QR se muestran en el resumen sólo si `datosCobro` no es `null`; el QR se descarga como blob
      autenticado y se renueva cuando cambia `qrVersion`.
- [ ] La vista por período aclara que son registros manuales activos, sin egresos ni conciliación real.

### PIN, patrón y reparación

- [ ] Listados, tarjetas, dashboard y tracking usan `Reparacion`, nunca `ReparacionDetalle`.
- [ ] Solo la ficha autenticada llama `GET /api/reparaciones/{id}` y puede mostrar PIN/patrón.
- [ ] PIN/patrón no se guardan en localStorage, analytics, logs, caché global ni mensajes de error.
- [ ] En PUT, campo omitido conserva; `""` borra; después de POST/PUT/PATCH se refresca el detalle.
- [ ] Al pasar a `ENTREGADO`, la UI elimina inmediatamente cualquier copia en memoria de esos valores.
- [ ] La UI ofrece únicamente las transiciones legales y maneja igualmente un `409` del backend.

### Plan y Mercado Pago

- [ ] Capacidades se toman de `funciones` y `limiteReparacionesMes`, no solo de `plan`.
- [ ] TRIAL se muestra como experiencia PRO ilimitada aunque `plan` sea `FREE`.
- [ ] Solo ADMIN ve iniciar/cancelar suscripción; el front nunca envía monto, moneda ni IDs de MP.
- [ ] Checkout redirige exclusivamente al `initPoint` recibido y maneja `409`/`502` sin duplicarlo.
- [ ] `/suscripcion/resultado` reconsulta el plan con reintentos acotados y contempla estado pendiente.
- [ ] Sandbox valida approved, rejected, paused, canceled, refunded, charged_back y eventos duplicados.
- [ ] Con MP activo, retorno y webhook usan URLs HTTPS públicas aunque el API local siga en HTTP;
      secretos de MP nunca existen en variables `VITE_*`.

### Legal versionado (cuando las APIs del backend estén implementadas)

Estado: contrato de diseño; `BACKEND-HANDOFF 1` continúa cerrado y esta lista no describe runtime
HTTP disponible. V28 sólo implementa el núcleo y la persistencia internos.

- [ ] El registro obtiene `REGISTRO/es-AR`, presenta todo el set y envía revisión, IDs, actos y
      digests con un `Idempotency-Key` estable por intento lógico.
- [ ] La UI conserva y reenvía una única `requiredSetRevision` opaca, también con `requisitos: []`;
      no la recalcula desde pendientes ni envía un mapa de revisiones por contexto.
- [ ] `409 DOCUMENTOS_LEGALES_DESACTUALIZADOS` conserva los campos no legales, reemplaza el set y
      solicita revisar nuevamente sólo las aceptaciones afectadas.
- [ ] `428 ACEPTACION_LEGAL_REQUERIDA` monta un gate resoluble y nunca provoca logout o retry global.
- [ ] Catálogo y requisitos públicos delegan ETag/304 al navegador; el cliente no crea manualmente
      un `If-None-Match` con Axios.
- [ ] Requisitos y aceptaciones autenticados usan caché de memoria ligada a sesión con
      `staleTime: 0` y se purgan antes de cambiar usuario o tenant.
- [ ] El Centro de Confianza muestra `VIGENTE`, `REEMPLAZADA` y `RETIRADA`; cada aceptación enlaza la
      versión UUID exacta y no usa borradores locales como fallback.
- [ ] ADMIN continúa viajando como `ROLE_ADMIN`; `ADMIN_TITULAR` no se espera como claim del JWT.

### Aceptación mínima

- [ ] Registro, login, logout, expiración/revocación y usuario desactivado.
- [ ] CRUD y búsquedas de clientes, equipos, reparaciones y repuestos con aislamiento por taller.
- [ ] Ingreso rápido, máquina de estados, presupuestos públicos, garantía y conformidad de entrega.
- [ ] FREE/ACTIVA, TRIAL, PRO/ACTIVA y PRO/VENCIDA con sus capacidades correctas.
- [ ] Tracking público no expone observaciones internas, PIN, patrón ni datos de otro cliente.
- [ ] Cobros activos/anulados, excedente legacy, permisos USER/ADMIN, QR por tenant y ambos endpoints
      del resumen digital tienen cobertura de integración.
- [ ] Flujo MP completo probado en sandbox antes de habilitar `MP_CHECKOUT_ENABLED` en producción.
