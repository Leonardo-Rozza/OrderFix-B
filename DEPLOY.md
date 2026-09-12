# OrdenFix — Guía de despliegue (backend)

Backend Spring Boot 4 / Java 21 + PostgreSQL. La app trae un `Dockerfile`, así que cualquier
plataforma que corra contenedores la levanta. Flyway crea/actualiza el esquema solo al arrancar.

---

## 1. Variables de entorno

Todas se configuran como variables de entorno en el panel del hosting. Las que no tienen default
son **obligatorias**. Para desarrollo local también se admite un archivo externo, fuera del
repositorio y con permisos restringidos, mediante
`SPRING_CONFIG_IMPORT=optional:file:/ruta/segura/ordenfix-secrets.properties`.

Nunca guardes secretos dentro de `src/main/resources`: el build excluye explícitamente
`application-secret.properties`; la fase `verify` y el build Docker inspeccionan el JAR y fallan si
aparece configuración secreta o local.

### Aplicación, base, autenticación y cifrado

| Variable | Obligatoria | Ejemplo / Default | Para qué |
|----------|:--:|------|----------|
| `DB_URL` | ✅ | `jdbc:postgresql://host:5432/ordenfix` | Conexión JDBC a Postgres |
| `DB_USERNAME` | ✅ | `ordenfix` | Usuario de la DB |
| `DB_PASSWORD` | ✅ | `••••••` | Password de la DB |
| `JWT_SECRET` | ✅ | mínimo 32 bytes | Firma HS256. Generar con `openssl rand -base64 48`; la app no inicia con un secreto débil |
| `JWT_ISSUER` | ✅ | `ordenfix` | Emisor esperado de los access tokens |
| `JWT_AUDIENCE` | ⬜ | `ordenfix-api` | Audiencia que acepta esta API |
| `JWT_EXPIRATION` | ✅ | `86400000` | Vida del access token en milisegundos |
| `JWT_CLOCK_SKEW_SECONDS` | ⬜ | `30` | Tolerancia para `iat`/`nbf`/`exp`; rango 0–300 s |
| `DEVICE_CREDENTIALS_ENCRYPTION_KEY` | ✅ | Base64 de exactamente 32 bytes | Clave AES-256-GCM para PIN y patrón de desbloqueo |
| `ADMIN_USER` | ✅ | `admin` | Nombre visible del admin inicial |
| `ADMIN_PASSWORD` | ✅ | `••••••` | Password del admin inicial |
| `ADMIN_EMAIL` | ✅ | `admin@tudominio.com` | Login del admin inicial |
| `APP_PUBLIC_URL` | ⬜ | `http://localhost:5173` | Base de links públicos enviados por email/WhatsApp; usar HTTPS en producción |
| `CORS_ORIGINS` | ⬜ | dominios separados por coma | Orígenes exactos permitidos para el frontend |
| `PORT` | ⬜ | `8080` | Puerto HTTP del proceso |
| `LOG_LEVEL` | ⬜ | `INFO` | Nivel de log de la aplicación |

Generá la clave de credenciales de dispositivos una sola vez y guardala en el secret manager del
hosting:

```bash
openssl rand -base64 32
```

El resultado decodifica a 32 bytes exactos. Debe conservarse estable entre reinicios, réplicas y
deploys. Si se pierde o se cambia sin un proceso de rotación, los PIN/patrones ya cifrados no se
pueden recuperar. No la guardes en Git, en la imagen ni en logs.

### Mercado Pago

| Variable | Obligatoria | Default | Para qué |
|----------|:--:|---------|----------|
| `MP_ENABLED` | ⬜ | `false` | Activa API, webhooks, cancelación, reintentos y conciliación |
| `MP_CHECKOUT_ENABLED` | ⬜ | `false` | Habilitación deliberada de nuevos checkouts; en `false` mantiene webhooks, bajas y conciliación |
| `MP_ACCESS_TOKEN` | ✅ si MP está activo | — | Access token de producción |
| `MP_WEBHOOK_SECRET` | ✅ si MP está activo | — | Firma HMAC del webhook; sin ella la app falla al iniciar |
| `MP_COLLECTOR_ID` | ✅ si MP está activo | — | ID numérico positivo de la cuenta vendedora esperada |
| `MP_APPLICATION_ID` | ✅ si MP está activo | — | ID numérico positivo de la aplicación esperada |
| `MP_API_URL` | ⬜ | `https://api.mercadopago.com` | Endpoint oficial; producción rechaza otro host/esquema |
| `MP_REASON` | ⬜ | `OrdenFix PRO - Suscripción mensual` | Descripción visible del plan |
| `MP_AMOUNT` | ⬜ | `24900` | Monto mensual |
| `MP_CURRENCY` | ⬜ | `ARS` | Moneda ISO de tres letras |
| `MP_BACK_URL` | ✅ si MP está activo | — | Retorno HTTPS al frontend |
| `MP_CONNECT_TIMEOUT` | ⬜ | `3s` | Timeout de conexión a la API |
| `MP_READ_TIMEOUT` | ⬜ | `8s` | Timeout de lectura de la API |
| `MP_WEBHOOK_TOLERANCE` | ⬜ | `5m` | Ventana anti-replay de la firma |
| `MP_WEBHOOK_PROCESSING_TIMEOUT` | ⬜ | `1m` | Tiempo para considerar abandonado un procesamiento |
| `MP_WEBHOOK_MAX_ATTEMPTS` | ⬜ | `8` | Máximo de intentos persistidos por evento |
| `MP_WEBHOOK_RETRY_DELAY` | ⬜ | `5m` | Intervalo del worker de reintentos |
| `MP_RECONCILIATION_DELAY` | ⬜ | `6h` | Intervalo entre conciliaciones con MP |
| `MP_RECONCILIATION_INITIAL_DELAY` | ⬜ | `5m` | Espera después del arranque antes de conciliar |
| `MP_RECONCILIATION_BATCH_SIZE` | ⬜ | `100` | Suscripciones por ciclo; rango 1–1000 |
| `MP_RECONCILIATION_MAX_PAYMENT_PAGES` | ⬜ | `5` | Páginas de pagos por suscripción; rango 1–20 |

### Rate limiting y proxy

| Variable | Default | Alcance |
|----------|---------|---------|
| `RATE_LIMIT_ENABLED` | `true` | Activa el limitador local por instancia |
| `TRUST_FORWARDED_HEADERS` | `false` | Usa el primer IP de `X-Forwarded-For` solo detrás de un proxy confiable |
| `RATE_LIMIT_LOGIN_REQUESTS` / `RATE_LIMIT_LOGIN_WINDOW` | `10` / `1m` | Login |
| `RATE_LIMIT_REGISTER_REQUESTS` / `RATE_LIMIT_REGISTER_WINDOW` | `5` / `1h` | Registro |
| `RATE_LIMIT_ACCOUNT_REQUESTS` / `RATE_LIMIT_ACCOUNT_WINDOW` | `5` / `15m` | Reset/verificación de cuenta |
| `RATE_LIMIT_TRACKING_READ_REQUESTS` / `RATE_LIMIT_TRACKING_READ_WINDOW` | `60` / `1m` | Lectura de seguimiento público |
| `RATE_LIMIT_TRACKING_ACTION_REQUESTS` / `RATE_LIMIT_TRACKING_ACTION_WINDOW` | `10` / `10m` | Aprobación/rechazo público |
| `RATE_LIMIT_MP_WEBHOOK_REQUESTS` / `RATE_LIMIT_MP_WEBHOOK_WINDOW` | `300` / `1m` | Webhook de Mercado Pago |

El limitador vive en memoria: con varias réplicas, complementalo con un límite compartido en el
load balancer/WAF/API gateway. Activá `TRUST_FORWARDED_HEADERS=true` únicamente si ese proxy elimina
cualquier `X-Forwarded-For` recibido del cliente y escribe uno confiable. De lo contrario dejalo en
`false`; confiar ciegamente en el header permite evadir el límite.

### Email, plan y runtime

Ver [Email transaccional](docs/operations/email.md) para arranque local, evidencia de
credenciales y dominio `orden-fix.com.ar`. La clave va en backend, nunca en `VITE_*`.
SMTP usa STARTTLS obligatorio con validación de certificado y timeouts 5/10/10 segundos.

| Variable | Obligatoria | Default | Para qué |
|----------|:--:|---------|----------|
| `MAIL_ENABLED` | ⬜ | `false` | Habilita emails reales; desactivado no registra destinatario ni contenido |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` | ⬜ | `smtp.resend.com` / `587` / `resend` | Conexión SMTP |
| `MAIL_PASSWORD` / `API_KEY_RESEND` | ✅ una si mail está activo | — | Clave SMTP; `MAIL_PASSWORD` tiene prioridad y admite referencia al segundo nombre en el archivo privado |
| `MAIL_FROM` | ⬜ | `OrdenFix <onboarding@resend.dev>` | Remitente; usar dominio verificado en producción |
| `FREE_MAX_REPARACIONES` | ⬜ | `25` | Tope mensual del plan FREE |

> **Importante:** Java necesita ~512 MB de RAM. Evitá instancias de 256 MB.

---

## 2. Base de datos (Postgres)

Cualquier Postgres 14+. Opciones gratis recomendadas:
- **Neon** (https://neon.tech) — serverless, ~0,5 GB free, se apaga sola.
- **Supabase** (https://supabase.com) — 500 MB free.

Creá la base y copiá la cadena de conexión en formato JDBC:
`jdbc:postgresql://<host>:<port>/<database>?sslmode=require` (Neon/Supabase requieren SSL).
Usuario y password van en `DB_USERNAME` / `DB_PASSWORD`.

> No hace falta crear tablas: **Flyway** aplica las migraciones V1…V20 en orden en el primer arranque.

---

## 3. Opción A — Railway (todo en un lugar, ~US$5/mes)

1. New Project → **Deploy from GitHub repo** (detecta el `Dockerfile`).
2. Add → **Database → PostgreSQL** (te da las credenciales).
3. En el servicio del backend → **Variables**: cargá las de la sección 1
   (apuntando `DB_URL/USERNAME/PASSWORD` a la Postgres de Railway).
4. Railway expone el puerto automáticamente (la app escucha en 8080).
5. Deploy. El healthcheck puede apuntar a `/actuator/health`.

## 4. Opción B — Render + Neon (free para validar)

1. Postgres en **Neon** (sección 2).
2. En Render → **New → Web Service → Build from a Dockerfile** (repo de GitHub).
3. Instance type: al menos **512 MB**. Health check path: `/actuator/health`.
4. **Environment**: cargá todas las variables (la `DB_*` apunta a Neon).
5. Deploy.
> El free de Render **se duerme** tras inactividad (arranque en frío lento en Java). Para algo
> "en serio", usá un tier pago o Railway.

## 5. Opción C — Koyeb / Fly.io + Neon

Igual que Render: contenedor desde el `Dockerfile`, Postgres en Neon, variables de la sección 1,
health check en `/actuator/health`.

---

## 6. Frontend y CORS

- En el frontend seteá `VITE_API_URL` apuntando a la URL pública del backend.
- En el backend agregá el dominio del front a `CORS_ORIGINS` (coma-separado). Sin esto, el navegador
  bloquea las llamadas.

---

## 7. MercadoPago en producción (si vas a cobrar)

1. Activá las **credenciales de producción** en el panel de MercadoPago.
2. Cargá `MP_ACCESS_TOKEN`, `MP_WEBHOOK_SECRET`, `MP_COLLECTOR_ID`, `MP_APPLICATION_ID` y una
   `MP_BACK_URL` HTTPS. El monto productivo actual es `MP_AMOUNT=24900` en `ARS`.
3. Para un alta gradual, desplegá primero con `MP_ENABLED=true` y `MP_CHECKOUT_ENABLED=false`: así
   funcionan webhooks, bajas y conciliación, pero nadie puede crear un checkout nuevo.
4. Configurá la **URL de notificaciones (webhook)** → `https://<tu-backend>/api/pagos/webhook` y
   verificá que la clave secreta corresponda exactamente a esa aplicación. Suscribí los tópicos
   `subscription_preapproval`, `subscription_authorized_payment` y `payment`.
5. Validá IDs, retorno HTTPS e idempotencia con credenciales de prueba; recién después activá
   `MP_CHECKOUT_ENABLED=true`.
6. Ante una incidencia de cobro, bajá solo `MP_CHECKOUT_ENABLED=false`. No desactives
   `MP_ENABLED` si todavía necesitás procesar webhooks, cancelaciones o conciliación.

El API y frontend pueden seguir usando `http://localhost` para desarrollo sin MP. Para probar MP
activo, tanto `MP_BACK_URL` como el endpoint de webhook deben ser URLs HTTPS públicas: usá un túnel
o un ambiente de staging; Mercado Pago no puede llamar a `localhost`.

---

## 8. Verificación post-deploy

- `GET https://<tu-backend>/actuator/health` → `{"status":"UP"}`.
- En los logs, Flyway debe decir `Successfully applied N migrations` y `Started MvgrReparacionesBackendApplication`.
- Probá `POST /api/auth/register` para crear un taller y luego `POST /api/auth/login`.
- Confirmá que las respuestas limitadas devuelvan `429` y `Retry-After`, y que el proxy entregue la
  IP real conforme a la política de `TRUST_FORWARDED_HEADERS`.
- Con MP activo, verificá en logs que el worker de reintentos y la conciliación arranquen sin errores;
  no registres access tokens, firmas ni cuerpos de webhook.

---

## 9. Build y verificación local/CI

Requiere **JDK 21** y Docker activo. `clean verify` ejecuta la suite normal, el integration test real
de migraciones sobre `postgres:16-alpine` mediante Testcontainers, `Flyway + JPA validate`, genera el
JAR y comprueba que no incluya archivos de secretos.

```bash
export JAVA_HOME=<ruta-a-un-JDK-21>
java -version               # debe informar 21
docker info                 # Testcontainers necesita un daemon accesible
./mvnw clean verify
```

La primera corrida puede descargar la imagen `postgres:16-alpine`. `PostgresMigrationIT` exige Docker,
no puede quedar omitido y comprueba explícitamente V17–V20; si el daemon no está disponible,
`clean verify` debe fallar.

Solo después de un `clean verify` exitoso generá/levantá el artefacto o la imagen:

```bash
java -jar target/*.jar      # alternativa local: ./mvnw spring-boot:run
docker build -t ordenfix-backend .
```

CI ejecuta ambos gates: verifica Maven sobre PostgreSQL y después construye la imagen del mismo SHA.
Promové esa imagen por digest entre ambientes; no la reconstruyas para producción.

El contrato de la API para el frontend está en **`FRONTEND_INTEGRATION.md`**.
