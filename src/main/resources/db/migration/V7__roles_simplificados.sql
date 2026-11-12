-- =====================================================================
-- SALTER · Roles simplificados: Administrador, Remitente, Receptor y Repartidor
--  - SUPERVISOR se integra en ADMIN (administrador general).
--  - CAPTURISTA se integra en REMITENTE: el remitente elabora y confirma sus
--    propias guías, administra sus destinatarios y consulta sus envíos y reportes.
--  - RECEPCION se muestra como "Receptor".
-- =====================================================================

UPDATE users SET role_id = (SELECT id FROM roles WHERE code = 'ADMIN')
WHERE role_id = (SELECT id FROM roles WHERE code = 'SUPERVISOR');

UPDATE users SET role_id = (SELECT id FROM roles WHERE code = 'REMITENTE')
WHERE role_id = (SELECT id FROM roles WHERE code = 'CAPTURISTA');

-- Usuarios de prueba de los roles eliminados: ya no tienen botón de acceso.
UPDATE users SET active = FALSE
WHERE lower(email) IN ('supervisor.demo@salter.test', 'capturista.demo@salter.test');

DELETE FROM roles WHERE code IN ('SUPERVISOR', 'CAPTURISTA');

UPDATE roles SET name = 'Administrador', description = 'Administrador general: acceso total al sistema'
WHERE code = 'ADMIN';
UPDATE roles SET name = 'Remitente', description = 'Elabora sus guías y consulta sus envíos y reportes'
WHERE code = 'REMITENTE';
UPDATE roles SET name = 'Receptor', description = 'Confirma la llegada de mercancía a destino'
WHERE code = 'RECEPCION';
UPDATE roles SET name = 'Repartidor', description = 'Consulta y actualiza sus envíos asignados'
WHERE code = 'REPARTIDOR';

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES
    ('RECIPIENTS_VIEW'), ('RECIPIENTS_MANAGE'), ('RECIPIENTS_IMPORT'),
    ('GUIDES_CREATE'), ('GUIDES_VIEW_OWN'), ('GUIDES_CONFIRM')
) AS p(permission)
WHERE r.code = 'REMITENTE'
ON CONFLICT DO NOTHING;
