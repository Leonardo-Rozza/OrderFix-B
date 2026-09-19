# Equipos de distintos tipos — implementación

Fecha: 2026-09-19. Continuación autorizada por el usuario después del corte de
cierres/datos. Se conserva el alcance de
la nota previa `docs/plans/2026-09-19-equipos-multidispositivo-pendiente.md` del frontend.

## Decisión

Agregar clasificación al equipo, conservando el mismo circuito de reparación,
evidencias, presupuesto y entrega. Los registros económicos siguen describiendo
cobros realizados fuera de OrdenFix y no tienen validez fiscal.

Se elige una lista acotada de categorías sobre el equipo. Cambiar sólo etiquetas
no permitiría filtrar; crear un flujo y modelo distinto por categoría duplicaría
reglas que hoy son comunes. No se agregan campos técnicos particulares por marca.

Contrato: CELULAR, NOTEBOOK, CONSOLA, PC_ESCRITORIO, MONITOR, TV y OTRO. Los registros
existentes quedan OTRO (mostrado como «Otro / sin clasificar»), sin inferir el tipo
por marca, modelo o identificador. El campo `imei` conserva nombre y límites en la
API/base; se presenta como «Serie / IMEI». Sigue siendo opcional.

Las credenciales siguen perteneciendo a la reparación y siendo opcionales/cifradas.
Cambiar categoría no borra valores. La interfaz orienta qué dato puede corresponder,
permite ampliar los campos de acceso y mantiene visibles los datos ya cargados.
Omitir un campo en edición conserva su valor; borrarlo explícitamente mantiene el
contrato actual. No cambian límites, tipos de cuenta vinculada ni cifrado.

## Cortes

### A — Contrato y persistencia

- V36 nueva después de congelar V35: columna tipo no nula con DEFAULT OTRO y CHECK.
  No usar UPDATE de backfill sobre talleres restringidos ni desactivar sus guards.
- Alta normal e ingreso rápido: tipo opcional; omisión crea OTRO. Edición antigua
  que omite tipo conserva el actual. Respuestas incluyen tipo y equipoTipo.
- GET equipos acepta filtro exacto tipo, combinado con búsqueda/paginación/taller.
- Admitir V36 explícitamente en historia Flyway y preflight sin reemplazar huellas
  anteriores. Mantener capacidad V35 de fotos/registro; no ampliar roles legales.
  La columna empresarial no se agrega al catálogo legal: Flyway/JPA y las pruebas
  PostgreSQL acreditan su contrato, igual que otras extensiones de negocio.
- Pruebas focales de categorías, compatibilidad, ambas altas, invalidación y tenant.

### B — Formularios y navegación

- Selector en equipo e ingreso rápido. Mostrar categoría al elegir equipo existente.
- Filtro de tipo en URL/query keys, conservando búsqueda y paginación coherentes.
- Etiquetas Serie / IMEI en formularios, listados y detalle autenticado.
- Ayuda de acceso acorde al equipo; información existente nunca se pierde al cambiar
  de categoría o esconder campos opcionales.
- Conservar las mejoras de UI del usuario y los componentes establecidos.
- Pruebas de formularios, filtro/navegación, payloads y edición con credenciales.

### C — Exportación y evidencia final

- Tipo y etiqueta Serie / IMEI en Excel, conservando estilo y contenido legible.
- Tipo en snapshots JSON nuevos del ZIP de cuenta. Los paquetes ya preparados
  conservan bytes, hash y vencimiento; no se regeneran retrospectivamente.
- No ampliar información en enlaces públicos por este cambio.
- Recorridos representativos de celular, notebook y consola/TV con/sin identificador,
  edición y exportación. Gate integral al cerrar por migración/preflight compartido.

Un commit atómico por repositorio y corte, sin push. Pruebas focales en A/B y
validación integral al cerrar C, salvo fallo transversal que exija adelantarla.

## Estado backend

Base V35 `826ea28`. A entregado en `e3f43bc`, B en frontend `86e5138`.
C implementado y validado; se entrega en un commit separado.

La integral V35 se interrumpió con fixtures antiguas ya identificadas; sus cuatro
clases corregidas aprobaron 104 casos. La integral final C ejecutará todo el estado
V35/V36. Este orden evita repetir dos gates completos durante la misma continuación.

### A — entregado localmente

- V36 checksum `-1571524851` medido con `LegalV36SchemaSnapshot` en PostgreSQL 16.
  Cinco catálogos legal/cierre/fotos mantienen exactamente sus huellas V35.
- 97 casos focales únicos aprobados: EquipoTipoFlowTests 19, ReparacionFlowTests 6,
  IngresoEnriquecidoTests 2, DeviceCredentialSecurityTests 4, TenantIsolationTests 6,
  EquipmentTypeMigrationIT 14, LegalV36CompatibilityIT 9, PostgresMigrationIT 8 y
  LegalV35PhotoDeletionSchemaVerifierIT 29. No se suman repeticiones.
- Upgrade 35→36 con equipos existentes y taller restringido: conserva valores,
  xmin/ctid y guards, asigna OTRO sin inferencias ni UPDATE de backfill.
- La primera focal detectó un cast requerido en la consulta de catálogo del test y
  que la constante todavía no contenía el checksum medido. Corregidos; las dos suites
  afectadas aprobaron sus 23 casos completos. No cambió V36 SQL ni huellas históricas.
- Revisión independiente A/B sin hallazgos; sin push.

Evidencia local: `/private/tmp/ordenfix-multidispositivo-backend-20260919/`:
`snapshot.log`, `cut-a-focal.log`, `cut-a-retry.log`. Comandos Maven `-Dtest=` con las
clases indicadas y `test`, sin llamadas a proveedores. El gate final se registra a continuación.


### B — frontend entregado

Frontend `86e5138`: selector en ambas altas, filtro URL/query key, categoría en
listas/detalles y Serie / IMEI. Edición omite tipo si no cambia; conserva credenciales.
996 pruebas en 112 archivos, TypeScript, ESLint focal y build local aprobados.

### C — exportación y recorrido integrado

Excel: 19 columnas de órdenes con categoría legible y Serie / IMEI; mantiene formatos
de dinero/fechas, identificadores como texto y ausencia de credenciales. ZIP: agrega
`tipo` a snapshots nuevos; los artefactos preparados conservan bytes/hash/vencimiento.
No se amplían los enlaces públicos.

78 casos focales aprobados: ExportServiceTest 3, ExportTests 2,
ProtectedExcelExportServiceTest 14, WorkshopExportSnapshotServiceIT 20,
ProtectedExcelExportIT 8 y LegalPrivatePhotoOperationsIT 31.

Cuatro recorridos de navegador + una comprobación JUnit aprobados sobre PostgreSQL
16 y almacenamiento sintético: celular/TV nuevos, notebook/consola existentes,
titular/empleado, con y sin serie, fotos/reintento/aislamiento/borrado con recibos,
filtro y edición de color que omite tipo. La comprobación SQL acredita conservación
de todos los demás datos existentes. Sin llamadas a Cloudinary, email o MP.

Se conserva evidencia descartable en el directorio del corte (`cut-c-focal.log`,
`browser.log`, `browser-screenshots/`). La integral final y sus correcciones focales se registran a continuación.
La repetición de capturas estables se registra al final.


### Orden de despliegue posterior

Aplicar backend/V36 primero y después frontend B/C. El backend conserva compatibilidad
con formularios anteriores que omiten el tipo; la UI nueva necesita ese contrato
para guardar y filtrar categorías. Los roles de fotos deben tener los grants nominales
V35 documentados en `docs/operations/private-photos.md` antes de activar el servicio.
V36 no agrega grants legales/fotos. No se ha ejecutado este despliegue ni se alteró
la base local de uso habitual: los ensayos utilizan bases temporales.


### Gate final — 2026-09-19

`./mvnw -B clean verify` ejecutó la suite completa en 35:16 min: 8.010 pruebas
unitarias aprobadas (265 clases) y 1.838 IT (124 clases), con dos fallos de fixture,
sin errores ni omisiones. Esa ejecución terminó BUILD FAILURE; se conserva el log
original y no se presenta como un `clean verify` verde.

Las dos correcciones afectan exclusivamente pruebas:

1. LegalV29AcceptanceSchemaVerifierIT esperaba nueve filas máximas de historia;
   V27–V36 requieren diez versiones más un sentinel de rechazo: once. La prueba
   conserva la comprobación de rechazo de historia desconocida.
2. PersonalAccessExitIT usaba un ADMIN sin email verificado para reactivar al empleado.
   Sólo ese ADMIN se marca verificado antes del login. El empleado permanece sin
   verificar; se conserva la cobertura de baja, revocación de sesiones y tokens
   antiguos que no recuperan validez tras reactivarlo.

Repeticiones focales aprobadas, sin errores ni omisiones:
`-Dtest=LegalV29AcceptanceSchemaVerifierIT,LegalV36CompatibilityIT test` (41 casos) y
`-Dtest=PersonalAccessExitIT test` (15 casos). No cambió código productivo después de
la integral, ni se repitió toda la suite por estos ajustes de fixtures. Combinando la
integral y esas repeticiones, todas sus clases tienen resultado aprobado; no quedan
fallos pendientes. Evidencia: `final-clean-verify.log`, `final-integral-reports/`,
`final-sentinel-correction.log`, `final-exit-fixture-correction.log` y
`final-corrected-reports/` dentro del directorio local del corte.

La baja definitiva de todo el taller continúa fuera de este cierre: el mantenimiento,
los recibos de fotos y la clasificación de equipos no sustituyen política real,
supresión por categorías ni recuperación del despliegue. No se activaron proveedores,
schedulers productivos, publicación ni push.


### C — cierre local

La repetición final de navegador aprobó nuevamente los cuatro escenarios y la
comprobación JUnit (`browser-stable.log`, BUILD SUCCESS). Veinte capturas conservadas
en `browser-screenshots-stable/`; se revisaron visualmente listado a 320 px y edición
de notebook/TV en escritorio/móvil, sin desbordamiento horizontal. Las capturas de
equipos desactivan sólo las animaciones durante la toma; no cambió la UI por ese ajuste.

A/B/C quedan implementados y probados localmente. No quedan defectos abiertos de este
alcance. Se preservan las diez migraciones V27–V36 y los 179 archivos ajenos no
versionados del frontend. Entrega atómica por repositorio/corte, sin push.
