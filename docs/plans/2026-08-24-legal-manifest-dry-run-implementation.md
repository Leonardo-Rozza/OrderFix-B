# Fase 2.3A — Plan de implementación del validador y dry-run legal

Fecha: 2026-08-24

Estado: en ejecución; Cortes 1 a 4 backend cerrados y mirror frontend pendiente

Diseño aprobado:

- `docs/plans/2026-08-24-legal-manifest-dry-run-design.md`;
- corrección de paridad editorial `d7d7b24`;
- persistencia append-only V27 y mappings de Fase 2.2.

Este plan implementa únicamente validación offline y simulación PostgreSQL rollback-only. No
habilita importación real, sello confirmado, promoción, retiro, readiness, endpoints ni aceptación.
`BACKEND-HANDOFF 1` permanece cerrado al terminar 2.3A.

## Decisiones congeladas

### Dependencias

Se conserva Spring Boot `4.0.6`, Java 21 y Jackson 3 `3.1.2` para el stack HTTP. Para el módulo
interno legal se usa la línea Jackson 2 ya presente en el proyecto:

- `com.networknt:json-schema-validator:2.0.7`;
- Jackson 2 BOM `2.22.1`;
- `io.github.erdtman:java-json-canonicalization:1.1`;
- `org.commonmark:commonmark:0.30.0`.

NetworkNT 2.x soporta Draft 2020-12 sin subir Jackson 3 a `3.2.1`, lo que evita modificar el
serializador principal de toda la API en este corte. La actualización secundaria de Jackson 2 sí
requiere regresión completa de Flyway, JWT, springdoc y filtros que ya lo usan.

Se excluye `jackson-dataformat-yaml` del edge de NetworkNT y la API legal acepta únicamente
`InputFormat.JSON`. Se conserva `com.ethlo.time:itu` para afirmar `date-time`. Joni y Graal no se
agregan; son opcionales y los patrones v1 quedan cubiertos con el motor JDK.

CommonMark se limita al parser AST core, sin renderer ni extensiones. La versión `0.30.0` congela
límites de anidamiento/bloques y correcciones de complejidad para input hostil; no agrega
dependencias transitivas de runtime. El AST es la fuente para distinguir HTML, enlaces, imágenes,
código y referencias, evitando que expresiones regulares incompletas decidan semántica Markdown.

El tag upstream `2.0.7` posee una trazabilidad GitHub inconsistente. La fuente reproducible para el
binario es Maven Central y su POM Jackson 2. El lock efectivo y los checksums quedan registrados por
el build/SBOM; nunca se obtiene código desde ese tag durante la compilación.

### Contrato congelado

El backend incorpora una copia byte-identical de:

```text
frontend/docs/legal/publication-manifest.schema.json
SHA-256: f7a4ee17f53f5ed3f2613d894fa3a4f46896dfaaec0c80dab055e4320f036f8b
Tamaño: 10547 bytes
```

El recurso backend será:

```text
src/main/resources/legal/manifest/v1/publication-manifest.schema.json
```

El schema sólo se carga desde classpath. `$schema` y `$id` nunca habilitan red ni otro loader. Un
test de integridad compara los bytes contra el hash literal anterior y fuerza la inicialización de
todos los validators al arrancar el núcleo.

### Límites operativos v1

Los siguientes límites son bloqueantes y se aplican antes de reservar estructuras grandes:

| Recurso | Máximo |
|---|---:|
| manifiesto raw | 1 MiB (`1_048_576`) |
| profundidad JSON | 32 |
| tokens JSON | 100.000 |
| longitud de string JSON | 1 MiB |
| longitud de nombre JSON | 256 |
| longitud de número JSON | 128 |
| documentos | 128 |
| requisitos | 256 |
| documentos por requisito | 16 |
| Markdown individual | 1 MiB |
| total de Markdown | 16 MiB |
| issues expuestos | 200 |

Los enums del schema acotan contextos a 8 y audiencias a 2. Superar un límite produce
`BLOCKED`; nunca se trunca el input para continuar.

### Resultado y fallos

Las validaciones esperables usan resultados explícitos y acumulan issues. Las excepciones quedan
reservadas para IO inesperado, configuración, conexión y SQL. Se capturan sólo en la frontera que
puede traducirlas; no se registran y relanzan en varias capas.

Estados:

- `PASS`: contrato válido para el nivel ejecutado;
- `BLOCKED`: input, contenido, referencia, identidad o constraint incompatible;
- `ERROR`: configuración, schema DB, conexión, timeout o fallo inesperado.

Exit codes: `0`, `2` y `3`, respectivamente.

El JSON de stdout es compacto, UTF-8, determinista y tiene esta forma estable:

```json
{
  "reportVersion": 1,
  "command": "validate",
  "status": "PASS",
  "persisted": false,
  "publicationId": "release-2026-08-24",
  "schemaVersion": 1,
  "manifestSha256": "...",
  "counts": {"documents": 11, "requirements": 6, "scopes": 8},
  "issues": [],
  "omittedIssueCount": 0
}
```

Campos aún desconocidos quedan `null`; no se fabrican valores. Cada issue contiene sólo
`severity`, `code`, `location` relativa y un mensaje operativo seguro. No incluye valores, SQL,
constraints desconocidas, ruta absoluta ni stack trace.

No hay retry automático de locks/conexión: el operador puede repetir el comando. Esto mantiene
tiempos acotados y evita multiplicar contención.

## Corte 1 — Dependencias y schema inmutable

### Archivos

Modificar:

- `pom.xml`.

Crear:

- `src/main/resources/legal/manifest/v1/publication-manifest.schema.json`;
- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/LegalManifestSchema.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/LegalManifestSchemaTest.java`.

### Implementación

1. Agregar propiedades versionadas para Jackson 2 BOM, NetworkNT y JCS.
2. Declarar `jackson-core`/`jackson-databind` directos para fijar el parser secundario.
3. Declarar NetworkNT con exclusión YAML y JCS sin dependencias adicionales.
4. Copiar el schema v1 exactamente, preservando LF y newline final.
5. Cargar bytes una sola vez, comprobar tamaño/hash y construir un `Schema` Draft 2020-12 con
   `formatAssertionsEnabled(true)`.
6. No registrar el validador como bean global ni tocar el `ObjectMapper` de Spring MVC.

### Pruebas y puerta

```bash
./mvnw -DskipTests dependency:tree
./mvnw -Dtest=LegalManifestSchemaTest test
```

Verificar:

- NetworkNT `2.0.7`;
- Jackson 2 core/databind/dataformat/datatype sin mezcla `2.21.x`;
- Jackson 3 continúa en `3.1.2`;
- ausencia de Joni/Graal en runtime;
- schema hash exacto y resolución 100% offline;
- schema inválido/futuro falla cerrado.

Commit: `build(legal): congela schema y dependencias del manifiesto`.

## Corte 2 — Bytes, JSON estricto y RFC 8785

### Archivos

Crear bajo `.../legal/manifest/core/`:

- `LegalManifestLimits.java`;
- `LegalManifestIssueCode.java`;
- `LegalManifestIssue.java`;
- `LegalManifestStatus.java`;
- `LegalManifestValidation.java`;
- `StrictJsonReader.java`;
- `Rfc8785Canonicalizer.java`;
- `LegalManifestParser.java`;
- `model/LegalManifestV1.java`;
- `model/LegalPublicationPlan.java`.

Crear pruebas:

- `StrictJsonReaderTest.java`;
- `Rfc8785CanonicalizerTest.java`;
- `LegalManifestParserTest.java`.

### Implementación

1. Leer el manifiesto con tamaño acotado antes de decodificar.
2. Rechazar cualquier componente symlink, path que no sea archivo regular y nombre distinto de
   `publication-manifest.json`.
3. Decodificar con `CharsetDecoder` y `CodingErrorAction.REPORT`; rechazar BOM, CR, texto no NFC y
   surrogates sueltos.
4. Configurar Jackson 2 con límites, `STRICT_DUPLICATE_DETECTION`,
   `FAIL_ON_READING_DUP_TREE_KEY` y `FAIL_ON_TRAILING_TOKENS`. No habilitar comments, single
   quotes, trailing commas, `NaN` ni `Infinity`.
5. Validar el `JsonNode` contra el schema local y mapear errores a código/location propios; nunca
   depender del texto u orden de NetworkNT.
6. Mapear a records inmutables y enums controlados. No usar DTOs HTTP ni entities JPA.
7. Pasar el `String` ya acreditado a `new JsonCanonicalizer(text)`. No usar el constructor
   `byte[]` ni confiar en su parser para UTF-8/duplicados.
8. Calcular SHA-256 sobre `getEncodedUTF8()` y preservar los bytes canónicos en el plan para el
   futuro importador.

### Pruebas y puerta

Cubrir:

- UTF-8 roto, BOM, CRLF, NFD, trailing JSON y claves duplicadas, incluidas escaped y valor previo
  `null`;
- límites de documento/tokens/depth/string/name/number;
- campos extra, tipos, `oneOf`, `if/then`, `uniqueItems`, email y `date-time`;
- whitespace Unicode peligroso en emails (`U+00A0`, `U+2007`, `U+202F`) y controles;
- ejemplo RFC 8785, orden UTF-16, escapes, números y vectores oficiales JCS;
- igual hash frente a cambios sólo de orden/whitespace;
- input inválido nunca llega a JCS.

Commit: `feat(legal): agrega parser estricto y canonicalizacion RFC 8785`.

## Corte 3 — Filesystem y Markdown seguro

### Archivos

Crear bajo `.../legal/manifest/core/`:

- `ConfinedReleaseReader.java`;
- `CanonicalTextValidator.java`;
- `LegalMarkdownValidator.java`;
- `LegalEditorialMarkerValidator.java`.

Crear pruebas:

- `ConfinedReleaseReaderTest.java`;
- `LegalMarkdownValidatorTest.java`;
- `LegalEditorialMarkerValidatorTest.java`.

### Implementación

1. Resolver la raíz real desde el padre del manifest y exigir
   `publicationId == basename(root)`.
2. Resolver cada source con `normalize()` y `toRealPath()`, verificar confinamiento y rechazar todo
   symlink de la cadena, aunque apunte dentro de la raíz.
3. Reabrir/verificar atributos antes de leer para reducir sustituciones TOCTOU; un cambio bloquea.
4. Aplicar límite individual y total antes/durante la lectura.
5. Exigir UTF-8 fatal, sin BOM/CR, NFC, sin noncharacters ni controles C0/C1 no permitidos, y
   SHA-256 exacto sobre bytes originales.
6. Extraer el primer H1 ATX, incluido un `#` vacío que no puede saltarse en favor de otro posterior;
   exigir texto plano no vacío y máximo 300 caracteres.
7. Rechazar HTML crudo en todo el Markdown y links `javascript:`, `vbscript:` o `data:` aun con
   entidades/controles. Permitir autolinks seguros `https:` y `mailto:`.
8. Compartir los siete placeholders, patrones genéricos y marcadores editoriales actuales del
   frontend. Inspeccionar tanto la fuente como el texto visible derivado del AST para cerrar markup,
   entidades, caracteres de formato invisibles y soft-breaks; ignorar correctamente labels de links,
   imágenes y checkboxes al detectar corchetes genéricos sin ocultar sus destinos.
9. Sanear todas las locations a rutas relativas que cumplan un patrón de salida seguro.

### Pruebas y puerta

Cubrir archivo ausente/no regular, absoluto, `..`, extensión, source duplicado, symlink interno y de
escape, swap básico, tamaño, digest, H1, markup inline, HTML, enlaces ofuscados, placeholders y
marcadores.

Commit: `feat(legal): confina fuentes y valida Markdown legal`.

## Corte 4 — Reglas cruzadas y matriz de cobertura

### Archivos

Crear:

- `.../legal/manifest/core/LegalManifestContractValidator.java`;
- `.../legal/manifest/core/LegalCoverageMatrix.java`;
- `.../legal/manifest/core/LegalManifestValidator.java`;
- `src/test/resources/legal/manifest/release-valid-v1/...`;
- `LegalManifestContractValidatorTest.java`;
- `LegalManifestValidatorTest.java`;
- `LegalManifestFrontendParityTest.java`.

### Implementación

1. Exigir revisión legal y contable `APPROVED` con referencia, fecha válida y sin marcadores
   editoriales; mapear sólo al plan, sin persistir.
   Los tres contactos del snapshot deben cumplir la misma política pública del guard frontend:
   local-part ASCII válida, dominio DNS con al menos dos labels y rechazo de IPs, hosts internos,
   TLDs reservados y dominios `example.*`. `CONTACT_MISMATCH` permanece fuera del core porque sólo
   compara variables del deploy frontend.
   En v1, `taxId` conserva el contrato congelado de formato `NN-NNNNNNNN-N`; no acredita checksum
   ni existencia ante ARCA. La fixture usa un valor sintético y la revisión profesional sigue siendo
   la responsable de confirmar la identidad real antes de una publicación.
2. Validar unicidad de document key, requirement key, source, combinaciones key+version y
   referencias dentro de cada requisito.
3. Validar locale, contexto, audiencia, acto, statement digest y compatibilidad documental.
   Los campos editoriales obligatorios, cada `statement` y el título H1 deben conservar al menos
   un code point visible después de ignorar whitespace, space separators y las categorías Unicode
   `FORMAT`/`MARK`; texto visualmente vacío siempre bloquea y cualquier control C0/C1 invalida el
   campo completo.
4. Rechazar documento completamente huérfano, pero aceptar uno ligado sólo a requisitos
   opcionales.
5. Permitir varias keys del mismo tipo documental; no copiar
   `MANIFEST_DUPLICATE_DOCUMENT_TYPE`.
6. Congelar los 11 tipos y la matriz mínima:

| Scope obligatorio | Tipos requeridos agregados |
|---|---|
| `REGISTRO / ADMIN_TITULAR` | términos, privacidad, DPA |
| `PRIMER_INGRESO_EMPLEADO / USER` | términos usuario, privacidad usuario, confidencialidad |
| `CONTRATACION_PRO / ADMIN_TITULAR` | términos, condiciones PRO, cancelaciones/reembolsos |
| `ATESTACION_FOTOS / ADMIN_TITULAR,USER` | aviso clientes, atestación |
| `ATESTACION_CREDENCIALES / ADMIN_TITULAR,USER` | aviso clientes, atestación |
| `CIERRE_CUENTA / ADMIN_TITULAR` | cierre de cuenta |

La cobertura se suma entre requisitos `required=true` del scope; no se fuerza un único requisito
monolítico.

7. Derivar scopes y miembros preservando el ordinal global del manifiesto. El plan es inmutable y
   compartido por `validate`, `dry-run` y el futuro importador. Documentos y requisitos conservan
   un `manifestOrdinal` explícito; los requisitos con múltiples audiencias repiten ese mismo ordinal
   en cada scope y los scopes mantienen el orden de primera aparición.
8. Encapsular el plan en un valor acreditado con constructor no público. El dry-run JDBC aceptará
   sólo ese tipo emitido por `LegalManifestValidator`; no records/DTOs fabricables por callers.
   Revalidar defensivamente los límites antes de toda preasignación aunque el schema ya los cubra.
   Esta opacidad evita el uso accidental de DTOs sin validar dentro de la aplicación; no pretende
   aislar código hostil cargado en la misma JVM frente a reflection/`Unsafe` ni es un sello
   criptográfico. La persistencia consumirá únicamente los snapshots inmutables del token y nunca
   reabrirá las rutas, por lo que un cambio posterior del bundle no altera los bytes acreditados.
9. Crear una fixture golden estática de 11 documentos, 6 requisitos y 8 scopes; ningún test positivo
   puede depender del ejemplo editorial incompleto.

### Pruebas y puerta

Cubrir cada fila de matriz, agregación entre requisitos, roles múltiples, docs opcionales, docs
huérfanos, múltiples docs del mismo tipo, referencias/contexts incompatibles, 11 tipos presentes y
paridad/diferencias deliberadas con los 35 códigos legales del guard frontend.

La fixture golden congela además estos fingerprints reproducibles:

```text
manifest raw: 8751 bytes
manifest raw SHA-256: cf1de54de0ebe43e792c15e2d0b4329e2f1e65023d71b0eca689a21e2392b68d
manifest JCS: 6584 bytes
manifest JCS SHA-256: b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1
Markdown total: 672 bytes
```

La prueba backend de paridad es hermética: no busca un checkout hermano. Congela el schema, enums,
matriz, placeholders, vectores Markdown y los 35 códigos del guard; también registra 18 garantías
adicionales del backend y las dos diferencias deliberadas (tipos repetidos y binding sólo opcional).
El espejo frontend se actualiza en un commit propio antes de declarar cerrada la paridad entre
repositorios.

Puerta backend ejecutada con Java 21 después de la auditoría independiente: 91 pruebas enfocadas y
497 pruebas completas, sin fallos, errores ni skips. `git diff --check` queda limpio antes del
commit del corte.

Commit: `feat(legal): valida contrato y cobertura del release`.

## Corte 5 — Contraste PostgreSQL y grafo provisional

### Archivos

Crear bajo `.../legal/manifest/persistence/`:

- `LegalDryRunDatabaseConfiguration.java`;
- `LegalV27SchemaVerifier.java`;
- `LegalDryRunPersistence.java`;
- `LegalManifestDryRunService.java`;
- `LegalDatabaseFailureMapper.java`;
- `LegalDryRunBlockedException.java`;
- `LegalDryRunOperationalException.java`.

Crear pruebas:

- `LegalManifestDryRunIT.java`;
- `LegalManifestDryRunConcurrencyIT.java`.

No modificar entities ni repositories JPA existentes.

### Contexto aislado

Usar configuración explícita, sin `@SpringBootApplication` ni `@ComponentScan`:

```java
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@Import({LegalV27SchemaVerifier.class, LegalDryRunPersistence.class,
         LegalManifestDryRunService.class})
final class LegalDryRunDatabaseConfiguration { }
```

Declarar `JdbcTransactionManager` y `TransactionTemplate`. No importar Flyway, Hibernate, MVC,
Security, Mail, Actuator ni scheduling. `validate` nunca construye este contexto.

### Transacción

Una única transacción:

- `REQUIRES_NEW`;
- `READ_COMMITTED`;
- timeout Spring 45 s;
- `lock_timeout=5s` local;
- `statement_timeout=30s` local;
- `status.setRollbackOnly()` siempre en `finally`.

Antes de escribir, verificar V27 exitosa en `flyway_schema_history`, las 12 tablas usadas y
`legal_validar_publicacion_sellada(uuid)`. No ejecutar Flyway.

Leer una vez `transaction_timestamp()` y usarlo para todos los timestamps provisionales.

### Contraste y escritura provisional

1. Bloquear un `publication_external_id` ya existente; la idempotencia real queda en 2.3B.
2. Buscar líneas documentales y de requisitos por `clave` global, no por key+locale.
3. Para línea existente, comparar identidad completa y exigir publicación introductoria sellada.
4. Para key+version existente, comparar exactamente metadata, bytes, contextos/audiencias y
   relaciones ordenadas. Reutilizar sólo coincidencia exacta.
5. Para versión nueva usar `max(lineage_ordinal)+1`; nunca interpretar el string de versión.
6. Insertar publicación `ABIERTO`, líneas/versiones/hijos nuevos y memberships con ordinales
   contiguos globales.
7. Crear snapshots para cada `(locale, contexto, audiencia)`, con todos los requisitos aplicables y
   ordinales globales que pueden contener huecos.
8. Calcular revisión provisional interna `sha256:<digest(manifest+scope)>`; no exponerla ni prometer
   que será la revisión final.
9. Sellar con `transaction_timestamp()` mediante SQL y exigir una fila actualizada.
10. Ejecutar `SET CONSTRAINTS ALL IMMEDIATE`, construir conteos y revertir.

Tablas provisionales exactas:

```text
legal_publicaciones
legal_documento_lineas
legal_documento_versiones
legal_documento_contextos
legal_publicacion_documentos
legal_requisito_lineas
legal_requisito_audiencias
legal_requisito_versiones
legal_requisito_documentos
legal_publicacion_requisitos
legal_requisito_conjuntos
legal_requisito_conjunto_miembros
```

No tocar transiciones, vigentes, conjuntos actuales, reemplazos, aceptaciones ni idempotencia.

### Traducción SQL

- clase `23`, `22001` y `P0001` del guard legal: `BLOCKED/DB_CONSTRAINT`;
- `25001`: `ERROR/DB_ISOLATION`;
- `55P03`: `ERROR/DB_LOCK_TIMEOUT`;
- `57014`: `ERROR/DB_STATEMENT_TIMEOUT`;
- clase `08`: `ERROR/DB_CONNECTION`;
- `40P01`/clase `40`: `ERROR/DB_CONCURRENCY`;
- V27/objeto ausente: `ERROR/DB_SCHEMA_INCOMPATIBLE`;
- resto: `ERROR/DB_OPERATION_FAILED`.

No inspeccionar el texto del mensaje para decidir reglas, salvo tests internos del SQLSTATE. No
exponer constraint name ni valores.

### Pruebas y puerta

Cubrir PASS y cero filas; fallo diferido y cero filas; reutilización exacta; conflictos de key,
identidad, bytes, fecha, flags, contexts, audiences y docs; publicación externa abierta; V26 sin
migración; aislamiento/timeouts; lock retenido; desconexión; secuencias que pueden avanzar; tablas
no legales sin cambios.

Commit: `feat(legal): simula grafo V27 con rollback obligatorio`.

## Corte 6 — CLI y artefacto ejecutable

### Archivos

Crear bajo `.../legal/manifest/cli/`:

- `LegalManifestCli.java`;
- `LegalManifestArguments.java`;
- `LegalManifestReport.java`;
- `LegalManifestReportWriter.java`.

Modificar:

- `pom.xml`.

Crear pruebas:

- `LegalManifestArgumentsTest.java`;
- `LegalManifestReportWriterTest.java`;
- `LegalManifestCliIsolationIT.java`;
- `LegalManifestCliProcessIT.java`.

### Interfaz

```bash
java -jar target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar \
  validate --manifest=/ruta/release-id/publication-manifest.json

SPRING_DATASOURCE_URL=jdbc:postgresql://... \
SPRING_DATASOURCE_USERNAME=... \
SPRING_DATASOURCE_PASSWORD=... \
java -jar target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar \
  dry-run --manifest=/ruta/release-id/publication-manifest.json
```

No aceptar password por argumento CLI. El parser admite sólo comando y `--manifest`; argumentos
desconocidos bloquean antes de Spring. El path absoluto nunca aparece en stdout.

`validate` ejecuta sólo el core. `dry-run` ejecuta el mismo core y crea después un
`SpringApplicationBuilder` no web, sin args reenviados, banner ni startup info; cierra el contexto
en todos los caminos.

Configurar un segundo `spring-boot:repackage` con classifier `legal-cli` y main class explícita,
manteniendo el jar normal con `MvgrReparacionesBackendApplication`. Extender el check Ant de
`application-secret.properties` a ambos jars.

### Pruebas y puerta

- args válidos/inválidos y manifest faltante;
- stdout JSON único, orden estable y redacción;
- `0/2/3` mediante proceso real;
- validate inválido sin conexión;
- dry-run sin DB da `ERROR` seguro;
- contexto sin Flyway, runners, schedulers, servlet ni web server;
- ambos jars arrancan con su main correcta y no contienen secrets.

Commit: `feat(legal): expone validate y dry-run por CLI aislada`.

## Corte 7 — Paridad frontend, regresión y cierre

### Backend

Modificar:

- `FRONTEND_INTEGRATION.md`;
- diseño/plan si la implementación prueba una diferencia real;
- agregar un cierre de Fase 2.3A bajo `docs/plans/`.

Registrar comandos, formato de reporte, límites, variables DB, garantías de rollback, efectos
físicos posibles (WAL/locks/secuencias), matriz, SHA del schema y pasos reproducibles.

### Frontend

Modificar sólo en commit propio:

- `scripts/lib/public-release-validation.mjs` o su test para congelar el SHA del schema;
- `scripts/check-public-release.test.mjs` para acreditar drift;
- `docs/plans/2026-08-23-lanzamiento-publico-confianza-cuenta-plan.md`;
- `docs/legal/README.md`.

El guard frontend conserva sus restricciones propias. No se cambia a los exit codes del CLI ni se
conecta todavía al backend.

### Verificación final

Backend:

```bash
./mvnw test
./mvnw verify
git diff --check
git status --short --branch
```

Frontend:

```bash
npm test -- --run scripts/check-public-release.test.mjs
npm run build
git diff --check
git status --short --branch
```

Confirmar explícitamente:

- misma fixture produce el mismo plan estático en ambos comandos;
- schema/hash/paridad congelados;
- vectores RFC 8785 verdes;
- PASS, blocker y error tardío dejan cero filas en las 12 tablas;
- ningún runner/scheduler/HTTP/Flyway se activa;
- ninguna ruta/PII/secret aparece en reportes;
- jar principal y frontend no cambian comportamiento fuera del guard acordado;
- worktrees conservan cambios ajenos y no existe push.

Commits de cierre sugeridos:

- backend: `test(legal): endurece CLI y rollback del dry-run`;
- backend: `docs(legal): cierra fase 2.3A`;
- frontend: `test(legal): congela paridad del schema v1`;
- frontend: `docs(plan): registra validador backend 2.3A`.

## Criterio de salida

2.3A termina cuando los cortes anteriores están verificados y documentados en commits locales
atómicos. El resultado acerca el lanzamiento porque elimina una clase importante de errores
editoriales y de persistencia antes de importar, pero no permite publicar todavía: el siguiente
corte es 2.3B, importación idempotente y sello real sin promoción.
