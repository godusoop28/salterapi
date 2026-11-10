package mx.salter.api.audit;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import mx.salter.api.common.Clocks;
import mx.salter.api.common.PageResponse;
import mx.salter.api.common.Paging;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@Tag(name = "Auditoría")
public class AuditController {
    private final AuditRepository repo;
    private final ZoneId zone;

    public AuditController(AuditRepository repo, ZoneId zone) {
        this.repo = repo;
        this.zone = zone;
    }

    public record AuditDto(Long id, Instant occurredAt, Long userId, String userEmail, String action,
                           String entityType, String entityId, String ip, Map<String, Object> details) {}

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    @Transactional(readOnly = true)
    public PageResponse<AuditDto> search(@RequestParam(required = false) String action,
                                         @RequestParam(required = false) String entityType,
                                         @RequestParam(required = false) String entityId,
                                         @RequestParam(required = false) Long userId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        Instant f = from == null ? Instant.EPOCH : Clocks.startOfDay(from, zone);
        Instant t = to == null ? Instant.parse("9999-01-01T00:00:00Z") : Clocks.endOfDayExclusive(to, zone);
        return PageResponse.of(repo.search(action, entityType, entityId, userId, f, t,
                        Paging.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"))),
                a -> new AuditDto(a.getId(), a.getOccurredAt(), a.getUserId(), a.getUserEmail(), a.getAction(),
                        a.getEntityType(), a.getEntityId(), a.getIp(), a.getDetails()));
    }
}
