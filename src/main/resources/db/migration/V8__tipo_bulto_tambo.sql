-- Tipos de bulto de la captura de guías: caja, sobre, acumulador, tambo y tarima.
INSERT INTO merchandise_concepts (name, unit)
SELECT v.name, 'PIEZA' FROM (VALUES ('Caja'), ('Sobre'), ('Acumulador'), ('Tambo'), ('Tarima')) AS v(name)
WHERE NOT EXISTS (SELECT 1 FROM merchandise_concepts c WHERE lower(c.name) = lower(v.name));
