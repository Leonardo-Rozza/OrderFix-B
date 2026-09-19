# Conciliación de renovaciones inciertas al cerrar un taller

Fecha: 2026-09-19. Continuación técnica del apartado D del plan de cierre, autorizada
por el pedido de avanzar con cierres/datos. Alcance interno y local; sin proveedores
reales, HTTP, scheduler, cambios en la política de retención ni habilitación pública.

## Problema y decisión

El worker V34 deja en INCIERTO las cancelaciones ambiguas, contradichas o agotadas.
La marca bloquea renovar ese vínculo aun después de restaurar el taller, pero no
existía una operación para acreditar que el proveedor finalmente lo canceló.

Se evaluaron reiniciar el worker, reclamar un lease de conciliación y consultar
sin reclamar trabajo. Reiniciar intentos puede volver a cancelar. Reutilizar
EN_CURSO permitiría al worker retomar un lease vencido como cancelación; distinguir
su propósito requeriría otro contrato. Se elige la consulta con confirmación
condicionada a versiones de filas, sin lease y sin modificar V27–V36.

## Contrato e implementación

`WorkshopClosureRenewalReconciler.reconcile(tallerId, effectId)` es exclusivamente
para un orquestador interno confiable. Los IDs no acreditan autorización HTTP.
Admite sólo CANCELAR_RENOVACION con identidad remota completa, operación de cierre,
historial, titular y vínculo pertenecientes al mismo taller. Es aplicable también a
un cierre histórico después de restaurar; nunca opera sobre el vínculo sustituto.

1. Captura estado, identidad y versiones `xmin` del efecto y del vínculo. Una
   captura es efímera, no un token persistente ni una revisión portable entre DBs.
2. Un efecto confirmado devuelve REUSED sin proveedor ni DML. Otra clase/estado,
   objetivo ajeno/ausente o identidad incompleta devuelve NOT_ELIGIBLE. Sin puerto
   instalado devuelve PORT_UNAVAILABLE sin mutaciones.
3. Consulta `ClosureRenewalPort.inspect` fuera de transacciones. Sólo CANCELED con
   ambas identidades exactamente iguales habilita confirmación. Ausencia, error,
   estado activo, respuesta temporal o contradicción dejan UNRESOLVED sin DML.
4. Revalida la captura con locks vínculo FOR SHARE → efecto FOR UPDATE. Cualquier
   cambio de `xmin`, incluso una escritura que conserva valores, produce STALE.
   Sólo la captura vigente cambia INCIERTO a CONFIRMADO con fecha durable V34.
5. El límite de frescura de 120 s se comprueba antes de consultar, después del I/O,
   después de adquirir locks y después del UPDATE. Vencimiento durante la escritura
   hace rollback. Retroceso del reloj también rechaza la captura.

Ambas fases SQL usan REQUIRES_NEW/READ_COMMITTED, timeout transaccional 5 s,
statement_timeout 3 s y lock_timeout 2 s. Adquieren primero el gate compartido V33,
sin esperar detrás de una transición exclusiva. Se rechaza una transacción del
llamador para garantizar que el I/O remoto queda fuera de ella. El orden de locks
coincide con la observación tardía V34. Dos consultas concurrentes son inocuas:
pueden leer el proveedor, pero sólo una confirma la captura original.

La operación no llama `cancel`, no envía avisos, no cambia intentos/disponibilidad,
no toca planes/vínculos, no quita la marca de bloqueo y no procesa un lote global.
Una observación local posterior puede volver a invalidar la confirmación mediante
el trigger V34 existente. No se afirma orden absoluto entre eventos externos: el
adaptador futuro debe comprobar cuenta/aplicación del proveedor y vigencia de su
respuesta, además de identidad. Los timeouts SQL no limitan el I/O; el adaptador
deberá imponer su propio timeout. El plazo impide acreditar respuestas vencidas.

## Validación focal ejecutada

PostgreSQL 16 con puertos sintéticos: confirmación, replay sin DML, ausencia de
puerto, respuestas ambiguas y ajenas, pertenencia, límite temporal, rollback al
vencer después del UPDATE, espera de locks, cambio de ambas versiones, dos
conciliaciones concurrentes y vínculo histórico tras restaurar. Se reejecutaron la
suite completa de efectos y la de coordinación MP/cierre para proteger el worker y fences.
No se modifica esquema, frontend ni arranque; no requiere otro clean verify integral.

## Pendientes de D

La resolución automática de REVISAR_RENOVACION y de avisos inciertos, alertas,
adaptador real de conciliación, registro externo y barrera de recuperación siguen
pendientes. También la política de retención y la supresión definitiva del taller.
Confirmar una cancelación de la suscripción de OrdenFix no acredita eliminación
de datos, cierre integral ni intervención en pagos taller-cliente.


Resultado final: **118 pruebas aprobadas**, sin fallos, errores ni omisiones:
57 unitarias (`ClosureRenewalAssessmentTest`: 38; `MercadoPagoClosureStateTest`: 19)
y 61 IT (`WorkshopClosureEffectsIT`: 43, incluidas 22 nuevas;
`WorkshopClosureDeletionInventoryIT`: 15; `MercadoPagoClosureIT`: 3).
BUILD SUCCESS, Java 21 y PostgreSQL 16, 2026-09-19 18:36:09 -03, 1:06 min.
También aprobó el control de empaquetado sin propiedades secretas.

Comando focal (entorno de pruebas sin importaciones de configuración real):

```sh
./mvnw -B -Dtest=MercadoPagoClosureStateTest,ClosureRenewalAssessmentTest \
  -Dit.test=WorkshopClosureEffectsIT,MercadoPagoClosureIT,WorkshopClosureDeletionInventoryIT verify
```

Evidencia local: `/private/tmp/ordenfix-closure-reconciliation-20260919/focal.log`,
`test-summary.json` y `reports/` en ese mismo directorio. Revisión independiente
sin hallazgos accionables. V27–V36 conservadas, frontend y sus 179 archivos ajenos
sin cambios. No se ejecutó clean verify completo ni se invocó ningún proveedor real.
Entrega en un commit atómico `feat(cuenta): concilia renovaciones inciertas del cierre`,
sin push, merge, despliegue ni modificación de la base local de navegación.
