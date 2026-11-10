# Despliegue, respaldos y seguridad

## Render (Docker) + PostgreSQL administrado

1. Crear una base **PostgreSQL** administrada (Render, Neon, Supabase, RDS…). Anotar host, puerto, base, usuario y contraseña.
2. Crear un **Web Service** desde este repositorio con entorno **Docker** (usa el `Dockerfile`).
3. Variables de entorno:

| Variable | Ejemplo |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DATABASE_URL` | `jdbc:postgresql://HOST:5432/BASE?sslmode=require` (formato **JDBC**, no `postgres://`) |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | credenciales de la base |
| `SALTER_JWT_SECRET` | 48+ caracteres aleatorios (`openssl rand -base64 48`) |
| `SALTER_CORS_ORIGINS` | `https://salterweb.vercel.app` |
| `SALTER_COOKIE_SECURE` | `true` |
| `SALTER_COOKIE_SAMESITE` | `Lax` (con proxy de Next.js) |
| `SALTER_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | primer administrador (quitar después del primer arranque) |

4. Health check path: `/actuator/health`. El puerto se toma de `PORT`.
5. Las migraciones Flyway se aplican automáticamente al iniciar.
6. En el frontend (Vercel) definir `SALTER_API_URL=https://<servicio>.onrender.com` para el proxy `/api`.

> En el perfil `prod`: Swagger deshabilitado (salvo `SALTER_SWAGGER_ENABLED=true`), logs en JSON, cookie segura y la
> aplicación **no arranca** sin `SALTER_JWT_SECRET`. No se crean datos de ejemplo.

## Respaldos

- Activar respaldos automáticos/PITR del proveedor administrado (recomendado: diario, retención 7–30 días).
- Respaldo manual:
  ```bash
  pg_dump --format=custom --no-owner -h HOST -U USUARIO -d BASE -f salter-$(date +%F).dump
  ```
- Restauración (en una base vacía):
  ```bash
  pg_restore --no-owner --clean --if-exists -h HOST -U USUARIO -d BASE salter-AAAA-MM-DD.dump
  ```
- Probar la restauración al menos una vez al mes en un entorno aparte. Guardar los respaldos cifrados y fuera del
  repositorio (`backups/` está en `.gitignore`).
- Los archivos adjuntos viven en la base (`attachments`), por lo que quedan incluidos en el respaldo.

## Requisitos de seguridad para producción

- HTTPS obligatorio (Render/Vercel lo proveen). `SALTER_COOKIE_SECURE=true`.
- `SALTER_JWT_SECRET` único por entorno; rotarlo invalida todas las sesiones.
- CORS limitado al dominio real del frontend.
- Usuario de base de datos dedicado, sin privilegios de superusuario; conexión con `sslmode=require`.
- Retirar las variables `SALTER_BOOTSTRAP_ADMIN_*` después de crear el administrador y cambiar su contraseña.
- No usar `SALTER_DEV_PASSWORD` ni el perfil `dev` en producción.
- Revisar periódicamente `GET /api/audit` (accesos fallidos, cambios de permisos, cancelaciones, correcciones).
- Las contraseñas se guardan con BCrypt (costo 12); 5 intentos fallidos bloquean 15 minutos.
- Tamaño de archivos limitado (5 MB) y validación del tipo real (JPG/PNG/WEBP/PDF).
