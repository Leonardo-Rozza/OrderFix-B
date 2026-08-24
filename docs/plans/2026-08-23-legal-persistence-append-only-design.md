# Fase 2.2 — Persistencia legal relacional append-only

Fecha: 2026-08-23

Estado: diseño aprobado; implementación pendiente

Alcance: Flyway, entidades, repositorios e invariantes PostgreSQL; sin controllers, servicios HTTP,
importador operativo, seed, contenido legal ni enforcement

## Objetivo

Persistir el contrato legal v1 congelado en la Fase 2.1 sin convertir JSON del navegador en fuente
de verdad. El modelo debe permitir publicar versiones inmutables, calcular requisitos vigentes,
reconstruir evidencia real, resolver carry-forward editorial y confirmar idempotencia sin guardar
secretos.

La fuente normativa del wire continúa siendo `FRONTEND_INTEGRATION.md` §4.1.a. Este documento fija
sólo el modelo interno y las barreras de persistencia.

## Alternativas evaluadas

### 1. Relacional normalizado — elegido

Se separan identidades estables, versiones, publicaciones, relaciones ordenadas, proyecciones
vigentes y evidencia. PostgreSQL protege formato, unicidad, tenant, estados e inmutabilidad.

Ventajas:

- las invariantes importantes no dependen sólo de Java;
- el historial y el carry-forward se consultan sin interpretar blobs;
- encaja con Flyway, JPA y `ddl-auto=validate`;
- permite retener evidencia y purgar metadata técnica por separado.

Costo: más tablas y una migración deliberadamente explícita.

### 2. Manifiesto JSONB con proyecciones parciales — descartado

Reduce el DDL inicial, pero desplaza al servicio la identidad de keys, linajes, audiencias,
solapamientos y documentos exactos. También dificulta Hibernate/H2, paginación e historial.

### 3. Event sourcing completo — descartado

Ofrece auditoría total, pero obliga a construir proyecciones y reconstrucción de estado antes de
tener un importador. Es una complejidad desproporcionada para el backend actual.

## Convenciones físicas

- Los IDs expuestos por el contrato usan `UUID` nativo, generado por la aplicación/Hibernate; no se
  instala una extensión PostgreSQL.
- Las FK existentes de usuario y taller continúan en `BIGINT`.
- Todos los tiempos legales usan `TIMESTAMP WITH TIME ZONE` y `Instant` UTC.
- Enums se guardan como `VARCHAR` con `CHECK`, no como enums propietarios de PostgreSQL.
- SHA-256/HMAC usa 64 hex minúsculas. Una revisión usa exactamente `sha256:<64-hex>`.
- Toda FK histórica usa `ON DELETE RESTRICT`. No hay `CASCADE`, `orphanRemoval` ni borrado físico
  desde usuario, taller, publicación, requisito o documento.
- Las relaciones con ordinal son entidades/tablas explícitas; no se usa `@ManyToMany` opaco.
- El modelo guarda `lineage_ordinal` y `manifest_ordinal` por separado. Nunca ordena versiones
  humanas lexicográficamente.

## Publicaciones inmutables

`legal_publicaciones` representa un manifiesto importado:

- UUID interno y `publication_id` externo único;
- versión de schema, locale y digest del manifiesto canónico;
- manifiesto RFC 8785 canónico como `TEXT` para auditoría exacta;
- snapshot del publicador y sus contactos;
- estado, referencia y fecha de revisión legal/contable;
- instante de importación;
- estado de construcción `ABIERTO | SELLADO` e instante de sellado.

Todos sus campos son inmutables salvo la transición técnica única `ABIERTO -> SELLADO`. Una
corrección usa otro `publication_id`; no actualiza ni reabre el manifiesto previo. Los estados
editoriales pertenecen a versiones documentales y de requisito, no a esta fila de lote.

La publicación abierta es el único agregado de construcción. `legal_documento_lineas` y
`legal_requisito_lineas` guardan su publicación introductoria; las versiones ya la guardan. Las
cuatro raíces `legal_documento_lineas`, `legal_documento_versiones`, `legal_requisito_lineas` y
`legal_requisito_versiones` sólo pueden insertarse mientras su publicación introductoria siga
abierta. La misma regla se aplica a sus hijos intrínsecos:

- contextos de una versión documental;
- audiencias de una línea de requisito;
- documentos de una versión de requisito.

Las membresías `legal_publicacion_documentos`, `legal_publicacion_requisitos`, cabeceras de snapshot
y miembros de snapshot sólo pueden insertarse mientras **su propia** publicación esté abierta. Al
sellar, un constraint trigger diferible valida que cada agregado introducido esté completo, que cada
versión introducida sea miembro de la publicación y que todos los ordinales requeridos sean contiguos
`1..N`. `SELLADO` es terminal y luego los hijos rechazan `INSERT`, `UPDATE` y `DELETE`.

El sello también exige que toda línea o versión referenciada pero introducida por otra publicación
ya pertenezca a una publicación `SELLADO`. Las dependencias propias se cierran atómicamente con el
sello actual. La validación recorre líneas, versiones documentales y de requisito, documentos de
cada requisito y miembros de snapshot; así una publicación sellada nunca depende de un agregado
externo todavía ampliable y dos publicaciones abiertas no pueden formar un ciclo de dependencias.

El cierre es seguro ante concurrencia PostgreSQL. Cada `INSERT` de una de las cuatro raíces, hijo
intrínseco, membresía o snapshot ejecuta `SELECT ... FOR SHARE` sobre la publicación dueña antes de
comprobar `ABIERTO` y retiene el lock hasta el fin de la transacción. La transición a `SELLADO` toma
el lock de fila incompatible, espera inserciones previas y ejecuta su validación diferible después
de la espera. Si el sello ganó la carrera, el `SELECT ... FOR SHARE` del insert relee la versión
bloqueada y rechaza el estado terminal. Las publicaciones introductoras externas se
bloquean/validan en orden UUID determinístico para evitar deadlocks.

El digest RFC 8785 se calcula y compara en el futuro importador antes del sello; PostgreSQL preserva
el `TEXT` canónico y el digest suministrado, comprueba su formato y evita que el grafo normalizado
cambie después. Ninguna versión puede pasar a `PUBLICADA`, ningún slot puede activarse y ningún
snapshot puede volverse actual mientras su publicación permanezca abierta.

Una versión exacta puede aparecer en publicaciones posteriores mediante tablas N:M si coincide
completamente. La nueva membresía se construye dentro de esa otra publicación abierta; no habilita
agregar hijos a la publicación introductoria ya sellada. Repetir `key + version` con bytes o metadata
diferentes falla; no crea otro UUID.

## Documentos

### Línea estable

`legal_documento_lineas` fija para siempre, además de su publicación introductoria:

- `key` estable;
- `tipo`;
- `locale`.

Reutilizar el key con otro tipo o locale es imposible por diseño.

### Versión

`legal_documento_versiones` contiene:

- UUID público;
- línea y publicación introductoria;
- versión humana y `lineage_ordinal` positivo;
- título, Markdown, digest y `vigente_desde`;
- `requires_reacceptance`;
- estado, motivo e instante de la última transición.

Son únicos `linea + version` y `linea + lineage_ordinal`. Título, contenido, digest, fecha, línea,
versión, ordinal y flag no admiten `UPDATE` ni `DELETE`, incluso en borrador. Corregir una importación
crea otra versión/publicación.

`legal_documento_contextos` guarda el conjunto no vacío y congelado de contextos de cada versión.
`legal_publicacion_documentos` vincula publicaciones y versiones con su `manifest_ordinal`.

### Ciclo y slots vigentes

El trigger de transición admite solamente:

```text
BORRADOR -> PUBLICADA -> VIGENTE -> REEMPLAZADA | RETIRADA
```

Cada cambio agrega una fila inmutable a `legal_documento_transiciones`. `RETIRADA` requiere un motivo
no vacío. `PUBLICADA -> VIGENTE` se rechaza si `vigente_desde` es posterior a
`transaction_timestamp()`. Los estados terminales no cambian ni pueden volver a ocupar slots, aunque
otra publicación histórica vuelva a vincular la misma versión.

`legal_documento_vigentes` materializa slots:

```text
(tipo, locale, contexto) -> documentoVersionId + publicacionId
```

Su PK compuesta impide dos documentos vigentes superpuestos, incluso con concurrencia. FK y triggers
diferibles verifican al commit que:

- el slot referencia un contexto congelado de esa versión;
- tipo y locale coinciden con la línea;
- una versión `VIGENTE` ocupa exactamente todos sus contextos;
- una versión no vigente no conserva slots.

`legal_documento_reemplazo_lotes` agrupa una transición y dos membresías inmutables:
`legal_documento_reemplazo_anteriores` y `legal_documento_reemplazo_sucesoras`. Esto permite split y
merge sin fingir que cada arista individual cubre todo.

Al commit, la unión disjunta de contextos anteriores debe ser exactamente igual a la unión disjunta
de sucesoras, siempre con el mismo tipo/locale. Todas las anteriores terminan `REEMPLAZADA` y todas
las sucesoras quedan `VIGENTE` en la misma transacción. Para ampliar alcance se crea otra versión
vigente que ocupa únicamente los contextos nuevos y disjuntos, sin modificar/revivir la anterior ni
agregarle slots. Un futuro modo de expansión que reemplace por un superset queda fuera de esta fase.
`RETIRADA` no exige sucesora y deja los sets afectados en modo fail-closed.

El lote nace `ABIERTO`, exige al menos una versión distinta en cada lado, prohíbe que una versión
aparezca como anterior y sucesora, y se sella en la misma transacción de estados/slots. Al inicio del
cierre, las anteriores deben estar `VIGENTE` y las sucesoras `PUBLICADA`. `sellado_en` vuelve
inmutables cabecera y membresías; no se pueden insertar miembros tardíos ni cerrar dos veces.
Cada insert de miembro toma `SELECT ... FOR SHARE` sobre el lote antes de releer `ABIERTO`; el sello
toma el lock incompatible y usa la misma disciplina de espera que las publicaciones.
Las transiciones generadas guardan el mismo `reemplazo_lote_id`: es obligatorio en cada
`VIGENTE -> REEMPLAZADA` y en cada `PUBLICADA -> VIGENTE` del lote, y sólo el trigger de sellado puede
asignarlo. No se permite asociar a posteriori un lote con estados históricos ya alcanzados.

## Requisitos

### Línea estable y audiencias

`legal_requisito_lineas` fija, además de su publicación introductoria:

- key estable;
- locale;
- contexto;
- tipo de acto.

`legal_requisito_audiencias` fija el conjunto no vacío de `ADMIN_TITULAR`/`USER`. Un key no puede
reutilizarse con otro contexto, acto o audiencia.

### Versión y documentos

`legal_requisito_versiones` contiene UUID, línea, publicación introductoria, versión,
`lineage_ordinal`, afirmación/digest, `requerido`, `requires_reacceptance` y estado interno.

El ciclo interno replica el documental aunque no se exponga en el wire. Así el backend puede definir
cuál requisito es vigente sin inferirlo por fecha o UUID.

Cada cambio agrega una fila inmutable a `legal_requisito_transiciones`; comparte la máquina de
estados, terminalidad y sello de publicación. Ese historial prueba si una versión alcanzó alguna vez
`PUBLICADA`, condición usada por carry-forward. No se usa la fecha de importación como sustituto.

`legal_requisito_documentos` relaciona documentos exactos y ordenados. Un trigger verifica mismo
locale y que el documento incluya el contexto del requisito. Debe existir al menos uno.

`legal_publicacion_requisitos` conserva la inclusión N:M y `manifest_ordinal`.

La vigencia no se representa como slots aislados. `legal_requisito_conjuntos` es una cabecera
inmutable de snapshot por `(publicacion, locale, contexto, audiencia)` y
`legal_requisito_conjunto_miembros` guarda cero o más versiones con FK compuesta a la membresía de
esa misma publicación, `requisito_linea_id` y `manifest_ordinal`. Son únicos la línea y el ordinal
dentro del conjunto, por lo que pueden coexistir términos, privacidad y tratamiento sin perder orden.

`legal_requisito_conjuntos_actuales` es el único puntero mutable, con PK
`(locale, contexto, audiencia)` y FK al snapshot. Distingue un conjunto vacío deliberadamente válido
de la ausencia/invalidación fail-closed. Un trigger diferible exige que los miembros sean exactamente
los requisitos aplicables de esa publicación para el scope, con sus ordinales completos. El contexto
público `REGISTRO` además necesita al menos un miembro `requerido=true` para pasar readiness.

Cabecera y miembros del conjunto se construyen sólo durante `ABIERTO` y quedan sellados
implícitamente con su publicación. Después no admiten inserciones tardías, aunque el snapshot ya no
sea actual; únicamente cambia el puntero externo `legal_requisito_conjuntos_actuales`.

Una versión de requisito `VIGENTE` aparece en todos los snapshots actuales de sus audiencias
congeladas o en ninguno; la promoción/liberación multiaudiencia es atómica. Un snapshot nunca mezcla
publicaciones.

Un miembro actual sólo es válido si todos sus documentos exactos continúan `VIGENTE`, ocupan el slot
documental correspondiente y pertenecen a la misma publicación mediante
`legal_publicacion_documentos`. Reemplazar o retirar un documento obliga a invalidar/eliminar en la
misma transacción todo puntero actual cuyo snapshot todavía lo referencia, o la transacción falla.
Los snapshots históricos permanecen inmutables, pero la ausencia del puntero hace que
readiness/lectura responda `503`; nunca queda un conjunto aparentemente vigente apoyado en un
documento histórico.

Una promoción futura reemplaza de forma transaccional el snapshot completo afectado. En respuestas
multicontexto se ordena primero por el orden congelado de `ContextoLegal` y luego por
`manifest_ordinal`.

## Linaje y carry-forward

El carry-forward recorre `lineage_ordinal`, pero sólo considera versiones que alguna vez alcanzaron
`PUBLICADA`; un borrador abandonado no obliga a reaceptar.

Para satisfacer un requisito vigente desde evidencia anterior:

1. una evidencia de la versión exacta y sus documentos exactos siempre satisface esa versión para el
   mismo actor, independientemente de un cambio posterior de rol; rol/audiencia quedan como snapshot
   histórico, no forman parte de la unicidad;
2. para carry-forward, debe existir evidencia del mismo usuario, tenant y key;
3. sólo se recorren versiones publicadas cuyo ordinal cumpla
   `evidenciada.lineage_ordinal < ordinal <= vigente.lineage_ordinal`; una publicación futura no
   bloquea anticipadamente;
4. todas esas versiones del requisito deben tener `requires_reacceptance=false`;
5. cada documento vigente debe conservar un key ya evidenciado;
6. para cada documento se aplica el mismo intervalo exclusivo/inclusivo de ordinales sobre **toda**
   la línea estable, sin filtrar por contexto, tal como congela el contrato v1; el flag documental es
   conservador y afecta a cualquier requisito que conserve ese `document.key`;
7. todas las versiones documentales resultantes de ese recorrido deben tener el flag en `false`;
8. un key/documento agregado o cualquier `true` impide herencia;
9. una nueva aceptación explícita se vuelve la base posterior.

Una herencia exitosa no inserta evidencia ni modifica fechas.

## Evidencia

### Lote

`legal_aceptacion_lotes` representa un POST/registro atómico y guarda:

- UUID;
- usuario y taller con FK compuesta que prueba pertenencia al insertar;
- snapshots de rol wire y audiencia, con `ADMIN <-> ADMIN_TITULAR` y `USER <-> USER`;
- `required_set_revision`;
- `aceptado_en` del servidor.

V27 agrega `UNIQUE users(id, taller_id)` para sostener la FK compuesta. Las aceptaciones que duplican
user/taller por consulta usan a su vez una FK compuesta `(lote_id, user_id, taller_id)` al lote, de
modo que JPA no pueda mezclar snapshots de dos actores o tenants.

La fila no admite update/delete. La futura baja de cuenta debe pseudonimizar mediante un
procedimiento legal específico; nunca destruye evidencia por cascada.

Al commit, cada lote exige al menos una aceptación y exactamente una cabecera de metadata técnica.
No puede confirmarse un lote/idempotencia vacío o sin IP protegida.

### Acto y documentos aceptados

`legal_aceptaciones` guarda una fila UUID por requisito con snapshots canónicos de key, versión,
contexto, acto, afirmación, digest y obligatoriedad. Conserva user/taller para indexar y exige
unicidad permanente `usuario + requisitoVersionId`. `aceptado_en` vive sólo en el lote para evitar
dos relojes; historial y DTO lo obtienen mediante el join.

`legal_aceptacion_documentos` copia por ordinal key, UUID, tipo, versión, título y digest. Un
constraint trigger diferible comprueba al commit tanto el encabezado como sus documentos:

- requirementVersion/key/version/contexto/acto/afirmación/digest/requerido coinciden con la versión
  canónica;
- la audiencia snapshot del lote pertenece a la línea del requisito;
- al insertar, la versión es miembro del snapshot apuntado actualmente para
  `(locale, contexto, audiencia)`; después la evidencia histórica sobrevive aunque cambie el puntero;
- la evidencia contiene exactamente los documentos del requisito, sin extras/omisiones;
- todos los snapshots documentales coinciden con sus versiones canónicas.

### Metadata técnica separada

`legal_aceptacion_metadatos` es una cabecera 1:1 con lote, captura, vencimiento de retención y fecha
de purga. `legal_aceptacion_metadatos_cifrados` contiene como máximo un campo `IP` y uno
`USER_AGENT`, cada uno con nonce AES-GCM de 12 bytes, ciphertext, tag de 16 bytes, versión de clave y
longitud original. Tras la purga la misma fila queda como tombstone criptográfico: conserva lote,
tipo, versión de clave y nonce, pero no conserva payload ni longitud.

- IP nunca se guarda en claro.
- User-Agent es opcional y antes de cifrar admite como máximo 512 caracteres.
- El AAD es determinístico y liga cada valor a
  `ordenfix:legal-metadata:v1:<loteId>:<IP|USER_AGENT>:<keyVersion>`; un ciphertext no puede moverse
  entre lotes o campos.
- `UNIQUE(key_version, nonce)` global impide reutilizar un nonce con la misma clave, incluso entre IP,
  User-Agent o lotes distintos.
- La cabecera exige un campo IP protegido y una fecha de retención al crear evidencia.
- Al vencer, la única transición permitida nulifica `ciphertext`, `tag` y longitud original, marca
  cada tombstone y luego `purgado_en` en la cabecera; no elimina `(key_version, nonce)`. Antes de la
  purga existe IP con payload completo y `purgado_en IS NULL`; después no queda payload cifrado,
  todos los campos del lote son tombstones y `purgado_en` no es NULL. Así la reserva global del
  nonce sobrevive toda la vida de la clave, incluso si backups aún contienen un ciphertext anterior.
- El keyring de metadata legal es propio; nunca reutiliza
  `DEVICE_CREDENTIALS_ENCRYPTION_KEY` ni secretos HMAC.
- El resolver de IP por proxies/CIDR confiables y el cifrado real pertenecen a una fase de servicio;
  no se reutiliza el helper actual que confía en el primer `X-Forwarded-For`.

## Idempotencia

`legal_idempotencia_resultados` guarda exclusivamente operaciones exitosas:

- operación y plantilla de ruta;
- HMAC de scope y de `Idempotency-Key`;
- fingerprint HMAC y versión de clave;
- referencias tipadas al usuario/taller/lote resultante;
- `completed_at` y `expires_at`, con garantía mínima de 24 horas.

La unicidad exacta es
`(operacion, route_template, scope_hmac, idempotency_key_hmac)`. Un `CHECK` exige
`expires_at >= completed_at + interval '24 hours'` y forma de resultado por operación:

- `REGISTRO`: user, taller y lote legal no nulos, unidos por la misma FK compuesta
  `(lote_id, user_id, taller_id)`;
- `ACEPTACION_LEGAL`: la misma FK compuesta y lote no nulo.

Nunca contiene clave cruda, contraseña, request, JWT ni response serializado. La fila se confirma en
la misma transacción que el resultado de negocio. Puede purgarse sólo después de `expires_at`.

`IN_PROGRESS` no se persiste como resultado. La fase de servicio usará un advisory transaction lock
PostgreSQL derivado del HMAC para esperar hasta cinco segundos; el lock desaparece con la
transacción. La unicidad `usuario + requisitoVersionId` continúa resolviendo dos claves distintas que
compiten por la misma evidencia. El servicio no intentará recuperar un `DataIntegrityViolation`
dentro de una transacción PostgreSQL ya abortada: serializará por actor/requisito o usará un
`INSERT ... ON CONFLICT DO NOTHING` nativo antes de responder `204`.

Los secretos HMAC de idempotencia usan otro keyring, separado del cifrado de metadata y de
credenciales de dispositivos. Una rotación conserva todas las versiones con resultados no vencidos:
el lookup calcula candidatos con cada versión retenida y no elimina una clave hasta que no exista
ninguna fila con `expires_at` futuro. De lo contrario, una misma key podría eludir replay dentro de
las 24 horas.

La fase de servicio debe además cerrar la carrera entre réplicas durante una rotación:

1. primero distribuye la nueva clave como inactiva y confirma que **todas** las réplicas con tráfico
   conocen el mismo keyring retenido;
2. para cada request calcula todos los HMAC candidatos, adquiere advisory transaction locks para
   todos ellos en orden byte-a-byte determinístico y, ya dentro de los locks, vuelve a consultar
   todos los candidatos;
3. sólo entonces cambia una única `active_write_version` coordinada a nivel cluster; durante una
   convergencia temporal todas las réplicas conocen ambas versiones y comparten al menos los mismos
   locks y la misma búsqueda;
4. una instancia que no confirmó el keyring activo queda fuera de servicio y no recibe tráfico.

La versión activa elige qué HMAC se persiste, pero nunca es por sí sola el mecanismo de exclusión.
Activar una clave antes de la barrera de distribución está prohibido operacionalmente.

## Repositorios y carga

Se crean repositorios para publicaciones, líneas/versiones documentales, líneas/versiones de
requisito, slots documentales, snapshots/punteros actuales, aceptaciones e idempotencia.

- Todos los métodos privados incluyen actor/tenant cuando corresponde.
- El historial pagina por `aceptadoEn DESC, id DESC` y puede filtrar contexto.
- Catálogo y requisitos usarán consultas en dos pasos o `EntityGraph`; nunca fetch-join de múltiples
  colecciones junto con `Pageable`.
- Los entities legales no exponen setters públicos generales ni cascadas destructivas.
- El mapeo `ADMIN -> ADMIN_TITULAR` / `USER -> USER` es un método explícito de dominio.
- `LocaleLegal` usa un `AttributeConverter` (`ES_AR <-> es-AR`); no depende del nombre Java del
  enum. Los demás enums persistidos usan `EnumType.STRING`.
- Los instantes se asignan desde el `Clock` de aplicación o desde triggers; no se usa `@CreatedDate`
  mientras `JpaAuditingConfig` no tenga un `DateTimeProvider` ligado a ese Clock.
- Las tablas N:M exigen `UNIQUE(publicacion_id, version_id)` y
  `UNIQUE(publicacion_id, manifest_ordinal)`. Slots documentales y de requisito usan FK compuesta a
  esa membresía, no dos FK independientes.
- Las relaciones con ordinal usan PK sustituta y uniques de negocio para simplificar Hibernate/H2;
  las proyecciones de slots pueden usar IDs compuestos.

## Barreras PostgreSQL

La migración V27 debe validar como mínimo:

- enums y longitudes;
- SHA/HMAC/revisiones;
- ordinales positivos;
- identidades/linajes únicos;
- contextos/audiencias/documentos no vacíos;
- transiciones válidas y estados terminales;
- slots sin solapamiento y coherentes con estado;
- reemplazo multicontexto exacto;
- snapshots de requisitos completos, homogéneos por publicación y distinguibles de ausencia;
- actor, tenant, rol y audiencia coherentes;
- evidencia exacta, inmutable y no duplicada;
- resultados idempotentes sin secretos y con expiración mínima.

Las tablas de definición, versión, membresía, relaciones históricas, evidencia y transiciones
rechazan `DELETE`. Las proyecciones `legal_documento_vigentes` y
`legal_requisito_conjuntos_actuales` sí se reemplazan mediante delete/insert transaccional; metadata
técnica e idempotencia poseen una purga acotada y comprobable.

Los hijos intrínsecos y de membresía también rechazan `INSERT` cuando su publicación dueña o
introductoria está `SELLADO`. La única mutación del agregado editorial es sellar una publicación
abierta; nunca se reabre.

## Pruebas

`PostgresMigrationIT` verifica V27 desde cero, upgrade V26 -> V27 y `ddl-auto=validate`.

`LegalPersistenceIT` sobre PostgreSQL 16 cubre:

- checks de enum, hash, revisión y ordinal;
- key/version/linaje duplicados;
- publicación incompleta, ordinales no contiguos, activación antes del sello e inserciones tardías de
  líneas, versiones, contextos, audiencias, documentos, membresías o miembros de snapshot;
- sello que depende de otra publicación abierta/cíclica y carreras insert-vs-sello en ambos órdenes
  de commit usando dos conexiones reales;
- transiciones ilegales, activación anterior a `vigente_desde`, terminales, intento de revivir e
  inmutabilidad;
- ciclo interno de requisitos, eventos, terminalidad y publicación/promoción multiaudiencia;
- slots solapados;
- lote de reemplazo no vacío/sin autociclo, sellado, split/merge exacto y reemplazo parcial
  rechazado;
- múltiples requisitos ordenados en el mismo contexto/audiencia y cobertura multiaudiencia atómica;
- snapshot actual vacío deliberado frente a puntero ausente/inválido;
- relaciones requisito-documento incompatibles y transición documental con requisito vigente;
- actor/tenant/rol/audiencia incorrectos;
- evidencia con encabezado/documento extra, omisión, snapshot alterado o duplicado;
- metadata técnica, tamaños/completitud, unicidad de nonce y purga;
- unicidad idempotente, forma de resultado y expiración;
- FK `RESTRICT` y ausencia de cascadas.

Los tests de triggers diferibles usan `TransactionTemplate` y fuerzan
`SET CONSTRAINTS ALL IMMEDIATE` o el commit real; no asumen que un `flush` H2/JPA reproduce la
excepción de PostgreSQL.

H2 puede probar construcción/mapeo básico, pero no acredita triggers, regex, slots, constraints
diferibles ni concurrencia. La puerta real es `mvn verify` con Testcontainers.

La autenticación AES-GCM, el AAD y el rechazo de swaps lote/campo se prueban cuando exista el codec
de servicio; PostgreSQL en esta fase sólo puede acreditar estructura, nonce y estados de purga.

## Orden de implementación

1. agregar V27 y pruebas estructurales PostgreSQL;
2. agregar enums, entities y repositorios con Hibernate `validate`;
3. agregar pruebas de invariantes/round-trip;
4. actualizar el plan coordinado del frontend;
5. mantener endpoints y enforcement apagados.

## Fuera de alcance

Esta fase no:

- importa o publica el manifiesto real;
- expone endpoints ni DTO;
- modifica Spring Security, CORS o filtros;
- cambia registro/login;
- calcula RFC 8785, SHA o HMAC en servicios;
- resuelve proxies confiables ni cifra requests reales;
- activa `428`, reaceptación o contenido legal;
- cambia el frontend runtime.

## Siguiente corte

Implementar el importador/dry-run y la promoción transaccional sobre este esquema, todavía con
enforcement apagado. Sólo después se exponen las lecturas públicas/autenticadas.
