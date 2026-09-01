# Corte 12 — Plan de implementación de la revisión agregada multicontexto V28

Fecha: 2026-09-01

Estado: listo para ejecución

Diseño aprobado:

- `docs/plans/2026-09-01-legal-required-set-aggregate-v28-design.md`;
- commit local backend `5f70e28` (`docs(legal): diseña revision agregada multicontexto`).

## Objetivo

Implementar V28 en siete subcortes pequeños y verificables. El resultado debe calcular una única
`requiredSetRevision` estable para los scopes aplicables, conservar la procedencia física exacta,
materializarla bajo lock editorial compartido y vincular los lotes nuevos sin alterar evidencia
V27.

El corte termina en núcleo y persistencia. No crea endpoints, no conecta el frontend, no importa
contenido legal real y no despliega.

## Baseline

```text
rama backend: codex/lanzamiento-publico-backend
baseline previo a V28: dd78484
diseño V28 aprobado: 5f70e28
rama frontend: codex/frontend-refactor-checkpoint
baseline frontend: 7545201
```

El frontend no se modifica en Corte 12. Sus directorios no versionados conocidos permanecen fuera
de alcance.

## Invariantes no negociables

1. No editar `V27__persistencia_legal_append_only.sql` ni cambiar su checksum.
2. `AGGREGATE_V1` hashea conjuntos completos; actor, evidencia y pendientes quedan fuera.
3. `REGISTRATION` usa sólo `REGISTRO`; `AUTHENTICATED_PENDING` usa siempre el mismo resolver y
   contiene al menos `USO_CONTINUADO`.
4. El navegador nunca aporta perfil, audiencia, tenant o vector de scopes como autoridad. El store
   sólo consume un valor opaco emitido por el resolver servidor; no expone un constructor público de
   vectores arbitrarios.
5. El orden canónico es el orden congelado de `ContextoLegal`, no el orden SQL ni alfabético.
6. Token semántico y procedencia física son identidades separadas.
7. La sesión debe poseer el advisory lock editorial compartido antes de leer punteros o insertar.
8. El guard SQL verifica que la sesión ya posee ese lock; no lo adquiere dentro del mismo INSERT.
   Así `statement_timestamp()` pertenece a una sentencia realmente posterior a la espera.
9. Writers editoriales existentes conservan el lock exclusivo sobre la misma key.
10. Una fila histórica `SCOPE_V1` sólo se lee; después de V28 ningún INSERT nuevo puede crearla.
11. El guard de actos conserva la prueba `xmin` de V27.
12. La migración V28 es final al commitearse en 12D. Después no se modifica: una corrección de
    esquema exige detenerse y diseñar V29.
13. V28 usa una credencial materializadora dedicada, distinta del rol web y del migration owner,
    `NOINHERIT` y no owner del schema. Sólo el contexto aislado del store recibe esa credencial.
14. El rol web no posee DML V28 y los roles import/editorial conservan cero privilegios V28.
15. No agregar controllers, DTO HTTP, OpenAPI, CORS, ETag, `documentSetRevision`, idempotencia HTTP,
    enforcement o lógica de carry-forward.
16. No hacer push, deploy, seed, importación o promoción contra una base real.

## Reglas de ejecución y commits

1. Ejecutar 12A a 12G en orden.
2. Usar `apply_patch` para cambios manuales.
3. No usar `git add .`, `git add -A` ni globs amplios.
4. Cada commit agrega únicamente la whitelist nominal del subcorte.
5. Verificar `git diff --cached --name-only` y `git diff --cached --check` antes de cada commit.
6. Los tests focales deben pasar antes de ampliar al gate del subcorte.
7. Un fallo de seguridad, migración, lock, causalidad temporal o preservación histórica detiene el
   corte; no se rebaja a warning.
8. Ningún commit intermedio habilita producción ni `BACKEND-HANDOFF 1`.
9. No versionar `target/`, logs, reportes temporales, secretos o credenciales de roles de prueba.

## Preflight común

```bash
cd /Volumes/DiscoExtern/Desktop/mvrg-backend
git branch --show-current
git log -2 --oneline
git status --short
git diff --check
java -version
./mvnw -version
```

Se exige Java 21. Los IT usan PostgreSQL 16 mediante Testcontainers. Antes del primer cambio debe
confirmarse que sólo este plan está sin versionar.

## Preparación — Commit del plan

### Whitelist

```text
docs/plans/2026-09-01-legal-required-set-aggregate-v28-implementation.md
```

### Gate

```bash
git diff --check
git add docs/plans/2026-09-01-legal-required-set-aggregate-v28-implementation.md
git diff --cached --name-only
git diff --cached --check
```

Commit:

```text
docs(legal): planifica revision agregada multicontexto
```

## Dependencias

```text
12A contrato canónico
  -> 12B gate shared/exclusive
    -> 12C store JDBC y replay unitarios
      -> 12D migración V28 final
        -> 12E servicio e integración PostgreSQL
          -> 12F concurrencia y capacidad
            -> 12G cierre y gate integral
```

## Subcorte 12A — Perfiles, proyecciones y vectores golden

### Objetivo

Congelar todo byte que forma el token semántico y el fingerprint interno antes de abrir PostgreSQL.

### Archivos

Modificar:

```text
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/Rfc8785Canonicalizer.java
```

Crear:

```text
src/main/java/com/leonardorozza/mvgrreparacionesbackend/persistence/entity/enums/EsquemaRevisionLegal.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/persistence/entity/enums/PerfilAgregadoLegal.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalApplicabilityPolicy.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalApplicableScopeResolver.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalApplicableScopeSet.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalRequiredSetAggregateProjection.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalRequiredSetAggregateRevisionCalculator.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalRequiredSetAggregateProvenance.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalRequiredSetAggregateProvenanceCalculator.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalApplicableScopeSetTest.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalApplicableScopeResolverTest.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalRequiredSetAggregateRevisionCalculatorTest.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalRequiredSetAggregateProvenanceCalculatorTest.java
src/test/resources/legal/manifest/required-set-aggregate-v1/projection.json
src/test/resources/legal/manifest/required-set-aggregate-v1/canonical.json
src/test/resources/legal/manifest/required-set-aggregate-v1/sha256.txt
src/test/resources/legal/manifest/required-set-aggregate-provenance-v1/projection.json
src/test/resources/legal/manifest/required-set-aggregate-provenance-v1/canonical.json
src/test/resources/legal/manifest/required-set-aggregate-provenance-v1/sha256.txt
```

### Implementación

1. `LegalApplicableScopeSet` es una clase final con constructor no público. Hace copia defensiva,
   rechaza vacío, duplicados y más de ocho, y ordena por `ContextoLegal`.
2. `LegalApplicableScopeResolver` es la única factory. Consume una `LegalApplicabilityPolicy`
   servidor, no un array de contextos de transporte, y emite el valor acreditado que usa el store.
3. La policy productiva mínima de este corte fija `REGISTRATION = REGISTRO/ADMIN_TITULAR` y
   `AUTHENTICATED_PENDING` con al menos `USO_CONTINUADO`. La futura policy de ciclo de vida podrá
   agregar contextos antes de habilitar HTTP, sin abrir un constructor arbitrario.
4. Tests multicontexto usan una policy fixture interna; no agregan un bypass productivo.
5. `LegalRequiredSetAggregateProjection` contiene sólo
   `revisionScheme, locale, audiencia, scopes[{contexto, requiredSetRevision}]`.
6. Cada componente valida `sha256:<64-hex>` minúsculo.
7. El writer streaming de `Rfc8785Canonicalizer` conserva el patrón tipado existente; no agrega una
   entrada JSON genérica.
8. Los bytes RFC 8785 ordenan claves lexicográficamente aunque el JSON lógico sea legible. El test
   contrasta el writer con `org.erdtman.jcs.JsonCanonicalizer`.
9. El fingerprint de procedencia usa dominio separado e incluye perfil, locale, audiencia y el
   vector ordenado `(contexto, conjuntoId, publicacionId)`. Nunca se devuelve al frontend.
10. Fijar límites antes de copiar listas o serializar.

### Pruebas

- golden legible, bytes canónicos y SHA esperado;
- input permutado conserva token/fingerprint;
- cambio de revisión incluida, scope, locale o audiencia cambia token;
- un scope legítimamente excluido puede faltar o cambiar sin alterar el token del perfil;
- cambio sólo de UUID físico conserva token y cambia fingerprint;
- vector `REGISTRATION` de un scope;
- filtrar algunos o todos los pendientes, incluido `requisitos: []`, conserva el token porque el
  cálculo recibe revisiones de conjuntos completos y no evidencia filtrada;
- un conjunto V27 completo y deliberadamente vacío sigue aportando su revisión componente;
- perfil autenticado sin `USO_CONTINUADO`, vacío, duplicado, noveno scope o digest inválido falla;
- sólo el resolver puede construir el valor aceptado por el store; una policy inválida falla;
- reflexión/proyección confirma ausencia de actor, taller, rol wire, evidencia y pendientes;
- regresión de calculadores V27 y del fingerprint editorial.

```bash
./mvnw -Dtest=LegalApplicableScopeSetTest,LegalApplicableScopeResolverTest,LegalRequiredSetAggregateRevisionCalculatorTest,LegalRequiredSetAggregateProvenanceCalculatorTest,Rfc8785CanonicalizerTest,LegalRequiredSetRevisionCalculatorTest,LegalEditorialStateFingerprintCalculatorTest test
git diff --check
```

Commit:

```text
feat(legal): congela revision agregada v1
```

## Subcorte 12B — Gate editorial compartido

### Objetivo

Permitir que resolvers y aceptaciones convivan sin serializar todos los GET, conservando exclusión
con promociones y demás writers editoriales.

### Archivos

```text
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestDatabaseGate.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestDatabaseGateTest.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalJdbcMetricsSupport.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalJdbcMetricsSupportTest.java
```

### Implementación

1. Agregar un modo interno `EXCLUSIVE | SHARED` sin cambiar el default de las entradas actuales.
2. Import, dry-run, plan, apply, readiness y reconciliación continúan usando
   `pg_advisory_xact_lock` exclusivo.
3. Agregar una frontera mutable compartida para V28 con
   `pg_advisory_xact_lock_shared` sobre `EDITORIAL_LOCK_NAME`.
4. Mantener `REQUIRES_NEW`, `READ_COMMITTED`, datasource exacto, commit outcome seguro, presupuestos
   y preflights previos.
5. Leer `transaction_timestamp()` y `statement_timestamp()` únicamente después de que la sentencia
   de lock haya retornado.
6. No incorporar fallback de JVM ni retry automático.
7. Clasificar `_shared` como advisory lock en métricas, no como SELECT común.

### Pruebas

- SQL exacto por modo;
- orden `statement budget -> modo efectivo -> preflights -> lock budget -> lock -> graph budget ->
  relojes -> callback`;
- constructores/entradas V27 siguen el modo exclusivo;
- frontera inválida, preflight fallido o lock fallido evita callback;
- causalidad `observedAt >= transactionAt` sin asumir igualdad;
- métricas reconocen ambas funciones advisory.

```bash
./mvnw -Dtest=LegalManifestDatabaseGateTest,LegalJdbcMetricsSupportTest,LegalEditorialTimeBoundaryTest,LegalEditorialTransactionBoundaryTest,LegalImportTransactionBoundaryTest test
git diff --check
```

Commit:

```text
feat(legal): habilita gate compartido de requisitos
```

## Subcorte 12C — Store JDBC y replay unitarios

### Objetivo

Implementar el núcleo SQL package-private contra una frontera ya protegida, sin wiring HTTP ni
dependencia de una base migrada todavía.

### Archivos nuevos

```text
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateReceipt.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateStore.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateReplayVerifier.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateStoreTest.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateReplayVerifierTest.java
```

### Implementación

1. El store no abre su propia transacción ni acepta sets crudos: recibe exclusivamente el valor
   acreditado emitido por `LegalApplicableScopeResolver` y la frontera temporal del gate.
2. Consultar todos los punteros incluidos en una sola sentencia, ordenados canónicamente, con
   `FOR SHARE OF actual` y sentinel `LIMIT 9`.
3. Exigir cantidad, locale, audiencia, contextos y revisiones exactos; una ausencia falla cerrado.
4. Calcular token semántico y fingerprint físico con los calculadores de 12A.
5. Insertar cabecera con `INSERT ... ON CONFLICT ... DO NOTHING RETURNING` y miembros en batch;
   nunca N+1. No capturar `23505`, porque esa violación deja abortada la transacción PostgreSQL.
6. Si `RETURNING` queda vacío, releer la identidad completa. Sólo la igualdad exacta es replay.
7. Antes de retornar, el replay reconstruye cabecera/miembros, recalcula ambos hashes y compara el
   vector observado.
8. Un resultado distingue `CREATED` y `REUSED`, pero ambos devuelven token wire, UUID de cabecera y
   procedencia exacta.
9. No consultar evidencia ni filtrar pendientes.

### Pruebas

- SQL y parámetros exactos con `JdbcTemplate` mockeado;
- orden independiente del result set;
- missing/duplicate/ninth/mixed/invalid revision falla antes de DML;
- insert batch y replay antes del retorno;
- conflicto igual reutiliza; conflicto diferente falla;
- mismo token con procedencia nueva crea identidad física nueva;
- scope excluido ausente/cambiado no se consulta ni modifica el resultado;
- el store acepta un snapshot completo deliberadamente vacío y rechaza uno incompleto; no consulta
  tablas de evidencia o pendientes, aun cuando el consumidor termine mostrando `requisitos: []`;
- callback fallido no se convierte en éxito.

```bash
./mvnw -Dtest=LegalRequiredSetAggregateStoreTest,LegalRequiredSetAggregateReplayVerifierTest,LegalRequiredSetAggregateRevisionCalculatorTest,LegalManifestDatabaseGateTest test
git diff --check
```

Commit:

```text
feat(legal): implementa store de agregados legales
```

## Subcorte 12D — Migración V28 final, esquema e historia

### Objetivo

Instalar la forma definitiva V28, acreditar catálogos y migrar evidencia existente sin modificarla.
Este es el único subcorte que crea o edita el archivo Flyway V28.

### Archivos principales

Crear:

```text
src/main/resources/db/migration/V28__persistencia_revision_legal_agregada.sql
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateDatabaseConfiguration.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV28AggregateInventory.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV28AggregateSchemaVerifier.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV28AggregatePrivilegeVerifier.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRestrictedAggregateRoleFixture.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateDatabaseConfigurationTest.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV28AggregateSchemaVerifierIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV28AggregatePrivilegeVerifierIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV28AggregatePersistenceIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV28AggregatePreFreezeSmokeIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateDatabaseIsolationIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateConcurrencyIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV28UpgradeIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV27AcceptanceHistoryFixture.java
```

Modificar según el impacto verificado:

```text
src/main/java/com/leonardorozza/mvgrreparacionesbackend/persistence/entity/legal/LegalAceptacionLote.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalDatabaseBoundaryMarker.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/PostgresMigrationIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/LegalPersistenceIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialRetireIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialProcessFixture.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestPersistenceITSupport.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalJdbcMetricsSupport.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV27ImportSchemaVerifierIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialSchemaVerifierIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalImportPrivilegeVerifierIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPrivilegeVerifierIT.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestDatabaseGate.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateStore.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateReplayVerifier.java
```

No modificar:

```text
src/main/resources/db/migration/V27__persistencia_legal_append_only.sql
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV27ImportInventory.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV27EditorialInventory.java
```

No crear entidades o repositories JPA para las dos tablas agregadas. El camino aprobado es JDBC y
el inventario V28 acredita el esquema. `LegalAceptacionLote` sólo mapea scheme, perfil nullable y UUID
de agregado para poder leer historia y representar lotes nuevos sin abrir otra superficie de DML.

### Orden interno de V28

1. Crear `legal_requisito_agregados` y `legal_requisito_agregado_scopes`.
2. Agregar PK, uniques de identidad física/FK, checks, índices no concurrentes y FKs existentes a
   snapshots V27.
3. Reutilizar `legal_rechazar_update_delete()` para inmutabilidad.
4. Agregar guard sidecar que compara la revisión copiada con la fila V27 referenciada.
5. El mismo guard exige que `(conjunto_id, publicacion_id)` sea exactamente el puntero de
   `legal_requisito_conjuntos_actuales` para locale, contexto y audiencia bajo el lock ya poseído.
6. Agregar constraint trigger diferible de cardinalidad, ordinales `1..scope_count` y cabecera no
   parcial.
7. Agregar a `legal_aceptacion_lotes` `revision_scheme NOT NULL DEFAULT 'SCOPE_V1'`, perfil nullable
   y UUID de agregado nullable.
8. Agregar matriz `SCOPE_V1 => perfil/agregado NULL` y
   `AGGREGATE_V1 => perfil/agregado NOT NULL`.
9. Agregar FK compuesta lote → cabecera que pruebe perfil, audiencia, scheme y revisión.
10. Reemplazar mediante V28 los guards de lote/acto y validaciones diferidas necesarias. Mantener
   actor/rol, `xmin`, documentos, metadata e idempotencia de V27.
11. Rechazar explícitamente todo lote nuevo distinto de `AGGREGATE_V1`.
12. El guard de lote vuelve a comparar todos los miembros del agregado con los punteros actuales;
    un agregado histórico no puede consumirse como actual. También exige
    `REGISTRATION = {REGISTRO}` y `AUTHENTICATED_PENDING` con `USO_CONTINUADO`.
13. Verificar mediante `pg_locks` que la sesión posee la key editorial compartida o exclusiva antes
    de insertar; el trigger no adquiere el lock.
14. Fijar `creado_en` y `aceptado_en` con `statement_timestamp()` en la sentencia posterior al lock.
15. Fijar `search_path=pg_catalog,<schema>,pg_temp`, `SECURITY INVOKER` y revocar
    `PUBLIC EXECUTE` en funciones nuevas o reemplazadas.
16. Retirar el default de `revision_scheme` al final. No ejecutar `UPDATE` histórico.

### Migración histórica

1. Los tests V27 usan target explícito `27`; nunca `latest` accidental.
2. Sembrar actor, lote, actos, documentos, metadata e idempotencia válidos antes de V28.
3. Capturar valores, conteos, relaciones y `xmin` relevantes.
4. Migrar a V28 y comprobar `SCOPE_V1`, perfil/agregado nulos y cero reinterpretación.
5. Incluir el caso legacy multicontexto con revisiones coincidentes.
6. No crear historia SCOPE mediante INSERT después de V28.
7. Probar clean V1→V28, V27 vacío→V28 y V27 con historia→V28.
8. Acreditar checksum V27 intacto y una única fila Flyway V28 exitosa.
9. Toda fixture cuyo objetivo sea congelar el catálogo V27 usa target explícito `27`, incluidos
   schemas alternativos. Separadamente, el verifier V28 ejecuta los preflights V27 sobre latest para
   probar que import/editorial siguen funcionando y que la superficie nueva no amplía sus grants.
10. En `LegalV28UpgradeIT`, después del upgrade con historia, crear un lote `AGGREGATE_V1` válido en
    la misma base. Leer ambos schemes, volver a comparar bytes, conteos, relaciones y `xmin` de la
    historia, y acreditar que un nuevo `SCOPE_V1` se rechaza.
11. Incluir un puntero V27 actual hacia un snapshot completo con cero requisitos; V28 lo materializa
    como componente válido y no lo confunde con un snapshot parcial.

### Inventario y privilegios

1. V28 compone, no reescribe, los verificadores V27.
2. Inventariar dos tablas nuevas, `legal_aceptacion_lotes` alterada, tablas de evidencia consumidas,
   constraints, índices, triggers, funciones, ACL y Flyway 27/28.
3. El rol materializador V28 es `NOINHERIT`, no owner, no comparte credencial con Flyway y sólo se
   inyecta en `LegalRequiredSetAggregateDatabaseConfiguration`.
4. El rol web y los roles import/editorial conservan cero DML sobre objetos V28.
5. El rol materializador sólo posee SELECT/DML/EXECUTE nominal necesario; sin DDL, ownership,
   grant option, membresías, `TEMP`, otros schemas o funciones inesperadas.
6. `LegalDatabaseBoundaryMarker.Kind.AGGREGATE` impide mezclar datasource/transaction manager del
   materializador con import, dry-run, editorial o el contexto web.
7. Si no puede acreditarse separación migration-owner/materializador/web, detener el corte. No
   simularla con una conexión owner.

### Smoke integrado previo a congelar V28

`LegalV28AggregatePreFreezeSmokeIT` es obligatorio dentro de 12D. Debe usar, sin mocks:

1. el rol restringido V28;
2. `LegalManifestDatabaseGate` en modo shared sobre la key real;
3. el resolver acreditado de 12A;
4. `LegalRequiredSetAggregateStore` y replay de 12C;
5. la migración V28 candidata y sus triggers reales.

El smoke acredita que el lock shared aparece en `pg_locks` como espera el guard, que los nombres y
tipos SQL coinciden con el store, que ACL/trigger functions son ejecutables y que el timestamp del
INSERT es posterior a la sentencia de lock. La whitelist permite corregir gate/store/replay antes de
crear el commit 12D. Después del commit, ningún mismatch de esquema se corrige editando V28.

Además, antes del commit 12D se ejecutan obligatoriamente los escenarios schema-sensitive que no se
pueden diferir:

1. paridad exacta entre `EDITORIAL_LOCK_NAME`, `hashtextextended` Java/SQL y la key observada en
   `pg_locks`;
2. orden JDBC en dos sentencias: lock retornado y recién después INSERT; no existe una entrada que
   combine lock+INSERT en un CTE o statement genérico;
3. materialización creada/reutilizada con store y replay reales;
4. aislamiento de la credencial materializadora y ausencia de DML en el rol web;
5. carrera de dos materializaciones físicas idénticas;
6. compatibilidad shared/shared y bloqueo shared/exclusive;
7. transacción iniciada antes de esperar con timestamp causal posterior;
8. rollback total ante fallo diferido.

12E puede agregar el wrapper de servicio y 12F ampliar cobertura/capacidad, pero ninguno puede ser la
primera prueba de una propiedad que dependa del SQL V28.

### Gates antes del commit

```bash
./mvnw -Dtest=LegalRequiredSetAggregateRevisionCalculatorTest,LegalManifestDatabaseGateTest,LegalRequiredSetAggregateDatabaseConfigurationTest test
./mvnw -Dit.test=PostgresMigrationIT,LegalV28UpgradeIT,LegalV28AggregatePersistenceIT,LegalV28AggregatePreFreezeSmokeIT,LegalRequiredSetAggregateIT,LegalRequiredSetAggregateDatabaseIsolationIT,LegalRequiredSetAggregateConcurrencyIT,LegalPersistenceIT verify
./mvnw -Dit.test=LegalV28AggregateSchemaVerifierIT,LegalV28AggregatePrivilegeVerifierIT,LegalV27ImportSchemaVerifierIT,LegalEditorialSchemaVerifierIT,LegalImportPrivilegeVerifierIT,LegalEditorialPrivilegeVerifierIT verify
./mvnw -Dit.test=LegalEditorialRetireIT,LegalEditorialProcessIT verify
./mvnw clean verify
git diff --check
```

Antes de commitear, calcular y registrar el checksum de V27 y el nuevo checksum V28. Una vez creado
el commit, V28 queda congelada.

Commit:

```text
feat(legal): agrega persistencia multicontexto v28
```

## Subcorte 12E — Servicio interno e integración PostgreSQL

### Objetivo

Orquestar gate compartido, store y replay contra V28 con un rol restringido, sin exponer un bean HTTP.

### Archivos

Crear:

```text
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateService.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateServiceTest.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateServiceIT.java
```

Modificar para la composición explícita:

```text
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateDatabaseConfiguration.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateStore.java
src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateReplayVerifier.java
```

No modificar V28.

### Implementación y pruebas

1. El servicio solicita el valor opaco al resolver autoritativo; no acepta contextos crudos.
2. `LegalRequiredSetAggregateDatabaseConfiguration` ensambla un bean interno del servicio con el
   resolver, preflight V28, gate compartido, store y replay del contexto `AGGREGATE`; nunca usa el
   datasource o transaction manager web/import/editorial.
3. Ejecuta preflight V28, lock compartido, frontera post-lock, store y replay en una única
   transacción `REQUIRES_NEW/READ_COMMITTED`.
4. Nunca recibe un array desde una frontera HTTP ni se registra como controller.
5. Materialización repetida devuelve `REUSED` sin DML.
6. Un snapshot físico nuevo con las mismas revisiones conserva token y crea procedencia nueva.
7. Missing/incomplete/stale/collision/mismatch falla cerrado y revierte.
8. La prueba de composición carga la configuración productiva y acredita el
   `LegalDatabaseBoundaryMarker.Kind.AGGREGATE`; no construye el servicio manualmente.
9. Con la misma composición actual, agregar, cambiar o retirar evidencia y simular filtros de
   pendientes parciales/totales; el token no cambia y permanece presente con `requisitos: []`.
10. Probar inmutabilidad, FK, ordinal, digest componente, nuevo SCOPE rechazado, pertenencia de acto,
   `xmin` y timestamp causal.
11. Probar que el rol no puede editar V27, hacer DDL, saltar guards ni usar otros schemas.

```bash
./mvnw -Dtest=LegalRequiredSetAggregateServiceTest,LegalRequiredSetAggregateStoreTest,LegalRequiredSetAggregateReplayVerifierTest test
./mvnw -Dit.test=LegalRequiredSetAggregateServiceIT,LegalRequiredSetAggregateIT,LegalRequiredSetAggregateDatabaseIsolationIT,LegalV28AggregatePersistenceIT verify
./mvnw clean verify
git diff --check
```

Commit:

```text
feat(legal): materializa agregados legales v28
```

## Subcorte 12F — Concurrencia, causalidad y capacidad

### Objetivo

Acreditar que V28 progresa en paralelo para lectores, bloquea correctamente writers y no confirma
estados parciales bajo fallos reales.

### Archivos

Crear:

```text
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateCapacityIT.java
```

Modificar sólo las pruebas/helpers necesarios:

```text
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRequiredSetAggregateConcurrencyIT.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestPersistenceITSupport.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalJdbcMetricsSupport.java
src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalJdbcMetricsSupportTest.java
```

No modificar V28.

### Escenarios

1. Dos locks shared progresan simultáneamente.
2. Un writer exclusive espera a los shared; un shared espera a un exclusive ya adquirido.
3. La promoción concurrente produce el vector íntegro anterior o posterior, nunca mezcla.
4. Dos materializaciones físicas iguales convergen a una fila; identidades distintas con igual token
   conservan dos procedencias.
5. Una transacción iniciada antes de esperar persiste `creado_en/aceptado_en` después del lock y del
   estado observado.
6. Timeout, deadlock inducido, session kill y fallo diferido dejan cero cabeceras, miembros o lotes
   parciales.
7. Retry exacto converge; no existe retry interno automático.
8. Máximo ocho scopes, sentinel noveno, query única de punteros e inserts batch sin N+1.
9. Métricas distinguen advisory shared/exclusive, SELECT, row-lock, DML y control transaccional.
10. Usar latches, PIDs, locks y checkpoints; no sleeps como sincronización.

```bash
./mvnw -Dtest=LegalJdbcMetricsSupportTest,LegalManifestDatabaseGateTest test
./mvnw -Dit.test=LegalRequiredSetAggregateConcurrencyIT,LegalRequiredSetAggregateCapacityIT,LegalEditorialConcurrencyIT,LegalEditorialCapacityIT verify
./mvnw clean verify
git diff --check
```

Commit:

```text
test(legal): acredita concurrencia de agregados v28
```

## Subcorte 12G — Cierre documental y gate integral

### Objetivo

Registrar evidencia fresca sin afirmar que las APIs o el lanzamiento público ya existen.

### Archivos esperados

```text
docs/plans/2026-09-01-legal-required-set-aggregate-v28-design.md
docs/plans/2026-09-01-legal-required-set-aggregate-v28-implementation.md
docs/plans/2026-09-01-legal-required-set-aggregate-v28-closure.md
README.md
FRONTEND_INTEGRATION.md
```

### Cierre

1. Marcar diseño/plan implementados con SHAs de 12A–12F.
2. Crear closure con JDK, Maven, Flyway, Testcontainers, PostgreSQL, conteos Surefire/Failsafe,
   duración, checksums V27/V28 y hashes de JARs.
3. Registrar clean install, tres upgrades, historia preservada, locks shared/exclusive, causalidad,
   rollback, capacidad e inventario.
4. Corregir la vieja semántica del hash filtrado en `FRONTEND_INTEGRATION.md`: token agregado de
   conjuntos completos, estable aunque `requisitos: []`.
5. Mantener el wire opaco y sin mapa por contexto; no editar el repositorio frontend.
6. Mantener pendientes HTTP, `documentSetRevision`, catálogo, aceptación de aplicación,
   idempotencia, `409/428/503`, enforcement, contenido real, staging y deploy.
7. Mantener `BACKEND-HANDOFF 1` cerrado.

### Gate final

```bash
java -version
./mvnw -version
./mvnw clean verify
git diff --check
git status --short
git log --oneline -10
```

Validar además:

- Flyway termina exactamente en V28;
- V27 conserva archivo y checksum;
- ninguna función legal carece de `search_path` seguro;
- no hay `PUBLIC EXECUTE` inesperado;
- roles import/editorial no obtuvieron objetos V28;
- no existen controllers/DTOs/endpoints nuevos;
- frontend y contenido real permanecen intactos;
- no hubo push o deploy.

Commit:

```text
docs(legal): cierra persistencia multicontexto v28
```

## Matriz de parada

| Hallazgo | Acción |
| --- | --- |
| V27 cambia o falla su checksum | Revertir el cambio local de V27 y detener el subcorte |
| Un test SQL requerido falla antes del commit 12D | Corregir V28 y repetir todo 12D |
| Un defecto de esquema aparece después del commit 12D | No editar V28; detenerse y diseñar V29 |
| El runtime V28 requiere owner/Flyway credential | Detenerse; separar roles antes de continuar |
| Un INSERT puede crear `SCOPE_V1` nuevo | Bloqueante P0 |
| El trigger adquiere lock y fecha dentro de la misma sentencia | Bloqueante P0 causal |
| Shared/shared se serializa globalmente | Bloqueante de capacidad |
| Shared/exclusive permite mezcla editorial | Bloqueante de integridad |
| Historia V27 cambia o se revalida | Bloqueante de evidencia |
| Se requiere decidir scopes desde HTTP | Fuera de alcance; detener y diseñar el corte HTTP |
| Se requiere cambiar el wire frontend | Fuera de alcance; coordinar después de V28 |

## Resultado esperado

Al cerrar Corte 12 deben existir siete commits funcionales/documentales posteriores al commit de
este plan, todos locales y sin push. V28 podrá resolver y persistir una revisión agregada auditable,
pero OrdenFix seguirá sin APIs legales públicas. El siguiente diseño será la capa HTTP; no se inicia
automáticamente dentro de este corte.
