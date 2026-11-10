-- =====================================================================
-- SALTER · Esquema inicial
-- Monolito modular: seguridad, catálogos, guías, manifiestos, operación,
-- incidencias, archivos y auditoría.
-- =====================================================================

-- ─────────────── Seguridad ───────────────
CREATE TABLE roles (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(80)  NOT NULL,
    description VARCHAR(300),
    system_role BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE role_permissions (
    role_id    BIGINT      NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission VARCHAR(60) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

-- ─────────────── Catálogos ───────────────
CREATE TABLE senders (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          VARCHAR(30)  NOT NULL UNIQUE,
    name          VARCHAR(160) NOT NULL,
    kind          VARCHAR(20)  NOT NULL CHECK (kind IN ('EMPRESA', 'PARTICULAR')),
    contact_name  VARCHAR(160),
    phone         VARCHAR(30),
    email         VARCHAR(160),
    street        VARCHAR(200),
    neighborhood  VARCHAR(120),
    city          VARCHAR(120),
    state         VARCHAR(120),
    zip           VARCHAR(10),
    address_refs  VARCHAR(300),
    notes         VARCHAR(500),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_senders_name ON senders (lower(name));

CREATE TABLE couriers (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(160) NOT NULL,
    phone      VARCHAR(30),
    email      VARCHAR(160),
    zone       VARCHAR(120),
    vehicle    VARCHAR(80),
    plate      VARCHAR(20),
    status     VARCHAR(20)  NOT NULL DEFAULT 'DISPONIBLE'
               CHECK (status IN ('DISPONIBLE', 'EN_RUTA', 'DESCANSO', 'INACTIVO')),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email           VARCHAR(160) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(160) NOT NULL,
    phone           VARCHAR(30),
    role_id         BIGINT       NOT NULL REFERENCES roles (id),
    sender_id       BIGINT REFERENCES senders (id),
    courier_id      BIGINT UNIQUE REFERENCES couriers (id),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    token_version   INTEGER      NOT NULL DEFAULT 0,
    failed_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    last_login_at   TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE INDEX ix_users_role ON users (role_id);

-- Ajustes de permisos por usuario (sobre los del rol): conceder o retirar.
CREATE TABLE user_permission_grants (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    permission VARCHAR(60) NOT NULL,
    PRIMARY KEY (user_id, permission)
);
CREATE TABLE user_permission_revokes (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    permission VARCHAR(60) NOT NULL,
    PRIMARY KEY (user_id, permission)
);

CREATE TABLE recipients (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sender_id     BIGINT       NOT NULL REFERENCES senders (id),
    first_name    VARCHAR(120) NOT NULL,
    last_name     VARCHAR(160),
    phone         VARCHAR(30),
    phone_digits  VARCHAR(20),
    email         VARCHAR(160),
    street        VARCHAR(200),
    neighborhood  VARCHAR(120),
    city          VARCHAR(120),
    state         VARCHAR(120),
    zip           VARCHAR(10),
    address_refs  VARCHAR(300),
    notes         VARCHAR(500),
    -- Texto normalizado (minúsculas, sin acentos) para búsquedas y duplicados.
    search_text   VARCHAR(600) NOT NULL,
    name_key      VARCHAR(300) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    BIGINT REFERENCES users (id),
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_recipients_sender_active ON recipients (sender_id, active);
CREATE INDEX ix_recipients_sender_phone ON recipients (sender_id, phone_digits);
CREATE INDEX ix_recipients_sender_name ON recipients (sender_id, name_key);

CREATE TABLE shifts (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    name       VARCHAR(80)  NOT NULL,
    start_time TIME         NOT NULL,
    end_time   TIME         NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE merchandise_concepts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    unit        VARCHAR(30)  NOT NULL DEFAULT 'PIEZA',
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_concepts_name ON merchandise_concepts (lower(name));

CREATE TABLE app_settings (
    key        VARCHAR(80)  PRIMARY KEY,
    value      VARCHAR(2000),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Secuencias de folios sin huecos (bloqueo pesimista por fila).
CREATE TABLE folio_sequences (
    key        VARCHAR(40) PRIMARY KEY,
    prefix     VARCHAR(20) NOT NULL,
    padding    INTEGER     NOT NULL DEFAULT 6,
    next_value BIGINT      NOT NULL DEFAULT 1
);

-- ─────────────── Guías ───────────────
CREATE TABLE guides (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    folio               VARCHAR(40) UNIQUE,
    status              VARCHAR(30)  NOT NULL,
    status_before_hold  VARCHAR(30),
    client_request_id   UUID,
    sender_id           BIGINT       NOT NULL REFERENCES senders (id),
    recipient_id        BIGINT REFERENCES recipients (id),
    shift_id            BIGINT REFERENCES shifts (id),
    created_by          BIGINT       NOT NULL REFERENCES users (id),
    -- Copia histórica del remitente
    sender_name         VARCHAR(160) NOT NULL,
    sender_contact      VARCHAR(160),
    sender_phone        VARCHAR(30),
    sender_street       VARCHAR(200),
    sender_neighborhood VARCHAR(120),
    sender_city         VARCHAR(120),
    sender_state        VARCHAR(120),
    sender_zip          VARCHAR(10),
    -- Copia histórica del destinatario
    rcpt_name           VARCHAR(300) NOT NULL,
    rcpt_phone          VARCHAR(30),
    rcpt_email          VARCHAR(160),
    rcpt_street         VARCHAR(200),
    rcpt_neighborhood   VARCHAR(120),
    rcpt_city           VARCHAR(120),
    rcpt_state          VARCHAR(120),
    rcpt_zip            VARCHAR(10),
    rcpt_refs           VARCHAR(300),
    rcpt_one_time       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Documentos y referencias
    cd_reference        VARCHAR(80),
    order_reference     VARCHAR(80),
    package_count       INTEGER      NOT NULL DEFAULT 1 CHECK (package_count > 0),
    total_weight_kg     NUMERIC(10, 2),
    notes               VARCHAR(1000),
    search_text         VARCHAR(1200) NOT NULL DEFAULT '',
    confirmed_at        TIMESTAMPTZ,
    confirmed_by        BIGINT REFERENCES users (id),
    cancelled_at        TIMESTAMPTZ,
    cancelled_by        BIGINT REFERENCES users (id),
    cancel_reason       VARCHAR(500),
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_guides_status CHECK (status IN ('BORRADOR', 'CONFIRMADA', 'LISTA_SALIDA', 'EN_TRANSITO',
        'RECIBIDA_DESTINO', 'ENTREGADA', 'CON_INCIDENCIA', 'RETENIDA', 'CANCELADA')),
    CONSTRAINT ck_guides_folio_confirmed CHECK (status IN ('BORRADOR', 'CANCELADA') OR folio IS NOT NULL)
);
CREATE UNIQUE INDEX ux_guides_request ON guides (created_by, client_request_id) WHERE client_request_id IS NOT NULL;
CREATE INDEX ix_guides_status ON guides (status);
CREATE INDEX ix_guides_sender_created ON guides (sender_id, created_at DESC);
CREATE INDEX ix_guides_created_by ON guides (created_by, created_at DESC);
CREATE INDEX ix_guides_shift_created ON guides (shift_id, created_at);
CREATE INDEX ix_guides_created_at ON guides (created_at DESC);

CREATE TABLE guide_items (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    guide_id    BIGINT        NOT NULL REFERENCES guides (id) ON DELETE CASCADE,
    position    INTEGER       NOT NULL,
    concept_id  BIGINT REFERENCES merchandise_concepts (id),
    description VARCHAR(300)  NOT NULL,
    quantity    NUMERIC(10, 2) NOT NULL CHECK (quantity > 0),
    unit        VARCHAR(30)   NOT NULL,
    weight_kg   NUMERIC(10, 2)
);
CREATE INDEX ix_guide_items_guide ON guide_items (guide_id);

-- Historial de movimientos: sólo inserciones.
CREATE TABLE guide_movements (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    guide_id    BIGINT       NOT NULL REFERENCES guides (id),
    type        VARCHAR(30)  NOT NULL,
    from_status VARCHAR(30),
    to_status   VARCHAR(30)  NOT NULL,
    user_id     BIGINT REFERENCES users (id),
    user_name   VARCHAR(160),
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    location    VARCHAR(160),
    notes       VARCHAR(1000),
    metadata    JSONB
);
CREATE INDEX ix_movements_guide ON guide_movements (guide_id, occurred_at);
CREATE INDEX ix_movements_occurred ON guide_movements (occurred_at);

-- ─────────────── Manifiestos y salidas ───────────────
CREATE TABLE manifests (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    number         VARCHAR(40)  NOT NULL UNIQUE,
    status         VARCHAR(20)  NOT NULL CHECK (status IN ('BORRADOR', 'CERRADO', 'DESPACHADO')),
    operation_date DATE         NOT NULL,
    shift_id       BIGINT REFERENCES shifts (id),
    sender_id      BIGINT REFERENCES senders (id),
    notes          VARCHAR(1000),
    created_by     BIGINT       NOT NULL REFERENCES users (id),
    closed_at      TIMESTAMPTZ,
    closed_by      BIGINT REFERENCES users (id),
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_manifests_status_date ON manifests (status, operation_date DESC);

CREATE TABLE manifest_guides (
    manifest_id BIGINT  NOT NULL REFERENCES manifests (id) ON DELETE CASCADE,
    guide_id    BIGINT  NOT NULL REFERENCES guides (id),
    position    INTEGER NOT NULL,
    PRIMARY KEY (manifest_id, guide_id),
    -- Una guía sólo puede pertenecer a un manifiesto.
    CONSTRAINT ux_manifest_guides_guide UNIQUE (guide_id)
);

CREATE TABLE dispatches (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    manifest_id   BIGINT       NOT NULL UNIQUE REFERENCES manifests (id),
    dispatched_by BIGINT       NOT NULL REFERENCES users (id),
    dispatched_at TIMESTAMPTZ  NOT NULL,
    courier_id    BIGINT REFERENCES couriers (id),
    vehicle       VARCHAR(80),
    notes         VARCHAR(1000),
    guide_count   INTEGER      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ─────────────── Operación ───────────────
CREATE TABLE courier_assignments (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    guide_id      BIGINT      NOT NULL REFERENCES guides (id),
    courier_id    BIGINT      NOT NULL REFERENCES couriers (id),
    assigned_by   BIGINT      NOT NULL REFERENCES users (id),
    assigned_at   TIMESTAMPTZ NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    released_at   TIMESTAMPTZ,
    released_by   BIGINT REFERENCES users (id),
    route_order   INTEGER
);
CREATE UNIQUE INDEX ux_assignment_active_guide ON courier_assignments (guide_id) WHERE active;
CREATE INDEX ix_assignment_courier_active ON courier_assignments (courier_id, active);

CREATE TABLE receptions (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    guide_id         BIGINT       NOT NULL UNIQUE REFERENCES guides (id),
    received_by      BIGINT       NOT NULL REFERENCES users (id),
    received_at      TIMESTAMPTZ  NOT NULL,
    location         VARCHAR(160),
    received_count   INTEGER,
    has_differences  BOOLEAN      NOT NULL DEFAULT FALSE,
    differences      VARCHAR(1000),
    notes            VARCHAR(1000)
);

CREATE TABLE deliveries (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    guide_id         BIGINT       NOT NULL UNIQUE REFERENCES guides (id),
    delivered_by     BIGINT       NOT NULL REFERENCES users (id),
    courier_id       BIGINT REFERENCES couriers (id),
    received_by_name VARCHAR(160) NOT NULL,
    delivered_at     TIMESTAMPTZ  NOT NULL,
    notes            VARCHAR(1000)
);

-- ─────────────── Incidencias ───────────────
CREATE TABLE incidents (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    folio                VARCHAR(30)  NOT NULL UNIQUE,
    guide_id             BIGINT       NOT NULL REFERENCES guides (id),
    type                 VARCHAR(40)  NOT NULL,
    severity             VARCHAR(10)  NOT NULL CHECK (severity IN ('BAJA', 'MEDIA', 'ALTA')),
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('ABIERTA', 'EN_REVISION', 'RESUELTA')),
    description          VARCHAR(2000) NOT NULL,
    guide_status_before  VARCHAR(30)  NOT NULL,
    reported_by          BIGINT       NOT NULL REFERENCES users (id),
    reported_by_name     VARCHAR(160) NOT NULL,
    assigned_to          BIGINT REFERENCES users (id),
    resolved_at          TIMESTAMPTZ,
    resolved_by          BIGINT REFERENCES users (id),
    resolution           VARCHAR(2000),
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_incidents_status ON incidents (status, created_at DESC);
CREATE INDEX ix_incidents_guide ON incidents (guide_id);
-- Una sola incidencia abierta por guía a la vez.
CREATE UNIQUE INDEX ux_incidents_open_guide ON incidents (guide_id) WHERE status <> 'RESUELTA';

CREATE TABLE incident_notes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id BIGINT       NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    author_id   BIGINT REFERENCES users (id),
    author_name VARCHAR(160) NOT NULL,
    text        VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_incident_notes_incident ON incident_notes (incident_id);

-- ─────────────── Archivos ───────────────
CREATE TABLE attachments (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_type   VARCHAR(30)  NOT NULL,
    owner_id     BIGINT       NOT NULL,
    category     VARCHAR(30)  NOT NULL,
    filename     VARCHAR(200) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    sha256       VARCHAR(64)  NOT NULL,
    data         BYTEA        NOT NULL,
    uploaded_by  BIGINT REFERENCES users (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_attachments_owner ON attachments (owner_type, owner_id);

-- ─────────────── Auditoría ───────────────
CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    user_id     BIGINT,
    user_email  VARCHAR(160),
    action      VARCHAR(60)  NOT NULL,
    entity_type VARCHAR(40),
    entity_id   VARCHAR(60),
    ip          VARCHAR(64),
    details     JSONB
);
CREATE INDEX ix_audit_occurred ON audit_log (occurred_at DESC);
CREATE INDEX ix_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_user ON audit_log (user_id, occurred_at DESC);
