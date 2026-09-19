# Ensayo local de recuperación de backups

Fecha: 2026-09-19. Continuación autorizada del corte D tras el cierre E.
Baseline backend: `2d73e39`; frontend: `18906e9`. Existen cambios ajenos de UI y
verificación de email sin commit: se preservan y quedan fuera de este corte.

## Diseño y alcance

Se ejecutará un backup lógico real con `pg_dump` y una recuperación con `pg_restore`
entre dos PostgreSQL 16 descartables. Sólo se usarán cuentas sintéticas. Se elige
este enfoque porque los fixtures SQL existentes prueban el comparador pero no el
archivo ni la recuperación del esquema. Una restauración de producción requiere
credenciales, evidencia externa y aislamiento del despliegue; queda fuera de este
ensayo local. No se levanta Spring Boot ni se instancian workers o proveedores.

Cada caso crea bases nuevas dentro de los contenedores, expuestos sólo en loopback.
Flyway inicializa exclusivamente la fuente; el destino se recupera vacío, incluyendo
esquema, datos, restricciones y ACL del dump. Los roles son sintéticos y conocidos:
no se acredita la recuperación de roles globales, claves, objetos remotos o WAL.
Los comandos tienen límite de ejecución y `pg_restore` aborta ante error dentro de
una única transacción. Los archivos temporales se eliminan al terminar las pruebas.

Los cierres/restauraciones usan gate exclusivo, Store y Effects reales en una única
transacción READ_COMMITTED, con pruebas/operaciones sintéticas coherentes con V34.
Se conservan activos todos los triggers. Este fixture no acredita autenticación HTTP
ni confirmación de contraseña, cubiertas por el corte E. Las exportaciones contienen
bytes sintéticos: prueban recuperación/revocación del payload, no un ZIP descargable.

## Escenarios y aceptación

1. Backup anterior a dos cierres: reaparecen ABIERTO, épocas anteriores de titular y
   empleados, exportaciones READY/QUEUED y ausencia de operaciones/avisos posteriores.
   El comparador detecta DATABASE_BEHIND; un tercer taller permanece igual.
2. Backup anterior a restaurar acceso: recupera RESTRINGIDO, épocas anteriores,
   exportación READY y aviso de cierre, aunque la fuente ya restauró y revocó el
   archivo. Detecta DATABASE_BEHIND y OWNER_EPOCH_BEHIND. El guard restaurado sigue
   rechazando escrituras operativas.
3. Backup actual: comparación compatible sin DML, siempre NO_AUTORIZA_REAPERTURA.
   Un taller creado/cerrado después del backup evidencia que una lista incompleta
   puede coincidir; al incluirlo aparece DATABASE_MISSING.
4. Archivo truncado: falla la recuperación y el destino queda sin esquema operativo;
   el comparador no puede emitir una comparación compatible.

Se comparan filas lógicas entre bases, y también versiones de fila dentro de una
misma base para comprobar que dump/diagnóstico no modifican los registros. Se
verifican Flyway, secuencias y triggers restaurados. No se crea reconciliación,
journal externo, eliminación terminal ni gate automático de cuarentena.

## Implementación y entrega

- Añadir `WorkshopClosureBackupRecoveryIT` independiente, sin cambiar código de
  producción ni migraciones V27–V34 congeladas.
- Ejecutar la nueva IT y `WorkshopClosureBackupCheckIT`/`WorkshopClosureBackupCheckTest`.
  Reservar el integral para cambios transversales o defectos que lo justifiquen.
- Registrar resultados y límites en el runbook y el plan de cierre. Guardar en el
  frontend el pendiente de equipos de distintos tipos, sin implementar esa mejora.
- Verificar hashes de todos los cambios ajenos y migraciones congeladas. Un commit
  atómico por repositorio afectado, sólo con archivos propios. Sin push ni merge.

## Resultado verificado

Aprobado el 2026-09-19 a las 09:25:43 -03. Java 21, PostgreSQL 16 y Docker locales.
`verify` focal terminó **BUILD SUCCESS en 48,616 s**: **27 casos** sin fallos,
errores ni omisiones (15 unitarios del comparador, 8 IT existentes y 4 IT nuevas).
También aprobó el control de empaquetado sin propiedades secretas. Log descartable:
`/private/tmp/ordenfix-backup-recovery-final-20260919.log`.

El primer pase también aprobó 27 casos en 1:33 min. La revisión amplió el control de
otro taller a filas completas de taller, usuarios y suscripción; la repetición final
anterior valida esa versión. No se cuentan las repeticiones como pruebas distintas.

Los cuatro escenarios recuperaron backups lógicos reales; el destino nunca se
inicializó con Flyway. La copia anterior al cierre recuperó ABIERTO/épocas 0 y trabajos
previos; la anterior a restaurar recuperó restricción/épocas 1 y bytes del archivo
revocado en la fuente. El diagnóstico detectó los retrocesos previstos, sin DML ni
reconciliación. La copia coincidente mantuvo NO_AUTORIZA_REAPERTURA; agregar un taller
posterior a la evidencia reveló su ausencia. El archivo truncado falló sin tablas
operativas y el comparador rechazó la consulta. La comprobación incluye un empleado
inactivo que permanece inactivo en ambos lados, secuencias, triggers, 34 migraciones
y rechazo P0033 de escritura en el taller restringido recuperado.

La compilación utilizó el working tree actual; estos casos no validan los cambios
ajenos de verificación de email o UI. Frontend sólo recibió documentación, por lo que
no se ejecutaron sus suites. No se repitió clean verify: no cambió código productivo,
seguridad compartida ni migraciones, y la ejecución focal no encontró un defecto.

El ensayo local queda cerrado. **D continúa parcial** por política de conservación,
supresión integral, constancias remotas, journal externo y reconciliación/cuarentena
del despliegue. No se activaron flags, servidores normales ni proveedores.


Verificación de preservación: los 18 archivos previos del backend y los 255 del
frontend conservan sus SHA-256, al igual que las ocho migraciones V27–V34. Los
puertos normales 8080/5173 permanecen sin listener. Se incluyen exclusivamente la
nueva prueba y documentos propios, con un commit atómico por repositorio y sin push.
