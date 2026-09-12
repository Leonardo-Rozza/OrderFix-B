# Email transaccional — Resend y dominio OrdenFix

Fecha: 2026-09-12. Proveedor existente: Resend por SMTP, desde el backend Java.
Alcance: verificación de cuenta y recuperación de contraseña. No agrega marketing,
notificaciones de reparaciones, cobros, endpoints de prueba ni infraestructura de colas.

## Estado comprobado

- El usuario posee `orden-fix.com.ar`. La consulta DNS pública del 2026-09-12 devuelve
  `ns1.vercel-dns.com` y `ns2.vercel-dns.com`: la zona ya está delegada a Vercel.
  Esto no acredita asignación al proyecto, certificado, deploy ni remitente verificado.
- La nueva `API_KEY_RESEND`, guardada en el archivo ignorado de secretos, autenticó por
  SMTP con STARTTLS y respondió `250` a NOOP. Se cerró con QUIT; **cero correos enviados**.
- El valor anterior de `MAIL_PASSWORD` fue rechazado como clave inválida. En el archivo
  privado se reemplazó sólo ese valor por `${API_KEY_RESEND}`, conservando la nueva clave
  en un único lugar. Ningún secreto se copia a esta documentación, Git o frontend.
- La clave tiene permiso de envío: la consulta de dominios devuelve `restricted_api_key`.
  Es una limitación esperada de ese permiso; no se amplía a Full access para inspeccionar
  dominios. La verificación debe acreditarse desde el panel de Resend.
- El remitente local sigue siendo `OrdenFix <onboarding@resend.dev>` y la activación local
  existente se conserva. Ese remitente de prueba sólo permite enviar al correo propio
  de la cuenta Resend. Autenticación SMTP no equivale a entrega de un mensaje.

## Arranque local

`SPRING_CONFIG_IMPORT` es una variable de entorno del proceso, no una línea que deba
copiarse dentro de `application.properties`. El archivo de secretos está excluido de
los recursos del build y no se importa automáticamente durante pruebas o producción.

Desde la raíz del backend, con Java 21 configurado:

```sh
./scripts/run-local.sh
```

El launcher importa el `src/main/resources/application-secret.properties` local ya
existente sólo para `spring-boot:run`. No inicia nada al compilar ni al probar. Si el
archivo falta o no es legible, falla antes de Maven. Para guardar la configuración
fuera del repositorio, se puede indicar una ruta, incluyendo rutas con espacios:

```sh
./scripts/run-local.sh /ruta/privada/ordenfix-secrets.properties
```

En un IDE se configura la variable `SPRING_CONFIG_IMPORT=file:/ruta/privada/ordenfix-secrets.properties`
en la ejecución de la aplicación. No se versiona una ruta absoluta de esta máquina.
El arranque usa la base y los servicios configurados en ese archivo, igual que el
arranque habitual; este corte no ejecuta la aplicación con credenciales reales.

## Contrato de configuración

| Propiedad externa | Uso |
| --- | --- |
| `MAIL_ENABLED` | `false` por defecto; `true` permite envíos reales. |
| `API_KEY_RESEND` | Nueva clave de Resend. Se admite directamente si falta `MAIL_PASSWORD`. |
| `MAIL_PASSWORD` | Tiene prioridad cuando existe; puede referenciar `${API_KEY_RESEND}` dentro del archivo privado. |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` | `smtp.resend.com` / `587` / `resend`. Puerto STARTTLS; no cambiar sólo el puerto a 465. |
| `MAIL_FROM` | Identidad de envío autorizada por Resend. Producción necesita dominio verificado. |
| `APP_PUBLIC_URL` | URL del frontend para los enlaces de verificación y recuperación. Local: `http://localhost:5173`; salida: origen HTTPS realmente desplegado. |

El transporte exige STARTTLS, comprueba el nombre del certificado y limita conexión a
5 segundos, lectura y escritura a 10 segundos cada una. Los tiempos limitan operaciones
SMTP, no prometen un máximo global de la petición. No se registra contenido, destinatario,
token ni mensaje de error del proveedor. El correo de recuperación se envía después del
commit; si hay rollback no se envía. Una falla de correo conserva la respuesta genérica
para evitar revelar si existe la cuenta. No hay reintento durable ni garantía de entrega;
el usuario puede volver a solicitar un enlace dentro de los límites existentes.

## Configuración de dominio y producción

1. En Resend, agregar/verificar un dominio de envío. Propuesta pendiente de aplicar:
   `cuenta.orden-fix.com.ar`, para separar estos correos transaccionales. Un remitente
   posible es `OrdenFix <notificaciones@cuenta.orden-fix.com.ar>`; no es todavía una
   dirección operativa ni sustituye soporte/privacidad/legal.
2. Copiar en la zona DNS de Vercel los registros exactos que entregue Resend para ese
   dominio: DKIM y SPF/Return-Path, incluyendo su MX de rebotes. No inventar valores,
   región ni selectores, ni reemplazar registros de recepción ajenos. Conservar apagado
   el seguimiento de aperturas/clics para enlaces con tokens. Acreditar estado Verified.
3. Configurar `MAIL_FROM` con ese dominio y comprobar que la clave de envío permite
   usarlo. Mantener la clave sólo en el servicio backend. El frontend Vite no necesita
   la API key; cualquier variable `VITE_*` se expone al navegador.
4. Agregar el dominio web al proyecto de Vercel y aplicar los registros que indique el
   proyecto. La delegación DNS ya existente no prueba que esté vinculado. Elegir el
   origen canónico y alinear `APP_PUBLIC_URL` y `CORS_ORIGINS` en backend; configurar
   `VITE_API_BASE_URL` con la API HTTPS real. No hace falta editar CorsConfig para cambiar
   la variable de orígenes. Conservar sólo los orígenes exactos necesarios.
5. Acreditar un envío controlado a una casilla autorizada, recepción y enlace utilizable;
   luego repetir registro/verificación/recuperación en staging. Un ACK SMTP sólo acredita
   aceptación del proveedor, no recepción en inbox. Antes de habilitar talleres reales,
   cerrar ese recorrido, credenciales del entorno y remitente definitivo.

El correo de soporte y los canales de privacidad/legal requieren una recepción atendida;
verificar un remitente transaccional no crea ese circuito. Su definición y la identidad
registral siguen diferidas hasta después de MP y Email, por decisión del usuario.

## Cortes restantes

- **Email A:** configuración local, SMTP seguro y regresión de cuenta. Evidencia en el
  [plan del corte](../plans/2026-09-12-email-transaccional-implementation.md).
- **Email B:** dominio Resend/DNS, remitente y entrega controlada a una casilla autorizada.
- **Email C:** recorrido con frontend y backend de staging, enlaces HTTPS y operación del envío.

## Referencias oficiales consultadas

- [Resend SMTP](https://resend.com/docs/send-with-smtp).
- [Dominios y subdominios](https://resend.com/docs/dashboard/domains/introduction).
- [Registros Resend en Vercel](https://resend.com/docs/knowledge-base/vercel).
- [Errores y permiso de envío](https://www.resend.com/docs/api-reference/errors).
- [Agregar dominio al proyecto Vercel](https://vercel.com/docs/domains/working-with-domains/add-a-domain).
