package mx.salter.api.report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.common.ApiException;
import mx.salter.api.guide.GuideStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@Tag(name = "Dashboard y reportes")
public class ReportController {
    private final ReportService service;
    private final ReportExporter exporter;
    private final AuditService audit;

    public ReportController(ReportService service, ReportExporter exporter, AuditService audit) {
        this.service = service;
        this.exporter = exporter;
        this.audit = audit;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('DASHBOARD_VIEW','REPORTS_VIEW','REPORTS_OWN')")
    @Operation(summary = "Indicadores del periodo (por defecto, últimos 30 días).")
    public ReportService.Summary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Long senderId, @RequestParam(required = false) Long shiftId,
                                         @RequestParam(required = false) Long courierId, @RequestParam(required = false) GuideStatus status,
                                         @RequestParam(required = false) String destination) {
        return service.summary(new ReportService.Filter(from, to, senderId, shiftId, courierId, status, destination));
    }

    @GetMapping("/guides/export")
    @PreAuthorize("hasAnyAuthority('REPORTS_VIEW','REPORTS_OWN')")
    @Transactional
    @Operation(summary = "Exporta el reporte de guías en csv, xlsx o pdf.")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "csv") String format,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Long senderId, @RequestParam(required = false) Long shiftId,
                                         @RequestParam(required = false) Long courierId, @RequestParam(required = false) GuideStatus status,
                                         @RequestParam(required = false) String destination) {
        ReportService.Filter f = new ReportService.Filter(from, to, senderId, shiftId, courierId, status, destination);
        List<Map<String, Object>> rows = service.guideRows(f);
        ReportService.Summary s = service.summary(f);
        String title = "Periodo " + s.from() + " a " + s.to();
        String base = "reporte-guias-" + s.from() + "_" + s.to();
        byte[] body;
        MediaType type;
        String file;
        switch (format.toLowerCase()) {
            case "csv" -> { body = exporter.csv(rows); type = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8); file = base + ".csv"; }
            case "xlsx" -> { body = exporter.xlsx(rows, "SALTER · Reporte de guías · " + title);
                type = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); file = base + ".xlsx"; }
            case "pdf" -> { body = exporter.pdf(rows, s, title); type = MediaType.APPLICATION_PDF; file = base + ".pdf"; }
            default -> throw ApiException.badRequest("INVALID_FORMAT", "Formato no válido. Usa csv, xlsx o pdf.");
        }
        audit.record("REPORT_EXPORTED", "REPORT", null, Map.of("format", format, "rows", rows.size(), "from", s.from().toString(), "to", s.to().toString()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .contentType(type).body(body);
    }
}
