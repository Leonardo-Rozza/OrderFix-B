# Borrado operativo — ejecución acotada y recuperación del avance

Fecha: 2026-09-19. Backend base `66ef5be`; frontend `b3d2af6`.

## Alcance aprobado y decisión

El usuario autorizó continuar con la automatización del borrado operativo: avanzar
por categorías, retomar lo pendiente y distinguir trabajo terminado de bloqueos.
Se mantiene la decisión previa de borrar las ocho categorías V37 después de siete
días, conservando identidad, QR, evidencia legal/fotográfica y suscripciones.
No representa baja integral de cuenta ni activa servicios en entornos existentes.

La exploración comparó tres alternativas: una transacción global (bloqueos y trabajo
sin límite), una tabla nueva de jobs/leases (duplica un avance ya recuperable y
requiere otro contrato persistente) y reconstruir el avance desde V37. Se elige la
tercera: cada lote confirmado conserva su recibo y elimina filas atómicamente; las
filas restantes son la fuente durable del trabajo que queda. No se necesita I/O
externo ni lease entre el borrado y su confirmación local.

Los resultados de completitud o bloqueo son observaciones con fecha; no se guardan
como estados nuevos de trabajo.
No se promete una bitácora durable de intentos, backoff o alertas. El diseño no
requiere V38 ni grants sobre `cuenta_borrado_lotes`; V27–V37 quedan congeladas.
El alcance y avance se documentan en este corte junto al código y las pruebas,
con un único commit atómico sin push, siguiendo la regla de trabajo acordada.

## Componentes y ejecución

- `WorkshopOperationalDeletionProgress`: observación interna REQUIRES_NEW/
  READ_COMMITTED, gate compartido antes de leer, preflight V37 y comprobación del
  cierre actual/ADMIN/generación/fechas. Una consulta coherente cuenta únicamente
  ITEMS, PRESUPUESTOS, COBROS, REPUESTOS, REPARACIONES, EQUIPOS, CLIENTES y ARTICULOS;
  también comprueba los impedimentos fotográficos de V37. No lee contenido de
  clientes ni concede acceso a los recibos privados. El JDBC interno existente
  necesita sus lecturas nominales; no se agregan roles/grants a la migración.
- `WorkshopOperationalDeletionWorker`: no acepta transacción del llamador. Cada
  ejecución procesa hasta cuatro lotes por defecto (configurable entre uno y ocho),
  en el orden de dependencias V37. Cada lote usa el servicio existente con su propia
  transacción y un UUID nuevo. Consulta el progreso antes y después; ningún lock
  exterior se mantiene al llamar al servicio REQUIRES_NEW.
- `WorkshopOperationalDeletionCandidates`: descubre dos talleres por página más
  un centinela, con gracia vencida según PostgreSQL y trabajo operativo/fotográfico
  pendiente. La selección orienta el recorrido, no autoriza el borrado.
- `WorkshopOperationalDeletionScheduler`: opt-in con ambos flags de worker y
  borrado; intervalo fijo de sesenta segundos y demora inicial igual. Su cursor
  en memoria sólo rota por candidatos, incluso bloqueados o fallidos, y vuelve al
  inicio al acabar la página final. Un reinicio pierde la posición de exploración,
  pero no el progreso confirmado. No hay scheduler/worker activo por defecto.

El límite es por invocación: varias instancias pueden avanzar a la vez; V37 y el
gate exclusivo serializan los lotes de un taller. El scheduler evita solaparse
consigo mismo, no inventa una cuota global ni procesa toda la base en una llamada.

## Resultados, errores y reinicio

`NO_PENDING_ROWS` significa exclusivamente ocho categorías sin filas en una
observación fresca válida; no significa supresión de identidad, QR, respaldos,
suscripciones o evidencia. No se calcula sumando respuestas de borrado ni usando
`remaining` de un recibo REUSED, porque ese valor corresponde al momento original.

Un presupuesto agotado devuelve `WORK_REMAINS`; gracia activa, `WAITING_GRACE`;
fotos no acreditadas, `PHOTOS_PENDING`. EMPTY con filas todavía presentes en esa
categoría indica `DEPENDENCIES_PENDING`, incluidos ciclos. No se saltan padres ni
se fuerzan cascadas. Un fallo SQL/preflight desconocido no se clasifica como bloqueo
permanente: devuelve error saneado o `RETRY_LATER` y detiene las mutaciones de esa
invocación. La siguiente ronda vuelve a observar antes de intentar trabajo.

Tras una respuesta perdida después del commit, una observación nueva recupera las
filas realmente restantes. No se vuelven a borrar filas ausentes ni se cuentan
replays como nuevos borrados. Si también falla la observación posterior, el resultado
conserva explícitamente la última captura disponible y requiere reintento; nunca
se inventa un estado completo. Los lotes anteriores confirmados sobreviven a un
fallo posterior; el lote actual conserva el rollback de V37.

El scheduler conserva sólo un resumen en memoria y logs con cantidades/estados,
sin IDs, causas SQL ni datos de negocio. Alertas externas, registro externo,
recuperación del despliegue y baja integral siguen en sus etapas propias.

## Plan de ejecución y validación

1. Preservar HEAD, archivos ajenos y SHA de V27–V37; revisar contratos existentes.
2. Implementar lector/orquestador, descubrimiento y scheduler opt-in.
3. Probar presupuesto, estados observados, flags y rotación sin inanición; ejecutar
   PostgreSQL 16 para recorrido de ocho categorías, reinicio, respuesta perdida,
   rollback parcial, concurrencia, dependencias, fotos y alcance retenido.
4. Repetir suites focales relacionadas y control del artefacto sin secretos. No
   repetir el integral ya acreditado en V37 salvo cambio transversal o defecto que
   lo justifique: este corte no cambia persistencia ni los consumidores existentes.
5. Registrar evidencia, verificar preservación y crear un commit atómico local.

La aprobación de avanzar y el alcance previo cubren estas decisiones internas;
no hay nueva publicación, envío de mensajes ni ejecución sobre cuentas reales.

## Resultado acreditado

El focal terminó **BUILD SUCCESS en 02:50 min**, Java 21 y PostgreSQL 16
descartable, con **151 casos distintos aprobados**, sin fallos, errores u omisiones:

| Suite | Casos |
| --- | ---: |
| WorkshopClosurePolicyTest | 19 |
| WorkshopOperationalDeletionCandidatesTest | 7 |
| WorkshopOperationalDeletionSchedulerConfigurationTest | 3 |
| WorkshopOperationalDeletionSchedulerTest | 7 |
| WorkshopOperationalDeletionWorkerTest | 22 |
| PostgresMigrationIT | 8 |
| WorkshopOperationalDeletionIT | 50 |
| LegalPrivatePhotoOperationsIT | 35 |

Son 58 unitarias y 93 IT. Las pruebas PostgreSQL acreditan el recorrido de las ocho
categorías, reinicios con nuevas instancias, límite de 1/2/8 lotes, pérdida de
respuesta después del commit, rollback del lote actual conservando los anteriores,
concurrencia, referencias ajenas/restauradas, gracia, ciclos y fotos legacy.
La suite de fotos confirma además que el lector distingue identidad eliminada,
foto asociada, ausencia sin identidad y falta de constancia, sin tocar proveedores.
El arranque/migraciones y el control de ambos JAR sin propiedades secretas aprobaron.

```sh
./mvnw -B \
  -Dtest=WorkshopOperationalDeletionWorkerTest,WorkshopOperationalDeletionCandidatesTest,WorkshopOperationalDeletionSchedulerTest,WorkshopOperationalDeletionSchedulerConfigurationTest,WorkshopClosurePolicyTest \
  -Dit.test=WorkshopOperationalDeletionIT,LegalPrivatePhotoOperationsIT,PostgresMigrationIT verify
```

Se ejecutó sin importar configuración de proveedores reales. Evidencia local:
`/private/tmp/ordenfix-operational-orchestration-20260919/focal.log`,
`test-summary.json`, `reports/` y `preservation.json`. No se repitió `clean verify`:
no hay migración ni cambios a contratos/consumidores existentes, y el focal no
encontró fallos. La evidencia integral de V37 conserva su resultado documentado.

V27–V37 mantienen sus SHA-256; frontend conserva HEAD `b3d2af6`, sus archivos
versionados y sus 179 archivos no versionados preexistentes. No se activaron flags,
servidores habituales o proveedores ni se borraron datos reales. Entrega en un único
commit local `feat(cuenta): orquesta borrado operativo por lotes`, sin push.
