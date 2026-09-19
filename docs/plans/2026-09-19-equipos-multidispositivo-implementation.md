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

Base V35 `826ea28`. A integrado después del commit de constancias; B preparado en
frontend y C preparado en copia aislada. No se mezcla C en el commit A.

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
clases indicadas y `test`, sin llamadas a proveedores. Gate integral pendiente de C.
