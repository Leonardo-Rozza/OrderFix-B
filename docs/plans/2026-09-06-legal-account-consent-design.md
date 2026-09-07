# Bloque 15 — Requisitos, aceptaciones y registro de cuenta

Fecha: 2026-09-06

Estado: 15A, 15B, 15F, 15C, 15D y 15E cerrados el 2026-09-06, con ejecución autorizada por el titular. La
[decisión 15A](2026-09-06-legal-account-consent-v29-decision.md) fija el protocolo ratificado en
FRONTEND_INTEGRATION. 15B agrega el núcleo puro; 15F implementa V29 y compatibilidad estricta,
acreditadas con un gate integral fresco de 5682 pruebas aprobadas. 15C agrega el lector privado interno,
acreditado con 430 pruebas focales y regresiones. 15D conecta el GET privado al lector, con 209
pruebas focales y regresiones aprobadas. 15E implementa historial propio con 426 pruebas focales
y regresiones aprobadas. El escritor de registro de cuenta sigue pendiente; no cambian campos ni códigos
legales del contrato. 15G1 cerrado con 294 pruebas y 15G2 con 260 pruebas focales; 15G completo.
15H1 cerrado con 259 pruebas y 15H2 con 370 pruebas el 2026-09-07; 15H completo.
15I completo en I1/I2/I3, con clean verify fresco de 6959 pruebas aprobadas. Sigue 15J.

## Objetivo y resultado esperado

Completar el punto uno del lanzamiento: consultar los requisitos del usuario autenticado,
calcular satisfacción y reaceptación, registrar evidencia propia, consultar su historial y crear
la cuenta junto con su consentimiento de forma atómica. Preparar el bloqueo legal configurable,
conservándolo apagado hasta que el frontend y las vías de salida estén disponibles.

El titular sigue siendo el único ADMIN; empleados USER no representan al taller. OrdenFix
administra reparaciones y evidencia. No procesa pagos taller-cliente, no emite comprobantes
fiscales ni participa de su acuerdo económico. Email y Mercado Pago conservan sus fases posteriores.

El resultado técnico del bloque no acredita lanzamiento, contenido legal definitivo, grants de un
entorno compartido ni BACKEND-HANDOFF 1. No incluye UI, invitaciones, PRO, fotos, exportación,
baja/cierre/restauración ni activación productiva de sus flujos.

## Baseline y fuentes

- Backend: `codex/lanzamiento-publico-backend`, HEAD `a25c7aa`, árbol limpio.
- Frontend: `codex/frontend-refactor-checkpoint`, HEAD `7545201`; preservar `.agents/` y
  `public/OrdenFix project naming/`, no versionados. Su plan general quedó registrado en 2.3C:
  las referencias antiguas a V28/GET públicos pendientes no describen el runtime backend actual.
- Bloques 12–14 cerrados: persistencia agregada V28, operación editorial y lectura pública.
  Último gate: 5.492 pruebas, 4.970 Surefire + 522 Failsafe, cero fallos/errores/omitidas,
  Java 21/PostgreSQL 16.14, 2026-09-05 17:42:44 -03:00. Es baseline, no evidencia de este bloque.
- V27/V28 están congeladas. SHA-256 respectivos:
  `52fd5f3eda14fde228e218f127b5e9362c8542dc7e26df7b502ba65061332b9b` y
  `1227c8261cfcca1263a0b2105bf0dc797c1f59f3b5bdc71225464fc4aa154a5e`.

Fuentes contractuales y técnicas:

- [Contrato HTTP](../../FRONTEND_INTEGRATION.md), sección 4.1.a.
- [Contrato legal v1](2026-08-23-legal-api-contract-v1-design.md).
- [Evidencia, cifrado e idempotencia V27](2026-08-23-legal-persistence-append-only-design.md).
- [Cierre V28](2026-09-01-legal-required-set-aggregate-v28-closure.md).
- [Cierre 14E](2026-09-05-legal-public-requirements-read-closure.md).
- [Plan de ejecución de este bloque](2026-09-06-legal-account-consent-implementation.md).

## Enfoques considerados

| Enfoque | Ventaja | Coste o incompatibilidad |
| --- | --- | --- |
| Fronteras JDBC consumidoras propias, una transacción por operación — recomendado | Reutiliza gate/store/replay y separa permisos de lectura, aceptación y alta | Requiere escritor de registro y acreditación de paridad con el alta actual |
| Compartir el datasource/JPA web para todo | Reutiliza repositorios y alta actual | Amplía privilegios web y exige acreditar mezcla JPA/JDBC, flush y manager común |
| Encadenar servicios actuales con commits independientes | Cambios locales más pequeños | No cumple atomicidad de cuenta, agregado, evidencia e idempotencia; descartado |

Se adopta el primer enfoque. No se crea un motor genérico de workflows ni una transacción
distribuida. Se conservan las fachadas públicas y sus roles; los nuevos servicios componen las
primitivas internas en su propia frontera, sin llamar a una fachada que haga otro commit.

## Decisiones ratificadas en 15A

### 1. Composición y requisitos exigibles

La primera política productiva mantiene la composición mínima acreditada:

| Operación | Perfil | Contextos | Audiencia |
| --- | --- | --- | --- |
| Alta titular | REGISTRATION | REGISTRO | ADMIN_TITULAR |
| GET/POST autenticados y decisión de gate | AUTHENTICATED_PENDING | USO_CONTINUADO | Derivada de ADMIN o USER persistidos |

Todos usan locale es-AR. Ningún input elige actor, taller, rol, audiencia, perfil o vector.
La composición se obtiene del servidor antes de leer evidencia; aceptar no elimina scopes ni
cambia el token. PRIMER_INGRESO_EMPLEADO y contextos ligados a PRO, fotos, cierre u otras acciones
se incorporarán con sus flujos y una regla de ciclo de vida explícita. No se activan por detectar
que faltan aceptaciones. Los históricos conservan ausencia de evidencia: no se backfillea REGISTRO
ni se considera aceptado el primer ingreso. La política mínima limita lo que acredita este bloque.

El agregado representa todos los requisitos de los scopes aplicables. Después se filtran los
satisfechos por evidencia exacta o herencia. Para el POST autenticado, «todos los obligatorios del
snapshot» se precisa como todos los obligatorios aún pendientes, evaluados en la misma transacción.
Puede incluir cualquier subconjunto de opcionales. En registro, sin evidencia previa, debe incluir
siempre todos los obligatorios vigentes de REGISTRO. La precisión queda documentada en el contrato en
15A, sin cambiar el token ni los campos HTTP.

### 2. Evidencia exacta y herencia

Una evidencia exacta del mismo usuario/taller satisface su versión; el rol histórico se conserva,
no se reescribe por el rol actual. La aplicabilidad actual sigue resolviéndose separadamente.
Para herencia: mismo requirement.key, documentos actuales con keys ya evidenciadas y ningún
requiresReacceptance=true en los intervalos `(evidenciada, vigente]` de requisito y documentos.
Se recorren todas las versiones que alcanzaron publicación, incluidos intermedios reemplazados o
retirados, sin filtrar la línea documental por contexto. Borradores y versiones posteriores a la
vigente no anticipan bloqueo. Un true intermedio sigue bloqueando aunque después aparezca false.
Una aceptación explícita posterior puede ser la nueva base. Un key/documento nuevo queda pendiente.

La herencia es sólo una decisión de lectura: no inserta actos ni mueve aceptadoEn. Un GET que ya
no tenga pendientes devuelve requisitos vacíos conservando la revisión de sus scopes completos.

Al cerrar 15B, esta regla quedó en un núcleo puro; 15C lo integra en su lector interno:
LegalActorSnapshot conserva identidad/rol/estado observados; LegalAuthenticatedRequirements.Snapshot
valida todos los scopes/contenido canónico y calcula sus revisiones completas; LegalRequirementLineage
indexa las líneas, versiones y referencias acreditadas; LegalRequirementSatisfactionEvaluator produce
EXACT/INHERITED/PENDING y una lista pendiente inmutable con el token original. Sólo un obligatorio
pendiente señala bloqueo. La proyección resultante no es un DTO ni un permiso de acceso.

Antes de decidir se comprueban todos los requisitos actuales y toda la evidencia entregada, incluso
si aparece una coincidencia exacta. Cada acto pertenece al mismo user/taller, conserva su rol/fecha
históricos y coincide con los digests/referencias de su versión canónica. Requisito/documento nuevo
no se satisface por coincidencia de digest ni por unir documentos de varias bases. Si varias bases
individuales sirven, todas se evalúan; el UUID menor en representación canónica sólo desempata el
identificador de evidencia explicativo, sin preferencia cronológica ni cambio de satisfacción.

Los ordinales admiten saltos y no se ordenan reparando inputs. La completitud del intervalo y la
historia de publicación requieren acreditación SQL independiente en 15C; no se deducen de ausencia
de números ni de un booleano aportado al constructor. El núcleo incluye PUBLICADA y estados
terminales del intervalo (base,objetivo], excluye BORRADOR y versiones posteriores al objetivo, y
recorre documentos por línea global. Usa índices de prefijos y búsqueda binaria para consultar
flags sin volver a escanear toda la línea por cada base. Cuenta hasta 4096 filas por intervalo,
incluidos borradores suministrados, y 65536 filas totales de versiones/enlaces/evidencia; exceso no
se trunca. El reader deberá acreditar además conteos/bytes/tiempos antes de hidratar sus inputs.

El núcleo recalcula digests de los textos actuales que recibe. El catálogo histórico sólo contiene
metadatos/digests: su coincidencia con el texto histórico y transiciones se acredita en 15C, no se
atribuye al evaluador una verificación de Markdown que no recibió. Keys conservan el schema
editorial congelado (ASCII canónico, máximo 100); no se amplían al VARCHAR(120) físico.

### 3. Vacío, duplicados y resultados sin nuevos actos

| Caso | Resolución ratificada |
| --- | --- |
| Replay de una clave exitosa | Reconstruir resultado durable antes de disponibilidad/freshness; no repetir negocio |
| Lista no vacía íntegramente confirmada de forma canónica | Tras acreditar disponibilidad, 204 antes de freshness; puede haber nuevos pendientes que el refetch mostrará |
| Lista vacía | No usar deduplicación vacía para saltar freshness; disponibilidad y revisión primero |
| Vacía con obligatorios pendientes y revisión actual | 400 ACEPTACION_LEGAL_INVALIDA / REQUISITO_FALTANTE |
| Vacía sin obligatorios pendientes y revisión actual | 204, sin nueva evidencia, con resultado idempotente durable |
| Mezcla de actos confirmados y nuevos | Freshness normal y cobertura de obligatorios pendientes; validar todos, insertar sólo los nuevos |

La deduplicación total exige una lista no vacía sin requisitos ni documentos duplicados. Si hay
duplicados, se conserva la evaluación normal: revisión primero y, si coincide, REQUISITO_DUPLICADO
o DOCUMENTO_DUPLICADO. La coincidencia exacta compara acto, confirmación, afirmación y
documentos/digests; no basta el UUID.
En el caso mixto, los actos enviados deben pertenecer al conjunto actual salvo la excepción de
repetición total anterior. Nunca se anexan actos a un lote ya confirmado ni se mueve su fecha.
Toda clave nueva que termine en éxito debe quedar protegida durante al menos 24 horas, también
cuando no produce actos. «Sin DML de evidencia» no equivale a «sin DML idempotente».

### 4. Extensión V29

La inspección del SQL confirma una incompatibilidad específica: legal_validar_lote_aceptacion()
rechaza lotes vacíos; legal_idempotencia_insert_guard() exige que el lote referenciado tenga xmin
de la transacción actual. Una nueva clave no puede apuntar a un lote histórico. V28 no cambia
esa guarda. Por tanto, el ledger actual no puede representar los éxitos sin actos de la tabla anterior.

Se elige V29 suplementaria: legal_idempotencia_sin_actos y sus referencias a aceptaciones ya
confirmadas para DEDUP/EMPTY. El ledger histórico sigue recibiendo registro y aceptación con nuevos
actos. La decisión 15A fija DDL, FKs, índices, guards transitivos, unicidad cruzada bajo advisory locks,
replay extendido mientras la fila exista y purga coordinada. No se fabrica lote, metadata ni evidencia.
Los resultados técnicos usan TTL inicial PT25H (mínimo PT24H); la retención personal no tiene default.
El retiro de keys exige cero filas en ambos ledgers y drenar operaciones de la write-version anterior.

15F incorpora V29 sin modificar bytes, filas, fechas ni relaciones históricas, ni permitir
lotes vacíos. El preflight agregado admite exactamente V28 histórico o V29 acreditado; import,
dry-run y editorial también acreditan el delta completo cuando la historia selecciona V29.
Los inventarios V27/V28 permanecen intactos. Un esquema desconocido o parcialmente migrado falla
cerrado; no se acepta cualquier versión >=28 ni se sustituyen fingerprints por existencia.
Las regresiones 12–14/CLI y el gate integral se registran en el plan compañero.

La protección elegida es un trigger global BEFORE UPDATE OF id FOR EACH STATEMENT en users y
talleres, SECURITY INVOKER, que rechaza cambios reales/no-op/cero filas, incluso del owner. Sólo
entonces se concede UPDATE(id) nominal para FOR SHARE; no UPDATE de estado, rol, password o taller.
Las guardas históricas protegen los otros grants nominales de bloqueo en agregado, punteros, lotes,
actos y cabecera de metadata. La prueba 15A atraviesa lote→todos los guards→documentos→metadata→ledger
con rol restringido; un helper aislado no sería suficiente. No se añade SECURITY DEFINER.

Esta protección también es requisito del lector privado. El orden se ajusta a A → B → F → C,
seguido de D → E → G…Q, manteniendo las etiquetas y regresiones 12–14 en F. Ningún consumidor
privado obtiene temporalmente permisos de mutación de PK sin su guarda.

## Arquitectura y flujo de datos

### Fronteras de base y actor

Tres contextos consumidores independientes: lectura privada, aceptación autenticada y registro.
Cada uno con datasource, pool, credenciales, presupuesto y verifier propios, sin parent ni fallback
al datasource web, editorial, documental o público. El lector puede materializar agregados y sólo
leer evidencia; el escritor autenticado no puede crear talleres/usuarios/suscripciones; registro
puede insertar exclusivamente las columnas de negocio; IDENTITY no requiere grants de secuencia. Un cuarto
rol de mantenimiento sólo puede purgar lo vencido según guardas. Ninguno recibe ownership/DDL.
Un rol JDBC compartido no conoce al principal HTTP por recibir un userId. El aislamiento se
acredita en principal→servicio→consulta y en las FKs de pertenencia; no se atribuye al helper SQL
una autorización por actor que no posee. No se elige imponer identidad del principal dentro de
PostgreSQL mediante una variable de sesión modificable; el rol JDBC no demuestra quién llamó al endpoint.

El AuthenticatedUserPrincipal actual ya se reconstruye desde BD por request. Los servicios reciben
una identidad interna tipada, vuelven a contrastar user/taller/rol/estado y tokenVersion en la frontera
consumidora y no aceptan un principal construido desde el DTO. Se define el punto de observación de
una lectura; una escritura mantiene estable el actor durante validación y commit. La estrategia de
locks y sus permisos se prueba antes de atribuir esa garantía al rol.

Cada operación usa una única conexión PostgreSQL y READ_COMMITTED. REQUIRES_NEW delimita sólo la
operación exterior: preflight, coordinación, gate editorial compartido, frontera temporal post-lock,
resolver, store/replay, hidratación, satisfacción y escritura corresponden a ese mismo commit.
No mezclar pools ni suponer que una anotación exterior absorbe otro REQUIRES_NEW.

Para idempotencia, separar «disponibilidad editorial» del preflight de esquema/privilegios: replay
no exige un puntero vigente, pero sí un resultado durable íntegro y actor válido. La precedencia
no autoriza omitir controles de integridad o seguridad.

### Lectura privada e historial

El reader nuevo hidrata todos los scopes aplicables antes de filtrar; no pasa por el tipo público
limitado a REGISTRATION. Reutiliza calculadores, store/replay y validadores canónicos cuando sus
contratos lo permitan, sin ampliar subrepticiamente la fachada pública.

Evidencia y linajes se consultan por usuario/taller y keys/ordinales relevantes, en batches. El
historial pagina actos, no lotes, y obtiene textos/versiones desde snapshots históricos; incluye
SCOPE_V1 genuino y AGGREGATE_V1, sin convertirlos. Orden aceptadoEn DESC, UUID DESC; page=0,
size=20 por defecto, máximo 100, contexto opcional. Omite IP, UA, claves, perfiles y procedencia.
Los GET privados responden private, no-store y no producen 304 por una condición pública.

### Escritura e idempotencia

Orden contractual: actor/sesión; formato de header presente; JSON/DTO; clasificación de registro y
header requerido; fingerprint/replay; disponibilidad; deduplicación exacta; revisión; semántica.
Los errores usan ApiError y los códigos/motivos ya congelados. 409 transporta el conjunto actual
correspondiente al perfil; nunca exponer un error genérico de integridad ni un payload sensible.

La reserva es un advisory transaction lock, no un éxito IN_PROGRESS persistido. HMAC de clave,
scope y DTO canónico con keyring propio; ordenar aceptaciones/documentos sin eliminar duplicados
antes de validarlos. La contraseña sólo participa en el HMAC de negocio y en su hash normal de
usuario. Nunca guardar body, clave cruda o JWT en un resultado idempotente.

Lookup y locks cubren todas las versiones retenidas del keyring. Orden determinista de locks;
rotación con distribución previa a todas las réplicas y una versión activa de escritura coordinada.
Una réplica sin el keyring acreditado no recibe tráfico. El límite total de espera idempotente es
5 s, no 5 s por clave; timeout devuelve 409 IDEMPOTENCY_EN_PROGRESO y Retry-After: 1.

El orden ratificado es idempotencia → gate editorial shared → advisory de actor → fila taller →
fila usuario → punteros/actos ordenados. Lectores toman advisory de actor compartido y escritores
exclusivo; actor/taller usan FOR SHARE. La rama de replay no toma después gate editorial, evitando
inversión. La decisión 15A fija derivación, permisos y fronteras de observación. Dos claves distintas
del mismo actor serializan o resuelven ON CONFLICT con relectura segura; no se captura una violación y continúa una transacción PostgreSQL abortada.
Una colisión ajena, fingerprint diferente o incoherencia nunca se convierte en replay exitoso.

La purga maneja expiración explícitamente: una fila vencida todavía puede ocupar UNIQUE. El rol de
request no gana DELETE para resolverlo. Se conserva replay mientras exista el resultado, incluso
vencido; lookup consulta ambos ledgers sin filtrar expiración. Purga y rotación respetan el protocolo de la decisión 15A. Ningún conflicto
SQL se interpreta automáticamente como éxito.

### Metadata y retención

IP resuelta desde remoteAddr y cadena de proxies/CIDR expresamente confiables, sin confiar en el
primer X-Forwarded-For. UA opcional, acotado a 512 caracteres. AES-GCM con nonces de 12 bytes, tag de
16 y AAD `ordenfix:legal-metadata:v1:<loteId>:<IP|USER_AGENT>:<keyVersion>`; keyring separado de HMAC,
JWT y credenciales de equipos. Los tombstones conservan keyVersion/nonce para evitar reutilización.

La duración de retención debe ser configuración explícita aprobada antes de habilitar escritura;
no se inventa aquí un plazo legal. Tests usan plazos sintéticos. Ausencia o invalidez de keys/IP/
retención falla cerrado antes de negocio. El mantenimiento purga sólo después de vencimiento y
no destruye evidencia contractual. No hay lectura pública de metadata ni secretos en logs.

### Registro y sesión después del commit

El nuevo writer JDBC inserta taller activo, suscripción FREE/TRIAL y ADMIN con email no verificado,
respetando defaults, Clock/trialDias, constraints e IDs RETURNING actuales. En la misma transacción
materializa el agregado y guarda actos, metadata y resultado idempotente. No consulta JPA para
observar filas aún no confirmadas. Cualquier fallo previo al commit revierte todo; una carrera de
email no deja talleres, suscripciones ni lotes huérfanos.

La respuesta 201 conserva AuthResponseDto. Después del commit se relee la identidad persistida y
se aplica una política común de emisión de sesión: usuario y taller habilitados, contraseña actual,
claims/tokenVersion actuales. AuthService hoy no verifica Taller.activo; esa paridad requiere un
corte propio y regresión, no se da por existente. Replay genera JWT nuevo, sin recrear cuenta ni
reenviar automáticamente bienvenida. Un fallo poscommit conserva la operación durable y se
reconcilia por la misma clave; no se informa como rollback. El replay localiza usuario/taller por
los IDs del resultado durable, también si el email cambió; no por el email original del request.

RegistroService hoy llama CuentaService.enviarVerificacion antes de confirmar. La integración debe
mover ese efecto después del commit, conservando el mecanismo existente sólo para un alta realmente
nueva. La creación/invalidez de auth_tokens se confirma en una transacción nueva explícita, sin
reutilizar el EntityManager del afterCommit anterior; sólo después de confirmar ese token puede
enviarse el enlace. Se prueba que el token persistido funciona en verificarEmail. No se completa
aquí transporte, entregabilidad, invitaciones ni outbox definitivo de email;
no se promete entrega garantizada, pero nunca se envía bienvenida por un alta revertida.

### Compatibilidad y bloqueo legal

Flags nuevos independientes de lectura pública, apagados por defecto, sin credenciales ambientales.
No registrar endpoints/componentes nuevos cuando su capacidad esté apagada. Para register, nunca
silenciar un bloque legal enviado porque una capacidad esté apagada: fallo cerrado explícito,
conservando validación de header/shape y sin crear cuenta. Los nombres exactos, configuración de
keyrings y matriz de flags quedan fijados en la decisión 15A; no se editan properties de entornos reales.

Enforcement registro apagado: legacy sólo si faltan los tres elementos (header, revisión y lista).
Bloque parcial → 400; completo → validación y persistencia atómicas. Enforcement encendido y ausencia
total → 428 con requisitos públicos actuales, salvo indisponibilidad que sigue siendo 503.

Gate autenticado: sólo obligatorios pendientes pueden producir 428. Ausencia/corrupción editorial
produce 503; opcionales no bloquean. Respuestas y errores privados no se cachean. Es un control
transversal sobre rutas de negocio después de autenticación y autorización suficientes para no
filtrar contratos a actores rechazados. El punto elegido es un advisor de método ROLE_INFRASTRUCTURE
con orden 401, posterior al @PreAuthorize efectivo de orden 200 y anterior al negocio; la prueba AOP 15A acredita esa cadena
con Spring real. El matcher HTTP y el gate productivo siguen pendientes de 15N. En el runtime
actual, un Filter tras AuthorizationFilter o un HandlerInterceptor aún puede preceder al
@PreAuthorize de ADMIN: la ubicación en la cadena HTTP por sí sola no prueba la precedencia. Un
USER con pendientes debe recibir 403 en una ruta ADMIN sin consultar requisitos, incluso si el
servicio legal está caído. Nunca transforma 401/403 en permiso por estar exceptuado.

Mantener excepciones exactas para los GET públicos, GET requisitos, GET/POST aceptaciones y las
rutas existentes de auth/email necesarias. Conservar el tratamiento actual de preflight, health,
seguimiento y webhook. Las rutas existentes POST /api/pagos/suscripcion/cancelar y GET
/api/export/excel no se bloquean por 428, manteniendo sus permisos actuales. No se introduce un
wildcard de cuenta/pagos/exportación ni se promete protección adicional de la exportación legacy.
Perfil/suscripción no se agregan automáticamente a excepciones; su necesidad se decide con el flujo
real. No se inventan URLs de logout, baja, cierre o supresión que todavía no existen.

El cierre técnico puede acreditar el advisor apagado y su matriz en tests. La activación general
queda pendiente de vías funcionales de baja/salida/datos por rol, frontend compatible, publicación
revisada, staging y telemetría. Nunca autoaceptar cuentas antiguas ni activar un bloqueo insoluble.

## Capacidad, fallos y verificación

Conservar límites estructurales V28 (1–8 scopes), requisitos/textos canónicos y sentinelas de lectura
acotada. Distinguir combinaciones editoriales válidas de fixtures de corrupción. El reader completo
no carga toda la historia en memoria: consultar sólo intervalos/keys pertinentes, paginar/batchear
y acreditar límites de filas, bytes, sentencias, deadline y cierre de recursos. Un exceso o una
cadena inconsistente falla cerrado, no devuelve un prefijo como conjunto completo.

Heredar el esquema de presupuesto monotónico y cancelación JDBC sin reiniciar el reloj por fase.
El presupuesto de registro contempla hashing de contraseña y no promete preempción de CPU. Los
valores concretos se eligen en la decisión 15A y se medirán al implementar cada consumidor; las
pruebas distinguen duración del GET/POST, liberación observada del servidor y resultado COMMITTED/ROLLED_BACK/UNKNOWN. No inferir SLA o heap.

Pruebas puras por semántica; PostgreSQL 16 real con roles restringidos por persistencia; HTTP por
actor, caché, error y rollout; concurrencia por clave/actor/editorial/estado de cuenta. clean verify
fresco al cierre y en los cambios transversales de migración, sesión/registro y filtro. La
inspección de XML, ambos JAR y migraciones forma parte del gate; tests viejos no certifican código nuevo.

## Estado de aprobación y siguiente paso

Planificación inicial cerrada en b51cb5a y 15A en 5054826. El núcleo puro 15B quedó implementado
y acreditado con 117 pruebas nuevas y 24 regresiones focales (141, cero fallos/errores/omitidas).
15F agrega la migración suplementaria y los preflight de compatibilidad, con 58 pruebas nuevas
PostgreSQL aprobadas (29 de esquema y 29 de protocolo), además de regresiones históricas focales.
El gate integral aprobó 5682 pruebas en 215 XML y la inspección de ambos JAR. El plan compañero registra comandos, fechas y límites de
la evidencia. No se implementan endpoints ni servicios de aplicación de este bloque. La skill brainstorming se aplicó; el formato
por cortes conserva rutas nominales, decisiones, pruebas, dependencias y commit atómico sin push.

Siguiente corte: 15C, lector privado PostgreSQL y frontera de actor,
con la protección de PK requerida por sus locks. El resto del bloque sigue
pendiente; su cierre técnico tampoco habilita lanzamiento, frontend ni enforcement productivo.

## Implementación 15C — frontera interna de lectura cerrada

El lector privado nace sobre V29 exacta y dispone de credencial propia bajo account-read, sin
registro automático ni importación HTTP en este corte. Recibe el principal servidor existente y
contrasta rol, estado, tenant y tokenVersion en una transacción nueva READ_COMMITTED. Mantiene gate
editorial compartido, advisory compartido del actor y locks de filas taller→usuario hasta confirmar.
Store/replay, composición completa, fuentes, evidencia y evaluador comparten una sola conexión.

La ACL admite SELECT nominal de 22 tablas del grafo/evidencia y siete columnas de actor, INSERT de
agregados/scopes, cuatro UPDATE de columnas protegidas exclusivamente para locks y siete funciones.
No amplía las allowlists previas ni otorga acceso a contraseña, metadata o ledgers idempotentes.
El servicio entrega el resultado únicamente tras commit, liberación y deadline final de 15 s; un
fallo de cierre tampoco permite entregar una respuesta, aunque COMMIT haya sido confirmado.

La hidratación conserva todos los scopes antes de filtrar evidencia. Su lectura de intervalos
completos y batches 32 conserva la exigencia de reaceptación de versiones intermedias; incluye
versiones publicadas nunca activadas y evita usar una versión borrador/futura como bloqueo anticipado.
Acredita pertenencia SCOPE_V1/AGGREGATE_V1 del acto a su snapshot histórico y digests de los textos
canónicos reales. Añade un techo defensivo de 128 MiB de fuentes históricas distintas, manteniendo
los límites estructurales previos y sentinelas antes de devolver una observación.

Se refresca la hora de observación después de adquirir los locks de actor. La fecha de aceptación
histórica V27 procede del comienzo de la transacción: no se exige orden relativo contra publicación,
activación o retiro para inferir causalidad física. Se comprueban fechas no posteriores a la
observación y la cadena legal de transiciones, además de pertenencia e inmutabilidad del snapshot.

Cierre acreditado: 430 pruebas únicas aprobadas, 252 nuevas y 178 regresiones, sin fallos, errores
ni omitidas. Se probaron roles, locks reales, replay sin DML, rollback/cierre, SCOPE_V1 anterior a V28,
herencia con 130 versiones reales, origen de lote y coherencia bajo aceptación concurrente. Ambos
JAR finales contienen las clases verificadas y V27/V28/V29 intactas; la evidencia detallada y las
correcciones de fixtures constan en el plan. Commit atómico local, sin push. Sigue 15D.


## Implementación 15D — adaptador HTTP privado

El GET exacto `/api/requisitos-legales` requiere una sesión ADMIN/USER. Method security rechaza un
rol no autorizado antes del lector; éste sigue contrastando la identidad con el estado persistido.
El controller recibe exclusivamente el principal tipado del servidor. No acepta query params ni
selectores de actor/tenant/perfil/contextos; su locale y revisión salen del snapshot completo.
La respuesta sólo contiene locale, requiredSetRevision y requisitos pendientes con documentos completos;
no expone procedencia, revisiones por scope, metadata de aceptación ni identidad del actor.

El puente posee y cierra su contexto sin padre y pool privado. Sólo pasan las credenciales
account-read.jdbc-url/username/password y account-read.enabled. El flag privado, apagado por defecto,
exige true/false exactos y no depende de las dos superficies públicas. No hay pool ni mappings
privados cuando está apagado; no se activaron flags ni se provisionaron roles fuera de fixtures.

El entry point opcional de SecurityConfig cambia únicamente el GET privado habilitado a 401 ante
falta de sesión; conserva el Http403ForbiddenEntryPoint existente fuera de esa clasificación y no
agrega permisos, bypass JWT ni políticas de rate limit. Controller y entry point comparten la
comprobación de URI cruda/contextPath: una equivalencia codificada que MVC haya resuelto no puede
materializar. HEAD atendido por el controller produce 405 con Allow: GET. El manejo global anterior
de métodos/vecinos sin mapping produce 500 con sesión; se caracteriza sin alterarlo en este corte.
Su corrección general queda fuera de 15D y no se presenta como comportamiento del nuevo GET.

Cada GET acredita de nuevo toda la observación y devuelve private, no-store sin ETag; If-None-Match
no abre una vía 304. Errores propios tienen no-store y un ApiError fijo: snapshot de actor inválido
401, rol sin permiso 403 y contrato indisponible 503 con contexto:null/locale:es-AR. No se transmiten
causas SQL ni payloads parciales. Timestamp conserva el formato textual del contrato tanto en MVC
como en el entry point. El token completo se mantiene al filtrar requisitos, incluso con lista vacía.

La integración utiliza el servicio 15C sin modificar su transacción, permisos o migraciones.
Se acredita por HTTP un rechazo de historia corrupta después de INSERT del agregado nuevo de otra
audiencia: ambas escrituras se revierten, ningún commit se confirma y el agregado previo permanece.
La observación estable reutiliza el agregado sin DML y no crea ni refecha evidencia. La prueba usa
sólo el owner de una base efímera para preparar historia; la ruta se ejecuta con su rol restringido.

La evidencia final y comandos se registran en el plan de implementación. No incluye historial,
aceptación, enforcement, alta atómica, frontend runtime ni un handoff de lanzamiento público.

Cierre 15D: 209 pruebas aprobadas el 2026-09-06T21:22:33-03:00, 137 nuevas y 72 regresiones,
sin fallos/errores/omitidas; artefactos y migraciones congeladas auditados. Sigue 15E: historial propio.


## Implementación 15E — historial propio paginado

GET `/api/aceptaciones-legales` obtiene sólo actos reales del principal servidor, con el mismo
permiso ADMIN/USER que requisitos. ADMIN no adquiere visibilidad sobre empleados; la consulta
siempre restringe user_id y taller_id y vuelve a acreditar estado/rol/tokenVersion del actor.
Contexto es un filtro del snapshot original, no un selector de otro usuario o de scopes actuales.

La historia reutiliza la frontera privada de 15C: misma credencial, preflight exacto V29 y
privilegios, gate editorial compartido, advisory shared del actor y filas taller/user FOR SHARE.
Una REQUIRES_NEW/READ_COMMITTED propia incluye count, página, acreditación y commit; refresca
la observación después de estabilizar actor. El servicio no tiene store ni resolver de scopes.
No crea agregados, actos ni metadata y una historia vacía es válida sin catálogo publicado.
Las fachadas de requisitos e historial comparten contexto y pool; cualquiera puede inicializarlo,
no heredan credenciales web y el cierre libera los recursos una sola vez, incluso tras un fallo.

Count parte de actos propios crudos, con filtro opcional de contexto, para no ocultar evidencia
por un JOIN a una fuente faltante. La selección de página usa LEFT JOIN, orden aceptadoEn DESC y
UUID DESC de PostgreSQL, y verifica cardinalidad exacta. PageMeta incluye cero para un filtro vacío
y preserva total fuera de la última página, con enteros seguros para JavaScript y offset long.
La comparación de UUID en el modelo respeta el orden unsigned de PostgreSQL.

Sólo se hidratan documentos/fuentes correspondientes a la página. Se acreditan snapshot de acto
y lote, keys/versiones, contexto/audiencia histórica, pertenencia SCOPE_V1 o AGGREGATE_V1, ordinales
y documentos exactos, digests y transiciones. Las igualdades de texto son binarias UTF-8 para no
depender de collation. Las fuentes pueden estar REEMPLAZADA/RETIRADA; no se sustituyen por versiones
vigentes. La fecha original SCOPE_V1 puede ser anterior a una espera de publicación de su transacción;
no se impone una causalidad de COMMIT basada en ese timestamp. Sí se exigen fuentes y fecha del
lote no posteriores a la observación, y activación documental acorde con su vigente_desde.

Límites de lectura: 100 actos por página, 16 documentos por acto, batches/fetch 32, afirmación
1000 code points/4000 bytes, Markdown de 1 MiB por fuente y presupuesto de 128 MiB de fuentes distintas.
Las cabeceras/tamaños se acreditan antes de cualquier consulta de bytes. Las sentinelas se rechazan
antes de mapear exceso. No se cargan linajes o actos fuera de página ni columnas IP/UA/HMAC/ciphertext.
Se conserva el plazo exterior de 15 s, SQL/socket de 5 s, locks/borrow/connect/cancel de 1 s y pool máximo 2 de 15A/15C;
no se introduce auto-retry ni se atribuye una capacidad integral a las pruebas focales.

Wire idéntico al contrato aprobado: content de actos/documentos originales y PageMeta, sin campos
de actor, estados actuales, metadata ni procedencia. Page/size/contexto son los únicos parámetros,
sin repeticiones ni coerción silenciosa; se mantienen los defaults 0/20/null y size 1–100. El entry point
existente agrega únicamente este GET exacto, manteniendo separada la clasificación de requisitos.
Sin sesión válida, 401; actor discordante, 401; rol no autorizado, 403 antes del lector, e indisponibilidad
503 con contexto del filtro/locale es-AR sin causas internas. Respuestas privadas no-store, sin ETag/304.
HEAD y URI equivalentes codificadas se rechazan antes del lector; rutas sin mapping conservan el
manejo global anterior documentado en 15D. No se activó account-read ni se ampliaron grants.

Pruebas PostgreSQL observan el lock de un escritor mientras el lector está detenido tras count;
la página conserva el conteo inicial y una lectura posterior observa el nuevo acto confirmado.
La evidencia legacy se crea en V27 con guards activos y luego migra a V29 preservando bytes/IDs/fechas/xmin.
REPLACE y RETIRE se ejecutan mediante los servicios editoriales existentes. La página de 100 actos
sobre 105 carga documentos en cuatro batches y deja el resto para su página correspondiente.
Las inyecciones de tamaño excesivo mantienen concordantes fuente/snapshot/digest para demostrar
rechazo por cabeceras antes de consultas text_utf8, sin DML y con rollback/cierre.

El registro de comandos, correcciones de fixtures y gate final está en el plan de implementación.
El historial no convierte la herencia en evidencia ni completa POST/idempotencia/enforcement/alta.

Cierre 15E: 426 pruebas aprobadas el 2026-09-06T21:51:19-03:00, cero fallos/errores/omitidas.
Ambos artefactos y migraciones congeladas auditados. Sigue 15G: comando canónico e idempotencia.


## Implementación 15G1 — comando y huellas protegidas

15G se divide antes de editar código: 15G1 entrega componentes puros; 15G2 mantiene pendiente la
coordinación PostgreSQL, locks, ambos ledgers y replay durable. No se habilita una escritura HTTP.
El comando sólo valida forma, copia y ordena aceptaciones/documentos por UUID unsigned con
comparación completa de desempate. Conserva multiplicidad, listas vacías y confirmado=false;
su construcción no acredita semántica, identidad persistida ni resultado confirmado.

La entrada de registro conserva valores exactos: teléfono null/vacío/espacios distintos, case,
Unicode y contraseña. Se respetan límites UTF-16 del DTO actual; email mantiene la validación
sintáctica HTTP futura, con defensa de 1 MiB UTF-8 sin introducir aquí el límite físico de 120.
UUID v4 lowercase/variante RFC se exige sólo a Idempotency-Key, no a UUID editoriales tipados.
El adaptador HTTP futuro acreditará presencia/null, JSON estricto y los límites de cuerpo/tokens.

El esquema HMAC se fija con arrays JSON canónicos RFC 8785 de dominio separado:

- Scope: `["ordenfix:legal-idempotency:scope:v1", "POST", plantilla, scope]`.
- Clave: `["ordenfix:legal-idempotency:key:v1", UUID_canónico]`.
- Fingerprint: `["ordenfix:legal-idempotency:fingerprint:v1", "POST", plantilla, scope, negocio]`.

Scope es `{"kind":"REGISTRATION"}` o `{"kind":"AUTHENTICATED","userId":"decimal"}`.
UserId se representa como string para conservar Long.MAX_VALUE y valores superiores a 2^53.
Las plantillas son exactamente /api/auth/register y /api/aceptaciones-legales; no se admiten rutas
libres. El negocio contiene los campos wire españoles, sin renombrar ni omitir valores, ordenados
canónicamente; registro incluye todos sus campos y contraseña. No se genera JSON completo ni hash
auxiliar de contraseña: un buffer pequeño alimenta Mac y se borra tras uso, incluso ante error.

Se mantiene el scope contractual por usuario. Taller/rol/tokenVersion no cambian esa tupla;
el servicio posterior debe contrastar actor y pertenencia al taller del resultado durable. El
cambio de taller no autoriza a reutilizar la clave mediante otro scope ni a devolver éxito ajeno.
La versión de clave identifica el secreto retenido y no se incorpora a los bytes HMAC. Las tres
huellas lowercase sólo salen como valores protegidos; toString omite body, clave, secreto y huellas.

El keyring copia 1–8 claves Base64 canónicas distintas de 32 bytes, exige versión activa existente
y deriva todos los candidatos en orden de versión. No expone secretos ni mutadores. Cada llamada
usa copias temporales borradas y una instancia Mac propia; no comparte estado mutable entre hilos.
La selección de versión activa no elimina candidatos viejos. Este orden no sustituye el orden
unsigned de locks físicos que deberá calcular PostgreSQL en 15G2.

TTL técnico por defecto 25 h, mínimo 24 h; se conserva precisión y se rechaza overflow de duración
nanosegundos o de expiresAt antes del futuro INSERT. No hay defaults secretos, carga de Environment,
beans, lectura de base ni retiro automático de claves. La coordinación entre réplicas y el drenaje
continúan siendo requisitos operativos de 15A, pendientes de integrar con los escritores.


Cierre 15G1: 294 pruebas aprobadas el 2026-09-06T22:39:56-03:00; 136 nuevas y 158 regresiones,
sin fallos/errores/omitidas. Vectores independientes HMAC/JCS, límites, Unicode, campos exactos,
duplicados y keyring concurrente acreditados. 15G2 conserva pendiente toda coordinación y replay
PostgreSQL; el corte puro no declara resultado durable ni rotación de réplicas implementados.

Ambos JAR auditados: diez clases nuevas idénticas a target/classes, entradas web/CLI correctas,
sin tests/propiedades secretas/duplicados y V27/V28/V29 con hashes congelados.


## Implementación 15G2 — coordinación y resultados en la transacción del escritor

El coordinador es un participante interno sobre JDBC; no crea transacciones ni administra el
commit del negocio. Acredita preflight V29 exacto, binding Spring y modo efectivo mutable
READ_COMMITTED. Su reserva captura holder, conexión y xid; el store vuelve a comprobar esa
identidad y los locks de todas las tuplas antes de leer/escribir. Un token no sirve en otra
transacción ni después de rollback; el resultado sólo puede completarse una vez. Las fronteras
15I/15L mantendrán REQUIRES_NEW exterior, credenciales, verificador de privilegios y presupuesto
desde antes de pool/BCrypt. Este corte no amplía el gate editorial existente ni invierte sus locks.

Todos los candidatos del keyring pasan a PostgreSQL con bindings para derivar las claves físicas
V29. Se ordenan como BIGINT unsigned y sólo se deduplican locks físicos; el lookup conserva cada
tupla lógica. Un reloj monotónico único de cinco segundos acota la espera acumulada. Cada lock
recibe lock_timeout en milisegundos enteros redondeados hacia abajo, nunca cero; se comprueba el
remanente después de cada espera. La indisponibilidad del presupuesto exterior o una interrupción
no se informa como contención. Una cancelación administrativa tampoco se interpreta como éxito
ni como replay. El SQLSTATE/causa quedan internos y un fallo marca rollback-only sin reintentos
ni más SQL sobre una transacción abortada.

Después de adquirir todos los locks, el store lee cabeceras crudas de ambos ledgers por sus cuatro
campos de tupla. No usa versión HMAC ni expires_at como filtros. Comprueba multiplicidad, versión,
fingerprint completo e identidad, conservando BIGINT y UUID en orígenes distintos. Un MISS no
retiene locks de actor ni editoriales; el futuro escritor puede seguir el orden idempotencia,
editorial compartido, actor exclusivo y filas. Un replay estabiliza actor/taller con advisory
compartido y FOR SHARE, sin adquirir después el gate editorial. No consulta punteros actuales,
contraseña, email, Markdown ni metadata personal para recuperar el resultado durable.

El scope por usuario se mantiene aunque cambie rol/tokenVersion; esos valores actuales se
contrastan con el actor autenticado. El taller del resultado también debe coincidir. Para registro,
los IDs provienen exclusivamente del resultado persistido y su lote; la contraseña actual y la
sesión poscommit siguen siendo responsabilidad de 15K/15L/15M. Un recibo interno no equivale a una
respuesta HTTP ni autoriza omitir esos pasos.

WITH_ACTS preserva la identidad del lote nuevo y permite que parte de los actos enviados ya exista
en otros lotes. EMPTY/DEDUP usan el ledger suplementario y referencias exactas a actos confirmados;
no fabrican lotes, actos o fechas de evidencia. Los checks de snapshots, documentos, referencias,
pertenencia y tiempos históricos no dependen de que el catálogo editorial siga vigente. La fecha
de expiración parte del completed_at que fija la guarda: aceptado_en del lote histórico o
statement_timestamp del mismo INSERT suplementario. La validación semántica de pendientes y el
commit del flujo completo siguen en el escritor 15I/15L.


Durabilidad de filas visibles: liberar SAVEPOINT no confirma el resultado. La prueba PostgreSQL
mostró que pg_locks ya no conserva el lock del hijo liberado, por lo que se consulta
pg_xact_status. La distancia unsigned de 32 bits desde el xid del padre reconstruye los candidatos
posteriores, incluido el cruce de epoch; los anteriores no pueden ser hijos propios. No se usa
age(), cuyo ancla puede haberse fijado antes de asignar el xid del padre, ni visibilidad de snapshots
que omite subxids. Estados no confirmados, desconocidos o errores fallan cerrado.

Límite conservador: una fila histórica congelada puede conservar un xmin de 32 bits que, después
de wrap, coincida con el intervalo actual. Si la durabilidad queda ambigua se devuelve UNAVAILABLE;
no se inventa un commit ni se abre otra conexión. Una prueba adicional para eliminar esa ambigüedad
exigiría otro alcance. Esta decisión conserva las migraciones congeladas y la conexión única.
Base técnica: [subtransacciones PostgreSQL 16](https://www.postgresql.org/docs/16/subxacts.html),
[estado de transacciones](https://www.postgresql.org/docs/16/functions-info.html#FUNCTIONS-PG-SNAPSHOT)
y [congelación de xmin](https://www.postgresql.org/docs/16/routine-vacuuming.html#VACUUM-FOR-WRAPAROUND).


Cierre 15G2: gate final fresco aprobado el 2026-09-06T23:29:59-03:00. Son 260 pruebas
(199 unitarias, 61 PostgreSQL), incluidas 104 nuevas y 156 regresiones; sin fallos, errores u omitidas.
Ambos ledgers rechazan resultados de subtransacciones aún sin commit y los recuperan después del
commit exterior; 70 hijos liberados y límites de aritmética XID están cubiertos. La corrupción
concordante de afirmación en fuente/snapshot se contrasta con SHA-256 real sin hidratar el texto.
TTL se redondea hacia arriba a microsegundos para conservar su mínimo contractual.

Los siete archivos Java permanecieron idénticos durante el gate. Ambos JAR fueron auditados:
15 clases nuevas exactas, entrypoints correctos, sin tests, duplicados ni propiedades secretas;
V27/V28/V29 intactas. Se cierra 15G; sigue 15H. El registro/escritor completo, credenciales y política
de sesión permanecen en sus cortes previstos, sin activar endpoints ni flags de escritura.


## Implementación 15H — captura y metadata protegida

Se divide 15H en H1 (captura/cifrado puros) y H2 (writer y PostgreSQL), con listas nominales en el
plan. LegalRequestMetadata es un valor core compartido: valida IP literal y Unicode sin acoplar
persistencia a Servlet. El resolver HTTP acredita la cadena de proxies; el valor por sí solo no
acredita procedencia o autorización. UA vacía/ausente se omite; se conserva el resto exactamente
hasta 512 code points, sin controles C0/C1 ni surrogates inválidos. No hay DNS ni truncamiento.

La política personal exige Duration positiva explícita, sin valor legal por defecto; se usa
precisión de microsegundos redondeada hacia arriba. El keyring AES-GCM es propio y no se conecta a
configuración, HMAC, JWT o cifrado de equipos. La futura composición de configuración acreditará
separación de secretos entre subsistemas antes de habilitar consumidores. Tests usan claves y
retenciones sintéticas. No se genera ni configura un secreto para un entorno real en este corte.

El codec prepara IP obligatoria y UA opcional con AAD por lote/campo/versión. Su resultado es opaco,
inmutable y ligado al codec, para impedir que el writer acepte bytes arbitrarios o de otro dueño.
El writer participa en la reserva/transacción existente, exige lote propio no vacío y actor válido,
y guarda sólo cabecera y campos cifrados. Usa el aceptado_en impuesto por PostgreSQL como origen de
capturado_en/retener_hasta. La unicidad global de nonce, incluida metadata tombstone, se decide en
V27 y cualquier colisión revierte el flujo completo. No completa el resultado idempotente ni abre
otra transacción: esas responsabilidades siguen en 15I/15L. No se crea endpoint de metadata.


Detalle de captura 15H1: IPv4 requiere cuatro octetos decimales sin ceros iniciales; IPv6 se
normaliza a RFC 5952 y las direcciones mapped a IPv4. No se aceptan DNS, zonas, puertos, corchetes,
whitespace o formas abreviadas de IPv4. La política de red admite hasta 64 CIDR explícitos, con
host bits en cero; mapped requiere prefijo >=96 que se convierte a IPv4. No se infiere si una IP
es pública/privada ni se añaden listas de redes confiables por defecto.

Si el peer no es confiable se ignora por completo X-Forwarded-For. Si lo es, se exige una única
cabecera no vacía, hasta 4096 caracteres y 32 saltos; todos sus literales se validan y la búsqueda
desde la derecha se detiene en el primer no confiable. Si todos son confiables, se usa el extremo
izquierdo. UA repetida se rechaza. La integración futura debe preservar remoteAddr como peer real
del conector y no aplicar antes una reescritura de headers no acreditada. Este corte no cambia
el rate limiter existente ni configura proxies de un entorno real.


Cierre 15H1: 259 pruebas aprobadas el 2026-09-07T06:59:01-03:00, incluidas 219 nuevas y
40 regresiones de keyring. Sin fallos, errores u omitidas; ocho archivos Java estables durante
el gate. Se acredita captura/cifrado puro, no persistencia ni unicidad global de nonce. 15H2
conserva esos requisitos pendientes y probará los tombstones y el rollback en PostgreSQL.

Auditoría H1: ocho clases nuevas exactas en ambos JAR, migraciones congeladas intactas, entrypoints
correctos y ausencia de tests/duplicados/propiedades secretas. Hashes del empaquetado en el plan.


Inicio 15H2: baseline `91fc5c8`, backend limpio; lista nominal confirmada antes de integrar el writer
y sus tres archivos de pruebas. Se reutilizan los fixtures y roles restringidos de 15A/15G2 intactos.


Cierre 15H2: 370 pruebas aprobadas el 2026-09-07T07:09:58-03:00 (289 unitarias y 81 PostgreSQL
16.14), en diez XML frescos sin fallos, errores u omitidas. Incluye siete unitarias y 20 casos
PostgreSQL nuevos, con 343 regresiones. Los doce Java H1/H2 se mantuvieron estables durante el gate.

El writer exige reserva MISS propia, actor estabilizado y lote del top XID actual con actos,
perfil/revisión coherentes y sin metadata previa. No completa el resultado idempotente ni abre
otra transacción. La retención parte de aceptado_en impuesto por PostgreSQL, se redondea hacia
arriba a microsegundos y se rechaza antes del INSERT si ya venció al observar el servidor.
El único commit exterior confirma metadata y resultado; antes del commit la metadata es invisible
a otra conexión. La prueba usa roles de aceptación y registro con las ACL nominales sin ampliarlas.

Se acredita el contenido cifrado con JCE independiente, UA de 512 code points/2048 bytes y replay
sin DML. La colisión del segundo nonce, activo o retenido después de tombstone, provoca SQLSTATE
23505 tras insertar cabecera y primer campo, y revierte el grafo completo preservando toda evidencia
anterior. La transición de purga del fixture efímero se ejecuta con las guardas reales de V27.
Actor, preparado, lote, reserva, presupuesto o retención inválidos fallan cerrado. Un error del
caller después de guardar metadata también revierte todo. No hay retry de nonce ni recuperación
SQL después del fallo; se conserva la causa internamente en la excepción tipada existente.

Auditoría H2 y revisiones independientes aprobadas: 940 clases por JAR, incluidas diez de metadata
exactas, migraciones V27/V28/V29 congeladas y entrypoints correctos; sin tests, dependencias de test,
duplicados ni propiedades secretas. Comando focal, desglose y hashes constan en el plan.
Se cierra 15H con dos commits atómicos, sin push. Siguen pendientes en 15I/15L la composición del
servicio completo, REQUIRES_NEW exterior, verificador de privilegios y configuración operativa;
este corte no habilita endpoints de escritura ni configura secretos/retenciones reales.


## Implementación 15I — aceptación autenticada atómica

Baseline `9100522`, backend limpio. Se subdivide en I1 (selección y evidencia exacta por IDs),
I2 (frontera/configuración y permisos propios) e I3 (servicio/writer y commit), con listas nominales
registradas en el plan antes de editar. El usuario autorizó continuar el diseño ya ratificado.
El lookup histórico específico evita depender del filtro de líneas vigentes para dedup total.
La frontera propia permite reservar/replay antes del gate editorial sin modificar el gate global;
reutiliza las clases de presupuesto existentes con pool/instancias independientes y observa
COMMITTED/ROLLED_BACK/UNKNOWN mediante el estado transaccional común. No se conectan todavía
endpoints de escritura ni se configuran credenciales, claves o retenciones de un entorno real.

15I1 cerrado el 2026-09-07 con 304 pruebas focales frescas (200 unitarias + 104 PostgreSQL), sin
fallos/errores/omitidas en el gate final. La primera corrida detectó seis SELECT faltantes del
fixture, corregidos exclusivamente en el helper nuevo; cada caso negativo parte de una lectura
válida. El reader comprueba fuentes y evidencia histórica fuera del catálogo vigente sin DML ni
metadata, con límites y verificación agregada en batches. Preserva SCOPE_V1 genuino y la causalidad
editorial cuando una transacción anterior publica después de aceptar. Selección pura y errores
contractuales tipados no crean una ruta HTTP. Auditoría de ambos JAR, fuentes y V27/V28/V29 aprobada;
evidencia y hashes en el plan. Commit atómico I1, sin push; frontend preservado.

I2 inicia sobre `94b4d65`, árbol limpio tras I1. Configuración explícita no escaneable, pool propio y
rol restringido sin credenciales heredadas; flags exactos, retención explícita y keyrings separados.
El marker ACCEPTANCE sólo identifica esta frontera para impedir su mezcla con otros contextos.

15I2 cerrado con 307 pruebas frescas (205 unitarias + 102 PostgreSQL), sin fallos/errores/omitidas
en su repetición final. El primer intento detectó assertions incorrectas del ciclo register/refresh
y tipo de error Spring, corregidas en el test nominal sin relajar producción. Frontera propia
REQUIRES_NEW/READ_COMMITTED, preflight V29/privilegios, orden replay antes de editorial, suspensión
y restauración del llamador y commit independiente acreditados. Auditoría de diez fuentes, 21
clases nuevas/del marker y ambos JAR aprobada; hashes V27/V28/V29 intactos. El servicio final se
compone en I3, que además ejecutará clean verify por el marker común. Commit atómico sin push.

I3 inicia sobre `584e82f`, árbol limpio. El servicio conserva actor servidor, prioridad replay/MISS,
validación semántica y selección; writer canónico, metadata y ledger comparten la misma transacción.
Los fallos físicos de commit/cleanup se acreditan con conexiones PostgreSQL instrumentadas en
fixtures; no hay reintento ni reconciliación automática dentro del servicio.

15I3 y 15I cerrados el 2026-09-07: 161 pruebas focales y clean verify fresco de **6959 pruebas**
(5999 Surefire + 960 PostgreSQL), sin fallos/errores/omitidas/flakes. El servicio y writer integran
actor servidor, REQUIRES_NEW/READ_COMMITTED, reserva/replay, disponibilidad, evidencia histórica,
selección, snapshots canónicos, metadata y ledger. Toda evidencia nueva comparte un único commit;
los errores conocidos revierten también el agregado nuevo. COMMITTED y UNKNOWN se conservan
honestamente ante fallos físicos de commit/cleanup, sin retry ni reconciliación automática.

El replay no realiza DML. DEDUP/EMPTY no crean ni refechan lotes, actos, documentos aceptados o
metadata: persisten resultado técnico/referencias y pueden materializar el agregado actual si aún
no existe. Se acreditan mezcla, herencia, stale/duplicados, corrupción, cambios de rol/token/estado
de usuario o taller durante espera y 34 actos en dos batches. Las correcciones de las primeras
corridas afectaron sólo fixtures y se registraron antes del gate final; fuentes productivas
congeladas durante la corrida integral. Inventarios completos de compilación/tests y ambos JAR
auditados, V27/V28/V29 intactas. AspectJ runtime se distingue de agentes de prueba en la auditoría.

Cierre mediante tres commits atómicos, sin push: I1 `94b4d65`, I2 `584e82f` e I3 con el mensaje
`feat(legal): registra aceptaciones atomicas`. Frontend y archivos no versionados preservados.
Todavía no hay endpoint de aceptación ni activación real; sigue 15J y continúan pendientes 15K–Q.
El cierre no acredita lanzamiento público, registro atómico ni contenido legal definitivo.
