# Corte 13 — Implementación de lectura pública documental

Fecha: 2026-09-05

Estado: 13A completado y verificado; 13B–13D pendientes.

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

Dependencia: 13A terminado. Antes de editar se fija la whitelist nominal de este corte dentro de
este mismo plan; los nombres nuevos del lector todavía no representan interfaces comprometidas.

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
