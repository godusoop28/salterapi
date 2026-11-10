# Roles y permisos

La autorización se valida **siempre en el backend** (anotaciones `@PreAuthorize` + reglas de alcance en los servicios).
Ocultar botones en la interfaz es sólo una ayuda visual.

Permisos efectivos de un usuario = permisos del rol **+** concedidos al usuario **−** retirados al usuario.
El administrador puede editar los permisos de cada rol (`PUT /api/roles/{id}/permissions`) y de cada usuario.

## Roles iniciales

| Permiso | ADMIN | SUPERVISOR | CAPTURISTA | REPARTIDOR | RECEPCION | REMITENTE |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| DASHBOARD_VIEW | ✔ | ✔ | | | | |
| USERS_MANAGE · ROLES_MANAGE · SETTINGS_MANAGE · AUDIT_VIEW | ✔ | | | | | |
| SENDERS_VIEW (todos los remitentes) | ✔ | ✔ | | | | |
| SENDERS_MANAGE · CATALOGS_MANAGE · COURIERS_MANAGE | ✔ | | | | | |
| RECIPIENTS_VIEW · RECIPIENTS_MANAGE | ✔ | ✔ | ✔ | | | |
| RECIPIENTS_IMPORT | ✔ | | configurable | | | |
| GUIDES_CREATE | ✔ | ✔ | ✔ | | | |
| GUIDES_VIEW_OWN | ✔ | | ✔ | | | |
| GUIDES_VIEW_ALL | ✔ | ✔ | | | | |
| GUIDES_CONFIRM | ✔ | ✔ | configurable | | | |
| GUIDES_CANCEL · GUIDES_CORRECT · GUIDES_HOLD | ✔ | ✔ | | | | |
| MANIFESTS_VIEW · MANIFESTS_MANAGE · DISPATCH_AUTHORIZE | ✔ | ✔ | | | | |
| COURIERS_VIEW · ASSIGNMENTS_MANAGE · DELIVERIES_ANY | ✔ | ✔ | | | | |
| DELIVERIES_OWN | | | | ✔ | | |
| RECEPTION_CONFIRM | ✔ | ✔ | | | ✔ | |
| INCIDENTS_CREATE | ✔ | ✔ | | ✔ | ✔ | |
| INCIDENTS_VIEW · INCIDENTS_RESOLVE | ✔ | ✔ | | | | |
| REPORTS_VIEW | ✔ | ✔ | | | | |
| SHIPMENTS_OWN · REPORTS_OWN | | | | | | ✔ |

"Recepción" es un conjunto de permisos: puede asignarse `RECEPTION_CONFIRM` a cualquier usuario sin crear uno exclusivo.

## Alcance de datos

| Usuario | Guías visibles |
|---|---|
| Con GUIDES_VIEW_ALL | Todas |
| Capturista (GUIDES_VIEW_OWN) | Las que elaboró |
| Remitente (SHIPMENTS_OWN) | Las de su remitente, excepto borradores |
| Repartidor (DELIVERIES_OWN) | Asignadas activas (lista) y las que tuvo asignadas (consulta) |
| Recepción (RECEPTION_CONFIRM) | En tránsito, recibidas, con incidencia, entregadas o retenidas |

- Usuarios **sin** `SENDERS_VIEW` quedan limitados a su remitente asignado (`users.sender_id`) para remitentes,
  destinatarios y elaboración de guías. El capturista obtiene así su remitente por defecto.
- Una guía fuera de alcance responde **404** (no revela su existencia).
- Los reportes de un remitente se fuerzan a su propio remitente aunque pida otro.
- El rastreo público no muestra nombres, teléfonos, direcciones ni usuarios.
