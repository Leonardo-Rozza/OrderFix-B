# Avisos de cierre y restauración por email

## Alcance aprobado

El titular recibe un aviso de la operación de cierre o recuperación registrada. El
usuario aprobó conectar estos avisos al correo existente, conservando reintentos
seguros e incertidumbre. Este corte no habilita el cierre público, no envía mensajes
reales, no integra MP ni declara terminado el borrado de identidad/evidencia.

## Decisiones

1. Reutilizar `JavaMailSender`, remitente y SMTP existentes con un adaptador específico
   que devuelve ACCEPTED, RETRYABLE o UNCERTAIN. `EmailSender` es best effort y oculta
   los fallos; envolverlo acreditaría éxitos falsos. Cambiar su contrato transversal
   afectaría alta y recuperación, por lo que se conserva intacto. Otro proveedor o
   una cola nueva no aportan valor a este corte.
2. El efecto durable V34 sigue siendo la autoridad. Reclamar con REQUIRES_NEW y
   READ_COMMITTED; resolver efecto, operación, historial, titular ADMIN activo con
   email verificado, taller y lease exactos en dos lecturas breves independientes.
   Volver a validar después de preparar MIME y antes de SMTP. No guardar correo ni
   cuerpo en la cola; no exigir cierre actual para avisos históricos tras recuperar.
3. Enviar fuera de transacciones. Un retorno normal de SMTP acredita aceptación del
   transporte, nunca entrega a Recibidos. Reintentar sólo fallos previos al intento
   de envío. Toda excepción desde `send` es incierta. Una confirmación SQL perdida o
   lease vencida tampoco permite reenviar. No existe atomicidad entre SQL y SMTP ni
   promesa de exactly-once; revisión de avisos inciertos queda pendiente.
4. Puerto interno incorpora token y vencimiento del lease. Worker ofrece una entrada
   exclusiva de notificaciones; el scheduler no descubre ni invoca el puerto MP.
   Dos avisos por ronda, demora inicial/entre rondas de 60 segundos, sin superposición
   en la misma instancia. Flags separados y apagados por defecto; requieren mail
   habilitado. El límite es por instancia. SMTP mantiene sus timeouts por operación,
   no un deadline total ni cancelación garantizada de una operación ya iniciada.
5. Dos plantillas HTML/texto en español, estilo de emails de cuenta, fechas de
   Argentina. Contenido histórico y enlace estático a Cuenta para consultar el estado
   actual. Sin tokens, datos operativos, tracking, promesas de borrado total, pagos o
   reactivación de suscripciones. Enlace HTTPS; HTTP sólo loopback para laboratorio.

## Implementación y validación

- [x] Puerto/worker: lease y entrada sólo avisos.
- [x] Adaptador SMTP: pertenencia, destinatario único, validación y resultados.
- [x] Presentación y scheduler opt-in; documentar configuración/operación.
- [x] Pruebas focalizadas: MIME, defaults apagados, PG16, concurrencia, reintento
      previo al envío, fallo incierto, ACK perdido, lease vencido y SMTP loopback.
- [x] Revisar cambios y preservar V27–V37/frontend; corte preparado para commit atómico sin push.

Las pruebas usan únicamente destinatarios sintéticos y transporte controlado/local.
No leer secretos, no iniciar la app habitual. Validación integral previa aprobada en
4adce86; este corte acota las regresiones a cierre/email sin cambiar SQL congelado ni
el transporte de alta/recuperación. Registrar resultados efectivos al cerrar.


## Resultado comprobado

Implementación limitada al backend: adaptador SMTP con preflight de destinatario/
pertenencia/lease, dos plantillas, entrada de worker sólo avisos y scheduler opt-in.
Alta/recuperación conservan su transporte y contrato previos. Sin endpoint, cambios
en V27–V37, nuevos permisos SQL ni cambios del frontend.

Validación focalizada con Java 21:

- `-Dtest=WorkshopClosureEmailTemplateTest,AccountEmailTemplateTest,SmtpEmailSenderTest,EmailConfigurationTest,MercadoPagoClosureStateTest test`:
  55 pruebas aprobadas.
- `-Dtest=ClosureNotificationConfigurationTest,WorkshopClosureNotificationSchedulerTest -Dit.test=ClosureSmtpNotificationIT,WorkshopClosureEffectsIT,MercadoPagoClosureIT verify`:
  16 unitarias y 78 de integración aprobadas; comprobación de artefactos sin secretos
  aprobada. Las 32 de la nueva IT usan PostgreSQL 16.14, puerto de DB ligado a loopback
  y una aceptación real de un servidor SMTP sintético en `127.0.0.1`.
- Total: **149 casos distintos, 0 fallos, 0 errores, 0 omitidos**. Se comprobaron
  MIME UTF-8 texto/HTML, destinatario único, falsificación/replay, vínculo histórico
  tras restaurar, backoff y nueva lease, no reenvío después de posible aceptación,
  rollback SQL posterior al envío, dos workers, expiración antes/durante SMTP,
  revocación del destinatario durante preparación y aislamiento del puerto MP.
- Revisión independiente sin hallazgos materiales. Migraciones congeladas verificadas
  por SHA-256; los 179 archivos no versionados del frontend y su estado se preservan.

No hubo entrega a proveedores, importación de secretos, despliegue ni push. Los flags
permanecen apagados. No se repitió clean verify completo: este cambio no modifica el
contrato de los emisores de alta/recuperación ni otras superficies; el integral de
4adce86 está documentado en el corte anterior. Este corte no acredita entrega en
Gmail ni resolución de avisos inciertos; tampoco completa la baja integral del taller.


Revisión visual: cuatro capturas del HTML real compilado (cierre/restauración a
900 px y 320 px), con texto y CTA legibles y sin desbordamiento. Chromium local no
realizó solicitudes externas; las plantillas no incluyen assets remotos. Este
preview no reemplaza una comprobación en Gmail/Outlook. Evidencia de pruebas,
preservación y previews en `/private/tmp/ordenfix-closure-email-20260919/`.
