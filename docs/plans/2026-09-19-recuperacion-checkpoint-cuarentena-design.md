# Recuperación: checkpoint externo y cuarentena de arranque

Fecha: 2026-09-19. Continuación del corte D de cierre de taller, autorizada por el
usuario. Backend en `codex/lanzamiento-publico-backend`; frontend sin cambios.
No se hace push, despliegue, recuperación real ni contacto con proveedores.

## Problema y decisión

Una restauración puede recuperar filas operativas borradas sin cambiar el estado,
referencia, generación o época del titular. El comparador de D no observa esas filas
ni todas las épocas de empleados: una comparación compatible puede ocultar este caso.

Se elige una captura consistente y autenticada fuera de PostgreSQL, comparada con
la base recuperada mediante una CLI aislada. Se agrega además una barrera temprana
de arranque bajo configuración externa. Esta entrega no pretende convertir una
captura en un journal transaccional ni en autorización para reabrir.

Alternativas consideradas:

- Mantener sólo el comprobador existente: pequeño, pero omite los borrados V37.
- Copiar un registro después del commit: tiene una ventana de pérdida entre commit
  y copia; no acredita continuidad ante una caída y no se adopta como journal.
- Captura externa explícita y cuarentena: permite detectar diferencias reales sin
  atribuir cobertura al intervalo posterior a la captura. Es el alcance elegido.

Un journal transaccional continuo necesita su propio protocolo de intención,
confirmación y reconciliación, y almacenamiento/operación independientes. Queda
separado; no se añade otra migración ni un publicador automático en este corte.

## Contrato implementado

`RecoverySnapshotReader` no es un componente del backend público. Captura todos los
talleres presentes en una transacción nueva `REPEATABLE_READ` de sólo lectura, con
preflight portable de lectura V37. Examina ancla de cierre, historial, operaciones
confirmadas,
recibos de lotes V37, épocas/rol/actividad/verificación de todos los usuarios y las
ocho categorías operativas. Los datos de negocio se hashean dentro de PostgreSQL;
no se exportan contenidos de clientes, credenciales, fotos o tokens.

Los hashes son lógicos, ordenan las huellas por superficie y preservan duplicados.
No usan xmin, CTID, OID ni orden físico: sobreviven a pg_dump/pg_restore. Se fijan
UTC, DateStyle, IntervalStyle, bytea_output y precisión flotante. Formato versión 1
para PostgreSQL 16 y esquema V37; no se promete igualdad entre motores/versiones.

Límites: 1.000 talleres, 10.000 filas totales por superficie y 4 MiB de archivo.
Superar un límite rechaza la captura completa, sin evidencia parcial. Transacción
30 s, statement_timeout 5 s y lock_timeout 2 s; no son un plazo absoluto de conexión,
proceso o infraestructura. No bloquea operaciones futuras y no captura cambios
confirmados después del snapshot. Repetir la captura crea una evidencia nueva.

`RecoveryCheckpointFiles` usa un formato binario canónico versionado con HMAC-SHA256
(clave externa de 32 bytes). El recibo SHA-256, identidad de entorno y UUID de
checkpoint se conservan por un canal independiente del archivo y de la base.
La comparación requiere esos tres valores: un archivo viejo válido se rechaza
contra el recibo esperado actual. Un recibo viejo aportado por el operador sigue
sin demostrar frescura; la fecha incluida tampoco la demuestra.

Los archivos se crean sin reemplazar otros, con permisos 0600 y sincronización de
archivo/directorio en un directorio privado. Un archivo incompleto, alterado,
excesivo, con clave/contexto/recibo distintos o framing inválido se rechaza antes
de conectar la CLI al destino. El checkpoint contiene identificadores y huellas
que siguen siendo información sensible: no se guarda en Git ni se hace público.

`RecoveryCheckpointComparison` detecta talleres ausentes/inesperados y cambios de
cantidad o huella por superficie. Siempre devuelve `NO_AUTORIZA_REAPERTURA`, también
con MATCH o base vacía. No escribe, restaura, revoca, borra, envía ni modifica flags.
No observa outbox, exportaciones, objetos Cloudinary/CDN, MP, evidencia legal
retenida, identidades completas, QR, WAL o backups: se revisan por separado.

## Compatibilidad comprobada de esquema después de pg_restore

El primer ensayo detectó una incompatibilidad del preflight histórico V27 en bases
restauradas: sus hashes de catálogo incluyen AST internos de condiciones de trigger
(con offsets del texto original y formas de coerción). Además, la representación
de las coerciones de arrays en CHECK cambia al restaurar. El dump conserva
el significado SQL, pero esas representaciones se reconstruyen. No se modifican
migraciones, constantes ni verificadores históricos de capacidades de escritura.

El lector usa siempre `RecoveryReadSchemaPreflight`, una huella propia de metadatos
lógicos PostgreSQL 16/V37; no es un fallback tras fallar otro verificador. Se aprueban
dos huellas
completas y exactas: fuente migrada y restauración lógica. El contraste de las 3.342
entradas identifica únicamente 46 CHECK y dos predicados de índices donde PostgreSQL
distribuye una conversión varchar[]→text[] entre los mismos literales, sin typmod.
Son 52 apariciones de esa transformación, sin diferencias residuales. No se reescribe
SQL ni normaliza texto en producción; una tercera huella se rechaza. Incluye
catálogo, funciones, triggers, restricciones, permisos e historial desde V27;
excluye identidad física, estadísticas y offsets de parseo. Los cambios reales de
esquema deben impedir la captura. Esto acredita el contrato de observación, no
capacidades de DML ni disponibilidad del backend público después de restaurar.

Por separado, la compatibilidad de los gates históricos de escritura con un restore
requiere resolver y verificar sus metadatos antes de cualquier reapertura. La nueva
CLI no repara ni disimula ese pendiente: su resultado sigue siendo diagnóstico.

## Cuarentena y herramienta operativa

`ordenfix.recovery.quarantine=true` aborta el arranque normal antes de crear el
contexto Spring; por tanto no inicia DataSource, Flyway, HTTP, schedulers o runners.
Sólo ausencia o el literal `false` permiten el comportamiento previo. Valores
inválidos o no resolubles bloquean con mensaje constante, sin imprimir el valor.
La marca debe configurarse fuera de la base **antes** de apuntar a una restauración.
No detecta automáticamente un restore si el operador omite la marca, no detiene
procesos ya iniciados y no reemplaza el aislamiento de red y proveedores.

La CLI `scripts/recovery-checkpoint.sh` se empaqueta como `*-recovery-cli.jar`, sin
crear SpringApplication ni leer application.properties/imports. Sólo consume sus
variables `ORDENFIX_RECOVERY_*`; el launcher retira opciones JVM que podrían imprimir
valores antes del saneamiento. Los tres artefactos del build excluyen y verifican
la ausencia de application-secret.properties.

Los comandos, configuración y procedimiento se documentan en
[el runbook](../runbooks/cierre-recuperacion-backup.md). No se conceden permisos SQL
nuevos: el operador necesita una conexión de mantenimiento aislada con las lecturas
requeridas; una cuenta sin privilegios falla cerrada. No se amplían los privilegios
del rol de aplicación ni los ACL congelados para facilitar el diagnóstico.

## Plan del corte y validación

1. Captura/modelo/comparación acotados y sin DML.
2. Adaptador de archivo autenticado, ancla externa y pruebas de rechazo.
3. Barrera anterior al contexto y CLI separada con gate de empaquetado.
4. Ensayos PostgreSQL 16 con backups reales sintéticos: borrado V37 recuperado,
   época de empleado atrasada, cambio de contenido con igual cantidad, inventario
   de talleres, coincidencia y captura excesiva. Guardas SQL activas.
5. Pruebas focales; después clean verify por tocar arranque y empaquetado comunes.
6. Documentación, comprobación de preservación V27–V37/frontend y commit atómico.

Validación focal aprobada: **114 pruebas (81 unitarias y 33 IT)**, sin fallos,
errores u omisiones; BUILD SUCCESS en 02:59 min. Incluye los 21 casos del ensayo de
backup, siete corrupciones sintéticas de esquema, CLI real y barrera en el JAR
principal. Una segunda restauración conserva la huella RESTORED_V37 y las capturas
lógicas coinciden. Los tres artefactos pasaron el control de propiedades secretas.

Comando focal, con Java 21 y Docker disponibles:

```sh
DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B \
  '-Dtest=Recovery*Test,WorkshopClosureBackupCheckTest' \
  '-Dit.test=RecoveryCheckpointJarIT,WorkshopClosureBackupRecoveryIT,WorkshopClosureBackupCheckIT' verify
```

La primera corrida focal detectó la incompatibilidad de representación del catálogo
histórico y una expectativa incorrecta sobre la ubicación de spring.factories en el
JAR de Boot. Se corrigió la expectativa, se agregó arranque real del JAR principal y
se construyó el perfil de lectura separado descrito arriba. Las corridas diagnósticas
no se contabilizan como pruebas adicionales aprobadas. También se corrigió la salida
de la CLI para fallar si PrintStream no puede emitir el recibo y se completó la
observación de herencia con hijos fuera de public.

Evidencia local: `/private/tmp/ordenfix-recovery-checkpoint-20260919/`, con baseline,
logs originales, catálogos sintéticos, informes focales y resumen. Claves y archivos
reales no se leyeron ni generaron.

**Gate integral aprobado**: `./mvnw -B clean verify` terminó con BUILD SUCCESS en
42:33 min: **10.070 pruebas (8.115 unitarias + 1.955 IT)**, cero fallos, errores u
omisiones. Incluye el chequeo final de los tres JAR sin propiedades secretas. No
hicieron falta correcciones ni reejecuciones después de esta corrida integral.

Se comprobó la igualdad SHA-256 de V27–V37 y de los 179 archivos no versionados
preexistentes del frontend, además de conservar su HEAD y estado de Git. Backend
parte de `91e813a` y frontend permanece en `b3d2af6`. Entrega en un único commit
`feat(cuenta): verifica recuperacion con checkpoint externo`, sin push.

El corte cierra el diagnóstico local mediante checkpoint y cuarentena configurada.
No cierra el journal continuo, la compatibilidad de escritura del backend restaurado,
la reconciliación ni la aprobación operativa para reabrir producción.
