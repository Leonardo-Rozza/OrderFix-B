# Email — Corte B: dominio y entrega controlada

Fecha: 2026-09-12. Baselines backend `93860d4`, frontend `6f5bfb8`.
Estado: dominio Verified, DNS y entrega al servidor receptor acreditados; pendiente
confirmación visual de Recibidos/Spam por el titular. Corte C conserva los recorridos
con enlaces reales en staging.

## Alcance y autorización

El usuario pidió continuar con Email B y autorizó expresamente publicar los tres
registros DKIM/SPF/MX en Vercel y enviar un único correo de prueba desde
`notificaciones@cuenta.orden-fix.com.ar` a su casilla Gmail. La autorización identifica
asunto y texto exactos. Se ejecutó un solo envío, sin reintentos y sin datos de clientes.
No se crearon nuevas credenciales ni se ampliaron permisos de la API key.

## Cambios externos y locales

- Resend: creado `cuenta.orden-fix.com.ar`, región São Paulo (`sa-east-1`), ID
  `6ad6313e-e713-48b0-8dff-bb82e51e71fd`. El panel confirmó **Verified** a las 10:11 -03.
  Envío habilitado, recepción deshabilitada y tracking sin configurar.
- Vercel: agregados manualmente los tres registros de la tabla. No se instaló la
  integración automática, no se delegó acceso DNS a Resend ni se tocaron otros registros.
- Backend: sólo `MAIL_FROM` del archivo privado ignorado pasa de `onboarding@resend.dev`
  a `OrdenFix <notificaciones@cuenta.orden-fix.com.ar>`. La clave se preserva y se usa
  desde memoria. No cambia código productivo, migraciones ni endpoints.
- La política de entrega Resend → destinatario conserva `Opportunistic`; STARTTLS
  obligatorio del corte A protege backend → Resend. No confundir ambos tramos.

| Nombre relativo | Tipo | Valor | TTL | Prioridad |
| --- | --- | --- | --- | --- |
| `resend._domainkey.cuenta` | TXT | Clave pública DKIM generada por Resend | 60 | — |
| `send.cuenta` | TXT | `v=spf1 include:amazonses.com ~all` | 60 | — |
| `send.cuenta` | MX | `feedback-smtp.sa-east-1.amazonses.com.` | 60 | 10 |

Huella SHA-256 del contenido TXT DKIM completo, incluyendo `p=` y sin comillas DNS:
`e0fc3596a9e6914f53ed42f1811a426c6fdeb03d7f61944c69f2ab29fb09e22d`.
Los tres registros se contrastaron con la UI de Resend, la tabla guardada en Vercel
más consultas DNS públicas; los valores coinciden.

```sh
dig +short resend._domainkey.cuenta.orden-fix.com.ar TXT
dig +short send.cuenta.orden-fix.com.ar TXT
dig +short send.cuenta.orden-fix.com.ar MX
```

## Prueba del adaptador real

Se utilizó un harness temporal fuera de ambos repositorios, Java 21, las clases
compiladas del corte A y únicamente `MailSenderAutoConfiguration`. Se cargó el
contrato público y una selección MAIL_*/API_KEY_RESEND del archivo privado en memoria;
no se ejecutó component scan, main, datasource ni Flyway. No se agregó una ruta HTTP.

El harness primero comprobó configuración sin conectar ni enviar. Para el envío,
un delegado observó el retorno exitoso de `JavaMailSender.send`; `SmtpEmailSender`
retorna void y absorbe errores, por lo que terminar sin excepción no bastaría como
prueba. Resultado del único intento autorizado: `SMTP_ACCEPTED=1`, sin reintentos.
No se habilitaron logs SMTP ni se imprimieron claves o errores crudos del proveedor.

Mensaje:

- Asunto: **OrdenFix — prueba de correo**.
- Cuerpo: “Esta es una prueba de envío de OrdenFix. No requiere ninguna acción ni
  contiene datos de clientes.” Se envía como un párrafo HTML UTF-8.
- Resend ID: `78e024f6-261e-4269-a464-05f2f9472706`.
- Panel: **Sent y Delivered**, 2026-09-12 10:12 -03:00; remitente y preview coinciden
  con lo autorizado. [Evidencia en Resend](https://resend.com/emails/78e024f6-261e-4269-a464-05f2f9472706).
- Delivered acredita la aceptación por el servidor receptor. El titular debe confirmar
  ubicación en Recibidos/Spam y lectura; esa respuesta todavía no se recibió.

No se enviaron tokens ni se consumieron enlaces de cuenta. El corte C comprobará
los recorridos de registro/verificación/recuperación desde el entorno real.

## Hallazgo sobre la web

Vercel muestra ambos dominios conectados al proyecto **order-fix**, con el raíz
redirigido a `www.orden-fix.com.ar` y certificados automáticos vigentes en el panel.
No se cambió esa configuración. Se conserva pendiente verificar repositorio/rama,
versión desplegada, API destino y recorrido real. No hubo push ni deploy.

## Validación y límites

- DNS público, estado Verified y evento Delivered comprobados con servicios reales.
- Archivo privado ignorado, otras propiedades preservadas y secretos fuera del diff.
- Revisión de documentación y enlaces. No se repite Maven ni frontend runtime porque
  no cambió código productivo; las 61 pruebas focalizadas del corte A son su evidencia
  anterior, no una ejecución nueva de este corte.
- La configuración del backend desplegado, enlaces HTTPS, identidad/contactos,
  recepción atendida de soporte, MP y salida pública no se acreditan con esta prueba.

Referencias: [guía operativa](../operations/email.md),
[corte A](2026-09-12-email-transaccional-implementation.md),
[registros Resend en Vercel](https://resend.com/docs/knowledge-base/vercel).
