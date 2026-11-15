-- =====================================================================
-- SALTER · V10: rutas de embarque del cliente AUTO TODO MEXICANA.
-- Fuente: «listado Atm 2026.xls» (columnas RUTA / SUB RUTA y tabla de subrutas) y el reporte de carga
-- «74f.xlsx» (una carga = una ruta, ej. Ruta 74 con clientes CVA-03 y CVA-04).
-- La clave de la ruta de embarque es el número de RUTA del listado: así las guías se asignan solas a la ruta
-- del destinatario (recipients.route) cuando el remitente no tiene ruta predeterminada.
-- Idempotente: no duplica claves que ya se hayan dado de alta a mano.
-- =====================================================================
INSERT INTO shipping_routes (code, name, notes)
SELECT v.code, v.name, v.notes
FROM (VALUES
    ('74', 'MORELOS',                   'Subrutas: CVA-01 Cuerna Norte, CVA-02 Cuerna Sur, CVA-03 Jojutla, CVA-04 Cuautla.'),
    ('75', 'ACAPULCO - CHILPANCINGO',   'Subrutas: GRO-01 Acapulco, GRO-02 Chilpancingo.'),
    ('76', 'IGUALA TAXCO - ALTAMIRANO', 'Subrutas: CVA-05 Iguala Taxco, CVA-06 Altamirano.'),
    ('77', 'COSTA CHICA',               'Subruta: GRO-04 Costa Chica.'),
    ('78', 'COSTA GRANDE',              'Subrutas: GRO-03 Costa Grande y GRO-04 (Ayutla).'),
    ('13', 'OAXACA CENTRO',             'Subrutas: OAX-01 Centro, OAX-02 Mixteca, OAX-03 Istmo, OAX-04 Costa, OAX-05 Local Ex.'),
    ('19', 'OAXACA COSTAS',             'Subrutas: OAX-03 Istmo, OAX-04 Costa.'),
    ('3',  'OAXACA ISTMO',              'Subruta: OAX-03 Istmo.')
) AS v(code, name, notes)
WHERE NOT EXISTS (SELECT 1 FROM shipping_routes r WHERE upper(r.code) = upper(v.code));
