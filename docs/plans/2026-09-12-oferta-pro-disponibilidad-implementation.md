# Corte B — oferta PRO y disponibilidad de contratación

Fecha: 2026-09-12. Estado: implementado y verificado localmente.
Baselines: backend `2cbffd1`, frontend `41b0f64`. Corresponde al corte B del plan
frontend `docs/plans/2026-09-12-pulido-local-y-pendientes.md`.

## Problema y contrato

Planes tenía un precio fijo y ofrecía iniciar checkout aunque las altas estuvieran
apagadas. La suscripción autenticada agrega un bloque compatible `ofertaPro`:

| Campo | Origen y significado |
| --- | --- |
| `precioMensual` | `MercadoPagoProperties.amount`, el mismo importe usado por checkout. |
| `moneda` | `currency`, trim y mayúsculas sin modificar la configuración. |
| `contratacionDisponible` | Ambas banderas `enabled` y `checkoutEnabled` habilitadas. |

La oferta es nula si el importe falta/no es positivo o la moneda no tiene tres letras.
Esto permite consultar el plan con configuración incompleta del proveedor apagado.
No cambia las capacidades, límite, consumo ni fechas de la respuesta anterior.
No agrega consultas, escrituras, endpoints, tablas ni llamadas al proveedor.
Sólo se exponen estos tres valores; no se serializan credenciales ni URLs privadas.

La disponibilidad describe la oferta operativa global. No concede permisos ni
garantiza que un taller pueda generar otra suscripción: el POST mantiene ADMIN,
validación del estado local/remoto, reutilización de pendientes e idempotencia.
No se deriva de `esPro`, que también incluye trial. Tampoco representa el importe
pactado de una suscripción histórica. No se eligió un nuevo precio comercial ni
se modificaron propiedades o banderas operativas.

Apagar checkout no debe impedir una cancelación permitida. Se conserva el contrato:
con referencia remota y MP habilitado, primero se confirma la cancelación remota;
con referencia remota y MP apagado, falla sin baja local; sin referencia remota,
la baja local sigue permitida. `checkoutEnabled` no condiciona esos recorridos.

## Compatibilidad frontend

El tipo nuevo es opcional/nulo para convivir con un backend anterior. El frontend
valida precio, moneda y booleano; ante oferta ausente o inválida, no inventa precio
ni inicia contratación, conserva capacidades y permite reconsultar. ADMIN puede
gestionar según estado; USER sólo consulta y recibe orientación al titular.
La cancelación de PRO no depende del bloque de oferta. El retorno sintético del
checkout no concede PRO: la activación sigue dependiendo del estado confirmado.

## Validación

Java 21, una única ejecución Maven serial con H2/proveedor simulado:

```sh
./mvnw -Dtest=SuscripcionServiceTest,PlanGatingTests,MercadoPagoContractTests test
```

**51 pruebas aprobadas, cero fallos/errores/omitidas**: 16 servicio (importe/moneda,
cuatro combinaciones de flags, oferta incompleta, trial y consumo), 7 HTTP de
plan/permisos (incluidos GET ADMIN/USER y POST USER denegados), 28 contrato MP
(incluida cancelación con checkout encendido/apagado y doble gate de altas).
El cambio posterior sólo ordena imports/espaciado; diff revisado.

Frontend aprobó 48 Vitest, 7 Playwright Chromium con API/retorno locales, typecheck,
lint focalizado y revisión de capturas móvil/escritorio. El guard de publicación
ya no detecta precio PRO literal; conserva API HTTPS/contactos/manifiesto pendientes.
Esta evidencia no acredita PostgreSQL, staging ni MP real. No se ejecutó clean verify;
la comprobación conjunta/build local quedan en D conforme a la política acordada.

Se preservan las migraciones congeladas, los cambios ajenos y los secretos locales.
No se enviaron correos ni se activaron proveedores. Commit atómico previsto:
`feat(suscripcion): informa oferta y disponibilidad`, sin push ni merge.
El próximo corte local es C — Inicio y Clientes en móvil, en frontend.
