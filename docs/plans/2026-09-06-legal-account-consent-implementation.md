# Bloque 15 — Plan por cortes de requisitos y aceptaciones de cuenta

Fecha: 2026-09-06

Estado: 15A y 15B cerrados el 2026-09-06; diseño y ejecución autorizados por el titular.
Los demás cortes no están iniciados. Sigue 15F antes de 15C.
[Diseño y decisiones ratificadas](2026-09-06-legal-account-consent-design.md).

## Alcance y reglas

Implementar el punto uno: requisitos autenticados, satisfacción exacta/heredada, historial propio,
aceptación e idempotencia, registro atómico y enforcement preparado pero apagado. No incluye frontend
runtime, contenido definitivo, invitaciones/email, PRO/Mercado Pago, fotos, exportación, baja/cierre,
staging, grants compartidos ni activación de producción. Esas dependencias siguen abiertas.

- Baseline backend `a25c7aa`, rama `codex/lanzamiento-publico-backend`, árbol limpio.
- Frontend `7545201`, rama `codex/frontend-refactor-checkpoint`, sin cambios; conservar sus dos
  rutas no versionadas `.agents/` y `public/OrdenFix project naming/`.
- V27/V28 congeladas, con hashes en el diseño. V29 se prevé por la incompatibilidad de idempotencia
  sin actos nuevos, no para reescribir historia o relajar V28.
- Un commit atómico por corte, sin push. Sólo stage de rutas nominales revisadas.
- Antes de cada corte, comparar rama/HEAD/status, leer instrucciones locales y confirmar su lista
  exacta de archivos. Nombres nuevos de este plan son propuestos, no archivos ya existentes.
- Si un corte combina responsabilidades separables, dividirlo documentalmente antes de editar.
  Un cambio de alcance/contrato o archivo extra exige motivo explícito en el plan; no da permiso
  para modificar todo un paquete ni archivos ajenos.
- Cada corte termina compilable y con sus pruebas focales aprobadas, decisión documentada y commit.
  Un bloque interno puede quedar sin ruta HTTP. No exponer una escritura parcialmente implementada.
- La planificación inicial fue sólo documental; 15A agrega las pruebas focales nominales indicadas
  abajo. La evidencia de 14E es baseline y no sustituye pruebas de este bloque.

## Secuencia y dependencias

| Corte | Resultado | Depende de |
| --- | --- | --- |
| 15A | Contrato preciso y viabilidad de SQL, permisos y transacciones | Diseño revisado |
| 15B | Satisfacción exacta y herencia puras | A |
| 15C | Lector privado PostgreSQL y actor servidor | A, B, F |
| 15D | GET de requisitos del usuario | C |
| 15E | Historial propio paginado | C |
| 15F | V29 y compatibilidad estricta del esquema | A; regresión 12–14 y 15A |
| 15G | Comando canónico, HMAC y coordinación idempotente | A, F |
| 15H | IP confiable, metadata cifrada y retención declarada | A, F |
| 15I | Servicio interno de aceptación atómica | B, C, F, G, H |
| 15J | POST de aceptaciones y errores contractuales | D, E, I |
| 15K | Política compartida de emisión de sesión | A |
| 15L | Escritor interno de registro atómico | F, G, H, I, K |
| 15M | Registro HTTP compatible, replay y efectos poscommit | J, K, L |
| 15N | Bloqueo legal configurable y excepciones exactas | D, E, J, M |
| 15O | Mantenimiento de resultados vencidos y metadata | F, G, H, I |
| 15P | Concurrencia, capacidad y fallos del flujo completo | A–O |
| 15Q | Gate integral fresco y cierre documental | P |

Orden ratificado en 15A: A → B → F → C → D → E → G → H → I → J → K → L → M → N → O → P → Q.
F se adelanta porque C necesita la protección de PK para adquirir locks de actor con credencial
restringida; no se concede UPDATE(id) temporalmente sin esa guarda. Se conservan las etiquetas.
K y algunos componentes puros pueden prepararse de forma independiente,
pero nunca se comparten working trees con ediciones solapadas ni se mezclan commits de cortes.
No paralelizar ejecuciones Maven que compartan target. Los gates transversales se indican abajo.

Prefijos de las listas nominales, relativos al backend:

```text
core = src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/
db   = src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/
http = src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/http/
sec  = src/main/java/com/leonardorozza/mvgrreparacionesbackend/config/security/
svc  = src/main/java/com/leonardorozza/mvgrreparacionesbackend/service/impl/
dto  = src/main/java/com/leonardorozza/mvgrreparacionesbackend/service/dto/
```

Los tests van en los paquetes equivalentes de src/test/java. Plan y diseño pueden actualizarse en
cada corte sólo para registrar decisiones/evidencia. Los helpers adicionales deben nombrarse antes
de editarlos; no modificar fixtures de 12–14 por comodidad.

## 15A — Contrato preciso y prueba de viabilidad

Baseline de ejecución: `b51cb5a`, backend limpio. Whitelist nominal confirmada antes de editar:
FRONTEND_INTEGRATION.md, este plan, el diseño y la nueva decisión V29; tests nuevos
`LegalAcceptanceProtocolFeasibilityIT.java`, `LegalAcceptanceProtocolFeasibilityITSupport.java`
y `LegalAcceptanceAdvisorOrderFeasibilityTest.java`, todos bajo
`src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/`.
El helper propio evita modificar fixtures históricos. El test AOP adicional acredita el punto del advisor frente a @PreAuthorize con Spring
real; es una prueba de viabilidad, no la implementación del gate 15N. Los triggers de protección
usados para caracterizar permisos existen sólo dentro de la base efímera, con nombres de fixture:
no se alteran los archivos ni las funciones congeladas V27/V28.

Resultado: decisiones implementables sobre pendientes, POST vacío, mezcla/dedup, transacción,
V29, locks/permisos y configuración. Todavía sin endpoints ni servicios productivos nuevos.

1. Ratificar la política mínima: REGISTRATION/REGISTRO/ADMIN_TITULAR y
   AUTHENTICATED_PENDING/USO_CONTINUADO según rol persistido. No incorporar contextos por ausencia
   de evidencia ni interpretar históricos como aceptados.
2. Precisar en FRONTEND_INTEGRATION las cuatro situaciones de POST del diseño, conservando wire,
   token y prioridad contractual. El vacío no se beneficia de un allMatch que saltee revisión.
3. Prueba PostgreSQL real: confirmar que el guard actual rechaza ledger nuevo sobre lote histórico,
   lote vacío y grant insuficiente para FOR SHARE de users; conservar controles negativos owner.
4. Elegir y documentar forma exacta V29: resultado sin actos, referencias, compatibilidad con ledger
   histórico, unicidad entre generaciones, expiración, keyring y replay. No posponer esta elección
   al código del endpoint. Incluir DDL/constraints/privilegios previstos y plan de upgrade.
5. Cerrar locks reales, alcance de permisos de users/lote/agregado y aislamiento actor/estado. La
   solución no puede dar capacidad de modificar usuarios al rol lector/aceptador sin protección.
   Probar INSERT lote→todos los guards con el rol final: un helper de lock aislado no elimina
   el permiso exigido por el FOR SHARE que el guard SECURITY INVOKER vuelve a ejecutar.
6. Fijar límites de lectura, bytes, batches, presupuesto total/SQL/socket/pool por consumidor,
   matrices de flags y configuración de keyrings/retención. 5 s de espera idempotente y >=24 h de
   replay son contractuales. Retención real requiere configuración aprobada, sin plazo inventado.
7. Acreditar la propuesta de alta JDBC frente a columnas/secuencias/defaults actuales y separar
   sesión/email después del commit. Registrar el alcance transversal de auth. Fijar el punto
   de enforcement después del @PreAuthorize efectivo, no sólo después de AuthorizationFilter: un
   USER en ruta ADMIN obtiene 403 sin consulta legal aunque tenga pendientes o legal esté caído.

Archivos nominales: FRONTEND_INTEGRATION.md; nuevo
`docs/plans/2026-09-06-legal-account-consent-v29-decision.md`;
`LegalAcceptanceProtocolFeasibilityIT.java` y su fixture nominal propio si hace falta.
Las pruebas caracterizan V28 congelada; no reescriben SQL ni configuran una base compartida.

Gate: focal PostgreSQL 16 y revisión de la matriz de decisiones. Si aparece una incompatibilidad
con v1, documentarla para revisión antes de cambiar el contrato; no reducir replay o atomicidad.
Commit: `docs(legal): precisa protocolo de aceptacion de cuenta`.

## 15B — Núcleo puro de pendientes y herencia

Baseline de ejecución: `5054826`, backend limpio, rama confirmada. Frontend `7545201` preservado
con sus dos rutas no versionadas. Whitelist de 15B, confirmada antes de integrar código:
cuatro clases core y dos tests nombrados abajo, este plan y el diseño compañero. Son ocho archivos;
no se modifican tipos/validadores públicos, migraciones, configuración, HTTP ni frontend.

Implementación acordada: actor servidor inmutable; snapshot completo de scopes con sus ordinales,
contenido y revisión antes de filtrar; catálogo de líneas/versiones y evidencia propia sin metadata
personal; evaluador exacto y después herencia por cada base íntegra. El resultado conserva scopes,
revisión y orden, distingue EXACT/INHERITED/PENDING y sólo señala bloqueo por obligatorios pendientes.
Las clases no son prueba de autenticación ni de completitud SQL. Se validan todos los inputs antes
de usar un éxito, y una inconsistencia no se convierte en pendiente ni en satisfacción.

Precisión confirmada contra V27: lineage_ordinal es positivo y estrictamente ordenado, puede tener
saltos válidos y no se interpreta como versión humana. El reader 15C acreditará todos los miembros
del intervalo; una lista/booleano no demuestra que no omitió una versión publicada. Se consideran
PUBLICADA/VIGENTE/REEMPLAZADA/RETIRADA en (base,objetivo], también intermedios nunca activados;
BORRADOR y ordinales posteriores no anticipan bloqueo. Las líneas documentales no tienen filtro de
contexto. La herencia evalúa bases individuales y nunca une documentos de actos distintos.
Keys siguen el schema editorial congelado (regex canónica, máximo 100), no el máximo físico SQL120.
Capacidad: 4096 filas por intervalo observado y 65536 filas de versiones/enlaces/evidencia;
exceso falla cerrado. El reader conserva además sus budgets SQL/bytes y prueba conteos completos.


Resultado: decisión inmutable de satisfacción/pendientes y obligatoriedad a partir de snapshots
acreditados. Sin JDBC, Spring, HTTP, flags ni nueva evidencia.

- Aplicar identidad de actor/tenant y keys; prueba exacta antes de herencia.
- OR de flags de todas las versiones publicadas de cada intervalo, incluso true→false intermedio;
  key/documento nuevo bloquea herencia; borrador/futuro no bloquea anticipadamente.
- Preservar ordinales y token de scopes completos; todos satisfechos mantiene revisión y lista vacía.
- Distinguir opcionales de obligatorios para decisión 428; evitar «cualquier pendiente bloquea».

Nuevos: `core/LegalActorSnapshot.java`, `LegalAuthenticatedRequirements.java`,
`LegalRequirementSatisfactionEvaluator.java`, `LegalRequirementLineage.java`.
Tests: `LegalRequirementSatisfactionEvaluatorTest`, `LegalAuthenticatedRequirementsTest`.
Reutilizar LegalApplicableScopeResolver/Policy y calculadores; no ampliar su política por heurísticas.

Gate: vectores puros de ambos roles, cambio de rol histórico, múltiples evidencias base, keys/ordinales
inválidos, cadena intermedia y cero DML por construcción. Commit:
`feat(legal): calcula pendientes y herencia de cuenta`.

## 15C — Lector privado y frontera de actor

Resultado: servicio interno que hidrata composición completa, evidencia y linajes bajo credencial
propia; entrega pendientes sólo al terminar la operación acreditada. Se ejecuta después de 15F,
con sus guardas de PK y preflight V29 estricto; aplica locks y presupuestos de la decisión 15A.

- Contexto independiente y preflight exacto; actor principal contrastado con user/taller/rol/estado.
- Store/replay V28 y reader en la misma conexión/gate compartido READ_COMMITTED. No invocar las
  fachadas públicas ni reducir primero por evidencia para calcular el agregado.
- Consultas por actor/tenant y keys/intervalos, batches/sentinelas y recursos acotados; definir
  observación coherente si otra sesión acepta mientras se lee. No combinar un contador y datos
  de dos snapshots como si fueran uno sin estrategia acreditada.
- Cualquier falta de scope, corrupción, exceso o drift falla cerrado; un vacío válido exige haber
  acreditado la composición completa y su evidencia. El lector no puede escribir actos ni metadata.

Nuevos: `db/LegalPrivateRequirementsDatabaseConfiguration.java`,
`LegalPrivateRequirementsPrivilegeVerifier.java`, `LegalPrivateRequirementsReader.java`,
`LegalPrivateRequirementsReadService.java`, `LegalActorSnapshotReader.java` y wrapper de recursos
nominal según 15A. Ampliaciones existentes, sólo si se requieren: LegalDatabaseConsumerContext,
LegalManifestDatabaseGate y su marcador; conservar fronteras/allowlists de 12–14.
Tests: `LegalPrivateRequirementsReadServiceIT`, `LegalPrivateRequirementsDatabaseContextIT`,
`LegalPrivateRequirementsPrivilegeVerifierIT`, `LegalPrivateRequirementsReaderTest`.

Gate focal: ambos roles, actor/tenant ajeno, rol/active/tokenVersion discordantes, cadena extensa,
metadata fuera de alcance, REUSED estable sin DML de agregado, rollback y cierre; regresión pública
cuando se toque un componente compartido. Commit: `feat(legal): consulta pendientes privados`.

## 15D — GET autenticado de requisitos

Resultado: GET exacto /api/requisitos-legales con contrato privado, sin parámetros de selección de
actor/perfil/contextos; respuesta completa acreditada, incluso requisitos vacíos legítimos.

Nuevos: `http/LegalPrivateRequirementsController.java`, `LegalPrivateRequirementsResponses.java`,
`LegalPrivateRequirementsHttpConfiguration.java` y advice/errores nominales del perfil privado.
Tests: controller, bridge y `LegalPrivateRequirementsHttpIT`.

Gate focal: 200/401/403/503, sesión/tenant, payload sin datos internos, private/no-store, If-None-Match
no produce 304, vecinos/métodos, flag apagado, sin dependencia del flag público. Sin nueva excepción
permitAll ni cambio de políticas públicas. Commit: `feat(legal): publica requisitos del usuario`.

## 15E — Historial de evidencia propia

Resultado: GET /api/aceptaciones-legales paginado y filtrable por contexto. Obtiene actos y documentos
reales, sin convertir herencia en evidencia ni exponer metadata técnica.

Nuevos: `db/LegalAcceptanceHistoryReader.java`, `LegalAcceptanceHistoryService.java`,
`http/LegalAcceptanceHistoryController.java`, `LegalAcceptanceHistoryResponses.java`.
Ampliar nominalmente la configuración privada si el rol ya permite exactamente esas lecturas.
Tests: `LegalAcceptanceHistoryReaderIT`, `LegalAcceptanceHistoryHttpIT`, controller puro.

Gate focal: ADMIN no ve empleados; USER no ve titular/otro tenant; vacío propio válido, page/size,
orden fecha/UUID, límites y batching sin N+1. Historia SCOPE_V1 creada antes de migrar y AGGREGATE_V1
real; texto/fechas originales tras REPLACE/RETIRE, sin IP/UA/HMAC. Count y página coherentes bajo
aceptaciones concurrentes según estrategia de 15A. Commit: `feat(legal): consulta aceptaciones propias`.

## 15F — V29 y compatibilidad de consumidores

Resultado: persistencia tipada de éxitos idempotentes sin actos nuevos, y solución nominal de
permisos/locks aprobada en la [decisión 15A](2026-09-06-legal-account-consent-v29-decision.md).
Se ejecuta después de B y antes de C. Es un corte de esquema, no un POST parcial.

- Implementar la decisión SQL de 15A; preservar V27/V28 e historia, unicidad permanente de evidencia
  y duración mínima de replay. No fabricar lotes, actos ni timestamps históricos.
- Inventario/guards/roles/verifier nuevos: ledger suplementario y referencias inmutables,
  admission cruzada con locks previos, protección global BEFORE UPDATE OF id en users/talleres
  y grants nominales de bloqueo protegidos. Sin SECURITY DEFINER. Probar search_path, ownership,
  permisos transitivos y el grafo completo de aceptación/registro hasta COMMIT. La autorización
  principal→servicio→consulta no se atribuye a recibir un userId por el rol JDBC compartido.
- Compatibilidad explícita latest V29 para web/CLI y consumidores 12–15; migración desconocida,
  faltante, checksum/topología/función alterados siguen rechazados. Fixtures históricas target V27/V28
  permanecen históricas; las suites latest se actualizan de forma nominal y justificada.

Nuevo SQL propuesto: `src/main/resources/db/migration/V29__resultados_aceptacion_idempotente.sql`.
Nuevos: `db/LegalV29AcceptanceInventory.java`, `LegalV29AcceptanceSchemaVerifier.java`.
Existentes a inventariar exactamente antes de editar: verificación Flyway/V28 y PostgresMigrationIT,
consumidores/gates cuya acreditación dependa de que V28 sea latest. Tests nuevos:
`LegalV29AcceptancePersistenceIT`, `LegalV29AcceptanceUpgradeIT`,
`LegalV29AcceptanceSchemaVerifierIT`, `LegalV29AcceptancePrivilegeVerifierIT`.

Gate transversal: clean verify con instalaciones limpias y upgrades con evidencia real, XML y
ambos JAR. Si el delta de compatibilidad y el nuevo esquema dejan de ser un cambio atómico revisable,
dividir F en subcortes documentados antes de editar, cada uno con baseline compatible.
Commit: `feat(legal): persiste resultados idempotentes sin actos nuevos`.

## 15G — Comando canónico y protocolo idempotente

Resultado: entrada de negocio validada, fingerprint/keyring protegido y coordinación/replay durable
para registro y aceptación. Todavía no expone HTTP de escritura.

- UUID v4 canónico, límites de DTO y claves, canonicalización estable conservando valores exactos.
  No ocultar duplicados al ordenar; password sólo dentro del HMAC del DTO normalizado.
- Scope por método+plantilla+actor, o público de registro; lookup y locks en todas las claves
  retenidas con espera total <=5 s. Relectura después del lock y colisión distinta nunca como éxito.
- V29 sin actos, mixtos, resultados viejos y filas expiradas de ambos almacenes según decisión A/F.
  No reclamar éxito si no hay identidad durable acreditada; no almacenar IN_PROGRESS exitoso.
- Configuración sin defaults secretos, rotación coordinada y errores sanitizados.

Nuevos: `core/LegalAcceptanceCommand.java`, `LegalAcceptanceCommandValidator.java`,
`LegalIdempotencyFingerprint.java`; `db/LegalIdempotencyCoordinator.java`,
`LegalIdempotencyResultStore.java`, `LegalIdempotencyKeyring.java`.
Tests puros correspondientes y `LegalIdempotencyCoordinatorIT`.

Gate focal: permutaciones equivalentes, password distinto, multi-réplica/keyring, timeout único,
replay posterior a REPLACE/RETIRE, distinto fingerprint, expirados no purgados, rollback de reserva.
Commit: `feat(legal): coordina idempotencia de aceptaciones`.

## 15H — Metadata protegida

Resultado: captura confiable y cifrado de IP/UA listo para confirmar en el lote, con retención
explícita y claves independientes. No endpoint de lectura de metadata.

Nuevos: `http/LegalRequestMetadataResolver.java`, `db/LegalAcceptanceMetadataCodec.java`,
`LegalAcceptanceMetadataPolicy.java`, `LegalAcceptanceMetadataWriter.java`.
Tests puros y `LegalAcceptanceMetadataIT`.

Gate focal: proxies no confiables, cadena válida/hostil, IP ausente, UA Unicode/512, AAD lote/tipo,
keyrings independientes, nonce duplicado, manipulación de tag, retención inválida y rollback completo.
Ningún secreto en DTO/error/log. No reutilizar el helper que confía en primer X-Forwarded-For.
Commit: `feat(legal): protege metadata de aceptaciones`.

## 15I — Aceptación autenticada interna y atómica

Resultado: un servicio que coordina actor, replay, disponibilidad, dedup, revisión, pendientes,
validación semántica, lote, actos, documentos, metadata y resultado dentro de un único commit.

Nuevos: `db/LegalAcceptanceDatabaseConfiguration.java`, `LegalAcceptancePrivilegeVerifier.java`,
`LegalAcceptanceService.java`, `LegalAcceptanceWriter.java` y receipt tipado.
Tests: `LegalAcceptanceServiceTest`, `LegalAcceptanceServiceIT`,
`LegalAcceptanceDatabaseIsolationIT`, `LegalAcceptanceCommitIT`.

Gate focal: todos/ningún/mixtos actos nuevos, obligatorios/optativos, snapshot cambiado, digest,
pertenencia/rol, rechazo de datos ajenos, fallos por fila y constraints diferidas. Exact dedup no crea
actos/metadata nuevos, pero guarda la nueva clave. Duplicados de requisitos/documentos con evidencia
previa no habilitan el atajo 204; probar
revisión actual (400) y desactualizada (409). Cambio de actor/estado durante la operación no
confirma un acto inválido. COMMITTED/ROLLED_BACK/UNKNOWN honestos; sin retry automático que oculte
resultado incierto. Commit: `feat(legal): registra aceptaciones atomicas`.

## 15J — POST autenticado y errores

Resultado: POST /api/aceptaciones-legales →204, con prioridad de validación, replay y errores exactos;
no acepta identidad ni autoridad del navegador.

Nuevos: `http/LegalAcceptanceController.java`, `LegalAcceptanceRequests.java`,
`LegalAcceptanceHttpConfiguration.java`, advice/errores nominales de escritura.
Tests: controller/bridge y `LegalAcceptanceHttpIT`.

Gate focal: matriz de header/JSON/DTO, 400/409/503, 204 sin cuerpo, no-store, Retry-After del 409 en
progreso, no leaks de contraseña/IP/UA/HMAC, replay antes de freshness y lista vacía con sus reglas.
CORS conserva contrato actual; si se cambia filtro común, ampliar gate por impacto.
Commit: `feat(legal): publica aceptaciones del usuario`.

## 15K — Política compartida de sesión

Resultado: login y futura sesión de replay verifican de forma coherente contraseña y estado actual
de usuario/taller. El JWT conserva schema y claims actuales; no añade audiencia como rol wire.

Nuevo: `svc/AccountSessionPolicy.java` y/o emisor nominal único definido en A.
Existentes: AuthService.java; UserDetailsServiceImpl.java, AuthenticatedUserPrincipal.java,
JwtUtils.java o UserRepository.java sólo si se justifica su cambio nominal. No retocar claims por
comodidad. Tests: AccountSessionPolicyTest, AuthTests, JwtSecurityIntegrationTests,
TenantIsolationTests y los de JWT afectados.

Gate transversal: clean verify; usuario/taller inactivo, password/tokenVersion cambiados,
principal y tenant coherentes. Esta condición de taller no está acreditada hoy en AuthService.
Commit: `fix(auth): unifica condiciones de emision de sesion`.

## 15L — Registro interno sobre una transacción JDBC

Resultado: writer dedicado que confirma taller+suscripción FREE/TRIAL+ADMIN+agregado+actos+metadata+
idempotencia juntos; devuelve identidad durable, todavía sin sustituir el controller histórico.

Nuevos: `db/LegalRegistrationDatabaseConfiguration.java`, `LegalRegistrationPrivilegeVerifier.java`,
`LegalRegistrationService.java`, `LegalRegistrationWriter.java`, receipt tipado.
Tests: `LegalRegistrationServiceIT`, `LegalRegistrationPrivilegeVerifierIT`,
`LegalRegistrationCommitIT` y paridad nominal con RegistroService.

Gate focal: columnas/defaults/Clock/trialDias, ADMIN único, constraints, secuencias, usuario/tenant
exactos y fallo después de cada INSERT. Dos claves mismo email: una cuenta sin huérfanos. Una clave
mismo payload: IDs idénticos; password/payload distinto: conflicto. Ningún JWT/email antes de commit
ni lectura JPA de filas no confirmadas. Commit: `feat(legal): crea cuenta y evidencia en una transaccion`.

## 15M — Integración de registro compatible y replay

Resultado: AuthController integra alta legal; mantiene legacy sólo cuando no llegó ningún elemento
legal y enforcement está apagado. Bloque completo nunca se ignora; parcial nunca crea cuenta.

Existentes: `controller/AuthController.java`, `dto/RegisterRequestDto.java`,
`svc/RegistroService.java`, `svc/CuentaService.java` para mover exclusivamente el efecto poscommit.
Nuevo adaptador de alta/emisión nominal según K/L. Tests: `LegalRegistrationHttpIT`,
`LegalRegistrationReplayIT`, AuthTests, CuentaTests y los fixtures de registro afectados.

Gate transversal: clean verify. Matriz ausente/parcial/completo × flags, prioridad exacta 400/428/503,
201 original, replay tras cambio editorial/password/email/estado, identidad por IDs durables,
token nuevo/emailVerificado actual,
corte después del commit y fallo de emisión de sesión. Email sólo poscommit para alta nueva,
sin envío por rollback o replay. Persistir/inutilizar auth_tokens en una transacción nueva explícita
y enviar sólo tras su commit; no reutilizar el EntityManager ya confirmado de afterCommit. Probar
que el token existe y verificarEmail puede consumirlo; no se amplía aquí el transporte de email.
Commit: `feat(legal): integra consentimiento en el registro`.

## 15N — Enforcement compatible, apagado

Resultado: decisión y adaptador del gate 428 con excepciones nominales. Sólo obligatorios pendientes
bloquean; 503 distingue publicación indisponible de falta de aceptación. Sin autoaceptaciones.

Nuevos: `sec/LegalAcceptanceEnforcementPolicy.java`, `LegalAcceptanceExemptRequestMatcher.java`,
`sec/LegalAcceptanceEnforcementAdvisor.java` como adaptador por método propuesto, con orden
acreditado respecto de @PreAuthorize en A. Existentes: configuración de seguridad de método
nominalmente identificada antes de editar; no sustituirla por un filtro HTTP que corra antes de
la autorización ADMIN. Tests de política/matcher y `LegalAcceptanceEnforcementIT`.

Gate transversal: clean verify y matriz método+ruta/rol/estado. Resolver aceptación, cancelar
suscripción y exportación legacy conservan acceso según permisos propios; no ampliar permitAll ni
crear rutas de baja ficticias. USER + pendientes en ruta ADMIN devuelve 403 sin lectura legal,
también con legal indisponible. 401/403 siguen siendo tales; auth/email/webhook/seguimiento/preflight
conservan comportamiento. Las nuevas vías de derechos de fases futuras deberán agregarse por ruta
exacta cuando existan. No activar mientras falten salidas aplicables y frontend compatible.
Commit: `feat(legal): prepara bloqueo compatible por aceptacion`.

## 15O — Retención y mantenimiento internos

Resultado: servicio y adaptador programable interno, apagado por defecto, para purgar únicamente
metadata/resultados vencidos. Credencial separada del lector/escritores; sin endpoint HTTP.

Nuevos: `db/LegalAcceptanceRetentionService.java`, `LegalAcceptanceMaintenanceConfiguration.java`,
`LegalAcceptanceMaintenancePrivilegeVerifier.java`, scheduler nominal condicionado por su flag.
Nuevo runbook: `docs/runbooks/legal-account-consent-postgresql.md`.
Tests: `LegalAcceptanceRetentionIT`, `LegalAcceptanceMaintenanceIsolationIT`.

Gate focal: no borrar antes del vencimiento, tombstones/nonce persistentes, purga atómica por lote,
resultados activos protegidos, colisión con replay y cambio de keyring, batches finitos, retry tras
fallo y observabilidad sin datos sensibles. Rol request no adquiere DELETE ni acceso de mantenimiento.
Runbook incluye rotación en réplicas, recuperación de UNKNOWN, configuración/retención y alertas;
no se configura un cron real ni un entorno compartido. Commit: `feat(legal): mantiene retencion de evidencia tecnica`.

## 15P — Concurrencia, causalidad, capacidad y deadlines

Resultado: evidencia de extremo a extremo para las superficies nuevas, con roles reales y HTTP.

Nuevos: `LegalAccountConsentConcurrencyIT`, `LegalAccountConsentCapacityIT`,
`LegalAccountConsentDeadlineIT`, helpers nominales propios. No cambios productivos previstos;
un defecto productivo se separa en corte de corrección antes del cierre.

- Dos lectores compartidos y escritor editorial; aceptación simultánea y respuesta coherente.
- Misma clave, distintas claves, mismo actor, distintos tenants, altas del mismo email; replay con
  publicación posterior y procedencia distinta aunque el token coincida.
- Keyrings y réplicas, expiración/purga, nonce, listas totalmente repetidas, vacías y mixtas.
- Más de 128 versiones históricas; linajes largos, miembros en último batch y límites estructurales
  frente a combinaciones editoriales realmente válidas. Sin N+1 ni historia ajena en memoria.
- Pool/locks/SQL/lectura/hash/commit/cierre, sin respuestas parciales; evidencia durable y resultado
  transaccional observado separadamente. Cooperación del hash no se presenta como preempción.
- Cero evidencia nueva por herencia/replay/dedup y metadata sólo para actos realmente nuevos.

Gate focal completo de estas clases; registrar SQL, filas, bytes y tiempos observados con sus
límites, sin afirmar SLA o heap. Commit: `test(legal): acredita concurrencia y capacidad de consentimiento`.

## 15Q — Gate integral y cierre del punto uno

Resultado: ejecución fresca completa, documentos actualizados y matriz exacta de capacidades
implementadas/apagadas/pendientes. No atribuir a este cierre la disponibilidad de staging ni la UI.

Documentos nominales: nuevo `docs/plans/2026-09-06-legal-account-consent-closure.md`, plan, diseño,
README.md, FRONTEND_INTEGRATION.md y runbook de O. La sincronización del plan frontend requiere su
propio corte documental en ese repositorio si se decide hacerla; no se mezcla en este commit backend.

Gate: clean verify Java 21 + PostgreSQL 16, XML completos sin omisiones silenciosas, JAR web/CLI,
Start-Class, inventarios/checksums, V27/V28 intactas, V29 acreditada y sin secretos/agentes de tests.
Revisar diff/stage nominal, backend limpio tras commit y frontend preservado. Registrar fallos y
resolución, no sólo último BUILD SUCCESS. Commit: `docs(legal): cierra aceptaciones y requisitos de cuenta`.

## Política de pruebas y comandos

Focalizados por corte; elegir nombres reales después de enumerar con rg --files. Los nombres de tests
anteriores son propuestos y deben existir antes de pasarlos a Maven. No contar un selector vacío
como evidencia ni reutilizar XML de una corrida anterior. Preparar package antes de Failsafe directo
cuando las pruebas necesiten JAR; skipTests no cuenta como verificación.

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dtest=<clases_reales_del_corte> test
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -DskipTests package
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dit.test=<clases_IT_reales_del_corte> failsafe:integration-test failsafe:verify
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw clean verify
git diff --check
git diff --cached --name-only
git diff --cached --check
git status --short
```

Las líneas con placeholders son plantillas documentales, no comandos listos para ejecutar.
Gates integrales previstos en F (migración/compatibilidad), K (sesión), M (registro), N (filtro) y Q
(cierre fresco). Fuera de ellos, ampliar por un cambio transversal o fallo que lo justifique.
No repetir suites aprobadas sin cambios o una incertidumbre concreta que resolver.

## Planificación inicial — cerrada en b51cb5a

Whitelist: sólo este plan y el diseño compañero. Se consultaron contrato, SQL V27/V28, actor,
registro/sesión, inventarios y planes de cierre. Tres revisiones independientes contrastaron
contrato, persistencia y auth. La revisión final precisó dedup sin duplicados, permisos de toda la
cadena de guards, límite de identidad del rol compartido, autorización @PreAuthorize previa al 428,
transacción nueva del token de email y replay por identidad durable aunque cambie el email.
No se ejecutó Maven ni se modificó producción/configuración/frontend.
Commit: `b51cb5a docs(legal): planifica aceptaciones y requisitos de cuenta`, local y sin push.
La fecha de estos archivos identifica la planificación; cada corte registra su ejecución real.

## Ejecución 15A — 2026-09-06

Decisiones en la [ADR 15A](2026-09-06-legal-account-consent-v29-decision.md): contrato de pendientes,
casos vacío/dedup/mixto, V29 suplementaria, locks/grants, expiración/rotación, paridad de alta,
precedencia de autorización, flags y presupuestos. F se adelanta antes de C por los locks de actor.

Cierre: 15 pruebas focales aprobadas, 8 Surefire + 7 Failsafe, dos XML con cero fallos/errores/omitidas.
Java 21/PostgreSQL 16.14; package final 17:42:07 -03:00 y Failsafe final 17:43:24 -03:00. Registro
SQL con rollback en diez etapas, guards de PK y aceptación completos, IDENTITY sin permisos de
secuencia y orden AOP [200,401]. La ADR detalla comandos, corrección de la aserción inicial AOP,
repetición PostgreSQL al reducir grants y límites de lo acreditado. No se ejecutó clean verify.
Ambos JAR excluyen estos tests y mantienen V27/V28 idénticas. No se modificó runtime/config/frontend.

Commit atómico: `docs(legal): precisa protocolo de aceptacion de cuenta`, local y sin push.
Al cerrar 15A siguió 15B, núcleo puro de satisfacción y herencia, registrado a continuación.
V29 se implementará en 15F antes de 15C.


## Ejecución 15B — 2026-09-06

Corte cerrado: cuatro tipos core nuevos y dos suites propias, con plan/diseño actualizados. No se
modifican validadores públicos, política mínima, cálculos congelados, migraciones ni frontend.
El snapshot valida la composición y los textos completos antes de calcular revisiones; el evaluador
contrasta toda la evidencia propia con metadatos canónicos antes de decidir. La evidencia exacta
prevalece; la herencia considera cada base íntegra, todos los flags publicados del intervalo y
cada key documental actual. Opcionales pendientes se conservan y no señalan bloqueo.

La revisión independiente contrastó semántica contractual, SQL de linajes y código puro. Se preservan
huecos válidos de ordinal, flags de versiones intermedias PUBLICADA/REEMPLAZADA/RETIRADA, bases
individuales sin unión ficticia, rol histórico, orden de manifiesto y token completo. Referencias,
digests, identidad o extremos incompatibles se rechazan, aun detrás de una primera evidencia exacta.
No se afirma que una lista pruebe completitud de PostgreSQL ni SHA de textos históricos ausentes.

Comandos focales ejecutados secuencialmente con Java 21.0.10 y Maven 3.9.11:

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw -Dtest=LegalAuthenticatedRequirementsTest,LegalApplicableScopeResolverTest,LegalRequiredSetRevisionCalculatorTest,LegalRequiredSetAggregateRevisionCalculatorTest package
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw -Dtest=LegalRequirementSatisfactionEvaluatorTest test
```

| Suite | Casos | Fallos / errores / omitidos |
| --- | ---: | --- |
| LegalAuthenticatedRequirementsTest | 46 | 0 / 0 / 0 |
| LegalRequirementSatisfactionEvaluatorTest | 71 | 0 / 0 / 0 |
| LegalApplicableScopeResolverTest | 7 | 0 / 0 / 0 |
| LegalRequiredSetRevisionCalculatorTest | 8 | 0 / 0 / 0 |
| LegalRequiredSetAggregateRevisionCalculatorTest | 9 | 0 / 0 / 0 |

Total focal: 141 (117 nuevos + 24 regresiones), cinco XML inspeccionados sin sumar reportes antiguos.
package terminó 18:21:33 -03:00 en 18.848 s; evaluator terminó 18:22:23 -03:00 en 12.633 s. Ambas
corridas aprobaron sin fallos iniciales. Fronteras verificadas: 8 scopes, 256 requisitos por scope,
keys de 100, 4096/4097 filas en intervalos de requisito/documento y presupuesto conjunto de 65536
contando versiones, referencias y evidencia. Son vectores puros; no mediciones de servicio o SLA.

Ambos JAR contienen las cuatro clases core idénticas a target/classes, excluyen sus tests y conservan
V27/V28 byte a byte con sus SHA-256 congelados. Sin dependencias de Spring/JDBC/HTTP ni DML en las
clases nuevas. No se ejecutó PostgreSQL ni clean verify: no hay cambio de persistencia/transversal;
la integración, transacciones, intervalos SQL completos y sus presupuestos se acreditarán en C/F/P.

Commit atómico: `feat(legal): calcula pendientes y herencia de cuenta`, local y sin push.
Siguiente: 15F, V29 y compatibilidad estricta, respetando la reordenación aprobada en 15A. Antes de
editar F se inventariarán sus archivos y el alcance del gate transversal; este commit no lo inicia.
