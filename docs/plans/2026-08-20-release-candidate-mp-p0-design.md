# Release candidate y cierre P0 de Mercado Pago

Fecha: 2026-08-20  
Estado: aprobado para implementación

## Objetivo

Convertir el hardening ya verificado localmente en una release reproducible y cerrar los riesgos que
todavía impiden probar suscripciones reales en sandbox. El corte no agrega funcionalidad comercial:
preserva el estado actual, completa el contrato de notificaciones, evita cancelaciones divergentes y
deja los checkouts apagados hasta una habilitación deliberada.

## Alcance

- Crear una rama de release y versionar código, migraciones V17–V20, pruebas, CI y runbooks.
- Procesar el tópico oficial `payment` sin crear una segunda máquina de estados de suscripción.
- Hacer fail-closed la cancelación cuando existe un preapproval remoto y MP está deshabilitado.
- Cambiar el default de `MP_CHECKOUT_ENABLED` a `false`.
- Proteger el contexto Docker de configuraciones locales y promover un artefacto verificable.
- Ejecutar pruebas focalizadas y `clean verify` con PostgreSQL/Testcontainers.

Quedan fuera de este corte: integración ARCA, clickwrap legal, frontend, sandbox real, métricas externas,
outbox general de comandos a proveedores y coordinación multi-réplica.

## Alternativas evaluadas

### Evento `payment`

1. Ignorarlo y esperar la conciliación periódica. Es simple, pero retrasa refunds/chargebacks y no
   cumple el contrato de notificaciones recomendado por el proveedor.
2. **Usarlo como disparador validado de conciliación — elegido.** Se consulta el pago, se valida que
   pertenezca al collector/aplicación/moneda esperados, se resuelve el vínculo local y se refrescan el
   preapproval y sus facturas autorizadas. La lógica de entitlement continúa en un único servicio.
3. Mutar el plan directamente desde el recurso Payment. Tiene menor latencia, pero duplica reglas,
   puede crear dos registros por la misma factura y aumenta el riesgo de eventos fuera de orden.

### Cancelación

1. **Síncrona y fail-closed — elegida.** Si existe un preapproval no cancelado, solo se modifica el
   estado local después de cancelar remotamente. Con MP apagado o error remoto, se devuelve error sin
   mutar la suscripción. Sin vínculo remoto, la baja local sigue disponible.
2. Outbox durable de cancelación. Es el objetivo robusto a largo plazo, pero requiere migración,
   worker, estados intermedios y UX adicional.
3. Bajar localmente aunque MP esté apagado. Se descarta porque puede dejar cobros recurrentes activos.

### Estrategia de release

1. **Rama, commits temáticos, PR y CI obligatorio — elegida.** Conserva trazabilidad y permite revisar
   el diff grande por dominios.
2. Reimplementar desde `origin/main` en commits pequeños. Produce una historia prolija, pero arriesga
   perder o alterar cambios ya verificados.
3. Commit directo sobre `main`. No ofrece una puerta de revisión ni protege el SHA de producción.

## Diseño del procesamiento `payment`

1. El webhook valida HMAC y registra el evento en el inbox durable existente.
2. El worker consulta `GET /v1/payments/{paymentId}` con timeouts actuales.
3. Una respuesta tipada se valida antes de usarla: ID, collector, moneda, importe positivo y referencia
   local. Nunca se confía en el cuerpo recibido por webhook.
4. La referencia resuelve un `SubscriptionProviderLink` vigente. Si todavía no está completo, el evento
   queda fallido y usa el mecanismo de reintentos existente.
5. Con el preapproval del vínculo se ejecuta la conciliación de preapproval y authorized payments.
6. Refund, cancelación o chargeback modifican entitlement únicamente a través de
   `MercadoPagoSubscriptionStateService`, conservando cronología e idempotencia.

El evento genérico queda auditado en `payment_events`. No se persiste el payload completo ni se registran
emails, tokens, tarjetas o identificadores innecesarios.

## Diseño de cancelación

- `alreadyCanceled=true`: operación idempotente; se asegura el estado local cancelado.
- Preapproval presente + MP apagado: error operativo; cero cambios locales.
- Preapproval presente + MP activo: PUT remoto `status=canceled`; solo el éxito permite la baja local.
- Sin preapproval remoto: baja local permitida para cuentas legacy/manuales.
- Error o timeout remoto: `PagoException`, respuesta uniforme, identificadores sanitizados en logs y
  posibilidad de reintento del usuario.

## Estado seguro de checkout

`MP_ENABLED=false` y `MP_CHECKOUT_ENABLED=false` son los defaults. En staging/producción la secuencia es:

1. desplegar con ambos apagados;
2. configurar y habilitar MP manteniendo checkout apagado;
3. verificar webhook, firma, conciliación y alertas;
4. habilitar checkout solo después de completar la matriz sandbox.

## Pruebas y aceptación

- `payment` válido dispara conciliación y termina `PROCESSED`.
- Payment de collector, moneda, importe o referencia ajenos no concede acceso.
- Payment duplicado no duplica facturas ni transiciones.
- Refund/chargeback se refleja mediante la fuente autorizada y respeta la cronología.
- Con MP apagado y preapproval activo, cancelar falla y conserva plan, estado e IDs.
- Cancelación remota fallida conserva el estado local.
- Cancelación ya aplicada y cuenta sin vínculo remoto son idempotentes.
- El checkout está apagado sin variable explícita.
- CI ejecuta Java 21, PostgreSQL real, Flyway V1–V20 y escaneo del JAR sin skips.
- El SHA final pasa `clean verify`; el artefacto no contiene secretos ni configuración local.

## Rollout y rollback

El primer despliegue usa una réplica, backup restaurable y `MP_ENABLED=false`. V20 se ensaya sobre una
copia anonimizada de producción porque no permite volver al binario anterior sin restaurar esquema y
datos. Ante una incidencia MP se apaga `MP_CHECKOUT_ENABLED`, pero se mantienen webhooks y conciliación.

La producción con cobros continúa bloqueada hasta completar frontend E2E, sandbox, observabilidad y las
puertas legales/fiscales documentadas por separado.
