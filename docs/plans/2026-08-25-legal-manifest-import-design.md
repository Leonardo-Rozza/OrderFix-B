# Fase 2.3B — Importación idempotente y sello transaccional legal

Fecha: 2026-08-25

Estado: diseño aprobado

Continuidad:

- `docs/plans/2026-08-23-legal-api-contract-v1-design.md`;
- `docs/plans/2026-08-23-legal-persistence-append-only-design.md`;
- `docs/plans/2026-08-24-legal-manifest-dry-run-design.md`;
- `docs/plans/2026-08-25-legal-manifest-dry-run-closure.md`.

## Decisión de producto

La importación de contratos globales de OrdenFix es una operación exclusiva de plataforma. No se
expone a un `ADMIN` o `USER` de taller, no posee endpoint HTTP y no aparece en la aplicación. Se
ejecuta manualmente o desde un job controlado con credenciales operativas propias.

El usuario aprobó además:

- ampliar el artefacto legal aislado con un comando `import`;
- exigir confirmaciones explícitas de `publicationId` y SHA canónico;
- persistir y sellar en una sola transacción;
- resolver replays por identidad natural, sin usar el ledger HTTP;
- conservar promoción, readiness, APIs y enforcement fuera de 2.3B;
- usar una credencial PostgreSQL incapaz de promover contenido;
- devolver un reporte v2 que pueda representar un commit indeterminado.

## Objetivo

Confirmar un `ValidatedRelease` en PostgreSQL V27 de forma idempotente, auditable y segura ante
concurrencia. Un éxito crea o reconcilia una publicación `SELLADO`, con sus documentos, requisitos,
membresías y snapshots completos. Las versiones nuevas continúan en `BORRADOR`.

La fase no vuelve vigente ningún documento o requisito. El sello es técnico: prueba que el agregado
quedó completo e inmutable, pero no equivale a aprobación editorial pública, firma criptográfica,
promoción ni readiness.

## Alternativas evaluadas

### A. Ampliar `legal-cli.jar` con `import` — elegida

Reutiliza validación, aislamiento y packaging ya acreditados. El comando mutante queda protegido por
confirmaciones ligadas al contenido, un flag operativo y una credencial DB restringida.

Ventajas:

- un único artefacto y una única cadena de validación;
- regresión directa contra `validate` y `dry-run`;
- menor duplicación de configuración y pruebas;
- operación reproducible desde CI o terminal controlada.

### B. Segundo jar exclusivo de importación — descartada

Separar el artefacto reduce la posibilidad de invocar accidentalmente el comando, pero duplica main
class, repackage, inspecciones y configuración. No reemplaza confirmación, idempotencia ni permisos
DB, que son las barreras efectivas.

### C. `prepare-import` más recibo firmado — descartada por ahora

Un primer comando podría emitir un receipt ligado a bundle y base, y un segundo consumirlo. Es una
barrera adicional útil para operaciones frecuentes, pero exige expiración, firma, keyring o estado
temporal. Para importaciones editoriales infrecuentes agrega complejidad sin necesidad actual.

## Alcance

Incluye:

- comando interno `import` en el jar legal existente;
- confirmación exacta de ID externo y SHA-256 JCS;
- cálculo productivo de `required_set_revision` por scope;
- escritor del grafo compartido por dry-run e import;
- servicio de importación separado, nunca un booleano `commit=true`;
- idempotencia por publicación externa y contenido canónico;
- serialización cooperativa entre dry-run e import;
- sello V27, constraints inmediatas y commit real;
- reporte v2 de importación y receipt seguro;
- contexto no web y perfil de permisos DB mínimo;
- pruebas unitarias, PostgreSQL, concurrencia y procesos JVM reales;
- documentación operativa y coordinación cross-repo.

No incluye:

- promoción `BORRADOR -> PUBLICADA -> VIGENTE`;
- reemplazo, retiro, lotes editoriales o punteros actuales;
- `documentSetRevision`, catálogo público o readiness;
- endpoints, DTOs HTTP, seguridad web, CORS, ETag o caché;
- aceptaciones, registro compatible, idempotencia HTTP, `428` o enforcement;
- UI o queries reales desde el frontend;
- importación de borradores editoriales actuales en una base real;
- deploy o push.

`BACKEND-HANDOFF 1` continúa cerrado al terminar 2.3B.

## Arquitectura

### Núcleo de validación único

`import` consume exclusivamente el mismo token opaco `ValidatedRelease` emitido por
`LegalManifestValidator`. No existe otro parser, DTO construible o camino de validación más
permisivo. El writer usa los snapshots inmutables ya cargados por el validador y nunca reabre las
rutas del bundle.

### Gate transaccional compartido

El protocolo previo a cualquier lectura o escritura del grafo vive en un coordinador neutral, por
ejemplo `LegalManifestDatabaseGate`. El gate:

- configura los presupuestos de transacción, sentencia y locks;
- acredita el inventario V27 mediante `LegalV27ImportSchemaVerifier`;
- acredita el perfil efectivo mediante `LegalImportPrivilegeVerifier` cuando el comando es
  `import`;
- adquiere el advisory transaction lock editorial antes de leer identidades legales;
- ejecuta bajo ese lock el callback del servicio y deja que la frontera transaccional decida commit
  o rollback.

El gate no decide replay ni construye publicaciones. `LegalManifestImportService` entra al gate,
consulta la identidad natural y resuelve allí mismo entre replay, conflicto o creación. Sólo la rama
de creación llama al writer. De esta manera, un replay nunca necesita ejecutar un componente que
inserta o avanza secuencias.

### Addendum de frontera transaccional — Corte 3

La auditoría contra Spring Framework 7.0.7 corrigió una precisión del diseño antes de habilitar el
importador. `TransactionSynchronization.beforeCommit` acredita que Spring ingresó en el camino de
commit, pero ocurre antes de `Connection.commit()` y por lo tanto no demuestra que ese método haya
sido invocado. Se conserva como una frontera conservadora, nunca como una confirmación.

El contexto de importación debe construir exactamente un `DataSourceTransactionManager`, con
`rollbackOnCommitFailure=false`; no puede usar `JdbcTransactionManager`. Este último puede traducir
un `SQLException` de commit a `DataAccessException`, provocar un rollback posterior y terminar con
`STATUS_ROLLED_BACK` aunque el servidor ya hubiera confirmado. La configuración se valida antes de
leer el agregado y el Corte 4 debe congelarla en su contexto restringido.

La frontera tampoco puede participar de una transacción exterior: debe usar exactamente
`PROPAGATION_REQUIRES_NEW`, `READ_COMMITTED`, timeout total productivo y `readOnly=false`. Gate,
servicio, writer y replay verifier deben compartir la misma instancia de `JdbcTemplate` y el mismo
`DataSource` administrado por ese transaction manager; de otro modo una operación podría escapar de
la completion observada. En el Corte 4 la misma acreditación se extiende a los preflights reales de
schema y privilegios.

La certeza final queda definida así:

- retorno normal del transaction manager o `STATUS_COMMITTED`, con receipt interno completo:
  `persisted=true`;
- `STATUS_ROLLED_BACK` bajo la frontera acreditada: `persisted=false`;
- cualquier `STATUS_UNKNOWN`, o una excepción después de entrar al camino de commit sin completion
  autoritativa: `persisted=null/outcome=UNKNOWN`;
- un fallo anterior al callback protegido: `persisted=false`, porque los preflights y locks no
  escriben el agregado.

La regla de `STATUS_UNKNOWN` también cubre fallos de rollback. Restaurar `autoCommit` después de una
conexión dañada puede dejar el resultado indeterminado; por eso el importador nunca degrada esa
señal a un rollback conocido ni expone el receipt tentativo.

El verificador de importación es más estricto que la comprobación provisional de 2.3A. Congela como
fixture versionado:

- las 12 tablas del agregado importable y las seis secuencias identity que usan;
- la fila exitosa y el checksum esperado de V27 en `flyway_schema_history`;
- firmas y definiciones de `legal_validar_publicacion_sellada(uuid)` y de los guards alcanzados por
  inserts, update de sello y constraints diferibles;
- nombre, tabla, función, habilitación y modalidad de cada trigger aplicable a esas 12 tablas;
- PK, UK, CHECK, FK y constraints diferibles, incluida su definición y deferibilidad;
- ownership de las secuencias y su vínculo con las columnas identity.

No se ejecuta `Flyway.validate`, pero tampoco se confía sólo en que exista una función o una tabla.
Una divergencia de ese inventario produce un error específico de importación antes de leer el grafo.

### Escritor compartido

La lógica JDBC hoy encerrada en `LegalDryRunPersistence` se extrae a un componente neutral, por
ejemplo `LegalManifestGraphWriter`. Recibe una publicación inexistente ya resuelta bajo el gate y:

- resuelve líneas/versiones nuevas o reutilizadas;
- bloquea y relee identidades históricas;
- asigna UUID y ordinales definitivos;
- calcula las revisiones de scope mediante una estrategia explícita;
- inserta publicación, documentos, requisitos, membresías y snapshots;
- ejecuta `ABIERTO -> SELLADO`;
- fuerza constraints y llama explícitamente a `legal_validar_publicacion_sellada(uuid)`;
- devuelve un recibo interno sin decidir commit o rollback.

El writer no configura el gate, no verifica permisos, no resuelve replays y no captura una excepción
de persistencia para convertirla en resultado dentro de la transacción: PostgreSQL podría haber
abortado la sesión transaccional. La frontera de cada servicio traduce el fallo después del rollback
o del intento de commit.

### Servicios separados

- `LegalManifestDryRunService`: writer con revisión acreditada y `rollback-only` obligatorio.
- `LegalManifestImportService`: idempotencia, writer con revisión final y commit.

No comparten una bandera mutante. El compilador y la configuración Spring hacen explícita la
diferencia entre simular y confirmar.

### Contexto operativo aislado

`import` abre una fuente Spring no web específica. No escanea la aplicación principal ni activa:

- MVC/servlet o servidor HTTP;
- JPA/Hibernate;
- Flyway;
- `DataLoader` o migraciones legacy;
- schedulers, runners o integraciones Mercado Pago;
- logging configurable por el host.

La base ya debe estar migrada con V27. `import` usa un resolver de secretos nuevo y separado del
resolver compatible de `dry-run`. Ignora y rechaza credenciales datasource recibidas mediante
system properties JVM —incluido `-Dspring.datasource.password`— y sólo copia al contexto hijo estas
variables específicas:

- `ORDENFIX_LEGAL_IMPORT_DB_URL`;
- `ORDENFIX_LEGAL_IMPORT_DB_USERNAME`;
- `ORDENFIX_LEGAL_IMPORT_DB_PASSWORD`;
- `ORDENFIX_LEGAL_IMPORT_DB_DRIVER_CLASS_NAME`, opcional.

El valor del password puede provenir del mecanismo de secretos del job, pero nunca de argumentos ni
propiedades visibles en la línea de proceso. La compatibilidad actual de `validate`/`dry-run` se
mantiene en su camino separado.

## Comando y confirmación

Forma objetivo:

```bash
ORDENFIX_LEGAL_IMPORT_ENABLED=true \
ORDENFIX_LEGAL_IMPORT_DB_URL=jdbc:postgresql://host:5432/database \
ORDENFIX_LEGAL_IMPORT_DB_USERNAME=ordenfix_legal_importer \
ORDENFIX_LEGAL_IMPORT_DB_PASSWORD=... \
java -jar target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar \
  import \
  --manifest=/ruta/release/publication-manifest.json \
  --confirm-publication-id=release-2026-08-25 \
  --confirm-manifest-sha256=<64-hex-jcs>
```

El parser exige exactamente una instancia de cada argumento, sin alias, extras, duplicados,
password, propiedades Spring ni `--force`. No hay prompt interactivo. Las confirmaciones no son un
mecanismo de autorización; evitan apuntar al bundle equivocado.

Los tres argumentos con nombre pueden aparecer en cualquier orden después de `import`. El orden no
posee semántica: sólo importan el nombre exacto, una única aparición y un valor no vacío que cumpla
su contrato. No se aceptan formas separadas (`--manifest ruta`) ni abreviaturas.

El orden operativo es:

1. parsear los argumentos sin revelar sus valores;
2. validar estáticamente el bundle completo;
3. comparar el ID y SHA confirmados con el `ValidatedRelease`;
4. verificar `ORDENFIX_LEGAL_IMPORT_ENABLED=true`;
5. recién entonces abrir PostgreSQL.

Una confirmación incorrecta produce `BLOCKED` antes de consultar configuración DB.

## Perfil PostgreSQL mínimo

La autorización efectiva usa una cuenta separada de la aplicación y del owner de migraciones. Se
configura mediante un runbook operativo, no mediante Flyway, porque usuarios y secretos pertenecen
al entorno.

La matriz positiva se limita a:

- `CONNECT` sobre la base y `USAGE`, pero no `CREATE`, sobre el schema esperado;
- `SELECT` sobre `flyway_schema_history` y sobre estas 12 tablas:
  `legal_publicaciones`, `legal_documento_lineas`, `legal_documento_versiones`,
  `legal_documento_contextos`, `legal_publicacion_documentos`, `legal_requisito_lineas`,
  `legal_requisito_audiencias`, `legal_requisito_versiones`, `legal_requisito_documentos`,
  `legal_publicacion_requisitos`, `legal_requisito_conjuntos` y
  `legal_requisito_conjunto_miembros`;
- `INSERT` sobre esas mismas 12 tablas;
- `USAGE`, sin `SELECT`, `UPDATE` ni ownership, sobre las seis secuencias identity de contextos,
  membresías documentales, audiencias, vínculos requisito-documento, membresías de publicación y
  miembros de conjuntos;
- `UPDATE (estado_construccion, sellado_en)` a nivel columna sobre `legal_publicaciones`, sin
  `UPDATE` de tabla ni de otra columna;
- `UPDATE (id)` a nivel columna sobre `legal_documento_lineas`, `legal_documento_versiones`,
  `legal_requisito_lineas` y `legal_requisito_versiones`, mínimo requerido por PostgreSQL para
  ejecutar los `SELECT ... FOR UPDATE/FOR KEY SHARE` del protocolo; los triggers V27 rechazan todo
  update directo, incluso `SET id=id`;
- `EXECUTE` sobre una allowlist versionada formada por `legal_validar_publicacion_sellada(uuid)` y
  el call graph mínimo de guards/helpers que ejecutan los triggers y constraints de esas 12 tablas;
  el SQL del comando sólo invoca directamente la función de validación.

La matriz negativa incluye cualquier `DELETE`, `TRUNCATE`, `REFERENCES`, `TRIGGER`, DDL o privilegio
de escritura fuera de esos grants de columna. El verifier exige que no exista `UPDATE` de tabla ni
de otra columna, y los tests prueban que los cuatro grants técnicos de lock no permiten mutar una
fila. En particular, la cuenta no puede:

- actualizar estados de versiones documentales o de requisito;
- insertar transiciones;
- escribir slots documentales o `legal_requisito_conjuntos_actuales`;
- crear/sellar lotes de reemplazo;
- insertar aceptaciones, metadata o idempotencia HTTP;
- borrar filas legales.

El runbook revoca de `PUBLIC`, mediante una lista versionada de firmas V27, el `EXECUTE` implícito de
las funciones legales y concede al importador sólo la allowlist de importación. Quedan fuera todas
las funciones de transición, slots actuales, reemplazos, aceptaciones e idempotencia. Si otro rol
operativo necesita una función, su grant se documenta de forma independiente; no se hereda a través
del importador.

`LegalImportPrivilegeVerifier` evalúa privilegios efectivos, no sólo grants nominales. Compara
`session_user` y `current_user`, exige que sean iguales y que ambos identifiquen la cuenta esperada;
exige además `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`,
`NOREPLICATION` y `NOBYPASSRLS`; rechaza membresía directa o indirecta en roles owner/amplios; y
comprueba que la cuenta no sea owner de la base, schema, tablas, secuencias o funciones. También
acredita la matriz positiva y la ausencia completa de la matriz negativa. Una cuenta demasiado
amplia falla cerrada. Los tests crean un rol no owner con el mismo perfil del runbook.

V27 no almacena el operador que ejecutó la importación. La auditabilidad interna acredita contenido,
digest y tiempos. El runbook exige conservar fuera del grafo el identificador del job/operador, el
SHA confirmado, el receipt y logs PostgreSQL sanitizados; no se agregan columnas ni secretos a 2.3B.

## Flujo transaccional

Después de la validación estática y de confirmaciones:

1. abrir una transacción `REQUIRES_NEW`, `READ_COMMITTED`, con timeout total de 75 s;
2. fijar `statement_timeout=30s` y acreditar schema; para `import`, acreditar además privilegios;
3. fijar temporalmente `lock_timeout=30s` y tomar el advisory transaction lock global ya usado por
   el sello V27, `ordenfix:legal-publicaciones:sello:v1`;
4. una vez adquirido, reducir `lock_timeout=5s` para los locks de filas e índices restantes;
5. consultar `publication_external_id` bajo lock;
6. resolver replay o continuar la construcción;
7. descubrir y bloquear versiones antes de líneas, siguiendo el orden acreditado en 2.3A;
8. releer identidades y dependencias externas selladas;
9. asignar UUID/ordinales y calcular revisiones definitivas;
10. insertar el grafo completo;
11. actualizar la publicación a `SELLADO`;
12. ejecutar `SET CONSTRAINTS ALL IMMEDIATE`;
13. llamar `legal_validar_publicacion_sellada(id)`;
14. releer las versiones creadas por este intento y comprobar que siguen `BORRADOR`;
15. devolver el receipt interno y permitir el commit.

El importador no recibe `SELECT` sobre transiciones ni punteros sólo para demostrar un negativo que
su propio rol no puede escribir. Esa ausencia se acredita por la API del writer, la matriz de
privilegios y tests con un observer privilegiado, no mediante una consulta runtime más amplia.

El presupuesto de 30 s del advisory lock es deliberadamente distinto de los 5 s para locks del
grafo. El dry-run adquiere el mismo lock antes de leer o insertar. Esto serializa a los dry-runs e
imports que respetan el protocolo cooperativo y evita sus carreras. No se afirma que pueda prevenir
un ciclo creado por un writer ajeno al gate: un timeout o deadlock provocado por ese writer se
revierte y se mapea a `ERROR`, sin presentar éxito ni dejar escrituras nuevas parciales.

En producción, `statement_timeout` y el presupuesto temporal del advisory lock valen ambos 30 s.
Si expiran en el mismo borde, PostgreSQL puede informar timeout de sentencia o de lock; ambos son
resultados operativos seguros y revierten la transacción completa. La evidencia determinista del
timeout específico de lock usa únicamente en tests presupuestos reducidos `statement=2s/lock=1s`,
sin modificar los valores productivos.

## Idempotencia y replay

No se usa `legal_idempotencia_resultados`: V27 la limita a `REGISTRO` y `ACEPTACION_LEGAL`, exige
actor/lote y protege operaciones HTTP distintas.

La identidad natural de importación es `publication_external_id`:

- inexistente: construir, sellar y devolver `IMPORTED`;
- existente `SELLADO`, con cabecera inmutable, SHA y JSON canónico exactos: revalidar el grafo
  importado y devolver `ALREADY_IMPORTED`;
- existente con SHA, JSON o metadata diferente: `BLOCKED/IMPORT_DB_PERSISTED_CONFLICT`;
- existente `ABIERTO`: `ERROR`; no completar, reabrir ni eliminar automáticamente.

El replay exitoso no escribe filas, no ejecuta el writer, no avanza secuencias y devuelve el mismo
UUID, `importado_en` y `sellado_en`. Además de comparar cabecera, invoca la validación V27 y contrasta
el grafo inmutable reconstruido con el release, para no tratar una fila sellada corrupta como éxito.
La comparación incluye sólo cabecera y nodos derivados del manifiesto: ignora transiciones, slots o
punteros actuales y no exige que las versiones continúen `BORRADOR`. Por ello, después de 2.3C una
publicación ya promovida, reemplazada o retirada continúa siendo `ALREADY_IMPORTED` si su grafo de
origen permanece exacto.

Dos imports idénticos concurrentes, si ambos obtienen el advisory lock dentro de su presupuesto,
producen un `IMPORTED` y un `ALREADY_IMPORTED`. Si el segundo agota la espera, devuelve un `ERROR`
seguro y la repetición exacta reconcilia como `ALREADY_IMPORTED`; no se promete espera ilimitada.
Dos publicaciones distintas con keys nuevas iguales se serializan; la segunda relee y reutiliza
versiones exactas o bloquea metadata incompatible. Los índices únicos continúan como barrera final
frente a writers que no respeten el protocolo cooperativo, cuyos locks pueden causar un error seguro
pero nunca se reinterpretan como éxito.

No hay retry automático de locks, conexiones o commits. El operador repite exactamente el mismo
comando; la identidad natural reconcilia el resultado.

## Revisión definitiva de requisitos

El dominio provisional `ordenfix:legal-dry-run-scope:v1` nunca se confirma. V27 vuelve
`legal_requisito_conjuntos` inmutable, por lo que la revisión productiva se calcula después de
resolver UUID definitivos y antes del sello.

Cada snapshot por `(publicación, locale, contexto, audiencia)` guarda la revisión del conjunto
completo del scope, no la lista de pendientes particular de un actor. La proyección prospectiva se
congela como este DTO JSON, omitiendo sólo `requiredSetRevision`:

```text
{
  contexto,
  locale,
  requisitos: [{
    id, contexto, tipoActo, afirmacion, afirmacionSha256,
    documentos: [{
      id, tipo, version, titulo, contenidoMarkdown, sha256,
      vigenteDesde, estado, locale
    }],
    requerido
  }]
}
```

Las propiedades conservan ese orden antes de RFC 8785. Los requisitos se ordenan por
`legal_publicacion_requisitos.manifest_ordinal` y los documentos de cada requisito por
`legal_requisito_documentos.documento_ordinal`; todos los `id` son UUID definitivos. `estado` se
proyecta siempre como `VIGENTE`, aunque la fila importada continúe `BORRADOR`, porque ésta es la
respuesta que el conjunto produciría al ser promovido. `vigenteDesde` se normaliza a UTC con sufijo
`Z` y precisión máxima de microsegundos, igual que PostgreSQL.

El resultado se canonicaliza con RFC 8785, se hashea sobre los bytes UTF-8 con SHA-256 y recibe el
prefijo `sha256:`. Un vector golden congela la estructura exacta y una prueba reconstruye esa misma
proyección desde las filas importadas para volver a obtener la revisión.

La proyección tipada sólo contiene strings, booleanos, arrays y claves de objeto fijas. Su camino
productivo emite directamente el orden canónico RFC 8785 hacia SHA-256, sin construir un árbol JSON,
un documento completo intermedio ni copias de los bytes canónicos. El parser de manifiestos externos
continúa usando la librería JCS después de `StrictJsonReader`; no aparece una entrada genérica nueva.
El writer tipado se acredita byte a byte contra esa librería con comillas, barras, controles,
Unicode astral y el vector golden.

### Addendum de capacidad previo al Corte 1

La auditoría de implementación detectó que el límite de 16 MiB sobre fuentes Markdown únicas no
acotaba el tamaño de la respuesta expandida: hasta 256 requisitos podían repetir el contenido de un
mismo documento en sus arrays y llevar la proyección a varios GiB antes de JCS.

Se congela por eso una segunda aplicación del mismo presupuesto de 16 MiB, esta vez por scope y
sobre la suma UTF-8 de cada aparición de `contenidoMarkdown`. Un documento compartido cuenta una
vez por cada requisito que lo proyecta, porque el DTO prospectivo repite efectivamente ese contenido.
Exactamente 16 MiB es válido; superarlo bloquea el manifiesto con
`DOCUMENT_TOTAL_SIZE_LIMIT_EXCEEDED` en `scopes/{CONTEXTO}/{AUDIENCIA}`.

El contrato estático acredita este límite antes de construir la proyección o abrir PostgreSQL. El
calculator lo vuelve a comprobar, sin crear copias UTF-8, como defensa de la frontera tipada. También
reacredita los máximos de requisitos y documentos por requisito, y los records tipados acotan
afirmación, título, versión y digests con los máximos ya congelados por schema/Markdown. Las
cantidades se comprueban antes de ejecutar las copias defensivas de listas. No cambia el JSON ni el
digest de ningún release aceptado: sólo rechaza una respuesta que no podría procesarse con el
presupuesto operativo acordado.

El contrato previo describe `RequisitosLegalesPendientesResponse` como posiblemente multicontexto y
actor-específico, pero V27 guarda una revisión por scope y su trigger de aceptación compara una única
revisión de lote contra cada scope. Esta incompatibilidad no afecta importación ni sello, pero impide
habilitar aceptaciones multicontexto con V27 sin cambios.

Antes de las APIs se requiere V28 para validar una revisión agregada estable de todos los scopes
aplicables. Esa revisión se calculará sobre conjuntos completos; el filtrado de pendientes no la
cambiará. Los documentos de contrato frontend/backend se corrigen de manera coordinada, pero 2.3B
no implementa todavía V28 ni endpoints.

## Reporte v2

Los reportes v1 de `validate` y `dry-run` permanecen byte-compatible. Sólo `import` emite v2, con
orden estable:

```text
reportVersion, command, status, persisted, publication, counts, dryRun, import, issues,
omittedIssueCount
```

`publication` conserva exactamente `publicationId, schemaVersion, manifestSha256`; `counts`
conserva `documents, requirements, scopes`. Ambos son no nulos desde que existe un
`ValidatedRelease`, aun si luego falla una confirmación o PostgreSQL. Son `null` en fallos de
argumentos/validación estática que no producen ese token y pueden ser `null` en el último boundary
de emergencia. `dryRun` es siempre `null`.

Cuando no es `null`, `import` contiene todas estas propiedades, también en `UNKNOWN`, en este orden:

- `outcome`: `IMPORTED`, `ALREADY_IMPORTED` o `UNKNOWN`;
- `publicationUuid`;
- `importedAt` y `sealedAt` en UTC;
- `sealed`: `true` o `null`;
- `promotionChanged`: siempre `false`, porque describe lo hecho por este comando y no el estado
  editorial actual.

`importedAt` y `sealedAt` se serializan en RFC 3339 UTC con sufijo `Z`, segundos obligatorios y entre
cero y seis dígitos fraccionarios. Se usa el `Instant` releído de PostgreSQL, cuya precisión máxima
es microsegundos; nunca se emite un offset alternativo ni una precisión inventada.

Semántica de `persisted`:

- `true`: el resultado sellado existe, creado ahora o acreditado por replay;
- `false`: el transaction manager confirmó rollback, o el callback protegido nunca comenzó;
- `null`: la finalización quedó indeterminada tras iniciar el callback, o se alcanzó la frontera de
  commit sin una completion autoritativa.

Las únicas combinaciones válidas son:

| `status` | `persisted` | `import` | Exit |
|---|---:|---|---:|
| `PASS` | `true` | receipt completo `IMPORTED` o `ALREADY_IMPORTED`; UUID/timestamps no nulos, `sealed=true`, `promotionChanged=false` | `0` |
| `BLOCKED` | `false` | `null` | `2` |
| `ERROR` conocido | `false` | `null` | `3` |
| `ERROR` indeterminado | `null` | `UNKNOWN`; UUID/timestamps/`sealed` nulos y `promotionChanged=false` | `3` |

No se omite ninguna propiedad para representar `null`. Un `UNKNOWN` no afirma UUID ni timestamps,
aunque el callback los hubiera calculado, porque el commit no quedó acreditado. Si falla stdout
después de un commit, el proceso puede terminar con exit `3` sin un envelope confiable; repetir el
comando es el mecanismo de reconciliación.

La CLI conserva hasta el último boundary un estado monótono de ejecución: comando crudo reconocido,
DB abierta, callback iniciado, callback entregó receipt, frontera `beforeCommit` alcanzada y commit
confirmado. Desde que `rawArguments[0]` reconoce exactamente `import`, todo catch externo selecciona
formato v2: un rollback confirmado o un fallo anterior al callback emite
`ERROR/persisted=false/import:null`; una completion `UNKNOWN`, o la frontera de commit sin completion,
emite `ERROR/persisted=null/UNKNOWN`; después de commit confirmado conserva el receipt y
`persisted=true`. Nunca vuelve al `EMERGENCY_REPORT` v1. Si incluso el serializador v2 mínimo o stdout
fallan, no se inventa otro envelope.

Exit codes:

- `0`: `IMPORTED` o `ALREADY_IMPORTED`;
- `2`: validación, confirmación o conflicto bloqueante;
- `3`: configuración, schema, privilegios, locks, conexión o commit incierto.

El receipt sólo expone metadata operativa segura. Nunca incluye contenido legal, argumentos, rutas
absolutas, SQL, stack traces, constraints desconocidas, usuario DB ni credenciales. Se mantienen el
límite de 200 issues y `omittedIssueCount`.

## Manejo de fallos

- Fallo estático o confirmación distinta: `BLOCKED`, `persisted=false`, sin DB.
- Conflicto persistido: `BLOCKED`, `persisted=false`, rollback.
- V27 o privilegios incompatibles: `ERROR`, `persisted=false`, sin escrituras.
- Lock/timeout/fallo dentro del callback con rollback confirmado: `ERROR`, `persisted=false`.
- Excepción después de que el callback entregó el receipt y alcanzó la frontera de commit, sin
  completion autoritativa, o cualquier `STATUS_UNKNOWN` tras iniciar el callback:
  `ERROR`, `persisted=null`, `outcome=UNKNOWN`.
- Fallo de serialización o cierre de contexto después de commit confirmado: conservar
  `PASS/persisted=true` y el receipt; nunca degradarlo a `false`.
- Publicación previa `ABIERTO`: `ERROR`, sin reparación automática.
- Fallo de stdout: exit `3`; no existe un segundo canal seguro.

Los códigos DB v1 y sus mensajes que prometen “simulación/dry-run” no se modifican. `import` agrega
su propio mapping v2 y códigos constantes, como mínimo para confirmación, flag deshabilitado,
configuración, schema, privilegios, conflicto persistido, publicación abierta, lock/timeout,
conexión, commit indeterminado y fallo interno. Los fallos estáticos comunes pueden conservar sus
códigos actuales; ningún mensaje de importación afirma rollback o ausencia de persistencia si no se
pudo acreditarlos.

El clasificador recibe la fase transaccional y el resultado de rollback/commit; no infiere
`persisted` sólo por el tipo Java o SQLState. No hay retry automático dentro de la misma ejecución.

La JVM procesa `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` y `_JAVA_OPTIONS` antes de `main`; el launcher
debe eliminarlas o controlarlas. Ese límite pre-main continúa explícitamente fuera de la garantía de
redacción del CLI.

## Estrategia de pruebas

### Unitarias

- argumentos exactos, duplicados, extras, confirmaciones y flag operativo;
- reporte v2, orden, matriz de estados, campos nulos, timestamps, receipt y redacción;
- revisión RFC 8785 con vector golden, orden y UUID definitivos;
- gate separado del writer y replay que demuestra cero invocaciones al writer;
- clasificación de fallos pre-callback, callback, rollback, commit indeterminado y post-commit;
- transaction manager instrumentado que acredita `UNKNOWN` sin agregar un switch de fallo al jar;
- ningún catch desde que el argumento crudo reconoce `import` puede emitir v1 ni inferir
  `persisted` sin la fase;
- regresión byte a byte del reporte v1.

### PostgreSQL/Testcontainers

- import fresco confirma las 12 tablas y deja publicación `SELLADO`;
- todas las versiones nuevas permanecen `BORRADOR`;
- cero transiciones, promociones, punteros actuales, aceptaciones e idempotencia HTTP, comprobados
  por un observer privilegiado de test;
- replay exacto produce cero delta y cero avance de secuencias;
- replay exacto después de simular promoción/reemplazo/retiro ignora estado editorial mutable;
- mismo ID con otro contenido bloquea;
- publicación previa abierta falla cerrado;
- constraint diferible, timeout o desconexión antes del commit revierten todo;
- revisión almacenada se reconstruye desde PostgreSQL;
- grafo sellado alterado artificialmente no pasa como replay;
- el verifier rechaza drift en tabla, secuencia, checksum V27, función, trigger o constraint crítico;
- el rol importador puede importar pero no promover, retirar, aceptar, borrar, ejecutar funciones
  legales ajenas ni heredar privilegios efectivos amplios;
- los grants `UPDATE(id)` permiten los row locks necesarios, pero un update directo real o no-op
  falla por los triggers V27 y no deja delta.

### Concurrencia

- dos imports idénticos dentro del presupuesto: uno crea y el otro hace replay;
- agotamiento del presupuesto del segundo: error seguro y repetición exacta posterior como replay;
- publicaciones distintas con identidades compatibles: ambas sellan y la segunda reutiliza;
- identidades incompatibles: como máximo una confirma;
- dry-run concurrente con import cooperativo no forma ciclos ni observa un grafo parcial;
- writer no cooperativo que fuerza timeout/deadlock se revierte y se mapea sin falso éxito;
- pérdida de certeza en commit seguida por replay exacto reconcilia el resultado.

### Procesos y regresión

- jar real `import` con `IMPORTED`, `ALREADY_IMPORTED`, `BLOCKED` y errores conocidos;
- frontera `UNKNOWN` cubierta con transaction manager instrumentado y, en proceso real, simulación
  externa de pérdida de stdout después de commit seguida por replay; no existe flag oculto de fallo;
- contexto sin Flyway, web, JPA, runners o schedulers;
- system properties datasource rechazadas por `import` y secretos ausentes de argumentos/reportes;
- ambos jars continúan sin `application-secret.properties`;
- `./mvnw verify` completo;
- guard y build frontend;
- `git diff --check` y estado de ambos repositorios.

## Cortes de implementación

1. revisión definitiva por scope y vectores golden;
2. extracción del gate y writer compartidos, sin cambio observable del dry-run;
3. servicio de importación idempotente y frontera de commit incierto;
4. contexto DB restringido, verificador de permisos y runbook;
5. comando `import`, confirmaciones y reporte v2;
6. concurrencia, procesos reales y hardening;
7. documentación cross-repo, regresión completa y cierre.

Cada corte usa commits locales atómicos. No se hace push. El frontend conserva sus archivos ajenos
no versionados.

## Puerta de salida

2.3B sólo cierra si:

- import fresco, replay y conflicto cumplen el contrato;
- ningún intento nuevo de importación deja una publicación parcial abierta; una fila `ABIERTO`
  preexistente se detecta y requiere intervención manual;
- `UNKNOWN` nunca se presenta como éxito o rollback confirmado;
- la revisión productiva es reproducible desde DB;
- dry-run e import comparten gate, writer y lock sin cambiar la salida v1;
- la credencial importadora no puede promover;
- no aparecen transiciones, punteros, aceptaciones ni ledger HTTP;
- todas las pruebas enfocadas y `./mvnw verify` pasan;
- la documentación cross-repo distingue sello de promoción;
- no se importó contenido real, no hubo deploy y no hubo push;
- `BACKEND-HANDOFF 1` continúa no disponible.

## Siguiente fase

- **2.3C:** promoción/retiro y readiness editorial, todavía sin enforcement.
- **Antes de APIs de aceptación:** V28 para revisión agregada multicontexto.
- **2.4+:** APIs legales, aceptación, registro compatible e integración frontend en fases separadas.
