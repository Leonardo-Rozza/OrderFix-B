# Fase 2.3A — Cierre del validador y dry-run legal

Fecha: 2026-08-25

Estado: cerrada y verificada; Cortes 1 a 7 completos

## Resultado

OrdenFix dispone de una puerta interna y reproducible para revisar un release editorial legal antes
de importarlo:

- `validate` verifica offline bytes, JSON, schema, Markdown, digests, referencias y cobertura;
- `dry-run` repite el mismo núcleo y contrasta el grafo provisional con PostgreSQL V27;
- toda transacción que el dry-run llega a abrir es rollback-only; los blockers estáticos no acceden
  a la base y todos los reportes declaran `persisted: false`;
- frontend y backend comparten el mismo schema byte a byte y aceptan la misma fixture golden;
- el ejecutable legal está aislado del API normal, Flyway, web, JPA, runners y schedulers.

Este cierre **no** habilita importación confirmada, sello, promoción, retiro, readiness, APIs
legales, aceptación, registro compatible ni enforcement. No constituye un release público y
`BACKEND-HANDOFF 1` continúa cerrado.

## Artefactos y comandos

El build conserva el jar normal del backend y agrega:

```text
target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar
```

Validación offline:

```bash
java -jar target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar \
  validate --manifest=/ruta/release/publication-manifest.json
```

Contraste rollback-only contra una base ya migrada con V27:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/database \
SPRING_DATASOURCE_USERNAME=usuario \
SPRING_DATASOURCE_PASSWORD=secreto \
java -jar target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar \
  dry-run --manifest=/ruta/release/publication-manifest.json
```

El parser admite únicamente `validate|dry-run` más una asignación `--manifest=<path>`. No acepta
passwords, propiedades Spring, duplicados ni argumentos adicionales. El environment hijo de
`dry-run` toma por allowlist sólo:

- `spring.datasource.url`, obligatoria;
- `spring.datasource.username`, opcional según la base;
- `spring.datasource.password`, opcional según la base;
- `spring.datasource.driver-class-name`, opcional.

La base debe migrarse por el proceso de despliegue. La CLI no ejecuta Flyway. El operador/CI debe
eliminar o controlar `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS`: la JVM las procesa
antes de `main` y esa salida pre-main no puede ser redactada por la aplicación.

## Reporte estable

Mientras stdout permanezca operativo, contiene exactamente un objeto JSON compacto UTF-8 y un LF
final. El orden de primer nivel es:

```text
reportVersion, command, status, persisted, publication, counts, dryRun, issues,
omittedIssueCount
```

Campos anidados:

- `publication`: `publicationId`, `schemaVersion`, `manifestSha256`;
- `counts`: `documents`, `requirements`, `scopes`;
- `dryRun`: `newDocumentLines`, `newDocumentVersions`, `reusedDocumentVersions`,
  `newRequirementLines`, `newRequirementVersions`, `reusedRequirementVersions`;
- issue: `severity`, `code`, `location`, `message`.

`dryRun` contiene el objeto de conteos únicamente en un `dry-run` con estado `PASS`; en los demás
reportes es `null`. `persisted` siempre es `false`. Los estados y códigos de proceso son:

| Estado | Exit | Significado |
|---|---:|---|
| `PASS` | 0 | contrato válido para el nivel ejecutado |
| `BLOCKED` | 2 | input, referencia, identidad o constraint incompatible |
| `ERROR` | 3 | configuración, schema DB, conexión, timeout o fallo inesperado |

El reporte expone hasta 200 issues y registra el excedente en `omittedIssueCount`. Fuera de la
metadata segura enumerada (`publication`, `counts` y, cuando corresponde, `dryRun`), no imprime
argumentos, campos o contenido sensible, rutas absolutas, PII, SQL, stack traces, constraints
desconocidas ni credenciales. No hay retry automático de locks o conexión.

## Contrato congelado

Schema frontend y backend:

```text
Tamaño: 10547 bytes
SHA-256: f7a4ee17f53f5ed3f2613d894fa3a4f46896dfaaec0c80dab055e4320f036f8b
```

Fixture `release-valid-v1`:

```text
Manifiesto raw SHA-256: cf1de54de0ebe43e792c15e2d0b4329e2f1e65023d71b0eca689a21e2392b68d
Manifiesto JCS SHA-256: b3b452c8f6f4deb459c31524466936f50265c165c772d50ea75364b8c9c6f3e1
Matriz: 11 documentos, 6 requisitos, 8 scopes
```

Matriz contractual mínima:

| Scope obligatorio | Tipos requeridos agregados |
|---|---|
| `REGISTRO / ADMIN_TITULAR` | términos, privacidad, DPA |
| `PRIMER_INGRESO_EMPLEADO / USER` | términos usuario, privacidad usuario, confidencialidad |
| `CONTRATACION_PRO / ADMIN_TITULAR` | términos, condiciones PRO, cancelaciones/reembolsos |
| `ATESTACION_FOTOS / ADMIN_TITULAR,USER` | aviso clientes, atestación |
| `ATESTACION_CREDENCIALES / ADMIN_TITULAR,USER` | aviso clientes, atestación |
| `CIERRE_CUENTA / ADMIN_TITULAR` | cierre de cuenta |

Límites bloqueantes v1:

| Recurso | Máximo |
|---|---:|
| manifiesto raw | 1 MiB (`1_048_576`) |
| profundidad JSON | 32 |
| tokens JSON | 100.000 |
| string JSON | 1 MiB |
| nombre de campo JSON | 256 |
| número JSON | 128 caracteres |
| documentos | 128 |
| requisitos | 256 |
| documentos por requisito | 16 |
| Markdown individual | 1 MiB |
| Markdown total | 16 MiB |
| issues expuestos | 200 |

Superar un límite bloquea; nunca se trunca la entrada para continuar. Los enums del schema limitan
los contextos a 8 y las audiencias a 2.

## Persistencia, rollback y efectos físicos

El dry-run usa una transacción nueva `READ COMMITTED`, timeouts acotados, fuerza constraints
diferibles y marca rollback-only incluso en la salida exitosa. PASS, blockers y fallos tardíos
quedaron acreditados con delta confirmado cero en estas 12 tablas:

1. `legal_publicaciones`;
2. `legal_documento_lineas`;
3. `legal_documento_versiones`;
4. `legal_documento_contextos`;
5. `legal_publicacion_documentos`;
6. `legal_requisito_lineas`;
7. `legal_requisito_audiencias`;
8. `legal_requisito_versiones`;
9. `legal_requisito_documentos`;
10. `legal_publicacion_requisitos`;
11. `legal_requisito_conjuntos`;
12. `legal_requisito_conjunto_miembros`.

Rollback lógico no significa ausencia de efectos físicos. El proceso puede escribir WAL, tomar
locks y avanzar secuencias no transaccionales; una prueba acredita deliberadamente ese avance para
demostrar que el PASS interactuó con JDBC. Para cero efectos físicos se usa una base descartable.
Para cero interacción con DB se usa `validate`.

## Evidencia final

| Comprobación | Resultado |
|---|---:|
| schema frontend/backend (`cmp`, tamaño y SHA-256) | PASS |
| suite enfocada backend de schema/paridad/golden/JCS/validator/reporte | 64/64 |
| fixture golden exacta bajo el guard frontend | `issues: []` |
| guard de release frontend | 42/42 |
| build frontend | PASS |
| `./mvnw verify` — unitarias | 562/562 |
| `./mvnw verify` — integración | 56/56 |
| ambos jars sin `application-secret.properties` | PASS |
| procesos reales: `validate` PASS/BLOCKED y `dry-run` PASS/ERROR | PASS |
| aislamiento de Flyway/web/JPA/runners/schedulers | PASS |
| rollback V27 después de PASS, blocker y error tardío | PASS |

La batería RFC 8785 fija por checksum el corpus oficial, cubre cuatro vectores oficiales
compatibles, dos vectores de producto más estrictos, los 24 números finitos del Apéndice B, orden
UTF-16 y escapes. El guard frontend no calcula JCS ni reproduce los límites de bytes y carreras: el
backend sigue siendo autoritativo para esas garantías.

El primer `./mvnw verify` de este cierre tuvo un fallo SSL transitorio durante el
provisioning/migración de la IT, antes de las aserciones de `LegalManifestCliProcessIT`. La IT se
repitió aislada con 6/6 y luego el `verify` completo pasó 562/56. No se modificó código para ocultar
el evento ni se atribuyó a una incompatibilidad funcional.

## Reproducción

Desde el backend, con Java 21 y Docker disponible:

```bash
./mvnw verify

./mvnw \
  -Dtest=LegalManifestSchemaTest,LegalManifestFrontendParityTest,LegalManifestGoldenFixtureTest,Rfc8785CanonicalizerTest,LegalManifestValidatorTest,LegalManifestReportTest \
  test
```

Paridad byte a byte desde cualquier directorio, indicando ambos checkouts:

```bash
BACKEND_REPO=/ruta/al/checkout/backend
FRONTEND_REPO=/ruta/al/checkout/frontend

shasum -a 256 \
  "$FRONTEND_REPO/docs/legal/publication-manifest.schema.json" \
  "$BACKEND_REPO/src/main/resources/legal/manifest/v1/publication-manifest.schema.json"

wc -c \
  "$FRONTEND_REPO/docs/legal/publication-manifest.schema.json" \
  "$BACKEND_REPO/src/main/resources/legal/manifest/v1/publication-manifest.schema.json"

cmp -s \
  "$FRONTEND_REPO/docs/legal/publication-manifest.schema.json" \
  "$BACKEND_REPO/src/main/resources/legal/manifest/v1/publication-manifest.schema.json"
```

Desde el frontend:

```bash
npm run test:release
npm run build
```

Para ejecutar la fixture backend exacta contra el guard frontend sin modificar ningún repo:

```bash
BACKEND_REPO=/ruta/al/checkout/backend
FRONTEND_REPO=/ruta/al/checkout/frontend
parity_fixture_root=$(mktemp -d /tmp/ordenfix-parity.XXXXXX)
mkdir -p "$parity_fixture_root/docs/legal/publications" "$parity_fixture_root/src"
cp -R \
  "$BACKEND_REPO/src/test/resources/legal/manifest/release-valid-v1" \
  "$parity_fixture_root/docs/legal/publications/release-valid-v1"
cp \
  "$FRONTEND_REPO/docs/legal/publication-manifest.schema.json" \
  "$parity_fixture_root/docs/legal/publication-manifest.schema.json"
PARITY_FIXTURE_ROOT="$parity_fixture_root" FRONTEND_REPO="$FRONTEND_REPO" \
  node --input-type=module -e \
  "import {pathToFileURL} from 'node:url'; const moduleUrl=pathToFileURL(process.env.FRONTEND_REPO+'/scripts/lib/public-release-validation.mjs').href; const {validatePublicRelease}=await import(moduleUrl); const environment={VITE_API_BASE_URL:'https://api.ordenfix.com',VITE_LEGAL_EMAIL:'legal@ordenfix.com',VITE_PRIVACY_EMAIL:'privacidad@ordenfix.com',VITE_SUPPORT_EMAIL:'soporte@ordenfix.com'}; const issues=await validatePublicRelease({root:process.env.PARITY_FIXTURE_ROOT,environment,manifestPath:'docs/legal/publications/release-valid-v1/publication-manifest.json'}); process.stdout.write(JSON.stringify({fixture:process.env.PARITY_FIXTURE_ROOT,issues})+'\\n'); process.exitCode=issues.length===0?0:1;"
```

## Advertencias no bloqueantes para el lanzamiento

- El build local no recibió contexto de deploy, por lo que registró
  `PUBLIC_RELEASE_CHECK_SKIPPED` y `PUBLIC_ARTIFACT_CHECK_SKIPPED`. Esto no acredita un deploy real.
- Vite advirtió un chunk principal de `519.43 kB` minificado (`142.57 kB` gzip), apenas por encima
  del umbral de 500 kB. No bloqueó esta fase, pero queda como mejora de performance.
- El directorio ajeno no versionado `public/OrdenFix project naming/` fue copiado por Vite al `dist/`
  local ignorado. Un CI limpio no lo tendrá; ese `dist/` local no debe usarse como artefacto de
  publicación.
- Se preservaron sin modificar los no versionados frontend `.agents/` y
  `public/OrdenFix project naming/`.

## Trazabilidad de commits

La implementación 2.3A quedó separada en commits locales atómicos desde `02f15ec` hasta este cierre.
Los cortes funcionales principales son:

- `b884b15` schema y dependencias;
- `d2a52d9`, `dd6dfb5`, `1f26ca7` parser, confinamiento y contrato estático;
- `64ee07a`, `8b386db`, `614d3b8` dry-run V27, pruebas y documentación;
- `5b97e63`, `334ba7b`, `143beae`, `8769a6c` reporte, CLI, procesos y documentación;
- frontend `19b4953` cierre documental coordinado;
- backend `docs(legal): cierra fase 2.3A` cierre documental coordinado.

No se hizo push en ninguno de los dos repositorios.

## Próximo corte

La secuencia aprobada continúa con **Fase 2.3B: importación idempotente y sello transaccional real,
sin promoción**. Recién 2.3C incorporará promoción/retiro y readiness de datos; APIs, aceptación y
enforcement quedan para fases posteriores.
