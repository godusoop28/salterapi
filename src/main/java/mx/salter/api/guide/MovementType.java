package mx.salter.api.guide;

public enum MovementType {
    CREACION("Guía elaborada"),
    EDICION("Borrador actualizado"),
    CONFIRMACION("Guía confirmada"),
    MANIFIESTO("Incluida en manifiesto cerrado"),
    MANIFIESTO_REABIERTO("Manifiesto reabierto"),
    DESPACHO("Salida autorizada"),
    ASIGNACION("Repartidor asignado"),
    DESASIGNACION("Repartidor retirado"),
    RECEPCION("Recibida en destino"),
    ENTREGA("Entrega final confirmada"),
    INCIDENCIA("Incidencia registrada"),
    INCIDENCIA_RESUELTA("Incidencia resuelta"),
    RETENCION("Guía retenida"),
    LIBERACION("Guía liberada"),
    CORRECCION("Corrección auditada"),
    CANCELACION("Guía cancelada"),
    NOTA("Observación");

    private final String label;

    MovementType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
