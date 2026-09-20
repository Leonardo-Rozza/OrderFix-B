# Atención de solicitudes de datos y cierre

Estado al 2026-09-19: procedimiento preparado, con ensayos técnicos locales aprobados;
pendiente de habilitación operativa para solicitudes reales.
No es un texto contractual ni una constancia de cierre. La referencia técnica del
corte es `docs/plans/2026-09-12-taller-inactivo-enlaces-publicos-implementation.md`.

Prioridad confirmada por el titular del proyecto el 2026-09-12: la identidad del
prestador, su alta y los contactos definitivos se completarán al final, después de
MP y Email. No bloquean los cortes técnicos independientes; sí siguen pendientes
antes de anunciar este canal como operativo o habilitar la salida pública.

## Preparación del canal

Antes de anunciarlo como atendido, registrar fuera del repositorio:

| Dato | Comprobación necesaria |
| --- | --- |
| Prestador | Identidad confirmada por el titular del proyecto y coherente con la publicación legal. |
| Buzón | Dirección real, acceso de la persona responsable y recepción/respuesta comprobadas. Puede ser el mismo para legal, privacidad y soporte. |
| Responsable | Persona que revisa el buzón y reemplazo si está ausente. |
| Registro | Ubicación privada para referencias, estado, próximos pasos y evidencia; acceso limitado a quienes atienden. |
| Atención | Frecuencia de revisión, próxima respuesta y tratamiento de ausencias definidos por el operador. |
| Datos y entrega | Verificación de identidad, medio de entrega autorizado y conservación del expediente definidos antes de tratar casos reales. |

Configurar `VITE_LEGAL_EMAIL`, `VITE_PRIVACY_EMAIL` y `VITE_SUPPORT_EMAIL` en el entorno
frontend y en el manifiesto de publicación correspondiente. Son valores públicos
incorporados al build; cambiarlos requiere otro build. No son credenciales SMTP.
Los enlaces de Cuenta abren correo; no envían mensajes ni crean un expediente.
El validador de configuración comprueba formato y paridad, no entrega o atención.

No se habilita un envío automático ni se completa la fase de email por este trabajo.
El ensayo de recepción/respuesta real se realiza con autorización de envío y datos
sintéticos; una prueba de UI con mailto no lo reemplaza.

## Recepción y clasificación

Al recibir un mensaje, la persona responsable crea una referencia única en el
registro privado y la devuelve por el canal verificado. Registrar fecha, solicitud,
alcance declarado, responsable y siguiente paso. La referencia no es una contraseña
ni autoriza a consultar datos. Una reiteración se vincula a la misma gestión.

| Pedido | Tratamiento inicial |
| --- | --- |
| Empleado quiere dejar de usar la app | Orientar a Cuenta → Dar de baja mi acceso. La contraseña se ingresa en OrdenFix, nunca por correo. Si no puede ingresar, atender el caso por el canal alternativo. |
| Persona consulta o pide corregir/suprimir sus datos | Precisar categorías y relación con el taller; verificar identidad antes de revelar existencia de registros o entregar información. |
| Titular pide datos del taller | Verificar identidad y facultad sobre ese taller; acordar el alcance y la entrega. El Excel operativo existente no acredita exportación integral. |
| Titular pide cierre del taller | Confirmar alcance sobre taller, empleados, datos y enlaces. Registrar la solicitud; no confundir recepción con cierre ejecutado. |
| Cliente de una reparación contacta a OrdenFix | Registrar y coordinar con el taller cuando corresponda; no conceder acceso a la cuenta del taller ni entregar datos de otros clientes. |

No pedir contraseñas, JWT, PIN/patrón del equipo, respaldos completos ni fotografías
de documentos por defecto. Una dirección escrita en el mensaje, captura del perfil,
ID de taller o código de seguimiento no acredita identidad o facultades. El método
de verificación deberá contrastar una fuente autorizada y no revelar datos a un
destinatario alternativo propuesto sólo por quien escribe. Si no puede verificarse
la cuenta por la vía habitual, asignar revisión humana y comunicar el siguiente paso.

## Seguimiento y respuesta

El registro manual distingue recepción, verificación pendiente, revisión, ejecución
pendiente y respuesta enviada. Si falta una herramienta o decisión, conservar la
gestión abierta con responsable y próxima revisión; no marcarla resuelta por haber
contestado el primer correo. Las fechas aplicables a cada solicitud deben quedar
definidas con el responsable legal/operativo antes de habilitar el canal; este
runbook no fija un plazo legal universal ni adopta los plazos de los borradores.

La respuesta inicial informa referencia, lo entendido, paso siguiente y fecha de
próxima comunicación realmente asumida. La respuesta final describe lo ejecutado,
categorías incluidas, límites o datos conservados con fundamento/plazo definidos,
fecha y canal de seguimiento. Usar únicamente hechos comprobados. No adjuntar
exportaciones a un destinatario sin verificar ni guardar expedientes de clientes,
credenciales o archivos descargados en Git, logs de aplicación o capturas públicas.

## Frontera técnica actual

- La baja individual existente exige sesión USER y contraseña actual, desactiva el
  usuario y revoca JWT anteriores. No elimina datos ni cierra el taller.
- `talleres.activo=false` bloquea el acceso privado y, con este corte, nuevas
  búsquedas públicas de seguimiento y respuesta a presupuestos. No es una operación
  de cierre: no tiene referencia durable, tratamiento de concurrencia de cierre,
  retención, borrado de archivos, restauración ni eliminación coordinada.
- Este runbook no ofrece SQL para desactivar/eliminar talleres. El cambio de la
  bandera en las pruebas es una fixture descartable, no un procedimiento productivo.
- `/api/export/excel` es una exportación operativa ADMIN de clientes, órdenes,
  cobros y presupuestos. D exige contraseña actual mediante POST; el GET anterior
  responde 410. No incluye todas las categorías/archivos. No sirve como
  respuesta automática a una solicitud individual de un empleado o cliente.
- El corte A de exportación agrega reautenticación interna ADMIN con contraseña
  actual y email verificado: prueba temporal de un solo uso ligada a la sesión,
  consumida junto con el efecto en la misma transacción. El corte B agrega captura
  consistente y escritura de un paquete local privado con JSON, textos legales
  propios y QR. Su manifiesto declara entrega incompleta y fotos remotas pendientes;
  no se debe enviar ese staging a una persona ni marcar su pedido resuelto.
  El corte C agrega el trabajo durable, ZIP con fotos permitidas, cifrado y
  vencimiento/limpieza en PostgreSQL. La generación permanece deshabilitada por
  defecto y sólo se activa con configuración explícita. D conecta API, pantalla
  ADMIN y descarga con una nueva prueba por archivo, también para el Excel.
  El runbook [de exportaciones](../operations/exports.md) detalla sus límites y
  recuperación. No habilita a soporte a pedir una contraseña por correo ni acredita
  atención o entrega real de solicitudes. La secuencia se documenta en el
  [plan de exportación integral](../plans/2026-09-12-exportacion-integral-implementation.md).
- Cierre A agrega preparación interna de ADMIN: resumen consistente de usuarios,
  trabajos y evidencia de renovación, sin iniciar una solicitud ni modificar datos.
  No es una autorización de cierre ni una prueba de cancelación remota. El plan
  [de cierre coordinado](../plans/2026-09-12-cierre-taller-implementation.md) separa
  estado/restricción, solicitud/restauración, eliminación y entrega mediante UI.
  B incorpora estado e historial durables, revocación y coordinación transaccional
  de escrituras, con acceso restringido del titular. C agrega el comando interno
  con contraseña/prueba de cinco minutos, propósito y confirmación escrita, además
  de constancia idempotente e intenciones durables. No habilita un procedimiento
  SQL de soporte ni un endpoint: D aporta mantenimiento temporal e inventario;
  su eliminación integral sigue pendiente. E ya implementó API/pantalla y sus recorridos
  locales de restricción/restauración y descarga previa. La activación productiva
  continúa apagada y requiere completar D y acreditar proveedores/despliegue.
  Los plazos técnicos de su política
  no sustituyen decisiones reales de retención ni habilitan publicación del borrador.
- Quitar una referencia de foto no prueba borrado del proveedor ni de backups.
  Un cierre deberá comprobar almacenamiento, retenciones y restauración de backups
  antes de afirmar eliminación. Las decisiones reales sobre esas categorías siguen
  pendientes.

Las limitaciones técnicas se registran y escalan; no convierten una solicitud
recibida en una baja completada. La primera salida necesita una vía ejecutable para
resolverlas, aunque parte de la atención sea manual.

## Ensayo previo a la salida

Con identidades y un taller sintéticos, comprobar recepción y respuesta en el buzón,
asignación de referencia/responsable, caso USER, caso ADMIN, cliente sin cuenta y
solicitante sin identidad verificable. Comprobar que no se revela información de
otro taller, que el caso continúa si la persona no puede iniciar sesión y que queda
constancia del resultado verdadero. Ensayar la entrega y ejecución cuando existan
las herramientas aprobadas; recibir el correo por sí solo no completa el ensayo.

Conservar fecha, entorno, versiones desplegadas, responsable y evidencias mínimas
en el registro privado. En Git sólo se documenta el resultado sin datos de personas.
Hasta cumplir estas condiciones, Confianza y cuenta sigue pendiente en la lista de
salida. No omitir los gates de publicación ni publicar documentos de ejemplo para salvar
esa falta de evidencia.

## Resultado del ensayo técnico local — 2026-09-12

El [acta del ensayo](../plans/2026-09-12-solicitudes-datos-baja-ensayo-local.md)
registra 14 recorridos de navegador → HTTP → PostgreSQL 16 aprobados. En escritorio
y 320 px, el empleado confirma su baja con contraseña: 204, sesiones rechazadas y
logout persistente, conservando el usuario, cliente y taller. ADMIN no puede usar
esa operación. También se descargaron y contrastaron los dos reportes operativos
reales; USER y anónimo no pueden obtenerlos. Este ensayo no elimina datos.

La matriz documental ENSAYO-01 a ENSAYO-05 aplica este procedimiento a pedidos
sintéticos. Baja personal verificada se distingue de solicitud integral de datos,
cierre del taller, cliente sin cuenta e identidad/destinatario no verificables.
Estos últimos conservan verificación, revisión o ejecución pendientes. No se
crearon expedientes reales ni se acreditó recepción/respuesta del buzón.

La herramienta de exportación A–D está implementada localmente; resta su activación
operativa y capacidad del despliegue. El cierre coordinado E está implementado y
ensayado localmente; siguen pendientes su eliminación integral y activación, las
reglas de retención/entrega y la atención real ya prevista.
No se reemplazan con el Excel operativo ni con SQL sobre `talleres.activo`.
Identidad/alta/contactos definitivos mantienen su etapa acordada; este resultado
no autoriza anunciar un cierre integral disponible ni omitir el gate de salida.

## Coordinación interna de cierre C

El reintento usa la misma operación y una sesión vigente; el JWT revocado por la
transición no sirve para recuperar la constancia. REUSED conserva las fechas y el
estado al confirmar, aunque después se haya restaurado el acceso. No amplía gracia
ni repite avisos o revocaciones. El contrato visual de E deberá distinguir esa
constancia histórica del estado actual. Nunca pedir contraseña o proof por correo.

Cada vínculo anterior de MP conserva su intención de cancelación tras restaurar.
Se conservan el plan y los datos económicos registrados, pero eventos de ese
vínculo no pueden conceder nuevos beneficios ni reutilizar su checkout. Una
contradicción o evidencia insuficiente exige revisión; FREE/ID ausente no demuestra
que el proveedor dejó de renovar. La app no procesa pagos taller–cliente.

Estados de efectos: PENDIENTE espera un intento o una identidad remota; EN_CURSO
tiene un permiso temporal; CONFIRMADO registra la confirmación tipada del puerto;
INCIERTO exige conciliación. Para avisos, CONFIRMADO significa aceptación del
servicio de envío, no entrega ni lectura. Un ACK perdido o ambiguo no permite
reenviar correo automáticamente. La cancelación inspecciona primero el objetivo
exacto y nunca aplica una respuesta histórica a otro vínculo vigente.

El worker sólo es invocable internamente. No hay scheduler ni adaptadores reales
de C; ausencia de puertos no consume trabajo ni informa éxito. Los futuros
adaptadores requieren timeout e idempotencia por clave estable: vencer el permiso
local no cancela una llamada externa que ya comenzó. Los estados inciertos y los
objetivos sin ID requieren el circuito de recuperación operativa pendiente.
La outbox no acredita eliminación de datos ni atención de una solicitud real.


## Mantenimiento y diagnóstico local D

El servicio interno `WorkshopClosureMaintenanceService.cleanExpired` limpia sólo el
cierre actual restringido, después de gracia, en cinco lotes de hasta 25 filas dentro
de una misma transacción. Purga tokens/pruebas vencidos (confirmaciones de cierre
sólo sin usar), vacía exportaciones vencidas y conserva su metadata al menos siete
días. Protege las confirmaciones utilizadas, operaciones, efectos, negocio y
aceptaciones. No invoca proveedores ni cambia el taller a ELIMINADO. Está probado
con datos sintéticos; no es un endpoint o comando operativo para cuentas reales.

`WorkshopClosureDeletionInventory.inspect` permite revisar categorías y cantidades
sin leer datos personales, archivos o identificadores remotos. Sus observaciones de
filas presentes/ausentes no son evidencia de borrado. Revisar fotos pendientes,
renovaciones sin confirmar y avisos inciertos; no quitar identidades, reiniciar
intentos ni marcar éxito manualmente. `payment_events` conserva una revisión global
porque no existe una pertenencia fiable para borrarlo por taller.

Para una copia restaurada, usar el [procedimiento de recuperación de backups](cierre-recuperacion-backup.md).
`WorkshopClosureBackupCheck` compara evidencia aportada con la copia y siempre
indica NO_AUTORIZA_REAPERTURA. La coincidencia parcial no reemplaza un registro
externo completo, ni implementa la cuarentena de arranque. Mantener el entorno
recuperado aislado mientras no se acrediten esos requisitos.

D sigue parcial: política real, supresión por categorías, cobertura de archivos legacy,
cargas tardías y copias remotas, resolución de efectos inciertos y recuperación
productiva requieren ejecución y verificación posteriores. V35 ya conserva objetivos
y resultados de las rutas de fotos privadas actuales, con los límites documentados. Una limpieza local sin pendientes nunca debe comunicarse
como baja o eliminación integral completada.


### Conciliación interna de renovaciones inciertas

Desde el corte del 2026-09-19, `WorkshopClosureRenewalReconciler.reconcile(tallerId,
effectId)` puede consultar un objetivo CANCELAR_RENOVACION/INCIERTO y registrar
CONFIRMADO sólo si el puerto informa CANCELED con ambas identidades exactas. El
servicio verifica pertenencia y que las filas no cambiaron durante la consulta;
no envía otra cancelación, no modifica el plan ni quita la marca de bloqueo del
vínculo. También sirve para un vínculo histórico después de restaurar el taller.

| Resultado | Interpretación interna |
| --- | --- |
| CONFIRMED | Confirmación durable de esta identidad; no acredita eliminación de datos. |
| REUSED | Ya estaba confirmado; sin consulta al proveedor ni escritura. |
| UNRESOLVED | Consulta fallida, ambigua, activa o identidad discrepante; continúa pendiente. |
| STALE | Captura vencida o registro cambiado; exige una consulta nueva. |
| NOT_ELIGIBLE | Objetivo ausente/ajeno, identidad incompleta, otro estado o tipo de efecto. |
| PORT_UNAVAILABLE | No hay puerto instalado; no se puede comprobar el resultado remoto. |

Errores SQL o gate ocupado rechazan la operación sin acreditar éxito; cualquier
escritura de confirmación fallida hace rollback. No resetear intentos ni editar
CONFIRMADO por SQL. Los IDs son argumentos internos, no autorización para soporte
ni para una API. El puerto real sigue sin instalarse; deberá acreditar la cuenta y
aplicación del proveedor, frescura de la observación y timeout. La operación no
llama al proveedor real por defecto y carece de scheduler/endpoint.

Los avisos de cierre/restauración tienen ahora adaptador SMTP opt-in (ver abajo).
Los avisos inciertos y REVISAR_RENOVACION conservan su circuito pendiente. El
registro de la suscripción de OrdenFix se mantiene separado del control opcional de
cobros del taller: esta conciliación no interviene en pagos taller–cliente.


### Borrado operativo por categorías — V37

El alcance confirmado comprende ítems de presupuestos, presupuestos, registros de
cobros, repuestos, reparaciones, equipos, clientes y artículos. La operación interna
`WorkshopOperationalDeletionService.deleteBatch(taller,cierre,lote,categoria)`
requiere V37 acreditada y opt-in explícito; permanece deshabilitada por defecto.
Es una herramienta de desarrollo/operación controlada sin endpoint; el scheduler
opt-in de la sección siguiente coordina su ejecución por categorías.
Los IDs no reemplazan autorización HTTP ni facultan a soporte a ejecutar SQL libre.

Cada invocación elige hasta 25 filas elegibles de una sola categoría. Procesar las
categorías en el orden del enum del servicio; un UUID distinto representa otro
lote, y repetir el mismo recupera su constancia original. DELETED acredita sólo
esas filas; REUSED conserva cantidad/fecha/pendientes originales. EMPTY indica que
no se eligieron filas, y `remaining=true` exige revisar dependencias pendientes.
No declarar completado por recibir EMPTY ni por sumar cantidades de replays.

Las fotos legacy y privadas sin borrado de identidad acreditado bloquean el trabajo.
Usar su circuito de resolución; no quitar URLs o recibos para sortear el control.
Las fotos privadas ya eliminadas conservan identidad y evidencia, aunque la FK
opcional a la reparación se desvincule. Referencias cruzadas o ciclos de garantías
requieren revisión, sin cascadas forzadas o ediciones de la constancia.

Usuarios, ancla/historial del taller, evidencia legal, fotos/recibos conservados y
suscripciones no se eliminan por este servicio. Los tokens/exportaciones mantienen
el mantenimiento temporal existente; sus copias no se incluyen en la constancia de
borrado de filas operativas. Continúan pendientes retención final, supresión de
identidad, efectos remotos restantes, registro externo y recuperación del despliegue.
El estado continúa RESTRINGIDO y el cierre productivo conserva su gate pendiente.


### Orquestación del borrado operativo

`WorkshopOperationalDeletionWorker.run(taller,cierre)` reconstruye lo pendiente
mediante `WorkshopOperationalDeletionProgress.read`. No usar los indicadores
históricos de un recibo como estado actual ni sumar DELETED/REUSED para calcular
el total eliminado. Los recibos V37 y las filas restantes sobreviven al reinicio;
el worker no necesita recrear una cola ni una marca de finalización.

| Resultado | Significado y continuación |
| --- | --- |
| NO_PENDING_ROWS | Las ocho categorías V37 están vacías en la captura válida indicada. No acredita baja integral ni borrado remoto. |
| WORK_REMAINS | Se agotó el presupuesto de lotes; continuar en otra ejecución. |
| WAITING_GRACE | Todavía rige la recuperación de siete días; no hubo nuevos borrados. |
| PHOTOS_PENDING | Hay fotos legacy o evidencia privada insuficiente; resolver por el circuito fotográfico. |
| DEPENDENCIES_PENDING | Un lote no encontró objetivos elegibles y la categoría todavía tiene filas; revisar dependencias/ciclos, sin forzar cascadas. |
| RETRY_LATER | Fallo desconocido o resultado incierto; detener esta ejecución y volver a observar en la siguiente. |

Cada resultado incluye la última observación exitosa y su fecha. En RETRY_LATER
esa captura puede ser anterior al intento incierto; no presentarla como resultado
final. Una respuesta perdida después de un commit se resuelve releyendo filas, sin
una segunda mutación a ciegas en la misma ejecución. Lo ya confirmado permanece.
El worker rechaza una transacción exterior: mantener un gate mientras se invoca
el servicio REQUIRES_NEW bloquearía la propia operación.

Los valores predeterminados son:

```properties
ordenfix.cuenta.cierre.operational-deletion-enabled=false
ordenfix.cuenta.cierre.operational-worker-enabled=false
ordenfix.cuenta.cierre.operational-worker-max-batches=4
```

El scheduler sólo existe con ambos flags en true. Espera sesenta segundos al
arrancar y entre rondas. Descubre dos talleres por página más un centinela y procesa
hasta cuatro lotes por taller por defecto, con un máximo configurable de ocho.
Cada lote conserva el máximo de 25 filas; los límites son por invocación, no una
cuota global entre instancias. La consulta usa gracia vencida según PostgreSQL y
el worker vuelve a acreditar alcance y precondiciones antes de borrar.

El cursor avanza incluso ante un taller bloqueado o fallido, y rota al inicio al
terminar. Ese cursor y `lastRun()` viven sólo en memoria; no son evidencia durable
ni un registro externo. Los logs contienen cantidades/estados, sin identificadores,
SQL ni causas internas. No se agregó transporte de alertas. Los roles de lectura
internos requieren las consultas nominales a ancla/historial/titular, las ocho tablas,
fotos y sus constancias; no se añaden permisos sobre `cuenta_borrado_lotes` ni se
modifican las ACL V37. No habilitar un rol con acceso ampliado para sortear preflight.

Esta configuración queda apagada en el corte. No habilita el cierre público,
no llama a MP/Cloudinary/email y no modifica las excepciones de conservación.
Las pruebas se ejecutan sólo con talleres sintéticos en PostgreSQL descartable.


### Avisos de cierre y restauración

El adaptador y scheduler de avisos quedaron implementados con opt-in apagado.
Consultar [configuración y estados de email](../operations/email.md#avisos-de-cierre-y-restauración--opt-in-local).
Los mensajes van únicamente al titular activo con email verificado y describen la
operación histórica, no el estado actual ni la finalización del borrado. Empleados,
clientes y destinatarios alternativos no reciben estos avisos.

El scheduler sólo toma AVISO_CIERRE/AVISO_RESTAURACION. No activa el adaptador de MP,
la eliminación operativa, el cierre público ni el borrado de identidad/evidencia.
CONFIRMADO significa aceptación SMTP más confirmación SQL; INCIERTO requiere revisión
sin reenvío automático. El cierre productivo conserva pendientes la prueba real de
proveedores, retención final, identidad y recuperación/operación del despliegue.
