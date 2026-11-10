package mx.salter.api.manifest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.salter.api.audit.AuditService;
import mx.salter.api.auth.AuthDtos.Ref;
import mx.salter.api.catalog.FolioService;
import mx.salter.api.catalog.Shift;
import mx.salter.api.catalog.ShiftRepository;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PageResponse;
import mx.salter.api.common.Paging;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideDtos.GuideFilter;
import mx.salter.api.guide.GuideDtos.GuideSummaryDto;
import mx.salter.api.guide.GuideRepository;
import mx.salter.api.guide.GuideService;
import mx.salter.api.guide.GuideSpecs;
import mx.salter.api.guide.GuideStateMachine;
import mx.salter.api.guide.GuideStatus;
import mx.salter.api.guide.MovementType;
import mx.salter.api.manifest.ManifestDtos.DispatchDto;
import mx.salter.api.manifest.ManifestDtos.DispatchRequest;
import mx.salter.api.manifest.ManifestDtos.ManifestDto;
import mx.salter.api.manifest.ManifestDtos.ManifestRequest;
import mx.salter.api.manifest.ManifestDtos.ManifestSummaryDto;
import mx.salter.api.operation.AssignmentService;
import mx.salter.api.operation.Courier;
import mx.salter.api.operation.CourierRepository;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.Permission;
import mx.salter.api.security.SalterPrincipal;
import mx.salter.api.sender.Sender;
import mx.salter.api.sender.SenderRepository;
import mx.salter.api.user.User;
import mx.salter.api.user.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manifiestos (estados PROVISIONALES: BORRADOR → CERRADO → DESPACHADO).
 * Cerrar el manifiesto deja las guías "Listas para salida"; la salida real se registra
 * únicamente con la autorización de despacho, en una sola transacción.
 */
@Service
public class ManifestService {
    private final ManifestRepository manifests;
    private final ManifestGuideRepository manifestGuides;
    private final DispatchRepository dispatches;
    private final GuideRepository guides;
    private final GuideStateMachine fsm;
    private final FolioService folios;
    private final ShiftRepository shifts;
    private final SenderRepository senders;
    private final CourierRepository couriers;
    private final UserRepository users;
    private final AssignmentService assignmentService;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;

    public ManifestService(ManifestRepository manifests, ManifestGuideRepository manifestGuides, DispatchRepository dispatches,
                           GuideRepository guides, GuideStateMachine fsm, FolioService folios, ShiftRepository shifts,
                           SenderRepository senders, CourierRepository couriers, UserRepository users,
                           AssignmentService assignmentService, AuditService audit, Clock clock, ZoneId zone) {
        this.manifests = manifests;
        this.manifestGuides = manifestGuides;
        this.dispatches = dispatches;
        this.guides = guides;
        this.fsm = fsm;
        this.folios = folios;
        this.shifts = shifts;
        this.senders = senders;
        this.couriers = couriers;
        this.users = users;
        this.assignmentService = assignmentService;
        this.audit = audit;
        this.clock = clock;
        this.zone = zone;
    }

    /** Guías confirmadas que todavía no pertenecen a ningún manifiesto. */
    @Transactional(readOnly = true)
    public PageResponse<GuideSummaryDto> eligible(Long shiftId, Long senderId, LocalDate from, LocalDate to, String q,
                                                  Integer page, Integer size) {
        GuideFilter f = new GuideFilter(q, List.of(GuideStatus.CONFIRMADA), senderId, shiftId, null, null, from, to, true, null);
        return PageResponse.of(guides.findAll(GuideSpecs.filter(f, zone, null),
                Paging.of(page, size == null ? 200 : size, Sort.by("confirmedAt", "id"))), GuideService::toSummary);
    }

    @Transactional(readOnly = true)
    public PageResponse<ManifestSummaryDto> search(Manifest.Status status, Long shiftId, Long senderId, LocalDate from, LocalDate to,
                                                   Integer page, Integer size) {
        LocalDate f = from == null ? LocalDate.of(2000, 1, 1) : from;
        LocalDate t = to == null ? LocalDate.of(2999, 12, 31) : to;
        return PageResponse.of(manifests.search(status, shiftId, senderId, f, t,
                        Paging.of(page, size, Sort.by(Sort.Direction.DESC, "operationDate", "id"))),
                m -> new ManifestSummaryDto(m.getId(), m.getNumber(), m.getStatus(), m.getOperationDate(), shiftRef(m.getShift()),
                        senderRef(m.getSender()), (int) manifestGuides.countByManifest(m.getId()), userRef(m.getCreatedBy()),
                        m.getCreatedAt(), m.getClosedAt(), m.getVersion()));
    }

    @Transactional(readOnly = true)
    public ManifestDto get(Long id) {
        return toDto(manifests.findDetailed(id).orElseThrow(() -> ApiException.notFound("Manifiesto")));
    }

    @Transactional
    public ManifestDto create(ManifestRequest req) {
        SalterPrincipal p = CurrentUser.get();
        Manifest m = new Manifest();
        m.setNumber(folios.next(FolioService.MANIFEST));
        m.setOperationDate(req.operationDate());
        m.setShift(resolveShift(req.shiftId()));
        m.setSender(resolveSender(req.senderId()));
        m.setNotes(trim(req.notes()));
        m.setCreatedBy(users.getReferenceById(p.userId()));
        manifests.saveAndFlush(m);
        replaceGuides(m, req.guideIds());
        audit.record("MANIFEST_CREATED", "MANIFEST", m.getId(), Map.of("number", m.getNumber(), "guides", req.guideIds().size()));
        manifests.flush();
        return get(m.getId());
    }

    @Transactional
    public ManifestDto update(Long id, ManifestRequest req) {
        Manifest m = lockDraft(id, req.version());
        m.setOperationDate(req.operationDate());
        m.setShift(resolveShift(req.shiftId()));
        m.setSender(resolveSender(req.senderId()));
        m.setNotes(trim(req.notes()));
        replaceGuides(m, req.guideIds());
        audit.record("MANIFEST_UPDATED", "MANIFEST", m.getId(), Map.of("guides", req.guideIds().size()));
        manifests.flush();
        return get(m.getId());
    }

    @Transactional
    public void deleteDraft(Long id, Long version) {
        Manifest m = lockDraft(id, version);
        audit.record("MANIFEST_DELETED", "MANIFEST", m.getId(), Map.of("number", m.getNumber()));
        manifests.delete(m);
    }

    @Transactional
    public ManifestDto close(Long id, Long version) {
        Manifest m = lockDraft(id, version);
        if (m.getGuides().isEmpty()) throw ApiException.business("EMPTY_MANIFEST", "El manifiesto no tiene guías.");
        List<Guide> locked = guides.lockAllById(guideIds(m));
        List<String> invalid = locked.stream().filter(g -> g.getStatus() != GuideStatus.CONFIRMADA).map(Guide::displayReference).toList();
        if (!invalid.isEmpty()) {
            throw ApiException.business("GUIDES_NOT_READY", "Estas guías ya no están confirmadas: " + String.join(", ", invalid) + ".")
                    .with("guides", invalid);
        }
        for (Guide g : locked) {
            fsm.transition(g, GuideStatus.LISTA_SALIDA, MovementType.MANIFIESTO, null, null, Map.of("manifest", m.getNumber()));
        }
        m.setStatus(Manifest.Status.CERRADO);
        m.setClosedAt(clock.instant());
        m.setClosedBy(users.getReferenceById(CurrentUser.get().userId()));
        audit.record("MANIFEST_CLOSED", "MANIFEST", m.getId(), Map.of("number", m.getNumber(), "guides", locked.size()));
        manifests.flush();
        return get(m.getId());
    }

    @Transactional
    public ManifestDto reopen(Long id, String reason, Long version) {
        Manifest m = manifests.lockById(id).orElseThrow(() -> ApiException.notFound("Manifiesto"));
        checkVersion(m, version);
        if (m.getStatus() != Manifest.Status.CERRADO) throw ApiException.business("NOT_CLOSED", "Sólo se puede reabrir un manifiesto cerrado y no despachado.");
        for (Guide g : guides.lockAllById(guideIds(m))) {
            if (g.getStatus() == GuideStatus.LISTA_SALIDA) {
                fsm.transition(g, GuideStatus.CONFIRMADA, MovementType.MANIFIESTO_REABIERTO, reason, null, Map.of("manifest", m.getNumber()));
            } else if (g.getStatus() == GuideStatus.RETENIDA && g.getStatusBeforeHold() == GuideStatus.LISTA_SALIDA) {
                g.setStatusBeforeHold(GuideStatus.CONFIRMADA);
            }
        }
        m.setStatus(Manifest.Status.BORRADOR);
        m.setClosedAt(null);
        m.setClosedBy(null);
        audit.record("MANIFEST_REOPENED", "MANIFEST", m.getId(), Map.of("number", m.getNumber(), "reason", reason));
        manifests.flush();
        return get(m.getId());
    }

    /**
     * Autoriza la salida: todas las guías pasan a "En tránsito" o ninguna (transacción única).
     * Una restricción única impide despachar dos veces el mismo manifiesto.
     */
    @Transactional
    public ManifestDto dispatch(Long id, DispatchRequest req) {
        CurrentUser.require(Permission.DISPATCH_AUTHORIZE);
        SalterPrincipal p = CurrentUser.get();
        Manifest m = manifests.lockById(id).orElseThrow(() -> ApiException.notFound("Manifiesto"));
        checkVersion(m, req.version());
        if (m.getStatus() == Manifest.Status.DESPACHADO || dispatches.existsByManifestId(m.getId())) {
            throw ApiException.conflict("ALREADY_DISPATCHED", "El manifiesto " + m.getNumber() + " ya fue despachado.");
        }
        if (m.getStatus() != Manifest.Status.CERRADO) {
            throw ApiException.business("NOT_CLOSED", "Cierra el manifiesto antes de autorizar la salida.");
        }
        Courier courier = null;
        if (req.courierId() != null) {
            courier = couriers.findById(req.courierId()).orElseThrow(() -> ApiException.badRequest("INVALID_COURIER", "Repartidor no válido."));
            if (!courier.isActive()) throw ApiException.business("COURIER_INACTIVE", "El repartidor está inactivo.");
        }
        List<Guide> locked = guides.lockAllById(guideIds(m));
        List<String> blocked = locked.stream().filter(g -> g.getStatus() != GuideStatus.LISTA_SALIDA)
                .map(g -> g.displayReference() + " (" + g.getStatus().getLabel() + ")").toList();
        if (!blocked.isEmpty()) {
            throw ApiException.business("GUIDES_BLOCKED", "No se puede despachar. Guías no disponibles: " + String.join(", ", blocked) + ".")
                    .with("guides", blocked);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("manifest", m.getNumber());
        if (courier != null) meta.put("courier", courier.getName());
        if (req.vehicle() != null && !req.vehicle().isBlank()) meta.put("vehicle", req.vehicle().trim());
        for (Guide g : locked) {
            fsm.transition(g, GuideStatus.EN_TRANSITO, MovementType.DESPACHO, req.notes(), null, meta);
        }
        Dispatch d = new Dispatch();
        d.setManifestId(m.getId());
        d.setDispatchedBy(p.userId());
        d.setDispatchedAt(clock.instant());
        d.setCourierId(courier == null ? null : courier.getId());
        d.setVehicle(trim(req.vehicle()));
        d.setNotes(trim(req.notes()));
        d.setGuideCount(locked.size());
        dispatches.saveAndFlush(d);
        m.setStatus(Manifest.Status.DESPACHADO);
        if (courier != null) assignmentService.assignLocked(locked, courier);
        Map<String, Object> ad = new LinkedHashMap<>();
        ad.put("number", m.getNumber());
        ad.put("guides", locked.stream().map(Guide::getFolio).toList());
        ad.put("courierId", courier == null ? null : courier.getId());
        audit.record("MANIFEST_DISPATCHED", "MANIFEST", m.getId(), ad);
        manifests.flush();
        return get(m.getId());
    }

    // ───────────────────────── Internos ─────────────────────────

    private Manifest lockDraft(Long id, Long version) {
        Manifest m = manifests.lockById(id).orElseThrow(() -> ApiException.notFound("Manifiesto"));
        checkVersion(m, version);
        if (m.getStatus() != Manifest.Status.BORRADOR) {
            throw ApiException.business("NOT_DRAFT", "El manifiesto " + m.getNumber() + " ya no está en borrador.");
        }
        return m;
    }

    private static void checkVersion(Manifest m, Long version) {
        if (version != null && version != m.getVersion()) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "El manifiesto fue modificado por otra persona. Recarga la información.");
        }
    }

    private void replaceGuides(Manifest m, List<Long> requestedIds) {
        Set<Long> ids = new LinkedHashSet<>(requestedIds);
        if (ids.size() != requestedIds.size()) throw ApiException.badRequest("DUPLICATE_GUIDES", "La lista contiene guías repetidas.");
        Set<Long> current = new HashSet<>(guideIds(m));
        List<Guide> locked = ids.isEmpty() ? List.of() : guides.lockAllById(ids);
        if (locked.size() != ids.size()) throw ApiException.badRequest("INVALID_GUIDES", "Alguna de las guías no existe.");
        Set<Long> inOther = new HashSet<>(manifestGuides.findAssignedGuideIds(ids));
        inOther.removeAll(current);
        List<String> problems = new ArrayList<>();
        Map<Long, Guide> byId = new LinkedHashMap<>();
        locked.forEach(g -> byId.put(g.getId(), g));
        for (Long gid : ids) {
            Guide g = byId.get(gid);
            if (g.getStatus() != GuideStatus.CONFIRMADA) problems.add(g.displayReference() + " no está confirmada");
            else if (inOther.contains(gid)) problems.add(g.getFolio() + " ya pertenece a otro manifiesto");
            else if (m.getSender() != null && !g.getSender().getId().equals(m.getSender().getId())) problems.add(g.getFolio() + " es de otro remitente");
        }
        if (!problems.isEmpty()) {
            throw ApiException.business("INVALID_GUIDES", "No se pueden incluir: " + String.join("; ", problems) + ".").with("problems", problems);
        }
        m.getGuides().removeIf(mg -> !ids.contains(mg.getGuide().getId()));
        manifests.flush();
        int pos = 1;
        Map<Long, ManifestGuide> existing = new LinkedHashMap<>();
        m.getGuides().forEach(mg -> existing.put(mg.getGuide().getId(), mg));
        for (Long gid : ids) {
            ManifestGuide mg = existing.get(gid);
            if (mg == null) m.getGuides().add(new ManifestGuide(m, byId.get(gid), pos));
            else mg.setPosition(pos);
            pos++;
        }
    }

    private static List<Long> guideIds(Manifest m) {
        return m.getGuides().stream().map(mg -> mg.getGuide().getId()).toList();
    }

    private Shift resolveShift(Long id) {
        return id == null ? null : shifts.findById(id).orElseThrow(() -> ApiException.badRequest("INVALID_SHIFT", "Turno no válido."));
    }

    private Sender resolveSender(Long id) {
        return id == null ? null : senders.findById(id).orElseThrow(() -> ApiException.badRequest("INVALID_SENDER", "Remitente no válido."));
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ManifestDto toDto(Manifest m) {
        SalterPrincipal p = CurrentUser.get();
        List<GuideSummaryDto> list = m.getGuides().stream().map(mg -> GuideService.toSummary(mg.getGuide())).toList();
        int packages = m.getGuides().stream().mapToInt(mg -> mg.getGuide().getPackageCount()).sum();
        DispatchDto dispatch = dispatches.findByManifestId(m.getId()).map(d -> new DispatchDto(d.getDispatchedAt(),
                users.findById(d.getDispatchedBy()).map(ManifestService::userRef).orElse(null), d.getCourierId(),
                d.getCourierId() == null ? null : couriers.findById(d.getCourierId()).map(Courier::getName).orElse(null),
                d.getVehicle(), d.getNotes(), d.getGuideCount())).orElse(null);
        List<String> actions = new ArrayList<>();
        if (p.has(Permission.MANIFESTS_MANAGE)) {
            if (m.getStatus() == Manifest.Status.BORRADOR) actions.addAll(List.of("EDIT", "CLOSE", "DELETE"));
            if (m.getStatus() == Manifest.Status.CERRADO) actions.add("REOPEN");
        }
        if (m.getStatus() == Manifest.Status.CERRADO && p.has(Permission.DISPATCH_AUTHORIZE)) actions.add("DISPATCH");
        actions.add("PDF");
        return new ManifestDto(m.getId(), m.getNumber(), m.getStatus(), m.getOperationDate(), shiftRef(m.getShift()),
                senderRef(m.getSender()), m.getNotes(), userRef(m.getCreatedBy()), m.getCreatedAt(), m.getClosedAt(),
                userRef(m.getClosedBy()), list.size(), packages, list, dispatch, actions, m.getVersion());
    }

    static Ref shiftRef(Shift s) {
        return s == null ? null : new Ref(s.getId(), s.getName());
    }

    static Ref senderRef(Sender s) {
        return s == null ? null : new Ref(s.getId(), s.getName());
    }

    static Ref userRef(User u) {
        return u == null ? null : new Ref(u.getId(), u.getFullName());
    }
}
