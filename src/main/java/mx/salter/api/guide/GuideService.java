package mx.salter.api.guide;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import mx.salter.api.audit.AuditService;
import mx.salter.api.auth.AuthDtos.Ref;
import mx.salter.api.catalog.ConceptRepository;
import mx.salter.api.catalog.FolioService;
import mx.salter.api.catalog.MerchandiseConcept;
import mx.salter.api.catalog.SettingsService;
import mx.salter.api.catalog.Shift;
import mx.salter.api.catalog.ShiftRepository;
import mx.salter.api.common.Address;
import mx.salter.api.common.AddressDto;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PageResponse;
import mx.salter.api.common.Paging;
import mx.salter.api.common.Text;
import mx.salter.api.guide.GuideDtos.AssignmentDto;
import mx.salter.api.guide.GuideDtos.CorrectionRequest;
import mx.salter.api.guide.GuideDtos.CreateResult;
import mx.salter.api.guide.GuideDtos.DeliveryDto;
import mx.salter.api.guide.GuideDtos.GuideDto;
import mx.salter.api.guide.GuideDtos.GuideFilter;
import mx.salter.api.guide.GuideDtos.GuideRequest;
import mx.salter.api.guide.GuideDtos.GuideSummaryDto;
import mx.salter.api.guide.GuideDtos.ItemDto;
import mx.salter.api.guide.GuideDtos.ItemInput;
import mx.salter.api.guide.GuideDtos.ManifestRef;
import mx.salter.api.guide.GuideDtos.MovementDto;
import mx.salter.api.guide.GuideDtos.NewRecipientInput;
import mx.salter.api.guide.GuideDtos.PartyDto;
import mx.salter.api.guide.GuideDtos.ReceptionDto;
import mx.salter.api.guide.GuideDtos.ShippingDto;
import mx.salter.api.guide.GuideDtos.ShippingInput;
import mx.salter.api.sender.GuidePrefix;
import mx.salter.api.incident.IncidentRepository;
import mx.salter.api.manifest.ManifestGuideRepository;
import mx.salter.api.operation.AssignmentRepository;
import mx.salter.api.operation.DeliveryRepository;
import mx.salter.api.operation.ReceptionRepository;
import mx.salter.api.recipient.Recipient;
import mx.salter.api.recipient.RecipientDtos.RecipientDto;
import mx.salter.api.recipient.RecipientDtos.RecipientRequest;
import mx.salter.api.recipient.RecipientRepository;
import mx.salter.api.recipient.RecipientService;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.Permission;
import mx.salter.api.security.SalterPrincipal;
import mx.salter.api.sender.Sender;
import mx.salter.api.sender.SenderService;
import mx.salter.api.user.User;
import mx.salter.api.user.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuideService {
    private final GuideRepository guides;
    private final GuideMovementRepository movements;
    private final GuideStateMachine fsm;
    private final GuideAccess access;
    private final FolioService folios;
    private final SenderService senders;
    private final RecipientService recipientService;
    private final RecipientRepository recipients;
    private final ShiftRepository shifts;
    private final ConceptRepository concepts;
    private final UserRepository users;
    private final ManifestGuideRepository manifestGuides;
    private final AssignmentRepository assignments;
    private final ReceptionRepository receptions;
    private final DeliveryRepository deliveries;
    private final IncidentRepository incidents;
    private final SettingsService settings;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;

    public GuideService(GuideRepository guides, GuideMovementRepository movements, GuideStateMachine fsm, GuideAccess access,
                        FolioService folios, SenderService senders, RecipientService recipientService, RecipientRepository recipients,
                        ShiftRepository shifts, ConceptRepository concepts, UserRepository users,
                        ManifestGuideRepository manifestGuides, AssignmentRepository assignments, ReceptionRepository receptions,
                        DeliveryRepository deliveries, IncidentRepository incidents, SettingsService settings, AuditService audit,
                        Clock clock, ZoneId zone) {
        this.guides = guides;
        this.movements = movements;
        this.fsm = fsm;
        this.access = access;
        this.folios = folios;
        this.senders = senders;
        this.recipientService = recipientService;
        this.recipients = recipients;
        this.shifts = shifts;
        this.concepts = concepts;
        this.users = users;
        this.manifestGuides = manifestGuides;
        this.assignments = assignments;
        this.receptions = receptions;
        this.deliveries = deliveries;
        this.incidents = incidents;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
        this.zone = zone;
    }

    // ───────────────────────── Consulta ─────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<GuideSummaryDto> search(GuideFilter f, Integer page, Integer size) {
        SalterPrincipal p = CurrentUser.get();
        var spec = access.scope(p).and(GuideSpecs.filter(f, zone, p));
        return PageResponse.of(guides.findAll(spec, Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"))),
                GuideService::toSummary);
    }

    @Transactional(readOnly = true)
    public GuideDto get(Long id) {
        Guide g = guides.findDetailed(id).orElseThrow(() -> ApiException.notFound("Guía"));
        access.requireView(CurrentUser.get(), g);
        return toDto(g);
    }

    @Transactional(readOnly = true)
    public GuideDto getByFolio(String folio) {
        Guide g = guides.findByFolioIgnoreCase(normalizeFolio(folio)).orElseThrow(() -> ApiException.notFound("Guía"));
        access.requireView(CurrentUser.get(), g);
        return toDto(g);
    }

    /**
     * Normaliza un folio capturado o leído con escáner: el código PDF417 de la guía trae "FOLIO|ORIGEN|DESTINO|…"
     * y en el documento se imprime con espacio ("ATM 000000001").
     */
    public static String normalizeFolio(String raw) {
        if (raw == null) return "";
        String f = raw.trim();
        int bar = f.indexOf('|');
        if (bar >= 0) f = f.substring(0, bar);
        f = f.replaceAll("\\s+", "").toUpperCase();
        // ATM-000000001 → ATM000000001 (sólo en el formato de siglas + número largo)
        if (f.matches("^[A-Z]{2,6}-\\d{7,}$")) f = f.replace("-", "");
        return f;
    }

    /** Guía visible para el usuario actual, con bloqueo de escritura para operar sobre ella. */
    @Transactional
    public Guide lockAccessible(Long id) {
        Guide g = guides.lockById(id).orElseThrow(() -> ApiException.notFound("Guía"));
        access.requireView(CurrentUser.get(), g);
        return g;
    }

    @Transactional(readOnly = true)
    public List<MovementDto> movements(Long id) {
        Guide g = guides.findById(id).orElseThrow(() -> ApiException.notFound("Guía"));
        access.requireView(CurrentUser.get(), g);
        return movements.findByGuideIdOrderByOccurredAtAscIdAsc(id).stream().map(MovementDto::of).toList();
    }

    // ───────────────────────── Elaboración ─────────────────────────

    @Transactional
    public CreateResult create(GuideRequest req) {
        SalterPrincipal p = CurrentUser.get();
        if (req.clientRequestId() != null) {
            var existing = guides.findByCreatedByIdAndClientRequestId(p.userId(), req.clientRequestId());
            if (existing.isPresent()) return new CreateResult(toDto(existing.get()), false);
        }
        if (req.confirm() && !p.has(Permission.GUIDES_CONFIRM)) {
            throw ApiException.forbidden("No tienes permiso para confirmar guías. Guárdala como borrador.");
        }
        Sender s = senders.requireAccessible(req.senderId());
        User creator = users.getReferenceById(p.userId());
        Guide g = new Guide();
        g.setClientRequestId(req.clientRequestId());
        g.setCreatedBy(creator);
        g.setSender(s);
        snapshotSender(g, s);
        g.setShift(resolveShift(req.shiftId()));
        applyRecipient(g, s, req.recipientId(), req.newRecipient());
        applyDetails(g, req.cdReference(), req.orderReference(), req.packageCount(), req.items(), req.notes());
        applyShipping(g, s, req.shipping(), false);
        guides.saveAndFlush(g);
        fsm.record(g, MovementType.CREACION, null, null, null);
        audit.record("GUIDE_CREATED", "GUIDE", g.getId(), Map.of("senderId", s.getId(), "oneTimeRecipient", g.isRcptOneTime()));
        if (req.confirm()) doConfirm(g);
        return new CreateResult(toDto(g), true);
    }

    @Transactional
    public GuideDto updateDraft(Long id, GuideRequest req) {
        SalterPrincipal p = CurrentUser.get();
        Guide g = lockAccessible(id);
        if (g.getStatus() != GuideStatus.BORRADOR) {
            throw ApiException.business("NOT_DRAFT", "Sólo se pueden editar guías en borrador. Para guías confirmadas solicita una corrección.");
        }
        if (!g.getCreatedBy().getId().equals(p.userId()) && !p.has(Permission.GUIDES_VIEW_ALL)) {
            throw ApiException.forbidden("Sólo puedes editar tus propios borradores.");
        }
        checkVersion(g, req.version());
        if (req.confirm() && !p.has(Permission.GUIDES_CONFIRM)) {
            throw ApiException.forbidden("No tienes permiso para confirmar guías.");
        }
        Sender s = req.senderId() == null || req.senderId().equals(g.getSender().getId())
                ? g.getSender() : senders.requireAccessible(req.senderId());
        g.setSender(s);
        snapshotSender(g, s);
        if (req.shiftId() != null) g.setShift(resolveShift(req.shiftId()));
        applyRecipient(g, s, req.recipientId(), req.newRecipient());
        applyDetails(g, req.cdReference(), req.orderReference(), req.packageCount(), req.items(), req.notes());
        applyShipping(g, s, req.shipping(), false);
        fsm.record(g, MovementType.EDICION, null, null, null);
        if (req.confirm()) doConfirm(g);
        guides.flush();
        return toDto(g);
    }

    @Transactional
    public GuideDto confirm(Long id, Long version) {
        SalterPrincipal p = CurrentUser.get();
        CurrentUser.require(Permission.GUIDES_CONFIRM);
        Guide g = lockAccessible(id);
        checkVersion(g, version);
        if (g.getStatus() == GuideStatus.CONFIRMADA) {
            throw ApiException.conflict("ALREADY_CONFIRMED", "La guía ya fue confirmada con el folio " + g.getFolio() + ".");
        }
        if (!p.has(Permission.GUIDES_VIEW_ALL) && !g.getCreatedBy().getId().equals(p.userId())) {
            throw ApiException.forbidden("Sólo puedes confirmar tus propias guías.");
        }
        doConfirm(g);
        guides.flush();
        return toDto(g);
    }

    private void doConfirm(Guide g) {
        validateForConfirmation(g);
        if (!g.getStatus().canTransitionTo(GuideStatus.CONFIRMADA)) {
            throw ApiException.business("INVALID_TRANSITION", "La guía no se puede confirmar en su estado actual.");
        }
        // El folio se toma en la misma transacción: si algo falla después, se revierte y el número no se consume.
        int padding = folioDigits();
        String folio = folios.nextGuide(GuidePrefix.of(g.getSender()), padding);
        fsm.transition(g, GuideStatus.CONFIRMADA, MovementType.CONFIRMACION, null, null, null);
        g.setFolio(folio);
        g.setConfirmedAt(clock.instant());
        if (g.getCommitmentDate() == null) {
            g.setCommitmentDate(LocalDate.now(clock.withZone(zone)).plusDays(serviceDays().getOrDefault(g.getServiceType(), 1)));
        }
        g.setConfirmedBy(users.getReferenceById(CurrentUser.get().userId()));
        audit.record("GUIDE_CONFIRMED", "GUIDE", g.getId(), Map.of("folio", g.getFolio()));
    }

    @Transactional
    public GuideDto cancel(Long id, String reason) {
        SalterPrincipal p = CurrentUser.get();
        Guide g = lockAccessible(id);
        boolean ownDraft = g.getStatus() == GuideStatus.BORRADOR && g.getCreatedBy().getId().equals(p.userId());
        if (!ownDraft && !p.has(Permission.GUIDES_CANCEL)) {
            throw ApiException.forbidden("No tienes permiso para cancelar esta guía.");
        }
        manifestGuides.findByGuideId(g.getId()).ifPresent(mg -> {
            throw ApiException.business("IN_MANIFEST", "La guía pertenece al manifiesto " + mg.getManifest().getNumber()
                    + ". Retírala del manifiesto antes de cancelarla.");
        });
        GuideStatus from = g.getStatus();
        fsm.transition(g, GuideStatus.CANCELADA, MovementType.CANCELACION, reason, null, null);
        g.setCancelledAt(clock.instant());
        g.setCancelledBy(users.getReferenceById(p.userId()));
        g.setCancelReason(reason.trim());
        assignments.findByGuideIdAndActiveTrue(g.getId()).ifPresent(a -> {
            a.setActive(false);
            a.setReleasedAt(clock.instant());
            a.setReleasedBy(p.userId());
        });
        audit.record("GUIDE_CANCELLED", "GUIDE", g.getId(), Map.of("from", from.name(), "reason", reason.trim()));
        guides.flush();
        return toDto(g);
    }

    /**
     * Corrección auditada de una guía confirmada que aún no está en un manifiesto cerrado.
     * Se registra el valor anterior y el nuevo en el historial; el folio no cambia.
     */
    @Transactional
    public GuideDto correct(Long id, CorrectionRequest req) {
        CurrentUser.require(Permission.GUIDES_CORRECT);
        Guide g = lockAccessible(id);
        checkVersion(g, req.version());
        if (g.getStatus() != GuideStatus.CONFIRMADA) {
            throw ApiException.business("NOT_CORRECTABLE", "Sólo se pueden corregir guías confirmadas que aún no están listas para salida.");
        }
        Map<String, Object> before = snapshotForAudit(g);
        if (req.recipient() != null) {
            NewRecipientInput r = req.recipient();
            g.setRcptName(Text.join(r.firstName(), r.lastName()));
            g.setRcptPhone(Text.trimToNull(r.phone()));
            g.setRcptEmail(Text.trimToNull(r.email()));
            applyRcptAddress(g, r.address() == null ? new Address() : r.address().toEntity());
        }
        applyDetails(g,
                req.cdReference() != null ? req.cdReference() : g.getCdReference(),
                req.orderReference() != null ? req.orderReference() : g.getOrderReference(),
                req.packageCount() != null ? req.packageCount() : g.getPackageCount(),
                req.items() != null && !req.items().isEmpty() ? req.items() : null,
                req.notes() != null ? req.notes() : g.getNotes());
        if (req.shipping() != null) applyShipping(g, g.getSender(), req.shipping(), true);
        Map<String, Object> after = snapshotForAudit(g);
        Map<String, Object> diff = new LinkedHashMap<>();
        for (String k : after.keySet()) {
            if (!Objects.equals(before.get(k), after.get(k))) diff.put(k, Map.of("antes", String.valueOf(before.get(k)), "despues", String.valueOf(after.get(k))));
        }
        if (diff.isEmpty()) throw ApiException.business("NO_CHANGES", "La corrección no contiene cambios.");
        fsm.record(g, MovementType.CORRECCION, req.reason(), null, diff);
        audit.record("GUIDE_CORRECTED", "GUIDE", g.getId(), Map.of("reason", req.reason(), "changes", diff));
        guides.flush();
        return toDto(g);
    }

    @Transactional
    public GuideDto hold(Long id, String reason) {
        CurrentUser.require(Permission.GUIDES_HOLD);
        Guide g = lockAccessible(id);
        GuideStatus from = g.getStatus();
        fsm.transition(g, GuideStatus.RETENIDA, MovementType.RETENCION, reason, null, null);
        g.setStatusBeforeHold(from);
        audit.record("GUIDE_HELD", "GUIDE", g.getId(), Map.of("from", from.name(), "reason", reason));
        guides.flush();
        return toDto(g);
    }

    @Transactional
    public GuideDto release(Long id, String notes) {
        CurrentUser.require(Permission.GUIDES_HOLD);
        Guide g = lockAccessible(id);
        if (g.getStatus() != GuideStatus.RETENIDA) throw ApiException.business("NOT_HELD", "La guía no está retenida.");
        GuideStatus back = g.getStatusBeforeHold() == null ? GuideStatus.CONFIRMADA : g.getStatusBeforeHold();
        fsm.transition(g, back, MovementType.LIBERACION, notes, null, null);
        g.setStatusBeforeHold(null);
        audit.record("GUIDE_RELEASED", "GUIDE", g.getId(), Map.of("to", back.name()));
        guides.flush();
        return toDto(g);
    }

    @Transactional
    public GuideDto addNote(Long id, String notes, String location) {
        SalterPrincipal p = CurrentUser.get();
        Guide g = lockAccessible(id);
        boolean allowed = p.hasAny(Permission.GUIDES_VIEW_ALL, Permission.DELIVERIES_ANY, Permission.RECEPTION_CONFIRM)
                || access.isAssignedCourier(p, g)
                || (p.has(Permission.GUIDES_VIEW_OWN) && g.getCreatedBy().getId().equals(p.userId()));
        if (!allowed || g.getStatus().isTerminal()) {
            throw ApiException.forbidden("No puedes registrar observaciones en esta guía.");
        }
        fsm.record(g, MovementType.NOTA, notes, location, null);
        return toDto(g);
    }

    // ───────────────────────── Reglas internas ─────────────────────────

    private void checkVersion(Guide g, Long version) {
        if (version != null && version != g.getVersion()) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "La guía fue modificada por otra persona. Recarga la información.");
        }
    }

    private Shift resolveShift(Long shiftId) {
        if (shiftId != null) {
            Shift s = shifts.findById(shiftId).orElseThrow(() -> ApiException.badRequest("INVALID_SHIFT", "Turno no válido."));
            if (!s.isActive()) throw ApiException.badRequest("INVALID_SHIFT", "El turno está inactivo.");
            return s;
        }
        LocalTime now = LocalTime.now(clock.withZone(zone));
        return shifts.findAllByOrderByStartTimeAsc().stream().filter(s -> s.isActive() && s.contains(now)).findFirst().orElse(null);
    }

    private static void snapshotSender(Guide g, Sender s) {
        g.setSenderName(s.getName());
        g.setSenderContact(s.getContactName());
        g.setSenderPhone(s.getPhone());
        Address a = s.getAddress() == null ? new Address() : s.getAddress();
        g.setSenderStreet(a.getStreet());
        g.setSenderNeighborhood(a.getNeighborhood());
        g.setSenderCity(a.getCity());
        g.setSenderState(a.getState());
        g.setSenderZip(a.getZip());
    }

    private void applyRecipient(Guide g, Sender s, Long recipientId, NewRecipientInput nr) {
        if (recipientId != null && nr != null) {
            throw ApiException.badRequest("RECIPIENT_AMBIGUOUS", "Selecciona un destinatario existente o captura uno nuevo, no ambos.");
        }
        if (recipientId != null) {
            Recipient r = recipientService.requireAccessible(recipientId);
            if (!r.getSender().getId().equals(s.getId())) {
                throw ApiException.business("RECIPIENT_SENDER_MISMATCH", "El destinatario no pertenece al remitente de la guía.");
            }
            if (!r.isActive()) throw ApiException.business("RECIPIENT_INACTIVE", "El destinatario está dado de baja.");
            g.setRecipient(r);
            g.setRcptOneTime(false);
            g.setRcptName(r.fullName());
            g.setRcptPhone(r.getPhone());
            g.setRcptEmail(r.getEmail());
            applyRcptAddress(g, r.getAddress() == null ? new Address() : r.getAddress());
            return;
        }
        if (nr == null) throw ApiException.badRequest("RECIPIENT_REQUIRED", "Indica el destinatario.");
        if (nr.saveToCatalog()) {
            if (!CurrentUser.get().has(Permission.RECIPIENTS_MANAGE)) {
                throw ApiException.forbidden("No tienes permiso para guardar destinatarios en el catálogo.");
            }
            RecipientDto created = recipientService.create(new RecipientRequest(s.getId(), nr.firstName(), nr.lastName(), nr.phone(),
                    nr.email(), nr.address(), null, nr.allowProbableDuplicate(), null));
            g.setRecipient(recipients.getReferenceById(created.id()));
            g.setRcptOneTime(false);
        } else {
            g.setRecipient(null);
            g.setRcptOneTime(true);
        }
        g.setRcptName(Text.join(nr.firstName(), nr.lastName()));
        g.setRcptPhone(Text.trimToNull(nr.phone()));
        g.setRcptEmail(Text.trimToNull(nr.email()));
        applyRcptAddress(g, nr.address() == null ? new Address() : nr.address().toEntity());
    }

    private static void applyRcptAddress(Guide g, Address a) {
        g.setRcptStreet(a.getStreet());
        g.setRcptNeighborhood(a.getNeighborhood());
        g.setRcptCity(a.getCity());
        g.setRcptState(a.getState());
        g.setRcptZip(a.getZip());
        g.setRcptRefs(a.getReferences());
    }

    private void applyDetails(Guide g, String cd, String order, Integer packageCount, List<ItemInput> items, String notes) {
        g.setCdReference(Text.trimToNull(cd));
        g.setOrderReference(Text.trimToNull(order));
        g.setPackageCount(packageCount == null ? 1 : packageCount);
        g.setNotes(Text.trimToNull(notes));
        if (items != null) {
            List<GuideItem> list = new ArrayList<>();
            for (ItemInput in : items) {
                GuideItem it = new GuideItem();
                String desc = Text.trimToNull(in.description());
                String unit = Text.trimToNull(in.unit());
                if (in.conceptId() != null) {
                    MerchandiseConcept c = concepts.findById(in.conceptId())
                            .orElseThrow(() -> ApiException.badRequest("INVALID_CONCEPT", "Concepto de mercancía no válido."));
                    it.setConceptId(c.getId());
                    if (desc == null) desc = c.getName();
                    if (unit == null) unit = c.getUnit();
                }
                if (desc == null) throw ApiException.badRequest("ITEM_DESCRIPTION", "Describe cada concepto de mercancía.");
                it.setDescription(desc);
                it.setUnit(unit == null ? "PIEZA" : unit.toUpperCase());
                it.setQuantity(in.quantity());
                it.setWeightKg(in.weightKg());
                list.add(it);
            }
            g.replaceItems(list);
        }
    }

    /** Tipos de servicio configurados (guide.services = "ORDINARIO:1,EXPRESS:0") con sus días para la fecha compromiso. */
    public Map<String, Integer> serviceDays() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String part : settings.get("guide.services", "ORDINARIO:1").split(",")) {
            String[] kv = part.trim().split(":");
            if (kv[0].isBlank()) continue;
            int days = 1;
            if (kv.length > 1) {
                try { days = Integer.parseInt(kv[1].trim()); } catch (NumberFormatException ignored) { }
            }
            m.put(kv[0].trim().toUpperCase(), Math.max(0, days));
        }
        if (m.isEmpty()) m.put("ORDINARIO", 1);
        return m;
    }

    private void applyShipping(Guide g, Sender s, ShippingInput in, boolean merge) {
        if (in == null) {
            if (merge) return;
            in = new ShippingInput(null, null, null, null, null);
        }
        String origin = upper(in.originCode());
        String dest = upper(in.destinationCode());
        String dims = Text.trimToNull(in.dimensions());
        String service = upper(in.serviceType());
        if (merge) {
            if (origin == null) origin = g.getOriginCode();
            if (dest == null) dest = g.getDestinationCode();
            if (dims == null) dims = g.getDimensions();
            if (service == null) service = g.getServiceType();
        }
        if (origin == null) origin = s.getPlazaCode();
        Map<String, Integer> services = serviceDays();
        if (service == null) service = services.keySet().iterator().next();
        if (!services.containsKey(service)) {
            throw ApiException.badRequest("INVALID_SERVICE", "Tipo de servicio no válido. Opciones: " + String.join(", ", services.keySet()) + ".");
        }
        g.setOriginCode(origin);
        g.setDestinationCode(dest);
        g.setDimensions(dims == null ? null : dims.toUpperCase());
        g.setServiceType(service);
        if (in.commitmentDate() != null || !merge) g.setCommitmentDate(in.commitmentDate());
    }

    private int folioDigits() {
        try {
            return Math.max(4, Math.min(12, Integer.parseInt(settings.get("guide.folioDigits", "9").trim())));
        } catch (NumberFormatException e) {
            return 9;
        }
    }

    private static String upper(String v) {
        String t = Text.trimToNull(v);
        return t == null ? null : t.toUpperCase();
    }

    private void validateForConfirmation(Guide g) {
        List<String> missing = new ArrayList<>();
        if (g.getRcptName() == null || g.getRcptName().isBlank()) missing.add("nombre del destinatario");
        if (g.getItems().isEmpty()) missing.add("conceptos de mercancía");
        for (String field : settings.get("guide.requiredFields", "").split(",")) {
            String f = field.trim();
            if (f.isEmpty()) continue;
            String value = switch (f) {
                case "rcptPhone" -> g.getRcptPhone();
                case "rcptStreet" -> g.getRcptStreet();
                case "rcptNeighborhood" -> g.getRcptNeighborhood();
                case "rcptCity" -> g.getRcptCity();
                case "rcptState" -> g.getRcptState();
                case "rcptZip" -> g.getRcptZip();
                case "cdReference" -> g.getCdReference();
                case "orderReference" -> g.getOrderReference();
                case "originCode" -> g.getOriginCode();
                case "destinationCode" -> g.getDestinationCode();
                case "dimensions" -> g.getDimensions();
                case "totalWeight" -> g.getTotalWeightKg() == null ? null : g.getTotalWeightKg().toPlainString();
                default -> "n/a";
            };
            if (value == null || value.isBlank()) missing.add(FIELD_LABELS.getOrDefault(f, f));
        }
        if (!missing.isEmpty()) {
            throw ApiException.business("INCOMPLETE_GUIDE", "Para confirmar falta: " + String.join(", ", missing) + ".")
                    .with("missing", missing);
        }
    }

    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("rcptPhone", "teléfono del destinatario"),
            Map.entry("rcptStreet", "calle del destinatario"),
            Map.entry("rcptNeighborhood", "colonia del destinatario"),
            Map.entry("rcptCity", "ciudad del destinatario"),
            Map.entry("rcptState", "estado del destinatario"),
            Map.entry("rcptZip", "código postal del destinatario"),
            Map.entry("cdReference", "referencia CD"),
            Map.entry("orderReference", "referencia de pedido"),
            Map.entry("originCode", "plaza de origen"),
            Map.entry("destinationCode", "plaza de destino"),
            Map.entry("dimensions", "dimensiones"),
            Map.entry("totalWeight", "peso"));

    private static Map<String, Object> snapshotForAudit(Guide g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("destinatario", g.getRcptName());
        m.put("telefono", g.getRcptPhone());
        m.put("calle", g.getRcptStreet());
        m.put("colonia", g.getRcptNeighborhood());
        m.put("ciudad", g.getRcptCity());
        m.put("estado", g.getRcptState());
        m.put("cp", g.getRcptZip());
        m.put("referenciaCD", g.getCdReference());
        m.put("pedido", g.getOrderReference());
        m.put("bultos", g.getPackageCount());
        m.put("origen", g.getOriginCode());
        m.put("destino", g.getDestinationCode());
        m.put("dimensiones", g.getDimensions());
        m.put("servicio", g.getServiceType());
        m.put("fechaCompromiso", g.getCommitmentDate());
        m.put("notas", g.getNotes());
        m.put("conceptos", g.getItems().stream().map(i -> i.getQuantity().stripTrailingZeros().toPlainString() + " " + i.getUnit() + " " + i.getDescription()).toList());
        return m;
    }

    // ───────────────────────── Mapeo ─────────────────────────

    public static GuideSummaryDto toSummary(Guide g) {
        return new GuideSummaryDto(g.getId(), g.getFolio(), g.displayReference(), g.getStatus(), g.getStatus().getLabel(),
                g.getSender().getId(), g.getSenderName(), g.getRecipient() == null ? null : g.getRecipient().getId(),
                g.getRcptName(), g.getRcptPhone(), g.getRcptCity(), g.getCdReference(), g.getOrderReference(), g.getPackageCount(),
                g.getOriginCode(), g.getDestinationCode(), g.getShift() == null ? null : new Ref(g.getShift().getId(), g.getShift().getName()),
                new Ref(g.getCreatedBy().getId(), g.getCreatedBy().getFullName()),
                g.getCreatedAt(), g.getConfirmedAt(), g.getVersion());
    }

    public GuideDto toDto(Guide g) {
        SalterPrincipal p = CurrentUser.get();
        var mg = manifestGuides.findByGuideId(g.getId()).orElse(null);
        var asg = assignments.findByGuideIdAndActiveTrue(g.getId()).orElse(null);
        var rec = receptions.findByGuideId(g.getId()).orElse(null);
        var del = deliveries.findByGuideId(g.getId()).orElse(null);
        var inc = incidents.findOpenByGuide(g.getId()).orElse(null);
        boolean courierView = !p.hasAny(Permission.GUIDES_VIEW_ALL) && access.isAssignedCourier(p, g);
        return new GuideDto(g.getId(), g.getFolio(), g.displayReference(), g.getStatus(), g.getStatus().getLabel(),
                g.getSender().getId(),
                new PartyDto(g.getSenderName(), g.getSenderContact(), g.getSenderPhone(), null,
                        new AddressDto(g.getSenderStreet(), g.getSenderNeighborhood(), g.getSenderCity(), g.getSenderState(), g.getSenderZip(), null)),
                g.getRecipient() == null ? null : g.getRecipient().getId(), g.isRcptOneTime(),
                new PartyDto(g.getRcptName(), null, g.getRcptPhone(), g.getRcptEmail(),
                        new AddressDto(g.getRcptStreet(), g.getRcptNeighborhood(), g.getRcptCity(), g.getRcptState(), g.getRcptZip(), g.getRcptRefs())),
                g.getShift() == null ? null : new Ref(g.getShift().getId(), g.getShift().getName()),
                g.getCdReference(), g.getOrderReference(), g.getPackageCount(), g.getTotalWeightKg(),
                new ShippingDto(g.getOriginCode(), g.getDestinationCode(), g.getDimensions(), g.getServiceType(), g.getCommitmentDate()),
                g.getNotes(),
                g.getItems().stream().map(i -> new ItemDto(i.getId(), i.getPosition(), i.getConceptId(), i.getDescription(),
                        i.getQuantity(), i.getUnit(), i.getWeightKg())).toList(),
                ref(g.getCreatedBy()), g.getCreatedAt(), g.getUpdatedAt(), g.getConfirmedAt(), ref(g.getConfirmedBy()),
                g.getCancelledAt(), ref(g.getCancelledBy()), g.getCancelReason(),
                mg == null ? null : new ManifestRef(mg.getManifest().getId(), mg.getManifest().getNumber(), mg.getManifest().getStatus().name()),
                asg == null ? null : new AssignmentDto(asg.getCourier().getId(), asg.getCourier().getName(), asg.getCourier().getPhone(),
                        asg.getAssignedAt(), asg.getRouteOrder()),
                rec == null ? null : new ReceptionDto(rec.getReceivedAt(), rec.getReceivedBy(), rec.getLocation(), rec.getReceivedCount(),
                        rec.isHasDifferences(), rec.getDifferences(), rec.getNotes()),
                del == null ? null : new DeliveryDto(del.getDeliveredAt(), del.getDeliveredBy(), del.getReceivedByName(), del.getNotes()),
                inc == null ? null : inc.getId(),
                movements.findByGuideIdOrderByOccurredAtAscIdAsc(g.getId()).stream()
                        .map(m -> courierView || p.has(Permission.SHIPMENTS_OWN) && !p.has(Permission.GUIDES_VIEW_ALL)
                                ? new MovementDto(m.getId(), m.getType(), m.getType().getLabel(), m.getFromStatus(), m.getToStatus(),
                                m.getToStatus().getLabel(), null, m.getOccurredAt(), m.getLocation(), m.getNotes(), null)
                                : MovementDto.of(m))
                        .toList(),
                allowedActions(p, g, mg != null, asg != null), g.getVersion());
    }

    private static Ref ref(User u) {
        return u == null ? null : new Ref(u.getId(), u.getFullName());
    }

    private List<String> allowedActions(SalterPrincipal p, Guide g, boolean inManifest, boolean assigned) {
        List<String> a = new ArrayList<>();
        boolean own = g.getCreatedBy().getId().equals(p.userId());
        GuideStatus s = g.getStatus();
        if (s == GuideStatus.BORRADOR && (own || p.has(Permission.GUIDES_VIEW_ALL)) && p.has(Permission.GUIDES_CREATE)) a.add("EDIT");
        if (s == GuideStatus.BORRADOR && p.has(Permission.GUIDES_CONFIRM) && (own || p.has(Permission.GUIDES_VIEW_ALL))) a.add("CONFIRM");
        if (s.canTransitionTo(GuideStatus.CANCELADA) && !inManifest && (p.has(Permission.GUIDES_CANCEL) || (own && s == GuideStatus.BORRADOR))) a.add("CANCEL");
        if (s == GuideStatus.CONFIRMADA && p.has(Permission.GUIDES_CORRECT)) a.add("CORRECT");
        if (s.canTransitionTo(GuideStatus.RETENIDA) && p.has(Permission.GUIDES_HOLD)) a.add("HOLD");
        if (s == GuideStatus.RETENIDA && p.has(Permission.GUIDES_HOLD)) a.add("RELEASE");
        if (s == GuideStatus.EN_TRANSITO && p.has(Permission.RECEPTION_CONFIRM)) a.add("RECEIVE");
        boolean canDeliverStatus = s == GuideStatus.RECIBIDA_DESTINO
                || (s == GuideStatus.EN_TRANSITO && !settings.getBool("delivery.requireReception", true));
        if (canDeliverStatus && (p.has(Permission.DELIVERIES_ANY) || access.isAssignedCourier(p, g))) a.add("DELIVER");
        if ((s == GuideStatus.EN_TRANSITO || s == GuideStatus.RECIBIDA_DESTINO) && p.has(Permission.INCIDENTS_CREATE)) a.add("REPORT_INCIDENT");
        if (!s.isTerminal() && s != GuideStatus.BORRADOR && p.has(Permission.ASSIGNMENTS_MANAGE)) a.add(assigned ? "REASSIGN" : "ASSIGN");
        if (s != GuideStatus.BORRADOR && s != GuideStatus.CANCELADA) a.add("PDF");
        return a;
    }

    static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
