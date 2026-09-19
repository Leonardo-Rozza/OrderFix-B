# Cierre del taller — borrado de datos operativos

Fecha: 2026-09-19. Backend base `bd7f04c`; frontend `b3d2af6`.

## Decisión de producto confirmada

El usuario confirmó: borrar clientes, equipos, reparaciones, presupuestos y
registros de cobros después de los siete días de recuperación. Se incluyen sus
ítems, repuestos y catálogo de artículos. Evidencia legal y suscripciones permanecen
separadas hasta definir conservación. No se presenta este resultado como borrado
total de cuenta. No se opera sobre cuentas reales en esta implementación.

El diseño aplica brainstorming a esa decisión: se compararon una cascada integral,
un borrado global de cada tabla y lotes de objetivos explícitos. Se eligen lotes de
hasta 25 filas de una categoría, dentro de una transacción. Evitan cascadas sin
límite y permiten reintentar sin rehacer borrados ya confirmados. La eliminación de
cuenta/identidad y la retención definitiva conservan su etapa final acordada.

## Persistencia V37 y servicio interno

Se agrega V37; V27–V36 permanecen intactas. La operación PostgreSQL
`cuenta_cierre_borrar_lote_v37(lote,taller,cierre,categoria)` verifica el cierre
RESTRINGIDO actual y su historial, generación, titular, fechas y gracia vencida con
el reloj de la DB. El servicio adquiere primero el gate exclusivo por taller y la función SQL exige
que ya esté retenido en la misma transacción. No admite una
referencia histórica/restaurada o perteneciente a otro taller.

Una capacidad privada, limitada a la transacción y sus objetivos seleccionados,
habilita únicamente los DELETE necesarios. No depende de variables de sesión ni
desactiva triggers. Se conserva el comportamiento ordinario de V33 para talleres
abiertos y el rechazo del DELETE directo durante el cierre. Las tablas de contexto
y recibos carecen de permisos públicos; el consumidor sólo necesita la entrada
tipada, además de la lectura requerida por el preflight de esquema.

Se borran explícitamente ítems, presupuestos, cobros, repuestos, reparaciones,
equipos, clientes y artículos. Los padres sólo son elegibles cuando no tienen hijos
pendientes; las garantías se procesan desde las reparaciones que no tienen
descendientes. Una relación cíclica o cruzada no habilita una cascada forzada.
Se comprueba pertenencia también en relaciones históricas que usan FK por ID simple.
Un efecto sobre un taller ajeno provoca rechazo y rollback.

Cada lote que borra filas conserva una constancia sin contenido de negocio: identidad
de operación/cierre, generación, categoría, cantidad, fecha y pendientes observados.
Repetir su UUID compatible devuelve REUSED sin DML; reutilizarlo para otro alcance
falla cerrado. Sin objetivos elegibles, EMPTY no escribe ni inventa una constancia.
`remaining` sólo describe filas de esa categoría al observarlas. EMPTY con
`remaining=true` indica dependencias pendientes; no significa categoría eliminada.

`WorkshopOperationalDeletionService.deleteBatch` usa REQUIRES_NEW/READ_COMMITTED,
timeout transaccional de 10 s, statement_timeout 5 s y lock_timeout 2 s. Verifica
capacidad y huellas exactas V37 antes de llamar a la función. Errores de SQL,
pertenencia, colisión o decodificación revierten el lote y devuelven un error saneado.
El flag `ordenfix.cuenta.cierre.operational-deletion-enabled` permanece en false por
defecto. No hay controller, scheduler ni llamadas a proveedores.

## Fotografías y datos conservados

Las fotos legacy y privadas pendientes o sin recibo de identidad eliminada bloquean
el borrado operativo. No se borra una URL local para dar por borrado un objeto remoto.
Una foto privada ELIMINADA y acreditada puede perder únicamente su FK opcional a la
reparación borrada; conserva `reparacion_original_id`, identidad, atestación y recibo.
Los contratos V30/V35 siguen validando esos registros.

No se borran usuarios, el ancla del taller, textos/aceptaciones legales, atestaciones,
constancias, historial de cierre ni registros de suscripción. El mantenimiento de
exportaciones/tokens mantiene sus plazos existentes. El taller continúa RESTRINGIDO;
no se introduce una transición ELIMINADO ni se habilita el cierre productivo.

## Verificación y entrega

PostgreSQL 16 descartable: recorrido de las ocho categorías, 25+1 y replay sin DML,
aislamiento, gracia/referencia, concurrencia, rollback después de DELETE, guardas
directas y rol restringido, fotos y dependencias. Acreditación de huellas V37 y
compatibilidad de consumidores legales/fotos, conservando constantes históricas.
Por alterar triggers y capacidad compartida corresponde gate integral backend tras
las pruebas focales. Frontend sin cambios funcionales.

Entrega en un commit atómico de este corte, documentado y sin push. Se preservan
los archivos ajenos/no versionados; la evidencia final queda registrada abajo.


### Evidencia focal

V37 se aplicó en PostgreSQL 16 limpio, sin alterar funciones legales ni el catálogo
V34 de cierre confirmado. Checksum Flyway: `6712563`. Las huellas propias nuevas
se midieron con `LegalV37SchemaSnapshot`; las constantes anteriores se conservan.

Se acreditaron **184 casos focales distintos**: política de cierre 19; migración 8;
inventario 15; mantenimiento 14; borrado operativo 35; fotos privadas 35;
compatibilidad V37 20; verificador de registro 38. Incluyen ejecución con rol SQL
sin permisos de lectura/escritura en objetivos, contexto o recibos, ítems duplicados
limitados por CTID, aislamiento, replay, rollback, concurrencia y fotos con recibo.

El primer pase ejecutó 19 unitarias y 127 IT, con un error: el selector del catálogo
de registro aún no contemplaba V37. Se corrigió para reutilizar su catálogo V34
conservado y se repitieron completas compatibilidad/registro: **58/58 aprobadas**.
No se suman esas 20 repeticiones como casos distintos. Esta corrección no modifica
la migración ni sus huellas. El gate integral se ejecuta después de este estado.

Evidencia local: `/private/tmp/ordenfix-operational-deletion-20260919/` contiene
`schema-snapshot-final.log`, `focal.log`, `focal-first-reports/`,
`focal-registration-correction.log` y `focal-corrected-reports/`.

Comandos focales con Java 21 y sin importar configuración de proveedores reales:

```sh
./mvnw -B -Dtest=LegalV37SchemaSnapshot test
./mvnw -B -Dtest=WorkshopClosurePolicyTest \
  -Dit.test=WorkshopOperationalDeletionIT,LegalV37CompatibilityIT,LegalPrivatePhotoOperationsIT,PostgresMigrationIT,WorkshopClosureMaintenanceIT,WorkshopClosureDeletionInventoryIT verify
./mvnw -B -Dtest=LegalV37CompatibilityIT,LegalRegistrationSchemaVerifierIT test
```

Los tests del executor conceden únicamente acceso al esquema y EXECUTE sobre la
entrada; la acreditación de esquema también necesita SELECT sobre
`flyway_schema_history` y lectura de catálogos. No se habilitan permisos de tablas
de negocio o de contexto. TRUNCATE se reserva al propietario por ACL, igual que en
las tablas legales históricas; ese propietario sigue siendo una identidad de
mantenimiento confiable y no el rol de ejecución del borrado.


### Gate integral y corrección final

Se ejecutó `./mvnw -B clean verify` con Java 21, PostgreSQL 16 descartable y sin
importar configuración de proveedores reales. Finalizó en **37:29 min**:
**8.010 unitarias aprobadas** y **1.919 IT**, con una falla y cero errores/omisiones.
La única falla fue `LegalEditorialCapacityIT`: su inventario de SQL esperaba diez
versiones históricas y `LIMIT 11`, mientras V37 requiere once versiones y el
sentinela `LIMIT 12`. Los resultados originales se conservan sin sobrescribir.

Se corrigieron únicamente esos dos literales del test. Se conservaron el fixture
V27, el límite de filas leídas y las demás mediciones de capacidad. No hubo cambios
de producción ni de migración después del gate integral. Se repitió completa la
clase afectada y el control de ambos JAR sin `application-secret.properties`:
**1/1 aprobado, BUILD SUCCESS en 49.514 s**.

```sh
./mvnw -B -Dit.test=LegalEditorialCapacityIT test-compile \
  failsafe:integration-test failsafe:verify \
  antrun:run@verify-no-secret-properties-in-jar
```

La evidencia combinada acredita **9.929 casos distintos (8.010 unitarias + 1.919
IT), sin fallos, errores u omisiones pendientes**, sin contar la repetición como
un caso nuevo. No se presenta la primera corrida integral como BUILD SUCCESS ni
se afirma una segunda corrida completa. El cambio final sólo actualiza una
expectativa de prueba; no justifica repetir nuevamente toda la suite.

Evidencia adicional en el directorio local indicado: `clean-verify.log`,
`integral-first-reports/`, `capacity-correction.log` y
`capacity-corrected-reports/`, con sus resúmenes de resultados. Una revisión
independiente de la migración y del servicio no encontró hallazgos accionables.

V37 conserva el checksum Flyway `6712563` y SHA-256
`51e0da3eaca0587cd19a9742716618ddb5e0870d63ff7c4c09bb3a47cafa5728`.
V27–V36 permanecen idénticas al baseline. El frontend mantiene su HEAD, sin cambios
en archivos versionados ni en sus 179 archivos no versionados preexistentes.
No se ejecutó el borrado sobre cuentas reales ni se activaron proveedores o flags.
