package mx.salter.api.guide;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PageResponse;
import mx.salter.api.guide.GuideDtos.CorrectionRequest;
import mx.salter.api.guide.GuideDtos.CreateResult;
import mx.salter.api.guide.GuideDtos.GuideDto;
import mx.salter.api.guide.GuideDtos.GuideFilter;
import mx.salter.api.guide.GuideDtos.GuideRequest;
import mx.salter.api.guide.GuideDtos.GuideSummaryDto;
import mx.salter.api.guide.GuideDtos.MovementDto;
import mx.salter.api.guide.GuideDtos.NoteRequest;
import mx.salter.api.guide.GuideDtos.ReasonRequest;
import mx.salter.api.security.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/guides")
@Tag(name = "Guías")
public class GuideController {
    private final GuideService service;
    private final GuidePdfService pdf;

    public GuideController(GuideService service, GuidePdfService pdf) {
        this.service = service;
        this.pdf = pdf;
    }

    @GetMapping
    @Operation(summary = "Lista guías dentro del alcance del usuario, con filtros y paginación.")
    public PageResponse<GuideSummaryDto> search(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) List<GuideStatus> status,
                                                @RequestParam(required = false) Long senderId,
                                                @RequestParam(required = false) Long shiftId,
                                                @RequestParam(required = false) Long courierId,
                                                @RequestParam(required = false) Boolean mine,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                @RequestParam(required = false) Boolean withoutManifest,
                                                @RequestParam(required = false) String folio,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return service.search(new GuideFilter(q, status, senderId, shiftId, courierId, mine, from, to, withoutManifest, folio), page, size);
    }

    @GetMapping("/{id}")
    public GuideDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/by-folio/{folio}")
    @Operation(summary = "Busca una guía por folio (compatible con lectura futura por QR o código de barras).")
    public GuideDto byFolio(@PathVariable String folio) {
        return service.getByFolio(folio);
    }

    @GetMapping("/{id}/movements")
    public List<MovementDto> movements(@PathVariable Long id) {
        return service.movements(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('GUIDES_CREATE')")
    @Operation(summary = "Elabora una guía (borrador o confirmada). Reenviar el mismo clientRequestId devuelve la guía ya creada.")
    public ResponseEntity<GuideDto> create(@Valid @RequestBody GuideRequest req) {
        CreateResult r;
        try {
            r = service.create(req);
        } catch (DataIntegrityViolationException e) {
            // Dos envíos simultáneos con el mismo clientRequestId: el segundo devuelve la guía del primero.
            if (req.clientRequestId() == null) throw e;
            r = service.create(req);
            if (r.created()) throw ApiException.conflict("DUPLICATE_REQUEST", "La solicitud ya fue procesada.");
        }
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.guide());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('GUIDES_CREATE')")
    @Operation(summary = "Actualiza un borrador.")
    public GuideDto update(@PathVariable Long id, @Valid @RequestBody GuideRequest req) {
        return service.updateDraft(id, req);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('GUIDES_CONFIRM')")
    @Operation(summary = "Confirma un borrador y asigna el folio único.")
    public GuideDto confirm(@PathVariable Long id, @RequestParam(required = false) Long version) {
        return service.confirm(id, version);
    }

    @PostMapping("/{id}/cancel")
    public GuideDto cancel(@PathVariable Long id, @Valid @RequestBody ReasonRequest req) {
        return service.cancel(id, req.reason());
    }

    @PostMapping("/{id}/corrections")
    @PreAuthorize("hasAuthority('GUIDES_CORRECT')")
    @Operation(summary = "Corrección auditada de una guía confirmada (antes de quedar lista para salida).")
    public GuideDto correct(@PathVariable Long id, @Valid @RequestBody CorrectionRequest req) {
        return service.correct(id, req);
    }

    @PostMapping("/{id}/hold")
    @PreAuthorize("hasAuthority('GUIDES_HOLD')")
    public GuideDto hold(@PathVariable Long id, @Valid @RequestBody ReasonRequest req) {
        return service.hold(id, req.reason());
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('GUIDES_HOLD')")
    public GuideDto release(@PathVariable Long id, @RequestBody(required = false) NoteRequest req) {
        return service.release(id, req == null ? null : req.notes());
    }

    @PostMapping("/{id}/notes")
    @Operation(summary = "Registra una observación en el historial sin cambiar el estado.")
    public GuideDto addNote(@PathVariable Long id, @Valid @RequestBody NoteRequest req) {
        return service.addNote(id, req.notes(), req.location());
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Documento de guía con plantilla PROVISIONAL.")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        GuidePdfService.Rendered r = pdf.render(id, CurrentUser.get());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(r.filename()).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(r.bytes());
    }
}
