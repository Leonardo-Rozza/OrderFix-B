# Cierre coordinado del taller — implementación por cortes

Fecha: 2026-09-12. Baselines backend `f3c67ca`, frontend `50eed86`.
Estado: A, B y C cerrados. D–E pendientes. No hay cierre de taller disponible en la UI.

## Objetivo y decisiones ya acordadas

Continúa Tarea 16 / BACKEND-HANDOFF 9 del diseño de Confianza y cuenta, después de
cerrar exportación A–D. La continuación fue autorizada por el usuario. Se mantiene:
ADMIN con email verificado, contraseña/confirmación específica, referencia durable,
exportación opcional previa, cuenta restringida, recuperación durante siete días y
eliminación posterior con reintentos. Restaurar acceso no reactiva automáticamente
una renovación. OrdenFix no procesa los cobros taller–cliente.

Se aplica brainstorming al diseño aprobado. Se compararon tres entradas: cambiar
`talleres.activo`, implementar toda la operación en un corte, y comenzar por una
preparación consistente para fijar las dependencias. Se elige la última: la bandera
actual también corta al titular el login y la exportación/restauración; por sí sola
no detiene escrituras admitidas antes del cambio ni una renovación remota. Tampoco
solicita borrado al proveedor de fotos. La implementación completa necesita resolver
esas coordinaciones antes de exponer un comando de cierre.

No se introduce una aprobación adicional para estas decisiones internas. Los plazos
de siete y treinta días concretan la propuesta técnica del diseño existente, no un
plazo legal universal. Identidad/alta/contactos, conservación de evidencia, backups y
texto contractual definitivo conservan la etapa final acordada. El borrador legal no
se publica ni se habilita cierre real en estos cortes internos.

## Secuencia acotada

| Corte | Resultado | Criterio de cierre |
| --- | --- | --- |
| A — Preparación consistente y reglas | Resumen interno autorizado de usuarios, trabajos y evidencia de renovación; política temporal pura. Sin cambios de estado ni endpoints. | JWT/ADMIN/email, dos talleres, cero DML, captura coherente y clasificación conservadora probados. |
| B — Estado durable y coordinación | Referencia/estado propios del cierre, protocolo por taller y acceso restringido. Integrar todos los escritores que deban quedar bloqueados y admisión pública. | Escrituras en curso vs transición, aislamiento/rollback, sesiones y ausencia de bypass acreditados; transición aún no expuesta hasta C. |
| C — Solicitud y restauración | Propósitos CERRAR/RESTAURAR, idempotencia, confirmación escrita y transiciones atómicas; intención durable/outbox de renovación y notificación. | Replay no renueva plazos ni repite efectos; gracia exacta; respuestas tardías MP no reactivan acceso/renovación. Proveedores sintéticos en local. |
| D — Eliminación y recuperación operativa | Trabajo acotado por categorías, archivos remotos, excepciones de retención, reintentos/alerta y restauración de backups. | No declara completado sin acreditar cada efecto; fallos mantienen restricción. Políticas reales pendientes impiden activar borrado productivo. |
| E — API, pantalla y gate integral | Página ADMIN, cuenta restringida, estado/referencia/fechas y restauración, con descargas permitidas y constancia durable. | Navegador/HTTP/PG; errores y sesiones; integral final. Activación y ensayo real separados según dependencias del despliegue. |

Un commit atómico por corte y repositorio afectado, sin push ni merge. No dividir en
subcortes por defecto: si una dependencia obliga a hacerlo, se documenta su frontera
concreta. Tests focalizados por corte; integral al cierre E o cambio transversal real.
No se repite un integral para el servicio aislado A: no modifica consumidores,
seguridad compartida, migraciones ni contratos HTTP.

## Fronteras que B–E deben resolver

- La cuenta en cierre necesita estado distinto de la inactividad actual para permitir
  sesión restringida del ADMIN. No basta aceptar JWT con taller inactivo.
- Mantener el estado individual `users.active`: restaurar no debe reactivar empleados
  que ya estaban dados de baja. Revocar versiones sin revivir JWT anteriores y sin
  repetir incrementos por replay; comprobar overflow antes de cualquier cambio.
- El gate debe cubrir transacciones, no sólo el inicio de HTTP: CRUD ordinario,
  presupuesto/seguimiento públicos, seguridad, fotos y tareas internas. Hoy existen
  órdenes de lock distintos en usuario/taller/exportaciones; definir uno compatible
  y probarlo antes de incorporar una transición exclusiva.
- Fotos alternan transacciones y acceso remoto. Una carga en curso puede terminar en
  un objeto huérfano que exige limpieza aunque se rechace su finalización.
- La cancelación MP actual combina llamada remota y actualización local sin estado
  durable de cierre. FREE, ID vacío o un flag apagado no prueban cancelación. Hay
  checkouts `creating/retryable`, históricos, webhooks y conciliación que coordinar.
- Conservar descarga de ZIP ya disponible durante la gracia según el diseño, sin
  generar nuevos ZIP/Excel operativos ni ampliar su caducidad. Inactivar taller o
  cambiar época del actor hoy revoca esos archivos: B/C deben resolverlo de forma
  explícita. No extender silenciosamente los 24 h de exportación a siete días.
- `ExportJobService.cleanup()` usa REQUIRES_NEW y el bean puede estar apagado.
  Invocarlo desde una transacción de cierre no acredita purga atómica con ese cierre.
- El borrado legal/fotos respeta restricciones V27–V32; nunca sortearlas con cascadas
  o alterando migraciones congeladas. Retención de evidencia y eliminación de activos
  requieren categorías y fundamento/plazo antes de activarse.

## Diseño del corte A

`WorkshopClosurePreparationService.prepare(accessToken)` es un servicio interno,
sin controller, ruta, bandera de cierre ni efecto ejecutable. Obtiene identidad
verificada mediante el lector existente de reautenticación: JWT criptográfico,
usuario/taller activos, ADMIN, email verificado y versión actual. Reutiliza únicamente
`authorize`; no emite/consume ninguna prueba ni reutiliza EXPORTAR para autorizar un
cierre. CERRAR/RESTAURAR siguen sin existir en el contrato de V31.

La preparación usa REQUIRES_NEW/REPEATABLE_READ con JpaTransactionManager y JDBC.
La transacción es de escritura sólo porque la autorización actual obtiene locks;
el servicio no ejecuta INSERT/UPDATE/DELETE ni cleanup. Relee y bloquea el taller
FOR SHARE, comprueba de nuevo la identidad y el vencimiento JWT al finalizar.
El snapshot no incorpora escrituras pendientes del llamador. Deadline transaccional
de 10 s, lock_timeout de 2 s y statement_timeout de 5 s: acotan esperas SQL; no se
presentan como un plazo duro de adquisición del pool o toda la JVM.

Devuelve IDs internos de vínculo, versión, nombre, momento observado, cantidades de
empleados activos/inactivos, metadata agregada de fotos/leases/limpieza,
trabajos de exportación pendientes/READY y clasificación de renovación. No recibe
taller/actor por separado ni necesita un TenantContext para resolverlos. No devuelve
emails, contraseñas, hashes, JWT, proof, URLs, claves o IDs externos; toString del
resumen es redactado. El contrato HTTP futuro deberá seleccionar campos visibles.

V26 garantiza un único ADMIN por taller, incluyendo titulares inactivos; no se modela
un segundo titular en este resumen.

Las cantidades de recursos describen filas persistidas; READY no acredita descarga
autorizada, lease no acredita I/O actual y estado de foto no demuestra borrado remoto.
No incluye blobs, fotos en claro, referencias legacy o payloads de proveedores. No
es un inventario de eliminación ni un conteo de todas las categorías del taller.

Renovación consulta la suscripción y todos sus vínculos históricos del taller, hasta
1.000 (LIMIT 1001 rechaza exceso completo). SQL sólo entrega enums/booleans al
clasificador; detecta IDs/estados contradictorios del vínculo actual dentro de la DB.

- NO_LOCAL_EVIDENCE: suscripción FREE presente y sin indicios locales ni vínculos.
  No afirma que no exista una suscripción remota.
- PROVIDER_COORDINATION_REQUIRED: evidencia comercial coherente, incluido historial
  local cancelado; no confirma una cancelación remota.
- UNCERTAIN: falta de suscripción, PRO sin respaldo vigente (el historial cancelado
  no lo sustituye), creación/reintento pendiente,
  estados/proveedor desconocidos, contradicción, más de un vínculo actual o
  historial anterior todavía no cancelado.

La lectura no congela escrituras después del commit ni concede permiso para cerrar.
La futura solicitud volverá a comprobar la evidencia dentro de su protocolo. Errores
son códigos internos SESSION_INVALID/FORBIDDEN/SOURCE_INVALID/CAPACITY_EXCEEDED/
UNAVAILABLE con mensaje fijo sin causas SQL ni valores de origen.

`WorkshopClosurePolicy` define `ordenfix-cierre/1`: siete días corridos (168 h UTC)
desde confirmación durable; treinta días de procesamiento (720 h) desde fin de
gracia. La preparación sólo informa duraciones, sin iniciar plazos ni fijar fechas
de una solicitud inexistente. `scheduleAt` normaliza a microsegundos para futura
persistencia; restaura en [confirmación, fin de gracia) y considera vencida la gracia
desde el límite exacto. Validación de null/overflow y constructor evita calendarios
inconsistentes. El cálculo no acredita eliminación ni sustituye reglas de estado.

## Validación y cierre A

Aprobado el 2026-09-12 a las 19:20:08 -03. Java 21, PostgreSQL 16 sintético,
JpaTransactionManager real, Flyway hasta V32 y JWT/BCrypt reales de prueba.

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
DOCKER_AUTH_CONFIG='{"auths":{}}' \
./mvnw -B \
  -Dtest=WorkshopClosurePolicyTest,ClosureRenewalAssessmentTest \
  -Dit.test=WorkshopClosurePreparationIT,ExportReauthenticationServiceIT verify
```

- 57 unitarias: política temporal 19; evidencia de renovación 38.
- 41 IT: preparación PostgreSQL 20; regresión de reautenticación existente 21.
- Cero fallos, errores u omisiones; BUILD SUCCESS en 58,435 s. Verificación de
  empaquetado sin propiedades secretas aprobada. Log local descartable:
  `/private/tmp/ordenfix-closure-a-verified.log`.
- Preparación: dos talleres, contexto ajeno ignorado, ADMIN/email/JWT/época activos,
  rechazo de empleado/revocación/inactividad, huella de todas las tablas sin DML y
  prueba de descarga sin consumir. Captura REPEATABLE_READ frente a commit concurrente
  real y REQUIRES_NEW frente a escritura pendiente del llamador; límite 1001,
  evidencia contradictoria/histórica, fallo final saneado y datos privados omitidos.
- Política: límites exactos, microsegundos, cambios de horario, null/overflow y
  constructor coherente. No inicia una solicitud ni acredita recuperación o borrado.

El primer pase detectó tres problemas de pruebas: un segundo ADMIN incompatible con
V26 y dos stubs configurados por delante del proxy MANDATORY. Se corrigieron los
fixtures y se configura el spy detrás del proxy, conservando autorización real.
La revisión adicional agregó la regresión de PRO sin respaldo vigente con historial
cancelado; permanece UNCERTAIN. Los resultados anteriores no sustituyen el pase final.
Las fotos de prueba sólo acreditan conteos de metadata (se suspenden dos triggers de
atestación para sembrarlas); los ZIP sintéticos no acreditan el codec ni descargas.

No se repitió clean verify: el cambio es un servicio aislado y clases nuevas, sin
modificar consumidores o seguridad compartida. El integral de exportación previo
continúa documentado en su acta; el integral de cierre corresponde a E. Frontend sólo
actualiza documentación, por lo que no requiere build ni navegador para este corte.

Revisión final y diff --check aprobados. V27–V32 conservan sus SHA-256; tampoco cambia
V26 ni se crea una migración. Se preservan los 77 archivos ajenos no versionados del
frontend. No hay cambios en secretos, configuración real, cuentas, proveedores,
frontend funcional ni texto legal publicado. Commit atómico por repositorio, sin
push ni merge. Próximo: B, estado durable y coordinación de escrituras/sesiones.


## Corte B — estado durable y coordinación

El usuario autorizó B y luego confirmó explícitamente la creación de V33, su
acreditación y las pruebas en PostgreSQL descartable. Quedó resuelta la pausa de
revisión automática previa; no se ejecutaron migraciones en bases reales.

### Persistencia y frontera interna

V33 agrega seis campos de cierre al ancla `talleres` y el historial `cuenta_cierres`:
referencia UUID, pertenencia, titular, generación, política y fechas. JPA sólo lee
los campos del ancla. Se mantienen `talleres.activo` como veto independiente y
`users.active` por empleado. Las restricciones diferidas comprueban la concordancia
entre historial y ancla al confirmar; no se permite adelantar eliminación en B.

`WorkshopClosureStore` es una frontera interna de persistencia, sin consumidores
productivos ni endpoint. Exige una transacción existente, writable, READ_COMMITTED,
y que el llamador posea el gate exclusivo antes de tomar locks de taller/usuarios.
C deberá validar identidad, contraseña, propósito y confirmación en esa misma
transacción antes de invocarlo. Los IDs internos del store no son una autorización
HTTP. B no acredita cancelación remota, eliminación de datos ni activación del cierre.

Restricción y restauración revocan todas las épocas JWT y pruebas existentes en la
misma transacción, comprobando overflow antes de escribir. Conservan la actividad
individual. El replay de una referencia compatible devuelve REUSED sin DML ni
ampliación de fechas; la colisión entre titulares/talleres falla cerrado. Una nueva
transición incrementa la generación, incluida la restauración. Restaurar sólo se
admite en [confirmación, fin de gracia); no reactiva una renovación de Mercado Pago.

### Coordinación de operaciones

El gate PostgreSQL compartido dura toda la transacción; la transición adquiere el
exclusivo primero. Los guards de fila usan try-lock compartido sin espera y rechazan
con P0034 si el exclusivo ya fue adquirido, incluso cuando el escritor trae otros
locks. La transición espera a escritores previamente admitidos. READ_COMMITTED
lee el ancla después de la admisión sin FOR SHARE: evita un upgrade compartido a
exclusivo al numerar reparaciones. Las lecturas existentes writable REPEATABLE_READ
sí bloquean el ancla para rechazar snapshots anteriores a una transición. El gate
rechaza una transacción readonly con snapshot antiguo. H2 sólo tiene una adaptación
para la suite histórica, sin pretender acreditar el protocolo PostgreSQL.

V33 agrega guards operativos sobre 28 tablas, incluidas las referencias de
idempotencia sin actos, y tres guards de historial/consistencia. Backfill de
`taller_id` en `reparacion_fotos`, `presupuesto_items` y `auth_tokens` conserva
pertenencia durante DELETE CASCADE aunque el padre ya no esté visible. No permite
reasignarla a otro taller. La eliminación ordinaria de un taller abierto conserva
el efecto referencial del QR; la cuenta con historial permanece protegida.

Las excepciones del bloqueo se delimitan por efecto: revocación, limpieza de
exportaciones/fotos, purga de metadatos vencidos y observación remota de MP. No existe
un flag de sesión para saltar el protocolo. Los guards legales anteriores siguen
aplicando sus propios límites. P0033 se traduce a 423 CUENTA_EN_CIERRE; P0034 a 503
CUENTA_NO_DISPONIBLE, con mensajes fijos y no-store, sin divulgar SQL.

### Sesiones, archivos y proveedores

Sólo el ADMIN activo, verificado y con época vigente obtiene sesión restringida
durante la gracia. La lista HTTP es exacta por método/ruta: perfil, historial legal
con sus filtros existentes, consulta de exportación y descarga de un READY previo.
Se vuelven a comprobar estado/época en los lectores sensibles. Empleados y JWT
anteriores quedan rechazados; los accesos públicos dejan de admitir operaciones.
No se admite crear ZIP, exportar Excel ni operar reparaciones en cierre.

Un READY elegible se vincula explícitamente a la nueva época del titular, conservando
bytes, captura y vencimiento original. La gracia de siete días no amplía el TTL de
24 horas del archivo. Los demás trabajos vivos se revocan y purgan atómicamente;
la restauración también los revoca. Las pruebas de descarga y los locks de archivo
se coordinan con la transición antes de entregar bytes.

Las fotos dejan de admitir creación/lectura/finalización, mientras la limpieza puede
continuar. Una carga ya iniciada conserva su clave de objeto y lease para recuperar
un ACK perdido sin declarar borrado. MP guarda IDs y observaciones tardías, pero no
concede acceso PRO ni entrega checkout tras la restricción. La cancelación remota
y las notificaciones de cierre requieren la intención durable/outbox de C.

### Acreditación de V33

V27–V32 permanecen congeladas. Se conserva la validación histórica y se agrega un
delta exacto para V33: ancla/historial, tres backfills, 28 guards, constraints,
funciones, propietarios, ACL y search_path. No se omiten objetos por prefijo. El rol
de fotos agrega únicamente SELECT(cierre_estado) sobre talleres; no obtiene acceso
a las referencias de cierre ni EXECUTE de los nuevos guards SECURITY DEFINER.
La provisión real conserva el procedimiento separado del runbook de fotos.

SHA-256 de V33: `663839f8a315b9fa8f4d467805fafee7ae6c8a3328b31e1a7fa13d5a0ebd018f`.
Checksum Flyway: `1626375596`. Huellas capturadas con migraciones limpias en PG16
mediante el diagnóstico opt-in `LegalV33SchemaSnapshot`, excluido de las suites
predeterminadas. Log: `/private/tmp/ordenfix-closure-b-v33-final-snapshot.log`.

### Validación B

Java 21 y PostgreSQL 16 descartable; importaciones de secretos y proveedores
reales deshabilitados. Se ejecutó clean verify por el cambio transversal de
seguridad/esquema. No hay ensayo nuevo de navegador: frontend sólo documenta B y
no se expone una pantalla ni un comando de cierre.

- Focales iniciales: 139 unitarias aprobadas. Los primeros pases PG detectaron una
  comparación de byte[] por referencia, prefijos incorrectos de bases de fixtures,
  tipos de excepción de drift ya detectado y una barrera DDL incompatible con la
  nueva acreditación del catálogo. Se corrigieron las pruebas sin ignorar objetos.
- Ajustes de acceso del historial/perfil: 52 unitarias y 84 IT aprobadas, cero
  fallos/errores/omisiones, 1:20 min. El historial conserva su parser único de query;
  el perfil revalida la época y el estado leídos antes del DTO. Log local:
  `/private/tmp/ordenfix-closure-b-corrections-focused.log`.
- Corrida completa: 7.754 unitarias aprobadas; 1.586 IT ejecutadas. Terminó
  BUILD FAILURE por 3 fallos y 28 errores en 12 clases, el 2026-09-12 a las
  21:03:20 -03, en 31:47 min. Log local:
  `/private/tmp/ordenfix-closure-b-clean-verify-final.log`.
- Las incidencias del integral son de fixtures/expectativas: el rechazo P0033 de
  usuario sin taller ocurre antes del NOT NULL; el clasificador SQL de capacidad
  requiere siete placeholders; cinco colegas se insertan directamente en el taller
  elegido y cuatro snapshots inconsistentes sustituyen traslados ahora prohibidos;
  el checkpoint de registro presenta IDs incompatibles sin mover usuarios; el
  harness HTTP/JPA migra a latest conservando ddl-auto=validate; la prueba de
  suspensión transaccional escribe el usuario antes de inactivar su taller, con
  ambos cambios todavía sin commit. Se conservan las comprobaciones de aislamiento,
  rechazo, rollback y ausencia de DML/efectos externos.

Repetición de las 12 clases completas: **215 IT y 3 unitarias aprobadas**, cero
fallos/errores/omisiones. BUILD SUCCESS el 2026-09-12 a las 21:10:19 -03, en 5:38 min,
incluido empaquetado sin propiedades secretas. Log:
`/private/tmp/ordenfix-closure-b-fixtures-verified.log`. Selección completa:
`AccountSessionPolicyIT,LegacyRegistrationPostCommitIT,PostgresMigrationIT,LegalAcceptanceHistoryHttpIT,LegalAcceptanceHistoryReaderIT,LegalEditorialCapacityIT,LegalPrivateRequirementsDatabaseContextIT,LegalPrivateRequirementsHttpIT,LegalPrivateRequirementsReadServiceIT,LegalRegistrationHttpIT,LegalRegistrationReplayIT,WorkshopExportLegalSnapshotIT`;
unitaria `WorkshopClosureErrorTest`, mediante `./mvnw -B -Dtest=... -Dit.test=... verify`
y el mismo JAVA_HOME/DOCKER_AUTH_CONFIG del integral.

Desde el integral no cambian los 579 archivos de producción/migraciones contrastados
por SHA-256; por eso se revalidaron las clases afectadas sin repetir la parte
aprobada. La corrida completa fallida se conserva como evidencia y no se presenta
como un clean verify exitoso en una sola ejecución. El resultado consolidado de
los informes XML mantiene las mismas clases y cantidades: **7.754 unitarias y
1.586 IT, cero fallos/errores/omisiones pendientes**, sin contar las repeticiones como
casos adicionales. Las 12 clases antes fallidas quedaron aprobadas completas.
El gate B se cierra con esta evidencia y la revisión independiente de los cambios.

Cobertura específica B: store 17, cascadas 8, HTTP 3, verificador V33 55, más
regresiones de exportaciones, reautenticación, fotos y MP. Se acreditan espera de
escritores admitidos, rechazo sin espera del nuevo escritor, dos talleres, rollback,
snapshot anterior, replay sin DML, época/overflow, READY elegible y plazos exactos.
Store/cascadas y la carrera HTTP usan el protocolo real; algunas fixtures previas
de exportación siembran metadata con privilegios owner para aislar el comportamiento
del trabajo, por lo que no sustituyen la prueba de transición del store. La barrera
de publicación ahora pausa el UPDATE real sin añadir triggers al catálogo.

V27–V32 conservan SHA-256 y el frontend sus 77 archivos ajenos no versionados.
Sin SQL sobre bases reales, push, merge, borrado real, secretos ni operaciones de
proveedores. Diff --check y revisión final aprobados. Un commit atómico por
repositorio, sin push ni merge. Próximo: C, confirmación específica, propósitos de
reautenticación y coordinación mediante outbox. B conserva su frontera interna,
sin API/UI ni activación del cierre o de eliminación real.


## Diseño C — confirmación y efectos durables

Continuación autorizada tras B. C conserva el límite interno: sin controller, ruta,
scheduler ni adaptador real de correo/MP. Se elige una tabla de confirmaciones propia
para CERRAR/RESTAURAR, preservando EXPORTAR/DESCARGAR y V31. Cada prueba dura como
máximo cinco minutos y queda ligada al titular, sesión, época, generación, propósito,
operación UUID y referencia. Sólo se guarda su SHA-256. La frase es fija y exacta:
`CERRAR MI TALLER` o `RESTAURAR MI TALLER`; no depende del nombre mutable del taller.

El coordinador abre REQUIRES_NEW/READ_COMMITTED, obtiene primero el gate exclusivo,
revalida al titular y busca una constancia de la operación. Una repetición compatible
requiere un JWT vigente —debe volver a iniciar sesión tras la revocación— y devuelve
REUSED sin consumir otra prueba, DML, nuevos plazos ni efectos. La constancia describe
el estado al confirmar, no promete que siga siendo el estado actual. Una colisión
de actor/taller/payload falla cerrada. No se acepta un JWT revocado para facilitar
replay ni se emite una sesión nueva desde el coordinador.

En una operación nueva, consumo, transición B, constancia inmutable e intenciones
se confirman juntos. El control final del vencimiento original incluye prueba, JWT
y gracia; no revalida la época que acaba de revocar la propia transición. Todo
fallo revierte prueba, épocas, historial, archivos y outbox. V34 agrega los objetos
y sus guardas sin editar V27–V33; los verificadores mantienen la acreditación
histórica y comprueban un delta exacto.

La outbox captura vínculos actuales e históricos hasta 1.000 y conserva por vínculo
una marca de cancelación aun después de restaurar el acceso o confirmar el efecto.
Los ACK tardíos se registran, pero el vínculo marcado no concede beneficios ni se
reutiliza para un checkout. Restaurar no retira la intención de cancelar renovaciones
anteriores. FREE o ausencia de ID no acreditan cancelación remota.

Un worker interno invocable usa puertos tipados y pruebas sintéticas: claim acotado,
I/O fuera de transacción y ACK condicionado por lease e identidad del objetivo.
No reutiliza el retorno void de SMTP como prueba de envío ni la cancelación global
que podría afectar otra suscripción vigente. La ambigüedad conserva un estado
visible pendiente/incierto, sin declarar éxito ni reenviar correos a ciegas.

La restauración conserva el plan previamente registrado y los datos económicos.
No retira beneficios ya existentes: bloquea nuevas activaciones y reutilización
del vínculo marcado. La propuesta de forzar FREE fue rechazada por revisión
automática por exceder la autorización económica; se descartó y no se implementó.
Se mantiene así la separación acordada entre capacidades previas y contratación.
Una revisión de renovación incierta bloquea nuevas activaciones hasta resolverla;
no se presenta la ausencia de evidencia como cancelación confirmada.

La veda sobre activaciones no suprime la semántica anterior de MP: una observación
remota validada de cancelación, para el vínculo actual de un taller abierto, sigue
el tratamiento ya existente del plan. Es un efecto acreditado del proveedor,
separado de restaurar el acceso; la restauración por sí misma conserva el plan.

### Acreditación y pruebas C

Baseline C: backend `1fa29e0`, frontend `fa9aa65`. V34 agrega tres tablas y cinco
funciones/triggers, con identidad, pertenencia, índices parciales, propietarios,
ACL y search_path acreditados. No se flexibilizan las huellas históricas ni se
otorgan permisos sobre los datos de cierre a los roles legales o de fotos.
La copia final conserva V27–V33 byte por byte.

V34 SHA-256: `738e36c5755fd17d64f908320f764add8ecea2aaf7690c6a0690813e0c0255cb`.
Checksum Flyway: `-1749634207`. Captura limpia PostgreSQL16 registrada dentro de
`/private/tmp/ordenfix-closure-c-behavior-final.log`; las capturas anteriores fueron
diagnósticos intermedios, no acreditan la versión final.

Primer pase de comandos: 21 unitarias y 31 IT aprobadas, con JWT/BCrypt reales de
prueba, PostgreSQL16 y transacciones Spring. Log:
`/private/tmp/ordenfix-closure-c-command-first.log`, BUILD SUCCESS en 56,328 s.

El primer pase ampliado ejecutó 78 pruebas Surefire (incluido el diagnóstico de
esquema) y 91 IT. Terminó BUILD FAILURE con 13 errores exclusivos del fixture nuevo
de outbox: 12 IDs remotos repetidos entre talleres sintéticos y un restub de Mockito
que ejecutaba su respuesta anterior con null. Se aislaron los IDs por taller y se
corrigió el restub, sin alterar constraints. Las otras 78 IT quedaron aprobadas.
Log: `/private/tmp/ordenfix-closure-c-behavior-final.log`.

La revisión antes del cierre agregó dos regresiones: no iniciar cancelación si la
inspección agotó la lease y preservar el tratamiento anterior de canceled/cancelled
remoto validado en un taller abierto. El ajuste no modifica el plan al restaurar.
Los nuevos casos se repiten junto con las clases MP afectadas.

El gate de consumidores también detectó un matcher de capacidad aún escrito para
siete placeholders; ahora exige exactamente ocho y LIMIT9. No se amplió el
clasificador ni se omitieron lecturas. Se repite esa clase completa tras corregir
el fixture. La evidencia final se registra a continuación.

### Cierre C — 2026-09-12, 21:44:11 -03

El gate de esquema ejecutó 225 IT y terminó con el único fallo de fixture descrito
arriba; los consumidores, los 55 casos históricos V33 y los 37 nuevos V34 pasaron.
Log: `/private/tmp/ordenfix-closure-c-schema-gate.log`.

La repetición final de las clases corregidas y regresiones MP terminó BUILD SUCCESS:
58 unitarias/servicio y 25 IT, sin fallos, errores u omisiones, en 1:23 min. Incluye
21 casos de efectos, tres integraciones MP y el probe de capacidad corregido. El
empaquetado sin propiedades secretas también pasó. Log:
`/private/tmp/ordenfix-closure-c-corrections-final.log`.

Consolidación acotada a las 19 clases de este gate: **79 unitarias/servicio y 318 IT
aprobadas (397 pruebas)**, sin fallos, errores u omisiones. No se cuentan dos veces
las repeticiones ni se presentan las corridas intermedias fallidas como exitosas.
El diagnóstico de catálogo se cuenta aparte. Manifiesto local:
`/private/tmp/ordenfix-closure-c-consolidated-results.json`. La huella final de las
588 fuentes/migraciones permanece estable.

Se acreditaron identidad y propósito, contraseña/prueba, replay sin DML, colisión
concurrente entre talleres, plazo exacto y revocación; rollback de prueba, épocas,
historial, constancia y efectos; captura de 1.000 vínculos/rechazo de 1.001; puertos
fuera de transacción, dos workers, lease vencida durante I/O/espera SQL, ACK obsoleto,
correo ambiguo, identidad remota ajena y respuestas tardías tras restaurar. Las
operaciones usan transacciones y guards reales en PostgreSQL16 descartable.

Se ejecutaron focales C y de sus consumidores; no se repitió el clean verify de B.
El integral final permanece en E: C agrega una frontera interna y adapta la
coordinación MP/esquema, sin cambiar JWT compartido, API de cuenta ni frontend.
Las pruebas afectadas por esa adaptación quedaron incluidas explícitamente.

V27–V33 congeladas e idénticas a HEAD; V34 queda congelada al cerrar este corte.
Frontend sólo documenta el resultado, por lo que no requiere build ni navegador.
Se preservan las 77 rutas ajenas no versionadas. Sin cambios en secretos, datos
reales, identidad legal, providers reales, push o merge. Un commit atómico por
repositorio afectado. Próximo: **D, eliminación por categorías y recuperación
operativa**; después E, API/pantalla y gate integral.
