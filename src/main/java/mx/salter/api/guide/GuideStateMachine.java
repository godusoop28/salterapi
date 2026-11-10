package mx.salter.api.guide;

import java.time.Clock;
import java.util.Map;
import mx.salter.api.catalog.SettingsService;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.Text;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.SalterPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Única vía para cambiar el estado de una guía; cada cambio deja un movimiento inmutable. */
@Component
public class GuideStateMachine {
    private final GuideMovementRepository movements;
    private final SettingsService settings;
    private final Clock clock;

    public GuideStateMachine(GuideMovementRepository movements, SettingsService settings, Clock clock) {
        this.movements = movements;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public GuideMovement transition(Guide g, GuideStatus to, MovementType type, String notes, String location,
                                    Map<String, Object> metadata) {
        GuideStatus from = g.getStatus();
        if (!from.canTransitionTo(to)) {
            throw ApiException.business("INVALID_TRANSITION",
                    "La guía " + g.displayReference() + " está en estado «" + from.getLabel() + "» y no puede pasar a «" + to.getLabel() + "».")
                    .with("currentStatus", from.name());
        }
        if (from == GuideStatus.EN_TRANSITO && to == GuideStatus.ENTREGADA && settings.getBool("delivery.requireReception", true)) {
            throw ApiException.business("RECEPTION_REQUIRED",
                    "La guía " + g.displayReference() + " debe registrarse como recibida en destino antes de la entrega final.");
        }
        g.setStatus(to);
        return log(g, from, to, type, notes, location, metadata);
    }

    /** Movimiento informativo sin cambio de estado (observaciones, asignaciones, correcciones). */
    @Transactional(propagation = Propagation.MANDATORY)
    public GuideMovement record(Guide g, MovementType type, String notes, String location, Map<String, Object> metadata) {
        return log(g, g.getStatus(), g.getStatus(), type, notes, location, metadata);
    }

    private GuideMovement log(Guide g, GuideStatus from, GuideStatus to, MovementType type, String notes, String location,
                              Map<String, Object> metadata) {
        SalterPrincipal p = CurrentUser.orNull();
        GuideMovement m = new GuideMovement();
        m.setGuideId(g.getId());
        m.setType(type);
        m.setFromStatus(from);
        m.setToStatus(to);
        m.setUserId(p == null ? null : p.userId());
        m.setUserName(p == null ? "Sistema" : p.fullName());
        m.setOccurredAt(clock.instant());
        m.setNotes(Text.truncate(Text.trimToNull(notes), 1000));
        m.setLocation(Text.truncate(Text.trimToNull(location), 160));
        m.setMetadata(metadata == null || metadata.isEmpty() ? null : metadata);
        return movements.save(m);
    }
}
