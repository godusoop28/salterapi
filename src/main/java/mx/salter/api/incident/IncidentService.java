package mx.salter.api.incident;

import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.catalog.FolioService;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.Clocks;
import mx.salter.api.common.PageResponse;
import mx.salter.api.common.Paging;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideAccess;
import mx.salter.api.guide.GuideRepository;
import mx.salter.api.guide.GuideStateMachine;
import mx.salter.api.guide.GuideStatus;
import mx.salter.api.guide.MovementType;
import mx.salter.api.incident.IncidentDtos.CreateIncidentRequest;
import mx.salter.api.incident.IncidentDtos.IncidentDto;
import mx.salter.api.incident.IncidentDtos.NoteDto;
import mx.salter.api.incident.IncidentDtos.Outcome;
import mx.salter.api.incident.IncidentDtos.StatusRequest;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.Permission;
import mx.salter.api.security.SalterPrincipal;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {
    private static final EnumSet<GuideStatus> REPORTABLE = EnumSet.of(GuideStatus.EN_TRANSITO, GuideStatus.RECIBIDA_DESTINO);

    private final IncidentRepository repo;
    private final GuideRepository guides;
    private final GuideAccess access;
    private final GuideStateMachine fsm;
    private final FolioService folios;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;

    public IncidentService(IncidentRepository repo, GuideRepository guides, GuideAccess access, GuideStateMachine fsm,
                           FolioService folios, AuditService audit, Clock clock, ZoneId zone) {
        this.repo = repo;
        this.guides = guides;
        this.access = access;
        this.fsm = fsm;
        this.folios = folios;
        this.audit = audit;
        this.clock = clock;
        this.zone = zone;
    }

    @Transactional(readOnly = true)
    public PageResponse<IncidentDto> search(Incident.Status status, Incident.Severity severity, Incident.Type type, String q,
                                            LocalDate from, LocalDate to, Integer page, Integer size) {
        SalterPrincipal p = CurrentUser.get();
        Specification<Incident> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (!p.has(Permission.INCIDENTS_VIEW)) ps.add(cb.equal(root.get("reportedBy"), p.userId()));
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (severity != null) ps.add(cb.equal(root.get("severity"), severity));
            if (type != null) ps.add(cb.equal(root.get("type"), type));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), Clocks.startOfDay(from, zone)));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"), Clocks.endOfDayExclusive(to, zone)));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toUpperCase() + "%";
                ps.add(cb.or(cb.like(cb.upper(root.get("folio")), like), cb.like(cb.upper(root.get("guide").get("folio")), like)));
            }
            if (query != null && Long.class != query.getResultType()) root.fetch("guide");
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return PageResponse.of(repo.findAll(spec, Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))), i -> toDto(i, false));
    }

    @Transactional(readOnly = true)
    public IncidentDto get(Long id) {
        Incident i = repo.findDetailed(id).orElseThrow(() -> ApiException.notFound("Incidencia"));
        requireView(i);
        return toDto(i, true);
    }

    @Transactional
    public IncidentDto create(CreateIncidentRequest req) {
        CurrentUser.require(Permission.INCIDENTS_CREATE);
        SalterPrincipal p = CurrentUser.get();
        Guide g = guides.lockById(req.guideId()).orElseThrow(() -> ApiException.notFound("Guía"));
        access.requireView(p, g);
        if (p.has(Permission.DELIVERIES_OWN) && !p.hasAny(Permission.GUIDES_VIEW_ALL, Permission.RECEPTION_CONFIRM)
                && !access.isAssignedCourier(p, g)) {
            throw ApiException.forbidden("Sólo puedes reportar incidencias de tus envíos asignados.");
        }
        Incident i = openInternal(g, req.type(), req.severity(), req.description());
        return toDto(i, true);
    }

    /** Abre la incidencia y pasa la guía a "Con incidencia" guardando el estado previo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Incident openInternal(Guide g, Incident.Type type, Incident.Severity severity, String description) {
        SalterPrincipal p = CurrentUser.get();
        if (!REPORTABLE.contains(g.getStatus())) {
            throw ApiException.business("NOT_REPORTABLE", "Sólo se registran incidencias de guías en tránsito o recibidas en destino.");
        }
        if (repo.findOpenByGuide(g.getId()).isPresent()) {
            throw ApiException.conflict("INCIDENT_OPEN", "La guía ya tiene una incidencia abierta.");
        }
        Incident i = new Incident();
        i.setFolio(folios.next(FolioService.INCIDENT));
        i.setGuide(g);
        i.setType(type);
        i.setSeverity(severity);
        i.setDescription(description.trim());
        i.setGuideStatusBefore(g.getStatus());
        i.setReportedBy(p.userId());
        i.setReportedByName(p.fullName());
        repo.saveAndFlush(i);
        fsm.transition(g, GuideStatus.CON_INCIDENCIA, MovementType.INCIDENCIA, description, null,
                Map.of("incident", i.getFolio(), "type", type.name(), "severity", severity.name()));
        audit.record("INCIDENT_CREATED", "INCIDENT", i.getId(), Map.of("folio", i.getFolio(), "guideId", g.getId()));
        return i;
    }

    @Transactional
    public IncidentDto addNote(Long id, String text) {
        SalterPrincipal p = CurrentUser.get();
        Incident i = repo.findDetailed(id).orElseThrow(() -> ApiException.notFound("Incidencia"));
        requireView(i);
        if (i.getStatus() == Incident.Status.RESUELTA) throw ApiException.business("INCIDENT_CLOSED", "La incidencia ya está resuelta.");
        IncidentNote n = new IncidentNote();
        n.setIncident(i);
        n.setAuthorId(p.userId());
        n.setAuthorName(p.fullName());
        n.setText(text.trim());
        i.getNotes().add(n);
        repo.flush();
        return toDto(i, true);
    }

    @Transactional
    public IncidentDto changeStatus(Long id, StatusRequest req) {
        CurrentUser.require(Permission.INCIDENTS_RESOLVE);
        SalterPrincipal p = CurrentUser.get();
        Incident i = repo.findDetailed(id).orElseThrow(() -> ApiException.notFound("Incidencia"));
        if (req.version() != i.getVersion()) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "La incidencia fue modificada por otra persona. Recarga la información.");
        }
        if (i.getStatus() == Incident.Status.RESUELTA) throw ApiException.business("INCIDENT_CLOSED", "La incidencia ya está resuelta.");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("from", i.getStatus().name());
        d.put("to", req.status().name());
        if (req.status() == Incident.Status.RESUELTA) {
            if (req.resolution() == null || req.resolution().isBlank()) {
                throw ApiException.badRequest("RESOLUTION_REQUIRED", "Describe cómo se resolvió la incidencia.");
            }
            Guide g = guides.lockById(i.getGuide().getId()).orElseThrow();
            Outcome outcome = req.outcome() == null ? Outcome.RESTAURAR : req.outcome();
            if (g.getStatus() == GuideStatus.CON_INCIDENCIA) {
                Map<String, Object> meta = Map.of("incident", i.getFolio(), "outcome", outcome.name());
                switch (outcome) {
                    case RESTAURAR -> fsm.transition(g, i.getGuideStatusBefore(), MovementType.INCIDENCIA_RESUELTA, req.resolution(), null, meta);
                    case RETENER -> {
                        fsm.transition(g, GuideStatus.RETENIDA, MovementType.INCIDENCIA_RESUELTA, req.resolution(), null, meta);
                        g.setStatusBeforeHold(i.getGuideStatusBefore());
                    }
                    case CANCELAR -> {
                        fsm.transition(g, GuideStatus.CANCELADA, MovementType.INCIDENCIA_RESUELTA, req.resolution(), null, meta);
                        g.setCancelledAt(clock.instant());
                        g.setCancelReason("Incidencia " + i.getFolio() + ": " + req.resolution().trim());
                    }
                }
            }
            i.setResolution(req.resolution().trim());
            i.setResolvedAt(clock.instant());
            i.setResolvedBy(p.userId());
            d.put("outcome", outcome.name());
        }
        i.setStatus(req.status());
        audit.record("INCIDENT_STATUS", "INCIDENT", i.getId(), d);
        repo.flush();
        return toDto(i, true);
    }

    private void requireView(Incident i) {
        SalterPrincipal p = CurrentUser.get();
        if (p.has(Permission.INCIDENTS_VIEW) || i.getReportedBy().equals(p.userId())) return;
        throw ApiException.notFound("Incidencia");
    }

    private static IncidentDto toDto(Incident i, boolean withNotes) {
        Guide g = i.getGuide();
        return new IncidentDto(i.getId(), i.getFolio(), g.getId(), g.getFolio(), g.getStatus(), i.getType(), i.getSeverity(),
                i.getStatus(), i.getDescription(), i.getGuideStatusBefore(), i.getReportedByName(), i.getCreatedAt(),
                i.getResolvedAt(), i.getResolution(),
                withNotes ? i.getNotes().stream().map(n -> new NoteDto(n.getId(), n.getAuthorName(), n.getText(), n.getCreatedAt())).toList() : List.of(),
                i.getVersion());
    }
}
