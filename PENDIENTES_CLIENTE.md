# Pendientes del cliente (SALTER)

Decisiones de negocio **no confirmadas**. El sistema funciona con valores provisionales, configurables o
claramente señalados; ninguno debe considerarse acuerdo definitivo hasta validarlo con SALTER.

> Nota: el documento de la reunión (`Salter_Reunion_Contexto_Integral_IA.pdf`) no estaba disponible en el entorno
> de desarrollo. Los requisitos se tomaron del brief técnico. Conviene revisar esta lista contra la transcripción.

## Documentos que SALTER debe proporcionar

| Material | Para qué se necesita | Estado actual |
|---|---|---|
| Ejemplo real de **guía** | Formato del PDF, campos obligatorios, tamaño, copias, QR/código de barras | Plantilla PROVISIONAL (`GuidePdfService`) |
| Ejemplo de **manifiesto** | Columnas, firmas, totales, tamaño | Plantilla PROVISIONAL (`ManifestPdfService`) |
| Formato del **CD** (documento de origen) | Saber si es sólo una referencia o un documento con estructura propia | Campo de texto `cd_reference` |
| **Excel de destinatarios** (200–300) | Ajustar sinónimos de columnas y validaciones | Importador genérico con correspondencia manual |
| Campos obligatorios por documento | Validaciones al confirmar | Configurable: `guide.requiredFields` = `rcptStreet,rcptCity` |
| Reglas definitivas de estados | Nombres y transiciones | Ver tabla de estados abajo |
| Relación **recepción en destino ↔ entrega final** | ¿Son dos eventos distintos? | Se tratan como distintos; configurable |
| Reglas de autorización | Quién confirma, quién autoriza salidas | Permisos configurables por rol y usuario |
| Evidencias obligatorias (foto, firma, GPS) | Bloquear o no la entrega | Opcionales; adjuntos disponibles |

## Decisiones provisionales y su impacto

| # | Tema | Valor provisional | Dónde se cambia | Impacto si cambia |
|---|---|---|---|---|
| 1 | Formato de folio | `SAL-000001` consecutivo, sin huecos | Configuración `folio.guide.prefix`/`padding` | Bajo (configurable). Si debe incluir año/sucursal se ajusta `FolioService`. |
| 2 | Momento del folio | Se asigna **al confirmar** (los borradores no consumen folio) | `GuideService.doConfirm` | Medio |
| 3 | Estados de guía | Borrador, Confirmada, Lista para salida, En tránsito, Recibida en destino, Entrega final confirmada, Con incidencia, Retenida, Cancelada | `GuideStatus` | Medio: migración de datos si cambian nombres |
| 4 | Recepción antes de entrega | Obligatoria (`delivery.requireReception=true`) | Configuración | Bajo: con `false` se permite En tránsito → Entregada |
| 5 | Estados de manifiesto | Borrador → Cerrado → Despachado; se puede reabrir si no salió | `Manifest.Status` | Medio |
| 6 | Cerrar ≠ salir | Cerrar deja las guías "Listas para salida"; la salida requiere autorización aparte | `ManifestService.dispatch` | — (requisito explícito) |
| 7 | Manifiesto por remitente | Opcional (puede mezclar remitentes si no se indica) | `ManifestService.replaceGuides` | Bajo |
| 8 | Capturista: confirmar guías | **No** por defecto; se concede por rol o por usuario | Usuarios → permisos | Ninguno (configurable) |
| 9 | Capturista: importar | **No** por defecto; se concede por rol o por usuario | Usuarios → permisos | Ninguno (configurable) |
| 10 | Turnos | Matutino 06–14, Vespertino 14–22, Nocturno 22–06 | Catálogo de turnos | Bajo |
| 11 | Conceptos de mercancía | Caja, Sobre, Tarima, Bulto | Catálogo | Bajo |
| 12 | Duplicados de destinatarios | Exacto = mismo nombre + (teléfono o calle+CP); probable = mismo teléfono o mismo nombre | `DuplicateDetector` | Bajo |
| 13 | Diferencias en recepción | Abren incidencia automáticamente (tipo DIFERENCIA_RECEPCION) | `OperationService.receive` | Bajo |
| 14 | Incidencias permitidas | Sólo en tránsito o recibida en destino | `IncidentService` | Bajo |
| 15 | Correcciones | Sólo guías Confirmadas (antes de cerrar manifiesto), auditadas con antes/después | `GuideService.correct` | Medio |
| 16 | Datos del rastreo público | Folio, estado, eventos genéricos, ciudad/estado destino | `TrackingController` | Bajo |
| 17 | Asignación de repartidor | Opcional al despachar; también manual en cualquier estado no terminal | `AssignmentService` | Bajo |
| 18 | Datos fiscales / QR / código de barras | **No incluidos** | — | Requiere definición |
| 19 | Almacenamiento de archivos | En PostgreSQL (bytea), máx. 5 MB | `attachments` | Medio si se migra a S3/R2 |
