-- =====================================================================
-- SALTER · Formato oficial de guía (boceto "GUIA SALTER.xls" enviado por el cliente)
--   PBA → OAX · remitente · consignatario · fecha · peso · dimensiones · piezas ·
--   clave CD (CVA-01) · código PDF417 · folio ATM 000000001 · servicio · fecha compromiso
-- =====================================================================

-- Remitente: plaza de origen por defecto y siglas para el folio (ATM = Auto Todo Mexicana)
ALTER TABLE senders ADD COLUMN plaza_code   VARCHAR(10);
ALTER TABLE senders ADD COLUMN guide_prefix VARCHAR(10);

-- Guía: datos que se plasman en el documento
ALTER TABLE guides ADD COLUMN origin_code      VARCHAR(10);
ALTER TABLE guides ADD COLUMN destination_code VARCHAR(10);
ALTER TABLE guides ADD COLUMN dimensions       VARCHAR(60);
ALTER TABLE guides ADD COLUMN service_type     VARCHAR(30) NOT NULL DEFAULT 'ORDINARIO';
ALTER TABLE guides ADD COLUMN commitment_date  DATE;

-- Tipos de servicio y días para la fecha compromiso (CLAVE:días, separados por coma)
INSERT INTO app_settings (key, value) VALUES ('guide.services', 'ORDINARIO:1,EXPRESS:0')
ON CONFLICT (key) DO NOTHING;
-- Folio de guía: siglas del remitente + 9 dígitos consecutivos por siglas
INSERT INTO app_settings (key, value) VALUES ('guide.folioDigits', '9')
ON CONFLICT (key) DO NOTHING;

UPDATE app_settings SET value = '' WHERE key = 'pdf.guide.footer';
-- La plaza de destino (OAX) es obligatoria para confirmar: es el dato principal del formato
UPDATE app_settings SET value = 'rcptStreet,rcptCity,destinationCode' WHERE key = 'guide.requiredFields';
