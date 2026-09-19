# Cierre coordinado del taller — implementación por cortes

Fecha: 2026-09-12. Baselines backend `f3c67ca`, frontend `50eed86`.
Estado: A, B y C cerrados. D tiene mantenimiento y diagnóstico local implementados;
la eliminación integral sigue pendiente. E queda cerrado en local con API, pantalla
y gate integral aprobados. Sus flags permanecen apagados; activación productiva
condicionada por D y los requisitos reales de salida.

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
operaciones usan transacciones y guards reales en PostgreSQL 16 descartable.

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


## Avance D — mantenimiento y diagnóstico operativo

El usuario pidió detener el backend local y continuar. El proceso quedó detenido;
las nuevas operaciones se prueban sólo en PostgreSQL 16 descartable. No se ejecutan
sobre la base local utilizada para navegar ni sobre proveedores reales.

La revisión encontró una frontera concreta del alcance: V33 rechaza la transición
terminal ELIMINADO y las escrituras de negocio durante el cierre; V27/V30/V34
conservan aceptaciones, atestaciones y constancias inmutables con FK RESTRICT. La
retención real de esas categorías sigue en la etapa final acordada. Un ledger nuevo
por sí solo no acredita ni habilita borrado. Sustituir ahora esos contratos también
exigiría decidir qué identidad/evidencia conservar y cómo demostrar cada efecto.

Se compararon esa ampliación del contrato, un motor abstracto sin ejecutores y el
mantenimiento concreto permitido por las reglas vigentes. Se implementa la tercera
opción junto con inventario y comparación de backups. **D no se declara completado**:
esto entrega una capacidad local útil y comprobable sin convertir limpieza temporal
en eliminación integral. No se agrega V35 ni una cadena de subcortes nominales.

### Limpieza de datos temporales

`WorkshopClosureMaintenanceService.cleanExpired(tallerId, closureReference)` es una
frontera interna explícita, sin controller, scheduler ni llamada remota. Los IDs no
sustituyen autorización HTTP. Sólo acepta el cierre actual RESTRINGIDO, con ancla,
historial, generación, política y fechas coincidentes, después del fin de gracia.
Una referencia ajena, restaurada o anterior no puede iniciar limpieza.

Abre REQUIRES_NEW/READ_COMMITTED, timeout transaccional de 10 s, statement_timeout de 5 s y
lock_timeout de 2 s. Adquiere primero el gate exclusivo del taller; los escritores ya
admitidos terminan antes y una restauración no puede intercalarse. Cada una de las
cinco selecciones materializa como máximo 25 objetivos con FOR UPDATE SKIP LOCKED.
Se comprueba también el conteo devuelto, con rollback ante un exceso inesperado.

- Elimina tokens de recuperación/verificación vencidos, pruebas de exportación
  vencidas y confirmaciones de cierre vencidas **sin usar**. Las usadas se conservan,
  junto con las operaciones y sus efectos. Las fechas sin zona de `auth_tokens`
  respetan la zona JVM del emisor legado, sin reinterpretarlas con la sesión SQL.
- Vence exportaciones activas cuyo plazo original ya terminó, vaciando BYTEA,
  referencias de fotos y lease. Conserva identidad, captura, vencimiento y demás
  campos originales; no amplía el TTL ni intenta regenerar archivos.
- Purga metadata de exportaciones terminales vacías sólo cuando transcurrieron
  estrictamente más de siete días desde su actualización. La metadata de un archivo
  que termina en esta pasada comienza entonces esa espera existente.

Todas las categorías participan de una sola transacción. Un fallo final revierte
las anteriores. La respuesta cuenta mutaciones locales confirmadas; no es una
constancia de eliminación ni un recibo durable de proveedor. Un reintento sin
objetivos elegibles no hace DML. `moreEligibleAtObservation=false` sólo describe
esas cinco selecciones en ese instante, incluso si quedan datos con vencimiento
futuro o categorías pendientes. No prueba borrado de disco, WAL, réplicas o backups.
Los límites SQL no constituyen un deadline absoluto de pool/JVM.

### Inventario y seguimiento

`WorkshopClosureDeletionInventory.inspect` obtiene una captura independiente
REPEATABLE_READ del cierre actual, incluso después de gracia. Usa gate compartido
y ancla FOR SHARE, sin DML. Lee cantidades/booleanos y devuelve categorías de datos
operativos, identidad, evidencia legal, fotos privadas/legacy, temporales,
suscripciones/efectos e historial. No recupera emails, contraseñas, JWT, claves,
URLs, payloads ni bytes. Los subconjuntos no se suman como si fueran filas distintas.

LOCAL_ROWS_PRESENT y NO_LOCAL_ROWS son observaciones. Las categorías conservan
motivos de revisión; ninguna se presenta como eliminada o con retención aprobada.
La presencia de `payment_events` se informa separadamente como revisión global:
la tabla carece de pertenencia acreditable por taller, y una coincidencia de un ID
externo no autoriza asignarla o borrarla. El informe no es una API de usuario.

Los efectos inciertos o sin identidad y la limpieza de fotos necesitan seguimiento.
El inventario permite detectarlos; no reinicia intentos ni convierte una ausencia de
respuesta en éxito. C conserva los reintentos automáticos ya acotados. Un aviso con
ACK ambiguo no se reenvía a ciegas y una marca de cancelación no se retira al restaurar.
El envío de alertas y la resolución durable de casos inciertos siguen pendientes.

### Comparación de backups

`WorkshopClosureBackupCheck.compare` acepta entre 1 y 1.000 entradas técnicas y compara
ancla, historial, referencia/generación y época del titular en una sola SELECT,
REQUIRES_NEW/REPEATABLE_READ de sólo lectura. Distingue ausencia, retroceso, base más
nueva y divergencias. Incluso COMPARACION_COMPATIBLE devuelve NO_AUTORIZA_REAPERTURA.
DELETED siempre señala que la eliminación terminal no está implementada.

No autentica la fuente ni prueba que la lista cubra todos los cierres posteriores
al backup. No evalúa épocas de empleados, contenido de fotos, outbox ni todos los
respaldos. El [runbook de recuperación](../runbooks/cierre-recuperacion-backup.md)
explica cuarentena externa a la DB, cobertura del journal y reconciliación previa
a reabrir. No existe todavía espejo externo transaccional ni barrera automática de
arranque: la comparación es diagnóstica y el procedimiento requiere aislamiento
operativo acreditado. Un backup antiguo no puede autodeclararse reconciliado.

### Pendientes concretos para completar D

1. Política real por categoría: fundamento, datos mínimos conservados, horizonte y
   responsable. La continuación 15O, documentada abajo, implementa el mantenimiento
   de plazos técnicos ya persistidos; no decide retención de negocio o identidad.
2. Contrato de supresión de negocio/identidad y transición terminal mediante una
   migración nueva acreditada, respetando los recursos V27–V34 congelados. Las
   excepciones conservadas se informarán separadamente de los datos eliminados.
3. Ejecución de archivos remotos con identidad y recibo por objetivo, incluyendo
   cargas tardías, legacy, CDN/respaldos y reintentos. El cleanup global existente no
   acredita la eliminación de todas las fotos de un taller.
4. Resolución durable/alerta de casos inciertos, registro externo y ensayo real de
   recuperación en cuarentena antes de activar el cierre productivo.

E conserva API/pantalla y gate integral pendientes. La parte visual podrá describir
solicitud/restricción/restauración y estados pendientes; no podrá anunciar borrado
completado o publicar el cierre mientras falten los criterios integrales de salida.


### Validación del avance D

Consolidación final del 2026-09-12: **125 pruebas focales aprobadas**, sin fallos,
errores u omisiones, en ocho clases: 34 unitarias (política 19 y backup 15) y 91 IT
(mantenimiento 14, inventario 15, backup 8, store 17, comando 16 y efectos 21).
Empaquetado sin propiedades secretas aprobado. No se cuentan dos veces repeticiones.

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
DOCKER_AUTH_CONFIG='{"auths":{}}' \
./mvnw -B -Dtest=WorkshopClosurePolicyTest,WorkshopClosureBackupCheckTest \
  -Dit.test=WorkshopClosureMaintenanceIT,WorkshopClosureDeletionInventoryIT,WorkshopClosureBackupCheckIT,WorkshopClosureStoreIT,WorkshopClosureCommandIT,WorkshopClosureEffectsIT verify
```

El primer mantenimiento detectó que la selección IN con LIMIT podía afectar 26
objetivos en vez de 25. Se corrigieron las cinco selecciones con CTE MATERIALIZED y
comprobación defensiva del conteo; la repetición de 13 IT pasó. Se corrigió también
el fixture UTC: PostgreSQL JDBC envía la zona JVM al iniciar una conexión, por lo
que la prueba debe fijar UTC expresamente. Se agregó después el límite exacto de
siete días (retiene en el límite; purga desde el microsegundo siguiente).

El primer gate conjunto encontró un import faltante en el test de inventario; tras
corregirlo, la corrida conjunta de 34 unitarias/91 IT tuvo sólo un error de fixture:
dos grafos del mismo taller usaban un teléfono duplicado, prohibido por la base.
Se asignaron teléfonos distintos respetando esa restricción. La repetición final
aprobó los 15 IT de inventario y 15 unitarias de backup en 35,935 s. Los otros 110 casos
del gate ya estaban aprobados. Ninguna corrida intermedia fallida se presenta como
BUILD SUCCESS. Logs locales descartables:

- `/private/tmp/ordenfix-closure-d-maintenance-first.log` y
  `/private/tmp/ordenfix-closure-d-maintenance-corrected.log`.
- `/private/tmp/ordenfix-closure-d-focused-final.log` (import) y
  `/private/tmp/ordenfix-closure-d-focused-corrected.log` (fixture de teléfono).
- `/private/tmp/ordenfix-closure-d-inventory-corrected.log` (repetición final).
- `/private/tmp/ordenfix-closure-d-consolidated-results.json` (ocho clases, 125 casos).

Mantenimiento acredita dos talleres, referencia/gracia, lotes/replay, límite de
metadata, vencimiento en zonas diferentes, aislamiento del caller, rollback de
categorías y concurrencia real con escritores/filas bloqueadas. Inventario acredita
snapshot consistente, cero DML, gate/timeout, categorías y datos privados omitidos.
Sus contadores de fotos y algunas categorías legales se verifican vacíos: no se
pretende acreditar ejecución de borrado remoto o purga legal mediante esos casos.
Backup acredita comparación de hasta 1.000 entradas, rechazo previo de 1.001 y datos
inválidos, ausencia/retroceso/avance/divergencias, snapshot concurrente y cero DML.
No se restauró un backup productivo ni se acreditó completitud de un journal.

No se repite clean verify: las tres fronteras son nuevas e internas, sin modificar
consumidores productivos, migraciones, seguridad compartida o HTTP. El defecto de
lote estaba limitado al servicio nuevo y su repetición/regresiones quedaron
aprobadas. El integral final permanece en E. V27–V34 conservan sus SHA-256 frente a
HEAD; no hay DML en bases reales, llamadas de proveedores, lectura de secretos ni
cambios de frontend funcional. Documentación y código se entregan en un commit
atómico por repositorio afectado, sin push ni merge. D permanece parcial según las
dependencias concretas anteriores.


## Continuación D — mantenimiento legal 15O

Se completa el servicio técnico previsto por 15O, ausente al comienzo de D en
`3f907b3`. Aplica vencimientos ya registrados a metadata cifrada y resultados de
reintentos legales; conserva aceptaciones, documentos, nonce y pertenencia.
No requiere decidir nuevos plazos ni descifrar información. Funciona también tras
el cierre y después de gracia, con gates y guards V27–V34 existentes.

Usa un contexto/rol restringido independiente, preflight de esquema y privilegios,
transacción REQUIRES_NEW/READ_COMMITTED y hasta diez objetivos por categoría. El
scheduler es opcional y queda apagado, junto con la configuración. La frontera no
se importa desde la aplicación ni expone HTTP. Un error anterior a commit
revierte la pasada; un resultado incierto nunca se presenta como confirmación de limpieza. Repetir una
pasada sin objetivos no hace DML. El acta técnica y pruebas están en el apartado
15O del [plan de consentimiento](2026-09-06-legal-account-consent-implementation.md)
y el [runbook](../runbooks/legal-account-consent-postgresql.md).

Este avance resuelve un pendiente local concreto. **D sigue parcial** por las
cuatro dependencias reales enumeradas arriba: política de conservación,
supresión de negocio/identidad, recibos remotos y recuperación/conciliación externa.
No declara ELIMINADO ni modifica contratos congelados. E conserva API/pantalla y
verificación integral; su alcance local puede mostrar solicitud, restricción y
restauración, con las salidas pendientes explícitas. No debe prometer borrado
completo o efectos MP/email confirmados mediante una intención en cola.

El backend continúa detenido. No se operan datos reales ni proveedores; frontend
sólo sincroniza estos planes. Sin push ni merge, un commit atómico por repositorio.

Validación 15O consolidada: **351 pruebas aprobadas** (272 unitarias y 79 IT
PostgreSQL 16, trece clases), empaquetado sin propiedades secretas y revisión
independiente sin hallazgos materiales. Se corrigieron únicamente dos problemas
de tests: genéricos de AssertJ y datos sintéticos que duplicaban una aceptación.
El acta 15O conserva fallos intermedios, comandos y consolidación sin duplicar
repeticiones. No se repite clean verify; integral reservado para E. Los recursos
V27–V34 y los 77 archivos ajenos del frontend conservaron sus hashes.


## Corte E — API, Cuenta y sesión restringida

Implementación local del 2026-09-13, sobre backend `4754f5f` y frontend `e2bd741`.
Continúa el diseño ya aprobado: el titular ADMIN con email verificado puede solicitar
cierre, consultar la constancia y restaurar durante siete días. El alcance termina en
la integración verificable de estos servicios. **D permanece parcial y la activación
productiva sigue bloqueada** por sus dependencias concretas; E no agrega supresión
terminal ni interpreta una intención MP/email como confirmación del proveedor.

### Diseño e interacción

Se elige una página propia en `/cuenta/cierre`, accesible desde Cuenta, porque permite
leer consecuencias, preparar el respaldo opcional y consultar después la referencia
sin depender de un modal efímero. Reutiliza superficies, bordes, sombras, tipografía,
espaciado y botones existentes de Cuenta. La jerarquía presenta estado y plazo,
respaldo, confirmación y última operación; rojo sólo para confirmar cierre y el botón
primario para restaurar. Controles de al menos 44 px, foco de encabezado/error y
referencias partidas permiten operar a 320 px sin desborde horizontal.

La confirmación exige contraseña actual y `CERRAR MI TALLER` o
`RESTAURAR MI TALLER`, según el estado recién consultado. No persiste la contraseña,
la prueba de reautenticación ni el identificador del comando en localStorage,
sessionStorage, URL, historial o caché de consultas/mutaciones. El formulario evita
doble envío, limpia los campos al iniciar y protege salida/navegación pendiente.
No impone descargar un respaldo para cerrar.

Después de confirmar se vuelve a login: el cambio revoca las épocas JWT de titular
y empleados. Se conserva sólo un aviso enumerado de una vez y la ruta fija de Cuenta.
Un timeout, respuesta no validable, 401 o 5xx posterior al envío del comando muestra
resultado incierto y exige volver a ingresar y consultar; nunca reenvía el comando
automáticamente ni supone rollback por falta de ACK. Un error conocido conserva el
mensaje saneado, respeta Retry-After y vuelve a leer el estado ante 409/423.

### Frontera HTTP y lectura durable

El contrato exacto está en [FRONTEND_INTEGRATION.md](../../FRONTEND_INTEGRATION.md).
Son tres rutas ADMIN: GET `/api/cuenta/cierre`, POST
`/api/cuenta/cierre/reauthenticaciones` y POST `/api/cuenta/cierre/operaciones`.
La reautenticación ata propósito, operación y referencia a sesión/época vigentes;
el comando exige además `X-Reauth-Token` y la frase exacta. El cierre usa el mismo
UUID canónico como operación y referencia; restaurar usa una operación nueva sobre
la referencia actual. El servicio C conserva transacción, gate exclusivo, replay y
outbox existentes. No se modifica V27–V34 ni se crea otra migración.

El filtro se registra una vez después de JWT. Valida autoridad antes de leer cuerpo,
admite sólo rutas/métodos exactos, sin query, y rechaza JSON duplicado, campos
adicionales, contenido sobrante, UTF-8 inválido y cuerpos mayores de 4 KiB. Acota
cuatro solicitudes concurrentes y dos verificaciones de contraseña por instancia;
cuotas por actor con tabla acotada a 2.048 actores. No reemplaza la capacidad ni la
limitación distribuida que deba acreditar el despliegue. Respuestas y errores llevan
`private, no-store` y `nosniff`, mensajes fijos y sin datos internos de proveedor.

`WorkshopClosureStatusService.read` usa REQUIRES_NEW/READ_COMMITTED, timeout de 10 s,
gate compartido antes de locks de identidad/ancla, `lock_timeout=2s` y
`statement_timeout=5s`. La transacción es writable sólo por los locks: no hace DML.
Cruza ancla, historial y última operación del titular/taller, verifica pertenencia,
generación, estado y fechas y revalida identidad/vencimiento antes de responder.
No devuelve IDs internos, claves ni fecha prometida de eliminación. La constancia
seleccionada es histórica; la capacidad actual viene del servidor, nunca del reloj
del navegador. Los límites SQL no son un deadline absoluto de pool/JVM.

### Acceso y exportación

`GET /api/perfil` informa `accesoTaller` usando el estado actual de la base y responde
sin caché. Las rutas privadas validan primero ese perfil. Ante restricción no consultan
`/suscripcion`, no muestran navegación operativa y llevan a Cuenta/cierre; si el flag
visual está apagado, a Cuenta. Errores al validar perfil no dejan renderizar datos
operativos y permiten reintento explícito. Se tolera ausencia del campo para el
backend anterior; valores desconocidos o null se rechazan. La autoridad efectiva
continúa en backend.

Durante la gracia, Cuenta permite descargar únicamente un ZIP previo READY y no
vencido, con nueva contraseña y prueba por descarga. No crea ZIP/Excel ni extiende
las 24 h originales del respaldo a siete días. Restaurar no revive JWT anteriores,
no reactiva empleados dados de baja ni renueva automáticamente OrdenFix. Al vencer
la gracia exacta, el acceso de cierre/restauración deja de admitirse. Esta restricción
no prueba que los datos hayan sido eliminados.

### Activación y ensayo local

`ordenfix.cuenta.cierre.http-enabled` admite `true`/`false` exactos y queda apagado por
omisión. El frontend usa `VITE_WORKSHOP_CLOSURE_ENABLED=true` sólo en el ensayo local;
sin ese valor no presenta acciones habilitadas. El control de release público
rechaza expresamente ese flag activo con `WORKSHOP_CLOSURE_NOT_RELEASED`, hasta
resolver política/supresión/recibos remotos y recuperación de D. Ningún archivo real
de configuración se modifica para este ensayo. Apagar el flag HTTP oculta la API;
no restaura talleres ni revoca sus efectos pendientes.

El launcher frontend `npm run test:e2e:closure-real` compila los tests backend y ejecuta
`WorkshopClosureBrowserE2E`. Requiere Java 21, Docker y navegadores Playwright locales.
Crea PostgreSQL 16 descartable y Tomcat en un puerto aleatorio de 127.0.0.1; Vite usa
5178 con configuración aislada que no carga `.env`. Dos talleres/cuentas sintéticas
recorren escritorio y móvil 320. Exportaciones previas se producen con el codec real;
los proveedores y el worker periódico de exportación permanecen apagados. Los
procesos hijos se identifican y terminan al finalizar. No se usa 8080 ni la DB real.

### Validación E

Focal backend aprobado: **109 unitarias y 14 IT**, ocho clases, sin fallos,
errores u omisiones. Incluye lectura sin DML, cierre/replay/restauración, sesiones,
rol/email, pertenencia entre talleres, rollback de operación/outbox, límite exacto
de gracia, rutas estrictas, CORS y ZIP anterior con vencimiento original. El primer
intento falló al compilar tests por visibilidad del worker periódico de exportación;
un soporte de tests en su paquete permite pausarlo sin cambiar visibilidad de
producción. La repetición completa focal terminó BUILD SUCCESS en 46,082 s.
Logs: `/private/tmp/ordenfix-closure-e-backend-focal.log` y
`/private/tmp/ordenfix-closure-e-backend-focal-corrected.log`.

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B \
  -Dtest=WorkshopClosureHttpRequestsTest,WorkshopClosureHttpGuardFilterTest,WorkshopClosureHttpControllerTest,WorkshopClosureHttpConfigurationTest,WorkshopClosureStatusServiceTest,JwtRestrictedAccessTest,PerfilClosureAccessTests \
  -Dit.test=WorkshopClosureAccountHttpIT verify
```

Navegador real aprobado el 2026-09-13 a las 00:01:36 -03: **2/2 recorridos**
(escritorio y móvil 320), más **1/1 verificación JUnit** de efectos persistidos;
BUILD SUCCESS en 50,076 s. PostgreSQL 16 descartable, JWT/BCrypt y HTTP reales,
proveedores apagados. Acredita contraseña incorrecta sin comando, cierre/relogin,
revocación de JWT anteriores y empleado, navegación restringida sin suscripción,
ZIP previo descargable, restauración y consulta de constancia tras nueva sesión.
Comprueba dos operaciones, épocas incrementadas, historial restaurado, avisos aún
PENDIENTE, datos de cliente conservados y ZIP revocado después de restaurar. No
acredita envío ni cancelación remotos. Los ZIP del ensayo no contienen fotos reales.

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B \
  -Dit.test=WorkshopClosureBrowserE2E \
  -Dordenfix.browser.frontend=/Volumes/DiscoExtern/Desktop/mvgr-reparaciones-frontend \
  failsafe:integration-test failsafe:verify
```

Log `/private/tmp/ordenfix-closure-e-browser-real.log`; informe y cuatro capturas en
`/var/folders/j4/ym6p76r56xq1tcv775vjckq40000gn/T/ordenfix-closure-browser-9441598395334438824`.
Revisión visual independiente aprobada: texto/fechas legibles, referencias ajustadas,
controles completos y sin desborde horizontal a 320 px. Son artefactos descartables.

Frontend: **819 Vitest en 105 archivos y 43 controles de release aprobados**;
typecheck, lint y build aprobados. Se corrigió una opción no admitida por los tipos
de Testing Library. Vite informa el chunk mayor de 500 kB y el harness avisa sobre
Node 20.15.1 anterior al rango recomendado; esta corrida no acredita release público.
Playwright existente acredita **135 casos** mediante integral (131 aprobados y cuatro
expectativas móviles antiguas fallidas) más repetición completa del archivo móvil
(48/48 aprobados en cuatro proyectos). El único ajuste exige ocultar dashboard ante
un perfil 500 y recuperar por reintento explícito. No se afirma una integral única
verde ni se cuentan dos veces las repeticiones. Logs frontend en el acta
`docs/plans/2026-09-13-cierre-taller-e.md` de ese repositorio.

**Gate integral backend aprobado** el 2026-09-13 a las 00:36:01 -03:
`./mvnw -B clean verify`, Java 21 y PostgreSQL 16. **7.900 unitarias** (260 clases) y
**1.771 IT** (120 clases): **9.671 pruebas**, sin fallos, errores ni omisiones.
BUILD SUCCESS en 34:01 min. Incluye el control de empaquetado sin propiedades
secretas. Se ejecutó una vez al cierre E por los cambios compartidos de seguridad,
perfil y navegación; no se sustituyó por los resultados focales. Log
`/private/tmp/ordenfix-closure-e-backend-clean-verify.log` y manifest de 380 reportes
`/private/tmp/ordenfix-closure-e-backend-integral-results.json`.

Revisión final de código/contrato y `git diff --cached --check` aprobados. V27–V34
conservan sus ocho SHA-256; los 77 archivos ajenos no versionados del frontend
conservan sus hashes. No hubo cambios en secretos o configuraciones reales,
operaciones sobre cuentas reales ni llamadas a proveedores. Los puertos 8080, 5178 y 62907
quedaron sin listener; el backend normal sigue detenido. El ensayo no detiene
servicios ajenos.

E queda cerrado **en local**. D conserva sus dependencias reales, y la activación
productiva sigue apagada. Entrega: un commit atómico backend
`feat(cuenta): expone cierre y restauracion del taller`, acompañado por el commit
frontend `feat(cuenta): integra cierre y restauracion del taller`. Sin push ni merge.

## Ensayo local de backup — 2026-09-19

Se completó la recuperación lógica real en dos PostgreSQL 16 descartables del
[plan de ensayo](2026-09-19-recuperacion-backup-local-design.md). La nueva IT usa
`pg_dump`/`pg_restore`, fuentes migradas hasta V34 y destinos vacíos, con todas las
restricciones activas. Prueba copias anteriores al cierre/restauración, copia actual,
evidencia incompleta y archivo truncado. Incluye épocas de titular/empleados,
exportaciones, operaciones/outbox, datos de otro taller y diagnóstico sin DML.

Validación focal final: **27 casos aprobados** (15 unitarios y 12 IT), sin fallos,
errores ni omisiones; BUILD SUCCESS en 48,616 s. No cambió código productivo ni las
migraciones congeladas. El runbook contiene el comando reproducible y los límites.
La coincidencia conserva NO_AUTORIZA_REAPERTURA; no se implementó journal externo,
reconciliación, cuarentena automática ni supresión integral. D continúa parcial y
los flags productivos siguen apagados. Cambios de UI/email ajenos preservados.


## Continuación D — objetivos y constancias de fotos V35 (2026-09-19)

Se retoma el frente de cierres/datos antes de adaptar otros tipos de equipos. V35
resuelve la pérdida de identidad al terminar el borrado de una foto privada: tanto
DELETE como cleanup guardan primero el objetivo y conservan el resultado durable.
El [diseño del corte](2026-09-19-cierre-recibos-fotos-design.md) distingue identidad
eliminada, ausencia observada sin identidad y pendientes, sin backfill de históricos.

El inventario incorpora cantidades de objetivos/resultados y solicita conciliación
si hay pendientes, ausencias sin identidad o fotos ELIMINADA sin constancia. Los
recibos son evidencia técnica del adaptador; no certifican CDN, backups, cargas
tardías, fotos legacy ni eliminación integral del taller. No se ligan retrospectivamente
a una generación de cierre. La lectura interna del inventario requiere SELECT
sobre la nueva tabla además de sus lecturas anteriores.

Esto completa la constancia por objetivo de las rutas privadas actuales del punto 3
de D, no todos sus efectos remotos. Siguen pendientes la política real por categoría,
supresión de negocio/identidad y estado terminal, conciliación/alertas, journal externo
y recuperación del despliegue. E permanece aprobado en local; flags productivos
conservan su estado anterior. No se opera sobre cuentas ni fotos reales en este corte.

Corte V35 entregado en `826ea28`. La validación focal y el gate consolidado con V36
se registran en el diseño y en el plan de equipos. La integral ejecutó todas sus
clases; las dos fixtures antiguas detectadas se corrigieron y revalidaron focalmente.
Esto no cambia el estado parcial de D ni habilita supresión o reapertura productiva.


## Continuación D — conciliación de renovaciones inciertas (2026-09-19)

Se agrega `WorkshopClosureRenewalReconciler.reconcile(tallerId, effectId)` para
resolver un CANCELAR_RENOVACION incierto mediante una consulta al puerto existente.
Sólo confirma CANCELED con identidad exacta y con las versiones del efecto y vínculo
sin cambios desde la captura. Las respuestas ambiguas dejan INCIERTO sin DML;
repetir un objetivo confirmado devuelve REUSED sin proveedor ni escritura.

El [diseño del corte](2026-09-19-cierre-conciliacion-renovaciones-design.md) detalla
pertenencia, causalidad, plazo de 120 s y orden gate → vínculo → efecto. No se usa un
lease EN_CURSO: permitiría al worker existente retomar una conciliación vencida como
cancelación. No se reinician intentos, no se vuelve a cancelar ni se envían avisos.
No modifica planes, vínculos, reglas de renovación o migraciones V27–V36.

Esto resuelve localmente el caso concreto CANCELAR_RENOVACION/INCIERTO del punto 4.
Persisten REVISAR_RENOVACION, avisos inciertos, alertas, adaptador real y registro
externo. D sigue parcial, incluida la retención/supresión definitiva y recuperación
de despliegue. No habilita cierre público ni opera con proveedores reales.

Validación focal: **118 pruebas aprobadas** (57 unitarias y 61 IT), incluidas 22
nuevas de conciliación. BUILD SUCCESS en 1:06 min, PostgreSQL 16 descartable y
control de empaquetado sin propiedades secretas aprobado. Se conservaron V27–V36
y el frontend sin cambios. Detalle y comando reproducible en el diseño enlazado;
no se afirma un nuevo clean verify integral. Entrega atómica sin push.
