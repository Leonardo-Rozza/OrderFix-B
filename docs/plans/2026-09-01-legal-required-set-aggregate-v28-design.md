# Fase 2.3D — Revisión agregada multicontexto V28

Fecha: 2026-09-01

Estado: diseño aprobado; implementación pendiente

Continuidad:

- `docs/plans/2026-08-23-legal-api-contract-v1-design.md`;
- `docs/plans/2026-08-23-legal-persistence-append-only-design.md`;
- `docs/plans/2026-08-25-legal-manifest-import-design.md`;
- `docs/plans/2026-08-31-legal-manifest-promotion-closure-design.md`;
- `FRONTEND_INTEGRATION.md`.

## Decisión de producto

OrdenFix usará una única `requiredSetRevision` opaca para cada flujo legal, aunque ese flujo reúna
requisitos de varios contextos. La revisión representa los conjuntos completos de los scopes
aplicables al flujo, no la lista de requisitos pendientes de un actor.

El usuario aprobó estas reglas:

- sólo los contextos aplicables al flujo integran el agregado;
- cambiar un contexto incluido invalida la revisión;
- cambiar un contexto ajeno al flujo no la invalida;
- satisfacer requisitos y filtrar la respuesta no modifica la revisión;
- el backend deriva locale, audiencia y contextos aplicables; el cliente no puede aportarlos ni
  reducirlos;
- el frontend conserva y reenvía un único token `sha256:<64-hex>` sin interpretarlo;
- no existe un puntero agregado global, porque la composición depende del flujo;
- los agregados son inmutables, reconstruibles y auditables;
- V28 es aditiva: no se modifica el archivo de migración V27 ni se reinterpreta la evidencia
  histórica.

El alta futura también utilizará `AGGREGATE_V1`. Su vector tendrá un único contexto `REGISTRO`, por
lo que no requiere un modelo excepcional ni cambia el wire del frontend. La respuesta pública
entregará el token agregado de ese único scope y el registro lo reenviará sin conocer su estructura.

## Objetivo

Crear la base persistente y determinística que permita resolver y validar una revisión estable para
uno o más scopes legales actuales. V28 debe cerrar la incompatibilidad entre:

- las respuestas autenticadas multicontexto previstas por el contrato;
- los snapshots V27, cuya revisión pertenece a un único
  `(publicación, locale, contexto, audiencia)`; y
- `legal_aceptacion_lotes`, que sólo puede almacenar una revisión.

El resultado debe impedir aceptaciones parciales, mezclas entre estados editoriales concurrentes y
reducciones de alcance decididas por el navegador.

## Problema de V27

V27 persiste `required_set_revision` en cada `legal_requisito_conjuntos` y mantiene un puntero actual
por `(locale, contexto, audiencia)`. El trigger `legal_aceptacion_insert_guard()` compara la única
revisión del lote con la revisión del scope de cada aceptación individual.

Ese guard funciona para un scope. En un lote multicontexto normal, dos scopes poseen revisiones
distintas y una sola cabecera no puede coincidir con ambas. Reutilizar una revisión por contexto en
el frontend expondría detalles internos, permitiría omitir scopes y rompería el contrato congelado
de un único token opaco.

V27 tampoco permite recalcular la revisión desde la lista pendiente: el carry-forward y las
aceptaciones previas hacen que dos actores vean subconjuntos diferentes del mismo contrato vigente.
La revisión debe permanecer ligada a la fuente legal completa.

## Alternativas evaluadas

### A. Agregado inmutable materializado bajo demanda — elegida

El backend deriva el vector aplicable, lee los punteros V27 actuales bajo el gate editorial,
calcula una revisión canónica y crea o reutiliza una cabecera inmutable con su composición exacta.
La aceptación referencia esa cabecera.

Ventajas:

- preserva un solo token frontend;
- sólo depende de los contextos aplicables al flujo;
- permite reproducir qué scopes integraron la decisión;
- deduplica composiciones equivalentes;
- no obliga a anticipar todos los flujos posibles en la base.

El costo aceptado es una escritura idempotente durante la primera resolución de una composición.

### B. Agregado preconstruido por propósito — descartada

Una tabla de punteros por propósito evitaría materializar durante la lectura, pero acoplaría la
migración a cada flujo de producto. Agregar o combinar un flujo exigiría coordinación editorial y
una nueva identidad persistente aunque los scopes fueran los mismos.

### C. Digest dinámico sin persistencia — descartada

Calcular el hash en cada request simplifica el esquema, pero no conserva el vector exacto usado por
un lote. La auditoría y el replay dependerían de reconstruir estado histórico mutable, por lo que no
alcanza el estándar append-only de V27.

## Alcance

Incluye:

- migración V28 sin editar V27;
- esquema de agregados y miembros inmutables;
- modelo canónico `AGGREGATE_V1` y vector golden;
- materialización idempotente bajo el mismo advisory lock editorial;
- validación fail-closed de scopes incluidos;
- vínculo inequívoco entre un lote nuevo y su agregado;
- preservación explícita de lotes V27 como `SCOPE_V1` históricos;
- guard de pertenencia para aceptaciones `AGGREGATE_V1`;
- inventario y verificación propios de V28;
- pruebas unitarias, PostgreSQL, upgrade, concurrencia y rollback;
- documentación técnica y coordinación contractual posterior.

No incluye:

- controllers, endpoints, DTOs HTTP u OpenAPI;
- reglas detalladas que agregan contextos al perfil autenticado según el ciclo de vida;
- `documentSetRevision`, catálogo legal, paginación, ETag, caché o CORS;
- cálculo de pendientes, carry-forward, completitud de obligatorios u opcionales;
- idempotencia HTTP, traducción a `409`, `428` o `503` y enforcement;
- cambios de tipos, queries, mutations o pantallas frontend;
- contenido legal real, importación o promoción en una base de staging;
- deploy o push.

`BACKEND-HANDOFF 1` continúa cerrado. V28 prepara persistencia y núcleo; no acredita todavía una API
legal pública.

## Autoridad de aplicabilidad

La autoridad no es `LegalCoverageMatrix`, la existencia de punteros ni un array recibido por HTTP.
Un resolver interno y tipado construirá un `LegalApplicableScopeSet` según la operación, el ciclo de
vida y el actor acreditado por el backend.

El materializador recibe ese valor sólo desde código servidor. Las futuras fronteras HTTP nunca
aceptarán contexto, audiencia, rol o tenant como autoridad del request. Audiencia, usuario y taller
se derivarán de la sesión y de PostgreSQL; en registro, de la transacción que crea al titular.

V28 congela dos perfiles servidor:

- `REGISTRATION`, usado únicamente por la lectura pública de alta y `POST /api/auth/register`, con
  el contexto fijo `REGISTRO` y audiencia `ADMIN_TITULAR`;
- `AUTHENTICATED_PENDING`, compartido por el futuro `GET /api/requisitos-legales`,
  `POST /api/aceptaciones-legales` y las reconstrucciones de estado para `409` y `428`.

El segundo perfil se resuelve siempre con la misma función autoritativa a partir del actor y su
estado servidor. Contiene como mínimo `USO_CONTINUADO`; otros contextos sólo se agregan por reglas
allowlisteadas del ciclo de vida. Por lo tanto, las respuestas autenticadas mono o multicontexto
siempre poseen al menos un scope, incluso cuando el scope sea un snapshot deliberadamente vacío o
la evidencia del actor deje `requisitos: []`.

PostgreSQL no intenta inferir por sí solo el ciclo de vida del producto. La garantía se compone del
resolver único, el perfil persistido, la revalidación de punteros bajo lock y la ausencia de INSERT
directo para el rol de aplicación. La base prueba que el vector entregado por esa autoridad es
íntegro y actual; la futura capa de aplicación prueba que era el perfil completo del actor.

Un flujo futuro que necesite otra composición debe aportar una frontera servidor inequívoca y un
diseño contractual propio. No puede reutilizar el POST genérico ni permitir que el token elija un
perfil más angosto.

El vector debe ser no vacío, contener una sola locale y audiencia, no repetir contextos y usar sólo
valores conocidos de `ContextoLegal`. La ausencia o invalidez de cualquier scope incluido bloquea
toda la operación. Un scope no incluido es irrelevante para esa revisión, aunque esté ausente o
cambie.

Después de congelar el vector, el filtrado por evidencia exacta, carry-forward u otra regla de
satisfacción no puede quitar miembros del agregado. Esas reglas sólo determinan qué requisitos se
devuelven o deben confirmarse.

## Modelo canónico

### Proyección

La entrada hasheada es un objeto tipado con esta forma lógica:

```json
{
  "revisionScheme": "AGGREGATE_V1",
  "locale": "es-AR",
  "audiencia": "ADMIN_TITULAR",
  "scopes": [
    {
      "contexto": "USO_CONTINUADO",
      "requiredSetRevision": "sha256:..."
    },
    {
      "contexto": "ATESTACION_FOTOS",
      "requiredSetRevision": "sha256:..."
    }
  ]
}
```

`scopes` se ordena por el orden congelado de `ContextoLegal`, no alfabéticamente:

1. `REGISTRO`;
2. `PRIMER_INGRESO_EMPLEADO`;
3. `USO_CONTINUADO`;
4. `CONTRATACION_PRO`;
5. `ATESTACION_FOTOS`;
6. `ATESTACION_CREDENCIALES`;
7. `CIERRE_CUENTA`;
8. `ARREPENTIMIENTO`.

Cada revisión componente es el `SCOPE_V1` ya persistido en V27 y representa el conjunto completo
del scope. La proyección excluye IDs internos de cabecera y snapshot, publicación, usuario, taller,
rol wire, perfil servidor, evidencia previa y requisitos pendientes.

Las propiedades tienen el orden mostrado antes de RFC 8785. El calculator emite JSON canónico, toma
los bytes UTF-8, calcula SHA-256 y antepone `sha256:`. `revisionScheme` separa explícitamente el
dominio agregado del digest por scope. Duplicados, enum desconocido, vector vacío, locale o audiencia
mezclados y revisiones mal formadas son inválidos antes de hashear.

### Semántica

Dos vectores con igual locale, audiencia, contextos y revisiones `SCOPE_V1` producen exactamente el
mismo token, sin importar el orden de entrada o de las filas SQL. Cambiar una revisión incluida,
agregar o retirar un scope incluido, cambiar locale o audiencia produce otro token.

Una promoción ajena al vector no cambia el agregado. Una nueva evidencia del actor tampoco lo
cambia. Si todos los requisitos quedan satisfechos, la respuesta futura podrá contener
`requisitos: []` y conservará la revisión agregada vigente; no se calcula un digest del conjunto
vacío.

Esta regla sustituye, para registro y para toda respuesta autenticada futura —mono o
multicontexto—, la redacción de `legal-api-contract-v1-design.md` que hacía depender
`requiredSetRevision` de la respuesta filtrada. La revisión V27 por scope continúa existiendo como
componente interno y evidencia histórica. `documentSetRevision` no cambia en esta fase.

## Persistencia

### `legal_requisito_agregados`

Cabecera inmutable con:

- UUID;
- perfil servidor;
- locale;
- audiencia;
- `revision_scheme = 'AGGREGATE_V1'`;
- `required_set_revision`;
- fingerprint interno de procedencia;
- cantidad de scopes;
- `creado_en` autoritativo de PostgreSQL.

El fingerprint de procedencia usa un dominio interno separado y el vector ordenado de
`(contexto, conjunto_id, publicacion_id)`. No se expone ni integra `requiredSetRevision`. La identidad
única por perfil, locale, audiencia, scheme, revisión semántica y fingerprint permite reutilizar una
materialización físicamente idéntica.

Dos publicaciones pueden producir snapshots distintos con el mismo `SCOPE_V1`. En ese caso se
crean cabeceras diferentes, con el mismo token wire y distinta procedencia. Así un lote nuevo apunta
al snapshot realmente observado sin invalidar contenido semánticamente idéntico. La cabecera expone
claves compuestas suficientes para que el lote pruebe perfil, audiencia, scheme y digest, no sólo un
UUID suelto.

### `legal_requisito_agregado_scopes`

Sidecar inmutable que conserva, por cada cabecera:

- ordinal canónico;
- contexto;
- `conjunto_id` y `publicacion_id` del snapshot V27 observado;
- locale y audiencia para FKs compuestas;
- revisión `SCOPE_V1` componente.

Las constraints impiden contexto u ordinal duplicado, mezcla de cabeceras y scopes, revisión mal
formada y referencia a un snapshot de otra locale, audiencia, contexto o publicación. Una validación
diferible exige al commit la cardinalidad declarada y que no exista una cabecera parcial.

PostgreSQL acredita estructura, identidad y membresía; no intenta implementar RFC 8785 dentro de una
constraint. Un trigger V28 sobre el sidecar compara la revisión componente con la fila V27
referenciada usando las FKs existentes, sin agregar constraints ni cambiar fingerprints de tablas
V27. El calculator tipado es el único constructor del digest y un replay Java, antes de confirmar la
misma transacción, reconstruye cabecera y sidecar para comprobar bytes canónicos, revisión semántica
y fingerprint de procedencia. El verificador V28 repite esa prueba sobre el esquema persistido. Los
privilegios de escritura se limitan a ese camino y forman parte del inventario V28.

El UUID V27 conserva procedencia auditable, pero no integra los bytes canónicos wire. Si un puntero
futuro apunta a otro snapshot con la misma revisión de contenido, el token puede seguir siendo
válido y una cabecera física nueva conserva la observación nueva. La revalidación contrasta el vector
semántico y la procedencia actuales; la evidencia aceptada conserva además sus propios snapshots de
requisito y documentos.

No existe `legal_requisito_agregado_actual`: una clave global por locale y audiencia invalidaría
flujos por cambios que no les pertenecen.

### Compatibilidad de `legal_aceptacion_lotes`

V28 añade un discriminador `SCOPE_V1 | AGGREGATE_V1` y una referencia nullable al agregado. La
invariante es:

- una fila histórica `SCOPE_V1` no posee referencia agregada;
- una fila nueva `AGGREGATE_V1` exige agregado y su perfil, audiencia, scheme y
  `required_set_revision` coinciden por FK/constraint;
- después de V28 no se crean lotes `SCOPE_V1`, incluido el registro futuro.

La migración marca filas preexistentes mediante DDL con default constante y retira ese default antes
de habilitar nuevas escrituras. No ejecuta `UPDATE`, no dispara un backfill prohibido por los guards
append-only y no intenta inferir locale, contexto o agregado histórico.

`SCOPE_V1` significa únicamente semántica histórica por scope. No se afirma que cada lote V27 tenga
un solo contexto: el esquema anterior podía conservar varios si sus revisiones coincidían. Esos
lotes siguen legibles e inmutables y nunca se revalidan contra punteros actuales.

Un `BEFORE INSERT` V28 rechaza explícitamente cualquier lote nuevo que no sea `AGGREGATE_V1`, aunque
el caller intente enviar `SCOPE_V1`. El guard V28 reemplaza la comparación imposible del guard de
aceptación V27 para escrituras nuevas, sin editar el archivo V27. Conserva la prueba `xmin` que
impide anexar actos a un lote ya confirmado. Toda aceptación `AGGREGATE_V1` debe pertenecer a un
scope y requisito del vector agregado.

Para `AGGREGATE_V1`, el backend ignora cualquier fecha del caller y el guard fija `aceptado_en` con
`statement_timestamp()` en una sentencia posterior a adquirir el lock compartido, no con el inicio
antiguo de la transacción. La misma causalidad se aplica a `creado_en` de una cabecera nueva. Metadata
e idempotencia continúan copiando el instante autoritativo del lote.

Los verificadores se versionan para distinguir el esquema V27 histórico del esquema V28 instalado;
no se atribuye esta acreditación a los inventarios cerrados de 2.3C. El rol de aplicación no recibe
un INSERT genérico capaz de saltar el materializador/guard.

## Resolución y materialización

Una operación interna sigue esta secuencia en una sola transacción y sesión JDBC:

1. Derivar locale, audiencia y vector de contextos mediante la autoridad servidor.
2. Adquirir en modo compartido la misma key del advisory transaction lock global usado en modo
   exclusivo por import, dry-run y operaciones editoriales.
3. Leer y bloquear los punteros V27 incluidos en orden locale, audiencia y `ContextoLegal`.
4. Fallar cerrado si falta un puntero, el snapshot no está completo, su revisión es inválida o sus
   documentos/requisitos no sostienen el estado actual.
5. Construir la proyección canónica desde las revisiones completas V27.
6. Calcular `requiredSetRevision`.
7. Calcular el fingerprint de procedencia e insertar o reutilizar la cabecera física exacta.
8. Persistir el vector y ejecutar el replay Java dentro de la misma transacción.
9. Ante una colisión única concurrente, releer y exigir coincidencia física exacta; nunca
   reinterpretar un conflicto distinto como replay exitoso.
10. Confirmar sólo si cabecera y todos los miembros están completos.

Los resolvers, materializadores y aceptaciones pueden coexistir con locks compartidos. Los writers
editoriales conservan el lock exclusivo y esperan a que terminen esos lectores. Por eso cada
operación observa el estado íntegro anterior o posterior a una promoción, reemplazo, split, merge o
retiro, nunca scopes mezclados, sin serializar globalmente todos los GET. Los row locks de los
punteros incluidos protegen su uso dentro de la transacción; la autoridad de aplicabilidad impide
que una omisión se disfrace como ausencia de fila.

Los agregados quedan inmutables aunque dejen de ser actuales. No se eliminan al mover punteros ni se
reescriben para seguir una nueva publicación.

## Aceptación futura

El servicio futuro de aceptación volverá a derivar actor, taller, rol, audiencia, locale y contextos;
no confiará en el vector del GET ni en datos de autorización enviados por el browser. Dentro de la
misma transacción:

1. toma en modo compartido la key del advisory lock editorial;
2. vuelve a resolver el perfil servidor y materializa el agregado actual para ese perfil;
3. compara el token enviado con la revisión actual;
4. exige que cada requisito enviado pertenezca a un scope del agregado y al snapshot completo que
   representa;
5. persiste lote, actos, documentos, metadata e idempotencia de forma atómica.

Entre GET y POST puede ocurrir una edición. Si cambia un scope incluido, el POST se considera stale y
no acepta el estado anterior. Si cambia sólo un scope excluido, el token continúa válido. Un puntero
incluido ausente o incompleto bloquea la operación. El mapeo HTTP futuro será `409` para revisión
stale y `503` para indisponibilidad legal fail-closed; V28 todavía no implementa esos responses.

Duplicados, requisitos extranjeros, digests inválidos o membresía incorrecta producen error de
validación y rollback total. La decisión de qué requisitos pendientes deben enviarse, cuáles son
obligatorios y qué evidencia hereda continúa a cargo del futuro servicio de aplicación. V28 asegura
identidad y pertenencia, no implementa por adelantado esas reglas.

## Concurrencia y rollback

El cálculo, la acreditación del vector y cualquier lote que lo consuma deben ocurrir bajo la misma
key editorial. Resolución y aceptación toman el modo compartido; toda mutación editorial toma el modo
exclusivo. `READ_COMMITTED` no se usa como sustituto del lock: la compatibilidad shared/exclusive
impide que cambien punteros mientras se usan y los row locks preservan las filas incluidas durante la
transacción.

Un timeout, deadlock, pérdida de sesión, fallo diferido o conflicto no concluyente nunca confirma un
agregado o lote parcial. La transacción revierte cabecera, miembros, lote, actos, documentos,
metadata e idempotencia. Los huecos legítimos de secuencias PostgreSQL no se consideran DML
persistido.

La migración Flyway usa DDL transaccional y no crea índices `CONCURRENTLY`. Después de que existan
lotes `AGGREGATE_V1`, no se diseña un down destructivo: la recuperación operativa es roll-forward o
restore explícito de la base completa.

## Estrategia de pruebas

### Canonicalización

- vector golden con bytes RFC 8785 y SHA esperado;
- input permutado produce los mismos bytes y token;
- cambiar una revisión incluida cambia el token;
- agregar o retirar un scope incluido cambia el token;
- cambiar un scope legítimamente excluido no cambia el token;
- locale o audiencia distintos cambian el token;
- duplicado, vector vacío, enum desconocido, mezcla de locale/audiencia y digest inválido fallan;
- actor, taller, evidencia y lista pendiente no aparecen en la proyección.

### Persistencia

- materialización física repetida reutiliza el agregado sin DML adicional;
- otro snapshot con la misma revisión crea otra cabecera de procedencia y conserva el token wire;
- cabecera y sidecar reconstruyen exactamente la proyección;
- el trigger sidecar contrasta la revisión copiada con el snapshot V27 sin alterar V27;
- FK y constraints rechazan scope, snapshot, ordinal, locale o audiencia cruzados;
- un fallo diferido deja cero cabeceras o miembros parciales;
- los agregados históricos sobreviven cambios de puntero.

### Aplicabilidad y aceptación

- el caller externo no puede reducir el vector;
- un scope incluido ausente o incompleto falla cerrado;
- un scope excluido ausente no bloquea el flujo;
- filtrar algunos o todos los pendientes conserva la revisión;
- GET, POST, `409` y `428` autenticados usan el mismo perfil `AUTHENTICATED_PENDING`;
- una aceptación pertenece al agregado completo;
- revisión stale, requisito ajeno, duplicado o digest incorrecto revierte el lote completo;
- registro usa un agregado válido de un único scope `REGISTRO`;
- un INSERT nuevo `SCOPE_V1` se rechaza incluso si lo solicita explícitamente;
- `aceptado_en` y `creado_en` quedan después de la adquisición efectiva del lock;
- los lotes V27 históricos permanecen consultables sin revalidación ni backfill inferido.

### Concurrencia

- dos materializaciones idénticas convergen a una identidad válida;
- varios readers/materializadores compartidos progresan en paralelo;
- un writer editorial exclusivo espera a los readers y los nuevos readers respetan el presupuesto;
- una promoción concurrente produce el vector íntegro anterior o posterior;
- nunca aparece una mezcla de revisiones de ambos estados;
- una edición entre GET y POST invalida sólo si afecta un scope incluido;
- timeout, deadlock y session kill no fabrican éxito.

### Migración e inventario

- instalación limpia Flyway V1 a V28;
- upgrade real V27 a V28 con base vacía;
- upgrade con evidencia V27 sembrada antes de aplicar V28;
- historia V27 posterior a reemplazo o retiro permanece byte y cardinalmente intacta;
- caso legacy multicontexto con revisiones coincidentes no se reinterpreta;
- ninguna prueba fabrica historia `SCOPE_V1` mediante INSERT después de V28;
- checksums y archivo V27 permanecen sin cambios;
- inventario, funciones, constraints, triggers, índices y grants V28 coinciden con la expectativa;
- los gates V27 continúan acreditando únicamente su superficie declarada;
- suite Maven completa con Java 21 y PostgreSQL 16.

## Criterios de aceptación del diseño

V28 queda lista para los cortes HTTP posteriores cuando:

1. una composición multicontexto produce un único token opaco y reproducible;
2. sólo un cambio dentro del vector aplicable invalida ese token;
3. el filtrado por evidencia no altera la revisión;
4. PostgreSQL conserva el vector exacto detrás de cada lote nuevo;
5. el núcleo transaccional y sus guards rechazan un agregado stale, parcial o extranjero, y la
   futura capa HTTP sólo puede llegar por el resolver de perfil autoritativo;
6. toda evidencia V27 sobrevive sin mutación ni reinterpretación;
7. clean install, upgrade, concurrencia, rollback e inventario pasan el gate completo;
8. no se introduce endpoint, cambio frontend, contenido real, deploy ni push.

## Secuencia posterior

La implementación se dividirá en cortes atómicos. Primero se congelarán el modelo canónico y sus
vectores; luego el esquema V28; después la materialización JDBC y concurrencia; a continuación la
compatibilidad de lotes y guards; finalmente inventarios, upgrade, documentación y gate integral.
Cada corte tendrá pruebas y commit propio.
