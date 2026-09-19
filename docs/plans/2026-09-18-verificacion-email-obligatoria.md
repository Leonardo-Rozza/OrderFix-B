# Verificación obligatoria para operar — etapa 1

Estado: implementación local aprobada; navegador combinado y cierre/restauración revalidados el 2026-09-19. No desplegada. Base original: `2d73e39`. Evidencia actual en [el acta de integración](2026-09-19-ui-email-integracion-local.md).

## Contrato y comportamiento

- `GET /api/perfil` expone `usuario.emailVerificado: boolean` desde el usuario persistido.
- El filtro JWT lee el estado actual y bloquea datos y operaciones privados con `403 EMAIL_NO_VERIFICADO`; no confunde la sesión pendiente con cierre de taller (`423 CUENTA_EN_CIERRE`).
- Excepciones: perfil; requisitos e historial legal propios; aceptación propia; baja propia USER con contraseña; consulta y cancelación del plan sólo para ADMIN. Los servicios retienen sus comprobaciones y flags. Las rutas públicas existentes siguen públicas.
- Exportaciones, cierre y checkout no reciben ninguna excepción nueva. La política queda activa sin flag.
- Los nuevos empleados quedan pendientes y reciben el circuito de verificación existente después del commit. Ninguna migración modifica estados previos.
- La UI debe refrescar el perfil tras confirmar cualquier enlace; no puede marcar la sesión actual como verificada por confirmar otra identidad.

## Pruebas y fixtures

Los fixtures de negocio registran y confirman por el endpoint real usando `RecordingEmailSender`; los escenarios de cuenta pendiente usan `registrarSinVerificar`. Los empleados de pruebas que deben operar confirman explícitamente. No se apaga el filtro para superar suites.

`JwtEmailVerificationAccessTest` cubre la matriz de métodos/rutas y roles, variantes de rutas, rutas públicas y la habilitación del mismo JWT después de confirmar. `EmailVerificationAccessTests` cubre registro, nuevo empleado, bloqueo de lecturas/escrituras, confirmación de otra cuenta y cancelación de plan. Las pruebas existentes de baja de empleado usan una cuenta pendiente y mantienen su contraseña y rol.

El harness `LegalRegistrationBrowserE2E` captura sólo tokens de email sintéticos en un directorio temporal JUnit y expone `ORDENFIX_REGISTRATION_E2E_MAILBOX`. Playwright consume `base64url(email) + '.txt'` y abre el enlace real. No agrega endpoints de prueba al producto. La comprobación final PostgreSQL exige los tokens consumidos y la verificación persistida de titulares y empleados; comprueba también que los datos previos permanezcan intactos. Correo externo y Mercado Pago permanecen desactivados.

## Validación

Java 21 (`corretto-21.0.10`), correo simulado y pagos desactivados:

- `./mvnw -B '-Dtest=JwtEmailVerificationAccessTest,JwtRestrictedAccessTest,EmailVerificationAccessTests,*Tests,CuentaVerificationSchedulingTest,AccountVerificationTokenIssuerTest,AccountVerificationNotifierTest' test`: **394 pruebas, cero fallos/errores/omitidas**.
- `./mvnw -B '-Dtest=LegalPublicDocumentSecurityTest,LegalPublicRequirementsSecurityTest,WorkshopClosureHttpGuardFilterTest,ExportHttpGuardFilterTest' test`: **37 pruebas, cero fallos/errores/omitidas**.
- `git diff --check`: correcto.
- El harness combinado PostgreSQL + Playwright se ejecuta desde frontend con `JAVA_HOME=<Java21> npm run test:e2e:registration-real`; resultado original pendiente de la UI coordinada; completado en el acta de integración del 2026-09-19.

La primera ejecución focalizada detectó una expectativa de paginación desactualizada y un problema al reconocer rutas públicas con servletPath vacío en MockMvc. Se corrigieron la aserción y la clasificación por URI/contexto; ambas regresiones posteriores pasan.
