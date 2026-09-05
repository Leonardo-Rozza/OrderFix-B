# Corte 14 — Plan de requisitos legales públicos de registro

Fecha: 2026-09-05

Estado: plan propuesto; ningún corte de implementación iniciado.
Depende de aprobar el [diseño](2026-09-05-legal-public-requirements-read-design.md).
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

## 14B — Contexto, privilegios y recursos acotados

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
- Regresiones actualizadas: `LegalDatabaseBoundaryMarkerTest.java`, `LegalManifestDatabaseGateTest.java`.

Gate focal: tests nuevos, boundary/gate, contexto agregado y documental existentes, privilegios
PostgreSQL 16 y rollback del store con el rol nuevo. Registrar cualquier efecto transversal real
sobre gate/marker y ampliar verificación si corresponde. El futuro endpoint permanece ausente.

Commit: `feat(legal): aisla consulta de requisitos publicos`.

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

## Evidencia de este corte documental

Diseño y plan preparados a partir del baseline 13D, con revisión independiente de wire, V28,
privilegios y resultados transaccionales. No se ejecutó Maven ni se modificó código/configuración.
La próxima acción después de aprobación es 14A; la autorización de cada continuación se registra
aquí cuando ocurra, sin dar por aprobados ni terminados los cortes futuros.
