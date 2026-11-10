# Modelo de datos (PostgreSQL)

Migraciones: `src/main/resources/db/migration` (Flyway). `V1` crea el esquema, `V2` carga datos de referencia
(roles, permisos, turnos, conceptos, secuencias de folio, configuración). **No hay usuarios ni contraseñas en las migraciones.**

Convenciones: llaves `BIGINT IDENTITY`, `created_at/updated_at` (`timestamptz`, UTC), columna `version` para control
optimista de concurrencia, borrado lógico (`active`) en catálogos.

## Seguridad
- **roles** (code único) · **role_permissions** (role_id, permission)
- **users**: email único (sin distinguir mayúsculas), `password_hash` (BCrypt costo 12), rol, `sender_id` (remitente
  asignado), `courier_id` (único), `token_version` (revocación de sesiones), intentos fallidos y bloqueo.
- **user_permission_grants** / **user_permission_revokes**: ajustes por usuario.

## Catálogos
- **senders**: remitentes (empresa/particular), dirección embebida.
- **recipients**: destinatarios por remitente; `search_text` y `name_key` normalizados (sin acentos) para búsqueda y
  detección de duplicados; índices por (sender_id, phone_digits) y (sender_id, name_key).
- **shifts** (turnos con horario, soportan cruce de medianoche) · **merchandise_concepts** · **app_settings** (clave/valor).
- **folio_sequences**: prefijo, longitud y siguiente valor; se bloquea la fila (`FOR UPDATE`) para folios sin huecos.

## Guías
- **guides**: folio único (se asigna al confirmar), estado, remitente, destinatario (opcional si es de un solo uso),
  turno, creador. **Copia histórica** de remitente (`sender_*`) y destinatario (`rcpt_*`): editar catálogos no altera
  guías emitidas. `client_request_id` único por usuario (evita duplicados por doble clic). Restricción: toda guía que no
  esté en borrador/cancelada tiene folio.
- **guide_items**: conceptos de mercancía (cantidad, unidad, peso) con copia del texto.
- **guide_movements**: historial **sólo de inserción** (estado anterior/nuevo, tipo, usuario y nombre, fecha, ubicación,
  observaciones, `metadata` JSONB). Es la fuente de los reportes por periodo.

## Manifiestos y salidas
- **manifests**: número único (`MAN-000001`), estado, fecha de operación, turno, remitente opcional, cierre.
- **manifest_guides**: (manifest_id, guide_id, posición); `guide_id` **único** → una guía sólo en un manifiesto.
- **dispatches**: una por manifiesto (`manifest_id` único): quién, cuándo, repartidor, vehículo, número de guías.

## Operación
- **couriers**: repartidores.
- **courier_assignments**: índice único parcial `(guide_id) WHERE active` → una asignación activa por guía.
- **receptions**: recepción en destino, `guide_id` único (sin dobles confirmaciones), diferencias.
- **deliveries**: entrega final, `guide_id` único, nombre de quien recibe.

## Incidencias, archivos, auditoría
- **incidents**: folio `INC-00001`, tipo, severidad, estado, estado previo de la guía; índice único parcial
  `(guide_id) WHERE status <> 'RESUELTA'` → una incidencia abierta por guía. **incident_notes**.
- **attachments**: archivo en `bytea` con tipo detectado por firma, tamaño y SHA-256, ligado a guía o incidencia.
- **audit_log**: acción, entidad, usuario, IP, detalles JSONB (antes/después en cambios sensibles).

## Diagrama (simplificado)

```text
roles ─< users >─ senders ─< recipients
                 │   │
couriers ─ users │   └─< guides >─ shifts
   │             │        ├─< guide_items
   └─< courier_assignments├─< guide_movements
                          ├── receptions (1:1)
                          ├── deliveries (1:1)
                          ├─< incidents ─< incident_notes
                          └── manifest_guides >─ manifests ── dispatches (1:1)
```
