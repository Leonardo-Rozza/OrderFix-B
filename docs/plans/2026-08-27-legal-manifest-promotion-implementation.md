# Fase 2.3C — Plan de implementación de promoción y readiness editorial

Fecha: 2026-08-27

Estado: plan aprobado por continuidad del diseño; ejecución en curso, Cortes 1 a 8 completados;
Corte 9 con diseño y plan específico registrados, Subcorte 9A completado el 2026-08-30, 9B
y 9C completados el 2026-08-31; 9D1, 9D2, 9E y 9F pendientes; Cortes 10 y 11 pendientes

Diseño aprobado:

- docs/plans/2026-08-27-legal-manifest-promotion-design.md;
- commit local backend d06d92b para el diseño base; la precisión del grant técnico sobre
  publicaciones se registra junto con este plan;
- continuidad de 2.3B cerrada en 0bf785a;
- mirror frontend de 2.3B cerrado en 50f9d69.

Este plan implementa readiness editorial, planificación, primera promoción, cutover, split/merge,
retiro y reconciliación sobre PostgreSQL 16 y V27. No agrega endpoints, UI, V28, aceptación,
enforcement, deploy ni contenido legal real.

Antes del Corte 1, este documento se registra por separado con el commit:

    docs(legal): planifica promocion y readiness editorial

Ningún archivo funcional participa de ese commit de planificación. Cada corte posterior termina en
un commit local atómico; el cierre cross-repo usa un commit documental por repositorio. No se hace
push.

## Reglas de ejecución

1. Trabajar en backend sobre codex/lanzamiento-publico-backend y en frontend sobre
   codex/frontend-refactor-checkpoint.
2. Preservar los no versionados frontend .agents/ y public/OrdenFix project naming/. Nunca incluirlos
   en un add, commit, build público o artefacto.
3. Empezar cada corte por la prueba que demuestra la nueva invariante o por la regresión que protege
   un contrato existente.
4. Mantener validate y dry-run byte-compatible en reporte v1 e import byte-compatible en v2.
5. No modificar V27, LegalV27ImportInventory, LegalV27ImportSchemaVerifier,
   LegalImportPrivilegeVerifier ni el rol importador.
6. No modificar publication-manifest.schema.json en ninguno de los repositorios.
7. No introducir endpoint, controller, JPA, repository, scheduler, runner, botón, query, mutation,
   flag frontend o commit=true.
8. El contexto editorial es JDBC aislado. No reutilizar entity/legal ni repository/legal.
9. Readiness y plan son readOnly=true, usan advisory lock y SELECT simples. Nunca ejecutan
   SELECT FOR UPDATE/SHARE ni legal_validar_publicacion_sellada(uuid).
10. Apply abre exactamente un gate REQUIRES_NEW/READ_COMMITTED. PlannerCore y ReadinessCore reciben
    su misma sesión JDBC y el transaction_timestamp caller-owned; no abren gates ni leen otro
    instante.
11. Ninguna lectura de estado mutable ocurre antes de adquirir el advisory lock, salvo preflights de
    schema y privilegios.
12. Las mutaciones bloquean publicaciones, líneas y versiones en orden UUID determinista. Slots y
    punteros se eliminan en orden de su PK bajo el advisory lock, sin pedir row locks que amplíen
    grants.
13. Se lee una sola vez transaction_timestamp por apply y se usa para precondiciones, transiciones
    y receipt.
14. SET CONSTRAINTS ALL IMMEDIATE se ejecuta después del delta completo. Después sólo se permite
    ReadinessCore en la misma sesión; no más DML.
15. Un replay o BLOCKED previo a DML no avanza secuencias. Un rollback tardío puede dejar huecos en
    secuencias PostgreSQL y no se interpreta como persistencia parcial.
16. No agregar switches productivos para simular fallos. Commit incierto, desconexión y stdout roto
    se inyectan mediante transaction managers, proxies o procesos de test.
17. No usar los borradores reales. Todas las promociones y retiros de prueba usan fixtures
    sintéticas.
18. Antes de cada commit: revisar el diff, ejecutar la puerta enfocada, git diff --check y git
    status --short.
19. Si una prueba demuestra que V27 no alcanza, detener el corte y diseñar V28; no debilitar
    constraints, fingerprint, rol ni postcondiciones.
20. Al cierre: suite backend completa, regresión frontend, paridad del schema y evidencia de ramas,
    sin push ni deploy.

## Invariantes globales

- Entrada de contenido: LegalManifestValidator.ValidatedRelease.
- Entrada destructiva: token opaco ValidatedEditorialPlan producido por el parser estricto.
- Base: PostgreSQL 16 con V27 exacta; la herramienta no ejecuta Flyway.
- Lock: ordenfix:legal-publicaciones:sello:v1, compartido con dry-run e import.
- Presupuestos iniciales: transacción 75 s, advisory/statement 30 s y locks restantes 5 s.
- Primera promoción: cero historia editorial, lotes sellados, slots y punteros previos.
- Replay: comparar postestado exacto antes de exigir el estado fuente.
- Fuente REPLACE/RETIRE: publicationId, SHA y editorialStateFingerprint exactos.
- PROMOTE y REPLACE sólo confirman con readinessAfter=READY.
- RETIRE sólo confirma con expectedReadinessAfter=NOT_READY y
  acknowledgeFailClosedGap=true dentro del plan hasheado.
- El operationId y SHA del plan son correlación/confirmación externa. V27 no los persiste.
- La idempotencia DB se acredita por postestado: estados, historial, motivos, lotes, slots y
  punteros.
- documentSetRevision permanece fuera de 2.3C.
- requiredSetRevision se reconstruye y compara por cada scope.
- BACKEND-HANDOFF 1 continúa cerrado después de esta fase.

## Superficie CLI congelada

Los comandos editoriales viven en el legal-cli.jar existente, pero tienen parser, contexto, report y
execution state propios.

| Comando | Escritura | Plan externo | Resultado principal |
|---|---:|---:|---|
| readiness | no | no | READY o NOT_READY |
| plan-promote | no | no | APPLICABLE o BLOCKED |
| apply-promote | sí | no | APPLIED o ALREADY_APPLIED |
| plan-replace | no | sí | APPLICABLE o BLOCKED |
| apply-replace | sí | sí | APPLIED o ALREADY_APPLIED |
| plan-retire | no | sí | APPLICABLE o BLOCKED |
| apply-retire | sí | sí | APPLIED o ALREADY_APPLIED |

Todos exigen exactamente una vez:

- --manifest=<ruta>;
- --confirm-publication-id=<id>;
- --confirm-manifest-sha256=<64-hex>.

Los cuatro comandos REPLACE/RETIRE exigen además:

- --editorial-plan=<ruta>;
- --confirm-operation-id=<uuid>;
- --confirm-editorial-plan-sha256=<64-hex>.

El tipo interno del plan debe coincidir con REPLACE o RETIRE según el comando crudo. No existen
aliases, argumentos separados, duplicados, extras, prompt, force ni booleano que convierta plan en
apply.

El preflight ocurre siempre en este orden, sin resolver todavía el entorno editorial:

1. parsear el subcomando y sus argumentos estrictos;
2. confinar, leer y validar el bundle hasta obtener ValidatedRelease;
3. para REPLACE/RETIRE, confinar, leer y validar el plan hasta obtener ValidatedEditorialPlan;
4. comparar publicationId y SHA del manifiesto confirmados;
5. cuando hay plan, comparar su tipo con el comando, operationId y SHA confirmados;
6. sólo entonces resolver el entorno, comprobar el habilitador de apply y abrir el contexto JDBC.

Cualquier diferencia de ruta, contenido, ID, SHA, operationId o tipo termina con cero resolución de
credenciales, cero contexto Spring y cero conexión PostgreSQL.

El contexto usa exclusivamente:

- ORDENFIX_LEGAL_EDITOR_DB_URL;
- ORDENFIX_LEGAL_EDITOR_DB_USERNAME;
- ORDENFIX_LEGAL_EDITOR_DB_PASSWORD;
- ORDENFIX_LEGAL_EDITOR_DB_DRIVER_CLASS_NAME, opcional;
- ORDENFIX_LEGAL_EDITOR_ENABLED=true sólo para apply-*.

Las propiedades JVM spring.datasource.* se rechazan. El password no aparece en argumentos, JSON,
stderr ni system properties.

## Reporte editorial v3

Los nuevos comandos usan reportVersion=3 con orden estable:

    reportVersion, command, status, persisted, publication, operation,
    plan, readiness, counts, issues, omittedIssueCount

Matriz cerrada:

| Caso | status | persisted | outcome | readiness | exit |
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

changeRequired indica si un plan APPLICABLE escribiría. Los constructores y tests de resultados de
los Cortes 1, 3 y 4 cierran sus respectivas filas; el Corte 5 sólo las serializa. La finalización
indeterminada nunca expone metadata derivada del commit.

stdout contiene un único JSON. stderr está vacío en PASS y sólo admite un resumen constante
allowlisteado en fallo. RETIRE APPLIED+NOT_READY es éxito solicitado y devuelve 0.

Los reportes v1 y v2 no reciben campos, orden, mensajes ni nulls nuevos.

## Corte 1 — Contrato de readiness y fingerprint editorial

Estado: completado el 2026-08-28.

### Objetivo

Congelar los tipos puros, matriz cerrada y fingerprint del estado editorial antes de leer
PostgreSQL.

### Archivos

Crear:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalEditorialReadiness.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalEditorialStateProjection.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalEditorialStateFingerprintCalculator.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessObservation.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessResult.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalEditorialStateFingerprintCalculatorTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessResultTest.java;
- src/test/resources/legal/manifest/editorial-state-fingerprint-v1/projection.json;
- src/test/resources/legal/manifest/editorial-state-fingerprint-v1/canonical.json;
- src/test/resources/legal/manifest/editorial-state-fingerprint-v1/sha256.txt.

Modificar:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalManifestIssueCode.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/Rfc8785Canonicalizer.java.

### Implementación

1. Modelar READY, NOT_READY y ERROR sin reutilizar LegalManifestValidation. NOT_READY conserva
   observation y fingerprint; ERROR no los expone.
2. Cerrar por constructor la matriz PASS/READY, BLOCKED/NOT_READY y ERROR/ERROR con
   persisted=false.
3. Proyectar para el fingerprint:
   - identidad y SHA de la publicación current;
   - slots actuales ordenados por tipo, locale y contexto;
   - punteros actuales ordenados por locale, contexto y audiencia;
   - estados, timestamps, motivo y reemplazo_lote_id de las versiones referenciadas;
   - lotes y miembros relacionados;
   - versiones no BORRADOR que puedan interferir;
   - excluir publicaciones nuevas completamente BORRADOR.
4. No incluir Markdown, afirmaciones, rutas, operador ni secretos; usar digests.
5. Canonicalizar la proyección tipada con RFC 8785 y emitir
   sha256:<64-hex>.
6. Fijar orden de propiedades y arrays mediante projection/canonical/sha golden.
7. Agregar los reason codes editoriales exactos del diseño con mensajes constantes.
8. Conservar capping, orden y redacción de LegalManifestIssue.
9. Acreditar que documentSetRevision no aparece en ningún tipo.

### Pruebas y puerta

Cubrir cambio de fingerprint ante cualquier slot, puntero, estado, motivo, lote o digest; estabilidad
ante distinto orden de entrada; listas defensivas; matrices inválidas no construibles; ERROR sin
observación y NOT_READY con observación.

~~~bash
./mvnw -Dtest=LegalEditorialStateFingerprintCalculatorTest,LegalEditorialReadinessResultTest,Rfc8785CanonicalizerTest,LegalRequiredSetRevisionCalculatorTest,LegalManifestReportTest,LegalManifestReportWriterTest,LegalManifestImportReportTest,LegalManifestImportReportWriterTest test
git diff --check
git status --short
~~~

Commit:

    feat(legal): define readiness editorial

### Evidencia de cierre

- Java 21 (Corretto 21.0.10), puerta enfocada: 95 tests, 0 fallos, 0 errores y 0 omitidos.
- Suite unitaria backend completa: 752 tests, 0 fallos, 0 errores y 0 omitidos.
- Golden RFC 8785 congelado en
  `sha256:d2cb96b3e9300f2febf428e875fd552d8e4700e76847c4b76a3680cfdb187b78`.
- Reportes históricos validate/dry-run v1 e import v2 permanecen byte-compatibles.
- `git diff --check` limpio. Este corte no abre PostgreSQL, no ejecuta DML y no modifica V27,
  endpoints, frontend ni configuración de despliegue.

## Corte 2 — Evaluador PostgreSQL read-only

Estado: completado el 2026-08-28.

### Objetivo

Observar el target real completo bajo el advisory lock, sin DML, row locks ni funciones V27 que
adquieran locks incompatibles con readOnly=true.

### Archivos

Crear:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestOriginGraphVerifier.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessCore.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessService.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV27EditorialInventory.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialSchemaVerifier.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialFailureMapper.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialBlockedException.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialOperationalException.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessCoreTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialSchemaVerifierIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialExceptionTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialFailureMapperTest.java.

Modificar:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestReplayVerifier.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestDatabaseGate.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalManifestIssueCode.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessResult.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestReplayVerifierTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestDatabaseGateTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessResultTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestPersistenceITSupport.java.

### Implementación

1. Extraer de LegalManifestReplayVerifier la comparación SELECT-only del grafo inmutable.
2. Mantener en replay importador sus FOR UPDATE y legal_validar_publicacion_sellada; readiness nunca
   los invoca.
3. Fingerprintar las 19 tablas de la superficie editorial V27, sus 10 identity sequences, funciones,
   triggers y constraints, sin ampliar el inventario importador.
4. Ejecutar readiness dentro de REQUIRES_NEW/READ_COMMITTED/readOnly=true con el advisory lock.
5. Tras adquirir el lock, ReadinessService lee transaction_timestamp exactamente una vez y lo pasa
   al ReadinessCore; el core siempre recibe ese instante caller-owned y nunca consulta otro.
6. Usar sólo SELECT; acotar a cardinalidad esperada+1 cuando existe un tamaño contractual y usar
   máximo finito más total observado para proyecciones globales e historia.
7. Comprobar target SELLADO y grafo de origen exacto mediante el verifier SELECT-only.
8. Verificar igualdad global:
   - todas las versiones target VIGENTE;
   - cero slots extra/faltantes/ajenos;
   - snapshots sellados target exactos;
   - cero punteros extra/faltantes/ajenos;
   - scopes, locales y audiencias completos;
   - REGISTRO con requisito obligatorio;
   - requiredSetRevision reconstruido;
   - vigenteDesde alcanzado con transaction_timestamp;
   - cero estados target incompatibles.
9. Verificar markers APPROVED sin afirmar autenticidad profesional.
10. Devolver NOT_READY con todos los hallazgos seguros, límite 200 y fingerprint observado.
11. Demostrar que servicio y core comparten JdbcTemplate, que el core no abre gate y que una llamada
    observa sólo el instante suministrado aunque avance el reloj real.

### Precisiones incorporadas durante el Corte 2

- La matriz original distinguía ERROR por conexión o lectura, pero no tenía un código general que
  representara esa incapacidad sin mentir sobre schema, privilegios o concurrencia. Se agregó
  EDITORIAL_OBSERVATION_FAILED como decimoctavo código editorial estable, con mensaje constante y
  sin detalles de excepción.
- Las colecciones con cardinalidad contractual conocida se leen con expected+1. Las proyecciones
  globales y los históricos necesarios para el fingerprint usan máximos finitos congelados y
  count(*) over() para probar que la lectura fue completa. Un exceso devuelve ERROR sin observation
  ni fingerprint parcial. Los topes base son 8.192 versiones documentales, 16.384 versiones de
  requisito y 8.192 lotes; las cardinalidades derivadas se calculan con límites aritméticos exactos.
  El LIMIT acota lo materializado por el cliente, mientras statement_timeout sigue siendo la defensa
  ante una selección cuyo conteo resulte costoso en el servidor.
- La clausura observada por el fingerprint incluye las membresías target, todos los slots y punteros
  actuales, toda versión global no BORRADOR, los BORRADOR target/current necesarios y los lotes de
  reemplazo alcanzables de forma transitiva. Una publicación ajena íntegramente en BORRADOR continúa
  excluida; su historia deja de ser invisible apenas una versión sale de BORRADOR.
- Los counts de la observation se calculan sobre la misma clausura completa que se canonicaliza. No
  se publican conteos obtenidos de un universo distinto al fingerprint.
- READ_COMMITTED más el advisory transaction lock da una vista coherente respecto de los writers que
  respetan el protocolo compartido. No se atribuye esa garantía a escritores externos que ignoren
  deliberadamente el lock.
- LegalManifestOriginGraphVerifier concentra la comparación SELECT-only. El importador conserva su
  bloqueo de fila y legal_validar_publicacion_sellada(uuid) antes de delegar esa comparación; el
  evaluador read-only no ejecuta ninguno de los dos.
- LegalEditorialReadinessResult acepta sólo las seis causas BLOCKED y cuatro causas ERROR que un
  readiness read-only puede producir. Los códigos de plan, postcondición y commit indeterminado
  permanecen en el vocabulario editorial global, pero no pueden representarse falsamente con
  persisted=false dentro de este resultado.

### Pruebas y puerta

Incluir READY, cada causa NOT_READY, publicación ajena, slots/punteros extra, drift de V27,
cardinalidad corrupta, import replay sin cambio y una prueba estructural que rechace FOR UPDATE/SHARE
en el core read-only.

~~~bash
./mvnw -Dtest=LegalEditorialReadinessResultTest,LegalEditorialReadinessCoreTest,LegalManifestReplayVerifierTest,LegalManifestDatabaseGateTest test
./mvnw -Dit.test=LegalEditorialReadinessIT,LegalEditorialSchemaVerifierIT,LegalManifestImportIT verify
git diff --check
git status --short
~~~

Commit:

    feat(legal): evalua readiness editorial en postgresql

### Evidencia de cierre

- Java 21 (Corretto 21.0.10), puerta enfocada documentada: 37 tests, 0 fallos, 0 errores y 0
  omitidos.
- Puerta unitaria ampliada del corte: 62 tests, 0 fallos, 0 errores y 0 omitidos.
- Suite unitaria backend completa ejecutada por verify: 790 tests, 0 fallos, 0 errores y 0 omitidos.
- PostgreSQL 16 real: 30 tests de integración, 0 fallos, 0 errores y 0 omitidos:
  LegalEditorialReadinessIT (8), LegalEditorialSchemaVerifierIT (15) y LegalManifestImportIT (7).
- El readiness cubre READY y las seis categorías BLOCKED aplicables en este corte; también acredita
  publicación ausente, slot y puntero global ajenos de forma independiente, historia global
  disjunta, fecha futura, revisión corrupta y desborde fail-closed sin fingerprint parcial.
- READY, NOT_READY y ERROR preservan las 19 tablas y las 10 secuencias de la superficie editorial:
  el evaluador no ejecuta DML ni avanza identidades.
- La superficie editorial V27 quedó congelada en 19 tablas, 130 columnas, 130 constraints, 58
  triggers, 10 identity sequences y 34 funciones. V27 y el inventario/import verifier de 2.3B no se
  modificaron.
- Los reportes históricos permanecen byte-compatibles. Este corte no agrega endpoints, JPA,
  frontend, configuración de despliegue ni contenido legal real.
- `git diff --check` limpio.

## Corte 3 — Plan editorial inmutable y simulación

Estado: completado el 2026-08-28.

### Objetivo

Congelar el JSON externo REPLACE/RETIRE y derivar un plan de ejecución determinista sin escritura.

### Archivos

Crear:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/LegalEditorialPlanSchema.java;
- src/main/resources/legal/editorial/v1/editorial-plan.schema.json;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalEditorialPlanLimits.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/ConfinedEditorialPlanReader.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalEditorialPlanParser.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalEditorialPlanValidator.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/model/LegalEditorialPlanV1.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialExecutionPlan.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPlannerCore.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPlanResult.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPlanService.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/LegalEditorialPlanSchemaTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/ConfinedEditorialPlanReaderTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalEditorialPlanParserTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalEditorialPlanValidatorTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalEditorialPlanGoldenFixtureTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialExecutionPlanTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPlanResultTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPlanServiceTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPlannerCoreTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPlannerIT.java;
- src/test/resources/legal/editorial/replace-valid-v1/editorial-plan.json;
- src/test/resources/legal/editorial/replace-valid-v1/canonical.json;
- src/test/resources/legal/editorial/replace-valid-v1/sha256.txt;
- src/test/resources/legal/editorial/retire-valid-v1/editorial-plan.json;
- src/test/resources/legal/editorial/retire-valid-v1/canonical.json;
- src/test/resources/legal/editorial/retire-valid-v1/sha256.txt.

Modificar:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/StrictJsonReader.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/Rfc8785Canonicalizer.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalManifestIssueCode.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessCore.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessCoreTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestPersistenceITSupport.java.

### Implementación

1. Mantener separados LegalEditorialPlanV1, artefacto externo, y LegalEditorialExecutionPlan, delta
   DB derivado.
2. Emitir sólo un token opaco ValidatedEditorialPlan desde el validator.
3. Aplicar 1 MiB, archivo regular sin symlink, UTF-8/NFC/LF/sin BOM, JSON estricto, unknown fields y
   duplicados rechazados.
4. Exigir UUID y digests normalizados, 128 documentos, 256 requisitos, 128 lotes y motivo de hasta
   1000 caracteres.
5. Modelar source publication+SHA+expectedEditorialStateFingerprint, target, adiciones,
   reutilizaciones, lotes documentales, sucesiones de requisitos y salidas con motivo.
6. REPLACE exige target distinto y expectedReadinessAfter=READY.
7. RETIRE exige target=current, expectedReadinessAfter=NOT_READY y acknowledgement true.
8. Clasificar cada elemento source y target exactamente una vez. Ninguna ausencia crea una salida.
9. Canonicalizar con RFC 8785 y congelar SHA golden.
10. Planificar PROMOTE sin JSON externo.
11. Para plan de estado ya final, devolver APPLICABLE/changeRequired=false; para fuente exacta,
    APPLICABLE/changeRequired=true; para parcial/distinto, BLOCKED.
12. Tras adquirir el lock, PlanService lee transaction_timestamp una vez; PlannerCore usa
    ReadinessCore y verifier sobre esa sesión y ese instante caller-owned, sin gate, nueva lectura
    temporal ni row locks.
13. No habilitar todavía DML REPLACE/RETIRE.

### Precisiones incorporadas durante el Corte 3

- El artefacto v1 quedó plano, cerrado y sin `$schema`: 18 propiedades obligatorias y ocho arrays
  siempre presentes. El batch documental declara su UUID; no se deriva de contenido ni de orden.
- El SHA externo se calcula sobre el JSON estricto de entrada canonicalizado con RFC 8785. Los
  arrays son sensibles al orden para esa identidad; el token validado mantiene además una
  representación semántica ordenada y determinista para planificar.
- El reader confinado reutiliza las garantías comunes de archivo regular, tamaño, UTF-8, NFC, LF y
  ausencia de symlink, pero conserva códigos editoriales específicos para que un plan inválido no
  se reporte falsamente como un manifiesto inválido.
- `LegalEditorialExecutionPlan` separa estado fuente esperado, postestado exacto, comandos directos
  y efectos producidos por los triggers V27. Así el futuro apply no podrá duplicar transiciones o
  proyecciones que ya crea el lote documental.
- El planner evalúa primero el postestado para reconocer replay sin volver a exigir el fingerprint
  fuente. Sólo ante un postestado distinto valida la fuente exacta; cualquier mezcla parcial queda
  BLOCKED.
- El replay REPLACE acredita una única operación atómica: todas las transiciones creadas por ese
  plan, la creación y sello de sus lotes y la actualización de los punteros target deben compartir
  un único instante. Una publicación source SELLADA no es suficiente por sí sola: sus membresías no
  pueden permanecer BORRADOR o PUBLICADA y la cobertura declarada se calcula sobre las VIGENTE.
- La recuperación de huecos editoriales clasifica la fuente desde la membresía sellada y los
  estados VIGENTE, no sólo desde punteros actuales. RETIRE elimina explícitamente los punteros que
  referencian documentos o requisitos afectados, incluidos retiros exclusivamente documentales.
- `LegalEditorialReadinessCore` expone internamente una única snapshot SELECT-only reutilizable por
  readiness y planner. Una publicación ajena completamente BORRADOR no altera el fingerprint de la
  fuente.
- `LegalEditorialPlanService` abre un único gate read-only, comparte la misma `JdbcTemplate` y lee
  exactamente una vez `transaction_timestamp()`. Core, parser y validator no abren conexiones ni
  ejecutan DML.

### Pruebas y puerta

Cubrir path security, límites, schema, canonical, cambios de hash, tipo de comando, source
fingerprint, cobertura exacta, delta mixto, ningún retiro inferido y cero cambio de tablas/secuencias
en plan.

~~~bash
./mvnw -Dtest=LegalEditorialPlanSchemaTest,ConfinedEditorialPlanReaderTest,LegalEditorialPlanParserTest,LegalEditorialPlanValidatorTest,LegalEditorialPlanGoldenFixtureTest,LegalEditorialExecutionPlanTest,LegalEditorialPlanResultTest,LegalEditorialPlanServiceTest,LegalEditorialPlannerCoreTest,StrictJsonReaderTest,Rfc8785CanonicalizerTest test
./mvnw -Dit.test=LegalEditorialPlannerIT,LegalEditorialReadinessIT verify
git diff --check
git status --short
~~~

Commit:

    feat(legal): planifica acciones editoriales

### Evidencia de cierre

- Java 21 (Corretto 21.0.10), puerta focalizada del contrato, parser, modelo, planner y service:
  134 tests, 0 fallos, 0 errores y 0 omitidos.
- Suite unitaria backend completa ejecutada por verify: 856 tests, 0 fallos, 0 errores y 0
  omitidos.
- PostgreSQL 16 real con Flyway V27: 39 tests de integración, 0 fallos, 0 errores y 0 omitidos:
  LegalEditorialPlannerIT (8), LegalEditorialReadinessIT (9),
  LegalEditorialSchemaVerifierIT (15) y LegalManifestImportIT (7).
- Los golden RFC 8785 del artefacto v1 quedaron congelados en
  `534ef5a63292484c4cde6fc2fa6735f7d42dbc40a3df671a6f9550626aebe49c` para REPLACE y
  `69015b111e3fda886debf05201648578e306403c1e222f02d7e17b21be822d33` para RETIRE.
- PROMOTE, replay, REPLACE mixto, RETIRE exclusivamente documental, fingerprint incorrecto,
  historia previa, estado parcial y overflow preservan exactamente las 19 tablas y 10 secuencias
  editoriales durante plan.
- La auditoría cruzada inicial detectó y cerró dos regresiones antes del commit: replay con
  transiciones en instantes distintos y REPLACE addition-only sobre una publicación source aún
  BORRADOR. La auditoría posterior al cierre encontró tres defensas adicionales, registradas y
  cerradas a continuación.
- El core no contiene DML, row locks, función de validación sellada ni reloj propio; el service abre
  un único gate read-only y ejecuta un único `transaction_timestamp()` después del lock.
- V27, inventario/verifier/importador de 2.3B, publication-manifest.schema.json, endpoints,
  frontend y configuración de despliegue no se modificaron. `git diff --check` quedó limpio.

### Endurecimiento posterior del Corte 3

Estado: completado el 2026-08-28, antes de iniciar las mutaciones del Corte 4.

Una revisión adversarial posterior confirmó dos hallazgos P1 y uno P2 que no cambian la semántica
editorial, pero sí fortalecen la frontera previa a PostgreSQL:

- `ConfinedEditorialPlanReader` ya no reabre la ruta para consumir bytes. Recorre cada componente
  sin seguir symlinks mediante `SecureDirectoryStream` cuando el proveedor lo permite; el fallback
  portable toma snapshots de toda la ruta, exige `fileKey`, abre un único channel, relee ese mismo
  handle y compara bytes, identidad, tamaño y timestamps. Las pruebas cubren symlink en un padre y
  sustitución ABA con restauración del nombre original.
- `reason` rechaza U+0000 tanto en el schema como en el validator antes de emitir el token opaco,
  porque PostgreSQL no puede persistir ese carácter en `varchar`. El schema editorial queda fijado
  en 8.891 bytes y SHA-256
  `160af4f4b5a6e1b9eedae90dfe52fabad8a12f0cfa15b00743c67fba28a70fac`; los SHA de los planes
  golden no cambian.
- `StrictJsonDocument` conserva su perfil de origen y ubicación segura; manifest y plan editorial
  usan límites y mappers separados, y `Rfc8785Canonicalizer` rechaza cruzar ambos perfiles. Se
  preserva la API pública histórica del reader.

Evidencia: Java 21 (Corretto 21.0.10), 111 tests focalizados, 0 fallos, 0 errores y 0 omitidos;
`git diff --check` limpio. Límite explícito: Java NIO portable no expone el `fileKey` del channel ya
abierto, por lo que un actor capaz de restaurar simultáneamente bytes y todos los timestamps entre
observaciones queda fuera de las garantías portables. No quedan hallazgos P1/P2 confirmados en este
hardening.

Commit local:

    fix(legal): endurece planes editoriales

## Corte 4 — Primera promoción transaccional

Estado: completado el 2026-08-28.

### Objetivo

Aplicar PROMOTE de forma atómica e idempotente, todavía sin exponer el comando en el jar.

### Archivos

Crear:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalInitialPromotionCore.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyService.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyResult.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyReceipt.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalTransactionCompletionState.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyResultTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalInitialPromotionCoreTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyServiceTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalTransactionCompletionStateTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalInitialPromotionIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalInitialPromotionFailureIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialTransactionBoundaryTest.java.

Modificar:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestDatabaseGate.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalImportTransactionState.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestImportService.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalImportTransactionStateTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalImportTransactionBoundaryTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestImportServiceTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestPersistenceITSupport.java.

### Implementación

1. Mantener LegalImportTransactionState como wrapper compatible del completion core neutral.
2. Abrir un único gate mutante REQUIRES_NEW/READ_COMMITTED/readOnly=false.
3. Resolver bajo lock, en orden:
   - postestado PROMOTE exacto, incluidas dos transiciones por versión y cero lotes:
     ALREADY_APPLIED;
   - fuente inicial exacta: continuar;
   - cualquier parcial o historia previa: BLOCKED.
4. Exigir cero transiciones editoriales globales, lotes sellados, slots y punteros.
5. Leer transaction_timestamp una vez y entregarlo a PlannerCore y ReadinessCore.
6. Bloquear publicaciones, líneas y versiones por UUID.
7. Insertar BORRADOR→PUBLICADA para documentos y requisitos.
8. Insertar PUBLICADA→VIGENTE.
9. Insertar slots exactos y punteros hacia snapshots sellados existentes.
10. Forzar constraints y ejecutar ReadinessCore en la misma JdbcTemplate con el instante ya leído.
11. Confirmar sólo con READY; NOT_READY revierte y produce POSTCONDITION_NOT_READY.
12. El receipt relee UUID, timestamp y cantidades desde DB; no contiene contenido.
13. Retorno normal/STATUS_COMMITTED: persisted=true.
14. Rollback autoritativo: persisted=false.
15. Completion incierta: persisted=null/UNKNOWN sin receipt tentativo; la reconciliación automática
    se agrega en Corte 9.
16. Acreditar replay con cero DML y cero avance de secuencias.

### Pruebas y puerta

Cubrir fresco, replay, target distinto, estados parciales, historia previa sin proyecciones, fecha
futura, transaction timestamp cruzando la fecha, constraint tardía, rollback y mismo core
readiness/planner sin gate anidado.

~~~bash
./mvnw -Dtest=LegalEditorialApplyResultTest,LegalInitialPromotionCoreTest,LegalEditorialApplyServiceTest,LegalTransactionCompletionStateTest,LegalEditorialTransactionBoundaryTest,LegalManifestImportServiceTest,LegalManifestDatabaseGateTest test
./mvnw -Dit.test=LegalInitialPromotionIT,LegalInitialPromotionFailureIT,LegalManifestImportIT verify
git diff --check
git status --short
~~~

Commit:

    feat(legal): promociona primera publicacion

### Precisiones incorporadas durante el Corte 4

- `LegalTransactionCompletionState<R>` concentra la semántica neutral de finalización; el estado
  histórico de importación queda como wrapper compatible. Un commit confirmado habilita el receipt,
  un rollback autoritativo informa `persisted=false` y una confirmación ambigua devuelve
  `UNKNOWN`/`persisted=null` sin exponer metadata tentativa.
- El resultado de apply distingue de forma tipada `APPLIED`, `ALREADY_APPLIED`, `BLOCKED`, `ERROR`
  y `UNKNOWN`. El receipt confirmado contiene operación, target, instante PostgreSQL, readiness y
  siete cantidades, nunca contenido legal.
- El gate mutante valida primero que el resultado del commit sea observable y abre una sola frontera
  `REQUIRES_NEW`, `READ_COMMITTED`, `readOnly=false`. Dentro de ella se obtiene un único
  `transaction_timestamp()` compartido por planner, mutación y readiness.
- Antes de mutar se bloquean en orden UUID el target y todas las publicaciones introductorias
  alcanzadas por las versiones reutilizadas, además de líneas y versiones. Esto respeta los locks
  que toman los triggers de V27 también cuando documento o requisito reutilizan membresía.
- Las transiciones se insertan en dos fases globales (`BORRADOR→PUBLICADA` y luego
  `PUBLICADA→VIGENTE`), se crean slots y punteros exactos, se fuerzan los constraints y el mismo
  `ReadinessCore` debe observar `READY` antes de aceptar el commit.
- El replay exacto se confirma por lectura de membresía y no ejecuta DML ni avanza sequences. Un
  target diferente, historia previa, estado parcial, fecha futura o postcondición no READY queda
  bloqueado o revierte según corresponda.
- No fue necesario modificar el servicio histórico de importación ni su API pública: la
  compatibilidad quedó cubierta por el wrapper neutral y su suite existente. Tampoco se agregó CLI,
  endpoint, migración, cambio de V27, despliegue ni modificación frontend en este corte.

### Evidencia de cierre

- Java 21 (Corretto 21.0.10), puerta unitaria focalizada: 81 tests, 0 fallos, 0 errores y 0
  omitidos.
- Suite unitaria backend completa: 897 tests, 0 fallos, 0 errores y 0 omitidos.
- PostgreSQL 16 real con las 27 migraciones: 18 integraciones seleccionadas, 0 fallos, 0 errores y
  0 omitidos (`LegalInitialPromotionIT`: 7, `LegalInitialPromotionFailureIT`: 4,
  `LegalManifestImportIT`: 7).
- Se acreditaron aplicación fresca, reutilización de membresía en documentos y requisitos, replay
  sin escrituras ni avance de sequences, exclusión entre targets, rechazo de parciales/historia y
  fecha futura, rollback por postcondición/constraint, timeout de advisory lock y pérdida de ACK del
  commit con resultado UNKNOWN y retry exacto.
- `git diff --check` limpio y revisión adversarial independiente sin hallazgos P1/P2 pendientes.

## Corte 5 — CLI inicial, contexto y rol editorial

Estado: completado el 2026-08-28.

### Objetivo

Exponer readiness, plan-promote y apply-promote en el jar aislado con reporte v3, credencial
editorial y verificadores exactos.

### Archivos

Crear:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialArguments.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialEnvironment.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialConfirmation.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialReport.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialReportWriter.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialCliExecutionState.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialDatabaseConfiguration.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPrivilegeVerifier.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialArgumentsTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialEnvironmentTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialConfirmationTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialReportTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialReportWriterTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialCliExecutionStateTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialPreflightTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialCliTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalCliProcessSupportTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalManifestEditorLauncherTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPrivilegeVerifierTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPrivilegeVerifierIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialDatabaseIsolationIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalRestrictedEditorialRoleFixture.java;
- scripts/legal-manifest-editor.sh.

Modificar:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalManifestCli.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/LegalManifestIssueCode.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalDatabaseBoundaryMarker.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyService.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPlanService.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReadinessService.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalManifestDatabaseGate.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalV27EditorialInventory.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/MvgrReparacionesBackendApplicationTests.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalCliProcessSupport.java;
- tests v1/v2 de CLI, reports y aislamiento.

### Implementación

1. Despachar los comandos editoriales crudos antes del fallback v1/v2 y usar un boundary v3
   separado.
2. No ampliar el record LegalManifestArguments, cuyo contrato está ligado a import.
3. Ejecutar el preflight congelado: argumentos → ValidatedRelease → ValidatedEditorialPlan cuando
   corresponda → confirmaciones de publicación/manifiesto → tipo/operationId/SHA del plan.
4. Ante cada mismatch, demostrar que no se resolvió el entorno, no nació Spring y no se pidió una
   conexión.
5. Sólo después abrir un contexto Spring WebApplicationType.NONE con JDBC, servicios editoriales,
   schema y privilege verifier.
6. No activar web, JPA, Flyway, DataLoader, schedulers, Mercado Pago ni logging host.
7. Aceptar sólo ORDENFIX_LEGAL_EDITOR_DB_* y rechazar system properties datasource.
8. Exigir ORDENFIX_LEGAL_EDITOR_ENABLED=true sólo para apply-promote.
9. Mantener los presupuestos 75/30/5 y DataSourceTransactionManager con
   rollbackOnCommitFailure=false.
10. Congelar el inventario editorial de 19 tablas y 10 sequences en el schema verifier creado en
   Corte 2.
11. El rol recibe SELECT en las 19 tablas y flyway_schema_history.
12. INSERT sólo en transiciones, slots, punteros, lote y dos tablas miembro.
13. DELETE sólo en slots y punteros.
14. USAGE sólo en las cuatro sequences editoriales.
15. UPDATE sólo:
    - id técnico en legal_publicaciones, líneas y versiones;
    - estado/estado_cambiado_en/ultimo_motivo/reemplazo_lote_id documental;
    - estado/estado_cambiado_en/ultimo_motivo de requisitos;
    - estado_construccion/sellado_en del lote.
16. Exigir positivamente el call graph que necesita UPDATE(id) de legal_publicaciones y que los
    guards V27 rechacen cada UPDATE directo y no-op.
17. EXECUTE sólo sobre el call graph de transiciones, slots, punteros y reemplazos.
18. Rechazar ownership, memberships, grants de tabla amplios, DDL, TEMP, large objects, parámetros,
    aceptación, idempotencia HTTP y snapshots sellados.
19. Documentar en comentarios y tests que el DML estructural residual hace a esta cuenta offline,
    temporal y ajena a la app.
20. Emitir v3 exacto y preservar bytes v1/v2.
21. Crear launcher POSIX executable que limpia JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS y _JAVA_OPTIONS.

### Pruebas y puerta

~~~bash
./mvnw -Dtest=LegalEditorialArgumentsTest,LegalEditorialEnvironmentTest,LegalEditorialConfirmationTest,LegalEditorialReportTest,LegalEditorialReportWriterTest,LegalEditorialCliExecutionStateTest,LegalEditorialPreflightTest,LegalEditorialCliTest,LegalCliProcessSupportTest,LegalManifestEditorLauncherTest,LegalManifestCliTest,LegalManifestReportTest,LegalManifestImportReportTest,LegalEditorialPrivilegeVerifierTest,MvgrReparacionesBackendApplicationTests test
./mvnw -Dit.test=LegalEditorialSchemaVerifierIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialDatabaseIsolationIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
git diff --check
git status --short
~~~

Commit:

    feat(legal): expone promocion editorial aislada

### Implementación cerrada

- El `legal-cli.jar` despacha `readiness`, `plan-promote` y `apply-promote` por una frontera v3
  anterior e independiente de los contratos v1/v2. Los tres comandos exigen exactamente la ruta
  del manifiesto y las dos confirmaciones; ningún mismatch llega a credenciales, Spring o JDBC.
- El contexto editorial nace únicamente con JDBC y un datasource. Readiness/plan usan un gate
  `readOnly=true`; apply usa otro mutable. Ambos son `REQUIRES_NEW/READ_COMMITTED`, conservan los
  presupuestos 75/30/5, comparten el mismo `DataSourceTransactionManager` con
  `rollbackOnCommitFailure=false` y ejecutan, en ese orden, schema y privilege verifier exactos.
- La configuración sólo acepta `ORDENFIX_LEGAL_EDITOR_DB_URL`, `USERNAME`, `PASSWORD` y el driver
  opcional. Rechaza propiedades JVM `spring.datasource.*`; `ORDENFIX_LEGAL_EDITOR_ENABLED=true` se
  exige únicamente para apply. El entorno aislado no carga web, JPA, Flyway, runners, schedulers,
  DataLoader, Mercado Pago ni configuración/logging host.
- El reporte v3 conserva el orden superior congelado y traduce únicamente matrices tipadas. Un
  apply invocado sin resultado terminal emite `persisted=null/UNKNOWN` sin UUID, timestamp,
  readiness ni postestado tentativos. Un receipt confirmado sobrevive a errores posteriores de
  cierre o serialización; un fallo de stdout —incluido `PrintStream.checkError()` adversarial— sólo
  devuelve exit 3 y no fabrica otro envelope. La evidencia de invocación es monotónica e
  independiente de la fase de serialización, por lo que un apply bloqueado antes del servicio nunca
  se degrada falsamente a `UNKNOWN`.
- El rol editorial efectivo queda cerrado sobre SELECT de las 19 tablas V27 y el historial Flyway,
  7 tablas con INSERT, 2 con DELETE, las columnas UPDATE técnicas/estatales exactas, 4 sequences y
  las 23 funciones del call graph. El verificador rechaza ownership —también de bases sin
  conexiones habilitadas—, memberships, grant options, DDL, TEMP, large objects, parámetros y
  cualquier capacidad sobre aceptación o tablas host. Además exige los valores efectivos
  `session_replication_role=origin` y `lo_compat_privileges=off`, y niega las cinco funciones PG16
  capaces de crear large objects.
- La cuenta mantiene DML estructural residual porque las funciones V27 son `SECURITY INVOKER`.
  Por eso el fixture y el protocolo la tratan como credencial offline, temporal, de un solo job y
  nunca como usuario de la app. El fixture destructivo se niega a operar fuera de una base efímera
  dedicada `ordenfix_legal_editorial_*`.
- `scripts/legal-manifest-editor.sh` ejecuta por defecto el jar legal empaquetado, permite overrides
  operativos explícitos y elimina `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` antes de
  iniciar la JVM. El soporte de procesos también elimina controles editoriales heredados.
- La aplicación web normal no activa el contexto ni los tres servicios editoriales aunque reciba
  hostilmente el flag interno de la herramienta.
- No se modificaron V27, el rol importador, endpoints, UI, JPA, contenido legal, deploy ni frontend.

### Evidencia de cierre

- Java 21 (Corretto 21.0.10), puerta unitaria focalizada: 170 tests, 0 fallos, 0 errores y 0
  omitidos.
- Suite unitaria backend completa: 1038 tests, 0 fallos, 0 errores y 0 omitidos.
- PostgreSQL 16/H2 y jar empaquetado, integraciones seleccionadas: 34 tests, 0 fallos, 0 errores y
  0 omitidos
  (`LegalEditorialSchemaVerifierIT`: 15, `LegalEditorialPrivilegeVerifierIT`: 7,
  `LegalEditorialDatabaseIsolationIT`: 2, `LegalManifestCliIsolationIT`: 3 y
  `LegalManifestCliProcessIT`: 7). La última incluye dispatch v3 crudo y regresión v1.
- El rol restringido acreditó promoción fresca, replay exacto sin avance de sequences, guards de
  UPDATE técnico y rechazo de expansiones de rol, base, schema, tablas, columnas, sequences,
  funciones, large objects y parámetros. También probó que un GUC de replicación inseguro bloquea
  apply sin DML ni avance de sequences, que ownership de una base deshabilitada no queda oculto y
  que no se puede crear metadata de large objects.
- `sh -n` del launcher y `git diff --check` limpios; revisión adversarial independiente sin
  hallazgos P1/P2 pendientes.

## Corte 6 — Cutover uno a uno, adiciones y reutilización

Estado: completado el 2026-08-30 mediante los Subcortes 6A, 6B, 6C, 6D y 6E.

Diseño específico aprobado:

- docs/plans/2026-08-29-legal-replace-cutover-design.md.

Plan detallado de ejecución:

- docs/plans/2026-08-29-legal-replace-cutover-implementation.md.

### Objetivo

Aplicar REPLACE completo con documentos uno a uno, adiciones, reutilizaciones y delta explícito de
requisitos.

### Archivos

Crear según la separación final del executor:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialMutationWriter.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialPostStateVerifier.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalDocumentReplacementWriter.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyServiceReplaceTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReplaceIT.java.

Modificar:

- LegalEditorialPlannerCore, LegalEditorialApplyService, LegalEditorialExecutionPlan y sus tests;
- parser/report/CLI v3 para plan-replace y apply-replace;
- LegalManifestPersistenceITSupport.

### Implementación

Orden SQL obligatorio:

1. validar el alcance 0..1/1→1 antes del gate; adquirir el gate y el advisory lock compartido;
2. leer `transaction_timestamp` antes de invocar al planner;
3. comparar primero el postestado exacto para replay; si falta, validar target `SELLADO`, source
   fingerprint, historia y grafo completos;
4. en el writer releer y bloquear publicación, líneas y versiones en orden UUID, releer
   slots/punteros y revalidar estado fuente bajo los mismos locks;
5. publicar las versiones nuevas documentales y de requisito (`BORRADOR→PUBLICADA`);
6. eliminar los punteros actuales que serán reemplazados;
7. liberar todos los slots directos antes de ejecutar cualquier insert directo;
8. activar adiciones documentales sin predecesor e insertar sus slots;
9. para uno a uno, insertar lote `ABIERTO`, anterior y sucesora, y sellarlo; el trigger V27 es el
   único dueño de transiciones y slots derivados del reemplazo;
10. retirar documentos salientes sin sucesor;
11. aplicar transiciones de requisitos nuevos, reemplazados y retirados; los reutilizados quedan
    `VIGENTE`;
12. insertar los slots directos restantes para rebinds hacia la publicación target;
13. insertar punteros target hacia snapshots sellados existentes, sin mutar los snapshots ni sus
    miembros;
14. acreditar el postestado exacto con el verificador SELECT-only;
15. ejecutar `SET CONSTRAINTS ALL IMMEDIATE`;
16. recalcular readiness en la misma sesión y confirmar sólo `READY`, sin DML posterior.

Además:

- cada elemento operativo source/target debe estar clasificado explícitamente; un miembro source ya
  terminal queda fuera del delta, pero conserva estado e historia exactos dentro del postestado;
- un target puede recuperar un hueco dejado por RETIRE mediante una adición directa;
- plan-replace nunca escribe;
- apply-replace exige el flag operativo, dos rutas y cuatro confirmaciones exactas;
- replay compara lote/estados/slots/punteros y no depende del operationId;
- ALREADY/BLOCKED previo a DML no avanza las cuatro sequences editoriales.

### Pruebas y puerta

Cubrir uno a uno, adición, recuperación de gap, reuse con rebind, documentos/requisitos mixtos,
audiencias completas, retiro explícito dentro del cutover, source distinto, target NOT_READY,
replay, rollback tardío y regresión import.

~~~bash
./mvnw -Dtest=LegalEditorialPlannerCoreTest,LegalEditorialReplaceScopeGuardTest,LegalEditorialPostStateVerifierTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialArgumentsTest,LegalEditorialPlanConfirmationTest,LegalEditorialCliExecutionStateTest,LegalEditorialCliTest,LegalEditorialReportTest,LegalEditorialApplyServiceTest,LegalInitialPromotionCoreTest test
./mvnw -Dit.test=LegalEditorialReplaceIT,LegalInitialPromotionIT,LegalInitialPromotionFailureIT,LegalEditorialReadinessIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialDatabaseIsolationIT,LegalManifestImportIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
./mvnw test
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

Commits locales del corte:

1. `f0875a7` — `fix(legal): delimita cutover editorial uno a uno` (6A);
2. `99e1504` — `feat(legal): expone plan de cutover editorial` (6B);
3. `ca28171` — `refactor(legal): separa mutacion y postestado editorial` (6C);
4. `3570490` — `feat(legal): aplica cutover editorial uno a uno` (6D);
5. `fix(legal): acredita cutover editorial uno a uno` (6E, commit de cierre).

### Evidencia de cierre

- `plan-replace` y `apply-replace` quedaron expuestos con contrato v3, guard previo a JDBC,
  transacción común, writer DML separado, verificador SELECT-only, replay exacto y completion-state
  que distingue commit confirmado, no persistido y `UNKNOWN`.
- La recuperación de un gap real reveló y cerró una omisión del planner: miembros source ya
  `RETIRADA`/`REEMPLAZADA` se transportan con su cadena histórica exacta, sin generar DML ni ampliar
  el alcance uno a uno. Historia corrupta o lote histórico ajeno bloquean fail-closed.
- Java 21 (Corretto 21.0.10): puerta focal de 1.628 tests y suite completa de 2.615 tests, todas
  verdes. La matriz PostgreSQL 16/Flyway V27 ejecutó 62 integraciones sin fallos: 13 REPLACE, 11
  PROMOTE/rollback, nueve readiness, siete privilegios, dos de aislamiento, siete de import, tres
  de aislamiento CLI y diez de proceso CLI.
- Las integraciones REPLACE acreditan rol restringido real, mezcla documental/de requisitos,
  target `NOT_READY→READY`, replay sin DML ni avance de secuencias, bloqueos sin healing, cinco
  rollbacks tardíos y commit incierto sin falso éxito. Snapshots sellados y las superficies de
  aceptación/idempotencia permanecen inmutables.
- `LegalManifestCliProcessIT` cubre jar empaquetado, dispatch, aislamiento y regresiones; no se
  presenta como E2E exitoso completo de `apply-replace`. Concurrencia multithread, capacidad y la
  matriz completa de procesos siguen en Corte 10.
- Revisiones adversariales finales sin P0–P2, launcher y diff limpios, frontend sin cambios
  versionados y ausencia de push/deploy. Corte 6 no cierra la Fase 2.3C ni habilita por sí solo la
  producción pública.

## Corte 7 — Reemplazos split y merge

Estado: completado el 2026-08-30; Subcortes 7A a 7E acreditados.

Diseño específico aprobado:

- docs/plans/2026-08-30-legal-split-merge-design.md.

Plan detallado de ejecución:

- docs/plans/2026-08-30-legal-split-merge-implementation.md.

### Objetivo

Extender el executor REPLACE a lotes 1→N, N→1 y múltiples lotes disjuntos.

### Archivos

Crear:

- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialSplitMergeIT.java.

No crear inicialmente `LegalReplacementBatchExecutor`: el writer actual ya separa apertura,
membresías y sellado. Reconsiderarlo sólo si una prueba demuestra una frontera necesaria.

Modificar:

- LegalDocumentReplacementWriter;
- LegalEditorialPlannerCore;
- LegalEditorialReplaceIT;
- fixtures REPLACE.

### Implementación

1. Exigir anteriores y sucesoras no vacíos.
2. Admitir `1→1`, `1→N`, `N→1` y múltiples lotes disjuntos; rechazar `N→M` cuando ambos lados
   superan uno.
3. Verificar mismo tipo y locale.
4. Verificar contextos predecesores y sucesores exactamente iguales como conjunto.
5. Rechazar overlap, autociclo, miembro duplicado o reutilizado por dos lotes.
6. Insertar todos los miembros antes de sellar.
7. Ordenar el delta y los sellos por mínimo UUID de sus miembros y miembros por UUID, sin cambiar
   el orden histórico/fingerprint por `batchId`.
8. Dejar que legal_reemplazo_lote_before_update vuelva a bloquear UUIDs y que after_update sea dueño
   de transiciones/slots.
9. Tratar UUID de lote como caller-generated no autoritativo para operationId; el replay compara
   estructura y postestado.
10. No ampliar report, rol ni schema.

### Pruebas y puerta

~~~bash
./mvnw -Dtest=LegalEditorialPlanValidatorTest,LegalEditorialReplaceScopeGuardTest,LegalEditorialExecutionPlanTest,LegalEditorialPlannerCoreTest,LegalDocumentReplacementWriterTest,LegalEditorialPostStateVerifierTest,LegalEditorialApplyServiceReplaceTest,LegalEditorialPlanServiceTest,LegalEditorialCliTest,LegalEditorialReportTest test
./mvnw -Dit.test=LegalEditorialSplitMergeIT,LegalEditorialReplaceIT,LegalInitialPromotionIT,LegalInitialPromotionFailureIT,LegalEditorialReadinessIT,LegalEditorialPrivilegeVerifierIT,LegalEditorialDatabaseIsolationIT,LegalManifestImportIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT,LegalConcurrencyIT verify
./mvnw test
sh -n scripts/legal-manifest-editor.sh
git diff --check
git status --short
~~~

### Evidencia de cierre

- `1→N`, `N→1` y múltiples lotes disjuntos convergen a `READY` con el rol restringido;
  `N→M` continúa bloqueado. Replay exacto y con otra identidad de plan no ejecuta DML; corrupción
  extra/faltante no se repara y el fallo entre sellos revierte las 19 tablas editoriales.
- Java 21 (Corretto 21.0.10): 134 unitarios focales y 2.629 unitarios completos, todos sin fallos,
  errores ni omitidos. La matriz seleccionada ejecutó 82 integraciones sobre PostgreSQL 16.14 y
  Flyway V27: 13 split/merge, 13 REPLACE, 7 PROMOTE, 4 fallos PROMOTE, 9 readiness, 7 privilegios,
  2 de aislamiento DB, 7 de import, 3 de aislamiento CLI, 10 de procesos CLI y 7 de concurrencia
  V27. El control de secretos del JAR también pasó.
- La suite unitaria completa se repitió de forma independiente; el launcher pasó `sh -n` y el diff
  quedó limpio. Las revisiones adversariales de alcance, pruebas y documentación cerraron sin
  hallazgos P0–P2 pendientes.
- 7E fue exclusivamente documental. En todo Corte 7 no se modificaron V27/V28, schemas externos,
  grants, rol, endpoints, JPA, frontend ni contenido legal. Backend quedó en
  `codex/lanzamiento-publico-backend`; frontend siguió en `codex/frontend-refactor-checkpoint` sin
  cambios versionados. No hubo deploy ni push.
- Corte 7 queda completo, pero no cierra la Fase 2.3C ni habilita producción pública. RETIRE,
  reconciliación de `UNKNOWN`, concurrencia multithread/capacidad/procesos editoriales exhaustivos y
  cierre cross-repo permanecen en Cortes 8 a 11.

Commits atómicos del Corte 7, gobernados por el plan detallado enlazado arriba:

    feat(legal): prepara lotes split y merge
    feat(legal): habilita reemplazos split y merge
    test(legal): acredita split y merge en postgresql
    test(legal): acredita atomicidad multibatch
    docs(legal): cierra reemplazos split y merge

## Corte 8 — Retiro explícito fail-closed

Estado: completado el 2026-08-30; Subcortes 8A a 8G acreditados.

Diseño específico aprobado:

- `docs/plans/2026-08-30-legal-retire-fail-closed-design.md`;
- commit local `5d2c358` y precisión de evidencia de replay `9991857`.

Plan detallado de ejecución:

- `docs/plans/2026-08-30-legal-retire-fail-closed-implementation.md`;
- commit local de planificación `e0a8fb2`.

### Objetivo

Aplicar RETIRE sin sucesor y confirmar de forma deliberada APPLIED+NOT_READY.

El trabajo se divide en siete subcortes acreditables:

1. 8A — invariantes del execution plan — completado el 2026-08-30;
2. 8B — postestado parcial exacto — completado el 2026-08-30;
3. 8C — writer dedicado y aplicación — completado el 2026-08-30;
4. 8D — PostgreSQL fresco con rol restringido — completado el 2026-08-30;
5. 8E — replay, corrupción y rollback — completado el 2026-08-30;
6. 8F — CLI interna y reporte v3 — completado el 2026-08-30;
7. 8G — regresiones y cierre documental — completado el 2026-08-30.

El plan enlazado congela archivos, pasos, puertas y commits. RETIRE no agrega ledger, migración,
grant, endpoint o frontend; `persisted=true` acredita postestado confirmado y no un receipt
persistido.

8A permite transportar proyecciones preservadas y exige conjuntos de dependencias canonicalizados
en cada puntero RETIRE declarado. 8B completa el universo desde toda la membresía current y todos
los scopes sellados: preserva exactamente estados, historias, lotes, slots, punteros, dependencias
y timestamps sobrevivientes; admite huecos históricos sin recrearlos; y rechaza replay con
transiciones append-only posteriores. La lectura PostgreSQL aplica límites antes de canonicalizar
y el planner permanece select-only. SOURCE exige prehistoria estrictamente anterior a la
observación y POST rechaza un cutover futuro antes de construir el plan. 8C agrega el writer
dedicado, revalida causalidad y grafo antes del DML, congela proyecciones con un lock de tabla
compatible con el rol mínimo y coordina `verifier → constraints → NOT_READY` sin receipt
tentativo. 8D acredita retiros frescos documentales, de requisitos y mixtos con el rol restringido.
8E demuestra replay select-only, corrupción sin healing, rollback fila por fila de las 19 tablas
—con los huecos `+1/+1` documentados de secuencias PostgreSQL no transaccionales— y retry
convergente tras commit incierto. 8F cierra los siete comandos internos, el preflight, el reporte
v3 y el éxito `RETIRE+NOT_READY`. 8G ejecuta la matriz final corregida y alinea la documentación
sin modificar producción.

### Evidencia de cierre del Corte 8

- Puerta focal 3.267/3.267; lifecycle limpio 4.193 unitarias y 108 integraciones; repetición
  independiente 4.193/4.193.
- Amazon Corretto 21.0.10, PostgreSQL 16.14 y Flyway 11.14.1 sobre V27; ambos JAR no contienen
  entradas `application-secret.properties` y el launcher pasó `sh -n`.
- RETIRE preserva estado no afectado, termina `APPLIED+NOT_READY`, acredita replay por postestado,
  no repara corrupción y revierte fallos tardíos. PROMOTE, REPLACE, split/merge, readiness,
  privilegios, import y reportes v1/v2 permanecen verdes.
- El rango completo del Corte 8 no modifica `src/main/resources`, V27/V28, schemas, grants,
  inventarios, API, controllers, JPA, frontend, runbooks o deploy. La única superficie operativa
  nueva es `plan-retire`/`apply-retire` dentro del JAR legal interno.
- Corte 8 completo no cierra la Fase 2.3C ni habilita producción pública. Reconciliación ampliada,
  concurrencia/capacidad/procesos exhaustivos y cierre cross-repo continúan en Cortes 9 a 11.

Commits locales atómicos de los subcortes 8A–8G:

    2777430 fix(legal): valida plan exacto de retiro
    ad0e03f fix(legal): preserva postestado parcial de retiro
    1d45e40 feat(legal): aplica retiro editorial fail closed
    91113e4 test(legal): acredita retiro editorial en postgresql
    2adf754 fix(legal): acredita replay y rollback de retiro
    10f9f13 feat(legal): expone retiro editorial interno
    docs(legal): cierra retiro editorial fail closed

## Corte 9 — Reconciliación y UNKNOWN

Estado: en ejecución; diseño y plan específico registrados, Subcorte 9A completado el 2026-08-30,
9B, 9C y 9D1 completados el 2026-08-31; 9D2, 9E y 9F pendientes.

Diseño específico aprobado:

- `docs/plans/2026-08-30-legal-editorial-commit-reconciliation-design.md`;
- commit local `878a0cf`.

Plan detallado de ejecución:

- `docs/plans/2026-08-30-legal-editorial-commit-reconciliation-implementation.md`.

El plan detallado gobierna el inventario exacto de archivos, el orden TDD, las puertas y los siete
commits 9A, 9B, 9C, 9D1, 9D2, 9E y 9F. Ante cualquier diferencia, prevalece sobre el bosquejo
prospectivo de este documento.

9A acredita el facade transaccional editorial marker-only, con receipt tentativo redactado,
32 pruebas focales y 4.205 unitarias completas verdes. 9B agrega el reconciliador SELECT-only y la
frontera read-only exacta: nueva transacción con el mismo advisory lock, timestamp fresco, planner
y verifier en la misma sesión, clasificación cerrada `POST_EXACT`/`SOURCE_EXACT`/`UNKNOWN` y cero
writers/DML. Su puerta focal cerró 91/91; el lifecycle limpio cerró 4.225 unitarias y 2 pruebas de
aislamiento del contexto sobre H2 en modo PostgreSQL. La evidencia PostgreSQL real permanece en
9D. 9C integra el facade y el reconciliador al apply sólo para `UNKNOWN` con plan exacto: la puerta
focal cerró 50/50 y el lifecycle fresco cerró 4.231 unitarias más 37 integraciones sobre PostgreSQL
16.14. Los ACK perdidos positivos de PROMOTE, REPLACE y RETIRE ya convergen a `ALREADY_APPLIED` en
la primera invocación y el replay conserva filas y secuencias. La matriz PostgreSQL adversarial
de 9D1 acredita además receipt y conteos reconstruidos, rollback sin ACK y session kill
concluyente como `SOURCE_EXACT`, fallback cerrado, otra transacción read-only con el mismo lock y
retry manual único. Su lifecycle fresco cerró 4.231 unitarias y 42 integraciones sobre PostgreSQL
16.14; las 19 tablas editoriales se compararon por snapshots JSONB exactos. La incertidumbre
restante continúa en 9D2; import, V27/V28, API, frontend y contratos públicos permanecen sin
cambios.

### Objetivo

Conservar la frontera epistémica de 2.3B y reconciliar commits ambiguos sin ledger editorial.

### Archivos

Crear:

- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialTransactionState.java;
- src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialCommitReconciler.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialTransactionStateTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialCommitReconcilerTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyServiceReconciliationTest.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialApplyFailureIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialReconciliationIT.java.

Modificar:

- LegalEditorialApplyService;
- LegalManifestDatabaseGate y LegalEditorialDatabaseConfiguration;
- constructores directos, tests de frontera e IT afectados por el wiring;
- expectativas PostgreSQL anteriores de pérdida de ACK;
- LegalEditorialApplyResult, post-state verifier, CLI/report/writer o completion core sólo si una
  prueba del plan detallado lo exige, sin cambiar receipt, JSON v3 ni import v2.

### Implementación

1. Exigir DataSourceTransactionManager exacto, rollbackOnCommitFailure=false, REQUIRES_NEW y
   READ_COMMITTED.
2. No inferir persisted sólo por excepción Java, SQLState o posición aparente antes del commit.
3. Tras completion incierta, dejar terminar/desvincular la sesión anterior.
4. Abrir una conexión/transacción nueva y adquirir el mismo advisory lock.
5. Si el postestado completo existe: ALREADY_APPLIED con receipt releído.
6. Sólo si el fingerprint fuente completo continúa exacto después de reacquire: ERROR/false.
7. Estado parcial, lock no adquirido o DB inaccesible: UNKNOWN/null.
8. No reejecutar DML dentro del reconciler.
9. No usar operationId, SHA externo o receipt tentativo como prueba DB.
10. Un retry del operador repite bundle, plan y confirmaciones idénticos.
11. stdout inexistente/truncado no fabrica envelope UNKNOWN; exit 3 y reconciliación externa.
12. UNKNOWN conserva identidad, `operationId`, SHA, readiness esperado y `counts.release` validados
    del input; `publicationUuid`, `appliedAt`, readiness autoritativo, `counts.state`,
    `counts.delta` y receipt DB son null.

### Pruebas, puertas y commits

Cubrir todas las completion states, pérdida de ACK, rollback sin ACK, session kill, DB inaccesible,
source exacto, postestado exacto, parcial, nueva conexión/mismo lock, retry sin duplicados y regresión
import v2.

Las puertas focales y la matriz final fresca están congeladas en el plan detallado. Corte 9 se
registra en estos commits locales:

    feat(legal): modela estado transaccional editorial
    feat(legal): clasifica evidencia de commits ambiguos
    fix(legal): reconcilia apply editorial ambiguo
    test(legal): acredita commits editoriales reconciliados
    test(legal): preserva incertidumbre editorial
    test(legal): conserva contratos al reconciliar commits
    docs(legal): cierra reconciliacion editorial

## Corte 10 — Concurrencia, capacidad y procesos reales

Estado: pendiente.

### Objetivo

Acreditar el ciclo completo sobre PostgreSQL 16 y el jar empaquetado con el rol restringido.

### Archivos

Crear:

- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialConcurrencyIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialFailureIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/LegalEditorialCapacityIT.java;
- src/test/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/cli/LegalEditorialProcessIT.java.

Modificar/reutilizar:

- LegalCliProcessSupport;
- LegalManifestCliIsolationIT;
- LegalManifestCliProcessIT;
- LegalManifestPersistenceITSupport;
- LegalRestrictedEditorialRoleFixture;
- scripts/legal-manifest-editor.sh;
- este plan, sólo para registrar evidencia y métricas reales.

### Escenarios

- dos PROMOTE idénticos: APPLIED + ALREADY_APPLIED;
- targets incompatibles: como máximo uno confirma;
- reemplazos con predecesores superpuestos: como máximo uno confirma;
- import, dry-run, readiness, plan y apply usan el mismo advisory lock;
- timeout, deadlock y session kill sin falso éxito;
- pérdida del acuse: reconciliación o UNKNOWN;
- retry exacto convergente;
- 128 documentos, 256 requisitos y 16 scopes;
- reuse, adición, uno a uno, split y merge;
- latencia artificial de 5 ms por ejecución JDBC lógica;
- cada operación debajo de 70 s, cada statement debajo de 30 s y presupuesto total 75 s;
- fijar antes del commit los caps observados de llamadas y cada lectura multirrow a expected+1;
- jar real con READY/NOT_READY, APPLICABLE/BLOCKED, APPLIED/ALREADY_APPLIED y
  APPLIED+NOT_READY;
- JSON único, stderr sanitizado, canaries, system properties hostiles;
- ausencia de web, Flyway, JPA y schedulers;
- rol editorial restringido e importador todavía incapaz de promover;
- Start-Class y contenido de ambos jars;
- launcher POSIX real y limpieza de opciones JVM.

### Puerta

~~~bash
./mvnw -Dit.test=LegalEditorialConcurrencyIT,LegalEditorialFailureIT,LegalEditorialCapacityIT verify
./mvnw -Dit.test=LegalEditorialProcessIT,LegalManifestCliIsolationIT,LegalManifestCliProcessIT verify
./mvnw verify
git diff --check
git status --short
~~~

Si la capacidad falla, optimizar batching/lecturas antes de cambiar timeouts. Registrar PostgreSQL,
JDK, conteos, duración, JDBC calls, mayor statement, memoria reproducible e incidentes encontrados.

Commit:

    test(legal): acredita concurrencia y procesos editoriales

## Corte 11 — Runbook, cierre y coordinación cross-repo

Estado: pendiente.

### Objetivo

Dejar operación reproducible y cerrar 2.3C sin habilitar integración frontend.

### Frontend primero

Modificar exclusivamente:

- ../mvgr-reparaciones-frontend/docs/legal/README.md;
- ../mvgr-reparaciones-frontend/docs/plans/2026-08-23-lanzamiento-publico-confianza-cuenta-plan.md.

No tocar:

- src/**;
- docs/FRONTEND_INTEGRATION.md;
- docs/openapi.json;
- scripts de readiness remoto;
- queries, mutations, botones o flags.

Registrar:

- 2.3C acredita operación editorial interna;
- la secuencia correcta es revisión → digest/validate/dry-run → import/sello →
  plan APPLICABLE → apply → readiness READY;
- V28, APIs, aceptación, seguridad, deploy y staging siguen pendientes;
- Tarea 3 no cambia;
- BACKEND-HANDOFF 1 continúa cerrado.

Puerta frontend:

~~~bash
npm run test:release
npm test
npm run build
git diff --check
git status --short
~~~

No ejecutar build:public con borradores. Commit frontend:

    docs(plan): registra cierre de fase 2.3C

### Backend después

Crear:

- docs/runbooks/legal-manifest-editorial-postgresql.md;
- docs/plans/2026-08-27-legal-manifest-promotion-closure.md.

Modificar:

- docs/plans/2026-08-27-legal-manifest-promotion-design.md;
- este plan;
- docs/runbooks/legal-manifest-import-postgresql.md;
- FRONTEND_INTEGRATION.md;
- README.md sólo si su resumen quedó desactualizado.

El runbook debe congelar:

- preflight de jar, Java, V27, schema, rol, red, secreto y ventana;
- perfil SQL idempotente del rol editorial y revocación/rotación;
- siete comandos y sus argumentos exactos;
- confirmaciones publication/manifest/operation/plan;
- flujo import/sello → plan → apply → readiness;
- matrices v3 y exit codes;
- RETIRE APPLIED+NOT_READY/exit 0;
- captura separada de stdout/stderr;
- receipts y custodia externa de job/operador;
- stdout ausente, UNKNOWN y retry exacto;
- prohibición de usar contenido real sin revisión profesional;
- diferencia entre readiness editorial y público.

La closure debe registrar:

- hashes de Cortes 1–10 y del commit frontend;
- matriz final;
- inventario de 19 tablas/10 sequences y grants efectivos;
- versiones PostgreSQL/JDK y conteos de tests;
- métricas de capacidad/concurrencia/procesos;
- jars, launcher y redacción;
- schema v1 cross-repo intacto;
- ausencia de V28, API, deploy, promoción real y push;
- riesgos y dependencias posteriores.

Paridad:

~~~bash
cmp src/main/resources/legal/manifest/v1/publication-manifest.schema.json ../mvgr-reparaciones-frontend/docs/legal/publication-manifest.schema.json
wc -c src/main/resources/legal/manifest/v1/publication-manifest.schema.json ../mvgr-reparaciones-frontend/docs/legal/publication-manifest.schema.json
shasum -a 256 src/main/resources/legal/manifest/v1/publication-manifest.schema.json ../mvgr-reparaciones-frontend/docs/legal/publication-manifest.schema.json
~~~

Puerta backend final:

~~~bash
./mvnw verify
git diff --check
git status --short
~~~

Commit backend:

    docs(legal): cierra fase 2.3C

## Matriz de commits prevista

| Corte | Commit |
|---:|---|
| diseño | docs(legal): diseña promocion y readiness editorial |
| plan | docs(legal): planifica promocion y readiness editorial |
| 1 | feat(legal): define readiness editorial |
| 2 | feat(legal): evalua readiness editorial en postgresql |
| 3 | feat(legal): planifica acciones editoriales |
| 4 | feat(legal): promociona primera publicacion |
| 5 | feat(legal): expone promocion editorial aislada |
| 6A | fix(legal): delimita cutover editorial uno a uno |
| 6B | feat(legal): expone plan de cutover editorial |
| 6C | refactor(legal): separa mutacion y postestado editorial |
| 6D | feat(legal): aplica cutover editorial uno a uno |
| 6E | fix(legal): acredita cutover editorial uno a uno |
| 7A | feat(legal): prepara lotes split y merge |
| 7B | feat(legal): habilita reemplazos split y merge |
| 7C | test(legal): acredita split y merge en postgresql |
| 7D | test(legal): acredita atomicidad multibatch |
| 7E | docs(legal): cierra reemplazos split y merge |
| 8A | fix(legal): valida plan exacto de retiro |
| 8B | fix(legal): preserva postestado parcial de retiro |
| 8C | feat(legal): aplica retiro editorial fail closed |
| 8D | test(legal): acredita retiro editorial en postgresql |
| 8E | fix(legal): acredita replay y rollback de retiro |
| 8F | feat(legal): expone retiro editorial interno |
| 8G | docs(legal): cierra retiro editorial fail closed |
| 9A | feat(legal): modela estado transaccional editorial |
| 9B | feat(legal): clasifica evidencia de commits ambiguos |
| 9C | fix(legal): reconcilia apply editorial ambiguo |
| 9D1 | test(legal): acredita commits editoriales reconciliados |
| 9D2 | test(legal): preserva incertidumbre editorial |
| 9E | test(legal): conserva contratos al reconciliar commits |
| 9F | docs(legal): cierra reconciliacion editorial |
| 10 | test(legal): acredita concurrencia y procesos editoriales |
| 11 frontend | docs(plan): registra cierre de fase 2.3C |
| 11 backend | docs(legal): cierra fase 2.3C |

## Puerta de salida

2.3C cierra sólo si:

- los siete comandos cumplen sus matrices;
- READY/NOT_READY y APPLIED/ALREADY/BLOCKED/ERROR/UNKNOWN están acreditados;
- primera promoción, cutover, split/merge y retiro funcionan sobre V27;
- replay exacto no duplica transiciones;
- source y poststate distinguen rollback, conflicto y UNKNOWN;
- el rol editorial mínimo funciona y todo privilegio extra bloquea;
- el importador continúa incapaz de promover;
- reportes v1/v2 permanecen byte-compatible;
- concurrencia y capacidad cumplen presupuesto;
- procesos reales acreditan jar y launcher;
- frontend sigue sin integración funcional;
- schema v1 permanece idéntico;
- no se modificó V27;
- no se promovió contenido real;
- no hubo deploy ni push;
- BACKEND-HANDOFF 1 permanece cerrado.

## Después de 2.3C

1. V28 para revisión agregada multicontexto.
2. Catálogo, documentos, ETag y endpoints autenticados.
3. Aceptación y registro atómicos.
4. Seguridad, CORS, 409, 428 y enforcement.
5. Contenido profesional aprobado y deploy en staging.
6. BACKEND-HANDOFF 1.
7. Tarea 3 frontend.
