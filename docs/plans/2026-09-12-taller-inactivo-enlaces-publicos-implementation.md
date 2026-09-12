# Taller inactivo: seguimiento y respuestas públicas

Fecha: 2026-09-12. Estado: corrección verificada localmente; canal operativo pendiente.
Baselines backend `061b680`, frontend `5c43da5`.

## Decisión y alcance

Continúa Confianza y cuenta de la lista de salida inicial. Al preparar el circuito
de solicitudes se detectó que `talleres.activo=false` bloqueaba el acceso privado,
pero no el seguimiento ni la respuesta pública a presupuestos. No se puede tratar
esa bandera como un cierre completo del taller.

El corte corrige ese acceso: la búsqueda pública por código debe exigir que el
taller esté activo. Lectura, aprobación y rechazo de un taller inactivo responden
404 con el mismo mensaje que un código inexistente. El rechazo ocurre antes de
buscar o modificar el presupuesto; no revela nombre del taller, equipo, estado ni
si existe un presupuesto pendiente. Los talleres activos conservan su recorrido.

Se reutiliza el repositorio de reparaciones y se aplica el predicado en SQL para
no depender de una entidad Taller ya cargada en el contexto de persistencia.
No se agrega un endpoint de cierre, un estado de cuenta, una migración ni una cola.
V27–V30 permanecen intactas. El frontend ya representa el 404 como código no
encontrado; este corte no cambia el contrato JSON ni agrega pantallas.

## Límites de la corrección

La comprobación se evalúa al buscar la reparación. No cancela una petición que ya
había superado ese paso antes de la desactivación, ni retira información que el
navegador ya recibió. Una futura operación de cierre deberá coordinar sus escrituras
con las operaciones en vuelo y comprobar sesiones, enlaces, archivos y retención.
Si se reactiva el taller, este predicado vuelve a permitir sus códigos existentes;
no es una revocación definitiva de enlaces ni una política de restauración.

La baja individual del empleado no desactiva el taller y conserva los seguimientos.
Aceptar un presupuesto no procesa pagos ni prueba acreditación bancaria. No cambia
el módulo opcional de registro de cobros externos ni los documentos informativos.

## Circuito de atención preparado

El backend incorpora `docs/runbooks/solicitudes-datos-y-cierre.md`: recepción manual,
responsable, referencia, verificación, alcance, respuesta y evidencia de ejecución.
El buzón y su registro operativo permanecen fuera de Git. No se envían mensajes,
se usan cuentas de clientes ni se ejecuta una baja real en este corte.

Se solicitó al titular del proyecto la identidad real del prestador y el buzón
atendido; no se infieren desde el nombre del equipo, Git o direcciones de ejemplo.
Un mismo buzón puede cubrir legal, privacidad y soporte. Su sintaxis válida no
acredita que exista, reciba mensajes o tenga un responsable.

El procedimiento preparado no acredita atención operativa ni cierra la fila del
lanzamiento. Quedan contactos/identidad confirmados, retenciones y plazos aplicables,
medio seguro de entrega, ejecución real de solicitudes/cierre y ensayo en staging.
No se publican los borradores legales ni se prometen los plazos propuestos de 7/30
días antes de contar con su operación correspondiente.

## Verificación y cierre

- Backend Java 21: **17 pruebas aprobadas**, cero fallos, errores u omisiones.
  `PresupuestoFlowTests` incluye dos casos nuevos para aprobar/rechazar en taller
  inactivo y continuar con otro activo. Se ejecutaron también `PresupuestoProTests`,
  `DeviceCredentialSecurityTests` y `JwtSecurityIntegrationTests`.
- `InactiveWorkshopPublicAccessIT` ejecuta **una prueba PostgreSQL 16**, incluida
  en las 17, con Flyway hasta V30 y Hibernate validate. Crea dos talleres y tres
  órdenes/presupuestos por las APIs existentes, desactiva sólo la fixture y prueba
  GET, aprobar y rechazar después del commit. Los 404 son iguales a código desconocido
  salvo timestamp/path. Filas completas y `xmin` de siete tablas de ambos talleres
  permanecen iguales ante los rechazos; el taller activo conserva la lectura y
  persiste aprobación/rechazo con el estado esperado de cada reparación.
- Maven terminó `BUILD SUCCESS` el **2026-09-12 a las 07:36:34 -03:00**, en 48,270 s.
- Frontend: **4 pruebas focalizadas aprobadas** de `SeguimientoPage.test.tsx` y
  `presupuestos.mutations.test.tsx`. No cambió código frontend; no se repitió build
  ni navegador. Estas pruebas usan API simulada y no acreditan staging.
- Revisión independiente del cambio/procedimiento y `git diff --check` aprobados.
  No se ejecutó clean verify: la corrección se limita al lookup de dos métodos,
  cubierta por regresiones HTTP y PostgreSQL, sin migraciones ni contratos nuevos.

Comando backend reproducible con Java 21:

```sh
./mvnw -B -Dtest=PresupuestoFlowTests,PresupuestoProTests,DeviceCredentialSecurityTests,JwtSecurityIntegrationTests,InactiveWorkshopPublicAccessIT test
```

La bandera se cambió únicamente en fixtures descartables. No se usaron datos de
clientes reales, no se envió correo ni se modificó un taller operativo. El ensayo
del buzón y la ejecución del circuito de atención siguen pendientes.
PostgreSQL efímero y Ryuk fueron eliminados al finalizar; se verificó la ausencia
de ambos contenedores y que V27–V30 no presentan diferencias contra HEAD.

Un commit atómico por repositorio, sin push: backend
`fix(privacidad): bloquea enlaces de talleres inactivos`; frontend documental
`docs(cuenta): registra circuito de solicitudes y limites`.

Se preservan cambios ajenos y los 77 archivos no versionados del frontend.
