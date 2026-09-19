# Cierre y datos — constancias de eliminación de fotos

Fecha: 2026-09-19. Bases: backend `70181fa`, frontend `53d653a`.
Continuación solicitada: cierres/datos primero, adaptación de otros equipos después.

## Problema y corte

El borrado privado actual llama al proveedor, comprueba ausencia y cambia la foto a
ELIMINADA vaciando asset_id/version. Eso pierde la identidad que permitiría revisar
el efecto después. El inventario de cierre sólo conoce cantidades/estado local.
La prueba real de Cloudinary del corte anterior no sustituye un registro durable
por cada eliminación futura.

Se incorpora V35 sin editar V27–V34. Una tabla específica conserva objetivo,
pertenencia y resultado, sin imágenes, URLs, nombres ni secretos. Cubre DELETE y
cleanup global, con taller abierto o restringido: limitarlo al cierre dejaría otra
ruta que elimina sin registrar. No se agrega otro worker, controller ni endpoint.

## Protocolo

1. Marcar LIMPIEZA_PENDIENTE y crear/reutilizar el objetivo en PostgreSQL antes del
   efecto remoto. Transacciones propias; el proveedor se invoca fuera de ellas.
2. Si hay identidad conocida, conservarla y borrar ese asset exacto. Si se recupera
   por clave después de una respuesta perdida, comprobar clave, MIME, bytes y SHA
   antes de registrar su identidad y ejecutar el borrado.
3. Confirmar el resultado y la transición ELIMINADA en la misma transacción. Una
   caída intermedia conserva el objetivo para reintentar con la misma identidad.
4. La identidad sólo puede establecerse una vez. Reintentos/concurrencia no pueden
   sustituirla ni ampliar fechas de confirmación. Guards SQL acreditan pertenencia,
   estado y coordinación, sin reemplazar los contratos congelados.

Se distinguen PENDIENTE, IDENTIDAD_ELIMINADA y AUSENCIA_OBSERVADA_SIN_IDENTIDAD.
Este último conserva el comportamiento previo para una intención cuya clave no se
encuentra, sin inventar un asset ni afirmar que nunca hubo una carga. La observación
no resuelve respuestas inciertas/cargas tardías y exige revisión para un cierre
integral. No se transforma en recibo de borrado confirmado.

Las fotos históricas ya ELIMINADA no reciben constancias retrospectivas. El
inventario separa resultados confirmados, ausencias observadas, pendientes y fotos
sin recibo; sigue sin certificar backups/CDN o eliminación de todo el taller.

## Compatibilidad y límites

Las operaciones de fotos requieren la capacidad V35 acreditada. Los consumidores
legales históricos mantienen compatibilidad y sus huellas anteriores. El rol de
fotos recibe únicamente los permisos nominales nuevos; no DELETE ni ejecución
libre de funciones SECURITY DEFINER. Los verificadores se actualizan por versión.

No cambia elegibilidad ni retención de fotos. Se conserva gracia de siete días y
plazo técnico de procesamiento existentes. La política real por categoría, supresión
de negocio/identidad, excepciones, journal externo y recuperación del despliegue
permanecen pendientes. Este corte no activa cierre productivo ni ejecuta limpiezas
sobre cuentas o assets reales.

## Verificación y entrega

Pruebas focales PostgreSQL 16: objetivo confirmado antes del efecto, errores/remoto
incierto, rollback final y reintento, identidad/pertenencia alteradas, concurrencia,
retención futura y taller ajeno. Una fixture sintética de foto ya ELIMINADA sin
recibo comprueba que el replay no inventa evidencia; no se presenta como ensayo de
upgrade con fotos históricas pobladas. La migración nueva no realiza backfill. Pruebas del
inventario y consumidores históricos. Se requiere integral por incorporar migración
y preflight compartido, según la política acordada. Sin llamadas reales a proveedores.

Un commit atómico por repositorio afectado, sin push. Preservar archivos ajenos y
migraciones congeladas. Resultado y comandos se completarán antes del commit.


### Evidencia focal del 2026-09-19

- Diagnóstico de PostgreSQL 16 limpio: V35 checksum `1631742413`. Se conservan
  catálogos legal V34 y registro V34; las nuevas huellas delta/fotos se midieron
  sin sustituir constantes anteriores.
- 120 casos focales únicos aprobados: operaciones de fotos 31, esquema/permisos V35
  29, inventario 15, migración 8 y compatibilidad V34 37. Las repeticiones no se suman.
- Navegador: cuatro escenarios desktop/móvil, alta rápida/equipo existente, más
  validación JUnit de los cuatro recibos con identidad exacta y fechas coherentes.
  Almacenamiento sintético; no se llamó a Cloudinary, email ni Mercado Pago.
- Se corrigieron en la primera ejecución una expresión CASE de la migración y una
  consulta de capacidad que ocurría al construir el verificador fuera de su deadline.
  La resolución ahora ocurre en cada verificación, dentro de la operación y mediante
  una instancia derivada local; no muta capacidades compartidas. También se ajustaron
  aserciones de causas SQL y motivos de revisión por categoría.
- Revisión independiente de SQL y fronteras Java sin hallazgos pendientes.

Comandos focales: `-Dtest=LegalV35SchemaSnapshot test`;
`-Dtest=LegalV35PhotoDeletionSchemaVerifierIT,LegalPrivatePhotoOperationsIT,WorkshopClosureDeletionInventoryIT,PostgresMigrationIT,LegalV34ClosureSchemaVerifierIT test`
y repeticiones limitadas a clases/casos corregidos; `node scripts/run-photos-real.mjs`
sin opt-in de proveedor. Evidencia descartable local en
`/private/tmp/ordenfix-closure-photo-receipts-20260919/`.

La primera integral se detuvo deliberadamente tras identificar fixtures desactualizadas,
para concentrar el gate final en el estado conjunto V35/V36. Alcanzó 7.991 pruebas
unitarias aprobadas y 1.188 casos IT en 83 clases: 18 aserciones fallidas, sin errores
ni omisiones. No se presenta esa ejecución interrumpida como una integral aprobada.

Los fallos corresponden a dos suites HTTP que usaban usuarios sin email verificado
para probar rutas vecinas/métodos alternativos, y a la expectativa de longitud de la
historia Flyway en capacidad. Las rutas legales canónicas conservan cobertura con
email pendiente. Se corrige además una prueba unitaria que no comprobaba la entrada
a su callback protegido. No cambia la política de autenticación ni los presupuestos.

La repetición focal de las cuatro clases aprobó 104 casos, sin fallos, errores ni
omisiones (`fixture-corrections.log`, `BUILD SUCCESS`). La integral completa
queda como gate final del corte C de equipos; incluye estos cambios sin modificarlos.
