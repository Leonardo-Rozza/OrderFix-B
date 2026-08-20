# Entorno local aislado para autenticación

## Objetivo

Permitir probar el backend en `localhost` con un usuario propio de desarrollo, sin reutilizar
credenciales, tokens ni datos de producción.

## Decisión

- Ejecutar PostgreSQL 16 en un contenedor dedicado y publicar únicamente un puerto de loopback.
- Usar una base, usuario y contraseña exclusivos de desarrollo.
- Cargar los secretos de Spring desde `application-local.properties`, archivo ignorado por Git.
- Mantener Mercado Pago y el envío de correo desactivados.
- Crear el administrador mediante el seed sobre una base vacía y comprobar el login por email.
- No conectar el backend local a la base de producción ni reutilizar contenedores antiguos.

## Flujo

1. Crear el contenedor `ordenfix-local-postgres` con almacenamiento persistente.
2. Iniciar Spring Boot importando el archivo externo local.
3. Flyway aplica las migraciones y `DataLoader` crea el taller y administrador iniciales.
4. Verificar `POST /api/auth/login` y una ruta autenticada con el JWT devuelto.

## Seguridad y recuperación

Las credenciales concretas permanecen solo en el archivo ignorado y se entregan al desarrollador.
El contenedor escucha en `127.0.0.1`, no en interfaces externas. Si el entorno se recrea, se puede
eliminar únicamente el contenedor y volumen dedicados, sin afectar otras bases locales.
