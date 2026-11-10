package mx.salter.api.security;

/**
 * Permisos atómicos. Los roles agrupan permisos (tabla role_permissions) y cada usuario
 * puede tener permisos concedidos o retirados individualmente.
 * La autorización SIEMPRE se valida en el backend.
 */
public enum Permission {
    DASHBOARD_VIEW("Ver dashboard operativo"),
    USERS_MANAGE("Administrar usuarios"),
    ROLES_MANAGE("Administrar roles y permisos"),
    SETTINGS_MANAGE("Administrar configuración"),
    AUDIT_VIEW("Consultar auditoría"),
    SENDERS_VIEW("Consultar todos los remitentes"),
    SENDERS_MANAGE("Administrar remitentes"),
    RECIPIENTS_VIEW("Consultar destinatarios autorizados"),
    RECIPIENTS_MANAGE("Registrar y editar destinatarios"),
    RECIPIENTS_IMPORT("Importar destinatarios masivamente"),
    CATALOGS_MANAGE("Administrar turnos y conceptos de mercancía"),
    GUIDES_CREATE("Elaborar guías"),
    GUIDES_VIEW_OWN("Consultar guías propias"),
    GUIDES_VIEW_ALL("Consultar todas las guías"),
    GUIDES_CONFIRM("Confirmar guías"),
    GUIDES_CANCEL("Cancelar guías"),
    GUIDES_CORRECT("Corregir guías confirmadas (auditado)"),
    GUIDES_HOLD("Retener y liberar guías"),
    MANIFESTS_VIEW("Consultar manifiestos"),
    MANIFESTS_MANAGE("Elaborar y cerrar manifiestos"),
    DISPATCH_AUTHORIZE("Autorizar salidas"),
    COURIERS_VIEW("Consultar repartidores"),
    COURIERS_MANAGE("Administrar repartidores"),
    ASSIGNMENTS_MANAGE("Asignar repartidores"),
    DELIVERIES_OWN("Operar envíos asignados (repartidor)"),
    DELIVERIES_ANY("Registrar entregas de cualquier envío"),
    RECEPTION_CONFIRM("Confirmar recepción en destino"),
    INCIDENTS_VIEW("Consultar todas las incidencias"),
    INCIDENTS_CREATE("Registrar incidencias"),
    INCIDENTS_RESOLVE("Dar seguimiento y resolver incidencias"),
    REPORTS_VIEW("Generar reportes generales"),
    REPORTS_OWN("Consultar reportes propios (remitente)"),
    SHIPMENTS_OWN("Consultar envíos propios (remitente)");

    private final String description;

    Permission(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
