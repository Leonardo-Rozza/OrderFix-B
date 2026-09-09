# Fotos privadas — recorrido operativo

Fecha: 2026-09-09. Baseline backend `e10070b`. Implementa el contrato del plan frontend homónimo y BACKEND-HANDOFF 4/Tarea 9. V27/V28/V29 permanecen byteidénticas; evolución mediante V30. Sin push.

## Apertura nominal del corte backend

Producción nueva: `photos/PrivatePhotoDtos.java`, `PrivatePhotoException.java`, `PrivatePhotoService.java`, `PrivatePhotoController.java`, `PrivatePhotoConfiguration.java`, `PrivatePhotoImageValidator.java`; integración `legal/manifest/persistence/LegalPrivatePhotoOperations.java`; migración `db/migration/V30__fotos_privadas_contextuales.sql`.

Producción modificada: `LegalAcceptanceCommand.java`, `LegalAcceptanceCommandValidator.java`, `LegalIdempotencyFingerprint.java`, `TipoOperacionIdempotenteLegal.java`, `LegalIdempotencyResultStore.java`, `LegalAcceptancePrivilegeVerifier.java`, `LegalV29AcceptanceSchemaVerifier.java`, `ReparacionServiceImpl.java`, `exceptions/GlobalExceptionHandler.java`. La acreditación nominal V30 se implementa en `LegalPrivatePhotoSchema.java`.

Se adapta `LegalAcceptanceCommandTest` para acreditar que ambos constructores internos siguen sin acceso público y el comando conserva campos inmutables.

Las pruebas `PostgresMigrationIT`, `LegalPrivateRequirementsReadServiceIT` y `LegalAcceptanceHistoryReaderIT` esperan V30 al migrar hasta la última versión. `LegalV29AcceptanceSchemaVerifierIT` fija V29 para su inventario congelado y esquema alternativo; una prueba separada acredita V30 con los consumidores anteriores en `public`.

Pruebas nuevas: `PrivatePhotoImageValidatorTest.java`, `PrivatePhotoControllerTest.java`, `LegalPrivatePhotoOperationsIT.java`, `LegalPhotoFingerprintTest.java`, `exceptions/PrivatePhotoDeletionErrorTest.java`. También se añaden el puerto, excepción, adapter y configuración bajo `photos/storage`, con `CloudinaryPrivatePhotoStorageTest` y `PrivatePhotoStorageConfigurationTest`. El laboratorio opt-in `PrivatePhotoBrowserE2E` ejecuta los cuatro casos frontend y verifica la persistencia. README, este plan y `docs/operations/private-photos.md` documentan su operación.

## Decisiones vinculadas al flujo

- Bytes por PUT al backend, máximo 8.000.000, JPEG/PNG, comprobación de tipo real, dimensiones y SHA-256; nunca URLs o permisos del proveedor en JSON.
- `photos.private.enabled` apagado por defecto. Activación requiere rol JDBC propio (`photos.private.jdbc-url`, `username`, `password`), keyrings legales existentes, retención de fotos explícita (`photos.private.retention`) y almacenamiento privado disponible. Intenciones expiran a los 15 minutos.
- Política local AUTHENTICATED_PENDING = USO_CONTINUADO + ATESTACION_FOTOS. La política general permanece mínima. Se reutilizan evidencia, snapshots, metadata cifrada y ledger canónicos; una intención registra la confirmación contextual de su actor por reparación, sin copiar textos legales.
- Operación idempotente nominal ATESTACION_FOTOS y fingerprint de reparación/manifiesto/confirmación. Las rutas previas conservan exactamente sus proyecciones.
- ADMIN/USER activos del taller pueden consultar/eliminar. Sólo el creador puede subir/finalizar. Actor/taller se revalidan en transacciones propias; no se mantiene una transacción SQL durante llamadas al proveedor.
- Identidad aleatoria del objeto se persiste antes de upload. Lease acotado, recuperación por find y limpieza reintentable de expiradas/fallidas/eliminaciones pendientes. No se pierde el identificador de un objeto por un fallo o ACK incierto.
- URLs legacy se mantienen sólo en lectura; los writes no pueden introducir referencias nuevas cuando el flag está activo. El borrado de reparación debe impedir perder objetos privados aún presentes.
- Sin configuración ni contacto Cloudinary real en pruebas locales. El fake de puerto acredita flujo/aislamiento, no ACL del proveedor; staging pendiente de configuración explícita.

## Contrato JSON

GET requisitos devuelve `{locale,requiredSetRevision,requisitos,limites:{maxBytes:8000000,mimeTypes:[image/jpeg,image/png],maxFotos:100}}`. Los requisitos/documentos usan el contrato privado canónico existente. POST intención recibe nombre,mimeType,bytes,sha256,momento,requiredSetRevision,aceptacionesLegales y `atestacion:{tipo:AUTORIZACION_DATOS_CLIENTE,alcances:[FOTOS],confirmada:true}` con Idempotency-Key UUIDv4.

Intención: `{id,estado,expiresAt,upload:{method:PUT,url:<relativa>}|null}`. POST 201/replay200, GET200, PUT200. Finalización201/repetida200 devuelve foto `{id,momento,mimeType,bytes,sha256,createdAt}`; GET fotos array200, contenido bytes no-store, DELETE204 sólo tras eliminación. Estado interno/terminal nunca expone objectKey/assetId/version del proveedor.

Errores seguros `{status,error,code,message,details}`: 428 ATESTACION_REQUERIDA, 409 REQUISITOS_LEGALES_DESACTUALIZADOS con details.requisitosActuales, 400 FOTO_INVALIDA, 404 FOTO_NO_ENCONTRADA, 403 FOTO_ACTOR_NO_PERMITIDO, 401 FOTO_ACTOR_NO_VALIDO, 409 IDEMPOTENCY_KEY_REUTILIZADA/OPERACION_EN_PROGRESO/INTENCION_FOTO_EXPIRADA/FOTO_LIMITE_ALCANZADO, 503 FOTOS_PRIVADAS_NO_DISPONIBLES. Resultado incierto de transacción/cierre siempre503.

## Validación prevista

Pruebas focales de contrato, fingerprint, contenido y PostgreSQL con rol restringido; regresiones legales/roles/registro y gate integral por modificación transversal. Maven se ejecuta de forma serial para no compartir escrituras de `target`. No se acredita proveedor real ni staging con el laboratorio local. Los resultados y el alcance de la revalidación se registran abajo.

## Ajustes de cierre del contrato antes del gate

Máximo 100 intenciones activas o pendientes por reparación, serializadas por su fila, para mantener consultable la lista. JPEG y PNG son los formatos decodificados en v1; WebP queda rechazado explícitamente. El guard referencial de borrado es la única función nueva SECURITY DEFINER: SELECT de tabla calificada, search_path fijo, propietario acreditado contra las tablas/migraciones, sin SQL dinámico ni grants EXECUTE al consumidor. Permite DELETE histórico sin conceder lectura de fotos y bloquea perder objetos pendientes. Ese constraint exacto se traduce a 409 FOTOS_PRIVADAS_PENDIENTES: «Eliminá primero las fotos privadas de la reparación e intentá nuevamente».

## Cierre local del corte — fotos privadas

Fecha: 2026-09-09. El backend conserva Cloudinary y ahora transporta JPEG/PNG por rutas
propias autenticadas. Crea una intención y su confirmación contextual canónica antes de transmitir
bytes; recupera la misma intención ante un ACK incierto y asocia una sola foto al finalizar. El
frontend conserva la orden y los archivos seleccionados, tanto en alta nueva como con equipo
existente. Se retira la subida unsigned del navegador. La galería descarga sólo la foto abierta
y libera el Blob al cerrar, cambiar de reparación o sesión.

El laboratorio de fotos aprobó el **2026-09-09 a las 12:21:53 -03:00**: un harness JUnit,
**cuatro casos Playwright**, sin reintentos ni omisiones, escritorio y 320 px. PostgreSQL 16.14
acreditó cuatro intenciones/fotos eliminadas y sus vínculos con actor, taller, reparación y
aceptación canónica. La primera carga de cada archivo conservó bytes reales en el almacenamiento
local y respondió 503; el reintento recuperó el objeto sin repetir el alta de orden ni intención.
Titular/empleado obtuvieron 200, otro taller 404, anónimo 403; DELETE confirmó 204 y la lectura
posterior 404. Se verificaron deltas exactos y ausencia de cambios en las tablas de pagos.

Validación complementaria: **566 Vitest + 42 controles de publicación**, typecheck, lint y build.
Las 50 pruebas del adapter/configuración comprueban transporte contra un servidor controlado,
identidad, límites, firmas y errores seguros; no acreditan una cuenta Cloudinary. Los tests de
PostgreSQL cubren rechazo de confirmación/revisión/manifiesto, pertenencia, replay, recuperación y
protección del borrado de reparaciones. La limpieza rota lotes de diez para que los fallos antiguos
no impidan procesar filas posteriores; una prueba conserva once fallos permanentes y acredita
avance y vuelta para reintentar, sin modificar V30.

El `clean verify` completo ejecutó **7.413 unitarias y 1.350 pruebas de integración**. Las unitarias
aprobaron; integración terminó con cuatro fallos y dos errores en cinco suites: tres expectativas
de última versión 29, dos comprobaciones V29 que migraban implícitamente a V30 y un rechazo de
archivo cambiante durante la preparación del laboratorio de capacidad. No se presenta ese comando
como verde. Se corrigieron únicamente las expectativas/fixtures y se añadió una comprobación
independiente de los cinco consumidores previos sobre V30. La revalidación focal de las cinco
suites completas aprobó **85 IT y 6 unitarias** el **2026-09-09 a las 13:02:37 -03:00**, sin fallos,
errores ni omisiones (4:10 min). La suite de capacidad pasó sin cambiar código ni relajar el guard.
La causa exacta de aquel incidente no se acreditó: el lector también compara atributos de los
directorios ancestros, incluido el tmp compartido. Queda registrado como incidente no reproducido.
La evidencia final combina el integral y esa revalidación; no se repitió otro clean verify completo
tras los ajustes exclusivos de pruebas. La prueba nueva eleva el inventario a **1.351 IT**.
Los **14 recorridos anteriores** de registro, empleados, reparación/presupuesto, cobros manuales
y resumen volvieron a aprobar el **2026-09-09 a las 13:04:11 -03:00**: un harness JUnit, cero
fallos/errores/omisiones, 71,58 s de suite. Ese laboratorio conserva deliberadamente su fixture
V29; el laboratorio de fotos y la prueba de compatibilidad usan V30. Son 18 recorridos locales
complementarios, no una acreditación del despliegue ni de la cuenta Cloudinary.

Se corrigieron durante la validación una referencia de compilación al driver de alcance runtime,
una URL de Testcontainers con parámetro ajeno al contrato, el esquema predeterminado de Hibernate
en el laboratorio y el selector de su botón Equipo. La suite frontend necesitó conservar el store
de autenticación en un mock parcial del detalle. Se actualizaron las expectativas del número de
constructores internos y de la última migración; las comprobaciones de inmutabilidad y evidencia
histórica se mantuvieron. Los intentos fallidos no cuentan como aprobados.
V27/V28/V29 conservaron sus hashes; V30 es la única migración nueva. Los 77 archivos no versionados
ajenos se preservaron. Un commit por repositorio integra implementación, pruebas y documentación,
sin push.

**El flujo de fotos queda verificado localmente.** Sigue pendiente configurar la cuenta Cloudinary
de prueba y acreditar acceso anónimo denegado a original/derivados, borrado remoto real y operación
de retención en staging. Las referencias legacy requieren su inventario y tratamiento explícito;
este corte no las privatiza ni elimina del proveedor. También continúan los pendientes de Confianza
y cuenta y Operación real de la lista finita de salida. No se habilita el lanzamiento con esta prueba.

Comandos y evidencia local de esta ejecución:

- `JAVA_HOME=<Java 21> ./mvnw -B clean verify`: `backend-clean-verify-2.log` y `integral-2-summary.json`.
- `JAVA_HOME=<Java 21> ./mvnw -B -Dtest=LegalAcceptanceCommandTest -Dit.test=PostgresMigrationIT,LegalAcceptanceHistoryReaderIT,LegalPrivateRequirementsReadServiceIT,LegalPublicRequirementsHttpCapacityIT,LegalV29AcceptanceSchemaVerifierIT verify`: `backend-final-focused.log`.
- Desde frontend, Node 24.14.0: `npm run test:e2e:photos-real` (`browser-3.log`) y `npm run test:e2e:registration-real` (`registration-regression.log`), con Java 21 y Docker activo.
- Frontend: `npm test`, `npm run typecheck`, `npm run lint`, `npm run build` y lint/TypeScript del E2E. El build conserva la advertencia de chunk mayor a 500 kB; no es un build de publicación acreditado.

Logs en `/private/tmp/ordenfix-private-photos/`; la documentación conserva sus resultados aunque
ese directorio temporal deje de existir. Los comandos de laboratorio requieren recursos efímeros;
no contienen credenciales reales ni deben apuntarse a una base compartida.
