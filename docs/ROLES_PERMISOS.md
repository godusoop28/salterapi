# Roles y permisos

La autorización se valida **siempre en el backend** (anotaciones `@PreAuthorize` + reglas de alcance en los servicios).
Ocultar botones en la interfaz es sólo una ayuda visual.

Permisos efectivos de un usuario = permisos del rol **+** concedidos al usuario **−** retirados al usuario.
El administrador puede editar los permisos de cada rol (`PUT /api/roles/{id}/permissions`) y de cada usuario.

## Roles vigentes (V7 + V9)

V7 integró SUPERVISOR en ADMIN y CAPTURISTA en REMITENTE. V9 agregó **OPERACIONES** y **ENCARGADO** sin cambiar los roles
existentes. Los permisos siguen siendo configurables por rol y por usuario (conceder / retirar).

| Permiso | ADMIN | OPERACIONES | ENCARGADO | REMITENTE | RECEPCION (Receptor) | REPARTIDOR |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| DASHBOARD_VIEW | ✔ | ✔ | ✔ | | | |
| USERS_MANAGE · ROLES_MANAGE · SETTINGS_MANAGE · AUDIT_VIEW · CATALOGS_MANAGE · SENDERS_MANAGE | ✔ | | | | | |
| SENDERS_VIEW (todos los remitentes) | ✔ | ✔ | ✔ | | | |
| RECIPIENTS_VIEW · RECIPIENTS_MANAGE · RECIPIENTS_IMPORT | ✔ | (VIEW) | | ✔ | | |
| GUIDES_CREATE · GUIDES_CONFIRM | ✔ | | | ✔ | | |
| GUIDES_VIEW_OWN | ✔ | | | ✔ | | |
| GUIDES_VIEW_ALL | ✔ | ✔ | ✔ | | | |
| GUIDES_CANCEL | ✔ | | | | | |
| **GUIDES_CORRECT** (además se exige rol ADMIN) | ✔ | | | | | |
| GUIDES_HOLD | ✔ | ✔ | | | | |
| MANIFESTS_VIEW | ✔ | ✔ | ✔ | | | |
| MANIFESTS_MANAGE (preparar, Viaja/No viaja, cerrar, reabrir) | ✔ | ✔ | | | | |
| **DISPATCH_AUTHORIZE** (autorizar salida) | ✔ | | ✔ | | | |
| **DISPATCH_REGISTER** (registrar despacho autorizado) | ✔ | ✔ | | | | |
| **ROUTES_VIEW** · **ROUTES_MANAGE** · **ROUTES_REASSIGN** | ✔ | ✔ | (VIEW) | | | |
| COURIERS_VIEW · COURIERS_MANAGE · ASSIGNMENTS_MANAGE | ✔ | ✔ | | | | |
| DELIVERIES_ANY | ✔ | | | | | |
| DELIVERIES_OWN | | | | | | ✔ |
| RECEPTION_CONFIRM | ✔ | | | | ✔ | |
| INCIDENTS_CREATE | ✔ | ✔ | | | ✔ | ✔ |
| INCIDENTS_VIEW | ✔ | ✔ | ✔ | | | |
| INCIDENTS_RESOLVE | ✔ | | | | | |
| REPORTS_VIEW | ✔ | ✔ | ✔ | | | |
| SHIPMENTS_OWN · REPORTS_OWN | | | | ✔ | | |

### Reglas que se validan en el backend (no sólo en botones)

- **Corrección de guías confirmadas:** sólo rol ADMIN con `GUIDES_CORRECT` (`GuideService.requireAdminCorrection`). Un permiso
  concedido a otro rol no basta. Se audita antes/después, usuario, fecha y motivo (historial `CORRECCION` + `audit_log`).
- **Salida:** preparar/cerrar (`MANIFESTS_MANAGE`) ≠ autorizar (`DISPATCH_AUTHORIZE`) ≠ registrar despacho (`DISPATCH_REGISTER`).
  Sin autorización el despacho responde `422 NOT_AUTHORIZED`; un segundo despacho, `409 ALREADY_DISPATCHED`.
  Reabrir un manifiesto anula su autorización.
- **Cambio de ruta de embarque:** requiere `ROUTES_REASSIGN` y motivo; al capturar, elegir una ruta distinta de la predeterminada
  del remitente cuenta como cambio. No se permite si el manifiesto de la guía ya está cerrado o despachado.
- **Versión interna del PDF** (agrega el teléfono de quien recibe): sólo `GUIDES_VIEW_ALL`, `DELIVERIES_ANY`, `RECEPTION_CONFIRM` o el
  repartidor asignado. El resto obtiene la versión para cliente.

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
