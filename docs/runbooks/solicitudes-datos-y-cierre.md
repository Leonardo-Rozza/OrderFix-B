# Atención de solicitudes de datos y cierre

Estado al 2026-09-12: procedimiento preparado, pendiente de habilitación operativa.
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
  cobros y presupuestos. No incluye todas las categorías/archivos ni incorpora la
  reautenticación y entrega previstas para la exportación integral. No sirve como
  respuesta automática a una solicitud individual de un empleado o cliente.
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
