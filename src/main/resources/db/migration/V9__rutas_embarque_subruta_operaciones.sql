-- =====================================================================
-- SALTER · V9: subruta, piezas, NO APLICA, rutas de embarque, manifiestos por ruta,
--              Viaja / No viaja, autorización de salida, lotes de importación y LPN,
--              ubicación del local de entrega, roles OPERACIONES y ENCARGADO.
-- Migración incremental: no modifica V1–V8 ni elimina datos.
-- =====================================================================

-- ─────────────── 1. Subruta histórica de la guía ───────────────
-- CVA-01 es la SUBRUTA del cliente (no la referencia CD). Hasta V8 se copiaba en cd_reference.
ALTER TABLE guides ADD COLUMN sub_route VARCHAR(20);

UPDATE guides
SET sub_route = upper(trim(cd_reference))
WHERE cd_reference IS NOT NULL AND upper(trim(cd_reference)) ~ '^[A-Z]{2,5}-[0-9]{1,4}$';

-- Si la referencia CD se autollenó con la subruta del cliente del catálogo, el valor se conserva en
-- sub_route y deja de mostrarse como referencia CD. Las demás referencias CD no se tocan.
UPDATE guides g
SET cd_reference = NULL
FROM recipients r
WHERE g.recipient_id = r.id
  AND g.sub_route IS NOT NULL
  AND upper(trim(g.cd_reference)) = g.sub_route
  AND upper(trim(r.sub_route)) = g.sub_route;

CREATE INDEX ix_guides_sub_route ON guides (sub_route);

-- ─────────────── 2. Piezas: vacías en borrador, obligatorias al confirmar ───────────────
-- La cantidad de piezas es la suma de los bultos capturados. Ya no se asume 1.
ALTER TABLE guides ALTER COLUMN package_count DROP DEFAULT;
ALTER TABLE guides ALTER COLUMN package_count DROP NOT NULL;
ALTER TABLE guides ADD CONSTRAINT ck_guides_pieces_confirmed
    CHECK (status IN ('BORRADOR', 'CANCELADA') OR package_count IS NOT NULL);

-- ─────────────── 3. Peso y dimensiones "NO APLICA" ───────────────
-- Se guarda como indicador; los campos numéricos/texto quedan vacíos (no se guarda texto en campos numéricos).
ALTER TABLE guides ADD COLUMN weight_not_applicable     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE guides ADD COLUMN dimensions_not_applicable BOOLEAN NOT NULL DEFAULT FALSE;

-- Descripción opcional de cada renglón de bultos (el tipo de bulto queda en description).
ALTER TABLE guide_items ADD COLUMN detail VARCHAR(300);

-- ─────────────── 4. Rutas de embarque ───────────────
-- Ruta de EMBARQUE (organiza manifiestos de salida). Es distinta de la ruta y subruta del destinatario
-- (recipients.route / recipients.sub_route), que vienen del listado de clientes del remitente.
CREATE TABLE shipping_routes (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                VARCHAR(20)  NOT NULL,
    name                VARCHAR(120) NOT NULL,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    responsible_user_id BIGINT REFERENCES users (id),
    notes               VARCHAR(500),
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_shipping_routes_code ON shipping_routes (upper(code));

ALTER TABLE senders ADD COLUMN default_shipping_route_id BIGINT REFERENCES shipping_routes (id);
ALTER TABLE guides  ADD COLUMN shipping_route_id BIGINT REFERENCES shipping_routes (id);
CREATE INDEX ix_guides_shipping_route ON guides (shipping_route_id, created_at DESC);

ALTER TABLE manifests ADD COLUMN shipping_route_id BIGINT REFERENCES shipping_routes (id);
-- Un solo manifiesto EN PREPARACIÓN por ruta: las guías confirmadas se agregan a él automáticamente.
CREATE UNIQUE INDEX ux_manifests_open_route ON manifests (shipping_route_id)
    WHERE status = 'BORRADOR' AND shipping_route_id IS NOT NULL;
CREATE INDEX ix_manifests_route ON manifests (shipping_route_id, operation_date DESC);

-- Autorización de salida (encargado), distinta del cierre y del registro de despacho.
ALTER TABLE manifests ADD COLUMN authorized_at       TIMESTAMPTZ;
ALTER TABLE manifests ADD COLUMN authorized_by       BIGINT REFERENCES users (id);
ALTER TABLE manifests ADD COLUMN authorization_notes VARCHAR(1000);

-- ─────────────── 5. Viaja / No viaja e historial de pertenencia ───────────────
ALTER TABLE manifest_guides ADD COLUMN travels           BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE manifest_guides ADD COLUMN travel_changed_by BIGINT REFERENCES users (id);
ALTER TABLE manifest_guides ADD COLUMN travel_changed_at TIMESTAMPTZ;
ALTER TABLE manifest_guides ADD COLUMN travel_reason     VARCHAR(500);
-- active = la guía pertenece al manifiesto. Las que "no viajaron" quedan como historial (active = false)
-- y la guía puede incorporarse al siguiente manifiesto de su ruta.
ALTER TABLE manifest_guides ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE manifest_guides DROP CONSTRAINT ux_manifest_guides_guide;
CREATE UNIQUE INDEX ux_manifest_guides_active_guide ON manifest_guides (guide_id) WHERE active;

-- ─────────────── 6. Lotes de importación y bultos (LPN) ───────────────
CREATE TABLE guide_import_batches (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sender_id         BIGINT       NOT NULL REFERENCES senders (id),
    file_key          VARCHAR(80)  NOT NULL,
    file_name         VARCHAR(200),
    load_code         VARCHAR(60),
    declared_packages INTEGER,
    created_by        BIGINT       NOT NULL REFERENCES users (id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_import_batches_file UNIQUE (sender_id, file_key)
);
ALTER TABLE guides ADD COLUMN import_batch_id BIGINT REFERENCES guide_import_batches (id);
CREATE INDEX ix_guides_import_batch ON guides (import_batch_id);

-- Relación original del reporte: cada LPN (bulto) con su tipo y su pedido.
CREATE TABLE guide_packages (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    guide_id     BIGINT      NOT NULL REFERENCES guides (id) ON DELETE CASCADE,
    lpn          VARCHAR(60),
    package_type VARCHAR(60) NOT NULL,
    order_number VARCHAR(40)
);
CREATE INDEX ix_guide_packages_guide ON guide_packages (guide_id);
CREATE INDEX ix_guide_packages_lpn ON guide_packages (lpn);

-- ─────────────── 7. Ubicación del local de entrega ───────────────
ALTER TABLE recipients ADD COLUMN latitude          NUMERIC(9, 6);
ALTER TABLE recipients ADD COLUMN longitude         NUMERIC(9, 6);
ALTER TABLE recipients ADD COLUMN location_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE recipients ADD CONSTRAINT ck_recipients_coords CHECK (
    (latitude IS NULL) = (longitude IS NULL)
    AND (latitude IS NULL OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)));

-- ─────────────── 8. Roles OPERACIONES y ENCARGADO ───────────────
INSERT INTO roles (code, name, description) VALUES
    ('OPERACIONES', 'Operaciones', 'Rutas, manifiestos, operadores, servicios de traslado y reasignaciones'),
    ('ENCARGADO',   'Encargado',   'Revisa y autoriza las salidas de los manifiestos')
ON CONFLICT (code) DO NOTHING;

-- Administrador: nuevos permisos de rutas y registro de despacho.
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r
CROSS JOIN (VALUES ('ROUTES_VIEW'), ('ROUTES_MANAGE'), ('ROUTES_REASSIGN'), ('DISPATCH_REGISTER')) AS p(permission)
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

-- Operaciones: NO corrige guías confirmadas ni autoriza salidas.
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r
CROSS JOIN (VALUES
    ('DASHBOARD_VIEW'), ('SENDERS_VIEW'), ('RECIPIENTS_VIEW'), ('GUIDES_VIEW_ALL'), ('GUIDES_HOLD'),
    ('MANIFESTS_VIEW'), ('MANIFESTS_MANAGE'), ('DISPATCH_REGISTER'), ('COURIERS_VIEW'), ('COURIERS_MANAGE'),
    ('ASSIGNMENTS_MANAGE'), ('ROUTES_VIEW'), ('ROUTES_MANAGE'), ('ROUTES_REASSIGN'), ('INCIDENTS_VIEW'),
    ('INCIDENTS_CREATE'), ('REPORTS_VIEW')
) AS p(permission)
WHERE r.code = 'OPERACIONES'
ON CONFLICT DO NOTHING;

-- Encargado: revisa y autoriza salidas.
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r
CROSS JOIN (VALUES
    ('DASHBOARD_VIEW'), ('SENDERS_VIEW'), ('GUIDES_VIEW_ALL'), ('MANIFESTS_VIEW'), ('DISPATCH_AUTHORIZE'),
    ('ROUTES_VIEW'), ('INCIDENTS_VIEW'), ('REPORTS_VIEW')
) AS p(permission)
WHERE r.code = 'ENCARGADO'
ON CONFLICT DO NOTHING;

-- Agrupación automática de guías confirmadas en el manifiesto en preparación de su ruta (configurable).
INSERT INTO app_settings (key, value) VALUES ('manifest.autoGroup', 'true') ON CONFLICT (key) DO NOTHING;
