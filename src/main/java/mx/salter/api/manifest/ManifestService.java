package mx.salter.api.manifest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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
import mx.salter.api.common.Text;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideDtos.GuideFilter;
import mx.salter.api.guide.GuideDtos.GuideSummaryDto;
import mx.salter.api.guide.GuideItem;
import mx.salter.api.guide.GuideMovement;
import mx.salter.api.guide.GuideMovementRepository;
import mx.salter.api.guide.GuideRepository;
import mx.salter.api.guide.GuideService;
import mx.salter.api.guide.GuideSpecs;
import mx.salter.api.guide.GuideStateMachine;
import mx.salter.api.guide.GuideStatus;
import mx.salter.api.guide.MovementType;
import mx.salter.api.manifest.ManifestDtos.AuthorizationDto;
import mx.salter.api.manifest.ManifestDtos.BatchComparison;
import mx.salter.api.manifest.ManifestDtos.DispatchDto;
import mx.salter.api.manifest.ManifestDtos.DispatchRequest;
import mx.salter.api.manifest.ManifestDtos.EntryDto;
import mx.salter.api.manifest.ManifestDtos.ManifestDto;
import mx.salter.api.manifest.ManifestDtos.ManifestRequest;
import mx.salter.api.manifest.ManifestDtos.ManifestSummaryDto;
import mx.salter.api.manifest.ManifestDtos.ReviewDto;
import mx.salter.api.manifest.ManifestDtos.RouteChange;
import mx.salter.api.manifest.ManifestDtos.TypeTotal;
import mx.salter.api.operation.AssignmentService;
import mx.salter.api.operation.Courier;
import mx.salter.api.operation.CourierRepository;
import mx.salter.api.route.RouteService;
import mx.salter.api.route.ShippingRoute;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.Permission;
import mx.salter.api.security.SalterPrincipal;
import mx.salter.api.sender.Sender;
import mx.salter.api.sender.SenderRepository;
import mx.salter.api.user.User;
import mx.salter.api.user.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manifiestos organizados por RUTA DE EMBARQUE. Estados: BORRADOR (en preparación) → CERRADO (confirmado) →
 * [autorización del encargado] → DESPACHADO. Preparar, cerrar, autorizar y registrar el despacho son pasos distintos
 * con permisos distintos; el despacho exige la autorización previa. Sólo salen las guías marcadas «Viaja».
 */
@Service
public class ManifestService {
    private final ManifestRepository manifests;
    private final ManifestGuideRepository manifestGuides;
    private final DispatchRepository dispatches;
    private final GuideRepository guides;
    private final GuideMovementRepository movements;
    private final GuideStateMachine fsm;
    private final FolioService folios;
    private final ShiftRepository shifts;
    private final SenderRepository senders;
    private final CourierRepository couriers;
    private final UserRepository users;
    private final AssignmentService assignmentService;
    private final ManifestPlanner planner;
    private final RouteService routes;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;

    public ManifestService(ManifestRepository manifests, ManifestGuideRepository manifestGuides, DispatchRepository dispatches,
                           GuideRepository guides, GuideMovementRepository movements, GuideStateMachine fsm, FolioService folios,
                           ShiftRepository shifts, SenderRepository senders, CourierRepository couriers, UserRepository users,
                           AssignmentService assignmentService, ManifestPlanner planner, RouteService routes, AuditService audit,
                           NamedParameterJdbcTemplate jdbc, Clock clock, ZoneId zone) {
        this.manifests = manifests;
        this.manifestGuides = manifestGuides;
        this.dispatches = dispatches;
        this.guides = guides;
        this.movements = movements;
        this.fsm = fsm;
        this.folios = folios;
        this.shifts = shifts;
        this.senders = senders;
        this.couriers = couriers;
        this.users = users;
        this.assignmentService = assignmentService;
        this.planner = planner;
        this.routes = routes;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = zone;
    }

    /** Guías confirmadas que todavía no pertenecen a ningún manifiesto. */
    @Transactional(readOnly = true)
    public PageResponse<GuideSummaryDto> eligible(Long shiftId, Long senderId, LocalDate from, LocalDate to, String q,
                                                  Long shippingRouteId, Integer page, Integer size) {
        GuideFilter f = new GuideFilter(q, List.of(GuideStatus.CONFIRMADA), senderId, shiftId, null, null, from, to, true, null,
                null, shippingRouteId, null, null);
        return PageResponse.of(guides.findAll(GuideSpecs.filter(f, zone, null),
                Paging.of(page, size == null ? 200 : size, Sort.by("confirmedAt", "id"))), GuideService::toSummary);
    }

    @Transactional(readOnly = true)
    public PageResponse<ManifestSummaryDto> search(Manifest.Status status, Long shiftId, Long senderId, Long routeId, LocalDate from,
                                                   LocalDate to, Integer pageNumber, Integer size) {
        LocalDate f = from == null ? LocalDate.of(2000, 1, 1) : from;
        LocalDate t = to == null ? LocalDate.of(2999, 12, 31) : to;
        var page = manifests.search(status, shiftId, senderId, routeId, f, t,
                Paging.of(pageNumber, size, Sort.by(Sort.Direction.DESC, "operationDate", "id")));
        Map<Long, Long> counts = new HashMap<>();
        if (!page.isEmpty()) {
            manifestGuides.countByManifestIds(page.map(Manifest::getId).toList())
                    .forEach(r -> counts.put((Long) r[0], (Long) r[1]));
        }
        return PageResponse.of(page,
                m -> new ManifestSummaryDto(m.getId(), m.getNumber(), m.getStatus(), m.getOperationDate(), shiftRef(m.getShift()),
                        senderRef(m.getSender()), counts.getOrDefault(m.getId(), 0L).intValue(), userRef(m.getCreatedBy()),
                        m.getCreatedAt(), m.getClosedAt(), m.getVersion(), routeRef(m.getShippingRoute()), m.getAuthorizedAt() != null));
    }

    @Transactional(readOnly = true)
    public ManifestDto get(Long id) {
        return toDto(manifests.findDetailed(id).orElseThrow(() -> ApiException.notFound("Manifiesto")));
    }

    @Transactional
    public ManifestDto create(ManifestRequest req) {
        SalterPrincipal p = CurrentUser.get();
        ShippingRoute route = req.shippingRouteId() == null ? null : routes.requireActive(req.shippingRouteId());
        if (route != null) {
            manifests.findOpenByRoute(route.getId()).ifPresent(open -> {
                throw ApiException.conflict("OPEN_MANIFEST_EXISTS", "Ya hay un manifiesto en preparación para la ruta " + route.getCode()
                        + ": " + open.getNumber() + ". Agrega las guías a ese manifiesto.").with("manifestId", open.getId());
            });
        }
        Manifest m = new Manifest();
        m.setNumber(folios.next(FolioService.MANIFEST));
        m.setOperationDate(req.operationDate());
        m.setShift(resolveShift(req.shiftId()));
        m.setSender(resolveSender(req.senderId()));
        m.setShippingRoute(route);
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
        if (req.shippingRouteId() != null && (m.getShippingRoute() == null || !m.getShippingRoute().getId().equals(req.shippingRouteId()))) {
            ShippingRoute route = routes.requireActive(req.shippingRouteId());
            manifests.findOpenByRoute(route.getId()).filter(o -> !o.getId().equals(m.getId())).ifPresent(open -> {
                throw ApiException.conflict("OPEN_MANIFEST_EXISTS", "Ya hay un manifiesto en preparación para la ruta " + route.getCode() + ": " + open.getNumber() + ".");
            });
            m.setShippingRoute(route);
        }
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

    /** Viaja / No viaja de una guía del manifiesto (sólo en preparación). Queda en el historial con motivo y responsable. */
    @Transactional
    public ManifestDto setTravel(Long id, Long guideId, ManifestDtos.TravelRequest req) {
        SalterPrincipal p = CurrentUser.get();
        Manifest m = lockDraft(id, req.version());
        ManifestGuide mg = m.getGuides().stream().filter(x -> x.isActive() && x.getGuide().getId().equals(guideId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Guía en el manifiesto"));
        if (mg.isTravels() == req.travels()) return get(m.getId());
        String reason = Text.trimToNull(req.reason());
        if (!req.travels() && reason == null) throw ApiException.badRequest("REASON_REQUIRED", "Indica el motivo por el que la guía no viaja.");
        mg.setTravels(req.travels());
        mg.setTravelReason(reason);
        mg.setTravelChangedBy(p.userId());
        mg.setTravelChangedAt(clock.instant());
        manifests.flush();
        Guide g = guides.lockById(guideId).orElseThrow();
        fsm.record(g, req.travels() ? MovementType.VIAJA : MovementType.NO_VIAJA, reason, null, Map.of("manifest", m.getNumber()));
        audit.record(req.travels() ? "MANIFEST_GUIDE_TRAVELS" : "MANIFEST_GUIDE_NOT_TRAVELS", "MANIFEST", m.getId(),
                Map.of("guideId", guideId, "folio", String.valueOf(g.getFolio()), "reason", reason == null ? "" : reason));
        return get(m.getId());
    }

    @Transactional
    public ManifestDto close(Long id, Long version) {
        Manifest m = lockDraft(id, version);
        List<ManifestGuide> active = m.activeGuides();
        if (active.isEmpty()) throw ApiException.business("EMPTY_MANIFEST", "El manifiesto no tiene guías.");
        List<Long> travelIds = active.stream().filter(ManifestGuide::isTravels).map(mg -> mg.getGuide().getId()).toList();
        if (travelIds.isEmpty()) throw ApiException.business("NOTHING_TRAVELS", "Ninguna guía del manifiesto está marcada como «Viaja».");
        List<Guide> locked = guides.lockAllById(travelIds);
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
        audit.record("MANIFEST_CLOSED", "MANIFEST", m.getId(), Map.of("number", m.getNumber(), "guides", locked.size(),
                "notTravelling", active.size() - locked.size()));
        manifests.flush();
        return get(m.getId());
    }

    @Transactional
    public ManifestDto reopen(Long id, String reason, Long version) {
        Manifest m = manifests.lockById(id).orElseThrow(() -> ApiException.notFound("Manifiesto"));
        checkVersion(m, version);
        if (m.getStatus() != Manifest.Status.CERRADO) throw ApiException.business("NOT_CLOSED", "Sólo se puede reabrir un manifiesto cerrado y no despachado.");
        if (m.getShippingRoute() != null) {
            manifests.findOpenByRoute(m.getShippingRoute().getId()).ifPresent(open -> {
                throw ApiException.conflict("OPEN_MANIFEST_EXISTS", "No se puede reabrir: la ruta " + m.getShippingRoute().getCode()
                        + " ya tiene en preparación el manifiesto " + open.getNumber() + ".");
            });
        }
        for (Guide g : guides.lockAllById(guideIds(m))) {
            if (g.getStatus() == GuideStatus.LISTA_SALIDA) {
                fsm.transition(g, GuideStatus.CONFIRMADA, MovementType.MANIFIESTO_REABIERTO, reason, null, Map.of("manifest", m.getNumber()));
            } else if (g.getStatus() == GuideStatus.RETENIDA && g.getStatusBeforeHold() == GuideStatus.LISTA_SALIDA) {
                g.setStatusBeforeHold(GuideStatus.CONFIRMADA);
            }
        }
        boolean wasAuthorized = m.getAuthorizedAt() != null;
        m.setStatus(Manifest.Status.BORRADOR);
        m.setClosedAt(null);
        m.setClosedBy(null);
        // Cualquier cambio posterior invalida la autorización: debe autorizarse de nuevo.
        m.setAuthorizedAt(null);
        m.setAuthorizedBy(null);
        m.setAuthorizationNotes(null);
        audit.record("MANIFEST_REOPENED", "MANIFEST", m.getId(), Map.of("number", m.getNumber(), "reason", reason, "authorizationRevoked", wasAuthorized));
        manifests.flush();
        return get(m.getId());
    }

    /** Autorización de salida del encargado (no despacha). */
    @Transactional
    public ManifestDto authorize(Long id, ManifestDtos.AuthorizeRequest req) {
        CurrentUser.require(Permission.DISPATCH_AUTHORIZE);
        SalterPrincipal p = CurrentUser.get();
        Manifest m = manifests.lockById(id).orElseThrow(() -> ApiException.notFound("Manifiesto"));
        checkVersion(m, req.version());
        if (m.getStatus() != Manifest.Status.CERRADO) {
            throw ApiException.business("NOT_CLOSED", "El manifiesto debe estar cerrado (confirmado) para autorizar su salida.");
        }
        if (m.getAuthorizedAt() != null) throw ApiException.conflict("ALREADY_AUTHORIZED", "La salida del manifiesto " + m.getNumber() + " ya fue autorizada.");
        m.setAuthorizedAt(clock.instant());
        m.setAuthorizedBy(users.getReferenceById(p.userId()));
        m.setAuthorizationNotes(trim(req.notes()));
        Map<String, Object> ad = new LinkedHashMap<>();
        ad.put("number", m.getNumber());
        ad.put("notes", m.getAuthorizationNotes() == null ? "" : m.getAuthorizationNotes());
        audit.record("MANIFEST_AUTHORIZED", "MANIFEST", m.getId(), ad);
        manifests.flush();
        return get(m.getId());
    }

    /**
     * Registra el despacho: las guías que VIAJAN pasan a "En tránsito" (todas o ninguna, transacción única).
     * Las que no viajan quedan en el historial del manifiesto, conservan su estado y se incorporan al siguiente
     * manifiesto en preparación de su ruta. Una restricción única impide despachar dos veces el mismo manifiesto.
     */
    @Transactional
    public ManifestDto dispatch(Long id, DispatchRequest req) {
        CurrentUser.require(Permission.DISPATCH_REGISTER);
        SalterPrincipal p = CurrentUser.get();
        Manifest m = manifests.lockById(id).orElseThrow(() -> ApiException.notFound("Manifiesto"));
        checkVersion(m, req.version());
        if (m.getStatus() == Manifest.Status.DESPACHADO || dispatches.existsByManifestId(m.getId())) {
            throw ApiException.conflict("ALREADY_DISPATCHED", "El manifiesto " + m.getNumber() + " ya fue despachado.");
        }
        if (m.getStatus() != Manifest.Status.CERRADO) {
            throw ApiException.business("NOT_CLOSED", "Cierra el manifiesto antes de registrar la salida.");
        }
        if (m.getAuthorizedAt() == null) {
            throw ApiException.business("NOT_AUTHORIZED", "La salida del manifiesto " + m.getNumber() + " aún no está autorizada por el encargado.");
        }
        Courier courier = null;
        if (req.courierId() != null) {
            courier = couriers.findById(req.courierId()).orElseThrow(() -> ApiException.badRequest("INVALID_COURIER", "Repartidor no válido."));
            if (!courier.isActive()) throw ApiException.business("COURIER_INACTIVE", "El repartidor está inactivo.");
        }
        List<ManifestGuide> active = m.activeGuides();
        List<Long> travelIds = active.stream().filter(ManifestGuide::isTravels).map(mg -> mg.getGuide().getId()).toList();
        List<Guide> locked = travelIds.isEmpty() ? List.of() : guides.lockAllById(travelIds);
        List<String> blocked = locked.stream().filter(g -> g.getStatus() != GuideStatus.LISTA_SALIDA)
                .map(g -> g.displayReference() + " (" + g.getStatus().getLabel() + ")").toList();
        if (!blocked.isEmpty()) {
            throw ApiException.business("GUIDES_BLOCKED", "No se puede despachar. Guías no disponibles: " + String.join(", ", blocked) + ".")
                    .with("guides", blocked);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("manifest", m.getNumber());
        if (m.getShippingRoute() != null) meta.put("route", m.getShippingRoute().getCode());
        if (courier != null) meta.put("courier", courier.getName());
        if (req.vehicle() != null && !req.vehicle().isBlank()) meta.put("vehicle", req.vehicle().trim());
        for (Guide g : locked) {
            fsm.transition(g, GuideStatus.EN_TRANSITO, MovementType.DESPACHO, req.notes(), null, meta);
        }
        // Las que no viajan: historial en este manifiesto y libres para el siguiente de su ruta.
        List<ManifestGuide> notTravelling = active.stream().filter(mg -> !mg.isTravels()).toList();
        notTravelling.forEach(mg -> mg.setActive(false));
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
        manifests.flush();
        if (courier != null && !locked.isEmpty()) assignmentService.assignLocked(locked, courier);
        List<String> moved = new ArrayList<>();
        for (ManifestGuide mg : notTravelling) {
            Guide g = guides.lockById(mg.getGuide().getId()).orElseThrow();
            if (g.getStatus() == GuideStatus.CONFIRMADA) planner.attach(g).ifPresent(next -> moved.add(g.getFolio() + "→" + next.getNumber()));
        }
        Map<String, Object> ad = new LinkedHashMap<>();
        ad.put("number", m.getNumber());
        ad.put("guides", locked.stream().map(Guide::getFolio).toList());
        ad.put("notTravelling", notTravelling.stream().map(mg -> String.valueOf(mg.getGuide().getFolio())).toList());
        ad.put("movedToNext", moved);
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
            throw ApiException.business("NOT_DRAFT", "El manifiesto " + m.getNumber() + " ya no está en preparación.");
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
            if (current.contains(gid)) continue;
            if (g.getStatus() != GuideStatus.CONFIRMADA) problems.add(g.displayReference() + " no está confirmada");
            else if (inOther.contains(gid)) problems.add(g.getFolio() + " ya pertenece a otro manifiesto");
            else if (m.getSender() != null && !g.getSender().getId().equals(m.getSender().getId())) problems.add(g.getFolio() + " es de otro remitente");
            else if (m.getShippingRoute() != null && g.getShippingRoute() != null && !g.getShippingRoute().getId().equals(m.getShippingRoute().getId())) {
                problems.add(g.getFolio() + " es de la ruta " + g.getShippingRoute().getCode() + " (cámbiala de ruta primero)");
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.business("INVALID_GUIDES", "No se pueden incluir: " + String.join("; ", problems) + ".").with("problems", problems);
        }
        m.getGuides().removeIf(mg -> mg.isActive() && !ids.contains(mg.getGuide().getId()));
        manifests.flush();
        int pos = 1;
        Map<Long, ManifestGuide> existing = new LinkedHashMap<>();
        m.getGuides().stream().filter(ManifestGuide::isActive).forEach(mg -> existing.put(mg.getGuide().getId(), mg));
        for (Long gid : ids) {
            ManifestGuide mg = existing.get(gid);
            if (mg == null) m.getGuides().add(new ManifestGuide(m, byId.get(gid), pos));
            else mg.setPosition(pos);
            pos++;
        }
    }

    private static List<Long> guideIds(Manifest m) {
        return m.activeGuides().stream().map(mg -> mg.getGuide().getId()).toList();
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

    /** Totales de revisión: guías, pedidos, cajas, piezas y bultos por tipo; comparación con el lote; cambios de ruta. */
    ReviewDto review(Manifest m) {
        List<ManifestGuide> active = m.activeGuides();
        int travelling = 0, orders = 0, boxes = 0, pieces = 0, travellingPieces = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (ManifestGuide mg : active) {
            Guide g = mg.getGuide();
            orders += GuideService.orderNumbers(g.getOrderReference()).size();
            pieces += g.pieces();
            if (mg.isTravels()) {
                travelling++;
                travellingPieces += g.pieces();
            }
            for (GuideItem i : g.getItems()) {
                int q = i.getQuantity().intValue();
                byType.merge(i.getDescription(), q, Integer::sum);
                if ("caja".equalsIgnoreCase(i.getDescription().trim())) boxes += q;
            }
        }
        List<Long> ids = active.stream().map(mg -> mg.getGuide().getId()).toList();
        List<RouteChange> changes = new ArrayList<>();
        if (!ids.isEmpty()) {
            Map<Long, String> folioById = new HashMap<>();
            active.forEach(mg -> folioById.put(mg.getGuide().getId(), mg.getGuide().displayReference()));
            for (GuideMovement mv : movements.findByGuideIdInAndTypeOrderByOccurredAtAscIdAsc(ids, MovementType.CAMBIO_RUTA)) {
                Map<String, Object> md = mv.getMetadata() == null ? Map.of() : mv.getMetadata();
                changes.add(new RouteChange(mv.getGuideId(), folioById.get(mv.getGuideId()), String.valueOf(md.getOrDefault("rutaAnterior", "")),
                        String.valueOf(md.getOrDefault("rutaNueva", "")), mv.getUserName(), mv.getOccurredAt(), mv.getNotes()));
            }
        }
        List<BatchComparison> batches = batches(m.getId());
        List<String> warnings = new ArrayList<>();
        for (BatchComparison b : batches) {
            if (b.difference() != null && b.difference() != 0) {
                warnings.add("El archivo " + (b.load() != null ? "de la carga " + b.load() : b.fileName()) + " declara " + b.declaredPackages()
                        + " bultos y las guías generadas suman " + (b.declaredPackages() - b.difference()) + ".");
            }
            if (b.piecesWithoutManifest() > 0 || b.piecesInOtherManifests() > 0) {
                warnings.add("Del archivo " + (b.load() != null ? b.load() : b.fileName()) + ": " + b.piecesInOtherManifests()
                        + " piezas están en otros manifiestos y " + b.piecesWithoutManifest() + " sin manifiesto.");
            }
        }
        int notTravelling = active.size() - travelling;
        if (notTravelling > 0) warnings.add(notTravelling + " guía(s) marcadas «No viaja»: no se despacharán ni se contarán como despachadas.");
        return new ReviewDto(active.size(), travelling, notTravelling, orders, boxes, pieces, travellingPieces,
                byType.entrySet().stream().map(e -> new TypeTotal(e.getKey(), e.getValue())).toList(), batches, changes, warnings);
    }

    private List<BatchComparison> batches(Long manifestId) {
        return jdbc.query("""
                select b.id, b.file_name, b.load_code, b.declared_packages, count(g.id) guides,
                       coalesce(sum(g.package_count) filter (where mg.manifest_id = :mid), 0) in_manifest,
                       coalesce(sum(g.package_count) filter (where mg.manifest_id is not null and mg.manifest_id <> :mid), 0) other,
                       coalesce(sum(g.package_count) filter (where mg.manifest_id is null), 0) none
                from guide_import_batches b
                join guides g on g.import_batch_id = b.id and g.status <> 'CANCELADA'
                left join manifest_guides mg on mg.guide_id = g.id and mg.active
                where b.id in (select g2.import_batch_id from manifest_guides mg2 join guides g2 on g2.id = mg2.guide_id
                               where mg2.manifest_id = :mid and mg2.active and g2.import_batch_id is not null)
                group by b.id, b.file_name, b.load_code, b.declared_packages order by b.id""",
                new MapSqlParameterSource("mid", manifestId), (rs, i) -> {
                    Integer declared = (Integer) rs.getObject("declared_packages");
                    int in = rs.getInt("in_manifest"), other = rs.getInt("other"), none = rs.getInt("none");
                    return new BatchComparison(rs.getLong("id"), rs.getString("file_name"), rs.getString("load_code"), declared, in, other, none,
                            rs.getInt("guides"), declared == null ? null : declared - (in + other + none));
                });
    }

    private ManifestDto toDto(Manifest m) {
        SalterPrincipal p = CurrentUser.get();
        List<ManifestGuide> active = m.activeGuides();
        List<GuideSummaryDto> list = active.stream().map(mg -> GuideService.toSummary(mg.getGuide())).toList();
        List<GuideSummaryDto> history = m.getGuides().stream().filter(mg -> !mg.isActive()).map(mg -> GuideService.toSummary(mg.getGuide())).toList();
        int packages = active.stream().mapToInt(mg -> mg.getGuide().pieces()).sum();
        Map<Long, Ref> userRefs = new HashMap<>();
        List<EntryDto> entries = m.getGuides().stream().map(mg -> {
            Guide g = mg.getGuide();
            Map<String, Integer> types = new LinkedHashMap<>();
            g.getItems().forEach(i -> types.merge(i.getDescription(), i.getQuantity().intValue(), Integer::sum));
            Ref changedBy = mg.getTravelChangedBy() == null ? null
                    : userRefs.computeIfAbsent(mg.getTravelChangedBy(), uid -> users.findById(uid).map(ManifestService::userRef).orElse(null));
            return new EntryDto(g.getId(), g.getFolio(), mg.isTravels(), mg.isActive(), mg.getTravelReason(), changedBy, mg.getTravelChangedAt(),
                    GuideService.orderNumbers(g.getOrderReference()).size(), types);
        }).toList();
        DispatchDto dispatch = dispatches.findByManifestId(m.getId()).map(d -> new DispatchDto(d.getDispatchedAt(),
                users.findById(d.getDispatchedBy()).map(ManifestService::userRef).orElse(null), d.getCourierId(),
                d.getCourierId() == null ? null : couriers.findById(d.getCourierId()).map(Courier::getName).orElse(null),
                d.getVehicle(), d.getNotes(), d.getGuideCount())).orElse(null);
        List<String> actions = new ArrayList<>();
        if (p.has(Permission.MANIFESTS_MANAGE)) {
            if (m.getStatus() == Manifest.Status.BORRADOR) actions.addAll(List.of("EDIT", "CLOSE", "DELETE", "TRAVEL"));
            if (m.getStatus() == Manifest.Status.CERRADO) actions.add("REOPEN");
        }
        if (m.getStatus() == Manifest.Status.CERRADO && m.getAuthorizedAt() == null && p.has(Permission.DISPATCH_AUTHORIZE)) actions.add("AUTHORIZE");
        if (m.getStatus() == Manifest.Status.CERRADO && m.getAuthorizedAt() != null && p.has(Permission.DISPATCH_REGISTER)) actions.add("DISPATCH");
        actions.add("PDF");
        AuthorizationDto auth = m.getAuthorizedAt() == null ? null
                : new AuthorizationDto(m.getAuthorizedAt(), userRef(m.getAuthorizedBy()), m.getAuthorizationNotes());
        return new ManifestDto(m.getId(), m.getNumber(), m.getStatus(), m.getOperationDate(), shiftRef(m.getShift()),
                senderRef(m.getSender()), m.getNotes(), userRef(m.getCreatedBy()), m.getCreatedAt(), m.getClosedAt(),
                userRef(m.getClosedBy()), list.size(), packages, list, dispatch, actions, m.getVersion(),
                routeRef(m.getShippingRoute()), auth, entries, review(m), history);
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

    static Ref routeRef(ShippingRoute r) {
        return r == null ? null : new Ref(r.getId(), r.label());
    }
}
