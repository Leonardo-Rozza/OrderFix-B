# Conservación y baja de identidad — corte de perfil local

## Objetivo y decisiones

El usuario pidió continuar con conservación/baja final de identidad y evidencia.
Se mantiene el borrado operativo autorizado tras siete días y la separación de
constancias legales y suscripción. No se ha definido un plazo nuevo de conservación
para estas últimas: se pidió esa información y no se inventa un plazo legal.

Alternativas revisadas: DELETE físico de usuarios/taller rompe referencias de
constancias; declarar ELIMINADO ahora confunde perfil suprimido con evidencia/backups
eliminados. Se elige retirar perfil y credenciales conservando filas técnicas inertes.
No es anonimización total: quedan IDs y referencias relacionables.

El corte A entrega V38 y servicio interno opt-in para suprimir en una transacción
el perfil del taller y todos sus usuarios (hasta 1.000), contactos/datos de cobro y
QR. No agrega scheduler ni endpoint. El flag queda false y sólo se prueba con datos
sintéticos; habilitación productiva requiere la política y operación de salida.

## Matriz del alcance

| Categoría | Acción del corte A | Conservación pendiente |
| --- | --- | --- |
| Usuario titular y empleados | Sustituir nombre visible, email por UUID aleatorio bajo dominio `.invalid`, password por valor inutilizable; active/email_verificado=false; incrementar época una vez | Mantener IDs, rol y pertenencia para referencias. No copiar el email original ni conservar un hash nuevo del mismo. El email original queda disponible para una cuenta nueva con otro ID y sin vínculo/acceso a la anterior. |
| Taller | Nombre neutro; contactos, alias, titular y entidad de cobro NULL; mostrar_en_resumen=false | Mantener ID, estado RESTRINGIDO, historial y fechas. `activo=true` sólo conserva el ancla técnica; todos los usuarios permanecen inactivos. |
| QR | Eliminar fila PNG/huella local | No acredita borrado de WAL/backups. |
| Tokens/pruebas/ZIP | Deben haberse limpiado antes: sin tokens ni pruebas de exportación residuales ni payloads/exportaciones no terminales | Se conserva metadata terminal bajo el mantenimiento existente. Las confirmaciones de cierre usadas siguen inmutables. |
| Datos operativos y fotos | Exigir ocho categorías vacías y preflight de fotos V37 aprobado | Nombres originales de archivos, IDs/huellas/recibos fotográficos siguen fuera de este corte. |
| Aceptaciones/constancias de cierre | Sin modificaciones | Fijar finalidad, fundamento, responsable, inicio y vencimiento antes de ejecutar su supresión. |
| IP/user-agent legales | Mantener contrato actual de cifrado/purga por vencimiento ya registrado | No acortar ni extender vencimientos por cerrar la cuenta. |
| Suscripción de OrdenFix y proveedor | Sin modificaciones | No son cobros taller–cliente. Retención propia y conciliación MP pendientes. `payment_events` no tiene pertenencia fiable por taller. |
| Backups y proveedores | Sin ejecución remota | Comprobar recepción, retención, supresión y recuperación real en despliegue. |

## Frontera transaccional

- Nueva migración V38; V27–V37 y constantes históricas se preservan.
- Servicio REQUIRES_NEW/READ_COMMITTED, gate exclusivo del taller primero, timeout
  transaccional de 10 s y SQL de 5 s. SQL acredita cierre/referencia/generación y
  gracia vencida; ninguna bandera de sesión autoriza la excepción de escritura.
- Contexto privado de capacidad, shape exacto y pertenencia de filas. Los triggers
  originales siguen atendiendo el resto de operaciones. Sin deshabilitar triggers,
  cascadas ni permiso de escritura directa a la tabla de constancias/contextos.
- Constancia inmutable sin valores personales originales. Replay no hace DML ni
  modifica fechas, pseudónimos o épocas. Colisión de operación/referencia falla.
- Ante datos pendientes, exceso de capacidad, overflow de época, drift, destinatario
  ajeno o fallo SQL: rollback completo y resultado no acreditado.
- Una lease SMTP de aviso vigente hace esperar; un aviso INCIERTO no retiene el perfil
  indefinidamente. No modificar ni bloquear filas de efectos bajo el gate exclusivo:
  su worker usa un orden distinto. Tras la supresión el adaptador no resuelve un
  titular activo/verificado y no puede iniciar un nuevo correo. No se promete cancelar
  un SMTP que ya había empezado y terminó después de vencer su lease.

## Recuperación y validación

La captura anterior no incluía nombre/email/password/contactos. El nuevo perfil debe
acreditar la constancia y el estado saneado, sin copiar valores originales al registro
externo. Un restore que reponga PII incluso conservando la misma época debe quedar
bloqueado. Versionar la evidencia al cambiar su semántica; un checkpoint anterior no
acredita por sí solo un entorno V38. Verificar catálogos exactos migrados/restaurados,
conservando los perfiles históricos.

Pruebas: aislamiento entre talleres, scope/gracia, capacidad/overflow, rollback,
replay sin DML, credenciales inutilizadas, contacto/QR retirados, referencias intactas,
lease SMTP, drift/permisos, compatibilidad V37 y restauración que repone perfil.
La migración toca contratos compartidos: se requiere clean verify completo al cierre,
además de pruebas focalizadas durante implementación. Un commit atómico, sin push.

## Secuencia restante, sin confundir alcances

A. Perfil/credenciales/QR y respaldo compatible: este corte.
B. Evidencia retenida: aplicar política concreta por categoría; incluir nombres de
   archivos, constancias, identificadores de proveedor y purga de correspondencias.
C. Estado terminal: sólo tras acreditar el alcance real, retenidos declarados,
   proveedores y recuperación del entorno. No convertir RESTRINGIDO en ELIMINADO
   como sustituto de esos resultados.

## Fuentes y límite de la decisión

La [Ley 25.326](https://www.argentina.gob.ar/normativa/nacional/64790/actualizacion),
arts. 4, 16 y 25, exige finalidad/necesidad y distingue supresión procedente de
retenciones justificadas. No fija un plazo universal de años para todas estas
categorías. La ventana comercial de siete días no sustituye el tratamiento de una
solicitud de derechos. El plazo excepcional del art.25 no es un mínimo obligatorio.
El [Decreto 1558/2001](https://www.argentina.gob.ar/normativa/nacional/decreto-1558-2001-70368/actualizacion)
separa tratamiento por encargo y eliminación por pérdida de finalidad.

Según el criterio de determinabilidad de la
[Resolución AAIP 4/2019, Anexo I](https://www.argentina.gob.ar/normativa/318874_res4AAIP_pdf/archivo),
retirar identificadores directos conservando referencias relacionables no basta para
acreditar anonimización. La matriz operativa deberá validarse con los contratos y
situación real del prestador; este corte no publica textos legales ni fija obligaciones
fiscales o contables.
