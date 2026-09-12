# Operación de fotos privadas

Describe la implementación del corte del 9 de septiembre de 2026; el gate y la configuración de staging se registran por separado en el plan de fotos privadas.

## Activación y configuración

`photos.private.enabled=false` es el estado predeterminado. No construye el servicio, el pool ni el adapter Cloudinary; los endpoints privados devuelven 503 `FOTOS_PRIVADAS_NO_DISPONIBLES`. Apagarlo también detiene su tarea de limpieza y hace inaccesibles por esta API los archivos privados existentes, sin borrarlos. El almacenamiento y las credenciales se configuran sólo en el backend; el navegador envía bytes autenticados al backend.

Las siguientes claves deben estar presentes antes de activar. Los marcadores no son valores utilizables:

```properties
photos.private.enabled=true
photos.private.jdbc-url=jdbc:postgresql://<host>:5432/<database>?sslmode=verify-full&sslrootcert=<ruta-certificado>
photos.private.username=<rol-privado-de-fotos>
photos.private.password=<secreto-del-rol>
photos.private.retention=<duracion-ISO-8601-aprobada>
photos.private.cloudinary.cloud-name=<cloud-name>
photos.private.cloudinary.api-key=<api-key-servidor>
photos.private.cloudinary.api-secret=<api-secret-servidor>
ordenfix.legal.idempotency.keyring.<version>=<base64-canonico-de-32-bytes>
ordenfix.legal.idempotency.active-write-version=<version>
ordenfix.legal.account-metadata.keyring.<version>=<otro-base64-canonico-de-32-bytes>
ordenfix.legal.account-metadata.active-write-version=<version>
ordenfix.legal.account-metadata.retention=<duracion-ISO-8601-aprobada>
```

### Credenciales del entorno y comprobación de septiembre

`application.properties` vincula `CLOUD_NAME`, `API_KEY` y `API_SECRET` con las tres
propiedades `photos.private.cloudinary.*`. Los valores predeterminados son vacíos:
el almacenamiento deshabilitado no necesita credenciales y la activación exige que
sean válidas. Definir sólo propiedades llamadas `API_KEY=${API_KEY}` no configura
el adapter, porque éste lee los nombres canónicos de fotos privadas.

Para configurar los valores se pueden usar variables de entorno o un archivo
externo importado mediante `SPRING_CONFIG_IMPORT`, como indica el README. Un archivo
`application-secret.properties` ignorado por Git no se carga por su mera presencia;
también está excluido del JAR. Para despliegue, usar secretos del servidor. Evitar
colocar archivos con credenciales en los recursos que se empaquetan. Esta corrección
no cambia perfiles ni importa automáticamente otros secretos locales.

La comprobación de las credenciales añadidas por el titular del proyecto devolvió
HTTP 200 y `status=ok` en `GET /ping` de Cloudinary el 2026-09-12, usando el cliente
HTTPS del sistema. No se registraron valores, cabeceras de autenticación ni cuerpo
completo de respuesta. El primer intento con Python falló en la conexión y no se
considera una validación. No se subió, leyó ni eliminó ningún asset.

Esta prueba acredita autenticación de Admin API y conectividad; no acredita por sí
sola permisos efectivos de carga/borrado, privacidad de archivos, configuración de
backups ni el recorrido completo de fotos. El flag privado y los demás requisitos
de esta página conservan su estado; no se activa el servicio con sólo estas claves.

Verificación local adicional: `PrivatePhotoStorageConfigurationTest`, 9 pruebas
aprobadas con Java 21, sin fallos ni omisiones. Se comprobó que el diff versionado
no contiene ninguno de los tres valores reales y que el archivo secreto sigue
ignorado. No se modificó dicho archivo ni se copiaron sus secretos; se conserva
la carga explícita por entorno/importación. Commit sin push.

Referencia: [Cloudinary Admin API, ping](https://cloudinary.com/documentation/admin_api#ping).

El indicador admite `true` o `false` exactos. La URL debe ser PostgreSQL TCP, sin fragmento; sus únicos parámetros admitidos son `sslmode`, `sslrootcert`, `sslcert`, `sslkey` y `sslpassword`. Usuario/contraseña no van en la URL. No hay fallback a credenciales de aplicación. Cada keyring contiene entre una y ocho versiones positivas, con claves distintas de 32 bytes; HMAC y AES tampoco pueden coincidir entre sí. Mantener las versiones históricas necesarias según la política de rotación legal existente. Usar fuentes de propiedades enumerables con los nombres canónicos anteriores; no asumir que cualquier alias de variable de entorno será descubierto como entrada del keyring.

`photos.private.retention` debe representar segundos enteros, superar 15 minutos y no superar 315.360.000 segundos. Se guarda el vencimiento por intención al crearla; cambiar la configuración no recalcula filas existentes. La retención AES de metadata legal es independiente. `ordenfix.legal.idempotency.result-ttl` es opcional: predeterminado `PT25H`, mínimo 24 horas; no es el plazo de retención del archivo ni el vencimiento de la intención.

El pool privado tiene como máximo dos conexiones y no es el DataSource de JPA. Las transacciones SQL propias son `REQUIRES_NEW`/`READ_COMMITTED`; las operaciones del proveedor ocurren entre transacciones. Estos límites no constituyen una garantía de latencia total HTTP.

## Esquema, publicación y rol

El consumidor de fotos admite únicamente el esquema `public`; su configuración no admite `currentSchema`. La acreditación histórica de V29 en otros esquemas se conserva por separado y no acredita fotos V30 allí. Aplicar V30 con la identidad de migraciones; no editar V27–V29. Debe existir una publicación legal vigente y coherente para `USO_CONTINUADO` y `ATESTACION_FOTOS`, con las audiencias de ADMIN y USER. La declaración FOTOS requerida se vuelve a confirmar por cada intención, sin duplicar los actos legales; cada nueva operación conserva su propio resultado idempotente y vínculo contextual sobre la persistencia legal existente.

El consumidor usa el inventario de `LegalAcceptancePrivilegeVerifier` más su extensión de fotos. Es un rol dedicado `LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`, sin pertenencias ni propiedad de base/esquema/objetos. Necesita el inventario nominal legal previo y, adicionalmente:

- `SELECT, INSERT` sobre `public.reparacion_fotos_privadas` y `public.reparacion_foto_atestaciones`.
- `SELECT(id,taller_id), UPDATE(id)` sobre `public.reparaciones`, para acreditar pertenencia y tomar el lock.
- `UPDATE(estado,asset_id,asset_version,lease_id,lease_hasta,asociada_en)` sobre `public.reparacion_fotos_privadas`.
- `EXECUTE` sobre `foto_privada_insert_guard_v30()`, `foto_atestacion_insert_guard_v30()`, `foto_privada_completa_v30()` y `foto_privada_update_guard_v30()`.

No conceder DELETE/TRUNCATE sobre las dos tablas nuevas, UPDATE de los vínculos/manifestaciones, ni EXECUTE sobre `foto_privada_conservar_objetos_v30()`. Este último guard se ejecuta por el trigger referencial con privilegios de su propietario; su definición exacta, search_path fijo y propietario de migraciones/tablas se acreditan. No exige conceder lectura de fotos a los consumidores históricos para borrar una reparación sin objetos pendientes. El rechazo general de funciones SECURITY DEFINER ejecutables permanece activo.

La provisión automatizada disponible hoy es **de laboratorio**: `LegalPrivatePhotoOperationsIT.prepare` compone el fixture de aceptación, publicación canónica y grants adicionales en un PostgreSQL efímero. Ese fixture revoca privilegios PUBLIC y exige un prefijo de base; **no es un instalador para bases compartidas**. La provisión de staging debe inventariar primero sus consumidores y acreditar las ACL efectivas con el verificador, sin trasladar esas revocaciones globales indiscriminadamente. El servicio verifica esquema/privilegios al ejecutar operaciones; construir el bean/pool no demuestra que ese preflight haya pasado.

## Retención, reintentos y borrado

Una intención vence 15 minutos después de crearse; admite sólo JPEG/PNG de hasta 8.000.000 bytes, con dimensiones/hash/contenido decodificado comprobados. Hay un máximo de 100 intenciones no eliminadas por reparación. El objeto recibe una identidad aleatoria persistida antes de contactar al proveedor. Un ACK perdido puede dejar el objeto real presente; se reutilizan la misma intención, clave y manifiesto para recuperarlo, sin sustituirlos por una carga nueva.

La aplicación tiene `@EnableScheduling`; con el flag activo, la tarea espera 60 segundos al arrancar y otros 60 después de terminar cada pase. Cada pase selecciona como máximo diez filas vencidas, fallidas, expiradas o pendientes de limpieza, y omite leases todavía vigentes. Un cursor en memoria por instancia recorre el orden `(confirmado_en,id)` y avanza también ante fallos; cuando no quedan candidatos posteriores vuelve al comienzo. Cada pase procesa una sola página; el regreso puede hacer una segunda consulta limitada. Reiniciar la instancia reinicia el cursor sin alterar las filas ni sus controles transaccionales. Los leases de upload/finalización vencen a los dos minutos. También se elimina el archivo asociado al alcanzar `retener_hasta`. El borrado lógico terminal conserva la intención y su vínculo de evidencia legal, pero quita la identidad del asset externo. No purga el ledger legal.

DELETE responde 204 sólo después de comprobar el borrado remoto y confirmar el estado local. Un fallo conserva `LIMPIEZA_PENDIENTE` y la identidad del objeto para reintentar. El borrado de la reparación se bloquea mientras haya objetos pendientes y devuelve 409 `FOTOS_PRIVADAS_PENDIENTES`, indicando que primero deben eliminarse sus fotos.

El proceso actual no expone una operación HTTP administrativa de limpieza ni métricas de la cola; captura los fallos del pase sin registrar diagnósticos del proveedor. No promete borrado en el segundo exacto de vencimiento. Los fallos permanentes conservan su identidad y vuelven a intentarse en la siguiente vuelta del cursor; no bloquean el avance hacia candidatos posteriores. No borrar filas/identidades manualmente para ocultar un fallo remoto.

## Legacy y acreditación pendiente

Decisión del titular del proyecto, 2026-09-12: las cuentas y datos históricos
existentes son de prueba y pueden descartarse. Se preparará una limpieza del
conjunto identificado en lugar de una migración legacy: base/cuentas y objetos
concretos del proveedor, preservando esquemas y migraciones congelados. Todavía
no se ejecutó esa limpieza. Borrar cuentas o referencias de base no demuestra
borrado remoto, y esta decisión no fija la retención de futuros datos de clientes.


Con el flag activo, crear/actualizar una reparación no puede introducir URLs legacy nuevas, duplicarlas ni cambiarles el momento. Se preserva la lectura de URLs ya existentes y su eliminación de la lista; no hay migración automática ni privatización/borrado remoto de esos objetos históricos. Con el flag apagado, permanece el comportamiento legacy anterior: el flag no es una medida para cerrar las referencias antiguas en el proveedor.

El laboratorio local usa PostgreSQL y HTTP reales, con un almacenamiento de pruebas que conserva bytes. Los tests del adapter acreditan su contrato contra un servidor controlado; ninguno demuestra las ACL de una cuenta Cloudinary real. Queda pendiente staging con credenciales propias: confirmar tipo autenticado del asset, imposibilidad de acceso anónimo/original/derivados, lectura sólo vía backend y borrado real/reintento del objeto. La ruta de seguimiento anónimo y el resumen digital no deben recibir URLs ni IDs del proveedor. No activar MP ni transporte de email para acreditar este flujo.
