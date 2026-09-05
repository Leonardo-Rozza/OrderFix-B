# Corte 14 — Plan de requisitos legales públicos de registro

Fecha: 2026-09-05

Estado: diseño aprobado por el titular el 2026-09-05; 14A–14E completados; gate integral aprobado.
[Diseño aprobado](2026-09-05-legal-public-requirements-read-design.md).
Baseline backend `40a31a2`, rama `codex/lanzamiento-publico-backend`.

## Reglas y evidencia de partida

- Cortes pequeños, decisión y evidencia documentadas, un commit atómico local por corte, sin push.
- Backend inicialmente limpio. Frontend `7545201`, rama `codex/frontend-refactor-checkpoint`;
  preservar `.agents/` y `public/OrdenFix project naming/`, ambos no versionados.
- V27/V28 congeladas; sin migraciones nuevas, cambios frontend, grants compartidos, contenido legal real ni
  habilitación de producción. BACKEND-HANDOFF 1 y Tarea 3 frontend permanecen cerrados.
- Baseline 13D: 4649 Surefire + 370 Failsafe = 5019 pruebas sin fallos, errores u omisiones.
  Ese resultado no verifica ninguna funcionalidad propuesta aquí.
- Focalizados por corte; `clean verify` nuevo en 14E. Si un cambio transversal o un fallo revela
  un alcance mayor, ampliar la verificación y documentar el motivo antes de seguir.
- Los nombres nominales siguientes delimitan el trabajo. Antes de editar se confirma la whitelist
  del corte contra el árbol real; una adición necesaria se documenta con su motivo, sin usar `git add .`.

Prefijos para las whitelists:

```text
core = src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/core/
db   = src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/manifest/persistence/
http = src/main/java/com/leonardorozza/mvgrreparacionesbackend/legal/http/
sec  = src/main/java/com/leonardorozza/mvgrreparacionesbackend/config/security/
```

Tests bajo el mismo paquete de `src/test/java`. Los prefijos sólo abrevian la lista documental;
no autorizan editar todo un paquete. En todos los cortes se actualizan este plan y el diseño
exclusivamente para registrar decisiones, autorización, avance y evidencia reales.

## 14A — Proyección completa y acreditación pura

Baseline 14A: `3351728`, árbol limpio. El titular aprobó el diseño y autorizó comenzar 14A
el 2026-09-05. La whitelist nominal de este apartado queda confirmada antes de implementar.

Resultado: representación inmutable de requisitos públicos de REGISTRO con revisión componente
y agregada calculadas a partir de los mismos campos. Sin JDBC, HTTP, Spring, flags o configuración.

Pasos:

1. Añadir `LegalPublicRegistrationRequirements` y `LegalPublicRequirementsValidator` como API
   tipada. El validador recibe una `LegalRequiredSetProjection`, conserva su orden y acredita
   exclusivamente REGISTRO/es-AR, contenido, estructura y capacidad. El resultado no prueba estado
   de base, pertenencia, commit o procedencia; tampoco acepta un token arbitrario como acreditado.
2. Exigir al menos un requisito obligatorio, validar también todos los opcionales, ausencia de
   duplicados de requisito y documento dentro de un requisito, entre 1 y 16 documentos por miembro,
   contextos/locales consistentes y metadatos representables. Las líneas, estados y ordinales SQL
   quedan para 14C, porque la proyección pura no los contiene.
3. Validar afirmación publicable con `LegalVisibleText` y afirmación/Markdown con
   `CanonicalTextValidator`, sin normalizar. Congelar los límites
   del diseño, incluyendo Markdown expandido por referencia repetida, Unicode y fechas UTC válidas.
   La comparación temporal contra el reloj post-lock corresponde a 14C.
4. Reutilizar calculadores existentes para SCOPE_V1 y AGGREGATE_V1 de un scope
   REGISTRO/ADMIN_TITULAR. Conservar ambos valores internos; el futuro wire expondrá sólo el agregado.
   No tocar canonicalizadores, writers, proyecciones o calculadores congelados para adaptar datos.
5. Crear golden con un obligatorio, un opcional y documentos compartidos, Unicode, escapes y
   microsegundos. Calcular valores esperados independientemente del código bajo prueba.
6. Probar cambios del último miembro/documento, orden preservado, falta de obligatorio, duplicados,
   ausencia de documentos, afirmación vacía/invisible aun con digest válido, límites
   exactos/excedidos, digest distinto, metadata inválida e
   inmutabilidad. Cubrir validez de documentos compartidos entre requisitos distintos.

Whitelist nominal:

- `core/LegalPublicRegistrationRequirements.java` (nuevo).
- `core/LegalPublicRequirementsValidator.java` (nuevo).
- `LegalPublicRequirementsValidatorTest.java` en tests core.
- Fixtures nominales `projection.json`, `scope-canonical.json`, `scope-sha256.txt`,
  `aggregate-canonical.json`, `aggregate-sha256.txt` bajo
  `src/test/resources/legal/manifest/public-registration-requirements-v1/`.
- Este plan y el diseño, sin cambios al contrato HTTP ya congelado.

Pruebas focalizadas: `LegalPublicRequirementsValidatorTest`, `CanonicalTextValidatorTest`,
`LegalRequiredSetRevisionCalculatorTest`, `LegalRequiredSetAggregateRevisionCalculatorTest` y
`Rfc8785CanonicalizerTest`. Si el nombre de una regresión difiere se usa el existente verificado.

Commit: `feat(legal): acredita requisitos publicos`.

### Decisiones de implementación 14A

- La API nueva es `LegalPublicRequirementsValidator.validate(LegalRequiredSetProjection)`.
  Devuelve un `LegalPublicRegistrationRequirements` final e inmutable, con `projection()`,
  `scopeRevision()` y `requiredSetRevision()`. No admite un token del caller ni ofrece constructor
  público; el constructor interno calcula ambas revisiones desde la proyección validada.
- Una entrada inválida falla con `IllegalArgumentException` y mensaje fijo sin contenido; una
  proyección null falla con `NullPointerException`. No hay resultado parcial ni catálogo de errores
  HTTP en este núcleo. La futura frontera 14C traducirá el fallo de acreditación a indisponibilidad.
- Se reutiliza `requireCanonicalizationCapacity` antes de asignar bytes UTF-8 o calcular digests.
  Cuenta todas las referencias para 1 MiB/documento y 16 MiB expandido, sin tocar sus límites.
- Los metadatos y fechas usan las validaciones existentes de `LegalDocumentSummary`, además de
  los límites de SCOPE_V1. Se preservan espacios y Unicode original de título/versión, sin NFC,
  trim o reglas de visibilidad nuevas. La afirmación sí debe ser publicable; Markdown vacío se
  rechaza explícitamente aunque tenga el digest del texto vacío.
- Un documento compartido se valida una vez, con hasta 128 UUID distintos. Toda referencia de
  la misma UUID debe conservar los campos canónicos; fechas se comparan por Instant, permitiendo
  offsets distintos del mismo instante. Se mantienen las referencias y el orden original.
- Los límites ya impuestos por los constructores congelados no se duplican como una segunda
  implementación. Se prueba que la API acepta los extremos válidos y se mantienen sus regresiones.
  La API pura no acredita slots VIGENTE, ordinales de base, actualidad ni límites de una publicación
  completa fuera del scope; esas responsabilidades siguen asignadas a 14C.

### Evidencia de 14A

Única ejecución focalizada con Corretto 21.0.10 (Amazon), finalizada el 2026-09-05 a las
14:29:27 -03: **153 pruebas, 0 fallos, 0 errores, 0 omitidas**, `BUILD SUCCESS` en 15.371 s.
Los cinco reportes XML confirman 81 pruebas nuevas de `LegalPublicRequirementsValidatorTest` y
72 regresiones: 14 de texto canónico, 8 de SCOPE_V1, 9 de AGGREGATE_V1 y 41 del canonicalizador.
No se ejecutó PostgreSQL ni `clean verify`: sólo se agregan tipos puros y sus tests; no hubo
fallos ni cambios transversales. El gate integral queda pendiente en 14E.

Los cinco fixtures son sintéticos y se generaron con Python fuera de la implementación Java.
El test los contrasta además contra `org.erdtman.jcs.JsonCanonicalizer` y SHA-256 independiente,
y compara bytes/tokens de los calculadores productivos. Los JSON canónicos no incluyen LF final;
el JSON legible y los archivos de digest sí lo incluyen. No se modifica ningún golden previo.

| Proyección | Bytes canónicos | Revisión |
| --- | --- | --- |
| SCOPE_V1 completo REGISTRO | 3177 | `sha256:82deb156c352d00664d09daabc790244681e55f0d2fb8ab2454076c577ac2541` |
| AGGREGATE_V1, ADMIN_TITULAR, un scope | 209 | `sha256:5ec6c40c9a87d7157a798cdc6f1bbba400c753a6cc7830dc3149c5d805f8b1e1` |

Se acreditan opcionales inválidos, último miembro influyendo en ambas revisiones, preservación de
orden/bytes originales, Unicode, controles, digests falsos, fechas UTC válidas y fuera de rango,
UUID sin restricción v4, identidades independientes de requisito/documento e inmutabilidad.
Las pruebas aceptan 256 requisitos, 16 referencias por requisito y 128 documentos distintos;
rechazan el UUID 129, 1 MiB UTF-8 + 1 byte y 16 MiB expandido + 1 byte. El caso expandido usa un
documento de 1 MiB compartido por 16 requisitos, probando el conteo por referencia. Las combinaciones
extremas acreditan la proyección pura, no slots/documentos VIGENTE de una publicación PostgreSQL.

Dos revisiones independientes cubrieron producción y tests. Se preservaron los calculadores,
canonicalizadores, roles y migraciones existentes; hashes de V27/V28 coinciden con el diseño.
El frontend conserva `7545201` y sus directorios no versionados. Whitelist final: dos clases,
un archivo de tests, cinco fixtures, plan y diseño. Sin HTTP, configuración o push.
Próximo corte: 14B, contexto restringido y recursos PostgreSQL acotados.

## 14B — Contexto, privilegios y recursos acotados

Baseline 14B: `efe8484`, árbol limpio. El titular autorizó continuar el 2026-09-05.
Se confirma la whitelist de este apartado antes de editar. Se añade nominalmente
`LegalPublicRequirementsDatabaseContextIT.java` para acreditar el store dentro del contexto
productivo nuevo con PostgreSQL 16, commit/reutilización y rollback, sin mezclar esas pruebas con
la matriz de permisos ni esperar al servicio de 14C. Reutiliza fixtures e instrumentación existentes.

Resultado: composición explícita independiente con gate mutable acreditado y store V28 existente;
todavía sin servicio de consulta pública ni mapping HTTP.

Pasos:

1. Crear `PUBLIC_REQUIREMENTS`, guard exclusivo de contexto y registro no escaneable. Rechazar
   mezcla con AGGREGATE, PUBLIC_DOCUMENT_READ u otro contexto legal.
2. Incorporar pool, JDBC, manager y template propios REQUIRES_NEW/READ_COMMITTED mutable; ningún
   fallback web/JPA/Flyway. Propiedades/URL, dos conexiones y presupuestos conforme al diseño.
3. Implementar wrapper/deadline propios tomando como referencia 13, sin modificar su lector.
   Preservar límite global por operación, reducción del remanente por I/O, sellado de unwrap,
   cancelación/abort, limpieza tras timeout y protección contra abortar una conexión ya devuelta.
4. Reutilizar el schema verifier V28 y agregar un privilege verifier con las 17 tablas SELECT,
   dos INSERT, una columna UPDATE y siete EXECUTE del diseño. Auditar permisos efectivos y
   capacidades indirectas; no ampliar el inventario ni verifier del materializador.
5. Añadir acreditación exacta de esta frontera mutable al gate. Mantener métodos, preflights,
   modos de lock y presupuestos de los consumidores existentes.
6. Configurar el store/replay con exactamente el mismo JdbcTemplate. La fixture PostgreSQL sólo
   podrá aprovisionar bases efímeras `ordenfix_legal_public_requirements_*`.
7. Acreditar esquema, ACL mínimas, denegaciones, PUBLIC/membresías, pérdida de privilegio, drift,
   transacción efectiva, presupuesto y cierre. Probar que un rol documental/materializador/owner
   no puede sustituir la credencial propuesta.

Whitelist nominal:

- `db/LegalPublicRequirementsDatabaseConfiguration.java` (nuevo).
- `db/LegalPublicRequirementsPrivilegeVerifier.java` (nuevo, allowlist propia).
- `db/LegalPublicRequirementsDataSource.java`, `LegalPublicRequirementsDeadline.java` y
  `LegalPublicRequirementsReadException.java` (nuevos).
- `db/LegalDatabaseBoundaryMarker.java`, `LegalManifestDatabaseGate.java` (extensiones aditivas).
- Tests nuevos: `LegalPublicRequirementsDatabaseConfigurationTest.java`,
  `LegalPublicRequirementsDataSourceTest.java`, `LegalPublicRequirementsDeadlineTest.java`,
  `LegalPublicRequirementsPrivilegeVerifierIT.java`, `LegalRestrictedPublicRequirementsRoleFixture.java`.
- Integración del contexto/store: `LegalPublicRequirementsDatabaseContextIT.java` (nuevo).
- Regresiones actualizadas: `LegalDatabaseBoundaryMarkerTest.java`, `LegalManifestDatabaseGateTest.java`.

Gate focal: tests nuevos, boundary/gate, contexto agregado y documental existentes, privilegios
PostgreSQL 16 y rollback del store con el rol nuevo. Registrar cualquier efecto transversal real
sobre gate/marker y ampliar verificación si corresponde. El futuro endpoint permanece ausente.

Commit: `feat(legal): aisla consulta de requisitos publicos`.

### Decisiones de implementación 14B

- Se incorpora una configuración explícita, sin `@Configuration` ni componente escaneable, bajo
  el flag interno `ordenfix.legal.public-requirements-context.enabled`. Sólo consume credencial
  `ordenfix.legal.public-requirements.*`; no activa ni implementa el flag HTTP futuro.
  El guard exige exclusivamente un marcador `PUBLIC_REQUIREMENTS`. El pool y el wrapper se
  cierran tanto al cerrar el contexto como al fallar su inicialización.
- Pool de dos conexiones, minIdle 0, borrow/validación 1 s, driver connect/login 1 s, socket 5 s y
  cancelSignal 1 s. Sólo se admiten las opciones TLS y `loggerLevel` ya previstas en la URL.
  El template mutable usa el manager JDBC exacto, REQUIRES_NEW/READ_COMMITTED, timeout 15 s y
  `rollbackOnCommitFailure=false`; presupuestos 15/5/1/1 s. El gate acredita datasource no nulo,
  identidad JDBC y el par ordenado de preflights de esquema V28/privilegios propios.
- La única extensión de producción compartida es un valor nuevo del marcador y un método nuevo
  de acreditación del gate. Los métodos, verificadores, wrappers y presupuestos de los contextos
  anteriores permanecen intactos. Se reutilizan store, replay, resolver y calculadores V28 dentro
  de esta composición; todavía no existe una fachada de lectura ni hidratación pública.
- El verifier nuevo conserva los controles de identidad, membresías, permisos efectivos, PUBLIC,
  grant options, columnas, funciones y capacidades sistémicas de V28, con allowlist propia:
  17 tablas SELECT, dos INSERT, UPDATE sólo de `conjunto_id` en el puntero y siete EXECUTE.
  El schema verifier V28 acredita además las definiciones y SECURITY INVOKER de las funciones.
  Estos permisos no restringen filas/perfiles; fijar REGISTRO/ADMIN_TITULAR y validar visibilidad
  vigente sigue siendo responsabilidad del consumidor de 14C.
- Deadline y wrapper propios conservan el presupuesto global al anidar operaciones y reducirlo
  entre sentencias/FETCH. Sellan unwrap y referencias a delegados, permiten rollback/limpieza
  tras expirar y cancelan el watchdog antes de devolver una conexión al pool. Un reloj inyectable
  package-private permite probar las fases sin sleeps; no introduce una propiedad productiva.
- El chequeo previo a COMMIT puede fallar como RuntimeException y permite rollback. Una
  RuntimeException del COMMIT delegado se convierte en SQLException, igual que un fallo JDBC de
  commit: Spring informa UNKNOWN y no intenta rollback automático. Tras un COMMIT exitoso no se
  lanza un error de deadline dentro del proxy: Spring primero informa COMMITTED y limpia recursos;
  `withinDeadline` comprueba entonces el plazo/interrupción y puede rechazar la entrega. Así un
  commit confirmado fuera de plazo no se presenta como un rollback conocido.
- La fixture sólo admite bases efímeras con prefijo `ordenfix_legal_public_requirements_` y un rol
  nuevo. Las revocaciones PUBLIC pertenecen exclusivamente a ese contenedor de pruebas; no hay
  provisioning compartido, migraciones ni cambio de credenciales productivas.

### Evidencia de 14B

Gate focalizado: **355 pruebas, 0 fallos, 0 errores, 0 omitidas**. Reportes XML de las doce clases
Surefire y siete Failsafe contrastados con ambas ejecuciones; no se sumaron reportes históricos
de otros cortes que permanecen en `target`.

| Ejecución | Pruebas | Finalización 2026-09-05 (-03) | Tiempo Maven |
| --- | ---: | --- | --- |
| Surefire focal | 230 | 15:11:49 | 16.667 s |
| Preparación `-DskipTests package` | No ejecuta pruebas | 15:13:47 | 2.195 s |
| Failsafe focal PostgreSQL | 125 | 15:15:55 | 1 min 42 s |

Surefire: contexto nuevo 53, datasource nuevo 19, deadline nuevo 9, gate 41, marker 3;
regresiones del contexto agregado 4, contexto documental 38, datasource documental 13,
deadline documental 9, store 12, replay 10 y servicio agregado 19.
Failsafe: privilegios nuevos 53 y contexto/store nuevo 7; regresiones de privilegios V28 6,
servicio agregado 13, aislamiento agregado 2, privilegios documentales 28 y servicio documental 16.

Entorno observado: Java Corretto 21.0.10 (Amazon), Maven 3.9.11, Spring Boot 4.0.6,
Spring 7.0.7, PostgreSQL **16.14** (`postgres:16-alpine`), JDBC 42.7.10,
Testcontainers 2.0.5 y Surefire/Failsafe 3.5.5. Las tres ejecuciones terminaron `BUILD SUCCESS`.

Los siete casos del contexto real acreditan la credencial dedicada y la transacción efectiva,
COMMITTED visible desde otra conexión, CREATED con una cabecera/scope y repetición secuencial
REUSED con cero DML. Un fallo del consumidor posterior al store revierte ambas filas nuevas;
sobre REUSED conserva el agregado confirmado sin reescritura. REQUIRES_NEW usa otra conexión,
restaura la transacción externa SERIALIZABLE y su commit sobrevive al rollback externo.
Deriva de privilegios y checksum Flyway rechazan antes del lock/DML; lock editorial exclusivo
provoca SQLSTATE `55P03`. Tras restaurar cada condición hay recuperación y recursos liberados.
Estas pruebas aún no acreditan hidratación pública ni corrupción de contenido, previstas en 14C.

La matriz PostgreSQL contrasta las allowlists nominales contra capacidades efectivas, revoca cada
SELECT/EXECUTE requerido y comprueba pérdida de INSERT/UPDATE exactos. Rechaza privilegios extras,
grants por columna, grant options, PUBLIC, membresías, suplantación de sesión y capacidades
sistémicas. `FOR SHARE` real funciona sobre el puntero; UPDATE real y no-op sobre una fila existente
fallan `23514`, preservando el guard congelado. Lectura personal/evidencia, DML extra, secuencias,
DDL y locks de sesión están denegados.

Los unitarios del wrapper usan un TransactionTemplate real con conexiones simuladas para
acreditar notificaciones ROLLED_BACK, UNKNOWN y COMMITTED en sus respectivas fases; no se presentan
como pruebas de un fallo remoto durante commit. La inyección PostgreSQL de esas fases pertenece
a 14C. También cubren remanente entre FETCH/sentencias, deadline anidado, interrupción, referencias
JDBC selladas, cierre tras fallo y watchdog tardío sin abortar una conexión devuelta.

Comandos ejecutados desde backend con Java 21 explícito:

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dtest=LegalPublicRequirementsDatabaseConfigurationTest,LegalPublicRequirementsDataSourceTest,LegalPublicRequirementsDeadlineTest,LegalManifestDatabaseGateTest,LegalDatabaseBoundaryMarkerTest,LegalRequiredSetAggregateDatabaseConfigurationTest,LegalPublicDocumentReadDatabaseConfigurationTest,LegalPublicDocumentDataSourceTest,LegalPublicDocumentDeadlineTest,LegalRequiredSetAggregateStoreTest,LegalRequiredSetAggregateReplayVerifierTest,LegalRequiredSetAggregateServiceTest test
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -DskipTests package
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dit.test=LegalPublicRequirementsPrivilegeVerifierIT,LegalPublicRequirementsDatabaseContextIT,LegalV28AggregatePrivilegeVerifierIT,LegalRequiredSetAggregateServiceIT,LegalRequiredSetAggregateDatabaseIsolationIT,LegalPublicDocumentPrivilegeVerifierIT,LegalPublicDocumentReadServiceIT failsafe:integration-test failsafe:verify
```

No hubo fallos ni cambios al comportamiento de los consumidores anteriores. La extensión aditiva
de gate/marker se verificó junto con sus contextos y regresiones PostgreSQL; no justifica ampliar
esta ejecución al `clean verify`, reservado para 14E. Revisiones independientes de contexto/gate,
wrapper/commit, ACL/fixtures y decisiones documentales no encontraron defectos materiales.

Whitelist final: siete archivos de producción (cinco nuevos y dos extensiones), ocho de tests
(seis nuevos, incluida la fixture, y dos existentes), este plan y el diseño: **17 archivos**.
V27/V28 conservan los SHA-256 del diseño. Frontend `7545201` y sus dos directorios no versionados
se preservan. No hay HTTP nuevo, habilitación de producción, migraciones ni push.
Próximo corte: 14C, hidratación y servicio PostgreSQL en esta misma transacción.

## 14C — Hidratación y servicio PostgreSQL

Baseline 14C: `208397d`, árbol limpio. El titular autorizó continuar el 2026-09-05.
Whitelist nominal confirmada antes de editar; no se amplía la superficie HTTP ni el rol de 14B.

Ampliación motivada durante revisión: `LegalPublicRequirementsDataSource.java` y
`LegalPublicRequirementsDeadline.java`, sólo para conservar un error de cierre que Spring puede
absorber y rechazar la entrega después de un commit confirmado. Se verifica mediante CommitIT
con cierre real seguido de SQLException/RuntimeException, conservando COMMITTED y recuperación.
No cambia el wrapper documental, el protocolo de commit ni las migraciones congeladas.

Resultado: fachada estrecha que devuelve requisitos completos sólo tras commit confirmado.
No recibe audiencia, perfil ni actor del caller público; usa el resolver servidor para REGISTRO.

Pasos:

1. Añadir lector de conjuntos por la identidad/procedencia exacta del receipt. SQL acotado con
   sentinelas, LEFT JOIN y comparación de membresía en ambos sentidos contra la publicación.
   Acreditar reutilización válida de versiones introducidas en otra publicación.
2. Verificar publicación sellada, requisito vigente, audiencia y locale/contexto, membresía,
   ordinales y puntero documental vigente. No filtrar filas inválidas ni fabricar datos ausentes.
3. Cargar metadata/enlaces primero y texto una vez por UUID en batches acotados. Verificar tamaño
   antes de transferir TEXT, tanto afirmación como Markdown, digests íntegros y límite expandido
   con aritmética comprobada.
   No hacer una consulta/documento HTTP por requisito ni recorrer historia ajena al scope.
4. Componer una única operación: deadline → gate shared → store → lector → validador 14A →
   comparación de revisiones/identidad → commit → cierre/chequeo final → resultado.
   No invocar el servicio REQUIRES_NEW materializador ni el lector documental con otra conexión.
5. Enlazar la fachada al contexto 14B y acreditar igualdad exacta de JDBC/preflights/budgets.
   No exponer store, receipt interno, datasource o proveedores JDBC al contexto web.
6. Capturar fallos como excepción segura de indisponibilidad con causa interna; sin retries ni
   conversión de un commit UNKNOWN en éxito/rollback conocido. Resultado tras commit solamente.
7. PostgreSQL real: crear, repetir REUSED sin DML, publicación con opcionales, corrupción de
   afirmación/Markdown/revisión/membresía, puntero ausente y fechas futuras. Cubrir fallo después
   de INSERT y sobre REUSED, verificando desde otra conexión que no hay filas parciales.
8. Inyectar fallo antes de commit, durante commit y tras commit confirmado. Distinguir rollback,
   resultado desconocido y agregado confirmado recuperable; nunca exigir borrado de un commit
   real. La repetición posterior revalida y reutiliza cuando corresponde.

Whitelist nominal:

- `db/LegalPublicRequirementsReader.java`, `LegalPublicRequirementsReadService.java` (nuevos).
- `db/LegalPublicRequirementsDatabaseConfiguration.java` (composición final del servicio).
- Tests nuevos: `LegalPublicRequirementsReadITSupport.java`,
  `LegalPublicRequirementsReadServiceIT.java`, `LegalPublicRequirementsCommitIT.java`.
- `LegalPublicRequirementsDatabaseConfigurationTest.java` (superficie final de beans).
- `LegalJdbcMetricsSupport.java` sólo si una medición necesaria requiere extensión documentada.

Gate focal: servicio/commit PostgreSQL, núcleo 14A, contexto 14B, store/replay/servicio V28 existentes.
Revisar queries y bytes transferidos, cancelación y ausencia de DML en repetición secuencial.
No presentar el `ON CONFLICT DO NOTHING` de una carrera como REUSED sin intento de DML.

Commit: `feat(legal): consulta requisitos con agregado v28`.

### Decisiones de implementación 14C

- La única API de la fachada es `readRegistration()`, sin argumentos: el resolver fija
  REGISTRATION/es-AR/ADMIN_TITULAR dentro del deadline. El constructor acredita la misma frontera
  JDBC, store, reader y el par exacto de preflights. Toda la lectura sucede dentro del gate mutable
  compartido de 14B; no hay llamada a otra fachada ni transacción secundaria de materialización.
- El lector contrasta el receipt con cabecera y sidecar V28, conjunto V27 y puntero actual,
  incluyendo IDs físicos, publicación, perfil, audiencia, locale, ordinal, revisiones y fecha de
  creación del agregado. Comprueba publicación SELLADO y fechas de publicación/conjunto/puntero
  no posteriores a la frontera post-lock. No exige que las versiones se introdujeran en esa
  publicación: sí exige su membresía y que el slot documental vigente apunte a ella, como V27.
- Membresía bilateral por UUID, línea y ordinal: lee los miembros exactos del conjunto y los
  requisitos aplicables de esa publicación. LEFT JOIN conserva filas huérfanas para rechazarlas;
  la selección por audiencia no elimina versiones/líneas desconocidas. Mantiene los huecos
  legítimos del orden de requisitos y exige documentos consecutivos desde 1 en cada requisito.
- Antes de transferir afirmación/Markdown se acreditan enlaces, estados, fechas, metadatos
  documentales y límites estructurales/de bytes. Canonicidad y digests se verifican después sobre
  los bytes originales.
  Dos conteos limitados a 129 documentos y 257 requisitos comprueban capacidad de la publicación
  completa sin hidratar otros scopes. El grafo exige hasta 256 requisitos, 16 referencias por
  requisito y 128 UUID documentales; suma los bytes Markdown por referencia con `Math.addExact`
  hasta 16 MiB. Afirmaciones: hasta 1000 codepoints/4000 bytes UTF-8; Markdown: hasta 1 MiB/UUID.
- Cada query del lector tiene sentinela máximo + 1 y cursor forward-only de 32 filas. Afirmaciones
  y documentos se cargan en lotes de 32 UUID, con CASE que devuelve bytea sólo dentro del límite;
  el número de bytes se vuelve a contrastar con la metadata previa. Cada documento se carga una
  sola vez y se comparte entre referencias. Son como máximo 20 SELECT emitidos por el lector para
  los máximos estructurales; excluye gate, preflights, store y SET LOCAL del wrapper. No es una
  medición de viajes de red ni implica que toda combinación extrema sea editorialmente válida.
- Se verifica texto canónico y digest de los bytes originales, se acredita la proyección completa
  con 14A y se exige igualdad de SCOPE_V1 persistido y AGGREGATE_V1 del receipt. Opcionales inválidos
  hacen fallar todo el conjunto. No se normaliza, filtra o rellena contenido para obtener éxito.
- Un fallo operativo se traduce a `LegalPublicRequirementsReadException` con mensaje fijo y causa
  interna cuando existe; no hay retries. El resultado sólo sale tras commit, liberación y chequeo
  externo del deadline. La fase de fallo distingue rollback previo, commit indeterminado y commit
  confirmado cuya entrega falla; una consulta posterior vuelve a acreditar todo el contenido.
- Se conserva el primer error SQLException/RuntimeException de `Connection.close` en el deadline
  de esa operación; errores adicionales quedan suprimidos. El wrapper vuelve a lanzar la causa y,
  aunque Spring la absorba durante cleanup, el chequeo externo impide entregar éxito. El estado se
  comparte al anidar operaciones y no se hereda en una solicitud nueva. Esto no promete liberar
  físicamente una conexión si el driver falla antes de hacerlo; las pruebas simulan pérdida de
  confirmación del cierre después de cerrar el delegado real y acreditan rechazo/recuperación.

### Evidencia de 14C

Gate consolidado: **383 pruebas aprobadas, 0 fallos, 0 errores, 0 omitidas en las últimas
ejecuciones de cada clase**: 248 Surefire + 135 Failsafe. Se contrastaron los ocho XML unitarios
y siete de integración; los 135 de PostgreSQL combinan las seis clases aprobadas de la primera
ejecución con el reporte final de ServiceIT, sin sumar dos veces sus invocaciones.

| Ejecución | Resultado | Finalización 2026-09-05 (-03) | Tiempo Maven |
| --- | --- | --- | --- |
| Compilación focal inicial | Error de tipos en test, sin ejecutar pruebas | 16:04:55 | 13.773 s |
| Surefire focal corregido | 248 aprobadas | 16:09:45 | 37.479 s |
| Preparación `-DskipTests package` | BUILD SUCCESS, sin pruebas | 16:10:04 | 3.126 s |
| Failsafe focal inicial | 108 aprobadas, 27 fallos de fixture | 16:13:00 | 2 min 43 s |
| Recompilación del fixture | BUILD SUCCESS, sin pruebas | 16:15:04 | 11.957 s |
| Failsafe ServiceIT corregido | 34 aprobadas | 16:15:38 | 29.642 s |

La primera compilación detectó una inferencia genérica demasiado amplia en una lista del nuevo
test de configuración; se corrigió el tipo explícito del fixture sin cambiar producción ni
comportamiento compartido.
La primera ejecución PostgreSQL completó 135 invocaciones: 108 aprobadas y 27 fallos de preparación
del fixture, antes de ejecutar el lector. `Corruption.name()` producía un publicationId en mayúsculas,
rechazado por el patrón congelado del JSON Schema. Se corrigió sólo ese identificador sintético
con `toLowerCase(Locale.ROOT)` y se repitió únicamente `LegalPublicRequirementsReadServiceIT`.
Los 34 casos pasaron después, incluyendo las 27 corrupciones que ahora sí ejecutaron la frontera
productiva. No se flexibilizaron schema, guards ni validadores para hacer pasar los fixtures.

Surefire: configuración 57, núcleo 14A 81, datasource 19, deadline 9, gate 41, store 12, replay 10
y servicio agregado 19. Failsafe: ServiceIT 34, CommitIT 10, contexto 14B 7, privilegios 14B 53,
servicio agregado 13, aislamiento agregado 2 y servicio documental 16.
Entorno observado: Corretto 21.0.10 (Amazon), Maven 3.9.11, Spring Boot 4.0.6, Spring 7.0.7,
PostgreSQL 16.14 (`postgres:16-alpine`), JDBC 42.7.10, Testcontainers 2.0.5 y Surefire/Failsafe 3.5.5.

ServiceIT acredita dos requisitos —obligatorio y opcional— en ordinales 1 y 7, cinco referencias
y tres UUID documentales: una consulta de texto para dos afirmaciones y otra para tres documentos,
también al reutilizar. La proyección coincide con la observación independiente del owner;
CREATED emite dos DML y REUSED secuencial cero, preservando identidad, filas editoriales y secuencias.
REPLACE real de otra línea mantiene el contenido/token de REGISTRO pero cambia conjunto/publicación
y crea otra identidad física; las versiones conservan su publicación de introducción anterior.
REQUIRES_NEW confirma el resultado completo y restaura la transacción exterior, aun si ésta revierte.

Las 27 corrupciones cubren afirmación y Markdown (digest, canonicidad y exceso), afirmación invisible,
falta de obligatorio, revisión componente, versión documental incompatible, opcional sin documentos,
huérfanos, membresía incompleta, slot/contexto ausente, ordinales, audiencia, estados y fecha futura,
publicación sin sellar y excesos globales de 129 documentos/257 requisitos. Todas revierten las dos
filas nuevas después de que el store real devolvió CREATED. Los excesos de texto/publicación fallan
antes de emitir consultas de TEXT. Sobre REUSED, una alteración de afirmación con digest válido
preserva el agregado confirmado; restaurar el contenido permite reutilizarlo sin DML.
Deriva de esquema/ACL se rechaza antes de lock/DML y recupera tras restauración.

CommitIT acredita diez escenarios con PostgreSQL real, observación de TransactionSynchronization,
contadores físicos y otra conexión owner: fallo/deadline precommit revierten; perder el acuse SQL
o Runtime después de un COMMIT real informa UNKNOWN sin rollback; callback/deadline/cierre tras
confirmación mantienen COMMITTED y rechazan entrega. Los errores SQL/Runtime posteriores al cierre
físico también rechazan éxito aunque Spring los absorba, incluso si el caller captura un error
anidado. La operación siguiente recupera CREATED o REUSED según corresponda; no hay retry implícito,
filas parciales ni conexiones activas al terminar los escenarios probados. El reloj inyectado
acredita fases deterministas, no una latencia real ni un SLA de teardown.

Comandos focalizados ejecutados con Java 21 explícito:

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dtest=LegalPublicRequirementsDatabaseConfigurationTest,LegalPublicRequirementsValidatorTest,LegalPublicRequirementsDataSourceTest,LegalPublicRequirementsDeadlineTest,LegalManifestDatabaseGateTest,LegalRequiredSetAggregateStoreTest,LegalRequiredSetAggregateReplayVerifierTest,LegalRequiredSetAggregateServiceTest test
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -DskipTests package
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dit.test=LegalPublicRequirementsReadServiceIT,LegalPublicRequirementsCommitIT,LegalPublicRequirementsDatabaseContextIT,LegalPublicRequirementsPrivilegeVerifierIT,LegalRequiredSetAggregateServiceIT,LegalRequiredSetAggregateDatabaseIsolationIT,LegalPublicDocumentReadServiceIT failsafe:integration-test failsafe:verify
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dit.test=LegalPublicRequirementsReadServiceIT failsafe:integration-test failsafe:verify
```

La corrección de cierre se limita al contexto 14B nuevo; se verificaron sus unitarios, contexto y
permisos reales además de CommitIT. Los fallos de compilación/preparación no revelaron regresión
compartida: no justifican repetir clases sin cambios ni ampliar a `clean verify`, reservado para
14E. Revisiones independientes cubrieron SQL/pertenencia, recursos/commit, fixtures y documentación.
Concurrencia editorial, extremos de capacidad válidos, transporte y gate integral siguen pendientes.

Whitelist final: cinco archivos de producción (reader/servicio nuevos y tres extensiones de 14B),
cuatro de tests (tres nuevos y configuración existente), plan y diseño: **11 archivos**.
Sin cambios a métricas compartidas, HTTP, ACL, esquema, contenido legal real o frontend. V27/V28
conservan los hashes del diseño; frontend `7545201` y sus dos directorios no versionados se preservan.
Commit local sin push. Próximo corte: 14D, transporte HTTP bajo su flag apagado por defecto.

## 14D — HTTP público y políticas cerradas

Baseline 14D: `741a1b2`, árbol limpio. El titular autorizó continuar el 2026-09-05.
Se confirma la whitelist nominal. Tests nuevos concretos: `LegalPublicRequirementsControllerTest`,
`LegalPublicRequirementsHttpConfigurationTest`, `LegalPublicRequirementsRequestMatcherTest`,
`LegalPublicRequirementsRateLimitPropertiesTest`, `LegalPublicRequirementsSecurityTest` y
`LegalPublicRequirementsHttpIT`. Se amplía `PublicEndpointRateLimitFilterTests` para la política
independiente. Los fixtures `LegalPublicDocumentSecurityTest`, `LegalPublicDocumentHttpIT` y
`LegalPublicDocumentHttpITSupport` sólo incorporan el matcher nuevo, apagado por defecto, como
dependencia explícita de los filtros compartidos; no cambian sus assertions documentales.
Por el impacto transversal de SecurityConfig/JwtFilter/rate limit, se ejecutará `clean verify`
después del gate focal. Es la excepción prevista en la política; no sustituye el nuevo gate
integral posterior a concurrencia/capacidad de 14E. Sin cambios de propiedades reales ni frontend.

Resultado: únicamente el GET público previsto, bajo flag propio apagado por defecto.

Pasos:

1. Crear puente de child context sin parent, copiar sólo credencial dedicada y flag interno,
   exponer la fachada y acreditar cierre normal/fallo. Flag sin activar no crea beans/rutas nuevas.
2. Implementar controller, DTO y advice acotado con el wire/error exactos del diseño. Queries String
   sin default silencioso, locale primero, repetidos rechazados, sin binding a enums que altere errores.
3. Mapear el resultado ya acreditado sin lazy loads, claves privadas ni transformación del texto.
   Comparar el DTO serializado con la proyección canónica para descubrir drift de fecha/orden/campos.
4. Evaluar ETag sólo después del servicio completo y construcción del DTO; cubrir lista de tags,
   weak/strong equivalentes, wildcard, precondiciones y errores con ETag previo. `304` sin body.
5. Crear matcher único GET exacto compartido por SecurityConfig, JwtFilter y rate policy propia.
   Mantener matcher documental; probar los cuatro estados combinados de flags y contextPath.
6. Agregar policy `public-legal-requirements` a 60/min/IP configurable con headers/no-store;
   preservar política proxy, CORS y flujos de sesión actuales. No ampliar permisiones por prefijo.
7. Actualizar estado implementado en FRONTEND_INTEGRATION y README sin habilitar el handoff global.

Whitelist nominal:

- `http/LegalPublicRequirementsController.java`, `LegalPublicRequirementsResponses.java`,
  `LegalPublicRequirementsHttpException.java`, `LegalPublicRequirementsExceptionHandler.java`,
  `LegalPublicRequirementsHttpConfiguration.java` (nuevos).
- `sec/LegalPublicRequirementsRequestMatcher.java` (nuevo).
- Clases existentes `SecurityConfig.java`, `JwtFilter.java`, `PublicEndpointRateLimitFilter.java`,
  `RateLimitProperties.java`: confirmar sus rutas con `rg --files` antes de editar.
- Tests nuevos de controller/advice/bridge/matcher y `LegalPublicRequirementsHttpIT.java`.
- Tests existentes de seguridad/JWT/rate/CORS sólo para acreditar la política nueva y regresiones.
- `FRONTEND_INTEGRATION.md`, `README.md`, plan y diseño. No cambiar properties de entornos reales.

Gate focal: wire MVC, puente PostgreSQL real, flags exactos, JWT inválido público, vecinos/HEAD,
sesión existente, rate limit incluso en error/304, CORS y regresiones HTTP documentales. Considerar
el impacto transversal real de los filtros para aplicar la política de ampliar checks.

Commit: `feat(legal): publica requisitos de registro`.

### Decisiones y evidencia focal de 14D

- `LegalPublicRequirementsHttpConfiguration` es un puente web condicionado por el flag propio.
  Su contexto sin padre elimina fuentes/perfiles ambientales, copia exactamente las tres
  credenciales dedicadas y el flag interno, y expone sólo la fachada. El cierre es idempotente;
  un fallo de refresh/obtención de fachada cierra el contexto parcial y conserva la causa.
- Controller, DTO y advice propios conservan el wire aprobado. La validación explícita por
  `getParameterValues` rechaza valores ausentes, inválidos y repetidos sin trim ni default,
  siempre con locale primero. La construcción del DTO completo ocurre después del servicio y
  antes de cache/precondiciones. Ningún resultado parcial recibe ETag de éxito.
- El matcher compartido clasifica únicamente GET exacto y verifica contextPath una sola vez.
  Las cuatro combinaciones de flags preservan las dos superficies independientemente. Los
  constructores históricos de filtros conservan requisitos apagados al usarse fuera de Spring.
- La cuota `public-legal-requirements` es independiente, configurable y cuenta 200/304/400/503.
  Su ventana participa en cleanup para no perder una cuota más larga que las demás. Se mantienen
  IP/proxy, CORS y autenticación persistida. HEAD y vecinos no reciben la excepción pública.
- El fixture HTTP PostgreSQL usa los dos puentes reales con roles restringidos en dos contenedores
  efímeros, conservando los prefijos de seguridad propios de cada fixture. La instrumentación
  pasiva se coloca antes del primer GET sobre el pool real; no sustituye servicio, JDBC, store,
  gate ni manager y acredita el cierre de contextos y pools. Los únicos mocks son JWT/usuarios.
- El README y contrato de integración actualizan el estado implementado y configuración operativa.
  Los flags permanecen apagados y el handoff global cerrado. No hay cambios frontend ni DDL/DCL
  fuera de las bases efímeras de tests. V27/V28 permanecen byte a byte iguales.

Comandos ejecutados desde backend con `JAVA_HOME` de Corretto 21.0.10:

```bash
./mvnw -Dtest=LegalPublicRequirementsControllerTest,LegalPublicRequirementsHttpConfigurationTest,LegalPublicRequirementsRequestMatcherTest,LegalPublicRequirementsRateLimitPropertiesTest,LegalPublicRequirementsSecurityTest,LegalPublicDocumentSecurityTest,PublicEndpointRateLimitFilterTests package
./mvnw -Dit.test=LegalPublicRequirementsHttpIT,LegalPublicDocumentHttpIT,LegalPublicDocumentHttpConcurrencyIT,LegalPublicDocumentHttpCapacityIT failsafe:integration-test failsafe:verify
```

Surefire focal: **169** pruebas, sin fallos, errores ni omitidas; finalizó el
2026-09-05 16:40:31 -03:00, Maven **16.495 s**, incluido empaquetado de ambos artefactos.
Desglose: controller 64, puente 32, matcher 30, properties 4, seguridad nueva 16,
seguridad documental 9 y filtro de rate limit 14.

El primer intento compiló, pero sus 16 casos de seguridad nueva fallaron al crear un catálogo
sintético vacío, que el contrato documental rechaza; provocó también stubbings incompletos en
Mockito. Se corrigió sólo el fixture para construir un catálogo válido de un documento antes
`thenReturn`, y se repitieron los 169 casos con éxito. No hubo defecto de producción asociado.
El selector inicial también incluía `JwtFilter*`, sin clase coincidente; no se cuenta como prueba.
La regresión JWT del focal está en los dos tests de seguridad y el gate integral no usa selector.

Failsafe focal: **42** pruebas, sin fallos, errores ni omitidas; finalizó el
2026-09-05 16:41:53 -03:00, Maven **1 min 02 s**. Desglose: HTTP nuevo 27,
HTTP documental 7, concurrencia documental 4 y capacidad documental 4.
PostgreSQL real acredita el wire completo, REUSED/304 con relectura y cero DML, rollback nuevo o
reutilizado ante corrupción opcional, cambio semántico por REPLACE, denegación por drift de ACL,
las cuatro combinaciones de flags y contextPath, rol owner rechazado y ausencia de fallback web.
Los 12 casos de queries inválidas no ejecutan JDBC.

### Gate integral ampliado de 14D

`./mvnw clean verify` con el mismo Java 21 terminó en **BUILD SUCCESS** el
2026-09-05 **16:55:04 -03:00**, Maven **12 min 52 s**, sobre PostgreSQL **16.14**
(`postgres:16-alpine`). Ejecutó **5.471** pruebas: **4.970 Surefire en 150 suites** y
**501 Failsafe en 54 suites**. Los 204 XML confirman cero fallos, errores y omitidas;
`failsafe-summary.xml` informa 501 completadas, cero flakes, sin timeout ni failureMessage.
El gate fresco no tuvo fallos ni requirió cambios posteriores de código.

La ampliación acredita la regresión completa por filtros compartidos, incluyendo aislamiento
tenant, autenticación, roles, procesos CLI, operación editorial y V27/V28. No sustituye la evidencia
nueva de concurrencia/capacidad HTTP que corresponde a 14E. No se interpreta la duración de esta
corrida como SLA ni se atribuyen las mediciones documentales a la superficie nueva.

Artefactos frescos del `clean verify` (hash del archivo completo, no promesa de build reproducible):

| Artefacto | Bytes | SHA-256 |
| --- | ---: | --- |
| `mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar` | 90571324 | `c2bf89c92f4435e05878ae4c66b464a7589ec1dbdc825a85a44f5d6255871317` |
| `mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar` | 90571320 | `2ed8d3e272bb4e71ed73eea4d5388d3498effef89e0066c02f7b5af1a7ade641` |

Ambos usan `JarLauncher`; entrypoints respectivos `LegalManifestCli` y
`MvgrReparacionesBackendApplication`. La inspección ZIP no encuentra Testcontainers, Mockito,
agente Byte Buddy, clases HTTPIT ni configuración local; contiene las ocho clases HTTP nuevas
(contando records) y conserva V27/V28 con los mismos SHA-256 que las fuentes congeladas.
Versiones del artefacto: Spring Boot 4.0.6, Spring Core 7.0.7, JDBC PostgreSQL 42.7.10,
Flyway 11.14.1. Maven 3.9.11, Corretto 21.0.10 y Surefire/Failsafe 3.5.5 en esta ejecución.
Los hashes de ambos artefactos se comprobaron nuevamente al finalizar el gate; no se versionan
los JAR ni los reportes. El diff nominal tiene 24 archivos: 10 de producción, 10 de tests/fixtures y
4 documentos. `git diff --check` aprobado, V27/V28 intactas y frontend sin modificaciones.
Commit atómico local sin push: `feat(legal): publica requisitos de registro`. Siguiente corte: 14E.

## 14E — Concurrencia, capacidad y gate integral

Baseline 14E: `82b6708`, árbol backend limpio. El titular autorizó continuar el 2026-09-05.
Whitelist confirmada antes de editar. Se añade nominalmente
`LegalPublicRequirementsHttpDeadlineIT.java` para separar los escenarios de pool, locks,
cancelación, commit y cierre de las pruebas de causalidad y capacidad. El helper
`LegalPublicRequirementsHttpITSupport` recibe la fachada real de un grafo PostgreSQL restringido
propiedad del test y sólo administra MVC/seguridad; el puente de producción ya está acreditado
por `LegalPublicRequirementsHttpIT`, que se conserva en el gate. Las dependencias JWT/usuarios
pueden ser mocks; el servicio, store, gate, JDBC, transacción y PostgreSQL permanecen reales.
Se añade `LegalPublicRequirementsCapacityITSupport.java` como helper nuevo de tests compartido
por capacidad y deadline: importa/promueve manifiestos válidos y calcula expectativas owner por
fuera de la instrumentación del consumidor. Evita duplicar el generador de 65/251 miembros y no
modifica el fixture de 14C. Concurrencia puede provisionar un segundo rol documental dentro de su
propia base Testcontainers para comprobar HTTP histórico después de RETIRE; valida el nombre de
base y la ausencia de la identidad antes de crearlo, mantiene la allowlist documental exacta y
no amplía el rol de requisitos ni los prefijos de seguridad de fixtures anteriores.
No hay cambios de producción previstos. Si se detecta un defecto, se documentará y corregirá en
un corte atómico previo conforme al plan, sin ocultarlo en el commit de cierre.

Resultado: evidencia de cierre de toda la superficie implementada, sin ampliar su alcance.

1. Probar dos observadores shared simultáneos y writer REPLACE/PROMOTE/RETIRO exclusivo esperando.
   Las respuestas muestran todo el estado anterior o posterior; nunca token y contenido mezclados.
   Dos creadores equivalentes convergen a una identidad; repetición estable no agrega DML.
2. Acreditar que un cambio posterior al commit invalida el ETag cuando corresponde; un retiro que
   deja incompleto el registro devuelve 503 aunque el catálogo documental histórico siga disponible.
   Probar igual token semántico con procedencia física distinta: no reutilizar por digest solamente
   ni dar por acreditado un receipt de otra publicación.
3. Capacidad con fixture editorial válida, hasta 256 requisitos y límite expandido permitido,
   documentos compartidos, último miembro influyendo en hash y bytes wire medidos. Los límites
   máximos estructurales no implican que todas sus combinaciones sean válidas: respetar los slots
   VIGENTE por tipo/contexto de V27. Usar corrupción efímera explícita sólo para exceder invariantes.
4. Documentar sentinelas y excesos de filas/texto, historia de más de 128 versiones sin arrastrarla
   a la consulta del scope, batches finitos, ausencia de N+1 y recursos cerrados. No afirmar un límite
   de heap ni tamaño HTTP igual al Markdown expandido.
5. Medir lock ocupado, pool agotado y deadline durante lectura/cálculo/commit/limpieza. Comprobar
   ausencia de respuesta parcial, `no-store`, cero ETag de éxito y recuperación posterior.
6. Ejecutar `clean verify` nuevo con Java 21 y PostgreSQL 16. Revisar reportes XML, omitidas,
   artefacto web y CLI, bytes migratorios congelados y ausencia de secretos/agentes de pruebas.
7. Documentar versiones, cantidades reales, fallos y resolución, tiempos observados, hashes de
   artefactos y límites de las mediciones. Actualizar cierre, plan, diseño, README y contrato de estado.

Whitelist nominal:

- Tests nuevos `LegalPublicRequirementsHttpConcurrencyIT.java`,
  `LegalPublicRequirementsHttpCapacityIT.java`, `LegalPublicRequirementsHttpITSupport.java`.
- Fixtures/tests de 14B–14D y `LegalJdbcMetricsSupport.java` sólo según necesidades justificadas.
- `docs/plans/2026-09-05-legal-public-requirements-read-closure.md` (nuevo).
- Plan, diseño, README y FRONTEND_INTEGRATION. Un hallazgo que requiera producción se documenta y
  se corrige en un corte atómico previo; no se esconde dentro de un commit de tests/documentación.

Commit previsto: `test(legal): cierra requisitos publicos de registro`.

### Preparación y alcance de evidencia 14E

El generador compartido usa importación y promoción/reemplazo reales. La publicación extrema
contiene 256 requisitos totales: 251 de REGISTRO y los cinco necesarios para cubrir los otros
contextos de la matriz congelada. No se presenta como un scope de 256 requisitos acreditado por
la vía editorial. La proyección pura de 256 y sus límites permanecen verificados por
`LegalPublicRequirementsValidatorTest`. El máximo de Markdown expandido se prueba con 253
referencias a tres UUID vigentes y 393.216 bytes distintos, que suman 16.777.216 bytes expandidos.
Los tests miden por separado los bytes del wire; no infieren un límite de JSON ni de heap.

Los tests nuevos de causalidad conservan el código real de gate/store/reader y usan barreras
explícitas: antes de hidratar para REPLACE/RETIRE, antes del store para PROMOTE sin puntero y en
el proveedor de UUID existente para forzar dos lookups vacíos concurrentes. Esta carrera puede
intentar dos INSERT de cabecera; la ausencia de DML se exige a una repetición estable posterior.
Los casos de vencimiento diferencian rollback, commit confirmado y acuse incierto. Un reloj
controlado después del cálculo real acredita el chequeo cooperativo, no una medición de CPU.

Preparación focal con Java 21:

```bash
./mvnw -Dtest=LegalPublicRequirementsValidatorTest package
./mvnw -Dit.test=LegalPublicRequirementsHttpConcurrencyIT,LegalPublicRequirementsHttpCapacityIT,LegalPublicRequirementsHttpDeadlineIT,LegalPublicRequirementsHttpIT failsafe:integration-test failsafe:verify
```

El primer `package` falló al compilar por un import del test de concurrencia: `ValidatedEditorialPlan`
es un tipo anidado de `LegalEditorialPlanValidator`. Se corrigió el import, sin tocar producción ni
assertions. El segundo `package` pasó 81 pruebas de acreditación pura, cero fallos/errores/omitidas,
y preparó los artefactos: Maven 15.144 s, final 2026-09-05 17:18:53 -03:00.
La integración inicial acreditó 47/48 casos: RETIRE fue bloqueado por un symlink en la ruta
`@TempDir` de macOS antes de ejecutar. Se corrigió el fixture con `directory.toRealPath()` y se
repitieron los cinco casos de concurrencia: aprobados, Maven 23.920 s, final 17:22:51 -03:00.
Capacidad 7, deadline 9 y puente HTTP 27 habían pasado sin cambios. Revisión posterior reforzó el
criterio 14E de recursos cerrados en DeadlineIT: un poll independiente acotado a 5 s por rol y base
comprueba ausencia de transacciones/advisory locks, permitiendo conexiones idle. Se ejecuta antes
de afirmar cero filas durables tras FETCH/UNKNOWN y no redefine el resultado transaccional.
La repetición de DeadlineIT aprobó sus 9 casos, cero fallos/errores/omitidas: Maven 21.251 s,
final 2026-09-05 17:27:46 -03:00. El focal consolidado queda en 81 Surefire y 48 Failsafe.
Lock/pool/FETCH registraron 1176/1015/3018 ms; excluyen el poll posterior de recursos del servidor.
El FETCH registró 64 filas, STATUS_UNKNOWN y ningún SQLSTATE extraído; no se afirma rollback conocido.

### Cierre y gate integral fresco de 14E

`./mvnw clean verify` con Corretto 21.0.10 terminó en **BUILD SUCCESS** el
**2026-09-05T17:42:44-03:00**, Maven **14 min 44 s**, sobre PostgreSQL 16.14.
Ejecutó **5.492 pruebas**: **4.970 Surefire en 150 suites** y
**522 Failsafe en 57 suites**, sin fallos, errores ni omitidas.
Los 207 XML frescos y `failsafe-summary.xml` confirman los conteos; no hubo flakes, timeout
ni failureMessage. Esta corrida no requirió correcciones ni repeticiones.

El [cierre de requisitos públicos](2026-09-05-legal-public-requirements-read-closure.md) registra
métricas, límites de observación, versiones, hashes y revisión de ambos JAR. Contiene 21 invocaciones
nuevas: concurrencia 5, capacidad 7 y tiempos límite 9; el puente HTTP existente conserva sus 27 casos.
Se distinguió la causalidad de transacciones del orden de entrega HTTP y se midió por separado el
GET y el poll de liberación del servidor. No aparecieron defectos de producción.

Las 28 migraciones empaquetadas coinciden con las fuentes; V27/V28 mantienen sus hashes congelados.
Sin clases exclusivas de tests, Mockito, Testcontainers, agente Byte Buddy ni configuración local
adicional en web/CLI. El diff final tiene diez archivos nominales: cinco tests/helpers nuevos y
cinco documentos. No se modifican fuentes productivas ni tests anteriores. Frontend `7545201`
conserva sus dos rutas no versionadas. Stage explícito, `git diff --check` y revisión final aprobados.
Commit atómico local: `test(legal): cierra requisitos publicos de registro`, sin push.
El bloque 14 queda cerrado; flags, BACKEND-HANDOFF 1 y Tarea 3 continúan sin habilitar.

## Comandos y control de cada commit

Desde backend, Java 21 explícito. Antes de ejecutar un focal se enumeran con `rg --files` los
tests reales del corte y se pasan sus nombres exactos. No usar patrones vacíos ni declarar aprobado
un gate porque Maven no encontró tests.

```bash
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dtest=LegalPublicRequirementsValidatorTest,CanonicalTextValidatorTest,LegalRequiredSetRevisionCalculatorTest,LegalRequiredSetAggregateRevisionCalculatorTest,Rfc8785CanonicalizerTest test
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -DskipTests package
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dit.test=LegalPublicRequirementsReadServiceIT,LegalPublicRequirementsCommitIT failsafe:integration-test failsafe:verify
env JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw clean verify
git diff --check
git diff --cached --name-only
git diff --cached --check
git status --short
```

Son comandos por fase, no una secuencia a ejecutar en cada corte. `-DskipTests package` sirve
para preparar clases y artefactos antes del Failsafe directo cuando corresponda; no es evidencia de
tests aprobados. El `clean verify` queda para 14E o el motivo de ampliación documentado.
No versionar `target`, logs o secretos. Revisar el diff nominal y hacer stage sólo de los archivos
autorizados del corte; confirmar rama, HEAD, frontend y hashes V27/V28 antes y después del commit.

## Evidencia del corte documental previo

Diseño y plan preparados a partir del baseline 13D, con revisión independiente de wire, V28,
privilegios y resultados transaccionales. No se ejecutó Maven ni se modificó código/configuración.
El titular aprobó después el diseño y autorizó implementar 14A. Su evidencia se registra en el
apartado correspondiente. Tras las autorizaciones siguientes se completaron 14B y 14C;
14D y 14E también quedaron completados y documentados arriba, con gate integral fresco de cierre.
