-- Índices adicionales detectados al perfilar consultas con volumen.

-- Historial y verificación de acceso del repartidor (asignaciones activas e inactivas por guía).
CREATE INDEX IF NOT EXISTS ix_assignment_guide_courier ON courier_assignments (guide_id, courier_id);

-- Usuarios sin INCIDENTS_VIEW sólo listan las incidencias que reportaron.
CREATE INDEX IF NOT EXISTS ix_incidents_reported_by ON incidents (reported_by, created_at DESC);
