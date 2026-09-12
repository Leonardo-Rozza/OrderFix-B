# Corte A — Excel operativo legible

Fecha: 2026-09-12. Rama: `codex/lanzamiento-publico-backend`.
Base: `35ecb8f`. Corte aprobado por el titular a partir del plan frontend
`docs/plans/2026-09-12-pulido-local-y-pendientes.md` (A).

## Resultado

`GET /api/export/excel` conserva acceso ADMIN en cualquier plan, las consultas por
taller y las cuatro hojas con los mismos nombres, columnas, registros y cálculos.
Los cobros anulados permanecen en el historial y se excluyen de las sumas como antes.

El archivo agrega encabezados de marca con contraste reforzado (`#0B6F66`), filas
alternadas, fuente Arial de 11 puntos, sangría interior, filtros hasta la última fila
y primera fila congelada. Los anchos son explícitos y acotados; los campos de texto
se envuelven y el alto de fila se estima respetando el máximo de Excel. El texto
completo permanece en la celda si alcanza ese máximo. Se comparten once estilos
nuevos por libro, sin crear estilos por registro ni autoajustar todas las columnas.

Se agrega alcance en la nota del primer encabezado de cada hoja, propiedades del
archivo y pie de impresión. Es un reporte operativo de clientes, órdenes, cobros y
presupuestos, sin validez fiscal. Los cobros son registros manuales de pagos recibidos
por fuera de OrdenFix; el archivo no acredita verificación de pagos ni exportación
integral de datos/archivos. No incorpora hojas, totales ni filas decorativas nuevas.

## Compatibilidad de los datos

- Importes siguen siendo valores numéricos, ahora con formato ARS y dos decimales.
- **Las fechas cambian de texto a valores de fecha Excel.** `LocalDate` se presenta
  como `dd/MM/yyyy`; `LocalDateTime`, como `dd/MM/yyyy HH:mm`. No hay conversión de
  zona horaria. El valor subyacente conserva los segundos aunque la vista muestre minutos.
  Los consumidores que parseaban cadenas deben admitir el tipo fecha del lector XLSX.
- Teléfono, IMEI, número de orden y contenido escrito por el taller continúan como
  texto literal: se preservan ceros iniciales y prefijos `=`, `+`, `-` o `@` sin fórmulas.
- Opcionales ausentes quedan vacíos. Se conservan IDs numéricos, enums, orden de
  columnas, nombre fechado de descarga y fórmulas de negocio del servicio existente.

## Validación local

- **5 tests backend aprobados:** `ExportTests` (2, Spring/MockMvc/H2) y
  `ExportServiceTest` (3, mocks de repositorios y XLSX real). Se comprueban aislamiento,
  rechazo del empleado, importes/saldos/anulaciones, libro vacío, rangos de filtros,
  tipos, horas, texto literal y opcionales. Los asserts de importes leen el valor
  numérico en vez del texto formateado. No son pruebas contra PostgreSQL.
- La prueba de tipos genera opcionalmente una muestra sintética con
  `-Dordenfix.export.sample=/ruta/ordenfix-ejemplo.xlsx`; no escribe archivos por defecto.
  El directorio elegido debe existir. La muestra no contiene datos reales ni credenciales.
- Se importó y renderizó el XLSX de prueba en cuatro hojas, dividiendo Órdenes en
  dos rangos para inspeccionar sus 18 columnas. Se revisaron encabezados, importes,
  horas, bandas y textos largos. La revisión motivó oscurecer el verde y usar Arial,
  disponible en el equipo, en lugar de una fuente susceptible de sustitución.
- **Límite del visor:** el render de `artifact-tool` interpreta algunos identificadores
  numéricos de texto sin ceros/prefijos y omite sangría/filtros/notas. Las pruebas POI y
  una lectura independiente del OOXML acreditan las cadenas originales completas y
  ausencia de fórmulas. Las imágenes no sustituyen esa comprobación de datos.
  La apertura interactiva en Numbers agotó el tiempo de espera y no se acredita.
- La descarga frontend se verificó por separado: **13 Vitest y 3 Playwright Chromium
  aprobados**, typecheck y lint focalizado. El E2E usa una API simulada y no acredita
  integración frontend → backend → PostgreSQL ni un despliegue.

Comando backend (Java 21, sin importar secretos ni arrancar proveedores):

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home ./mvnw -Dtest=ExportServiceTest,ExportTests test
```

Los casos focalizados se repitieron al ajustar contraste/sangría y fuente; no hubo
fallos de aplicación. No se ejecutó `clean verify`, según la política del corte.
No se modificaron migraciones V27/V28, configuración privada, planes, roles ni
integraciones externas. Sin push ni merge.
