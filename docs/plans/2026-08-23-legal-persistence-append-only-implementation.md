# Fase 2.2 — Plan de implementación de persistencia legal append-only

Fecha: 2026-08-23

Estado: completado y verificado

Diseño fuente: `docs/plans/2026-08-23-legal-persistence-append-only-design.md`

Contrato wire: `FRONTEND_INTEGRATION.md` §4.1.a y
`docs/plans/2026-08-23-legal-api-contract-v1-design.md`

## Objetivo del corte

Agregar la base persistente del contrato legal v1 mediante una migración Flyway, mapeos JPA y
pruebas PostgreSQL. Al terminar, el esquema puede almacenar publicaciones selladas, versiones,
snapshots vigentes, evidencia e idempotencia sin exponer todavía endpoints ni activar enforcement.

## Límites

Incluye:

- V27 con tablas, índices, checks, FK, funciones y triggers;
- UUID/`Instant`, enums legales y converter de locale;
- entities y repositorios de persistencia;
- validación Hibernate sobre PostgreSQL y construcción básica sobre H2;
- pruebas de migración, invariantes diferibles y concurrencia real;
- actualización del estado coordinado en la documentación frontend.

No incluye:

- controllers, DTO HTTP o cambios de `SecurityConfig`/CORS;
- importador de manifiestos, dry-run o contenido/seed legal;
- codec AES-GCM, resolución de proxy/IP o keyrings operativos;
- cálculo de revisiones, carry-forward o advisory locks de idempotencia en servicios;
- cambios al registro/login, `428` o enforcement;
- push, deploy o escritura en el repositorio Java que el usuario mantenga en otro chat fuera de este
  backend autorizado.

## Estrategia de commits

1. `docs(legal): planifica persistencia append-only`
2. `feat(db): agrega persistencia legal append-only`
3. `feat(legal): mapea persistencia relacional`
4. `test(legal): cubre invariantes y concurrencia`
5. `docs(legal): registra cierre fase 2.2`
6. `docs(plan): coordina persistencia legal implementada` en el frontend

Si una prueba revela una corrección que no puede incorporarse limpiamente al commit de su tarea, se
crea un `fix(db)` o `fix(legal)` separado. No se mezcla runtime HTTP con esta fase.

## Tarea 1 — Migración V27 y estructura comprobable

Archivos:

- crear `src/main/resources/db/migration/V27__persistencia_legal_append_only.sql`;
- modificar `src/test/java/com/leonardorozza/mvgrreparacionesbackend/PostgresMigrationIT.java`.

### 1.1 Convenciones comunes

- Agregar `UNIQUE (id, taller_id)` en `users` para las FK compuestas de evidencia.
- Usar UUID nativo sin extensión, `BIGINT` para `users/talleres` e `Instant` mediante
  `TIMESTAMP WITH TIME ZONE`.
- Guardar enums como `VARCHAR` + `CHECK` y hashes como `CHAR(64)` o `VARCHAR(64)` con regex
  minúscula; revisiones como `VARCHAR(71)` con `sha256:`.
- Nombrar todas las PK/FK/unique/checks para que las pruebas y errores sean estables.
- Declarar toda FK histórica con `ON DELETE RESTRICT`/`NO ACTION`; no usar `CASCADE`.
- Crear índices de consulta por locale/contexto/estado, linaje, publicación y actor/tenant.

### 1.2 Grafo editorial

Crear, en orden de dependencia:

1. `legal_publicaciones`;
2. `legal_documento_lineas`, `legal_documento_versiones`, `legal_documento_contextos` y
   `legal_publicacion_documentos`;
3. `legal_documento_transiciones`, `legal_documento_vigentes` y las tres tablas del agregado de
   reemplazo;
4. `legal_requisito_lineas`, `legal_requisito_audiencias`, `legal_requisito_versiones`,
   `legal_requisito_documentos`, `legal_publicacion_requisitos` y
   `legal_requisito_transiciones`;
5. `legal_requisito_conjuntos`, `legal_requisito_conjunto_miembros` y
   `legal_requisito_conjuntos_actuales`.

Reglas que deben quedar en PostgreSQL:

- identidades estables, versión y `lineage_ordinal` únicos;
- conjuntos intrínsecos no vacíos y ordinales `1..N` al sellar;
- publicación técnica `ABIERTO -> SELLADO`, terminal;
- protocolo de escritura con lock+relectura limitado a `READ COMMITTED`, rechazando snapshots de
  aislamiento que puedan omitir un hijo confirmado durante una espera;
- barrera advisory `BEFORE STATEMENT` previa a los locks de fila para serializar sellos de
  publicaciones y evitar dependencias cruzadas en deadlock;
- roots/hijos/membresías/snapshots insertables sólo con publicación abierta, tomando
  `SELECT ... FOR SHARE` sobre la publicación dueña;
- dependencias externas provenientes exclusivamente de publicaciones ya selladas;
- `BORRADOR -> PUBLICADA -> VIGENTE -> REEMPLAZADA | RETIRADA` con transición auditada;
- publicación de ordinal estrictamente mayor al máximo históricamente publicado bajo lock de línea;
- slots documentales exactos por `(tipo, locale, contexto)`;
- lote de reemplazo sellado, no vacío, sin autociclo y con cobertura split/merge exacta;
- snapshots actuales homogéneos, completos y sin mezclar publicaciones;
- miembros de snapshot con el `manifest_ordinal` global de su membresía; al filtrar por scope pueden
  existir huecos legítimos y no se exige una secuencia local `1..N`;
- invalidación fail-closed de punteros cuando se reemplaza o retira un documento vigente.

### 1.3 Evidencia, metadata e idempotencia

Crear:

1. `legal_aceptacion_lotes`, `legal_aceptaciones` y `legal_aceptacion_documentos`;
2. `legal_aceptacion_metadatos` y `legal_aceptacion_metadatos_cifrados`;
3. `legal_idempotencia_resultados`.

Reglas obligatorias:

- actor y tenant unidos por FK compuesta al lote y `users(id,taller_id)`;
- un lote confirmado posee al menos una aceptación y exactamente una cabecera técnica;
- evidencia exacta contra requisito/documentos canónicos y snapshot actual al insertar;
- unicidad permanente `usuario + requisitoVersionId`;
- IP cifrada obligatoria antes de purga; User-Agent opcional y longitud previa máxima 512;
- nonce/tag/tamaños exactos y `UNIQUE(key_version, nonce)` global;
- purga por tombstone: nulificar payload/tag/longitud, conservar keyVersion+nonce y marcar fechas;
- `aceptado_en`, `capturado_en` y `completed_at` normalizados por PostgreSQL, sin permitir
  retención o idempotencia ya vencidas al confirmar;
- idempotencia de éxitos con forma tipada por operación, HMAC hex, FK de resultado y expiración
  mínima de 24 horas;
- `DELETE` bloqueado en historia/evidencia; delete/insert sólo para proyecciones actuales y purgas
  estrictamente acotadas.

### 1.4 Funciones y triggers

Agrupar funciones por responsabilidad y usar nombres prefijados `legal_`:

- guardas genéricas de inmutabilidad/no-delete;
- lock y validación de publicación abierta;
- validación diferible de sello editorial y dependencias;
- transición y auditoría documental/de requisito;
- validación de slots y conjuntos actuales;
- sello/cobertura de reemplazo;
- completitud de lote/evidencia;
- transición de purga técnica;
- guardas de idempotencia.

Los constraint triggers de completitud se declaran `DEFERRABLE INITIALLY DEFERRED`. Los triggers de
formato, estado terminal, lock y no-delete fallan inmediatamente.

Los entrypoints que esperan locks y luego releen filas ejecutan una guarda común de aislamiento. Los
sellos de publicación adquieren además su advisory lock en un trigger por sentencia, antes de que el
`UPDATE` pueda tomar el lock de la publicación propia.

Al terminar V27, aplicar a toda función `legal_*` un `search_path` fijo compuesto por `pg_catalog`,
el schema de instalación resuelto por Flyway y `pg_temp` en último lugar. La prueba PostgreSQL debe
intentar sombrear una tabla legal con una temporal y comprobar que la función continúa leyendo el
agregado real.

### 1.5 Prueba estructural y upgrade

Extender `PostgresMigrationIT` para:

- exigir que Flyway aplique `27` desde cero;
- comprobar tablas/columnas, UUID, `timestamptz`, checks, FK `RESTRICT`, índices y triggers clave;
- migrar un schema aislado a target `26`, crear usuario/taller compatible y subir a `27`;
- verificar `UNIQUE users(id,taller_id)` y `ddl-auto=validate` cuando existan los entities;
- no depender de datos legales semilla.

Verificación del commit:

```bash
./mvnw -Dit.test=PostgresMigrationIT verify
```

## Tarea 2 — Mapeo JPA y repositorios

Paquetes nuevos:

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/persistence/entity/legal/`;
- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/persistence/entity/legal/id/`;
- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/persistence/converter/`;
- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/persistence/repository/legal/`;
- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/persistence/repository/legal/projection/`.

Archivo existente:

- modificar `src/main/java/com/leonardorozza/mvgrreparacionesbackend/persistence/entity/enums/UserRole.java`
  sólo para el mapping explícito a audiencia legal, sin cambiar valores wire.

Los enums nuevos mantienen la convención existente y viven en
`src/main/java/com/leonardorozza/mvgrreparacionesbackend/persistence/entity/enums/`.

### 2.1 Tipos

Crear como mínimo:

- `LocaleLegal`, `TipoActoLegal`, `ContextoLegal`, `TipoDocumentoLegal`;
- `AudienciaLegal`, `EstadoVersionLegal`, `EstadoConstruccionLegal`;
- `EstadoRevisionLegal`, `TipoCampoMetadataLegal`, `TipoOperacionIdempotenteLegal`;
- `LocaleLegalConverter` con `ES_AR <-> es-AR`.

Los otros enums usan `EnumType.STRING`. `UserRole` expone mapping exhaustivo
`ADMIN -> ADMIN_TITULAR`, `USER -> USER`.

### 2.2 Entities

Mapear las 25 tablas legales, agrupadas por agregado y con prefijo de clase `Legal`:

- publicación y documentos: `LegalPublicacion`, `LegalDocumentoLinea`, `LegalDocumentoVersion`,
  `LegalDocumentoContexto`, `LegalPublicacionDocumento`, `LegalDocumentoTransicion`,
  `LegalDocumentoVigente`, `LegalDocumentoReemplazoLote`,
  `LegalDocumentoReemplazoAnterior`, `LegalDocumentoReemplazoSucesora`;
- requisitos: `LegalRequisitoLinea`, `LegalRequisitoAudiencia`, `LegalRequisitoVersion`,
  `LegalRequisitoDocumento`, `LegalPublicacionRequisito`, `LegalRequisitoTransicion`,
  `LegalRequisitoConjunto`, `LegalRequisitoConjuntoMiembro`,
  `LegalRequisitoConjuntoActual`;
- evidencia: `LegalAceptacionLote`, `LegalAceptacion`, `LegalAceptacionDocumento`,
  `LegalAceptacionMetadata`, `LegalAceptacionMetadataCifrada`;
- idempotencia: `LegalIdempotenciaResultado`.

Usar `GenerationType.UUID` para IDs UUID de aplicación y `IDENTITY` para links/eventos/resultados
internos `BIGINT`, incluido el ledger idempotente. Los slots/punteros usan
`LegalDocumentoVigenteId` y `LegalRequisitoConjuntoActualId` con `@EmbeddedId`; las relaciones
ordenadas usan ID sustituto y unique de negocio.

Reglas de mapeo:

- asociaciones `LAZY`, sin `CascadeType.REMOVE` ni `orphanRemoval`;
- campos históricos `updatable=false` y sin setters públicos generales;
- constructores/factorías de dominio que exijan campos obligatorios y reciban `Instant` explícito;
- no usar `@CreatedDate` hasta ligar auditing al `Clock`;
- `byte[]` de metadata como binario/varbinary compatible con PostgreSQL `bytea` y H2;
- Markdown, manifiesto y afirmaciones como `TEXT`, nunca `@Lob`/OID;
- no intentar modelar triggers diferibles en annotations; las annotations deben coincidir en
  nullability, longitud y unique básicos para `validate`.

### 2.3 Repositorios y consultas

Crear repositorios sólo para roots/proyecciones consultables: publicación; línea, versión,
transición, membresía, slot y reemplazo documental; línea, versión, transición, membresía, snapshot
y puntero de requisito; lote, aceptación y metadata; resultado idempotente. Los children sin caso de
lectura propio se persisten explícitamente con `EntityManager`, no reciben un repository público.

Incluir consultas explícitas para:

- versión exacta y linaje por key+locale;
- snapshot actual por locale/contexto/audiencia;
- historial paginado por `aceptadoEn DESC, id DESC`, siempre con user+taller;
- evidencia exacta de actor+requirementVersion;
- candidatos idempotentes por operación+ruta+scope+hash/version.

Los candidatos idempotentes se consultan como una tupla correlacionada exacta
`(keyVersion, scopeHmac, keyHmac)` y sólo si `expiresAt` sigue vigente; no se combinan tres listas
`IN` independientes. Las transiciones se insertan mediante comandos nativos que hacen flush y
limpian el contexto de persistencia, porque los triggers actualizan la versión y una entity ya
administrada quedaría obsoleta. La purga expone dos comandos ordenados: primero todos los campos
cifrados y después la cabecera.

Agregar proyecciones cerradas para catálogo documental, linajes documental/de requisito, requisito
actual e historial de aceptación; no serializar entities. Historial y requisitos actuales cargan
sus documentos en una segunda consulta por IDs, con orden padre + ordinal + ID estable.

Las cargas de colecciones usan dos pasos o `EntityGraph`; no se pagina un fetch-join de múltiples
colecciones.

Verificación del commit:

```bash
./mvnw test
./mvnw -Dit.test=PostgresMigrationIT verify
```

## Tarea 3 — Invariantes y concurrencia PostgreSQL

Archivos nuevos previstos:

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/LegalPersistenceIT.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/LegalConcurrencyIT.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/support/LegalSqlFixture.java` si evita
  duplicar la construcción de grafos válidos;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/support/PostgresConcurrencyHarness.java`
  para conexiones, latches y timeouts reutilizables.

### 3.1 `LegalPersistenceIT`

Sobre PostgreSQL 16 real, probar por grupos:

- formatos, enums, ordinales y duplicados;
- sello incompleto, ordinales no contiguos, dependencias externas abiertas e inserts tardíos;
- serialización previa de sellos y rechazo fail-closed de `REPEATABLE READ` en publicaciones,
  transiciones y reemplazos;
- máquinas de estado, barrera `vigente_desde`, orden histórico de linaje y terminalidad;
- slots y reemplazos exactos, incluidos split/merge y rechazo parcial;
- snapshots múltiples/empty-set/ausencia, promoción multiaudiencia y fail-closed documental;
- tenant/actor/rol/audiencia y evidencia exacta/inmutable;
- completitud del lote con `SET CONSTRAINTS ALL IMMEDIATE`;
- metadata válida, hora autoritativa, tamaños, tombstone de purga y reserva permanente del nonce;
- idempotencia, hora autoritativa, forma del resultado, HMAC y vencimiento;
- `RESTRICT`, no-delete y ausencia de cascadas;
- round-trip mínimo de repositories JPA.

Las consultas de linaje se prueban también como base de carry-forward: evidencia exacta, flags
intermedios, borradores excluidos, futuro fuera del intervalo, key/documento agregado y recorrido
documental sobre toda la línea sin filtrar contexto. No se implementa todavía el gate de servicio.

Cada test de constraint diferible usa transacción independiente y fuerza constraints o commit real;
después comprueba que no haya quedado estado parcial.

### 3.2 `LegalConcurrencyIT`

Usar dos `Connection`, `autoCommit=false`, latches/futures y timeout acotado para demostrar:

- insert de root/hijo gana antes del sello: el sello espera y valida el grafo ya confirmado;
- sello gana antes del insert: el insert espera, relee `SELLADO` y falla;
- dos `BORRADOR -> PUBLICADA` de la misma línea se serializan y nunca permiten ordinal inferior
  tardío;
- dos slots para el mismo `(tipo,locale,contexto)` no pueden confirmar simultáneamente;
- miembros de reemplazo no entran después del sello;
- dos escrituras con el mismo ledger idempotente exacto no confirman dos resultados de negocio.

No usar sleeps como sincronización principal y liberar siempre conexiones/executor en `finally`.

Verificación del commit:

```bash
./mvnw -Dit.test=LegalPersistenceIT,LegalConcurrencyIT verify
./mvnw verify
```

## Tarea 4 — Coordinación con frontend

Modificar únicamente documentación del repositorio frontend:

- el plan público de lanzamiento vigente;
- el diseño/roadmap legal coordinado ya existente;
- cualquier checklist que todavía marque la persistencia backend como pendiente.

Registrar:

- hashes de commits backend de esta fase;
- V27 aplicada y validada;
- entidades/repositorios disponibles;
- endpoints, importador, contenido y enforcement todavía pendientes;
- siguiente corte: importador/dry-run/promoción con enforcement apagado.

No tocar runtime frontend en este corte y no agregar los directorios ajenos `.agents/` ni
`public/OrdenFix project naming/`.

## Puerta de salida

La fase termina sólo si:

- `git diff --check` pasa en ambos repositorios;
- `./mvnw test` pasa sobre H2;
- `./mvnw verify` pasa con PostgreSQL/Testcontainers, o queda documentado un bloqueo ambiental real
  sin presentar la fase como verificada;
- Hibernate valida el esquema V27;
- las pruebas concurrentes no dependen del orden accidental del scheduler;
- cada commit contiene una sola tarea y no existe push;
- backend queda limpio y frontend conserva únicamente los untracked ajenos ya existentes.

## Cierre ejecutado

Implementación completada el 2026-08-23 sobre la rama backend
`codex/lanzamiento-publico-backend`, sin push.

Commits técnicos del corte:

- `8b4b7b1 feat(db): agrega persistencia legal append-only`;
- `984d108 feat(legal): mapea persistencia relacional`;
- `cbb5bf1 test(legal): cubre invariantes y concurrencia`.

Resultado entregado:

- V27 con 25 tablas legales, 47 funciones `legal_*` y 77 triggers;
- 25 entities, 20 repositorios y 7 proyecciones JPA;
- migración desde cero, upgrade V26 -> V27, schema personalizado y validación Hibernate;
- timestamps autoritativos, `search_path` endurecido, append-only, evidencia exacta, tombstones,
  idempotencia y protocolos de concurrencia protegidos en PostgreSQL.

Verificación final:

- `PostgresMigrationIT`: 7/7;
- `LegalPersistenceIT`: 9/9;
- `LegalConcurrencyIT`: 7/7;
- `./mvnw verify`: 185 pruebas unitarias y 28 pruebas de integración, sin fallos ni errores;
- `git diff --check`: correcto.

Este cierre no agrega endpoints HTTP, importador, contenido legal, seeds, enforcement, cambios de
registro/login, seguridad o CORS. La persistencia queda disponible como base interna, pero todavía
no constituye el `BACKEND-HANDOFF 1` consumible por el frontend.

## Siguiente corte

Implementar importador/dry-run RFC 8785, sellado/promoción transaccional y readiness, todavía sin
exponer aceptación ni activar `428`.
