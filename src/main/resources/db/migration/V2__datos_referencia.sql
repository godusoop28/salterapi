-- =====================================================================
-- SALTER · Datos de referencia (sin usuarios ni contraseñas)
-- Los valores marcados como PROVISIONAL deben validarse con el cliente.
-- =====================================================================

INSERT INTO roles (code, name, description) VALUES
    ('ADMIN',      'Administrador',           'Acceso general al sistema'),
    ('SUPERVISOR', 'Supervisor',              'Revisión, manifiestos, salidas, asignaciones, incidencias y reportes'),
    ('CAPTURISTA', 'Capturista',              'Personal de SALTER en instalaciones del cliente: elabora guías'),
    ('REPARTIDOR', 'Repartidor',              'Consulta y actualiza sus envíos asignados'),
    ('RECEPCION',  'Responsable de recepción','Confirma la llegada de mercancía a destino'),
    ('REMITENTE',  'Remitente empresarial',   'Consulta exclusivamente sus envíos');

-- Administrador: todos los permisos
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES
    ('DASHBOARD_VIEW'), ('USERS_MANAGE'), ('ROLES_MANAGE'), ('SETTINGS_MANAGE'), ('AUDIT_VIEW'),
    ('SENDERS_VIEW'), ('SENDERS_MANAGE'), ('RECIPIENTS_VIEW'), ('RECIPIENTS_MANAGE'), ('RECIPIENTS_IMPORT'),
    ('CATALOGS_MANAGE'), ('GUIDES_CREATE'), ('GUIDES_VIEW_OWN'), ('GUIDES_VIEW_ALL'), ('GUIDES_CONFIRM'),
    ('GUIDES_CANCEL'), ('GUIDES_CORRECT'), ('GUIDES_HOLD'), ('MANIFESTS_VIEW'), ('MANIFESTS_MANAGE'),
    ('DISPATCH_AUTHORIZE'), ('COURIERS_VIEW'), ('COURIERS_MANAGE'), ('ASSIGNMENTS_MANAGE'),
    ('DELIVERIES_ANY'), ('RECEPTION_CONFIRM'), ('INCIDENTS_VIEW'), ('INCIDENTS_CREATE'),
    ('INCIDENTS_RESOLVE'), ('REPORTS_VIEW')
) AS p(permission)
WHERE r.code = 'ADMIN';

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES
    ('DASHBOARD_VIEW'), ('SENDERS_VIEW'), ('RECIPIENTS_VIEW'), ('RECIPIENTS_MANAGE'),
    ('GUIDES_CREATE'), ('GUIDES_VIEW_ALL'), ('GUIDES_CONFIRM'), ('GUIDES_CANCEL'), ('GUIDES_CORRECT'),
    ('GUIDES_HOLD'), ('MANIFESTS_VIEW'), ('MANIFESTS_MANAGE'), ('DISPATCH_AUTHORIZE'), ('COURIERS_VIEW'),
    ('ASSIGNMENTS_MANAGE'), ('DELIVERIES_ANY'), ('RECEPTION_CONFIRM'), ('INCIDENTS_VIEW'),
    ('INCIDENTS_CREATE'), ('INCIDENTS_RESOLVE'), ('REPORTS_VIEW')
) AS p(permission)
WHERE r.code = 'SUPERVISOR';

-- Capturista: confirmar guías e importar destinatarios NO vienen por defecto;
-- el administrador puede concederlos por rol o por usuario (PENDIENTE de definir con el cliente).
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES ('RECIPIENTS_VIEW'), ('RECIPIENTS_MANAGE'), ('GUIDES_CREATE'), ('GUIDES_VIEW_OWN')) AS p(permission)
WHERE r.code = 'CAPTURISTA';

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES ('DELIVERIES_OWN'), ('INCIDENTS_CREATE')) AS p(permission)
WHERE r.code = 'REPARTIDOR';

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES ('RECEPTION_CONFIRM'), ('INCIDENTS_CREATE')) AS p(permission)
WHERE r.code = 'RECEPCION';

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES ('SHIPMENTS_OWN'), ('REPORTS_OWN')) AS p(permission)
WHERE r.code = 'REMITENTE';

-- Turnos PROVISIONALES (horarios por confirmar con el cliente)
INSERT INTO shifts (code, name, start_time, end_time) VALUES
    ('MAT', 'Matutino',   '06:00', '14:00'),
    ('VES', 'Vespertino', '14:00', '22:00'),
    ('NOC', 'Nocturno',   '22:00', '06:00');

-- Conceptos de mercancía PROVISIONALES
INSERT INTO merchandise_concepts (name, unit) VALUES
    ('Caja', 'PIEZA'),
    ('Sobre', 'PIEZA'),
    ('Tarima', 'PIEZA'),
    ('Bulto', 'PIEZA');

-- Folios: formato PROVISIONAL {PREFIJO}-{NÚMERO} (regla oficial pendiente)
INSERT INTO folio_sequences (key, prefix, padding, next_value) VALUES
    ('GUIDE',    'SAL', 6, 1),
    ('MANIFEST', 'MAN', 6, 1),
    ('INCIDENT', 'INC', 5, 1);

INSERT INTO app_settings (key, value) VALUES
    ('company.name', 'SALTER'),
    ('company.slogan', 'Alternativa en Movimiento'),
    ('company.phone', ''),
    ('company.email', ''),
    ('company.address', ''),
    ('tracking.public', 'true'),
    ('pdf.guide.pageSize', 'LETTER'),
    ('pdf.guide.copies', '1'),
    ('pdf.guide.footer', 'Documento generado con plantilla PROVISIONAL. Formato oficial pendiente de validación.'),
    ('pdf.manifest.pageSize', 'LETTER'),
    ('delivery.requirePhoto', 'false'),
    ('delivery.requireSignature', 'false'),
    -- PROVISIONAL: exigir recepción en destino antes de la entrega final
    ('delivery.requireReception', 'true'),
    -- PROVISIONAL: campos obligatorios para confirmar una guía (separados por coma)
    ('guide.requiredFields', 'rcptStreet,rcptCity');
