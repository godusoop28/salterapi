# salterapi · Backend de SALTER

API de **SALTER — Alternativa en Movimiento**. Monolito modular en **Java 21 + Spring Boot 3.5 + PostgreSQL**.
El frontend (Next.js) vive en su propio repositorio y consume esta API.

> Estado: base operativa funcional (guías → manifiestos → salida → recepción → entrega → reportes) con
> reglas de negocio **provisionales** donde el cliente aún no ha confirmado. Ver [`PENDIENTES_CLIENTE.md`](PENDIENTES_CLIENTE.md).

## Contenido

| Documento | Tema |
|---|---|
| [docs/API.md](docs/API.md) | Endpoints, autenticación, errores, paginación |
| [docs/ROLES_PERMISOS.md](docs/ROLES_PERMISOS.md) | Roles, permisos y alcance de datos |
| [docs/MODELO_DATOS.md](docs/MODELO_DATOS.md) | Tablas y relaciones |
| [docs/FLUJOS.md](docs/FLUJOS.md) | Guías, estados, manifiestos, salida, recepción |
| [docs/DESPLIEGUE.md](docs/DESPLIEGUE.md) | Render/Docker, variables, respaldos, seguridad en producción |
| [PENDIENTES_CLIENTE.md](PENDIENTES_CLIENTE.md) | Decisiones por confirmar y su impacto técnico |

Swagger UI (perfil dev): `http://localhost:8080/swagger-ui.html` · OpenAPI: `/api-docs`

## Tecnologías

Spring Web · Spring Security (JWT HS256 en cookie HttpOnly o Bearer, BCrypt) · Spring Data JPA/Hibernate ·
Flyway · Bean Validation · springdoc-openapi · OpenPDF (PDF) · Apache POI (Excel) · PostgreSQL 16 ·
pruebas con PostgreSQL embebido (zonky, sin Docker).

## Estructura (por dominio)

```text
src/main/java/mx/salter/api/
  auth/        inicio y cierre de sesión, /me, cambio de contraseña
  security/    JWT, filtro, permisos, alcance del usuario actual
  user/        usuarios, roles, permisos por rol y por usuario, administrador inicial
  sender/      remitentes (clientes empresariales)
  recipient/   destinatarios, búsqueda sin acentos, duplicados, importación Excel/CSV
  catalog/     turnos, conceptos de mercancía, configuración, folios consecutivos
  guide/       guías, partidas, máquina de estados, historial, alcance, PDF
  manifest/    manifiestos, cierre, reapertura, despacho (salida), PDF
  operation/   repartidores, asignaciones, recepción en destino, entrega final
  incident/    incidencias y notas
  attachment/  evidencias y archivos (validación por firma real)
  report/      indicadores y exportación CSV/XLSX/PDF
  tracking/    rastreo público (datos mínimos)
  audit/       bitácora de acciones sensibles
  common/      errores RFC 7807, paginación, texto, PDF, utilidades
src/main/resources/db/migration/   migraciones Flyway (V1 esquema, V2 datos de referencia)
```

## Desarrollo local

Requisitos: **JDK 21+** y **PostgreSQL 16** (local o con Docker). Maven no es necesario (se incluye `mvnw`).

### Opción A — Docker Compose

```bash
cp .env.example .env        # completa DATABASE_PASSWORD, SALTER_JWT_SECRET y el administrador inicial
docker compose up --build   # API en http://localhost:8080, PostgreSQL en localhost:5433
```

### Opción B — PostgreSQL instalado

```bash
# 1) Crear base y usuario (una vez)
psql -U postgres -c "create role salter login password 'TU_CONTRASEÑA';" -c "create database salter owner salter;"

# 2) Variables (PowerShell: $env:NOMBRE="valor")
export DATABASE_URL=jdbc:postgresql://localhost:5432/salter
export DATABASE_PASSWORD=TU_CONTRASEÑA
export SALTER_JWT_SECRET=$(openssl rand -base64 48)
export SALTER_BOOTSTRAP_ADMIN_EMAIL=admin@tuempresa.mx
export SALTER_BOOTSTRAP_ADMIN_PASSWORD=UnaClaveSegura2026
# Opcional (sólo dev): usuarios de ejemplo por rol *@salter.local con esta contraseña
export SALTER_DEV_PASSWORD=OtraClaveDev2026

# 3) Ejecutar
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

Las migraciones se aplican solas al arrancar. Si la base no tiene usuarios, se crea el administrador inicial.

Con `SALTER_DEV_PASSWORD` (perfil `dev`) se crean: `admin@`, `supervisor@`, `capturista@` (con permisos extra
de confirmar e importar), `repartidor@`, `recepcion@` y `remitente@salter.local`, un remitente y un repartidor
**de ejemplo**, marcados como tales.

### Pruebas

```bash
./mvnw test
```

Levantan un PostgreSQL embebido real, aplican las migraciones y recorren los flujos críticos
(ver `src/test/java`). No requieren Docker.

## Conexión con el frontend

El frontend llama a `/api/...`. Recomendado: que Next.js reenvíe `/api/*` a esta API (rewrite), así la cookie de sesión
es de primer nivel (`SameSite=Lax`). Toda solicitud que modifica datos debe enviar `X-Requested-With`.
Configura `SALTER_CORS_ORIGINS` con el dominio del frontend.
