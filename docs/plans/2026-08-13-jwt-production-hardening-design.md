# Endurecimiento JWT productivo

## Objetivo

Fortalecer los access tokens existentes sin cambiar el contrato HTTP de login/registro. Cada request
autenticado debe validar firma HS256, issuer, audience, tiempo de validez, identidad, tenant y estado
de revocación contra el usuario persistido.

## Decisión

Se agrega `token_version BIGINT NOT NULL DEFAULT 0` a `users` y el claim `tokenVersion` al JWT. Un
reset o cambio de contraseña incrementa la versión. Todo token emitido con una versión anterior queda
revocado inmediatamente, sin mantener una denylist por `jti` ni depender de precisión temporal.

Se descartó usar `credentials_changed_at` porque la comparación con `iat` depende de precisión y
sincronización de reloj. También se descartó persistir cada `jti`: permitiría revocación individual,
pero requiere otra tabla, limpieza periódica y una escritura por login sin aportar valor al caso de
revocación global por cambio de credenciales.

## Contrato del token

Los tokens nuevos contienen:

- `iss`, `aud`, `sub` y `exp` validados por `java-jwt`.
- `iat`, `nbf` y `jti` obligatorios.
- `tallerId`, `tokenVersion` y `role` como claims de aplicación.

El algoritmo queda fijado a HS256. El bean JWT no inicia si el secreto tiene menos de 32 bytes, si
issuer/audience están vacíos, si la expiración no es positiva o si la tolerancia de reloj es inválida.
La audiencia se configura con `JWT_AUDIENCE` y la tolerancia con `JWT_CLOCK_SKEW_SECONDS`.

## Flujo por request

1. El filtro verifica criptografía y claims registrados una sola vez.
2. Carga el usuario y su taller actual desde base mediante un principal propio.
3. Compara `sub`, `tallerId` y `tokenVersion` con ese principal, además de comprobar que siga activo.
4. Construye la autenticación y `TenantContext` con el taller persistido, nunca con el claim.
5. Ante token inválido, usuario inexistente o mismatch, el request continúa anónimo y Spring Security
   aplica el contrato de acceso existente. La causa concreta no se expone ni se registra.
6. `TenantContext` se limpia siempre al finalizar el request.

## Persistencia y revocación

Flyway V19 agrega `users.token_version` con valor inicial cero. La entidad centraliza el cambio de
password para actualizar el hash e incrementar esa versión dentro de la misma transacción.

## Pruebas

- Unitarias: claims emitidos, rechazo de audience incorrecta y secreto débil.
- Integración: token correctamente firmado con tenant manipulado y rechazo de un token emitido antes
  de un reset de contraseña; un login posterior debe volver a funcionar.

## Fuera de alcance

Refresh tokens, rotación de claves, cambio a firma asimétrica y revocación individual por `jti`.
