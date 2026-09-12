# Email transaccional — Resend y dominio OrdenFix

Fecha: 2026-09-12. Proveedor existente: Resend por SMTP, desde el backend Java.
Alcance: verificación de cuenta y recuperación de contraseña. No agrega marketing,
notificaciones de reparaciones, cobros, endpoints de prueba ni infraestructura de colas.

## Estado comprobado

**Corte B, 2026-09-12:** `cuenta.orden-fix.com.ar` está Verified en Resend,
región São Paulo (`sa-east-1`). Los tres registros pedidos por el proveedor se
publicaron manualmente en Vercel y respondieron en DNS público. El usuario autorizó
expresamente esos registros y un único envío de prueba a su propia casilla.

- El remitente local es `OrdenFix <notificaciones@cuenta.orden-fix.com.ar>`.
  Se modificó únicamente `MAIL_FROM` en el archivo privado ignorado; claves y otras
  propiedades se preservaron. No se cambió la configuración de un backend desplegado.
- El adaptador Java real obtuvo una única aceptación SMTP observada, sin arrancar
  la app, DB o Flyway. Resend registró **Sent y Delivered**, 2026-09-12 10:12 -03:00,
  para el mensaje `78e024f6-261e-4269-a464-05f2f9472706`.
  El titular confirmó que recibió el correo el 2026-09-12. No especificó la carpeta
  de recepción; no se infiere Recibidos ni Spam.
- No se configuró dominio de tracking, no se habilitó recepción de correo ni se dio
  acceso automático a Resend sobre Vercel. El enlace backend → Resend exige STARTTLS;
  la política Resend → servidor receptor conserva el valor `Opportunistic` observado
  en el panel. No se acredita TLS obligatorio en ese segundo tramo para todo destino.
- Vercel muestra `orden-fix.com.ar` conectado al proyecto `order-fix`, redirigiendo a
  `www.orden-fix.com.ar`, también conectado; certificados administrados por Vercel.
  Esto acredita la configuración del panel, no el repositorio/rama desplegado ni el
  funcionamiento del frontend/backend de este plan en esa URL. No hubo deploy.

En el corte A se comprobó la nueva clave con SMTP AUTH/NOOP sin enviar correos, se
corrigió `MAIL_PASSWORD=${API_KEY_RESEND}` y se aprobaron 61 tests focalizados. La clave
conserva permiso de envío; no se amplió a Full access. La evidencia de DNS, remitente
y mensaje está en el [cierre del corte B](../plans/2026-09-12-email-dominio-entrega-implementation.md).

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

## Dominio configurado y siguiente entorno

El dominio de envío ya configurado es `cuenta.orden-fix.com.ar`, con remitente
`OrdenFix <notificaciones@cuenta.orden-fix.com.ar>`. Los registros de su zona Vercel son:

| Nombre relativo a orden-fix.com.ar | Tipo | Valor | TTL / prioridad |
| --- | --- | --- | --- |
| `resend._domainkey.cuenta` | TXT | Clave pública DKIM emitida por Resend; huella en el cierre B | 60 |
| `send.cuenta` | TXT | `v=spf1 include:amazonses.com ~all` | 60 |
| `send.cuenta` | MX | `feedback-smtp.sa-east-1.amazonses.com.` | 60 / 10 |

El MX pertenece al Return-Path de rebotes; no crea una casilla atendida. No se modificó
el MX raíz, DMARC, registros web, nameservers ni configuración de otros dominios.

Para el **corte C**:

1. Identificar el despliegue real de frontend y backend y comprobar que corresponde a
   estas ramas/cambios. El proyecto `order-fix` y su redirección ya existen: no duplicarlos
   ni asumir que su versión desplegada coincide con la versión local sin comprobarlo.
2. Configurar en el servicio backend del entorno `MAIL_ENABLED`, la clave de envío y
   el mismo `MAIL_FROM` verificado. No copiar secretos a Vercel/Vite ni a Git.
3. Alinear el origen HTTPS realmente usado con `APP_PUBLIC_URL` y `CORS_ORIGINS`;
   configurar `VITE_API_BASE_URL` con la API HTTPS del entorno. La redirección actual
   apunta a `www`, pero no se reemplaza la URL local por una URL pública sin probar el
   recorrido desplegado. `CorsConfig` consume la variable; no requiere editar Java.
4. Probar registro/verificación, reenvío y recuperación hasta consumir enlaces reales,
   con cuenta propia de prueba y permisos para esos envíos. La prueba del corte B usó
   texto sintético sin enlaces ni tokens, y no valida esos recorridos.

El correo de soporte y los canales de privacidad/legal requieren una recepción atendida;
verificar un remitente transaccional no crea ese circuito. Su definición y la identidad
registral siguen diferidas hasta después de MP y Email, por decisión del usuario.

## Cortes restantes

- **Email A:** configuración local, SMTP seguro y regresión de cuenta. Evidencia en el
  [plan del corte](../plans/2026-09-12-email-transaccional-implementation.md).
- **Email B cerrado:** dominio, DNS, entrega al servidor receptor y recepción confirmada por el titular.
- **Email C:** recorrido con frontend y backend de staging, enlaces HTTPS y operación del envío.

## Referencias oficiales consultadas

- [Resend SMTP](https://resend.com/docs/send-with-smtp).
- [Dominios y subdominios](https://resend.com/docs/dashboard/domains/introduction).
- [Registros Resend en Vercel](https://resend.com/docs/knowledge-base/vercel).
- [Errores y permiso de envío](https://www.resend.com/docs/api-reference/errors).
- [Agregar dominio al proyecto Vercel](https://vercel.com/docs/domains/working-with-domains/add-a-domain).
