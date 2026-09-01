# Fase 2.3C — Cierre de promoción y readiness editorial internas

Fecha: 2026-08-31

Estado: cerrada y verificada; Cortes 1 a 11 completos

Ramas locales al cierre:

- backend: `codex/lanzamiento-publico-backend`;
- frontend: `codex/frontend-refactor-checkpoint`.

## Resultado

OrdenFix dispone de una plataforma editorial interna sobre PostgreSQL 16 y V27 capaz de:

- consultar la readiness del grafo legal persistido;
- planificar y aplicar una primera promoción;
- planificar y aplicar reemplazos uno-a-uno, split y merge;
- planificar y aplicar retiros fail-closed;
- reconciliar replays y resultados transaccionales inciertos a partir del postestado exacto;
- operar con un rol PostgreSQL temporal, mínimo y separado del importador.

La superficie está limitada a siete comandos de una CLI interna. El contrato HTTP v1 está diseñado,
pero todavía no existen controllers/endpoints legales en runtime ni una integración funcional
frontend. Esta fase no acredita contenido jurídicamente aprobado, aceptaciones, vigencia pública,
ETag remoto, enforcement, staging, deploy ni disponibilidad productiva. `BACKEND-HANDOFF 1` y la
Tarea 3 frontend permanecen cerrados.

El sello, los fingerprints, los planes y los receipts son evidencia técnica. No constituyen firma
jurídica, dictamen profesional, comprobante fiscal ni autorización para publicar borradores.

## Artefactos y operación

El build genera:

```text
target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT.jar
target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar
```

Los launchers versionados son:

- `scripts/legal-manifest-import.sh`, para la importación y el sello de 2.3B;
- `scripts/legal-manifest-editor.sh`, para la operación editorial de 2.3C.

La ejecución editorial real debe usar el segundo launcher con `ORDENFIX_LEGAL_CLI_JAR` y
`ORDENFIX_JAVA_BIN` fijados a artefactos previamente aprobados. El datasource se recibe únicamente
por `ORDENFIX_LEGAL_EDITOR_DB_URL`, `ORDENFIX_LEGAL_EDITOR_DB_USERNAME`,
`ORDENFIX_LEGAL_EDITOR_DB_PASSWORD` y el driver opcional
`ORDENFIX_LEGAL_EDITOR_DB_DRIVER_CLASS_NAME`. Las propiedades `-Dspring.datasource.*` están
prohibidas. `ORDENFIX_LEGAL_EDITOR_ENABLED=true` se habilita sólo en el proceso individual de cada
`apply-*`.

El procedimiento completo, incluidos grants, red, captura de stdout/stderr, reconciliación,
`NOLOGIN`, drenaje de sesiones y rotación del secreto, está en
`docs/runbooks/legal-manifest-editorial-postgresql.md`. La fase anterior se conserva en
`docs/runbooks/legal-manifest-import-postgresql.md` y usa otro rol.

## Superficie y matriz terminal v3

Los comandos y sus argumentos son case-sensitive y no admiten aliases, extras ni duplicados:

| Comando | Escritura | Plan externo | Resultado exitoso |
|---|---:|---:|---|
| `readiness` | no | no | `READY` |
| `plan-promote` | no | no | `APPLICABLE` |
| `apply-promote` | sí | no | `APPLIED` o `ALREADY_APPLIED` |
| `plan-replace` | no | sí | `APPLICABLE` |
| `apply-replace` | sí | sí | `APPLIED` o `ALREADY_APPLIED` |
| `plan-retire` | no | sí | `APPLICABLE` |
| `apply-retire` | sí | sí | `APPLIED` o `ALREADY_APPLIED` con `NOT_READY` |

Los tres primeros exigen exactamente `--manifest`, `--confirm-publication-id` y
`--confirm-manifest-sha256`. Los cuatro de reemplazo/retiro agregan `--editorial-plan`,
`--confirm-operation-id` y `--confirm-editorial-plan-sha256`.

El reporte v3 conserva este orden superior:

```text
reportVersion, command, status, persisted, publication, operation, plan,
readiness, counts, issues, omittedIssueCount
```

| Caso | `status` | `persisted` | Resultado | Readiness | Exit |
|---|---|---:|---|---|---:|
| consulta lista | `PASS` | `false` | — | `READY` | 0 |
| consulta incompleta | `BLOCKED` | `false` | — | `NOT_READY` | 2 |
| consulta no determinable | `ERROR` | `false` | — | `ERROR` o ausente | 3 |
| plan aplicable | `PASS` | `false` | `APPLICABLE` | esperada | 0 |
| plan bloqueado | `BLOCKED` | `false` | `BLOCKED` | — | 2 |
| fallo operativo de plan | `ERROR` | `false` | `ERROR` | — | 3 |
| apply confirmado | `PASS` | `true` | `APPLIED` | posterior | 0 |
| replay exacto | `PASS` | `true` | `ALREADY_APPLIED` | posterior | 0 |
| apply bloqueado | `BLOCKED` | `false` | `BLOCKED` | — | 2 |
| rollback conocido | `ERROR` | `false` | `ERROR` | — | 3 |
| finalización indeterminada | `ERROR` | `null` | `UNKNOWN` | — | 3 |

Un `apply-retire` exitoso termina deliberadamente en `NOT_READY` con exit `0`; una consulta
`readiness` posterior devuelve `BLOCKED/NOT_READY` con exit `2`. El primer resultado confirma la
mutación solicitada y el segundo confirma que el hueco quedó fail-closed.

Stdout contiene un único JSON y stderr permanece separado. Ante stdout ausente, truncado o inválido
no se infiere rollback ni se fabrica un envelope. Se observa la verdad SQL y se repite exactamente
el mismo bundle, `publicationId` y hash del manifiesto; para REPLACE/RETIRE también se conservan el
mismo plan, `operationId` y hash del plan. Un retry puede reconciliar como `ALREADY_APPLIED`.

## Persistencia V27 y rol editorial

La operación quedó acreditada sobre PostgreSQL `16.14`, schema fijo `public`, migración
`V27__persistencia_legal_append_only.sql` y checksum Flyway `1575269868`.

Inventario editorial exacto:

- 19 tablas, 130 columnas, 130 constraints y 58 triggers;
- 10 secuencias identity;
- 34 funciones editoriales V27.

Perfil efectivo del rol temporal:

- `SELECT` sobre las 19 tablas y `flyway_schema_history`;
- `INSERT` sobre 7 tablas y `DELETE` sobre 2;
- `UPDATE` limitado a 14 columnas de 6 tablas;
- `USAGE` sobre 4 secuencias, sin `SELECT` ni `UPDATE` de secuencias;
- `EXECUTE` sobre 23 de las 34 funciones editoriales;
- `CONNECT` sólo a la base actual y `USAGE` únicamente sobre `public`;
- `LOGIN NOINHERIT`, `session_replication_role=origin` y `lo_compat_privileges=off`.

El rol no posee objetos, memberships, grant options, `TEMP`, DDL, `CREATE`, `TRUNCATE`,
`REFERENCES`, `TRIGGER`, parámetros amplios, permisos de aplicación, large objects ni funciones
`lo_*`. Un privilegio adicional o `INHERIT` produce `ROLE_PRIVILEGE_DRIFT`. El importador continúa
sin capacidad para promover, reemplazar o retirar.

Las revocaciones a `PUBLIC` tienen impacto global: antes de aplicarlas en una base compartida se
deben inventariar consumidores y preparar regrants nominales. La ventana termina cerrando job y red,
aplicando `NOLOGIN`, drenando o terminando nominalmente sólo las sesiones del rol, verificando cero
sesiones y rotando el secreto. `NOLOGIN` por sí solo no cierra sesiones existentes.

## Concurrencia, fallos y capacidad

La evidencia técnica acumulada de los Cortes 9 y 10 cubre:

- advisory lock compartido con dry-run/import y lectura de estado editorial mutable únicamente
  después del lock; sólo los preflights de schema y privilegios ocurren antes;
- frontera dual `transaction_timestamp()`/`statement_timestamp()` adquirida post-lock;
- carreras concurrentes de promociones idénticas o incompatibles y de REPLACE con predecesor
  compartido, sin doble transición;
- atomicidad multibatch, replay exacto y lifecycle de split/merge y retiro;
- timeout `55P03`, deadlock `40P01`, sesión terminada, ACK perdido reconciliable y commit
  conservadoramente incierto;
- replay exacto por postestado con cero DML y cero avance de secuencias;
- snapshots owner de 25 tablas y 13 secuencias `legal_%` frente al inventario restringido de 19/10.

La fixture importable máxima contiene 128 documentos, 256 requisitos y 16 scopes. La fixture
editorial realizable contiene 87 documentos, 256 requisitos, 16 scopes y 88 slots, con hasta 11
referencias por requisito READY, 2.642 referencias por publicación y 5.284 proyecciones activas.

La corrida fresca 11D, también con delay test-only de 5 ms por viaje, volvió a observar `52/96/184`
viajes JDBC para readiness/plan/apply, `11.636/36.042/68.648` filas y máximos de `2/4/6`
ejecuciones por SQL. Sus tiempos fueron aproximadamente `0,94/1,86/4,43 s`; son observaciones de
esta máquina, no un SLA. La puerta 10F con el mismo delay artificial mantuvo las tres operaciones
debajo de 70 s, cada statement debajo de 30 s y el presupuesto transaccional en 75 s.

## Procesos, stdout y aislamiento

Los procesos JVM empaquetados acreditaron `READY/NOT_READY`, `APPLICABLE/BLOCKED`,
`APPLIED/ALREADY_APPLIED`, retiro `APPLIED+NOT_READY`, roles restringidos y configuración hostil.
Importador, owner, privilegio extra e `INHERIT` fueron rechazados. Las cuatro properties datasource
hostiles fallaron antes de abrir una nueva sesión en la base objetivo.

Las inyecciones test-only de stdout `N=0`, `N=78` y pipe cerrado terminaron con exit `3`, stderr
vacío y commit SQL autoritativo. El proceso fallido no intentó fabricar un segundo envelope; el retry
exacto devolvió su único JSON válido con `ALREADY_APPLIED`. El launcher eliminó
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` y conservó argumentos literales. Los
contextos CLI no activaron web, Flyway, JPA, runners ni schedulers, aunque esas dependencias
permanezcan físicamente dentro de los fat JAR.

## Puerta fresca de cierre 11D

La puerta se ejecutó desde cero con:

```text
Amazon Corretto: 21.0.10
Maven Wrapper:   3.9.11
Flyway:          11.14.1
Testcontainers:  2.0.5
PostgreSQL:      16.14
Migraciones:     27, hasta V27
```

Resultado de `./mvnw clean verify`:

| Suite | Tests | Fallos | Errores | Omitidos |
|---|---:|---:|---:|---:|
| Surefire | 4.283 | 0 | 0 | 0 |
| Failsafe | 250 | 0 | 0 | 0 |

El build terminó `BUILD SUCCESS` en `08:09 min`. Los dos launchers aprobaron `sh -n` y mantienen
modo `100755`.

Hashes SHA-256 frescos:

| Artefacto | Bytes | SHA-256 |
|---|---:|---|
| aplicación | 90.258.270 | `2c218aa7830b680e07ba9dd2a3b0a3056e9cde438433be22dc013828f3b22ab3` |
| legal CLI | 90.258.274 | `2e555169a40867fdc509053a23c62b5b1feed4e62b7cfb7683d2e6013701af06` |
| launcher import | — | `ba6591b51a3de56ef9dd558deb64e8901bb39552d5db7b6a71d21da4aebc6302` |
| launcher editorial | — | `8e0834ad15528f46c9ad195d6de9a7319b53932e6635fe1e3013a8e16029df29` |

El JAR normal declara `MvgrReparacionesBackendApplication` como `Start-Class`; el JAR legal declara
`LegalManifestCli`. Ninguno contiene atributos de agente, `LegalCliStdoutFailureAgent` ni
`application-secret.properties`. Maven no fija `outputTimestamp`: estos hashes identifican esta
ejecución y no sustituyen los hashes históricos de 10F.

Como referencia histórica independiente, 10F había cerrado tres puertas verdes: 4.283 + 10 en
`1:57`, 4.283 + 22 en `2:24` y 4.283 + 250 en `8:28`, todas sin fallos, errores ni omitidos.

## Paridad y estado frontend

Las copias del schema v1 siguen byte-identical:

```text
Backend:  10547 bytes
Frontend: 10547 bytes
SHA-256:  f7a4ee17f53f5ed3f2613d894fa3a4f46896dfaaec0c80dab055e4320f036f8b
cmp:      iguales
```

El subcorte 11A quedó en el commit frontend
`75452010e88a2e2a3252d78b267549d1ee5adce3`, sobre el baseline
`50f9d69fb73aa709f29e83890388ec273414d9dd`. Antes de ese commit aprobaron 42/42 pruebas del guard,
82 archivos con 498 pruebas Vitest y el build local. El 11D confirmó que el HEAD no cambió y no
repitió esa suite. Los únicos no versionados preservados siguen siendo `.agents/` y
`public/OrdenFix project naming/`; no integran commits. Por el comportamiento de Vite, el build
local de 11A copió la segunda carpeta al `dist/OrdenFix project naming/` ignorado. Ese `dist/` no es
un artefacto público aprobado y no debe publicarse ni reutilizarse; un CI limpio no contiene esa
carpeta local.

No se modificaron `src/**`, OpenAPI, queries, mutations, componentes, flags ni scripts de readiness
remoto del frontend.

## Trazabilidad de commits

| Corte | Commits |
|---:|---|
| continuidad 2.3B | `0bf785a` |
| diseño base 2.3C | `d06d92b` |
| plan base 2.3C | `461be8e` |
| 1 | `846d90a` |
| 2 | `aa565b9` |
| 3 | `abb2520`, `842257b` |
| 4 | `7c0efb9` |
| 5 | `aa09d84` |
| 6 diseño/plan | `a7f94df`, `0542910` |
| 6A–6E | `f0875a7`, `99e1504`, `ca28171`, `3570490`, `b4ff4e8` |
| 7 diseño/plan | `ebeb487`, `a73a34b` |
| 7A–7E | `cf0b281`, `e177590`, `a780482`, `8b2c96f`, `1eb5445` |
| 8 diseño/precisión/plan | `5d2c358`, `9991857`, `e0a8fb2` |
| 8A–8G | `2777430`, `ad0e03f`, `1d45e40`, `91113e4`, `2adf754`, `10f9f13`, `94ad11f` |
| 9 diseño/plan | `878a0cf`, `61704d5` |
| 9A–9F | `a05728a`, `34b6ecf`, `78eafa7`, `cc890e9`, `7455aab`, `60f5e54`, `b511530` |
| 10 diseño/plan | `2dcad53`, `73f5a83` |
| 10A | `246ef54`, `69c3763`, `d882c63`, `9f00cb6`, `77c80c5`, `af5b847` |
| 10B | `794c9b2`, `b846965` |
| 10C | `c0f02cc`, `e227a2d`, `dc9b619`, `148f989`, `4e511e5` |
| 10D | `09c0e68`, `9e8148b`, `f528f46`, `23139f8`, `e021d67`, `373bc47`, `5923eec`, `d8f6a97`, `baa188f` |
| 10E | `ab2a917`, `2f5985f`, `bc83eaa`, `0d40ae1`, `0b7f7f5`, `9629a99`, `8e6062b`, `2efc19f`, `d3a08a8` |
| 10F | `ccfe89422539d732bdec479b9f28ba37f092fc37` |
| 11 diseño | `6852c645faea5c7c6885917e0c2a5dbf50ee2b4b` |
| 11 plan | `1c74652c61146709852da92b8880515368dab20d` |
| 11A frontend | `75452010e88a2e2a3252d78b267549d1ee5adce3` |
| 11B backend | `662291535807c950fa950775f6c64003d88668cc` |
| 11C backend | `38cdc41760bfd3999ece07323a0cc50674010529` |
| 11D backend | este commit; parent `38cdc41760bfd3999ece07323a0cc50674010529`; asunto `docs(legal): cierra fase 2.3C` |

La autorreferencia del hash de 11D es imposible dentro de su propio contenido. El SHA real se
obtiene después con `git rev-parse HEAD`; el parent y el asunto permiten verificar la continuidad.

## Estado cross-repo y límites

- Los cambios del Corte 11 son exclusivamente documentales.
- No se modificó V27 ni se creó V28.
- No se importó, promovió, reemplazó ni retiró contenido legal real.
- No se creó seed, controller, endpoint, catálogo, aceptación, enforcement ni integración frontend.
- No se abrió una conexión a staging o producción.
- No se hizo push ni deploy.
- Readiness editorial `READY` sólo describe el grafo V27 interno; no significa readiness pública.

## Campos operativos editables pendientes

Estos valores deben resolverse fuera de Git antes de cualquier operación real. Su ausencia no
invalida las pruebas técnicas, pero bloquea una ventana operativa:

| Campo | Estado |
|---|---|
| entorno y base exactos | `POR DEFINIR` |
| custodio del release y receipts | `POR DEFINIR` |
| secrets manager y política de rotación | `POR DEFINIR` |
| red/allowlist y mecanismo de cierre | `POR DEFINIR` |
| ventana, timeout y plan de abortar | `POR DEFINIR` |
| job runner y hash del artefacto aprobado | `POR DEFINIR` |
| operador titular y suplente | `POR DEFINIR` |
| aprobadores editorial/técnico | `POR DEFINIR` |
| ticket, evidencia y retención de logs | `POR DEFINIR` |

## Dependencias posteriores

El orden de trabajo que sigue es:

1. diseñar e implementar V28 para revisión agregada multicontexto;
2. implementar catálogo, documentos, requisitos, ETag y endpoints autenticados;
3. implementar aceptación y registro atómicos;
4. aplicar seguridad, CORS, `409`, `428` y enforcement compatible;
5. obtener revisión profesional o aprobación editorial responsable y trazable del contenido
   definitivo;
6. desplegar y migrar las capas compatibles en staging, importar/promover allí y validar readiness
   pública y smokes remotos;
7. habilitar y acreditar `BACKEND-HANDOFF 1`;
8. conectar la Tarea 3 frontend y ejecutar E2E;
9. decidir separadamente la activación productiva y la reaceptación de cuentas legacy.

Hasta completar y verificar esas capas, 2.3C permanece una capacidad editorial interna y no una
funcionalidad pública de la aplicación.
