# Exportación del taller: generación y entrega protegida

Cortes A–D, 2026-09-12. El [plan por cortes](../plans/2026-09-12-exportacion-integral-implementation.md)
registra el contrato y la validación. D conecta el servicio a la API y a
`/cuenta/exportacion`; el Excel operativo también exige contraseña actual.
El ZIP permanece deshabilitado por defecto hasta completar el gate del despliegue.

## Configuración y claves

El worker no existe cuando `exports.jobs.enabled` está ausente o vale `false`.
El indicador sólo acepta `true`/`false` exactos. Con `true` se requieren:

| Propiedad | Valor esperado |
| --- | --- |
| `exports.jobs.active-key-version` | Versión numérica positiva usada para nuevas escrituras. |
| `exports.jobs.key-versions` | Lista sin espacios de hasta ocho versiones distintas, por ejemplo `1,2`. |
| `exports.jobs.keys.v<version>` | Clave de 32 bytes aleatorios en base64 canónica, distinta por versión. Secreto exclusivo de exportación. |

Guardar las claves únicamente en el gestor de secretos/configuración privada del
servidor. No se generan automáticamente ni se reutilizan claves de PIN, fotos o
metadata legal. No se necesita otra credencial de Cloudinary: para incluir fotos se
reutiliza el servicio privado existente y su configuración, ya acreditada por separado.
Una foto pendiente con ese servicio ausente bloquea la generación, sin omitirla.
Este corte no modifica configuración real ni habilita el worker por defecto.

Para rotar, agregar primero la nueva versión conservando las anteriores y cambiar
la versión activa. Retirar una clave sólo después de vencer y limpiar todos los
trabajos que puedan necesitarla. Si una clave se pierde o se retira antes de tiempo,
el tag no puede acreditarse y el archivo no se entrega. Un READY todavía conservado
puede volver a leerse al restaurar su clave. Si el worker ya intentó leer un snapshot
sin su clave, el trabajo falla y purga sus payloads: restaurar la clave no lo revive;
requiere un nuevo pedido y prueba. Restaurar una base requiere además conservar las
claves necesarias y ejecutar limpieza antes de habilitar entregas.

## Operación

V32 almacena el pedido y ciphertext temporal en PostgreSQL. A confirma contraseña,
ADMIN, email y sesión; el consumo de la prueba y la creación del trabajo comparten
transacción. La clave de idempotencia identifica el pedido; repetirlo desde su sesión
original devuelve su estado sin consumir otra prueba ni reiniciar plazos. Consulta y
recuperación internas exigen el mismo actor/taller/versión; recuperar el ZIP exige una
prueba nueva de descarga, incluso desde otra sesión válida de ese titular.

El executor dedicado inicia a los 60 segundos y ejecuta un paso por vez; entre pasos
espera otros 60 segundos. No usa el scheduler de fotos. La reserva global en PostgreSQL
impide que otra instancia tome un trabajo mientras la reserva actual siga vigente.
El snapshot cifrado persistido se reutiliza en reintentos; no se mezclan capturas.
READY se confirma sólo con ZIP cifrado completo y fotos todavía autorizadas.

| Límite inicial | Tratamiento |
| --- | --- |
| Cuatro trabajos activos globales, uno por taller | Otra solicitud conserva su prueba y devuelve capacidad ocupada. |
| Una reserva RUNNING, cinco minutos | Un proceso atrasado no puede escribir sobre la reserva siguiente. |
| Tres intentos, presupuesto cooperativo de tres minutos por paso | Un fallo transitorio se reintenta; el límite/fallo definitivo elimina payloads. |
| 256 fotos y 64 MiB de fotos | Exceso rechaza el paquete, sin truncarlo. |
| Snapshot serializado 72 MiB; ciphertext hasta 80 MiB en DB | Conserva bytes exactos de B, con sus límites propios. |
| ZIP completo hasta 128 MiB de contenido; ciphertext hasta 140 MiB | Incluye manifiesto y fotos. No es un formato de streaming de memoria constante. |
| 24 horas desde solicitud, acortadas por retención de fotos | No se extiende por reintento, replay o descarga. |

Cifrado, descifrado autenticado, ZIP y JDBC mantienen buffers/copias proporcionales al
contenido. Antes de activar el recorrido público, acreditar memoria del proceso y
espacio PostgreSQL con estos topes y la concurrencia permitida. C no crea archivos
en claro ni archivos temporales en disco; la generación ocurre en memoria y el
resultado durable es ciphertext BYTEA. El directorio de prueba que puede producir
el escritor B es una herramienta separada; el worker no lo usa ni lo entrega.

## Limpieza y recuperación

Cada paso limpia caducados/revocados, recupera reservas vencidas y purga metadata
terminal antigua por lotes. Los cambios de usuario/taller/versión o una foto que deje
de ser elegible vetan futuras entregas. La limpieza borra snapshot, ZIP y referencias
de fotos de esa fila; nunca borra datos del taller ni assets Cloudinary. Se conserva
estado técnico al menos siete días para replay; después queda elegible para purga,
hasta 100 filas por paso. Las pruebas de reautenticación vencidas se purgan de a
1.000. Apagar el worker detiene esta limpieza periódica y no ejecuta una pasada final;
la caducidad de acceso sigue verificándose al consultar/recuperar por el servicio.
La eliminación física de payloads ocurre en una pasada exitosa, no exactamente en
el instante de vencimiento.

La eliminación de BYTEA no acredita borrado inmediato de WAL, backups ni réplicas.
Las políticas de conservación/recuperación de esos sistemas forman parte del gate
operativo. No afirmar que una copia desapareció de todos los respaldos por ver una
fila sin payload. Después de restaurar un backup, limpiar vencidos/revocados antes
de habilitar entrega y verificar la vigencia de claves y estados de cuenta.

Observación de salud sin extraer datos ni secretos:

```sql
SELECT estado, count(*) AS trabajos,
       sum(coalesce(octet_length(snapshot_cipher),0) + coalesce(octet_length(archive_cipher),0)) AS bytes_cifrados
FROM cuenta_exportaciones GROUP BY estado ORDER BY estado;
```

Los fallos persistidos son códigos, sin respuestas del proveedor ni SQL. Si un paso
se interrumpe, el siguiente recupera su reserva vencida; no editar leases/ciphertext
manualmente ni reutilizar una autorización consumida para simular una solicitud.
MP, email, cierre del taller y contacto legal mantienen sus pendientes independientes.


## API y pantalla

Todas las rutas requieren JWT vigente y ADMIN. Emisión y consumo releen además
usuario activo, taller activo, email verificado y versión de revocación. Un empleado
no puede acceder a la página de exportación ni descargar por estas rutas.

| Método y ruta | Uso |
| --- | --- |
| POST `/api/cuenta/reauthenticaciones` | JSON `passwordActual` y `proposito`: EXPORTAR o DESCARGAR_EXPORTACION. Devuelve prueba opaca y vencimiento; no renueva JWT. |
| POST `/api/exportaciones` | Cuerpo vacío, `Idempotency-Key` UUID canónico y `X-Reauth-Token`. Responde 202 y Location del recurso; replay conserva trabajo y plazo. |
| GET `/api/exportaciones/actual` | Devuelve `habilitada` y última exportación del propio actor/taller/versión. Puede recuperarse desde una nueva sesión válida. |
| GET `/api/exportaciones/{id}` | Estado propio; recurso ajeno equivale a inexistente. |
| POST `/api/exportaciones/{id}/archivo` | Cuerpo vacío y nueva prueba DESCARGAR_EXPORTACION; devuelve ZIP autenticado completo. |
| POST `/api/export/excel` | Cuerpo vacío y nueva prueba DESCARGAR_EXPORTACION; devuelve XLSX operativo. Funciona aunque el worker esté apagado. |
| GET `/api/export/excel` | Retirado: 410 sin archivo. HEAD no entrega bytes. |

Contraseña/proof no se envían por query, no se conservan en React Query ni se incluyen
en URLs, storage, telemetría o mensajes de error. Cada nueva descarga solicita la
contraseña actual. Si se pierde una respuesta después del commit, se confirma otra
prueba: no se promete que una descarga fallida en red conserve una prueba ya consumida.
La interfaz recupera el trabajo al recargar y consulta pendientes cada quince segundos
sólo mientras la página está visible; detiene consultas automáticas ante error o estado
terminal. No conserva IDs de exportación entre sesiones en el navegador.

JSON estricto hasta 4 KiB, sin propiedades duplicadas, coerción, cuerpo residual ni
profundidad excesiva; contraseña de 1–100 caracteres, sin recortarla. UUID y headers
son únicos y canónicos. No se admiten query parameters o cuerpos no previstos.
Errores sanitizados: 400 solicitud/contraseña/prueba inválida; 403 permiso; 404 recurso
ajeno/inexistente; 409 archivo no disponible; 429 límite con Retry-After; 503 función
apagada/fallo temporal; 413 cuerpo excesivo y 415 tipo de contenido no admitido.
Una contraseña incorrecta no invalida el login. Respuestas sensibles usan no-store
y nosniff; los archivos son respuestas directas, sin enlaces públicos permanentes.

## Cuotas y trabajo costoso

El filtro propio conserva estos límites aunque `security.rate-limit.enabled=false`:

| Ventana por actor y JVM | Límite |
| --- | --- |
| Confirmaciones de contraseña / quince minutos | 5 |
| Solicitudes / quince minutos | 3 |
| Descargas ZIP y XLSX combinadas / quince minutos | 3 |
| Consultas / minuto | 30 |

El mapa admite hasta 2.048 actores; sólo elimina ventanas vencidas y nunca expulsa
una cuota viva para admitir otra identidad. Usa tiempo monotónico para las ventanas.
Como máximo dos verificaciones BCrypt concurrentes, sin cola. Una plaza compartida
por JVM cubre generación del worker y descarga HTTP hasta finalizar su escritura
síncrona. Si está ocupada se rechaza antes de gastar cuota; todas las salidas liberan
la plaza. Estos controles no son distribuidos: reinicios, varias instancias, límites
de proxy y timeouts deben incorporarse a la política del despliegue.

Una prueba inválida de descarga ZIP se rechaza antes de cargar BYTEA o descifrar.
Consumo, revalidación de estado y preparación autorizada del archivo comparten
transacción; corrupción o revocación detectada antes de su commit revierte el
consumo y descarta bytes en claro. La escritura HTTP ocurre después. La autorización
se vuelve a comprobar después del descifrado, incluido vencimiento real del JWT.

Excel conserva sus cuatro hojas y cálculos, como registro operativo sin validez fiscal.
El wrapper usa REQUIRES_NEW/REPEATABLE_READ sobre JDBC/JPA: consume prueba, comprueba
pertenencia y presupuestos, genera y vuelve a autorizar dentro de una captura
consistente. Límite global de 50.000 filas del grafo que carga el escritor, 16 MiB de
huella de origen y 32 MiB de salida. Se computan textos descomprimidos y relaciones
inversas para evitar que TOAST o datos ajenos sorteen el preflight. Un exceso rechaza
la operación completa y restaura la prueba; no se truncan hojas.

Auditoría: autorización y escritura HTTP registran actor, taller, formato e ID cuando
corresponde; no imprimen contraseña, proof, JWT ni contenido. Una escritura completada
por el servidor no acredita recepción por el cliente. La colección, acceso y retención
de estos logs son responsabilidades operativas del despliegue.

## Capacidad acreditada localmente y gate productivo

El probe opt-in `scripts/run-export-capacity.sh --synthetic-pg16-only --heap 2g`
compila en un directorio temporal propio y utiliza PostgreSQL 16 descartable; no llama
Maven ni modifica target. Requiere Java 21 y un classpath de pruebas previamente
compilado (`LocalExportPackageWriterTest` o `--classpath-file`). Admite `--help` y
conserva su log/medición; no debe ejecutarse apuntando a datos reales.

Se ensayó codec C + cifrado/descifrado + ida/vuelta JDBC/PG con 67.100.672 bytes de
datos, nueve PNG que suman 66.954.285 bytes y 27 entradas ZIP. El contenido expandido
fue **134.061.717 bytes**, 99,88 % del máximo de 128 MiB; ciphertext: **117.405.731
bytes**. Se verificaron manifiesto, hashes, autenticidad, recuperación y limpieza de
fila, con estos resultados locales:

| Heap máximo | Duración del probe | RSS máximo observado | Peak memory footprint de macOS |
| --- | --- | --- | --- |
| 768 MiB | 22,57 s | 822.362.112 B | 928.698.496 B |
| 1 GiB | 17,46 s | 791.855.104 B | 1.176.392.384 B |
| 2 GiB | 16,98 s | 1.249.460.224 B | 2.058.033.344 B |

Son observaciones de procesos separados, no una relación monotónica ni una promesa
para producción. RSS no representa toda la huella de macOS y no incluye PostgreSQL
Docker. El mínimo ensayado, 768 MiB, pasó con poco margen de heap. Una repetición
con el launcher y 2 GiB pasó en 17,87 s. Los contenedores propios se eliminaron.
El probe no incorpora el contexto Spring completo, captura B, I/O de fotos remotas,
worker y tráfico HTTP concurrente; no acredita una instancia productiva de 768 MiB
ni de 512 MiB. El recorrido de navegador usa fixtures pequeñas.

Antes de activar ZIP, dimensionar JVM/proceso completo y PostgreSQL/WAL/backups con
el límite y número de trabajos; provisionar claves exclusivas, confirmar retención y
recuperación, límites entre instancias y timeouts del proxy. La implementación local
A–D queda disponible para esa validación; no activa por sí sola el servicio público.
