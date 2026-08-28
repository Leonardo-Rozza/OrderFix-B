# Fase 2.3B — Cierre de la importación legal idempotente

Fecha: 2026-08-27

Estado: cerrada y verificada; Cortes 1 a 7 completos

Ramas locales al cierre:

- backend: `codex/lanzamiento-publico-backend`;
- frontend: `codex/frontend-refactor-checkpoint`.

## Resultado

OrdenFix dispone de una operación interna capaz de validar, importar y sellar un release legal en
PostgreSQL V27 de forma transaccional e idempotente:

- un import fresco persiste el grafo completo y confirma una publicación `SELLADO`;
- las versiones documentales y de requisitos nuevas permanecen en `BORRADOR`;
- un replay exacto reconcilia el mismo grafo y devuelve el mismo receipt sin escribir ni avanzar
  secuencias;
- una credencial PostgreSQL restringida puede construir y sellar el grafo, pero no promoverlo;
- `validate` y `dry-run` conservan el contrato de reporte v1; sólo `import` usa v2.

El sello es evidencia técnica de integridad e inmutabilidad del grafo. No equivale a aprobación
jurídica, aceptación, comprobante fiscal, firma criptográfica, publicación o vigencia. Esta fase no
promueve, retira, ofrece catálogo/API, registra aceptaciones ni activa enforcement. Por ello,
`BACKEND-HANDOFF 1` continúa cerrado.

## Artefactos y protocolo operativo

El build produce el jar normal y un único jar legal aislado:

```text
target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar
target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar
```

El launcher `scripts/legal-manifest-import.sh`, versionado con modo `100755`, debe distribuirse
junto al jar CLI aprobado. Exige:

- `ORDENFIX_LEGAL_IMPORT_ENABLED=true`;
- URL, username y password mediante `ORDENFIX_LEGAL_IMPORT_DB_*`, con driver opcional;
- ruta explícita del jar en `ORDENFIX_LEGAL_CLI_JAR` y Java opcional en `ORDENFIX_JAVA_BIN`;
- `--manifest`, `--confirm-publication-id` y `--confirm-manifest-sha256` exactamente una vez;
- coincidencia exacta del ID externo y del SHA-256 JCS con el release profesionalmente aprobado.

El launcher elimina `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` antes de iniciar Java.
No se admiten secretos por argumentos, archivos versionados, propiedades `-Dspring.datasource.*`
ni logs. El procedimiento completo y la recuperación de resultados inciertos están en
`docs/runbooks/legal-manifest-import-postgresql.md`.

## Matriz terminal del reporte v2

| Caso | `status` | `persisted` | `import.outcome` | Receipt | Exit |
|---|---|---:|---|---|---:|
| Fresco confirmado | `PASS` | `true` | `IMPORTED` | completo | 0 |
| Replay exacto | `PASS` | `true` | `ALREADY_IMPORTED` | el mismo | 0 |
| Input, confirmación o conflicto bloqueante | `BLOCKED` | `false` | `null` | no | 2 |
| Configuración, schema, privilegios o rollback conocido | `ERROR` | `false` | `null` | no | 3 |
| Finalización DB indeterminada | `ERROR` | `null` | `UNKNOWN` | no | 3 |

`UNKNOWN` oculta UUID, timestamps y estado de sello; `promotionChanged` permanece `false`. No afirma
commit ni rollback. Debe resolverse la causa y repetirse exactamente el mismo bundle con las mismas
confirmaciones: el retry puede terminar `ALREADY_IMPORTED`, `IMPORTED` o en un fallo conocido.

Un stdout ausente, truncado o inválido también deja el resultado operativo indeterminado, pero no
constituye por sí solo un reporte `UNKNOWN`. No se infiere rollback ni se fabrica un envelope: se
aplica la misma reconciliación exacta.

## Persistencia, concurrencia y reconciliación

- El import fresco confirma las 12 tablas del grafo, una publicación `SELLADO`, versiones nuevas
  `BORRADOR`, revisiones por scope y timestamps releídos desde PostgreSQL en una sola transacción.
- El replay compara la cabecera, el canónico, los digests y el grafo inmutable completo; no exige
  que el estado editorial siga en `BORRADOR` y no escribe ni avanza las seis secuencias.
- Imports idénticos concurrentes producen un `IMPORTED` y un `ALREADY_IMPORTED`. Releases
  compatibles se serializan y reutilizan identidades; los incompatibles confirman como máximo uno.
- Dry-run e import comparten el advisory lock editorial y no exponen grafos parciales.
- Lock timeout y deadlock con rollback acreditado producen `ERROR/false`. Terminación de sesión o
  pérdida del acuse de commit sólo producen `UNKNOWN` cuando la frontera transaccional no permite
  conocer el resultado.
- El caso acreditado de stdout cerrado ocurrió después del commit; el retry exacto devolvió
  `ALREADY_IMPORTED` con el receipt persistido.

## Perfil PostgreSQL V27 acreditado

La verificación exacta se ejecuta sobre PostgreSQL 16, migración V27
`V27__persistencia_legal_append_only.sql` y checksum Flyway `1575269868`:

- superficie importadora: 12 tablas, 93 columnas, 97 constraints, 29 triggers de negocio y seis
  secuencias;
- 47 firmas de funciones legales V27 inventariadas y revocadas de `PUBLIC`; sólo 17 quedan
  ejecutables por el importador;
- `SELECT, INSERT` sobre las 12 tablas del grafo;
- `UPDATE` únicamente sobre `legal_publicaciones(estado_construccion, sellado_en)` y cuatro columnas
  `id` necesarias para row locks; los triggers rechazan updates directos;
- `USAGE` sólo sobre las seis secuencias técnicas y `SELECT` sobre el historial Flyway;
- rol no owner, `NOINHERIT`, sin superuser, bypass RLS, memberships, delegación, schema/database
  create, temporales, parámetros amplios, large objects ni privilegios adicionales.

El rol no puede promover o retirar publicaciones, insertar transiciones/pointers/slots editoriales,
registrar aceptaciones o idempotencia HTTP, borrar, truncar, cambiar IDs ni escribir Flyway.

## Capacidad y límites de lectura

La fixture máxima válida acreditó 128 documentos, 256 requisitos, 16 documentos por requisito y 16
scopes, respetando el presupuesto total de Markdown. No representa un payload completo de 16 MiB de
Markdown. La métrica cuenta ejecuciones JDBC lógicas con ida al servidor, no paquetes físicos del
protocolo.

La primera ejecución con 5 ms artificiales por llamada expuso un writer fila a fila de alrededor de
149 s. El Corte 6 agrupó en orden determinista los inserts de 11 relaciones. Las repeticiones
finales de import más replay quedaron entre 7,2 y 9,0 s, por debajo del margen de 70 s y con cada
sentencia por debajo de 30 s.

Las siete lecturas multirrow del replay y las tres históricas del writer usan límite
`cardinalidad esperada + 1`. Una fixture corrupta agregó 4096 vínculos documentales y fue bloqueada
tras leer exactamente 129 filas.

## Evidencia final

- Puertas enfocadas del Corte 6: 5 unitarias de replay; 12 IT de capacidad, concurrencia y fallos;
  17 unitarias CLI; 17 IT de procesos y aislamiento, todas aprobadas.
- Suite backend completa del cierre con JDK 21.0.10 y PostgreSQL 16.14: 729 pruebas unitarias y 113
  de integración, sin fallos, errores ni omitidas.
- Procesos JVM reales con jar empaquetado y rol restringido acreditaron `IMPORTED`,
  `ALREADY_IMPORTED`, conflicto, drift de privilegios, `UNKNOWN`, pérdida de stdout y redacción de
  canaries.
- Inspección de jars: Start-Class normal/CLI correctos, ausencia del
  `application-secret.properties` y de los canaries inspeccionados, y un solo jar importador.
- Regresión frontend del Corte 7: 42/42 pruebas dirigidas del guard, 82 archivos con 498 pruebas
  Vitest y build local aprobados.
- El build frontend local omitió deliberadamente los controles de release y artefactos públicos al
  no existir manifiesto profesional ni variables de deploy; no acredita readiness público.
- Schema compartido byte a byte: 10.547 bytes y SHA-256
  `f7a4ee17f53f5ed3f2613d894fa3a4f46896dfaaec0c80dab055e4320f036f8b`.

## Reproducción

```bash
env \
  JAVA_HOME=/ruta/aprobada/al/jdk-21 \
  PATH=/ruta/aprobada/al/jdk-21/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin \
  ./mvnw verify

cd ../mvgr-reparaciones-frontend
npm run test:release
npm test
npm run build
```

La comparación del schema se reproduce con `cmp`, conteo de bytes y SHA-256 entre
`src/main/resources/legal/manifest/v1/publication-manifest.schema.json` y
`../mvgr-reparaciones-frontend/docs/legal/publication-manifest.schema.json`.

## Incidentes y límites de la acreditación

- El problema de capacidad inicial se registró como fallo real y se corrigió mediante batching; no
  se relajaron locks, constraints, cardinalidades ni timeouts.
- La puerta focalizada del Corte 4 corrió sobre PostgreSQL 16.15; su suite completa quedó entonces
  diferida por indisponibilidad transitoria de Docker. El Corte 6 cerró después toda la regresión
  sobre PostgreSQL 16.14.
- Las pruebas usan fixtures y credenciales sintéticas. No autorizan por sí mismas una importación
  operativa ni prueban la aprobación profesional del contenido.

## Trazabilidad de commits

| Corte | Commit | Resultado |
|---:|---|---|
| 1 | `22e464bc8c9719404c1db119676556151d62ae76` | revisión definitiva por scope |
| 2 | `6da82cf2f1378293c0e5ba8503c0e5ff924703f4` | gate y writer compartidos |
| 3 | `dff55c60d9dcd0b7d4a4154b99cc8430351fb72b` | importación idempotente |
| 4 | `d317a692b1d6e2c4ac344c21235046030efd7b38` | rol PostgreSQL restringido |
| 5 | `e4f894427ce118243f957f3ea9409ac846862aae` | CLI v2 y contexto importador aislado |
| 6 | `79f18ee1b8a388823376a6e36a356c981df67bd3` | launcher, procesos, fallos y capacidad reales |

Trazabilidad previa: diseño `a8cc3522994f55a12fdb9a0fe2ada0a2d5421b4a`, plan
`76dc24cc895d966e3dcfb4be99edb10f8cf8098b` y addendum CLI
`cf2aaafd4c0c507c4dbdc67e01367cb633f5d8bb`. El cierre anterior 2.3A está en
`ec794cbd7bc8476fc7e53ab7a08cbd96db800d55` y su mirror frontend en
`19b495374e1cb484b2f9ad585502b9d62e4883f0`.

## Estado cross-repo

- El backend llegó limpio al Corte 7.
- El frontend conserva intactos los no versionados `.agents/` y
  `public/OrdenFix project naming/`; no forman parte del cierre.
- Este trabajo no hizo push, deploy ni importación operativa de contenido legal real.
- No se agregaron endpoints, controller, scheduler, API client, query, mutation, botón ni flag de
  integración.

## Riesgos diferidos y próxima fase

- **2.3C:** promoción/retiro y readiness editorial, todavía sin enforcement.
- **V28 antes de aceptaciones:** revisión agregada multicontexto.
- **2.4+:** catálogo y documentos remotos, APIs autenticadas, registro/aceptación atómicos,
  integración frontend y enforcement en cortes separados.

Hasta que esas capas estén desplegadas y verificadas en staging, la Tarea 3 frontend no debe
conectar datos reales y `BACKEND-HANDOFF 1` permanece no disponible.
