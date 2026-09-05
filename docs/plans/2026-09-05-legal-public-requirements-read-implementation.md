# Corte 14 — Plan de requisitos legales públicos de registro

Fecha: 2026-09-05

Estado: diseño aprobado por el titular el 2026-09-05; 14A–14B completados, 14C–14E pendientes.
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

## 14D — HTTP público y políticas cerradas

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

## 14E — Concurrencia, capacidad y gate integral

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
apartado correspondiente. Tras la autorización siguiente también se completó 14B;
14C–14E permanecen pendientes.
