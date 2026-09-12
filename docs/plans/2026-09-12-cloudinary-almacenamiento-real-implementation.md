# Cloudinary — verificación del almacenamiento privado

Fecha: 2026-09-12. Base backend: `bae8cb5`; frontend: `fa3bdd2`.
El titular pospuso MP y pidió continuar con los pendientes que podamos completar.
Se ejecuta el ensayo de imágenes sintéticas previsto en el plan de salida existente.

## Alcance del corte

Usar el adapter productivo y las credenciales ya verificadas con ping para comprobar
carga, lectura, denegación anónima y borrado reales. El contexto Spring contiene sólo
la configuración de almacenamiento, sin iniciar la aplicación completa.

El laboratorio `CloudinaryPrivatePhotoStorageLiveE2E` es opt-in: no coincide con los
patrones habituales de Surefire ni Failsafe. Exige una habilitación explícita y una
ruta privada de credenciales. La ausencia de configuración falla antes de contactar
al proveedor; no modifica archivos privados ni importa la configuración completa.

Se generan PNG y JPEG pequeños, sin datos de personas, bajo claves aleatorias propias
de la corrida. Cada caso comprueba ausencia previa, carga autenticada, identidad/tamaño,
igualdad de bytes originales, HTTP anónimo y borrado por identidad inmutable. La limpieza
recupera por la clave exclusiva si la respuesta de carga se pierde y registra su resultado.
No se usan borrados masivos, por prefijo ni de objetos anteriores al ensayo.

La denegación se acredita con HTTP real 401/403 y comprobación de existencia/lectura
privada. Un timeout, error genérico o 404 no se convierte en prueba de privacidad.
El intento de transformación anónima no acredita por sí solo una variante preexistente:
este adapter no genera derivados. La documentación distingue estos límites.

## Resultado

**Aprobado el 2026-09-12 a las 14:22:28 -03:** 2 casos, 0 fallos, 0 errores,
0 omisiones; `BUILD SUCCESS`. La compilación previa también terminó correctamente
con Java 21. No se modificó código productivo, configuración privada ni flags de la app.

| Comprobación contra Cloudinary real | PNG | JPEG |
| --- | --- | --- |
| Carga autenticada e identidad/tamaño coherentes | Aprobada | Aprobada |
| Lectura privada con bytes originales y SHA-256 iguales | Aprobada | Aprobada |
| Acceso anónimo al original | HTTP 401 | HTTP 401 |
| Intento anónimo de transformación `c_scale,w_32` | HTTP 401 | HTTP 401 |
| Objeto todavía presente y legible tras las denegaciones | Aprobado | Aprobado |
| Borrado, ausencia, borrado repetido y lectura `MISSING` | Aprobados | Aprobados |

Los dos casos terminaron en `passed-and-deleted`, sin limpieza pendiente. Correlación
sintética: PNG `adf6473c-36ca-4e43-abf1-31590d8b3a2c`; JPEG
`d8b2c249-0042-4201-9c40-07cc12642cbf`. No se contactaron objetos históricos.
La corrida feliz no ensaya una caída real del proveedor ni pérdida de ACK.

Comandos ejecutados desde backend, con `JAVA_HOME` apuntando a Corretto 21.0.10:

```sh
./mvnw -B -DskipTests test-compile
ORDENFIX_CLOUDINARY_LIVE_CHECK=synthetic-only \
ORDENFIX_CLOUDINARY_CREDENTIALS_FILE="$PWD/src/main/resources/application-secret.properties" \
./mvnw -B -Dit.test=CloudinaryPrivatePhotoStorageLiveE2E failsafe:integration-test failsafe:verify
```

La ruta del ejemplo apunta al archivo privado ya ignorado; también puede apuntar a
un archivo externo. El test lee sólo las tres credenciales canónicas o sus aliases,
sin importar MP, email u otras propiedades a Spring. Cada ejecución explícita crea
y elimina dos assets sintéticos y consume las llamadas correspondientes del proveedor;
no forma parte de la suite predeterminada. Ante un fallo, consultar la etapa y el
journal de la clave propia; un upload sin ACK y actualmente ausente queda señalado
como limpieza incierta, sin prometer que no pueda aparecer después.

Evidencia local, no versionada: `/private/tmp/ordenfix-cloudinary-live/compile.log`,
`/private/tmp/ordenfix-cloudinary-live/corrida-1.log` y el reporte Failsafe
`target/failsafe-reports/TEST-com.leonardorozza.mvgrreparacionesbackend.photos.storage.CloudinaryPrivatePhotoStorageLiveE2E.xml`.
Los journals temporales conservan sólo UUID, formato, clave sintética y etapas;
no guardan credenciales, payloads del proveedor ni URLs firmadas.
SHA-256 del log de la corrida: `e819d5c0433959adf3d73fbb3620e6932d1d57a6bd45e5ef03aec1bc189c9b82`.

No se ejecutó `clean verify`: se agregó un laboratorio opt-in sin alterar runtime;
se conserva el integral para el cierre coordinado según la política acordada.


## Límites de la evidencia

Este corte une la cuenta Cloudinary real con el adapter productivo. Las pruebas previas
de autorización por taller y PostgreSQL usan almacenamiento de laboratorio; no se
presenta la suma de ambas como una corrida de navegador → HTTP → PostgreSQL → Cloudinary.

Siguen pendientes el despliegue y rol de fotos, retención de futuros clientes, backups
y alcance de invalidación CDN, limpieza identificada de históricos y acreditación en
staging. La ausencia en Admin API no acredita destrucción de todas las copias de backup.
MP queda pendiente del panel por decisión del titular. No se reenvían correos.

Fuentes oficiales consultadas el 2026-09-12:
[control de acceso](https://cloudinary.com/documentation/control_access_to_media),
[Upload API](https://cloudinary.com/documentation/image_upload_api_reference)
y [Admin API](https://cloudinary.com/documentation/admin_api).
