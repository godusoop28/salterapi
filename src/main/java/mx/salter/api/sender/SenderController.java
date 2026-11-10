package mx.salter.api.sender;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import mx.salter.api.common.PageResponse;
import mx.salter.api.sender.SenderDtos.SenderDto;
import mx.salter.api.sender.SenderDtos.SenderRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/senders")
@Tag(name = "Remitentes")
public class SenderController {
    private final SenderService service;

    public SenderController(SenderService service) {
        this.service = service;
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista remitentes. Usuarios restringidos sólo ven su remitente asignado.")
    public PageResponse<SenderDto> search(@RequestParam(required = false) String q, @RequestParam(required = false) Boolean active,
                                          @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.search(q, active, page, size);
    }

    @GetMapping("/{id}")
    public SenderDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SENDERS_MANAGE')")
    public ResponseEntity<SenderDto> create(@Valid @RequestBody SenderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SENDERS_MANAGE')")
    public SenderDto update(@PathVariable Long id, @Valid @RequestBody SenderRequest req) {
        return service.update(id, req);
    }
}
