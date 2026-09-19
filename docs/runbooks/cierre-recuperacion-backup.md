# Recuperación de backups y cierres de taller

Estado: procedimiento propuesto y comprobador interno de comparación parcial del
corte D. No hay adaptador de journal externo ni gate automático de cuarentena.
Este documento no autoriza restaurar bases reales ni reabrir producción.

## Qué debe conservar la cuarentena

Un backup anterior a un cierre puede recuperar `ABIERTO`, versiones JWT anteriores,
trabajos con payload y efectos todavía pendientes o inexistentes. La base restaurada
no puede probar por sí misma qué cierres ocurrieron después de su copia. Que no haya
filas de cierre, o que sus fechas parezcan coherentes, no demuestra ausencia de
solicitudes posteriores. Rotar JWT por sí solo tampoco evita un nuevo login contra
una cuenta antigua que vuelve a aparecer abierta.

Antes de conectar una restauración, mantenerla aislada de tráfico de usuarios y de
workers, webhooks y proveedores. Esta restricción debe depender del procedimiento y
entorno de despliegue, fuera de la base restaurada. Un flag guardado dentro del mismo
backup puede retroceder con ella. Hoy la aplicación no implementa esa barrera de
arranque: el equipo de operación debe acreditar el aislamiento antes del ensayo o
recuperación. No iniciar la aplicación con los proveedores habilitados para hacer
esta comprobación.

## Evidencia externa necesaria

Obtener un registro autorizado de cierres, restauraciones y supresiones que sobreviva
independientemente al backup. Debe identificar el despliegue y su cobertura temporal,
orden/generación, referencia y taller, y permitir comprobar la continuidad hasta un
punto posterior al backup. Deben evaluarse también revocaciones, efectos remotos y
copias conservadas. Una lista aportada, un hash o una firma aislada no prueban que el
registro esté completo ni que su emisor tenga autoridad. Las claves y evidencia real
no se guardan en Git ni se imprimen en diagnósticos.

C conserva intenciones y constancias en la misma PostgreSQL; no ofrece un espejo
externo transaccional. Existe por tanto una dependencia operativa pendiente: si no
puede acreditarse la cobertura del intervalo entre el backup y el punto de
recuperación, mantener cuarentena y escalar la reconciliación. No dar el intervalo
por cubierto porque coincidan las referencias conocidas.

## Comparador interno disponible en D

`WorkshopClosureBackupCheck.compare` recibe entre 1 y 1.000 entradas tipadas: taller,
titular, referencia de cierre, generación, estado y época del titular. Rechaza entradas
inválidas, talleres/referencias repetidos y 1.001 entradas antes de consultar la base.
No lee archivos, interpreta URLs ni autentica el origen de esa evidencia.

Cada llamada abre una transacción nueva `REPEATABLE_READ`, de sólo lectura. Una única
SELECT compara el ancla, el historial correspondiente a la generación y el titular.
La referencia de una restauración se obtiene del historial porque el ancla abierta
la limpia. El instante del informe viene de `statement_timestamp()` de esa consulta.
La captura puede quedar atrás de cambios confirmados después de ese instante; no
bloquea futuras modificaciones. La transacción tiene timeout de 10 segundos y aplica
`statement_timeout` de 5 segundos y `lock_timeout` de 2 segundos. Estos límites no
constituyen un plazo absoluto del pool, del proceso o de toda la recuperación.

Los resultados son `COMPARACION_COMPATIBLE` o `DIVERGENCIAS`, siempre con
`NO_AUTORIZA_REAPERTURA`. Los hallazgos distinguen base ausente, generación atrasada o
más nueva, inconsistencia local, discrepancia de referencia/estado/titular y época
del titular anterior o posterior. Una base más nueva también exige revisión: no
convierte el conjunto aportado en un journal completo. La eliminación terminal no
está implementada en V34; evidencia `DELETED` produce `DELETION_NOT_IMPLEMENTED` y no
puede obtener un resultado compatible con una declaración de borrado.

Una comparación compatible sólo indica que coincidieron los datos examinados. No
acredita épocas de empleados, integridad o completitud del journal, identidad del
entorno, outbox, constancias del proveedor, archivos, retenciones ni contenido de
WAL, réplicas o backups. Tampoco aplica cambios, revoca sesiones, elimina payloads,
reenvía correos, cancela suscripciones ni modifica la configuración de arranque.

## Reconciliación y revisión antes de reabrir

1. Acreditar el aislamiento, la procedencia del backup y la cobertura del registro
   externo. Si falta alguna evidencia, mantener la restauración en cuarentena.
2. Comparar las referencias conocidas. Investigar cada ausencia, retroceso o
   discrepancia y ampliar la revisión a cierres que no estaban en la copia. La
   herramienta de D sirve de diagnóstico; no es una lista exhaustiva de cuentas.
3. Preparar y aprobar la reconciliación específica de estado, sesiones, outbox y
   datos conservados. No editar épocas, estados o leases a mano para silenciar un
   hallazgo ni recrear operaciones con IDs nuevos. Esta ejecución no está incluida
   en el comprobador.
4. Revisar efectos con resultado incierto mediante la identidad estable del objetivo.
   Un correo aceptado sin ACK persistido no se reenvía a ciegas. Una renovación
   cancelada se contrasta antes de repetir un efecto. Restaurar acceso no autoriza
   renovar los vínculos anteriores.
5. Revisar expiración y revocación de exportaciones antes de cualquier entrega. La
   purga de BYTEA no borra WAL o backups. Para fotos, conservar la identidad exacta
   hasta acreditar la ausencia del activo; el estado `ELIMINADA` de una copia antigua
   no prueba el estado actual del proveedor, CDN o respaldos. Una carga tardía tampoco
   queda resuelta sólo porque su lease local haya vencido.
6. Verificar en el entorno recuperado las restricciones, sesiones y bloqueos de
   proveedores, incluyendo talleres afectados y controles de otro taller. Registrar
   referencias, versiones, cobertura y resultado saneado. Una persona responsable
   debe acreditar por separado todos los requisitos operativos antes de reabrir.

Las herramientas de borrado por categorías, política real de conservación, journal
externo, reconciliación y barrera automática de arranque siguen pendientes. El cierre
D parcial y una comparación compatible no sustituyen esos requisitos.

## Ensayo automatizado local

`WorkshopClosureBackupRecoveryIT` realiza el ensayo reproducible con dos PostgreSQL
16 descartables y datos sintéticos. Crea bases nuevas por caso, migra sólo la fuente
y usa `pg_dump --format=custom` y `pg_restore --exit-on-error --single-transaction
--no-owner` para recuperar el destino vacío. Los comandos corren dentro de esos
contenedores, con un límite de 45 segundos; los puertos PostgreSQL sólo se publican
en loopback y se comprueba esa configuración. No se inicia la aplicación ni se
instancian workers, clientes de proveedores o controladores HTTP.

El harness compara las filas de todas las tablas públicas, las secuencias y los
triggers entre el backup y la recuperación. Comprueba además el rechazo efectivo de
una escritura en un taller restringido. La transferencia verifica SHA-256 contra el
archivo local generado: acredita igualdad de esos bytes, no procedencia ni autoridad
de un backup externo. Los archivos temporales tienen permisos 0600 y pertenecen a
JUnit/los contenedores descartables.

Los fixtures coordinan pruebas y operaciones sintéticas con Store, Effects y gate
reales, sin desactivar triggers. Recuperar un backup anterior al cierre o a una
restauración reproduce retrocesos de estado, versiones de sesiones, operaciones,
exportaciones y avisos pendientes. El diagnóstico compara sólo su contrato: épocas
de empleados, payloads y outbox se comprueban aparte en la prueba. Los bytes de
exportación son opacos y sintéticos; no prueban ZIP, cifrado o descarga autorizada.

Una copia actual puede coincidir y conservar `NO_AUTORIZA_REAPERTURA`. Un taller
creado después del backup sólo aparece como ausente si está en la evidencia aportada;
el ensayo muestra expresamente que una lista incompleta también puede coincidir.
El caso truncado debe fallar y dejar el destino sin tablas operativas; el comparador
rechaza entonces la consulta como no disponible.

Ejecución focal (Java 21 y Docker disponibles):

```sh
DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B \
  -Dtest=WorkshopClosureBackupCheckTest \
  -Dit.test=WorkshopClosureBackupRecoveryIT,WorkshopClosureBackupCheckIT verify
```

El aislamiento lo establece este harness, no un gate de arranque de OrdenFix. Es un
backup lógico con el mismo rol sintético en ambas instancias y ACL del archivo; no
acredita roles globales, cuentas restringidas de producción, infraestructura, claves,
WAL/PITR, backups cifrados, objetos remotos ni tiempos de recuperación a volumen real.
Tampoco constituye un journal externo completo, reconciliación o aprobación de
reapertura. La eliminación integral del corte D conserva sus pendientes.
El diseño y el resultado de ejecución se registran en
[el acta del ensayo](../plans/2026-09-19-recuperacion-backup-local-design.md).
