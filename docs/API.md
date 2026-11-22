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
| POST | /manifests/{id}/close · /reopen | MANIFESTS_MANAGE (reabrir anula la autorización) |
| POST | /manifests/{id}/guides/{guideId}/travel `{travels, reason, version}` | MANIFESTS_MANAGE (Viaja / No viaja; motivo obligatorio para «No viaja») |
| POST | /manifests/{id}/authorize `{notes, version}` | DISPATCH_AUTHORIZE (encargado) |
| POST | /manifests/{id}/dispatch | DISPATCH_REGISTER (exige autorización previa; sólo salen las guías que viajan) |

Filtros: `GET /manifests?shippingRouteId=…`, `GET /manifests/eligible-guides?shippingRouteId=…`.
`ManifestDto` incluye `shippingRoute`, `authorization`, `entries` (viaja/no viaja, quién y motivo), `review`
(guías, viajan/no viajan, pedidos, cajas, piezas, bultos por tipo, comparación con el archivo de carga, cambios de ruta,
advertencias) y `notTravelledHistory`.

### Rutas de embarque
| GET | /shipping-routes?active= | autenticado (clave y nombre, para proponer la ruta en la captura) |
| GET | /shipping-routes/overview | ROUTES_VIEW / ROUTES_MANAGE |
| POST/PUT | /shipping-routes · /shipping-routes/{id} | ROUTES_MANAGE |
| POST | /guides/{id}/route `{shippingRouteId, reason, version}` | ROUTES_REASSIGN |

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
| GET/POST | /attachments/{GUIDE\|INCIDENT\|RECIPIENT}/{id} · GET /attachments/file/{id} | alcance de la guía; RECIPIENT (foto del local, categoría FACHADA): RECIPIENTS_VIEW para ver, RECIPIENTS_MANAGE para subir |
| GET | /reports/summary?from&to&senderId&shiftId&courierId&status&destination&client&subRoute&shippingRouteId&manifestId&folio | DASHBOARD_VIEW / REPORTS_VIEW / REPORTS_OWN (incluye `byCreator` y productividad por operador) |
| GET | /reports/today?date&senderId | REPORTS_OWN / REPORTS_VIEW / SHIPMENTS_OWN / DASHBOARD_VIEW / GUIDES_VIEW_OWN (gráfica del día; un remitente sólo ve el suyo) |
| GET | /reports/guides/export?format=csv\|xlsx\|pdf (mismos filtros) | REPORTS_VIEW / REPORTS_OWN. Columnas: folio, estado, fechas, remitente, cliente, ruta de embarque, subruta, manifiesto, pedidos, piezas, turno, **nombre** de quien elaboró, operador. Sin LPN/DPN. |
| GET | /audit | AUDIT_VIEW |
| GET | /public/tracking/{folio} | público (30 consultas/min por IP) |

### Guías: cambios de V9

- `items` es el **carrito de bultos** (tipo, cantidad entera, `detail` opcional, peso). `packageCount` = suma de cantidades;
  si se envía y no coincide → `422 PACKAGE_COUNT_MISMATCH`. En borrador puede no haber bultos (`packageCount` vacío);
  para confirmar se exigen bultos y piezas > 0.
- `subRoute` (ej. CVA-01) es la **subruta**; `cdReference` es otro dato (referencia CD).
- `shipping.weightNotApplicable` / `shipping.dimensionsNotApplicable`: el documento imprime «NO APLICA».
- `shippingRouteId` + `routeChangeReason`: ruta de embarque (por defecto la del remitente).
- Al confirmar, la guía entra sola al manifiesto en preparación de su ruta (`manifest.autoGroup`, por defecto `true`).
- `POST /guides/import/commit`: genera las guías de la previsualización revisada. Una transacción por guía; resultado por
  renglón `CREATED | SKIPPED_EXISTING | SKIPPED_DUPLICATE | EXCLUDED | REJECTED`; reintentar con el mismo `fileKey` no duplica.
  Guarda el lote (`guide_import_batches`) y los LPN (`guide_packages`).
- `GET /guides/{id}/pdf?variant=CLIENTE|INTERNA&size=LETTER|LABEL_4X6`. CLIENTE (por defecto) es el formato oficial con
  consignatario (nombre, domicilio, ciudad) y nombre/firma; no incluye el teléfono de quien recibe. INTERNA agrega el teléfono.
  Los datos personales no van en el nombre de archivo, metadatos ni código.
- `GET /guides/barcode?code=…`: interpreta el PDF417 (`FOLIO|V2|ORIGEN|DESTINO|PIEZAS|PESO|FECHA|SUBRUTA|SERVICIO|COMPROMISO`,
  también el formato anterior) y localiza la guía si está en tu alcance. El código NO contiene datos personales: la información
  completa se consulta con `GET /guides/by-folio/{codigo}` (autenticado).
- Filtros nuevos de `GET /guides`: `subRoute`, `shippingRouteId`, `manifestId`, `client`.
