# Diseño de endurecimiento para producción

Fecha: 2026-08-13

## Objetivo

Preparar el backend de OrdenFix para operar suscripciones PRO con Mercado Pago sin poner en riesgo secretos, historial comercial, stock ni aislamiento entre talleres. El despliegue con credenciales productivas queda bloqueado hasta que las invariantes y pruebas de salida de este documento estén verificadas.

## Estrategia elegida

Se trabajará en entregas incrementales y compatibles, con una verificación completa al final de cada corte:

1. Corregir los riesgos P0 de secretos y destrucción de datos.
2. Separar el derecho de uso PRO del nombre comercial del plan.
3. Convertir Mercado Pago en un flujo idempotente, auditable y reconciliable.
4. Endurecer autenticación, endpoints públicos y tratamiento de datos sensibles.
5. Probar esquema, concurrencia, contratos y recorridos funcionales sobre infraestructura equivalente a producción.

Se descarta un cambio total en una sola entrega porque mezclaría migraciones, autorización y cobros sin puntos seguros de rollback. También se descarta limitarse a parches locales: resolvería los síntomas actuales pero seguiría sin existir un libro de eventos/pagos para conciliación.

## Invariantes

- Ningún secreto puede residir en el classpath, JAR, imagen o repositorio.
- Un cliente, equipo, reparación o cobro con historia comercial no se elimina en cascada. La baja debe preservarlo o rechazarse explícitamente.
- El stock solo cambia a través de una operación de dominio transaccional y nunca como efecto lateral invisible de una cascada JPA/SQL.
- `plan=PRO` no concede funciones por sí solo. El acceso requiere un entitlement vigente derivado de un estado de facturación permitido.
- Todo checkout, notificación y pago externo es idempotente. Los duplicados no crean suscripciones ni aplican transiciones dos veces.
- La notificación se persiste antes de contestar `2xx`; el procesamiento puede reintentarse y deja motivo del último error.
- Los identificadores de Mercado Pago y eventos financieros nunca se borran: quedan como historial de auditoría.
- Las transiciones se calculan con fechas UTC provistas por un `Clock` inyectable y con importes decimales exactos.
- Ningún token, PIN, patrón, contraseña o cuerpo que los contenga se escribe en logs.
- Toda consulta o mutación de negocio prueba pertenencia al taller autenticado.

## Arquitectura de suscripción

Se mantiene inicialmente el flujo de preapproval sin plan asociado para no cambiar el checkout del frontend. La suscripción local conserva:

- plan comercial;
- estado de facturación;
- estado de entitlement;
- período vigente y próxima fecha informada por el proveedor;
- vínculo externo actual sin eliminar vínculos históricos.

Se agregan dos libros append-only:

- `subscription_payment`: un registro por cobro recurrente, con importe, moneda, estado y referencias externas únicas;
- `payment_event`: bandeja durable de webhooks, con identificador deduplicable, fecha de recepción, estado de proceso, cantidad de intentos y error sanitizado.

El checkout toma un lock de la suscripción. Si ya existe un checkout pendiente y reutilizable, devuelve el mismo resultado; si hay una suscripción activa, responde `409`. La referencia externa es opaca y única. Al consultar Mercado Pago se validan vendedor/aplicación, referencia, moneda e importe antes de mutar el dominio.

Los estados `authorized`, `paused`, `canceled` y los cobros aprobados, rechazados, reintentados, reembolsados o contracargados producen transiciones explícitas. El estado remoto desconocido se registra sin conceder acceso. Un proceso programado reconcilia suscripciones y pagos que no recibieron eventos.

## API y errores

Se conservan los endpoints existentes mientras no sea necesaria una ruptura. Las mutaciones esperables usan errores tipados y el formato global actual:

- `400/422`: entrada o firma mal formada;
- `401/403`: autenticación o permiso;
- `404`: recurso ausente dentro del tenant;
- `409`: transición o checkout incompatible con el estado actual;
- `429`: límite de solicitudes;
- `502/503`: proveedor externo rechazó la operación o no está disponible.

Las llamadas externas tienen timeouts finitos. Solo se reintentan fallas transitorias y operaciones idempotentes. Los mensajes al cliente no exponen cuerpos completos del proveedor ni credenciales; el log operativo conserva correlación y códigos sanitizados.

## Seguridad

- Configuración sensible exclusivamente por variables de entorno o un gestor de secretos montado fuera del artefacto.
- JWT con audience, identificador único y vida corta; la evolución a refresh tokens rotativos se realiza sin incluir datos sensibles en claims. El taller del claim se contrasta con el usuario activo.
- Rate limiting diferenciado para login, registro, recuperación, seguimiento público y webhook.
- Validación de firma de webhook con comparación constante, tolerancia temporal acotada y defensa contra replay.
- PIN/patrón de equipos cifrado en reposo, accesible solo cuando el rol y el caso de uso lo requieren, con borrado al entregar o por retención.
- Restablecer contraseña revoca sesiones vigentes mediante una versión de token o mecanismo equivalente.

## Integridad de datos

Las cascadas `Cliente -> Equipo -> Reparacion -> Cobro` se eliminan. La primera entrega rechaza la baja de clientes/equipos con dependencias mediante `409`, que es la opción más segura y compatible con el modelo actual. Una futura baja lógica podrá ocultar registros sin perderlos, pero no es requisito para habilitar pagos.

Las claves foráneas financieras pasan de `ON DELETE CASCADE` a `RESTRICT/NO ACTION`. Las migraciones deben funcionar desde una base vacía y desde el esquema actualmente desplegado.

## Pruebas y puertas de salida

1. La suite unitaria e integrada existente permanece verde.
2. PostgreSQL efímero ejecuta todas las migraciones Flyway desde cero y valida una actualización representativa.
3. Pruebas de integridad demuestran que una baja no elimina reparaciones/cobros ni altera stock.
4. Pruebas de contrato de Mercado Pago cubren éxito, rechazo, timeout, reintento, duplicado, replay y eventos fuera de orden.
5. Pruebas de concurrencia cubren doble checkout, numeración, límites FREE y stock.
6. Matriz endpoint x rol x tenant cubre las operaciones de negocio.
7. El JAR y la imagen se inspeccionan automáticamente para impedir archivos o patrones de secretos.
8. Sandbox de Mercado Pago valida alta, cobro aprobado/rechazado, pausa, cancelación y reconciliación.
9. Solo después se permite un smoke test productivo con importe y cuenta controlados.

## Entorno legal y fiscal

El trabajo técnico preparará evidencia versionada de aceptación, política de privacidad, retención y exportación/baja. La puesta en producción requiere validación profesional argentina de:

- términos SaaS, renovación, precio, impuestos, cancelación y reembolsos;
- roles y obligaciones bajo la Ley 25.326, transferencias internacionales y acuerdos con encargados;
- circuito de factura electrónica/nota de crédito en ARCA por cada cobro;
- aplicabilidad de normativa de defensa del consumidor y mecanismo visible de baja;
- textos y evidencia de consentimiento para almacenar información de acceso a dispositivos.

La evidencia mínima de aceptación guarda versión/hash del documento, usuario, taller, instante UTC, IP y user-agent. Los documentos aceptados no se sobrescriben.

## Operación y rollback

Las migraciones son aditivas antes de cambiar lecturas; el código puede operar temporalmente con los campos nuevos vacíos. Mercado Pago queda detrás de su flag hasta completar sandbox. Ante incidentes se deshabilitan nuevos checkouts sin dejar de recibir/persistir webhooks y reconciliar cobros existentes.

