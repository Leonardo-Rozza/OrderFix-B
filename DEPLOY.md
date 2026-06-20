# OrdenFix — Guía de despliegue (backend)

Backend Spring Boot 4 / Java 21 + PostgreSQL. La app trae un `Dockerfile`, así que cualquier
plataforma que corra contenedores la levanta. Flyway crea/actualiza el esquema solo al arrancar.

---

## 1. Variables de entorno

Todas se configuran en el panel del hosting (o en `application-secret.properties` para local,
**que NO se commitea**). Las que no tienen default son **obligatorias**.

| Variable | Obligatoria | Ejemplo / Default | Para qué |
|----------|:--:|------|----------|
| `DB_URL` | ✅ | `jdbc:postgresql://host:5432/ordenfix` | Conexión a Postgres |
| `DB_USERNAME` | ✅ | `ordenfix` | Usuario de la DB |
| `DB_PASSWORD` | ✅ | `••••••` | Password de la DB |
| `JWT_SECRET` | ✅ | (string largo aleatorio) | Firma de los tokens. Generar con `openssl rand -base64 48` |
| `JWT_ISSUER` | ✅ | `ordenfix` | Emisor del token |
| `JWT_EXPIRATION` | ✅ | `86400000` | Vida del token en ms (24 h) |
| `ADMIN_USER` | ✅ | `admin` | Usuario admin sembrado al inicio |
| `ADMIN_PASSWORD` | ✅ | `••••••` | Password del admin sembrado |
| `admin.email` | ⬜ | `admin@mvgr.com` | Email del admin sembrado |
| `APP_PUBLIC_URL` | ⬜ | `https://app.tudominio.com` | Base de los links de seguimiento público |
| `CORS_ORIGINS` | ⬜ | `https://app.tudominio.com,http://localhost:5173` | Dominios del frontend permitidos (coma) |
| `LOG_LEVEL` | ⬜ | `INFO` | `DEBUG` para depurar |
| `MP_ENABLED` | ⬜ | `false` | `true` para activar MercadoPago |
| `MP_ACCESS_TOKEN` | ⬜ | `APP_USR-...` | Token de MercadoPago |
| `MP_WEBHOOK_SECRET` | ⬜ | (del panel MP) | Valida la firma del webhook |
| `MP_AMOUNT` / `MP_CURRENCY` / `MP_BACK_URL` | ⬜ | `4999` / `ARS` / URL del front | Config del checkout PRO |
| `plan.free.max-reparaciones-mes` | ⬜ | `50` | Tope mensual del plan FREE |

> **Importante:** Java necesita ~512 MB de RAM. Evitá instancias de 256 MB.

---

## 2. Base de datos (Postgres)

Cualquier Postgres 14+. Opciones gratis recomendadas:
- **Neon** (https://neon.tech) — serverless, ~0,5 GB free, se apaga sola.
- **Supabase** (https://supabase.com) — 500 MB free.

Creá la base y copiá la cadena de conexión en formato JDBC:
`jdbc:postgresql://<host>:<port>/<database>?sslmode=require` (Neon/Supabase requieren SSL).
Usuario y password van en `DB_USERNAME` / `DB_PASSWORD`.

> No hace falta crear tablas: **Flyway** las crea solas (migraciones V1…V10) en el primer arranque.

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
2. Seteá `MP_ENABLED=true`, `MP_ACCESS_TOKEN` (producción) y `MP_WEBHOOK_SECRET`.
3. Configurá la **URL de notificaciones (webhook)** → `https://<tu-backend>/api/pagos/webhook`.
4. `MP_BACK_URL` debe ser una URL `https` real del frontend.

---

## 8. Verificación post-deploy

- `GET https://<tu-backend>/actuator/health` → `{"status":"UP"}`.
- En los logs, Flyway debe decir `Successfully applied N migrations` y `Started MvgrReparacionesBackendApplication`.
- Probá `POST /api/auth/register` para crear un taller y luego `POST /api/auth/login`.

---

## 9. Build local (referencia)

Requiere **JDK 21** (`java -version` debe decir 21). Con Maven wrapper:

```bash
export JAVA_HOME=<ruta-a-un-JDK-21>
./mvnw clean package        # genera target/*.jar
java -jar target/*.jar      # o: ./mvnw spring-boot:run
```

El contrato de la API para el frontend está en **`FRONTEND_INTEGRATION.md`**.
