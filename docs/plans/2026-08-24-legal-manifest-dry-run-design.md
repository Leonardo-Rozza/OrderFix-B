# Fase 2.3A — Validador y dry-run del manifiesto legal

Fecha: 2026-08-24

Estado: implementación cerrada y verificada (Cortes 1 a 7)

Cierre y evidencia reproducible:

- `docs/plans/2026-08-25-legal-manifest-dry-run-closure.md`.

Fuentes normativas:

- `FRONTEND_INTEGRATION.md`, sección 4.1.a;
- `docs/plans/2026-08-23-legal-api-contract-v1-design.md`;
- `docs/plans/2026-08-23-legal-persistence-append-only-design.md`;
- `docs/plans/2026-08-23-legal-persistence-append-only-implementation.md`;
- `docs/legal/publication-manifest.schema.json` del repositorio frontend.

## Objetivo

Agregar al backend una puerta operativa reproducible para validar una publicación legal antes de
importarla. El corte ofrece validación offline y un dry-run contra PostgreSQL V27, pero nunca
confirma publicaciones, versiones, snapshots ni contenido legal.

Al terminar, un release editorial puede demostrar que:

- sus bytes, estructura, digests y referencias son válidos;
- cumple las reglas contractuales congeladas;
- no contradice keys, versiones o linajes ya presentes;
- puede atravesar las constraints reales de V27;
- un éxito o un fallo del dry-run no deja filas legales confirmadas.

Esto reduce riesgo para staging y producción, pero no completa `BACKEND-HANDOFF 1`: todavía faltan
importación/sellado real, promoción/readiness, APIs, aceptación e idempotencia operativas, registro
atómico, seguridad/CORS, frontend conectado y contenido profesionalmente aprobado.

## Alternativas evaluadas

### A. Validador y dry-run sin persistencia — elegida

Separa una validación puramente offline de otra database-aware que construye el grafo dentro de una
transacción PostgreSQL y siempre revierte.

Ventajas:

- mantiene el corte pequeño y verificable;
- detecta errores editoriales sin necesitar infraestructura;
- acredita invariantes contra el estado real y los triggers de V27;
- deja un único núcleo reutilizable por la futura importación 2.3B.

Costo: el dry-run necesita una base ya migrada a V27 y ejerce escrituras provisionales que generan
WAL/locks y pueden consumir valores de secuencias, aunque el rollback impide que queden filas de
dominio confirmadas. Quien necesite cero efectos físicos debe ejecutar el dry-run sobre una base
descartable; `validate` sí es estrictamente offline.

### B. Validador más importación/sellado en el mismo corte — descartada

Acorta la cantidad de fases, pero mezcla parsing, seguridad de archivos, canonicalización,
persistencia idempotente y cierre transaccional antes de estabilizar el reporte del dry-run.

### C. Flujo completo con promoción/readiness — descartada

También incorporaría cambios de vigencia, slots y snapshots actuales. La superficie de riesgo y la
cantidad de estados operativos son demasiado grandes para una sola mini-fase.

## Alcance

Incluye:

- dos comandos internos: `validate` y `dry-run`;
- parser JSON estricto y DTOs del manifiesto v1;
- JSON Schema Draft 2020-12 congelado en recursos backend;
- canonicalización RFC 8785 y SHA-256;
- carga confinada y validación byte a byte de Markdown;
- validaciones cruzadas del contrato y la matriz de cobertura;
- contraste read-only con identidad/linaje persistidos;
- simulación transaccional contra V27 con rollback obligatorio;
- reporte determinista, machine-readable y sin contenido sensible;
- pruebas unitarias, de seguridad de paths y PostgreSQL/Testcontainers;
- documentación coordinada con el frontend.

No incluye:

- importación, sello, publicación, promoción, reemplazo, retiro o readiness real;
- V28, seed o contenido legal definitivo;
- controllers, endpoints HTTP, DTOs consumidores, ETag o caché;
- cambios de `SecurityConfig`, JWT, CORS o rate limits;
- registro legal, aceptaciones, idempotencia HTTP, `428` o enforcement;
- runtime frontend, deploy o push.

## Arquitectura

### Núcleo puro

Un módulo interno de manifiestos legales concentra tipos y reglas sin depender de Spring MVC ni de
entities serializables. Sus responsabilidades son:

1. leer y validar bytes;
2. parsear el manifiesto con configuración fail-closed;
3. validar JSON Schema y reglas cruzadas;
4. canonicalizar y calcular digests;
5. resolver/cargar fuentes Markdown dentro del release;
6. construir un plan normalizado e inmutable;
7. producir issues estables sin revelar valores sensibles.

El futuro importador 2.3B debe consumir ese mismo plan. No se permite implementar un segundo parser
o una validación más permisiva para el camino que escribe.

El valor acreditado que envuelve el plan es una frontera de tipos contra el uso accidental de
entradas no validadas, no una sandbox frente a reflection/`Unsafe` ni una firma criptográfica. El
camino JDBC usa los snapshots inmutables ya leídos y nunca vuelve a abrir las rutas de origen; así,
el TOCTOU residual inevitable del filesystem no puede sustituir el contenido que se simula o
persiste.

### Comando `validate`

Opera sin arrancar el contexto Spring principal ni conectarse a PostgreSQL. Ejecuta todas las reglas
de bytes, schema, contenido, referencias y cobertura que no dependen del estado persistido.

Es útil para edición local y CI. No acredita disponibilidad de la base, compatibilidad con linajes
existentes ni constraints diferibles.

### Comando `dry-run`

Ejecuta primero el mismo núcleo puro y, si no existen blockers estáticos:

1. comprueba por catálogo PostgreSQL/JDBC que el schema objetivo posee V27 y el modelo esperado;
2. contrasta keys y versiones con las identidades persistidas;
3. calcula ordinales tentativos sin interpretar la versión humana;
4. construye el grafo provisional de publicación, documentos, requisitos, membresías y snapshots;
5. solicita el sello técnico provisional;
6. fuerza `SET CONSTRAINTS ALL IMMEDIATE`;
7. genera el reporte;
8. marca la transacción para rollback aun en caso de éxito.

La transacción usa `READ COMMITTED`, porque V27 rechaza niveles con snapshots estables en los
protocolos que esperan locks y luego releen estado. Ningún camino del comando puede hacer commit.

El protocolo acreditado primero descubre las versiones existentes, toma sus locks
`FOR KEY SHARE NOWAIT` en orden estable y recién después bloquea las líneas por key. Luego relee y
rechaza cualquier versión aparecida entre ambas lecturas como concurrencia operativa. Las
publicaciones históricas introductorias se bloquean selladas antes de comparar sus hijos. Las
identidades nuevas se insertan por key, aunque los memberships conservan el ordinal explícito del
manifiesto; así dos releases equivalentes con arrays inversos no forman ciclos sobre los índices
únicos.

### Contexto operativo aislado

El dry-run usa un contexto Spring no web y limitado a los componentes legales necesarios. No
escanea ni ejecuta:

- `DataLoader`;
- `DeviceCredentialLegacyMigration`;
- schedulers de Mercado Pago o suscripciones;
- controllers, filtros o servidor HTTP;
- Flyway automático.

El comando falla si V27 no está aplicada; nunca migra una base como efecto colateral. El contexto
normal de la aplicación permanece sin cambios cuando no se invoca una operación legal. La
configuración JDBC aislada sólo se habilita mediante la propiedad interna
`ordenfix.legal.dry-run-context.enabled=true`, que el futuro CLI controlará dentro de su propio
proceso; no es una opción de runtime de la API normal.

## Entrada y ownership del contrato

El argumento obligatorio es la ruta a un archivo llamado exactamente `publication-manifest.json`.
La raíz de publicación es su directorio padre real y el basename de ese directorio debe coincidir
con `publicationId`. El CLI puede recibir el bundle desde cualquier ubicación local; la exigencia
frontend de alojarlo bajo `docs/legal/publications` es una convención de ese repositorio, no una ruta
absoluta portable al backend.

El backend incorpora una copia inmutable y versionada del JSON Schema v1. El schema frontend sigue
siendo el artefacto editorial visible, pero ambos repositorios documentan el mismo SHA-256 y poseen
una prueba/check de paridad para que un cambio exija coordinación explícita. El comando nunca carga
un schema remoto indicado por `$schema`.

`schemaVersion != 1`, locale no soportado o cualquier campo desconocido bloquean el release. Un
schema futuro requiere otro recurso versionado y una decisión contractual; no cambia el significado
de v1.

## Validación de bytes y JSON

El manifiesto debe ser:

- UTF-8 estricto, sin reemplazo silencioso de bytes inválidos;
- Unicode NFC;
- sin BOM;
- con saltos LF, sin CR;
- JSON válido bajo I-JSON;
- sin nombres de propiedad duplicados.

El parser rechaza trailing content, propiedades desconocidas, coerciones de tipos y enums no
reconocidos. Ordenar propiedades con Jackson no se considera RFC 8785: se usa una implementación JCS
dedicada, validada contra los vectores oficiales. El `manifestSha256` es SHA-256 sobre los bytes
UTF-8 del JSON canónico RFC 8785.

La canonicalización no corrige Unicode ni el contenido. Primero se validan los bytes/strings y luego
se calcula el digest; un release inválido nunca se normaliza para hacerlo pasar.

Se aplican límites operativos explícitos y testeados para tamaño de manifiesto, cantidad de entradas,
tamaño por Markdown y tamaño total. Superar un límite produce un blocker, no lectura parcial ni
`OutOfMemoryError` como mecanismo de rechazo. Los números exactos se congelan en el plan de
implementación junto con fixtures de borde.

## Resolución segura de fuentes

Cada `source` se resuelve desde la raíz real de la publicación. Debe:

- ser relativa y terminar en `.md`;
- cumplir el patrón del schema;
- resolver a un archivo regular;
- permanecer dentro de la raíz después de `normalize()` y `toRealPath()`;
- no atravesar ningún componente symlink, aunque su destino actual permanezca dentro del release;
- no repetirse entre documentos.

No se siguen URLs, classpath, stdin, dispositivos ni rutas absolutas. Los issues muestran sólo la
ruta relativa saneada.

## Validación de Markdown y afirmaciones

Cada Markdown y cada `statement` debe ser UTF-8/NFC/LF, sin BOM. También se rechazan noncharacters
Unicode, C0 salvo TAB/LF, DEL y C1: `U+0000` no es admisible en `text` de PostgreSQL y el resto no
es contenido legal visible seguro ni puede usarse para alterar el parseo u ocultar marcadores
editoriales. El digest se calcula sobre los bytes UTF-8 exactos ya validados, sin reescritura. Debe
coincidir en tiempo constante con el SHA-256 del manifiesto.

El título se obtiene del primer encabezado ATX H1 `# Título`. Para v1:

- debe existir y no estar vacío;
- sólo admite texto plano;
- rechaza HTML, imágenes, enlaces y markup inline;
- se recorta únicamente el whitespace exterior permitido;
- el valor resultante debe cumplir el límite persistente de 300 caracteres.

Además, los campos editoriales obligatorios del publicador, las afirmaciones y el H1 deben contener
al menos un code point visible. Para esta decisión se ignoran `Character.isWhitespace`,
`Character.isSpaceChar` y las categorías Unicode `FORMAT` y `MARK`; NBSP, zero-width spaces, word
joiners, variation selectors, marcas combinantes o direccionales no pueden convertir un valor
visualmente vacío en contenido publicable. Todo control C0/C1 invalida el campo completo, incluso
cuando está embebido entre caracteres visibles.

Esta regla evita que frontend/backend deriven títulos distintos de Markdown complejo.

Los siete placeholders editoriales conocidos se rechazan tanto en el snapshot del publicador como
en contenido y afirmaciones:

- `[RAZÓN SOCIAL]`;
- `[CUIT]`;
- `[DOMICILIO]`;
- `[EMAIL LEGAL]`;
- `[EMAIL PRIVACIDAD]`;
- `[JURISDICCIÓN]`;
- `[HORARIO DE ATENCIÓN]`.

También se rechazan los patrones genéricos ya coordinados con el guard frontend (`{{...}}`,
`<REPLACE_ME>`, `TODO_LEGAL`, corchetes de dato pendiente) y sus marcadores editoriales de borrador,
no-publicación o revisión pendiente. No se intenta clasificar lenguaje jurídico libre fuera de ese
set contractual explícito.

La detección combina la fuente con el texto visible del AST CommonMark: entidades, énfasis,
soft-breaks y caracteres Unicode de formato no pueden fragmentar un marcador. La normalización se
usa sólo para detectar y nunca reescribe el contenido acreditado. Los labels reales de links e
imágenes quedan fuera del patrón genérico de corchetes, pero sus destinos siguen auditados.

Todo el Markdown, no sólo el H1, rechaza HTML crudo y destinos de enlace con esquema activo
`javascript:`, `vbscript:` o `data:` después de decodificar las entidades admitidas y remover
controles ASCII. Los autolinks seguros `https:` y `mailto:` no se consideran HTML crudo.

## Paridad con el guard frontend

El schema, sus enums, la matriz mínima de cobertura, placeholders, marcadores editoriales, texto
canónico y reglas de Markdown se mantienen coordinados entre repositorios. El backend agrega
garantías autoritativas que el guard frontend no acredita: lectura estricta del manifiesto
UTF-8/I-JSON, nombres JSON duplicados, trailing content, RFC 8785, límites operativos y detección de
cambios de archivos entre lecturas.

La copia exacta del schema y el guard equivalente del frontend quedaron actualizados y verificados
en `d38e276 fix(legal): alinea guard con contrato backend v1`. La paridad estática del Corte 4 está
cerrada entre repositorios; no implica que el dry-run JDBC ni el importador estén terminados.

El core también replica la política de correo público del guard para los tres contactos del
snapshot: sintaxis ASCII acotada, dominio DNS público y rechazo de IPs, hosts internos y dominios
reservados. La comparación con `VITE_*` (`CONTACT_MISMATCH`) continúa siendo una regla exclusiva de
deploy frontend y no forma parte del manifiesto portable.

El mirror frontend retiró dos sobre-restricciones que no pertenecen al schema ni a V27:

- un tipo documental puede aparecer más de una vez con keys/versiones y scopes compatibles; esto
  permite split/merge o versiones paralelas sin perder la identidad estable por key;
- un documento puede estar vinculado sólo a requisitos opcionales. Sí se rechaza un documento
  completamente huérfano, pero no se exige que su vínculo sea con `required=true`.

Ambos repositorios aceptan ahora esos dos casos. La mitad backend del handshake es hermética y
congela 35 códigos frontend, 14 garantías de hardening adicionales y un único código legacy
reservado (`MANIFEST_DUPLICATE_DOCUMENT_TYPE`), que el frontend conserva por compatibilidad pero ya
no emite. La prueba nunca depende de una ruta local hacia otro checkout.

## Reglas contractuales cruzadas

El validador exige, como mínimo:

- revisión jurídica y contable `APPROVED`, cada una con referencia sin marcadores editoriales y
  fecha válida;
- locale de documento igual al locale del manifiesto;
- keys, combinaciones key+version, sources y referencias únicas;
- toda referencia de requisito resuelta exactamente una vez;
- al menos un documento por requisito;
- documentos de un requisito con el mismo locale y con su contexto congelado;
- audiencias no vacías y sin duplicados;
- `statementSha256` exacto;
- identidad estable de documento por key: tipo y locale inmutables;
- identidad estable de requisito por key: locale, contexto, acto y set de audiencias inmutables;
- reutilización de `key + version` sólo si todos los bytes y metadata coinciden;
- ordinal tentativo mayor al máximo históricamente publicado de la línea;
- orden del manifiesto preservado en memberships y snapshots, sin renumerar scopes filtrados;
- cada scope construido con exactamente los requisitos que le aplican;
- `REGISTRO/es-AR/ADMIN_TITULAR` con cobertura obligatoria completa, acumulable entre varios
  requisitos del mismo scope.

El `taxId` v1 verifica únicamente el formato congelado `NN-NNNNNNNN-N`; no prueba checksum ni
existencia ante ARCA. La acreditación técnica no reemplaza la revisión profesional de la identidad
del publicador.

La puerta de release completo también revisa la matriz congelada en frontend: registro del titular,
primer ingreso de `USER`, contratación PRO, atestaciones de fotos y credenciales para ambas
audiencias, y cierre de cuenta para el titular. El reporte identifica scopes faltantes sin fabricar
requisitos. La cobertura de cada scope se agrega entre sus requisitos `required=true`; no se exige
que un único requisito concentre todos los tipos documentales esperados.

El manifiesto v1 no expresa UUID, retiro, motivo ni mapa split/merge. La fase 2.3A no infiere esas
operaciones. Un dry-run de una futura sustitución sólo acredita que el release puede importarse como
BORRADOR; la intención de promoción/retiro se diseñará por separado en 2.3C.

Como los UUID definitivos no se persisten en este corte, el reporte no promete IDs ni
`requiredSetRevision`/`documentSetRevision` finales. Sí informa los conteos, scopes y operaciones
tentativas necesarios para revisar el release.

PostgreSQL almacena `timestamptz` con precisión de microsegundos. El dry-run rechaza antes de
escribir cualquier `effectiveAt` o timestamp de revisión con fracción más precisa, para no aceptar
un valor que la base redondearía silenciosamente. La revisión provisional de scope tampoco es un
contrato público: usa SHA-256 sobre el domain tag UTF-8
`ordenfix:legal-dry-run-scope:v1\n`, el JSON canónico y, separados por NUL, locale, contexto y
audiencia. El importador real podrá reemplazarla mediante una decisión versionada.

## Modelo de reporte

La salida canónica es machine-readable y determinista. Contiene:

- versión del reporte;
- comando ejecutado;
- `PASS`, `BLOCKED` o `ERROR`;
- `publicationId`, schema version y digest canónico cuando pudieron calcularse;
- conteos de documentos, requisitos y scopes;
- lista ordenada de issues;
- indicador explícito `persisted=false`.

Cada issue contiene código estable, severidad, ubicación relativa y mensaje seguro. Nunca incluye:

- Markdown o afirmaciones completos;
- contactos completos o CUIT;
- rutas absolutas;
- SQL, stack traces o nombres internos innecesarios;
- credenciales, variables de entorno o secretos.

Los issues se ordenan por severidad, código y ubicación. Se limita la cantidad devuelta y se agrega
un contador de omitidos para evitar respuestas no acotadas.

Exit codes:

- `0`: `PASS`;
- `2`: `BLOCKED` por entrada o contrato inválido;
- `3`: `ERROR` operativo/configuración/DB.

Los fallos esperables conservan siempre el envelope seguro. La construcción del CLI también ocurre
dentro de la frontera protegida y su último fallback es un envelope constante pre-serializado que
no depende de Jackson. La frontera CLI no ofrece un modo debug: descarta causas inesperadas y
termina con `3` para impedir que el código propio publique
logging, rutas, SQL o credenciales en stdout/stderr. Si falla el propio stream no puede prometerse
un envelope, pero el CLI tampoco imprime un stack trace. Esta garantía comienza al entrar en
`LegalManifestCli.main`: `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` son procesadas
por la JVM antes de `main`, pueden escribir su contenido en stderr y deben llegar ausentes o bajo
control de un launcher confiable. Un canal de diagnóstico futuro deberá ser estructurado,
redactado y probado antes de habilitarse.

## Manejo de fallos y rollback

Los errores esperables se traducen cerca de su frontera:

- bytes/JSON/schema;
- paths/IO;
- contenido/digest;
- referencias/contrato;
- conflicto con identidad persistida;
- schema DB incompatible;
- constraint V27;
- lock/timeout operativo.

No se reutiliza la traducción HTTP genérica de `DataIntegrityViolationException`, porque hoy está
orientada a teléfono/email y sería incorrecta para el dominio legal.

El orquestador del dry-run abre una transacción nueva, fuerza constraints y siempre la marca
rollback-only en `finally`. Si la conexión se corta, PostgreSQL revierte la transacción. Las pruebas
comprueban ausencia de filas parciales tanto después de `PASS` como de cada clase de fallo tardío.
La clasificación estable distingue conflicto persistido y constraint (`BLOCKED`) de aislamiento,
lock, timeout SQL, conexión, concurrencia, schema incompatible y fallo operativo genérico
(`ERROR`), sin inspeccionar ni publicar mensajes de PostgreSQL.

## Estrategia de pruebas

### Unitarias

- vectores oficiales RFC 8785, incluido orden UTF-16, escapes y representación numérica;
- UTF-8 roto, BOM, CRLF, NFD, JSON duplicado, trailing content y campos desconocidos;
- schema/enum/locale y límites de tamaño;
- H1 ausente, vacío o con markup;
- SHA de manifiesto, documentos y afirmaciones;
- referencias, duplicados, scopes, audiencias y matriz de cobertura;
- mapping explícito `PENDING/APPROVED` a estados internos y CUIT con guiones a 11 dígitos;
- reporte determinista y redacción de datos.

### Seguridad de filesystem

- `..`, rutas absolutas y extensiones incorrectas;
- archivo inexistente/no regular;
- cualquier componente symlink rechazado, tanto interno como de escape;
- carrera básica de sustitución detectada reabriendo/verificando atributos antes de leer.

### PostgreSQL/Testcontainers

- V27 válida y versión inferior/incompatible;
- dry-run válido termina `PASS` y deja cero filas legales;
- blocker estático no abre una escritura;
- conflicto de key, identidad, versión y ordinal existente;
- reutilización exacta aceptada;
- fallo de constraint diferible después de construir el grafo;
- error tardío y desconexión sin estado parcial;
- aislamiento `READ COMMITTED` y timeouts acotados;
- contexto CLI sin Flyway, users/talleres semilla, runners ni schedulers.

El Corte 5 quedó acreditado sobre PostgreSQL 16 con 41 pruebas enfocadas: 20 de traducción de
fallos, 14 de contraste/rollback y 7 de concurrencia/fallos tardíos. Incluye constraint diferible,
V26, dependencia abierta, identidad y metadata incompatibles, precisión temporal, transición
versión→línea, órdenes inversos, timeouts, desconexión real y recuperación del pool. La regresión
unitaria completa posterior ejecutó 517 pruebas sin fallos.

El Corte 6 agregó 45 pruebas unitarias de argumentos, reporte y frontera CLI, una IT de aislamiento
y una IT de empaquetado/proceso con seis casos: una inspección de ambos jars y cinco ejecuciones JVM
reales del `legal-cli`. El environment del dry-run expone por allowlist sólo las cuatro propiedades
datasource y elimina las fuentes system/env del contexto hijo; así no pueden inyectarse sources,
Config Data, Flyway, web o logging desde el host. La configuración JDBC tampoco es candidata del
component scan normal, incluso si el flag interno llega hostilmente habilitado. Sobre una base vacía
no crea `flyway_schema_history`, no registra JPA/web/runners/schedulers y cierra Hikari. La
inspección acredita ambos `Start-Class` y que los jars excluyen `application-secret.properties`;
cuatro procesos acreditan códigos `0/2/3/0`, salida JSON redactada y rollback V27, y el quinto
delimita la salida pre-`main` de
`JAVA_TOOL_OPTIONS`. Una secuencia identity que avanza fuera del rollback demuestra que no es un
PASS simulado sin interacción JDBC.

El Corte 7 cerró la paridad cross-repo sin cambiar el contrato: los schemas son byte-identical
(`10547` bytes y SHA-256
`f7a4ee17f53f5ed3f2613d894fa3a4f46896dfaaec0c80dab055e4320f036f8b`), la fixture golden produjo
el mismo plan estático (11 documentos, 6 requisitos y 8 scopes), las 64 pruebas backend enfocadas y
las 42 del guard frontend pasaron, y el build frontend terminó limpio. La regresión backend final
ejecutó 562 unitarias y 56 IT sin fallos. El cierre documental frontend quedó en `19b4953` y la
evidencia completa se conserva en el documento de cierre.

### Regresión

- `./mvnw test`;
- suites legales PostgreSQL;
- `./mvnw verify` completo;
- `git diff --check` en ambos repositorios.

## Documentación y commits

La implementación se separa como mínimo en:

1. diseño aprobado;
2. recursos/dependencias de validación;
3. parser, canonicalización y validación estática;
4. contraste PostgreSQL y dry-run rollback-only;
5. comando/reportes;
6. pruebas y hardening;
7. cierre backend y coordinación documental frontend.

No se hace push. El frontend conserva sin tocar `.agents/` y
`public/OrdenFix project naming/`.

## Puerta de salida

La fase 2.3A sólo se considera cerrada si:

- los dos comandos producen el mismo plan estático para la misma entrada;
- RFC 8785 pasa vectores oficiales;
- schema, Markdown y paths fallan cerrado;
- PostgreSQL V27 acredita el grafo provisional;
- `PASS`, blockers y errores tardíos dejan cero filas legales;
- el comando no levanta HTTP ni ejecuta migraciones, seeds, schedulers o runners ajenos;
- los reportes no filtran contenido ni configuración sensible;
- `./mvnw verify` y `git diff --check` pasan;
- los commits son atómicos y locales;
- `BACKEND-HANDOFF 1` sigue documentado como no disponible.

Todos estos puntos quedaron acreditados al cerrar el Corte 7. La fase reduce el riesgo de una
importación futura, pero no constituye readiness de publicación ni habilitación pública.

## Siguientes cortes

- **2.3B:** importación idempotente y sello transaccional reales, sin promoción.
- **2.3C:** publicación/promoción/retiro y readiness de datos, todavía sin enforcement.
- **2.4+:** APIs públicas/autenticadas, aceptación, registro compatible e integración frontend en
  fases separadas.
