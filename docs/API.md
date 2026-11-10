# API de SALTER

Base: `/api`. Documentación interactiva: `/swagger-ui.html` (perfil dev). Formato JSON, fechas ISO-8601 (UTC).

## Autenticación

1. `POST /api/auth/login` `{ "email", "password" }` → `{ token, expiresAt, user }` y cookie `SALTER_SESSION` (HttpOnly).
2. Enviar en cada solicitud **una** de estas opciones:
   - Cookie `SALTER_SESSION` + cabecera `X-Requested-With: <cualquier valor>` en POST/PUT/DELETE (protección CSRF).
   - `Authorization: Bearer <token>` (herramientas, integraciones).
3. `POST /api/auth/logout` borra la cookie; `POST /api/auth/logout-all` invalida todas las sesiones del usuario.

Bloqueo: 5 intentos fallidos → 15 minutos. Cambiar contraseña, desactivar o cambiar rol invalida sesiones.
Los permisos se recalculan en cada solicitud desde la base.

## Errores (RFC 7807)

```json
{ "status": 422, "title": "Unprocessable Entity", "code": "INVALID_TRANSITION",
  "detail": "La guía SAL-000001 está en estado «Entregada» y no puede pasar a «Retenida».", "currentStatus": "ENTREGADA" }
```

| HTTP | Uso | Códigos frecuentes |
|---|---|---|
| 400 | Datos inválidos | `VALIDATION` (incluye `errors` por campo), `BAD_REQUEST` |
| 401 | Sin sesión | `UNAUTHENTICATED`, `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED` |
| 403 | Sin permiso | `FORBIDDEN`, `CSRF` |
| 404 | No existe o fuera de alcance | `NOT_FOUND` |
| 409 | Conflicto / duplicado / concurrencia | `CONCURRENT_MODIFICATION`, `ALREADY_DISPATCHED`, `ALREADY_RECEIVED`, `RECIPIENT_DUPLICATE` (incluye `matches`) |
| 422 | Regla de negocio | `INVALID_TRANSITION`, `INCOMPLETE_GUIDE` (incluye `missing`), `GUIDES_BLOCKED` |

## Paginación

Parámetros `page` (desde 0) y `size` (máx. 200). Respuesta: `{ content, page, size, totalElements, totalPages }`.

## Concurrencia e idempotencia

- Entidades editables llevan `version`; si se envía y no coincide → 409 `CONCURRENT_MODIFICATION`.
- `POST /api/guides` acepta `clientRequestId` (UUID): reenviar la misma solicitud devuelve la guía ya creada (200).
- Folios, despachos, recepciones y entregas usan bloqueo de fila y restricciones únicas.

## Endpoints

### Sesión
| Método | Ruta | Permiso |
|---|---|---|
| POST | /auth/login · /auth/logout | público |
| POST | /auth/logout-all · /auth/change-password | sesión |
| GET | /auth/me | sesión |

### Usuarios, roles, permisos
| GET/POST | /users | USERS_MANAGE |
| GET/PUT | /users/{id} | USERS_MANAGE |
| POST | /users/{id}/reset-password · /users/{id}/unlock | USERS_MANAGE |
| GET | /roles · /permissions | USERS_MANAGE o ROLES_MANAGE |
| PUT | /roles/{id}/permissions | ROLES_MANAGE |

### Catálogos y configuración
| GET | /senders · /senders/{id} | sesión (usuarios restringidos sólo ven su remitente) |
| POST/PUT | /senders · /senders/{id} | SENDERS_MANAGE |
| GET | /shifts · /concepts · /settings | sesión |
| POST/PUT | /shifts · /concepts | CATALOGS_MANAGE |
| PUT | /settings | SETTINGS_MANAGE |
| GET | /public/settings | público |

### Destinatarios
| GET | /recipients?senderId&q&page&size | RECIPIENTS_VIEW |
| GET | /recipients/{id} | RECIPIENTS_VIEW |
| POST/PUT/DELETE | /recipients · /recipients/{id} | RECIPIENTS_MANAGE |
| POST | /recipients/check-duplicates | RECIPIENTS_VIEW |
| POST | /recipients/import/preview (multipart `file`) | RECIPIENTS_IMPORT |
| POST | /recipients/import/validate · /recipients/import/commit | RECIPIENTS_IMPORT |

### Guías
| GET | /guides?q&status&senderId&shiftId&courierId&mine&from&to&withoutManifest&folio | alcance del usuario |
| GET | /guides/{id} · /guides/by-folio/{folio} · /guides/{id}/movements · /guides/{id}/pdf | alcance del usuario |
| POST | /guides | GUIDES_CREATE (con `confirm:true` requiere GUIDES_CONFIRM) |
| PUT | /guides/{id} (borrador) | GUIDES_CREATE |
| POST | /guides/{id}/confirm | GUIDES_CONFIRM |
| POST | /guides/{id}/cancel | GUIDES_CANCEL (o creador si es borrador) |
| POST | /guides/{id}/corrections | GUIDES_CORRECT |
| POST | /guides/{id}/hold · /release | GUIDES_HOLD |
| POST | /guides/{id}/notes | según participación |

Cuerpo de creación:
```json
{ "clientRequestId": "uuid", "senderId": 1, "shiftId": null,
  "recipientId": 15,
  "newRecipient": { "firstName": "Ana", "lastName": "Ruiz", "phone": "...", "address": { "street": "...", "city": "..." },
                    "saveToCatalog": false },
  "cdReference": "CD-123", "orderReference": "P-9", "packageCount": 2,
  "items": [ { "conceptId": 1, "quantity": 2 } ], "notes": "...", "confirm": true }
```
(`recipientId` **o** `newRecipient`). La respuesta incluye `allowedActions` para la interfaz.

### Manifiestos y salidas
| GET | /manifests/eligible-guides | MANIFESTS_MANAGE |
| GET | /manifests · /manifests/{id} · /manifests/{id}/pdf | MANIFESTS_VIEW / MANAGE / DISPATCH_AUTHORIZE |
| POST/PUT/DELETE | /manifests · /manifests/{id} | MANIFESTS_MANAGE |
| POST | /manifests/{id}/close · /reopen | MANIFESTS_MANAGE |
| POST | /manifests/{id}/dispatch | DISPATCH_AUTHORIZE |

### Operación
| GET | /couriers | COURIERS_VIEW / MANAGE / ASSIGNMENTS_MANAGE / DISPATCH_AUTHORIZE |
| POST/PUT | /couriers · /couriers/{id} | COURIERS_MANAGE |
| POST | /assignments · DELETE /assignments/guide/{id} | ASSIGNMENTS_MANAGE |
| GET | /courier/assignments · /courier/history | DELIVERIES_OWN |
| POST | /guides/{id}/reception | RECEPTION_CONFIRM |
| POST | /guides/{id}/delivery | DELIVERIES_OWN (asignado) o DELIVERIES_ANY |

### Incidencias, archivos, reportes, auditoría, rastreo
| GET/POST | /incidents · /incidents/{id} · /incidents/{id}/notes | INCIDENTS_* |
| POST | /incidents/{id}/status | INCIDENTS_RESOLVE |
| GET/POST | /attachments/{GUIDE|INCIDENT}/{id} · GET /attachments/file/{id} | alcance de la guía |
| GET | /reports/summary?from&to&senderId&shiftId&courierId&status&destination | DASHBOARD_VIEW / REPORTS_VIEW / REPORTS_OWN |
| GET | /reports/guides/export?format=csv\|xlsx\|pdf | REPORTS_VIEW / REPORTS_OWN |
| GET | /audit | AUDIT_VIEW |
| GET | /public/tracking/{folio} | público (30 consultas/min por IP) |
