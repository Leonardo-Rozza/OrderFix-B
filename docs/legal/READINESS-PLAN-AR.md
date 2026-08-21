# Plan de preparación legal y fiscal — Argentina

Fecha de revisión: 2026-08-13
Estado: borrador operativo; no habilita por sí solo la venta ni reemplaza asesoramiento profesional.

## Decisión de salida

Mercado Pago debe permanecer con `MP_ENABLED=false` en producción hasta que un abogado argentino y un contador confirmen por escrito los puntos marcados como puerta de salida. El primer dato a resolver es quién contrata y para qué usa OrdenFix: el producto parece B2B para talleres, pero una persona humana podría quedar alcanzada por normas de consumo según las circunstancias concretas.

## 1. Identidad, oferta y contrato

Responsables: fundador + abogado comercial.

- Definir razón social/persona contratante, CUIT, domicilio, jurisdicción, canales de soporte y reclamos.
- Versionar Términos del Servicio con: alcance del SaaS, disponibilidad, soporte, propiedad de los datos, licencia, usos prohibidos, responsabilidad, suspensión, terminación, exportación y ley/jurisdicción aplicable.
- Expresar antes de contratar precio final, moneda, impuestos, periodicidad mensual, renovación automática, fecha de débito, prueba gratuita, límites FREE/PRO, mora, política de reembolsos y tratamiento de contracargos.
- Publicar la intervención de Mercado Pago y separar con claridad el vínculo SaaS del servicio de pago del proveedor.
- Prohibir cambios silenciosos: cada versión debe conservar documento, versión, hash, fecha de vigencia y motivo del cambio.
- Implementar clickwrap no pre-marcado y guardar por aceptación: usuario, taller, versión/hash, instante UTC, IP y user-agent. Una aceptación histórica no se sobrescribe.

Puerta de salida: términos firmados por abogado, precio único en contrato/UI/backend y evidencia de aceptación probada.

## 2. Baja, arrepentimiento y renovaciones

Responsables: abogado de consumo + producto.

- Determinar y documentar si cada segmento es relación B2B o de consumo; no asumir que la forma societaria resuelve por sí sola la cuestión.
- Revisar la Disposición 954/2025: para comercialización a distancia exige mecanismos visibles de arrepentimiento y baja. La Disposición 3/2026 permite pasos razonables y habituales destinados exclusivamente a verificar identidad y seguridad.
- Diseñar en el frontend un camino de cancelación claro, confirmación con código de trámite, fecha efectiva y efecto sobre el acceso. No ocultarlo detrás de soporte.
- Definir contractualmente si la baja corta la renovación al final del período o de inmediato, qué ocurre con importes ya cobrados y cómo se emiten notas de crédito.
- Conservar solicitud, actor, instante, resultado local, resultado MP y código de confirmación sin borrar los identificadores financieros.

Puerta de salida: dictamen de aplicabilidad, flujo visible probado y texto de baja/reembolso consistente con Mercado Pago.

## 3. Privacidad y datos personales

Responsables: abogado de privacidad + responsable interno de datos.

- Hacer un inventario por finalidad y base de legitimación: cuentas de talleres, clientes del taller, teléfonos/emails, equipos/IMEI, fotos, órdenes, credenciales de desbloqueo, soporte, seguridad, facturación y metadatos de Mercado Pago.
- Definir los roles. Como hipótesis de trabajo, cada taller será responsable de los datos de sus clientes y OrdenFix será encargado; OrdenFix será responsable de cuentas, contratación, facturación, prevención de fraude y soporte. El abogado debe confirmar y reflejarlo en contrato/DPA.
- Redactar Política de Privacidad y aviso en cada punto de captura: finalidad, obligatoriedad, destinatarios, responsable y domicilio, derechos y canal de ejercicio.
- Verificar inscripción del responsable y de las bases que correspondan ante el Registro Nacional de Bases de Datos Personales. La AAIP indica que el responsable debe inscribirse antes de registrar bases.
- Crear un procedimiento trazable para acceso, rectificación, actualización y supresión; separar datos eliminables de libros contables, antifraude e integridad operativa sujetos a conservación.
- Aprobar una matriz de retención. Propuesta a validar: credenciales de equipo hasta entrega o antes; fotos y orden por el plazo de garantía/reclamo; logs de seguridad por plazo corto; facturación por el plazo fiscal; backups con expiración documentada.
- Documentar incidentes, responsables de respuesta, evaluación, preservación de evidencia, comunicaciones y simulacro anual.

La AAIP exige informar de manera clara finalidad, destinatarios, identidad del responsable y derechos, además de medidas técnicas y organizativas de seguridad. Fuentes: [obligaciones del responsable](https://www.argentina.gob.ar/aaip/datospersonales/responsables/obligaciones), [trámites del registro](https://www.argentina.gob.ar/aaip/datospersonales/tramites) y [derechos de titulares](https://www.argentina.gob.ar/aaip/datospersonales/derechos).

Puerta de salida: política/DPA aprobados, inventario y retención firmados, canal de derechos ensayado y registro evaluado.

## 4. Transferencias y proveedores

Responsables: privacidad + infraestructura.

- Inventariar entidad contractual, país, región de datos y subencargados de hosting, base de datos, correo, frontend, observabilidad, backups y Mercado Pago.
- Obtener y archivar Términos, DPA, medidas de seguridad, ubicación, subprocessors, borrado/exportación y notificación de incidentes de cada proveedor.
- Evaluar cada transferencia internacional y el mecanismo aplicable. No asumir adecuación por marca o región comercial; la AAIP contempla países adecuados, cláusulas modelo y autorización en determinados contratos divergentes.
- Establecer revisión previa para incorporar un nuevo subencargado y una lista pública/versionada de proveedores.
- Evitar enviar PIN, patrón, fotos o texto libre de órdenes a servicios de analítica, email o logs.

Puerta de salida: mapa de transferencias y contratos/DPA revisados, con país y fundamento para cada flujo.

## 5. Credenciales y consentimiento del cliente del taller

Responsables: producto + abogado + seguridad.

- El taller debe informar al cliente para qué se solicita PIN/patrón, que es opcional cuando el diagnóstico no lo requiere, quién puede verlo y cuándo se elimina.
- Incorporar consentimiento específico en la orden de ingreso, separado de términos generales, junto con alternativa operativa sin revelar la credencial cuando sea posible.
- Limitar el acceso al detalle autenticado, auditar visualizaciones futuras y borrar al marcar `ENTREGADO`; nunca incluirlo en listados, seguimiento público, emails, exports o logs.
- Definir texto de autorización para fotos, pruebas del equipo, tratamiento de cuentas vinculadas y conformidad de entrega/garantía.

Puerta de salida: texto de orden aprobado y recorrido completo probado con y sin credencial.

## 6. Facturación, impuestos y conciliación

Responsables: contador + administración + ingeniería.

- Confirmar condición fiscal, tipo de comprobante, IVA, Ingresos Brutos/convenio multilateral, domicilio fiscal y tratamiento de comisiones/retenciones de Mercado Pago.
- Emitir factura electrónica por cada cobro y nota de crédito cuando corresponda; vincular comprobante fiscal, cobro autorizado, pago de MP y suscripción local sin usar el recibo de MP como sustituto fiscal.
- Definir contingencia si ARCA o el proveedor de facturación no responde, numeración, reintentos idempotentes y conciliación diaria.
- Separar ambientes y credenciales de prueba/producción; aplicar mínimo privilegio y rotación.

ARCA informa que responsables inscriptos, exentos y monotributistas —con excepciones— deben usar controlador fiscal y/o factura electrónica. Fuente: [comprobantes electrónicos y controladores fiscales](https://www.arca.gob.ar/facturacion/comprobantes/fe-vs-cf.asp).

Puerta de salida: circuito y responsables definidos por contador, comprobante de prueba emitido/anulado y conciliación contable ensayada.

## 7. Evidencia y operación

Responsables: operaciones + ingeniería.

- Mantener un registro de versiones legales y aprobaciones profesionales dentro de un repositorio privado con control de acceso.
- Guardar evidencia de aceptación, baja, cambios de precio, incidentes, solicitudes de datos, facturas/notas de crédito y conciliaciones.
- Crear runbooks para: baja, reembolso, chargeback, cuenta comprometida, filtración, exportación y supresión.
- Medir sin contenido sensible: tasa de checkout, duplicados, firma inválida/replay, webhooks fallidos, conciliaciones fallidas, bajas y tiempos de respuesta.
- Revisar este plan semestralmente y ante cambios de precio, proveedor, país de hosting, finalidad o normativa.

## Secuencia recomendada

1. Semana 1: entidad/segmento, mapa de datos/proveedores y decisión fiscal.
2. Semana 2: términos, privacidad, DPA, política de retención, baja/reembolso y texto de orden de ingreso.
3. Semana 3: evidencia de aceptación/baja, canal de derechos, exportación/supresión y circuito de factura/nota de crédito.
4. Semana 4: revisión profesional, simulacro de incidente, sandbox completo de Mercado Pago y acta de go/no-go.

## Fuentes normativas para la revisión profesional

- [Ley 25.326, texto actualizado](https://www.argentina.gob.ar/normativa/nacional/64790/actualizacion).
- [Ley 24.240, texto actualizado](https://www.argentina.gob.ar/normativa/nacional/ley-24240-638/actualizacion).
- [Disposición 954/2025 — contratación a distancia, baja y arrepentimiento](https://www.argentina.gob.ar/normativa/nacional/disposici%C3%B3n-954-2025-417152/texto).
- [Disposición 3/2026 — verificación razonable de identidad](https://www.argentina.gob.ar/normativa/nacional/disposici%C3%B3n-3-2026-423007/texto).

Estas fuentes se consultaron el 2026-08-13. Deben volver a verificarse al momento del lanzamiento.
