# Baja del acceso propio del empleado

Estado: cerrado localmente el 2026-09-09. Baselines frontend `540f619`,
backend `ef8d9f4`. Continúa Tarea 14 / BACKEND-HANDOFF 3 del plan de Confianza y
cuenta, dentro de la fila 6 del alcance de salida inicial.

## Alcance y decisión

El empleado USER puede desactivar su propio acceso desde Cuenta. Confirma la
contraseña actual y el alcance de la acción. El servidor desactiva su usuario e
incrementa la versión de sesión en una transacción; los JWT anteriores dejan de
servir para solicitudes posteriores al commit, incluso si el titular reactiva
el usuario. No se cancelan peticiones ya autorizadas que estuvieran en vuelo.

La verificación razonable prevista por el handoff se resuelve con contraseña
actual y sesión vigente. No requiere email verificado, proveedor de correo,
suscripción activa ni nuevas aceptaciones para salir. Los contactos reales y la
identidad del operador siguen pendientes; no bloquean esta operación personal.

La acción no cierra el taller ni elimina datos, reparaciones o evidencia. El
titular conserva su función de reactivar empleados. ADMIN no ve este formulario
y el backend le rechaza la operación. La baja del taller, la supresión de datos
y el circuito de atención de solicitudes son trabajos distintos todavía abiertos.

## Contrato backend/frontend

`POST /api/cuenta/baja-acceso`, sesión USER, `Content-Type: application/json`:

```json
{"passwordActual":"contraseña actual sin recortar","confirmado":true}
```

- El actor y el taller se toman exclusivamente del principal autenticado.
- Cuerpo de hasta 4096 bytes; exactamente dos campos, sin duplicados, valores
  coercionados, JSON adicional ni parámetros de consulta. Contraseña de 1–100
  unidades UTF-16, no sólo espacios. No se recorta su valor.
- Éxito `204`, cuerpo vacío y `Cache-Control: private, no-store`, después del commit.
- `400 / PASSWORD_ACTUAL_INVALIDA` permite reingresar la contraseña conservando
  sesión. `400 / BAJA_ACCESO_INVALIDA` indica confirmación/cuerpo inválidos.
- Un principal obsoleto se rechaza; la frontera JWT conserva sus 401/403 actuales.
- `429` con `Retry-After`: bucket propio que reutiliza la configuración
  `security.rate-limit.account-recovery` (por defecto 5 intentos/15 minutos por
  dirección e instancia, con la política existente de proxy). No se cambia el
  comportamiento de login/recuperación.

El servicio relee y bloquea el usuario antes de verificar identidad, taller,
estado, rol, versión de sesión y contraseña. También se refresca el taller.
El cambio de active/tokenVersion es atómico; una excepción revierte ambos.

## Concurrencia y límites

La entidad User no tiene versión optimista. Un reset, verificación de email o
actualización administrativa que hubiera cargado una entidad antes de la baja
podía sobrescribir el estado nuevo. Esos tres escritores ahora adquieren el mismo
bloqueo de fila y refrescan la entidad antes de modificarla. Reset/verificación
revalidan además su token después del bloqueo y rechazan usuarios/talleres inactivos.
No se borran tokens de correo ni se agrega un job de eliminación.

No hay tablas, migraciones ni cambios a V27–V30. Se reutiliza `users.active` y
`users.token_version`. El frontend se publica después del backend que incorpora
este contrato; no se habilita una capacidad ficticia si el servidor no responde.

## Interfaz y protección de la confirmación

Cuenta ofrece el enlace sólo a USER. `/cuenta/baja-acceso` pasa por el guard de
perfil remoto independiente del plan; ADMIN vuelve a Cuenta sin montar el formulario.
La página presenta primero el alcance sobre usuario, sesiones y registros del taller,
luego contraseña y casilla de confirmación no preseleccionada. Mantiene los tokens,
tipografía y espaciado de Cuenta, una columna y controles de al menos 44 px en móvil.

La contraseña sólo permanece en memoria local y en el POST en curso. Se limpia del
formulario al iniciar la petición; no se guardan variables de mutation, secretos en
storage, query keys, URL ni mensajes de error. Confirmar otra vez exige reingresarla
y marcar de nuevo la casilla. No hay reintento automático ni segunda petición por
doble clic. La espera tiene timeout de 20 s y la navegación durante la confirmación
queda protegida. Desmontar cancela la espera; no revierte una operación ya enviada.

Sólo un 204 confirma la baja en pantalla. Un error de red/servidor comunica resultado
indeterminado. El éxito vacía la caché privada, cierra sesión y muestra una constancia
local en login sin incluir identificadores ni credenciales. Una respuesta tardía de
una sesión anterior no cierra la nueva sesión.

El interceptor de Cuenta compara el Authorization enviado con el token vigente
antes de aplicar efectos de error, y vuelve a comprobarlo tras validar el perfil.
La protección de la página sola no alcanzaba: el interceptor procesa primero un
401/403. Esta corrección se limita a solicitudes `sessionValidation: 'profile'`.
El proveedor DOM del router permite confirmar sincrónicamente la navegación al
login junto con el logout; así el guard no reemplaza la constancia de éxito.
Durante un POST en curso, el diálogo de navegación indica que la solicitud ya fue
enviada y bloquea el descarte, sin prometer que no se solicitará la baja.

## Pruebas y cierre

- Backend, Java 21: **93 pruebas** de contrato, servicio, HTTP, rate limit y cuenta,
  incluidas **15 PostgreSQL 16**, más **12 regresiones de roles/JWT**. Cero fallos,
  errores u omisiones en ambos lotes. Flyway hasta V30 y Hibernate validate.
- PostgreSQL acredita dos JWT revocados tras commit, conservación de otros usuarios
  y talleres, reactivación explícita sin restaurar JWT antiguos, rechazo de contraseña
  incorrecta/cuerpo inyectado sin cambios en filas y versión obsoleta tras reset.
  Las carreras usan entidades previamente cargadas y esperas de bloqueo observadas
  en `pg_stat_activity`: reset, verificación y actualización administrativa no
  restauran estado anterior. Dos bajas concurrentes incrementan la versión una vez.
- Frontend: **97 focalizadas** iniciales y, tras las correcciones de navegación e
  interceptor, **661 Vitest en 94 archivos + 42 controles de publicación** aprobados.
  Se revalidaron además las 11 pruebas de página tras ajustar el tipo del adaptador
  de router del test. Typecheck, lint y build aprobados.
- Chromium: **9 recorridos** aprobados, incluidos USER 1280/320, ADMIN sin operación,
  error de contraseña, Retry-After y resultado indeterminado con reintento explícito.
  Comprueban un solo POST, secreto descartado al enviar, sesión vigente hasta el 204,
  constancia posterior y persistencia del logout al recargar. Capturas revisadas sin
  desborde horizontal. La API del navegador es simulada; no es un recorrido integrado
  navegador → PostgreSQL ni evidencia de staging.

En la verificación se corrigieron el selector de la constancia y la exposición de
Retry-After del mock. La repetición detectó que el guard reemplazaba el estado de
éxito al hacer logout; el proveedor DOM y la navegación sincrónica lo resolvieron.
La revisión independiente detectó el 401/403 tardío del interceptor y la copy de
descarte durante el POST; ambos se corrigieron y tienen regresiones. El laboratorio
Vitest necesitó el proveedor base con adaptador flushSync para evitar dos contextos
de router, y el orden correcto de los handlers MSW. Una ejecución integral accidental
durante esos ajustes no se usa como cierre: la corrida estable de 661 pruebas es la
evidencia indicada arriba.

El build local conserva la advertencia de chunk principal mayor de 500 kB. Sus gates
de publicación se omiten por ser local (`PUBLIC_RELEASE_CHECK_SKIPPED`); los 42 tests
del gate no autorizan un despliegue. No se repitió clean verify backend: el cambio
de seguridad se cubrió con las regresiones de escritores y carreras PostgreSQL,
sin migración ni modificación del sistema legal. El proveedor de navegación motivó
la suite completa frontend.

Se confirmó la eliminación de PostgreSQL efímero y Ryuk y el cierre del puerto 5173.
V27–V30 no presentan diferencias contra HEAD. Se preservan los 77 archivos ajenos
no versionados. Primero debe desplegarse el contrato backend y después el frontend.
Staging, identidad/contactos reales, atención de solicitudes de datos, cierre del
taller y operación del proveedor de fotos siguen pendientes; no se amplía este corte.

Commits atómicos, uno por repositorio y sin push:

- Backend: `feat(cuenta): permite baja del acceso propio`.
- Frontend: `feat(cuenta): incorpora baja del acceso del empleado`.
