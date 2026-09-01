# Corte 11 — Plan de implementación del cierre operativo de la Fase 2.3C

Fecha: 2026-08-31

Estado: listo para ejecución

Diseño aprobado:

- `docs/plans/2026-08-31-legal-manifest-promotion-closure-design.md`;
- commit local backend `6852c64` (`docs(legal): diseña cierre operativo de fase 2.3C`).

## Objetivo

Ejecutar el cierre cross-repo de 2.3C en cuatro subcortes documentales, con whitelists verificables,
evidencia fresca y commits locales atómicos. El cierre no modifica runtime, schema, migraciones,
contenido legal real ni integración frontend.

## Reglas de ejecución

1. Ejecutar 11A en frontend antes de cualquier cierre backend.
2. No usar `git add -A`, `git add .` ni patrones amplios. Cada commit agrega rutas nominales.
3. Antes de cada commit, verificar que `git diff --cached --name-only` coincida exactamente con la
   whitelist del subcorte.
4. Preservar los directorios frontend no versionados `.agents/` y
   `public/OrdenFix project naming/`. No moverlos, editarlos, borrarlos ni agregarlos.
5. El build frontend es una evidencia local. Aunque Vite copie contenido local de `public/` a un
   artefacto ignorado, ese artefacto no se versiona ni despliega.
6. No ejecutar `npm run build:public` mientras los documentos legales sean borradores.
7. No ejecutar una operación editorial contra una base real, no crear roles reales y no usar
   secretos. El runbook se deriva del código y de las pruebas ya acreditadas.
8. No modificar `src/**`, migraciones, schemas, fixtures, launchers, API, OpenAPI ni flags.
9. Un fallo de gate, una diferencia de schema o un diff tracked fuera de whitelist detiene el
   subcorte. No se amplía alcance para corregirlo.
10. No hacer push, deploy, seed, importación o promoción real.

## Baselines

### Backend

```text
rama: codex/lanzamiento-publico-backend
baseline funcional/documental previo: ccfe894
diseño Corte 11: 6852c64
```

### Frontend

```text
rama: codex/frontend-refactor-checkpoint
baseline: 50f9d69
tracked: limpio
untracked preservado: .agents/, public/OrdenFix project naming/
```

## Preflight común

Ejecutar y registrar antes de editar cada repositorio, entrando primero a su ruta absoluta:

```bash
cd /Volumes/DiscoExtern/Desktop/mvrg-backend
git branch --show-current
git log -1 --oneline
git status --short
git diff --check

cd /Volumes/DiscoExtern/Desktop/mvgr-reparaciones-frontend
git branch --show-current
git log -1 --oneline
git status --short
git diff --check
```

En backend sólo puede estar presente este plan todavía no commiteado al preparar su propio commit.
En frontend sólo pueden aparecer los dos directorios no versionados conocidos.

## Preparación — Commit del plan

### Whitelist backend

```text
docs/plans/2026-08-31-legal-manifest-promotion-closure-implementation.md
```

### Puerta

```bash
cd /Volumes/DiscoExtern/Desktop/mvrg-backend
git diff --check
git add docs/plans/2026-08-31-legal-manifest-promotion-closure-implementation.md
git diff --cached --name-only
git diff --cached --check
```

El listado cached debe contener una única ruta. Commit:

```text
docs(legal): planifica cierre operativo de fase 2.3C
```

## Subcorte 11A — Cierre documental frontend

### Objetivo

Registrar el cierre técnico de 2.3C sin crear ni anunciar una integración funcional.

### Whitelist frontend

```text
docs/legal/README.md
docs/plans/2026-08-23-lanzamiento-publico-confianza-cuenta-plan.md
```

### Cambios

En `docs/legal/README.md`:

1. actualizar el estado al 2026-08-31;
2. registrar que `validate`, `dry-run`, `import`, promoción, reemplazo, retiro y readiness existen
   como operación interna;
3. congelar la secuencia revisión/aprobación editorial → digest/validate/dry-run → import/sello →
   plan `APPLICABLE` → apply → readiness `READY`;
4. enlazar el runbook/closure backend una vez que sus rutas estén congeladas;
5. aclarar que no hubo contenido real, seed, APIs, staging, deploy ni habilitación pública;
6. mantener borradores editables y no aprobados.

En el plan maestro frontend:

1. agregar el registro de Fase 2.3C después de 2.3B;
2. registrar baseline backend `ccfe894`, diseño 11 `6852c64` y evidencia de Corte 10;
3. corregir referencias que todavía ubican promoción/retiro/readiness como pendientes;
4. mantener pendientes V28, catálogo/documentos/requisitos HTTP, aceptación, registro atómico,
   CORS, `409`, `428`, enforcement, contenido definitivo, staging y deploy;
5. mantener Tarea 3 y `BACKEND-HANDOFF 1` cerrados;
6. distinguir readiness editorial interno de readiness público remoto.

No incorporar los nuevos criterios tributarios o jurídicos debatidos durante la planificación: ese
ajuste pertenece a la revisión posterior del roadmap general y no al cierre técnico de 2.3C.

### Gates frontend

```bash
cd /Volumes/DiscoExtern/Desktop/mvgr-reparaciones-frontend
git branch --show-current
git log -1 --oneline
node --version
npm --version
npm run test:release
npm test
npm run build
git diff --check
git diff --name-only
git status --short
```

`git diff --name-only` debe devolver exactamente las dos rutas de la whitelist. Después:

```bash
git add docs/legal/README.md \
  docs/plans/2026-08-23-lanzamiento-publico-confianza-cuenta-plan.md
git diff --cached --name-only
git diff --cached --check
```

El listado cached debe coincidir exactamente con la whitelist. Commit:

```text
docs(plan): registra cierre de fase 2.3C
```

Registrar el SHA resultante para 11D mediante `git rev-parse HEAD`.

## Subcorte 11B — Runbook editorial PostgreSQL

### Objetivo

Dejar una operación reproducible y fail-closed para los siete comandos editoriales internos.

### Whitelist backend

```text
docs/runbooks/legal-manifest-editorial-postgresql.md
docs/runbooks/legal-manifest-import-postgresql.md
```

### Fuentes autoritativas

- `LegalEditorialArguments` para comandos, flags y formatos;
- `LegalEditorialEnvironment` para variables, flag mutante y redacción;
- `LegalEditorialReportWriter` y `LegalManifestStatus` para reporte v3 y exit codes;
- `LegalEditorialSchemaVerifier` y `LegalV27EditorialInventory` para V27;
- `LegalEditorialPrivilegeVerifier` para invariantes del rol;
- `LegalRestrictedEditorialRoleFixture` únicamente como evidencia del perfil probado, nunca como
  script para copiar literalmente a un cluster compartido;
- `scripts/legal-manifest-editor.sh` para routing del JAR/JVM y saneamiento del entorno.

### Contenido mínimo del nuevo runbook

1. alcance, prohibiciones y responsables editables;
2. preflight de release/JDK/JAR/launcher/V27/schema `public`/rol/red/ventana;
3. inventario V27 de 19 tablas, 10 secuencias, 130 columnas, 130 constraints, 58 triggers y 34
   funciones;
4. grants efectivos exactos: `SELECT` sobre las 19 tablas y `flyway_schema_history`, `INSERT` sobre
   7 tablas, `DELETE` sobre 2, `UPDATE` sobre 14 columnas de 6 tablas, `USAGE` sobre 4 secuencias y
   `EXECUTE` sobre 23 funciones;
5. invariantes negativas: sin ownership, memberships, grant options, `TEMP`, DDL, parámetros,
   permisos de aplicación, large objects o `lo_*`; `CONNECT` sólo a la base actual, `USAGE` sólo
   sobre `public`, sin otros schemas ni `CREATE`; rol `LOGIN NOINHERIT`,
   `session_replication_role=origin` y `lo_compat_privileges=off`;
6. evaluación previa del impacto de revocaciones globales y regrants nominales;
7. creación/rotación de la credencial temporal;
8. variables exactas `ORDENFIX_LEGAL_EDITOR_DB_URL`, `ORDENFIX_LEGAL_EDITOR_DB_USERNAME`,
   `ORDENFIX_LEGAL_EDITOR_DB_PASSWORD` y el driver opcional; prohibición de
   `-Dspring.datasource.*`;
9. `ORDENFIX_LEGAL_EDITOR_ENABLED=true` sólo en cada proceso `apply-*`, nunca exportada para plan o
   readiness;
10. pin obligatorio de `ORDENFIX_LEGAL_CLI_JAR` y `ORDENFIX_JAVA_BIN`, hash previo del JAR aprobado
    y prohibición de depender del fallback local a `target/`;
11. siete comandos con flags exactos y ejemplos sin secretos;
12. confirmaciones de publicación, manifiesto, operación y plan;
13. flujo import/sello → plan → apply → readiness;
14. reporte v3, matrices y exit codes;
15. separación y permisos de stdout/stderr;
16. custodia externa del receipt, job, operador, aprobadores y ventana;
17. tratamiento de stdout ausente/truncado, `UNKNOWN` y retry exacto;
18. cierre de job/red, `NOLOGIN`, drenaje/terminación nominal de sesiones, verificación de cero
    sesiones y rotación;
19. diferencia entre readiness editorial y público;
20. prohibición de usar contenido real sin aprobación editorial responsable y autorización del
    entorno.

### Corrección del runbook importador

Reemplazar la sugerencia de un schema operativo configurable por la restricción real a `public`.
Enlazar el nuevo runbook como fase posterior y conservar roles separados.

### Puerta 11B

```bash
cd /Volumes/DiscoExtern/Desktop/mvrg-backend
git branch --show-current
git log -1 --oneline
sh -n scripts/legal-manifest-import.sh
sh -n scripts/legal-manifest-editor.sh
git diff --check
git diff --name-only
git status --porcelain=v1 --untracked-files=all
```

Antes del stage, el diff tracked debe contener sólo el runbook importador y el status debe mostrar
además el nuevo runbook editorial como única ruta untracked del subcorte. Después:

```bash
git add docs/runbooks/legal-manifest-editorial-postgresql.md \
  docs/runbooks/legal-manifest-import-postgresql.md
git diff --cached --name-only
git diff --cached --check
```

Commit:

```text
docs(legal): documenta operacion editorial
```

Registrar el SHA mediante `git rev-parse HEAD`.

## Subcorte 11C — Sincronización de estado backend

### Objetivo

Hacer que la documentación pública del repositorio describa el estado real sin inventar contrato
HTTP.

### Whitelist backend

```text
README.md
FRONTEND_INTEGRATION.md
docs/plans/2026-08-27-legal-manifest-promotion-design.md
```

### Cambios

1. En `README.md`, enlazar el runbook 2.3C y resumir la operación interna disponible. El enlace a
   la closure se difiere a 11D para que 11C no contenga una referencia rota.
2. En `FRONTEND_INTEGRATION.md`, documentar el launcher, los siete comandos, reporte v3 y fronteras
   internas; no agregar endpoints inexistentes ni cambiar tipos HTTP.
3. Corregir el rollout para ubicar V28 y APIs antes de ETag/readiness público y de Tarea 3.
4. En el diseño base, reemplazar el estado histórico de Cortes 1–2 por una descripción durable:
   Cortes 1–10 técnicos completos y estado final delegado a la closure de Corte 11. No afirmar que
   11 cerró ni anticipar evidencia todavía inexistente.
5. Mantener explícitos la ausencia de controllers legales, V28, contenido real, staging y deploy.

### Puerta 11C

```bash
cd /Volumes/DiscoExtern/Desktop/mvrg-backend
git branch --show-current
git log -1 --oneline
git diff --check
git diff --name-only
rg -n "BACKEND-HANDOFF 1|Tarea 3|V28|staging|deploy" \
  README.md FRONTEND_INTEGRATION.md \
  docs/plans/2026-08-27-legal-manifest-promotion-design.md
```

El diff debe contener únicamente las tres rutas autorizadas. Después:

```bash
git add README.md FRONTEND_INTEGRATION.md \
  docs/plans/2026-08-27-legal-manifest-promotion-design.md
git diff --cached --name-only
git diff --cached --check
```

Commit:

```text
docs(legal): sincroniza estado editorial
```

Registrar el SHA mediante `git rev-parse HEAD`.

## Subcorte 11D — Regresión, evidencia y closure

### Objetivo

Producir evidencia fresca, crear la closure y cerrar 2.3C sin autorreferencias imposibles.

### Prerrequisitos

- 11A, 11B y 11C commiteados;
- ambos repositorios sin cambios tracked;
- SHA real de 11A disponible;
- schemas cross-repo intactos;
- ningún push o deploy realizado.

### Puerta frontend confirmatoria

No se repite la suite si 11A no cambió después de su commit. Verificar:

```bash
cd /Volumes/DiscoExtern/Desktop/mvgr-reparaciones-frontend
git branch --show-current
git log -1 --oneline
git status --short
git diff --check
```

### Paridad cross-repo

Desde backend:

```bash
cd /Volumes/DiscoExtern/Desktop/mvrg-backend
git branch --show-current
cmp src/main/resources/legal/manifest/v1/publication-manifest.schema.json \
  ../mvgr-reparaciones-frontend/docs/legal/publication-manifest.schema.json
wc -c src/main/resources/legal/manifest/v1/publication-manifest.schema.json \
  ../mvgr-reparaciones-frontend/docs/legal/publication-manifest.schema.json
shasum -a 256 src/main/resources/legal/manifest/v1/publication-manifest.schema.json \
  ../mvgr-reparaciones-frontend/docs/legal/publication-manifest.schema.json
```

Resultado requerido para ambas copias: `10547` bytes y
`f7a4ee17f53f5ed3f2613d894fa3a4f46896dfaaec0c80dab055e4320f036f8b`.

### Puerta backend fresca

```bash
sh -n scripts/legal-manifest-import.sh
sh -n scripts/legal-manifest-editor.sh
shasum -a 256 scripts/legal-manifest-import.sh scripts/legal-manifest-editor.sh
java -version
./mvnw -version
./mvnw clean verify
shasum -a 256 target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar \
  target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar
git diff --check
git status --short
```

Registrar conteos, fallos/errores/skips, tiempos, JDK y hashes. PostgreSQL se registra desde la
evidencia de la suite PostgreSQL fresca o desde la última prueba controlada de Corte 10F si el
runner no expone una versión más precisa; no se abre una conexión real sólo para documentación.

### Whitelist backend de cierre

```text
docs/plans/2026-08-27-legal-manifest-promotion-closure.md
docs/plans/2026-08-27-legal-manifest-promotion-implementation.md
README.md
```

### Closure

Crear la closure con:

1. alcance cerrado y límites públicos;
2. trazabilidad nominal y hashes de diseño, plan, Cortes 1–10, 11A frontend y 11B–11C backend;
3. 11D como `este commit`, registrando parent y asunto esperado;
4. matriz terminal de siete comandos;
5. inventario V27 y grants efectivos;
6. métricas exactas de concurrencia, capacidad y procesos reales de Corte 10, incluidos stdout
   truncado/ausente, retry exacto, redacción, launcher y aislamiento del JAR;
7. conteos, versiones, tiempos y resultados de la puerta final fresca;
8. hashes de schemas, scripts y JARs;
9. ausencia de V28, APIs, frontend funcional, contenido real, push y deploy;
10. riesgos editables: custodio, entorno, red, ventana, aprobadores y secrets manager;
11. dependencias posteriores.

Actualizar el plan maestro:

- marcar Corte 11 y Fase 2.3C completos;
- registrar commits 11A–11C y representar 11D sin hash autorreferencial;
- reemplazar 10F `este commit` por `ccfe894`;
- enlazar diseño, plan, runbook y closure;
- agregar a `README.md` el enlace a la closure recién creada;
- conservar `BACKEND-HANDOFF 1` cerrado;
- mantener la secuencia posterior V28 → HTTP → aceptación → seguridad → contenido/staging →
  handoff → frontend.

### Puerta documental y commit 11D

```bash
cd /Volumes/DiscoExtern/Desktop/mvrg-backend
git diff --check
git diff --name-only
git status --porcelain=v1 --untracked-files=all
git rev-parse HEAD
git add docs/plans/2026-08-27-legal-manifest-promotion-closure.md \
  docs/plans/2026-08-27-legal-manifest-promotion-implementation.md \
  README.md
git diff --cached --name-only
git diff --cached --check
```

Antes del stage, el status debe contener únicamente esas tres rutas. El listado cached debe
contener exactamente las tres rutas de cierre. Commit:

```text
docs(legal): cierra fase 2.3C
```

## Verificación posterior

### Backend

```bash
cd /Volumes/DiscoExtern/Desktop/mvrg-backend
git log -6 --oneline
git rev-parse HEAD^
git status --short
```

Resultado requerido: árbol limpio y cinco commits backend del Corte 11
(diseño/plan/11B/11C/11D), más la referencia separada al commit 11A frontend.

### Frontend

```bash
cd /Volumes/DiscoExtern/Desktop/mvgr-reparaciones-frontend
git log -2 --oneline
git status --short
```

Resultado requerido: sólo `.agents/` y `public/OrdenFix project naming/` como untracked conocidos;
ningún cambio tracked.

## Condiciones de stop

Detener y documentar sin cerrar 2.3C si:

- una suite falla o tiene skips inesperados;
- el schema difiere en bytes o hash;
- aparece V28 o un cambio a V27;
- el frontend tiene cambios funcionales;
- cualquier whitelist no coincide;
- un runbook exige información implícita o propone secretos en argumentos;
- no puede verificarse el rol con la matriz implementada;
- una afirmación confunde readiness editorial con readiness público;
- se detecta contenido real, seed, conexión a entorno real, push o deploy.

## Resultado esperado

Al terminar, 2.3C queda cerrada como plataforma editorial interna. El siguiente bloque continúa con
V28 y las capas HTTP. El frontend permanece desconectado hasta que `BACKEND-HANDOFF 1` exista y esté
acreditado en staging.
