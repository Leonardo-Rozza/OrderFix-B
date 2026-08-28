# Fase 2.3C — Promoción, retiro y readiness editorial del manifiesto legal

Fecha: 2026-08-27

Estado: diseño aprobado; implementación pendiente

Continuidad:

- FRONTEND_INTEGRATION.md, sección 4.1.a;
- docs/plans/2026-08-23-legal-api-contract-v1-design.md;
- docs/plans/2026-08-23-legal-persistence-append-only-design.md;
- docs/plans/2026-08-25-legal-manifest-import-design.md;
- docs/plans/2026-08-25-legal-manifest-import-closure.md;
- ../mvgr-reparaciones-frontend/docs/plans/2026-08-23-lanzamiento-publico-confianza-cuenta-plan.md.

## Decisión de producto

La Fase 2.3C completa el ciclo editorial interno que comenzó con la persistencia V27, continuó con
el dry-run de 2.3A y llegó al sello transaccional de 2.3B. Comprende la primera promoción completa,
los reemplazos posteriores, los retiros explícitos y una evaluación agregada de readiness editorial.

El usuario aprobó:

- cubrir el ciclo completo dentro de 2.3C, dividido en cortes pequeños y commits atómicos;
- mantener la operación fuera de la aplicación web y de los roles ADMIN/USER de los talleres;
- conservar sin cambios el manifiesto v1 y empezar sin una migración V28;
- reutilizar el bundle validado para la primera promoción;
- usar un plan editorial inmutable y separado para reemplazos y retiros;
- exigir decisiones explícitas y no inferir retiros por ausencias en un manifiesto;
- separar readiness real, planificación sin escritura y aplicación mutante;
- ejecutar cada mutación completa bajo un único lock y una única transacción;
- distinguir el éxito operativo del estado editorial resultante;
- permitir un retiro fail-closed que deje NOT_READY sólo con una autorización expresa;
- documentar, probar y commitear cada corte, sin push.

2.3C sigue siendo una operación de plataforma. No crea una pantalla administrativa, un endpoint
HTTP, una API consumible por el frontend ni una acción disponible para titulares o empleados.

## Objetivo

Transformar un grafo legal exacto, importado y SELLADO, en una proyección editorial vigente,
coherente y auditable mediante las tablas de comando y triggers V27. El resultado debe poder:

1. acreditar el estado real de una publicación sin mutarlo;
2. simular de forma determinista si una promoción, reemplazo o retiro puede aplicarse;
3. promover por primera vez una publicación completa;
4. reemplazar versiones con relaciones uno a uno, divisiones, fusiones o reutilización explícita;
5. retirar versiones sin reemplazo sólo mediante una decisión explícita y trazable;
6. reconciliar repeticiones exactas y no afirmar un commit que PostgreSQL no pudo confirmar.

La fase termina cuando la herramienta interna puede operar todo ese ciclo sobre PostgreSQL 16 y V27
con credenciales editoriales mínimas, evidencia de concurrencia, capacidad y procesos JVM reales.

## Fronteras semánticas

### Construcción y vigencia no son lo mismo

- SELLADO pertenece a legal_publicaciones y prueba que el grafo importado quedó completo e
  inmutable.
- BORRADOR, PUBLICADA, VIGENTE, REEMPLAZADA y RETIRADA pertenecen a cada versión documental o de
  requisito.
- PUBLICADA significa que una versión quedó editorialmente fijada, pero todavía no está activa.
- VIGENTE significa que una versión participa de las proyecciones actuales.
- REEMPLAZADA y RETIRADA son estados terminales.

Una publicación SELLADO no está automáticamente vigente. Del mismo modo, el replay de importación
de 2.3B continúa siendo ALREADY_IMPORTED aunque las versiones hayan avanzado después en el ciclo
editorial.

### Readiness editorial

Readiness editorial es una comprobación interna y no mutante de consistencia del estado legal
persistido. No equivale a:

- /actuator/health;
- readiness HTTP o readiness remoto del despliegue;
- ETag, catálogo o documentos públicos;
- aprobación jurídica o contable;
- una decisión global de go/no-go;
- disponibilidad de Mercado Pago;
- deployment, build público o habilitación de enforcement;
- disponibilidad de BACKEND-HANDOFF 1.

La herramienta puede acreditar que los datos son editorialmente coherentes. No acredita que los
textos actuales hayan sido aprobados profesionalmente ni autoriza promover los borradores reales.

## Alternativas evaluadas

### A. Estrategia híbrida y progresiva — elegida

La primera promoción consume el mismo bundle validado de 2.3B y exige el publicationId y SHA-256
canónico exactos. Los reemplazos y retiros posteriores consumen además un plan editorial inmutable
que identifica versiones, contextos, motivos y relaciones.

Ventajas:

- no cambia el schema del manifiesto v1;
- permite acreditar primero el camino mínimo de promoción;
- evita agregar UUID, motivos o mapas editoriales a un artefacto cuyo objetivo es describir
  contenido;
- conserva decisiones destructivas en un artefacto explícito y separado;
- habilita cortes pequeños sin perder uniformidad transaccional.

### B. Plan editorial para toda operación desde el primer corte — descartada

Un plan único para primera promoción, reemplazo y retiro ofrecería una auditoría uniforme desde el
inicio, pero adelantaría parser, canonicalización, permisos y semántica de operaciones que la primera
promoción no necesita. Aumenta la superficie antes de comprobar el readiness y la transición mínima.

### C. Inferir reemplazos y retiros comparando manifiestos — descartada

Que una key o un contexto no aparezca en una publicación nueva no demuestra intención de retiro.
Inferir esa intención podría eliminar vigencia por un error editorial o por una publicación
incompleta. También contradice la frontera fijada en 2.3A: nunca se deducen operaciones destructivas
por ausencia.

## Alcance

Incluye:

- evaluador read-only de readiness para una publicación exacta;
- reporte determinista de READY, NOT_READY o ERROR;
- planificador read-only de promoción, reemplazo y retiro;
- primera promoción completa e idempotente;
- reemplazo explícito, incluido split, merge y reutilización;
- retiro explícito con motivo y aceptación de hueco cuando corresponda;
- mismo advisory lock cooperativo de import y dry-run;
- transacciones REQUIRES_NEW, READ_COMMITTED y constraints inmediatas para las mutaciones;
- reconciliación de replay y commit indeterminado;
- reporte editorial nuevo sin modificar reportes anteriores;
- contexto CLI no web y rol PostgreSQL editorial mínimo;
- pruebas unitarias, PostgreSQL, concurrencia, procesos y capacidad;
- runbook y coordinación documental entre repositorios.

No incluye:

- migrar V27 o implementar de forma anticipada V28;
- modificar publication-manifest.schema.json o el contrato v1;
- scheduler o promoción automática al llegar vigenteDesde;
- endpoint, controller, DTO HTTP, catálogo, documentos remotos o ETag;
- aceptación, registro atómico, idempotencia HTTP, 409, 428 o enforcement;
- documentSetRevision, catálogo histórico o revisiones derivadas de respuestas HTTP;
- integración funcional del frontend;
- permisos para ADMIN o USER de un taller;
- deploy, readiness remoto o build público;
- importación o promoción de borradores legales reales;
- aprobación profesional de textos, precios, fiscalidad o privacidad.

BACKEND-HANDOFF 1 permanece cerrado al terminar esta fase.

## Invariantes congeladas

- Base mínima: PostgreSQL 16 con V27 exacta y una publicación SELLADO.
- El manifiesto v1 permanece byte-compatible en ambos repositorios.
- La primera promoción y todo reemplazo apuntan a una publicación completa, nunca a un scope
  aislado.
- Las transiciones permitidas son BORRADOR → PUBLICADA → VIGENTE → REEMPLAZADA | RETIRADA.
- REEMPLAZADA y RETIRADA son terminales.
- vigenteDesde se compara con la hora de PostgreSQL; no se puede activar anticipadamente.
- Los ordinales de linaje publicados siguen siendo monotónicos.
- Los slots documentales actuales no pueden solaparse.
- Los punteros actuales de requisitos deben apuntar de forma homogénea a snapshots sellados de la
  publicación objetivo.
- Un reemplazo multicontexto enumera cobertura exacta, completa y sin solapamientos.
- Un retiro siempre enumera versiones y motivo no vacío.
- Toda mutación usa el mismo advisory transaction lock editorial que import y dry-run.
- Todo lock de filas se adquiere en orden UUID determinista.
- Toda mutación usa exactamente READ_COMMITTED y fuerza las constraints diferidas antes del commit.
- El readiness posterior se recalcula dentro de la misma transacción de la mutación.
- El rol importador de 2.3B nunca recibe permisos editoriales.
- No se usa un flag force, un prompt interactivo ni inferencia por ausencia.
- Un cambio de V27 requiere un diseño V28 separado y aprobación antes de implementarse.

## Arquitectura

### Bundle validado como entrada de contenido

Los comandos reciben exclusivamente el ValidatedRelease producido por LegalManifestValidator. No
aparece un segundo parser de manifiestos ni un DTO construible por caminos más permisivos.

El operador confirma:

- la ruta explícita del manifiesto;
- el publicationId exacto;
- el SHA-256 JCS exacto.

Una diferencia bloquea la operación antes de abrir PostgreSQL. El SHA confirmado identifica el
contenido, pero no reemplaza autorización ni aprobación profesional.

### Tres responsabilidades separadas

El dominio expone tres responsabilidades sin compartir una bandera mutante:

1. Readiness: observa el estado real actual y nunca escribe.
2. Plan: calcula el delta y las precondiciones sin escribir.
3. Apply: vuelve a planificar bajo lock y ejecuta la mutación completa.

Un PASS previo del plan no autoriza por sí mismo un apply posterior. Apply repite todas las
precondiciones dentro de su propia transacción para eliminar la ventana TOCTOU.

ReadinessCore y PlannerCore operan sobre la sesión JDBC entregada por el caller y nunca abren una
transacción o gate propios. Los comandos read-only los envuelven en su gate. Apply los invoca
directamente dentro de la transacción mutante que ya posee el advisory lock. Así se evita suspender
la transacción exterior y esperar desde una segunda conexión el lock que ella misma retiene.

Los nombres finales de los subcomandos quedan ligados a estas responsabilidades y se congelarán en
el corte que incorpore cada superficie CLI. No se agrega un comando genérico capaz de omitir el tipo
de operación ni una opción que convierta un plan en escritura mediante un booleano.

### Gate editorial compartido

Un coordinador editorial reutiliza el protocolo acreditado en 2.3B:

1. abrir una transacción nueva;
2. fijar presupuestos de sentencia y locks;
3. acreditar la superficie editorial de V27 y, en operaciones editoriales, privilegios;
4. adquirir el advisory transaction lock ordenfix:legal-publicaciones:sello:v1;
5. reducir el lock timeout para los locks restantes;
6. ejecutar el callback del evaluador, planificador o servicio mutante;
7. en las mutaciones, forzar constraints y comprobar postcondiciones antes de permitir commit.

Readiness y plan también toman el advisory lock dentro de una transacción read-only para obtener una
vista coherente respecto de todos los writers cooperativos. Las mutaciones usan REQUIRES_NEW,
READ_COMMITTED y readOnly=false. No participan en una transacción exterior.

Los presupuestos iniciales serán los ya acreditados por 2.3B: 75 segundos totales, hasta 30 segundos
para el advisory lock y sentencias, y 5 segundos para locks del grafo una vez adquirido. Las pruebas
de capacidad pueden exigir optimización, pero no una relajación silenciosa.

### Evaluador de readiness

El evaluador recibe el bundle exacto y la publicación objetivo. Para devolver READY debe acreditar:

- publicación existente y SELLADO;
- cabecera, canónico y SHA exactos;
- marcadores de revisión legal y contable aprobados en el manifiesto validado;
- todas las versiones objetivo en VIGENTE;
- vigenteDesde alcanzado;
- membresía documental y de requisitos exacta;
- slots actuales exactos, completos y sin solapamiento;
- slots ligados a la publicación objetivo, incluso para documentos reutilizados;
- ausencia global de slots actuales adicionales o ligados a otra publicación;
- snapshots sellados del target exactos e inmutables;
- punteros actuales homogéneos y ligados a esos snapshots de la publicación objetivo;
- ausencia global de punteros actuales adicionales, faltantes o ligados a otra publicación;
- cobertura completa de todos los contextos, locales y audiencias del release;
- al menos un requisito requerido en REGISTRO;
- requiredSetRevision recalculado y exacto para cada scope;
- ausencia de versiones adicionales de la publicación en un estado incompatible;
- constraints V27 satisfechas.

El evaluador comprueba el target completo. No se limita a ejecutar validadores globales V27, porque
V27 puede permitir una versión VIGENTE sin que sea la proyección actual esperada para ese release.
Los marcadores APPROVED demuestran lo registrado en el bundle; no autentican por sí solos identidad,
firma, competencia ni suficiencia del profesional que realizó la revisión.

documentSetRevision queda fuera de 2.3C. Esa revisión deriva del catálogo HTTP completo, incluido su
histórico y orden contractual, y se implementará junto con esa API. Readiness puede emitir un
editorialStateFingerprint interno del estado observado, sin presentarlo como contrato público. La
proyección versionada del fingerprint incluye identidad y SHA de la publicación actual, todos los
slots y punteros actuales, estados y metadata terminal de sus versiones, lotes relacionados y
versiones adicionales que podrían interferir. Excluye nuevas publicaciones SELLADO que continúan
íntegramente en BORRADOR. Se canonicaliza con RFC 8785 y se acredita con un vector golden.

El resultado observado es:

- READY: el target exacto es la proyección vigente completa;
- NOT_READY: la lectura fue válida pero una o más condiciones editoriales no se cumplen;
- ERROR: configuración, schema, privilegios, conexión o lectura impidieron determinar el estado.

Readiness no simula qué ocurriría después de una promoción. Esa responsabilidad pertenece al plan.

### Planificador determinista

El planificador:

- toma el advisory lock cooperativo y usa SELECT simples, sin row locks incompatibles con
  readOnly=true;
- clasifica la operación como primera promoción, reemplazo o retiro;
- compara estados y digests esperados;
- ordena todas las versiones por UUID;
- calcula transiciones, slots, punteros a snapshots sellados y lotes necesarios;
- valida cobertura de contextos y revisiones;
- calcula el readiness posterior esperado;
- devuelve APPLICABLE o BLOCKED sin insertar transiciones ni modificar proyecciones.

El apply no consume ciegamente un resultado de plan almacenado. Reconstruye el mismo plan desde las
entradas inmutables y el estado bloqueado actual. Una diferencia de estado produce
CURRENT_STATE_MISMATCH y cero escrituras confirmadas.

### Plan editorial inmutable

La primera promoción no requiere un plan externo adicional. Los reemplazos y retiros sí consumen un
artefacto editorial independiente, estricto y canonicalizable.

Cada plan representa una única transición editorial atómica y contiene como mínimo:

- schemaVersion del plan;
- operationId estable;
- operationType: REPLACE o RETIRE;
- expectedCurrentPublicationId y expectedCurrentManifestSha256;
- expectedEditorialStateFingerprint obtenido de una lectura previa;
- targetPublicationId y targetManifestSha256;
- versiones documentales afectadas por UUID y sha256;
- versiones de requisito afectadas por UUID y afirmacionSha256;
- contextos afectados;
- adiciones documentales y de requisitos explícitas;
- reutilizaciones documentales y de requisitos explícitas;
- lotes documentales explícitos de predecesores y sucesores;
- relaciones explícitas de requisitos anteriores y sucesores;
- salidas sin sucesor explícitas, con motivo no vacío;
- expectedReadinessAfter;
- acknowledgeFailClosedGap=true cuando expectedReadinessAfter es NOT_READY.

REPLACE representa el cutover completo desde una publicación actual hacia otra. Puede combinar en
una sola transacción adiciones, reutilizaciones, reemplazos y salidas sin sucesor, siempre
enumerados. RETIRE representa una retirada de emergencia sobre la publicación actual sin un nuevo
target vigente; en ese caso targetPublicationId y targetManifestSha256 repiten la identidad current
esperada. REPLACE exige expectedReadinessAfter=READY. RETIRE exige
expectedReadinessAfter=NOT_READY y acknowledgeFailClosedGap=true. Ningún delta destructivo se deduce
comparando manifests.

El plan no contiene documentos legales, secretos, credenciales ni datos personales. Se
canonicaliza y recibe su propio SHA-256. Apply exige que el operationId y el SHA confirmados
coincidan con el artefacto antes de abrir PostgreSQL.

V27 no posee un ledger editorial donde guardar operationId o el SHA del plan. Por eso esos campos
identifican el artefacto operativo y su confirmación, pero no prueban históricamente qué plan produjo
un estado existente. La unicidad global de operationId se controla en el repositorio editorial y el
runbook. El replay en PostgreSQL se acredita contra el postestado completo derivado del plan, no
contra un hash inexistente en la base.

Una ausencia en el manifiesto o en el plan nunca se interpreta como retiro.

La forma JSON exacta y sus vectores golden se congelarán antes de implementar el parser, sin
modificar el schema del manifiesto de publicación. El parser comparte las defensas del bundle:

- archivo regular explícito y sin seguir symlinks;
- máximo 1 MiB;
- UTF-8 válido, NFC, saltos LF y sin BOM;
- JSON estricto, sin claves desconocidas ni duplicadas;
- operationId UUID, IDs normalizados y arrays sin duplicados;
- hasta 128 documentos, 256 requisitos y 128 lotes;
- motivo recortado, no vacío y de hasta 1000 caracteres;
- cero rutas o referencias a archivos adicionales dentro del plan.

### Primera promoción

La primera promoción sólo es aplicable cuando no existen slots documentales ni punteros actuales de
requisitos y no existe ninguna transición editorial previa en V27. Pueden existir otras
publicaciones SELLADO todavía inactivas. Esta condición impide reutilizar PROMOTE después de un
retiro total; todo ciclo posterior debe usar un plan REPLACE o RETIRE explícito.

El flujo transaccional es:

1. validar bundle y confirmaciones antes de abrir PostgreSQL;
2. abrir la transacción editorial y tomar el advisory lock;
3. acreditar schema, rol y publicación SELLADO exacta;
4. derivar el postestado objetivo y compararlo primero con el estado actual;
5. devolver ALREADY_APPLIED sin escribir si el postestado ya es exacto;
6. sólo si no está aplicado, acreditar el estado fuente inicial: cero transiciones editoriales, cero
   lotes sellados, cero slots, cero punteros actuales y versiones target en BORRADOR;
7. leer una sola vez transaction_timestamp de PostgreSQL y usar ese instante para validar
   vigenteDesde, fechar todas las transiciones y construir el receipt, exactamente igual que V27;
8. construir el plan completo y bloquear versiones en orden determinista;
9. insertar las transiciones V27 BORRADOR → PUBLICADA;
10. insertar las transiciones V27 PUBLICADA → VIGENTE;
11. crear slots documentales actuales exactos;
12. insertar punteros actuales hacia los snapshots inmutables que 2.3B ya importó y selló;
13. ejecutar SET CONSTRAINTS ALL IMMEDIATE;
14. recalcular readiness real dentro de la misma sesión y transacción;
15. permitir commit sólo si el target queda READY.

No se actualizan estados directamente. El servicio inserta en legal_documento_transiciones y
legal_requisito_transiciones; sus triggers V27 materializan los estados.

Una repetición exacta compara el estado completo. Si ya coincide, no escribe ni avanza secuencias y
devuelve ALREADY_APPLIED para PROMOTE. Un estado parcial o un target vigente diferente bloquea; no se
completa ni corrige automáticamente.

### Reemplazo

Un reemplazo consume un nuevo bundle SELLADO y un plan editorial REPLACE ligado a su ID y SHA. Es
un cutover completo, no sólo la creación de lotes V27.

El plan enumera lotes con conjuntos no vacíos de predecesores, sucesores y contextos. La cobertura
debe ser exacta y disjunta. Se admiten:

- uno a uno;
- una versión anterior hacia varias nuevas;
- varias anteriores hacia una nueva;
- documentos exactos reutilizados entre publicaciones;
- documentos o contextos nuevos sin predecesor;
- salidas sin sucesor, explícitamente RETIRADA con motivo.

Los requisitos tienen un protocolo separado porque V27 no posee lotes de reemplazo para ellos. El
plan enumera de forma explícita requisitos anteriores y sucesores compatibles por línea, scope,
audiencias y afirmacionSha256. Requisitos nuevos avanzan BORRADOR → PUBLICADA → VIGENTE; requisitos
exactos reutilizados permanecen VIGENTE; requisitos con sucesor avanzan VIGENTE → REEMPLAZADA; y
requisitos sin sucesor avanzan VIGENTE → RETIRADA con motivo. Todas las audiencias de cada requisito
se promocionan en el mismo cambio de punteros.

Dentro de una única transacción:

1. se acredita el target y el plan;
2. se deriva el postestado y se devuelve ALREADY_APPLIED antes de exigir el estado fuente si ya
   coincide exactamente;
3. si no está aplicado, se verifica el fingerprint fuente completo y se bloquean versiones, slots y
   punteros actuales en orden determinista;
4. se publican las versiones documentales y de requisito nuevas;
5. los documentos agregados sin predecesor avanzan directamente a VIGENTE y reciben sus slots;
6. se construyen los lotes documentales y se sellan; el trigger V27 del sello es quien activa las
   sucesoras, reemplaza las predecesoras y reconstruye sus slots, sin DML duplicado del servicio;
7. para documentos sin sucesor se eliminan primero sus slots y después se insertan sus transiciones
   a RETIRADA; también se insertan explícitamente las transiciones de requisitos y retiradas sin
   sucesor;
8. se vuelven a vincular los slots de documentos reutilizados al nuevo publicationId mediante
   delete más insert atómicos, aunque la versión siga VIGENTE;
9. se reemplazan los punteros actuales para que señalen exclusivamente a los snapshots sellados del
   target; nunca se crean ni modifican legal_requisito_conjuntos después del sello;
10. se fuerzan constraints;
11. se recalcula readiness;
12. se permite commit sólo si el nuevo target queda READY.

La reasignación de slots reutilizados es obligatoria. Conservar un slot ligado a la publicación
anterior produciría un snapshot heterogéneo aunque el documento fuese byte a byte idéntico.

### Retiro

Un retiro consume un plan RETIRE ligado a la publicación actualmente esperada. El plan enumera cada
versión documental o de requisito, su digest específico, contextos, motivo y resultado esperado.

Dentro de una única transacción:

1. se deriva el postestado y se devuelve ALREADY_APPLIED si ya coincide exactamente;
2. si no está aplicado, se acredita el fingerprint fuente completo;
3. se bloquean las versiones y proyecciones afectadas;
4. se eliminan punteros o slots actuales que referencian esas versiones;
5. se ejecutan las transiciones V27 a RETIRADA;
6. se fuerzan constraints;
7. se calcula el readiness posterior.

Un retiro cubierto por reemplazo usa el flujo REPLACE y debe terminar READY. Un retiro puro puede
terminar NOT_READY sólo cuando el plan fija expectedReadinessAfter=NOT_READY,
acknowledgeFailClosedGap=true y se suministra la confirmación exacta del plan. No es un force
genérico: ambos campos forman parte del artefacto inmutable y hasheado. La respuesta separa:

- resultado operativo APPLIED;
- readiness posterior NOT_READY.

Esta combinación representa un cierre fail-closed deliberado. No es una degradación silenciosa ni
un error que el servicio deba reparar.

### Fecha de vigencia

2.3C no incorpora scheduler. Un release futuro puede validarse, importarse, sellarse y planificarse,
pero apply se bloquea con EFFECTIVE_DATE_NOT_REACHED hasta que la hora de PostgreSQL alcance todas
las fechas necesarias.

El operador vuelve a ejecutar exactamente el mismo comando al llegar la fecha. No hay espera activa,
job oculto ni transición automática.

## Contexto operativo y privilegios

Las operaciones editoriales se ejecutan en el mismo jar legal aislado, pero en un contexto Spring no
web separado de import. No activa:

- servidor HTTP, MVC o servlet;
- JPA/Hibernate;
- Flyway;
- DataLoader o migraciones legacy;
- schedulers, runners o integraciones Mercado Pago;
- datasource de la aplicación principal.

La credencial editorial usa variables exclusivas:

- ORDENFIX_LEGAL_EDITOR_DB_URL;
- ORDENFIX_LEGAL_EDITOR_DB_USERNAME;
- ORDENFIX_LEGAL_EDITOR_DB_PASSWORD;
- ORDENFIX_LEGAL_EDITOR_DB_DRIVER_CLASS_NAME, opcional;
- un habilitador editorial explícito que se congelará con la CLI.

El password nunca aparece en argumentos, system properties, reportes o logs. El launcher elimina o
controla JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS y _JAVA_OPTIONS antes de iniciar la JVM.

Se crean LegalEditorialSchemaVerifier y LegalEditorialPrivilegeVerifier separados. El primero
fingerprinta toda la superficie V27 alcanzada por 2.3C: versiones, transiciones, slots, punteros
actuales, lotes, membresías, funciones, triggers, constraints y secuencias. El inventario restringido
de LegalV27ImportSchemaVerifier permanece exacto y no se amplía. Tampoco se amplían
LegalImportPrivilegeVerifier ni el rol importador.

El rol editorial:

- es no owner, NOINHERIT y sin privilegios administrativos;
- puede leer el grafo, transiciones y proyecciones V27 requeridas;
- puede insertar las transiciones, slots, punteros actuales y lotes estrictamente necesarios;
- puede actualizar únicamente las columnas de construcción y sello del lote V27;
- recibe los UPDATE de columnas que el call graph SECURITY INVOKER necesita para materializar
  estados: estado, estado_cambiado_en, ultimo_motivo y reemplazo_lote_id documentales; estado,
  estado_cambiado_en y ultimo_motivo de requisitos;
- recibe UPDATE(id) técnico sobre publicaciones, líneas y versiones para los SELECT FOR
  UPDATE/SHARE requeridos;
- puede borrar únicamente proyecciones actuales que el protocolo de reemplazo/retiro deba
  reconstruir;
- no puede insertar, actualizar ni borrar snapshots sellados en legal_requisito_conjuntos o
  legal_requisito_conjunto_miembros;
- puede ejecutar sólo la allowlist V27 necesaria;
- no puede ejecutar DDL, Flyway, TRUNCATE ni escribir tablas de la aplicación;
- no puede registrar aceptaciones, idempotencia HTTP o evidencias de usuarios;
- no hereda el owner, el rol de aplicación ni el importador.

Esos grants de UPDATE no autorizan updates directos: legal_version_update_interno_guard y los
guards de las líneas los rechazan fuera del call graph de triggers. El verifier exige las columnas
positivas exactas, la ausencia de UPDATE de tabla y pruebas negativas de UPDATE real y no-op.

Precisión incorporada durante el plan de implementación: legal_publicaciones también requiere
UPDATE(id) porque legal_bloquear_publicacion_sellada(uuid), parte del call graph SECURITY INVOKER de
V27, usa SELECT FOR SHARE. Es un grant técnico de columna, no UPDATE de tabla ni autorización para
cambiar una publicación; el verifier acredita el call graph positivo y que UPDATE directo y no-op
continúan rechazados.

El inventario exacto de tablas, columnas, secuencias, funciones y grants se congela como fixture en
el corte de seguridad. Un permiso faltante o adicional bloquea antes de mutar.

V27 protege invariantes estructurales, pero no puede exigir las confirmaciones SHA ni validar el plan
editorial por sí sola. Por ello el rol mínimo todavía posee DML directo suficiente para causar una
transición válida fuera del CLI si sus credenciales fueran utilizadas indebidamente. Mínimo
privilegio significa aquí mínimo DML estructural, no autorización semántica completa.

La cuenta editorial permanece deshabilitada para la aplicación, restringida por red al job
operativo, disponible sólo durante la ventana editorial y con secreto rotado o revocado después de
usarse. CLI, confirmaciones, repositorio inmutable y control del job forman la barrera semántica. Si
se necesitara que PostgreSQL verificara también el plan, haría falta una función segura y estado
durable nuevos, diseñados en V28.

V27 no se modifica para guardar la identidad humana del operador. El runbook exige conservar de
forma externa el job u operador, IDs y hashes confirmados, receipt y logs sanitizados.

## Contrato de resultados

Los reportes v1 de validate y dry-run, y el reporte v2 de import, permanecen byte-compatible. Los
nuevos comandos usan un envelope editorial versionado independientemente.

El resultado de aplicación y el readiness son ejes distintos:

| Resultado | persisted | Semántica |
|---|---:|---|
| APPLIED | true | La mutación quedó confirmada |
| ALREADY_APPLIED | true | El estado final exacto ya existía |
| BLOCKED | false | Una precondición editorial impidió aplicar |
| ERROR | false | Falló y el rollback quedó confirmado |
| UNKNOWN | null | No pudo acreditarse commit ni rollback |

operationType identifica PROMOTE, REPLACE o RETIRE. Las combinaciones permitidas son:

| Comando y caso | status | persisted | Resultado | Readiness | Exit |
|---|---|---:|---|---|---:|
| readiness listo | PASS | false | null | READY | 0 |
| readiness incompleto | BLOCKED | false | null | NOT_READY | 2 |
| readiness no determinable | ERROR | false | null | ERROR | 3 |
| plan aplicable | PASS | false | APPLICABLE | expectedReadinessAfter | 0 |
| plan bloqueado | BLOCKED | false | BLOCKED | null | 2 |
| plan con fallo operativo | ERROR | false | ERROR | null | 3 |
| apply confirmado | PASS | true | APPLIED | readinessAfter | 0 |
| apply ya existente | PASS | true | ALREADY_APPLIED | readinessAfter | 0 |
| apply bloqueado | BLOCKED | false | BLOCKED | null | 2 |
| apply con rollback conocido | ERROR | false | ERROR | null | 3 |
| apply indeterminado | ERROR | null | UNKNOWN | null | 3 |

Para PROMOTE y REPLACE, expectedReadinessAfter y readinessAfter sólo pueden ser READY. Para RETIRE
pueden ser NOT_READY únicamente con el acknowledgement fail-closed incluido en el plan. Readiness y
plan siempre exponen persisted=false porque no mutan.

### Reporte seguro

stdout contiene un único JSON canónico. En ejecución normal stderr queda vacío. Ante un fallo puede
contener únicamente un resumen constante, acotado y allowlisteado; no contiene valores dinámicos.
Las prohibiciones de redacción aplican a ambos canales, no sólo al JSON.

El reporte incluye:

- versión y comando;
- status superior PASS, BLOCKED o ERROR;
- persisted;
- operationType y outcome cuando corresponda;
- publicationId, UUID y SHA confirmados cuando son conocidos;
- operationId y SHA del plan cuando corresponda;
- cantidades de versiones, transiciones, slots, punteros actuales y lotes;
- timestamps releídos desde PostgreSQL;
- readiness y códigos de sus hallazgos;
- issues limitados y omittedIssueCount.

Ningún canal incluye:

- contenido Markdown o afirmaciones legales;
- rutas absolutas;
- argumentos completos;
- SQL o nombres de constraints no allowlisteados;
- stack traces;
- usuarios o passwords DB;
- secretos, tokens o datos personales.

Un stdout inexistente, truncado o inválido deja el resultado operativo indeterminado, pero no
constituye por sí solo un envelope UNKNOWN. El operador aplica el protocolo de reconciliación y no
infiere éxito ni rollback.

En UNKNOWN se conserva la identidad del bundle recibida como input cuando fue validada, pero todos
los UUID, timestamps, deltas y receipts derivados de persistencia son null. El launcher elimina los
canales de opciones JVM conocidos antes de main; cualquier mensaje emitido por una JVM comprometida
antes de main queda fuera del envelope y se trata como salida no confiable.

### Exit codes

- 0: READY observado, plan APPLICABLE, APPLIED o ALREADY_APPLIED válido;
- 2: NOT_READY observado, plan BLOCKED o precondición editorial bloqueante;
- 3: configuración, schema, privilegios, conexión, rollback conocido o resultado UNKNOWN.

Un retiro APPLIED más NOT_READY autorizado devuelve 0 porque la operación solicitada quedó
confirmada. El readiness separado conserva la señal fail-closed.

## Fallos, replay y commit indeterminado

Los códigos estables incluyen como mínimo:

- PUBLICATION_NOT_SEALED;
- PUBLICATION_CONTENT_MISMATCH;
- EFFECTIVE_DATE_NOT_REACHED;
- CURRENT_STATE_MISMATCH;
- SOURCE_FINGERPRINT_MISMATCH;
- INITIAL_PROJECTION_ALREADY_EXISTS;
- SCOPE_COVERAGE_INCOMPLETE;
- REPLACEMENT_MAPPING_INVALID;
- RETIREMENT_REASON_REQUIRED;
- EXPECTED_READINESS_MISMATCH;
- FAIL_CLOSED_GAP_NOT_ACKNOWLEDGED;
- REVISION_MISMATCH;
- CONCURRENT_OPERATION;
- ROLE_PRIVILEGE_DRIFT;
- SCHEMA_DRIFT;
- POSTCONDITION_NOT_READY;
- COMMIT_OUTCOME_UNKNOWN.

BLOCKED representa una condición de dominio esperable. ERROR representa una incapacidad operativa
con rollback acreditado. UNKNOWN sólo aparece cuando la frontera transaccional no puede conocer la
finalización.

Si la conexión falla después de entrar en el camino de commit:

1. se abre una nueva conexión aislada;
2. se toma el mismo advisory lock cuando PostgreSQL vuelve a estar disponible;
3. se compara el estado completo contra el postestado derivado del bundle y plan exactos;
4. si el estado final existe, se devuelve ALREADY_APPLIED;
5. sólo si el lock fue adquirido después de finalizar la sesión anterior y el fingerprint fuente
   completo continúa exacto se acredita que no fue aplicado y se devuelve ERROR con persisted=false;
6. si ninguna conclusión es autoritativa, se devuelve UNKNOWN con persisted=null.

UNKNOWN no incluye UUID, timestamps ni un receipt tentativo. El operador sólo puede repetir el mismo
bundle, operationId, plan y hashes. Como V27 no conserva el hash del plan, la reconciliación prueba
el postestado exacto —transiciones, motivos, lotes, slots y punteros— y no la identidad histórica del
comando que lo produjo. Un retry exacto:

- devuelve ALREADY_APPLIED si el commit anterior existía;
- aplica normalmente si se acredita que no existía;
- bloquea si encuentra un estado parcial o distinto.

La mera ausencia del postestado no demuestra rollback: puede existir un estado parcial, una sesión
todavía activa o una base temporalmente inaccesible. No hay retry automático de una nueva mutación
dentro de la misma ejecución. Un operationId nuevo no se usa para escapar de UNKNOWN.

## Estrategia de pruebas

### Unitarias

- estados READY, NOT_READY y ERROR;
- todos los reason codes y su orden estable;
- validación estricta del plan editorial;
- tamaño, UTF-8/NFC/LF, symlinks, claves desconocidas, duplicados y límites del plan;
- canonicalización y vector golden del plan;
- cobertura exacta y disjunta de contextos;
- plan determinista ante distinto orden de entrada;
- matriz de operationType, outcome, persisted y readiness;
- timestamps, receipt, exit codes y redacción;
- confirmaciones de operationId y SHA distintas del artefacto bloqueadas antes de abrir la base;
- replay por postestado exacto y documentación de la limitación histórica de V27;
- clasificación de fallos antes, durante y después del callback transaccional;
- byte-compatibility de reportes validate/dry-run v1 e import v2.

### PostgreSQL/Testcontainers

- readiness READY de un target completo;
- NOT_READY por cada categoría de inconsistencia;
- slots o punteros globales adicionales de otra publicación producen NOT_READY;
- primera promoción fresca;
- PROMOTE después de cualquier transición histórica se bloquea aunque no queden proyecciones;
- repetición exacta con cero delta y cero avance de secuencias;
- fecha futura y cruce del instante durante la transacción usan transaction_timestamp y se bloquean;
- publicación no sellada o contenido distinto bloqueados;
- estado actual parcial o target diferente bloqueados;
- requiredSetRevision recalculado para todos los scopes;
- REGISTRO sin requisito requerido bloqueado;
- reemplazo uno a uno;
- reutilización con reasignación de publicationId en slots;
- adición sin predecesor y recuperación de un hueco retirado;
- split y merge con cobertura exacta;
- requisitos nuevos, reutilizados, reemplazados y retirados en un mismo cutover;
- promoción de requisitos completa para todas sus audiencias;
- cobertura faltante, duplicada o solapada bloqueada;
- retiro puro autorizado con APPLIED más NOT_READY;
- retiro sin motivo, expectedReadinessAfter o acknowledgement bloqueado;
- fallo de constraint o postcondición revierte todo;
- ningún camino escribe snapshots sellados, aceptaciones, idempotencia HTTP o tablas de aplicación.

### Concurrencia y fallos

- dos promociones idénticas: una APPLIED y otra ALREADY_APPLIED;
- dos targets incompatibles: como máximo uno confirma;
- reemplazos con predecesores superpuestos no confirman ambos;
- import, dry-run, readiness, plan y apply cooperan sobre el mismo advisory lock;
- apply reutiliza PlannerCore y ReadinessCore en la misma sesión sin abrir un gate anidado;
- agotamiento del lock devuelve un resultado conocido y retryable;
- writer no cooperativo que causa timeout o deadlock no produce falso éxito;
- una desconexión sin completion autoritativa no presume rollback por su posición aparente;
- pérdida del acuse de commit produce reconciliación o UNKNOWN;
- persisted=false tras desconexión exige lock nuevo y fingerprint fuente todavía exacto;
- retry exacto después de UNKNOWN converge sin duplicar transiciones.

### Seguridad, procesos y regresión

- rol editorial positivo con grants mínimos;
- rol demasiado amplio, owner, heredado o con drift bloqueado;
- LegalEditorialSchemaVerifier detecta drift en toda la superficie V27 editorial;
- importador continúa sin poder promover ni retirar;
- el rol editorial no puede mutar snapshots sellados ni superficies HTTP;
- los grants UPDATE necesarios para SECURITY INVOKER permiten el call graph, pero todo UPDATE directo
  o no-op sigue siendo rechazado;
- jar real ejecuta readiness, plan y apply sin levantar web, Flyway, JPA o schedulers;
- stdout contiene un único JSON y ningún canal filtra paths, SQL, stack traces o canaries;
- system properties datasource no sustituyen variables editoriales;
- ambos jars excluyen application-secret.properties;
- suite backend completa;
- guard, suite y build frontend;
- git diff --check y estado de ambos repositorios.

### Capacidad

La puerta mínima usa:

- 128 documentos;
- 256 requisitos;
- 16 scopes;
- máxima reutilización razonable de documentos;
- reemplazos suficientes para recorrer uno a uno, split y merge.

Se registra:

- duración de readiness;
- duración de plan y apply;
- cantidad de ejecuciones JDBC lógicas;
- mayor sentencia;
- espera del advisory lock;
- memoria máxima del proceso cuando sea reproducible.

La prueba debe terminar dentro de los presupuestos editoriales sin N+1 no acotado. Si falla, se
optimiza el acceso antes de relajar timeouts o modificar V27.

## Cortes de implementación

1. contrato de readiness, modelos y pruebas unitarias;
2. evaluador PostgreSQL read-only;
3. formato inmutable del plan editorial, parser y dry-run;
4. primera promoción transaccional e idempotente;
5. CLI de promoción, rol editorial y verificador de permisos;
6. reemplazo uno a uno y reutilización;
7. reemplazos split y merge explícitos;
8. retiro explícito y hueco fail-closed;
9. reconciliación de commits ambiguos y UNKNOWN;
10. concurrencia, capacidad y procesos reales;
11. runbook, evidencia, coordinación cross-repo y cierre.

Cada corte termina con pruebas enfocadas, git diff --check, revisión del diff y un commit local
atómico. Backend y frontend usan commits separados. No se hace push.

El plan de implementación posterior detallará archivos, orden TDD, comandos y puertas exactas. Si
una prueba demuestra que V27 no puede satisfacer una invariante o el presupuesto de capacidad, el
corte se detiene y se propone V28 por separado.

## Documentación cross-repo

Al cerrar 2.3C se actualizan de forma coordinada:

- backend FRONTEND_INTEGRATION.md;
- backend docs/runbooks con la operación editorial;
- backend documento de cierre 2.3C;
- frontend docs/legal/README.md;
- frontend plan maestro de lanzamiento y BACKEND-HANDOFF 1.

El mirror frontend debe declarar que:

- promoción, reemplazo, retiro y readiness editorial internos quedaron acreditados;
- no existen todavía rutas HTTP legales;
- V28 multicontexto sigue pendiente;
- Tarea 3 no conecta queries reales;
- BACKEND-HANDOFF 1 continúa no disponible hasta API, seguridad, deploy y staging.

También corrige la secuencia histórica que ubicaba readiness antes de la promoción. El flujo
coordinado pasa a ser:

1. revisión profesional registrada;
2. digest, validación y dry-run;
3. importación y sello;
4. plan APPLICABLE;
5. apply de promoción o cutover;
6. readiness editorial READY;
7. más adelante, APIs, readiness HTTP, staging y handoff.

## Puerta de salida

2.3C queda cerrada únicamente cuando:

- los once cortes están completados y trazados;
- los cuatro tipos de pruebas pasan;
- el rol editorial mínimo fue acreditado;
- procesos reales cubren resultados exitosos, bloqueantes e indeterminados;
- la capacidad máxima termina dentro de presupuesto;
- reportes anteriores permanecen byte-compatible;
- no se modificó V27 o existe un diseño V28 explícitamente aprobado;
- el runbook permite promover, reemplazar, retirar y reconciliar sin información implícita;
- ambos repositorios documentan el estado real;
- no hubo push, deploy ni promoción de contenido real.

## Dependencias posteriores

La secuencia permanece:

1. cerrar 2.3C;
2. diseñar e implementar V28 para revisión agregada multicontexto;
3. implementar catálogo, documentos, ETag y endpoints autenticados;
4. implementar aceptación y registro atómicos;
5. aplicar seguridad, CORS, 409, 428 y enforcement;
6. desplegar contenido profesionalmente aprobado en staging;
7. acreditar BACKEND-HANDOFF 1;
8. conectar Tarea 3 del frontend.

Hasta entonces, los borradores actuales no se promueven y la aplicación pública no consume este
agregado legal.
