package mx.salter.api.guide;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estados PROVISIONALES de una guía (nombres y reglas pendientes de validar con el cliente).
 * Las transiciones permitidas se controlan exclusivamente en el backend.
 */
public enum GuideStatus {
    BORRADOR("Borrador"),
    CONFIRMADA("Confirmada"),
    LISTA_SALIDA("Lista para salida"),
    EN_TRANSITO("En tránsito"),
    RECIBIDA_DESTINO("Recibida en destino"),
    ENTREGADA("Entrega final confirmada"),
    CON_INCIDENCIA("Con incidencia"),
    RETENIDA("Retenida"),
    CANCELADA("Cancelada");

    private final String label;

    GuideStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    private static final Map<GuideStatus, Set<GuideStatus>> TRANSITIONS = Map.of(
            BORRADOR, EnumSet.of(CONFIRMADA, CANCELADA),
            CONFIRMADA, EnumSet.of(LISTA_SALIDA, RETENIDA, CANCELADA),
            LISTA_SALIDA, EnumSet.of(CONFIRMADA, EN_TRANSITO, RETENIDA),
            EN_TRANSITO, EnumSet.of(RECIBIDA_DESTINO, ENTREGADA, CON_INCIDENCIA, RETENIDA),
            RECIBIDA_DESTINO, EnumSet.of(ENTREGADA, CON_INCIDENCIA, RETENIDA),
            CON_INCIDENCIA, EnumSet.of(EN_TRANSITO, RECIBIDA_DESTINO, RETENIDA, CANCELADA),
            RETENIDA, EnumSet.of(CONFIRMADA, LISTA_SALIDA, EN_TRANSITO, RECIBIDA_DESTINO, CANCELADA),
            ENTREGADA, EnumSet.noneOf(GuideStatus.class),
            CANCELADA, EnumSet.noneOf(GuideStatus.class));

    public boolean canTransitionTo(GuideStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public Set<GuideStatus> nextStatuses() {
        return Set.copyOf(TRANSITIONS.get(this));
    }

    public boolean isTerminal() {
        return this == ENTREGADA || this == CANCELADA;
    }

    /** Estados en los que la mercancía ya salió de origen. */
    public boolean isDispatched() {
        return this == EN_TRANSITO || this == RECIBIDA_DESTINO || this == ENTREGADA || this == CON_INCIDENCIA;
    }
}
