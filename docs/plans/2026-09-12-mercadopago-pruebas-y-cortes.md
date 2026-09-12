# Mercado Pago — prueba de la suscripción de OrdenFix

Fecha: 2026-09-12. Base revisada: backend `39a0890`, frontend `8547f3a`.
Estado: MP-A terminado; MP-B en curso. Token de cuenta de prueba verificado;
ciclo de suscripción con el proveedor pendiente.

## Alcance y cortes

El usuario pidió continuar con MP después del pulido A–D. Se verifica la integración
existente de suscripción mensual taller → OrdenFix: preapproval sin plan asociado,
creado `pending`, checkout hospedado y estado confirmado por el backend. Los cobros
taller → cliente siguen siendo registros manuales de operaciones externas.

| Corte | Trabajo | Cierre |
| --- | --- | --- |
| MP-A — preparación local | Revisar contrato, configuración sin revelar secretos, requisitos de laboratorio y pruebas existentes. | Diagnóstico y procedimiento versionados; pruebas focalizadas verdes. No acredita al proveedor. |
| MP-B — conexión y ciclo básico | Identificar vendedor/comprador de prueba, completar configuración y HTTPS; probar alta pendiente, autorización, retorno y cancelación repetida. | Una suscripción de prueba conciliada en MP y OrdenFix; alta pendiente no concede PRO, autorización confirmada sí, baja cancela remoto y local. Conservar IDs y evidencia sanitizada. |
| MP-C — eventos y recuperación | Completar la matriz del runbook: duplicados, rechazo/reintento, orden temporal, fallos, conciliación, reembolsos y contracargos. | Cada caso tiene evidencia y tipo de prueba explícitos. Si el proveedor no permite reproducir un caso, registrar esa limitación y resolver su aceptación; no marcarlo aprobado por un mock. |

Un commit atómico por corte y repositorio afectado, sin push. Se cambia código sólo
ante un bloqueo reproducible. No se agrega SDK de tarjetas, otro checkout ni una nueva
máquina de estados. El gate integral de lanzamiento y la identidad/alta legal conservan
su etapa posterior acordada; no son requisitos para esta preparación local.

## Hallazgos de MP-A

- Backend ya implementa checkout, firma, inbox, reintentos, conciliación y cancelación.
  Frontend ya tiene `/planes` y `/suscripcion/resultado`; el retorno consulta la
  suscripción del servidor, no concede PRO por parámetros recibidos en la URL.
- Inspección de presencia, sin mostrar valores: hay token guardado, pero su cuenta y
  entorno no están confirmados. No se encontraron secreto de webhook ni IDs de
  collector/aplicación en los dos archivos privados revisados o el entorno del proceso
  de diagnóstico. Esto no inspecciona configuración de un despliegue remoto.
- El archivo privado de recursos contiene `MP_ENABLED=true`; el archivo local
  contiene `enabled=false` y `checkout-enabled=false`. No se modificaron. No inferir
  la configuración de otro proceso sin conocer qué archivos y overrides carga.
- Encender MP también permite que los schedulers procesen vínculos/eventos previos,
  aunque checkout esté apagado. Preparar una base exclusiva para el ensayo, sin copiar
  vínculos ni datos de talleres reales.
- El email enviado como `payer_email` viene de `taller.emailContacto`. El taller
  sintético debe usar el comprador de prueba y permitir contratación: FREE o TRIAL,
  sin PRO/ACTIVA ni otro vínculo vigente que bloquee una nueva alta.
- Login, inicio del checkout y retorno deben usar el mismo origen HTTPS: la sesión del
  frontend está en almacenamiento del navegador por origen. Iniciar en localhost y
  retornar a otro dominio no conserva esa sesión. Probar recarga directa del retorno.
- La cobertura del retorno en navegador acredita por ahora la espera inicial sin
  conceder PRO. En MP-B comprobar también confirmación por GET, demora/reintento y
  error o sesión vencida; no dar esos casos por aprobados por lectura del código.

## Laboratorio para MP-B

1. Identificar aplicación, vendedor y comprador de prueba de Argentina. Confirmar
   pertenencia del token a esa cuenta/aplicación; el prefijo del token por sí solo no
   acredita el entorno. Conservar credenciales fuera del repositorio y del chat.
2. Preparar una instancia/backend y PostgreSQL dedicados, con fixtures sintéticos.
   Usar credenciales y permisos del runtime correspondiente, sin activar correo ni
   fotos para este ensayo. No publicar la instancia local habitual mediante un túnel.
3. Elegir el entorno HTTPS de prueba: staging aislado es preferible si ya existe;
   un túnel temporal a la instancia dedicada permite avanzar sin publicar las ramas.
   El usuario conserva push/merge. Registrar URLs exactas antes de iniciar el ensayo.
4. Completar `MP_ACCESS_TOKEN`, `MP_WEBHOOK_SECRET`, `MP_COLLECTOR_ID`,
   `MP_APPLICATION_ID`, `MP_BACK_URL=https://<frontend-prueba>/suscripcion/resultado`
   y webhook `https://<backend-prueba>/api/pagos/webhook`. Mantener importe/moneda
   vigentes del backend; CORS debe admitir sólo los orígenes previstos para el ensayo.
5. Verificar en esa aplicación la configuración que entrega
   `subscription_preapproval`, `subscription_authorized_payment` y `payment`.
   Acreditar headers de firma, query y recepción efectiva; ver la incertidumbre
   documental del runbook. No habilitar recepción sin firma ni inventar un campo de API.
6. Comenzar con ambos flags apagados. Con identidad de prueba, datos aislados y
   configuración completos, habilitar MP en esa instancia manteniendo altas apagadas;
   luego permitir sólo las altas del ensayo. Producción permanece apagada.
7. Ejecutar el ciclo desde el ADMIN del taller sintético, con comprador y tarjeta
   de prueba oficiales. Nunca usar una cuenta o tarjeta real para destrabar sandbox.
   Verificar estado remoto, GET de suscripción y persistencia local, no sólo pantalla.
8. Concluir cancelando las suscripciones creadas para la corrida, verificar la baja
   remota/local y cerrar el acceso temporal. Conservar historial; apagar MP sólo cuando
   no queden operaciones del ensayo por procesar.

## Evidencia local

El 2026-09-12 pasaron **53 casos**, cero fallos, errores o skips:

| Suite | Casos | Límite |
| --- | ---: | --- |
| MercadoPagoPropertiesTests | 3 | Validaciones locales de configuración. |
| MercadoPagoSignatureTests | 7 | Firmas sintéticas. |
| MercadoPagoContractTests | 28 | Transporte remoto simulado. |
| MercadoPagoPersistenceTests | 10 | Persistencia H2; no PostgreSQL ni proveedor real. |
| SubscriptionEntitlementPolicyTests | 5 | Política local de acceso. |

Comando ejecutado con Java 21 desde backend:

```bash
./mvnw -B \
  -Dtest=MercadoPagoPropertiesTests,MercadoPagoSignatureTests,MercadoPagoContractTests,MercadoPagoPersistenceTests,SubscriptionEntitlementPolicyTests \
  -Dspring.config.location=classpath:/application.properties \
  -Dspring.config.import= -Dspring.config.additional-location= \
  -Dmercadopago.enabled=false -Dmercadopago.checkout-enabled=false \
  -Dmail.enabled=false test
```

Log local efímero: `/private/tmp/ordenfix-mp-preparacion/pruebas.log`.
No se ejecutó clean verify ni se activaron proveedores. No se cambió runtime, base,
contratos, migraciones, configuración privada o frontend en MP-A.

## Fuentes y continuación

Revisión del código y [runbook de MP](../runbooks/mercadopago-release.md).
Documentación oficial consultada el 2026-09-12:
[preapproval pendiente](https://www.mercadopago.com.ar/developers/es/docs/subscriptions/integration-configuration/subscription-no-associated-plan/pending-payments),
[cuentas de prueba](https://www.mercadopago.com.ar/developers/es/docs/subscriptions/additional-content/your-integrations/test/accounts),
[compra de prueba](https://www.mercadopago.com.ar/developers/es/docs/subscriptions/integration-test/payment-approval)
y [Webhooks](https://www.mercadopago.com.ar/developers/es/docs/subscriptions/additional-content/your-integrations/notifications/webhooks).

## Avance MP-B — token verificado el 2026-09-12

El titular confirmó que la credencial guardada es de prueba y autorizó continuar.
Una consulta autenticada **GET `/users/me`** a `https://api.mercadopago.com` obtuvo
**HTTP 200**, `site_id=MLA` y presencia del tag `test_user`. El token guardado pertenece
a una cuenta de prueba argentina. La familia `APP_USR` es compatible con este resultado:
no se clasificó la cuenta sólo por el prefijo. Se conservaron únicamente estos datos
sanitizados; no se versionan credenciales, respuesta de perfil ni identificadores.

La respuesta incluye el ID de cuenta, pero no `application_id`. El panel de Mercado
Pago en el navegador disponible solicita iniciar sesión. Siguen pendientes
`MP_APPLICATION_ID` y `MP_WEBHOOK_SECRET`, identificar al comprador de prueba, las URLs HTTPS
y la instancia/base aisladas. No se habilitó MP, no se creó una suscripción ni se
realizaron cargos. El ciclo MP-B y la matriz MP-C permanecen abiertos.

En paralelo se reprodujo, con respuestas HTTP simuladas, un fallo del frontend al
volver por historial a un resultado confirmado después de simular una baja PRO → FREE:
conserva PRO en caché y no vuelve a consultar.
La corrección local del retorno se documenta en el frontend en
`docs/plans/2026-09-12-mercadopago-retorno-implementation.md`; no acredita por sí sola
el ciclo contra Mercado Pago.
