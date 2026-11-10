package mx.salter.api.operation;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.common.ApiException;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideRepository;
import mx.salter.api.guide.GuideStateMachine;
import mx.salter.api.guide.GuideStatus;
import mx.salter.api.guide.MovementType;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentService {
    private final AssignmentRepository assignments;
    private final CourierRepository couriers;
    private final GuideRepository guides;
    private final GuideStateMachine fsm;
    private final AuditService audit;
    private final Clock clock;

    public AssignmentService(AssignmentRepository assignments, CourierRepository couriers, GuideRepository guides,
                             GuideStateMachine fsm, AuditService audit, Clock clock) {
        this.assignments = assignments;
        this.couriers = couriers;
        this.guides = guides;
        this.fsm = fsm;
        this.audit = audit;
        this.clock = clock;
    }

    /** Asigna (o reasigna) guías a un repartidor. Una guía sólo tiene una asignación activa. */
    @Transactional
    public int assign(List<Long> guideIds, Long courierId) {
        CurrentUser.require(Permission.ASSIGNMENTS_MANAGE);
        Courier c = couriers.findById(courierId).orElseThrow(() -> ApiException.notFound("Repartidor"));
        if (!c.isActive()) throw ApiException.business("COURIER_INACTIVE", "El repartidor está inactivo.");
        List<Guide> locked = guides.lockAllById(guideIds);
        if (locked.size() != guideIds.stream().distinct().count()) throw ApiException.badRequest("INVALID_GUIDES", "Alguna guía no existe.");
        List<String> invalid = new ArrayList<>();
        for (Guide g : locked) {
            if (g.getStatus() == GuideStatus.BORRADOR || g.getStatus().isTerminal()) invalid.add(g.displayReference());
        }
        if (!invalid.isEmpty()) {
            throw ApiException.business("NOT_ASSIGNABLE", "No se pueden asignar guías en borrador, entregadas o canceladas: "
                    + String.join(", ", invalid) + ".");
        }
        return assignLocked(locked, c);
    }

    /** Usado también por el despacho con repartidor (las guías ya están bloqueadas). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int assignLocked(List<Guide> locked, Courier c) {
        Long userId = CurrentUser.get().userId();
        int order = (int) assignments.countActiveByCourier(c.getId());
        int changed = 0;
        for (Guide g : locked) {
            var current = assignments.findByGuideIdAndActiveTrue(g.getId()).orElse(null);
            if (current != null && current.getCourier().getId().equals(c.getId())) continue;
            if (current != null) {
                current.setActive(false);
                current.setReleasedAt(clock.instant());
                current.setReleasedBy(userId);
                assignments.flush();
            }
            CourierAssignment a = new CourierAssignment();
            a.setGuide(g);
            a.setCourier(c);
            a.setAssignedBy(userId);
            a.setAssignedAt(clock.instant());
            a.setRouteOrder(++order);
            assignments.save(a);
            fsm.record(g, MovementType.ASIGNACION, null, null,
                    current == null ? Map.of("courier", c.getName()) : Map.of("courier", c.getName(), "previous", current.getCourier().getName()));
            changed++;
        }
        assignments.flush();
        audit.record("GUIDES_ASSIGNED", "COURIER", c.getId(), Map.of("guides", locked.stream().map(Guide::getId).toList(), "changed", changed));
        return changed;
    }

    @Transactional
    public void unassign(Long guideId) {
        CurrentUser.require(Permission.ASSIGNMENTS_MANAGE);
        Guide g = guides.lockById(guideId).orElseThrow(() -> ApiException.notFound("Guía"));
        CourierAssignment a = assignments.findByGuideIdAndActiveTrue(guideId)
                .orElseThrow(() -> ApiException.business("NOT_ASSIGNED", "La guía no tiene repartidor asignado."));
        a.setActive(false);
        a.setReleasedAt(clock.instant());
        a.setReleasedBy(CurrentUser.get().userId());
        fsm.record(g, MovementType.DESASIGNACION, null, null, Map.of("courier", a.getCourier().getName()));
        audit.record("GUIDE_UNASSIGNED", "GUIDE", guideId, Map.of("courierId", a.getCourier().getId()));
    }
}
