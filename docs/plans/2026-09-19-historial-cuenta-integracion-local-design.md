# Historial de Cuenta — integración local real

Fecha: 2026-09-19. Baselines: backend `83cfcfb`, frontend `e751588`.
Estado: cerrado localmente, con validación focal aprobada.

## Decisión y alcance

El corte anterior integró UI y email, pero su laboratorio de registro dejaba
apagada la lectura legal privada. La prueba HTTP del historial usa PostgreSQL y
rol restringido, aunque simula la autenticación; el navegador de Cuenta usa API
interceptada. Falta acreditar juntas sesión real, consentimiento persistido y
lectura de la versión exacta desde Cuenta, incluso antes de verificar el email.

Se amplía el laboratorio existente, conservando sus catorce recorridos y cuentas.
Un laboratorio nuevo duplicaría infraestructura; una nueva función productiva no
resuelve este hueco de evidencia. No cambia la funcionalidad ni el contrato HTTP.

## Cambios previstos

- Activar sólo en el harness `account-read`, con un quinto rol físico independiente
  aprovisionado en la base descartable y verificado contra el esquema y los
  privilegios exactos existentes. La escritura de aceptaciones sigue apagada.
- Recorrer Cuenta en la cuenta recién creada antes y después de confirmar el email.
  El historial debe corresponder a lo aceptado durante el registro y los documentos
  deben coincidir con las versiones, hashes y textos presentados allí.
- Comprobar que leer Cuenta no habilite el trabajo del taller mientras el email siga
  pendiente. Consultar como empleado no debe mostrar las aceptaciones del titular.
- Contrastar los IDs observados en el navegador con sus filas y pertenencia en
  PostgreSQL, conservando los conteos y controles de aislamiento existentes.

El rol privado comparte la capacidad existente de materializar requisitos; no se
presenta como un rol SQL de sólo lectura. No puede insertar aceptaciones. El
historial usa el servicio existente, sin crear actos ni nuevas cuentas para probar.

## Validación y límites

Ejecutar typecheck y lint de las pruebas cambiadas, seguido del harness real con
Java 21, Chromium de escritorio y móvil de 320 px y PostgreSQL 16 descartable.
No se repiten suites completas ni se ejecuta `clean verify` sin una causa nueva.
El correo se captura en buzón sintético; Resend, Cloudinary y MP siguen apagados.
No cambian migraciones V27–V34, flags reales, secretos, UI ni datos históricos.
La publicación de textos definitivos, staging y el corte D de supresión integral
conservan sus dependencias y no se declaran completos por este ensayo.

Un commit atómico por repositorio, sin push.

## Resultado final

**14/14 recorridos Chromium desktop/móvil y 1/1 comprobación JUnit aprobados**, sin
fallos, errores u omisiones. `BUILD SUCCESS` en 1:52 min, terminado el 2026-09-19
10:44:35 -03 con Java 21 y Node 24.14.0. El historial del titular coincide antes y
después del email, conserva la sesión y muestra cada documento exacto del registro.
El empleado conserva la SPA entre identidades, muestra historial vacío y completa
la baja/revocación existente. Los IDs entregados al titular coinciden exactamente
con las filas del usuario y su taller; los conteos originales siguen cumpliéndose.

Este ensayo no es un contador global de DML ni prueba publicación/retirada de una
versión durante la sesión. Acredita el catálogo sintético existente y su lectura
con autenticación real. No habilita la aceptación posterior ni el cierre productivo.
El código productivo y las migraciones permanecen intactos; no hay cambios de UI.

Log final: `/private/tmp/ordenfix-cuenta-history-browser-approved-20260919.log`.
Acta frontend: `docs/plans/2026-09-19-historial-cuenta-integracion-local.md`.


## Comandos y resultados de preparación

- Java 21: `./mvnw -B -DskipTests test-compile` aprobado en 20,026 s.
- Frontend: `npm run typecheck`, ESLint focal y TypeScript estricto de los tres E2E
  aprobados. El chequeo estricto adicional incluye los E2E fuera del tsconfig de app.
- El harness usa el fixture HTTP existente migrado hasta V34 y comprueba cinco
  identidades PostgreSQL distintas, contextos sin padre y restricciones del rol.
- Se conservan los 179 archivos frontend no versionados y las ocho migraciones
  V27–V34 byte por byte respecto del inicio del corte.

Ejecución del laboratorio (Node 24.14.0 en PATH, Java 21 y Docker disponibles):

```sh
DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B \
  -Dit.test=LegalRegistrationBrowserE2E \
  -Dordenfix.browser.frontend=/Volumes/DiscoExtern/Desktop/mvgr-reparaciones-frontend \
  failsafe:integration-test failsafe:verify
```

El primer pase con Node 20.15 heredado por el entorno elevado aprobó, pero no es la
validación final: después se fortaleció el cambio de identidad sin recargar la SPA.
La primera ejecución de esa fuente con Node 24 aprobó 12 de 14; los dos casos de
empleados fallaron al buscar un `status` genérico durante la baja, porque también
seguía visible el aviso de login. Se precisó el selector por el mensaje de baja,
conservando la comprobación del texto completo. No se añadieron esperas, omisiones
ni cambios productivos. Se volvió a ejecutar el harness completo porque su control
PostgreSQL requiere los catorce reportes, sin sumar repeticiones como casos nuevos.


## Continuación registrada, sin ampliar este corte

La revisión estática detectó un candidato acotado en
`WorkshopClosureEffectWorker.claim`: la recuperación de leases usa una selección
`LIMIT 20` sin `MATERIALIZED` ni control del conteo afectado. El mantenimiento D ya
había necesitado acotar físicamente una selección semejante. No se reprodujo aquí
un exceso, por lo que no se declara un defecto confirmado ni se cambia el worker.
La siguiente revisión local puede sembrar más de veinte leases vencidos en PG16 y
comprobar el límite y la ausencia de envíos; sólo corregir si la prueba lo justifica.
Sin adaptadores el worker sigue saliendo sin DML. No vuelve a abrir retención,
terminalidad, recuperación real o MP como tareas autónomas de este corte.
