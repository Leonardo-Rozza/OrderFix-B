# Corte 8 — Plan de implementación del retiro editorial RETIRE fail-closed

Fecha: 2026-08-30

Estado: en ejecución — diseño aprobado; Subcorte 8A pendiente

Diseño aprobado:

- `docs/plans/2026-08-30-legal-retire-fail-closed-design.md`;
- commit local `5d2c358` y precisión de evidencia de replay `9991857`;
- rama `codex/lanzamiento-publico-backend`.

Este documento descompone el Corte 8 de la Fase 2.3C en subcortes pequeños. Cada subcorte empieza
por una prueba roja o de caracterización, termina con diff limpio y se registra en un commit local
atómico. No se hace push ni deploy.

## Invariantes comunes

1. `RETIRE` es una herramienta editorial interna. No agregar endpoint, controller, JPA, frontend,
   scheduler ni permisos para usuarios de talleres.
2. No modificar V27/V28, schemas JSON, grants, roles, inventarios ni funciones SQL.
3. Current y target son el mismo UUID; sólo se retiran miembros enumerados explícitamente.
4. Motivo, digest, contexto, audiencia, acknowledgement y readiness esperado se validan antes del
   primer DML.
5. El postestado representa el grafo completo. Nunca se deriva copiando una proyección mutable
   incompleta.
6. Todo miembro, historia, lote, slot y puntero no afectado se preserva exactamente, incluido el
   `updatedAt` de cada puntero sobreviviente.
7. El delta RETIRE contiene sólo deletes de punteros/slots y transiciones terminales; no contiene
   inserts de proyecciones, sucesoras, rebinds o lotes.
8. Apply usa una única sesión JDBC y transacción `REQUIRES_NEW/READ_COMMITTED` bajo el advisory
   lock editorial compartido.
9. El orden DML es punteros, slots, requisitos y documentos; cada batch exige cardinalidad exacta.
10. Writer, post-verifier, constraints y readiness mantienen responsabilidades separadas.
11. Después de `SET CONSTRAINTS ALL IMMEDIATE` sólo se ejecuta readiness; no hay más DML.
12. Un apply fresco sólo confirma si el readiness real es exactamente `NOT_READY`.
13. Replay se acredita por postestado, no por autoría de `operationId`; no se agrega ledger V27.
14. `persisted=true` acredita el estado editorial confirmado, no una fila de receipt persistida.
15. Replay o BLOCKED previo al DML no avanza secuencias. Rollback tardío puede dejar huecos en
    secuencias PostgreSQL sin representar persistencia parcial.
16. No agregar retries, force, healing, fallback a otra operación ni hooks productivos de fallo.
17. PROMOTE, REPLACE, reportes v1/v2 y demás comandos v3 permanecen compatibles.
18. Antes de cada commit: puerta focal, revisión adversarial, `git diff --check` y
    `git status --short`.

## Subcorte 8A — Invariantes del execution plan

Estado: pendiente.

### Objetivo

Preparar la forma operation-aware que permite a RETIRE transportar proyecciones preservadas sin
confundirlas con inserts del delta. Este subcorte debe quedar verde antes de que el planner empiece
a emitir el postestado parcial completo.

### Modificar

- `LegalEditorialExecutionPlan`;
- `LegalEditorialExecutionPlanTest`;
- `LegalEditorialPostStateVerifierTest`;
- `LegalEditorialPostStateVerifier` sólo si una prueba demuestra una brecha de comparación;
- tests de construcción RETIRE de `LegalEditorialPlannerCoreTest` sólo para conservar
  compatibilidad durante la transición.

### Pasos

1. Caracterizar la matriz actual que exige slots/punteros/lotes finales vacíos para RETIRE.
2. Permitir colecciones finales no vacías cuando representen proyecciones históricas preservadas.
3. Exigir que cada transición terminal, delete de slot y delete de puntero corresponda al retiro
   explícito exacto.
4. Prohibir inserts de slots/punteros, altas, sucesoras, rebinds y lotes nuevos.
5. Prohibir que una clave eliminada aparezca también como sobreviviente.
6. No exigir `updatedAt == expectedAppliedAt` a punteros preservados.
7. Exigir `expectedAppliedAt` uniforme sólo para las transiciones nuevas del delta.
8. Separar lotes históricos esperados de comandos de lote actuales, que deben ser vacíos.
9. Mantener el verifier global: falta/exceso de membresía, historia, slot, puntero o lote bloquea el
   postestado.
10. Ejecutar regresión de execution plans PROMOTE y REPLACE.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialExecutionPlanTest,LegalEditorialPlannerCoreTest,LegalEditorialPostStateVerifierTest test
git diff --check
git status --short
~~~

Commit:

    fix(legal): valida plan exacto de retiro

## Subcorte 8B — Postestado parcial exacto

Estado: pendiente.

### Objetivo

Corregir la planificación RETIRE para clasificar toda la publicación current y representar de
forma exacta los elementos retirados y los sobrevivientes, todavía sin habilitar apply o CLI.

### Modificar

- `LegalEditorialPlannerCore`;
- `LegalEditorialPlannerCoreTest`;
- `LegalEditorialPlanServiceTest` sólo si cambia la evidencia devuelta por el planner;
- `LegalEditorialPlannerIT` para acreditar la lectura real de proyecciones preservadas.

### Pasos

1. Escribir casos document-only, requirement-only y mixto con miembros no afectados.
2. Hacer que `retirementPlan` clasifique la membresía documental y de requisitos completa.
3. Preservar el estado y la prehistoria exacta de todas las versiones no retiradas.
4. Agregar una sola transición terminal por versión explícita, con motivo y timestamp esperados.
5. Enriquecer la evidencia autoritativa de cada scope con miembros del conjunto sellado y
   versiones documentales referenciadas; derivar desde allí las claves sobrevivientes y la unión
   afectada tanto en SOURCE como en POST.
6. Acreditar existencia y unicidad de cada slot/puntero esperado antes de copiar sus valores; sólo
   los punteros transportan `updatedAt`.
7. Construir la unión exacta de punteros afectados por documentos y requisitos retirados.
8. Preservar todos los lotes históricos y prohibir lotes actuales nuevos.
9. Bloquear una proyección sobreviviente extra, faltante o incoherente sin normalizarla.
10. Conservar replay-first: el postestado ya exacto se compara antes de exigir el estado fuente.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialExecutionPlanTest,LegalEditorialPlannerCoreTest,LegalEditorialPlanServiceTest,LegalEditorialPostStateVerifierTest test
./mvnw -Dit.test=LegalEditorialPlannerIT verify
git diff --check
git status --short
~~~

Commit:

    fix(legal): preserva postestado parcial de retiro

## Subcorte 8C — Writer y aplicación RETIRE

Estado: pendiente.

### Objetivo

Ejecutar el delta RETIRE mediante un writer dedicado y coordinar replay, verificación, constraints
y postcondición `NOT_READY` dentro de una sola transacción.

### Crear

- `src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialRetirementWriter.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialRetirementWriterTest.java`;
- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialRetireServiceTest.java`.

### Modificar

- `LegalEditorialApplyService`;
- `LegalEditorialDatabaseConfiguration`;
- `LegalEditorialApplyServiceTest` y `LegalEditorialApplyServiceReplaceTest` por ensamblado y
  regresión;
- `LegalEditorialTransactionBoundaryTest`;
- `LegalManifestPersistenceITSupport` y ensamblados directos del apply service;
- failure mapper sólo si hace falta conservar el issue tipado exacto.

### Pasos

1. Escribir el contrato SQL exacto del writer antes de implementarlo.
2. Rechazar un plan que no sea RETIRE o contenga comandos PROMOTE/REPLACE antes del DML.
3. Bloquear publicación, líneas, versiones y proyecciones en orden determinista.
4. Revalidar current/target, estados, membresía, fingerprint y proyecciones bajo lock.
5. Eliminar la unión deduplicada de punteros afectados y exigir cardinalidad exacta.
6. Eliminar slots documentales afectados y exigir cardinalidad exacta.
7. Insertar transiciones de requisitos y luego documentos en orden UUID.
8. Mantener al writer fuera de replay, receipt, constraints y readiness.
9. Inyectar el writer dedicado y exponer `applyRetire` en el servicio.
10. Ejecutar `writer → verifier → constraints → readiness` en la misma sesión.
11. Rechazar un input cuyo readiness esperado no sea `NOT_READY` con
    `BLOCKED/EXPECTED_READINESS_MISMATCH` antes del DML.
12. Exigir readiness real `NOT_READY` después del DML; otra forma devuelve
    `ERROR/POSTCONDITION_NOT_READY`, `persisted=false` y rollback.
13. Congelar `APPLIED/ALREADY_APPLIED`, `persisted` y completion-state sin receipt tentativo.
14. Probar que PROMOTE/REPLACE continúan eligiendo sus writers exactos.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialRetirementWriterTest,LegalEditorialRetireServiceTest,LegalEditorialApplyServiceTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialTransactionBoundaryTest,LegalEditorialFailureMapperTest test
git diff --check
git status --short
~~~

Commit:

    feat(legal): aplica retiro editorial fail closed

## Subcorte 8D — PostgreSQL fresco

Estado: pendiente.

### Objetivo

Acreditar ejecuciones frescas documentales, de requisitos y mixtas sobre PostgreSQL 16/Flyway V27
con el rol editorial restringido exacto.

### Crear

- `src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialRetireIT.java`.

### Modificar

- `LegalManifestPersistenceITSupport` sólo para fixtures compartidas estrictamente necesarias;
- `LegalEditorialPrivilegeVerifierIT` sólo si hace falta ensamblar el writer nuevo.

### Escenarios

- retirar un documento y conservar otros documentos, requisitos, slots y punteros;
- retirar un requisito de un scope y conservar otros requisitos y audiencias;
- retirar documentos y requisitos en una misma operación;
- publicación con múltiples contextos, locales, audiencias y lotes históricos;
- motivos, estados, historia y `expectedAppliedAt` exactos;
- `updatedAt` de punteros sobrevivientes sin cambios;
- unión de punteros afectados eliminada una sola vez;
- triggers y constraints V27 satisfechos sin DML duplicado;
- snapshots, aceptación, idempotencia y tablas ajenas sin cambios;
- `APPLIED`, `persisted=true`, `NOT_READY` y receipt exacto;
- ejecución completa como el rol editorial restringido, sin grant nuevo.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialRetirementWriterTest,LegalEditorialRetireServiceTest,LegalEditorialPostStateVerifierTest test
./mvnw -Dit.test=LegalEditorialRetireIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialReadinessIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): acredita retiro editorial en postgresql

## Subcorte 8E — Replay, corrupción y rollback

Estado: pendiente.

### Objetivo

Demostrar que RETIRE es idempotente por postestado, no oculta corrupción y revierte exactamente un
fallo tardío.

### Modificar

- `LegalEditorialRetireIT`;
- soporte test-only de inyección JDBC sólo si es necesario;
- tests unitarios de writer/verifier/service únicamente si una integración revela una brecha.

### Escenarios

- replay exacto con la misma identidad externa;
- replay del mismo postestado con otro `operationId`/SHA, sin atribuir autoría histórica;
- cero cambios de filas y secuencias en replay o BLOCKED previo a DML;
- plan distinto que predice otro postestado bloqueado por terminalidad/fingerprint;
- slot, puntero, transición, membresía o lote sobreviviente extra/faltante sin healing;
- no atribuir al replay detección forense de una alteración aislada de `updatedAt` en un puntero
  sobreviviente, porque V27 no persiste la evidencia histórica necesaria;
- fallo después de deletes y transiciones, antes de permitir commit;
- rollback fila por fila de las 19 tablas editoriales;
- huecos de secuencia limitados a `nextval` realmente ejecutados y documentados como no
  transaccionales;
- regresión de post-verifier, constraints, readiness y completion-state.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialRetirementWriterTest,LegalEditorialRetireServiceTest,LegalEditorialPostStateVerifierTest test
./mvnw -Dit.test=LegalEditorialRetireIT,LegalEditorialDatabaseIsolationIT,LegalEditorialReadinessIT verify
git diff --check
git status --short
~~~

Commit:

    test(legal): acredita replay y rollback de retiro

## Subcorte 8F — CLI y reporte v3

Estado: pendiente.

### Objetivo

Exponer `plan-retire` y `apply-retire` sólo en la herramienta interna, con preflight estricto,
reporte sanitizado y exit codes coherentes con un éxito deliberadamente `NOT_READY`.

### Modificar

- `LegalEditorialArguments` y `LegalEditorialArgumentsTest`;
- `LegalEditorialCliExecutionState` y su test;
- `LegalEditorialReport`, `LegalEditorialReportWriter` y sus tests;
- `LegalManifestCli` y `LegalManifestCliTest`;
- `LegalEditorialCliTest`, `LegalEditorialPreflightTest`, `LegalEditorialPlanConfirmationTest` y
  `LegalEditorialEnvironmentTest`;
- `LegalManifestCliIsolationIT` y `LegalManifestCliProcessIT`;
- `scripts/legal-manifest-editor.sh` y su test sólo si el launcher enumera comandos.

### Pasos

1. Agregar comandos exactos `plan-retire` y `apply-retire`, sin aliases ni defaults.
2. Reutilizar ruta de manifest, ruta de plan y las cuatro confirmaciones literales estrictas.
3. Mantener siete tokens exactos en ambos comandos y habilitar apply sólo mediante el flag
   operativo existente `ORDENFIX_LEGAL_EDITOR_ENABLED=true`; no agregar otra confirmación.
4. Validar bundle, plan RETIRE, operationId, SHAs, current/target, acknowledgement y readiness antes
   de resolver entorno/Spring/JDBC.
5. Ejecutar `LegalEditorialReplaceScopeGuard` exclusivamente para comandos REPLACE.
6. Despachar al método plan/apply RETIRE exacto y retener identidad input-safe monotónica.
7. Admitir `operationType=RETIRE` y `expectedReadinessAfter=NOT_READY` en reporte v3.
8. Emitir exit 0 para `APPLIED` y `ALREADY_APPLIED` aunque readiness sea `NOT_READY`.
9. Mantener exit no cero y metadata sanitizada para `BLOCKED`, `ERROR` y `UNKNOWN`.
10. Probar proceso empaquetado y ausencia de secretos/rutas en stdout y JAR.
11. Confirmar que reportes v1/v2 y comandos PROMOTE/REPLACE no cambian.

### Puerta

~~~bash
./mvnw -Dtest=LegalEditorialArgumentsTest,LegalEditorialCliExecutionStateTest,LegalEditorialPreflightTest,LegalEditorialCliTest,LegalEditorialReportTest,LegalEditorialReportWriterTest,LegalEditorialPlanConfirmationTest,LegalEditorialEnvironmentTest,LegalManifestCliTest,LegalManifestEditorLauncherTest test
./mvnw -Dit.test=LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Commit:

    feat(legal): expone retiro editorial interno

## Subcorte 8G — Regresiones y cierre

Estado: pendiente.

### Objetivo

Cerrar Corte 8 con evidencia ejecutable, documentación alineada y ninguna expansión de superficie.

### Modificar

- `docs/plans/2026-08-30-legal-retire-fail-closed-design.md`;
- este documento;
- `docs/plans/2026-08-27-legal-manifest-promotion-implementation.md`;
- documentos históricos anteriores sólo con una nota de continuidad que evite una lectura
  engañosa.

No actualizar todavía runbooks de lanzamiento, `FRONTEND_INTEGRATION.md` o planes frontend: el
cierre cross-repo permanece reservado para el Corte 11.

### Puerta final

~~~bash
./mvnw -Dtest=LegalEditorialPlanValidatorTest,LegalEditorialExecutionPlanTest,LegalEditorialPlannerCoreTest,LegalEditorialPlanServiceTest,LegalEditorialRetirementWriterTest,LegalEditorialRetireServiceTest,LegalEditorialPostStateVerifierTest,LegalEditorialApplyServiceTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialArgumentsTest,LegalEditorialCliExecutionStateTest,LegalEditorialPreflightTest,LegalEditorialCliTest,LegalEditorialReportTest,LegalManifestCliTest test
./mvnw -Dit.test=LegalEditorialRetireIT,LegalEditorialSplitMergeIT,LegalEditorialReplaceIT,LegalInitialPromotionIT,LegalInitialPromotionFailureIT,LegalEditorialReadinessIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialDatabaseIsolationIT,LegalManifestImportIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
./mvnw test
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Registrar Java, PostgreSQL, Flyway, conteos reales, regresiones, revisión adversarial, ramas y
ausencia de push/deploy. Reconciliación ampliada de `UNKNOWN`, capacidad y procesos exhaustivos
permanecen en Cortes 9 y 10.

Commit:

    docs(legal): cierra retiro editorial fail closed

## Criterio de cierre

Corte 8 termina únicamente cuando 8A–8G están en commits locales separados; RETIRE preserva todo
el estado no afectado; documentos, requisitos y mixto terminan `APPLIED+NOT_READY`; replay es
select-only; corrupción no se repara; rollback es exacto; el rol no obtiene nuevos grants; CLI es
la única superficie incorporada; PROMOTE/REPLACE/import siguen verdes; y no hubo push ni deploy.
