# Email transaccional — Corte A y continuación

Fecha: 2026-09-12. Baselines: backend `e7c203f`, frontend `4b46cd5`.
Ramas conservadas: `codex/lanzamiento-publico-backend` y
`codex/frontend-refactor-checkpoint`. Corte A cerrado localmente; B/C pendientes.

## Objetivo y alcance

El usuario guardó una nueva API key de Resend y confirmó propiedad de
`orden-fix.com.ar`. Se revisa la configuración, se corrigen las referencias para el
arranque local y se completa el transporte existente de verificación/reset. No se
crea otra integración REST, controller, cola ni funcionalidad de marketing.

La identidad registral y los canales definitivos de soporte, privacidad y legal
siguen al final, después de MP y Email. La suscripción MP es sólo del taller al SaaS;
no se agregan cobros del cliente ni validación de acreditaciones.

## Hallazgos y cambios del corte A

- `MAIL_PASSWORD` contenía un valor rechazado por Resend; `API_KEY_RESEND` era la
  nueva clave con permisos de envío. Se corrigió únicamente la referencia privada
  `MAIL_PASSWORD=${API_KEY_RESEND}`. En el contrato público se admite la nueva variable
  como fallback, conservando precedencia de `MAIL_PASSWORD` para otros entornos.
- Se quitó la propiedad circular `API_KEY_RESEND=${API_KEY_RESEND}` del archivo
  versionado: no conectaba la clave al transporte. También se quitó la línea
  `SPRING_CONFIG_IMPORT` con una ruta absoluta local; no funcionaba como variable
  de entorno dentro de ese archivo.
- `scripts/run-local.sh` importa explícitamente el archivo privado sólo al arrancar
  con Maven. Admite una ruta externa y falla antes del arranque si falta el archivo.
  Los tests/build no cargan esos secretos. No se ejecutó el arranque real en este corte.
- SMTP exige STARTTLS, identidad de certificado y timeouts 5/10/10 segundos. Resolver
  un bean SMTP fallido queda dentro de la misma frontera que absorbe errores sin
  registrar datos sensibles.
- Reset escapa nombre/enlace HTML y agenda la entrega después del commit capturando
  strings, sin retener entidades. Rollback o commit fallido no envían. Un error del
  adaptador no rompe la respuesta genérica. Verificación conserva su issuer y envío
  posterior al commit existentes.
- Se documenta dominio/DNS, configuración del remitente y la separación entre
  backend SMTP y frontend Vercel. CORS ya se configura con `CORS_ORIGINS`; no requiere
  editar la clase Java al agregar un dominio.

Se preservan el resto del archivo privado, activación local y remitente de prueba,
las migraciones V27/V28 y demás archivos ajenos. No se incluyen claves en Git y no
se realiza push, deploy, cambio DNS, borrado ni envío real de correo.

## Evidencia

- Nueva clave: SMTP `STARTTLS → AUTH → NOOP → QUIT`, estado `250`, sin MAIL/RCPT/DATA.
  La consulta HTTPS de dominios devuelve `restricted_api_key`: el permiso sólo permite
  envío y no acredita el estado de verificación del dominio. No se amplían permisos.
- DNS público: NS `ns1.vercel-dns.com` / `ns2.vercel-dns.com`, registros A presentes.
  No se acredita asignación al proyecto Vercel, certificado o deploy final.
- **61 tests Java aprobados**, cero fallas, errores u omitidos. Java 21, Maven serial,
  32,605 segundos; finalización 2026-09-12 09:56:47 -03:00.
- 20 casos nuevos: configuración SMTP (3), adaptador/MIME/errores/logs (7), scheduling
  de reset (10). Regresión de cuenta (6), scheduling de verificación (10), notifier (7)
  e issuer (18). Los callbacks de reset usan TransactionTemplate con manager local y
  repositorios dobles; los flujos HTTP existentes usan H2. No acredita PostgreSQL de staging.
- Launcher: sintaxis shell y cuatro escenarios con wrapper Maven simulado: ruta por
  defecto, externa relativa con espacios, archivo ausente y exceso de argumentos.
- Diff revisado, secretos excluidos y sin valores privados en archivos del corte.
  Frontend sólo cambia documentación: no requiere repetir suites de navegador.

```sh
env -u SPRING_CONFIG_IMPORT JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dtest=EmailConfigurationTest,SmtpEmailSenderTest,CuentaResetSchedulingTest,CuentaTests,CuentaVerificationSchedulingTest,AccountVerificationNotifierTest,AccountVerificationTokenIssuerTest test
sh -n scripts/run-local.sh
git diff --check
```

No se repite `clean verify`: el cambio está acotado a Email y cuenta y los focales
pasaron. La política conserva el gate integral para el cierre coordinado o fallos
que justifiquen ampliarlo.

## Continuación finita

- **Email B:** seleccionar/verificar dominio de envío, copiar registros Resend en
  Vercel, configurar remitente y acreditar entrega a una casilla autorizada.
- **Email C:** al disponer de staging, comprobar registro/verificación/recuperación
  con enlaces HTTPS reales, errores recuperables y configuración del entorno.

La [guía operativa](../operations/email.md) contiene los pasos y las fuentes oficiales.
SMTP aceptado no equivale a inbox. Se conserva envío síncrono sin reintento durable:
una caída después del commit puede requerir solicitar un enlace nuevo.
