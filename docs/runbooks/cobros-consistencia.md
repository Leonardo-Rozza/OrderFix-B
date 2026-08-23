# Runbook — consistencia de cobros manuales y validaciones monetarias

Fecha: 2026-08-23
Alcance: cobros operativos registrados manualmente por cada taller y checks monetarios agregados en V21.

## Objetivo y límites

Este runbook permite:

1. detectar, sin modificar datos, órdenes cuyo cobrado activo supera el total que calcula la aplicación;
2. revisar y corregir cada caso mediante la operatoria auditable de OrdenFix;
3. decidir cuándo los 12 checks `NOT VALID` de V21 están listos para una migración posterior de
   `VALIDATE CONSTRAINT`.

No concilia bancos ni billeteras, no confirma acreditaciones y no determina obligaciones fiscales.
Los cobros de este módulo son registros manuales. Este documento no contiene ni autoriza `UPDATE`,
`DELETE` o correcciones directas sobre tablas, y no debe usarse para ejecutar migraciones ad hoc.

## Reglas de seguridad

- Abrir un ticket/incidente con responsable, ambiente, ventana y `taller_id` exacto antes de consultar.
- Usar una credencial de base **read-only** y, si existe, una réplica de lectura. Nunca reutilizar la
  cuenta de migraciones Flyway para el diagnóstico.
- Bindear `:taller_id` como parámetro numérico del cliente SQL; no concatenar texto ni aceptar un ID
  copiado desde el navegador como única evidencia.
- Cada join y agregado operativo debe conservar el tenant. La única excepción es el preflight, que
  compara desigualdad y devuelve sólo conteos; no quitar filtros para “buscar más rápido”.
- No seleccionar el BYTEA `taller_qr_cobro.png`, credenciales de dispositivos ni otros secretos.
- Guardar evidencia mínima: IDs internos, importes, timestamps y decisión. Evitar datos personales en
  logs, chats o tickets.
- Las anulaciones se hacen por la API/UI canónica con ADMIN y motivo; nunca borrando o actualizando
  filas a mano.

## Definiciones que deben coincidir con el backend

- **Cobro activo:** `cobros.anulado_at IS NULL`.
- **Mano de obra:** `COALESCE(precio_final, precio_estimado, 0)`.
- **Repuestos:** suma de `COALESCE(precio, 0) * GREATEST(1, cantidad)`.
- **Total mostrado:** máximo entre mano de obra + repuestos y cero.
- **Cobrado mostrado:** suma de cobros activos, normalizada a un mínimo de cero.
- **Saldo:** `max(total - cobrado, 0)`.
- **Excedente:** `max(cobrado - total, 0)`; si es positivo, `requiereRevision=true`.

La normalización a cero reproduce `EstadoCuentaOrden`. El `GREATEST(1, cantidad)` reproduce el
comportamiento de `Reparacion.calcularTotalRepuestos()` incluso ante cantidades legacy inválidas.
Esos valores legacy también deben aparecer en la auditoría separada de V21.

## 1. Abrir una sesión de diagnóstico read-only

La sintaxis de bind puede variar por cliente. Los ejemplos usan `:taller_id` y, cuando corresponde,
`:reparacion_id`.

```sql
BEGIN;
SET TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '2s';

SELECT id, nombre, activo
FROM talleres
WHERE id = :taller_id;
```

Debe regresar exactamente el taller autorizado en el ticket. Cero filas o una identidad inesperada
detienen el procedimiento. Al terminar cualquier bloque de diagnóstico:

```sql
ROLLBACK;
```

`ROLLBACK` cierra deliberadamente la transacción read-only; no es una forma de hacer correcciones
temporales.

## 2. Detectar cobrado activo mayor que el total actual

### 2.1 Preflight de relaciones tenant

El esquema tiene FKs simples hacia reparación y taller, pero no una FK compuesta que garantice que
el tenant del hijo coincida con el de la reparación. Además, algunas superficies Java agregan por
`reparacion_id`. Antes de comparar importes, detectar relaciones cruzadas **sin devolver IDs, importes
ni el tenant ajeno**:

```sql
SELECT 'REPARACION_PROPIA_CON_REPUESTO_AJENO' AS inconsistencia, COUNT(*) AS cantidad
FROM reparaciones r
JOIN repuestos rp ON rp.reparacion_id = r.id
WHERE r.taller_id = :taller_id
  AND rp.taller_id <> r.taller_id
HAVING COUNT(*) > 0

UNION ALL
SELECT 'REPUESTO_PROPIO_EN_REPARACION_AJENA', COUNT(*)
FROM repuestos rp
JOIN reparaciones r ON r.id = rp.reparacion_id
WHERE rp.taller_id = :taller_id
  AND r.taller_id <> rp.taller_id
HAVING COUNT(*) > 0

UNION ALL
SELECT 'REPARACION_PROPIA_CON_COBRO_AJENO', COUNT(*)
FROM reparaciones r
JOIN cobros c ON c.reparacion_id = r.id
WHERE r.taller_id = :taller_id
  AND c.taller_id <> r.taller_id
HAVING COUNT(*) > 0

UNION ALL
SELECT 'COBRO_PROPIO_EN_REPARACION_AJENA', COUNT(*)
FROM cobros c
JOIN reparaciones r ON r.id = c.reparacion_id
WHERE c.taller_id = :taller_id
  AND r.taller_id <> c.taller_id
HAVING COUNT(*) > 0;
```

Resultado obligatorio: **cero filas**. Cualquier fila es un incidente de aislamiento/integridad: detener
el runbook, no inspeccionar datos del tenant ajeno y escalar a seguridad + responsable de datos para
una remediación separada. La consulta de importes siguiente excluye deliberadamente hijos cruzados;
por eso un resultado “limpio” no es confiable si este preflight falló.

### 2.2 Consulta de excedentes

Ejecutar dentro de la sesión read-only y con el tenant ya verificado y el preflight limpio:

```sql
WITH repuestos_por_orden AS (
    SELECT
        r.id AS reparacion_id,
        COALESCE(
            SUM(
                COALESCE(rp.precio, 0)
                * GREATEST(1, COALESCE(rp.cantidad, 1))
            ),
            0
        )::numeric AS total_repuestos
    FROM reparaciones r
    LEFT JOIN repuestos rp
        ON rp.reparacion_id = r.id
       AND rp.taller_id = r.taller_id
    WHERE r.taller_id = :taller_id
    GROUP BY r.id
),
cobros_activos_por_orden AS (
    SELECT
        c.reparacion_id,
        SUM(c.monto)::numeric AS cobrado_activo_raw,
        COUNT(*) AS cantidad_cobros_activos,
        MIN(c.created_at) AS primer_cobro_activo,
        MAX(c.created_at) AS ultimo_cobro_activo
    FROM cobros c
    WHERE c.taller_id = :taller_id
      AND c.anulado_at IS NULL
    GROUP BY c.reparacion_id
),
estado AS (
    SELECT
        r.id AS reparacion_id,
        r.numero_orden,
        GREATEST(
            COALESCE(r.precio_final, r.precio_estimado, 0)
            + COALESCE(rp.total_repuestos, 0),
            0::numeric
        ) AS total_actual,
        ca.cobrado_activo_raw,
        GREATEST(ca.cobrado_activo_raw, 0::numeric) AS cobrado_activo,
        ca.cantidad_cobros_activos,
        ca.primer_cobro_activo,
        ca.ultimo_cobro_activo
    FROM reparaciones r
    JOIN cobros_activos_por_orden ca
      ON ca.reparacion_id = r.id
    LEFT JOIN repuestos_por_orden rp
      ON rp.reparacion_id = r.id
    WHERE r.taller_id = :taller_id
)
SELECT
    reparacion_id,
    numero_orden,
    total_actual,
    cobrado_activo_raw,
    cobrado_activo,
    cobrado_activo - total_actual AS excedente,
    cantidad_cobros_activos,
    primer_cobro_activo,
    ultimo_cobro_activo
FROM estado
WHERE cobrado_activo > total_actual
ORDER BY excedente DESC, reparacion_id;
```

Resultado esperado después del saneamiento: **cero filas**. `cobrado_activo_raw` se conserva en la
salida para detectar si importes legacy negativos están alterando la suma; V21 los audita por separado.

### 2.3 Desglose de una orden detectada

Confirmar primero que `:reparacion_id` pertenece al mismo tenant y revisar la composición del total:

```sql
SELECT
    r.id AS reparacion_id,
    r.numero_orden,
    r.precio_estimado,
    r.precio_final,
    rp.id AS repuesto_id,
    rp.nombre AS repuesto,
    rp.precio,
    rp.cantidad,
    COALESCE(rp.precio, 0) * GREATEST(1, COALESCE(rp.cantidad, 1)) AS aporte_repuesto
FROM reparaciones r
LEFT JOIN repuestos rp
    ON rp.reparacion_id = r.id
   AND rp.taller_id = r.taller_id
WHERE r.taller_id = :taller_id
  AND r.id = :reparacion_id
ORDER BY rp.id;
```

Revisar luego la historia de cobros, incluidos los anulados, sin mezclar tenants:

```sql
SELECT
    c.id AS cobro_id,
    c.monto,
    c.metodo,
    c.referencia,
    c.created_at,
    CASE WHEN c.anulado_at IS NULL THEN 'ACTIVO' ELSE 'ANULADO' END AS estado,
    c.anulado_at,
    u.username AS anulado_por,
    c.motivo_anulacion
FROM cobros c
JOIN reparaciones r
  ON r.id = c.reparacion_id
 AND r.taller_id = c.taller_id
LEFT JOIN users u
  ON u.id = c.anulado_por_id
 AND u.taller_id = c.taller_id
WHERE c.taller_id = :taller_id
  AND c.reparacion_id = :reparacion_id
ORDER BY c.created_at, c.id;
```

No copiar `observaciones` a evidencia compartida: puede contener contexto interno innecesario.

## 3. Corrección manual auditable

Resolver una orden por vez:

1. Comparar la orden, sus repuestos y referencias con la evidencia externa que el taller conserve.
   Una `referencia` en OrdenFix no prueba que la acreditación haya ocurrido.
2. Si un cobro activo es incorrecto, un ADMIN usa
   `POST /api/reparaciones/{reparacionId}/cobros/{cobroId}/anulacion` con un motivo específico y
   trazable. No usar el alias `DELETE`, porque está deprecado y registra un motivo genérico.
3. Si el cobro era real pero se registró con monto, método o referencia incorrectos, anular el
   movimiento equivocado y volver a registrarlo con `POST /api/reparaciones/{reparacionId}/cobros`
   **sólo si corresponde** y el nuevo monto no supera el saldo recalculado.
4. Si el total de la orden o un repuesto era incorrecto, corregirlo mediante la UI/API operativa. Las
   invariantes pueden exigir anular primero el cobro incorrecto; no dividir importes para eludirlas.
5. Reconsultar `GET /api/reparaciones/{id}/cobros` y verificar `excedente:0` y
   `requiereRevision:false`.
6. Repetir la consulta read-only de la sección 2 y adjuntar al ticket el resultado sin filas para esa
   orden/tenant.

Una anulación conserva el movimiento original, la fecha, el usuario y el motivo. No se debe reactivar
una fila anulada ni modificarla directamente. Si la evidencia externa es insuficiente, escalar al
responsable del taller/contabilidad en vez de asumir qué importe es correcto.

## 4. Qué significa `NOT VALID` en V21

V21 agregó 12 `CHECK ... NOT VALID`:

- PostgreSQL aplica cada check a `INSERT` y `UPDATE` nuevos desde que se creó la constraint.
- Las filas que ya existían no se escanearon ni quedaron automáticamente saneadas.
- `VALIDATE CONSTRAINT` vuelve a leer las filas existentes y, si todas cumplen, marca la constraint
  como validada. **No corrige datos**.
- Sanear sólo excedentes de cobros no alcanza: deben quedar limpios importes, cantidades y stock de
  las 12 reglas.

Estado actual de las constraints (consulta read-only):

```sql
SELECT
    conrelid::regclass AS tabla,
    conname AS constraint_name,
    convalidated
FROM pg_constraint
WHERE contype = 'c'
  AND conname::text = ANY (ARRAY[
      'ck_reparaciones_precio_estimado_no_negativo',
      'ck_reparaciones_precio_final_no_negativo',
      'ck_repuestos_precio_no_negativo',
      'ck_repuestos_cantidad_positiva',
      'ck_cobros_monto_positivo',
      'ck_presupuestos_total_no_negativo',
      'ck_presupuesto_items_precio_no_negativo',
      'ck_presupuesto_items_cantidad_positiva',
      'ck_articulos_precio_no_negativo',
      'ck_articulos_costo_no_negativo',
      'ck_articulos_stock_no_negativo',
      'ck_articulos_stock_minimo_no_negativo'
  ]::text[])
ORDER BY conrelid::regclass::text, conname;
```

## 5. Auditar las 12 reglas V21 por tenant

Ejecutar para un `:taller_id` autorizado. Cada fila identifica una violación histórica; cero filas es
el resultado requerido para ese tenant.

```sql
WITH violaciones (
    taller_id, tabla, registro_id, constraint_name, campo, valor
) AS (
    SELECT r.taller_id, 'reparaciones', r.id,
           'ck_reparaciones_precio_estimado_no_negativo',
           'precio_estimado', r.precio_estimado::text
    FROM reparaciones r
    WHERE r.taller_id = :taller_id AND r.precio_estimado < 0

    UNION ALL
    SELECT r.taller_id, 'reparaciones', r.id,
           'ck_reparaciones_precio_final_no_negativo',
           'precio_final', r.precio_final::text
    FROM reparaciones r
    WHERE r.taller_id = :taller_id AND r.precio_final < 0

    UNION ALL
    SELECT rp.taller_id, 'repuestos', rp.id,
           'ck_repuestos_precio_no_negativo',
           'precio', rp.precio::text
    FROM repuestos rp
    WHERE rp.taller_id = :taller_id AND rp.precio < 0

    UNION ALL
    SELECT rp.taller_id, 'repuestos', rp.id,
           'ck_repuestos_cantidad_positiva',
           'cantidad', rp.cantidad::text
    FROM repuestos rp
    WHERE rp.taller_id = :taller_id AND rp.cantidad <= 0

    UNION ALL
    SELECT c.taller_id, 'cobros', c.id,
           'ck_cobros_monto_positivo',
           'monto', c.monto::text
    FROM cobros c
    WHERE c.taller_id = :taller_id AND c.monto <= 0

    UNION ALL
    SELECT p.taller_id, 'presupuestos', p.id,
           'ck_presupuestos_total_no_negativo',
           'total', p.total::text
    FROM presupuestos p
    WHERE p.taller_id = :taller_id AND p.total < 0

    UNION ALL
    SELECT p.taller_id, 'presupuesto_items', pi.presupuesto_id,
           'ck_presupuesto_items_precio_no_negativo',
           'precio_unitario', pi.precio_unitario::text
    FROM presupuesto_items pi
    JOIN presupuestos p
      ON p.id = pi.presupuesto_id
    WHERE p.taller_id = :taller_id AND pi.precio_unitario < 0

    UNION ALL
    SELECT p.taller_id, 'presupuesto_items', pi.presupuesto_id,
           'ck_presupuesto_items_cantidad_positiva',
           'cantidad', pi.cantidad::text
    FROM presupuesto_items pi
    JOIN presupuestos p
      ON p.id = pi.presupuesto_id
    WHERE p.taller_id = :taller_id AND pi.cantidad <= 0

    UNION ALL
    SELECT a.taller_id, 'articulos', a.id,
           'ck_articulos_precio_no_negativo',
           'precio', a.precio::text
    FROM articulos a
    WHERE a.taller_id = :taller_id AND a.precio < 0

    UNION ALL
    SELECT a.taller_id, 'articulos', a.id,
           'ck_articulos_costo_no_negativo',
           'costo', a.costo::text
    FROM articulos a
    WHERE a.taller_id = :taller_id AND a.costo < 0

    UNION ALL
    SELECT a.taller_id, 'articulos', a.id,
           'ck_articulos_stock_no_negativo',
           'stock', a.stock::text
    FROM articulos a
    WHERE a.taller_id = :taller_id AND a.stock < 0

    UNION ALL
    SELECT a.taller_id, 'articulos', a.id,
           'ck_articulos_stock_minimo_no_negativo',
           'stock_minimo', a.stock_minimo::text
    FROM articulos a
    WHERE a.taller_id = :taller_id AND a.stock_minimo < 0
)
SELECT
    taller_id,
    tabla,
    registro_id,
    constraint_name,
    campo,
    valor
FROM violaciones
ORDER BY constraint_name, tabla, registro_id;
```

Antes de validar globalmente, repetir esta auditoría para **todos** los talleres de un inventario
completo y estable de `talleres.id`, registrar cero violaciones por tenant y volver a ejecutarla en la
ventana de release. Cero violaciones para un solo tenant no autoriza una validación global.

Cada corrección debe seguir el flujo de dominio correspondiente. Para cobros, aplicar la sección 3.
Para reparaciones, repuestos, presupuestos o inventario, definir y aprobar la corrección con el dueño
funcional antes de mutar; este runbook no prescribe valores de reemplazo.

La anulación operativa de un cobro legacy con `monto <= 0` conserva —correctamente— el monto original,
por lo que **no basta** para validar `ck_cobros_monto_positivo`. Cualquier violación que el flujo de
dominio no pueda llevar a un valor válido necesita una remediación de datos separada, revisada y
versionada, con criterio contable y evidencia por registro. No improvisar esa mutación desde una
consola ni incluirla en la migración que sólo ejecuta `VALIDATE CONSTRAINT`.

## 6. Futura migración de validación (no ejecutar durante el diagnóstico)

Sólo después de obtener cero relaciones cruzadas y cero violaciones para los 12 checks en todos los
tenants, preparar una migración Flyway **posterior y separada** usando el próximo número disponible.
Revisarla en PostgreSQL staging, medir locks/duración, tener backup probado y aprobar la ventana de
producción.

Plantilla de esa futura migración:

```sql
ALTER TABLE reparaciones
    VALIDATE CONSTRAINT ck_reparaciones_precio_estimado_no_negativo;
ALTER TABLE reparaciones
    VALIDATE CONSTRAINT ck_reparaciones_precio_final_no_negativo;

ALTER TABLE repuestos
    VALIDATE CONSTRAINT ck_repuestos_precio_no_negativo;
ALTER TABLE repuestos
    VALIDATE CONSTRAINT ck_repuestos_cantidad_positiva;

ALTER TABLE cobros
    VALIDATE CONSTRAINT ck_cobros_monto_positivo;

ALTER TABLE presupuestos
    VALIDATE CONSTRAINT ck_presupuestos_total_no_negativo;

ALTER TABLE presupuesto_items
    VALIDATE CONSTRAINT ck_presupuesto_items_precio_no_negativo;
ALTER TABLE presupuesto_items
    VALIDATE CONSTRAINT ck_presupuesto_items_cantidad_positiva;

ALTER TABLE articulos
    VALIDATE CONSTRAINT ck_articulos_precio_no_negativo;
ALTER TABLE articulos
    VALIDATE CONSTRAINT ck_articulos_costo_no_negativo;
ALTER TABLE articulos
    VALIDATE CONSTRAINT ck_articulos_stock_no_negativo;
ALTER TABLE articulos
    VALIDATE CONSTRAINT ck_articulos_stock_minimo_no_negativo;
```

`VALIDATE CONSTRAINT` verifica y marca; no cambia filas. Si una validación falla, no borrar ni recrear
la constraint para “destrabar” el deploy: cancelar la entrega, identificar el tenant/registro con la
consulta previa, sanearlo por el flujo aprobado y reintentar una nueva release.

Después de aplicar la migración en el ambiente autorizado, repetir la consulta a `pg_constraint` de la
sección 4. Debe devolver exactamente 12 filas con `convalidated=true`, además de una suite
`./mvnw clean verify` verde contra PostgreSQL real.

## Cierre del incidente o tarea de saneamiento

- Preflight de relaciones tenant: cero filas.
- Consulta de excedentes: cero filas para los tenants en alcance.
- Auditoría V21: cero filas para cada tenant relevado.
- Correcciones: IDs y motivos documentados; sin SQL de datos directo.
- Cobros anulados: conservan usuario, fecha y motivo.
- Re-registros: sólo cuando correspondían y sin superar el saldo.
- Validación global: no se programa hasta cubrir todos los tenants y las 12 reglas.
- Evidencia final: sanitizada, revisada por dos personas y vinculada al release/ticket.
