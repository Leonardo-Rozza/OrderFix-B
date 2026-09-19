# Fotos privadas — navegador, PostgreSQL y Cloudinary real

Fecha: 2026-09-19. Bases: backend `dedf759`, frontend `b6fdc2b`.
El titular pidió completar fotos antes de Mercado Pago.

## Diseño y alcance

Reutilizar `PrivatePhotoBrowserE2E` con una selección explícita del almacenamiento.
El modo predeterminado conserva el proveedor local. El modo real usa el adaptador
productivo en un contexto aislado, cargando solamente sus tres credenciales.
No importa el archivo privado como configuración de la aplicación.

Se conservan cuatro casos de navegador: escritorio/320 px y alta nueva/equipo
existente. La base PostgreSQL 16 es descartable, con usuarios sintéticos verificados,
rol de aplicación y rol restringido de fotos separados. No se conectan email/MP,
ni el backend o base habituales. No se modifican migraciones ni runtime.

El decorador confirma una carga real y pierde deliberadamente su primera respuesta.
El reintento debe recuperar por la clave durable sin repetir upload, intención u orden.
Se acreditan bytes/hash, pertenencia y declaración contextual, lectura ADMIN/USER,
rechazo ajeno/anónimo y borrado remoto antes de confirmar DELETE.

El decorador real limita la corrida a cuatro claves UUID pertenecientes a la base
sintética. Comprueba ausencia previa, guarda identidad exacta y valida acceso anónimo
al original y al intento de transformación con el objeto presente (401/403; 404 no
acredita privacidad). La limpieza final sólo opera sobre identidades propias.
Un upload incierto o cleanup fallido conserva un journal fuera del directorio efímero,
sin secretos ni respuestas del proveedor. No se borran prefijos ni históricos.

Duplicar otro laboratorio mantendría innecesariamente dos recorridos iguales. Sumar
las pruebas anteriores no acredita esta integración. Probar contra una base compartida
mezclaría datos y permisos ajenos al ensayo.

## Validación y resultado

**Aprobado el 2026-09-19 a las 16:09:19 -03.**

- `BrowserCloudinaryStorageTest`: 17 pruebas, cero fallos/errores/omisiones.
- Modo local: cuatro casos Playwright y un harness JUnit, cero fallos/errores/omisiones,
  completado a las 16:07:00 -03; suite de 54,678 s.
- Modo Cloudinary: los mismos cuatro casos y un harness JUnit, cero
  fallos/errores/omisiones; suite de 99,18 s.
- Frontend: lint y TypeScript focales aprobados; el runner rechaza opt-in inválido o
  sin archivo antes de Maven. Doce capturas por modo, decodificación efectiva de las
  imágenes y revisión visual de selección/confirmación/galería en móvil de 320 px.

En los cuatro casos reales se acreditó una carga por imagen, recuperación del objeto
tras el HTTP 503 inducido y finalización 201. No se duplicaron orden, intención ni
upload. Titular y empleado leen 200 con bytes/SHA originales; otro taller recibe 404
y el anónimo 403. Los probes directos a Cloudinary recibieron 401 para original e
intento de transformación, con asset presente. DELETE confirmó 204 y la lectura
posterior 404. PostgreSQL conservó la evidencia contextual y el estado ELIMINADA sin
identidad remota; los deltas exactos y las tablas de pagos permanecieron correctos.

Las cuatro imágenes sintéticas se eliminaron. El journal acredita cuatro uploads
aceptados, cuatro pérdidas inducidas de ACK, cuatro verificaciones anónimas y cuatro
borrados, terminando en `browser-four-assets-passed-and-deleted` y `cleanup-complete`.
No quedaron pendientes. Correlación: `e855b5bf-a250-4ce2-8088-370ce248058e`.
La falla de respuesta es deliberada; no se presenta como una caída real de Cloudinary.
El intento de transformación denegado no acredita una variante preexistente.

Los tests nuevos verifican selección de credenciales, cuota y pertenencia, colisiones,
bytes/identidad y limpieza ante respuesta real incierta. Un upload sin respuesta y
actualmente ausente queda pendiente; no se declara eliminado por un 404 transitorio.
La revisión corrigió flags del fixture (`ordenfix.legal.maintenance.enabled` y
`.scheduled`, ambos false; se retiró el inexistente `exports.http.enabled`) y los
probes usan `NO_PROXY`, como el adapter, para evitar un 403 del proxy como evidencia.
La limpieza programada de fotos sigue activa y acotada a la base sintética.

Comandos, con Java 21, Node 24 y Docker, sin importar configuración privada global:

```sh
./mvnw -B -Dtest=BrowserCloudinaryStorageTest test
# Desde frontend:
npm run test:e2e:photos-real
ORDENFIX_PHOTOS_BROWSER_CLOUDINARY=synthetic-only \
ORDENFIX_CLOUDINARY_CREDENTIALS_FILE=<ruta-privada-absoluta> \
npm run test:e2e:photos-real
```

La habilitación real exige las dos variables; el comando predeterminado conserva
storage local. El archivo sólo aporta cloud-name/api-key/api-secret (nombres
canónicos o aliases existentes); no importa DB, email ni MP. Sólo el contexto aislado
del proveedor recibe esas credenciales. El nombre E2E sigue fuera de suites por defecto.

Evidencia no versionada: `/private/tmp/ordenfix-photos-browser-live-20260919/`, con
`guards.log`, `browser-local.log`, `browser-cloudinary.log`, sus reportes XML,
`provider-journal.log`, `summary.json` y capturas por modo. El journal original se
crea fuera de JUnit TempDir, con directorio 0700, archivo 0600 y fsync por evento.
No contiene URLs firmadas, valores de credenciales ni respuestas del proveedor.

SHA-256 del log real:
`3ce42761039b03e51d8e7979d1b3f60057f9168a637dec6890b871e00751fd6c`.
SHA-256 de su journal:
`a93a470c573ae0ff98d635639fa447293d7b94f05afc0046273dd1ba0080feb3`.

V27–V34 conservan sus hashes y los 179 archivos ajenos no versionados del frontend
permanecen intactos. Los procesos propios terminaron y los servidores habituales
siguen apagados. Un commit por repositorio, sin push ni merge. No hubo cambios de
producción ni fallos en las corridas focales finales; no se repitió clean verify.

El acta frontend es `docs/plans/2026-09-19-fotos-cloudinary-recorrido-local.md`.
Quedan pendientes despliegue HTTPS/rol real, política de retención y backups/CDN,
y limpieza identificada de cuentas/objetos históricos. La ausencia remota no prueba
supresión de todas las copias. MP continúa aplazado y no se envían correos.
