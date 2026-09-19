# Integración local de UI y verificación de email

Fecha: 2026-09-19. Baseline backend `00ec1b0`, frontend `0d04e05`, más mejoras
previas de UI/email sin commit. Continuación autorizada después del ensayo de
backups. Estado: **aprobado localmente, sin despliegue ni push**.

## Resultado

Se consolida sin modificar el código previo de verificación: perfil informa el
booleano persistido; JWT bloquea operaciones de cuentas pendientes, conserva las
excepciones de Cuenta y mantiene la precedencia del cierre. Los empleados nuevos
confirman su correo mediante el flujo existente después del commit.

La revisión independiente no encontró bypass ni cambio de permisos. Se reejecutó
la regresión y el navegador con el frontend actual: vista previa antes de verificar,
registro/confirmación de titulares y empleados, reparación, presupuesto público,
entrega y registro manual de cobros externos. El harness ahora cambia un estado
desde la nueva vista Etapas, usando búsqueda, diálogo y destino explícito. Mantiene
las mismas escrituras permitidas y verifica los resultados persistidos por taller.

El frontend corrigió una carrera reproducible de Hoy/Atrás en Cobros, ajustó un
rótulo de navegación y dos selectores de pruebas. Esas correcciones no requieren
cambiar el contrato ni el código productivo backend.

## Evidencia

Java 21, PostgreSQL 16 descartable, Node 24.14.0; proveedores reales desactivados.

```sh
JAVA_HOME=/Users/leonardorozza/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home \
DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B \
  '-Dtest=JwtEmailVerificationAccessTest,JwtRestrictedAccessTest,EmailVerificationAccessTests,*Tests,CuentaVerificationSchedulingTest,AccountVerificationTokenIssuerTest,AccountVerificationNotifierTest,LegalPublicDocumentSecurityTest,LegalPublicRequirementsSecurityTest,WorkshopClosureHttpGuardFilterTest,ExportHttpGuardFilterTest' test
```

- **431 casos** aprobados; cero fallos, errores u omisiones; BUILD SUCCESS en 1:08 min.
- `npm run test:e2e:registration-real` desde frontend, con el mismo JAVA_HOME y
  DOCKER_AUTH_CONFIG: **14 recorridos desktop/móvil y 1 comprobación JUnit** aprobados;
  BUILD SUCCESS en 2:15 min, finalizado a las 10:02:34 -03. Los tokens se emiten por
  el backend y se consumen desde un buzón efímero, sin desactivar la verificación.
- `npm run test:e2e:closure-real`: **2 recorridos desktop/móvil y 1 comprobación JUnit**
  aprobados; BUILD SUCCESS en 30,183 s, finalizado a las 10:04:19 -03. Confirma que
  la integración conserva restricción/restauración y sus constancias persistidas.
- Frontend: **975 Vitest y 43 controles de publicación**; tipos, lint y build
  aprobados. Navegador simulado: matriz de 167 casos, 165 aprobados en la tanda amplia
  y dos selectores corregidos mediante repetición completa de Dashboard (6/6).
  El acta frontend del mismo nombre detalla límites, fallos y repeticiones.

Logs descartables: `/private/tmp/ordenfix-ui-email-backend-focal-20260919.log`,
`/private/tmp/ordenfix-ui-email-registration-real-final-20260919.log` y
`/private/tmp/ordenfix-ui-email-closure-real-20260919.log`. El primer pase del harness
ampliado falló por usar textbox en el selector de un input de búsqueda; el pase
final anterior incluye la corrección. No se suman repeticiones como casos distintos.

## Límites y cierre del corte

No hubo cambios nuevos de producción backend ni migraciones; se consolida la
implementación previa de email y sus pruebas, junto con esta evidencia. No se
repitió clean verify completo: se acredita sólo la regresión focal y los harness.
V27–V34 conservan sus ocho SHA-256. Ninguna cuenta real se modificó y no se enviaron
correos ni se llamó a Mercado Pago o almacenamiento remoto.

El harness de registro mantiene apagado el lector legal privado de Cuenta: las
consultas a su historial no acreditan ese módulo ni forman parte del criterio de
este recorrido. Correo real, MP, identidad/textos definitivos y configuración del
despliegue conservan su trabajo pendiente. Cierre productivo y eliminación integral
siguen sujetos a D; no se activaron sus flags reales.

Se conserva el contenido de todos los cambios previos; sólo se actualiza el estado
documental de la etapa 1. Frontend conserva archivos de diseño, capturas y actas
históricas locales. Los servidores efímeros terminaron; 8080, 5173, 5175 y 5178 están
libres. Un commit atómico por repositorio con el trabajo relacionado validado, sin
push ni merge.
