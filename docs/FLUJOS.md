# Flujos operativos

Prioridad del cliente: **destinatarios → guías → validación → manifiestos → salidas → recepción → reportes**.

## 1. Destinatarios
- Búsqueda (`GET /recipients?q=`): cada palabra debe aparecer en nombre, apellidos, teléfono, correo o dirección;
  ignora mayúsculas y acentos; con 4+ dígitos busca también por teléfono parcial.
- Alta (`POST /recipients`): duplicado **exacto** → 409 `RECIPIENT_DUPLICATE`; **probable** → 409
  `RECIPIENT_PROBABLE_DUPLICATE` salvo `allowProbableDuplicate: true`. La respuesta incluye las coincidencias.
- Importación: `preview` (archivo) → la interfaz relaciona columnas → `validate` (errores y duplicados por fila,
  incluidos duplicados dentro del mismo archivo) → `commit` (una transacción; bloqueo por remitente para evitar
  importaciones simultáneas; los duplicados exactos nunca se importan; los probables sólo si se indica).

## 2. Guías
1. El capturista inicia sesión; su remitente asignado se usa automáticamente.
2. Elige destinatario del catálogo (`recipientId`) o captura uno nuevo (`newRecipient`):
   - `saveToCatalog: false` → sólo en esta guía (no entra al catálogo).
   - `saveToCatalog: true` → se guarda en el catálogo (con detección de duplicados).
3. Ruta (plaza origen → destino), servicio, dimensiones, conceptos de mercancía, clave CD / pedido, piezas, observaciones.
4. Guarda como **borrador** o **confirma** (requiere `GUIDES_CONFIRM`). Al confirmar se validan los campos
   obligatorios configurados y se asigna el folio consecutivo en la misma transacción.
5. Reenviar el mismo `clientRequestId` no crea otra guía.

### Formato oficial de la guía (boceto del cliente `GUIA SALTER.xls`)
| Dato en el documento | Origen en el sistema |
|---|---|
| `PBA → OAX` | `shipping.originCode` (por defecto la plaza del remitente) → `shipping.destinationCode` (obligatoria para confirmar) |
| Remitente y ciudad | Copia del remitente (`senderName`, `senderCity`, `senderState`) |
| Consignatario (nombre, calle, ciudad) | Copia del destinatario |
| Fecha | Fecha de confirmación |
| Peso | Suma del peso de los conceptos (`totalWeightKg`) |
| Dimensiones | `shipping.dimensions` |
| `8 PIEZAS` | `packageCount` |
| `CVA-01` | Clave CD (`cdReference`) |
| Código PDF417 | `FOLIO\|ORIGEN\|DESTINO\|PIEZAS\|PESO\|AAAAMMDD\|CLAVE CD` (el lector puede pegarlo en cualquier búsqueda por folio) |
| `ATM 000000001` | Folio: siglas del remitente (configurables o calculadas del nombre) + consecutivo de `guide.folioDigits` dígitos por siglas |
| Servicio ordinario / fecha compromiso | `shipping.serviceType` y `shipping.commitmentDate` (si no se captura: confirmación + días de `guide.services`) |
| Nombre / firma | Espacio para quien recibe |

Tamaño: `pdf.guide.pageSize` = `LETTER` (formato centrado en hoja carta) o `LABEL_4X6` (etiqueta térmica 4×6").

## 3. Estados de la guía (provisionales)

```text
BORRADOR ──► CONFIRMADA ──► LISTA_SALIDA ──► EN_TRANSITO ──► RECIBIDA_DESTINO ──► ENTREGADA
   │             │   ▲            │               │  │               │
   └► CANCELADA ◄┘   └─(reabrir)──┘               │  └► CON_INCIDENCIA ◄┘
                 RETENIDA ◄── (desde confirmada, lista, tránsito o recibida; al liberar vuelve al estado previo)
```

| Evento | Transición | Quién |
|---|---|---|
| Confirmar | BORRADOR → CONFIRMADA | GUIDES_CONFIRM |
| Cerrar manifiesto | CONFIRMADA → LISTA_SALIDA | MANIFESTS_MANAGE |
| Reabrir manifiesto | LISTA_SALIDA → CONFIRMADA | MANIFESTS_MANAGE |
| Autorizar salida | LISTA_SALIDA → EN_TRANSITO | DISPATCH_AUTHORIZE |
| Recepción | EN_TRANSITO → RECIBIDA_DESTINO | RECEPTION_CONFIRM |
| Entrega final | RECIBIDA_DESTINO → ENTREGADA (*) | repartidor asignado / DELIVERIES_ANY |
| Incidencia | EN_TRANSITO/RECIBIDA → CON_INCIDENCIA | INCIDENTS_CREATE |
| Resolver | CON_INCIDENCIA → estado previo / RETENIDA / CANCELADA | INCIDENTS_RESOLVE |
| Retener / liberar | → RETENIDA / → estado previo | GUIDES_HOLD |
| Cancelar | BORRADOR/CONFIRMADA/RETENIDA/CON_INCIDENCIA → CANCELADA | GUIDES_CANCEL |

(*) Con `delivery.requireReception=false` también se permite EN_TRANSITO → ENTREGADA.

Cada cambio inserta un movimiento en `guide_movements`; nunca se sobrescribe el historial. Las correcciones de guías
confirmadas (`/corrections`) registran el motivo y los valores antes/después.

## 4. Manifiestos y salida
1. `GET /manifests/eligible-guides`: guías confirmadas sin manifiesto (filtros por turno, remitente, fechas).
2. Crear borrador con las guías seleccionadas; editar mientras sea borrador.
3. **Cerrar**: valida que todas sigan confirmadas → pasan a *Lista para salida*. **No significa que salieron.**
4. **Autorizar salida** (`/dispatch`): bloquea el manifiesto y sus guías; si alguna no está lista (p. ej. retenida),
   no despacha ninguna. Registra fecha, hora, usuario, guías, vehículo y repartidor opcional (se asignan las guías).
   Un segundo despacho responde 409.
5. PDF provisional del manifiesto en cualquier estado (el borrador se marca como no válido para salida).

## 5. Recepción y entrega
- Recepción por folio (`GET /guides/by-folio/{folio}` y `POST /guides/{id}/reception`), compatible con un lector
  QR/código de barras futuro que sólo "escribe" el folio. Si hay diferencias o el conteo no coincide, se abre una
  incidencia automáticamente.
- Entrega final: repartidor asignado o supervisor; registra quién recibe. Evidencias (foto/firma) opcionales vía
  `/attachments`.

## 6. Reportes
`/reports/summary` calcula en SQL: elaboradas, confirmadas, listas para salida, despachadas, en tránsito, recibidas,
entregadas, pendientes, incidencias, actividad por turno, movimientos por día, por repartidor y por remitente.
Los eventos del periodo se cuentan desde el historial de movimientos, por lo que coinciden con lo registrado.
