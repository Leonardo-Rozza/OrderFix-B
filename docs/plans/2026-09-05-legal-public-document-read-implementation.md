# Corte 13 — Implementación de lectura pública documental

Fecha: 2026-09-05

Estado: 13A–13B completados y verificados; 13C–13D pendientes.

Diseño aprobado: [lectura pública documental](2026-09-05-legal-public-document-read-design.md),
commit `10bf5b5`. El titular autorizó comenzar 13A el 2026-09-05.

## Baseline y reglas

- Backend `codex/lanzamiento-publico-backend`, HEAD inicial `10bf5b5`, árbol limpio.
- Frontend `codex/frontend-refactor-checkpoint`, HEAD `7545201`; se preservan `.agents/` y
  `public/OrdenFix project naming/`, ambos no versionados.
- V27 y V28 permanecen congeladas durante todo el bloque. En 13A no se cambia esquema,
  provisioning ni permisos; 13B acredita un nuevo rol sólo en bases efímeras.
- Un commit atómico local por subcorte, con whitelist nominal y sin push. No agregar todo el árbol.
- No importar contenido legal real ni habilitar producción. BACKEND-HANDOFF 1 permanece cerrado.
- Pruebas focalizadas por corte; `clean verify` nuevo en 13D o ante un cambio transversal/fallo que
  justifique ampliar la verificación. El baseline 12G fue 4366 Surefire + 306 Failsafe sin fallos.

## 13A — Proyección y revisión documental

Resultado: tipos puros que representan los resúmenes de un filtro completo y calculan
`documentSetRevision` por una sola pasada. No incorpora JDBC, controllers, DTO HTTP, configuración,
flags ni cambios frontend. La paginación y la selección de filas pertenecen a los cortes siguientes.

### Pasos y decisiones

1. Crear `LegalDocumentSummary` con UUID, tipo, versión, título, SHA-256, `Instant`, estado y locale.
   Sólo admite estados públicos y metadatos representables. No recibe Markdown ni acredita su
   digest de contenido: ésa es una responsabilidad distinta del lector/publicador.
2. Validar versión de hasta 64 codepoints y título de hasta 300, no vacíos tras `btrim` de espacios
   U+0020, conforme a V27. No reutilizar el límite de versión del manifiesto ni contar unidades
   UTF-16. Rechazar NUL y surrogates aislados. Conservar Unicode, espacios y escapes originales;
   no normalizar ni imponer NFC a metadata histórica que la base no restringe así.
3. Rechazar fechas fuera de los años RFC 3339 0000–9999 y fracciones menores al microsegundo;
   no redondear. `effectiveAtUtc()` usa `DateTimeFormatter.ISO_INSTANT` y será el render que debe
   reutilizar el DTO de 13C. UUID usa `UUID.toString()`.
4. Crear `LegalDocumentCatalogProjection` con contexto opcional, locale obligatoria e iterator
   exclusivo no consumido. Construirla no consulta filas. Se reclama una sola vez, incluso si
   falla la lectura; un segundo intento no puede acreditar sólo el sufijo de un cursor.
   El caller posee/cierra los recursos, también al fallar; la proyección no es una colección
   inmutable reutilizable ni un propietario de recursos JDBC.
5. Extender `Rfc8785Canonicalizer` con una entrada tipada y writer documental nuevos. Mantener
   intactos los writers y bytes existentes. El camino de producción emite al digest con buffer
   fijo; sólo el helper package-private de golden materializa bytes.
6. Validar durante el recorrido locale y orden estricto: tipo por enum, fecha descendente y UUID
   ascendente por comparación unsigned de ambas mitades. Rechazar la primera inversión o posición
   repetida. Conservar sólo la fila anterior; sin sort, lista completa ni límite de manifiesto.
7. Rechazar catálogo vacío; nunca entregar una revisión de disponibilidad vacía. Una página vacía
   posterior al final sí podrá responder con la revisión del catálogo completo en 13B/13C.
   Propagar fallos del iterator sin retry ni digest parcial. Contexto/locale integran el hash.
8. Crear golden independientes para bytes, digest, fechas, UUID, Unicode/escapes y estados históricos.
   Verificar mutaciones, uso único, orden, errores y una historia generada mayor a 128 filas.
9. Ejecutar pruebas nuevas y regresiones focalizadas de los cinco calculadores/adaptador existentes.
   Revisar diff, whitelist, hashes V27/V28 y estado frontend antes del commit.

La unicidad global del UUID y la pertenencia al contexto requieren la consulta SQL de 13B. El núcleo
sólo observa metadata documental; no inventa contextos ni un `HashSet` creciente para suplir esa
garantía. Orden estricto acredita posiciones, no identidad global ante filas adulteradas. No se
agrega un contador/página en 13A: 13B compone el recorrido con conteos comprobados y retención de
su página. El calculador no puede descubrir un truncamiento silencioso del proveedor; 13B debe
acreditar el recorrido íntegro del cursor y su deadline antes de devolver la respuesta.

### Whitelist 13A

Prefijo Java: `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/`.

- `LegalDocumentCatalogProjection.java` (nuevo).
- `LegalDocumentSummary.java` (nuevo).
- `LegalDocumentSetRevisionCalculator.java` (nuevo).
- `Rfc8785Canonicalizer.java` (extensión tipada aditiva).
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalDocumentCatalogProjectionTest.java`.
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalDocumentSetRevisionCalculatorTest.java`.
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/Rfc8785CanonicalizerTest.java`
  (allowlist exacta de entradas, agregando sólo el tipo documental aprobado).
- Seis fixtures bajo `src/test/resources/legal/manifest/document-set-v1/`:
  `all-contexts-projection.json`, `all-contexts-canonical.json`, `all-contexts-sha256.txt`,
  `registration-projection.json`, `registration-canonical.json`, `registration-sha256.txt`.
- Este plan y la actualización de estado del diseño aprobado.

### Comandos focalizados

Desde backend, con Java 21:

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dtest=LegalDocumentCatalogProjectionTest,LegalDocumentSetRevisionCalculatorTest test
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dtest=Rfc8785CanonicalizerTest,LegalRequiredSetRevisionCalculatorTest,LegalRequiredSetAggregateRevisionCalculatorTest,LegalRequiredSetAggregateProvenanceCalculatorTest,LegalEditorialStateFingerprintCalculatorTest test
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw '-Dtest=com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.*Test' test
git diff --check
git diff --cached --name-only
git diff --cached --check
```

Commit previsto: `feat(legal): calcula revision documental publica`.

### Evidencia de 13A

Primera ejecución: 66 pruebas nuevas, sin fallos, errores ni omisiones (15.243 s, Java 21).
La regresión de 79 pruebas detectó una única expectativa de superficie pendiente de actualizar:
`Rfc8785CanonicalizerTest` enumeraba sólo los cinco tipos anteriores. Se agrega exclusivamente la
proyección documental aprobada; se conservan todos los tipos previos y la prohibición de entradas
genéricas. Todos los vectores anteriores pasaron. Se amplió la validación al paquete core completo,
incluyendo las pruebas nuevas, para cerrar esa modificación de la allowlist.

Resultado final: **450 pruebas, 0 fallos, 0 errores, 0 omitidas**, `BUILD SUCCESS` en 12.599 s,
finalizado el 2026-09-05 a las 12:11:20 -03. Este total incluye las 66 pruebas nuevas y las 79 de
regresión canónica. Se usó Corretto 21.0.10. No se modificaron los writers existentes ni los vectores
congelados; el fallo de enumeración se resolvió ampliando exclusivamente la allowlist autorizada.
No se requirió `clean verify`: se acreditó el núcleo afectado completo, sin cambios de persistencia,
seguridad o transporte. El gate integral del bloque permanece pendiente para 13D.

Los golden fijan estas revisiones con seis resúmenes públicos/históricos en cada proyección:

| Filtro | Bytes canónicos sin LF final del fixture | `documentSetRevision` |
| --- | --- | --- |
| Contexto ausente | 1807 | `sha256:76420473f017cf5f0fad90635a166a0f3d449fe40c21367afa01302dc3137039` |
| REGISTRO | 1813 | `sha256:e9227403a61dce248feb37e7877177a953cc786c07dc692bdb86bbf88e92f214` |

Las pruebas acreditan una pasada de 4097 resúmenes generados, influencia de la última fila y
rechazo de fallos/orden inválido incluso al final. La memoria auxiliar constante se verifica por
inspección del writer y del buffer fijo; no se presenta como una medición de heap ni prueba JDBC.
No se acredita todavía filtro SQL, unicidad global, paginación HTTP, permisos, cancelación o latencia.

Revisión independiente de implementación, casos límite y plan completada. `git diff --check` y
revisión nominal antes de commit sin incidencias. Hashes preservados:

- V27 SHA-256: `52fd5f3eda14fde228e218f127b5e9362c8542dc7e26df7b502ba65061332b9b`.
- V28 SHA-256: `1227c8261cfcca1263a0b2105bf0dc797c1f59f3b5bdc71225464fc4aa154a5e`.

El frontend conserva `7545201` y sus directorios no versionados. Próximo corte: 13B, lector
PostgreSQL con credencial restringida, preflight propio y gate read-only compartido.

## 13B — Lector PostgreSQL restringido

Baseline 13B: `67a9580`, árbol limpio. El titular autorizó continuar el 2026-09-05.
Dependencia 13A cumplida. El contexto se registra explícitamente y no se descubre por component scan;
su habilitación interna no agrega mappings HTTP ni altera el flag público previsto para 13C.

### Whitelist 13B antes de implementación

Prefijo producción: `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/`.

- `LegalPublicDocumentPrivilegeVerifier.java`, preflight aislado con allowlist propia.
- `LegalPublicDocumentReader.java`, `LegalPublicDocumentCatalog.java`, `LegalPublicDocumentVersion.java`.
- `LegalPublicDocumentReadService.java`, `LegalPublicDocumentReadException.java`.
- `LegalPublicDocumentDeadline.java`, `LegalPublicDocumentDataSource.java`.
- `LegalPublicDocumentReadDatabaseConfiguration.java`, composición explícita con propiedades propias.
- `LegalManifestDatabaseGate.java`, variante read-only shared aditiva y acreditación del par lector.
- `LegalDatabaseBoundaryMarker.java`, nueva frontera `PUBLIC_DOCUMENT_READ`.
- Tests bajo el mismo paquete: `LegalRestrictedPublicDocumentRoleFixture.java`,
  `LegalPublicDocumentPrivilegeVerifierIT.java`, `LegalPublicDocumentCatalogTest.java`,
  `LegalPublicDocumentReadServiceIT.java`, `LegalPublicDocumentReadDatabaseConfigurationTest.java`,
  `LegalPublicDocumentDeadlineTest.java`, `LegalPublicDocumentDataSourceTest.java`,
  `LegalPublicDocumentReadConcurrencyIT.java`, `LegalPublicDocumentReadITSupport.java`,
  `LegalManifestDatabaseGateTest.java` y `LegalDatabaseBoundaryMarkerTest.java`.
- Este plan y la actualización de estado del diseño. No migraciones, frontend ni HTTP.

Se mantienen los verificadores/roles existentes. El preflight nuevo replica la acreditación efectiva
aislada del patrón V28 para evitar parametrizar o ampliar la allowlist del materializador.

### Ejecución

1. Agregar frontera aislada `PUBLIC_DOCUMENT_READ`, credencial dedicada y transacción efectiva
   `REQUIRES_NEW/READ_COMMITTED/read-only`, sin fallback web/owner ni migración automática.
2. Reutilizar acreditación V27/V28 y crear preflight propio de privilegios efectivos: SELECT sólo
   sobre las tres tablas documentales y Flyway; sin DML, usuarios/evidencia ni EXECUTE legal.
   Probar revocaciones/regrants de PUBLIC en bases efímeras; inventariar consumidores antes de
   cualquier modificación futura en una base compartida. No editar V27/V28 ni rol materializador.
3. Añadir variante explícita shared al gate read-only, preservando sus callers exclusivos.
4. Implementar cursor ordenado sin Markdown y con EXISTS por contexto histórico; una fila por
   versión. Calcular revisión, total y página en la misma observación posterior al lock. Mantener
   únicamente fetch acotado, página de hasta 100, fila anterior y digest; validar conteos/overflow.
5. Leer documento exacto en una proyección protegida. Diferenciar ausencia pública de fallo de
   contrato, privilegios o base. Aplicar disponibilidad del catálogo completo antes de paginar.
6. Acotar adquisición/driver/sentencias/locks; deadline cooperativo incluye borrow hasta commit.
   Acreditar cierre/cancelación y fallo sin respuesta parcial. Registrar latencia de cancelación
   aparte del presupuesto; ningún chequeo Java promete interrumpir un driver bloqueado.
7. Gate focal PostgreSQL 16: datos visibles/históricos, contexto, orden real UUID, más de 128 filas,
   ausencia de DML, rol restringido, dos lectores shared, writer bloqueado y coherencia tras cambio.

### Decisiones implementadas en 13B

- Frontera `PUBLIC_DOCUMENT_READ`, registrada explícitamente mediante
  `LegalPublicDocumentReadDatabaseConfiguration`, sin `@Configuration`/component scan ni
  autoconfiguración web, JPA o Flyway. Su propiedad interna es
  `ordenfix.legal.public-document-read-context.enabled=true`; ausente/false no crea beans.
- `ordenfix.legal.public-document-read.jdbc-url`, `.username` y `.password` son obligatorias;
  no hay fallback a `spring.datasource.*`. El esquema de esta lectura es `public`. La URL acepta
  opciones TLS explícitas y `loggerLevel`; rechaza overrides de credencial, driver, search_path
  y timeouts que pudieran anular los presupuestos del pool.
- Pool dedicado de dos conexiones, minIdle 0, adquisición 1000 ms; driver con connect/login 1 s,
  socket 5 s y cancelSignal 1 s. El contexto no conecta/migra al crearse. El preflight acredita
  la credencial real, PostgreSQL 16 y el esquema V27/V28 antes de leer documentos.
- Transacción `REQUIRES_NEW/READ_COMMITTED`, read-only declarado y efectivo, con enforceReadOnly
  y par exacto esquema/privilegios sobre el mismo JdbcTemplate. El gate nuevo usa shared;
  executeReadOnly y reconciliación histórica conservan exclusive y sus presupuestos anteriores.
- El rol sólo recibe SELECT sobre líneas, versiones, contextos documentales y Flyway. Se comprueban
  privilegios efectivos de relaciones/columnas, funciones y capacidades sistémicas; no basta con
  que los grants directos parezcan restringidos. La fixture rechaza bases ajenas al prefijo
  `ordenfix_legal_public_document_`; revoca PUBLIC sólo dentro de los clusters de prueba.
  Ninguna ACL de una base compartida ni rol existente del producto se modifica en este corte.
- Catálogo con cursor forward-only/read-only, fetch 128 y consulta sin Markdown. EXISTS filtra
  contextos históricos sin multiplicar versiones. Revisión, total y página se resuelven en una
  pasada después del shared lock. La página retiene hasta 100 elementos; no hay límite de 128
  versiones históricas. Conteos/offsets y totalPages usan aritmética comprobada y números exactos
  para JavaScript. Catálogo vacío falla; página posterior al final conserva revisión/conteos.
- La versión exacta distingue ausencia pública de fallo. Obtiene resumen y bytes UTF-8 en una
  proyección; CASE limita el contenido transferido a 1 MiB. `CanonicalTextValidator` acredita
  digest y texto original antes de devolverlo. UUID oculto/desconocido produce Optional.empty
  sólo después de finalizar correctamente la transacción; drift/corrupción no se convierten en ausencia.
- El deadline monotónico de 15 s empieza antes del borrow y abarca preflights, lock, cursor,
  canonicalización, commit y cleanup. Los scopes internos anidados conservan el mismo deadline.
  Se ajustan timeout PostgreSQL/JDBC y socket al tiempo restante; el watchdog cancela la sentencia
  activa y aborta el lease al vencer. El monitor de cierre impide abortar una conexión ya devuelta
  al pool; los proxies de metadata/cursor tampoco exponen su delegado. Todo recurso se cierra
  al fallar, conservando la causa primaria. El facade no devuelve un resultado vencido ni hace retry.
- `LegalPublicDocumentReadException` separa indisponibilidad de ausencia con mensaje fijo y causa
  interna. Los DTO wire/ApiError HTTP quedan en 13C. No se registra ningún controller ni endpoint.

### Evidencia focal de 13B

- 113 pruebas unitarias focalizadas aprobadas (gate/marker, resultados, configuración, deadline y
  recursos), 0 fallos/errores/omitidas. Se corrigió una inferencia genérica ambigua de AssertJ durante
  testCompile; no requirió modificar producción ni relajar una expectativa.
- 28 IT de privilegios aprobados con PostgreSQL 16, incluidas ACL PUBLIC/columnas, ownership,
  funciones, faltantes, memberships, search_path y denegación real de DML/DDL/lecturas ajenas.
- 16 IT funcionales + 5 IT de concurrencia/cancelación aprobados. Import, PROMOTE, REPLACE y retiro
  de fixtures usan los caminos/guards editoriales reales; sólo las pruebas explícitas de corrupción
  alteran datos como owner dentro de la base efímera.
- Historia de 143 versiones, orden SQL real, filtro histórico, revisión estable entre páginas,
  página vacía, contenido exacto, corrupción con rollback y credencial/READ_COMMITTED/read-only reales.
- Dos catálogos avanzan con shared; un REPLACE real preparado previamente espera a ambos. Tras su
  commit el lector observa revisión, versiones y conteos nuevos juntos. Lock timeout se acredita
  con SQLSTATE `55P03`; saturación del pool falla en su presupuesto de adquisición.
- Con presupuesto de prueba 2 s, sentencia bloqueada finalizó en 2011 ms y cursor lento en 2023 ms,
  tras consumir 128 filas en batches de 32. En ambos casos se verifican liberación de advisory lock
  y cero conexiones activas en el pool afectado. Son observaciones de prueba: el exceso medido de
  11/23 ms incluye detección/cancelación/teardown, no es una latencia de cancelación aislada ni SLA.
  Los límites de conexión, sentencia y cancelSignal siguen siendo independientes del deadline;
  un driver bloqueado no se interrumpe por el solo chequeo Java.
- Revisión independiente de SQL/roles, frontera, composición, lifecycle y pruebas realizada.
  Se cerró la ruta metadata→ResultSet→Statement→Connection y se agregó su prueba de regresión.
- Regresión focal de callers históricos: 39 pruebas unitarias y 27 IT aprobados (readiness,
  reconciliación y materialización V28), sin fallos/errores/omitidas. El último comando terminó
  el 2026-09-05 a las 12:38:34 -03, `BUILD SUCCESS`, 30.075 s.
- Total sin contar ejecuciones repetidas: **152 pruebas unitarias + 76 IT = 228**, todas aprobadas.
  Se usó Corretto 21.0.10 y PostgreSQL 16.14 (`postgres:16-alpine`). El gate integral queda en 13D.
- `git diff --check` sin incidencias; V27/V28 mantienen los SHA-256 registrados en 13A. El frontend
  permanece en `7545201`, incluidos sus archivos no versionados; no se modificó ni hizo push.

Comandos de validación desde backend, con Java 21:

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dtest=LegalPublicDocumentDeadlineTest,LegalPublicDocumentDataSourceTest,LegalPublicDocumentReadDatabaseConfigurationTest,LegalPublicDocumentCatalogTest,LegalManifestDatabaseGateTest,LegalDatabaseBoundaryMarkerTest test
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dit.test=LegalPublicDocumentPrivilegeVerifierIT,LegalPublicDocumentReadServiceIT,LegalPublicDocumentReadConcurrencyIT test-compile failsafe:integration-test failsafe:verify
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dtest=LegalRequiredSetAggregateDatabaseConfigurationTest,LegalRequiredSetAggregateServiceTest,LegalEditorialApplyServiceReconciliationTest,LegalEditorialTransactionBoundaryTest,LegalImportTransactionBoundaryTest,LegalEditorialReadinessCoreTest -Dit.test=LegalRequiredSetAggregateServiceIT,LegalEditorialReadinessIT,LegalEditorialReconciliationIT test failsafe:integration-test failsafe:verify
```

El segundo comando permite reproducir juntos los tres IT nuevos, ejecutados inicialmente en dos
grupos (28 y 21) para aislar el gate de privilegios del funcional. No se ejecutó `clean verify`.
No hay cambios de migraciones ni seguridad HTTP; la regresión se concentra en los callers del gate.

Commit previsto: `feat(legal): lee documentos con rol restringido`.

## 13C — Transporte público y políticas conjuntas

Dependencia: lector 13B acreditado. Cerrar whitelist antes de editar seguridad/HTTP.

1. Crear sólo los dos GET documentales y sus DTO wire, reutilizando el formato canónico de fechas.
2. Flag apagado por defecto: sin mappings ni excepciones de autenticación nuevas cuando está off.
   Datasource explícito al encender; no ampliar `/api/public/**`.
3. Compartir clasificación exacta GET/path/context path entre SecurityConfig, JwtFilter y rate
   policy. HEAD no hereda GET; OPTIONS permanece en CORS. No incorporar requisitos públicos aún.
4. Aplicar rate limit, CORS, errores ApiError, ETags y caché conforme al diseño en el mismo corte.
   UUID malformado/oculto/desconocido conserva el mismo 404; resolver/acreditar antes de un 304.
5. Gate focal MockMvc/seguridad y lectura real: flag off/on, variantes UUID, query y path vecinos,
   400/404/503/429, headers, condicionales débiles/listas/*, errores no-store. Regresión obligatoria
   de AuthTests, JwtSecurityIntegrationTests, PublicEndpointRateLimitFilterTests y ApiErrorContractTests.

## 13D — Capacidad y cierre integral

1. Acreditar concurrencia HTTP, historia grande, cursor/fetch, cancelación, deadline y conteos extremos.
2. Completar contrato/inventario/documentación con evidencia de cada corte y riesgos remanentes.
3. Ejecutar `clean verify` con PostgreSQL 16, conservar conteos exactos y hashes de migraciones.
4. Commit local de cierre; no habilitar producción ni abrir handoff sólo por completar estos dos GET.

Requisitos, aceptación/registro y enforcement requieren bloques separados. Se mantiene la semántica
V28 de agregados completos y el registro histórico mientras esos contratos no estén implementados.
