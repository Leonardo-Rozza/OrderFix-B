# Recuperación de backups y cierres de taller

Estado: comprobador parcial D, checkpoint externo autenticado y barrera de arranque
configurable disponibles en local. No hay journal externo transaccional continuo.
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
backup puede retroceder con ella. Configurar `ORDENFIX_RECOVERY_QUARANTINE=true` en
el entorno del backend antes de apuntarlo a una restauración: aborta antes de crear
el contexto Spring, DataSource, Flyway, HTTP o workers. Un valor inválido también
bloquea; ausencia o `false` conservan el arranque previo. No detiene procesos ya
iniciados ni detecta una restauración por sí sola. Acreditar también aislamiento
de red/proveedores; no iniciar el backend para ejecutar el diagnóstico.

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

El borrado operativo por categorías y su worker V37 ya existen como capacidades
opt-in; identidad y evidencia retenida siguen fuera de ese borrado. Permanecen
pendientes la conservación real por categoría, journal continuo, reconciliación
específica y operación del despliegue. La barrera de arranque requiere configuración
externa previa; ninguna comparación compatible sustituye esos requisitos.

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

El aislamiento de este ensayo lo establece el harness; las pruebas de la nueva
barrera de arranque son independientes. Es un
backup lógico con el mismo rol sintético en ambas instancias y ACL del archivo; no
acredita roles globales, cuentas restringidas de producción, infraestructura, claves,
WAL/PITR, backups cifrados, objetos remotos ni tiempos de recuperación a volumen real.
Tampoco constituye un journal externo completo, reconciliación o aprobación de
reapertura. La eliminación integral del corte D conserva sus pendientes.
El diseño y el resultado de ejecución se registran en
[el acta del ensayo](../plans/2026-09-19-recuperacion-backup-local-design.md).


## Checkpoint externo autenticado y CLI aislada

El corte de [checkpoint y cuarentena](../plans/2026-09-19-recuperacion-checkpoint-cuarentena-design.md)
amplía el diagnóstico a todos los talleres presentes, las épocas de todos sus
usuarios, historial/operaciones, recibos V37 y ocho categorías operativas. Detecta
filas recuperadas después de un borrado aunque el cierre siga coincidiendo, y
cambios de contenido aun con igual cantidad. Sólo escribe el checkpoint externo;
las consultas a PostgreSQL son de sólo lectura y no migran ni corrigen la base.

Preparar **fuera del repositorio y del backup principal**, mediante la operación
del entorno, un directorio privado 0700 y una clave binaria aleatoria de 32 bytes
con permisos 0600. Conservar y respaldar la clave por un canal seguro independiente.
No reutilizar JWT, claves de email, MP o Cloudinary como clave HMAC. El archivo
contiene IDs y huellas sensibles; no publicarlo ni adjuntarlo a tickets abiertos.

Variables obligatorias para el proceso CLI:

| Variable | Procedencia y significado |
| --- | --- |
| `ORDENFIX_RECOVERY_ENVIRONMENT_ID` | UUID estable del despliegue, autorizado fuera de la base restaurada. Es una identidad declarada por el operador; verificar también a qué instancia conecta JDBC. |
| `ORDENFIX_RECOVERY_CHECKPOINT_ID` | UUID nuevo para capturar; UUID exacto del recibo externo esperado para comparar. |
| `ORDENFIX_RECOVERY_KEY_FILE` | Ruta al archivo privado con la clave binaria de 32 bytes. |
| `ORDENFIX_RECOVERY_JDBC_URL` | Conexión PostgreSQL a la fuente autorizada o al destino aislado. Mantener timeouts de conexión/socket y TLS adecuados en la configuración de infraestructura. |
| `ORDENFIX_RECOVERY_JDBC_USERNAME` | Cuenta de mantenimiento con las lecturas requeridas, separada del rol HTTP. |
| `ORDENFIX_RECOVERY_JDBC_PASSWORD` | Secreto inyectado por el entorno seguro; no incluir en comandos, commits o registros. |
| `ORDENFIX_RECOVERY_EXPECTED_SHA256` | Sólo `compare`: huella del recibo esperado conservado por un canal independiente. No recalcularla a partir del archivo que se pretende validar. |

El proceso necesita leer metadatos del preflight de lectura V37 y las superficies capturadas,
incluidos recibos privados. No modifica ACL ni crea roles: permisos insuficientes
rechazan la captura. No otorgar privilegios al rol público para forzar un diagnóstico.
La transacción impone READ ONLY y `row_security=off` para rechazar filtrados RLS,
no producir una falsa captura completa. El preflight admite exactamente dos perfiles
V37 revisados: migrado y restaurado en PostgreSQL 16. Otra huella, nuevos grants,
configuración de collation/locale distinta o cambios de catálogo requieren revisar
el perfil; no hay opción para omitir la comprobación. No certifica roles globales ni
privilegios de infraestructura. No reemplaza los gates históricos de escritura legal.

El [corte de compatibilidad V37](../plans/2026-09-19-compatibilidad-restauracion-v37-design.md)
agrega variantes exactas para los metadatos reconstruidos por pg_dump/pg_restore
PostgreSQL 16. Los consumidores de importación, edición legal, agregados, aceptación,
registro, fotos y borrado operativo mantienen sus controles de sesión, historial,
funciones y privilegios. El alcance es el esquema actual **public/V37**; no habilita
restauraciones de V27–V36 ni representaciones de catálogo desconocidas. Las huellas
originales y las migraciones permanecen congeladas.

Esto resuelve la compatibilidad de esos verificadores, **no autoriza la reapertura**:
los datos pueden seguir atrasados y contener sesiones o filas que debían estar
revocadas o borradas. Mantener la cuarentena, contrastar evidencia externa y resolver
las divergencias antes de cualquier decisión operativa. No modificar migraciones,
registros Flyway o hashes a mano para silenciar un rechazo.

Un pg_dump de una base no incorpora la definición de roles globales ni toda su
configuración de sesión. Reponer los roles y configuración por el procedimiento
externo del despliegue, manteniendo las capacidades mínimas; el catálogo restaurado
no concede permisos adicionales. Las pruebas locales provisionan roles sintéticos
explícitamente después de restaurar y comprueban sus fronteras de privilegios.

Tras empaquetar con Java 21 (`./mvnw -B -DskipTests package`), usar el launcher:

```sh
# Variables y permisos provisionados previamente por el operador.
# ORDENFIX_RECOVERY_DIR debe ser un directorio privado externo al repositorio.
scripts/recovery-checkpoint.sh capture "$ORDENFIX_RECOVERY_DIR/checkpoint.bin"

# Conectar ahora al destino aislado y proporcionar el recibo esperado independiente.
scripts/recovery-checkpoint.sh compare "$ORDENFIX_RECOVERY_DIR/checkpoint.bin"
```

El launcher retira JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS y _JAVA_OPTIONS, utiliza
`ORDENFIX_JAVA_BIN` si se define y permite seleccionar artefacto con
`ORDENFIX_RECOVERY_CLI_JAR`. La CLI no crea SpringApplication, no procesa sus imports
ni contacta HTTP/proveedores; puede trabajar aunque el backend esté en cuarentena.

`capture` devuelve `CAPTURED`, el UUID de entorno/checkpoint y SHA-256 del archivo.
Guardar ese recibo por el canal independiente autorizado. El archivo no se sobrescribe.
`compare` valida HMAC, formato, límites y recibo antes de abrir la conexión al destino;
devuelve MATCH o DIFFERENCES con IDs técnicos/superficies, sin contenido de negocio.
Salidas: 0 = captura o comparación ejecutada y coincidente; 2 = diferencias; 1 =
rechazo/error. **Ni siquiera 0 autoriza reapertura**. Toda salida mantiene
`NO_AUTORIZA_REAPERTURA`; el comando jamás quita la cuarentena.

Límites por captura: 1.000 talleres y 10.000 filas por superficie en toda la base,
archivo de hasta 4 MiB. Si se exceden, se rechaza sin emitir una captura parcial.
No dividir manualmente talleres para presentar cobertura completa: ampliar capacidad
requiere otro corte con validación. Una captura vacía se admite como inventario vacío
observado, no como evidencia de ausencia de operaciones posteriores.

Un checkpoint auténtico acredita sólo su instante/superficies. Una captura vieja
más su recibo viejo pueden coincidir y seguir omitiendo cierres posteriores. El HMAC,
la fecha y la coincidencia no prueban continuidad ni autoridad del operador. Mantener
la cuarentena hasta reconciliar el intervalo faltante, sesiones, exportaciones,
efectos remotos, evidencia retenida y respaldos. No hay reanudación automática.
