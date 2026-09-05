# Corte 14 — Diseño de requisitos legales públicos de registro

Fecha: 2026-09-05

Estado: diseño aprobado por el titular el 2026-09-05; 14A completado, 14B–14E pendientes.
La aprobación permite implementar el diseño por cortes. No habilita el endpoint ni el enforcement.
[Plan por cortes](2026-09-05-legal-public-requirements-read-implementation.md).

Baseline backend: `40a31a2`, rama `codex/lanzamiento-publico-backend`, árbol limpio.
Frontend: `7545201`, rama `codex/frontend-refactor-checkpoint`; se preservan `.agents/` y
`public/OrdenFix project naming/`, no versionados.

Fuentes del diseño:

- [Contrato HTTP vigente, §4.1.a](../../FRONTEND_INTEGRATION.md).
- [Diseño V28](2026-09-01-legal-required-set-aggregate-v28-design.md) y
  [cierre V28](2026-09-01-legal-required-set-aggregate-v28-closure.md).
- [Cierre de lectura documental 13D](2026-09-05-legal-public-document-read-closure.md).
- [Fronteras de los consumidores posteriores](2026-09-05-legal-public-document-read-design.md#fronteras-de-los-bloques-posteriores).

## Resultado y alcance

Entregar el conjunto completo de requisitos de registro, con sus documentos vigentes y un token
AGGREGATE_V1 persistido y verificado contra el mismo contenido de la respuesta:

```http
GET /api/public/requisitos-legales?contexto=REGISTRO&locale=es-AR
```

El servidor fija `REGISTRATION`, `ADMIN_TITULAR` y el vector completo `[REGISTRO]` mediante
`LegalApplicableScopeResolver`. El titular se registra como ADMIN. No se incorpora una audiencia,
perfil, actor o taller seleccionable desde el navegador. El resultado no depende de una sesión.

Se desarrollan cinco cortes: acreditación pura, contexto restringido, servicio PostgreSQL,
transporte HTTP y cierre integral. El primero, 14A, sólo agrega tipos y validaciones puras.

Los pendientes autenticados quedan para el bloque siguiente: requieren identidad real, tenant,
audiencia y evidencia previa, además de las reglas de carry-forward. También quedan pendientes
aceptación, historial propio, registro con evidencia, idempotencia HTTP y enforcement `428`.
No se cambia el contrato histórico de alta ni se crean aceptaciones al consultar requisitos.

V27/V28 permanecen congeladas. Este bloque no necesita V29 porque reutiliza el store y sus guards
sin introducir nuevas operaciones SQL protegidas. Una necesidad posterior de cambiar los guards
obliga a otro diseño y una nueva migración; nunca a editar V27/V28.
No se importa contenido legal real, aprovisionan bases compartidas, modifica frontend, habilita
email/Mercado Pago ni hace push/deploy. BACKEND-HANDOFF 1 y Tarea 3 frontend siguen cerrados.
Se conservan las decisiones de producto: reparaciones y evidencia, sin procesamiento de pagos
taller-cliente, emisión fiscal ni participación en su acuerdo económico.

## Alternativas y recomendación

| Alternativa | Ventaja | Costo o limitación |
| --- | --- | --- |
| Contexto público propio, store e hidratación en una transacción — recomendada | El token y los textos quedan acreditados bajo el mismo lock; un fallo precommit revierte el agregado nuevo | Requiere credencial y preflight propios, con INSERT sólo para el agregado derivado |
| Materializador existente y lector en dos transacciones | Mantiene cada credencial más estrecha | Exige reacreditar los punteros bajo shared y resolver cambios entre fases; un agregado puede quedar persistido aunque falle la lectura posterior |
| Sólo lectura de agregados preparados previamente | El GET nunca realiza DML | Requiere un nuevo proceso de preparación y coordinación editorial; una publicación válida podría carecer todavía de agregado |

Se recomienda la primera. Es coherente con la materialización bajo demanda aprobada en V28.
La primera resolución puede insertar la cabecera y su scope derivados; solicitudes posteriores
reutilizan la misma identidad física. No publica contenido, altera decisiones del usuario ni
escribe evidencia. Prefetch, revalidación y retries sólo pueden producir esa materialización
idempotente. No se calcula un digest sin persistencia ni se crean punteros nuevos por propósito.

No se amplía el rol del materializador ni el lector documental de 13. Se crea una frontera
`PUBLIC_REQUIREMENTS` con allowlist propia y se llama directamente a
`LegalRequiredSetAggregateStore` dentro de la transacción del consumidor. No se invoca
`LegalRequiredSetAggregateService`: su REQUIRES_NEW independiente confirmaría antes de hidratar.

## Wire, validación y disponibilidad

Se conserva exactamente la forma del contrato:

```text
{
  contexto, locale, requiredSetRevision,
  requisitos: [{
    id, contexto, tipoActo, afirmacion, afirmacionSha256, requerido,
    documentos: [{
      id, tipo, version, titulo, contenidoMarkdown, sha256,
      vigenteDesde, estado, locale
    }]
  }]
}
```

- `contexto` y `locale` son obligatorios, sensibles a mayúsculas y sin normalización. Sólo se
  admiten `REGISTRO` y `es-AR`. Si ambos fallan se evalúa primero locale, como en el catálogo.
- Ausencia produce el correspondiente `400 LOCALE_LEGAL_NO_SOPORTADO` o
  `400 CONTEXTO_LEGAL_NO_SOPORTADO`, con valor `null`. Un valor inválido conserva su string.
  Listas soportadas exactas: `["es-AR"]` y `["REGISTRO"]`.
- No se aceptan parámetros repetidos, aunque repitan el mismo valor. Se evalúan como un escalar
  formado con los valores en orden unidos por coma, que se devuelve en el campo de `details`
  correspondiente; no se elige silenciosamente uno. Se acredita esta regla mediante MVC real.
- No hay paginación. Se incluyen todos los requisitos del scope, obligatorios y opcionales.
  Requisitos en `manifest_ordinal` ascendente; documentos en `documento_ordinal` ascendente.
  No se ordenan documentos por tipo, fecha o UUID como en el catálogo.
- Debe existir al menos un requisito obligatorio completo. Cada requisito, incluido el opcional,
  debe estar vigente y tener entre 1 y 16 documentos, todos vigentes y verificables. La afirmación
  debe contener texto publicable según `LegalVisibleText.isPublishable`; digest correcto de un
  texto vacío, espacios o caracteres invisibles no basta. Un miembro inválido hace indisponible
  el conjunto entero; no se filtra para ocultarlo.
- UUID con `UUID.toString()`, hashes de textos con 64 hex minúsculas y fecha RFC 3339 UTC de
  precisión microsegundo. `estado` de cada documento es exclusivamente `VIGENTE`.
- Se preservan texto, espacios, Unicode y saltos originales. No se normaliza un texto para lograr
  que su digest coincida. No se exponen keys editoriales, IDs de publicación/agregado, perfil,
  audiencia, procedencia, `requiresReacceptance` ni outcome CREATED/REUSED.

Ausencia de puntero, conjunto vacío o sólo opcional, estado inconsistente, corrupción, límite
superado o fallo PostgreSQL producen `503 CONTRATO_LEGAL_NO_DISPONIBLE`, con
`details={contexto:"REGISTRO",locale:"es-AR"}`. No se devuelve un `200` vacío, un `404` ni un `409`
de integridad por fallos internos. Todos los errores usan el envelope existente, `no-store` y
ningún ETag de éxito. Mensajes públicos no incluyen SQL, contenido ni detalles de credenciales.

## Acreditación del contenido y de las revisiones

El replay existente de V28 reconstruye cabecera, scopes, revisión agregada y procedencia. No
hidrata los requisitos V27 ni vuelve a calcular su revisión desde Markdown. La nueva lectura
debe cubrir esa responsabilidad incluso cuando el store devuelve REUSED.

Se reutilizan `LegalRequiredSetProjection`, `LegalRequiredSetRevisionCalculator` y
`LegalRequiredSetAggregateRevisionCalculator`, sin alterar sus bytes, writers, límites o vectores.
La proyección pura nueva acredita forma, digests y capacidad; no constituye por sí misma prueba
de persistencia, actualidad o pertenencia. Estas últimas pertenecen al lector PostgreSQL.

1. Hidratar el conjunto y la publicación exactos de la procedencia del receipt, con el contexto,
   locale y audiencia resueltos por el servidor. No volver a consultar "lo último" en otra sesión.
2. Acreditar publicación SELLADO, scope y puntero actuales, miembros exactos, identidad de línea y
   versión, audiencia, contexto y pertenencia a la publicación. Comparar el conjunto con todos los
   requisitos de esa publicación aplicables al scope; detectar omisiones y miembros extranjeros.
3. Acreditar requisitos VIGENTE y documentos referenciados VIGENTE, con locale/contexto, membresía
   editorial y puntero documental correspondiente. Una versión puede haber sido introducida en
   una publicación anterior y ser reutilizada: no exigir igualdad con `publicacion_intro_id`.
4. Validar cardinalidades, duplicados y ordinales. Los ordinales de requisitos son únicos y
   ascendentes, pero pueden tener huecos por el filtro de scope. Los documentos de cada requisito
   mantienen ordinales consecutivos desde 1. No deduplicar o reparar datos inválidos en silencio.
5. Verificar afirmación y Markdown con `CanonicalTextValidator`, incluyendo SHA-256 de sus bytes
   originales. Validar metadatos representables y fechas no futuras respecto de la frontera
   post-lock. No inferir vigencia sólo de `vigenteDesde`.
6. Calcular SCOPE_V1 desde la proyección completa hidratada y exigir igualdad con la revisión del
   conjunto persistido. Recalcular AGGREGATE_V1 con ese componente y exigir igualdad con el receipt.
   La respuesta entrega la revisión agregada; aun con un solo scope difiere del token componente.
7. Formar un resultado inmutable, sin referencias JDBC/lazy. Los campos y el orden enviados por
   HTTP son los mismos de la proyección acreditada. UUID y fecha usan el render canónico existente;
   golden y pruebas de DTO verifican equivalencia. No se calcula el token desde JSON arbitrario.

La igualdad de hashes no reemplaza las verificaciones de estado y pertenencia. Tampoco se afirma
resistencia a un owner malicioso que cambie simultáneamente los datos y los verificadores; se
mantiene la frontera de confianza de V27/V28 y el protocolo cooperativo editorial existente.

## Transacción y resultado

Una única conexión del contexto nuevo usa `DataSourceTransactionManager`, sin rollback automático
ante fallo de commit, y `TransactionTemplate` declarado y efectivo
`REQUIRES_NEW/READ_COMMITTED`, mutable. El gate acredita el mismo JdbcTemplate y el par exacto y
ordenado de verificadores de esquema V28 y privilegios públicos de requisitos.

Secuencia obligatoria:

1. Resolver el vector servidor e iniciar el deadline antes de adquirir conexión.
2. Abrir transacción, aplicar presupuestos y acreditar modo efectivo, esquema y privilegios.
3. Tomar el advisory transaction lock compartido `ordenfix:legal-publicaciones:sello:v1` y obtener
   la frontera temporal post-lock.
4. Llamar al store existente: snapshot completo con FOR SHARE, cálculo, creación/reutilización y
   replay. Mantener todos los locks hasta el fin de esta transacción.
5. Hidratar y acreditar el contenido y las revisiones según el apartado anterior, con el mismo JDBC.
6. Confirmar y liberar recursos; devolver el resultado al transporte sólo tras commit exitoso y
   comprobación final del deadline. Recién entonces evaluar el ETag y responder `200`/`304`.

Una discrepancia de snapshot, colisión, digest, pertenencia, capacidad o deadline detectada antes
del commit aborta la transacción; no puede quedar un agregado nuevo parcial o inválido. Sobre un
agregado reutilizado, el fallo no lo borra ni lo reescribe. La reutilización encontrada en la
consulta inicial no ejecuta INSERT, UPDATE ni DELETE, incluso en una revalidación HTTP. Dos
creadores simultáneos pueden competir con `ON CONFLICT DO NOTHING`: el perdedor también puede
terminar con outcome REUSED, pero sí intentó ese INSERT. No se presenta como ausencia de DML.

Un fallo durante commit puede tener resultado UNKNOWN; también puede fallar la entrega después
de un commit confirmado. En esos casos no se promete rollback remoto ni se responde éxito
anticipadamente. Una nueva solicitud vuelve a acreditar todo y puede encontrar REUSED. No hay
retry automático en la misma solicitud ni supuesta transacción distribuida con el navegador.

Un writer editorial puede publicar después del commit y antes de que llegue la respuesta. La
respuesta acredita la observación protegida, no vigencia indefinida: la futura aceptación debe
revalidar su propia transacción. Este bloque no resuelve todavía esa escritura.

## Credencial y aislamiento

Contexto explícito no escaneable, con marcador `PUBLIC_REQUIREMENTS`, esquema `public` y
preflight propio. No hay fallback a `spring.datasource.*`, Flyway automático, JPA, web datasource
ni credencial owner. Un puente HTTP posterior crea un child context sin parent y copia sólo las
propiedades dedicadas y el flag interno. Expone únicamente la fachada; cierra contexto y pool en
shutdown o fallo de refresh.

Propiedades propuestas:

- Flag HTTP: `ordenfix.legal.public-requirements.enabled`, apagado por defecto.
- Flag interno: `ordenfix.legal.public-requirements-context.enabled`.
- Credencial: `ordenfix.legal.public-requirements.jdbc-url`, `.username`, `.password`.

Allowlist SELECT nominal del nuevo rol:

```text
flyway_schema_history
legal_publicaciones
legal_publicacion_requisitos
legal_publicacion_documentos
legal_requisito_conjuntos_actuales
legal_requisito_conjuntos
legal_requisito_conjunto_miembros
legal_requisito_lineas
legal_requisito_audiencias
legal_requisito_versiones
legal_requisito_documentos
legal_documento_lineas
legal_documento_versiones
legal_documento_contextos
legal_documento_vigentes
legal_requisito_agregados
legal_requisito_agregado_scopes
```

INSERT sólo en las dos tablas de agregados. UPDATE por columna exclusivamente sobre
`legal_requisito_conjuntos_actuales.conjunto_id`, requerido por PostgreSQL para FOR SHARE; el guard
V27 rechaza cualquier UPDATE real o no-op. Sin DELETE, TRUNCATE, DDL, secuencias, grant options,
acceso a usuarios, talleres, evidencia, metadata personal o idempotencia.

EXECUTE sólo sobre los siete guards/dependencias ya permitidos al materializador:

```text
legal_rechazar_update_delete()
legal_exigir_read_committed()
legal_exigir_lock_editorial_v28()
legal_requisito_agregado_insert_guard()
legal_requisito_agregado_scope_insert_guard()
legal_validar_requisito_agregado(uuid)
legal_requisito_agregado_constraint_guard()
```

No se agrega `legal_validar_requisito_agregado_actual(uuid)`: su FOR SHARE sobre cabecera exigiría
más privilegios sin aportar la hidratación requerida. Se comprueban privilegios efectivos,
PUBLIC/membresías, funciones, capacidades sistémicas, search_path e identidad de sesión como en
los contextos anteriores. Es un nuevo verifier, sin parametrizar o ensanchar allowlists existentes.
El provisioning se prueba sólo en PostgreSQL efímero con prefijo exclusivo. Cualquier aplicación
posterior de grants compartidos exige inventariar consumidores en su propio corte operativo.

Estos permisos limitan tablas y operaciones, no filas ni perfiles: SELECT puede alcanzar
borradores y V28 admite otros perfiles de agregados válidos a nivel SQL. La frontera pública
REGISTRATION/REGISTRO/ADMIN_TITULAR y la visibilidad vigente se imponen en resolver y lector;
no se atribuyen a RLS ni a una restricción física del rol que este esquema no tiene.

## Capacidad y presupuesto

Se mantienen los límites del contrato de manifiestos/proyección: 256 requisitos, 16 documentos por
requisito, 128 documentos distintos por publicación, 1 MiB UTF-8 por Markdown y 16 MiB de Markdown
expandido por scope. Las referencias repetidas cuentan de nuevo en este último límite. Afirmación
hasta 1000 codepoints, título hasta 300 y versión documental hasta 40 según SCOPE_V1; además se
exigen las reglas de texto y representación. No se amplía el límite a 64 porque V27 admita ese
ancho: una proyección incompatible falla `503`, sin cambiar el canonicalizador congelado.

Esos límites corresponden al conjunto actual, no a todo el catálogo histórico. El lector usa
consultas por IDs exactos y batches acotados, sin N+1 ni scan de todo el historial. Carga cada UUID
documental una vez, comparte sus strings en la proyección y preserva todas sus referencias wire.
Antes de transferir texto, cuenta enlaces y bytes; aplica sentinelas de exceso y CASE/proyecciones
acotadas para impedir cargar un TEXT arbitrariamente grande. LEFT JOIN y controles de cardinalidad
impiden que un filtro `estado='VIGENTE'` o un INNER JOIN oculte miembros rotos.

16 MiB de Markdown expandido no equivale a 16 MiB de JSON ni de heap: escapes, metadatos y
serialización agregan costo. No se introduce un tope de respuesta que rechace un manifiesto válido.
El gate de capacidad medirá bytes wire, filas, DML, consultas y cancelación en escenarios válidos
extremos; no se prometerá memoria auxiliar constante para una respuesta completa con Markdown.

Pool dedicado de dos conexiones, minIdle 0, adquisición 1 s, connect/login 1 s, socket 5 s y
cancelSignal 1 s. Deadline monotónico de 15 s desde borrow hasta commit/cleanup y chequeo final;
presupuestos de transacción/sentencia/lock editorial/lock de grafo: 15/5/1/1 s. Sentencias y socket
consumen el remanente, sin reiniciar el deadline por fase o FETCH. La URL restringe overrides de
sesión/credenciales/presupuestos, siguiendo la política del lector documental.

Se implementa un wrapper acotado del contexto nuevo basado en el mecanismo probado en 13, con
cancelación/abort de la conexión prestada y limpieza segura. No se cambia el wrapper documental
para introducir capacidades mutables compartidas. Chequeos antes/después de hashes y entre
batches acotan trabajo cooperativo; no prometen preempción de CPU. Jackson, red y teardown del
driver pueden exceder el tiempo de la operación: 15 s es presupuesto, no SLA extremo a extremo.

## Transporte, caché y seguridad

`Cache-Control: public, max-age=0, must-revalidate`, ETag `W/"<requiredSetRevision>"`.
Se verifica todo, incluso textos del último miembro, antes de evaluar `If-None-Match`; coincidencia,
lista de tags o wildcard nunca ocultan indisponibilidad. `304` no lleva body. Se conserva el
tratamiento de precondiciones acreditado con Spring en 13, con regresión de sus casos límite.

Una sola clasificación nueva, compartida por autorización, bypass JWT y rate limit, permite
únicamente GET de la ruta exacta cuando el flag es literalmente `true` sin trim, ignorando sólo
mayúsculas como `ConditionalOnProperty`. No admite HEAD implícito, vecinos, subrutas, slash final
ni `permitAll('/api/public/**')`. Respeta contextPath y mantiene la clasificación documental actual.
Un Bearer inválido no interfiere con este GET público. Con flag apagado no hay controller,
excepción de seguridad ni contexto de lectura nuevo.

Policy configurable propia `security.rate-limit.public-legal-requirements`: inicialmente 60/min
por IP, clave `public-legal-requirements`. No comparte cupo con documentos. Aplica también a
consultas inválidas y `304`; `429` conserva envelope, Retry-After, limit/remaining y `no-store`.
El número es operativo, no una constante del wire. Se conserva la política vigente de proxies/IP.
CORS mantiene Authorization y los tres headers de rate limit expuestos; no se agrega ETag manual.
Los cuatro estados combinados de flags documental/requisitos deben funcionar independientemente.

## Criterio de cierre y validación de esta propuesta

El bloque cierra sólo tras PostgreSQL 16 real, controles de privilegios, rollback nuevo/reutilizado,
carreras entre materializadores, dos lectores shared y writer editorial, límites válidos y excesos,
coherencia de wire/revisión/ETag, cancelación y un nuevo `clean verify` de ambos artefactos.
Los resultados previos de 13D son baseline; no sustituyen la evidencia por corte de este bloque.

El corte documental previo sólo documentó la propuesta. Se revisaron contrato, calculadores,
store/replay, esquema, ACLs, gate,
deadline y transporte existentes; tres revisiones independientes cubrieron contrato, persistencia
y fallos transaccionales. En ese corte no se ejecutó Maven por cambios exclusivamente Markdown.
La skill `writing-plans`, referida por brainstorming, no está instalada; se usa el formato de
cortes/whitelists/gates del repositorio. No se crea código ni configuración como sustituto.

La whitelist documental se limita a este diseño y su plan. V27 SHA-256
`52fd5f3eda14fde228e218f127b5e9362c8542dc7e26df7b502ba65061332b9b` y V28 SHA-256
`1227c8261cfcca1263a0b2105bf0dc797c1f59f3b5bdc71225464fc4aa154a5e` deben permanecer iguales.

Commit documental: `3351728 docs(legal): diseña requisitos publicos de registro`.
El titular aprobó después comenzar 14A. Su implementación, decisiones y gate focalizado están
registrados en el plan. Commit 14A: `feat(legal): acredita requisitos publicos`.
