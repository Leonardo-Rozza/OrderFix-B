# OrdenFix — Backend

SaaS **multi-taller** para la gestión integral de talleres de reparación de celulares/dispositivos:
clientes, equipos, órdenes de reparación, presupuestos, repuestos, inventario con stock, cobros/caja,
seguimiento público para el cliente y suscripción **freemium (FREE/PRO)** con cobro por MercadoPago.

> 📚 Documentación relacionada:
> - **`FRONTEND_INTEGRATION.md`** — contrato completo de la API (request/response exactos, tipos TS).
> - **`DEPLOY.md`** — guía de despliegue y variables de entorno.

---

## Stack

- **Java 21**, **Spring Boot 4.0.6**
- **Spring Security 7** + **JWT** (auth0 java-jwt, HMAC-SHA256) — stateless
- **Spring Data JPA** / Hibernate 7 sobre **PostgreSQL**
- **Flyway** (dueño del esquema; Hibernate solo valida)
- **MapStruct** (DTO ↔ entidad) + **Lombok**
- **MercadoPago** (suscripción PRO vía preapproval, con webhook firmado) — vía `RestClient`
- **springdoc-openapi** (Swagger) y **Spring Boot Actuator** (`/actuator/health`)
- Build: Maven wrapper (`./mvnw`)

---

## Conceptos clave (cómo funciona)

### Multi-tenancy (aislamiento por taller)
Cada cuenta es un **Taller** (tenant). El `tallerId` viaja dentro del JWT; un filtro lo deja en un
`TenantContext` por request y **todas las queries filtran por taller**. Un taller nunca ve ni toca
datos de otro (hay tests que lo garantizan). El frontend nunca manda `tallerId`.

### Autenticación
- `POST /api/auth/register` crea taller + usuario admin + suscripción en TRIAL y devuelve un JWT.
- `POST /api/auth/login` devuelve el JWT (`sub`=email, `role`, `tallerId`, `exp`; 24 h por defecto).
- Header en cada request: `Authorization: Bearer <token>`.
- Roles: **ADMIN** (dueño) y **USER** (empleado). Operaciones sensibles (borrados, suscripción,
  gestión de usuarios) son solo ADMIN vía `@PreAuthorize`.

### Freemium (FREE vs PRO)
- **FREE**: hasta **25 reparaciones/mes** (configurable por `FREE_MAX_REPARACIONES`).
  El consumo se lleva con un **contador mensual** en la suscripción que **no baja al borrar** y se
  **reinicia el día 1**. Superar el tope → `402`.
- **PRO**: reparaciones ilimitadas + funciones exclusivas: **inventario**, **cobros/caja/recibo** y
  **más de 1 empleado**. Al usarlas sin PRO → `402`.
- `GET /api/suscripcion` expone el plan, el consumo y un mapa `funciones` para que el front
  habilite/oculte secciones.

### Suscripción PRO (MercadoPago)
- `POST /api/pagos/suscripcion` crea un preapproval y devuelve el `initPoint` (el front redirige).
- `POST /api/pagos/webhook` (público) recibe las notificaciones, **valida la firma HMAC** y actualiza
  plan/estado automáticamente. `POST /api/pagos/suscripcion/cancelar` baja a FREE.
- Desactivado por defecto; se activa con `MP_ENABLED`, `MP_ACCESS_TOKEN`, `MP_WEBHOOK_SECRET`.

### Seguimiento público
Cada reparación tiene un **código** público. `GET /api/seguimiento/{codigo}` (sin login) muestra el
estado al cliente y le permite **aprobar/rechazar el presupuesto**. También hay un generador de link
de **WhatsApp** para avisar al cliente.

### Listados (paginados, denormalizados)
Los listados devuelven una **página** (`{ content, page }`) con `?q=`, `?page=`, `?size=`, `?sort=`.
Vienen **autocontenidos**: p. ej. cada reparación trae equipo + cliente; cada equipo/cliente trae sus
contadores. Sin N+1 (join fetch + agregados por página).

---

## Modelo de dominio

```
Taller (cuenta / tenant)
 ├── Usuarios (ADMIN / USER, login por email)
 ├── Suscripción (FREE|PRO · TRIAL|ACTIVA|VENCIDA|CANCELADA · contador mensual)
 ├── Clientes
 │    └── Equipos
 │         └── Reparaciones (estado + estado de pago, nº de orden, orden de trabajo, flags de riesgo, técnico, fotos, código de seguimiento)
 │              ├── Repuestos (opcionalmente ligados a un Artículo de inventario)
 │              ├── Presupuestos (ítems, estado, aprobación del cliente)
 │              └── Cobros (pagos parciales / total)
 └── Inventario: Artículos (stock, stock mínimo)
```

**Enums:** `EstadoReparacion` (INGRESADO, EN_DIAGNOSTICO, PRESUPUESTADO, EN_PROCESO, ESPERANDO_REPUESTO,
ESPERANDO_ADICIONAL, NO_REPARABLE, COMPLETADO, LISTO_SIN_REPARAR, ENTREGADO, ABANDONADO, CANCELADO — con
**máquina de transiciones**: un salto ilegal devuelve `409`) · `EstadoPago` (SIN_COBRAR, PARCIAL, PAGADO —
**derivado** de cobrado vs total, no se almacena) ·
`PlanType` (FREE, PRO) · `EstadoSuscripcion` (TRIAL, ACTIVA, VENCIDA, CANCELADA) ·
`EstadoPresupuesto` (PENDIENTE, APROBADO, RECHAZADO, VENCIDO — derivado) · `TipoPresupuesto` (ORIGINAL,
ADICIONAL) · `TipoItemPresupuesto` (MANO_DE_OBRA, REPUESTO) · `CalidadRepuesto` (ORIGINAL, ALTERNATIVO,
USADO_REACONDICIONADO) · `MetodoPago` (EFECTIVO, TRANSFERENCIA, TARJETA,
MERCADOPAGO, OTRO) · `UserRole` (ADMIN, USER) · `CuentaVinculada` (NINGUNA, ICLOUD, GOOGLE, OTRA — bloqueo de cuenta del equipo) ·
`MomentoFoto` (INGRESO, POST_REPARACION).

---

## API (resumen)

Base URL local: `http://localhost:8080`. Detalle de cada request/response en `FRONTEND_INTEGRATION.md`.
**PRO** = requiere plan PRO (si no, 402). **ADMIN** = requiere rol ADMIN (si no, 403).

| Área | Endpoints | Notas |
|------|-----------|-------|
| **Auth** (público) | `POST /api/auth/register` · `POST /api/auth/login` | Devuelven `{ token, type, email }` |
| **Suscripción** | `GET /api/suscripcion` | Plan, consumo del mes y mapa `funciones` |
| **Clientes** | `POST` · `PUT/{id}` · `GET/{id}` · `GET` (paginado `?q=`) · `DELETE/{id}` (ADMIN) | Item con `equiposCount`, `reparacionesCount`, `ultimaVisita` |
| **Equipos** | `POST` · `PUT/{id}` · `GET/{id}` · `GET` (paginado) · `GET /cliente/{id}` · `DELETE/{id}` (ADMIN) | Item con cliente + `reparacionesCount` |
| **Reparaciones** | `POST` · `POST /ingreso-rapido` · `PUT/{id}` · `PATCH /{id}/estado` · `GET/{id}` · `GET` (`?q=&estado=&page=`) · `GET /equipo/{id}` · `GET /{id}/whatsapp` · `DELETE/{id}` (ADMIN) | `ingreso-rapido` crea cliente+equipo+reparación de una. Item denormalizado (equipo+cliente). Orden de trabajo ampliada (patrón/PIN, accesorios, técnico, fotos). |
| **Presupuestos** | `POST /api/reparaciones/{id}/presupuestos` · `GET` · `POST /{pid}/aprobar` · `/rechazar` · `/represupuestar` | Ítems discriminados (mano de obra/repuesto + calidad), validez/vencimiento, tipo ORIGINAL/ADICIONAL; mueve el estado de la reparación |
| **Repuestos** | `POST` · `PUT/{id}` · `GET/{id}` · `GET` (paginado) · `GET /reparacion/{id}` · `DELETE/{id}` (ADMIN) | Con `articuloId` descuenta stock del inventario |
| **Inventario** (PRO) | `POST` · `PUT/{id}` · `GET/{id}` · `GET` (paginado) · `GET /stock-bajo` · `POST /{id}/ajuste` · `DELETE/{id}` (ADMIN) | Catálogo con stock, ajustes y aviso de stock bajo |
| **Cobros** (PRO) | `POST /api/reparaciones/{id}/cobros` · `GET /cobros` · `DELETE /cobros/{id}` (ADMIN) · `GET /{id}/recibo` | total/cobrado/saldo; recibo imprimible |
| **Caja** (PRO) | `GET /api/caja?desde=&hasta=` | Resumen por período + desglose por método |
| **Dashboard** | `GET /api/dashboard` | Conteos por estado, consumo, stock bajo, últimas 5 reparaciones |
| **Usuarios/Empleados** (ADMIN) | `POST` · `GET` · `GET/{id}` · `PATCH/{id}` | Más de 1 empleado es PRO |
| **Seguimiento** (público) | `GET /api/seguimiento/{codigo}` · `POST /{codigo}/presupuesto/aprobar` · `POST /.../rechazar` | Sin login, por código |
| **Pagos** | `POST /api/pagos/suscripcion` (ADMIN) · `POST /api/pagos/suscripcion/cancelar` (ADMIN) · `POST /api/pagos/webhook` (público) | MercadoPago |
| **Salud** (público) | `GET /actuator/health` | Para readiness/liveness del deploy |

### Formato de error (uniforme)
```json
{ "timestamp": "...", "status": 402, "error": "Límite del plan alcanzado", "message": "...", "path": "/api/..." }
```
Códigos: `400` validación · `401` no autenticado · `402` límite/función PRO · `403` sin permiso ·
`404` no encontrado (o de otro taller) · `409` conflicto (unicidad o transición de estado no permitida) · `502` error de MercadoPago.

---

## Base de datos (migraciones Flyway)

| Versión | Qué agrega |
|---------|-----------|
| V1 | Esquema inicial (clientes, equipos, reparaciones, repuestos) |
| V2 | Multi-tenancy (talleres) + suscripciones |
| V3 | Login por email |
| V4 | `taller_id` obligatorio y unicidad por taller |
| V5 | Auditoría de reparaciones |
| V6 | Código de seguimiento público |
| V7 | Orden de trabajo ampliada (+ tabla `reparacion_fotos`) |
| V8 | Presupuestos (+ `presupuesto_items`) |
| V9 | Inventario (`articulos` + link en repuestos) |
| V10 | Cobros |
| V11 | Contador de consumo mensual en la suscripción |
| V12 | Ingreso enriquecido (flags de riesgo, bloqueo de cuenta) + número de orden por taller |
| V13 | Presupuesto pro (tipo, validez/vencimiento, mano de obra vs repuesto + calidad) |
| V14 | Fotos con momento (ingreso/post) + conformidad de entrega |

---

## Correr en local

Requiere **JDK 21** y una **PostgreSQL**. Las credenciales/secretos van en
`src/main/resources/application-secret.properties` (gitignored) o como variables de entorno
(ver `DEPLOY.md` para la lista completa).

```bash
export JAVA_HOME=<ruta-a-un-JDK-21>
./mvnw spring-boot:run        # levanta en http://localhost:8080
```

Flyway crea/actualiza el esquema solo. Swagger queda en `/swagger-ui.html` (detrás de auth).

### Verificación sin Docker
`./mvnw test` levanta el contexto completo sobre una **H2** en memoria (no necesita Postgres).

---

## Tests

```bash
export JAVA_HOME=<ruta-a-un-JDK-21>
./mvnw test
```

Suite de **49 tests** (integración MockMvc sobre el stack real + H2, y algunos unitarios puros). Los de flujo extienden
`support/IntegrationTestBase` (helpers de registro/login/PRO/JSON):
- **Aplicación** — carga del contexto completo (H2).
- **TenantIsolationTests** (3) — un taller no ve/borra clientes, equipos ni reparaciones de otro.
- **AuthTests** (5) — registro, login, credenciales inválidas (401), email duplicado (400), sin token (403).
- **ReparacionFlowTests** (6) — ingreso rápido + reúso de cliente, denormalización, búsqueda, cambio de estado (DTO + inválido), orden ampliada, paginación.
- **EstadoTransicionTests** (4) — máquina de estados: camino legal completo, salto ilegal → 409, mismo estado idempotente, terminal sin salida.
- **EstadoPagoTests** (2) — estado de pago derivado: SIN_COBRAR → PARCIAL → PAGADO (con saldo) y FREE sin cobros.
- **IngresoEnriquecidoTests** (2) — número de orden correlativo por taller y bandera roja de cuenta sin credenciales.
- **ReparacionDeleteTests** (2) — al borrar limpia presupuestos (cascade) y repone stock; bloquea si hay cobros.
- **PresupuestoFlowTests** (2) — crear + aprobar/rechazar desde el link público.
- **PresupuestoProTests** (3) — totales discriminados (mano de obra/repuesto + calidad), auto-estado, aprobación del taller, re-presupuestar y vencido.
- **EntregaYFotosTests** (2) — fotos con momento (default INGRESO) y conformidad de entrega sellada al pasar a ENTREGADO.
- **InventarioStockTests** (3) — descuento/reposición de stock, stock insuficiente (400), stock bajo + dashboard.
- **CobroCajaReciboTests** (1) — cobros parciales, saldo, recibo y caja.
- **PlanGatingTests** (3) — FREE → 402 en funciones PRO, mapa `funciones`, multi-empleado.
- **RolTests** (2) — USER vs ADMIN; empleado desactivado no loguea.
- **PlanLimitTests** (1) — superar el tope FREE devuelve 402.
- **MercadoPagoSignatureTests** (4) — firma del webhook (válida/inválida/ausente/sin-secreto).
- **PresupuestoVencidoTest** (3) — lógica pura de vencimiento (PENDIENTE expirado → VENCIDO; aprobado nunca vence).

---

## Estructura

```
src/main/java/com/leonardorozza/mvgrreparacionesbackend/
├── config/            # Security, CORS, JWT filter, tenant, MercadoPago, Web (paginación), auditoría
├── controller/        # Endpoints REST
├── service/           # Lógica + impl/ + dto/
├── persistence/       # entity/ (+ enums) y repository/
├── exceptions/        # GlobalExceptionHandler + excepciones de dominio
└── utils/             # mappers (MapStruct) + jwt
src/main/resources/db/migration/   # Flyway V1..V11
```
