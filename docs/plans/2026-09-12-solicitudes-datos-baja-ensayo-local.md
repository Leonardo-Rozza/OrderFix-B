# Solicitudes de datos y baja — ensayo local

Fecha: 2026-09-12. Baselines backend `6c942ca`, frontend `372d1d4`.
Estado: ensayo técnico cerrado localmente; atención real y cierre integral pendientes.

## Decisión y alcance

El titular autorizó avanzar con el ensayo de solicitudes de datos y cierre, con
MP pospuesto. La inspección confirma que existen la baja personal USER y el Excel
operativo ADMIN; todavía no existe una operación de cierre integral del taller ni
una exportación integral para solicitudes de datos personales.

Se extiende el laboratorio navegador → Tomcat → PostgreSQL 16 ya existente. Crear
otro harness duplicaría su aislamiento y limpieza; construir ahora un cierre
integral requiere un contrato y decisiones de retención que este ensayo no fija.
Se conservan los catorce recorridos anteriores y se amplían los dos de empleados
(escritorio y 320 px). No se agrega funcionalidad productiva ni se modifica V27–V30.

## Comprobaciones ejecutables

- ADMIN desactiva y reactiva al empleado mediante la app, manteniendo el recorrido
  de permisos anterior. USER inicia dos sesiones reales y consulta Cuenta.
- Una contraseña incorrecta no desactiva el acceso. ADMIN no puede utilizar la baja
  personal USER. La confirmación correcta por UI devuelve 204, limpia la sesión del
  navegador y revoca ambos JWT para consultas posteriores.
- El empleado permanece en PostgreSQL, inactivo y con `token_version=1`; conserva
  identidad, contraseña y taller. El cliente creado por ese empleado sigue disponible
  al titular; se conservan las comprobaciones de filas, evidencia y otros talleres
  del laboratorio anterior. No se prueba eliminación de fotos ni retención con esto.
- ADMIN descarga desde Inicio el Excel servido por la API real, después de la baja.
  POI compara el archivo descargado con PostgreSQL: cuatro hojas, el único cliente
  propio esperado y las otras tres hojas sin filas de datos. El control SQL posterior
  comprueba que existen clientes de otros talleres; no es una barrera temporal previa
  a cada descarga ni acredita controles positivos individuales para las otras hojas.
  USER y anónimo no pueden obtenerlo. No se presenta como exportación integral ni
  respuesta entregada a una persona externa.
- Cuenta indica que el cierre integral no está disponible y no ofrece contactos
  ficticios cuando faltan los tres emails. El laboratorio fija esos valores vacíos.

## Aislamiento y ejecución

Se reutilizan el PostgreSQL descartable, roles de laboratorio, Tomcat loopback de
puerto aleatorio y Vite 5175 exclusivo. La fixture sigue en V29: no acredita fotos
V30 ni staging. Email se captura en memoria; MP/checkout y fotos privadas quedan
explícitamente apagados. Spring no importa archivos de configuración privada y
no se envían correos al exterior.

Los límites de login y baja se elevan sólo en el laboratorio a 30 y 10 para las
sesiones/intentos de ambos tamaños; no se cambian los valores productivos. Cada
reporte y XLSX queda dentro del directorio temporal propio, sin contraseña/JWT en
la evidencia. Se conserva la limpieza de procesos, contenedor y archivos propios.

Comando ejecutado, desde frontend con Java 21:

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home npm run test:e2e:registration-real
```

## Simulación documental de atención

Estas referencias son ficticias y no crean expedientes ni acreditan recepción o
respuesta en un buzón. Se recorre el runbook con los siguientes casos y se separa
la clasificación manual de las comprobaciones técnicas del navegador:

| Referencia sintética | Pedido y verificación disponible | Resultado permitido |
| --- | --- | --- |
| ENSAYO-01 | Empleado con sesión y contraseña actual quiere dejar de acceder. | Ejecutar baja personal y confirmar sólo el acceso desactivado; no afirmar supresión de datos. |
| ENSAYO-02 | Titular autenticado pide sus datos. | Puede descargar el reporte operativo; la solicitud integral queda en ejecución pendiente porque faltan categorías/archivos, reautenticación y entrega acordada. |
| ENSAYO-03 | Titular pide cerrar taller y eliminar archivos. | Ejecución pendiente: no existe operación coordinada. No cambiar `talleres.activo` ni declarar cierre por haber recibido el pedido. |
| ENSAYO-04 | Cliente sin cuenta aporta un código de seguimiento y pide datos. | Verificación/revisión pendiente; ese código no acredita identidad. No entregar Excel, acceso al taller ni datos de otros clientes. |
| ENSAYO-05 | Solicitante sin acceso propone otro destinatario de entrega. | Revisión humana pendiente por el canal alternativo; no enviar información al destinatario propuesto sin contrastarlo. No descartar el caso por no poder iniciar sesión. |

Al habilitar atención real, cada gestión necesita responsable, referencia privada,
próxima revisión y método de verificación; este documento no inventa una persona,
un buzón ni una fecha de respuesta asumida. Los casos 02–05 no se marcan resueltos
por clasificar o contestar una solicitud. ENSAYO-01 se resuelve sólo en el alcance
personal demostrado por el 204, no como cierre del taller.

## Resultado y límites

**Aprobado el 2026-09-12 a las 14:46:50 -03.** Failsafe: 1 test orquestador,
0 fallos, 0 errores, 0 omitidos; `BUILD SUCCESS`. Ese test comprueba **14 recorridos
Chromium** y sus resultados SQL, incluidos los dos escenarios ampliados de empleados.
No se suman repeticiones ni se cuentan las aserciones como pruebas adicionales.

- En ambos tamaños: contraseña incorrecta 400 sin baja, ADMIN 403, confirmación USER
  204 sin cuerpo y `no-store`, dos JWT distintos rechazados con 403 después del commit,
  login del usuario inactivo 401 y logout persistente tras recargar ambas sesiones.
- SQL confirmó `active=false`, `token_version=1`, conservación del empleado y su
  contraseña/identidad, del cliente propio y del taller activo. Pasaron las comprobaciones
  previas de evidencia, otros talleres, reparaciones y cobros del laboratorio.
- Los dos XLSX reales aprobaron la comparación POI/SQL y conservaron el aviso de
  alcance operativo y ausencia de validez fiscal. USER y anónimo recibieron 403 al
  exportar. Los límites por hoja indicados arriba se mantienen.
- Cuenta mostró la ausencia de contactos y de cierre/exportación integral, sin
  simular solicitudes enviadas. Los casos ENSAYO-02 a ENSAYO-05 son una simulación
  documental de clasificación, no solicitudes recibidas ni operaciones ejecutadas.

La preparación corrigió una sobrecarga ambigua de AssertJ al comprobar una fila POI.
La primera corrida de navegador pasó 12/14: los dos casos nuevos consultaban storage
mientras la segunda sesión redirigía completamente al login. Se corrigió el test
esperando el formulario real, sin sleeps, reintentos automáticos ni cambios de app.
La segunda corrida completa aprobó. Después se sustituyeron dos aserciones de
contenido por el booleano equivalente para que un fallo no imprima storage/JWT;
ESLint y revisión de diff verifican ese cambio exclusivamente de diagnóstico.

Evidencia local: `/private/tmp/ordenfix-solicitudes-real-2.log` (SHA-256 `2b084e40f3207a950ed9f0c667f3d76641d3b158942bf5fe706261fd2b07c32f`)
y reporte Failsafe `target/failsafe-reports/TEST-com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationBrowserE2E.xml`.
La corrida inicial queda en `/private/tmp/ordenfix-solicitudes-real-1.log`; la
compilación inicial en `/private/tmp/ordenfix-solicitudes-compile.log`. Los reportes
por caso y archivos descargados son temporales del harness, no entregables de clientes.

Se comprobó la eliminación de los cuatro contenedores propios de ambas corridas,
los dos directorios temporales y el cierre del puerto Vite 5175.

ESLint focalizado del spec/config y `git diff --check` aprobados. No se repite
`clean verify`: los fallos fueron de preparación/sincronización del ensayo, sin
regresión productiva ni cambio transversal. Se conserva la política de tests
focalizados y el integral para el cierre coordinado de salida.


No se acredita recepción/respuesta real, exportación integral, cierre ADMIN,
supresión, restauración ni una política de retención. Identidad/alta/contactos
reales conservan la etapa acordada después de MP y Email. El frente Confianza y
cuenta sigue abierto hasta contar con una vía ejecutable para esas solicitudes.
