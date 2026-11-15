package mx.salter.api.manifest;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import mx.salter.api.auth.AuthDtos.Ref;
import mx.salter.api.guide.GuideDtos.GuideSummaryDto;

public final class ManifestDtos {
    private ManifestDtos() {}

    public record ManifestRequest(
            @NotNull(message = "Indica la fecha de operación") LocalDate operationDate,
            Long shiftId,
            Long senderId,
            @Size(max = 1000) String notes,
            @NotNull @Size(max = 1000) List<Long> guideIds,
            Long version,
            /** Ruta de embarque (opcional en manifiestos manuales). Sólo puede haber uno en preparación por ruta. */
            Long shippingRouteId) {}

    public record DispatchRequest(Long courierId, @Size(max = 80) String vehicle, @Size(max = 1000) String notes,
                                  @NotNull Long version) {}

    public record ReopenRequest(@NotNull @Size(min = 3, max = 500) String reason, @NotNull Long version) {}

    /** Autorización de salida del encargado. */
    public record AuthorizeRequest(@Size(max = 1000) String notes, @NotNull Long version) {}

    /** Viaja / No viaja. Para «No viaja» el motivo es obligatorio. */
    public record TravelRequest(boolean travels, @Size(max = 500) String reason, Long version) {}

    public record DispatchDto(Instant dispatchedAt, Ref dispatchedBy, Long courierId, String courierName, String vehicle,
                              String notes, int guideCount) {}

    public record AuthorizationDto(Instant authorizedAt, Ref authorizedBy, String notes) {}

    public record ManifestSummaryDto(Long id, String number, Manifest.Status status, LocalDate operationDate, Ref shift,
                                     Ref sender, int guideCount, Ref createdBy, Instant createdAt, Instant closedAt, long version,
                                     Ref shippingRoute, boolean authorized) {}

    /** Renglón del manifiesto: guía, Viaja / No viaja y quién lo cambió. */
    public record EntryDto(Long guideId, String folio, boolean travels, boolean active, String travelReason, Ref travelChangedBy,
                           Instant travelChangedAt, int orders, Map<String, Integer> piecesByType) {}

    public record TypeTotal(String type, int quantity) {}

    /** Comparación contra el archivo/lote de origen (cuando las guías vienen de una importación). */
    public record BatchComparison(Long batchId, String fileName, String load, Integer declaredPackages, int piecesInManifest,
                                  int piecesInOtherManifests, int piecesWithoutManifest, int guidesInBatch, Integer difference) {}

    public record RouteChange(Long guideId, String folio, String from, String to, String by, Instant at, String reason) {}

    /** Resumen de revisión antes de cerrar/autorizar. Sólo cuentan como despachables las guías que viajan. */
    public record ReviewDto(int guides, int travelling, int notTravelling, int orders, int boxes, int pieces, int travellingPieces,
                            List<TypeTotal> piecesByType, List<BatchComparison> batches, List<RouteChange> routeChanges,
                            List<String> warnings) {}

    public record ManifestDto(Long id, String number, Manifest.Status status, LocalDate operationDate, Ref shift, Ref sender,
                              String notes, Ref createdBy, Instant createdAt, Instant closedAt, Ref closedBy,
                              int guideCount, int packageCount, List<GuideSummaryDto> guides, DispatchDto dispatch,
                              List<String> allowedActions, long version,
                              Ref shippingRoute, AuthorizationDto authorization, List<EntryDto> entries, ReviewDto review,
                              /** Guías que estuvieron en el manifiesto pero no viajaron (historial). */
                              List<GuideSummaryDto> notTravelledHistory) {}
}
