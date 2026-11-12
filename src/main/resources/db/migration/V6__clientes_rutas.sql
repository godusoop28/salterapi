-- Datos del listado de clientes del remitente (ej. "listado Atm 2026.xls"):
-- número de cliente, ruta y subruta (la subruta es la "Clave CD" de la guía, ej. CVA-01, y su prefijo la plaza destino).
ALTER TABLE recipients ADD COLUMN client_code VARCHAR(30);
ALTER TABLE recipients ADD COLUMN route       VARCHAR(20);
ALTER TABLE recipients ADD COLUMN sub_route   VARCHAR(20);
CREATE INDEX ix_recipients_sender_client ON recipients (sender_id, client_code);

-- La guía conserva el número de cliente del destinatario (consulta y búsqueda).
ALTER TABLE guides ADD COLUMN rcpt_client_code VARCHAR(30);

-- Tipos de bulto que maneja el cliente en sus reportes (caja, atado, acumulador).
INSERT INTO merchandise_concepts (name, unit)
SELECT v.name, 'PIEZA' FROM (VALUES ('Atado'), ('Acumulador')) AS v(name)
WHERE NOT EXISTS (SELECT 1 FROM merchandise_concepts c WHERE lower(c.name) = lower(v.name));
