# Bloque 15 — Requisitos, aceptaciones y registro de cuenta

Fecha: 2026-09-06

Estado: propuesta de diseño y plan por cortes solicitada por el titular. Sólo documentación;
implementación no iniciada. Las precisiones nuevas de este documento se revisan antes de 15A y
no sustituyen silenciosamente el contrato congelado.

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

Se recomienda el primer enfoque. No se crea un motor genérico de workflows ni una transacción
distribuida. Se conservan las fachadas públicas y sus roles; los nuevos servicios componen las
primitivas internas en su propia frontera, sin llamar a una fachada que haga otro commit.

## Decisiones propuestas para cerrar las ambigüedades

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
siempre todos los obligatorios vigentes de REGISTRO. La precisión se documentará en el contrato en
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

### 3. Vacío, duplicados y resultados sin nuevos actos

| Caso | Resolución propuesta |
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

### 4. Extensión V29 prevista

La inspección del SQL confirma una incompatibilidad específica: legal_validar_lote_aceptacion()
rechaza lotes vacíos; legal_idempotencia_insert_guard() exige que el lote referenciado tenga xmin
de la transacción actual. Una nueva clave no puede apuntar a un lote histórico. V28 no cambia
esa guarda. Por tanto, el ledger actual no puede representar los éxitos sin actos de la tabla anterior.

Se prevé V29 para agregar un resultado de operación tipado que pueda acreditar actos existentes
o un no-op validado. Debe conservar actor/tenant, revisión/fingerprint protegidos, hora autoritativa,
expiración y referencias verificables, sin fabricar un lote ni evidencia. Los éxitos con nuevos
actos conservan el vínculo atómico con su lote. La forma SQL exacta, índices y estrategia de
coexistencia con el ledger V27 se cierran en 15A mediante prueba PostgreSQL y ADR; no se improvisan
al llegar al POST. No se añade un segundo ledger sin resolver unicidad, lookup, replay, rotación,
expiración y carreras entre ambos. Si se propone un ledger unificado para nuevas operaciones,
debe leer y preservar la historia V27 y acreditar todos los vínculos de negocio, no sólo guardar 204.

V29 preservará bytes, filas, fechas y relaciones históricas; no debilitará guards para poder
crear lotes vacíos. Los inventarios actuales exigen V28 como última migración: su compatibilidad
con V29 debe ser explícita, con preflight versionado y regresiones 12–14/CLI. Nunca aceptar cualquier
versión >=28 ni sustituir fingerprints por un chequeo superficial de existencia.

También se cerrará el permiso de bloqueo sobre users: FOR SHARE requiere permiso UPDATE sobre una
columna y users no tiene la guarda inmutable de los punteros legales. No se declarará un rol de
«sólo lectura de actor» mientras pueda modificar usuarios. Cualquier helper privilegiado o protección
aditiva elegida debe tener alcance nominal, búsqueda segura, EXECUTE restringido y pruebas adversas.
Esta decisión es requisito previo de los escritores, no autorización para dar UPDATE general.
Un helper que sólo bloquee users no resuelve el segundo FOR SHARE ejecutado por el guard
SECURITY INVOKER del lote. La ADR debe acreditar el grafo completo INSERT lote→guards con el rol
final: proteger el grant nominal o encapsular la escritura completa con privilegios nominales,
sin debilitar los guards. Probar únicamente el helper sería evidencia insuficiente.

## Arquitectura y flujo de datos

### Fronteras de base y actor

Tres contextos consumidores independientes: lectura privada, aceptación autenticada y registro.
Cada uno con datasource, pool, credenciales, presupuesto y verifier propios, sin parent ni fallback
al datasource web, editorial, documental o público. El lector puede materializar agregados y sólo
leer evidencia; el escritor autenticado no puede crear talleres/usuarios/suscripciones; registro
puede insertar exclusivamente las columnas de negocio y usar las secuencias necesarias. Un cuarto
rol de mantenimiento sólo puede purgar lo vencido según guardas. Ninguno recibe ownership/DDL.
Un rol JDBC compartido no conoce al principal HTTP por recibir un userId. El aislamiento se
acredita en principal→servicio→consulta y en las FKs de pertenencia; no se atribuye al helper SQL
una autorización por actor que no posee. Si 15A elige imponer identidad dentro de PostgreSQL,
deberá especificar un vínculo no falsificable por ese rol, no una variable de sesión modificable.

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

El orden candidato de locks es idempotencia → gate editorial shared → actor → punteros/actos
ordenados. 15A debe verificar compatibilidad con los locks que ya toma store/guards y fijar un
único orden ejecutable. Dos claves distintas del mismo actor serializan o resuelven ON CONFLICT
con relectura segura; no se captura una violación y continúa una transacción PostgreSQL abortada.
Una colisión ajena, fingerprint diferente o incoherencia nunca se convierte en replay exitoso.

La purga maneja expiración explícitamente: una fila vencida todavía puede ocupar UNIQUE. El rol de
request no gana DELETE para resolverlo. La estrategia de generación/reutilización de claves
vencidas y el worker deben quedar coherentes con keyring/locks antes de cerrar el store idempotente;
ningún conflicto SQL se interpreta automáticamente como éxito.

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
conservando validación de header/shape y sin crear cuenta. Los nombres exactos y matriz de flags
quedan fijados en 15A; no se editan properties de entornos reales.

Enforcement registro apagado: legacy sólo si faltan los tres elementos (header, revisión y lista).
Bloque parcial → 400; completo → validación y persistencia atómicas. Enforcement encendido y ausencia
total → 428 con requisitos públicos actuales, salvo indisponibilidad que sigue siendo 503.

Gate autenticado: sólo obligatorios pendientes pueden producir 428. Ausencia/corrupción editorial
produce 503; opcionales no bloquean. Respuestas y errores privados no se cachean. Es un control
transversal sobre rutas de negocio después de autenticación y autorización suficientes para no
filtrar contratos a actores rechazados. El punto propuesto es un adaptador por método con orden
posterior a la autorización efectiva y anterior al negocio; 15A acreditará ese orden. En el runtime
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

El cierre técnico puede acreditar el filtro apagado y su matriz en tests. La activación general
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
valores concretos se congelan con evidencia en 15A; las pruebas distinguen duración del GET/POST,
liberación observada del servidor y resultado COMMITTED/ROLLED_BACK/UNKNOWN. No inferir SLA o heap.

Pruebas puras por semántica; PostgreSQL 16 real con roles restringidos por persistencia; HTTP por
actor, caché, error y rollout; concurrencia por clave/actor/editorial/estado de cuenta. clean verify
fresco al cierre y en los cambios transversales de migración, sesión/registro y filtro. La
inspección de XML, ambos JAR y migraciones forma parte del gate; tests viejos no certifican código nuevo.

## Estado de aprobación y siguiente paso

Este corte sólo propone diseño y secuencia. No se ejecutó Maven ni se crearon clases, flags,
migraciones o endpoints. Se revisaron contrato, guards SQL, roles, actor/registro y ciclo de vida
con tres revisiones independientes. La skill brainstorming se aplicó; writing-plans referida por
ella no está instalada tras buscar en skills/plugins, por lo que se utiliza el formato existente
del repositorio: resultado por corte, rutas nominales, pruebas, dependencias y commit.

El primer corte de ejecución es 15A: resolver y documentar las precisiones, acreditar las dos
brechas de persistencia/permisos y fijar el diseño SQL/locks/flags antes de cualquier escritor.
No se inicia automáticamente dentro de esta planificación.
