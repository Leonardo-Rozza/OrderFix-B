# Email — entrega real y recorridos locales

Fecha: 2026-09-19. Baselines: backend `d67bcc8`, frontend `fc8ba0e`.
Estado: **prueba local con entrega real cerrada**. Email C desplegado sigue pendiente.

## Alcance y autorización

El titular autorizó expresamente dos correos a su casilla propia: uno de confirmación
con asunto «Confirmá tu email de OrdenFix» y otro de recuperación con asunto
«Restablecer tu contraseña de OrdenFix». Remitente: OrdenFix
<notificaciones@cuenta.orden-fix.com.ar>. Se usó una cuenta descartable llamada
«Prueba local OrdenFix», sin datos de clientes. La autorización anterior del 12/09
no se reutilizó; los dos envíos corresponden a la confirmación «Si, dale» de este corte.

La preparación comprobó autenticación SMTP con STARTTLS y certificado validado,
sin enviar. Tras la autorización se ejecutaron exactamente dos intentos, ambos
aceptados por SMTP, sin reintentos, fallos inciertos ni otros destinatarios.

## Entorno y método

Se reutilizó el fixture de registro con PostgreSQL 16 descartable, los cinco roles
restringidos verificados y Boot real en un puerto loopback efímero. Frontend real
en 127.0.0.1:5175; DevTools restart desactivado. La base habitual permaneció intacta.
No se modificó código productivo, configuración persistente ni migraciones.

Un harness temporal seleccionó sólo propiedades SMTP del archivo privado en memoria,
sin importar base, MP o fotos. El adaptador general quedó apagado; el delegado
controlado reutilizó AccountEmailTemplate y SmtpEmailSender productivos. Admitió sólo
el destinatario y los dos asuntos autorizados y reservó cada intento antes del I/O.
La aceptación se observó al retornar JavaMailSender real, separada del retorno de
SmtpEmailSender, que absorbe errores. No hubo reinicios después de un intento SMTP.

Las etapas de navegador usaron la UI, HTTP, JWT y PostgreSQL reales. Para consumir
los enlaces se leyó el contenido del mensaje recibido mediante Gmail, verificando
origen local, ruta y coincidencia entre CTA, alternativa HTML y texto. No se usaron
los enlaces de la captura local de respaldo. Las claves eran aleatorias y exclusivas
de la cuenta de prueba; nunca se modificó la contraseña de Gmail.

## Resultados observados

| Paso | Resultado | Hora Argentina |
| --- | --- | --- |
| Alta por UI | 201, sesión y perfil con email pendiente | 15:41:15 |
| Confirmación recibida | Gmail INBOX; SPF y DKIM de cuenta.orden-fix.com.ar aprobados | 15:41:16 |
| Consumo del enlace recibido | 200, perfil verificado y acceso al inicio | 15:42:32 |
| Solicitud de recuperación por UI | 200, respuesta genérica y segundo correo | 15:43:21 |
| Recuperación recibida | Gmail INBOX; SPF y DKIM de cuenta.orden-fix.com.ar aprobados | 15:43:21 |
| Consumo y nuevo ingreso | Reset 200, login 200 y perfil verificado | 15:44:31 |

Ambos mensajes contienen multipart/alternative con HTML y texto. Gmail API confirmó
INBOX y la revisión visual se hizo dentro de Gmail web en Chrome: marca, tarjeta,
botón, texto, plazo y alternativa legibles. La recuperación se revisó hasta el pie.
Gmail mostró una sugerencia automática de traducción aunque el contenido está en
español; se conservó el mensaje original, sin activar traducción. No se observó un
resultado DMARC en Authentication-Results, por lo que no se declara comprobado.

Las cuatro etapas generaron capturas de la aplicación en 1280 y 320 px, sin
desbordamiento horizontal. Eso no equivale a probar la app móvil de Gmail, otros
clientes o sus modos oscuros. Las pruebas previas de uso único, reemplazo y revocación
conservan su evidencia; no se cuentan como nuevas ejecuciones de este ensayo.

IDs Gmail, sin enlaces de autenticación:
- Confirmación: `1a0baf9087200b56`.
- Recuperación: `1a0bafaf390d85ef`.

## Cierre y límites

Las cuatro etapas de los dos recorridos aprobaron en una única ejecución autorizada:
register 201, verify 200, resetrequest 200 y resetconsume 200, con login 200. Contador final:
SMTP_ATTEMPTS=2, SMTP_ACCEPTED=2, SMTP_UNCERTAIN=0, BLOCKED=0, estado STOPPED.
Se retiró la admisión consumida, se cerraron Boot/Vite y el PostgreSQL descartable.
Los puertos habituales siguen apagados. Los enlaces ya fueron consumidos y su base
fue descartada; no sirven como acceso permanente a una cuenta.

Los artefactos privados quedan fuera de Git en
`/private/tmp/ordenfix-email-live-20260919`: fuente temporal, compilados, resultados
por etapa, recibos sin tokens, HTML recibido y capturas. Archivos con tokens/sesión
sintéticos tienen permisos 600 dentro de directorio 700; no hay credenciales SMTP en
los artefactos. No se versionan correos completos ni contraseñas. El harness temporal
no es un runner durable; su cuota es por proceso y no debe reiniciarse para reenviar.

No se repitió clean verify: no cambió runtime productivo. Se preservan V27–V34 y los
179 archivos frontend ajenos no versionados. Un commit documental atómico por repo,
sin push. Email C conserva HTTPS, configuración del despliegue y prueba desde otro
dispositivo; este resultado completa la entrega real desde local.
