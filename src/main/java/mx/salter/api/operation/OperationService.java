package mx.salter.api.operation;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.catalog.SettingsService;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.Text;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideAccess;
import mx.salter.api.guide.GuideDtos.GuideDto;
import mx.salter.api.guide.GuideService;
import mx.salter.api.guide.GuideStateMachine;
import mx.salter.api.guide.GuideStatus;
import mx.salter.api.guide.MovementType;
import mx.salter.api.incident.Incident;
import mx.salter.api.incident.IncidentService;
import mx.salter.api.operation.OperationDtos.CourierAssignmentDto;
import mx.salter.api.operation.OperationDtos.DeliveryRequest;
import mx.salter.api.operation.OperationDtos.HistoryItemDto;
import mx.salter.api.operation.OperationDtos.ReceptionRequest;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.Permission;
import mx.salter.api.security.SalterPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recepción en destino, entrega final y vista del repartidor. */
@Service
public class OperationService {
    private final GuideService guideService;
    private final GuideStateMachine fsm;
    private final GuideAccess access;
    private final ReceptionRepository receptions;
    private final DeliveryRepository deliveries;
    private final AssignmentRepository assignments;
    private final IncidentService incidents;
    private final SettingsService settings;
    private final AuditService audit;
    private final Clock clock;

    public OperationService(GuideService guideService, GuideStateMachine fsm, GuideAccess access, ReceptionRepository receptions,
                            DeliveryRepository deliveries, AssignmentRepository assignments, IncidentService incidents,
                            SettingsService settings, AuditService audit, Clock clock) {
        this.guideService = guideService;
        this.fsm = fsm;
        this.access = access;
        this.receptions = receptions;
        this.deliveries = deliveries;
        this.assignments = assignments;
        this.incidents = incidents;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Confirma la recepción en la instalación de destino. Rechaza duplicados (restricción única + bloqueo)
     * y estados que no correspondan. Si hay diferencias, abre una incidencia automáticamente.
     */
    @Transactional
    public GuideDto receive(Long guideId, ReceptionRequest req) {
        CurrentUser.require(Permission.RECEPTION_CONFIRM);
        SalterPrincipal p = CurrentUser.get();
        Guide g = guideService.lockAccessible(guideId);
        if (receptions.existsByGuideId(g.getId())) {
            throw ApiException.conflict("ALREADY_RECEIVED", "La guía " + g.displayReference() + " ya tiene una recepción registrada.");
        }
        if (g.getStatus() != GuideStatus.EN_TRANSITO) {
            throw ApiException.business("INVALID_TRANSITION", "Sólo se reciben guías en tránsito. Estado actual: «" + g.getStatus().getLabel() + "».")
                    .with("currentStatus", g.getStatus().name());
        }
        if (req.hasDifferences() && (req.differences() == null || req.differences().isBlank())) {
            throw ApiException.badRequest("DIFFERENCES_REQUIRED", "Describe las diferencias encontradas.");
        }
        Reception r = new Reception();
        r.setGuideId(g.getId());
        r.setReceivedBy(p.userId());
        r.setReceivedAt(clock.instant());
        r.setLocation(Text.trimToNull(req.location()));
        r.setReceivedCount(req.receivedCount());
        r.setHasDifferences(req.hasDifferences());
        r.setDifferences(Text.trimToNull(req.differences()));
        r.setNotes(Text.trimToNull(req.notes()));
        receptions.saveAndFlush(r);
        Map<String, Object> meta = new LinkedHashMap<>();
        if (req.receivedCount() != null) meta.put("receivedCount", req.receivedCount());
        meta.put("expectedCount", g.getPackageCount());
        if (req.hasDifferences()) meta.put("differences", req.differences().trim());
        fsm.transition(g, GuideStatus.RECIBIDA_DESTINO, MovementType.RECEPCION, req.notes(), req.location(), meta);
        audit.record("GUIDE_RECEIVED", "GUIDE", g.getId(), meta);
        boolean countMismatch = req.receivedCount() != null && req.receivedCount() != g.getPackageCount();
        if (req.hasDifferences() || countMismatch) {
            String desc = req.hasDifferences() ? req.differences().trim()
                    : "Se recibieron " + req.receivedCount() + " bultos de " + g.getPackageCount() + " esperados.";
            incidents.openInternal(g, Incident.Type.DIFERENCIA_RECEPCION, Incident.Severity.MEDIA, desc);
        }
        return guideService.toDto(g);
    }

    /** Entrega final al destinatario (repartidor asignado o usuario con DELIVERIES_ANY). */
    @Transactional
    public GuideDto deliver(Long guideId, DeliveryRequest req) {
        SalterPrincipal p = CurrentUser.get();
        Guide g = guideService.lockAccessible(guideId);
        if (deliveries.existsByGuideId(g.getId())) {
            throw ApiException.conflict("ALREADY_DELIVERED", "La entrega de la guía " + g.displayReference() + " ya fue confirmada.");
        }
        boolean assigned = access.isAssignedCourier(p, g);
        if (!assigned && !p.has(Permission.DELIVERIES_ANY)) {
            throw ApiException.forbidden("Sólo el repartidor asignado o un supervisor puede confirmar la entrega.");
        }
        fsm.transition(g, GuideStatus.ENTREGADA, MovementType.ENTREGA, req.notes(), null, Map.of("receivedBy", req.receivedByName().trim()));
        Delivery d = new Delivery();
        d.setGuideId(g.getId());
        d.setDeliveredBy(p.userId());
        d.setCourierId(assignments.findByGuideIdAndActiveTrue(g.getId()).map(a -> a.getCourier().getId()).orElse(null));
        d.setReceivedByName(req.receivedByName().trim());
        d.setDeliveredAt(clock.instant());
        d.setNotes(Text.trimToNull(req.notes()));
        deliveries.saveAndFlush(d);
        assignments.findByGuideIdAndActiveTrue(g.getId()).ifPresent(a -> {
            a.setActive(false);
            a.setReleasedAt(clock.instant());
            a.setReleasedBy(p.userId());
        });
        audit.record("GUIDE_DELIVERED", "GUIDE", g.getId(), Map.of("receivedBy", d.getReceivedByName()));
        return guideService.toDto(g);
    }

    /** Asignaciones activas del repartidor que inició sesión. */
    @Transactional(readOnly = true)
    public List<CourierAssignmentDto> myAssignments() {
        SalterPrincipal p = CurrentUser.get();
        if (p.courierId() == null) throw ApiException.forbidden("Tu usuario no está vinculado a un repartidor.");
        return assignments.findActiveByCourier(p.courierId()).stream().map(a -> {
            Guide g = a.getGuide();
            return new CourierAssignmentDto(a.getId(), a.getRouteOrder(), a.getAssignedAt(), GuideService.toSummary(g),
                    g.getRcptStreet(), g.getRcptNeighborhood(), g.getRcptRefs(), g.getRcptZip(), g.getNotes());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<HistoryItemDto> myHistory(int days) {
        SalterPrincipal p = CurrentUser.get();
        if (p.courierId() == null) throw ApiException.forbidden("Tu usuario no está vinculado a un repartidor.");
        return assignments.findHistory(p.courierId(), clock.instant().minus(Duration.ofDays(Math.min(Math.max(days, 1), 90))))
                .stream().map(a -> new HistoryItemDto(a.getAssignedAt(), a.isActive(), a.getReleasedAt(), GuideService.toSummary(a.getGuide())))
                .toList();
    }
}
