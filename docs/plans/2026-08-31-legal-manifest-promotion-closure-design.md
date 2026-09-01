# Corte 11 — Diseño de cierre operativo de la Fase 2.3C

Fecha: 2026-08-31

Estado: aprobado para implementación

## Objetivo

Cerrar de forma reproducible la Fase 2.3C de promoción, reemplazo, retiro y readiness editorial
internos. El cierre debe dejar una guía operativa utilizable, evidencia verificable y ambos
repositorios coordinados, sin presentar esa capacidad como integración legal pública.

## Decisiones congeladas

1. El alcance es exclusivamente interno. No se promueve contenido legal real, no se ejecuta un
   seed, no se despliega en staging o producción y no se habilitan APIs, catálogo, aceptación,
   enforcement ni UI legal.
2. `BACKEND-HANDOFF 1` y la Tarea 3 frontend permanecen cerrados.
3. El frontend se actualiza primero y sólo de forma documental. Su commit real se incorpora luego
   a la evidencia backend.
4. La ejecución backend se divide en tres commits documentales además de los commits previos de
   diseño y plan: runbook, sincronización de estado y closure. Esta división reemplaza el único
   commit backend originalmente previsto para mantener atomicidad y revisión simple.
5. La CLI editorial empaquetada opera sobre PostgreSQL 16, V27 y el schema fijo `public`. No se
   documenta un selector de schema inexistente.
6. Importador y editor conservan roles y credenciales separados. El rol importador nunca se amplía
   para promover, reemplazar o retirar.
7. Los hashes históricos prueban ejecuciones concretas; cada release debe volver a calcular y
   custodiar los hashes de sus propios artefactos.
8. Ningún secreto, contenido legal, ruta sensible o dato de operador se incorpora al repositorio.
   Los nombres de entorno, responsables, custodios y ventanas quedan como campos explícitos a
   completar fuera de Git.

## Estado de partida

### Backend

- repositorio: `mvrg-backend`;
- rama: `codex/lanzamiento-publico-backend`;
- baseline: `ccfe894` (`docs(legal): cierra corte de procesos reales`);
- árbol tracked limpio al diseñar el corte;
- Cortes 1 a 10 cerrados;
- V27 continúa como última migración legal;
- no existen controllers ni rutas HTTP legales.

### Frontend

- repositorio: `mvgr-reparaciones-frontend`;
- rama: `codex/frontend-refactor-checkpoint`;
- baseline: `50f9d69` (`docs(plan): registra cierre de fase 2.3B`);
- árbol tracked limpio al diseñar el corte;
- `.agents/` y `public/OrdenFix project naming/` son contenido local no versionado que debe
  preservarse y nunca agregarse por accidente;
- no existen queries, mutations o componentes conectados al contrato legal remoto.

### Contrato cross-repo

La copia backend y la copia frontend del schema v1 deben seguir byte-identical:

- tamaño esperado: `10547` bytes;
- SHA-256 esperado: `f7a4ee17f53f5ed3f2613d894fa3a4f46896dfaaec0c80dab055e4320f036f8b`;
- fixture golden: 11 documentos, 6 requisitos y 8 scopes.

## Arquitectura documental del cierre

El Corte 11 se ejecuta en cuatro subcortes secuenciales.

### 11A — Espejo documental frontend

Modificar exclusivamente:

- `docs/legal/README.md`;
- `docs/plans/2026-08-23-lanzamiento-publico-confianza-cuenta-plan.md`.

Registrar que 2.3C acredita la operación editorial interna y que el flujo correcto es:

1. revisión profesional o aprobación editorial responsable del contenido;
2. digest, `validate` y `dry-run`;
3. `import` y sello técnico;
4. plan editorial con resultado `APPLICABLE`;
5. `apply`;
6. `readiness` con resultado `READY`.

La documentación debe distinguir ese flujo de las capas todavía pendientes: V28 multicontexto,
catálogo HTTP, documentos y requisitos remotos, aceptación, registro atómico, seguridad, CORS,
`409`, `428`, enforcement, staging y deploy.

No se modifican `src/**`, `docs/FRONTEND_INTEGRATION.md`, `docs/openapi.json`, scripts de readiness
remoto ni ninguna superficie funcional. No se ejecuta `build:public` con borradores.

### 11B — Runbook editorial PostgreSQL

Crear `docs/runbooks/legal-manifest-editorial-postgresql.md` y enlazarlo desde
`docs/runbooks/legal-manifest-import-postgresql.md`.

El nuevo runbook debe ser autocontenido para la operación editorial y remitir al runbook de import
para la fase anterior. Debe congelar:

- preflight de release, Java, JAR, launcher, V27, schema `public`, red, secreto temporal, rol y
  ventana;
- perfil SQL idempotente, mínimo y auditable del rol editorial;
- inventario de 19 tablas, 10 secuencias, 130 columnas, 130 constraints, 58 triggers y 34 funciones
  V27;
- grants efectivos: `SELECT` en las 19 tablas y `flyway_schema_history`, `INSERT` en 7 tablas,
  `DELETE` en 2, `UPDATE` restringido a 14 columnas de 6 tablas, `USAGE` en 4 secuencias y
  `EXECUTE` en 23 funciones;
- ausencia de ownership, memberships, grant options, `TEMP`, DDL, parámetros de rol, permisos de
  aplicación, large objects y funciones `lo_*`;
- `CONNECT` sólo a la base actual, sin `CONNECT` a otras bases conectables, `USAGE` únicamente
  sobre `public`, sin uso de otros schemas y sin `CREATE` sobre schema o base;
- `LOGIN NOINHERIT`, `session_replication_role=origin` y `lo_compat_privileges=off`;
- cierre ordenado de job y red, `NOLOGIN` para impedir nuevas conexiones, drenaje o terminación
  nominal de las sesiones exactas del rol, verificación de cero sesiones activas y rotación del
  secreto después de la ventana; `NOLOGIN` por sí solo no cierra conexiones existentes;
- impacto global de revocaciones a `PUBLIC`, que exige inventario y regrants nominales antes de
  aplicarse en un entorno compartido.

La interfaz documentada contiene exactamente siete comandos case-sensitive:

- `readiness`;
- `plan-promote`;
- `apply-promote`;
- `plan-replace`;
- `apply-replace`;
- `plan-retire`;
- `apply-retire`.

Los tres primeros reciben exactamente `--manifest`, `--confirm-publication-id` y
`--confirm-manifest-sha256`. Los cuatro de reemplazo/retiro agregan `--editorial-plan`,
`--confirm-operation-id` y `--confirm-editorial-plan-sha256`. No existe `--help`; argumentos
faltantes, repetidos o adicionales bloquean antes de JDBC.

Todos los comandos reciben exclusivamente:

- `ORDENFIX_LEGAL_EDITOR_DB_URL`;
- `ORDENFIX_LEGAL_EDITOR_DB_USERNAME`;
- `ORDENFIX_LEGAL_EDITOR_DB_PASSWORD`;
- opcionalmente `ORDENFIX_LEGAL_EDITOR_DB_DRIVER_CLASS_NAME`.

Sólo cada proceso `apply-*` recibe `ORDENFIX_LEGAL_EDITOR_ENABLED=true`; la variable no se deja
exportada para planificación o readiness. Las propiedades `-Dspring.datasource.*` están prohibidas.
El launcher admite `ORDENFIX_LEGAL_CLI_JAR` y `ORDENFIX_JAVA_BIN` como routing externo. La operación
debe fijar ambas rutas, verificar el SHA del JAR aprobado y no depender del fallback local a
`target/`.

El runbook debe describir el reporte v3, `PASS/0`, `BLOCKED/2`, `ERROR/3`, la separación de
stdout/stderr, la custodia externa del receipt y la matriz de resultados. `RETIRE` puede terminar
`APPLIED + NOT_READY` con exit `0`: la falta de vigencia posterior es el resultado editorial
esperado, no un error técnico.

Ante stdout ausente, truncado o inválido, el operador no infiere rollback ni inventa un resultado.
Consulta con credenciales de observación y repite exactamente bundle, manifiesto, plan,
`publicationId`, `operationId` y hashes originales. Un nuevo plan u operation ID rompería la
reconciliación diseñada.

El runbook de importación corrige su descripción de schema: el JAR operativo también queda fijado
a `public`; no se ofrece una propiedad de selección que el contexto aislado descarta.

### 11C — Sincronización del estado backend

Actualizar:

- `README.md`;
- `FRONTEND_INTEGRATION.md`;
- `docs/plans/2026-08-27-legal-manifest-promotion-design.md`.

La documentación debe reconocer los siete comandos y el cierre interno de 2.3C, sin convertirlos
en un contrato frontend. Debe diferenciar:

- readiness editorial interno: inspección del grafo V27 por CLI;
- readiness público futuro: APIs, ETag, contenido aprobado, seguridad, deploy y smokes de staging.

El orden de rollout no puede sugerir validar ETag o catálogo antes de que existan. La ausencia de
V28, controllers y endpoints se mantiene visible.

### 11D — Evidencia y closure

Crear `docs/plans/2026-08-27-legal-manifest-promotion-closure.md` y actualizar el plan maestro de
2.3C. La closure debe registrar:

- commits de diseño, plan, Cortes 1–10 y 11A–11C;
- parent y asunto esperado de 11D, identificado como el propio commit de cierre para evitar una
  autorreferencia de hash imposible;
- baseline y hash real del commit frontend;
- matriz terminal de los siete comandos;
- evidencia de concurrencia, capacidad, procesos empaquetados, stdout truncado y retry exacto;
- inventario y grants efectivos del rol PostgreSQL;
- versiones de PostgreSQL y JDK, conteos de tests y duraciones;
- hashes frescos de los JAR y validación de launchers;
- paridad exacta del schema v1;
- ausencia de V28, API, integración funcional frontend, contenido real, deploy y push;
- riesgos, decisiones editables y dependencias posteriores.

La frase `Corte 10F — este commit` del plan maestro se reemplaza por `ccfe894`. La fase se marca
cerrada sólo después de que todas las puertas frescas sean verdes.

## Flujo de datos y evidencia

La operación real futura mantendrá cuatro clases de artefactos separadas:

1. bundle legal aprobado e inmutable;
2. plan editorial canónico y su SHA-256 RFC 8785 producido por el repositorio o pipeline aprobado;
3. stdout JSON v3 como receipt técnico;
4. metadata externa de job, operador, aprobadores, entorno y ventana.

Git conserva el contrato y la guía, pero no secretos, identidad operativa ni contenido real. El
SHA del JSON crudo calculado con `shasum` no se presenta como sustituto del digest canónico del plan.

## Manejo de fallos

El cierre se detiene si ocurre cualquiera de estas condiciones:

- falla una puerta frontend o backend;
- cambia el schema v1 o V27;
- aparece un diff tracked fuera de los archivos autorizados;
- no puede derivarse un comando, argumento o grant exacto desde código o pruebas;
- un documento sugiere que ya existen APIs, staging o contenido aprobado;
- un comando requeriría credenciales o un entorno real para producir la evidencia documental;
- se detecta que un archivo local no versionado entraría en un commit.

No se corrige un fallo ampliando alcance silenciosamente. Los cambios funcionales se derivan a una
fase posterior con diseño propio.

## Estrategia de pruebas

### Frontend

```bash
npm run test:release
npm test
npm run build
git diff --check
git status --short
```

El build es evidencia local y no se despliega. Se preservan los dos directorios no versionados y se
agregan al commit únicamente los dos documentos autorizados.

### Backend

```bash
cmp src/main/resources/legal/manifest/v1/publication-manifest.schema.json \
  ../mvgr-reparaciones-frontend/docs/legal/publication-manifest.schema.json
wc -c src/main/resources/legal/manifest/v1/publication-manifest.schema.json \
  ../mvgr-reparaciones-frontend/docs/legal/publication-manifest.schema.json
shasum -a 256 src/main/resources/legal/manifest/v1/publication-manifest.schema.json \
  ../mvgr-reparaciones-frontend/docs/legal/publication-manifest.schema.json
sh -n scripts/legal-manifest-import.sh
sh -n scripts/legal-manifest-editor.sh
shasum -a 256 scripts/legal-manifest-import.sh scripts/legal-manifest-editor.sh
java -version
./mvnw clean verify
shasum -a 256 target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar \
  target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar
git diff --check
git status --short
```

No se reutiliza un `target/` previo como prueba final: `clean verify` genera la matriz fresca.
La closure obtiene la versión de PostgreSQL de la evidencia PostgreSQL fresca de la suite o de una
consulta controlada ya autorizada; no abre una conexión real sólo para completar documentación.

## Commits previstos

1. `docs(legal): diseña cierre operativo de fase 2.3C`;
2. `docs(legal): planifica cierre operativo de fase 2.3C`;
3. frontend 11A: `docs(plan): registra cierre de fase 2.3C`;
4. backend 11B: `docs(legal): documenta operacion editorial`;
5. backend 11C: `docs(legal): sincroniza estado editorial`;
6. backend 11D: `docs(legal): cierra fase 2.3C`.

Todos los commits son locales. No se hace push, deploy ni promoción de contenido real.

## Criterio de éxito

Corte 11 termina cuando ambos repositorios reflejan el estado real, el runbook permite reproducir
la operación con información explícita, la evidencia fresca es verde y 2.3C queda cerrada sin
confundirse con producción pública. El siguiente trabajo comienza con V28 y las capas HTTP, no con
una conexión frontend prematura.
