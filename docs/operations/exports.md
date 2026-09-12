# Exportación integral: generación temporal interna

Corte C, 2026-09-12. El [plan por cortes](../plans/2026-09-12-exportacion-integral-implementation.md)
registra el contrato y la validación. No hay endpoint ni pantalla de exportación
integral en C. El Excel operativo conserva su alcance hasta su protección/retiro en D.

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
D debe conectar límites HTTP, pantalla y descarga a estos servicios internos. MP,
email, cierre del taller y contacto legal mantienen sus pendientes independientes.
