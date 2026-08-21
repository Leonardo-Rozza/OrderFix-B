# Runbook de salida — Mercado Pago Suscripciones

Fecha: 2026-08-20
Integración: suscripción sin plan asociado (`preapproval`) + facturas autorizadas + conciliación
disparada por eventos `payment`.

## Estado seguro por defecto

- Mantener `MP_ENABLED=false` hasta completar todas las puertas de este documento.
- El checkout también inicia apagado: `MP_CHECKOUT_ENABLED=false` es el default seguro.
- Para detener altas sin perder eventos ni conciliación, mantener `MP_ENABLED=true` y usar `MP_CHECKOUT_ENABLED=false`.
- Nunca copiar tokens o secretos al repositorio, JAR, imagen, logs, tickets o capturas.

## Configuración obligatoria

| Variable | Regla |
|---|---|
| `MP_ENABLED` | `true` solo en sandbox aprobado y luego producción |
| `MP_CHECKOUT_ENABLED` | corte independiente de nuevas altas; default `false` |
| `MP_ACCESS_TOKEN` | secreto del ambiente correcto |
| `MP_WEBHOOK_SECRET` | secreto de Webhooks de la aplicación correcta |
| `MP_COLLECTOR_ID` | User ID esperado del vendedor |
| `MP_APPLICATION_ID` | Application ID esperado |
| `MP_AMOUNT` | precio contractual decimal positivo; hoy `24900` |
| `MP_CURRENCY` | hoy `ARS` |
| `MP_BACK_URL` | URL HTTPS del frontend del ambiente |
| `MP_CONNECT_TIMEOUT` / `MP_READ_TIMEOUT` | defaults `3s` / `8s` |
| `MP_WEBHOOK_TOLERANCE` | default `5m` |
| `MP_WEBHOOK_MAX_ATTEMPTS` | default `8` |
| `MP_RECONCILIATION_DELAY` | default `6h` |
| `MP_RECONCILIATION_BATCH_SIZE` | default `100` |

El arranque falla de forma segura si MP está activo y faltan credenciales, IDs de cuenta, importe o timeouts válidos.

## Panel de Mercado Pago

1. Usar una aplicación separada por ambiente y verificar propietario/collector.
2. Configurar `https://<backend>/api/pagos/webhook`.
3. Suscribir los tópicos `subscription_preapproval`, `subscription_authorized_payment` y `payment`.
4. Copiar la clave secreta al gestor de secretos del ambiente.
5. Confirmar que el proxy conserva `x-signature`, `x-request-id`, query `data.id` y el cuerpo sin reescribirlos.
6. Si se activa confianza en `X-Forwarded-For`, el proxy debe eliminar el header aportado por el cliente y establecer uno propio.

El API puede correr en `http://localhost` para pruebas sin MP. Para sandbox, el retorno del frontend y
el webhook del backend deben exponerse mediante URLs HTTPS públicas (túnel o staging); el proveedor no
puede acceder a direcciones `localhost`.

Referencias oficiales: [notificaciones de suscripciones](https://www.mercadopago.com.ar/developers/es/docs/subscriptions/additional-content/your-integrations/notifications), [validación de firma Webhooks](https://www.mercadopago.com.ar/developers/es/docs/checkout-bricks/additional-content/your-integrations/notifications/webhooks), [crear preapproval](https://www.mercadopago.com.ar/developers/es/reference/online-payments/subscriptions/create-preapproval/post) y [buscar facturas autorizadas](https://www.mercadopago.com.ar/developers/es/reference/online-payments/subscriptions/authorized-payment-search/get).

## Matriz de sandbox obligatoria

Ejecutar con usuarios y medios de pago de prueba de Mercado Pago. Guardar ID de corrida, timestamps UTC y evidencia sanitizada; nunca secretos ni datos completos de tarjetas.

| Caso | Resultado esperado |
|---|---|
| Alta pendiente | Un solo vínculo local, referencia opaca y URL HTTPS oficial |
| Doble click/concurrencia | Reutiliza checkout o responde conflicto; no crea dos vínculos vigentes |
| Respuesta con vendedor, app, referencia, importe o moneda ajenos | Rechazo sin conceder PRO |
| Firma ausente/alterada/vencida/replay | `401` o deduplicación; sin mutación de estado |
| Preapproval `authorized` | `PRO + ACTIVA`, próxima fecha tomada de MP |
| Preapproval `paused` | entitlement cerrado (`PRO + VENCIDA`) |
| Preapproval `canceled` | `FREE + ACTIVA`, IDs históricos conservados |
| Factura aprobada | factura local única y entitlement activo |
| Factura rechazada | factura/auditoría conservada y entitlement cerrado |
| Reintento posterior aprobado | misma factura actualizada, sin duplicado, entitlement reactivado |
| Evento genérico `payment` | valida el pago remoto y dispara conciliación; nunca confía en el payload del webhook |
| Reembolso total o parcial | entitlement cerrado y evento preservado para revisión |
| Contracargo `in_process`/`settled` | entitlement cerrado |
| Contracargo `reimbursed` favorable al vendedor | puede restaurar solo ese ciclo si sigue siendo el vigente |
| Evento duplicado | una sola aplicación |
| Estado viejo de la misma factura | no pisa su snapshot más reciente ni reabre PRO |
| Factura vieja actualizada tarde | se audita, pero no supera un ciclo posterior por usar `last_modified` |
| Timeout/error 5xx | queda reintentable con misma idempotency key o inbox durable |
| Webhook perdido | conciliación recupera preapproval y facturas |
| Cancelación repetida | idempotente y sin pérdida de auditoría |

## Verificación técnica previa

```bash
JAVA_HOME=/ruta/a/jdk-21 ./mvnw clean verify
jar tf target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar \
  | grep application-secret.properties
```

El primer comando debe terminar verde, incluida la prueba PostgreSQL/Testcontainers. El segundo no debe imprimir nada; `verify` también aplica una regla automática sobre el JAR.

## Observabilidad mínima

- Alertar eventos `FAILED` próximos al máximo de intentos.
- Alertar eventos `PROCESSING` recuperados por timeout: el ACK ocurre tras persistir y encolar, no
  después de esperar las consultas remotas.
- Alertar conciliaciones `FAILED` y ausencia prolongada de conciliaciones exitosas.
- Controlar divergencias entre suscripción local, preapproval y facturas autorizadas.
- Medir checkouts iniciados/completados, duplicados, cancelaciones, rechazos, refunds y chargebacks.
- Vigilar respuestas `429` del webhook y su `Retry-After`; ajustar edge/limitador solo con evidencia.
- Los logs deben conservar solo correlación y estado sanitizado; nunca token, secreto, payload completo, email, PIN o patrón.

Consultas operativas sugeridas (sin datos personales):

```sql
SELECT status, COUNT(*) FROM payment_events GROUP BY status;
SELECT last_reconciliation_status, COUNT(*)
FROM subscription_provider_links
WHERE provider = 'MERCADO_PAGO' AND is_current = TRUE
GROUP BY last_reconciliation_status;
```

## Rollback e incidente

1. Configurar `MP_CHECKOUT_ENABLED=false`; mantener `MP_ENABLED=true` para preservar eventos, bajas y conciliación.
2. No borrar filas de `payment_events`, `subscription_provider_links` ni `subscription_payments`.
3. Rotar el token/secret solo mediante el gestor de secretos y reinicio controlado.
4. Conciliar manualmente IDs afectados contra MP.
5. Si hubo cobro erróneo, coordinar reembolso/nota de crédito y comunicación; no corregirlo borrando registros.
6. Documentar causa, ventana, tenants afectados, transiciones y evidencia de reparación.

## Go / no-go

Solo habilitar producción cuando estén verdes:

- suite Java 21 + PostgreSQL + escaneo de secretos;
- matriz sandbox completa;
- HTTPS/proxy/rate limiting/alertas;
- precio y límites consistentes en contrato, UI, backend y despliegue;
- snapshot de precio/moneda implementado antes de ofrecer promociones o cambiar el precio de vínculos
  existentes (esta release valida contra el único precio global configurado);
- facturación/nota de crédito aprobadas por contador;
- términos, privacidad, DPA, baja y reembolsos aprobados por abogado;
- responsable de guardia y rollback ensayado.

Esta documentación de Mercado Pago se actualizó el 2026-08-20 y debe revisarse nuevamente antes del lanzamiento.
