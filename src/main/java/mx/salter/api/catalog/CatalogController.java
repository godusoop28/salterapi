package mx.salter.api.catalog;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.catalog.ConceptRepository;
import mx.salter.api.catalog.ShiftRepository;
import mx.salter.api.common.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Catálogos y configuración")
public class CatalogController {
    private final ShiftRepository shifts;
    private final ConceptRepository concepts;
    private final SettingsService settings;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;

    public CatalogController(ShiftRepository shifts, ConceptRepository concepts, SettingsService settings, AuditService audit,
                             Clock clock, ZoneId zone) {
        this.shifts = shifts;
        this.concepts = concepts;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
        this.zone = zone;
    }

    public record ShiftDto(Long id, String code, String name, LocalTime startTime, LocalTime endTime, boolean active, boolean current) {}
    public record ShiftRequest(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 80) String name,
                               @NotNull LocalTime startTime, @NotNull LocalTime endTime, Boolean active) {}
    public record ConceptDto(Long id, String name, String unit, boolean active) {}
    public record ConceptRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 30) String unit, Boolean active) {}

    @GetMapping("/shifts")
    @Transactional(readOnly = true)
    public List<ShiftDto> shifts() {
        LocalTime now = LocalTime.now(clock.withZone(zone));
        return shifts.findAllByOrderByStartTimeAsc().stream()
                .map(s -> new ShiftDto(s.getId(), s.getCode(), s.getName(), s.getStartTime(), s.getEndTime(), s.isActive(),
                        s.isActive() && s.contains(now)))
                .toList();
    }

    @PostMapping("/shifts")
    @PreAuthorize("hasAuthority('CATALOGS_MANAGE')")
    @Transactional
    public ShiftDto createShift(@Valid @RequestBody ShiftRequest req) {
        if (shifts.existsByCodeIgnoreCase(req.code())) throw ApiException.conflict("CODE_TAKEN", "Ya existe un turno con esa clave.");
        Shift s = new Shift();
        s.setCode(req.code().trim().toUpperCase());
        applyShift(s, req);
        shifts.save(s);
        audit.record("SHIFT_CREATED", "SHIFT", s.getId(), Map.of("code", s.getCode()));
        return new ShiftDto(s.getId(), s.getCode(), s.getName(), s.getStartTime(), s.getEndTime(), s.isActive(), false);
    }

    @PutMapping("/shifts/{id}")
    @PreAuthorize("hasAuthority('CATALOGS_MANAGE')")
    @Transactional
    public ShiftDto updateShift(@PathVariable Long id, @Valid @RequestBody ShiftRequest req) {
        Shift s = shifts.findById(id).orElseThrow(() -> ApiException.notFound("Turno"));
        applyShift(s, req);
        audit.record("SHIFT_UPDATED", "SHIFT", s.getId(), Map.of("code", s.getCode()));
        return new ShiftDto(s.getId(), s.getCode(), s.getName(), s.getStartTime(), s.getEndTime(), s.isActive(), false);
    }

    private static void applyShift(Shift s, ShiftRequest req) {
        if (req.startTime().equals(req.endTime())) throw ApiException.badRequest("INVALID_SHIFT", "El turno debe tener horas distintas de inicio y fin.");
        s.setName(req.name().trim());
        s.setStartTime(req.startTime());
        s.setEndTime(req.endTime());
        if (req.active() != null) s.setActive(req.active());
    }

    @GetMapping("/concepts")
    @Transactional(readOnly = true)
    public List<ConceptDto> concepts() {
        return concepts.findAllByOrderByNameAsc().stream().map(c -> new ConceptDto(c.getId(), c.getName(), c.getUnit(), c.isActive())).toList();
    }

    @PostMapping("/concepts")
    @PreAuthorize("hasAuthority('CATALOGS_MANAGE')")
    @Transactional
    public ConceptDto createConcept(@Valid @RequestBody ConceptRequest req) {
        if (concepts.existsByNameIgnoreCase(req.name().trim())) throw ApiException.conflict("NAME_TAKEN", "Ya existe ese concepto.");
        MerchandiseConcept c = new MerchandiseConcept();
        c.setName(req.name().trim());
        c.setUnit(req.unit().trim().toUpperCase());
        concepts.save(c);
        audit.record("CONCEPT_CREATED", "CONCEPT", c.getId(), Map.of("name", c.getName()));
        return new ConceptDto(c.getId(), c.getName(), c.getUnit(), c.isActive());
    }

    @PutMapping("/concepts/{id}")
    @PreAuthorize("hasAuthority('CATALOGS_MANAGE')")
    @Transactional
    public ConceptDto updateConcept(@PathVariable Long id, @Valid @RequestBody ConceptRequest req) {
        MerchandiseConcept c = concepts.findById(id).orElseThrow(() -> ApiException.notFound("Concepto"));
        c.setName(req.name().trim());
        c.setUnit(req.unit().trim().toUpperCase());
        if (req.active() != null) c.setActive(req.active());
        audit.record("CONCEPT_UPDATED", "CONCEPT", c.getId(), Map.of("name", c.getName()));
        return new ConceptDto(c.getId(), c.getName(), c.getUnit(), c.isActive());
    }

    @GetMapping("/settings")
    public Map<String, String> settings() {
        return settings.all();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public Map<String, String> updateSettings(@RequestBody Map<String, String> values) {
        return settings.update(values);
    }

    @GetMapping("/public/settings")
    public Map<String, String> publicSettings() {
        return settings.publicSettings();
    }
}
