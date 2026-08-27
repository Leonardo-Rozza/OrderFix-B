# Fase 2.3B — Plan de implementación de la importación legal

Fecha: 2026-08-25

Estado: Cortes 1 a 5 completados; Cortes 6 a 7 pendientes

Diseño aprobado:

- `docs/plans/2026-08-25-legal-manifest-import-design.md`;
- commit local backend `a8cc352`;
- precisión posterior del contrato CLI en `cf2aaaf`;
- continuidad de la Fase 2.3A cerrada en `ec794cb` y del mirror frontend en `19b4953`.

Este plan agrega una importación interna, idempotente y transaccional al jar legal existente. No
promueve documentos, no publica endpoints, no conecta UI, no habilita aceptaciones y no cambia
`BACKEND-HANDOFF 1`. Cada corte termina en un commit local atómico. No se hace push.

Antes del Corte 1, este documento se registra por separado con el commit
`docs(legal): planifica importacion idempotente del manifiesto`. Ningún archivo funcional participa
de ese commit de planificación.

## Reglas de ejecución

1. Trabajar en backend sobre `codex/lanzamiento-publico-backend` y en frontend sobre
   `codex/frontend-refactor-checkpoint`.
2. No mezclar archivos no versionados del frontend: `.agents/` y
   `public/OrdenFix project naming/` permanecen ajenos.
3. Empezar cada corte con tests que demuestren la nueva invariantes o preserven una salida previa.
4. Mantener `validate` y `dry-run` en reporte v1 byte-compatible. Un cambio de formato v1 bloquea
   el corte aunque el JSON siga siendo semánticamente equivalente.
5. No agregar `commit=true`, endpoint HTTP, controller, scheduler, runner ni botón de importación.
6. No introducir flags de fallo productivos. Los límites y fallos controlados se inyectan sólo por
   constructores package-private o dobles de test.
7. No importar los borradores legales reales. La fixture golden de test sigue siendo sintética.
8. Antes de cada commit: revisar el diff exacto, ejecutar la puerta enfocada y `git diff --check`.
9. Al final: `./mvnw verify`, regresión frontend, documentación de evidencia y estado limpio salvo
   los archivos ajenos ya identificados.

## Invariantes congeladas

- Entrada única: `LegalManifestValidator.ValidatedRelease`.
- Base mínima: PostgreSQL 16 con V27 exacta; el comando no ejecuta Flyway.
- Transacción: `REQUIRES_NEW`, `READ_COMMITTED`, timeout total 75 s.
- Lock editorial: `lock_timeout=30s` para adquirir
  `ordenfix:legal-publicaciones:sello:v1`; después `lock_timeout=5s` y
  `statement_timeout=30s`.
- Persistencia fresca: un grafo completo, sello técnico y commit único; versiones nuevas en
  `BORRADOR`.
- Replay exacto: cero escrituras y cero avance de secuencias; compara sólo el grafo inmutable de
  origen y no exige estado editorial `BORRADOR`.
- Idempotencia: `publication_external_id` más cabecera/canónico/digest/grafo, sin ledger HTTP.
- Reporte import v2: tri-state `persisted`; `UNKNOWN` sólo ante una finalización DB realmente
  ambigua.
- Credenciales: sólo `ORDENFIX_LEGAL_IMPORT_DB_*`, nunca argumentos ni system properties JVM.
- Permisos: rol no owner, sin promoción y con grants técnicos de row lock limitados por columna.
- Operación: interna de plataforma, sin autorización ADMIN/USER y sin superficie web.

## Corte 1 — Revisión definitiva RFC 8785 por scope

Estado: completado el 2026-08-25.

### Objetivo

Reemplazar la revisión provisional del dry-run por la misma revisión productiva que persistirá el
importador. El reporte v1 no empieza a exponerla.

### Archivos

Crear:

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalRequiredSetProjection.java`;
- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalRequiredSetRevisionCalculator.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalRequiredSetRevisionCalculatorTest.java`;
- `src/test/resources/legal/manifest/required-set-revision-v1/projection.json`;
- `src/test/resources/legal/manifest/required-set-revision-v1/canonical.json`;
- `src/test/resources/legal/manifest/required-set-revision-v1/sha256.txt`.

Modificar:

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/Rfc8785Canonicalizer.java`;
- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalDryRunPersistence.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestDryRunIT.java`.

### Implementación

1. Modelar la proyección exacta sin `requiredSetRevision`:
   `contexto, locale, requisitos[]`; cada requisito contiene
   `id, contexto, tipoActo, afirmacion, afirmacionSha256, documentos[], requerido`; cada documento
   contiene `id, tipo, version, titulo, contenidoMarkdown, sha256, vigenteDesde, estado, locale`.
2. Rechazar propiedades opcionales, maps sin orden o DTOs HTTP. Usar records inmutables y listas
   defensivas.
3. Ordenar requisitos por `manifest_ordinal` y documentos por `documento_ordinal`.
4. Usar UUID definitivos ya resueltos y proyectar `estado="VIGENTE"` sin modificar la fila
   `BORRADOR`.
5. Normalizar `vigenteDesde` a `Instant` UTC, máximo seis dígitos fraccionarios.
6. Reutilizar el adaptador RFC 8785 existente con una entrada exclusivamente tipada que emite el
   canónico directo a SHA-256; no materializar el scope productivo ni aceptar texto externo sin el
   parser estricto. Acreditar su equivalencia byte a byte contra JCS.
7. Calcular `sha256:<64-hex>` sobre los bytes UTF-8 canónicos, sin dominio provisional.
8. Inyectar el calculator en `LegalDryRunPersistence` y eliminar
   `ordenfix:legal-dry-run-scope:v1`.
9. Reconstruir la proyección desde PostgreSQL en el IT y exigir el mismo hash persistido.
10. Aplicar antes de la proyección el presupuesto de 16 MiB de Markdown expandido por scope; cada
    aparición documental cuenta y el calculator repite el guard sin asignar el JSON.

### Pruebas y puerta

Cubrir:

- vector golden exacto de proyección, canonical y SHA;
- equivalencia del writer streaming con JCS para escapes, controles y Unicode astral;
- orden de propiedades/arrays y estabilidad ante orden de entrada distinto;
- cambio de UUID, contenido, digest, afirmación, requerido, timestamp o documento cambia el hash;
- `BORRADOR` almacenado se proyecta como `VIGENTE`;
- timestamp con offset equivalente produce la misma salida UTC;
- precisión superior a microsegundos continúa bloqueada por el gate DB;
- exactamente 16 MiB expandidos pasa, 16 MiB más una aparición bloquea antes de JCS y una suma
  artificial que desborda `long` continúa fallando cerrada;
- 8 scopes golden tienen revisiones reproducibles y el dry-run sigue rollback-only.

```bash
./mvnw -Dtest=LegalRequiredSetRevisionCalculatorTest test
./mvnw -Dit.test=LegalManifestDryRunIT verify
git diff --check
```

### Evidencia del Corte 1

- golden canónico fijado en
  `sha256:e5dbad24bbcbdb35bbd0f76fe4969d23d298db086910d8593fb6576d0550aa5c`;
- writer tipado equivalente byte a byte con JCS para escapes, controles y Unicode astral;
- caso máximo de 16 MiB Markdown —aproximadamente 32 MiB escapados— calculado con `-Xmx128m`
  sin materializar el JSON y sin `OutOfMemoryError`;
- 8 scopes frescos reconstruidos desde PostgreSQL y 8 scopes con versiones históricas reutilizadas,
  conservando UUID, `manifest_ordinal` y `documento_ordinal`;
- 17 versiones nuevas acreditadas en `BORRADOR`, aunque el contrato canónico proyecta `VIGENTE`;
- transacciones de evidencia marcadas rollback-only y cero delta residual en tablas legales;
- reportes v1 sin campos nuevos y parser externo todavía confinado a `StrictJsonReader` más JCS.
- verificación completa: 576 pruebas unitarias y 16 pruebas de integración, todas sin fallos.

Commit: `feat(legal): calcula revision definitiva de requisitos`.

## Corte 2 — Gate y writer compartidos

Estado: completado el 2026-08-25.

### Objetivo

Separar coordinación transaccional, escritura del grafo y semántica de dry-run sin cambiar ninguna
salida v1 ni confirmar datos.

### Archivos

Crear bajo `.../legal/manifest/persistence/`:

- `LegalDatabasePreflight.java`;
- `LegalManifestDatabaseGate.java`;
- `LegalManifestGraphWriter.java`;
- `LegalManifestGraphReceipt.java`;
- `LegalDatabaseBudgets.java`;

Modificar:

- `LegalDryRunPersistence.java` — reducir y retirar al completar la extracción;
- `LegalManifestDryRunService.java`;
- `LegalDryRunDatabaseConfiguration.java`;
- `LegalV27SchemaVerifier.java`;
- `LegalDatabaseFailureMapper.java` — revisado; no requirió cambios de códigos ni mensajes v1;
- `LegalManifestDryRunIT.java`;
- `LegalManifestDryRunConcurrencyIT.java`;
- tests CLI v1 que congelen bytes.

### Implementación

1. Hacer que `LegalManifestDatabaseGate` reciba `TransactionTemplate`, presupuestos fijos y una
   lista explícita de preflights. No conoce reportes ni comandos.
2. Dentro de `READ_COMMITTED`, aplicar `statement_timeout=30s`, ejecutar preflights de catálogo,
   aplicar `lock_timeout=30s`, adquirir el advisory xact lock y recién entonces bajar a 5 s.
3. Mantener los presupuestos productivos constantes. `LegalDatabaseBudgets` sólo admite valores
   menores por constructor package-private en tests.
4. Mover a `LegalManifestGraphWriter` exclusivamente resolución de identidades, locks de filas,
   UUID/ordinales, inserts, revisión, sello, constraints y validación V27.
5. El writer recibe una identidad externa ya declarada inexistente bajo el gate. No consulta replay
   ni configura transacción/permisos.
6. Devolver `LegalManifestGraphReceipt` con UUID, timestamps y conteos internos. No decidir commit,
   rollback o status de reporte.
7. `LegalManifestDryRunService` entra al gate, invoca writer y marca rollback-only en `finally`.
8. Conservar mapping y mensajes DB v1 exactos. No renombrar códigos existentes que dicen
   “simulación”.
9. Hacer que el dry-run cooperativo use el advisory lock desde antes de leer identidades.

### Pruebas y puerta

- El writer aislado no ejecuta `SET` de sesión/transacción, advisory lock, schema ni permisos; sólo
  fuerza el `SET CONSTRAINTS ALL IMMEDIATE` que forma parte de su responsabilidad V27.
- El gate ejecuta preflights y lock antes del primer query del grafo.
- PASS, BLOCKED y ERROR del dry-run producen exactamente los mismos bytes v1 previos.
- Las 12 tablas quedan sin delta; las secuencias pueden avanzar como ya estaba documentado.
- Los tests concurrentes previos siguen pasando bajo el lock global.
- Una excepción del writer sale de la transacción y se mapea después del rollback.

```bash
./mvnw -Dtest=LegalManifestReportTest,LegalManifestReportWriterTest,LegalManifestCliTest test
./mvnw -Dit.test=LegalManifestDryRunIT,LegalManifestDryRunConcurrencyIT verify
git diff --check
```

### Evidencia del Corte 2

- gate compartido con orden acreditado
  `statement_timeout -> preflights -> lock_timeout=30s -> advisory xact lock -> lock_timeout=5s -> callback`;
- transacción productiva congelada en `REQUIRES_NEW`, `READ_COMMITTED`, timeout total de 75 s, con
  presupuestos package-private que sólo permiten reducciones de test;
- consulta de `publication_external_id` dentro del gate y antes del writer; un fallo del writer
  cruza la frontera transaccional, completa rollback y recién entonces se traduce al reporte;
- writer aislado sin SQL de gate, catálogo, privilegios o replay; sello, constraints inmediatas,
  validación V27 explícita, comprobación `BORRADOR` y receipt con timestamps releídos desde DB;
- dos dry-runs con orden inverso acreditan exactamente un writer dentro del grafo y otro esperando
  el lock editorial; ambos terminan PASS y rollback-only;
- lock editorial retenido con presupuestos reducidos `statement=2s/lock=1s` produce
  `DB_LOCK_TIMEOUT` antes del grafo, sin delta de tablas ni secuencias, y el retry posterior pasa;
- PASS, BLOCKED y ERROR obtenidos del servicio/gate reales se renderizan byte a byte como reporte
  v1; el jar conserva aislamiento, procesos reales, exit codes y una única LF en la frontera CLI;
- verificación completa sobre PostgreSQL 16.14: 588 pruebas unitarias y 60 pruebas de integración,
  todas sin fallos; los IT focalizados del corte aportan 17 casos de grafo y 8 de concurrencia;
- ninguna migración, endpoint, importación real, cambio frontend, deploy o push formó parte del
  corte.

Commit: `refactor(legal): comparte gate y writer del manifiesto`.

## Corte 3 — Servicio idempotente y frontera de commit

Estado: completado el 2026-08-25.

### Objetivo

Implementar la semántica de importación sin exponer todavía el comando ni abrir un contexto DB de
producción.

### Archivos

Crear bajo `.../legal/manifest/persistence/`:

- `LegalManifestImportService.java`;
- `LegalManifestImportResult.java`;
- `LegalManifestReplayVerifier.java`;
- `LegalImportTransactionState.java`;
- `LegalImportFailureMapper.java`;
- `LegalImportBlockedException.java`;
- `LegalImportOperationalException.java`;

Modificar:

- `LegalManifestIssueCode.java` para agregar códigos `IMPORT_*` sin tocar valores v1;
- `LegalManifestDatabaseGate.java` sólo si necesita exponer el callback protegido;
- `LegalManifestGraphReceipt.java` para el receipt confirmado;
- `LegalManifestGraphWriter.java` sólo para datos necesarios por el replay.

Crear pruebas:

- `LegalManifestImportServiceTest.java`;
- `LegalManifestImportIT.java`;
- `LegalImportTransactionStateTest.java`.

### Implementación

1. Exigir `ValidatedRelease` y un gate configurado con preflight de acceso; no ofrecer constructor
   que acepte plan/DTO crudo.
2. Mantener servicio, replay verifier y tracker sin annotations/component scan. En este corte no se
   registra ningún bean importador; los tests ensamblan explícitamente un preflight real o doble
   cerrado y el contexto restringido llega recién en el Corte 4.
3. Bajo el advisory lock, buscar `publication_external_id`:
   - ausente: writer y `IMPORTED`;
   - `SELLADO` exacto: verifier y `ALREADY_IMPORTED`;
   - cabecera/canónico/digest/grafo distinto: `IMPORT_DB_PERSISTED_CONFLICT`;
   - `ABIERTO`: error operativo manual, sin completar ni borrar.
4. En replay, comparar cabecera inmutable y cada nodo derivado del manifiesto. Reejecutar
   `legal_validar_publicacion_sellada(uuid)` y reconstruir revisiones.
5. Ignorar estados editoriales mutables, transiciones, slots y punteros. Una publicación promovida,
   reemplazada o retirada en tests continúa siendo replay exacto.
6. No invocar writer ni `nextval` en replay. Medir antes/después las seis secuencias con observer de
   test.
7. Registrar mediante `TransactionSynchronization` las fases: callback iniciado, receipt entregado,
   frontera conservadora `beforeCommit`, `COMMITTED`, `ROLLED_BACK` o `UNKNOWN`. No llamar
   «commit intentado» a `beforeCommit`: Spring lo ejecuta antes de `Connection.commit()`.
8. Congelar la matriz por fase: antes de `beforeCommit`, incluido un receipt entregado, el resultado
   es `persisted=false/import:null`; una excepción tras esa frontera sin completion o cualquier
   `STATUS_UNKNOWN` produce `persisted=null/UNKNOWN`; rollback confirmado es false y commit
   confirmado es true.
9. Sólo devolver `persisted=true` después de retorno normal del transaction manager o replay
   acreditado. Una excepción con rollback confirmado produce false; frontera de commit sin
   completion o cualquier `STATUS_UNKNOWN` produce unknown.
10. Mantener el receipt fuera de factories capaces de fabricar combinaciones inválidas.
11. No hacer retry automático. El caller reconcilia repitiendo exactamente el release.

### Pruebas y puerta

- import fresco deja las 12 tablas completas, publicación `SELLADO` y versiones nuevas
  `BORRADOR`;
- replay devuelve mismo UUID/timestamps, cero filas y cero secuencias;
- mismo ID con contenido distinto bloquea;
- fila `ABIERTO` previa queda intacta y falla;
- corrupción artificial del grafo no se acepta como replay;
- estados editoriales posteriores aplicados por V27 no invalidan el grafo inmutable;
- transaction manager instrumentado cubre cada marcador monótono, incluido receipt previo a
  `beforeCommit`, commit, rollback y `STATUS_UNKNOWN`;
- ningún intento nuevo deja una publicación `ABIERTO` después de rollback.

### Addendum técnico aprobado durante la implementación

La verificación del source de Spring Framework 7.0.7 mostró que `JdbcTransactionManager` puede
traducir un `SQLException` de commit a `DataAccessException`; el manejador superior intenta rollback
y podría publicar `STATUS_ROLLED_BACK` después de un commit ambiguo. El importador rechaza esa
configuración y exige `DataSourceTransactionManager` exacto con
`rollbackOnCommitFailure=false`. El dry-run existente conserva su manager porque nunca confirma
escrituras. El Corte 4 debe ensamblar el contexto importador con este manager seguro.

El gate exige además `PROPAGATION_REQUIRES_NEW`, `READ_COMMITTED`, timeout productivo y
`readOnly=false`. El servicio acredita por identidad que gate, writer y replay verifier usan la
misma instancia de `JdbcTemplate`, cuyo `DataSource` coincide con el manager. Esto evita devolver
éxito antes del commit de una transacción exterior o ejecutar JDBC fuera de la completion
observada. En el Corte 4 los preflights reales deberán quedar sujetos a la misma instancia.

También se amplía la regla conservadora: cualquier `STATUS_UNKNOWN`, incluido un fallo de rollback,
se informa como `persisted=null/UNKNOWN` y oculta el receipt. No hay retry automático; repetir el
mismo release exacto es la reconciliación idempotente.

```bash
./mvnw -Dtest=LegalManifestImportServiceTest,LegalImportTransactionStateTest test
./mvnw -Dit.test=LegalManifestImportIT verify
git diff --check
```

### Evidencia del Corte 3

- resultado público cerrado por construcción: `IMPORTED`, `ALREADY_IMPORTED` o `UNKNOWN`, con
  `persisted=true/false/null`, receipt mínimo sólo cuando hay confirmación y una única issue segura
  en fallos conocidos;
- import fresco confirmado sobre PostgreSQL 16.14 con las 12 tablas pobladas, publicación
  `SELLADO`, versiones nuevas `BORRADOR` y ninguna fila `ABIERTO`;
- replay exacto con mismo UUID/timestamps, cero delta en las 12 tablas y cero avance de las seis
  secuencias identity observadas;
- conflicto de canónico, corrupción inmutable y publicación previa `ABIERTO` bloqueados sin
  mutación ni intento de reparación;
- replay posterior a estados reales `VIGENTE`, `REEMPLAZADA` y `RETIRADA`, con slot documental y
  puntero de conjunto actual presentes, conserva tanto el grafo como el estado editorial;
- una excepción inyectada después de ejecutar el writer real revierte las 12 tablas y deja cero
  publicaciones `ABIERTO`;
- pruebas con conexiones instrumentadas acreditan commit y rollback fallidos: cualquier
  `STATUS_UNKNOWN` oculta el receipt y produce `persisted=null`, sin retry automático;
- puerta focalizada: 639 pruebas unitarias y 7 pruebas PostgreSQL del importador; verificación
  completa: las mismas 639 unitarias y 67 pruebas de integración sobre PostgreSQL 16.14, todas sin
  fallos;
- sin migración, bean productivo, CLI, endpoint, cambio frontend, importación real, deploy ni push.

Riesgos no bloqueantes diferidos al hardening del Corte 6: acotar las lecturas de replay a la
cardinalidad esperada más una fila frente a una base deliberadamente corrupta, y repetir en
PostgreSQL el replay de un segundo release que reutiliza líneas/versiones históricas. La rama de
reutilización ya está cubierta unitariamente; no se interpreta esta cobertura parcial como la
acreditación de procesos reales prevista para ese corte.

Commit: `feat(legal): importa manifiestos de forma idempotente`.

## Corte 4 — Contexto DB restringido, schema exacto y runbook

### Objetivo

Crear la única configuración que puede construir el importador y acreditar por catálogo que su
credencial posee exactamente las capacidades previstas.

### Archivos

Crear bajo `.../legal/manifest/persistence/`:

- `LegalImportDatabaseConfiguration.java`;
- `LegalV27ImportSchemaVerifier.java`;
- `LegalImportPrivilegeVerifier.java`;
- `LegalV27ImportInventory.java`.

Crear pruebas:

- `LegalV27ImportSchemaVerifierIT.java`;
- `LegalImportPrivilegeVerifierIT.java`;
- `LegalImportDatabaseIsolationIT.java`.

Crear documentación:

- `docs/runbooks/legal-manifest-import-postgresql.md`.

Modificar:

- `LegalDryRunDatabaseConfiguration.java` para compartir el verifier estricto sin activar import;
- `LegalManifestImportService.java` para exigir el verifier real en configuración productiva;
- `LegalDatabaseFailureMapper.java` sólo mediante un adapter contextual; no cambiar mensajes v1.

### Implementación

1. Configuración lite, explícita y no escaneable: DataSource, JdbcTemplate,
   `DataSourceTransactionManager`, gate, verifier, writer e import service. Sin JPA, Flyway, MVC,
   Security, Mail, Actuator, scheduling, runners ni app principal.
2. Congelar en `LegalV27ImportInventory`:
   - 12 tablas importables;
   - seis sequences identity exactas;
   - versión/checksum Flyway V27;
   - firmas y digest de funciones alcanzables;
   - triggers con tabla/función/timing/eventos/habilitación;
   - constraints con definición, deferibilidad y estado;
   - ownership y vínculo identity.
3. Consultar `pg_catalog` con identificadores constantes. No concatenar schema recibido del
   operador sin quoting validado.
4. Verificar `session_user=current_user`, rol esperado, `NOSUPERUSER`, `NOCREATEDB`,
   `NOCREATEROLE`, `NOREPLICATION`, `NOBYPASSRLS`, sin membership amplia y sin ownership.
5. Permitir sólo `CONNECT`, `USAGE` de schema, `SELECT` exclusivo sobre
   `flyway_schema_history`, SELECT/INSERT en las 12 tablas, USAGE en seis secuencias, UPDATE de
   columnas de sello y la allowlist de funciones requerida. La tabla Flyway no admite
   INSERT/UPDATE/DELETE para el importador.
6. Conceder `UPDATE(id)` sólo en líneas/versiones documentales y de requisitos para habilitar
   `FOR UPDATE/FOR KEY SHARE`. Probar que los triggers V27 rechazan cualquier update directo/no-op.
7. Rechazar UPDATE de tabla/otra columna, DELETE, TRUNCATE, REFERENCES, TRIGGER, CREATE y toda
   escritura en transición, slot, reemplazo, aceptación, metadata o ledger HTTP.
8. El runbook usa placeholders editables para base/schema/rol, revoca `PUBLIC EXECUTE` de cada firma
   legal V27 y concede la allowlist importadora. No contiene password ni comando destructivo.
9. Documentar que job/operador, SHA, receipt y logs PostgreSQL sanitizados son evidencia externa;
   V27 no almacena actor operativo.

### Pruebas y puerta

- drift individual de tabla, sequence, checksum, función, trigger o constraint falla antes del
  grafo;
- rol owner/superuser/bypass-RLS/membership/schema-create/privilegio extra falla cerrado;
- rol exacto puede leer versión/checksum de `flyway_schema_history` e importa, pero no puede escribir
  esa tabla;
- el rol no puede promover, insertar transición/puntero/aceptación, borrar ni alterar IDs;
- contextos normal e import no descubren beans del otro;
- no aparece Flyway/JPA/web/runners al abrir el contexto importador.

```bash
./mvnw -Dit.test=LegalV27ImportSchemaVerifierIT,LegalImportPrivilegeVerifierIT,LegalImportDatabaseIsolationIT verify
git diff --check
```

### Cierre ejecutado — 26 de agosto de 2026

- configuración importadora lite, explícita y no escaneable, con una única frontera
  `DataSource`/`JdbcTemplate`, `DataSourceTransactionManager` exacto y acreditación por identidad de
  los dos preflights en orden schema → privilegios;
- guard cruzado entre contextos import/dry-run: registrarlos juntos falla durante el refresh; el
  contexto normal no descubre ninguno aun con flags hostiles;
- inventario V27 congelado sobre PostgreSQL 16.15: checksum Flyway `1575269868`, 12 tablas/93
  columnas/97 constraints —incluidos 76 triggers internos—, 29 triggers de negocio, seis sequences
  identity y digest de las 17 funciones alcanzables;
- huellas SHA-256: tablas
  `d3b0a50cb6cbdf0a0a8eab97bd10ae0e1f8e605ce009c6083ae77e1827257ad9`, columnas
  `71ce2628bcac127f8798bbd5098563c8bd3a43c8fc505bd7794dfaa726ae96a7`, constraints
  `359366a916255d91e4de547eb4476d236b307c984495490071f1b54920936475`, triggers
  `e85176c8a84aa7cbf52b5051b8a73981529e29955cf5a49127e20f7cbe3205e3` y sequences
  `308609421640e4120de7cf8621a605b541287c0808e9b44ca0f64f782df2874e`;
- schema verifier cerrado ante versión distinta de PostgreSQL 16, `search_path` configurado con
  entradas ocultas/inexistentes, drift de tabla/columna/sequence/checksum/función/trigger/constraint,
  función de trigger homónima en otro schema o trigger FK interno deshabilitado;
- perfil efectivo exacto del rol: identidad/flags, memberships recibidas y delegadas, ownership,
  todas las bases del clúster, schemas, tablas/columnas, sequences, 47 funciones V27, grant options,
  large objects y ACL explícitas de parámetros como `session_replication_role`;
- el rol mínimo importa y reanuda el mismo release con receipt estable; no puede escribir Flyway,
  promover IDs, insertar transición/puntero/aceptación, borrar ni obtener capacidades por `PUBLIC`;
- SQLSTATE `42501` se traduce en el adapter v2 a
  `IMPORT_DB_PRIVILEGES_INCOMPATIBLE`; los códigos y mensajes v1 del dry-run permanecen intactos y
  no existe reparación ni retry automático;
- runbook operativo con 47 revocaciones explícitas de `PUBLIC EXECUTE`, allowlist de 17 funciones,
  seis sequences, columnas técnicas mínimas, impactos globales, evidencia externa y cero secretos;
- puerta local: 643 pruebas unitarias y 24 pruebas focalizadas sobre PostgreSQL 16.15, todas sin
  fallos; `git diff --check` limpio. La suite histórica completa de Testcontainers no se volvió a
  ejecutar en esta máquina porque no dispone de daemon Docker; su última base acreditada del Corte
  3 fue 67 IT sobre PostgreSQL 16.14. No se presenta esa base previa como ejecución de este corte;
- sin comando import, credenciales reales, migración nueva, cambio frontend, deploy ni push.

Commit: `feat(legal): restringe acceso DB del importador`.

## Corte 5 — Comando import, confirmaciones y reporte v2

Estado: completado el 2026-08-27.

### Objetivo

Exponer la capacidad sólo en el jar legal interno, con confirmaciones ligadas al release, secretos
aislados y un reporte que nunca mienta sobre persistencia.

### Archivos

Crear bajo `.../legal/manifest/cli/`:

- `LegalManifestImportConfirmation.java`;
- `LegalImportEnvironment.java`;
- `LegalManifestImportReport.java`;
- `LegalManifestImportReportWriter.java`;
- `LegalManifestCliExecutionState.java`.

Modificar:

- `LegalManifestArguments.java`;
- `LegalManifestCli.java`;
- `LegalManifestIssueCode.java`;
- `LegalManifestImportService.java` para emitir la señal del callback transaccional real;
- `pom.xml` sólo si la inspección del jar necesita actualizarse.

Crear/modificar pruebas:

- `LegalManifestArgumentsTest.java`;
- `LegalManifestImportConfirmationTest.java`;
- `LegalImportEnvironmentTest.java`;
- `LegalManifestImportReportTest.java`;
- `LegalManifestImportReportWriterTest.java`;
- `LegalManifestCliExecutionStateTest.java`;
- `LegalManifestCliTest.java`;
- `LegalManifestImportServiceTest.java`;
- regresiones exactas de `LegalManifestReportTest` y `LegalManifestReportWriterTest`.

### Implementación

1. Agregar `import` al parser con exactamente:
   `--manifest`, `--confirm-publication-id` y `--confirm-manifest-sha256`; una instancia de cada uno,
   en cualquier orden después del comando y sin alias, extras, duplicados, prompt, `--force`,
   password ni properties Spring. No aceptar formas separadas como `--manifest ruta`.
2. Validar bundle antes de confirmaciones; comparar ID y hash JCS exactos; comprobar luego
   `ORDENFIX_LEGAL_IMPORT_ENABLED=true`; abrir DB al final.
3. `LegalImportEnvironment` acepta únicamente:
   `ORDENFIX_LEGAL_IMPORT_DB_URL`, `_USERNAME`, `_PASSWORD` y opcional
   `_DRIVER_CLASS_NAME`.
4. Si existe cualquier system property `spring.datasource.*` durante `import`, fallar configuración
   sin copiar ni imprimir el valor. Mantener resolver actual de dry-run separado.
5. El contexto hijo elimina system properties y system environment, y recibe un map interno con
   sólo las cuatro propiedades traducidas y constantes de aislamiento.
6. Mantener `LegalManifestReport`/writer v1 sin agregar el campo `import`. Crear el modelo v2
   separado con orden superior exacto:
   `reportVersion, command, status, persisted, publication, counts, dryRun, import, issues,
   omittedIssueCount`.
7. Congelar `import` como
   `outcome, publicationUuid, importedAt, sealedAt, sealed, promotionChanged`; conservar propiedades
   nulas presentes y timestamps UTC `Z` de 0 a 6 fracciones.
8. Hacer imposibles las combinaciones fuera de la matriz aprobada: PASS/true/receipt,
   BLOCKED/false/null, ERROR conocido/false/null, ERROR incierto/null/UNKNOWN.
9. Reconocer `rawArguments[0] == "import"` antes de crear la CLI. Desde allí todo fallback es v2;
   nunca usar `EMERGENCY_REPORT` v1.
10. Conservar phase/receipt hasta después del cierre de contexto y escritura. Un fallo post-commit
    no degrada a false; stdout roto devuelve exit 3 sin fabricar otro canal.

### Pruebas y puerta

- matriz completa, orden de campos, nulls, timestamps y límites de issues;
- argumento/confirmación/flag incorrecto no consulta configuración DB;
- property JVM datasource bloqueada y nunca reflejada;
- variables import-specific llegan; variables Spring hostiles no;
- excepciones antes de DB, callback, rollback, commit incierto, post-commit, serialización y stdout;
- receipt entregado seguido de rollback confirmado produce ERROR/false/null, aunque no haya llegado
  a `beforeCommit`; frontera de commit sin completion o cualquier `STATUS_UNKNOWN` produce
  ERROR/null/UNKNOWN;
- salidas v1 de validate/dry-run byte-identical a las fixtures previas.

```bash
./mvnw -Dtest=LegalManifestArgumentsTest,LegalManifestImportConfirmationTest,LegalImportEnvironmentTest,LegalManifestImportReportTest,LegalManifestImportReportWriterTest,LegalManifestCliExecutionStateTest,LegalManifestCliTest,LegalManifestImportServiceTest,LegalManifestReportTest,LegalManifestReportWriterTest test
git diff --check
```

### Evidencia del Corte 5

- parser cerrado con los tres flags `--nombre=valor` exactos, aceptados en cualquier orden y una
  sola vez; no existen alias, prompt, `--force`, password ni properties Spring por argumentos;
- bundle validado completamente antes de comparar publication ID/SHA-256; confirmaciones resueltas
  antes del opt-in literal `ORDENFIX_LEGAL_IMPORT_ENABLED=true`, la configuración y la apertura DB;
- sólo las cuatro variables `ORDENFIX_LEGAL_IMPORT_DB_*` llegan al property source interno; una
  property JVM `spring.datasource.*` bloquea sin leer ni reflejar su valor, y el contexto hijo
  descarta system properties, system environment y Config Data hostil;
- reporte v2 separado, con orden y matriz `PASS/true`, `BLOCKED/false`, `ERROR/false` y
  `ERROR/null/UNKNOWN` cerrados por construcción; reconocer el comando crudo garantiza fallbacks v2
  mientras `validate` y `dry-run` conservan el modelo/writer v1 sin cambios;
- observer emitido dentro de la sincronización transaccional activa y antes de la primera lectura
  SQL: los fallos previos al callback permanecen `false`, un rollback confirmado oculta incluso un
  receipt ya entregado y sólo una frontera realmente indeterminada produce `UNKNOWN`;
- resultado/receipt confirmado conservado ante fallo posterior de cierre o serialización; stdout
  roto devuelve exit `3` sin inventar un segundo canal;
- test unitario de contexto real con H2 acredita un único DataSource, servicio importador aislado,
  ausencia de dry-run/Flyway, rechazo de configuración Spring hostil y cierre del pool. Esto no se
  presenta como acreditación PostgreSQL ni como prueba del jar en otro proceso;
- puerta focalizada con JDK 21: 142 pruebas; suite unitaria completa: 729 pruebas; ambas con cero
  fallos, errores u omitidas. `compile` pasó y `package -DskipTests` construyó los jars normal y
  `legal-cli`; `git diff --check` quedó limpio;
- no hubo migración, endpoint, cambio frontend, credenciales reales, importación de contenido real,
  PostgreSQL/proceso real, deploy, push ni cambio de `BACKEND-HANDOFF 1`. Concurrencia, canaries en
  otra JVM, stdout real, rol restringido, capacidad e inspección de jars permanecen en el Corte 6.

Commit: `feat(legal): expone import seguro en CLI aislada`.

## Corte 6 — Concurrencia, procesos reales y hardening

### Objetivo

Acreditar el protocolo completo en PostgreSQL y en el jar empaquetado, incluidos timeout seguro,
reconciliación y pérdida de stdout, sin hooks productivos de fallo.

### Archivos

Crear/modificar pruebas:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestImportConcurrencyIT.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestImportFailureIT.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestImportCapacityIT.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalManifestImportProcessIT.java`;
- `LegalManifestCliIsolationIT.java`;
- `LegalManifestCliProcessIT.java` para regresión de ambos comandos v1;
- `pom.xml` sólo para incluir la nueva inspección si no entra por `*IT`.

### Implementación y escenarios

1. Dos imports idénticos dentro del presupuesto: exactamente un `IMPORTED` y un
   `ALREADY_IMPORTED`.
2. Retener el advisory lock más allá de un presupuesto reducido sólo por constructor de test:
   segundo proceso `ERROR/false`; repetición posterior `ALREADY_IMPORTED`.
3. Publicaciones distintas con identidades compatibles se serializan y reutilizan; incompatibles
   confirman como máximo una. Repetir además el segundo release compatible y acreditar su replay
   PostgreSQL con los mismos conteos internos de líneas/versiones nuevas y reutilizadas.
4. Dry-run/import cooperativos no observan grafo parcial y respetan el mismo lock.
5. Instalar un writer de test no cooperativo que fuerza timeout/deadlock; mapear y revertir sin
   afirmar que el lock global controla writers externos.
6. Terminar una sesión antes de commit y verificar rollback total/recuperación de pool.
7. Cubrir `UNKNOWN` con transaction manager instrumentado. Para proceso real, cerrar el pipe stdout
   del hijo: el commit ocurre antes de escribir el reporte; luego repetir el comando y acreditar
   replay. No agregar variable/flag que provoque el fallo.
8. Ejecutar jar real con rol restringido para `IMPORTED`, `ALREADY_IMPORTED`, BLOCKED y errores
   conocidos; inspeccionar exit, stdout UTF-8 compacto y stderr sin secretos.
9. Ejecutar el jar con cada `-Dspring.datasource.*` hostil y un canary secreto, aun teniendo las
   variables import-specific válidas. Debe terminar con exit 3/reporte v2, cero sesión/escritura DB
   y ausencia del canary en stdout/stderr.
10. Limpiar `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` en el launcher documentado;
   conservar explícito que la JVM los procesa antes de `main`.
11. Inspeccionar ambos jars: Start-Class correcto, ausencia de `application-secret.properties` y
    ningún segundo jar importador.
12. Generar en test un release máximo válido de 128 documentos, 256 requisitos y 16 documentos por
    requisito, respetando el total de Markdown. Medir cantidad de round-trips y duración de import
    fresco/replay con PostgreSQL 16.
13. Repetir la medición con un DataSource instrumentado que agrega 5 ms antes de cada round-trip. El
    import fresco debe terminar antes de 70 s y ninguna sentencia superar 30 s, dejando margen sobre
    el timeout total de 75 s. Si no cumple, agrupar inserts JDBC por tabla/ordinal en este mismo
    corte; no relajar locks, constraints, límites ni timeouts.
14. Acotar cada lectura multirrow del replay a la cardinalidad acreditada por el release más una
    fila. Una fixture PostgreSQL deliberadamente sobredimensionada debe bloquear sin materializar la
    relación corrupta completa en memoria.

### Puerta

```bash
./mvnw -Dit.test=LegalManifestImportConcurrencyIT,LegalManifestImportFailureIT,LegalManifestImportCapacityIT verify
./mvnw -Dit.test=LegalManifestImportProcessIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
./mvnw verify
git diff --check
```

Registrar cantidad de unitarias/IT, versión de PostgreSQL y cualquier incidente transitorio sin
disfrazarlo como PASS inicial.

Commit: `test(legal): acredita importacion y reconciliacion real`.

## Corte 7 — Runbook final, cierre cross-repo y regresión

### Objetivo

Dejar evidencia reproducible, actualizar el plan maestro sin habilitar integración y cerrar 2.3B
en ambos repositorios.

### Backend

Crear:

- `docs/plans/2026-08-25-legal-manifest-import-closure.md`.

Modificar:

- este plan para marcar cortes/commits/pruebas completados;
- `docs/runbooks/legal-manifest-import-postgresql.md` con el comando final, checklist de secretos,
  confirmaciones, receipt, replay y recuperación de `UNKNOWN`;
- `FRONTEND_INTEGRATION.md` sólo si necesita aclarar que el sello interno no habilita APIs;
- documentación de release que ya enumere la siguiente mini-fase.

La closure debe registrar:

- hashes de los commits de los seis cortes;
- matriz de resultados `IMPORTED/ALREADY_IMPORTED/BLOCKED/ERROR/UNKNOWN`;
- inventario/grants acreditados;
- conteos reales de pruebas enfocadas y completas;
- estado de ramas y ausencia de push/deploy/import real;
- riesgos diferidos: 2.3C, V28 multicontexto, APIs, aceptación y enforcement.

Commit backend: `docs(legal): cierra fase 2.3B`.

### Frontend

Modificar sólo documentación:

- `docs/legal/README.md`;
- `docs/plans/2026-08-23-lanzamiento-publico-confianza-cuenta-plan.md`.

Registrar que 2.3B importa/sella internamente pero no promueve, no ofrece catálogo remoto y no
habilita la Tarea 3 ni `BACKEND-HANDOFF 1`. No agregar API client, query, mutation, botón ni flag.

Commit frontend: `docs(plan): registra cierre de fase 2.3B`.

### Puerta final

Backend:

```bash
./mvnw verify
git diff --check
git status --short
```

Frontend:

```bash
npm run test:release
npm test
npm run build
git diff --check
git status --short
```

El build frontend local puede omitir deliberadamente el guard `public` cuando no hay variables de
deploy ni manifiesto profesional aprobado. No ejecutar `build:public` contra borradores para simular
readiness.

## Puerta de salida de la fase

2.3B se considera cerrada sólo cuando:

- import fresco, replay, conflicto y publicación abierta cumplen el contrato;
- ningún intento nuevo deja fila `ABIERTO`;
- `UNKNOWN` nunca se emite como éxito o rollback acreditado;
- revisión por scope se reconstruye desde PostgreSQL;
- replay posterior a estados editoriales mutables sigue siendo idempotente;
- validate/dry-run conservan bytes v1;
- el rol real importa y no puede promover ni ampliar privilegios;
- tests unitarios, PostgreSQL, concurrencia, proceso y suites completas pasan;
- frontend continúa sin integración real;
- no se importó contenido real, no hubo deploy y no hubo push;
- `BACKEND-HANDOFF 1` permanece cerrado.

## Después de 2.3B

- **2.3C:** promoción/retiro y readiness editorial, sin enforcement.
- **V28 antes de aceptaciones:** revisión agregada multicontexto.
- **2.4+:** APIs legales, aceptación/registro atómicos e integración frontend en cortes separados.
