# Corte 15A — Protocolo de cuenta, V29 y fronteras consumidoras

Fecha: 2026-09-06

Estado: Corte 15A cerrado el 2026-09-06 con 15 pruebas focales aprobadas. V29 y las capacidades
de aplicación aún no están implementadas ni habilitadas; este documento fija su diseño.

## Alcance y evidencia de partida

Baseline backend `b51cb5a`, rama `codex/lanzamiento-publico-backend`, limpio al comenzar. Frontend
`7545201`, rama `codex/frontend-refactor-checkpoint`, conservado con sus archivos no versionados.
Este corte precisa el contrato, el diseño SQL futuro y sus permisos; agrega pruebas de viabilidad
sobre PostgreSQL 16 y Spring real. No crea una migración, servicio, controller, flag o grant real.

El [diseño](2026-09-06-legal-account-consent-design.md) y el
[plan](2026-09-06-legal-account-consent-implementation.md) describen el bloque 15. El wire se conserva
en [FRONTEND_INTEGRATION](../../FRONTEND_INTEGRATION.md). V27/V28 permanecen congeladas:

| Fuente | SHA-256 |
| --- | --- |
| V27 | `52fd5f3eda14fde228e218f127b5e9362c8542dc7e26df7b502ba65061332b9b` |
| V28 | `1227c8261cfcca1263a0b2105bf0dc797c1f59f3b5bdc71225464fc4aa154a5e` |

La inspección de las guardas confirma dos incompatibilidades para un éxito sin nuevos actos:
legal_validar_lote_aceptacion() prohíbe lotes vacíos; legal_idempotencia_insert_guard() exige que
el lote referenciado pertenezca a la transacción que inserta el resultado. Un lote histórico no
sirve para consumir una nueva clave. No se reduce la garantía de replay ni se fabrica evidencia.

## 1. Semántica cerrada del POST

La composición mínima permanece REGISTRATION→REGISTRO/ADMIN_TITULAR y
AUTHENTICATED_PENDING→USO_CONTINUADO para la audiencia del rol persistido, locale es-AR. Scopes y
revisión se resuelven antes de filtrar evidencia. Se exigen los obligatorios aún pendientes en el
POST autenticado; registro exige todos sus obligatorios porque no tiene evidencia previa.

| Entrada tras validar actor/header/DTO | Resultado |
| --- | --- |
| Clave confirmada y fingerprint idéntico | Replay íntegro antes de disponibilidad editorial/freshness |
| Clave confirmada y fingerprint diferente | 409 IDEMPOTENCY_KEY_REUTILIZADA |
| Lista no vacía, sin duplicados y con todos sus actos confirmados canónicamente | Disponibilidad primero; 204 sin nuevos actos antes de freshness |
| Lista vacía, revisión vieja | 409 DOCUMENTOS_LEGALES_DESACTUALIZADOS |
| Lista vacía, revisión actual y obligatorios pendientes | 400 ACEPTACION_LEGAL_INVALIDA / motivo REQUISITO_FALTANTE |
| Lista vacía, revisión actual y sin obligatorios pendientes | 204 sin nuevos actos y resultado idempotente durable |
| Mezcla de actos existentes y nuevos | Revisión actual, todos los obligatorios pendientes y validación de todo lo enviado; insertar sólo nuevos |
| Requisitos/documentos duplicados, aun ya evidenciados | No usar atajo 204; revisión primero, después motivo de duplicado si coincide |

Opcionales no bloquean ni su ausencia exige aceptación. Dedup total compara acto, confirmado=true,
digests y conjunto exacto de documentos; un UUID conocido no acredita el request. En el caso mixto
lo enviado debe pertenecer al conjunto actual; no se anexa a lotes viejos. Ninguna aceptación se
fabrica por herencia y sus fechas no cambian. Un 204 sin actos puede hacer DML de resultado técnico.

La prioridad completa permanece: sesión/actor → formato de header presente → JSON/DTO → clasificación
legacy/parcial/completa y header requerido → fingerprint/replay → disponibilidad → dedup exacto →
revisión → semántica. La excepción de registro con ausencia total y enforcement activo es 428 con
el conjunto público actual; indisponibilidad sigue siendo 503. Los cuerpos 409/428 usan siempre el
perfil público o privado correcto. Sin campos nuevos en request/response ni códigos nuevos.

## 2. Solución SQL elegida: ledger suplementario

Se conserva legal_idempotencia_resultados para REGISTRO y ACEPTACION_LEGAL que insertan actos.
Sus filas, vínculos, completitud de lote y guarda de misma transacción no se modifican. Para
DEDUP/EMPTY se agregan dos tablas; no se reemplaza ni se copia el ledger histórico.

Nombres y forma física previstos para
`V29__resultados_aceptacion_idempotente.sql`:

```sql
CREATE TABLE legal_idempotencia_sin_actos (
    id UUID NOT NULL,
    operacion VARCHAR(30) NOT NULL,
    route_template VARCHAR(200) NOT NULL,
    scope_hmac VARCHAR(64) NOT NULL,
    idempotency_key_hmac VARCHAR(64) NOT NULL,
    fingerprint_hmac VARCHAR(64) NOT NULL,
    hmac_key_version INTEGER NOT NULL,
    user_id BIGINT NOT NULL,
    taller_id BIGINT NOT NULL,
    rol_wire VARCHAR(10) NOT NULL,
    audiencia VARCHAR(20) NOT NULL,
    resultado VARCHAR(10) NOT NULL,
    submitted_revision VARCHAR(71) NOT NULL,
    agregado_observado_id UUID NOT NULL,
    perfil VARCHAR(30) NOT NULL,
    revision_scheme VARCHAR(20) NOT NULL,
    observed_revision VARCHAR(71) NOT NULL,
    referencia_count INTEGER NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_legal_idempotencia_sin_actos PRIMARY KEY (id),
    CONSTRAINT uk_legal_idem_sin_actos_actor UNIQUE (id, user_id, taller_id),
    CONSTRAINT uk_legal_idem_sin_actos_tupla
        UNIQUE (operacion, route_template, scope_hmac, idempotency_key_hmac),
    CONSTRAINT fk_legal_idem_sin_actos_actor FOREIGN KEY (user_id, taller_id)
        REFERENCES users (id, taller_id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_idem_sin_actos_agregado FOREIGN KEY
        (agregado_observado_id, perfil, audiencia, revision_scheme, observed_revision)
        REFERENCES legal_requisito_agregados
        (id, perfil, audiencia, revision_scheme, required_set_revision) ON DELETE RESTRICT,
    CONSTRAINT ck_legal_idem_sin_actos_operacion CHECK
        (operacion = 'ACEPTACION_LEGAL'
         AND route_template = '/api/aceptaciones-legales'),
    CONSTRAINT ck_legal_idem_sin_actos_rol CHECK
        ((rol_wire = 'ADMIN' AND audiencia = 'ADMIN_TITULAR')
         OR (rol_wire = 'USER' AND audiencia = 'USER')),
    CONSTRAINT ck_legal_idem_sin_actos_perfil CHECK
        (perfil = 'AUTHENTICATED_PENDING' AND revision_scheme = 'AGGREGATE_V1'),
    CONSTRAINT ck_legal_idem_sin_actos_hmac CHECK
        (scope_hmac ~ '^[0-9a-f]{64}$'
         AND idempotency_key_hmac ~ '^[0-9a-f]{64}$'
         AND fingerprint_hmac ~ '^[0-9a-f]{64}$' AND hmac_key_version > 0),
    CONSTRAINT ck_legal_idem_sin_actos_revision CHECK
        (submitted_revision ~ '^sha256:[0-9a-f]{64}$'
         AND observed_revision ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_legal_idem_sin_actos_shape CHECK
        ((resultado = 'EMPTY' AND referencia_count = 0
          AND submitted_revision = observed_revision)
         OR (resultado = 'DEDUP' AND referencia_count BETWEEN 1 AND 2048)),
    CONSTRAINT ck_legal_idem_sin_actos_expiracion CHECK
        (expires_at >= completed_at + INTERVAL '24 hours')
);

CREATE TABLE legal_idempotencia_sin_actos_referencias (
    resultado_id UUID NOT NULL,
    aceptacion_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    taller_id BIGINT NOT NULL,
    CONSTRAINT pk_legal_idem_sin_actos_ref PRIMARY KEY (resultado_id, aceptacion_id),
    CONSTRAINT fk_legal_idem_sin_actos_ref_padre
        FOREIGN KEY (resultado_id, user_id, taller_id)
        REFERENCES legal_idempotencia_sin_actos (id, user_id, taller_id) ON DELETE RESTRICT,
    CONSTRAINT fk_legal_idem_sin_actos_ref_aceptacion
        FOREIGN KEY (aceptacion_id, user_id, taller_id)
        REFERENCES legal_aceptaciones (id, user_id, taller_id) ON DELETE RESTRICT
);
CREATE INDEX idx_legal_idem_sin_actos_expira ON legal_idempotencia_sin_actos (expires_at, id);
CREATE INDEX idx_legal_idem_sin_actos_version ON legal_idempotencia_sin_actos (hmac_key_version);
CREATE INDEX idx_legal_idem_sin_actos_ref_actor
    ON legal_idempotencia_sin_actos_referencias (aceptacion_id, user_id, taller_id);
```

Es DDL de decisión para 15F, no una migración ejecutada en 15A. UUID no agrega secuencias. El índice
único de tupla sirve al lookup completo; la aplicación compara además hmac_key_version. La búsqueda
en ambas tablas conserva tipo de origen, no confunde UUID suplementario con BIGINT histórico.

Guardas V29 nuevas, SECURITY INVOKER, con search_path fijo pg_catalog, schema verificado, pg_temp:

| Función / trigger nominal | Obligaciones |
| --- | --- |
| legal_exigir_lock_idempotente_v29(varchar,varchar,varchar,varchar) | READ_COMMITTED y lock exclusivo transaccional de tupla previamente tomado |
| legal_idempotencia_tupla_guard_v29() en INSERT/DELETE de ambos padres | Acreditar lock; INSERT rechaza tupla existente en el otro ledger, incluso vencida; DELETE exige vencimiento |
| legal_idempotencia_sin_actos_insert_guard_v29() | Gate shared, actor/rol y agregado observado actual; completed_at del servidor; no registro ni lote falso |
| legal_idempotencia_sin_actos_ref_insert_guard_v29() | Padre de la transacción actual, resultado DEDUP y evidencia previamente confirmada del mismo actor/tenant; rechazar acto cuyo xmin sea de la transacción actual |
| legal_validar_idempotencia_sin_actos_v29(uuid) y constraint guard diferido | Conteo exacto, shape y agregado actual al confirmar una nueva cabecera; hijo no se agrega tras commit |
| legal_idempotencia_sin_actos_mutation_guard_v29() | Prohibir UPDATE; DELETE sólo vencido con lock y mantenimiento autorizado por ACL |
| legal_idempotencia_sin_actos_ref_delete_guard_v29() | Padre vencido y lock de su tupla; conservar padre completo o eliminarlo en el mismo commit |
| legal_idempotencia_sin_actos_ref_update_guard_v29() | BEFORE UPDATE de sentencia sobre referencias: rechazo incondicional, incluso no-op o cero filas |

Los triggers que acreditan la tupla ejecutan antes de las guardas históricas de INSERT mediante
nombres ordenados explícitamente en 15F. No se cambia el cuerpo de las funciones V27/V28. La
completitud diferida posterior a DELETE retorna si el padre ya no existe; si persiste, exige todas
sus referencias originales. Mantenimiento elimina hijos y padre explícitamente en una transacción,
sin cascada hacia evidencia. UPDATE de ambos tipos de resultado permanece prohibido.

DEDUP guarda referencias a todos los actos enviados y observados; pueden pertenecer a varios lotes
históricos confirmados antes de esta transacción; la guarda rechaza referencias a actos recién
insertados en el mismo commit. No se les exige pertenecer al agregado actual porque el contrato permite revisión vieja
ante dedup total. EMPTY acredita cero referencias y revisión actual; que no queden obligatorios
pendientes requiere ejecutar el evaluador de aplicación, no se infiere de contar FKs. HMAC tampoco
prueba por sí mismo semántica o herencia a PostgreSQL. La autorización HTTP vive en el servicio.

La nueva cabecera recibe completed_at=statement_timestamp() después de locks y observación; no
lo aporta el navegador ni se usa una transacción iniciada antes de esperar como reloj de captura.
El timestamp es del resultado técnico, no aceptadoEn ni hora física del COMMIT. La guarda histórica
continúa copiando su hora de lote. expires_at se deriva de completed_at y TTL configurado; SQL exige
>=24 horas. No se atribuye a un clock wall-clock la garantía de un reloj monotónico de ejecución.

## 3. Unicidad, expiración y rotación entre los dos ledgers

La tupla protegida excluye hmac_key_version. Clave física del advisory lock:

```sql
pg_catalog.hashtextextended(
    pg_catalog.jsonb_build_array(
        'ordenfix:legal-idempotencia:tupla:v29',
        operacion, route_template, scope_hmac, idempotency_key_hmac
    )::text,
    0
)
```

La derivación ocurre en PostgreSQL con bindings, no concatenación ambigua del cliente. Se calculan
los candidatos de todas las claves retenidas, se ordenan los BIGINT físicos por sus ocho bytes
unsigned big-endian y se deduplican locks idénticos. Se conservan todos los candidatos lógicos para
lookup. Colisiones del hash sólo serializan; la comparación de resultado usa la tupla completa.

El coordinador adquiere los locks en sentencias anteriores y después consulta ambos ledgers. La
guarda de INSERT exige el lock ya presente mediante pg_locks/PID/database/key/objsubid y el patrón
transaccional acreditado por V28; el rol no puede adquirir locks de sesión. No se espera por primera
vez dentro del INSERT, evitando atribuir frescura a un snapshot anterior a la espera. El mismo
protocolo se exige al mantenimiento y a cualquier escritor de las tablas después de V29.

Mientras exista una fila, aunque expires_at haya pasado, se conserva replay y se rechaza otro
fingerprint. No se filtra por expires_at en lookup; la garantía se extiende hasta purga. Esto evita
que una fila vencida, todavía UNIQUE, se trate como ausente. El rol de request no obtiene DELETE.
Tras purgar, la clave deja de estar garantizada y puede representar una operación nueva; la
unicidad permanente de evidencia sigue evitando duplicación de actos. Un registro cuyo resultado
ya fue purgado no se reconstruye desde email como falso replay; aplica el alta/validación actuales.

TTL idempotente técnico: valor inicial PT25H, mínimo admitido PT24H. Ambos ledgers usan la misma
política. El plazo de metadata personal es distinto y no tiene default. Para retirar una clave:

1. Distribuir el ring completo a todas las réplicas y obtener ACK; las que no acreditan el ring
   no reciben tráfico.
2. Activar una nueva write-version coordinada y drenar solicitudes que capturaron la anterior.
3. Purgar filas vencidas de esa versión con locks de tupla y comprobar ausencia en ambos ledgers.
4. Recién después del drenaje y ausencia total, retirar el secreto. Un conteo cero sin drenaje no
   impide un commit posterior con la versión antigua.

La purga se hace en lotes de 100 padres como máximo, por expires_at/id con desempate por origen,
locks ordenados y relectura bajo lock antes de borrar. No se mantienen locks de una página al
pasar a otra. El worker sin clave criptográfica puede derivar locks de HMAC ya almacenados; no
necesita descifrar metadata ni conocer secretos HMAC para purgar. El retiro del ring es operación
coordinada de configuración, no un DELETE automático de secretos.

## 4. Permisos reales para locks de actor

Se elige protección global de las PK existentes, sin SECURITY DEFINER ni tablas de roles mágicos:

```sql
CREATE FUNCTION legal_rechazar_update_identidad_cuenta_v29()
RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER
SET search_path TO pg_catalog, public, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'la identidad de cuenta es inmutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_legal_users_identidad_inmutable_v29
BEFORE UPDATE OF id ON users FOR EACH STATEMENT
EXECUTE FUNCTION legal_rechazar_update_identidad_cuenta_v29();
CREATE TRIGGER trg_legal_talleres_identidad_inmutable_v29
BEFORE UPDATE OF id ON talleres FOR EACH STATEMENT
EXECUTE FUNCTION legal_rechazar_update_identidad_cuenta_v29();
REVOKE ALL ON FUNCTION legal_rechazar_update_identidad_cuenta_v29() FROM PUBLIC;
```

DDL ilustrado para public, schema actual soportado; inventario/quoting de un schema verificado
siguen el protocolo existente. Se rechaza UPDATE id incluso si no cambia el valor o no encuentra
filas. No se ejecuta una mutación ficticia ni cambia xmin. JPA normal no actualiza su PK; no se
prohíben updates de nombre/estado que los roles de aplicación ya realizan legítimamente.

Los consumidores reciben UPDATE(id) nominal en users/talleres sólo para FOR SHARE, nunca UPDATE
de role, active, password, token_version o taller_id. Las guardas existentes protegen los grants
lock-only de agregado, lote, actos, cabecera de metadata y punteros. En concreto se requieren
UPDATE(id) en legal_requisito_agregados, legal_aceptacion_lotes y legal_aceptaciones;
UPDATE(lote_id) en legal_aceptacion_metadatos y UPDATE(conjunto_id) en
legal_requisito_conjuntos_actuales. Documentos y metadata cifrada vuelven a bloquear sus padres
dentro de guards SECURITY INVOKER. El padre suplementario también requiere UPDATE(id), protegido
por su mutation guard, si sus referencias toman FOR SHARE. Estos grants no permiten modificar
contenido/estado; 15F verificará cada guarda junto con su ACL. SELECT de columna no concede identidad: actor/taller y
principal persistido se contrastan en el servicio; PostgreSQL acredita FKs y capacidades físicas.

La prueba decisiva ejecuta INSERT lote→guards V28→actos→documentos→metadata→ledger con el rol
restringido y protección de PK. Un FOR SHARE aislado o un helper previo no acredita el grafo entero.
La protección global también rechaza al owner; owner sigue siendo actor de fixture/migración y
nunca se acepta como credencial consumidora. No se crean memberships ni herencia de privilegios.

### Matriz de capacidades

- Lectura de cuenta: SELECT del grafo y snapshots de evidencia/linajes, user/taller nominales;
  INSERT sólo de agregados V28; UPDATE lock-only de agregado, punteros y PK protegidas de actor. No leer
  payload técnico cifrado, password ni tablas idempotentes.
- Aceptación: añade INSERT nominal de lotes/actos/documentos/metadata y ambos tipos de resultados;
  lectura exacta de resultados/referencias y las funciones transitivas necesarias; grants lock-only
  nominales anteriores protegidos por guardas. Ningún INSERT de negocio, DELETE ni UPDATE efectivo
  de contenido/estado de actores, evidencia o resultados.
- Registro: añade INSERT de columnas de talleres/suscripciones/users del apartado 6, siempre
  RETURNING id. No recibe DELETE ni UPDATE de negocio, ni ownership/DDL. El principal de un alta
  proviene de las filas recién creadas, no de IDs enviados en JSON.
- Mantenimiento: SELECT de IDs, tiempos, versión de clave y tupla HMAC; también SELECT nominal
  transitivo del grafo canónico/evidencia requerido por legal_validar_lote_aceptacion() y
  legal_validar_aceptacion() al purgar metadata. No se supone suficiente leer sólo IDs: las funciones
  actuales hacen SELECT * de actos y comparan documentos/snapshots. DELETE de resultados
  vencidos/referencias y UPDATE de columnas de tombstone de metadata según guardas. No modifica
  actos, documentos, usuarios, talleres ni suscripciones y no necesita leer texto personal.

Purga de metadata: bloquear primero la cabecera legal_aceptacion_metadatos FOR UPDATE y después
actualizar hijos cifrados, con orden estable de lotes. El trigger del hijo vuelve a tomar la
cabecera; invertir ese orden entre workers produciría ciclos. 15O probará la purga hasta COMMIT
con el rol final, incluyendo todos sus constraints diferidos. No se concede lectura de ciphertext
por comodidad ni se requiere descifrar IP/UA para mantener retención.

15F congelará los grants por tabla/columna/función y secuencias acreditadas por la prueba. Toda
función transitiva invocada por un trigger pertenece al inventario de EXECUTE; no usar GRANT ALL
ON ALL FUNCTIONS/TABLES ni una credencial owner para completar un permiso faltante.

## 5. Orden y coherencia de las operaciones

Orden único de adquisición para los nuevos consumidores:

1. Locks idempotentes ordenados cuando corresponda; replay se resuelve tras preflight/actor válido,
   antes de exigir un catálogo editorial disponible.
2. Gate editorial compartido para una nueva observación/aceptación.
3. Lock advisory de actor legal: compartido para lecturas, exclusivo para escrituras.
4. Fila talleres FOR SHARE y luego users FOR SHARE, contrastando pertenencia, rol, active,
   tokenVersion y taller.activo. No sustituir por FOR KEY SHARE, que permite cambiar esos valores.
5. Punteros ordenados por ordinal de scope; store/replay, hidratación y filas de negocio legales.

El lock de actor usa namespace distinto `ordenfix:legal-actor:v1`, tallerId y userId codificados
como array JSON en PostgreSQL y hashtextextended seed 0. Toda escritura legal futura de ese actor,
incluidos actos en contextos de fases posteriores, debe respetarlo para sostener observaciones
coherentes. La purga técnica no cambia evidencia de satisfacción/historial.

Al resolver un replay confirmado no se obliga a tomar gate editorial ni a reconstruir pendientes;
se estabiliza actor/taller y acredita resultado durable/contraseña/estado según operación. Esa
rama no adquiere después el gate, para evitar inversión de locks. Si no hay resultado, se sigue
el orden normal. Autenticación previa a estos locks es validación de identidad; no retiene row locks.

Bajo READ_COMMITTED, el lock shared de actor impide un escritor legal concurrente mientras se
leen evidencia/linajes, count y página. Gate shared mantiene fija la frontera editorial. No se
presentan dos SELECT independientes como snapshot estable sin estos protocolos. Mutaciones de
cuenta actuales respetan sus row locks; timeouts/deadlocks son fallos cerrados sin auto-retry.
La nueva secuencia no obliga a reordenar locks de procesos legacy sin evidencia y pruebas propias.

## 6. Alta JDBC y efectos después del commit

| Tabla | Columnas explícitas de alta | Paridad que debe conservarse |
| --- | --- | --- |
| talleres | nombre, email_contacto, telefono, activo, created_at, updated_at | Activo, secuencia_orden=0, mostrar_en_resumen=true; año/datos de cobro nulos |
| suscripciones | taller_id, plan, estado, fecha_inicio, fecha_fin_trial, created_at, updated_at | FREE/TRIAL explícitos, reparaciones_mes=0; valores MP/consumo futuro nulos |
| users | username, password BCrypt, email, role, active, email_verificado, token_version, taller_id | ADMIN único, active=true, email_verificado=false, token_version=0 |

Las PK son GENERATED BY DEFAULT AS IDENTITY: talleres_id_seq, suscripciones_id_seq y users_id_seq.
La prueba completa aprobó sin USAGE de secuencias: 24 aserciones verifican ausencia de ese permiso
en seis secuencias y cuatro roles del fixture. INSERT con IDENTITY y RETURNING no necesita
nextval explícito ni ese grant. Tablas legales históricas también contienen IDENTITY. Los nuevos
consumidores no reciben permisos de secuencia para estas inserciones; 15F inventariará la topología
sin ampliar privilegios históricos de otros consumidores.

Fecha de inicio usa LocalDate.now(clock); Clock de aplicación actual UTC y trialDias actual 14.
created_at/updated_at de taller/suscripción no tienen default SQL: escribir explícitamente la
semántica actual del auditor JPA (LocalDateTime.now en zona local JVM), con valores observados de
forma coherente para el alta. Unificar auditoría a UTC sería otro cambio, no se oculta aquí.

Límites físicos actuales: nombre taller/email_contacto 120, teléfono 20, username 50, password/email
usuario 255. El DTO histórico no limita email a 120 aunque se copia al taller. No truncar ni cambiar
normalización/case de email: una entrada incompatible debe fallar con rollback. 15M documentará
cualquier validación anticipada que sustituya ese error; este corte no cambia el DTO legacy.

El writer legal usa un único manager JDBC, una conexión y REQUIRES_NEW/READ_COMMITTED exterior.
Agregado, taller, suscripción, usuario, evidencia, metadata e idempotencia comparten commit. No llama
fachadas REQUIRES_NEW anidadas ni consulta JPA para ver filas no confirmadas.

La identidad durable se entrega a la política 15K después de confirmar y liberar el pool legal.
Replay se localiza por userId/tallerId guardados, incluso si cambió email; contraseña y estado
actuales se revalidan y se emite JWT nuevo con claims actuales. No se guarda JWT/body/password en
el ledger. Cambio de estado/contraseña produce el error de autenticación vigente sin recrear cuenta.

La bienvenida sólo se intenta para alta nueva después del commit. Crear/inutilizar auth_tokens
requiere otra transacción explícita; el envío ocurre después de confirmar el token y sin reutilizar
el EntityManager ya confirmado de afterCommit. Probar verificarEmail con el token realmente
persistido. Si ese efecto falla, no deshacer la cuenta; transporte/outbox/entregabilidad quedan en
la fase de email. No reenviar bienvenida automáticamente por replay.

## 7. Punto exacto de enforcement

Se elige DefaultPointcutAdvisor + MethodInterceptor con bean ROLE_INFRASTRUCTURE en los handlers
MVC nominales, y orden AuthorizationInterceptorsOrder.JSR250.getOrder()+1. En Spring Security local
7.0.5 es 401. @EnableMethodSecurity actual usa offset 0, prePostEnabled=true y no habilita Secured/JSR250.
Las anotaciones existentes de autorización son @PreAuthorize de clase y método.

Se acredita cadena aplicable [200,401]: autorización previa, decisión legal y luego negocio.
Los advisors de resultado/postautorización no sustituyen la autorización previa. Si cambia la
topología (offset, nuevos advisors o autorización posterior), revisar y probar el orden antes de
habilitar el gate. No duplicar expresiones de roles en el advisor ni reordenar JwtFilter.

El matcher usa método y plantilla exacta del handler, contextPath retirado una vez y configuración
nominal de excepciones. Un USER con pendientes recibe 403 en ruta ADMIN antes de cualquier lookup
legal, incluso si legales está caído. Una ruta exceptuada conserva @PreAuthorize y tenant. La
prueba de 15A usa Spring real y un advisor de prueba: no implementa ni acredita todavía HTTP 15N.

Conservar excepciones contractuales de documentos/requisitos/aceptaciones, auth/email aplicables,
POST /api/pagos/suscripcion/cancelar y GET /api/export/excel. No exceptuar automáticamente perfil ni
suscripción; el frontend futuro debe poder resolver 428 sin esas consultas. No inventar rutas de
baja/logout/cierre que no existen ni ampliar public/**. El gate permanece apagado hasta disponer
de todas las vías aplicables de salida/datos y de un frontend compatible verificado en staging.

## 8. Flags y configuración cerrados

Todos los flags nuevos ausentes significan false. Sólo se aceptan literales exactos true/false,
sin trim, aliases ni coerción de mayúsculas; otro valor presente falla startup. La validación
central no modifica los parsers de flags públicos históricos.

| Propiedad bajo ordenfix.legal. | Habilita | Dependencias de arranque |
| --- | --- | --- |
| account-read.enabled | GET requisitos e historial y frontera de lectura | Sus tres credenciales dedicadas |
| account-acceptance.enabled | POST privado y escritor | account-read=true y su configuración propia |
| registration-consent.enabled | Rama legal de register | Su frontera/keys/retención; no exige GET públicos en rollout aditivo |
| registration-enforcement.enabled | Alta sin bloque legal→428 | registration-consent y ambas capacidades públicas de documentos/requisitos |
| account-enforcement.enabled | Advisor legal de negocio | account-read y account-acceptance |
| account-maintenance.enabled | Worker interno de retención | Credenciales propias; independiente de HTTP |

Cada frontera tiene exactamente jdbc-url, username y password bajo account-read, account-acceptance,
registration-consent o account-maintenance, respectivamente. Driver PostgreSQL fijo y recursos
limitados por consumidor. No copiar el Environment entero ni heredar spring.datasource, roles
públicos/editoriales o secretos ambientales. Lectura no carga keys de escritura.

Configuración adicional de escritores:

- `ordenfix.legal.idempotency.active-write-version`: entero positivo dentro del ring.
- `ordenfix.legal.idempotency.keyring.<version>`: Base64 canónico de 32 bytes; 1–8 claves distintas
  retenidas. No retirar una clave con filas retenidas. Agotar el límite bloquea la siguiente rotación,
  no elimina claves para permitirla.
- `ordenfix.legal.idempotency.result-ttl`: Duration positiva >=PT24H, inicial PT25H.
- `ordenfix.legal.account-metadata.active-write-version` y `.keyring.<version>`: AES-256-GCM,
  claves Base64 de 32 bytes, 1–8 versiones. Independientes de HMAC/JWT/credenciales de equipos.
- `ordenfix.legal.account-metadata.retention`: Duration positiva obligatoria, sin default legal;
  validar overflow y que el vencimiento sea posterior a captura. Tests usan una retención sintética.
- `ordenfix.legal.account-metadata.trusted-proxy-cidrs`: lista explícita, default vacía; sólo si
  remoteAddr pertenece a un proxy confiable se recorre X-Forwarded-For desde el extremo confiable.
  Primera dirección no confiable de derecha a izquierda es la fuente; cadena inválida no se acepta
  como evidencia. Sin proxy confiable, usar remoteAddr e ignorar headers aportados por el cliente.

La configuración válida no certifica retención profesionalmente aprobada ni autoriza entorno real.
Mantenimiento no requiere secretos de descifrado. Metadata no se lee por HTTP, UA es opcional y
se limita a 512 code points antes de cifrar; IP es obligatoria y nunca se guarda en claro.

### Matriz register

| Elementos legales | Capacidad | Enforcement | Resultado |
| --- | --- | --- | --- |
| Tres ausentes realmente | Cualquiera | false | Legacy |
| Tres ausentes | true | true | 428 con set público actual, o 503 si indisponible |
| Parcial | Cualquiera | Cualquiera | 400; prioridad de header/shape contractual |
| Completo válido en forma | false | false | 503, sin crear cuenta ni descartar evidencia |
| Completo | true | Cualquiera permitido | Flujo legal y errores/replay normales |

Header/property presente con null, cadena vacía o lista vacía nunca se convierte en «ausente».
Header inválido presente se procesa antes del JSON conforme al contrato. Claves duplicadas/headers
repetidos no se normalizan a un valor arbitrario. La matriz de presencia se evalúa antes de perder
esa información al convertir a un record DTO. Flags inválidos/dependencias faltantes fallan startup,
no habilitan una combinación fuera de la tabla.

Con capacidades apagadas no se registran pools/endpoints nuevos. Permanecen la validación de flags
y el adaptador del register existente necesario para no ignorar evidencia. Encender un flag en un
test no equivale a activar producción. El advisor de cuenta sigue condicionado además al rollout
operativo de vías de salida; no se declara resuelto por encontrar un mapping con el nombre esperado.

## 9. Presupuestos y capacidad

Valores elegidos para implementar; su eficacia se acreditará en cada corte. No son mediciones ni SLA.

| Consumidor | Operación exterior | Transacción | Pool máximo |
| --- | ---: | ---: | ---: |
| Lectura privada/historial | 15 s | 15 s | 2 |
| Aceptación | 20 s | 20 s | 2 |
| Registro | 30 s | 25 s | 2 |
| Mantenimiento | 30 s | 30 s | 1 |

Todos: statement/socket 5 s; editorial y actor/graph 1 s; borrow/validation/connect/login/cancel
1 s; minIdle 0; fetch/batch 32. Reserva idempotente 5 s acumulados para todo el ring. Cada espera
se recorta al remanente monotónico; SQLSTATE/causa/resultado se conserva internamente y se traduce
sin leaks. Registro inicia el reloj antes de BCrypt y pool; incluye la sesión poscommit, no promete
preempción de CPU. Email externo no retiene una conexión legal ni condiciona el resultado durable.

Límites estructurales por scope: 256 requisitos, 128 documentos distintos, 16 documentos por
requisito, 1 MiB por Markdown y 16 MiB de Markdown expandido; afirmación 1000 code points/4000 bytes.
Agregado 1–8 scopes: como máximo 2048 requisitos, 1024 UUID documentales y 128 MiB expandidos antes
de filtrar. Son techos de defensa, no una afirmación de combinación editorial válida ni de heap.
La política mínima productiva sólo activa un scope en cada perfil actual.

Reader: consultar cabeceras/tamaños antes de texto y sentinela antes de mapear la fila excedente;
no devolver un prefijo. Historia paginada por 1–100 actos, batch de documentos sólo para IDs de esa
página. Linajes y evidencias para satisfacción se acotan por keys/intervalos pertinentes: batch 32,
4096 versiones como máximo por intervalo y 65536 filas de linaje/evidencia en una observación,
con sentinela +1. Superarlos produce indisponibilidad explícita y exige ampliar/verificar capacidad
en otro corte; no omitir versiones intermedias ni atribuir false a una cadena truncada. Los 128
UUID de una publicación no son el máximo de versiones históricas almacenables.

POST: 2048 actos y 16 documentos por acto; cuerpo JSON máximo 8 MiB, profundidad 32, 300000 tokens,
strings con máximo defensivo 1 MiB antes de validación tipada y nombres de propiedad 256. Estos
límites de operación no cambian LegalManifestLimits ni el parser editorial de 1 MiB. Antes de
crear listas/cifrar/DML, validar IDs, digests, estructura y límites del DTO. Un exceso no se
convierte en aceptación parcial. No registrar contraseña/body ni hashes auxiliares de contraseña.

## 10. Compatibilidad V29 y orden ajustado de cortes

V29 agrega tablas, funciones/triggers e índices; no cambia bytes V27/V28 ni hace backfill. Los
triggers nuevos sobre el ledger V27, users y talleres cambian topología acreditada. Mantener
verificación estricta para V28 y agregar inventario V29 explícito; no aceptar cualquier latest>=28.
La configuración sólo acepta fingerprints/checksums/topología correspondientes al target concreto.

15F debe probar V1→V29 limpio y V28→V29 con evidencia histórica genuina SCOPE_V1/AGGREGATE_V1 y
ledger previo, preservando bytes/IDs/fechas/xmin de esa historia. CLI editorial/importador y lectores
12–14 mantienen sus permisos; no adquieren tablas/funciones nuevas por ampliaciones genéricas.
Los tests históricos target V27/V28 siguen fijados; sólo las suites latest se adaptan nominalmente.

La protección de PK es requisito del lector privado que mantiene locks de actor. Por ello se
adelanta 15F antes de 15C, sin renombrar los cortes: A → B → F → C → D → E → G → H → I → J → K → L →
M → N → O → P → Q. F ya no depende de las regresiones C–E aún inexistentes; acredita 12–14 y el
protocolo 15A. Luego C–E nacen directamente sobre V29. La reordenación evita conceder temporalmente
UPDATE(id) sin la guarda que lo vuelve seguro. El siguiente corte sigue siendo 15B.

## 11. Evidencia focal y límites de esta decisión

Verificación final con Java 21.0.10, Maven 3.9.11 y PostgreSQL 16.14 en contenedor efímero,
Flyway target 28 explícito. Comandos ejecutados secuencialmente desde el backend:

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw -Dtest=LegalAcceptanceAdvisorOrderFeasibilityTest package
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
  ./mvnw -Dit.test=LegalAcceptanceProtocolFeasibilityIT failsafe:integration-test failsafe:verify
```

| Suite final | Tests | Fallos / errores / omitidos | Evidencia |
| --- | ---: | --- | --- |
| LegalAcceptanceAdvisorOrderFeasibilityTest | 8 | 0 / 0 / 0 | Cadenas [200,401], rol antes de lookup incluso indisponible, exención conserva autorización |
| LegalAcceptanceProtocolFeasibilityIT | 7 | 0 / 0 / 0 | Control positivo owner, rechazo histórico/vacío, permisos completos, guardas PK, atomicidad de alta |

XML de ambas suites inspeccionados; no se suman reportes viejos. package terminó 17:42:07 -03:00
(13.443 s de Maven); PostgreSQL terminó 17:43:24 -03:00 (39.593 s). Los siete casos PostgreSQL
incluyen rollback deliberado en diez etapas de alta, doce intentos UPDATE de identidad rechazados
(owner/aceptador × users/talleres × real/no-op/cero filas), 24 controles de falta de USAGE y commit
completo bajo roles sin ownership, DDL ni inserción de negocio para el aceptador. La prueba sin
guarda demuestra que UPDATE(id) por sí solo permitiría cambiar identidades reales.

La primera ejecución AOP detectó que Spring registra wrappers Advisor y MethodInterceptor; se
corrigió la aserción del inventario y se mantuvo la exigencia de una sola cadena efectiva [200,401].
La suite completa volvió a pasar. PostgreSQL pasó inicialmente y se repitió después de quitar los
grants de secuencias; volvió a pasar sin ellos. No hubo fallo de código productivo.

Ambos JAR producidos excluyen las clases de estos tests y conservan bytes SHA-256 V27/V28. El
árbol de fuentes productivas, configuraciones y migraciones no cambia. Revisión independiente de
SQL/permisos, contrato y auth integrada; plan y diseño reflejan el orden ajustado.

El fixture de alta prueba viabilidad SQL, constraints y atomicidad; usa fecha fija, contraseña y
bytes HMAC/ciphertext sintéticos, omite auditoría de aplicación y no ejecuta Clock/BCrypt/JPA/JWT ni
email. La paridad completa de 15L/15M sigue pendiente. Las guardas de identidad sólo existen en la
BD de prueba; V29 no fue aplicada. Las ACL del fixture acreditan la cadena SQL, no autorización por
principal HTTP ni el inventario definitivo de todos los consumidores.

No se ejecutó clean verify por este corte de pruebas/documentación; V29, auth/registro y enforcement
tendrán gates transversales al implementarse. Estos tests tampoco acreditan cifrado, retención,
capacidad, concurrencia del protocolo V29 ni transporte email aún no implementados.
