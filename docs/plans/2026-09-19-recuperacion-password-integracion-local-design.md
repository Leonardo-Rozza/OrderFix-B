# Recuperación de contraseña — recorrido local real

Fecha: 2026-09-19. Baselines: backend `26282ba`, frontend `69e3a1c`.
Estado: cerrado localmente, con validación focal aprobada.

## Decisión y alcance

Alta, verificación y recuperación ya existen. El corte anterior unificó el HTML
con su alternativa de texto y acreditó plantilla, MIME y envío posterior al commit.
La recuperación tiene pruebas HTTP/H2 y navegador con API simulada, pero falta
conectar pantalla, enlace del HTML real, sesión y persistencia PostgreSQL.

Se extiende el escenario `created` del laboratorio existente en escritorio y móvil
320 px, conservando sus catorce casos y sus cuentas. Un laboratorio nuevo duplicaría
infraestructura; agregar otra función de cuenta no resuelve este hueco de validación.
No se modifica código productivo salvo defecto reproducido durante el recorrido.

## Contrato del corte

- Tras registrar y verificar la cuenta y consultar su historial, cerrar la sesión
  y pedir recuperación desde la pantalla. Un email inexistente recibe la misma
  respuesta genérica que el registrado y no genera cuenta, token ni correo.
- Pedir dos enlaces sucesivos y abrir el botón del HTML capturado en un buzón
  efímero: el primer enlace falla con 400; el segundo cambia la contraseña y vuelve
  al login. El mismo enlace consumido vuelve a fallar con 400.
- El JWT anterior deja de acceder al perfil, la contraseña vieja deja de permitir
  ingreso y la nueva inicia sesión sobre la misma cuenta verificada. El intento
  de reutilización no debe destruir la sesión nueva ni cambiar la clave otra vez.
- El reporte sólo agrega dos hashes SHA-256 de tokens sintéticos. PostgreSQL debe
  conservar un único RESET_PASSWORD usado del usuario, con el hash vigente y sin
  el reemplazado; BCrypt corresponde a la nueva clave y token_version vale uno.
  La verificación de email y la evidencia de registro permanecen intactas.
- Se conservan las comprobaciones de pertenencia, aislamiento, cinco roles físicos
  restringidos y conteos del laboratorio. El único delta nuevo esperado son dos
  filas de recuperación consumidas, una por cuenta `created`.

La captura HTML se habilita sólo en el EmailSender de pruebas y sólo con un buzón
sintético explícito. No se expone un endpoint para leer correos ni se graban tokens
reales. El reporte no contiene JWT, contraseñas ni enlaces utilizables. Todos los
proveedores permanecen apagados; no se accede a configuración privada.

## Validación prevista y límites

TypeScript estricto y lint focal de E2E; compilación Java 21 y ejecución única del
harness real con Node 24, Chromium y PostgreSQL 16 descartable. Repetir sólo por
fallo/cambio que lo justifique. No se ejecuta `clean verify` ni se amplía el gate.
La comprobación final SQL no es un contador global de DML ni un ensayo concurrente.
El reloj no se adelanta: vencimiento por tiempo conserva sus pruebas existentes.

Email C desplegado sigue pendiente: HTTPS y entrega/lectura en casillas reales.
Este corte no acredita Gmail/Outlook, ni modifica DNS, flags o credenciales.
V27–V34 y los 179 archivos frontend no versionados se preservan. Un commit atómico
por repositorio, sin push. Los servidores habituales quedarán detenidos.

## Resultado final

Aprobados **14/14 recorridos Chromium desktop/móvil y 1/1 verificación JUnit**,
sin fallos, errores ni omisiones. Única ejecución del harness de este corte:
`BUILD SUCCESS`, 1:58 min, terminado el 2026-09-19 a las 12:28:48 -03.
Compilación Java 21 aprobada antes del ensayo, en 18,506 s; frontend con ESLint y
TypeScript estricto focal aprobados. Revisión independiente del diff sin bloqueos.

Se abrió el CTA del HTML real para los dos enlaces y se confirmó recuperación,
rechazo del reemplazado y reutilizado, JWT anterior inválido, login anterior 401 y
login nuevo 200. El intento con token consumido preserva la nueva sesión, cuyo
perfil sigue respondiendo 200. Desconocido/conocido comparten JSON y aviso genérico;
el buzón final contiene exactamente dos archivos, ambos de cuentas creadas.

PostgreSQL acreditó hashes, token único consumido y plazo configurado de una hora,
BCrypt nuevo y rechazo del anterior, token_version uno, verificación usada retenida
y evidencia legal original. Se mantienen los catorce reportes y los controles de
identidad, aislamiento y operaciones previas. El total nuevo de auth_tokens es
catorce: doce verificaciones y dos recuperaciones consumidas. No se encontraron
fallos productivos ni fueron necesarios cambios de UI, API, contrato o permisos.

No se repitió el gate integral. V27–V34 y los 179 archivos frontend preexistentes
no versionados conservan su contenido; los puertos habituales y del harness
quedaron sin listeners. No hubo mensajes reales, operaciones de proveedores ni push.

Logs locales:
- `/private/tmp/ordenfix-recovery-compile-20260919.log`
- `/private/tmp/ordenfix-recovery-browser-20260919.log`
- `/private/tmp/ordenfix-recovery-e2e-eslint-20260919.log`
- `/private/tmp/ordenfix-recovery-e2e-typecheck-20260919.log`

Ejecución desde backend con Java 21 y Node 24.14.0 en PATH:

```sh
DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B \
  -Dit.test=LegalRegistrationBrowserE2E \
  -Dordenfix.browser.frontend=/Volumes/DiscoExtern/Desktop/mvgr-reparaciones-frontend \
  failsafe:integration-test failsafe:verify
```

El proceso se ejecutó sin `SPRING_CONFIG_IMPORT`; el harness mantiene la ubicación
de configuración limitada al classpath y usa datos/roles exclusivamente efímeros.
La evidencia nueva acredita la integración local de recuperación, sin sustituir
Email C desplegado ni ampliar los pendientes de lanzamiento.
