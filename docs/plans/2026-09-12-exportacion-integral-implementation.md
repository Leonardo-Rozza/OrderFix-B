# Exportación integral del taller — implementación por cortes

Fecha: 2026-09-12. Baselines backend `bf9b6d9`, frontend `0d76348`.
Estado: cortes A–D cerrados localmente. La activación productiva permanece pendiente.

## Alcance acordado y secuencia

Continúa Tarea 15 / BACKEND-HANDOFF 8 del plan de Confianza y cuenta y el diseño
frontend de 2026-08-23, sección Exportación y cierre de cuenta. El usuario pidió
continuar después del ensayo local de solicitudes/baja. El cierre ADMIN permanece
como una operación posterior y separada. MP está pospuesto; identidad/alta/contactos
reales mantienen su etapa final acordada después de MP y Email.

| Corte | Resultado concreto | Criterio de cierre |
| --- | --- | --- |
| A — Reautenticación interna | Credencial opaca persistida, ligada a titular/sesión/taller/propósito y consumo transaccional único. | PostgreSQL: permisos, expiración, reemplazo, otra sesión, concurrencia y rollback acreditados. Sin endpoint ni UI todavía. |
| B — Datos y formato | Lectura consistente con campos explícitos, relaciones verificadas, categorías/exclusiones documentadas, manifiesto y archivos locales del paquete. | Dos talleres, valores sensibles y relaciones inconsistentes probados; nunca se publica un archivo parcial como completo. |
| C — Generación y conservación temporal | Trabajo durable/idempotente, archivos permitidos, cifrado, expiración, recuperación y limpieza. | Fallos/reintentos no mezclan snapshots ni tenants; revocación y eliminación temporal verificadas, sin URLs públicas permanentes. |
| D — API, pantalla y verificación final | Reautenticación HTTP con límites, solicitud/consulta/descarga, pantalla ADMIN y protección o retiro de Excel directo. | Navegador/HTTP/PostgreSQL, nueva confirmación por descarga y permisos; no queda un camino de exportación que saltee la protección acordada. |

Cada corte incluye sus pruebas focalizadas, documentación y un commit atómico por
repositorio afectado, sin push. El integral completo se reserva para el cierre
coordinado o un cambio transversal/regresión que lo justifique. Cerrar A no habilita
el handoff ni protege todavía `/api/export/excel`; ese bloqueo conserva su criterio D.
El Excel actual sigue siendo exclusivamente un reporte operativo.

## Diseño del corte A

Se elige una tabla dedicada y un servicio interno. Reutilizar `auth_tokens` de email
no ofrece vínculos de sesión/taller/versión; extender JWT o emitir otra sesión no
representa una confirmación de contraseña de un solo uso. Tampoco se agrega ahora
un endpoint que entregue credenciales sin un consumidor sensible integrado.

El servicio recibe el access token para verificarlo criptográficamente mediante
`JwtUtils`; no confía en un `jti`, actor, taller ni rol enviados por separado.
Relee el usuario y obtiene el bloqueo compartido con reset/baja/actualización de
usuarios. Verifica ADMIN activo, taller activo, versión de sesión y email verificado.
Email verificado permanece obligatorio aunque el recorrido Email C siga pendiente.
El PIN/patrón del equipo y los secretos de proveedores no intervienen.

La credencial usa 32 bytes aleatorios, codificados base64url sin padding. La tabla
sólo guarda SHA-256 del token, SHA-256 del JWT completo, IDs de usuario/taller,
versión de revocación, propósito y fechas. La sesión queda ligada al token realmente
verificado, sin alterar el principal o filtro JWT compartidos. El JWT completo y
la contraseña no se persisten ni aparecen en `toString`/errores del nuevo componente.

Propósitos iniciales: `EXPORTAR` y `DESCARGAR_EXPORTACION`. No se habilitan `CERRAR`
ni `RESTAURAR`. Vencimiento: cinco minutos como máximo, limitado además por el
vencimiento real del JWT, sin extenderlo por la tolerancia de reloj de autenticación.
Contraseña de 1–100 caracteres UTF-16, no sólo espacios y sin recortar su valor;
JWT de hasta 8192 caracteres y prueba opaca estrictamente canónica.

`issue` inicia una transacción READ_COMMITTED o participa de la transacción de
escritura existente, conservando su aislamiento. La emisión reemplaza atómicamente la
prueba previa para el mismo usuario/sesión/propósito. Una respuesta perdida requiere
confirmar la contraseña otra vez; el hash guardado no permite reproducir el secreto.
Las pruebas de otra sesión o propósito no se invalidan por ese reemplazo. Una
colisión aleatoria falla y conserva la prueba anterior, sin renovar su vencimiento.

`consume` exige una transacción de escritura ya activa (`MANDATORY`) y vuelve a
comprobar la sesión/estado. Marca un único consumo mediante actualización condicional
por hash, vínculos, propósito, versión, no usada y vencimiento. El futuro consumidor
resuelve primero el replay de su trabajo y consume la prueba en la misma transacción
que persiste el efecto. Un rollback debe restaurar prueba y efecto juntos. No se usa
una transacción independiente que consuma la autorización aunque el trabajo falle.
Tras el UPDATE se vuelve a comprobar el vencimiento devuelto por PostgreSQL: una
espera de bloqueo que excede su vigencia también revierte el consumo.

V31 agrega `cuenta_reautenticaciones`, sus restricciones e índices. No modifica
V27–V30. La nueva FK compuesta `(user_id,taller_id)` referencia `users(id,taller_id)`
y rechaza pares incoherentes; el borrado en cascada sólo descarta la autorización
temporal cuando se elimina ese usuario. No es un mecanismo de cierre del taller.
El verificador legal debe reconocer la historia exacta V31 sin cambiar los
fingerprints ni admitir deriva del catálogo legal/fotos ya acreditado con V30.
La acreditación legal no incorpora esta tabla de cuenta a su inventario: conserva
compatibilidad con una historia V30 y catálogo V30 válidos. Flyway sigue a cargo
de la migración completa al arrancar; este preflight no acredita el esquema de
reautenticación ni sustituye sus pruebas de integridad. V31 usa checksum
`518186831` y la lectura de historia admite un máximo de seis filas, incluida la
fila testigo que rechaza una extensión desconocida.
Se usa JDBC sobre el DataSource de JPA, sin una entidad adicional que haga depender
el arranque de los laboratorios V29 de esta tabla. No se agrega configuración secreta,
flag, tarea de limpieza ni rutas nuevas en A. La limpieza periódica de credenciales
vencidas y límites de emisión HTTP quedan exigidos antes de habilitar el consumidor.
Las filas de reautenticación no son el registro permanente de auditoría del trabajo.

La autorización acredita estado al comprobarlo bajo el bloqueo de usuario. No
pretende implementar el protocolo futuro de concurrencia del cierre de taller;
éste debe coordinar su propia restricción, renovaciones, datos y almacenamiento.

Referencias técnicas consultadas para compartir transacción JPA/JDBC y conservar
la semántica de rollback: [JpaTransactionManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html)
y [propagación transaccional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).

## Inventario para los siguientes cortes

La revisión encontró 48 tablas hasta V30. Un dump de entidades o tablas por
`taller_id` no es un formato de entrega: puede exponer credenciales o relaciones
inconsistentes. B debe usar proyecciones explícitas y verificar pertenencia de
cliente/equipo/técnico/artículo/reparación de origen, además del tenant principal.

| Categoría | Tratamiento previsto |
| --- | --- |
| Taller y usuarios | Datos operativos de titular/empleados, incluidos inactivos; excluir contraseña/hash y versión de sesión. |
| Clientes y equipos | Identidad/contacto y asociaciones propias del taller. |
| Reparaciones | Datos de trabajo, importes, fechas, accesorios, checklist, garantía y relaciones; excluir PIN/patrón y material de cifrado. No reutilizar el mapper que descifra credenciales. |
| Repuestos e inventario | También repuestos sin reparación y artículos inactivos. No inventar un historial de stock inexistente. |
| Presupuestos | Estado, datos e ítems; preservar duplicados sin inventar ID u orden histórico. |
| Cobros manuales | Incluir anulaciones y autor/motivo/fecha; describir registros del taller, nunca acreditaciones procesadas por OrdenFix. |
| QR de cobro | PNG almacenado en PostgreSQL, con digest comprobado y nombre de archivo generado. |
| Suscripción SaaS | Proyección comercial separada de los cobros taller–cliente; excluir diagnósticos, URLs de checkout e idempotencia del proveedor. Precisar campos/IDs comerciales en B. |
| Fotos privadas | Metadata con estados y referencias originales, aun cuando una reparación ya no exista; binarios sólo si la política vigente permite leerlos. No reponer archivos vencidos/eliminados ni exponer identidades técnicas del proveedor. |
| Fotos legacy | La URL declarada no acredita propiedad ni autoriza fetch arbitrario. Registrar su límite; la limpieza identificada de históricos sigue pendiente. |
| Constancia legal | Historial propio del solicitante y documentos exactos que le corresponden. El ADMIN no obtiene por esta vía aceptaciones personales USER que hoy sólo pueden leer sus autores. |

Quedan excluidos del paquete tokens/sesiones/reautenticaciones, contraseñas y hashes,
PIN/patrón, claves/versiones de cifrado, metadata IP/UA, HMAC y ledgers internos,
inbox/diagnósticos MP, catálogos editoriales globales, leases y asset IDs/keys del
proveedor. B documentará la inclusión o exclusión del código de seguimiento como
capacidad de acceso, además del número de orden. Las categorías faltantes nunca
se silencian detrás de la etiqueta «integral».

## Pruebas y resultado del corte A

Pruebas focalizadas previas a la comprobación final:

- `./mvnw -B -Dtest=JwtUtilsTests,JwtSecurityIntegrationTests,AccountAccessExitServiceTest test`:
  **35 aprobadas**, sin fallos ni omisiones; también compiló las nuevas pruebas.
- `./mvnw -B -Dit.test=ExportReauthenticationServiceIT,PersonalAccessExitIT failsafe:integration-test failsafe:verify`:
  **36 aprobadas** (21 reautenticación y 15 baja personal), sin fallos ni omisiones.
  Esta primera ejecución usó los recursos compilados antes de cambiar las FK simples
  por la FK compuesta. La corrida completa posterior acreditó la V31 definitiva.

El nuevo laboratorio usa PostgreSQL 16/Flyway/Hibernate validate, JWT y BCrypt
reales con identidades sintéticas. Comprueba permisos/estado/email, hash y vínculos,
ambos propósitos, reemplazo, expiración, token no canónico, JWT adulterado,
revocación de sesión, rechazo sin transacción/de sólo lectura y rollback del efecto.
La concurrencia observa una espera real en PostgreSQL; una segunda prueba adelanta
el reloj mientras el UPDATE está bloqueado y comprueba que no consuma fuera de plazo.
También fuerza una colisión aleatoria y verifica que no renueve ni borre la prueba.

Se ejecutó `clean verify` por el ajuste transversal del verificador de historia legal.
**BUILD SUCCESS**, finalizado el 2026-09-12 a las 15:48:28 -03, en 27 min 54 s:
**7.492 unitarias y 1.390 de integración, cero fallos, errores u omisiones**.
La corrida incluye nuevamente las pruebas focalizadas y la V31 definitiva con FK
compuesta. Acredita rechazo del par usuario/taller ajeno, compatibilidad V30/V31 de
los consumidores legales, deriva y versiones desconocidas rechazadas, límites de
lectura de catálogo y lectores sobre la versión actual. El guard de empaquetado
confirmó ausencia de `application-secret.properties` en los JAR.

Entorno: Amazon Corretto 21.0.10, Maven Wrapper del repositorio y PostgreSQL
`16-alpine` descartable. Comando: `JAVA_HOME=<Corretto 21> ./mvnw -B clean verify`;
registro temporal: `/private/tmp/ordenfix-export-reauth-clean-verify.log`.
Se contrastaron V27–V30 byte a byte contra HEAD: idénticas. `git diff --check`
aprobado en ambos repositorios; los 77 archivos ajenos no versionados del frontend
se preservan. No hay cambios de privilegios legales, filtros JWT ni principal.
Las pruebas no envían correos ni contactan MP/Cloudinary, no borran cuentas reales
ni cambian el funcionamiento del Excel o de las pantallas actuales. Los laboratorios
reales opt-in no forman parte del integral predeterminado. Frontend sólo actualiza
los dos documentos de seguimiento y se valida por revisión del diff.


## Cierre del corte A

A queda completo con el servicio interno, V31, compatibilidad, pruebas y esta acta.
Un commit backend y otro documental frontend, sin push ni merge. No se aplicó la
migración a una base real ni se habilitaron consumidores públicos.
Al cerrar A, el siguiente corte era **B: proyecciones de datos, pertenencia y formato del paquete**;
C y D mantienen sus criterios. El cierre ADMIN sigue fuera de esta secuencia de
exportación, como trabajo posterior, y MP permanece pospuesto.


## Diseño del corte B — datos y paquete local

Continuación autorizada por el usuario después de A. Baselines: backend `21dfffb`,
frontend `9a11d32`. Se conserva el enfoque de proyecciones explícitas del plan:
serializar entidades expondría campos privados, y leer cada categoría en su propia
transacción podría mezclar estados. Se usa una sola transacción nueva PostgreSQL
REPEATABLE_READ de sólo lectura para todas las categorías de base de datos. El
componente interno recibe IDs de actor/taller y la versión de sesión esperada desde
el futuro trabajo autorizado; recomprueba esa versión, ADMIN, actividad y email
verificado dentro del snapshot; no autentica
por sí mismo una petición HTTP. A y la autorización del trabajo se conectan en C/D.

El resultado contiene JSON UTF-8 por categoría, Markdown legal exacto y PNG del QR,
con un manifiesto de formato `ordenfix-export/1`. IDs BIGINT y decimales son cadenas
para conservar precisión; enteros y booleanos conservan su tipo. DATE y TIMESTAMP
sin zona conservan su calendario/hora sin añadir una zona; TIMESTAMPTZ se representa
en UTC. Se conservan nulls, duplicados, inactivos y registros anulados, sin recalcular
históricos. Los ítems tienen orden técnico por valores, no una posición histórica
inventada. Se validan padres mediante EXISTS; una relación ajena aborta, no desaparece
mediante un JOIN que filtre el problema.

Se excluye `codigo_seguimiento`: es una capacidad de lectura y decisión pública del
presupuesto. Se mantienen ID y número de orden. La proyección SaaS incluye sus IDs
comerciales seleccionados (preapproval y pagos), estados e importes; excluye identidad
externa del pagador, enlaces, correlación, reintentos y marcadores causales internos.
No genera comprobantes fiscales ni acredita cobros del taller a sus clientes.

El historial legal se limita al actor. Se reutiliza su acreditación de snapshots,
membresía, fuentes, transiciones y digests mediante una entrada interna específica
para el snapshot REPEATABLE_READ; el lector HTTP mantiene su contrato READ_COMMITTED
con gates. No se alteran migraciones, privilegios ni respuestas HTTP. Los documentos
se escriben como Markdown exacto, nunca HTML interpretado ni enlaces de descarga.

Las fotos privadas incluyen metadata operativa de todos los estados y referencia
original, incluso si la reparación ya no existe. Esa ausencia sólo es válida en
estado ELIMINADA, según el contrato V30; en otro estado aborta por inconsistencia.
Se listan como binarios pendientes
sólo las asociadas, conservadas y con reparación vigente al observar el snapshot;
C debe releer autorización/retención y comprobar bytes/digest antes de incorporarlas.
Las URLs legacy no se copian ni descargan: se preservan cantidad, relación y momento,
y se registra su exclusión por origen no acreditado. No se exportan atestaciones
personales de otros usuarios, asset IDs, object keys, leases ni HMAC.

El escritor local crea un directorio privado nuevo y nombres generados; nunca usa
nombres de clientes o fotografías como rutas. El manifiesto se escribe al final y
lista cantidades, tamaños y SHA-256, exclusiones y binarios pendientes. B se identifica
siempre como `DATOS_LOCALES`, con `exportacion_integral_completa=false`: no habilita
publicación, entrega ni descarga. Un fallo de escritura o validación intenta limpiar
únicamente su directorio recién creado y nunca devuelve éxito si la limpieza falla.
Una interrupción del proceso puede dejar staging huérfano: C debe reconciliarlo. C conectará generación durable/cifrado/expiración; D conectará API/pantalla.

Pruebas focalizadas previstas: PostgreSQL con dos talleres y todos los grupos de
datos, exclusión de valores sensibles, relaciones cruzadas, consistencia ante un
commit concurrente, JSON/precisión/nulls/duplicados, QR y legal con digest inválido,
permisos, límites y fallo de escritura sin manifiesto completo. Se revalidará el
lector de historial existente por la nueva entrada interna; no se repite de rutina
el clean verify de A.

Referencias: [snapshot de PostgreSQL](https://www.postgresql.org/docs/16/transaction-iso.html)
y [TransactionTemplate](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionTemplate.html).


## Contrato local implementado en B

`WorkshopExportSnapshotService.capture(actorId, tallerId, expectedTokenVersion)`
produce el snapshot en memoria al confirmar su transacción nueva, sin DML. La
versión de sesión se comprueba pero no se exporta. `LocalExportPackageWriter.write`
recibe ese resultado y una raíz privada existente: no es un controlador ni una
herramienta operativa de entrega. Los dos componentes todavía no tienen consumidor
productivo. La comprobación de autorización corresponde al instante del snapshot;
C/D deben vincular el trabajo a la prueba de A y comprobar revocación/estado de nuevo
antes de generar o entregar. No se conserva una sesión autorizada indefinidamente.

El catálogo de campos del formato está fijado por proyecciones en
`cuenta/export/ExportBusinessCatalog.java`. Son doce archivos JSON de negocio:
`talleres`, `users`, `clientes`, `equipos`, `reparaciones`, `repuestos`, `articulos`,
`presupuestos`, `presupuesto_items`, `cobros`, `suscripciones` y
`subscription_payments`. Se agregan cuatro: `fotos_privadas`, `fotos_legacy`,
`qr_cobro` y `aceptaciones_propias`. Todos existen aun cuando su arreglo esté vacío.
`LEEME.txt`, cada documento legal distinto y el QR, cuando existe, completan los
archivos locales. Los metadatos del manifiesto incluyen registros, ruta generada,
tipo, bytes y SHA-256; los binarios no tienen cantidad de registros.

Las fotos pendientes tienen UUID, ruta generada, tamaño y digest esperado, sin URL
ni identidad del proveedor. No se descargan en B. Se comprueban digest, tamaño y
firma PNG del QR local; su normalización como imagen sigue siendo responsabilidad
del flujo de carga existente. El Markdown legal se acredita con el mismo validador
canónico y digest del historial. La lectura legal usa la misma conexión y snapshot
que el negocio; la entrada HTTP anterior conserva su aislamiento y gates originales.

Límites iniciales para fallar de forma acotada, sin truncar categorías:

- 50.000 filas por consulta y 200.000 filas totales, incluidos metadatos y actos.
- 262.144 caracteres UTF-16 por campo de texto genérico; los documentos legales
  mantienen su límite canónico independiente de 1 MiB.
- 64 MiB sumados en archivos de contenido, más hasta 8 MiB para el manifiesto.
  Es un límite de contenido serializado, no una garantía de memoria RSS máxima:
  existen buffers/copias para conservar inmutabilidad. C debe limitar trabajos
  simultáneos y recursos antes de habilitar generación productiva.
- 5.000 actos legales propios, leídos de a 100 con el presupuesto cooperativo de
  15 segundos del lector. Exceso de actos se distingue de evidencia inválida.
- Transacción configurada a 60 segundos y comprobación monotónica entre operaciones;
  PostgreSQL limita cada sentencia a 15 segundos y cada espera de lock a 5 segundos.
  No se afirma un plazo duro que abarque adquisición de conexión o I/O de disco.

El escritor usa directorios 0700 y archivos 0600, verifica de nuevo bytes/SHA-256
leídos desde disco y publica `manifest.json` mediante rename atómico al final.
Rechaza rutas fuera del formato, raíz simbólica, categorías faltantes o corrupción
de archivos. El manifiesto siempre declara que la exportación integral está
incompleta, incluso cuando no hay fotos pendientes. C debe agregar cifrado,
conservación, límites de disco/tiempo, recuperación y limpieza; no puede reintentar
categorías con snapshots nuevos dentro de un mismo paquete.


## Pruebas y cierre del corte B — 2026-09-12

**109 casos focalizados distintos aprobados: 7 unitarios y 102 de integración,
sin fallos, errores ni omisiones en sus resultados finales.** Desglose:

| Suite | Casos | Evidencia |
| --- | ---: | --- |
| `LocalExportPackageWriterTest` | 7 | Manifiesto final, permisos, lectura y digest de disco, corrupción/fallo de escritura, limpieza propia, categorías faltantes, rutas y payload inmutable. |
| `WorkshopExportSnapshotServiceIT` | 20 | Dos talleres/todas las categorías de negocio, precisión, nulls y duplicados, exclusión de secretos, 9 relaciones cruzadas, 7 variantes de autorización, QR corrupto, 50.001 ítems rechazados y captura→writer real. Un commit concurrente entre clientes/equipos no mezcla versiones. |
| `WorkshopExportJpaWiringIT` | 1 | Bean real con `JpaTransactionManager`: JDBC comparte la conexión RR/readOnly nueva y no ve cambios sin confirmar del llamador; rollback exterior conservado. |
| `WorkshopExportLegalSnapshotIT` | 13 | Sólo actos propios, Markdown exacto, reemplazo/retiro editorial real, 6 corrupciones, metadata de fotos y 4 inconsistencias, incluida ASOCIADA huérfana. Sin DML del capturador. |
| `LegalAcceptanceHistoryReaderIT` | 18 | Regresión del lector existente en PostgreSQL. |
| `LegalAcceptanceHistoryHttpIT` | 50 | Regresión del contrato HTTP anterior. |

Se ejecutó primero la selección de los cinco IT con `test-compile` y Failsafe.
Los 101 casos restantes pasaron; la preparación de JPA falló por un teléfono
obligatorio ausente en un cliente sintético. Se corrigió esa fixture y la ejecución
final recompiló y aprobó las tres suites nuevas (34 IT) y las siete unitarias:

```sh
JAVA_HOME=<Corretto 21> ./mvnw -B test failsafe:integration-test failsafe:verify \
  -Dtest=LocalExportPackageWriterTest \
  -Dit.test=WorkshopExportSnapshotServiceIT,WorkshopExportJpaWiringIT,WorkshopExportLegalSnapshotIT
```

Regresión ejecutada en la primera selección, sin cambios posteriores a ese lector:
`LegalAcceptanceHistoryReaderIT,LegalAcceptanceHistoryHttpIT`. Se contrastaron los
seis reportes XML concretos, sin sumar reportes antiguos ni contar las repeticiones
como casos nuevos. La ejecución final terminó **BUILD SUCCESS** a las 16:29:44 -03,
en 1 min 9 s. Logs temporales: `/private/tmp/ordenfix-export-b-it.log` y
`/private/tmp/ordenfix-export-b-final.log`. Un intento previo con `-DskipTests` omitió
Failsafe y no se contó como validación; las corridas acreditadas no llevan ese flag.

Entorno: Corretto 21.0.10, Maven Wrapper, PostgreSQL `16-alpine` descartable con
Flyway V31; el caso Boot agrega Hibernate validate. Las fixtures adversariales de
fotos usan bypass de triggers/FK únicamente en la base descartable y conservan CHECK;
no acreditan carga ni autorización de almacenamiento remoto. No hubo llamadas a
MP/Cloudinary, envío de email, modificación de cuentas reales ni migración productiva.

V27–V31 son idénticas a HEAD. No se agregan migraciones, controllers, endpoints,
configuración ni flags. Frontend sólo actualiza los dos documentos de seguimiento;
`git diff --check` y revisión de diff en ambos repositorios. Los 77 archivos ajenos
no versionados del frontend se conservan. No se repitió `clean verify`: la única
extensión del lector compartido se cubrió con su regresión focalizada y el fallo
fue de preparación de la nueva prueba, sin regresión transversal.

**B queda cerrado localmente.** Se guarda un commit atómico backend y otro documental
frontend, sin push ni merge. El siguiente corte es **C: trabajo durable, archivos
remotos permitidos, cifrado, vencimiento y limpieza**. D conecta reautenticación,
solicitud/consulta/descarga y pantalla, y protege o retira el Excel directo. La
exportación integral para el usuario continúa pendiente de C/D; el cierre ADMIN
permanece separado y MP conserva su pausa acordada.

## Diseño del corte C — generación y conservación temporal

Continuación autorizada el 2026-09-12. Baselines backend `f7beecd`, frontend `b2e625e`.
Se aplica brainstorming sobre el alcance A–D ya acordado, sin abrir otra aprobación
para las decisiones internas. Se elige una tabla PostgreSQL de trabajos con BYTEA
cifrado temporal: permite confirmar prueba y pedido juntos, conservar una captura
entre reintentos y eliminar contenido transaccionalmente. Archivos cifrados en disco
requerirían reconciliar publicaciones/huérfanos entre dos sistemas; almacenamiento
remoto requeriría otro proveedor. No se agregan esas dependencias en C. El escritor
local de B conserva su contrato, pero el worker de C trabaja en memoria y no crea
staging ni ZIP en claro en el filesystem.

V32 agrega `cuenta_exportaciones`, sin alterar V27–V31. Identidad del trabajo UUID,
actor/taller/versión y hash de sesión/clave de idempotencia; nunca JWT o contraseña.
La reautenticación de A verifica criptográficamente y bloquea el usuario dentro de
la transacción nueva READ_COMMITTED que crea el trabajo. Replay se resuelve antes
de consumir otra prueba, ligado al actor y la misma sesión de solicitud. Otro pedido
mientras existe uno activo del taller o capacidad global ocupada conserva la prueba.
Estado, descarga y pertenencia se verifican desde la sesión actual del mismo titular
y versión; otra sesión válida puede confirmar una descarga con su propia prueba.

Estados: QUEUED, RUNNING, READY, FAILED, EXPIRED y REVOKED. Transacciones cortas
serializan los cambios de trabajos mediante un advisory lock propio. Una reserva
con UUID y vencimiento de cinco minutos identifica al worker; escrituras posteriores
vuelven a comprobarla, por lo que un proceso atrasado no modifica al sucesor.
Recuperación y errores temporales admiten hasta tres intentos. Después de guardar
la primera captura cifrada se reutiliza exactamente; antes de ese commit se puede
recapturar todo, sin combinar categorías de instantes diferentes. La captura usa
el REQUIRES_NEW/REPEATABLE_READ de B. Nunca hay I/O de proveedor dentro de una
transacción de trabajos.

El ZIP contiene los archivos del snapshot y todas sus fotos pendientes elegibles,
con nombres generados y verificación de bytes/MIME/SHA-256. Se reutiliza
`PrivatePhotoService.content`, que relee estado/actor/retención después del acceso
al proveedor. Las fotos deben seguir asociadas, pertenecer al taller y conservar
reparación/autor coherentes. Se revalida el conjunto antes de READY y de devolver
contenido; revocación o borrado veta el paquete, sin omitir una foto silenciosamente.
La versión del manifiesto permanece `ordenfix-export/1`; el ZIP reemplaza el LEEME
de preparación y marca archivos incorporados, conservando explícitas las exclusiones
acordadas de B. «Completa» refiere a ese alcance, no a restauración de fotos vencidas,
URLs legacy o secretos. No incluye aceptaciones personales de empleados.

Snapshot y ZIP se cifran con AES-256-GCM y clave exclusiva de exportación. AAD liga
versión del formato, tipo de artefacto, trabajo, taller y actor; nonce aleatorio por
cifrado. Descifrado sólo devuelve bytes después de autenticar el tag completo.
Header versionado y keyring permiten lectura de claves anteriores mientras existan
trabajos vivos; no reutilizan claves PIN, fotos o metadata legal. Sin claves válidas
la configuración opt-in falla sin imprimirlas.

Capacidad inicial: cuatro trabajos activos globales y uno por taller, un worker
reservado globalmente, hasta 256 fotos y 64 MiB de fotos, hasta 128 MiB de contenido
de ZIP. Snapshot serializado hasta 72 MiB, cifrado hasta 80 MiB y archivo cifrado hasta 140 MiB. Son límites
de contenido; cifrado/descifrado y JDBC consumen copias proporcionales en heap.
El worker tiene presupuesto cooperativo de tres minutos, dentro de una reserva de
cinco; no se promete un timeout duro de toda la JVM. Antes de producción D debe
acreditar memoria/disco de PostgreSQL y rendimiento con la capacidad del despliegue.

Caducidad máxima: 24 horas desde solicitud, acortada por la retención de sus fotos.
Limpieza elimina ciphertext y referencias al vencer o revocar usuario/taller/versión,
o quedar inválida una foto. Conservar metadata técnica de estado por siete días
permite replay sin renovar autorizaciones; después se vuelve elegible para purga de
hasta 100 filas por paso. Apagar el worker detiene la limpieza periódica; el acceso
fuera de plazo sigue vetado y el borrado ocurre en una pasada exitosa. Credenciales
de A vencidas se eliminan en lotes acotados. Borrado lógico en PostgreSQL no prueba
purga inmediata de WAL/backups: sus retenciones y recuperación siguen en el gate
operativo. No se borran datos de negocio ni fotos del proveedor en esta limpieza.

La configuración `exports.jobs.enabled` queda ausente/deshabilitada. Al habilitarla,
un executor dedicado de un hilo ejecuta un paso y limpieza cada 60 segundos después
del paso anterior; no ocupa el scheduler de fotos. C agrega servicios internos,
incluida recuperación autenticada del archivo con prueba DESCARGAR_EXPORTACION,
sin controller, ruta, UI, URLs públicas, envío de email ni activación de proveedores.
D implementará límites HTTP, pantalla y consumo por el usuario.

Pruebas focalizadas: PostgreSQL/JWT/BCrypt para atomicidad, replay, consumo de descarga,
roles/tenants/revocación, concurrencia, leases y recuperación; codec para manipulación
criptográfica/contexto y ZIP exacto; fotos sintéticas sin proveedor real. La nueva
versión de historia requiere compatibilidad legal y el integral después de esos
casos por tratarse de un verificador compartido.

Referencias: [locks PostgreSQL 16](https://www.postgresql.org/docs/16/explicit-locking.html)
y [Cipher Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html).


La revisión de concurrencia de C incorporó locks compartidos sobre creadores,
reparaciones y fotos hasta confirmar READY o recuperar el ZIP; una eliminación no
puede intercalarse después del último chequeo y antes de ese commit. No se mantienen
esos locks durante las lecturas remotas. Operación, configuración, rotación y límites
en [exports.md](../operations/exports.md). El verificador legal reconoce la historia
exacta V32 con checksum `-1414907070` y límite de siete filas incluida la fila testigo;
no amplía su catálogo legal/fotos ni acredita el esquema independiente de trabajos.
Flyway sigue acreditando/migrando el conjunto al arrancar.


## Acta de validación del corte C

Las pruebas focalizadas finales acreditan **45 unitarias y 133 de integración**, sin
fallos, errores ni omisiones. C incorpora 38 unitarias nuevas y 22 casos de
integración nuevos: trabajos (19), lectura de fotos con el protocolo real (2) e
historia V32 (1). El resto es regresión A/B/legal y migración.

- Codec y configuración: 30 + 8 casos; el escritor local B aporta 7 unitarias.
- Trabajos PostgreSQL: solicitud/prueba atómicas, replay, permisos, descarga de un
  solo uso, revocación, caducidad, capacidad, reintentos y recuperación de reservas.
- Una prueba concurrente bloquea el commit READY y acredita que el borrado de la
  foto espera los locks compartidos. Otra deja un worker atrasado durante el acceso
  remoto y comprueba que no puede alterar el archivo del sucesor.
- Fotos: se agrega el recorrido real de autorización privada con almacenamiento
  sintético, más revocación entre captura y lectura. No se llama a Cloudinary.

Evidencia focalizada en `/private/tmp/ordenfix-export-c-focused.log`,
`/private/tmp/ordenfix-export-c-corrected.log` y
`/private/tmp/ordenfix-export-c-config.log`. Los primeros intentos detectaron una
clave HMAC duplicada en la fixture, expectativas anteriores de versión/fila testigo
y la llamada normal de inicialización de Spring en una verificación del mock;
se corrigieron y se repitieron los grupos afectados completos.

El integral `clean verify` es necesario en C porque reconocer V32 extiende un
verificador compartido. Durante esa corrida se detectaron otras dos expectativas
históricas de versión actual 31 (historial de aceptación y lectura de requisitos);
se actualizan a 32 y se mantienen todas las verificaciones de evidencia histórica.
No cambió el comportamiento productivo después de iniciar el integral; sólo esas
expectativas y el comentario de la séptima fila testigo.

El comando `./mvnw -B clean verify` terminó en **28:03 min**: **7.537 unitarias sin
fallos** y **1.446 casos de integración con dos fallos**, únicamente las expectativas
V31/V32 anteriores; no hubo errores ni omisiones. El log íntegro queda en
`/private/tmp/ordenfix-export-c-clean-verify.log` y el resumen por suite, sin
propiedades ni datos de pruebas, en `/private/tmp/ordenfix-export-c-integral-summary.json`.
No se presenta esa corrida como un `clean verify` verde. La corrección no modifica
comportamiento productivo, por lo que se reejecutan ambas clases históricas completas
con `verify` y la comprobación de empaquetado, sin repetir todo el integral.

La revalidación final usa `./mvnw -B
-Dtest=ExportArtifactCodecTest,ExportJobConfigurationTest
-Dit.test=LegalAcceptanceHistoryReaderIT,LegalPrivateRequirementsReadServiceIT verify`
(argumentos en una misma línea), con el mismo Corretto 21.0.10 y PostgreSQL 16
Testcontainers. **BUILD SUCCESS en 02:55 min: 38 unitarias y 41 de integración**
(18 del historial y 23 de requisitos), sin fallos, errores ni omisiones. Pasó además
`verify-no-secret-properties-in-jar`. Evidencia en
`/private/tmp/ordenfix-export-c-history-final.log`.

La evidencia consolidada cubre **7.537 unitarias y 1.446 casos de integración distintos**,
con los dos casos corregidos y sus clases completas revalidados. Ese total combina
el integral y la revalidación focalizada; no representa otra corrida completa.
V27–V31 permanecen idénticas a los baselines. `git diff --check` aprobado en ambos
repositorios; frontend sólo registra seguimiento documental y conserva los 77
archivos ajenos no versionados. No se modificaron secretos, configuración real,
cuentas ni bases productivas; no hubo llamadas a MP/Cloudinary ni envío de correo.

**C queda cerrado localmente**, con un commit atómico backend y otro documental
frontend, sin push ni merge. El worker sigue deshabilitado por defecto y no existe
un endpoint o pantalla de exportación integral. El siguiente corte de esta secuencia
es **D: API, pantalla, límites HTTP, confirmación nueva por descarga y protección o
retiro del Excel directo**. El gate de capacidad del despliegue, la operación de
retención/recuperación y los pendientes independientes de lanzamiento permanecen
abiertos; C no habilita por sí solo publicación ni entrega al usuario.


## Diseño del corte D — entrega autenticada y pantalla

Continuación autorizada sobre backend `71558e4` y frontend `1dc3cc7`. Se aplica el
brainstorming al diseño A–D aprobado y a la sección Exportación del diseño de
Confianza y cuenta; no se abre una nueva aprobación para decisiones de implementación.
El corte conserva el Excel operativo con confirmación, en lugar de retirarlo o
mantener dos recorridos de contraseña. La página completa `/cuenta/exportacion`
reutiliza Cuenta, sus tokens y formulario de contraseña actual; evita modales largos
en móvil. Dashboard enlaza a esa página para el Excel. El ADMIN ve alcance, estado,
vencimiento, consulta/reintento y acciones; USER no monta ni solicita la exportación.

Intención visual: el titular que necesita una copia del taller debe distinguir un
reporte de cuatro resúmenes de un ZIP con datos y evidencia. Se conserva la paleta,
tipografía, superficies y bordes de Cuenta, espaciado de cuatro píxeles y controles
con área táctil de 44 px. El estado y la fecha del archivo guían la acción, sin
porcentajes ficticios ni cambios de identidad visual.

Contrato autenticado (mismos actores, talleres y versiones de A/C):

| Método y ruta | Solicitud | Resultado |
| --- | --- | --- |
| POST `/api/cuenta/reauthenticaciones` | JSON `passwordActual`, `proposito` EXPORTAR o DESCARGAR_EXPORTACION | `reauthToken`, `proposito`, `expiresAt`; no renueva sesión. |
| POST `/api/exportaciones` | Cuerpo vacío; Idempotency-Key UUID y X-Reauth-Token | 202: `id`, `estado`, `expiresAt`, `reused` y Location del recurso. |
| GET `/api/exportaciones/actual` | Sin query ni secreto adicional | `habilitada`, `exportacion` última del titular/taller/versión o null; permite recargar sin guardar IDs. |
| GET `/api/exportaciones/{id}` | UUID canónico, sin query | Estado actual propio. |
| POST `/api/exportaciones/{id}/archivo` | Cuerpo vacío y X-Reauth-Token | ZIP privado, autorización nueva por descarga. |
| POST `/api/export/excel` | Cuerpo vacío y X-Reauth-Token | Excel operativo protegido. |
| GET `/api/export/excel` | Ruta anterior | 410 sin archivo; no conserva descarga directa. |

Las descargas son POST porque consumen una credencial de un solo uso. No se publica
URL firmada ni remota: la respuesta transmite bytes autenticados directamente.
Ambos formatos usan DESCARGAR_EXPORTACION; no se inventa otro propósito ni se cambia
V31. Estados JSON conservan QUEUED/RUNNING/READY/FAILED/EXPIRED/REVOKED y se traducen
en pantalla. Los errores son sanitizados: 400 contraseña/prueba/solicitud inválida,
403 acción no permitida, 404 recurso ajeno/inexistente, 409 archivo no disponible,
429 límite con Retry-After y 503 función apagada/fallo temporal. Una contraseña
incorrecta no cierra la sesión. Todas las respuestas sensibles evitan caché.

La contraseña se limpia al enviar; proof y Blob sólo viven en variables transitorias.
No se usan mutations de React Query para conservar secretos, ni storage, URL o
errores Axios completos. Cambio de sesión/desmontaje aborta y descarta resultados;
la descarga no usa respuestas de otra sesión. Consultas pueden repetirse; una
solicitud incierta conserva su clave en memoria y verifica estado antes de crear
otra. Cada descarga requiere una confirmación nueva. El polling sólo acompaña
trabajos pendientes y respeta errores/Retry-After.

Límites HTTP propios, siempre activos: por actor/JVM, cinco confirmaciones, tres
solicitudes y tres descargas en quince minutos; treinta lecturas por minuto.
Mapa de hasta 2.048 actores: no expulsa cuotas vivas al llenarse. Como máximo dos
verificaciones de contraseña concurrentes; JSON estricto hasta 4 KiB y contraseña
1–100 caracteres sin recortar. Header único/canónico, sin query ni cuerpos no
previstos. Estos límites locales no se presentan como un rate limit distribuido;
reinicios y varias instancias requieren la política complementaria del despliegue.

Un permiso de trabajo pesado compartido por JVM serializa generación del worker
y descarga hasta finalizar la escritura HTTP síncrona. El permiso se libera también
en errores/desconexión; otra descarga no carga archivos mientras esté ocupado.
En C se mueve consume antes de leer BYTEA/descifrar, dentro de la misma transacción:
una prueba inválida no dispara el trabajo costoso y cualquier fallo antes del commit
revierte el consumo. Se conserva el chequeo final de vigencia y pertenencia. La
escritura HTTP ocurre después; una pérdida de respuesta no restaura la prueba.

Excel conserva formato y cálculos, con un wrapper nuevo REQUIRES_NEW/REPEATABLE_READ:
consume, presupuesto previo de filas/bytes del grafo leído, generación y revalidación
comparten snapshot. La lectura consistente impide que un COUNT previo bajo
READ_COMMITTED quede obsoleto antes de cargar JPA/POI. No se usan locks globales de
tablas. El permiso HTTP acota concurrencia, no acredita memoria constante; la prueba
de capacidad se documenta en el runbook. Los límites son 50.000 filas del grafo,
16 MiB de huella de origen y 32 MiB de salida; se comprueban textos descomprimidos,
relaciones inversas y pertenencia antes de entrar a POI.

La auditoría operativa registra autorización y escritura de respuesta con identificadores
y formato, sin tokens, contraseña, datos exportados ni diagnósticos de proveedor. Una
respuesta escrita no acredita recepción del cliente; recolección/retención de logs,
timeouts del proxy y capacidad del despliegue permanecen en el gate operativo.
No hay migraciones nuevas; V27–V32 permanecen congeladas. El worker continúa apagado
por defecto; Excel protegido no depende de activarlo. No se modifican secretos ni
se activan MP, email, proveedores o despliegues reales.


## Acta del corte D — validación local

No se agrega ninguna migración. La API y pantalla completan el contrato A–D, con
confirmación actual por solicitud/descarga, cuotas activas y retiro del GET Excel.
El formato del reporte y sus cálculos se conservan; los cobros siguen siendo registros
manuales ajenos al procesamiento de pagos y el reporte no tiene validez fiscal.

### Pruebas focalizadas

`./mvnw -B -Dtest=ExportArtifactCodecTest,ExportJobConfigurationTest,ExportHttpRequestsTest,ExportHttpRateLimitTest,ExportHttpGuardFilterTest,ProtectedExcelExportServiceTest,ExportServiceTest,ExportTests -Dit.test=ExportJobServiceIT,ExportReauthenticationServiceIT,ExportHttpIT,ProtectedExcelExportIT verify`:
**BUILD SUCCESS**, 01:21 min; **84 unitarias y 69 de integración**, sin fallos,
errores ni omisiones. Log: `/private/tmp/ordenfix-export-d-focused.log`.

Los 18 casos HTTP usan PostgreSQL 16/Flyway V32, Hibernate validate, JWT, BCrypt,
principal/filtros reales y MockMvc sobre la cadena de Spring Security. Cubren solicitud,
replay, recuperación desde otra sesión, ambas descargas, proof nuevo y rechazo de
permisos/cuerpo/cabeceras/cuotas/corrupción. La protección propia se prueba con rate
limit público desactivado; la plaza de trabajo ocupada conserva cuota y prueba.
Las respuestas 401 previas al filtro también conservan no-store. CORS admite los
headers del contrato; GET legado responde 410 y HEAD no entrega archivo.

Los 22 IT de trabajos incluyen recuperación del último trabajo por actor/taller/época,
rechazo de prueba antes de leer BYTEA/descifrar y revalidación final con rollback y
limpieza de plaintext. Los 21 IT de reautenticación son regresión completa de A.
Los ocho IT nuevos de Excel usan el TransactionManager/JPA reales: dos talleres y
cuatro hojas con cálculos; exceso de filas, ítems y texto TOAST rechazado antes de
POI; relaciones inversas ajenas; fallo de generación con rollback; una escritura
concurrente entre preflight y JPA no contamina el snapshot REPEATABLE_READ.
Las unitarias cubren parser estricto, cuotas monotónicas, concurrencia del permiso,
liberación en error y estructura de las transacciones. El ensayo H2 anterior conserva
la regresión del escritor y acredita el retiro GET; la seguridad nueva se prueba en PG.

### Navegador real y frontend

`./mvnw -B -Dtest=ExportHttpRequestsTest -Dit.test=ExportBrowserE2E verify`:
**BUILD SUCCESS**, 01:07 min, finalizado a las 18:16:06 -03. Sus 18 unitarias son
repetición focal; el IT opt-in orquesta **dos recorridos Playwright reales**, escritorio
y 320 px. Tomcat en loopback, PostgreSQL 16 efímero, CORS/JWT/BCrypt y descargas
reales; no hay mocks HTTP. El disparador de scheduler se acelera desde el test y
conserva el permiso compartido; el servicio y almacenamiento no se sustituyen.

Cada titular solicita desde Cuenta, espera READY, recarga para recuperar el trabajo,
confirma otra contraseña para descargar ZIP y repite confirmación para XLSX. Playwright
comprueba MIME/no-store, archivos, manifiesto y todos sus hashes; verifica inclusión
del cliente propio y ausencia del otro taller. Java vuelve a leer ZIP/POI y contrasta
hashes, dos trabajos READY y consumo durable de pruebas en PostgreSQL. No se guardan
contraseña/proof en URL o storage; las identidades del laboratorio son sintéticas.
Las fixtures pequeñas no acreditan capacidad máxima del proceso web ni fotos remotas.

Log Maven: `/private/tmp/ordenfix-export-d-browser.log`. Evidencia del laboratorio:
`/var/folders/j4/ym6p76r56xq1tcv775vjckq40000gn/T/ordenfix-export-browser-4987642637657445602`.
El comando reproducible desde frontend es `npm run test:e2e:export-real`, con Java 21,
Docker disponible y `DOCKER_AUTH_CONFIG` vacío para imágenes públicas. Vite usa
`envDir:false`; no importa secretos `.env`. Trazas, capturas y video del recorrido
real están apagados. Los artefactos descargados son sintéticos con permisos 0600.

Frontend: **730 Vitest en 99 archivos y 42 pruebas del tooling de publicación** con
`npm test`; `npm run lint` y `npm run build` aprobados. El build local omite su gate
productivo y advierte sobre el bundle principal de 577 kB; no se presenta como una
acreditación de publicación. **16 Playwright focalizados** de exportación/Cuenta/Inicio
pasaron; los cinco de exportación se repitieron tras ajustar copy/estado y ambos
recorridos visuales volvieron a comprobarse con el control real del tema oscuro.
Esas repeticiones no se suman como casos distintos. Typecheck de app y del gate real
aprobados. Se inspeccionaron escritorio y 320 px, temas claro/oscuro y formulario.
Una captura oscura tomó un frame de la transición de 150 ms; la comprobación del
color final y repetición de los dos recorridos confirmaron contraste correcto, sin
cambiar producción. Las capturas esperan el fin de animaciones. Evidencia visual:
`/var/folders/j4/ym6p76r56xq1tcv775vjckq40000gn/T/ordenfix-export-chevron-qa-5hsdacbu/`.
Los resultados frontend completos se conservaron en las salidas de herramientas;
no se generó un log independiente de esa ejecución.

El laboratorio histórico de registro/empleados se adapta al retiro GET: comprueba
entrada al recorrido protegido y 410 para ADMIN, conservando rechazos USER/anónimo.
La acreditación binaria se traslada al nuevo laboratorio ExportBrowserE2E; el gate
histórico completo no se vuelve a ejecutar en D. Sus fuentes compilan en el integral.

### Capacidad y revisión

El probe opt-in `ExportArtifactCapacityProbe` ejecutó codec real + ida/vuelta
PostgreSQL con **134.061.717 bytes expandidos**, 99,88 % de 128 MiB, y ciphertext
**117.405.731 bytes**. Pasó con heaps de 768 MiB, 1 GiB y 2 GiB. Se agrega el launcher
`scripts/run-export-capacity.sh`, compilación temporal aislada y timeout; su ejecución
con 2 GiB también pasó. Tamaños, tiempos, RSS y footprint exactos, reproducibilidad y
límites de la medición se conservan en [exports.md](../operations/exports.md).

768 MiB es el mínimo ensayado, con poco margen; no se acredita una instancia Spring
productiva de ese tamaño. El probe no ejecuta captura completa B, tráfico web o I/O
Cloudinary. Dimensionar proceso completo, PostgreSQL/WAL/backups y proxy permanece
requisito de activación. Logs: `/private/tmp/ordenfix-export-capacity-y1d2_3zk/` y
`/private/tmp/ordenfix-export-capacity-1zxadc5n/`. Se eliminaron todos los contenedores
propios de esas mediciones. No se tocaron datos/configuración reales.

Revisión independiente de autorización/rollback, cuotas y liberación de recursos,
contrato API, cancelación por sesión, errores y manejo de secretos: sin hallazgos
accionables. Se revisó el diff y la UI; no se sustituyen por ello los ensayos anteriores.

### Integral final

Comando: `JAVA_HOME=<Corretto 21.0.10> DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B clean verify`.
**BUILD SUCCESS**, finalizado el 2026-09-12 a las **18:45:27 -03**, en **28:56 min**:
**7.578 unitarias (239 suites) y 1.475 IT (103 suites), cero fallos, errores u omisiones**.
El cierre D y la integración del filtro en SecurityConfig justifican esta corrida
completa. No hubo correcciones de producción durante el integral ni revalidaciones
focales para cubrir fallos del mismo: pasó completo en una ejecución.

Log: `/private/tmp/ordenfix-export-d-clean-verify.log`; resumen contrastado de los
XML finales, sin propiedades/contenido de pruebas:
`/private/tmp/ordenfix-export-d-integral-summary.json`. Pasó
`verify-no-secret-properties-in-jar`. Los laboratorios opt-in de navegador y capacidad
son evidencia adicional separada; no se suman al total del integral.

### Cierre local

**D queda cerrado y completa la secuencia de exportación A–D.** Se conserva un commit
atómico por repositorio, sin push ni merge. V27–V32 son idénticas al baseline; no se
agrega migración ni se modifican privilegios legales. `git diff --check` aprobado y
los 77 archivos ajenos no versionados del frontend se preservan.

Excel protegido queda integrado; la generación ZIP sigue apagada por defecto. La
activación requiere claves y capacidad/operación del despliegue según el runbook;
esta acta acredita implementación y pruebas locales. No se modificaron secretos,
configuración real, cuentas ni proveedores, ni se enviaron correos. El cierre
coordinado ADMIN permanece como trabajo posterior separado; MP, Email y los contactos
reales mantienen sus pendientes y decisiones de etapa acordadas.
