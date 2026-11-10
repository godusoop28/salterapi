package mx.salter.api.manifest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import mx.salter.api.common.PageResponse;
import mx.salter.api.guide.GuideDtos.GuideSummaryDto;
import mx.salter.api.manifest.ManifestDtos.DispatchRequest;
import mx.salter.api.manifest.ManifestDtos.ManifestDto;
import mx.salter.api.manifest.ManifestDtos.ManifestRequest;
import mx.salter.api.manifest.ManifestDtos.ManifestSummaryDto;
import mx.salter.api.manifest.ManifestDtos.ReopenRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manifests")
@Tag(name = "Manifiestos y salidas")
public class ManifestController {
    private final ManifestService service;
    private final ManifestPdfService pdf;

    public ManifestController(ManifestService service, ManifestPdfService pdf) {
        this.service = service;
        this.pdf = pdf;
    }

    @GetMapping("/eligible-guides")
    @PreAuthorize("hasAuthority('MANIFESTS_MANAGE')")
    @Operation(summary = "Guías confirmadas pendientes de incluir en un manifiesto.")
    public PageResponse<GuideSummaryDto> eligible(@RequestParam(required = false) Long shiftId, @RequestParam(required = false) Long senderId,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.eligible(shiftId, senderId, from, to, q, page, size);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('MANIFESTS_VIEW','MANIFESTS_MANAGE','DISPATCH_AUTHORIZE')")
    public PageResponse<ManifestSummaryDto> search(@RequestParam(required = false) Manifest.Status status,
                                                   @RequestParam(required = false) Long shiftId, @RequestParam(required = false) Long senderId,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.search(status, shiftId, senderId, from, to, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('MANIFESTS_VIEW','MANIFESTS_MANAGE','DISPATCH_AUTHORIZE')")
    public ManifestDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MANIFESTS_MANAGE')")
    public ResponseEntity<ManifestDto> create(@Valid @RequestBody ManifestRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MANIFESTS_MANAGE')")
    public ManifestDto update(@PathVariable Long id, @Valid @RequestBody ManifestRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MANIFESTS_MANAGE')")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam(required = false) Long version) {
        service.deleteDraft(id, version);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('MANIFESTS_MANAGE')")
    @Operation(summary = "Cierra el manifiesto: sus guías quedan listas para salida (aún NO salen).")
    public ManifestDto close(@PathVariable Long id, @RequestParam(required = false) Long version) {
        return service.close(id, version);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('MANIFESTS_MANAGE')")
    public ManifestDto reopen(@PathVariable Long id, @Valid @RequestBody ReopenRequest req) {
        return service.reopen(id, req.reason(), req.version());
    }

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAuthority('DISPATCH_AUTHORIZE')")
    @Operation(summary = "Autoriza la salida del manifiesto completo en una sola transacción.")
    public ManifestDto dispatch(@PathVariable Long id, @Valid @RequestBody DispatchRequest req) {
        return service.dispatch(id, req);
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyAuthority('MANIFESTS_VIEW','MANIFESTS_MANAGE','DISPATCH_AUTHORIZE')")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        ManifestPdfService.Rendered r = pdf.render(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(r.filename()).build().toString())
                .contentType(MediaType.APPLICATION_PDF).body(r.bytes());
    }
}
