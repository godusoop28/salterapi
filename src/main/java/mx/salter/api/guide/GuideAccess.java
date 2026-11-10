package mx.salter.api.guide;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import mx.salter.api.common.ApiException;
import mx.salter.api.operation.AssignmentRepository;
import mx.salter.api.operation.CourierAssignment;
import mx.salter.api.security.Permission;
import mx.salter.api.security.SalterPrincipal;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Política de alcance de datos sobre guías. Se aplica tanto a consultas (Specification) como
 * a accesos individuales, para impedir accesos cruzados entre usuarios, remitentes y repartidores.
 */
@Component
public class GuideAccess {
    /** Estados que el personal de recepción puede consultar. */
    static final Set<GuideStatus> RECEPTION_VISIBLE = EnumSet.of(GuideStatus.EN_TRANSITO, GuideStatus.RECIBIDA_DESTINO,
            GuideStatus.CON_INCIDENCIA, GuideStatus.ENTREGADA, GuideStatus.RETENIDA);

    private final AssignmentRepository assignments;

    public GuideAccess(AssignmentRepository assignments) {
        this.assignments = assignments;
    }

    public Specification<Guide> scope(SalterPrincipal p) {
        return (root, query, cb) -> {
            if (p.has(Permission.GUIDES_VIEW_ALL)) return cb.conjunction();
            List<Predicate> any = new ArrayList<>();
            if (p.has(Permission.GUIDES_VIEW_OWN)) {
                any.add(cb.equal(root.get("createdBy").get("id"), p.userId()));
            }
            if (p.has(Permission.SHIPMENTS_OWN) && p.senderId() != null) {
                any.add(cb.and(cb.equal(root.get("sender").get("id"), p.senderId()),
                        cb.notEqual(root.get("status"), GuideStatus.BORRADOR)));
            }
            if (p.has(Permission.DELIVERIES_OWN) && p.courierId() != null) {
                Subquery<Long> sq = query.subquery(Long.class);
                var a = sq.from(CourierAssignment.class);
                sq.select(a.get("id")).where(cb.equal(a.get("guide"), root), cb.equal(a.get("courier").get("id"), p.courierId()),
                        cb.isTrue(a.get("active")));
                any.add(cb.exists(sq));
            }
            if (p.has(Permission.RECEPTION_CONFIRM)) {
                any.add(root.get("status").in(RECEPTION_VISIBLE));
            }
            if (any.isEmpty()) return cb.disjunction();
            return cb.or(any.toArray(Predicate[]::new));
        };
    }

    public boolean canView(SalterPrincipal p, Guide g) {
        if (p.has(Permission.GUIDES_VIEW_ALL)) return true;
        if (p.has(Permission.GUIDES_VIEW_OWN) && g.getCreatedBy().getId().equals(p.userId())) return true;
        if (p.has(Permission.SHIPMENTS_OWN) && p.senderId() != null && g.getSender().getId().equals(p.senderId())
                && g.getStatus() != GuideStatus.BORRADOR) return true;
        if (p.has(Permission.RECEPTION_CONFIRM) && RECEPTION_VISIBLE.contains(g.getStatus())) return true;
        // El repartidor puede consultar guías que tuvo asignadas (historial); sólo opera las activas.
        return p.has(Permission.DELIVERIES_OWN) && p.courierId() != null
                && assignments.existsByGuideIdAndCourierId(g.getId(), p.courierId());
    }

    public boolean isAssignedCourier(SalterPrincipal p, Guide g) {
        return p.has(Permission.DELIVERIES_OWN) && p.courierId() != null
                && assignments.existsByGuideIdAndCourierIdAndActiveTrue(g.getId(), p.courierId());
    }

    public void requireView(SalterPrincipal p, Guide g) {
        // Se responde 404 para no revelar la existencia de guías ajenas.
        if (!canView(p, g)) throw ApiException.notFound("Guía");
    }
}
