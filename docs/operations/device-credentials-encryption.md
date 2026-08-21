# Cifrado de PIN y patrón de dispositivos

Las credenciales de desbloqueo se guardan con AES-256-GCM. La clave no pertenece
al repositorio, a `application.properties`, a la base de datos ni a los logs: se
inyecta únicamente mediante `DEVICE_CREDENTIALS_ENCRYPTION_KEY`.

## Preparación del despliegue V20

1. Hacer un backup verificable de PostgreSQL antes de ejecutar Flyway.
2. Generar una clave de 32 bytes y guardar su Base64 en el gestor de secretos del
   entorno, por ejemplo con `openssl rand -base64 32`.
3. Exponerla al proceso como `DEVICE_CREDENTIALS_ENCRYPTION_KEY`. No copiarla en
   archivos, tickets, comandos versionados ni salidas de CI.
4. Desplegar V20 y la aplicación en la misma ventana. V20 solo renombra las dos
   columnas y marca las filas existentes como versión `0`; no borra datos.
5. El primer arranque cifra las filas legacy dentro de una transacción y cambia
   su versión a `1`. La aplicación no queda lista si falta la clave, es inválida,
   no autentica un ciphertext existente o aparece una versión desconocida.

Si el arranque falla, no editar ni poner en `NULL` las columnas. Corregir la clave
ambiental y reiniciar: las filas versión `0` conservan el valor original y la
migración vuelve a intentarse. Una vez completada, puede verificarse sin leer los
secretos:

```sql
SELECT credenciales_cifrado_version, count(*)
FROM reparaciones
GROUP BY credenciales_cifrado_version;
```

El resultado operativo esperado es que no existan filas con versión `0`.

## Operación segura

- No registrar request DTOs, DTOs de detalle, ciphertexts ni resultados de SQL.
- Solo `GET /api/reparaciones/{id}`, autenticado y aislado por taller, devuelve
  PIN/patrón. Altas, actualizaciones, cambios de estado, listados, dashboard y
  seguimiento público no los incluyen.
- En un `PUT`, omitir el campo conserva su valor; enviar una cadena vacía lo borra.
- Al pasar a `ENTREGADO`, ambos ciphertexts se eliminan en la misma transacción.
- No cambiar la clave de entorno directamente: los datos existentes dejarían de
  autenticar y el arranque fallaría. La rotación requiere una migración controlada
  que descifre con la clave anterior y vuelva a cifrar con la nueva.
- Después de aplicar V20 no se debe arrancar una versión anterior de la aplicación,
  porque espera los nombres históricos de las columnas.
