# Bloque 15 — Plan por cortes de requisitos y aceptaciones de cuenta

Fecha: 2026-09-06

Estado: 15A, 15B, 15F, 15C, 15D y 15E cerrados el 2026-09-06; diseño y ejecución autorizados por el titular.
15G1 y 15G2 cerrados el 2026-09-06; 15G completo. 15H1 cerrado el 2026-09-07.
15H2 cerrado con 370 pruebas el 2026-09-07; 15H completo. 15I cerrado en I1/I2/I3,
con clean verify fresco de 6959 pruebas. 15J1 cerrado con 346 pruebas focales; 15J2 cerrado con
628 pruebas focales (550 unitarias y 78 PostgreSQL). Sigue 15J3: publicación del POST y gate HTTP.
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
| 15G1 | Comando canónico y HMAC/keyring — cerrado | A, F |
| 15G2 | Coordinación SQL y replay durable — cerrado | G1 |
| 15H1 | Captura, política explícita y cifrado puro — cerrado | A, F |
| 15H2 | Persistencia de metadata en la transacción del escritor — cerrado | H1, G2 |
| 15I | Servicio interno de aceptación atómica — cerrado en I1/I2/I3 | B, C, F, G, H |
| 15J | POST de aceptaciones y errores contractuales; J1/J2 cerrados, J3 pendiente | D, E, I |
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

Baseline de ejecución: `c2fa300`, backend limpio, rama confirmada. Frontend `7545201`
preservado con sus dos rutas no versionadas. Lista nominal confirmada antes de editar producción:

- Nueve clases nuevas: `LegalPrivateRequirementsDatabaseConfiguration`,
  `LegalPrivateRequirementsPrivilegeVerifier`, `LegalPrivateRequirementsReader`,
  `LegalPrivateRequirementsReadService`, `LegalActorSnapshotReader`, `LegalActorSnapshotException`,
  `LegalPrivateRequirementsDataSource`, `LegalPrivateRequirementsDeadline` y
  `LegalPrivateRequirementsReadException`, todas en `db`.
- Ampliaciones aditivas de `LegalManifestDatabaseGate` y `LegalDatabaseBoundaryMarker`: nueva
  frontera privada exacta, sin cambiar la ejecución ni las allowlists de consumidores anteriores.
- Nueve archivos propios de pruebas: `LegalPrivateRequirementsReadServiceIT`,
  `LegalPrivateRequirementsDatabaseContextIT`, `LegalPrivateRequirementsPrivilegeVerifierIT`,
  `LegalPrivateRequirementsReaderTest`, `LegalActorSnapshotReaderTest`,
  `LegalPrivateRequirementsITSupport`, `LegalPrivateRequirementsDatabaseConfigurationTest`,
  `LegalPrivateRequirementsDataSourceTest` y `LegalPrivateRequirementsDeadlineTest`.
- Este plan y el diseño compañero; no se modifica SQL, auth, HTTP, frontend ni configuración runtime.

El wrapper/deadline específico conserva el protocolo probado del consumidor público, con tipos
privados propios para evitar acoplar credenciales y ampliar el alcance de una refactorización común.
La configuración es explícita y no escaneable; `account-read.enabled` ausente/false no crea recursos,
true exige sus tres propiedades y cualquier otro literal falla. No se importa todavía desde HTTP.
El servicio recibe `AuthenticatedUserPrincipal` construido en servidor; no ofrece selección pública
por ID, audiencia o tenant. Una discordancia de actor conserva error interno distinguible de
indisponibilidad para el adaptador futuro. La autorización HTTP sigue fuera de este corte.
Observación coherente: gate editorial shared → advisory shared de actor → taller/user FOR SHARE →
store/replay V28 → composición completa y evidencia/linajes → evaluador15B → commit/cierre/deadline.
Los escritores futuros deben respetar el advisory de actor; no se atribuye ese protocolo a un INSERT
SQL arbitrario. Los límites, sentinelas, filtros y digests se acreditan antes de entregar pendientes.
Se añade un techo defensivo de 128 MiB de fuentes históricas UTF-8 por observación, sumando una vez
cada versión distinta de afirmación/documento que hidrata el catálogo histórico; se acredita el
presupuesto por cabeceras antes de leer sus bytes. Conserva además 65536 filas de versiones/enlaces/
evidencia, 4096 versiones por intervalo (base,objetivo] y batches/fetch 32. Las transiciones son
como máximo tres por versión y se consultan en batches nominales con sentinela.

El instante de observación se refresca en PostgreSQL después de estabilizar actor y filas, porque
una aceptación puede confirmar mientras el lector espera ese advisory. El mismo boundary alimenta
store y reader. Las fechas de lote/fuentes no pueden superar esa observación; la fecha histórica
V27 de aceptación es transaction_timestamp(), por lo que no se exige que sea posterior a publicación
ni se convierte en prueba de orden físico de COMMIT. Se acredita la cadena de transiciones de cada
versión y la pertenencia del acto al conjunto/agregado histórico de su propio lote; no se reconstruye
el agregado actual para validar historia. La revisión agregó esta pertenencia durante implementación.


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

Baseline de ejecución: `cf50844`, backend limpio en la rama prevista; frontend `7545201`
preservado con sus dos rutas no versionadas. Lista nominal confirmada antes de editar código:

- Seis clases nuevas: `http/LegalPrivateRequirementsController.java`,
  `LegalPrivateRequirementsResponses.java`, `LegalPrivateRequirementsHttpConfiguration.java`,
  `LegalPrivateRequirementsHttpException.java`, `LegalPrivateRequirementsExceptionHandler.java`
  y `sec/LegalPrivateRequirementsAuthenticationEntryPoint.java`.
- Una integración acotada en `config/SecurityConfig.java`: entry point opcional que devuelve 401
  exclusivamente para GET exacto `/api/requisitos-legales` con el flag privado encendido. Conserva
  el entry point 403 existente para cualquier otra solicitud; no agrega `permitAll`, bypass JWT,
  reglas de autorización ni cambios de rate limit. Es necesaria porque la configuración actual
  responde 403 a toda solicitud anónima y este contrato distingue ausencia de sesión (401).
- Tests nuevos: `http/LegalPrivateRequirementsControllerTest.java`,
  `LegalPrivateRequirementsHttpConfigurationTest.java`,
  `sec/LegalPrivateRequirementsAuthenticationEntryPointTest.java`,
  `db/LegalPrivateRequirementsHttpIT.java` y su helper nominal
  `LegalPrivateRequirementsHttpITSupport.java` si lo exige el fixture PostgreSQL propio.
- Este plan, el diseño compañero y `FRONTEND_INTEGRATION.md`; este último se amplía nominalmente
  para reflejar la ruta disponible y sustituir el estado previo del lector ya cerrado en 15C.

Decisiones del adaptador: sólo el principal tipado del servidor selecciona actor; ADMIN/USER se
verifican con method security antes del lector. No hay query params admitidos (400 genérico sin
código legal nuevo); HEAD no consulta ni materializa (405, Allow: GET). La ruta no ofrece ETag ni
304: siempre vuelve a acreditar pendientes y devuelve `private, no-store`. Errores propios tienen
`no-store`; snapshot de actor inválido produce 401 y contrato indisponible 503 con `contexto: null`,
`locale: es-AR`, sin causa interna. La bandera privada acepta únicamente `true`/`false` literales,
por defecto false; se comprueba además la URI cruda exacta (incluido contextPath), porque Spring
decodifica segmentos antes de resolver el mapping. Una equivalencia codificada responde 404 antes
de materializar. Controller y entry point comparten esa clasificación exacta. El puente crea un
contexto sin padre con sólo sus tres credenciales y esa bandera.
El GET puede materializar un agregado faltante mediante el lector 15C; no escribe aceptaciones.

Caracterización durante el focal: los métodos sin mapping (POST/PUT/PATCH/DELETE) y vecinos no
mapeados llegan al `GlobalExceptionHandler` existente y producen 500 con sesión (403 sin sesión),
sin invocar el lector. No se amplía este corte para modificar el advice global; HEAD y URI codificada
que sí alcanzan este controller mantienen sus 405/404 propios. El flag apagado tampoco tiene mapping.

Gate focal con regresiones de las dos superficies públicas y seguridad compartida por la conexión
aditiva del entry point. No cambia la política general de autenticación/autorización; si la regresión
revela un fallo transversal se amplía a clean verify conforme a la política acordada.

Resultado: GET exacto /api/requisitos-legales con contrato privado, sin parámetros de selección de
actor/perfil/contextos; respuesta completa acreditada, incluso requisitos vacíos legítimos.

Nuevos: `http/LegalPrivateRequirementsController.java`, `LegalPrivateRequirementsResponses.java`,
`LegalPrivateRequirementsHttpConfiguration.java` y advice/errores nominales del perfil privado.
Tests: controller, bridge y `LegalPrivateRequirementsHttpIT`.

Gate focal: 200/401/403/503, sesión/tenant, payload sin datos internos, private/no-store, If-None-Match
no produce 304, vecinos/métodos, flag apagado, sin dependencia del flag público. Sin nueva excepción
permitAll ni cambio de políticas públicas. Commit: `feat(legal): publica requisitos del usuario`.

## 15E — Historial de evidencia propia

Baseline de ejecución: `688b020`, backend limpio y rama confirmada; frontend `7545201`
preservado con sus dos rutas no versionadas. Lista nominal confirmada antes de editar producción:

- Ocho clases nuevas: `db/LegalAcceptanceHistoryReader.java`, `LegalAcceptanceHistoryService.java`,
  `LegalAcceptanceHistoryPage.java`, `LegalAcceptanceHistoryReadException.java`;
  `http/LegalAcceptanceHistoryController.java`, `LegalAcceptanceHistoryResponses.java`,
  `LegalAcceptanceHistoryHttpException.java` y `LegalAcceptanceHistoryExceptionHandler.java`.
- Ampliaciones acotadas: `db/LegalPrivateRequirementsDatabaseConfiguration.java` registra lector y
  servicio históricos sobre la misma frontera; `http/LegalPrivateRequirementsHttpConfiguration.java`
  expone ambas fachadas y conserva un único contexto/pool; `sec/LegalPrivateRequirementsAuthenticationEntryPoint.java`
  admite además el GET exacto de historial en su clasificación privada, sin tocar SecurityConfig.
- Tests nuevos nominales: `db/LegalAcceptanceHistoryReaderIT.java`, `LegalAcceptanceHistoryITSupport.java`,
  `LegalAcceptanceHistoryPageTest.java`, `LegalAcceptanceHistoryServiceTest.java`,
  `LegalAcceptanceHistoryHttpIT.java`; `http/LegalAcceptanceHistoryControllerTest.java`.
- Tests existentes que se amplían para las dos fachadas/rutas: `http/LegalPrivateRequirementsHttpConfigurationTest.java`
  y `sec/LegalPrivateRequirementsAuthenticationEntryPointTest.java`. Se agrega nominalmente
  `db/LegalPrivateRequirementsHttpIT.java`: su caso vecino usaba la ruta de aceptaciones que ahora
  existe; se reemplaza por su subruta no implementada para mantener la regresión de vecinos.
- Este plan, el diseño compañero y FRONTEND_INTEGRATION.md. No se editan SQL, verificadores de
  privilegios, fuentes del reader de pendientes ni fixtures históricos de cortes previos.

La credencial 15C ya permite las tablas históricas necesarias; no se amplían grants ni consumidores.
Historia comparte preflight V29, gate editorial shared, advisory shared de actor, filas taller/user
FOR SHARE y una única REQUIRES_NEW/READ_COMMITTED, con observación refrescada después de locks.
No resuelve scopes actuales ni invoca store/materialización: una historia vacía es válida aun sin
catálogo vigente y la consulta no ejecuta DML. Count y página se estabilizan frente a escritores que
respeten el advisory de actor según 15A; no se atribuye ese protocolo a un INSERT SQL arbitrario.

Se pagina el snapshot propio de actos (no lotes), sin joins que omitan corrupción antes de contar;
orden aceptadoEn DESC, UUID DESC de PostgreSQL, filtro opcional por contexto original. Sólo se
hidratan fuentes y documentos de esa página, en batches 32 y con sentinelas antes de mapear exceso.
Límites: 100 actos, 16 documentos por acto, afirmación 1000 code points/4000 bytes, Markdown 1MiB
por fuente y 128MiB de fuentes históricas distintas por observación, acreditados antes de texto.
Count no carga historia fuera de página; sus enteros respetan el máximo exacto JavaScript. Se
validan snapshot, digest, pertenencia histórica SCOPE_V1/AGGREGATE_V1 y fechas contra la observación,
sin reconstruir un agregado vigente ni exigir aceptadoEn posterior a una publicación legacy.
REPLACE/RETIRE conservan la evidencia original; la herencia no crea filas históricas.

HTTP sólo admite contexto/page/size, sin selectores de actor: defaults null/0/20, decimales ASCII
sin signo, size 1–100 y page hasta Integer.MAX_VALUE, con offset long comprobado. Contexto inválido
usa CONTEXTO_LEGAL_NO_SOPORTADO; parámetros repetidos, desconocidos o paginación inválida usan 400
genérico. No hay ETag/304; éxito private,no-store y errores propios no-store. Head/URI codificada
mantienen los controles explícitos 405/404 de 15D y las rutas sin mapping conservan el advice global
previo. 401/403 ocurren por sesión/actor o rol; 503 conserva sólo contexto del filtro y locale es-AR.

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

Baseline de ejecución: `e0c7860`, backend limpio, rama confirmada; frontend `7545201`
preservado. La migración y su admisión estricta constituyen un único corte: no se habilita
V29 sin acreditar el delta en los consumidores existentes. Lista nominal confirmada antes
de editar código (prefijos anteriores; tests bajo sus paquetes equivalentes):

- SQL V29, `db/LegalV29AcceptanceInventory.java`, `db/LegalV29AcceptanceSchemaVerifier.java`.
- Compatibilidad en `db/LegalV27ImportSchemaVerifier.java`,
  `db/LegalEditorialSchemaVerifier.java`, `db/LegalV28AggregateSchemaVerifier.java`;
  métodos internos de superficie histórica evitan recursión entre verificadores.
- Los cuatro IT V29 enumerados abajo y fixture nuevo `LegalV29AcceptanceITSupport.java`.
- Tests históricos `LegalV28UpgradeIT.java`, `LegalV28AggregatePersistenceIT.java`,
  `LegalV28AggregatePreFreezeSmokeIT.java`: fijar target 28 explícito conforme a su propósito.
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/PostgresMigrationIT.java`
  (latest 29 separado del upgrade histórico 27→28) y `LegalPersistenceIT.java`
  (locks de tupla previos al DML del ledger en las transacciones de prueba).
- `LegalEditorialRetireIT.java`, `cli/LegalEditorialProcessFixture.java` y
  `cli/LegalEditorialProcessIT.java`: mismo protocolo previo y nuevas tablas en snapshots.
- Ampliación nominal tras el primer gate, antes de editar: `LegalEditorialCapacityIT.java`.
  La prueba histórica target 27 ahora observa dos consultas fijas adicionales por preflight
  (historia cerrada y ausencia de delta V29); se inventarían y acotan explícitamente, conservando
  sentinelas, cardinalidades del grafo, límites temporales y cero DML de lectura.
- Este plan, diseño compañero y decisión V29: registrar decisiones y evidencia de ejecución.

Se mantienen los tipos concretos de preflight, sus familias de errores y los inventarios
históricos. No se amplían allowlists de consumidores existentes ni se alteran sus gates/configs.
Los grants nominales nuevos se acreditan en PostgreSQL efímero; los verificadores específicos
por consumidor corresponden a C/I/L/O, sin introducir ahora un contexto productivo sin servicio.
El advisory de actor continúa siendo protocolo de servicio; SQL V29 exige el lock de tupla
previo y las guardas editoriales/relacionales, sin prometer autenticación HTTP por credencial JDBC.
La validación de agregado actual sólo corresponde a cabeceras nuevas; la purga histórica
comprueba estructura y referencias aunque el catálogo editorial haya cambiado.

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

### Implementación y acreditación 15F

V29 agrega dos tablas (24 columnas, 15 constraints, siete índices incluidos PK/UNIQUE), diez
funciones INVOKER y once triggers; no agrega secuencias ni backfill. Su SHA-256 es
`976a66c0a7f234e79c1ba84be4721ecb2407a1d6e076f2444630ccb9afc949e9`, checksum Flyway
`2141641921`. V27/V28 conservan exactamente sus archivos y huellas anteriores.
El catálogo V29 acredita doce relaciones, 119 columnas, 96 constraints, 43 índices,
38 triggers y cinco secuencias de esas relaciones, más 32 funciones y las superficies base V27.
Las huellas se obtuvieron del recurso final migrado en PostgreSQL 16.14 limpio; una captura
inicial diagnóstica falló deliberadamente contra constantes provisionales, que se reemplazaron
antes de acreditar consumidores. No se admite un inventario provisional en runtime.

El despacho conserva import/editorial históricos sobre 27/28 con su superficie original,
historia exacta y ausencia del delta V29. En 29 todos los preflight afectados acreditan la
superficie completa actual. Se rechazan migraciones desconocidas, filas requeridas faltantes,
orden/checksum/script/success alterados, delta parcial, drift de tablas/constraints/índices,
guardas deshabilitadas, funciones alteradas, overloads y search_path inseguro. La base alternativa
normaliza sólo su propio schema, sin cambiar los inventarios históricos.

La revisión incorporó dos cierres de subtransacciones: un evento INSERT de cabecera revalida
el agregado actual aunque xmin sea un subxid de SAVEPOINT; una referencia DEDUP comprueba también
el xmin de su lote, además del acto, para rechazar evidencia aún no confirmada. La purga histórica
no exige que la observación siga vigente. La exclusión entre ledgers se prueba en ambos sentidos
con espera observada en pg_locks, lectura nueva tras COMMIT del ganador y rollback del perdedor.
Esto acredita el protocolo SQL; el coordinador de claves/keyring y sus presupuestos sigue en 15G.

Los roles de prueba separan aceptación, registro y mantenimiento de resultados. No tienen
SELECT de password/ciphertext, UPDATE de negocio, secuencias USAGE, sesión advisory, LOB ni DDL.
UPDATE(id) sólo permite bloquear filas donde las guardas prohíben mutación; referencias prohíben
UPDATE también con cero filas. Mantenimiento no hereda INSERT. Son credenciales efímeras:
los verificadores por consumidor, autenticación, metadata worker y servicios finales siguen
correspondiendo a C/I/L/O. El alta JDBC de prueba usa datos sintéticos y acredita el COMMIT/rollback
del grafo, sin afirmar paridad de callbacks, sesión o email con el registro HTTP futuro.

Primera regresión focal: `./mvnw -Dit.test=LegalV29AcceptanceSchemaVerifierIT,LegalV28AggregateSchemaVerifierIT,LegalV27ImportSchemaVerifierIT,LegalEditorialSchemaVerifierIT,PostgresMigrationIT,LegalPersistenceIT failsafe:integration-test failsafe:verify`
(después de test-compile con Java 21). Terminó el 2026-09-06 18:58:25 -03:00, 1:01 min:
87 pruebas, cero fallos, errores u omitidas, incluyendo 28 V29 de esquema.
Segunda focal: `./mvnw -Dit.test=LegalV29AcceptancePersistenceIT,LegalV29AcceptanceUpgradeIT,LegalV29AcceptancePrivilegeVerifierIT failsafe:integration-test failsafe:verify`, después de test-compile.
Terminó el 2026-09-06 19:05:29 -03:00, 41.424 s: 29 pruebas nuevas (20 persistencia,
ocho privilegios, un upgrade), cero fallos, errores u omitidas. Total de ambas focales: 116,
57 nuevas V29. El primer clean verify detectó dos adaptaciones de pruebas pendientes: el fixture
CLI debe sembrar también padre/referencias V29, y el inventario de capacidad histórico debe medir
el despacho de versión añadido. Se corrigen sin reducir la protección ni omitir las pruebas.
El envejecimiento de resultados y la invalidación de punteros son fault injection
explícita en bases efímeras; las historias del upgrade se crean con triggers activos y calculadores,
sin backfill ni timestamps falsificados.

El primer clean verify terminó el 2026-09-06 19:23:04 -03:00, 17:00 min: 5681 pruebas,
con sólo los dos fallos de adaptación descritos, cero errores u omitidas. La corrección conserva
la exigencia de filas reales protegidas: el fixture CLI confirma evidencia y luego un DEDUP en
una segunda transacción REQUIRES_NEW/READ_COMMITTED. La capacidad target 27 inventaría las dos
consultas fijas, con límites de round trips 54/98/186 para readiness/plan/apply; se comprueba
una ejecución y una fila por cada consulta, sin ampliar límites semánticos ni de DML o tiempo.
Además, el historial del preflight queda acotado con LIMIT 4: máximo tres versiones admitidas y
una fila de rechazo. Un test con dieciséis versiones adicionales acredita esa cota y el fallo.

Corrección focal: `./mvnw -Dit.test=LegalEditorialProcessIT,LegalEditorialCapacityIT,LegalV29AcceptanceSchemaVerifierIT failsafe:integration-test failsafe:verify`, después de package con tests omitidos sólo
para refrescar ambos JAR. Terminó el 2026-09-06 19:26:52 -03:00, 2:16 min: 37 pruebas aprobadas
(siete CLI, una capacidad y 29 esquema), cero fallos, errores u omitidas. Son 58 pruebas nuevas
V29 en total. Fuente final fijada antes de repetir clean verify: 23 archivos nominales,
20 de código/pruebas/SQL y tres documentos.

Gate integral final: `./mvnw clean verify`, con JAVA_HOME Corretto 21.0.10. Terminó el
2026-09-06 19:43:47 -03:00, 16:10 min, BUILD SUCCESS. XML frescos: 5095 pruebas Surefire
(153 suites) y 587 Failsafe (62 suites), total 5682 en 215 XML, cero fallos, errores u omitidas.
Los veinte archivos de código/pruebas/SQL permanecieron idénticos durante esta ejecución.
La inspección independiente de ambos JAR comparó las 24 clases de los cinco verificadores/
inventarios afectados con target/classes y las tres migraciones con sus fuentes. Las cinco
clases de pruebas/fixture V29 no están empaquetadas y el gate de propiedades secretas aprobó.
SHA-256 de los artefactos de esta ejecución:

| Artefacto | SHA-256 |
| --- | --- |
| Aplicación | `20c1314451ad01f0aaa77bffd9253bcedf06f1ebe08afea2e255303b038dcc8e` |
| CLI legal | `70957d76a9cbee03b3954217e4a14e9b4cb1a3f4d45922773aeafaf2de5bc75f` |

Cierre: 15F aprobado; V27/V28 intactas, sin endpoints nuevos, configuración de servicios nuevos
ni grants compartidos. Frontend preservado en 7545201 con sus dos rutas no versionadas.
Un único commit del corte, sin push. Al cerrar 15F siguió 15C, cuya ejecución se registra aquí.

## 15G — Comando canónico y protocolo idempotente

### Subdivisión de ejecución: 15G1 y 15G2

Antes de editar producción, 15G se divide por la regla de responsabilidades separables: la
validación/canonicalización y criptografía no necesitan JDBC; los locks y resultados durables sí.
15G1 prepara exclusivamente el comando inmutable y las huellas de todas las claves retenidas.
15G2 implementará coordinador/store, espera acumulada de cinco segundos y replay de ambos ledgers,
con el gate PostgreSQL previsto abajo. Cerrar 15G1 no cierra 15G ni acredita replay o rotación en réplicas.

Baseline 15G1: `9c61dd7`, backend limpio en `codex/lanzamiento-publico-backend`; frontend `7545201`
preservado con `.agents/` y `public/OrdenFix project naming/` no versionados. Lista nominal previa:

- Nuevos `core/LegalAcceptanceCommand.java`, `core/LegalAcceptanceCommandValidator.java`,
  `core/LegalIdempotencyFingerprint.java` y `db/LegalIdempotencyKeyring.java`.
- Cuatro tests equivalentes: `LegalAcceptanceCommandTest`, `LegalAcceptanceCommandValidatorTest`,
  `LegalIdempotencyFingerprintTest` y `LegalIdempotencyKeyringTest`.
- Este plan y el diseño compañero. Sin cambios de DTO HTTP, parser editorial, SQL, flags, auth,
  credenciales de entorno, dependencias, frontend ni consumidores actuales.

El validador sólo acredita forma/límites del comando tipado; conserva duplicados, confirmado=false
u omisiones semánticas para no anteponerlos a revisión/replay. No acredita identidad servidor,
pertenencia, disponibilidad ni consentimiento. Ordena copias sin eliminar entradas; empates usan
el contenido completo para que permutaciones equivalentes conserven huella sin ocultar duplicados.
Registro conserva valores exactos y null opcional; no hace trim, case-fold ni normalización Unicode,
ni sustituye la validación HTTP actual. Los límites del parser de 8 MiB/profundidad/tokens y la
clasificación legacy/parcial se implementarán en la frontera HTTP correspondiente.

Las huellas HMAC-SHA-256 separan dominios versionados de scope, clave y fingerprint. La proyección
canónica incluye POST, plantilla fija, scope público de registro o userId servidor
como string decimal exacto, y todo el negocio (incluida contraseña sólo dentro del HMAC).
Se conserva el scope contractual por usuario; taller/rol/tokenVersion no cambian esa tupla. La
revalidación de actor y pertenencia al taller del resultado durable compete al escritor; un traslado
de taller no debe convertirse en un scope nuevo que permita consumir de nuevo la misma clave.
Las huellas y sus diagnósticos no exportan JSON canónico, secreto, clave cruda, contraseña ni
un hash auxiliar de contraseña.

Keyring inmutable explícito: 1–8 versiones positivas con claves Base64 canónicas distintas de
32 bytes, versión activa presente y TTL >=24 h sin overflow; valor técnico inicial 25 h al omitir
sólo TTL. Calcula candidatos de todas las versiones con snapshot estable; no lee Environment ni
se conecta automáticamente a Spring. Retirar claves requiere el protocolo coordinado 15A/15G2,
no una operación local sobre este objeto. Errores y toString no revelan entradas sensibles.

Gate 15G1: pruebas puras de forma, cardinalidad, Unicode, duplicados/permutaciones, valores exactos,
separación de actores/rutas/claves/dominios, cambio de contraseña, vectores HMAC independientes,
keyring/TTL inválidos y ausencia de secretos en diagnóstico. Commit 15G1:
`feat(legal): canonicaliza comandos de aceptacion`. El commit original de 15G queda para 15G2.


### Ejecución nominal 15G2

Baseline `3d133a4`, backend limpio en la rama autorizada; frontend `7545201` y sus rutas no
versionadas preservados. Antes de editar producción se confirma esta lista nominal:

- Nuevos `db/LegalIdempotencyCoordinator.java`, `db/LegalIdempotencyResultStore.java` y
  `db/LegalIdempotencyException.java`. Reserva ligada a conexión/transacción, errores tipados y
  recibos etiquetados por origen se encapsulan en estas clases.
- Nuevos tests `db/LegalIdempotencyCoordinatorTest.java`, `db/LegalIdempotencyResultStoreTest.java`,
  `db/LegalIdempotencyCoordinatorIT.java` y fixture `db/LegalIdempotencyCoordinatorITSupport.java`.
- Este plan y el diseño compañero. No se modifican V27/V28/V29, consumidores/ACL históricos,
  core 15G1, DTOs, auth, flags, HTTP ni frontend. Helpers previos sólo se reutilizan sin cambios.

Coordinador y store son componentes internos que participan en la transacción del caller. No
abren una REQUIRES_NEW anidada ni hacen commit/rollback propios. Exigen JDBC/DataSource ligados a
una transacción Spring activa, mutable READ_COMMITTED y conexión sin autocommit; la reserva queda
atada al recurso y xid de esa transacción y no puede reutilizarse en otra. Las futuras fronteras
15I/15L acreditarán REQUIRES_NEW exterior, presupuesto de operación, privilegios propios y commit.
15G2 usa preflight V29 exacto sobre su mismo JDBC antes de reservar. El fixture acredita los grants
restringidos V29 (más lectura nominal de historia Flyway para preflight), sin atribuirle el verifier
productivo de privilegios de 15I aún pendiente.

La reserva toma candidatos de todas las claves retenidas, deriva BIGINT físicos en PostgreSQL,
ordena unsigned y deduplica sólo locks. Cada espera usa el remanente de un único reloj monotónico
máximo de cinco segundos y del presupuesto exterior suministrado; no se reinicia por versión.
Después del último lock relee ambos ledgers por tupla completa, sin filtrar versión ni expiración.
Colisiones sólo serializan; más de un resultado lógico, versión incoherente o identidad ajena
fallan cerrado. Una huella distinta es conflicto tipado, jamás un resultado exitoso.

MISS no toma locks de actor ni editoriales: el futuro escritor continúa idempotencia→editorial→
actor→filas. REPLAY estabiliza actor y taller sin adquirir después el gate editorial; contrasta
identidad actual con el resultado original y valida estructura histórica, referencias y tiempos.
El recibo de registro devuelve IDs durables; contraseña actual y emisión de sesión siguen en
15K/15L/15M y no se reconstruyen por email. Ningún recibo previo al commit declara éxito HTTP.

El store inserta sólo el resultado técnico y referencias: WITH_ACTS sobre lote de la transacción
actual (aceptación/registro, incluida mezcla con evidencia previa), o EMPTY/DEDUP V29 sobre la
observación acreditada por el escritor. No crea lotes, actos, documentos ni metadata. Completed_at
se obtiene del servidor/lote conforme a cada guarda; expires_at se deriva de ese mismo valor,
con TTL mínimo 24 h. Las guardas diferidas siguen decidiendo la completitud al commit del caller.
Un fallo marca rollback-only y conserva la causa interna; no continúa una transacción abortada.

La primera ejecución focal (199 unitarias, 55 de integración) detectó un fallo nuevo y localizado:
el lock de una subtransacción desaparece al liberar SAVEPOINT aunque su padre siga sin commit.
Los otros 34 casos nuevos PostgreSQL y las 20 regresiones V29 aprobaron; esa ejecución no acredita
el cierre. Se reemplaza la comprobación de pg_locks por pg_xact_status sobre el xid completo
reconstruido desde el padre y la distancia unsigned de xmin. Se amplían ambos ledgers, hijos
liberados, fronteras aritméticas y digest real de afirmación dentro de los mismos archivos nominales.
No se corrige ni relaja ninguna migración o helper compartido.

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

### Cierre 15G2

Gate final fresco aprobado el 2026-09-06T23:29:59-03:00 con Java 21 y PostgreSQL 16.14.
Ejecución focal, sin Maven paralelo ni cambios en los siete archivos Java durante la prueba:

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw \
  -Dtest=LegalIdempotencyCoordinatorTest,LegalIdempotencyResultStoreTest,LegalAcceptanceCommandTest,LegalAcceptanceCommandValidatorTest,LegalIdempotencyFingerprintTest,LegalIdempotencyKeyringTest \
  -Dit.test=LegalIdempotencyCoordinatorIT,LegalV29AcceptancePersistenceIT \
  package failsafe:integration-test failsafe:verify \
  antrun:run@verify-no-secret-properties-in-jar
```

199 pruebas Surefire en seis XML y 61 Failsafe en dos XML frescos: **260 aprobadas**, cero fallos,
errores u omitidas. Son 104 nuevas (38 del coordinador, 25 del recibo/store y 41 PostgreSQL) y
156 regresiones (136 de 15G1 y 20 de persistencia V29). No se suman reportes viejos de target.

El gate acredita ambos ledgers bajo roles restringidos, comandos mixtos, EMPTY/DEDUP, rotación
retenida entre réplicas, commit/rollback del ganador y espera acumulada real entre dos claves.
Replay conserva IDs/fechas sin DML después de expiración sin purga, REPLACE o RETIRE; conflictos,
actor inválido, snapshots/digests incoherentes, referencias incompletas y multiplicidad fallan cerrado.
Registro recupera IDs originales aunque cambien email/password/tokenVersion, y rechaza cuenta o
taller deshabilitados. Esto no implementa comprobación de contraseña ni emisión de sesión futura.

La corrección de durabilidad se acreditó en ambos ledgers antes/después del commit exterior,
con 70 hijos liberados y con aritmética SQL de límites unsigned, epoch y valores mayores a Long.MAX_VALUE.
El SHA real de afirmación rechaza corrupción concordante del texto en snapshot y fuente. Las lecturas
siguen acotadas, sin traer Markdown, contraseña ni metadata; TTL se redondea hacia arriba a microsegundos.

Auditoría independiente: las 15 clases nuevas, incluidas internas, son idénticas a target/classes
en ambos JAR. Entradas web/CLI correctas; sin tests, duplicados ni propiedades secretas. V27/V28/V29
conservan sus hashes congelados en fuentes, target y artefactos. SHA-256 de este empaquetado:

| Artefacto | SHA-256 |
| --- | --- |
| Aplicación | `cc85949156720656dacbda6d0151d8b4bd1e79517ac4bfde8e1fa31a368b010e` |
| CLI legal | `cc82255907fcfbc24f898bbafb43ef1c9e01299c2867070c7e9a5653eee57ad5` |

Se cierra 15G2 y con él 15G. Son los nueve archivos nominales; sin cambios de consumidor,
configuración runtime, HTTP, SQL congelado o frontend. Un commit atómico:
`feat(legal): coordina idempotencia de aceptaciones`, sin push. Sigue **15H: metadata protegida**.
El servicio escritor completo y sus fronteras REQUIRES_NEW/privilegios siguen en 15I/15L.

## 15H — Metadata protegida

### Ejecución subdividida: 15H1 y 15H2

Baseline 15H: `5669248`, backend limpio en `codex/lanzamiento-publico-backend`; frontend `7545201`
preservado con sus dos rutas no versionadas. El 2026-09-07 se divide ejecución pura y JDBC antes
de editar, manteniendo un commit atómico por corte. Se completan ambos pasos para cerrar 15H.

Lista nominal 15H1:

- Nuevo `core/LegalRequestMetadata.java` y `core/LegalRequestMetadataTest.java`.
- Nuevo `http/LegalRequestMetadataResolver.java` y su test equivalente.
- Nuevos `db/LegalAcceptanceMetadataPolicy.java`, `db/LegalAcceptanceMetadataCodec.java` y sus tests.
- Este plan y el diseño compañero. El valor core adicional evita duplicar validación de literales
  IP/Unicode o hacer depender persistencia de HTTP; no acredita identidad ni confianza de proxies.

Lista nominal 15H2, después del commit 15H1:

- Nuevo `db/LegalAcceptanceMetadataWriter.java` y `db/LegalAcceptanceMetadataWriterTest.java`.
- Nuevos `db/LegalAcceptanceMetadataIT.java` y `db/LegalAcceptanceMetadataITSupport.java`.
- Este plan y el diseño compañero. Reutiliza fixtures 15A/15G2 sin modificarlos. Sólo se provisionan
  roles y datos de prueba en PostgreSQL efímero; no se amplían ACL ni credenciales compartidas.

Baseline 15H2: `91fc5c8` (`feat(legal): prepara captura y cifrado de metadata`), backend limpio
el 2026-09-07. Se confirma la lista nominal anterior de cuatro Java y dos documentos antes de
integrar JDBC. Frontend `7545201` y sus rutas no versionadas permanecen preservados.

La captura admite sólo direcciones literales IPv4/IPv6 sin DNS, resuelve X-Forwarded-For desde el
peer y de derecha a izquierda mediante CIDR explícitos; no confía en prefijos enviados por clientes.
UA ausente/vacía se omite; presente conserva valores exactos, hasta 512 code points Unicode válidos
(2048 bytes UTF-8), sin truncar ni normalizar y sin controles C0/C1. Diagnósticos redactados.
El keyring AES es explícito e independiente, sin fallback a HMAC/JWT/equipos ni configuración runtime.
La retención positiva es obligatoria, no tiene default y se redondea hacia arriba a microsegundos.
Los futuros escritores deberán validar su configuración completa antes del negocio.

El codec entrega un preparado inmutable ligado a su instancia y lote, con IP obligatoria y UA opcional,
AAD contractual y nonces aleatorios. El writer sólo admite preparados propios ya cifrados y una
reserva MISS de 15G2 ligada al mismo JDBC, actor estabilizado y lote nuevo de esa transacción.
Inserta cabecera/campos, sin completar la reserva ni gestionar commit/REQUIRES_NEW propios.
Cualquier error o colisión de nonce marca rollback; no hay retry, upsert ni lectura de ciphertext
con el rol de escritura. Registro/aceptación completos, configuración y mantenimiento quedan en
15I/15L/15O. V27/V28/V29, consumidores actuales, flags, HTTP de escritura y frontend quedan fuera.

Commit 15H1: `feat(legal): prepara captura y cifrado de metadata`.
Commit 15H2: `feat(legal): protege metadata de aceptaciones` (commit original de 15H).


Resultado: captura confiable y cifrado de IP/UA listo para confirmar en el lote, con retención
explícita y claves independientes. No endpoint de lectura de metadata.

Nuevos: `http/LegalRequestMetadataResolver.java`, `db/LegalAcceptanceMetadataCodec.java`,
`LegalAcceptanceMetadataPolicy.java`, `LegalAcceptanceMetadataWriter.java`.
Tests puros y `LegalAcceptanceMetadataIT`.

Gate focal: proxies no confiables, cadena válida/hostil, IP ausente, UA Unicode/512, AAD lote/tipo,
keyrings independientes, nonce duplicado, manipulación de tag, retención inválida y rollback completo.
Ningún secreto en DTO/error/log. No reutilizar el helper que confía en primer X-Forwarded-For.
Commit: `feat(legal): protege metadata de aceptaciones`.

### Cierre 15H1

Gate fresco aprobado el 2026-09-07T06:59:01-03:00, Java 21. Comando focal:

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw \
  -Dtest=LegalRequestMetadataTest,LegalRequestMetadataResolverTest,LegalAcceptanceMetadataPolicyTest,LegalAcceptanceMetadataCodecTest,LegalIdempotencyKeyringTest \
  package antrun:run@verify-no-secret-properties-in-jar
```

259 pruebas en cinco XML frescos, sin fallos, errores u omitidas: 89 del valor core, 67 del resolver,
50 del codec y 13 de política (219 nuevas), más 40 regresiones del keyring idempotente. Los ocho
archivos Java permanecieron idénticos durante el gate. Parser IPv4/IPv6 sin DNS, CIDR/mapped,
spoofing/prefijos, límites/duplicados, Unicode/512, vector AES-GCM fijo, AAD y bytes alterados,
rotación, copias defensivas, nonces por preparado y concurrencia del codec están cubiertos.
El componente sólo cifra; los tests acreditan autenticación y contenido mediante JCE independiente.
No se crea un descifrador productivo, endpoint o configuración de claves/retención reales.

15H1 se cierra con los diez archivos nominales y su commit atómico, sin push. 15H2 conserva pendiente
la persistencia y la prueba PostgreSQL de colisiones globales de nonce, tombstones y rollback del grafo.
La prueba pura no atribuye unicidad global ni commit a un preparado cifrado.

Auditoría H1 independiente aprobada: ambos JAR contienen 938 clases, incluidas ocho nuevas
idénticas a target/classes. V27/V28/V29 congeladas en fuente/target/artefactos; entrypoints correctos,
sin tests, dependencias de test, duplicados ni propiedades secretas. SHA-256:

- Web: `d53e74778bdc432aedb4ba0b01f3fefd4c17ebc5759735ea72e6c12e17c14eb0`.
- CLI: `37c24e26b0df945c58d56284933d0132c1b441ef264cded7a041d52298a93d4f`.

### Cierre 15H2 y 15H

15H2 implementa el writer interno de metadata, ligado a la reserva MISS y al mismo JDBC de 15G2.
Exige actor estabilizado y lote del top XID actual, con actos, sin cabecera previa y con perfil y
revisión del comando. El preparado debe pertenecer al codec del writer. Usa el aceptado_en del
servidor como origen de captura y retención; redondea hacia arriba a microsegundos y rechaza una
retención ya vencida antes de insertar. Verifica la cabecera devuelta por la guarda de V27.

Persiste IP obligatoria y UA opcional sin leer ciphertext, nonce ni IDs generados con el rol
restringido. Cada SQL respeta el presupuesto de la reserva. Un error o colisión marca rollback,
sin retry ni recuperación en una transacción abortada. La metadata no completa la reserva: el
store guarda después el resultado idempotente. No hay conexión/transacción interior ni commit
propio; la prueba confirma invisibilidad de la metadata antes del único commit exterior.

Gate fresco aprobado el 2026-09-07T07:09:58-03:00 con Java 21 y PostgreSQL 16.14 efímero:

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw \
  -Dtest=LegalRequestMetadataTest,LegalRequestMetadataResolverTest,LegalAcceptanceMetadataPolicyTest,LegalAcceptanceMetadataCodecTest,LegalAcceptanceMetadataWriterTest,LegalIdempotencyCoordinatorTest,LegalIdempotencyResultStoreTest \
  -Dit.test=LegalAcceptanceMetadataIT,LegalIdempotencyCoordinatorIT,LegalV29AcceptancePersistenceIT \
  package failsafe:integration-test failsafe:verify antrun:run@verify-no-secret-properties-in-jar
```

370 pruebas en diez XML frescos, sin fallos, errores u omitidas: 289 unitarias (219 H1, siete nuevas
del writer, 38 del coordinador y 25 del store) y 81 PostgreSQL (20 nuevas de metadata, 41 del
coordinador y 20 de persistencia V29). Los doce Java H1/H2 permanecieron idénticos durante el gate.
Se ejecutó una sola corrida Maven, sin clean verify transversal: no hubo fallos ni cambios comunes.

Las pruebas nuevas acreditan ambos roles restringidos, IP sola/UA de 512 code points y 2048 bytes,
retención del servidor, descifrado JCE independiente del contenido persistido y replay sin DML.
La colisión del segundo campo exige tres INSERT intentados, un fallo SQLSTATE 23505 y rollback
completo del lote/actos/documentos/header/primer campo; la evidencia previa permanece idéntica.
Se prueba tanto nonce activo como retenido tras tombstone. El helper de envejecimiento actúa sólo
en PostgreSQL efímero; la transición de purga corre con las guardas reales de V27 activas.

También fallan cerrado el actor/preparado ajenos, IP ausente, lote histórico/ajeno/vacío, cabecera
repetida, reserva de otro JDBC/transacción, reserva consumida/replay y presupuesto o retención
vencidos. Las pruebas observan rollbackOnly antes de propagar errores y el estado durable después
del rollback, incluido un fallo del caller posterior a una inserción de metadata válida.
Las revisiones independientes del writer y de esas assertions no identificaron hallazgos materiales.

Auditoría independiente H2: ambos JAR contienen 940 clases, incluidas las diez de metadata
idénticas a target/classes. V27/V28/V29 mantienen sus hashes congelados en fuente/target/artefactos;
entrypoints correctos, sin tests, dependencias de test, duplicados ni propiedades secretas. SHA-256:

- Web: `1a1af98015be863c322ef4f40b232bff99736174e12315f31e6ce7f24a92cc56`.
- CLI: `3e67de3b7a562ddb1e7de0bd80f796b599b14506a7f5992c87d659c6e0ac205e`.

Se cierra 15H2 con los seis archivos nominales y su commit atómico
`feat(legal): protege metadata de aceptaciones`, sin push. 15H queda completo en dos commits;
15H1 quedó en `91fc5c8`. Frontend y rutas no versionadas preservados; no se alteraron migraciones,
ACL compartidas ni consumidores. La configuración operativa, validación integral de privilegios,
frontera REQUIRES_NEW del servicio completo y activación siguen en 15I/15L; este corte no habilita
escritura HTTP ni configura secretos o retenciones de un entorno real. Sigue **15I: aceptación
interna y atómica**.

## 15I — Aceptación autenticada interna y atómica

### Ejecución subdividida: 15I1, 15I2 y 15I3

Baseline 15I: `9100522`, backend limpio en `codex/lanzamiento-publico-backend` el 2026-09-07.
Frontend `7545201` conserva `.agents/` y `public/OrdenFix project naming/` sin cambios.
Se divide antes de editar para separar selección/evidencia, infraestructura y operación completa.
Los tres pasos completan 15I; cada uno tendrá baseline comprobado, gate y commit atómico sin push.

Lista nominal 15I1:

- Nuevos `db/LegalAcceptanceSelection.java`, `db/LegalAcceptanceValidationException.java` y
  `db/LegalAcceptanceEvidenceReader.java`.
- Nuevos tests `db/LegalAcceptanceSelectionTest.java`, `db/LegalAcceptanceEvidenceReaderIT.java`
  y `db/LegalAcceptanceEvidenceITSupport.java`.
- Este plan y el diseño compañero. No cambia G2 ni la lectura de requisitos/historial.

El lookup específico es necesario porque la lectura de pendientes sólo hidrata líneas vigentes:
la excepción de dedup total también admite actos confirmados que ya no integran el conjunto actual.
Se acreditan por IDs enviados, actor estabilizado, contenido/documentos canónicos y pertenencia
histórica, con batches y límites. Un acto propio ausente es normal; corrupción de evidencia no
se convierte en un error semántico del request. El núcleo conserva duplicados y aplica dedup total
no vacío antes de freshness; vacío/mixto/duplicados pasan por revisión y luego motivos congelados.
La herencia permite omitir obligatorios satisfechos, pero no inventa actos exactos.
El reader admite hasta 2048 IDs y 16 documentos por acto, consulta en batches de 32 y acredita
un máximo de 128 MiB de fuentes distintas antes de hidratar TEXT. Reconstruye agregados históricos
en batches con sus calculadores criptográficos; no introduce una consulta por agregado. Se exige
coherencia temporal de la evidencia respecto de la publicación/activación de sus fuentes.
La cota inferior de publicación/activación aplica a AGGREGATE_V1 (hora de sentencia), no
retroactivamente a SCOPE_V1 (hora de inicio de transacción). Tampoco se infiere un límite superior
desde cambiado_en terminal: una transacción editorial iniciada antes puede esperar el gate y
retirar después. Ambos casos genuinos se acreditan en PostgreSQL con transacciones reales.
El primer gate I1 aprobó 200 unitarias y detectó que el fixture G2 no concedía seis lecturas
editoriales adicionales que este reader necesita. El helper nuevo I1 agregará SELECT exclusivamente
sobre legal_publicaciones, legal_publicacion_requisitos, legal_publicacion_documentos,
legal_documento_contextos, legal_requisito_transiciones y legal_documento_transiciones al rol de
prueba efímero; son parte de la allowlist lectora de I2. No se modifica el fixture G2 ni un rol
compartido. Cada caso de corrupción debe acreditar primero su lectura válida para impedir que
un permiso faltante haga pasar una prueba negativa por una causa distinta de la prevista.

### Cierre 15I1 — selección y evidencia histórica

Gate focal final aprobado el 2026-09-07 a las 08:12:50 -03:00, Java 21 y PostgreSQL 16.14:
**304 pruebas**, 200 Surefire y 104 Failsafe, cero fallos/errores/omitidas.
Los ocho XML nominales de la corrida final acreditan CommandValidator (56), SatisfactionEvaluator
(71), Selection (48), ResultStore (25), EvidenceReader (22), Coordinator (41), HistoryReader (18)
y PrivateRequirementsReadService (23). Se ejecutó package + failsafe:integration-test +
failsafe:verify + antrun:run@verify-no-secret-properties-in-jar con selectores exactos.

La primera corrida tuvo ocho errores de permisos en el fixture de EvidenceReader, con las 200
unitarias aprobadas. Se corrigieron sólo sus dos archivos de test nominales: el rol efímero recibe
las seis lecturas de fuentes faltantes y los casos negativos prueban primero una lectura válida.
La repetición completa del gate focal es la evidencia de cierre; no se suman ambas corridas.
No cambió código productivo para resolver ese fallo ni se modificó el fixture G2.

La selección distingue EMPTY, DEDUP y WITH_ACTS; preserva duplicados y el orden contractual de los
motivos, dedup total antes de freshness y reglas de obligatorios/herencia. El reader acredita
actos propios confirmados incluso fuera del catálogo vigente, con rechazo de snapshot/digest,
pertenencia y corrupción agregada. Las pruebas incluyen 40 actos, dos sesiones con publicación
posterior a la aceptación pese a timestamp editorial anterior y evidencia SCOPE_V1 genuina migrada.
No hay DML ni lectura de metadata personal en este lookup. Revisión independiente sin hallazgos
materiales; V27/V28/V29 congeladas, sin endpoints ni cambios frontend.

Auditoría final: seis fuentes coinciden con el gate y las 16 clases productivas nuevas, incluidas
las internas, son idénticas a target/classes dentro de los JAR web/CLI. Entrypoints correctos; sin
entradas duplicadas, tests ni application-secret. Hashes SHA-256 de artefactos:

- Web: `248abe5a0c2c9858b8bae6380aa270fb50ae781028eff9c6f1396468cc514f85`.
- CLI: `422466a9045f352c7505acde2d5c3c951906001d7cec322089b08879dc0cc073`.

Cierre con los ocho archivos nominales y commit `feat(legal): selecciona aceptaciones y acredita evidencia`,
sin push. I1 implementa selección/lectura internas; la operación completa sigue en I2/I3.

### Ejecución 15I2 — infraestructura aislada

Baseline `94b4d65`, backend limpio y rama confirmada antes de integrar los diez Java nominales.
Se mantiene la lista ya ratificada: nueve archivos nuevos, marker común y ambos documentos.
Gate focal: configuración/frontera nuevas, marker, configuración privada, keyring y codec/política
de metadata; PostgreSQL para privilegios/aislamiento nuevos y regresiones de privilegios privados
y V29. El marker compartido obliga además al clean verify final en I3.

Primera corrida I2: Surefire ejecutó 205 casos y encontró 3 fallos + 11 errores exclusivamente en
ConfigurationTest; Failsafe no llegó a ejecutarse. La condición se evalúa ya en register y la
propiedad requerida ausente produce IllegalStateException en esta versión de Spring. Se corrige
la prueba nominal para envolver register/refresh, acreditar el error concreto y cerrar el contexto
en cualquier salida. La configuración productiva conserva su rechazo estricto; no se relaja para
adaptarla a una assertion incorrecta. Las 17 pruebas de frontera y las regresiones pasaron.

Lista nominal 15I2:

- Nuevos `db/LegalAcceptancePrivilegeVerifier.java`, `db/LegalAcceptanceDatabaseConfiguration.java`,
  `db/LegalAcceptanceTransactionBoundary.java` y `db/LegalAcceptanceKeyConfiguration.java`.
- Existente `db/LegalDatabaseBoundaryMarker.java`: sólo nueva variante ACCEPTANCE para rechazar
  mezcla de contextos durante refresh, sin cambiar guardas de consumidores existentes.
- Nuevos tests `db/LegalAcceptancePrivilegeVerifierIT.java`,
  `db/LegalAcceptanceDatabaseConfigurationTest.java`, `db/LegalAcceptanceTransactionBoundaryTest.java`,
  `db/LegalAcceptanceDatabaseIsolationIT.java` y `db/LegalRestrictedAcceptanceRoleFixture.java`.
- Este plan y el diseño compañero. Roles y secretos sintéticos sólo en PostgreSQL efímero.

La nueva frontera respeta preflight → idempotencia → gate editorial shared → advisory de actor
exclusivo → taller/usuario FOR SHARE. El gate actual entra en el editorial antes del callback, así
que no sirve para replay temprano; se compone una frontera propia sin cambiar ese gate global.
Se reutilizan las clases DataSource/Deadline de requisitos privados con instancias y pool propios;
no se reutilizan sus credenciales ni su contexto. Se conserva su control de deadline/cleanup y
LegalTransactionCompletionState para distinguir commit confirmado, rollback e incertidumbre.
La configuración no escaneable permanece sin endpoints ni activación de entornos reales.
I2 rechaza secretos AES/HMAC iguales y carece de fallback a otro subsistema. La futura frontera
HTTP de 15J/15M, que dispone de la configuración web, debe verificar además la separación respecto
de JWT/credenciales de equipos antes de crear el contexto aislado; no se atribuye esa comprobación
a un contexto que no recibe tales secretos. La retención personal sigue explícita y sin default.

### Cierre 15I2 — infraestructura y permisos de aceptación

Gate focal final aprobado el 2026-09-07 a las 08:21:08 -03:00, Java 21/PostgreSQL 16.14:
**307 pruebas**, 205 Surefire y 102 Failsafe, sin fallos/errores/omitidas. Los once XML exactos:
AcceptanceDatabaseConfiguration (36), AcceptanceTransactionBoundary (17), DatabaseBoundaryMarker
(3), PrivateRequirementsDatabaseConfiguration (46), IdempotencyKeyring (40), MetadataCodec (50),
MetadataPolicy (13), AcceptancePrivilegeVerifier (26), AcceptanceDatabaseIsolation (5),
PrivateRequirementsPrivilegeVerifier (63) y V29AcceptancePrivilegeVerifier (8). Se ejecutó package,
failsafe:integration-test, failsafe:verify y el gate de propiedades secretas del JAR.

La primera corrida falló en 14 assertions/capturas de ConfigurationTest según lo documentado arriba;
se corrigió sólo ese test nominal y se repitió el gate entero. No se cambió código productivo por
esas assertions. La revisión independiente de infraestructura no encontró defectos materiales.

La frontera valida la instancia/manager/propagación/aislamiento/presupuestos antes de tomar conexión
y acredita READ_COMMITTED mutable real en PostgreSQL. Preflight V29 y privilegios ocurren antes de
la operación; la entrada editorial sólo se ofrece para una reserva MISS del mismo JDBC. La clase
de deadline/cleanup privada se reutiliza con un pool y una instancia independientes. El rol exacto
carece de DDL, ownership, membresías, secuencias, DELETE, escritura de cuentas y lectura de secretos
o ciphertext; su capacidad de UPDATE se limita a columnas necesarias para locks protegidos.

Las pruebas de aislamiento usan configuración Spring real y acreditan REQUIRES_NEW ante una
transacción exterior readonly/REPEATABLE_READ del mismo datasource y de otro datasource: se
suspende y restaura el contexto exterior, y su rollback no elimina el ledger interno confirmado.
Se prueba rechazo de replay al intentar entrar al grafo editorial. La configuración queda sin
activar y sin endpoints; retención personal explícita y claves AES/HMAC separadas, sin fallback.
La separación respecto de secretos JWT/equipos se comprobará en el bridge que los conoce (15J/M).

Auditoría final: diez fuentes coinciden con el gate; las 21 clases nuevas y del marker, incluidas
las internas, son idénticas a target/classes en ambos JAR. Entrypoints correctos, sin clases I3,
tests, dependencias de test, propiedades secretas ni duplicados. V27/V28/V29 coinciden en fuente,
target/classes y ambos artefactos. SHA-256:

- Web: `5a79fe0ce29fbec95af0b4696747ce4452b0dacca693d8c0a2915092f54bc8ff`.
- CLI: `ca31a69d996d75cadb6123769450257e460845c277bd00d40b20a3f567546f58`.

Cierre con doce archivos nominales y commit `feat(legal): aisla escritura de aceptaciones`, sin push.
I1 quedó en `94b4d65`; sigue I3 con la operación completa y el clean verify final.

### Ejecución 15I3 — servicio completo y confirmación

Baseline `584e82f`, backend limpio y rama confirmada antes de integrar nueve Java nominales:
ocho archivos nuevos y composición del bean final en DatabaseConfiguration, más ambos documentos.
El servicio une las primitivas ya acreditadas; no crea HTTP ni modifica la configuración web.
El writer usa INSERT SELECT de fuentes canónicas en batches de 32 y valida conteos/IDs; no copia
snapshots del request. Cifra la metadata antes del primer INSERT de evidencia. La operación fuerza
SET CONSTRAINTS ALL IMMEDIATE antes de entregar el receipt tentativo, para acreditar rollback de
fallos diferidos antes de COMMIT. Fallos de COMMIT conservan UNKNOWN sin retry automático; cleanup
tardío conserva COMMITTED y su receipt confirmado.

Gate focal previo al integral: ServiceTest, DatabaseConfigurationTest, TransactionBoundaryTest y
SelectionTest; PostgreSQL ServiceIT, CommitIT y DatabaseIsolationIT. Después clean verify completo
por el marker común, con auditoría fresca de XML, clases y artefactos. Los faults pertenecen sólo
a proxies JDBC de los fixtures efímeros; producción no incorpora interruptores de prueba.

Primera corrida focal I3: 111 unitarias aprobaron y Failsafe ejecutó 48 casos, con 43 errores
previos al servicio. El username sintético del fixture medía 54 caracteres frente a VARCHAR(50).
Se acorta sólo el prefijo del usuario de LegalAcceptanceServiceITSupport a accept-it- (46 caracteres
con UUID); no se altera esquema ni producción. Los cinco IT de aislamiento aprobaron. Se repite
el gate focal completo antes del integral y no se toma el rechazo del fixture como evidencia del
comportamiento del servicio.

Segunda corrida focal I3: 111 unitarias aprobadas y 47/48 IT aprobados, incluidos los diez de
commit/cleanup. La prueba de fallo SQL al insertar la cabecera de metadata no inyectaba su error
porque exigía un espacio entre tabla y lista de columnas. Se corrige únicamente el detector de
INSERT del ServiceIT para admitir espacio o paréntesis como delimitador exacto de tabla, sin
confundirla con la tabla de cifrados. El test sigue exigiendo fallo real SQLSTATE 22012, préstamo
único y rollback completo. Además se completan los dos casos del gate requerido de cambio de
estado durante espera: desactivar usuario y taller, junto a los ya presentes de rol/tokenVersion.
No cambió producción. Se repite el gate focal con 35 casos ServiceIT antes del clean verify.

Gate focal I3 final aprobado el 2026-09-07 a las 08:30:51 -03:00: **161 pruebas**, 111 unitarias
(Configuration 36, Selection 48, Service 10, Boundary 17) y 50 PostgreSQL (Commit 10, Isolation 5,
Service 35), cero fallos/errores/omitidas. Auditoría independiente de los siete XML frescos y nueve
fuentes aprobada; se conserva evidencia antes de clean. Los 35 casos ServiceIT incluyen 34 actos
en dos batches reales, dedup histórico, replay sin DML con gate exclusivo/catálogo retirado,
selección mixta/vacía, herencia, corrupción, fallos por fase y estados del actor durante espera.
Se inicia ahora clean verify integral; el corte aún no se considera cerrado por el gate focal.

Lista nominal 15I3:

- Nuevos `db/LegalAcceptanceService.java`, `db/LegalAcceptanceWriter.java`,
  `db/LegalAcceptanceReceipt.java` y `db/LegalAcceptanceFailure.java`.
- Existente `db/LegalAcceptanceDatabaseConfiguration.java`: composición final del servicio completo.
- Nuevos tests `db/LegalAcceptanceServiceTest.java`, `db/LegalAcceptanceServiceIT.java`,
  `db/LegalAcceptanceCommitIT.java` y `db/LegalAcceptanceServiceITSupport.java`.
- Este plan y el diseño compañero. Toda necesidad de otro archivo se justificará antes de editarlo.

Una única REQUIRES_NEW/READ_COMMITTED acredita el principal servidor, reserva y replay, disponibilidad,
selección, actos/documentos nuevos, metadata y ledger. El receipt se entrega sólo después de commit,
liberación y deadline final; un fallo tardío conserva COMMITTED sin anunciar rollback, y una excepción
al invocar COMMIT permanece UNKNOWN sin retry ni reconciliación automática. DEDUP/EMPTY no crean
evidencia ni metadata: guardan el resultado técnico y pueden materializar el agregado actual que
la observación necesita.
El replay confirmado no realiza DML. La frontera HTTP sigue en 15J.

Commits: I1 `feat(legal): selecciona aceptaciones y acredita evidencia`; I2
`feat(legal): aisla escritura de aceptaciones`; I3 `feat(legal): registra aceptaciones atomicas`.
Gates focalizados por subcorte; la ampliación del marker compartido se acredita además mediante
clean verify al cierre I3. V27/V28/V29 permanecen congeladas en todos los pasos.

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

### Cierre 15I3 — aceptación autenticada interna y atómica

La composición final queda disponible sólo mediante el contexto explícito de aceptación y su
flag exacto. No es escaneable, no usa JPA ni la credencial web y no crea controller/endpoint HTTP.
La operación identifica al usuario desde AuthenticatedUserPrincipal, verifica rol/tenant/estado y
respeta preflight → reserva idempotente → replay o gate editorial compartido → advisory exclusivo
del actor → FOR SHARE de taller/usuario → agregado/disponibilidad → evidencia/selección → escritura.
Después de esperar el gate se vuelven a acreditar rol, tokenVersion y estado de usuario/taller.

El replay confirmado no realiza DML y no depende del catálogo actual ni del gate editorial.
Un MISS que termina en DEDUP o EMPTY no crea ni refecha lotes, actos, documentos aceptados o
metadata personal: guarda el resultado técnico y, para DEDUP, referencias a evidencia existente.
La observación previa reutiliza el agregado actual o lo materializa si aún no existe, dentro de
la misma transacción. WITH_ACTS crea un solo lote con los actos que faltan, snapshots SQL canónicos,
documentos, cabecera/cifrados y ledger. La mezcla conserva los IDs previos sin copiarlos a otro lote.
La metadata se prepara antes del primer INSERT de evidencia; las restricciones diferidas se
fuerzan antes del receipt tentativo y cualquier fallo conocido revierte también el agregado nuevo.

El receipt público de la fachada interna es inmutable y sus diagnósticos no contienen identidad.
LegalAcceptanceFailure separa motivo, completion y persistence: NONE/ROLLED_BACK/COMMITTED/UNKNOWN
no se deducen de la mera presencia de una excepción. Un COMMIT cuyo resultado se pierde queda
UNKNOWN; un fallo posterior a la confirmación conserva COMMITTED y el receipt confirmado. No hay
retry, rollback compensatorio ni reconciliación automática. Las pruebas consultan filas durables
desde otra conexión y verifican que un replay explícito posterior recupera el resultado sin DML.

Los faults permanecen sólo en fixtures PostgreSQL efímeros. Las tres corridas focales y sus
correcciones de fixture se documentan arriba; ninguna exigió cambiar el código productivo después
de integrarlo. El foco final aprobó 161 casos antes del integral. Las revisiones independientes de
servicio, writer, metadata, actor, asserts de rollback y composición no encontraron defectos materiales.
La evidencia de 34 actos acredita dos batches de INSERT dentro de un único commit; no se presenta
como medición del límite máximo, throughput, heap o SLA. La capacidad integral del flujo HTTP sigue
en 15P. El registro de cuenta y sus efectos poscommit siguen en 15L/M.

Gate integral fresco: `./mvnw clean verify`, Java 21/PostgreSQL 16.14, terminado `2026-09-07T08:55:22-03:00`
(23:01 min). **6959 pruebas aprobadas**, 5999 Surefire en 179 suites y 960
Failsafe en 75 suites; cero fallos, errores, omitidas o flakes. Los XML se contrastaron con
el inventario de clases y métodos JUnit compilados, no sólo con el total de consola. También
coinciden las listas de fuentes/outputs del compilador; ninguna prueba nominal quedó omitida.

La auditoría final compara las nueve fuentes nominales con los hashes del gate y verifica el diff
exacto de once archivos frente a I2. Todas las clases y recursos de target/classes coinciden byte
a byte con los JAR web y CLI, con sus entrypoints correctos y sin entradas duplicadas, clases o
dependencias de prueba ni propiedades secretas. V27/V28/V29 coinciden en fuente, baseline, target
y ambos artefactos. El detector genérico de Agent-Class se precisó para distinguir AspectJ,
dependencia runtime de spring-aspects incorporada por starter-data-jpa, de un agente de pruebas.
No se modificó el runtime para resolver esa comprobación; no se empaquetaron agentes de pruebas.

La excepción nominal es aspectjweaver-1.9.25.1.jar, gestionado por Boot 4.0.6, SHA-256
`4fe86fdc18faea571f29129c70eaad5d121363504a06d7907be88f6c60ba3116`; su manifest ofrece
org.aspectj.weaver.loadtime.Agent, pero no hay -javaagent productivo. Ambos JAR contienen las
mismas 122 dependencias, comparadas también por bytes.

- Web: 983 clases y 32 recursos; SHA-256 `bf3dba1d60961746add8d4b4404aa1a443479a526d20f94368e040db039502fa`.
- CLI: 983 clases y 32 recursos; SHA-256 `439899b928d575cdf31553a54de93f88e48a84a1dfc21e6f2a6f3cf23f448587`.

Se cierra I3 con sus once archivos nominales y commit `feat(legal): registra aceptaciones atomicas`,
sin push. **15I completo en tres commits atómicos**: I1 `94b4d65`, I2 `584e82f` y este I3.
Frontend `7545201`, su rama y las rutas no versionadas `.agents/` y `public/OrdenFix project naming/`
preservados. No se activa un entorno real ni se configura retención, credenciales o claves operativas.
Sigue **15J: POST autenticado y errores contractuales**; permanecen pendientes registro atómico,
sesión, enforcement, mantenimiento, capacidad HTTP y cierre integral del bloque 15 (K–Q).

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

### División de 15J ratificada antes de editar código

15J combina protocolo de entrada, composición del servicio y exposición de seguridad/HTTP. Se
separa en J1/J2/J3; cada uno termina compilable, con gate propio y commit atómico. La ruta de escritura
se publica sólo al cerrar J3. No cambian el wire, las migraciones ni los estados durables de 15I.

- **15J1 — parser y decisiones de transporte.** Baseline `d7d87c8`, backend limpio en
  `codex/lanzamiento-publico-backend`; frontend `7545201` y sus dos rutas no versionadas preservados.
  Nuevos `http/LegalAcceptanceRequests.java`, `http/LegalAcceptanceHttpException.java` y sus tests
  nominales `LegalAcceptanceRequestsTest.java` / `LegalAcceptanceHttpExceptionTest.java` en el paquete
  HTTP equivalente. Existentes: sólo este plan y el diseño compañero (seis archivos en total).
  Sin beans/controller/advice nuevos. Gate focal: límites y sintaxis hostiles, presencia/precedencia
  del header, estructura exacta, tipado sin coerción, preservación de duplicados/false/vacío,
  inmutabilidad/redacción y clasificación segura de todos los errores de 15I. Regresión de comando
  canónico y proyección privada. Commit: `feat(legal): valida solicitudes de aceptacion`.
- **15J2 — composición aislada y actor antes del payload.** Partirá del commit de J1; confirmar
  baseline y whitelist nominal antes de editar. Previstos `http/LegalAcceptanceHttpConfiguration.java`,
  `http/LegalAcceptanceHttpSettings.java` y tests correspondientes; ampliación nominal del servicio
  interno y sus pruebas para diferir la lectura del request hasta observar el actor persistido,
  dentro de su frontera existente. Mantener el API interno actual y el segundo chequeo bajo locks.
  El bridge selecciona sólo propiedades legales, verifica separación de todas las claves retenidas
  frente a JWT/cifrado de equipos, administra su propio contexto/pool y configura proxies explícitos.
  Gate focal de composición y PostgreSQL, sin ruta publicada. Commit previsto:
  `feat(legal): conecta aceptacion al contexto web`.
- **15J3 — POST y gate HTTP.** Partirá del commit de J2; confirmar whitelist nominal antes de editar.
  Previstos `http/LegalAcceptanceController.java`, `http/LegalAcceptanceExceptionHandler.java`,
  `LegalAcceptanceControllerTest.java`, `db/LegalAcceptanceHttpIT.java` y fixture nuevo nominal si
  corresponde. Extensión exacta del entry point privado (y sus tests) para 401 del POST bajo su flag;
  no ampliar la política JWT global de 15K. Mantener GET, CORS y flags apagados. Gate HTTP/PG completo
  del alcance 15J: actor/header/JSON en orden, 204 vacío, 400/409/503, no-store, Retry-After:1,
  replay/dedup/lista vacía y metadata. Ese cambio de seguridad compartida exige `clean verify`.
  Commit previsto: `feat(legal): publica aceptaciones del usuario`.

Decisiones de borde de J1: header presente repetido, null, vacío o no UUID v4 canónico minúsculo
se rechaza antes de leer el cuerpo; header ausente se reclama sólo después de JSON/DTO válidos.
JSON UTF-8 estricto sin BOM, una única raíz objeto, claves únicas y whitelist exacta en los tres
niveles. Propiedad JSON ausente/null, propiedad extra (incluida identidad/autoridad), sintaxis, coerción o exceso
responden 400 `ACEPTACION_LEGAL_INVALIDA` / `PAYLOAD_LEGAL_INCOMPLETO`, sin eco de entrada ni causas.
Los UUID editoriales requieren formato hexadecimal canónico de 36 caracteres, sin restringirlos a
v4; los digests y revisión mantienen su gramática minúscula. Sólo booleanos JSON para `confirmado`;
false, arrays vacíos y duplicados de arrays siguen hasta la fase semántica de 15I.
Límites congelados de 15A: 8 MiB de bytes, profundidad 32, 300000 tokens, string 1 MiB y nombre 256,
2048 actos/16 documentos. El parser no cierra el stream de servlet, no acepta identidad ni realiza
acceso a DB; no sirve como acreditación de actor. J2/J3 deben invocarlo sólo después de esa observación.
El rechazo por Content-Type/charset/query del POST se instrumentará después del actor en J3;
no se delega a resolvers MVC que puedan adelantar el error.

### Cierre 15J1 — 2026-09-07

Cerrados los seis archivos nominales sobre `d7d87c8`: dos clases de producción package-private,
dos suites nuevas y plan/diseño. El parser es explícito y sin beans; el traductor entrega decisiones
seguras para el advice futuro. No se modificaron servicio interno, autenticación, configuración,
persistencia, frontend ni migraciones, y no se publicó el POST.

Java 21.0.10; Maven 3.9.11. Compilación inicial aprobada; gate focal final:

```sh
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw -B \
  -Dtest=LegalAcceptanceRequestsTest,LegalAcceptanceHttpExceptionTest,LegalAcceptanceCommandTest,LegalAcceptanceCommandValidatorTest,LegalIdempotencyFingerprintTest,LegalPrivateRequirementsControllerTest,StrictJsonReaderTest \
  package
```

| Suite final | Casos | Fallos / errores / omitidos |
| --- | ---: | --- |
| LegalAcceptanceRequestsTest | 128 | 0 / 0 / 0 |
| LegalAcceptanceHttpExceptionTest | 50 | 0 / 0 / 0 |
| LegalAcceptanceCommandTest | 6 | 0 / 0 / 0 |
| LegalAcceptanceCommandValidatorTest | 56 | 0 / 0 / 0 |
| LegalIdempotencyFingerprintTest | 34 | 0 / 0 / 0 |
| LegalPrivateRequirementsControllerTest | 42 | 0 / 0 / 0 |
| StrictJsonReaderTest | 30 | 0 / 0 / 0 |
| **Total** | **346** | **0 / 0 / 0** |

Son 178 casos nuevos y 168 de regresión. Los siete XML son frescos, posteriores a la última corrección;
no se suman reportes anteriores. Sin flaky/rerun. Maven final terminó a las 09:33:26 -03:00, en
17.980 s. La primera ejecución tuvo 0 fallos y 11 errores de fixture en la nueva suite de excepciones:
se anidaba la creación/configuración de un mock dentro del `when(...).thenReturn(...)` de otro.
Se corrigieron ocho asignaciones con `doReturn(...).when(...)`, sin alterar producción ni expectativas,
y se repitió el gate focal completo. Ese defecto de preparación no afecta componentes compartidos
ni justifica ampliar a clean verify; el gate integral de seguridad sigue previsto en J3.

Acreditados 2048 actos × 16 referencias documentales, sin eliminar duplicados, y cuerpo válido de
8 MiB incluyendo whitespace; un exceso consume como máximo el byte centinela y no cierra el stream.
La prueba masiva es de forma/lectura, no de consentimiento, unicidad, SQL ni SLA. Las entradas con
profundidad/tokens/nombres/strings excesivos se rechazan; algunas formas quedan excluidas por reglas
más estrictas del DTO antes de alcanzar el límite defensivo de Jackson. No se atribuye a esas pruebas
un umbral que no haya sido el primer rechazo observado.

Revisión independiente de contrato, código y matriz transaccional sin hallazgos materiales. Auditoría
final del empaquetado: cuatro clases nuevas (las dos principales, Parsed y el switch sintético), sin
anotaciones de controller/mapping/configuración/bean; las 1015 entradas previas (983 clases y 32 recursos)
conservan sus hashes. Cada JAR coincide con target en sus 987 clases y 32 recursos y ambos conservan
122 bibliotecas idénticas. Start-Class web/CLI correctos, sin clases/dependencias/agentes de tests,
propiedades secretas ni duplicados. El AspectJ weaver de runtime existente permanece acreditado como
dependencia JPA, sin agente inesperado. V27/V28/V29 conservan sus hashes congelados en fuente y JAR.

SHA-256 de artefactos finales:

- `mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar`: `abf332c4f6b63ee7c82743a0bcf1fcfd194bb7e317e670c98db43ee3bcc6f808`.
- `mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar`: `d73c111ab8dc38789464b34500ec3ebd68c194ab7fd9804763a592a31de7cdd5`.

Cierre mediante commit atómico `feat(legal): valida solicitudes de aceptacion`, sin push. Siguiente:
**15J2**, para conectar configuración aislada y observar actor persistido antes de consumir header/JSON.
J3 conserva la publicación, metadata HTTP, errores/envelopes/headers reales y gate PostgreSQL/seguridad;
15K–Q siguen pendientes. El clean verify de 6959 casos es baseline de 15I, no evidencia nueva de J1.

### Ejecución 15J2 — composición y entrada diferida

Baseline `63ba195`, backend limpio en `codex/lanzamiento-publico-backend`; frontend `7545201`,
rama y dos rutas no versionadas preservados. Se confirman antes de editar estos **14 archivos**:

- Nuevos de producción: `core/LegalAcceptanceInput.java`, `core/LegalAcceptanceInputException.java`,
  `http/LegalAcceptanceHttpSettings.java`, `http/LegalAcceptanceHttpConfiguration.java`.
- Existentes de producción: `db/LegalAcceptanceService.java` (overload diferido conservando API y
  rechazo temprano de metadata nula), `db/LegalAcceptanceFailure.java` (clasificación neutral de
  entrada), `db/LegalAcceptanceTransactionBoundary.java` (deadline/cleanup también tras excepción),
  `http/LegalAcceptanceHttpException.java` (recuperar sólo códigos de entrada acreditados).
- Nuevos tests: `http/LegalAcceptanceHttpSettingsTest.java`, `http/LegalAcceptanceHttpConfigurationTest.java`,
  `db/LegalAcceptanceDeferredPayloadIT.java`; existente `http/LegalAcceptanceHttpExceptionTest.java`.
  Paquetes equivalentes bajo src/test/java; se reutiliza sin modificar LegalAcceptanceServiceITSupport.
- Este plan y el diseño compañero para decisiones/evidencia. Sin otros archivos autorizados ni
  modificación de fixtures históricos, pools compartidos, JWT global o migraciones congeladas.

API neutral: LegalAcceptanceInput contiene clave/revisión/actos/metadata, copia defensiva acotada y
redacción; su Reader.read(Runnable checkpoint) se ejecuta una vez después de observeActor dentro de
REQUIRES_NEW/READ_COMMITTED, antes de comando/reserva. Principal inválido no invoca callback; reader
nulo/retorno nulo/fallo inesperado fallan cerrado. El API actual conserva firma y garantías previas.
LegalAcceptanceInputException sólo admite REQUIRED_KEY, INVALID_KEY e INVALID_PAYLOAD, sin causa ni
texto externo. El adaptador futuro convierte sólo esos errores de J1; los fallos de captura siguen
operativos. Failure conserva Optional<inputReason>; HTTP sólo recupera el 400 tras rollback acreditado,
NOT_PERSISTED y sin recibo/validación contradictoria. No se busca una excepción HTTP en cadenas arbitrarias.
Se comprueba deadline antes/después del reader y después de rollback/cierre aun si hubo rechazo: un
vencimiento o fallo de cleanup prevalece como UNAVAILABLE. Se conserva la segunda observación de actor
bajo locks. El checkpoint es cooperativo: no interrumpe una lectura servlet bloqueada ni acredita SLA;
J3 integra el stream/servidor y 15P verifica capacidad HTTP.

Settings valida flags exactos (aceptación requiere lectura), selecciona sólo tres credenciales de
aceptación, ambos flags legales, versiones/keyrings completos, TTL y retención. Sin fallback a JDBC
web/read ni copia de JWT, clave de equipos, fuentes/perfiles ambientales o propiedades de proxy.
Compara todas las versiones retenidas AES/HMAC con bytes efectivos del JWT (UTF-8 original) y equipos
(Base64 después de trim, incluida representación sin padding); también rechaza la misma cadena de
configuración del JWT y una clave legal. No se usan ni registran secretos reales para las pruebas.
El contexto existente I2 conserva la validación criptográfica completa antes de crear el pool.

Propiedad nueva de captura: `ordenfix.legal.account-metadata.trusted-proxy-cidrs`, CSV de hasta 4096
caracteres y 64 CIDR. Ausente/vacío significa ninguna confianza; permite sólo espacios/tabs ASCII
alrededor de cada CIDR, rechaza elementos vacíos y formato indexado. No usa el flag de rate limit. El bridge exige `server.forward-headers-strategy=none` explícito cuando está habilitado
para evitar defaults cloud y rechaza remote-ip-header/protocol-header de Tomcat con texto, que podrían
activar RemoteIpValve incluso bajo none. No cambia configuración real ni habilita flags. No existen
reescritores de peer propios en el runtime actual; J3 deberá acreditar peer original con el servidor
real, y rechazar configuraciones incompatibles antes de publicar la captura.

El bridge web sólo exporta el servicio de aceptación y su resolver de metadata; administra un contexto
sin parent, fuentes por defecto ni perfiles, con pool restringido propio. Cierra al fallar refresh,
al destruirse o fallar el contexto web posterior; no reabre después de destroy. J2 no registra controller,
advice ni escritura HTTP. Gate focal: suites nuevas, servicio/commit/configuración/metadata/J1 afectados,
PostgreSQL16 real y auditoría de ambos JAR. El cierre integral de seguridad compartida permanece en J3.
Commit: `feat(legal): conecta aceptacion al contexto web`.

### Cierre 15J2 — 2026-09-07

Completados los 14 archivos nominales sobre `63ba195`. El contexto web sólo recibe el servicio y el
resolver de metadata; el contexto JDBC independiente conserva rol restringido, pool propio,
REQUIRES_NEW/READ_COMMITTED, preflight V29 y presupuesto de 15 s. La entrada diferida no elige actor,
no abre conexiones paralelas ni altera la reserva/replay de 15I. Se preserva el API anterior, incluido
su rechazo de metadata nula antes de DB. Ningún controller/advice ni flag de producción habilitado.

Gate final aprobado en el primer intento con Java 21.0.10, Maven 3.9.11 y PostgreSQL 16.14
(`postgres:16-alpine`, cuatro bases efímeras). Comando ejecutado secuencialmente, sin otro Maven:

```sh
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw -B \
  -Dtest=LegalAcceptanceHttpConfigurationTest,LegalAcceptanceHttpSettingsTest,LegalAcceptanceHttpExceptionTest,LegalAcceptanceRequestsTest,LegalAcceptanceServiceTest,LegalAcceptanceDatabaseConfigurationTest,LegalAcceptanceTransactionBoundaryTest,LegalPrivateRequirementsHttpConfigurationTest,LegalRequestMetadataResolverTest,LegalAcceptanceCommandValidatorTest \
  -Dit.test=LegalAcceptanceDeferredPayloadIT,LegalAcceptanceServiceIT,LegalAcceptanceCommitIT,LegalAcceptanceDatabaseIsolationIT \
  package failsafe:integration-test failsafe:verify
```

| Suite final | Motor | Casos | Fallos / errores / omitidos |
| --- | --- | ---: | --- |
| LegalAcceptanceHttpConfigurationTest | surefire | 28 | 0 / 0 / 0 |
| LegalAcceptanceHttpSettingsTest | surefire | 114 | 0 / 0 / 0 |
| LegalAcceptanceHttpExceptionTest | surefire | 59 | 0 / 0 / 0 |
| LegalAcceptanceRequestsTest | surefire | 128 | 0 / 0 / 0 |
| LegalAcceptanceServiceTest | surefire | 10 | 0 / 0 / 0 |
| LegalAcceptanceDatabaseConfigurationTest | surefire | 36 | 0 / 0 / 0 |
| LegalAcceptanceTransactionBoundaryTest | surefire | 17 | 0 / 0 / 0 |
| LegalPrivateRequirementsHttpConfigurationTest | surefire | 35 | 0 / 0 / 0 |
| LegalRequestMetadataResolverTest | surefire | 67 | 0 / 0 / 0 |
| LegalAcceptanceCommandValidatorTest | surefire | 56 | 0 / 0 / 0 |
| LegalAcceptanceDeferredPayloadIT | failsafe | 28 | 0 / 0 / 0 |
| LegalAcceptanceServiceIT | failsafe | 35 | 0 / 0 / 0 |
| LegalAcceptanceCommitIT | failsafe | 10 | 0 / 0 / 0 |
| LegalAcceptanceDatabaseIsolationIT | failsafe | 5 | 0 / 0 / 0 |
| **Total** | **550 Surefire + 78 Failsafe** | **628** | **0 / 0 / 0** |

Son 179 casos nuevos (114 settings, 28 composición, 28 diferimiento PostgreSQL y nueve extensiones
del traductor) y 449 regresiones. Los catorce XML son frescos y corresponden a las doce fuentes Java
finales registradas; sin flaky/rerun ni reportes anteriores sumados. Maven terminó a las
14:26:02 -03:00 en 01:51 min. No hubo fallos de compilación, tests ni correcciones posgate.

Evidencia nueva: cero callbacks para principal/actor rechazados, un callback dentro de la transacción
restringida por invocación incluso replay, copia independiente del borrador, cero DML antes de input
válido y ante rechazo. Usuario/taller/token/rol/pertenencia revocados durante la lectura vuelven a
fallar bajo locks antes de escribir. Una excepción ajena al marcador no puede fabricar rechazo de
actor ni validación legal. Expiración durante el reader (con retorno, checkpoint o rechazo) y error
de cierre prevalecen como UNAVAILABLE; rollback con ACK perdido permanece UNKNOWN. Commit con ACK
perdido conserva UNKNOWN sin rollback inventado; un reintento explícito posterior lee el resultado
confirmado con DML cero. Los fallos físicos inyectados son casos de prueba esperados, no fallos del gate.

Composición: las claves activas/históricas y su representación efectiva se contrastan con
la política completa de settings; las versiones retenidas se enumeran por sus nombres canónicos
punteados. Valores sólo disponibles en fuentes no enumerables no constituyen un keyring completo.
El CSV limita bytes ASCII antes de dividir y no acepta confianza parcial ni configuración indexada.
Los tests prueban aislamiento de fuentes/perfiles/beans, ausencia de fallback, dos pools privados
separados del web, cero conexiones al inicializar, cierre tras fallos de refresh/web/destroy y
prohibición de reabrir. La validación de retención/keyrings de I2 sigue anterior a la creación del pool.

Auditoría final de ambos JAR: 994 clases y 32 recursos con bytes idénticos a target, siete clases
nuevas exactas y nueve clases existentes modificadas desde los archivos nominales; las restantes
1010 entradas del baseline conservan sus hashes. Sin controller/mapping nuevo. Start-Class web/CLI
correctos, 122 bibliotecas idénticas, sin clases/dependencias/agentes de tests, propiedades secretas,
ZIP duplicados ni agentes inesperados. El AspectJ weaver de runtime conserva su procedencia JPA.
V27/V28/V29 mantienen sus hashes congelados en fuente y JAR. SHA-256 finales:

- `mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar`: `dfce658ee4d58625329edc93237fb8323b11507639253fcedecf9e9c28551701`.
- `mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar`: `1007542dd6ac5086158e06be2bee7cb92fdfa26cc3a697cb2518fbbeb835c20c`.

Cierre mediante commit atómico `feat(legal): conecta aceptacion al contexto web`, sin push. Frontend,
archivos no versionados y fixtures históricos preservados. **Sigue 15J3**: convertir los rechazos
conocidos de J1 al marcador neutral, conectar captura/stream con checkpoints y acreditar peer original
frente al servidor/reescritores, publicar POST y advice con 204/400/401/409/503, no-store/Retry-After,
replay/errores reales y clean verify por el cambio de seguridad compartida. J2 no acredita preempción
sobre una lectura servlet bloqueada, endpoint desplegado ni disponibilidad de producción. 15K–Q
siguen pendientes; el clean verify de 6959 casos sigue siendo baseline de 15I.

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

## Ejecución 15C — 2026-09-06

Corte cerrado desde `c2fa300`: nueve clases productivas nuevas, dos ampliaciones aditivas de la
frontera compartida, nueve archivos propios de pruebas/fixture y los dos documentos del bloque.
Son 22 rutas nominales. V27/V28/V29 y los inventarios/allowlists históricos permanecen intactos.
El servicio es interno, explícito, sin controller, endpoint ni importación en el contexto web.
No se cambian credenciales/configuración de entornos compartidos ni frontend.

La revisión independiente cubrió actor, permisos, origen histórico, coherencia y recursos. Los
casos acreditan ADMIN/USER, mismo taller/otro taller, cambio legítimo de rol, principal desactualizado,
active/taller.activo/tokenVersion, fallos de locks/preflight, REQUIRES_NEW/READ_COMMITTED, rollback y
cierre. Reutilizar la observación no hace DML de agregado ni de evidencia. Se conserva la revisión
completa cuando la lista de pendientes es vacía; herencia y aceptación exacta no fabrican actos.

La herencia se probó con REPLACE reales false→true→false y 130 versiones persistidas de una línea:
la lectura observó 131 filas de versiones de requisitos incluyendo la segunda línea vigente.
El true temprano no se perdió tras varios batches y bytes/fechas/xmin de la evidencia original
permanecieron iguales. No se usó replica para producir esa historia ni se fingieron timestamps.
Una aceptación confirma bajo advisory exclusivo mientras el lector espera el shared; tras adquirirlo,
el lector acredita el resultado completo con la hora refrescada y sin DML de agregado.

Una base efímera separada creó evidencia SCOPE_V1 genuina en V27, con la transacción empezada antes de
publicar el catálogo, y la migró a V29 con guardas y constraints activos. La lectura la reconoce
EXACT y preserva sus columnas históricas/xmin. Las inyecciones de corrupción sí son explícitas y
están limitadas a fixtures desechables: digest/documentos incompletos, snapshot de lote ajeno y
vigencia documental histórica imposible fallan cerrados. La activación de cada documento respeta
su propio vigente_desde conforme a V27; no se impone orden temporal entre el lote y la publicación.

La operación usa 15 s monotónicos exteriores, pool máximo 2, borrow/connect/login/validation/cancel
1 s, statement/socket 5 s, locks 1 s y fetch/batch 32. Se verificaron sentinelas, presupuestos de
65536 filas y 128 MiB de fuentes históricas, transiciones y cierre/cancelación. No se afirma SLA,
heap ni capacidad integral de 15P: el plazo impide entregar observaciones vencidas y la limpieza
puede finalizar después. La coherencia presupone que los futuros escritores respeten el advisory
de actor; no se atribuye esa propiedad a un INSERT arbitrario con otra credencial.

### Evidencia focal y correcciones

Se ejecutó Java 21.0.10, Maven 3.9.11 y PostgreSQL 16.14 en contenedores efímeros. No se ejecutó
clean verify: las únicas ampliaciones comunes son un nuevo marcador y un método de acreditación;
se probaron sus suites y las regresiones públicas. El gate integral fresco sigue reservado a los
cortes transversales previstos y 15Q. Reportes XML inspeccionados por clase, sin sumar repeticiones.

- Compilación inicial: un test usaba un setter de password inexistente; se corrigió con el builder
  de fixture, sin modificar User ni auth.
- Primera tanda: 220 casos, una aserción de configuración fallida. Hikari crea su MXBean al
  inicializar el pool aunque no abra conexiones; se verifican cero conexiones, no MXBean nulo.
- Primera integración: 96 casos, 60 errores del fixture ACL; servicio/contexto aprobaron sus 33 casos.
  REVOKE SELECT de tabla también revoca grants de columnas del mismo rol: el test ahora restaura
  sólo las columnas nominales de users/talleres. No se relajó el verificador de producción.
- Siguiente focal: 102 unitarios y 82 PostgreSQL aprobados; incluye 63 casos de permisos y 19 del
  servicio con el linaje de 130 versiones. Terminó 20:44:54 -03:00 en 3:24 min.
- Compatibilidad: 36 unitarios y 79 PostgreSQL aprobados; 22 privados y 57 regresiones públicas,
  incluida la historia V27 y la observación concurrente. Terminó 20:52:50 -03:00 en 4:02 min.
- Focal final tras el control de vigencia documental: 36 unitarios y 23 PostgreSQL aprobados,
  más el gate `verify-no-secret-properties-in-jar` para ambos artefactos. Terminó 2026-09-06T20:56:38-03:00 en  02:38 min.

| Suite focal nueva | Casos | Fallos / errores / omitidos |
| --- | ---: | --- |
| LegalActorSnapshotReaderTest | 39 | 0 / 0 / 0 |
| LegalPrivateRequirementsReaderTest | 36 | 0 / 0 / 0 |
| LegalPrivateRequirementsDatabaseConfigurationTest | 45 | 0 / 0 / 0 |
| LegalPrivateRequirementsDataSourceTest | 21 | 0 / 0 / 0 |
| LegalPrivateRequirementsDeadlineTest | 9 | 0 / 0 / 0 |
| LegalPrivateRequirementsReadServiceIT | 23 | 0 / 0 / 0 |
| LegalPrivateRequirementsDatabaseContextIT | 16 | 0 / 0 / 0 |
| LegalPrivateRequirementsPrivilegeVerifierIT | 63 | 0 / 0 / 0 |

Total único de este corte: **430 pruebas aprobadas**, 252 nuevas y 178 regresiones; 15 XML,
271 Surefire y 159 Failsafe, cero fallos/errores/omitidas en sus ejecuciones finales. Regresiones:
LegalManifestDatabaseGateTest (41), LegalDatabaseBoundaryMarkerTest (3),
LegalPublicRequirementsDatabaseConfigurationTest (58), LegalRequiredSetAggregateServiceTest (19),
LegalPublicRequirementsReadServiceIT (34), LegalPublicRequirementsDatabaseContextIT (7) y
LegalPublicDocumentReadServiceIT (16).

Los comandos se ejecutaron secuencialmente, sin Maven concurrente sobre target. La última ejecución:

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw -Dtest=LegalPrivateRequirementsReaderTest \
  -Dit.test=LegalPrivateRequirementsReadServiceIT package \
  failsafe:integration-test failsafe:verify antrun:run@verify-no-secret-properties-in-jar
```

La auditoría independiente comparó 42 clases de las once fuentes productivas nominales
con target/classes en ambos JAR; los 20 archivos de código/pruebas no cambiaron durante el gate final.
Los nueve tipos de pruebas/fixture no están empaquetados, Start-Class web/CLI es correcto y no hay
application-secret.properties. V27/V28/V29 coinciden byte a byte con sus fuentes congeladas;
V29 mantiene SHA-256 `976a66c0a7f234e79c1ba84be4721ecb2407a1d6e076f2444630ccb9afc949e9`.

| Artefacto final | SHA-256 |
| --- | --- |
| CLI legal | `0f10b8cd0d7abb940681f6faa930132661e7d9f37d3187f99c93e2cf8539361c` |
| Aplicación | `1dd32ab56237422ced1905618ba9ff642099b8d5365014e4b8b5fef828c28605` |

Cierre: commit atómico `feat(legal): consulta pendientes privados`, local y sin push. Backend en la
rama prevista; frontend preservado en 7545201 con sus dos rutas no versionadas. Sigue **15D — GET
autenticado de requisitos**, usando este servicio y conservando la frontera privada.


## Ejecución 15D — 2026-09-06

Cerrado desde `cf50844` en la rama prevista. Catorce archivos nominales: seis clases nuevas,
una conexión opcional en SecurityConfig, cuatro suites nuevas y tres documentos. Se reutilizaron
los fixtures propios de 15A/15C sin modificarlos; no hizo falta el helper HTTP adicional previsto.
No cambian persistencia, SQL congelado, permisos, wire público, configuración de entorno ni frontend.

El GET privado exige principal del servidor y permiso ADMIN/USER; sólo publica la proyección
completa entregada después de la transacción 15C. Incluye pendientes opcionales/obligatorios,
conserva el token completo al filtrar y devuelve una lista vacía legítima para el actor satisfecho.
El contrato privado no contiene identidad, evidencia, metadata, procedencia ni revisiones de scopes.
Las pruebas de MVC incluyen composición multicontexto real del núcleo puro; PostgreSQL usa la
política actual USO_CONTINUADO para ambas audiencias, sin ampliar contextos por falta de evidencia.

Pruebas HTTP con JWT/filtros reales y sólo colaboradores JWT/UserDetails simulados: credencial
PostgreSQL restringida, preflight V29, actor/tenant/rol/tokenVersion/estado discordantes, evidencia
ajena en el mismo taller y en otro tenant, propios totalmente satisfechos, reutilización sin DML,
If-None-Match sin304, rollback, error sanitizado y cierre del pool. El nuevo caso de rollback crea
un agregado ADMIN con historia propia USER corrupta: se ejecutan las dos inserciones, el reader
rechaza la evidencia y ambas se revierten; agregado anterior y conteos quedan intactos. El owner
sólo prepara corrupción dentro de la base efímera protegida por su prefijo nominal.

Se mantuvo el gate focal acordado, con regresiones públicas y de autenticación/tenant por el
entry point opcional. No hubo cambio de autorización general ni fallo de regresión transversal;
clean verify continúa reservado al gate integral previsto. Los errores iniciales fueron de pruebas:
17 aserciones esperaban el texto viejo de Spring para 403 (el actual es Forbidden), siete suponían
404/405 donde el advice global previo devuelve500 y un matcher Mockito ambiguo requería DecodedJWT
explícito. Se corrigieron fixtures/aserciones sin modificar auth ni el advice global. La revisión
sí corrigió antes del gate la serialización textual de timestamp y el guard compartido de URI cruda.

La primera integración aprobó 42 casos MVC y 43 PostgreSQL. La auditoría pidió además acreditar
el rollback posterior a un agregado nuevo desde HTTP; su caso se añadió al gate final. No se suman
las repeticiones a los totales de cierre.

### Evidencia final

Java 21.0.10, Maven 3.9.11, PostgreSQL 16.14 en contenedores efímeros. Gate final aprobado el
2026-09-06T21:22:33-03:00, duración 1:44 min. Se inspeccionaron los once XML nominales:
**209 pruebas aprobadas**, 137 nuevas y 72 regresiones; 131 Surefire y 78 Failsafe, sin fallos,
errores ni omitidas.

| Suite | Casos | Fallos / errores / omitidos |
| --- | ---: | --- |
| LegalPrivateRequirementsControllerTest | 42 | 0 / 0 / 0 |
| LegalPrivateRequirementsHttpConfigurationTest | 32 | 0 / 0 / 0 |
| LegalPrivateRequirementsAuthenticationEntryPointTest | 19 | 0 / 0 / 0 |
| LegalPublicRequirementsSecurityTest | 16 | 0 / 0 / 0 |
| LegalPublicDocumentSecurityTest | 9 | 0 / 0 / 0 |
| JwtSecurityIntegrationTests | 3 | 0 / 0 / 0 |
| TenantIsolationTests | 5 | 0 / 0 / 0 |
| AuthTests | 5 | 0 / 0 / 0 |
| LegalPrivateRequirementsHttpIT | 44 | 0 / 0 / 0 |
| LegalPublicRequirementsHttpIT | 27 | 0 / 0 / 0 |
| LegalPublicDocumentHttpIT | 7 | 0 / 0 / 0 |

Comando secuencial final (sin ejecuciones Maven concurrentes sobre target):

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw \
  -Dtest=LegalPrivateRequirementsControllerTest,LegalPrivateRequirementsHttpConfigurationTest,LegalPrivateRequirementsAuthenticationEntryPointTest,LegalPublicRequirementsSecurityTest,LegalPublicDocumentSecurityTest,JwtSecurityIntegrationTests,TenantIsolationTests,AuthTests \
  -Dit.test=LegalPrivateRequirementsHttpIT,LegalPublicRequirementsHttpIT,LegalPublicDocumentHttpIT \
  package failsafe:integration-test failsafe:verify antrun:run@verify-no-secret-properties-in-jar
```

Los once archivos de código/pruebas permanecieron idénticos durante el gate. Una revisión
independiente acreditó las once clases compiladas de las siete fuentes productivas contra
`target/classes` en ambos JAR, Start-Class correcto para web/CLI, ausencia de clases de tests y de
application-secret, y hashes congelados V27/V28/V29 en fuentes y artefactos. No se activaron flags ni se provisionaron
grants compartidos. No se hizo push. Frontend `7545201` y sus dos rutas no
versionadas permanecen preservados.

El 500 previo para rutas/métodos sin mapping queda caracterizado como limitación del advice global,
fuera del nuevo GET. No constituye un permiso ni consulta legal. El flag privado permanece apagado
por defecto y el handoff global sigue cerrado. Commit del corte:
`feat(legal): publica requisitos del usuario`. Sigue **15E — historial de evidencia propia**.


## Ejecución 15E — 2026-09-06

Cerrado desde `688b020`, rama y baseline verificados antes de editar; frontend `7545201` preservado.
Veintitrés archivos nominales: once de producción (ocho nuevos), nueve de pruebas/fixture (seis
nuevos) y tres documentos. Las ampliaciones previas están registradas en la lista del corte. No se
modificaron SQL, verificadores de schema/ACL, readers previos, roles compartidos ni configuración
de entorno. El fixture nuevo reutiliza los de 15A/15C sin cambiarlos.

El servicio histórico se ejecuta en la frontera privada existente, con su rol restringido y todos
los locks/plazos de 15A/15C. El puente posee un único contexto sin padre y expone dos fachadas. No
invoca scopes actuales ni store: todo GET de historia usa cero DML y no necesita un catálogo
vigente. Actor, count, selección, fuentes, documentos y transiciones se acreditan antes de devolver
la página tras commit/cierre. Las versiones históricas, textos y fechas originales permanecen
iguales tras REPLACE/RETIRE; no se convierte una decisión de herencia en evidencia nueva.

La revisión independiente confirmó el aislamiento, conteo sin ocultar filas, ambos esquemas de
pertenencia histórica, límites/sentinelas antes de texto y ausencia de consultas de metadata.
Se añadió comparación binaria UTF-8 de campos del snapshot para evitar depender de collation.
El IT crea SCOPE_V1 genuino bajo V27 con constraints activos y transaction_timestamp anterior a la
publicación; migra a V29 y compara bytes, IDs, fechas y xmin. No simula historia legacy insertando
sólo un enum antiguo sobre el esquema nuevo.

Concurrencia acotada: un lector se detiene después de su count SQL real; otro backend PostgreSQL
queda esperando el advisory exclusivo de ese actor, observado mediante pg_locks. La primera página
conserva count/content iniciales y la siguiente observación incluye el acto recién confirmado.
La prueba presupone el protocolo de escritores 15A y no se extiende a DML arbitrario externo.
Capacidad focal: 105 actos, página de 100 con cuatro batches documentales y cinco actos en la
siguiente página. Dos corrupciones owner con fuente/snapshot/digest concordantes demuestran rechazo
de afirmación de 1001 code points o Markdown de 1.048.577 bytes antes de cualquier consulta text_utf8,
con rollback, pool liberado y cero DML. No se presenta como gate integral de capacidad 15P.

### Pruebas y correcciones de fixtures

Primera tanda: 218 unitarios/configuración aprobados. Integración inicial: 113 unitarios y 66 casos
PostgreSQL; todos los 50 HTTP aprobaron y el reader tuvo dos aserciones de fixture a corregir. El
contador por nombre de tabla mezclaba cuatro consultas documentales con ocho preflights: ahora
identifica sólo el JOIN real de datos y acredita cuatro batches/100 filas. El validador editorial
rechazó la ruta temporal con /var enlazado de macOS: el fixture ahora le entrega toRealPath(), sin
relajar ninguna regla del validador. Antes de ejecutar se corrigió también el literal RETIRADA del
fixture. La tanda siguiente aprobó 49 unitarios y los 18 PostgreSQL del reader, incluidos límites.

No hubo fallos de producción ni regresiones transversales. Se conserva la política de gate focal,
ampliado a la configuración/frontera privada, GET previo, seguridad pública, JWT/auth y tenant.
No se ejecutó clean verify; el gate integral continúa reservado a 15Q y a cambios transversales
que lo requieran. No se suman ejecuciones repetidas a los resultados de cierre.

### Gate final y artefactos

Java 21.0.10, Maven 3.9.11, PostgreSQL 16.14 en contenedores efímeros. Gate final aprobado el
2026-09-06T21:51:19-03:00 en 1:48 min: **426 pruebas aprobadas**, 298 Surefire y 128 Failsafe,
sin fallos/errores/omitidas. Son 181 casos en las cinco suites nuevas y 245 de configuración/regresión,
incluidos nueve casos adicionales de ambas fachadas/rutas. Se inspeccionaron los dieciséis XML.

| Suite | Casos | Fallos / errores / omitidos |
| --- | ---: | --- |
| LegalAcceptanceHistoryPageTest | 37 | 0 / 0 / 0 |
| LegalAcceptanceHistoryServiceTest | 12 | 0 / 0 / 0 |
| LegalAcceptanceHistoryControllerTest | 64 | 0 / 0 / 0 |
| LegalPrivateRequirementsHttpConfigurationTest | 35 | 0 / 0 / 0 |
| LegalPrivateRequirementsAuthenticationEntryPointTest | 25 | 0 / 0 / 0 |
| LegalPrivateRequirementsDatabaseConfigurationTest | 45 | 0 / 0 / 0 |
| LegalPrivateRequirementsControllerTest | 42 | 0 / 0 / 0 |
| LegalPublicRequirementsSecurityTest | 16 | 0 / 0 / 0 |
| LegalPublicDocumentSecurityTest | 9 | 0 / 0 / 0 |
| JwtSecurityIntegrationTests | 3 | 0 / 0 / 0 |
| TenantIsolationTests | 5 | 0 / 0 / 0 |
| AuthTests | 5 | 0 / 0 / 0 |
| LegalAcceptanceHistoryReaderIT | 18 | 0 / 0 / 0 |
| LegalAcceptanceHistoryHttpIT | 50 | 0 / 0 / 0 |
| LegalPrivateRequirementsHttpIT | 44 | 0 / 0 / 0 |
| LegalPrivateRequirementsDatabaseContextIT | 16 | 0 / 0 / 0 |

Comando final, sin Maven concurrente sobre target:

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw \
  -Dtest=LegalAcceptanceHistoryPageTest,LegalAcceptanceHistoryServiceTest,LegalAcceptanceHistoryControllerTest,LegalPrivateRequirementsHttpConfigurationTest,LegalPrivateRequirementsAuthenticationEntryPointTest,LegalPrivateRequirementsDatabaseConfigurationTest,LegalPrivateRequirementsControllerTest,LegalPublicRequirementsSecurityTest,LegalPublicDocumentSecurityTest,JwtSecurityIntegrationTests,TenantIsolationTests,AuthTests \
  -Dit.test=LegalAcceptanceHistoryReaderIT,LegalAcceptanceHistoryHttpIT,LegalPrivateRequirementsHttpIT,LegalPrivateRequirementsDatabaseContextIT \
  package failsafe:integration-test failsafe:verify antrun:run@verify-no-secret-properties-in-jar
```

Las veinte fuentes Java nominales permanecieron iguales durante el gate. La auditoría independiente
comparó 25 clases de las once fuentes productivas contra target/classes en ambos JAR; Start-Class
web/CLI correcto, sin clases de tests, application-secret ni entradas duplicadas. Las migraciones
V27/V28/V29 coinciden con sus hashes congelados en fuentes y en ambos artefactos.

Account-read sigue apagado por defecto, sin grants compartidos, push ni activación frontend.
Se preservan los archivos no versionados del frontend y el handoff global continúa cerrado. El
manejo global anterior para rutas/métodos sin mapping permanece documentado en 15D; no se amplía
este corte para modificarlo. Commit: `feat(legal): consulta aceptaciones propias`.
Sigue **15G — comando canónico, HMAC y coordinación idempotente**, dado que 15F ya está cerrado.


## Ejecución 15G1 — 2026-09-06

Cerrado desde `9c61dd7` con rama y árbol limpios verificados; frontend `7545201` y sus dos rutas
no versionadas preservados. Diez archivos nominales: cuatro clases productivas nuevas, cuatro
suites nuevas y dos documentos. La subdivisión 15G1/15G2 quedó registrada antes de editar código.
Ninguna fuente existente, migración, dependencia, configuración, controller o DTO HTTP cambió.

Comando inmutable, listas ordenadas por UUID unsigned y contenido completo de desempate, con
multiplicidad conservada. El límite documental se comprueba antes de copiar la lista; el límite
previo de email evita recorrer una entrada tipada excesiva. Ninguna validación de forma inventa
consentimiento, omite duplicados o adelanta confirmado=false/listas vacías a la semántica futura.
La revisión mantuvo el scope contractual de usuario: taller/rol/tokenVersion deben revalidarse
contra la identidad durable al integrar los escritores, sin crear otra tupla para una misma clave.

HMAC-SHA-256 en tres dominios v1 separados; proyección tipada RFC 8785 por streaming, sin JSON
completo ni hash auxiliar de contraseña. El buffer temporal se borra al terminar o fallar.
El keyring es un snapshot inmutable de todas las versiones retenidas; no elimina candidatos al
cambiar la versión activa ni comparte Mac mutable entre llamadas. Los secretos retenidos no se
exponen, las copias de derivación se borran y las excepciones no retienen entradas sensibles.
TTL 25 h por defecto técnico, mínimo 24 h y aritmética de vencimiento comprobada antes del futuro DML.

### Gate focal

Primera compilación: producción aprobó; testCompile detectó que ThrowableAssertAlternative no
admite hasNoCause/hasMessage en la versión local de AssertJ. Se corrigió sólo el test del keyring
para usar assertThatThrownBy con comprobación explícita de IllegalArgumentException, mismo mensaje
y ausencia de causa. No hubo fallo de producción ni regresión transversal; no se ejecutó clean verify.

Gate final aprobado con Java 21.0.10 y Maven 3.9.11 el **2026-09-06T22:39:56-03:00**, en 16.075 s:
**294 pruebas**, 136 nuevas y 158 regresiones, cero fallos/errores/omitidas. Siete XML inspeccionados,
sin sumar reportes viejos ni ejecuciones repetidas. Todas las ocho fuentes Java conservaron sus
hashes durante el gate.

| Suite | Casos | Fallos / errores / omitidos |
| --- | ---: | --- |
| LegalAcceptanceCommandTest | 6 | 0 / 0 / 0 |
| LegalAcceptanceCommandValidatorTest | 56 | 0 / 0 / 0 |
| LegalIdempotencyFingerprintTest | 34 | 0 / 0 / 0 |
| LegalIdempotencyKeyringTest | 40 | 0 / 0 / 0 |
| Rfc8785CanonicalizerTest | 41 | 0 / 0 / 0 |
| LegalAuthenticatedRequirementsTest | 46 | 0 / 0 / 0 |
| LegalRequirementSatisfactionEvaluatorTest | 71 | 0 / 0 / 0 |

Comando final, sin Maven concurrente sobre target:

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw \
  -Dtest=LegalAcceptanceCommandTest,LegalAcceptanceCommandValidatorTest,LegalIdempotencyFingerprintTest,LegalIdempotencyKeyringTest,Rfc8785CanonicalizerTest,LegalAuthenticatedRequirementsTest,LegalRequirementSatisfactionEvaluatorTest \
  package antrun:run@verify-no-secret-properties-in-jar
```

Evidencia relevante: vector fijo calculado independientemente con Python hmac/json para los tres
HMAC; comparación con JsonCanonicalizer y Mac independientes para las categorías de escapes/control,
astral, U+2028/U+2029, Unicode descompuesto y teléfono null. Un comando completo con
2048 actos × 16 documentos atraviesa múltiples buffers y coincide con el JCS independiente.
Permutar duplicados conserva huella; quitar una ocurrencia o cambiar cualquier campo de negocio,
incluida contraseña, la cambia. UserId superior a 2^53 y Long.MAX_VALUE mantienen representación
exacta. Cuarenta derivaciones concurrentes sobre tres versiones conservan todos los candidatos.
Límites/Base64 no canónico/versiones/TTL/overflow inválidos fallan con diagnósticos sanitizados.

No se ejecuta PostgreSQL en este corte puro; locks, espera acumulada, lectura de ambos ledgers,
replay después de REPLACE/RETIRE, expiración no purgada, reserva/rollback y acreditación de identidad
durable siguen siendo el gate obligatorio de **15G2**. 15G completo continúa abierto. No hay endpoint
HTTP de escritura, beans ni flags activados, grants, push o cambios de frontend. Commit de 15G1:
`feat(legal): canonicaliza comandos de aceptacion`.


Auditoría final de artefactos aprobada: las diez clases compiladas de las cuatro fuentes nuevas,
incluidas las internas, coinciden byte a byte con target/classes en ambos JAR. Start-Class web/CLI
correctos, sin clases de tests, application-secret ni entradas duplicadas. V27/V28/V29 conservan
sus hashes congelados en fuentes y en ambos artefactos. El gate de propiedades secretas pasó.
