# Acta del release candidate de endurecimiento del backend

Fecha: 2026-08-20

Rama: `codex/backend-hardening-release`
Estado técnico: **apto para staging; Mercado Pago y su checkout continúan apagados por defecto**.

## Objetivo del corte

Este release candidate consolida el endurecimiento previo al lanzamiento de suscripciones: integridad
de datos, seguridad de autenticación y secretos, credenciales de dispositivos, entitlements, cobros
recurrentes de Mercado Pago, límites de endpoints públicos, migraciones reales y documentación de
operación/frontend/legal.

## Resultado funcional

- Las bajas de clientes y equipos ya no eliminan en cascada reparaciones, cobros ni historial. Las
  dependencias se rechazan con un conflicto explícito.
- El acceso PRO se decide mediante una única política: `TRIAL` y `PRO + ACTIVA` habilitan capacidades;
  `FREE`, `VENCIDA` y `CANCELADA` no las habilitan.
- Los JWT validan firma, issuer, audience, usuario, tenant y `tokenVersion`; un cambio de contraseña
  revoca los access tokens anteriores.
- PIN y patrón de desbloqueo se almacenan cifrados con AES-256-GCM y solo se devuelven en el detalle
  autenticado de la reparación. No aparecen en altas, ediciones, listados ni seguimiento público.
- Los endpoints públicos sensibles tienen rate limit y publican `Retry-After` al responder `429`.
- Maven y Docker excluyen y verifican que no entren archivos de secretos o configuración local al JAR.
- Mercado Pago mantiene dos interruptores independientes:
  - `MP_ENABLED=false` desactiva toda la integración.
  - `MP_CHECKOUT_ENABLED=false` impide nuevas altas sin detener webhooks, cancelaciones ni conciliación.
- El webhook valida firma, registra primero un inbox durable, responde sin esperar las consultas remotas
  y procesa de forma asíncrona con reintentos acotados.
- Los tópicos `subscription_preapproval`, `subscription_authorized_payment` y `payment` convergen en un
  único mutador idempotente del estado de suscripción.
- La cancelación con un preapproval externo es fail-closed: solo modifica el plan local después de que
  Mercado Pago confirma la baja. Sin integración activa o ante error remoto devuelve `502` sin degradar
  el estado local.
- Los eventos de una misma factura se ordenan por la actualización del proveedor; los ciclos distintos
  se ordenan por su fecha de facturación. Un evento viejo no puede sobrescribir un refund nuevo ni
  reactivar el acceso.
- Refunds y chargebacks se clasifican conservadoramente. Un parcial requiere revisión; un chargeback no
  queda oculto por el monto reintegrado; `charged_back/reimbursed` se interpreta como resolución favorable
  al vendedor solo si corresponde al ciclo vigente.

## Evidencia de verificación

Comando principal:

```bash
JAVA_HOME=<JDK_21> ./mvnw --batch-mode --no-transfer-progress clean verify
```

Resultado del 2026-08-20:

- Surefire: **128 pruebas**, 0 fallos, 0 errores, 0 omitidas.
- Failsafe/Testcontainers: **1 prueba PostgreSQL**, 0 fallos, 0 errores, 0 omitidas.
- PostgreSQL: versión 16.14, esquema inicialmente vacío.
- Flyway: validó y aplicó **V1–V20**.
- Hibernate: inició con `ddl-auto=validate` contra el esquema migrado.
- Verificación Maven anti-secretos: aprobada.
- Inspección independiente del JAR: sin `application-secret.properties` ni perfiles
  `application-local/dev/prod`.
- `git diff --check`: aprobado.
- Build Docker: **aprobado** con la etiqueta local `ordenfix-backend:rc`.
- Imagen Docker: usuario `appuser`, entrypoint Java 21, escaneo anti-secretos aprobado dentro del build.

## Configuración segura para el primer despliegue

1. Desplegar en staging con `MP_ENABLED=false` y `MP_CHECKOUT_ENABLED=false`.
2. Cargar secretos mediante el gestor del entorno, nunca dentro del repositorio o de la imagen.
3. Mantener estable `DEVICE_CREDENTIALS_ENCRYPTION_KEY`; perderla impide descifrar credenciales existentes.
4. Definir explícitamente `JWT_EXPIRATION`, issuer, audience y secreto JWT de al menos 32 bytes.
5. Habilitar Mercado Pago en dos pasos: primero `MP_ENABLED=true` con checkout apagado para validar
   webhooks/conciliación; luego `MP_CHECKOUT_ENABLED=true` después del go/no-go.
6. Ante una incidencia de cobro, apagar primero solo el checkout para conservar cancelaciones, eventos y
   conciliación.

La lista completa de variables y la secuencia operativa están en `DEPLOY.md` y
`docs/runbooks/mercadopago-release.md`.

## Puertas obligatorias antes de cobrar en producción

- Ejecutar en sandbox la matriz completa del runbook: alta, renovación, rechazo, cancelación, duplicados,
  eventos fuera de orden, refund total/parcial y los tres estados relevantes de chargeback.
- Validar el frontend contra `FRONTEND_INTEGRATION.md`, especialmente roles `ROLE_*`, `exp`, respuestas
  `402/403/409/429/502`, refresco del detalle y retorno desde Mercado Pago.
- Configurar URL HTTPS pública, firma de webhook, collector y application ID del ambiente correcto.
- Confirmar alertas sobre eventos `FAILED`, reintentos agotados, reconciliación y divergencias de estado.
- Probar backup y restauración de PostgreSQL, migración y rollback operativo antes del corte.
- Completar revisión profesional de términos, privacidad, defensa del consumidor, baja, facturación e
  impuestos según `docs/legal/READINESS-PLAN-AR.md`.
- Aprobar precio, moneda, textos comerciales y política de refunds. El valor técnico actual es ARS 24.900.

## Riesgos residuales aceptados para staging

- El monto y la moneda todavía se validan contra la configuración global. Antes de cambiar precio, lanzar
  promociones o soportar planes múltiples se debe persistir un snapshot inmutable por preapproval/ciclo.
- La cancelación es fail-closed, pero no posee todavía un outbox o estado durable `CANCEL_REQUESTED`; un
  corte entre la confirmación remota y el commit local requiere conciliación/reintento.
- Cancelación y webhook adquieren locks en distinto orden. El inbox reintenta el evento ante un deadlock,
  pero conviene uniformar el orden como mejora operativa P2.
- La semántica actual de autenticación puede responder `403` tanto para token inválido/revocado como para
  falta de rol; el frontend debe aplicar el contrato documentado hasta una eventual normalización a `401`.

## Decisión de release

- **Código backend:** candidato congelable y verificable.
- **Staging técnico:** autorizado con Mercado Pago apagado.
- **Sandbox Mercado Pago:** siguiente etapa, con checkout inicialmente apagado.
- **Cobros productivos:** no autorizados hasta completar todas las puertas anteriores y registrar el
  go/no-go operativo, frontend y legal.
