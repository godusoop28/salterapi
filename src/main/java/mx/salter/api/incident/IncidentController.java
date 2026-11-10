package mx.salter.api.incident;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import mx.salter.api.common.PageResponse;
import mx.salter.api.incident.IncidentDtos.CreateIncidentRequest;
import mx.salter.api.incident.IncidentDtos.IncidentDto;
import mx.salter.api.incident.IncidentDtos.NoteRequest;
import mx.salter.api.incident.IncidentDtos.StatusRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incidencias")
public class IncidentController {
    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('INCIDENTS_VIEW','INCIDENTS_CREATE','INCIDENTS_RESOLVE')")
    public PageResponse<IncidentDto> search(@RequestParam(required = false) Incident.Status status,
                                            @RequestParam(required = false) Incident.Severity severity,
                                            @RequestParam(required = false) Incident.Type type,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.search(status, severity, type, q, from, to, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('INCIDENTS_VIEW','INCIDENTS_CREATE','INCIDENTS_RESOLVE')")
    public IncidentDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('INCIDENTS_CREATE')")
    public ResponseEntity<IncidentDto> create(@Valid @RequestBody CreateIncidentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAnyAuthority('INCIDENTS_VIEW','INCIDENTS_CREATE','INCIDENTS_RESOLVE')")
    public IncidentDto addNote(@PathVariable Long id, @Valid @RequestBody NoteRequest req) {
        return service.addNote(id, req.text());
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('INCIDENTS_RESOLVE')")
    public IncidentDto changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
        return service.changeStatus(id, req);
    }
}
