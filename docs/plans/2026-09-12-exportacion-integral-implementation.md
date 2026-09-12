# Exportación integral del taller — implementación por cortes

Fecha: 2026-09-12. Baselines backend `bf9b6d9`, frontend `0d76348`.
Estado: corte A cerrado localmente. B–D pendientes; la exportación integral todavía no está disponible.

## Alcance acordado y secuencia

Continúa Tarea 15 / BACKEND-HANDOFF 8 del plan de Confianza y cuenta y el diseño
frontend de 2026-08-23, sección Exportación y cierre de cuenta. El usuario pidió
continuar después del ensayo local de solicitudes/baja. El cierre ADMIN permanece
como una operación posterior y separada. MP está pospuesto; identidad/alta/contactos
reales mantienen su etapa final acordada después de MP y Email.

| Corte | Resultado concreto | Criterio de cierre |
| --- | --- | --- |
| A — Reautenticación interna | Credencial opaca persistida, ligada a titular/sesión/taller/propósito y consumo transaccional único. | PostgreSQL: permisos, expiración, reemplazo, otra sesión, concurrencia y rollback acreditados. Sin endpoint ni UI todavía. |
| B — Datos y formato | Lectura consistente con campos explícitos, relaciones verificadas, categorías/exclusiones documentadas, manifiesto y archivos locales del paquete. | Dos talleres, valores sensibles y relaciones inconsistentes probados; nunca se publica un archivo parcial como completo. |
| C — Generación y conservación temporal | Trabajo durable/idempotente, archivos permitidos, cifrado, expiración, recuperación y limpieza. | Fallos/reintentos no mezclan snapshots ni tenants; revocación y eliminación temporal verificadas, sin URLs públicas permanentes. |
| D — API, pantalla y verificación final | Reautenticación HTTP con límites, solicitud/consulta/descarga, pantalla ADMIN y protección o retiro de Excel directo. | Navegador/HTTP/PostgreSQL, nueva confirmación por descarga y permisos; no queda un camino de exportación que saltee la protección acordada. |

Cada corte incluye sus pruebas focalizadas, documentación y un commit atómico por
repositorio afectado, sin push. El integral completo se reserva para el cierre
coordinado o un cambio transversal/regresión que lo justifique. Cerrar A no habilita
el handoff ni protege todavía `/api/export/excel`; ese bloqueo conserva su criterio D.
El Excel actual sigue siendo exclusivamente un reporte operativo.

## Diseño del corte A

Se elige una tabla dedicada y un servicio interno. Reutilizar `auth_tokens` de email
no ofrece vínculos de sesión/taller/versión; extender JWT o emitir otra sesión no
representa una confirmación de contraseña de un solo uso. Tampoco se agrega ahora
un endpoint que entregue credenciales sin un consumidor sensible integrado.

El servicio recibe el access token para verificarlo criptográficamente mediante
`JwtUtils`; no confía en un `jti`, actor, taller ni rol enviados por separado.
Relee el usuario y obtiene el bloqueo compartido con reset/baja/actualización de
usuarios. Verifica ADMIN activo, taller activo, versión de sesión y email verificado.
Email verificado permanece obligatorio aunque el recorrido Email C siga pendiente.
El PIN/patrón del equipo y los secretos de proveedores no intervienen.

La credencial usa 32 bytes aleatorios, codificados base64url sin padding. La tabla
sólo guarda SHA-256 del token, SHA-256 del JWT completo, IDs de usuario/taller,
versión de revocación, propósito y fechas. La sesión queda ligada al token realmente
verificado, sin alterar el principal o filtro JWT compartidos. El JWT completo y
la contraseña no se persisten ni aparecen en `toString`/errores del nuevo componente.

Propósitos iniciales: `EXPORTAR` y `DESCARGAR_EXPORTACION`. No se habilitan `CERRAR`
ni `RESTAURAR`. Vencimiento: cinco minutos como máximo, limitado además por el
vencimiento real del JWT, sin extenderlo por la tolerancia de reloj de autenticación.
Contraseña de 1–100 caracteres UTF-16, no sólo espacios y sin recortar su valor;
JWT de hasta 8192 caracteres y prueba opaca estrictamente canónica.

`issue` inicia una transacción READ_COMMITTED o participa de la transacción de
escritura existente, conservando su aislamiento. La emisión reemplaza atómicamente la
prueba previa para el mismo usuario/sesión/propósito. Una respuesta perdida requiere
confirmar la contraseña otra vez; el hash guardado no permite reproducir el secreto.
Las pruebas de otra sesión o propósito no se invalidan por ese reemplazo. Una
colisión aleatoria falla y conserva la prueba anterior, sin renovar su vencimiento.

`consume` exige una transacción de escritura ya activa (`MANDATORY`) y vuelve a
comprobar la sesión/estado. Marca un único consumo mediante actualización condicional
por hash, vínculos, propósito, versión, no usada y vencimiento. El futuro consumidor
resuelve primero el replay de su trabajo y consume la prueba en la misma transacción
que persiste el efecto. Un rollback debe restaurar prueba y efecto juntos. No se usa
una transacción independiente que consuma la autorización aunque el trabajo falle.
Tras el UPDATE se vuelve a comprobar el vencimiento devuelto por PostgreSQL: una
espera de bloqueo que excede su vigencia también revierte el consumo.

V31 agrega `cuenta_reautenticaciones`, sus restricciones e índices. No modifica
V27–V30. La nueva FK compuesta `(user_id,taller_id)` referencia `users(id,taller_id)`
y rechaza pares incoherentes; el borrado en cascada sólo descarta la autorización
temporal cuando se elimina ese usuario. No es un mecanismo de cierre del taller.
El verificador legal debe reconocer la historia exacta V31 sin cambiar los
fingerprints ni admitir deriva del catálogo legal/fotos ya acreditado con V30.
La acreditación legal no incorpora esta tabla de cuenta a su inventario: conserva
compatibilidad con una historia V30 y catálogo V30 válidos. Flyway sigue a cargo
de la migración completa al arrancar; este preflight no acredita el esquema de
reautenticación ni sustituye sus pruebas de integridad. V31 usa checksum
`518186831` y la lectura de historia admite un máximo de seis filas, incluida la
fila testigo que rechaza una extensión desconocida.
Se usa JDBC sobre el DataSource de JPA, sin una entidad adicional que haga depender
el arranque de los laboratorios V29 de esta tabla. No se agrega configuración secreta,
flag, tarea de limpieza ni rutas nuevas en A. La limpieza periódica de credenciales
vencidas y límites de emisión HTTP quedan exigidos antes de habilitar el consumidor.
Las filas de reautenticación no son el registro permanente de auditoría del trabajo.

La autorización acredita estado al comprobarlo bajo el bloqueo de usuario. No
pretende implementar el protocolo futuro de concurrencia del cierre de taller;
éste debe coordinar su propia restricción, renovaciones, datos y almacenamiento.

Referencias técnicas consultadas para compartir transacción JPA/JDBC y conservar
la semántica de rollback: [JpaTransactionManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html)
y [propagación transaccional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).

## Inventario para los siguientes cortes

La revisión encontró 48 tablas hasta V30. Un dump de entidades o tablas por
`taller_id` no es un formato de entrega: puede exponer credenciales o relaciones
inconsistentes. B debe usar proyecciones explícitas y verificar pertenencia de
cliente/equipo/técnico/artículo/reparación de origen, además del tenant principal.

| Categoría | Tratamiento previsto |
| --- | --- |
| Taller y usuarios | Datos operativos de titular/empleados, incluidos inactivos; excluir contraseña/hash y versión de sesión. |
| Clientes y equipos | Identidad/contacto y asociaciones propias del taller. |
| Reparaciones | Datos de trabajo, importes, fechas, accesorios, checklist, garantía y relaciones; excluir PIN/patrón y material de cifrado. No reutilizar el mapper que descifra credenciales. |
| Repuestos e inventario | También repuestos sin reparación y artículos inactivos. No inventar un historial de stock inexistente. |
| Presupuestos | Estado, datos e ítems; preservar duplicados sin inventar ID u orden histórico. |
| Cobros manuales | Incluir anulaciones y autor/motivo/fecha; describir registros del taller, nunca acreditaciones procesadas por OrdenFix. |
| QR de cobro | PNG almacenado en PostgreSQL, con digest comprobado y nombre de archivo generado. |
| Suscripción SaaS | Proyección comercial separada de los cobros taller–cliente; excluir diagnósticos, URLs de checkout e idempotencia del proveedor. Precisar campos/IDs comerciales en B. |
| Fotos privadas | Metadata con estados y referencias originales, aun cuando una reparación ya no exista; binarios sólo si la política vigente permite leerlos. No reponer archivos vencidos/eliminados ni exponer identidades técnicas del proveedor. |
| Fotos legacy | La URL declarada no acredita propiedad ni autoriza fetch arbitrario. Registrar su límite; la limpieza identificada de históricos sigue pendiente. |
| Constancia legal | Historial propio del solicitante y documentos exactos que le corresponden. El ADMIN no obtiene por esta vía aceptaciones personales USER que hoy sólo pueden leer sus autores. |

Quedan excluidos del paquete tokens/sesiones/reautenticaciones, contraseñas y hashes,
PIN/patrón, claves/versiones de cifrado, metadata IP/UA, HMAC y ledgers internos,
inbox/diagnósticos MP, catálogos editoriales globales, leases y asset IDs/keys del
proveedor. B documentará la inclusión o exclusión del código de seguimiento como
capacidad de acceso, además del número de orden. Las categorías faltantes nunca
se silencian detrás de la etiqueta «integral».

## Pruebas y resultado del corte A

Pruebas focalizadas previas a la comprobación final:

- `./mvnw -B -Dtest=JwtUtilsTests,JwtSecurityIntegrationTests,AccountAccessExitServiceTest test`:
  **35 aprobadas**, sin fallos ni omisiones; también compiló las nuevas pruebas.
- `./mvnw -B -Dit.test=ExportReauthenticationServiceIT,PersonalAccessExitIT failsafe:integration-test failsafe:verify`:
  **36 aprobadas** (21 reautenticación y 15 baja personal), sin fallos ni omisiones.
  Esta primera ejecución usó los recursos compilados antes de cambiar las FK simples
  por la FK compuesta. La corrida completa posterior acreditó la V31 definitiva.

El nuevo laboratorio usa PostgreSQL 16/Flyway/Hibernate validate, JWT y BCrypt
reales con identidades sintéticas. Comprueba permisos/estado/email, hash y vínculos,
ambos propósitos, reemplazo, expiración, token no canónico, JWT adulterado,
revocación de sesión, rechazo sin transacción/de sólo lectura y rollback del efecto.
La concurrencia observa una espera real en PostgreSQL; una segunda prueba adelanta
el reloj mientras el UPDATE está bloqueado y comprueba que no consuma fuera de plazo.
También fuerza una colisión aleatoria y verifica que no renueve ni borre la prueba.

Se ejecutó `clean verify` por el ajuste transversal del verificador de historia legal.
**BUILD SUCCESS**, finalizado el 2026-09-12 a las 15:48:28 -03, en 27 min 54 s:
**7.492 unitarias y 1.390 de integración, cero fallos, errores u omisiones**.
La corrida incluye nuevamente las pruebas focalizadas y la V31 definitiva con FK
compuesta. Acredita rechazo del par usuario/taller ajeno, compatibilidad V30/V31 de
los consumidores legales, deriva y versiones desconocidas rechazadas, límites de
lectura de catálogo y lectores sobre la versión actual. El guard de empaquetado
confirmó ausencia de `application-secret.properties` en los JAR.

Entorno: Amazon Corretto 21.0.10, Maven Wrapper del repositorio y PostgreSQL
`16-alpine` descartable. Comando: `JAVA_HOME=<Corretto 21> ./mvnw -B clean verify`;
registro temporal: `/private/tmp/ordenfix-export-reauth-clean-verify.log`.
Se contrastaron V27–V30 byte a byte contra HEAD: idénticas. `git diff --check`
aprobado en ambos repositorios; los 77 archivos ajenos no versionados del frontend
se preservan. No hay cambios de privilegios legales, filtros JWT ni principal.
Las pruebas no envían correos ni contactan MP/Cloudinary, no borran cuentas reales
ni cambian el funcionamiento del Excel o de las pantallas actuales. Los laboratorios
reales opt-in no forman parte del integral predeterminado. Frontend sólo actualiza
los dos documentos de seguimiento y se valida por revisión del diff.


## Cierre y siguiente paso

A queda completo con el servicio interno, V31, compatibilidad, pruebas y esta acta.
Un commit backend y otro documental frontend, sin push ni merge. No se aplicó la
migración a una base real ni se habilitaron consumidores públicos.
El siguiente corte es **B: proyecciones de datos, pertenencia y formato del paquete**;
C y D mantienen sus criterios. El cierre ADMIN sigue fuera de esta secuencia de
exportación, como trabajo posterior, y MP permanece pospuesto.
