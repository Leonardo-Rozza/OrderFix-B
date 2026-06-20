# OrdenFix — Guía de integración Frontend

Fuente de verdad del contrato entre el frontend y el backend de **OrdenFix**.
Copiá este archivo al repo del frontend (o usalo como referencia para Claude Code).

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

CORS habilitado para:
- `http://localhost:5173` (Vite dev)
- `https://mvgr-reparaciones-frontend.vercel.app`

> Si el frontend se despliega en otro dominio, hay que agregarlo en el backend (`CorsConfig`).

---

## 3. Autenticación (JWT)

- Endpoints públicos: todo bajo `/api/auth/**`. **El resto requiere token.**
- Header en cada request autenticada:
  `Authorization: Bearer <token>`
- El token es un JWT que contiene `sub` (email), `role`, `tallerId` y `exp`.
  Se puede decodificar en el front para mostrar email/rol, **pero la seguridad la
  valida siempre el backend** (no confíes en el token para habilitar acciones críticas).
- Duración del token: definida por el backend (por defecto 24 h). Cuando vence → 401.
- **Roles**: `ADMIN` (dueño) y `USER` (empleado). Hoy el registro crea siempre ADMIN.
  Operaciones **solo ADMIN** (un USER recibe `403`): iniciar suscripción PRO (`POST /api/pagos/suscripcion`)
  y los **borrados** (DELETE de clientes/equipos/reparaciones/repuestos). El resto del CRUD lo puede hacer cualquier usuario autenticado.

### Flujo
1. **Registro** (`POST /api/auth/register`) o **Login** (`POST /api/auth/login`) → devuelven `{ token, type, email }`.
2. Guardar el token (localStorage) y mandarlo en el header en todas las llamadas.
3. Ante un **401**, borrar el token y redirigir a `/login`.

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
{ "token": "eyJ...", "type": "Bearer", "email": "juan@celexpress.com" }
```
Errores: `400` (email ya registrado o validación).

#### `POST /api/auth/login`
Request:
```json
{ "email": "juan@celexpress.com", "password": "juan123" }
```
Respuesta `200`:
```json
{ "token": "eyJ...", "type": "Bearer", "email": "juan@celexpress.com" }
```
Errores: `401` (email o contraseña incorrectos).

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
  "limiteReparacionesMes": 50      // null = ilimitado (PRO)
}
```

---

### 4.2.bis Paginación y búsqueda (todos los listados principales)

Los `GET` de listado (`/api/clientes`, `/api/equipos`, `/api/reparaciones`, `/api/repuestos`)
**devuelven una página**, no un array. Aceptan query params:

- `page` (0-based, default 0), `size` (default 20), `sort` (ej: `sort=nombre,asc`).
- `q` = búsqueda por texto (clientes: nombre/apellido/teléfono; equipos: marca/modelo/IMEI;
  reparaciones: descripción; repuestos: nombre).
- Reparaciones además: `estado=EN_PROCESO` (filtro por estado).

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
ClienteResponse: `{ id, nombre, apellido, telefono, email, direccion }`

Errores: `400` si el teléfono ya existe en el taller (al crear); `409` si choca la unicidad al actualizar.

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
EquipoResponse: `{ id, marca, modelo, imei, color, descripcion, clienteId }`

Errores: `404` si el `clienteId` no pertenece a tu taller.

---

### 4.5 Reparaciones — requiere token  (`/api/reparaciones`)

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/api/reparaciones` | ReparacionRequest | `201` ReparacionResponse |
| PUT    | `/api/reparaciones/{id}` | ReparacionRequest | `200` ReparacionResponse |
| PATCH  | `/api/reparaciones/{id}/estado` | `{ "estado": "EN_PROCESO" }` | `200` ReparacionResponse |
| GET    | `/api/reparaciones/{id}` | — | `200` ReparacionResponse |
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
  "tecnicoId": 1,                      // id de un usuario del taller (404 si no existe)
  "fotos": ["https://cdn/foto1.jpg"]   // URLs (la subida del archivo la hace el front)
}
```
ReparacionResponse: `{ ...campos de arriba..., codigoSeguimiento, tecnicoId, tecnicoNombre, fotos, totalRepuestos, total }`
> **Privacidad:** `patronDesbloqueo`, `pinDesbloqueo` y `observaciones` se ven en la app (con token) pero **nunca** en el seguimiento público (§4.9).
- `codigoSeguimiento`: código público para compartir con el cliente (ver §4.9).
- `totalRepuestos`: suma de los repuestos. `total`: mano de obra (`precioFinal ?? precioEstimado ?? 0`) + repuestos.

**Avisar al cliente por WhatsApp:** `GET /api/reparaciones/{id}/whatsapp` → `{ url, telefono, mensaje, linkSeguimiento }`.
El front abre `url` (wa.me con mensaje prearmado que incluye el link de seguimiento). Útil al pasar a COMPLETADO.

**Cambiar estado** (`PATCH .../estado`): body JSON con el campo `estado`:
```json
{ "estado": "EN_PROCESO" }
```
(Header `Content-Type: application/json`.) Estado inválido → `400`.

Errores: `404` si el equipo/reparación no es de tu taller; **`402` si alcanzaste el límite del plan** (ver §6).

Estados posibles (enum `EstadoReparacion`):
`INGRESADO`, `EN_PROCESO`, `ESPERANDO_REPUESTO`, `COMPLETADO`, `ENTREGADO`

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
RepuestoResponse: `{ id, nombre, descripcion, precio, reparacionId, articuloId, cantidad }`
> Si mandás `articuloId`, el backend **descuenta `cantidad` del stock** (400 si no alcanza) y lo **repone** si borrás el repuesto. Ver inventario en §4.12.

---

### 4.7 Pagos / Upgrade a PRO — requiere token  (`/api/pagos`)

Suscripción PRO con **cobro mensual recurrente** vía MercadoPago (preapproval).

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

- `502` (`"Error con el proveedor de pagos"`) si MercadoPago falla o la integración no está habilitada en el backend.
- Tras el pago, el plan **no cambia al instante**: se confirma por webhook (server-to-server). El front debe **re-consultar `GET /api/suscripcion`** al volver (y/o reintentar unos segundos) para ver `plan: "PRO"` / `estado: "ACTIVA"`.

#### `POST /api/pagos/suscripcion/cancelar` — solo ADMIN
Cancela la suscripción PRO (cancela el preapproval en MercadoPago si existe) y **baja a plan FREE**
(el taller sigue operando con el tope gratuito). Devuelve la suscripción actualizada (`200`).

#### `POST /api/pagos/webhook` — PÚBLICO (uso interno de MercadoPago)
Lo llama MercadoPago, **no el frontend**. Actualiza el plan/estado de la suscripción automáticamente
(`authorized` → PRO/ACTIVA, `paused` → VENCIDA, `cancelled` → vuelve a FREE/ACTIVA).

---

### 4.8 Dashboard — requiere token  (`/api/dashboard`)

#### `GET /api/dashboard`
Métricas del taller para la pantalla de inicio.
```json
{
  "reparacionesPorEstado": { "INGRESADO": 3, "EN_PROCESO": 1, "ESPERANDO_REPUESTO": 0, "COMPLETADO": 0, "ENTREGADO": 0 },
  "totalReparaciones": 4,
  "reparacionesEsteMes": 4,
  "equiposListos": 0,
  "articulosStockBajo": 1,
  "plan": "PRO",
  "estadoSuscripcion": "ACTIVA",
  "limiteReparacionesMes": null
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

### 4.11 Presupuestos  (`/api/reparaciones/{reparacionId}/presupuestos`) — requiere token

Presupuesto de una reparación, con ítems y aprobación del cliente.

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/api/reparaciones/{id}/presupuestos` | PresupuestoRequest | `201` PresupuestoResponse |
| GET    | `/api/reparaciones/{id}/presupuestos` | — | `200` PresupuestoResponse[] (más nuevo primero) |

PresupuestoRequest:
```json
{
  "items": [
    { "descripcion": "Pin de carga", "cantidad": 1, "precioUnitario": 8000 },
    { "descripcion": "Mano de obra", "cantidad": 1, "precioUnitario": 12000 }
  ],
  "observaciones": "Demora 48hs"   // opcional
}
```
PresupuestoResponse: `{ id, reparacionId, estado, items[], total, observaciones, fechaRespuesta, createdAt }`
- `estado`: `PENDIENTE | APROBADO | RECHAZADO`. `total` lo calcula el backend (Σ cantidad×precioUnitario).
- El cliente **aprueba/rechaza desde el link público** (ver §4.9).

---

### 4.10 Usuarios / Empleados — solo ADMIN  (`/api/usuarios`)

Gestión de los empleados del taller. **Todo el grupo requiere rol ADMIN** (un USER recibe `403`).

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/api/usuarios` | CrearUsuario | `201` UsuarioResponse |
| GET    | `/api/usuarios` | — | `200` UsuarioResponse[] |
| GET    | `/api/usuarios/{id}` | — | `200` UsuarioResponse |
| PATCH  | `/api/usuarios/{id}` | `{ "role"?, "active"? }` | `200` UsuarioResponse |

CrearUsuario: `{ "username", "email", "password", "role"? }` (sin `role` → se crea `USER`).
UsuarioResponse: `{ id, username, email, role, active }`.

- Un usuario **desactivado** (`active:false`) no puede loguear (`401`).
- Guardas: un ADMIN **no puede desactivarse ni quitarse el rol a sí mismo** (`400`).
- `400` si el email ya está en uso.

---

### 4.12 Inventario — requiere token  (`/api/inventario`)

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

### 4.13 Cobros / Caja / Recibo — requiere token

Pagos de una reparación (parciales o totales), resumen de caja y recibo imprimible.

**Cobros de una reparación** (`/api/reparaciones/{reparacionId}`):

| Método | Ruta | Body | Resp |
|--------|------|------|------|
| POST   | `/cobros` | `{ "monto": 20000, "metodo": "EFECTIVO", "observaciones"? }` | `201` CobroResponse |
| GET    | `/cobros` | — | `200` CobrosReparacion (resumen + lista) |
| DELETE | `/cobros/{cobroId}` | — | `204` (solo ADMIN, anula el cobro) |
| GET    | `/recibo` | — | `200` Recibo (datos para imprimir) |

- `metodo`: `EFECTIVO | TRANSFERENCIA | TARJETA | MERCADOPAGO | OTRO`.
- `GET /cobros` devuelve: `{ total, cobrado, saldo, pagado, cobros: [...] }` (el `total` lo calcula el backend = mano de obra + repuestos).
- `GET /recibo` trae todo lo del recibo: taller, cliente, equipo, descripción, `repuestos[{nombre,cantidad,precioUnitario,subtotal}]`, `manoDeObra`, `totalRepuestos`, `total`, `cobrado`, `saldo`, `pagado`, `fecha`. El front lo maqueta/imprime.

**Caja** (`/api/caja`):

| Método | Ruta | Resp |
|--------|------|------|
| GET | `/api/caja?desde=YYYY-MM-DD&hasta=YYYY-MM-DD` | `200` CajaResumen |

Sin params = **hoy**. Devuelve `{ desde, hasta, totalCobrado, cantidad, porMetodo: { EFECTIVO, TRANSFERENCIA, ... }, cobros: [...] }`.

---

## 5. Formato de error (todos los endpoints)

```json
{
  "timestamp": "2026-06-16T22:02:21.43",
  "status": 402,
  "error": "Límite del plan alcanzado",
  "message": "Alcanzaste el límite de 50 reparaciones por mes del plan FREE...",
  "path": "/api/reparaciones"
}
```

| Código | Significado | Qué hace el front |
|--------|-------------|-------------------|
| 400 | Validación / dato inválido (incl. teléfono duplicado al crear cliente) | Mostrar `message` en el form/toast |
| 401 | No autenticado, login inválido o token vencido | Logout + redirigir a `/login` |
| 402 | **Límite del plan / suscripción no vigente** | Modal "Pasá a PRO" con el `message` |
| 403 | Falta token o sin permiso | Logout o "sin permisos" |
| 404 | No encontrado (o recurso de otro taller) | "No existe" |
| 409 | Conflicto de unicidad (al actualizar) | Mostrar `message` |
| 500 | Error interno (mensaje genérico) | Toast genérico "Intentá más tarde" |
| 502 | Falló el proveedor de pagos (MercadoPago) | Toast "No pudimos iniciar el pago, probá de nuevo" |

---

## 6. Freemium / límites de plan

- Plan **FREE/TRIAL**: tope de **50 reparaciones por mes** (configurable en backend).
- Plan **PRO**: ilimitado.
- Suscripción `VENCIDA`/`CANCELADA`: bloquea la creación de reparaciones.
- Cuando se supera el límite, `POST /api/reparaciones` devuelve **`402`** con un `message` accionable.
- El front debe leer `GET /api/suscripcion` para mostrar el consumo (ej: "12/50 este mes")
  y mostrar el modal de upgrade ante un 402.

**Flujo de upgrade a PRO:**
1. Ante el 402 (o desde la pantalla de Plan), el usuario toca "Pasar a PRO".
2. `POST /api/pagos/suscripcion` → tomás `initPoint` y hacés `window.location.href = initPoint`.
3. El usuario paga en MercadoPago y vuelve a `MP_BACK_URL` (`/suscripcion/resultado`).
4. En esa página, re-consultás `GET /api/suscripcion`. Si todavía figura FREE, reintentá cada
   pocos segundos (el webhook puede tardar unos instantes en confirmar) hasta ver `plan: "PRO"`.

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
  | "INGRESADO" | "EN_PROCESO" | "ESPERANDO_REPUESTO" | "COMPLETADO" | "ENTREGADO";

export interface AuthResponse { token: string; type: string; email: string; }
export interface Suscripcion {
  plan: Plan; estado: EstadoSuscripcion;
  fechaInicio: string | null; fechaFinTrial: string | null; proximoCobro: string | null;
  reparacionesEsteMes: number; limiteReparacionesMes: number | null;
}
export interface Cliente { id: number; nombre: string; apellido: string; telefono: string; email: string | null; direccion: string | null; }
export interface Equipo { id: number; marca: string; modelo: string; imei: string | null; color: string | null; descripcion: string | null; clienteId: number; }
export interface Reparacion {
  id: number; equipoId: number; descripcionProblema: string; estado: EstadoReparacion;
  precioEstimado: number | null; precioFinal: number | null;
  fechaIngreso: string | null; fechaEstimadaEntrega: string | null; fechaEntrega: string | null;
  codigoSeguimiento: string | null; totalRepuestos: number; total: number;
  patronDesbloqueo: string | null; pinDesbloqueo: string | null; accesorios: string | null;
  condicionesIngreso: string | null; observaciones: string | null;
  tecnicoId: number | null; tecnicoNombre: string | null; fotos: string[];
}
export interface Repuesto { id: number; nombre: string; descripcion: string | null; precio: number; reparacionId: number | null; }
export interface CheckoutResponse { preapprovalId: string; initPoint: string; }
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

// listar por estado
const { data } = await api.get<Reparacion[]>("/api/reparaciones/estado", { params: { estado: "EN_PROCESO" } });

// iniciar upgrade a PRO (redirige a MercadoPago)
const { data } = await api.post<CheckoutResponse>("/api/pagos/suscripcion");
window.location.href = data.initPoint;
```

---

## 8. Pantallas mínimas del frontend

1. **Login** y **Registro** (onboarding del taller).
2. **Layout protegido** (sidebar: Clientes, Equipos, Reparaciones, Repuestos, Plan) con logout.
3. **Clientes**: tabla + búsqueda + ABM; ficha con sus equipos.
4. **Equipos**: tabla + ABM (selector de cliente); ver reparaciones del equipo.
5. **Reparaciones** (pantalla estrella): tablero/lista por estado, ABM, cambio rápido de estado,
   detalle con repuestos y total (suma de precios).
   - **Carga rápida** (`POST /api/reparaciones/ingreso-rapido`): un formulario corto (nombre + teléfono
     del cliente, marca + modelo del equipo, problema) que crea todo de una. Con el `clienteId`/`equipoId`
     que devuelve, ofrecé "completar datos" para ir a las pantallas de Cliente/Equipo.
6. **Repuestos**: ABM, asociación a reparación.
7. **Plan**: muestra `GET /api/suscripcion` (consumo + estado), comparativa FREE/PRO, botón
   "Pasar a PRO" (`POST /api/pagos/suscripcion` → redirige a MercadoPago), y maneja el 402.
8. **Resultado de pago** (`/suscripcion/resultado`): página de retorno de MercadoPago que
   re-consulta `GET /api/suscripcion` hasta confirmar el plan PRO.

---

## 9. Estado actual del backend y roadmap

**Listo y funcionando:**
- Auth (registro + login por email), JWT con tenant.
- Multi-tenancy con aislamiento total por taller.
- CRUD de Clientes, Equipos, Reparaciones, Repuestos.
- Suscripciones freemium (FREE/PRO) con límite mensual y gating 402.
- **MercadoPago**: checkout de suscripción PRO (`POST /api/pagos/suscripcion`) + **cancelación**
  (`/cancelar`) + webhook con **firma validada**. *(Requiere Access Token y webhook-secret; ver §10.)*
- **Carga rápida** de reparación (§4.5), **total** de reparación (mano de obra + repuestos).
- **Orden de trabajo ampliada**: checklist de ingreso (patrón/PIN, accesorios, condiciones), técnico asignado, observaciones internas y fotos.
- **Paginación + búsqueda** en todos los listados (§4.2.bis).
- **Roles ADMIN/USER** (borrados y suscripción solo ADMIN) y **gestión de empleados** (§4.10).
- **Dashboard** (§4.8), **seguimiento público** + **link de WhatsApp** (§4.9).
- **Presupuestos** con aprobación del cliente desde el link público (§4.11).
- **Inventario** con stock, ajustes, descuento automático y aviso de stock bajo (§4.12).
- **Cobros / Caja / Recibo** (§4.13): pagos parciales, saldo, caja por período y recibo imprimible.
- **Salud** (`/actuator/health`) y **tests** (aislamiento de tenant, 402, firma de webhook).
- Spring Boot 4 / Java 21, migraciones con Flyway.

**Próximo (ideas a futuro):**
- **WhatsApp Business API** (envío automático real; hoy es link wa.me manual).
- Proveedores en inventario, reportes avanzados, verificación de email en el registro.
- **WhatsApp Business API** (envío automático real; hoy es link wa.me manual).
- **Reportes** avanzados.

---

## 10. Configuración del backend para MercadoPago (no es del front)

La integración viene **desactivada por defecto** (`mercadopago.enabled=false`): el backend arranca igual
sin tocar nada. Para operar pagos en vivo, definí estas variables de entorno (o en `application-secret.properties`):

| Variable | Default | Descripción |
|----------|---------|-------------|
| `MP_ENABLED` | `false` | `true` para activar las llamadas a MercadoPago |
| `MP_ACCESS_TOKEN` | *(vacío)* | Access Token de MercadoPago (las de prueba también empiezan con `APP_USR-`) |
| `MP_WEBHOOK_SECRET` | *(vacío)* | Clave secreta del webhook (panel MP → Webhooks). Si está vacía, **no se valida la firma** (solo dev). En prod, obligatoria. |
| `MP_AMOUNT` | `4999` | Monto mensual de la suscripción PRO |
| `MP_CURRENCY` | `ARS` | Moneda |
| `MP_REASON` | `OrdenFix PRO - Suscripción mensual` | Texto que ve el usuario en el checkout |
| `MP_BACK_URL` | `http://localhost:5173/suscripcion/resultado` | URL del front a la que vuelve el usuario tras pagar |

Además, en el panel de MercadoPago hay que configurar la **URL de notificaciones (webhook)** apuntando a
`https://<tu-backend>/api/pagos/webhook` (en local se usa un túnel tipo ngrok). El endpoint `/api/pagos/webhook`
es público pero **valida la firma `x-signature`** con `MP_WEBHOOK_SECRET`; el resto de `/api/pagos/**` exige token.

**Health check (para el deploy):** `GET /actuator/health` es público y devuelve `{"status":"UP"}`. Útil para
configurar los probes de readiness/liveness en Render/Railway. No expone otros endpoints de Actuator.

> Diseñá la navegación contemplando estas secciones futuras (Dashboard, Caja, Inventario, Reportes)
> para no rehacer el layout más adelante.
```