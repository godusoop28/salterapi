package mx.salter.api.recipient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import mx.salter.api.common.PageResponse;
import mx.salter.api.recipient.RecipientDtos.DuplicateCheckRequest;
import mx.salter.api.recipient.RecipientDtos.DuplicateCheckResponse;
import mx.salter.api.recipient.RecipientDtos.ImportPreview;
import mx.salter.api.recipient.RecipientDtos.ImportRequest;
import mx.salter.api.recipient.RecipientDtos.ImportResult;
import mx.salter.api.recipient.RecipientDtos.RecipientDto;
import mx.salter.api.recipient.RecipientDtos.RecipientRequest;
import mx.salter.api.recipient.RecipientDtos.ValidationResult;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/recipients")
@Tag(name = "Destinatarios")
public class RecipientController {
    private final RecipientService service;
    private final RecipientImportService importer;

    public RecipientController(RecipientService service, RecipientImportService importer) {
        this.service = service;
        this.importer = importer;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('RECIPIENTS_VIEW')")
    @Operation(summary = "Busca destinatarios autorizados (sin distinguir mayúsculas ni acentos).")
    public PageResponse<RecipientDto> search(@RequestParam(required = false) Long senderId, @RequestParam(required = false) String q,
                                             @RequestParam(required = false) Boolean active,
                                             @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.search(senderId, q, active, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('RECIPIENTS_VIEW')")
    public RecipientDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('RECIPIENTS_MANAGE')")
    public ResponseEntity<RecipientDto> create(@Valid @RequestBody RecipientRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('RECIPIENTS_MANAGE')")
    public RecipientDto update(@PathVariable Long id, @Valid @RequestBody RecipientRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('RECIPIENTS_MANAGE')")
    @Operation(summary = "Baja lógica del destinatario (las guías emitidas no cambian).")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/check-duplicates")
    @PreAuthorize("hasAuthority('RECIPIENTS_VIEW')")
    public DuplicateCheckResponse checkDuplicates(@Valid @RequestBody DuplicateCheckRequest req) {
        return service.checkDuplicates(req);
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('RECIPIENTS_IMPORT')")
    @Operation(summary = "Lee un Excel/CSV y devuelve encabezados, filas y correspondencia sugerida.")
    public ImportPreview preview(@RequestPart("file") MultipartFile file) {
        return importer.preview(file);
    }

    @PostMapping("/import/validate")
    @PreAuthorize("hasAuthority('RECIPIENTS_IMPORT')")
    @Operation(summary = "Valida filas ya relacionadas con campos; marca errores y duplicados.")
    public ValidationResult validate(@Valid @RequestBody ImportRequest req) {
        return importer.validate(req);
    }

    @PostMapping("/import/commit")
    @PreAuthorize("hasAuthority('RECIPIENTS_IMPORT')")
    @Operation(summary = "Importa en una sola transacción las filas válidas (y probables duplicados si se indica).")
    public ImportResult commit(@Valid @RequestBody ImportRequest req) {
        return importer.commit(req);
    }
}
