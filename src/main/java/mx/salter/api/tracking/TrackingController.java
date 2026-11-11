package mx.salter.api.tracking;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mx.salter.api.catalog.SettingsService;
import mx.salter.api.common.ApiException;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideMovementRepository;
import mx.salter.api.guide.GuideRepository;
import mx.salter.api.guide.GuideStatus;
import mx.salter.api.guide.MovementType;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rastreo público por folio. Sólo expone estado, eventos genéricos y ciudad de destino:
 * nunca nombres, teléfonos, direcciones ni usuarios internos.
 */
@RestController
@RequestMapping("/api/public/tracking")
@Tag(name = "Rastreo público")
public class TrackingController {
    private static final Set<MovementType> PUBLIC_EVENTS = EnumSet.of(MovementType.CONFIRMACION, MovementType.MANIFIESTO,
            MovementType.DESPACHO, MovementType.RECEPCION, MovementType.ENTREGA, MovementType.INCIDENCIA,
            MovementType.INCIDENCIA_RESUELTA, MovementType.RETENCION, MovementType.LIBERACION, MovementType.CANCELACION);
    private static final int LIMIT_PER_MINUTE = 30;

    public record TrackingEvent(String type, String label, String status, Instant occurredAt) {}
    public record TrackingDto(String folio, String status, String statusLabel, String destinationCity, String destinationState,
                              int packageCount, Instant confirmedAt, List<TrackingEvent> events) {}

    private final GuideRepository guides;
    private final GuideMovementRepository movements;
    private final SettingsService settings;
    private final Clock clock;
    private final Map<String, long[]> hits = new ConcurrentHashMap<>();

    public TrackingController(GuideRepository guides, GuideMovementRepository movements, SettingsService settings, Clock clock) {
        this.guides = guides;
        this.movements = movements;
        this.settings = settings;
        this.clock = clock;
    }

    @GetMapping("/{folio}")
    @Transactional(readOnly = true)
    @Operation(summary = "Rastreo público de una guía por folio (datos mínimos).")
    public TrackingDto track(@PathVariable String folio, HttpServletRequest req) {
        rateLimit(req.getRemoteAddr());
        if (!settings.getBool("tracking.public", true)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRACKING_DISABLED", "El rastreo público no está disponible.");
        }
        if (folio == null || folio.length() > 40) throw ApiException.notFound("Guía");
        Guide g = guides.findByFolioIgnoreCase(mx.salter.api.guide.GuideService.normalizeFolio(folio))
                .filter(x -> x.getStatus() != GuideStatus.BORRADOR)
                .orElseThrow(() -> ApiException.notFound("Guía"));
        List<TrackingEvent> events = movements.findByGuideIdOrderByOccurredAtAscIdAsc(g.getId()).stream()
                .filter(m -> PUBLIC_EVENTS.contains(m.getType()))
                .map(m -> new TrackingEvent(m.getType().name(), m.getType().getLabel(), m.getToStatus().getLabel(), m.getOccurredAt()))
                .toList();
        return new TrackingDto(g.getFolio(), g.getStatus().name(), g.getStatus().getLabel(), g.getRcptCity(), g.getRcptState(),
                g.getPackageCount(), g.getConfirmedAt(), events);
    }

    private void rateLimit(String ip) {
        long minute = clock.millis() / 60_000;
        long[] slot = hits.compute(ip, (k, v) -> v == null || v[0] != minute ? new long[]{minute, 1} : new long[]{minute, v[1] + 1});
        if (hits.size() > 10_000) hits.entrySet().removeIf(e -> e.getValue()[0] != minute);
        if (slot[1] > LIMIT_PER_MINUTE) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT", "Demasiadas consultas. Intenta en un minuto.");
        }
    }
}
