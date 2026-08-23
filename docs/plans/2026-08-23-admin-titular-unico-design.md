# Fase 2.0 — ADMIN titular único por taller

Fecha: 2026-08-23

Estado: implementada en rama local, pendiente de despliegue

Alcance: backend Java; sin cambios de UI, invitaciones ni aceptación legal

## Motivo

OrdenFix distingue dos responsabilidades:

- `ADMIN`: titular que crea y representa al taller;
- `USER`: empleado con acceso operativo delegado.

El contrato anterior permitía enviar `ADMIN` al crear un empleado o promoverlo
mediante `PATCH /api/usuarios/{id}`. Eso contradecía la UI y dejaba la autoridad
del taller expuesta a un request manual o a un cliente desactualizado.

Esta fase cierra únicamente esa brecha antes de versionar las aceptaciones
legales. En el wire y en la base se conserva `ADMIN` para evitar una migración de
JWT; `ADMIN titular` es su semántica de negocio.

## Decisiones

1. El registro de un taller sigue siendo la única operación que crea un `ADMIN`.
2. `POST /api/usuarios` siempre persiste `USER`.
3. El campo request `role` se conserva temporalmente por compatibilidad:
   - ausente o `USER`: alta válida;
   - `ADMIN`: `400 EMPLEADO_DEBE_SER_USER` y no se crea nada.
4. En `PATCH /api/usuarios/{id}`, omitir `role` o repetir el actual es un no-op
   compatible. Cualquier cambio responde `400 ROL_USUARIO_INMUTABLE`.
5. `active` continúa siendo editable para empleados; el titular no puede
   desactivarse a sí mismo.
6. PostgreSQL es la última barrera ante concurrencia o escrituras fuera del
   servicio: exige tenant, roles conocidos y como máximo un `ADMIN` por taller.
7. No se implementa transferencia de titularidad. Cuando exista, será un flujo
   explícito, reautenticado, auditado y transaccional; nunca un PATCH genérico.

## Contrato HTTP transitorio

### Crear empleado

```http
POST /api/usuarios
Authorization: Bearer <ADMIN>
Content-Type: application/json

{
  "username": "Ana",
  "email": "ana@taller.com",
  "password": "secreto-seguro",
  "role": "USER"
}
```

`role` puede omitirse. En ambos casos la respuesta contiene `role: "USER"`.

Intentar `role: "ADMIN"` devuelve:

```json
{
  "status": 400,
  "code": "EMPLEADO_DEBE_SER_USER",
  "message": "Los empleados se crean con rol USER; el ADMIN titular es único por taller.",
  "details": {
    "rolPermitido": "USER",
    "rolSolicitado": "ADMIN"
  }
}
```

### Actualizar estado

El cliente nuevo envía únicamente:

```http
PATCH /api/usuarios/{id}

{ "active": false }
```

Mientras se retira el campo legacy, repetir el rol actual no cambia nada. Una
promoción o degradación devuelve:

```json
{
  "status": 400,
  "code": "ROL_USUARIO_INMUTABLE",
  "message": "El rol de un usuario no se puede cambiar desde la gestión de empleados.",
  "details": {
    "rolActual": "USER",
    "rolSolicitado": "ADMIN"
  }
}
```

## Migración V26

`V26__admin_titular_unico.sql` falla de forma cerrada si detecta:

- usuarios sin `taller_id`;
- roles distintos de `ADMIN`/`USER`;
- más de un `ADMIN` en el mismo taller.

No corrige ni degrada usuarios automáticamente porque los datos no permiten
determinar quién representa legítimamente al taller. Antes de desplegar se puede
auditar con:

```sql
SELECT id, email, role, taller_id
FROM users
WHERE taller_id IS NULL OR role NOT IN ('ADMIN', 'USER');

SELECT taller_id, COUNT(*) AS admins
FROM users
WHERE role = 'ADMIN'
GROUP BY taller_id
HAVING COUNT(*) > 1;
```

Después del preflight la migración agrega:

- `users.taller_id NOT NULL`;
- `ck_users_role`;
- índice único parcial `uk_users_admin_titular_por_taller`.

`SET NOT NULL`, el check y el índice requieren escanear/bloquear brevemente
`users`. En producción se ejecutan después del preflight y dentro de una ventana
controlada, sin altas de usuarios concurrentes.

## Verificación y despliegue

- `RolTests` cubre alta maliciosa, promoción, degradación, compatibilidad de
  `role: USER` y desactivación.
- `PostgresMigrationIT` valida V26 sobre PostgreSQL real, prueba la restricción
  única, tenant obligatorio y roles válidos, y simula upgrades V25 → V26 con
  datos históricos incompatibles.
- Orden de despliegue: backend compatible → frontend deja de enviar `role` → en
  una fase posterior se elimina el campo legacy de los DTO.

## Siguiente fase

Congelar el contrato de documentos/requisitos legales antes de crear tablas o
queries frontend. Esta fase no publica documentos ni registra aceptaciones.
