# Plan de implementación: release candidate y cierre P0 de Mercado Pago

Fecha: 2026-08-20  
Diseño aprobado: `docs/plans/2026-08-20-release-candidate-mp-p0-design.md`

## Resultado esperado

Un SHA revisable que contenga todo el hardening del backend, arranque con checkout apagado, procese
los tres eventos necesarios de suscripción (`subscription_preapproval`,
`subscription_authorized_payment` y `payment`), no permita una baja local mientras el cobro remoto
pueda seguir activo y pase el gate completo sobre PostgreSQL 16.

## Invariantes

- Ningún webhook concede PRO a partir de su payload: siempre se consulta al proveedor.
- `MercadoPagoSubscriptionStateService.applyAuthorizedPayment` continúa siendo el único camino que
  persiste facturas y modifica entitlement por un cobro.
- Una notificación ajena nunca concede acceso. Un fallo transitorio o una relación ambigua se reintenta
  de forma acotada y queda auditable.
- Si existe un preapproval remoto, la baja local solo ocurre después de confirmar la cancelación remota.
- `MP_CHECKOUT_ENABLED` es `false` salvo habilitación explícita.
- El build falla si no puede ejecutar PostgreSQL/Testcontainers o si el JAR contiene secretos.
- `.agents/`, secretos y configuraciones locales quedan fuera del commit y del contexto Docker.

## Fase 1 — Contrato `payment`

Archivos principales:

- `service/dto/pago/mercadopago/MercadoPagoPaymentResponse.java`: DTO mínimo para ID, collector,
  estado/detalle, importe, moneda, referencia, fecha de actualización y total reintegrado.
- `service/impl/MercadoPagoResponseValidator.java`: valida ID exacto, collector configurado y contrato
  monetario. Payment no exige `application_id` porque ese campo no está garantizado por la API.
- `service/impl/MercadoPagoService.java`: normaliza `payment` (y el alias defensivo `payments`), consulta
  `/v1/payments/{id}`, busca la única factura con `payment_id`, verifica la relación y delega al upsert
  existente. Red, `429`, `5xx`, búsqueda vacía o ambigua quedan `FAILED`; collector ajeno queda
  `IGNORED`.
- `MercadoPagoContractTests.java`: cubre éxito, duplicado, collector ajeno, importe/moneda alterados,
  búsqueda vacía/ambigua, relación incoherente y fallo remoto.

La búsqueda por `payment_id` evita usar `external_reference` como identificador único. En este corte el
producto sigue teniendo un único precio configurado; antes de soportar precios históricos o promociones
se agregará un snapshot inmutable de importe/moneda al vínculo local.

## Fase 2 — Cancelación fail-closed

En `MercadoPagoService.cancelarSuscripcion`:

1. si ya está cancelada, devolver sin red ni escritura;
2. si existe preapproval y MP está apagado, lanzar `PagoException` sin mutar estado;
3. si existe preapproval y MP está activo, ejecutar PUT remoto y recién después aplicar la baja local;
4. si no existe preapproval, permitir la baja local de cuentas manuales/legacy;
5. ante timeout o error remoto, conservar plan, estado e identificadores.

Pruebas: éxito remoto, MP apagado con vínculo, cuenta sin vínculo, cancelación repetida y error remoto.
`canceled` y `cancelled` se consideran terminales para idempotencia.

## Fase 3 — Defaults y empaquetado

- Cambiar los defaults de `mercadopago.checkout-enabled` y `MercadoPagoProperties.checkoutEnabled` a
  `false`; mantener `mercadopago.enabled=false`.
- Actualizar README, DEPLOY, FRONTEND y runbook con el mismo default y secuencia de habilitación.
- Dejar de ignorar DEPLOY y FRONTEND para que el contrato operativo quede versionado.
- Ampliar `.dockerignore` para `.env*`, `application-local/dev/prod.*`, `.agents/` y `.claude/`.
- Mantener la exclusión Maven y el gate del JAR para `application-secret.properties`.

## Fase 4 — CI y base real

- Quitar `disabledWithoutDocker=true` de `PostgresMigrationIT`: sin Docker el gate debe fallar, no saltar.
- Afirmar explícitamente que Flyway aplicó V17, V18, V19 y V20 y que no quedan migraciones pendientes.
- GitHub Actions usa Java 21, verifica Docker y ejecuta `./mvnw --batch-mode --no-transfer-progress clean verify`.
- El Dockerfile final debe consumir el artefacto ya verificado o repetir exactamente el mismo gate; nunca
  promover un build distinto con pruebas omitidas.

## Fase 5 — Contrato frontend y operación

- Documentar claims `ROLE_ADMIN`/`ROLE_USER`, expiración derivada de `exp` y logout ante autenticación
  inválida.
- Documentar `402`, `409`, `429` con `Retry-After` y `502`, además de bajas bloqueadas por historial.
- Aclarar que sandbox local de MP necesita URL HTTPS/túnel para `MP_BACK_URL` y webhook.
- Mantener checkout apagado durante despliegue, migración, smoke y recepción de webhooks; habilitarlo
  solo al aprobar la matriz sandbox.

## Orden de verificación

1. Tests focalizados MP y configuración.
2. Suite H2 completa.
3. `clean verify` con Docker disponible: Surefire, Failsafe/Testcontainers, PostgreSQL 16, Flyway
   V1–V20, `ddl-auto=validate` y escaneo ant-secreto.
4. Inspección independiente del JAR y del contexto Docker.
5. `git diff --check`, revisión del staging exacto y comprobación de que no se versionaron archivos
   locales, secretos ni `.agents/`.

## Commits y puerta de salida

- Documentación de diseño y plan.
- Hardening funcional y migraciones existentes.
- Cierre P0 MP con sus pruebas.
- CI, Docker y documentación operativa.

La rama solo queda lista para PR cuando todos los gates son verdes sobre el mismo árbol. Producción con
cobros permanece bloqueada hasta completar staging, frontend E2E, sandbox, observabilidad y revisión
legal/fiscal. V20 se ensaya primero sobre una copia anonimizada con backup restaurable y clave de cifrado
estable.
