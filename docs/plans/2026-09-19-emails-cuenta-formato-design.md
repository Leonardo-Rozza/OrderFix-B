# Correos de cuenta — formato OrdenFix

Fecha: 2026-09-19. Baselines backend `8f4c733`, frontend `64e35d5`.
Estado: cerrado localmente, con pruebas focalizadas y revisión visual aprobadas.

## Punto de partida y decisión

El alta de titular/empleado, la confirmación del email, su reenvío y la recuperación
ya existen. Los correos usaban párrafos HTML y un enlace desnudo, sin una plantilla
común ni alternativa de texto. El usuario pidió revisar y completar su formato.

Se mejora la presentación sobre los flujos existentes: dos plantillas compartidas
para verificación y recuperación. No se agrega un segundo correo de bienvenida ni
un motor externo de templates. El correo de alta es la confirmación del email;
para empleados sirve también como verificación de la cuenta creada por el titular.
La contraseña del empleado la define hoy el titular: nunca se incluye en el correo
ni se promete una invitación para crearla. No hay notificaciones de reparaciones,
marketing o cobros nuevos en este corte.

## Presentación y contrato

- Wordmark OrdenFix, fondo claro, tarjeta blanca, turquesa y texto oscuro tomados de
  la app actual. Botón de contraste más oscuro, asunto y preencabezado propios.
- Un encabezado, saludo, explicación breve, acción principal y plazo claro. El
  enlace completo se puede copiar si no funciona el botón. Se indica qué hacer si
  no se reconoce la solicitud y que el enlace es personal y de un solo uso.
- HTML con tablas de presentación y estilos inline, ancho máximo 600 px, legible
  a 320 px. Tipografía de sistema, sin imágenes, fuentes remotas o tracking.
- Alternativa `text/plain` junto con `text/html`, ambas UTF-8, en el mismo mensaje
  `multipart/alternative`. La interfaz conserva el envío HTML anterior para los
  consumidores y dobles existentes; SMTP implementa explícitamente ambas partes.
- Asuntos, remitente configurado y rutas no cambian. El plazo se toma del emisor:
  verificación 48 horas y recuperación 1 hora con la configuración vigente.
- Nombre y URL se escapan sólo al renderizar HTML. El texto conserva caracteres
  literales. No se registran destinatarios, cuerpos, enlaces, tokens ni errores
  privados del proveedor. El objeto de contenido oculta sus valores en toString.

Se conserva la entrega posterior al commit y la respuesta genérica de recuperación.
No se cambian permisos, tokens, revocación de sesiones, políticas o migraciones.
La revisión de las pantallas existentes no confirmó un defecto que requiera tocar
el frontend; su login revalida el perfil cuando queda una sesión anterior.

## Validación y cierre

Pruebas focales de contenido/escape, MIME serializado y releído, emisión posterior
al commit, rollback, errores SMTP y flujos HTTP de cuenta. Regresión PostgreSQL
focal de alta/verificación sin servicios externos. Renderizar los HTML del emisor
real con datos sintéticos a ancho móvil/escritorio, comprobar ausencia de recursos
remotos, desbordamientos, destino de ambas acciones y revisar las capturas.

El render en navegador no acredita Gmail, Outlook, Apple Mail ni su modo oscuro.
La entrega real y los enlaces HTTPS con la aplicación desplegada conservan Email C
pendiente. No se envían correos, se leen secretos, cambian DNS o despliegan servicios.
Un commit atómico por repositorio afectado, sin push; preservar archivos ajenos y
V27–V34 congeladas.

## Resultado y evidencia

| Comprobación | Resultado |
| --- | --- |
| Plantilla, MIME y cuenta | 77/77 casos Java aprobados; 33,547 s, final 12:06:22 -03. |
| Alta legacy con PostgreSQL 16 | 14/14 en `LegacyRegistrationPostCommitIT`. |
| Verificación y commit con PostgreSQL 16 | 17/17 en la revalidación final de `AccountVerificationPostCommitIT`; 11,923 s de Maven, final 12:11:10 -03. |
| HTML real en Chromium | 8 renders aprobados: ambas plantillas, nombres normal/extremo y 320/640 px. Cero solicitudes remotas y cero desbordamiento horizontal; dos enlaces idénticos por correo. |
| Revisión visual | Aprobada: jerarquía, CTA, plazos, nombres extensos y lectura móvil. |
| Preservación | Ocho migraciones V27–V34 y 179 archivos frontend no versionados intactos. Servidores 8080/5173/5175/5178 detenidos. |

Son 108 casos Java distintos. No se suman repeticiones: el primer focal corrigió
cuatro expectativas de acentos literales en HTML, donde el escape usa entidades;
la tanda PostgreSQL pasó 30/31 y se actualizó una expectativa que buscaba el saludo
anterior. Se repitió completa la clase afectada y aprobó sus 17 casos. Los dos
observadores de email ahora exigen exactamente el mismo token en CTA y alternativa,
conservando hash, identidad, persistencia única y observación posterior al commit.
No se cambió código productivo para adaptar esas expectativas.

Las pruebas de cuenta incluyen recuperación HTTP con H2, contraseña anterior/nueva,
reemplazo de token, rechazo de reuso y respuesta genérica. Los 31 IT PostgreSQL
acreditan alta/verificación y commit; no se presentan como recuperación navegador →
PostgreSQL ni como SMTP real. La muestra MIME serializada y releída cubre las dos
partes UTF-8 y límites de error sin abrir una conexión de correo.

Comandos (Java 21 y Docker disponibles, Maven secuencial):

```sh
./mvnw -B -Dtest=AccountEmailTemplateTest,SmtpEmailSenderTest,EmailConfigurationTest,CuentaResetSchedulingTest,CuentaTests,CuentaVerificationSchedulingTest,AccountVerificationNotifierTest,AccountVerificationTokenIssuerTest test
./mvnw -B -DskipTests test-compile
DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B -Dit.test=AccountVerificationPostCommitIT,LegacyRegistrationPostCommitIT failsafe:integration-test failsafe:verify
# Revalidación final de la clase que cambió una expectativa:
DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B -Dit.test=AccountVerificationPostCommitIT failsafe:integration-test failsafe:verify
```

Logs locales `/private/tmp/ordenfix-email-format-`: `focal-final-20260919.log`,
`postgres-20260919.log`, `postgres-final-20260919.log`. El renderer compilado generó
las vistas previas y sus textos en `/private/tmp/ordenfix-email-preview-20260919`;
se copiaron a `emails-cuenta-2026-09-19` dentro de los artefactos de esta tarea.
No hay tokens reales en ellos. La presentación se revisó sin modificar los bytes
del HTML generado. El formulario real y los proveedores no se iniciaron.

Se conserva la política de focales: no se repite clean verify por un cambio acotado
de presentación/MIME y expectativas de tests. Frontend sólo documenta el resultado;
no requiere repetir build o suites de UI. Email C y las dependencias de salida no
se cierran con esta evidencia local.
